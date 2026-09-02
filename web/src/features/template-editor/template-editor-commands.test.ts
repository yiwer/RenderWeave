import { isLosslessNumber, parse } from 'lossless-json';
import { describe, expect, it } from 'vitest';

import {
  createSessionFromBaseline,
  projectStructuredNodes,
  type StructuredEditorSession,
} from './template-editor-model';
import {
  executeTemplateEditorCommand,
  type TemplateEditorCommandIntent,
} from './template-editor-commands';
import {
  isCanonicalDirty,
  canonicalStringifyWorkingValue,
  redoStructuredCommand,
  undoStructuredCommand,
} from './template-editor-session';
import { templateNumberDraft } from './template-editor-numbers';
import { structuredBaseline } from './template-editor-test-support';

const FRAME_ID = '11111111-1111-4111-8111-111111111111';
const STACK_ID = '22222222-2222-4222-8222-222222222222';
const RECT_ID = '33333333-3333-4333-8333-333333333333';
const REPEAT_ID = '44444444-4444-4444-8444-444444444444';
const LOOP_ID = '55555555-5555-4555-8555-555555555555';
const BINDING_ID = '66666666-6666-4666-8666-666666666666';
const OUTSIDE_FRAME_ID = '77777777-7777-4777-8777-777777777777';
const LOOP_DEFINITION_ID = '88888888-8888-4888-8888-888888888888';
const DELETE_FRAME_ID = '99999999-9999-4999-8999-999999999999';
const QR_CODE_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

