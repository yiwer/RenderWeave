import {
  AlertTriangle,
  Box,
  Braces,
  CheckCircle2,
  ChevronRight,
  Download,
  FileJson,
  FolderTree,
  Image,
  LoaderCircle,
  PanelLeft,
  PanelRight,
  PencilLine,
  Redo2,
  RefreshCw,
  Save,
  ShieldCheck,
  Undo2,
  Unplug,
  Wrench,
  type LucideIcon,
} from 'lucide-react';
import { useEffect, useId, useMemo, useRef, useState } from 'react';

import {
  createSessionFromBaseline,
  objectOrNull,
  profileIdentity,
  projectStructuredNodes,
  SUPPORTED_NODE_KIND_COUNT,
  templateDisplayName,
  type CanonicalDesignWorkingCopy,
  type CanonicalTemplateBaseline,
  type EditorNodeProjection,
  type EditorReadiness,
  type StructuredEditorSession,
  type TemplateEditorSession,
} from './template-editor-model';
import {
  applyTemplateDisplayName,
  authoritativePreviewGuard,
  isCanonicalDirty,
  redoStructuredCommand,
  undoStructuredCommand,
  updateStructuredReadiness,
} from './template-editor-session';
import {
  defaultTemplateEditorTransport,
  openTemplateEditor,
  TemplateCurrentDriftError,
  TemplateIntegrityError,
  TemplateRequestError,
  type TemplateEditorTransport,
} from './template-open';
import {
  reconcileTemplateUnknownSave,
  type TemplateSaveReconciliationResult,
} from './template-save-reconciliation';
import {
  confirmTemplateInvalidSave,
  confirmTemplateOverwrite,
  defaultTemplateSaveTransport,
  retryTemplateUnknownSave,
  saveTemplateWorkingCopy,
  type TemplateConflictOffer,
  type TemplateInvalidSaveOffer,
  type TemplateSaveResult,
  type TemplateSaveTransport,
  type TemplateUnknownSaveAttempt,
} from './template-save';
import './template-editor.css';

type EditorEntry = 'structure' | 'nodes' | 'assets' | 'definitions' | 'exchange';

const ENTRIES: Array<{ id: EditorEntry; label: string; icon: LucideIcon }> = [
  { id: 'structure', label: '结构', icon: FolderTree },
  { id: 'nodes', label: '节点', icon: Box },
  { id: 'assets', label: '资产', icon: Image },
  { id: 'definitions', label: '定义', icon: Braces },
  { id: 'exchange', label: '交换', icon: FileJson },
];

interface TemplateEditorShellProps {
  session: TemplateEditorSession;
  onRetryReadiness?: () => void;
  saveTransport?: TemplateSaveTransport;
  initialSaveNotice?: string;
  onSessionCommitted?: (session: StructuredEditorSession, saveNotice?: string) => void;
}

export function TemplateEditorShell({
  session,
  onRetryReadiness,
  saveTransport,
  initialSaveNotice,
  onSessionCommitted,
}: TemplateEditorShellProps) {
  if (session.mode === 'raw-repair') {
    return <RawRepairShell session={session} />;
  }
  if (session.mode === 'compatibility') {
    return <CompatibilityShell session={session} onRetryReadiness={onRetryReadiness} />;
  }
  return (
    <StructuredShell
      key={baselineIdentity(session.baseline)}
      session={session}
      onRetryReadiness={onRetryReadiness}
      saveTransport={saveTransport}
      initialSaveNotice={initialSaveNotice}
      onSessionCommitted={onSessionCommitted}
    />
  );
}

interface TemplateEditorSurfaceProps {
  templateId: string;
  transport?: TemplateEditorTransport;
  saveTransport?: TemplateSaveTransport;
}

type SurfaceState =
  | { state: 'loading' }
  | { state: 'open'; session: TemplateEditorSession; saveNotice?: string }
  | { state: 'error'; message: string };

export function TemplateEditorSurface({
  templateId,
  transport = defaultTemplateEditorTransport,
  saveTransport,
}: TemplateEditorSurfaceProps) {
  const [retryKey, setRetryKey] = useState(0);
  const [surface, setSurface] = useState<SurfaceState>({ state: 'loading' });
  const generation = useRef(0);
  const effectiveSaveTransport = saveTransport
    ?? (transport === defaultTemplateEditorTransport ? defaultTemplateSaveTransport : undefined);

  useEffect(() => {
    const activeGeneration = generation.current + 1;
    generation.current = activeGeneration;
    const controller = new AbortController();
    queueMicrotask(() => {
      if (generation.current === activeGeneration) setSurface({ state: 'loading' });
    });
    void openTemplateEditor(
      templateId,
      transport,
      (baseline) => {
        if (generation.current !== activeGeneration) return;
        setSurface({
          state: 'open',
          session: createSessionFromBaseline(baseline, { state: 'checking' }),
        });
      },
      controller.signal,
    ).then((session) => {
      if (generation.current === activeGeneration) {
        setSurface({ state: 'open', session });
      }
    }).catch((error: unknown) => {
      if (controller.signal.aborted || generation.current !== activeGeneration) return;
      setSurface({ state: 'error', message: openingErrorMessage(error) });
    });
    return () => {
      controller.abort();
      if (generation.current === activeGeneration) generation.current += 1;
    };
  }, [retryKey, templateId, transport]);

  if (surface.state === 'loading') return <OpeningSkeleton />;
  if (surface.state === 'error') {
    return (
      <OpeningError
        message={surface.message}
        onRetry={() => setRetryKey((value) => value + 1)}
      />
    );
  }
  return (
    <TemplateEditorShell
      session={surface.session}
      onRetryReadiness={() => setRetryKey((value) => value + 1)}
      saveTransport={effectiveSaveTransport}
      initialSaveNotice={surface.saveNotice}
      onSessionCommitted={(session, saveNotice) => setSurface({
        state: 'open',
        session,
        ...(saveNotice ? { saveNotice } : {}),
      })}
    />
  );
}

