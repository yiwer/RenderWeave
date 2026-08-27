import { isLosslessNumber, LosslessNumber, stringify } from 'lossless-json';

import type {
  CanonicalDesignWorkingCopy,
  CanonicalTemplateBaseline,
  EditorReadiness,
  InsertNodeCommand,
  SetTemplateDisplayNameCommand,
  StructuredEditorCommand,
  StructuredEditorHistory,
  StructuredEditorSession,
} from './template-editor-model';
import type { StructuredTemplateImport } from './template-import';

const HISTORY_LIMIT = 100;
const MAX_DISPLAY_NAME_CODE_POINTS = 128;
const MAX_CANONICAL_BYTES = 16 * 1024 * 1024;
const textEncoder = new TextEncoder();

export type TemplateDisplayNameEditResult =
  | { state: 'applied'; session: StructuredEditorSession }
  | { state: 'no-op'; session: StructuredEditorSession }
  | {
    state: 'invalid';
    session: StructuredEditorSession;
    reason: 'DISPLAY_NAME_REQUIRED' | 'DISPLAY_NAME_TOO_LONG' | 'DISPLAY_NAME_INVALID_UNICODE'
      | 'CANONICAL_SIZE_EXCEEDED' | 'WORKING_COPY_INVALID';
    message: string;
  };

export type NodeInsertionEditResult =
  | { state: 'applied'; session: StructuredEditorSession }
  | {
    state: 'invalid';
    session: StructuredEditorSession;
    reason: 'PARENT_NOT_FOUND' | 'PARENT_CANNOT_HAVE_CHILDREN' | 'NODE_ID_INVALID'
      | 'NODE_ID_DUPLICATE' | 'CANONICAL_SIZE_EXCEEDED' | 'WORKING_COPY_INVALID';
    message: string;
  };

export type AuthoritativePreviewGuard =
  | { state: 'eligible'; generation: number }
  | {
    state: 'blocked';
    generation: number;
    reason: 'LOCAL_DIVERGENCE' | 'READINESS_CHECKING' | 'READINESS_UNAVAILABLE'
      | 'READINESS_INVALID';
    message: string;
  };

export type StructuredImportAdoption =
  | { state: 'adopted'; session: StructuredEditorSession }
  | { state: 'no-op'; session: StructuredEditorSession };

export function createStructuredEditorSession(
  baseline: CanonicalTemplateBaseline,
  readiness: EditorReadiness,
): StructuredEditorSession {
  const immutableBaseline = immutableBaselineCopy(baseline);
  return {
    mode: 'structured',
    baseline: immutableBaseline,
    workingCopy: Object.freeze({
      canonicalDesignDsl: immutableBaseline.canonicalDesignDsl,
      designDsl: deepFreeze(cloneCanonicalRecord(immutableBaseline.designDsl)),
    }),
    readiness,
    history: emptyHistory(),
    previewGeneration: 0,
  };
}

export function adoptStructuredTemplateImport(
  session: StructuredEditorSession,
  imported: StructuredTemplateImport,
): StructuredImportAdoption {
  if (imported.canonicalDesignDsl === session.workingCopy.canonicalDesignDsl) {
    return { state: 'no-op', session };
  }
  return {
    state: 'adopted',
    session: {
      ...session,
      workingCopy: Object.freeze({
        canonicalDesignDsl: imported.canonicalDesignDsl,
        designDsl: deepFreeze(cloneCanonicalRecord(imported.designDsl)),
      }),
      history: emptyHistory(),
      previewGeneration: session.previewGeneration + 1,
    },
  };
}

export function applyTemplateDisplayName(
  session: StructuredEditorSession,
  rawValue: string,
): TemplateDisplayNameEditResult {
  const normalized = normalizeDisplayName(rawValue);
  if (normalized.state === 'invalid') {
    return { ...normalized, session };
  }
  const before = session.workingCopy.designDsl.displayName;
  if (typeof before !== 'string') {
    return {
      state: 'invalid',
      session,
      reason: 'WORKING_COPY_INVALID',
      message: '当前工作副本缺少 Template 名称，不能应用结构化编辑。',
    };
  }

  const command: SetTemplateDisplayNameCommand = {
    kind: 'set-template-display-name',
    before,
    after: normalized.value,
  };
  const nextWorkingCopy = replayCommand(session.workingCopy, command, 'forward');
  if (nextWorkingCopy.canonicalDesignDsl === session.workingCopy.canonicalDesignDsl) {
    return { state: 'no-op', session };
  }
  if (textEncoder.encode(nextWorkingCopy.canonicalDesignDsl).byteLength > MAX_CANONICAL_BYTES) {
    return {
      state: 'invalid',
      session,
      reason: 'CANONICAL_SIZE_EXCEEDED',
      message: '编辑后的 DesignDSL 超过 16 MiB canonical 上限。',
    };
  }

  return {
    state: 'applied',
    session: {
      ...session,
      workingCopy: nextWorkingCopy,
      history: structuredHistory(appendBounded(session.history.past, command), []),
      previewGeneration: session.previewGeneration + 1,
    },
  };
}

