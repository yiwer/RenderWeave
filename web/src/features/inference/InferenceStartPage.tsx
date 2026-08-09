import { useMutation, useQuery, type UseMutationResult, type UseQueryResult } from '@tanstack/react-query';
import {
  ArrowRight,
  Bot,
  Braces,
  CheckCircle2,
  CircleDollarSign,
  Cloud,
  FileJson2,
  Image,
  Images,
  Network,
  Play,
  ShieldCheck,
  Upload,
} from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import type {
  CreateLiveRunRequest,
  InferenceMode,
  InferenceRunResponse,
  LiveAvailabilityResponse,
  LiveProfileResponse,
  ReplayFixtureListResponse,
  ReplayFixtureResponse,
} from '../../api/generated';
import { ResourceError, ResourceLoading } from '../resources/DraftListPage';
import { ResourceFrame } from '../resources/ResourceFrame';
import {
  createLiveRunRequest,
  createReplayRunRequest,
  getLiveAvailabilityRequest,
  listReplayFixturesRequest,
} from './candidate-api';

type Launcher = 'REPLAY' | 'LIVE';
type LiveProfileId = CreateLiveRunRequest['profileId'];

const modeLabels: Record<InferenceMode, string> = {
  IMAGE_ONLY: '仅图片',
  JSON_ONLY: '仅 JSON',
  COMBINED: '图片 + JSON',
};

const modeIcons = {
  IMAGE_ONLY: Image,
  JSON_ONLY: FileJson2,
  COMBINED: Images,
} satisfies Record<InferenceMode, typeof Image>;

export function InferenceStartPage() {
  const navigate = useNavigate();
  const [launcher, setLauncher] = useState<Launcher>('REPLAY');
  const [mode, setMode] = useState<InferenceMode>('COMBINED');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const replayQuery = useQuery({ queryKey: ['replay-fixtures'], queryFn: listReplayFixturesRequest });
  const liveQuery = useQuery({ queryKey: ['live-inference-availability'], queryFn: getLiveAvailabilityRequest });
  const fixtures = useMemo(
    () => Array.from(replayQuery.data?.items ?? []).filter((item) => item.mode === mode),
    [mode, replayQuery.data],
  );
  const selected = fixtures.find((item) => item.fixtureId === selectedId) ?? fixtures[0];
  const createReplay = useMutation({
    mutationFn: (fixture: ReplayFixtureResponse) => createReplayRunRequest(fixture.fixtureId, crypto.randomUUID()),
    onSuccess: (run) => navigate(`/inference-runs/${run.runId}/review`),
  });

  return (
    <ResourceFrame
      title="数据结构智能识别"
      description="先形成可审计的 Schema Candidate，再由用户逐项确认并创建 Draft；推断流程不会直接发布数据结构资产。"
    >
      <div className="inference-kind-tabs" role="tablist" aria-label="推断方式">
        <button type="button" role="tab" aria-selected={launcher === 'REPLAY'} className={launcher === 'REPLAY' ? 'active' : ''} onClick={() => setLauncher('REPLAY')}>
          <Braces aria-hidden="true" size={16} /><span><strong>确定性样本</strong><small>零网络 · 可复现</small></span>
        </button>
        <button type="button" role="tab" aria-selected={launcher === 'LIVE'} className={launcher === 'LIVE' ? 'active' : ''} onClick={() => setLauncher('LIVE')}>
          <Bot aria-hidden="true" size={16} /><span><strong>AI 识别</strong><small>DashScope · 实验配置</small></span>
        </button>
      </div>

      {launcher === 'REPLAY'
        ? <ReplayLauncher
            mode={mode}
            setMode={setMode}
            setSelectedId={setSelectedId}
            confirmed={confirmed}
            setConfirmed={setConfirmed}
            query={replayQuery}
            fixtures={fixtures}
            selected={selected}
            createRun={createReplay}
          />
        : <LiveLauncher mode={mode} setMode={setMode} query={liveQuery} onCreated={(runId) => navigate(`/inference-runs/${runId}/review`)} />}
    </ResourceFrame>
  );
}

