import { describe, expect, it } from 'vitest';

import type { DesignerNode, DraftBox, InspectorProp, NodeKind } from './model';
import {
  parsePrototypeGridTrackToken,
  projectPrototypeLayout,
  prototypeGridTrackTokens,
} from './stack-layout';

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

function gridProps(overrides: Record<string, string> = {}): InspectorProp[] {
  const values = {
    columns: '1*, 1*',
    rows: '1*, 1*',
    columnGapMm: '2',
    rowGapMm: '2',
    'stroke.widthMm': '0',
    'padding.topMm': '0',
    'padding.rightMm': '0',
    'padding.bottomMm': '0',
    'padding.leftMm': '0',
    ...overrides,
  };
  return Object.entries(values).map(([label, value]) => prop(label, value));
}

function gridPlacement(overrides: Record<string, string> = {}): InspectorProp[] {
  const values = {
    'placement.widthMode': 'FIXED',
    'placement.heightMode': 'FIXED',
    'placement.column': '0',
    'placement.row': '0',
    'placement.columnSpan': '1',
    'placement.rowSpan': '1',
    'placement.marginTopMm': '0',
    'placement.marginRightMm': '0',
    'placement.marginBottomMm': '0',
    'placement.marginLeftMm': '0',
    'placement.horizontalAlignSelf': 'START',
    'placement.verticalAlignSelf': 'START',
    ...overrides,
  };
  return Object.entries(values).map(([label, value]) => prop(label, value));
}

