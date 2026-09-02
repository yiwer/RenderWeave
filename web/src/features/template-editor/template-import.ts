import { isLosslessNumber, LosslessNumber, parse, stringify } from 'lossless-json';

import {
  DESIGN_DSL_VERSION,
  EXPRESSION_PROFILE,
  inspectDesignDslWire,
} from './template-design-dsl-wire';

export const TEMPLATE_REVISION_EXPORT_VERSION = 'renderweave-template-revision-export/1.0';
export const BARE_DESIGN_DSL_MEDIA_TYPE = 'application/vnd.renderweave.design+json';
export const TEMPLATE_REVISION_EXPORT_MEDIA_TYPE = 'application/vnd.renderweave.template-revision+json';

const CONTENT_HASH_DOMAIN = 'renderweave-design-content/1\0';
const MAX_RAW_UTF8_BYTES = 16 * 1024 * 1024;
const MAX_CANONICAL_BYTES = 16 * 1024 * 1024;
const MAX_JSON_DEPTH = 64;
const MAX_OBJECT_MEMBERS = 1_024;
const MAX_ARRAY_ITEMS = 100_000;
const MAX_TOTAL_VALUES_AND_CONTAINERS = 1_000_000;
const MAX_STRING_UTF8_BYTES = 1 * 1024 * 1024;
const MAX_MEMBER_NAME_UTF8_BYTES = 256;
const MAX_NUMBER_TOKEN_BYTES = 256;
const MAX_SIGNED_REVISION = 9_223_372_036_854_775_807n;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const encoder = new TextEncoder();
const fatalDecoder = new TextDecoder('utf-8', { fatal: true });

export interface ImportedRevisionIdentity {
  kind: 'templateRevision';
  templateId: string;
  revision: string;
}

export interface ImportedStaticSchemaIdentity {
  schemaKey: string;
  versionTag: string;
}

export interface StructuredTemplateImport {
  mode: 'structured';
  source: 'bare-design-dsl' | 'template-revision-export';
  canonicalDesignDsl: string;
  designDsl: Record<string, unknown>;
  contentHash: string;
  sourceIdentity?: ImportedRevisionIdentity;
  sourceStaticSchema?: ImportedStaticSchemaIdentity;
}

export interface CompatibilityTemplateImport {
  mode: 'compatibility';
  code: 'UNKNOWN_WIRE';
  message: string;
  originalBytes: Uint8Array;
  source: 'bare-design-dsl' | 'template-revision-export';
  sourceIdentity?: ImportedRevisionIdentity;
  sourceStaticSchema?: ImportedStaticSchemaIdentity;
}

export type RawRepairCode =
  | 'RAW_SIZE_EXCEEDED'
  | 'INVALID_UTF8'
  | 'UTF8_BOM_FORBIDDEN'
  | 'INVALID_JSON'
  | 'DUPLICATE_MEMBER'
  | 'INVALID_UNICODE'
  | 'JSON_DEPTH_EXCEEDED'
  | 'OBJECT_MEMBERS_EXCEEDED'
  | 'ARRAY_ITEMS_EXCEEDED'
  | 'TOTAL_VALUES_EXCEEDED'
  | 'STRING_TOO_LARGE'
  | 'MEMBER_NAME_TOO_LARGE'
  | 'NUMBER_TOKEN_TOO_LARGE'
  | 'CANONICAL_SIZE_EXCEEDED'
  | 'UNTRUSTED_DESIGN_ROOT'
  | 'UNTRUSTED_EXPORT_ENVELOPE'
  | 'UNSUPPORTED_EXPORT_VERSION'
  | 'UNSUPPORTED_PROFILE'
  | 'EXPORT_CONTENT_HASH_MISMATCH';

export interface RawRepairTemplateImport {
  mode: 'raw-repair';
  code: RawRepairCode;
  message: string;
  originalBytes: Uint8Array;
  rawText?: string;
}

export type TemplateImportInspection =
  | StructuredTemplateImport
  | CompatibilityTemplateImport
  | RawRepairTemplateImport;

