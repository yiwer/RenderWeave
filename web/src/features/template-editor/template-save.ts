import { isLosslessNumber, parse } from 'lossless-json';

import type { TemplateValidationIssue } from '../../api/generated';

import {
  createSessionFromBaseline,
  type CanonicalTemplateBaseline,
  type StructuredEditorSession,
} from './template-editor-model';
import { isCanonicalDirty } from './template-editor-session';
import {
  defaultTemplateEditorTransport,
  parseTemplateCurrentResponse,
  templateContentHashOf,
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
    confirmationToken?: string,
  ): Promise<TemplateSaveHttpResponse>;
}

export interface TemplateConflictOffer {
  offeredRevision: string;
  draftCanonical: string;
  previewGeneration: number;
}

export interface TemplateInvalidSaveOffer {
  confirmationToken: string;
  expectedRevision: string;
  draftCanonical: string;
  previewGeneration: number;
  proposedContentHash: string;
  expiresAt: string;
  problems: TemplateValidationIssue[];
  truncated: false;
}

export type TemplateSaveResult =
  | { state: 'saved'; session: StructuredEditorSession }
  | { state: 'conflict'; offer: TemplateConflictOffer; message: string }
  | { state: 'invalid-save-confirmation'; offer: TemplateInvalidSaveOffer; message: string }
  | { state: 'offer-invalidated'; code: string; message: string }
  | { state: 'rejected'; code: string; message: string }
  | { state: 'unknown'; message: string };

