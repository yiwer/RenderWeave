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
import { useEffect, useEffectEvent, useId, useMemo, useRef, useState } from 'react';

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
  adoptStructuredTemplateImport,
  applyTemplateDisplayName,
  authoritativePreviewGuard,
  isCanonicalDirty,
  redoStructuredCommand,
  undoStructuredCommand,
  updateStructuredReadiness,
} from './template-editor-session';
import {
  BARE_DESIGN_DSL_MEDIA_TYPE,
  inspectTemplateImport,
  TEMPLATE_REVISION_EXPORT_MEDIA_TYPE,
  type CompatibilityTemplateImport,
  type RawRepairTemplateImport,
  type StructuredTemplateImport,
} from './template-import';
import {
  LOCAL_RECOVERY_DEBOUNCE_MS,
  browserTemplateRecoveryStorage,
  buildTemplateRecoveryRecord,
  clearTemplateRecovery,
  loadTemplateRecovery,
  persistTemplateRecovery,
  recoveryOverwriteOffer,
  restoreStructuredSessionFromRecovery,
  type TemplateRecoveryBase,
  type TemplateRecoveryEditState,
  type TemplateRecoveryEntry,
  type TemplateRecoveryRecord,
  type TemplateRecoveryStorage,
} from './template-recovery';
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
import {
  locateTemplateProblem,
  type TemplateProblemLocation,
} from './template-problem-locator';
import {
  TemplatePreviewPanel,
  useTemplatePreviewCoordinator,
  type TemplatePreviewCoordinator,
} from './TemplatePreviewPanel';
import {
  defaultTemplatePreviewObjectUrls,
  defaultTemplatePreviewTransport,
  type TemplatePreviewObjectUrlFactory,
  type TemplatePreviewRequest,
  type TemplatePreviewTransport,
} from './template-preview';
import './template-editor.css';

type EditorEntry = TemplateRecoveryEntry;
type LocatedTemplateProblem = Extract<TemplateProblemLocation, { state: 'located' }>;
type InvalidSaveProblem = TemplateInvalidSaveOffer['problems'][number];

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
  previewTransport?: TemplatePreviewTransport;
  previewObjectUrls?: TemplatePreviewObjectUrlFactory;
  initialSaveNotice?: string;
  onSessionCommitted?: (session: StructuredEditorSession, saveNotice?: string) => void;
  recoveryStorage?: TemplateRecoveryStorage;
  recoveryNow?: () => number;
  download?: TemplateEditorDownload;
}

export interface TemplateEditorDownloadArtifact {
  filename: string;
  mediaType: string;
  bytes: Uint8Array;
}

export type TemplateEditorDownload = (artifact: TemplateEditorDownloadArtifact) => void;

export function TemplateEditorShell({
  session,
  onRetryReadiness,
  saveTransport,
  previewTransport,
  previewObjectUrls = defaultTemplatePreviewObjectUrls,
  initialSaveNotice,
  onSessionCommitted,
  recoveryStorage,
  recoveryNow = Date.now,
  download = defaultTemplateEditorDownload,
}: TemplateEditorShellProps) {
  const preview = useTemplatePreviewCoordinator(session, previewTransport, previewObjectUrls);
  if (session.mode === 'raw-repair') {
    return <RawRepairShell session={session} download={download} />;
  }
  if (session.mode === 'compatibility') {
    return <CompatibilityShell
      session={session}
      onRetryReadiness={onRetryReadiness}
      download={download}
    />;
  }
  return (
    <StructuredShell
      key={baselineIdentity(session.baseline)}
      session={session}
      onRetryReadiness={onRetryReadiness}
      saveTransport={saveTransport}
      preview={preview}
      initialSaveNotice={initialSaveNotice}
      onSessionCommitted={onSessionCommitted}
      recoveryStorage={recoveryStorage}
      recoveryNow={recoveryNow}
      download={download}
    />
  );
}

interface TemplateEditorSurfaceProps {
  templateId: string;
  transport?: TemplateEditorTransport;
  saveTransport?: TemplateSaveTransport;
  previewTransport?: TemplatePreviewTransport;
  previewObjectUrls?: TemplatePreviewObjectUrlFactory;
  recoveryStorage?: TemplateRecoveryStorage;
  recoveryNow?: () => number;
  download?: TemplateEditorDownload;
}

type SurfaceState =
  | { state: 'loading' }
  | { state: 'open'; session: TemplateEditorSession; saveNotice?: string }
  | { state: 'error'; message: string };

