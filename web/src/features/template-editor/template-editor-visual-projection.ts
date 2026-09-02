import type { DesignNode } from '../../api/generated/types.gen';

export const DEFAULT_TEMPLATE_CANVAS_PX_PER_MM = 4;

export const TEMPLATE_EDITOR_VISUAL_NODE_KINDS = [
  'text',
  'image',
  'rect',
  'ellipse',
  'line',
  'polygon',
  'polyline',
  'path',
  'qrCode',
  'barcode',
] as const;

export type TemplateEditorVisualNodeKind =
  (typeof TEMPLATE_EDITOR_VISUAL_NODE_KINDS)[number];

export type TemplateEditorVisualDesignNode = Extract<
  DesignNode,
  { kind: TemplateEditorVisualNodeKind }
>;

export interface TemplateGeometryPoint {
  readonly xMm: number;
  readonly yMm: number;
}

export interface TemplateGeometryTargetBox {
  readonly widthMm: number;
  readonly heightMm: number;
  readonly strokeWidthMm?: number;
  /** Conservative painted extent reserved on every edge by the local projector. */
  readonly paintInsetMm?: number;
}

const VISUAL_KIND_SET = new Set<string>(TEMPLATE_EDITOR_VISUAL_NODE_KINDS);

export function isTemplateEditorVisualNodeKind(value: unknown): value is TemplateEditorVisualNodeKind {
  return typeof value === 'string' && VISUAL_KIND_SET.has(value);
}

export function isTemplateEditorVisualNode(
  value: unknown,
): value is TemplateEditorVisualDesignNode | Readonly<Record<string, unknown>> {
  const node = recordOrNull(value);
  return isTemplateEditorVisualNodeKind(node?.kind);
}

/** Browser-local canvas pixels for a physical point size. The world transform supplies zoom. */
export function templatePointsToCanvasPixels(
  points: number,
  pixelsPerMm = DEFAULT_TEMPLATE_CANVAS_PX_PER_MM,
): number {
  if (!Number.isFinite(points) || !Number.isFinite(pixelsPerMm)) return 0;
  return Math.max(0, points) * (25.4 / 72) * Math.max(0, pixelsPerMm);
}

/** Closed projection of formal commands[]. Unknown or malformed commands fail to an empty path. */
export function designPathCommandsToSvgPath(commands: unknown): string {
  const parsed = parseDesignPathCommands(commands);
  return parsed ? serializePathCommands(parsed, identityPoint) : '';
}

/**
 * Maps raw local-mm points to the current authored box. A degenerate source axis is
 * centered while its non-degenerate peer still fills the available axis.
 */
export function projectDesignGeometryPointsToAuthoredBox(
  value: unknown,
  target: TemplateGeometryTargetBox,
): readonly TemplateGeometryPoint[] | null {
  const points = parseGeometryPoints(value);
  if (!points) return null;
  const projector = createGeometryProjector(boundsFromPoints(points), target);
  return points.map(projector);
}

/**
 * Projects commands[] without inventing pathData. Quadratic and cubic extrema are
 * included in the source bounds, so the painted curve—not just its endpoints—fills
 * the current authored box. Stroke remains in local millimetres.
 */
export function projectDesignPathCommandsToAuthoredBox(
  commands: unknown,
  target: TemplateGeometryTargetBox,
): string {
  const parsed = parseDesignPathCommands(commands);
  if (!parsed) return '';
  const bounds = pathGeometryBounds(parsed);
  if (!bounds) return '';
  return serializePathCommands(parsed, createGeometryProjector(bounds, target));
}

type ParsedPathCommand =
  | { readonly type: 'MOVE_TO' | 'LINE_TO'; readonly point: TemplateGeometryPoint }
  | {
      readonly type: 'QUAD_TO';
      readonly control: TemplateGeometryPoint;
      readonly point: TemplateGeometryPoint;
    }
  | {
      readonly type: 'CUBIC_TO';
      readonly control1: TemplateGeometryPoint;
      readonly control2: TemplateGeometryPoint;
      readonly point: TemplateGeometryPoint;
    }
  | { readonly type: 'CLOSE' };

