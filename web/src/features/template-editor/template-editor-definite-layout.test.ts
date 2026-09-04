import { describe, expect, it } from 'vitest';
import { LosslessNumber } from 'lossless-json';

import {
  projectTemplateDefiniteLayout,
  projectTemplateGroupUnionBounds,
} from './template-editor-definite-layout';

type Node = Record<string, unknown>;

const fixed = (x: number, y: number, width: number, height: number) => ({
  type: 'ABSOLUTE', xMm: x, yMm: y,
  widthMode: 'FIXED', widthMm: width,
  heightMode: 'FIXED', heightMm: height,
});

const rect = (nodeId: string, placement: Record<string, unknown>): Node => ({
  nodeId, kind: 'rect', placement,
});

const stackPlacement = (overrides: Record<string, unknown> = {}) => ({
  type: 'STACK', widthMode: 'FIXED', widthMm: 10,
  heightMode: 'FIXED', heightMm: 5,
  ...overrides,
});

const gridPlacement = (overrides: Record<string, unknown> = {}) => ({
  type: 'GRID', column: 0, row: 0, columnSpan: 1, rowSpan: 1,
  widthMode: 'FIXED', widthMm: 10,
  heightMode: 'FIXED', heightMm: 5,
  ...overrides,
});

const canvas = (children: Node[], widthMm = 100, heightMm = 80): Node => ({
  nodeId: 'canvas', kind: 'canvas', widthMm, heightMm, children,
});

