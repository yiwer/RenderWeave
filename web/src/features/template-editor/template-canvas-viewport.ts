export const CANVAS_ZOOM_MIN = 0.25;
export const CANVAS_ZOOM_MAX = 4;

const WHEEL_SENSITIVITY = 0.0016;
const CTRL_WHEEL_SENSITIVITY = 0.008;

export interface CanvasPoint {
  readonly x: number;
  readonly y: number;
}

export interface CanvasSize {
  readonly width: number;
  readonly height: number;
}

export interface CanvasViewportTransform {
  readonly scale: number;
  readonly x: number;
  readonly y: number;
}

export function fitCanvasViewport(
  viewport: CanvasSize,
  artboard: CanvasSize,
  padding: number,
): CanvasViewportTransform {
  if (!isPositiveFinite(viewport.width) || !isPositiveFinite(viewport.height)
    || !isPositiveFinite(artboard.width) || !isPositiveFinite(artboard.height)) {
    return { scale: 1, x: 0, y: 0 };
  }
  const inset = Number.isFinite(padding) ? Math.max(0, padding) : 0;
  const availableWidth = Math.max(1, viewport.width - inset * 2);
  const availableHeight = Math.max(1, viewport.height - inset * 2);
  const scale = clampCanvasScale(Math.min(
    availableWidth / artboard.width,
    availableHeight / artboard.height,
  ));
  return {
    scale,
    x: (viewport.width - artboard.width * scale) / 2,
    y: (viewport.height - artboard.height * scale) / 2,
  };
}

export function zoomCanvasViewportAt(
  current: CanvasViewportTransform,
  requestedScale: number,
  anchor: CanvasPoint,
): CanvasViewportTransform {
  const currentScale = clampCanvasScale(current.scale);
  const nextScale = clampCanvasScale(requestedScale);
  const anchorX = finiteOr(anchor.x, 0);
  const anchorY = finiteOr(anchor.y, 0);
  const currentX = finiteOr(current.x, 0);
  const currentY = finiteOr(current.y, 0);
  const worldX = (anchorX - currentX) / currentScale;
  const worldY = (anchorY - currentY) / currentScale;
  return {
    scale: nextScale,
    x: anchorX - worldX * nextScale,
    y: anchorY - worldY * nextScale,
  };
}

export function wheelZoomCanvasViewport(
  current: CanvasViewportTransform,
  deltaY: number,
  anchor: CanvasPoint,
  ctrlKey: boolean,
): CanvasViewportTransform {
  const sensitivity = ctrlKey ? CTRL_WHEEL_SENSITIVITY : WHEEL_SENSITIVITY;
  const delta = Number.isFinite(deltaY) ? deltaY : 0;
  const nextScale = current.scale * Math.exp(-delta * sensitivity);
  return zoomCanvasViewportAt(current, nextScale, anchor);
}

export function panCanvasViewport(
  current: CanvasViewportTransform,
  delta: CanvasPoint,
): CanvasViewportTransform {
  return {
    scale: clampCanvasScale(current.scale),
    x: finiteOr(current.x, 0) + finiteOr(delta.x, 0),
    y: finiteOr(current.y, 0) + finiteOr(delta.y, 0),
  };
}

export function clampCanvasScale(scale: number): number {
  if (Number.isNaN(scale)) return 1;
  return Math.min(CANVAS_ZOOM_MAX, Math.max(CANVAS_ZOOM_MIN, scale));
}

function finiteOr(value: number, fallback: number): number {
  return Number.isFinite(value) ? value : fallback;
}

function isPositiveFinite(value: number): boolean {
  return Number.isFinite(value) && value > 0;
}
