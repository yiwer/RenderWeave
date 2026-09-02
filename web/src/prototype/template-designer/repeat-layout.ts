/**
 * Browser-only Repeat projection for the T220 interaction prototype.
 *
 * Authored `children[]` describe one item. This module proves the list source,
 * packs that one item, then creates virtual occurrences for preview. It never
 * writes virtual nodes or geometry back into the DesignDSL-shaped draft.
 */
import {
  definitions,
  repeatSourcesForDefinitions,
  repeatTemplateCandidates,
  type DesignerDefinition,
  type DesignerNode,
  type DraftBox,
  type PrototypeRepeatSource,
  type PrototypeRepeatTemplateCandidate,
  type RepeatPreviewSample,
} from './model';

export type RepeatConfigurationPhase = 'SOURCE_REQUIRED' | 'CONTENT_REQUIRED' | 'NEEDS_REPAIR' | 'READY';
export type RepeatProjectionOutcome = 'PROJECTED' | 'EMPTY' | 'ABSENT_ERROR' | 'INVALID';
export type RepeatLayoutKind = 'STACK' | 'GRID';
export type RepeatDirection = 'ROW' | 'COLUMN';

type RepeatSampleValue = PrototypeRepeatSource['sampleValues'][number];

export interface RepeatSourceProof {
  expression: string;
  valid: boolean;
  sourceId?: PrototypeRepeatSource['id'];
  sourceGroup?: PrototypeRepeatSource['sourceGroup'];
  path?: PrototypeRepeatSource['path'];
  sourceType?: PrototypeRepeatSource['sourceType'];
  itemStaticSchemaRef?: string;
  optional?: boolean;
  message: string;
}

export interface RepeatPackedChild {
  authoredNodeId: string;
  kind: DesignerNode['kind'];
  label: string;
  xMm: number;
  yMm: number;
  widthMm: number;
  heightMm: number;
}

export interface RepeatPackingTrace {
  kind: RepeatLayoutKind;
  direction?: RepeatDirection;
  columns?: number;
  gapMm?: number;
  columnGapMm?: number;
  rowGapMm?: number;
  widthMm: number;
  heightMm: number;
  children: RepeatPackedChild[];
}

export interface RepeatVirtualOccurrence {
  virtualId: string;
  inputIndex: number;
  label: string;
  value: RepeatSampleValue;
  xMm: number;
  yMm: number;
  widthMm: number;
  heightMm: number;
  children: RepeatPackedChild[];
}

export interface RepeatProjection {
  repeatNodeId: string;
  phase: RepeatConfigurationPhase;
  outcome: RepeatProjectionOutcome;
  message: string;
  source: PrototypeRepeatSource | null;
  sourceProof: RepeatSourceProof;
  compatibleTemplates: PrototypeRepeatTemplateCandidate[];
  authoredChildCount: number;
  itemLayout: RepeatPackingTrace;
  instanceLayout: RepeatPackingTrace;
  occurrences: RepeatVirtualOccurrence[];
}

function nodeProp(node: DesignerNode, label: string): string | undefined {
  return node.props.find((prop) => prop.label === label)?.value;
}

function finiteNonNegative(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, parsed) : fallback;
}

function positiveInteger(value: string | undefined, fallback: number): number {
  const parsed = Math.trunc(Number(value));
  return Number.isFinite(parsed) ? Math.max(1, parsed) : fallback;
}

function layoutKind(value: string | undefined): RepeatLayoutKind {
  return value === 'GRID' ? 'GRID' : 'STACK';
}

function layoutDirection(value: string | undefined): RepeatDirection {
  return value === 'COLUMN' ? 'COLUMN' : 'ROW';
}

function sourceFromExpression(expression: string, authoredDefinitions: readonly DesignerDefinition[]): PrototypeRepeatSource | null {
  const normalized = expression.replaceAll(/\s+/g, '');
  return repeatSourcesForDefinitions(authoredDefinitions)
    .find((source) => normalized === source.expression.replaceAll(/\s+/g, '')) ?? null;
}

export function provePrototypeRepeatSource(
  repeat: DesignerNode,
  authoredDefinitions: readonly DesignerDefinition[] = definitions,
): RepeatSourceProof {
  const expression = nodeProp(repeat, 'items')?.trim() ?? '';
  const source = sourceFromExpression(expression, authoredDefinitions);
  if (!expression) {
    return { expression, valid: false, message: '先选择一个静态可证明为数组的循环数据源' };
  }
  if (!source) {
    return { expression, valid: false, message: '当前表达式无法静态证明为本模板中的数组字段' };
  }
  return {
    expression,
    valid: true,
    sourceId: source.id,
    sourceGroup: source.sourceGroup,
    path: source.path,
    sourceType: source.sourceType,
    itemStaticSchemaRef: source.itemStaticSchemaRef,
    optional: source.optional,
    message: source.sourceType === 'SCALAR_LIST'
      ? `已证明 list<${source.itemValueType ?? 'scalar'}>；单项映射到 ${source.itemStaticSchemaRef}`
      : `已证明 ReferenceValue[]；每项精确引用 ${source.itemStaticSchemaRef}`,
  };
}

