import { describe, expect, it } from 'vitest';

import type { DesignerNode, DraftBox, InspectorProp, NodeKind } from './model';
import { projectPrototypeLayout } from './stack-layout';

function prop(label: string, value: string): InspectorProp {
  return { label, value, bindable: true };
}

function node(
  id: string,
  kind: NodeKind,
  props: InspectorProp[] = [],
  children: DesignerNode[] = [],
): DesignerNode {
  return { id, kind, name: id, detail: '', flags: [], props, children };
}

function box(nodeId: string, x: number, y: number, w: number, h: number): DraftBox {
  return { nodeId, x, y, w, h, tone: 'frame', label: nodeId };
}

function canvas(children: DesignerNode[]): DesignerNode {
  return node('canvas', 'canvas', [
    prop('widthMm', '100'),
    prop('heightMm', '80'),
    prop('layoutMode', 'FREE'),
  ], children);
}

function stackProps(overrides: Record<string, string> = {}): InspectorProp[] {
  const values = {
    direction: 'VERTICAL',
    gapMm: '2',
    mainAlign: 'START',
    crossAlign: 'STRETCH',
    'stroke.widthMm': '1',
    'padding.topMm': '2',
    'padding.rightMm': '3',
    'padding.bottomMm': '4',
    'padding.leftMm': '5',
    ...overrides,
  };
  return Object.entries(values).map(([label, value]) => prop(label, value));
}

