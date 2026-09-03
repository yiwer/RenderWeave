import type {
  DeleteNodeCommand,
  InsertNodeCommand,
  MoveNodeCommand,
  NodeShellReplacement,
  ReplaceNodeShellCommand,
  ReplaceNodeShellsCommand,
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
import { isTemplateGridTrackList } from './template-editor-grid-tracks';
import { projectTemplateGroupUnionBounds } from './template-editor-definite-layout';
import {
  expectedTemplateChildPlacement,
  isCoreTemplateAuthoringKind,
  isCoreTemplateAuthoringParentKind,
  isTemplateNodeSizeModeAllowed,
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

export type CoreInsertableNodeKind = 'group' | 'frame' | 'stack' | 'grid' | TemplateVisualLeafKind;
export type TemplateTreeDropPosition = 'before' | 'into' | 'after';
export type TemplateSiblingOrder = 'front' | 'forward' | 'backward' | 'back';
export type TemplateLayoutProperty =
  | 'paddingTopMm' | 'paddingRightMm' | 'paddingBottomMm' | 'paddingLeftMm'
  | 'widthMode' | 'heightMode' | 'widthMm' | 'heightMm'
  | 'minWidthMm' | 'minHeightMm' | 'maxWidthMm' | 'maxHeightMm'
  | 'marginTopMm' | 'marginRightMm' | 'marginBottomMm' | 'marginLeftMm'
  | 'alignSelf' | 'fillWeight'
  | 'direction' | 'gapMm' | 'justifyContent' | 'alignItems'
  | 'rows' | 'columns' | 'rowGapMm' | 'columnGapMm'
  | 'row' | 'column' | 'rowSpan' | 'columnSpan'
  | 'horizontalAlignSelf' | 'verticalAlignSelf';
export interface TemplateProjectedGeometry {
  readonly xMm: number;
  readonly yMm: number;
  readonly widthMm: number;
  readonly heightMm: number;
}

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
    projectedGeometry?: TemplateProjectedGeometry;
  }
  | { operation: 'reorder'; nodeId: string; order: TemplateSiblingOrder }
  | {
    operation: 'set-geometry';
    nodeId: string;
    geometry: { xMm?: number; yMm?: number; widthMm?: number; heightMm?: number };
  }
  | {
    operation: 'set-property';
    nodeId: string;
    property: 'fillColor' | 'backgroundColor' | 'clipContent'
      | 'canvasWidthMm' | 'canvasHeightMm' | TemplateLayoutProperty
      | TemplateVisualPropertyChange['property'];
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
    ? placementForParent(parent, placementSize(authoredPlacement), intent.at)
    : null;
  if (!placement) {
    return rejected(session, 'PLACEMENT_CONVERSION_INVALID', '目标父级没有可用的 placement 合同。');
  }
  const node = { ...built.node, placement };
  if (!validLayoutCandidate(node, parent)) {
    return rejected(session, 'PLACEMENT_CONVERSION_INVALID', '默认节点无法满足目标父级的布局合同。');
  }
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

  const sameParent = source.parent.nodeId === destinationParentId;
  const sourcePlacement = objectOrNull(source.node.placement);
  if (!sourcePlacement) {
    return rejected(session, 'PLACEMENT_CONVERSION_INVALID', '移动节点缺少 placement。');
  }
  const destinationPlacement = convertPlacement(
    sourcePlacement,
    stringMember(source.node, 'kind'),
    destinationParent,
    intent.at,
    intent.projectedGeometry,
    sameParent,
  );
  if (!destinationPlacement) {
    return rejected(session, 'PLACEMENT_CONVERSION_INVALID', '无法为目标父级构造合法 placement。');
  }
  const sourceKind = stringMember(source.node, 'kind');
  if (!sourceKind || (!sameParent
    && !validPlacementForNode(sourceKind, destinationPlacement, destinationParent))) {
    return rejected(session, 'PLACEMENT_CONVERSION_INVALID', '转换后的 placement 不满足目标父级布局合同。');
  }

  const destinationIndex = sameParent && source.childIndex < rawIndex ? rawIndex - 1 : rawIndex;
  if (sameParent
    && source.childIndex === destinationIndex
    && sameRecord(sourcePlacement, destinationPlacement)) {
    return { state: 'no-op', session, message: '节点已经位于目标位置。' };
  }
  let groupCompensations: readonly NodeShellReplacement[] | undefined;
  if (!sameParent) {
    const prospectiveRoot = projectTreeMove(
      root,
      source.parent.nodeId as string,
      source.childIndex,
      intent.nodeId,
      destinationParentId,
      destinationIndex,
      destinationPlacement,
    );
    if (!prospectiveRoot) {
      return rejected(session, 'WORKING_COPY_INVALID', '无法构造待验证的结构移动结果。');
    }
    const sourceParentPath = findNodePath(root, source.parent.nodeId as string);
    const destinationParentPath = findNodePath(root, destinationParentId);
    if (!sourceParentPath || !destinationParentPath) {
      return rejected(session, 'WORKING_COPY_INVALID', '无法定位自由分组的结构祖先。');
    }
    const compensation = planGroupUnionMinimumCompensations(
      root,
      prospectiveRoot,
      [sourceParentPath, destinationParentPath],
    );
    if (compensation.state === 'invalid') {
      return rejected(
        session,
        'PLACEMENT_CONVERSION_INVALID',
        compensation.reason === 'MANAGED'
          ? '受 managed layout 管理的自由分组无法补偿 union-min 原点变化。'
          : '无法精确计算自由分组的 union-min 原点补偿。',
      );
    }
    if (compensation.replacements.length > 0) {
      groupCompensations = compensation.replacements;
    }
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
      ...(groupCompensations === undefined ? {} : { groupCompensations }),
    },
    affectedNodeIds: uniqueStrings([
      intent.nodeId,
      source.parent.nodeId as string,
      destinationParentId,
      ...(groupCompensations?.map((item) => item.nodeId) ?? []),
    ]),
    message: groupCompensations
      ? '已移动节点并原子转换 placement 与自由分组原点。'
      : '已移动节点并原子转换 placement。',
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
): { state: 'command'; command: ReplaceNodeShellCommand | ReplaceNodeShellsCommand; affectedNodeIds: string[]; message: string }
  | Extract<TemplateEditorCommandResult, { state: 'rejected' }> {
  const path = findNodePath(root, nodeId);
  if (!path) return rejected(session, 'NODE_NOT_FOUND', '待调整节点不在当前 working copy 中。');
  const located = path.at(-1)!;
  if (!located.parent || !isCoreNode(located.node)) {
    return rejected(session, 'CANVAS_MUTATION_FORBIDDEN', 'Canvas 根不使用 child geometry 命令。');
  }
  const placement = objectOrNull(located.node.placement);
  const hasGeometry = geometry.xMm !== undefined || geometry.yMm !== undefined
    || geometry.widthMm !== undefined || geometry.heightMm !== undefined;
  if (!hasGeometry || !placement
    || (placement.type !== 'ABSOLUTE' && placement.type !== 'STACK' && placement.type !== 'GRID')
    || (geometry.xMm !== undefined && finiteTemplateNumber(geometry.xMm) === null)
    || (geometry.yMm !== undefined && finiteTemplateNumber(geometry.yMm) === null)
    || (geometry.widthMm !== undefined && (positiveTemplateNumber(geometry.widthMm) === null
      || placement.widthMode !== 'FIXED'))
    || (geometry.heightMm !== undefined && (positiveTemplateNumber(geometry.heightMm) === null
      || placement.heightMode !== 'FIXED'))) {
    return rejected(session, 'GEOMETRY_INVALID', 'geometry 只能移动 ABSOLUTE 节点或调整 FIXED 尺寸。');
  }
  const finalWidthMm = positiveTemplateNumber(geometry.widthMm ?? placement.widthMm);
  const finalHeightMm = positiveTemplateNumber(geometry.heightMm ?? placement.heightMm);
  if (located.node.kind === 'qrCode'
    && placement.widthMode === 'FIXED'
    && placement.heightMode === 'FIXED'
    && (finalWidthMm === null || finalHeightMm === null || finalWidthMm !== finalHeightMm)) {
    return rejected(session, 'GEOMETRY_INVALID', '二维码最终尺寸必须是严格正方形。');
  }
  const before = nodeShell(located.node);
  const afterPlacement = {
      ...placement,
      ...(placement.type !== 'ABSOLUTE' || geometry.xMm === undefined
        ? {}
        : {
          xMm: sameTemplateNumber(placement.xMm, geometry.xMm)
            ? placement.xMm : geometry.xMm,
        }),
      ...(placement.type !== 'ABSOLUTE' || geometry.yMm === undefined
        ? {}
        : {
          yMm: sameTemplateNumber(placement.yMm, geometry.yMm)
            ? placement.yMm : geometry.yMm,
        }),
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
  };
  const kind = stringMember(located.node, 'kind');
  if (!kind || !validPlacementForNode(kind, afterPlacement, located.parent)) {
    return rejected(session, 'GEOMETRY_INVALID', 'resize 会违反 placement size/min/max 合同。');
  }
  const after = {
    ...before,
    placement: afterPlacement,
  };
  const prospectiveRoot = projectNodeShellReplacement(root, nodeId, after);
  if (!prospectiveRoot) {
    return rejected(session, 'WORKING_COPY_INVALID', '无法构造待验证的 authored geometry 结果。');
  }
  const compensation = planGroupUnionMinimumCompensations(
    root,
    prospectiveRoot,
    [path.slice(0, -1)],
  );
  if (compensation.state === 'invalid') {
    return rejected(
      session,
      'GEOMETRY_INVALID',
      compensation.reason === 'MANAGED'
        ? '受 managed layout 管理的自由分组无法补偿 union-min 原点变化。'
        : '无法精确计算自由分组的 union-min 原点补偿。',
    );
  }
  const replacements: NodeShellReplacement[] = [
    { nodeId, before, after },
    ...compensation.replacements,
  ];
  if (replacements.length === 1) {
    return replaceNodeCommand(nodeId, before, after, '已提交一次 authored move/resize。');
  }
  return {
    state: 'command',
    command: { kind: 'replace-node-shells', replacements },
    affectedNodeIds: replacements.map((item) => item.nodeId),
    message: '已原子提交 authored move/resize 与自由分组原点补偿。',
  };
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
  const layout = updateTemplateLayoutProperty(
    located.node,
    located.parent,
    property,
    value,
  );
  if (layout.state === 'invalid') {
    return rejected(session, 'PROPERTY_INVALID', layout.message);
  }
  if (layout.state === 'updated') {
    return replaceNodeCommand(nodeId, before, layout.node, '已更新 authored layout property。');
  }
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
      if ((kind !== 'frame' && kind !== 'stack' && kind !== 'grid' && kind !== 'rect')
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
      if ((kind !== 'frame' && kind !== 'stack' && kind !== 'grid') || typeof value !== 'boolean') {
        return rejected(session, 'PROPERTY_INVALID', '该节点不能设置内容裁剪。');
      }
      after = { ...before, clipContent: value };
      break;
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

type LayoutPropertyUpdate =
  | { state: 'not-layout' }
  | { state: 'invalid'; message: string }
  | { state: 'updated'; node: Record<string, unknown> };

function updateTemplateLayoutProperty(
  node: Record<string, unknown>,
  parent: Record<string, unknown> | null,
  property: Extract<TemplateEditorCommandIntent, { operation: 'set-property' }>['property'],
  value: unknown,
): LayoutPropertyUpdate {
  const kind = stringMember(node, 'kind');
  let after = nodeShell(node);
  const placement = objectOrNull(after.placement);

  switch (property) {
    case 'paddingTopMm':
    case 'paddingRightMm':
    case 'paddingBottomMm':
    case 'paddingLeftMm': {
      if (kind !== 'frame' && kind !== 'stack' && kind !== 'grid') {
        return invalidLayout('只有 Frame、Stack 与 Grid 可以设置 padding。');
      }
      const amount = finiteTemplateNumber(value);
      const padding = completePadding(after.padding);
      if (amount === null || amount < 0 || !padding) {
        return invalidLayout('padding 必须是完整的非负有限 mm 值。');
      }
      const member = `${property.slice('padding'.length, -'Mm'.length).toLowerCase()}Mm`;
      after = {
        ...after,
        padding: {
          ...padding,
          [member]: sameTemplateNumber(padding[member], amount) ? padding[member] : value,
        },
      };
      break;
    }
    case 'widthMode':
    case 'heightMode': {
      if (!placement || (value !== 'FIXED' && value !== 'HUG_CONTENT' && value !== 'FILL')) {
        return invalidLayout('size mode 必须使用正式 FIXED、HUG_CONTENT 或 FILL。');
      }
      const axis = property === 'widthMode' ? 'width' : 'height';
      const updated = setPlacementAxisMode(placement, axis, value);
      if (!updated) return invalidLayout('切换 FIXED 前必须提供合法固定尺寸。');
      after = { ...after, placement: updated };
      break;
    }
    case 'widthMm':
    case 'heightMm': {
      const amount = positiveTemplateNumber(value);
      if (!placement || amount === null) return invalidLayout('固定尺寸必须是正有限 mm 值。');
      const axis = property === 'widthMm' ? 'width' : 'height';
      const modeMember = `${axis}Mode`;
      const updated = {
        ...placement,
        [modeMember]: 'FIXED',
        [property]: sameTemplateNumber(placement[property], amount) ? placement[property] : value,
      };
      delete updated[axis === 'width' ? 'rightInsetMm' : 'bottomInsetMm'];
      after = { ...after, placement: updated };
      break;
    }
    case 'minWidthMm':
    case 'minHeightMm':
    case 'maxWidthMm':
    case 'maxHeightMm': {
      if (!placement || kind === 'group') {
        return invalidLayout('该节点不能设置 min/max 尺寸。');
      }
      if (value === null) {
        after = { ...after, placement: withoutMember(placement, property) };
        break;
      }
      const amount = finiteTemplateNumber(value);
      const isMinimum = property.startsWith('min');
      if (amount === null || (isMinimum ? amount < 0 : amount <= 0)) {
        return invalidLayout(isMinimum ? 'min 尺寸必须非负。' : 'max 尺寸必须为正。');
      }
      after = {
        ...after,
        placement: {
          ...placement,
          [property]: sameTemplateNumber(placement[property], amount) ? placement[property] : value,
        },
      };
      break;
    }
    case 'marginTopMm':
    case 'marginRightMm':
    case 'marginBottomMm':
    case 'marginLeftMm': {
      if (!placement || (placement.type !== 'STACK' && placement.type !== 'GRID')) {
        return invalidLayout('margin 只属于 STACK/GRID placement。');
      }
      if (value === null) {
        after = { ...after, placement: withoutMember(placement, property) };
        break;
      }
      const amount = finiteTemplateNumber(value);
      if (amount === null) return invalidLayout('margin 必须是有限 mm 值。');
      after = {
        ...after,
        placement: {
          ...placement,
          [property]: sameTemplateNumber(placement[property], amount) ? placement[property] : value,
        },
      };
      break;
    }
    case 'alignSelf': {
      if (!placement || placement.type !== 'STACK'
        || (value !== null && value !== 'START' && value !== 'CENTER' && value !== 'END')) {
        return invalidLayout('alignSelf 只接受 STACK placement 的 START/CENTER/END。');
      }
      after = {
        ...after,
        placement: value === null
          ? withoutMember(placement, property)
          : { ...placement, [property]: value },
      };
      break;
    }
    case 'fillWeight': {
      if (!placement || placement.type !== 'STACK' || kind === 'group') {
        return invalidLayout('fillWeight 只属于非 Group 的 STACK placement。');
      }
      if (value === null) {
        after = { ...after, placement: withoutMember(placement, property) };
        break;
      }
      const amount = positiveTemplateNumber(value);
      if (amount === null) return invalidLayout('fillWeight 必须为正有限值。');
      after = {
        ...after,
        placement: {
          ...placement,
          fillWeight: sameTemplateNumber(placement.fillWeight, amount)
            ? placement.fillWeight : value,
        },
      };
      break;
    }
    case 'direction':
      if (kind !== 'stack' || (value !== 'ROW' && value !== 'COLUMN')) {
        return invalidLayout('Stack 方向必须是 ROW 或 COLUMN。');
      }
      after = { ...after, direction: value };
      break;
    case 'gapMm':
    case 'rowGapMm':
    case 'columnGapMm': {
      const allowed = property === 'gapMm' ? kind === 'stack' : kind === 'grid';
      if (!allowed) return invalidLayout('该 gap 不属于此容器。');
      if (value === null) {
        after = withoutMember(after, property);
        break;
      }
      const amount = finiteTemplateNumber(value);
      if (amount === null || amount < 0) return invalidLayout('gap 必须是非负有限 mm 值。');
      after = {
        ...after,
        [property]: sameTemplateNumber(after[property], amount) ? after[property] : value,
      };
      break;
    }
    case 'justifyContent': {
      const allowed = new Set(['START', 'CENTER', 'END', 'SPACE_BETWEEN', 'SPACE_AROUND', 'SPACE_EVENLY']);
      if (kind !== 'stack' || typeof value !== 'string' || !allowed.has(value)) {
        return invalidLayout('justifyContent 不是正式 Stack 枚举值。');
      }
      after = { ...after, justifyContent: value };
      break;
    }
    case 'alignItems':
      if (kind !== 'stack' || (value !== 'START' && value !== 'CENTER' && value !== 'END')) {
        return invalidLayout('alignItems 必须是 START、CENTER 或 END。');
      }
      after = { ...after, alignItems: value };
      break;
    case 'rows':
    case 'columns':
      if (kind !== 'grid' || !isTemplateGridTrackList(value)) {
        return invalidLayout('Grid 轨道必须是非空、最多 64 项的正式有序 track。');
      }
      after = { ...after, [property]: value };
      break;
    case 'row':
    case 'column':
    case 'rowSpan':
    case 'columnSpan': {
      if (!placement || placement.type !== 'GRID') {
        return invalidLayout('Grid cell 属性只属于 GRID placement。');
      }
      if ((property === 'rowSpan' || property === 'columnSpan') && value === null) {
        after = { ...after, placement: withoutMember(placement, property) };
        break;
      }
      const amount = finiteTemplateNumber(value);
      const positive = property === 'rowSpan' || property === 'columnSpan';
      if (amount === null || !Number.isInteger(amount) || (positive ? amount <= 0 : amount < 0)) {
        return invalidLayout(positive ? 'Grid span 必须是正整数。' : 'Grid cell 索引必须是非负整数。');
      }
      after = {
        ...after,
        placement: {
          ...placement,
          [property]: sameTemplateNumber(placement[property], amount) ? placement[property] : value,
        },
      };
      break;
    }
    case 'horizontalAlignSelf':
    case 'verticalAlignSelf': {
      if (!placement || placement.type !== 'GRID'
        || (value !== null && value !== 'START' && value !== 'CENTER' && value !== 'END')) {
        return invalidLayout('Grid align-self 必须是 START、CENTER 或 END。');
      }
      after = {
        ...after,
        placement: value === null
          ? withoutMember(placement, property)
          : { ...placement, [property]: value },
      };
      break;
    }
    default:
      return { state: 'not-layout' };
  }

  const candidate = { ...node, ...after };
  if (!validLayoutCandidate(candidate, parent)) {
    return invalidLayout('该修改会形成非法 size、placement 或容器布局组合。');
  }
  return { state: 'updated', node: after };
}

function setPlacementAxisMode(
  placement: Record<string, unknown>,
  axis: 'width' | 'height',
  mode: 'FIXED' | 'HUG_CONTENT' | 'FILL',
): Record<string, unknown> | null {
  const modeMember = `${axis}Mode`;
  const sizeMember = `${axis}Mm`;
  const insetMember = axis === 'width' ? 'rightInsetMm' : 'bottomInsetMm';
  const updated = { ...placement, [modeMember]: mode };
  if (mode === 'FIXED') {
    if (positiveTemplateNumber(updated[sizeMember]) === null) return null;
  } else {
    delete updated[sizeMember];
  }
  if (mode !== 'FILL') delete updated[insetMember];
  return updated;
}

function completePadding(value: unknown): Record<string, unknown> | null {
  if (value === undefined) return { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 };
  const padding = objectOrNull(value);
  if (!padding || !exactMembers(padding, 'topMm', 'rightMm', 'bottomMm', 'leftMm')) return null;
  return ['topMm', 'rightMm', 'bottomMm', 'leftMm'].every((member) => {
    const amount = finiteTemplateNumber(padding[member]);
    return amount !== null && amount >= 0;
  }) ? padding : null;
}

function withoutMember(
  value: Record<string, unknown>,
  member: string,
): Record<string, unknown> {
  const copy = { ...value };
  delete copy[member];
  return copy;
}

function invalidLayout(message: string): LayoutPropertyUpdate {
  return { state: 'invalid', message };
}

function validLayoutCandidate(
  node: Record<string, unknown>,
  parent: Record<string, unknown> | null,
): boolean {
  const kind = stringMember(node, 'kind');
  if (!kind) return false;
  if (node.padding !== undefined && !completePadding(node.padding)) return false;
  if (kind !== 'canvas') {
    const placement = objectOrNull(node.placement);
    if (!placement || !validPlacementForNode(kind, placement, parent)) return false;
  }
  if (kind === 'stack' && !validStackLayout(node)) return false;
  if (kind === 'grid' && !validGridLayout(node)) return false;
  return true;
}

function validPlacementForNode(
  kind: string,
  placement: Record<string, unknown>,
  parent: Record<string, unknown> | null,
): boolean {
  const expected = expectedTemplateChildPlacement(parent?.kind);
  if (!expected || placement.type !== expected || !validCommonPlacement(placement)) return false;
  const widthMode = placement.widthMode;
  const heightMode = placement.heightMode;
  if (!isTemplateNodeSizeModeAllowed(kind, widthMode)
    || !isTemplateNodeSizeModeAllowed(kind, heightMode)) return false;
  if (kind === 'group') {
    if (widthMode !== 'HUG_CONTENT' || heightMode !== 'HUG_CONTENT') return false;
    if (['minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm']
      .some((member) => placement[member] !== undefined)) return false;
  }
  if (kind === 'image' && widthMode === 'HUG_CONTENT' && heightMode === 'HUG_CONTENT') return false;
  if (kind === 'qrCode' && widthMode === 'FIXED' && heightMode === 'FIXED') {
    const width = positiveTemplateNumber(placement.widthMm);
    const height = positiveTemplateNumber(placement.heightMm);
    if (width === null || height === null || width !== height) return false;
  }

  if (placement.type === 'ABSOLUTE') {
    if (finiteTemplateNumber(placement.xMm) === null || finiteTemplateNumber(placement.yMm) === null) {
      return false;
    }
    if (!validInset(placement, 'width', 'rightInsetMm')
      || !validInset(placement, 'height', 'bottomInsetMm')) return false;
    if (parent?.kind === 'group' && (widthMode === 'FILL' || heightMode === 'FILL')) return false;
    if (parent?.kind === 'frame') {
      const parentPlacement = objectOrNull(parent.placement);
      if (parentPlacement?.widthMode === 'HUG_CONTENT' && widthMode === 'FILL') return false;
      if (parentPlacement?.heightMode === 'HUG_CONTENT' && heightMode === 'FILL') return false;
    }
  } else if (placement.type === 'STACK') {
    if (!validMargins(placement)) return false;
    if (placement.alignSelf !== undefined
      && placement.alignSelf !== 'START' && placement.alignSelf !== 'CENTER'
      && placement.alignSelf !== 'END') return false;
    const direction = parent?.direction === 'ROW' ? 'ROW' : 'COLUMN';
    const parentPlacement = parent ? objectOrNull(parent.placement) : null;
    if (parentPlacement?.widthMode === 'HUG_CONTENT' && widthMode === 'FILL') return false;
    if (parentPlacement?.heightMode === 'HUG_CONTENT' && heightMode === 'FILL') return false;
    const crossMode = direction === 'ROW' ? heightMode : widthMode;
    if (crossMode === 'FILL' && placement.alignSelf !== undefined) return false;
    if (placement.fillWeight !== undefined) {
      const mainMode = direction === 'ROW' ? widthMode : heightMode;
      if (kind === 'group' || mainMode !== 'FILL'
        || positiveTemplateNumber(placement.fillWeight) === null) return false;
    }
  } else if (placement.type === 'GRID') {
    if (!validMargins(placement)) return false;
    const row = nonnegativeInteger(placement.row);
    const column = nonnegativeInteger(placement.column);
    const rowSpan = placement.rowSpan === undefined ? 1 : positiveInteger(placement.rowSpan);
    const columnSpan = placement.columnSpan === undefined ? 1 : positiveInteger(placement.columnSpan);
    if (row === null || column === null || rowSpan === null || columnSpan === null) return false;
    if (!parent || !isTemplateGridTrackList(parent.rows) || !isTemplateGridTrackList(parent.columns)
      || row + rowSpan > parent.rows.length || column + columnSpan > parent.columns.length) return false;
    if (widthMode === 'FILL'
      && parent.columns.slice(column, column + columnSpan).some((track) => track.type === 'AUTO')) {
      return false;
    }
    if (heightMode === 'FILL'
      && parent.rows.slice(row, row + rowSpan).some((track) => track.type === 'AUTO')) {
      return false;
    }
    if (!validGridAlign(placement.horizontalAlignSelf)
      || !validGridAlign(placement.verticalAlignSelf)) return false;
    if (widthMode === 'FILL' && placement.horizontalAlignSelf !== undefined) return false;
    if (heightMode === 'FILL' && placement.verticalAlignSelf !== undefined) return false;
  }
  return true;
}

function validInset(
  placement: Record<string, unknown>,
  axis: 'width' | 'height',
  member: 'rightInsetMm' | 'bottomInsetMm',
): boolean {
  const inset = placement[member];
  return inset === undefined
    || (placement[`${axis}Mode`] === 'FILL' && finiteTemplateNumber(inset) !== null);
}

function validMargins(placement: Record<string, unknown>): boolean {
  return ['marginTopMm', 'marginRightMm', 'marginBottomMm', 'marginLeftMm']
    .every((member) => placement[member] === undefined
      || finiteTemplateNumber(placement[member]) !== null);
}

function validGridAlign(value: unknown): boolean {
  return value === undefined || value === 'START' || value === 'CENTER' || value === 'END';
}

function validStackLayout(node: Record<string, unknown>): boolean {
  if (node.direction !== undefined && node.direction !== 'ROW' && node.direction !== 'COLUMN') return false;
  if (node.gapMm !== undefined) {
    const gap = finiteTemplateNumber(node.gapMm);
    if (gap === null || gap < 0) return false;
  }
  if (node.justifyContent !== undefined
    && node.justifyContent !== 'START' && node.justifyContent !== 'CENTER'
    && node.justifyContent !== 'END' && node.justifyContent !== 'SPACE_BETWEEN'
    && node.justifyContent !== 'SPACE_AROUND' && node.justifyContent !== 'SPACE_EVENLY') return false;
  if (node.alignItems !== undefined && node.alignItems !== 'START'
    && node.alignItems !== 'CENTER' && node.alignItems !== 'END') return false;
  return Array.isArray(node.children) && node.children.every((child) => {
    const childNode = objectOrNull(child);
    const childKind = childNode ? stringMember(childNode, 'kind') : null;
    const placement = childNode ? objectOrNull(childNode.placement) : null;
    return Boolean(childKind && placement && validPlacementForNode(childKind, placement, node));
  });
}

function validGridLayout(node: Record<string, unknown>): boolean {
  if (!isTemplateGridTrackList(node.rows) || !isTemplateGridTrackList(node.columns)) return false;
  for (const member of ['rowGapMm', 'columnGapMm']) {
    if (node[member] !== undefined) {
      const gap = finiteTemplateNumber(node[member]);
      if (gap === null || gap < 0) return false;
    }
  }
  const placement = objectOrNull(node.placement);
  if (placement?.widthMode === 'HUG_CONTENT'
    && node.columns.some((track) => track.type === 'FRACTION')) return false;
  if (placement?.heightMode === 'HUG_CONTENT'
    && node.rows.some((track) => track.type === 'FRACTION')) return false;
  return Array.isArray(node.children) && node.children.every((child) => {
    const childNode = objectOrNull(child);
    const childKind = childNode ? stringMember(childNode, 'kind') : null;
    const childPlacement = childNode ? objectOrNull(childNode.placement) : null;
    return Boolean(childKind && childPlacement
      && validPlacementForNode(childKind, childPlacement, node));
  });
}

function nonnegativeInteger(value: unknown): number | null {
  const amount = finiteTemplateNumber(value);
  return amount !== null && Number.isInteger(amount) && amount >= 0 ? amount : null;
}

function positiveInteger(value: unknown): number | null {
  const amount = finiteTemplateNumber(value);
  return amount !== null && Number.isInteger(amount) && amount > 0 ? amount : null;
}

function exactMembers(value: Record<string, unknown>, ...members: string[]): boolean {
  const keys = Object.keys(value);
  return keys.length === members.length && members.every((member) => Object.hasOwn(value, member));
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
  if (kind !== 'group' && kind !== 'frame' && kind !== 'stack' && kind !== 'grid') {
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
    ...(kind === 'group'
      ? { widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT' }
      : defaultContainerSize()),
  };
  const common = {
    nodeId,
    kind,
    displayName: `${kindLabel(kind)} ${ordinal}`,
    bindings: [],
    placement,
  };
  switch (kind) {
    case 'group':
      return { state: 'built', node: { ...common, children: [] } };
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
    case 'grid':
      return { state: 'built', node: {
        ...common,
        children: [],
        padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
        rows: [{ type: 'FRACTION', weight: 1 }],
        columns: [{ type: 'FRACTION', weight: 1 }],
        rowGapMm: 0,
        columnGapMm: 0,
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
  parent: Record<string, unknown>,
  size: Record<string, unknown>,
  at?: { xMm: number; yMm: number },
): Record<string, unknown> | null {
  const parentKind = stringMember(parent, 'kind');
  const targetType = expectedTemplateChildPlacement(parentKind);
  if (targetType === 'STACK') return { type: 'STACK', ...size };
  if (targetType === 'GRID') {
    if (!isTemplateGridTrackList(parent.rows) || !isTemplateGridTrackList(parent.columns)) return null;
    return { type: 'GRID', ...size, row: 0, column: 0 };
  }
  if (targetType === 'ABSOLUTE') {
    const xMm = at?.xMm ?? 25.4;
    const yMm = at?.yMm ?? 25.4;
    if (finiteTemplateNumber(xMm) === null || finiteTemplateNumber(yMm) === null) return null;
    return { type: 'ABSOLUTE', xMm, yMm, ...size };
  }
  return null;
}

function convertPlacement(
  placement: Record<string, unknown>,
  nodeKind: string | null,
  parent: Record<string, unknown>,
  at?: { xMm: number; yMm: number },
  projectedGeometry?: TemplateProjectedGeometry,
  sameParent = false,
): Record<string, unknown> | null {
  const parentKind = stringMember(parent, 'kind');
  const targetType = expectedTemplateChildPlacement(parentKind);
  if (!targetType || targetType === 'PACK'
    || (placement.type !== 'ABSOLUTE' && placement.type !== 'STACK' && placement.type !== 'GRID')) {
    return null;
  }
  if (projectedGeometry) {
    const projectedWidth = finiteTemplateNumber(projectedGeometry.widthMm);
    const projectedHeight = finiteTemplateNumber(projectedGeometry.heightMm);
    if (finiteTemplateNumber(projectedGeometry.xMm) === null
      || finiteTemplateNumber(projectedGeometry.yMm) === null
      || projectedWidth === null || projectedWidth < 0
      || projectedHeight === null || projectedHeight < 0) return null;
  }
  if (targetType === 'GRID'
    && (!isTemplateGridTrackList(parent.rows) || !isTemplateGridTrackList(parent.columns))) return null;

  if (placement.type === targetType) {
    if (targetType === 'STACK' || targetType === 'GRID') return placement;
    if (sameParent) return placement;
    const xMm = projectedGeometry?.xMm ?? at?.xMm;
    const yMm = projectedGeometry?.yMm ?? at?.yMm;
    if (xMm === null || yMm === null
      || xMm === undefined || yMm === undefined
      || finiteTemplateNumber(xMm) === null || finiteTemplateNumber(yMm) === null) return null;
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
  if (targetType === 'GRID') return { type: 'GRID', ...common, row: 0, column: 0 };
  if (!projectedGeometry
    || finiteTemplateNumber(projectedGeometry.xMm) === null
    || finiteTemplateNumber(projectedGeometry.yMm) === null
    || finiteTemplateNumber(projectedGeometry.widthMm) === null
    || finiteTemplateNumber(projectedGeometry.heightMm) === null) return null;
  if (nodeKind === 'group') {
    return {
      type: 'ABSOLUTE',
      xMm: projectedGeometry.xMm,
      yMm: projectedGeometry.yMm,
      widthMode: 'HUG_CONTENT',
      heightMode: 'HUG_CONTENT',
    };
  }
  if (positiveTemplateNumber(projectedGeometry.widthMm) === null
    || positiveTemplateNumber(projectedGeometry.heightMm) === null) return null;
  const fixedCommon = {
    ...Object.fromEntries([
      'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm',
    ].filter((key) => Object.hasOwn(common, key)).map((key) => [key, common[key]])),
    widthMode: 'FIXED',
    widthMm: projectedGeometry.widthMm,
    heightMode: 'FIXED',
    heightMm: projectedGeometry.heightMm,
  };
  if (!validCommonPlacement(fixedCommon)) return null;
  return {
    type: 'ABSOLUTE',
    xMm: projectedGeometry.xMm,
    yMm: projectedGeometry.yMm,
    ...fixedCommon,
  };
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

function findNodePath(
  node: Record<string, unknown>,
  nodeId: string,
  parent: Record<string, unknown> | null = null,
  childIndex = -1,
): LocatedNode[] | null {
  const located = { node, parent, childIndex };
  if (node.nodeId === nodeId) return [located];
  if (!Array.isArray(node.children)) return null;
  for (let index = 0; index < node.children.length; index += 1) {
    const child = objectOrNull(node.children[index]);
    if (!child) continue;
    const descendantPath = findNodePath(child, nodeId, node, index);
    if (descendantPath) return [located, ...descendantPath];
  }
  return null;
}

type GroupCompensationPlan =
  | {
    readonly state: 'ready';
    readonly replacements: readonly NodeShellReplacement[];
  }
  | { readonly state: 'invalid'; readonly reason: 'BOUNDS' | 'MANAGED' | 'PLACEMENT' };

function planGroupUnionMinimumCompensations(
  originalRoot: Record<string, unknown>,
  proposedRoot: Record<string, unknown>,
  ownerPaths: readonly (readonly LocatedNode[])[],
): GroupCompensationPlan {
  const candidates = new Map<string, { nodeId: string; depth: number; order: number }>();
  let order = 0;
  for (const path of ownerPaths) {
    for (let index = path.length - 1; index >= 0; index -= 1) {
      const ancestor = path[index]!.node;
      if (ancestor.kind !== 'group') continue;
      const nodeId = stringMember(ancestor, 'nodeId');
      if (!nodeId) return { state: 'invalid', reason: 'BOUNDS' };
      if (!candidates.has(nodeId)) {
        candidates.set(nodeId, { nodeId, depth: index, order });
        order += 1;
      }
    }
  }
  const ordered = [...candidates.values()].sort(
    (left, right) => right.depth - left.depth || left.order - right.order,
  );
  const replacements: NodeShellReplacement[] = [];
  let currentProposedRoot = proposedRoot;
  for (const candidate of ordered) {
    const original = findNode(originalRoot, candidate.nodeId);
    const proposed = findNode(currentProposedRoot, candidate.nodeId);
    if (!original || !proposed) return { state: 'invalid', reason: 'BOUNDS' };
    // An empty Group has no content-origin fact to preserve. Keeping its authored
    // placement stable also avoids inventing a before-minimum -> zero translation.
    if (Array.isArray(proposed.node.children) && proposed.node.children.length === 0) continue;
    const before = projectTemplateGroupUnionBounds(original.node);
    const after = projectTemplateGroupUnionBounds(proposed.node);
    if (before.state !== 'ready' || after.state !== 'ready') {
      return { state: 'invalid', reason: 'BOUNDS' };
    }
    const deltaX = after.bounds.minimumX - before.bounds.minimumX;
    const deltaY = after.bounds.minimumY - before.bounds.minimumY;
    if (deltaX === 0 && deltaY === 0) continue;
    const placement = objectOrNull(proposed.node.placement);
    const x = placement ? finiteTemplateNumber(placement.xMm) : null;
    const y = placement ? finiteTemplateNumber(placement.yMm) : null;
    if (placement?.type !== 'ABSOLUTE' || x === null || y === null) {
      return { state: 'invalid', reason: 'MANAGED' };
    }
    const adjustedX = x + deltaX;
    const adjustedY = y + deltaY;
    if (finiteTemplateNumber(adjustedX) === null || finiteTemplateNumber(adjustedY) === null) {
      return { state: 'invalid', reason: 'PLACEMENT' };
    }
    const compensatedPlacement = {
      ...placement,
      xMm: deltaX === 0 || sameTemplateNumber(placement.xMm, adjustedX)
        ? placement.xMm : adjustedX,
      yMm: deltaY === 0 || sameTemplateNumber(placement.yMm, adjustedY)
        ? placement.yMm : adjustedY,
    };
    if (!validPlacementForNode('group', compensatedPlacement, proposed.parent)) {
      return { state: 'invalid', reason: 'PLACEMENT' };
    }
    const originalShell = nodeShell(original.node);
    const proposedShell = {
      ...nodeShell(proposed.node),
      placement: compensatedPlacement,
    };
    const rewritten = projectNodeShellReplacement(
      currentProposedRoot,
      candidate.nodeId,
      proposedShell,
    );
    if (!rewritten) return { state: 'invalid', reason: 'BOUNDS' };
    currentProposedRoot = rewritten;
    replacements.push({
      nodeId: candidate.nodeId,
      before: originalShell,
      after: proposedShell,
    });
  }
  return { state: 'ready', replacements };
}

function projectTreeMove(
  root: Record<string, unknown>,
  sourceParentId: string,
  sourceIndex: number,
  nodeId: string,
  destinationParentId: string,
  destinationIndex: number,
  destinationPlacement: Record<string, unknown>,
): Record<string, unknown> | null {
  let removedNode: Record<string, unknown> | null = null;
  const removed = rewriteTreeNode(root, sourceParentId, (parent) => {
    if (!Array.isArray(parent.children)
      || sourceIndex < 0
      || sourceIndex >= parent.children.length) return null;
    const candidate = objectOrNull(parent.children[sourceIndex]);
    if (!candidate || candidate.nodeId !== nodeId) return null;
    removedNode = candidate;
    const children = [...parent.children];
    children.splice(sourceIndex, 1);
    return { ...parent, children };
  });
  if (!removed.found || !removed.node || !removedNode) return null;
  const movingNode: Record<string, unknown> = {
    ...(removedNode as Record<string, unknown>),
    placement: destinationPlacement,
  };
  const inserted = rewriteTreeNode(removed.node, destinationParentId, (parent) => {
    if (!Array.isArray(parent.children)
      || destinationIndex < 0
      || destinationIndex > parent.children.length) return null;
    const children = [...parent.children];
    children.splice(destinationIndex, 0, movingNode);
    return { ...parent, children };
  });
  return inserted.found ? inserted.node : null;
}

function projectNodeShellReplacement(
  root: Record<string, unknown>,
  nodeId: string,
  replacement: Record<string, unknown>,
): Record<string, unknown> | null {
  const rewritten = rewriteTreeNode(root, nodeId, (current) => ({
    ...replacement,
    ...(Array.isArray(current.children) ? { children: current.children } : {}),
  }));
  return rewritten.found ? rewritten.node : null;
}

function rewriteTreeNode(
  node: Record<string, unknown>,
  nodeId: string,
  replacement: (current: Record<string, unknown>) => Record<string, unknown> | null,
): { readonly node: Record<string, unknown> | null; readonly found: boolean } {
  if (node.nodeId === nodeId) {
    const rewritten = replacement(node);
    return { node: rewritten, found: rewritten !== null };
  }
  if (!Array.isArray(node.children)) return { node, found: false };
  for (let index = 0; index < node.children.length; index += 1) {
    const child = objectOrNull(node.children[index]);
    if (!child) continue;
    const rewritten = rewriteTreeNode(child, nodeId, replacement);
    if (!rewritten.found || !rewritten.node) continue;
    const children = [...node.children];
    children[index] = rewritten.node;
    return { node: { ...node, children }, found: true };
  }
  return { node, found: false };
}

function uniqueStrings(values: readonly string[]): string[] {
  return [...new Set(values)];
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
    group: '自由分组',
    frame: '框架',
    stack: '堆叠',
    grid: '网格',
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
