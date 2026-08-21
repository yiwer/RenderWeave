import { describe, expect, it, vi } from 'vitest';

import {
  createSessionFromBaseline,
  type StructuredEditorSession,
} from './template-editor-model';
import { applyTemplateDisplayName } from './template-editor-session';
import { structuredBaseline } from './template-editor-test-support';
import {
  reconcileTemplateUnknownSave,
} from './template-save-reconciliation';
import {
  confirmTemplateInvalidSave,
  confirmTemplateOverwrite,
  retryTemplateUnknownSave,
  saveTemplateWorkingCopy,
  type TemplateConflictOffer,
  type TemplateInvalidSaveOffer,
  type TemplateSaveTransport,
  type TemplateUnknownSaveAttempt,
} from './template-save';
import { TemplateRequestError } from './template-open';

describe('Template Editor E5 save reconciliation', () => {
  it('does not emit a mutation when the write-time trusted baseline cannot be verified', async () => {
    const session = editedSession('7', 'untrusted baseline draft');
    const corrupted: StructuredEditorSession = {
      ...session,
      baseline: {
        ...session.baseline,
        contentHash: `sha256:${'0'.repeat(64)}`,
      },
    };
    const putCurrent = vi.fn();

    const result = await saveTemplateWorkingCopy(
      corrupted,
      transportWith({ putCurrent }),
    );

    expect(result).toMatchObject({
      state: 'rejected',
      code: 'TEMPLATE_RECONCILIATION_CONTEXT_UNAVAILABLE',
    });
    expect(putCurrent).not.toHaveBeenCalled();
  });

  it('freezes a complete hash-bound attempt before every mutation shape can become unknown', async () => {
    const session = editedSession('7', 'unknown draft');
    const proposedContentHash = await contentHash(session.workingCopy.canonicalDesignDsl);

    const normal = await saveTemplateWorkingCopy(session, transportWith({
      putCurrent: vi.fn().mockRejectedValue(new TypeError('lost')),
    }));
    expect(normal).toMatchObject({
      state: 'unknown',
      attempt: {
        expectedRevision: '7',
        expectedCurrent: {
          templateId: session.baseline.templateId,
          revision: '7',
          contentHash: session.baseline.contentHash,
          canonicalDesignDsl: session.baseline.canonicalDesignDsl,
        },
        draftCanonical: session.workingCopy.canonicalDesignDsl,
        proposedContentHash,
        previewGeneration: session.previewGeneration,
        requiredReadiness: 'READY',
      },
    });

    const remote = cleanSession('8');
    const overwrite = await confirmTemplateOverwrite(
      session,
      conflictOffer(session, '8'),
      transportWith({
        getCurrent: vi.fn().mockResolvedValue(await readableResponse(
          remote,
          '8',
          remote.baseline.canonicalDesignDsl,
        )),
        putCurrent: vi.fn().mockRejectedValue(new TypeError('lost')),
      }),
    );
    expect(overwrite).toMatchObject({
      state: 'unknown',
      attempt: {
        expectedRevision: '8',
        expectedCurrent: { revision: '8', contentHash: remote.baseline.contentHash },
        proposedContentHash,
        requiredReadiness: 'READY',
      },
    });

    const invalidOffer = await invalidSaveOffer(session, 'a'.repeat(64));
    const invalid = await confirmTemplateInvalidSave(
      session,
      invalidOffer,
      transportWith({ putCurrent: vi.fn().mockRejectedValue(new TypeError('lost')) }),
    );
    expect(invalid).toMatchObject({
      state: 'unknown',
      attempt: {
        expectedRevision: '7',
        proposedContentHash,
        requiredReadiness: 'INVALID',
        confirmation: {
          token: 'a'.repeat(64),
          expiresAt: invalidOffer.expiresAt,
        },
      },
    });
  });

  it('adopts only a later trusted current that converges to the exact proposed content', async () => {
    const session = editedSession('7', 'converged draft');
    const attempt = await unknownAttempt(session);

    const result = await reconcileTemplateUnknownSave(attempt, transportWith({
      getCurrent: vi.fn().mockResolvedValue(await readableResponse(
        session,
        '9',
        session.workingCopy.canonicalDesignDsl,
        'INVALID',
      )),
    }));

    expect(result.state).toBe('adopted');
    if (result.state !== 'adopted') throw new Error('expected adopted');
    expect(result.message).toContain('内容已在服务器确认');
    expect(result.message).toContain('不代表具体请求归属');
    expect(result.session.baseline.revision).toBe('9');
    expect(result.session.baseline.canonicalDesignDsl).toBe(attempt.draftCanonical);
    expect(result.session.history.past).toHaveLength(0);
    expect(result.session.previewGeneration).toBe(0);
    expect(result.session.readiness).toEqual({
      state: 'unavailable',
      message: expect.stringContaining('必须重新检查 readiness'),
    });
  });

  it('allows only an explicit exact retry when current still matches the write-time baseline', async () => {
    const session = editedSession('7', 'retry draft');
    const attempt = await unknownAttempt(session);
    const reconciliation = await reconcileTemplateUnknownSave(attempt, transportWith({
      getCurrent: vi.fn().mockResolvedValue(await readableResponse(
        session,
        '7',
        session.baseline.canonicalDesignDsl,
      )),
    }));

    expect(reconciliation).toMatchObject({ state: 'retryable', attempt });
    expect(reconciliation.state === 'retryable' && reconciliation.message).toContain('显式重试');

    const putCurrent = vi.fn().mockResolvedValue({
      status: 200,
      body: await readableResponse(session, '8', session.workingCopy.canonicalDesignDsl),
    });
    const retried = await retryTemplateUnknownSave(
      session,
      attempt,
      transportWith({ putCurrent }),
    );

    expect(putCurrent).toHaveBeenCalledWith(
      session.baseline.templateId,
      '7',
      session.workingCopy.canonicalDesignDsl,
      undefined,
      undefined,
    );
    expect(retried).toMatchObject({ state: 'saved' });
  });

  it('turns a later different current into the existing revision-bound overwrite flow', async () => {
    const session = editedSession('7', 'local conflict draft');
    const attempt = await unknownAttempt(session);
    const remote = editedSession('11', 'another author');

    const result = await reconcileTemplateUnknownSave(attempt, transportWith({
      getCurrent: vi.fn().mockResolvedValue(await readableResponse(
        remote,
        '11',
        remote.workingCopy.canonicalDesignDsl,
      )),
    }));

    expect(result).toEqual({
      state: 'conflict',
      offer: {
        offeredRevision: '11',
        draftCanonical: attempt.draftCanonical,
        previewGeneration: attempt.previewGeneration,
      },
      message: expect.stringContaining('revision 11'),
    });
  });

  it('distinguishes exact terminal deletion from temporary unavailability and other request errors', async () => {
    const attempt = await unknownAttempt(editedSession('7', 'recovery draft'));

    await expect(reconcileTemplateUnknownSave(attempt, transportWith({
      getCurrent: vi.fn().mockRejectedValue(
        new TemplateRequestError(410, 'TEMPLATE_DELETED'),
      ),
    }))).resolves.toMatchObject({ state: 'deleted', attempt });

    for (const error of [
      new TypeError('offline'),
      new TemplateRequestError(503, 'TEMPLATE_PERSISTENCE_UNAVAILABLE'),
    ]) {
      await expect(reconcileTemplateUnknownSave(attempt, transportWith({
        getCurrent: vi.fn().mockRejectedValue(error),
      }))).resolves.toMatchObject({ state: 'unavailable', attempt });
    }

    for (const error of [
      new TemplateRequestError(404, 'TEMPLATE_NOT_FOUND'),
      new TemplateRequestError(410, 'UNEXPECTED_RESPONSE'),
      new TemplateRequestError(500, 'TEMPLATE_INTEGRITY_MISMATCH'),
    ]) {
      await expect(reconcileTemplateUnknownSave(attempt, transportWith({
        getCurrent: vi.fn().mockRejectedValue(error),
      }))).resolves.toMatchObject({ state: 'failed-closed', attempt });
    }
  });

  it('fails closed on rollback, same-revision drift, identity mismatch, malformed current, and exhausted conflict', async () => {
    const session = editedSession('7', 'fail-closed draft');
    const attempt = await unknownAttempt(session);
    const other = editedSession('7', 'different current');
    const max = editedSession('9223372036854775807', 'different max current');
    const cases = [
      await readableResponse(session, '6', session.baseline.canonicalDesignDsl),
      await readableResponse(other, '7', other.workingCopy.canonicalDesignDsl),
      (await readableResponse(session, '8', session.workingCopy.canonicalDesignDsl))
        .replace(session.baseline.templateId, 'another-template'),
      '{"not":"a current"}',
      await readableResponse(max, '9223372036854775807', max.workingCopy.canonicalDesignDsl),
    ];

    for (const body of cases) {
      await expect(reconcileTemplateUnknownSave(attempt, transportWith({
        getCurrent: vi.fn().mockResolvedValue(body),
      }))).resolves.toMatchObject({ state: 'failed-closed', attempt });
    }
  });

  it('retries an unknown INVALID confirmation with the exact token and rejects expiry or tampering before I/O', async () => {
    const session = editedSession('7', 'invalid retry draft');
    const offer = await invalidSaveOffer(session, 'b'.repeat(64));
    const malformedOfferTransport = transportWith({ putCurrent: vi.fn() });
    await expect(confirmTemplateInvalidSave(
      session,
      { ...offer, confirmationToken: 'not-an-opaque-token', expiresAt: 'not-a-date' },
      malformedOfferTransport,
    )).resolves.toMatchObject({
      state: 'offer-invalidated',
      code: 'TEMPLATE_INVALID_SAVE_OFFER_INVALIDATED',
    });
    expect(malformedOfferTransport.putCurrent).not.toHaveBeenCalled();

    const unknown = await confirmTemplateInvalidSave(
      session,
      offer,
      transportWith({ putCurrent: vi.fn().mockRejectedValue(new TypeError('lost')) }),
    );
    if (unknown.state !== 'unknown') throw new Error('expected unknown');

    const putCurrent = vi.fn().mockResolvedValue({
      status: 200,
      body: await readableResponse(
        session,
        '8',
        session.workingCopy.canonicalDesignDsl,
        'INVALID',
      ),
    });
    await expect(retryTemplateUnknownSave(
      session,
      unknown.attempt,
      transportWith({ putCurrent }),
    )).resolves.toMatchObject({ state: 'saved' });
    expect(putCurrent).toHaveBeenCalledWith(
      session.baseline.templateId,
      '7',
      session.workingCopy.canonicalDesignDsl,
      undefined,
      'b'.repeat(64),
    );

    const expired: TemplateUnknownSaveAttempt = {
      ...unknown.attempt,
      confirmation: { token: 'b'.repeat(64), expiresAt: '2000-01-01T00:00:00Z' },
    };
    const noIo = transportWith({ putCurrent: vi.fn() });
    await expect(retryTemplateUnknownSave(session, expired, noIo)).resolves.toMatchObject({
      state: 'offer-invalidated',
      code: 'TEMPLATE_CONFIRMATION_EXPIRED',
    });
    await expect(retryTemplateUnknownSave(
      { ...session, previewGeneration: session.previewGeneration + 1 },
      unknown.attempt,
      noIo,
    )).resolves.toMatchObject({
      state: 'offer-invalidated',
      code: 'TEMPLATE_RECONCILIATION_ATTEMPT_INVALIDATED',
    });
    expect(noIo.putCurrent).not.toHaveBeenCalled();
  });
});

