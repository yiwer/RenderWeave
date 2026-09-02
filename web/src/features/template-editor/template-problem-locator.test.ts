import { LosslessNumber } from 'lossless-json';
import { describe, expect, it } from 'vitest';

import type { EditorNodeProjection } from './template-editor-model';
import { structuredBaseline } from './template-editor-test-support';
import { locateTemplateProblem } from './template-problem-locator';

const nodes: EditorNodeProjection[] = [
  node('canvas-id', 'canvas', '画布', 0),
  node('frame-id', 'frame', '内容区', 1),
  node('rect-id', 'rect', '底色', 2),
];

const DEFINITION_ID = '20000000-0000-4000-8000-000000000001';
const BINDING_ID = '30000000-0000-4000-8000-000000000001';

describe('Template Editor E9 strict problem locator', () => {
  it('projects only the exact Template display-name field', () => {
    expect(locateTemplateProblem(structuredBaseline().designDsl, nodes, '/displayName')).toEqual({
      state: 'located',
      target: { kind: 'template-display-name', label: 'Template 名称' },
      precision: 'exact',
    });
    expect(locateTemplateProblem(structuredBaseline().designDsl, nodes, '/displayName/value')).toEqual({
      state: 'unavailable',
      reason: 'TARGET_NOT_FOUND',
    });
  });

  it('returns the deepest exact authored node and reports exact traversal precision', () => {
    expect(locateTemplateProblem(
      structuredBaseline().designDsl,
      nodes,
      '/designRoot/children/0/children/0/displayName',
    )).toEqual({
      state: 'located',
      target: {
        kind: 'node',
        nodeId: 'rect-id',
        label: '底色',
      },
      precision: 'exact',
    });
  });

  it('falls back only to an exactly walked owning node when an invalid property is absent', () => {
    expect(locateTemplateProblem(
      structuredBaseline().designDsl,
      nodes,
      '/designRoot/children/0/fills/0/source',
    )).toEqual({
      state: 'located',
      target: {
        kind: 'node',
        nodeId: 'frame-id',
        label: '内容区',
      },
      precision: 'owning-node',
    });
  });

  it('projects an exact definition pointer with its stable definition focus identity', () => {
    const designDsl = structuredClone(structuredBaseline().designDsl);
    designDsl.definitions = [{
      definitionId: DEFINITION_ID,
      kind: 'custom',
      displayName: '会员标题',
      exposure: 'PRIVATE',
      valueType: 'text',
      defaultValue: '备用文本',
    }];

    expect(locateTemplateProblem(
      designDsl,
      nodes,
      '/definitions/0/displayName',
    )).toEqual({
      state: 'located',
      target: {
        kind: 'definitions',
        label: '会员标题',
        focus: { kind: 'definition', definitionId: DEFINITION_ID },
      },
      precision: 'exact',
    });
  });

  it('keeps stale or incomplete definition pointers on the compatible definitions section', () => {
    expect(locateTemplateProblem(
      structuredBaseline().designDsl,
      nodes,
      '/definitions/0/a~1b~0c',
    )).toEqual({
      state: 'located',
      target: { kind: 'definitions', label: '定义' },
      precision: 'section',
    });
  });

  it('projects a node-local binding pointer with its exact TargetPropertyRef focus identity', () => {
    const designDsl = structuredClone(structuredBaseline().designDsl);
    const frame = ((designDsl.designRoot as Record<string, unknown>).children as Record<string, unknown>[])[0]!;
    (frame.children as Record<string, unknown>[]).push({
      nodeId: 'text-id',
      kind: 'text',
      displayName: '标题',
      bindings: [{
        bindingId: BINDING_ID,
        targetPropertyRef: {
          rootPropertyId: 'runs',
          selectors: [
            { kind: 'index', index: new LosslessNumber('0') },
            { kind: 'member', name: 'text' },
          ],
        },
        source: { kind: 'context', domain: 'invocation', pointer: '/title' },
      }],
      runs: [{ text: '备用标题' }],
    });
    const projectedNodes = [...nodes, node('text-id', 'text', '标题', 2)];

    for (const pointer of [
      '/designRoot/children/0/children/1/bindings/0/targetPropertyRef/selectors/1/name',
      '/designRoot/children/0/children/1/bindings/0/source/pointer',
    ]) {
      expect(locateTemplateProblem(designDsl, projectedNodes, pointer)).toEqual({
        state: 'located',
        target: {
          kind: 'node',
          nodeId: 'text-id',
          label: '标题',
          focus: {
            kind: 'binding',
            bindingId: BINDING_ID,
            propertyPath: 'runs[0].text',
            targetPropertyRef: {
              rootPropertyId: 'runs',
              selectors: [
                { kind: 'index', index: 0 },
                { kind: 'member', name: 'text' },
              ],
            },
          },
        },
        precision: 'exact',
      });
    }
  });

  it('normalizes a server-legal member-then-index selector order for exact binding focus', () => {
    const designDsl = structuredClone(structuredBaseline().designDsl);
    const frame = ((designDsl.designRoot as Record<string, unknown>).children as Record<string, unknown>[])[0]!;
    (frame.children as Record<string, unknown>[]).push({
      nodeId: 'text-id',
      kind: 'text',
      displayName: '标题',
      bindings: [{
        bindingId: BINDING_ID,
        targetPropertyRef: {
          rootPropertyId: 'runs',
          selectors: [
            { kind: 'member', name: 'text' },
            { kind: 'index', index: new LosslessNumber('0') },
          ],
        },
        source: { kind: 'context', domain: 'invocation', pointer: '/title' },
      }],
      runs: [{ text: '备用标题' }],
    });

    expect(locateTemplateProblem(
      designDsl,
      [...nodes, node('text-id', 'text', '标题', 2)],
      '/designRoot/children/0/children/1/bindings/0/source/pointer',
    )).toMatchObject({
      state: 'located',
      target: {
        focus: {
          propertyPath: 'runs[0].text',
          targetPropertyRef: {
            rootPropertyId: 'runs',
            selectors: [
              { kind: 'index', index: 0 },
              { kind: 'member', name: 'text' },
            ],
          },
        },
      },
    });
  });

  it('does not emit ambiguous definition or binding focus identities during recovery', () => {
    const designDsl = structuredClone(structuredBaseline().designDsl);
    const definition = {
      definitionId: DEFINITION_ID,
      kind: 'custom',
      displayName: '重复定义',
      exposure: 'PRIVATE',
      valueType: 'text',
      defaultValue: '备用文本',
    };
    designDsl.definitions = [definition, { ...definition }];
    const frame = ((designDsl.designRoot as Record<string, unknown>).children as Record<string, unknown>[])[0]!;
    (frame.children as Record<string, unknown>[]).push({
      nodeId: 'text-id',
      kind: 'text',
      displayName: '标题',
      bindings: [0, 1].map(() => ({
        bindingId: BINDING_ID,
        targetPropertyRef: { rootPropertyId: 'runs', selectors: [] },
        source: { kind: 'context', domain: 'invocation', pointer: '/title' },
      })),
      runs: [{ text: '备用标题' }],
    });
    const projectedNodes = [...nodes, node('text-id', 'text', '标题', 2)];

    expect(locateTemplateProblem(designDsl, projectedNodes, '/definitions/0/displayName')).toEqual({
      state: 'located',
      target: { kind: 'definitions', label: '定义' },
      precision: 'section',
    });
    expect(locateTemplateProblem(
      designDsl,
      projectedNodes,
      '/designRoot/children/0/children/1/bindings/0/source/pointer',
    )).toEqual({
      state: 'located',
      target: { kind: 'node', nodeId: 'text-id', label: '标题' },
      precision: 'exact',
    });
  });

  it.each([
    ['missing leading slash', 'designRoot/children/0', 'MALFORMED_POINTER'],
    ['URI fragment form', '#/designRoot', 'MALFORMED_POINTER'],
    ['bad escape', '/designRoot/~2', 'MALFORMED_POINTER'],
    ['dangling escape', '/designRoot/~', 'MALFORMED_POINTER'],
    ['non-canonical array index', '/designRoot/children/01', 'INVALID_ARRAY_INDEX'],
    ['dash array index', '/designRoot/children/-', 'INVALID_ARRAY_INDEX'],
    ['out-of-range array index', '/designRoot/children/9', 'TARGET_NOT_FOUND'],
    ['unknown root branch', '/staticSchemaRef/schemaKey', 'UNSUPPORTED_TARGET'],
  ] as const)('fails closed for %s', (_label, pointer, reason) => {
    expect(locateTemplateProblem(structuredBaseline().designDsl, nodes, pointer)).toEqual({
      state: 'unavailable',
      reason,
    });
  });

  it('treats an empty limit marker as summary-only and bounds pointer work', () => {
    expect(locateTemplateProblem(structuredBaseline().designDsl, nodes, '')).toEqual({
      state: 'unavailable',
      reason: 'SUMMARY_ONLY',
    });
    expect(locateTemplateProblem(
      structuredBaseline().designDsl,
      nodes,
      `/${'x'.repeat(2049)}`,
    )).toEqual({
      state: 'unavailable',
      reason: 'POINTER_LIMIT_EXCEEDED',
    });
  });

  it('never traverses inherited properties or targets a node absent from the rendered projection', () => {
    const designDsl = structuredBaseline().designDsl;
    const canvas = designDsl.designRoot as Record<string, unknown>;
    const inherited = Object.create({
      inherited: { nodeId: 'evil-id', kind: 'frame', displayName: '不应定位' },
    }) as Record<string, unknown>;
    Object.assign(inherited, canvas);
    const withInherited = { ...designDsl, designRoot: inherited };

    expect(locateTemplateProblem(withInherited, [
      ...nodes,
      node('evil-id', 'frame', '不应定位', 1),
    ], '/designRoot/inherited/displayName')).toEqual({
      state: 'located',
      target: {
        kind: 'node',
        nodeId: 'canvas-id',
        label: '画布',
      },
      precision: 'owning-node',
    });

    expect(locateTemplateProblem(
      structuredBaseline().designDsl,
      nodes.slice(0, 2),
      '/designRoot/children/0/children/0/displayName',
    )).toEqual({
      state: 'located',
      target: {
        kind: 'node',
        nodeId: 'frame-id',
        label: '内容区',
      },
      precision: 'owning-node',
    });
  });
});

function node(
  nodeId: string,
  kind: string,
  displayName: string,
  depth: number,
): EditorNodeProjection {
  return {
    nodeId,
    kind,
    displayName,
    depth,
    childCount: 0,
    value: {},
  };
}
