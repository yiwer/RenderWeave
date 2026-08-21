import {
  AlertTriangle,
  Box,
  Braces,
  CheckCircle2,
  ChevronRight,
  FileJson,
  FolderTree,
  Image,
  LoaderCircle,
  PanelLeft,
  PanelRight,
  RefreshCw,
  ShieldCheck,
  Unplug,
  Wrench,
  type LucideIcon,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

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
  defaultTemplateEditorTransport,
  openTemplateEditor,
  TemplateCurrentDriftError,
  TemplateIntegrityError,
  TemplateRequestError,
  type TemplateEditorTransport,
} from './template-open';
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
}

export function TemplateEditorShell({
  session,
  onRetryReadiness,
}: TemplateEditorShellProps) {
  if (session.mode === 'raw-repair') {
    return <RawRepairShell session={session} />;
  }
  if (session.mode === 'compatibility') {
    return <CompatibilityShell session={session} onRetryReadiness={onRetryReadiness} />;
  }
  return <StructuredShell session={session} onRetryReadiness={onRetryReadiness} />;
}

interface TemplateEditorSurfaceProps {
  templateId: string;
  transport?: TemplateEditorTransport;
}

type SurfaceState =
  | { state: 'loading' }
  | { state: 'open'; session: TemplateEditorSession }
  | { state: 'error'; message: string };

export function TemplateEditorSurface({
  templateId,
  transport = defaultTemplateEditorTransport,
}: TemplateEditorSurfaceProps) {
  const [retryKey, setRetryKey] = useState(0);
  const [surface, setSurface] = useState<SurfaceState>({ state: 'loading' });
  const generation = useRef(0);

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
    />
  );
}

function StructuredShell({
  session,
  onRetryReadiness,
}: {
  session: StructuredEditorSession;
  onRetryReadiness?: () => void;
}) {
  const nodes = useMemo(() => projectStructuredNodes(session), [session]);
  const [entry, setEntry] = useState<EditorEntry>('structure');
  const [selectedNodeId, setSelectedNodeId] = useState(nodes[0]?.nodeId ?? '');
  const [navigatorOpen, setNavigatorOpen] = useState(true);
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const effectiveSelectedNodeId = nodes.some((node) => node.nodeId === selectedNodeId)
    ? selectedNodeId
    : nodes[0]?.nodeId ?? '';
  const selected = nodes.find((node) => node.nodeId === effectiveSelectedNodeId) ?? nodes[0];

  return (
    <EditorFrame
      baseline={session.baseline}
      modeLabel="Structured Editor"
      readiness={session.readiness}
      onRetryReadiness={onRetryReadiness}
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
              <span>浏览器只读投影 · 非权威</span>
            </div>
            <span className="te-mode-chip"><ShieldCheck aria-hidden="true" size={14} />完整性已核验</span>
          </div>
          <CanvasProjection baseline={session.baseline} nodes={nodes} />
        </section>

        {inspectorOpen ? (
          <aside className="te-inspector" aria-label="节点检视器">
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
  modeLabel,
  readiness,
  onRetryReadiness,
  children,
}: {
  baseline: CanonicalTemplateBaseline;
  modeLabel: string;
  readiness: EditorReadiness;
  onRetryReadiness?: () => void;
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
          <h1>{templateDisplayName(baseline)}</h1>
          <span title={baseline.templateId}>{shortIdentity(baseline.templateId)}</span>
        </div>
        <div className="te-baseline-facts" aria-label="Canonical editor baseline">
          <span>{baseline.staticSchema.schemaKey}@{baseline.staticSchema.versionTag}</span>
          <span>revision {baseline.revision}</span>
          <span>{modeLabel}</span>
        </div>
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
      return <AssetSummary designDsl={session.baseline.designDsl} />;
    case 'definitions':
      return <DefinitionSummary designDsl={session.baseline.designDsl} />;
    case 'exchange':
      return <ExchangeSummary baseline={session.baseline} />;
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
      <p className="te-panel-copy">当前只读投影识别 exact v1 closed Node kinds；不创建或改写节点。</p>
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

function ExchangeSummary({ baseline }: { baseline: CanonicalTemplateBaseline }) {
  return (
    <>
      <PanelHeading title="交换" detail="canonical current" />
      <p className="te-panel-copy">E1 只展示已验证 identity；本票不提前提供导入、导出或 migration 动作。</p>
      <dl className="te-fact-list">
        <div><dt>revision</dt><dd>{baseline.revision}</dd></div>
        <div><dt>contentHash</dt><dd title={baseline.contentHash}>{shortHash(baseline.contentHash)}</dd></div>
        <div><dt>canonical UTF-8</dt><dd>{new TextEncoder().encode(baseline.canonicalDesignDsl).byteLength} bytes</dd></div>
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
  baseline,
  nodes,
}: {
  baseline: CanonicalTemplateBaseline;
  nodes: EditorNodeProjection[];
}) {
  const canvas = objectOrNull(baseline.designDsl.designRoot);
  const width = positiveNumber(canvas?.widthMm) ?? 210;
  const height = positiveNumber(canvas?.heightMm) ?? 297;
  return (
    <div className="te-canvas-viewport" tabIndex={0} aria-label="只读画布视口">
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