type StructuredSaveView =
  | { state: 'idle' }
  | { state: 'pending'; message: string }
  | { state: 'conflict'; offer: TemplateConflictOffer; message: string }
  | { state: 'invalid-save-confirmation'; offer: TemplateInvalidSaveOffer; message: string }
  | { state: 'rejected'; code: string; message: string }
  | { state: 'unknown'; attempt: TemplateUnknownSaveAttempt; message: string }
  | { state: 'reconciling'; attempt: TemplateUnknownSaveAttempt; message: string }
  | { state: 'retryable'; attempt: TemplateUnknownSaveAttempt; message: string }
  | { state: 'confirmed-current'; message: string }
  | { state: 'deleted'; attempt: TemplateUnknownSaveAttempt; message: string }
  | {
    state: 'failed-closed';
    attempt: TemplateUnknownSaveAttempt;
    code: string;
    message: string;
  };

function StructuredShell({
  session: incomingSession,
  onRetryReadiness,
  saveTransport,
  initialSaveNotice,
  onSessionCommitted,
}: {
  session: StructuredEditorSession;
  onRetryReadiness?: () => void;
  saveTransport?: TemplateSaveTransport;
  initialSaveNotice?: string;
  onSessionCommitted?: (session: StructuredEditorSession, saveNotice?: string) => void;
}) {
  const [localSession, setLocalSession] = useState(incomingSession);
  const [saveView, setSaveView] = useState<StructuredSaveView>(
    initialSaveNotice
      ? { state: 'confirmed-current', message: initialSaveNotice }
      : { state: 'idle' },
  );
  const mutationId = useRef(0);
  const mutationAbort = useRef<AbortController | null>(null);
  const session = useMemo(
    () => baselineIdentity(localSession.baseline) === baselineIdentity(incomingSession.baseline)
      ? updateStructuredReadiness(localSession, incomingSession.readiness)
      : localSession,
    [incomingSession.baseline, incomingSession.readiness, localSession],
  );
  const nodes = useMemo(() => projectStructuredNodes(session), [session]);
  const [entry, setEntry] = useState<EditorEntry>('structure');
  const [selectedNodeId, setSelectedNodeId] = useState(nodes[0]?.nodeId ?? '');
  const [navigatorOpen, setNavigatorOpen] = useState(true);
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const effectiveSelectedNodeId = nodes.some((node) => node.nodeId === selectedNodeId)
    ? selectedNodeId
    : nodes[0]?.nodeId ?? '';
  const selected = nodes.find((node) => node.nodeId === effectiveSelectedNodeId) ?? nodes[0];
  const dirty = isCanonicalDirty(session);
  const workingName = templateDisplayName(session.workingCopy);
  const guard = authoritativePreviewGuard(session);
  const localLocked = saveView.state === 'pending'
    || saveView.state === 'unknown'
    || saveView.state === 'reconciling'
    || saveView.state === 'retryable'
    || saveView.state === 'deleted'
    || saveView.state === 'failed-closed';

  useEffect(() => () => {
    mutationId.current += 1;
    mutationAbort.current?.abort();
  }, []);

  const acceptLocalChange = (next: StructuredEditorSession) => {
    if (localLocked) return;
    setLocalSession(next);
    setSaveView({ state: 'idle' });
  };

  const undo = () => acceptLocalChange(undoStructuredCommand(session));
  const redo = () => acceptLocalChange(redoStructuredCommand(session));

  const acceptSaveResult = (result: TemplateSaveResult) => {
    switch (result.state) {
      case 'saved':
        setLocalSession(result.session);
        setSaveView({ state: 'idle' });
        onSessionCommitted?.(result.session);
        return;
      case 'conflict':
        setSaveView({
          state: 'conflict',
          offer: result.offer,
          message: result.message,
        });
        return;
      case 'invalid-save-confirmation':
        setSaveView({
          state: 'invalid-save-confirmation',
          offer: result.offer,
          message: result.message,
        });
        return;
      case 'rejected':
        setSaveView({ state: 'rejected', code: result.code, message: result.message });
        return;
      case 'offer-invalidated':
        setSaveView({
          state: 'rejected',
          code: result.code,
          message: result.message,
        });
        return;
      case 'unknown':
        setSaveView({ state: 'unknown', attempt: result.attempt, message: result.message });
        void runReconciliation(result.attempt);
    }
  };

  const acceptReconciliationResult = (result: TemplateSaveReconciliationResult) => {
    switch (result.state) {
      case 'adopted':
        setLocalSession(result.session);
        setSaveView({ state: 'confirmed-current', message: result.message });
        onSessionCommitted?.(result.session, result.message);
        return;
      case 'retryable':
        setSaveView({ state: 'retryable', attempt: result.attempt, message: result.message });
        return;
      case 'conflict':
        setSaveView({ state: 'conflict', offer: result.offer, message: result.message });
        return;
      case 'deleted':
        setSaveView({ state: 'deleted', attempt: result.attempt, message: result.message });
        return;
      case 'unavailable':
        setSaveView({ state: 'unknown', attempt: result.attempt, message: result.message });
        return;
      case 'failed-closed':
        setSaveView({
          state: 'failed-closed',
          attempt: result.attempt,
          code: result.code,
          message: result.message,
        });
    }
  };

  const runReconciliation = async (attempt: TemplateUnknownSaveAttempt) => {
    const transport = saveTransport;
    if (!transport || mutationAbort.current !== null) return;
    const id = mutationId.current + 1;
    mutationId.current = id;
    const controller = new AbortController();
    mutationAbort.current = controller;
    setSaveView({
      state: 'reconciling',
      attempt,
      message: '正在读取并严格校验 trusted current；不会自动重试写入。',
    });
    let result: TemplateSaveReconciliationResult;
    try {
      result = await reconcileTemplateUnknownSave(attempt, transport, controller.signal);
    } catch (error) {
      if (isAbort(error)) return;
      result = {
        state: 'failed-closed',
        attempt,
        code: 'TEMPLATE_RECONCILIATION_COORDINATOR_FAILURE',
        message: '保存核验协调器出现无法解释的失败；本地草稿继续锁定。',
      };
    }
    if (mutationId.current !== id) return;
    mutationAbort.current = null;
    acceptReconciliationResult(result);
  };

  const runMutation = async (
    message: string,
    operation: (signal: AbortSignal) => Promise<TemplateSaveResult>,
    allowReconciliationRetry = false,
  ) => {
    if (
      !saveTransport
      || (!allowReconciliationRetry && localLocked)
      || mutationAbort.current !== null
    ) return;
    const id = mutationId.current + 1;
    mutationId.current = id;
    const controller = new AbortController();
    mutationAbort.current = controller;
    setSaveView({ state: 'pending', message });
    let result: TemplateSaveResult;
    try {
      result = await operation(controller.signal);
    } catch {
      result = {
        state: 'rejected',
        code: 'TEMPLATE_EDITOR_COORDINATOR_FAILURE',
        message: '保存协调器未能建立可核验结果；本次操作未被自动重试。',
      };
    }
    if (mutationId.current !== id) return;
    mutationAbort.current = null;
    acceptSaveResult(result);
  };

  const save = () => {
    const transport = saveTransport;
    if (!transport) return;
    void runMutation(
      '保存请求进行中',
      (signal) => saveTemplateWorkingCopy(session, transport, signal),
    );
  };
  const confirmOverwrite = (offer: TemplateConflictOffer) => {
    const transport = saveTransport;
    if (!transport) return;
    void runMutation(
      '正在重读 current 并提交覆盖',
      (signal) => confirmTemplateOverwrite(session, offer, transport, signal),
    );
  };
  const confirmInvalidSave = (offer: TemplateInvalidSaveOffer) => {
    const transport = saveTransport;
    if (!transport) return;
    void runMutation(
      '正在确认并保存 INVALID revision',
      (signal) => confirmTemplateInvalidSave(session, offer, transport, signal),
    );
  };
  const retryUnknownSave = (attempt: TemplateUnknownSaveAttempt) => {
    const transport = saveTransport;
    if (!transport) return;
    void runMutation(
      '正在显式重试原保存',
      (signal) => retryTemplateUnknownSave(session, attempt, transport, signal),
      true,
    );
  };

  return (
    <EditorFrame
      baseline={session.baseline}
      documentName={workingName}
      modeLabel="Structured Editor"
      readiness={session.readiness}
      onRetryReadiness={dirty ? undefined : onRetryReadiness}
      headerTools={(
        <StructuredHeaderTools
          dirty={dirty}
          canUndo={session.history.past.length > 0}
          canRedo={session.history.future.length > 0}
          localLocked={localLocked}
          canSave={saveTransport !== undefined && dirty}
          saving={saveView.state === 'pending'}
          onUndo={undo}
          onRedo={redo}
          onSave={save}
        />
      )}
    >
      <main
        className={`te-workbench${navigatorOpen ? '' : ' is-navigator-closed'}${inspectorOpen ? '' : ' is-inspector-closed'}`}
        id="main-content"
        aria-label="Template 编辑工作区"
      >
        {navigatorOpen ? (
          <aside className="te-navigator" aria-label="结构与资源面板">
            <nav className="te-entry-nav" aria-label="编辑器入口">
              {ENTRIES.map(({ id, label, icon: Icon }) => (
                <button
                  key={id}
                  type="button"
                  className={entry === id ? 'is-active' : ''}
                  aria-current={entry === id ? 'page' : undefined}
                  onClick={() => setEntry(id)}
                >
                  <Icon aria-hidden="true" size={16} />
                  <span>{label}</span>
                </button>
              ))}
            </nav>
            <section className="te-entry-panel" aria-live="polite">
              <EntryPanel
                entry={entry}
                session={session}
                nodes={nodes}
                selectedNodeId={effectiveSelectedNodeId}
                onSelectNode={setSelectedNodeId}
              />
            </section>
          </aside>
        ) : null}

        <section className="te-canvas-stage" aria-label="非权威画布投影">
          <div className="te-canvas-heading">
            <div>
              <strong>Canvas Focus</strong>
              <span>本地草稿投影 · 非权威</span>
            </div>
            <span className={`te-mode-chip${dirty ? ' is-dirty' : ''}`}>
              {dirty ? <PencilLine aria-hidden="true" size={14} /> : <ShieldCheck aria-hidden="true" size={14} />}
              {dirty ? '本地草稿已保留' : 'Baseline 完整性已核验'}
            </span>
          </div>
          {saveView.state === 'invalid-save-confirmation' ? (
            <InvalidSaveConfirmationPanel
              view={saveView}
              onConfirm={() => confirmInvalidSave(saveView.offer)}
              onCancel={() => setSaveView({ state: 'idle' })}
            />
          ) : null}
          <CanvasProjection workingCopy={session.workingCopy} nodes={nodes} />
        </section>

        {inspectorOpen ? (
          <aside className="te-inspector" aria-label="属性检视器">
            <TemplateNameEditor
              key={workingName}
              session={session}
              guard={guard}
              disabled={localLocked}
              onSessionChange={acceptLocalChange}
            />
            <StructuredSaveStatus
              view={saveView}
              onConfirmOverwrite={confirmOverwrite}
              onCancelOverwrite={() => setSaveView({ state: 'idle' })}
              onReconcile={runReconciliation}
              onRetry={retryUnknownSave}
              onExport={exportReconciliationDraft}
            />
            <NodeInspector node={selected} />
          </aside>
        ) : null}

        <div className="te-dock" aria-label="工作区面板控制">
          <button
            type="button"
            aria-pressed={navigatorOpen}
            onClick={() => setNavigatorOpen((value) => !value)}
          >
            <PanelLeft aria-hidden="true" size={15} />
            导航面板
          </button>
          <button
            type="button"
            aria-pressed={inspectorOpen}
            onClick={() => setInspectorOpen((value) => !value)}
          >
            <PanelRight aria-hidden="true" size={15} />
            检视器
          </button>
          <span>{nodes.length} 个 authored 节点</span>
        </div>
      </main>
    </EditorFrame>
  );
}

