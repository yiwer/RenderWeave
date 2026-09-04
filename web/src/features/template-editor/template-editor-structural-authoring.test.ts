import { describe, expect, it } from 'vitest';

import type { StaticSnapshot } from '../schema-studio/lossless-api';
import {
  projectStructuralAuthoring,
  selectTemplateUseInsertionCandidate,
  wholeTemplateContextSelector,
  type TemplateStructuralSample,
} from './template-editor-structural-authoring';

const ROOT_REPEAT_ID = '00000000-0000-4000-8000-000000000101';
const ROOT_LOOP_ID = '00000000-0000-4000-8000-000000000201';
const LIST_DEFINITION_ID = '00000000-0000-4000-8000-000000000301';
const TEMPLATE_USE_NODE_ID = '00000000-0000-4000-8000-000000000105';
const TEMPLATE_USE_ID = '00000000-0000-4000-8000-000000000205';
const OFFER_TEMPLATE_ID = '00000000-0000-4000-8000-000000000501';
const CHILD_PUBLIC_ID = '00000000-0000-4000-8000-000000000601';
const CHILD_PUBLIC_LIST_ID = '00000000-0000-4000-8000-000000000603';

describe('Template Editor structural authoring', () => {
  it('offers only exact eligible StaticSchema and Definition collections to Repeat', () => {
    const designDsl = designWithChildren([
      repeatNode(ROOT_REPEAT_ID, ROOT_LOOP_ID, context('invocation', '/tags')),
    ], [{
      definitionId: LIST_DEFINITION_ID,
      kind: 'expression',
      displayName: '备用标签',
      domain: 'invocation',
      output: { type: 'list', items: 'text' },
      inputs: [],
      source: '[]',
    }, {
      definitionId: '00000000-0000-4000-8000-000000000302',
      kind: 'expression',
      displayName: '图片列表',
      domain: 'invocation',
      output: { type: 'list', items: 'imageRef' },
      inputs: [],
      source: '[]',
    }]);

    const projection = projectStructuralAuthoring({
      designDsl,
      staticSchema: rootSchema(),
      staticSchemas: [offerSchema()],
    });

    expect(projection.repeatSources[ROOT_REPEAT_ID]?.map(({ id, itemContext, presence }) => ({
      id,
      itemContext,
      presence,
    }))).toEqual([
      {
        id: 'context:invocation:/tags',
        itemContext: { schemaKey: 'system-basic-text', versionTag: 'v1' },
        presence: 'MAY_BE_ABSENT',
      },
      {
        id: 'context:invocation:/offers',
        itemContext: { schemaKey: 'offer', versionTag: 'v1' },
        presence: 'CONCRETE',
      },
      {
        id: `definition:${LIST_DEFINITION_ID}`,
        itemContext: { schemaKey: 'system-basic-text', versionTag: 'v1' },
        presence: 'CONCRETE',
      },
    ]);
    expect(projection.loopContexts).toEqual([expect.objectContaining({
      loopId: ROOT_LOOP_ID,
      repeatNodeId: ROOT_REPEAT_ID,
      itemContext: { schemaKey: 'system-basic-text', versionTag: 'v1' },
    })]);
  });

  it('projects Repeat values, empty, ABSENT and errors without replacing authored content', () => {
    const authored = repeatNode(ROOT_REPEAT_ID, ROOT_LOOP_ID, context('invocation', '/tags'));
    const designDsl = designWithChildren([authored]);
    const children = authored.children as readonly unknown[];
    const project = (
      sample: TemplateStructuralSample,
      priorItemContexts: Readonly<Record<string, { schemaKey: string; versionTag: string }>> = {},
    ) => projectStructuralAuthoring({
      designDsl,
      staticSchema: rootSchema(),
      staticSchemas: [offerSchema()],
      sample: { [ROOT_REPEAT_ID]: sample },
      priorItemContexts,
    }).nodeStates[ROOT_REPEAT_ID];

    expect(project({ state: 'value', value: ['alpha', 'beta'] })).toMatchObject({
      kind: 'repeat',
      authoringState: 'READY',
      runtime: {
        state: 'VALUES',
        occurrences: [
          {
            inputIndex: 0,
            itemContext: { schemaKey: 'system-basic-text', versionTag: 'v1' },
            value: { index: 0, value: 'alpha' },
          },
          {
            inputIndex: 1,
            itemContext: { schemaKey: 'system-basic-text', versionTag: 'v1' },
            value: { index: 1, value: 'beta' },
          },
        ],
      },
    });
    expect(project({ state: 'value', value: [] })).toMatchObject({ runtime: { state: 'EMPTY' } });
    expect(project({ state: 'absent' })).toMatchObject({ runtime: { state: 'EMPTY' } });
    expect(project({ state: 'error', code: 'SAMPLE_INVALID' })).toMatchObject({
      runtime: { state: 'SOURCE_ERROR', code: 'SAMPLE_INVALID' },
    });

    authored.absentPolicy = 'ERROR';
    expect(project({ state: 'absent' })).toMatchObject({ runtime: { state: 'ABSENT_ERROR' } });

    authored.items = context('invocation', '/offers');
    const changed = project(
      { state: 'value', value: [{ name: 'Offer' }] },
      { [ROOT_REPEAT_ID]: { schemaKey: 'system-basic-text', versionTag: 'v1' } },
    );
    expect(changed).toMatchObject({
      authoringState: 'NEEDS_REPAIR',
      itemContext: { schemaKey: 'offer', versionTag: 'v1' },
    });
    if (!changed || changed.kind !== 'repeat') throw new Error('expected Repeat state');
    expect(changed.authoredChildren).toBe(children);
  });

  it('offers loop fields and loop-scoped Definitions only inside their exact lexical domain', () => {
    const nestedRepeatId = '00000000-0000-4000-8000-000000000102';
    const nestedLoopId = '00000000-0000-4000-8000-000000000202';
    const conditionalId = '00000000-0000-4000-8000-000000000103';
    const loopDefinitionId = '00000000-0000-4000-8000-000000000303';
    const nested = repeatNode(
      nestedRepeatId,
      nestedLoopId,
      context({ kind: 'loop', loopId: ROOT_LOOP_ID }, '/badges'),
    );
    const conditional = conditionalNode(
      conditionalId,
      context({ kind: 'loop', loopId: ROOT_LOOP_ID }, '/featured'),
    );
    const outer = repeatNode(
      ROOT_REPEAT_ID,
      ROOT_LOOP_ID,
      context('invocation', '/offers'),
      [nested, conditional],
    );
    const projection = projectStructuralAuthoring({
      designDsl: designWithChildren([outer], [{
        definitionId: loopDefinitionId,
        kind: 'mapping',
        displayName: '循环标签',
        domain: { kind: 'loop', loopId: ROOT_LOOP_ID },
        output: { type: 'list', items: 'text' },
        input: context({ kind: 'loop', loopId: ROOT_LOOP_ID }, '/badges'),
        cases: [],
        otherwise: { kind: 'literal', valueType: { type: 'list', items: 'text' }, value: [] },
      }]),
      staticSchema: rootSchema(),
      staticSchemas: [offerSchema()],
    });

    expect(projection.repeatSources[ROOT_REPEAT_ID]?.some(({ id }) => id === `definition:${loopDefinitionId}`)).toBe(false);
    expect(projection.repeatSources[nestedRepeatId]?.map(({ id }) => id)).toContain(
      `context:loop:${ROOT_LOOP_ID}:/badges`,
    );
    expect(projection.repeatSources[nestedRepeatId]).toContainEqual(expect.objectContaining({
      id: `definition:${loopDefinitionId}`,
      source: { kind: 'definition', definitionId: loopDefinitionId },
    }));
    expect(projection.booleanSources[conditionalId]).toContainEqual(expect.objectContaining({
      id: `context:loop:${ROOT_LOOP_ID}:/featured`,
      presence: 'MAY_BE_ABSENT',
    }));
    expect(projection.loopContexts.map(({ loopId, ancestorLoopIds }) => ({ loopId, ancestorLoopIds }))).toEqual([
      { loopId: ROOT_LOOP_ID, ancestorLoopIds: [] },
      { loopId: nestedLoopId, ancestorLoopIds: [ROOT_LOOP_ID] },
    ]);
  });

  it('projects Conditional TRUE, FALSE, ABSENT and error while retaining its authored branch', () => {
    const conditionalId = '00000000-0000-4000-8000-000000000104';
    const conditional = conditionalNode(conditionalId, context('invocation', '/show'));
    const branch = conditional.children as readonly unknown[];
    const designDsl = designWithChildren([conditional]);
    const project = (sample: TemplateStructuralSample) => projectStructuralAuthoring({
      designDsl,
      staticSchema: rootSchema(),
      sample: { [conditionalId]: sample },
    }).nodeStates[conditionalId];

    expect(project({ state: 'value', value: true })).toMatchObject({
      kind: 'conditional', authoringState: 'READY', runtime: { state: 'TRUE' },
    });
    expect(project({ state: 'value', value: false })).toMatchObject({ runtime: { state: 'FALSE' } });
    expect(project({ state: 'absent' })).toMatchObject({ runtime: { state: 'FALSE' } });
    expect(project({ state: 'error', code: 'SAMPLE_INVALID' })).toMatchObject({
      runtime: { state: 'SOURCE_ERROR', code: 'SAMPLE_INVALID' },
    });
    expect(project({ state: 'value', value: 'true' })).toMatchObject({
      runtime: { state: 'SOURCE_ERROR', code: 'SAMPLE_NOT_BOOLEAN' },
    });
    const trueState = project({ state: 'value', value: true });
    if (!trueState || trueState.kind !== 'conditional') throw new Error('expected Conditional state');
    expect(trueState.authoredChildren).toBe(branch);

    conditional.absentPolicy = 'ERROR';
    expect(project({ state: 'absent' })).toMatchObject({ runtime: { state: 'ABSENT_ERROR' } });

    conditional.condition = context('invocation', '/title');
    expect(project({ state: 'value', value: true })).toMatchObject({
      authoringState: 'INVALID',
      problems: ['SOURCE_INELIGIBLE'],
    });
  });

  it('filters TemplateUse by exact READY context and validates PUBLIC fills in the parent loop', () => {
    const use = templateUseNode(
      OFFER_TEMPLATE_ID,
      wholeTemplateContextSelector({ kind: 'loop', loopId: ROOT_LOOP_ID }),
      [{
        targetDefinitionId: CHILD_PUBLIC_ID,
        source: context({ kind: 'loop', loopId: ROOT_LOOP_ID }, '/name'),
      }],
    );
    const outer = repeatNode(
      ROOT_REPEAT_ID,
      ROOT_LOOP_ID,
      context('invocation', '/offers'),
      [use],
    );
    const projection = projectStructuralAuthoring({
      designDsl: designWithChildren([outer]),
      staticSchema: rootSchema(),
      staticSchemas: [offerSchema()],
      templateCatalog: [
        templateCatalogEntry(OFFER_TEMPLATE_ID, 'offer', 'v1', 'READY'),
        templateCatalogEntry('00000000-0000-4000-8000-000000000502', 'offer', 'v1', 'STALE'),
        templateCatalogEntry('00000000-0000-4000-8000-000000000503', 'other', 'v1', 'READY'),
      ],
      templateCurrents: [childTemplateCurrent()],
    });

    expect(wholeTemplateContextSelector({ kind: 'loop', loopId: ROOT_LOOP_ID })).toEqual({
      kind: 'context',
      domain: { kind: 'loop', loopId: ROOT_LOOP_ID },
      pointer: '',
      contextAbsentPolicy: 'ERROR',
    });
    expect(projection.templateTargets[TEMPLATE_USE_NODE_ID]?.map(({ templateId, state }) => ({
      templateId,
      state,
    }))).toEqual([
      { templateId: OFFER_TEMPLATE_ID, state: 'eligible' },
      { templateId: '00000000-0000-4000-8000-000000000502', state: 'unavailable' },
      { templateId: '00000000-0000-4000-8000-000000000503', state: 'incompatible' },
    ]);
    expect(projection.nodeStates[TEMPLATE_USE_NODE_ID]).toMatchObject({
      kind: 'templateUse',
      authoringState: 'READY',
      context: {
        state: 'READY',
        schema: { schemaKey: 'offer', versionTag: 'v1' },
      },
      fillTargets: [{
        definitionId: CHILD_PUBLIC_ID,
        displayName: '商品名',
        valueType: 'text',
      }],
      fills: [{ targetDefinitionId: CHILD_PUBLIC_ID, state: 'READY' }],
      sourceCanvasSizeMm: { widthMm: 100, heightMm: 100 },
      problems: [],
    });
    expect(selectTemplateUseInsertionCandidate(
      projection,
      TEMPLATE_USE_NODE_ID,
      [templateCatalogEntry(OFFER_TEMPLATE_ID, 'offer', 'v1', 'READY')],
    )).toEqual({
      templateId: OFFER_TEMPLATE_ID,
      contextSelector: wholeTemplateContextSelector(
        { kind: 'loop', loopId: ROOT_LOOP_ID },
        'SKIP',
      ),
    });
  });

  it('authors reference context and exact list fills without excluding legal ABSENT sources', () => {
    const use = templateUseNode(
      OFFER_TEMPLATE_ID,
      {
        kind: 'context',
        domain: { kind: 'invocation' },
        pointer: '/featuredOffer',
        contextAbsentPolicy: 'SKIP',
      },
      [{
        targetDefinitionId: CHILD_PUBLIC_LIST_ID,
        source: context('invocation', '/tags'),
      }],
    );
    const parentListDefinition = {
      definitionId: LIST_DEFINITION_ID,
      kind: 'expression',
      displayName: '备用标签',
      domain: 'invocation',
      output: { type: 'list', items: 'text' },
      inputs: [],
      source: '[]',
    };
    const projection = projectStructuralAuthoring({
      designDsl: designWithChildren([use], [parentListDefinition]),
      staticSchema: rootSchemaWithOfferReference(),
      staticSchemas: [offerSchema()],
      templateCatalog: [templateCatalogEntry(OFFER_TEMPLATE_ID, 'offer', 'v1', 'READY')],
      templateCurrents: [childTemplateCurrent([{
        definitionId: CHILD_PUBLIC_LIST_ID,
        kind: 'custom',
        displayName: '标签列表',
        exposure: 'PUBLIC',
        valueType: { type: 'list', items: 'text' },
        defaultValue: [],
      }])],
    });
    const state = projection.nodeStates[TEMPLATE_USE_NODE_ID];
    if (!state || state.kind !== 'templateUse') throw new Error('expected TemplateUse state');

    expect(state.authoringState).toBe('READY');
    expect(state.contextOptions).toEqual(expect.arrayContaining([
      expect.objectContaining({
        id: 'template-context:invocation:',
        schema: { schemaKey: 'root', versionTag: 'v1' },
        presence: 'CONCRETE',
      }),
      expect.objectContaining({
        id: 'template-context:invocation:/featuredOffer',
        schema: { schemaKey: 'offer', versionTag: 'v1' },
        presence: 'MAY_BE_ABSENT',
      }),
    ]));
    const listTarget = state.fillTargets.find(({ definitionId }) => (
      definitionId === CHILD_PUBLIC_LIST_ID
    ));
    expect(listTarget?.sources.map(({ id, presence }) => ({ id, presence }))).toEqual([
      { id: 'context:invocation:/tags', presence: 'MAY_BE_ABSENT' },
      { id: 'context:invocation:/featuredOffer/badges', presence: 'MAY_BE_ABSENT' },
      { id: `definition:${LIST_DEFINITION_ID}`, presence: 'CONCRETE' },
    ]);
    expect(state.fills).toContainEqual({
      targetDefinitionId: CHILD_PUBLIC_LIST_ID,
      state: 'READY',
    });
    expect(selectTemplateUseInsertionCandidate(
      projection,
      TEMPLATE_USE_NODE_ID,
      [templateCatalogEntry(OFFER_TEMPLATE_ID, 'offer', 'v1', 'READY')],
    )).toEqual({
      templateId: OFFER_TEMPLATE_ID,
      contextSelector: {
        kind: 'context',
        domain: { kind: 'invocation' },
        pointer: '/featuredOffer',
        contextAbsentPolicy: 'ERROR',
      },
    });
  });

  it('does not expose child facts when catalog READY and current readiness drift apart', () => {
    const use = templateUseNode(
      OFFER_TEMPLATE_ID,
      wholeTemplateContextSelector({ kind: 'loop', loopId: ROOT_LOOP_ID }),
    );
    const outer = repeatNode(
      ROOT_REPEAT_ID,
      ROOT_LOOP_ID,
      context('invocation', '/offers'),
      [use],
    );
    const current = { ...childTemplateCurrent(), readiness: 'STALE' };
    const projection = projectStructuralAuthoring({
      designDsl: designWithChildren([outer]),
      staticSchema: rootSchema(),
      staticSchemas: [offerSchema()],
      templateCatalog: [templateCatalogEntry(OFFER_TEMPLATE_ID, 'offer', 'v1', 'READY')],
      templateCurrents: [current],
    });

    expect(projection.nodeStates[TEMPLATE_USE_NODE_ID]).toMatchObject({
      kind: 'templateUse',
      authoringState: 'NEEDS_REPAIR',
      fillTargets: [],
      problems: ['TEMPLATE_CURRENT_DRIFT'],
    });
  });

  it('rejects primitive, array, omitted-pointer and dynamic-parent TemplateUse selectors explicitly', () => {
    const cases: readonly [string, Readonly<Record<string, unknown>>, string][] = [
      ['primitive', {
        kind: 'context', domain: { kind: 'invocation' }, pointer: '/title', contextAbsentPolicy: 'ERROR',
      }, 'PRIMITIVE_CONTEXT_SELECTOR'],
      ['array', {
        kind: 'context', domain: { kind: 'invocation' }, pointer: '/offers', contextAbsentPolicy: 'ERROR',
      }, 'ARRAY_CONTEXT_SELECTOR'],
      ['omitted-pointer', {
        kind: 'context', domain: { kind: 'invocation' }, contextAbsentPolicy: 'ERROR',
      }, 'CONTEXT_POINTER_REQUIRED'],
      ['dynamic-parent', {
        kind: 'context', domain: { kind: 'parent' }, pointer: '', contextAbsentPolicy: 'ERROR',
      }, 'DYNAMIC_CONTEXT_DOMAIN'],
      ['primitive-adapter', {
        kind: 'value', value: { value: 'hello' },
      }, 'PRIMITIVE_VALUE_ADAPTER_FORBIDDEN'],
    ];

    for (const [, selector, expectedCode] of cases) {
      const projection = projectStructuralAuthoring({
        designDsl: designWithChildren([templateUseNode(OFFER_TEMPLATE_ID, selector)]),
        staticSchema: rootSchema(),
        staticSchemas: [offerSchema()],
        templateCatalog: [templateCatalogEntry(OFFER_TEMPLATE_ID, 'offer', 'v1', 'READY')],
        templateCurrents: [childTemplateCurrent()],
      });
      expect(projection.nodeStates[TEMPLATE_USE_NODE_ID]).toMatchObject({
        kind: 'templateUse',
        authoringState: 'INVALID',
        problems: [expectedCode],
      });
    }
  });
});

