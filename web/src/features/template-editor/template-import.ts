import { isLosslessNumber, LosslessNumber, parse, stringify } from 'lossless-json';

export const TEMPLATE_REVISION_EXPORT_VERSION = 'renderweave-template-revision-export/1.0';
export const BARE_DESIGN_DSL_MEDIA_TYPE = 'application/vnd.renderweave.design+json';
export const TEMPLATE_REVISION_EXPORT_MEDIA_TYPE = 'application/vnd.renderweave.template-revision+json';

const DESIGN_DSL_VERSION = 'renderweave-design/1.0';
const EXPRESSION_PROFILE = 'renderweave-expression/1.0';
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

    const unknownPath = firstUnknownWirePath(canonicalized.designDsl);
    if (unknownPath !== null) {
      return {
        mode: 'compatibility',
        code: 'UNKNOWN_WIRE',
        message: `当前客户端不能完整理解 ${unknownPath} 的 wire；已保留原始文件供原样导出。`,
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

// Closed-wire inspection intentionally checks only whether a member/union is understood.
// Missing members and invalid scalar values remain best-effort Structured for server validation.
function firstUnknownWirePath(root: Record<string, unknown>): string | null {
  const inspector = new ClosedWireInspector();
  inspector.allowed(root, ROOT_MEMBERS, 'DesignDSL');
  if (Array.isArray(root.definitions)) {
    root.definitions.forEach((entry, index) => inspector.definition(entry, `definitions[${index}]`));
  }
  inspector.node(root.designRoot, 'designRoot');
  return inspector.unknownPath;
}

class ClosedWireInspector {
  unknownPath: string | null = null;

  mark(path: string) {
    if (this.unknownPath === null) this.unknownPath = path;
  }

  allowed(value: unknown, allowed: ReadonlySet<string>, path: string) {
    if (!isRecord(value) || this.unknownPath !== null) return;
    const unknown = Object.keys(value).find((key) => !allowed.has(key));
    if (unknown !== undefined) this.mark(`${path}.${unknown}`);
  }

  union(kind: unknown, known: ReadonlySet<string>, path: string): kind is string {
    if (typeof kind !== 'string') return false;
    if (!known.has(kind)) this.mark(path);
    return known.has(kind);
  }

  definition(value: unknown, path: string) {
    if (!isRecord(value) || this.unknownPath !== null) return;
    const kind = value.kind;
    if (!this.union(kind, DEFINITION_KINDS, `${path}.kind`)) return;
    const allowed = new Set(COMMON_DEFINITION_MEMBERS);
    for (const member of DEFINITION_MEMBERS[kind] ?? []) allowed.add(member);
    this.allowed(value, allowed, path);
    this.valueType(value.valueType, `${path}.valueType`);
    if (kind === 'custom') {
      this.literal(value.defaultValue, value.valueType, `${path}.defaultValue`);
    } else if (kind === 'mapping') {
      this.domain(value.domain, `${path}.domain`);
      this.valueType(value.output, `${path}.output`);
      this.source(value.input, `${path}.input`);
      if (Array.isArray(value.cases)) {
        value.cases.forEach((entry, index) => this.mappingCase(entry, `${path}.cases[${index}]`));
      }
      this.source(value.otherwise, `${path}.otherwise`);
    } else if (kind === 'expression') {
      this.domain(value.domain, `${path}.domain`);
      this.valueType(value.output, `${path}.output`);
      if (Array.isArray(value.inputs)) {
        value.inputs.forEach((entry, index) => {
          const inputPath = `${path}.inputs[${index}]`;
          this.allowed(entry, EXPRESSION_INPUT_MEMBERS, inputPath);
          if (isRecord(entry)) this.source(entry.source, `${inputPath}.source`);
        });
      }
    }
  }

  mappingCase(value: unknown, path: string) {
    this.allowed(value, CASE_MEMBERS, path);
    if (!isRecord(value)) return;
    if (isRecord(value.operand)) {
      this.allowed(value.operand, OPERAND_MEMBERS, `${path}.operand`);
      this.valueType(value.operand.valueType, `${path}.operand.valueType`);
      this.literal(value.operand.value, value.operand.valueType, `${path}.operand.value`);
    }
    this.source(value.then, `${path}.then`);
  }

  valueType(value: unknown, path: string) {
    if (typeof value === 'string') {
      if (!BASE_VALUE_TYPES.has(value)) this.mark(path);
      return;
    }
    if (!isRecord(value)) return;
    this.allowed(value, VALUE_TYPE_MEMBERS, path);
    if (typeof value.type === 'string' && !VALUE_TYPE_KINDS.has(value.type)) this.mark(`${path}.type`);
  }

  literal(value: unknown, valueType: unknown, path: string) {
    const key = valueTypeKey(valueType);
    if (key === 'imageRef' || key === 'fontRef') this.allowed(value, ASSET_REF_MEMBERS, path);
    if (key?.startsWith('list:') && Array.isArray(value)) {
      const itemType = key.slice('list:'.length);
      value.forEach((item, index) => this.literal(item, itemType, `${path}[${index}]`));
    }
  }

  domain(value: unknown, path: string) {
    if (!isRecord(value)) return;
    this.allowed(value, DOMAIN_LOOP_MEMBERS, path);
    if (typeof value.kind === 'string' && value.kind !== 'loop') this.mark(`${path}.kind`);
  }

  source(value: unknown, path: string) {
    if (!isRecord(value) || this.unknownPath !== null) return;
    const kind = value.kind;
    if (!this.union(kind, VALUE_SOURCE_KINDS, `${path}.kind`)) return;
    this.allowed(value, SOURCE_MEMBERS[kind] ?? EMPTY_MEMBERS, path);
    if (kind === 'literal') {
      this.valueType(value.valueType, `${path}.valueType`);
      this.literal(value.value, value.valueType, `${path}.value`);
    } else if (kind === 'context') {
      this.domain(value.domain, `${path}.domain`);
    }
  }

  node(value: unknown, path: string) {
    if (!isRecord(value) || this.unknownPath !== null) return;
    const kind = value.kind;
    let allowed: ReadonlySet<string> = ALL_NODE_MEMBERS;
    if (typeof kind === 'string') {
      if (!NODE_KINDS.has(kind)) {
        this.mark(`${path}.kind`);
        return;
      }
      allowed = kind === 'canvas' ? CANVAS_MEMBERS : NODE_MEMBERS[kind] ?? COMMON_NODE_MEMBERS;
    }
    this.allowed(value, allowed, path);
    this.bindings(value.bindings, `${path}.bindings`);
    this.placement(value.placement, `${path}.placement`);
    this.allowed(value.transform, TRANSFORM_MEMBERS, `${path}.transform`);
    this.allowed(value.fill, FILL_MEMBERS, `${path}.fill`);
    this.stroke(value.stroke, kind === 'text', `${path}.stroke`);
    this.allowed(value.cornerRadii, CORNER_RADII_MEMBERS, `${path}.cornerRadii`);
    this.allowed(value.padding, PADDING_MEMBERS, `${path}.padding`);

    if (kind === 'canvas') this.allowed(value.bleed, BLEED_MEMBERS, `${path}.bleed`);
    if (kind === 'grid') {
      this.tracks(value.rows, `${path}.rows`);
      this.tracks(value.columns, `${path}.columns`);
    } else if (kind === 'repeat') {
      this.source(value.items, `${path}.items`);
      this.packing(value.itemLayout, `${path}.itemLayout`);
      this.packing(value.instanceLayout, `${path}.instanceLayout`);
    } else if (kind === 'text') {
      if (Array.isArray(value.runs)) {
        value.runs.forEach((run, index) => {
          const runPath = `${path}.runs[${index}]`;
          this.allowed(run, RUN_MEMBERS, runPath);
          if (isRecord(run)) this.allowed(run.fontRef, ASSET_REF_MEMBERS, `${runPath}.fontRef`);
        });
      }
      this.lineHeight(value.lineHeight, `${path}.lineHeight`);
    } else if (kind === 'image') {
      this.allowed(value.imageRef, ASSET_REF_MEMBERS, `${path}.imageRef`);
    } else if (kind === 'line') {
      this.allowed(value.start, POINT_MM_MEMBERS, `${path}.start`);
      this.allowed(value.end, POINT_MM_MEMBERS, `${path}.end`);
    } else if (kind === 'polygon' || kind === 'polyline') {
      this.points(value.points, `${path}.points`);
    } else if (kind === 'path') {
      this.pathCommands(value.commands, `${path}.commands`);
    } else if (kind === 'templateUse') {
      this.templateUse(value, path);
    } else if (kind === 'conditional') {
      this.source(value.condition, `${path}.condition`);
    }

    if (Array.isArray(value.children)) {
      value.children.forEach((child, index) => this.node(child, `${path}.children[${index}]`));
    }
  }

  bindings(value: unknown, path: string) {
    if (!Array.isArray(value)) return;
    value.forEach((entry, index) => {
      const bindingPath = `${path}[${index}]`;
      this.allowed(entry, BINDING_MEMBERS, bindingPath);
      if (!isRecord(entry)) return;
      this.allowed(entry.targetPropertyRef, TARGET_PROPERTY_REF_MEMBERS, `${bindingPath}.targetPropertyRef`);
      if (isRecord(entry.targetPropertyRef) && Array.isArray(entry.targetPropertyRef.selectors)) {
        entry.targetPropertyRef.selectors.forEach((selector, selectorIndex) => {
          const selectorPath = `${bindingPath}.targetPropertyRef.selectors[${selectorIndex}]`;
          if (!isRecord(selector)) return;
          const kind = selector.kind;
          if (!this.union(kind, SELECTOR_KINDS, `${selectorPath}.kind`)) return;
          this.allowed(selector, kind === 'member' ? MEMBER_SELECTOR_MEMBERS : INDEX_SELECTOR_MEMBERS, selectorPath);
        });
      }
      this.source(entry.source, `${bindingPath}.source`);
    });
  }

  placement(value: unknown, path: string) {
    if (!isRecord(value)) return;
    const type = value.type;
    if (!this.union(type, PLACEMENT_KINDS, `${path}.type`)) return;
    this.allowed(value, PLACEMENT_MEMBERS[type] ?? EMPTY_MEMBERS, path);
  }

  stroke(value: unknown, points: boolean, path: string) {
    this.allowed(value, points ? STROKE_PT_MEMBERS : STROKE_MM_MEMBERS, path);
  }

  tracks(value: unknown, path: string) {
    if (!Array.isArray(value)) return;
    value.forEach((track, index) => {
      if (!isRecord(track)) return;
      const trackPath = `${path}[${index}]`;
      const type = track.type;
      if (!this.union(type, TRACK_KINDS, `${trackPath}.type`)) return;
      this.allowed(track, TRACK_MEMBERS[type] ?? EMPTY_MEMBERS, trackPath);
    });
  }

  packing(value: unknown, path: string) {
    if (!isRecord(value)) return;
    const kind = value.kind;
    if (!this.union(kind, PACKING_KINDS, `${path}.kind`)) return;
    this.allowed(value, kind === 'STACK' ? STACK_PACKING_MEMBERS : GRID_PACKING_MEMBERS, path);
  }

  lineHeight(value: unknown, path: string) {
    if (!isRecord(value)) return;
    const type = value.type;
    if (typeof type === 'string' && !LINE_HEIGHT_KINDS.has(type)) this.mark(`${path}.type`);
    this.allowed(value, LINE_HEIGHT_MEMBERS, path);
  }

  points(value: unknown, path: string) {
    if (!Array.isArray(value)) return;
    value.forEach((point, index) => this.allowed(point, POINT_MM_MEMBERS, `${path}[${index}]`));
  }

  pathCommands(value: unknown, path: string) {
    if (!Array.isArray(value)) return;
    value.forEach((command, index) => {
      if (!isRecord(command)) return;
      const commandPath = `${path}[${index}]`;
      const type = command.type;
      if (!this.union(type, PATH_COMMAND_KINDS, `${commandPath}.type`)) return;
      this.allowed(command, PATH_COMMAND_MEMBERS[type] ?? EMPTY_MEMBERS, commandPath);
    });
  }

  templateUse(value: Record<string, unknown>, path: string) {
    this.allowed(value.templateRef, TEMPLATE_REF_MEMBERS, `${path}.templateRef`);
    if (isRecord(value.contextSelector)) {
      const selector = value.contextSelector;
      const kind = selector.kind;
      if (this.union(kind, CONTEXT_SELECTOR_KINDS, `${path}.contextSelector.kind`)) {
        this.allowed(
          selector,
          kind === 'context' ? CONTEXT_SELECTOR_MEMBERS : EMPTY_SELECTOR_MEMBERS,
          `${path}.contextSelector`,
        );
        if (kind === 'context') this.selectorDomain(selector.domain, `${path}.contextSelector.domain`);
      }
    }
    if (Array.isArray(value.fills)) {
      value.fills.forEach((fill, index) => {
        const fillPath = `${path}.fills[${index}]`;
        this.allowed(fill, USE_FILL_MEMBERS, fillPath);
        if (isRecord(fill)) this.source(fill.source, `${fillPath}.source`);
      });
    }
  }

  selectorDomain(value: unknown, path: string) {
    if (!isRecord(value)) return;
    this.allowed(value, SELECTOR_DOMAIN_MEMBERS, path);
    if (typeof value.kind === 'string' && !SELECTOR_DOMAIN_KINDS.has(value.kind)) {
      this.mark(`${path}.kind`);
    }
  }
}

const ROOT_MEMBERS = set('dslVersion', 'expressionProfile', 'displayName', 'description', 'definitions', 'designRoot');
const CANVAS_MEMBERS = set('nodeId', 'kind', 'displayName', 'widthMm', 'heightMm', 'backgroundColor', 'bleed', 'bindings', 'children');
const BLEED_MEMBERS = set('topMm', 'rightMm', 'bottomMm', 'leftMm');
const COMMON_NODE_MEMBERS = set('nodeId', 'kind', 'displayName', 'bindings', 'placement', 'render', 'visible', 'opacity', 'transform');
const CONTAINER_MEMBERS = set('children');
const APPEARANCE_MEMBERS = set('fill', 'stroke', 'cornerRadii', 'padding', 'clipContent');
const STACK_MEMBERS = set('direction', 'gapMm', 'justifyContent', 'alignItems');
const GRID_MEMBERS = set('rows', 'columns', 'rowGapMm', 'columnGapMm');
const REPEAT_MEMBERS = set('loopId', 'items', 'absentPolicy', 'itemLayout', 'instanceLayout');
const TEXT_MEMBERS = set('runs', 'writingMode', 'horizontalAlign', 'verticalAlign', 'lineBreak', 'overflow', 'lineHeight', 'maxLines', 'padding', 'stroke', 'fitMode', 'minScale');
const RUN_MEMBERS = set('text', 'fontRef', 'fontSizePt', 'color', 'decoration', 'letterSpacingPt', 'letterSpacingFactor');
const LINE_HEIGHT_MEMBERS = set('type', 'factor', 'valuePt');
const IMAGE_MEMBERS = set('imageRef', 'fit', 'sampling');
const RECT_MEMBERS = set('fill', 'stroke', 'cornerRadii');
const ELLIPSE_MEMBERS = set('fill', 'stroke');
const LINE_MEMBERS = set('start', 'end', 'stroke');
const POLYGON_MEMBERS = set('points', 'fill', 'stroke');
const POLYLINE_MEMBERS = set('points', 'stroke');
const PATH_MEMBERS = set('commands', 'fill', 'stroke', 'fillRule');
const QRCODE_MEMBERS = set('content', 'errorCorrectionLevel', 'foregroundColor', 'backgroundColor');
const BARCODE_MEMBERS = set('format', 'value', 'foregroundColor', 'backgroundColor');
const POINT_MM_MEMBERS = set('xMm', 'yMm');
const TEMPLATE_USE_MEMBERS = set('useId', 'templateRef', 'contextSelector', 'fills');
const CONDITIONAL_MEMBERS = set('condition', 'absentPolicy');
const NODE_KINDS = set('canvas', 'group', 'frame', 'stack', 'grid', 'repeat', 'text', 'image', 'rect', 'ellipse', 'line', 'polygon', 'polyline', 'path', 'qrCode', 'barcode', 'templateUse', 'conditional');

const NODE_MEMBERS: Record<string, ReadonlySet<string>> = {
  group: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS),
  frame: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, APPEARANCE_MEMBERS),
  stack: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, APPEARANCE_MEMBERS, STACK_MEMBERS),
  grid: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, APPEARANCE_MEMBERS, GRID_MEMBERS),
  repeat: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, REPEAT_MEMBERS),
  text: union(COMMON_NODE_MEMBERS, TEXT_MEMBERS),
  image: union(COMMON_NODE_MEMBERS, IMAGE_MEMBERS),
  rect: union(COMMON_NODE_MEMBERS, RECT_MEMBERS),
  ellipse: union(COMMON_NODE_MEMBERS, ELLIPSE_MEMBERS),
  line: union(COMMON_NODE_MEMBERS, LINE_MEMBERS),
  polygon: union(COMMON_NODE_MEMBERS, POLYGON_MEMBERS),
  polyline: union(COMMON_NODE_MEMBERS, POLYLINE_MEMBERS),
  path: union(COMMON_NODE_MEMBERS, PATH_MEMBERS),
  qrCode: union(COMMON_NODE_MEMBERS, QRCODE_MEMBERS),
  barcode: union(COMMON_NODE_MEMBERS, BARCODE_MEMBERS),
  templateUse: union(COMMON_NODE_MEMBERS, TEMPLATE_USE_MEMBERS),
  conditional: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, CONDITIONAL_MEMBERS),
};
const ALL_NODE_MEMBERS = union(CANVAS_MEMBERS, ...Object.values(NODE_MEMBERS));

