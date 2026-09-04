import type { StaticSnapshot } from '../schema-studio/lossless-api';

export interface TemplateStructuralSchemaRef {
  readonly schemaKey: string;
  readonly versionTag: string;
}

export type TemplateStructuralPresence = 'CONCRETE' | 'MAY_BE_ABSENT';

export interface TemplateRepeatSourceOption {
  readonly id: string;
  readonly label: string;
  readonly source: Readonly<Record<string, unknown>>;
  readonly origin: 'static-schema' | 'definition' | 'loop';
  readonly presence: TemplateStructuralPresence;
  readonly itemContext: TemplateStructuralSchemaRef;
  readonly itemKind: 'scalar' | 'reference';
  readonly declarationLoopId?: string;
}

export interface TemplateBooleanSourceOption {
  readonly id: string;
  readonly label: string;
  readonly source: Readonly<Record<string, unknown>>;
  readonly origin: 'static-schema' | 'definition' | 'loop';
  readonly presence: TemplateStructuralPresence;
  readonly declarationLoopId?: string;
}

export interface TemplateLoopContextProjection {
  readonly repeatNodeId: string;
  readonly loopId: string;
  readonly ancestorLoopIds: readonly string[];
  readonly itemContext: TemplateStructuralSchemaRef;
  readonly itemKind: 'scalar' | 'reference';
}

export interface TemplateTargetOption {
  readonly templateId: string;
  readonly displayName: string;
  readonly staticSchema: TemplateStructuralSchemaRef;
  readonly readiness: 'READY' | 'INVALID' | 'STALE';
  readonly state: 'eligible' | 'incompatible' | 'unavailable';
}

export interface TemplateUseContextOption {
  readonly id: string;
  readonly label: string;
  readonly selector: Readonly<Record<string, unknown>>;
  readonly schema: TemplateStructuralSchemaRef;
  readonly presence: TemplateStructuralPresence;
}

export interface TemplateUseFillSourceOption {
  readonly id: string;
  readonly label: string;
  readonly source: Readonly<Record<string, unknown>>;
  readonly valueType: unknown;
  readonly presence: TemplateStructuralPresence;
}

export interface TemplateUseFillTarget {
  readonly definitionId: string;
  readonly displayName: string;
  readonly valueType: unknown;
  readonly sources: readonly TemplateUseFillSourceOption[];
}

export interface TemplateUseFillProjection {
  readonly targetDefinitionId: string;
  readonly state: 'READY' | 'INVALID' | 'NEEDS_REPAIR';
  readonly problem?: string;
}

export interface TemplateStructuralAuthoringInput {
  readonly designDsl: Readonly<Record<string, unknown>>;
  readonly staticSchema: StaticSnapshot;
  readonly staticSchemas?: readonly StaticSnapshot[];
  readonly templateCatalog?: readonly Readonly<Record<string, unknown>>[];
  readonly templateCurrents?: readonly Readonly<Record<string, unknown>>[];
  readonly sample?: Readonly<Record<string, TemplateStructuralSample>>;
  readonly priorItemContexts?: Readonly<Record<string, TemplateStructuralSchemaRef>>;
}

export type TemplateStructuralSample =
  | { readonly state: 'value'; readonly value: unknown }
  | { readonly state: 'absent' }
  | { readonly state: 'error'; readonly code: string };

export type TemplateRepeatRuntimeProjection =
  | { readonly state: 'UNSAMPLED'; readonly occurrences: readonly [] }
  | { readonly state: 'EMPTY'; readonly occurrences: readonly [] }
  | { readonly state: 'ABSENT_ERROR'; readonly occurrences: readonly [] }
  | { readonly state: 'SOURCE_ERROR'; readonly code: string; readonly occurrences: readonly [] }
  | {
    readonly state: 'VALUES';
    readonly occurrences: readonly {
      readonly inputIndex: number;
      readonly itemContext: TemplateStructuralSchemaRef;
      readonly value: unknown;
    }[];
  };

export interface TemplateRepeatNodeState {
  readonly kind: 'repeat';
  readonly authoringState: 'READY' | 'NEEDS_REPAIR' | 'INVALID';
  readonly itemContext: TemplateStructuralSchemaRef | null;
  readonly authoredChildren: readonly unknown[];
  readonly runtime: TemplateRepeatRuntimeProjection;
  readonly problems: readonly string[];
}

export type TemplateConditionalRuntimeProjection =
  | { readonly state: 'UNSAMPLED' }
  | { readonly state: 'TRUE' }
  | { readonly state: 'FALSE' }
  | { readonly state: 'ABSENT_ERROR' }
  | { readonly state: 'SOURCE_ERROR'; readonly code: string };

export interface TemplateConditionalNodeState {
  readonly kind: 'conditional';
  readonly authoringState: 'READY' | 'INVALID';
  readonly authoredChildren: readonly unknown[];
  readonly runtime: TemplateConditionalRuntimeProjection;
  readonly problems: readonly string[];
}

export interface TemplateUseNodeState {
  readonly kind: 'templateUse';
  readonly authoringState: 'READY' | 'NEEDS_REPAIR' | 'INVALID';
  readonly contextOptions: readonly TemplateUseContextOption[];
  readonly context:
    | { readonly state: 'READY'; readonly schema: TemplateStructuralSchemaRef }
    | { readonly state: 'INVALID'; readonly problem: string };
  readonly fillTargets: readonly TemplateUseFillTarget[];
  readonly fills: readonly TemplateUseFillProjection[];
  readonly sourceCanvasSizeMm?: Readonly<{ readonly widthMm: number; readonly heightMm: number }>;
  readonly problems: readonly string[];
}

export type TemplateStructuralNodeState =
  | TemplateRepeatNodeState
  | TemplateConditionalNodeState
  | TemplateUseNodeState;

export interface TemplateStructuralAuthoringProjection {
  readonly repeatSources: Readonly<Record<string, readonly TemplateRepeatSourceOption[]>>;
  readonly booleanSources: Readonly<Record<string, readonly TemplateBooleanSourceOption[]>>;
  readonly templateTargets: Readonly<Record<string, readonly TemplateTargetOption[]>>;
  readonly loopContexts: readonly TemplateLoopContextProjection[];
  readonly nodeStates: Readonly<Record<string, TemplateStructuralNodeState>>;
}

export interface TemplateUseInsertionCandidate {
  readonly templateId: string;
  readonly contextSelector: Readonly<Record<string, unknown>>;
}

