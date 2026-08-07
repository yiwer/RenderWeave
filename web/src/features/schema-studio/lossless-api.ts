import { isLosslessNumber, parse } from 'lossless-json';

import type { Problem } from '../../api/generated';
import type {
  DraftSnapshot,
  PersistedArrayValue,
  PersistedDefinition,
  PersistedField,
  PersistedScalarValue,
  PersistedValue,
} from './editor-types';

export class StudioRequestError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title);
    this.name = 'StudioRequestError';
    this.problem = problem;
  }
}

export async function getDraftSnapshotRequest(schemaKey: string): Promise<DraftSnapshot> {
  return parseDraftSnapshot(await studioRequestText(`/api/v1/schema-drafts/${encodeURIComponent(schemaKey)}`));
}

export async function createDraftSnapshotRequest(
  schemaKey: string,
  definitionJson: string,
): Promise<DraftSnapshot> {
  const body = `{"schemaKey":${JSON.stringify(schemaKey)},"definition":${definitionJson}}`;
  return parseDraftSnapshot(await studioRequestText('/api/v1/schema-drafts', { method: 'POST', body }));
}

export async function saveDraftSnapshotRequest(
  schemaKey: string,
  expectedRevision: number,
  definitionJson: string,
): Promise<DraftSnapshot> {
  const body = `{"expectedRevision":${expectedRevision},"definition":${definitionJson}}`;
  return parseDraftSnapshot(await studioRequestText(`/api/v1/schema-drafts/${encodeURIComponent(schemaKey)}`, {
    method: 'PUT',
    body,
  }));
}

export interface DraftRevisionSnapshot {
  schemaKey: string;
  revision: number;
  definition: PersistedDefinition;
  savedAt: string;
}

export interface StaticSnapshot {
  schemaKey: string;
  versionTag: string;
  origin: 'DRAFT' | 'SYSTEM';
  sourceDraftRevision: number | null;
  definition: PersistedDefinition;
  compilerVersion: string;
  releaseNote: string | null;
  referenceDepth: number;
  publishedAt: string;
}

export async function getDraftRevisionSnapshotRequest(
  schemaKey: string,
  revision: number,
): Promise<DraftRevisionSnapshot> {
  const json = await studioRequestText(`/api/v1/schema-drafts/${encodeURIComponent(schemaKey)}/revisions/${revision}`);
  const root = record(parse(json), 'Draft revision response');
  return {
    schemaKey: string(root.schemaKey, 'schemaKey'),
    revision: safeInteger(root.revision, 'revision'),
    definition: parsePersistedDefinitionValue(root.definition),
    savedAt: string(root.savedAt, 'savedAt'),
  };
}

export async function restoreDraftSnapshotRequest(
  schemaKey: string,
  expectedRevision: number,
  sourceRevision: number,
): Promise<DraftSnapshot> {
  const body = JSON.stringify({ expectedRevision, sourceRevision });
  return parseDraftSnapshot(await studioRequestText(
    `/api/v1/schema-drafts/${encodeURIComponent(schemaKey)}/restore`,
    { method: 'POST', body },
  ));
}

export async function copyDraftSnapshotRequest(
  sourceSchemaKey: string,
  schemaKey: string,
  displayName: string,
): Promise<DraftSnapshot> {
  return parseDraftSnapshot(await studioRequestText(
    `/api/v1/schema-drafts/${encodeURIComponent(sourceSchemaKey)}/copies`,
    { method: 'POST', body: JSON.stringify({ schemaKey, displayName }) },
  ));
}

export async function deleteDraftRequest(schemaKey: string, expectedRevision: number): Promise<void> {
  await studioRequestText(
    `/api/v1/schema-drafts/${encodeURIComponent(schemaKey)}?expectedRevision=${expectedRevision}`,
    { method: 'DELETE' },
  );
}

export async function publishStaticSnapshotRequest(
  schemaKey: string,
  expectedRevision: number,
  versionTag: string,
  releaseNote: string,
): Promise<StaticSnapshot> {
  const body = JSON.stringify({
    schemaKey,
    expectedRevision,
    versionTag,
    ...(releaseNote.trim() ? { releaseNote } : {}),
  });
  return parseStaticSnapshot(await studioRequestText('/api/v1/static-schemas', { method: 'POST', body }));
}

export async function getStaticSnapshotRequest(schemaKey: string, versionTag: string): Promise<StaticSnapshot> {
  return parseStaticSnapshot(await studioRequestText(
    `/api/v1/static-schemas/${encodeURIComponent(schemaKey)}/${encodeURIComponent(versionTag)}`,
  ));
}

export async function getStaticArtifactRequest(
  schemaKey: string,
  versionTag: string,
  artifact: 'definition' | 'compiled-json-schema',
): Promise<string> {
  return studioRequestText(
    `/api/v1/static-schemas/${encodeURIComponent(schemaKey)}/${encodeURIComponent(versionTag)}/${artifact}`,
  );
}

