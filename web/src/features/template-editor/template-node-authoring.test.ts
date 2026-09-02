import { parse } from 'lossless-json';
import { describe, expect, it } from 'vitest';

import {
  createSessionFromBaseline,
  projectStructuredNodes,
  type CanonicalTemplateBaseline,
  type StructuredEditorSession,
} from './template-editor-model';
import { insertTemplateNode } from './template-node-authoring';
import {
  isCanonicalDirty,
  redoStructuredCommand,
  undoStructuredCommand,
} from './template-editor-session';
import { structuredBaseline } from './template-editor-test-support';

const NEW_NODE_ID = '11111111-1111-4111-8111-111111111111';

describe('formal Template node authoring seam', () => {
  it('inserts an admitted Rect into the nearest selected container and recommends its selection', () => {
    const initial = structuredSession();

    const result = insertTemplateNode(
      initial,
      { kind: 'rect', selectedNodeId: 'rect-id' },
      () => NEW_NODE_ID,
    );

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error(result.message);
    expect(result.nodeId).toBe(NEW_NODE_ID);
    expect(result.parentNodeId).toBe('frame-id');
    expect(result.session.baseline).toBe(initial.baseline);
    expect(result.session.previewGeneration).toBe(1);
    expect(result.session.history.past).toHaveLength(1);
    expect(result.session.history.future).toHaveLength(0);
    expect(isCanonicalDirty(result.session)).toBe(true);

    const frame = projectStructuredNodes(result.session)
      .find((node) => node.nodeId === 'frame-id')?.value;
    const inserted = (frame?.children as Record<string, unknown>[] | undefined)?.at(-1);
    expect(inserted).toEqual({
      nodeId: NEW_NODE_ID,
      kind: 'rect',
      displayName: '矩形 2',
      bindings: [],
      placement: {
        type: 'ABSOLUTE',
        xMm: 25.4,
        yMm: 25.4,
        widthMode: 'FIXED',
        widthMm: 25.4,
        heightMode: 'FIXED',
        heightMm: 25.4,
      },
      fill: { color: '#2563EBFF' },
    });
    expect(result.session.workingCopy.canonicalDesignDsl).toContain(
      `"nodeId":"${NEW_NODE_ID}"`,
    );
    expect(Object.isFrozen(inserted)).toBe(true);
  });

  it.each([
    ['canvas', 'ABSOLUTE', { xMm: 25.4, yMm: 25.4 }],
    ['group', 'ABSOLUTE', { xMm: 25.4, yMm: 25.4 }],
    ['frame', 'ABSOLUTE', { xMm: 25.4, yMm: 25.4 }],
    ['conditional', 'ABSOLUTE', { xMm: 25.4, yMm: 25.4 }],
    ['stack', 'STACK', {}],
    ['grid', 'GRID', { row: 0, column: 0 }],
    ['repeat', 'PACK', {}],
  ] as const)('derives %s parent placement from the ContentModel', (kind, type, detail) => {
    const initial = sessionWithSelectedParent(kind);

    const result = insertTemplateNode(
      initial,
      { kind: 'rect', selectedNodeId: 'parent-id' },
      () => NEW_NODE_ID,
    );

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error(result.message);
    expect(result.parentNodeId).toBe('parent-id');
    const inserted = projectStructuredNodes(result.session)
      .find((node) => node.nodeId === NEW_NODE_ID)?.value;
    expect(inserted?.placement).toEqual(expect.objectContaining({
      type,
      widthMode: 'FIXED',
      widthMm: 25.4,
      heightMode: 'FIXED',
      heightMm: 25.4,
      ...detail,
    }));
  });

  it('rejects invalid or duplicate client identities without changing the session', () => {
    const initial = structuredSession();

    for (const nodeId of ['NOT-A-UUID', 'canvas-id']) {
      const result = insertTemplateNode(
        initial,
        { kind: 'rect', selectedNodeId: 'canvas-id' },
        () => nodeId,
      );
      expect(result).toEqual(expect.objectContaining({ state: 'rejected', session: initial }));
      expect(initial.history.past).toHaveLength(0);
      expect(isCanonicalDirty(initial)).toBe(false);
    }
  });

  it('round-trips the exact canonical node through the shared undo/redo history', () => {
    const initial = structuredSession();
    const result = insertTemplateNode(
      initial,
      { kind: 'rect', selectedNodeId: 'canvas-id' },
      () => NEW_NODE_ID,
    );
    if (result.state !== 'applied') throw new Error(result.message);

    const undone = undoStructuredCommand(result.session);
    expect(undone.workingCopy.canonicalDesignDsl).toBe(initial.baseline.canonicalDesignDsl);
    expect(projectStructuredNodes(undone).some((node) => node.nodeId === NEW_NODE_ID)).toBe(false);
    expect(undone.previewGeneration).toBe(2);

    const redone = redoStructuredCommand(undone);
    expect(redone.workingCopy.canonicalDesignDsl)
      .toBe(result.session.workingCopy.canonicalDesignDsl);
    expect(projectStructuredNodes(redone).some((node) => node.nodeId === NEW_NODE_ID)).toBe(true);
    expect(redone.previewGeneration).toBe(3);
  });
});

function structuredSession(
  baseline: CanonicalTemplateBaseline = structuredBaseline(),
): StructuredEditorSession {
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function sessionWithSelectedParent(kind: string): StructuredEditorSession {
  const baseline = structuredBaseline();
  const parent = {
    nodeId: 'parent-id',
    kind,
    bindings: [],
    placement: {
      type: 'ABSOLUTE', xMm: 0, yMm: 0,
      widthMode: 'FIXED', widthMm: 100,
      heightMode: 'FIXED', heightMm: 100,
    },
    children: [],
    ...(kind === 'grid' ? { rows: [{ type: 'AUTO' }], columns: [{ type: 'AUTO' }] } : {}),
    ...(kind === 'conditional' ? {
      condition: { kind: 'literal', valueType: 'boolean', value: true },
      absentPolicy: 'FALSE',
    } : {}),
    ...(kind === 'repeat' ? {
      loopId: '40000000-0000-4000-8000-000000000001',
      items: { kind: 'context', domain: 'invocation', pointer: '/items' },
      absentPolicy: 'ERROR',
      itemLayout: { kind: 'STACK', direction: 'ROW' },
      instanceLayout: { kind: 'STACK', direction: 'ROW' },
    } : {}),
  };
  const designDsl = {
    ...baseline.designDsl,
    designRoot: {
      nodeId: kind === 'canvas' ? 'parent-id' : 'canvas-id',
      kind: 'canvas',
      widthMm: 210,
      heightMm: 297,
      bindings: [],
      children: kind === 'canvas' ? [] : [parent],
      ...(kind === 'canvas' ? { displayName: '画布' } : {}),
    },
  };
  const canonicalDesignDsl = JSON.stringify(designDsl);
  return structuredSession({
    ...baseline,
    canonicalDesignDsl,
    designDsl: parse(canonicalDesignDsl) as Record<string, unknown>,
  });
}
