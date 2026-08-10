import type {
  CandidateBundle,
  CandidateField,
  CandidateResolution,
  CandidateReviewResponse,
  CandidateSchema,
  CandidateValue,
} from '../../api/generated';

export type CandidateView = 'form' | 'map';

export interface CandidateReviewState {
  snapshot: CandidateReviewResponse;
  draft: CandidateBundle;
  selectedSchemaId: string;
  selectedFieldId: string | null;
  view: CandidateView;
  search: string;
  generation: number;
  dirty: boolean;
  saving: boolean;
  saveBlocked: boolean;
  saveMessage: string | null;
}

export type CandidateReviewAction =
  | { type: 'hydrate'; snapshot: CandidateReviewResponse }
  | { type: 'select-schema'; schemaId: string }
  | { type: 'select-field'; schemaId: string; fieldId: string }
  | { type: 'set-view'; view: CandidateView }
  | { type: 'set-search'; search: string }
  | { type: 'edit-schema'; schemaId: string; patch: Partial<Pick<CandidateSchema, 'proposedSchemaKey' | 'displayName'>> }
  | { type: 'edit-field'; schemaId: string; fieldId: string; patch: Partial<Pick<CandidateField, 'proposedFieldKey' | 'displayName' | 'required' | 'value'>> }
  | { type: 'add-schema'; schema: CandidateSchema }
  | { type: 'add-field'; schemaId: string; field: CandidateField }
  | { type: 'move-schema'; schemaId: string; direction: -1 | 1 }
  | { type: 'move-field'; schemaId: string; fieldId: string; direction: -1 | 1 }
  | { type: 'resolve-schema'; schemaId: string; resolution: CandidateResolution }
  | { type: 'resolve-field'; schemaId: string; fieldId: string; resolution: CandidateResolution }
  | { type: 'save-start' }
  | { type: 'save-success'; snapshot: CandidateReviewResponse; generation: number }
  | { type: 'save-error'; message: string }
  | { type: 'retry-save' };

export function createCandidateReviewState(snapshot: CandidateReviewResponse): CandidateReviewState {
  return {
    snapshot,
    draft: snapshot.current,
    selectedSchemaId: snapshot.current.rootCandidateSchemaId,
    selectedFieldId: null,
    view: 'form',
    search: '',
    generation: 0,
    dirty: false,
    saving: false,
    saveBlocked: false,
    saveMessage: null,
  };
}

