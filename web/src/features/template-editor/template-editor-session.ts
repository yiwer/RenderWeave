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
import {
  normalizeTemplateEditorDisplayName,
  type TemplateEditorDisplayNameInvalidReason,
} from './template-editor-display-name';
import { inspectDesignDslWire } from './template-design-dsl-wire';
import type { StructuredTemplateImport } from './template-import';

const HISTORY_LIMIT = 100;
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

export type StructuredCommandCommitResult =
  | { state: 'applied'; session: StructuredEditorSession }
  | { state: 'no-op'; session: StructuredEditorSession }
  | {
    state: 'invalid';
    session: StructuredEditorSession;
    reason: 'CANONICAL_SIZE_EXCEEDED' | 'WORKING_COPY_INVALID';
  };

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
  const normalized = normalizeTemplateEditorDisplayName(rawValue);
  if (normalized.state === 'invalid') {
    return {
      state: 'invalid',
      reason: normalized.reason,
      session,
      message: templateDisplayNameInvalidMessage(normalized.reason),
    };
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
  const committed = commitStructuredEditorCommand(session, command);
  if (committed.state === 'no-op') {
    return { state: 'no-op', session };
  }
  if (committed.state === 'invalid') {
    const sizeExceeded = committed.reason === 'CANONICAL_SIZE_EXCEEDED';
    return {
      state: 'invalid',
      session,
      reason: sizeExceeded ? 'CANONICAL_SIZE_EXCEEDED' : 'WORKING_COPY_INVALID',
      message: sizeExceeded
        ? '编辑后的 DesignDSL 超过 16 MiB canonical 上限。'
        : '当前工作副本不能安全应用结构化编辑。',
    };
  }
  return committed;
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
  const committed = commitStructuredEditorCommand(session, command);
  if (committed.state === 'invalid' && committed.reason === 'WORKING_COPY_INVALID') {
    return invalidNodeInsertion(
      session,
      'WORKING_COPY_INVALID',
      '当前工作副本不能安全应用节点插入命令。',
    );
  }
  if (committed.state === 'invalid') {
    return invalidNodeInsertion(
      session,
      'CANONICAL_SIZE_EXCEEDED',
      '添加节点后的 DesignDSL 超过 16 MiB canonical 上限。',
    );
  }
  if (committed.state === 'no-op') {
    return invalidNodeInsertion(
      session,
      'WORKING_COPY_INVALID',
      '节点插入命令没有产生完整的结构化变更。',
    );
  }
  return committed;
}