function designWithChildren(
  children: readonly Record<string, unknown>[],
  definitions: readonly Record<string, unknown>[] = [],
): Record<string, unknown> {
  return {
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: '结构组合',
    definitions: [...definitions],
    designRoot: {
      nodeId: '00000000-0000-4000-8000-000000000001',
      kind: 'canvas',
      widthMm: 100,
      heightMm: 100,
      bindings: [],
      children: [...children],
    },
  };
}

function repeatNode(
  nodeId: string,
  loopId: string,
  items: Record<string, unknown>,
  children: readonly Record<string, unknown>[] = [leaf('00000000-0000-4000-8000-000000000401')],
): Record<string, unknown> {
  return {
    nodeId,
    kind: 'repeat',
    loopId,
    items,
    absentPolicy: 'EMPTY',
    itemLayout: { kind: 'STACK', direction: 'ROW', gapMm: 0 },
    instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
    placement: {
      type: 'ABSOLUTE', xMm: 0, yMm: 0,
      widthMode: 'FIXED', widthMm: 20,
      heightMode: 'FIXED', heightMm: 20,
    },
    bindings: [],
    children: [...children],
  };
}

function conditionalNode(
  nodeId: string,
  condition: Record<string, unknown>,
): Record<string, unknown> {
  return {
    nodeId,
    kind: 'conditional',
    condition,
    absentPolicy: 'FALSE',
    placement: {
      type: 'PACK', widthMode: 'FIXED', widthMm: 10,
      heightMode: 'FIXED', heightMm: 5,
    },
    bindings: [],
    children: [leaf('00000000-0000-4000-8000-000000000402')],
  };
}

