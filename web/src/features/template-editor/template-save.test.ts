import { afterEach, describe, expect, it, vi } from 'vitest';

import { applyTemplateDisplayName } from './template-editor-session';
import type { StructuredEditorSession } from './template-editor-model';
import { createSessionFromBaseline } from './template-editor-model';
import { structuredBaseline } from './template-editor-test-support';
import {
  confirmTemplateOverwrite,
  defaultTemplateSaveTransport,
  saveTemplateWorkingCopy,
  type TemplateConflictOffer,
  type TemplateSaveTransport,
} from './template-save';

describe('Template Editor E3 save coordinator', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sends the exact int64 token and canonical body through the real transport boundary', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"code":"conflict"}', {
      status: 409,
      headers: { 'Content-Type': 'application/problem+json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await defaultTemplateSaveTransport.putCurrent(
      'template / one',
      '9007199254740993',
      '{"displayName":"精确"}',
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/templates/template%20%2F%20one?expectedRevision=9007199254740993',
      expect.objectContaining({
        method: 'PUT',
        body: '{"displayName":"精确"}',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/vnd.renderweave.design+json',
        },
      }),
    );
  });

  it('does not issue a mutation for a clean baseline or an exhausted int64 revision', async () => {
    const transport = transportWith({ putCurrent: vi.fn() });

    const clean = await saveTemplateWorkingCopy(cleanSession('7'), transport);
    const exhausted = await saveTemplateWorkingCopy(
      editedSession('9223372036854775807', '无法追加'),
      transport,
    );

    expect(clean).toEqual(expect.objectContaining({
      state: 'rejected', code: 'TEMPLATE_EDITOR_CLEAN',
    }));
    expect(exhausted).toEqual(expect.objectContaining({
      state: 'rejected', code: 'TEMPLATE_REVISION_EXHAUSTED',
    }));
    expect(transport.putCurrent).not.toHaveBeenCalled();
  });

  it('adopts a verified +1 readable current and clears local history', async () => {
    const session = editedSession('9007199254740993', '保存版本');
    const transport = transportWith({
      putCurrent: vi.fn().mockResolvedValue({
        status: 200,
        body: await readableResponse(session, '9007199254740994'),
      }),
    });

    const result = await saveTemplateWorkingCopy(session, transport);

    expect(result.state).toBe('saved');
    if (result.state !== 'saved') throw new Error('expected saved');
    expect(transport.putCurrent).toHaveBeenCalledWith(
      session.baseline.templateId,
      '9007199254740993',
      session.workingCopy.canonicalDesignDsl,
      undefined,
    );
    expect(result.session.baseline.revision).toBe('9007199254740994');
    expect(result.session.baseline.canonicalDesignDsl).toBe(
      session.workingCopy.canonicalDesignDsl,
    );
    expect(result.session.workingCopy.canonicalDesignDsl).toBe(
      result.session.baseline.canonicalDesignDsl,
    );
    expect(result.session.history.past).toHaveLength(0);
    expect(result.session.history.future).toHaveLength(0);
    expect(result.session.previewGeneration).toBe(0);
  });

  it('fails closed when a 200 response is opaque, drifted, or not the submitted canonical', async () => {
    const session = editedSession('7', '本地版本');
    const valid = await readableResponse(session, '8');
    const cases = [
      '{"templateId":"opaque","disclosure":"OPAQUE"}',
      await readableResponse(session, '9'),
      await readableResponse(session, '8', canonicalWithName(session, '服务器改写')),
      valid.replace(session.baseline.templateId, 'another-template'),
      valid.replace(session.baseline.staticSchema.schemaKey, 'another-schema'),
      valid.replace('"readiness":"READY"', '"readiness":"STALE"'),
      valid.replace(/sha256:[0-9a-f]{64}/, `sha256:${'0'.repeat(64)}`),
    ];

    for (const body of cases) {
      const result = await saveTemplateWorkingCopy(session, transportWith({
        putCurrent: vi.fn().mockResolvedValue({ status: 200, body }),
      }));
      expect(result).toEqual(expect.objectContaining({ state: 'unknown' }));
    }
  });

  it('turns only a disclosed revision conflict into an overwrite offer bound to the draft', async () => {
    const session = editedSession('7', '冲突草稿');
    const transport = transportWith({
      putCurrent: vi.fn().mockResolvedValue({
        status: 409,
        body: problem('TEMPLATE_REVISION_CONFLICT', '9007199254740995'),
      }),
    });

    const result = await saveTemplateWorkingCopy(session, transport);

    expect(result).toEqual({
      state: 'conflict',
      offer: {
        offeredRevision: '9007199254740995',
        draftCanonical: session.workingCopy.canonicalDesignDsl,
        previewGeneration: session.previewGeneration,
      },
      message: expect.stringContaining('9007199254740995'),
    });

    const hiddenRevision = await saveTemplateWorkingCopy(session, transportWith({
      putCurrent: vi.fn().mockResolvedValue({
        status: 409,
        body: problem('TEMPLATE_REVISION_CONFLICT'),
      }),
    }));
    expect(hiddenRevision).toEqual(expect.objectContaining({ state: 'rejected' }));
  });

  it('re-reads the trusted current before an overwrite PUT and then adopts success', async () => {
    const session = editedSession('7', '覆盖版本');
    const offer = conflictOffer(session, '8');
    const calls: string[] = [];
    const remote = cleanSession('8');
    const transport = transportWith({
      getCurrent: vi.fn().mockImplementation(async () => {
        calls.push('GET');
        return readableResponse(remote, '8');
      }),
      putCurrent: vi.fn().mockImplementation(async () => {
        calls.push('PUT');
        return { status: 200, body: await readableResponse(session, '9') };
      }),
    });

    const result = await confirmTemplateOverwrite(session, offer, transport);

    expect(calls).toEqual(['GET', 'PUT']);
    expect(transport.putCurrent).toHaveBeenCalledWith(
      session.baseline.templateId,
      '8',
      session.workingCopy.canonicalDesignDsl,
      undefined,
    );
    expect(result).toEqual(expect.objectContaining({ state: 'saved' }));
  });

  it('requires another confirmation when current drifts before the confirmed PUT', async () => {
    const session = editedSession('7', '覆盖版本');
    const offer = conflictOffer(session, '8');
    const remote = cleanSession('9');
    const putCurrent = vi.fn();
    const result = await confirmTemplateOverwrite(session, offer, transportWith({
      getCurrent: vi.fn().mockResolvedValue(await readableResponse(remote, '9')),
      putCurrent,
    }));

    expect(putCurrent).not.toHaveBeenCalled();
    expect(result).toEqual(expect.objectContaining({
      state: 'conflict',
      offer: expect.objectContaining({ offeredRevision: '9' }),
      message: expect.stringContaining('重新确认'),
    }));
  });

  it('requires another confirmation when the overwrite PUT itself conflicts', async () => {
    const session = editedSession('7', '覆盖版本');
    const remote = cleanSession('8');
    const result = await confirmTemplateOverwrite(
      session,
      conflictOffer(session, '8'),
      transportWith({
        getCurrent: vi.fn().mockResolvedValue(await readableResponse(remote, '8')),
        putCurrent: vi.fn().mockResolvedValue({
          status: 409,
          body: problem('TEMPLATE_REVISION_CONFLICT', '9'),
        }),
      }),
    );

    expect(result).toEqual(expect.objectContaining({
      state: 'conflict',
      offer: expect.objectContaining({ offeredRevision: '9' }),
    }));
  });

  it('invalidates an offer after any local generation or canonical change without I/O', async () => {
    const session = editedSession('7', '冲突草稿');
    const offer = conflictOffer(session, '8');
    const changed = applied(applyTemplateDisplayName(session, '冲突后的新草稿'));
    const transport = transportWith();

    const result = await confirmTemplateOverwrite(changed, offer, transport);

    expect(result).toEqual(expect.objectContaining({ state: 'offer-invalidated' }));
    expect(transport.getCurrent).not.toHaveBeenCalled();
    expect(transport.putCurrent).not.toHaveBeenCalled();
  });

  it('keeps a conflict offer retryable when the side-effect-free current read is unavailable', async () => {
    const session = editedSession('7', '冲突草稿');
    const offer = conflictOffer(session, '8');
    const result = await confirmTemplateOverwrite(session, offer, transportWith({
      getCurrent: vi.fn().mockRejectedValue(new TypeError('offline')),
    }));

    expect(result).toEqual({
      state: 'conflict',
      offer,
      message: expect.stringContaining('重读 current'),
    });
  });

  it('separates known no-write rejection from an ambiguous mutation outcome', async () => {
    const session = editedSession('7', '待保存');
    for (const status of [400, 401, 403, 404, 409, 410, 413, 415, 422]) {
      const rejected = await saveTemplateWorkingCopy(session, transportWith({
        putCurrent: vi.fn().mockResolvedValue({
          status,
          body: problem('DESIGN_DSL_REJECTED'),
        }),
      }));
      expect(rejected).toEqual(expect.objectContaining({
        state: 'rejected', code: 'DESIGN_DSL_REJECTED',
      }));
    }

    for (const result of [
      await saveTemplateWorkingCopy(session, transportWith({
        putCurrent: vi.fn().mockRejectedValue(new TypeError('connection lost')),
      })),
      await saveTemplateWorkingCopy(session, transportWith({
        putCurrent: vi.fn().mockResolvedValue({
          status: 503,
          body: problem('TEMPLATE_PERSISTENCE_UNAVAILABLE'),
        }),
      })),
      await saveTemplateWorkingCopy(session, transportWith({
        putCurrent: vi.fn().mockResolvedValue({
          status: 500,
          body: problem('INTERNAL_ERROR'),
        }),
      })),
    ]) {
      expect(result).toEqual(expect.objectContaining({ state: 'unknown' }));
    }
  });
});