const FILL_MEMBERS = set('color');
const STROKE_MM_MEMBERS = set('color', 'widthMm', 'cap', 'join');
const STROKE_PT_MEMBERS = set('color', 'widthPt', 'cap', 'join');
const PADDING_MEMBERS = set('topMm', 'rightMm', 'bottomMm', 'leftMm');
const CORNER_RADII_MEMBERS = set('topLeftMm', 'topRightMm', 'bottomRightMm', 'bottomLeftMm');
const TRANSFORM_MEMBERS = set('rotationDeg', 'scaleX', 'scaleY', 'originX', 'originY');
const ABSOLUTE_PLACEMENT_MEMBERS = set('type', 'xMm', 'yMm', 'widthMode', 'heightMode', 'widthMm', 'heightMm', 'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm', 'rightInsetMm', 'bottomInsetMm');
const STACK_PLACEMENT_MEMBERS = set('type', 'widthMode', 'heightMode', 'widthMm', 'heightMm', 'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm', 'marginTopMm', 'marginRightMm', 'marginBottomMm', 'marginLeftMm', 'alignSelf', 'fillWeight');
const GRID_PLACEMENT_MEMBERS = set('type', 'widthMode', 'heightMode', 'widthMm', 'heightMm', 'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm', 'row', 'column', 'rowSpan', 'columnSpan', 'marginTopMm', 'marginRightMm', 'marginBottomMm', 'marginLeftMm', 'horizontalAlignSelf', 'verticalAlignSelf');
const PACK_PLACEMENT_MEMBERS = set('type', 'widthMode', 'heightMode', 'widthMm', 'heightMm', 'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm');
const PLACEMENT_KINDS = set('ABSOLUTE', 'STACK', 'GRID', 'PACK');
const PLACEMENT_MEMBERS: Record<string, ReadonlySet<string>> = {
  ABSOLUTE: ABSOLUTE_PLACEMENT_MEMBERS,
  STACK: STACK_PLACEMENT_MEMBERS,
  GRID: GRID_PLACEMENT_MEMBERS,
  PACK: PACK_PLACEMENT_MEMBERS,
};
const TRACK_KINDS = set('FIXED', 'FRACTION', 'AUTO');
const TRACK_MEMBERS: Record<string, ReadonlySet<string>> = {
  FIXED: set('type', 'valueMm'), FRACTION: set('type', 'weight'), AUTO: set('type'),
};
const STACK_PACKING_MEMBERS = set('kind', 'direction', 'gapMm');
const GRID_PACKING_MEMBERS = set('kind', 'columns', 'columnGapMm', 'rowGapMm');
const PACKING_KINDS = set('STACK', 'GRID');

