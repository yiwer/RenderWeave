import { useQuery } from '@tanstack/react-query';
import {
  Activity,
  Bot,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Coins,
  GitBranch,
  ListChecks,
  MapPinned,
  RefreshCw,
  RotateCcw,
  TriangleAlert,
  XCircle,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

import type {
  InferenceAttempt,
  InferenceExecutionEvent,
  InferenceExecutionLogResponse,
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
  const stages = summarizeStages(query.data);
  const issues = summarizeIssues(query.data?.attempts ?? []);
  const recovery = summarizeRecovery(query.data);

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

              <div className="inference-log-grid">
                <div className="inference-log-main">
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
                </div>

                <div className="inference-log-rail">
                  <StageTelemetry stages={stages} />

                  <div className={`inference-recovery-summary ${recovery.tone}`} aria-label="恢复状态">
                    <span className="inference-recovery-icon"><RotateCcw aria-hidden="true" size={16} /></span>
                    <span><strong>{recovery.title}</strong><small>{recovery.detail}</small></span>
                    <em>{recovery.checkpointCount} 个检查点事件</em>
                  </div>

                  {issues.length > 0 && <IssueTelemetry issues={issues} />}
                </div>
              </div>
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

type StageTone = 'pending' | 'active' | 'repairing' | 'verified' | 'blocked';

type StageSummary = {
  stage: InferenceAttempt['stage'];
  label: string;
  scope: string;
  tone: StageTone;
  attemptCount: number;
  problemCount: number;
};

type IssueSummary = {
  code: string;
  count: number;
  scope: string;
  earliestStage: InferenceAttempt['stage'];
};

function StageTelemetry({ stages }: { stages: StageSummary[] }) {
  return (
    <section className="inference-stage-telemetry" aria-labelledby="inference-stage-telemetry-title">
      <header>
        <span><GitBranch aria-hidden="true" size={16} /></span>
        <span>
          <h3 id="inference-stage-telemetry-title">阶段与检查点</h3>
          <small>每个阶段只展示状态、有限问题计数和受控定位范围</small>
        </span>
      </header>
      <ol>
        {stages.map((stage) => (
          <li key={stage.stage} className={stage.tone}>
            <span className="inference-stage-state">{stageToneLabel(stage.tone, stage.stage)}</span>
            <strong>{stage.label}</strong>
            <small>{stage.scope}</small>
            <span className="inference-stage-counts">
              {stage.attemptCount === 0
                ? '零外部调用'
                : stage.stage === 'REPAIR' ? `${stage.attemptCount} 次触发` : `${stage.attemptCount} 次调用`}
              {stage.problemCount > 0 && ` · ${stage.problemCount} 个问题`}
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}

function IssueTelemetry({ issues }: { issues: IssueSummary[] }) {
  const total = issues.reduce((sum, issue) => sum + issue.count, 0);
  return (
    <section className="inference-issue-telemetry" aria-labelledby="inference-issue-telemetry-title">
      <header>
        <span><MapPinned aria-hidden="true" size={16} /></span>
        <span>
          <h3 id="inference-issue-telemetry-title">有限问题定位</h3>
          <small>{total} 个校验信号；不包含 OCR、图片、Prompt 或 Provider 原文</small>
        </span>
      </header>
      <ul>
        {issues.map((issue) => (
          <li key={`${issue.earliestStage}:${issue.code}`}>
            <span className="inference-issue-scope">{issue.scope}</span>
            <span>
              <strong>{problemLabel(issue.code)}</strong>
              <small>最早返回 {inferenceStageLabel(issue.earliestStage)} 修复</small>
            </span>
            <code>{issue.code}</code>
            <b aria-label={`${issue.count} 项`}>× {issue.count}</b>
          </li>
        ))}
      </ul>
    </section>
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
        {attempt.rejectionEnvelope && (
          <div
            className="inference-rejection-envelope"
            aria-label={`模型调用 ${attempt.attemptOrdinal + 1} 的拒绝分类`}
          >
            <span>拒绝分类 · 最早阶段 {inferenceStageLabel(attempt.rejectionEnvelope.earliestStage)}</span>
            <code>{attempt.rejectionEnvelope.primaryCode}</code>
            <strong>{attempt.rejectionEnvelope.detailCodeCount} 项固定字段诊断</strong>
          </div>
        )}
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

const STAGE_DEFINITIONS: Array<Pick<StageSummary, 'stage' | 'label' | 'scope'>> = [
  { stage: 'OBSERVE', label: '感知与区域', scope: '元素盘点 · region forest' },
  { stage: 'HIERARCHY', label: '层级语义', scope: '实体、关系与空间归属' },
  { stage: 'ELEMENT_BINDING', label: '元素归属', scope: '字段绑定 · 最近实体' },
  { stage: 'STRUCTURE', label: 'Candidate 构建', scope: '确定性编译与合同' },
  { stage: 'REPAIR', label: '定向修复', scope: '最早失败阶段 · selected crop' },
];

const STAGE_ORDER: Record<string, number> = {
  NORMALIZE: 0,
  OBSERVE: 1,
  HIERARCHY: 2,
  ELEMENT_BINDING: 3,
  STRUCTURE: 4,
  DETERMINISTIC_VALIDATE: 5,
  CRITIQUE: 6,
  REPAIR: 7,
  USER_APPROVAL: 8,
  ATOMIC_CREATE: 9,
};

function summarizeStages(log?: InferenceExecutionLogResponse): StageSummary[] {
  const attempts = log?.attempts ?? [];
  return STAGE_DEFINITIONS.map((definition) => {
    if (definition.stage === 'REPAIR') {
      const rejected = attempts.filter((attempt) => attempt.status === 'REJECTED');
      const problemCount = rejected.reduce((sum, attempt) => sum
        + Object.values(attempt.problemCodeCounts).reduce((attemptSum, count) => attemptSum + count, 0), 0);
      const latestRejectedOrdinal = Math.max(...rejected.map((attempt) => attempt.attemptOrdinal));
      const latestSucceededOrdinal = Math.max(...attempts
        .filter((attempt) => attempt.status === 'SUCCEEDED')
        .map((attempt) => attempt.attemptOrdinal));
      const recovered = rejected.length > 0 && latestSucceededOrdinal > latestRejectedOrdinal;
      const terminalFailure = log?.run.state === 'FAILED' || log?.run.state === 'CANCELLED';
      return {
        ...definition,
        attemptCount: rejected.length,
        problemCount,
        tone: rejected.length === 0
          ? 'pending'
          : terminalFailure && !recovered
            ? 'blocked'
            : log?.run.state === 'RUNNING'
              ? 'repairing'
              : 'verified',
      };
    }
    const stageAttempts = attempts.filter((attempt) => attempt.stage === definition.stage);
    const problemCount = stageAttempts.reduce((sum, attempt) => sum
      + Object.values(attempt.problemCodeCounts).reduce((attemptSum, count) => attemptSum + count, 0), 0);
    return {
      ...definition,
      attemptCount: stageAttempts.length,
      problemCount,
      tone: stageTone(log, definition.stage, stageAttempts),
    };
  });
}

function stageTone(
  log: InferenceExecutionLogResponse | undefined,
  stage: InferenceAttempt['stage'],
  attempts: InferenceAttempt[],
): StageTone {
  if (attempts.some((attempt) => attempt.status === 'SUCCEEDED')) return 'verified';
  if (attempts.some((attempt) => attempt.status !== 'SUCCEEDED')) {
    return log?.run.state === 'RUNNING' && log.run.stage === stage ? 'repairing' : 'blocked';
  }
  if (!log) return 'pending';
  if ((STAGE_ORDER[log.run.stage] ?? -1) > (STAGE_ORDER[stage] ?? Number.MAX_SAFE_INTEGER)) {
    return 'verified';
  }
  if (log.run.state === 'REVIEW_REQUIRED' || log.run.state === 'APPLYING' || log.run.state === 'COMPLETED') {
    return 'verified';
  }
  return log.run.state === 'RUNNING' && log.run.stage === stage ? 'active' : 'pending';
}

function stageToneLabel(tone: StageTone, stage?: InferenceAttempt['stage']) {
  if (stage === 'REPAIR') {
    const labels: Record<StageTone, string> = {
      pending: '未触发',
      active: '准备修复',
      repairing: '正在修复',
      verified: '修复后推进',
      blocked: '修复未通过',
    };
    return labels[tone];
  }
  const labels: Record<StageTone, string> = {
    pending: '待执行',
    active: '正在执行',
    repairing: '阶段内修复',
    verified: '检查点已验证',
    blocked: '在此停止',
  };
  return labels[tone];
}

function summarizeIssues(attempts: InferenceAttempt[]): IssueSummary[] {
  const indexed = new Map<string, IssueSummary>();
  for (const attempt of attempts) {
    for (const [code, count] of Object.entries(attempt.problemCodeCounts)) {
      const earliestStage = earliestRepairStage(code, attempt.stage);
      const key = `${earliestStage}:${code}`;
      const existing = indexed.get(key);
      indexed.set(key, {
        code,
        count: (existing?.count ?? 0) + count,
        scope: problemScope(code),
        earliestStage,
      });
    }
  }
  return [...indexed.values()].sort((left, right) =>
    (STAGE_ORDER[left.earliestStage] ?? 99) - (STAGE_ORDER[right.earliestStage] ?? 99)
      || left.code.localeCompare(right.code));
}

function earliestRepairStage(code: string, fallback: InferenceAttempt['stage']): InferenceAttempt['stage'] {
  if (code.startsWith('VISUAL_BINDINGS') || code.startsWith('VISUAL_SEMANTIC_BINDING')) {
    return 'ELEMENT_BINDING';
  }
  if (code.startsWith('VISUAL_HIERARCHY') || code.startsWith('VISUAL_SEMANTIC_HIERARCHY')) {
    return 'HIERARCHY';
  }
  if (code.startsWith('VISUAL_GROUNDING') || code.startsWith('VISUAL_SEMANTIC')) {
    return 'OBSERVE';
  }
  if (code.startsWith('CANDIDATE_') || code.startsWith('VISUAL_PLAN_')) return 'REPAIR';
  return fallback;
}

function problemScope(code: string) {
  if (code.startsWith('VISUAL_BINDINGS') || code.startsWith('VISUAL_SEMANTIC_BINDING')) return '字段归属';
  if (code.startsWith('VISUAL_HIERARCHY') || code.startsWith('VISUAL_SEMANTIC_HIERARCHY')) return '层级边';
  if (code.includes('REPEATED') || code.includes('GROUP')) return '重复区域';
  if (code.startsWith('VISUAL_GROUNDING') || code.includes('REGION')) return '区域树';
  if (code.includes('EVIDENCE')) return '证据区域';
  if (code.startsWith('CANDIDATE_') || code.startsWith('VISUAL_PLAN_')) return 'Candidate';
  return '阶段合同';
}

function summarizeRecovery(log?: InferenceExecutionLogResponse) {
  const checkpointCount = log?.events.filter((event) => event.type === 'CHECKPOINT_ADVANCED').length ?? 0;
  if (!log) return { title: '正在读取恢复状态', detail: '等待结构化事件', tone: 'neutral', checkpointCount };
  const reclaimed = log.events.filter((event) => event.type === 'LEASE_RECLAIMED').length;
  const rejected = log.attempts.filter((attempt) => attempt.status === 'REJECTED').length;
  const preserved = new Set(log.attempts
    .filter((attempt) => attempt.status === 'SUCCEEDED')
    .map((attempt) => attempt.stage)).size;
  if (reclaimed > 0) {
    const failed = log.run.state === 'FAILED';
    const cancelled = log.run.state === 'CANCELLED';
    return {
      title: failed ? '从检查点恢复后仍失败' : cancelled ? '从检查点恢复后安全取消' : '已从持久检查点恢复',
      detail: `${reclaimed} 次 lease 重领；已验证阶段继续复用${failed ? '，最终固定问题码已保留' : ''}`,
      tone: failed ? 'error' : cancelled ? 'neutral' : 'success',
      checkpointCount,
    };
  }
  if (log.run.state === 'RUNNING' && log.run.cancellationRequested) {
    return {
      title: '正在等待安全取消',
      detail: '当前调用完成后在下一个检查点停止，不启动新阶段',
      tone: 'warning',
      checkpointCount,
    };
  }
  if (log.run.state === 'CANCELLED') {
    return {
      title: '已在安全检查点取消',
      detail: '已结算 attempt 与已验证检查点保持可审计',
      tone: 'neutral',
      checkpointCount,
    };
  }
  if (log.run.retryOfRunId) {
    return {
      title: '当前为审计重试任务',
      detail: '源任务保持只读；本次运行拥有独立编号和时间线',
      tone: 'active',
      checkpointCount,
    };
  }
  if (rejected > 0) {
    const failed = log.run.state === 'FAILED';
    return {
      title: failed ? '定向修复未通过' : log.run.state === 'RUNNING' ? '正在阶段内定向修复' : '阶段内定向修复已完成',
      detail: `${rejected} 次响应由最早失败阶段处理；${preserved > 0 ? `${preserved} 个已通过阶段保持复用` : '未重做无关阶段'}`,
      tone: failed ? 'error' : log.run.state === 'RUNNING' ? 'warning' : 'success',
      checkpointCount,
    };
  }
  if (log.run.state === 'FAILED') {
    return {
      title: '恢复路径可用',
      detail: '固定问题码与完成检查点已保留，可创建独立审计重试',
      tone: 'error',
      checkpointCount,
    };
  }
  if (log.run.state === 'RUNNING' || log.run.state === 'QUEUED') {
    return {
      title: '检查点持续写入',
      detail: '崩溃或 lease 失效后只从最近安全边界继续',
      tone: 'active',
      checkpointCount,
    };
  }
  return {
    title: '无需恢复',
    detail: '本次流程未发生 lease 重领、取消或审计重试',
    tone: 'neutral',
    checkpointCount,
  };
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
