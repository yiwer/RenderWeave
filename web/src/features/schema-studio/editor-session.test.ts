import { describe, expect, it } from 'vitest';

import { serializeDefinition, type DraftSnapshot } from './editor-types';
import { createNewEditorSession, editorReducer, sessionFromDraft } from './editor-session';

describe('full Schema Studio EditorSession', () => {
  it('projects one semantic state through form and map without history noise', () => {
    let state = createNewEditorSession();
    expect(state.view).toBe('map');
    state = editorReducer(state, { type: 'set-schema-key', value: 'campaign-card' });
    state = editorReducer(state, { type: 'set-display-name', value: '商品推广卡' });
    const rowKey = state.fields[0]!.rowKey;
    state = editorReducer(state, {
      type: 'update-field',
      rowKey,
      patch: { fieldKey: 'title', displayName: '主标题', required: true },
    });
    const before = serializeDefinition(state.displayName, state.description, state.fields);
    const undoCount = state.undoStack.length;

    state = editorReducer(state, { type: 'set-view', view: 'form' });
    state = editorReducer(state, { type: 'select-field', rowKey });
    state = editorReducer(state, { type: 'set-view', view: 'map' });

    expect(serializeDefinition(state.displayName, state.description, state.fields)).toBe(before);
    expect(state.undoStack).toHaveLength(undoCount);
  });

  it('coalesces continuous typing and supports bounded undo and redo', () => {
    let state = createNewEditorSession();
    state = editorReducer(state, {
      type: 'set-display-name', value: '商', historyGroup: 'schema-display-name',
    });
    state = editorReducer(state, {
      type: 'set-display-name', value: '商品', historyGroup: 'schema-display-name',
    });
    state = editorReducer(state, {
      type: 'set-display-name', value: '商品卡', historyGroup: 'schema-display-name',
    });
    expect(state.undoStack).toHaveLength(1);

    state = editorReducer(state, { type: 'commit-history-group' });
    for (let index = 0; index < 110; index += 1) {
      state = editorReducer(state, { type: 'set-description', value: `description-${index}` });
    }
    expect(state.undoStack).toHaveLength(100);

    state = editorReducer(state, { type: 'undo' });
    expect(state.description).toBe('description-108');
    state = editorReducer(state, { type: 'redo' });
    expect(state.description).toBe('description-109');
  });

  it('keeps history after save, locks a newly-created identity, and clears history only on reload', () => {
    let state = createNewEditorSession();
    state = editorReducer(state, { type: 'set-schema-key', value: 'saved-card' });
    state = editorReducer(state, { type: 'set-display-name', value: '  保存卡片  ' });
    state = editorReducer(state, {
      type: 'update-field',
      rowKey: state.fields[0]!.rowKey,
      patch: { fieldKey: 'title' },
    });
    const historyBeforeSave = state.undoStack.length;
    const draft = textDraft('saved-card', 0, '保存卡片', 'title');

    state = editorReducer(state, { type: 'accept-save', draft });
    expect(state.dirty).toBe(false);
    expect(state.undoStack).toHaveLength(historyBeforeSave);
    expect(state.displayName).toBe('保存卡片');

    state = editorReducer(state, { type: 'undo' });
    expect(state.schemaKey).toBe('saved-card');
    expect(state.dirty).toBe(true);

    state = editorReducer(state, { type: 'reload-draft', draft });
    expect(state.undoStack).toEqual([]);
    expect(state.redoStack).toEqual([]);
    expect(state.dirty).toBe(false);
  });

  it('restores the saved definition as an undoable semantic action', () => {
    let state = sessionFromDraft(textDraft('restore-card', 4, 'Saved', 'title'));
    state = editorReducer(state, { type: 'set-display-name', value: 'Local change' });
    expect(state.dirty).toBe(true);

    state = editorReducer(state, { type: 'restore-saved' });
    expect(state.displayName).toBe('Saved');
    expect(state.dirty).toBe(false);

    state = editorReducer(state, { type: 'undo' });
    expect(state.displayName).toBe('Local change');
    expect(state.dirty).toBe(true);
  });

  it('round-trips all types and preserves 128-digit decimal lexemes as numbers', () => {
    const huge = '12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678';
    const draft: DraftSnapshot = {
      schemaKey: 'all-types', revision: 9, creationSource: 'USER',
      createdAt: '2026-08-08T00:00:00Z', updatedAt: '2026-08-08T00:00:00Z',
      savedAt: '2026-08-08T00:00:00Z', resolvedRevisions: { 'all-types': 9 },
      definition: {
        dslVersion: 'renderweave-schema/1.0', displayName: 'All types', fields: [
          { fieldKey: 'text', required: true, value: { type: 'text', constraints: { const: '' } } },
          { fieldKey: 'decimal', required: true, value: { type: 'decimal', constraints: { const: huge } } },
          { fieldKey: 'date', required: false, value: { type: 'date', constraints: { min: '2026-01-01' } } },
          { fieldKey: 'time', required: false, value: { type: 'time', constraints: { max: '16:32:00' } } },
          { fieldKey: 'boolean', required: false, value: { type: 'boolean', constraints: { const: false } } },
          { fieldKey: 'reference', required: false, value: { type: 'reference', ref: { schemaKey: 'child', versionTag: 'v1' } } },
          { fieldKey: 'array', required: false, value: { type: 'array', constraints: { uniqueItems: true }, items: { type: 'decimal', constraints: { multipleOf: '0.01' } } } },
        ],
      },
    };
    const state = sessionFromDraft(draft);
    expect(state.view).toBe('map');
    const serialized = serializeDefinition(state.displayName, state.description, state.fields);

    expect(state.fields.map((field) => field.value.type)).toEqual([
      'text', 'decimal', 'date', 'time', 'boolean', 'reference', 'array',
    ]);
    expect(serialized).toContain(`"const":${huge}`);
    expect(serialized).not.toContain(`"const":"${huge}"`);
    expect(serialized).toContain('"multipleOf":0.01');
  });

  it('supports add, duplicate, reorder and delete with stable row identities', () => {
    let state = createNewEditorSession();
    const first = state.fields[0]!.rowKey;
    state = editorReducer(state, { type: 'add-field', valueType: 'reference' });
    const second = state.fields[1]!.rowKey;
    state = editorReducer(state, { type: 'duplicate-field', rowKey: second });
    const copy = state.fields[2]!.rowKey;
    state = editorReducer(state, { type: 'move-field-to', rowKey: copy, targetIndex: 0 });
    state = editorReducer(state, { type: 'delete-field', rowKey: second });

    expect(state.fields.map((field) => field.rowKey)).toEqual([copy, first]);
    expect(state.selectedRowKey).toBe(copy);
  });

  it('supports a representative 256-field definition while enforcing the hard limit', () => {
    let state = createNewEditorSession();
    for (let index = 1; index < 256; index += 1) {
      state = editorReducer(state, { type: 'add-field', valueType: index % 2 === 0 ? 'array' : 'reference' });
    }
    expect(state.fields).toHaveLength(256);
    const last = state.fields.at(-1)!;
    state = editorReducer(state, { type: 'move-field-to', rowKey: last.rowKey, targetIndex: 0 });
    expect(state.fields[0]?.rowKey).toBe(last.rowKey);
    state = editorReducer(state, { type: 'add-field', valueType: 'decimal' });
    expect(state.fields).toHaveLength(256);
  });
});

function textDraft(
  schemaKey: string,
  revision: number,
  displayName: string,
  fieldKey: string,
): DraftSnapshot {
  return {
    schemaKey,
    revision,
    creationSource: 'USER',
    createdAt: '2026-08-08T00:00:00Z',
    updatedAt: '2026-08-08T00:00:00Z',
    savedAt: '2026-08-08T00:00:00Z',
    resolvedRevisions: { [schemaKey]: revision },
    definition: {
      dslVersion: 'renderweave-schema/1.0',
      displayName,
      fields: [{ fieldKey, required: true, value: { type: 'text' } }],
    },
  };
}
