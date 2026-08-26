import {
  AlertTriangle,
  Eye,
  Image as ImageIcon,
  LoaderCircle,
  ShieldCheck,
  Square,
  X,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import type { StructuredEditorSession, TemplateEditorSession } from './template-editor-model';
import { authoritativePreviewGuard, isCanonicalDirty } from './template-editor-session';
import {
  DEFAULT_TEMPLATE_PREVIEW_INPUT,
  defaultTemplatePreviewObjectUrls,
  previewBasisMatchesSession,
  requestAuthoritativeTemplatePreview,
  type TemplatePreviewObjectUrlFactory,
  type TemplatePreviewProblem,
  type TemplatePreviewRequest,
  type TemplatePreviewResult,
  type TemplatePreviewTransport,
} from './template-preview';

type TemplatePreviewView =
  | { state: 'idle' }
  | { state: 'withdrawn'; message: string }
  | { state: 'pending'; sequence: number; savedFirst: boolean }
  | {
    state: 'rendered';
    sequence: number;
    savedFirst: boolean;
    result: Extract<TemplatePreviewResult, { state: 'rendered' }>;
    objectUrl: string;
  }
  | {
    state: 'problem';
    sequence: number;
    savedFirst: boolean;
    problem: TemplatePreviewProblem;
  }
  | { state: 'stopped'; sequence: number; message: string };

interface BoundPreviewSession {
  templateId: string;
  revision: string;
  contentHash: string;
  previewGeneration: number;
}

export interface TemplatePreviewCoordinator {
  readonly enabled: boolean;
  readonly panelOpen: boolean;
  readonly request: TemplatePreviewRequest;
  readonly view: TemplatePreviewView;
  open(): void;
  close(): void;
  updateRequest(next: TemplatePreviewRequest, message: string): void;
  start(
    session: StructuredEditorSession,
    request?: TemplatePreviewRequest,
    options?: { savedFirst?: boolean },
  ): Promise<void>;
  syncSession(session: StructuredEditorSession): void;
  invalidate(message: string): void;
  stop(): void;
  reportProblem(problem: TemplatePreviewProblem, savedFirst?: boolean): void;
}

// The coordinator intentionally shares this module with its only view so their
// single-slot lifecycle cannot drift into two public interfaces.
// eslint-disable-next-line react-refresh/only-export-components
export function useTemplatePreviewCoordinator(
  incomingSession: TemplateEditorSession,
  transport?: TemplatePreviewTransport,
  objectUrls: TemplatePreviewObjectUrlFactory = defaultTemplatePreviewObjectUrls,
): TemplatePreviewCoordinator {
  const [panelOpen, setPanelOpen] = useState(false);
  const [request, setRequest] = useState<TemplatePreviewRequest>({
    inputJson: DEFAULT_TEMPLATE_PREVIEW_INPUT,
    format: 'PNG',
    dpi: 96,
  });
  const [view, setView] = useState<TemplatePreviewView>({ state: 'idle' });
  const sequence = useRef(0);
  const abort = useRef<AbortController | null>(null);
  const objectUrl = useRef<{
    url: string;
    owner: TemplatePreviewObjectUrlFactory;
  } | null>(null);
  const boundSession = useRef<BoundPreviewSession | null>(null);

  const releaseObjectUrl = useCallback(() => {
    if (objectUrl.current === null) return;
    objectUrl.current.owner.revoke(objectUrl.current.url);
    objectUrl.current = null;
  }, []);

  const supersede = useCallback(() => {
    sequence.current += 1;
    abort.current?.abort();
    abort.current = null;
    boundSession.current = null;
    releaseObjectUrl();
  }, [releaseObjectUrl]);

  const invalidate = useCallback((message: string) => {
    supersede();
    setView({ state: 'withdrawn', message });
  }, [supersede]);

  useEffect(() => () => {
    sequence.current += 1;
    abort.current?.abort();
    abort.current = null;
    boundSession.current = null;
    releaseObjectUrl();
  }, [releaseObjectUrl]);

  useEffect(() => {
    if (incomingSession.mode === 'structured') return;
    if (boundSession.current === null && view.state !== 'rendered') return;
    invalidate('Editor 已离开 Structured 模式；旧权威图片已撤下。');
  }, [incomingSession.mode, invalidate, view.state]);

  const start = async (
    session: StructuredEditorSession,
    selectedRequest: TemplatePreviewRequest = request,
    options: { savedFirst?: boolean } = {},
  ) => {
    const effectiveTransport = transport;
    if (!effectiveTransport) return;
    supersede();
    setPanelOpen(true);
    const operationSequence = sequence.current;
    const controller = new AbortController();
    abort.current = controller;
    boundSession.current = boundSessionOf(session);
    const savedFirst = options.savedFirst === true;
    setView({ state: 'pending', sequence: operationSequence, savedFirst });

    let result: TemplatePreviewResult;
    try {
      result = await requestAuthoritativeTemplatePreview(
        session,
        selectedRequest,
        effectiveTransport,
        controller.signal,
      );
    } catch {
      if (sequence.current !== operationSequence || controller.signal.aborted) return;
      result = {
        state: 'problem',
        problem: {
          source: 'client',
          code: 'EDITOR_PREVIEW_COORDINATOR_FAILURE',
          message: '权威预览协调器出现无法解释的失败；图片已撤下。',
        },
      };
    }
    if (sequence.current !== operationSequence || controller.signal.aborted) return;
    abort.current = null;
    boundSession.current = null;

    if (result.state === 'problem') {
      setView({
        state: 'problem',
        sequence: operationSequence,
        savedFirst,
        problem: result.problem,
      });
      return;
    }
    if (!previewBasisMatchesSession(result.basis, session)) {
      setView({
        state: 'withdrawn',
        message: '权威预览 basis 已失效；迟到结果已丢弃。',
      });
      return;
    }
    try {
      const nextObjectUrl = objectUrls.create(result.bytes, result.mediaType);
      if (sequence.current !== operationSequence) {
        objectUrls.revoke(nextObjectUrl);
        return;
      }
      objectUrl.current = { url: nextObjectUrl, owner: objectUrls };
      setView({
        state: 'rendered',
        sequence: operationSequence,
        savedFirst,
        result,
        objectUrl: nextObjectUrl,
      });
    } catch {
      setView({
        state: 'problem',
        sequence: operationSequence,
        savedFirst,
        problem: {
          source: 'client',
          code: 'EDITOR_PREVIEW_OBJECT_URL_FAILURE',
          message: '浏览器无法建立已核验图片的临时显示地址；图片未展示。',
        },
      });
    }
  };

  const stop = () => {
    supersede();
    const nextSequence = sequence.current;
    setView({
      state: 'stopped',
      sequence: nextSequence,
      message: '已停止本地等待并撤下结果；服务端 operation 可能继续，本操作不等于 Engine cancel。',
    });
  };

  const reportProblem = (problem: TemplatePreviewProblem, savedFirst = false) => {
    supersede();
    setPanelOpen(true);
    setView({
      state: 'problem',
      sequence: sequence.current,
      savedFirst,
      problem,
    });
  };

  return {
    enabled: transport !== undefined,
    panelOpen,
    request,
    view,
    open: () => setPanelOpen(true),
    close: () => setPanelOpen(false),
    updateRequest: (next, message) => {
      invalidate(message);
      setRequest(next);
    },
    start,
    syncSession: (session) => {
      const binding = boundSession.current;
      if ((binding !== null && !boundSessionMatches(binding, session))
        || (view.state === 'rendered'
          && !previewBasisMatchesSession(view.result.basis, session))) {
        invalidate('服务器 current、readiness 或 Editor generation 已变化；旧权威图片已撤下。');
      }
    },
    invalidate,
    stop,
    reportProblem,
  };
}

export function TemplatePreviewPanel({
  coordinator,
  session,
  documentName,
  localLocked,
  canSave,
  onGenerate,
}: {
  coordinator: TemplatePreviewCoordinator;
  session: StructuredEditorSession;
  documentName: string;
  localLocked: boolean;
  canSave: boolean;
  onGenerate: () => void;
}) {
  const titleId = 'template-authoritative-preview-title';
  const inputId = 'template-authoritative-preview-input';
  const view = coordinator.view;
  const request = coordinator.request;
  const dirty = isCanonicalDirty(session);
  const guard = authoritativePreviewGuard(session);
  const pending = view.state === 'pending';
  const controlsLocked = localLocked || pending;
  const canGenerate = !controlsLocked
    && (dirty ? canSave : guard.state === 'eligible');
  const problemRef = useRef<HTMLDivElement>(null);
  const inputByteLength = useMemo(
    () => new TextEncoder().encode(request.inputJson).byteLength,
    [request.inputJson],
  );
  const problemSequence = view.state === 'problem' ? view.sequence : null;

  useEffect(() => {
    if (problemSequence === null) return;
    queueMicrotask(() => problemRef.current?.focus({ preventScroll: true }));
  }, [problemSequence]);

  if (!coordinator.panelOpen) return null;

  const update = (next: TemplatePreviewRequest, message: string) => {
    coordinator.updateRequest(next, message);
  };
  return (
    <section
      className="te-authoritative-preview"
      id="template-authoritative-preview-panel"
      aria-labelledby={titleId}
    >
      <header className="te-preview-heading">
        <div>
          <span><ShieldCheck aria-hidden="true" size={14} />Authoritative Preview</span>
          <h2 id={titleId}>权威预览</h2>
          <p>只渲染已保存 current；与正式输出使用同一 Evaluator、RenderEngine 和 exact Profile。</p>
        </div>
        <button type="button" onClick={coordinator.close} aria-label="关闭权威预览">
          <X aria-hidden="true" size={18} />
        </button>
      </header>

      <div className="te-preview-layout">
        <div className="te-preview-controls">
          <label htmlFor={inputId}>RenderInput JSON</label>
          <textarea
            id={inputId}
            value={request.inputJson}
            disabled={controlsLocked}
            spellCheck={false}
            onChange={(event) => update(
              { ...request, inputJson: event.currentTarget.value },
              '输入样例已变化；旧权威图片已撤下。',
            )}
          />
          <div className="te-preview-input-facts">
            <span>{inputByteLength.toLocaleString('zh-CN')} / 8,388,608 bytes</span>
            <span>仅本次 EditorSession · 不保存</span>
          </div>

          <div className="te-preview-output-fields">
            <label>
              <span>输出格式</span>
              <select
                aria-label="输出格式"
                value={request.format}
                disabled={controlsLocked}
                onChange={(event) => {
                  const format = event.currentTarget.value === 'JPEG' ? 'JPEG' : 'PNG';
                  update({
                    inputJson: request.inputJson,
                    format,
                    dpi: request.dpi,
                    ...(format === 'JPEG' ? { quality: 90 } : {}),
                  }, '输出格式已变化；旧权威图片已撤下。');
                }}
              >
                <option value="PNG">PNG</option>
                <option value="JPEG">JPEG</option>
              </select>
            </label>
            <label>
              <span>DPI</span>
              <input
                type="number"
                aria-label="DPI"
                min="1"
                max="600"
                step="1"
                value={request.dpi}
                disabled={controlsLocked}
                onChange={(event) => update(
                  { ...request, dpi: Number(event.currentTarget.value) },
                  '输出参数已变化；旧权威图片已撤下。',
                )}
              />
            </label>
            {request.format === 'JPEG' ? (
              <label>
                <span>JPEG quality</span>
                <input
                  type="number"
                  aria-label="JPEG quality"
                  min="1"
                  max="100"
                  step="1"
                  value={request.quality ?? 90}
                  disabled={controlsLocked}
                  onChange={(event) => update(
                    { ...request, quality: Number(event.currentTarget.value) },
                    'JPEG quality 已变化；旧权威图片已撤下。',
                  )}
                />
              </label>
            ) : null}
          </div>

          <div className={`te-preview-basis ${guard.state === 'eligible' ? 'is-ready' : 'is-blocked'}`}>
            <strong>请求 basis</strong>
            <span>revision {session.baseline.revision} · {shortHash(session.baseline.contentHash)}</span>
            <span>{guard.state === 'eligible' ? 'READY current · 无本地分歧' : guard.message}</span>
          </div>

          <div className="te-preview-actions">
            {pending ? (
              <button type="button" className="is-secondary" onClick={coordinator.stop}>
                <Square aria-hidden="true" size={15} />停止等待
              </button>
            ) : (
              <button type="button" disabled={!canGenerate} onClick={onGenerate}>
                <Eye aria-hidden="true" size={16} />
                {dirty ? '保存并生成权威预览' : '生成权威预览'}
              </button>
            )}
          </div>
          {!canGenerate && !pending ? (
            <p className="te-preview-help">
              {dirty && !canSave
                ? '当前没有可用的 Template save transport，不能保存并预览。'
                : guard.state === 'blocked' ? guard.message : '当前 mutation 完成后可预览。'}
            </p>
          ) : null}
        </div>

        <div className="te-preview-slot">
          {view.state === 'idle' ? (
            <PreviewEmpty message="尚未发起权威预览。完整图片通过校验后才会出现在这里。" />
          ) : null}
          {view.state === 'withdrawn' ? <PreviewEmpty message={view.message} /> : null}
          {view.state === 'stopped' ? <PreviewEmpty message={view.message} /> : null}
          {view.state === 'pending' ? (
            <div className="te-preview-pending" role="status">
              <LoaderCircle className="te-loading-icon" aria-hidden="true" size={24} />
              <strong>{view.savedFirst ? 'Template 已保存，正在独立生成预览' : '正在生成权威预览'}</strong>
              <span>旧结果已撤下；等待完整 image、length、digest 与 Profile metadata。</span>
            </div>
          ) : null}
          {view.state === 'problem' ? (
            <div className="te-preview-problem" role="alert" tabIndex={-1} ref={problemRef}>
              <AlertTriangle aria-hidden="true" size={22} />
              <div>
                <span>权威预览未生成</span>
                <h3>{view.problem.code}</h3>
                <p>{view.problem.message}</p>
                {view.savedFirst ? (
                  <p>Template revision 已保存；预览失败不影响提交，保存不会回滚。</p>
                ) : null}
                <dl>
                  {view.problem.stage ? <div><dt>Stage</dt><dd>{view.problem.stage}</dd></div> : null}
                  {view.problem.safeLocation ? <div><dt>安全位置</dt><dd>{view.problem.safeLocation}</dd></div> : null}
                  {view.problem.limitId ? <div><dt>Limit</dt><dd>{view.problem.limitId}</dd></div> : null}
                  {view.problem.renderOperationId ? (
                    <div><dt>Operation</dt><dd>{view.problem.renderOperationId}</dd></div>
                  ) : null}
                </dl>
              </div>
            </div>
          ) : null}
          {view.state === 'rendered' ? (
            <div className="te-preview-result">
              <div className="te-preview-image-wrap">
                <img
                  src={view.objectUrl}
                  alt={`${documentName}的权威预览`}
                  width={view.result.metadata.widthPx}
                  height={view.result.metadata.heightPx}
                />
              </div>
              <div className="te-preview-result-summary">
                <span><ShieldCheck aria-hidden="true" size={14} />完整结果已核验</span>
                <strong>{view.result.metadata.widthPx} × {view.result.metadata.heightPx} px</strong>
                <dl>
                  <div><dt>Current</dt><dd>revision {view.result.basis.revision}</dd></div>
                  <div><dt>Format</dt><dd>{view.result.metadata.format} · {view.result.metadata.dpi} DPI</dd></div>
                  {view.result.metadata.quality === undefined ? null : (
                    <div><dt>Quality</dt><dd>{view.result.metadata.quality}</dd></div>
                  )}
                  <div><dt>Renderer</dt><dd>{view.result.metadata.rendererProfile}</dd></div>
                  <div><dt>Layout</dt><dd>{view.result.metadata.layoutProfile}</dd></div>
                  <div><dt>Output</dt><dd>{view.result.metadata.outputProfile}</dd></div>
                  <div><dt>Operation</dt><dd>{view.result.metadata.renderOperationId}</dd></div>
                </dl>
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}

function PreviewEmpty({ message }: { message: string }) {
  return (
    <div className="te-preview-empty" role="status">
      <ImageIcon aria-hidden="true" size={24} />
      <strong>单一权威结果槽</strong>
      <span>{message}</span>
    </div>
  );
}

function boundSessionOf(session: StructuredEditorSession): BoundPreviewSession {
  return {
    templateId: session.baseline.templateId,
    revision: session.baseline.revision,
    contentHash: session.baseline.contentHash,
    previewGeneration: session.previewGeneration,
  };
}

function boundSessionMatches(
  binding: BoundPreviewSession,
  session: StructuredEditorSession,
): boolean {
  return binding.templateId === session.baseline.templateId
    && binding.revision === session.baseline.revision
    && binding.contentHash === session.baseline.contentHash
    && binding.previewGeneration === session.previewGeneration;
}

function shortHash(value: string): string {
  return value.length > 22 ? `${value.slice(0, 13)}…${value.slice(-7)}` : value;
}
