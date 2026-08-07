import { describe, expect, it } from 'vitest';

import { diffDraftDefinitions } from './definition-diff';
import { createNewEditorSession, editorReducer } from './editor-session';
import type { DraftSnapshot } from './editor-types';

describe('revision conflict structural diff', () => {
  it('reports metadata, changed, added, removed and ordering differences without merging', () => {
    let local = createNewEditorSession();
    local = editorReducer(local, { type: 'set-schema-key', value: 'card' });
    local = editorReducer(local, { type: 'set-display-name', value: 'Local' });
    local = editorReducer(local, { type: 'update-field', rowKey: local.fields[0]!.rowKey, patch: { fieldKey: 'title', required: true } });
    local = editorReducer(local, { type: 'add-field', valueType: 'decimal' });
    local = editorReducer(local, { type: 'update-field', rowKey: local.fields[1]!.rowKey, patch: { fieldKey: 'amount' } });
    const server: DraftSnapshot = {
      schemaKey: 'card', revision: 4, creationSource: 'USER',
      createdAt: '2026-08-08T00:00:00Z', updatedAt: '2026-08-08T00:00:00Z', savedAt: '2026-08-08T00:00:00Z', resolvedRevisions: { card: 4 },
      definition: { dslVersion: 'renderweave-schema/1.0', displayName: 'Server', fields: [
        { fieldKey: 'legacy', required: false, value: { type: 'boolean' } },
        { fieldKey: 'title', required: false, value: { type: 'text' } },
      ] },
    };
    const diffs = diffDraftDefinitions(local, server);
    expect(diffs.map((diff) => diff.kind)).toEqual(['changed', 'changed', 'added', 'removed']);
    expect(diffs.some((diff) => diff.label.includes('title'))).toBe(true);
    expect(local.revision).toBeNull();
  });
});
