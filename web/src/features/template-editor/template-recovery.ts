import { parse } from 'lossless-json';

import {
  createSessionFromBaseline,
  type CanonicalTemplateBaseline,
  type EditorReadiness,
  MalformedDesignDslWireError,
  type StructuredEditorSession,
} from './template-editor-model';
import { canonicalStringifyWorkingValue, isCanonicalDirty } from './template-editor-session';
import { templateContentHashOf } from './template-open';
import type { TemplateConflictOffer, TemplateUnknownSaveAttempt } from './template-save';

export const LOCAL_RECOVERY_VERSION = 'renderweave-template-local-recovery/1';
export const LOCAL_RECOVERY_DEBOUNCE_MS = 500;
export const LOCAL_RECOVERY_TTL_MS = 7 * 24 * 60 * 60 * 1000;

const KEY_PREFIX = 'renderweave.template-local-recovery.v1:';
const CONTENT_HASH_PATTERN = /^sha256:[0-9a-f]{64}$/;
const REVISION_PATTERN = /^(0|[1-9][0-9]*)$/;
const CONFIRMATION_TOKEN_PATTERN = /^[0-9a-f]{64}$/;
const MAX_REVISION = 9_223_372_036_854_775_807n;
const MAX_CANONICAL_BYTES = 16 * 1024 * 1024;
const ENTRY_VALUES = new Set<TemplateRecoveryEntry>([
  'structure', 'nodes', 'assets', 'definitions', 'exchange',
]);
const textEncoder = new TextEncoder();

export type TemplateRecoveryEntry =
  | 'structure'
  | 'nodes'
  | 'assets'
  | 'definitions'
  | 'exchange';

export interface TemplateRecoveryEditState {
  readonly entry: TemplateRecoveryEntry;
  readonly selectedNodeId: string;
  readonly navigatorOpen: boolean;
  readonly inspectorOpen: boolean;
}

export interface TemplateRecoveryBase {
  readonly revision: string;
  readonly contentHash: string;
}

export interface TemplateRecoveryRecord {
  readonly recordVersion: typeof LOCAL_RECOVERY_VERSION;
  readonly templateId: string;
  readonly staticSchema: {
    readonly schemaKey: string;
    readonly versionTag: string;
  };
  readonly baseRevision: string;
  readonly baseContentHash: string;
  readonly draftCanonical: string;
  readonly draftContentHash: string;
  readonly previewGeneration: number;
  readonly editState: TemplateRecoveryEditState;
  readonly updatedAt: string;
  readonly unknownAttempt?: TemplateUnknownSaveAttempt;
}

export interface TemplateRecoveryStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export type TemplateRecoveryWriteResult =
  | { state: 'stored' }
  | { state: 'cleared' }
  | { state: 'unavailable'; operation: 'write' | 'clear' };

export type TemplateRecoveryInvalidReason =
  | 'MALFORMED_ENVELOPE'
  | 'UNSUPPORTED_VERSION'
  | 'TEMPLATE_ID_MISMATCH'
  | 'STATIC_SCHEMA_MISMATCH'
  | 'BASE_REVISION_INVALID'
  | 'BASE_REVISION_AHEAD'
  | 'BASE_CONTENT_MISMATCH'
  | 'DRAFT_TOO_LARGE'
  | 'DRAFT_NOT_CANONICAL'
  | 'DRAFT_UNSUPPORTED'
  | 'DRAFT_HASH_MISMATCH'
  | 'EDIT_STATE_INVALID'
  | 'UPDATED_AT_INVALID'
  | 'UNKNOWN_ATTEMPT_MISMATCH';

export type TemplateRecoveryLoadResult =
  | { state: 'absent' }
  | { state: 'expired' }
  | { state: 'obsolete' }
  | { state: 'unavailable'; operation: 'read' | 'clear' }
  | {
    state: 'invalid';
    reason: TemplateRecoveryInvalidReason;
    exportCanonical?: string;
  }
  | {
    state: 'available';
    baseState: 'matching' | 'drifted';
    record: TemplateRecoveryRecord;
  };

