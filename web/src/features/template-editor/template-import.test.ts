/// <reference types="node" />

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import {
  inspectTemplateImport,
  TEMPLATE_REVISION_EXPORT_VERSION,
} from './template-import';

interface KernelVector {
  id: string;
  input: { kind: string; text?: string };
  expected: {
    outcome: string;
    canonicalUtf8?: string;
    contentHash?: string;
  };
}

interface KernelManifest {
  cases: KernelVector[];
}

const encoder = new TextEncoder();
const manifest = JSON.parse(readFileSync(fileURLToPath(new URL(
  '../../../../renderweave-template/src/test/resources/cn/hbads/renderweave/template/canonical-kernel-v1/vectors.json',
  import.meta.url,
)), 'utf8')) as KernelManifest;
const admitted = manifest.cases.filter((entry) =>
  entry.expected.outcome === 'ADMITTED' && entry.expected.canonicalUtf8 !== undefined,
);

describe('Template Editor E8 strict import boundary', () => {
  it('losslessly canonicalizes decimal tokens, member order and frozen metadata rules', async () => {
    const vector = manifest.cases.find((entry) =>
      entry.id === 'admit-metadata-unicode-decimal-semantic-empty-arrays',
    );
    if (!vector?.input.text || !vector.expected.canonicalUtf8) throw new Error('fixture missing');

    const result = await inspectTemplateImport(encoder.encode(vector.input.text));

    expect(result).toEqual(expect.objectContaining({
      mode: 'structured',
      source: 'bare-design-dsl',
      canonicalDesignDsl: vector.expected.canonicalUtf8,
      contentHash: vector.expected.contentHash,
    }));
  });

  it('keeps every Java-authority ADMITTED canonical vector in byte-identical Structured mode', async () => {
    expect(admitted.length).toBeGreaterThan(40);

    for (const vector of admitted) {
      const result = await inspectTemplateImport(encoder.encode(vector.expected.canonicalUtf8 ?? ''));
      expect(result.mode, vector.id).toBe('structured');
      if (result.mode !== 'structured') continue;
      expect(result.canonicalDesignDsl, vector.id).toBe(vector.expected.canonicalUtf8);
      expect(result.contentHash, vector.id).toBe(vector.expected.contentHash);
    }
  });

  it('sorts every frozen set-like collection but preserves semantic array order', async () => {
    const definitions = admittedVector('admit-custom-definitions-sorted');
    const definitionValue = JSON.parse(definitions.expected.canonicalUtf8 ?? '') as JsonObject;
    (definitionValue.definitions as unknown[]).reverse();
    const definitionResult = await inspectTemplateImport(encoder.encode(JSON.stringify(definitionValue)));
    expectStructuredCanonical(definitionResult, definitions.expected.canonicalUtf8);

    const bindings = admittedVector('admit-bindings-sorted');
    const bindingValue = JSON.parse(bindings.expected.canonicalUtf8 ?? '') as JsonObject;
    const frame = (((bindingValue.designRoot as JsonObject).children as JsonObject[])[0]);
    if (!frame) throw new Error('binding frame fixture missing');
    (frame.bindings as unknown[]).reverse();
    const bindingResult = await inspectTemplateImport(encoder.encode(JSON.stringify(bindingValue)));
    expectStructuredCanonical(bindingResult, bindings.expected.canonicalUtf8);

    const expression = admittedVector('admit-expression-definition-inputs-sorted');
    const expressionValue = JSON.parse(expression.expected.canonicalUtf8 ?? '') as JsonObject;
    const expressionDefinition = (expressionValue.definitions as JsonObject[])[0];
    if (!expressionDefinition) throw new Error('expression fixture missing');
    (expressionDefinition.inputs as unknown[]).reverse();
    const expressionResult = await inspectTemplateImport(encoder.encode(JSON.stringify(expressionValue)));
    expectStructuredCanonical(expressionResult, expression.expected.canonicalUtf8);

    const fills = admittedVector('admit-template-use-fills-sorted');
    const fillsValue = JSON.parse(fills.expected.canonicalUtf8 ?? '') as JsonObject;
    const templateUse = ((((fillsValue.designRoot as JsonObject).children as JsonObject[])[0]));
    if (!templateUse) throw new Error('TemplateUse fixture missing');
    (templateUse.fills as unknown[]).reverse();
    const fillsResult = await inspectTemplateImport(encoder.encode(JSON.stringify(fillsValue)));
    expectStructuredCanonical(fillsResult, fills.expected.canonicalUtf8);
  });

  it.each([
    ['invalid UTF-8', new Uint8Array([0xc3, 0x28]), 'INVALID_UTF8'],
    ['UTF-8 BOM', new Uint8Array([0xef, 0xbb, 0xbf, 0x7b, 0x7d]), 'UTF8_BOM_FORBIDDEN'],
    ['malformed JSON', encoder.encode('{"dslVersion":'), 'INVALID_JSON'],
    ['same-value duplicate', encoder.encode('{"dslVersion":"renderweave-design/1.0","dslVersion":"renderweave-design/1.0"}'), 'DUPLICATE_MEMBER'],
    ['escaped duplicate', encoder.encode('{"a":1,"\\u0061":2}'), 'DUPLICATE_MEMBER'],
    ['lone surrogate', encoder.encode('{"dslVersion":"renderweave-design/1.0","displayName":"\\ud800"}'), 'INVALID_UNICODE'],
  ])('routes %s to Raw Repair without inventing a baseline', async (_label, bytes, code) => {
    const result = await inspectTemplateImport(bytes);

    expect(result).toEqual(expect.objectContaining({
      mode: 'raw-repair',
      code,
      originalBytes: bytes,
    }));
    expect('canonicalDesignDsl' in result).toBe(false);
  });

  it.each([
    ['depth', nestedArray(65), 'JSON_DEPTH_EXCEEDED'],
    ['member name', `{"${'m'.repeat(257)}":0}`, 'MEMBER_NAME_TOO_LARGE'],
    ['number token', `{"n":${'1'.repeat(257)}}`, 'NUMBER_TOKEN_TOO_LARGE'],
    ['string', `{"s":"${'x'.repeat(1024 * 1024 + 1)}"}`, 'STRING_TOO_LARGE'],
  ])('enforces the frozen %s parser limit before adoption', async (_label, text, code) => {
    const result = await inspectTemplateImport(encoder.encode(text));
    expect(result).toEqual(expect.objectContaining({ mode: 'raw-repair', code }));
  });

  it('enforces object-member, array-item, total-value, raw-byte and canonical-byte limits', async () => {
    const tooManyMembers = `{${Array.from(
      { length: 1_025 },
      (_, index) => `"m${index}":0`,
    ).join(',')}}`;
    expect(await inspectTemplateImport(encoder.encode(tooManyMembers))).toEqual(
      expect.objectContaining({ mode: 'raw-repair', code: 'OBJECT_MEMBERS_EXCEEDED' }),
    );

    const tooManyItems = `[${'0,'.repeat(100_000)}0]`;
    expect(await inspectTemplateImport(encoder.encode(tooManyItems))).toEqual(
      expect.objectContaining({ mode: 'raw-repair', code: 'ARRAY_ITEMS_EXCEEDED' }),
    );

    const tenValues = '[0,0,0,0,0,0,0,0,0,0]';
    const tooManyTotalValues = `[${(`${tenValues},`).repeat(99_999)}${tenValues}]`;
    const totalResult = await inspectTemplateImport(encoder.encode(tooManyTotalValues));
    expect(totalResult.mode).toBe('raw-repair');
    if (totalResult.mode === 'raw-repair') expect(totalResult.code).toBe('TOTAL_VALUES_EXCEEDED');

    expect(await inspectTemplateImport(new Uint8Array(16 * 1024 * 1024 + 1))).toEqual(
      expect.objectContaining({ mode: 'raw-repair', code: 'RAW_SIZE_EXCEEDED' }),
    );

    const canonicalExpansion = importedNumber('1e16777216');
    expect(await inspectTemplateImport(encoder.encode(canonicalExpansion))).toEqual(
      expect.objectContaining({ mode: 'raw-repair', code: 'CANONICAL_SIZE_EXCEEDED' }),
    );
  }, 20_000);

  it('accepts the exact closed revision export, checks its hash and preserves lossless display identity', async () => {
    const vector = admittedVector('admit-baseline-member-order');
    const envelope = revisionExport(
      vector.expected.canonicalUtf8 ?? '',
      vector.expected.contentHash ?? '',
      '9223372036854775807',
    );

    const result = await inspectTemplateImport(encoder.encode(envelope));

    expect(result).toEqual(expect.objectContaining({
      mode: 'structured',
      source: 'template-revision-export',
      canonicalDesignDsl: vector.expected.canonicalUtf8,
      sourceIdentity: {
        kind: 'templateRevision',
        templateId: '00000000-0000-4000-8000-000000000099',
        revision: '9223372036854775807',
      },
      sourceStaticSchema: { schemaKey: 'foreign-schema', versionTag: 'foreign-v7' },
    }));
  });

  it('accepts revision zero in an exact revision export', async () => {
    const vector = admittedVector('admit-baseline-member-order');
    const result = await inspectTemplateImport(encoder.encode(revisionExport(
      vector.expected.canonicalUtf8 ?? '',
      vector.expected.contentHash ?? '',
      '0',
    )));

    expect(result).toEqual(expect.objectContaining({
      mode: 'structured',
      sourceIdentity: expect.objectContaining({ revision: '0' }),
    }));
  });

  it.each([
    ['hash mismatch', (value: JsonObject) => { value.contentHash = `sha256:${'0'.repeat(64)}`; }, 'EXPORT_CONTENT_HASH_MISMATCH'],
    ['outer extension', (value: JsonObject) => { value.future = true; }, 'UNTRUSTED_EXPORT_ENVELOPE'],
    ['identity extension', (value: JsonObject) => { (value.identity as JsonObject).future = true; }, 'UNTRUSTED_EXPORT_ENVELOPE'],
    ['unsupported export version', (value: JsonObject) => { value.exportVersion = 'renderweave-template-revision-export/2.0'; }, 'UNSUPPORTED_EXPORT_VERSION'],
    ['revision overflow', (value: JsonObject) => { value.identity = { ...(value.identity as JsonObject), revision: 9223372036854775808n }; }, 'UNTRUSTED_EXPORT_ENVELOPE'],
  ])('rejects an exact export with %s into Raw Repair', async (_label, mutate, code) => {
    const vector = admittedVector('admit-baseline-member-order');
    const value = JSON.parse(revisionExport(
      vector.expected.canonicalUtf8 ?? '',
      vector.expected.contentHash ?? '',
      '7',
    )) as JsonObject;
    mutate(value);

    const serialized = JSON.stringify(value, (_key, child) =>
      typeof child === 'bigint' ? child.toString() : child,
    ).replace('"revision":"9223372036854775808"', '"revision":9223372036854775808');
    const result = await inspectTemplateImport(encoder.encode(serialized));
    expect(result).toEqual(expect.objectContaining({ mode: 'raw-repair', code }));
  });

  it('classifies unsupported exact profiles as Raw Repair and unknown known-profile wire as Compatibility', async () => {
    const unsupported = baseDesign();
    unsupported.expressionProfile = 'renderweave-expression/2.0';
    const unsupportedResult = await inspectTemplateImport(encoder.encode(JSON.stringify(unsupported)));
    expect(unsupportedResult).toEqual(expect.objectContaining({
      mode: 'raw-repair',
      code: 'UNSUPPORTED_PROFILE',
    }));

    const futureWire = baseDesign();
    (futureWire.designRoot as JsonObject).futureMember = { opaque: true };
    const futureResult = await inspectTemplateImport(encoder.encode(JSON.stringify(futureWire)));
    expect(futureResult).toEqual(expect.objectContaining({
      mode: 'compatibility',
      code: 'UNKNOWN_WIRE',
      originalBytes: expect.any(Uint8Array),
    }));
    expect('canonicalDesignDsl' in futureResult).toBe(false);
  });

  it('keeps semantic invalidity on fully understood wire as best-effort Structured', async () => {
    const invalid = baseDesign();
    (invalid.designRoot as JsonObject).widthMm = -1;

    const result = await inspectTemplateImport(encoder.encode(JSON.stringify(invalid)));

    expect(result).toEqual(expect.objectContaining({
      mode: 'structured',
      source: 'bare-design-dsl',
    }));
  });
});

