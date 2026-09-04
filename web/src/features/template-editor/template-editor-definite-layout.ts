import { finiteTemplateNumber } from './template-editor-numbers';

export type TemplateParentLayout = 'ABSOLUTE' | 'STACK' | 'GRID' | 'PACK';

export type TemplateStructuralLayoutState =
  | { readonly kind: 'repeat'; readonly outcome: 'VALUES'; readonly count: number }
  | { readonly kind: 'repeat'; readonly outcome: 'EMPTY' | 'ABSENT_ERROR' | 'SOURCE_ERROR' }
  | { readonly kind: 'conditional'; readonly outcome: 'TRUE' | 'FALSE' | 'ABSENT_ERROR' | 'SOURCE_ERROR' }
  | {
    readonly kind: 'templateUse';
    readonly outcome: 'READY' | 'NEEDS_REPAIR' | 'SOURCE_ERROR';
    readonly sourceCanvasSizeMm?: Readonly<{ readonly widthMm: number; readonly heightMm: number }>;
  };

export type TemplateStructuralLayoutStates = Readonly<Record<string, TemplateStructuralLayoutState>>;

export interface TemplateRepeatOccurrence {
  readonly repeatNodeId: string;
  readonly ordinal: number;
  readonly worldRect: TemplateLayoutRect;
}

export interface TemplateStructuralLayoutProblem {
  readonly nodeId: string;
  readonly kind: 'repeat' | 'conditional' | 'templateUse';
  readonly outcome: 'ABSENT_ERROR' | 'SOURCE_ERROR' | 'NEEDS_REPAIR';
}

export interface TemplateLayoutRect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

export interface TemplateDefiniteLayoutEntry {
  readonly nodeId: string;
  readonly parentNodeId: string | null;
  readonly kind: string;
  readonly value: Readonly<Record<string, unknown>>;
  readonly localRect: TemplateLayoutRect;
  readonly worldRect: TemplateLayoutRect;
  /** The node's own content box in world coordinates; null for leaves. */
  readonly worldContentRect: TemplateLayoutRect | null;
  readonly paintIndex: number;
  readonly parentLayout: TemplateParentLayout;
}

export type TemplateEditorLayoutProblemCode =
  | 'EDITOR_LAYOUT_CYCLE'
  | 'EDITOR_LAYOUT_CONSTRAINT_INVALID'
  | 'EDITOR_LAYOUT_INTRINSIC_UNSUPPORTED';

export interface TemplateEditorLayoutProblem {
  readonly code: TemplateEditorLayoutProblemCode;
  readonly nodeId: string;
  readonly property: string;
}

export type TemplateDefiniteLayoutResult =
  | {
    readonly state: 'ready';
    readonly entries: readonly TemplateDefiniteLayoutEntry[];
    readonly canvasContentRect: TemplateLayoutRect;
    readonly virtualOccurrences?: readonly TemplateRepeatOccurrence[];
    readonly structuralProblems?: readonly TemplateStructuralLayoutProblem[];
  }
  | {
    readonly state: 'invalid';
    readonly problems: readonly TemplateEditorLayoutProblem[];
  };

export interface TemplateGroupUnionBounds {
  readonly minimumX: number;
  readonly minimumY: number;
  readonly maximumX: number;
  readonly maximumY: number;
  readonly width: number;
  readonly height: number;
}

export type TemplateGroupUnionBoundsResult =
  | { readonly state: 'ready'; readonly bounds: TemplateGroupUnionBounds }
  | { readonly state: 'invalid'; readonly problem: TemplateEditorLayoutProblem };

type NodeValue = Readonly<Record<string, unknown>>;
type SizeMode = 'FIXED' | 'FILL' | 'HUG_CONTENT';
type LayoutAxis = 'width' | 'height';

class LayoutFault extends Error {
  constructor(readonly problem: TemplateEditorLayoutProblem) {
    super(problem.code);
  }
}

interface GroupMeasure {
  readonly width: number;
  readonly height: number;
  readonly minimumX: number;
  readonly minimumY: number;
  readonly maximumX: number;
  readonly maximumY: number;
}

interface DirectChildBounds {
  readonly minimumX: number;
  readonly minimumY: number;
  readonly maximumX: number;
  readonly maximumY: number;
}

type Alignment = 'START' | 'CENTER' | 'END';
type Justification = Alignment | 'SPACE_BETWEEN' | 'SPACE_AROUND' | 'SPACE_EVENLY';

interface StackChildMeasure {
  readonly node: NodeValue;
  readonly nodeId: string;
  readonly placement: NodeValue;
  readonly widthMode: SizeMode;
  readonly heightMode: SizeMode;
  width: number;
  height: number;
  readonly margins: readonly [number, number, number, number];
  readonly alignSelf: Alignment;
  readonly fillWeight: number;
  readonly deferredCrossHug: boolean;
}

interface GridTrackMeasure {
  readonly type: 'FIXED' | 'AUTO' | 'FRACTION';
  readonly weight: number;
  size: number;
}

interface GridAxisMeasure {
  readonly sizes: readonly number[];
  readonly origins: readonly number[];
  readonly gap: number;
}

interface GridChildMeasure {
  readonly node: NodeValue;
  readonly nodeId: string;
  readonly placement: NodeValue;
  readonly column: number;
  readonly columnSpan: number;
  readonly row: number;
  readonly rowSpan: number;
  readonly margins: readonly [number, number, number, number];
  readonly horizontalAlign: Alignment;
  readonly verticalAlign: Alignment;
}

export function projectTemplateDefiniteLayout(
  canvas: unknown,
  structuralStates: TemplateStructuralLayoutStates = {},
): TemplateDefiniteLayoutResult {
  try {
    const authoredRoot = record(canvas, '<canvas>', 'canvas');
    const rootId = text(authoredRoot.nodeId, '<canvas>', 'nodeId');
    if (authoredRoot.kind !== 'canvas') invalid(rootId, 'kind');
    assertAcyclicUniqueTree(authoredRoot);
    const root = suppressStructuralBranches(authoredRoot, structuralStates);
    const width = positive(root.widthMm, rootId, 'widthMm');
    const height = positive(root.heightMm, rootId, 'heightMm');
    const rootRect = rect(0, 0, width, height);
    const projector = new LayoutProjector(structuralStates);
    projector.push(root, null, rootRect, rootRect, 'ABSOLUTE');
    for (const child of staticallyRenderedChildren(root, rootId)) {
      projector.absolute(child, rootId, rootRect);
    }
    return Object.freeze({
      state: 'ready',
      entries: Object.freeze(projector.entries),
      canvasContentRect: rootRect,
      ...(projector.virtualOccurrences.length > 0
        ? { virtualOccurrences: Object.freeze(projector.virtualOccurrences) }
        : {}),
      ...(projector.structuralProblems.length > 0
        ? { structuralProblems: Object.freeze(projector.structuralProblems) }
        : {}),
    });
  } catch (error) {
    const problem = error instanceof LayoutFault
      ? error.problem
      : { code: 'EDITOR_LAYOUT_CONSTRAINT_INVALID' as const, nodeId: '<canvas>', property: 'canvas' };
    return Object.freeze({ state: 'invalid', problems: Object.freeze([Object.freeze(problem)]) });
  }
}

/**
 * Projects the one Group fact structural commands need when preserving world position.
 * The calculation is pure and shares the exact direct-child AABB implementation used by
 * the browser layout projection; callers never need to duplicate Group normalization.
 */
export function projectTemplateGroupUnionBounds(group: unknown): TemplateGroupUnionBoundsResult {
  try {
    const node = record(group, '<group>', 'group');
    const nodeId = text(node.nodeId, '<group>', 'nodeId');
    if (node.kind !== 'group') invalid(nodeId, 'kind');
    assertAcyclicUniqueTree(node);
    const measured = measureGroup(node, {});
    return Object.freeze({
      state: 'ready',
      bounds: Object.freeze({
        minimumX: measured.minimumX,
        minimumY: measured.minimumY,
        maximumX: measured.maximumX,
        maximumY: measured.maximumY,
        width: measured.width,
        height: measured.height,
      }),
    });
  } catch (error) {
    const problem = error instanceof LayoutFault
      ? error.problem
      : { code: 'EDITOR_LAYOUT_CONSTRAINT_INVALID' as const, nodeId: '<group>', property: 'group' };
    return Object.freeze({ state: 'invalid', problem: Object.freeze(problem) });
  }
}

class LayoutProjector {
  readonly entries: TemplateDefiniteLayoutEntry[] = [];
  readonly virtualOccurrences: TemplateRepeatOccurrence[] = [];
  readonly structuralProblems: TemplateStructuralLayoutProblem[] = [];

  constructor(private readonly structuralStates: TemplateStructuralLayoutStates) {}

  push(
    value: NodeValue,
    parentNodeId: string | null,
    localRect: TemplateLayoutRect,
    worldRect: TemplateLayoutRect,
    parentLayout: TemplateParentLayout,
    worldContentRect: TemplateLayoutRect | null = worldRect,
  ): void {
    this.entries.push(Object.freeze({
      nodeId: text(value.nodeId, parentNodeId ?? '<canvas>', 'nodeId'),
      parentNodeId,
      kind: text(value.kind, parentNodeId ?? '<canvas>', 'kind'),
      value,
      localRect,
      worldRect,
      worldContentRect,
      paintIndex: this.entries.length,
      parentLayout,
    }));
  }