export function applyNodeInsertion(
  session: StructuredEditorSession,
  parentNodeId: string,
  node: Readonly<Record<string, unknown>>,
): NodeInsertionEditResult {
  const nodeId = node.nodeId;
  if (typeof nodeId !== 'string' || nodeId.length === 0) {
    return invalidNodeInsertion(session, 'NODE_ID_INVALID', '新节点缺少可用的 nodeId。');
  }
  const designRoot = isRecord(session.workingCopy.designDsl.designRoot)
    ? session.workingCopy.designDsl.designRoot
    : null;
  if (!designRoot) {
    return invalidNodeInsertion(session, 'WORKING_COPY_INVALID', '当前工作副本缺少可编辑的 Canvas。');
  }
  if (findNodeRecord(designRoot, nodeId)) {
    return invalidNodeInsertion(session, 'NODE_ID_DUPLICATE', '新节点 nodeId 已存在于当前 Template。');
  }
  const parent = findNodeRecord(designRoot, parentNodeId);
  if (!parent) {
    return invalidNodeInsertion(session, 'PARENT_NOT_FOUND', '目标父节点已不在当前工作副本中。');
  }
  if (!Array.isArray(parent.children)) {
    return invalidNodeInsertion(
      session,
      'PARENT_CANNOT_HAVE_CHILDREN',
      '目标节点的 ContentModel 不允许插入子节点。',
    );
  }

  const command: InsertNodeCommand = {
    kind: 'insert-node',
    parentNodeId,
    childIndex: parent.children.length,
    node,
  };
  let nextWorkingCopy: CanonicalDesignWorkingCopy;
  try {
    nextWorkingCopy = replayCommand(session.workingCopy, command, 'forward');
  } catch {
    return invalidNodeInsertion(
      session,
      'WORKING_COPY_INVALID',
      '当前工作副本不能安全应用节点插入命令。',
    );
  }
  if (textEncoder.encode(nextWorkingCopy.canonicalDesignDsl).byteLength > MAX_CANONICAL_BYTES) {
    return invalidNodeInsertion(
      session,
      'CANONICAL_SIZE_EXCEEDED',
      '添加节点后的 DesignDSL 超过 16 MiB canonical 上限。',
    );
  }
  return {
    state: 'applied',
    session: {
      ...session,
      workingCopy: nextWorkingCopy,
      history: structuredHistory(appendBounded(session.history.past, command), []),
      previewGeneration: session.previewGeneration + 1,
    },
  };
}

export function undoStructuredCommand(
  session: StructuredEditorSession,
): StructuredEditorSession {
  const command = session.history.past.at(-1);
  if (!command) return session;
  return {
    ...session,
    workingCopy: replayCommand(session.workingCopy, command, 'backward'),
    history: structuredHistory(
      session.history.past.slice(0, -1),
      [command, ...session.history.future],
    ),
    previewGeneration: session.previewGeneration + 1,
  };
}

export function redoStructuredCommand(
  session: StructuredEditorSession,
): StructuredEditorSession {
  const [command, ...remaining] = session.history.future;
  if (!command) return session;
  return {
    ...session,
    workingCopy: replayCommand(session.workingCopy, command, 'forward'),
    history: structuredHistory(
      appendBounded(session.history.past, command),
      remaining,
    ),
    previewGeneration: session.previewGeneration + 1,
  };
}

export function updateStructuredReadiness(
  session: StructuredEditorSession,
  readiness: EditorReadiness,
): StructuredEditorSession {
  if (sameReadiness(session.readiness, readiness)) return session;
  return { ...session, readiness };
}

export function isCanonicalDirty(session: StructuredEditorSession): boolean {
  return session.workingCopy.canonicalDesignDsl !== session.baseline.canonicalDesignDsl;
}

export function authoritativePreviewGuard(
  session: StructuredEditorSession,
): AuthoritativePreviewGuard {
  const generation = session.previewGeneration;
  if (isCanonicalDirty(session)) {
    return {
      state: 'blocked',
      generation,
      reason: 'LOCAL_DIVERGENCE',
      message: '本地草稿尚未成为 current，权威预览不可用。',
    };
  }
  if (session.readiness.state === 'checking') {
    return {
      state: 'blocked',
      generation,
      reason: 'READINESS_CHECKING',
      message: '权威 readiness 正在重检。',
    };
  }
  if (session.readiness.state === 'unavailable') {
    return {
      state: 'blocked',
      generation,
      reason: 'READINESS_UNAVAILABLE',
      message: '权威 readiness 暂不可用。',
    };
  }
  if (session.readiness.value === 'INVALID') {
    return {
      state: 'blocked',
      generation,
      reason: 'READINESS_INVALID',
      message: '当前 revision 的权威 readiness 为 INVALID。',
    };
  }
  return { state: 'eligible', generation };
}

