import type {
  DeleteNodeCommand,
  InsertNodeCommand,
  MoveNodeCommand,
  ReplaceNodeShellCommand,
  StructuredEditorCommand,
  StructuredEditorSession,
} from './template-editor-model';
import { objectOrNull } from './template-editor-model';
import {
  finiteTemplateNumber,
  positiveTemplateNumber,
  sameTemplateNumber,
} from './template-editor-numbers';
import { normalizeTemplateEditorDisplayName } from './template-editor-display-name';
import {
  isCoreTemplateAuthoringKind,
  isCoreTemplateAuthoringParentKind,
} from './template-editor-node-contract';
import { commitStructuredEditorCommand } from './template-editor-session';
import {
  proveTemplateStructureCommandSafety,
  type TemplateStructureSafetyProblemCode,
} from './template-editor-structure-safety';
import {
  buildTemplateShapePresetNode,
  buildTemplateVisualNode,
  updateTemplateVisualNodeProperty,
  type TemplateShapePreset,
  type TemplateVisualLeafKind,
  type TemplateVisualPropertyChange,
} from './template-editor-visual-authoring';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const COLOR = /^#[0-9A-Fa-f]{8}$/;

export type CoreInsertableNodeKind = 'frame' | 'stack' | TemplateVisualLeafKind;
export type TemplateTreeDropPosition = 'before' | 'into' | 'after';
export type TemplateSiblingOrder = 'front' | 'forward' | 'backward' | 'back';

export type TemplateEditorCommandIntent =
  | {
    operation: 'insert';
    nodeKind: CoreInsertableNodeKind;
    parentNodeId: string;
    at?: { xMm: number; yMm: number };
    assetId?: string;
    shapePreset?: TemplateShapePreset;
  }
  | { operation: 'rename'; nodeId: string; displayName: string }
  | { operation: 'delete'; nodeId: string }
  | {
    operation: 'move-tree';
    nodeId: string;
    targetNodeId: string;
    position: TemplateTreeDropPosition;
    at?: { xMm: number; yMm: number };
  }
  | { operation: 'reorder'; nodeId: string; order: TemplateSiblingOrder }
  | {
    operation: 'set-geometry';
    nodeId: string;
    geometry: { xMm: number; yMm: number; widthMm?: number; heightMm?: number };
  }
  | {
    operation: 'set-property';
    nodeId: string;
    property: 'fillColor' | 'backgroundColor' | 'clipContent' | 'direction' | 'gapMm'
      | 'canvasWidthMm' | 'canvasHeightMm' | TemplateVisualPropertyChange['property'];
    value: unknown;
  };

export interface TemplateEditorCommandOptions {
  createNodeId?: () => string;
}

export type TemplateEditorCommandResult =
  | {
    state: 'applied';
    session: StructuredEditorSession;
    affectedNodeIds: readonly string[];
    message: string;
  }
  | { state: 'no-op'; session: StructuredEditorSession; message: string }
  | {
    state: 'rejected';
    session: StructuredEditorSession;
    code: TemplateEditorCommandProblemCode;
    message: string;
    nodeId?: string;
    pointer?: string;
  };

export type TemplateEditorCommandProblemCode =
  | 'NODE_NOT_FOUND'
  | 'NODE_KIND_UNSUPPORTED'
  | 'NODE_ID_UNAVAILABLE'
  | 'NODE_ID_INVALID'
  | 'NODE_ID_DUPLICATE'
  | 'ASSET_REQUIRED'
  | 'ASSET_ID_INVALID'
  | 'PARENT_NOT_FOUND'
  | 'PARENT_CANNOT_HAVE_CHILDREN'
  | 'CANVAS_MUTATION_FORBIDDEN'
  | 'TREE_CYCLE'
  | 'TREE_TARGET_INVALID'
  | 'PLACEMENT_CONVERSION_INVALID'
  | 'GEOMETRY_INVALID'
  | 'PROPERTY_INVALID'
  | 'DISPLAY_NAME_INVALID'
  | 'CANONICAL_SIZE_EXCEEDED'
  | 'WORKING_COPY_INVALID'
  | TemplateStructureSafetyProblemCode;

interface LocatedNode {
  readonly node: Record<string, unknown>;
  readonly parent: Record<string, unknown> | null;
  readonly childIndex: number;
}

