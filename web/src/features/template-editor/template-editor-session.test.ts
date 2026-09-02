import { parse } from 'lossless-json';
import { describe, expect, it } from 'vitest';

import {
  applyTemplateDisplayName,
  adoptStructuredTemplateImport,
  authoritativePreviewGuard,
  canonicalStringifyWorkingValue,
  isCanonicalDirty,
  redoStructuredCommand,
  undoStructuredCommand,
  updateStructuredReadiness,
} from './template-editor-session';
import type { StructuredTemplateImport } from './template-import';
import {
  createSessionFromBaseline,
  type CanonicalTemplateBaseline,
  type StructuredEditorSession,
  templateDisplayName,
} from './template-editor-model';
import { structuredBaseline } from './template-editor-test-support';

const JAVA_VECTOR_CANONICAL = '{"definitions":[],"designRoot":{"bindings":[],"bleed":{"bottomMm":1,"leftMm":2.5,"rightMm":0,"topMm":0},"children":[],"displayName":"画布 é","heightMm":297,"kind":"canvas","nodeId":"00000000-0000-4000-8000-000000000001","widthMm":210},"displayName":"A/会员 é","dslVersion":"renderweave-design/1.0","expressionProfile":"renderweave-expression/1.0"}';

describe('Template Editor E2 canonical local session', () => {
  it('uses Java UTF-8 member order and keeps lossless decimal/int64 tokens', () => {
    const value = parse('{"𐀀":9223372036854775807,"z":2.5,"":0}') as Record<string, unknown>;

    expect(canonicalStringifyWorkingValue(value)).toBe(
      '{"z":2.5,"":0,"𐀀":9223372036854775807}',
    );
  });

  it('edits only the top-level displayName over a canonical Java vector', () => {
    const session = structuredSession(baselineFromCanonical(JAVA_VECTOR_CANONICAL));

    const result = applyTemplateDisplayName(session, '\u0000\t  新门店 😀  \r\n');

    expect(result.state).toBe('applied');
    expect(templateDisplayName(result.session.workingCopy)).toBe('新门店 😀');
    expect(result.session.workingCopy.canonicalDesignDsl).toBe(
      JAVA_VECTOR_CANONICAL.replace('"displayName":"A/会员 é"', '"displayName":"新门店 😀"'),
    );
    expect(result.session.baseline.canonicalDesignDsl).toBe(JAVA_VECTOR_CANONICAL);
    expect(isCanonicalDirty(result.session)).toBe(true);
  });

  it('matches the Java canonical writer for escaped display-name scalars', () => {
    const session = structuredSession(baselineFromCanonical(JAVA_VECTOR_CANONICAL));

    const result = applied(applyTemplateDisplayName(
      session,
      'A"\\/\b\f\n\r\t\u0001Z\u2028😀',
    ));

    expect(result.workingCopy.canonicalDesignDsl).toContain(
      '"displayName":"A\\"\\\\/\\b\\f\\n\\r\\t\\u0001Z' + '\u2028' + '😀"',
    );
  });

  it('matches Java String.trim boundaries and validates 1..128 Unicode code points', () => {
    const session = structuredSession();

    const nbsp = applyTemplateDisplayName(session, '\u00a0门店\u00a0');
    expect(nbsp.state).toBe('applied');
    expect(templateDisplayName(nbsp.session.workingCopy)).toBe('\u00a0门店\u00a0');
    const nbspOnly = applyTemplateDisplayName(session, '\u00a0');
    expect(nbspOnly.state).toBe('applied');
    expect(templateDisplayName(nbspOnly.session.workingCopy)).toBe('\u00a0');

    expect(applyTemplateDisplayName(session, '\u0000\t\r\n ')).toEqual({
      state: 'invalid',
      session,
      reason: 'DISPLAY_NAME_REQUIRED',
      message: 'Template 名称不能为空。',
    });
    expect(applyTemplateDisplayName(session, '😀'.repeat(129))).toEqual({
      state: 'invalid',
      session,
      reason: 'DISPLAY_NAME_TOO_LONG',
      message: 'Template 名称最多 128 个 Unicode 字符。',
    });
    expect(applyTemplateDisplayName(session, '\ud800')).toEqual({
      state: 'invalid',
      session,
      reason: 'DISPLAY_NAME_INVALID_UNICODE',
      message: 'Template 名称包含无效 Unicode。',
    });

    const maximum = applyTemplateDisplayName(session, '😀'.repeat(128));
    expect(maximum.state).toBe('applied');
  });

  it('derives dirty from canonical bytes and leaves no-op edits out of history', () => {
    const session = structuredSession();

    const noOp = applyTemplateDisplayName(session, ' \t门店价签\r\n');

    expect(noOp).toEqual({ state: 'no-op', session });
    expect(noOp.session.history.past).toHaveLength(0);
    expect(noOp.session.previewGeneration).toBe(0);
    expect(isCanonicalDirty(noOp.session)).toBe(false);
  });

  it('undoes and redoes structural commands while invalidating each prior preview generation', () => {
    const initial = structuredSession();
    const first = applied(applyTemplateDisplayName(initial, '第一版'));
    const second = applied(applyTemplateDisplayName(first, '第二版'));

    expect(second.previewGeneration).toBe(2);
    expect(second.history.past).toHaveLength(2);
    expect(second.history.future).toHaveLength(0);

    const undoSecond = undoStructuredCommand(second);
    expect(templateDisplayName(undoSecond.workingCopy)).toBe('第一版');
    expect(undoSecond.previewGeneration).toBe(3);
    expect(undoSecond.history.future).toHaveLength(1);

    const undoFirst = undoStructuredCommand(undoSecond);
    expect(templateDisplayName(undoFirst.workingCopy)).toBe('门店价签');
    expect(undoFirst.previewGeneration).toBe(4);
    expect(isCanonicalDirty(undoFirst)).toBe(false);

    const redoFirst = redoStructuredCommand(undoFirst);
    expect(templateDisplayName(redoFirst.workingCopy)).toBe('第一版');
    expect(redoFirst.previewGeneration).toBe(5);

    const branch = applied(applyTemplateDisplayName(redoFirst, '分支版'));
    expect(branch.history.future).toHaveLength(0);
    expect(redoStructuredCommand(branch)).toBe(branch);
  });

  it('bounds history at 100 structural commands', () => {
    let session = structuredSession();
    for (let index = 1; index <= 101; index += 1) {
      session = applied(applyTemplateDisplayName(session, `版本 ${index}`));
    }

    expect(session.history.past).toHaveLength(100);
    for (let index = 0; index < 100; index += 1) {
      session = undoStructuredCommand(session);
    }
    expect(templateDisplayName(session.workingCopy)).toBe('版本 1');
    expect(undoStructuredCommand(session)).toBe(session);
  });

  it('adopts a Structured import as a history-free local working copy without changing target authority', () => {
    const original = applied(applyTemplateDisplayName(structuredSession(), '旧本地草稿'));
    const importedCanonical = original.baseline.canonicalDesignDsl.replace(
      '"displayName":"门店价签"',
      '"displayName":"导入草稿"',
    );
    const imported = structuredImport(importedCanonical, {
      ...original.baseline.designDsl,
      displayName: '导入草稿',
    });

    const result = adoptStructuredTemplateImport(original, imported);

    expect(result.state).toBe('adopted');
    expect(result.session.baseline).toBe(original.baseline);
    expect(result.session.baseline.templateId).toBe(original.baseline.templateId);
    expect(result.session.baseline.staticSchema).toEqual({ schemaKey: 'system-empty', versionTag: 'v1' });
    expect(result.session.workingCopy.canonicalDesignDsl).toBe(importedCanonical);
    expect(templateDisplayName(result.session.workingCopy)).toBe('导入草稿');
    expect(result.session.history).toEqual({ past: [], future: [] });
    expect(Object.isFrozen(result.session.workingCopy.designDsl)).toBe(true);
    expect(Object.isFrozen(result.session.history.past)).toBe(true);
    expect(result.session.previewGeneration).toBe(original.previewGeneration + 1);
    expect(isCanonicalDirty(result.session)).toBe(true);
  });

  it('makes a same-working-copy import a no-op, but lets explicit replacement of a dirty draft with baseline become clean', () => {
    const clean = structuredSession();
    const same = adoptStructuredTemplateImport(
      clean,
      structuredImport(clean.workingCopy.canonicalDesignDsl, clean.workingCopy.designDsl),
    );
    expect(same).toEqual({ state: 'no-op', session: clean });

    const dirty = applied(applyTemplateDisplayName(clean, '将被放弃'));
    const replaced = adoptStructuredTemplateImport(
      dirty,
      structuredImport(clean.baseline.canonicalDesignDsl, clean.baseline.designDsl),
    );
    expect(replaced.state).toBe('adopted');
    expect(isCanonicalDirty(replaced.session)).toBe(false);
    expect(replaced.session.history).toEqual({ past: [], future: [] });
    expect(replaced.session.previewGeneration).toBe(dirty.previewGeneration + 1);
  });

  it('allows authoritative preview only for a clean current with fresh READY readiness', () => {
    const ready = structuredSession();
    expect(authoritativePreviewGuard(ready)).toEqual({
      state: 'eligible',
      generation: 0,
    });

    const dirty = applied(applyTemplateDisplayName(ready, '本地草稿'));
    expect(authoritativePreviewGuard(dirty)).toEqual(expect.objectContaining({
      state: 'blocked', reason: 'LOCAL_DIVERGENCE', generation: 1,
    }));

    const clean = undoStructuredCommand(dirty);
    expect(authoritativePreviewGuard(clean)).toEqual({
      state: 'eligible',
      generation: 2,
    });

    expect(authoritativePreviewGuard(updateStructuredReadiness(clean, { state: 'checking' })))
      .toEqual(expect.objectContaining({ state: 'blocked', reason: 'READINESS_CHECKING' }));
    expect(authoritativePreviewGuard(updateStructuredReadiness(clean, {
      state: 'unavailable', message: 'offline',
    }))).toEqual(expect.objectContaining({ state: 'blocked', reason: 'READINESS_UNAVAILABLE' }));
    expect(authoritativePreviewGuard(updateStructuredReadiness(clean, {
      state: 'checked', value: 'INVALID',
    }))).toEqual(expect.objectContaining({ state: 'blocked', reason: 'READINESS_INVALID' }));
  });
});

