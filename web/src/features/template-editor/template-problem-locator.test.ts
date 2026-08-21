import { describe, expect, it } from 'vitest';

import type { EditorNodeProjection } from './template-editor-model';
import { structuredBaseline } from './template-editor-test-support';
import { locateTemplateProblem } from './template-problem-locator';

const nodes: EditorNodeProjection[] = [
  node('canvas-id', 'canvas', '画布', 0),
  node('frame-id', 'frame', '内容区', 1),
  node('rect-id', 'rect', '底色', 2),
];

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

  it('projects definitions pointers to the stable definitions panel after strict escape decoding', () => {
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