  absolute(
    value: unknown,
    parentNodeId: string,
    parentContent: TemplateLayoutRect,
    parentOwnContent: TemplateLayoutRect = parentContent,
  ): void {
    const node = record(value, parentNodeId, 'children');
    const nodeId = text(node.nodeId, parentNodeId, 'nodeId');
    const placement = record(node.placement, nodeId, 'placement');
    if (placement.type !== 'ABSOLUTE') invalid(nodeId, 'placement.type');
    const x = finite(placement.xMm, nodeId, 'placement.xMm');
    const y = finite(placement.yMm, nodeId, 'placement.yMm');
    const kind = text(node.kind, nodeId, 'kind');
    const groupMeasure = kind === 'group' ? measureGroup(node, this.structuralStates) : null;
    const widthMode = sizeMode(placement.widthMode, nodeId, 'placement.widthMode');
    const heightMode = sizeMode(placement.heightMode, nodeId, 'placement.heightMode');
    let width = widthMode === 'HUG_CONTENT'
      ? undefined
      : resolveAbsoluteAxis(placement, 'width', parentContent.width, x, nodeId, undefined);
    let height = heightMode === 'HUG_CONTENT'
      ? undefined
      : resolveAbsoluteAxis(placement, 'height', parentContent.height, y, nodeId, undefined);
    if (width === undefined) {
      width = resolveAbsoluteAxis(
        placement,
        'width',
        parentContent.width,
        x,
        nodeId,
        groupMeasure?.width ?? measureIntrinsicAxis(
          node,
          placement,
          'width',
          heightMode === 'HUG_CONTENT' ? undefined : height,
          this.structuralStates,
        ),
      );
    }
    if (height === undefined) {
      height = resolveAbsoluteAxis(
        placement,
        'height',
        parentContent.height,
        y,
        nodeId,
        groupMeasure?.height ?? measureIntrinsicAxis(
          node,
          placement,
          'height',
          widthMode === 'HUG_CONTENT' ? undefined : width,
          this.structuralStates,
        ),
      );
    }
    const worldRect = rect(parentContent.x + x, parentContent.y + y, width, height);
    const localRect = rect(
      worldRect.x - parentOwnContent.x,
      worldRect.y - parentOwnContent.y,
      width,
      height,
    );

    this.emit(node, parentNodeId, localRect, worldRect, 'ABSOLUTE', groupMeasure);
  }

  private emit(
    node: NodeValue,
    parentNodeId: string,
    localRect: TemplateLayoutRect,
    worldRect: TemplateLayoutRect,
    parentLayout: TemplateParentLayout,
    knownGroupMeasure: GroupMeasure | null = null,
  ): void {
    const nodeId = text(node.nodeId, parentNodeId, 'nodeId');
    const kind = text(node.kind, nodeId, 'kind');
    if (kind === 'group') {
      const groupMeasure = knownGroupMeasure ?? measureGroup(node, this.structuralStates);
      const normalizedContent = rect(
        derivedFinite(worldRect.x - groupMeasure.minimumX, nodeId, 'children'),
        derivedFinite(worldRect.y - groupMeasure.minimumY, nodeId, 'children'),
        worldRect.width,
        worldRect.height,
      );
      this.push(node, parentNodeId, localRect, worldRect, parentLayout, normalizedContent);
      for (const child of staticallyRenderedChildren(node, nodeId)) {
        this.absolute(child, nodeId, normalizedContent, worldRect);
      }
    } else if (kind === 'frame') {
      const content = containerContentRect(node, worldRect, nodeId);
      this.push(node, parentNodeId, localRect, worldRect, parentLayout, content);
      for (const child of staticallyRenderedChildren(node, nodeId)) {
        this.absolute(child, nodeId, content);
      }
    } else if (kind === 'stack') {
      const content = containerContentRect(node, worldRect, nodeId);
      this.push(node, parentNodeId, localRect, worldRect, parentLayout, content);
      this.stack(node, content);
    } else if (kind === 'grid') {
      const content = containerContentRect(node, worldRect, nodeId);
      this.push(node, parentNodeId, localRect, worldRect, parentLayout, content);
      this.grid(node, content);
    } else if (kind === 'conditional') {
      this.push(node, parentNodeId, localRect, worldRect, parentLayout, worldRect);
      this.captureStructuralProblem(nodeId, kind);
      for (const child of staticallyRenderedChildren(node, nodeId)) {
        this.absolute(child, nodeId, worldRect);
      }
    } else if (kind === 'repeat') {
      this.repeat(node, parentNodeId, localRect, worldRect, parentLayout);
    } else if (kind === 'templateUse') {
      this.push(node, parentNodeId, localRect, worldRect, parentLayout, null);
      this.captureStructuralProblem(nodeId, kind);
    } else {
      this.push(node, parentNodeId, localRect, worldRect, parentLayout, null);
    }
  }

  private repeat(
    node: NodeValue,
    parentNodeId: string,
    localRect: TemplateLayoutRect,
    worldRect: TemplateLayoutRect,
    parentLayout: TemplateParentLayout,
  ): void {
    const nodeId = text(node.nodeId, parentNodeId, 'nodeId');
    this.push(node, parentNodeId, localRect, worldRect, parentLayout, worldRect);
    const state = this.structuralStates[nodeId];
    if (!state || state.kind !== 'repeat' || state.outcome !== 'VALUES') {
      this.captureStructuralProblem(nodeId, 'repeat');
      return;
    }
    const count = nonnegativeInteger(state.count, nodeId, 'occurrences');
    if (count === 0) return;
    const children = staticallyRenderedChildren(node, nodeId);
    const item = packNodes(
      children,
      node.itemLayout,
      nodeId,
      'itemLayout',
      this.structuralStates,
    );
    const occurrenceSizes = Array.from({ length: count }, () => ({
      width: item.width,
      height: item.height,
    }));
    const instances = packSizes(occurrenceSizes, node.instanceLayout, nodeId, 'instanceLayout');
    instances.rects.forEach((occurrence, index) => {
      this.virtualOccurrences.push(Object.freeze({
        repeatNodeId: nodeId,
        ordinal: index + 1,
        worldRect: rect(
          worldRect.x + occurrence.x,
          worldRect.y + occurrence.y,
          occurrence.width,
          occurrence.height,
        ),
      }));
    });
    const first = instances.rects[0];
    if (!first) return;
    item.rects.forEach((childRect, index) => {
      const child = children[index];
      if (!child) return;
      const childNode = record(child, nodeId, 'children');
      const projected = rect(
        worldRect.x + first.x + childRect.x,
        worldRect.y + first.y + childRect.y,
        childRect.width,
        childRect.height,
      );
      this.emit(
        childNode,
        nodeId,
        rect(first.x + childRect.x, first.y + childRect.y, childRect.width, childRect.height),
        projected,
        'PACK',
      );
    });
  }

  private captureStructuralProblem(
    nodeId: string,
    kind: 'repeat' | 'conditional' | 'templateUse',
  ): void {
    const state = this.structuralStates[nodeId];
    if (!state || state.kind !== kind) return;
    if (state.outcome !== 'ABSENT_ERROR'
      && state.outcome !== 'SOURCE_ERROR'
      && state.outcome !== 'NEEDS_REPAIR') return;
    this.structuralProblems.push(Object.freeze({ nodeId, kind, outcome: state.outcome }));
  }

  private stack(stack: NodeValue, content: TemplateLayoutRect): void {
    const stackId = text(stack.nodeId, '<stack>', 'nodeId');
    const direction = optionalEnum(stack.direction, 'COLUMN', ['ROW', 'COLUMN'], stackId, 'direction');
    const gap = optionalNonnegative(stack.gapMm, 0, stackId, 'gapMm');
    const justify = optionalEnum(
      stack.justifyContent,
      'START',
      ['START', 'CENTER', 'END', 'SPACE_BETWEEN', 'SPACE_AROUND', 'SPACE_EVENLY'],
      stackId,
      'justifyContent',
    ) as Justification;
    const alignItems = optionalEnum(
      stack.alignItems,
      'START',
      ['START', 'CENTER', 'END'],
      stackId,
      'alignItems',
    ) as Alignment;
    const row = direction === 'ROW';
    const values = staticallyRenderedChildren(stack, stackId);
    const measured = values.map((value) => measureStackChild(
      value,
      row,
      content,
      alignItems,
      this.structuralStates,
    ));
    const availableMain = row ? content.width : content.height;
    let usedWithoutFill = gap * Math.max(0, measured.length - 1);
    const fillIndices: number[] = [];
    for (let index = 0; index < measured.length; index += 1) {
      const child = measured[index]!;
      const [top, right, bottom, left] = child.margins;
      const mainFill = row ? child.widthMode === 'FILL' : child.heightMode === 'FILL';
      usedWithoutFill += row ? left + right : top + bottom;
      if (mainFill) fillIndices.push(index);
      else usedWithoutFill += row ? child.width : child.height;
    }
    allocateStackFill(measured, fillIndices, row, Math.max(0, availableMain - usedWithoutFill));
    for (const child of measured) {
      if (!child.deferredCrossHug) continue;
      if (row) {
        child.height = measureIntrinsicAxis(
          child.node,
          child.placement,
          'height',
          child.width,
          this.structuralStates,
        );
      } else {
        child.width = measureIntrinsicAxis(
          child.node,
          child.placement,
          'width',
          child.height,
          this.structuralStates,
        );
      }
    }
    let occupied = usedWithoutFill;
    for (const index of fillIndices) occupied += row ? measured[index]!.width : measured[index]!.height;
    const distribution = stackDistribution(justify, Math.max(0, availableMain - Math.max(0, occupied)), measured.length);
    let cursor = distribution.leading;
    for (let index = 0; index < measured.length; index += 1) {
      const child = measured[index]!;
      const [top, right, bottom, left] = child.margins;
      const mainLeading = row ? left : top;
      const mainTrailing = row ? right : bottom;
      cursor += mainLeading;
      const crossPosition = alignedPosition(
        row ? content.height : content.width,
        row ? top : left,
        row ? bottom : right,
        row ? child.height : child.width,
        child.alignSelf,
      );
      const local = row
        ? rect(cursor, crossPosition, child.width, child.height)
        : rect(crossPosition, cursor, child.width, child.height);
      const world = rect(content.x + local.x, content.y + local.y, local.width, local.height);
      this.emit(child.node, stackId, local, world, 'STACK');
      cursor += row ? child.width : child.height;
      cursor += mainTrailing;
      if (index + 1 < measured.length) cursor += gap + (distribution.between[index] ?? 0);
    }
  }

