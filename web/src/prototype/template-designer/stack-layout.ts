/**
 * Browser-only definite layout projection for the T220 authoring prototype.
 *
 * The reducer keeps authored geometry stable. This module derives effective
 * Stack and Grid canvas boxes on every render so ordering and inspector edits
 * are immediately visible without introducing a second persisted document shape.
 */
import { canvasProjection, type DesignerNode, type DraftBox } from './model';

export type PrototypeStackDirection = 'VERTICAL' | 'HORIZONTAL';
export type PrototypeMainAlign = 'START' | 'CENTER' | 'END' | 'SPACE_BETWEEN';
export type PrototypeCrossAlign = 'START' | 'CENTER' | 'END' | 'STRETCH';
export type PrototypeSizeMode = 'FIXED' | 'FILL';
export type PrototypeAlignSelf = 'INHERIT' | 'START' | 'CENTER' | 'END';
export type PrototypeResolvedCrossAlign = Exclude<PrototypeAlignSelf, 'INHERIT'>;
export type PrototypeGridAlign = 'START' | 'CENTER' | 'END';
export type PrototypeGridTrackKind = 'FIXED' | 'AUTO' | 'FRACTION';

export interface PrototypeLayoutRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface StackPlacementTrace {
  nodeId: string;
  order: number;
  box: PrototypeLayoutRect;
  widthMode: PrototypeSizeMode;
  heightMode: PrototypeSizeMode;
  mainFill: boolean;
  crossFill: boolean;
  fillWeight: number | null;
  mainAllocationMm: number | null;
  margins: { top: number; right: number; bottom: number; left: number };
  alignSelf: PrototypeAlignSelf;
  resolvedCrossAlign: PrototypeResolvedCrossAlign;
}

export interface StackLayoutTrace {
  containerId: string;
  containerName: string;
  direction: PrototypeStackDirection;
  mainAlign: PrototypeMainAlign;
  crossAlign: PrototypeCrossAlign;
  contentBox: PrototypeLayoutRect;
  gapMm: number;
  effectiveBetweenGapsMm: number[];
  availableMainMm: number;
  usedWithoutFillMm: number;
  fillAvailableMainMm: number;
  fillCount: number;
  occupiedMainMm: number;
  freeMainMm: number;
  overflowMainMm: number;
  missingChildIds: string[];
  placements: StackPlacementTrace[];
}

export interface GridTrackTrace {
  index: number;
  kind: PrototypeGridTrackKind;
  token: string;
  originMm: number;
  sizeMm: number;
  valueMm: number | null;
  weight: number | null;
}

export interface GridPlacementTrace {
  nodeId: string;
  order: number;
  row: number;
  column: number;
  rowSpan: number;
  columnSpan: number;
  cell: PrototypeLayoutRect;
  box: PrototypeLayoutRect;
  widthMode: PrototypeSizeMode;
  heightMode: PrototypeSizeMode;
  margins: { top: number; right: number; bottom: number; left: number };
  horizontalAlignSelf: PrototypeGridAlign;
  verticalAlignSelf: PrototypeGridAlign;
}

export interface GridLayoutTrace {
  containerId: string;
  containerName: string;
  contentBox: PrototypeLayoutRect;
  columnGapMm: number;
  rowGapMm: number;
  columns: GridTrackTrace[];
  rows: GridTrackTrace[];
  occupiedWidthMm: number;
  occupiedHeightMm: number;
  freeWidthMm: number;
  freeHeightMm: number;
  overflowWidthMm: number;
  overflowHeightMm: number;
  autoConstraintCount: number;
  missingChildIds: string[];
  problems: string[];
  placements: GridPlacementTrace[];
}

export interface AbsolutePlacementTrace {
  nodeId: string;
  order: number;
  localBox: PrototypeLayoutRect;
  box: PrototypeLayoutRect;
}

export interface AbsoluteContainerLayoutTrace {
  containerId: string;
  containerName: string;
  kind: 'group' | 'frame' | 'conditional';
  layoutBox: PrototypeLayoutRect;
  contentBox: PrototypeLayoutRect | null;
  sourceBounds: PrototypeLayoutRect | null;
  missingChildIds: string[];
  placements: AbsolutePlacementTrace[];
}

export interface PrototypeLayoutProjection {
  boxes: DraftBox[];
  boxByNodeId: ReadonlyMap<string, DraftBox>;
  absoluteByContainerId: ReadonlyMap<string, AbsoluteContainerLayoutTrace>;
  stackByContainerId: ReadonlyMap<string, StackLayoutTrace>;
  gridByContainerId: ReadonlyMap<string, GridLayoutTrace>;
}

export interface PrototypeLayoutOptions {
  /** Nodes removed by runtime structure expansion before layout (for example false Conditional branches). */
  excludedNodeIds?: ReadonlySet<string>;
}

interface StackSpec {
  direction: PrototypeStackDirection;
  mainAlign: PrototypeMainAlign;
  crossAlign: PrototypeCrossAlign;
  gapMm: number;
}

interface StackChildMeasurement {
  child: DesignerNode;
  box: DraftBox;
  widthMode: PrototypeSizeMode;
  heightMode: PrototypeSizeMode;
  width: number;
  height: number;
  margins: { top: number; right: number; bottom: number; left: number };
  alignSelf: PrototypeAlignSelf;
  resolvedCrossAlign: PrototypeResolvedCrossAlign;
  mainFill: boolean;
  crossFill: boolean;
  fillWeight: number;
  mainMinimum?: number;
  mainMaximum?: number;
}

interface PrototypeGridTrackDefinition {
  kind: PrototypeGridTrackKind;
  token: string;
  valueMm?: number;
  weight?: number;
}

