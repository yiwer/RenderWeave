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

export interface TemplateSaveExpectedCurrent {
  readonly templateId: string;
  readonly revision: string;
  readonly staticSchema: {
    readonly schemaKey: string;
    readonly versionTag: string;
  };
  readonly contentHash: string;
  readonly canonicalDesignDsl: string;
}

export interface TemplateUnknownSaveAttempt {
  readonly expectedCurrent: TemplateSaveExpectedCurrent;
  readonly expectedRevision: string;
  readonly draftCanonical: string;
  readonly proposedContentHash: string;
  readonly previewGeneration: number;
  readonly requiredReadiness: 'READY' | 'INVALID';
  readonly confirmation?: {
    readonly token: string;
    readonly expiresAt: string;
  };
}

export type TemplateSaveResult =
  | { state: 'saved'; session: StructuredEditorSession }
  | { state: 'conflict'; offer: TemplateConflictOffer; message: string }
  | { state: 'invalid-save-confirmation'; offer: TemplateInvalidSaveOffer; message: string }
  | { state: 'offer-invalidated'; code: string; message: string }
  | { state: 'rejected'; code: string; message: string }
  | { state: 'unknown'; attempt: TemplateUnknownSaveAttempt; message: string };

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

  const attempt = await prepareAttempt(
    session,
    session.baseline,
    'READY',
  );
  if (attempt === null) {
    return rejected(
      'TEMPLATE_RECONCILIATION_CONTEXT_UNAVAILABLE',
      '无法在写入前冻结可核验的保存上下文；本次写入未发出。',
    );
  }

  let response: TemplateSaveHttpResponse;
  try {
    response = await transport.putCurrent(
      attempt.expectedCurrent.templateId,
      attempt.expectedRevision,
      attempt.draftCanonical,
      signal,
    );
  } catch {
    return unknownMutation(attempt);
  }
  return interpretMutationResponse(session, attempt, response);
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
  const attempt = await prepareAttempt(session, current, 'READY');
  if (attempt === null) {
    return rejected(
      'TEMPLATE_RECONCILIATION_CONTEXT_UNAVAILABLE',
      '无法在写入前冻结可核验的覆盖上下文；覆盖写入未发出。',
    );
  }

  let response: TemplateSaveHttpResponse;
  try {
    response = await transport.putCurrent(
      attempt.expectedCurrent.templateId,
      attempt.expectedRevision,
      attempt.draftCanonical,
      signal,
    );
  } catch {
    return unknownMutation(attempt);
  }
  return interpretMutationResponse(session, attempt, response);
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
  const confirmationExpiresAt = Date.parse(offer.expiresAt);
  if (
    !/^[0-9a-f]{64}$/.test(offer.confirmationToken)
    || !Number.isFinite(confirmationExpiresAt)
  ) {
    return {
      state: 'offer-invalidated',
      code: 'TEMPLATE_INVALID_SAVE_OFFER_INVALIDATED',
      message: 'INVALID 保存确认凭据未通过完整性检查；旧确认已失效。',
    };
  }
  if (confirmationExpiresAt <= Date.now()) {
    return {
      state: 'offer-invalidated',
      code: 'TEMPLATE_CONFIRMATION_EXPIRED',
      message: 'INVALID 保存确认已过期；请重新保存并审阅新的完整问题集。',
    };
  }

  const attempt = await prepareAttempt(
    session,
    session.baseline,
    'INVALID',
    { token: offer.confirmationToken, expiresAt: offer.expiresAt },
  );
  if (attempt === null || attempt.proposedContentHash !== offer.proposedContentHash) {
    return {
      state: 'offer-invalidated',
      code: 'TEMPLATE_INVALID_SAVE_OFFER_INVALIDATED',
      message: '本地草稿或 proposed contentHash 已变化；旧 INVALID 确认已失效。',
    };
  }

  let response: TemplateSaveHttpResponse;
  try {
    response = await transport.putCurrent(
      attempt.expectedCurrent.templateId,
      attempt.expectedRevision,
      attempt.draftCanonical,
      signal,
      attempt.confirmation?.token,
    );
  } catch {
    return unknownMutation(attempt);
  }
  return interpretMutationResponse(session, attempt, response);
}

export async function retryTemplateUnknownSave(
  session: StructuredEditorSession,
  attempt: TemplateUnknownSaveAttempt,
  transport: TemplateSaveTransport,
  signal?: AbortSignal,
): Promise<TemplateSaveResult> {
  if (!(await attemptMatchesSession(attempt, session))) {
    return {
      state: 'offer-invalidated',
      code: 'TEMPLATE_RECONCILIATION_ATTEMPT_INVALIDATED',
      message: '保存草稿、generation 或 trusted current identity 已变化；旧 reconciliation retry 已失效。',
    };
  }
  if (attempt.confirmation && Date.parse(attempt.confirmation.expiresAt) <= Date.now()) {
    return {
      state: 'offer-invalidated',
      code: 'TEMPLATE_CONFIRMATION_EXPIRED',
      message: 'INVALID 保存确认已过期；不能重放旧 confirmation。',
    };
  }

  let response: TemplateSaveHttpResponse;
  try {
    response = await transport.putCurrent(
      attempt.expectedCurrent.templateId,
      attempt.expectedRevision,
      attempt.draftCanonical,
      signal,
      attempt.confirmation?.token,
    );
  } catch {
    return unknownMutation(attempt);
  }
  return interpretMutationResponse(session, attempt, response);
}

