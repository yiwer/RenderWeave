import {
  AlertTriangle,
  Barcode,
  Box,
  Boxes,
  CheckCircle2,
  Circle,
  Database,
  Download,
  FileJson,
  FlaskConical,
  FolderTree,
  Hand,
  Grid2X2,
  Image,
  LoaderCircle,
  Minus,
  MousePointer2,
  PanelLeft,
  PanelRight,
  PencilLine,
  Redo2,
  RefreshCw,
  QrCode,
  Save,
  Shapes,
  ShieldCheck,
  Spline,
  SquarePlus,
  Type,
  Undo2,
  Unplug,
  Wrench,
  type LucideIcon,
} from 'lucide-react';
import {
  useCallback,
  useEffect,
  useEffectEvent,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

import {
  createSessionFromBaseline,
  objectOrNull,
  profileIdentity,
  projectStructuredNodes,
  SUPPORTED_NODE_KIND_COUNT,
  templateDisplayName,
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
  executeTemplateEditorCommand,
  type CoreInsertableNodeKind,
  type TemplateEditorCommandIntent,
  type TemplateProjectedGeometry,
} from './template-editor-commands';
import {
  projectTemplateDefiniteLayout,
  type TemplateDefiniteLayoutResult,
  type TemplateStructuralLayoutStates,
} from './template-editor-definite-layout';
import {
  defaultTemplateEditorAssetTransport,
  resolveTemplateAssetRef,
  type TemplateAssetResolution,
  type TemplateEditorAssetKind,
  type TemplateEditorAssetTransport,
} from './template-editor-assets';
import { isCoreTemplateAuthoringParentKind } from './template-editor-node-contract';
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
  type TemplatePreviewAssurance,
  type TemplatePreviewRequest,
  type TemplatePreviewTransport,
} from './template-preview';
import {
  TEMPLATE_NODE_DRAG_MIME,
  TemplateEditorCanvas,
  type TemplateCanvasDropKind,
} from './TemplateEditorCanvas';
import { TemplateEditorAssetPicker } from './TemplateEditorAssetPicker';
import {
  TemplateEditorInspector,
  type TemplateEditorInspectorFocusRequest,
} from './TemplateEditorInspector';
import {
  executeTemplateDataAuthoringCommand,
  type TemplateDataAuthoringContext,
  type TemplateDataAuthoringIntent,
} from './template-editor-data-authoring';
import {
  defaultTemplateStaticSchemaTransport,
  loadExactTemplateStaticSchema,
  loadTemplateStaticSchema,
  type TemplateStaticSchemaTransport,
} from './template-editor-static-schema';
import {
  defaultTemplateEditorCompositionTransport,
  loadTemplateCompositionCatalog,
  loadTemplateCompositionCurrent,
  type TemplateEditorCompositionTransport,
} from './template-editor-composition';
import {
  projectStructuralAuthoring,
  selectTemplateUseInsertionCandidate,
  wholeTemplateContextSelector,
  type TemplateStructuralAuthoringProjection,
  type TemplateStructuralSample,
  type TemplateStructuralSchemaRef,
} from './template-editor-structural-authoring';
import type { StaticSnapshot } from '../schema-studio/lossless-api';
import type { TemplateCatalogEntry, TemplateReadableResponse } from '../../api/generated';
import {
  TemplateEditorDataSources,
  type TemplateStaticSchemaView,
} from './TemplateEditorDataSources';
import { TemplateEditorStructureTree } from './TemplateEditorStructureTree';
import './template-editor.css';

type EditorEntry = 'elements' | 'containers' | 'assets' | 'definitions' | 'structure' | 'exchange';
type LocatedTemplateProblem = Extract<TemplateProblemLocation, { state: 'located' }>;
type InvalidSaveProblem = TemplateInvalidSaveOffer['problems'][number];

const ENTRIES: Array<{ id: EditorEntry; label: string; icon: LucideIcon }> = [
  { id: 'elements', label: '元素', icon: Box },
  { id: 'containers', label: '容器', icon: SquarePlus },
  { id: 'assets', label: '资产', icon: Image },
  { id: 'definitions', label: '数据源', icon: Database },
  { id: 'structure', label: '结构', icon: FolderTree },
];

function recoveryEntryFor(entry: EditorEntry): TemplateRecoveryEntry {
  switch (entry) {
    case 'elements':
    case 'containers':
      return 'nodes';
    case 'assets':
      return 'assets';
    case 'definitions':
      return 'definitions';
    case 'structure':
    case 'exchange':
      return entry;
  }
}

function editorEntryFromRecovery(entry: TemplateRecoveryEntry): EditorEntry {
  switch (entry) {
    case 'nodes': return 'elements';
    case 'assets': return 'assets';
    case 'definitions': return 'definitions';
    case 'structure':
    case 'exchange':
      return entry;
  }
}

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
  assetTransport?: TemplateEditorAssetTransport;
  staticSchemaTransport?: TemplateStaticSchemaTransport;
  compositionTransport?: TemplateEditorCompositionTransport;
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
  assetTransport = defaultTemplateEditorAssetTransport,
  staticSchemaTransport,
  compositionTransport,
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
      assetTransport={assetTransport}
      staticSchemaTransport={staticSchemaTransport}
      compositionTransport={compositionTransport}
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
  assetTransport?: TemplateEditorAssetTransport;
  staticSchemaTransport?: TemplateStaticSchemaTransport;
  compositionTransport?: TemplateEditorCompositionTransport;
}

type SurfaceState =
  | { state: 'loading' }
  | { state: 'open'; session: TemplateEditorSession; saveNotice?: string }
  | { state: 'error'; message: string };