export function executeTemplateEditorCommand(
  session: StructuredEditorSession,
  intent: TemplateEditorCommandIntent,
  options: TemplateEditorCommandOptions = {},
): TemplateEditorCommandResult {
  const root = objectOrNull(session.workingCopy.designDsl.designRoot);
  if (!root) return rejected(session, 'WORKING_COPY_INVALID', '当前工作副本缺少可编辑的 Canvas。');

  let resolved:
    | { state: 'command'; command: StructuredEditorCommand; affectedNodeIds: string[]; message: string }
    | Extract<TemplateEditorCommandResult, { state: 'no-op' | 'rejected' }>;
  switch (intent.operation) {
    case 'insert':
      resolved = resolveInsert(session, root, intent, options);
      break;
    case 'rename':
      resolved = resolveRename(session, root, intent.nodeId, intent.displayName);
      break;
    case 'delete':
      resolved = resolveDelete(session, root, intent.nodeId);
      break;
    case 'move-tree':
      resolved = resolveTreeMove(session, root, intent);
      break;
    case 'reorder':
      resolved = resolveReorder(session, root, intent.nodeId, intent.order);
      break;
    case 'set-geometry':
      resolved = resolveGeometry(session, root, intent.nodeId, intent.geometry);
      break;
    case 'set-property':
      resolved = resolveProperty(
        session,
        root,
        intent.nodeId,
        intent.property,
        intent.value,
      );
  }
  if (resolved.state !== 'command') return resolved;

  const committed = commitStructuredEditorCommand(session, resolved.command);
  if (committed.state === 'no-op') {
    return { state: 'no-op', session, message: '该命令没有改变 canonical working copy。' };
  }
  if (committed.state === 'invalid') {
    return committed.reason === 'CANONICAL_SIZE_EXCEEDED'
      ? rejected(session, 'CANONICAL_SIZE_EXCEEDED', '命令结果超过 16 MiB canonical 上限。')
      : rejected(session, 'WORKING_COPY_INVALID', '命令结果不是完整受支持的 DesignDSL wire。');
  }
  const structureSafety = proveTemplateStructureCommandSafety(
    committed.session.workingCopy.designDsl,
    resolved.command,
  );
  if (structureSafety.state === 'rejected') {
    return rejected(
      session,
      structureSafety.code,
      structureSafety.message,
      { nodeId: structureSafety.nodeId, pointer: structureSafety.pointer },
    );
  }
  return {
    state: 'applied',
    session: committed.session,
    affectedNodeIds: Object.freeze([...resolved.affectedNodeIds]),
    message: resolved.message,
  };
}

function resolveInsert(
  session: StructuredEditorSession,
  root: Record<string, unknown>,
  intent: Extract<TemplateEditorCommandIntent, { operation: 'insert' }>,
  options: TemplateEditorCommandOptions,
): { state: 'command'; command: InsertNodeCommand; affectedNodeIds: string[]; message: string }
  | Extract<TemplateEditorCommandResult, { state: 'rejected' }> {
  const parent = findNode(root, intent.parentNodeId)?.node;
  if (!parent) return rejected(session, 'PARENT_NOT_FOUND', '目标父节点不在当前 working copy 中。');
  if (!canAcceptCoreChildren(parent)) {
    return rejected(session, 'PARENT_CANNOT_HAVE_CHILDREN', '目标节点不能承载首批核心节点。');
  }
  let nodeId: string;
  try {
    nodeId = (options.createNodeId ?? defaultNodeId)().toLowerCase();
  } catch {
    return rejected(session, 'NODE_ID_UNAVAILABLE', '浏览器未能生成新节点 identity。');
  }
  if (!UUID_V4.test(nodeId)) {
    return rejected(session, 'NODE_ID_INVALID', '浏览器生成的 nodeId 不是 canonical UUID v4。');
  }
  if (findNode(root, nodeId)) {
    return rejected(session, 'NODE_ID_DUPLICATE', '浏览器生成的 nodeId 已存在。');
  }
  const ordinal = countKind(root, intent.nodeKind) + 1;
  const built = defaultNode(intent, nodeId, ordinal);
  if (built.state === 'rejected') {
    const code = built.code === 'ASSET_ID_REQUIRED'
      ? 'ASSET_REQUIRED'
      : built.code === 'ASSET_ID_INVALID'
        ? 'ASSET_ID_INVALID'
        : 'PROPERTY_INVALID';
    return rejected(session, code, built.message);
  }
  const authoredPlacement = objectOrNull(built.node.placement);
  const placement = authoredPlacement
    ? placementForParent(stringMember(parent, 'kind'), placementSize(authoredPlacement), intent.at)
    : null;
  if (!placement) {
    return rejected(session, 'PLACEMENT_CONVERSION_INVALID', '目标父级没有可用的 placement 合同。');
  }
  const node = { ...built.node, placement };
  const children = parent.children;
  if (!Array.isArray(children)) {
    return rejected(session, 'PARENT_CANNOT_HAVE_CHILDREN', '目标节点没有 children[]。');
  }
  return {
    state: 'command',
    command: {
      kind: 'insert-node',
      parentNodeId: intent.parentNodeId,
      childIndex: children.length,
      node,
    },
    affectedNodeIds: [nodeId, intent.parentNodeId],
    message: `已添加${kindLabel(intent.nodeKind)}。`,
  };
}

