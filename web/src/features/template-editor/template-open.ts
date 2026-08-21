import { isLosslessNumber, parse, stringify } from 'lossless-json';

import {
  createSessionFromBaseline,
  type CanonicalTemplateBaseline,
  type CheckedTemplateReadiness,
  type CompatibilityEditorSession,
  type PersistedTemplateReadiness,
  type StructuredEditorSession,
} from './template-editor-model';

const CONTENT_HASH_DOMAIN = 'renderweave-design-content/1\0';
const MAX_OPEN_ATTEMPTS = 3;
const CONTENT_HASH_PATTERN = /^sha256:[0-9a-f]{64}$/;

export interface TemplateEditorTransport {
  getCurrent(templateId: string, signal?: AbortSignal): Promise<string>;
  recheckCurrent(templateId: string, signal?: AbortSignal): Promise<string>;
}

export interface ReadinessRecheckIdentity {
  templateId: string;
  revision: string;
  contentHash: string;
  readiness: CheckedTemplateReadiness;
}

export class TemplateRequestError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string) {
    super(`Template request failed (${code})`);
    this.name = 'TemplateRequestError';
    this.status = status;
    this.code = code;
  }
}

export class TemplateIntegrityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TemplateIntegrityError';
  }
}

export class TemplateCurrentDriftError extends Error {
  constructor() {
    super('Template current changed repeatedly while opening the editor');
    this.name = 'TemplateCurrentDriftError';
  }
}

export const defaultTemplateEditorTransport: TemplateEditorTransport = {
  getCurrent(templateId, signal) {
    return requestText(`/api/v1/templates/${encodeURIComponent(templateId)}`, { signal });
  },
  recheckCurrent(templateId, signal) {
    return requestText(
      `/api/v1/templates/${encodeURIComponent(templateId)}/readiness-recheck`,
      { method: 'POST', signal },
    );
  },
};

export async function parseTemplateCurrentResponse(
  json: string,
): Promise<CanonicalTemplateBaseline> {
  const root = parseRecord(json, 'Template current response');
  exactKeys(root, [
    'templateId',
    'disclosure',
    'revision',
    'staticSchema',
    'contentHash',
    'readiness',
    'designDsl',
  ], 'Template current response');
  if (root.disclosure !== 'READABLE') {
    throw new TemplateIntegrityError('Template current disclosure is not READABLE');
  }
  const staticSchema = record(root.staticSchema, 'staticSchema');
  exactKeys(staticSchema, ['schemaKey', 'versionTag'], 'staticSchema');
  const designDsl = record(root.designDsl, 'designDsl');
  const canonicalDesignDsl = stringify(designDsl);
  if (canonicalDesignDsl === undefined) {
    throw new TemplateIntegrityError('Canonical DesignDSL could not be serialized');
  }
  const contentHash = string(root.contentHash, 'contentHash');
  if (!CONTENT_HASH_PATTERN.test(contentHash)) {
    throw new TemplateIntegrityError('Template contentHash has an invalid shape');
  }
  const computedHash = await templateContentHashOf(canonicalDesignDsl);
  if (computedHash !== contentHash) {
    throw new TemplateIntegrityError('Canonical DesignDSL does not match contentHash');
  }
  return {
    templateId: nonBlankString(root.templateId, 'templateId'),
    revision: nonNegativeIntegerToken(root.revision, 'revision'),
    staticSchema: {
      schemaKey: nonBlankString(staticSchema.schemaKey, 'staticSchema.schemaKey'),
      versionTag: nonBlankString(staticSchema.versionTag, 'staticSchema.versionTag'),
    },
    contentHash,
    persistedReadiness: persistedReadiness(root.readiness),
    canonicalDesignDsl,
    designDsl,
  };
}

export function parseReadinessRecheckResponse(json: string): ReadinessRecheckIdentity {
  const root = parseRecord(json, 'Template readiness recheck response');
  exactKeys(
    root,
    ['templateId', 'revision', 'contentHash', 'readiness'],
    'Template readiness recheck response',
  );
  const contentHash = string(root.contentHash, 'contentHash');
  if (!CONTENT_HASH_PATTERN.test(contentHash)) {
    throw new TemplateIntegrityError('Readiness recheck contentHash has an invalid shape');
  }
  const readiness = root.readiness;
  if (readiness !== 'READY' && readiness !== 'INVALID') {
    throw new TemplateIntegrityError('Readiness recheck readiness is not READY or INVALID');
  }
  return {
    templateId: nonBlankString(root.templateId, 'templateId'),
    revision: nonNegativeIntegerToken(root.revision, 'revision'),
    contentHash,
    readiness,
  };
}

