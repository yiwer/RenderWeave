import { useMutation, useQuery, type UseMutationResult, type UseQueryResult } from '@tanstack/react-query';
import {
  ArrowRight,
  AlertTriangle,
  Bot,
  Braces,
  CheckCircle2,
  CircleDollarSign,
  Cloud,
  FileJson2,
  History,
  Image,
  Images,
  Network,
  Play,
  RefreshCw,
  ShieldCheck,
  Trash2,
  Upload,
} from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import type {
  CreateLiveRunRequest,
  InferenceMode,
  InferenceRunPageResponse,
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
  listInferenceRunsRequest,
  listReplayFixturesRequest,
} from './candidate-api';
import { InferenceFlowSteps } from './InferenceFlowSteps';
import { inferenceStageLabel, inferenceStateLabel } from './inference-format';
import { filesForLiveMode, formatFileSize, mergeLiveFiles, validateLiveFiles, type LiveFileIssue, type LiveFileKind } from './live-input';

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
  const recentRunsQuery = useQuery({
    queryKey: ['inference-runs', 1, 6],
    queryFn: () => listInferenceRunsRequest(1, 6),
  });
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
      <InferenceFlowSteps current={1} />
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
      <RecentInferenceRuns query={recentRunsQuery} />
    </ResourceFrame>
  );
}

