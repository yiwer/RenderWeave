import {
  getStaticSnapshotRequest,
  type StaticSnapshot,
} from '../schema-studio/lossless-api';
import type { PersistedValue } from '../schema-studio/editor-types';
import type { StaticSchemaIdentity } from './template-editor-model';

export class TemplateStaticSchemaIntegrityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TemplateStaticSchemaIntegrityError';
  }
}

export interface TemplateStaticSchemaSession {
  readonly baseline: {
    readonly staticSchema: StaticSchemaIdentity;
  };
}

export interface TemplateStaticSchemaTransport {
  getStaticSchema(
    identity: StaticSchemaIdentity,
    signal?: AbortSignal,
  ): Promise<StaticSnapshot>;
}

export interface TemplateStaticSchemaPendingReference {
  readonly identity: StaticSchemaIdentity;
  readonly label: string;
}

export const defaultTemplateStaticSchemaTransport: TemplateStaticSchemaTransport = {
  getStaticSchema(identity, signal) {
    return getStaticSnapshotRequest(identity.schemaKey, identity.versionTag, signal);
  },
};

export async function loadTemplateStaticSchema(
  session: TemplateStaticSchemaSession,
  transport: TemplateStaticSchemaTransport = defaultTemplateStaticSchemaTransport,
  signal?: AbortSignal,
): Promise<StaticSnapshot> {
  const expected = session.baseline.staticSchema;
  if (!isNonBlankString(expected.schemaKey) || !isNonBlankString(expected.versionTag)) {
    throw new TemplateStaticSchemaIntegrityError(
      'Permanent Template StaticSchema identity is malformed',
    );
  }
  return loadExactTemplateStaticSchema(expected, transport, signal);
}

/** Loads any exact immutable StaticSchema identity without weakening response checks. */
export async function loadExactTemplateStaticSchema(
  expected: StaticSchemaIdentity,
  transport: TemplateStaticSchemaTransport = defaultTemplateStaticSchemaTransport,
  signal?: AbortSignal,
): Promise<StaticSnapshot> {
  if (!isNonBlankString(expected.schemaKey) || !isNonBlankString(expected.versionTag)) {
    throw new TemplateStaticSchemaIntegrityError(
      'Exact StaticSchema identity is malformed',
    );
  }
  return fetchExactTemplateStaticSchema(expected, transport, signal);
}

/**
 * Loads only reference boundaries crossed by the selected schema-field branches.
 * A branch uses RFC 6901 field segments but deliberately crosses Reference and
 * Array<Reference> boundaries without a document array index; this models the
 * exact schema proof needed by an invocation or Repeat item context.
 */
export async function loadTemplateStaticSchemaBranches(
  root: StaticSnapshot,
  branchPointers: readonly string[],
  transport: TemplateStaticSchemaTransport = defaultTemplateStaticSchemaTransport,
  signal?: AbortSignal,
): Promise<readonly StaticSnapshot[]> {
  assertSnapshotExactReferences(root);
  const resolved = new Map<string, StaticSnapshot>([[staticSchemaIdentityKey(root), root]]);
  const ordered: StaticSnapshot[] = [root];
  for (const pointer of new Set(branchPointers)) {
    signal?.throwIfAborted();
    const segments = decodeSchemaBranchPointer(pointer);
    let current = root;
    for (let index = 0; index < segments.length - 1; index += 1) {
      signal?.throwIfAborted();
      const fieldKey = segments[index];
      const fieldIndex = current.definition.fields.findIndex((field) => (
        field.fieldKey === fieldKey
      ));
      if (fieldIndex < 0) break;
      const field = current.definition.fields[fieldIndex];
      if (!field) break;
      const identity = traversedReferenceIdentity(
        field.value,
        `/fields/${fieldIndex}/value`,
      );
      if (!identity) break;
      const key = staticSchemaIdentityKey(identity);
      const cached = resolved.get(key);
      if (cached) {
        current = cached;
        continue;
      }
      const loaded = await fetchExactTemplateStaticSchema(identity, transport, signal);
      signal?.throwIfAborted();
      resolved.set(key, loaded);
      ordered.push(loaded);
      current = loaded;
    }
  }
  return Object.freeze(ordered);
}

/**
 * Resolves the immutable StaticSchema graph used to prove nested context paths.
 * The root is already loaded by the Template baseline boundary; every referenced
 * identity is fetched exactly once and checked through the same integrity seam.
 */
export async function loadTemplateStaticSchemaClosure(
  root: StaticSnapshot,
  transport: TemplateStaticSchemaTransport = defaultTemplateStaticSchemaTransport,
  signal?: AbortSignal,
): Promise<readonly StaticSnapshot[]> {
  root.definition.fields.forEach((field, index) => {
    assertExactStaticReference(field.value, `/fields/${index}/value`);
  });
  const resolved: StaticSnapshot[] = [root];
  const seen = new Set<string>([staticSchemaIdentityKey(root)]);
  const queued: StaticSchemaIdentity[] = [];
  const enqueue = (identity: StaticSchemaIdentity) => {
    const key = staticSchemaIdentityKey(identity);
    if (seen.has(key)) return;
    seen.add(key);
    queued.push(identity);
  };
  exactReferenceIdentities(root).forEach(enqueue);

  for (let index = 0; index < queued.length; index += 1) {
    if (signal?.aborted) throw signal.reason;
    const identity = queued[index];
    if (!identity) continue;
    const snapshot = await loadExactTemplateStaticSchema(identity, transport, signal);
    resolved.push(snapshot);
    exactReferenceIdentities(snapshot).forEach(enqueue);
  }
  return Object.freeze(resolved);
}

