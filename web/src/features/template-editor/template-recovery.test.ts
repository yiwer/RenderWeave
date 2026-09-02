import { describe, expect, it, vi } from 'vitest';

import { createSessionFromBaseline, type StructuredEditorSession } from './template-editor-model';
import { applyTemplateDisplayName } from './template-editor-session';
import { structuredBaseline } from './template-editor-test-support';
import { templateContentHashOf } from './template-open';
import type { TemplateUnknownSaveAttempt } from './template-save';
import {
  LOCAL_RECOVERY_TTL_MS,
  LOCAL_RECOVERY_VERSION,
  buildTemplateRecoveryRecord,
  clearTemplateRecovery,
  loadTemplateRecovery,
  persistTemplateRecovery,
  recoveryOverwriteOffer,
  restoreStructuredSessionFromRecovery,
  templateRecoveryKey,
  type TemplateRecoveryEditState,
  type TemplateRecoveryStorage,
} from './template-recovery';

const UPDATED_AT = '2026-08-21T00:00:00.000Z';
const EDIT_STATE: TemplateRecoveryEditState = {
  entry: 'assets',
  selectedNodeId: 'rect-id',
  navigatorOpen: false,
  inspectorOpen: true,
};

describe('Template Editor E7 Local recovery contract', () => {
  it('writes one exact versioned record without runtime inputs or asset payloads', async () => {
    const storage = new MemoryStorage();
    const session = dirtySession('设备草稿');
    const record = await buildTemplateRecoveryRecord(session, EDIT_STATE, UPDATED_AT);

    expect(persistTemplateRecovery(storage, record)).toEqual({ state: 'stored' });
    expect(storage.keys()).toEqual([templateRecoveryKey(session.baseline.templateId)]);
    const stored = JSON.parse(storage.onlyValue()) as Record<string, unknown>;
    expect(Object.keys(stored)).toEqual([
      'recordVersion',
      'templateId',
      'staticSchema',
      'baseRevision',
      'baseContentHash',
      'draftCanonical',
      'draftContentHash',
      'previewGeneration',
      'editState',
      'updatedAt',
    ]);
    expect(stored.recordVersion).toBe(LOCAL_RECOVERY_VERSION);
    expect(stored.draftCanonical).toBe(session.workingCopy.canonicalDesignDsl);
    expect(stored.editState).toEqual(EDIT_STATE);
    expect(storage.onlyValue()).not.toMatch(
      /RootDocument|customValues|previewImage|assetBytes|RenderDocument|sidecar|lease/,
    );
  });

  it('atomically replaces the one per-Template record', async () => {
    const storage = new MemoryStorage();
    const first = await buildTemplateRecoveryRecord(
      dirtySession('first'), EDIT_STATE, UPDATED_AT,
    );
    const second = await buildTemplateRecoveryRecord(
      dirtySession('second'), { ...EDIT_STATE, entry: 'structure' },
      '2026-08-21T00:00:01.000Z',
    );

    persistTemplateRecovery(storage, first);
    persistTemplateRecovery(storage, second);

    expect(storage.keys()).toHaveLength(1);
    expect(storage.onlyValue()).toContain('second');
    expect(storage.setItem).toHaveBeenCalledTimes(2);
  });

  it('isolates recovery records by Template identity', async () => {
    const storage = new MemoryStorage();
    const first = dirtySession('first Template');
    const secondBaseline = structuredBaseline();
    secondBaseline.templateId = '85c87bcc-0fe9-4cdf-8939-e7636976647b';
    const secondClean = createSessionFromBaseline(
      secondBaseline,
      { state: 'checked', value: 'READY' },
    );
    if (secondClean.mode !== 'structured') throw new Error('expected Structured Editor');
    const secondChange = applyTemplateDisplayName(secondClean, 'second Template');
    if (secondChange.state !== 'applied') throw new Error('expected applied edit');

    persistTemplateRecovery(
      storage,
      await buildTemplateRecoveryRecord(first, EDIT_STATE, UPDATED_AT),
    );
    persistTemplateRecovery(
      storage,
      await buildTemplateRecoveryRecord(secondChange.session, EDIT_STATE, UPDATED_AT),
    );

    expect(storage.keys().sort()).toEqual([
      templateRecoveryKey(first.baseline.templateId),
      templateRecoveryKey(secondBaseline.templateId),
    ].sort());
  });

  it('classifies an exact base as matching and a later trusted current as drifted', async () => {
    const storage = new MemoryStorage();
    const session = dirtySession('recover me');
    persistTemplateRecovery(
      storage,
      await buildTemplateRecoveryRecord(session, EDIT_STATE, UPDATED_AT),
    );

    const matching = await loadTemplateRecovery(
      storage,
      session.baseline,
      Date.parse(UPDATED_AT) + 1,
    );
    expect(matching.state).toBe('available');
    if (matching.state !== 'available') throw new Error('expected available');
    expect(matching.baseState).toBe('matching');

    const advanced = structuredBaseline();
    advanced.revision = '8';
    advanced.contentHash = 'sha256:' + 'b'.repeat(64);
    advanced.canonicalDesignDsl = advanced.canonicalDesignDsl.replace('门店价签', '远端版本');
    advanced.designDsl.displayName = '远端版本';
    const drifted = await loadTemplateRecovery(
      storage,
      advanced,
      Date.parse(UPDATED_AT) + 1,
    );
    expect(drifted.state).toBe('available');
    if (drifted.state !== 'available') throw new Error('expected available');
    expect(drifted.baseState).toBe('drifted');
  });

  it('keeps the record at exactly seven days and clears it only after the boundary', async () => {
    const storage = new MemoryStorage();
    const session = dirtySession('seven day draft');
    persistTemplateRecovery(
      storage,
      await buildTemplateRecoveryRecord(session, EDIT_STATE, UPDATED_AT),
    );

    expect((await loadTemplateRecovery(
      storage,
      session.baseline,
      Date.parse(UPDATED_AT) + LOCAL_RECOVERY_TTL_MS,
    )).state).toBe('available');
    const expired = await loadTemplateRecovery(
      storage,
      session.baseline,
      Date.parse(UPDATED_AT) + LOCAL_RECOVERY_TTL_MS + 1,
    );
    expect(expired.state).toBe('expired');
    expect(storage.keys()).toEqual([]);
  });

  it('fails closed on tampering, non-canonical content, schema mismatch, or revision rollback', async () => {
    const session = dirtySession('safe draft');
    const record = await buildTemplateRecoveryRecord(session, EDIT_STATE, UPDATED_AT);

    const tampered = new MemoryStorage();
    persistTemplateRecovery(tampered, record);
    tampered.replaceRaw(tampered.onlyValue().replace('safe draft', 'evil draft'));
    const tamperedResult = await loadTemplateRecovery(
      tampered, session.baseline, Date.parse(UPDATED_AT) + 1,
    );
    expect(tamperedResult).toMatchObject({ state: 'invalid', reason: 'DRAFT_HASH_MISMATCH' });
    if (tamperedResult.state !== 'invalid') throw new Error('expected invalid');
    expect(tamperedResult.exportCanonical).toContain('evil draft');

    const nonCanonical = new MemoryStorage();
    persistTemplateRecovery(nonCanonical, record);
    nonCanonical.mutate((value) => ({
      ...value,
      draftCanonical: '{ "displayName": "not canonical" }',
      draftContentHash: 'sha256:' + '0'.repeat(64),
    }));
    expect((await loadTemplateRecovery(
      nonCanonical, session.baseline, Date.parse(UPDATED_AT) + 1,
    )).state).toBe('invalid');

    const schemaMismatch = structuredBaseline();
    schemaMismatch.staticSchema = { schemaKey: 'other', versionTag: 'v1' };
    const schemaResult = await loadTemplateRecovery(
      storageWith(record), schemaMismatch, Date.parse(UPDATED_AT) + 1,
    );
    expect(schemaResult).toMatchObject({ state: 'invalid', reason: 'STATIC_SCHEMA_MISMATCH' });

    const rolledBack = structuredBaseline();
    rolledBack.revision = '6';
    const rollbackResult = await loadTemplateRecovery(
      storageWith(record), rolledBack, Date.parse(UPDATED_AT) + 1,
    );
    expect(rollbackResult).toMatchObject({ state: 'invalid', reason: 'BASE_REVISION_AHEAD' });
  });

  it('classifies a canonical malformed closed wire as unsupported recovery', async () => {
    const session = dirtySession('malformed recovery');
    const record = await buildTemplateRecoveryRecord(session, EDIT_STATE, UPDATED_AT);
    const malformed = JSON.parse(record.draftCanonical) as Record<string, unknown>;
    const frame = (((malformed.designRoot as Record<string, unknown>)
      .children as Record<string, unknown>[])[0]);
    if (!frame) throw new Error('expected Frame fixture');
    delete frame.nodeId;
    const draftCanonical = JSON.stringify(malformed);
    const draftContentHash = await templateContentHashOf(draftCanonical);
    const malformedRecord = { ...record, draftCanonical, draftContentHash };

    expect(await loadTemplateRecovery(
      storageWith(malformedRecord),
      session.baseline,
      Date.parse(UPDATED_AT) + 1,
    )).toMatchObject({ state: 'invalid', reason: 'DRAFT_UNSUPPORTED' });
    expect(restoreStructuredSessionFromRecovery(
      cleanSessionAt('7'),
      malformedRecord,
    )).toMatchObject({ state: 'invalid', reason: 'DRAFT_UNSUPPORTED' });
  });

  it('reports unavailable storage without claiming persistence or clearing', async () => {
    const session = dirtySession('quota draft');
    const record = await buildTemplateRecoveryRecord(session, EDIT_STATE, UPDATED_AT);
    const storage = new MemoryStorage();
    storage.setItem.mockImplementation(() => { throw new DOMException('quota', 'QuotaExceededError'); });

    expect(persistTemplateRecovery(storage, record)).toEqual({
      state: 'unavailable',
      operation: 'write',
    });

    const brokenRead = new MemoryStorage();
    brokenRead.getItem.mockImplementation(() => { throw new DOMException('denied', 'SecurityError'); });
    expect(await loadTemplateRecovery(
      brokenRead, session.baseline, Date.parse(UPDATED_AT) + 1,
    )).toEqual({ state: 'unavailable', operation: 'read' });

    const brokenClear = new MemoryStorage();
    brokenClear.removeItem.mockImplementation(() => { throw new DOMException('denied', 'SecurityError'); });
    expect(clearTemplateRecovery(brokenClear, session.baseline.templateId)).toEqual({
      state: 'unavailable',
      operation: 'clear',
    });
  });

  it('restores a complete structured working copy with empty history and an explicit drift offer', async () => {
    const dirty = dirtySession('restored canonical');
    const record = await buildTemplateRecoveryRecord(dirty, EDIT_STATE, UPDATED_AT);
    const current = cleanSessionAt('8');

    const restored = restoreStructuredSessionFromRecovery(current, record);
    expect(restored.state).toBe('restored');
    if (restored.state !== 'restored') throw new Error('expected restored');
    expect(restored.session.workingCopy.canonicalDesignDsl).toBe(
      dirty.workingCopy.canonicalDesignDsl,
    );
    expect(restored.session.baseline.revision).toBe('8');
    expect(restored.session.history).toEqual({ past: [], future: [] });
    expect(restored.session.previewGeneration).toBe(record.previewGeneration);

    expect(recoveryOverwriteOffer(restored.session, record, 'drifted')).toEqual({
      offeredRevision: '8',
      draftCanonical: dirty.workingCopy.canonicalDesignDsl,
      previewGeneration: record.previewGeneration,
    });
    expect(recoveryOverwriteOffer(restored.session, record, 'matching')).toBeNull();
  });

  it('persists and validates the exact unknown attempt needed for cross-refresh reconciliation', async () => {
    const session = dirtySession('unknown save');
    const attempt = await unknownAttempt(session);
    const record = await buildTemplateRecoveryRecord(
      session,
      EDIT_STATE,
      UPDATED_AT,
      attempt,
    );
    const storage = storageWith(record);

    const loaded = await loadTemplateRecovery(
      storage,
      session.baseline,
      Date.parse(UPDATED_AT) + 1,
    );
    expect(loaded.state).toBe('available');
    if (loaded.state !== 'available') throw new Error('expected available');
    expect(loaded.record.unknownAttempt).toEqual(attempt);
    expect(Object.isFrozen(loaded.record.unknownAttempt)).toBe(true);
    expect(Object.isFrozen(loaded.record.unknownAttempt?.expectedCurrent)).toBe(true);
    expect(Object.keys(JSON.parse(storage.onlyValue()))).toContain('unknownAttempt');

    storage.mutate((value) => ({
      ...value,
      unknownAttempt: {
        ...(value.unknownAttempt as Record<string, unknown>),
        previewGeneration: attempt.previewGeneration + 1,
      },
    }));
    expect(await loadTemplateRecovery(
      storage,
      session.baseline,
      Date.parse(UPDATED_AT) + 1,
    )).toMatchObject({ state: 'invalid', reason: 'UNKNOWN_ATTEMPT_MISMATCH' });

    storage.mutate((value) => ({ ...value, unknownAttempt: null }));
    expect(await loadTemplateRecovery(
      storage,
      session.baseline,
      Date.parse(UPDATED_AT) + 1,
    )).toMatchObject({ state: 'invalid', reason: 'UNKNOWN_ATTEMPT_MISMATCH' });
  });
});