function cleanSession(revision: string): StructuredEditorSession {
  const baseline = structuredBaseline();
  baseline.revision = revision;
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected structured');
  return session;
}

function editedSession(revision: string, name: string): StructuredEditorSession {
  return applied(applyTemplateDisplayName(cleanSession(revision), name));
}

function applied(result: ReturnType<typeof applyTemplateDisplayName>): StructuredEditorSession {
  if (result.state !== 'applied') throw new Error(`expected applied, got ${result.state}`);
  return result.session;
}

function canonicalWithName(session: StructuredEditorSession, name: string): string {
  const result = applyTemplateDisplayName(session, name);
  if (result.state !== 'applied') throw new Error('expected changed canonical');
  return result.session.workingCopy.canonicalDesignDsl;
}

function conflictOffer(
  session: StructuredEditorSession,
  offeredRevision: string,
): TemplateConflictOffer {
  return {
    offeredRevision,
    draftCanonical: session.workingCopy.canonicalDesignDsl,
    previewGeneration: session.previewGeneration,
  };
}

function transportWith(
  overrides: Partial<TemplateSaveTransport> = {},
): TemplateSaveTransport {
  return {
    getCurrent: vi.fn().mockRejectedValue(new Error('unexpected GET')),
    putCurrent: vi.fn().mockRejectedValue(new Error('unexpected PUT')),
    ...overrides,
  };
}

async function readableResponse(
  session: StructuredEditorSession,
  revision: string,
  canonical = session.workingCopy.canonicalDesignDsl,
): Promise<string> {
  const hash = await contentHash(canonical);
  const { templateId, staticSchema } = session.baseline;
  return `{"templateId":"${templateId}","disclosure":"READABLE","revision":${revision},`
    + `"staticSchema":{"schemaKey":"${staticSchema.schemaKey}","versionTag":"${staticSchema.versionTag}"},`
    + `"contentHash":"${hash}","readiness":"READY","designDsl":${canonical}}`;
}

async function contentHash(canonical: string): Promise<string> {
  const bytes = new TextEncoder().encode('renderweave-design-content/1\0' + canonical);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return `sha256:${Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')}`;
}

function problem(code: string, currentRevision?: string): string {
  return `{"code":"${code}"${currentRevision ? `,"currentRevision":${currentRevision}` : ''}}`;
}