export const defaultTemplateSaveTransport: TemplateSaveTransport = {
  getCurrent(templateId, signal) {
    return defaultTemplateEditorTransport.getCurrent(templateId, signal);
  },
  async putCurrent(
    templateId,
    expectedRevision,
    canonicalDesignDsl,
    signal,
    confirmationToken,
  ) {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      'Content-Type': DESIGN_MEDIA_TYPE,
    };
    if (confirmationToken !== undefined) {
      headers['X-Confirmation-Token'] = confirmationToken;
    }
    const response = await fetch(
      `/api/v1/templates/${encodeURIComponent(templateId)}`
        + `?expectedRevision=${encodeURIComponent(expectedRevision)}`,
      {
        method: 'PUT',
        signal,
        headers,
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
      code: 'TEMPLATE_OVERWRITE_OFFER_INVALIDATED',
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

export async function confirmTemplateInvalidSave(
  session: StructuredEditorSession,
  offer: TemplateInvalidSaveOffer,
  transport: TemplateSaveTransport,
  signal?: AbortSignal,
): Promise<TemplateSaveResult> {
  if (!invalidSaveOfferMatchesSession(offer, session)) {
    return {
      state: 'offer-invalidated',
      code: 'TEMPLATE_INVALID_SAVE_OFFER_INVALIDATED',
      message: '本地草稿、revision 或 preview generation 已变化；旧 INVALID 确认已失效。',
    };
  }
  if (Date.parse(offer.expiresAt) <= Date.now()) {
    return {
      state: 'offer-invalidated',
      code: 'TEMPLATE_CONFIRMATION_EXPIRED',
      message: 'INVALID 保存确认已过期；请重新保存并审阅新的完整问题集。',
    };
  }

  let response: TemplateSaveHttpResponse;
  try {
    response = await transport.putCurrent(
      session.baseline.templateId,
      offer.expectedRevision,
      session.workingCopy.canonicalDesignDsl,
      signal,
      offer.confirmationToken,
    );
  } catch {
    return unknownMutation();
  }
  if (response.status === 200) {
    return verifiedSuccess(
      session,
      offer.expectedRevision,
      response.body,
      'INVALID',
    );
  }
  return interpretMutationResponse(session, offer.expectedRevision, response);
}

async function interpretMutationResponse(
  session: StructuredEditorSession,
  expectedRevision: string,
  response: TemplateSaveHttpResponse,
): Promise<TemplateSaveResult> {
  if (response.status === 200) {
    return verifiedSuccess(session, expectedRevision, response.body, 'READY');
  }

  const problem = parseProblem(response.body);
  if (response.status === 422 && isInvalidSaveOfferCode(problem.code)) {
    const offer = await invalidSaveOfferFor(session, expectedRevision, problem.value);
    if (offer !== null) {
      return {
        state: 'invalid-save-confirmation',
        offer,
        message: problem.code === 'TEMPLATE_CONFIRMATION_STALE'
          ? '依赖事实已变化；请审阅新的完整问题集，再次确认仍保存为 INVALID。'
          : '依赖检查发现可确认的 ERROR；审阅完整问题集后可选择仍保存为 INVALID。',
      };
    }
    return rejected(
      problem.code,
      '服务端未返回可验证的完整确认凭据；本次写入未发生。',
    );
  }
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
  requiredReadiness: 'READY' | 'INVALID',
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
    || baseline.persistedReadiness !== requiredReadiness
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

function invalidSaveOfferMatchesSession(
  offer: TemplateInvalidSaveOffer,
  session: StructuredEditorSession,
): boolean {
  return offer.expectedRevision === session.baseline.revision
    && offer.draftCanonical === session.workingCopy.canonicalDesignDsl
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

interface ParsedProblem {
  code: string;
  currentRevision: string | null;
  value: Record<string, unknown> | null;
}

function parseProblem(body: string): ParsedProblem {
  try {
    const value = parse(body);
    if (!isRecord(value)) {
      return { code: 'UNEXPECTED_RESPONSE', currentRevision: null, value: null };
    }
    const code = typeof value.code === 'string' ? value.code : 'UNEXPECTED_RESPONSE';
    return {
      code,
      currentRevision: nonNegativeIntegerToken(value.currentRevision),
      value,
    };
  } catch {
    return { code: 'UNEXPECTED_RESPONSE', currentRevision: null, value: null };
  }
}

function isInvalidSaveOfferCode(code: string): boolean {
  return code === 'TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED'
    || code === 'TEMPLATE_CONFIRMATION_STALE';
}

async function invalidSaveOfferFor(
  session: StructuredEditorSession,
  expectedRevision: string,
  value: Record<string, unknown> | null,
): Promise<TemplateInvalidSaveOffer | null> {
  if (value === null
    || typeof value.confirmationToken !== 'string'
    || !/^[0-9a-f]{64}$/.test(value.confirmationToken)
    || typeof value.proposedContentHash !== 'string'
    || !/^sha256:[0-9a-f]{64}$/.test(value.proposedContentHash)
    || typeof value.expiresAt !== 'string'
    || !Number.isFinite(Date.parse(value.expiresAt))
    || Date.parse(value.expiresAt) <= Date.now()
    || value.truncated !== false
    || !Array.isArray(value.problems)
    || value.problems.length === 0
    || value.problems.length > 200) {
    return null;
  }
  const problems: TemplateValidationIssue[] = [];
  for (const candidate of value.problems) {
    const problem = validationIssue(candidate);
    if (problem === null || problem.category !== 'DEPENDENCY') return null;
    problems.push(problem);
  }
  if (await templateContentHashOf(session.workingCopy.canonicalDesignDsl)
    !== value.proposedContentHash) {
    return null;
  }
  return {
    confirmationToken: value.confirmationToken,
    expectedRevision,
    draftCanonical: session.workingCopy.canonicalDesignDsl,
    previewGeneration: session.previewGeneration,
    proposedContentHash: value.proposedContentHash,
    expiresAt: value.expiresAt,
    problems,
    truncated: false,
  };
}

function validationIssue(value: unknown): TemplateValidationIssue | null {
  if (!isRecord(value)
    || typeof value.code !== 'string'
    || value.code.length === 0
    || value.code.length > 128
    || (value.category !== 'DEPENDENCY'
      && value.category !== 'HARD'
      && value.category !== 'LIMIT')
    || value.severity !== 'ERROR'
    || typeof value.canonicalPointer !== 'string'
    || value.canonicalPointer.length > 2048
    || !Array.isArray(value.messageArgs)
    || value.messageArgs.length > 32
    || value.messageArgs.some((argument) =>
      typeof argument !== 'string' || argument.length > 512)) {
    return null;
  }
  return {
    code: value.code,
    category: value.category,
    severity: value.severity,
    canonicalPointer: value.canonicalPointer,
    messageArgs: value.messageArgs as string[],
  };
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