function EditorFrame({
  baseline,
  documentName,
  modeLabel,
  readiness,
  onRetryReadiness,
  headerTools,
  children,
}: {
  baseline: CanonicalTemplateBaseline;
  documentName?: string;
  modeLabel: string;
  readiness: EditorReadiness;
  onRetryReadiness?: () => void;
  headerTools?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="template-editor-root">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <header className="te-chrome">
        <div className="te-product-mark">
          <span className="weave-mark" aria-hidden="true">RW</span>
          <span>RenderWeave</span>
        </div>
        <div className="te-document-identity">
          <h1>{documentName ?? templateDisplayName(baseline)}</h1>
          <span title={baseline.templateId}>{shortIdentity(baseline.templateId)}</span>
        </div>
        <div className="te-baseline-facts" aria-label="Canonical editor baseline">
          <span className="te-schema-fact">{baseline.staticSchema.schemaKey}@{baseline.staticSchema.versionTag}</span>
          <span className="te-revision-fact">revision {baseline.revision}</span>
          <span className="te-mode-fact">{modeLabel}</span>
        </div>
        <div className="te-header-tools">{headerTools}</div>
        <ReadinessStatus readiness={readiness} onRetry={onRetryReadiness} />
      </header>
      {children}
      <div className="te-unsupported-width" role="status">
        <span className="weave-mark" aria-hidden="true">RW</span>
        <strong>Template 编辑器需要更宽的工作区</strong>
        <span>请将窗口扩大到至少 1024px；当前内容没有被修改。</span>
      </div>
    </div>
  );
}