interface ParsedEnvelope {
  source: 'bare-design-dsl' | 'template-revision-export';
  designDsl: Record<string, unknown>;
  declaredContentHash?: string;
  sourceIdentity?: ImportedRevisionIdentity;
  sourceStaticSchema?: ImportedStaticSchemaIdentity;
}

export async function inspectTemplateImport(bytes: Uint8Array): Promise<TemplateImportInspection> {
  const originalBytes = bytes.slice();
  let rawText: string | undefined;
  try {
    if (bytes.byteLength > MAX_RAW_UTF8_BYTES) {
      throw fault('RAW_SIZE_EXCEEDED');
    }
    if (hasUtf8Bom(bytes)) throw fault('UTF8_BOM_FORBIDDEN');
    try {
      rawText = fatalDecoder.decode(bytes);
    } catch {
      throw fault('INVALID_UTF8');
    }

    preflightDepth(rawText);
    assertNoDuplicateMembers(rawText);
    const parsed = parseStrictLossless(rawText);
    validateParsedLimits(parsed);
    const envelope = parseEnvelope(parsed);
    assertTrustedDesignRoot(envelope.designDsl);
    const canonicalized = canonicalizeDesignDsl(envelope.designDsl);
    const contentHash = await designContentHash(canonicalized.canonicalDesignDsl);
    if (envelope.declaredContentHash !== undefined
      && envelope.declaredContentHash !== contentHash) {
      throw fault('EXPORT_CONTENT_HASH_MISMATCH');
    }

    const wire = inspectDesignDslWire(canonicalized.designDsl);
    if (wire.status === 'unsupported-profile') throw fault('UNSUPPORTED_PROFILE');
    if (wire.status === 'malformed') throw fault('UNTRUSTED_DESIGN_ROOT');
    if (wire.status === 'unknown') {
      return {
        mode: 'compatibility',
        code: 'UNKNOWN_WIRE',
        message: `当前客户端不能完整理解 ${wire.path} 的 wire；已保留原始文件供原样导出。`,
        originalBytes,
        source: envelope.source,
        ...(envelope.sourceIdentity ? { sourceIdentity: envelope.sourceIdentity } : {}),
        ...(envelope.sourceStaticSchema
          ? { sourceStaticSchema: envelope.sourceStaticSchema }
          : {}),
      };
    }

    return Object.freeze({
      mode: 'structured',
      source: envelope.source,
      canonicalDesignDsl: canonicalized.canonicalDesignDsl,
      designDsl: deepFreeze(canonicalized.designDsl),
      contentHash,
      ...(envelope.sourceIdentity ? { sourceIdentity: Object.freeze(envelope.sourceIdentity) } : {}),
      ...(envelope.sourceStaticSchema
        ? { sourceStaticSchema: Object.freeze(envelope.sourceStaticSchema) }
        : {}),
    });
  } catch (error) {
    const problem = error instanceof ImportFault ? error : fault('INVALID_JSON');
    return {
      mode: 'raw-repair',
      code: problem.code,
      message: rawRepairMessage(problem.code),
      originalBytes,
      ...(rawText === undefined ? {} : { rawText }),
    };
  }
}

function parseStrictLossless(text: string): unknown {
  try {
    return parse(text, null, {
      parseNumber: (token) => {
        if (encoder.encode(token).byteLength > MAX_NUMBER_TOKEN_BYTES) {
          throw fault('NUMBER_TOKEN_TOO_LARGE');
        }
        return new LosslessNumber(token);
      },
      onDuplicateKey: () => {
        throw fault('DUPLICATE_MEMBER');
      },
    });
  } catch (error) {
    if (error instanceof ImportFault) throw error;
    throw fault('INVALID_JSON');
  }
}