  private grid(grid: NodeValue, content: TemplateLayoutRect): void {
    const gridId = text(grid.nodeId, '<grid>', 'nodeId');
    const columnGap = optionalNonnegative(grid.columnGapMm, 0, gridId, 'columnGapMm');
    const rowGap = optionalNonnegative(grid.rowGapMm, 0, gridId, 'rowGapMm');
    const columnTracks = gridTracks(grid.columns, gridId, 'columns');
    const rowTracks = gridTracks(grid.rows, gridId, 'rows');
    const measured = staticallyRenderedChildren(grid, gridId).map((value) => measureGridChild(
      value,
      columnTracks.length,
      rowTracks.length,
    ));
    const columns = solveGridAxis(
      measured,
      columnTracks,
      columnGap,
      content.width,
      'width',
      this.structuralStates,
    );
    const rows = solveGridAxis(
      measured,
      rowTracks,
      rowGap,
      content.height,
      'height',
      this.structuralStates,
      columns,
    );

    for (const child of measured) {
      const columnWidth = spannedSize(columns, child.column, child.columnSpan);
      const rowHeight = spannedSize(rows, child.row, child.rowSpan);
      const [top, right, bottom, left] = child.margins;
      const widthOffer = resolveGridDefiniteAxis(child, 'width', columnWidth, left, right);
      const heightOffer = resolveGridDefiniteAxis(child, 'height', rowHeight, top, bottom);
      const width = widthOffer
        ?? resolveGridChildAxis(child, 'width', heightOffer, this.structuralStates);
      const height = heightOffer
        ?? resolveGridChildAxis(child, 'height', widthOffer, this.structuralStates);
      const x = columns.origins[child.column]!
        + gridAxisPosition(child, 'width', columnWidth, left, right, width);
      const y = rows.origins[child.row]!
        + gridAxisPosition(child, 'height', rowHeight, top, bottom, height);
      const local = rect(x, y, width, height);
      const world = rect(content.x + x, content.y + y, width, height);
      this.emit(child.node, gridId, local, world, 'GRID');
    }
  }
}

function gridTracks(
  value: unknown,
  nodeId: string,
  property: 'columns' | 'rows',
): GridTrackMeasure[] {
  if (!Array.isArray(value) || value.length === 0) invalid(nodeId, property);
  return value.map((candidate, index) => {
    const track = record(candidate, nodeId, `${property}[${index}]`);
    if (track.type === 'FIXED') {
      return {
        type: 'FIXED' as const,
        weight: 0,
        size: positive(track.valueMm, nodeId, `${property}[${index}].valueMm`),
      };
    }
    if (track.type === 'AUTO') {
      return { type: 'AUTO' as const, weight: 0, size: 0 };
    }
    if (track.type === 'FRACTION') {
      return {
        type: 'FRACTION' as const,
        weight: positive(track.weight, nodeId, `${property}[${index}].weight`),
        size: 0,
      };
    }
    invalid(nodeId, `${property}[${index}].type`);
  });
}

function measureGridChild(
  value: unknown,
  columnCount: number,
  rowCount: number,
): GridChildMeasure {
  const node = record(value, '<grid>', 'children');
  const nodeId = text(node.nodeId, '<grid>', 'nodeId');
  const placement = record(node.placement, nodeId, 'placement');
  if (placement.type !== 'GRID') invalid(nodeId, 'placement.type');
  const column = nonnegativeInteger(placement.column, nodeId, 'placement.column');
  const row = nonnegativeInteger(placement.row, nodeId, 'placement.row');
  const columnSpan = positiveInteger(placement.columnSpan ?? 1, nodeId, 'placement.columnSpan');
  const rowSpan = positiveInteger(placement.rowSpan ?? 1, nodeId, 'placement.rowSpan');
  if (column + columnSpan > columnCount) invalid(nodeId, 'placement.columnSpan');
  if (row + rowSpan > rowCount) invalid(nodeId, 'placement.rowSpan');
  sizeMode(placement.widthMode, nodeId, 'placement.widthMode');
  sizeMode(placement.heightMode, nodeId, 'placement.heightMode');
  return {
    node,
    nodeId,
    placement,
    column,
    columnSpan,
    row,
    rowSpan,
    margins: Object.freeze([
      optionalFinite(placement.marginTopMm, 0, nodeId, 'placement.marginTopMm'),
      optionalFinite(placement.marginRightMm, 0, nodeId, 'placement.marginRightMm'),
      optionalFinite(placement.marginBottomMm, 0, nodeId, 'placement.marginBottomMm'),
      optionalFinite(placement.marginLeftMm, 0, nodeId, 'placement.marginLeftMm'),
    ] as const),
    horizontalAlign: optionalEnum(
      placement.horizontalAlignSelf,
      'START',
      ['START', 'CENTER', 'END'],
      nodeId,
      'placement.horizontalAlignSelf',
    ) as Alignment,
    verticalAlign: optionalEnum(
      placement.verticalAlignSelf,
      'START',
      ['START', 'CENTER', 'END'],
      nodeId,
      'placement.verticalAlignSelf',
    ) as Alignment,
  };
}

function solveGridAxis(
  children: readonly GridChildMeasure[],
  tracks: GridTrackMeasure[],
  gap: number,
  available: number,
  axis: 'width' | 'height',
  structuralStates: TemplateStructuralLayoutStates,
  oppositeAxisMeasure?: GridAxisMeasure,
): GridAxisMeasure {
  const constraints: Array<{
    readonly start: number;
    readonly span: number;
    readonly authoredIndex: number;
    readonly contribution: number;
  }> = [];
  for (let authoredIndex = 0; authoredIndex < children.length; authoredIndex += 1) {
    const child = children[authoredIndex]!;
    const start = axis === 'width' ? child.column : child.row;
    const span = axis === 'width' ? child.columnSpan : child.rowSpan;
    const covered = tracks.slice(start, start + span);
    if (!covered.some((track) => track.type === 'AUTO')) continue;
    const mode = sizeMode(
      child.placement[`${axis}Mode`],
      child.nodeId,
      `placement.${axis}Mode`,
    );
    if (mode === 'FILL') cycle(child.nodeId, `placement.${axis}Mode`);
    const size = gridChildNaturalSize(
      child,
      axis,
      mode,
      resolvedGridOppositeOuter(child, axis, oppositeAxisMeasure),
      structuralStates,
    );
    const [top, right, bottom, left] = child.margins;
    const contribution = Math.max(0, size + (axis === 'width' ? left + right : top + bottom));
    constraints.push({ start, span, authoredIndex, contribution });
  }
  constraints.sort((left, right) => left.span - right.span
    || left.start - right.start
    || left.authoredIndex - right.authoredIndex);
  for (const constraint of constraints) {
    const autoIndices: number[] = [];
    let occupied = gap * Math.max(0, constraint.span - 1);
    for (let index = constraint.start; index < constraint.start + constraint.span; index += 1) {
      const track = tracks[index]!;
      occupied += track.size;
      if (track.type === 'AUTO') autoIndices.push(index);
    }
    const deficit = Math.max(0, constraint.contribution - occupied);
    const increments = stableSlots(deficit, autoIndices.length);
    for (let index = 0; index < autoIndices.length; index += 1) {
      tracks[autoIndices[index]!]!.size += increments[index]!;
    }
  }

  const fractionIndices = tracks
    .map((track, index) => track.type === 'FRACTION' ? index : -1)
    .filter((index) => index >= 0);
  const used = tracks.reduce((total, track) => total + track.size, 0)
    + gap * Math.max(0, tracks.length - 1);
  const remaining = Math.max(0, available - used);
  const totalWeight = fractionIndices.reduce((total, index) => total + tracks[index]!.weight, 0);
  let assigned = 0;
  for (let position = 0; position < fractionIndices.length; position += 1) {
    const index = fractionIndices[position]!;
    const size = position + 1 === fractionIndices.length
      ? remaining - assigned
      : remaining * tracks[index]!.weight / totalWeight;
    tracks[index]!.size = size;
    assigned += size;
  }

  const sizes = tracks.map(({ size }) => size);
  const origins: number[] = [];
  let cursor = 0;
  for (const size of sizes) {
    origins.push(cursor);
    cursor += size + gap;
  }
  return { sizes: Object.freeze(sizes), origins: Object.freeze(origins), gap };
}