export function canonicalStringifyWorkingValue(value: unknown): string {
  // E1 guarantees that every scalar is already Java-authority canonical. E2 only
  // replaces a string member, so this operation restores canonical member order
  // while preserving lossless decimal and int64 tokens exactly.
  const canonical = stringify(sortCanonicalValue(value));
  if (canonical === undefined) {
    throw new Error('Canonical working value could not be serialized');
  }
  return canonical;
}

function normalizeDisplayName(rawValue: string):
  | { state: 'valid'; value: string }
  | {
    state: 'invalid';
    reason: 'DISPLAY_NAME_REQUIRED' | 'DISPLAY_NAME_TOO_LONG' | 'DISPLAY_NAME_INVALID_UNICODE';
    message: string;
  } {
  const value = javaTrim(rawValue);
  if (!hasOnlyUnicodeScalars(value)) {
    return {
      state: 'invalid',
      reason: 'DISPLAY_NAME_INVALID_UNICODE',
      message: 'Template 名称包含无效 Unicode。',
    };
  }
  const codePoints = Array.from(value).length;
  if (codePoints === 0) {
    return {
      state: 'invalid',
      reason: 'DISPLAY_NAME_REQUIRED',
      message: 'Template 名称不能为空。',
    };
  }
  if (codePoints > MAX_DISPLAY_NAME_CODE_POINTS) {
    return {
      state: 'invalid',
      reason: 'DISPLAY_NAME_TOO_LONG',
      message: 'Template 名称最多 128 个 Unicode 字符。',
    };
  }
  return { state: 'valid', value };
}

function javaTrim(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value.charCodeAt(start) <= 0x20) start += 1;
  while (end > start && value.charCodeAt(end - 1) <= 0x20) end -= 1;
  return value.slice(start, end);
}

function hasOnlyUnicodeScalars(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const current = value.charCodeAt(index);
    if (current >= 0xd800 && current <= 0xdbff) {
      if (index + 1 >= value.length) return false;
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) return false;
      index += 1;
    } else if (current >= 0xdc00 && current <= 0xdfff) {
      return false;
    }
  }
  return true;
}

function replayCommand(
  workingCopy: CanonicalDesignWorkingCopy,
  command: StructuredEditorCommand,
  direction: 'forward' | 'backward',
): CanonicalDesignWorkingCopy {
  switch (command.kind) {
    case 'set-template-display-name': {
      const displayName = direction === 'forward' ? command.after : command.before;
      const designDsl = {
        ...workingCopy.designDsl,
        displayName,
      };
      const sorted = sortCanonicalValue(designDsl) as Record<string, unknown>;
      return Object.freeze({
        canonicalDesignDsl: canonicalStringifyWorkingValue(sorted),
        designDsl: deepFreeze(sorted),
      });
    }
    case 'insert-node': {
      const designRoot = isRecord(workingCopy.designDsl.designRoot)
        ? workingCopy.designDsl.designRoot
        : null;
      if (!designRoot) throw new Error('Structured working copy has no DesignDSL root');
      const rewritten = rewriteNodeChildren(
        designRoot,
        command.parentNodeId,
        (children) => {
          if (direction === 'forward') {
            if (command.childIndex !== children.length) {
              throw new Error('Insert-node history index drifted');
            }
            return [
              ...children,
              cloneCanonicalRecord(command.node as Record<string, unknown>),
            ];
          }
          const authored = children[command.childIndex];
          if (!isRecord(authored) || authored.nodeId !== command.node.nodeId) {
            throw new Error('Insert-node history identity drifted');
          }
          return children.filter((_, index) => index !== command.childIndex);
        },
      );
      if (!rewritten.found) throw new Error('Insert-node history parent drifted');
      return workingCopyFromDesignDsl({
        ...workingCopy.designDsl,
        designRoot: rewritten.node,
      });
    }
  }
}

function immutableBaselineCopy(
  baseline: CanonicalTemplateBaseline,
): CanonicalTemplateBaseline {
  return Object.freeze({
    ...baseline,
    staticSchema: Object.freeze({ ...baseline.staticSchema }),
    designDsl: deepFreeze(cloneCanonicalRecord(baseline.designDsl)),
  });
}

function emptyHistory(): StructuredEditorHistory {
  return structuredHistory([], []);
}