function authoredBox(node: DesignerNode, boxes: readonly DraftBox[]): Pick<DraftBox, 'w' | 'h'> {
  const box = boxes.find((candidate) => candidate.nodeId === node.id);
  if (box) return { w: Math.max(0.5, box.w), h: Math.max(0.5, box.h) };
  if (node.kind === 'templateUse') return { w: 24, h: 8 };
  if (node.kind === 'text') return { w: 18, h: 6 };
  return { w: 12, h: 8 };
}

function stackPack(
  repeat: DesignerNode,
  prefix: 'itemLayout' | 'instanceLayout',
  entries: Array<{ id: string; kind: DesignerNode['kind']; label: string; widthMm: number; heightMm: number }>,
): RepeatPackingTrace {
  const direction = layoutDirection(nodeProp(repeat, `${prefix}.direction`));
  const gapMm = finiteNonNegative(nodeProp(repeat, `${prefix}.gapMm`), 0);
  let cursor = 0;
  let cross = 0;
  const children = entries.map((entry) => {
    const packed: RepeatPackedChild = {
      authoredNodeId: entry.id,
      kind: entry.kind,
      label: entry.label,
      xMm: direction === 'ROW' ? cursor : 0,
      yMm: direction === 'COLUMN' ? cursor : 0,
      widthMm: entry.widthMm,
      heightMm: entry.heightMm,
    };
    cursor += (direction === 'ROW' ? entry.widthMm : entry.heightMm) + gapMm;
    cross = Math.max(cross, direction === 'ROW' ? entry.heightMm : entry.widthMm);
    return packed;
  });
  const main = entries.length === 0 ? 0 : Math.max(0, cursor - gapMm);
  return {
    kind: 'STACK',
    direction,
    gapMm,
    widthMm: direction === 'ROW' ? main : cross,
    heightMm: direction === 'COLUMN' ? main : cross,
    children,
  };
}

function gridPack(
  repeat: DesignerNode,
  prefix: 'itemLayout' | 'instanceLayout',
  entries: Array<{ id: string; kind: DesignerNode['kind']; label: string; widthMm: number; heightMm: number }>,
): RepeatPackingTrace {
  const columns = positiveInteger(nodeProp(repeat, `${prefix}.columns`), 1);
  const columnGapMm = finiteNonNegative(nodeProp(repeat, `${prefix}.columnGapMm`), 0);
  const rowGapMm = finiteNonNegative(nodeProp(repeat, `${prefix}.rowGapMm`), 0);
  const rowCount = Math.ceil(entries.length / columns);
  const columnWidths = Array.from({ length: columns }, () => 0);
  const rowHeights = Array.from({ length: rowCount }, () => 0);
  entries.forEach((entry, index) => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    columnWidths[column] = Math.max(columnWidths[column] ?? 0, entry.widthMm);
    rowHeights[row] = Math.max(rowHeights[row] ?? 0, entry.heightMm);
  });
  const columnOffsets = columnWidths.map((_, index) => columnWidths
    .slice(0, index)
    .reduce((sum, width) => sum + width, 0) + index * columnGapMm);
  const rowOffsets = rowHeights.map((_, index) => rowHeights
    .slice(0, index)
    .reduce((sum, height) => sum + height, 0) + index * rowGapMm);
  const children = entries.map((entry, index): RepeatPackedChild => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    return {
      authoredNodeId: entry.id,
      kind: entry.kind,
      label: entry.label,
      xMm: columnOffsets[column] ?? 0,
      yMm: rowOffsets[row] ?? 0,
      widthMm: entry.widthMm,
      heightMm: entry.heightMm,
    };
  });
  return {
    kind: 'GRID',
    columns,
    columnGapMm,
    rowGapMm,
    widthMm: columnWidths.reduce((sum, width) => sum + width, 0) + Math.max(0, columns - 1) * columnGapMm,
    heightMm: rowHeights.reduce((sum, height) => sum + height, 0) + Math.max(0, rowCount - 1) * rowGapMm,
    children,
  };
}

function pack(
  repeat: DesignerNode,
  prefix: 'itemLayout' | 'instanceLayout',
  entries: Array<{ id: string; kind: DesignerNode['kind']; label: string; widthMm: number; heightMm: number }>,
): RepeatPackingTrace {
  return layoutKind(nodeProp(repeat, `${prefix}.kind`)) === 'GRID'
    ? gridPack(repeat, prefix, entries)
    : stackPack(repeat, prefix, entries);
}

function flatten(nodes: readonly DesignerNode[]): DesignerNode[] {
  return nodes.flatMap((node) => [node, ...flatten(node.children)]);
}