function gridChildNaturalSize(
  child: GridChildMeasure,
  axis: 'width' | 'height',
  mode: SizeMode,
  oppositeOuter: number | undefined,
  structuralStates: TemplateStructuralLayoutStates,
): number {
  if (mode === 'FIXED') return constrainedFixed(child.placement, axis, child.nodeId);
  if (mode === 'FILL') cycle(child.nodeId, `placement.${axis}Mode`);
  return measureIntrinsicAxis(
    child.node,
    child.placement,
    axis,
    oppositeOuter,
    structuralStates,
  );
}

function resolveGridChildAxis(
  child: GridChildMeasure,
  axis: 'width' | 'height',
  oppositeOuter: number | undefined,
  structuralStates: TemplateStructuralLayoutStates,
): number {
  const mode = sizeMode(
    child.placement[`${axis}Mode`],
    child.nodeId,
    `placement.${axis}Mode`,
  );
  if (mode !== 'HUG_CONTENT') invalid(child.nodeId, `placement.${axis}Mode`);
  return gridChildNaturalSize(child, axis, mode, oppositeOuter, structuralStates);
}

function resolveGridDefiniteAxis(
  child: GridChildMeasure,
  axis: LayoutAxis,
  available: number,
  leading: number,
  trailing: number,
): number | undefined {
  const mode = sizeMode(
    child.placement[`${axis}Mode`],
    child.nodeId,
    `placement.${axis}Mode`,
  );
  if (mode === 'HUG_CONTENT') return undefined;
  if (mode === 'FIXED') return constrainedFixed(child.placement, axis, child.nodeId);
  return clamp(
    Math.max(0, available - leading - trailing),
    child.placement,
    axis,
    child.nodeId,
  );
}

function resolvedGridOppositeOuter(
  child: GridChildMeasure,
  measuredAxis: LayoutAxis,
  oppositeAxisMeasure: GridAxisMeasure | undefined,
): number | undefined {
  const opposite = oppositeAxis(measuredAxis);
  const mode = sizeMode(
    child.placement[`${opposite}Mode`],
    child.nodeId,
    `placement.${opposite}Mode`,
  );
  if (mode === 'FIXED') return constrainedFixed(child.placement, opposite, child.nodeId);
  if (mode === 'HUG_CONTENT' || oppositeAxisMeasure === undefined) return undefined;
  const start = opposite === 'width' ? child.column : child.row;
  const span = opposite === 'width' ? child.columnSpan : child.rowSpan;
  const [top, right, bottom, left] = child.margins;
  return clamp(
    Math.max(
      0,
      spannedSize(oppositeAxisMeasure, start, span)
        - (opposite === 'width' ? left + right : top + bottom),
    ),
    child.placement,
    opposite,
    child.nodeId,
  );
}

function gridAxisPosition(
  child: GridChildMeasure,
  axis: 'width' | 'height',
  available: number,
  leading: number,
  trailing: number,
  size: number,
): number {
  const mode = sizeMode(
    child.placement[`${axis}Mode`],
    child.nodeId,
    `placement.${axis}Mode`,
  );
  if (mode === 'FILL') return leading;
  return alignedPosition(
    available,
    leading,
    trailing,
    size,
    axis === 'width' ? child.horizontalAlign : child.verticalAlign,
  );
}

function spannedSize(axis: GridAxisMeasure, start: number, span: number): number {
  let size = axis.gap * Math.max(0, span - 1);
  for (let index = start; index < start + span; index += 1) size += axis.sizes[index]!;
  return size;
}

function measureGroup(
  group: NodeValue,
  structuralStates: TemplateStructuralLayoutStates,
): GroupMeasure {
  const nodeId = text(group.nodeId, '<group>', 'nodeId');
  const values = staticallyRenderedChildren(group, nodeId);
  if (values.length === 0) {
    return {
      width: 0, height: 0,
      minimumX: 0, minimumY: 0,
      maximumX: 0, maximumY: 0,
    };
  }
  let minimumX = Number.POSITIVE_INFINITY;
  let minimumY = Number.POSITIVE_INFINITY;
  let maximumX = Number.NEGATIVE_INFINITY;
  let maximumY = Number.NEGATIVE_INFINITY;
  for (const value of values) {
    const bounds = measureAbsoluteDirectChildBounds(value, nodeId, structuralStates);
    minimumX = derivedFinite(Math.min(minimumX, bounds.minimumX), nodeId, 'children');
    minimumY = derivedFinite(Math.min(minimumY, bounds.minimumY), nodeId, 'children');
    maximumX = derivedFinite(Math.max(maximumX, bounds.maximumX), nodeId, 'children');
    maximumY = derivedFinite(Math.max(maximumY, bounds.maximumY), nodeId, 'children');
  }
  return {
    width: derivedFinite(maximumX - minimumX, nodeId, 'children'),
    height: derivedFinite(maximumY - minimumY, nodeId, 'children'),
    minimumX,
    minimumY,
    maximumX,
    maximumY,
  };
}

function measureFrameAxis(
  frame: NodeValue,
  axis: LayoutAxis,
  oppositeOuter: number | undefined,
  structuralStates: TemplateStructuralLayoutStates,
): number {
  const nodeId = text(frame.nodeId, '<frame>', 'nodeId');
  const oppositeContent = oppositeOuter === undefined
    ? undefined
    : containerAxisContentSize(frame, oppositeAxis(axis), oppositeOuter, nodeId);
  let contentExtent = 0;
  for (const value of staticallyRenderedChildren(frame, nodeId)) {
    const bounds = measureAbsoluteDirectChildBounds(
      value,
      nodeId,
      structuralStates,
      axis === 'height' ? oppositeContent : undefined,
      axis === 'width' ? oppositeContent : undefined,
    );
    contentExtent = Math.max(
      contentExtent,
      axis === 'width' ? bounds.maximumX : bounds.maximumY,
    );
  }
  return derivedFinite(contentExtent + axisInsetExtent(frame, axis, nodeId), nodeId, 'children');
}

function measureAbsoluteDirectChildBounds(
  value: unknown,
  parentNodeId: string,
  structuralStates: TemplateStructuralLayoutStates,
  parentWidth: number | undefined = undefined,
  parentHeight: number | undefined = undefined,
): DirectChildBounds {
  const child = record(value, parentNodeId, 'children');
  const childId = text(child.nodeId, parentNodeId, 'nodeId');
  const placement = record(child.placement, childId, 'placement');
  if (placement.type !== 'ABSOLUTE') invalid(childId, 'placement.type');
  const x = finite(placement.xMm, childId, 'placement.xMm');
  const y = finite(placement.yMm, childId, 'placement.yMm');
  const widthMode = sizeMode(placement.widthMode, childId, 'placement.widthMode');
  const heightMode = sizeMode(placement.heightMode, childId, 'placement.heightMode');
  const groupMeasure = child.kind === 'group' ? measureGroup(child, structuralStates) : null;
  let width = resolveDirectChildDefiniteAxis(
    placement,
    'width',
    widthMode,
    parentWidth,
    x,
    childId,
  );
  let height = resolveDirectChildDefiniteAxis(
    placement,
    'height',
    heightMode,
    parentHeight,
    y,
    childId,
  );
  if (width === undefined) {
    width = groupMeasure?.width ?? measureIntrinsicAxis(
      child,
      placement,
      'width',
      heightMode === 'HUG_CONTENT' ? undefined : height,
      structuralStates,
    );
  }
  if (height === undefined) {
    height = groupMeasure?.height ?? measureIntrinsicAxis(
      child,
      placement,
      'height',
      widthMode === 'HUG_CONTENT' ? undefined : width,
      structuralStates,
    );
  }
  return transformedLayoutBoxBounds(child, childId, x, y, width, height);
}

function resolveDirectChildDefiniteAxis(
  placement: NodeValue,
  axis: LayoutAxis,
  mode: SizeMode,
  parentSize: number | undefined,
  start: number,
  nodeId: string,
): number | undefined {
  if (mode === 'HUG_CONTENT') return undefined;
  if (mode === 'FILL' && parentSize === undefined) cycle(nodeId, `placement.${axis}Mode`);
  return resolveAbsoluteAxis(placement, axis, parentSize ?? 0, start, nodeId, undefined);
}

