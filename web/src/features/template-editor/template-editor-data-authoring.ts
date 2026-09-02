import type {
  PersistedField,
  PersistedValue,
} from '../schema-studio/editor-types';
import type { StaticSnapshot } from '../schema-studio/lossless-api';
import {
  type ReplaceDefinitionCommand,
  type ReplaceNodeBindingCommand,
  type StructuredEditorSession,
  type StaticSchemaIdentity,
} from './template-editor-model';
import { TEMPLATE_BINDING_POLICY } from './template-editor-binding-policy.generated';
import { normalizeTemplateEditorDisplayName } from './template-editor-display-name';
import { parseTemplateExpressionSource } from './template-expression-syntax';
import {
  canonicalStringifyWorkingValue,
  commitStructuredEditorCommand,
} from './template-editor-session';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export type TemplateBindingValueType =
  | 'text'
  | 'decimal'
  | 'boolean'
  | 'date'
  | 'time'
  | 'color'
  | 'imageRef'
  | 'fontRef';

export interface TemplateBindingTargetPropertyRef {
  readonly rootPropertyId: string;
  readonly selectors: readonly (
    | { readonly kind: 'member'; readonly name: string }
    | { readonly kind: 'index'; readonly index: number }
  )[];
}

export interface TemplateBindableProperty {
  readonly propertyPath: string;
  readonly pattern: string;
  readonly label: string;
  readonly valueType: TemplateBindingValueType;
  readonly baselineValue: unknown;
  readonly targetPropertyRef: TemplateBindingTargetPropertyRef;
  readonly bindingId?: string;
}

export type TemplateBindingSource =
  | {
    readonly kind: 'context';
    readonly domain: 'invocation' | { readonly kind: 'loop'; readonly loopId: string };
    readonly pointer: string;
  }
  | { readonly kind: 'loopIndex'; readonly loopId: string }
  | { readonly kind: 'definition'; readonly definitionId: string };

export type TemplateBindingSourceState =
  | 'available'
  | 'incompatible'
  | 'may-be-absent'
  | 'out-of-scope';

export interface TemplateBindingSourceOption {
  readonly id: string;
  readonly group: 'system' | 'definition' | 'loop';
  readonly label: string;
  readonly detail: string;
  readonly source: TemplateBindingSource;
  readonly valueType?: TemplateBindingValueType;
  readonly state: TemplateBindingSourceState;
  readonly reason?: string;
}

export type TemplateDataAuthoringIntent =
  | {
    readonly operation: 'create-definition';
    readonly definition: Readonly<Record<string, unknown>>;
  }
  | {
    readonly operation: 'update-definition';
    readonly definitionId: string;
    readonly definition: Readonly<Record<string, unknown>>;
  }
  | {
    readonly operation: 'create-binding';
    readonly nodeId: string;
    readonly propertyPath: string;
    readonly source: TemplateBindingSource;
  }
  | {
    readonly operation: 'remove-binding';
    readonly nodeId: string;
    readonly bindingId: string;
  };

export interface TemplateDataAuthoringOptions {
  readonly createUuid?: () => string;
  readonly staticSchema?: StaticSnapshot;
  readonly staticSchemas?: readonly StaticSnapshot[];
}

export interface TemplateDataAuthoringContext {
  readonly staticSchemas?: readonly StaticSnapshot[];
}

export type TemplateDataAuthoringResult =
  | {
    readonly state: 'applied';
    readonly session: StructuredEditorSession;
    readonly definitionId?: string;
    readonly bindingId?: string;
    readonly message: string;
  }
  | {
    readonly state: 'no-op';
    readonly session: StructuredEditorSession;
    readonly message: string;
  }
  | {
    readonly state: 'rejected';
    readonly session: StructuredEditorSession;
    readonly code: string;
    readonly message: string;
  };

export interface TemplateSystemFieldProjection {
  readonly fieldKey: string;
  readonly displayName: string;
  readonly description?: string;
  readonly pointer: string;
  readonly required: boolean;
  readonly typeLabel: string;
  readonly constraintLabels: readonly string[];
  readonly reference?: StaticSchemaIdentity;
}

export interface TemplateSystemSchemaProjection {
  readonly identity: StaticSchemaIdentity;
  readonly displayName: string;
  readonly description?: string;
  readonly fields: readonly TemplateSystemFieldProjection[];
}

/**
 * Projects one immutable StaticSchema into authoring vocabulary. Reference fields
 * remain opaque cards here; their exact referenced schema is resolved only by the
 * detail dialog so the navigator never invents an inline object tree.
 */
export function projectTemplateStaticSchema(
  snapshot: StaticSnapshot,
): TemplateSystemSchemaProjection {
  return Object.freeze({
    identity: Object.freeze({
      schemaKey: snapshot.schemaKey,
      versionTag: snapshot.versionTag,
    }),
    displayName: snapshot.definition.displayName,
    ...(snapshot.definition.description === undefined
      ? {}
      : { description: snapshot.definition.description }),
    fields: Object.freeze(snapshot.definition.fields.map(projectField)),
  });
}

/**
 * Expands the OpenAPI-projected append-only policy against one authored node.
 * Only existing leaves are returned: a Binding never materializes an omitted
 * default, changes a union variant, extends an array, or replaces an object.
 */
export function projectBindableProperties(
  node: Readonly<Record<string, unknown>>,
): readonly TemplateBindableProperty[] {
  if (typeof node.kind !== 'string') return [];
  const byKind = TEMPLATE_BINDING_POLICY.byKind as Readonly<Record<string, readonly string[]>>;
  if (!Object.hasOwn(byKind, node.kind)) return [];
  const patterns = [
    ...(node.kind === 'canvas' ? [] : TEMPLATE_BINDING_POLICY.commonNonCanvas),
    ...(node.kind === 'canvas' || node.kind === 'group'
      ? []
      : TEMPLATE_BINDING_POLICY.commonNonGroup),
    ...(byKind[node.kind] ?? []),
  ];
  const bindings = Array.isArray(node.bindings)
    ? node.bindings.filter(isRecord)
    : [];
  const projected: TemplateBindableProperty[] = [];
  for (const pattern of new Set(patterns)) {
    for (const materialized of materializedPaths(node, pattern)) {
      const targetPropertyRef = targetRef(materialized.path);
      if (!targetPropertyRef) continue;
      const existing = bindings.find((binding) => sameTarget(
        binding.targetPropertyRef,
        targetPropertyRef,
      ));
      projected.push(Object.freeze({
        propertyPath: materialized.path,
        pattern,
        label: bindingPropertyLabel(materialized.path),
        valueType: propertyValueType(pattern),
        baselineValue: materialized.value,
        targetPropertyRef,
        ...(typeof existing?.bindingId === 'string'
          ? { bindingId: existing.bindingId }
          : {}),
      }));
    }
  }
  return Object.freeze(projected.sort((left, right) => (
    left.propertyPath.localeCompare(right.propertyPath, 'en')
  )));
}

/**
 * Builds the closed ordinary-Binding source picker for one consumer node.
 * Optional invocation fields remain visible but unavailable because ordinary
 * visual properties require CONCRETE values and never fall back to baseline.
 */
export function projectBindingSources(
  designDsl: Readonly<Record<string, unknown>>,
  schema: StaticSnapshot,
  nodeId: string,
  targetType: TemplateBindingValueType,
  staticSchemas: readonly StaticSnapshot[] = [],
): readonly TemplateBindingSourceOption[] {
  const catalog = staticSchemaCatalog(schema, staticSchemas);
  const rootContext = schemaContext(schema);
  const loopContexts = availableLoopContexts(
    designDsl.designRoot,
    definitionRecords(designDsl.definitions),
    rootContext,
    catalog,
    nodeId,
  );
  const loopIds = new Set(loopContexts.map((loop) => loop.loopId));
  const system = projectContextSources({
    context: rootContext,
    domain: 'invocation',
    group: 'system',
    targetType,
    catalog,
  });
  const definitions = definitionRecords(designDsl.definitions).map(
    (definition): TemplateBindingSourceOption => {
      const definitionId = stringMember(definition, 'definitionId') ?? '';
      const sourceType = definitionOutputType(definition);
      const loopId = definitionLoopId(definition);
      const inScope = loopId === null || loopIds.has(loopId);
      const mismatched = sourceType !== targetType;
      const state: TemplateBindingSourceState = !inScope
        ? 'out-of-scope'
        : mismatched ? 'incompatible' : 'available';
      const reason = !inScope
        ? '定义属于当前节点不可见的循环域'
        : mismatched
          ? `类型为${sourceType ? bindingValueTypeLabel(sourceType) : '不支持的类型'}，目标需要${bindingValueTypeLabel(targetType)}`
          : undefined;
      return Object.freeze({
        id: `definition:${definitionId}`,
        group: 'definition',
        label: stringMember(definition, 'displayName') ?? '未命名定义',
        detail: `${definitionKindLabel(definition.kind)} · ${sourceType ? bindingValueTypeLabel(sourceType) : '复合类型'}`,
        source: Object.freeze({ kind: 'definition', definitionId }),
        ...(sourceType ? { valueType: sourceType } : {}),
        state,
        ...(reason ? { reason } : {}),
      });
    },
  );
  const loops = loopContexts.flatMap((loop): TemplateBindingSourceOption[] => {
    const fields = loop.context
      ? [...projectContextSources({
        context: loop.context,
        domain: { kind: 'loop', loopId: loop.loopId },
        group: 'loop',
        targetType,
        catalog,
        detailPrefix: `循环 ${shortIdentity(loop.loopId)}`,
      })]
      : [];
    fields.push(Object.freeze({
      id: `loop-index:${loop.loopId}`,
      group: 'loop',
      label: '循环索引',
      detail: `${shortIdentity(loop.loopId)} · 从 0 开始`,
      source: Object.freeze({ kind: 'loopIndex', loopId: loop.loopId }),
      valueType: 'decimal',
      state: targetType === 'decimal' ? 'available' : 'incompatible',
      ...(targetType === 'decimal'
        ? {}
        : { reason: `类型为数值，目标需要${bindingValueTypeLabel(targetType)}` }),
    }));
    return fields;
  });
  return Object.freeze([...system, ...definitions, ...loops]);
}