function resolveRename(
  session: StructuredEditorSession,
  root: Record<string, unknown>,
  nodeId: string,
  rawDisplayName: string,
): { state: 'command'; command: ReplaceNodeShellCommand; affectedNodeIds: string[]; message: string }
  | Extract<TemplateEditorCommandResult, { state: 'rejected' }> {
  const located = findNode(root, nodeId);
  if (!located) return rejected(session, 'NODE_NOT_FOUND', '待重命名节点不在当前 working copy 中。');
  if (located.parent === null) {
    return rejected(session, 'CANVAS_MUTATION_FORBIDDEN', 'Canvas 根名称不由结构树重命名命令修改。');
  }
  if (!isCoreNode(located.node)) {
    return rejected(session, 'NODE_KIND_UNSUPPORTED', '该节点尚未进入核心 authoring slice。');
  }
  const displayName = normalizeTemplateEditorDisplayName(rawDisplayName).value;
  if (displayName === null) {
    return rejected(session, 'DISPLAY_NAME_INVALID', '节点名称必须是 1..128 个有效 Unicode 字符。');
  }
  const before = nodeShell(located.node);
  const after = { ...before, displayName };
  return replaceNodeCommand(nodeId, before, after, `已重命名为“${displayName}”。`);
}

function resolveDelete(
  session: StructuredEditorSession,
  root: Record<string, unknown>,
  nodeId: string,
): { state: 'command'; command: DeleteNodeCommand; affectedNodeIds: string[]; message: string }
  | Extract<TemplateEditorCommandResult, { state: 'rejected' }> {
  const located = findNode(root, nodeId);
  if (!located) return rejected(session, 'NODE_NOT_FOUND', '待删除节点不在当前 working copy 中。');
  if (!located.parent || typeof located.parent.nodeId !== 'string') {
    return rejected(session, 'CANVAS_MUTATION_FORBIDDEN', 'Canvas 是唯一 DesignRoot，不能删除。');
  }
  if (!isCoreNode(located.node)) {
    return rejected(session, 'NODE_KIND_UNSUPPORTED', '该节点尚未进入核心 authoring slice。');
  }
  return {
    state: 'command',
    command: {
      kind: 'delete-node',
      parentNodeId: located.parent.nodeId,
      childIndex: located.childIndex,
      node: located.node,
    },
    affectedNodeIds: [nodeId, located.parent.nodeId],
    message: '已删除节点及其 authored subtree。',
  };
}

