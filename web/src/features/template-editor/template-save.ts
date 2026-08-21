import { isLosslessNumber, parse } from 'lossless-json';

import {
  createSessionFromBaseline,
  type CanonicalTemplateBaseline,
  type StructuredEditorSession,
} from './template-editor-model';
import { isCanonicalDirty } from './template-editor-session';
import {
  defaultTemplateEditorTransport,
  parseTemplateCurrentResponse,
  TemplateRequestError,
} from './template-open';

const DESIGN_MEDIA_TYPE = 'application/vnd.renderweave.design+json';
const MAX_REVISION = 9_223_372_036_854_775_807n;
const KNOWN_NO_WRITE_STATUSES = new Set([400, 401, 403, 404, 409, 410, 413, 415, 422]);

export interface TemplateSaveHttpResponse {
  status: number;
  body: string;
}

export interface TemplateSaveTransport {
  getCurrent(templateId: string, signal?: AbortSignal): Promise<string>;
  putCurrent(
    templateId: string,
    expectedRevision: string,
    canonicalDesignDsl: string,
    signal?: AbortSignal,
  ): Promise<TemplateSaveHttpResponse>;
}

export interface TemplateConflictOffer {
  offeredRevision: string;
  draftCanonical: string;
  previewGeneration: number;
}

export type TemplateSaveResult =
  | { state: 'saved'; session: StructuredEditorSession }
  | { state: 'conflict'; offer: TemplateConflictOffer; message: string }
  | { state: 'offer-invalidated'; message: string }
  | { state: 'rejected'; code: string; message: string }
  | { state: 'unknown'; message: string };

export const defaultTemplateSaveTransport: TemplateSaveTransport = {
  getCurrent(templateId, signal) {
    return defaultTemplateEditorTransport.getCurrent(templateId, signal);
  },
  async putCurrent(templateId, expectedRevision, canonicalDesignDsl, signal) {
    const response = await fetch(
      `/api/v1/templates/${encodeURIComponent(templateId)}`
        + `?expectedRevision=${encodeURIComponent(expectedRevision)}`,
      {
        method: 'PUT',
        signal,
        headers: {
          Accept: 'application/json',
          'Content-Type': DESIGN_MEDIA_TYPE,
        },
        body: canonicalDesignDsl,
      },
    );
    return { status: response.status, body: await response.text() };
  },
};

export async function saveTemplateWorkingCopy(
  session: StructuredEditorSession,
  transport: TemplateSaveTransport,
  signal?: AbortSignal,
): Promise<TemplateSaveResult> {
  if (!isCanonicalDirty(session)) {
    return rejected('TEMPLATE_EDITOR_CLEAN', 'Canonical current 没有本地变化，无需保存。');
  }
  if (!hasSuccessor(session.baseline.revision)) {
    return rejected(
      'TEMPLATE_REVISION_EXHAUSTED',
      '当前 revision 不能再追加后继版本。',
    );
  }

  let response: TemplateSaveHttpResponse;
  try {
    response = await transport.putCurrent(
      session.baseline.templateId,
      session.baseline.revision,
      session.workingCopy.canonicalDesignDsl,
      signal,
    );
  } catch {
    return unknownMutation();
  }
  return interpretMutationResponse(
    session,
    session.baseline.revision,
    response,
  );
}

export async function confirmTemplateOverwrite(
  session: StructuredEditorSession,
  offer: TemplateConflictOffer,
  transport: TemplateSaveTransport,
  signal?: AbortSignal,
): Promise<TemplateSaveResult> {
  if (!offerMatchesSession(offer, session)) {
    return {
      state: 'offer-invalidated',
      message: '本地草稿已变化；旧覆盖确认已失效，请重新保存。',
    };
  }

  let current: CanonicalTemplateBaseline;
  try {
    current = await parseTemplateCurrentResponse(
      await transport.getCurrent(session.baseline.templateId, signal),
    );
  } catch (error) {
    if (currentReadIsRetryable(error)) {
      return {
        state: 'conflict',
        offer,
        message: '暂时无法重读 current；尚未发出覆盖写入，可稍后再次确认。',
      };
    }
    if (error instanceof TemplateRequestError) {
      return rejected(error.code, '无法重读可覆盖的 Template current；本地草稿已保留。');
    }
    return rejected(
      'TEMPLATE_CURRENT_UNTRUSTED',
      '重读 current 未通过完整性校验；未发出覆盖写入。',
    );
  }

  if (!samePermanentIdentity(current, session.baseline)) {
    return rejected(
      'TEMPLATE_CURRENT_IDENTITY_MISMATCH',
      '重读 current 的 Template 或 StaticSchema 身份不一致；未发出覆盖写入。',
    );
  }
  if (current.revision !== offer.offeredRevision) {
    if (!hasSuccessor(current.revision)) {
      return rejected(
        'TEMPLATE_REVISION_EXHAUSTED',
        '远端 current 已没有可追加的后继 revision。',
      );
    }
    return {
      state: 'conflict',
      offer: offerFor(session, current.revision),
      message: `current 已前进到 revision ${current.revision}，必须重新确认覆盖。`,
    };
  }
  if (!hasSuccessor(current.revision)) {
    return rejected(
      'TEMPLATE_REVISION_EXHAUSTED',
      '远端 current 已没有可追加的后继 revision。',
    );
  }

  let response: TemplateSaveHttpResponse;
  try {
    response = await transport.putCurrent(
      session.baseline.templateId,
      current.revision,
      session.workingCopy.canonicalDesignDsl,
      signal,
    );
  } catch {
    return unknownMutation();
  }
  return interpretMutationResponse(session, current.revision, response);
}