/**
 * Resolves data-authoring intent into the same canonical EditorSession history
 * used by every other authored change. No definition state exists outside the
 * DesignDSL working copy.
 */
export function executeTemplateDataAuthoringCommand(
  session: StructuredEditorSession,
  intent: TemplateDataAuthoringIntent,
  options: TemplateDataAuthoringOptions = {},
): TemplateDataAuthoringResult {
  switch (intent.operation) {
    case 'create-definition': {
      if (Object.hasOwn(intent.definition, 'definitionId')) {
        return dataRejected(session, 'DEFINITION_ID_CLIENT_OWNED', '新建定义不能自报 definitionId。');
      }
      const definitionId = (options.createUuid ?? defaultUuid)().toLowerCase();
      if (!UUID_V4.test(definitionId)) {
        return dataRejected(session, 'DEFINITION_ID_INVALID', '无法生成合法的 definitionId。');
      }
      if (definitionRecords(session.workingCopy.designDsl.definitions)
        .some((definition) => definition.definitionId === definitionId)) {
        return dataRejected(session, 'DEFINITION_ID_DUPLICATE', 'definitionId 已存在。');
      }
      const definition = { definitionId, ...intent.definition };
      const problem = validateDefinitionShell(definition);
      if (problem) return dataRejected(session, problem.code, problem.message);
      const domainProblem = validateDefinitionDomainAgainstTree(
        definition,
        session.workingCopy.designDsl.designRoot,
      );
      if (domainProblem) {
        return dataRejected(session, domainProblem.code, domainProblem.message);
      }
      const prospectiveDefinitions = [
        ...definitionRecords(session.workingCopy.designDsl.definitions),
        definition,
      ];
      const sourceProblem = validateDefinitionSourcesAgainstScope(
        definition,
        session.workingCopy.designDsl.designRoot,
        prospectiveDefinitions,
      );
      if (sourceProblem) {
        return dataRejected(session, sourceProblem.code, sourceProblem.message);
      }
      const typeProblem = validateMappingKnownTypes(
        definition,
        session.workingCopy.designDsl.designRoot,
        prospectiveDefinitions,
        options.staticSchema,
        options.staticSchemas,
      );
      if (typeProblem) {
        return dataRejected(session, typeProblem.code, typeProblem.message);
      }
      const graphProblem = validateDefinitionGraph(prospectiveDefinitions);
      if (graphProblem) {
        return dataRejected(session, graphProblem.code, graphProblem.message);
      }
      const capacityProblem = validateDefinitionCapacity(prospectiveDefinitions);
      if (capacityProblem) {
        return dataRejected(session, capacityProblem.code, capacityProblem.message);
      }
      const command: ReplaceDefinitionCommand = {
        kind: 'replace-definition',
        definitionId,
        before: null,
        after: definition,
      };
      const committed = commitStructuredEditorCommand(session, command);
      if (committed.state === 'invalid') {
        return dataRejected(
          session,
          committed.reason,
          committed.reason === 'CANONICAL_SIZE_EXCEEDED'
            ? '添加定义后的 DesignDSL 超过 canonical 上限。'
            : '定义不符合当前客户端理解的 closed DesignDSL。',
        );
      }
      if (committed.state === 'no-op') {
        return { state: 'no-op', session, message: '定义没有产生变化。' };
      }
      return {
        state: 'applied',
        session: committed.session,
        definitionId,
        message: '已在当前 DesignDSL 中创建定义。',
      };
    }
    case 'update-definition': {
      if (Object.hasOwn(intent.definition, 'definitionId')) {
        return dataRejected(session, 'DEFINITION_ID_IMMUTABLE', '编辑定义不能修改 definitionId。');
      }
      const before = definitionRecords(session.workingCopy.designDsl.definitions)
        .find((definition) => definition.definitionId === intent.definitionId);
      if (!before) {
        return dataRejected(session, 'DEFINITION_NOT_FOUND', '待编辑定义已不在当前工作副本中。');
      }
      if (intent.definition.kind !== before.kind) {
        return dataRejected(session, 'DEFINITION_KIND_IMMUTABLE', '编辑定义不能改变定义种类。');
      }
      const after: Record<string, unknown> = {
        definitionId: intent.definitionId,
        ...intent.definition,
      };
      if ((before.kind === 'mapping' || before.kind === 'expression')
        && canonicalStringifyWorkingValue(before.domain)
          !== canonicalStringifyWorkingValue(after.domain)) {
        return dataRejected(
          session,
          'DEFINITION_DOMAIN_IMMUTABLE',
          '已有派生定义不能直接改变作用域；请新建定义后迁移引用。',
        );
      }
      const problem = validateDefinitionShell(after);
      if (problem) return dataRejected(session, problem.code, problem.message);
      const domainProblem = validateDefinitionDomainAgainstTree(
        after,
        session.workingCopy.designDsl.designRoot,
      );
      if (domainProblem) {
        return dataRejected(session, domainProblem.code, domainProblem.message);
      }
      const prospectiveDefinitions = definitionRecords(session.workingCopy.designDsl.definitions)
        .map((definition) => definition.definitionId === intent.definitionId ? after : definition);
      const sourceProblem = validateDefinitionSourcesAgainstScope(
        after,
        session.workingCopy.designDsl.designRoot,
        prospectiveDefinitions,
      );
      if (sourceProblem) {
        return dataRejected(session, sourceProblem.code, sourceProblem.message);
      }
      const typeProblem = validateMappingKnownTypes(
        after,
        session.workingCopy.designDsl.designRoot,
        prospectiveDefinitions,
        options.staticSchema,
        options.staticSchemas,
      );
      if (typeProblem) {
        return dataRejected(session, typeProblem.code, typeProblem.message);
      }
      const graphProblem = validateDefinitionGraph(prospectiveDefinitions);
      if (graphProblem) {
        return dataRejected(session, graphProblem.code, graphProblem.message);
      }
      const capacityProblem = validateDefinitionCapacity(prospectiveDefinitions);
      if (capacityProblem) {
        return dataRejected(session, capacityProblem.code, capacityProblem.message);
      }
      const committed = commitStructuredEditorCommand(session, {
        kind: 'replace-definition',
        definitionId: intent.definitionId,
        before,
        after,
      });
      if (committed.state === 'invalid') {
        return dataRejected(
          session,
          committed.reason,
          committed.reason === 'CANONICAL_SIZE_EXCEEDED'
            ? '编辑定义后的 DesignDSL 超过 canonical 上限。'
            : '定义不符合当前客户端理解的 closed DesignDSL。',
        );
      }
      if (committed.state === 'no-op') {
        return { state: 'no-op', session, message: '定义没有产生变化。' };
      }
      return {
        state: 'applied',
        session: committed.session,
        definitionId: intent.definitionId,
        message: '已更新当前 DesignDSL 中的定义。',
      };
    }
    case 'create-binding': {
      const node = findAuthoredNode(session.workingCopy.designDsl.designRoot, intent.nodeId);
      if (!node) return dataRejected(session, 'NODE_NOT_FOUND', '待绑定节点已不在当前工作副本中。');
      const target = projectBindableProperties(node)
        .find((candidate) => candidate.propertyPath === intent.propertyPath);
      if (!target) {
        return dataRejected(
          session,
          'BINDING_TARGET_NOT_AUTHORIZED',
          '目标属性不存在、尚未 materialize 或未被 BindingPolicyCatalog 授权。',
        );
      }
      const existingBindings = Array.isArray(node.bindings) ? node.bindings.filter(isRecord) : [];
      if (target.bindingId || existingBindings.some((binding) => {
        const existingPath = targetPathOf(binding.targetPropertyRef);
        return existingPath !== null && pathsOverlap(existingPath, target.propertyPath);
      })) {
        return dataRejected(session, 'BINDING_TARGET_OVERLAP', '目标属性已被相同或重叠 Binding 占用。');
      }
      if (Array.isArray(node.bindings) && node.bindings.length >= 64) {
        return dataRejected(
          session,
          'BINDING_CAPACITY_EXCEEDED',
          '单个节点最多允许 64 个 Binding。',
        );
      }
      if (countAuthoredBindings(session.workingCopy.designDsl.designRoot, 4_096) >= 4_096) {
        return dataRejected(
          session,
          'BINDING_CAPACITY_EXCEEDED',
          'DesignDSL 最多允许 4096 个 Binding。',
        );
      }
      if (!options.staticSchema) {
        return dataRejected(session, 'STATIC_SCHEMA_UNAVAILABLE', '永久 StaticSchema 尚不可用，不能证明来源类型。');
      }
      const source = projectBindingSources(
        session.workingCopy.designDsl,
        options.staticSchema,
        intent.nodeId,
        target.valueType,
        options.staticSchemas,
      ).find((candidate) => sameBindingSource(candidate.source, intent.source));
      if (!source || source.state !== 'available') {
        return dataRejected(
          session,
          source?.state === 'out-of-scope' ? 'BINDING_SOURCE_OUT_OF_SCOPE'
            : source?.state === 'may-be-absent' ? 'BINDING_SOURCE_MAY_BE_ABSENT'
              : 'BINDING_SOURCE_TYPE_MISMATCH',
          source?.reason ?? '来源不存在或与目标类型不兼容。',
        );
      }
      const bindingId = (options.createUuid ?? defaultUuid)().toLowerCase();
      if (!UUID_V4.test(bindingId)) {
        return dataRejected(session, 'BINDING_ID_INVALID', '无法生成合法的 bindingId。');
      }
      if (hasAuthoredBindingId(session.workingCopy.designDsl.designRoot, bindingId)) {
        return dataRejected(session, 'BINDING_ID_DUPLICATE', 'bindingId 已存在于当前 DesignDSL。');
      }
      const binding = {
        bindingId,
        targetPropertyRef: target.targetPropertyRef,
        source: intent.source,
      };
      const command: ReplaceNodeBindingCommand = {
        kind: 'replace-node-binding',
        nodeId: intent.nodeId,
        bindingId,
        before: null,
        after: binding,
      };
      const committed = commitStructuredEditorCommand(session, command);
      if (committed.state === 'invalid') {
        return dataRejected(session, committed.reason, 'Binding 不能安全写入当前 DesignDSL。');
      }
      if (committed.state === 'no-op') {
        return { state: 'no-op', session, message: 'Binding 没有产生变化。' };
      }
      return {
        state: 'applied',
        session: committed.session,
        bindingId,
        message: `已绑定属性“${target.label}”。`,
      };
    }
    case 'remove-binding': {
      const node = findAuthoredNode(session.workingCopy.designDsl.designRoot, intent.nodeId);
      if (!node || !Array.isArray(node.bindings)) {
        return dataRejected(session, 'NODE_NOT_FOUND', '待解绑节点已不在当前工作副本中。');
      }
      const binding = node.bindings.filter(isRecord)
        .find((candidate) => candidate.bindingId === intent.bindingId);
      if (!binding) return dataRejected(session, 'BINDING_NOT_FOUND', '待移除 Binding 已不存在。');
      const command: ReplaceNodeBindingCommand = {
        kind: 'replace-node-binding',
        nodeId: intent.nodeId,
        bindingId: intent.bindingId,
        before: binding,
        after: null,
      };
      const committed = commitStructuredEditorCommand(session, command);
      if (committed.state === 'invalid') {
        return dataRejected(session, committed.reason, 'Binding 不能安全移除。');
      }
      if (committed.state === 'no-op') {
        return { state: 'no-op', session, message: 'Binding 已不存在。' };
      }
      return {
        state: 'applied',
        session: committed.session,
        bindingId: intent.bindingId,
        message: '已移除 Binding；属性继续保留 authored baseline。',
      };
    }
  }
}