const DEFINITION_KINDS = set('custom', 'mapping', 'expression');
const COMMON_DEFINITION_MEMBERS = set('definitionId', 'kind', 'displayName');
const DEFINITION_MEMBERS: Record<string, ReadonlySet<string>> = {
  custom: set('exposure', 'valueType', 'defaultValue'),
  mapping: set('domain', 'output', 'input', 'cases', 'otherwise'),
  expression: set('domain', 'output', 'inputs', 'source'),
};
const BASE_VALUE_TYPES = set('text', 'decimal', 'boolean', 'date', 'time', 'color', 'imageRef', 'fontRef');
const VALUE_TYPE_MEMBERS = set('type', 'items', 'catalogId');
const VALUE_TYPE_KINDS = set('list', 'enum');
const VALUE_SOURCE_KINDS = set('literal', 'context', 'loopIndex', 'definition', 'capability');
const SOURCE_MEMBERS: Record<string, ReadonlySet<string>> = {
  literal: set('kind', 'valueType', 'value'),
  context: set('kind', 'domain', 'pointer'),
  loopIndex: set('kind', 'loopId'),
  definition: set('kind', 'definitionId'),
  capability: set('kind', 'capability', 'operation'),
};
const CASE_MEMBERS = set('operator', 'operand', 'then');
const OPERAND_MEMBERS = set('valueType', 'value');
const EXPRESSION_INPUT_MEMBERS = set('alias', 'source');
const DOMAIN_LOOP_MEMBERS = set('kind', 'loopId');
const ASSET_REF_MEMBERS = set('assetId');

