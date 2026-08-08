import {
  cloneEditorFields,
  createEditorValue,
  editorValueFromPersisted,
  type DraftSnapshot,
  type EditorField,
  type EditorValue,
  type EditorValueType,
  type EditorView,
} from './editor-types';

export interface EditorCheckpoint {
  schemaKey: string;
  displayName: string;
  description: string;
  fields: EditorField[];
  nextRowSequence: number;
}

export interface EditorSession extends EditorCheckpoint {
  revision: number | null;
  selectedRowKey: string;
  view: EditorView;
  dirty: boolean;
  saved: EditorCheckpoint | null;
  undoStack: EditorCheckpoint[];
  redoStack: EditorCheckpoint[];
  lastHistoryGroup?: string;
}

export type EditorAction =
  | { type: 'set-schema-key'; value: string; historyGroup?: string }
  | { type: 'set-display-name'; value: string; historyGroup?: string }
  | { type: 'set-description'; value: string; historyGroup?: string }
  | { type: 'set-view'; view: EditorView }
  | { type: 'select-field'; rowKey: string }
  | {
      type: 'update-field';
      rowKey: string;
      patch: Partial<Omit<EditorField, 'rowKey'>>;
      historyGroup?: string;
    }
  | { type: 'set-field-type'; rowKey: string; valueType: EditorValueType }
  | { type: 'set-field-value'; rowKey: string; value: EditorValue; historyGroup?: string }
  | { type: 'add-field'; valueType?: EditorValueType }
  | { type: 'duplicate-field'; rowKey: string }
  | { type: 'delete-field'; rowKey: string }
  | { type: 'move-field'; rowKey: string; direction: -1 | 1 }
  | { type: 'move-field-to'; rowKey: string; targetIndex: number }
  | { type: 'undo' }
  | { type: 'redo' }
  | { type: 'commit-history-group' }
  | { type: 'accept-save'; draft: DraftSnapshot }
  | { type: 'reload-draft'; draft: DraftSnapshot }
  | { type: 'restore-saved' };

const HISTORY_LIMIT = 100;

export function createNewEditorSession(): EditorSession {
  const first = createField('row-1', 1, 'text', true);
  return {
    schemaKey: '',
    revision: null,
    displayName: '',
    description: '',
    fields: [first],
    selectedRowKey: first.rowKey,
    view: 'form',
    dirty: true,
    nextRowSequence: 2,
    saved: null,
    undoStack: [],
    redoStack: [],
  };
}

export function sessionFromDraft(draft: DraftSnapshot): EditorSession {
  const checkpoint = checkpointFromDraft(draft);
  return {
    ...cloneCheckpoint(checkpoint),
    revision: draft.revision,
    selectedRowKey: checkpoint.fields[0]?.rowKey ?? '',
    view: 'form',
    dirty: false,
    saved: cloneCheckpoint(checkpoint),
    undoStack: [],
    redoStack: [],
  };
}