function transformedLayoutBoxBounds(
  node: NodeValue,
  nodeId: string,
  x: number,
  y: number,
  width: number,
  height: number,
): DirectChildBounds {
  if (node.transform === undefined) {
    return {
      minimumX: x,
      minimumY: y,
      maximumX: derivedFinite(x + width, nodeId, 'placement'),
      maximumY: derivedFinite(y + height, nodeId, 'placement'),
    };
  }
  const transform = record(node.transform, nodeId, 'transform');
  const rotation = finite(transform.rotationDeg, nodeId, 'transform.rotationDeg');
  const quarterTurns = rotation / 90;
  if (!Number.isSafeInteger(quarterTurns)) {
    intrinsicUnsupported(nodeId, 'transform.rotationDeg');
  }
  const scaleX = finite(transform.scaleX, nodeId, 'transform.scaleX');
  const scaleY = finite(transform.scaleY, nodeId, 'transform.scaleY');
  if (scaleX === 0) invalid(nodeId, 'transform.scaleX');
  if (scaleY === 0) invalid(nodeId, 'transform.scaleY');
  const originX = finite(transform.originX, nodeId, 'transform.originX');
  const originY = finite(transform.originY, nodeId, 'transform.originY');
  if (originX < 0 || originX > 1) invalid(nodeId, 'transform.originX');
  if (originY < 0 || originY > 1) invalid(nodeId, 'transform.originY');
  const originOffsetX = derivedFinite(originX * width, nodeId, 'transform');
  const originOffsetY = derivedFinite(originY * height, nodeId, 'transform');
  const origin = {
    x: derivedFinite(x + originOffsetX, nodeId, 'transform'),
    y: derivedFinite(y + originOffsetY, nodeId, 'transform'),
  };
  const normalizedQuarterTurns = ((quarterTurns % 4) + 4) % 4;
  const right = derivedFinite(x + width, nodeId, 'transform');
  const bottom = derivedFinite(y + height, nodeId, 'transform');
  const points = [
    [x, y],
    [right, y],
    [x, bottom],
    [right, bottom],
  ].map(([pointX, pointY]) => {
    const offsetX = derivedFinite(pointX! - origin.x, nodeId, 'transform');
    const offsetY = derivedFinite(pointY! - origin.y, nodeId, 'transform');
    const scaledX = derivedFinite(offsetX * scaleX, nodeId, 'transform');
    const scaledY = derivedFinite(offsetY * scaleY, nodeId, 'transform');
    const rotatedX = normalizedQuarterTurns === 0
      ? scaledX
      : normalizedQuarterTurns === 1
        ? -scaledY
        : normalizedQuarterTurns === 2
          ? -scaledX
          : scaledY;
    const rotatedY = normalizedQuarterTurns === 0
      ? scaledY
      : normalizedQuarterTurns === 1
        ? scaledX
        : normalizedQuarterTurns === 2
          ? -scaledY
          : -scaledX;
    return {
      x: derivedFinite(origin.x + rotatedX, nodeId, 'transform'),
      y: derivedFinite(origin.y + rotatedY, nodeId, 'transform'),
    };
  });
  return {
    minimumX: derivedFinite(Math.min(...points.map((point) => point.x)), nodeId, 'transform'),
    minimumY: derivedFinite(Math.min(...points.map((point) => point.y)), nodeId, 'transform'),
    maximumX: derivedFinite(Math.max(...points.map((point) => point.x)), nodeId, 'transform'),
    maximumY: derivedFinite(Math.max(...points.map((point) => point.y)), nodeId, 'transform'),
  };
}

function measureStackChild(
  value: unknown,
  row: boolean,
  content: TemplateLayoutRect,
  inheritedAlign: Alignment,
  structuralStates: TemplateStructuralLayoutStates,
): StackChildMeasure {
  const node = record(value, '<stack>', 'children');
  const nodeId = text(node.nodeId, '<stack>', 'nodeId');
  const placement = record(node.placement, nodeId, 'placement');
  if (placement.type !== 'STACK') invalid(nodeId, 'placement.type');
  const widthMode = sizeMode(placement.widthMode, nodeId, 'placement.widthMode');
  const heightMode = sizeMode(placement.heightMode, nodeId, 'placement.heightMode');
  const margins = stackMargins(placement, nodeId);
  const mainMode = row ? widthMode : heightMode;
  const resolvedWidthFill = widthMode === 'FILL' && !row
    ? clamp(Math.max(0, content.width - margins[3] - margins[1]), placement, 'width', nodeId)
    : undefined;
  const resolvedHeightFill = heightMode === 'FILL' && row
    ? clamp(Math.max(0, content.height - margins[0] - margins[2]), placement, 'height', nodeId)
    : undefined;
  const deferredCrossHug = row
    ? widthMode === 'FILL' && heightMode === 'HUG_CONTENT'
    : heightMode === 'FILL' && widthMode === 'HUG_CONTENT';
  const width = widthMode === 'FIXED'
    ? constrainedFixed(placement, 'width', nodeId)
    : widthMode === 'HUG_CONTENT'
      ? deferredCrossHug
        ? 0
        : measureIntrinsicAxis(
          node,
          placement,
          'width',
          heightMode === 'FIXED'
            ? constrainedFixed(placement, 'height', nodeId)
            : resolvedHeightFill,
          structuralStates,
        )
      : row
        ? 0
        : resolvedWidthFill!;
  const height = heightMode === 'FIXED'
    ? constrainedFixed(placement, 'height', nodeId)
    : heightMode === 'HUG_CONTENT'
      ? deferredCrossHug
        ? 0
        : measureIntrinsicAxis(
          node,
          placement,
          'height',
          widthMode === 'FIXED'
            ? constrainedFixed(placement, 'width', nodeId)
            : resolvedWidthFill,
          structuralStates,
        )
      : row
        ? resolvedHeightFill!
        : 0;
  const fillWeight = mainMode === 'FILL'
    ? positive(placement.fillWeight ?? 1, nodeId, 'placement.fillWeight')
    : 1;
  if (mainMode !== 'FILL' && placement.fillWeight !== undefined) {
    invalid(nodeId, 'placement.fillWeight');
  }
  return {
    node, nodeId, placement, widthMode, heightMode, width, height, margins,
    alignSelf: optionalEnum(
      placement.alignSelf,
      inheritedAlign,
      ['START', 'CENTER', 'END'],
      nodeId,
      'placement.alignSelf',
    ) as Alignment,
    fillWeight,
    deferredCrossHug,
  };
}

function allocateStackFill(
  children: StackChildMeasure[],
  indices: readonly number[],
  row: boolean,
  available: number,
): void {
  if (indices.length === 0) return;
  if (indices.length === 1) {
    setStackMain(children[indices[0]!]!, row, clamp(
      available,
      children[indices[0]!]!.placement,
      row ? 'width' : 'height',
      children[indices[0]!]!.nodeId,
    ));
    return;
  }
  const allocations = new Map<number, number>();
  const active = new Set(indices);
  while (active.size > 0) {
    const frozen = [...allocations.values()].reduce((sum, value) => sum + value, 0);
    const remaining = Math.max(0, available - frozen);
    const activeIndices = indices.filter((index) => active.has(index));
    const totalWeight = activeIndices.reduce((sum, index) => sum + children[index]!.fillWeight, 0);
    let allocatedBeforeLast = 0;
    const provisional = new Map<number, number>();
    for (let position = 0; position < activeIndices.length; position += 1) {
      const index = activeIndices[position]!;
      const share = position + 1 === activeIndices.length
        ? remaining - allocatedBeforeLast
        : remaining * children[index]!.fillWeight / totalWeight;
      provisional.set(index, share);
      allocatedBeforeLast += share;
    }

    const activeBounds: Array<{ readonly index: number; readonly bound: number }> = [];
    for (const index of activeIndices) {
      const child = children[index]!;
      const axis = row ? 'width' : 'height';
      const { minimum, maximum } = axisBounds(child.placement, axis, child.nodeId);
      const share = provisional.get(index)!;
      if (share < minimum) activeBounds.push({ index, bound: minimum });
      else if (share > maximum) activeBounds.push({ index, bound: maximum });
    }
    if (activeBounds.length > 0) {
      for (const { index, bound } of activeBounds) {
        allocations.set(index, bound);
        active.delete(index);
      }
      continue;
    }

    for (const [index, share] of provisional) allocations.set(index, share);
    break;
  }
  for (const index of indices) setStackMain(children[index]!, row, allocations.get(index) ?? 0);
}

function setStackMain(child: StackChildMeasure, row: boolean, value: number): void {
  if (row) child.width = value;
  else child.height = value;
}

function stackDistribution(
  justify: Justification,
  free: number,
  count: number,
): { readonly leading: number; readonly between: readonly number[] } {
  const between = Array.from({ length: Math.max(0, count - 1) }, () => 0);
  if (justify === 'END') return { leading: free, between };
  if (justify === 'CENTER') return { leading: free / 2, between };
  if (justify === 'SPACE_BETWEEN' && count > 1) {
    return { leading: 0, between: stableSlots(free, count - 1) };
  }
  if (justify === 'SPACE_EVENLY' && count > 0) {
    const slots = stableSlots(free, count + 1);
    return { leading: slots[0]!, between: slots.slice(1, count) };
  }
  if (justify === 'SPACE_AROUND' && count > 0) {
    const slots = stableSlots(free, count);
    return {
      leading: slots[0]! / 2,
      between: between.map((_, index) => slots[index]! / 2 + slots[index + 1]! / 2),
    };
  }
  return { leading: 0, between };
}

function stableSlots(total: number, count: number): number[] {
  if (count === 0) return [];
  const unit = total / count;
  let remaining = total;
  return Array.from({ length: count }, (_, index) => {
    const value = index + 1 === count ? remaining : unit;
    remaining -= value;
    return value;
  });
}

function alignedPosition(
  available: number,
  leading: number,
  trailing: number,
  size: number,
  alignment: Alignment,
): number {
  const remaining = available - leading - trailing - size;
  return leading + (alignment === 'CENTER' ? remaining / 2 : alignment === 'END' ? remaining : 0);
}