function projectField(field: PersistedField): TemplateSystemFieldProjection {
  const reference = exactReferenceIdentity(field.value);
  return Object.freeze({
    fieldKey: field.fieldKey,
    displayName: field.displayName?.trim() || field.fieldKey,
    ...(field.description === undefined ? {} : { description: field.description }),
    pointer: `/${escapePointerSegment(field.fieldKey)}`,
    required: field.required,
    typeLabel: schemaValueTypeLabel(field.value),
    constraintLabels: Object.freeze([
      field.required ? '必填' : '可缺省',
      ...constraintLabels(field.value),
    ]),
    ...(reference ? { reference } : {}),
  });
}

function exactReferenceIdentity(value: PersistedValue): StaticSchemaIdentity | undefined {
  if (value.type === 'array') return exactReferenceIdentity(value.items);
  return value.type === 'reference' && value.ref.versionTag
    ? Object.freeze({
      schemaKey: value.ref.schemaKey,
      versionTag: value.ref.versionTag,
    })
    : undefined;
}

function schemaValueTypeLabel(value: PersistedValue): string {
  switch (value.type) {
    case 'text': return '文本';
    case 'decimal': return '数值';
    case 'date': return '日期';
    case 'time': return '时间';
    case 'boolean': return '布尔';
    case 'reference': return '对象引用';
    case 'array': return `${schemaValueTypeLabel(value.items)}列表`;
  }
}

function constraintLabels(value: PersistedValue): string[] {
  switch (value.type) {
    case 'text': {
      const constraints = value.constraints;
      if (!constraints) return [];
      return definedLabels([
        label(constraints.minLength, (entry) => `最少 ${entry} 个字符`),
        label(constraints.maxLength, (entry) => `最多 ${entry} 个字符`),
        label(constraints.pattern, (entry) => `匹配模式 ${entry}`),
        label(constraints.enum, (entry) => `${entry.length} 个枚举值`),
        label(constraints.const, (entry) => `固定为 ${entry}`),
      ]);
    }
    case 'decimal': {
      const constraints = value.constraints;
      if (!constraints) return [];
      return definedLabels([
        label(constraints.min, (entry) => `不小于 ${entry}`),
        label(constraints.exclusiveMin, (entry) => `大于 ${entry}`),
        label(constraints.max, (entry) => `不大于 ${entry}`),
        label(constraints.exclusiveMax, (entry) => `小于 ${entry}`),
        label(constraints.multipleOf, (entry) => `步进 ${entry}`),
        label(constraints.enum, (entry) => `${entry.length} 个枚举值`),
        label(constraints.const, (entry) => `固定为 ${entry}`),
      ]);
    }
    case 'date':
    case 'time': {
      const constraints = value.constraints;
      if (!constraints) return [];
      return definedLabels([
        label(constraints.min, (entry) => `不早于 ${entry}`),
        label(constraints.exclusiveMin, (entry) => `晚于 ${entry}`),
        label(constraints.max, (entry) => `不晚于 ${entry}`),
        label(constraints.exclusiveMax, (entry) => `早于 ${entry}`),
        label(constraints.enum, (entry) => `${entry.length} 个枚举值`),
        label(constraints.const, (entry) => `固定为 ${entry}`),
      ]);
    }
    case 'boolean': {
      const constraints = value.constraints;
      if (!constraints) return [];
      return constraints.const === undefined ? [] : [`固定为 ${constraints.const ? '是' : '否'}`];
    }
    case 'array': {
      const constraints = value.constraints;
      const own = constraints ? definedLabels([
        label(constraints.minItems, (entry) => `至少 ${entry} 项`),
        label(constraints.maxItems, (entry) => `至多 ${entry} 项`),
        constraints.uniqueItems ? '元素不可重复' : undefined,
      ]) : [];
      return [
        ...own,
        ...constraintLabels(value.items).map((entry) => `元素：${entry}`),
      ];
    }
    case 'reference':
      return [];
  }
}

function label<T>(value: T | undefined, format: (value: T) => string): string | undefined {
  return value === undefined ? undefined : format(value);
}

function definedLabels(values: readonly (string | undefined)[]): string[] {
  return values.filter((value): value is string => value !== undefined);
}

function escapePointerSegment(value: string): string {
  return value.replaceAll('~', '~0').replaceAll('/', '~1');
}

interface MaterializedPath {
  readonly path: string;
  readonly value: unknown;
}

function materializedPaths(
  root: Readonly<Record<string, unknown>>,
  pattern: string,
): MaterializedPath[] {
  const segments = pattern.split('.');
  let candidates: Array<{ value: unknown; path: string }> = [{ value: root, path: '' }];
  for (const segment of segments) {
    const arrayMember = segment.endsWith('[*]') ? segment.slice(0, -3) : null;
    const next: Array<{ value: unknown; path: string }> = [];
    for (const candidate of candidates) {
      if (!isRecord(candidate.value)) continue;
      if (arrayMember !== null) {
        const value = candidate.value[arrayMember];
        if (!Array.isArray(value)) continue;
        value.forEach((entry, index) => {
          next.push({
            value: entry,
            path: joinPropertyPath(candidate.path, `${arrayMember}[${index}]`),
          });
        });
      } else if (Object.hasOwn(candidate.value, segment)) {
        next.push({
          value: candidate.value[segment],
          path: joinPropertyPath(candidate.path, segment),
        });
      }
    }
    candidates = next;
  }
  return candidates.map((candidate) => ({ path: candidate.path, value: candidate.value }));
}

