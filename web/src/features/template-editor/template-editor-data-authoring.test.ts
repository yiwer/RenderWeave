import { describe, expect, it } from 'vitest';

import type { StaticSnapshot } from '../schema-studio/lossless-api';
import { createStructuredEditorSession } from './template-editor-session';
import {
  executeTemplateDataAuthoringCommand,
  projectBindableProperties,
  projectBindingSources,
  projectTemplateStaticSchema,
} from './template-editor-data-authoring';
import {
  canonicalStringifyWorkingValue,
  redoStructuredCommand,
  undoStructuredCommand,
} from './template-editor-session';

describe('Template Editor data authoring', () => {
  it('projects the permanent StaticSchema as flat exact fields with Chinese type and constraints', () => {
    const snapshot: StaticSnapshot = {
      schemaKey: 'price-card',
      versionTag: 'v7',
      origin: 'DRAFT',
      sourceDraftRevision: 12,
      compilerVersion: 'schema-compiler/1',
      releaseNote: null,
      referenceDepth: 1,
      publishedAt: '2026-09-03T00:00:00Z',
      definition: {
        dslVersion: 'renderweave-schema/1.0',
        displayName: '价签数据',
        fields: [
          {
            fieldKey: 'product/name',
            displayName: '商品名',
            required: true,
            value: {
              type: 'text',
              constraints: { minLength: 1, maxLength: 40, pattern: '^\\S' },
            },
          },
          {
            fieldKey: 'price',
            required: false,
            value: {
              type: 'decimal',
              constraints: { min: '0', exclusiveMax: '100000', multipleOf: '0.01' },
            },
          },
          {
            fieldKey: 'brand',
            displayName: '品牌',
            required: true,
            value: {
              type: 'reference',
              ref: { schemaKey: 'brand', versionTag: 'v2' },
            },
          },
        ],
      },
    };

    const projection = projectTemplateStaticSchema(snapshot);

    expect(projection.identity).toEqual({ schemaKey: 'price-card', versionTag: 'v7' });
    expect(projection.displayName).toBe('价签数据');
    expect(projection.fields.map((field) => ({
      fieldKey: field.fieldKey,
      pointer: field.pointer,
      typeLabel: field.typeLabel,
      required: field.required,
    }))).toEqual([
      { fieldKey: 'product/name', pointer: '/product~1name', typeLabel: '文本', required: true },
      { fieldKey: 'price', pointer: '/price', typeLabel: '数值', required: false },
      { fieldKey: 'brand', pointer: '/brand', typeLabel: '对象引用', required: true },
    ]);
    expect(projection.fields[0]?.constraintLabels).toEqual([
      '必填', '最少 1 个字符', '最多 40 个字符', '匹配模式 ^\\S',
    ]);
    expect(projection.fields[1]?.constraintLabels).toEqual([
      '可缺省', '不小于 0', '小于 100000', '步进 0.01',
    ]);
    expect(projection.fields[2]?.reference).toEqual({ schemaKey: 'brand', versionTag: 'v2' });
    expect(projection.fields[2]).not.toHaveProperty('children');
  });

  it('keeps an Array<StaticSchema> flat while exposing its exact reference for dialog detail', () => {
    const projection = projectTemplateStaticSchema(schemaSnapshot([{
      fieldKey: 'items',
      displayName: '商品列表',
      required: true,
      value: {
        type: 'array',
        constraints: { minItems: 1, maxItems: 12, uniqueItems: true },
        items: {
          type: 'reference',
          ref: { schemaKey: 'catalog-item', versionTag: 'v3' },
        },
      },
    }]));

    expect(projection.fields[0]).toMatchObject({
      fieldKey: 'items',
      typeLabel: '对象引用列表',
      constraintLabels: ['必填', '至少 1 项', '至多 12 项', '元素不可重复'],
      reference: { schemaKey: 'catalog-item', versionTag: 'v3' },
    });
    expect(projection.fields[0]).not.toHaveProperty('children');
  });

  it('keeps array-item constraints visible even when the array has no outer constraints', () => {
    const projection = projectTemplateStaticSchema(schemaSnapshot([{
      fieldKey: 'tags',
      displayName: '标签',
      required: true,
      value: {
        type: 'array',
        items: {
          type: 'text',
          constraints: { minLength: 2, maxLength: 12, pattern: '^[A-Z]' },
        },
      },
    }]));

    expect(projection.fields[0]?.constraintLabels).toEqual([
      '必填',
      '元素：最少 2 个字符',
      '元素：最多 12 个字符',
      '元素：匹配模式 ^[A-Z]',
    ]);
  });

  it('projects only materialized policy-authorized leaves into exact binding targets', () => {
    const properties = projectBindableProperties({
      nodeId: '10000000-0000-4000-8000-000000000001',
      kind: 'text',
      displayName: '标题',
      bindings: [],
      placement: {
        type: 'ABSOLUTE', xMm: 8, yMm: 9,
        widthMode: 'FIXED', widthMm: 42,
        heightMode: 'HUG_CONTENT',
      },
      runs: [{
        text: '静态标题',
        fontRef: { assetId: '80000000-0000-4000-8000-000000000001' },
        fontSizePt: 16,
        color: '#111111FF',
        letterSpacingPt: 0,
        decoration: 'NONE',
      }],
      writingMode: 'HORIZONTAL_TB',
      horizontalAlign: 'LEFT',
      verticalAlign: 'TOP',
      lineBreak: 'WORD',
      overflow: 'CLIP',
      fitMode: 'NONE',
    });

    const byPath = new Map(properties.map((property) => [property.propertyPath, property]));
    expect(byPath.get('runs[0].text')).toMatchObject({
      pattern: 'runs[*].text',
      valueType: 'text',
      targetPropertyRef: {
        rootPropertyId: 'runs',
        selectors: [{ kind: 'index', index: 0 }, { kind: 'member', name: 'text' }],
      },
    });
    expect(byPath.get('runs[0].fontRef')?.valueType).toBe('fontRef');
    expect(byPath.get('placement.widthMm')?.valueType).toBe('decimal');
    expect(byPath.has('fitMode')).toBe(false);
    expect(byPath.has('displayName')).toBe(false);
    expect(byPath.has('stroke.color')).toBe(false);
  });

  it('keeps binding source type, absence and lexical-domain eligibility explicit', () => {
    const snapshot = schemaSnapshot([
      { fieldKey: 'title', required: true, value: { type: 'text' } },
      { fieldKey: 'price', required: false, value: { type: 'decimal' } },
    ]);
    const invocationDefinitionId = '20000000-0000-4000-8000-000000000001';
    const loopDefinitionId = '20000000-0000-4000-8000-000000000002';
    const loopId = '40000000-0000-4000-8000-000000000001';
    const outsideNodeId = '10000000-0000-4000-8000-000000000002';
    const insideNodeId = '10000000-0000-4000-8000-000000000004';
    const designDsl = {
      definitions: [
        {
          definitionId: invocationDefinitionId,
          kind: 'custom',
          displayName: '标题覆盖',
          exposure: 'PRIVATE',
          valueType: 'text',
          defaultValue: '',
        },
        {
          definitionId: loopDefinitionId,
          kind: 'expression',
          displayName: '行标题',
          domain: { kind: 'loop', loopId },
          output: 'text',
          inputs: [],
          source: "'行'",
        },
      ],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001',
        kind: 'canvas',
        children: [
          { nodeId: outsideNodeId, kind: 'text', bindings: [], runs: [] },
          {
            nodeId: '10000000-0000-4000-8000-000000000003',
            kind: 'repeat',
            loopId,
            bindings: [],
            children: [
              { nodeId: insideNodeId, kind: 'text', bindings: [], runs: [] },
            ],
          },
        ],
      },
    };

    const outside = projectBindingSources(designDsl, snapshot, outsideNodeId, 'text');
    expect(source(outside, 'context:invocation:/title')).toMatchObject({
      state: 'available',
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    });
    expect(source(outside, 'context:invocation:/price')).toMatchObject({
      state: 'incompatible',
      reason: '类型为数值，目标需要文本',
    });
    expect(source(outside, `definition:${invocationDefinitionId}`)?.state).toBe('available');
    expect(source(outside, `definition:${loopDefinitionId}`)).toMatchObject({
      state: 'out-of-scope',
    });

    const price = projectBindingSources(designDsl, snapshot, outsideNodeId, 'decimal');
    expect(source(price, 'context:invocation:/price')).toMatchObject({
      state: 'may-be-absent',
      reason: '字段可缺省；请先用 Mapping 定义显式补全',
    });

    const inside = projectBindingSources(designDsl, snapshot, insideNodeId, 'text');
    expect(source(inside, `definition:${loopDefinitionId}`)?.state).toBe('available');
  });

  it('projects nested reference leaves and exact Repeat item-context fields', () => {
    const root = schemaSnapshot([
      {
        fieldKey: 'featured', displayName: '主商品', required: true,
        value: { type: 'reference', ref: { schemaKey: 'catalog-item', versionTag: 'v3' } },
      },
      {
        fieldKey: 'products', displayName: '商品列表', required: true,
        value: {
          type: 'array',
          items: { type: 'reference', ref: { schemaKey: 'catalog-item', versionTag: 'v3' } },
        },
      },
    ]);
    const item = schemaSnapshotWithIdentity('catalog-item', 'v3', [
      { fieldKey: 'name', displayName: '名称', required: true, value: { type: 'text' } },
      { fieldKey: 'subtitle', displayName: '副标题', required: false, value: { type: 'text' } },
      {
        fieldKey: 'meta', displayName: '元数据', required: true,
        value: { type: 'reference', ref: { schemaKey: 'catalog-meta', versionTag: 'v1' } },
      },
    ]);
    const meta = schemaSnapshotWithIdentity('catalog-meta', 'v1', [
      { fieldKey: 'code', displayName: '代码', required: true, value: { type: 'text' } },
    ]);
    const loopId = '40000000-0000-4000-8000-000000000010';
    const nodeId = '10000000-0000-4000-8000-000000000010';
    const designDsl = {
      definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001', kind: 'canvas',
        children: [{
          nodeId: '10000000-0000-4000-8000-000000000009', kind: 'repeat', loopId,
          items: { kind: 'context', domain: 'invocation', pointer: '/products' },
          children: [{ nodeId, kind: 'text', bindings: [], runs: [] }],
        }],
      },
    };

    const sources = projectBindingSources(designDsl, root, nodeId, 'text', [item, meta]);

    expect(source(sources, 'context:invocation:/featured/name')).toMatchObject({
      state: 'available',
      source: { kind: 'context', domain: 'invocation', pointer: '/featured/name' },
    });
    expect(source(sources, 'context:invocation:/featured/meta/code')?.state).toBe('available');
    expect(source(sources, `context:loop:${loopId}:/name`)).toMatchObject({
      state: 'available',
      source: { kind: 'context', domain: { kind: 'loop', loopId }, pointer: '/name' },
    });
    expect(source(sources, `context:loop:${loopId}:/subtitle`)).toMatchObject({
      state: 'may-be-absent',
    });
    expect(source(sources, `context:loop:${loopId}:/meta/code`)?.state).toBe('available');
  });

  it('projects scalar Repeat items through the exact system-basic item context', () => {
    const loopId = '40000000-0000-4000-8000-000000000011';
    const nodeId = '10000000-0000-4000-8000-000000000011';
    const designDsl = {
      definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001', kind: 'canvas',
        children: [{
          nodeId: '10000000-0000-4000-8000-000000000012', kind: 'repeat', loopId,
          items: { kind: 'literal', valueType: { type: 'list', items: 'text' }, value: ['A'] },
          children: [{ nodeId, kind: 'text', bindings: [], runs: [] }],
        }],
      },
    };

    const textSources = projectBindingSources(designDsl, schemaSnapshot([]), nodeId, 'text');
    expect(source(textSources, `context:loop:${loopId}:/value`)).toMatchObject({
      state: 'available', valueType: 'text',
    });
    expect(source(textSources, `context:loop:${loopId}:/index`)?.state).toBe('incompatible');
    const decimalSources = projectBindingSources(designDsl, schemaSnapshot([]), nodeId, 'decimal');
    expect(source(decimalSources, `context:loop:${loopId}:/index`)?.state).toBe('available');
    expect(source(decimalSources, `loop-index:${loopId}`)?.state).toBe('available');
  });

  it('creates one exact CustomDefinition as an undoable semantic command', () => {
    const definitionId = '20000000-0000-4000-8000-000000000009';
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签',
      definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001',
        kind: 'canvas', widthMm: 210, heightMm: 297, bindings: [], children: [],
      },
    });

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'custom',
        displayName: '门店名称',
        exposure: 'PUBLIC',
        valueType: 'text',
        defaultValue: '默认门店',
      },
    }, { createUuid: () => definitionId });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error('expected applied command');
    expect(result.definitionId).toBe(definitionId);
    expect(result.session.workingCopy.designDsl.definitions).toEqual([{
      definitionId,
      kind: 'custom',
      displayName: '门店名称',
      exposure: 'PUBLIC',
      valueType: 'text',
      defaultValue: '默认门店',
    }]);
    expect(result.session.history.past.at(-1)?.kind).toBe('replace-definition');
    expect(undoStructuredCommand(result.session).workingCopy.designDsl.definitions).toEqual([]);
    expect(redoStructuredCommand(undoStructuredCommand(result.session)).workingCopy.designDsl.definitions)
      .toEqual(result.session.workingCopy.designDsl.definitions);
  });

  it('updates one existing definition without changing its identity', () => {
    const definitionId = '20000000-0000-4000-8000-000000000009';
    const before = {
      definitionId,
      kind: 'custom',
      displayName: '门店名称',
      exposure: 'PUBLIC',
      valueType: 'text',
      defaultValue: '默认门店',
    };
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签',
      definitions: [before],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001',
        kind: 'canvas', widthMm: 210, heightMm: 297, bindings: [], children: [],
      },
    });

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'update-definition',
      definitionId,
      definition: {
        kind: 'custom',
        displayName: '门店简称',
        exposure: 'PRIVATE',
        valueType: 'text',
        defaultValue: '默认简称',
      },
    });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error('expected applied command');
    expect(result.session.workingCopy.designDsl.definitions).toEqual([{
      definitionId,
      kind: 'custom',
      displayName: '门店简称',
      exposure: 'PRIVATE',
      valueType: 'text',
      defaultValue: '默认简称',
    }]);
    expect(result.session.history.past.at(-1)).toMatchObject({
      kind: 'replace-definition',
      definitionId,
      before,
    });
    expect(undoStructuredCommand(result.session).workingCopy.designDsl.definitions)
      .toEqual([before]);
  });

  it('admits exactly 512 definitions and rejects the prospective 513th definition', () => {
    const atBoundary = executeTemplateDataAuthoringCommand(
      sessionWithDefinitions(Array.from({ length: 511 }, (_, index) => customDefinition(index))),
      {
        operation: 'create-definition',
        definition: {
          kind: 'custom', displayName: '边界定义', exposure: 'PRIVATE',
          valueType: 'text', defaultValue: '值',
        },
      },
      { createUuid: () => '20000000-0000-4000-8000-000000000fff' },
    );
    expect(atBoundary).toMatchObject({ state: 'applied' });

    const overBoundarySession = sessionWithDefinitions(
      Array.from({ length: 512 }, (_, index) => customDefinition(index)),
    );
    const overBoundary = executeTemplateDataAuthoringCommand(overBoundarySession, {
      operation: 'create-definition',
      definition: {
        kind: 'custom', displayName: '超限定义', exposure: 'PRIVATE',
        valueType: 'text', defaultValue: '值',
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000fff' });

    expect(overBoundary).toMatchObject({
      state: 'rejected', code: 'DEFINITION_CAPACITY_EXCEEDED',
      session: overBoundarySession,
    });
  });

  it('checks the whole prospective definition set when updating at the 512 boundary', () => {
    const update = {
      operation: 'update-definition' as const,
      definitionId: customDefinition(0).definitionId as string,
      definition: {
        kind: 'custom', displayName: '已编辑', exposure: 'PRIVATE',
        valueType: 'text', defaultValue: '新值',
      },
    };
    expect(executeTemplateDataAuthoringCommand(
      sessionWithDefinitions(Array.from({ length: 512 }, (_, index) => customDefinition(index))),
      update,
    )).toMatchObject({ state: 'applied' });

    const overBoundarySession = sessionWithDefinitions(
      Array.from({ length: 513 }, (_, index) => customDefinition(index)),
    );
    expect(executeTemplateDataAuthoringCommand(overBoundarySession, update)).toMatchObject({
      state: 'rejected', code: 'DEFINITION_CAPACITY_EXCEEDED',
      session: overBoundarySession,
    });
  });

  it('creates an invocation MappingDefinition with closed sources and fallback', () => {
    const definitionId = '20000000-0000-4000-8000-00000000000a';
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签',
      definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001',
        kind: 'canvas', widthMm: 210, heightMm: 297, bindings: [], children: [],
      },
    });
    const definition = {
      kind: 'mapping',
      displayName: '标题补全',
      domain: 'invocation',
      output: 'text',
      input: { kind: 'context', domain: 'invocation', pointer: '/title' },
      cases: [{
        operator: 'IS_ABSENT',
        then: { kind: 'literal', valueType: 'text', value: '未命名' },
      }],
      otherwise: { kind: 'context', domain: 'invocation', pointer: '/title' },
    };

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition,
    }, { createUuid: () => definitionId });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error('expected applied mapping');
    expect(result.session.workingCopy.designDsl.definitions).toEqual([{
      definitionId,
      ...definition,
    }]);
    expect(undoStructuredCommand(result.session).workingCopy.designDsl.definitions).toEqual([]);
  });

  it('rejects a Mapping operand whose declared type differs from a literal input', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '错误比较', domain: 'invocation', output: 'text',
        input: { kind: 'literal', valueType: 'text', value: '一' },
        cases: [{
          operator: 'EQ',
          operand: { valueType: 'decimal', value: 1 },
          then: { kind: 'literal', valueType: 'text', value: '命中' },
        }],
        otherwise: { kind: 'literal', valueType: 'text', value: '未命中' },
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000021' });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_INPUT_MISMATCH', session: initial,
    });
  });

  it('rejects a Mapping operand whose declared type differs from a Definition input', () => {
    const sourceDefinition: Record<string, unknown> = {
      ...customDefinition(0),
      valueType: 'decimal',
      defaultValue: 1,
    };
    const initial = sessionWithDefinitions([sourceDefinition]);
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '错误定义比较', domain: 'invocation', output: 'text',
        input: { kind: 'definition', definitionId: sourceDefinition.definitionId },
        cases: [{
          operator: 'EQ',
          operand: { valueType: 'text', value: '一' },
          then: { kind: 'literal', valueType: 'text', value: '命中' },
        }],
        otherwise: { kind: 'literal', valueType: 'text', value: '未命中' },
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000022' });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_INPUT_MISMATCH', session: initial,
    });
  });

  it('rejects a Mapping operand that conflicts with an exact invocation field type', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '错误字段比较', domain: 'invocation', output: 'text',
        input: { kind: 'context', domain: 'invocation', pointer: '/price' },
        cases: [{
          operator: 'EQ',
          operand: { valueType: 'text', value: '一' },
          then: { kind: 'literal', valueType: 'text', value: '命中' },
        }],
        otherwise: { kind: 'literal', valueType: 'text', value: '未命中' },
      },
    }, {
      createUuid: () => '20000000-0000-4000-8000-000000000023',
      staticSchema: schemaSnapshot([
        { fieldKey: 'price', required: true, value: { type: 'decimal' } },
      ]),
    });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_INPUT_MISMATCH', session: initial,
    });
  });

  it('uses the available exact reference closure to prove a nested Mapping input type', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const root = schemaSnapshot([{
      fieldKey: 'brand', required: true,
      value: { type: 'reference', ref: { schemaKey: 'brand', versionTag: 'v2' } },
    }]);
    const brand = schemaSnapshotWithIdentity('brand', 'v2', [{
      fieldKey: 'name', required: true, value: { type: 'text' },
    }]);
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '错误嵌套比较', domain: 'invocation', output: 'text',
        input: { kind: 'context', domain: 'invocation', pointer: '/brand/name' },
        cases: [{
          operator: 'EQ',
          operand: { valueType: 'decimal', value: 1 },
          then: { kind: 'literal', valueType: 'text', value: '命中' },
        }],
        otherwise: { kind: 'literal', valueType: 'text', value: '未命中' },
      },
    }, {
      createUuid: () => '20000000-0000-4000-8000-000000000024',
      staticSchema: root,
      staticSchemas: [brand],
    });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_INPUT_MISMATCH', session: initial,
    });
  });

  it('rejects an exact context source that resolves to a non-value reference', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '对象不可作值', domain: 'invocation', output: 'text',
        input: { kind: 'context', domain: 'invocation', pointer: '/brand' },
        cases: [{
          operator: 'IS_ABSENT',
          then: { kind: 'literal', valueType: 'text', value: '缺失' },
        }],
        otherwise: { kind: 'literal', valueType: 'text', value: '存在' },
      },
    }, {
      createUuid: () => '20000000-0000-4000-8000-000000000029',
      staticSchema: schemaSnapshot([{
        fieldKey: 'brand', required: true,
        value: { type: 'reference', ref: { schemaKey: 'brand', versionTag: 'v2' } },
      }]),
    });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_SOURCE_TYPE_INVALID', session: initial,
    });
  });

  it('rejects a Mapping branch whose Definition result differs from its output', () => {
    const resultDefinition: Record<string, unknown> = {
      ...customDefinition(0),
      valueType: 'decimal',
      defaultValue: 1,
    };
    const initial = sessionWithDefinitions([resultDefinition]);
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '错误分支结果', domain: 'invocation', output: 'text',
        input: { kind: 'literal', valueType: 'text', value: '一' },
        cases: [{
          operator: 'IS_ABSENT',
          then: { kind: 'definition', definitionId: resultDefinition.definitionId },
        }],
        otherwise: { kind: 'literal', valueType: 'text', value: '未命中' },
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000025' });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_OUTPUT_MISMATCH', session: initial,
    });
  });

  it('rejects a known Mapping output mismatch on update without mutating history', () => {
    const resultDefinition: Record<string, unknown> = {
      ...customDefinition(0), valueType: 'decimal', defaultValue: 1,
    };
    const mapping = mappingDefinition(1);
    const initial = sessionWithDefinitions([resultDefinition, mapping]);
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'update-definition',
      definitionId: mapping.definitionId as string,
      definition: {
        kind: 'mapping', displayName: '更新后错误', domain: 'invocation', output: 'text',
        input: { kind: 'literal', valueType: 'text', value: '一' },
        cases: [{
          operator: 'IS_ABSENT',
          then: { kind: 'definition', definitionId: resultDefinition.definitionId },
        }],
        otherwise: { kind: 'literal', valueType: 'text', value: '未命中' },
      },
    });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_OUTPUT_MISMATCH', session: initial,
    });
    expect(initial.history.past).toEqual([]);
  });

  it('rejects a Mapping fallback whose exact context field differs from its output', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '错误默认结果', domain: 'invocation', output: 'text',
        input: { kind: 'literal', valueType: 'text', value: '一' },
        cases: [{
          operator: 'IS_ABSENT',
          then: { kind: 'literal', valueType: 'text', value: '命中' },
        }],
        otherwise: { kind: 'context', domain: 'invocation', pointer: '/price' },
      },
    }, {
      createUuid: () => '20000000-0000-4000-8000-000000000026',
      staticSchema: schemaSnapshot([
        { fieldKey: 'price', required: true, value: { type: 'decimal' } },
      ]),
    });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_OUTPUT_MISMATCH', session: initial,
    });
  });

  it('uses a Mapping loop domain to prove its exact scalar item-context type', () => {
    const loopId = '40000000-0000-4000-8000-000000000027';
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签', definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001', kind: 'canvas',
        widthMm: 210, heightMm: 297, bindings: [],
        children: [{
          nodeId: '10000000-0000-4000-8000-000000000027', kind: 'repeat', loopId,
          items: { kind: 'context', domain: 'invocation', pointer: '/prices' },
          placement: {
            type: 'ABSOLUTE', xMm: 0, yMm: 0,
            widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
          },
          absentPolicy: 'EMPTY',
          itemLayout: { kind: 'STACK', direction: 'ROW' },
          instanceLayout: { kind: 'STACK', direction: 'ROW' },
          bindings: [], children: [],
        }],
      },
    });
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '错误循环比较',
        domain: { kind: 'loop', loopId }, output: 'text',
        input: { kind: 'context', domain: { kind: 'loop', loopId }, pointer: '/value' },
        cases: [{
          operator: 'EQ',
          operand: { valueType: 'text', value: '一' },
          then: { kind: 'literal', valueType: 'text', value: '命中' },
        }],
        otherwise: { kind: 'literal', valueType: 'text', value: '未命中' },
      },
    }, {
      createUuid: () => '20000000-0000-4000-8000-000000000027',
      staticSchema: schemaSnapshot([{
        fieldKey: 'prices', required: true,
        value: { type: 'array', items: { type: 'decimal' } },
      }]),
    });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_INPUT_MISMATCH', session: initial,
    });
  });

  it('uses an exact Array<StaticSchema> closure to prove a Mapping loop field type', () => {
    const loopId = '40000000-0000-4000-8000-000000000028';
    const initial = repeatDomainSession(loopId, {
      kind: 'context', domain: 'invocation', pointer: '/products',
    });
    const root = schemaSnapshot([{
      fieldKey: 'products', required: true,
      value: {
        type: 'array',
        items: { type: 'reference', ref: { schemaKey: 'product', versionTag: 'v1' } },
      },
    }]);
    const product = schemaSnapshotWithIdentity('product', 'v1', [{
      fieldKey: 'price', required: true, value: { type: 'decimal' },
    }]);
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '错误商品比较',
        domain: { kind: 'loop', loopId }, output: 'text',
        input: { kind: 'context', domain: { kind: 'loop', loopId }, pointer: '/price' },
        cases: [{
          operator: 'EQ',
          operand: { valueType: 'text', value: '一' },
          then: { kind: 'literal', valueType: 'text', value: '命中' },
        }],
        otherwise: { kind: 'literal', valueType: 'text', value: '未命中' },
      },
    }, {
      createUuid: () => '20000000-0000-4000-8000-000000000028',
      staticSchema: root,
      staticSchemas: [product],
    });

    expect(result).toMatchObject({
      state: 'rejected', code: 'DEFINITION_INPUT_MISMATCH', session: initial,
    });
  });

  it('rejects a MappingDefinition with more than 256 cases', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const mappingCase = {
      operator: 'IS_ABSENT',
      then: { kind: 'literal', valueType: 'text', value: '未命名' },
    };

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '分支过多', domain: 'invocation', output: 'text',
        input: { kind: 'context', domain: 'invocation', pointer: '/title' },
        cases: Array.from({ length: 257 }, () => mappingCase),
        otherwise: { kind: 'literal', valueType: 'text', value: '默认值' },
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000ffc' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_CAPACITY_EXCEEDED' });
    expect(result.session).toBe(initial);
  });

  it('rejects a new MappingDefinition when prospective cases exceed 8192', () => {
    const mappingCase = {
      operator: 'IS_ABSENT',
      then: { kind: 'literal', valueType: 'text', value: '未命名' },
    };
    const initial = sessionWithDefinitions(Array.from(
      { length: 32 },
      (_, index) => mappingDefinition(index, {
        cases: Array.from({ length: 256 }, () => mappingCase),
      }),
    ));

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '越过总分支上限', domain: 'invocation', output: 'text',
        input: { kind: 'context', domain: 'invocation', pointer: '/title' },
        cases: [mappingCase],
        otherwise: { kind: 'literal', valueType: 'text', value: '默认值' },
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000ffb' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_CAPACITY_EXCEEDED' });
    expect(result.session).toBe(initial);
  });

  it('creates an ExpressionDefinition whose capability stays inside expression inputs', () => {
    const definitionId = '20000000-0000-4000-8000-00000000000b';
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签',
      definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001',
        kind: 'canvas', widthMm: 210, heightMm: 297, bindings: [], children: [],
      },
    });
    const definition = {
      kind: 'expression',
      displayName: '当前日期',
      domain: 'invocation',
      output: 'date',
      inputs: [{
        alias: 'today',
        source: { kind: 'capability', capability: 'CLOCK', operation: 'UTC_DATE' },
      }],
      source: 'input.today',
    };

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition', definition,
    }, { createUuid: () => definitionId });

    expect(result.state).toBe('applied');
    if (result.state !== 'applied') throw new Error('expected applied expression');
    expect(result.session.workingCopy.designDsl.definitions).toEqual([{
      definitionId,
      ...definition,
    }]);
  });

  it('rejects an ExpressionDefinition with more than 32 inputs', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const inputs = Array.from({ length: 33 }, (_, index) => ({
      alias: `value${index}`,
      source: { kind: 'context', domain: 'invocation', pointer: `/value${index}` },
    }));

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '输入过多', domain: 'invocation', output: 'text',
        inputs,
        source: inputs.map((input) => `input.${input.alias}`).join(' + '),
      },
    }, { createUuid: () => '20000000-0000-4000-8000-00000000001d' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_CAPACITY_EXCEEDED' });
    expect(result.session).toBe(initial);
  });

  it('rejects a new ExpressionDefinition when prospective source bytes exceed 1 MiB', () => {
    const source = `'${'a'.repeat(65_534)}'`;
    const definitions = Array.from({ length: 16 }, (_, index) => expressionDefinition(index, {
      source,
    }));
    const initial = sessionWithDefinitions(definitions);

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '越过总源码上限', domain: 'invocation', output: 'text',
        inputs: [], source: "'x'",
      },
    }, { createUuid: () => '20000000-0000-4000-8000-00000000001e' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_CAPACITY_EXCEEDED' });
    expect(result.session).toBe(initial);
  });

  it('rejects a new ExpressionDefinition when prospective inputs exceed 4096', () => {
    const inputs = Array.from({ length: 32 }, (_, index) => ({
      alias: `value${index}`,
      source: { kind: 'context', domain: 'invocation', pointer: `/value${index}` },
    }));
    const source = inputs.map((input) => `input.${input.alias}`).join(' + ');
    const initial = sessionWithDefinitions(Array.from(
      { length: 128 },
      (_, index) => expressionDefinition(index, { inputs, source }),
    ));

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '越过总输入上限', domain: 'invocation', output: 'text',
        inputs: [{
          alias: 'value',
          source: { kind: 'context', domain: 'invocation', pointer: '/value' },
        }],
        source: 'input.value',
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000fff' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_CAPACITY_EXCEEDED' });
    expect(result.session).toBe(initial);
  });

  it('rejects a new ExpressionDefinition when prospective AST nodes exceed 65536', () => {
    const source = `-1${'+1'.repeat(2_047)}`;
    const initial = sessionWithDefinitions(Array.from(
      { length: 16 },
      (_, index) => expressionDefinition(index, { source }),
    ));

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '越过总 AST 上限', domain: 'invocation', output: 'text',
        inputs: [], source: "'x'",
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000ffe' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_CAPACITY_EXCEEDED' });
    expect(result.session).toBe(initial);
  });

  it('fails closed when a conservative parse cannot count a prospective AST', () => {
    const maxAstSource = `-1${'+1'.repeat(2_047)}`;
    const conservativeDepthSource = `${'('.repeat(257)}${maxAstSource}${')'.repeat(257)}`;
    const initial = sessionWithDefinitions([
      expressionDefinition(0, { source: conservativeDepthSource }),
      ...Array.from(
        { length: 15 },
        (_, index) => expressionDefinition(index + 1, { source: maxAstSource }),
      ),
    ]);

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '越过总 AST 上限', domain: 'invocation', output: 'text',
        inputs: [], source: "'x'",
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000ff8' });

    expect(result).toMatchObject({ state: 'rejected' });
    expect(result.session).toBe(initial);
  });

  it('rejects an explicit round scale above 64', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '舍入过深', domain: 'invocation', output: 'decimal',
        inputs: [], source: "round(1, 65, 'HALF_UP')",
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000ffd' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_EXPRESSION_INVALID' });
    expect(result.session).toBe(initial);
  });

  it.each([
    "divide(1, 1, 65, 'HALF_UP')",
    "formatDecimal(1, 65, 2, 'HALF_UP')",
    "formatDecimal(1, 2, 65, 'HALF_UP')",
  ])('rejects every explicit function scale above 64: %s', (source) => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '舍入过深', domain: 'invocation', output: 'decimal',
        inputs: [], source,
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000ffa' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_EXPRESSION_INVALID' });
    expect(result.session).toBe(initial);
  });

  it.each([
    "round(1, (64), 'HALF_UP')",
    "divide(1, 1, 64, 'HALF_UP')",
    "formatDecimal(1, 64, 64, 'HALF_UP')",
  ])('accepts the exact explicit function scale limit: %s', (source) => {
    const result = executeTemplateDataAuthoringCommand(
      bindingSession('10000000-0000-4000-8000-000000000002'),
      {
        operation: 'create-definition',
        definition: {
          kind: 'expression', displayName: '舍入边界', domain: 'invocation', output: 'decimal',
          inputs: [], source,
        },
      },
      { createUuid: () => '20000000-0000-4000-8000-000000000ff9' },
    );

    expect(result).toMatchObject({ state: 'applied' });
  });

  it('rejects Expression source that the renderweave-expression/1.0 grammar cannot parse', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '残缺表达式', domain: 'invocation', output: 'text',
        inputs: [{
          alias: 'value',
          source: { kind: 'context', domain: 'invocation', pointer: '/title' },
        }],
        source: 'input.value +',
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000017' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_EXPRESSION_INVALID' });
    expect(result.session).toBe(initial);
  });

  it('rejects a Definition reference cycle before mutating the editor session', () => {
    const definitionId = '20000000-0000-4000-8000-000000000018';
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '自引用表达式', domain: 'invocation', output: 'text',
        inputs: [{
          alias: 'self',
          source: { kind: 'definition', definitionId },
        }],
        source: 'input.self',
      },
    }, { createUuid: () => definitionId });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_GRAPH_INVALID' });
    expect(result.session).toBe(initial);
  });

  it('rejects an update that closes an indirect Definition reference cycle', () => {
    const firstId = '20000000-0000-4000-8000-000000000019';
    const secondId = '20000000-0000-4000-8000-00000000001a';
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签',
      definitions: [{
        definitionId: firstId, kind: 'expression', displayName: '第一项',
        domain: 'invocation', output: 'text',
        inputs: [{ alias: 'second', source: { kind: 'definition', definitionId: secondId } }],
        source: 'input.second',
      }, {
        definitionId: secondId, kind: 'expression', displayName: '第二项',
        domain: 'invocation', output: 'text', inputs: [], source: "'基础值'",
      }],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001',
        kind: 'canvas', widthMm: 210, heightMm: 297, bindings: [], children: [],
      },
    });

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'update-definition', definitionId: secondId,
      definition: {
        kind: 'expression', displayName: '第二项', domain: 'invocation', output: 'text',
        inputs: [{ alias: 'first', source: { kind: 'definition', definitionId: firstId } }],
        source: 'input.first',
      },
    });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_GRAPH_INVALID' });
    expect(result.session).toBe(initial);
  });

  it('rejects an Expression input alias that is not used by the parsed source', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '未使用输入', domain: 'invocation', output: 'text',
        inputs: [{
          alias: 'unused',
          source: { kind: 'context', domain: 'invocation', pointer: '/title' },
        }],
        source: "'固定值'",
      },
    }, { createUuid: () => '20000000-0000-4000-8000-00000000001b' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_EXPRESSION_INVALID' });
    expect(result.session).toBe(initial);
  });

  it('preserves valid Expression source whitespace exactly in the semantic command', () => {
    const definitionId = '20000000-0000-4000-8000-00000000001c';
    const source = '\n  input.value  \r\n';
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '精确源码', domain: 'invocation', output: 'text',
        inputs: [{
          alias: 'value',
          source: { kind: 'context', domain: 'invocation', pointer: '/title' },
        }],
        source,
      },
    }, { createUuid: () => definitionId });

    expect(result).toMatchObject({ state: 'applied' });
    if (result.state !== 'applied') throw new Error('expected exact expression source to apply');
    expect(result.session.workingCopy.designDsl.definitions).toContainEqual(
      expect.objectContaining({ definitionId, source }),
    );
  });

  it('rejects a definition whose loop domain is not authored in the current tree', () => {
    const missingLoopId = '40000000-0000-4000-8000-000000000099';
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签',
      definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001',
        kind: 'canvas', widthMm: 210, heightMm: 297, bindings: [], children: [],
      },
    });

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '游离循环值',
        domain: { kind: 'loop', loopId: missingLoopId },
        output: 'text', inputs: [], source: "'值'",
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000099' });

    expect(result).toMatchObject({
      state: 'rejected',
      code: 'DEFINITION_DOMAIN_OUT_OF_SCOPE',
      session: initial,
    });
    expect(initial.history.past).toEqual([]);
  });

  it('rejects lexically unreachable Definition sources before mutating the session', () => {
    const loopId = '40000000-0000-4000-8000-000000000012';
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签',
      definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001', kind: 'canvas', bindings: [],
        widthMm: 210, heightMm: 297,
        children: [{
          nodeId: '10000000-0000-4000-8000-000000000013', kind: 'repeat', loopId,
          items: { kind: 'literal', valueType: { type: 'list', items: 'text' }, value: [] },
          bindings: [], children: [],
        }],
      },
    });

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '越界值', domain: 'invocation', output: 'text',
        inputs: [{
          alias: 'item',
          source: { kind: 'context', domain: { kind: 'loop', loopId }, pointer: '/value' },
        }],
        source: 'input.item',
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000012' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_SOURCE_OUT_OF_SCOPE' });
    expect(result.session).toBe(initial);
  });

  it('accepts a context pointer whose decoded RFC 6901 UTF-8 value is within 1024 bytes', () => {
    const pointer = `/${'~0'.repeat(512)}`;
    expect(pointer.length).toBeGreaterThan(1024);
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '边界指针', domain: 'invocation', output: 'text',
        inputs: [{
          alias: 'value',
          source: { kind: 'context', domain: 'invocation', pointer },
        }],
        source: 'input.value',
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000014' });

    expect(result).toMatchObject({ state: 'applied' });
  });

  it.each([
    [`/${'界'.repeat(341)}`, 'applied'],
    [`/${'界'.repeat(342)}`, 'rejected'],
  ] as const)('counts decoded multibyte context pointers by UTF-8 bytes (%s)', (pointer, state) => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');
    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '多字节指针', domain: 'invocation', output: 'text',
        inputs: [{
          alias: 'value',
          source: { kind: 'context', domain: 'invocation', pointer },
        }],
        source: 'input.value',
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000015' });

    expect(result.state).toBe(state);
    if (state === 'rejected') {
      expect(result).toMatchObject({ code: 'DEFINITION_INPUT_INVALID', session: initial });
    }
  });

  it.each([
    ['date', '2026/09/03'],
    ['time', '08:30'],
    ['color', '#aabbccff'],
    ['imageRef', { assetId: 'not-a-v4-uuid' }],
  ])('rejects a non-canonical %s Custom default', (valueType, defaultValue) => {
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签', definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001', kind: 'canvas',
        widthMm: 210, heightMm: 297, bindings: [], children: [],
      },
    });

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'custom', displayName: '非法默认值', exposure: 'PRIVATE',
        valueType, defaultValue,
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000013' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_DEFAULT_INVALID' });
    expect(result.session).toBe(initial);
  });

  it('fails closed for enum ValueType because v1 registers no global enum catalog', () => {
    const initial = bindingSession('10000000-0000-4000-8000-000000000002');

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-definition',
      definition: {
        kind: 'custom', displayName: '未知枚举', exposure: 'PRIVATE',
        valueType: { type: 'enum', catalogId: 'catalog-that-does-not-exist' },
        defaultValue: 'ANY',
      },
    }, { createUuid: () => '20000000-0000-4000-8000-000000000016' });

    expect(result).toMatchObject({ state: 'rejected', code: 'DEFINITION_DEFAULT_INVALID' });
    expect(result.session).toBe(initial);
  });

  it('creates and removes an exact node-local Binding through compact undoable commands', () => {
    const bindingId = '30000000-0000-4000-8000-000000000009';
    const nodeId = '10000000-0000-4000-8000-000000000002';
    const initial = sessionWithDesign({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: '价签',
      definitions: [],
      designRoot: {
        nodeId: '10000000-0000-4000-8000-000000000001',
        kind: 'canvas', widthMm: 210, heightMm: 297, bindings: [],
        children: [{
          nodeId,
          kind: 'text',
          bindings: [],
          placement: {
            type: 'ABSOLUTE', xMm: 4, yMm: 5,
            widthMode: 'FIXED', widthMm: 50,
            heightMode: 'HUG_CONTENT',
          },
          runs: [{
            text: '静态标题',
            fontRef: { assetId: '80000000-0000-4000-8000-000000000001' },
            fontSizePt: 14,
            color: '#111111FF',
            letterSpacingPt: 0,
            decoration: 'NONE',
          }],
        }],
      },
    });
    const snapshot = schemaSnapshot([
      { fieldKey: 'title', displayName: '标题', required: true, value: { type: 'text' } },
    ]);

    const created = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-binding',
      nodeId,
      propertyPath: 'runs[0].text',
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    }, { createUuid: () => bindingId, staticSchema: snapshot });

    expect(created.state).toBe('applied');
    if (created.state !== 'applied') throw new Error('expected applied binding');
    expect(created.bindingId).toBe(bindingId);
    expect(bindingOf(created.session, nodeId)).toEqual({
      bindingId,
      targetPropertyRef: {
        rootPropertyId: 'runs',
        selectors: [{ kind: 'index', index: 0 }, { kind: 'member', name: 'text' }],
      },
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    });
    expect(created.session.history.past.at(-1)?.kind).toBe('replace-node-binding');
    expect(bindingOf(undoStructuredCommand(created.session), nodeId)).toBeUndefined();
    expect(bindingOf(redoStructuredCommand(undoStructuredCommand(created.session)), nodeId))
      .toEqual(bindingOf(created.session, nodeId));

    const removed = executeTemplateDataAuthoringCommand(created.session, {
      operation: 'remove-binding', nodeId, bindingId,
    });
    expect(removed.state).toBe('applied');
    if (removed.state !== 'applied') throw new Error('expected removed binding');
    expect(bindingOf(removed.session, nodeId)).toBeUndefined();
    expect(bindingOf(undoStructuredCommand(removed.session), nodeId))
      .toEqual(bindingOf(created.session, nodeId));
  });

  it('admits exactly 64 Bindings on one node and rejects the prospective 65th', () => {
    const nodeId = '10000000-0000-4000-8000-000000000002';
    const snapshot = schemaSnapshot([
      { fieldKey: 'title', required: true, value: { type: 'text' } },
    ]);
    const create = (session: ReturnType<typeof bindingSession>, bindingId: string) => (
      executeTemplateDataAuthoringCommand(session, {
        operation: 'create-binding', nodeId, propertyPath: 'runs[0].text',
        source: { kind: 'context', domain: 'invocation', pointer: '/title' },
      }, { staticSchema: snapshot, createUuid: () => bindingId })
    );

    expect(create(
      bindingSessionWithExistingBindings(nodeId, 63),
      '30000000-0000-4000-8000-000000000ffe',
    )).toMatchObject({ state: 'applied' });

    const overBoundarySession = bindingSessionWithExistingBindings(nodeId, 64);
    expect(create(
      overBoundarySession,
      '30000000-0000-4000-8000-000000000fff',
    )).toMatchObject({
      state: 'rejected', code: 'BINDING_CAPACITY_EXCEEDED',
      session: overBoundarySession,
    });
  });

  it('admits exactly 4096 Bindings in the tree and rejects the prospective 4097th', () => {
    const nodeId = '10000000-0000-4000-8000-000000000002';
    const snapshot = schemaSnapshot([
      { fieldKey: 'title', required: true, value: { type: 'text' } },
    ]);
    const create = (session: ReturnType<typeof bindingSession>, bindingId: string) => (
      executeTemplateDataAuthoringCommand(session, {
        operation: 'create-binding', nodeId, propertyPath: 'runs[0].text',
        source: { kind: 'context', domain: 'invocation', pointer: '/title' },
      }, { staticSchema: snapshot, createUuid: () => bindingId })
    );

    expect(create(
      bindingSessionWithTotalBindings(nodeId, 4_095),
      '30000000-0000-4000-8000-00000000fffe',
    )).toMatchObject({ state: 'applied' });

    const overBoundarySession = bindingSessionWithTotalBindings(nodeId, 4_096);
    expect(create(
      overBoundarySession,
      '30000000-0000-4000-8000-00000000ffff',
    )).toMatchObject({
      state: 'rejected', code: 'BINDING_CAPACITY_EXCEEDED',
      session: overBoundarySession,
    });
  });

  it('detects an existing Binding target by canonical selector identity, not authored selector order', () => {
    const nodeId = '10000000-0000-4000-8000-000000000002';
    const seed = bindingSession(nodeId);
    const designDsl = structuredClone(seed.workingCopy.designDsl);
    const root = designDsl.designRoot as Record<string, unknown>;
    const node = (root.children as Record<string, unknown>[])[0] as Record<string, unknown>;
    node.bindings = [{
      bindingId: '30000000-0000-4000-8000-000000000020',
      targetPropertyRef: {
        rootPropertyId: 'runs',
        selectors: [
          { kind: 'member', name: 'text' },
          { kind: 'index', index: 0 },
        ],
      },
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    }];
    const initial = sessionWithDesign(designDsl);
    expect(projectBindableProperties(node)
      .find((property) => property.propertyPath === 'runs[0].text')?.bindingId)
      .toBe('30000000-0000-4000-8000-000000000020');

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-binding',
      nodeId,
      propertyPath: 'runs[0].text',
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    }, {
      staticSchema: schemaSnapshot([
        { fieldKey: 'title', required: true, value: { type: 'text' } },
      ]),
      createUuid: () => '30000000-0000-4000-8000-000000000021',
    });

    expect(result).toMatchObject({ state: 'rejected', code: 'BINDING_TARGET_OVERLAP' });
    expect(result.session).toBe(initial);
  });

  it('requires the exact referenced-schema closure before committing a nested context Binding', () => {
    const nodeId = '10000000-0000-4000-8000-000000000002';
    const initial = bindingSession(nodeId);
    const root = schemaSnapshot([{
      fieldKey: 'brand', displayName: '品牌', required: true,
      value: { type: 'reference', ref: { schemaKey: 'catalog-brand', versionTag: 'v2' } },
    }]);
    const brand = schemaSnapshotWithIdentity('catalog-brand', 'v2', [{
      fieldKey: 'name', displayName: '名称', required: true, value: { type: 'text' },
    }]);
    const intent = {
      operation: 'create-binding' as const,
      nodeId,
      propertyPath: 'runs[0].text',
      source: {
        kind: 'context' as const,
        domain: 'invocation' as const,
        pointer: '/brand/name',
      },
    };

    expect(executeTemplateDataAuthoringCommand(initial, intent, {
      staticSchema: root,
    })).toMatchObject({ state: 'rejected', code: 'BINDING_SOURCE_TYPE_MISMATCH' });

    const applied = executeTemplateDataAuthoringCommand(initial, intent, {
      staticSchema: root,
      staticSchemas: [brand],
      createUuid: () => '30000000-0000-4000-8000-000000000010',
    });
    expect(applied).toMatchObject({ state: 'applied' });
    if (applied.state !== 'applied') throw new Error('expected nested binding to apply');
    expect(bindingOf(applied.session, nodeId)?.source).toEqual(intent.source);
  });

  it('rejects unauthorized and optional-field bindings without mutating the session', () => {
    const nodeId = '10000000-0000-4000-8000-000000000002';
    const initial = bindingSession(nodeId);
    const snapshot = schemaSnapshot([
      { fieldKey: 'subtitle', displayName: '副标题', required: false, value: { type: 'text' } },
    ]);

    const unauthorized = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-binding', nodeId, propertyPath: 'fitMode',
      source: { kind: 'context', domain: 'invocation', pointer: '/subtitle' },
    }, { staticSchema: snapshot });
    expect(unauthorized).toMatchObject({
      state: 'rejected', code: 'BINDING_TARGET_NOT_AUTHORIZED', session: initial,
    });

    const optional = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-binding', nodeId, propertyPath: 'runs[0].text',
      source: { kind: 'context', domain: 'invocation', pointer: '/subtitle' },
    }, { staticSchema: snapshot });
    expect(optional).toMatchObject({
      state: 'rejected', code: 'BINDING_SOURCE_MAY_BE_ABSENT', session: initial,
    });
    expect(initial.history.past).toEqual([]);
  });

  it('keeps bindingId unique across the whole DesignDSL tree', () => {
    const targetNodeId = '10000000-0000-4000-8000-000000000002';
    const duplicateBindingId = '30000000-0000-4000-8000-000000000099';
    const seed = bindingSession(targetNodeId);
    const designDsl = structuredClone(seed.workingCopy.designDsl);
    const root = designDsl.designRoot as Record<string, unknown>;
    const children = root.children as Record<string, unknown>[];
    children.push({
      ...structuredClone(children[0] ?? {}),
      nodeId: '10000000-0000-4000-8000-000000000003',
      bindings: [{
        bindingId: duplicateBindingId,
        targetPropertyRef: {
          rootPropertyId: 'runs',
          selectors: [{ kind: 'index', index: 0 }, { kind: 'member', name: 'text' }],
        },
        source: { kind: 'context', domain: 'invocation', pointer: '/title' },
      }],
    });
    const initial = sessionWithDesign(designDsl);

    const result = executeTemplateDataAuthoringCommand(initial, {
      operation: 'create-binding',
      nodeId: targetNodeId,
      propertyPath: 'runs[0].text',
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    }, {
      staticSchema: schemaSnapshot([
        { fieldKey: 'title', required: true, value: { type: 'text' } },
      ]),
      createUuid: () => duplicateBindingId,
    });

    expect(result).toMatchObject({ state: 'rejected', code: 'BINDING_ID_DUPLICATE' });
    expect(result.session).toBe(initial);
  });
});

