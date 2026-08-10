import { Activity, ArrowRight, Braces, CheckCircle2, ChevronLeft, ChevronRight, History, Plus, RefreshCw } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { ResourceError, ResourceLoading } from '../resources/DraftListPage';
import { ResourceFrame } from '../resources/ResourceFrame';
import { listInferenceRunsRequest } from './candidate-api';
import {
  formatInferenceTime,
  inferenceModeLabel,
  inferenceProfileLabel,
  inferenceRunActionLabel,
  inferenceRunStateLabel,
  inferenceRunWorkspacePath,
  inferenceStageLabel,
} from './inference-format';

const PAGE_SIZE = 10;

export function InferenceHistoryPage() {
  const [page, setPage] = useState(1);
  const query = useQuery({
    queryKey: ['inference-runs', page, PAGE_SIZE],
    queryFn: () => listInferenceRunsRequest(page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  });
  const totalPages = Math.max(1, Math.ceil((query.data?.total ?? 0) / PAGE_SIZE));
  const items = query.data?.items ?? [];
  const running = items.filter((run) => run.state === 'QUEUED' || run.state === 'RUNNING' || run.state === 'APPLYING').length;
  const reviewRequired = items.filter((run) => run.state === 'REVIEW_REQUIRED').length;
  const completed = items.filter((run) => run.state === 'COMPLETED').length;

  return (
    <ResourceFrame
      title="历史识别任务"
      description="集中查看每次识别的输入模式、执行状态与结果去向；运行记录持久化，可随时恢复监控或继续校对。"
      actions={(
        <>
          <Link className="button ghost-button" to="/inference/samples"><Braces aria-hidden="true" size={15} />确定性样本</Link>
          <Link className="button primary-button" to="/inference/new"><Plus aria-hidden="true" size={15} />新增识别</Link>
        </>
      )}
      breadcrumbs={[{ label: '智能识别' }, { label: '历史任务' }]}
    >
      <dl className="inference-history-summary" aria-label="识别任务概览">
        <div><History aria-hidden="true" size={17} /><span><dt>任务总量</dt><dd>{query.data?.total ?? '—'}</dd></span></div>
        <div><Activity aria-hidden="true" size={17} /><span><dt>本页进行中</dt><dd>{running}</dd></span></div>
        <div><RefreshCw aria-hidden="true" size={17} /><span><dt>本页待校对</dt><dd>{reviewRequired}</dd></span></div>
        <div><CheckCircle2 aria-hidden="true" size={17} /><span><dt>本页已完成</dt><dd>{completed}</dd></span></div>
      </dl>

      <section className="recent-inference-runs inference-history-list" aria-labelledby="inference-history-title">
        <header>
          <div>
            <span className="recent-inference-icon"><History aria-hidden="true" size={18} /></span>
            <span>
              <h2 id="inference-history-title">全部识别记录</h2>
              <p>点击任务会按当前状态进入识别监控或结果版面。</p>
            </span>
          </div>
          <button type="button" className="button ghost-button" disabled={query.isFetching} onClick={() => void query.refetch()}>
            <RefreshCw aria-hidden="true" size={15} />{query.isFetching ? '正在刷新' : '刷新'}
          </button>
        </header>
        {query.isPending && <ResourceLoading label="正在读取历史识别任务" />}
        {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
        {query.data && items.length === 0 && (
          <div className="recent-inference-empty">
            <History aria-hidden="true" size={22} />
            <strong>还没有识别任务</strong>
            <span>新建一次 AI 识别或运行确定性样本后，任务会在这里持续留档。</span>
            <Link className="button primary-button" to="/inference/new"><Plus aria-hidden="true" size={15} />新增识别</Link>
          </div>
        )}
        {query.data && items.length > 0 && (
          <>
            <div className="inference-history-columns" aria-hidden="true">
              <span>状态</span><span>任务与阶段</span><span>执行配置</span><span>关联</span><span>操作</span>
            </div>
            <div className="recent-inference-list">
              {items.map((run) => (
                <Link key={run.runId} to={inferenceRunWorkspacePath(run)} className="recent-inference-row">
                  <span className={`recent-inference-state state-${run.state.toLowerCase()}`}>{inferenceRunStateLabel(run)}</span>
                  <span className="recent-inference-main">
                    <strong>{inferenceModeLabel(run.mode)} · {inferenceStageLabel(run.stage)}</strong>
                    <small>{run.sourceReference} · {formatInferenceTime(run.updatedAt)}</small>
                  </span>
                  <span className="recent-inference-profile">{inferenceProfileLabel(run.profileId)}</span>
                  <span>{run.retryOfRunId ? <span className="recent-inference-retry">重试任务</span> : <span className="recent-inference-origin">首次运行</span>}</span>
                  <span className="recent-inference-action">{inferenceRunActionLabel(run.state)}<ArrowRight aria-hidden="true" size={15} /></span>
                </Link>
              ))}
            </div>
          </>
        )}
      </section>
      {query.data && query.data.total > 0 && (
        <nav className="inference-history-pagination" aria-label="历史识别任务分页">
          <span>共 {query.data.total} 项 · 第 {page} / {totalPages} 页</span>
          <div>
            <button type="button" aria-label="上一页" disabled={page === 1 || query.isFetching} onClick={() => setPage((value) => value - 1)}><ChevronLeft aria-hidden="true" size={16} /></button>
            <button type="button" aria-label="下一页" disabled={page === totalPages || query.isFetching} onClick={() => setPage((value) => value + 1)}><ChevronRight aria-hidden="true" size={16} /></button>
          </div>
        </nav>
      )}
    </ResourceFrame>
  );
}