function structuredSession(
  baseline: CanonicalTemplateBaseline = structuredBaseline(),
): StructuredEditorSession {
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  expect(session.workingCopy.designDsl).not.toBe(session.baseline.designDsl);
  expect(Object.isFrozen(session.baseline.designDsl)).toBe(true);
  expect(Object.isFrozen(session.workingCopy.designDsl)).toBe(true);
  expect(Object.isFrozen(session.history.past)).toBe(true);
  expect(Object.isFrozen(session.history.future)).toBe(true);
  return session;
}

function baselineFromCanonical(canonicalDesignDsl: string): CanonicalTemplateBaseline {
  return {
    ...structuredBaseline(),
    canonicalDesignDsl,
    designDsl: parse(canonicalDesignDsl) as Record<string, unknown>,
  };
}

function applied(result: ReturnType<typeof applyTemplateDisplayName>): StructuredEditorSession {
  if (result.state !== 'applied') throw new Error(`expected applied, got ${result.state}`);
  return result.session;
}

function structuredImport(
  canonicalDesignDsl: string,
  designDsl: Record<string, unknown>,
): StructuredTemplateImport {
  return {
    mode: 'structured',
    source: 'template-revision-export',
    canonicalDesignDsl,
    designDsl,
    contentHash: `sha256:${'a'.repeat(64)}`,
    sourceIdentity: {
      kind: 'templateRevision',
      templateId: 'foreign-template',
      revision: '9223372036854775807',
    },
    sourceStaticSchema: { schemaKey: 'foreign-schema', versionTag: 'foreign-v7' },
  };
}