function templateUseNode(
  templateId: string,
  contextSelector: Readonly<Record<string, unknown>>,
  fills: readonly Readonly<Record<string, unknown>>[] = [],
): Record<string, unknown> {
  return {
    nodeId: TEMPLATE_USE_NODE_ID,
    kind: 'templateUse',
    useId: TEMPLATE_USE_ID,
    templateRef: { templateId },
    contextSelector,
    fills: [...fills],
    placement: {
      type: 'PACK', widthMode: 'FIXED', widthMm: 20,
      heightMode: 'FIXED', heightMm: 10,
    },
    bindings: [],
  };
}

function leaf(nodeId: string): Record<string, unknown> {
  return {
    nodeId,
    kind: 'rect',
    bindings: [],
    placement: {
      type: 'PACK', widthMode: 'FIXED', widthMm: 10,
      heightMode: 'FIXED', heightMm: 5,
    },
    fill: { color: '#000000FF' },
  };
}

function context(
  domain: 'invocation' | { readonly kind: 'loop'; readonly loopId: string },
  pointer: string,
): Record<string, unknown> {
  return { kind: 'context', domain, pointer };
}

function rootSchema(): StaticSnapshot {
  return schema('root', 'v1', [
    {
      fieldKey: 'tags',
      required: false,
      value: { type: 'array', items: { type: 'text' } },
    },
    {
      fieldKey: 'offers',
      required: true,
      value: {
        type: 'array',
        items: { type: 'reference', ref: { schemaKey: 'offer', versionTag: 'v1' } },
      },
    },
    { fieldKey: 'show', required: false, value: { type: 'boolean' } },
    { fieldKey: 'title', required: true, value: { type: 'text' } },
  ]);
}

