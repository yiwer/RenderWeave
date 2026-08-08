import * as Dialog from '@radix-ui/react-dialog';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  GitBranch,
  List,
  LoaderCircle,
  RefreshCw,
  Search,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import { useCallback, useEffect, useReducer, useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import { StudioRequestError } from '../schema-studio/lossless-api';
import { ResourceError, ResourceLoading } from '../resources/DraftListPage';
import { ResourceFrame } from '../resources/ResourceFrame';
import {
  applyCandidateRequest,
  getCandidateReviewRequest,
  saveCandidateReviewRequest,
  subscribeInferenceRunEvents,
} from './candidate-api';
import type { CandidateApplyResponse } from '../../api/generated';
import { CandidateInspector } from './CandidateInspector';
import { CandidateBundleNav, CandidateSurface } from './CandidateSurfaces';
import { problemLabel } from './candidate-format';
import {
  candidateReviewReducer,
  createCandidateReviewState,
  findSelected,
  type CandidateReviewState,
} from './candidate-session';

export function CandidateReviewPage() {
  const { runId = '' } = useParams();
  const query = useQuery({
    queryKey: ['inference-candidate', runId],
    queryFn: () => getCandidateReviewRequest(runId),
    enabled: Boolean(runId),
  });
  const refetch = query.refetch;
  const reload = useCallback(
    () => refetch().then((result) => result.data),
    [refetch],
  );

  return (
    <ResourceFrame
      title="逐项审核 AI Schema Candidate"
      description="表单与一层树图共享同一份候选状态；置信度和证据只读，每个低置信度项必须单独确认、编辑解决或移除。"
      actions={<Link className="button ghost-button" to="/inference"><ArrowLeft aria-hidden="true" size={15} />返回样本</Link>}
    >
      {query.isPending && <ResourceLoading label="正在读取 Candidate 与证据" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && <CandidateReviewWorkspace key={runId} initial={query.data} onReload={reload} />}
    </ResourceFrame>
  );
}

function CandidateReviewWorkspace({
  initial,
  onReload,
}: {
  initial: Parameters<typeof createCandidateReviewState>[0];
  onReload: () => Promise<Parameters<typeof createCandidateReviewState>[0] | undefined>;
}) {
  const [state, dispatch] = useReducer(candidateReviewReducer, initial, createCandidateReviewState);
  const queryClient = useQueryClient();
  const [applyResult, setApplyResult] = useState<CandidateApplyResponse | null>(null);
  const selected = findSelected(state);
  const blockerCount = state.snapshot.problems.filter((problem) => problem.severity === 'BLOCKER').length;
  const warningCount = state.snapshot.problems.length - blockerCount;
  const completed = state.snapshot.run.state === 'COMPLETED';
  const activeSchemas = (state.snapshot.finalCandidate ?? state.snapshot.current).schemas
    .filter((schema) => schema.assessment.resolution !== 'REMOVED');
  const canApply = blockerCount === 0
    && !state.dirty
    && !state.saving
    && !state.saveBlocked
    && state.snapshot.run.state === 'REVIEW_REQUIRED';
  const applyMutation = useMutation({
    mutationFn: () => applyCandidateRequest(state.snapshot.run.runId, state.snapshot.candidateRevision),
    retry: false,
    onSuccess: async (result) => {
      setApplyResult(result);
      void queryClient.invalidateQueries({ queryKey: ['schema-drafts'] });
      const snapshot = await onReload();
      if (snapshot) dispatch({ type: 'hydrate', snapshot });
    },
  });

  useEffect(() => {
    if (!state.dirty || state.saving || state.saveBlocked) return;
    const generation = state.generation;
    const revision = state.snapshot.candidateRevision;
    const candidate = state.draft;
    const timer = window.setTimeout(() => {
      dispatch({ type: 'save-start' });
      void saveCandidateReviewRequest(state.snapshot.run.runId, revision, candidate)
        .then((snapshot) => dispatch({ type: 'save-success', snapshot, generation }))
        .catch((error: unknown) => dispatch({ type: 'save-error', message: saveErrorMessage(error) }));
    }, 0);
    return () => window.clearTimeout(timer);
  }, [state.dirty, state.draft, state.generation, state.saveBlocked, state.saving, state.snapshot.candidateRevision, state.snapshot.run.runId]);

  useEffect(() => {
    if (state.snapshot.run.state === 'COMPLETED'
      || state.snapshot.run.state === 'FAILED'
      || state.snapshot.run.state === 'CANCELLED') return;
    let refreshing = false;
    return subscribeInferenceRunEvents(
      state.snapshot.run.runId,
      state.snapshot.run.sequence,
      () => {
        if (refreshing) return;
        refreshing = true;
        void onReload()
          .then((snapshot) => { if (snapshot) dispatch({ type: 'hydrate', snapshot }); })
          .finally(() => { refreshing = false; });
      },
    );
  }, [onReload, state.snapshot.run.runId, state.snapshot.run.sequence, state.snapshot.run.state]);

  if (!selected.schema) return <div className="resource-state resource-state-error" role="alert">Candidate 包中没有可读取的 Schema。</div>;
  return (
    <>
      <section className="candidate-run-strip" aria-label="推断运行状态">
        <div><span>运行编号</span><code>{state.snapshot.run.runId}</code></div>
        <div><span>执行配置</span><strong>{state.snapshot.run.profileId}</strong></div>
        <div><span>状态 / 阶段</span><strong>{state.snapshot.run.state} · {state.snapshot.run.stage}</strong></div>
        <div><span>候选版本</span><strong>c{state.snapshot.candidateRevision}</strong></div>
        <SaveIndicator state={state} />
      </section>

      {state.saveMessage && (
        <section className="candidate-save-error" role="alert">
          <AlertCircle aria-hidden="true" size={17} />
          <div><strong>本地修改尚未保存</strong><span>{state.saveMessage}</span></div>
          <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'retry-save' })}>重试</button>
          <button type="button" className="button ghost-button" onClick={() => void onReload().then((snapshot) => { if (snapshot) dispatch({ type: 'hydrate', snapshot }); })}><RefreshCw aria-hidden="true" size={14} />舍弃本地并重载</button>
        </section>
      )}

      <section className={`candidate-gate-summary ${blockerCount === 0 ? 'ready' : ''}`}>
        {blockerCount === 0 ? <ShieldCheck aria-hidden="true" size={20} /> : <TriangleAlert aria-hidden="true" size={20} />}
        <div>
          <strong>{completed ? 'Draft Bundle 已原子创建' : blockerCount === 0 ? 'Candidate 审核门已通过' : `${blockerCount} 个 blocker 阻止落库`}</strong>
          <span>{completed ? `${activeSchemas.length} 个 Draft · revision 0 · 来源 AI` : `${warningCount} warning · 诊断来自服务端确定性验证`}</span>
        </div>
        <p>{completed
          ? 'final Candidate 已冻结；本次操作没有发布、更新或删除任何既有 Schema。'
          : blockerCount === 0
            ? `自动保存稳定后，可一次创建 ${activeSchemas.length} 个 Draft；任一 key、引用或事务冲突都会整包回滚。`
            : '选择问题对应的 Schema/字段，逐项处理后会自动保存并重新验证。'}</p>
        {completed
          ? <CreatedDraftLinks result={applyResult} schemaKeys={activeSchemas.map((schema) => schema.proposedSchemaKey).filter((key): key is string => Boolean(key))} />
          : <CandidateApplyDialog
              canApply={canApply}
              schemaKeys={activeSchemas.map((schema) => schema.proposedSchemaKey).filter((key): key is string => Boolean(key))}
              pending={applyMutation.isPending}
              error={applyMutation.error}
              onApply={() => applyMutation.mutate()}
            />}
      </section>

      {state.snapshot.problems.length > 0 && (
        <div className="candidate-problem-ribbon" aria-label="Candidate 全局诊断">
          {state.snapshot.problems.slice(0, 6).map((problem, index) => (
            <button key={`${problem.code}:${problem.pointer}:${index}`} type="button" onClick={() => selectProblem(state, problem.itemId, dispatch)}>
              <span className={problem.severity.toLocaleLowerCase()}>{problem.severity}</span><strong>{problemLabel(problem.code)}</strong><code>{problem.code}</code>
            </button>
          ))}
          {state.snapshot.problems.length > 6 && <span>另有 {state.snapshot.problems.length - 6} 项，可在各字段检查器中查看</span>}
        </div>
      )}

      <fieldset className="candidate-review-fieldset" disabled={completed} aria-label={completed ? '已冻结的 final Candidate' : 'Candidate 编辑工作区'}>
        <div className="candidate-review-grid">
          <CandidateBundleNav state={state} dispatch={dispatch} />
          <section className="candidate-center">
          <header className="candidate-surface-toolbar">
            <div>
              <span>当前 Schema</span>
              <strong>{selected.schema.displayName || selected.schema.proposedSchemaKey || '未命名 Schema'}</strong>
              <code>{selected.schema.proposedSchemaKey || 'schemaKey 待填写'}</code>
            </div>
            <label className="candidate-search"><Search aria-hidden="true" size={14} /><span className="sr-only">搜索 Candidate 字段</span><input type="search" value={state.search} placeholder="搜索字段" onChange={(event) => dispatch({ type: 'set-search', search: event.target.value })} /></label>
            <div className="candidate-view-toggle" aria-label="Candidate 编辑视图">
              <button type="button" className={state.view === 'form' ? 'active' : ''} aria-pressed={state.view === 'form'} onClick={() => dispatch({ type: 'set-view', view: 'form' })}><List aria-hidden="true" size={14} />表单</button>
              <button type="button" className={state.view === 'map' ? 'active' : ''} aria-pressed={state.view === 'map'} onClick={() => dispatch({ type: 'set-view', view: 'map' })}><GitBranch aria-hidden="true" size={14} />树图</button>
            </div>
          </header>
          <CandidateSurface state={state} schema={selected.schema} dispatch={dispatch} />
          </section>
          <CandidateInspector state={state} schema={selected.schema} field={selected.field} dispatch={dispatch} />
        </div>
      </fieldset>
    </>
  );
}