function ReplayLauncher({
  mode,
  setMode,
  setSelectedId,
  confirmed,
  setConfirmed,
  query,
  fixtures,
  selected,
  createRun,
}: {
  mode: InferenceMode;
  setMode: (mode: InferenceMode) => void;
  setSelectedId: (id: string | null) => void;
  confirmed: boolean;
  setConfirmed: (value: boolean) => void;
  query: UseQueryResult<ReplayFixtureListResponse, Error>;
  fixtures: ReplayFixtureResponse[];
  selected: ReplayFixtureResponse | undefined;
  createRun: UseMutationResult<InferenceRunResponse, Error, ReplayFixtureResponse>;
}) {
  return (
    <>
      <section className="replay-contract" aria-label="Replay 执行边界">
        <div><ShieldCheck aria-hidden="true" size={19} /><span>执行配置</span><strong>{query.data?.profileId ?? 'replay-v1'}</strong></div>
        <div><Network aria-hidden="true" size={19} /><span>外部网络</span><strong className="contract-safe">禁止</strong></div>
        <div><Braces aria-hidden="true" size={19} /><span>数据范围</span><strong>仅合成样本</strong></div>
        <p><CheckCircle2 aria-hidden="true" size={15} />本流程不上传真实图片或业务数据，也不会调用 live provider。</p>
      </section>

      <div className="replay-layout">
        <section className="replay-catalog" aria-label="Replay 样本目录">
          <header><div><h2>选择推断场景</h2></div><span>{fixtures.length} / 60</span></header>
          <ModeTabs mode={mode} onChange={(value) => { setMode(value); setSelectedId(null); setConfirmed(false); }} />
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
                <div><dt>输入模式</dt><dd>{modeLabels[selected.mode]}</dd></div>
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
                <Play aria-hidden="true" size={16} />{createRun.isPending ? '正在执行确定性流程…' : '运行并进入审核'}
              </button>
              {createRun.isError && <p className="replay-error" role="alert">{errorMessage(createRun.error)}</p>}
            </>
          )}
          <p className="replay-footnote">运行记录和 Candidate 会持久化；Draft 表在审核完成前保持不变。</p>
        </aside>
      </div>
    </>
  );
}