interface SourceCatalog {
  readonly repeat: readonly TemplateRepeatSourceOption[];
  readonly boolean: readonly TemplateBooleanSourceOption[];
}

interface LexicalLoop {
  readonly projection: TemplateLoopContextProjection;
  readonly sources: SourceCatalog;
}

const SCALAR_ITEM_TYPES = new Set(['text', 'decimal', 'date', 'time', 'boolean']);

export function projectStructuralAuthoring(
  input: TemplateStructuralAuthoringInput,
): TemplateStructuralAuthoringProjection {
  const schemas = schemaCatalog(input.staticSchema, input.staticSchemas ?? []);
  const rootSources = contextSources(
    input.staticSchema,
    'invocation',
    'static-schema',
    schemas,
  );
  const definitions = definitionSources(input.designDsl);
  const repeatSources: Record<string, readonly TemplateRepeatSourceOption[]> = {};
  const booleanSources: Record<string, readonly TemplateBooleanSourceOption[]> = {};
  const templateTargets: Record<string, readonly TemplateTargetOption[]> = {};
  const nodeStates: Record<string, TemplateStructuralNodeState> = {};
  const loopContexts: TemplateLoopContextProjection[] = [];
  const root = record(input.designDsl.designRoot);
  walkNodes(array(root?.children), [], (node, loops) => {
    const nodeId = text(node.nodeId);
    if (!nodeId) return;
    const lexicalSources = mergeSources(rootSources, definitions, loops);
    if (node.kind === 'repeat') {
      repeatSources[nodeId] = Object.freeze([...lexicalSources.repeat]);
      nodeStates[nodeId] = projectRepeatNodeState(
        node,
        lexicalSources.repeat,
        input.sample?.[nodeId],
        input.priorItemContexts?.[nodeId],
      );
    } else if (node.kind === 'conditional') {
      booleanSources[nodeId] = Object.freeze([...lexicalSources.boolean]);
      nodeStates[nodeId] = projectConditionalNodeState(
        node,
        lexicalSources.boolean,
        input.sample?.[nodeId],
      );
    } else if (node.kind === 'templateUse') {
      const projected = projectTemplateUseNodeState({
        node,
        loops,
        rootSchema: input.staticSchema,
        schemas,
        designDsl: input.designDsl,
        templateCatalog: input.templateCatalog ?? [],
        templateCurrents: input.templateCurrents ?? [],
      });
      templateTargets[nodeId] = projected.targets;
      nodeStates[nodeId] = projected.state;
    }
  }, (node, loops) => {
    const nodeId = text(node.nodeId);
    const loopId = text(node.loopId);
    if (node.kind !== 'repeat' || !nodeId || !loopId) return null;
    const lexicalSources = mergeSources(rootSources, definitions, loops);
    const selected = lexicalSources.repeat.find(({ source }) => sameSource(source, node.items));
    if (!selected) return null;
    const projection = Object.freeze({
      repeatNodeId: nodeId,
      loopId,
      ancestorLoopIds: Object.freeze(loops.map(({ projection: loop }) => loop.loopId)),
      itemContext: selected.itemContext,
      itemKind: selected.itemKind,
    });
    loopContexts.push(projection);
    return Object.freeze({
      projection,
      sources: loopContextSources(projection, schemas),
    });
  });

  return Object.freeze({
    repeatSources: Object.freeze(repeatSources),
    booleanSources: Object.freeze(booleanSources),
    templateTargets: Object.freeze(templateTargets),
    loopContexts: Object.freeze(loopContexts),
    nodeStates: Object.freeze(nodeStates),
  });
}

export function wholeTemplateContextSelector(
  domain: 'invocation' | Readonly<{ kind: 'loop'; loopId: string }>,
  contextAbsentPolicy: 'ERROR' | 'SKIP' = 'ERROR',
): Readonly<Record<string, unknown>> {
  return Object.freeze({
    kind: 'context',
    domain: domain === 'invocation'
      ? Object.freeze({ kind: 'invocation' })
      : Object.freeze({ kind: 'loop', loopId: domain.loopId }),
    pointer: '',
    contextAbsentPolicy,
  });
}

export function selectTemplateUseInsertionCandidate(
  projection: TemplateStructuralAuthoringProjection,
  nodeId: string,
  templateCatalog: readonly Readonly<Record<string, unknown>>[],
): TemplateUseInsertionCandidate | null {
  const state = projection.nodeStates[nodeId];
  if (state?.kind !== 'templateUse') return null;
  for (const option of state.contextOptions) {
    const target = projectTemplateTargets(
      templateCatalog,
      readyContext(option.schema),
    ).find((candidate) => candidate.state === 'eligible');
    if (!target) continue;
    const selector = record(option.selector);
    const domain = record(selector?.domain);
    return Object.freeze({
      templateId: target.templateId,
      contextSelector: selector?.kind === 'context' && domain?.kind === 'loop'
        ? Object.freeze({ ...option.selector, contextAbsentPolicy: 'SKIP' })
        : option.selector,
    });
  }
  return null;
}

interface ProjectTemplateUseInput {
  readonly node: Readonly<Record<string, unknown>>;
  readonly loops: readonly LexicalLoop[];
  readonly rootSchema: StaticSnapshot;
  readonly schemas: ReadonlyMap<string, StaticSnapshot>;
  readonly designDsl: Readonly<Record<string, unknown>>;
  readonly templateCatalog: readonly Readonly<Record<string, unknown>>[];
  readonly templateCurrents: readonly Readonly<Record<string, unknown>>[];
}

function projectTemplateUseContextOptions(
  input: ProjectTemplateUseInput,
): readonly TemplateUseContextOption[] {
  const options: TemplateUseContextOption[] = [];
  for (const { projection } of [...input.loops].reverse()) {
    const domain = Object.freeze({ kind: 'loop' as const, loopId: projection.loopId });
    appendTemplateUseContextOptions(
      options,
      projection.itemContext,
      input.schemas.get(schemaIdentity(
        projection.itemContext.schemaKey,
        projection.itemContext.versionTag,
      )) ?? null,
      domain,
      `循环 ${projection.loopId.slice(0, 8)} 当前项`,
      input.schemas,
    );
  }
  appendTemplateUseContextOptions(
    options,
    Object.freeze({ schemaKey: input.rootSchema.schemaKey, versionTag: input.rootSchema.versionTag }),
    input.rootSchema,
    'invocation',
    '调用上下文',
    input.schemas,
  );
  options.push(Object.freeze({
    id: 'template-context:empty',
    label: '空上下文',
    selector: Object.freeze({ kind: 'empty' }),
    schema: Object.freeze({ schemaKey: 'system-empty', versionTag: 'v1' }),
    presence: 'CONCRETE',
  }));
  return Object.freeze(options);
}