async function unknownAttempt(
  session: StructuredEditorSession,
): Promise<TemplateUnknownSaveAttempt> {
  const result = await saveTemplateWorkingCopy(session, transportWith({
    putCurrent: vi.fn().mockRejectedValue(new TypeError('lost')),
  }));
  if (result.state !== 'unknown') throw new Error('expected unknown');
  return result.attempt;
}

function cleanSession(revision: string): StructuredEditorSession {
  const baseline = structuredBaseline();
  baseline.revision = revision;
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected structured');
  return session;
}

function editedSession(revision: string, name: string): StructuredEditorSession {
  const result = applyTemplateDisplayName(cleanSession(revision), name);
  if (result.state !== 'applied') throw new Error(`expected applied, got ${result.state}`);
  return result.session;
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
  canonical: string,
  readiness: 'READY' | 'INVALID' = 'READY',
): Promise<string> {
  const hash = await contentHash(canonical);
  const { templateId, staticSchema } = session.baseline;
  return `{"templateId":"${templateId}","disclosure":"READABLE","revision":${revision},`
    + `"staticSchema":{"schemaKey":"${staticSchema.schemaKey}","versionTag":"${staticSchema.versionTag}"},`
    + `"contentHash":"${hash}","readiness":"${readiness}","designDsl":${canonical}}`;
}

async function contentHash(canonical: string): Promise<string> {
  const bytes = new TextEncoder().encode('renderweave-design-content/1\0' + canonical);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return `sha256:${Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')}`;
}

async function invalidSaveOffer(
  session: StructuredEditorSession,
  confirmationToken: string,
): Promise<TemplateInvalidSaveOffer> {
  return {
    confirmationToken,
    expectedRevision: session.baseline.revision,
    draftCanonical: session.workingCopy.canonicalDesignDsl,
    previewGeneration: session.previewGeneration,
    proposedContentHash: await contentHash(session.workingCopy.canonicalDesignDsl),
    expiresAt: '2099-01-01T00:00:00Z',
    problems: [{
      code: 'TEMPLATE_USE_FILL_TYPE_MISMATCH',
      category: 'DEPENDENCY',
      severity: 'ERROR',
      canonicalPointer: '/designRoot/children/0/fills/0/source',
      messageArgs: [],
    }],
    truncated: false,
  };
}