export function candidateReviewReducer(
  state: CandidateReviewState,
  action: CandidateReviewAction,
): CandidateReviewState {
  switch (action.type) {
    case 'hydrate': {
      const next = createCandidateReviewState(action.snapshot);
      const schema = action.snapshot.current.schemas.find((item) => item.candidateSchemaId === state.selectedSchemaId);
      if (!schema) return { ...next, view: state.view, search: state.search };
      const fieldStillExists = schema.fields.some((item) => item.candidateFieldId === state.selectedFieldId);
      return {
        ...next,
        selectedSchemaId: schema.candidateSchemaId,
        selectedFieldId: fieldStillExists ? state.selectedFieldId : null,
        view: state.view,
        search: state.search,
      };
    }
    case 'select-schema':
      return { ...state, selectedSchemaId: action.schemaId, selectedFieldId: null };
    case 'select-field':
      return { ...state, selectedSchemaId: action.schemaId, selectedFieldId: action.fieldId };
    case 'set-view':
      return { ...state, view: action.view };
    case 'set-search':
      return { ...state, search: action.search };
    case 'edit-schema':
      return change(state, updateSchema(state.draft, action.schemaId, (schema) => ({
        ...schema,
        ...action.patch,
        assessment: editedAssessment(schema),
      })));
    case 'edit-field':
      return change(state, updateField(state.draft, action.schemaId, action.fieldId, (field) => ({
        ...field,
        ...action.patch,
        assessment: editedAssessment(field),
      })));
    case 'add-schema':
      return {
        ...change(state, { ...state.draft, schemas: [...state.draft.schemas, action.schema] }, null),
        selectedSchemaId: action.schema.candidateSchemaId,
      };
    case 'add-field':
      return change(state, updateSchema(state.draft, action.schemaId, (schema) => ({
        ...schema,
        fields: [...schema.fields, action.field],
      })), action.field.candidateFieldId);
    case 'move-schema':
      return change(state, {
        ...state.draft,
        schemas: moveById(state.draft.schemas, 'candidateSchemaId', action.schemaId, action.direction),
      });
    case 'move-field':
      return change(state, updateSchema(state.draft, action.schemaId, (schema) => ({
        ...schema,
        fields: moveById(schema.fields, 'candidateFieldId', action.fieldId, action.direction),
      })), action.fieldId);
    case 'resolve-schema':
      if (action.schemaId === state.draft.rootCandidateSchemaId && action.resolution === 'REMOVED') return state;
      {
        const schema = state.draft.schemas.find((item) => item.candidateSchemaId === action.schemaId);
        if (!schema) return state;
        const resolution = safeSchemaResolution(state.snapshot.original, schema, action.resolution);
        if (resolution === schema.assessment.resolution) return state;
        return change(state, updateSchema(state.draft, action.schemaId, (item) => ({
          ...item,
          assessment: { ...item.assessment, resolution },
        })));
      }
    case 'resolve-field':
      {
        const field = state.draft.schemas.find((item) => item.candidateSchemaId === action.schemaId)
          ?.fields.find((item) => item.candidateFieldId === action.fieldId);
        if (!field) return state;
        const resolution = safeFieldResolution(state.snapshot.original, field, action.resolution);
        if (resolution === field.assessment.resolution) return state;
        return change(state, updateField(state.draft, action.schemaId, action.fieldId, (item) => ({
          ...item,
          assessment: { ...item.assessment, resolution },
        })));
      }
    case 'save-start':
      return { ...state, saving: true, saveMessage: null };
    case 'save-success': {
      const editedWhileSaving = state.generation !== action.generation;
      return {
        ...state,
        snapshot: action.snapshot,
        draft: editedWhileSaving ? state.draft : action.snapshot.current,
        dirty: editedWhileSaving,
        saving: false,
        saveBlocked: false,
        saveMessage: null,
      };
    }
    case 'save-error':
      return { ...state, dirty: true, saving: false, saveBlocked: true, saveMessage: action.message };
    case 'retry-save':
      return { ...state, saveBlocked: false, saveMessage: null };
  }
}

function change(state: CandidateReviewState, draft: CandidateBundle, selectedFieldId = state.selectedFieldId) {
  return {
    ...state,
    draft,
    selectedFieldId,
    generation: state.generation + 1,
    dirty: true,
    saveBlocked: false,
    saveMessage: null,
  };
}

function editedAssessment(item: CandidateSchema | CandidateField) {
  if (item.source === 'USER') return item.assessment;
  return { ...item.assessment, resolution: 'RESOLVED_BY_EDIT' as const };
}

function safeSchemaResolution(
  original: CandidateBundle,
  schema: CandidateSchema,
  requested: CandidateResolution,
) {
  if (schema.source !== 'AI' || requested === 'REMOVED') return requested;
  const source = original.schemas.find((item) => item.candidateSchemaId === schema.candidateSchemaId);
  if (!source || source.proposedSchemaKey !== schema.proposedSchemaKey || source.displayName !== schema.displayName) {
    return 'RESOLVED_BY_EDIT' as const;
  }
  return requested;
}

function safeFieldResolution(
  original: CandidateBundle,
  field: CandidateField,
  requested: CandidateResolution,
) {
  if (field.source !== 'AI' || requested === 'REMOVED') return requested;
  const source = original.schemas.flatMap((schema) => schema.fields)
    .find((item) => item.candidateFieldId === field.candidateFieldId);
  if (!source
    || source.proposedFieldKey !== field.proposedFieldKey
    || source.displayName !== field.displayName
    || source.required !== field.required
    || !candidateValueEquals(source.value, field.value)) return 'RESOLVED_BY_EDIT' as const;
  return requested;
}

function candidateValueEquals(left: CandidateValue, right: CandidateValue): boolean {
  if (left.kind !== right.kind
    || !candidateValueOrNullEquals(left.items, right.items)
    || !referenceEquals(left.reference, right.reference)
    || left.observedKinds.length !== right.observedKinds.length
    || left.observedKinds.some((value, index) => value !== right.observedKinds[index])) return false;
  const leftConstraints = Object.entries(left.constraints);
  const rightConstraints = Object.entries(right.constraints);
  return leftConstraints.length === rightConstraints.length
    && leftConstraints.every(([key, value]) => right.constraints[key] === value);
}