function appendTemplateUseContextOptions(
  options: TemplateUseContextOption[],
  context: TemplateStructuralSchemaRef,
  schema: StaticSnapshot | null,
  domain: 'invocation' | Readonly<{ kind: 'loop'; loopId: string }>,
  domainLabel: string,
  schemas: ReadonlyMap<string, StaticSnapshot>,
): void {
  options.push(Object.freeze({
    id: templateContextId(domain, ''),
    label: domainLabel,
    selector: wholeTemplateContextSelector(domain),
    schema: context,
    presence: 'CONCRETE',
  }));
  if (!schema) return;
  collectReferenceContextOptions(
    options,
    schema,
    domain,
    domainLabel,
    schemas,
    '',
    true,
    new Set(),
  );
}

function collectReferenceContextOptions(
  options: TemplateUseContextOption[],
  schema: StaticSnapshot,
  domain: 'invocation' | Readonly<{ kind: 'loop'; loopId: string }>,
  domainLabel: string,
  schemas: ReadonlyMap<string, StaticSnapshot>,
  prefix: string,
  concrete: boolean,
  visited: ReadonlySet<string>,
): void {
  const identity = schemaIdentity(schema.schemaKey, schema.versionTag);
  if (visited.has(identity)) return;
  const nextVisited = new Set(visited).add(identity);
  for (const field of schema.definition.fields) {
    if (field.value.type !== 'reference' || !field.value.ref.versionTag) continue;
    const pointer = `${prefix}/${escapePointer(field.fieldKey)}`;
    const present = concrete && field.required;
    const referenced = Object.freeze({
      schemaKey: field.value.ref.schemaKey,
      versionTag: field.value.ref.versionTag,
    });
    options.push(Object.freeze({
      id: templateContextId(domain, pointer),
      label: `${domainLabel} · ${field.displayName ?? field.fieldKey}`,
      selector: templateContextSelector(domain, pointer),
      schema: referenced,
      presence: present ? 'CONCRETE' : 'MAY_BE_ABSENT',
    }));
    const child = schemas.get(schemaIdentity(referenced.schemaKey, referenced.versionTag));
    if (child) {
      collectReferenceContextOptions(
        options,
        child,
        domain,
        domainLabel,
        schemas,
        pointer,
        present,
        nextVisited,
      );
    }
  }
}

function projectTemplateUseFillSources(
  input: ProjectTemplateUseInput,
): readonly TemplateUseFillSourceOption[] {
  const sources: TemplateUseFillSourceOption[] = [];
  collectTemplateUseContextFillSources(
    sources,
    input.rootSchema,
    'invocation',
    input.schemas,
    '',
    true,
    new Set(),
  );
  for (const value of array(input.designDsl.definitions)) {
    const definition = record(value);
    const definitionId = text(definition?.definitionId);
    const valueType = definition?.kind === 'custom' ? definition.valueType : definition?.output;
    const declarationLoopId = record(definition?.domain)?.kind === 'loop'
      ? text(record(definition?.domain)?.loopId)
      : null;
    if (!definitionId || valueType === undefined || (declarationLoopId
      && !input.loops.some(({ projection }) => projection.loopId === declarationLoopId))) continue;
    sources.push(Object.freeze({
      id: `definition:${definitionId}`,
      label: text(definition?.displayName) ?? definitionId,
      source: Object.freeze({ kind: 'definition', definitionId }),
      valueType,
      presence: 'CONCRETE',
    }));
  }
  for (const { projection } of input.loops) {
    const domain = Object.freeze({ kind: 'loop' as const, loopId: projection.loopId });
    if (projection.itemKind === 'scalar') {
      const valueType = systemBasicValueType(projection.itemContext);
      if (valueType) {
        sources.push(Object.freeze({
          id: contextId(domain, '/value'),
          label: `循环 ${projection.loopId.slice(0, 8)} 当前值`,
          source: Object.freeze({ kind: 'context', domain, pointer: '/value' }),
          valueType,
          presence: 'CONCRETE',
        }));
      }
    } else {
      const schema = input.schemas.get(schemaIdentity(
        projection.itemContext.schemaKey,
        projection.itemContext.versionTag,
      ));
      if (schema) {
        collectTemplateUseContextFillSources(
          sources,
          schema,
          domain,
          input.schemas,
          '',
          true,
          new Set(),
        );
      }
    }
    sources.push(Object.freeze({
      id: `loop-index:${projection.loopId}`,
      label: `循环 ${projection.loopId.slice(0, 8)} 索引`,
      source: Object.freeze({ kind: 'loopIndex', loopId: projection.loopId }),
      valueType: 'decimal',
      presence: 'CONCRETE',
    }));
  }
  return Object.freeze(sources);
}

function collectTemplateUseContextFillSources(
  sources: TemplateUseFillSourceOption[],
  schema: StaticSnapshot,
  domain: 'invocation' | Readonly<{ kind: 'loop'; loopId: string }>,
  schemas: ReadonlyMap<string, StaticSnapshot>,
  prefix: string,
  concrete: boolean,
  visited: ReadonlySet<string>,
): void {
  const identity = schemaIdentity(schema.schemaKey, schema.versionTag);
  if (visited.has(identity)) return;
  const nextVisited = new Set(visited).add(identity);
  for (const field of schema.definition.fields) {
    const pointer = `${prefix}/${escapePointer(field.fieldKey)}`;
    const present = concrete && field.required;
    if (field.value.type === 'reference' && field.value.ref.versionTag) {
      const child = schemas.get(schemaIdentity(field.value.ref.schemaKey, field.value.ref.versionTag));
      if (child) {
        collectTemplateUseContextFillSources(
          sources,
          child,
          domain,
          schemas,
          pointer,
          present,
          nextVisited,
        );
      }
      continue;
    }
    const valueType = schemaValueType(field.value);
    if (valueType === null) continue;
    sources.push(Object.freeze({
      id: contextId(domain, pointer),
      label: field.displayName ?? field.fieldKey,
      source: Object.freeze({ kind: 'context', domain, pointer }),
      valueType,
      presence: present ? 'CONCRETE' : 'MAY_BE_ABSENT',
    }));
  }
}

interface ReadyContext {
  readonly state: 'READY';
  readonly schema: TemplateStructuralSchemaRef;
}

interface InvalidContext {
  readonly state: 'INVALID';
  readonly problem: string;
}