function resolveTreeMove(
  session: StructuredEditorSession,
  root: Record<string, unknown>,
  intent: Extract<TemplateEditorCommandIntent, { operation: 'move-tree' }>,
): { state: 'command'; command: MoveNodeCommand; affectedNodeIds: string[]; message: string }
  | Extract<TemplateEditorCommandResult, { state: 'no-op' | 'rejected' }> {
  const source = findNode(root, intent.nodeId);
  const target = findNode(root, intent.targetNodeId);
  if (!source || !target) {
    return rejected(session, 'TREE_TARGET_INVALID', '移动源或目标已不在当前 working copy 中。');
  }
  if (!source.parent || typeof source.parent.nodeId !== 'string') {
    return rejected(session, 'CANVAS_MUTATION_FORBIDDEN', 'Canvas 是唯一 DesignRoot，不能移动。');
  }
  if (!isCoreNode(source.node)) {
    return rejected(session, 'NODE_KIND_UNSUPPORTED', '该节点尚未进入核心 authoring slice。');
  }

  let destinationParent: Record<string, unknown> | null;
  let rawIndex: number;
  if (intent.position === 'into') {
    destinationParent = target.node;
    if (!canAcceptCoreChildren(destinationParent)) {
      return rejected(session, 'PARENT_CANNOT_HAVE_CHILDREN', '目标节点不能接收子节点。');
    }
    rawIndex = Array.isArray(destinationParent.children) ? destinationParent.children.length : 0;
  } else {
    destinationParent = target.parent;
    if (!destinationParent || typeof destinationParent.nodeId !== 'string') {
      return rejected(session, 'TREE_TARGET_INVALID', 'Canvas 根前后不存在同级插入位置。');
    }
    if (!canAcceptCoreChildren(destinationParent)) {
      return rejected(session, 'PARENT_CANNOT_HAVE_CHILDREN', '目标父级不能接收首批核心节点。');
    }
    rawIndex = target.childIndex + (intent.position === 'after' ? 1 : 0);
  }
  const destinationParentId = stringMember(destinationParent, 'nodeId');
  if (!destinationParentId) {
    return rejected(session, 'TREE_TARGET_INVALID', '目标父级缺少稳定 nodeId。');
  }
  if (subtreeContains(source.node, destinationParentId)) {
    return rejected(session, 'TREE_CYCLE', '不能把节点移入自己或自己的后代。');
  }

  const sourcePlacement = objectOrNull(source.node.placement);
  if (!sourcePlacement) {
    return rejected(session, 'PLACEMENT_CONVERSION_INVALID', '移动节点缺少 placement。');
  }
  const destinationPlacement = convertPlacement(
    sourcePlacement,
    stringMember(destinationParent, 'kind'),
    intent.at,
  );
  if (!destinationPlacement) {
    return rejected(session, 'PLACEMENT_CONVERSION_INVALID', '无法为目标父级构造合法 placement。');
  }

  const sameParent = source.parent.nodeId === destinationParentId;
  const destinationIndex = sameParent && source.childIndex < rawIndex ? rawIndex - 1 : rawIndex;
  if (sameParent
    && source.childIndex === destinationIndex
    && sameRecord(sourcePlacement, destinationPlacement)) {
    return { state: 'no-op', session, message: '节点已经位于目标位置。' };
  }
  return {
    state: 'command',
    command: {
      kind: 'move-node',
      nodeId: intent.nodeId,
      before: {
        parentNodeId: source.parent.nodeId,
        childIndex: source.childIndex,
        placement: sourcePlacement,
      },
      after: {
        parentNodeId: destinationParentId,
        childIndex: destinationIndex,
        placement: destinationPlacement,
      },
    },
    affectedNodeIds: [intent.nodeId, source.parent.nodeId, destinationParentId],
    message: '已移动节点并原子转换 placement。',
  };
}

function resolveReorder(
  session: StructuredEditorSession,
  root: Record<string, unknown>,
  nodeId: string,
  order: TemplateSiblingOrder,
): { state: 'command'; command: MoveNodeCommand; affectedNodeIds: string[]; message: string }
  | Extract<TemplateEditorCommandResult, { state: 'no-op' | 'rejected' }> {
  const source = findNode(root, nodeId);
  if (!source) return rejected(session, 'NODE_NOT_FOUND', '待排序节点不在当前 working copy 中。');
  if (!source.parent || typeof source.parent.nodeId !== 'string' || !Array.isArray(source.parent.children)) {
    return rejected(session, 'CANVAS_MUTATION_FORBIDDEN', 'Canvas 根不能参与 sibling Z-order。');
  }
  if (!isCoreNode(source.node)) {
    return rejected(session, 'NODE_KIND_UNSUPPORTED', '该节点尚未进入核心 authoring slice。');
  }
  const last = source.parent.children.length - 1;
  const destinationIndex = order === 'front'
    ? last
    : order === 'back'
      ? 0
      : order === 'forward'
        ? Math.min(last, source.childIndex + 1)
        : Math.max(0, source.childIndex - 1);
  if (destinationIndex === source.childIndex) {
    return { state: 'no-op', session, message: '节点已位于该 Z-order 边界。' };
  }
  const placement = objectOrNull(source.node.placement);
  if (!placement) return rejected(session, 'PLACEMENT_CONVERSION_INVALID', '节点缺少 placement。');
  return {
    state: 'command',
    command: {
      kind: 'move-node',
      nodeId,
      before: {
        parentNodeId: source.parent.nodeId,
        childIndex: source.childIndex,
        placement,
      },
      after: {
        parentNodeId: source.parent.nodeId,
        childIndex: destinationIndex,
        placement,
      },
    },
    affectedNodeIds: [nodeId, source.parent.nodeId],
    message: '已按 children[] 调整 sibling Z-order。',
  };
}

