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
  | { type: 'add-field'; schemaId: string; field: CandidateField }
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
    case 'add-field':
      return change(state, updateSchema(state.draft, action.schemaId, (schema) => ({
        ...schema,
        fields: [...schema.fields, action.field],
      })), action.field.candidateFieldId);
    case 'resolve-schema':
      return change(state, updateSchema(state.draft, action.schemaId, (schema) => ({
        ...schema,
        assessment: { ...schema.assessment, resolution: action.resolution },
      })));
    case 'resolve-field':
      return change(state, updateField(state.draft, action.schemaId, action.fieldId, (field) => ({
        ...field,
        assessment: { ...field.assessment, resolution: action.resolution },
      })));
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

export function newUserField(): CandidateField {
  return {
    candidateFieldId: crypto.randomUUID(),
    proposedFieldKey: 'new-field',
    displayName: '新字段',
    required: false,
    value: candidateValue('TEXT'),
    source: 'USER',
    assessment: { confidenceBps: null, inferred: false, resolution: 'NOT_REQUIRED', evidence: [] },
  };
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