function projectTemplateUseNodeState(
  input: ProjectTemplateUseInput,
): { readonly targets: readonly TemplateTargetOption[]; readonly state: TemplateUseNodeState } {
  const context = resolveTemplateUseContext(input);
  const contextOptions = projectTemplateUseContextOptions(input);
  const targets = projectTemplateTargets(input.templateCatalog, context);
  if (context.state === 'INVALID') {
    return Object.freeze({
      targets,
      state: Object.freeze({
        kind: 'templateUse',
        authoringState: 'INVALID',
        contextOptions,
        context,
        fillTargets: Object.freeze([]),
        fills: Object.freeze([]),
        problems: Object.freeze([context.problem]),
      }),
    });
  }

  const templateRef = record(input.node.templateRef);
  const templateId = text(templateRef?.templateId);
  const selected = targets.find((target) => target.templateId === templateId);
  const catalogEntry = input.templateCatalog.find((candidate) => candidate.templateId === templateId);
  const currentCandidate = input.templateCurrents.find((candidate) => candidate.templateId === templateId);
  const currentMatchesCatalog = Boolean(currentCandidate && catalogEntry
    && exactTemplateCurrent(currentCandidate, catalogEntry));
  const current = currentMatchesCatalog && currentCandidate?.readiness === 'READY'
    ? currentCandidate
    : undefined;
  const currentDesign = record(current?.designDsl);
  const sourceCanvasSizeMm = currentDesign ? templateCanvasSize(currentDesign) : null;
  const definitions = array(currentDesign?.definitions).map(record).filter(notNull);
  const fillSources = projectTemplateUseFillSources(input);
  const fillTargets = Object.freeze(definitions
    .filter((definition) => definition.kind === 'custom' && definition.exposure === 'PUBLIC')
    .map((definition) => Object.freeze({
      definitionId: text(definition.definitionId) ?? '',
      displayName: text(definition.displayName) ?? '',
      valueType: definition.valueType,
      sources: Object.freeze(fillSources.filter((source) => (
        sameValueType(source.valueType, definition.valueType)
      ))),
    }))
    .filter(({ definitionId }) => definitionId.length > 0));
  const fillResult = projectTemplateUseFills(input, definitions);
  const problems: string[] = [];
  if (!selected) problems.push('TEMPLATE_TARGET_MISSING');
  else if (selected.state === 'incompatible') problems.push('TEMPLATE_SCHEMA_MISMATCH');
  else if (selected.state === 'unavailable') problems.push('TEMPLATE_NOT_READY');
  if (!currentCandidate) problems.push('TEMPLATE_CURRENT_UNAVAILABLE');
  else if (!currentMatchesCatalog) problems.push('TEMPLATE_CURRENT_DRIFT');
  problems.push(...fillResult.problems);
  const hard = fillResult.hard;
  return Object.freeze({
    targets,
    state: Object.freeze({
      kind: 'templateUse',
      authoringState: hard ? 'INVALID' : problems.length > 0 ? 'NEEDS_REPAIR' : 'READY',
      contextOptions,
      context,
      fillTargets,
      fills: fillResult.fills,
      ...(sourceCanvasSizeMm ? { sourceCanvasSizeMm } : {}),
      problems: Object.freeze(problems),
    }),
  });
}

function resolveTemplateUseContext(input: ProjectTemplateUseInput): ReadyContext | InvalidContext {
  const selector = record(input.node.contextSelector);
  if (!selector) return invalidContext('CONTEXT_SELECTOR_INVALID');
  if (selector.kind === 'value') return invalidContext('PRIMITIVE_VALUE_ADAPTER_FORBIDDEN');
  if (selector.kind === 'empty') {
    return readyContext(Object.freeze({ schemaKey: 'system-empty', versionTag: 'v1' }));
  }
  if (selector.kind !== 'context') return invalidContext('CONTEXT_SELECTOR_INVALID');
  if (!Object.hasOwn(selector, 'pointer') || typeof selector.pointer !== 'string') {
    return invalidContext('CONTEXT_POINTER_REQUIRED');
  }
  const base = templateSelectorBaseContext(
    selector.domain,
    input.rootSchema,
    input.loops,
  );
  if (!base) return invalidContext('DYNAMIC_CONTEXT_DOMAIN');
  if (selector.pointer === '') return readyContext(base);
  const resolved = resolveContextPath(base, selector.pointer, input.schemas);
  if (resolved.kind === 'reference') return readyContext(resolved.schema);
  if (resolved.kind === 'array') return invalidContext('ARRAY_CONTEXT_SELECTOR');
  if (resolved.kind === 'value') return invalidContext('PRIMITIVE_CONTEXT_SELECTOR');
  return invalidContext(resolved.problem);
}

function templateSelectorBaseContext(
  domain: unknown,
  rootSchema: StaticSnapshot,
  loops: readonly LexicalLoop[],
): TemplateStructuralSchemaRef | null {
  const value = record(domain);
  if (value?.kind === 'invocation') {
    return Object.freeze({ schemaKey: rootSchema.schemaKey, versionTag: rootSchema.versionTag });
  }
  if (value?.kind !== 'loop' || typeof value.loopId !== 'string') return null;
  return loops.find(({ projection }) => projection.loopId === value.loopId)?.projection.itemContext ?? null;
}

function projectTemplateTargets(
  catalog: readonly Readonly<Record<string, unknown>>[],
  context: ReadyContext | InvalidContext,
): readonly TemplateTargetOption[] {
  return Object.freeze(catalog.flatMap((entry): TemplateTargetOption[] => {
    const templateId = text(entry.templateId);
    const schema = schemaRef(entry.staticSchema);
    const readiness = entry.readiness;
    if (!templateId || !schema || (readiness !== 'READY' && readiness !== 'INVALID' && readiness !== 'STALE')) {
      return [];
    }
    const state = context.state === 'READY' && sameSchema(schema, context.schema)
      ? readiness === 'READY' ? 'eligible' : 'unavailable'
      : 'incompatible';
    return [Object.freeze({
      templateId,
      displayName: text(entry.displayName) ?? templateId,
      staticSchema: schema,
      readiness,
      state,
    })];
  }));
}