export async function copyStaticToDraftRequest(
  sourceSchemaKey: string,
  versionTag: string,
  schemaKey: string,
  displayName: string,
): Promise<DraftSnapshot> {
  return parseDraftSnapshot(await studioRequestText(
    `/api/v1/static-schemas/${encodeURIComponent(sourceSchemaKey)}/${encodeURIComponent(versionTag)}/copies`,
    { method: 'POST', body: JSON.stringify({ schemaKey, displayName }) },
  ));
}

export function parseDraftSnapshot(json: string): DraftSnapshot {
  const root = record(parse(json), 'Draft response');
  const resolved = record(root.resolvedRevisions, 'resolvedRevisions');
  return {
    schemaKey: string(root.schemaKey, 'schemaKey'),
    revision: safeInteger(root.revision, 'revision'),
    definition: parsePersistedDefinitionValue(root.definition),
    creationSource: string(root.creationSource, 'creationSource'),
    createdAt: string(root.createdAt, 'createdAt'),
    updatedAt: string(root.updatedAt, 'updatedAt'),
    savedAt: string(root.savedAt, 'savedAt'),
    resolvedRevisions: Object.fromEntries(Object.entries(resolved).map(([key, value]) => [
      key,
      safeInteger(value, `resolvedRevisions.${key}`),
    ])),
  };
}

export function parseStaticSnapshot(json: string): StaticSnapshot {
  const root = record(parse(json), 'StaticSchema response');
  const origin = string(root.origin, 'origin');
  if (origin !== 'DRAFT' && origin !== 'SYSTEM') throw new Error('Unexpected StaticSchema origin');
  const sourceDraftRevision = root.sourceDraftRevision === null
    ? null
    : safeInteger(root.sourceDraftRevision, 'sourceDraftRevision');
  return {
    schemaKey: string(root.schemaKey, 'schemaKey'),
    versionTag: string(root.versionTag, 'versionTag'),
    origin,
    sourceDraftRevision,
    definition: parsePersistedDefinitionValue(root.definition),
    compilerVersion: string(root.compilerVersion, 'compilerVersion'),
    releaseNote: root.releaseNote === null ? null : string(root.releaseNote, 'releaseNote'),
    referenceDepth: safeInteger(root.referenceDepth, 'referenceDepth'),
    publishedAt: string(root.publishedAt, 'publishedAt'),
  };
}

export function parsePersistedDefinition(json: string): PersistedDefinition {
  return parsePersistedDefinitionValue(parse(json));
}

function parsePersistedDefinitionValue(value: unknown): PersistedDefinition {
  const definition = record(value, 'definition');
  const fields = array(definition.fields, 'definition.fields').map(parseField);
  const description = optionalString(definition.description, 'definition.description');
  return {
    dslVersion: literal(definition.dslVersion, 'renderweave-schema/1.0', 'definition.dslVersion'),
    displayName: string(definition.displayName, 'definition.displayName'),
    ...(description === undefined ? {} : { description }),
    fields,
  };
}

function parseField(value: unknown, index: number): PersistedField {
  const field = record(value, `definition.fields.${index}`);
  const displayName = optionalString(field.displayName, `definition.fields.${index}.displayName`);
  const description = optionalString(field.description, `definition.fields.${index}.description`);
  return {
    fieldKey: string(field.fieldKey, `definition.fields.${index}.fieldKey`),
    ...(displayName === undefined ? {} : { displayName }),
    ...(description === undefined ? {} : { description }),
    required: boolean(field.required, `definition.fields.${index}.required`),
    value: parseValue(field.value, `definition.fields.${index}.value`, false),
  };
}

function parseValue(value: unknown, path: string, arrayItem: boolean): PersistedValue {
  const descriptor = record(value, path);
  const type = string(descriptor.type, `${path}.type`);
  switch (type) {
    case 'text': {
      const constraints = parseTextConstraints(descriptor.constraints, `${path}.constraints`);
      return { type, ...(constraints ? { constraints } : {}) };
    }
    case 'decimal': {
      const constraints = parseDecimalConstraints(descriptor.constraints, `${path}.constraints`);
      return { type, ...(constraints ? { constraints } : {}) };
    }
    case 'date':
    case 'time': {
      const constraints = parseOrderedConstraints(descriptor.constraints, `${path}.constraints`);
      return { type, ...(constraints ? { constraints } : {}) };
    }
    case 'boolean': {
      if (descriptor.constraints === undefined) return { type };
      const constraints = record(descriptor.constraints, `${path}.constraints`);
      return { type, constraints: { const: boolean(constraints.const, `${path}.constraints.const`) } };
    }
    case 'reference': {
      const ref = record(descriptor.ref, `${path}.ref`);
      const versionTag = optionalString(ref.versionTag, `${path}.ref.versionTag`);
      return {
        type,
        ref: {
          schemaKey: string(ref.schemaKey, `${path}.ref.schemaKey`),
          ...(versionTag === undefined ? {} : { versionTag }),
        },
      };
    }
    case 'array': {
      if (arrayItem) throw new Error('Stored definition contains a nested array');
      const constraints = parseArrayConstraints(descriptor.constraints, `${path}.constraints`);
      const items = parseValue(descriptor.items, `${path}.items`, true);
      if (items.type === 'array') throw new Error('Stored definition contains a nested array');
      return {
        type,
        ...(constraints ? { constraints } : {}),
        items: items as PersistedScalarValue,
      } satisfies PersistedArrayValue;
    }
    default:
      throw new Error(`Unsupported persisted value type at ${path}`);
  }
}