function parseDesignPathCommands(commands: unknown): ParsedPathCommand[] | null {
  if (!Array.isArray(commands)) return null;
  const parsed: ParsedPathCommand[] = [];
  for (const value of commands) {
    const command = recordOrNull(value);
    if (!command || typeof command.type !== 'string') return null;
    switch (command.type) {
      case 'MOVE_TO': {
        const values = finiteMembers(command, ['xMm', 'yMm']);
        if (!values) return null;
        parsed.push({ type: 'MOVE_TO', point: { xMm: values[0]!, yMm: values[1]! } });
        break;
      }
      case 'LINE_TO': {
        const values = finiteMembers(command, ['xMm', 'yMm']);
        if (!values) return null;
        parsed.push({ type: 'LINE_TO', point: { xMm: values[0]!, yMm: values[1]! } });
        break;
      }
      case 'QUAD_TO': {
        const values = finiteMembers(command, ['cxMm', 'cyMm', 'xMm', 'yMm']);
        if (!values) return null;
        parsed.push({
          type: 'QUAD_TO',
          control: { xMm: values[0]!, yMm: values[1]! },
          point: { xMm: values[2]!, yMm: values[3]! },
        });
        break;
      }
      case 'CUBIC_TO': {
        const values = finiteMembers(command, [
          'c1xMm', 'c1yMm', 'c2xMm', 'c2yMm', 'xMm', 'yMm',
        ]);
        if (!values) return null;
        parsed.push({
          type: 'CUBIC_TO',
          control1: { xMm: values[0]!, yMm: values[1]! },
          control2: { xMm: values[2]!, yMm: values[3]! },
          point: { xMm: values[4]!, yMm: values[5]! },
        });
        break;
      }
      case 'CLOSE':
        parsed.push({ type: 'CLOSE' });
        break;
      default:
        return null;
    }
  }
  return parsed;
}

function serializePathCommands(
  commands: readonly ParsedPathCommand[],
  project: (point: TemplateGeometryPoint) => TemplateGeometryPoint,
): string {
  const segments: string[] = [];
  for (const command of commands) {
    switch (command.type) {
      case 'MOVE_TO': {
        const point = project(command.point);
        segments.push(`M ${formatNumber(point.xMm)} ${formatNumber(point.yMm)}`);
        break;
      }
      case 'LINE_TO': {
        const point = project(command.point);
        segments.push(`L ${formatNumber(point.xMm)} ${formatNumber(point.yMm)}`);
        break;
      }
      case 'QUAD_TO': {
        const control = project(command.control);
        const point = project(command.point);
        segments.push(
          `Q ${formatNumber(control.xMm)} ${formatNumber(control.yMm)} ${formatNumber(point.xMm)} ${formatNumber(point.yMm)}`,
        );
        break;
      }
      case 'CUBIC_TO': {
        const control1 = project(command.control1);
        const control2 = project(command.control2);
        const point = project(command.point);
        segments.push([
          'C',
          formatNumber(control1.xMm), formatNumber(control1.yMm),
          formatNumber(control2.xMm), formatNumber(control2.yMm),
          formatNumber(point.xMm), formatNumber(point.yMm),
        ].join(' '));
        break;
      }
      case 'CLOSE':
        segments.push('Z');
        break;
    }
  }
  return segments.join(' ');
}

interface GeometryBounds {
  readonly minX: number;
  readonly minY: number;
  readonly maxX: number;
  readonly maxY: number;
}

function parseGeometryPoints(value: unknown): TemplateGeometryPoint[] | null {
  if (!Array.isArray(value)) return null;
  const points: TemplateGeometryPoint[] = [];
  for (const entry of value) {
    const point = recordOrNull(entry);
    const xMm = finiteNumber(point?.xMm);
    const yMm = finiteNumber(point?.yMm);
    if (xMm === null || yMm === null) return null;
    points.push({ xMm, yMm });
  }
  return points;
}