export type TemplateRecoveryRestoreResult =
  | { state: 'restored'; session: StructuredEditorSession }
  | { state: 'invalid'; reason: 'CURRENT_SESSION_DIRTY' | 'DRAFT_UNSUPPORTED' | 'DRAFT_NOT_DIRTY' };

export function templateRecoveryKey(templateId: string): string {
  return `${KEY_PREFIX}${encodeURIComponent(templateId)}`;
}

export function browserTemplateRecoveryStorage(): TemplateRecoveryStorage | undefined {
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

export async function buildTemplateRecoveryRecord(
  session: StructuredEditorSession,
  editState: TemplateRecoveryEditState,
  updatedAt: string,
  unknownAttempt?: TemplateUnknownSaveAttempt,
  baseOverride?: TemplateRecoveryBase,
): Promise<TemplateRecoveryRecord> {
  if (!validIsoInstant(updatedAt)) {
    throw new Error('Local recovery updatedAt must be an exact ISO instant');
  }
  if (!validEditState(editState)) {
    throw new Error('Local recovery edit state is invalid');
  }
  const draftCanonical = session.workingCopy.canonicalDesignDsl;
  if (textEncoder.encode(draftCanonical).byteLength > MAX_CANONICAL_BYTES) {
    throw new Error('Local recovery canonical draft exceeds 16 MiB');
  }
  const draftContentHash = await templateContentHashOf(draftCanonical);
  const attemptBase = unknownAttempt?.expectedCurrent;
  const baseRevision = attemptBase?.revision
    ?? baseOverride?.revision
    ?? session.baseline.revision;
  const baseContentHash = attemptBase?.contentHash
    ?? baseOverride?.contentHash
    ?? session.baseline.contentHash;

  const record: TemplateRecoveryRecord = {
    recordVersion: LOCAL_RECOVERY_VERSION,
    templateId: session.baseline.templateId,
    staticSchema: Object.freeze({ ...session.baseline.staticSchema }),
    baseRevision,
    baseContentHash,
    draftCanonical,
    draftContentHash,
    previewGeneration: session.previewGeneration,
    editState: Object.freeze({ ...editState }),
    updatedAt,
    ...(unknownAttempt ? { unknownAttempt: immutableAttempt(unknownAttempt) } : {}),
  };
  if (unknownAttempt && !(await unknownAttemptMatchesRecord(unknownAttempt, record))) {
    throw new Error('Unknown save attempt does not match the Local recovery record');
  }
  return Object.freeze(record);
}

export function persistTemplateRecovery(
  storage: TemplateRecoveryStorage,
  record: TemplateRecoveryRecord,
): TemplateRecoveryWriteResult {
  try {
    storage.setItem(templateRecoveryKey(record.templateId), JSON.stringify(record));
    return { state: 'stored' };
  } catch {
    return { state: 'unavailable', operation: 'write' };
  }
}

export function clearTemplateRecovery(
  storage: TemplateRecoveryStorage,
  templateId: string,
): TemplateRecoveryWriteResult {
  try {
    storage.removeItem(templateRecoveryKey(templateId));
    return { state: 'cleared' };
  } catch {
    return { state: 'unavailable', operation: 'clear' };
  }
}

export async function loadTemplateRecovery(
  storage: TemplateRecoveryStorage,
  current: CanonicalTemplateBaseline,
  nowMillis = Date.now(),
): Promise<TemplateRecoveryLoadResult> {
  let raw: string | null;
  try {
    raw = storage.getItem(templateRecoveryKey(current.templateId));
  } catch {
    return { state: 'unavailable', operation: 'read' };
  }
  if (raw === null) return { state: 'absent' };

  let candidate: Record<string, unknown>;
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!isRecord(parsed)) throw new Error('not an object');
    candidate = parsed;
  } catch {
    return { state: 'invalid', reason: 'MALFORMED_ENVELOPE' };
  }
  const exportCanonical = safeDraft(candidate.draftCanonical);
  const invalid = (reason: TemplateRecoveryInvalidReason): TemplateRecoveryLoadResult => ({
    state: 'invalid',
    reason,
    ...(exportCanonical === undefined ? {} : { exportCanonical }),
  });

  const hasUnknownAttempt = Object.hasOwn(candidate, 'unknownAttempt');
  if (!exactKeys(candidate, [
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
    ...(hasUnknownAttempt ? ['unknownAttempt'] : []),
  ])) return invalid('MALFORMED_ENVELOPE');
  if (candidate.recordVersion !== LOCAL_RECOVERY_VERSION) {
    return invalid('UNSUPPORTED_VERSION');
  }
  if (candidate.templateId !== current.templateId) return invalid('TEMPLATE_ID_MISMATCH');

  const staticSchema = candidate.staticSchema;
  if (!isRecord(staticSchema)
    || !exactKeys(staticSchema, ['schemaKey', 'versionTag'])
    || typeof staticSchema.schemaKey !== 'string'
    || typeof staticSchema.versionTag !== 'string'
    || staticSchema.schemaKey !== current.staticSchema.schemaKey
    || staticSchema.versionTag !== current.staticSchema.versionTag) {
    return invalid('STATIC_SCHEMA_MISMATCH');
  }
  if (!validRevision(candidate.baseRevision)
    || typeof candidate.baseContentHash !== 'string'
    || !CONTENT_HASH_PATTERN.test(candidate.baseContentHash)) {
    return invalid('BASE_REVISION_INVALID');
  }
  if (typeof candidate.updatedAt !== 'string' || !validIsoInstant(candidate.updatedAt)) {
    return invalid('UPDATED_AT_INVALID');
  }
  const updatedMillis = Date.parse(candidate.updatedAt);
  if (!Number.isFinite(nowMillis) || nowMillis < 0) return invalid('UPDATED_AT_INVALID');
  if (nowMillis > updatedMillis + LOCAL_RECOVERY_TTL_MS) {
    const cleared = clearTemplateRecovery(storage, current.templateId);
    return cleared.state === 'cleared'
      ? { state: 'expired' }
      : { state: 'unavailable', operation: 'clear' };
  }
  if (typeof candidate.draftCanonical !== 'string'
    || textEncoder.encode(candidate.draftCanonical).byteLength > MAX_CANONICAL_BYTES) {
    return invalid('DRAFT_TOO_LARGE');
  }
  if (typeof candidate.draftContentHash !== 'string'
    || !CONTENT_HASH_PATTERN.test(candidate.draftContentHash)) {
    return invalid('DRAFT_HASH_MISMATCH');
  }
  if (!Number.isSafeInteger(candidate.previewGeneration)
    || (candidate.previewGeneration as number) < 0) {
    return invalid('MALFORMED_ENVELOPE');
  }
  if (!isRecord(candidate.editState) || !validEditState(candidate.editState)) {
    return invalid('EDIT_STATE_INVALID');
  }

  const parsedDraft = parseCanonicalRecord(candidate.draftCanonical);
  if (parsedDraft === null) return invalid('DRAFT_NOT_CANONICAL');
  const compatibilityProbe = createStructuredRecoveryProbe({
    ...current,
    canonicalDesignDsl: candidate.draftCanonical,
    contentHash: candidate.draftContentHash,
    designDsl: parsedDraft,
  }, { state: 'checking' });
  if (compatibilityProbe === null) return invalid('DRAFT_UNSUPPORTED');
  if (await templateContentHashOf(candidate.draftCanonical) !== candidate.draftContentHash) {
    return invalid('DRAFT_HASH_MISMATCH');
  }

  const baseComparison = compareRevisions(candidate.baseRevision, current.revision);
  if (baseComparison === null) return invalid('BASE_REVISION_INVALID');
  if (baseComparison > 0) return invalid('BASE_REVISION_AHEAD');
  if (baseComparison === 0 && candidate.baseContentHash !== current.contentHash) {
    return invalid('BASE_CONTENT_MISMATCH');
  }

  const baseRecord: TemplateRecoveryRecord = Object.freeze({
    recordVersion: LOCAL_RECOVERY_VERSION,
    templateId: candidate.templateId,
    staticSchema: Object.freeze({
      schemaKey: staticSchema.schemaKey,
      versionTag: staticSchema.versionTag,
    }),
    baseRevision: candidate.baseRevision,
    baseContentHash: candidate.baseContentHash,
    draftCanonical: candidate.draftCanonical,
    draftContentHash: candidate.draftContentHash,
    previewGeneration: candidate.previewGeneration as number,
    editState: Object.freeze({
      entry: candidate.editState.entry as TemplateRecoveryEntry,
      selectedNodeId: candidate.editState.selectedNodeId as string,
      navigatorOpen: candidate.editState.navigatorOpen as boolean,
      inspectorOpen: candidate.editState.inspectorOpen as boolean,
    }),
    updatedAt: candidate.updatedAt,
  });
  if (hasUnknownAttempt && !(await unknownAttemptMatchesRecord(
    candidate.unknownAttempt,
    baseRecord,
  ))) return invalid('UNKNOWN_ATTEMPT_MISMATCH');
  const record: TemplateRecoveryRecord = hasUnknownAttempt
    ? Object.freeze({
      ...baseRecord,
      unknownAttempt: immutableAttempt(candidate.unknownAttempt as TemplateUnknownSaveAttempt),
    })
    : baseRecord;

  if (!record.unknownAttempt
    && record.draftContentHash === current.contentHash
    && record.draftCanonical === current.canonicalDesignDsl) {
    const cleared = clearTemplateRecovery(storage, current.templateId);
    return cleared.state === 'cleared'
      ? { state: 'obsolete' }
      : { state: 'unavailable', operation: 'clear' };
  }
  return {
    state: 'available',
    baseState: baseComparison === 0 ? 'matching' : 'drifted',
    record,
  };
}