function parseTextConstraints(value: unknown, path: string) {
  if (value === undefined) return undefined;
  const constraints = record(value, path);
  return defined({
    minLength: optionalInteger(constraints.minLength, `${path}.minLength`),
    maxLength: optionalInteger(constraints.maxLength, `${path}.maxLength`),
    pattern: optionalString(constraints.pattern, `${path}.pattern`),
    enum: optionalArray(constraints.enum, `${path}.enum`, (entry, entryPath) => string(entry, entryPath)),
    const: optionalString(constraints.const, `${path}.const`),
  });
}

function parseDecimalConstraints(value: unknown, path: string) {
  if (value === undefined) return undefined;
  const constraints = record(value, path);
  return defined({
    min: optionalNumberToken(constraints.min, `${path}.min`),
    exclusiveMin: optionalNumberToken(constraints.exclusiveMin, `${path}.exclusiveMin`),
    max: optionalNumberToken(constraints.max, `${path}.max`),
    exclusiveMax: optionalNumberToken(constraints.exclusiveMax, `${path}.exclusiveMax`),
    multipleOf: optionalNumberToken(constraints.multipleOf, `${path}.multipleOf`),
    enum: optionalArray(constraints.enum, `${path}.enum`, numberToken),
    const: optionalNumberToken(constraints.const, `${path}.const`),
  });
}

function parseOrderedConstraints(value: unknown, path: string) {
  if (value === undefined) return undefined;
  const constraints = record(value, path);
  return defined({
    min: optionalString(constraints.min, `${path}.min`),
    exclusiveMin: optionalString(constraints.exclusiveMin, `${path}.exclusiveMin`),
    max: optionalString(constraints.max, `${path}.max`),
    exclusiveMax: optionalString(constraints.exclusiveMax, `${path}.exclusiveMax`),
    enum: optionalArray(constraints.enum, `${path}.enum`, (entry, entryPath) => string(entry, entryPath)),
    const: optionalString(constraints.const, `${path}.const`),
  });
}

function parseArrayConstraints(value: unknown, path: string) {
  if (value === undefined) return undefined;
  const constraints = record(value, path);
  return defined({
    minItems: optionalInteger(constraints.minItems, `${path}.minItems`),
    maxItems: optionalInteger(constraints.maxItems, `${path}.maxItems`),
    uniqueItems: optionalBoolean(constraints.uniqueItems, `${path}.uniqueItems`),
  });
}

export async function studioRequestText(path: string, init: RequestInit = {}): Promise<string> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...init.headers },
  });
  const text = await response.text();
  if (!response.ok) {
    let problem: Problem;
    try {
      problem = JSON.parse(text) as Problem;
    } catch {
      problem = {
        type: 'about:blank', title: 'Request failed', status: response.status,
        code: 'UNEXPECTED_RESPONSE', traceId: 'unavailable', detail: text || response.statusText,
      };
    }
    throw new StudioRequestError(problem);
  }
  return text;
}

function numberToken(value: unknown, path: string): string {
  if (isLosslessNumber(value)) return value.toString();
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  throw new Error(`Expected JSON number at ${path}`);
}

function optionalNumberToken(value: unknown, path: string): string | undefined {
  return value === undefined ? undefined : numberToken(value, path);
}

function safeInteger(value: unknown, path: string): number {
  const token = numberToken(value, path);
  const parsed = Number(token);
  if (!Number.isSafeInteger(parsed) || parsed < 0) throw new Error(`Expected non-negative safe integer at ${path}`);
  return parsed;
}

function optionalInteger(value: unknown, path: string): number | undefined {
  return value === undefined ? undefined : safeInteger(value, path);
}

function string(value: unknown, path: string): string {
  if (typeof value !== 'string') throw new Error(`Expected string at ${path}`);
  return value;
}

function optionalString(value: unknown, path: string): string | undefined {
  return value === undefined ? undefined : string(value, path);
}

function boolean(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') throw new Error(`Expected boolean at ${path}`);
  return value;
}

function optionalBoolean(value: unknown, path: string): boolean | undefined {
  return value === undefined ? undefined : boolean(value, path);
}

function literal<T extends string>(value: unknown, expected: T, path: string): T {
  if (value !== expected) throw new Error(`Expected ${expected} at ${path}`);
  return expected;
}

function record(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`Expected object at ${path}`);
  }
  return value as Record<string, unknown>;
}

function array(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) throw new Error(`Expected array at ${path}`);
  return value;
}

function optionalArray<T>(
  value: unknown,
  path: string,
  parseEntry: (value: unknown, path: string) => T,
): T[] | undefined {
  return value === undefined
    ? undefined
    : array(value, path).map((entry, index) => parseEntry(entry, `${path}.${index}`));
}

function defined<T extends Record<string, unknown>>(value: T): Partial<T> {
  return Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== undefined)) as Partial<T>;
}