interface ParsedGridTrackList {
  tracks: PrototypeGridTrackDefinition[];
  problems: string[];
}

interface GridSpec {
  columns: ParsedGridTrackList;
  rows: ParsedGridTrackList;
  columnGapMm: number;
  rowGapMm: number;
}

interface GridChildMeasurement {
  child: DesignerNode;
  box: DraftBox;
  order: number;
  row: number;
  column: number;
  rowSpan: number;
  columnSpan: number;
  widthMode: PrototypeSizeMode;
  heightMode: PrototypeSizeMode;
  margins: { top: number; right: number; bottom: number; left: number };
  horizontalAlignSelf: PrototypeGridAlign;
  verticalAlignSelf: PrototypeGridAlign;
}

interface SolvedGridAxis {
  tracks: GridTrackTrace[];
  gapMm: number;
  occupiedMm: number;
  freeMm: number;
  overflowMm: number;
  autoConstraintCount: number;
}

export const PROTOTYPE_GRID_TRACK_LIMIT = 64;

export interface PrototypeGridTrackToken {
  canonicalToken: string;
  kind: PrototypeGridTrackKind;
  valueMm?: number;
  weight?: number;
}

/**
 * Browser authoring shorthand, modelled after design-layout-draw's GridLength list.
 * Commas are canonical; whitespace-only lists remain readable for earlier prototype state.
 */
export function prototypeGridTrackTokens(rawValue: string | undefined): string[] {
  const raw = rawValue?.trim() ?? '';
  if (raw === '') return [];
  return raw.includes(',')
    ? raw.split(',').map((token) => token.trim())
    : raw.split(/\s+/).filter(Boolean);
}

export function parsePrototypeGridTrackToken(rawToken: string): PrototypeGridTrackToken | null {
  const normalized = rawToken.trim().toLocaleLowerCase();
  if (normalized === 'auto') return { canonicalToken: 'auto', kind: 'AUTO' };
  if (normalized === '*') return { canonicalToken: '1*', kind: 'FRACTION', weight: 1 };

  const fixed = normalized.match(/^([+]?(?:\d+(?:\.\d*)?|\.\d+))(?:mm)?$/);
  if (fixed) {
    const valueMm = Number(fixed[1]);
    if (Number.isFinite(valueMm) && valueMm > 0) {
      return { canonicalToken: String(valueMm), kind: 'FIXED', valueMm };
    }
  }

  const fraction = normalized.match(/^([+]?(?:\d+(?:\.\d*)?|\.\d+))(?:\*|fr)$/);
  if (fraction) {
    const weight = Number(fraction[1]);
    if (Number.isFinite(weight) && weight > 0) {
      return { canonicalToken: `${weight}*`, kind: 'FRACTION', weight };
    }
  }
  return null;
}

export function serializePrototypeGridTrackTokens(tokens: readonly string[]): string {
  return tokens.join(', ');
}

function nodeProp(node: DesignerNode, label: string): string | undefined {
  return node.props.find((prop) => prop.label === label)?.value;
}

function finiteProp(node: DesignerNode, label: string, fallback: number): number {
  const parsed = Number(nodeProp(node, label));
  return Number.isFinite(parsed) ? parsed : fallback;
}