function resolveGeometry(
  session: StructuredEditorSession,
  root: Record<string, unknown>,
  nodeId: string,
  geometry: Extract<TemplateEditorCommandIntent, { operation: 'set-geometry' }>['geometry'],
): { state: 'command'; command: ReplaceNodeShellCommand; affectedNodeIds: string[]; message: string }
  | Extract<TemplateEditorCommandResult, { state: 'rejected' }> {
  const located = findNode(root, nodeId);
  if (!located) return rejected(session, 'NODE_NOT_FOUND', '待调整节点不在当前 working copy 中。');
  if (!located.parent || !isCoreNode(located.node)) {
    return rejected(session, 'CANVAS_MUTATION_FORBIDDEN', 'Canvas 根不使用 child geometry 命令。');
  }
  const placement = objectOrNull(located.node.placement);
  if (!placement || placement.type !== 'ABSOLUTE'
    || finiteTemplateNumber(geometry.xMm) === null
    || finiteTemplateNumber(geometry.yMm) === null
    || (geometry.widthMm !== undefined && (positiveTemplateNumber(geometry.widthMm) === null
      || placement.widthMode !== 'FIXED'))
    || (geometry.heightMm !== undefined && (positiveTemplateNumber(geometry.heightMm) === null
      || placement.heightMode !== 'FIXED'))) {
    return rejected(session, 'GEOMETRY_INVALID', '只有合法 ABSOLUTE geometry 可写入首批 move/resize。');
  }
  const finalWidthMm = positiveTemplateNumber(geometry.widthMm ?? placement.widthMm);
  const finalHeightMm = positiveTemplateNumber(geometry.heightMm ?? placement.heightMm);
  if (located.node.kind === 'qrCode'
    && (finalWidthMm === null || finalHeightMm === null || finalWidthMm !== finalHeightMm)) {
    return rejected(session, 'GEOMETRY_INVALID', '二维码最终尺寸必须是严格正方形。');
  }
  const before = nodeShell(located.node);
  const after = {
    ...before,
    placement: {
      ...placement,
      xMm: sameTemplateNumber(placement.xMm, geometry.xMm)
        ? placement.xMm : geometry.xMm,
      yMm: sameTemplateNumber(placement.yMm, geometry.yMm)
        ? placement.yMm : geometry.yMm,
      ...(geometry.widthMm === undefined
        ? {}
        : {
          widthMm: sameTemplateNumber(placement.widthMm, geometry.widthMm)
            ? placement.widthMm : geometry.widthMm,
        }),
      ...(geometry.heightMm === undefined
        ? {}
        : {
          heightMm: sameTemplateNumber(placement.heightMm, geometry.heightMm)
            ? placement.heightMm : geometry.heightMm,
        }),
    },
  };
  return replaceNodeCommand(nodeId, before, after, '已提交一次 authored move/resize。');
}