export function editorReducer(state: EditorSession, action: EditorAction): EditorSession {
  switch (action.type) {
    case 'set-schema-key':
      if (state.revision !== null) return state;
      return applySemantic(state, { ...currentCheckpoint(state), schemaKey: action.value }, action.historyGroup);
    case 'set-display-name':
      return applySemantic(
        state,
        { ...currentCheckpoint(state), displayName: action.value },
        action.historyGroup,
      );
    case 'set-description':
      return applySemantic(
        state,
        { ...currentCheckpoint(state), description: action.value },
        action.historyGroup,
      );
    case 'set-view':
      return { ...state, view: action.view, lastHistoryGroup: undefined };
    case 'select-field':
      return state.fields.some((field) => field.rowKey === action.rowKey)
        ? { ...state, selectedRowKey: action.rowKey, lastHistoryGroup: undefined }
        : state;
    case 'update-field':
      return updateField(state, action.rowKey, (field) => ({ ...field, ...action.patch }), action.historyGroup);
    case 'set-field-type':
      return updateField(
        state,
        action.rowKey,
        (field) => ({ ...field, value: createEditorValue(action.valueType) }),
      );
    case 'set-field-value':
      return updateField(
        state,
        action.rowKey,
        (field) => ({ ...field, value: action.value }),
        action.historyGroup,
      );
    case 'add-field': {
      if (state.fields.length >= 256) return state;
      const field = createField(
        `row-${state.nextRowSequence}`,
        state.nextRowSequence,
        action.valueType ?? 'text',
        false,
      );
      return applySemantic(
        state,
        {
          ...currentCheckpoint(state),
          fields: [...cloneEditorFields(state.fields), field],
          nextRowSequence: state.nextRowSequence + 1,
        },
        undefined,
        field.rowKey,
      );
    }
    case 'duplicate-field': {
      if (state.fields.length >= 256) return state;
      const sourceIndex = state.fields.findIndex((field) => field.rowKey === action.rowKey);
      const source = state.fields[sourceIndex];
      if (!source) return state;
      const copy: EditorField = {
        ...cloneEditorFields([source])[0]!,
        rowKey: `row-${state.nextRowSequence}`,
        fieldKey: `${source.fieldKey || 'field'}-copy`,
        displayName: source.displayName ? `${source.displayName} 副本` : '',
      };
      const fields = cloneEditorFields(state.fields);
      fields.splice(sourceIndex + 1, 0, copy);
      return applySemantic(
        state,
        { ...currentCheckpoint(state), fields, nextRowSequence: state.nextRowSequence + 1 },
        undefined,
        copy.rowKey,
      );
    }
    case 'delete-field': {
      const index = state.fields.findIndex((field) => field.rowKey === action.rowKey);
      if (index < 0) return state;
      const fields = state.fields.filter((field) => field.rowKey !== action.rowKey);
      const selected = state.selectedRowKey === action.rowKey
        ? fields[Math.min(index, fields.length - 1)]?.rowKey ?? ''
        : state.selectedRowKey;
      return applySemantic(
        state,
        { ...currentCheckpoint(state), fields: cloneEditorFields(fields) },
        undefined,
        selected,
      );
    }
    case 'move-field': {
      const sourceIndex = state.fields.findIndex((field) => field.rowKey === action.rowKey);
      return moveField(state, action.rowKey, sourceIndex + action.direction);
    }
    case 'move-field-to':
      return moveField(state, action.rowKey, action.targetIndex);
    case 'undo':
      return undo(state);
    case 'redo':
      return redo(state);
    case 'commit-history-group':
      return { ...state, lastHistoryGroup: undefined };
    case 'accept-save':
      return acceptSave(state, action.draft);
    case 'reload-draft': {
      const loaded = sessionFromDraft(action.draft);
      return { ...loaded, view: state.view };
    }
    case 'restore-saved':
      if (!state.saved) return state;
      return applySemantic(
        state,
        cloneCheckpoint(state.saved),
        undefined,
        state.saved.fields.some((field) => field.rowKey === state.selectedRowKey)
          ? state.selectedRowKey
          : state.saved.fields[0]?.rowKey ?? '',
      );
  }
}

function updateField(
  state: EditorSession,
  rowKey: string,
  update: (field: EditorField) => EditorField,
  historyGroup?: string,
): EditorSession {
  if (!state.fields.some((field) => field.rowKey === rowKey)) return state;
  const fields = state.fields.map((field) => field.rowKey === rowKey ? update(field) : field);
  return applySemantic(
    state,
    { ...currentCheckpoint(state), fields: cloneEditorFields(fields) },
    historyGroup,
  );
}

function moveField(state: EditorSession, rowKey: string, targetIndex: number): EditorSession {
  const sourceIndex = state.fields.findIndex((field) => field.rowKey === rowKey);
  if (sourceIndex < 0 || targetIndex < 0 || targetIndex >= state.fields.length || sourceIndex === targetIndex) {
    return state;
  }
  const fields = cloneEditorFields(state.fields);
  const [source] = fields.splice(sourceIndex, 1);
  if (!source) return state;
  fields.splice(targetIndex, 0, source);
  return applySemantic(state, { ...currentCheckpoint(state), fields });
}

function applySemantic(
  state: EditorSession,
  next: EditorCheckpoint,
  historyGroup?: string,
  selectedRowKey = state.selectedRowKey,
): EditorSession {
  const current = currentCheckpoint(state);
  if (checkpointEquals(current, next)) return state;
  const coalesced = historyGroup !== undefined && historyGroup === state.lastHistoryGroup;
  const undoStack = coalesced
    ? state.undoStack
    : [...state.undoStack, cloneCheckpoint(current)].slice(-HISTORY_LIMIT);
  return {
    ...state,
    ...cloneCheckpoint(next),
    selectedRowKey,
    dirty: state.saved === null || !semanticEquals(next, state.saved),
    undoStack,
    redoStack: [],
    lastHistoryGroup: historyGroup,
  };
}