function boundsFromPoints(points: readonly TemplateGeometryPoint[]): GeometryBounds | null {
  if (points.length === 0) return null;
  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;
  for (const point of points) {
    minX = Math.min(minX, point.xMm);
    minY = Math.min(minY, point.yMm);
    maxX = Math.max(maxX, point.xMm);
    maxY = Math.max(maxY, point.yMm);
  }
  return { minX, minY, maxX, maxY };
}

function pathGeometryBounds(commands: readonly ParsedPathCommand[]): GeometryBounds | null {
  const collector = new BoundsCollector();
  let current: TemplateGeometryPoint | null = null;
  let subpathStart: TemplateGeometryPoint | null = null;
  for (const command of commands) {
    switch (command.type) {
      case 'MOVE_TO':
        current = command.point;
        subpathStart = command.point;
        break;
      case 'LINE_TO':
        if (!current) return null;
        collector.include(current);
        collector.include(command.point);
        current = command.point;
        break;
      case 'QUAD_TO':
        if (!current) return null;
        includeQuadraticBounds(collector, current, command.control, command.point);
        current = command.point;
        break;
      case 'CUBIC_TO':
        if (!current) return null;
        includeCubicBounds(
          collector,
          current,
          command.control1,
          command.control2,
          command.point,
        );
        current = command.point;
        break;
      case 'CLOSE':
        if (!current || !subpathStart) return null;
        if (!sameGeometryPoint(current, subpathStart)) {
          collector.include(current);
          collector.include(subpathStart);
        }
        current = subpathStart;
        break;
    }
  }
  return collector.value();
}

class BoundsCollector {
  minX = Number.POSITIVE_INFINITY;
  minY = Number.POSITIVE_INFINITY;
  maxX = Number.NEGATIVE_INFINITY;
  maxY = Number.NEGATIVE_INFINITY;

  include(point: TemplateGeometryPoint) {
    this.includeX(point.xMm);
    this.includeY(point.yMm);
  }

  includeX(value: number) {
    this.minX = Math.min(this.minX, value);
    this.maxX = Math.max(this.maxX, value);
  }

  includeY(value: number) {
    this.minY = Math.min(this.minY, value);
    this.maxY = Math.max(this.maxY, value);
  }

  value(): GeometryBounds | null {
    return Number.isFinite(this.minX) && Number.isFinite(this.minY)
      && Number.isFinite(this.maxX) && Number.isFinite(this.maxY)
      ? { minX: this.minX, minY: this.minY, maxX: this.maxX, maxY: this.maxY }
      : null;
  }
}

function includeQuadraticBounds(
  collector: BoundsCollector,
  start: TemplateGeometryPoint,
  control: TemplateGeometryPoint,
  end: TemplateGeometryPoint,
) {
  collector.include(start);
  collector.include(end);
  for (const t of quadraticExtrema(start.xMm, control.xMm, end.xMm)) {
    collector.includeX(evaluateQuadratic(start.xMm, control.xMm, end.xMm, t));
  }
  for (const t of quadraticExtrema(start.yMm, control.yMm, end.yMm)) {
    collector.includeY(evaluateQuadratic(start.yMm, control.yMm, end.yMm, t));
  }
}

function includeCubicBounds(
  collector: BoundsCollector,
  start: TemplateGeometryPoint,
  control1: TemplateGeometryPoint,
  control2: TemplateGeometryPoint,
  end: TemplateGeometryPoint,
) {
  collector.include(start);
  collector.include(end);
  for (const t of cubicExtrema(start.xMm, control1.xMm, control2.xMm, end.xMm)) {
    collector.includeX(evaluateCubic(start.xMm, control1.xMm, control2.xMm, end.xMm, t));
  }
  for (const t of cubicExtrema(start.yMm, control1.yMm, control2.yMm, end.yMm)) {
    collector.includeY(evaluateCubic(start.yMm, control1.yMm, control2.yMm, end.yMm, t));
  }
}