function joinPropertyPath(prefix: string, segment: string): string {
  return prefix.length === 0 ? segment : `${prefix}.${segment}`;
}

function targetRef(propertyPath: string): TemplateBindingTargetPropertyRef | null {
  const tokens = propertyPath.split('.');
  const first = parsePathToken(tokens[0] ?? '');
  if (!first || first.member.length === 0) return null;
  const selectors: Array<
    | { readonly kind: 'member'; readonly name: string }
    | { readonly kind: 'index'; readonly index: number }
  > = [];
  if (first.index !== undefined) selectors.push({ kind: 'index', index: first.index });
  for (const token of tokens.slice(1)) {
    const parsed = parsePathToken(token);
    if (!parsed) return null;
    selectors.push({ kind: 'member', name: parsed.member });
    if (parsed.index !== undefined) selectors.push({ kind: 'index', index: parsed.index });
  }
  if (selectors.length > 2) return null;
  return Object.freeze({
    rootPropertyId: first.member,
    selectors: Object.freeze(selectors.map((selector) => Object.freeze(selector))),
  });
}

function parsePathToken(value: string): { member: string; index?: number } | null {
  const match = /^([^[]+)(?:\[(0|[1-9][0-9]*)\])?$/.exec(value);
  if (!match) return null;
  const index = match[2] === undefined ? undefined : Number(match[2]);
  if (index !== undefined && !Number.isSafeInteger(index)) return null;
  return { member: match[1] ?? '', ...(index === undefined ? {} : { index }) };
}

function sameTarget(left: unknown, right: TemplateBindingTargetPropertyRef): boolean {
  const candidatePath = targetPathOf(left);
  return candidatePath !== null && candidatePath === targetPathOf(right);
}

function numericValue(value: unknown): number | null {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return value;
  if (typeof value === 'object' && value !== null && 'toString' in value) {
    const token = String(value);
    if (/^(0|[1-9][0-9]*)$/.test(token)) {
      const parsed = Number(token);
      return Number.isSafeInteger(parsed) ? parsed : null;
    }
  }
  return null;
}

function propertyValueType(pattern: string): TemplateBindingValueType {
  const valueType = TEMPLATE_BINDING_POLICY.valueTypes[
    pattern as keyof typeof TEMPLATE_BINDING_POLICY.valueTypes
  ];
  if (!valueType) throw new Error(`Binding policy path has no projected value type: ${pattern}`);
  return valueType;
}

function bindingPropertyLabel(path: string): string {
  const labels: Readonly<Record<string, string>> = {
    'runs[0].text': '文本值',
    'runs[0].fontRef': '字体 Asset',
    'runs[0].fontSizePt': '字号',
    'runs[0].color': '文字颜色',
    'placement.xMm': 'X 坐标',
    'placement.yMm': 'Y 坐标',
    'placement.widthMm': '宽度',
    'placement.heightMm': '高度',
    'fill.color': '填充颜色',
    'stroke.color': '描边颜色',
    'stroke.widthMm': '描边宽度',
    backgroundColor: '背景颜色',
    content: '二维码内容',
    value: '条形码值',
  };
  return labels[path] ?? path;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function schemaFieldValueType(value: PersistedValue | undefined): TemplateBindingValueType | null {
  if (!value) return null;
  switch (value.type) {
    case 'text':
    case 'decimal':
    case 'boolean':
    case 'date':
    case 'time':
      return value.type;
    case 'reference':
    case 'array':
      return null;
  }
}

type TemplateContextDomain = Extract<TemplateBindingSource, { readonly kind: 'context' }>['domain'];

interface TemplateSchemaContext {
  readonly identity: StaticSchemaIdentity;
  readonly displayName: string;
  readonly fields: readonly PersistedField[];
}

interface TemplateLoopContext {
  readonly loopId: string;
  readonly context: TemplateSchemaContext | null;
}

function schemaContext(snapshot: StaticSnapshot): TemplateSchemaContext {
  return Object.freeze({
    identity: Object.freeze({
      schemaKey: snapshot.schemaKey,
      versionTag: snapshot.versionTag,
    }),
    displayName: snapshot.definition.displayName,
    fields: snapshot.definition.fields,
  });
}

function staticSchemaCatalog(
  root: StaticSnapshot,
  snapshots: readonly StaticSnapshot[],
): ReadonlyMap<string, TemplateSchemaContext> {
  const catalog = new Map<string, TemplateSchemaContext>();
  for (const snapshot of [root, ...snapshots]) {
    const context = schemaContext(snapshot);
    if (!catalog.has(schemaIdentityKey(context.identity))) {
      catalog.set(schemaIdentityKey(context.identity), context);
    }
  }
  return catalog;
}

function schemaIdentityKey(identity: StaticSchemaIdentity): string {
  return `${identity.schemaKey.length}:${identity.schemaKey}${identity.versionTag.length}:${identity.versionTag}`;
}

function projectContextSources({
  context,
  domain,
  group,
  targetType,
  catalog,
  detailPrefix,
}: {
  context: TemplateSchemaContext;
  domain: TemplateContextDomain;
  group: 'system' | 'loop';
  targetType: TemplateBindingValueType;
  catalog: ReadonlyMap<string, TemplateSchemaContext>;
  detailPrefix?: string;
}): readonly TemplateBindingSourceOption[] {
  const projected: TemplateBindingSourceOption[] = [];
  const domainKey = domain === 'invocation' ? 'invocation' : `loop:${domain.loopId}`;
  const visit = (
    current: TemplateSchemaContext,
    pointerPrefix: string,
    labelPrefix: readonly string[],
    ancestorMayBeAbsent: boolean,
    lineage: ReadonlySet<string>,
    depth: number,
  ) => {
    if (depth >= 32) return;
    const identity = schemaIdentityKey(current.identity);
    if (lineage.has(identity)) return;
    const nextLineage = new Set(lineage).add(identity);
    for (const field of current.fields) {
      const pointer = `${pointerPrefix}/${escapePointerSegment(field.fieldKey)}`;
      const mayBeAbsent = ancestorMayBeAbsent || !field.required;
      const sourceType = schemaFieldValueType(field.value);
      const typeLabel = schemaValueTypeLabel(field.value);
      const mismatched = sourceType !== targetType;
      const state: TemplateBindingSourceState = mismatched
        ? 'incompatible'
        : mayBeAbsent ? 'may-be-absent' : 'available';
      const reason = mismatched
        ? `类型为${sourceType ? bindingValueTypeLabel(sourceType) : typeLabel}，目标需要${bindingValueTypeLabel(targetType)}`
        : mayBeAbsent ? '字段可缺省；请先用 Mapping 定义显式补全' : undefined;
      const labels = [...labelPrefix, field.displayName?.trim() || field.fieldKey];
      projected.push(Object.freeze({
        id: `context:${domainKey}:${pointer}`,
        group,
        label: labels.join(' / '),
        detail: `${detailPrefix ? `${detailPrefix} · ` : ''}${pointer} · ${typeLabel}`,
        source: Object.freeze({ kind: 'context', domain, pointer }),
        ...(sourceType ? { valueType: sourceType } : {}),
        state,
        ...(reason ? { reason } : {}),
      }));
      if (field.value.type !== 'reference' || !field.value.ref.versionTag) continue;
      const referenced = catalog.get(schemaIdentityKey({
        schemaKey: field.value.ref.schemaKey,
        versionTag: field.value.ref.versionTag,
      }));
      if (referenced) {
        visit(referenced, pointer, labels, mayBeAbsent, nextLineage, depth + 1);
      }
    }
  };
  visit(context, '', [], false, new Set(), 0);
  return Object.freeze(projected);
}

function availableLoopContexts(
  designRoot: unknown,
  definitions: readonly Readonly<Record<string, unknown>>[],
  rootContext: TemplateSchemaContext,
  catalog: ReadonlyMap<string, TemplateSchemaContext>,
  targetNodeId: string,
): readonly TemplateLoopContext[] {
  const root = isRecord(designRoot) ? designRoot : null;
  if (!root) return [];
  let result: readonly TemplateLoopContext[] | null = null;
  const visit = (
    node: Readonly<Record<string, unknown>>,
    scope: readonly TemplateLoopContext[],
  ): boolean => {
    if (node.nodeId === targetNodeId) {
      result = scope;
      return true;
    }
    let childScope = scope;
    if (node.kind === 'repeat' && typeof node.loopId === 'string') {
      const context = resolveRepeatItemContext(
        node.items,
        rootContext,
        scope,
        definitions,
        catalog,
      );
      childScope = [...scope, Object.freeze({ loopId: node.loopId, context })];
    }
    if (!Array.isArray(node.children)) return false;
    return node.children.some((candidate) => {
      const child = isRecord(candidate) ? candidate : null;
      return child ? visit(child, childScope) : false;
    });
  };
  visit(root, []);
  return result ?? [];
}

function resolveRepeatItemContext(
  sourceValue: unknown,
  rootContext: TemplateSchemaContext,
  scope: readonly TemplateLoopContext[],
  definitions: readonly Readonly<Record<string, unknown>>[],
  catalog: ReadonlyMap<string, TemplateSchemaContext>,
): TemplateSchemaContext | null {
  const source = isRecord(sourceValue) ? sourceValue : null;
  if (!source) return null;
  if (source.kind === 'literal') {
    return basicLoopItemContext(listItemType(source.valueType));
  }
  if (source.kind === 'definition' && typeof source.definitionId === 'string') {
    const definition = definitions.find((candidate) => (
      candidate.definitionId === source.definitionId
    ));
    if (!definition || !definitionIsVisibleInLoopScope(definition, scope)) return null;
    const declared = definition.kind === 'custom' ? definition.valueType : definition.output;
    return basicLoopItemContext(listItemType(declared));
  }
  if (source.kind !== 'context' || typeof source.pointer !== 'string') return null;
  const sourceDomain = source.domain;
  const sourceLoopId = isRecord(sourceDomain) && sourceDomain.kind === 'loop'
    && typeof sourceDomain.loopId === 'string'
    ? sourceDomain.loopId
    : null;
  const context = sourceDomain === 'invocation'
    ? rootContext
    : sourceLoopId !== null
      ? scope.find((candidate) => candidate.loopId === sourceLoopId)?.context ?? null
      : null;
  if (!context) return null;
  const selected = resolveSchemaPointer(context, source.pointer, catalog);
  if (!selected || selected.value.type !== 'array') return null;
  if (selected.value.items.type !== 'reference') {
    return basicLoopItemContext(selected.value.items.type);
  }
  const versionTag = selected.value.items.ref.versionTag;
  if (!versionTag) return null;
  return catalog.get(schemaIdentityKey({
    schemaKey: selected.value.items.ref.schemaKey,
    versionTag,
  })) ?? null;
}

function definitionIsVisibleInLoopScope(
  definition: Readonly<Record<string, unknown>>,
  scope: readonly TemplateLoopContext[],
): boolean {
  const loopId = definitionLoopId(definition);
  return loopId === null || scope.some((candidate) => candidate.loopId === loopId);
}

function listItemType(value: unknown): string | null {
  return isRecord(value) && value.type === 'list' && typeof value.items === 'string'
    ? value.items
    : null;
}

function basicLoopItemContext(valueType: string | null): TemplateSchemaContext | null {
  if (!valueType || !new Set(['text', 'decimal', 'date', 'time', 'boolean']).has(valueType)) {
    return null;
  }
  const scalar = valueType as 'text' | 'decimal' | 'date' | 'time' | 'boolean';
  return Object.freeze({
    identity: Object.freeze({ schemaKey: `system-basic-${scalar}`, versionTag: 'v1' }),
    displayName: `基础${bindingValueTypeLabel(scalar)}项`,
    fields: Object.freeze([
      Object.freeze({
        fieldKey: 'index', displayName: '索引', required: true,
        value: Object.freeze({ type: 'decimal' as const }),
      }),
      Object.freeze({
        fieldKey: 'value', displayName: '值', required: true,
        value: Object.freeze({ type: scalar }),
      }),
    ]),
  });
}

function resolveSchemaPointer(
  context: TemplateSchemaContext,
  pointer: string,
  catalog: ReadonlyMap<string, TemplateSchemaContext>,
): { readonly value: PersistedValue; readonly mayBeAbsent: boolean } | null {
  if (!pointer.startsWith('/') || pointer.length === 1) return null;
  const encodedSegments = pointer.slice(1).split('/');
  if (encodedSegments.length > 32) return null;
  let current = context;
  let mayBeAbsent = false;
  for (let index = 0; index < encodedSegments.length; index += 1) {
    const encoded = encodedSegments[index] ?? '';
    if (/(?:~(?![01]))/.test(encoded)) return null;
    const fieldKey = encoded.replaceAll('~1', '/').replaceAll('~0', '~');
    const field = current.fields.find((candidate) => candidate.fieldKey === fieldKey);
    if (!field) return null;
    mayBeAbsent ||= !field.required;
    if (index === encodedSegments.length - 1) {
      return Object.freeze({ value: field.value, mayBeAbsent });
    }
    if (field.value.type !== 'reference' || !field.value.ref.versionTag) return null;
    const referenced = catalog.get(schemaIdentityKey({
      schemaKey: field.value.ref.schemaKey,
      versionTag: field.value.ref.versionTag,
    }));
    if (!referenced) return null;
    current = referenced;
  }
  return null;
}

function definitionRecords(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter(isRecord) : [];
}

function definitionOutputType(
  definition: Readonly<Record<string, unknown>>,
): TemplateBindingValueType | null {
  const candidate = definition.kind === 'custom' ? definition.valueType : definition.output;
  return isBindingValueType(candidate) ? candidate : null;
}

function isBindingValueType(value: unknown): value is TemplateBindingValueType {
  return value === 'text' || value === 'decimal' || value === 'boolean'
    || value === 'date' || value === 'time'
    || value === 'color' || value === 'imageRef' || value === 'fontRef';
}

function definitionLoopId(definition: Readonly<Record<string, unknown>>): string | null {
  const domain = isRecord(definition.domain) ? definition.domain : null;
  return domain?.kind === 'loop' && typeof domain.loopId === 'string'
    ? domain.loopId
    : null;
}

function validateDefinitionDomainAgainstTree(
  definition: Readonly<Record<string, unknown>>,
  designRoot: unknown,
): { code: string; message: string } | null {
  const loopId = definitionLoopId(definition);
  if (loopId === null) return null;
  const authored = collectAuthoredLoopIds(designRoot);
  return authored.has(loopId)
    ? null
    : {
      code: 'DEFINITION_DOMAIN_OUT_OF_SCOPE',
      message: '定义选择的循环域已不在当前 DesignDSL 结构中。',
    };
}

function validateDefinitionSourcesAgainstScope(
  definition: Readonly<Record<string, unknown>>,
  designRoot: unknown,
  definitions: readonly Readonly<Record<string, unknown>>[],
): { code: string; message: string } | null {
  if (definition.kind === 'custom') return null;
  const domainLoopId = definitionLoopId(definition);
  const available = domainLoopId === null
    ? new Set<string>()
    : loopLexicalScopeIds(designRoot, domainLoopId);
  for (const source of definitionValueSources(definition)) {
    const candidate = isRecord(source) ? source : null;
    if (!candidate) continue;
    if (candidate.kind === 'context') {
      const sourceLoopId = isRecord(candidate.domain)
        && candidate.domain.kind === 'loop'
        && typeof candidate.domain.loopId === 'string'
        ? candidate.domain.loopId
        : null;
      if (sourceLoopId !== null && !available.has(sourceLoopId)) {
        return {
          code: 'DEFINITION_SOURCE_OUT_OF_SCOPE',
          message: '定义来源引用了当前作用域不可见的循环。',
        };
      }
    } else if (candidate.kind === 'loopIndex') {
      if (typeof candidate.loopId === 'string' && !available.has(candidate.loopId)) {
        return {
          code: 'DEFINITION_SOURCE_OUT_OF_SCOPE',
          message: '定义来源引用了当前作用域不可见的循环索引。',
        };
      }
    } else if (candidate.kind === 'definition' && typeof candidate.definitionId === 'string') {
      const referenced = definitions.find((entry) => (
        entry.definitionId === candidate.definitionId
      ));
      if (!referenced) {
        return {
          code: 'DEFINITION_SOURCE_NOT_FOUND',
          message: '定义来源引用的定义不存在。',
        };
      }
      const referencedLoopId = definitionLoopId(referenced);
      if (referencedLoopId !== null && !available.has(referencedLoopId)) {
        return {
          code: 'DEFINITION_SOURCE_OUT_OF_SCOPE',
          message: '定义来源引用了当前作用域不可见的派生定义。',
        };
      }
    }
  }
  return null;
}

function definitionValueSources(
  definition: Readonly<Record<string, unknown>>,
): readonly unknown[] {
  if (definition.kind === 'expression') {
    return Array.isArray(definition.inputs)
      ? definition.inputs.flatMap((entry) => {
        const input = isRecord(entry) ? entry : null;
        return input && Object.hasOwn(input, 'source') ? [input.source] : [];
      })
      : [];
  }
  if (definition.kind !== 'mapping') return [];
  const sources: unknown[] = [definition.input, definition.otherwise];
  if (Array.isArray(definition.cases)) {
    for (const entry of definition.cases) {
      const mappingCase = isRecord(entry) ? entry : null;
      if (mappingCase && Object.hasOwn(mappingCase, 'then')) sources.push(mappingCase.then);
    }
  }
  return sources;
}

function validateMappingKnownTypes(
  definition: Readonly<Record<string, unknown>>,
  designRoot: unknown,
  definitions: readonly Readonly<Record<string, unknown>>[],
  staticSchema?: StaticSnapshot,
  staticSchemas: readonly StaticSnapshot[] = [],
): { code: string; message: string } | null {
  if (definition.kind !== 'mapping' || !Array.isArray(definition.cases)) return null;
  const rootContext = staticSchema ? schemaContext(staticSchema) : null;
  const catalog = staticSchema ? staticSchemaCatalog(staticSchema, staticSchemas) : null;
  const loopContexts = rootContext && catalog
    ? loopContextsForDefinition(
      designRoot,
      definitionLoopId(definition),
      rootContext,
      definitions,
      catalog,
    )
    : [];
  const inputProof = valueSourceTypeProof(
    definition.input,
    definitions,
    rootContext,
    catalog,
    loopContexts,
  );
  if (inputProof.state === 'invalid') {
    return { code: 'DEFINITION_SOURCE_TYPE_INVALID', message: '映射输入不是可求值的已知类型。' };
  }
  if (inputProof.state === 'known' && definition.cases.some((entry) => (
    isRecord(entry)
      && isRecord(entry.operand)
      && !sameValueType(inputProof.valueType, entry.operand.valueType)
  ))) {
    return { code: 'DEFINITION_INPUT_MISMATCH', message: '映射分支操作数必须与输入类型一致。' };
  }
  const resultSources = [
    ...definition.cases.flatMap((entry) => (
      isRecord(entry) && Object.hasOwn(entry, 'then') ? [entry.then] : []
    )),
    definition.otherwise,
  ];
  if (resultSources.some((source) => {
    const proof = valueSourceTypeProof(
      source,
      definitions,
      rootContext,
      catalog,
      loopContexts,
    );
    return proof.state === 'invalid'
      || proof.state === 'known' && !sameValueType(proof.valueType, definition.output);
  })) {
    return { code: 'DEFINITION_OUTPUT_MISMATCH', message: '映射分支结果必须是可求值且与输出一致的类型。' };
  }
  return null;
}

type ValueSourceTypeProof =
  | { readonly state: 'known'; readonly valueType: unknown }
  | { readonly state: 'unknown' }
  | { readonly state: 'invalid' };

function valueSourceTypeProof(
  source: unknown,
  definitions: readonly Readonly<Record<string, unknown>>[],
  rootContext: TemplateSchemaContext | null,
  catalog: ReadonlyMap<string, TemplateSchemaContext> | null,
  loopContexts: readonly TemplateLoopContext[],
): ValueSourceTypeProof {
  if (!isRecord(source)) return { state: 'invalid' };
  if (source.kind === 'literal') {
    return source.valueType === undefined
      ? { state: 'invalid' }
      : { state: 'known', valueType: source.valueType };
  }
  if (source.kind === 'loopIndex') return { state: 'known', valueType: 'decimal' };
  if (source.kind === 'definition' && typeof source.definitionId === 'string') {
    const referenced = definitions.find((entry) => entry.definitionId === source.definitionId);
    if (!referenced) return { state: 'invalid' };
    const valueType = referenced.kind === 'custom' ? referenced.valueType : referenced.output;
    return valueType === undefined
      ? { state: 'invalid' }
      : { state: 'known', valueType };
  }
  if (source.kind !== 'context' || typeof source.pointer !== 'string') {
    return { state: 'unknown' };
  }
  if (!catalog) return { state: 'unknown' };
  const loopId = isRecord(source.domain) && source.domain.kind === 'loop'
    && typeof source.domain.loopId === 'string'
    ? source.domain.loopId
    : null;
  const context = source.domain === 'invocation'
    ? rootContext
    : loopId === null
      ? null
      : loopContexts.find((candidate) => candidate.loopId === loopId)?.context ?? null;
  if (!context) return { state: 'invalid' };
  const selected = resolveSchemaPointer(context, source.pointer, catalog);
  if (!selected) return { state: 'invalid' };
  const valueType = schemaValueDefinitionType(selected.value);
  return valueType === null
    ? { state: 'invalid' }
    : { state: 'known', valueType };
}

function loopContextsForDefinition(
  designRoot: unknown,
  targetLoopId: string | null,
  rootContext: TemplateSchemaContext,
  definitions: readonly Readonly<Record<string, unknown>>[],
  catalog: ReadonlyMap<string, TemplateSchemaContext>,
): readonly TemplateLoopContext[] {
  if (targetLoopId === null) return [];
  const root = isRecord(designRoot) ? designRoot : null;
  if (!root) return [];
  let result: readonly TemplateLoopContext[] | null = null;
  const visit = (
    node: Readonly<Record<string, unknown>>,
    scope: readonly TemplateLoopContext[],
  ): boolean => {
    let childScope = scope;
    if (node.kind === 'repeat' && typeof node.loopId === 'string') {
      const context = resolveRepeatItemContext(
        node.items,
        rootContext,
        scope,
        definitions,
        catalog,
      );
      childScope = [...scope, Object.freeze({ loopId: node.loopId, context })];
      if (node.loopId === targetLoopId) {
        result = childScope;
        return true;
      }
    }
    return Array.isArray(node.children) && node.children.some((candidate) => {
      const child = isRecord(candidate) ? candidate : null;
      return child ? visit(child, childScope) : false;
    });
  };
  visit(root, []);
  return result ?? [];
}

function schemaValueDefinitionType(value: PersistedValue): unknown | null {
  if (value.type === 'reference') return null;
  if (value.type !== 'array') return value.type;
  return value.items.type === 'reference'
    ? null
    : Object.freeze({ type: 'list', items: value.items.type });
}

function validateDefinitionGraph(
  definitions: readonly Readonly<Record<string, unknown>>[],
): { code: string; message: string } | null {
  const edges = new Map<string, string[]>();
  let edgeCount = 0;
  for (const definition of definitions) {
    if (typeof definition.definitionId !== 'string') continue;
    const references = definitionValueSources(definition).flatMap((source) => {
      const candidate = isRecord(source) ? source : null;
      return candidate?.kind === 'definition' && typeof candidate.definitionId === 'string'
        ? [candidate.definitionId]
        : [];
    });
    edgeCount += references.length;
    edges.set(definition.definitionId, references);
  }
  if (edgeCount > 8192) return invalidDefinitionGraph();

  const state = new Map<string, 'visiting' | 'visited'>();
  const depth = new Map<string, number>();
  const visit = (definitionId: string, stackDepth: number): number | null => {
    if (stackDepth > 65) return null;
    if (!edges.has(definitionId) || state.get(definitionId) === 'visiting') return null;
    if (state.get(definitionId) === 'visited') return depth.get(definitionId) ?? 0;
    state.set(definitionId, 'visiting');
    let longest = 0;
    for (const targetId of edges.get(definitionId) ?? []) {
      const targetDepth = visit(targetId, stackDepth + 1);
      if (targetDepth === null) return null;
      longest = Math.max(longest, targetDepth + 1);
      if (longest > 64) return null;
    }
    state.set(definitionId, 'visited');
    depth.set(definitionId, longest);
    return longest;
  };
  for (const definitionId of edges.keys()) {
    if (visit(definitionId, 1) === null) return invalidDefinitionGraph();
  }
  return null;
}

function invalidDefinitionGraph(): { code: string; message: string } {
  return {
    code: 'DEFINITION_GRAPH_INVALID',
    message: '定义引用必须全部存在、无环，且引用链深度不超过 64。',
  };
}

function validateDefinitionCapacity(
  definitions: readonly Readonly<Record<string, unknown>>[],
): { code: string; message: string } | null {
  if (definitions.length > 512) {
    return {
      code: 'DEFINITION_CAPACITY_EXCEEDED',
      message: 'DesignDSL 最多允许 512 个定义。',
    };
  }
  let expressionSourceBytes = 0;
  let expressionInputs = 0;
  let expressionAstNodes = 0;
  let mappingCases = 0;
  for (const definition of definitions) {
    if (definition.kind === 'mapping' && Array.isArray(definition.cases)) {
      mappingCases += definition.cases.length;
      if (mappingCases > 8_192) {
        return {
          code: 'DEFINITION_CAPACITY_EXCEEDED',
          message: '全部映射分支合计不能超过 8192 个。',
        };
      }
      continue;
    }
    if (definition.kind !== 'expression' || typeof definition.source !== 'string') continue;
    expressionSourceBytes += new TextEncoder().encode(definition.source).byteLength;
    if (expressionSourceBytes > 1_048_576) {
      return {
        code: 'DEFINITION_CAPACITY_EXCEEDED',
        message: '全部表达式源码合计不能超过 1 MiB。',
      };
    }
    expressionInputs += Array.isArray(definition.inputs) ? definition.inputs.length : 0;
    if (expressionInputs > 4_096) {
      return {
        code: 'DEFINITION_CAPACITY_EXCEEDED',
        message: '全部表达式输入合计不能超过 4096 个。',
      };
    }
    const parsed = parseTemplateExpressionSource(definition.source);
    if (!parsed.valid) {
      return {
        code: 'DEFINITION_EXPRESSION_INVALID',
        message: '现有表达式超出客户端可保守计量的语法范围。',
      };
    }
    expressionAstNodes += parsed.astNodes;
    if (expressionAstNodes > 65_536) {
      return {
        code: 'DEFINITION_CAPACITY_EXCEEDED',
        message: '全部表达式 AST 节点合计不能超过 65536 个。',
      };
    }
  }
  return null;
}

function loopLexicalScopeIds(designRoot: unknown, targetLoopId: string): Set<string> {
  const found = new Set<string>();
  const visit = (
    value: unknown,
    ancestors: readonly string[],
  ): boolean => {
    const node = isRecord(value) ? value : null;
    if (!node) return false;
    const ownLoopId = node.kind === 'repeat' && typeof node.loopId === 'string'
      ? node.loopId
      : null;
    const scope = ownLoopId === null ? ancestors : [...ancestors, ownLoopId];
    if (ownLoopId === targetLoopId) {
      scope.forEach((loopId) => found.add(loopId));
      return true;
    }
    return Array.isArray(node.children)
      && node.children.some((child) => visit(child, scope));
  };
  visit(designRoot, []);
  return found;
}

function collectAuthoredLoopIds(value: unknown, target = new Set<string>()): Set<string> {
  const node = isRecord(value) ? value : null;
  if (!node) return target;
  if (node.kind === 'repeat' && typeof node.loopId === 'string') target.add(node.loopId);
  if (Array.isArray(node.children)) {
    node.children.forEach((child) => collectAuthoredLoopIds(child, target));
  }
  return target;
}

function stringMember(value: Readonly<Record<string, unknown>>, member: string): string | null {
  return typeof value[member] === 'string' ? value[member] : null;
}

function definitionKindLabel(value: unknown): string {
  switch (value) {
    case 'custom': return '定义数据';
    case 'mapping': return '映射数据';
    case 'expression': return '表达式数据';
    default: return '未知定义';
  }
}

export function bindingValueTypeLabel(value: TemplateBindingValueType): string {
  switch (value) {
    case 'text': return '文本';
    case 'decimal': return '数值';
    case 'boolean': return '布尔';
    case 'date': return '日期';
    case 'time': return '时间';
    case 'color': return '颜色';
    case 'imageRef': return '图片 Asset';
    case 'fontRef': return '字体 Asset';
  }
}

function shortIdentity(value: string): string {
  return value.length <= 12 ? value : `${value.slice(0, 8)}…${value.slice(-4)}`;
}

function validateDefinitionShell(
  definition: Readonly<Record<string, unknown>>,
): { code: string; message: string } | null {
  const normalizedName = typeof definition.displayName === 'string'
    ? normalizeTemplateEditorDisplayName(definition.displayName)
    : { state: 'invalid' as const };
  if (normalizedName.state === 'invalid' || normalizedName.value !== definition.displayName) {
    return { code: 'DEFINITION_NAME_INVALID', message: '定义名称必须是已整理的 1–128 个有效字符。' };
  }
  switch (definition.kind) {
    case 'custom':
      if (!exactMembers(definition, [
        'definitionId', 'kind', 'displayName', 'exposure', 'valueType', 'defaultValue',
      ])) {
        return { code: 'DEFINITION_WIRE_INVALID', message: '定义含有缺失或未知字段。' };
      }
      if (definition.exposure !== 'PUBLIC' && definition.exposure !== 'PRIVATE') {
        return { code: 'DEFINITION_EXPOSURE_INVALID', message: '定义可见性必须是 PUBLIC 或 PRIVATE。' };
      }
      if (!validDefinitionValueType(definition.valueType)
        || !literalMatchesValueType(definition.defaultValue, definition.valueType)) {
        return { code: 'DEFINITION_DEFAULT_INVALID', message: '默认值与定义类型不一致。' };
      }
      return null;
    case 'mapping':
      return validateMappingDefinition(definition);
    case 'expression':
      return validateExpressionDefinition(definition);
    default:
      return { code: 'DEFINITION_KIND_UNSUPPORTED', message: '定义种类不受当前 DesignDSL 支持。' };
  }
}

function validateExpressionDefinition(
  definition: Readonly<Record<string, unknown>>,
): { code: string; message: string } | null {
  if (!exactMembers(definition, [
    'definitionId', 'kind', 'displayName', 'domain', 'output', 'inputs', 'source',
  ])) {
    return { code: 'DEFINITION_WIRE_INVALID', message: '表达式定义含有缺失或未知字段。' };
  }
  if (!validDefinitionDomain(definition.domain)) {
    return { code: 'DEFINITION_DOMAIN_INVALID', message: '表达式定义域必须是调用域或具体循环域。' };
  }
  if (!validDefinitionValueType(definition.output)) {
    return { code: 'DEFINITION_OUTPUT_INVALID', message: '表达式输出类型无效。' };
  }
  if (!Array.isArray(definition.inputs)) {
    return { code: 'DEFINITION_INPUT_INVALID', message: '表达式输入必须是数组。' };
  }
  if (definition.inputs.length > 32) {
    return { code: 'DEFINITION_CAPACITY_EXCEEDED', message: '单个表达式最多允许 32 个输入。' };
  }
  const aliases = new Set<string>();
  for (const entry of definition.inputs) {
    if (!isRecord(entry) || !exactMembers(entry, ['alias', 'source'])
      || typeof entry.alias !== 'string'
      || !/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(entry.alias)
      || aliases.has(entry.alias)
      || !validValueSource(entry.source, true)) {
      return { code: 'DEFINITION_INPUT_INVALID', message: '表达式输入别名或 ValueSource 无效。' };
    }
    aliases.add(entry.alias);
  }
  if (typeof definition.source !== 'string' || definition.source.trim().length === 0) {
    return { code: 'DEFINITION_EXPRESSION_INVALID', message: '表达式源码不能为空。' };
  }
  const expression = parseTemplateExpressionSource(definition.source);
  if (!expression.valid || [...aliases].some((alias) => !expression.usedAliases.has(alias))) {
    return {
      code: 'DEFINITION_EXPRESSION_INVALID',
      message: '表达式源码不符合 renderweave-expression/1.0，或存在未使用的输入别名。',
    };
  }
  return null;
}

function validateMappingDefinition(
  definition: Readonly<Record<string, unknown>>,
): { code: string; message: string } | null {
  if (!exactMembers(definition, [
    'definitionId', 'kind', 'displayName', 'domain', 'output',
    'input', 'cases', 'otherwise',
  ])) {
    return { code: 'DEFINITION_WIRE_INVALID', message: '映射定义含有缺失或未知字段。' };
  }
  if (!validDefinitionDomain(definition.domain)) {
    return { code: 'DEFINITION_DOMAIN_INVALID', message: '映射定义域必须是调用域或具体循环域。' };
  }
  if (!validDefinitionValueType(definition.output)) {
    return { code: 'DEFINITION_OUTPUT_INVALID', message: '映射输出类型无效。' };
  }
  if (!validValueSource(definition.input, false)
    || !validValueSource(definition.otherwise, false)) {
    return { code: 'DEFINITION_SOURCE_INVALID', message: '映射来源必须是 closed 的非能力 ValueSource。' };
  }
  if (!sourceLiteralMatchesOutput(definition.otherwise, definition.output)) {
    return { code: 'DEFINITION_OUTPUT_MISMATCH', message: '映射默认来源与输出类型不一致。' };
  }
  if (!Array.isArray(definition.cases) || definition.cases.length === 0) {
    return { code: 'DEFINITION_CASE_INVALID', message: '映射至少需要一个合法分支，且分支输出必须匹配输出类型。' };
  }
  if (definition.cases.length > 256) {
    return { code: 'DEFINITION_CAPACITY_EXCEEDED', message: '单个映射最多允许 256 个分支。' };
  }
  if (!definition.cases.every((entry) => validMappingCase(entry, definition.output))) {
    return { code: 'DEFINITION_CASE_INVALID', message: '映射至少需要一个合法分支，且分支输出必须匹配输出类型。' };
  }
  const literalInputType = literalSourceValueType(definition.input);
  if (literalInputType !== null && definition.cases.some((entry) => (
    isRecord(entry)
      && isRecord(entry.operand)
      && !sameValueType(literalInputType, entry.operand.valueType)
  ))) {
    return { code: 'DEFINITION_INPUT_MISMATCH', message: '映射分支操作数必须与输入类型一致。' };
  }
  return null;
}

const MAPPING_OPERATORS = new Set([
  'IS_ABSENT', 'IS_PRESENT', 'EQ', 'NOT_EQ', 'GT', 'GTE', 'LT', 'LTE',
  'CONTAINS', 'STARTS_WITH', 'ENDS_WITH', 'PATTERN_MATCH', 'IS_BLANK', 'IS_NOT_BLANK',
]);
const OPERATORS_WITHOUT_OPERAND = new Set(['IS_ABSENT', 'IS_PRESENT']);

function validMappingCase(value: unknown, output: unknown): boolean {
  if (!isRecord(value) || typeof value.operator !== 'string'
    || !MAPPING_OPERATORS.has(value.operator)
    || !validValueSource(value.then, false)
    || !sourceLiteralMatchesOutput(value.then, output)) return false;
  const withoutOperand = OPERATORS_WITHOUT_OPERAND.has(value.operator);
  if (withoutOperand) {
    return value.operand === undefined && exactMembers(value, ['operator', 'then']);
  }
  if (!exactMembers(value, ['operator', 'operand', 'then']) || !isRecord(value.operand)
    || !exactMembers(value.operand, ['valueType', 'value'])
    || !validDefinitionValueType(value.operand.valueType)) return false;
  return literalMatchesValueType(value.operand.value, value.operand.valueType);
}

function validDefinitionDomain(value: unknown): boolean {
  if (value === 'invocation') return true;
  return isRecord(value)
    && exactMembers(value, ['kind', 'loopId'])
    && value.kind === 'loop'
    && typeof value.loopId === 'string'
    && UUID_V4.test(value.loopId);
}

function validValueSource(value: unknown, allowCapability: boolean): boolean {
  if (!isRecord(value) || typeof value.kind !== 'string') return false;
  switch (value.kind) {
    case 'literal':
      return exactMembers(value, ['kind', 'valueType', 'value'])
        && validDefinitionValueType(value.valueType)
        && literalMatchesValueType(value.value, value.valueType);
    case 'context':
      return exactMembers(value, ['kind', 'domain', 'pointer'])
        && validDefinitionDomain(value.domain)
        && typeof value.pointer === 'string'
        && validContextPointer(value.pointer);
    case 'loopIndex':
      return exactMembers(value, ['kind', 'loopId'])
        && typeof value.loopId === 'string'
        && UUID_V4.test(value.loopId);
    case 'definition':
      return exactMembers(value, ['kind', 'definitionId'])
        && typeof value.definitionId === 'string'
        && UUID_V4.test(value.definitionId);
    case 'capability':
      if (!allowCapability || !exactMembers(value, ['kind', 'capability', 'operation'])) return false;
      return value.capability === 'CLOCK'
        ? value.operation === 'UTC_DATE' || value.operation === 'UTC_TIME'
        : value.capability === 'RANDOM' && value.operation === 'UNIFORM_DECIMAL_0_1';
    default:
      return false;
  }
}

function validContextPointer(pointer: string): boolean {
  if (pointer.length === 0 || !pointer.startsWith('/') || pointer === '/') return false;
  let segments = 0;
  for (let index = 0; index < pointer.length; index += 1) {
    const current = pointer[index];
    if (current === '/') {
      segments += 1;
    } else if (current === '~' && pointer[index + 1] !== '0' && pointer[index + 1] !== '1') {
      return false;
    }
  }
  if (segments > 32) return false;
  const decoded = pointer.replaceAll('~1', '/').replaceAll('~0', '~');
  return new TextEncoder().encode(decoded).byteLength <= 1024;
}

function sourceLiteralMatchesOutput(source: unknown, output: unknown): boolean {
  return !isRecord(source) || source.kind !== 'literal'
    || canonicalStringifyWorkingValue(source.valueType)
      === canonicalStringifyWorkingValue(output);
}

function literalSourceValueType(source: unknown): unknown | null {
  return isRecord(source) && source.kind === 'literal' ? source.valueType : null;
}

function sameValueType(left: unknown, right: unknown): boolean {
  return canonicalStringifyWorkingValue(left) === canonicalStringifyWorkingValue(right);
}

function validDefinitionValueType(value: unknown): boolean {
  if (typeof value === 'string') {
    return new Set([
      'text', 'decimal', 'boolean', 'date', 'time', 'color', 'imageRef', 'fontRef',
    ]).has(value);
  }
  if (!isRecord(value)) return false;
  if (value.type === 'list') {
    return typeof value.items === 'string' && new Set([
      'text', 'decimal', 'boolean', 'date', 'time', 'imageRef', 'fontRef',
    ]).has(value.items) && exactMembers(value, ['type', 'items']);
  }
  // renderweave-design/1.0 defines the enum wire shape, but registers no
  // global enum catalogs. Without an exact catalog authority every enum must
  // fail closed, matching CanonicalDesignDslAuthority.
  return false;
}

function literalMatchesValueType(value: unknown, valueType: unknown): boolean {
  if (isRecord(valueType) && valueType.type === 'list') {
    return Array.isArray(value)
      && value.every((entry) => literalMatchesValueType(entry, valueType.items));
  }
  switch (valueType) {
    case 'text':
      return typeof value === 'string';
    case 'date':
      return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value);
    case 'time':
      return typeof value === 'string' && /^\d{2}:\d{2}:\d{2}$/.test(value);
    case 'color':
      return typeof value === 'string' && /^#[0-9A-F]{8}$/.test(value);
    case 'decimal':
      return typeof value === 'number' && Number.isFinite(value)
        || isLosslessNumericToken(value);
    case 'boolean':
      return typeof value === 'boolean';
    case 'imageRef':
    case 'fontRef':
      return isRecord(value) && exactMembers(value, ['assetId'])
        && typeof value.assetId === 'string'
        && UUID_V4.test(value.assetId);
    default:
      return false;
  }
}

function isLosslessNumericToken(value: unknown): boolean {
  if (typeof value !== 'object' || value === null || !('toString' in value)) return false;
  return /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/.test(String(value));
}

function exactMembers(
  value: Readonly<Record<string, unknown>>,
  expected: readonly string[],
): boolean {
  const actual = Object.keys(value).sort();
  const sortedExpected = [...expected].sort();
  return actual.length === sortedExpected.length
    && actual.every((member, index) => member === sortedExpected[index]);
}

function defaultUuid(): string {
  return globalThis.crypto.randomUUID();
}

function dataRejected(
  session: StructuredEditorSession,
  code: string,
  message: string,
): Extract<TemplateDataAuthoringResult, { state: 'rejected' }> {
  return { state: 'rejected', session, code, message };
}

function findAuthoredNode(value: unknown, nodeId: string): Record<string, unknown> | null {
  const node = isRecord(value) ? value : null;
  if (!node) return null;
  if (node.nodeId === nodeId) return node;
  if (!Array.isArray(node.children)) return null;
  for (const child of node.children) {
    const found = findAuthoredNode(child, nodeId);
    if (found) return found;
  }
  return null;
}

function hasAuthoredBindingId(value: unknown, bindingId: string): boolean {
  const node = isRecord(value) ? value : null;
  if (!node) return false;
  if (Array.isArray(node.bindings) && node.bindings.some((binding) => (
    isRecord(binding) && binding.bindingId === bindingId
  ))) return true;
  return Array.isArray(node.children)
    && node.children.some((child) => hasAuthoredBindingId(child, bindingId));
}

function countAuthoredBindings(value: unknown, stopAt: number): number {
  const pending = [value];
  let total = 0;
  while (pending.length > 0) {
    const candidate = pending.pop();
    const node = isRecord(candidate) ? candidate : null;
    if (!node) continue;
    if (Array.isArray(node.bindings)) {
      total += node.bindings.length;
      if (total >= stopAt) return total;
    }
    if (Array.isArray(node.children)) pending.push(...node.children);
  }
  return total;
}

function sameBindingSource(left: TemplateBindingSource, right: TemplateBindingSource): boolean {
  return canonicalStringifyWorkingValue(left as unknown as Record<string, unknown>)
    === canonicalStringifyWorkingValue(right as unknown as Record<string, unknown>);
}

function targetPathOf(value: unknown): string | null {
  const target = isRecord(value) ? value : null;
  if (!target || typeof target.rootPropertyId !== 'string' || !Array.isArray(target.selectors)) {
    return null;
  }
  if (target.selectors.length > 2) return null;
  let index: number | null = null;
  let member: string | null = null;
  for (const candidate of target.selectors) {
    const selector = isRecord(candidate) ? candidate : null;
    if (selector?.kind === 'member' && typeof selector.name === 'string') {
      if (member !== null) return null;
      member = selector.name;
    } else if (selector?.kind === 'index') {
      const parsedIndex = numericValue(selector.index);
      if (parsedIndex === null || index !== null) return null;
      index = parsedIndex;
    } else {
      return null;
    }
  }
  let path = target.rootPropertyId;
  if (index !== null) path += `[${index}]`;
  if (member !== null) path += `.${member}`;
  return path;
}

function pathsOverlap(left: string, right: string): boolean {
  return left === right || isPathAncestor(left, right) || isPathAncestor(right, left);
}

function isPathAncestor(ancestor: string, descendant: string): boolean {
  return descendant.startsWith(`${ancestor}.`) || descendant.startsWith(`${ancestor}[`);
}