function parseEnvelope(parsed: unknown): ParsedEnvelope {
  if (!isRecord(parsed)) throw fault('UNTRUSTED_DESIGN_ROOT');
  if (!Object.hasOwn(parsed, 'exportVersion')) {
    return { source: 'bare-design-dsl', designDsl: parsed };
  }

  assertExactMembers(parsed, [
    'exportVersion', 'identity', 'staticSchemaRef', 'contentHash', 'designDsl',
  ], 'UNTRUSTED_EXPORT_ENVELOPE');
  if (parsed.exportVersion !== TEMPLATE_REVISION_EXPORT_VERSION) {
    throw fault('UNSUPPORTED_EXPORT_VERSION');
  }
  const identity = parsed.identity;
  const staticSchemaRef = parsed.staticSchemaRef;
  const designDsl = parsed.designDsl;
  if (!isRecord(identity) || !isRecord(staticSchemaRef) || !isRecord(designDsl)) {
    throw fault('UNTRUSTED_EXPORT_ENVELOPE');
  }
  assertExactMembers(identity, ['kind', 'templateId', 'revision'], 'UNTRUSTED_EXPORT_ENVELOPE');
  assertExactMembers(staticSchemaRef, ['schemaKey', 'versionTag'], 'UNTRUSTED_EXPORT_ENVELOPE');
  const revision = identity.revision;
  const revisionToken = isLosslessNumber(revision) ? revision.toString() : undefined;
  if (identity.kind !== 'templateRevision'
    || typeof identity.templateId !== 'string' || identity.templateId.length === 0
    || !UUID_V4.test(identity.templateId)
    || revisionToken === undefined || !/^(?:0|[1-9][0-9]*)$/.test(revisionToken)
    || (revisionToken !== undefined && /^(?:0|[1-9][0-9]*)$/.test(revisionToken)
      && BigInt(revisionToken) > MAX_SIGNED_REVISION)
    || typeof staticSchemaRef.schemaKey !== 'string' || staticSchemaRef.schemaKey.length === 0
    || typeof staticSchemaRef.versionTag !== 'string' || staticSchemaRef.versionTag.length === 0
    || typeof parsed.contentHash !== 'string'
    || !/^sha256:[0-9a-f]{64}$/.test(parsed.contentHash)) {
    throw fault('UNTRUSTED_EXPORT_ENVELOPE');
  }
  return {
    source: 'template-revision-export',
    designDsl,
    declaredContentHash: parsed.contentHash,
    sourceIdentity: {
      kind: 'templateRevision',
      templateId: identity.templateId,
      revision: revisionToken,
    },
    sourceStaticSchema: {
      schemaKey: staticSchemaRef.schemaKey,
      versionTag: staticSchemaRef.versionTag,
    },
  };
}

function assertTrustedDesignRoot(value: Record<string, unknown>) {
  if (value.dslVersion !== DESIGN_DSL_VERSION
    || value.expressionProfile !== EXPRESSION_PROFILE) {
    if (typeof value.dslVersion === 'string' && typeof value.expressionProfile === 'string') {
      throw fault('UNSUPPORTED_PROFILE');
    }
    throw fault('UNTRUSTED_DESIGN_ROOT');
  }
  if (typeof value.displayName !== 'string'
    || !Array.isArray(value.definitions)
    || !isRecord(value.designRoot)
    || typeof value.designRoot.kind !== 'string') {
    throw fault('UNTRUSTED_DESIGN_ROOT');
  }
}

function canonicalizeDesignDsl(value: Record<string, unknown>): {
  designDsl: Record<string, unknown>;
  canonicalDesignDsl: string;
} {
  const normalized = normalizeNumbers(value) as Record<string, unknown>;
  normalizeMetadataAndSets(normalized);
  const designDsl = sortObjectMembers(normalized) as Record<string, unknown>;
  const canonicalDesignDsl = stringify(designDsl);
  if (canonicalDesignDsl === undefined) throw fault('UNTRUSTED_DESIGN_ROOT');
  if (encoder.encode(canonicalDesignDsl).byteLength > MAX_CANONICAL_BYTES) {
    throw fault('CANONICAL_SIZE_EXCEEDED');
  }
  return { designDsl, canonicalDesignDsl };
}

function normalizeNumbers(value: unknown): unknown {
  if (isLosslessNumber(value)) return new LosslessNumber(canonicalDecimal(value.toString()));
  if (Array.isArray(value)) return value.map(normalizeNumbers);
  if (isRecord(value)) {
    return Object.fromEntries(
      Object.entries(value).map(([key, child]) => [key, normalizeNumbers(child)]),
    );
  }
  return value;
}

