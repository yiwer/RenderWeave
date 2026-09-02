/// <reference types="node" />

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { parse } from 'lossless-json';
import { describe, expect, it } from 'vitest';

import {
  inspectDesignDslWire,
  SUPPORTED_NODE_KIND_COUNT,
} from './template-design-dsl-wire';

interface KernelVector {
  id: string;
  expected: {
    outcome: string;
    canonicalUtf8?: string;
  };
}

interface KernelManifest {
  cases: KernelVector[];
}

const manifest = JSON.parse(readFileSync(fileURLToPath(new URL(
  '../../../../renderweave-template/src/test/resources/cn/hbads/renderweave/template/canonical-kernel-v1/vectors.json',
  import.meta.url,
)), 'utf8')) as KernelManifest;
const completeWire = readFileSync(fileURLToPath(new URL(
  '../../../../renderweave-template/src/test/resources/cn/hbads/renderweave/template/complete-wire-v1/all-kinds.json',
  import.meta.url,
)), 'utf8');
const admitted = manifest.cases.filter((entry) =>
  entry.expected.outcome === 'ADMITTED' && entry.expected.canonicalUtf8 !== undefined,
);

describe('Template DesignDSL closed-wire seam', () => {
  it('recognizes the single shared complete-wire fixture as Structured-capable', () => {
    const designDsl = parse(completeWire) as Record<string, unknown>;
    const encounteredKinds = new Set<string>();
    collectNodeKinds(designDsl.designRoot, encounteredKinds);

    expect(inspectDesignDslWire(designDsl)).toEqual({ status: 'supported' });
    expect(encounteredKinds.size).toBe(SUPPORTED_NODE_KIND_COUNT);
    expect(SUPPORTED_NODE_KIND_COUNT).toBe(18);
  });

  it('recognizes every authority-admitted canonical shape across all 18 node kinds', () => {
    const encounteredKinds = new Set<string>();

    for (const vector of admitted) {
      const designDsl = parse(vector.expected.canonicalUtf8 ?? '') as Record<string, unknown>;
      expect(inspectDesignDslWire(designDsl), vector.id).toEqual({ status: 'supported' });
      collectNodeKinds(designDsl.designRoot, encounteredKinds);
    }

    expect(admitted.length).toBeGreaterThan(40);
    expect(encounteredKinds.size).toBe(SUPPORTED_NODE_KIND_COUNT);
    expect(SUPPORTED_NODE_KIND_COUNT).toBe(18);
  });

  it('reports future nested members at their exact closed-wire path', () => {
    const designDsl = baseDesign();
    const child = ((designDsl.designRoot as JsonObject).children as JsonObject[])[0];
    if (!child) throw new Error('fixture child missing');
    child.futurePaint = { opaque: true };

    expect(inspectDesignDslWire(designDsl)).toEqual({
      status: 'unknown',
      path: 'designRoot.children[0].futurePaint',
    });
  });

  it.each([
    {
      name: 'list ValueType carrying an enum-only member',
      designDsl: {
        ...baseDesign(),
        definitions: [{
          definitionId: 'definition-id', kind: 'custom', displayName: 'List',
          exposure: 'PRIVATE',
          valueType: { type: 'list', items: 'text', catalogId: 'must-not-cross-variants' },
          defaultValue: [],
        }],
      },
      path: 'definitions[0].valueType.catalogId',
    },
    {
      name: 'invocation selector domain carrying a loop-only member',
      designDsl: designWithChild(node('templateUse', {
        templateRef: { templateId: 'template-id' },
        contextSelector: {
          kind: 'context',
          domain: { kind: 'invocation', loopId: 'must-not-cross-variants' },
          pointer: '/value',
          contextAbsentPolicy: 'ERROR',
        },
        fills: [],
      })),
      path: 'designRoot.children[0].contextSelector.domain.loopId',
    },
  ])('keeps closed-union members variant-specific: $name', ({ designDsl, path }) => {
    expect(inspectDesignDslWire(designDsl)).toEqual({ status: 'unknown', path });
  });

  it('distinguishes malformed authored structure from known semantic invalidity', () => {
    const malformed = baseDesign();
    delete (((malformed.designRoot as JsonObject).children as JsonObject[])[0] as JsonObject).nodeId;
    expect(inspectDesignDslWire(malformed)).toEqual({
      status: 'malformed',
      path: 'designRoot.children[0].nodeId',
    });

    const semanticallyInvalid = baseDesign();
    (semanticallyInvalid.designRoot as JsonObject).widthMm = -1;
    expect(inspectDesignDslWire(semanticallyInvalid)).toEqual({ status: 'supported' });
  });

  it.each(malformedWireCases())('fails closed for malformed $name', ({ designDsl, path }) => {
    expect(inspectDesignDslWire(designDsl)).toEqual({ status: 'malformed', path });
  });

  it('leaves known scalar-domain invalidity to the server authority', () => {
    const designDsl = designWithChild(node('rect', {
      opacity: 99,
      placement: { type: 'ABSOLUTE', widthMode: 'FUTURE_SIZE_MODE' },
      fill: { color: 'not-a-canonical-color' },
    }));
    ((designDsl.designRoot as JsonObject).children as JsonObject[])[0]!.nodeId = 'not-a-uuid';

    expect(inspectDesignDslWire(designDsl)).toEqual({ status: 'supported' });
  });
});