describe('Template Editor core command seam', () => {
  it('inserts Frame, Stack and Rect into an explicit parent with admitted defaults', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'insert', nodeKind: 'frame', parentNodeId: 'canvas-id', at: { xMm: 10, yMm: 12 },
    }, FRAME_ID);
    session = applied(session, {
      operation: 'insert', nodeKind: 'stack', parentNodeId: FRAME_ID,
    }, STACK_ID);
    session = applied(session, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: STACK_ID,
    }, RECT_ID);

    const nodes = projectStructuredNodes(session);
    expect(nodes.map((node) => [node.nodeId, node.depth])).toEqual([
      ['canvas-id', 0], ['frame-id', 1], ['rect-id', 2],
      [FRAME_ID, 1], [STACK_ID, 2], [RECT_ID, 3],
    ]);
    expect(node(session, FRAME_ID).placement).toEqual(expect.objectContaining({
      type: 'ABSOLUTE', xMm: 10, yMm: 12, widthMode: 'FIXED', heightMode: 'FIXED',
    }));
    expect(node(session, STACK_ID).placement).toEqual(expect.objectContaining({ type: 'ABSOLUTE' }));
    expect(node(session, RECT_ID).placement).toEqual(expect.objectContaining({ type: 'STACK' }));
    expect(node(session, FRAME_ID).padding).toEqual({ topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 });
    expect(node(session, STACK_ID).padding).toEqual({ topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 });
    expect(session.history.past).toHaveLength(3);
    expect(isCanonicalDirty(session)).toBe(true);
  });

  it('inserts every visual leaf through the canonical command seam and lowers Shape to Polygon', () => {
    const fontAssetId = 'aaaaaaaa-0000-4000-8000-000000000001';
    const imageAssetId = 'bbbbbbbb-0000-4000-8000-000000000002';
    const inputs: Array<Extract<TemplateEditorCommandIntent, { operation: 'insert' }>> = [
      { operation: 'insert', nodeKind: 'text', parentNodeId: 'canvas-id', assetId: fontAssetId },
      { operation: 'insert', nodeKind: 'image', parentNodeId: 'canvas-id', assetId: imageAssetId },
      { operation: 'insert', nodeKind: 'rect', parentNodeId: 'canvas-id' },
      { operation: 'insert', nodeKind: 'ellipse', parentNodeId: 'canvas-id' },
      { operation: 'insert', nodeKind: 'line', parentNodeId: 'canvas-id' },
      { operation: 'insert', nodeKind: 'polygon', parentNodeId: 'canvas-id' },
      { operation: 'insert', nodeKind: 'polyline', parentNodeId: 'canvas-id' },
      { operation: 'insert', nodeKind: 'path', parentNodeId: 'canvas-id' },
      { operation: 'insert', nodeKind: 'qrCode', parentNodeId: 'canvas-id' },
      { operation: 'insert', nodeKind: 'barcode', parentNodeId: 'canvas-id' },
      {
        operation: 'insert', nodeKind: 'polygon', parentNodeId: 'canvas-id',
        shapePreset: 'star', at: { xMm: 3, yMm: 4 },
      },
    ];
    let session = structuredSession();
    const ids = inputs.map((_, index) => (
      `a0000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`
    ));
    inputs.forEach((intent, index) => {
      session = applied(session, intent, ids[index]);
    });

    expect(ids.map((id) => node(session, id).kind)).toEqual([
      'text', 'image', 'rect', 'ellipse', 'line', 'polygon',
      'polyline', 'path', 'qrCode', 'barcode', 'polygon',
    ]);
    expect(node(session, ids[0]!).runs).toEqual([expect.objectContaining({
      fontRef: { assetId: fontAssetId },
    })]);
    expect(node(session, ids[1]!).imageRef).toEqual({ assetId: imageAssetId });
    expect(node(session, ids[7]!)).toHaveProperty('commands');
    expect(node(session, ids[7]!)).not.toHaveProperty('pathData');
    expect(node(session, ids[10]!).displayName).toMatch(/星形/);
    expect(node(session, ids[10]!).placement).toEqual(expect.objectContaining({
      type: 'ABSOLUTE', xMm: 3, yMm: 4,
    }));
  });

  it('rejects assetless Text/Image before changing canonical state', () => {
    const session = structuredSession();
    for (const nodeKind of ['text', 'image'] as const) {
      const result = executeTemplateEditorCommand(session, {
        operation: 'insert', nodeKind, parentNodeId: 'canvas-id',
      }, { createNodeId: () => 'a0000000-0000-4000-8000-000000000099' });
      expect(result).toEqual(expect.objectContaining({
        state: 'rejected', code: 'ASSET_REQUIRED', session,
      }));
    }
    expect(session.history.past).toHaveLength(0);
  });

  it('renames and updates core properties as reversible canonical commands', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'rename', nodeId: 'frame-id', displayName: '商品内容',
    });
    session = applied(session, {
      operation: 'set-property', nodeId: 'frame-id', property: 'fillColor', value: '#112233FF',
    });

    expect(node(session, 'frame-id')).toEqual(expect.objectContaining({
      displayName: '商品内容', fill: { color: '#112233FF' },
    }));
    const undone = undoStructuredCommand(session);
    expect(node(undone, 'frame-id').fill).toBeUndefined();
    expect(node(undone, 'frame-id').displayName).toBe('商品内容');
    const redone = redoStructuredCommand(undone);
    expect(node(redone, 'frame-id').fill).toEqual({ color: '#112233FF' });
  });

  it('deletes a subtree and restores its exact position through undo', () => {
    const initial = structuredSession();
    const result = executeTemplateEditorCommand(initial, {
      operation: 'delete', nodeId: 'frame-id',
    });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error(result.message);
    expect(projectStructuredNodes(result.session).map((item) => item.nodeId)).toEqual(['canvas-id']);
    expect(undoStructuredCommand(result.session).workingCopy.canonicalDesignDsl)
      .toBe(initial.workingCopy.canonicalDesignDsl);
  });

  it('rejects deleting a subtree when a surviving definition would retain a dangling loop ref', () => {
    const initial = repeatScopedRectSession({ wrappedForDelete: true });
    const result = executeTemplateEditorCommand(initial, {
      operation: 'delete', nodeId: DELETE_FRAME_ID,
    });

    expect(result).toEqual(expect.objectContaining({
      state: 'rejected',
      session: initial,
      code: 'STRUCTURE_REFERENCE_INVALID',
      pointer: '/definitions/0/domain/loopId',
    }));
    expect(result.message).toContain(LOOP_ID);
    expect(initial.history.past).toHaveLength(0);
    expect(initial.workingCopy.canonicalDesignDsl).toBe(initial.baseline.canonicalDesignDsl);
  });

  it('moves into a Stack with atomic placement conversion and restores the original parent', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'insert', nodeKind: 'stack', parentNodeId: 'canvas-id',
    }, STACK_ID);
    const beforeMove = session.workingCopy.canonicalDesignDsl;

    session = applied(session, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: STACK_ID, position: 'into',
    });

    expect(parentId(session, 'rect-id')).toBe(STACK_ID);
    expect(node(session, 'rect-id').placement).toEqual({
      type: 'STACK', widthMode: 'FIXED', widthMm: 100,
      heightMode: 'FIXED', heightMm: 100,
    });
    expect(undoStructuredCommand(session).workingCopy.canonicalDesignDsl).toBe(beforeMove);
  });

  it('preserves a legal TemplateUse invocation selector during an unrelated same-scope move', () => {
    const baseline = structuredBaseline();
    const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
    const canvas = findMutableNode(designDsl.designRoot, 'canvas-id');
    if (!canvas || !Array.isArray(canvas.children)) throw new Error('test fixture Canvas missing');
    canvas.children.push({
      nodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      kind: 'templateUse',
      displayName: '调用域嵌套模板',
      bindings: [],
      placement: {
        type: 'ABSOLUTE', xMm: 120, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      useId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      templateRef: { templateId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' },
      contextSelector: {
        kind: 'context',
        domain: { kind: 'invocation' },
        contextAbsentPolicy: 'ERROR',
      },
      fills: [],
    });
    baseline.designDsl = designDsl;
    baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
    const initial = createSessionFromBaseline(
      baseline,
      { state: 'checked', value: 'READY' },
    );
    if (initial.mode !== 'structured') throw new Error('expected Structured Editor');

    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
    });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error(result.message);
    expect(parentId(result.session, 'rect-id')).toBe('canvas-id');
  });

  it('rejects a structural result whose binding identities are not Template-unique', () => {
    const baseline = structuredBaseline();
    const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
    const frame = findMutableNode(designDsl.designRoot, 'frame-id');
    const rect = findMutableNode(designDsl.designRoot, 'rect-id');
    if (!frame || !rect) throw new Error('test fixture nodes missing');
    for (const candidate of [frame, rect]) {
      candidate.opacity = 1;
      candidate.bindings = [{
        bindingId: BINDING_ID,
        targetPropertyRef: { rootPropertyId: 'opacity', selectors: [] },
        source: { kind: 'context', domain: 'invocation', pointer: '/value' },
      }];
    }
    baseline.designDsl = designDsl;
    baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
    const initial = createSessionFromBaseline(
      baseline,
      { state: 'checked', value: 'READY' },
    );
    if (initial.mode !== 'structured') throw new Error('expected Structured Editor');

    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
    });

    expect(result).toEqual(expect.objectContaining({
      state: 'rejected',
      session: initial,
      code: 'STRUCTURE_IDENTITY_INVALID',
      pointer: '/designRoot/children/1/bindings/0/bindingId',
    }));
    expect(result.message).toContain(BINDING_ID);
    expect(initial.history.past).toHaveLength(0);
  });

  it('rejects a same-signature move when a surviving Binding has no reachable loop', () => {
    const baseline = structuredBaseline();
    const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
    const rect = findMutableNode(designDsl.designRoot, 'rect-id');
    if (!rect) throw new Error('test fixture rect missing');
    rect.opacity = 1;
    rect.bindings = [{
      bindingId: BINDING_ID,
      targetPropertyRef: { rootPropertyId: 'opacity', selectors: [] },
      source: { kind: 'loopIndex', loopId: LOOP_ID },
    }];
    baseline.designDsl = designDsl;
    baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
    const initial = createSessionFromBaseline(
      baseline,
      { state: 'checked', value: 'READY' },
    );
    if (initial.mode !== 'structured') throw new Error('expected Structured Editor');

    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
    });

    expect(result).toEqual(expect.objectContaining({
      state: 'rejected',
      session: initial,
      code: 'STRUCTURE_LEXICAL_SCOPE_INVALID',
      nodeId: 'rect-id',
      pointer: '/designRoot/children/1/bindings/0/source/loopId',
    }));
    expect(result.message).toContain(LOOP_ID);
    expect(initial.history.past).toHaveLength(0);
  });

  it('rejects moving a loop-bound node outside its reachable Repeat scope atomically', () => {
    const initial = repeatScopedRectSession();
    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree',
      nodeId: 'rect-id',
      targetNodeId: OUTSIDE_FRAME_ID,
      position: 'after',
    });

    expect(result).toEqual(expect.objectContaining({
      state: 'rejected',
      session: initial,
      code: 'STRUCTURE_LEXICAL_SCOPE_INVALID',
      nodeId: 'rect-id',
    }));
    expect(result.message).toContain('rect-id');
    expect(initial.history.past).toHaveLength(0);
    expect(initial.workingCopy.canonicalDesignDsl).toBe(initial.baseline.canonicalDesignDsl);
  });

  it('allows an unbound node to move into and out of a Repeat scope', () => {
    const initial = repeatScopedRectSession({ withLoopBinding: false });
    const movedOut = executeTemplateEditorCommand(initial, {
      operation: 'move-tree',
      nodeId: 'rect-id',
      targetNodeId: OUTSIDE_FRAME_ID,
      position: 'into',
    });

    expect(movedOut.state).toBe('applied');
    if (movedOut.state !== 'applied') throw new Error(movedOut.message);
    expect(parentId(movedOut.session, 'rect-id')).toBe(OUTSIDE_FRAME_ID);

    const movedBack = executeTemplateEditorCommand(movedOut.session, {
      operation: 'move-tree',
      nodeId: 'rect-id',
      targetNodeId: 'frame-id',
      position: 'into',
    });

    expect(movedBack.state).toBe('applied');
    if (movedBack.state !== 'applied') throw new Error(movedBack.message);
    expect(parentId(movedBack.session, 'rect-id')).toBe('frame-id');
  });

  it('moves a lossless-parser session and replays the exact authored numbers through undo/redo', () => {
    const initial = losslessStructuredSession();
    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
    });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error(result.message);
    const movedCanonical = result.session.workingCopy.canonicalDesignDsl;
    expect(parentId(result.session, 'rect-id')).toBe('canvas-id');
    expect(losslessToken(node(result.session, 'rect-id').placement, 'xMm')).toBe('0');

    const undone = undoStructuredCommand(result.session);
    expect(undone.workingCopy.canonicalDesignDsl).toBe(initial.workingCopy.canonicalDesignDsl);
    expect(redoStructuredCommand(undone).workingCopy.canonicalDesignDsl).toBe(movedCanonical);
  });

  it('preserves the complete ABSOLUTE placement and changes only at coordinates', () => {
    const initial = sessionWithRectPlacement({
      type: 'ABSOLUTE', xMm: 1.25, yMm: -2.5,
      widthMode: 'FILL', heightMode: 'FILL',
      minWidthMm: 2, minHeightMm: 3, maxWidthMm: 90, maxHeightMm: 91,
      rightInsetMm: 4, bottomInsetMm: 5,
    });
    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
      at: { xMm: 12.5, yMm: -3.25 },
    });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error(result.message);
    expect(authoredPlacement(result.session, 'rect-id')).toEqual({
      type: 'ABSOLUTE', xMm: '12.5', yMm: '-3.25',
      widthMode: 'FILL', heightMode: 'FILL',
      minWidthMm: '2', minHeightMm: '3', maxWidthMm: '90', maxHeightMm: '91',
      rightInsetMm: '4', bottomInsetMm: '5',
    });
  });

  it('preserves STACK margins, alignment and fill weight on same-variant sibling moves', () => {
    let session = sessionWithRectPlacement({
      type: 'STACK', widthMode: 'FILL', heightMode: 'FIXED', heightMm: 20,
      minWidthMm: 2, minHeightMm: 3, maxWidthMm: 90, maxHeightMm: 91,
      marginTopMm: -1, marginRightMm: 2, marginBottomMm: 3, marginLeftMm: 4,
      alignSelf: 'CENTER', fillWeight: 2,
    }, 'stack');
    session = applied(session, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: 'frame-id',
    }, RECT_ID);
    const beforeMove = session.workingCopy.canonicalDesignDsl;

    session = applied(session, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: RECT_ID, position: 'after',
    });
    const movedCanonical = session.workingCopy.canonicalDesignDsl;

    expect(authoredPlacement(session, 'rect-id')).toEqual({
      type: 'STACK', widthMode: 'FILL', heightMode: 'FIXED', heightMm: '20',
      minWidthMm: '2', minHeightMm: '3', maxWidthMm: '90', maxHeightMm: '91',
      marginTopMm: '-1', marginRightMm: '2', marginBottomMm: '3', marginLeftMm: '4',
      alignSelf: 'CENTER', fillWeight: '2',
    });
    const undone = undoStructuredCommand(session);
    expect(undone.workingCopy.canonicalDesignDsl).toBe(beforeMove);
    expect(redoStructuredCommand(undone).workingCopy.canonicalDesignDsl).toBe(movedCanonical);
  });

  it('projects ABSOLUTE to STACK using only common size and min/max fields', () => {
    let session = sessionWithRectPlacement({
      type: 'ABSOLUTE', xMm: 1, yMm: 2,
      widthMode: 'FILL', heightMode: 'FILL',
      minWidthMm: 3, minHeightMm: 4, maxWidthMm: 90, maxHeightMm: 91,
      rightInsetMm: 5, bottomInsetMm: 6,
    });
    session = applied(session, {
      operation: 'insert', nodeKind: 'stack', parentNodeId: 'canvas-id',
    }, STACK_ID);
    const beforeMove = session.workingCopy.canonicalDesignDsl;

    session = applied(session, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: STACK_ID, position: 'into',
    });
    const movedCanonical = session.workingCopy.canonicalDesignDsl;

    expect(authoredPlacement(session, 'rect-id')).toEqual({
      type: 'STACK', widthMode: 'FILL', heightMode: 'FILL',
      minWidthMm: '3', minHeightMm: '4', maxWidthMm: '90', maxHeightMm: '91',
    });
    const undone = undoStructuredCommand(session);
    expect(undone.workingCopy.canonicalDesignDsl).toBe(beforeMove);
    expect(redoStructuredCommand(undone).workingCopy.canonicalDesignDsl).toBe(movedCanonical);
  });

  it('projects STACK to ABSOLUTE using only common size and min/max fields', () => {
    const initial = sessionWithRectPlacement({
      type: 'STACK', widthMode: 'FILL', heightMode: 'FIXED', heightMm: 20,
      minWidthMm: 2, minHeightMm: 3, maxWidthMm: 90, maxHeightMm: 91,
      marginTopMm: -1, marginRightMm: 2, marginBottomMm: 3, marginLeftMm: 4,
      alignSelf: 'CENTER', fillWeight: 2,
    }, 'stack');
    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
      at: { xMm: 7.5, yMm: 8.5 },
    });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error(result.message);
    expect(authoredPlacement(result.session, 'rect-id')).toEqual({
      type: 'ABSOLUTE', xMm: '7.5', yMm: '8.5',
      widthMode: 'FILL', heightMode: 'FIXED', heightMm: '20',
      minWidthMm: '2', minHeightMm: '3', maxWidthMm: '90', maxHeightMm: '91',
    });
  });

  it('rejects a cross-variant move when the common size contract is invalid', () => {
    let session = sessionWithRectPlacement({
      type: 'ABSOLUTE', xMm: 1, yMm: 2,
      widthMode: 'FIXED', heightMode: 'FIXED', heightMm: 20,
    });
    session = applied(session, {
      operation: 'insert', nodeKind: 'stack', parentNodeId: 'canvas-id',
    }, STACK_ID);
    const beforeMove = session.workingCopy.canonicalDesignDsl;

    const result = executeTemplateEditorCommand(session, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: STACK_ID, position: 'into',
    });

    expect(result).toEqual(expect.objectContaining({
      state: 'rejected', session, code: 'PLACEMENT_CONVERSION_INVALID',
    }));
    expect(session.workingCopy.canonicalDesignDsl).toBe(beforeMove);
  });

  it('supports before/after sibling moves and all four children-order operations', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: 'canvas-id',
    }, RECT_ID);
    session = applied(session, {
      operation: 'insert', nodeKind: 'frame', parentNodeId: 'canvas-id',
    }, FRAME_ID);
    expect(childIds(session, 'canvas-id')).toEqual(['frame-id', RECT_ID, FRAME_ID]);

    session = applied(session, {
      operation: 'move-tree', nodeId: FRAME_ID, targetNodeId: RECT_ID, position: 'before',
    });
    expect(childIds(session, 'canvas-id')).toEqual(['frame-id', FRAME_ID, RECT_ID]);

    session = applied(session, { operation: 'reorder', nodeId: FRAME_ID, order: 'front' });
    expect(childIds(session, 'canvas-id')).toEqual(['frame-id', RECT_ID, FRAME_ID]);
    session = applied(session, { operation: 'reorder', nodeId: FRAME_ID, order: 'backward' });
    expect(childIds(session, 'canvas-id')).toEqual(['frame-id', FRAME_ID, RECT_ID]);
    session = applied(session, { operation: 'reorder', nodeId: FRAME_ID, order: 'back' });
    expect(childIds(session, 'canvas-id')).toEqual([FRAME_ID, 'frame-id', RECT_ID]);
    session = applied(session, { operation: 'reorder', nodeId: FRAME_ID, order: 'forward' });
    expect(childIds(session, 'canvas-id')).toEqual(['frame-id', FRAME_ID, RECT_ID]);
  });

  it('rejects a sibling reorder when the resulting parent ContentModel remains unprovable', () => {
    const baseline = structuredBaseline();
    const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
    const frame = findMutableNode(designDsl.designRoot, 'frame-id');
    const rect = findMutableNode(designDsl.designRoot, 'rect-id');
    if (!frame || !rect || !Array.isArray(frame.children)) {
      throw new Error('test fixture nodes missing');
    }
    rect.placement = {
      type: 'STACK', widthMode: 'FIXED', widthMm: 100,
      heightMode: 'FIXED', heightMm: 100,
    };
    frame.children.push({
      nodeId: RECT_ID,
      kind: 'rect',
      displayName: '第二矩形',
      bindings: [],
      placement: {
        type: 'ABSOLUTE', xMm: 10, yMm: 10,
        widthMode: 'FIXED', widthMm: 20,
        heightMode: 'FIXED', heightMm: 20,
      },
      fill: { color: '#112233FF' },
    });
    baseline.designDsl = designDsl;
    baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
    const initial = createSessionFromBaseline(
      baseline,
      { state: 'checked', value: 'READY' },
    );
    if (initial.mode !== 'structured') throw new Error('expected Structured Editor');

    const result = executeTemplateEditorCommand(initial, {
      operation: 'reorder', nodeId: 'rect-id', order: 'front',
    });

    expect(result).toEqual(expect.objectContaining({
      state: 'rejected',
      session: initial,
      code: 'STRUCTURE_CONTENT_MODEL_INVALID',
      nodeId: 'rect-id',
      pointer: '/designRoot/children/0/children/1/placement/type',
    }));
    expect(initial.history.past).toHaveLength(0);
  });

  it('commits one bounded move/resize command and rejects invalid geometry', () => {
    const initial = structuredSession();
    const moved = executeTemplateEditorCommand(initial, {
      operation: 'set-geometry', nodeId: 'rect-id',
      geometry: { xMm: 8, yMm: 9, widthMm: 42, heightMm: 24 },
    });
    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);
    expect(node(moved.session, 'rect-id').placement).toEqual(expect.objectContaining({
      xMm: 8, yMm: 9, widthMm: 42, heightMm: 24,
    }));
    expect(moved.session.history.past).toHaveLength(1);
    expect(undoStructuredCommand(moved.session).workingCopy.canonicalDesignDsl)
      .toBe(initial.workingCopy.canonicalDesignDsl);

    for (const geometry of [
      { xMm: 8, yMm: 9, widthMm: 0, heightMm: 24 },
      { xMm: Number.NaN, yMm: 9, widthMm: 42, heightMm: 24 },
    ]) {
      const rejected = executeTemplateEditorCommand(initial, {
        operation: 'set-geometry', nodeId: 'rect-id', geometry,
      });
      expect(rejected).toEqual(expect.objectContaining({ state: 'rejected', session: initial }));
    }
  });

  it('accepts only strict positive square geometry for QR codes', () => {
    let initial = structuredSession();
    initial = applied(initial, {
      operation: 'insert', nodeKind: 'qrCode', parentNodeId: 'canvas-id',
    }, QR_CODE_ID);

    const square = executeTemplateEditorCommand(initial, {
      operation: 'set-geometry', nodeId: QR_CODE_ID,
      geometry: { xMm: 8, yMm: 9, widthMm: 30, heightMm: 30 },
    });
    expect(square.state).toBe('applied');
    if (square.state !== 'applied') throw new Error(square.message);
    expect(node(square.session, QR_CODE_ID).placement).toEqual(expect.objectContaining({
      xMm: 8, yMm: 9, widthMm: 30, heightMm: 30,
    }));

    for (const geometry of [
      { xMm: 8, yMm: 9, widthMm: 30, heightMm: 20 },
      { xMm: 8, yMm: 9, widthMm: 30 },
    ]) {
      const rejected = executeTemplateEditorCommand(initial, {
        operation: 'set-geometry', nodeId: QR_CODE_ID, geometry,
      });
      expect(rejected).toEqual(expect.objectContaining({
        state: 'rejected',
        session: initial,
        code: 'GEOMETRY_INVALID',
        message: expect.stringContaining('正方形'),
      }));
      expect(initial.history.past).toHaveLength(1);
    }
  });

  it('does not round an unchanged lossless authored coordinate during geometry commit', () => {
    const placement = parse('{"type":"ABSOLUTE","xMm":0.123456789012345678,"yMm":0,"widthMode":"FIXED","widthMm":100,"heightMode":"FIXED","heightMm":100}') as Record<string, unknown>;
    const initial = sessionWithRectPlacement(placement);
    const result = executeTemplateEditorCommand(initial, {
      operation: 'set-geometry', nodeId: 'rect-id',
      geometry: { xMm: 0.12345678901234568, yMm: 0 },
    });

    expect(result).toEqual(expect.objectContaining({ state: 'no-op', session: initial }));
    expect(losslessToken(node(initial, 'rect-id').placement, 'xMm'))
      .toBe('0.123456789012345678');
  });

  it('rejects leaf targets, Canvas mutation and cycles without canonical or history changes', () => {
    const initial = structuredSession();
    const intents: TemplateEditorCommandIntent[] = [
      { operation: 'delete', nodeId: 'canvas-id' },
      { operation: 'rename', nodeId: 'canvas-id', displayName: '根' },
      { operation: 'move-tree', nodeId: 'frame-id', targetNodeId: 'rect-id', position: 'into' },
      { operation: 'move-tree', nodeId: 'frame-id', targetNodeId: 'rect-id', position: 'after' },
    ];
    for (const intent of intents) {
      const result = executeTemplateEditorCommand(initial, intent);
      expect(result).toEqual(expect.objectContaining({ state: 'rejected', session: initial }));
      expect(initial.history.past).toHaveLength(0);
      expect(initial.workingCopy.canonicalDesignDsl).toBe(initial.baseline.canonicalDesignDsl);
    }
  });
});