function undo(state: EditorSession): EditorSession {
  const previous = state.undoStack.at(-1);
  if (!previous) return state;
  return {
    ...state,
    ...cloneCheckpoint(previous),
    selectedRowKey: selectedAfterRestore(state.selectedRowKey, previous),
    dirty: state.saved === null || !semanticEquals(previous, state.saved),
    undoStack: state.undoStack.slice(0, -1),
    redoStack: [...state.redoStack, currentCheckpoint(state)].slice(-HISTORY_LIMIT),
    lastHistoryGroup: undefined,
  };
}

function redo(state: EditorSession): EditorSession {
  const next = state.redoStack.at(-1);
  if (!next) return state;
  return {
    ...state,
    ...cloneCheckpoint(next),
    selectedRowKey: selectedAfterRestore(state.selectedRowKey, next),
    dirty: state.saved === null || !semanticEquals(next, state.saved),
    undoStack: [...state.undoStack, currentCheckpoint(state)].slice(-HISTORY_LIMIT),
    redoStack: state.redoStack.slice(0, -1),
    lastHistoryGroup: undefined,
  };
}

function acceptSave(state: EditorSession, draft: DraftSnapshot): EditorSession {
  const saved = checkpointFromDraft(draft, state.fields);
  const firstCreate = state.revision === null;
  const lockIdentity = (checkpoint: EditorCheckpoint): EditorCheckpoint => ({
    ...cloneCheckpoint(checkpoint),
    schemaKey: draft.schemaKey,
  });
  return {
    ...state,
    ...saved,
    revision: draft.revision,
    selectedRowKey: selectedAfterRestore(state.selectedRowKey, saved),
    dirty: false,
    saved: cloneCheckpoint(saved),
    undoStack: firstCreate ? state.undoStack.map(lockIdentity) : state.undoStack,
    redoStack: firstCreate ? state.redoStack.map(lockIdentity) : state.redoStack,
    lastHistoryGroup: undefined,
  };
}

function checkpointFromDraft(draft: DraftSnapshot, preferredRows: EditorField[] = []): EditorCheckpoint {
  const fields = draft.definition.fields.map((field, index): EditorField => ({
    rowKey: preferredRows[index]?.rowKey ?? `row-${index + 1}`,
    fieldKey: field.fieldKey,
    displayName: field.displayName ?? '',
    description: field.description ?? '',
    required: field.required,
    value: editorValueFromPersisted(field.value),
  }));
  const maxSequence = fields.reduce((highest, field) => {
    const match = /^row-(\d+)$/.exec(field.rowKey);
    return match ? Math.max(highest, Number(match[1])) : highest;
  }, 0);
  return {
    schemaKey: draft.schemaKey,
    displayName: draft.definition.displayName,
    description: draft.definition.description ?? '',
    fields,
    nextRowSequence: Math.max(maxSequence + 1, fields.length + 1),
  };
}

function createField(
  rowKey: string,
  sequence: number,
  valueType: EditorValueType,
  first: boolean,
): EditorField {
  return {
    rowKey,
    fieldKey: first ? '' : `field-${sequence}`,
    displayName: first ? '' : `字段 ${sequence}`,
    description: '',
    required: false,
    value: createEditorValue(valueType),
  };
}

function currentCheckpoint(state: EditorSession): EditorCheckpoint {
  return cloneCheckpoint(state);
}

function cloneCheckpoint(checkpoint: EditorCheckpoint): EditorCheckpoint {
  return {
    schemaKey: checkpoint.schemaKey,
    displayName: checkpoint.displayName,
    description: checkpoint.description,
    fields: cloneEditorFields(checkpoint.fields),
    nextRowSequence: checkpoint.nextRowSequence,
  };
}

function checkpointEquals(left: EditorCheckpoint, right: EditorCheckpoint): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function semanticEquals(left: EditorCheckpoint, right: EditorCheckpoint): boolean {
  return semanticFingerprint(left) === semanticFingerprint(right);
}

function semanticFingerprint(checkpoint: EditorCheckpoint): string {
  return JSON.stringify({
    schemaKey: checkpoint.schemaKey,
    displayName: checkpoint.displayName,
    description: checkpoint.description,
    fields: checkpoint.fields.map((field) => ({
      fieldKey: field.fieldKey,
      displayName: field.displayName,
      description: field.description,
      required: field.required,
      value: field.value,
    })),
  });
}

function selectedAfterRestore(selectedRowKey: string, checkpoint: EditorCheckpoint): string {
  return checkpoint.fields.some((field) => field.rowKey === selectedRowKey)
    ? selectedRowKey
    : checkpoint.fields[0]?.rowKey ?? '';
}