export function restoreStructuredSessionFromRecovery(
  current: StructuredEditorSession,
  record: TemplateRecoveryRecord,
): TemplateRecoveryRestoreResult {
  if (isCanonicalDirty(current)) {
    return { state: 'invalid', reason: 'CURRENT_SESSION_DIRTY' };
  }
  const designDsl = parseCanonicalRecord(record.draftCanonical);
  if (designDsl === null) return { state: 'invalid', reason: 'DRAFT_UNSUPPORTED' };
  const draftProbe = createStructuredRecoveryProbe({
    ...current.baseline,
    canonicalDesignDsl: record.draftCanonical,
    contentHash: record.draftContentHash,
    designDsl,
  }, current.readiness);
  if (draftProbe === null) {
    return { state: 'invalid', reason: 'DRAFT_UNSUPPORTED' };
  }
  const session: StructuredEditorSession = Object.freeze({
    ...current,
    workingCopy: draftProbe.workingCopy,
    history: draftProbe.history,
    previewGeneration: record.previewGeneration,
  });
  if (!isCanonicalDirty(session)) return { state: 'invalid', reason: 'DRAFT_NOT_DIRTY' };
  return { state: 'restored', session };
}

function createStructuredRecoveryProbe(
  baseline: CanonicalTemplateBaseline,
  readiness: EditorReadiness,
): StructuredEditorSession | null {
  try {
    const probe = createSessionFromBaseline(baseline, readiness);
    return probe.mode === 'structured' ? probe : null;
  } catch (error) {
    if (error instanceof MalformedDesignDslWireError) return null;
    throw error;
  }
}