describe('projectPrototypeLayout', () => {
  it('derives a Group HUG box from the child union and normalizes local origins', () => {
    const first = node('first', 'rect');
    const second = node('second', 'rect');
    const group = node('group', 'group', [], [first, second]);
    const projection = projectPrototypeLayout(canvas([group]), [
      box('group', 10, 8, 40, 30),
      box('first', 4, 2, 10, 5),
      box('second', 18, 9, 8, 6),
    ]);

    expect(projection.boxByNodeId.get('group')).toMatchObject({ x: 10, y: 8, w: 22, h: 13 });
    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 10, y: 8, w: 10, h: 5 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 24, y: 15, w: 8, h: 6 });
    expect(projection.absoluteByContainerId.get('group')).toMatchObject({
      kind: 'group',
      sourceBounds: { x: 4, y: 2, w: 22, h: 13 },
    });
  });

  it('places Frame absolute children from its live ContentBox', () => {
    const child = node('child', 'rect');
    const frame = node('frame', 'frame', [
      prop('stroke.widthMm', '1'),
      prop('padding.topMm', '2'),
      prop('padding.rightMm', '3'),
      prop('padding.bottomMm', '4'),
      prop('padding.leftMm', '5'),
    ], [child]);
    const projection = projectPrototypeLayout(canvas([frame]), [
      box('frame', 10, 10, 40, 30),
      box('child', 2, 3, 8, 6),
    ]);

    expect(projection.boxByNodeId.get('frame')).toMatchObject({ x: 10, y: 10, w: 40, h: 30 });
    expect(projection.boxByNodeId.get('child')).toMatchObject({ x: 18, y: 16, w: 8, h: 6 });
    expect(projection.absoluteByContainerId.get('frame')).toMatchObject({
      kind: 'frame',
      contentBox: { x: 16, y: 13, w: 30, h: 22 },
    });
  });

  it('arranges a vertical stack inside stroke and padding and stretches the cross axis', () => {
    const first = node('first', 'rect');
    const second = node('second', 'rect');
    const stack = node('stack', 'stack', stackProps(), [first, second]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 10, 5, 50, 40),
      box('first', 0, 0, 12, 6),
      box('second', 0, 0, 18, 8),
    ]);

    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 16, y: 8, w: 40, h: 6 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 16, y: 16, w: 40, h: 8 });
    expect(projection.stackByContainerId.get('stack')).toMatchObject({
      availableMainMm: 32,
      occupiedMainMm: 16,
      freeMainMm: 16,
      overflowMainMm: 0,
    });
  });

  it('centres a horizontal stack on both axes using only positive free space', () => {
    const first = node('first', 'rect');
    const second = node('second', 'rect');
    const stack = node('stack', 'stack', stackProps({
      direction: 'HORIZONTAL',
      gapMm: '4',
      mainAlign: 'CENTER',
      crossAlign: 'CENTER',
      'stroke.widthMm': '0',
      'padding.topMm': '3',
      'padding.rightMm': '5',
      'padding.bottomMm': '3',
      'padding.leftMm': '5',
    }), [first, second]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 10, 10, 60, 30),
      box('first', 1, 1, 10, 8),
      box('second', 1, 1, 20, 12),
    ]);

    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 23, y: 21, w: 10, h: 8 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 37, y: 19, w: 20, h: 12 });
  });

  it('adds distributed free space between horizontal children in authored order', () => {
    const first = node('first', 'rect');
    const second = node('second', 'rect');
    const stack = node('stack', 'stack', stackProps({
      direction: 'HORIZONTAL',
      gapMm: '4',
      mainAlign: 'SPACE_BETWEEN',
      crossAlign: 'START',
      'stroke.widthMm': '0',
      'padding.topMm': '3',
      'padding.rightMm': '5',
      'padding.bottomMm': '3',
      'padding.leftMm': '5',
    }), [first, second]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 10, 10, 60, 30),
      box('first', 1, 1, 10, 8),
      box('second', 1, 1, 20, 12),
    ]);

    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 15, y: 13 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 45, y: 13 });
    expect(projection.stackByContainerId.get('stack')?.placements.map((placement) => placement.nodeId)).toEqual(['first', 'second']);
  });

  it('starts overflowing children at the content origin without negative justification', () => {
    const first = node('first', 'rect');
    const second = node('second', 'rect');
    const stack = node('stack', 'stack', stackProps({
      direction: 'HORIZONTAL',
      gapMm: '3',
      mainAlign: 'END',
      crossAlign: 'START',
      'stroke.widthMm': '0',
      'padding.topMm': '0',
      'padding.rightMm': '0',
      'padding.bottomMm': '0',
      'padding.leftMm': '0',
    }), [first, second]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 4, 4, 20, 12),
      box('first', 1, 1, 12, 4),
      box('second', 1, 1, 10, 4),
    ]);

    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 4, y: 4 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 19, y: 4 });
    expect(projection.stackByContainerId.get('stack')).toMatchObject({
      availableMainMm: 20,
      occupiedMainMm: 25,
      freeMainMm: 0,
      overflowMainMm: 5,
    });
  });

  it('uses tree order as the only direct-child ordering authority', () => {
    const first = node('first', 'rect');
    const second = node('second', 'rect');
    const stack = node('stack', 'stack', stackProps({
      direction: 'HORIZONTAL',
      gapMm: '4',
      'stroke.widthMm': '0',
      'padding.topMm': '0',
      'padding.rightMm': '0',
      'padding.bottomMm': '0',
      'padding.leftMm': '0',
    }), [second, first]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 10, 10, 60, 20),
      box('first', 1, 1, 10, 8),
      box('second', 1, 1, 20, 8),
    ]);

    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 10, y: 10 });
    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 34, y: 10 });
    expect(projection.stackByContainerId.get('stack')?.placements.map((placement) => placement.nodeId)).toEqual(['second', 'first']);
  });

  it('feeds a stretched nested Stack its effective outer box before arranging descendants', () => {
    const innerFirst = node('inner-first', 'rect');
    const innerSecond = node('inner-second', 'rect');
    const inner = node('inner', 'stack', stackProps({
      direction: 'HORIZONTAL',
      gapMm: '1',
      'stroke.widthMm': '0',
      'padding.topMm': '1',
      'padding.rightMm': '2',
      'padding.bottomMm': '1',
      'padding.leftMm': '2',
    }), [innerFirst, innerSecond]);
    const outer = node('outer', 'stack', stackProps({
      direction: 'VERTICAL',
      gapMm: '0',
      'stroke.widthMm': '0',
      'padding.topMm': '5',
      'padding.rightMm': '5',
      'padding.bottomMm': '5',
      'padding.leftMm': '5',
    }), [inner]);
    const projection = projectPrototypeLayout(canvas([outer]), [
      box('outer', 10, 10, 60, 40),
      box('inner', 0, 0, 30, 10),
      box('inner-first', 0, 0, 10, 4),
      box('inner-second', 0, 0, 12, 4),
    ]);

    expect(projection.boxByNodeId.get('inner')).toMatchObject({ x: 15, y: 15, w: 50, h: 10 });
    expect(projection.boxByNodeId.get('inner-first')).toMatchObject({ x: 17, y: 16, w: 10, h: 8 });
    expect(projection.boxByNodeId.get('inner-second')).toMatchObject({ x: 28, y: 16, w: 12, h: 8 });
  });

  it('uses the Canvas content box when the prototype root layout is Stack', () => {
    const first = node('first', 'rect');
    const second = node('second', 'rect');
    const root = node('canvas', 'canvas', [
      prop('widthMm', '100'),
      prop('heightMm', '80'),
      prop('layoutMode', 'STACK'),
      prop('direction', 'HORIZONTAL'),
      prop('gapMm', '5'),
      prop('mainAlign', 'END'),
      prop('crossAlign', 'START'),
      prop('padding.topMm', '10'),
      prop('padding.rightMm', '10'),
      prop('padding.bottomMm', '10'),
      prop('padding.leftMm', '10'),
    ], [first, second]);
    const projection = projectPrototypeLayout(root, [
      box('first', 0, 0, 20, 8),
      box('second', 0, 0, 30, 8),
    ]);

    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 35, y: 10, w: 20, h: 8 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 60, y: 10, w: 30, h: 8 });
    expect(projection.stackByContainerId.get('canvas')).toMatchObject({ availableMainMm: 80, occupiedMainMm: 55, freeMainMm: 25 });
  });

  it('uses signed child margins and lets alignSelf override the container cross alignment', () => {
    const first = node('first', 'rect', [
      prop('placement.marginTopMm', '2'),
      prop('placement.marginRightMm', '1'),
      prop('placement.marginBottomMm', '-1'),
      prop('placement.marginLeftMm', '3'),
      prop('placement.alignSelf', 'END'),
    ]);
    const second = node('second', 'rect', [
      prop('placement.marginTopMm', '1'),
      prop('placement.marginRightMm', '2'),
      prop('placement.marginBottomMm', '0'),
      prop('placement.marginLeftMm', '-2'),
      prop('placement.alignSelf', 'CENTER'),
    ]);
    const stack = node('stack', 'stack', stackProps({
      gapMm: '2',
      crossAlign: 'START',
      'stroke.widthMm': '0',
      'padding.topMm': '0',
      'padding.rightMm': '0',
      'padding.bottomMm': '0',
      'padding.leftMm': '0',
    }), [first, second]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 0, 0, 60, 40),
      box('first', 0, 0, 10, 5),
      box('second', 0, 0, 12, 5),
    ]);

    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 49, y: 2, w: 10, h: 5 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 22, y: 9, w: 12, h: 5 });
    expect(projection.stackByContainerId.get('stack')).toMatchObject({
      occupiedMainMm: 14,
      freeMainMm: 26,
    });
  });

  it('allocates main-axis FILL by weight, freezes a max bound and gives the last active child the remainder', () => {
    const fixed = node('fixed', 'rect', [
      prop('placement.widthMode', 'FIXED'),
      prop('placement.heightMode', 'FIXED'),
      prop('placement.marginRightMm', '2'),
    ]);
    const capped = node('capped', 'rect', [
      prop('placement.widthMode', 'FILL'),
      prop('placement.heightMode', 'FIXED'),
      prop('placement.fillWeight', '1'),
      prop('placement.maxWidthMm', '20'),
      prop('placement.marginLeftMm', '1'),
      prop('placement.marginRightMm', '1'),
    ]);
    const remainder = node('remainder', 'rect', [
      prop('placement.widthMode', 'FILL'),
      prop('placement.heightMode', 'FIXED'),
      prop('placement.fillWeight', '3'),
      prop('placement.marginLeftMm', '1'),
      prop('placement.marginRightMm', '-1'),
    ]);
    const stack = node('stack', 'stack', stackProps({
      direction: 'HORIZONTAL',
      gapMm: '2',
      crossAlign: 'START',
      'stroke.widthMm': '0',
      'padding.topMm': '0',
      'padding.rightMm': '0',
      'padding.bottomMm': '0',
      'padding.leftMm': '0',
    }), [fixed, capped, remainder]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 0, 0, 100, 20),
      box('fixed', 0, 0, 10, 5),
      box('capped', 0, 0, 3, 6),
      box('remainder', 0, 0, 3, 7),
    ]);

    expect(projection.boxByNodeId.get('fixed')).toMatchObject({ x: 0, w: 10 });
    expect(projection.boxByNodeId.get('capped')).toMatchObject({ x: 15, w: 20 });
    expect(projection.boxByNodeId.get('remainder')).toMatchObject({ x: 39, w: 62 });
    expect(projection.stackByContainerId.get('stack')).toMatchObject({
      usedWithoutFillMm: 18,
      fillAvailableMainMm: 82,
      occupiedMainMm: 100,
      freeMainMm: 0,
      overflowMainMm: 0,
    });
    expect(projection.stackByContainerId.get('stack')?.placements.map((placement) => placement.mainAllocationMm)).toEqual([null, 20, 62]);
  });

  it('keeps active minimums when their sum overflows the main-axis offer', () => {
    const first = node('first', 'rect', [
      prop('placement.widthMode', 'FILL'),
      prop('placement.fillWeight', '1'),
      prop('placement.minWidthMm', '70'),
    ]);
    const second = node('second', 'rect', [
      prop('placement.widthMode', 'FILL'),
      prop('placement.fillWeight', '1'),
      prop('placement.minWidthMm', '70'),
    ]);
    const stack = node('stack', 'stack', stackProps({
      direction: 'HORIZONTAL',
      gapMm: '0',
      crossAlign: 'START',
      'stroke.widthMm': '0',
      'padding.topMm': '0',
      'padding.rightMm': '0',
      'padding.bottomMm': '0',
      'padding.leftMm': '0',
    }), [first, second]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 0, 0, 100, 20),
      box('first', 0, 0, 10, 5),
      box('second', 0, 0, 10, 5),
    ]);

    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 0, w: 70 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 70, w: 70 });
    expect(projection.stackByContainerId.get('stack')).toMatchObject({
      occupiedMainMm: 140,
      freeMainMm: 0,
      overflowMainMm: 40,
    });
  });

  it('maps vertical main-axis FILL to height and includes signed top/bottom margins in its offer', () => {
    const fixed = node('fixed', 'rect', [
      prop('placement.heightMode', 'FIXED'),
      prop('placement.marginBottomMm', '2'),
    ]);
    const fill = node('fill', 'rect', [
      prop('placement.heightMode', 'FILL'),
      prop('placement.fillWeight', '1'),
      prop('placement.marginTopMm', '3'),
      prop('placement.marginBottomMm', '-1'),
    ]);
    const stack = node('stack', 'stack', stackProps({
      gapMm: '5',
      crossAlign: 'START',
      'stroke.widthMm': '0',
      'padding.topMm': '0',
      'padding.rightMm': '0',
      'padding.bottomMm': '0',
      'padding.leftMm': '0',
    }), [fixed, fill]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 0, 0, 30, 100),
      box('fixed', 0, 0, 10, 20),
      box('fill', 0, 0, 10, 3),
    ]);

    expect(projection.boxByNodeId.get('fixed')).toMatchObject({ x: 0, y: 0, h: 20 });
    expect(projection.boxByNodeId.get('fill')).toMatchObject({ x: 0, y: 30, h: 71 });
    expect(projection.stackByContainerId.get('stack')).toMatchObject({
      usedWithoutFillMm: 29,
      fillAvailableMainMm: 71,
      occupiedMainMm: 100,
    });
  });

  it('resolves cross-axis FILL from the signed-margin interval and applies a max clamp before inherited alignment', () => {
    const child = node('child', 'rect', [
      prop('placement.widthMode', 'FILL'),
      prop('placement.heightMode', 'FIXED'),
      prop('placement.marginLeftMm', '3'),
      prop('placement.marginRightMm', '5'),
      prop('placement.maxWidthMm', '30'),
    ]);
    const stack = node('stack', 'stack', stackProps({
      crossAlign: 'END',
      'stroke.widthMm': '0',
      'padding.topMm': '0',
      'padding.rightMm': '0',
      'padding.bottomMm': '0',
      'padding.leftMm': '0',
    }), [child]);
    const projection = projectPrototypeLayout(canvas([stack]), [
      box('stack', 0, 0, 50, 20),
      box('child', 0, 0, 10, 5),
    ]);

    expect(projection.boxByNodeId.get('child')).toMatchObject({ x: 15, y: 0, w: 30, h: 5 });
    expect(projection.stackByContainerId.get('stack')?.placements[0]).toMatchObject({
      crossFill: true,
      resolvedCrossAlign: 'END',
    });
  });
});