function measureStackAxis(
  stack: NodeValue,
  axis: LayoutAxis,
  oppositeOuter: number | undefined,
  structuralStates: TemplateStructuralLayoutStates,
): number {
  const stackId = text(stack.nodeId, '<stack>', 'nodeId');
  const direction = optionalEnum(stack.direction, 'COLUMN', ['ROW', 'COLUMN'], stackId, 'direction');
  const mainAxis: LayoutAxis = direction === 'ROW' ? 'width' : 'height';
  const gap = optionalNonnegative(stack.gapMm, 0, stackId, 'gapMm');
  const oppositeContent = oppositeOuter === undefined
    ? undefined
    : containerAxisContentSize(stack, oppositeAxis(axis), oppositeOuter, stackId);
  const values = staticallyRenderedChildren(stack, stackId);
  if (axis === mainAxis) {
    let cursor = 0;
    let extent = 0;
    for (let index = 0; index < values.length; index += 1) {
      const child = record(values[index], stackId, 'children');
      const childId = text(child.nodeId, stackId, 'nodeId');
      const childPlacement = stackChildPlacement(child, childId);
      const [leading, trailing] = stackAxisMargins(childPlacement, axis, childId);
      const childOppositeOuter = resolveStackChildOppositeOuter(
        childPlacement,
        axis,
        oppositeContent,
        childId,
      );
      const size = resolveMeasuredNodeAxis(
        child,
        childPlacement,
        axis,
        childOppositeOuter,
        structuralStates,
      );
      for (const addition of [leading, size, trailing]) {
        cursor += addition;
        extent = Math.max(extent, cursor);
      }
      if (index + 1 < values.length) {
        cursor += gap;
        extent = Math.max(extent, cursor);
      }
    }
    return extent + axisInsetExtent(stack, axis, stackId);
  }

  const mainSizes = oppositeContent === undefined
    ? null
    : resolveStackMainSizes(stack, direction === 'ROW', oppositeContent, structuralStates);
  let extent = 0;
  for (const value of values) {
    const child = record(value, stackId, 'children');
    const childId = text(child.nodeId, stackId, 'nodeId');
    const childPlacement = stackChildPlacement(child, childId);
    const [leading, trailing] = stackAxisMargins(childPlacement, axis, childId);
    const childOppositeOuter = mainSizes?.get(child)
      ?? fixedOppositeOuter(childPlacement, axis, childId);
    const size = resolveMeasuredNodeAxis(
      child,
      childPlacement,
      axis,
      childOppositeOuter,
      structuralStates,
    );
    extent = Math.max(extent, leading + size + trailing);
  }
  return extent + axisInsetExtent(stack, axis, stackId);
}

function resolveStackMainSizes(
  stack: NodeValue,
  row: boolean,
  available: number,
  structuralStates: TemplateStructuralLayoutStates,
): ReadonlyMap<NodeValue, number> {
  const stackId = text(stack.nodeId, '<stack>', 'nodeId');
  const alignItems = optionalEnum(
    stack.alignItems,
    'START',
    ['START', 'CENTER', 'END'],
    stackId,
    'alignItems',
  ) as Alignment;
  const measured = staticallyRenderedChildren(stack, stackId).map((value): StackChildMeasure => {
    const node = record(value, stackId, 'children');
    const nodeId = text(node.nodeId, stackId, 'nodeId');
    const childPlacement = stackChildPlacement(node, nodeId);
    const widthMode = sizeMode(childPlacement.widthMode, nodeId, 'placement.widthMode');
    const heightMode = sizeMode(childPlacement.heightMode, nodeId, 'placement.heightMode');
    const mainAxis: LayoutAxis = row ? 'width' : 'height';
    const mainMode = row ? widthMode : heightMode;
    const margins = stackMargins(childPlacement, nodeId);
    const mainSize = mainMode === 'FILL'
      ? 0
      : resolveMeasuredNodeAxis(
          node,
          childPlacement,
          mainAxis,
          fixedOppositeOuter(childPlacement, mainAxis, nodeId),
          structuralStates,
        );
    const fillWeight = mainMode === 'FILL'
      ? positive(childPlacement.fillWeight ?? 1, nodeId, 'placement.fillWeight')
      : 1;
    return {
      node,
      nodeId,
      placement: childPlacement,
      widthMode,
      heightMode,
      width: row ? mainSize : 0,
      height: row ? 0 : mainSize,
      margins,
      alignSelf: optionalEnum(
        childPlacement.alignSelf,
        alignItems,
        ['START', 'CENTER', 'END'],
        nodeId,
        'placement.alignSelf',
      ) as Alignment,
      fillWeight,
      deferredCrossHug: false,
    };
  });
  const gap = optionalNonnegative(stack.gapMm, 0, stackId, 'gapMm');
  let usedWithoutFill = gap * Math.max(0, measured.length - 1);
  const fillIndices: number[] = [];
  for (let index = 0; index < measured.length; index += 1) {
    const child = measured[index]!;
    const [top, right, bottom, left] = child.margins;
    const fill = row ? child.widthMode === 'FILL' : child.heightMode === 'FILL';
    usedWithoutFill += row ? left + right : top + bottom;
    if (fill) fillIndices.push(index);
    else usedWithoutFill += row ? child.width : child.height;
  }
  allocateStackFill(measured, fillIndices, row, Math.max(0, available - usedWithoutFill));
  return new Map(measured.map((child) => [child.node, row ? child.width : child.height]));
}

function stackChildPlacement(node: NodeValue, nodeId: string): NodeValue {
  const placement = record(node.placement, nodeId, 'placement');
  if (placement.type !== 'STACK') invalid(nodeId, 'placement.type');
  return placement;
}

function stackMargins(
  placement: NodeValue,
  nodeId: string,
): readonly [number, number, number, number] {
  return Object.freeze([
    optionalFinite(placement.marginTopMm, 0, nodeId, 'placement.marginTopMm'),
    optionalFinite(placement.marginRightMm, 0, nodeId, 'placement.marginRightMm'),
    optionalFinite(placement.marginBottomMm, 0, nodeId, 'placement.marginBottomMm'),
    optionalFinite(placement.marginLeftMm, 0, nodeId, 'placement.marginLeftMm'),
  ] as const);
}

function stackAxisMargins(
  placement: NodeValue,
  axis: LayoutAxis,
  nodeId: string,
): readonly [number, number] {
  const [top, right, bottom, left] = stackMargins(placement, nodeId);
  return axis === 'width' ? [left, right] : [top, bottom];
}

function resolveStackChildOppositeOuter(
  placement: NodeValue,
  measuredAxis: LayoutAxis,
  parentOppositeContent: number | undefined,
  nodeId: string,
): number | undefined {
  const opposite = oppositeAxis(measuredAxis);
  const mode = sizeMode(placement[`${opposite}Mode`], nodeId, `placement.${opposite}Mode`);
  if (mode === 'FIXED') return constrainedFixed(placement, opposite, nodeId);
  if (mode === 'HUG_CONTENT' || parentOppositeContent === undefined) return undefined;
  const [leading, trailing] = stackAxisMargins(placement, opposite, nodeId);
  return clamp(
    Math.max(0, parentOppositeContent - leading - trailing),
    placement,
    opposite,
    nodeId,
  );
}

function measureGridIntrinsicAxis(
  grid: NodeValue,
  axis: LayoutAxis,
  oppositeOuter: number | undefined,
  structuralStates: TemplateStructuralLayoutStates,
): number {
  const gridId = text(grid.nodeId, '<grid>', 'nodeId');
  const measured = staticallyRenderedChildren(grid, gridId).map((value) => measureGridChild(
    value,
    gridTrackCount(grid.columns, gridId, 'columns'),
    gridTrackCount(grid.rows, gridId, 'rows'),
  ));
  if (axis === 'width') {
    const tracks = gridTracks(grid.columns, gridId, 'columns');
    if (tracks.some((track) => track.type === 'FRACTION')) invalid(gridId, 'columns');
    const gap = optionalNonnegative(grid.columnGapMm, 0, gridId, 'columnGapMm');
    const columns = solveGridAxis(measured, tracks, gap, 0, 'width', structuralStates);
    return gridAxisExtent(columns) + axisInsetExtent(grid, axis, gridId);
  }

  let columns: GridAxisMeasure | undefined;
  if (oppositeOuter !== undefined) {
    const contentWidth = containerAxisContentSize(grid, 'width', oppositeOuter, gridId);
    columns = solveGridAxis(
      measured,
      gridTracks(grid.columns, gridId, 'columns'),
      optionalNonnegative(grid.columnGapMm, 0, gridId, 'columnGapMm'),
      contentWidth,
      'width',
      structuralStates,
    );
  }
  const rowTracks = gridTracks(grid.rows, gridId, 'rows');
  if (rowTracks.some((track) => track.type === 'FRACTION')) invalid(gridId, 'rows');
  const rows = solveGridAxis(
    measured,
    rowTracks,
    optionalNonnegative(grid.rowGapMm, 0, gridId, 'rowGapMm'),
    0,
    'height',
    structuralStates,
    columns,
  );
  return gridAxisExtent(rows) + axisInsetExtent(grid, axis, gridId);
}

function gridTrackCount(
  value: unknown,
  nodeId: string,
  property: 'columns' | 'rows',
): number {
  if (!Array.isArray(value) || value.length === 0) invalid(nodeId, property);
  return value.length;
}

function gridAxisExtent(axis: GridAxisMeasure): number {
  return axis.sizes.reduce((total, size) => total + size, 0)
    + axis.gap * Math.max(0, axis.sizes.length - 1);
}