export function hasTemplateStaticSchemaReferences(snapshot: StaticSnapshot): boolean {
  return exactReferenceIdentities(snapshot).length > 0;
}

/**
 * Projects only the next exact reference boundaries reachable from schemas the
 * editor has already loaded. Loading one option never walks the transitive graph.
 */
export function projectPendingTemplateStaticSchemaReferences(
  snapshots: readonly StaticSnapshot[],
): readonly TemplateStaticSchemaPendingReference[] {
  const loaded = new Set(snapshots.map(staticSchemaIdentityKey));
  const projected = new Map<string, TemplateStaticSchemaPendingReference>();
  for (const snapshot of snapshots) {
    for (const field of snapshot.definition.fields) {
      const identity = exactReferenceIdentity(field.value);
      if (!identity) continue;
      const key = staticSchemaIdentityKey(identity);
      if (loaded.has(key) || projected.has(key)) continue;
      projected.set(key, Object.freeze({
        identity,
        label: field.displayName?.trim() || field.fieldKey,
      }));
    }
  }
  return Object.freeze([...projected.values()]);
}

function exactReferenceIdentities(snapshot: StaticSnapshot): StaticSchemaIdentity[] {
  const identities: StaticSchemaIdentity[] = [];
  const collect = (value: PersistedValue) => {
    if (value.type === 'array') {
      collect(value.items);
      return;
    }
    if (value.type === 'reference' && value.ref.versionTag) {
      identities.push(Object.freeze({
        schemaKey: value.ref.schemaKey,
        versionTag: value.ref.versionTag,
      }));
    }
  };
  snapshot.definition.fields.forEach((field) => collect(field.value));
  return identities;
}

function exactReferenceIdentity(value: PersistedValue): StaticSchemaIdentity | null {
  if (value.type === 'array') return exactReferenceIdentity(value.items);
  return value.type === 'reference' && value.ref.versionTag
    ? Object.freeze({ schemaKey: value.ref.schemaKey, versionTag: value.ref.versionTag })
    : null;
}

function traversedReferenceIdentity(
  value: PersistedValue,
  path: string,
): StaticSchemaIdentity | null {
  if (value.type === 'array') {
    return traversedReferenceIdentity(value.items, `${path}/items`);
  }
  if (value.type !== 'reference') return null;
  assertExactStaticReference(value, path);
  return Object.freeze({
    schemaKey: value.ref.schemaKey,
    versionTag: value.ref.versionTag as string,
  });
}

function decodeSchemaBranchPointer(pointer: string): string[] {
  if (pointer === '/') return [];
  if (!pointer.startsWith('/') || pointer.length === 0) {
    throw new TemplateStaticSchemaIntegrityError('StaticSchema branch is not an RFC 6901 pointer');
  }
  const encoded = pointer.slice(1).split('/');
  if (encoded.length > 64 || encoded.some((segment) => /~(?![01])/.test(segment))) {
    throw new TemplateStaticSchemaIntegrityError('StaticSchema branch is not an RFC 6901 pointer');
  }
  return encoded.map((segment) => segment.replaceAll('~1', '/').replaceAll('~0', '~'));
}

async function fetchExactTemplateStaticSchema(
  expected: StaticSchemaIdentity,
  transport: TemplateStaticSchemaTransport,
  signal?: AbortSignal,
): Promise<StaticSnapshot> {
  signal?.throwIfAborted();
  const snapshot = await transport.getStaticSchema(expected, signal);
  signal?.throwIfAborted();
  if (snapshot.schemaKey !== expected.schemaKey || snapshot.versionTag !== expected.versionTag) {
    throw new TemplateStaticSchemaIntegrityError(
      'StaticSchema response identity does not match the permanent Template baseline',
    );
  }
  assertSnapshotExactReferences(snapshot);
  return snapshot;
}

function assertSnapshotExactReferences(snapshot: StaticSnapshot): void {
  snapshot.definition.fields.forEach((field, index) => {
    assertExactStaticReference(field.value, `/fields/${index}/value`);
  });
}

function staticSchemaIdentityKey(identity: StaticSchemaIdentity): string {
  return `${identity.schemaKey.length}:${identity.schemaKey}${identity.versionTag.length}:${identity.versionTag}`;
}

function assertExactStaticReference(value: PersistedValue, path: string): void {
  if (value.type === 'array') {
    assertExactStaticReference(value.items, `${path}/items`);
    return;
  }
  if (
    value.type === 'reference'
    && (
      !value.ref.schemaKey.trim()
      || value.ref.versionTag === undefined
      || !value.ref.versionTag.trim()
    )
  ) {
    throw new TemplateStaticSchemaIntegrityError(
      `StaticSchema reference at ${path} is not an exact immutable identity`,
    );
  }
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
