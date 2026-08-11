import { useMutation, useQuery, type UseQueryResult } from '@tanstack/react-query';
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  CircleDollarSign,
  Cloud,
  ShieldCheck,
  Trash2,
  Upload,
} from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import type {
  CreateLiveRunRequest,
  InferenceMode,
  LiveAvailabilityResponse,
  LiveProfileResponse,
} from '../../api/generated';
import { ResourceError, ResourceLoading } from '../resources/DraftListPage';
import { ResourceFrame } from '../resources/ResourceFrame';
import {
  createLiveRunRequest,
  getLiveAvailabilityRequest,
} from './candidate-api';
import { InferenceFlowSteps } from './InferenceFlowSteps';
import { InferenceInputModeTabs } from './InferenceInputModeTabs';
import { inferenceModeLabels } from './inference-mode';
import { filesForLiveMode, formatFileSize, mergeLiveFiles, validateLiveFiles, type LiveFileIssue, type LiveFileKind } from './live-input';

type LiveProfileId = CreateLiveRunRequest['profileId'];

export function InferenceStartPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<InferenceMode>('IMAGE_ONLY');
  const liveQuery = useQuery({ queryKey: ['live-inference-availability'], queryFn: getLiveAvailabilityRequest });

  return (
    <ResourceFrame
      title="新增识别输入"
      description="上传设计图片，选择 v42 DashScope 模型与费用边界；提交后进入独立监控版面，不会直接发布或写入正式数据结构。"
      actions={<Link className="button ghost-button" to="/inference">返回历史任务</Link>}
      breadcrumbs={[{ label: '智能识别', to: '/inference' }, { label: '新增识别' }]}
    >
      <InferenceFlowSteps current={1} />
      <LiveLauncher mode={mode} setMode={setMode} query={liveQuery} onCreated={(runId) => navigate(`/inference-runs/${runId}/monitor`)} />
    </ResourceFrame>
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
  const [profileId, setProfileId] = useState<LiveProfileId>('dashscope-qwen37-plus-product-v42-hybrid-generic');
  const [images, setImages] = useState<File[]>([]);
  const [jsonSamples, setJsonSamples] = useState<File[]>([]);
  const [transferConfirmed, setTransferConfirmed] = useState(false);
  const [experimentalConfirmed, setExperimentalConfirmed] = useState(false);
  const [costLimitEnabled, setCostLimitEnabled] = useState(true);
  const [costLimitYuan, setCostLimitYuan] = useState('5.00');
  const selectedProfile = query.data?.profiles.find((item) => item.profileId === profileId);
  const profile = selectedProfile?.available
    ? selectedProfile
    : query.data?.profiles.find((item) => item.available) ?? selectedProfile;
  const activeProfileId = profile?.profileId ?? profileId;
  const imageIssues = validateLiveFiles('IMAGE', images);
  const jsonIssues = validateLiveFiles('JSON', jsonSamples);
  const activeFiles = filesForLiveMode(mode, images, jsonSamples);
  const activeIssues = [
    ...(mode === 'JSON_ONLY' ? [] : imageIssues),
    ...(mode === 'IMAGE_ONLY' ? [] : jsonIssues),
  ];
  const profileAvailable = Boolean(profile?.available);
  const profileSupportsMode = Boolean(profile?.supportedModes.includes(mode));
  const modeReady = (mode === 'JSON_ONLY' || activeFiles.images.length > 0)
    && (mode === 'IMAGE_ONLY' || activeFiles.jsonSamples.length > 0)
    && activeIssues.length === 0
    && profileAvailable
    && profileSupportsMode;
  const uploadAuthorized = Boolean(query.data?.uploadEnabled);
  const localVisionReady = Boolean(query.data?.profiles.some((item) => item.available));
  const available = Boolean(
    query.data?.enabled && query.data.configured && uploadAuthorized && localVisionReady,
  );
  const costLimitRequired = Boolean(query.data?.runCostLimitRequired);
  const costLimitMicrosCny = costLimitEnabled ? parseYuanMicros(costLimitYuan) : null;
  const costLimitValid = (!costLimitRequired && !costLimitEnabled) || (costLimitEnabled && costLimitMicrosCny !== null
    && costLimitMicrosCny <= (query.data?.maximumRunCostLimitMicrosCny ?? 0));
  const createRun = useMutation({
    mutationFn: () => {
      if (!costLimitValid || costLimitMicrosCny === null) {
        throw new Error('任务累计成本上限无效。');
      }
      return createLiveRunRequest(
        activeProfileId,
        mode,
        activeFiles.images,
        activeFiles.jsonSamples,
        crypto.randomUUID(),
        costLimitMicrosCny,
      );
    },
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
                <strong>{!uploadAuthorized
                  ? '当前部署未开放文件传输'
                  : !query.data.enabled || !query.data.configured
                    ? 'DashScope 运行配置尚未就绪'
                    : 'v42 本地 OCR / Layout 能力尚未就绪'}</strong>
                <span>{!uploadAuthorized
                  ? '请使用 live Compose 配置启动服务；选择文件、预览和切换模型都不会触发调用。'
                  : !query.data.enabled || !query.data.configured
                    ? '需要配置凭据并启用运行门；页面本身不会触发模型。'
                    : '启动前探针未匹配 Profile 绑定的精确 capability；任务不会排队，也不会产生 Provider 调用。'}</span>
              </div>
            </section>
          )}

          <div className="replay-layout live-layout">
            <section className="replay-catalog live-input-panel" aria-label="AI 推断输入">
              <header><div><h2>准备识别输入</h2></div><span>最多 10 图 · 20 JSON</span></header>
              <InferenceInputModeTabs mode={mode} onChange={(value) => { setMode(value); setTransferConfirmed(false); }} />

              <div className="live-form-section">
                <span className="section-kicker">选择模型配置</span>
                <div className="live-profile-grid">
                  {query.data.profiles.map((item) => (
                    <button key={item.profileId} type="button" disabled={!item.available} className={activeProfileId === item.profileId ? 'active' : ''} onClick={() => { setProfileId(item.profileId); setExperimentalConfirmed(false); }}>
                      <Bot aria-hidden="true" size={17} />
                      <span><strong>{item.model}</strong><small>{liveProfileDescription(item)}</small></span>
                      <em>{item.available
                        ? `单次上限 ¥${formatYuan(item.maximumEstimatedCostMicrosCny)}`
                        : '本地能力未就绪'}</em>
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
                <div><dt>输入模式</dt><dd>{inferenceModeLabels[mode]}</dd></div>
                <div><dt>本次文件</dt><dd>{activeFiles.images.length + activeFiles.jsonSamples.length}</dd></div>
                <div><dt>最多调用</dt><dd>{profile?.maximumTotalCalls ?? 0}</dd></div>
                <div><dt>单次预留上界</dt><dd>¥{formatYuan(profile?.maximumEstimatedCostMicrosCny ?? 0)}</dd></div>
              </dl>
              <div className="live-cost-policy">
                <label>
                  <input
                    type="checkbox"
                    checked={costLimitEnabled}
                    disabled={costLimitRequired}
                    onChange={(event) => setCostLimitEnabled(event.target.checked)}
                  />
                  <span><strong>设置本次任务成本上限</strong><small>当前部署要求累计覆盖全部串行阶段与受控重试。</small></span>
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
                  : <p>不设置本次上限；仍受单次预留上界与最多 {profile?.maximumTotalCalls ?? 0} 次调用约束。</p>}
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
                <Upload aria-hidden="true" size={16} />{createRun.isPending ? '正在创建任务…' : '排队识别并查看监控'}
              </button>
              {!profileAvailable
                ? <p className="live-input-hint">所选 v42 Profile 的本地 OCR / Layout capability 未就绪，任务不会排队。</p>
                : !profileSupportsMode
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

function formatYuan(micros: number) {
  return (micros / 1_000_000).toFixed(micros >= 100_000 ? 2 : 3);
}

function liveProfileDescription(profile: LiveProfileResponse) {
  if (profile.model === 'qwen3.7-flash') return '低成本 smoke · 复杂站牌不推荐';
  if (profile.model === 'qwen3.7-plus') return '默认平衡方案 · 复杂结构优先于 Flash';
  return '高能力方案 · 高难度嵌套结构';
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
