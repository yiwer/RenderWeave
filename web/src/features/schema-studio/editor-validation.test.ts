import { describe, expect, it } from 'vitest';

import { createEditorValue } from './editor-types';
import { createNewEditorSession, editorReducer } from './editor-session';
import { localDiagnostics, parseExactDecimal } from './editor-validation';

describe('Schema Studio local DSL rules', () => {
  it('uses exact decimal math beyond JavaScript number precision', () => {
    const a = parseExactDecimal('123456789012345678901234567890.00');
    const b = parseExactDecimal('1.2345678901234567890123456789e29');
    expect(a).toEqual(b);
    expect(parseExactDecimal('1e65')).toBeUndefined();
    expect(parseExactDecimal('9'.repeat(129))).toBeUndefined();
  });

  it('accepts a complete seven-type session', () => {
    let state = createNewEditorSession();
    state = editorReducer(state, { type: 'set-schema-key', value: 'all-types' });
    state = editorReducer(state, { type: 'set-display-name', value: 'All types' });
    state = editorReducer(state, {
      type: 'update-field', rowKey: state.fields[0]!.rowKey,
      patch: { fieldKey: 'text', value: createEditorValue('text') },
    });
    for (const type of ['decimal', 'date', 'time', 'boolean', 'reference', 'array'] as const) {
      state = editorReducer(state, { type: 'add-field', valueType: type });
      const field = state.fields.at(-1)!;
      const value = field.value;
      if (value.type === 'reference') {
        value.schemaKey = 'child';
      }
      if (value.type === 'array') {
        value.minItems = { enabled: true, value: '0' };
      }
      state = editorReducer(state, {
        type: 'update-field', rowKey: field.rowKey,
        patch: { fieldKey: type, value },
      });
    }
    expect(localDiagnostics(state)).toEqual([]);
  });

  it('orders field, reference, array and constraint failures by declared field order', () => {
    let state = createNewEditorSession();
    state = editorReducer(state, { type: 'set-schema-key', value: 'bad' });
    state = editorReducer(state, { type: 'set-display-name', value: 'Bad' });
    const first = state.fields[0]!;
    const decimal = createEditorValue('decimal');
    if (decimal.type !== 'decimal') throw new Error('unreachable');
    decimal.min = { enabled: true, value: '10' };
    decimal.max = { enabled: true, value: '1' };
    decimal.enumValues = { enabled: true, values: ['1.0', '1'] };
    state = editorReducer(state, {
      type: 'update-field', rowKey: first.rowKey,
      patch: { fieldKey: 'same', value: decimal },
    });
    state = editorReducer(state, { type: 'add-field', valueType: 'reference' });
    const second = state.fields[1]!;
    const reference = createEditorValue('reference');
    if (reference.type !== 'reference') throw new Error('unreachable');
    reference.schemaKey = 'system-not-a-draft';
    state = editorReducer(state, {
      type: 'update-field', rowKey: second.rowKey,
      patch: { fieldKey: 'same', value: reference },
    });
    state = editorReducer(state, { type: 'add-field', valueType: 'array' });
    const third = state.fields[2]!;
    const array = createEditorValue('array');
    if (array.type !== 'array') throw new Error('unreachable');
    array.uniqueItems = true;
    array.items = { type: 'reference', referenceKind: 'draft', schemaKey: '', versionTag: '' };
    state = editorReducer(state, {
      type: 'update-field', rowKey: third.rowKey,
      patch: { fieldKey: 'items', value: array },
    });

    expect(localDiagnostics(state).map((problem) => problem.code)).toEqual([
      'FIELD_KEY_DUPLICATE',
      'CONSTRAINT_RANGE_INVALID',
      'CONSTRAINT_LITERAL_VIOLATION',
      'CONSTRAINT_ENUM_DUPLICATE',
      'CONSTRAINT_LITERAL_VIOLATION',
      'FIELD_KEY_DUPLICATE',
      'REFERENCE_SCHEMA_KEY_INVALID',
      'ARRAY_UNIQUE_ITEMS_UNSUPPORTED',
      'REFERENCE_SCHEMA_KEY_INVALID',
    ]);
  });
});
