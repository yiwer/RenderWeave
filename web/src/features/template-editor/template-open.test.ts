import { describe, expect, it, vi } from 'vitest';

import {
  openTemplateEditor,
  parseReadinessRecheckResponse,
  parseTemplateCurrentResponse,
  templateContentHashOf,
  TemplateIntegrityError,
  TemplateRequestError,
  type TemplateEditorTransport,
} from './template-open';
import {
  CANONICAL_DESIGN,
  CONTENT_HASH,
  currentResponse,
  recheckResponse,
  TEMPLATE_ID,
} from './template-editor-test-support';

describe('Template Editor canonical open boundary', () => {
  it('preserves an int64 revision and verifies the domain-separated canonical content hash', async () => {
    const baseline = await parseTemplateCurrentResponse(currentResponse('9007199254740993'));

    expect(baseline.revision).toBe('9007199254740993');
    expect(baseline.canonicalDesignDsl).toBe(CANONICAL_DESIGN);
    expect(baseline.contentHash).toBe(CONTENT_HASH);
    expect(baseline.persistedReadiness).toBe('STALE');
  });

  it('fails closed when the canonical DesignDSL does not match contentHash', async () => {
    await expect(parseTemplateCurrentResponse(
      currentResponse('7').replace('API template', 'tampered'),
    )).rejects.toThrow(/contentHash/i);
  });

  it('parses only a current-identity-bound READY or INVALID recheck result', () => {
    expect(parseReadinessRecheckResponse(recheckResponse('7', 'INVALID'))).toEqual({
      templateId: TEMPLATE_ID,
      revision: '7',
      contentHash: CONTENT_HASH,
      readiness: 'INVALID',
    });
    expect(() => parseReadinessRecheckResponse(recheckResponse('7', 'STALE'))).toThrow(/readiness/i);
  });

  it('drops a drifted recheck result and reopens against the newer trusted current', async () => {
    const transport: TemplateEditorTransport = {
      getCurrent: vi.fn()
        .mockResolvedValueOnce(currentResponse('7'))
        .mockResolvedValueOnce(currentResponse('8')),
      recheckCurrent: vi.fn()
        .mockResolvedValueOnce(recheckResponse('8', 'READY'))
        .mockResolvedValueOnce(recheckResponse('8', 'READY')),
    };
    const observedBaselines: string[] = [];

    const session = await openTemplateEditor(
      TEMPLATE_ID,
      transport,
      (baseline) => observedBaselines.push(baseline.revision),
    );

    expect(observedBaselines).toEqual(['7', '8']);
    expect(session.baseline.revision).toBe('8');
    expect(session.readiness).toEqual({ state: 'checked', value: 'READY' });
    expect(transport.getCurrent).toHaveBeenCalledTimes(2);
    expect(transport.recheckCurrent).toHaveBeenCalledTimes(2);
  });

  it('keeps a trusted baseline and marks readiness unavailable when recheck fails', async () => {
    const transport: TemplateEditorTransport = {
      getCurrent: vi.fn().mockResolvedValue(currentResponse('7')),
      recheckCurrent: vi.fn().mockRejectedValue(new TypeError('offline')),
    };

    const session = await openTemplateEditor(TEMPLATE_ID, transport);

    expect(session.baseline.revision).toBe('7');
    expect(session.readiness.state).toBe('unavailable');
    expect(session.mode).toBe('structured');
  });

  it('keeps a trusted baseline when the explicit authority reports 503', async () => {
    const transport: TemplateEditorTransport = {
      getCurrent: vi.fn().mockResolvedValue(currentResponse('7')),
      recheckCurrent: vi.fn().mockRejectedValue(
        new TemplateRequestError(503, 'TEMPLATE_DEPENDENCY_UNAVAILABLE'),
      ),
    };

    const session = await openTemplateEditor(TEMPLATE_ID, transport);

    expect(session.readiness.state).toBe('unavailable');
    expect(session.mode).toBe('structured');
  });

  it('fails closed when a successful recheck response is malformed', async () => {
    const transport: TemplateEditorTransport = {
      getCurrent: vi.fn().mockResolvedValue(currentResponse('7')),
      recheckCurrent: vi.fn().mockResolvedValue('{"templateId":'),
    };

    await expect(openTemplateEditor(TEMPLATE_ID, transport))
      .rejects.toBeInstanceOf(TemplateIntegrityError);
  });

  it('rejects a malformed current before exposing its baseline or rechecking readiness', async () => {
    const designDsl = JSON.parse(CANONICAL_DESIGN) as Record<string, unknown>;
    delete (designDsl.designRoot as Record<string, unknown>).children;
    const canonicalDesignDsl = JSON.stringify(designDsl);
    const contentHash = await templateContentHashOf(canonicalDesignDsl);
    const current = `{"templateId":"${TEMPLATE_ID}","disclosure":"READABLE","revision":7,`
      + '"staticSchema":{"schemaKey":"system-empty","versionTag":"v1"},'
      + `"contentHash":"${contentHash}","readiness":"STALE","designDsl":${canonicalDesignDsl}}`;
    const onBaseline = vi.fn();
    const transport: TemplateEditorTransport = {
      getCurrent: vi.fn().mockResolvedValue(current),
      recheckCurrent: vi.fn(),
    };

    await expect(openTemplateEditor(TEMPLATE_ID, transport, onBaseline))
      .rejects.toBeInstanceOf(TemplateIntegrityError);
    expect(onBaseline).not.toHaveBeenCalled();
    expect(transport.recheckCurrent).not.toHaveBeenCalled();
  });

  it('does not retain a visible baseline after recheck hides or deletes the Template', async () => {
    const transport: TemplateEditorTransport = {
      getCurrent: vi.fn().mockResolvedValue(currentResponse('7')),
      recheckCurrent: vi.fn().mockRejectedValue(
        new TemplateRequestError(404, 'TEMPLATE_NOT_FOUND'),
      ),
    };

    await expect(openTemplateEditor(TEMPLATE_ID, transport)).rejects.toMatchObject({
      status: 404,
      code: 'TEMPLATE_NOT_FOUND',
    });
  });
});