export function recoveryOverwriteOffer(
  session: StructuredEditorSession,
  _record: TemplateRecoveryRecord,
  baseState: 'matching' | 'drifted',
): TemplateConflictOffer | null {
  if (baseState !== 'drifted' || !isCanonicalDirty(session)) return null;
  return Object.freeze({
    offeredRevision: session.baseline.revision,
    draftCanonical: session.workingCopy.canonicalDesignDsl,
    previewGeneration: session.previewGeneration,
  });
}

function parseCanonicalRecord(canonical: string): Record<string, unknown> | null {
  try {
    const value = parse(canonical) as unknown;
    if (!isRecord(value)) return null;
    return canonicalStringifyWorkingValue(value) === canonical ? value : null;
  } catch {
    return null;
  }
}

async function unknownAttemptMatchesRecord(
  value: unknown,
  record: TemplateRecoveryRecord,
): Promise<boolean> {
  if (!isRecord(value)) return false;
  const hasConfirmation = Object.hasOwn(value, 'confirmation');
  if (!exactKeys(value, [
    'expectedCurrent',
    'expectedRevision',
    'draftCanonical',
    'proposedContentHash',
    'previewGeneration',
    'requiredReadiness',
    ...(hasConfirmation ? ['confirmation'] : []),
  ])) return false;
  const expected = value.expectedCurrent;
  if (!isRecord(expected)
    || !exactKeys(expected, [
      'templateId', 'revision', 'staticSchema', 'contentHash', 'canonicalDesignDsl',
    ])) return false;
  const schema = expected.staticSchema;
  if (!isRecord(schema)
    || !exactKeys(schema, ['schemaKey', 'versionTag'])
    || schema.schemaKey !== record.staticSchema.schemaKey
    || schema.versionTag !== record.staticSchema.versionTag) return false;
  if (expected.templateId !== record.templateId
    || expected.revision !== record.baseRevision
    || expected.contentHash !== record.baseContentHash
    || value.expectedRevision !== expected.revision
    || value.draftCanonical !== record.draftCanonical
    || value.proposedContentHash !== record.draftContentHash
    || value.previewGeneration !== record.previewGeneration
    || (value.requiredReadiness !== 'READY' && value.requiredReadiness !== 'INVALID')
    || (value.requiredReadiness === 'INVALID') !== hasConfirmation
    || typeof expected.canonicalDesignDsl !== 'string') return false;

  if (hasConfirmation) {
    const confirmation = value.confirmation;
    if (!isRecord(confirmation)
      || !exactKeys(confirmation, ['token', 'expiresAt'])
      || typeof confirmation.token !== 'string'
      || !CONFIRMATION_TOKEN_PATTERN.test(confirmation.token)
      || typeof confirmation.expiresAt !== 'string'
      || !validIsoInstant(confirmation.expiresAt)) return false;
  }
  try {
    return await templateContentHashOf(expected.canonicalDesignDsl) === expected.contentHash
      && await templateContentHashOf(record.draftCanonical) === record.draftContentHash;
  } catch {
    return false;
  }
}

