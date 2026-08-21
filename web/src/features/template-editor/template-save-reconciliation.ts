import {
  createSessionFromBaseline,
  type CanonicalTemplateBaseline,
  type StructuredEditorSession,
} from './template-editor-model';
import {
  parseTemplateCurrentResponse,
  templateContentHashOf,
  TemplateRequestError,
} from './template-open';
import type {
  TemplateConflictOffer,
  TemplateSaveTransport,
  TemplateUnknownSaveAttempt,
} from './template-save';

const MAX_REVISION = 9_223_372_036_854_775_807n;

export type TemplateSaveReconciliationResult =
  | { state: 'adopted'; session: StructuredEditorSession; message: string }
  | { state: 'retryable'; attempt: TemplateUnknownSaveAttempt; message: string }
  | { state: 'conflict'; offer: TemplateConflictOffer; message: string }
  | { state: 'deleted'; attempt: TemplateUnknownSaveAttempt; message: string }
  | { state: 'unavailable'; attempt: TemplateUnknownSaveAttempt; message: string }
  | {
    state: 'failed-closed';
    attempt: TemplateUnknownSaveAttempt;
    code: string;
    message: string;
  };

export async function reconcileTemplateUnknownSave(
  attempt: TemplateUnknownSaveAttempt,
  transport: TemplateSaveTransport,
  signal?: AbortSignal,
): Promise<TemplateSaveReconciliationResult> {
  if (!(await internallyConsistent(attempt))) {
    return failedClosed(
      attempt,
      'TEMPLATE_RECONCILIATION_ATTEMPT_UNTRUSTED',
      '保存核验上下文未通过完整性检查；本地草稿继续锁定。',
    );
  }

  let current: CanonicalTemplateBaseline;
  try {
    current = await parseTemplateCurrentResponse(
      await transport.getCurrent(attempt.expectedCurrent.templateId, signal),
    );
  } catch (error) {
    if (isAbort(error)) throw error;
    if (isTemporarilyUnavailable(error)) {
      return {
        state: 'unavailable',
        attempt,
        message: 'trusted current 暂时不可读；保存结果仍未知，未重试任何写入。',
      };
    }
    if (
      error instanceof TemplateRequestError
      && error.status === 410
      && error.code === 'TEMPLATE_DELETED'
    ) {
      return {
        state: 'deleted',
        attempt,
        message: 'Template 已删除；保存结果不再可收敛为可编辑 current，本地草稿仅保留用于导出。',
      };
    }
    return failedClosed(
      attempt,
      error instanceof TemplateRequestError ? error.code : 'TEMPLATE_CURRENT_UNTRUSTED',
      'trusted current 未通过合同或完整性检查；保存结果无法解释，本地草稿继续锁定。',
    );
  }

  if (!samePermanentIdentity(current, attempt)) {
    return failedClosed(
      attempt,
      'TEMPLATE_CURRENT_IDENTITY_MISMATCH',
      'trusted current 的 Template 或永久 StaticSchema identity 不一致；本地草稿继续锁定。',
    );
  }

  const currentRevision = revisionValue(current.revision);
  const expectedRevision = revisionValue(attempt.expectedRevision);
  if (currentRevision === null || expectedRevision === null) {
    return failedClosed(
      attempt,
      'TEMPLATE_REVISION_UNTRUSTED',
      'trusted current revision 无法无损比较；本地草稿继续锁定。',
    );
  }

  if (currentRevision < expectedRevision) {
    return failedClosed(
      attempt,
      'TEMPLATE_REVISION_ROLLBACK',
      'trusted current revision 低于原 expectedRevision；检测到回退或无法解释的状态。',
    );
  }

  if (currentRevision === expectedRevision) {
    if (
      current.contentHash !== attempt.expectedCurrent.contentHash
      || current.canonicalDesignDsl !== attempt.expectedCurrent.canonicalDesignDsl
    ) {
      return failedClosed(
        attempt,
        'TEMPLATE_CURRENT_SAME_REVISION_DRIFT',
        '同一 revision 的 trusted current 内容发生漂移；本地草稿继续锁定。',
      );
    }
    return {
      state: 'retryable',
      attempt,
      message: 'trusted current 仍精确等于原 expectedRevision；仅可由作者显式重试同一保存。',
    };
  }

  if (current.contentHash === attempt.proposedContentHash) {
    if (current.canonicalDesignDsl !== attempt.draftCanonical) {
      return failedClosed(
        attempt,
        'TEMPLATE_CURRENT_CANONICAL_MISMATCH',
        'trusted current 的 hash 与草稿相同但 canonical bytes 不同；拒绝采用可疑 baseline。',
      );
    }
    const adopted = createSessionFromBaseline(current, {
      state: 'unavailable',
      message: '已采用 reconciliation 的 trusted current；权威 preview 前必须重新检查 readiness。',
    });
    if (adopted.mode !== 'structured') {
      return failedClosed(
        attempt,
        'TEMPLATE_CURRENT_UNSUPPORTED',
        '收敛后的 trusted current 不再属于客户端支持的 Structured Profile；拒绝部分采用。',
      );
    }
    return {
      state: 'adopted',
      session: adopted,
      message: '内容已在服务器确认；已采用 trusted current，但这不代表具体请求归属。',
    };
  }

  if (currentRevision >= MAX_REVISION) {
    return failedClosed(
      attempt,
      'TEMPLATE_REVISION_EXHAUSTED',
      '远端 current 已没有可追加的后继 revision；不能提供无效覆盖操作。',
    );
  }
  return {
    state: 'conflict',
    offer: {
      offeredRevision: current.revision,
      draftCanonical: attempt.draftCanonical,
      previewGeneration: attempt.previewGeneration,
    },
    message: `trusted current 已前进到 revision ${current.revision} 且内容不同；覆盖前需要显式确认。`,
  };
}

async function internallyConsistent(
  attempt: TemplateUnknownSaveAttempt,
): Promise<boolean> {
  if (
    attempt.expectedRevision !== attempt.expectedCurrent.revision
    || revisionValue(attempt.expectedRevision) === null
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

function samePermanentIdentity(
  current: CanonicalTemplateBaseline,
  attempt: TemplateUnknownSaveAttempt,
): boolean {
  return current.templateId === attempt.expectedCurrent.templateId
    && current.staticSchema.schemaKey === attempt.expectedCurrent.staticSchema.schemaKey
    && current.staticSchema.versionTag === attempt.expectedCurrent.staticSchema.versionTag;
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

function isTemporarilyUnavailable(error: unknown): boolean {
  return error instanceof TypeError
    || (error instanceof TemplateRequestError && error.status === 503);
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function failedClosed(
  attempt: TemplateUnknownSaveAttempt,
  code: string,
  message: string,
): TemplateSaveReconciliationResult {
  return { state: 'failed-closed', attempt, code, message };
}