type TemplateCompositionCatalogView =
  | { state: 'loading' }
  | { state: 'ready'; items: readonly TemplateCatalogEntry[] }
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
  assetTransport = defaultTemplateEditorAssetTransport,
  staticSchemaTransport,
  compositionTransport,
}: TemplateEditorSurfaceProps) {
  const [retryKey, setRetryKey] = useState(0);
  const [surface, setSurface] = useState<SurfaceState>({ state: 'loading' });
  const generation = useRef(0);
  const effectiveSaveTransport = saveTransport
    ?? (transport === defaultTemplateEditorTransport ? defaultTemplateSaveTransport : undefined);
  const effectivePreviewTransport = previewTransport
    ?? (transport === defaultTemplateEditorTransport ? defaultTemplatePreviewTransport : undefined);
  const effectiveStaticSchemaTransport = staticSchemaTransport
    ?? (transport === defaultTemplateEditorTransport
      ? defaultTemplateStaticSchemaTransport
      : undefined);
  const effectiveRecoveryStorage = recoveryStorage ?? browserTemplateRecoveryStorage();
  const effectiveCompositionTransport = compositionTransport
    ?? (transport === defaultTemplateEditorTransport
      ? defaultTemplateEditorCompositionTransport
      : undefined);

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
      assetTransport={assetTransport}
      staticSchemaTransport={effectiveStaticSchemaTransport}
      compositionTransport={effectiveCompositionTransport}
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

interface PendingInspectorProblemFocus {
  readonly request: TemplateEditorInspectorFocusRequest;
  readonly problem: InvalidSaveProblem;
  readonly location: LocatedTemplateProblem;
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
  assetTransport,
  staticSchemaTransport,
  compositionTransport,
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
  assetTransport: TemplateEditorAssetTransport;
  staticSchemaTransport?: TemplateStaticSchemaTransport;
  compositionTransport?: TemplateEditorCompositionTransport;
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
  const [nodeAuthoringProblem, setNodeAuthoringProblem] = useState<string | null>(null);
  const [pendingAssetInsertion, setPendingAssetInsertion] = useState<{
    kind: 'text' | 'image';
    parentNodeId: string;
    at?: { xMm: number; yMm: number };
  } | null>(null);
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
  const problemFocusSequence = useRef(0);
  const incomingBaselineKey = baselineIdentity(incomingSession.baseline);
  const [staticSchemaView, setStaticSchemaView] = useState<TemplateStaticSchemaView>({
    state: 'loading',
  });
  const [structuralSchemas, setStructuralSchemas] = useState<readonly StaticSnapshot[]>([]);
  const [compositionCatalog, setCompositionCatalog] = useState<TemplateCompositionCatalogView>({
    state: 'loading',
  });
  const [templateCurrents, setTemplateCurrents] = useState<ReadonlyMap<string, TemplateReadableResponse>>(
    () => new Map(),
  );
  const [structuralSamples, setStructuralSamples] = useState<
    Readonly<Record<string, TemplateStructuralSample>>
  >({});
  const [priorItemContexts, setPriorItemContexts] = useState<
    Readonly<Record<string, TemplateStructuralSchemaRef>>
  >({});
  const compositionCatalogRef = useRef(compositionCatalog);
  const templateCurrentsRef = useRef(templateCurrents);
  const structuralSchemasRef = useRef(structuralSchemas);
  const pendingTemplateCurrents = useRef(new Set<string>());
  const session = useMemo(
    () => baselineIdentity(localSession.baseline) === baselineIdentity(incomingSession.baseline)
      ? updateStructuredReadiness(localSession, incomingSession.readiness)
      : localSession,
    [incomingSession.baseline, incomingSession.readiness, localSession],
  );
  const sessionRef = useRef(session);
  useLayoutEffect(() => {
    sessionRef.current = session;
  }, [session]);
  useEffect(() => {
    preview.syncSession(session);
  }, [preview, session]);
  useEffect(() => {
    if (!staticSchemaTransport) {
      queueMicrotask(() => setStaticSchemaView({
        state: 'error',
        message: '当前宿主未提供 StaticSchema 读取能力。',
      }));
      queueMicrotask(() => setStructuralSchemas([]));
      return undefined;
    }
    const controller = new AbortController();
    queueMicrotask(() => {
      if (!controller.signal.aborted) setStaticSchemaView({ state: 'loading' });
    });
    void loadTemplateStaticSchema(
      incomingSession,
      staticSchemaTransport,
      controller.signal,
    ).then(
      (snapshot) => {
        if (!controller.signal.aborted) {
          setStaticSchemaView({ state: 'ready', snapshot });
          setStructuralSchemas([snapshot]);
        }
      },
      () => {
        if (!controller.signal.aborted) {
          setStaticSchemaView({
            state: 'error',
            message: '永久 StaticSchema 暂不可读取或未通过身份核验。',
          });
          setStructuralSchemas([]);
        }
      },
    );
    return () => controller.abort();
  }, [incomingBaselineKey, incomingSession, staticSchemaTransport]);
  useEffect(() => {
    compositionCatalogRef.current = compositionCatalog;
  }, [compositionCatalog]);
  useEffect(() => {
    templateCurrentsRef.current = templateCurrents;
  }, [templateCurrents]);
  useEffect(() => {
    structuralSchemasRef.current = structuralSchemas;
  }, [structuralSchemas]);
  useEffect(() => {
    if (!compositionTransport) {
      queueMicrotask(() => setCompositionCatalog({
        state: 'error',
        message: '当前宿主未提供 Template catalog/current 读取能力。',
      }));
      return undefined;
    }
    const controller = new AbortController();
    queueMicrotask(() => {
      if (!controller.signal.aborted) setCompositionCatalog({ state: 'loading' });
    });
    void loadTemplateCompositionCatalog(compositionTransport, controller.signal).then(
      (items) => {
        if (!controller.signal.aborted) setCompositionCatalog({ state: 'ready', items });
      },
      () => {
        if (!controller.signal.aborted) setCompositionCatalog({
          state: 'error',
          message: 'Template 目录暂不可读取；请重试后再创建嵌套调用。',
        });
      },
    );
    return () => controller.abort();
  }, [compositionTransport, incomingBaselineKey]);
  const nodes = useMemo(() => projectStructuredNodes(session), [session]);
  const structuralProjection = useMemo<TemplateStructuralAuthoringProjection | undefined>(() => (
    staticSchemaView.state === 'ready'
      ? projectStructuralAuthoring({
        designDsl: session.workingCopy.designDsl,
        staticSchema: staticSchemaView.snapshot,
        staticSchemas: structuralSchemas,
        templateCatalog: compositionCatalog.state === 'ready' ? compositionCatalog.items : [],
        templateCurrents: [...templateCurrents.values()],
        sample: structuralSamples,
        priorItemContexts,
      })
      : undefined
  ), [
    compositionCatalog,
    priorItemContexts,
    session.workingCopy.designDsl,
    staticSchemaView,
    structuralSamples,
    structuralSchemas,
    templateCurrents,
  ]);
  const structuralLayoutStates = useMemo(
    () => projectStructuralLayoutStates(structuralProjection),
    [structuralProjection],
  );
  const layoutProjection = useMemo(() => projectTemplateDefiniteLayout(
    objectOrNull(session.workingCopy.designDsl.designRoot),
    structuralLayoutStates,
  ), [session.workingCopy.designDsl.designRoot, structuralLayoutStates]);
  const [entry, setEntry] = useState<EditorEntry>('structure');
  const [selectedNodeId, setSelectedNodeId] = useState(nodes[0]?.nodeId ?? '');
  const [selectedNodeIds, setSelectedNodeIds] = useState<readonly string[]>(
    nodes[0]?.nodeId ? [nodes[0].nodeId] : [],
  );
  const [navigatorOpen, setNavigatorOpen] = useState(true);
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const [canvasTool, setCanvasTool] = useState<'select' | 'pan'>('select');
  const [announcement, setAnnouncement] = useState('');
  const [inspectorProblemFocus, setInspectorProblemFocus] = useState<
    PendingInspectorProblemFocus | null
  >(null);
  const effectiveSelectedNodeId = nodes.some((node) => node.nodeId === selectedNodeId)
    ? selectedNodeId
    : nodes[0]?.nodeId ?? '';
  const selected = nodes.find((node) => node.nodeId === effectiveSelectedNodeId) ?? nodes[0];
  const selectedLayoutEntry = layoutProjection.state === 'ready'
    ? layoutProjection.entries.find((candidate) => candidate.nodeId === effectiveSelectedNodeId)
    : undefined;
  const selectedProjectedSizeMm = selectedLayoutEntry
    && selectedLayoutEntry.worldRect.width > 0
    && selectedLayoutEntry.worldRect.height > 0
    ? {
      widthMm: selectedLayoutEntry.worldRect.width,
      heightMm: selectedLayoutEntry.worldRect.height,
    }
    : undefined;
  const validSelectedNodeIds = selectedNodeIds.filter((nodeId) => (
    nodes.some((node) => node.nodeId === nodeId)
  ));
  const effectiveSelectedNodeIds = validSelectedNodeIds.length > 0
    ? validSelectedNodeIds
    : effectiveSelectedNodeId ? [effectiveSelectedNodeId] : [];
  const dirty = isCanonicalDirty(session);
  const dependencyStaleMessage = templateDependencyStaleMessage(session);
  const workingName = templateDisplayName(session.workingCopy);
  const candidatePreview = preview.assurance === 'candidate';
  const previewOperationLabel = candidatePreview
    ? '候选预览（NOT_CERTIFIED）'
    : '权威预览';
  const previewImageLabel = candidatePreview
    ? 'NOT_CERTIFIED 候选图片'
    : '权威图片';
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
    entry: recoveryEntryFor(entry),
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

  const selectNode = useCallback((nodeId: string) => {
    setSelectedNodeId(nodeId);
    setSelectedNodeIds([nodeId]);
  }, []);

  const applyRecoveredEditState = (editState: TemplateRecoveryEditState) => {
    setEntry(editorEntryFromRecovery(editState.entry));
    selectNode(editState.selectedNodeId);
    setNavigatorOpen(editState.navigatorOpen);
    setInspectorOpen(editState.inspectorOpen);
  };

  const acceptLocalChange = (next: StructuredEditorSession) => {
    if (localLocked || next === session) return;
    pendingPreviewAfterSave.current = null;
    preview.invalidate(`DesignDSL 本地工作副本已变化；旧${previewImageLabel}已撤下。`);
    if (importView.state === 'candidate') {
      setImportView({ state: 'stale', message: '导入候选已因本地 generation 变化而失效。' });
    }
    setLocalSession(next);
    setSaveView({ state: 'idle' });
  };

  const ensureTemplateCurrent = useCallback(async (
    templateId: string,
  ): Promise<TemplateReadableResponse | null> => {
    const cached = templateCurrentsRef.current.get(templateId);
    if (cached) return cached;
    if (!compositionTransport || pendingTemplateCurrents.current.has(templateId)) return null;
    const catalog = compositionCatalogRef.current;
    const entry = catalog.state === 'ready'
      ? catalog.items.find((candidate) => candidate.templateId === templateId)
      : undefined;
    if (!entry) {
      setNodeAuthoringProblem('所选 Template 已不在当前目录中，请刷新目录后重试。');
      return null;
    }
    pendingTemplateCurrents.current.add(templateId);
    try {
      const current = await loadTemplateCompositionCurrent(entry, compositionTransport);
      setTemplateCurrents((values) => {
        const next = new Map(values).set(templateId, current);
        templateCurrentsRef.current = next;
        return next;
      });
      return current;
    } catch {
      setNodeAuthoringProblem('所选 Template current 已变化或暂不可读取，请刷新目录后重试。');
      return null;
    } finally {
      pendingTemplateCurrents.current.delete(templateId);
    }
  }, [compositionTransport]);

  const ensureStructuralSchema = useCallback(async (
    identity: TemplateStructuralSchemaRef,
  ): Promise<StaticSnapshot | null> => {
    const cached = structuralSchemasRef.current.find((snapshot) => (
      snapshot.schemaKey === identity.schemaKey && snapshot.versionTag === identity.versionTag
    ));
    if (cached) return cached;
    if (!staticSchemaTransport) {
      setNodeAuthoringProblem('当前宿主无法读取循环单项的 exact StaticSchema。');
      return null;
    }
    try {
      const snapshot = await loadExactTemplateStaticSchema(identity, staticSchemaTransport);
      setStructuralSchemas((values) => {
        if (values.some((value) => value.schemaKey === snapshot.schemaKey
          && value.versionTag === snapshot.versionTag)) return values;
        const next = [...values, snapshot];
        structuralSchemasRef.current = next;
        return next;
      });
      return snapshot;
    } catch {
      setNodeAuthoringProblem('循环单项的 exact StaticSchema 暂不可读取，请重试。');
      return null;
    }
  }, [staticSchemaTransport]);

  const undo = () => acceptLocalChange(undoStructuredCommand(session));
  const redo = () => acceptLocalChange(redoStructuredCommand(session));
  const dispatchEditorCommand = (
    intent: TemplateEditorCommandIntent,
    options: { selectAffected?: boolean; openStructure?: boolean } = {},
  ) => {
    if (localLocked) return;
    if (intent.operation === 'configure-structural' && intent.structural.kind === 'repeat') {
      const previous = structuralProjection?.nodeStates[intent.nodeId];
      if (previous?.kind === 'repeat' && previous.itemContext) {
        setPriorItemContexts((values) => ({
          ...values,
          [intent.nodeId]: previous.itemContext as TemplateStructuralSchemaRef,
        }));
      }
    }
    const result = executeTemplateEditorCommand(session, intent);
    if (result.state === 'rejected') {
      setNodeAuthoringProblem(result.message);
      setAnnouncement(`编辑未应用：${result.message}`);
      return;
    }
    setNodeAuthoringProblem(null);
    if (result.state === 'no-op') {
      setAnnouncement(result.message);
      return;
    }
    acceptLocalChange(result.session);
    if (options.selectAffected && result.affectedNodeIds[0]) {
      selectNode(result.affectedNodeIds[0]);
    }
    if (options.openStructure) setEntry('structure');
    setAnnouncement(result.message);
  };
  const dispatchDataAuthoringIntent = (
    intent: TemplateDataAuthoringIntent,
    context: TemplateDataAuthoringContext = {},
  ): boolean => {
    if (localLocked) return false;
    const result = executeTemplateDataAuthoringCommand(session, intent, {
      ...(staticSchemaView.state === 'ready'
        ? {
          staticSchema: staticSchemaView.snapshot,
          staticSchemas: context.staticSchemas,
        }
        : {}),
    });
    if (result.state === 'rejected') {
      setNodeAuthoringProblem(result.message);
      setAnnouncement(`数据编辑未应用：${result.message}`);
      return false;
    }
    setNodeAuthoringProblem(null);
    if (result.state === 'no-op') {
      setAnnouncement(result.message);
      return true;
    }
    acceptLocalChange(result.session);
    setAnnouncement(result.message);
    return true;
  };
  const insertNode = (
    kind: TemplateCanvasDropKind,
    at?: { xMm: number; yMm: number },
    explicitParentNodeId?: string,
  ) => {
    const parentNodeId = explicitParentNodeId
      ?? nearestCoreParentNodeId(nodes, effectiveSelectedNodeId);
    if (!parentNodeId) {
      setNodeAuthoringProblem('当前选择没有可承载新节点的正式父容器。');
      return;
    }
    if (kind === 'text' || kind === 'image') {
      setNodeAuthoringProblem(null);
      setPendingAssetInsertion({ kind, parentNodeId, ...(at ? { at } : {}) });
      return;
    }
    if (kind === 'repeat' || kind === 'conditional' || kind === 'templateUse') {
      if (staticSchemaView.state !== 'ready') {
        setNodeAuthoringProblem('请等待永久 StaticSchema 加载完成后再创建结构节点。');
        return;
      }
      const insertion = projectStructuralInsertion(
        session.workingCopy.designDsl,
        parentNodeId,
        kind,
        staticSchemaView.snapshot,
        structuralSchemas,
        compositionCatalog.state === 'ready' ? compositionCatalog.items : [],
        [...templateCurrents.values()],
      );
      if (kind === 'repeat') {
        const source = insertion.projection.repeatSources[insertion.probeNodeId]?.[0];
        if (!source) {
          setNodeAuthoringProblem('当前词法作用域没有可用于 Repeat 的 exact list 字段或定义。');
          return;
        }
        dispatchEditorCommand({
          operation: 'insert', nodeKind: kind, parentNodeId,
          ...(at ? { at } : {}),
          structural: { kind, items: source.source as never },
        }, { selectAffected: true, openStructure: true });
        return;
      }
      if (kind === 'conditional') {
        const source = insertion.projection.booleanSources[insertion.probeNodeId]?.[0];
        if (!source) {
          setNodeAuthoringProblem('当前词法作用域没有可用于 Conditional 的布尔字段或定义。');
          return;
        }
        dispatchEditorCommand({
          operation: 'insert', nodeKind: kind, parentNodeId,
          ...(at ? { at } : {}),
          structural: { kind, condition: source.source as never },
        }, { selectAffected: true, openStructure: true });
        return;
      }
      const templateUse = insertion.templateUse;
      if (!templateUse) {
        setNodeAuthoringProblem(compositionCatalog.state === 'error'
          ? compositionCatalog.message
          : '当前 exact context 没有 READY Template；请先准备兼容目标。');
        return;
      }
      void ensureTemplateCurrent(templateUse.templateId).then((current) => {
        if (!current) return;
        if (sessionRef.current !== session) {
          setNodeAuthoringProblem('读取 Template current 期间本地草稿已变化；未应用过期操作，请重试。');
          return;
        }
        dispatchEditorCommand({
          operation: 'insert', nodeKind: kind, parentNodeId,
          ...(at ? { at } : {}),
          structural: {
            kind,
            templateId: templateUse.templateId,
            contextSelector: templateUse.contextSelector as never,
            fills: [],
          },
        }, { selectAffected: true, openStructure: true });
      });
      return;
    }
    const nodeKind: CoreInsertableNodeKind = kind === 'shape' ? 'polygon' : kind;
    dispatchEditorCommand(
      {
        operation: 'insert',
        nodeKind,
        parentNodeId,
        ...(at ? { at } : {}),
        ...(kind === 'shape' ? { shapePreset: 'star' as const } : {}),
      },
      { selectAffected: true, openStructure: true },
    );
  };

  const chooseLoopTemplate = (
    repeatNodeId: string,
    templateId: string,
    existingNodeId?: string,
  ) => {
    const repeat = nodes.find((node) => node.nodeId === repeatNodeId)?.value;
    const loopId = repeat && typeof repeat.loopId === 'string' ? repeat.loopId : null;
    const state = structuralProjection?.nodeStates[repeatNodeId];
    if (!loopId || state?.kind !== 'repeat' || !state.itemContext) {
      setNodeAuthoringProblem('请先选择可证明 exact 单项 context 的 Repeat 数据源。');
      return;
    }
    void Promise.all([
      ensureStructuralSchema(state.itemContext),
      ensureTemplateCurrent(templateId),
    ]).then(([schema, current]) => {
      if (!schema || !current) return;
      if (sessionRef.current !== session) {
        setNodeAuthoringProblem('读取循环单项依赖期间本地草稿已变化；未应用过期操作，请重试。');
        return;
      }
      const contextSelector = wholeTemplateContextSelector({ kind: 'loop', loopId }, 'SKIP');
      if (existingNodeId) {
        const existing = nodes.find((node) => node.nodeId === existingNodeId)?.value;
        const previousTemplateId = stringMember(
          objectOrNull(existing?.templateRef),
          'templateId',
        );
        dispatchEditorCommand({
          operation: 'configure-structural',
          nodeId: existingNodeId,
          structural: {
            kind: 'templateUse',
            templateId,
            contextSelector: contextSelector as never,
            fills: previousTemplateId === templateId && Array.isArray(existing?.fills)
              ? existing.fills as never
              : [],
          },
        }, { selectAffected: true, openStructure: true });
        return;
      }
      dispatchEditorCommand({
        operation: 'insert',
        nodeKind: 'templateUse',
        parentNodeId: repeatNodeId,
        structural: {
          kind: 'templateUse', templateId,
          contextSelector: contextSelector as never,
          fills: [],
        },
      }, { selectAffected: true, openStructure: true });
    });
  };

  const selectTemplateTarget = (nodeId: string, templateId: string) => {
    const existing = nodes.find((node) => node.nodeId === nodeId)?.value;
    if (!existing || existing.kind !== 'templateUse') return;
    void ensureTemplateCurrent(templateId).then((current) => {
      if (!current) return;
      if (sessionRef.current !== session) {
        setNodeAuthoringProblem('读取 Template current 期间本地草稿已变化；未应用过期操作，请重试。');
        return;
      }
      const previousTemplateId = stringMember(objectOrNull(existing.templateRef), 'templateId');
      dispatchEditorCommand({
        operation: 'configure-structural', nodeId,
        structural: {
          kind: 'templateUse', templateId,
          contextSelector: existing.contextSelector as never,
          fills: previousTemplateId === templateId && Array.isArray(existing.fills)
            ? existing.fills as never
            : [],
        },
      });
    });
  };

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
    preview.invalidate(`导入内容已替换本地工作副本；旧${previewImageLabel}已撤下。`);
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
    preview.invalidate(`Local recovery 已恢复为本地草稿；旧${previewImageLabel}已撤下。`);
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
    preview.invalidate(`Local recovery 草稿已放弃；旧${previewImageLabel}已撤下。`);
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
        message: `revision ${committed.baseline.revision} 已采用，但 canonical 内容与保存并预览 intent 不一致；未启动${previewOperationLabel}。`,
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
          ? `revision ${committed.baseline.revision} 已保存为 INVALID；未启动${previewOperationLabel}。`
          : `revision ${committed.baseline.revision} 已保存，但 current 尚不能形成 READY snapshot；未启动${previewOperationLabel}。`,
      }, true);
      return invalid
        ? `revision ${committed.baseline.revision} 已保存为 INVALID；未启动${previewOperationLabel}。`
        : `revision ${committed.baseline.revision} 已保存；当前未启动${previewOperationLabel}。`;
    }
    void preview.start(committed, intent.request, { savedFirst: true });
    return `revision ${committed.baseline.revision} 已保存；${previewOperationLabel}已作为独立操作发起。`;
  };

  const abandonPendingPreview = (saveCode: string) => {
    if (!pendingPreviewAfterSave.current) return;
    pendingPreviewAfterSave.current = null;
    preview.reportProblem({
      source: 'client',
      code: 'EDITOR_PREVIEW_SAVE_NOT_COMPLETED',
      message: `Template 保存未完成（${saveCode}）；未启动${previewOperationLabel}。`,
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
          setEntry(editorEntryFromRecovery(loaded.record.editState.entry));
          selectNode(loaded.record.editState.selectedNodeId);
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
  }, [incomingBaselineKey, recoveryNow, recoveryStorage, selectNode]);

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
    if (location.target.kind === 'node' && location.target.focus?.kind === 'binding') {
      setNavigatorOpen(true);
      setEntry('structure');
      setInspectorOpen(true);
      selectNode(location.target.nodeId);
      problemFocusSequence.current += 1;
      setInspectorProblemFocus({
        request: {
          requestId: problemFocusSequence.current,
          nodeId: location.target.nodeId,
          mode: bindingProblemFocusMode(problem.canonicalPointer),
          focus: location.target.focus,
        },
        problem,
        location,
      });
      return;
    }
    setInspectorProblemFocus(null);
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
        selectNode(location.target.nodeId);
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
        if (location.target.focus?.kind === 'definition') {
          const definitionId = location.target.focus.definitionId;
          const matches = [...root.querySelectorAll<HTMLElement>('[data-template-definition-id]')]
            .filter((candidate) => candidate.dataset.templateDefinitionId === definitionId);
          target = matches.length === 1 ? matches[0] : undefined;
          if (target) target.tabIndex = -1;
        } else {
          target = root.querySelector<HTMLElement>('[data-template-editor-location="definitions"]')
            ?? undefined;
        }
      } else {
        const nodeId = location.target.nodeId;
        target = [...root.querySelectorAll<HTMLElement>('[data-template-editor-node-id]')]
          .find((candidate) => candidate.dataset.templateEditorNodeId === nodeId);
      }
      if (target) {
        target.focus({ preventScroll: true });
        target.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
        setAnnouncement(problemLocationAnnouncement(problem, location));
      } else {
        setAnnouncement(`问题 ${problem.code} 的目标当前不可用；问题仍保留在摘要中。`);
      }
    });
  };
  const completeInspectorProblemFocus = (requestId: number, focused: boolean) => {
    if (!inspectorProblemFocus || inspectorProblemFocus.request.requestId !== requestId) return;
    const pending = inspectorProblemFocus;
    setInspectorProblemFocus(null);
    setAnnouncement(focused
      ? problemLocationAnnouncement(pending.problem, pending.location)
      : `问题 ${pending.problem.code} 的目标当前不可用；问题仍保留在摘要中。`);
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

  const handleStructuredShortcut = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.repeat || event.altKey) return;
    const key = event.key.toLowerCase();
    const commandModifier = event.ctrlKey || event.metaKey;
    if (commandModifier && key === 's') {
      event.preventDefault();
      if (!localLocked && dirty && saveTransport) save();
      return;
    }
    if (isTemplateEditorEditableTarget(event.target)) return;
    if (commandModifier && key === 'z') {
      event.preventDefault();
      if (event.shiftKey) redo();
      else undo();
      return;
    }
    if (event.ctrlKey && !event.metaKey && key === 'y') {
      event.preventDefault();
      redo();
      return;
    }
    if (!commandModifier && !event.shiftKey
      && (key === 'delete' || key === 'backspace')
      && isTemplateEditorDeleteShortcutTarget(event.target)) {
      event.preventDefault();
      dispatchEditorCommand({ operation: 'delete', nodeId: effectiveSelectedNodeId });
    }
  };

  return (
    <EditorFrame
      rootRef={editorRootRef}
      onKeyDown={handleStructuredShortcut}
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
          <aside className="te-navigator" aria-label="元素、容器、资产、数据源与结构面板">
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
            <button
              type="button"
              className={`te-exchange-entry${entry === 'exchange' ? ' is-active' : ''}`}
              aria-current={entry === 'exchange' ? 'page' : undefined}
              onClick={() => setEntry('exchange')}
            >
              <FileJson aria-hidden="true" size={15} />
              导入 / 导出
            </button>
            <section className="te-entry-panel">
              <EntryPanel
                entry={entry}
                session={session}
                staticSchema={staticSchemaView}
                referenceTransport={staticSchemaTransport}
                onDataIntent={dispatchDataAuthoringIntent}
                assetTransport={assetTransport}
                dependencyStaleMessage={dependencyStaleMessage}
                nodes={nodes}
                selectedNodeId={effectiveSelectedNodeId}
                onSelectNode={selectNode}
                onRenameNode={(nodeId, displayName) => dispatchEditorCommand({
                  operation: 'rename', nodeId, displayName,
                })}
                onMoveNode={(nodeId, targetNodeId, position) => {
                  const projectedGeometry = projectedTreeMoveGeometry(
                    layoutProjection,
                    nodeId,
                    targetNodeId,
                    position,
                  );
                  dispatchEditorCommand({
                    operation: 'move-tree', nodeId, targetNodeId, position,
                    ...(projectedGeometry ? { projectedGeometry } : {}),
                  });
                }}
                onReorderNode={(nodeId, order) => dispatchEditorCommand({
                  operation: 'reorder', nodeId, order,
                })}
                onDeleteNode={(nodeId) => dispatchEditorCommand({ operation: 'delete', nodeId })}
                nodeAuthoringProblem={nodeAuthoringProblem}
                nodeAuthoringLocked={localLocked}
                onInsertNode={insertNode}
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
            <div className="te-canvas-tool-picker" role="toolbar" aria-label="画布操作模式">
              <button
                type="button"
                aria-pressed={canvasTool === 'select'}
                onClick={() => setCanvasTool('select')}
              >
                <MousePointer2 aria-hidden="true" size={14} />选择 <kbd>V</kbd>
              </button>
              <button
                type="button"
                aria-pressed={canvasTool === 'pan'}
                onClick={() => setCanvasTool('pan')}
              >
                <Hand aria-hidden="true" size={14} />平移 <kbd>H</kbd>
              </button>
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
          <TemplateEditorCanvas
            workingCopy={session.workingCopy}
            nodes={nodes}
            selectedNodeId={effectiveSelectedNodeId}
            selectedNodeIds={effectiveSelectedNodeIds}
            tool={canvasTool}
            disabled={localLocked}
            assetTransport={assetTransport}
            structuralStates={structuralLayoutStates}
            onToolChange={setCanvasTool}
            onSelectNode={selectNode}
            onSelectionChange={(nodeIds, primaryNodeId) => {
              setSelectedNodeIds(nodeIds);
              setSelectedNodeId(primaryNodeId);
            }}
            onGeometryCommit={(nodeId, geometry) => {
              dispatchEditorCommand({
                operation: 'set-geometry',
                nodeId,
                geometry,
              });
            }}
            onDeleteSelection={() => dispatchEditorCommand({
              operation: 'delete', nodeId: effectiveSelectedNodeId,
            })}
            onReorderNode={(nodeId, order) => dispatchEditorCommand({
              operation: 'reorder', nodeId, order,
            })}
            onInsertAt={(nodeKind, xMm, yMm) => {
              const canvasNodeId = nodes.find((node) => node.kind === 'canvas')?.nodeId;
              if (!canvasNodeId) return;
              insertNode(nodeKind, { xMm, yMm }, canvasNodeId);
            }}
          />
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
              previewAssurance={preview.assurance}
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
            <TemplateEditorInspector
              node={selected}
              projectedSizeMm={selectedProjectedSizeMm}
              disabled={localLocked}
              onCommand={dispatchEditorCommand}
              problemFocus={inspectorProblemFocus?.request}
              onProblemFocusResult={completeInspectorProblemFocus}
              designDsl={session.workingCopy.designDsl}
              staticSchema={staticSchemaView}
              staticSchemaTransport={staticSchemaTransport}
              onDataIntent={dispatchDataAuthoringIntent}
              assetTransport={assetTransport}
              dependencyStaleMessage={dependencyStaleMessage}
              structuralProjection={structuralProjection}
              structuralStaticSchemas={structuralSchemas}
              templateCatalog={compositionCatalog.state === 'ready' ? compositionCatalog.items : []}
              onConfigureStructural={(nodeId, structural) => {
                dispatchEditorCommand({ operation: 'configure-structural', nodeId, structural });
              }}
              onStructuralPreviewSample={(nodeId, sample) => {
                setStructuralSamples((values) => ({ ...values, [nodeId]: sample }));
              }}
              onCreateLoopTemplate={chooseLoopTemplate}
              onSelectTemplateTarget={selectTemplateTarget}
              onEnsureTemplateCurrent={ensureTemplateCurrent}
            />
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
              aria-controls={preview.panelOpen ? 'template-authoritative-preview-panel' : undefined}
              aria-label={candidatePreview
                ? preview.panelOpen
                  ? '关闭候选预览（NOT_CERTIFIED）'
                  : '打开候选预览（NOT_CERTIFIED）'
                : preview.panelOpen ? '关闭权威预览' : '打开权威预览'}
              onClick={preview.panelOpen ? preview.close : preview.open}
            >
              {candidatePreview
                ? <FlaskConical aria-hidden="true" size={15} />
                : <ShieldCheck aria-hidden="true" size={15} />}
              {candidatePreview ? '候选预览 · NOT_CERTIFIED' : '权威预览'}
            </button>
          ) : null}
          <span>{nodes.length} 个 authored 节点</span>
        </div>
      </main>
      <TemplateEditorAssetPicker
        open={pendingAssetInsertion !== null}
        expectedKind={pendingAssetInsertion?.kind === 'image' ? 'IMAGE' : 'FONT'}
        transport={assetTransport}
        onOpenChange={(open) => {
          if (!open) setPendingAssetInsertion(null);
        }}
        onSelect={(selection) => {
          const pending = pendingAssetInsertion;
          if (!pending) return;
          dispatchEditorCommand({
            operation: 'insert',
            nodeKind: pending.kind,
            parentNodeId: pending.parentNodeId,
            assetId: selection.ref.assetId,
            ...(pending.at ? { at: pending.at } : {}),
          }, { selectAffected: true, openStructure: true });
          setPendingAssetInsertion(null);
        }}
      />
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
  onKeyDown,
  baseline,
  documentName,
  modeLabel,
  readiness,
  onRetryReadiness,
  headerTools,
  children,
}: {
  rootRef?: React.Ref<HTMLDivElement>;
  onKeyDown?: React.KeyboardEventHandler<HTMLDivElement>;
  baseline: CanonicalTemplateBaseline;
  documentName?: string;
  modeLabel: string;
  readiness: EditorReadiness;
  onRetryReadiness?: () => void;
  headerTools?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="template-editor-root" ref={rootRef} onKeyDown={onKeyDown}>
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
      <button
        type="button"
        onClick={onUndo}
        disabled={localLocked || !canUndo}
        aria-label="撤销本地编辑"
        aria-keyshortcuts="Control+Z Meta+Z"
      >
        <Undo2 aria-hidden="true" size={16} />
      </button>
      <button
        type="button"
        onClick={onRedo}
        disabled={localLocked || !canRedo}
        aria-label="重做本地编辑"
        aria-keyshortcuts="Control+Y Control+Shift+Z Meta+Shift+Z"
      >
        <Redo2 aria-hidden="true" size={16} />
      </button>
      {canSave || localLocked ? (
        <button
          type="button"
          className="button primary-button te-save-button"
          onClick={onSave}
          disabled={localLocked}
          aria-label="保存 canonical 本地草稿"
          aria-keyshortcuts="Control+S Meta+S"
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
  previewAssurance,
  disabled,
  onSessionChange,
}: {
  session: StructuredEditorSession;
  guard: ReturnType<typeof authoritativePreviewGuard>;
  previewAssurance: TemplatePreviewAssurance;
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
  const candidatePreview = previewAssurance === 'candidate';
  const guardMessage = guard.state === 'eligible'
    ? `当前 current 满足${candidatePreview ? '候选预览' : '权威预览'}前置条件 · generation ${guard.generation}`
    : `${candidatePreview ? guard.message.replace('权威预览', '候选预览') : guard.message} · generation ${guard.generation}`;

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
        <strong>{candidatePreview ? '候选预览条件 · NOT_CERTIFIED' : '权威预览条件'}</strong>
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
                  aria-label={`${problem.code}：${problemLocationButtonLabel(problem, location)}`}
                  onClick={() => onLocate(problem, location)}
                >
                  {problemLocationButtonLabel(problem, location)}
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

function problemLocationButtonLabel(
  problem: InvalidSaveProblem,
  location: LocatedTemplateProblem,
): string {
  switch (location.target.kind) {
    case 'template-display-name':
      return '定位到 Template 名称';
    case 'definitions':
      return location.target.focus?.kind === 'definition'
        ? `定位到定义“${location.target.label}”`
        : '定位到数据源面板';
    case 'node':
      if (location.target.focus?.kind === 'binding') {
        const focusLabel = bindingProblemFocusMode(problem.canonicalPointer) === 'property'
          ? '属性'
          : '绑定';
        return `定位到节点“${location.target.label}”的${focusLabel}“${location.target.focus.propertyPath}”`;
      }
      return `定位到节点“${location.target.label}”`;
  }
}

function problemLocationAnnouncement(
  problem: InvalidSaveProblem,
  location: LocatedTemplateProblem,
): string {
  const code = problem.code;
  switch (location.target.kind) {
    case 'template-display-name':
      return `已定位问题 ${code} 到 Template 名称。`;
    case 'definitions':
      return location.target.focus?.kind === 'definition'
        ? `已定位问题 ${code} 到定义“${location.target.label}”。`
        : `已定位问题 ${code} 到数据源面板。`;
    case 'node':
      if (location.target.focus?.kind === 'binding') {
        const focusLabel = bindingProblemFocusMode(problem.canonicalPointer) === 'property'
          ? '属性'
          : '绑定';
        return `已定位问题 ${code} 到节点“${location.target.label}”的${focusLabel}“${location.target.focus.propertyPath}”。`;
      }
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
  staticSchema,
  referenceTransport,
  onDataIntent,
  assetTransport,
  dependencyStaleMessage,
  nodes,
  selectedNodeId,
  onSelectNode,
  onRenameNode,
  onMoveNode,
  onReorderNode,
  onDeleteNode,
  nodeAuthoringProblem,
  nodeAuthoringLocked,
  onInsertNode,
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
  staticSchema: TemplateStaticSchemaView;
  referenceTransport?: TemplateStaticSchemaTransport;
  onDataIntent: (
    intent: TemplateDataAuthoringIntent,
    context?: TemplateDataAuthoringContext,
  ) => boolean;
  assetTransport: TemplateEditorAssetTransport;
  dependencyStaleMessage?: string;
  nodes: EditorNodeProjection[];
  selectedNodeId: string;
  onSelectNode: (nodeId: string) => void;
  onRenameNode: (nodeId: string, displayName: string) => void;
  onMoveNode: (nodeId: string, targetNodeId: string, position: 'before' | 'into' | 'after') => void;
  onReorderNode: (nodeId: string, operation: 'front' | 'forward' | 'backward' | 'back') => void;
  onDeleteNode: (nodeId: string) => void;
  nodeAuthoringProblem: string | null;
  nodeAuthoringLocked: boolean;
  onInsertNode: (kind: TemplateCanvasDropKind) => void;
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
        <TemplateEditorStructureTree
          nodes={nodes}
          selectedNodeId={selectedNodeId}
          disabled={nodeAuthoringLocked}
          onSelectNode={onSelectNode}
          onRenameNode={onRenameNode}
          onMoveNode={onMoveNode}
          onReorderNode={onReorderNode}
          onDeleteNode={onDeleteNode}
        />
      );
    case 'elements':
      return (
        <NodeCatalogSummary
          nodes={nodes}
          problem={nodeAuthoringProblem}
          disabled={nodeAuthoringLocked}
          onInsert={onInsertNode}
        />
      );
    case 'containers':
      return (
        <ContainerCatalogSummary
          disabled={nodeAuthoringLocked}
          problem={nodeAuthoringProblem}
          onInsert={onInsertNode}
        />
      );
    case 'assets':
      return <AssetSummary
        designDsl={session.workingCopy.designDsl}
        transport={assetTransport}
        dependencyStaleMessage={dependencyStaleMessage}
      />;
    case 'definitions':
      return <TemplateEditorDataSources
        designDsl={session.workingCopy.designDsl}
        staticSchema={staticSchema}
        referenceTransport={referenceTransport}
        disabled={nodeAuthoringLocked}
        onIntent={onDataIntent}
      />;
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

function NodeCatalogSummary({
  nodes,
  problem,
  disabled,
  onInsert,
}: {
  nodes: EditorNodeProjection[];
  problem: string | null;
  disabled: boolean;
  onInsert: (kind: TemplateCanvasDropKind) => void;
}) {
  const counts = new Map<string, number>();
  for (const node of nodes) counts.set(node.kind, (counts.get(node.kind) ?? 0) + 1);
  return (
    <>
      <PanelHeading title="元素" detail="10 类视觉元素" />
      <div className="te-node-library" aria-label="可添加元素">
        {ELEMENT_CATALOG.map(({ kind, label, detail, icon: Icon }) => (
          <button
            key={kind}
            type="button"
            aria-label={`添加${label}`}
            disabled={disabled}
            draggable={!disabled}
            onDragStart={(event) => {
              event.dataTransfer.effectAllowed = 'copy';
              event.dataTransfer.setData(TEMPLATE_NODE_DRAG_MIME, kind);
            }}
            onClick={() => onInsert(kind)}
          >
            <span className="te-node-library-icon"><Icon aria-hidden="true" size={18} /></span>
            <span className="te-node-library-copy">
              <strong>{label}</strong>
              <small>{detail}</small>
            </span>
            <span className="te-node-library-action">
              {kind === 'text' || kind === 'image' ? '选择资产' : '添加'}
            </span>
          </button>
        ))}
      </div>
      {problem ? <p className="te-node-authoring-alert" role="alert">{problem}</p> : null}
      <p className="te-node-contract-note">
        Shape 是创建预设并保存为 Polygon；当前客户端识别 {SUPPORTED_NODE_KIND_COUNT} 种 v1 closed wire。
      </p>
      <ul className="te-summary-list">
        {[...counts.entries()].map(([kind, count]) => (
          <li key={kind}><span>{kind}</span><strong>{count}</strong></li>
        ))}
      </ul>
    </>
  );
}

function ContainerCatalogSummary({
  problem,
  disabled,
  onInsert,
}: {
  problem: string | null;
  disabled: boolean;
  onInsert: (kind: TemplateCanvasDropKind) => void;
}) {
  return (
    <>
      <PanelHeading title="容器" detail="7 个正式容器 / 结构节点" />
      <p className="te-panel-copy">布局容器保存几何；Repeat、Conditional 与 TemplateUse 保存结构语义。</p>
      <div className="te-node-library" aria-label="可添加容器">
        <button
          type="button"
          aria-label="添加自由分组"
          disabled={disabled}
          draggable={!disabled}
          onDragStart={(event) => {
            event.dataTransfer.effectAllowed = 'copy';
            event.dataTransfer.setData(TEMPLATE_NODE_DRAG_MIME, 'group');
          }}
          onClick={() => onInsert('group')}
        >
          <span className="te-node-library-icon"><Boxes aria-hidden="true" size={18} /></span>
          <span className="te-node-library-copy"><strong>自由分组</strong><small>内容包围 · 绝对子级</small></span>
          <span className="te-node-library-action">添加</span>
        </button>
        <button
          type="button"
          aria-label="添加循环容器"
          disabled={disabled}
          onClick={() => onInsert('repeat')}
        >
          <span className="te-node-library-icon"><RefreshCw aria-hidden="true" size={18} /></span>
          <span className="te-node-library-copy"><strong>循环容器</strong><small>exact list · authored once</small></span>
          <span className="te-node-library-action">配置并添加</span>
        </button>
        <button
          type="button"
          aria-label="添加条件容器"
          disabled={disabled}
          onClick={() => onInsert('conditional')}
        >
          <span className="te-node-library-icon"><Wrench aria-hidden="true" size={18} /></span>
          <span className="te-node-library-copy"><strong>条件容器</strong><small>TRUE branch · FALSE prune</small></span>
          <span className="te-node-library-action">配置并添加</span>
        </button>
        <button
          type="button"
          aria-label="添加嵌套模板"
          disabled={disabled}
          onClick={() => onInsert('templateUse')}
        >
          <span className="te-node-library-icon"><Boxes aria-hidden="true" size={18} /></span>
          <span className="te-node-library-copy"><strong>嵌套模板</strong><small>READY exact-schema target</small></span>
          <span className="te-node-library-action">配置并添加</span>
        </button>
        <button
          type="button"
          aria-label="添加框架"
          disabled={disabled}
          draggable={!disabled}
          onDragStart={(event) => {
            event.dataTransfer.effectAllowed = 'copy';
            event.dataTransfer.setData(TEMPLATE_NODE_DRAG_MIME, 'frame');
          }}
          onClick={() => onInsert('frame')}
        >
          <span className="te-node-library-icon"><Box aria-hidden="true" size={18} /></span>
          <span className="te-node-library-copy"><strong>框架</strong><small>固定边界 · 绝对子级</small></span>
          <span className="te-node-library-action">添加</span>
        </button>
        <button
          type="button"
          aria-label="添加堆叠容器"
          disabled={disabled}
          draggable={!disabled}
          onDragStart={(event) => {
            event.dataTransfer.effectAllowed = 'copy';
            event.dataTransfer.setData(TEMPLATE_NODE_DRAG_MIME, 'stack');
          }}
          onClick={() => onInsert('stack')}
        >
          <span className="te-node-library-icon"><SquarePlus aria-hidden="true" size={18} /></span>
          <span className="te-node-library-copy"><strong>堆叠容器</strong><small>横向 / 纵向由布局属性决定</small></span>
          <span className="te-node-library-action">添加</span>
        </button>
        <button
          type="button"
          aria-label="添加网格容器"
          disabled={disabled}
          draggable={!disabled}
          onDragStart={(event) => {
            event.dataTransfer.effectAllowed = 'copy';
            event.dataTransfer.setData(TEMPLATE_NODE_DRAG_MIME, 'grid');
          }}
          onClick={() => onInsert('grid')}
        >
          <span className="te-node-library-icon"><Grid2X2 aria-hidden="true" size={18} /></span>
          <span className="te-node-library-copy"><strong>网格容器</strong><small>固定 / 自动 / 比例轨道</small></span>
          <span className="te-node-library-action">添加</span>
        </button>
      </div>
      {problem ? <p className="te-node-authoring-alert" role="alert">{problem}</p> : null}
    </>
  );
}

const ELEMENT_CATALOG: ReadonlyArray<{
  kind: TemplateCanvasDropKind;
  label: string;
  detail: string;
  icon: LucideIcon;
}> = [
  { kind: 'text', label: '文本', detail: '单 Run · 真实字体 Asset', icon: Type },
  { kind: 'image', label: '图片', detail: '真实图片 Asset · 自适应', icon: Image },
  { kind: 'rect', label: '矩形', detail: '实色填充 · 可设圆角', icon: SquarePlus },
  { kind: 'ellipse', label: '椭圆', detail: '随边界自由缩放', icon: Circle },
  { kind: 'line', label: '直线', detail: '端点与描边', icon: Minus },
  { kind: 'shape', label: '形状', detail: '星形预设 · 保存为 Polygon', icon: Shapes },
  { kind: 'polygon', label: '多边形', detail: '闭合点序列', icon: Shapes },
  { kind: 'polyline', label: '折线', detail: '开放点序列', icon: Spline },
  { kind: 'path', label: '路径', detail: '正式 commands[]', icon: Spline },
  { kind: 'qrCode', label: '二维码', detail: '本地草稿预览', icon: QrCode },
  { kind: 'barcode', label: '条形码', detail: '本地草稿预览', icon: Barcode },
];

function projectStructuralLayoutStates(
  projection: TemplateStructuralAuthoringProjection | undefined,
): TemplateStructuralLayoutStates {
  if (!projection) return {};
  return Object.fromEntries(Object.entries(projection.nodeStates).map(([nodeId, state]) => {
    if (state.kind === 'repeat') {
      if (state.authoringState === 'INVALID') {
        return [nodeId, { kind: 'repeat', outcome: 'SOURCE_ERROR' as const }];
      }
      if (state.runtime.state === 'VALUES') {
        return [nodeId, {
          kind: 'repeat', outcome: 'VALUES' as const, count: state.runtime.occurrences.length,
        }];
      }
      return [nodeId, {
        kind: 'repeat',
        outcome: state.runtime.state === 'UNSAMPLED' ? 'EMPTY' : state.runtime.state,
      }];
    }
    if (state.kind === 'conditional') {
      return [nodeId, {
        kind: 'conditional',
        outcome: state.authoringState === 'INVALID'
          ? 'SOURCE_ERROR'
          : state.runtime.state === 'UNSAMPLED' ? 'TRUE' : state.runtime.state,
      }];
    }
    return [nodeId, {
      kind: 'templateUse',
      outcome: state.authoringState === 'READY'
        ? 'READY'
        : state.authoringState === 'NEEDS_REPAIR' ? 'NEEDS_REPAIR' : 'SOURCE_ERROR',
      ...(state.sourceCanvasSizeMm ? { sourceCanvasSizeMm: state.sourceCanvasSizeMm } : {}),
    }];
  })) as TemplateStructuralLayoutStates;
}

function projectStructuralInsertion(
  designDsl: Readonly<Record<string, unknown>>,
  parentNodeId: string,
  kind: 'repeat' | 'conditional' | 'templateUse',
  staticSchema: StaticSnapshot,
  staticSchemas: readonly StaticSnapshot[],
  templateCatalog: readonly TemplateCatalogEntry[],
  templateCurrents: readonly TemplateReadableResponse[],
): {
  probeNodeId: string;
  projection: TemplateStructuralAuthoringProjection;
  templateUse?: Readonly<{
    templateId: string;
    contextSelector: Readonly<Record<string, unknown>>;
  }>;
} {
  const probeNodeId = '00000000-0000-4000-8000-000000000000';
  const root = objectOrNull(designDsl.designRoot);
  const loopId = root ? lexicalLoopForParent(root, parentNodeId) : null;
  const contextSelector = wholeTemplateContextSelector(
    loopId ? { kind: 'loop', loopId } : 'invocation',
    loopId ? 'SKIP' : 'ERROR',
  );
  const common = {
    nodeId: probeNodeId,
    kind,
    bindings: [],
    placement: { type: 'ABSOLUTE', xMm: 0, yMm: 0, widthMode: 'FIXED', widthMm: 1, heightMode: 'FIXED', heightMm: 1 },
  };
  const probe = kind === 'repeat'
    ? {
      ...common, loopId: '00000000-0000-4000-8000-000000000001',
      items: { kind: 'definition', definitionId: '00000000-0000-4000-8000-000000000002' },
      absentPolicy: 'EMPTY',
      itemLayout: { kind: 'STACK', direction: 'COLUMN' },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN' }, children: [],
    }
    : kind === 'conditional'
      ? {
        ...common,
        condition: { kind: 'definition', definitionId: '00000000-0000-4000-8000-000000000002' },
        absentPolicy: 'FALSE', children: [],
      }
      : {
        ...common,
        useId: '00000000-0000-4000-8000-000000000003',
        templateRef: { templateId: '00000000-0000-4000-8000-000000000004' },
        contextSelector, fills: [],
      };
  const projectedRoot = root ? appendStructuralProbe(root, parentNodeId, probe) : root;
  const projection = projectStructuralAuthoring({
    designDsl: { ...designDsl, designRoot: projectedRoot ?? designDsl.designRoot },
    staticSchema,
    staticSchemas,
    templateCatalog,
    templateCurrents,
  });
  const templateUse = kind === 'templateUse'
    ? selectTemplateUseInsertionCandidate(projection, probeNodeId, templateCatalog)
    : null;
  return {
    probeNodeId,
    projection,
    ...(templateUse ? { templateUse } : {}),
  };
}

function appendStructuralProbe(
  node: Readonly<Record<string, unknown>>,
  parentNodeId: string,
  probe: Readonly<Record<string, unknown>>,
): Readonly<Record<string, unknown>> {
  if (node.nodeId === parentNodeId && Array.isArray(node.children)) {
    return { ...node, children: [...node.children, probe] };
  }
  if (!Array.isArray(node.children)) return node;
  return {
    ...node,
    children: node.children.map((value) => {
      const child = objectOrNull(value);
      return child ? appendStructuralProbe(child, parentNodeId, probe) : value;
    }),
  };
}

function lexicalLoopForParent(
  root: Readonly<Record<string, unknown>>,
  parentNodeId: string,
): string | null {
  const visit = (node: Readonly<Record<string, unknown>>, loops: readonly string[]): string | null => {
    const childLoops = node.kind === 'repeat' && typeof node.loopId === 'string'
      ? [...loops, node.loopId]
      : loops;
    if (node.nodeId === parentNodeId) return childLoops.at(-1) ?? null;
    if (!Array.isArray(node.children)) return null;
    for (const value of node.children) {
      const child = objectOrNull(value);
      if (!child) continue;
      const found = visit(child, childLoops);
      if (found) return found;
    }
    return null;
  };
  return visit(root, []);
}

function stringMember(value: Readonly<Record<string, unknown>> | null, member: string): string | null {
  return value && typeof value[member] === 'string' ? value[member] : null;
}

function templateDependencyStaleMessage(
  session: StructuredEditorSession,
): string | undefined {
  if (session.baseline.persistedReadiness !== 'STALE') return undefined;
  switch (session.readiness.state) {
    case 'checking':
      return 'Template 打开时依赖快照 STALE；权威重检中';
    case 'unavailable':
      return 'Template 打开时依赖快照 STALE；权威重检暂不可用';
    case 'checked':
      return `Template 打开时依赖快照 STALE；本次权威重检 ${session.readiness.value}`;
  }
}

function bindingProblemFocusMode(canonicalPointer: string): 'binding' | 'property' {
  const segments = canonicalPointer.split('/');
  const bindingsIndex = segments.lastIndexOf('bindings');
  return bindingsIndex >= 0 && segments[bindingsIndex + 2] === 'targetPropertyRef'
    ? 'property'
    : 'binding';
}

function AssetSummary({
  designDsl,
  transport,
  dependencyStaleMessage,
}: {
  designDsl: Record<string, unknown>;
  transport: TemplateEditorAssetTransport;
  dependencyStaleMessage?: string;
}) {
  const assets = authoredAssetReferences(designDsl);
  return (
    <>
      <PanelHeading title="资产" detail={`${assets.length} 个已引用资产`} />
      {assets.length === 0 ? (
        <p className="te-empty-state">当前 DesignDSL 没有 imageRef/fontRef 资产引用。</p>
      ) : (
        <ul className="te-summary-list">
          {assets.map((asset) => (
            <AssetSummaryItem
              key={`${asset.expectedKind}:${asset.assetId}`}
              reference={asset}
              transport={transport}
              dependencyStaleMessage={dependencyStaleMessage}
            />
          ))}
        </ul>
      )}
    </>
  );
}

function AssetSummaryItem({
  reference,
  transport,
  dependencyStaleMessage,
}: {
  reference: AuthoredAssetReference;
  transport: TemplateEditorAssetTransport;
  dependencyStaleMessage?: string;
}) {
  const [resolution, setResolution] = useState<TemplateAssetResolution | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    queueMicrotask(() => {
      if (!controller.signal.aborted) setResolution(null);
    });
    void resolveTemplateAssetRef(
      { assetId: reference.assetId },
      reference.expectedKind,
      transport,
      controller.signal,
    ).then(
      (next) => {
        if (!controller.signal.aborted) setResolution(next);
      },
      () => {
        if (!controller.signal.aborted) {
          setResolution({
            state: 'unavailable',
            ref: { assetId: reference.assetId },
            expectedKind: reference.expectedKind,
            code: 'ASSET_REQUEST_UNAVAILABLE',
          });
        }
      },
    );
    return () => controller.abort();
  }, [reference.assetId, reference.expectedKind, transport]);
  const kindLabel = reference.expectedKind === 'FONT' ? '字体' : '图片';
  return (
    <li>
      <span>{resolution?.state === 'active' ? resolution.asset.displayName : `${kindLabel} Asset`}</span>
      <small>{assetSummaryState(resolution, dependencyStaleMessage)}</small>
      <code title={reference.assetId}>{shortIdentity(reference.assetId)}</code>
    </li>
  );
}

function assetSummaryState(
  resolution: TemplateAssetResolution | null,
  dependencyStaleMessage?: string,
): string {
  if (!resolution) return '核验中';
  switch (resolution.state) {
    case 'active': return dependencyStaleMessage
      ? `当前 Asset ACTIVE · ${dependencyStaleMessage}`
      : 'ACTIVE';
    case 'missing': return '不存在 · 引用已保留';
    case 'deleted': return 'DELETED · 引用已保留';
    case 'kind-mismatch': return '类型不匹配 · 引用已保留';
    case 'unavailable': return '暂不可核验 · 引用已保留';
  }
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

function isTemplateEditorEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  return target.closest([
    'input',
    'textarea',
    'select',
    '[contenteditable]:not([contenteditable="false"])',
  ].join(',')) !== null;
}

function isTemplateEditorDeleteShortcutTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  return target.matches('[data-template-canvas-viewport], [role="tree"], [role="treeitem"]');
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

interface AuthoredAssetReference {
  readonly assetId: string;
  readonly expectedKind: TemplateEditorAssetKind;
}

function authoredAssetReferences(value: unknown): AuthoredAssetReference[] {
  const references = new Map<string, AuthoredAssetReference>();
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
      const expectedKind = parentKey === 'imageRef' ? 'IMAGE' : 'FONT';
      references.set(`${expectedKind}:${object.assetId}`, { assetId: object.assetId, expectedKind });
    }
    Object.entries(object).forEach(([key, child]) => visit(child, key));
  };
  visit(value);
  return [...references.values()].sort((left, right) => (
    left.expectedKind.localeCompare(right.expectedKind)
      || left.assetId.localeCompare(right.assetId)
  ));
}

