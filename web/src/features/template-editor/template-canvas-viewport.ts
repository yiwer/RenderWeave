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

export interface CanvasRect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

export type CanvasResizeHandle = 'nw' | 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w';

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

export function resizeCanvasRect(
  current: CanvasRect,
  handle: CanvasResizeHandle,
  delta: CanvasPoint,
  minimumSize: number,
): CanvasRect {
  const minimum = Math.max(0, finiteOr(minimumSize, 0));
  const startX = finiteOr(current.x, 0);
  const startY = finiteOr(current.y, 0);
  const startWidth = Math.max(minimum, finiteOr(current.width, minimum));
  const startHeight = Math.max(minimum, finiteOr(current.height, minimum));
  const right = startX + startWidth;
  const bottom = startY + startHeight;
  const deltaX = finiteOr(delta.x, 0);
  const deltaY = finiteOr(delta.y, 0);
  const movesLeft = handle.includes('w');
  const movesRight = handle.includes('e');
  const movesTop = handle.includes('n');
  const movesBottom = handle.includes('s');
  const x = movesLeft ? Math.min(startX + deltaX, right - minimum) : startX;
  const y = movesTop ? Math.min(startY + deltaY, bottom - minimum) : startY;

  return {
    x,
    y,
    width: movesLeft
      ? right - x
      : movesRight
        ? Math.max(minimum, startWidth + deltaX)
        : startWidth,
    height: movesTop
      ? bottom - y
      : movesBottom
        ? Math.max(minimum, startHeight + deltaY)
        : startHeight,
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