function StructuredHeaderTools({
  dirty,
  canUndo,
  canRedo,
  localLocked,
  canSave,
  saving,
  onUndo,
  onRedo,
  onSave,
}: {
  dirty: boolean;
  canUndo: boolean;
  canRedo: boolean;
  localLocked: boolean;
  canSave: boolean;
  saving: boolean;
  onUndo: () => void;
  onRedo: () => void;
  onSave: () => void;
}) {
  return (
    <>
      <span
        className={`te-canonical-state ${dirty ? 'is-dirty' : 'is-clean'}`}
        role="status"
        aria-live="polite"
      >
        {dirty ? 'Canonical 本地草稿' : 'Canonical current'}
      </span>
      <button type="button" onClick={onUndo} disabled={localLocked || !canUndo} aria-label="撤销本地编辑">
        <Undo2 aria-hidden="true" size={16} />
      </button>
      <button type="button" onClick={onRedo} disabled={localLocked || !canRedo} aria-label="重做本地编辑">
        <Redo2 aria-hidden="true" size={16} />
      </button>
      {canSave || localLocked ? (
        <button
          type="button"
          className="button primary-button te-save-button"
          onClick={onSave}
          disabled={localLocked}
          aria-label="保存 canonical 本地草稿"
        >
          {saving ? (
            <LoaderCircle className="te-loading-icon" aria-hidden="true" size={16} />
          ) : (
            <Save aria-hidden="true" size={16} />
          )}
          <span>{saving ? '保存中' : '保存草稿'}</span>
        </button>
      ) : null}
    </>
  );
}