function quadraticExtrema(start: number, control: number, end: number): number[] {
  const denominator = start - 2 * control + end;
  if (Math.abs(denominator) < Number.EPSILON) return [];
  const t = (start - control) / denominator;
  return t > 0 && t < 1 ? [t] : [];
}

function cubicExtrema(start: number, control1: number, control2: number, end: number): number[] {
  const a = -start + 3 * control1 - 3 * control2 + end;
  const b = 2 * (start - 2 * control1 + control2);
  const c = control1 - start;
  if (Math.abs(a) < Number.EPSILON) {
    if (Math.abs(b) < Number.EPSILON) return [];
    const t = -c / b;
    return t > 0 && t < 1 ? [t] : [];
  }
  const discriminant = b * b - 4 * a * c;
  if (discriminant < 0) return [];
  const root = Math.sqrt(discriminant);
  const values = [(-b + root) / (2 * a), (-b - root) / (2 * a)];
  return values.filter((value, index) => (
    value > 0 && value < 1 && (index === 0 || Math.abs(value - values[0]!) > 1e-12)
  ));
}

function evaluateQuadratic(start: number, control: number, end: number, t: number): number {
  const inverse = 1 - t;
  return inverse * inverse * start + 2 * inverse * t * control + t * t * end;
}

function evaluateCubic(
  start: number,
  control1: number,
  control2: number,
  end: number,
  t: number,
): number {
  const inverse = 1 - t;
  return inverse ** 3 * start
    + 3 * inverse ** 2 * t * control1
    + 3 * inverse * t ** 2 * control2
    + t ** 3 * end;
}

function createGeometryProjector(
  bounds: GeometryBounds | null,
  target: TemplateGeometryTargetBox,
): (point: TemplateGeometryPoint) => TemplateGeometryPoint {
  if (!bounds) return identityPoint;
  const widthMm = positiveDimension(target.widthMm);
  const heightMm = positiveDimension(target.heightMm);
  const requestedInset = finiteNumber(target.paintInsetMm)
    ?? Math.max(0, finiteNumber(target.strokeWidthMm) ?? 0) / 2;
  const insetX = Math.min(Math.max(0, requestedInset), widthMm / 2);
  const insetY = Math.min(Math.max(0, requestedInset), heightMm / 2);
  const projectX = createAxisProjector(bounds.minX, bounds.maxX, insetX, widthMm - insetX);
  const projectY = createAxisProjector(bounds.minY, bounds.maxY, insetY, heightMm - insetY);
  return (point) => ({ xMm: projectX(point.xMm), yMm: projectY(point.yMm) });
}

function createAxisProjector(
  sourceMin: number,
  sourceMax: number,
  targetMin: number,
  targetMax: number,
): (value: number) => number {
  const sourceSpan = sourceMax - sourceMin;
  if (Math.abs(sourceSpan) <= 1e-12) {
    const center = (targetMin + targetMax) / 2;
    return () => center;
  }
  const scale = (targetMax - targetMin) / sourceSpan;
  return (value) => targetMin + (value - sourceMin) * scale;
}

function identityPoint(point: TemplateGeometryPoint): TemplateGeometryPoint {
  return point;
}

function sameGeometryPoint(
  left: TemplateGeometryPoint,
  right: TemplateGeometryPoint,
): boolean {
  return left.xMm === right.xMm && left.yMm === right.yMm;
}

function positiveDimension(value: number): number {
  return Number.isFinite(value) && value > 0 ? value : 1;
}

function finiteMembers(
  record: Readonly<Record<string, unknown>>,
  members: readonly string[],
): number[] | null {
  const values: number[] = [];
  for (const member of members) {
    const value = finiteNumber(record[member]);
    if (value === null) return null;
    values.push(value);
  }
  return values;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function recordOrNull(value: unknown): Readonly<Record<string, unknown>> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Readonly<Record<string, unknown>>
    : null;
}

function formatNumber(value: number): string {
  return Number.isInteger(value)
    ? String(value)
    : value.toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
}