function projectTemplateUseFills(
  input: ProjectTemplateUseInput,
  childDefinitions: readonly Readonly<Record<string, unknown>>[],
): {
  readonly fills: readonly TemplateUseFillProjection[];
  readonly problems: readonly string[];
  readonly hard: boolean;
} {
  const seen = new Set<string>();
  const fills: TemplateUseFillProjection[] = [];
  const problems: string[] = [];
  let hard = false;
  for (const value of array(input.node.fills)) {
    const fill = record(value);
    const targetDefinitionId = text(fill?.targetDefinitionId) ?? '';
    if (!fill || !targetDefinitionId) {
      hard = true;
      problems.push('FILL_WIRE_INVALID');
      continue;
    }
    if (seen.has(targetDefinitionId)) {
      hard = true;
      problems.push('DUPLICATE_FILL_TARGET');
      fills.push(Object.freeze({
        targetDefinitionId,
        state: 'INVALID',
        problem: 'DUPLICATE_FILL_TARGET',
      }));
      continue;
    }
    seen.add(targetDefinitionId);
    const target = childDefinitions.find((definition) => definition.definitionId === targetDefinitionId);
    if (!target || target.kind !== 'custom' || target.exposure !== 'PUBLIC') {
      problems.push('FILL_TARGET_UNAVAILABLE');
      fills.push(Object.freeze({
        targetDefinitionId,
        state: 'NEEDS_REPAIR',
        problem: 'FILL_TARGET_UNAVAILABLE',
      }));
      continue;
    }
    const source = record(fill.source);
    if (!source || source.kind === 'literal' || source.kind === 'capability') {
      hard = true;
      const problem = source?.kind === 'literal'
        ? 'LITERAL_FILL_SOURCE_FORBIDDEN'
        : source?.kind === 'capability'
          ? 'CAPABILITY_FILL_SOURCE_FORBIDDEN'
          : 'FILL_SOURCE_INVALID';
      problems.push(problem);
      fills.push(Object.freeze({ targetDefinitionId, state: 'INVALID', problem }));
      continue;
    }
    const sourceType = resolveBindingSourceType(source, input);
    if (sourceType.state === 'invalid') {
      hard = true;
      problems.push(sourceType.problem);
      fills.push(Object.freeze({
        targetDefinitionId,
        state: 'INVALID',
        problem: sourceType.problem,
      }));
      continue;
    }
    if (!sameValueType(sourceType.valueType, target.valueType)) {
      problems.push('FILL_TYPE_MISMATCH');
      fills.push(Object.freeze({
        targetDefinitionId,
        state: 'NEEDS_REPAIR',
        problem: 'FILL_TYPE_MISMATCH',
      }));
      continue;
    }
    fills.push(Object.freeze({ targetDefinitionId, state: 'READY' }));
  }
  return Object.freeze({
    fills: Object.freeze(fills),
    problems: Object.freeze(problems),
    hard,
  });
}

function projectConditionalNodeState(
  node: Readonly<Record<string, unknown>>,
  sources: readonly TemplateBooleanSourceOption[],
  sample: TemplateStructuralSample | undefined,
): TemplateConditionalNodeState {
  const authoredChildren = array(node.children);
  const source = sources.find((candidate) => sameSource(candidate.source, node.condition));
  if (!source) {
    return Object.freeze({
      kind: 'conditional',
      authoringState: 'INVALID',
      authoredChildren,
      runtime: Object.freeze({ state: 'SOURCE_ERROR', code: 'SOURCE_INELIGIBLE' }),
      problems: Object.freeze(['SOURCE_INELIGIBLE']),
    });
  }
  return Object.freeze({
    kind: 'conditional',
    authoringState: 'READY',
    authoredChildren,
    runtime: projectConditionalRuntime(node, sample),
    problems: Object.freeze([]),
  });
}

function projectConditionalRuntime(
  node: Readonly<Record<string, unknown>>,
  sample: TemplateStructuralSample | undefined,
): TemplateConditionalRuntimeProjection {
  if (!sample) return Object.freeze({ state: 'UNSAMPLED' });
  if (sample.state === 'error') {
    return Object.freeze({ state: 'SOURCE_ERROR', code: sample.code });
  }
  if (sample.state === 'absent') {
    return node.absentPolicy === 'FALSE'
      ? Object.freeze({ state: 'FALSE' })
      : Object.freeze({ state: 'ABSENT_ERROR' });
  }
  if (typeof sample.value !== 'boolean') {
    return Object.freeze({ state: 'SOURCE_ERROR', code: 'SAMPLE_NOT_BOOLEAN' });
  }
  return Object.freeze({ state: sample.value ? 'TRUE' : 'FALSE' });
}

function projectRepeatNodeState(
  node: Readonly<Record<string, unknown>>,
  sources: readonly TemplateRepeatSourceOption[],
  sample: TemplateStructuralSample | undefined,
  priorItemContext: TemplateStructuralSchemaRef | undefined,
): TemplateRepeatNodeState {
  const authoredChildren = array(node.children);
  const source = sources.find((candidate) => sameSource(candidate.source, node.items));
  if (!source) {
    return Object.freeze({
      kind: 'repeat',
      authoringState: 'INVALID',
      itemContext: null,
      authoredChildren,
      runtime: emptyRepeatRuntime('SOURCE_ERROR', 'SOURCE_INELIGIBLE'),
      problems: Object.freeze(['SOURCE_INELIGIBLE']),
    });
  }
  const changedContext = priorItemContext !== undefined
    && !sameSchema(priorItemContext, source.itemContext);
  return Object.freeze({
    kind: 'repeat',
    authoringState: changedContext && authoredChildren.length > 0 ? 'NEEDS_REPAIR' : 'READY',
    itemContext: source.itemContext,
    authoredChildren,
    runtime: projectRepeatRuntime(node, source, sample),
    problems: Object.freeze(changedContext && authoredChildren.length > 0
      ? ['ITEM_CONTEXT_CHANGED']
      : []),
  });
}

function projectRepeatRuntime(
  node: Readonly<Record<string, unknown>>,
  source: TemplateRepeatSourceOption,
  sample: TemplateStructuralSample | undefined,
): TemplateRepeatRuntimeProjection {
  if (!sample) return emptyRepeatRuntime('UNSAMPLED');
  if (sample.state === 'error') return emptyRepeatRuntime('SOURCE_ERROR', sample.code);
  if (sample.state === 'absent') {
    return node.absentPolicy === 'EMPTY'
      ? emptyRepeatRuntime('EMPTY')
      : emptyRepeatRuntime('ABSENT_ERROR');
  }
  if (!Array.isArray(sample.value)) return emptyRepeatRuntime('SOURCE_ERROR', 'SAMPLE_NOT_COLLECTION');
  if (sample.value.length === 0) return emptyRepeatRuntime('EMPTY');
  return Object.freeze({
    state: 'VALUES',
    occurrences: Object.freeze(sample.value.map((value, inputIndex) => Object.freeze({
      inputIndex,
      itemContext: source.itemContext,
      value: source.itemKind === 'scalar' ? Object.freeze({ index: inputIndex, value }) : value,
    }))),
  });
}