function source(
  sources: ReturnType<typeof projectBindingSources>,
  id: string,
) {
  return sources.find((candidate) => candidate.id === id);
}

function schemaSnapshot(
  fields: StaticSnapshot['definition']['fields'],
): StaticSnapshot {
  return {
    schemaKey: 'price-card',
    versionTag: 'v7',
    origin: 'DRAFT',
    sourceDraftRevision: 12,
    compilerVersion: 'schema-compiler/1',
    releaseNote: null,
    referenceDepth: 1,
    publishedAt: '2026-09-03T00:00:00Z',
    definition: {
      dslVersion: 'renderweave-schema/1.0',
      displayName: '价签数据',
      fields,
    },
  };
}

function schemaSnapshotWithIdentity(
  schemaKey: string,
  versionTag: string,
  fields: StaticSnapshot['definition']['fields'],
): StaticSnapshot {
  return {
    ...schemaSnapshot(fields),
    schemaKey,
    versionTag,
    definition: {
      ...schemaSnapshot(fields).definition,
      displayName: schemaKey,
      fields,
    },
  };
}

function sessionWithDesign(designDsl: Record<string, unknown>) {
  const canonicalDesignDsl = canonicalStringifyWorkingValue(designDsl);
  return createStructuredEditorSession({
    templateId: 'template-1',
    revision: '0',
    staticSchema: { schemaKey: 'price-card', versionTag: 'v7' },
    contentHash: `sha256:${'0'.repeat(64)}`,
    persistedReadiness: 'READY',
    canonicalDesignDsl,
    designDsl,
  }, { state: 'checked', value: 'READY' });
}