type JsonObject = Record<string, unknown>;

function baseDesign(): JsonObject {
  return {
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: 'Wire seam',
    definitions: [],
    designRoot: {
      nodeId: '00000000-0000-4000-8000-000000000001',
      kind: 'canvas',
      widthMm: 210,
      heightMm: 297,
      bindings: [],
      children: [{
        nodeId: '00000000-0000-4000-8000-000000000002',
        kind: 'rect',
        bindings: [],
        placement: { type: 'ABSOLUTE' },
      }],
    },
  };
}

function malformedWireCases(): Array<{
  name: string;
  designDsl: JsonObject;
  path: string;
}> {
  const mapping = (overrides: JsonObject = {}): JsonObject => ({
    definitionId: 'definition-id',
    kind: 'mapping',
    displayName: 'Mapping',
    domain: 'invocation',
    output: 'text',
    input: { kind: 'context', domain: 'invocation', pointer: '/value' },
    cases: [{ operator: 'IS_ABSENT', then: { kind: 'literal', valueType: 'text', value: '' } }],
    otherwise: { kind: 'literal', valueType: 'text', value: '' },
    ...overrides,
  });
  const expression = (overrides: JsonObject = {}): JsonObject => ({
    definitionId: 'definition-id',
    kind: 'expression',
    displayName: 'Expression',
    domain: 'invocation',
    output: 'text',
    inputs: [{ alias: 'value', source: { kind: 'context', domain: 'invocation', pointer: '/value' } }],
    source: 'input.value',
    ...overrides,
  });
  const withDefinitions = (definitions: unknown): JsonObject => ({ ...baseDesign(), definitions });
  const binding = (overrides: JsonObject = {}): JsonObject => ({
    bindingId: 'binding-id',
    targetPropertyRef: { rootPropertyId: 'opacity', selectors: [] },
    source: { kind: 'definition', definitionId: 'definition-id' },
    ...overrides,
  });

  return [
    { name: 'definitions container', designDsl: withDefinitions({}), path: 'definitions' },
    { name: 'definition item', designDsl: withDefinitions([null]), path: 'definitions[0]' },
    { name: 'definition discriminator', designDsl: withDefinitions([{}]), path: 'definitions[0].kind' },
    {
      name: 'valueType discriminator',
      designDsl: withDefinitions([{
        definitionId: 'definition-id', kind: 'custom', displayName: 'Custom',
        exposure: 'PRIVATE', valueType: {}, defaultValue: '',
      }]),
      path: 'definitions[0].valueType.type',
    },
    { name: 'mapping cases container', designDsl: withDefinitions([mapping({ cases: {} })]), path: 'definitions[0].cases' },
    { name: 'mapping case item', designDsl: withDefinitions([mapping({ cases: [null] })]), path: 'definitions[0].cases[0]' },
    { name: 'mapping source object', designDsl: withDefinitions([mapping({ input: null })]), path: 'definitions[0].input' },
    { name: 'mapping source discriminator', designDsl: withDefinitions([mapping({ input: {} })]), path: 'definitions[0].input.kind' },
    { name: 'expression inputs container', designDsl: withDefinitions([expression({ inputs: {} })]), path: 'definitions[0].inputs' },
    { name: 'expression input item', designDsl: withDefinitions([expression({ inputs: [null] })]), path: 'definitions[0].inputs[0]' },
    {
      name: 'bindings container',
      designDsl: designWithChild(node('rect', { bindings: {} })),
      path: 'designRoot.children[0].bindings',
    },
    {
      name: 'binding item',
      designDsl: designWithChild(node('rect', { bindings: [null] })),
      path: 'designRoot.children[0].bindings[0]',
    },
    {
      name: 'binding target object',
      designDsl: designWithChild(node('rect', { bindings: [binding({ targetPropertyRef: undefined })] })),
      path: 'designRoot.children[0].bindings[0].targetPropertyRef',
    },
    {
      name: 'binding source object',
      designDsl: designWithChild(node('rect', { bindings: [binding({ source: undefined })] })),
      path: 'designRoot.children[0].bindings[0].source',
    },
    {
      name: 'selectors container',
      designDsl: designWithChild(node('rect', {
        bindings: [binding({ targetPropertyRef: { rootPropertyId: 'opacity', selectors: {} } })],
      })),
      path: 'designRoot.children[0].bindings[0].targetPropertyRef.selectors',
    },
    {
      name: 'selector item',
      designDsl: designWithChild(node('rect', {
        bindings: [binding({ targetPropertyRef: { rootPropertyId: 'opacity', selectors: [null] } })],
      })),
      path: 'designRoot.children[0].bindings[0].targetPropertyRef.selectors[0]',
    },
    {
      name: 'selector discriminator',
      designDsl: designWithChild(node('rect', {
        bindings: [binding({ targetPropertyRef: { rootPropertyId: 'opacity', selectors: [{}] } })],
      })),
      path: 'designRoot.children[0].bindings[0].targetPropertyRef.selectors[0].kind',
    },
    { name: 'children container', designDsl: designWithChildren({}), path: 'designRoot.children' },
    { name: 'required Canvas children', designDsl: designWithChildren(undefined), path: 'designRoot.children' },
    { name: 'child item', designDsl: designWithChildren([null]), path: 'designRoot.children[0]' },
    {
      name: 'placement object',
      designDsl: designWithChild(node('rect', { placement: [] })),
      path: 'designRoot.children[0].placement',
    },
    {
      name: 'required placement object',
      designDsl: designWithChild(node('rect', { placement: undefined })),
      path: 'designRoot.children[0].placement',
    },
    {
      name: 'placement discriminator',
      designDsl: designWithChild(node('rect', { placement: {} })),
      path: 'designRoot.children[0].placement.type',
    },
    {
      name: 'runs container',
      designDsl: designWithChild(node('text', { runs: {} })),
      path: 'designRoot.children[0].runs',
    },
    {
      name: 'run item',
      designDsl: designWithChild(node('text', { runs: [null] })),
      path: 'designRoot.children[0].runs[0]',
    },
    {
      name: 'points container',
      designDsl: designWithChild(node('polygon', { points: {} })),
      path: 'designRoot.children[0].points',
    },
    {
      name: 'point item',
      designDsl: designWithChild(node('polygon', { points: [null] })),
      path: 'designRoot.children[0].points[0]',
    },
    {
      name: 'commands container',
      designDsl: designWithChild(node('path', { commands: {} })),
      path: 'designRoot.children[0].commands',
    },
    {
      name: 'command item',
      designDsl: designWithChild(node('path', { commands: [null] })),
      path: 'designRoot.children[0].commands[0]',
    },
    {
      name: 'command discriminator',
      designDsl: designWithChild(node('path', { commands: [{}] })),
      path: 'designRoot.children[0].commands[0].type',
    },
    {
      name: 'tracks container',
      designDsl: designWithChild(node('grid', { rows: {}, columns: [{ type: 'AUTO' }], children: [] })),
      path: 'designRoot.children[0].rows',
    },
    {
      name: 'track item',
      designDsl: designWithChild(node('grid', { rows: [null], columns: [{ type: 'AUTO' }], children: [] })),
      path: 'designRoot.children[0].rows[0]',
    },
    {
      name: 'track discriminator',
      designDsl: designWithChild(node('grid', { rows: [{}], columns: [{ type: 'AUTO' }], children: [] })),
      path: 'designRoot.children[0].rows[0].type',
    },
    {
      name: 'fills container',
      designDsl: designWithChild(node('templateUse', {
        templateRef: { templateId: 'template-id' }, contextSelector: { kind: 'empty' }, fills: {},
      })),
      path: 'designRoot.children[0].fills',
    },
    {
      name: 'fill item',
      designDsl: designWithChild(node('templateUse', {
        templateRef: { templateId: 'template-id' }, contextSelector: { kind: 'empty' }, fills: [null],
      })),
      path: 'designRoot.children[0].fills[0]',
    },
    {
      name: 'context selector discriminator',
      designDsl: designWithChild(node('templateUse', {
        templateRef: { templateId: 'template-id' }, contextSelector: {}, fills: [],
      })),
      path: 'designRoot.children[0].contextSelector.kind',
    },
    {
      name: 'required templateRef object',
      designDsl: designWithChild(node('templateUse', {
        contextSelector: { kind: 'empty' }, fills: [],
      })),
      path: 'designRoot.children[0].templateRef',
    },
    {
      name: 'repeat packing discriminator',
      designDsl: designWithChild(node('repeat', {
        items: { kind: 'definition', definitionId: 'definition-id' },
        itemLayout: {}, instanceLayout: { kind: 'STACK', direction: 'ROW' }, children: [],
      })),
      path: 'designRoot.children[0].itemLayout.kind',
    },
    {
      name: 'required repeat items object',
      designDsl: designWithChild(node('repeat', {
        itemLayout: { kind: 'STACK', direction: 'ROW' },
        instanceLayout: { kind: 'STACK', direction: 'ROW' }, children: [],
      })),
      path: 'designRoot.children[0].items',
    },
  ];
}

function node(kind: string, members: JsonObject = {}): JsonObject {
  return {
    nodeId: `node-${kind}`,
    kind,
    bindings: [],
    placement: { type: 'ABSOLUTE' },
    ...members,
  };
}

function designWithChild(child: JsonObject): JsonObject {
  return designWithChildren([child]);
}

function designWithChildren(children: unknown): JsonObject {
  const designDsl = baseDesign();
  (designDsl.designRoot as JsonObject).children = children;
  return designDsl;
}

function collectNodeKinds(value: unknown, target: Set<string>) {
  if (!isRecord(value)) return;
  if (typeof value.kind === 'string') target.add(value.kind);
  if (Array.isArray(value.children)) {
    for (const child of value.children) collectNodeKinds(child, target);
  }
}

function isRecord(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
