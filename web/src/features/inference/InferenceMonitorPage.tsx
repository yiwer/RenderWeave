import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertCircle, ArrowRight, CheckCircle2, History, LoaderCircle, Plus, RotateCcw } from 'lucide-react';
import { Link, useNavigate, useParams } from 'react-router-dom';

import { ResourceError, ResourceLoading } from '../resources/DraftListPage';
import { ResourceFrame } from '../resources/ResourceFrame';
import { cancelInferenceRunRequest, getInferenceRunRequest, retryInferenceRunRequest } from './candidate-api';
import { InferenceExecutionLogPanel } from './InferenceExecutionLogPanel';
import { InferenceFlowSteps } from './InferenceFlowSteps';
import { RunCancelButton } from './InferenceRunActions';
import {
  formatInferenceTime,
  inferenceFailureMessage,
  inferenceModeLabel,
  inferenceProfileLabel,
  inferenceRunHasResult,
  inferenceStageLabel,
  inferenceStateLabel,
} from './inference-format';

export function InferenceMonitorPage() {
  const { runId = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: ['inference-run', runId],
    queryFn: () => getInferenceRunRequest(runId),
    enabled: Boolean(runId),
    refetchInterval: (current) => {
      const state = current.state.data?.state;
      return state === 'QUEUED' || state === 'RUNNING' || state === 'APPLYING' ? 1_000 : false;
    },
  });
  const cancelRun = useMutation({
    mutationFn: () => cancelInferenceRunRequest(runId),
    onSuccess: (run) => queryClient.setQueryData(['inference-run', runId], run),
  });
  const retryRun = useMutation({
    mutationFn: () => retryInferenceRunRequest(runId),
    onSuccess: (run) => navigate(`/inference-runs/${run.runId}/monitor`, { replace: true }),
  });
  const resultAvailable = Boolean(query.data && inferenceRunHasResult(query.data.state));
  const flowStep = query.data?.state === 'COMPLETED' || query.data?.state === 'APPLYING'
    ? 4
    : resultAvailable ? 3 : 2;

  return (
    <ResourceFrame
      title="识别监控"
      description="查看归一化、模型调用、确定性校验、修复与费用结算；监控版面不允许编辑 Candidate。"
      actions={<Link className="button primary-button" to="/inference/new"><Plus aria-hidden="true" size={15} />新增识别</Link>}
      breadcrumbs={[{ label: '智能识别', to: '/inference' }, { label: '识别监控' }]}
    >
      <InferenceFlowSteps current={flowStep} />
      {query.isPending && <ResourceLoading label="正在读取识别任务" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && (
        <>
          <InferenceMonitorStatus
            run={query.data}
            cancelPending={cancelRun.isPending}
            retryPending={retryRun.isPending}
            error={cancelRun.error ?? retryRun.error}
            onCancel={() => cancelRun.mutate()}
            onRetry={() => retryRun.mutate()}
          />
          <dl className="inference-monitor-facts" aria-label="识别任务信息">
            <div><dt>运行编号</dt><dd><code>{query.data.runId}</code></dd></div>
            <div><dt>输入模式</dt><dd>{inferenceModeLabel(query.data.mode)}</dd></div>
            <div><dt>执行配置</dt><dd>{inferenceProfileLabel(query.data.profileId)}</dd></div>
            <div><dt>最近更新</dt><dd>{formatInferenceTime(query.data.updatedAt)}</dd></div>
          </dl>
          <InferenceExecutionLogPanel
            runId={query.data.runId}
            state={query.data.state}
            sequence={query.data.sequence}
          />
        </>
      )}
    </ResourceFrame>
  );
}

function InferenceMonitorStatus({
  run,
  cancelPending,
  retryPending,
  error,
  onCancel,
  onRetry,
}: {
  run: import('../../api/generated').InferenceRunResponse;
  cancelPending: boolean;
  retryPending: boolean;
  error: Error | null;
  onCancel: () => void;
  onRetry: () => void;
}) {
  const failed = run.state === 'FAILED' || run.state === 'CANCELLED';
  const ready = inferenceRunHasResult(run.state);
  const historicalProductProfile = /-product-v[1-3]$/.test(run.profileId);
  const failureMessage = run.failureCode ? inferenceFailureMessage(run.failureCode) : null;
  return (
    <section className={`inference-run-progress inference-monitor-status ${failed ? 'failed' : ready ? 'ready' : ''}`} aria-live="polite">
      {failed
        ? <AlertCircle aria-hidden="true" size={24} />
        : ready
          ? <CheckCircle2 aria-hidden="true" size={24} />
          : <LoaderCircle className="spin" aria-hidden="true" size={24} />}
      <div>
        <strong>{failed ? '识别任务未生成 Candidate' : ready ? 'Candidate 已生成' : '正在执行受控识别流程'}</strong>
        <span>{inferenceStateLabel(run.state)} · {inferenceStageLabel(run.stage)} · {inferenceProfileLabel(run.profileId)}</span>
        {run.failureCode && (
          <>
            <code>{run.failureCode}</code>
            {failureMessage && <p className="inference-failure-guidance">{failureMessage}</p>}
            {historicalProductProfile && (
              <p className="inference-failure-guidance">该任务保存的是历史执行配置，直接重试仍会沿用旧时限。</p>
            )}
          </>
        )}
      </div>
      <div className="inference-run-progress-actions">
        {ready && <Link className="button primary-button" to={`/inference-runs/${run.runId}/review`}>查看识别结果<ArrowRight aria-hidden="true" size={15} /></Link>}
        {failed
          ? historicalProductProfile
            ? <Link className="button primary-button" to="/inference/new"><RotateCcw aria-hidden="true" size={15} />用新配置重新识别</Link>
            : <button type="button" className="button primary-button" disabled={retryPending} onClick={onRetry}><RotateCcw aria-hidden="true" size={15} />{retryPending ? '正在创建新任务…' : '重新运行'}</button>
          : !ready && <RunCancelButton pending={cancelPending} onCancel={onCancel} />}
        <Link className="button ghost-button" to="/inference"><History aria-hidden="true" size={15} />历史任务</Link>
        {error && <p role="alert">{error.message}</p>}
      </div>
    </section>
  );
}