function bindingSession(nodeId: string) {
  return sessionWithDesign({
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: '价签',
    definitions: [],
    designRoot: {
      nodeId: '10000000-0000-4000-8000-000000000001',
      kind: 'canvas', widthMm: 210, heightMm: 297, bindings: [],
      children: [{
        nodeId,
        kind: 'text',
        bindings: [],
        placement: {
          type: 'ABSOLUTE', xMm: 4, yMm: 5,
          widthMode: 'FIXED', widthMm: 50,
          heightMode: 'HUG_CONTENT',
        },
        runs: [{
          text: '静态标题',
          fontRef: { assetId: '80000000-0000-4000-8000-000000000001' },
          fontSizePt: 14,
          color: '#111111FF',
          letterSpacingPt: 0,
          decoration: 'NONE',
        }],
      }],
    },
  });
}

function repeatDomainSession(loopId: string, items: Record<string, unknown>) {
  return sessionWithDesign({
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: '价签', definitions: [],
    designRoot: {
      nodeId: '10000000-0000-4000-8000-000000000001', kind: 'canvas',
      widthMm: 210, heightMm: 297, bindings: [],
      children: [{
        nodeId: '10000000-0000-4000-8000-000000000028', kind: 'repeat', loopId,
        items,
        placement: {
          type: 'ABSOLUTE', xMm: 0, yMm: 0,
          widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
        },
        absentPolicy: 'EMPTY',
        itemLayout: { kind: 'STACK', direction: 'ROW' },
        instanceLayout: { kind: 'STACK', direction: 'ROW' },
        bindings: [], children: [],
      }],
    },
  });
}