function RecentInferenceRuns({ query }: { query: UseQueryResult<InferenceRunPageResponse, Error> }) {
  return (
    <section className="recent-inference-runs" aria-labelledby="recent-inference-title">
      <header>
        <div>
          <span className="recent-inference-icon"><History aria-hidden="true" size={18} /></span>
          <span>
            <h2 id="recent-inference-title">最近识别任务</h2>
            <p>任务记录由服务端持久化，可随时返回继续校对或查看结果。</p>
          </span>
        </div>
        <button type="button" className="button ghost-button" disabled={query.isFetching} onClick={() => void query.refetch()}>
          <RefreshCw aria-hidden="true" size={15} />{query.isFetching ? '正在刷新' : '刷新'}
        </button>
      </header>
      {query.isPending && <ResourceLoading label="正在读取最近识别任务" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && query.data.items.length === 0 && (
        <div className="recent-inference-empty">
          <History aria-hidden="true" size={22} />
          <strong>还没有识别任务</strong>
          <span>从上方选择一个确定性样本，即可体验完整的识别与校对流程。</span>
        </div>
      )}
      {query.data && query.data.items.length > 0 && (
        <div className="recent-inference-list">
          {query.data.items.map((run) => (
            <Link key={run.runId} to={`/inference-runs/${run.runId}/review`} className="recent-inference-row">
              <span className={`recent-inference-state state-${run.state.toLowerCase()}`}>{inferenceStateLabel(run.state)}</span>
              <span className="recent-inference-main">
                <strong>{modeLabels[run.mode]} · {inferenceStageLabel(run.stage)}</strong>
                <small>{run.sourceReference} · {formatRunTime(run.updatedAt)}</small>
              </span>
              <span className="recent-inference-profile">{humanProfile(run.profileId)}</span>
              {run.retryOfRunId && <span className="recent-inference-retry">重试任务</span>}
              <span className="recent-inference-action">{runActionLabel(run.state)}<ArrowRight aria-hidden="true" size={15} /></span>
            </Link>
          ))}
        </div>
      )}
      {query.data && query.data.total > query.data.items.length && (
        <p className="recent-inference-total">共 {query.data.total} 个任务，当前展示最近 {query.data.items.length} 个。</p>
      )}
    </section>
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
  const [profileId, setProfileId] = useState<LiveProfileId>('dashscope-qwen37-flash-product-v2');
  const [images, setImages] = useState<File[]>([]);
  const [jsonSamples, setJsonSamples] = useState<File[]>([]);
  const [transferConfirmed, setTransferConfirmed] = useState(false);
  const [experimentalConfirmed, setExperimentalConfirmed] = useState(false);
  const [costLimitEnabled, setCostLimitEnabled] = useState(false);
  const [costLimitYuan, setCostLimitYuan] = useState('10.00');
  const profile = query.data?.profiles.find((item) => item.profileId === profileId);
  const imageIssues = validateLiveFiles('IMAGE', images);
  const jsonIssues = validateLiveFiles('JSON', jsonSamples);
  const activeFiles = filesForLiveMode(mode, images, jsonSamples);
  const activeIssues = [
    ...(mode === 'JSON_ONLY' ? [] : imageIssues),
    ...(mode === 'IMAGE_ONLY' ? [] : jsonIssues),
  ];
  const profileSupportsMode = Boolean(profile?.supportedModes.includes(mode));
  const modeReady = (mode === 'JSON_ONLY' || activeFiles.images.length > 0)
    && (mode === 'IMAGE_ONLY' || activeFiles.jsonSamples.length > 0)
    && activeIssues.length === 0
    && profileSupportsMode;
  const uploadAuthorized = Boolean(query.data?.uploadEnabled);
  const available = Boolean(query.data?.enabled && query.data.configured && uploadAuthorized);
  const costLimitMicrosCny = costLimitEnabled ? parseYuanMicros(costLimitYuan) : null;
  const costLimitValid = !costLimitEnabled || (costLimitMicrosCny !== null
    && costLimitMicrosCny <= (query.data?.maximumRunCostLimitMicrosCny ?? 0));
  const createRun = useMutation({
    mutationFn: () => createLiveRunRequest(
      profileId,
      mode,
      activeFiles.images,
      activeFiles.jsonSamples,
      crypto.randomUUID(),
      costLimitMicrosCny,
    ),
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
            <div><CircleDollarSign aria-hidden="true" size={19} /><span>本次成本</span><strong>可选硬上限</strong></div>
            <p><ShieldCheck aria-hidden="true" size={15} />只有点击启动后，当前选择的图片与结构摘要才会发送到所选实验模型。</p>
          </section>

          {!available && (
            <section className="live-policy-notice" role="status">
              <ShieldCheck aria-hidden="true" size={18} />
              <div>
                <strong>{!uploadAuthorized ? '当前部署未开放文件传输' : 'DashScope 运行配置尚未就绪'}</strong>
                <span>{!uploadAuthorized
                  ? '请使用 live Compose 配置启动服务；选择文件、预览和切换模型都不会触发调用。'
                  : '需要配置 DASHSCOPE_API_KEY 并启用运行门；页面本身不会触发模型。'}</span>
              </div>
            </section>
          )}

          <div className="replay-layout live-layout">
            <section className="replay-catalog live-input-panel" aria-label="AI 推断输入">
              <header><div><h2>准备识别输入</h2></div><span>最多 10 图 · 20 JSON</span></header>
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
                    kind="IMAGE"
                    title="设计图"
                    description="PNG / JPEG，最多 10 张；超大图自动适配至 4096 像素"
                    accept="image/png,image/jpeg"
                    disabled={mode === 'JSON_ONLY'}
                    files={images}
                    issues={mode === 'JSON_ONLY' ? [] : imageIssues}
                    onFiles={(files) => { setImages(mergeLiveFiles(images, files)); setTransferConfirmed(false); }}
                    onRemove={(index) => { setImages(images.filter((_, current) => current !== index)); setTransferConfirmed(false); }}
                  />
                  <UploadField
                    kind="JSON"
                    title="JSON 样本"
                    description="仅用于生成无值结构摘要，最多 20 份"
                    accept="application/json,.json"
                    disabled={mode === 'IMAGE_ONLY'}
                    files={jsonSamples}
                    issues={mode === 'IMAGE_ONLY' ? [] : jsonIssues}
                    onFiles={(files) => { setJsonSamples(mergeLiveFiles(jsonSamples, files)); setTransferConfirmed(false); }}
                    onRemove={(index) => { setJsonSamples(jsonSamples.filter((_, current) => current !== index)); setTransferConfirmed(false); }}
                  />
                </div>
                {activeIssues.some((issue) => issue.fileIndex === null) && (
                  <div className="live-input-errors" role="alert">
                    <AlertTriangle aria-hidden="true" size={16} />
                    <ul>{activeIssues.filter((issue) => issue.fileIndex === null).map((issue) => <li key={`${issue.code}:${issue.message}`}>{issue.message}</li>)}</ul>
                  </div>
                )}
                {!uploadAuthorized && (images.length > 0 || jsonSamples.length > 0) && (
                  <p className="live-local-only-note"><ShieldCheck aria-hidden="true" size={15} />文件只保留在当前浏览器页面；部署上传门关闭，启动按钮不会开放。</p>
                )}
                {((mode === 'JSON_ONLY' && images.length > 0) || (mode === 'IMAGE_ONLY' && jsonSamples.length > 0)) && (
                  <p className="live-local-only-note"><ShieldCheck aria-hidden="true" size={15} />非当前模式文件仅在本页保留，本次不会发送；切回对应模式后可继续使用。</p>
                )}
              </div>
            </section>

            <aside className="replay-launch-panel live-launch-panel" aria-label="AI 调用确认">
              <span className="section-kicker">调用摘要</span>
              <h2>{profile?.model ?? '选择模型'}</h2>
              <dl className="fixture-metrics">
                <div><dt>输入模式</dt><dd>{modeLabels[mode]}</dd></div>
                <div><dt>本次文件</dt><dd>{activeFiles.images.length + activeFiles.jsonSamples.length}</dd></div>
                <div><dt>最多调用</dt><dd>{profile?.maximumTotalCalls ?? 0}</dd></div>
                <div><dt>单次预留上界</dt><dd>¥{formatYuan(profile?.maximumEstimatedCostMicrosCny ?? 0)}</dd></div>
              </dl>
              <div className="live-cost-policy">
                <label>
                  <input
                    type="checkbox"
                    checked={costLimitEnabled}
                    onChange={(event) => setCostLimitEnabled(event.target.checked)}
                  />
                  <span><strong>设置本次任务成本上限</strong><small>累计覆盖首次识别与最多两次修复。</small></span>
                </label>
                {costLimitEnabled
                  ? <div className={costLimitValid ? 'live-cost-input' : 'live-cost-input invalid'}>
                      <span>¥</span>
                      <input
                        type="number"
                        min="0.01"
                        max={formatYuanInput(query.data.maximumRunCostLimitMicrosCny)}
                        step="0.01"
                        inputMode="decimal"
                        aria-label="本次任务成本上限"
                        value={costLimitYuan}
                        onChange={(event) => setCostLimitYuan(event.target.value)}
                      />
                    </div>
                  : <p>不设置本次上限；仍受单次预留上界与最多 3 次调用约束。</p>}
                {costLimitEnabled && !costLimitValid && (
                  <em role="alert">请输入大于 0 且不超过 ¥{formatYuan(query.data.maximumRunCostLimitMicrosCny)} 的金额。</em>
                )}
              </div>
              <label className="replay-confirmation">
                <input type="checkbox" checked={transferConfirmed} onChange={(event) => setTransferConfirmed(event.target.checked)} />
                <span><strong>确认数据可外发</strong>我有权将本次选择的文件发送至 DashScope 进行识别。</span>
              </label>
              <label className="replay-confirmation compact-confirmation">
                <input type="checkbox" checked={experimentalConfirmed} onChange={(event) => setExperimentalConfirmed(event.target.checked)} />
                <span><strong>接受实验配置</strong>Candidate 必须经过确定性校验与人工审核。</span>
              </label>
              <button
                type="button"
                className="button primary-button replay-launch"
                disabled={!available || !modeReady || !costLimitValid || !transferConfirmed || !experimentalConfirmed || createRun.isPending}
                onClick={() => createRun.mutate()}
              >
                <Upload aria-hidden="true" size={16} />{createRun.isPending ? '正在创建任务…' : '排队识别并进入审核'}
              </button>
              {!profileSupportsMode
                ? <p className="live-input-hint">所选模型配置不支持当前输入模式，请切换模型或模式。</p>
                : !modeReady && <p className="live-input-hint">请按当前模式添加必需文件。</p>}
              {createRun.isError && <p className="replay-error" role="alert">{errorMessage(createRun.error)}</p>}
              <p className="replay-footnote">每次 Provider 尝试都会先持久化费用预留；任务上限不足时会在调用前安全停止。</p>
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
  kind,
  title,
  description,
  accept,
  disabled,
  files,
  issues,
  onFiles,
  onRemove,
}: {
  kind: LiveFileKind;
  title: string;
  description: string;
  accept: string;
  disabled: boolean;
  files: File[];
  issues: LiveFileIssue[];
  onFiles: (files: File[]) => void;
  onRemove: (index: number) => void;
}) {
  return (
    <div className={`live-upload-group ${disabled ? 'disabled' : ''}`}>
      <label className={`live-upload-field ${disabled ? 'disabled' : ''}`}>
        <input
          type="file"
          multiple
          accept={accept}
          disabled={disabled}
          onChange={(event) => {
            onFiles(Array.from(event.target.files ?? []));
            event.currentTarget.value = '';
          }}
        />
        <Upload aria-hidden="true" size={20} />
        <span><strong>{title}</strong><small>{disabled ? '当前模式不使用此输入' : description}</small></span>
        <em>{files.length > 0 ? '继续添加' : '选择文件'}</em>
      </label>
      {files.length > 0 && (
        <ul className="live-file-queue" aria-label={`${title}文件队列`}>
          {files.map((file, index) => {
            const fileIssues = issues.filter((issue) => issue.fileIndex === index);
            return (
              <li key={`${file.name}:${file.size}:${file.lastModified}`} className={fileIssues.length > 0 ? 'invalid' : ''}>
                <span className="live-file-status">{fileIssues.length > 0 ? <AlertTriangle aria-hidden="true" size={15} /> : <CheckCircle2 aria-hidden="true" size={15} />}</span>
                <span><strong>{file.name}</strong><small>{formatFileSize(file.size)} · {kind === 'IMAGE' ? '图片' : 'JSON'}</small>{fileIssues.map((issue) => <em key={issue.code}>{issue.message}</em>)}</span>
                <button type="button" aria-label={`移除文件 ${file.name}`} onClick={() => onRemove(index)}><Trash2 aria-hidden="true" size={15} /></button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function humanScenario(scenario: string) {
  return scenario.replaceAll('-', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatYuan(micros: number) {
  return (micros / 1_000_000).toFixed(micros >= 100_000 ? 2 : 3);
}

function liveProfileDescription(profile: LiveProfileResponse) {
  if (profile.model === 'qwen3.7-flash') return '低成本快速识别';
  if (profile.model === 'qwen3.7-plus') return '质量、速度与成本均衡';
  if (profile.model === 'qwen3.7-max-2026-06-08') return '固定版本 · 复杂视觉结构';
  return '高能力实验模型';
}

function humanProfile(profileId: string) {
  if (profileId === 'replay-v1') return '确定性回放';
  if (profileId.includes('qwen37-plus')) return 'Qwen3.7 Plus';
  if (profileId.includes('qwen37-flash')) return 'Qwen3.7 Flash';
  if (profileId.includes('qwen37-max')) return 'Qwen3.7 Max 2026-06-08';
  if (profileId.includes('qwen38-max')) return 'Qwen3.8 Max';
  return profileId;
}

function runActionLabel(state: InferenceRunResponse['state']) {
  if (state === 'REVIEW_REQUIRED') return '继续校对';
  if (state === 'COMPLETED') return '查看结果';
  if (state === 'FAILED' || state === 'CANCELLED') return '查看并重试';
  return '查看进度';
}

function formatRunTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function parseYuanMicros(value: string): number | null {
  if (!/^\d{1,3}(?:\.\d{1,6})?$/.test(value)) return null;
  const [whole, fraction = ''] = value.split('.');
  const micros = Number(whole) * 1_000_000 + Number(fraction.padEnd(6, '0'));
  return micros > 0 && Number.isSafeInteger(micros) ? micros : null;
}

function formatYuanInput(micros: number) {
  return (micros / 1_000_000).toFixed(2);
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '创建推断任务失败。';
}