function TemplateNameEditor({
  session,
  guard,
  disabled,
  onSessionChange,
}: {
  session: StructuredEditorSession;
  guard: ReturnType<typeof authoritativePreviewGuard>;
  disabled: boolean;
  onSessionChange: (session: StructuredEditorSession) => void;
}) {
  const id = useId();
  const titleId = `${id}-title`;
  const fieldId = `${id}-display-name`;
  const helpId = `${id}-help`;
  const problemId = `${id}-problem`;
  const [draft, setDraft] = useState(templateDisplayName(session.workingCopy));
  const [problem, setProblem] = useState<string | null>(null);
  const guardMessage = guard.state === 'eligible'
    ? `当前 current 满足权威预览前置条件 · generation ${guard.generation}`
    : `${guard.message} · generation ${guard.generation}`;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (disabled) return;
    const result = applyTemplateDisplayName(session, draft);
    if (result.state === 'invalid') {
      setProblem(result.message);
      return;
    }
    setProblem(null);
    setDraft(templateDisplayName(result.session.workingCopy));
    if (result.state === 'applied') onSessionChange(result.session);
  };

  return (
    <section className="te-template-edit" aria-labelledby={titleId}>
      <header>
        <span><PencilLine aria-hidden="true" size={14} />Template</span>
        <h2 id={titleId}>本地工作副本</h2>
      </header>
      <form onSubmit={submit} noValidate>
        <label htmlFor={fieldId}>Template 名称</label>
        <div className="te-name-field">
          <input
            id={fieldId}
            value={draft}
            disabled={disabled}
            onChange={(event) => {
              setDraft(event.currentTarget.value);
              setProblem(null);
            }}
            aria-invalid={problem ? 'true' : 'false'}
            aria-describedby={problem ? problemId : helpId}
          />
          <button type="submit" disabled={disabled}>应用本地名称</button>
        </div>
        {problem ? (
          <p id={problemId} className="te-field-problem" role="alert">{problem}</p>
        ) : (
          <p id={helpId} className="te-field-help">应用先更新 canonical working copy；顶栏保存才写入服务器。</p>
        )}
      </form>
      <div className={`te-preview-guard ${guard.state === 'eligible' ? 'is-eligible' : 'is-blocked'}`} role="status" aria-live="polite">
        <strong>权威预览条件</strong>
        <span>{guardMessage}</span>
      </div>
    </section>
  );
}

function StructuredSaveStatus({
  view,
  onConfirmOverwrite,
  onCancelOverwrite,
  onReconcile,
  onRetry,
  onExport,
}: {
  view: StructuredSaveView;
  onConfirmOverwrite: (offer: TemplateConflictOffer) => void;
  onCancelOverwrite: () => void;
  onReconcile: (attempt: TemplateUnknownSaveAttempt) => void;
  onRetry: (attempt: TemplateUnknownSaveAttempt) => void;
  onExport: (attempt: TemplateUnknownSaveAttempt) => void;
}) {
  if (view.state === 'idle') return null;
  if (view.state === 'invalid-save-confirmation') return null;
  if (view.state === 'pending' || view.state === 'reconciling') {
    return (
      <section className="te-save-status is-pending" role="status" aria-live="polite">
        <LoaderCircle className="te-loading-icon" aria-hidden="true" size={18} />
        <div>
          <strong>{view.state === 'pending' ? view.message : '正在核验保存结果'}</strong>
          <span>{view.state === 'pending'
            ? '本地编辑与历史记录已暂时锁定。'
            : view.message}</span>
        </div>
      </section>
    );
  }
  if (view.state === 'conflict') {
    return (
      <section className="te-save-status is-conflict" role="alert">
        <AlertTriangle aria-hidden="true" size={18} />
        <div>
          <strong>保存冲突</strong>
          <span>{view.message}</span>
          <div className="te-save-actions">
            <button type="button" onClick={() => onConfirmOverwrite(view.offer)}>
              重读并覆盖 revision {view.offer.offeredRevision}
            </button>
            <button type="button" className="is-secondary" onClick={onCancelOverwrite}>
              取消覆盖
            </button>
          </div>
        </div>
      </section>
    );
  }
  if (view.state === 'rejected') {
    return (
      <section className="te-save-status is-rejected" role="alert">
        <AlertTriangle aria-hidden="true" size={18} />
        <div>
          <strong>保存未写入</strong>
          <span>{view.message}</span>
          <code>{view.code}</code>
        </div>
      </section>
    );
  }
  if (view.state === 'confirmed-current') {
    return (
      <section className="te-save-status is-confirmed" role="status" aria-live="polite">
        <CheckCircle2 aria-hidden="true" size={18} />
        <div>
          <strong>内容已在服务器确认</strong>
          <span>{view.message}</span>
        </div>
      </section>
    );
  }
  if (view.state === 'retryable') {
    return (
      <section className="te-save-status is-retryable" role="alert">
        <RefreshCw aria-hidden="true" size={18} />
        <div>
          <strong>可以显式重试</strong>
          <span>{view.message}</span>
          <ReconciliationActions
            primaryLabel="显式重试原保存"
            onPrimary={() => onRetry(view.attempt)}
            onExport={() => onExport(view.attempt)}
          />
        </div>
      </section>
    );
  }
  if (view.state === 'deleted') {
    return (
      <section className="te-save-status is-unknown" role="alert">
        <AlertTriangle aria-hidden="true" size={18} />
        <div>
          <strong>Template 已删除</strong>
          <span>{view.message}</span>
          <ReconciliationActions onExport={() => onExport(view.attempt)} />
        </div>
      </section>
    );
  }
  if (view.state === 'failed-closed') {
    return (
      <section className="te-save-status is-unknown" role="alert">
        <AlertTriangle aria-hidden="true" size={18} />
        <div>
          <strong>保存核验失败（fail closed）</strong>
          <span>{view.message}</span>
          <code>{view.code}</code>
          <ReconciliationActions onExport={() => onExport(view.attempt)} />
        </div>
      </section>
    );
  }
  return (
    <section className="te-save-status is-unknown" role="alert">
      <Unplug aria-hidden="true" size={18} />
      <div>
        <strong>保存结果仍未知</strong>
        <span>{view.message}</span>
        <ReconciliationActions
          primaryLabel="重新核验 trusted current"
          onPrimary={() => onReconcile(view.attempt)}
          onExport={() => onExport(view.attempt)}
        />
      </div>
    </section>
  );
}

function ReconciliationActions({
  primaryLabel,
  onPrimary,
  onExport,
}: {
  primaryLabel?: string;
  onPrimary?: () => void;
  onExport: () => void;
}) {
  return (
    <div className="te-save-actions">
      {primaryLabel && onPrimary ? (
        <button type="button" onClick={onPrimary}>{primaryLabel}</button>
      ) : null}
      <button type="button" className="is-secondary" onClick={onExport}>
        <Download aria-hidden="true" size={15} />
        导出 canonical 本地草稿
      </button>
    </div>
  );
}