function bindingSessionWithExistingBindings(nodeId: string, count: number) {
  const seed = bindingSession(nodeId);
  const designDsl = structuredClone(seed.workingCopy.designDsl);
  const root = designDsl.designRoot as Record<string, unknown>;
  const node = (root.children as Record<string, unknown>[])[0] as Record<string, unknown>;
  node.bindings = Array.from({ length: count }, (_, index) => ({
    bindingId: `30000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}`,
    targetPropertyRef: { rootPropertyId: `existing${index}`, selectors: [] },
    source: { kind: 'literal', valueType: 'text', value: '值' },
  }));
  return sessionWithDesign(designDsl);
}

function bindingSessionWithTotalBindings(nodeId: string, total: number) {
  const seed = bindingSession(nodeId);
  const designDsl = structuredClone(seed.workingCopy.designDsl);
  const root = designDsl.designRoot as Record<string, unknown>;
  const children = root.children as Record<string, unknown>[];
  const target = children[0] as Record<string, unknown>;
  let bindingIndex = 0;
  const bindings = (count: number) => Array.from({ length: count }, () => ({
    bindingId: `30000000-0000-4000-8000-${(bindingIndex++).toString(16).padStart(12, '0')}`,
    targetPropertyRef: { rootPropertyId: 'x', selectors: [] },
    source: { kind: 'literal', valueType: 'boolean', value: true },
  }));
  const targetCount = Math.min(63, total);
  target.bindings = bindings(targetCount);
  let remaining = total - targetCount;
  let nodeIndex = 0;
  while (remaining > 0) {
    const count = Math.min(64, remaining);
    children.push({
      ...structuredClone(target),
      nodeId: `10000000-0000-4000-8001-${nodeIndex.toString(16).padStart(12, '0')}`,
      bindings: bindings(count),
    });
    remaining -= count;
    nodeIndex += 1;
  }
  return sessionWithDesign(designDsl);
}

