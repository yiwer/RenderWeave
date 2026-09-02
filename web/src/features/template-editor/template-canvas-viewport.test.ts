import { describe, expect, it } from 'vitest';

import {
  CANVAS_ZOOM_MAX,
  CANVAS_ZOOM_MIN,
  fitCanvasViewport,
  panCanvasViewport,
  resizeCanvasRect,
  wheelZoomCanvasViewport,
  zoomCanvasViewportAt,
} from './template-canvas-viewport';

describe('Template Canvas viewport transform', () => {
  it('fits and centers a finite physical artboard inside the viewport', () => {
    const fitted = fitCanvasViewport(
      { width: 1000, height: 700 },
      { width: 840, height: 1188 },
      40,
    );

    expect(fitted.scale).toBeCloseTo(620 / 1188, 8);
    expect(fitted.x).toBeCloseTo((1000 - 840 * fitted.scale) / 2, 8);
    expect(fitted.y).toBeCloseTo(40, 8);
  });

  it('zooms around the pointer without moving the world point under it', () => {
    const before = { scale: 0.5, x: 100, y: 50 };
    const anchor = { x: 300, y: 250 };
    const worldBefore = {
      x: (anchor.x - before.x) / before.scale,
      y: (anchor.y - before.y) / before.scale,
    };

    const after = zoomCanvasViewportAt(before, 1, anchor);

    expect(after).toEqual({ scale: 1, x: -100, y: -150 });
    expect(after.x + worldBefore.x * after.scale).toBeCloseTo(anchor.x, 8);
    expect(after.y + worldBefore.y * after.scale).toBeCloseTo(anchor.y, 8);
  });

  it('uses continuous wheel zoom and clamps both zoom boundaries', () => {
    const initial = { scale: 1, x: 0, y: 0 };
    const anchor = { x: 240, y: 160 };

    const zoomedIn = wheelZoomCanvasViewport(initial, -120, anchor, false);
    expect(zoomedIn.scale).toBeGreaterThan(1);
    expect(wheelZoomCanvasViewport(initial, -100_000, anchor, true).scale)
      .toBe(CANVAS_ZOOM_MAX);
    expect(wheelZoomCanvasViewport(initial, 100_000, anchor, true).scale)
      .toBe(CANVAS_ZOOM_MIN);
  });

  it('pans without changing scale', () => {
    expect(panCanvasViewport({ scale: 0.75, x: 20, y: 30 }, { x: -8, y: 12 }))
      .toEqual({ scale: 0.75, x: 12, y: 42 });
  });

  it('resizes each edge independently instead of preserving the authored aspect ratio', () => {
    const start = { x: 40, y: 60, width: 120, height: 120 };

    expect(resizeCanvasRect(start, 's', { x: 0, y: -80 }, 4))
      .toEqual({ x: 40, y: 60, width: 120, height: 40 });
    expect(resizeCanvasRect(start, 'nw', { x: 150, y: 20 }, 4))
      .toEqual({ x: 156, y: 80, width: 4, height: 100 });
    expect(resizeCanvasRect(start, 'e', { x: -200, y: 0 }, 4))
      .toEqual({ x: 40, y: 60, width: 4, height: 120 });
  });
});