function InvalidSaveConfirmationPanel({
  view,
  onConfirm,
  onCancel,
}: {
  view: Extract<StructuredSaveView, { state: 'invalid-save-confirmation' }>;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();
  const problemCount = view.offer.problems.length;

  return (
    <section className="te-invalid-save-confirmation" role="alert" aria-labelledby={titleId}>
      <header>
        <AlertTriangle aria-hidden="true" size={20} />
        <div>
          <h2 id={titleId}>确认仍保存为 INVALID</h2>
          <span>{view.message}</span>
        </div>
      </header>
      <p>
        {problemCount} 项依赖问题 · {view.offer.truncated ? '已截断' : '完整未截断'}
      </p>
      <ul>
        {view.offer.problems.map((problem, index) => (
          <li key={`${problem.canonicalPointer}\0${problem.code}\0${index}`}>
            <code>{problem.code}</code>
            <code>{problem.canonicalPointer || '(root)'}</code>
          </li>
        ))}
      </ul>
      <div className="te-invalid-save-actions">
        <button type="button" onClick={onConfirm}>仍保存为 INVALID</button>
        <button type="button" className="is-secondary" onClick={onCancel}>
          取消 INVALID 保存
        </button>
      </div>
    </section>
  );
}

function ReadinessStatus({
  readiness,
  onRetry,
}: {
  readiness: EditorReadiness;
  onRetry?: () => void;
}) {
  if (readiness.state === 'checking') {
    return (
      <div className="te-readiness is-checking" role="status" aria-live="polite">
        <LoaderCircle aria-hidden="true" size={16} />
        <span>权威重检中</span>
      </div>
    );
  }
  if (readiness.state === 'unavailable') {
    return (
      <div className="te-readiness is-unavailable" role="status" aria-live="polite">
        <Unplug aria-hidden="true" size={16} />
        <span>重检暂不可用</span>
        {onRetry ? (
          <button type="button" onClick={onRetry} aria-label="重试权威重检">
            <RefreshCw aria-hidden="true" size={14} />
          </button>
        ) : null}
        <span className="sr-only">{readiness.message}</span>
      </div>
    );
  }
  const ready = readiness.value === 'READY';
  const Icon = ready ? CheckCircle2 : AlertTriangle;
  return (
    <div
      className={`te-readiness ${ready ? 'is-ready' : 'is-invalid'}`}
      role="status"
      aria-live="polite"
    >
      <Icon aria-hidden="true" size={16} />
      <span>{readiness.value}</span>
    </div>
  );
}

function EntryPanel({
  entry,
  session,
  nodes,
  selectedNodeId,
  onSelectNode,
}: {
  entry: EditorEntry;
  session: StructuredEditorSession;
  nodes: EditorNodeProjection[];
  selectedNodeId: string;
  onSelectNode: (nodeId: string) => void;
}) {
  switch (entry) {
    case 'structure':
      return (
        <NodeTree
          nodes={nodes}
          selectedNodeId={selectedNodeId}
          onSelectNode={onSelectNode}
        />
      );
    case 'nodes':
      return <NodeCatalogSummary nodes={nodes} />;
    case 'assets':
      return <AssetSummary designDsl={session.workingCopy.designDsl} />;
    case 'definitions':
      return <DefinitionSummary designDsl={session.workingCopy.designDsl} />;
    case 'exchange':
      return <ExchangeSummary session={session} />;
  }
}

function NodeTree({
  nodes,
  selectedNodeId,
  onSelectNode,
}: {
  nodes: EditorNodeProjection[];
  selectedNodeId: string;
  onSelectNode: (nodeId: string) => void;
}) {
  const [visibleCount, setVisibleCount] = useState(50);
  const visibleNodes = nodes.slice(0, visibleCount);
  return (
    <>
      <PanelHeading title="结构" detail={`${nodes.length} 个节点`} />
      <ul className="te-tree" role="tree" aria-label="DesignDSL 结构">
        {visibleNodes.map((node) => (
          <li key={node.nodeId} role="none">
            <button
              type="button"
              role="treeitem"
              aria-level={node.depth + 1}
              aria-selected={node.nodeId === selectedNodeId}
              style={{ paddingInlineStart: `${12 + node.depth * 16}px` }}
              onClick={() => onSelectNode(node.nodeId)}
            >
              <ChevronRight aria-hidden="true" size={13} />
              <span>{node.displayName}</span>
              <small>{node.kind}</small>
            </button>
          </li>
        ))}
      </ul>
      {visibleCount < nodes.length ? (
        <button
          className="te-more-button"
          type="button"
          onClick={() => setVisibleCount((count) => Math.min(nodes.length, count + 50))}
        >
          再显示 {Math.min(50, nodes.length - visibleCount)} 个节点
        </button>
      ) : null}
    </>
  );
}

function NodeCatalogSummary({ nodes }: { nodes: EditorNodeProjection[] }) {
  const counts = new Map<string, number>();
  for (const node of nodes) counts.set(node.kind, (counts.get(node.kind) ?? 0) + 1);
  return (
    <>
      <PanelHeading title="节点" detail={`${SUPPORTED_NODE_KIND_COUNT} 种 v1 wire`} />
      <p className="te-panel-copy">本地投影识别 exact v1 closed Node kinds；E2 只改写 Template 名称，不创建或改写节点。</p>
      <ul className="te-summary-list">
        {[...counts.entries()].map(([kind, count]) => (
          <li key={kind}><span>{kind}</span><strong>{count}</strong></li>
        ))}
      </ul>
    </>
  );
}

function AssetSummary({ designDsl }: { designDsl: Record<string, unknown> }) {
  const assets = authoredAssetIds(designDsl);
  return (
    <>
      <PanelHeading title="资产" detail={`${assets.length} 个 authored ref`} />
      {assets.length === 0 ? (
        <p className="te-empty-state">当前 DesignDSL 没有 authored imageRef/fontRef。</p>
      ) : (
        <ul className="te-summary-list">
          {assets.map((asset) => <li key={asset}><code>{shortIdentity(asset)}</code></li>)}
        </ul>
      )}
    </>
  );
}

function DefinitionSummary({ designDsl }: { designDsl: Record<string, unknown> }) {
  const definitions = Array.isArray(designDsl.definitions)
    ? designDsl.definitions.map(objectOrNull).filter((value): value is Record<string, unknown> => value !== null)
    : [];
  return (
    <>
      <PanelHeading title="定义" detail={`${definitions.length} 个定义`} />
      {definitions.length === 0 ? (
        <p className="te-empty-state">0 个定义 · 当前 baseline 不含 Custom、Mapping 或 Expression。</p>
      ) : (
        <ul className="te-summary-list">
          {definitions.map((definition, index) => (
            <li key={typeof definition.definitionId === 'string' ? definition.definitionId : index}>
              <span>{typeof definition.displayName === 'string' ? definition.displayName : '未命名定义'}</span>
              <small>{typeof definition.kind === 'string' ? definition.kind : 'unknown'}</small>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function ExchangeSummary({ session }: { session: StructuredEditorSession }) {
  const { baseline, workingCopy } = session;
  return (
    <>
      <PanelHeading title="交换" detail={isCanonicalDirty(session) ? 'canonical local' : 'canonical current'} />
      <p className="te-panel-copy">E2 展示 current 与 working-copy identity；不提前提供导入、导出或 migration 动作。</p>
      <dl className="te-fact-list">
        <div><dt>revision</dt><dd>{baseline.revision}</dd></div>
        <div><dt>contentHash</dt><dd title={baseline.contentHash}>{shortHash(baseline.contentHash)}</dd></div>
        <div><dt>current UTF-8</dt><dd>{new TextEncoder().encode(baseline.canonicalDesignDsl).byteLength} bytes</dd></div>
        <div><dt>local UTF-8</dt><dd>{new TextEncoder().encode(workingCopy.canonicalDesignDsl).byteLength} bytes</dd></div>
      </dl>
    </>
  );
}

function PanelHeading({ title, detail }: { title: string; detail: string }) {
  return (
    <header className="te-panel-heading">
      <h2>{title}</h2>
      <span>{detail}</span>
    </header>
  );
}

function CanvasProjection({
  workingCopy,
  nodes,
}: {
  workingCopy: CanonicalDesignWorkingCopy;
  nodes: EditorNodeProjection[];
}) {
  const canvas = objectOrNull(workingCopy.designDsl.designRoot);
  const width = positiveNumber(canvas?.widthMm) ?? 210;
  const height = positiveNumber(canvas?.heightMm) ?? 297;
  return (
    <div className="te-canvas-viewport" tabIndex={0} aria-label="本地草稿画布视口">
      <div className="te-artboard" style={{ aspectRatio: `${width} / ${height}` }}>
        <div className="te-artboard-meta">
          <span>{formatNumber(width)} × {formatNumber(height)} mm</span>
          <small>结构缩影 · 不计算 Layout</small>
        </div>
        <div className="te-node-silhouettes" aria-hidden="true">
          {nodes.slice(1, 13).map((node) => (
            <span key={node.nodeId} data-kind={node.kind}>{node.displayName}</span>
          ))}
        </div>
        {nodes.length > 13 ? <small className="te-node-overflow">另有 {nodes.length - 13} 个节点</small> : null}
      </div>
    </div>
  );
}

function NodeInspector({ node }: { node?: EditorNodeProjection }) {
  if (!node) {
    return <p className="te-empty-state">当前 DesignDSL 没有可投影节点。</p>;
  }
  const bindings = Array.isArray(node.value.bindings) ? node.value.bindings.length : 0;
  const placement = objectOrNull(node.value.placement);
  return (
    <>
      <header className="te-inspector-heading">
        <span>{node.kind}</span>
        <h2>{node.displayName}</h2>
      </header>
      <dl className="te-fact-list">
        <div><dt>nodeId</dt><dd title={node.nodeId}>{shortIdentity(node.nodeId)}</dd></div>
        <div><dt>children</dt><dd>{node.childCount}</dd></div>
        <div><dt>bindings</dt><dd>{bindings}</dd></div>
        <div><dt>placement</dt><dd>{typeof placement?.type === 'string' ? placement.type : 'root / none'}</dd></div>
      </dl>
      <p className="te-inspector-note">只读检视器只投影 authored facts；浏览器不会把推测的坐标写回 DesignDSL。</p>
    </>
  );
}

function CompatibilityShell({
  session,
  onRetryReadiness,
}: {
  session: Extract<TemplateEditorSession, { mode: 'compatibility' }>;
  onRetryReadiness?: () => void;
}) {
  return (
    <EditorFrame
      baseline={session.baseline}
      modeLabel="Compatibility Read-only"
      readiness={session.readiness}
      onRetryReadiness={onRetryReadiness}
    >
      <main className="te-safe-mode" id="main-content" aria-label="Template 编辑工作区">
        <section className="te-safe-card">
          <AlertTriangle aria-hidden="true" size={24} />
          <div>
            <p className="te-eyebrow">只读兼容边界</p>
            <h2>Compatibility Read-only</h2>
            <p>{session.reason} 当前内容不会被部分重序列化，也不会用浏览器猜测生成画布。</p>
          </div>
          <dl className="te-fact-list">
            <div><dt>Profile</dt><dd>{profileIdentity(session.baseline)}</dd></div>
            <div><dt>Schema</dt><dd>{session.baseline.staticSchema.schemaKey}@{session.baseline.staticSchema.versionTag}</dd></div>
            <div><dt>contentHash</dt><dd>{shortHash(session.baseline.contentHash)}</dd></div>
          </dl>
        </section>
      </main>
    </EditorFrame>
  );
}

function RawRepairShell({
  session,
}: {
  session: Extract<TemplateEditorSession, { mode: 'raw-repair' }>;
}) {
  return (
    <div className="template-editor-root">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <header className="te-chrome te-raw-chrome">
        <div className="te-product-mark">
          <span className="weave-mark" aria-hidden="true">RW</span>
          <span>RenderWeave</span>
        </div>
        <div className="te-document-identity">
          <h1>本地修复缓冲</h1>
          <span>未建立服务器 baseline</span>
        </div>
        <span className="te-mode-chip"><Wrench aria-hidden="true" size={14} />Raw Repair</span>
      </header>
      <main className="te-safe-mode" id="main-content" aria-label="Template 编辑工作区">
        <section className="te-safe-card te-raw-card">
          <Wrench aria-hidden="true" size={24} />
          <div>
            <p className="te-eyebrow">local buffer only</p>
            <h2>Raw Repair</h2>
            <p>{session.problem}</p>
            <small>{session.byteLength} UTF-8 bytes · 未持久化 · 无 canonical baseline</small>
          </div>
          <pre aria-label="原始修复缓冲">{session.rawBuffer}</pre>
        </section>
      </main>
      <div className="te-unsupported-width" role="status">
        <strong>Template 编辑器需要至少 1024px 宽度</strong>
      </div>
    </div>
  );
}

function OpeningSkeleton() {
  return (
    <div className="template-editor-root te-opening" aria-busy="true">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <header className="te-chrome">
        <div className="te-product-mark"><span className="weave-mark" aria-hidden="true">RW</span><span>RenderWeave</span></div>
      </header>
      <main id="main-content" className="te-opening-main">
        <LoaderCircle className="te-loading-icon" aria-hidden="true" size={22} />
        <h1>正在读取可信 current…</h1>
        <p>完整性校验通过后才会建立 Canonical editor baseline。</p>
        <div className="te-skeleton-grid" aria-hidden="true"><span /><span /><span /></div>
      </main>
    </div>
  );
}

function OpeningError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="template-editor-root te-opening">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <header className="te-chrome">
        <div className="te-product-mark"><span className="weave-mark" aria-hidden="true">RW</span><span>RenderWeave</span></div>
      </header>
      <main id="main-content" className="te-opening-main">
        <AlertTriangle aria-hidden="true" size={24} />
        <h1>未打开 Template</h1>
        <p role="alert">{message}</p>
        <button type="button" className="te-primary-button" onClick={onRetry}>
          <RefreshCw aria-hidden="true" size={16} />
          重试打开 Template
        </button>
      </main>
    </div>
  );
}

function openingErrorMessage(error: unknown): string {
  if (error instanceof TemplateIntegrityError) {
    return '服务器 current 未通过完整性校验；可疑内容没有装入编辑器。';
  }
  if (error instanceof TemplateCurrentDriftError) {
    return 'Template current 持续变化，暂时无法建立稳定 baseline。请重试。';
  }
  if (error instanceof TemplateRequestError) {
    if (error.status === 404) return 'Template 不存在，或当前身份无权读取。';
    if (error.status === 410) return 'Template 已删除，不能进入编辑模式。';
    return '服务器暂时无法读取 Template；请检查服务状态后重试。';
  }
  return '无法连接到 Template 服务；请检查网络后重试。';
}

function exportReconciliationDraft(attempt: TemplateUnknownSaveAttempt) {
  const url = URL.createObjectURL(new Blob(
    [attempt.draftCanonical],
    { type: 'application/vnd.renderweave.design+json;charset=utf-8' },
  ));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = 'renderweave-local-draft.design.json';
  anchor.click();
  URL.revokeObjectURL(url);
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function authoredAssetIds(value: unknown): string[] {
  const ids = new Set<string>();
  const visit = (entry: unknown, parentKey?: string) => {
    if (Array.isArray(entry)) {
      entry.forEach((item) => visit(item, parentKey));
      return;
    }
    const object = objectOrNull(entry);
    if (!object) return;
    if (
      (parentKey === 'imageRef' || parentKey === 'fontRef')
      && typeof object.assetId === 'string'
    ) {
      ids.add(object.assetId);
    }
    Object.entries(object).forEach(([key, child]) => visit(child, key));
  };
  visit(value);
  return [...ids].sort();
}

function positiveNumber(value: unknown): number | null {
  if (typeof value !== 'number' && typeof value !== 'bigint' && typeof value !== 'object') return null;
  const parsed = Number(String(value));
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 }).format(value);
}

function shortIdentity(value: string): string {
  return value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value;
}

function shortHash(value: string): string {
  return value.length > 24 ? `${value.slice(0, 15)}…${value.slice(-8)}` : value;
}

function baselineIdentity(baseline: CanonicalTemplateBaseline): string {
  return `${baseline.templateId}:${baseline.revision}:${baseline.contentHash}`;
}