function resolveProperty(
  session: StructuredEditorSession,
  root: Record<string, unknown>,
  nodeId: string,
  property: Extract<TemplateEditorCommandIntent, { operation: 'set-property' }>['property'],
  value: unknown,
): { state: 'command'; command: ReplaceNodeShellCommand; affectedNodeIds: string[]; message: string }
  | Extract<TemplateEditorCommandResult, { state: 'rejected' }> {
  const located = findNode(root, nodeId);
  if (!located) return rejected(session, 'NODE_NOT_FOUND', '待更新节点不在当前 working copy 中。');
  if (!isCoreNode(located.node)) {
    return rejected(session, 'NODE_KIND_UNSUPPORTED', '该节点尚未进入核心 authoring slice。');
  }
  const kind = stringMember(located.node, 'kind');
  const before = nodeShell(located.node);
  if (isVisualLeafKind(kind)) {
    const change = visualPropertyChange(property, value);
    if (!change) {
      return rejected(session, 'PROPERTY_INVALID', '视觉属性值不符合 closed authoring contract。');
    }
    const updated = updateTemplateVisualNodeProperty(before, change);
    if (updated.state === 'rejected') {
      return rejected(session, 'PROPERTY_INVALID', updated.message);
    }
    return replaceNodeCommand(nodeId, before, updated.node, '已更新 authored visual property。');
  }
  let after: Record<string, unknown>;
  switch (property) {
    case 'fillColor':
      if ((kind !== 'frame' && kind !== 'stack' && kind !== 'rect')
        || typeof value !== 'string' || !COLOR.test(value)) {
        return rejected(session, 'PROPERTY_INVALID', '该节点不能使用这个填充颜色。');
      }
      after = { ...before, fill: { color: value.toUpperCase() } };
      break;
    case 'backgroundColor':
      if (kind !== 'canvas' || typeof value !== 'string' || !COLOR.test(value)) {
        return rejected(session, 'PROPERTY_INVALID', 'Canvas 背景颜色无效。');
      }
      after = { ...before, backgroundColor: value.toUpperCase() };
      break;
    case 'clipContent':
      if ((kind !== 'frame' && kind !== 'stack') || typeof value !== 'boolean') {
        return rejected(session, 'PROPERTY_INVALID', '该节点不能设置内容裁剪。');
      }
      after = { ...before, clipContent: value };
      break;
    case 'direction':
      if (kind !== 'stack' || (value !== 'ROW' && value !== 'COLUMN')) {
        return rejected(session, 'PROPERTY_INVALID', 'Stack 方向必须是 ROW 或 COLUMN。');
      }
      after = { ...before, direction: value };
      break;
    case 'gapMm': {
      const gapMm = finiteTemplateNumber(value);
      if (kind !== 'stack' || gapMm === null || gapMm < 0) {
        return rejected(session, 'PROPERTY_INVALID', 'Stack gap 必须是非负有限值。');
      }
      after = {
        ...before,
        gapMm: sameTemplateNumber(before.gapMm, gapMm) ? before.gapMm : value,
      };
      break;
    }
    case 'canvasWidthMm':
    case 'canvasHeightMm': {
      const dimension = positiveTemplateNumber(value);
      if (kind !== 'canvas' || dimension === null) {
        return rejected(session, 'PROPERTY_INVALID', 'Canvas 物理尺寸必须为正有限值。');
      }
      const member = property === 'canvasWidthMm' ? 'widthMm' : 'heightMm';
      after = {
        ...before,
        [member]: sameTemplateNumber(before[member], dimension) ? before[member] : value,
      };
      break;
    }
    default:
      return rejected(session, 'PROPERTY_INVALID', '该节点不能使用这个属性。');
  }
  return replaceNodeCommand(nodeId, before, after, '已更新 authored property。');
}

function replaceNodeCommand(
  nodeId: string,
  before: Record<string, unknown>,
  after: Record<string, unknown>,
  message: string,
): { state: 'command'; command: ReplaceNodeShellCommand; affectedNodeIds: string[]; message: string } {
  return {
    state: 'command',
    command: { kind: 'replace-node-shell', nodeId, before, after },
    affectedNodeIds: [nodeId],
    message,
  };
}

function defaultNode(
  intent: Extract<TemplateEditorCommandIntent, { operation: 'insert' }>,
  nodeId: string,
  ordinal: number,
):
  | { state: 'built'; node: Record<string, unknown> }
  | { state: 'rejected'; code: string; message: string } {
  const kind = intent.nodeKind;
  if (kind !== 'frame' && kind !== 'stack') {
    const result = intent.shapePreset && kind === 'polygon'
      ? buildTemplateShapePresetNode({
        nodeId,
        ordinal,
        preset: intent.shapePreset,
        ...(intent.at ? { at: intent.at } : {}),
      })
      : buildTemplateVisualNode({
        kind,
        nodeId,
        ordinal,
        ...(intent.at ? { at: intent.at } : {}),
        ...(kind === 'text' ? { fontAssetId: intent.assetId ?? '' } : {}),
        ...(kind === 'image' ? { imageAssetId: intent.assetId ?? '' } : {}),
      } as Parameters<typeof buildTemplateVisualNode>[0]);
    return result.state === 'built'
      ? { state: 'built', node: result.node as unknown as Record<string, unknown> }
      : result;
  }
  const placement = {
    type: 'ABSOLUTE', xMm: intent.at?.xMm ?? 25.4, yMm: intent.at?.yMm ?? 25.4,
    ...defaultContainerSize(),
  };
  const common = {
    nodeId,
    kind,
    displayName: `${kindLabel(kind)} ${ordinal}`,
    bindings: [],
    placement,
  };
  switch (kind) {
    case 'frame':
      return { state: 'built', node: {
        ...common,
        children: [],
        padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
      } };
    case 'stack':
      return { state: 'built', node: {
        ...common,
        children: [],
        padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
        direction: 'COLUMN',
        gapMm: 0,
      } };
  }
}

function defaultContainerSize(): Record<string, unknown> {
  const widthMm = 80;
  const heightMm = 60;
  return { widthMode: 'FIXED', widthMm, heightMode: 'FIXED', heightMm };
}