function structuredSession(): StructuredEditorSession {
  const session = createSessionFromBaseline(
    structuredBaseline(),
    { state: 'checked', value: 'READY' },
  );
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function losslessStructuredSession(): StructuredEditorSession {
  const baseline = structuredBaseline();
  baseline.designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function sessionWithRectPlacement(
  placement: Record<string, unknown>,
  parentKind: 'frame' | 'stack' = 'frame',
): StructuredEditorSession {
  const baseline = structuredBaseline();
  const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
  const frame = findMutableNode(designDsl.designRoot, 'frame-id');
  const rect = findMutableNode(designDsl.designRoot, 'rect-id');
  if (!frame || !rect) throw new Error('test fixture nodes missing');
  if (parentKind === 'stack') {
    frame.kind = 'stack';
    frame.direction = 'ROW';
    frame.gapMm = 0;
  }
  rect.placement = parse(canonicalStringifyWorkingValue(placement));
  baseline.designDsl = designDsl;
  baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function repeatScopedRectSession(
  options: { wrappedForDelete?: boolean; withLoopBinding?: boolean } = {},
): StructuredEditorSession {
  const baseline = structuredBaseline();
  const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
  const canvas = findMutableNode(designDsl.designRoot, 'canvas-id');
  const frame = findMutableNode(designDsl.designRoot, 'frame-id');
  const rect = findMutableNode(designDsl.designRoot, 'rect-id');
  if (!canvas || !frame || !rect) throw new Error('test fixture nodes missing');
  frame.placement = {
    type: 'PACK', widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
  };
  if (options.withLoopBinding !== false) {
    rect.opacity = 1;
    rect.bindings = [{
      bindingId: BINDING_ID,
      targetPropertyRef: { rootPropertyId: 'opacity', selectors: [] },
      source: { kind: 'loopIndex', loopId: LOOP_ID },
    }];
  }
  const repeat = {
    nodeId: REPEAT_ID,
    kind: 'repeat',
    displayName: '循环',
    bindings: [],
    placement: {
      type: 'ABSOLUTE', xMm: 0, yMm: 0,
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    },
    loopId: LOOP_ID,
    items: { kind: 'literal', valueType: { type: 'list', items: 'decimal' }, value: [1] },
    absentPolicy: 'EMPTY',
    itemLayout: { kind: 'STACK', direction: 'ROW' },
    instanceLayout: { kind: 'STACK', direction: 'ROW' },
    children: [frame],
  };
  const outsideFrame = {
    nodeId: OUTSIDE_FRAME_ID,
    kind: 'frame',
    displayName: '循环外框架',
    bindings: [],
    placement: {
      type: 'ABSOLUTE', xMm: 120, yMm: 0,
      widthMode: 'FIXED', widthMm: 40,
      heightMode: 'FIXED', heightMm: 40,
    },
    children: [],
  };
  if (options.wrappedForDelete) {
    canvas.children = [{
      nodeId: DELETE_FRAME_ID,
      kind: 'frame',
      displayName: '待删除框架',
      bindings: [],
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [repeat],
    }, outsideFrame];
    designDsl.definitions = [{
      definitionId: LOOP_DEFINITION_ID,
      kind: 'mapping',
      displayName: '循环序号',
      domain: { kind: 'loop', loopId: LOOP_ID },
      output: 'decimal',
      input: { kind: 'loopIndex', loopId: LOOP_ID },
      cases: [],
      otherwise: { kind: 'loopIndex', loopId: LOOP_ID },
    }];
  } else {
    canvas.children = [repeat, outsideFrame];
  }
  baseline.designDsl = designDsl;
  baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function applied(
  session: StructuredEditorSession,
  intent: TemplateEditorCommandIntent,
  nodeId?: string,
): StructuredEditorSession {
  const result = executeTemplateEditorCommand(
    session,
    intent,
    nodeId ? { createNodeId: () => nodeId } : undefined,
  );
  if (result.state !== 'applied') {
    throw new Error(result.state === 'rejected' ? result.message : 'expected an applied command');
  }
  return result.session;
}

function node(session: StructuredEditorSession, nodeId: string): Record<string, unknown> {
  const found = projectStructuredNodes(session).find((item) => item.nodeId === nodeId)?.value;
  if (!found) throw new Error(`node ${nodeId} not found`);
  return found;
}

function childIds(session: StructuredEditorSession, nodeId: string): string[] {
  const children = node(session, nodeId).children;
  return Array.isArray(children)
    ? children.map((child) => (child as Record<string, unknown>).nodeId as string)
    : [];
}

function parentId(session: StructuredEditorSession, nodeId: string): string | null {
  const nodes = projectStructuredNodes(session);
  const target = nodes.findIndex((item) => item.nodeId === nodeId);
  if (target < 0) return null;
  const depth = nodes[target]?.depth ?? 0;
  for (let index = target - 1; index >= 0; index -= 1) {
    if ((nodes[index]?.depth ?? -1) === depth - 1) return nodes[index]?.nodeId ?? null;
  }
  return null;
}

function losslessToken(value: unknown, member: string): string | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const candidate = (value as Record<string, unknown>)[member];
  return isLosslessNumber(candidate) ? candidate.toString() : null;
}

function findMutableNode(value: unknown, nodeId: string): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const record = value as Record<string, unknown>;
  if (record.nodeId === nodeId) return record;
  if (!Array.isArray(record.children)) return null;
  for (const child of record.children) {
    const found = findMutableNode(child, nodeId);
    if (found) return found;
  }
  return null;
}

function authoredPlacement(
  session: StructuredEditorSession,
  nodeId: string,
): Record<string, unknown> {
  const placement = node(session, nodeId).placement;
  if (!placement || typeof placement !== 'object' || Array.isArray(placement)) {
    throw new Error(`node ${nodeId} has no placement`);
  }
  return Object.fromEntries(Object.entries(placement).map(([key, value]) => {
    const draft = templateNumberDraft(value);
    return [key, draft === '' ? value : draft];
  }));
}
