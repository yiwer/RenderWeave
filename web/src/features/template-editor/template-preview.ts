import { parse } from 'lossless-json';

import type { StructuredEditorSession } from './template-editor-model';
import { authoritativePreviewGuard } from './template-editor-session';

export const RENDER_INPUT_MEDIA_TYPE =
  'application/vnd.renderweave.render-input+json;version=1.0';
export const RENDER_PROBLEM_MEDIA_TYPE =
  'application/vnd.renderweave.render-problem+json;version=1.0';
export const DEFAULT_TEMPLATE_PREVIEW_INPUT = '{"rootDocument":{}}';
export const MAX_TEMPLATE_PREVIEW_INPUT_BYTES = 8 * 1024 * 1024;

const RESULT_CONTRACT_VERSION = 'renderweave-render-result/1.0';
const PROBLEM_CONTRACT_VERSION = 'renderweave-render-problem/1.0';
const RENDER_DSL_VERSION = 'renderweave-render/1.0';
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const CONTENT_DIGEST = /^sha-256=:([A-Za-z0-9+/]+={0,2}):$/;
const RENDER_PROBLEM_STATUSES = new Set([400, 403, 404, 409, 413, 415, 422, 500, 502, 503, 504]);
const CLOSED_STAGES = new Set<TemplatePreviewStage>([
  'REQUEST_ADMISSION',
  'TEMPLATE_CLOSURE',
  'INPUT_ADMISSION',
  'ASSET_ADMISSION',
  'CAPABILITY_STATE',
  'MATERIALIZATION',
  'ASSET_RESOLUTION',
  'DOCUMENT_SEAL',
  'ENGINE',
]);
const encoder = new TextEncoder();

export type TemplatePreviewFormat = 'PNG' | 'JPEG';
export type TemplatePreviewStage =
  | 'REQUEST_ADMISSION'
  | 'TEMPLATE_CLOSURE'
  | 'INPUT_ADMISSION'
  | 'ASSET_ADMISSION'
  | 'CAPABILITY_STATE'
  | 'MATERIALIZATION'
  | 'ASSET_RESOLUTION'
  | 'DOCUMENT_SEAL'
  | 'ENGINE';

export interface TemplatePreviewRequest {
  readonly inputJson: string;
  readonly format: TemplatePreviewFormat;
  readonly dpi: number;
  readonly quality?: number;
}

export interface TemplatePreviewHttpResponse {
  readonly status: number;
  readonly headers: Pick<Headers, 'get'>;
  readonly body: Uint8Array;
}

export interface TemplatePreviewTransport {
  postPreview(
    templateId: string,
    request: TemplatePreviewRequest,
    signal?: AbortSignal,
  ): Promise<TemplatePreviewHttpResponse>;
}

export interface TemplatePreviewObjectUrlFactory {
  create(bytes: Uint8Array, mediaType: 'image/png' | 'image/jpeg'): string;
  revoke(url: string): void;
}

export interface TemplatePreviewBasis {
  readonly templateId: string;
  readonly revision: string;
  readonly contentHash: string;
  readonly previewGeneration: number;
  readonly inputByteLength: number;
  readonly inputSha256: string;
  readonly format: TemplatePreviewFormat;
  readonly dpi: number;
  readonly quality?: number;
}

export interface TemplatePreviewMetadata {
  readonly contractVersion: typeof RESULT_CONTRACT_VERSION;
  readonly renderOperationId: string;
  readonly rendererProfile: string;
  readonly dslVersion: typeof RENDER_DSL_VERSION;
  readonly layoutProfile: string;
  readonly outputProfile: string;
  readonly format: TemplatePreviewFormat;
  readonly widthPx: number;
  readonly heightPx: number;
  readonly dpi: number;
  readonly quality?: number;
}

export interface TemplatePreviewProblem {
  readonly source: 'client' | 'server';
  readonly code: string;
  readonly message: string;
  readonly stage?: TemplatePreviewStage;
  readonly renderOperationId?: string;
  readonly safeLocation?: string;
  readonly limitId?: string;
}

export type TemplatePreviewResult =
  | {
    readonly state: 'rendered';
    readonly basis: TemplatePreviewBasis;
    readonly bytes: Uint8Array;
    readonly mediaType: 'image/png' | 'image/jpeg';
    readonly metadata: TemplatePreviewMetadata;
  }
  | {
    readonly state: 'problem';
    readonly problem: TemplatePreviewProblem;
    readonly basis?: TemplatePreviewBasis;
  };

