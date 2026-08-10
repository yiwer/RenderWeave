import * as Dialog from '@radix-ui/react-dialog';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  Activity,
  CheckCircle2,
  GitBranch,
  List,
  LoaderCircle,
  PanelRightOpen,
  RefreshCw,
  Search,
  ShieldCheck,
  TriangleAlert,
  XCircle,
} from 'lucide-react';
import { useCallback, useEffect, useReducer, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';

import { StudioRequestError } from '../schema-studio/lossless-api';
import { ResourceError, ResourceLoading } from '../resources/DraftListPage';
import { ResourceFrame } from '../resources/ResourceFrame';
import {
  applyCandidateRequest,
  cancelInferenceRunRequest,
  getCandidateReviewRequest,
  getInferenceRunRequest,
  saveCandidateReviewRequest,
  subscribeInferenceRunEvents,
} from './candidate-api';
import type { CandidateApplyResponse, CandidateProblem } from '../../api/generated';
import { CandidateInspector } from './CandidateInspector';
import { CandidateBundleNav, CandidateSurface } from './CandidateSurfaces';
import { problemLabel } from './candidate-format';
import {
  candidateReviewReducer,
  createCandidateReviewState,
  findSelected,
  type CandidateReviewAction,
  type CandidateReviewState,
} from './candidate-session';
import { InferenceFlowSteps } from './InferenceFlowSteps';
import { InferenceExecutionLogPanel } from './InferenceExecutionLogPanel';
import { inferenceRunHasResult, inferenceStageLabel, inferenceStateLabel } from './inference-format';
import { RunCancelButton } from './InferenceRunActions';

export function CandidateReviewPage() {
  const { runId = '' } = useParams();
  const queryClient = useQueryClient();
  const runQuery = useQuery({
    queryKey: ['inference-run', runId],
    queryFn: () => getInferenceRunRequest(runId),
    enabled: Boolean(runId),
    refetchInterval: (current) => {
      const state = current.state.data?.state;
      return state === 'QUEUED' || state === 'RUNNING' ? 1_000 : false;
    },
  });
  const reviewReady = Boolean(runQuery.data && inferenceRunHasResult(runQuery.data.state));
  const query = useQuery({
    queryKey: ['inference-candidate', runId],
    queryFn: () => getCandidateReviewRequest(runId),
    enabled: Boolean(runId) && reviewReady,
  });
  const refetch = query.refetch;
  const reload = useCallback(
    () => refetch().then((result) => {
      if (result.data) queryClient.setQueryData(['inference-run', runId], result.data.run);
      return result.data;
    }),
    [queryClient, refetch, runId],
  );
  const cancelRun = useMutation({
    mutationFn: () => cancelInferenceRunRequest(runId),
    onSuccess: (run) => queryClient.setQueryData(['inference-run', runId], run),
  });
  const flowStep = runQuery.data?.state === 'COMPLETED' || runQuery.data?.state === 'APPLYING'
    ? 4
    : reviewReady ? 3 : 2;

  if (runQuery.data && !reviewReady) {
    return <Navigate replace to={`/inference-runs/${runId}/monitor`} />;
  }

  return (
    <ResourceFrame
      title="识别结果"
      description="逐项核对字段、类型、约束、引用与证据；AI 来源保持只读，只有全部门通过后才能原子创建 Draft。"
      actions={<Link className="button ghost-button" to={`/inference-runs/${runId}/monitor`}><Activity aria-hidden="true" size={15} />查看识别监控</Link>}
      breadcrumbs={[{ label: '智能识别', to: '/inference' }, { label: '识别结果' }]}
    >
      <InferenceFlowSteps current={flowStep} />
      {runQuery.isPending && <ResourceLoading label="正在读取推断任务" />}
      {runQuery.isError && <ResourceError error={runQuery.error} onRetry={() => void runQuery.refetch()} />}
      {runQuery.data && (
        <InferenceExecutionLogPanel
          runId={runQuery.data.runId}
          state={runQuery.data.state}
          sequence={runQuery.data.sequence}
        />
      )}
      {reviewReady && query.isPending && <ResourceLoading label="正在读取 Candidate 与证据" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {reviewReady && query.data && (
        <CandidateReviewWorkspace
          key={runId}
          initial={query.data}
          onReload={reload}
          cancelPending={cancelRun.isPending}
          cancelError={cancelRun.error}
          onCancel={() => cancelRun.mutate()}
        />
      )}
    </ResourceFrame>
  );
}

function CandidateReviewWorkspace({
  initial,
  onReload,
  cancelPending,
  cancelError,
  onCancel,
}: {
  initial: Parameters<typeof createCandidateReviewState>[0];
  onReload: () => Promise<Parameters<typeof createCandidateReviewState>[0] | undefined>;
  cancelPending: boolean;
  cancelError: Error | null;
  onCancel: () => void;
}) {
  const [state, dispatch] = useReducer(candidateReviewReducer, initial, createCandidateReviewState);
  const queryClient = useQueryClient();
  const [applyResult, setApplyResult] = useState<CandidateApplyResponse | null>(null);
  const compactInspector = useMediaQuery('(max-width: 1180px)');
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const selected = findSelected(state);
  const blockerCount = state.snapshot.problems.filter((problem) => problem.severity === 'BLOCKER').length;
  const warningCount = state.snapshot.problems.length - blockerCount;
  const problemGroups = groupCandidateProblems(state.snapshot.problems);
  const completed = state.snapshot.run.state === 'COMPLETED';
  const terminal = completed
    || state.snapshot.run.state === 'FAILED'
    || state.snapshot.run.state === 'CANCELLED';
  const reviewDispatch = useCallback((action: CandidateReviewAction) => {
    dispatch(action);
    if (compactInspector && (action.type === 'select-schema'
      || action.type === 'select-field'
      || action.type === 'add-schema'
      || action.type === 'add-field')) setInspectorOpen(true);
  }, [compactInspector]);
  const activeSchemas = (state.snapshot.finalCandidate ?? state.snapshot.current).schemas
    .filter((schema) => schema.assessment.resolution !== 'REMOVED');
  const reviewItems = state.draft.schemas.flatMap((schema) => [schema, ...schema.fields])
    .filter((item) => item.source === 'AI');
  const pendingReviewCount = reviewItems.filter((item) => item.assessment.resolution === 'UNRESOLVED').length;
  const reviewedCount = reviewItems.length - pendingReviewCount;
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
      queryClient.setQueryData(['inference-run', result.run.runId], result.run);
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
        <div><span>状态 / 阶段</span><strong>{inferenceStateLabel(state.snapshot.run.state)} · {inferenceStageLabel(state.snapshot.run.stage)}</strong></div>
        <div><span>候选版本</span><strong>c{state.snapshot.candidateRevision}</strong></div>
        <SaveIndicator state={state} />
        {state.snapshot.run.state === 'REVIEW_REQUIRED' && <div className="candidate-run-cancel"><RunCancelButton pending={cancelPending} onCancel={onCancel} /></div>}
      </section>

      {cancelError && <p className="candidate-operation-error" role="alert">{cancelError.message}</p>}

      {state.saveMessage && (
        <section className="candidate-save-error" role="alert">
          <AlertCircle aria-hidden="true" size={17} />
          <div><strong>本地修改尚未保存</strong><span>{state.saveMessage}</span></div>
          <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'retry-save' })}>重试</button>
          <button type="button" className="button ghost-button" onClick={() => void onReload().then((snapshot) => { if (snapshot) dispatch({ type: 'hydrate', snapshot }); })}><RefreshCw aria-hidden="true" size={14} />舍弃本地并重载</button>
        </section>
      )}

      <CandidateReviewOverview
        reviewed={reviewedCount}
        total={reviewItems.length}
        pending={pendingReviewCount}
        blockers={blockerCount}
        warnings={warningCount}
        autosaveReady={!state.dirty && !state.saving && !state.saveBlocked}
        keysReady={activeSchemas.length > 0 && activeSchemas.every((schema) => Boolean(schema.proposedSchemaKey))}
        completed={completed}
      />

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
        <section className="candidate-problem-summary" aria-label="Candidate 全局诊断">
          <header>
            <div><TriangleAlert aria-hidden="true" size={17} /><span><strong>诊断汇总</strong><small>{state.snapshot.problems.length} 项 · {problemGroups.length} 类</small></span></div>
            <p>同类诊断已合并；选择一类可定位到首个相关字段。</p>
          </header>
          <ul>
            {problemGroups.map((group) => (
              <li key={`${group.severity}:${group.code}`}>
                <button type="button" onClick={() => selectProblem(state, group.first.itemId, reviewDispatch)}>
                  <span className={group.severity.toLocaleLowerCase()}>{group.severity === 'BLOCKER' ? '阻断' : '提示'}</span>
                  <span><strong>{problemLabel(group.code)}</strong><code>{group.code}</code></span>
                  <b aria-label={`${group.count} 项`}>×{group.count}</b>
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className={`candidate-review-fieldset ${completed ? 'is-frozen' : ''}`} aria-label={completed ? '已冻结的 final Candidate' : 'Candidate 编辑工作区'}>
        <Dialog.Root open={compactInspector ? inspectorOpen : false} onOpenChange={setInspectorOpen}>
        <div className="candidate-review-grid">
          <CandidateBundleNav state={state} dispatch={reviewDispatch} readOnly={terminal} />
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
            {compactInspector && (
              <Dialog.Trigger asChild>
                <button type="button" className="button ghost-button candidate-inspector-trigger"><PanelRightOpen aria-hidden="true" size={15} />属性与证据</button>
              </Dialog.Trigger>
            )}
          </header>
          <CandidateSurface state={state} schema={selected.schema} dispatch={reviewDispatch} readOnly={terminal} />
          </section>
          {!compactInspector && <CandidateInspector state={state} schema={selected.schema} field={selected.field} dispatch={dispatch} readOnly={terminal} />}
        </div>
        {compactInspector && (
          <Dialog.Portal>
            <Dialog.Overlay className="candidate-inspector-overlay" />
            <Dialog.Content className="candidate-inspector-drawer" aria-describedby={undefined}>
              <Dialog.Title className="sr-only">Candidate 属性与证据</Dialog.Title>
              <CandidateInspector state={state} schema={selected.schema} field={selected.field} dispatch={dispatch} readOnly={terminal} onClose={() => setInspectorOpen(false)} />
            </Dialog.Content>
          </Dialog.Portal>
        )}
        </Dialog.Root>
      </section>
    </>
  );
}

function CandidateReviewOverview({
  reviewed,
  total,
  pending,
  blockers,
  warnings,
  autosaveReady,
  keysReady,
  completed,
}: {
  reviewed: number;
  total: number;
  pending: number;
  blockers: number;
  warnings: number;
  autosaveReady: boolean;
  keysReady: boolean;
  completed: boolean;
}) {
  const percentage = total === 0 ? 100 : Math.round((reviewed / total) * 100);
  const checks = [
    { label: '逐项处置完成', ready: pending === 0, detail: pending === 0 ? '没有待决定 AI 项' : `仍有 ${pending} 项待决定` },
    { label: '确定性校验', ready: blockers === 0, detail: blockers === 0 ? `${warnings} 项提示不阻止创建` : `${blockers} 个 blocker` },
    { label: '数据结构标识', ready: keysReady, detail: keysReady ? '所有活动 Schema 已填写 key' : '仍有 key 待填写' },
    { label: '自动保存', ready: autosaveReady, detail: autosaveReady ? '服务端版本已同步' : '等待保存稳定' },
  ];
  return (
    <section className={`candidate-review-overview ${completed ? 'completed' : ''}`} aria-label="审核完成度">
      <div className="candidate-review-progress">
        <span>逐项校对</span>
        <strong>{completed ? '已创建' : `${reviewed} / ${total}`}</strong>
        <div role="progressbar" aria-label="逐项校对完成度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={completed ? 100 : percentage}><i style={{ width: `${completed ? 100 : percentage}%` }} /></div>
        <small>{completed ? 'final Candidate 已冻结' : `${percentage}% 已检查；没有批量确认入口`}</small>
      </div>
      <ul>
        {checks.map((check) => (
          <li key={check.label} className={check.ready ? 'ready' : ''}>
            {check.ready ? <CheckCircle2 aria-hidden="true" size={16} /> : <XCircle aria-hidden="true" size={16} />}
            <span><strong>{check.label}</strong><small>{check.detail}</small></span>
          </li>
        ))}
      </ul>
    </section>
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

function groupCandidateProblems(problems: CandidateProblem[]) {
  const groups = new Map<string, {
    code: string;
    severity: CandidateProblem['severity'];
    count: number;
    first: CandidateProblem;
  }>();
  for (const problem of problems) {
    const key = `${problem.severity}:${problem.code}`;
    const current = groups.get(key);
    if (current) current.count += 1;
    else groups.set(key, {
      code: problem.code,
      severity: problem.severity,
      count: 1,
      first: problem,
    });
  }
  return [...groups.values()].sort((left, right) => {
    if (left.severity !== right.severity) return left.severity === 'BLOCKER' ? -1 : 1;
    return right.count - left.count || left.code.localeCompare(right.code);
  });
}

function useMediaQuery(query: string) {
  const [matches, setMatches] = useState(() => typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia(query).matches);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const media = window.matchMedia(query);
    const update = () => setMatches(media.matches);
    update();
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, [query]);

  return matches;
}