async function interpretMutationResponse(
  session: StructuredEditorSession,
  expectedRevision: string,
  response: TemplateSaveHttpResponse,
): Promise<TemplateSaveResult> {
  if (response.status === 200) {
    return verifiedSuccess(session, expectedRevision, response.body);
  }

  const problem = parseProblem(response.body);
  if (
    response.status === 409
    && problem.code === 'TEMPLATE_REVISION_CONFLICT'
    && problem.currentRevision !== null
  ) {
    if (!hasSuccessor(problem.currentRevision)) {
      return rejected(
        'TEMPLATE_REVISION_EXHAUSTED',
        '远端 current 已没有可追加的后继 revision。',
      );
    }
    return {
      state: 'conflict',
      offer: offerFor(session, problem.currentRevision),
      message: `远端 current 已是 revision ${problem.currentRevision}；覆盖前需要显式确认。`,
    };
  }

  if (KNOWN_NO_WRITE_STATUSES.has(response.status)) {
    return rejected(
      problem.code,
      '服务器明确拒绝本次写入；canonical 本地草稿已保留。',
    );
  }
  return unknownMutation();
}

async function verifiedSuccess(
  session: StructuredEditorSession,
  expectedRevision: string,
  body: string,
): Promise<TemplateSaveResult> {
  let baseline: CanonicalTemplateBaseline;
  try {
    baseline = await parseTemplateCurrentResponse(body);
  } catch {
    return unknownMutation();
  }
  const expectedNext = successor(expectedRevision);
  if (
    expectedNext === null
    || baseline.revision !== expectedNext
    || !samePermanentIdentity(baseline, session.baseline)
    || baseline.canonicalDesignDsl !== session.workingCopy.canonicalDesignDsl
    || (baseline.persistedReadiness !== 'READY' && baseline.persistedReadiness !== 'INVALID')
  ) {
    return unknownMutation();
  }
  const next = createSessionFromBaseline(baseline, {
    state: 'checked',
    value: baseline.persistedReadiness,
  });
  if (next.mode !== 'structured') return unknownMutation();
  return { state: 'saved', session: next };
}

function offerFor(
  session: StructuredEditorSession,
  offeredRevision: string,
): TemplateConflictOffer {
  return {
    offeredRevision,
    draftCanonical: session.workingCopy.canonicalDesignDsl,
    previewGeneration: session.previewGeneration,
  };
}

function offerMatchesSession(
  offer: TemplateConflictOffer,
  session: StructuredEditorSession,
): boolean {
  return offer.draftCanonical === session.workingCopy.canonicalDesignDsl
    && offer.previewGeneration === session.previewGeneration
    && isCanonicalDirty(session);
}

function samePermanentIdentity(
  current: CanonicalTemplateBaseline,
  baseline: CanonicalTemplateBaseline,
): boolean {
  return current.templateId === baseline.templateId
    && current.staticSchema.schemaKey === baseline.staticSchema.schemaKey
    && current.staticSchema.versionTag === baseline.staticSchema.versionTag;
}

function hasSuccessor(revision: string): boolean {
  const value = revisionValue(revision);
  return value !== null && value < MAX_REVISION;
}

function successor(revision: string): string | null {
  const value = revisionValue(revision);
  return value !== null && value < MAX_REVISION ? String(value + 1n) : null;
}

function revisionValue(revision: string): bigint | null {
  if (!/^(0|[1-9][0-9]*)$/.test(revision)) return null;
  try {
    const value = BigInt(revision);
    return value <= MAX_REVISION ? value : null;
  } catch {
    return null;
  }
}

function parseProblem(body: string): { code: string; currentRevision: string | null } {
  try {
    const value = parse(body);
    if (!isRecord(value)) return { code: 'UNEXPECTED_RESPONSE', currentRevision: null };
    const code = typeof value.code === 'string' ? value.code : 'UNEXPECTED_RESPONSE';
    return {
      code,
      currentRevision: nonNegativeIntegerToken(value.currentRevision),
    };
  } catch {
    return { code: 'UNEXPECTED_RESPONSE', currentRevision: null };
  }
}

function nonNegativeIntegerToken(value: unknown): string | null {
  const token = isLosslessNumber(value)
    ? value.toString()
    : typeof value === 'number' ? String(value) : '';
  return revisionValue(token) === null ? null : token;
}

function currentReadIsRetryable(error: unknown): boolean {
  return error instanceof TypeError
    || (error instanceof TemplateRequestError && error.status === 503);
}

function rejected(code: string, message: string): TemplateSaveResult {
  return { state: 'rejected', code, message };
}

function unknownMutation(): TemplateSaveResult {
  return {
    state: 'unknown',
    message: '保存请求的结果不明；本地草稿已锁定，需由 E5 reconciliation 核验 current 后再继续。',
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
