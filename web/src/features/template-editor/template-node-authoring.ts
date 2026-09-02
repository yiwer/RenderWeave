import {
  objectOrNull,
  type StructuredEditorSession,
} from './template-editor-model';
import { executeTemplateEditorCommand } from './template-editor-commands';
import {
  expectedTemplateChildPlacement,
  isCoreTemplateAuthoringParentKind,
  isTemplateDesignContainerKind,
} from './template-editor-node-contract';
import { applyNodeInsertion } from './template-editor-session';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export type InsertableTemplateNodeKind = 'rect' | 'frame' | 'stack';

export interface TemplateNodeInsertionIntent {
  kind: InsertableTemplateNodeKind;
  selectedNodeId?: string;
}

export type TemplateNodeInsertionResult =
  | {
    state: 'applied';
    session: StructuredEditorSession;
    nodeId: string;
    parentNodeId: string;
  }
  | {
    state: 'rejected';
    session: StructuredEditorSession;
    reason: 'NODE_ID_UNAVAILABLE' | 'NODE_ID_INVALID' | 'NODE_ID_DUPLICATE'
      | 'EDIT_PARENT_UNAVAILABLE' | 'CANONICAL_SIZE_EXCEEDED' | 'WORKING_COPY_INVALID';
    message: string;
  };

export function insertTemplateNode(
  session: StructuredEditorSession,
  intent: TemplateNodeInsertionIntent,
  createNodeId: () => string = defaultNodeId,
): TemplateNodeInsertionResult {
  const root = objectOrNull(session.workingCopy.designDsl.designRoot);
  if (!root) {
    return rejected(session, 'WORKING_COPY_INVALID', '当前工作副本缺少可编辑的 Canvas。');
  }
  const path = intent.selectedNodeId ? findNodePath(root, intent.selectedNodeId) : null;
  const parent = [...(path ?? [root])]
    .reverse()
    .find((node) => isContainer(node));
  if (!parent || typeof parent.nodeId !== 'string' || typeof parent.kind !== 'string') {
    return rejected(session, 'EDIT_PARENT_UNAVAILABLE', '当前选择没有可承载新节点的父级。');
  }

  let nodeId: string;
  try {
    nodeId = createNodeId().toLowerCase();
  } catch {
    return rejected(session, 'NODE_ID_UNAVAILABLE', '浏览器未能生成新节点 identity。');
  }
  if (!UUID_V4.test(nodeId)) {
    return rejected(session, 'NODE_ID_INVALID', '浏览器生成的 nodeId 不是 canonical lowercase UUID v4。');
  }
  if (findNodePath(root, nodeId)) {
    return rejected(session, 'NODE_ID_DUPLICATE', '浏览器生成的 nodeId 已存在，请重试。');
  }

  if (isCoreTemplateAuthoringParentKind(parent.kind)) {
    const result = executeTemplateEditorCommand(
      session,
      { operation: 'insert', nodeKind: intent.kind, parentNodeId: parent.nodeId },
      { createNodeId: () => nodeId },
    );
    if (result.state !== 'applied') {
      const reason = result.state === 'rejected' && result.code === 'CANONICAL_SIZE_EXCEEDED'
        ? 'CANONICAL_SIZE_EXCEEDED'
        : result.state === 'rejected' && result.code === 'NODE_ID_DUPLICATE'
          ? 'NODE_ID_DUPLICATE'
          : result.state === 'rejected' && result.code === 'NODE_ID_INVALID'
            ? 'NODE_ID_INVALID'
            : result.state === 'rejected'
                && (result.code === 'PARENT_NOT_FOUND'
                  || result.code === 'PARENT_CANNOT_HAVE_CHILDREN')
              ? 'EDIT_PARENT_UNAVAILABLE'
              : 'WORKING_COPY_INVALID';
      return rejected(session, reason, result.message);
    }
    return {
      state: 'applied',
      session: result.session,
      nodeId,
      parentNodeId: parent.nodeId,
    };
  }

  if (intent.kind !== 'rect') {
    return rejected(
      session,
      'EDIT_PARENT_UNAVAILABLE',
      'Frame 与 Stack 的首批插入只支持 Canvas、Frame 或 Stack 父级。',
    );
  }

  const node = buildRectNode(nodeId, countKind(root, intent.kind) + 1, parent.kind);
  const applied = applyNodeInsertion(session, parent.nodeId, node);
  if (applied.state === 'invalid') {
    const reason = applied.reason === 'CANONICAL_SIZE_EXCEEDED'
      ? 'CANONICAL_SIZE_EXCEEDED'
      : applied.reason === 'NODE_ID_DUPLICATE'
        ? 'NODE_ID_DUPLICATE'
        : applied.reason === 'PARENT_NOT_FOUND' || applied.reason === 'PARENT_CANNOT_HAVE_CHILDREN'
          ? 'EDIT_PARENT_UNAVAILABLE'
          : 'WORKING_COPY_INVALID';
    return rejected(session, reason, applied.message);
  }
  return {
    state: 'applied',
    session: applied.session,
    nodeId,
    parentNodeId: parent.nodeId,
  };
}