function candidateValueOrNullEquals(left: CandidateValue | null, right: CandidateValue | null) {
  return left === null || right === null ? left === right : candidateValueEquals(left, right);
}

function referenceEquals(
  left: CandidateValue['reference'],
  right: CandidateValue['reference'],
) {
  if (left === null || right === null) return left === right;
  return left.kind === right.kind
    && left.candidateSchemaId === right.candidateSchemaId
    && left.schemaKey === right.schemaKey
    && left.versionTag === right.versionTag;
}

function updateSchema(
  bundle: CandidateBundle,
  schemaId: string,
  update: (schema: CandidateSchema) => CandidateSchema,
): CandidateBundle {
  return {
    ...bundle,
    schemas: bundle.schemas.map((schema) => schema.candidateSchemaId === schemaId ? update(schema) : schema),
  };
}

function updateField(
  bundle: CandidateBundle,
  schemaId: string,
  fieldId: string,
  update: (field: CandidateField) => CandidateField,
): CandidateBundle {
  return updateSchema(bundle, schemaId, (schema) => ({
    ...schema,
    fields: schema.fields.map((field) => field.candidateFieldId === fieldId ? update(field) : field),
  }));
}

export function newUserField(proposedFieldKey = 'new-field', displayName = '新字段'): CandidateField {
  return {
    candidateFieldId: crypto.randomUUID(),
    proposedFieldKey,
    displayName,
    required: false,
    value: candidateValue('TEXT'),
    source: 'USER',
    assessment: { confidenceBps: null, inferred: false, resolution: 'NOT_REQUIRED', evidence: [] },
  };
}

export function newUserSchema(proposedSchemaKey = 'new-schema', displayName = '新数据结构'): CandidateSchema {
  return {
    candidateSchemaId: crypto.randomUUID(),
    proposedSchemaKey,
    displayName,
    source: 'USER',
    assessment: { confidenceBps: null, inferred: false, resolution: 'NOT_REQUIRED', evidence: [] },
    fields: [],
  };
}

export function nextCandidateKey(base: string, existing: Array<string | null>): string {
  const keys = new Set(existing.filter((value): value is string => Boolean(value)));
  if (!keys.has(base)) return base;
  for (let suffix = 2; suffix <= keys.size + 2; suffix += 1) {
    const candidate = `${base}-${suffix}`;
    if (!keys.has(candidate)) return candidate;
  }
  return `${base}-${crypto.randomUUID().slice(0, 8)}`;
}

export type FinalCandidateKind = Exclude<CandidateValue['kind'], 'UNRESOLVED' | 'CONFLICT'>;
export type ArrayItemCandidateKind = Exclude<FinalCandidateKind, 'ARRAY'>;

export function candidateValue(kind: FinalCandidateKind, candidateSchemaId?: string): CandidateValue {
  if (kind === 'ARRAY') {
    return {
      kind,
      items: candidateValue('TEXT'),
      reference: null,
      observedKinds: [],
      constraints: {},
    };
  }
  if (kind === 'REFERENCE') {
    return {
      kind,
      items: null,
      reference: {
        kind: 'CANDIDATE_SCHEMA',
        candidateSchemaId: candidateSchemaId ?? null,
        schemaKey: null,
        versionTag: null,
      },
      observedKinds: [],
      constraints: {},
    };
  }
  return { kind, items: null, reference: null, observedKinds: [], constraints: {} };
}

export function findSelected(state: CandidateReviewState) {
  const schema = state.draft.schemas.find((item) => item.candidateSchemaId === state.selectedSchemaId)
    ?? state.draft.schemas[0];
  const field = schema?.fields.find((item) => item.candidateFieldId === state.selectedFieldId) ?? null;
  return { schema, field };
}

function moveById<T, K extends keyof T>(items: T[], key: K, id: T[K], direction: -1 | 1): T[] {
  const index = items.findIndex((item) => item[key] === id);
  const target = index + direction;
  if (index < 0 || target < 0 || target >= items.length) return items;
  const moved = [...items];
  [moved[index], moved[target]] = [moved[target]!, moved[index]!];
  return moved;
}