function canonicalDecimal(token: string): string {
  const match = /^(-?)(0|[1-9][0-9]*)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/.exec(token);
  if (!match) throw fault('INVALID_JSON');
  const negative = match[1] === '-';
  const integer = match[2] ?? '0';
  const fraction = match[3] ?? '';
  const exponent = BigInt(match[4] ?? '0');
  let digits = integer + fraction;
  if (/^0+$/.test(digits)) return '0';
  let decimalPosition = BigInt(integer.length) + exponent;
  const leading = digits.match(/^0+/)?.[0].length ?? 0;
  if (leading > 0) {
    digits = digits.slice(leading);
    decimalPosition -= BigInt(leading);
  }
  digits = digits.replace(/0+$/, '');

  const digitLength = BigInt(digits.length);
  let bodyLength: bigint;
  if (decimalPosition <= 0n) {
    bodyLength = 2n + (-decimalPosition) + digitLength;
  } else if (decimalPosition >= digitLength) {
    bodyLength = decimalPosition;
  } else {
    bodyLength = digitLength + 1n;
  }
  if (bodyLength + (negative ? 1n : 0n) > BigInt(MAX_CANONICAL_BYTES)) {
    throw fault('CANONICAL_SIZE_EXCEEDED');
  }

  let body: string;
  if (decimalPosition <= 0n) {
    body = `0.${'0'.repeat(Number(-decimalPosition))}${digits}`;
  } else if (decimalPosition >= digitLength) {
    body = digits + '0'.repeat(Number(decimalPosition - digitLength));
  } else {
    const at = Number(decimalPosition);
    body = `${digits.slice(0, at)}.${digits.slice(at)}`;
  }
  return negative ? `-${body}` : body;
}

function normalizeMetadataAndSets(root: Record<string, unknown>) {
  if (typeof root.displayName === 'string') root.displayName = javaTrim(root.displayName);
  if (typeof root.description === 'string') {
    const description = javaTrim(root.description);
    if (description.length === 0) delete root.description;
    else root.description = description;
  }
  if (Array.isArray(root.definitions)) {
    for (const value of root.definitions) {
      if (!isRecord(value)) continue;
      if (typeof value.displayName === 'string') value.displayName = javaTrim(value.displayName);
      if (value.kind === 'expression' && Array.isArray(value.inputs)) {
        sortSet(value.inputs, 'alias');
      }
    }
    sortSet(root.definitions, 'definitionId');
  }
  if (isRecord(root.designRoot)) normalizeNode(root.designRoot);
}

function normalizeNode(node: Record<string, unknown>) {
  if (typeof node.displayName === 'string') node.displayName = javaTrim(node.displayName);
  if (Array.isArray(node.bindings)) sortSet(node.bindings, 'bindingId');
  if (node.kind === 'templateUse' && Array.isArray(node.fills)) {
    sortSet(node.fills, 'targetDefinitionId');
  }
  if (Array.isArray(node.children)) {
    for (const child of node.children) if (isRecord(child)) normalizeNode(child);
  }
}

function sortSet(values: unknown[], member: string) {
  values.sort((left, right) => {
    if (!isRecord(left) || !isRecord(right)) return 0;
    const leftKey = left[member];
    const rightKey = right[member];
    return typeof leftKey === 'string' && typeof rightKey === 'string'
      ? compareUtf8(leftKey, rightKey)
      : 0;
  });
}

function sortObjectMembers(value: unknown): unknown {
  if (isLosslessNumber(value)) return new LosslessNumber(value.toString());
  if (Array.isArray(value)) return value.map(sortObjectMembers);
  if (isRecord(value)) {
    return Object.fromEntries(
      Object.keys(value)
        .sort(compareUtf8)
        .map((key) => [key, sortObjectMembers(value[key])]),
    );
  }
  return value;
}

async function designContentHash(canonicalDesignDsl: string): Promise<string> {
  const bytes = encoder.encode(CONTENT_HASH_DOMAIN + canonicalDesignDsl);
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', bytes));
  return `sha256:${Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')}`;
}

