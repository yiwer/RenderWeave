import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  defaultTemplatePreviewTransport,
  localCandidateTemplatePreviewTransport,
  previewBasisMatchesSession,
  requestAuthoritativeTemplatePreview,
  requestTemplatePreview,
  RENDER_INPUT_MEDIA_TYPE,
  RENDER_PROBLEM_MEDIA_TYPE,
  type TemplatePreviewHttpResponse,
  type TemplatePreviewTransport,
} from './template-preview';
import { createSessionFromBaseline, type StructuredEditorSession } from './template-editor-model';
import { applyTemplateDisplayName } from './template-editor-session';
import { structuredBaseline } from './template-editor-test-support';

const OPERATION_ID = '123e4567-e89b-42d3-a456-426614174000';
const RAW_INPUT = '{"rootDocument":{"amount":1,"amount":2}}';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('Authoritative Preview transport and integrity boundary', () => {
  it('uses the public preview route and preserves the exact raw body in the default fetch transport', async () => {
    const responseBody = new Uint8Array([1, 2, 3]);
    const fetchMock = vi.fn().mockResolvedValue({
      status: 422,
      headers: new Headers({ 'Content-Type': RENDER_PROBLEM_MEDIA_TYPE }),
      arrayBuffer: vi.fn().mockResolvedValue(responseBody.buffer),
    });
    vi.stubGlobal('fetch', fetchMock);
    const signal = new AbortController().signal;

    const response = await defaultTemplatePreviewTransport.postPreview('template/id', {
      inputJson: RAW_INPUT,
      format: 'JPEG',
      dpi: 144,
      quality: 82,
    }, signal);

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/v1/templates/template%2Fid/authoritative-preview?format=JPEG&dpi=144&quality=82');
    expect(init).toMatchObject({ method: 'POST', signal, body: RAW_INPUT });
    expect(new Headers(init.headers).get('Content-Type')).toBe(RENDER_INPUT_MEDIA_TYPE);
    expect(new Headers(init.headers).get('Accept')).toBe(
      `image/png, image/jpeg, ${RENDER_PROBLEM_MEDIA_TYPE}`,
    );
    expect(response).toMatchObject({ status: 422, body: responseBody });
  });

  it('uses a distinct internal route only for the explicit local candidate transport', async () => {
    const responseBody = new Uint8Array([1, 2, 3]);
    const fetchMock = vi.fn().mockResolvedValue({
      status: 422,
      headers: new Headers({
        'Content-Type': RENDER_PROBLEM_MEDIA_TYPE,
        'RenderWeave-Candidate-Status': 'NOT_CERTIFIED',
      }),
      arrayBuffer: vi.fn().mockResolvedValue(responseBody.buffer),
    });
    vi.stubGlobal('fetch', fetchMock);

    await localCandidateTemplatePreviewTransport.postPreview('template/id', {
      inputJson: RAW_INPUT,
      format: 'PNG',
      dpi: 96,
    });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(localCandidateTemplatePreviewTransport.assurance).toBe('candidate');
    expect(url).toBe('/internal/candidate-preview/templates/template%2Fid?format=PNG&dpi=96');
    expect(init).toMatchObject({ method: 'POST', body: RAW_INPUT });
  });

  it('sends the exact raw RenderInput bytes and only the public output selection', async () => {
    const postPreview = vi.fn().mockResolvedValue(problemResponse({
      contractVersion: 'renderweave-render-problem/1.0',
      renderOperationId: OPERATION_ID,
      code: 'RENDER_INPUT_JSON_INVALID',
      stage: 'INPUT_ADMISSION',
      safeLocation: '/rootDocument',
      parameters: {},
    }));

    const result = await requestAuthoritativeTemplatePreview(cleanSession(), {
      inputJson: RAW_INPUT,
      format: 'JPEG',
      dpi: 144,
      quality: 82,
    }, transport(postPreview));

    expect(postPreview).toHaveBeenCalledWith(
      structuredBaseline().templateId,
      {
        inputJson: RAW_INPUT,
        format: 'JPEG',
        dpi: 144,
        quality: 82,
      },
      undefined,
    );
    expect(result.state).toBe('problem');
    if (result.state !== 'problem') throw new Error('expected problem');
    expect(result.problem).toMatchObject({
      source: 'server',
      code: 'RENDER_INPUT_JSON_INVALID',
      stage: 'INPUT_ADMISSION',
      renderOperationId: OPERATION_ID,
      safeLocation: '/rootDocument',
    });
  });

  it.each([
    [{ inputJson: '', format: 'PNG' as const, dpi: 96 }, 'EDITOR_PREVIEW_INPUT_REQUIRED'],
    [{ inputJson: '{"rootDocument":{}}', format: 'PNG' as const, dpi: 0 }, 'EDITOR_PREVIEW_DPI_INVALID'],
    [{ inputJson: '{"rootDocument":{}}', format: 'PNG' as const, dpi: 96, quality: 90 }, 'EDITOR_PREVIEW_QUALITY_INVALID'],
    [{ inputJson: '{"rootDocument":{}}', format: 'JPEG' as const, dpi: 96, quality: 101 }, 'EDITOR_PREVIEW_QUALITY_INVALID'],
  ])('rejects invalid local settings before any HTTP call', async (settings, code) => {
    const postPreview = vi.fn();
    const result = await requestAuthoritativeTemplatePreview(
      cleanSession(),
      settings,
      transport(postPreview),
    );

    expect(result).toMatchObject({ state: 'problem', problem: { source: 'client', code } });
    expect(postPreview).not.toHaveBeenCalled();
  });

  it('enforces the 8 MiB entity boundary before HTTP', async () => {
    const postPreview = vi.fn();
    const result = await requestAuthoritativeTemplatePreview(cleanSession(), {
      inputJson: 'x'.repeat((8 * 1024 * 1024) + 1),
      format: 'PNG',
      dpi: 96,
    }, transport(postPreview));

    expect(result).toMatchObject({
      state: 'problem',
      problem: { code: 'EDITOR_PREVIEW_INPUT_TOO_LARGE' },
    });
    expect(postPreview).not.toHaveBeenCalled();
  });

  it('accepts one complete image only after every safe header and digest agrees', async () => {
    const body = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3]);
    const response = await renderedResponse(body, {
      'RenderWeave-Request-Id': OPERATION_ID,
      'RenderWeave-Format': 'PNG',
      'RenderWeave-DPI': '144',
    });

    const result = await requestAuthoritativeTemplatePreview(cleanSession(), {
      inputJson: '{"rootDocument":{}}',
      format: 'PNG',
      dpi: 144,
    }, transport(vi.fn().mockResolvedValue(response)));

    expect(result.state).toBe('rendered');
    if (result.state !== 'rendered') throw new Error('expected rendered');
    expect(result.bytes).toEqual(body);
    expect(result.mediaType).toBe('image/png');
    expect(result.metadata).toEqual({
      contractVersion: 'renderweave-render-result/1.0',
      renderOperationId: OPERATION_ID,
      rendererProfile: 'renderweave-renderer/test-certified',
      dslVersion: 'renderweave-render/1.0',
      layoutProfile: 'renderweave-layout/1.0',
      outputProfile: 'renderweave-output-png/1.0',
      format: 'PNG',
      widthPx: 794,
      heightPx: 1123,
      dpi: 144,
    });
    expect(result.basis).toMatchObject({
      revision: '7',
      contentHash: structuredBaseline().contentHash,
      previewGeneration: 0,
      inputByteLength: new TextEncoder().encode('{"rootDocument":{}}').byteLength,
      format: 'PNG',
      dpi: 144,
    });
    expect(result.basis.inputSha256).toMatch(/^sha256:[0-9a-f]{64}$/);
    expect(previewBasisMatchesSession(result.basis, cleanSession())).toBe(true);
  });

  it('defaults JPEG quality to 90 and verifies JPEG-only media and metadata', async () => {
    const body = new Uint8Array([255, 216, 255, 217]);
    const postPreview = vi.fn().mockResolvedValue(await renderedResponse(body, {
      'Content-Type': 'image/jpeg',
      'RenderWeave-Output-Profile': 'renderweave-output-jpeg/1.0',
      'RenderWeave-Format': 'JPEG',
      'RenderWeave-Quality': '90',
    }));

    const result = await requestAuthoritativeTemplatePreview(cleanSession(), {
      inputJson: '{"rootDocument":{}}',
      format: 'JPEG',
      dpi: 96,
    }, transport(postPreview));

    expect(postPreview).toHaveBeenCalledWith(
      structuredBaseline().templateId,
      { inputJson: '{"rootDocument":{}}', format: 'JPEG', dpi: 96, quality: 90 },
      undefined,
    );
    expect(result).toMatchObject({
      state: 'rendered',
      mediaType: 'image/jpeg',
      basis: { format: 'JPEG', quality: 90 },
      metadata: { format: 'JPEG', quality: 90, outputProfile: 'renderweave-output-jpeg/1.0' },
    });
  });

  it.each([
    ['PNG' as const, new Uint8Array([137, 80, 78, 71, 1]), {}],
    ['JPEG' as const, new Uint8Array([255, 216, 255, 217]), {
      'Content-Type': 'image/jpeg',
      'RenderWeave-Output-Profile': 'renderweave-output-jpeg/1.0',
      'RenderWeave-Format': 'JPEG',
      'RenderWeave-Quality': '90',
    }],
  ])('accepts a %s candidate only with the exact non-certification header', async (
    format,
    body,
    formatHeaders,
  ) => {
    const request = {
      inputJson: '{"rootDocument":{}}',
      format,
      dpi: 96,
      ...(format === 'JPEG' ? { quality: 90 } : {}),
    };
    const complete = await renderedResponse(body, {
      ...formatHeaders,
      'RenderWeave-Candidate-Status': 'NOT_CERTIFIED',
    });
    const accepted = await requestTemplatePreview(
      cleanSession(),
      request,
      transport(vi.fn().mockResolvedValue(complete), 'candidate'),
    );
    expect(accepted).toMatchObject({
      state: 'rendered',
      basis: { assurance: 'candidate', format },
    });

    const missingDisclosure = await requestTemplatePreview(
      cleanSession(),
      request,
      transport(
        vi.fn().mockResolvedValue(await renderedResponse(body, formatHeaders)),
        'candidate',
      ),
    );
    expect(missingDisclosure).toMatchObject({
      state: 'problem',
      problem: { code: 'EDITOR_PREVIEW_RESPONSE_INTEGRITY_FAILURE' },
    });
  });

  it.each([
    ['digest mismatch', async (body: Uint8Array) => renderedResponse(body, { 'Content-Digest': 'sha-256=:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=:' })],
    ['truncated body', async (body: Uint8Array) => renderedResponse(body, { 'Content-Length': String(body.byteLength + 1) })],
    ['profile metadata drift', async (body: Uint8Array) => renderedResponse(body, { 'RenderWeave-DPI': '300' })],
    ['PNG quality leakage', async (body: Uint8Array) => renderedResponse(body, { 'RenderWeave-Quality': '90' })],
  ])('fails closed on %s', async (_label, responseFactory) => {
    const body = new Uint8Array([137, 80, 78, 71, 1]);
    const result = await requestAuthoritativeTemplatePreview(cleanSession(), {
      inputJson: '{"rootDocument":{}}',
      format: 'PNG',
      dpi: 96,
    }, transport(vi.fn().mockResolvedValue(await responseFactory(body))));

    expect(result).toMatchObject({
      state: 'problem',
      problem: { source: 'client', code: 'EDITOR_PREVIEW_RESPONSE_INTEGRITY_FAILURE' },
    });
  });

  it('does not accept malformed or wrong-media server failures', async () => {
    const result = await requestAuthoritativeTemplatePreview(cleanSession(), {
      inputJson: '{"rootDocument":{}}',
      format: 'PNG',
      dpi: 96,
    }, transport(vi.fn().mockResolvedValue({
      status: 503,
      headers: new Headers({ 'Content-Type': 'application/json' }),
      body: new TextEncoder().encode(JSON.stringify({ code: 'RENDERER_UNAVAILABLE' })),
    })));

    expect(result).toMatchObject({
      state: 'problem',
      problem: { source: 'client', code: 'EDITOR_PREVIEW_RESPONSE_INTEGRITY_FAILURE' },
    });
  });

  it.each([
    [
      'duplicate members',
      `{"contractVersion":"renderweave-render-problem/1.0","code":"A","code":"B","stage":"ENGINE","parameters":{}}`,
      503,
    ],
    [
      'an extra Engine identity',
      `{"contractVersion":"renderweave-render-problem/1.0","code":"A","stage":"ENGINE","parameters":{},"engineRequestId":"secret"}`,
      503,
    ],
    [
      'a status outside the public closed response set',
      `{"contractVersion":"renderweave-render-problem/1.0","code":"A","stage":"ENGINE","parameters":{}}`,
      302,
    ],
  ])('fails closed when a server problem contains %s', async (_label, body, status) => {
    const result = await requestAuthoritativeTemplatePreview(cleanSession(), {
      inputJson: '{"rootDocument":{}}',
      format: 'PNG',
      dpi: 96,
    }, transport(vi.fn().mockResolvedValue({
      status,
      headers: new Headers({ 'Content-Type': RENDER_PROBLEM_MEDIA_TYPE }),
      body: new TextEncoder().encode(body),
    })));

    expect(result).toMatchObject({
      state: 'problem',
      problem: { source: 'client', code: 'EDITOR_PREVIEW_RESPONSE_INTEGRITY_FAILURE' },
    });
  });

  it('does not issue HTTP when Web Crypto digest authority is unavailable', async () => {
    const postPreview = vi.fn();
    vi.stubGlobal('crypto', undefined);

    const result = await requestAuthoritativeTemplatePreview(cleanSession(), {
      inputJson: '{"rootDocument":{}}',
      format: 'PNG',
      dpi: 96,
    }, transport(postPreview));

    expect(result).toMatchObject({
      state: 'problem',
      problem: { source: 'client', code: 'EDITOR_PREVIEW_CRYPTO_UNAVAILABLE' },
    });
    expect(postPreview).not.toHaveBeenCalled();
  });

  it('blocks dirty or non-READY sessions and invalidates a basis on current/generation drift', async () => {
    const postPreview = vi.fn();
    const dirty = dirtySession();
    const blocked = await requestAuthoritativeTemplatePreview(dirty, {
      inputJson: '{"rootDocument":{}}',
      format: 'PNG',
      dpi: 96,
    }, transport(postPreview));
    expect(blocked).toMatchObject({
      state: 'problem',
      problem: { code: 'EDITOR_PREVIEW_CURRENT_REQUIRED' },
    });
    expect(postPreview).not.toHaveBeenCalled();

    const body = new Uint8Array([137, 80, 78, 71, 1]);
    const rendered = await requestAuthoritativeTemplatePreview(cleanSession(), {
      inputJson: '{"rootDocument":{}}', format: 'PNG', dpi: 96,
    }, transport(vi.fn().mockResolvedValue(await renderedResponse(body))));
    if (rendered.state !== 'rendered') throw new Error('expected rendered');
    expect(previewBasisMatchesSession(rendered.basis, dirty)).toBe(false);
    const advancedBaseline = structuredBaseline();
    advancedBaseline.revision = '8';
    const advanced = createSessionFromBaseline(
      advancedBaseline,
      { state: 'checked', value: 'READY' },
    );
    if (advanced.mode !== 'structured') throw new Error('expected structured');
    expect(previewBasisMatchesSession(rendered.basis, advanced)).toBe(false);
  });
});