function emptyRepeatRuntime(
  state: 'UNSAMPLED' | 'EMPTY' | 'ABSENT_ERROR',
): TemplateRepeatRuntimeProjection;
function emptyRepeatRuntime(
  state: 'SOURCE_ERROR',
  code: string,
): TemplateRepeatRuntimeProjection;
function emptyRepeatRuntime(
  state: 'UNSAMPLED' | 'EMPTY' | 'ABSENT_ERROR' | 'SOURCE_ERROR',
  code?: string,
): TemplateRepeatRuntimeProjection {
  if (state === 'SOURCE_ERROR') {
    return Object.freeze({
      state: 'SOURCE_ERROR',
      code: code ?? 'SOURCE_ERROR',
      occurrences: [] as const,
    });
  }
  if (state === 'EMPTY') return Object.freeze({ state: 'EMPTY', occurrences: [] as const });
  if (state === 'ABSENT_ERROR') {
    return Object.freeze({ state: 'ABSENT_ERROR', occurrences: [] as const });
  }
  return Object.freeze({ state: 'UNSAMPLED', occurrences: [] as const });
}

function walkNodes(
  nodes: readonly unknown[],
  loops: readonly LexicalLoop[],
  visit: (node: Readonly<Record<string, unknown>>, loops: readonly LexicalLoop[]) => void,
  enterRepeat: (
    node: Readonly<Record<string, unknown>>,
    loops: readonly LexicalLoop[],
  ) => LexicalLoop | null,
): void {
  for (const value of nodes) {
    const node = record(value);
    if (!node) continue;
    visit(node, loops);
    const ownLoop = enterRepeat(node, loops);
    walkNodes(array(node.children), ownLoop ? [...loops, ownLoop] : loops, visit, enterRepeat);
  }
}

function mergeSources(
  root: SourceCatalog,
  definitions: SourceCatalog,
  loops: readonly LexicalLoop[],
): SourceCatalog {
  return {
    repeat: [
      ...root.repeat,
      ...definitions.repeat.filter((source) => definitionVisible(source, loops)),
      ...loops.flatMap((loop) => loop.sources.repeat),
    ],
    boolean: [
      ...root.boolean,
      ...definitions.boolean.filter((source) => definitionVisible(source, loops)),
      ...loops.flatMap((loop) => loop.sources.boolean),
    ],
  };
}

function definitionVisible(
  source: TemplateRepeatSourceOption | TemplateBooleanSourceOption,
  loops: readonly LexicalLoop[],
): boolean {
  return source.declarationLoopId === undefined
    || loops.some(({ projection }) => projection.loopId === source.declarationLoopId);
}

function definitionSources(designDsl: Readonly<Record<string, unknown>>): SourceCatalog {
  const repeat: TemplateRepeatSourceOption[] = [];
  const boolean: TemplateBooleanSourceOption[] = [];
  for (const value of array(designDsl.definitions)) {
    const definition = record(value);
    const definitionId = text(definition?.definitionId);
    if (!definition || !definitionId) continue;
    const output = definition.kind === 'custom' ? definition.valueType : definition.output;
    const list = record(output);
    const declarationLoopId = record(definition.domain)?.kind === 'loop'
      ? text(record(definition.domain)?.loopId) ?? undefined
      : undefined;
    const source = Object.freeze({
      kind: 'definition',
      definitionId,
    });
    if (list?.type === 'list' && typeof list.items === 'string' && SCALAR_ITEM_TYPES.has(list.items)) {
      repeat.push(Object.freeze({
        id: `definition:${definitionId}`,
        label: text(definition.displayName) ?? definitionId,
        source,
        origin: 'definition',
        presence: 'CONCRETE',
        itemContext: systemBasic(list.items),
        itemKind: 'scalar',
        ...(declarationLoopId ? { declarationLoopId } : {}),
      }));
    } else if (output === 'boolean') {
      boolean.push(Object.freeze({
        id: `definition:${definitionId}`,
        label: text(definition.displayName) ?? definitionId,
        source,
        origin: 'definition',
        presence: 'CONCRETE',
        ...(declarationLoopId ? { declarationLoopId } : {}),
      }));
    }
  }
  return { repeat: Object.freeze(repeat), boolean: Object.freeze(boolean) };
}

function contextSources(
  schema: StaticSnapshot,
  domain: 'invocation' | Readonly<{ kind: 'loop'; loopId: string }>,
  origin: 'static-schema' | 'loop',
  schemas: ReadonlyMap<string, StaticSnapshot>,
): SourceCatalog {
  const repeat: TemplateRepeatSourceOption[] = [];
  const boolean: TemplateBooleanSourceOption[] = [];
  collectContextSources(schema, domain, origin, schemas, '', true, new Set(), repeat, boolean);
  return { repeat: Object.freeze(repeat), boolean: Object.freeze(boolean) };
}

function collectContextSources(
  schema: StaticSnapshot,
  domain: 'invocation' | Readonly<{ kind: 'loop'; loopId: string }>,
  origin: 'static-schema' | 'loop',
  schemas: ReadonlyMap<string, StaticSnapshot>,
  prefix: string,
  concrete: boolean,
  visited: ReadonlySet<string>,
  repeat: TemplateRepeatSourceOption[],
  boolean: TemplateBooleanSourceOption[],
): void {
  const identity = schemaIdentity(schema.schemaKey, schema.versionTag);
  if (visited.has(identity)) return;
  const nextVisited = new Set(visited).add(identity);
  for (const field of schema.definition.fields) {
    const pointer = `${prefix}/${escapePointer(field.fieldKey)}`;
    const present = concrete && field.required;
    const source = Object.freeze({ kind: 'context', domain, pointer });
    if (field.value.type === 'array') {
      if (SCALAR_ITEM_TYPES.has(field.value.items.type)) {
        repeat.push(Object.freeze({
          id: contextId(domain, pointer),
          label: field.displayName ?? field.fieldKey,
          source,
          origin,
          presence: present ? 'CONCRETE' : 'MAY_BE_ABSENT',
          itemContext: systemBasic(field.value.items.type),
          itemKind: 'scalar',
        }));
      } else if (field.value.items.type === 'reference' && field.value.items.ref.versionTag) {
        repeat.push(Object.freeze({
          id: contextId(domain, pointer),
          label: field.displayName ?? field.fieldKey,
          source,
          origin,
          presence: present ? 'CONCRETE' : 'MAY_BE_ABSENT',
          itemContext: Object.freeze({
            schemaKey: field.value.items.ref.schemaKey,
            versionTag: field.value.items.ref.versionTag,
          }),
          itemKind: 'reference',
        }));
      }
    } else if (field.value.type === 'boolean') {
      boolean.push(Object.freeze({
        id: contextId(domain, pointer),
        label: field.displayName ?? field.fieldKey,
        source,
        origin,
        presence: present ? 'CONCRETE' : 'MAY_BE_ABSENT',
      }));
    } else if (field.value.type === 'reference' && field.value.ref.versionTag) {
      const referenced = schemas.get(schemaIdentity(field.value.ref.schemaKey, field.value.ref.versionTag));
      if (referenced) {
        collectContextSources(
          referenced,
          domain,
          origin,
          schemas,
          pointer,
          present,
          nextVisited,
          repeat,
          boolean,
        );
      }
    }
  }
}