export function undoStructuredCommand(
  session: StructuredEditorSession,
): StructuredEditorSession {
  const command = session.history.past.at(-1);
  if (!command) return session;
  let workingCopy: CanonicalDesignWorkingCopy;
  try {
    workingCopy = replayCommand(session.workingCopy, command, 'backward');
  } catch {
    return session;
  }
  return {
    ...session,
    workingCopy,
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
  let workingCopy: CanonicalDesignWorkingCopy;
  try {
    workingCopy = replayCommand(session.workingCopy, command, 'forward');
  } catch {
    return session;
  }
  return {
    ...session,
    workingCopy,
    history: structuredHistory(
      appendBounded(session.history.past, command),
      remaining,
    ),
    previewGeneration: session.previewGeneration + 1,
  };
}

/**
 * Applies one already-resolved, reversible semantic command as a single canonical history step.
 * Callers resolve user intent first; this seam owns atomic replay, closed-wire validation,
 * canonical size, history branching and preview-generation invalidation.
 */
export function commitStructuredEditorCommand(
  session: StructuredEditorSession,
  command: StructuredEditorCommand,
): StructuredCommandCommitResult {
  let nextWorkingCopy: CanonicalDesignWorkingCopy;
  try {
    nextWorkingCopy = replayCommand(session.workingCopy, command, 'forward');
  } catch {
    return { state: 'invalid', session, reason: 'WORKING_COPY_INVALID' };
  }
  if (nextWorkingCopy.canonicalDesignDsl === session.workingCopy.canonicalDesignDsl) {
    return { state: 'no-op', session };
  }
  if (textEncoder.encode(nextWorkingCopy.canonicalDesignDsl).byteLength > MAX_CANONICAL_BYTES) {
    return { state: 'invalid', session, reason: 'CANONICAL_SIZE_EXCEEDED' };
  }
  if (inspectDesignDslWire(nextWorkingCopy.designDsl).status !== 'supported') {
    return { state: 'invalid', session, reason: 'WORKING_COPY_INVALID' };
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

function templateDisplayNameInvalidMessage(
  reason: TemplateEditorDisplayNameInvalidReason,
): string {
  switch (reason) {
    case 'DISPLAY_NAME_REQUIRED':
      return 'Template 名称不能为空。';
    case 'DISPLAY_NAME_TOO_LONG':
      return 'Template 名称最多 128 个 Unicode 字符。';
    case 'DISPLAY_NAME_INVALID_UNICODE':
      return 'Template 名称包含无效 Unicode。';
  }
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
            if (command.childIndex < 0 || command.childIndex > children.length) {
              throw new Error('Insert-node history index drifted');
            }
            const next = [...children];
            next.splice(
              command.childIndex,
              0,
              cloneCanonicalRecord(command.node as Record<string, unknown>),
            );
            return next;
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
    case 'delete-node': {
      const designRoot = editableDesignRoot(workingCopy);
      const rewritten = rewriteNodeChildren(
        designRoot,
        command.parentNodeId,
        (children) => {
          if (direction === 'forward') {
            const authored = children[command.childIndex];
            if (!isRecord(authored) || authored.nodeId !== command.node.nodeId) {
              throw new Error('Delete-node history identity drifted');
            }
            return children.filter((_, index) => index !== command.childIndex);
          }
          if (command.childIndex < 0 || command.childIndex > children.length) {
            throw new Error('Delete-node history index drifted');
          }
          const next = [...children];
          next.splice(
            command.childIndex,
            0,
            cloneCanonicalRecord(command.node as Record<string, unknown>),
          );
          return next;
        },
      );
      if (!rewritten.found) throw new Error('Delete-node history parent drifted');
      return workingCopyFromDesignDsl({
        ...workingCopy.designDsl,
        designRoot: rewritten.node,
      });
    }
    case 'replace-node-shell': {
      const designRoot = editableDesignRoot(workingCopy);
      const expected = direction === 'forward' ? command.before : command.after;
      const replacement = direction === 'forward' ? command.after : command.before;
      const rewritten = rewriteNode(
        designRoot,
        command.nodeId,
        (current) => {
          const currentShell = nodeShell(current);
          if (canonicalStringifyWorkingValue(currentShell)
            !== canonicalStringifyWorkingValue(expected)) {
            throw new Error('Replace-node history shell drifted');
          }
          const next = cloneCanonicalRecord(replacement as Record<string, unknown>);
          if (Array.isArray(current.children)) next.children = current.children;
          return next;
        },
      );
      if (!rewritten.found) throw new Error('Replace-node history identity drifted');
      return workingCopyFromDesignDsl({
        ...workingCopy.designDsl,
        designRoot: rewritten.node,
      });
    }
    case 'replace-node-shells': {
      const designRoot = editableDesignRoot(workingCopy);
      if (command.replacements.length < 2) {
        throw new Error('Replace-node-shells history must contain multiple shells');
      }
      const identities = new Set<string>();
      for (const item of command.replacements) {
        if (identities.has(item.nodeId)) {
          throw new Error('Replace-node-shells history identity is duplicated');
        }
        identities.add(item.nodeId);
        const current = findNodeRecord(designRoot, item.nodeId);
        const expected = direction === 'forward' ? item.before : item.after;
        if (!current || canonicalStringifyWorkingValue(nodeShell(current))
          !== canonicalStringifyWorkingValue(expected)) {
          throw new Error('Replace-node-shells history shell drifted');
        }
      }
      let rewrittenRoot = designRoot;
      for (const item of command.replacements) {
        const replacement = direction === 'forward' ? item.after : item.before;
        const rewritten = rewriteNode(rewrittenRoot, item.nodeId, (current) => {
          const next = cloneCanonicalRecord(replacement as Record<string, unknown>);
          if (Array.isArray(current.children)) next.children = current.children;
          return next;
        });
        if (!rewritten.found) throw new Error('Replace-node-shells history identity drifted');
        rewrittenRoot = rewritten.node;
      }
      return workingCopyFromDesignDsl({
        ...workingCopy.designDsl,
        designRoot: rewrittenRoot,
      });
    }
    case 'replace-definition': {
      const expected = direction === 'forward' ? command.before : command.after;
      const replacement = direction === 'forward' ? command.after : command.before;
      const definitions = editableDefinitions(workingCopy);
      const next = replaceSetEntry(
        definitions,
        'definitionId',
        command.definitionId,
        expected,
        replacement,
        'Definition history identity drifted',
      );
      next.sort((left, right) => compareUtf8(
        String(left.definitionId),
        String(right.definitionId),
      ));
      return workingCopyFromDesignDsl({
        ...workingCopy.designDsl,
        definitions: next,
      });
    }
    case 'replace-node-binding': {
      const expected = direction === 'forward' ? command.before : command.after;
      const replacement = direction === 'forward' ? command.after : command.before;
      const designRoot = editableDesignRoot(workingCopy);
      const rewritten = rewriteNode(designRoot, command.nodeId, (current) => {
        if (!Array.isArray(current.bindings) || !current.bindings.every(isRecord)) {
          throw new Error('Binding history node has no bindings');
        }
        const bindings = replaceSetEntry(
          current.bindings,
          'bindingId',
          command.bindingId,
          expected,
          replacement,
          'Binding history identity drifted',
        );
        bindings.sort((left, right) => compareUtf8(
          String(left.bindingId),
          String(right.bindingId),
        ));
        return { ...current, bindings };
      });
      if (!rewritten.found) throw new Error('Binding history node identity drifted');
      return workingCopyFromDesignDsl({
        ...workingCopy.designDsl,
        designRoot: rewritten.node,
      });
    }
    case 'move-node': {
      const source = direction === 'forward' ? command.before : command.after;
      const destination = direction === 'forward' ? command.after : command.before;
      const designRoot = editableDesignRoot(workingCopy);
      const compensations = command.groupCompensations ?? [];
      const compensationIdentities = new Set<string>();
      for (const item of compensations) {
        if (item.nodeId === command.nodeId || compensationIdentities.has(item.nodeId)) {
          throw new Error('Move-node Group compensation identity is duplicated');
        }
        compensationIdentities.add(item.nodeId);
        const current = findNodeRecord(designRoot, item.nodeId);
        const expected = direction === 'forward' ? item.before : item.after;
        if (!current || current.kind !== 'group'
          || item.before.kind !== 'group' || item.after.kind !== 'group'
          || item.before.nodeId !== item.nodeId || item.after.nodeId !== item.nodeId
          || canonicalStringifyWorkingValue(nodeShell(current))
          !== canonicalStringifyWorkingValue(expected)) {
          throw new Error('Move-node Group compensation shell drifted');
        }
      }
      const removed = removeChildAt(
        designRoot,
        source.parentNodeId,
        source.childIndex,
        command.nodeId,
      );
      if (!removed.found || !removed.removed) {
        throw new Error('Move-node history source drifted');
      }
      const currentPlacement = isRecord(removed.removed.placement)
        ? removed.removed.placement
        : null;
      if (!currentPlacement
        || canonicalStringifyWorkingValue(currentPlacement)
          !== canonicalStringifyWorkingValue(source.placement)) {
        throw new Error('Move-node history placement drifted');
      }
      const moving = {
        ...removed.removed,
        placement: cloneCanonicalRecord(destination.placement as Record<string, unknown>),
      };
      const inserted = insertChildAt(
        removed.node,
        destination.parentNodeId,
        destination.childIndex,
        moving,
      );
      if (!inserted.found) throw new Error('Move-node history destination drifted');
      let rewrittenRoot = inserted.node;
      for (const item of compensations) {
        const replacement = direction === 'forward' ? item.after : item.before;
        const rewritten = rewriteNode(rewrittenRoot, item.nodeId, (current) => {
          const next = cloneCanonicalRecord(replacement as Record<string, unknown>);
          if (Array.isArray(current.children)) next.children = current.children;
          return next;
        });
        if (!rewritten.found) throw new Error('Move-node Group compensation identity drifted');
        rewrittenRoot = rewritten.node;
      }
      return workingCopyFromDesignDsl({
        ...workingCopy.designDsl,
        designRoot: rewrittenRoot,
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
  switch (command.kind) {
    case 'insert-node':
    case 'delete-node':
      return Object.freeze({
        ...command,
        node: deepFreeze(cloneCanonicalRecord(command.node as Record<string, unknown>)),
      });
    case 'replace-node-shell':
      return Object.freeze({
        ...command,
        before: deepFreeze(cloneCanonicalRecord(command.before as Record<string, unknown>)),
        after: deepFreeze(cloneCanonicalRecord(command.after as Record<string, unknown>)),
      });
    case 'replace-node-shells':
      return Object.freeze({
        ...command,
        replacements: Object.freeze(command.replacements.map((item) => Object.freeze({
          ...item,
          before: deepFreeze(cloneCanonicalRecord(item.before as Record<string, unknown>)),
          after: deepFreeze(cloneCanonicalRecord(item.after as Record<string, unknown>)),
        }))),
      });
    case 'replace-definition':
    case 'replace-node-binding':
      return Object.freeze({
        ...command,
        before: command.before === null
          ? null
          : deepFreeze(cloneCanonicalRecord(command.before as Record<string, unknown>)),
        after: command.after === null
          ? null
          : deepFreeze(cloneCanonicalRecord(command.after as Record<string, unknown>)),
      });
    case 'move-node':
      return Object.freeze({
        ...command,
        before: Object.freeze({
          ...command.before,
          placement: deepFreeze(cloneCanonicalRecord(
            command.before.placement as Record<string, unknown>,
          )),
        }),
        after: Object.freeze({
          ...command.after,
          placement: deepFreeze(cloneCanonicalRecord(
            command.after.placement as Record<string, unknown>,
          )),
        }),
        ...(command.groupCompensations === undefined
          ? {}
          : {
            groupCompensations: Object.freeze(command.groupCompensations.map((item) => Object.freeze({
              ...item,
              before: deepFreeze(cloneCanonicalRecord(item.before as Record<string, unknown>)),
              after: deepFreeze(cloneCanonicalRecord(item.after as Record<string, unknown>)),
            }))),
          }),
      });
    case 'set-template-display-name':
      return Object.freeze({ ...command });
  }
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

function editableDefinitions(
  workingCopy: CanonicalDesignWorkingCopy,
): Record<string, unknown>[] {
  if (!Array.isArray(workingCopy.designDsl.definitions)
    || !workingCopy.designDsl.definitions.every(isRecord)) {
    throw new Error('Structured working copy has no definitions array');
  }
  return [...workingCopy.designDsl.definitions];
}

function replaceSetEntry(
  entries: readonly Record<string, unknown>[],
  identityMember: string,
  identity: string,
  expected: Readonly<Record<string, unknown>> | null,
  replacement: Readonly<Record<string, unknown>> | null,
  driftMessage: string,
): Record<string, unknown>[] {
  const index = entries.findIndex((entry) => entry[identityMember] === identity);
  if (expected === null) {
    if (index !== -1) throw new Error(driftMessage);
  } else {
    if (index === -1 || canonicalStringifyWorkingValue(entries[index])
      !== canonicalStringifyWorkingValue(expected)) throw new Error(driftMessage);
  }
  const next = [...entries];
  if (replacement === null) {
    if (index !== -1) next.splice(index, 1);
  } else if (index === -1) {
    next.push(cloneCanonicalRecord(replacement as Record<string, unknown>));
  } else {
    next.splice(index, 1, cloneCanonicalRecord(replacement as Record<string, unknown>));
  }
  return next;
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

function rewriteNode(
  node: Record<string, unknown>,
  nodeId: string,
  rewrite: (node: Record<string, unknown>) => Record<string, unknown>,
): { node: Record<string, unknown>; found: boolean } {
  if (node.nodeId === nodeId) return { node: rewrite(node), found: true };
  if (!Array.isArray(node.children)) return { node, found: false };
  for (let index = 0; index < node.children.length; index += 1) {
    const child = node.children[index];
    if (!isRecord(child)) continue;
    const rewritten = rewriteNode(child, nodeId, rewrite);
    if (!rewritten.found) continue;
    const children = [...node.children];
    children[index] = rewritten.node;
    return { node: { ...node, children }, found: true };
  }
  return { node, found: false };
}

function removeChildAt(
  root: Record<string, unknown>,
  parentNodeId: string,
  childIndex: number,
  childNodeId: string,
): { node: Record<string, unknown>; found: boolean; removed?: Record<string, unknown> } {
  let removed: Record<string, unknown> | undefined;
  const rewritten = rewriteNodeChildren(root, parentNodeId, (children) => {
    const child = children[childIndex];
    if (!isRecord(child) || child.nodeId !== childNodeId) {
      throw new Error('Child location drifted');
    }
    removed = child;
    return children.filter((_, index) => index !== childIndex);
  });
  return { ...rewritten, ...(removed ? { removed } : {}) };
}

function insertChildAt(
  root: Record<string, unknown>,
  parentNodeId: string,
  childIndex: number,
  child: Record<string, unknown>,
): { node: Record<string, unknown>; found: boolean } {
  return rewriteNodeChildren(root, parentNodeId, (children) => {
    if (childIndex < 0 || childIndex > children.length) {
      throw new Error('Child insertion index drifted');
    }
    const next = [...children];
    next.splice(childIndex, 0, child);
    return next;
  });
}

function nodeShell(node: Record<string, unknown>): Record<string, unknown> {
  const shell = { ...node };
  delete shell.children;
  return shell;
}

function editableDesignRoot(workingCopy: CanonicalDesignWorkingCopy): Record<string, unknown> {
  const designRoot = isRecord(workingCopy.designDsl.designRoot)
    ? workingCopy.designDsl.designRoot
    : null;
  if (!designRoot) throw new Error('Structured working copy has no DesignDSL root');
  return designRoot;
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
