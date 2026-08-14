/**
 * PROTOTYPE — throwaway. 模板设计器三变体共享的部件。
 * 布局不共享(见 VariantA/B/C);此处只共享「内容块」:树、画板、检查器、资产、
 * 定义、数据、预览、问题、chrome。状态模型见 ./model.ts。
 */
import {
  AlertTriangle,
  ArrowLeft,
  ArrowLeftRight,
  Braces,
  Check,
  ChevronRight,
  Copy,
  Database,
  Download,
  EyeOff,
  FileClock,
  FileJson,
  FolderInput,
  History,
  Image,
  Info,
  Layers,
  Link2,
  Lock,
  Play,
  Plus,
  RefreshCw,
  Redo2,
  Save,
  Search,
  Trash2,
  Type,
  Upload,
  Undo2,
  WandSparkles,
  X,
} from 'lucide-react';
import { useId, useState, type Dispatch, type ReactNode } from 'react';

import { SelectField } from '../../components/SelectField';

import { kindIcons } from './kind-icons';

import {
  assetIds,
  assets,
  childTemplateIds,
  customValuesSample,
  definitionIds,
  definitions,
  draftBoxes,
  findNode,
  loopIds,
  nodeCatalog,
  nodeGroupLabels,
  nodeIds,
  problemsFor,
  rootDocumentSample,
  schemaFields,
  templateMeta,
  type DesignerAction,
  type DesignerAsset,
  type DesignerNode,
  type DesignerProblem,
  type DesignerState,
  type InspectorProp,
  type LeftTab,
  type NodeBinding,
  type NodeGroup,
  scenarios,
  useIds,
} from './model';

export interface PartProps {
  state: DesignerState;
  dispatch: Dispatch<DesignerAction>;
  onRunPreview: () => void;
  onCancelPreview: () => void;
}

const leftTabs: { key: LeftTab; label: string; icon: ReactNode }[] = [
  { key: 'library', label: '节点库', icon: <Plus aria-hidden="true" size={16} /> },
  { key: 'tree', label: '结构树', icon: <Layers aria-hidden="true" size={16} /> },
  { key: 'assets', label: '资产', icon: <Image aria-hidden="true" size={16} /> },
  { key: 'definitions', label: '定义', icon: <Braces aria-hidden="true" size={16} /> },
  { key: 'data', label: '数据', icon: <Database aria-hidden="true" size={16} /> },
  { key: 'exchange', label: '导入导出', icon: <ArrowLeftRight aria-hidden="true" size={16} /> },
];

const newCanonicalUuid = () => crypto.randomUUID().toLowerCase();

export function StatusChip({ state }: { state: DesignerState }) {
  if (state.templateStatus === 'INVALID') {
    return (
      <span className="status-chip status-warning" title="可继续编辑，但不可权威预览或渲染">
        <AlertTriangle aria-hidden="true" size={13} />
        INVALID
      </span>
    );
  }
  if (state.dirty) {
    return (
      <span className="status-chip status-dirty">
        <span aria-hidden="true" className="rwtd-status-dot" />
        未保存
      </span>
    );
  }
  return (
    <span className="status-chip">
      <Check aria-hidden="true" size={13} />
      已保存
    </span>
  );
}

export function TdChrome({ state, dispatch, onRunPreview, layoutName }: PartProps & { layoutName: string }) {
  return (
    <header className="product-chrome td-chrome rwtd-topbar">
      <div className="rwtd-brand-block">
        <button
          type="button"
          className="rwtd-icon-button"
          aria-label="返回模板列表"
          title="返回模板列表"
          onClick={() => dispatch({ type: 'set-notice', notice: '原型：返回模板列表' })}
        >
          <ArrowLeft aria-hidden="true" size={16} />
        </button>
        <div className="product-mark" aria-label="RenderWeave">
          <span className="weave-mark" aria-hidden="true">RW</span>
          <span>RenderWeave</span>
        </div>
      </div>
      <div className="chrome-context td-chrome-context">
        <div className="rwtd-document-title">
          <strong className="td-chrome-name">{templateMeta.name}</strong>
          <span>{layoutName} · 90 × 54 mm</span>
        </div>
        <div className="rwtd-history-actions" role="group" aria-label="编辑历史">
          <button
            type="button"
            className="rwtd-icon-button"
            aria-label="撤销"
            title="撤销 · Ctrl+Z"
            onClick={() => dispatch({ type: 'set-notice', notice: '原型：撤销上一步编辑' })}
          >
            <Undo2 aria-hidden="true" size={15} />
          </button>
          <button
            type="button"
            className="rwtd-icon-button"
            aria-label="重做"
            title="重做 · Ctrl+Shift+Z"
            onClick={() => dispatch({ type: 'set-notice', notice: '原型：重做上一步编辑' })}
          >
            <Redo2 aria-hidden="true" size={15} />
          </button>
        </div>
        <span className="td-schema-pill" title="创建时永久绑定 exact StaticSchema，不可改绑">
          <Lock aria-hidden="true" size={11} />
          {templateMeta.schemaRef}
        </span>
        <code className="td-dsl-chip">{templateMeta.dslVersion}</code>
      </div>
      <div className="chrome-actions">
        <StatusChip state={state} />
        <span className="td-revision-chip" title="当前模板 revision">r{state.revision}</span>
        <button
          type="button"
          className="rwtd-icon-button"
          aria-label="导入 DesignDSL"
          title="导入 DesignDSL"
          onClick={() => {
            dispatch({ type: 'set-tab', tab: 'exchange' });
            dispatch({ type: 'set-notice', notice: '已打开 Import 工作流 · 导入不会静默覆盖目标 identity 或 exact Schema' });
          }}
        >
          <Upload aria-hidden="true" size={15} />
        </button>
        <button
          type="button"
          className="rwtd-icon-button"
          aria-label="导出 DesignDSL"
          title="导出 DesignDSL"
          onClick={() => {
            dispatch({ type: 'set-tab', tab: 'exchange' });
            dispatch({ type: 'set-notice', notice: '已打开 Export 工作流 · 可选择 bare DesignDSL 或 exact revision envelope' });
          }}
        >
          <Download aria-hidden="true" size={15} />
        </button>
        <button type="button" className="button ghost-button rwtd-preview-button" onClick={onRunPreview} disabled={state.previewPhase === 'loading'}>
          <Play aria-hidden="true" size={15} />
          预览
        </button>
        <button type="button" className="button primary-button" onClick={() => dispatch({ type: 'save' })}>
          <Save aria-hidden="true" size={15} />
          保存
        </button>
      </div>
    </header>
  );
}