describe('projectTemplateDefiniteLayout', () => {
  it('derives and normalizes a Group HUG union without a parallel box store', () => {
    const first = rect('first', fixed(4, 2, 10, 5));
    const second = rect('second', fixed(18, 9, 8, 6));
    const group: Node = {
      nodeId: 'group', kind: 'group',
      placement: {
        type: 'ABSOLUTE', xMm: 10, yMm: 8,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [first, second],
    };

    const result = projectTemplateDefiniteLayout(canvas([group]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.map(({ nodeId, paintIndex }) => [nodeId, paintIndex])).toEqual([
      ['canvas', 0], ['group', 1], ['first', 2], ['second', 3],
    ]);
    expect(result.entries[1]).toMatchObject({
      nodeId: 'group', parentNodeId: 'canvas', kind: 'group', value: group,
      parentLayout: 'ABSOLUTE',
      localRect: { x: 10, y: 8, width: 22, height: 13 },
      worldRect: { x: 10, y: 8, width: 22, height: 13 },
      worldContentRect: { x: 6, y: 6, width: 22, height: 13 },
    });
    expect(result.entries[2]).toMatchObject({
      parentNodeId: 'group', localRect: { x: 0, y: 0, width: 10, height: 5 },
      worldRect: { x: 10, y: 8, width: 10, height: 5 },
    });
    expect(result.entries[3]?.worldRect).toEqual({ x: 24, y: 15, width: 8, height: 6 });
  });

  it('uses exact quarter-turn child AABBs for Group and HUG Frame bounds', () => {
    const transformedChild = (nodeId: string): Node => ({
      ...rect(nodeId, fixed(10, 20, 4, 2)),
      transform: {
        rotationDeg: 90,
        scaleX: 2,
        scaleY: 3,
        originX: 0,
        originY: 0,
      },
    });
    const group: Node = {
      nodeId: 'transformed-group', kind: 'group',
      placement: {
        type: 'ABSOLUTE', xMm: 50, yMm: 40,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [transformedChild('group-child')],
    };
    const frame: Node = {
      nodeId: 'transformed-frame', kind: 'frame',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [transformedChild('frame-child')],
    };

    const result = projectTemplateDefiniteLayout(canvas([group, frame]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'transformed-group')).toMatchObject({
      worldRect: { x: 50, y: 40, width: 6, height: 8 },
      worldContentRect: { x: 46, y: 20, width: 6, height: 8 },
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'group-child')?.worldRect).toEqual({
      x: 56, y: 40, width: 4, height: 2,
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'transformed-frame')).toMatchObject({
      worldRect: { x: 0, y: 0, width: 10, height: 28 },
    });
  });

  it('exposes the transform-aware Group union through the command calculation seam', () => {
    const group: Node = {
      nodeId: 'shared-group', kind: 'group',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [{
        ...rect('flipped-child', fixed(10, 20, 4, 2)),
        transform: {
          rotationDeg: 0,
          scaleX: -2,
          scaleY: 3,
          originX: 0.5,
          originY: 0.5,
        },
      }],
    };

    expect(projectTemplateGroupUnionBounds(group)).toEqual({
      state: 'ready',
      bounds: {
        minimumX: 8,
        minimumY: 18,
        maximumX: 16,
        maximumY: 24,
        width: 8,
        height: 6,
      },
    });
  });

  it.each(['group', 'frame'])('fails closed when a HUG %s needs an arbitrary-angle child AABB', (
    kind,
  ) => {
    const container: Node = {
      nodeId: `${kind}-arbitrary-transform`, kind,
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [{
        ...rect(`${kind}-child`, fixed(2, 3, 4, 5)),
        transform: {
          rotationDeg: 45,
          scaleX: 1,
          scaleY: 1,
          originX: 0.5,
          originY: 0.5,
        },
      }],
    };

    expect(projectTemplateDefiniteLayout(canvas([container]))).toEqual({
      state: 'invalid',
      problems: [{
        code: 'EDITOR_LAYOUT_INTRINSIC_UNSUPPORTED',
        nodeId: `${kind}-child`,
        property: 'transform.rotationDeg',
      }],
    });
  });

  it.each([
    {
      label: 'affine endpoint overflow',
      children: [{
        ...rect('overflow-child', fixed(0, 0, 1e308, 1)),
        transform: {
          rotationDeg: 0,
          scaleX: 1e308,
          scaleY: 1,
          originX: 0,
          originY: 0,
        },
      }],
      nodeId: 'overflow-child',
      property: 'transform',
    },
    {
      label: 'union size overflow',
      children: [
        rect('left-child', fixed(-1e308, 0, 1, 1)),
        rect('right-child', fixed(1e308, 0, 1, 1)),
      ],
      nodeId: 'overflow-group',
      property: 'children',
    },
  ])('fails closed on $label while measuring a Group', ({ children, nodeId, property }) => {
    const group: Node = {
      nodeId: 'overflow-group', kind: 'group',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children,
    };

    expect(projectTemplateDefiniteLayout(canvas([group]))).toEqual({
      state: 'invalid',
      problems: [{
        code: 'EDITOR_LAYOUT_CONSTRAINT_INVALID',
        nodeId,
        property,
      }],
    });
  });

  it('places Frame children from its inward-stroke and padding content box', () => {
    const child = rect('child', fixed(2, 3, 8, 6));
    const frame: Node = {
      nodeId: 'frame', kind: 'frame', placement: fixed(10, 10, 40, 30),
      stroke: { widthMm: 1 },
      padding: { topMm: 2, rightMm: 3, bottomMm: 4, leftMm: 5 },
      children: [child],
    };

    const result = projectTemplateDefiniteLayout(canvas([frame]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries[1]).toMatchObject({
      nodeId: 'frame', parentNodeId: 'canvas',
      localRect: { x: 10, y: 10, width: 40, height: 30 },
      worldContentRect: { x: 16, y: 13, width: 30, height: 22 },
    });
    expect(result.entries[2]).toMatchObject({
      nodeId: 'child', parentNodeId: 'frame', parentLayout: 'ABSOLUTE',
      localRect: { x: 2, y: 3, width: 8, height: 6 },
      worldRect: { x: 18, y: 16, width: 8, height: 6 },
      worldContentRect: null,
    });
  });

  it('measures a resource-free HUG Frame from fixed ABSOLUTE children', () => {
    const frame: Node = {
      nodeId: 'hug-frame', kind: 'frame',
      placement: {
        type: 'ABSOLUTE', xMm: 10, yMm: 10,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      stroke: { widthMm: 1 },
      padding: { topMm: 2, rightMm: 3, bottomMm: 4, leftMm: 5 },
      children: [rect('child', fixed(2, 3, 8, 6))],
    };

    const result = projectTemplateDefiniteLayout(canvas([frame]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries[1]).toMatchObject({
      worldRect: { x: 10, y: 10, width: 20, height: 17 },
      worldContentRect: { x: 16, y: 13, width: 10, height: 9 },
    });
    expect(result.entries[2]?.worldRect).toEqual({ x: 18, y: 16, width: 8, height: 6 });
  });

  it('uses a definite opposite-axis offer for a one-axis-HUG Frame child FILL', () => {
    const frame: Node = {
      nodeId: 'one-axis-frame', kind: 'frame',
      placement: {
        type: 'ABSOLUTE', xMm: 2, yMm: 3,
        widthMode: 'HUG_CONTENT',
        heightMode: 'FILL', bottomInsetMm: 5,
      },
      padding: { topMm: 1, rightMm: 1, bottomMm: 1, leftMm: 1 },
      children: [rect('fill-height', {
        type: 'ABSOLUTE', xMm: 1, yMm: 2,
        widthMode: 'FIXED', widthMm: 10,
        heightMode: 'FILL', bottomInsetMm: 3,
      })],
    };

    const result = projectTemplateDefiniteLayout(canvas([frame], 60, 50));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'one-axis-frame')).toMatchObject({
      worldRect: { x: 2, y: 3, width: 13, height: 42 },
      worldContentRect: { x: 3, y: 4, width: 11, height: 40 },
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'fill-height')?.worldRect).toEqual({
      x: 4, y: 6, width: 10, height: 35,
    });
  });

  it('measures a deterministic dual-HUG Stack and arranges its fixed children', () => {
    const stack: Node = {
      nodeId: 'hug-stack', kind: 'stack',
      placement: {
        type: 'ABSOLUTE', xMm: 10, yMm: 10,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      direction: 'ROW', gapMm: 2,
      padding: { topMm: 1, rightMm: 3, bottomMm: 2, leftMm: 2 },
      children: [
        rect('first', stackPlacement({
          widthMm: 10, heightMm: 4,
          marginTopMm: 1, marginLeftMm: 1, marginRightMm: 2,
        })),
        rect('second', stackPlacement({
          widthMm: 6, heightMm: 8,
          marginLeftMm: -1, marginRightMm: 1, marginBottomMm: 1,
        })),
      ],
    };

    const result = projectTemplateDefiniteLayout(canvas([stack]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'hug-stack')).toMatchObject({
      worldRect: { x: 10, y: 10, width: 26, height: 12 },
      worldContentRect: { x: 12, y: 11, width: 21, height: 9 },
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'first')?.worldRect).toEqual({
      x: 13, y: 12, width: 10, height: 4,
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'second')?.worldRect).toEqual({
      x: 26, y: 11, width: 6, height: 8,
    });
  });

  it('measures a deterministic dual-HUG Grid from ordered AUTO span constraints', () => {
    const grid: Node = {
      nodeId: 'hug-grid', kind: 'grid',
      placement: {
        type: 'ABSOLUTE', xMm: 5, yMm: 6,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      padding: { topMm: 1, rightMm: 1, bottomMm: 1, leftMm: 1 },
      columns: [{ type: 'AUTO' }, { type: 'AUTO' }],
      rows: [{ type: 'AUTO' }], columnGapMm: 2,
      children: [
        rect('single', gridPlacement({
          widthMm: 10, heightMm: 4,
          marginLeftMm: 1, marginRightMm: 1, marginTopMm: 1,
        })),
        rect('spanning', gridPlacement({
          widthMm: 30, heightMm: 6, columnSpan: 2,
        })),
      ],
    };

    const result = projectTemplateDefiniteLayout(canvas([grid]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'hug-grid')).toMatchObject({
      worldRect: { x: 5, y: 6, width: 32, height: 8 },
      worldContentRect: { x: 6, y: 7, width: 30, height: 6 },
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'single')?.worldRect).toEqual({
      x: 7, y: 8, width: 10, height: 4,
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'spanning')?.worldRect).toEqual({
      x: 6, y: 7, width: 30, height: 6,
    });
  });

  it.each([
    {
      axis: 'width',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'FIXED', heightMm: 10,
      },
      columns: [{ type: 'FRACTION', weight: 1 }],
      rows: [{ type: 'FIXED', valueMm: 10 }],
      property: 'columns',
    },
    {
      axis: 'height',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'FIXED', widthMm: 10, heightMode: 'HUG_CONTENT',
      },
      columns: [{ type: 'FIXED', valueMm: 10 }],
      rows: [{ type: 'FRACTION', weight: 1 }],
      property: 'rows',
    },
  ])('rejects FRACTION tracks on a HUG Grid $axis axis', ({
    placement, columns, rows, property,
  }) => {
    const result = projectTemplateDefiniteLayout(canvas([{
      nodeId: 'fraction-hug-grid', kind: 'grid', placement, columns, rows, children: [],
    }]));

    expect(result).toEqual({
      state: 'invalid',
      problems: [{
        code: 'EDITOR_LAYOUT_CONSTRAINT_INVALID',
        nodeId: 'fraction-hug-grid',
        property,
      }],
    });
  });

  it('arranges Stack children in authored order with signed margins and weighted FILL bounds', () => {
    const stack: Node = {
      nodeId: 'stack', kind: 'stack', placement: fixed(5, 4, 100, 20),
      direction: 'ROW', gapMm: 2, justifyContent: 'START', alignItems: 'START',
      children: [
        rect('fixed', stackPlacement({ marginRightMm: 2 })),
        rect('capped', stackPlacement({
          widthMode: 'FILL', widthMm: undefined, heightMm: 6,
          fillWeight: 1, maxWidthMm: 20, marginLeftMm: 1, marginRightMm: 1,
        })),
        rect('remainder', stackPlacement({
          widthMode: 'FILL', widthMm: undefined, heightMm: 7,
          fillWeight: 3, marginLeftMm: 1, marginRightMm: -1,
        })),
      ],
    };

    const result = projectTemplateDefiniteLayout(canvas([stack], 120, 40));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.slice(2).map(({ nodeId, parentLayout, localRect, worldRect }) => ({
      nodeId, parentLayout, localRect, worldRect,
    }))).toEqual([
      {
        nodeId: 'fixed', parentLayout: 'STACK',
        localRect: { x: 0, y: 0, width: 10, height: 5 },
        worldRect: { x: 5, y: 4, width: 10, height: 5 },
      },
      {
        nodeId: 'capped', parentLayout: 'STACK',
        localRect: { x: 15, y: 0, width: 20, height: 6 },
        worldRect: { x: 20, y: 4, width: 20, height: 6 },
      },
      {
        nodeId: 'remainder', parentLayout: 'STACK',
        localRect: { x: 39, y: 0, width: 62, height: 7 },
        worldRect: { x: 44, y: 4, width: 62, height: 7 },
      },
    ]);
  });

  it('freezes opposite Stack FILL min and max violations in the same round', () => {
    const stack: Node = {
      nodeId: 'bounded-stack', kind: 'stack', placement: fixed(0, 0, 100, 20),
      direction: 'ROW', gapMm: 0, justifyContent: 'END', alignItems: 'START',
      children: [
        rect('minimum', stackPlacement({
          widthMode: 'FILL', widthMm: undefined, minWidthMm: 80,
        })),
        rect('maximum', stackPlacement({
          widthMode: 'FILL', widthMm: undefined, maxWidthMm: 10,
        })),
      ],
    };

    const result = projectTemplateDefiniteLayout(canvas([stack], 100, 20));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'minimum')?.worldRect).toEqual({
      x: 10, y: 0, width: 80, height: 5,
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'maximum')?.worldRect).toEqual({
      x: 90, y: 0, width: 10, height: 5,
    });
  });

  it('prunes statically render-false subtrees from every definite container layout', () => {
    const hiddenFrame = (nodeId: string, placement: Record<string, unknown>): Node => ({
      nodeId, kind: 'frame', render: false, placement,
      children: [rect(`${nodeId}-descendant`, fixed(0, 0, 50, 5))],
    });

    const stackResult = projectTemplateDefiniteLayout(canvas([{
      nodeId: 'stack', kind: 'stack', placement: fixed(0, 0, 100, 20),
      direction: 'ROW', gapMm: 2,
      children: [
        rect('stack-first', stackPlacement()),
        hiddenFrame('stack-hidden', stackPlacement({ widthMm: 50 })),
        rect('stack-last', stackPlacement()),
      ],
    }]));
    expect(stackResult.state).toBe('ready');
    if (stackResult.state !== 'ready') throw new Error('expected ready Stack layout');
    expect(stackResult.entries.map(({ nodeId }) => nodeId)).toEqual([
      'canvas', 'stack', 'stack-first', 'stack-last',
    ]);
    expect(stackResult.entries.at(-1)?.worldRect.x).toBe(12);

    const gridResult = projectTemplateDefiniteLayout(canvas([{
      nodeId: 'grid', kind: 'grid', placement: fixed(0, 0, 100, 20),
      columns: [{ type: 'AUTO' }, { type: 'FRACTION', weight: 1 }],
      rows: [{ type: 'FRACTION', weight: 1 }],
      children: [
        hiddenFrame('grid-hidden', gridPlacement({ widthMm: 50 })),
        rect('grid-auto', gridPlacement({ widthMm: 10 })),
        rect('grid-fill', gridPlacement({
          column: 1, widthMode: 'FILL', widthMm: undefined,
        })),
      ],
    }]));
    expect(gridResult.state).toBe('ready');
    if (gridResult.state !== 'ready') throw new Error('expected ready Grid layout');
    expect(gridResult.entries.map(({ nodeId }) => nodeId)).toEqual([
      'canvas', 'grid', 'grid-auto', 'grid-fill',
    ]);
    expect(gridResult.entries.at(-1)?.worldRect).toMatchObject({ x: 10, width: 90 });

    const groupResult = projectTemplateDefiniteLayout(canvas([{
      nodeId: 'group', kind: 'group',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [
        rect('group-visible', fixed(4, 3, 10, 5)),
        hiddenFrame('group-hidden', fixed(100, 50, 50, 20)),
      ],
    }]));
    expect(groupResult.state).toBe('ready');
    if (groupResult.state !== 'ready') throw new Error('expected ready Group layout');
    expect(groupResult.entries.map(({ nodeId }) => nodeId)).toEqual([
      'canvas', 'group', 'group-visible',
    ]);
    expect(groupResult.entries[1]?.worldRect).toMatchObject({ width: 10, height: 5 });

    const frameResult = projectTemplateDefiniteLayout(canvas([{
      nodeId: 'frame', kind: 'frame',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [
        rect('frame-visible', fixed(2, 3, 10, 5)),
        hiddenFrame('frame-hidden', fixed(100, 50, 50, 20)),
      ],
    }]));
    expect(frameResult.state).toBe('ready');
    if (frameResult.state !== 'ready') throw new Error('expected ready Frame layout');
    expect(frameResult.entries.map(({ nodeId }) => nodeId)).toEqual([
      'canvas', 'frame', 'frame-visible',
    ]);
    expect(frameResult.entries[1]?.worldRect).toMatchObject({ width: 12, height: 8 });
  });

  it('applies Stack COLUMN justification and inherited or overridden cross alignment', () => {
    const stack: Node = {
      nodeId: 'column-stack', kind: 'stack', placement: fixed(5, 4, 40, 40),
      direction: 'COLUMN', gapMm: 2, justifyContent: 'CENTER', alignItems: 'END',
      children: [
        rect('end', stackPlacement({
          marginTopMm: 2, marginBottomMm: -1, marginLeftMm: 3, marginRightMm: 1,
        })),
        rect('center', stackPlacement({
          widthMm: 12, marginTopMm: 1, marginLeftMm: -2, marginRightMm: 2,
          alignSelf: 'CENTER',
        })),
      ],
    };

    const result = projectTemplateDefiniteLayout(canvas([stack]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'end')?.worldRect).toEqual({
      x: 34, y: 19, width: 10, height: 5,
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'center')?.worldRect).toEqual({
      x: 17, y: 26, width: 12, height: 5,
    });
  });

  it('applies HUG min/max bounds when a nested container is measured as a Stack child', () => {
    const childFrame: Node = {
      nodeId: 'bounded-hug', kind: 'frame',
      placement: stackPlacement({
        widthMode: 'HUG_CONTENT', widthMm: undefined, maxWidthMm: 8,
        heightMm: 10,
      }),
      children: [rect('wide-child', fixed(0, 0, 10, 4))],
    };
    const stack: Node = {
      nodeId: 'host-stack', kind: 'stack', placement: fixed(0, 0, 100, 20),
      direction: 'ROW', children: [childFrame],
    };

    const result = projectTemplateDefiniteLayout(canvas([stack]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'bounded-hug')?.worldRect).toEqual({
      x: 0, y: 0, width: 8, height: 10,
    });
  });

  it('solves Grid FIXED and FRACTION tracks columns-first inside the content box', () => {
    const grid: Node = {
      nodeId: 'grid', kind: 'grid', placement: fixed(10, 5, 80, 50),
      stroke: { widthMm: 1 },
      padding: { topMm: 2, rightMm: 3, bottomMm: 4, leftMm: 5 },
      columns: [
        { type: 'FIXED', valueMm: 10 },
        { type: 'FRACTION', weight: 1 },
        { type: 'FRACTION', weight: 2 },
      ],
      rows: [{ type: 'FIXED', valueMm: 12 }, { type: 'FRACTION', weight: 1 }],
      columnGapMm: 2,
      rowGapMm: 3,
      children: [
        rect('first', gridPlacement({ widthMode: 'FILL', heightMode: 'FILL' })),
        rect('second', gridPlacement({
          column: 1, row: 1, columnSpan: 2,
          widthMode: 'FILL', heightMode: 'FILL',
        })),
      ],
    };

    const result = projectTemplateDefiniteLayout(canvas([grid]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries[1]?.worldContentRect).toEqual({ x: 16, y: 8, width: 70, height: 42 });
    expect(result.entries[2]).toMatchObject({
      nodeId: 'first', parentNodeId: 'grid', parentLayout: 'GRID',
      localRect: { x: 0, y: 0, width: 10, height: 12 },
      worldRect: { x: 16, y: 8, width: 10, height: 12 },
    });
    expect(result.entries[3]?.localRect.x).toBe(12);
    expect(result.entries[3]?.localRect.y).toBe(15);
    expect(result.entries[3]?.localRect.width).toBeCloseTo(58, 12);
    expect(result.entries[3]?.localRect.height).toBe(27);
  });

  it('defaults omitted optional Grid spans to one track', () => {
    const child = rect('default-span', {
      type: 'GRID', row: 0, column: 0,
      widthMode: 'FILL', heightMode: 'FILL',
    });
    const grid: Node = {
      nodeId: 'grid', kind: 'grid', placement: fixed(2, 3, 20, 10),
      columns: [{ type: 'FRACTION', weight: 1 }],
      rows: [{ type: 'FRACTION', weight: 1 }],
      children: [child],
    };

    const result = projectTemplateDefiniteLayout(canvas([grid]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'default-span')?.worldRect).toEqual({
      x: 2, y: 3, width: 20, height: 10,
    });
  });

  it('grows stable AUTO span constraints before assigning the FRACTION remainder', () => {
    const grid: Node = {
      nodeId: 'auto-grid', kind: 'grid', placement: fixed(0, 0, 100, 30),
      columns: [
        { type: 'AUTO' }, { type: 'AUTO' }, { type: 'FRACTION', weight: 1 },
      ],
      rows: [{ type: 'FRACTION', weight: 1 }],
      columnGapMm: 2,
      children: [
        rect('single', gridPlacement({ widthMm: 20, marginLeftMm: 1, marginRightMm: 2 })),
        rect('spanning', gridPlacement({ widthMm: 60, heightMm: 7, columnSpan: 2 })),
        rect('fraction', gridPlacement({
          column: 2, widthMode: 'FILL', heightMode: 'FILL',
        })),
      ],
    };

    const result = projectTemplateDefiniteLayout(canvas([grid]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'fraction')?.localRect).toEqual({
      x: 62, y: 0, width: 38, height: 30,
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'single')?.localRect).toEqual({
      x: 1, y: 0, width: 20, height: 5,
    });
  });

  it('feeds a Grid cell outer box to a nested Stack before arranging descendants', () => {
    const innerStack: Node = {
      nodeId: 'inner-stack', kind: 'stack',
      placement: gridPlacement({ column: 1, widthMode: 'FILL', heightMode: 'FILL' }),
      direction: 'ROW',
      padding: { topMm: 1, rightMm: 2, bottomMm: 1, leftMm: 2 },
      children: [rect('inner-leaf', stackPlacement({
        widthMm: 8, heightMode: 'FILL', heightMm: undefined,
      }))],
    };
    const outerGrid: Node = {
      nodeId: 'outer-grid', kind: 'grid', placement: fixed(10, 10, 70, 30),
      columns: [{ type: 'FIXED', valueMm: 20 }, { type: 'FRACTION', weight: 1 }],
      rows: [{ type: 'FRACTION', weight: 1 }],
      columnGapMm: 2,
      children: [innerStack],
    };

    const result = projectTemplateDefiniteLayout(canvas([outerGrid]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'inner-stack')).toMatchObject({
      localRect: { x: 22, y: 0, width: 48, height: 30 },
      worldRect: { x: 32, y: 10, width: 48, height: 30 },
      worldContentRect: { x: 34, y: 11, width: 44, height: 28 },
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'inner-leaf')?.worldRect).toEqual({
      x: 34, y: 11, width: 8, height: 28,
    });
  });

  it('uses solved Grid columns as the offer for a HUG row containing a width-FILL Stack', () => {
    const innerStack: Node = {
      nodeId: 'row-stack', kind: 'stack',
      placement: gridPlacement({ widthMode: 'FILL', heightMode: 'HUG_CONTENT' }),
      direction: 'ROW',
      padding: { topMm: 1, rightMm: 1, bottomMm: 1, leftMm: 1 },
      children: [rect('row-fill', stackPlacement({
        widthMode: 'FILL', widthMm: undefined, heightMm: 5,
      }))],
    };
    const grid: Node = {
      nodeId: 'column-offer-grid', kind: 'grid',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'FIXED', widthMm: 40,
        heightMode: 'HUG_CONTENT',
      },
      padding: { topMm: 1, rightMm: 1, bottomMm: 1, leftMm: 1 },
      columns: [{ type: 'FRACTION', weight: 1 }],
      rows: [{ type: 'AUTO' }],
      children: [innerStack],
    };

    const result = projectTemplateDefiniteLayout(canvas([grid]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'column-offer-grid')).toMatchObject({
      worldRect: { x: 0, y: 0, width: 40, height: 9 },
      worldContentRect: { x: 1, y: 1, width: 38, height: 7 },
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'row-stack')).toMatchObject({
      worldRect: { x: 1, y: 1, width: 38, height: 7 },
      worldContentRect: { x: 2, y: 2, width: 36, height: 5 },
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'row-fill')?.worldRect).toEqual({
      x: 2, y: 2, width: 36, height: 5,
    });
  });

  it('uses signed Grid margin intervals for alignment and clamps FILL before placement', () => {
    const grid: Node = {
      nodeId: 'aligned-grid', kind: 'grid', placement: fixed(0, 0, 100, 50),
      columns: [{ type: 'FRACTION', weight: 1 }, { type: 'FRACTION', weight: 1 }],
      rows: [{ type: 'FRACTION', weight: 1 }, { type: 'FRACTION', weight: 1 }],
      columnGapMm: 4, rowGapMm: 2,
      children: [
        rect('fixed-aligned', gridPlacement({
          marginTopMm: 1, marginRightMm: 4, marginBottomMm: 3, marginLeftMm: 2,
          horizontalAlignSelf: 'END', verticalAlignSelf: 'CENTER',
          widthMm: 10, heightMm: 6,
        })),
        rect('fill-clamped', gridPlacement({
          column: 1, row: 1, widthMode: 'FILL', heightMode: 'FILL',
          maxWidthMm: 30, maxHeightMm: 20,
          marginTopMm: 2, marginRightMm: 3, marginBottomMm: -1, marginLeftMm: -2,
        })),
      ],
    };

    const result = projectTemplateDefiniteLayout(canvas([grid]));

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.entries.find(({ nodeId }) => nodeId === 'fixed-aligned')?.worldRect).toEqual({
      x: 34, y: 8, width: 10, height: 6,
    });
    expect(result.entries.find(({ nodeId }) => nodeId === 'fill-clamped')?.worldRect).toEqual({
      x: 50, y: 28, width: 30, height: 20,
    });
  });

  it('rejects AUTO/FILL cycles and resource-dependent leaf HUG without partial entries', () => {
    const cycleGrid: Node = {
      nodeId: 'cycle-grid', kind: 'grid', placement: fixed(0, 0, 100, 20),
      columns: [{ type: 'AUTO' }], rows: [{ type: 'FRACTION', weight: 1 }],
      children: [rect('fill', gridPlacement({ widthMode: 'FILL' }))],
    };
    const cycleResult = projectTemplateDefiniteLayout(canvas([cycleGrid]));
    expect(cycleResult).toEqual({
      state: 'invalid',
      problems: [{ code: 'EDITOR_LAYOUT_CYCLE', nodeId: 'fill', property: 'placement.widthMode' }],
    });
    expect('entries' in cycleResult).toBe(false);

    const hugText: Node = {
      nodeId: 'text', kind: 'text',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'FIXED', heightMm: 5,
      },
    };
    expect(projectTemplateDefiniteLayout(canvas([hugText]))).toEqual({
      state: 'invalid',
      problems: [{
        code: 'EDITOR_LAYOUT_INTRINSIC_UNSUPPORTED',
        nodeId: 'text', property: 'placement.widthMode',
      }],
    });
  });

  it('accepts only finite authored JSON numbers and rejects duplicate IDs and recursive graphs atomically', () => {
    const authored = rect('authored', {
      type: 'ABSOLUTE', xMm: new LosslessNumber('1.5'), yMm: 0,
      widthMode: 'FIXED', widthMm: 2,
      heightMode: 'FIXED', heightMm: 3,
    });
    const ready = projectTemplateDefiniteLayout(canvas([authored]));
    expect(ready.state).toBe('ready');

    for (const invalidNumber of ['', null, false]) {
      const malformed = rect('malformed', fixed(0, 0, 2, 3));
      (malformed.placement as Record<string, unknown>).xMm = invalidNumber;
      const result = projectTemplateDefiniteLayout(canvas([malformed]));
      expect(result.state).toBe('invalid');
      if (result.state !== 'invalid') throw new Error('expected invalid layout');
      expect(result.problems[0]).toMatchObject({
        code: 'EDITOR_LAYOUT_CONSTRAINT_INVALID', nodeId: 'malformed', property: 'placement.xMm',
      });
      expect('entries' in result).toBe(false);
    }

    const duplicate = projectTemplateDefiniteLayout(canvas([
      rect('same', fixed(0, 0, 1, 1)), rect('same', fixed(2, 0, 1, 1)),
    ]));
    expect(duplicate).toEqual({
      state: 'invalid',
      problems: [{ code: 'EDITOR_LAYOUT_CONSTRAINT_INVALID', nodeId: 'same', property: 'nodeId' }],
    });

    const recursive: Node = {
      nodeId: 'recursive', kind: 'frame', placement: fixed(0, 0, 10, 10), children: [],
    };
    (recursive.children as Node[]).push(recursive);
    expect(projectTemplateDefiniteLayout(canvas([recursive]))).toEqual({
      state: 'invalid',
      problems: [{
        code: 'EDITOR_LAYOUT_CYCLE', nodeId: 'recursive', property: 'children',
      }],
    });
  });

  it('clamps nested container HUG axes and rejects non-canonical Group constraints', () => {
    const inner: Node = {
      nodeId: 'inner-frame', kind: 'frame',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', maxWidthMm: 8,
        heightMode: 'HUG_CONTENT',
      },
      children: [rect('wide', fixed(0, 0, 10, 4))],
    };
    const outer: Node = {
      nodeId: 'outer-frame', kind: 'frame',
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [inner],
    };
    const nested = projectTemplateDefiniteLayout(canvas([outer]));
    expect(nested.state).toBe('ready');
    if (nested.state !== 'ready') throw new Error('expected ready layout');
    expect(nested.entries.find(({ nodeId }) => nodeId === 'outer-frame')?.worldRect.width).toBe(8);
    expect(nested.entries.find(({ nodeId }) => nodeId === 'inner-frame')?.worldRect.width).toBe(8);

    for (const placement of [
      fixed(0, 0, 10, 10),
      {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT', minWidthMm: 1,
      },
    ]) {
      const invalidGroup: Node = {
        nodeId: 'invalid-group', kind: 'group', placement,
        children: [rect('child', fixed(0, 0, 1, 1))],
      };
      const result = projectTemplateDefiniteLayout(canvas([invalidGroup]));
      expect(result.state).toBe('invalid');
      if (result.state !== 'invalid') throw new Error('expected invalid layout');
      expect(result.problems[0]).toMatchObject({
        code: 'EDITOR_LAYOUT_CONSTRAINT_INVALID', nodeId: 'invalid-group',
      });
    }
  });

  it('keeps Canvas a fixed FREE board and does not reinterpret hidden layout or padding fields', () => {
    const root = canvas([rect('child', fixed(3, 4, 5, 6))], 20, 10);
    root.layoutMode = 'STACK';
    root.padding = { topMm: 9, rightMm: 9, bottomMm: 9, leftMm: 9 };

    const result = projectTemplateDefiniteLayout(root);

    expect(result.state).toBe('ready');
    if (result.state !== 'ready') throw new Error('expected ready layout');
    expect(result.canvasContentRect).toEqual({ x: 0, y: 0, width: 20, height: 10 });
    expect(result.entries[1]).toMatchObject({
      parentLayout: 'ABSOLUTE',
      localRect: { x: 3, y: 4, width: 5, height: 6 },
      worldRect: { x: 3, y: 4, width: 5, height: 6 },
    });
  });
});