class MemoryStorage implements TemplateRecoveryStorage {
  private readonly values = new Map<string, string>();

  readonly getItem = vi.fn((key: string) => this.values.get(key) ?? null);
  readonly setItem = vi.fn((key: string, value: string) => { this.values.set(key, value); });
  readonly removeItem = vi.fn((key: string) => { this.values.delete(key); });

  keys(): string[] {
    return [...this.values.keys()];
  }

  onlyValue(): string {
    const value = [...this.values.values()][0];
    if (value === undefined) throw new Error('expected one value');
    return value;
  }

  replaceRaw(value: string) {
    const key = this.keys()[0];
    if (!key) throw new Error('expected one key');
    this.values.set(key, value);
  }

  mutate(change: (value: Record<string, unknown>) => Record<string, unknown>) {
    this.replaceRaw(JSON.stringify(change(JSON.parse(this.onlyValue()) as Record<string, unknown>)));
  }
}

function storageWith(record: Awaited<ReturnType<typeof buildTemplateRecoveryRecord>>): MemoryStorage {
  const storage = new MemoryStorage();
  persistTemplateRecovery(storage, record);
  return storage;
}

function dirtySession(name: string): StructuredEditorSession {
  const result = applyTemplateDisplayName(cleanSessionAt('7'), name);
  if (result.state !== 'applied') throw new Error(`expected applied, got ${result.state}`);
  return result.session;
}

function cleanSessionAt(revision: string): StructuredEditorSession {
  const baseline = structuredBaseline();
  baseline.revision = revision;
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

async function unknownAttempt(
  session: StructuredEditorSession,
): Promise<TemplateUnknownSaveAttempt> {
  return {
    expectedCurrent: {
      templateId: session.baseline.templateId,
      revision: session.baseline.revision,
      staticSchema: { ...session.baseline.staticSchema },
      contentHash: session.baseline.contentHash,
      canonicalDesignDsl: session.baseline.canonicalDesignDsl,
    },
    expectedRevision: session.baseline.revision,
    draftCanonical: session.workingCopy.canonicalDesignDsl,
    proposedContentHash: await templateContentHashOf(session.workingCopy.canonicalDesignDsl),
    previewGeneration: session.previewGeneration,
    requiredReadiness: 'READY',
  };
}