function LiveLauncher({
  mode,
  setMode,
  query,
  onCreated,
}: {
  mode: InferenceMode;
  setMode: (mode: InferenceMode) => void;
  query: UseQueryResult<LiveAvailabilityResponse, Error>;
  onCreated: (runId: string) => void;
}) {
  const [profileId, setProfileId] = useState<LiveProfileId>('dashscope-qwen37-flash-v1');
  const [images, setImages] = useState<File[]>([]);
  const [jsonSamples, setJsonSamples] = useState<File[]>([]);
  const [transferConfirmed, setTransferConfirmed] = useState(false);
  const [experimentalConfirmed, setExperimentalConfirmed] = useState(false);
  const profile = query.data?.profiles.find((item) => item.profileId === profileId);
  const modeReady = (mode === 'JSON_ONLY' || images.length > 0) && (mode === 'IMAGE_ONLY' || jsonSamples.length > 0);
  const uploadAuthorized = Boolean(query.data?.uploadEnabled);
  const available = Boolean(query.data?.enabled && query.data.configured && uploadAuthorized
    && query.data.remainingAttempts > 0 && query.data.remainingCostMicrosCny > 0);
  const createRun = useMutation({
    mutationFn: () => createLiveRunRequest(profileId, mode, images, jsonSamples, crypto.randomUUID()),
    onSuccess: (run) => onCreated(run.runId),
  });

  return (
    <>
      {query.isPending && <ResourceLoading label="正在读取 AI 推断配置" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && (
        <>
          <section className="replay-contract live-contract" aria-label="Live 执行边界">
            <div><Cloud aria-hidden="true" size={19} /><span>服务提供方</span><strong>DashScope</strong></div>
            <div><Bot aria-hidden="true" size={19} /><span>运行状态</span><strong className={available ? 'contract-safe' : 'contract-blocked'}>{available ? '可用' : '已关闭'}</strong></div>
            <div><CircleDollarSign aria-hidden="true" size={19} /><span>全局预算</span><strong>{query.data.remainingAttempts} 次 / ¥{formatYuan(query.data.remainingCostMicrosCny)}</strong></div>
            <p><ShieldCheck aria-hidden="true" size={15} />仅允许仓库合成数据；图片与结构摘要会发送到所选实验模型。</p>
          </section>

          {!available && (
            <section className="live-policy-notice" role="status">
              <ShieldCheck aria-hidden="true" size={18} />
              <div>
                <strong>{!uploadAuthorized ? '当前授权未开放任意文件外传' : '部署策略尚未开放真实调用'}</strong>
                <span>{!uploadAuthorized
                  ? '真实通路已由仓库合成 canary 验证；任意 multipart 上传需要新的数据范围授权。选择文件不会上传或触发模型。'
                  : '需要同时配置密钥并设置 RENDERWEAVE_LIVE_AI_ENABLED=true；上传与预览本身不会触发模型。'}</span>
              </div>
            </section>
          )}

          <div className="replay-layout live-layout">
            <section className="replay-catalog live-input-panel" aria-label="AI 推断输入">
              <header><div><h2>准备合成输入</h2></div><span>最多 10 图 · 20 JSON</span></header>
              <ModeTabs mode={mode} onChange={(value) => { setMode(value); setTransferConfirmed(false); }} />

              <div className="live-form-section">
                <span className="section-kicker">选择模型配置</span>
                <div className="live-profile-grid">
                  {query.data.profiles.map((item) => (
                    <button key={item.profileId} type="button" className={profileId === item.profileId ? 'active' : ''} onClick={() => { setProfileId(item.profileId); setExperimentalConfirmed(false); }}>
                      <Bot aria-hidden="true" size={17} />
                      <span><strong>{item.model}</strong><small>{liveProfileDescription(item)}</small></span>
                      <em>单次上限 ¥{formatYuan(item.maximumEstimatedCostMicrosCny)}</em>
                    </button>
                  ))}
                </div>
              </div>

              <div className="live-form-section">
                <span className="section-kicker">添加输入文件</span>
                <div className="live-upload-grid">
                  <UploadField
                    title="设计图"
                    description="PNG / JPEG，最多 10 张"
                    accept="image/png,image/jpeg"
                    disabled={!uploadAuthorized || mode === 'JSON_ONLY'}
                    files={images}
                    onFiles={(files) => { setImages(files.slice(0, 10)); setTransferConfirmed(false); }}
                  />
                  <UploadField
                    title="JSON 样本"
                    description="仅用于生成无值结构摘要，最多 20 份"
                    accept="application/json,.json"
                    disabled={!uploadAuthorized || mode === 'IMAGE_ONLY'}
                    files={jsonSamples}
                    onFiles={(files) => { setJsonSamples(files.slice(0, 20)); setTransferConfirmed(false); }}
                  />
                </div>
              </div>
            </section>

            <aside className="replay-launch-panel live-launch-panel" aria-label="AI 调用确认">
              <span className="section-kicker">调用摘要</span>
              <h2>{profile ? `${profile.model} · ${liveProfileShortVersion(profile)}` : '选择模型'}</h2>
              <dl className="fixture-metrics">
                <div><dt>输入模式</dt><dd>{modeLabels[mode]}</dd></div>
                <div><dt>本次文件</dt><dd>{images.length + jsonSamples.length}</dd></div>
                <div><dt>最多调用</dt><dd>{profile?.maximumTotalCalls ?? 0}</dd></div>
                <div><dt>成本预留上限</dt><dd>¥{formatYuan(profile?.maximumEstimatedCostMicrosCny ?? 0)}</dd></div>
              </dl>
              <label className="replay-confirmation">
                <input type="checkbox" checked={transferConfirmed} onChange={(event) => setTransferConfirmed(event.target.checked)} />
                <span><strong>确认数据可外发</strong>这些文件仅含仓库合成数据，可发送至 DashScope。</span>
              </label>
              <label className="replay-confirmation compact-confirmation">
                <input type="checkbox" checked={experimentalConfirmed} onChange={(event) => setExperimentalConfirmed(event.target.checked)} />
                <span><strong>接受实验配置</strong>Candidate 必须经过确定性校验与人工审核。</span>
              </label>
              <button
                type="button"
                className="button primary-button replay-launch"
                disabled={!available || !modeReady || !transferConfirmed || !experimentalConfirmed || createRun.isPending}
                onClick={() => createRun.mutate()}
              >
                <Upload aria-hidden="true" size={16} />{createRun.isPending ? '正在创建任务…' : '排队识别并进入审核'}
              </button>
              {!modeReady && <p className="live-input-hint">请按当前模式添加必需文件。</p>}
              {createRun.isError && <p className="replay-error" role="alert">{errorMessage(createRun.error)}</p>}
              <p className="replay-footnote">每次 provider 尝试先进行持久化预算预留；失败、修复和重试都会计入 6 次全局上限。</p>
            </aside>
          </div>
        </>
      )}
    </>
  );
}