function fixedOppositeOuter(
  placement: NodeValue,
  measuredAxis: LayoutAxis,
  nodeId: string,
): number | undefined {
  const opposite = oppositeAxis(measuredAxis);
  return sizeMode(placement[`${opposite}Mode`], nodeId, `placement.${opposite}Mode`) === 'FIXED'
    ? constrainedFixed(placement, opposite, nodeId)
    : undefined;
}

function oppositeAxis(axis: LayoutAxis): LayoutAxis {
  return axis === 'width' ? 'height' : 'width';
}

function axisInsetExtent(node: NodeValue, axis: LayoutAxis, nodeId: string): number {
  const insets = containerInsets(node, nodeId);
  return axis === 'width' ? insets.left + insets.right : insets.top + insets.bottom;
}

function containerAxisContentSize(
  node: NodeValue,
  axis: LayoutAxis,
  outerSize: number,
  nodeId: string,
): number {
  return Math.max(0, outerSize - axisInsetExtent(node, axis, nodeId));
}

function measureIntrinsicAxis(
  node: NodeValue,
  placement: NodeValue,
  axis: LayoutAxis,
  oppositeOuter: number | undefined,
  structuralStates: TemplateStructuralLayoutStates,
): number {
  const nodeId = text(node.nodeId, '<node>', 'nodeId');
  const kind = text(node.kind, nodeId, 'kind');
  let natural: number;
  if (kind === 'group') {
    const group = measureGroup(node, structuralStates);
    natural = axis === 'width' ? group.width : group.height;
  } else if (kind === 'frame' || kind === 'conditional') {
    natural = measureFrameAxis(node, axis, oppositeOuter, structuralStates);
  } else if (kind === 'stack') {
    natural = measureStackAxis(node, axis, oppositeOuter, structuralStates);
  } else if (kind === 'grid') {
    natural = measureGridIntrinsicAxis(node, axis, oppositeOuter, structuralStates);
  } else if (kind === 'repeat') {
    const repeat = measureRepeatNaturalSize(node, structuralStates);
    natural = axis === 'width' ? repeat.width : repeat.height;
  } else if (kind === 'templateUse') {
    natural = measureTemplateUseNaturalAxis(nodeId, axis, structuralStates);
  } else {
    intrinsicUnsupported(nodeId, `placement.${axis}Mode`);
  }
  return clamp(natural, placement, axis, nodeId);
}

function resolveMeasuredNodeAxis(
  node: NodeValue,
  placement: NodeValue,
  axis: LayoutAxis,
  oppositeOuter: number | undefined,
  structuralStates: TemplateStructuralLayoutStates,
): number {
  const nodeId = text(node.nodeId, '<node>', 'nodeId');
  const mode = sizeMode(placement[`${axis}Mode`], nodeId, `placement.${axis}Mode`);
  if (mode === 'FILL') cycle(nodeId, `placement.${axis}Mode`);
  if (mode === 'HUG_CONTENT') {
    return measureIntrinsicAxis(node, placement, axis, oppositeOuter, structuralStates);
  }
  return constrainedFixed(placement, axis, nodeId);
}

function measureRepeatNaturalSize(
  node: NodeValue,
  structuralStates: TemplateStructuralLayoutStates,
): PackedSize {
  const nodeId = text(node.nodeId, '<repeat>', 'nodeId');
  const state = structuralStates[nodeId];
  const count = state?.kind === 'repeat' && state.outcome === 'VALUES'
    ? nonnegativeInteger(state.count, nodeId, 'occurrences')
    : state?.kind === 'repeat' && state.outcome === 'EMPTY'
      ? 0
      : 1;
  if (count === 0) return { width: 0, height: 0 };
  const item = packNodes(
    staticallyRenderedChildren(node, nodeId),
    node.itemLayout,
    nodeId,
    'itemLayout',
    structuralStates,
  );
  const instances = packSizes(
    Array.from({ length: count }, () => ({ width: item.width, height: item.height })),
    node.instanceLayout,
    nodeId,
    'instanceLayout',
  );
  return { width: instances.width, height: instances.height };
}

function measureTemplateUseNaturalAxis(
  nodeId: string,
  axis: LayoutAxis,
  structuralStates: TemplateStructuralLayoutStates,
): number {
  const state = structuralStates[nodeId];
  if (state?.kind !== 'templateUse'
    || state.outcome !== 'READY'
    || state.sourceCanvasSizeMm === undefined) {
    intrinsicUnsupported(nodeId, `placement.${axis}Mode`);
  }
  return positive(
    state.sourceCanvasSizeMm[axis === 'width' ? 'widthMm' : 'heightMm'],
    nodeId,
    `sourceCanvasSizeMm.${axis}Mm`,
  );
}

function resolveAbsoluteAxis(
  placement: NodeValue,
  axis: 'width' | 'height',
  parentSize: number,
  start: number,
  nodeId: string,
  intrinsic: number | undefined,
): number {
  const mode = sizeMode(placement[`${axis}Mode`], nodeId, `placement.${axis}Mode`);
  if (mode === 'FIXED') return constrainedFixed(placement, axis, nodeId);
  if (mode === 'HUG_CONTENT') {
    if (intrinsic === undefined) intrinsicUnsupported(nodeId, `placement.${axis}Mode`);
    return clamp(intrinsic, placement, axis, nodeId);
  }
  const endMember = axis === 'width' ? 'rightInsetMm' : 'bottomInsetMm';
  const end = optionalFinite(placement[endMember], 0, nodeId, `placement.${endMember}`);
  return clamp(Math.max(0, parentSize - start - end), placement, axis, nodeId);
}

function constrainedFixed(placement: NodeValue, axis: 'width' | 'height', nodeId: string): number {
  const value = positive(placement[`${axis}Mm`], nodeId, `placement.${axis}Mm`);
  const clamped = clamp(value, placement, axis, nodeId);
  if (clamped !== value) invalid(nodeId, `placement.${axis}Mm`);
  return value;
}

function clamp(value: number, placement: NodeValue, axis: 'width' | 'height', nodeId: string): number {
  const { minimum, maximum } = axisBounds(placement, axis, nodeId);
  return Math.min(maximum, Math.max(minimum, value));
}

function axisBounds(
  placement: NodeValue,
  axis: 'width' | 'height',
  nodeId: string,
): { readonly minimum: number; readonly maximum: number } {
  const title = axis === 'width' ? 'Width' : 'Height';
  const minimum = optionalFinite(placement[`min${title}Mm`], 0, nodeId, `placement.min${title}Mm`);
  const maximum = placement[`max${title}Mm`] === undefined
    ? Number.POSITIVE_INFINITY
    : positive(placement[`max${title}Mm`], nodeId, `placement.max${title}Mm`);
  if (minimum < 0 || minimum > maximum) invalid(nodeId, `placement.min${title}Mm`);
  return { minimum, maximum };
}

function children(node: NodeValue, nodeId: string): readonly unknown[] {
  if (!Array.isArray(node.children)) invalid(nodeId, 'children');
  return node.children;
}

function staticallyRenderedChildren(node: NodeValue, nodeId: string): readonly unknown[] {
  return children(node, nodeId).filter((value) => (
    record(value, nodeId, 'children').render !== false
  ));
}

function suppressStructuralBranches(
  node: NodeValue,
  states: TemplateStructuralLayoutStates,
): NodeValue {
  const nodeId = text(node.nodeId, '<node>', 'nodeId');
  const state = states[nodeId];
  let suppressed = node.kind === 'conditional'
    && state?.kind === 'conditional'
    && state.outcome === 'FALSE';
  suppressed ||= node.kind === 'repeat'
    && state?.kind === 'repeat'
    && (state.outcome === 'EMPTY' || (state.outcome === 'VALUES' && state.count === 0));
  if (!Array.isArray(node.children)) return suppressed ? { ...node, render: false } : node;
  const projectedChildren = node.children.map((value) => (
    suppressStructuralBranches(record(value, nodeId, 'children'), states)
  ));
  suppressed ||= node.kind === 'repeat'
    && state?.kind === 'repeat'
    && state.outcome === 'VALUES'
    && projectedChildren.every((child) => child.render === false);
  return {
    ...node,
    ...(suppressed ? { render: false } : {}),
    children: projectedChildren,
  };
}

interface PackedSize {
  readonly width: number;
  readonly height: number;
}

interface PackedProjection {
  readonly width: number;
  readonly height: number;
  readonly rects: readonly TemplateLayoutRect[];
}

function packNodes(
  values: readonly unknown[],
  spec: unknown,
  parentNodeId: string,
  property: string,
  structuralStates: TemplateStructuralLayoutStates,
): PackedProjection {
  const sizes = values.map((value): PackedSize => {
    const node = record(value, parentNodeId, 'children');
    const nodeId = text(node.nodeId, parentNodeId, 'nodeId');
    const placement = record(node.placement, nodeId, 'placement');
    if (placement.type !== 'PACK') invalid(nodeId, 'placement.type');
    const widthMode = sizeMode(placement.widthMode, nodeId, 'placement.widthMode');
    const heightMode = sizeMode(placement.heightMode, nodeId, 'placement.heightMode');
    if (widthMode === 'FILL' || heightMode === 'FILL') invalid(nodeId, 'placement');
    const fixedWidth = widthMode === 'FIXED' ? constrainedFixed(placement, 'width', nodeId) : undefined;
    const fixedHeight = heightMode === 'FIXED' ? constrainedFixed(placement, 'height', nodeId) : undefined;
    const width = fixedWidth ?? measureIntrinsicAxis(
      node,
      placement,
      'width',
      fixedHeight,
      structuralStates,
    );
    const height = fixedHeight ?? measureIntrinsicAxis(
      node,
      placement,
      'height',
      width,
      structuralStates,
    );
    return { width, height };
  });
  return packSizes(sizes, spec, parentNodeId, property);
}

