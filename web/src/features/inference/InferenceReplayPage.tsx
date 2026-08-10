import { useMutation, useQuery } from '@tanstack/react-query';
import { ArrowRight, Braces, CheckCircle2, Network, Play, ShieldCheck } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import type { InferenceMode, ReplayFixtureResponse } from '../../api/generated';
import { ResourceError, ResourceLoading } from '../resources/DraftListPage';
import { ResourceFrame } from '../resources/ResourceFrame';
import { createReplayRunRequest, listReplayFixturesRequest } from './candidate-api';
import { InferenceFlowSteps } from './InferenceFlowSteps';
import { InferenceInputModeTabs } from './InferenceInputModeTabs';
import { inferenceModeLabels } from './inference-mode';

export function InferenceReplayPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<InferenceMode>('COMBINED');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const query = useQuery({ queryKey: ['replay-fixtures'], queryFn: listReplayFixturesRequest });
  const fixtures = useMemo(
    () => Array.from(query.data?.items ?? []).filter((item) => item.mode === mode),
    [mode, query.data],
  );
  const selected = fixtures.find((item) => item.fixtureId === selectedId) ?? fixtures[0];
  const createRun = useMutation({
    mutationFn: (fixture: ReplayFixtureResponse) => createReplayRunRequest(fixture.fixtureId, crypto.randomUUID()),
    onSuccess: (run) => navigate(`/inference-runs/${run.runId}/monitor`),
  });

  return (
    <ResourceFrame
      title="确定性样本"
      description="从合成样本目录运行可复现的本地识别流程；全程禁止外部网络，不上传真实图片或业务数据。"
      actions={<Link className="button ghost-button" to="/inference">返回历史任务</Link>}
      breadcrumbs={[{ label: '智能识别', to: '/inference' }, { label: '确定性样本' }]}
    >
      <InferenceFlowSteps current={1} />
      <section className="replay-contract" aria-label="Replay 执行边界">
        <div><ShieldCheck aria-hidden="true" size={19} /><span>执行配置</span><strong>{query.data?.profileId ?? 'replay-v1'}</strong></div>
        <div><Network aria-hidden="true" size={19} /><span>外部网络</span><strong className="contract-safe">禁止</strong></div>
        <div><Braces aria-hidden="true" size={19} /><span>数据范围</span><strong>仅合成样本</strong></div>
        <p><CheckCircle2 aria-hidden="true" size={15} />本流程不会调用 DashScope，也不会读取新增识别页面中的文件。</p>
      </section>

      <div className="replay-layout">
        <section className="replay-catalog" aria-label="Replay 样本目录">
          <header><div><h2>选择推断场景</h2></div><span>{fixtures.length} / 60</span></header>
          <InferenceInputModeTabs mode={mode} onChange={(value) => { setMode(value); setSelectedId(null); setConfirmed(false); }} />
          {query.isPending && <ResourceLoading label="正在读取合成样本" />}
          {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
          <div className="fixture-list">
            {fixtures.map((fixture) => (
              <button
                key={fixture.fixtureId}
                type="button"
                className={selected?.fixtureId === fixture.fixtureId ? 'fixture-row selected' : 'fixture-row'}
                aria-pressed={selected?.fixtureId === fixture.fixtureId}
                onClick={() => { setSelectedId(fixture.fixtureId); setConfirmed(false); }}
              >
                <span className="fixture-number">{fixture.fixtureId.match(/\d+/)?.[0] ?? '—'}</span>
                <span className="fixture-name"><strong>{humanScenario(fixture.scenario)}</strong><code>{fixture.fixtureId}</code></span>
                <span>{fixture.expectedSchemaCount} Schema</span>
                <ArrowRight aria-hidden="true" size={15} />
              </button>
            ))}
          </div>
        </section>

        <aside className="replay-launch-panel" aria-label="运行确认">
          <h2>{selected ? humanScenario(selected.scenario) : '选择一个场景'}</h2>
          {selected && (
            <>
              <code className="fixture-id">{selected.fixtureId}</code>
              <dl className="fixture-metrics">
                <div><dt>输入模式</dt><dd>{inferenceModeLabels[selected.mode]}</dd></div>
                <div><dt>图片</dt><dd>{selected.imageCount}</dd></div>
                <div><dt>JSON 样本</dt><dd>{selected.jsonSampleCount}</dd></div>
                <div><dt>预期 Schema</dt><dd>{selected.expectedSchemaCount}</dd></div>
              </dl>
              <div className="expected-problems">
                <span>预期审核信号</span>
                {selected.expectedProblemCodes.length === 0
                  ? <strong>无预置 blocker</strong>
                  : selected.expectedProblemCodes.map((code) => <code key={code}>{code}</code>)}
              </div>
              <label className="replay-confirmation">
                <input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
                <span><strong>确认使用 replay-v1</strong>仅处理当前合成样本，外部传输关闭。</span>
              </label>
              <button type="button" className="button primary-button replay-launch" disabled={!confirmed || createRun.isPending} onClick={() => createRun.mutate(selected)}>
                <Play aria-hidden="true" size={16} />{createRun.isPending ? '正在创建任务…' : '运行并查看监控'}
              </button>
              {createRun.isError && <p className="replay-error" role="alert">{errorMessage(createRun.error)}</p>}
            </>
          )}
          <p className="replay-footnote">运行记录和 Candidate 会持久化；Draft 表在审核完成前保持不变。</p>
        </aside>
      </div>
    </ResourceFrame>
  );
}

function humanScenario(scenario: string) {
  return scenario.replaceAll('-', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '创建任务失败';
}