function preflightDepth(text: string) {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (const character of text) {
    if (inString) {
      if (escaped) escaped = false;
      else if (character === '\\') escaped = true;
      else if (character === '"') inString = false;
      continue;
    }
    if (character === '"') inString = true;
    else if (character === '{' || character === '[') {
      depth += 1;
      if (depth > MAX_JSON_DEPTH) throw fault('JSON_DEPTH_EXCEEDED');
    } else if (character === '}' || character === ']') {
      depth -= 1;
    }
  }
}

function assertNoDuplicateMembers(text: string) {
  let index = 0;

  parseValue();
  whitespace();
  if (index !== text.length) throw fault('INVALID_JSON');

  function parseValue() {
    whitespace();
    const current = text[index];
    if (current === '{') parseObject();
    else if (current === '[') parseArray();
    else if (current === '"') parseString();
    else if (current === 't') literal('true');
    else if (current === 'f') literal('false');
    else if (current === 'n') literal('null');
    else if (current === '-' || (current !== undefined && current >= '0' && current <= '9')) {
      parseNumberToken();
    } else throw fault('INVALID_JSON');
  }

  function parseObject() {
    index += 1;
    whitespace();
    const members = new Set<string>();
    if (text[index] === '}') {
      index += 1;
      return;
    }
    while (index < text.length) {
      whitespace();
      if (text[index] !== '"') throw fault('INVALID_JSON');
      const member = parseString();
      if (members.has(member)) throw fault('DUPLICATE_MEMBER');
      members.add(member);
      whitespace();
      if (text[index] !== ':') throw fault('INVALID_JSON');
      index += 1;
      parseValue();
      whitespace();
      if (text[index] === '}') {
        index += 1;
        return;
      }
      if (text[index] !== ',') throw fault('INVALID_JSON');
      index += 1;
    }
    throw fault('INVALID_JSON');
  }

  function parseArray() {
    index += 1;
    whitespace();
    if (text[index] === ']') {
      index += 1;
      return;
    }
    while (index < text.length) {
      parseValue();
      whitespace();
      if (text[index] === ']') {
        index += 1;
        return;
      }
      if (text[index] !== ',') throw fault('INVALID_JSON');
      index += 1;
    }
    throw fault('INVALID_JSON');
  }

  function parseString(): string {
    const start = index;
    index += 1;
    let escaped = false;
    while (index < text.length) {
      const code = text.charCodeAt(index);
      const current = text[index];
      if (!escaped && current === '"') {
        index += 1;
        try {
          return JSON.parse(text.slice(start, index)) as string;
        } catch {
          throw fault('INVALID_JSON');
        }
      }
      if (!escaped && code < 0x20) throw fault('INVALID_JSON');
      if (!escaped && current === '\\') escaped = true;
      else escaped = false;
      index += 1;
    }
    throw fault('INVALID_JSON');
  }

  function parseNumberToken() {
    const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(text.slice(index));
    if (!match) throw fault('INVALID_JSON');
    index += match[0].length;
  }

  function literal(expected: string) {
    if (text.slice(index, index + expected.length) !== expected) throw fault('INVALID_JSON');
    index += expected.length;
  }

  function whitespace() {
    while (index < text.length && (text[index] === ' ' || text[index] === '\t'
      || text[index] === '\r' || text[index] === '\n')) index += 1;
  }
}

function validateParsedLimits(root: unknown) {
  const stack: unknown[] = [root];
  let total = 0;
  while (stack.length > 0) {
    const value = stack.pop();
    total += 1;
    if (total > MAX_TOTAL_VALUES_AND_CONTAINERS) throw fault('TOTAL_VALUES_EXCEEDED');
    if (typeof value === 'string') {
      if (!hasOnlyUnicodeScalars(value)) throw fault('INVALID_UNICODE');
      if (encoder.encode(value).byteLength > MAX_STRING_UTF8_BYTES) throw fault('STRING_TOO_LARGE');
    } else if (Array.isArray(value)) {
      if (value.length > MAX_ARRAY_ITEMS) throw fault('ARRAY_ITEMS_EXCEEDED');
      for (const child of value) stack.push(child);
    } else if (isRecord(value)) {
      const entries = Object.entries(value);
      if (entries.length > MAX_OBJECT_MEMBERS) throw fault('OBJECT_MEMBERS_EXCEEDED');
      for (const [key, child] of entries) {
        if (!hasOnlyUnicodeScalars(key)) throw fault('INVALID_UNICODE');
        if (encoder.encode(key).byteLength > MAX_MEMBER_NAME_UTF8_BYTES) {
          throw fault('MEMBER_NAME_TOO_LARGE');
        }
        stack.push(child);
      }
    }
  }
}