export const defaultTemplatePreviewTransport: TemplatePreviewTransport = {
  async postPreview(templateId, request, signal) {
    const query = new URLSearchParams({
      format: request.format,
      dpi: String(request.dpi),
    });
    if (request.quality !== undefined) query.set('quality', String(request.quality));
    const response = await fetch(
      `/api/v1/templates/${encodeURIComponent(templateId)}/authoritative-preview?${query}`,
      {
        method: 'POST',
        signal,
        headers: {
          Accept: `image/png, image/jpeg, ${RENDER_PROBLEM_MEDIA_TYPE}`,
          'Content-Type': RENDER_INPUT_MEDIA_TYPE,
        },
        body: request.inputJson,
      },
    );
    return {
      status: response.status,
      headers: response.headers,
      body: new Uint8Array(await response.arrayBuffer()),
    };
  },
};

export const defaultTemplatePreviewObjectUrls: TemplatePreviewObjectUrlFactory = {
  create(bytes, mediaType) {
    const stableBytes = new Uint8Array(bytes);
    return URL.createObjectURL(new Blob([stableBytes], { type: mediaType }));
  },
  revoke(url) {
    URL.revokeObjectURL(url);
  },
};

export async function requestAuthoritativeTemplatePreview(
  session: StructuredEditorSession,
  requested: TemplatePreviewRequest,
  transport: TemplatePreviewTransport,
  signal?: AbortSignal,
): Promise<TemplatePreviewResult> {
  const guard = authoritativePreviewGuard(session);
  if (guard.state === 'blocked') {
    return clientProblem(
      guard.reason === 'LOCAL_DIVERGENCE'
        ? 'EDITOR_PREVIEW_CURRENT_REQUIRED'
        : 'EDITOR_PREVIEW_READINESS_REQUIRED',
      guard.message,
    );
  }

  const admitted = admitRequest(requested);
  if (admitted.state === 'problem') return admitted;
  if (!globalThis.crypto?.subtle) {
    return clientProblem(
      'EDITOR_PREVIEW_CRYPTO_UNAVAILABLE',
      '当前浏览器无法核验权威图片摘要；未发起预览。',
    );
  }

  let inputSha256: string;
  try {
    inputSha256 = await sha256Hex(admitted.inputBytes);
  } catch {
    return clientProblem(
      'EDITOR_PREVIEW_CRYPTO_UNAVAILABLE',
      '当前浏览器无法核验权威图片摘要；未发起预览。',
    );
  }
  const basis: TemplatePreviewBasis = Object.freeze({
    templateId: session.baseline.templateId,
    revision: session.baseline.revision,
    contentHash: session.baseline.contentHash,
    previewGeneration: session.previewGeneration,
    inputByteLength: admitted.inputBytes.byteLength,
    inputSha256,
    format: admitted.request.format,
    dpi: admitted.request.dpi,
    ...(admitted.request.quality === undefined ? {} : { quality: admitted.request.quality }),
  });

  let response: TemplatePreviewHttpResponse;
  try {
    response = await transport.postPreview(
      session.baseline.templateId,
      admitted.request,
      signal,
    );
  } catch (error) {
    if (isAbort(error) || signal?.aborted) throw error;
    return clientProblem(
      'EDITOR_PREVIEW_TRANSPORT_FAILURE',
      '权威预览传输失败；未保留旧图片，可重新发起。',
      basis,
    );
  }

  if (response.status === 200) {
    return verifyRenderedResponse(response, basis);
  }
  return verifyProblemResponse(response, basis);
}

export function previewBasisMatchesSession(
  basis: TemplatePreviewBasis,
  session: StructuredEditorSession,
): boolean {
  return authoritativePreviewGuard(session).state === 'eligible'
    && basis.templateId === session.baseline.templateId
    && basis.revision === session.baseline.revision
    && basis.contentHash === session.baseline.contentHash
    && basis.previewGeneration === session.previewGeneration;
}

