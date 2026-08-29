import { describe, expect, it } from 'vitest';

import type { EditorNodeProjection } from './template-editor-model';
import {
  buildTemplateTreeBranchColors,
  buildTemplateTreeGuidePieces,
  buildTemplateTreeRows,
  projectVisibleTemplateTreeRows,
  templateTreeKeyAction,
  templateTreeWindow,
} from './template-structure-tree';

describe('Template structure tree projection', () => {
  const nodes = [
    node('canvas', 'canvas', '画布', 0, 1),
    node('frame', 'frame', '内容区', 1, 2),
    node('price', 'text', '价格', 2, 0),
    node('badge', 'rect', '折扣角标', 2, 0),
  ];

  it('derives stable parent and descendant relationships from the authored preorder', () => {
    const rows = buildTemplateTreeRows(nodes);

    expect(rows.map(({ nodeId, parentNodeId, descendantCount }) => ({
      nodeId,
      parentNodeId,
      descendantCount,
    }))).toEqual([
      { nodeId: 'canvas', parentNodeId: null, descendantCount: 3 },
      { nodeId: 'frame', parentNodeId: 'canvas', descendantCount: 2 },
      { nodeId: 'price', parentNodeId: 'frame', descendantCount: 0 },
      { nodeId: 'badge', parentNodeId: 'frame', descendantCount: 0 },
    ]);
  });

  it('collapses descendants while search keeps each match and its ancestor chain', () => {
    const rows = buildTemplateTreeRows(nodes);

    expect(projectVisibleTemplateTreeRows(rows, new Set(['frame']), ''))
      .toEqual(rows.slice(0, 2));
    expect(projectVisibleTemplateTreeRows(rows, new Set(['frame']), '价格')
      .map((row) => row.nodeId))
      .toEqual(['canvas', 'frame', 'price']);
  });

  it('maps right/left keys to expand, first-child, collapse and parent navigation', () => {
    const rows = buildTemplateTreeRows(nodes);
    const collapsed = new Set<string>(['frame']);

    expect(templateTreeKeyAction(rows, collapsed, 'frame', 'ArrowRight'))
      .toEqual({ kind: 'toggle', nodeId: 'frame' });
    expect(templateTreeKeyAction(rows, new Set(), 'frame', 'ArrowRight'))
      .toEqual({ kind: 'focus', nodeId: 'price' });
    expect(templateTreeKeyAction(rows, new Set(), 'frame', 'ArrowLeft'))
      .toEqual({ kind: 'toggle', nodeId: 'frame' });
    expect(templateTreeKeyAction(rows, new Set(), 'price', 'ArrowLeft'))
      .toEqual({ kind: 'focus', nodeId: 'frame' });
  });

  it('keeps the rendered row window proportional to the viewport', () => {
    expect(templateTreeWindow({
      rowCount: 4096,
      scrollTop: 2000,
      viewportHeight: 440,
      rowHeight: 44,
      overscan: 6,
    })).toEqual({ start: 39, end: 61, totalHeight: 180224 });
  });

  it('inherits one stable color through each top-level mind-map branch', () => {
    const rows = buildTemplateTreeRows([
      ...nodes,
      node('sidebar', 'frame', '侧栏', 1, 1),
      node('note', 'text', '说明', 2, 0),
    ]).slice(1);
    const colors = buildTemplateTreeBranchColors(rows);

    expect(colors.get('price')).toBe(colors.get('frame'));
    expect(colors.get('badge')).toBe(colors.get('frame'));
    expect(colors.get('note')).toBe(colors.get('sidebar'));
    expect(colors.get('frame')).not.toBe(colors.get('sidebar'));
  });

  it('projects center-aligned taps and parent trunks that stop at the last direct child', () => {
    const rows = buildTemplateTreeRows(nodes).slice(1);
    const pieces = buildTemplateTreeGuidePieces(rows, 'canvas');

    for (const [index] of rows.entries()) {
      const taps = pieces[index]?.filter((piece) => piece.axis === 'horizontal');
      expect(taps).toHaveLength(1);
      expect(taps?.[0]?.top).toBe(index * 44 + 21);
      expect(taps?.[0]?.width).toBeGreaterThan(0);
    }
    const frameTrunk = pieces.flat()
      .filter((piece) => piece.axis === 'vertical' && piece.ownerNodeId === 'frame')
      .sort((left, right) => left.top - right.top);
    expect(frameTrunk[0]?.top).toBe(27);
    expect(frameTrunk.at(-1)!.top + frameTrunk.at(-1)!.height).toBe(110);
    expect(frameTrunk.every((piece) => piece.width > 0 && piece.height > 0)).toBe(true);
  });
});

function node(
  nodeId: string,
  kind: string,
  displayName: string,
  depth: number,
  childCount: number,
): EditorNodeProjection {
  return {
    nodeId,
    kind,
    displayName,
    depth,
    childCount,
    value: { nodeId, kind, displayName },
  };
}