function loopContextSources(
  loop: TemplateLoopContextProjection,
  schemas: ReadonlyMap<string, StaticSnapshot>,
): SourceCatalog {
  const domain = Object.freeze({ kind: 'loop' as const, loopId: loop.loopId });
  if (loop.itemKind === 'scalar') {
    const boolean = loop.itemContext.schemaKey === 'system-basic-boolean'
      ? [Object.freeze({
        id: contextId(domain, '/value'),
        label: '循环值',
        source: Object.freeze({ kind: 'context', domain, pointer: '/value' }),
        origin: 'loop' as const,
        presence: 'CONCRETE' as const,
      })]
      : [];
    return { repeat: Object.freeze([]), boolean: Object.freeze(boolean) };
  }
  const schema = schemas.get(schemaIdentity(loop.itemContext.schemaKey, loop.itemContext.versionTag));
  return schema ? contextSources(schema, domain, 'loop', schemas) : emptySources();
}

type ContextPathResult =
  | { readonly kind: 'reference'; readonly schema: TemplateStructuralSchemaRef }
  | { readonly kind: 'array'; readonly valueType: unknown | null }
  | { readonly kind: 'value'; readonly valueType: unknown }
  | { readonly kind: 'invalid'; readonly problem: string };

function resolveContextPath(
  base: TemplateStructuralSchemaRef,
  pointer: string,
  schemas: ReadonlyMap<string, StaticSnapshot>,
): ContextPathResult {
  if (!pointer.startsWith('/')) return { kind: 'invalid', problem: 'CONTEXT_POINTER_INVALID' };
  let schema = schemas.get(schemaIdentity(base.schemaKey, base.versionTag));
  if (!schema) {
    const basic = systemBasicValueType(base);
    if (basic && pointer === '/value') return { kind: 'value', valueType: basic };
    if (basic && pointer === '/index') return { kind: 'value', valueType: 'decimal' };
    return { kind: 'invalid', problem: 'CONTEXT_SCHEMA_UNAVAILABLE' };
  }
  const segments = pointer.slice(1).split('/').map(unescapePointer);
  for (let index = 0; index < segments.length; index += 1) {
    const field = schema.definition.fields.find((candidate) => candidate.fieldKey === segments[index]);
    if (!field) return { kind: 'invalid', problem: 'CONTEXT_PATH_UNAVAILABLE' };
    const final = index === segments.length - 1;
    if (field.value.type === 'reference' && field.value.ref.versionTag) {
      const reference = Object.freeze({
        schemaKey: field.value.ref.schemaKey,
        versionTag: field.value.ref.versionTag,
      });
      if (final) return { kind: 'reference', schema: reference };
      schema = schemas.get(schemaIdentity(reference.schemaKey, reference.versionTag));
      if (!schema) return { kind: 'invalid', problem: 'CONTEXT_SCHEMA_UNAVAILABLE' };
      continue;
    }
    if (!final) return { kind: 'invalid', problem: 'CONTEXT_POINTER_INVALID' };
    if (field.value.type === 'array') {
      return { kind: 'array', valueType: schemaValueType(field.value) };
    }
    return { kind: 'value', valueType: field.value.type };
  }
  return { kind: 'invalid', problem: 'CONTEXT_POINTER_INVALID' };
}

function resolveBindingSourceType(
  source: Readonly<Record<string, unknown>>,
  input: ProjectTemplateUseInput,
): { readonly state: 'ready'; readonly valueType: unknown }
  | { readonly state: 'invalid'; readonly problem: string } {
  if (source.kind === 'loopIndex') {
    const loopId = text(source.loopId);
    return loopId && input.loops.some(({ projection }) => projection.loopId === loopId)
      ? { state: 'ready', valueType: 'decimal' }
      : { state: 'invalid', problem: 'FILL_SOURCE_OUT_OF_SCOPE' };
  }
  if (source.kind === 'definition') {
    const definitionId = text(source.definitionId);
    const definition = array(input.designDsl.definitions)
      .map(record)
      .filter(notNull)
      .find((candidate) => candidate.definitionId === definitionId);
    if (!definition) return { state: 'invalid', problem: 'FILL_SOURCE_UNAVAILABLE' };
    const declarationLoopId = text(record(definition.domain)?.loopId);
    if (declarationLoopId
      && !input.loops.some(({ projection }) => projection.loopId === declarationLoopId)) {
      return { state: 'invalid', problem: 'FILL_SOURCE_OUT_OF_SCOPE' };
    }
    return {
      state: 'ready',
      valueType: definition.kind === 'custom' ? definition.valueType : definition.output,
    };
  }
  if (source.kind !== 'context' || typeof source.pointer !== 'string' || source.pointer === '') {
    return { state: 'invalid', problem: 'FILL_SOURCE_INVALID' };
  }
  const base = valueSourceBaseContext(source.domain, input.rootSchema, input.loops);
  if (!base) return { state: 'invalid', problem: 'FILL_SOURCE_OUT_OF_SCOPE' };
  const resolved = resolveContextPath(base, source.pointer, input.schemas);
  if (resolved.kind === 'value') return { state: 'ready', valueType: resolved.valueType };
  if (resolved.kind === 'array' && resolved.valueType !== null) {
    return { state: 'ready', valueType: resolved.valueType };
  }
  return { state: 'invalid', problem: 'FILL_SOURCE_INVALID' };
}