function defaultNodeId(): string {
  return globalThis.crypto.randomUUID().toLowerCase();
}

function buildRectNode(
  nodeId: string,
  ordinal: number,
  parentKind: string,
): Readonly<Record<string, unknown>> {
  return {
    nodeId,
    kind: 'rect',
    displayName: `矩形 ${ordinal}`,
    bindings: [],
    placement: placementFor(parentKind),
    fill: { color: '#2563EBFF' },
  };
}

// One inch lowers to exactly 72 pt, so the default Rect remains pixel-aligned at integral DPI.
const RENDERABLE_RECT_SIZE_MM = 25.4;

function placementFor(parentKind: string): Readonly<Record<string, unknown>> {
  const size = {
    widthMode: 'FIXED',
    widthMm: RENDERABLE_RECT_SIZE_MM,
    heightMode: 'FIXED',
    heightMm: RENDERABLE_RECT_SIZE_MM,
  };
  const placement = expectedTemplateChildPlacement(parentKind);
  if (placement === 'STACK') return { type: placement, ...size };
  if (placement === 'GRID') return { type: placement, ...size, row: 0, column: 0 };
  if (placement === 'PACK') return { type: placement, ...size };
  return {
    type: placement ?? 'ABSOLUTE',
    xMm: RENDERABLE_RECT_SIZE_MM,
    yMm: RENDERABLE_RECT_SIZE_MM,
    ...size,
  };
}

function isContainer(node: Record<string, unknown>): boolean {
  return isTemplateDesignContainerKind(node.kind)
    && Array.isArray(node.children);
}

function findNodePath(
  node: Record<string, unknown>,
  nodeId: string,
  ancestors: readonly Record<string, unknown>[] = [],
): Record<string, unknown>[] | null {
  const path = [...ancestors, node];
  if (node.nodeId === nodeId) return path;
  if (!Array.isArray(node.children)) return null;
  for (const child of node.children) {
    const childNode = objectOrNull(child);
    if (!childNode) continue;
    const found = findNodePath(childNode, nodeId, path);
    if (found) return found;
  }
  return null;
}

function countKind(node: Record<string, unknown>, kind: string): number {
  let count = node.kind === kind ? 1 : 0;
  if (!Array.isArray(node.children)) return count;
  for (const child of node.children) {
    const childNode = objectOrNull(child);
    if (childNode) count += countKind(childNode, kind);
  }
  return count;
}

function rejected(
  session: StructuredEditorSession,
  reason: Extract<TemplateNodeInsertionResult, { state: 'rejected' }>['reason'],
  message: string,
): TemplateNodeInsertionResult {
  return { state: 'rejected', session, reason, message };
}