async function interpretMutationResponse(
  session: StructuredEditorSession,
  attempt: TemplateUnknownSaveAttempt,
  response: TemplateSaveHttpResponse,
): Promise<TemplateSaveResult> {
  if (response.status === 200) {
    return verifiedSuccess(attempt, response.body);
  }

  const problem = parseProblem(response.body);
  if (response.status === 422 && isInvalidSaveOfferCode(problem.code)) {
    const offer = await invalidSaveOfferFor(
      session,
      attempt.expectedRevision,
      problem.value,
    );
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
  return unknownMutation(attempt);
}

async function verifiedSuccess(
  attempt: TemplateUnknownSaveAttempt,
  body: string,
): Promise<TemplateSaveResult> {
  let baseline: CanonicalTemplateBaseline;
  try {
    baseline = await parseTemplateCurrentResponse(body);
  } catch {
    return unknownMutation(attempt);
  }
  const expectedNext = successor(attempt.expectedRevision);
  if (
    expectedNext === null
    || baseline.revision !== expectedNext
    || !samePermanentIdentity(baseline, attempt.expectedCurrent)
    || baseline.canonicalDesignDsl !== attempt.draftCanonical
    || baseline.contentHash !== attempt.proposedContentHash
    || baseline.persistedReadiness !== attempt.requiredReadiness
  ) {
    return unknownMutation(attempt);
  }
  const next = createSessionFromBaseline(baseline, {
    state: 'checked',
    value: baseline.persistedReadiness,
  });
  if (next.mode !== 'structured') return unknownMutation(attempt);
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
  baseline: TemplateSaveExpectedCurrent | CanonicalTemplateBaseline,
): boolean {
  return current.templateId === baseline.templateId
    && current.staticSchema.schemaKey === baseline.staticSchema.schemaKey
    && current.staticSchema.versionTag === baseline.staticSchema.versionTag;
}

async function prepareAttempt(
  session: StructuredEditorSession,
  expectedCurrent: CanonicalTemplateBaseline,
  requiredReadiness: 'READY' | 'INVALID',
  confirmation?: { token: string; expiresAt: string },
): Promise<TemplateUnknownSaveAttempt | null> {
  try {
    if (
      !samePermanentIdentity(expectedCurrent, session.baseline)
      || (requiredReadiness === 'INVALID') !== (confirmation !== undefined)
      || (confirmation !== undefined && (
        !/^[0-9a-f]{64}$/.test(confirmation.token)
        || !Number.isFinite(Date.parse(confirmation.expiresAt))
      ))
    ) return null;
    const expectedContentHash = await templateContentHashOf(
      expectedCurrent.canonicalDesignDsl,
    );
    if (expectedContentHash !== expectedCurrent.contentHash) return null;
    const proposedContentHash = await templateContentHashOf(
      session.workingCopy.canonicalDesignDsl,
    );
    return Object.freeze({
      expectedCurrent: Object.freeze({
        templateId: expectedCurrent.templateId,
        revision: expectedCurrent.revision,
        staticSchema: Object.freeze({ ...expectedCurrent.staticSchema }),
        contentHash: expectedCurrent.contentHash,
        canonicalDesignDsl: expectedCurrent.canonicalDesignDsl,
      }),
      expectedRevision: expectedCurrent.revision,
      draftCanonical: session.workingCopy.canonicalDesignDsl,
      proposedContentHash,
      previewGeneration: session.previewGeneration,
      requiredReadiness,
      ...(confirmation ? { confirmation: Object.freeze({ ...confirmation }) } : {}),
    });
  } catch {
    return null;
  }
}

async function attemptMatchesSession(
  attempt: TemplateUnknownSaveAttempt,
  session: StructuredEditorSession,
): Promise<boolean> {
  if (
    attempt.expectedRevision !== attempt.expectedCurrent.revision
    || !samePermanentIdentity(session.baseline, attempt.expectedCurrent)
    || attempt.draftCanonical !== session.workingCopy.canonicalDesignDsl
    || attempt.previewGeneration !== session.previewGeneration
    || !isCanonicalDirty(session)
    || !hasSuccessor(attempt.expectedRevision)
    || (attempt.requiredReadiness === 'INVALID') !== (attempt.confirmation !== undefined)
  ) return false;
  if (attempt.confirmation && (
    !/^[0-9a-f]{64}$/.test(attempt.confirmation.token)
    || !Number.isFinite(Date.parse(attempt.confirmation.expiresAt))
  )) return false;
  try {
    return await templateContentHashOf(attempt.draftCanonical) === attempt.proposedContentHash
      && await templateContentHashOf(attempt.expectedCurrent.canonicalDesignDsl)
        === attempt.expectedCurrent.contentHash;
  } catch {
    return false;
  }
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

function unknownMutation(attempt: TemplateUnknownSaveAttempt): TemplateSaveResult {
  return {
    state: 'unknown',
    attempt,
    message: '保存请求的结果不明；本地草稿已锁定，正在通过 trusted current 核验。',
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