function projectedTreeMoveGeometry(
  layout: TemplateDefiniteLayoutResult,
  nodeId: string,
  targetNodeId: string,
  position: 'before' | 'into' | 'after',
): TemplateProjectedGeometry | null {
  if (layout.state !== 'ready') return null;
  const byId = new Map(layout.entries.map((entry) => [entry.nodeId, entry]));
  const source = byId.get(nodeId);
  const target = byId.get(targetNodeId);
  if (!source || !target) return null;
  const destinationParentId = position === 'into' ? target.nodeId : target.parentNodeId;
  if (!destinationParentId) return null;
  const destinationParent = byId.get(destinationParentId);
  const destinationContent = destinationParent?.worldContentRect;
  if (!destinationContent) return null;
  return {
    xMm: source.worldRect.x - destinationContent.x,
    yMm: source.worldRect.y - destinationContent.y,
    widthMm: source.worldRect.width,
    heightMm: source.worldRect.height,
  };
}

function nearestCoreParentNodeId(
  nodes: readonly EditorNodeProjection[],
  selectedNodeId: string,
): string | null {
  const selectedIndex = nodes.findIndex((node) => node.nodeId === selectedNodeId);
  if (selectedIndex < 0) return nodes.find((node) => node.kind === 'canvas')?.nodeId ?? null;
  const selected = nodes[selectedIndex];
  if (selected && isCoreTemplateAuthoringParentKind(selected.kind)) return selected.nodeId;
  let maximumDepth = selected?.depth ?? Number.POSITIVE_INFINITY;
  for (let index = selectedIndex - 1; index >= 0; index -= 1) {
    const candidate = nodes[index];
    if (!candidate || candidate.depth >= maximumDepth) continue;
    maximumDepth = candidate.depth;
    if (isCoreTemplateAuthoringParentKind(candidate.kind)) return candidate.nodeId;
  }
  return null;
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
