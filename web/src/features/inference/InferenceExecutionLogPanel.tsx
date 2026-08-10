import { useQuery } from '@tanstack/react-query';
import {
  Activity,
  Bot,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Coins,
  ListChecks,
  RefreshCw,
  TriangleAlert,
  XCircle,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

import type {
  InferenceAttempt,
  InferenceExecutionEvent,
  InferenceRunResponse,
} from '../../api/generated';
import { getInferenceExecutionLogRequest } from './candidate-api';
import { problemLabel } from './candidate-format';
import { inferenceStageLabel, inferenceStateLabel } from './inference-format';

const INITIAL_VISIBLE_ENTRIES = 100;

export function InferenceExecutionLogPanel({
  runId,
  state,
  sequence,
}: {
  runId: string;
  state: InferenceRunResponse['state'];
  sequence: number;
}) {
  const [expanded, setExpanded] = useState(true);
  const [visibleEntries, setVisibleEntries] = useState(INITIAL_VISIBLE_ENTRIES);
  const lastSequence = useRef(sequence);
  const query = useQuery({
    queryKey: ['inference-execution-log', runId],
    queryFn: () => getInferenceExecutionLogRequest(runId),
    refetchInterval: state === 'QUEUED' || state === 'RUNNING' ? 1_000 : false,
  });
  const refetchLog = query.refetch;

  useEffect(() => {
    if (lastSequence.current === sequence) return;
    lastSequence.current = sequence;
    void refetchLog();
  }, [refetchLog, sequence]);

  const timeline = useMemo(() => {
    if (!query.data) return [];
    return [
      ...query.data.events.map((event) => ({ kind: 'event' as const, at: event.occurredAt, event })),
      ...query.data.attempts.map((attempt) => ({ kind: 'attempt' as const, at: attempt.completedAt, attempt })),
    ].sort((left, right) => left.at.localeCompare(right.at)
      || (left.kind === 'event' ? -1 : 1));
  }, [query.data]);
  const hiddenEntries = Math.max(0, timeline.length - visibleEntries);
  const visibleTimeline = hiddenEntries > 0 ? timeline.slice(hiddenEntries) : timeline;
  const totals = summarizeAttempts(query.data?.attempts ?? []);

  return (
    <section className="inference-execution-log" aria-labelledby="inference-execution-log-title" aria-busy={query.isFetching}>
      <header>
        <div className="inference-log-title">
          <Activity aria-hidden="true" size={18} />
          <span>
            <h2 id="inference-execution-log-title">执行日志</h2>
            <small>结构化运行记录，不包含图片、Prompt、模型原文或思维链</small>
          </span>
        </div>
        <div className="inference-log-actions">
          <button
            type="button"
            className="button ghost-button"
            disabled={query.isFetching}
            onClick={() => void refetchLog()}
          >
            <RefreshCw className={query.isFetching ? 'spin' : ''} aria-hidden="true" size={15} />
            {query.isFetching ? '刷新中' : '刷新'}
          </button>
          <button
            type="button"
            className="button ghost-button inference-log-toggle"
            aria-expanded={expanded}
            aria-controls="inference-execution-log-body"
            onClick={() => setExpanded((current) => !current)}
          >
            <ChevronDown aria-hidden="true" size={16} />
            {expanded ? '收起' : '展开'}
          </button>
        </div>
      </header>

      {expanded && (
        <div id="inference-execution-log-body" className="inference-log-body">
          {query.isPending && <div className="inference-log-state"><RefreshCw className="spin" aria-hidden="true" size={17} />正在读取执行日志</div>}
          {query.isError && (
            <div className="inference-log-state error" role="alert">
              <TriangleAlert aria-hidden="true" size={17} />
              <span>{query.error.message}</span>
              <button type="button" className="button ghost-button" onClick={() => void refetchLog()}>重新读取</button>
            </div>
          )}
          {query.data && (
            <>
              <div className="inference-log-summary" aria-label="执行日志汇总">
                <LogMetric icon={<ListChecks aria-hidden="true" size={16} />} label="阶段事件" value={formatInteger(query.data.events.length)} detail={`当前序列 ${query.data.run.sequence}`} />
                <LogMetric icon={<Bot aria-hidden="true" size={16} />} label="模型调用" value={formatInteger(query.data.attempts.length)} detail={query.data.attempts.length === 0 ? '未发生外部调用' : `${totals.problemCount} 个校验信号`} />
                <LogMetric icon={<Clock3 aria-hidden="true" size={16} />} label="Token" value={formatInteger(totals.inputTokens + totals.outputTokens)} detail={`输入 ${formatInteger(totals.inputTokens)} · 输出 ${formatInteger(totals.outputTokens)}`} />
                <LogMetric icon={<Coins aria-hidden="true" size={16} />} label="调用费用" value={formatCost(totals.costMicrosCny)} detail={`调用耗时 ${formatDuration(totals.durationMillis)}`} />
              </div>

              {query.data.truncated && (
                <p className="inference-log-truncated" role="note">
                  <TriangleAlert aria-hidden="true" size={15} />日志事件超过 1000 条，当前仅展示服务端返回的受控窗口。
                </p>
              )}

              {hiddenEntries > 0 && (
                <button
                  type="button"
                  className="button ghost-button inference-log-more"
                  onClick={() => setVisibleEntries((current) => Math.min(timeline.length, current + 100))}
                >
                  显示更早记录（剩余 {hiddenEntries} 条）
                </button>
              )}
              {timeline.length === 0
                ? <div className="inference-log-empty">任务刚刚创建，尚无可展示的执行事件。</div>
                : (
                  <ol className="inference-log-timeline" aria-label="推断执行时间线">
                    {visibleTimeline.map((entry) => entry.kind === 'event'
                      ? <EventLogItem key={`event-${entry.event.sequence}`} event={entry.event} />
                      : <AttemptLogItem key={`attempt-${entry.attempt.attemptOrdinal}`} attempt={entry.attempt} />)}
                  </ol>
                )}
            </>
          )}
        </div>
      )}
    </section>
  );
}

function LogMetric({ icon, label, value, detail }: { icon: React.ReactNode; label: string; value: string; detail: string }) {
  return (
    <div>
      <span className="inference-log-metric-icon">{icon}</span>
      <span><small>{label}</small><strong>{value}</strong><em>{detail}</em></span>
    </div>
  );
}

function EventLogItem({ event }: { event: InferenceExecutionEvent }) {
  const failed = event.state === 'FAILED' || event.state === 'CANCELLED';
  return (
    <li className={`inference-log-entry event ${failed ? 'failed' : ''}`}>
      <span className="inference-log-marker">
        {failed ? <XCircle aria-hidden="true" size={16} /> : <CheckCircle2 aria-hidden="true" size={16} />}
      </span>
      <div className="inference-log-entry-main">
        <div className="inference-log-entry-heading">
          <strong>{eventTypeLabel(event.type)}</strong>
          <time dateTime={event.occurredAt}>{formatTime(event.occurredAt)}</time>
        </div>
        <p>{inferenceStateLabel(event.state)} · {inferenceStageLabel(event.stage)}</p>
        <span className="inference-log-sequence">事件 #{event.sequence}</span>
      </div>
    </li>
  );
}

function AttemptLogItem({ attempt }: { attempt: InferenceAttempt }) {
  const failed = attempt.status !== 'SUCCEEDED';
  const problems = Object.entries(attempt.problemCodeCounts).sort(([left], [right]) => left.localeCompare(right));
  return (
    <li className={`inference-log-entry attempt ${failed ? 'failed' : ''}`}>
      <span className="inference-log-marker"><Bot aria-hidden="true" size={16} /></span>
      <div className="inference-log-entry-main">
        <div className="inference-log-entry-heading">
          <strong>模型调用 #{attempt.attemptOrdinal + 1} · {inferenceStageLabel(attempt.stage)}</strong>
          <time dateTime={attempt.completedAt}>{formatTime(attempt.completedAt)}</time>
        </div>
        <p>{attemptStatusLabel(attempt.status)} · {attempt.providerModel ?? '本地 replay'} · {formatDuration(attempt.durationMillis)}</p>
        <div className="inference-attempt-facts">
          <span>输入 {formatInteger(attempt.inputTokens)} tokens</span>
          <span>输出 {formatInteger(attempt.outputTokens)} tokens</span>
          <span>{formatCost(attempt.costMicrosCny)}</span>
          <code>{attempt.outcomeCode}</code>
        </div>
        {problems.length > 0 && (
          <ul className="inference-attempt-problems" aria-label={`模型调用 ${attempt.attemptOrdinal + 1} 的校验问题`}>
            {problems.map(([code, count]) => (
              <li key={code} title={problemLabel(code)}>
                <span>{problemLabel(code)}</span><code>{code}</code><strong>× {count}</strong>
              </li>
            ))}
          </ul>
        )}
      </div>
    </li>
  );
}

function summarizeAttempts(attempts: InferenceAttempt[]) {
  return attempts.reduce((summary, attempt) => ({
    inputTokens: summary.inputTokens + attempt.inputTokens,
    outputTokens: summary.outputTokens + attempt.outputTokens,
    costMicrosCny: summary.costMicrosCny + attempt.costMicrosCny,
    durationMillis: summary.durationMillis + attempt.durationMillis,
    problemCount: summary.problemCount
      + Object.values(attempt.problemCodeCounts).reduce((sum, count) => sum + count, 0),
  }), { inputTokens: 0, outputTokens: 0, costMicrosCny: 0, durationMillis: 0, problemCount: 0 });
}

function eventTypeLabel(type: string) {
  const labels: Record<string, string> = {
    QUEUED: '任务已进入队列',
    RETRIED: '已创建可审计重试',
    LEASE_ACQUIRED: '执行器已领取任务',
    LEASE_RECLAIMED: '执行器已恢复任务',
    CHECKPOINT_ADVANCED: '流程检查点已推进',
    PROVIDER_ATTEMPT_FAILED: '模型调用失败',
    PROVIDER_ATTEMPT_REJECTED: '模型响应未通过阶段合同',
    REVIEW_REQUIRED: 'Candidate 已进入人工审核',
    CANDIDATE_UPDATED: '审核修改已保存',
    CANCELLATION_REQUESTED: '已请求取消任务',
    CANCELLED: '任务已取消',
    FAILED: '任务已失败',
    APPLYING: '开始原子创建 Draft',
    CANDIDATE_APPLIED: 'Draft Bundle 已创建',
  };
  return labels[type] ?? type;
}

function attemptStatusLabel(status: InferenceAttempt['status']) {
  if (status === 'SUCCEEDED') return '响应已接受';
  if (status === 'REJECTED') return '响应被合同拒绝';
  return '调用失败';
}

function formatInteger(value: number) {
  return new Intl.NumberFormat('zh-CN').format(value);
}

function formatCost(microsCny: number) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency', currency: 'CNY', minimumFractionDigits: microsCny === 0 ? 2 : 4, maximumFractionDigits: 6,
  }).format(microsCny / 1_000_000);
}

function formatDuration(millis: number) {
  if (millis < 1_000) return `${millis} ms`;
  if (millis < 60_000) return `${(millis / 1_000).toFixed(millis < 10_000 ? 1 : 0)} 秒`;
  return `${Math.floor(millis / 60_000)} 分 ${Math.round((millis % 60_000) / 1_000)} 秒`;
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(value));
}
