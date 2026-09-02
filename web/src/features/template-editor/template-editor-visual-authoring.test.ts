import { describe, expect, it } from 'vitest';

import { inspectDesignDslWire } from './template-design-dsl-wire';
import {
  buildTemplateShapePresetNode,
  buildTemplateVisualNode,
  updateTemplateVisualNodeProperty,
  type BuildTemplateVisualNodeInput,
  type TemplateVisualLeafKind,
  type TemplateVisualPropertyChange,
} from './template-editor-visual-authoring';

const NODE_ID = '11111111-1111-4111-8111-111111111111';
const FONT_ASSET_ID = '22222222-2222-4222-8222-222222222222';
const IMAGE_ASSET_ID = '33333333-3333-4333-8333-333333333333';
const REPLACEMENT_FONT_ID = '44444444-4444-4444-8444-444444444444';

describe('Template visual authoring seam', () => {
  it.each([
    ['text', ['runs', 'writingMode', 'horizontalAlign', 'verticalAlign', 'lineBreak',
      'overflow', 'lineHeight', 'padding', 'fitMode']],
    ['image', ['imageRef', 'fit', 'sampling']],
    ['rect', ['fill']],
    ['ellipse', ['fill']],
    ['line', ['start', 'end', 'stroke']],
    ['polygon', ['points', 'fill']],
    ['polyline', ['points', 'stroke']],
    ['path', ['commands', 'fill', 'fillRule']],
    ['qrCode', ['content', 'errorCorrectionLevel', 'foregroundColor', 'backgroundColor']],
    ['barcode', ['format', 'value', 'foregroundColor', 'backgroundColor']],
  ] as const)('builds an exact supported %s default wire', (kind, specificMembers) => {
    const result = buildTemplateVisualNode(buildInput(kind));

    expect(result.state).toBe('built');
    if (result.state !== 'built') throw new Error(result.message);
    expect(result.node).toEqual(expect.objectContaining({
      nodeId: NODE_ID,
      kind,
      bindings: [],
      placement: expect.objectContaining({
        type: 'ABSOLUTE',
        xMm: 3,
        yMm: 4,
        widthMode: 'FIXED',
        heightMode: 'FIXED',
      }),
    }));
    for (const member of specificMembers) expect(result.node).toHaveProperty(member);
    expect(Object.keys(result.node)).toEqual(expect.arrayContaining([
      'nodeId', 'kind', 'displayName', 'bindings', 'placement', ...specificMembers,
    ]));
    expect(inspectDesignDslWire(wrapInDesignDsl(result.node))).toEqual({ status: 'supported' });
  });

  it('persists exact AssetRef objects for Text and Image and requires explicit Assets', () => {
    const text = builtNode(buildInput('text'));
    const image = builtNode(buildInput('image'));

    expect((text.runs as Record<string, unknown>[])[0]?.fontRef)
      .toEqual({ assetId: FONT_ASSET_ID });
    expect(Object.keys((text.runs as Record<string, unknown>[])[0]?.fontRef as object))
      .toEqual(['assetId']);
    expect(image.imageRef).toEqual({ assetId: IMAGE_ASSET_ID });
    expect(Object.keys(image.imageRef as object)).toEqual(['assetId']);
    expect(JSON.stringify([text, image])).not.toMatch(/fontFamily|blob:|https?:\/\//);

    expect(buildTemplateVisualNode({
      kind: 'text', nodeId: NODE_ID, ordinal: 1,
    } as BuildTemplateVisualNodeInput)).toEqual(expect.objectContaining({
      state: 'rejected', code: 'ASSET_ID_REQUIRED',
    }));
    expect(buildTemplateVisualNode({
      kind: 'image', nodeId: NODE_ID, ordinal: 1, imageAssetId: 'not-an-asset',
    })).toEqual(expect.objectContaining({ state: 'rejected', code: 'ASSET_ID_INVALID' }));
  });

  it('uses formal commands[] for Path and never introduces prototype pathData', () => {
    const path = builtNode(buildInput('path'));

    expect(path.commands).toEqual([
      { type: 'MOVE_TO', xMm: 0, yMm: 24 },
      {
        type: 'CUBIC_TO', c1xMm: 8, c1yMm: 0,
        c2xMm: 24, c2yMm: 0, xMm: 32, yMm: 24,
      },
      { type: 'CLOSE' },
    ]);
    expect(path).not.toHaveProperty('pathData');
  });

  it.each(['diamond', 'triangle', 'star', 'arrow'] as const)(
    'lowers the %s Shape preset to an admitted Polygon',
    (preset) => {
      const result = buildTemplateShapePresetNode({
        preset, nodeId: NODE_ID, ordinal: 3, at: { xMm: 7, yMm: 8 },
      });

      expect(result.state).toBe('built');
      if (result.state !== 'built') throw new Error(result.message);
      expect(result.node.kind).toBe('polygon');
      expect(result.node).not.toHaveProperty('preset');
      expect(result.node).not.toHaveProperty('shape');
      expect((result.node as Record<string, unknown>).points).toEqual(expect.any(Array));
      expect(inspectDesignDslWire(wrapInDesignDsl(result.node))).toEqual({ status: 'supported' });
    },
  );

  it('updates one Text Run losslessly while preserving bindings and unknown fields', () => {
    const bindings = [{ bindingId: 'binding-kept' }];
    const node: Record<string, unknown> = {
      nodeId: NODE_ID,
      kind: 'text',
      displayName: '保真文本',
      bindings,
      placement: {
        type: 'ABSOLUTE', xMm: 0, yMm: 0,
        widthMode: 'FIXED', widthMm: 60,
        heightMode: 'FIXED', heightMm: 20,
      },
      opacity: 0.75,
      futureNodeMember: { retained: true },
      runs: [{
        text: '旧值',
        fontRef: { assetId: FONT_ASSET_ID },
        fontSizePt: 18,
        color: '#112233FF',
        decoration: 'UNDERLINE',
        letterSpacingFactor: 0.1,
        futureRunMember: 'retained',
      }],
    };

    const textResult = updateTemplateVisualNodeProperty(node, {
      property: 'text', value: '新值',
    });
    expect(textResult.state).toBe('updated');
    if (textResult.state !== 'updated') throw new Error(textResult.message);
    expect(textResult.node).toEqual({
      ...node,
      runs: [{
        ...(node.runs as Record<string, unknown>[])[0],
        text: '新值',
      }],
    });
    expect(textResult.node.bindings).toBe(bindings);
    expect(node).toHaveProperty('runs.0.text', '旧值');

    const fontResult = updateTemplateVisualNodeProperty(textResult.node, {
      property: 'fontRef', assetId: REPLACEMENT_FONT_ID,
    });
    expect(fontResult.state).toBe('updated');
    if (fontResult.state !== 'updated') throw new Error(fontResult.message);
    expect(fontResult.node).toEqual(expect.objectContaining({
      futureNodeMember: { retained: true },
      bindings,
      runs: [expect.objectContaining({
        text: '新值',
        fontRef: { assetId: REPLACEMENT_FONT_ID },
        fontSizePt: 18,
        futureRunMember: 'retained',
      })],
    }));
    expect(Object.keys(
      ((fontResult.node.runs as Record<string, unknown>[])[0]?.fontRef as object),
    )).toEqual(['assetId']);
  });

  it('atomically refuses to flatten or mutate multi-Run Text', () => {
    const node: Record<string, unknown> = {
      kind: 'text',
      bindings: [{ bindingId: 'kept' }],
      runs: [
        {
          text: '一', fontRef: { assetId: FONT_ASSET_ID }, fontSizePt: 12,
          color: '#000000FF', decoration: 'NONE', letterSpacingPt: 0,
        },
        {
          text: '二', fontRef: { assetId: REPLACEMENT_FONT_ID }, fontSizePt: 14,
          color: '#FFFFFFFF', decoration: 'LINE_THROUGH', letterSpacingPt: 1,
        },
      ],
    };
    const before = JSON.stringify(node);

    for (const change of [
      { property: 'text', value: '扁平值' },
      { property: 'fontRef', assetId: REPLACEMENT_FONT_ID },
    ] as const) {
      const result = updateTemplateVisualNodeProperty(node, change);
      expect(result).toEqual(expect.objectContaining({
        state: 'rejected', code: 'TEXT_MULTI_RUN_UNSUPPORTED', node,
      }));
      expect(result.node).toBe(node);
      expect(JSON.stringify(node)).toBe(before);
    }
  });

  it('switches the Text letter-spacing union without losing the rest of the Run', () => {
    const node = builtNode(buildInput('text'));
    const result = updateTemplateVisualNodeProperty(node, {
      property: 'letterSpacingFactor', value: -0.05,
    });

    expect(result.state).toBe('updated');
    if (result.state !== 'updated') throw new Error(result.message);
    expect(result.node).not.toHaveProperty('runs.0.letterSpacingPt');
    expect(result.node).toHaveProperty('runs.0.letterSpacingFactor', -0.05);
    expect(result.node).toHaveProperty('runs.0.fontRef.assetId', FONT_ASSET_ID);
  });

  it.each([
    {
      kind: 'image',
      valid: { property: 'fit', value: 'COVER' },
      expectedPath: 'fit', expectedValue: 'COVER',
      invalid: { property: 'imageRef', assetId: 'not-an-asset' },
      invalidCode: 'ASSET_ID_INVALID',
    },
    {
      kind: 'rect',
      valid: { property: 'cornerRadiusMm', value: 2 },
      expectedPath: 'cornerRadii',
      expectedValue: { topLeftMm: 2, topRightMm: 2, bottomRightMm: 2, bottomLeftMm: 2 },
      invalid: { property: 'cornerRadiusMm', value: -1 },
      invalidCode: 'PROPERTY_INVALID',
    },
    {
      kind: 'ellipse',
      valid: { property: 'fillColor', value: '#11223344' },
      expectedPath: 'fill.color', expectedValue: '#11223344',
      invalid: { property: 'fillColor', value: '#123' },
      invalidCode: 'PROPERTY_INVALID',
    },
    {
      kind: 'line',
      valid: { property: 'start', value: { xMm: 2, yMm: 8 } },
      expectedPath: 'start', expectedValue: { xMm: 2, yMm: 8 },
      invalid: { property: 'start', value: { xMm: 40, yMm: 10 } },
      invalidCode: 'PROPERTY_INVALID',
    },
    {
      kind: 'polygon',
      valid: {
        property: 'points',
        value: [{ xMm: 0, yMm: 0 }, { xMm: 8, yMm: 0 }, { xMm: 4, yMm: 6 }],
      },
      expectedPath: 'points',
      expectedValue: [{ xMm: 0, yMm: 0 }, { xMm: 8, yMm: 0 }, { xMm: 4, yMm: 6 }],
      invalid: {
        property: 'points',
        value: [{ xMm: 0, yMm: 0 }, { xMm: 1, yMm: 1 }, { xMm: 2, yMm: 2 }],
      },
      invalidCode: 'PROPERTY_INVALID',
    },
    {
      kind: 'polyline',
      valid: {
        property: 'points',
        value: [{ xMm: 0, yMm: 0 }, { xMm: 4, yMm: 2 }, { xMm: 8, yMm: 1 }],
      },
      expectedPath: 'points',
      expectedValue: [{ xMm: 0, yMm: 0 }, { xMm: 4, yMm: 2 }, { xMm: 8, yMm: 1 }],
      invalid: {
        property: 'points',
        value: [{ xMm: 0, yMm: 0 }, { xMm: 0, yMm: 0 }],
      },
      invalidCode: 'PROPERTY_INVALID',
    },
    {
      kind: 'path',
      valid: {
        property: 'commands',
        value: [{ type: 'MOVE_TO', xMm: 0, yMm: 0 }, { type: 'LINE_TO', xMm: 8, yMm: 4 }],
      },
      expectedPath: 'commands',
      expectedValue: [{ type: 'MOVE_TO', xMm: 0, yMm: 0 }, { type: 'LINE_TO', xMm: 8, yMm: 4 }],
      invalid: {
        property: 'commands',
        value: [{ type: 'LINE_TO', xMm: 8, yMm: 4 }],
      },
      invalidCode: 'PROPERTY_INVALID',
    },
    {
      kind: 'qrCode',
      valid: { property: 'content', value: 'RW-QR-002' },
      expectedPath: 'content', expectedValue: 'RW-QR-002',
      invalid: { property: 'content', value: '' },
      invalidCode: 'PROPERTY_INVALID',
    },
    {
      kind: 'barcode',
      valid: { property: 'barcodeValue', value: 'RW-BAR-002' },
      expectedPath: 'value', expectedValue: 'RW-BAR-002',
      invalid: { property: 'barcodeValue', value: '不可编码' },
      invalidCode: 'PROPERTY_INVALID',
    },
  ] satisfies ReadonlyArray<{
    kind: Exclude<TemplateVisualLeafKind, 'text'>;
    valid: TemplateVisualPropertyChange;
    expectedPath: string;
    expectedValue: unknown;
    invalid: TemplateVisualPropertyChange;
    invalidCode: 'ASSET_ID_INVALID' | 'PROPERTY_INVALID';
  }>)('updates $kind properties losslessly and rejects invalid values atomically', ({
    kind, valid, expectedPath, expectedValue, invalid, invalidCode,
  }) => {
    const node = builtNode(buildInput(kind));
    const before = JSON.stringify(node);
    const accepted = updateTemplateVisualNodeProperty(node, valid);

    expect(accepted.state).toBe('updated');
    if (accepted.state !== 'updated') throw new Error(accepted.message);
    expect(accepted.node).toHaveProperty(expectedPath, expectedValue);
    expect(inspectDesignDslWire(wrapInDesignDsl(accepted.node))).toEqual({ status: 'supported' });
    expect(JSON.stringify(node)).toBe(before);

    const rejected = updateTemplateVisualNodeProperty(node, invalid);
    expect(rejected).toEqual(expect.objectContaining({
      state: 'rejected', code: invalidCode, node,
    }));
    expect(rejected.node).toBe(node);
    expect(JSON.stringify(node)).toBe(before);
  });
});

function buildInput(kind: TemplateVisualLeafKind): BuildTemplateVisualNodeInput {
  const common = { nodeId: NODE_ID, ordinal: 2, at: { xMm: 3, yMm: 4 } } as const;
  if (kind === 'text') return { ...common, kind, fontAssetId: FONT_ASSET_ID };
  if (kind === 'image') return { ...common, kind, imageAssetId: IMAGE_ASSET_ID };
  return { ...common, kind };
}

function builtNode(input: BuildTemplateVisualNodeInput): Record<string, unknown> {
  const result = buildTemplateVisualNode(input);
  if (result.state !== 'built') throw new Error(result.message);
  return result.node as Record<string, unknown>;
}

function wrapInDesignDsl(node: unknown): Record<string, unknown> {
  return {
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: '视觉节点默认值检查',
    definitions: [],
    designRoot: {
      nodeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      kind: 'canvas',
      widthMm: 210,
      heightMm: 297,
      bindings: [],
      children: [node],
    },
  };
}