export function TemplateEditorSurface({
  templateId,
  transport = defaultTemplateEditorTransport,
  saveTransport,
  previewTransport,
  previewObjectUrls,
  recoveryStorage,
  recoveryNow = Date.now,
  download,
}: TemplateEditorSurfaceProps) {
  const [retryKey, setRetryKey] = useState(0);
  const [surface, setSurface] = useState<SurfaceState>({ state: 'loading' });
  const generation = useRef(0);
  const effectiveSaveTransport = saveTransport
    ?? (transport === defaultTemplateEditorTransport ? defaultTemplateSaveTransport : undefined);
  const effectivePreviewTransport = previewTransport
    ?? (transport === defaultTemplateEditorTransport ? defaultTemplatePreviewTransport : undefined);
  const effectiveRecoveryStorage = recoveryStorage ?? browserTemplateRecoveryStorage();

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
      previewTransport={effectivePreviewTransport}
      previewObjectUrls={previewObjectUrls}
      initialSaveNotice={surface.saveNotice}
      onSessionCommitted={(session, saveNotice) => setSurface({
        state: 'open',
        session,
        ...(saveNotice ? { saveNotice } : {}),
      })}
      recoveryStorage={effectiveRecoveryStorage}
      recoveryNow={recoveryNow}
      download={download}
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

type StructuredRecoveryView =
  | { state: 'disabled' }
  | { state: 'loading' }
  | { state: 'none' }
  | {
    state: 'offer';
    baseState: 'matching' | 'drifted';
    record: TemplateRecoveryRecord;
  }
  | {
    state: 'invalid';
    reason: string;
    exportCanonical?: string;
  }
  | { state: 'unavailable'; operation: 'read' | 'write' | 'clear' }
  | {
    state: 'restored';
    baseState: 'matching' | 'drifted';
    record: TemplateRecoveryRecord;
    resumingUnknown: boolean;
  };

type RecoveryPersistenceView =
  | { state: 'idle' }
  | { state: 'stored' }
  | { state: 'unavailable'; operation: 'write' | 'clear' };

interface StructuredImportCandidate {
  inspection: StructuredTemplateImport;
  filename: string;
  targetTemplateId: string;
  baselineKey: string;
  previewGeneration: number;
  replacement: 'ready' | 'guard';
}

type StructuredImportView =
  | { state: 'idle' }
  | { state: 'inspecting'; filename: string }
  | { state: 'candidate'; candidate: StructuredImportCandidate }
  | { state: 'raw-repair'; inspection: RawRepairTemplateImport; filename: string }
  | { state: 'compatibility'; inspection: CompatibilityTemplateImport; filename: string }
  | { state: 'stale'; message: string }
  | { state: 'notice'; message: string }
  | { state: 'error'; message: string };

interface PendingPreviewAfterSave {
  draftCanonical: string;
  previewGeneration: number;
  request: TemplatePreviewRequest;
}

function StructuredShell({
  session: incomingSession,
  onRetryReadiness,
  saveTransport,
  preview,
  initialSaveNotice,
  onSessionCommitted,
  recoveryStorage,
  recoveryNow,
  download,
}: {
  session: StructuredEditorSession;
  onRetryReadiness?: () => void;
  saveTransport?: TemplateSaveTransport;
  preview: TemplatePreviewCoordinator;
  initialSaveNotice?: string;
  onSessionCommitted?: (session: StructuredEditorSession, saveNotice?: string) => void;
  recoveryStorage?: TemplateRecoveryStorage;
  recoveryNow: () => number;
  download: TemplateEditorDownload;
}) {
  const [localSession, setLocalSession] = useState(incomingSession);
  const [saveView, setSaveView] = useState<StructuredSaveView>(
    initialSaveNotice
      ? { state: 'confirmed-current', message: initialSaveNotice }
      : { state: 'idle' },
  );
  const [recoveryView, setRecoveryView] = useState<StructuredRecoveryView>(
    recoveryStorage ? { state: 'loading' } : { state: 'disabled' },
  );
  const [recoveryPersistence, setRecoveryPersistence] = useState<RecoveryPersistenceView>({
    state: 'idle',
  });
  const [recoveryBase, setRecoveryBase] = useState<TemplateRecoveryBase>();
  const [importView, setImportView] = useState<StructuredImportView>({ state: 'idle' });
  const mutationId = useRef(0);
  const mutationAbort = useRef<AbortController | null>(null);
  const recoveryEpoch = useRef(0);
  const recoveryWriteEpoch = useRef(0);
  const cachedRecovery = useRef<TemplateRecoveryRecord | null>(null);
  const importEpoch = useRef(0);
  const pendingImportAfterSave = useRef<StructuredImportCandidate | null>(null);
  const pendingPreviewAfterSave = useRef<PendingPreviewAfterSave | null>(null);
  const incomingSessionRef = useRef(incomingSession);
  const editorRootRef = useRef<HTMLDivElement>(null);
  const incomingBaselineKey = baselineIdentity(incomingSession.baseline);
  const session = useMemo(
    () => baselineIdentity(localSession.baseline) === baselineIdentity(incomingSession.baseline)
      ? updateStructuredReadiness(localSession, incomingSession.readiness)
      : localSession,
    [incomingSession.baseline, incomingSession.readiness, localSession],
  );
  useEffect(() => {
    preview.syncSession(session);
  }, [preview, session]);
  const nodes = useMemo(() => projectStructuredNodes(session), [session]);
  const [entry, setEntry] = useState<EditorEntry>('structure');
  const [selectedNodeId, setSelectedNodeId] = useState(nodes[0]?.nodeId ?? '');
  const [navigatorOpen, setNavigatorOpen] = useState(true);
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const [announcement, setAnnouncement] = useState('');
  const effectiveSelectedNodeId = nodes.some((node) => node.nodeId === selectedNodeId)
    ? selectedNodeId
    : nodes[0]?.nodeId ?? '';
  const selected = nodes.find((node) => node.nodeId === effectiveSelectedNodeId) ?? nodes[0];
  const dirty = isCanonicalDirty(session);
  const workingName = templateDisplayName(session.workingCopy);
  const guard = authoritativePreviewGuard(session);
  const recoveryChoiceLocked = recoveryView.state === 'loading'
    || recoveryView.state === 'offer'
    || recoveryView.state === 'invalid';
  const localLocked = recoveryChoiceLocked
    || saveView.state === 'pending'
    || saveView.state === 'unknown'
    || saveView.state === 'reconciling'
    || saveView.state === 'retryable'
    || saveView.state === 'deleted'
    || saveView.state === 'failed-closed';
  const importLocked = localLocked
    || saveView.state === 'conflict'
    || saveView.state === 'invalid-save-confirmation';
  const recoveryEditState: TemplateRecoveryEditState = useMemo(() => ({
    entry,
    selectedNodeId: effectiveSelectedNodeId,
    navigatorOpen,
    inspectorOpen,
  }), [entry, effectiveSelectedNodeId, inspectorOpen, navigatorOpen]);

  useEffect(() => () => {
    mutationId.current += 1;
    mutationAbort.current?.abort();
    recoveryEpoch.current += 1;
    recoveryWriteEpoch.current += 1;
    importEpoch.current += 1;
  }, []);

  useEffect(() => {
    incomingSessionRef.current = incomingSession;
  }, [incomingSession]);

  const applyRecoveredEditState = (editState: TemplateRecoveryEditState) => {
    setEntry(editState.entry);
    setSelectedNodeId(editState.selectedNodeId);
    setNavigatorOpen(editState.navigatorOpen);
    setInspectorOpen(editState.inspectorOpen);
  };

  const acceptLocalChange = (next: StructuredEditorSession) => {
    if (localLocked) return;
    pendingPreviewAfterSave.current = null;
    preview.invalidate('DesignDSL 本地工作副本已变化；旧权威图片已撤下。');
    if (importView.state === 'candidate') {
      setImportView({ state: 'stale', message: '导入候选已因本地 generation 变化而失效。' });
    }
    setLocalSession(next);
    setSaveView({ state: 'idle' });
  };

  const undo = () => acceptLocalChange(undoStructuredCommand(session));
  const redo = () => acceptLocalChange(redoStructuredCommand(session));

  const persistRecoveryNow = async (
    targetSession: StructuredEditorSession,
    unknownAttempt?: TemplateUnknownSaveAttempt,
  ) => {
    if (!recoveryStorage || !isCanonicalDirty(targetSession)) return;
    const epoch = recoveryWriteEpoch.current + 1;
    recoveryWriteEpoch.current = epoch;
    try {
      const record = await buildTemplateRecoveryRecord(
        targetSession,
        recoveryEditState,
        new Date(recoveryNow()).toISOString(),
        unknownAttempt,
        recoveryBase,
      );
      if (recoveryWriteEpoch.current !== epoch) return;
      cachedRecovery.current = record;
      const result = persistTemplateRecovery(recoveryStorage, record);
      setRecoveryPersistence(result.state === 'stored'
        ? { state: 'stored' }
        : { state: 'unavailable', operation: 'write' });
    } catch {
      if (recoveryWriteEpoch.current === epoch) {
        setRecoveryPersistence({ state: 'unavailable', operation: 'write' });
      }
    }
  };

  const clearRecoveryNow = (): boolean => {
    if (!recoveryStorage) return true;
    recoveryWriteEpoch.current += 1;
    cachedRecovery.current = null;
    const result = clearTemplateRecovery(recoveryStorage, session.baseline.templateId);
    if (result.state === 'cleared') {
      setRecoveryPersistence({ state: 'idle' });
      return true;
    }
    setRecoveryPersistence({ state: 'unavailable', operation: 'clear' });
    return false;
  };

  const inspectImportBytes = async (bytes: Uint8Array, filename: string) => {
    if (importLocked) return;
    const epoch = importEpoch.current + 1;
    importEpoch.current = epoch;
    setImportView({ state: 'inspecting', filename });
    const inspection = await inspectTemplateImport(bytes);
    if (importEpoch.current !== epoch) return;
    switch (inspection.mode) {
      case 'structured':
        setEntry('exchange');
        setImportView({
          state: 'candidate',
          candidate: {
            inspection,
            filename,
            targetTemplateId: session.baseline.templateId,
            baselineKey: baselineIdentity(session.baseline),
            previewGeneration: session.previewGeneration,
            replacement: 'ready',
          },
        });
        return;
      case 'raw-repair':
        setImportView({ state: 'raw-repair', inspection, filename });
        return;
      case 'compatibility':
        setImportView({ state: 'compatibility', inspection, filename });
    }
  };

  const inspectImportFile = async (file: File) => {
    if (importLocked) return;
    try {
      await inspectImportBytes(new Uint8Array(await file.arrayBuffer()), file.name);
    } catch {
      setImportView({ state: 'error', message: '浏览器无法读取所选本地文件；当前工作副本未改变。' });
    }
  };

  const candidateStillBound = (
    candidate: StructuredImportCandidate,
    targetSession: StructuredEditorSession,
  ) => candidate.targetTemplateId === targetSession.baseline.templateId
    && candidate.baselineKey === baselineIdentity(targetSession.baseline)
    && candidate.previewGeneration === targetSession.previewGeneration;

  const adoptImportCandidate = (
    candidate: StructuredImportCandidate,
    targetSession: StructuredEditorSession,
    options: { rebasedAfterSave?: boolean; recoveryCleared?: boolean; committed?: boolean } = {},
  ): boolean => {
    if (candidate.targetTemplateId !== targetSession.baseline.templateId
      || (!options.rebasedAfterSave && !candidateStillBound(candidate, targetSession))) {
      pendingImportAfterSave.current = null;
      setImportView({ state: 'stale', message: '导入候选已因本地 generation 变化而失效。' });
      return false;
    }
    const adopted = adoptStructuredTemplateImport(targetSession, candidate.inspection);
    if (adopted.state === 'no-op') {
      pendingImportAfterSave.current = null;
      setImportView({
        state: 'notice',
        message: '导入内容与当前工作副本相同；未改变本地状态。',
      });
      return true;
    }
    if (!options.recoveryCleared && !clearRecoveryNow()) {
      setImportView({
        state: 'error',
        message: '旧 Local recovery 无法清除；为避免稍后恢复错误草稿，本次导入未替换工作副本。',
      });
      return false;
    }
    pendingImportAfterSave.current = null;
    pendingPreviewAfterSave.current = null;
    preview.invalidate('导入内容已替换本地工作副本；旧权威图片已撤下。');
    setLocalSession(adopted.session);
    setSaveView({ state: 'idle' });
    setRecoveryBase(undefined);
    setRecoveryView(recoveryStorage ? { state: 'none' } : { state: 'disabled' });
    setImportView({
      state: 'notice',
      message: '已接受为 canonical 本地草稿；服务器 current 未被写入。',
    });
    if (options.committed) onSessionCommitted?.(adopted.session);
    return true;
  };

  const requestImportAdoption = (candidate: StructuredImportCandidate) => {
    if (!candidateStillBound(candidate, session)) {
      setImportView({ state: 'stale', message: '导入候选已因本地 generation 变化而失效。' });
      return;
    }
    if (candidate.inspection.canonicalDesignDsl === session.workingCopy.canonicalDesignDsl) {
      adoptImportCandidate(candidate, session);
      return;
    }
    if (dirty) {
      setImportView({
        state: 'candidate',
        candidate: { ...candidate, replacement: 'guard' },
      });
      return;
    }
    adoptImportCandidate(candidate, session);
  };

  const cancelImportReplacement = (candidate: StructuredImportCandidate) => {
    pendingImportAfterSave.current = null;
    setImportView({
      state: 'candidate',
      candidate: { ...candidate, replacement: 'ready' },
    });
  };

  const exportThenAdoptImport = (candidate: StructuredImportCandidate) => {
    downloadBareCanonical(download, session.workingCopy.canonicalDesignDsl, 'renderweave-local-draft.design.json');
    adoptImportCandidate(candidate, session);
  };

  const discardThenAdoptImport = (candidate: StructuredImportCandidate) => {
    adoptImportCandidate(candidate, session);
  };

  const downloadImportBytes = (
    bytes: Uint8Array,
    filename: string,
    mediaType: string,
  ) => download({ filename, mediaType, bytes: bytes.slice() });


  const restoreRecoveryOffer = (
    record: TemplateRecoveryRecord,
    baseState: 'matching' | 'drifted',
  ) => {
    const restored = restoreStructuredSessionFromRecovery(session, record);
    if (restored.state !== 'restored') {
      setRecoveryView({ state: 'invalid', reason: restored.reason });
      return;
    }
    applyRecoveredEditState(record.editState);
    pendingPreviewAfterSave.current = null;
    preview.invalidate('Local recovery 已恢复为本地草稿；旧权威图片已撤下。');
    setRecoveryBase({
      revision: record.baseRevision,
      contentHash: record.baseContentHash,
    });
    setLocalSession(restored.session);
    setSaveView({ state: 'idle' });
    setRecoveryView({
      state: 'restored',
      baseState,
      record,
      resumingUnknown: false,
    });
  };

  const discardRecoveryOffer = () => {
    if (!clearRecoveryNow()) return;
    setRecoveryBase(undefined);
    setRecoveryView({ state: 'none' });
  };

  const discardRestoredRecovery = () => {
    if (!clearRecoveryNow()) return;
    const clean = createSessionFromBaseline(session.baseline, session.readiness);
    if (clean.mode !== 'structured') return;
    pendingPreviewAfterSave.current = null;
    preview.invalidate('Local recovery 草稿已放弃；旧权威图片已撤下。');
    setLocalSession({
      ...clean,
      previewGeneration: session.previewGeneration + 1,
    });
    setRecoveryBase(undefined);
    setSaveView({ state: 'idle' });
    setRecoveryView({ state: 'none' });
  };

  useEffect(() => {
    if (!recoveryStorage || recoveryChoiceLocked) return;
    if (saveView.state === 'pending'
      || saveView.state === 'unknown'
      || saveView.state === 'reconciling'
      || saveView.state === 'retryable'
      || saveView.state === 'deleted'
      || saveView.state === 'failed-closed') return;
    if (!dirty) {
      if (cachedRecovery.current !== null) {
        recoveryWriteEpoch.current += 1;
        cachedRecovery.current = null;
        const result = clearTemplateRecovery(recoveryStorage, session.baseline.templateId);
        setRecoveryPersistence(result.state === 'cleared'
          ? { state: 'idle' }
          : { state: 'unavailable', operation: 'clear' });
      }
      return;
    }

    const epoch = recoveryWriteEpoch.current + 1;
    recoveryWriteEpoch.current = epoch;
    let timer: ReturnType<typeof setTimeout> | undefined;
    void buildTemplateRecoveryRecord(
      session,
      recoveryEditState,
      new Date(recoveryNow()).toISOString(),
      undefined,
      recoveryBase,
    ).then((record) => {
      if (recoveryWriteEpoch.current !== epoch) return;
      cachedRecovery.current = record;
      timer = setTimeout(() => {
        if (recoveryWriteEpoch.current !== epoch) return;
        const result = persistTemplateRecovery(recoveryStorage, record);
        setRecoveryPersistence(result.state === 'stored'
          ? { state: 'stored' }
          : { state: 'unavailable', operation: 'write' });
      }, LOCAL_RECOVERY_DEBOUNCE_MS);
    }).catch(() => {
      if (recoveryWriteEpoch.current === epoch) {
        setRecoveryPersistence({ state: 'unavailable', operation: 'write' });
      }
    });
    return () => {
      if (timer !== undefined) clearTimeout(timer);
      if (recoveryWriteEpoch.current === epoch) recoveryWriteEpoch.current += 1;
    };
  }, [
    dirty,
    recoveryBase,
    recoveryChoiceLocked,
    recoveryEditState,
    recoveryNow,
    recoveryStorage,
    saveView.state,
    session,
  ]);

  useEffect(() => {
    if (!dirty) return;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      const record = cachedRecovery.current;
      if (recoveryStorage && record) persistTemplateRecovery(recoveryStorage, record);
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty, recoveryStorage]);

  const continuePreviewAfterCommit = (
    committed: StructuredEditorSession,
  ): string | undefined => {
    const intent = pendingPreviewAfterSave.current;
    pendingPreviewAfterSave.current = null;
    if (!intent) return undefined;
    if (intent.draftCanonical !== session.workingCopy.canonicalDesignDsl
      || intent.previewGeneration !== session.previewGeneration
      || intent.draftCanonical !== committed.baseline.canonicalDesignDsl) {
      preview.reportProblem({
        source: 'client',
        code: 'EDITOR_PREVIEW_SAVE_BASIS_MISMATCH',
        message: `revision ${committed.baseline.revision} 已采用，但 canonical 内容与保存并预览 intent 不一致；未启动权威预览。`,
      }, true);
      return `revision ${committed.baseline.revision} 已采用；预览 intent 已安全终止。`;
    }
    const committedGuard = authoritativePreviewGuard(committed);
    if (committedGuard.state === 'blocked') {
      const invalid = committed.readiness.state === 'checked'
        && committed.readiness.value === 'INVALID';
      preview.reportProblem({
        source: 'client',
        code: invalid
          ? 'EDITOR_PREVIEW_SAVED_CURRENT_INVALID'
          : 'EDITOR_PREVIEW_SAVED_CURRENT_NOT_READY',
        message: invalid
          ? `revision ${committed.baseline.revision} 已保存为 INVALID；未启动权威预览。`
          : `revision ${committed.baseline.revision} 已保存，但 current 尚不能形成 READY snapshot；未启动权威预览。`,
      }, true);
      return invalid
        ? `revision ${committed.baseline.revision} 已保存为 INVALID；未启动权威预览。`
        : `revision ${committed.baseline.revision} 已保存；当前未启动权威预览。`;
    }
    void preview.start(committed, intent.request, { savedFirst: true });
    return `revision ${committed.baseline.revision} 已保存；权威预览已作为独立操作发起。`;
  };

  const abandonPendingPreview = (saveCode: string) => {
    if (!pendingPreviewAfterSave.current) return;
    pendingPreviewAfterSave.current = null;
    preview.reportProblem({
      source: 'client',
      code: 'EDITOR_PREVIEW_SAVE_NOT_COMPLETED',
      message: `Template 保存未完成（${saveCode}）；未启动权威预览。`,
    });
  };

  const acceptSaveResult = (result: TemplateSaveResult) => {
    switch (result.state) {
      case 'saved': {
        clearRecoveryNow();
        setRecoveryBase(undefined);
        setRecoveryView(recoveryStorage ? { state: 'none' } : { state: 'disabled' });
        if (pendingImportAfterSave.current) {
          pendingPreviewAfterSave.current = null;
          adoptImportCandidate(pendingImportAfterSave.current, result.session, {
            rebasedAfterSave: true,
            recoveryCleared: true,
            committed: true,
          });
          return;
        }
        if (importView.state === 'candidate') {
          setImportView({ state: 'stale', message: '导入候选已因本地 generation 变化而失效。' });
        }
        setLocalSession(result.session);
        const previewNotice = continuePreviewAfterCommit(result.session);
        setSaveView(previewNotice
          ? { state: 'confirmed-current', message: previewNotice }
          : { state: 'idle' });
        onSessionCommitted?.(result.session, previewNotice);
        return;
      }
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
        pendingImportAfterSave.current = null;
        abandonPendingPreview(result.code);
        setSaveView({ state: 'rejected', code: result.code, message: result.message });
        return;
      case 'offer-invalidated':
        pendingImportAfterSave.current = null;
        abandonPendingPreview(result.code);
        setSaveView({
          state: 'rejected',
          code: result.code,
          message: result.message,
        });
        return;
      case 'unknown':
        void persistRecoveryNow(session, result.attempt);
        setSaveView({ state: 'unknown', attempt: result.attempt, message: result.message });
        void runReconciliation(result.attempt);
    }
  };

  const acceptReconciliationResult = (result: TemplateSaveReconciliationResult) => {
    switch (result.state) {
      case 'adopted': {
        clearRecoveryNow();
        setRecoveryBase(undefined);
        setRecoveryView(recoveryStorage ? { state: 'none' } : { state: 'disabled' });
        if (pendingImportAfterSave.current) {
          pendingPreviewAfterSave.current = null;
          adoptImportCandidate(pendingImportAfterSave.current, result.session, {
            rebasedAfterSave: true,
            recoveryCleared: true,
            committed: true,
          });
          return;
        }
        if (importView.state === 'candidate') {
          setImportView({ state: 'stale', message: '导入候选已因本地 generation 变化而失效。' });
        }
        setLocalSession(result.session);
        const previewNotice = continuePreviewAfterCommit(result.session);
        const adoptedNotice = previewNotice ?? result.message;
        setSaveView({ state: 'confirmed-current', message: adoptedNotice });
        onSessionCommitted?.(result.session, adoptedNotice);
        return;
      }
      case 'retryable':
        setSaveView({ state: 'retryable', attempt: result.attempt, message: result.message });
        return;
      case 'conflict':
        setSaveView({ state: 'conflict', offer: result.offer, message: result.message });
        return;
      case 'deleted':
        abandonPendingPreview('TEMPLATE_DELETED');
        setSaveView({ state: 'deleted', attempt: result.attempt, message: result.message });
        return;
      case 'unavailable':
        setSaveView({ state: 'unknown', attempt: result.attempt, message: result.message });
        return;
      case 'failed-closed':
        abandonPendingPreview(result.code);
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

  const resumeUnknownReconciliation = useEffectEvent((attempt: TemplateUnknownSaveAttempt) => {
    void runReconciliation(attempt);
  });

  useEffect(() => {
    if (!recoveryStorage) return;
    const openingSession = incomingSessionRef.current;
    const epoch = recoveryEpoch.current + 1;
    recoveryEpoch.current = epoch;
    void loadTemplateRecovery(
      recoveryStorage,
      openingSession.baseline,
      recoveryNow(),
    ).then((loaded) => {
      if (recoveryEpoch.current !== epoch) return;
      if (loaded.state === 'available') {
        cachedRecovery.current = loaded.record;
        if (loaded.record.unknownAttempt) {
          const restored = restoreStructuredSessionFromRecovery(openingSession, loaded.record);
          if (restored.state !== 'restored') {
            setRecoveryView({ state: 'invalid', reason: restored.reason });
            return;
          }
          setEntry(loaded.record.editState.entry);
          setSelectedNodeId(loaded.record.editState.selectedNodeId);
          setNavigatorOpen(loaded.record.editState.navigatorOpen);
          setInspectorOpen(loaded.record.editState.inspectorOpen);
          setRecoveryBase({
            revision: loaded.record.baseRevision,
            contentHash: loaded.record.baseContentHash,
          });
          setLocalSession(restored.session);
          setRecoveryView({
            state: 'restored',
            baseState: loaded.baseState,
            record: loaded.record,
            resumingUnknown: true,
          });
          const unknownAttempt = loaded.record.unknownAttempt;
          setSaveView({
            state: 'unknown',
            attempt: unknownAttempt,
            message: '已恢复结果不明的保存上下文；正在先核验 trusted current。',
          });
          queueMicrotask(() => {
            if (recoveryEpoch.current === epoch) {
              resumeUnknownReconciliation(unknownAttempt);
            }
          });
          return;
        }
        setRecoveryView({
          state: 'offer',
          baseState: loaded.baseState,
          record: loaded.record,
        });
        return;
      }
      cachedRecovery.current = null;
      if (loaded.state === 'invalid') {
        setRecoveryView({
          state: 'invalid',
          reason: loaded.reason,
          ...(loaded.exportCanonical === undefined
            ? {}
            : { exportCanonical: loaded.exportCanonical }),
        });
      } else if (loaded.state === 'unavailable') {
        setRecoveryView({ state: 'unavailable', operation: loaded.operation });
      } else {
        setRecoveryView({ state: 'none' });
      }
    });
    return () => {
      if (recoveryEpoch.current === epoch) recoveryEpoch.current += 1;
    };
  }, [incomingBaselineKey, recoveryNow, recoveryStorage]);

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
      await persistRecoveryNow(session);
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

  const save = (origin: 'plain' | 'preview' | 'import' = 'plain') => {
    const transport = saveTransport;
    if (!transport) return;
    if (origin !== 'preview') pendingPreviewAfterSave.current = null;
    if (recoveryView.state === 'restored') {
      const overwrite = recoveryOverwriteOffer(
        session,
        recoveryView.record,
        recoveryView.baseState,
      );
      if (overwrite) {
        setSaveView({
          state: 'conflict',
          offer: overwrite,
          message: `本地恢复草稿基于 revision ${recoveryView.record.baseRevision}；`
            + `trusted current 已是 revision ${session.baseline.revision}，保存前需要显式确认覆盖。`,
        });
        return;
      }
    }
    void runMutation(
      '保存请求进行中',
      (signal) => saveTemplateWorkingCopy(
        session,
        transport,
        signal,
        (attempt) => persistRecoveryNow(session, attempt),
      ),
    );
  };
  const generatePreview = () => {
    if (!preview.enabled || localLocked) return;
    if (!dirty) {
      void preview.start(session);
      return;
    }
    if (!saveTransport) return;
    pendingImportAfterSave.current = null;
    pendingPreviewAfterSave.current = {
      draftCanonical: session.workingCopy.canonicalDesignDsl,
      previewGeneration: session.previewGeneration,
      request: { ...preview.request },
    };
    save('preview');
  };
  const saveThenAdoptImport = (candidate: StructuredImportCandidate) => {
    if (!saveTransport || !candidateStillBound(candidate, session)) {
      setImportView({ state: 'stale', message: '导入候选已因本地 generation 变化而失效。' });
      return;
    }
    pendingPreviewAfterSave.current = null;
    pendingImportAfterSave.current = candidate;
    save('import');
  };
  const cancelSaveOffer = () => {
    const pending = pendingImportAfterSave.current;
    pendingImportAfterSave.current = null;
    abandonPendingPreview('EDITOR_PREVIEW_SAVE_CANCELLED');
    setSaveView({ state: 'idle' });
    if (pending) cancelImportReplacement(pending);
  };
  const confirmOverwrite = (offer: TemplateConflictOffer) => {
    const transport = saveTransport;
    if (!transport) return;
    void runMutation(
      '正在重读 current 并提交覆盖',
      (signal) => confirmTemplateOverwrite(
        session,
        offer,
        transport,
        signal,
        (attempt) => persistRecoveryNow(session, attempt),
      ),
    );
  };
  const confirmInvalidSave = (offer: TemplateInvalidSaveOffer) => {
    const transport = saveTransport;
    if (!transport) return;
    void runMutation(
      '正在确认并保存 INVALID revision',
      (signal) => confirmTemplateInvalidSave(
        session,
        offer,
        transport,
        signal,
        (attempt) => persistRecoveryNow(session, attempt),
      ),
    );
  };
  const problemLocation = (canonicalPointer: string) => locateTemplateProblem(
    session.workingCopy.designDsl,
    nodes,
    canonicalPointer,
  );
  const focusProblemLocation = (
    problem: InvalidSaveProblem,
    location: LocatedTemplateProblem,
  ) => {
    switch (location.target.kind) {
      case 'template-display-name':
        setInspectorOpen(true);
        break;
      case 'definitions':
        setNavigatorOpen(true);
        setEntry('definitions');
        break;
      case 'node':
        setNavigatorOpen(true);
        setEntry('structure');
        setSelectedNodeId(location.target.nodeId);
        break;
    }
    queueMicrotask(() => {
      const root = editorRootRef.current;
      if (!root) return;
      let target: HTMLElement | undefined;
      if (location.target.kind === 'template-display-name') {
        target = root.querySelector<HTMLElement>('[data-template-editor-location="template-display-name"]')
          ?? undefined;
      } else if (location.target.kind === 'definitions') {
        target = root.querySelector<HTMLElement>('[data-template-editor-location="definitions"]')
          ?? undefined;
      } else {
        const nodeId = location.target.nodeId;
        target = [...root.querySelectorAll<HTMLElement>('[data-template-editor-node-id]')]
          .find((candidate) => candidate.dataset.templateEditorNodeId === nodeId);
      }
      if (target) {
        target.focus({ preventScroll: true });
        target.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
        setAnnouncement(problemLocationAnnouncement(problem.code, location));
      } else {
        setAnnouncement(`问题 ${problem.code} 的目标当前不可用；问题仍保留在摘要中。`);
      }
    });
  };
  const retryUnknownSave = (attempt: TemplateUnknownSaveAttempt) => {
    const transport = saveTransport;
    if (!transport) return;
    void runMutation(
      '正在显式重试原保存',
      (signal) => retryTemplateUnknownSave(
        session,
        attempt,
        transport,
        signal,
        (prepared) => persistRecoveryNow(session, prepared),
      ),
      true,
    );
  };

  if (importView.state === 'raw-repair') {
    return (
      <ImportedRawRepairShell
        key={`${importView.filename}:${importView.inspection.code}`}
        baseline={session.baseline}
        readiness={session.readiness}
        filename={importView.filename}
        inspection={importView.inspection}
        disabled={importLocked}
        onInspect={(bytes) => inspectImportBytes(bytes, importView.filename)}
        onInspectFile={inspectImportFile}
        onDownload={() => downloadImportBytes(
          importView.inspection.originalBytes,
          importView.filename,
          'application/octet-stream',
        )}
        onDownloadRepair={(text) => downloadImportBytes(
          new TextEncoder().encode(text),
          `repaired-${importView.filename}`,
          BARE_DESIGN_DSL_MEDIA_TYPE,
        )}
        onDiscard={() => {
          importEpoch.current += 1;
          setEntry('exchange');
          setImportView({ state: 'idle' });
        }}
      />
    );
  }
  if (importView.state === 'compatibility') {
    const mediaType = importView.inspection.source === 'template-revision-export'
      ? TEMPLATE_REVISION_EXPORT_MEDIA_TYPE
      : BARE_DESIGN_DSL_MEDIA_TYPE;
    return (
      <ImportedCompatibilityShell
        baseline={session.baseline}
        readiness={session.readiness}
        filename={importView.filename}
        inspection={importView.inspection}
        disabled={importLocked}
        onInspectFile={inspectImportFile}
        onDownload={() => downloadImportBytes(
          importView.inspection.originalBytes,
          importView.filename,
          mediaType,
        )}
        onDiscard={() => {
          importEpoch.current += 1;
          setEntry('exchange');
          setImportView({ state: 'idle' });
        }}
      />
    );
  }

  return (
    <EditorFrame
      rootRef={editorRootRef}
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
        tabIndex={-1}
      >
        <p
          className="sr-only"
          role="status"
          aria-live="polite"
          aria-atomic="true"
          data-template-editor-announcer=""
        >
          {announcement}
        </p>
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
            <section className="te-entry-panel">
              <EntryPanel
                entry={entry}
                session={session}
                nodes={nodes}
                selectedNodeId={effectiveSelectedNodeId}
                onSelectNode={setSelectedNodeId}
                importView={importView}
                importLocked={importLocked}
                canSaveBeforeImport={saveTransport !== undefined}
                onInspectImport={inspectImportFile}
                onRequestImportAdoption={requestImportAdoption}
                onCancelImportReplacement={cancelImportReplacement}
                onSaveThenAdoptImport={saveThenAdoptImport}
                onExportThenAdoptImport={exportThenAdoptImport}
                onDiscardThenAdoptImport={discardThenAdoptImport}
                onDiscardImportCandidate={() => {
                  importEpoch.current += 1;
                  pendingImportAfterSave.current = null;
                  setImportView({ state: 'idle' });
                }}
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
          <TemplateRecoveryPanel
            view={recoveryView}
            persistence={recoveryPersistence}
            currentRevision={session.baseline.revision}
            currentCanonical={session.workingCopy.canonicalDesignDsl}
            onRestore={restoreRecoveryOffer}
            onDiscardOffer={discardRecoveryOffer}
            onDiscardRestored={discardRestoredRecovery}
            onExport={(canonical) => downloadBareCanonical(
              download,
              canonical,
              'renderweave-local-draft.design.json',
            )}
          />
          {saveView.state === 'invalid-save-confirmation' ? (
            <InvalidSaveConfirmationPanel
              view={saveView}
              locate={problemLocation}
              onLocate={focusProblemLocation}
              onConfirm={() => confirmInvalidSave(saveView.offer)}
              onCancel={cancelSaveOffer}
            />
          ) : null}
          <CanvasProjection workingCopy={session.workingCopy} nodes={nodes} />
          {preview.enabled ? (
            <TemplatePreviewPanel
              coordinator={preview}
              session={session}
              documentName={workingName}
              localLocked={localLocked}
              canSave={saveTransport !== undefined}
              onGenerate={generatePreview}
            />
          ) : null}
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
              onCancelOverwrite={cancelSaveOffer}
              onReconcile={runReconciliation}
              onRetry={retryUnknownSave}
              onExport={(attempt) => downloadBareCanonical(
                download,
                attempt.draftCanonical,
                'renderweave-local-draft.design.json',
              )}
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
          {preview.enabled ? (
            <button
              type="button"
              aria-pressed={preview.panelOpen}
              aria-controls="template-authoritative-preview-panel"
              aria-label={preview.panelOpen ? '关闭权威预览' : '打开权威预览'}
              onClick={preview.panelOpen ? preview.close : preview.open}
            >
              <ShieldCheck aria-hidden="true" size={15} />
              权威预览
            </button>
          ) : null}
          <span>{nodes.length} 个 authored 节点</span>
        </div>
      </main>
    </EditorFrame>
  );
}

function TemplateCatalogLink() {
  return (
    <a className="te-product-mark" href="/templates" aria-label="返回模板目录">
      <span className="weave-mark" aria-hidden="true">RW</span>
      <span>RenderWeave</span>
    </a>
  );
}

function EditorFrame({
  rootRef,
  baseline,
  documentName,
  modeLabel,
  readiness,
  onRetryReadiness,
  headerTools,
  children,
}: {
  rootRef?: React.Ref<HTMLDivElement>;
  baseline: CanonicalTemplateBaseline;
  documentName?: string;
  modeLabel: string;
  readiness: EditorReadiness;
  onRetryReadiness?: () => void;
  headerTools?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="template-editor-root" ref={rootRef}>
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <header className="te-chrome">
        <TemplateCatalogLink />
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
            data-template-editor-location="template-display-name"
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

function TemplateRecoveryPanel({
  view,
  persistence,
  currentRevision,
  currentCanonical,
  onRestore,
  onDiscardOffer,
  onDiscardRestored,
  onExport,
}: {
  view: StructuredRecoveryView;
  persistence: RecoveryPersistenceView;
  currentRevision: string;
  currentCanonical: string;
  onRestore: (record: TemplateRecoveryRecord, baseState: 'matching' | 'drifted') => void;
  onDiscardOffer: () => void;
  onDiscardRestored: () => void;
  onExport: (canonical: string) => void;
}) {
  if (view.state === 'disabled' || view.state === 'none') {
    return persistence.state === 'unavailable' ? (
      <section className="te-recovery-status is-unavailable" role="alert">
        <AlertTriangle aria-hidden="true" size={18} />
        <div>
          <strong>此设备上的 Local recovery 不可用</strong>
          <span>浏览器未能{persistence.operation === 'clear' ? '清除' : '保存'}恢复记录；请导出 canonical 草稿。</span>
          <button type="button" onClick={() => onExport(currentCanonical)}>导出当前 canonical 草稿</button>
        </div>
      </section>
    ) : null;
  }
  if (view.state === 'loading') {
    return (
      <section className="te-recovery-status" role="status" aria-live="polite">
        <LoaderCircle className="te-loading-icon" aria-hidden="true" size={18} />
        <div>
          <strong>正在检查此设备的本地恢复记录</strong>
          <span>记录通过完整性与 7 天期限校验前不会装入编辑器。</span>
        </div>
      </section>
    );
  }
  if (view.state === 'unavailable') {
    return (
      <section className="te-recovery-status is-unavailable" role="alert">
        <AlertTriangle aria-hidden="true" size={18} />
        <div>
          <strong>无法访问此设备的 Local recovery</strong>
          <span>浏览器存储不可用；当前会话仍可编辑，但异常关闭恢复不受保证。</span>
        </div>
      </section>
    );
  }
  if (view.state === 'invalid') {
    return (
      <section className="te-recovery-status is-invalid" role="alert">
        <AlertTriangle aria-hidden="true" size={18} />
        <div>
          <h2>本地恢复记录不可验证</h2>
          <span>记录未装入 Structured Editor（{view.reason}）。</span>
          <div className="te-recovery-actions">
            {view.exportCanonical ? (
              <button type="button" onClick={() => onExport(view.exportCanonical!)}>
                导出未验证的本地草稿
              </button>
            ) : null}
            <button type="button" className="is-secondary" onClick={onDiscardOffer}>
              清除此恢复记录
            </button>
          </div>
        </div>
      </section>
    );
  }
  if (view.state === 'offer') {
    const drifted = view.baseState === 'drifted';
    return (
      <section className={`te-recovery-status${drifted ? ' is-drifted' : ''}`} role="alert">
        <RefreshCw aria-hidden="true" size={18} />
        <div>
          <h2>发现此设备上的本地恢复草稿</h2>
          <span>
            {drifted
              ? `本地草稿基于 revision ${view.record.baseRevision}；trusted current 为 revision ${currentRevision}。恢复后保存仍需显式确认覆盖。`
              : `本地草稿与 trusted current revision ${currentRevision} 基线一致；不会自动载入或提交。`}
          </span>
          <small>更新于 {view.record.updatedAt} · 当前设备 best-effort</small>
          <div className="te-recovery-actions">
            <button
              type="button"
              onClick={() => onRestore(view.record, view.baseState)}
            >
              {drifted ? '确认恢复旧基线草稿' : '恢复本地草稿'}
            </button>
            <button type="button" className="is-secondary" onClick={() => onExport(view.record.draftCanonical)}>
              导出本地恢复草稿
            </button>
            <button type="button" className="is-secondary" onClick={onDiscardOffer}>
              放弃本地恢复草稿
            </button>
          </div>
        </div>
      </section>
    );
  }
  return (
    <section className="te-recovery-status is-restored" role="status" aria-live="polite">
      <CheckCircle2 aria-hidden="true" size={18} />
      <div>
        <strong>{view.resumingUnknown
          ? '已恢复结果不明的保存上下文'
          : '已恢复此设备上的本地草稿'}</strong>
        <span>{view.resumingUnknown
          ? '先只读核验 trusted current；不会自动重发保存。'
          : '恢复没有写入服务器；可继续编辑、导出或明确放弃。'}</span>
        {!view.resumingUnknown ? (
          <div className="te-recovery-actions">
            <button type="button" onClick={() => onExport(currentCanonical)}>导出已恢复草稿</button>
            <button type="button" className="is-secondary" onClick={onDiscardRestored}>
              放弃已恢复草稿
            </button>
          </div>
        ) : null}
        {persistence.state === 'unavailable' ? (
          <small>浏览器未能更新 Local recovery；请先导出草稿。</small>
        ) : null}
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
  locate,
  onLocate,
  onConfirm,
  onCancel,
}: {
  view: Extract<StructuredSaveView, { state: 'invalid-save-confirmation' }>;
  locate: (canonicalPointer: string) => TemplateProblemLocation;
  onLocate: (problem: InvalidSaveProblem, location: LocatedTemplateProblem) => void;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();
  const summaryId = `${titleId}-summary`;
  const summaryRef = useRef<HTMLElement>(null);
  const problemCount = view.offer.problems.length;

  useEffect(() => {
    summaryRef.current?.focus({ preventScroll: true });
  }, [view.offer.confirmationToken, view.offer.proposedContentHash]);

  return (
    <section
      ref={summaryRef}
      className="te-invalid-save-confirmation"
      role="alert"
      aria-labelledby={titleId}
      aria-describedby={summaryId}
      tabIndex={-1}
    >
      <header>
        <AlertTriangle aria-hidden="true" size={20} />
        <div>
          <h2 id={titleId}>确认仍保存为 INVALID</h2>
          <span>{view.message}</span>
        </div>
      </header>
      <p id={summaryId}>
        {problemCount} 项依赖问题 · {view.offer.truncated ? '已截断' : '完整未截断'}
      </p>
      <ul>
        {view.offer.problems.map((problem, index) => {
          const location = locate(problem.canonicalPointer);
          return (
            <li key={`${problem.canonicalPointer}\0${problem.code}\0${index}`}>
              <code>{problem.code}</code>
              <code>{problem.canonicalPointer || '(root)'}</code>
              {location.state === 'located' ? (
                <button
                  type="button"
                  className="te-problem-locator"
                  aria-label={`${problem.code}：${problemLocationButtonLabel(location)}`}
                  onClick={() => onLocate(problem, location)}
                >
                  {problemLocationButtonLabel(location)}
                </button>
              ) : (
                <span className="te-problem-unavailable">只能在问题摘要中查看</span>
              )}
            </li>
          );
        })}
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

function problemLocationButtonLabel(location: LocatedTemplateProblem): string {
  switch (location.target.kind) {
    case 'template-display-name':
      return '定位到 Template 名称';
    case 'definitions':
      return '定位到定义面板';
    case 'node':
      return `定位到节点“${location.target.label}”`;
  }
}

function problemLocationAnnouncement(
  code: string,
  location: LocatedTemplateProblem,
): string {
  switch (location.target.kind) {
    case 'template-display-name':
      return `已定位问题 ${code} 到 Template 名称。`;
    case 'definitions':
      return `已定位问题 ${code} 到定义面板。`;
    case 'node':
      return location.precision === 'owning-node'
        ? `已定位问题 ${code} 到所属节点“${location.target.label}”；具体属性没有独立表单控件。`
        : `已定位问题 ${code} 到节点“${location.target.label}”。`;
  }
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
  importView,
  importLocked,
  canSaveBeforeImport,
  onInspectImport,
  onRequestImportAdoption,
  onCancelImportReplacement,
  onSaveThenAdoptImport,
  onExportThenAdoptImport,
  onDiscardThenAdoptImport,
  onDiscardImportCandidate,
}: {
  entry: EditorEntry;
  session: StructuredEditorSession;
  nodes: EditorNodeProjection[];
  selectedNodeId: string;
  onSelectNode: (nodeId: string) => void;
  importView: StructuredImportView;
  importLocked: boolean;
  canSaveBeforeImport: boolean;
  onInspectImport: (file: File) => void;
  onRequestImportAdoption: (candidate: StructuredImportCandidate) => void;
  onCancelImportReplacement: (candidate: StructuredImportCandidate) => void;
  onSaveThenAdoptImport: (candidate: StructuredImportCandidate) => void;
  onExportThenAdoptImport: (candidate: StructuredImportCandidate) => void;
  onDiscardThenAdoptImport: (candidate: StructuredImportCandidate) => void;
  onDiscardImportCandidate: () => void;
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
      return (
        <ExchangeSummary
          session={session}
          view={importView}
          disabled={importLocked}
          canSaveBeforeImport={canSaveBeforeImport}
          onInspect={onInspectImport}
          onRequestAdoption={onRequestImportAdoption}
          onCancelReplacement={onCancelImportReplacement}
          onSaveThenAdopt={onSaveThenAdoptImport}
          onExportThenAdopt={onExportThenAdoptImport}
          onDiscardThenAdopt={onDiscardThenAdoptImport}
          onDiscardCandidate={onDiscardImportCandidate}
        />
      );
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
  const buttonRefs = useRef(new Map<string, HTMLButtonElement>());
  const selectedIndex = nodes.findIndex((node) => node.nodeId === selectedNodeId);
  const effectiveVisibleCount = selectedIndex >= 0
    ? Math.max(visibleCount, selectedIndex + 1)
    : visibleCount;
  const visibleNodes = nodes.slice(0, effectiveVisibleCount);

  const moveFocus = (currentNodeId: string, key: string) => {
    const currentIndex = visibleNodes.findIndex((node) => node.nodeId === currentNodeId);
    if (currentIndex < 0) return;
    let nextIndex: number;
    if (key === 'ArrowDown') nextIndex = Math.min(visibleNodes.length - 1, currentIndex + 1);
    else if (key === 'ArrowUp') nextIndex = Math.max(0, currentIndex - 1);
    else if (key === 'Home') nextIndex = 0;
    else if (key === 'End') nextIndex = visibleNodes.length - 1;
    else return;
    const next = visibleNodes[nextIndex];
    if (!next) return;
    onSelectNode(next.nodeId);
    buttonRefs.current.get(next.nodeId)?.focus();
  };

  return (
    <>
      <PanelHeading title="结构" detail={`${nodes.length} 个节点`} />
      <ul className="te-tree" role="tree" aria-label="DesignDSL 结构">
        {visibleNodes.map((node) => (
          <li key={node.nodeId} role="none">
            <button
              ref={(element) => {
                if (element) buttonRefs.current.set(node.nodeId, element);
                else buttonRefs.current.delete(node.nodeId);
              }}
              type="button"
              role="treeitem"
              data-template-editor-node-id={node.nodeId}
              aria-level={node.depth + 1}
              aria-selected={node.nodeId === selectedNodeId}
              tabIndex={node.nodeId === selectedNodeId ? 0 : -1}
              style={{ paddingInlineStart: `${12 + node.depth * 16}px` }}
              onClick={() => onSelectNode(node.nodeId)}
              onKeyDown={(event) => {
                if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
                event.preventDefault();
                moveFocus(node.nodeId, event.key);
              }}
            >
              <ChevronRight aria-hidden="true" size={13} />
              <span>{node.displayName}</span>
              <small>{node.kind}</small>
            </button>
          </li>
        ))}
      </ul>
      {effectiveVisibleCount < nodes.length ? (
        <button
          className="te-more-button"
          type="button"
          onClick={() => setVisibleCount((count) => Math.min(nodes.length, count + 50))}
        >
          再显示 {Math.min(50, nodes.length - effectiveVisibleCount)} 个节点
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
      <PanelHeading title="定义" detail={`${definitions.length} 个定义`} location="definitions" />
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

function ExchangeSummary({
  session,
  view,
  disabled,
  canSaveBeforeImport,
  onInspect,
  onRequestAdoption,
  onCancelReplacement,
  onSaveThenAdopt,
  onExportThenAdopt,
  onDiscardThenAdopt,
  onDiscardCandidate,
}: {
  session: StructuredEditorSession;
  view: StructuredImportView;
  disabled: boolean;
  canSaveBeforeImport: boolean;
  onInspect: (file: File) => void;
  onRequestAdoption: (candidate: StructuredImportCandidate) => void;
  onCancelReplacement: (candidate: StructuredImportCandidate) => void;
  onSaveThenAdopt: (candidate: StructuredImportCandidate) => void;
  onExportThenAdopt: (candidate: StructuredImportCandidate) => void;
  onDiscardThenAdopt: (candidate: StructuredImportCandidate) => void;
  onDiscardCandidate: () => void;
}) {
  const { baseline, workingCopy } = session;
  return (
    <>
      <PanelHeading title="交换" detail={isCanonicalDirty(session) ? 'canonical local' : 'canonical current'} />
      <p className="te-panel-copy">只从本地字节检查 bare DesignDSL 或 exact revision export；检查本身不会替换工作副本。</p>
      <ImportFilePicker disabled={disabled} onFile={onInspect} label="选择本地 Template 文件" />
      <ImportExchangeStatus
        view={view}
        disabled={disabled}
        canSaveBeforeImport={canSaveBeforeImport}
        onRequestAdoption={onRequestAdoption}
        onCancelReplacement={onCancelReplacement}
        onSaveThenAdopt={onSaveThenAdopt}
        onExportThenAdopt={onExportThenAdopt}
        onDiscardThenAdopt={onDiscardThenAdopt}
        onDiscardCandidate={onDiscardCandidate}
      />
      <dl className="te-fact-list">
        <div><dt>revision</dt><dd>{baseline.revision}</dd></div>
        <div><dt>contentHash</dt><dd title={baseline.contentHash}>{shortHash(baseline.contentHash)}</dd></div>
        <div><dt>current UTF-8</dt><dd>{new TextEncoder().encode(baseline.canonicalDesignDsl).byteLength} bytes</dd></div>
        <div><dt>local UTF-8</dt><dd>{new TextEncoder().encode(workingCopy.canonicalDesignDsl).byteLength} bytes</dd></div>
      </dl>
    </>
  );
}

function ImportFilePicker({
  disabled,
  onFile,
  label,
}: {
  disabled: boolean;
  onFile: (file: File) => void;
  label: string;
}) {
  return (
    <label className={`te-import-picker${disabled ? ' is-disabled' : ''}`}>
      <FileJson aria-hidden="true" size={16} />
      <span>{label}</span>
      <input
        type="file"
        accept={`${BARE_DESIGN_DSL_MEDIA_TYPE},${TEMPLATE_REVISION_EXPORT_MEDIA_TYPE},application/json,.json`}
        disabled={disabled}
        onChange={(event) => {
          const file = event.currentTarget.files?.[0];
          event.currentTarget.value = '';
          if (file) onFile(file);
        }}
      />
    </label>
  );
}

function ImportExchangeStatus({
  view,
  disabled,
  canSaveBeforeImport,
  onRequestAdoption,
  onCancelReplacement,
  onSaveThenAdopt,
  onExportThenAdopt,
  onDiscardThenAdopt,
  onDiscardCandidate,
}: {
  view: StructuredImportView;
  disabled: boolean;
  canSaveBeforeImport: boolean;
  onRequestAdoption: (candidate: StructuredImportCandidate) => void;
  onCancelReplacement: (candidate: StructuredImportCandidate) => void;
  onSaveThenAdopt: (candidate: StructuredImportCandidate) => void;
  onExportThenAdopt: (candidate: StructuredImportCandidate) => void;
  onDiscardThenAdopt: (candidate: StructuredImportCandidate) => void;
  onDiscardCandidate: () => void;
}) {
  if (view.state === 'idle') {
    return <p className="te-import-note">未选择本地文件；服务器 current 与 Local recovery 均未改变。</p>;
  }
  if (view.state === 'inspecting') {
    return (
      <div className="te-import-status" role="status">
        <LoaderCircle className="te-loading-icon" aria-hidden="true" size={16} />
        <span>正在严格检查本地文件…</span>
      </div>
    );
  }
  if (view.state === 'stale' || view.state === 'error') {
    return <p className="te-import-status is-warning" role="alert">{view.message}</p>;
  }
  if (view.state === 'notice') {
    return <p className="te-import-status is-success" role="status">{view.message}</p>;
  }
  if (view.state !== 'candidate') return null;
  const { candidate } = view;
  if (candidate.replacement === 'guard') {
    return (
      <section className="te-import-guard" role="alert">
        <h3>替换当前本地草稿？</h3>
        <p>导入不会自动保存；必须先明确处理当前 canonical working copy。</p>
        <div className="te-import-actions">
          {canSaveBeforeImport ? (
            <button type="button" disabled={disabled} onClick={() => onSaveThenAdopt(candidate)}>
              保存当前草稿后继续导入
            </button>
          ) : null}
          <button type="button" disabled={disabled} onClick={() => onExportThenAdopt(candidate)}>
            导出当前草稿并继续导入
          </button>
          <button type="button" disabled={disabled} onClick={() => onDiscardThenAdopt(candidate)}>
            放弃当前草稿并继续导入
          </button>
          <button type="button" className="is-secondary" disabled={disabled} onClick={() => onCancelReplacement(candidate)}>
            取消导入替换
          </button>
        </div>
      </section>
    );
  }
  const { inspection } = candidate;
  return (
    <section className="te-import-candidate" role="status">
      <CheckCircle2 aria-hidden="true" size={16} />
      <div>
        <strong>导入检查通过</strong>
        <span>{candidate.filename} · {encoderByteLength(inspection.canonicalDesignDsl)} UTF-8 bytes</span>
        <span>目标仍为当前 Template 与 StaticSchema</span>
        {inspection.sourceIdentity ? (
          <small>
            文件显示身份：{shortIdentity(inspection.sourceIdentity.templateId)} · revision {inspection.sourceIdentity.revision}
          </small>
        ) : null}
        {inspection.sourceStaticSchema ? (
          <small>
            文件显示 Schema：{inspection.sourceStaticSchema.schemaKey}@{inspection.sourceStaticSchema.versionTag}
          </small>
        ) : null}
        <div className="te-import-actions">
          <button type="button" disabled={disabled} onClick={() => onRequestAdoption(candidate)}>
            接受导入为本地草稿
          </button>
          <button type="button" className="is-secondary" disabled={disabled} onClick={onDiscardCandidate}>
            丢弃导入候选
          </button>
        </div>
      </div>
    </section>
  );
}

function PanelHeading({
  title,
  detail,
  location,
}: {
  title: string;
  detail: string;
  location?: string;
}) {
  return (
    <header
      className="te-panel-heading"
      data-template-editor-location={location}
      tabIndex={location ? -1 : undefined}
    >
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

function ImportedCompatibilityShell({
  baseline,
  readiness,
  filename,
  inspection,
  disabled,
  onInspectFile,
  onDownload,
  onDiscard,
}: {
  baseline: CanonicalTemplateBaseline;
  readiness: EditorReadiness;
  filename: string;
  inspection: CompatibilityTemplateImport;
  disabled: boolean;
  onInspectFile: (file: File) => void;
  onDownload: () => void;
  onDiscard: () => void;
}) {
  return (
    <EditorFrame
      baseline={baseline}
      documentName={templateDisplayName(baseline)}
      modeLabel="Compatibility Read-only"
      readiness={readiness}
    >
      <main className="te-safe-mode" id="main-content" aria-label="Template 编辑工作区">
        <section className="te-safe-card te-import-mode-card">
          <AlertTriangle aria-hidden="true" size={24} />
          <div>
            <p className="te-eyebrow">完整 JSON · 未知 closed wire</p>
            <h2>Compatibility Read-only</h2>
            <p>{inspection.message}</p>
            <p>当前没有已注册的 migration profile；不会显示不可执行的迁移动作。</p>
          </div>
          <dl className="te-fact-list">
            <div><dt>文件</dt><dd>{filename}</dd></div>
            <div><dt>来源合同</dt><dd>{inspection.source}</dd></div>
            <div><dt>目标 Template</dt><dd>{shortIdentity(baseline.templateId)}</dd></div>
            {inspection.sourceIdentity ? (
              <div><dt>文件身份</dt><dd>{shortIdentity(inspection.sourceIdentity.templateId)} · revision {inspection.sourceIdentity.revision}</dd></div>
            ) : null}
            {inspection.sourceStaticSchema ? (
              <div><dt>文件 Schema</dt><dd>{inspection.sourceStaticSchema.schemaKey}@{inspection.sourceStaticSchema.versionTag}</dd></div>
            ) : null}
          </dl>
          <div className="te-import-mode-actions">
            <button type="button" onClick={onDownload}><Download aria-hidden="true" size={16} />原样导出兼容文件</button>
            <ImportFilePicker disabled={disabled} onFile={onInspectFile} label="替换导入文件" />
            <button type="button" className="is-secondary" onClick={onDiscard}>丢弃导入并返回 Structured</button>
          </div>
        </section>
      </main>
    </EditorFrame>
  );
}

function ImportedRawRepairShell({
  baseline,
  readiness,
  filename,
  inspection,
  disabled,
  onInspect,
  onInspectFile,
  onDownload,
  onDownloadRepair,
  onDiscard,
}: {
  baseline: CanonicalTemplateBaseline;
  readiness: EditorReadiness;
  filename: string;
  inspection: RawRepairTemplateImport;
  disabled: boolean;
  onInspect: (bytes: Uint8Array) => void;
  onInspectFile: (file: File) => void;
  onDownload: () => void;
  onDownloadRepair: (text: string) => void;
  onDiscard: () => void;
}) {
  const [repairText, setRepairText] = useState(inspection.rawText);
  return (
    <EditorFrame
      baseline={baseline}
      documentName="本地修复缓冲"
      modeLabel="Raw Repair"
      readiness={readiness}
    >
      <main className="te-safe-mode" id="main-content" aria-label="Template 编辑工作区">
        <section className="te-safe-card te-raw-card te-import-mode-card">
          <Wrench aria-hidden="true" size={24} />
          <div>
            <p className="te-eyebrow">local bytes only</p>
            <h2>Raw Repair</h2>
            <p>{inspection.message}</p>
            <small>{filename} · {inspection.originalBytes.byteLength} bytes · 未持久化 · 未建立新的 canonical working copy</small>
          </div>
          {repairText === undefined ? (
            <p className="te-import-note">原始字节不是合法 UTF-8，只能原样下载或换文件。</p>
          ) : (
            <label className="te-raw-editor">
              <span>Raw Repair 文本</span>
              <textarea
                value={repairText}
                disabled={disabled}
                onChange={(event) => setRepairText(event.currentTarget.value)}
              />
            </label>
          )}
          <div className="te-import-mode-actions">
            {repairText === undefined ? null : (
              <button
                type="button"
                disabled={disabled}
                onClick={() => onInspect(new TextEncoder().encode(repairText))}
              >
                重新检查修复文本
              </button>
            )}
            <button type="button" onClick={onDownload}><Download aria-hidden="true" size={16} />下载原始导入字节</button>
            {repairText === undefined ? null : (
              <button type="button" onClick={() => onDownloadRepair(repairText)}>
                <Download aria-hidden="true" size={16} />下载当前修复稿
              </button>
            )}
            <ImportFilePicker disabled={disabled} onFile={onInspectFile} label="替换导入文件" />
            <button type="button" className="is-secondary" onClick={onDiscard}>丢弃导入并返回 Structured</button>
          </div>
        </section>
      </main>
    </EditorFrame>
  );
}

function CompatibilityShell({
  session,
  onRetryReadiness,
  download,
}: {
  session: Extract<TemplateEditorSession, { mode: 'compatibility' }>;
  onRetryReadiness?: () => void;
  download: TemplateEditorDownload;
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
          <div className="te-import-mode-actions">
            <button
              type="button"
              onClick={() => downloadBareCanonical(
                download,
                session.baseline.canonicalDesignDsl,
                'renderweave-compatible-current.design.json',
              )}
            >
              <Download aria-hidden="true" size={16} />导出完整 canonical 文件
            </button>
          </div>
          <p className="te-import-note">当前没有已注册的 migration profile；内容保持只读，不提供迁移动作。</p>
        </section>
      </main>
    </EditorFrame>
  );
}

function RawRepairShell({
  session,
  download,
}: {
  session: Extract<TemplateEditorSession, { mode: 'raw-repair' }>;
  download: TemplateEditorDownload;
}) {
  const [rawBuffer, setRawBuffer] = useState(session.rawBuffer);
  return (
    <div className="template-editor-root">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <header className="te-chrome te-raw-chrome">
        <TemplateCatalogLink />
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
          <label className="te-raw-editor">
            <span>Raw Repair 文本</span>
            <textarea value={rawBuffer} onChange={(event) => setRawBuffer(event.currentTarget.value)} />
          </label>
          <div className="te-import-mode-actions">
            <button
              type="button"
              onClick={() => downloadBareCanonical(
                download,
                rawBuffer,
                'renderweave-raw-repair.txt',
                'text/plain',
              )}
            >
              <Download aria-hidden="true" size={16} />下载修复缓冲
            </button>
          </div>
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
        <TemplateCatalogLink />
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
        <TemplateCatalogLink />
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

const defaultTemplateEditorDownload: TemplateEditorDownload = (artifact) => {
  const buffer = new ArrayBuffer(artifact.bytes.byteLength);
  new Uint8Array(buffer).set(artifact.bytes);
  const url = URL.createObjectURL(new Blob([buffer], { type: artifact.mediaType }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = artifact.filename;
  anchor.click();
  URL.revokeObjectURL(url);
};

function downloadBareCanonical(
  download: TemplateEditorDownload,
  canonical: string,
  filename: string,
  mediaType = BARE_DESIGN_DSL_MEDIA_TYPE,
) {
  download({ filename, mediaType, bytes: new TextEncoder().encode(canonical) });
}

function encoderByteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
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