function admitRequest(requested: TemplatePreviewRequest):
  | { state: 'admitted'; request: TemplatePreviewRequest; inputBytes: Uint8Array }
  | Extract<TemplatePreviewResult, { state: 'problem' }> {
  if (typeof requested.inputJson !== 'string') {
    return clientProblem('EDITOR_PREVIEW_INPUT_REQUIRED', '请输入一份 strict RenderInput JSON。');
  }
  const inputBytes = encoder.encode(requested.inputJson);
  if (inputBytes.byteLength === 0) {
    return clientProblem('EDITOR_PREVIEW_INPUT_REQUIRED', '请输入一份 strict RenderInput JSON。');
  }
  if (inputBytes.byteLength > MAX_TEMPLATE_PREVIEW_INPUT_BYTES) {
    return clientProblem(
      'EDITOR_PREVIEW_INPUT_TOO_LARGE',
      'RenderInput JSON 超过 8 MiB 上限；未发起预览。',
    );
  }
  if (requested.format !== 'PNG' && requested.format !== 'JPEG') {
    return clientProblem('EDITOR_PREVIEW_FORMAT_INVALID', '权威预览只接受 PNG 或 JPEG。');
  }
  if (!Number.isInteger(requested.dpi) || requested.dpi < 1 || requested.dpi > 600) {
    return clientProblem('EDITOR_PREVIEW_DPI_INVALID', 'DPI 必须是 1 到 600 的整数。');
  }
  if (requested.format === 'PNG' && requested.quality !== undefined) {
    return clientProblem('EDITOR_PREVIEW_QUALITY_INVALID', 'PNG 不接受 JPEG quality。');
  }
  const quality = requested.format === 'JPEG' ? requested.quality ?? 90 : undefined;
  if (quality !== undefined && (!Number.isInteger(quality) || quality < 1 || quality > 100)) {
    return clientProblem('EDITOR_PREVIEW_QUALITY_INVALID', 'JPEG quality 必须是 1 到 100 的整数。');
  }
  return {
    state: 'admitted',
    request: Object.freeze({
      inputJson: requested.inputJson,
      format: requested.format,
      dpi: requested.dpi,
      ...(quality === undefined ? {} : { quality }),
    }),
    inputBytes,
  };
}

async function verifyRenderedResponse(
  response: TemplatePreviewHttpResponse,
  basis: TemplatePreviewBasis,
): Promise<TemplatePreviewResult> {
  try {
    const mediaType = requiredHeader(response, 'Content-Type');
    const expectedMediaType = basis.format === 'PNG' ? 'image/png' : 'image/jpeg';
    if (mediaType !== expectedMediaType || response.body.byteLength === 0) throw integrityFault();

    const length = positiveIntegerHeader(response, 'Content-Length');
    if (length !== response.body.byteLength) throw integrityFault();
    const digestHeader = requiredHeader(response, 'Content-Digest');
    const digestMatch = CONTENT_DIGEST.exec(digestHeader);
    if (!digestMatch || digestMatch[1] !== await sha256Base64(response.body)) throw integrityFault();

    if (requiredHeader(response, 'RenderWeave-Result-Version') !== RESULT_CONTRACT_VERSION) {
      throw integrityFault();
    }
    const renderOperationId = requiredHeader(response, 'RenderWeave-Request-Id');
    if (!UUID_V4.test(renderOperationId)) throw integrityFault();
    const rendererProfile = nonBlankHeader(response, 'RenderWeave-Renderer-Profile');
    const dslVersion = requiredHeader(response, 'RenderWeave-DSL-Version');
    if (dslVersion !== RENDER_DSL_VERSION) throw integrityFault();
    const layoutProfile = nonBlankHeader(response, 'RenderWeave-Layout-Profile');
    const outputProfile = nonBlankHeader(response, 'RenderWeave-Output-Profile');
    const format = requiredHeader(response, 'RenderWeave-Format');
    if (format !== basis.format) throw integrityFault();
    const widthPx = positiveIntegerHeader(response, 'RenderWeave-Width-Px');
    const heightPx = positiveIntegerHeader(response, 'RenderWeave-Height-Px');
    const dpi = rangedIntegerHeader(response, 'RenderWeave-DPI', 1, 600);
    if (dpi !== basis.dpi) throw integrityFault();

    const qualityHeader = response.headers.get('RenderWeave-Quality');
    let quality: number | undefined;
    if (basis.format === 'JPEG') {
      quality = rangedIntegerToken(qualityHeader, 1, 100);
      if (quality !== basis.quality) throw integrityFault();
    } else if (qualityHeader !== null) {
      throw integrityFault();
    }

    const metadata: TemplatePreviewMetadata = Object.freeze({
      contractVersion: RESULT_CONTRACT_VERSION,
      renderOperationId,
      rendererProfile,
      dslVersion: RENDER_DSL_VERSION,
      layoutProfile,
      outputProfile,
      format: basis.format,
      widthPx,
      heightPx,
      dpi,
      ...(quality === undefined ? {} : { quality }),
    });
    return {
      state: 'rendered',
      basis,
      bytes: new Uint8Array(response.body),
      mediaType: expectedMediaType,
      metadata,
    };
  } catch {
    return clientProblem(
      'EDITOR_PREVIEW_RESPONSE_INTEGRITY_FAILURE',
      '权威预览响应未通过完整 length、digest 与 metadata 核验；图片已撤下。',
      basis,
    );
  }
}

