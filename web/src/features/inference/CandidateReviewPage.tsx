import { useQuery } from '@tanstack/react-query';
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
import { useEffect, useReducer } from 'react';
import { Link, useParams } from 'react-router-dom';

import { StudioRequestError } from '../schema-studio/lossless-api';
import { ResourceError, ResourceLoading } from '../resources/DraftListPage';
import { ResourceFrame } from '../resources/ResourceFrame';
import { getCandidateReviewRequest, saveCandidateReviewRequest } from './candidate-api';
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

  return (
    <ResourceFrame
      eyebrow="CANDIDATE REVIEW"
      title="逐项审核 AI Schema Candidate"
      description="表单与一层树图共享同一份候选状态；置信度和证据只读，每个低置信度项必须单独确认、编辑解决或移除。"
      actions={<Link className="button ghost-button" to="/inference"><ArrowLeft aria-hidden="true" size={15} />返回样本</Link>}
    >
      {query.isPending && <ResourceLoading label="正在读取 Candidate 与证据" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && <CandidateReviewWorkspace key={runId} initial={query.data} onReload={() => query.refetch().then((result) => result.data)} />}
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
  const selected = findSelected(state);
  const blockerCount = state.snapshot.problems.filter((problem) => problem.severity === 'BLOCKER').length;
  const warningCount = state.snapshot.problems.length - blockerCount;

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

  if (!selected.schema) return <div className="resource-state resource-state-error" role="alert">Candidate 包中没有可读取的 Schema。</div>;
  return (
    <>
      <section className="candidate-run-strip" aria-label="推断运行状态">
        <div><span>RUN</span><code>{state.snapshot.run.runId}</code></div>
        <div><span>PROFILE</span><strong>{state.snapshot.run.profileId}</strong></div>
        <div><span>STATE / STAGE</span><strong>{state.snapshot.run.state} · {state.snapshot.run.stage}</strong></div>
        <div><span>REVISION</span><strong>c{state.snapshot.candidateRevision}</strong></div>
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
        <div><strong>{blockerCount === 0 ? 'Candidate 审核门已通过' : `${blockerCount} 个 blocker 阻止落库`}</strong><span>{warningCount} warning · 诊断来自服务端确定性验证</span></div>
        <p>{blockerCount === 0 ? '当前节点只完成审核；原子创建 Draft 将在 T4-4 开放。' : '选择问题对应的 Schema/字段，逐项处理后会自动保存并重新验证。'}</p>
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
    </>
  );
}

function SaveIndicator({ state }: { state: CandidateReviewState }) {
  if (state.saving) return <div className="candidate-save-state saving"><LoaderCircle className="spin" aria-hidden="true" size={15} /><span>逐项自动保存</span><strong>保存中</strong></div>;
  if (state.saveBlocked) return <div className="candidate-save-state error"><AlertCircle aria-hidden="true" size={15} /><span>AUTOSAVE</span><strong>需处理</strong></div>;
  if (state.dirty) return <div className="candidate-save-state"><LoaderCircle className="spin" aria-hidden="true" size={15} /><span>AUTOSAVE</span><strong>排队中</strong></div>;
  return <div className="candidate-save-state saved"><CheckCircle2 aria-hidden="true" size={15} /><span>AUTOSAVE</span><strong>已保存</strong></div>;
}

function saveErrorMessage(error: unknown) {
  if (error instanceof StudioRequestError && error.problem.status === 409) {
    return 'Candidate revision 已变化。你的本地修改仍保留；请重载后重新审核，或确认没有其他窗口后再试。';
  }
  return error instanceof Error ? error.message : '自动保存失败。';
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