function sessionWithDefinitions(definitions: readonly Record<string, unknown>[]) {
  return sessionWithDesign({
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: '价签',
    definitions,
    designRoot: {
      nodeId: '10000000-0000-4000-8000-000000000001',
      kind: 'canvas', widthMm: 210, heightMm: 297, bindings: [], children: [],
    },
  });
}

function expressionDefinition(
  index: number,
  overrides: Partial<Record<string, unknown>> = {},
): Record<string, unknown> {
  return {
    definitionId: `20000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}`,
    kind: 'expression',
    displayName: `表达式 ${index}`,
    domain: 'invocation',
    output: 'text',
    inputs: [],
    source: "'x'",
    ...overrides,
  };
}

function customDefinition(index: number): Record<string, unknown> {
  return {
    definitionId: `20000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}`,
    kind: 'custom',
    displayName: `定义 ${index}`,
    exposure: 'PRIVATE',
    valueType: 'text',
    defaultValue: '值',
  };
}

function mappingDefinition(
  index: number,
  overrides: Partial<Record<string, unknown>> = {},
): Record<string, unknown> {
  return {
    definitionId: `30000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}`,
    kind: 'mapping',
    displayName: `映射 ${index}`,
    domain: 'invocation',
    output: 'text',
    input: { kind: 'context', domain: 'invocation', pointer: '/title' },
    cases: [{
      operator: 'IS_ABSENT',
      then: { kind: 'literal', valueType: 'text', value: '未命名' },
    }],
    otherwise: { kind: 'literal', valueType: 'text', value: '默认值' },
    ...overrides,
  };
}

function bindingOf(
  session: ReturnType<typeof sessionWithDesign>,
  nodeId: string,
): Record<string, unknown> | undefined {
  const find = (value: unknown): Record<string, unknown> | undefined => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return undefined;
    const node = value as Record<string, unknown>;
    if (node.nodeId === nodeId) {
      return Array.isArray(node.bindings)
        ? node.bindings[0] as Record<string, unknown> | undefined
        : undefined;
    }
    if (!Array.isArray(node.children)) return undefined;
    for (const child of node.children) {
      const found = find(child);
      if (found) return found;
    }
    return undefined;
  };
  return find(session.workingCopy.designDsl.designRoot);
}