function packSizes(
  sizes: readonly PackedSize[],
  specValue: unknown,
  nodeId: string,
  property: string,
): PackedProjection {
  const spec = record(specValue, nodeId, property);
  if (spec.kind === 'STACK') {
    const direction = optionalEnum(
      spec.direction,
      'COLUMN',
      ['ROW', 'COLUMN'],
      nodeId,
      `${property}.direction`,
    );
    const gap = optionalNonnegative(spec.gapMm, 0, nodeId, `${property}.gapMm`);
    let cursor = 0;
    const rects = sizes.map((size) => {
      const projected = direction === 'ROW'
        ? rect(cursor, 0, size.width, size.height)
        : rect(0, cursor, size.width, size.height);
      cursor += (direction === 'ROW' ? size.width : size.height) + gap;
      return projected;
    });
    return Object.freeze({
      width: direction === 'ROW'
        ? sizes.reduce((total, size) => total + size.width, 0) + gap * Math.max(0, sizes.length - 1)
        : Math.max(0, ...sizes.map((size) => size.width)),
      height: direction === 'COLUMN'
        ? sizes.reduce((total, size) => total + size.height, 0) + gap * Math.max(0, sizes.length - 1)
        : Math.max(0, ...sizes.map((size) => size.height)),
      rects: Object.freeze(rects),
    });
  }
  if (spec.kind !== 'GRID') invalid(nodeId, `${property}.kind`);
  const columnCount = positiveInteger(spec.columns, nodeId, `${property}.columns`);
  const columnGap = optionalNonnegative(spec.columnGapMm, 0, nodeId, `${property}.columnGapMm`);
  const rowGap = optionalNonnegative(spec.rowGapMm, 0, nodeId, `${property}.rowGapMm`);
  const effectiveColumnCount = Math.min(columnCount, sizes.length);
  if (effectiveColumnCount === 0) {
    return Object.freeze({ width: 0, height: 0, rects: Object.freeze([]) });
  }
  const rowCount = Math.ceil(sizes.length / effectiveColumnCount);
  const columnWidths = Array.from({ length: effectiveColumnCount }, () => 0);
  const rowHeights = Array.from({ length: rowCount }, () => 0);
  sizes.forEach((size, index) => {
    const column = index % effectiveColumnCount;
    const row = Math.floor(index / effectiveColumnCount);
    columnWidths[column] = Math.max(columnWidths[column] ?? 0, size.width);
    rowHeights[row] = Math.max(rowHeights[row] ?? 0, size.height);
  });
  const origins = (values: readonly number[], gap: number): number[] => {
    let cursor = 0;
    return values.map((value) => {
      const origin = cursor;
      cursor += value + gap;
      return origin;
    });
  };
  const columnOrigins = origins(columnWidths, columnGap);
  const rowOrigins = origins(rowHeights, rowGap);
  const rects = sizes.map((size, index) => rect(
    columnOrigins[index % effectiveColumnCount] ?? 0,
    rowOrigins[Math.floor(index / effectiveColumnCount)] ?? 0,
    size.width,
    size.height,
  ));
  return Object.freeze({
    width: columnWidths.reduce((total, value) => total + value, 0)
      + columnGap * Math.max(0, columnWidths.length - 1),
    height: rowHeights.reduce((total, value) => total + value, 0)
      + rowGap * Math.max(0, rowHeights.length - 1),
    rects: Object.freeze(rects),
  });
}

function assertAcyclicUniqueTree(root: NodeValue): void {
  const active = new WeakSet<object>();
  const nodeIds = new Set<string>();
  const visit = (value: unknown, parentId: string): void => {
    const node = record(value, parentId, 'children');
    const nodeId = text(node.nodeId, parentId, 'nodeId');
    if (active.has(node)) cycle(nodeId, 'children');
    if (nodeIds.has(nodeId)) invalid(nodeId, 'nodeId');
    nodeIds.add(nodeId);
    active.add(node);
    const kind = text(node.kind, nodeId, 'kind');
    if (kind === 'group') validateGroupPlacement(record(node.placement, nodeId, 'placement'), nodeId);
    if (kind === 'canvas' || kind === 'group' || kind === 'frame' || kind === 'stack'
      || kind === 'grid' || kind === 'repeat' || kind === 'conditional') {
      for (const child of children(node, nodeId)) visit(child, nodeId);
    }
    active.delete(node);
  };
  visit(root, '<canvas>');
}

function validateGroupPlacement(placement: NodeValue, nodeId: string): void {
  if (placement.widthMode !== 'HUG_CONTENT') invalid(nodeId, 'placement.widthMode');
  if (placement.heightMode !== 'HUG_CONTENT') invalid(nodeId, 'placement.heightMode');
  for (const member of ['minWidthMm', 'maxWidthMm', 'minHeightMm', 'maxHeightMm']) {
    if (placement[member] !== undefined) invalid(nodeId, `placement.${member}`);
  }
}

function containerContentRect(
  node: NodeValue,
  outer: TemplateLayoutRect,
  nodeId: string,
): TemplateLayoutRect {
  const insets = containerInsets(node, nodeId);
  return rect(
    outer.x + insets.left,
    outer.y + insets.top,
    Math.max(0, outer.width - insets.left - insets.right),
    Math.max(0, outer.height - insets.top - insets.bottom),
  );
}

function containerInsets(node: NodeValue, nodeId: string): {
  readonly top: number; readonly right: number; readonly bottom: number; readonly left: number;
} {
  const stroke = node.stroke === undefined ? null : record(node.stroke, nodeId, 'stroke');
  const strokeWidth = stroke === null ? 0 : nonnegative(stroke.widthMm, nodeId, 'stroke.widthMm');
  const padding = node.padding === undefined ? {} : record(node.padding, nodeId, 'padding');
  return {
    top: strokeWidth + optionalNonnegative(padding.topMm, 0, nodeId, 'padding.topMm'),
    right: strokeWidth + optionalNonnegative(padding.rightMm, 0, nodeId, 'padding.rightMm'),
    bottom: strokeWidth + optionalNonnegative(padding.bottomMm, 0, nodeId, 'padding.bottomMm'),
    left: strokeWidth + optionalNonnegative(padding.leftMm, 0, nodeId, 'padding.leftMm'),
  };
}

function rect(x: number, y: number, width: number, height: number): TemplateLayoutRect {
  return Object.freeze({ x, y, width, height });
}

function record(value: unknown, nodeId: string, property: string): NodeValue {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) invalid(nodeId, property);
  return value as NodeValue;
}

function text(value: unknown, nodeId: string, property: string): string {
  if (typeof value !== 'string' || value.length === 0) invalid(nodeId, property);
  return value;
}

function sizeMode(value: unknown, nodeId: string, property: string): SizeMode {
  if (value !== 'FIXED' && value !== 'FILL' && value !== 'HUG_CONTENT') invalid(nodeId, property);
  return value;
}

function optionalEnum(
  value: unknown,
  fallback: string,
  allowed: readonly string[],
  nodeId: string,
  property: string,
): string {
  if (value === undefined) return fallback;
  if (typeof value !== 'string' || !allowed.includes(value)) invalid(nodeId, property);
  return value;
}

function positive(value: unknown, nodeId: string, property: string): number {
  const result = finite(value, nodeId, property);
  if (result <= 0) invalid(nodeId, property);
  return result;
}

function nonnegative(value: unknown, nodeId: string, property: string): number {
  const result = finite(value, nodeId, property);
  if (result < 0) invalid(nodeId, property);
  return result;
}

function nonnegativeInteger(value: unknown, nodeId: string, property: string): number {
  const result = nonnegative(value, nodeId, property);
  if (!Number.isSafeInteger(result)) invalid(nodeId, property);
  return result;
}

function positiveInteger(value: unknown, nodeId: string, property: string): number {
  const result = positive(value, nodeId, property);
  if (!Number.isSafeInteger(result)) invalid(nodeId, property);
  return result;
}

function optionalNonnegative(value: unknown, fallback: number, nodeId: string, property: string): number {
  return value === undefined ? fallback : nonnegative(value, nodeId, property);
}

function optionalFinite(value: unknown, fallback: number, nodeId: string, property: string): number {
  return value === undefined ? fallback : finite(value, nodeId, property);
}

function finite(value: unknown, nodeId: string, property: string): number {
  const result = finiteTemplateNumber(value);
  if (result === null) invalid(nodeId, property);
  return result;
}

function derivedFinite(value: number, nodeId: string, property: string): number {
  if (!Number.isFinite(value)) invalid(nodeId, property);
  return value;
}

function invalid(nodeId: string, property: string): never {
  throw new LayoutFault({ code: 'EDITOR_LAYOUT_CONSTRAINT_INVALID', nodeId, property });
}

function cycle(nodeId: string, property: string): never {
  throw new LayoutFault({ code: 'EDITOR_LAYOUT_CYCLE', nodeId, property });
}

function intrinsicUnsupported(nodeId: string, property: string): never {
  throw new LayoutFault({ code: 'EDITOR_LAYOUT_INTRINSIC_UNSUPPORTED', nodeId, property });
}