function verifyProblemResponse(
  response: TemplatePreviewHttpResponse,
  basis: TemplatePreviewBasis,
): TemplatePreviewResult {
  try {
    if (!RENDER_PROBLEM_STATUSES.has(response.status)) throw integrityFault();
    if (requiredHeader(response, 'Content-Type') !== RENDER_PROBLEM_MEDIA_TYPE) {
      throw integrityFault();
    }
    const text = new TextDecoder('utf-8', { fatal: true }).decode(response.body);
    const value = parse(text, null, {
      onDuplicateKey: () => {
        throw integrityFault();
      },
    });
    if (!isRecord(value)) throw integrityFault();
    exactKeys(value, [
      'contractVersion',
      'code',
      'stage',
      'parameters',
    ], ['renderOperationId', 'safeLocation']);
    if (value.contractVersion !== PROBLEM_CONTRACT_VERSION
      || typeof value.code !== 'string'
      || value.code.length < 1
      || value.code.length > 128
      || typeof value.stage !== 'string'
      || !CLOSED_STAGES.has(value.stage as TemplatePreviewStage)) {
      throw integrityFault();
    }
    const renderOperationId = optionalString(value.renderOperationId, 36);
    if (renderOperationId !== undefined && !UUID_V4.test(renderOperationId)) {
      throw integrityFault();
    }
    const safeLocation = optionalString(value.safeLocation, 1_024);
    const parameters = value.parameters;
    if (!isRecord(parameters)) throw integrityFault();
    exactKeys(parameters, [], ['limitId']);
    const limitId = optionalString(parameters.limitId, 256);
    return {
      state: 'problem',
      basis,
      problem: Object.freeze({
        source: 'server',
        code: value.code,
        stage: value.stage as TemplatePreviewStage,
        message: `权威预览失败（${value.code}）；没有保留旧图片。`,
        ...(renderOperationId === undefined ? {} : { renderOperationId }),
        ...(safeLocation === undefined ? {} : { safeLocation }),
        ...(limitId === undefined ? {} : { limitId }),
      }),
    };
  } catch {
    return clientProblem(
      'EDITOR_PREVIEW_RESPONSE_INTEGRITY_FAILURE',
      '权威预览失败响应不符合 closed contract；图片已撤下。',
      basis,
    );
  }
}

function clientProblem(
  code: string,
  message: string,
  basis?: TemplatePreviewBasis,
): Extract<TemplatePreviewResult, { state: 'problem' }> {
  return {
    state: 'problem',
    ...(basis === undefined ? {} : { basis }),
    problem: Object.freeze({ source: 'client', code, message }),
  };
}

function requiredHeader(response: TemplatePreviewHttpResponse, name: string): string {
  const value = response.headers.get(name);
  if (value === null || value.length === 0) throw integrityFault();
  return value;
}

function nonBlankHeader(response: TemplatePreviewHttpResponse, name: string): string {
  const value = requiredHeader(response, name);
  if (value.trim().length === 0) throw integrityFault();
  return value;
}

function positiveIntegerHeader(response: TemplatePreviewHttpResponse, name: string): number {
  return rangedIntegerToken(requiredHeader(response, name), 1, Number.MAX_SAFE_INTEGER);
}

function rangedIntegerHeader(
  response: TemplatePreviewHttpResponse,
  name: string,
  minimum: number,
  maximum: number,
): number {
  return rangedIntegerToken(requiredHeader(response, name), minimum, maximum);
}

function rangedIntegerToken(
  value: string | null,
  minimum: number,
  maximum: number,
): number {
  if (value === null || !/^(0|[1-9][0-9]*)$/.test(value)) throw integrityFault();
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw integrityFault();
  }
  return parsed;
}

function optionalString(value: unknown, maximumLength: number): string | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || value.length < 1 || value.length > maximumLength) {
    throw integrityFault();
  }
  return value;
}

function exactKeys(
  value: Record<string, unknown>,
  required: readonly string[],
  optional: readonly string[],
) {
  const allowed = new Set([...required, ...optional]);
  if (required.some((key) => !Object.hasOwn(value, key))
    || Object.keys(value).some((key) => !allowed.has(key))) {
    throw integrityFault();
  }
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', digestSource(bytes)));
  return `sha256:${Array.from(
    digest,
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')}`;
}

async function sha256Base64(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', digestSource(bytes)));
  return btoa(String.fromCharCode(...digest));
}

function digestSource(bytes: Uint8Array): ArrayBuffer {
  const copy = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(copy).set(bytes);
  return copy;
}

function integrityFault(): Error {
  return new Error('preview response integrity failure');
}

function isAbort(error: unknown): boolean {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && error.name === 'AbortError';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