function ModeTabs({ mode, onChange }: { mode: InferenceMode; onChange: (mode: InferenceMode) => void }) {
  return (
    <div className="mode-tabs" role="tablist" aria-label="输入模式">
      {(Object.keys(modeLabels) as InferenceMode[]).map((value) => {
        const Icon = modeIcons[value];
        return (
          <button key={value} type="button" role="tab" aria-selected={mode === value} className={mode === value ? 'active' : ''} onClick={() => onChange(value)}>
            <Icon aria-hidden="true" size={15} />{modeLabels[value]}
          </button>
        );
      })}
    </div>
  );
}

function UploadField({
  title,
  description,
  accept,
  disabled,
  files,
  onFiles,
}: {
  title: string;
  description: string;
  accept: string;
  disabled: boolean;
  files: File[];
  onFiles: (files: File[]) => void;
}) {
  return (
    <label className={`live-upload-field ${disabled ? 'disabled' : ''}`}>
      <input type="file" multiple accept={accept} disabled={disabled} onChange={(event) => onFiles(Array.from(event.target.files ?? []))} />
      <Upload aria-hidden="true" size={20} />
      <span><strong>{title}</strong><small>{disabled ? '当前模式不使用此输入' : description}</small></span>
      <em>{files.length > 0 ? `${files.length} 个文件` : '选择文件'}</em>
      {files.length > 0 && <code title={files.map((file) => file.name).join('\n')}>{files.map((file) => file.name).join('、')}</code>}
    </label>
  );
}

function humanScenario(scenario: string) {
  return scenario.replaceAll('-', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatYuan(micros: number) {
  return (micros / 1_000_000).toFixed(micros >= 100_000 ? 2 : 3);
}

function liveProfileShortVersion(profile: LiveProfileResponse) {
  return profile.profileId.endsWith('-prompt-v2') ? 'Prompt v2' : 'Prompt v1';
}

function liveProfileDescription(profile: LiveProfileResponse) {
  if (profile.profileId.endsWith('-prompt-v2')) return '证据锚定 · 最小结构 Prompt v2';
  return profile.model.includes('flash') ? '低成本快速识别 · Prompt v1' : '复杂结构复核 · Prompt v1';
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '创建推断任务失败。';
}