function valueSourceBaseContext(
  domain: unknown,
  rootSchema: StaticSnapshot,
  loops: readonly LexicalLoop[],
): TemplateStructuralSchemaRef | null {
  if (domain === 'invocation') {
    return Object.freeze({ schemaKey: rootSchema.schemaKey, versionTag: rootSchema.versionTag });
  }
  const value = record(domain);
  if (value?.kind !== 'loop' || typeof value.loopId !== 'string') return null;
  return loops.find(({ projection }) => projection.loopId === value.loopId)?.projection.itemContext ?? null;
}

function systemBasicValueType(schema: TemplateStructuralSchemaRef): string | null {
  const prefix = 'system-basic-';
  return schema.versionTag === 'v1' && schema.schemaKey.startsWith(prefix)
    ? schema.schemaKey.slice(prefix.length)
    : null;
}

function schemaValueType(
  value: StaticSnapshot['definition']['fields'][number]['value'],
): unknown | null {
  if (value.type === 'reference') return null;
  if (value.type !== 'array') return value.type;
  return value.items.type === 'reference'
    ? null
    : Object.freeze({ type: 'list', items: value.items.type });
}

function sameValueType(left: unknown, right: unknown): boolean {
  if (typeof left === 'string' || typeof right === 'string') return left === right;
  const leftRecord = record(left);
  const rightRecord = record(right);
  if (!leftRecord || !rightRecord || leftRecord.type !== rightRecord.type) return false;
  if (leftRecord.type === 'list') return leftRecord.items === rightRecord.items;
  if (leftRecord.type === 'enum') return leftRecord.catalogId === rightRecord.catalogId;
  return false;
}

function readyContext(schema: TemplateStructuralSchemaRef): ReadyContext {
  return Object.freeze({ state: 'READY', schema });
}

function invalidContext(problem: string): InvalidContext {
  return Object.freeze({ state: 'INVALID', problem });
}

function schemaRef(value: unknown): TemplateStructuralSchemaRef | null {
  const candidate = record(value);
  return typeof candidate?.schemaKey === 'string' && typeof candidate.versionTag === 'string'
    ? Object.freeze({ schemaKey: candidate.schemaKey, versionTag: candidate.versionTag })
    : null;
}

function schemaCatalog(
  root: StaticSnapshot,
  schemas: readonly StaticSnapshot[],
): ReadonlyMap<string, StaticSnapshot> {
  return new Map([root, ...schemas].map((schema) => [
    schemaIdentity(schema.schemaKey, schema.versionTag),
    schema,
  ]));
}

function emptySources(): SourceCatalog {
  return { repeat: Object.freeze([]), boolean: Object.freeze([]) };
}

function systemBasic(itemType: string): TemplateStructuralSchemaRef {
  return Object.freeze({ schemaKey: `system-basic-${itemType}`, versionTag: 'v1' });
}

function contextId(
  domain: 'invocation' | Readonly<{ kind: 'loop'; loopId: string }>,
  pointer: string,
): string {
  return domain === 'invocation'
    ? `context:invocation:${pointer}`
    : `context:loop:${domain.loopId}:${pointer}`;
}

function templateContextId(
  domain: 'invocation' | Readonly<{ kind: 'loop'; loopId: string }>,
  pointer: string,
): string {
  return `template-${contextId(domain, pointer)}`;
}

function templateContextSelector(
  domain: 'invocation' | Readonly<{ kind: 'loop'; loopId: string }>,
  pointer: string,
  contextAbsentPolicy: 'ERROR' | 'SKIP' = 'ERROR',
): Readonly<Record<string, unknown>> {
  return Object.freeze({
    kind: 'context',
    domain: domain === 'invocation'
      ? Object.freeze({ kind: 'invocation' })
      : Object.freeze({ kind: 'loop', loopId: domain.loopId }),
    pointer,
    contextAbsentPolicy,
  });
}

function schemaIdentity(schemaKey: string, versionTag: string): string {
  return `${schemaKey}@${versionTag}`;
}

function sameSchema(left: TemplateStructuralSchemaRef, right: TemplateStructuralSchemaRef): boolean {
  return left.schemaKey === right.schemaKey && left.versionTag === right.versionTag;
}

function exactTemplateCurrent(
  current: Readonly<Record<string, unknown>>,
  catalogEntry: Readonly<Record<string, unknown>>,
): boolean {
  const currentSchema = schemaRef(current.staticSchema);
  const catalogSchema = schemaRef(catalogEntry.staticSchema);
  return current.disclosure === 'READABLE'
    && current.templateId === catalogEntry.templateId
    && current.revision === catalogEntry.revision
    && current.readiness === catalogEntry.readiness
    && currentSchema !== null
    && catalogSchema !== null
    && sameSchema(currentSchema, catalogSchema);
}

function templateCanvasSize(
  designDsl: Readonly<Record<string, unknown>>,
): Readonly<{ readonly widthMm: number; readonly heightMm: number }> | null {
  const root = record(designDsl.designRoot);
  return root?.kind === 'canvas'
    && typeof root.widthMm === 'number' && Number.isFinite(root.widthMm) && root.widthMm > 0
    && typeof root.heightMm === 'number' && Number.isFinite(root.heightMm) && root.heightMm > 0
    ? Object.freeze({ widthMm: root.widthMm, heightMm: root.heightMm })
    : null;
}

function escapePointer(segment: string): string {
  return segment.replaceAll('~', '~0').replaceAll('/', '~1');
}

function unescapePointer(segment: string): string {
  return segment.replaceAll('~1', '/').replaceAll('~0', '~');
}

function sameSource(left: Readonly<Record<string, unknown>>, right: unknown): boolean {
  const candidate = record(right);
  if (!candidate || left.kind !== candidate.kind) return false;
  if (left.kind === 'definition') return left.definitionId === candidate.definitionId;
  if (left.kind !== 'context') return false;
  return left.pointer === candidate.pointer && sameDomain(left.domain, candidate.domain);
}

function sameDomain(left: unknown, right: unknown): boolean {
  if (left === 'invocation' || right === 'invocation') return left === right;
  const leftRecord = record(left);
  const rightRecord = record(right);
  return leftRecord?.kind === 'loop' && rightRecord?.kind === 'loop'
    && leftRecord.loopId === rightRecord.loopId;
}

function record(value: unknown): Readonly<Record<string, unknown>> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Readonly<Record<string, unknown>>
    : null;
}

function array(value: unknown): readonly unknown[] {
  return Array.isArray(value) ? value : [];
}

function text(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function notNull<T>(value: T | null): value is T {
  return value !== null;
}