function immutableAttempt(attempt: TemplateUnknownSaveAttempt): TemplateUnknownSaveAttempt {
  return Object.freeze({
    expectedCurrent: Object.freeze({
      ...attempt.expectedCurrent,
      staticSchema: Object.freeze({ ...attempt.expectedCurrent.staticSchema }),
    }),
    expectedRevision: attempt.expectedRevision,
    draftCanonical: attempt.draftCanonical,
    proposedContentHash: attempt.proposedContentHash,
    previewGeneration: attempt.previewGeneration,
    requiredReadiness: attempt.requiredReadiness,
    ...(attempt.confirmation ? {
      confirmation: Object.freeze({ ...attempt.confirmation }),
    } : {}),
  });
}

function validEditState(value: Record<string, unknown> | TemplateRecoveryEditState): boolean {
  return exactKeys(value as Record<string, unknown>, [
    'entry', 'selectedNodeId', 'navigatorOpen', 'inspectorOpen',
  ])
    && typeof value.entry === 'string'
    && ENTRY_VALUES.has(value.entry as TemplateRecoveryEntry)
    && typeof value.selectedNodeId === 'string'
    && value.selectedNodeId.length <= 1024
    && typeof value.navigatorOpen === 'boolean'
    && typeof value.inspectorOpen === 'boolean';
}

function validIsoInstant(value: string): boolean {
  const millis = Date.parse(value);
  return Number.isFinite(millis) && new Date(millis).toISOString() === value;
}

function validRevision(value: unknown): value is string {
  if (typeof value !== 'string' || !REVISION_PATTERN.test(value)) return false;
  try {
    return BigInt(value) <= MAX_REVISION;
  } catch {
    return false;
  }
}

function compareRevisions(left: string, right: string): number | null {
  if (!validRevision(left) || !validRevision(right)) return null;
  const leftValue = BigInt(left);
  const rightValue = BigInt(right);
  return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}

function safeDraft(value: unknown): string | undefined {
  return typeof value === 'string'
    && textEncoder.encode(value).byteLength <= MAX_CANONICAL_BYTES
    ? value
    : undefined;
}

function exactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const sortedExpected = [...expected].sort();
  return actual.length === sortedExpected.length
    && actual.every((key, index) => key === sortedExpected[index]);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