function assertExactMembers(
  value: Record<string, unknown>,
  expected: readonly string[],
  code: RawRepairCode,
) {
  const keys = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (keys.length !== wanted.length || keys.some((key, index) => key !== wanted[index])) {
    throw fault(code);
  }
}

function compareUtf8(left: string, right: string): number {
  const leftBytes = encoder.encode(left);
  const rightBytes = encoder.encode(right);
  const length = Math.min(leftBytes.length, rightBytes.length);
  for (let index = 0; index < length; index += 1) {
    const difference = (leftBytes[index] ?? 0) - (rightBytes[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return leftBytes.length - rightBytes.length;
}

function javaTrim(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value.charCodeAt(start) <= 0x20) start += 1;
  while (end > start && value.charCodeAt(end - 1) <= 0x20) end -= 1;
  return value.slice(start, end);
}

function hasOnlyUnicodeScalars(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const current = value.charCodeAt(index);
    if (current >= 0xd800 && current <= 0xdbff) {
      if (index + 1 >= value.length) return false;
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) return false;
      index += 1;
    } else if (current >= 0xdc00 && current <= 0xdfff) return false;
  }
  return true;
}

function hasUtf8Bom(bytes: Uint8Array): boolean {
  return bytes.byteLength >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && !isLosslessNumber(value);
}

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  if (Array.isArray(value)) for (const child of value) deepFreeze(child);
  else for (const child of Object.values(value)) deepFreeze(child);
  return Object.freeze(value);
}

class ImportFault extends Error {
  constructor(readonly code: RawRepairCode) {
    super(code);
  }
}

function fault(code: RawRepairCode): ImportFault {
  return new ImportFault(code);
}

function rawRepairMessage(code: RawRepairCode): string {
  const messages: Record<RawRepairCode, string> = {
    RAW_SIZE_EXCEEDED: '文件超过 16 MiB 原始字节上限。',
    INVALID_UTF8: '文件不是严格 UTF-8；可下载原始字节后换文件。',
    UTF8_BOM_FORBIDDEN: 'UTF-8 BOM 不属于 DesignDSL wire。',
    INVALID_JSON: 'JSON grammar 不完整或不合法。',
    DUPLICATE_MEMBER: 'JSON 含重复 member，不能安全解释。',
    INVALID_UNICODE: 'JSON 含 lone surrogate 或无效 Unicode scalar。',
    JSON_DEPTH_EXCEEDED: 'JSON 深度超过 64。',
    OBJECT_MEMBERS_EXCEEDED: '单个 object 的 member 数超过 1024。',
    ARRAY_ITEMS_EXCEEDED: '单个 array 的 item 数超过 100000。',
    TOTAL_VALUES_EXCEEDED: 'JSON value/container 总数超过 1000000。',
    STRING_TOO_LARGE: '单个 string 超过 1 MiB UTF-8。',
    MEMBER_NAME_TOO_LARGE: 'member name 超过 256 UTF-8 bytes。',
    NUMBER_TOKEN_TOO_LARGE: 'number token 超过 256 bytes。',
    CANONICAL_SIZE_EXCEEDED: 'canonical DesignDSL 超过 16 MiB。',
    UNTRUSTED_DESIGN_ROOT: '无法识别可信的 DesignDSL root。',
    UNTRUSTED_EXPORT_ENVELOPE: 'revision export envelope 不是 exact closed wire。',
    UNSUPPORTED_EXPORT_VERSION: '客户端不支持该 revision export version。',
    UNSUPPORTED_PROFILE: '客户端不支持该 DesignDSL/Expression exact profile。',
    EXPORT_CONTENT_HASH_MISMATCH: 'revision export 的 contentHash 与 canonical DesignDSL 不一致。',
  };
  return messages[code];
}