function structuredHistory(
  past: readonly StructuredEditorCommand[],
  future: readonly StructuredEditorCommand[],
): StructuredEditorHistory {
  return Object.freeze({
    past: Object.freeze([...past]),
    future: Object.freeze([...future]),
  });
}

function appendBounded(
  commands: readonly StructuredEditorCommand[],
  command: StructuredEditorCommand,
): readonly StructuredEditorCommand[] {
  return [...commands, freezeStructuredCommand(command)].slice(-HISTORY_LIMIT);
}

function freezeStructuredCommand(command: StructuredEditorCommand): StructuredEditorCommand {
  if (command.kind === 'insert-node') {
    return Object.freeze({
      ...command,
      node: deepFreeze(cloneCanonicalRecord(command.node as Record<string, unknown>)),
    });
  }
  return Object.freeze({ ...command });
}

function workingCopyFromDesignDsl(
  designDsl: Record<string, unknown>,
): CanonicalDesignWorkingCopy {
  const sorted = sortCanonicalValue(designDsl) as Record<string, unknown>;
  return Object.freeze({
    canonicalDesignDsl: canonicalStringifyWorkingValue(sorted),
    designDsl: deepFreeze(sorted),
  });
}

function findNodeRecord(
  node: Record<string, unknown>,
  nodeId: string,
): Record<string, unknown> | null {
  if (node.nodeId === nodeId) return node;
  if (!Array.isArray(node.children)) return null;
  for (const child of node.children) {
    if (!isRecord(child)) continue;
    const found = findNodeRecord(child, nodeId);
    if (found) return found;
  }
  return null;
}

function rewriteNodeChildren(
  node: Record<string, unknown>,
  parentNodeId: string,
  rewrite: (children: readonly unknown[]) => readonly unknown[],
): { node: Record<string, unknown>; found: boolean } {
  if (node.nodeId === parentNodeId) {
    if (!Array.isArray(node.children)) throw new Error('Target parent has no children array');
    return { node: { ...node, children: [...rewrite(node.children)] }, found: true };
  }
  if (!Array.isArray(node.children)) return { node, found: false };
  for (let index = 0; index < node.children.length; index += 1) {
    const child = node.children[index];
    if (!isRecord(child)) continue;
    const rewritten = rewriteNodeChildren(child, parentNodeId, rewrite);
    if (!rewritten.found) continue;
    const children = [...node.children];
    children[index] = rewritten.node;
    return { node: { ...node, children }, found: true };
  }
  return { node, found: false };
}

function invalidNodeInsertion(
  session: StructuredEditorSession,
  reason: Extract<NodeInsertionEditResult, { state: 'invalid' }>['reason'],
  message: string,
): NodeInsertionEditResult {
  return { state: 'invalid', session, reason, message };
}

function cloneCanonicalRecord(value: Record<string, unknown>): Record<string, unknown> {
  return cloneCanonicalValue(value) as Record<string, unknown>;
}

function cloneCanonicalValue(value: unknown): unknown {
  if (isLosslessNumber(value)) return new LosslessNumber(value.toString());
  if (Array.isArray(value)) return value.map(cloneCanonicalValue);
  if (isRecord(value)) {
    return Object.fromEntries(
      Object.entries(value).map(([key, child]) => [key, cloneCanonicalValue(child)]),
    );
  }
  return value;
}

function sortCanonicalValue(value: unknown): unknown {
  if (isLosslessNumber(value)) return new LosslessNumber(value.toString());
  if (Array.isArray(value)) return value.map(sortCanonicalValue);
  if (isRecord(value)) {
    return Object.fromEntries(
      Object.keys(value)
        .sort(compareUtf8)
        .map((key) => [key, sortCanonicalValue(value[key])]),
    );
  }
  return value;
}

function compareUtf8(left: string, right: string): number {
  const leftBytes = textEncoder.encode(left);
  const rightBytes = textEncoder.encode(right);
  const length = Math.min(leftBytes.length, rightBytes.length);
  for (let index = 0; index < length; index += 1) {
    const difference = (leftBytes[index] ?? 0) - (rightBytes[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return leftBytes.length - rightBytes.length;
}

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  if (Array.isArray(value)) {
    for (const child of value) deepFreeze(child);
  } else {
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return Object.freeze(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && !isLosslessNumber(value);
}

function sameReadiness(left: EditorReadiness, right: EditorReadiness): boolean {
  if (left.state !== right.state) return false;
  if (left.state === 'checking' && right.state === 'checking') return true;
  if (left.state === 'checked' && right.state === 'checked') return left.value === right.value;
  if (left.state === 'unavailable' && right.state === 'unavailable') {
    return left.message === right.message;
  }
  return false;
}