function rootSchemaWithOfferReference(): StaticSnapshot {
  const root = rootSchema();
  return schema(root.schemaKey, root.versionTag, [
    ...root.definition.fields,
    {
      fieldKey: 'featuredOffer',
      displayName: '主推优惠',
      required: false,
      value: { type: 'reference', ref: { schemaKey: 'offer', versionTag: 'v1' } },
    },
  ]);
}

function offerSchema(): StaticSnapshot {
  return schema('offer', 'v1', [
    { fieldKey: 'name', required: true, value: { type: 'text' } },
    {
      fieldKey: 'badges',
      required: false,
      value: { type: 'array', items: { type: 'text' } },
    },
    { fieldKey: 'featured', required: false, value: { type: 'boolean' } },
  ]);
}

function templateCatalogEntry(
  templateId: string,
  schemaKey: string,
  versionTag: string,
  readiness: 'READY' | 'INVALID' | 'STALE',
): Readonly<Record<string, unknown>> {
  return {
    templateId,
    displayName: templateId === OFFER_TEMPLATE_ID ? '优惠卡' : '其他模板',
    staticSchema: { schemaKey, versionTag },
    revision: 3,
    readiness,
    updatedAt: '2026-09-03T00:00:00Z',
  };
}

function childTemplateCurrent(
  extraDefinitions: readonly Record<string, unknown>[] = [],
): Readonly<Record<string, unknown>> {
  return {
    templateId: OFFER_TEMPLATE_ID,
    disclosure: 'READABLE',
    revision: 3,
    staticSchema: { schemaKey: 'offer', versionTag: 'v1' },
    contentHash: 'a'.repeat(64),
    readiness: 'READY',
    designDsl: designWithChildren([], [
      {
        definitionId: CHILD_PUBLIC_ID,
        kind: 'custom',
        displayName: '商品名',
        exposure: 'PUBLIC',
        valueType: 'text',
        defaultValue: '',
      },
      {
        definitionId: '00000000-0000-4000-8000-000000000602',
        kind: 'custom',
        displayName: '内部开关',
        exposure: 'PRIVATE',
        valueType: 'boolean',
        defaultValue: false,
      },
      ...extraDefinitions,
    ]),
  };
}

function schema(
  schemaKey: string,
  versionTag: string,
  fields: StaticSnapshot['definition']['fields'],
): StaticSnapshot {
  return {
    schemaKey,
    versionTag,
    origin: 'DRAFT',
    sourceDraftRevision: 1,
    definition: {
      dslVersion: 'renderweave-schema/1.0',
      displayName: schemaKey,
      fields: [...fields],
    },
    compilerVersion: 'schema-compiler/1',
    releaseNote: null,
    referenceDepth: 1,
    publishedAt: '2026-09-03T00:00:00Z',
  };
}