export async function openTemplateEditor(
  templateId: string,
  transport: TemplateEditorTransport = defaultTemplateEditorTransport,
  onBaseline?: (baseline: CanonicalTemplateBaseline) => void,
  signal?: AbortSignal,
): Promise<StructuredEditorSession | CompatibilityEditorSession> {
  for (let attempt = 0; attempt < MAX_OPEN_ATTEMPTS; attempt += 1) {
    const baseline = await parseTemplateCurrentResponse(
      await transport.getCurrent(templateId, signal),
    );
    if (baseline.templateId !== templateId) {
      throw new TemplateIntegrityError('Template current identity does not match the request');
    }
    onBaseline?.(baseline);

    let checked: ReadinessRecheckIdentity;
    try {
      checked = parseReadinessRecheckResponse(
        await transport.recheckCurrent(templateId, signal),
      );
    } catch (error) {
      if (error instanceof TemplateRequestError && error.code === 'TEMPLATE_CURRENT_DRIFTED') {
        continue;
      }
      if (isAbort(error)) throw error;
      if (isReadinessUnavailable(error)) {
        return createSessionFromBaseline(baseline, {
          state: 'unavailable',
          message: '权威重检暂不可用；可信 current 仍可只读查看。',
        });
      }
      throw error;
    }

    if (checked.templateId !== baseline.templateId) {
      throw new TemplateIntegrityError('Readiness recheck returned another Template identity');
    }
    if (checked.revision === baseline.revision && checked.contentHash !== baseline.contentHash) {
      throw new TemplateIntegrityError('Readiness recheck changed contentHash within one revision');
    }
    if (
      checked.revision !== baseline.revision
      || checked.contentHash !== baseline.contentHash
    ) {
      continue;
    }
    return createSessionFromBaseline(baseline, {
      state: 'checked',
      value: checked.readiness,
    });
  }
  throw new TemplateCurrentDriftError();
}

async function requestText(path: string, init: RequestInit): Promise<string> {
  const response = await fetch(path, {
    ...init,
    headers: { Accept: 'application/json', ...init.headers },
  });
  const text = await response.text();
  if (!response.ok) {
    let code = 'UNEXPECTED_RESPONSE';
    try {
      const problem = record(JSON.parse(text), 'problem');
      if (typeof problem.code === 'string') code = problem.code;
    } catch {
      // The status remains authoritative when an intermediary returns a non-Problem body.
    }
    throw new TemplateRequestError(response.status, code);
  }
  return text;
}

export async function templateContentHashOf(canonicalDesignDsl: string): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new TemplateIntegrityError('Web Crypto SHA-256 is unavailable');
  }
  const bytes = new TextEncoder().encode(CONTENT_HASH_DOMAIN + canonicalDesignDsl);
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', bytes));
  return `sha256:${Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')}`;
}

function persistedReadiness(value: unknown): PersistedTemplateReadiness {
  if (value === 'READY' || value === 'INVALID' || value === 'STALE') return value;
  throw new TemplateIntegrityError('Template current readiness is outside the closed set');
}

function nonNegativeIntegerToken(value: unknown, path: string): string {
  const token = isLosslessNumber(value)
    ? value.toString()
    : typeof value === 'number' ? String(value) : '';
  if (!/^(0|[1-9][0-9]*)$/.test(token)) {
    throw new TemplateIntegrityError(`${path} is not a non-negative JSON integer`);
  }
  return token;
}

function nonBlankString(value: unknown, path: string): string {
  const parsed = string(value, path);
  if (!parsed.trim()) throw new TemplateIntegrityError(`${path} is blank`);
  return parsed;
}

function string(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw new TemplateIntegrityError(`${path} is not a string`);
  }
  return value;
}

function record(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new TemplateIntegrityError(`${path} is not an object`);
  }
  return value as Record<string, unknown>;
}

function parseRecord(json: string, path: string): Record<string, unknown> {
  try {
    return record(parse(json), path);
  } catch (error) {
    if (error instanceof TemplateIntegrityError) throw error;
    throw new TemplateIntegrityError(`${path} is not valid JSON`);
  }
}

function exactKeys(
  value: Record<string, unknown>,
  expected: string[],
  path: string,
) {
  const actual = Object.keys(value).sort();
  const exact = [...expected].sort();
  if (actual.length !== exact.length || actual.some((key, index) => key !== exact[index])) {
    throw new TemplateIntegrityError(`${path} has missing or unknown members`);
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function isReadinessUnavailable(error: unknown): boolean {
  return error instanceof TypeError
    || (error instanceof TemplateRequestError && error.status === 503);
}