const BINDING_MEMBERS = set('bindingId', 'targetPropertyRef', 'source');
const TARGET_PROPERTY_REF_MEMBERS = set('rootPropertyId', 'selectors');
const MEMBER_SELECTOR_MEMBERS = set('kind', 'name');
const INDEX_SELECTOR_MEMBERS = set('kind', 'index');
const SELECTOR_KINDS = set('member', 'index');
const TEMPLATE_REF_MEMBERS = set('templateId');
const CONTEXT_SELECTOR_MEMBERS = set('kind', 'domain', 'pointer', 'contextAbsentPolicy');
const EMPTY_SELECTOR_MEMBERS = set('kind');
const CONTEXT_SELECTOR_KINDS = set('context', 'empty');
const SELECTOR_DOMAIN_MEMBERS = set('kind', 'loopId');
const SELECTOR_DOMAIN_KINDS = set('invocation', 'loop');
const USE_FILL_MEMBERS = set('targetDefinitionId', 'source');
const LINE_HEIGHT_KINDS = set('FACTOR', 'FIXED');
const PATH_COMMAND_KINDS = set('MOVE_TO', 'LINE_TO', 'QUAD_TO', 'CUBIC_TO', 'CLOSE');
const PATH_COMMAND_MEMBERS: Record<string, ReadonlySet<string>> = {
  MOVE_TO: set('type', 'xMm', 'yMm'),
  LINE_TO: set('type', 'xMm', 'yMm'),
  QUAD_TO: set('type', 'cxMm', 'cyMm', 'xMm', 'yMm'),
  CUBIC_TO: set('type', 'c1xMm', 'c1yMm', 'c2xMm', 'c2yMm', 'xMm', 'yMm'),
  CLOSE: set('type'),
};
const EMPTY_MEMBERS = new Set<string>();

function valueTypeKey(value: unknown): string | null {
  if (typeof value === 'string') return value;
  if (isRecord(value) && value.type === 'list' && typeof value.items === 'string') {
    return `list:${value.items}`;
  }
  return null;
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

function set(...values: string[]): ReadonlySet<string> {
  return new Set(values);
}

function union(...sets: ReadonlySet<string>[]): ReadonlySet<string> {
  return new Set(sets.flatMap((value) => [...value]));
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
