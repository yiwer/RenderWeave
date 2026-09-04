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
import { projectTemplateDefiniteLayout } from './template-editor-definite-layout';
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
const GROUP_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const GRID_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const CONDITIONAL_ID = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const TEMPLATE_USE_ID = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
const USE_ID = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
const CONDITIONAL_CHILD_ID = '01234567-89ab-4cde-8fab-0123456789ab';
const TARGET_TEMPLATE_ID = '12345678-9abc-4def-8abc-123456789abc';
const PUBLIC_DEFINITION_ID = '23456789-abcd-4efa-8bcd-23456789abcd';

describe('Template Editor core command seam', () => {
  it('authors and reconfigures Repeat, Conditional and TemplateUse without persisting occurrences', () => {
    let session = structuredSession();
    const repeatIds = [REPEAT_ID, TEMPLATE_USE_ID];
    session = appliedWithOptions(session, {
      operation: 'insert',
      nodeKind: 'repeat',
      parentNodeId: 'canvas-id',
      structural: {
        kind: 'repeat',
        items: {
          kind: 'context', domain: 'invocation', pointer: '/labels',
        },
      },
    }, {
      createNodeId: () => repeatIds.shift()!,
      createLoopId: () => LOOP_ID,
    });

    expect(node(session, REPEAT_ID)).toEqual(expect.objectContaining({
      kind: 'repeat',
      loopId: LOOP_ID,
      absentPolicy: 'EMPTY',
      itemLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      placement: expect.objectContaining({ type: 'ABSOLUTE' }),
      children: [expect.objectContaining({
        nodeId: TEMPLATE_USE_ID,
        kind: 'rect', render: false,
        placement: expect.objectContaining({ type: 'PACK', widthMode: 'FIXED', heightMode: 'FIXED' }),
      })],
    }));
    expect(JSON.stringify(node(session, REPEAT_ID))).not.toContain('occurrence');

    const conditionalIds = [CONDITIONAL_ID, CONDITIONAL_CHILD_ID];
    session = appliedWithOptions(session, {
      operation: 'insert',
      nodeKind: 'conditional',
      parentNodeId: 'canvas-id',
      structural: {
        kind: 'conditional',
        condition: { kind: 'literal', valueType: 'boolean', value: true },
      },
    }, {
      createNodeId: () => conditionalIds.shift()!,
    });
    expect(node(session, CONDITIONAL_ID)).toEqual(expect.objectContaining({
      kind: 'conditional', absentPolicy: 'FALSE',
      condition: { kind: 'literal', valueType: 'boolean', value: true },
      children: [expect.objectContaining({ nodeId: CONDITIONAL_CHILD_ID, kind: 'rect', render: false })],
    }));

    session = appliedWithOptions(session, {
      operation: 'insert',
      nodeKind: 'templateUse',
      parentNodeId: REPEAT_ID,
      structural: {
        kind: 'templateUse',
        templateId: TARGET_TEMPLATE_ID,
        contextSelector: {
          kind: 'context',
          domain: { kind: 'loop', loopId: LOOP_ID },
          pointer: '',
          contextAbsentPolicy: 'ERROR',
        },
        fills: [{
          targetDefinitionId: PUBLIC_DEFINITION_ID,
          source: { kind: 'loopIndex', loopId: LOOP_ID },
        }],
      },
    }, {
      createNodeId: () => '3456789a-bcde-4fab-8cde-3456789abcde',
      createUseId: () => USE_ID,
    });
    expect(node(session, TEMPLATE_USE_ID)).toEqual(expect.objectContaining({
      useId: USE_ID,
      templateRef: { templateId: TARGET_TEMPLATE_ID },
      contextSelector: expect.objectContaining({ pointer: '' }),
      fills: [expect.objectContaining({ targetDefinitionId: PUBLIC_DEFINITION_ID })],
      placement: expect.objectContaining({ type: 'PACK' }),
    }));

    const authoredChildren = node(session, REPEAT_ID).children;
    session = applied(session, {
      operation: 'configure-structural',
      nodeId: REPEAT_ID,
      structural: {
        kind: 'repeat',
        items: { kind: 'definition', definitionId: LOOP_DEFINITION_ID },
        absentPolicy: 'ERROR',
        itemLayout: { kind: 'GRID', columns: 2, columnGapMm: 1, rowGapMm: 2 },
        instanceLayout: { kind: 'STACK', direction: 'ROW', gapMm: 3 },
      },
    });
    expect(node(session, REPEAT_ID)).toEqual(expect.objectContaining({
      items: { kind: 'definition', definitionId: LOOP_DEFINITION_ID },
      absentPolicy: 'ERROR',
      itemLayout: { kind: 'GRID', columns: 2, columnGapMm: 1, rowGapMm: 2 },
      instanceLayout: { kind: 'STACK', direction: 'ROW', gapMm: 3 },
      children: authoredChildren,
    }));
  });

  it('rejects structural insertion without loaded authoring facts or an explicit context pointer', () => {
    const session = structuredSession();
    const repeat = executeTemplateEditorCommand(session, {
      operation: 'insert', nodeKind: 'repeat', parentNodeId: 'canvas-id',
    });
    expect(repeat).toEqual(expect.objectContaining({
      state: 'rejected', code: 'STRUCTURAL_FACTS_REQUIRED', session,
    }));

    const missingPointer = executeTemplateEditorCommand(session, {
      operation: 'insert',
      nodeKind: 'templateUse',
      parentNodeId: 'canvas-id',
      structural: {
        kind: 'templateUse',
        templateId: TARGET_TEMPLATE_ID,
        contextSelector: {
          kind: 'context', domain: { kind: 'invocation' }, contextAbsentPolicy: 'ERROR',
        },
        fills: [],
      },
    });
    expect(missingPointer).toEqual(expect.objectContaining({
      state: 'rejected', code: 'STRUCTURAL_CONFIGURATION_INVALID', session,
    }));
  });
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

  it('inserts Group and Grid with formal container and child-placement defaults', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'insert', nodeKind: 'group', parentNodeId: 'canvas-id', at: { xMm: 4, yMm: 5 },
    }, GROUP_ID);
    session = applied(session, {
      operation: 'insert', nodeKind: 'grid', parentNodeId: GROUP_ID,
    }, GRID_ID);
    session = applied(session, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: GRID_ID,
    }, RECT_ID);

    expect(node(session, GROUP_ID).placement).toEqual({
      type: 'ABSOLUTE', xMm: 4, yMm: 5,
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    });
    expect(node(session, GROUP_ID)).not.toHaveProperty('padding');
    expect(node(session, GRID_ID).placement).toEqual(expect.objectContaining({ type: 'ABSOLUTE' }));
    expect(node(session, GRID_ID).padding).toEqual({ topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 });
    expect(node(session, GRID_ID).rows).toEqual([{ type: 'FRACTION', weight: 1 }]);
    expect(node(session, GRID_ID).columns).toEqual([{ type: 'FRACTION', weight: 1 }]);
    expect(node(session, RECT_ID).placement).toEqual(expect.objectContaining({
      type: 'GRID', row: 0, column: 0,
    }));
    expect(node(session, RECT_ID).placement).not.toEqual(expect.objectContaining({
      rowSpan: expect.anything(), columnSpan: expect.anything(),
    }));
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

  it('authors the closed Stack container and child layout property set', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'insert', nodeKind: 'stack', parentNodeId: 'canvas-id',
    }, STACK_ID);
    session = applied(session, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: STACK_ID,
    }, RECT_ID);
    for (const intent of [
      { property: 'paddingTopMm', value: 1 },
      { property: 'paddingRightMm', value: 2 },
      { property: 'paddingBottomMm', value: 3 },
      { property: 'paddingLeftMm', value: 4 },
      { property: 'direction', value: 'ROW' },
      { property: 'gapMm', value: 5 },
      { property: 'justifyContent', value: 'SPACE_BETWEEN' },
      { property: 'alignItems', value: 'CENTER' },
    ] as const) {
      session = applied(session, {
        operation: 'set-property', nodeId: STACK_ID, ...intent,
      });
    }
    for (const intent of [
      { property: 'widthMode', value: 'FILL' },
      { property: 'minWidthMm', value: 8 },
      { property: 'marginTopMm', value: -1 },
      { property: 'marginRightMm', value: 2 },
      { property: 'marginBottomMm', value: 3 },
      { property: 'marginLeftMm', value: 4 },
      { property: 'fillWeight', value: 2 },
      { property: 'alignSelf', value: 'END' },
    ] as const) {
      session = applied(session, {
        operation: 'set-property', nodeId: RECT_ID, ...intent,
      });
    }

    expect(node(session, STACK_ID)).toEqual(expect.objectContaining({
      padding: { topMm: 1, rightMm: 2, bottomMm: 3, leftMm: 4 },
      direction: 'ROW', gapMm: 5, justifyContent: 'SPACE_BETWEEN', alignItems: 'CENTER',
    }));
    expect(node(session, RECT_ID).placement).toEqual({
      type: 'STACK', widthMode: 'FILL', heightMode: 'FIXED', heightMm: 25.4,
      minWidthMm: 8,
      marginTopMm: -1, marginRightMm: 2, marginBottomMm: 3, marginLeftMm: 4,
      fillWeight: 2, alignSelf: 'END',
    });

    session = applied(session, {
      operation: 'set-property', nodeId: RECT_ID, property: 'marginTopMm', value: null,
    });
    expect((node(session, RECT_ID).placement as Record<string, unknown>).marginTopMm).toBeUndefined();
  });

  it('authors formal Grid tracks, gaps, cell, span, margins and alignment', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'insert', nodeKind: 'grid', parentNodeId: 'canvas-id',
    }, GRID_ID);
    session = applied(session, {
      operation: 'set-property', nodeId: GRID_ID, property: 'rows',
      value: [{ type: 'FIXED', valueMm: 20 }, { type: 'AUTO' }],
    });
    session = applied(session, {
      operation: 'set-property', nodeId: GRID_ID, property: 'columns',
      value: [{ type: 'FRACTION', weight: 1 }, { type: 'FRACTION', weight: 2 }],
    });
    session = applied(session, {
      operation: 'set-property', nodeId: GRID_ID, property: 'rowGapMm', value: 3,
    });
    session = applied(session, {
      operation: 'set-property', nodeId: GRID_ID, property: 'columnGapMm', value: 4,
    });
    session = applied(session, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: GRID_ID,
    }, RECT_ID);
    for (const intent of [
      { property: 'row', value: 1 },
      { property: 'columnSpan', value: 2 },
      { property: 'rowSpan', value: 1 },
      { property: 'marginLeftMm', value: -2 },
      { property: 'horizontalAlignSelf', value: 'CENTER' },
      { property: 'verticalAlignSelf', value: 'END' },
    ] as const) {
      session = applied(session, {
        operation: 'set-property', nodeId: RECT_ID, ...intent,
      });
    }

    expect(node(session, GRID_ID)).toEqual(expect.objectContaining({
      rows: [{ type: 'FIXED', valueMm: 20 }, { type: 'AUTO' }],
      columns: [{ type: 'FRACTION', weight: 1 }, { type: 'FRACTION', weight: 2 }],
      rowGapMm: 3, columnGapMm: 4,
    }));
    expect(node(session, RECT_ID).placement).toEqual(expect.objectContaining({
      type: 'GRID', row: 1, column: 0, rowSpan: 1, columnSpan: 2,
      marginLeftMm: -2, horizontalAlignSelf: 'CENTER', verticalAlignSelf: 'END',
    }));
  });

  it('rejects invalid layout-property combinations atomically', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'insert', nodeKind: 'group', parentNodeId: 'canvas-id',
    }, GROUP_ID);
    session = applied(session, {
      operation: 'insert', nodeKind: 'grid', parentNodeId: 'canvas-id',
    }, GRID_ID);
    session = applied(session, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: GRID_ID,
    }, RECT_ID);

    const invalidIntents: TemplateEditorCommandIntent[] = [
      { operation: 'set-property', nodeId: GROUP_ID, property: 'widthMm', value: 20 },
      { operation: 'set-property', nodeId: GRID_ID, property: 'paddingTopMm', value: -1 },
      { operation: 'set-property', nodeId: GRID_ID, property: 'rows', value: '12, auto' },
      {
        operation: 'set-property', nodeId: GRID_ID, property: 'rows',
        value: [{ type: 'AUTO', weight: 1 }],
      },
      { operation: 'set-property', nodeId: RECT_ID, property: 'row', value: 1 },
      { operation: 'set-property', nodeId: RECT_ID, property: 'horizontalAlignSelf', value: 'MIDDLE' },
    ];
    for (const intent of invalidIntents) {
      const result = executeTemplateEditorCommand(session, intent);
      expect(result).toEqual(expect.objectContaining({
        state: 'rejected', session, code: 'PROPERTY_INVALID',
      }));
    }
  });

  it('changes size modes and min/max as one legal placement command at a time', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'set-property', nodeId: 'frame-id', property: 'widthMm', value: 80,
    });
    session = applied(session, {
      operation: 'set-property', nodeId: 'frame-id', property: 'minWidthMm', value: 20,
    });
    session = applied(session, {
      operation: 'set-property', nodeId: 'frame-id', property: 'maxWidthMm', value: 100,
    });
    session = applied(session, {
      operation: 'set-property', nodeId: 'frame-id', property: 'heightMode', value: 'FILL',
    });
    expect(node(session, 'frame-id').placement).toEqual(expect.objectContaining({
      widthMode: 'FIXED', widthMm: 80, minWidthMm: 20, maxWidthMm: 100,
      heightMode: 'FILL',
    }));

    const invalidFixed = executeTemplateEditorCommand(session, {
      operation: 'set-property', nodeId: 'frame-id', property: 'widthMm', value: 101,
    });
    expect(invalidFixed).toEqual(expect.objectContaining({
      state: 'rejected', session, code: 'PROPERTY_INVALID',
    }));

    session = applied(session, {
      operation: 'set-property', nodeId: 'frame-id', property: 'minWidthMm', value: null,
    });
    expect((node(session, 'frame-id').placement as Record<string, unknown>).minWidthMm)
      .toBeUndefined();
  });

  it('rejects Stack/Grid aggregate conflicts introduced by a single property edit', () => {
    let stackSession = structuredSession();
    stackSession = applied(stackSession, {
      operation: 'insert', nodeKind: 'stack', parentNodeId: 'canvas-id',
    }, STACK_ID);
    stackSession = applied(stackSession, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: STACK_ID,
    }, RECT_ID);
    stackSession = applied(stackSession, {
      operation: 'set-property', nodeId: STACK_ID, property: 'direction', value: 'ROW',
    });
    stackSession = applied(stackSession, {
      operation: 'set-property', nodeId: RECT_ID, property: 'widthMode', value: 'FILL',
    });
    stackSession = applied(stackSession, {
      operation: 'set-property', nodeId: RECT_ID, property: 'fillWeight', value: 2,
    });
    const directionConflict = executeTemplateEditorCommand(stackSession, {
      operation: 'set-property', nodeId: STACK_ID, property: 'direction', value: 'COLUMN',
    });
    expect(directionConflict).toEqual(expect.objectContaining({
      state: 'rejected', session: stackSession, code: 'PROPERTY_INVALID',
    }));

    let gridSession = structuredSession();
    gridSession = applied(gridSession, {
      operation: 'insert', nodeKind: 'grid', parentNodeId: 'canvas-id',
    }, GRID_ID);
    gridSession = applied(gridSession, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: GRID_ID,
    }, RECT_ID);
    gridSession = applied(gridSession, {
      operation: 'set-property', nodeId: RECT_ID, property: 'horizontalAlignSelf', value: 'CENTER',
    });
    const alignConflict = executeTemplateEditorCommand(gridSession, {
      operation: 'set-property', nodeId: RECT_ID, property: 'widthMode', value: 'FILL',
    });
    expect(alignConflict).toEqual(expect.objectContaining({
      state: 'rejected', session: gridSession, code: 'PROPERTY_INVALID',
    }));
    const hugFractionConflict = executeTemplateEditorCommand(gridSession, {
      operation: 'set-property', nodeId: GRID_ID, property: 'widthMode', value: 'HUG_CONTENT',
    });
    expect(hugFractionConflict).toEqual(expect.objectContaining({
      state: 'rejected', session: gridSession, code: 'PROPERTY_INVALID',
    }));
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
      projectedGeometry: { xMm: 0, yMm: 0, widthMm: 100, heightMm: 100 },
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
      projectedGeometry: { xMm: 0, yMm: 0, widthMm: 100, heightMm: 100 },
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
      projectedGeometry: { xMm: 0, yMm: 0, widthMm: 100, heightMm: 100 },
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
      projectedGeometry: { xMm: 0, yMm: 0, widthMm: 100, heightMm: 100 },
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
      projectedGeometry: { xMm: 0, yMm: 0, widthMm: 100, heightMm: 100 },
    });

    expect(movedOut.state).toBe('applied');
    if (movedOut.state !== 'applied') throw new Error(movedOut.message);
    expect(parentId(movedOut.session, 'rect-id')).toBe(OUTSIDE_FRAME_ID);

    const movedBack = executeTemplateEditorCommand(movedOut.session, {
      operation: 'move-tree',
      nodeId: 'rect-id',
      targetNodeId: 'frame-id',
      position: 'into',
      projectedGeometry: { xMm: 0, yMm: 0, widthMm: 100, heightMm: 100 },
    });

    expect(movedBack.state).toBe('applied');
    if (movedBack.state !== 'applied') throw new Error(movedBack.message);
    expect(parentId(movedBack.session, 'rect-id')).toBe('frame-id');
  });

  it('moves a lossless-parser session and replays the exact authored numbers through undo/redo', () => {
    const initial = losslessStructuredSession();
    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
      projectedGeometry: { xMm: 0, yMm: 0, widthMm: 100, heightMm: 100 },
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

  it('rejects cross-parent ABSOLUTE moves without projected local geometry', () => {
    const initial = structuredSession();
    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
    });

    expect(result).toEqual(expect.objectContaining({
      state: 'rejected', session: initial, code: 'PLACEMENT_CONVERSION_INVALID',
    }));
    expect(initial.history.past).toHaveLength(0);
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

  it('uses projected local coordinates when reparenting between ABSOLUTE parents', () => {
    let session = sessionWithRectPlacement({
      type: 'ABSOLUTE', xMm: 1, yMm: 2,
      widthMode: 'FIXED', widthMm: 30,
      heightMode: 'FIXED', heightMm: 20,
    });
    session = applied(session, {
      operation: 'insert', nodeKind: 'frame', parentNodeId: 'canvas-id',
    }, OUTSIDE_FRAME_ID);

    session = applied(session, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: OUTSIDE_FRAME_ID, position: 'into',
      projectedGeometry: { xMm: 7, yMm: 8, widthMm: 30, heightMm: 20 },
    });

    expect(parentId(session, 'rect-id')).toBe(OUTSIDE_FRAME_ID);
    expect(authoredPlacement(session, 'rect-id')).toEqual({
      type: 'ABSOLUTE', xMm: '7', yMm: '8',
      widthMode: 'FIXED', widthMm: '30', heightMode: 'FIXED', heightMm: '20',
    });
  });

  it('keeps both ABSOLUTE Group branches world-stable in one reversible reparent step', () => {
    const initial = reparentGroupSession();
    const beforeCanonical = initial.workingCopy.canonicalDesignDsl;
    const beforeMoving = worldRect(initial, 'moving-child');
    const beforeSourceSibling = worldRect(initial, 'source-sibling');
    const beforeDestinationSibling = worldRect(initial, 'destination-sibling');
    const destinationContent = worldContentRect(initial, 'destination-group');

    const moved = executeTemplateEditorCommand(initial, {
      operation: 'move-tree',
      nodeId: 'moving-child',
      targetNodeId: 'destination-group',
      position: 'into',
      projectedGeometry: {
        xMm: beforeMoving.x - destinationContent.x,
        yMm: beforeMoving.y - destinationContent.y,
        widthMm: beforeMoving.width,
        heightMm: beforeMoving.height,
      },
    });

    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);
    expect(parentId(moved.session, 'moving-child')).toBe('destination-group');
    expect(childIds(moved.session, 'source-group')).toEqual(['source-sibling']);
    expect(childIds(moved.session, 'destination-group')).toEqual([
      'destination-sibling', 'moving-child',
    ]);
    expect(worldRect(moved.session, 'moving-child')).toEqual(beforeMoving);
    expect(worldRect(moved.session, 'source-sibling')).toEqual(beforeSourceSibling);
    expect(worldRect(moved.session, 'destination-sibling')).toEqual(beforeDestinationSibling);
    expect(authoredPlacement(moved.session, 'source-group')).toEqual(expect.objectContaining({
      xMm: '70', yMm: '50',
    }));
    expect(authoredPlacement(moved.session, 'destination-group')).toEqual(expect.objectContaining({
      xMm: '50', yMm: '40',
    }));
    expect(moved.session.history.past).toHaveLength(1);
    expect(moved.session.history.past[0]?.kind).toBe('move-node');
    expect(JSON.stringify(moved.session.history.past[0])).not.toContain('"children"');

    const movedCanonical = moved.session.workingCopy.canonicalDesignDsl;
    const undone = undoStructuredCommand(moved.session);
    expect(undone.workingCopy.canonicalDesignDsl).toBe(beforeCanonical);
    expect(redoStructuredCommand(undone).workingCopy.canonicalDesignDsl).toBe(movedCanonical);
  });

  it.each(['source', 'destination'] as const)(
    'rejects reparent when the %s managed Group would require union-min compensation',
    (managedBranch) => {
      const initial = reparentGroupSession(managedBranch);
      const beforeMoving = worldRect(initial, 'moving-child');
      const destinationContent = worldContentRect(initial, 'destination-group');

      const moved = executeTemplateEditorCommand(initial, {
        operation: 'move-tree',
        nodeId: 'moving-child',
        targetNodeId: 'destination-group',
        position: 'into',
        projectedGeometry: {
          xMm: beforeMoving.x - destinationContent.x,
          yMm: beforeMoving.y - destinationContent.y,
          widthMm: beforeMoving.width,
          heightMm: beforeMoving.height,
        },
      });

      expect(moved).toEqual(expect.objectContaining({
        state: 'rejected',
        session: initial,
        code: 'PLACEMENT_CONVERSION_INVALID',
        message: expect.stringContaining('managed layout'),
      }));
      expect(initial.history.past).toHaveLength(0);
      expect(initial.workingCopy.canonicalDesignDsl).toBe(initial.baseline.canonicalDesignDsl);
    },
  );

  it('compensates a shared Group ancestor once after both HUG Frame branches settle', () => {
    const initial = crossFrameReparentSession();
    const beforeMoving = worldRect(initial, 'moving-child');
    const beforeSourceSibling = worldRect(initial, 'source-sibling');
    const beforeDestinationSibling = worldRect(initial, 'destination-sibling');
    const beforeOuterSibling = worldRect(initial, 'outer-sibling');
    const destinationContent = worldContentRect(initial, 'destination-group');

    const moved = executeTemplateEditorCommand(initial, {
      operation: 'move-tree',
      nodeId: 'moving-child',
      targetNodeId: 'destination-group',
      position: 'into',
      projectedGeometry: {
        xMm: beforeMoving.x - destinationContent.x,
        yMm: beforeMoving.y - destinationContent.y,
        widthMm: beforeMoving.width,
        heightMm: beforeMoving.height,
      },
    });

    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);
    expect(worldRect(moved.session, 'moving-child')).toEqual(beforeMoving);
    expect(worldRect(moved.session, 'source-sibling')).toEqual(beforeSourceSibling);
    expect(worldRect(moved.session, 'destination-sibling')).toEqual(beforeDestinationSibling);
    expect(worldRect(moved.session, 'outer-sibling')).toEqual(beforeOuterSibling);
    expect(authoredPlacement(moved.session, 'outer-group')).toEqual(expect.objectContaining({
      xMm: '160',
    }));
    const historyCommand = moved.session.history.past[0];
    expect(historyCommand?.kind).toBe('move-node');
    if (historyCommand?.kind !== 'move-node') throw new Error('expected move history');
    expect(historyCommand.groupCompensations?.map((item) => item.nodeId)).toEqual([
      'source-group', 'destination-group', 'outer-group',
    ]);
  });

  it('keeps an emptied source Group placement while compensating an empty destination Group', () => {
    const initial = emptyEdgeReparentSession();
    const beforeMoving = worldRect(initial, 'moving-child');
    const destinationContent = worldContentRect(initial, 'destination-group');

    const moved = executeTemplateEditorCommand(initial, {
      operation: 'move-tree',
      nodeId: 'moving-child',
      targetNodeId: 'destination-group',
      position: 'into',
      projectedGeometry: {
        xMm: beforeMoving.x - destinationContent.x,
        yMm: beforeMoving.y - destinationContent.y,
        widthMm: beforeMoving.width,
        heightMm: beforeMoving.height,
      },
    });

    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);
    expect(worldRect(moved.session, 'moving-child')).toEqual(beforeMoving);
    expect(authoredPlacement(moved.session, 'source-group')).toEqual(expect.objectContaining({
      xMm: '50', yMm: '40',
    }));
    expect(authoredPlacement(moved.session, 'destination-group')).toEqual(expect.objectContaining({
      xMm: '50', yMm: '40',
    }));
    const historyCommand = moved.session.history.past[0];
    expect(historyCommand?.kind).toBe('move-node');
    if (historyCommand?.kind !== 'move-node') throw new Error('expected move history');
    expect(historyCommand.groupCompensations?.map((item) => item.nodeId)).toEqual([
      'destination-group',
    ]);
  });

  it('preserves exact ABSOLUTE placement on same-parent reorder despite projection feedback', () => {
    const precise = parse('{"type":"ABSOLUTE","xMm":0.123456789012345678,"yMm":2,"widthMode":"FIXED","widthMm":30,"heightMode":"FIXED","heightMm":20}') as Record<string, unknown>;
    let session = sessionWithRectPlacement(precise);
    session = applied(session, {
      operation: 'insert', nodeKind: 'rect', parentNodeId: 'frame-id',
    }, RECT_ID);

    session = applied(session, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: RECT_ID, position: 'after',
      projectedGeometry: { xMm: 99, yMm: 98, widthMm: 30, heightMm: 20 },
    });

    expect(losslessToken(node(session, 'rect-id').placement, 'xMm'))
      .toBe('0.123456789012345678');
    expect(authoredPlacement(session, 'rect-id')).toEqual({
      type: 'ABSOLUTE', xMm: '0.123456789012345678', yMm: '2',
      widthMode: 'FIXED', widthMm: '30', heightMode: 'FIXED', heightMm: '20',
    });
  });

  it('keeps Group HUG_CONTENT while using projected local position out of managed layout', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'insert', nodeKind: 'stack', parentNodeId: 'canvas-id',
    }, STACK_ID);
    session = applied(session, {
      operation: 'insert', nodeKind: 'group', parentNodeId: STACK_ID,
    }, GROUP_ID);

    session = applied(session, {
      operation: 'move-tree', nodeId: GROUP_ID, targetNodeId: 'frame-id', position: 'into',
      projectedGeometry: { xMm: 6, yMm: 7, widthMm: 0, heightMm: 0 },
    });

    expect(authoredPlacement(session, GROUP_ID)).toEqual({
      type: 'ABSOLUTE', xMm: '6', yMm: '7',
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
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

  it('projects managed placement to fixed ABSOLUTE using caller-supplied local geometry', () => {
    const initial = sessionWithRectPlacement({
      type: 'STACK', widthMode: 'FILL', heightMode: 'FIXED', heightMm: 20,
      minWidthMm: 2, minHeightMm: 3, maxWidthMm: 90, maxHeightMm: 91,
      marginTopMm: -1, marginRightMm: 2, marginBottomMm: 3, marginLeftMm: 4,
      alignSelf: 'CENTER', fillWeight: 2,
    }, 'stack');
    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
      projectedGeometry: { xMm: 7.5, yMm: 8.5, widthMm: 30, heightMm: 20 },
    });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error(result.message);
    expect(authoredPlacement(result.session, 'rect-id')).toEqual({
      type: 'ABSOLUTE', xMm: '7.5', yMm: '8.5',
      widthMode: 'FIXED', widthMm: '30', heightMode: 'FIXED', heightMm: '20',
      minWidthMm: '2', minHeightMm: '3', maxWidthMm: '90', maxHeightMm: '91',
    });
  });

  it('rejects managed-to-ABSOLUTE reparent without projected local geometry', () => {
    const initial = sessionWithRectPlacement({
      type: 'STACK', widthMode: 'FIXED', widthMm: 20,
      heightMode: 'FIXED', heightMm: 10,
    }, 'stack');

    const result = executeTemplateEditorCommand(initial, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: 'frame-id', position: 'after',
      at: { xMm: 7.5, yMm: 8.5 },
    });

    expect(result).toEqual(expect.objectContaining({
      state: 'rejected', session: initial, code: 'PLACEMENT_CONVERSION_INVALID',
    }));
    expect(initial.history.past).toHaveLength(0);
  });

  it('converts STACK to GRID with required cell defaults and no foreign variant fields', () => {
    let session = sessionWithRectPlacement({
      type: 'STACK', widthMode: 'FIXED', widthMm: 20,
      heightMode: 'FIXED', heightMm: 10, marginTopMm: -1, alignSelf: 'END',
    }, 'stack');
    session = applied(session, {
      operation: 'insert', nodeKind: 'grid', parentNodeId: 'canvas-id',
    }, GRID_ID);

    session = applied(session, {
      operation: 'move-tree', nodeId: 'rect-id', targetNodeId: GRID_ID, position: 'into',
    });

    expect(authoredPlacement(session, 'rect-id')).toEqual({
      type: 'GRID', widthMode: 'FIXED', widthMm: '20',
      heightMode: 'FIXED', heightMm: '10', row: '0', column: '0',
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
    expect(moved.session.history.past[0]?.kind).toBe('replace-node-shell');
    expect(undoStructuredCommand(moved.session).workingCopy.canonicalDesignDsl)
      .toBe(initial.workingCopy.canonicalDesignDsl);

    for (const geometry of [
      {},
      { xMm: 8, yMm: 9, widthMm: 0, heightMm: 24 },
      { xMm: Number.NaN, yMm: 9, widthMm: 42, heightMm: 24 },
    ]) {
      const rejected = executeTemplateEditorCommand(initial, {
        operation: 'set-geometry', nodeId: 'rect-id', geometry,
      });
      expect(rejected).toEqual(expect.objectContaining({ state: 'rejected', session: initial }));
    }
  });

  it('moves a single child in an ABSOLUTE Group by the requested world delta', () => {
    const initial = groupedRectSession([
      absoluteRect('group-child', 10, 7),
    ], {
      type: 'ABSOLUTE', xMm: 50, yMm: 40,
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    });
    expect(worldRect(initial, 'group-child')).toEqual({ x: 50, y: 40, width: 20, height: 10 });

    const moved = executeTemplateEditorCommand(initial, {
      operation: 'set-geometry', nodeId: 'group-child', geometry: { xMm: 15 },
    });

    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);
    expect(authoredPlacement(moved.session, GROUP_ID)).toEqual({
      type: 'ABSOLUTE', xMm: '55', yMm: '40',
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    });
    expect(authoredPlacement(moved.session, 'group-child')).toEqual(expect.objectContaining({
      xMm: '15', yMm: '7',
    }));
    expect(worldRect(moved.session, 'group-child')).toEqual({ x: 55, y: 40, width: 20, height: 10 });
    expect(moved.session.history.past).toHaveLength(1);
    expect(moved.session.history.past[0]?.kind).toBe('replace-node-shells');
  });

  it('keeps Group siblings world-stable and replays the compact compensation exactly', () => {
    const initial = groupedRectSession([
      absoluteRect('minimum-child', 10, 7),
      absoluteRect('stable-sibling', 30, 20),
    ], {
      type: 'ABSOLUTE', xMm: 50, yMm: 40,
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    });
    const beforeCanonical = initial.workingCopy.canonicalDesignDsl;
    expect(worldRect(initial, 'stable-sibling')).toEqual({ x: 70, y: 53, width: 20, height: 10 });

    const moved = executeTemplateEditorCommand(initial, {
      operation: 'set-geometry', nodeId: 'minimum-child', geometry: { xMm: 15 },
    });

    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);
    expect(worldRect(moved.session, 'minimum-child')).toEqual({ x: 55, y: 40, width: 20, height: 10 });
    expect(worldRect(moved.session, 'stable-sibling')).toEqual({ x: 70, y: 53, width: 20, height: 10 });
    const movedCanonical = moved.session.workingCopy.canonicalDesignDsl;
    const command = moved.session.history.past[0];
    expect(command?.kind).toBe('replace-node-shells');
    if (command?.kind !== 'replace-node-shells') throw new Error('expected compact multi-shell history');
    expect(command.replacements.map((item) => item.nodeId)).toEqual(['minimum-child', GROUP_ID]);
    expect(command.replacements.every((item) => (
      !Object.hasOwn(item.before, 'children') && !Object.hasOwn(item.after, 'children')
    ))).toBe(true);

    const undone = undoStructuredCommand(moved.session);
    expect(undone.workingCopy.canonicalDesignDsl).toBe(beforeCanonical);
    expect(redoStructuredCommand(undone).workingCopy.canonicalDesignDsl).toBe(movedCanonical);
  });

  it('propagates union-min compensation through nested ABSOLUTE Groups', () => {
    const innerGroupId = 'nested-group';
    const initial = groupedRectSession([
      absoluteGroup(innerGroupId, 10, 8, [
        absoluteRect('nested-target', 5, 2),
        absoluteRect('nested-sibling', 20, 12),
      ]),
      absoluteRect('outer-sibling', 40, 30),
    ], {
      type: 'ABSOLUTE', xMm: 100, yMm: 80,
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    });
    expect(worldRect(initial, 'nested-target').x).toBe(100);
    expect(worldRect(initial, 'nested-sibling').x).toBe(115);
    expect(worldRect(initial, 'outer-sibling').x).toBe(130);

    const moved = executeTemplateEditorCommand(initial, {
      operation: 'set-geometry', nodeId: 'nested-target', geometry: { xMm: 10 },
    });

    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);
    expect(authoredPlacement(moved.session, innerGroupId)).toEqual(expect.objectContaining({ xMm: '15' }));
    expect(authoredPlacement(moved.session, GROUP_ID)).toEqual(expect.objectContaining({ xMm: '105' }));
    expect(worldRect(moved.session, 'nested-target').x).toBe(105);
    expect(worldRect(moved.session, 'nested-sibling').x).toBe(115);
    expect(worldRect(moved.session, 'outer-sibling').x).toBe(130);
    const command = moved.session.history.past[0];
    expect(command?.kind === 'replace-node-shells'
      ? command.replacements.map((item) => item.nodeId)
      : []).toEqual(['nested-target', innerGroupId, GROUP_ID]);
  });

  it('keeps the existing single-shell command when a Group union minimum does not change', () => {
    const initial = groupedRectSession([
      absoluteRect('minimum-sibling', 0, 0),
      absoluteRect('nonminimum-target', 10, 10),
    ], {
      type: 'ABSOLUTE', xMm: 50, yMm: 40,
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    });

    const moved = executeTemplateEditorCommand(initial, {
      operation: 'set-geometry', nodeId: 'nonminimum-target', geometry: { xMm: 15 },
    });

    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);
    expect(authoredPlacement(moved.session, GROUP_ID)).toEqual({
      type: 'ABSOLUTE', xMm: '50', yMm: '40',
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    });
    expect(worldRect(initial, 'nonminimum-target').x).toBe(60);
    expect(worldRect(moved.session, 'nonminimum-target').x).toBe(65);
    expect(moved.session.history.past[0]?.kind).toBe('replace-node-shell');
  });

  it.each(['STACK', 'GRID', 'PACK'] as const)(
    'rejects union-min movement when the owning Group uses managed %s placement',
    (placementType) => {
      const initial = managedGroupedRectSession(placementType);
      const beforeCanonical = initial.workingCopy.canonicalDesignDsl;

      const moved = executeTemplateEditorCommand(initial, {
        operation: 'set-geometry', nodeId: 'managed-target', geometry: { xMm: 5 },
      });

      expect(moved).toEqual(expect.objectContaining({
        state: 'rejected', session: initial, code: 'GEOMETRY_INVALID',
        message: expect.stringContaining('managed layout'),
      }));
      expect(initial.workingCopy.canonicalDesignDsl).toBe(beforeCanonical);
      expect(initial.history.past).toHaveLength(0);
    },
  );

  it('restores managed moves and persists only FIXED-axis managed resize', () => {
    const initial = sessionWithRectPlacement({
      type: 'STACK', widthMode: 'FIXED', widthMm: 20,
      heightMode: 'FIXED', heightMm: 10,
    }, 'stack');

    const moved = executeTemplateEditorCommand(initial, {
      operation: 'set-geometry', nodeId: 'rect-id', geometry: { xMm: 90, yMm: 91 },
    });
    expect(moved).toEqual(expect.objectContaining({ state: 'no-op', session: initial }));

    const resized = executeTemplateEditorCommand(initial, {
      operation: 'set-geometry', nodeId: 'rect-id', geometry: { widthMm: 25 },
    });
    expect(resized.state).toBe('applied');
    if (resized.state !== 'applied') throw new Error(resized.message);
    expect(authoredPlacement(resized.session, 'rect-id')).toEqual({
      type: 'STACK', widthMode: 'FIXED', widthMm: '25',
      heightMode: 'FIXED', heightMm: '10',
    });
    expect(resized.session.history.past).toHaveLength(1);
    expect(resized.session.history.past[0]?.kind).toBe('replace-node-shell');
  });

  it('rejects resize for a managed non-FIXED axis and accepts partial ABSOLUTE movement', () => {
    const managed = sessionWithRectPlacement({
      type: 'GRID', widthMode: 'FILL', heightMode: 'FIXED', heightMm: 10,
      row: 0, column: 0,
    }, 'grid');
    const rejected = executeTemplateEditorCommand(managed, {
      operation: 'set-geometry', nodeId: 'rect-id', geometry: { widthMm: 25 },
    });
    expect(rejected).toEqual(expect.objectContaining({
      state: 'rejected', session: managed, code: 'GEOMETRY_INVALID',
    }));

    const absolute = structuredSession();
    const moved = executeTemplateEditorCommand(absolute, {
      operation: 'set-geometry', nodeId: 'rect-id', geometry: { xMm: 8 },
    });
    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);
    expect(node(moved.session, 'rect-id').placement).toEqual(expect.objectContaining({
      xMm: 8, yMm: 0,
    }));

    const bounded = sessionWithRectPlacement({
      type: 'ABSOLUTE', xMm: 0, yMm: 0,
      widthMode: 'FIXED', widthMm: 20, maxWidthMm: 25,
      heightMode: 'FIXED', heightMm: 10,
    });
    const beyondMaximum = executeTemplateEditorCommand(bounded, {
      operation: 'set-geometry', nodeId: 'rect-id', geometry: { widthMm: 30 },
    });
    expect(beyondMaximum).toEqual(expect.objectContaining({
      state: 'rejected', session: bounded, code: 'GEOMETRY_INVALID',
    }));
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

  it('allows QR FILL axes and does not require an authored square until both axes are FIXED', () => {
    let session = structuredSession();
    session = applied(session, {
      operation: 'insert', nodeKind: 'qrCode', parentNodeId: 'canvas-id',
    }, QR_CODE_ID);

    const widthFill = executeTemplateEditorCommand(session, {
      operation: 'set-property', nodeId: QR_CODE_ID, property: 'widthMode', value: 'FILL',
    });
    expect(widthFill.state).toBe('applied');
    if (widthFill.state !== 'applied') throw new Error(widthFill.message);
    expect(authoredPlacement(widthFill.session, QR_CODE_ID)).toEqual(expect.objectContaining({
      widthMode: 'FILL', heightMode: 'FIXED', heightMm: '25',
    }));

    const moved = executeTemplateEditorCommand(widthFill.session, {
      operation: 'set-geometry', nodeId: QR_CODE_ID, geometry: { xMm: 8, yMm: 9 },
    });
    expect(moved.state).toBe('applied');
    if (moved.state !== 'applied') throw new Error(moved.message);

    const bothFill = executeTemplateEditorCommand(moved.session, {
      operation: 'set-property', nodeId: QR_CODE_ID, property: 'heightMode', value: 'FILL',
    });
    expect(bothFill.state).toBe('applied');
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
  parentKind: 'frame' | 'stack' | 'grid' = 'frame',
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
  } else if (parentKind === 'grid') {
    frame.kind = 'grid';
    frame.rows = [{ type: 'FRACTION', weight: 1 }];
    frame.columns = [{ type: 'FRACTION', weight: 1 }];
    frame.rowGapMm = 0;
    frame.columnGapMm = 0;
  }
  rect.placement = parse(canonicalStringifyWorkingValue(placement));
  baseline.designDsl = designDsl;
  baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function groupedRectSession(
  children: Record<string, unknown>[],
  groupPlacement: Record<string, unknown>,
): StructuredEditorSession {
  const baseline = structuredBaseline();
  const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
  const canvas = findMutableNode(designDsl.designRoot, 'canvas-id');
  if (!canvas) throw new Error('test fixture Canvas missing');
  canvas.children = [{
    nodeId: GROUP_ID,
    kind: 'group',
    displayName: '自由分组',
    bindings: [],
    placement: groupPlacement,
    children,
  }];
  baseline.designDsl = designDsl;
  baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function managedGroupedRectSession(
  placementType: 'STACK' | 'GRID' | 'PACK',
): StructuredEditorSession {
  const baseline = structuredBaseline();
  const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
  const canvas = findMutableNode(designDsl.designRoot, 'canvas-id');
  if (!canvas) throw new Error('test fixture Canvas missing');
  const managedPlacement = placementType === 'GRID'
    ? {
      type: 'GRID', row: 0, column: 0,
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    }
    : { type: placementType, widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT' };
  const group = {
    nodeId: GROUP_ID,
    kind: 'group',
    displayName: '受布局管理的分组',
    bindings: [],
    placement: managedPlacement,
    children: [
      absoluteRect('managed-target', 0, 0),
      absoluteRect('managed-sibling', 10, 0),
    ],
  };
  const parentPlacement = {
    type: 'ABSOLUTE', xMm: 10, yMm: 10,
    widthMode: 'FIXED', widthMm: 100,
    heightMode: 'FIXED', heightMm: 100,
  };
  canvas.children = placementType === 'STACK'
    ? [{
      nodeId: 'managed-parent', kind: 'stack', displayName: '堆叠', bindings: [],
      placement: parentPlacement,
      padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
      direction: 'ROW', gapMm: 0, children: [group],
    }]
    : placementType === 'GRID'
      ? [{
        nodeId: 'managed-parent', kind: 'grid', displayName: '网格', bindings: [],
        placement: parentPlacement,
        padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
        rows: [{ type: 'FIXED', valueMm: 100 }],
        columns: [{ type: 'FIXED', valueMm: 100 }],
        rowGapMm: 0, columnGapMm: 0, children: [group],
      }]
      : [{
        nodeId: REPEAT_ID, kind: 'repeat', displayName: '循环', bindings: [],
        placement: parentPlacement,
        loopId: LOOP_ID,
        items: { kind: 'literal', valueType: { type: 'list', items: 'decimal' }, value: [1] },
        absentPolicy: 'EMPTY',
        itemLayout: { kind: 'STACK', direction: 'ROW' },
        instanceLayout: { kind: 'STACK', direction: 'ROW' },
        children: [group],
      }];
  baseline.designDsl = designDsl;
  baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function reparentGroupSession(
  managedBranch?: 'source' | 'destination',
): StructuredEditorSession {
  const baseline = structuredBaseline();
  const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
  const canvas = findMutableNode(designDsl.designRoot, 'canvas-id');
  if (!canvas) throw new Error('test fixture Canvas missing');
  const sourceGroup = absoluteGroup('source-group', 50, 40, [
    absoluteRect('moving-child', -10, -5),
    absoluteRect('source-sibling', 10, 5),
  ]);
  const destinationGroup = absoluteGroup('destination-group', 150, 90, [
    absoluteRect('destination-sibling', 20, 10),
  ]);
  const asManagedStackChild = (group: Record<string, unknown>) => ({
    ...group,
    placement: {
      type: 'STACK', widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    },
  });
  const managedParent = (nodeId: string, xMm: number, yMm: number, group: Record<string, unknown>) => ({
    nodeId,
    kind: 'stack',
    displayName: nodeId,
    bindings: [],
    placement: {
      type: 'ABSOLUTE', xMm, yMm,
      widthMode: 'FIXED', widthMm: 100,
      heightMode: 'FIXED', heightMm: 80,
    },
    direction: 'ROW',
    gapMm: 0,
    children: [asManagedStackChild(group)],
  });
  canvas.children = [
    managedBranch === 'source'
      ? managedParent('source-stack', 50, 40, sourceGroup)
      : sourceGroup,
    managedBranch === 'destination'
      ? managedParent('destination-stack', 150, 90, destinationGroup)
      : destinationGroup,
  ];
  baseline.designDsl = designDsl;
  baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function crossFrameReparentSession(): StructuredEditorSession {
  const baseline = structuredBaseline();
  const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
  const canvas = findMutableNode(designDsl.designRoot, 'canvas-id');
  if (!canvas) throw new Error('test fixture Canvas missing');
  const sourceGroup = absoluteGroup('source-group', 0, 0, [
    absoluteRectSize('moving-child', -10, 0, 100, 10),
    absoluteRect('source-sibling', 10, 0),
  ]);
  const destinationGroup = absoluteGroup('destination-group', 0, 0, [
    absoluteRect('destination-sibling', 20, 0),
  ]);
  const hugFrame = (
    nodeId: string,
    xMm: number,
    child: Record<string, unknown>,
    transform?: Record<string, unknown>,
  ) => ({
    nodeId,
    kind: 'frame',
    displayName: nodeId,
    bindings: [],
    placement: {
      type: 'ABSOLUTE', xMm, yMm: 0,
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    },
    ...(transform === undefined ? {} : { transform }),
    children: [child],
  });
  canvas.children = [absoluteGroup('outer-group', 100, 80, [
    hugFrame('source-frame', 10, sourceGroup, {
      rotationDeg: 0, scaleX: -1, scaleY: 1, originX: 0, originY: 0,
    }),
    hugFrame('destination-frame', 200, destinationGroup),
    absoluteRect('outer-sibling', 120, 0),
  ])];
  baseline.designDsl = designDsl;
  baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function emptyEdgeReparentSession(): StructuredEditorSession {
  const baseline = structuredBaseline();
  const designDsl = parse(baseline.canonicalDesignDsl) as Record<string, unknown>;
  const canvas = findMutableNode(designDsl.designRoot, 'canvas-id');
  if (!canvas) throw new Error('test fixture Canvas missing');
  canvas.children = [
    absoluteGroup('source-group', 50, 40, [absoluteRect('moving-child', 10, 5)]),
    absoluteGroup('destination-group', 150, 90, []),
  ];
  baseline.designDsl = designDsl;
  baseline.canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function absoluteRect(nodeId: string, xMm: number, yMm: number): Record<string, unknown> {
  return absoluteRectSize(nodeId, xMm, yMm, 20, 10);
}

function absoluteRectSize(
  nodeId: string,
  xMm: number,
  yMm: number,
  widthMm: number,
  heightMm: number,
): Record<string, unknown> {
  return {
    nodeId,
    kind: 'rect',
    displayName: nodeId,
    bindings: [],
    placement: {
      type: 'ABSOLUTE', xMm, yMm,
      widthMode: 'FIXED', widthMm,
      heightMode: 'FIXED', heightMm,
    },
  };
}

function absoluteGroup(
  nodeId: string,
  xMm: number,
  yMm: number,
  children: Record<string, unknown>[],
): Record<string, unknown> {
  return {
    nodeId,
    kind: 'group',
    displayName: nodeId,
    bindings: [],
    placement: {
      type: 'ABSOLUTE', xMm, yMm,
      widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
    },
    children,
  };
}

function worldRect(
  session: StructuredEditorSession,
  nodeId: string,
): { x: number; y: number; width: number; height: number } {
  const layout = projectTemplateDefiniteLayout(session.workingCopy.designDsl.designRoot);
  if (layout.state !== 'ready') throw new Error(`expected ready layout: ${JSON.stringify(layout.problems)}`);
  const rect = layout.entries.find((entry) => entry.nodeId === nodeId)?.worldRect;
  if (!rect) throw new Error(`layout entry ${nodeId} missing`);
  return { ...rect };
}

function worldContentRect(
  session: StructuredEditorSession,
  nodeId: string,
): { x: number; y: number; width: number; height: number } {
  const layout = projectTemplateDefiniteLayout(session.workingCopy.designDsl.designRoot);
  if (layout.state !== 'ready') throw new Error(`expected ready layout: ${JSON.stringify(layout.problems)}`);
  const rect = layout.entries.find((entry) => entry.nodeId === nodeId)?.worldContentRect;
  if (!rect) throw new Error(`layout content entry ${nodeId} missing`);
  return { ...rect };
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

function appliedWithOptions(
  session: StructuredEditorSession,
  intent: TemplateEditorCommandIntent,
  options: Parameters<typeof executeTemplateEditorCommand>[2],
): StructuredEditorSession {
  const result = executeTemplateEditorCommand(session, intent, options);
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