function CandidateApplyDialog({
  canApply,
  schemaKeys,
  pending,
  error,
  onApply,
}: {
  canApply: boolean;
  schemaKeys: string[];
  pending: boolean;
  error: Error | null;
  onApply: () => void;
}) {
  return (
    <Dialog.Root>
      <Dialog.Trigger asChild>
        <button className="button primary-button candidate-apply-trigger" type="button" disabled={!canApply || pending}>
          {pending ? <LoaderCircle className="spin" aria-hidden="true" size={15} /> : <ShieldCheck aria-hidden="true" size={15} />}
          原子创建 {schemaKeys.length} 个 Draft
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content candidate-apply-dialog" aria-describedby="candidate-apply-description">
          <Dialog.Title>确认创建完整 Draft Bundle</Dialog.Title>
          <Dialog.Description id="candidate-apply-description">
            这会冻结当前 Candidate，并以 AI 来源创建以下 revision 0。它不会发布、覆盖或合并既有 Schema。
          </Dialog.Description>
          <ol className="candidate-apply-list">
            {schemaKeys.map((schemaKey) => <li key={schemaKey}><code>{schemaKey}</code><span>revision 0</span></li>)}
          </ol>
          <ul className="candidate-apply-contract">
            <li>任一 active key 或 tombstone 冲突：整包零写</li>
            <li>任一引用、DAG 或约束失败：整包零写</li>
            <li>成功后 final Candidate 只读，Draft 仍可进入普通生命周期</li>
          </ul>
          {error && <div className="candidate-apply-error" role="alert"><AlertCircle aria-hidden="true" size={16} /><span>{applyErrorMessage(error)}</span></div>}
          <div className="dialog-actions">
            <Dialog.Close asChild><button className="button ghost-button" type="button" disabled={pending}>继续审核</button></Dialog.Close>
            <button className="button primary-button" type="button" disabled={!canApply || pending} onClick={onApply}>
              {pending ? <LoaderCircle className="spin" aria-hidden="true" size={15} /> : <ShieldCheck aria-hidden="true" size={15} />}
              确认原子创建
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function CreatedDraftLinks({ result, schemaKeys }: { result: CandidateApplyResponse | null; schemaKeys: string[] }) {
  const keys = result?.createdDrafts.map((draft) => draft.schemaKey) ?? schemaKeys;
  return (
    <div className="candidate-created-links" aria-label="已创建 Draft">
      {keys.map((schemaKey) => <Link key={schemaKey} to={`/schemas/${schemaKey}`}>{schemaKey}<span>r0</span></Link>)}
    </div>
  );
}

function SaveIndicator({ state }: { state: CandidateReviewState }) {
  if (state.saving) return <div className="candidate-save-state saving"><LoaderCircle className="spin" aria-hidden="true" size={15} /><span>逐项自动保存</span><strong>保存中</strong></div>;
  if (state.saveBlocked) return <div className="candidate-save-state error"><AlertCircle aria-hidden="true" size={15} /><span>自动保存</span><strong>需处理</strong></div>;
  if (state.dirty) return <div className="candidate-save-state"><LoaderCircle className="spin" aria-hidden="true" size={15} /><span>自动保存</span><strong>排队中</strong></div>;
  return <div className="candidate-save-state saved"><CheckCircle2 aria-hidden="true" size={15} /><span>自动保存</span><strong>已保存</strong></div>;
}

function saveErrorMessage(error: unknown) {
  if (error instanceof StudioRequestError && error.problem.status === 409) {
    return 'Candidate revision 已变化。你的本地修改仍保留；请重载后重新审核，或确认没有其他窗口后再试。';
  }
  return error instanceof Error ? error.message : '自动保存失败。';
}

function applyErrorMessage(error: unknown) {
  if (error instanceof StudioRequestError && error.problem.status === 409) {
    return '创建期间发现 key、tombstone 或引用冲突。整包没有写入，Candidate 仍保留在审核态。';
  }
  if (error instanceof StudioRequestError && error.problem.status === 422) {
    return '服务端重新验证发现 blocker。整包没有写入，请关闭窗口并继续逐项处理。';
  }
  return error instanceof Error ? error.message : '原子创建失败；整包没有写入。';
}

function selectProblem(
  state: CandidateReviewState,
  itemId: string | null,
  dispatch: React.Dispatch<Parameters<typeof candidateReviewReducer>[1]>,
) {
  if (!itemId) return;
  for (const schema of state.draft.schemas) {
    if (schema.candidateSchemaId === itemId) {
      dispatch({ type: 'select-schema', schemaId: itemId });
      return;
    }
    if (schema.fields.some((field) => field.candidateFieldId === itemId)) {
      dispatch({ type: 'select-field', schemaId: schema.candidateSchemaId, fieldId: itemId });
      return;
    }
  }
}