function placementSize(placement: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries([
    'widthMode', 'widthMm', 'heightMode', 'heightMm',
    'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm',
  ].filter((key) => Object.hasOwn(placement, key)).map((key) => [key, placement[key]]));
}

function placementForParent(
  parentKind: string | null,
  size: Record<string, unknown>,
  at?: { xMm: number; yMm: number },
): Record<string, unknown> | null {
  if (parentKind === 'stack') return { type: 'STACK', ...size };
  if (parentKind === 'canvas' || parentKind === 'frame') {
    const xMm = at?.xMm ?? 25.4;
    const yMm = at?.yMm ?? 25.4;
    if (finiteTemplateNumber(xMm) === null || finiteTemplateNumber(yMm) === null) return null;
    return { type: 'ABSOLUTE', xMm, yMm, ...size };
  }
  return null;
}

function convertPlacement(
  placement: Record<string, unknown>,
  parentKind: string | null,
  at?: { xMm: number; yMm: number },
): Record<string, unknown> | null {
  const targetType = parentKind === 'stack'
    ? 'STACK'
    : parentKind === 'canvas' || parentKind === 'frame'
      ? 'ABSOLUTE'
      : null;
  if (!targetType || (placement.type !== 'ABSOLUTE' && placement.type !== 'STACK')) return null;

  if (placement.type === targetType) {
    if (targetType === 'STACK') return placement;
    const xMm = at?.xMm ?? finiteTemplateNumber(placement.xMm);
    const yMm = at?.yMm ?? finiteTemplateNumber(placement.yMm);
    if (xMm === null || yMm === null
      || finiteTemplateNumber(xMm) === null || finiteTemplateNumber(yMm) === null) return null;
    if (!at) return placement;
    return {
      ...placement,
      xMm: sameTemplateNumber(placement.xMm, xMm) ? placement.xMm : xMm,
      yMm: sameTemplateNumber(placement.yMm, yMm) ? placement.yMm : yMm,
    };
  }

  const commonKeys = [
    'widthMode', 'widthMm', 'heightMode', 'heightMm',
    'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm',
  ];
  const common = Object.fromEntries(
    commonKeys
      .filter((key) => Object.hasOwn(placement, key))
      .map((key) => [key, placement[key]]),
  );
  if (!validCommonPlacement(common)) return null;
  if (targetType === 'STACK') return { type: 'STACK', ...common };
  const xMm = at?.xMm ?? 0;
  const yMm = at?.yMm ?? 0;
  if (finiteTemplateNumber(xMm) === null || finiteTemplateNumber(yMm) === null) return null;
  return { type: 'ABSOLUTE', xMm, yMm, ...common };
}

function validCommonPlacement(placement: Record<string, unknown>): boolean {
  for (const axis of ['Width', 'Height'] as const) {
    const lower = axis.toLowerCase();
    const mode = placement[`${lower}Mode`];
    const fixed = placement[`${lower}Mm`];
    if (mode !== 'FIXED' && mode !== 'HUG_CONTENT' && mode !== 'FILL') return false;
    if (mode === 'FIXED') {
      if (positiveTemplateNumber(fixed) === null) return false;
    } else if (fixed !== undefined) {
      return false;
    }

    const minimum = placement[`min${axis}Mm`];
    const maximum = placement[`max${axis}Mm`];
    const minimumNumber = minimum === undefined ? null : finiteTemplateNumber(minimum);
    const maximumNumber = maximum === undefined ? null : positiveTemplateNumber(maximum);
    if ((minimum !== undefined && (minimumNumber === null || minimumNumber < 0))
      || (maximum !== undefined && maximumNumber === null)
      || (minimumNumber !== null && maximumNumber !== null && minimumNumber > maximumNumber)) {
      return false;
    }
    const fixedNumber = mode === 'FIXED' ? positiveTemplateNumber(fixed) : null;
    if (fixedNumber !== null
      && ((minimumNumber !== null && fixedNumber < minimumNumber)
        || (maximumNumber !== null && fixedNumber > maximumNumber))) return false;
  }
  return true;
}

function findNode(
  node: Record<string, unknown>,
  nodeId: string,
  parent: Record<string, unknown> | null = null,
  childIndex = -1,
): LocatedNode | null {
  if (node.nodeId === nodeId) return { node, parent, childIndex };
  if (!Array.isArray(node.children)) return null;
  for (let index = 0; index < node.children.length; index += 1) {
    const child = objectOrNull(node.children[index]);
    if (!child) continue;
    const found = findNode(child, nodeId, node, index);
    if (found) return found;
  }
  return null;
}