function transport(
  postPreview: TemplatePreviewTransport['postPreview'],
  assurance?: TemplatePreviewTransport['assurance'],
): TemplatePreviewTransport {
  return { postPreview, ...(assurance === undefined ? {} : { assurance }) };
}

function cleanSession(): StructuredEditorSession {
  const session = createSessionFromBaseline(
    structuredBaseline(),
    { state: 'checked', value: 'READY' },
  );
  if (session.mode !== 'structured') throw new Error('expected structured');
  return session;
}

function dirtySession(): StructuredEditorSession {
  const result = applyTemplateDisplayName(cleanSession(), '本地草稿');
  if (result.state !== 'applied') throw new Error('expected applied');
  return result.session;
}

function problemResponse(problem: unknown): TemplatePreviewHttpResponse {
  return {
    status: 422,
    headers: new Headers({
      'Content-Type': 'application/vnd.renderweave.render-problem+json;version=1.0',
    }),
    body: new TextEncoder().encode(JSON.stringify(problem)),
  };
}

async function renderedResponse(
  body: Uint8Array,
  overrides: Record<string, string> = {},
): Promise<TemplatePreviewHttpResponse> {
  const digestBytes = new ArrayBuffer(body.byteLength);
  new Uint8Array(digestBytes).set(body);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', digestBytes));
  const headers = new Headers({
    'Content-Type': 'image/png',
    'Content-Length': String(body.byteLength),
    'Content-Digest': `sha-256=:${base64(digest)}:`,
    'RenderWeave-Result-Version': 'renderweave-render-result/1.0',
    'RenderWeave-Request-Id': OPERATION_ID,
    'RenderWeave-Renderer-Profile': 'renderweave-renderer/test-certified',
    'RenderWeave-DSL-Version': 'renderweave-render/1.0',
    'RenderWeave-Layout-Profile': 'renderweave-layout/1.0',
    'RenderWeave-Output-Profile': 'renderweave-output-png/1.0',
    'RenderWeave-Format': 'PNG',
    'RenderWeave-Width-Px': '794',
    'RenderWeave-Height-Px': '1123',
    'RenderWeave-DPI': '96',
    ...overrides,
  });
  return { status: 200, headers, body };
}

function base64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}