export function ScenarioBar({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const current = scenarios.find((scenario) => scenario.key === state.scenario);
  return (
    <div className="td-scenario-bar" role="group" aria-label="演示场景(改变服务端/求值状态)">
      <span className="td-scenario-label">
        <Info aria-hidden="true" size={13} />
        状态样本
      </span>
      <div className="rwtd-scenario-options">
        {scenarios.map((scenario) => (
          <button
            key={scenario.key}
            type="button"
            className={`td-scenario-chip${state.scenario === scenario.key ? ' active' : ''}`}
            aria-pressed={state.scenario === scenario.key}
            title={scenario.hint}
            onClick={() => dispatch({ type: 'set-scenario', scenario: scenario.key })}
          >
            {scenario.label}
          </button>
        ))}
      </div>
      <span className="td-scenario-hint">{current?.hint}</span>
    </div>
  );
}

interface TreeRowProps {
  node: DesignerNode;
  depth: number;
  state: DesignerState;
  dispatch: Dispatch<DesignerAction>;
}

function TreeRow({ node, depth, state, dispatch }: TreeRowProps) {
  const selected = state.selectedNodeId === node.id;
  const hasBinding = node.props.some((prop) => prop.binding);
  return (
    <>
      <button
        type="button"
        role="treeitem"
        aria-level={depth + 1}
        className={`td-tree-row${selected ? ' selected' : ''}`}
        style={{ paddingLeft: `${10 + depth * 14}px` }}
        aria-selected={selected}
        onClick={() => dispatch({ type: 'select-node', nodeId: node.id })}
      >
        <span className="td-tree-caret" aria-hidden="true">
          {node.children.length > 0 ? <ChevronRight size={12} className="td-caret-open" /> : null}
        </span>
        <span className={`td-tree-kind kind-${node.kind}`} aria-hidden="true">{kindIcons[node.kind]}</span>
        <span className="td-tree-name">{node.name}</span>
        {hasBinding ? (
          <span className="td-tree-bound" title="含 Binding" aria-label="含 Binding">
            <Link2 aria-hidden="true" size={11} />
          </span>
        ) : null}
        {node.flags.map((flag) => (
          <span key={flag} className="td-flag-chip">
            <EyeOff aria-hidden="true" size={10} />
            {flag}
          </span>
        ))}
      </button>
      {node.children.map((child) => (
        <TreeRow key={child.id} node={child} depth={depth + 1} state={state} dispatch={dispatch} />
      ))}
    </>
  );
}

export function NodeTree({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  return (
    <div className="td-tree" role="tree" aria-label="DesignDSL 节点树(designRoot)">
      <TreeRow node={state.tree} depth={0} state={state} dispatch={dispatch} />
    </div>
  );
}

export function LibraryPanel({ dispatch }: { dispatch: Dispatch<DesignerAction> }) {
  const groups: NodeGroup[] = ['container', 'element', 'compose'];
  const simulateCreate = (kind: DesignerNode['kind']) => {
    const identities = [`nodeId ${newCanonicalUuid()}`];
    if (kind === 'repeat') identities.push(`loopId ${newCanonicalUuid()}`);
    if (kind === 'templateUse') identities.push(`useId ${newCanonicalUuid()}`);
    dispatch({ type: 'mark-dirty' });
    dispatch({
      type: 'set-notice',
      notice: `创建向导已由前端分配 ${identities.join(' · ')} · 下一步按父 ContentModel 写 placement 与合法 baseline`,
    });
  };
  return (
    <div className="td-panel-flow">
      <p className="td-panel-note">
        全部 kind 由 <code>NodeContractCatalog</code> 投影;点击模拟向选中容器插入(前端生成 UUID v4,按父 ContentModel 写 placement)。
      </p>
      {groups.map((group) => (
        <section key={group} className="td-library-section">
          <span className="td-panel-subhead">{nodeGroupLabels[group]}</span>
          <div className="td-library-grid">
            {nodeCatalog
              .filter((entry) => entry.group === group)
              .map((entry) => (
                <button
                  key={entry.kind}
                  type="button"
                  className="td-library-item"
                  onClick={() => simulateCreate(entry.kind)}
                >
                  <span className={`td-tree-kind kind-${entry.kind}`} aria-hidden="true">{kindIcons[entry.kind]}</span>
                  <span>{entry.label}</span>
                </button>
              ))}
          </div>
        </section>
      ))}
      <p className="td-inline-note">
        <Info aria-hidden="true" size={12} />
        Text 创建即含一个完整 Run 与显式 FONT <code>fontRef</code>；若无可用字体,创建结果为 INVALID 且不能权威预览。
      </p>
    </div>
  );
}

function assetWithScenario(asset: DesignerAsset, state: DesignerState): DesignerAsset {
  if (state.scenario === 'asset-deleted' && asset.id === assetIds.logoBadge) {
    return { ...asset, status: 'DELETED', detail: `${asset.detail} · 刚刚被删除(影响 1 个 Template)` };
  }
  return asset;
}

export function AssetsPanel({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const [query, setQuery] = useState('');
  const [kindFilter, setKindFilter] = useState<'ALL' | 'IMAGE' | 'FONT'>('ALL');
  const [catalogView, setCatalogView] = useState<'ACTIVE' | 'DELETED'>('ACTIVE');
  const [tagMode, setTagMode] = useState<'ANY' | 'ALL'>('ANY');
  const [tagQuery, setTagQuery] = useState('');
  const [pickerMode, setPickerMode] = useState(false);
  const [showUpload, setShowUpload] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [deletePhase, setDeletePhase] = useState<'idle' | 'impact' | 'stale'>('idle');
  const activeView = pickerMode ? 'ACTIVE' : catalogView;
  const requestedTags = tagQuery.split(',').map((tag) => tag.trim()).filter(Boolean);
  const rows = assets
    .map((asset) => assetWithScenario(asset, state))
    .filter((asset) => asset.status === activeView)
    .filter((asset) => kindFilter === 'ALL' || asset.kind === kindFilter)
    .filter((asset) => asset.name.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
    .filter((asset) => requestedTags.length === 0 || (tagMode === 'ALL'
      ? requestedTags.every((tag) => asset.tags.includes(tag))
      : requestedTags.some((tag) => asset.tags.includes(tag))));
  return (
    <div className="td-panel-flow">
      <p className="td-panel-note">
        Asset 创建的 <code>assetId</code> 由服务端生成；Node/Definition/Binding 等本地 identity 才由前端生成。picker 只列 ACTIVE。
      </p>
      <div className="td-mode-tabs" role="tablist" aria-label="Asset 目录视图">
        {(['ACTIVE', 'DELETED'] as const).map((view) => (
          <button
            key={view}
            type="button"
            role="tab"
            aria-selected={activeView === view}
            className={activeView === view ? 'active' : ''}
            disabled={pickerMode && view === 'DELETED'}
            onClick={() => setCatalogView(view)}
          >
            {view}
          </button>
        ))}
        <button
          type="button"
          className={pickerMode ? 'active' : ''}
          aria-pressed={pickerMode}
          onClick={() => {
            setPickerMode((value) => !value);
            setCatalogView('ACTIVE');
          }}
        >
          {pickerMode ? '退出 picker' : 'AssetRef picker'}
        </button>
      </div>
      <div className="td-asset-tools">
        <label className="td-search">
          <Search aria-hidden="true" size={13} />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索名称…"
            aria-label="搜索资产名称"
          />
        </label>
        {(['ALL', 'IMAGE', 'FONT'] as const).map((kind) => (
          <button
            key={kind}
            type="button"
            className={`td-mini-chip${kindFilter === kind ? ' active' : ''}`}
            aria-pressed={kindFilter === kind}
            onClick={() => setKindFilter(kind)}
          >
            {kind === 'ALL' ? '全部' : kind}
          </button>
        ))}
      </div>
      <div className="td-asset-tools td-tag-tools">
        <label className="td-search">
          <Search aria-hidden="true" size={13} />
          <input
            value={tagQuery}
            onChange={(event) => setTagQuery(event.target.value)}
            placeholder="tags:品牌,徽标"
            aria-label="按标签筛选资产"
          />
        </label>
        {(['ANY', 'ALL'] as const).map((mode) => (
          <button
            key={mode}
            type="button"
            className={`td-mini-chip${tagMode === mode ? ' active' : ''}`}
            aria-pressed={tagMode === mode}
            onClick={() => setTagMode(mode)}
          >
            tags{mode === 'ANY' ? 'Any' : 'All'}
          </button>
        ))}
      </div>
      {pickerMode ? (
        <p className="td-inline-note chip-info-block">
          <Lock aria-hidden="true" size={12} />
          picker 经 <code>asset.read</code> 与 same-ownerScope 筛选；missing / DELETED / kind mismatch 只在既有引用诊断中展示,不可新选。
        </p>
      ) : null}
      <ul className="td-asset-list">
        {rows.map((asset) => (
          <li key={asset.id} className={`td-asset-row${asset.status === 'DELETED' ? ' is-deleted' : ''}`}>
            <span className={`td-tree-kind kind-${asset.kind === 'IMAGE' ? 'image' : 'text'}`} aria-hidden="true">
              {asset.kind === 'IMAGE' ? <Image size={15} /> : <Type size={15} />}
            </span>
            <div className="td-asset-main">
              <div className="td-asset-title">
                <strong>{asset.name}</strong>
                <span className="td-mini-chip">{asset.kind}</span>
                <span className={`td-mini-chip ${asset.status === 'DELETED' ? 'chip-error' : 'chip-ok'}`}>
                  {asset.status}
                </span>
              </div>
              <small>{asset.detail}</small>
              <span className="td-tag-row">{asset.tags.map((tag) => <em key={tag}>#{tag}</em>)}</span>
              {asset.usedBy ? <small className="td-asset-used">引用:{asset.usedBy}</small> : null}
              {asset.misuse ? (
                <small className="td-asset-misuse">
                  <AlertTriangle aria-hidden="true" size={11} />
                  {asset.misuse}
                </small>
              ) : null}
            </div>
            {pickerMode ? (
              <button
                type="button"
                className="button ghost-button td-row-action"
                onClick={() => dispatch({ type: 'set-notice', notice: `已选择 ${asset.name} · 保存时仍按 AssetRef kind 与权限权威重检` })}
              >
                选择
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {rows.length === 0 ? <p className="td-empty-row">当前过滤条件无结果。</p> : null}
      <div className="td-cursor-row">
        <span>稳定游标 <code>cur_A01…</code> · 当前 1–{rows.length}</span>
        <button type="button" className="td-mini-chip" disabled>上一页</button>
        <button type="button" className="td-mini-chip" onClick={() => dispatch({ type: 'set-notice', notice: '使用响应 nextCursor 取下一页;不以 offset 推断目录状态' })}>下一页</button>
      </div>

      {!pickerMode ? (
        <div className="td-asset-actions">
          <button type="button" className="button ghost-button" onClick={() => setShowUpload((value) => !value)}>
            <Upload aria-hidden="true" size={13} />
            多文件上传
          </button>
          <button type="button" className="button ghost-button" onClick={() => setShowHistory((value) => !value)}>
            <History aria-hidden="true" size={13} />
            内容历史
          </button>
          <button type="button" className="button ghost-button" onClick={() => setDeletePhase('impact')}>
            <Trash2 aria-hidden="true" size={13} />
            删除影响检查
          </button>
        </div>
      ) : null}

      {showUpload ? (
        <section className="td-workflow-card" aria-label="独立逐文件上传结果">
          <div className="td-workflow-head"><strong>上传结果 · 非原子批次</strong><span>3 files</span></div>
          <p className="td-upload-result ok"><Check size={12} /> logo-summer.png · ACCEPTED · 服务端生成 assetId</p>
          <p className="td-upload-result error"><AlertTriangle size={12} /> poster.svg · REJECTED · ASSET_MEDIA_TYPE_UNSUPPORTED</p>
          <p className="td-upload-result ok"><Check size={12} /> number-face.ttf · ACCEPTED · single face</p>
        </section>
      ) : null}

      {showHistory ? (
        <section className="td-workflow-card" aria-label="Asset 内容历史">
          <div className="td-workflow-head"><strong>logo-badge · 内容历史</strong><span>metadata 独立</span></div>
          {[3, 2, 1].map((version) => (
            <div key={version} className="td-history-row">
              <code>contentVersion {version}</code>
              <span>{version === 3 ? 'current · PNG' : '历史 · PNG'}</span>
              <button type="button" className="td-mini-chip" onClick={() => dispatch({ type: 'set-notice', notice: `精确下载 contentVersion ${version} · 通过 Asset 产品接口,不暴露 Renderer lease` })}>下载</button>
              {version === 2 ? (
                <button type="button" className="td-mini-chip" onClick={() => dispatch({ type: 'set-notice', notice: '恢复不会改写历史 · 已将 v2 bytes 追加为新的 contentVersion 4' })}>恢复为新版本</button>
              ) : null}
            </div>
          ))}
        </section>
      ) : null}

      {deletePhase !== 'idle' ? (
        <section className={`td-workflow-card td-delete-impact${deletePhase === 'stale' ? ' is-stale' : ''}`} aria-label="Asset 删除影响">
          <div className="td-workflow-head"><strong>删除 logo-badge</strong><span>全量影响 5 occurrences</span></div>
          <p>可见 occurrences 4 · redactedCount 1 · 重复 assetId 按 canonical authored occurrence 保留定位</p>
          <code>活动价签 · /designRoot/children/0/children/2/imageRef · expected IMAGE</code>
          <code>品牌角标 · /definitions/6/default · Custom default · expected IMAGE</code>
          <code>主题映射 · /definitions/7/cases/1/result · Mapping result · expected IMAGE</code>
          <code>画廊默认 · /definitions/8/default/2 · list&lt;imageRef&gt; element · expected IMAGE</code>
          {deletePhase === 'stale' ? (
            <p className="td-inline-note chip-error-block"><AlertTriangle size={12} />确认 token 已失效:引用在检查后漂移;服务端零删除,必须重新检查。</p>
          ) : (
            <p className="td-inline-note"><Lock size={12} />影响 token 绑定当前反向依赖快照;不可由客户端改写数量。</p>
          )}
          <div className="td-workflow-actions">
            <button type="button" className="button ghost-button" onClick={() => setDeletePhase('stale')}>模拟引用漂移</button>
            <button type="button" className="button ghost-button" onClick={() => setDeletePhase('impact')}>重新检查</button>
            <button
              type="button"
              className="button td-danger-button"
              disabled={deletePhase === 'stale'}
              onClick={() => {
                dispatch({ type: 'set-scenario', scenario: 'asset-deleted' });
                dispatch({ type: 'set-notice', notice: 'Asset 已删除 · 受影响 Template 自动重检;当前 Template 进入依赖 ERROR 场景' });
                setCatalogView('DELETED');
              }}
            >
              使用有效 token 删除
            </button>
          </div>
        </section>
      ) : null}

      <p className="td-panel-note">
        authored 依赖索引不因 Binding 覆盖、branch 未选或结构不可达而折叠；每个 pointer 与 expected kind 都保留。
      </p>
    </div>
  );
}

export function DefinitionsPanel({ dispatch }: Pick<PartProps, 'dispatch'>) {
  const createDefinition = (kind: 'CUSTOM' | 'MAPPING' | 'EXPRESSION') => {
    const definitionId = newCanonicalUuid();
    dispatch({ type: 'mark-dirty' });
    dispatch({
      type: 'set-notice',
      notice: `${kind} 创建向导由前端分配 definitionId ${definitionId} · source/input/domain 将随 DesignDSL 保存`,
    });
  };
  return (
    <div className="td-panel-flow">
      <p className="td-panel-note">
        CustomDefinition / Computed Definition — 不是通用 DataSource；稳定 definitionId,显式 invocation / loopId domain。
      </p>
      <div className="td-def-create" aria-label="创建 definition">
        {(['CUSTOM', 'MAPPING', 'EXPRESSION'] as const).map((kind) => (
          <button key={kind} type="button" className="td-mini-chip" onClick={() => createDefinition(kind)}>
            <Plus aria-hidden="true" size={10} />{kind}
          </button>
        ))}
      </div>
      <ul className="td-def-list">
        {definitions.map((definition) => (
          <li key={definition.id} className="td-def-row">
            <div className="td-def-title">
              <strong>{definition.name}</strong>
              <span className="td-mini-chip">{definition.kind}</span>
              <span className={`td-mini-chip ${definition.visibility === 'PUBLIC' ? 'chip-info' : ''}`}>
                {definition.visibility}
              </span>
            </div>
            <code>{definition.id}</code>
            <small>{definition.domain}</small>
            <small className="td-def-detail">{definition.detail}</small>
            {definition.inputs?.length ? (
              <span className="td-def-inputs">
                {definition.inputs.map((input) => <code key={input}>input · {input}</code>)}
              </span>
            ) : (
              <small>default 是 authored static ValueSource；PRIVATE 对外不可见,外部同名 override 静默忽略。</small>
            )}
          </li>
        ))}
      </ul>
      <section className="td-workflow-card td-capability-card">
        <div className="td-workflow-head"><strong>Expression input · exact Profile</strong><span>仅此三项</span></div>
        <div className="td-capability-list">
          {['UTC_DATE → date', 'UTC_TIME → time', 'UNIFORM_DECIMAL_0_1 → decimal'].map((capability) => (
            <code key={capability}>{capability}</code>
          ))}
        </div>
        <p>它们是显式 typed capability input,不是“系统数据源”；无 args/timezone/seed/version。Random 不可用于 UUID、token 或安全用途。</p>
        <p>declaration frame:invocation-domain 在多个 loop consumer 间共享 memoized draw；loop-domain 按原输入 index 独立。duplicate item 仍独立,reorder 会改变 item 与 draw 的对应。</p>
      </section>
      <p className="td-inline-note chip-error-block">
        <AlertTriangle aria-hidden="true" size={12} />
        <code>map(input.tags, x =&gt; x)</code> → <code>EXPRESSION_PROFILE_UNSUPPORTED_SYNTAX</code>；不会显示“已解析,稍后实现”。
      </p>
    </div>
  );
}

export function DataPanel({ state }: Pick<PartProps, 'state'>) {
  return (
    <div className="td-panel-flow">
      <p className="td-panel-note">
        以下输入只属于本地 EditorSession,不进入 Template revision；DesignDSL 在另一事实边界。
      </p>
      <div className="td-code-block">
        <div className="td-code-head">
          <FileJson aria-hidden="true" size={13} />
          ① RootDocument · exact Schema context
          <span className="td-mini-chip chip-info">local session</span>
        </div>
        <pre tabIndex={0}>{rootDocumentSample(state.scenario)}</pre>
      </div>
      {state.scenario === 'binding-absent' ? (
        <p className="td-inline-note chip-info-block">
          <Info aria-hidden="true" size={12} />
          样本缺少 <code>/launchDate</code> → optional field 求值为 typed ABSENT;Binding 存在但无回退。
        </p>
      ) : null}
      <div className="td-code-block">
        <div className="td-code-head">
          <Braces aria-hidden="true" size={13} />
          ② 根 customValues 赋值列表
          <span className="td-mini-chip chip-info">local session</span>
        </div>
        <pre tabIndex={0}>{customValuesSample}</pre>
      </div>
      <p className="td-inline-note">
        <AlertTriangle aria-hidden="true" size={12} />
        <code>brandName</code> 是 PRIVATE → 静默忽略;<code>unknownKey</code> 不在 definitions → 静默忽略。
      </p>
      <section className="td-workflow-card">
        <div className="td-workflow-head"><strong>PUBLIC Asset override 准入</strong><span>逐 atom</span></div>
        <code>brandIcon · imageRef · same scope ✓ · ACTIVE ✓ · IMAGE ✓ · asset.read ✓</code>
        <p>Definition/fill 传递不固定某个内容版本；每个 materialized consumer 独立选择 current。两次消费之间发生 replace 时可观察到不同内容,普通作者 UI 不显示 version/hash/lease。</p>
      </section>
      <section className="td-workflow-card td-loop-frame-card">
        <div className="td-workflow-head"><strong>③ Repeat lexical frame</strong><span>system-basic-text@v1</span></div>
        <div className="td-frame-columns">
          <code>/index = 0 · decimal</code>
          <code>/value = '新品' · text</code>
        </div>
        <p><code>/index</code> 是原输入零基 loopIndex；item 被 render:false / SKIP 剪枝后 instance packing 可压紧,但 index 不重编号。reference 业务 Schema 不伪注入 index。</p>
      </section>
      {state.scenario === 'child-fill-invalid' ? (
        <p className="td-inline-note chip-error-block">
          <AlertTriangle aria-hidden="true" size={12} />
          这不是 unknown/PRIVATE external override 的静默忽略：authored child fill 指向失效 PUBLIC definitionId 会使父 Template INVALID。
        </p>
      ) : null}
      <div className="td-schema-context">
        <span className="td-panel-subhead">exact Schema context</span>
        <div className="td-schema-fields">
          {schemaFields.map((field) => (
            <span key={field.path} className="td-field-chip">
              <code>{field.path}</code>
              <em>{field.type}</em>
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

type ExchangeMode = 'overview' | 'strict-repair' | 'best-effort' | 'migration' | 'export';

export function ExchangePanel({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const [mode, setMode] = useState<ExchangeMode>('overview');
  return (
    <div className="td-panel-flow td-exchange">
      <p className="td-panel-note">
        Import / Export 是 authoring 边界,不是身份或权限迁移。目标 Template 的 identity 与 exact StaticSchema 永不被文件静默覆盖。
      </p>
      <button type="button" className="td-mini-chip td-exchange-back" onClick={() => dispatch({ type: 'set-tab', tab: 'definitions' })}>返回 Definitions</button>
      <div className="td-exchange-grid">
        <button type="button" className="td-workflow-choice" onClick={() => setMode('best-effort')}>
          <FolderInput aria-hidden="true" size={16} />
          <strong>导入 bare DesignDSL</strong>
          <span>strict 校验；结构可识别的无效内容可进入 best-effort canvas</span>
        </button>
        <button type="button" className="td-workflow-choice" onClick={() => setMode('strict-repair')}>
          <FileClock aria-hidden="true" size={16} />
          <strong>导入 exact revision export</strong>
          <span>来源 identity/Schema 只读展示,不授予目标权限</span>
        </button>
        <button type="button" className="td-workflow-choice" onClick={() => setMode('migration')}>
          <WandSparkles aria-hidden="true" size={16} />
          <strong>打开不受支持版本</strong>
          <span>只读 / export / 显式 migration；禁止 partial-model save</span>
        </button>
        <button type="button" className="td-workflow-choice" onClick={() => setMode('export')}>
          <Download aria-hidden="true" size={16} />
          <strong>导出与复制</strong>
          <span>bare DSL、exact revision envelope、same-scope whole copy</span>
        </button>
      </div>

      {mode === 'overview' ? (
        <section className="td-workflow-card">
          <div className="td-workflow-head"><strong>保存后的 canonical 重同步</strong><span>revision {state.revision}</span></div>
          <div className="td-canonical-diff">
            <code>metadata.name: ' 活动价签 ' → '活动价签'</code>
            <code>decimal token: 199.00 → 199</code>
            <code>definitions / bindings / inputs → 按稳定 ID/alias 规范排序</code>
            <code>Expression source bytes → 原样保留,包括 whitespace/newline</code>
          </div>
          <p>客户端用响应中的 canonical DesignDSL、revision 与 contentHash 整体替换本地基线；contentHash 是内容标识,不是签名。</p>
        </section>
      ) : null}

      {mode === 'strict-repair' ? (
        <section className="td-workflow-card td-repair-card">
          <div className="td-workflow-head"><strong>exact revision · 来源检查</strong><span>raw repair</span></div>
          <code>source templateId 28b… · schema campaign-card@v2 · revision 41</code>
          <p className="td-inline-note chip-error-block"><AlertTriangle size={12} />dslVersion <code>renderweave-design/0.8</code> / expressionProfile <code>renderweave-expression/0.7</code> 不受支持；当前客户端只读,可原样导出或进入 migration。</p>
          <button type="button" className="button ghost-button" onClick={() => setMode('migration')}>预览迁移</button>
        </section>
      ) : null}

      {mode === 'best-effort' ? (
        <section className="td-workflow-card">
          <div className="td-workflow-head"><strong>best-effort canvas</strong><span>结构可识别 · 当前不可保存</span></div>
          <p>12 个节点可呈现；2 个属性保留为 raw repair；1 个 binding target 越界。来源 Template/Schema 仅作提示,目标仍绑定 <code>{templateMeta.schemaRef}</code>。</p>
          <code>IMPORT_TARGET_INDEX_OUT_OF_RANGE · /designRoot/children/0/bindings/2 · hard</code>
          <div className="td-workflow-actions">
            <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'set-notice', notice: '已进入 best-effort canvas · 修复全部 hard problems 后才可走普通 save' })}>进入修复画布</button>
            <button type="button" className="button ghost-button" onClick={() => setMode('strict-repair')}>查看 raw JSON</button>
          </div>
        </section>
      ) : null}

      {mode === 'migration' ? (
        <section className="td-workflow-card td-migration-card">
          <div className="td-workflow-head"><strong>显式 migration 预览</strong><span>尚未写入</span></div>
          <code>dslVersion 0.8 → renderweave-design/1.0</code>
          <code>expressionProfile 0.7 → renderweave-expression/1.0</code>
          <code>text.fontFamily → runs[0].fontRef (需要选择 FONT)</code>
          <code>templateLayout → itemLayout + instanceLayout (需人工确认)</code>
          <p>先审阅 canonical output、changes 与 problems；接受后仍走普通 expectedRevision save,不绕过 conflict/validation。</p>
          <button type="button" className="button primary-button" onClick={() => dispatch({ type: 'set-notice', notice: '迁移草稿已装载 · 尚未保存;请在画布修复字体与 Repeat placement' })}>接受迁移草稿</button>
        </section>
      ) : null}

      {mode === 'export' ? (
        <section className="td-workflow-card">
          <div className="td-workflow-head"><strong>Export</strong><span>两种明确格式</span></div>
          <button type="button" className="td-export-row" onClick={() => dispatch({ type: 'set-notice', notice: '已生成 bare DesignDSL · 不含 Template identity、revision 或权限声明' })}>
            <FileJson size={14} /><span><strong>bare DesignDSL</strong><small>可移植 authored 内容</small></span>
          </button>
          <button type="button" className="td-export-row" onClick={() => dispatch({ type: 'set-notice', notice: `已生成 exact revision ${state.revision} envelope · contentHash 不是签名` })}>
            <FileClock size={14} /><span><strong>exact Template revision envelope</strong><small>identity + exact Schema + revision + contentHash + DSL</small></span>
          </button>
          <button type="button" className="td-export-row" onClick={() => dispatch({ type: 'set-notice', notice: 'whole Template copy 仅同 ownerScope · 新 Template identity,保留全部 local node/definition/binding/loop/use IDs' })}>
            <Copy size={14} /><span><strong>同 ownerScope 复制整个 Template</strong><small>local IDs 保留；v1 无 cross-scope deep copy</small></span>
          </button>
          <p>文件中的 Template/Schema/Asset identity 不能授予 read/write 权限；导入时仍逐项鉴权。</p>
        </section>
      ) : null}
    </div>
  );
}

export function Artboard({ state, dispatch, compact }: Pick<PartProps, 'state' | 'dispatch'> & { compact?: boolean }) {
  const pxPerMm = (96 / 25.4) * ((compact ? 100 : state.zoom) / 100);
  const width = templateMeta.canvasMm.width * pxPerMm;
  const height = templateMeta.canvasMm.height * pxPerMm;
  return (
    <div className={`td-artboard-wrap${compact ? ' compact' : ''}`}>
      <div className="td-artboard-meta">
        <span className="td-mini-chip chip-warn">草稿画布 · 浏览器派生 · 非权威</span>
        <span className="td-mini-chip">server authority · renderweave-layout/1.0</span>
        <span className="td-mini-chip">90×54mm · pt 字号</span>
        {!compact ? (
          <label className="td-zoom">
            缩放
            <select
              value={state.zoom}
              onChange={(event) => dispatch({ type: 'set-zoom', zoom: Number(event.target.value) })}
              aria-label="画布缩放"
            >
              {[50, 75, 100, 150, 200].map((zoom) => (
                <option key={zoom} value={zoom}>{zoom}%</option>
              ))}
            </select>
          </label>
        ) : null}
      </div>
      <div className="td-artboard-clip" tabIndex={0} aria-label={compact ? '缩略画布视口' : '画布视口'}>
        <div
          className="td-artboard"
          style={{ width, height }}
          role={compact ? 'img' : 'application'}
          aria-label={compact ? '草稿画布缩略引用(非权威)' : '草稿画布(非权威)'}
        >
          <span className="td-artboard-size" aria-hidden="true">90mm × 54mm</span>
          {draftBoxes.map((box) => {
            const selected = state.selectedNodeId === box.nodeId;
            const style = {
              left: box.x * pxPerMm,
              top: box.y * pxPerMm,
              width: box.w * pxPerMm,
              height: box.h * pxPerMm,
            };
            if (compact) {
              return (
                <span
                  key={box.nodeId}
                  className={`td-node-box tone-${box.tone}${selected ? ' selected' : ''}`}
                  style={style}
                  aria-hidden="true"
                >
                  <span className="td-node-label">{box.label}</span>
                </span>
              );
            }
            return (
              <button
                key={box.nodeId}
                type="button"
                className={`td-node-box tone-${box.tone}${selected ? ' selected' : ''}`}
                style={style}
                aria-pressed={selected}
                aria-label={`选择节点 ${box.label}`}
                onClick={() => dispatch({ type: 'select-node', nodeId: box.nodeId })}
              >
                <span className="td-node-label">{box.label}</span>
              </button>
            );
          })}
        </div>
      </div>
      <p className="td-artboard-foot">
        派生 x/y/LayoutBox 不持久化 · promoCorner render:false 无 occurrence · watermark visible:false 仍以虚线示占布局
      </p>
    </div>
  );
}

function bindingStateFor(nodeId: string, state: DesignerState): 'ok' | 'absent' | 'error' {
  if (state.scenario === 'binding-absent' && nodeId === nodeIds.dateLine) return 'absent';
  return 'ok';
}

type PropertyGroupKey = 'content' | 'typography' | 'layout' | 'appearance' | 'data' | 'behavior' | 'composition' | 'advanced';
type PropertyControlKind = 'text' | 'multiline' | 'number' | 'select' | 'boolean' | 'color' | 'asset' | 'font' | 'source' | 'template' | 'readonly';

interface PropertyPresentation {
  name: string;
  group: PropertyGroupKey;
  control: PropertyControlKind;
  unit?: string;
  step?: string;
  min?: number;
}

const propertyGroups: { key: PropertyGroupKey; label: string }[] = [
  { key: 'content', label: '内容' },
  { key: 'typography', label: '文字' },
  { key: 'layout', label: '布局' },
  { key: 'appearance', label: '外观' },
  { key: 'data', label: '数据' },
  { key: 'behavior', label: '行为' },
  { key: 'composition', label: '子模板' },
  { key: 'advanced', label: '高级' },
];

const propertyPresentations: Record<string, PropertyPresentation> = {
  widthMm: { name: '画布宽度', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  heightMm: { name: '画布高度', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  backgroundColor: { name: '画布背景', group: 'appearance', control: 'color' },
  direction: { name: '排列方向', group: 'layout', control: 'select' },
  gapMm: { name: '项目间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'padding.topMm': { name: '上内边距', group: 'layout', control: 'number', unit: 'mm', step: '0.1' },
  'fill.color': { name: '填充颜色', group: 'appearance', control: 'color' },
  'cornerRadii.topLeftMm': { name: '左上圆角', group: 'appearance', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'placement.fillWeight': { name: '填充权重', group: 'layout', control: 'number', step: '0.1', min: 0 },
  'runs[0].text': { name: '文本内容', group: 'content', control: 'multiline' },
  'runs[0].fontRef': { name: '字体', group: 'typography', control: 'font' },
  'runs[0].fontSizePt': { name: '字号', group: 'typography', control: 'number', unit: 'pt', step: '0.5', min: 0 },
  'runs[0].color': { name: '文字颜色', group: 'typography', control: 'color' },
  writingMode: { name: '书写方向', group: 'typography', control: 'select' },
  lineBreak: { name: '换行方式', group: 'typography', control: 'select' },
  overflow: { name: '溢出处理', group: 'typography', control: 'select' },
  horizontalAlign: { name: '水平对齐', group: 'typography', control: 'select' },
  verticalAlign: { name: '垂直对齐', group: 'typography', control: 'select' },
  'stroke.widthMm': { name: '描边宽度', group: 'appearance', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  shrinkToFit: { name: '自动缩小', group: 'typography', control: 'boolean' },
  imageRef: { name: '图片资源', group: 'content', control: 'asset' },
  fit: { name: '图片适配', group: 'appearance', control: 'select' },
  sampling: { name: '采样方式', group: 'appearance', control: 'select' },
  maxLines: { name: '最大行数', group: 'typography', control: 'number', unit: '行', step: '1', min: 1 },
  items: { name: '循环数据', group: 'data', control: 'source' },
  absentPolicy: { name: '缺失值处理', group: 'data', control: 'select' },
  'itemLayout.kind': { name: '单项布局', group: 'layout', control: 'readonly' },
  'itemLayout.direction': { name: '单项方向', group: 'layout', control: 'select' },
  'itemLayout.gapMm': { name: '单项间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'instanceLayout.kind': { name: '实例布局', group: 'layout', control: 'readonly' },
  'instanceLayout.columns': { name: '网格列数', group: 'layout', control: 'number', unit: '列', step: '1', min: 1 },
  'instanceLayout.columnGapMm': { name: '列间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'instanceLayout.rowGapMm': { name: '行间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'placement.type': { name: '布局方式', group: 'layout', control: 'readonly' },
  'templateRef.templateId': { name: '目标模板', group: 'composition', control: 'template' },
  contextSelector: { name: '数据上下文', group: 'data', control: 'source' },
  contextAbsentPolicy: { name: '上下文缺失', group: 'data', control: 'select' },
  fills: { name: '自定义数据赋值', group: 'composition', control: 'readonly' },
  'fill: brandName': { name: '品牌名称赋值', group: 'composition', control: 'source' },
  content: { name: '二维码内容', group: 'content', control: 'text' },
  errorCorrectionLevel: { name: '容错等级', group: 'content', control: 'select' },
  foregroundColor: { name: '前景颜色', group: 'appearance', control: 'color' },
  render: { name: '参与渲染', group: 'behavior', control: 'boolean' },
  condition: { name: '渲染条件', group: 'behavior', control: 'source' },
  visible: { name: '可见性', group: 'behavior', control: 'boolean' },
};

const propertyOptionLabels: Record<string, Record<string, string>> = {
  direction: { VERTICAL: '纵向', HORIZONTAL: '横向' },
  writingMode: { HORIZONTAL_TB: '横排', VERTICAL_RL: '竖排（从右向左）' },
  lineBreak: { NONE: '不换行', WORD: '按词换行', CHAR: '按字符换行' },
  overflow: { VISIBLE: '显示溢出', CLIP: '裁剪', ELLIPSIS: '省略号', FAIL: '溢出时报错' },
  horizontalAlign: { LEFT: '左对齐', CENTER: '水平居中', RIGHT: '右对齐', JUSTIFY: '两端对齐', SPACE_EVENLY: '均匀分布' },
  verticalAlign: { TOP: '顶部', CENTER: '垂直居中', BOTTOM: '底部', JUSTIFY: '两端对齐', SPACE_EVENLY: '均匀分布' },
  fit: { CONTAIN: '完整显示', COVER: '覆盖裁剪', FILL: '拉伸填充' },
  sampling: { LINEAR: '平滑', NEAREST: '邻近像素' },
  absentPolicy: { ERROR: '缺失时报错', EMPTY: '按空列表处理', FALSE: '按不满足处理' },
  'itemLayout.direction': { ROW: '横向', COLUMN: '纵向' },
  contextAbsentPolicy: { ERROR: '缺失时报错', SKIP: '缺失时跳过' },
  errorCorrectionLevel: { L: '低（7%）', M: '中（15%）', Q: '较高（25%）', H: '高（30%）' },
};

const colorPreviews: Record<string, string> = {
  surface: 'var(--color-surface)',
  'accent-wash': 'var(--color-accent-wash)',
  ink: 'var(--color-ink)',
  coral: 'var(--color-coral)',
  hairline: 'var(--color-hairline)',
};

const colorNames: Record<string, string> = {
  surface: '表面色',
  'accent-wash': '强调浅色',
  ink: '正文深色',
  coral: '促销红',
  hairline: '分隔线色',
  '#FFFFFF': '白色',
};

function propertyPresentationFor(prop: InspectorProp): PropertyPresentation {
  return propertyPresentations[prop.label] ?? {
    name: '扩展属性',
    group: 'advanced',
    control: prop.options ? 'select' : 'text',
  };
}

function humanReadonlyValue(prop: InspectorProp): string {
  if (prop.label === 'items') return '调用数据 · /tags';
  if (prop.label === 'itemLayout.kind') return '堆叠布局';
  if (prop.label === 'instanceLayout.kind') return '网格布局';
  if (prop.label === 'placement.type') return prop.value === 'PACK' ? '自动打包' : '堆叠定位';
  if (prop.label === 'templateRef.templateId') {
    return prop.value === childTemplateIds.tagPill ? '标签胶囊模板 · 当前版本' : '品牌角标模板 · 当前版本';
  }
  if (prop.label === 'contextSelector') return prop.value.includes('loop(') ? '当前循环项' : '调用数据 · /brand';
  if (prop.label === 'fills') return '无自定义数据赋值';
  if (prop.label === 'fill: brandName') return '来自 /brand.name';
  if (prop.label === 'condition') return '固定为真';
  return prop.value;
}

function referencedAsset(value: string): DesignerAsset | undefined {
  const assetId = value.match(/assetId:([^}]+)/)?.[1];
  return assets.find((asset) => asset.id === assetId);
}

function bindingSourceSummary(binding: NodeBinding): { source: string; note: string } {
  const definitionId = binding.source.match(/^definition\(([^)]+)\)$/)?.[1];
  if (definitionId) {
    const definition = definitions.find((candidate) => candidate.id === definitionId);
    return { source: `定义 · ${definition?.name ?? '表达式'}`, note: '公共定义输出' };
  }
  if (binding.source.startsWith('context(loop')) {
    return { source: '循环项 · /value', note: '当前循环作用域' };
  }
  if (binding.note.includes('optional')) {
    return { source: `数据字段 · ${binding.source}`, note: '可选字段；缺失时预览失败' };
  }
  return { source: `数据字段 · ${binding.source}`, note: '绑定值覆盖基础值' };
}

function PropControl({
  node,
  prop,
  presentation,
  controlId,
  labelId,
  dispatch,
}: {
  node: DesignerNode;
  prop: InspectorProp;
  presentation: PropertyPresentation;
  controlId: string;
  labelId: string;
  dispatch: Dispatch<DesignerAction>;
}) {
  const updateValue = (value: string) => dispatch({ type: 'update-prop', nodeId: node.id, label: prop.label, value });

  if (presentation.control === 'select' && prop.options) {
    const labels = propertyOptionLabels[prop.label];
    return (
      <SelectField
        id={controlId}
        ariaLabel={`设置${presentation.name}`}
        value={prop.value}
        options={prop.options.map((option) => ({ value: option, label: labels?.[option] ?? option }))}
        onChange={updateValue}
      />
    );
  }

  if (presentation.control === 'boolean') {
    const checked = prop.value === 'true';
    return (
      <button
        id={controlId}
        type="button"
        role="switch"
        aria-labelledby={labelId}
        aria-checked={checked}
        className={`td-boolean-control${checked ? ' is-on' : ''}`}
        onClick={() => updateValue(checked ? 'false' : 'true')}
      >
        <span className="td-toggle-track" aria-hidden="true"><span /></span>
        <span>{checked ? '开启' : '关闭'}</span>
      </button>
    );
  }

  if (presentation.control === 'number') {
    return (
      <span className="td-number-control">
        <input
          id={controlId}
          type="number"
          inputMode="decimal"
          value={prop.value}
          min={presentation.min}
          step={presentation.step}
          aria-labelledby={labelId}
          onChange={(event) => updateValue(event.target.value)}
        />
        {presentation.unit ? <span aria-hidden="true">{presentation.unit}</span> : null}
      </span>
    );
  }

  if (presentation.control === 'color') {
    const preview = colorPreviews[prop.value] ?? (prop.value.startsWith('#') ? prop.value : 'var(--color-surface-strong)');
    return (
      <button
        id={controlId}
        type="button"
        className="td-color-control"
        aria-labelledby={labelId}
        onClick={() => dispatch({ type: 'set-notice', notice: `已打开${presentation.name}选择器（原型）：当前 ${colorNames[prop.value] ?? prop.value}` })}
      >
        <span className="td-color-swatch" style={{ backgroundColor: preview }} aria-hidden="true" />
        <span>{colorNames[prop.value] ?? prop.value}</span>
        <ChevronRight aria-hidden="true" size={12} />
      </button>
    );
  }

  if (presentation.control === 'asset' || presentation.control === 'font') {
    const asset = referencedAsset(prop.value);
    const ResourceIcon = presentation.control === 'font' ? Type : Image;
    return (
      <button
        id={controlId}
        type="button"
        className="td-reference-control"
        aria-labelledby={labelId}
        onClick={() => dispatch({ type: 'set-notice', notice: `已打开${presentation.name}选择器（原型）：当前 ${asset?.name ?? '未选择'}` })}
      >
        <ResourceIcon aria-hidden="true" size={13} />
        <span>{asset?.name ?? '选择资源'}</span>
        <ChevronRight aria-hidden="true" size={12} />
      </button>
    );
  }

  if (presentation.control === 'source' || presentation.control === 'template') {
    const SourceIcon = presentation.control === 'template' ? Layers : Braces;
    return (
      <button
        id={controlId}
        type="button"
        className="td-reference-control"
        aria-labelledby={labelId}
        onClick={() => dispatch({ type: 'set-notice', notice: `已打开${presentation.name}配置（原型）` })}
      >
        <SourceIcon aria-hidden="true" size={13} />
        <span>{humanReadonlyValue(prop)}</span>
        <ChevronRight aria-hidden="true" size={12} />
      </button>
    );
  }

  if (presentation.control === 'readonly') {
    return (
      <output id={controlId} className="td-readonly-control" aria-labelledby={labelId}>
        <Lock aria-hidden="true" size={11} />
        <span>{humanReadonlyValue(prop)}</span>
      </output>
    );
  }

  if (presentation.control === 'multiline') {
    return (
      <textarea
        id={controlId}
        className="td-prop-input td-prop-textarea"
        value={prop.value}
        rows={2}
        aria-labelledby={labelId}
        onChange={(event) => updateValue(event.target.value)}
      />
    );
  }

  return (
    <input
      id={controlId}
      className="td-prop-input"
      value={prop.value}
      aria-labelledby={labelId}
      onChange={(event) => updateValue(event.target.value)}
    />
  );
}

function InspectorPropRow({ node, prop, state, dispatch }: { node: DesignerNode; prop: InspectorProp; state: DesignerState; dispatch: Dispatch<DesignerAction> }) {
  const generatedId = useId();
  const controlId = `${generatedId}-control`;
  const labelId = `${generatedId}-label`;
  const presentation = propertyPresentationFor(prop);
  const bindState = prop.binding ? bindingStateFor(node.id, state) : null;
  const sourceSummary = prop.binding ? bindingSourceSummary(prop.binding) : null;
  const openEditor = () => dispatch({ type: 'open-binding', nodeId: node.id, label: prop.label });
  return (
    <li className={`td-prop-row${prop.binding ? ' is-bound' : ''}`}>
      <label id={labelId} className="td-prop-label" htmlFor={controlId}>{presentation.name}</label>
      <div className="td-prop-stack">
        <div className="td-prop-editor">
          <div className="td-prop-control">
            <PropControl
              node={node}
              prop={prop}
              presentation={presentation}
              controlId={controlId}
              labelId={labelId}
              dispatch={dispatch}
            />
          </div>
          {prop.bindable ? (
            <button
              type="button"
              className={`td-bind-action${prop.binding ? ` is-bound bind-${bindState}` : ''}`}
              onClick={openEditor}
              aria-label={`${prop.binding ? '编辑' : '为'}${presentation.name}${prop.binding ? '绑定' : '添加绑定'}`}
              title={prop.binding ? '编辑或取消绑定' : '添加绑定'}
            >
              {bindState && bindState !== 'ok' ? <AlertTriangle aria-hidden="true" size={12} /> : <Link2 aria-hidden="true" size={12} />}
              <span>{prop.binding ? (bindState === 'ok' ? '已绑定' : '异常') : '绑定'}</span>
            </button>
          ) : null}
        </div>
        {prop.binding && bindState && sourceSummary ? (
          <div className={`td-binding-summary bind-${bindState}`}>
            {bindState === 'ok' ? <Link2 aria-hidden="true" size={11} /> : <AlertTriangle aria-hidden="true" size={11} />}
            <span>{sourceSummary.source}</span>
            <small>
              {bindState === 'absent' ? '当前数据缺失；权威预览失败' : bindState === 'error' ? '绑定资源解析失败' : sourceSummary.note}
            </small>
          </div>
        ) : null}
      </div>
    </li>
  );
}

function StructuralFacts({ node, state, dispatch }: { node: DesignerNode; state: DesignerState; dispatch: Dispatch<DesignerAction> }) {
  if (node.kind === 'repeat') {
    return (
      <section className="td-structural-facts" aria-label="循环容器结构说明">
        <div className="td-fact-block">
          <span>循环数据 · 类型校验</span>
          <strong>调用数据中的标签列表</strong>
          <small>每个循环项都是文本；原始循环序号会在过滤后保留</small>
        </div>
        <div className="td-fact-grid">
          <div><span>单项内容</span><strong>2 个直接子节点</strong><small>文本 + 子模板；均使用自动打包布局</small></div>
          <div><span>单项布局</span><strong>横向堆叠</strong><small>间距 1.5mm；先完成每个循环项内部布局</small></div>
          <div><span>实例排列</span><strong>3 列网格</strong><small>间距 1.5mm；再排列所有保留的实例</small></div>
        </div>
        <p>中间项目被条件移除后，视觉位置会重新连续排列，但每项仍保留原始循环序号；没有可见项目时，整个循环容器不参与布局。</p>
        <p className="td-inline-note"><Info size={12} />当前版本不提供筛选、排序、分页、瀑布流或逐项布局覆盖；循环内容直接使用该容器的子节点。</p>
        <div className="td-workflow-actions">
          <button
            type="button"
            className="button ghost-button"
            onClick={() => dispatch({
              type: 'set-notice',
              notice: `Repeat subtree 原子复制:nodeId ${newCanonicalUuid()} · loopId ${newCanonicalUuid()} · bindingId ${newCanonicalUuid()} · 内部 domain refs 同步重写`,
            })}
          >
            <Copy size={12} />成组复制
          </button>
          <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'set-notice', notice: '重新挂载已暂停：需要先修复循环作用域、自动打包方式与父容器布局关系' })}>重新挂载检查</button>
        </div>
      </section>
    );
  }

  if (node.kind === 'templateUse') {
    const isTagUse = node.useId === useIds.tagPill;
    const targetTemplate = isTagUse ? childTemplateIds.tagPill : childTemplateIds.brandBadge;
    return (
      <section className="td-structural-facts" aria-label="子模板调用说明">
        <div className="td-fact-grid td-template-use-steps">
          <button type="button" onClick={() => dispatch({ type: 'set-notice', notice: '只能调用同一工作区中的逻辑子模板当前版本；不支持指定历史版本或动态选择模板' })}>
            <span>1 · 子模板</span><strong>{isTagUse ? '标签胶囊模板' : '品牌角标模板'}</strong><small>同一工作区 · 始终使用当前版本</small>
          </button>
          <button type="button" onClick={() => dispatch({ type: 'set-notice', notice: isTagUse ? '数据上下文使用完整的当前循环项' : '数据上下文使用调用数据中的品牌对象' })}>
            <span>2 · 数据上下文</span><strong>{isTagUse ? '当前循环项' : '调用数据中的品牌对象'}</strong><small>必须明确选择缺失时报错或跳过；不会自动继承父级数据</small>
          </button>
          <button type="button" onClick={() => dispatch({ type: 'set-notice', notice: '自定义数据只按子模板公开参数赋值；名称相同也不会自动传递' })}>
            <span>3 · 自定义数据赋值</span><strong>{isTagUse ? '无额外赋值' : '品牌名称来自调用数据'}</strong><small>只显示子模板公开的参数</small>
          </button>
        </div>
        <p>子模板调用没有自己的子节点、外观或适配面板。自适应尺寸使用子模板裁切区；固定和填充尺寸会完整居中显示，超出宿主区域的内容按裁剪设置处理。</p>
        {state.scenario === 'child-fill-invalid' && !isTagUse ? (
          <p className="td-inline-note chip-error-block"><AlertTriangle size={12} />子模板当前版本已移除目标公开参数；该赋值会使父模板失效，不能静默忽略。</p>
        ) : null}
        <button
          type="button"
          className="button ghost-button"
          onClick={() => dispatch({
            type: 'set-notice',
            notice: `TemplateUse copy:nodeId ${newCanonicalUuid()} · useId ${newCanonicalUuid()} · bindings 全量 remap；target templateId ${targetTemplate} 保持`,
          })}
        >
          <Copy size={12} />复制调用
        </button>
      </section>
    );
  }

  if (node.kind === 'conditional') {
    return (
      <section className="td-structural-facts">
        <p><strong>不参与渲染</strong>时不会生成绘制实例、占用布局或解析运行时资源；但模板自身的缺失引用、失效状态与循环依赖仍会阻止权威预览。</p>
        <p><strong>不可见或完全透明</strong>时仍会生成绘制实例并占用布局，子模板赋值、资源和能力错误仍需处理；“看不见”不等于“没有依赖”。</p>
      </section>
    );
  }

  if (node.kind === 'stack' || node.kind === 'grid' || node.kind === 'frame') {
    return (
      <section className="td-structural-facts" aria-label="约束布局说明">
        <p><strong>固定物理画布内的约束自适应</strong>——不是网页响应式布局，不使用断点、视口百分比或层叠顺序。</p>
        <div className="td-fact-grid">
          <div><span>堆叠填充</span><strong>权重 2 · 最小 10 / 最大 40</strong><small>与其他填充项按剩余毫米空间迭代分配</small></div>
          <div><span>网格轨道</span><strong>2 份 · 自动 · 1 份</strong><small>比例轨道与自动轨道属于模板布局定义</small></div>
          <div><span>带符号外边距</span><strong>允许 -1mm</strong><small>自适应与填充形成循环时会直接报错</small></div>
        </div>
        <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'set-notice', notice: '重新挂载不会沿用旧布局方式：客户端必须让用户确认绝对定位与容器布局之间的显式转换' })}>演示重新挂载转换</button>
      </section>
    );
  }

  if (node.kind === 'text') {
    return (
      <section className="td-structural-facts">
        <p>纯文本会作为一个完整文本片段保存；创建时必须明确选择字体资源。尺寸和间距使用毫米，字号使用点，DPI 只在输出时设置。</p>
        <p>支持横排、竖排、双轴对齐、内边距、描边与自动缩小。显示溢出与最大行数不能同时启用；字体资源失效时模板也会失效。</p>
      </section>
    );
  }

  return null;
}

export function Inspector({
  state,
  dispatch,
  initialMode = 'design',
}: Pick<PartProps, 'state' | 'dispatch'> & { initialMode?: 'design' | 'binding' }) {
  const [mode, setMode] = useState<'design' | 'binding'>(initialMode);
  const node = findNode(state.tree, state.selectedNodeId) ?? state.tree;
  const visibleProps = mode === 'binding' ? node.props.filter((prop) => prop.bindable) : node.props;
  const groupedProps = propertyGroups
    .map((group) => ({ ...group, props: visibleProps.filter((prop) => propertyPresentationFor(prop).group === group.key) }))
    .filter((group) => group.props.length > 0);
  const localizedName = node.name.split(/\s+/).filter((part) => /[\u3400-\u9fff]/.test(part)).join(' ') || '当前节点';
  const kindLabel: Record<DesignerNode['kind'], string> = {
    canvas: '画布',
    group: '分组容器',
    frame: '框架容器',
    stack: '堆叠容器',
    grid: '网格容器',
    text: '文本',
    image: '图片',
    rect: '矩形',
    ellipse: '椭圆',
    line: '直线',
    polygon: '多边形',
    polyline: '折线',
    path: '路径',
    qrCode: '二维码',
    barcode: '条码',
    repeat: '循环容器',
    conditional: '条件容器',
    templateUse: '子模板调用',
  };
  const bindingCount = node.props.filter((prop) => prop.binding).length;
  return (
    <div className="td-inspector" aria-label="属性栏">
      <div className="td-inspector-head">
        <span className={`td-tree-kind kind-${node.kind}`} aria-hidden="true">{kindIcons[node.kind]}</span>
        <div>
          <strong>{localizedName}</strong>
          <small>{kindLabel[node.kind]}{node.children.length > 0 ? ` · ${node.children.length} 个直接子节点` : ''}</small>
        </div>
        {bindingCount > 0 ? (
          <span className="td-inspector-binding-count" title={`${bindingCount} 个属性已绑定`}>
            <Link2 aria-hidden="true" size={11} />
            {bindingCount}
          </span>
        ) : null}
      </div>
      <div className="rwtd-inspector-tabs" role="tablist" aria-label="检查器模式">
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'design'}
          className={mode === 'design' ? 'active' : ''}
          onClick={() => setMode('design')}
        >
          设计
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'binding'}
          className={mode === 'binding' ? 'active' : ''}
          onClick={() => setMode('binding')}
        >
          绑定
        </button>
      </div>
      <div className="td-inspector-section">
        <div className="td-inspector-intro">
          <strong>{mode === 'design' ? '属性' : '可绑定属性'}</strong>
          <span>{mode === 'design' ? '按用途分组' : '由全局绑定策略授权'}</span>
        </div>
        <div className="td-prop-groups">
          {groupedProps.map((group) => (
            <section key={group.key} className="td-prop-group" aria-labelledby={`property-group-${group.key}`}>
              <div className="td-prop-group-head">
                <h3 id={`property-group-${group.key}`}>{group.label}</h3>
                <span>{group.props.length} 项</span>
              </div>
              <ul className="td-prop-list">
                {group.props.map((prop) => (
                  <InspectorPropRow key={prop.label} node={node} prop={prop} state={state} dispatch={dispatch} />
                ))}
              </ul>
            </section>
          ))}
        </div>
      </div>
      {mode === 'design' ? <StructuralFacts node={node} state={state} dispatch={dispatch} /> : null}
      {mode === 'binding' && node.kind !== 'canvas' ? (
        <div className="td-inspector-section">
          <span className="td-panel-subhead">通用目标 · non-Canvas</span>
          <div className="td-generic-targets">
            {[
              { target: 'render', label: '参与渲染' },
              { target: 'visible', label: '可见性' },
              { target: 'opacity', label: '不透明度' },
              { target: 'transform.rotationDeg', label: '旋转角度' },
            ].map(({ target, label }) => (
              <button key={target} type="button" className="td-mini-chip" onClick={() => dispatch({ type: 'mark-dirty' })}>
                <Link2 aria-hidden="true" size={10} />
                {label}
              </button>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}

type BindingSourceKind = 'context' | 'loopIndex' | 'definition';

interface TargetSelector {
  kind: 'MEMBER' | 'INDEX';
  memberId?: string;
  index?: number;
}

function targetPropertyRefOf(label: string): { rootPropertyId: string; selectors: TargetSelector[] } {
  const rootPropertyId = label.match(/^[^.[]+/)?.[0] ?? label;
  const selectors: TargetSelector[] = [];
  const tail = label.slice(rootPropertyId.length);
  const selectorPattern = /\.([^.[]+)|\[(\d+)\]/g;
  for (const match of tail.matchAll(selectorPattern)) {
    if (match[1]) selectors.push({ kind: 'MEMBER', memberId: match[1] });
    if (match[2]) selectors.push({ kind: 'INDEX', index: Number(match[2]) });
  }
  return { rootPropertyId, selectors };
}

function sourceKindOf(source: string | undefined): BindingSourceKind {
  if (source?.startsWith('definition(')) return 'definition';
  if (source?.startsWith('loopIndex(')) return 'loopIndex';
  return 'context';
}

function sourceDraftOf(source: string | undefined, kind: BindingSourceKind): string {
  if (!source) return '';
  if (kind === 'definition') return source.match(/^definition\((.+)\)$/)?.[1] ?? source;
  if (kind === 'loopIndex') return source.match(/^loopIndex\((.+)\)$/)?.[1] ?? source;
  return source;
}

function targetTypeOf(label: string): string {
  if (label.endsWith('fontRef')) return 'fontRef';
  if (label.endsWith('imageRef')) return 'imageRef';
  if (label.endsWith('text') || label === 'content') return 'text';
  if (label.includes('color')) return 'color';
  if (label === 'visible' || label === 'render' || label === 'shrinkToFit') return 'boolean';
  return 'decimal / exact enum (按 Policy)';
}

function BindingDialogContent({
  target,
  node,
  prop,
  dispatch,
}: {
  target: { nodeId: string; label: string };
  node: DesignerNode;
  prop: InspectorProp;
  dispatch: Dispatch<DesignerAction>;
}) {
  const initialKind = sourceKindOf(prop.binding?.source);
  const [draftSource, setDraftSource] = useState(() => sourceDraftOf(prop.binding?.source, initialKind));
  const [draftKind, setDraftKind] = useState<BindingSourceKind>(initialKind);
  const targetPropertyRef = targetPropertyRefOf(prop.label);

  const save = () => {
    const trimmed = draftSource.trim();
    if (!trimmed) return;
    const source = draftKind === 'definition'
      ? `definition(${trimmed})`
      : draftKind === 'loopIndex'
        ? `loopIndex(${trimmed})`
        : trimmed;
    dispatch({
      type: 'save-binding',
      nodeId: target.nodeId,
      label: target.label,
      source,
      bindingId: prop.binding?.id ?? newCanonicalUuid(),
    });
  };

  return (
    <div className="td-dialog-overlay">
      <div className="td-dialog td-binding-dialog" role="dialog" aria-modal="true" aria-labelledby="td-binding-title">
        <h2 id="td-binding-title">{prop.binding ? '编辑 Binding' : '新增 Binding'}</h2>
        <div className="td-binding-target">
          <span className="td-panel-subhead">node-local targetPropertyRef</span>
          <div className="td-binding-target-grid">
            <code>{JSON.stringify(targetPropertyRef, null, 2)}</code>
            <div>
              <strong>{node.kind} · {prop.label}</strong>
              <small>targetType <code>{targetTypeOf(prop.label)}</code></small>
              <small>BindingPolicyCatalog 命中；host node 隐式,wire 无 nodeId / slotId / policyId。</small>
            </div>
          </div>
        </div>
        <div className="td-target-shapes" aria-label="支持的 targetPropertyRef 形状">
          {['property', 'property.member', 'property[index]', 'property[index].member', 'property.member[index]'].map((shape) => <code key={shape}>{shape}</code>)}
        </div>
        <label className="td-form-row">
          <span>ValueSource</span>
          <select value={draftKind} onChange={(event) => setDraftKind(event.target.value as BindingSourceKind)}>
            <option value="context">typed context path</option>
            <option value="loopIndex">loopIndex · decimal</option>
            <option value="definition">DefinitionRef</option>
          </select>
        </label>
        <label className="td-form-row">
          <span>来源</span>
          <input
            value={draftSource}
            onChange={(event) => setDraftSource(event.target.value)}
            placeholder={draftKind === 'context' ? '/title' : draftKind === 'definition' ? definitionIds.priceText : loopIds.tags}
          />
        </label>
        <p className="td-panel-note">literal 应直接编辑 static baseline；Capability 只能作为 Expression input,不会出现在 Binding source picker。</p>
        {targetPropertyRef.selectors.some((selector) => selector.kind === 'INDEX') ? (
          <div className="td-reorder-note">
            <strong>数组位置没有 item identity</strong>
            <span>重排默认仍指向 numeric index。若作者选择“保持原 Run”,客户端须在同一操作把 INDEX 0 原子改写为新下标。</span>
            <button type="button" className="td-mini-chip" onClick={() => dispatch({ type: 'set-notice', notice: '模拟重排:runs[0] → runs[1] 与 targetPropertyRef INDEX 0 → 1 已原子改写' })}>模拟保持原 item</button>
          </div>
        ) : null}
        <p className="td-inline-note chip-error-block">
          <AlertTriangle aria-hidden="true" size={12} />
          越界、duplicate target、ancestor/descendant overlap 是不可确认 hard error；Binding ABSENT/ERROR/类型不符不回退 baseline。
        </p>
        <div className="td-dialog-actions">
          {prop.binding ? (
            <button
              type="button"
              className="button td-danger-button"
              onClick={() => dispatch({ type: 'remove-binding', nodeId: target.nodeId, label: target.label })}
            >
              删除 Binding
            </button>
          ) : null}
          <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'close-binding' })}>
            取消
          </button>
          <button type="button" className="button primary-button" onClick={save} disabled={!draftSource.trim()}>
            保存 Binding
          </button>
        </div>
      </div>
    </div>
  );
}

export function BindingDialog({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const target = state.bindingEditor;
  const node = target ? findNode(state.tree, target.nodeId) : null;
  const prop = node?.props.find((entry) => entry.label === target?.label);
  if (!target || !node || !prop) return null;
  return (
    <BindingDialogContent
      key={`${target.nodeId}:${target.label}:${prop.binding?.id ?? 'new'}`}
      target={target}
      node={node}
      prop={prop}
      dispatch={dispatch}
    />
  );
}

export function ProblemRow({ problem, dispatch }: { problem: DesignerProblem; dispatch: Dispatch<DesignerAction> }) {
  return (
    <button
      type="button"
      className={`td-problem-row sev-${problem.severity}`}
      onClick={() => problem.nodeId && dispatch({ type: 'select-node', nodeId: problem.nodeId })}
    >
      <span className="td-problem-icon" aria-hidden="true">
        <AlertTriangle size={14} />
      </span>
      <span className="td-problem-body">
        <span className="td-problem-head">
          <code>{problem.code}</code>
          <em>{problem.severity === 'hard' ? 'hard error' : problem.severity === 'dependency' ? '依赖 ERROR' : '运行时'}</em>
        </span>
        <span className="td-problem-msg">{problem.message}</span>
        <span className="td-problem-loc">
          <code>{problem.pointer}</code>
          {problem.definitionId ? <code>definitionId {problem.definitionId}</code> : null}
          {problem.bindingId ? <code>{problem.bindingId}</code> : null}
          {problem.span ? <code>{problem.span}</code> : null}
        </span>
      </span>
    </button>
  );
}

export function ProblemsList({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const problems = problemsFor(state.scenario);
  if (state.scenario === 'conflict') {
    return <p className="td-inline-note">本地草稿与 current 存在 revision 分歧;问题列表以待重检为准。</p>;
  }
  if (problems.length === 0) {
    return (
      <p className="td-problems-ok">
        <Check aria-hidden="true" size={14} />
        服务端权威校验通过 · 0 问题(current 重检)
      </p>
    );
  }
  return (
    <div className="td-problem-list" role="list" aria-label="问题列表">
      {problems.map((problem) => (
        <ProblemRow key={problem.code} problem={problem} dispatch={dispatch} />
      ))}
    </div>
  );
}

export function PreviewArt() {
  return (
    <svg className="td-preview-art" viewBox="0 0 90 54" role="img" aria-label="已核验权威图片的 UI 缩略示意:活动价签">
      <rect className="pa-bg" x="0" y="0" width="90" height="54" rx="2" />
      <rect className="pa-band" x="4" y="11.5" width="50" height="15" rx="2" />
      <text className="pa-title" x="4" y="9">春季新品发布会</text>
      <text className="pa-price" x="6.5" y="22.5">¥ 199.00</text>
      <text className="pa-note" x="40" y="21">起</text>
      <text className="pa-date" x="4" y="33">2026-08-01</text>
      <rect className="pa-chip" x="4" y="36" width="14" height="7" rx="3.5" />
      <text className="pa-chip-text" x="11" y="41">新品</text>
      <rect className="pa-chip" x="20" y="36" width="14" height="7" rx="3.5" />
      <text className="pa-chip-text" x="27" y="41">限量</text>
      <rect className="pa-chip" x="36" y="36" width="14" height="7" rx="3.5" />
      <text className="pa-chip-text" x="43" y="41">会员</text>
      <circle className="pa-logo" cx="76" cy="14" r="8" />
      <text className="pa-logo-text" x="76" y="16.5">HB</text>
      <g className="pa-qr">
        <rect x="66" y="26" width="20" height="20" rx="1" />
        <rect x="68" y="28" width="5" height="5" />
        <rect x="79" y="28" width="5" height="5" />
        <rect x="68" y="39" width="5" height="5" />
        <rect x="75" y="31" width="2.5" height="2.5" />
        <rect x="79" y="36" width="2.5" height="2.5" />
        <rect x="75" y="40" width="2.5" height="2.5" />
        <rect x="82" y="41" width="2" height="2" />
      </g>
      <text className="pa-badge" x="4" y="51">海博优选 · 品牌角标</text>
    </svg>
  );
}

function outputPixels(mm: number, dpi: number): number {
  return Math.floor((mm * dpi) / 25.4 + 0.5);
}

export function PreviewPanel({ state, dispatch, onRunPreview, onCancelPreview }: PartProps) {
  const problems = problemsFor(state.scenario);
  const widthPx = outputPixels(templateMeta.canvasMm.width, state.dpi);
  const heightPx = outputPixels(templateMeta.canvasMm.height, state.dpi);
  return (
    <div className="td-preview" aria-label="权威预览">
      <div className="td-preview-head">
        <span className="td-panel-subhead">权威预览(服务端重检 current 闭包)</span>
      </div>
      <div className="td-preview-controls">
        <div className="td-format-toggle" role="group" aria-label="输出格式">
          {(['PNG', 'JPEG'] as const).map((format) => (
            <button
              key={format}
              type="button"
              className={state.outputFormat === format ? 'active' : ''}
              aria-pressed={state.outputFormat === format}
              onClick={() => dispatch({ type: 'set-output-format', format })}
            >
              {format}
            </button>
          ))}
        </div>
        <label className="td-preview-number">
          <span>DPI</span>
          <input
            type="number"
            min="72"
            max="1200"
            step="1"
            value={state.dpi}
            onChange={(event) => dispatch({ type: 'set-dpi', dpi: Number(event.target.value) })}
            aria-label="预览 DPI"
          />
        </label>
        {state.outputFormat === 'JPEG' ? (
          <label className="td-preview-number">
            <span>Quality</span>
            <input
              type="number"
              min="1"
              max="100"
              step="1"
              value={state.jpegQuality}
              onChange={(event) => dispatch({ type: 'set-jpeg-quality', quality: Number(event.target.value) })}
              aria-label="JPEG quality"
            />
          </label>
        ) : null}
        <label className="td-trace-toggle">
          <input
            type="checkbox"
            checked={state.layoutTrace}
            onChange={(event) => dispatch({ type: 'set-layout-trace', enabled: event.target.checked })}
          />
          有界 LayoutTrace
        </label>
        {state.previewPhase === 'loading' ? (
          <button type="button" className="button td-danger-button td-preview-run" onClick={onCancelPreview}>
            <X aria-hidden="true" size={14} />取消
          </button>
        ) : (
          <button type="button" className="button primary-button td-preview-run" onClick={onRunPreview}>
            <Play aria-hidden="true" size={14} />运行
          </button>
        )}
      </div>
      <p className="td-preview-note">
        只提交 PNG/JPEG、正整数 DPI 与 JPEG quality；省略时有效值 96/90。一次 operation 只返回根 Canvas 完整一张图,无 node export/crop/target size/background override/batch。
      </p>
      <div className="td-preview-stage">
        {state.previewPhase === 'idle' && (
          <p className="td-preview-empty">尚未运行;编辑器打开时已权威重检最新 current。</p>
        )}
        {state.previewPhase === 'loading' && (
          <p className="td-preview-empty">
            <RefreshCw aria-hidden="true" size={14} className="td-spin" />
            完整 Evaluator + RenderEngine 路径中…旧结果已撤下；取消需在 atomic output seal 前胜出。
          </p>
        )}
        {state.previewPhase === 'ok' && (
          <>
            <div className="td-preview-result-head">
              <span className="td-mini-chip chip-ok">完整 length + digest 已核验</span>
              <strong>{state.outputFormat} · {widthPx}×{heightPx}px · {state.dpi} DPI</strong>
              <small>{state.outputFormat === 'PNG' ? 'RGBA8 · alpha 保留' : `RGB8 · opaque matte · quality ${state.jpegQuality}`}</small>
            </div>
            <div className="td-preview-image-wrap">
              <span>已解码图片缩略示意</span>
              <PreviewArt />
            </div>
            {state.layoutTrace ? (
              <div className="td-preview-trace">
                <strong>LayoutTrace · 成功结果 sidecar</strong>
                <code>root › titleText · box 4,4,58,6mm · paint 1</code>
                <code>root › Repeat tagLoop › item[2] › tagPillUse › child · clip host</code>
                <small>14 occurrences · overflow 0 · clip 1 · paint 11 · 已由服务端投影获准路径,无 opaque occurrenceId、原始输入或完整 sidecar。</small>
              </div>
            ) : (
              <p className="td-preview-trace-off">LayoutTrace 未请求(默认 NONE)。</p>
            )}
            <p className="td-preview-capability">本次预览建立了新的 CapabilityState #{state.previewGeneration}；Clock/Random 可与上次不同,EditorSession 不 pin time/seed。</p>
          </>
        )}
        {state.previewPhase === 'failed' && (
          <div className="td-preview-failed">
            <p className="td-inline-note chip-error-block">
              <AlertTriangle aria-hidden="true" size={12} />
              权威预览失败 — 不保留旧 Scene 冒充当前结果
            </p>
            {problems.map((problem) => (
              <ProblemRow key={problem.code} problem={problem} dispatch={dispatch} />
            ))}
          </div>
        )}
        {state.previewPhase === 'blocked' && (
          <p className="td-preview-empty">
            {state.templateStatus === 'INVALID' || state.scenario === 'layout-error'
              ? 'INVALID / hard error · 不可权威预览或 Render(可继续编辑)'
              : '存在 revision 冲突 · 先重新载入 latest 再预览'}
          </p>
        )}
        {state.previewPhase === 'cancelled' && (
          <p className="td-preview-empty">
            <X aria-hidden="true" size={14} />
            预览已取消 · 零 Output / 零 partial bytes / 零失败 trace；不会回显旧权威图片。
          </p>
        )}
      </div>
      <div className="td-preview-failure-policy" aria-label="权威预览失败策略">
        {['网络截断', 'deadline', 'cancel', 'trace 超限', '任一 Engine problem'].map((failure) => <code key={failure}>{failure} → 撤下旧图 · 零 partial</code>)}
      </div>
      <p className="td-preview-note">作者 UI 不显示 Engine requestId、Command/RenderDocument digest、Renderer Profile 内部选择、fetch URL/token/hash/version 或 exact replay control。</p>
    </div>
  );
}

export function ConflictBanner({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  if (state.savePhase !== 'rejected-conflict') return null;
  return (
    <div className="td-conflict-banner" role="alert">
      <AlertTriangle aria-hidden="true" size={15} />
      <span>
        保存被拒:expectedRevision <strong>{state.expectedRevision}</strong> ≠ current <strong>{state.currentRevision}</strong> — 他处已保存更新。
      </span>
      <button type="button" className="button primary-button" onClick={() => dispatch({ type: 'reload-latest' })}>
        重新载入最新
      </button>
      <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'cancel-save' })}>
        保留本地草稿
      </button>
    </div>
  );
}

export function NoticeToast({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  if (!state.notice) return null;
  return (
    <div className="td-toast" role="status">
      <Check aria-hidden="true" size={14} />
      <span>{state.notice}</span>
      <button type="button" aria-label="关闭提示" onClick={() => dispatch({ type: 'dismiss-notice' })}>
        <X aria-hidden="true" size={14} />
      </button>
    </div>
  );
}

export function InvalidSaveDialog({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  if (state.savePhase !== 'confirm-invalid') return null;
  const problems = problemsFor(state.scenario);
  return (
    <div className="td-dialog-overlay">
      <div className="td-dialog" role="alertdialog" aria-modal="true" aria-labelledby="td-dialog-title">
        <h2 id="td-dialog-title">确认保存为 INVALID?</h2>
        <p>存在依赖 ERROR。精确问题集将随 revision 记录;保存后可继续编辑,但不可权威预览 / Render。</p>
        <div className="td-dialog-problems">
          {problems.map((problem) => (
            <code key={problem.code}>{problem.code} · {problem.pointer}</code>
          ))}
        </div>
        <div className="td-dialog-actions">
          <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'cancel-save' })}>
            取消
          </button>
          <button type="button" className="button td-danger-button" onClick={() => dispatch({ type: 'confirm-invalid-save' })}>
            二次确认 · 保存为 INVALID
          </button>
        </div>
      </div>
    </div>
  );
}

export function LeftPanel({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const currentTab = leftTabs.find((tab) => tab.key === state.leftTab) ?? leftTabs[0];
  return (
    <div className="td-left-panel">
      <header className="rwtd-panel-header">
        <span className="rwtd-panel-title">
          {currentTab?.icon}
          <strong>{currentTab?.label}</strong>
        </span>
        <span className="rwtd-panel-context">Template</span>
      </header>
      <div className="rwtd-panel-scroll" tabIndex={0} aria-label={`${currentTab?.label}内容`}>
        {state.leftTab === 'library' && <LibraryPanel dispatch={dispatch} />}
        {state.leftTab === 'tree' && <NodeTree state={state} dispatch={dispatch} />}
        {state.leftTab === 'assets' && <AssetsPanel state={state} dispatch={dispatch} />}
        {state.leftTab === 'definitions' && <DefinitionsPanel dispatch={dispatch} />}
        {state.leftTab === 'data' && <DataPanel state={state} />}
        {state.leftTab === 'exchange' && <ExchangePanel state={state} dispatch={dispatch} />}
      </div>
    </div>
  );
}

export function RailButtons({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  return (
    <nav className="td-rail" aria-label="编辑器资源">
      {leftTabs.map((tab) => (
        <button
          key={tab.key}
          type="button"
          className={`td-rail-button${state.leftTab === tab.key ? ' active' : ''}`}
          aria-pressed={state.leftTab === tab.key}
          aria-label={tab.label}
          title={tab.label}
          onClick={() => dispatch({ type: 'set-tab', tab: tab.key })}
        >
          {tab.icon}
          <span>{tab.label}</span>
        </button>
      ))}
    </nav>
  );
}