type JsonObject = Record<string, unknown>;

function admittedVector(id: string): KernelVector {
  const vector = manifest.cases.find((entry) => entry.id === id);
  if (!vector?.expected.canonicalUtf8) throw new Error(`missing admitted vector ${id}`);
  return vector;
}

function expectStructuredCanonical(result: Awaited<ReturnType<typeof inspectTemplateImport>>, expected?: string) {
  expect(result.mode).toBe('structured');
  if (result.mode === 'structured') expect(result.canonicalDesignDsl).toBe(expected);
}

function nestedArray(depth: number): string {
  return '['.repeat(depth) + '0' + ']'.repeat(depth);
}

function baseDesign(): JsonObject {
  return {
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: 'Imported',
    definitions: [],
    designRoot: {
      nodeId: '00000000-0000-4000-8000-000000000001',
      kind: 'canvas',
      widthMm: 210,
      heightMm: 297,
      bindings: [],
      children: [],
    },
  };
}

function revisionExport(canonical: string, contentHash: string, revision: string): string {
  return `{"exportVersion":"${TEMPLATE_REVISION_EXPORT_VERSION}","identity":{"kind":"templateRevision","templateId":"00000000-0000-4000-8000-000000000099","revision":${revision}},"staticSchemaRef":{"schemaKey":"foreign-schema","versionTag":"foreign-v7"},"contentHash":"${contentHash}","designDsl":${canonical}}`;
}

function importedNumber(widthMm: string): string {
  return `{"dslVersion":"renderweave-design/1.0","expressionProfile":"renderweave-expression/1.0","displayName":"Imported","definitions":[],"designRoot":{"nodeId":"00000000-0000-4000-8000-000000000001","kind":"canvas","widthMm":${widthMm},"heightMm":297,"bindings":[],"children":[]}}`;
}