function optionalFiniteProp(node: DesignerNode, label: string): number | undefined {
  const value = nodeProp(node, label);
  if (value === undefined || value.trim() === '') return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function nonnegativeProp(node: DesignerNode, label: string, fallback = 0): number {
  return Math.max(0, finiteProp(node, label, fallback));
}

function positiveSubtract(size: number, inset: number): number {
  const remaining = size - inset;
  return remaining > 0 ? remaining : 0;
}

function positiveZero(value: number): number {
  return value > 0 ? value : 0;
}

function sizeMode(node: DesignerNode, axis: 'width' | 'height'): PrototypeSizeMode {
  return nodeProp(node, `placement.${axis}Mode`) === 'FILL' ? 'FILL' : 'FIXED';
}

function alignSelf(node: DesignerNode): PrototypeAlignSelf {
  const value = nodeProp(node, 'placement.alignSelf');
  return value === 'START' || value === 'CENTER' || value === 'END' ? value : 'INHERIT';
}

function axisMinimum(node: DesignerNode, axis: 'Width' | 'Height'): number | undefined {
  const minimum = optionalFiniteProp(node, `placement.min${axis}Mm`);
  return minimum === undefined ? undefined : Math.max(0, minimum);
}

function axisMaximum(node: DesignerNode, axis: 'Width' | 'Height'): number | undefined {
  const maximum = optionalFiniteProp(node, `placement.max${axis}Mm`);
  return maximum === undefined ? undefined : Math.max(0, maximum);
}

function clampFlexibleAxis(size: number, minimum?: number, maximum?: number): number {
  let clamped = size;
  if (minimum !== undefined && clamped < minimum) clamped = minimum;
  if (maximum !== undefined && clamped > maximum) clamped = maximum;
  return clamped;
}

function stackSpec(node: DesignerNode): StackSpec | null {
  if (node.kind === 'canvas' && nodeProp(node, 'layoutMode') !== 'STACK') return null;
  if (node.kind !== 'canvas' && node.kind !== 'stack') return null;
  const direction: PrototypeStackDirection = nodeProp(node, 'direction') === 'HORIZONTAL' ? 'HORIZONTAL' : 'VERTICAL';
  const mainValue = nodeProp(node, 'mainAlign');
  const mainAlign: PrototypeMainAlign = mainValue === 'CENTER' || mainValue === 'END' || mainValue === 'SPACE_BETWEEN'
    ? mainValue
    : 'START';
  const crossValue = nodeProp(node, 'crossAlign');
  const crossAlign: PrototypeCrossAlign = crossValue === 'CENTER' || crossValue === 'END' || crossValue === 'STRETCH'
    ? crossValue
    : 'START';
  return { direction, mainAlign, crossAlign, gapMm: nonnegativeProp(node, 'gapMm') };
}

function parseGridTracks(rawValue: string | undefined, axisLabel: '列' | '行'): ParsedGridTrackList {
  const tokens = prototypeGridTrackTokens(rawValue);
  if (tokens.length === 0) return { tracks: [], problems: [`${axisLabel}轨道不能为空`] };
  const tracks: PrototypeGridTrackDefinition[] = [];
  const problems: string[] = [];
  if (tokens.length > PROTOTYPE_GRID_TRACK_LIMIT) {
    problems.push(`${axisLabel}轨道最多 ${PROTOTYPE_GRID_TRACK_LIMIT} 条`);
  }
  tokens.forEach((token, index) => {
    if (token === '') {
      problems.push(`${axisLabel}轨道 ${index + 1} 不能为空`);
      return;
    }
    const parsed = parsePrototypeGridTrackToken(token);
    if (!parsed) {
      problems.push(`${axisLabel}轨道“${token}”无效`);
      return;
    }
    tracks.push({
      kind: parsed.kind,
      token: parsed.canonicalToken,
      valueMm: parsed.valueMm,
      weight: parsed.weight,
    });
  });
  if (tracks.length > PROTOTYPE_GRID_TRACK_LIMIT) {
    tracks.length = PROTOTYPE_GRID_TRACK_LIMIT;
  }
  return { tracks, problems };
}

function gridSpec(node: DesignerNode): GridSpec | null {
  if (node.kind === 'canvas' && nodeProp(node, 'layoutMode') !== 'GRID') return null;
  if (node.kind !== 'canvas' && node.kind !== 'grid') return null;
  return {
    columns: parseGridTracks(nodeProp(node, 'columns') ?? '1*', '列'),
    rows: parseGridTracks(nodeProp(node, 'rows') ?? '1*', '行'),
    columnGapMm: nonnegativeProp(node, 'columnGapMm'),
    rowGapMm: nonnegativeProp(node, 'rowGapMm'),
  };
}

function nonnegativeIntegerProp(node: DesignerNode, label: string, fallback: number): number | null {
  const value = nodeProp(node, label);
  if (value === undefined || value.trim() === '') return fallback;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : null;
}

function positiveIntegerProp(node: DesignerNode, label: string, fallback: number): number | null {
  const value = nodeProp(node, label);
  if (value === undefined || value.trim() === '') return fallback;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

function gridAlign(node: DesignerNode, axis: 'horizontal' | 'vertical'): PrototypeGridAlign {
  const value = nodeProp(node, `placement.${axis}AlignSelf`);
  return value === 'CENTER' || value === 'END' ? value : 'START';
}

function contentBoxFor(node: DesignerNode, layoutBox: PrototypeLayoutRect): PrototypeLayoutRect {
  const stroke = node.kind === 'canvas' ? 0 : nonnegativeProp(node, 'stroke.widthMm');
  const innerWidth = positiveSubtract(positiveSubtract(layoutBox.w, stroke), stroke);
  const innerHeight = positiveSubtract(positiveSubtract(layoutBox.h, stroke), stroke);
  const innerX = layoutBox.x + stroke;
  const innerY = layoutBox.y + stroke;
  const top = nonnegativeProp(node, 'padding.topMm');
  const right = nonnegativeProp(node, 'padding.rightMm');
  const bottom = nonnegativeProp(node, 'padding.bottomMm');
  const left = nonnegativeProp(node, 'padding.leftMm');
  return {
    x: innerX + left,
    y: innerY + top,
    w: positiveSubtract(positiveSubtract(innerWidth, left), right),
    h: positiveSubtract(positiveSubtract(innerHeight, top), bottom),
  };
}

function equalBinary64Slots(total: number, count: number): number[] {
  if (count <= 0) return [];
  const unit = total / count;
  let remaining = total;
  return Array.from({ length: count }, (_, index) => {
    const slot = index + 1 === count ? remaining : unit;
    remaining -= slot;
    return slot;
  });
}

function descendantIds(node: DesignerNode): string[] {
  return [node.id, ...node.children.flatMap(descendantIds)];
}

function cloneBox(box: DraftBox): DraftBox {
  return { ...box };
}

function mainSize(measurement: StackChildMeasurement, direction: PrototypeStackDirection): number {
  return direction === 'HORIZONTAL' ? measurement.width : measurement.height;
}

function withMainSize(
  measurement: StackChildMeasurement,
  direction: PrototypeStackDirection,
  size: number,
): StackChildMeasurement {
  return direction === 'HORIZONTAL'
    ? { ...measurement, width: size }
    : { ...measurement, height: size };
}

function mainLeadingMargin(measurement: StackChildMeasurement, direction: PrototypeStackDirection): number {
  return direction === 'HORIZONTAL' ? measurement.margins.left : measurement.margins.top;
}

function mainTrailingMargin(measurement: StackChildMeasurement, direction: PrototypeStackDirection): number {
  return direction === 'HORIZONTAL' ? measurement.margins.right : measurement.margins.bottom;
}

function weightedFillAllocations(
  measurements: readonly StackChildMeasurement[],
  availableMainMm: number,
  usedWithoutFillMm: number,
): ReadonlyMap<number, number> {
  const fillIndices = measurements
    .map((measurement, index) => measurement.mainFill ? index : -1)
    .filter((index) => index >= 0);
  if (fillIndices.length === 0) return new Map();

  const remaining = positiveZero(availableMainMm - usedWithoutFillMm);
  if (fillIndices.length === 1) {
    const index = fillIndices[0]!;
    const measurement = measurements[index]!;
    return new Map([[index, clampFlexibleAxis(remaining, measurement.mainMinimum, measurement.mainMaximum)]]);
  }

  const allocations = fillIndices.map(() => 0);
  const frozen = fillIndices.map(() => false);
  let hasFrozenMinimum = false;

  // Every non-terminal round freezes at least one authored child, so N + 1
  // is a deterministic safety bound rather than a tolerance-based escape.
  for (let round = 0; round <= fillIndices.length; round += 1) {
    const activePositions = frozen
      .map((isFrozen, position) => isFrozen ? -1 : position)
      .filter((position) => position >= 0);
    if (activePositions.length === 0) break;

    const frozenSum = allocations.reduce((sum, allocation, position) => sum + (frozen[position] ? allocation : 0), 0);
    const remainingForRound = positiveZero(remaining - frozenSum);
    const activeWeight = activePositions.reduce(
      (sum, position) => sum + measurements[fillIndices[position]!]!.fillWeight,
      0,
    );
    let allocatedBeforeLast = 0;
    const provisional = activePositions.map((position, order) => {
      const share = order + 1 === activePositions.length
        ? remainingForRound - allocatedBeforeLast
        : remainingForRound * measurements[fillIndices[position]!]!.fillWeight / activeWeight;
      if (order + 1 !== activePositions.length) allocatedBeforeLast += share;
      return { position, share: positiveZero(share) };
    });
    const activeBounds: Array<{ position: number; bound: number; minimum: boolean }> = [];
    for (const { position, share } of provisional) {
      const measurement = measurements[fillIndices[position]!]!;
      if (measurement.mainMinimum !== undefined && share < measurement.mainMinimum) {
        activeBounds.push({ position, bound: measurement.mainMinimum, minimum: true });
      } else if (measurement.mainMaximum !== undefined && share > measurement.mainMaximum) {
        activeBounds.push({ position, bound: measurement.mainMaximum, minimum: false });
      }
    }
    if (activeBounds.length === 0) {
      for (const { position, share } of provisional) allocations[position] = share;
      break;
    }
    for (const activeBound of activeBounds) {
      frozen[activeBound.position] = true;
      allocations[activeBound.position] = positiveZero(activeBound.bound);
      hasFrozenMinimum ||= activeBound.minimum;
    }
    const newlyFrozenSum = allocations.reduce((sum, allocation, position) => sum + (frozen[position] ? allocation : 0), 0);
    if (newlyFrozenSum > remaining && hasFrozenMinimum) {
      allocations.forEach((_, position) => {
        if (!frozen[position]) allocations[position] = measurements[fillIndices[position]!]!.mainMinimum ?? 0;
      });
      break;
    }
  }

  return new Map(fillIndices.map((fillIndex, position) => [fillIndex, allocations[position]!]));
}

function gridSpanExtent(sizes: readonly number[], gapMm: number, start: number, span: number): number {
  let extent = 0;
  for (let offset = 0; offset < span; offset += 1) {
    extent += sizes[start + offset] ?? 0;
    if (offset + 1 < span) extent += gapMm;
  }
  return extent;
}

function solveGridAxis(
  definitions: readonly PrototypeGridTrackDefinition[],
  axis: 'column' | 'row',
  originMm: number,
  availableMm: number,
  gapMm: number,
  measurements: readonly GridChildMeasurement[],
  problems: string[],
): SolvedGridAxis {
  const sizes = definitions.map((track) => track.kind === 'FIXED' ? track.valueMm ?? 0 : 0);
  const autoIndices = definitions
    .map((track, index) => track.kind === 'AUTO' ? index : -1)
    .filter((index) => index >= 0);
  const fractionTracks = definitions
    .map((track, index) => track.kind === 'FRACTION' ? { index, weight: track.weight ?? 1 } : null)
    .filter((track): track is { index: number; weight: number } => track !== null);
  const axisLabel = axis === 'column' ? '列' : '行';
  let autoConstraintCount = 0;

  if (autoIndices.length > 0) {
    const constraints: Array<{
      start: number;
      span: number;
      order: number;
      autoIndices: number[];
      contribution: number;
    }> = [];
    for (const measurement of measurements) {
      const start = axis === 'column' ? measurement.column : measurement.row;
      const span = axis === 'column' ? measurement.columnSpan : measurement.rowSpan;
      const coveredAutoIndices = autoIndices.filter((index) => index >= start && index < start + span);
      if (coveredAutoIndices.length === 0) continue;
      const mode = axis === 'column' ? measurement.widthMode : measurement.heightMode;
      if (mode === 'FILL') {
        problems.push(`${measurement.child.id} 的${axisLabel} FILL 跨越 AUTO 轨道`);
        continue;
      }
      const size = axis === 'column' ? measurement.box.w : measurement.box.h;
      const leading = axis === 'column' ? measurement.margins.left : measurement.margins.top;
      const trailing = axis === 'column' ? measurement.margins.right : measurement.margins.bottom;
      constraints.push({
        start,
        span,
        order: measurement.order,
        autoIndices: coveredAutoIndices,
        contribution: positiveZero((size + leading) + trailing),
      });
    }
    constraints.sort((left, right) => left.span - right.span || left.start - right.start || left.order - right.order);
    autoConstraintCount = constraints.length;
    for (const constraint of constraints) {
      const occupied = gridSpanExtent(sizes, gapMm, constraint.start, constraint.span);
      const deficit = constraint.contribution - occupied;
      if (deficit <= 0) continue;
      const equalShare = deficit / constraint.autoIndices.length;
      let allocatedBeforeLast = 0;
      constraint.autoIndices.forEach((index, position) => {
        const share = position + 1 === constraint.autoIndices.length
          ? deficit - allocatedBeforeLast
          : equalShare;
        sizes[index] = positiveZero((sizes[index] ?? 0) + share);
        if (position + 1 !== constraint.autoIndices.length) allocatedBeforeLast += share;
      });
    }
  }

  if (fractionTracks.length > 0) {
    const usedWithoutFraction = gridSpanExtent(sizes, gapMm, 0, sizes.length);
    const remaining = positiveZero(availableMm - usedWithoutFraction);
    const totalWeight = fractionTracks.reduce((sum, track) => sum + track.weight, 0);
    let allocatedBeforeLast = 0;
    fractionTracks.forEach((track, position) => {
      const share = position + 1 === fractionTracks.length
        ? remaining - allocatedBeforeLast
        : remaining * track.weight / totalWeight;
      sizes[track.index] = positiveZero(share);
      if (position + 1 !== fractionTracks.length) allocatedBeforeLast += share;
    });
  }

  let cursor = originMm;
  const tracks = definitions.map((definition, index): GridTrackTrace => {
    const trace = {
      index,
      kind: definition.kind,
      token: definition.token,
      originMm: cursor,
      sizeMm: sizes[index] ?? 0,
      valueMm: definition.kind === 'FIXED' ? definition.valueMm ?? 0 : null,
      weight: definition.kind === 'FRACTION' ? definition.weight ?? 1 : null,
    };
    cursor += trace.sizeMm;
    if (index + 1 < definitions.length) cursor += gapMm;
    return trace;
  });
  const occupiedMm = gridSpanExtent(sizes, gapMm, 0, sizes.length);
  return {
    tracks,
    gapMm,
    occupiedMm,
    freeMm: positiveZero(availableMm - occupiedMm),
    overflowMm: positiveZero(occupiedMm - availableMm),
    autoConstraintCount,
  };
}

function gridCell(axis: SolvedGridAxis, start: number, span: number): { origin: number; size: number } {
  return {
    origin: axis.tracks[start]!.originMm,
    size: gridSpanExtent(axis.tracks.map((track) => track.sizeMm), axis.gapMm, start, span),
  };
}

function alignedGridAxisPosition(
  cellOrigin: number,
  cellSize: number,
  leadingMargin: number,
  trailingMargin: number,
  childSize: number,
  alignment: PrototypeGridAlign,
): number {
  const extra = ((cellSize - leadingMargin) - trailingMargin) - childSize;
  const offset = alignment === 'CENTER' ? extra / 2 : alignment === 'END' ? extra : 0;
  return cellOrigin + leadingMargin + offset;
}

/**
 * Projects definite absolute, Stack and Grid children in authored tree order.
 *
 * Stack supports direction, padding/stroke, signed margins, fixed/distributed
 * gaps, alignment and bounded weighted FILL. Grid solves explicit tracks in
 * FIXED -> AUTO -> FRACTION order, then applies cell spans, signed margins,
 * alignment and bounded FILL. HUG remains outside this browser-only closure
 * because it requires intrinsic text/image/container measurement.
 */
export function projectPrototypeLayout(
  tree: DesignerNode,
  authoredBoxes: readonly DraftBox[],
  options: PrototypeLayoutOptions = {},
): PrototypeLayoutProjection {
  const authored = new Map(authoredBoxes.map((box) => [box.nodeId, cloneBox(box)]));
  const projected = new Map(authoredBoxes.map((box) => [box.nodeId, cloneBox(box)]));
  const excludedNodeIds = options.excludedNodeIds ?? new Set<string>();
  const absoluteTraces = new Map<string, AbsoluteContainerLayoutTrace>();
  const stackTraces = new Map<string, StackLayoutTrace>();
  const gridTraces = new Map<string, GridLayoutTrace>();

  const translateSubtree = (node: DesignerNode, dx: number, dy: number) => {
    if (dx === 0 && dy === 0) return;
    for (const nodeId of descendantIds(node)) {
      const current = projected.get(nodeId);
      if (current) projected.set(nodeId, { ...current, x: current.x + dx, y: current.y + dy });
    }
  };

  const groupSourceBounds = (node: DesignerNode): PrototypeLayoutRect | null => {
    const children = node.children.filter((child) => !excludedNodeIds.has(child.id)).flatMap((child) => {
      const local = authored.get(child.id);
      const measured = projected.get(child.id);
      return local && measured ? [{ x: local.x, y: local.y, w: measured.w, h: measured.h }] : [];
    });
    if (children.length === 0) return null;
    const left = Math.min(...children.map((box) => box.x));
    const top = Math.min(...children.map((box) => box.y));
    const right = Math.max(...children.map((box) => box.x + box.w));
    const bottom = Math.max(...children.map((box) => box.y + box.h));
    return { x: left, y: top, w: positiveZero(right - left), h: positiveZero(bottom - top) };
  };

  // Group supports HUG only. Resolve descendant groups bottom-up before a
  // Stack/Grid parent measures them, while keeping authored local x/y intact.
  const primeGroupHugBoxes = (node: DesignerNode) => {
    node.children.forEach(primeGroupHugBoxes);
    if (node.kind !== 'group') return;
    const current = projected.get(node.id);
    if (!current) return;
    const bounds = groupSourceBounds(node);
    projected.set(node.id, { ...current, w: bounds?.w ?? 0, h: bounds?.h ?? 0 });
  };
  primeGroupHugBoxes(tree);

  const arrangeAbsoluteChildren = (node: DesignerNode, layoutBox: PrototypeLayoutRect) => {
    if (node.kind !== 'group' && node.kind !== 'frame' && node.kind !== 'conditional') return;
    const contentBox = node.kind === 'frame'
      ? contentBoxFor(node, layoutBox)
      : node.kind === 'conditional'
        ? { ...layoutBox }
        : null;
    const sourceBounds = node.kind === 'group' ? groupSourceBounds(node) : null;
    const originX = node.kind === 'group'
      ? layoutBox.x - (sourceBounds?.x ?? 0)
      : contentBox!.x;
    const originY = node.kind === 'group'
      ? layoutBox.y - (sourceBounds?.y ?? 0)
      : contentBox!.y;
    const missingChildIds: string[] = [];
    const placements: AbsolutePlacementTrace[] = [];
    node.children.filter((child) => !excludedNodeIds.has(child.id)).forEach((child, index) => {
      const local = authored.get(child.id);
      const current = projected.get(child.id);
      if (!local || !current) {
        missingChildIds.push(child.id);
        return;
      }
      const desired = { x: originX + local.x, y: originY + local.y, w: current.w, h: current.h };
      translateSubtree(child, desired.x - current.x, desired.y - current.y);
      projected.set(child.id, { ...projected.get(child.id)!, ...desired });
      placements.push({
        nodeId: child.id,
        order: index + 1,
        localBox: { x: local.x, y: local.y, w: current.w, h: current.h },
        box: desired,
      });
    });
    absoluteTraces.set(node.id, {
      containerId: node.id,
      containerName: node.name,
      kind: node.kind,
      layoutBox: { ...layoutBox },
      contentBox,
      sourceBounds,
      missingChildIds,
      placements,
    });
  };

  const arrangeStack = (node: DesignerNode, layoutBox: PrototypeLayoutRect, spec: StackSpec) => {
    const contentBox = contentBoxFor(node, layoutBox);
    const availableMainMm = spec.direction === 'HORIZONTAL' ? contentBox.w : contentBox.h;
    const availableCrossMm = spec.direction === 'HORIZONTAL' ? contentBox.h : contentBox.w;
    const participatingChildren = node.children.filter((child) => !excludedNodeIds.has(child.id));
    const directChildrenWithBoxes = participatingChildren
      .map((child) => ({ child, box: projected.get(child.id) }))
      .filter((entry): entry is { child: DesignerNode; box: DraftBox } => Boolean(entry.box));
    const missingChildIds = participatingChildren.filter((child) => !projected.has(child.id)).map((child) => child.id);
    const measurements = directChildrenWithBoxes.map(({ child, box }): StackChildMeasurement => {
      const widthMode = sizeMode(child, 'width');
      const heightMode = sizeMode(child, 'height');
      const childAlignSelf = alignSelf(child);
      const authoredCrossFill = spec.direction === 'HORIZONTAL' ? heightMode === 'FILL' : widthMode === 'FILL';
      const inheritedStretch = spec.crossAlign === 'STRETCH' && childAlignSelf === 'INHERIT';
      const crossFill = authoredCrossFill || inheritedStretch;
      const parentAlignment: PrototypeResolvedCrossAlign = spec.crossAlign === 'CENTER' || spec.crossAlign === 'END'
        ? spec.crossAlign
        : 'START';
      const resolvedCrossAlign = authoredCrossFill
        ? parentAlignment
        : childAlignSelf === 'INHERIT'
          ? parentAlignment
          : childAlignSelf;
      const margins = {
        top: finiteProp(child, 'placement.marginTopMm', 0),
        right: finiteProp(child, 'placement.marginRightMm', 0),
        bottom: finiteProp(child, 'placement.marginBottomMm', 0),
        left: finiteProp(child, 'placement.marginLeftMm', 0),
      };
      const crossLeading = spec.direction === 'HORIZONTAL' ? margins.top : margins.left;
      const crossTrailing = spec.direction === 'HORIZONTAL' ? margins.bottom : margins.right;
      const crossOffer = positiveZero((availableCrossMm - crossLeading) - crossTrailing);
      const crossAxis = spec.direction === 'HORIZONTAL' ? 'Height' : 'Width';
      const resolvedCrossSize = crossFill
        ? clampFlexibleAxis(crossOffer, axisMinimum(child, crossAxis), axisMaximum(child, crossAxis))
        : spec.direction === 'HORIZONTAL' ? box.h : box.w;
      const mainAxis = spec.direction === 'HORIZONTAL' ? 'Width' : 'Height';
      return {
        child,
        box,
        widthMode,
        heightMode,
        width: spec.direction === 'HORIZONTAL' ? box.w : resolvedCrossSize,
        height: spec.direction === 'HORIZONTAL' ? resolvedCrossSize : box.h,
        margins,
        alignSelf: childAlignSelf,
        resolvedCrossAlign,
        mainFill: spec.direction === 'HORIZONTAL' ? widthMode === 'FILL' : heightMode === 'FILL',
        crossFill,
        fillWeight: Math.max(Number.EPSILON, finiteProp(child, 'placement.fillWeight', 1)),
        mainMinimum: axisMinimum(child, mainAxis),
        mainMaximum: axisMaximum(child, mainAxis),
      };
    });
    const fixedGapTotal = spec.gapMm * Math.max(0, measurements.length - 1);
    const usedWithoutFillMm = measurements.reduce((total, measurement) => (
      total
      + mainLeadingMargin(measurement, spec.direction)
      + (measurement.mainFill ? 0 : mainSize(measurement, spec.direction))
      + mainTrailingMargin(measurement, spec.direction)
    ), fixedGapTotal);
    const fillAllocations = weightedFillAllocations(measurements, availableMainMm, usedWithoutFillMm);
    const resolvedMeasurements = measurements.map((measurement, index) => {
      const allocation = fillAllocations.get(index);
      return allocation === undefined ? measurement : withMainSize(measurement, spec.direction, allocation);
    });
    const occupiedRaw = resolvedMeasurements.reduce(
      (total, measurement) => total
        + mainLeadingMargin(measurement, spec.direction)
        + mainSize(measurement, spec.direction)
        + mainTrailingMargin(measurement, spec.direction),
      fixedGapTotal,
    );
    const occupiedMainMm = positiveZero(occupiedRaw);
    const remaining = availableMainMm - occupiedMainMm;
    const freeMainMm = positiveZero(remaining);
    const overflowMainMm = positiveZero(-remaining);
    const distributed = spec.mainAlign === 'SPACE_BETWEEN' && resolvedMeasurements.length > 1
      ? equalBinary64Slots(freeMainMm, resolvedMeasurements.length - 1)
      : Array.from({ length: Math.max(0, resolvedMeasurements.length - 1) }, () => 0);
    const leading = spec.mainAlign === 'CENTER'
      ? freeMainMm / 2
      : spec.mainAlign === 'END'
        ? freeMainMm
        : 0;
    const placements: StackPlacementTrace[] = [];
    let cursor = leading;

    resolvedMeasurements.forEach((measurement, index) => {
      const { child, box } = measurement;
      const childMainSize = mainSize(measurement, spec.direction);
      const effectiveCrossSize = spec.direction === 'HORIZONTAL' ? measurement.height : measurement.width;
      const crossLeading = spec.direction === 'HORIZONTAL' ? measurement.margins.top : measurement.margins.left;
      const crossTrailing = spec.direction === 'HORIZONTAL' ? measurement.margins.bottom : measurement.margins.right;
      const crossInterval = (availableCrossMm - crossLeading) - crossTrailing;
      const crossExtra = crossInterval - effectiveCrossSize;
      const crossOffset = measurement.resolvedCrossAlign === 'CENTER'
        ? crossExtra / 2
        : measurement.resolvedCrossAlign === 'END'
          ? crossExtra
          : 0;
      cursor += mainLeadingMargin(measurement, spec.direction);
      const desired: PrototypeLayoutRect = spec.direction === 'HORIZONTAL'
        ? { x: contentBox.x + cursor, y: contentBox.y + crossLeading + crossOffset, w: childMainSize, h: effectiveCrossSize }
        : { x: contentBox.x + crossLeading + crossOffset, y: contentBox.y + cursor, w: effectiveCrossSize, h: childMainSize };
      translateSubtree(child, desired.x - box.x, desired.y - box.y);
      projected.set(child.id, { ...projected.get(child.id)!, ...desired });
      placements.push({
        nodeId: child.id,
        order: index + 1,
        box: desired,
        widthMode: measurement.widthMode,
        heightMode: measurement.heightMode,
        mainFill: measurement.mainFill,
        crossFill: measurement.crossFill,
        fillWeight: measurement.mainFill ? measurement.fillWeight : null,
        mainAllocationMm: measurement.mainFill ? childMainSize : null,
        margins: measurement.margins,
        alignSelf: measurement.alignSelf,
        resolvedCrossAlign: measurement.resolvedCrossAlign,
      });
      cursor += childMainSize + mainTrailingMargin(measurement, spec.direction);
      if (index + 1 < resolvedMeasurements.length) cursor += spec.gapMm + distributed[index]!;
    });

    stackTraces.set(node.id, {
      containerId: node.id,
      containerName: node.name,
      direction: spec.direction,
      mainAlign: spec.mainAlign,
      crossAlign: spec.crossAlign,
      contentBox,
      gapMm: spec.gapMm,
      effectiveBetweenGapsMm: distributed.map((slot) => spec.gapMm + slot),
      availableMainMm,
      usedWithoutFillMm,
      fillAvailableMainMm: positiveZero(availableMainMm - usedWithoutFillMm),
      fillCount: fillAllocations.size,
      occupiedMainMm,
      freeMainMm,
      overflowMainMm,
      missingChildIds,
      placements,
    });
  };

  const arrangeGrid = (node: DesignerNode, layoutBox: PrototypeLayoutRect, spec: GridSpec) => {
    const contentBox = contentBoxFor(node, layoutBox);
    const participatingChildren = node.children.filter((child) => !excludedNodeIds.has(child.id));
    const missingChildIds = participatingChildren.filter((child) => !projected.has(child.id)).map((child) => child.id);
    const directChildrenWithBoxes = participatingChildren
      .map((child) => ({ child, box: projected.get(child.id) }))
      .filter((entry): entry is { child: DesignerNode; box: DraftBox } => Boolean(entry.box));
    const problems = [...spec.columns.problems, ...spec.rows.problems];
    const measurements: GridChildMeasurement[] = [];

    directChildrenWithBoxes.forEach(({ child, box }, index) => {
      const row = nonnegativeIntegerProp(child, 'placement.row', 0);
      const column = nonnegativeIntegerProp(child, 'placement.column', 0);
      const rowSpan = positiveIntegerProp(child, 'placement.rowSpan', 1);
      const columnSpan = positiveIntegerProp(child, 'placement.columnSpan', 1);
      if (row === null) problems.push(`${child.id} 的行索引必须是非负整数`);
      if (column === null) problems.push(`${child.id} 的列索引必须是非负整数`);
      if (rowSpan === null) problems.push(`${child.id} 的行跨度必须是正整数`);
      if (columnSpan === null) problems.push(`${child.id} 的列跨度必须是正整数`);
      const resolvedRow = row ?? 0;
      const resolvedColumn = column ?? 0;
      const resolvedRowSpan = rowSpan ?? 1;
      const resolvedColumnSpan = columnSpan ?? 1;
      if (spec.rows.problems.length === 0 && spec.rows.tracks.length > 0 && resolvedRow + resolvedRowSpan > spec.rows.tracks.length) {
        problems.push(`${child.id} 的行范围超出显式轨道`);
      }
      if (spec.columns.problems.length === 0 && spec.columns.tracks.length > 0 && resolvedColumn + resolvedColumnSpan > spec.columns.tracks.length) {
        problems.push(`${child.id} 的列范围超出显式轨道`);
      }
      measurements.push({
        child,
        box,
        order: index,
        row: resolvedRow,
        column: resolvedColumn,
        rowSpan: resolvedRowSpan,
        columnSpan: resolvedColumnSpan,
        widthMode: sizeMode(child, 'width'),
        heightMode: sizeMode(child, 'height'),
        margins: {
          top: finiteProp(child, 'placement.marginTopMm', 0),
          right: finiteProp(child, 'placement.marginRightMm', 0),
          bottom: finiteProp(child, 'placement.marginBottomMm', 0),
          left: finiteProp(child, 'placement.marginLeftMm', 0),
        },
        horizontalAlignSelf: gridAlign(child, 'horizontal'),
        verticalAlignSelf: gridAlign(child, 'vertical'),
      });
    });

    const columns = solveGridAxis(
      spec.columns.tracks,
      'column',
      contentBox.x,
      contentBox.w,
      spec.columnGapMm,
      measurements,
      problems,
    );
    // The frozen profile is columns-first. This fixed/FILL prototype subset
    // has no intrinsic row measurement, but preserves the same solve order.
    const rows = solveGridAxis(
      spec.rows.tracks,
      'row',
      contentBox.y,
      contentBox.h,
      spec.rowGapMm,
      measurements,
      problems,
    );
    const placements: GridPlacementTrace[] = [];

    if (problems.length === 0) {
      measurements.forEach((measurement, index) => {
        const columnCell = gridCell(columns, measurement.column, measurement.columnSpan);
        const rowCell = gridCell(rows, measurement.row, measurement.rowSpan);
        const width = measurement.widthMode === 'FILL'
          ? clampFlexibleAxis(
              positiveZero((columnCell.size - measurement.margins.left) - measurement.margins.right),
              axisMinimum(measurement.child, 'Width'),
              axisMaximum(measurement.child, 'Width'),
            )
          : measurement.box.w;
        const height = measurement.heightMode === 'FILL'
          ? clampFlexibleAxis(
              positiveZero((rowCell.size - measurement.margins.top) - measurement.margins.bottom),
              axisMinimum(measurement.child, 'Height'),
              axisMaximum(measurement.child, 'Height'),
            )
          : measurement.box.h;
        const x = measurement.widthMode === 'FILL'
          ? columnCell.origin + measurement.margins.left
          : alignedGridAxisPosition(
              columnCell.origin,
              columnCell.size,
              measurement.margins.left,
              measurement.margins.right,
              width,
              measurement.horizontalAlignSelf,
            );
        const y = measurement.heightMode === 'FILL'
          ? rowCell.origin + measurement.margins.top
          : alignedGridAxisPosition(
              rowCell.origin,
              rowCell.size,
              measurement.margins.top,
              measurement.margins.bottom,
              height,
              measurement.verticalAlignSelf,
            );
        const desired = { x, y, w: width, h: height };
        translateSubtree(measurement.child, desired.x - measurement.box.x, desired.y - measurement.box.y);
        projected.set(measurement.child.id, { ...projected.get(measurement.child.id)!, ...desired });
        placements.push({
          nodeId: measurement.child.id,
          order: index + 1,
          row: measurement.row,
          column: measurement.column,
          rowSpan: measurement.rowSpan,
          columnSpan: measurement.columnSpan,
          cell: { x: columnCell.origin, y: rowCell.origin, w: columnCell.size, h: rowCell.size },
          box: desired,
          widthMode: measurement.widthMode,
          heightMode: measurement.heightMode,
          margins: measurement.margins,
          horizontalAlignSelf: measurement.horizontalAlignSelf,
          verticalAlignSelf: measurement.verticalAlignSelf,
        });
      });
    }

    gridTraces.set(node.id, {
      containerId: node.id,
      containerName: node.name,
      contentBox,
      columnGapMm: spec.columnGapMm,
      rowGapMm: spec.rowGapMm,
      columns: columns.tracks,
      rows: rows.tracks,
      occupiedWidthMm: columns.occupiedMm,
      occupiedHeightMm: rows.occupiedMm,
      freeWidthMm: columns.freeMm,
      freeHeightMm: rows.freeMm,
      overflowWidthMm: columns.overflowMm,
      overflowHeightMm: rows.overflowMm,
      autoConstraintCount: columns.autoConstraintCount + rows.autoConstraintCount,
      missingChildIds,
      problems,
      placements,
    });
  };

  const visit = (node: DesignerNode) => {
    if (node.kind !== 'canvas' && excludedNodeIds.has(node.id)) return;
    const layoutBox = node.kind === 'canvas'
      ? { x: 0, y: 0, w: canvasProjection(node).widthMm, h: canvasProjection(node).heightMm }
      : projected.get(node.id);
    const stack = stackSpec(node);
    const grid = gridSpec(node);
    if (layoutBox) arrangeAbsoluteChildren(node, layoutBox);
    if (layoutBox && stack) arrangeStack(node, layoutBox, stack);
    if (layoutBox && grid) arrangeGrid(node, layoutBox, grid);
    node.children.forEach(visit);
  };

  visit(tree);
  const boxes = authoredBoxes.map((box) => projected.get(box.nodeId) ?? cloneBox(box));
  return {
    boxes,
    boxByNodeId: projected,
    absoluteByContainerId: absoluteTraces,
    stackByContainerId: stackTraces,
    gridByContainerId: gridTraces,
  };
}