describe('projectPrototypeLayout Grid projection', () => {
  it('accepts design-layout-draw GridLength shorthand and keeps a bare number as one fixed track', () => {
    expect(prototypeGridTrackTokens('12, auto, 1*, 2*')).toEqual(['12', 'auto', '1*', '2*']);
    expect(parsePrototypeGridTrackToken('12')).toEqual({ canonicalToken: '12', kind: 'FIXED', valueMm: 12 });
    expect(parsePrototypeGridTrackToken('auto')).toEqual({ canonicalToken: 'auto', kind: 'AUTO' });
    expect(parsePrototypeGridTrackToken('*')).toEqual({ canonicalToken: '1*', kind: 'FRACTION', weight: 1 });
    expect(parsePrototypeGridTrackToken('2*')).toEqual({ canonicalToken: '2*', kind: 'FRACTION', weight: 2 });
    expect(parsePrototypeGridTrackToken('12mm')?.canonicalToken).toBe('12');
    expect(parsePrototypeGridTrackToken('2fr')?.canonicalToken).toBe('2*');

    const grid = node('grid', 'grid', gridProps({ columns: '12', rows: '1*' }));
    const projection = projectPrototypeLayout(canvas([grid]), [box('grid', 0, 0, 100, 20)]);
    expect(projection.gridByContainerId.get('grid')?.columns).toHaveLength(1);
    expect(projection.gridByContainerId.get('grid')?.columns[0]).toMatchObject({ kind: 'FIXED', sizeMm: 12 });
  });

  it('solves FIXED then FRACTION tracks inside the inward-stroke ContentBox', () => {
    const first = node('first', 'rect', gridPlacement({
      'placement.column': '0',
      'placement.row': '0',
      'placement.widthMode': 'FILL',
      'placement.heightMode': 'FILL',
    }));
    const second = node('second', 'rect', gridPlacement({
      'placement.column': '1',
      'placement.row': '1',
      'placement.columnSpan': '2',
      'placement.widthMode': 'FILL',
      'placement.heightMode': 'FILL',
    }));
    const grid = node('grid', 'grid', gridProps({
      columns: '10, 1*, 2*',
      rows: '12, 1*',
      columnGapMm: '2',
      rowGapMm: '3',
      'stroke.widthMm': '1',
      'padding.topMm': '2',
      'padding.rightMm': '3',
      'padding.bottomMm': '4',
      'padding.leftMm': '5',
    }), [first, second]);

    const projection = projectPrototypeLayout(canvas([grid]), [
      box('grid', 10, 5, 80, 50),
      box('first', 0, 0, 4, 4),
      box('second', 0, 0, 4, 4),
    ]);
    const trace = projection.gridByContainerId.get('grid');

    expect(trace?.contentBox).toEqual({ x: 16, y: 8, w: 70, h: 42 });
    const columnSizes = trace?.columns.map((track) => track.sizeMm) ?? [];
    expect(columnSizes[0]).toBe(10);
    expect(columnSizes[1]).toBeCloseTo(56 / 3, 12);
    expect(columnSizes[2]).toBeCloseTo(112 / 3, 12);
    expect(trace?.rows.map((track) => track.sizeMm)).toEqual([12, 27]);
    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 16, y: 8, w: 10, h: 12 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 28, y: 23, w: 58, h: 27 });
    expect(trace).toMatchObject({ freeWidthMm: 0, overflowWidthMm: 0, freeHeightMm: 0, overflowHeightMm: 0 });
  });

  it('grows AUTO tracks from stable single-track and multi-track span constraints before FRACTION', () => {
    const single = node('single', 'rect', gridPlacement({
      'placement.column': '0',
      'placement.marginLeftMm': '1',
      'placement.marginRightMm': '2',
    }));
    const spanning = node('spanning', 'rect', gridPlacement({
      'placement.column': '0',
      'placement.columnSpan': '2',
    }));
    const fraction = node('fraction', 'rect', gridPlacement({
      'placement.column': '2',
      'placement.widthMode': 'FILL',
      'placement.heightMode': 'FILL',
    }));
    const grid = node('grid', 'grid', gridProps({
      columns: 'auto, auto, 1*',
      rows: '1*',
      columnGapMm: '2',
      rowGapMm: '0',
    }), [single, spanning, fraction]);

    const projection = projectPrototypeLayout(canvas([grid]), [
      box('grid', 0, 0, 100, 30),
      box('single', 0, 0, 20, 5),
      box('spanning', 0, 0, 60, 7),
      box('fraction', 0, 0, 3, 3),
    ]);
    const trace = projection.gridByContainerId.get('grid');

    expect(trace?.columns.map((track) => track.sizeMm)).toEqual([40.5, 17.5, 38]);
    expect(trace?.columns.map((track) => track.kind)).toEqual(['AUTO', 'AUTO', 'FRACTION']);
    expect(projection.boxByNodeId.get('fraction')).toMatchObject({ x: 62, y: 0, w: 38, h: 30 });
    expect(trace?.autoConstraintCount).toBe(2);
  });

  it('positions FIXED children by signed-margin intervals and clamps FILL before placement', () => {
    const fixed = node('fixed', 'rect', gridPlacement({
      'placement.column': '0',
      'placement.row': '0',
      'placement.marginTopMm': '1',
      'placement.marginRightMm': '4',
      'placement.marginBottomMm': '3',
      'placement.marginLeftMm': '2',
      'placement.horizontalAlignSelf': 'END',
      'placement.verticalAlignSelf': 'CENTER',
    }));
    const fill = node('fill', 'rect', gridPlacement({
      'placement.column': '1',
      'placement.row': '1',
      'placement.widthMode': 'FILL',
      'placement.heightMode': 'FILL',
      'placement.maxWidthMm': '30',
      'placement.maxHeightMm': '20',
      'placement.marginTopMm': '2',
      'placement.marginRightMm': '3',
      'placement.marginBottomMm': '-1',
      'placement.marginLeftMm': '-2',
    }));
    const grid = node('grid', 'grid', gridProps({
      columns: '1*, 1*',
      rows: '1*, 1*',
      columnGapMm: '4',
      rowGapMm: '2',
    }), [fixed, fill]);

    const projection = projectPrototypeLayout(canvas([grid]), [
      box('grid', 0, 0, 100, 50),
      box('fixed', 0, 0, 10, 6),
      box('fill', 0, 0, 4, 4),
    ]);

    expect(projection.boxByNodeId.get('fixed')).toMatchObject({ x: 34, y: 8, w: 10, h: 6 });
    expect(projection.boxByNodeId.get('fill')).toMatchObject({ x: 50, y: 28, w: 30, h: 20 });
    expect(projection.gridByContainerId.get('grid')?.placements[1]).toMatchObject({
      widthMode: 'FILL',
      heightMode: 'FILL',
      horizontalAlignSelf: 'START',
      verticalAlignSelf: 'START',
    });
  });

  it('feeds an effective Grid cell box to a nested Stack before arranging descendants', () => {
    const innerLeaf = node('inner-leaf', 'rect');
    const innerStack = node('inner-stack', 'stack', [
      prop('direction', 'HORIZONTAL'),
      prop('gapMm', '0'),
      prop('mainAlign', 'START'),
      prop('crossAlign', 'STRETCH'),
      prop('padding.topMm', '1'),
      prop('padding.rightMm', '2'),
      prop('padding.bottomMm', '1'),
      prop('padding.leftMm', '2'),
      ...gridPlacement({
        'placement.column': '1',
        'placement.widthMode': 'FILL',
        'placement.heightMode': 'FILL',
      }),
    ], [innerLeaf]);
    const outerGrid = node('outer-grid', 'grid', gridProps({ columns: '20, 1*', rows: '1*' }), [innerStack]);

    const projection = projectPrototypeLayout(canvas([outerGrid]), [
      box('outer-grid', 10, 10, 70, 30),
      box('inner-stack', 0, 0, 10, 10),
      box('inner-leaf', 0, 0, 8, 4),
    ]);

    expect(projection.boxByNodeId.get('inner-stack')).toMatchObject({ x: 32, y: 10, w: 48, h: 30 });
    expect(projection.boxByNodeId.get('inner-leaf')).toMatchObject({ x: 34, y: 11, w: 8, h: 28 });
    expect(projection.stackByContainerId.get('inner-stack')?.contentBox).toEqual({ x: 34, y: 11, w: 44, h: 28 });
  });

  it('uses Canvas padding and explicit tracks when the prototype root layout is Grid', () => {
    const first = node('first', 'rect', gridPlacement({
      'placement.column': '0',
      'placement.row': '0',
      'placement.widthMode': 'FILL',
      'placement.heightMode': 'FILL',
    }));
    const second = node('second', 'rect', gridPlacement({
      'placement.column': '1',
      'placement.row': '0',
      'placement.widthMode': 'FILL',
      'placement.heightMode': 'FILL',
    }));
    const root = node('canvas', 'canvas', [
      prop('widthMm', '100'),
      prop('heightMm', '80'),
      prop('layoutMode', 'GRID'),
      prop('columns', '1*, 2*'),
      prop('rows', '1*'),
      prop('columnGapMm', '4'),
      prop('rowGapMm', '0'),
      prop('padding.topMm', '10'),
      prop('padding.rightMm', '10'),
      prop('padding.bottomMm', '10'),
      prop('padding.leftMm', '10'),
    ], [first, second]);

    const projection = projectPrototypeLayout(root, [
      box('first', 0, 0, 2, 2),
      box('second', 0, 0, 2, 2),
    ]);

    expect(projection.boxByNodeId.get('first')).toMatchObject({ x: 10, y: 10, w: 76 / 3, h: 60 });
    expect(projection.boxByNodeId.get('second')).toMatchObject({ x: 10 + 76 / 3 + 4, y: 10, h: 60 });
    expect(projection.boxByNodeId.get('second')?.w).toBeCloseTo(152 / 3, 12);
    expect(projection.gridByContainerId.get('canvas')?.contentBox).toEqual({ x: 10, y: 10, w: 80, h: 60 });
  });

  it('surfaces invalid tracks and AUTO/FILL cycles without mutating authored boxes', () => {
    const child = node('child', 'rect', gridPlacement({
      'placement.widthMode': 'FILL',
    }));
    const invalidTracks = node('invalid-tracks', 'grid', gridProps({ columns: '1*, banana', rows: '1*' }), [child]);
    const autoCycle = node('auto-cycle', 'grid', gridProps({ columns: 'auto', rows: '1*' }), [child]);

    const invalidProjection = projectPrototypeLayout(canvas([invalidTracks]), [
      box('invalid-tracks', 0, 0, 100, 20),
      box('child', 7, 8, 9, 10),
    ]);
    const cycleProjection = projectPrototypeLayout(canvas([autoCycle]), [
      box('auto-cycle', 0, 0, 100, 20),
      box('child', 7, 8, 9, 10),
    ]);

    expect(invalidProjection.boxByNodeId.get('child')).toMatchObject({ x: 7, y: 8, w: 9, h: 10 });
    expect(invalidProjection.gridByContainerId.get('invalid-tracks')?.problems).toContain('列轨道“banana”无效');
    expect(cycleProjection.boxByNodeId.get('child')).toMatchObject({ x: 7, y: 8, w: 9, h: 10 });
    expect(cycleProjection.gridByContainerId.get('auto-cycle')?.problems).toContain('child 的列 FILL 跨越 AUTO 轨道');
  });
});