function subtreeContains(node: Record<string, unknown>, nodeId: string): boolean {
  if (node.nodeId === nodeId) return true;
  return Array.isArray(node.children)
    && node.children.some((child) => {
      const childNode = objectOrNull(child);
      return childNode ? subtreeContains(childNode, nodeId) : false;
    });
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

const VISUAL_LEAF_KINDS: ReadonlySet<string> = new Set([
  'text', 'image', 'rect', 'ellipse', 'line', 'polygon',
  'polyline', 'path', 'qrCode', 'barcode',
]);

function isVisualLeafKind(value: unknown): value is TemplateVisualLeafKind {
  return typeof value === 'string' && VISUAL_LEAF_KINDS.has(value);
}

function visualPropertyChange(
  property: Extract<TemplateEditorCommandIntent, { operation: 'set-property' }>['property'],
  value: unknown,
): TemplateVisualPropertyChange | null {
  switch (property) {
    case 'text':
    case 'fontRef':
    case 'imageRef':
    case 'fillColor':
    case 'strokeColor':
    case 'textColor':
    case 'content':
    case 'barcodeValue':
    case 'foregroundColor':
    case 'backgroundColor':
      if (typeof value !== 'string') return null;
      return property === 'fontRef' || property === 'imageRef'
        ? { property, assetId: value } as TemplateVisualPropertyChange
        : { property, value } as TemplateVisualPropertyChange;
    case 'fontSizePt':
    case 'letterSpacingPt':
    case 'letterSpacingFactor':
    case 'strokeWidthMm':
    case 'cornerRadiusMm':
      return typeof value === 'number' && Number.isFinite(value)
        ? { property, value } as TemplateVisualPropertyChange
        : null;
    case 'decoration':
      return value === 'NONE' || value === 'UNDERLINE' || value === 'LINE_THROUGH'
        ? { property, value }
        : null;
    case 'fit':
      return value === 'CONTAIN' || value === 'COVER' || value === 'FILL'
        ? { property, value }
        : null;
    case 'sampling':
      return value === 'LINEAR' || value === 'NEAREST'
        ? { property, value }
        : null;
    case 'fillRule':
      return value === 'NONZERO' || value === 'EVEN_ODD'
        ? { property, value }
        : null;
    case 'errorCorrectionLevel':
      return value === 'L' || value === 'M' || value === 'Q' || value === 'H'
        ? { property, value }
        : null;
    case 'format':
      return value === 'EAN_8' || value === 'EAN_13'
        || value === 'UPC_A' || value === 'CODE_128'
        ? { property, value }
        : null;
    case 'start':
    case 'end':
      return objectOrNull(value)
        ? { property, value } as unknown as TemplateVisualPropertyChange
        : null;
    case 'points':
    case 'commands':
      return Array.isArray(value)
        ? { property, value } as unknown as TemplateVisualPropertyChange
        : null;
    default:
      return null;
  }
}

function canAcceptCoreChildren(node: Record<string, unknown>): boolean {
  return isCoreTemplateAuthoringParentKind(node.kind)
    && Array.isArray(node.children);
}

function isCoreNode(node: Record<string, unknown>): boolean {
  return isCoreTemplateAuthoringKind(node.kind);
}

function nodeShell(node: Record<string, unknown>): Record<string, unknown> {
  const shell = { ...node };
  delete shell.children;
  return shell;
}

function sameRecord(left: Record<string, unknown>, right: Record<string, unknown>): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function stringMember(value: Record<string, unknown>, member: string): string | null {
  return typeof value[member] === 'string' ? value[member] : null;
}

function kindLabel(kind: CoreInsertableNodeKind): string {
  const labels: Record<CoreInsertableNodeKind, string> = {
    frame: '框架',
    stack: '堆叠',
    text: '文本',
    image: '图片',
    rect: '矩形',
    ellipse: '椭圆',
    line: '直线',
    polygon: '多边形',
    polyline: '折线',
    path: '路径',
    qrCode: '二维码',
    barcode: '条形码',
  };
  return labels[kind];
}

function defaultNodeId(): string {
  return globalThis.crypto.randomUUID().toLowerCase();
}

function rejected(
  session: StructuredEditorSession,
  code: TemplateEditorCommandProblemCode,
  message: string,
  location: { nodeId?: string; pointer?: string } = {},
): Extract<TemplateEditorCommandResult, { state: 'rejected' }> {
  return { state: 'rejected', session, code, message, ...location };
}