function contentMismatch(repeat: DesignerNode, source: PrototypeRepeatSource): string | null {
  for (const node of flatten(repeat.children)) {
    if (node.kind === 'templateUse') {
      const templateId = nodeProp(node, 'templateRef.templateId');
      const candidate = repeatTemplateCandidates.find((entry) => entry.templateId === templateId);
      if (!candidate || candidate.lifecycle !== 'ACTIVE' || candidate.readiness !== 'READY' || candidate.staticSchemaRef !== source.itemStaticSchemaRef) {
        return `子模板与 ${source.itemStaticSchemaRef} 不兼容；保留内容并标记为需修复`;
      }
    }
    if (source.sourceType === 'REFERENCE_LIST') {
      const staleScalarBinding = node.props.some((property) => property.binding?.source.includes('/value'));
      if (staleScalarBinding) return `当前单项内容仍读取标量 /value，与 ${source.itemStaticSchemaRef} 不兼容`;
    }
  }
  return null;
}

function sampleLabel(value: RepeatSampleValue): string {
  if (typeof value === 'object') return `${value.name} · ${value.price}`;
  return String(value);
}

export function projectPrototypeRepeat(
  repeat: DesignerNode,
  boxes: readonly DraftBox[],
  sample: RepeatPreviewSample,
  authoredDefinitions: readonly DesignerDefinition[] = definitions,
): RepeatProjection {
  const sources = repeatSourcesForDefinitions(authoredDefinitions);
  const sourceProof = provePrototypeRepeatSource(repeat, authoredDefinitions);
  const source = sourceProof.sourceId
    ? sources.find((candidate) => candidate.id === sourceProof.sourceId) ?? null
    : null;
  const compatibleTemplates = source
    ? repeatTemplateCandidates.filter((candidate) => candidate.lifecycle === 'ACTIVE'
      && candidate.readiness === 'READY'
      && candidate.staticSchemaRef === source.itemStaticSchemaRef)
    : [];
  const itemEntries = repeat.children.map((node) => {
    const geometry = authoredBox(node, boxes);
    return { id: node.id, kind: node.kind, label: node.name, widthMm: geometry.w, heightMm: geometry.h };
  });
  const itemLayout = pack(repeat, 'itemLayout', itemEntries);
  const mismatch = source ? contentMismatch(repeat, source) : null;
  const phase: RepeatConfigurationPhase = !source
    ? 'SOURCE_REQUIRED'
    : repeat.children.length === 0
      ? 'CONTENT_REQUIRED'
      : mismatch
        ? 'NEEDS_REPAIR'
        : 'READY';

  let values: RepeatSampleValue[] = [];
  let outcome: RepeatProjectionOutcome = 'INVALID';
  let message = sourceProof.message;
  if (phase === 'CONTENT_REQUIRED') message = `数据项类型为 ${source!.itemStaticSchemaRef}；请直接设计一份单项内容或插入兼容模板`;
  if (phase === 'NEEDS_REPAIR') message = mismatch!;
  if (phase === 'READY') {
    if (sample === 'absent') {
      if (nodeProp(repeat, 'absentPolicy') === 'ERROR') {
        outcome = 'ABSENT_ERROR';
        message = '样本中数组缺失；absentPolicy=ERROR 会阻止预览';
      } else {
        outcome = 'EMPTY';
        message = '样本中数组缺失；absentPolicy=EMPTY 将其投影为空列表';
      }
    } else if (sample === 'empty') {
      outcome = 'EMPTY';
      message = '数组存在但为空；Repeat 不生成任何实例';
    } else {
      values = [...source!.sampleValues];
      outcome = values.length > 0 ? 'PROJECTED' : 'EMPTY';
      message = `已从 ${source!.path ?? source!.label} 投影 ${values.length} 个虚拟实例`;
    }
  }

  const instanceEntries = values.map((value, index) => ({
    id: `${repeat.id}::occurrence::${index}`,
    kind: 'repeat' as const,
    label: sampleLabel(value),
    widthMm: itemLayout.widthMm,
    heightMm: itemLayout.heightMm,
  }));
  const instanceLayout = pack(repeat, 'instanceLayout', instanceEntries);
  const occurrences = instanceLayout.children.map((packed, index): RepeatVirtualOccurrence => ({
    virtualId: packed.authoredNodeId,
    inputIndex: index,
    label: packed.label,
    value: values[index]!,
    xMm: packed.xMm,
    yMm: packed.yMm,
    widthMm: packed.widthMm,
    heightMm: packed.heightMm,
    children: itemLayout.children.map((child) => ({ ...child })),
  }));

  return {
    repeatNodeId: repeat.id,
    phase,
    outcome,
    message,
    source,
    sourceProof,
    compatibleTemplates,
    authoredChildCount: repeat.children.length,
    itemLayout,
    instanceLayout,
    occurrences,
  };
}
