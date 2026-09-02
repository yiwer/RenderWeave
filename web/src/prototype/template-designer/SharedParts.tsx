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
  Puzzle,
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

import {
  projectPrototypeConditional,
  projectPrototypeConditionalRuntime,
  prototypeConditionalSources,
  type ConditionalOutcome,
} from './conditional-layout';
import { kindIcons } from './kind-icons';
import { projectPrototypeRepeat, type RepeatPackingTrace } from './repeat-layout';
import {
  parsePrototypeGridTrackToken,
  projectPrototypeLayout,
  PROTOTYPE_GRID_TRACK_LIMIT,
  prototypeGridTrackTokens,
  serializePrototypeGridTrackTokens,
} from './stack-layout';
import { bindingTargetValueType, prototypeBindingSourceOptions } from './binding-options';

import {
  assetIds,
  assets,
  canvasProjection,
  childTemplateIds,
  customValuesSample,
  definitionDomainLabel,
  definitionValueType,
  definitions,
  findNode,
  findParentNode,
  isLayoutManagingNode,
  nestedTemplates,
  nodeCatalog,
  nodeGroupLabels,
  nodeIds,
  problemsFor,
  repeatSourcesForDefinitions,
  repeatTemplateCandidates,
  rootDocumentSample,
  schemaFields,
  templateMeta,
  templateUseContextSources,
  valueSourceSummary,
  type DesignerAction,
  type DesignerAsset,
  type DesignerNode,
  type DesignerProblem,
  type DesignerState,
  type InspectorProp,
  type LeftTab,
  type NodeBinding,
  type NodeGroup,
  type NodeKind,
  type PrototypeRepeatSource,
  scenarios,
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
  const canvas = canvasProjection(state.tree);
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
          <strong className="td-chrome-name">活动价签 · {canvas.widthMm}×{canvas.heightMm}mm</strong>
          <span>{layoutName} · {canvas.widthMm} × {canvas.heightMm} mm</span>
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
        Text 在作者侧只呈现一个文本值，并要求选择一个 ACTIVE 的 FONT 资产；若无可用字体，创建结果为 INVALID 且不能权威预览。
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
              <span className={`td-mini-chip ${definition.kind === 'CUSTOM' && definition.exposure === 'PUBLIC' ? 'chip-info' : ''}`}>
                {definition.kind === 'CUSTOM' ? definition.exposure : definitionValueType(definition)}
              </span>
            </div>
            <code>{definition.id}</code>
            <small>{definitionDomainLabel(definition)}</small>
            <small className="td-def-detail">{definition.detail}</small>
            {definition.kind === 'EXPRESSION' ? (
              <span className="td-def-inputs">
                {definition.inputs.map((input) => <code key={input.alias}>{input.alias} · {valueSourceSummary(input.source)}</code>)}
              </span>
            ) : definition.kind === 'MAPPING' ? (
              <span className="td-def-inputs"><code>input · {valueSourceSummary(definition.input)}</code></span>
            ) : (
              <small>defaultValue 是 authored typed literal；只有 PUBLIC 接收根 override / child fill。</small>
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
        PRIVATE <code>brandName</code> 与未知 <code>definitionId</code> 在 envelope 通过后静默忽略；PUBLIC assignment 才覆盖默认值。
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
          <code>文本字体 → 字体资产（需要选择 FONT）</code>
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
  const canvas = canvasProjection(state.tree);
  const width = canvas.widthMm * pxPerMm;
  const height = canvas.heightMm * pxPerMm;
  return (
    <div className={`td-artboard-wrap${compact ? ' compact' : ''}`}>
      <div className="td-artboard-meta">
        <span className="td-mini-chip chip-warn">草稿画布 · 浏览器派生 · 非权威</span>
        <span className="td-mini-chip">server authority · renderweave-layout/1.0</span>
        <span className="td-mini-chip">{canvas.widthMm}×{canvas.heightMm}mm · pt 字号</span>
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
          <span className="td-artboard-size" aria-hidden="true">{canvas.widthMm}mm × {canvas.heightMm}mm</span>
          {state.boxes.map((box) => {
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

type PropertyGroupKey = 'content' | 'typography' | 'geometry' | 'layout' | 'appearance' | 'data' | 'behavior' | 'composition' | 'advanced';
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
  { key: 'typography', label: '文字与排版' },
  { key: 'layout', label: '布局与约束' },
  { key: 'geometry', label: '位置与尺寸' },
  { key: 'appearance', label: '外观' },
  { key: 'data', label: '数据' },
  { key: 'behavior', label: '行为与可见性' },
  { key: 'composition', label: '子模板' },
  { key: 'advanced', label: '高级' },
];

const propertyOrder = [
  // Content: edit the thing users see before tuning how it is laid out.
  'runs[0].text',
  'content',
  'code',
  'imageRef',
  'symbology',
  'errorCorrectionLevel',
  'quietZoneMm',
  'preset',
  'pointsCount',
  'innerRadiusMm',
  'points',
  'closed',
  'pathData',
  // Typography: asset and scale first, then flow, alignment, and overflow policy.
  'runs[0].fontRef',
  'runs[0].fontSizePt',
  'runs[0].color',
  'writingMode',
  'horizontalAlign',
  'verticalAlign',
  'lineBreak',
  'maxLines',
  'overflow',
  'shrinkToFit',
  // Layout: container model, tracks/flow, spacing, then child constraints.
  'layoutMode',
  'direction',
  'columns',
  'rows',
  'autoFlow',
  'gapMm',
  'columnGapMm',
  'rowGapMm',
  'mainAlign',
  'crossAlign',
  'padding.topMm',
  'padding.rightMm',
  'padding.bottomMm',
  'padding.leftMm',
  'placement.widthMode',
  'placement.heightMode',
  'placement.minWidthMm',
  'placement.maxWidthMm',
  'placement.minHeightMm',
  'placement.maxHeightMm',
  'placement.alignSelf',
  'placement.fillWeight',
  'placement.row',
  'placement.column',
  'placement.rowSpan',
  'placement.columnSpan',
  'placement.horizontalAlignSelf',
  'placement.verticalAlignSelf',
  'placement.marginTopMm',
  'placement.marginRightMm',
  'placement.marginBottomMm',
  'placement.marginLeftMm',
  'itemLayout.kind',
  'itemLayout.direction',
  'itemLayout.gapMm',
  'itemLayout.columns',
  'itemLayout.columnGapMm',
  'itemLayout.rowGapMm',
  'instanceLayout.kind',
  'instanceLayout.direction',
  'instanceLayout.gapMm',
  'instanceLayout.columns',
  'instanceLayout.columnGapMm',
  'instanceLayout.rowGapMm',
  'placement.type',
  // Geometry: size before coordinates mirrors the most common resize workflow.
  'widthMm',
  'heightMm',
  'xMm',
  'yMm',
  'rotationDeg',
  // Appearance: paint, border, corners, opacity, then kind-specific rendering.
  'backgroundColor',
  'fill.color',
  'foregroundColor',
  'stroke.widthMm',
  'stroke.color',
  'cornerRadiusMm',
  'cornerRadii.topLeftMm',
  'cornerRadii.topRightMm',
  'cornerRadii.bottomRightMm',
  'cornerRadii.bottomLeftMm',
  'opacity',
  'fit',
  'lineCap',
  'lineJoin',
  'startArrow',
  'endArrow',
  'fillRule',
  'showText',
  'barWidthMm',
  // Data and behavior keep source selection ahead of fallback policies.
  'items',
  'contextSelector',
  'absentPolicy',
  'contextAbsentPolicy',
  'visible',
  'render',
  'condition',
  'clipContent',
  'locked',
  // Composition and expert-only rendering details stay last.
  'templateRef.templateId',
  'fills',
  'fill: brandName',
  'sampling',
] as const;

const propertyOrderByLabel = new Map<string, number>(propertyOrder.map((label, index) => [label, index]));

const propertyPresentations: Record<string, PropertyPresentation> = {
  xMm: { name: 'X', group: 'geometry', control: 'number', unit: 'mm', step: '0.1' },
  yMm: { name: 'Y', group: 'geometry', control: 'number', unit: 'mm', step: '0.1' },
  widthMm: { name: '宽度', group: 'geometry', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  heightMm: { name: '高度', group: 'geometry', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  rotationDeg: { name: '旋转', group: 'geometry', control: 'number', unit: '°', step: '1' },
  opacity: { name: '不透明度', group: 'appearance', control: 'number', unit: '%', step: '1', min: 0 },
  locked: { name: '锁定图层', group: 'behavior', control: 'boolean' },
  backgroundColor: { name: '背景颜色', group: 'appearance', control: 'color' },
  direction: { name: '排列方向', group: 'layout', control: 'select' },
  gapMm: { name: '项目间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'padding.topMm': { name: '上内边距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'padding.rightMm': { name: '右内边距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'padding.bottomMm': { name: '下内边距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'padding.leftMm': { name: '左内边距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'fill.color': { name: '填充颜色', group: 'appearance', control: 'color' },
  'cornerRadii.topLeftMm': { name: '左上圆角', group: 'appearance', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'cornerRadii.topRightMm': { name: '右上圆角', group: 'appearance', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'cornerRadii.bottomRightMm': { name: '右下圆角', group: 'appearance', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'cornerRadii.bottomLeftMm': { name: '左下圆角', group: 'appearance', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'placement.widthMode': { name: '宽度方式', group: 'layout', control: 'select' },
  'placement.heightMode': { name: '高度方式', group: 'layout', control: 'select' },
  'placement.minWidthMm': { name: '最小宽度', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'placement.maxWidthMm': { name: '最大宽度', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'placement.minHeightMm': { name: '最小高度', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'placement.maxHeightMm': { name: '最大高度', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'placement.marginTopMm': { name: '上外边距', group: 'layout', control: 'number', unit: 'mm', step: '0.1' },
  'placement.marginRightMm': { name: '右外边距', group: 'layout', control: 'number', unit: 'mm', step: '0.1' },
  'placement.marginBottomMm': { name: '下外边距', group: 'layout', control: 'number', unit: 'mm', step: '0.1' },
  'placement.marginLeftMm': { name: '左外边距', group: 'layout', control: 'number', unit: 'mm', step: '0.1' },
  'placement.alignSelf': { name: '单项交叉轴对齐', group: 'layout', control: 'select' },
  'placement.fillWeight': { name: '主轴填充权重', group: 'layout', control: 'number', step: '0.1', min: 0.1 },
  'placement.row': { name: '起始行', group: 'layout', control: 'number', unit: '行', step: '1', min: 0 },
  'placement.column': { name: '起始列', group: 'layout', control: 'number', unit: '列', step: '1', min: 0 },
  'placement.rowSpan': { name: '跨行数', group: 'layout', control: 'number', unit: '行', step: '1', min: 1 },
  'placement.columnSpan': { name: '跨列数', group: 'layout', control: 'number', unit: '列', step: '1', min: 1 },
  'placement.horizontalAlignSelf': { name: '单元内水平对齐', group: 'layout', control: 'select' },
  'placement.verticalAlignSelf': { name: '单元内垂直对齐', group: 'layout', control: 'select' },
  'runs[0].text': { name: '文本值', group: 'content', control: 'multiline' },
  'runs[0].fontRef': { name: '字体资产', group: 'typography', control: 'font' },
  'runs[0].fontSizePt': { name: '字号', group: 'typography', control: 'number', unit: 'pt', step: '0.5', min: 0 },
  'runs[0].color': { name: '文字颜色', group: 'typography', control: 'color' },
  writingMode: { name: '书写方向', group: 'typography', control: 'select' },
  lineBreak: { name: '换行方式', group: 'typography', control: 'select' },
  overflow: { name: '溢出处理', group: 'typography', control: 'select' },
  horizontalAlign: { name: '水平对齐', group: 'typography', control: 'select' },
  verticalAlign: { name: '垂直对齐', group: 'typography', control: 'select' },
  'stroke.widthMm': { name: '描边宽度', group: 'appearance', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'stroke.color': { name: '描边颜色', group: 'appearance', control: 'color' },
  cornerRadiusMm: { name: '图片圆角', group: 'appearance', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  innerRadiusMm: { name: '内半径', group: 'content', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  shrinkToFit: { name: '自动缩小', group: 'typography', control: 'boolean' },
  imageRef: { name: '图片资源', group: 'content', control: 'asset' },
  fit: { name: '图片适配', group: 'appearance', control: 'select' },
  sampling: { name: '采样方式', group: 'advanced', control: 'select' },
  maxLines: { name: '最大行数', group: 'typography', control: 'number', unit: '行', step: '1', min: 1 },
  items: { name: '循环数据', group: 'data', control: 'source' },
  absentPolicy: { name: '缺失值处理', group: 'data', control: 'select' },
  'itemLayout.kind': { name: '单项布局', group: 'layout', control: 'select' },
  'itemLayout.direction': { name: '单项方向', group: 'layout', control: 'select' },
  'itemLayout.gapMm': { name: '单项间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'itemLayout.columns': { name: '单项网格列数', group: 'layout', control: 'number', unit: '列', step: '1', min: 1 },
  'itemLayout.columnGapMm': { name: '单项列间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'itemLayout.rowGapMm': { name: '单项行间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'instanceLayout.kind': { name: '实例布局', group: 'layout', control: 'select' },
  'instanceLayout.direction': { name: '实例方向', group: 'layout', control: 'select' },
  'instanceLayout.gapMm': { name: '实例间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'instanceLayout.columns': { name: '实例网格列数', group: 'layout', control: 'number', unit: '列', step: '1', min: 1 },
  'instanceLayout.columnGapMm': { name: '实例列间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'instanceLayout.rowGapMm': { name: '实例行间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  'placement.type': { name: '布局方式', group: 'layout', control: 'readonly' },
  'templateRef.templateId': { name: '目标模板', group: 'composition', control: 'template' },
  contextSelector: { name: '数据上下文', group: 'data', control: 'source' },
  contextAbsentPolicy: { name: '上下文缺失', group: 'data', control: 'select' },
  fills: { name: '自定义数据赋值', group: 'composition', control: 'readonly' },
  'fill: brandName': { name: '品牌名称赋值', group: 'composition', control: 'source' },
  content: { name: '二维码内容', group: 'content', control: 'text' },
  errorCorrectionLevel: { name: '容错等级', group: 'content', control: 'select' },
  foregroundColor: { name: '前景颜色', group: 'appearance', control: 'color' },
  lineCap: { name: '线帽', group: 'appearance', control: 'select' },
  startArrow: { name: '起点箭头', group: 'appearance', control: 'select' },
  endArrow: { name: '终点箭头', group: 'appearance', control: 'select' },
  points: { name: '点集', group: 'content', control: 'readonly' },
  closed: { name: '闭合路径', group: 'content', control: 'boolean' },
  lineJoin: { name: '转角', group: 'appearance', control: 'select' },
  pathData: { name: 'Path Data', group: 'content', control: 'readonly' },
  fillRule: { name: '填充规则', group: 'appearance', control: 'select' },
  preset: { name: '形状预设', group: 'content', control: 'select' },
  pointsCount: { name: '角 / 边数量', group: 'content', control: 'number', step: '1', min: 3 },
  quietZoneMm: { name: '静区', group: 'content', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  code: { name: '条码内容', group: 'content', control: 'text' },
  symbology: { name: '条码制式', group: 'content', control: 'select' },
  showText: { name: '显示码文', group: 'appearance', control: 'boolean' },
  barWidthMm: { name: '条宽', group: 'appearance', control: 'number', unit: 'mm', step: '0.05', min: 0 },
  layoutMode: { name: '布局方式', group: 'layout', control: 'readonly' },
  clipContent: { name: '裁剪内容', group: 'behavior', control: 'boolean' },
  mainAlign: { name: '主轴对齐', group: 'layout', control: 'select' },
  crossAlign: { name: '交叉轴对齐', group: 'layout', control: 'select' },
  columns: { name: '列轨道', group: 'layout', control: 'text' },
  rows: { name: '行轨道', group: 'layout', control: 'text' },
  columnGapMm: { name: '列间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  rowGapMm: { name: '行间距', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 0 },
  autoFlow: { name: '自动排列方向', group: 'layout', control: 'select' },
  render: { name: '参与渲染', group: 'behavior', control: 'boolean' },
  condition: { name: '渲染条件', group: 'behavior', control: 'source' },
  visible: { name: '可见性', group: 'behavior', control: 'boolean' },
};

const propertyOptionLabels: Record<string, Record<string, string>> = {
  layoutMode: { FREE: '自由定位', STACK: '堆叠布局', GRID: '网格布局' },
  direction: { VERTICAL: '纵向', HORIZONTAL: '横向' },
  writingMode: { HORIZONTAL_TB: '横排', VERTICAL_RL: '竖排（从右向左）' },
  lineBreak: { NONE: '不换行', WORD: '按词换行', CHAR: '按字符换行' },
  overflow: { VISIBLE: '显示溢出', CLIP: '裁剪', ELLIPSIS: '省略号', FAIL: '溢出时报错' },
  horizontalAlign: { LEFT: '左对齐', CENTER: '水平居中', RIGHT: '右对齐', JUSTIFY: '两端对齐', SPACE_EVENLY: '均匀分布' },
  verticalAlign: { TOP: '顶部', CENTER: '垂直居中', BOTTOM: '底部', JUSTIFY: '两端对齐', SPACE_EVENLY: '均匀分布' },
  fit: { CONTAIN: '完整显示', COVER: '覆盖裁剪', FILL: '拉伸填充', NONE: '原始尺寸' },
  sampling: { LINEAR: '平滑', NEAREST: '像素锐利' },
  absentPolicy: { ERROR: '缺失时报错', EMPTY: '按空列表处理', FALSE: '按不满足处理' },
  'itemLayout.kind': { STACK: '堆叠', GRID: '网格' },
  'itemLayout.direction': { ROW: '横向', COLUMN: '纵向' },
  'instanceLayout.kind': { STACK: '堆叠', GRID: '网格' },
  'instanceLayout.direction': { ROW: '横向', COLUMN: '纵向' },
  contextAbsentPolicy: { ERROR: '缺失时报错', SKIP: '缺失时跳过' },
  errorCorrectionLevel: { L: '低（7%）', M: '中（15%）', Q: '较高（25%）', H: '高（30%）' },
  lineCap: { BUTT: '平头', ROUND: '圆头', SQUARE: '方头' },
  startArrow: { NONE: '无', ARROW: '箭头', CIRCLE: '圆点' },
  endArrow: { NONE: '无', ARROW: '箭头', CIRCLE: '圆点' },
  lineJoin: { MITER: '尖角', ROUND: '圆角', BEVEL: '斜角' },
  fillRule: { NON_ZERO: '非零环绕', EVEN_ODD: '奇偶规则' },
  preset: { STAR: '星形', TRIANGLE: '三角形', ARROW: '箭头', POLYGON: '正多边形' },
  symbology: { EAN13: 'EAN-13', CODE128: 'Code 128', UPC_A: 'UPC-A' },
  mainAlign: { START: '起始', CENTER: '居中', END: '末端', SPACE_BETWEEN: '两端分布' },
  crossAlign: { START: '起始', CENTER: '居中', END: '末端', STRETCH: '拉伸' },
  'placement.widthMode': { FIXED: '固定宽度', FILL: '填充可用宽度' },
  'placement.heightMode': { FIXED: '固定高度', FILL: '填充可用高度' },
  'placement.alignSelf': { INHERIT: '跟随容器', START: '起始', CENTER: '居中', END: '末端' },
  'placement.horizontalAlignSelf': { START: '起始', CENTER: '居中', END: '末端' },
  'placement.verticalAlignSelf': { START: '起始', CENTER: '居中', END: '末端' },
  autoFlow: { ROW: '按行', COLUMN: '按列' },
};

const colorPreviews: Record<string, string> = {
  surface: 'var(--color-surface)',
  'accent-wash': 'var(--color-accent-wash)',
  ink: 'var(--color-ink)',
  coral: 'var(--color-coral)',
  hairline: 'var(--color-hairline)',
  transparent: 'transparent',
};

const colorNames: Record<string, string> = {
  surface: '表面色',
  'accent-wash': '强调浅色',
  ink: '正文深色',
  coral: '促销红',
  hairline: '分隔线色',
  transparent: '透明',
  '#FFFFFF': '白色',
};

const editableColorValues: Record<string, string> = {
  surface: '#fdfcf8',
  'accent-wash': '#effbf2',
  ink: '#272a2e',
  coral: '#ee4160',
  hairline: '#dedbd2',
  transparent: '#ffffff',
};

function propertyPresentationFor(prop: InspectorProp, nodeKind?: DesignerNode['kind']): PropertyPresentation {
  const presentation = propertyPresentations[prop.label];
  if (nodeKind === 'canvas') {
    if (prop.label === 'widthMm') return { name: '画板宽度', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 1 };
    if (prop.label === 'heightMm') return { name: '画板高度', group: 'layout', control: 'number', unit: 'mm', step: '0.1', min: 1 };
    if (prop.label === 'layoutMode') return { name: '布局方式', group: 'layout', control: 'select' };
    if (prop.label.startsWith('padding.') && presentation) return { ...presentation, name: `画板${presentation.name}` };
  }
  if (presentation && prop.label === 'backgroundColor' && nodeKind === 'canvas') return { ...presentation, name: '画板背景' };
  if (presentation && prop.label === 'innerRadiusMm' && nodeKind === 'ellipse') return { ...presentation, name: '环孔半径' };
  return presentation ?? {
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
    if (prop.value === childTemplateIds.tagPill) return '标签胶囊模板 · 当前版本';
    if (prop.value === childTemplateIds.offerCard) return '优惠信息卡模板 · 当前版本';
    return '品牌角标模板 · 当前版本';
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

function GridTrackEditor({
  axis,
  controlId,
  labelId,
  value,
  onChange,
}: {
  axis: '列' | '行';
  controlId: string;
  labelId: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const parsedTokens = prototypeGridTrackTokens(value);
  const tokens = parsedTokens.length > 0 ? parsedTokens : [''];
  const updateToken = (index: number, token: string) => {
    const next = [...tokens];
    next[index] = token;
    onChange(serializePrototypeGridTrackTokens(next));
  };
  const insertTrack = (index: number) => {
    const next = [...tokens];
    next.splice(index + 1, 0, '1*');
    onChange(serializePrototypeGridTrackTokens(next));
  };
  const removeTrack = (index: number) => {
    if (tokens.length <= 1) return;
    const next = tokens.filter((_, candidateIndex) => candidateIndex !== index);
    onChange(serializePrototypeGridTrackTokens(next));
  };
  return (
    <div className="td-grid-track-editor" data-grid-track-editor={axis === '列' ? 'columns' : 'rows'}>
      <div className="td-grid-track-list">
        {tokens.map((token, index) => {
          const parsed = parsePrototypeGridTrackToken(token);
          const kindLabel = parsed?.kind === 'FIXED' ? '固定' : parsed?.kind === 'AUTO' ? '内容' : parsed?.kind === 'FRACTION' ? '比例' : '无效';
          return (
            <div className="td-grid-track-row" data-track-kind={parsed?.kind.toLocaleLowerCase() ?? 'invalid'} key={`${axis}-${index}`}>
              <span className="td-grid-track-index" title={`${axis}轨道 ${index + 1} · ${kindLabel}`} aria-hidden="true">{index + 1}</span>
              <input
                id={index === 0 ? controlId : undefined}
                className="td-grid-track-input"
                value={token}
                aria-label={`${axis}轨道 ${index + 1}`}
                aria-labelledby={index === 0 ? labelId : undefined}
                aria-invalid={!parsed}
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                onChange={(event) => updateToken(index, event.target.value)}
                onBlur={(event) => {
                  const normalized = parsePrototypeGridTrackToken(event.currentTarget.value);
                  if (normalized) updateToken(index, normalized.canonicalToken);
                }}
              />
              <button
                type="button"
                className="td-grid-track-action"
                aria-label={`在${axis}轨道 ${index + 1} 后添加比例轨道`}
                title={`在后面添加 1* ${axis}轨道`}
                disabled={tokens.length >= PROTOTYPE_GRID_TRACK_LIMIT}
                onClick={() => insertTrack(index)}
              >
                <Plus aria-hidden="true" size={12} />
              </button>
              <button
                type="button"
                className="td-grid-track-action is-remove"
                aria-label={`删除${axis}轨道 ${index + 1}`}
                title={`删除${axis}轨道 ${index + 1}`}
                disabled={tokens.length <= 1}
                onClick={() => removeTrack(index)}
              >
                <X aria-hidden="true" size={12} />
              </button>
            </div>
          );
        })}
      </div>
      <small className="td-grid-track-legend">
        <span><code>12</code> 固定 mm</span>
        <span><code>auto</code> 适应内容</span>
        <span><code>1*</code> 剩余份额</span>
      </small>
    </div>
  );
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

  if ((prop.label === 'columns' || prop.label === 'rows') && (node.kind === 'grid' || node.kind === 'canvas')) {
    return (
      <GridTrackEditor
        axis={prop.label === 'columns' ? '列' : '行'}
        controlId={controlId}
        labelId={labelId}
        value={prop.value}
        onChange={updateValue}
      />
    );
  }

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
          onWheel={(event) => event.currentTarget.blur()}
        />
        {presentation.unit ? <span aria-hidden="true">{presentation.unit}</span> : null}
      </span>
    );
  }

  if (presentation.control === 'color') {
    const preview = colorPreviews[prop.value] ?? (prop.value.startsWith('#') ? prop.value : 'var(--color-surface-strong)');
    const editableValue = editableColorValues[prop.value] ?? (prop.value.match(/^#[0-9a-f]{6}$/i) ? prop.value : '#ffffff');
    return (
      <label
        className="td-color-control"
        aria-labelledby={labelId}
      >
        <span className="td-color-swatch" style={{ backgroundColor: preview }}>
          <input
            id={controlId}
            type="color"
            value={editableValue}
            aria-labelledby={labelId}
            onChange={(event) => updateValue(event.target.value.toLocaleUpperCase())}
          />
        </span>
        <span>{colorNames[prop.value] ?? prop.value}</span>
        <ChevronRight aria-hidden="true" size={12} />
      </label>
    );
  }

  if (presentation.control === 'font') {
    const asset = referencedAsset(prop.value);
    const fontAssets = assets.filter((candidate) => candidate.kind === 'FONT' && candidate.status === 'ACTIVE');
    return (
      <div
        className="td-font-asset-control"
        data-font-asset-id={asset?.kind === 'FONT' ? asset.id : ''}
      >
        <SelectField
          id={controlId}
          ariaLabel={`设置${presentation.name}`}
          value={asset?.kind === 'FONT' && asset.status === 'ACTIVE' ? asset.id : ''}
          options={fontAssets.map((fontAsset) => ({ value: fontAsset.id, label: fontAsset.name }))}
          onChange={(assetId) => updateValue(`{assetId:${assetId}}`)}
          placeholder={fontAssets.length > 0 ? '选择字体资产' : '无可用字体资产'}
          disabled={fontAssets.length === 0}
        />
        <small>
          <Type aria-hidden="true" size={11} />
          <span>{asset?.kind === 'FONT' ? `FONT · ${asset.detail}` : '只列出 ACTIVE · FONT'}</span>
        </small>
      </div>
    );
  }

  if (presentation.control === 'asset') {
    const asset = referencedAsset(prop.value);
    return (
      <button
        id={controlId}
        type="button"
        className="td-reference-control"
        aria-labelledby={labelId}
        onClick={() => dispatch({ type: 'set-notice', notice: `已打开${presentation.name}选择器（原型）：当前 ${asset?.name ?? '未选择'}` })}
      >
        <Image aria-hidden="true" size={13} />
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
        data-authoring-property={node.kind === 'text' && prop.label === 'runs[0].text' ? 'text' : undefined}
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

function InspectorPropRow({
  node,
  prop,
  state,
  dispatch,
  layoutDerived = false,
}: {
  node: DesignerNode;
  prop: InspectorProp;
  state: DesignerState;
  dispatch: Dispatch<DesignerAction>;
  layoutDerived?: boolean;
}) {
  const generatedId = useId();
  const controlId = `${generatedId}-control`;
  const labelId = `${generatedId}-label`;
  const basePresentation = propertyPresentationFor(prop, node.kind);
  const layoutNames: Record<string, string> = { xMm: '布局 X', yMm: '布局 Y', widthMm: '布局宽度', heightMm: '布局高度' };
  const presentation: PropertyPresentation = layoutDerived && layoutNames[prop.label]
    ? { ...basePresentation, name: layoutNames[prop.label]!, control: 'readonly' }
    : basePresentation;
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
              title={prop.binding ? '编辑或解除绑定' : '添加绑定'}
            >
              {bindState && bindState !== 'ok' ? <AlertTriangle aria-hidden="true" size={12} /> : <Link2 aria-hidden="true" size={12} />}
              <span>{prop.binding ? (bindState === 'ok' ? '已绑定' : '异常') : '绑定'}</span>
            </button>
          ) : null}
        </div>
        {prop.binding && bindState && sourceSummary ? (
          <button type="button" className={`td-binding-summary bind-${bindState}`} onClick={openEditor}>
            {bindState === 'ok' ? <Link2 aria-hidden="true" size={11} /> : <AlertTriangle aria-hidden="true" size={11} />}
            <span>{sourceSummary.source}</span>
            <small>{bindState === 'absent' ? '当前数据缺失；权威预览失败' : bindState === 'error' ? '绑定资源解析失败' : sourceSummary.note}</small>
          </button>
        ) : null}
      </div>
    </li>
  );
}

function layoutMillimetres(value: number): string {
  const rounded = Math.round(value * 100) / 100;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(2).replace(/0$/, '');
}

function RepeatLayoutEditor({ node, trace, dispatch }: {
  node: DesignerNode;
  trace: RepeatPackingTrace;
  dispatch: Dispatch<DesignerAction>;
}) {
  const prefix = 'instanceLayout';
  const value = (suffix: string, fallback = '') => node.props.find((property) => property.label === `${prefix}.${suffix}`)?.value ?? fallback;
  const update = (suffix: string, nextValue: string) => dispatch({
    type: 'update-prop',
    nodeId: node.id,
    label: `${prefix}.${suffix}`,
    value: nextValue,
  });
  const kind = value('kind', trace.kind);
  return (
    <fieldset className="td-repeat-layout-editor" data-repeat-layout={prefix}>
      <legend>循环布局</legend>
      <label>
        <span>布局方式</span>
        <select aria-label="循环布局方式" value={kind} onChange={(event) => update('kind', event.target.value)}>
          <option value="STACK">堆叠</option>
          <option value="GRID">网格</option>
        </select>
      </label>
      {kind === 'STACK' ? (
        <div className="td-repeat-layout-fields">
          <label>
            <span>排列方向</span>
            <select aria-label="循环排列方向" value={value('direction', 'ROW')} onChange={(event) => update('direction', event.target.value)}>
              <option value="ROW">横向</option>
              <option value="COLUMN">纵向</option>
            </select>
          </label>
          <label>
            <span>项目间距</span>
            <span className="td-repeat-number"><input aria-label="循环项目间距" type="number" min="0" step="0.5" value={value('gapMm', '0')} onChange={(event) => update('gapMm', event.target.value)} onWheel={(event) => event.currentTarget.blur()} /><i>mm</i></span>
          </label>
        </div>
      ) : (
        <div className="td-repeat-layout-fields is-grid">
          <label>
            <span>列数</span>
            <input aria-label="循环网格列数" type="number" min="1" step="1" value={value('columns', '1')} onChange={(event) => update('columns', event.target.value)} onWheel={(event) => event.currentTarget.blur()} />
          </label>
          <label>
            <span>列距</span>
            <span className="td-repeat-number"><input aria-label="循环列间距" type="number" min="0" step="0.5" value={value('columnGapMm', '0')} onChange={(event) => update('columnGapMm', event.target.value)} onWheel={(event) => event.currentTarget.blur()} /><i>mm</i></span>
          </label>
          <label>
            <span>行距</span>
            <span className="td-repeat-number"><input aria-label="循环行间距" type="number" min="0" step="0.5" value={value('rowGapMm', '0')} onChange={(event) => update('rowGapMm', event.target.value)} onWheel={(event) => event.currentTarget.blur()} /><i>mm</i></span>
          </label>
        </div>
      )}
      <small>循环项占用 {layoutMillimetres(trace.widthMm)} × {layoutMillimetres(trace.heightMm)}mm · {trace.children.length} 项</small>
    </fieldset>
  );
}

function RepeatConfigurator({ node, state, dispatch }: { node: DesignerNode; state: DesignerState; dispatch: Dispatch<DesignerAction> }) {
  const projection = projectPrototypeRepeat(node, state.boxes, state.repeatPreviewSample, state.definitions);
  const sources = repeatSourcesForDefinitions(state.definitions);
  const currentSourceId = projection.source?.id ?? '';
  const [sourceGroupByNodeId, setSourceGroupByNodeId] = useState<Record<string, PrototypeRepeatSource['sourceGroup']>>({});
  const sourceGroup = sourceGroupByNodeId[node.id] ?? projection.source?.sourceGroup ?? 'SYSTEM';
  const visibleSources = sources.filter((source) => source.sourceGroup === sourceGroup);
  const absentPolicy = node.props.find((property) => property.label === 'absentPolicy')?.value ?? 'EMPTY';
  const templateChild = node.children.length === 1 && node.children[0]?.kind === 'templateUse' ? node.children[0] : null;
  const currentTemplateId = templateChild?.props.find((property) => property.label === 'templateRef.templateId')?.value ?? '';
  const compatibleCurrent = projection.compatibleTemplates.some((candidate) => candidate.templateId === currentTemplateId);
  const templateValue = templateChild ? currentTemplateId : node.children.length > 0 ? '__custom__' : '';
  const phaseLabel = projection.phase === 'READY'
    ? `${projection.occurrences.length} 个预览`
    : projection.phase === 'NEEDS_REPAIR'
      ? '模板需调整'
      : projection.phase === 'CONTENT_REQUIRED'
        ? '设计单项'
        : '选择列表';
  const phaseTone = projection.phase === 'READY' ? 'ready' : projection.phase === 'NEEDS_REPAIR' ? 'repair' : 'pending';
  return (
    <section className="td-repeat-configurator td-repeat-compact" aria-label="循环容器设置" data-repeat-phase={projection.phase.toLocaleLowerCase()} data-repeat-outcome={projection.outcome.toLocaleLowerCase()}>
      <header className="td-repeat-config-head">
        <div><span>REPEAT</span><strong>循环设置</strong></div>
        <i data-tone={phaseTone}>{phaseLabel}</i>
      </header>
      <div className="td-repeat-primary-settings">
        <label>
          <span>列表来源</span>
          <select
            aria-label="循环数据源类别"
            value={sourceGroup}
            onChange={(event) => {
              const nextGroup = event.target.value as PrototypeRepeatSource['sourceGroup'];
              setSourceGroupByNodeId((current) => ({ ...current, [node.id]: nextGroup }));
              if (projection.source?.sourceGroup !== nextGroup) {
                dispatch({ type: 'update-prop', nodeId: node.id, label: 'items', value: '' });
                dispatch({ type: 'set-repeat-active-index', index: 0 });
              }
            }}
          >
            <option value="SYSTEM">模板字段</option>
            <option value="CUSTOM">自定义输入</option>
            <option value="DERIVED">派生值</option>
          </select>
        </label>
        <label>
          <span>循环列表</span>
          <select
            aria-label="循环列表属性"
            value={currentSourceId}
            onChange={(event) => {
              const source = sources.find((candidate) => candidate.id === event.target.value);
              dispatch({ type: 'update-prop', nodeId: node.id, label: 'items', value: source?.expression ?? '' });
              dispatch({ type: 'set-repeat-active-index', index: 0 });
            }}
          >
            <option value="">选择列表类型数据…</option>
            {visibleSources.map((source) => (
              <option key={source.id} value={source.id}>{source.label}{source.path ? ` · ${source.path}` : ''} · {source.sourceType === 'SCALAR_LIST' ? `list<${source.itemValueType ?? 'scalar'}>` : `list<${source.itemStaticSchemaRef}>`}</option>
            ))}
          </select>
        </label>
        <label>
          <span>单项内容 · 可选</span>
          <select
            aria-label="循环单项模板"
            value={templateValue}
            disabled={!projection.source}
            onChange={(event) => {
              if (!event.target.value || event.target.value.startsWith('__')) return;
              dispatch({ type: 'set-repeat-template', nodeId: node.id, templateId: event.target.value });
            }}
          >
            <option value="">直接设计 children[]</option>
            {node.children.length > 0 && !templateChild ? <option value="__custom__">当前自定义单项内容</option> : null}
            {templateChild && currentTemplateId && !compatibleCurrent ? <option value={currentTemplateId}>当前模板 · 类型不兼容</option> : null}
            {projection.compatibleTemplates.map((candidate) => <option key={candidate.templateId} value={candidate.templateId}>{candidate.name} · {candidate.staticSchemaRef}</option>)}
          </select>
        </label>
      </div>
      <div className="td-repeat-compact-status" data-tone={phaseTone}>
        <span>{projection.source ? `${projection.source.sourceGroup === 'SYSTEM' ? '模板字段' : projection.source.sourceGroup === 'CUSTOM' ? '自定义输入' : '派生值'} · ${projection.sourceProof.itemStaticSchemaRef}` : '等待列表类型'}</span>
        <strong>{phaseLabel}</strong>
      </div>
      {projection.phase === 'NEEDS_REPAIR' ? <p className="td-repeat-compact-error"><AlertTriangle size={12} />列表单项类型与当前模板不兼容，请重新选择模板。</p> : null}
      <RepeatLayoutEditor node={node} trace={projection.instanceLayout} dispatch={dispatch} />
      <details className="td-repeat-advanced">
        <summary><span>本地数据预览</span><small>编辑器状态 · {projection.occurrences.length} 项</small><ChevronRight aria-hidden="true" size={13} /></summary>
        <div className="td-repeat-advanced-body">
          <div className="td-repeat-inline-fields">
            <label><span>列表缺失</span><select aria-label="循环数组缺失策略" value={absentPolicy} onChange={(event) => dispatch({ type: 'update-prop', nodeId: node.id, label: 'absentPolicy', value: event.target.value })}><option value="EMPTY">按空列表</option><option value="ERROR">阻止预览</option></select></label>
            <div className="td-repeat-samples" role="group" aria-label="循环预览样本">
              {(['values', 'empty', 'absent'] as const).map((sample) => <button key={sample} type="button" className={state.repeatPreviewSample === sample ? 'active' : ''} aria-pressed={state.repeatPreviewSample === sample} onClick={() => dispatch({ type: 'set-repeat-preview-sample', sample })}>{sample === 'values' ? '有数据' : sample === 'empty' ? '空列表' : '缺失'}</button>)}
            </div>
          </div>
          <div className="td-repeat-preview-mode" role="group" aria-label="循环画布预览模式">
            <button type="button" className={state.repeatPreviewMode === 'instances' ? 'active' : ''} aria-pressed={state.repeatPreviewMode === 'instances'} onClick={() => dispatch({ type: 'set-repeat-preview-mode', mode: 'instances' })}>全部实例</button>
            <button type="button" className={state.repeatPreviewMode === 'item' ? 'active' : ''} aria-pressed={state.repeatPreviewMode === 'item'} onClick={() => dispatch({ type: 'set-repeat-preview-mode', mode: 'item' })}>当前单项</button>
          </div>
        </div>
      </details>
    </section>
  );
}

function TemplateUseConfigurator({ node, dispatch }: { node: DesignerNode; dispatch: Dispatch<DesignerAction> }) {
  const selector = node.props.find((property) => property.label === 'contextSelector')?.value ?? '';
  const targetTemplateId = node.props.find((property) => property.label === 'templateRef.templateId')?.value ?? '';

  if (selector.startsWith('loop(')) {
    const loopTemplate = repeatTemplateCandidates.find((candidate) => candidate.templateId === targetTemplateId);
    return (
      <section className="td-repeat-configurator td-repeat-compact td-template-use-configurator is-loop-item" aria-label="循环项嵌套模板设置" data-template-use-phase={targetTemplateId ? 'ready' : 'pending'}>
        <header className="td-repeat-config-head">
          <div><span>TEMPLATE USE · REPEAT ITEM</span><strong>嵌套模板容器</strong></div>
          <i data-tone={targetTemplateId ? 'ready' : 'pending'}>{targetTemplateId ? '循环项已连接' : '等待模板'}</i>
        </header>
        <div className="td-template-use-loop-proof">
          <Puzzle aria-hidden="true" size={15} />
          <span><strong>{loopTemplate?.name ?? '当前循环项模板'}</strong><small>上下文固定为完整 loop item · 兼容性由 Repeat 的单项 StaticSchema 校验</small></span>
        </div>
        <p className="td-inline-note"><Info size={12} />这是 Repeat authored 单项子树中的显式 TemplateUse；请选中父级循环容器修改数组来源或单项模板。</p>
      </section>
    );
  }

  const source = templateUseContextSources.find((candidate) => candidate.selector === selector);
  const compatibleTemplates = source
    ? nestedTemplates.filter((candidate) => candidate.lifecycle === 'ACTIVE'
      && candidate.readiness === 'READY'
      && candidate.compatibilityKey === source.compatibilityKey)
    : [];
  const selectedTemplate = compatibleTemplates.find((candidate) => candidate.id === targetTemplateId);
  const phaseLabel = selectedTemplate ? '已配置' : source ? '选择模板' : '选择属性';
  const phaseTone = selectedTemplate ? 'ready' : 'pending';

  return (
    <section className="td-repeat-configurator td-repeat-compact td-template-use-configurator" aria-label="嵌套模板容器设置" data-template-use-phase={selectedTemplate ? 'ready' : source ? 'template' : 'property'}>
      <header className="td-repeat-config-head">
        <div><span>TEMPLATE USE</span><strong>嵌套模板容器</strong></div>
        <i data-tone={phaseTone}>{phaseLabel}</i>
      </header>
      <div className="td-repeat-primary-settings">
        <label>
          <span>1 · 属性</span>
          <select
            aria-label="嵌套模板属性"
            value={source?.id ?? ''}
            onChange={(event) => {
              if (event.target.value) dispatch({ type: 'set-template-use-context', nodeId: node.id, sourceId: event.target.value });
            }}
          >
            <option value="">选择属性…</option>
            <optgroup label="引用属性">
              {templateUseContextSources.filter((candidate) => candidate.kind === 'REFERENCE').map((candidate) => (
                <option key={candidate.id} value={candidate.id}>{candidate.label} · {candidate.pointer}</option>
              ))}
            </optgroup>
            <optgroup label="基础属性">
              {templateUseContextSources.filter((candidate) => candidate.kind === 'SCALAR_PROPOSAL').map((candidate) => (
                <option key={candidate.id} value={candidate.id}>{candidate.label} · {candidate.pointer} · {candidate.typeLabel}{candidate.presence === 'optional' ? ' · 可选' : ''}</option>
              ))}
            </optgroup>
          </select>
        </label>
        <label>
          <span>2 · 模板</span>
          <select
            aria-label="嵌套模板选择"
            value={selectedTemplate?.id ?? ''}
            disabled={!source}
            onChange={(event) => {
              if (event.target.value) dispatch({ type: 'set-template-use-template', nodeId: node.id, templateId: event.target.value });
            }}
          >
            <option value="">{source ? '选择模板…' : '请先选择属性'}</option>
            {targetTemplateId && !selectedTemplate ? <option value={targetTemplateId}>当前模板 · 类型不兼容</option> : null}
            {compatibleTemplates.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
            ))}
          </select>
        </label>
      </div>
      <div className="td-repeat-compact-status" data-tone={phaseTone}>
        <span>{source ? `${source.pointer} · ${source.kind === 'REFERENCE' ? source.contextLabel : source.typeLabel}` : '等待选择属性'}</span>
        <strong>{selectedTemplate?.name ?? phaseLabel}</strong>
      </div>
      {source?.kind === 'SCALAR_PROPOSAL' ? (
        <p className="td-template-use-proposal"><Info aria-hidden="true" size={12} /><span><strong>基础类型模板提案</strong>无 index，仅包含 value；尚未进入正式合同。</span></p>
      ) : null}
      <p className="td-inline-note"><Info size={12} />模板内容由子模板提供，不能手工拖入子节点。</p>
    </section>
  );
}

function conditionalOutcomeMeta(outcome: ConditionalOutcome): { label: string; tone: 'ready' | 'pruned' | 'error' | 'pending' } {
  switch (outcome) {
    case 'INCLUDED': return { label: 'TRUE · 已纳入', tone: 'ready' };
    case 'PRUNED_FALSE': return { label: 'FALSE · 已剪枝', tone: 'pruned' };
    case 'PRUNED_ABSENT': return { label: 'ABSENT → FALSE', tone: 'pruned' };
    case 'RENDER_DISABLED': return { label: 'render:false · 已剪枝', tone: 'pruned' };
    case 'ABSENT_ERROR': return { label: 'ABSENT · Evaluation 失败', tone: 'error' };
    case 'INPUT_INVALID': return { label: '输入样本无效', tone: 'error' };
    case 'INVALID': return { label: '配置未完成', tone: 'pending' };
  }
}

function ConditionalConfigurator({ node, state, dispatch }: { node: DesignerNode; state: DesignerState; dispatch: Dispatch<DesignerAction> }) {
  const projection = projectPrototypeConditional(node, state.conditionalPreviewSample);
  const outcome = conditionalOutcomeMeta(projection.outcome);
  const condition = node.props.find((property) => property.label === 'condition')?.value ?? '';
  const absentPolicy = node.props.find((property) => property.label === 'absentPolicy')?.value === 'ERROR' ? 'ERROR' : 'FALSE';
  const sampleDisabled = projection.source?.kind === 'LITERAL';
  const sourceComplete = projection.sourceProof.valid;
  const contentComplete = node.children.length > 0;
  const evaluationComplete = projection.phase === 'READY';
  const pipeline = projection.outcome === 'INCLUDED'
    ? [
        { label: '求值 boolean 条件', state: 'done', detail: 'TRUE' },
        { label: '降低为无外观 Frame', state: 'done', detail: '保留 authored children[]' },
        { label: '子节点 Binding → 布局 → Asset → 输出', state: 'done', detail: '按结构顺序继续' },
      ]
    : [
        { label: '求值 boolean 条件', state: projection.outcome === 'ABSENT_ERROR' || projection.outcome === 'INPUT_INVALID' ? 'error' : 'done', detail: outcome.label },
        { label: '整棵 true 分支', state: projection.outcome === 'ABSENT_ERROR' || projection.outcome === 'INPUT_INVALID' ? 'blocked' : 'pruned', detail: projection.outcome === 'ABSENT_ERROR' || projection.outcome === 'INPUT_INVALID' ? 'Evaluation 无部分输出' : '从运行时树移除' },
        { label: '子节点 Binding / 布局 / Asset', state: 'blocked', detail: '不求值、不占位' },
      ];

  return (
    <section
      className="td-repeat-configurator td-conditional-configurator"
      aria-label="条件容器配置"
      data-conditional-phase={projection.phase.toLocaleLowerCase()}
      data-conditional-outcome={projection.outcome.toLocaleLowerCase()}
    >
      <header className="td-repeat-config-head">
        <div><span>CONDITIONAL PROJECTION</span><strong>只设计 true 分支</strong></div>
        <i data-tone={outcome.tone}>{outcome.label}</i>
      </header>

      <section className="td-repeat-step" data-step="condition">
        <header><b>1</b><div><strong>选择 boolean 条件</strong><span>来源必须能静态证明为 boolean</span></div><i data-complete={sourceComplete}>{sourceComplete ? '已证明' : '未完成'}</i></header>
        <label className="td-repeat-field">
          <span>条件来源</span>
          <select
            aria-label="条件数据源"
            value={condition}
            onChange={(event) => dispatch({ type: 'update-prop', nodeId: node.id, label: 'condition', value: event.target.value })}
          >
            <option value="">请选择 boolean 来源</option>
            {prototypeConditionalSources.map((source) => <option key={source.id} value={source.wire}>{source.label}</option>)}
          </select>
        </label>
        <div className="td-repeat-proof" data-valid={projection.sourceProof.valid}>
          {projection.sourceProof.valid ? <Check aria-hidden="true" size={13} /> : <AlertTriangle aria-hidden="true" size={13} />}
          <span><strong>{projection.sourceProof.valid ? '类型证明成立' : '条件不可求值'}</strong><small>{projection.sourceProof.message}</small></span>
        </div>
        <div className="td-repeat-inline-fields">
          <label><span>缺失策略</span><select aria-label="条件缺失策略" value={absentPolicy} onChange={(event) => dispatch({ type: 'update-prop', nodeId: node.id, label: 'absentPolicy', value: event.target.value })}><option value="FALSE">按 FALSE 剪枝</option><option value="ERROR">终止 Evaluation</option></select></label>
          <div className="td-repeat-samples" role="group" aria-label="条件预览输入">
            {(['true', 'false', 'absent'] as const).map((sample) => (
              <button
                key={sample}
                type="button"
                disabled={sampleDisabled}
                className={state.conditionalPreviewSample === sample ? 'active' : ''}
                aria-pressed={state.conditionalPreviewSample === sample}
                onClick={() => dispatch({ type: 'set-conditional-preview-sample', sample })}
              >
                {sample === 'true' ? 'TRUE' : sample === 'false' ? 'FALSE' : 'ABSENT'}
              </button>
            ))}
          </div>
        </div>
        {sampleDisabled ? <p className="td-inline-note"><Info size={12} />字面量不读取预览输入；切回 Schema 字段后可切换样本。</p> : null}
      </section>

      <section className="td-repeat-step" data-step="true-branch">
        <header><b>2</b><div><strong>设计 true 分支</strong><span>没有 else 分支；children[] 必须非空</span></div><i data-complete={contentComplete}>{contentComplete ? `${node.children.length} 个 authored` : '待设计'}</i></header>
        {contentComplete ? (
          <div className="td-repeat-authored-list">
            {node.children.map((child, index) => (
              <button key={child.id} type="button" onClick={() => dispatch({ type: 'select-node', nodeId: child.id })}>
                <b>{index + 1}</b><span>{kindIcons[child.kind]}<strong>{child.name}</strong><small>{child.kind} · ABSOLUTE</small></span><ChevronRight aria-hidden="true" size={13} />
              </button>
            ))}
          </div>
        ) : <p className="td-repeat-empty-content">空 Conditional 不是合法 DesignDSL。添加至少一个 true 分支节点；FALSE 时它仍不会被求值。</p>}
        <div className="td-repeat-content-actions">
          <button type="button" onClick={() => dispatch({ type: 'insert-node', kind: 'text', parentId: node.id, positionMm: { x: 4, y: 4 } })}><Type size={12} />添加文本</button>
          <button type="button" onClick={() => dispatch({ type: 'insert-node', kind: 'rect', parentId: node.id, positionMm: { x: 2, y: 2 } })}>{kindIcons.rect}添加矩形</button>
          <button type="button" onClick={() => dispatch({ type: 'insert-node', kind: 'frame', preset: 'frame', parentId: node.id, positionMm: { x: 2, y: 2 } })}>{kindIcons.frame}添加框架</button>
        </div>
      </section>

      <section className="td-repeat-step td-conditional-evaluation" data-step="evaluation">
        <header><b>3</b><div><strong>检查运行时顺序</strong><span>剪枝发生在子树 Binding、布局与资源之前</span></div><i data-complete={evaluationComplete}>{evaluationComplete ? '可预览' : '等待配置'}</i></header>
        <ol className="td-conditional-pipeline">
          {pipeline.map((entry, index) => (
            <li key={entry.label} data-state={entry.state}><b>{index + 1}</b><span><strong>{entry.label}</strong><small>{entry.detail}</small></span></li>
          ))}
        </ol>
      </section>

      <footer className="td-repeat-outcome" data-outcome={outcome.tone} aria-live="polite">
        <span>{projection.outcome === 'INCLUDED' ? <Check aria-hidden="true" size={14} /> : projection.outcome === 'ABSENT_ERROR' || projection.outcome === 'INPUT_INVALID' ? <AlertTriangle aria-hidden="true" size={14} /> : <EyeOff aria-hidden="true" size={14} />}</span>
        <p><strong>{outcome.label}</strong><small>{projection.message}</small></p>
      </footer>
      <p className="td-inline-note"><Info size={12} />画布虚线框是编辑器占位，不是输出节点；Structure 始终保留 authored true 分支。</p>
    </section>
  );
}

function StructuralFacts({ node, state, dispatch }: { node: DesignerNode; state: DesignerState; dispatch: Dispatch<DesignerAction> }) {
  if (node.kind === 'canvas') {
    return (
      <section className="td-structural-facts" aria-label="画板原型语义说明">
        <p><strong>Canvas 是唯一 DesignRoot。</strong>宽高以 mm 为物理真相；内边距会在画板上形成可见内容区。</p>
        <p>根布局与内边距是 T220 的交互验证 delta，本轮只影响浏览器草稿布局，不修改生产 DesignDSL 合同或调用 RenderServer。</p>
      </section>
    );
  }

  if (node.kind === 'repeat') {
    return <RepeatConfigurator node={node} state={state} dispatch={dispatch} />;
  }

  if (node.kind === 'templateUse') {
    return <TemplateUseConfigurator node={node} dispatch={dispatch} />;
  }

  if (node.kind === 'conditional') {
    return <ConditionalConfigurator node={node} state={state} dispatch={dispatch} />;
  }

  if (node.kind === 'stack') {
    const conditionalRuntime = projectPrototypeConditionalRuntime(state.tree, state.conditionalPreviewSample);
    const trace = projectPrototypeLayout(state.tree, state.boxes, { excludedNodeIds: conditionalRuntime.excludedNodeIds }).stackByContainerId.get(node.id);
    if (!trace) {
      return (
        <section className="td-structural-facts td-live-stack-facts" aria-label="堆叠布局实时计算">
          <div className="td-live-stack-head"><span>实时布局</span><strong>等待可视 LayoutBox</strong></div>
          <p>这个历史根容器没有独立画布框。可在左侧“容器”载入横向或纵向 Demo，或创建一个新的堆叠容器后把图层拖入。</p>
        </section>
      );
    }
    const content = trace.contentBox;
    const gapSummary = trace.effectiveBetweenGapsMm.length > 0
      ? trace.effectiveBetweenGapsMm.map(layoutMillimetres).join(' / ')
      : '无相邻项';
    const fillSummary = trace.fillCount > 0
      ? `${trace.fillCount} 项 · ${layoutMillimetres(trace.fillAvailableMainMm)}mm`
      : '无主轴填充项';
    return (
      <section className="td-structural-facts td-live-stack-facts" aria-label="堆叠布局实时计算">
        <div className="td-live-stack-head">
          <span>实时布局 · 浏览器投影</span>
          <strong className={trace.overflowMainMm > 0 ? 'is-overflowing' : ''}>{trace.overflowMainMm > 0 ? `溢出 ${layoutMillimetres(trace.overflowMainMm)}mm` : '计算完成'}</strong>
        </div>
        <div className="td-fact-grid td-live-stack-summary">
          <div><span>内容区</span><strong>{layoutMillimetres(content.w)} × {layoutMillimetres(content.h)} mm</strong><small>X {layoutMillimetres(content.x)} · Y {layoutMillimetres(content.y)}；已扣描边与内边距</small></div>
          <div><span>主轴 FILL 预算</span><strong>{fillSummary}</strong><small>固定项、signed margin 与 gap 先占 {layoutMillimetres(trace.usedWithoutFillMm)}mm</small></div>
          <div><span>主轴占用</span><strong>{layoutMillimetres(trace.occupiedMainMm)} / {layoutMillimetres(trace.availableMainMm)} mm</strong><small>剩余 {layoutMillimetres(trace.freeMainMm)}mm；溢出 {layoutMillimetres(trace.overflowMainMm)}mm</small></div>
          <div><span>实际相邻间距</span><strong>{gapSummary} mm</strong><small>{trace.mainAlign === 'SPACE_BETWEEN' ? '固定 gap + 正剩余空间分布' : `固定 gap ${layoutMillimetres(trace.gapMm)}mm`}</small></div>
        </div>
        <ol className="td-live-stack-placements" aria-label="子项实时位置">
          {trace.placements.map((placement) => {
            const child = findNode(state.tree, placement.nodeId);
            return (
              <li key={placement.nodeId}>
                <span>
                  <b>{placement.order}</b>
                  <strong>{child?.name ?? placement.nodeId}</strong>
                  <em>{placement.mainFill ? `FILL × ${layoutMillimetres(placement.fillWeight ?? 1)} → ${layoutMillimetres(placement.mainAllocationMm ?? 0)}mm` : 'FIXED'}</em>
                </span>
                <code>X {layoutMillimetres(placement.box.x)} · Y {layoutMillimetres(placement.box.y)} · {layoutMillimetres(placement.box.w)}×{layoutMillimetres(placement.box.h)}</code>
                <small>
                  外边距 {(['top', 'right', 'bottom', 'left'] as const).map((side) => layoutMillimetres(placement.margins[side])).join('/')}
                  {' · '}{placement.crossFill ? '交叉轴 FILL' : `交叉轴 ${placement.resolvedCrossAlign}${placement.alignSelf === 'INHERIT' ? '（跟随容器）' : '（单项覆盖）'}`}
                </small>
              </li>
            );
          })}
        </ol>
        {trace.missingChildIds.length > 0 ? <p className="td-inline-note"><Info size={12} />{trace.missingChildIds.length} 个历史子项没有可视 DraftBox，未进入本轮 definite 投影。</p> : null}
        <p>当前计算 FIXED / FILL、signed margin、单项交叉轴覆盖与 min/max 权重分配；最后一个 active FILL 接收 binary64 余数。HUG 仍需文本、图片和容器的内在尺寸测量，暂不猜测。</p>
      </section>
    );
  }

  if (node.kind === 'grid') {
    const conditionalRuntime = projectPrototypeConditionalRuntime(state.tree, state.conditionalPreviewSample);
    const trace = projectPrototypeLayout(state.tree, state.boxes, { excludedNodeIds: conditionalRuntime.excludedNodeIds }).gridByContainerId.get(node.id);
    if (!trace) {
      return (
        <section className="td-structural-facts td-live-stack-facts td-live-grid-facts" aria-label="网格布局实时计算">
          <div className="td-live-stack-head"><span>实时布局</span><strong>等待可视 LayoutBox</strong></div>
          <p>这个历史网格没有独立画布框。可在左侧“容器”载入 Grid Demo，或创建新网格后把图层拖入。</p>
        </section>
      );
    }
    const trackSummary = (track: typeof trace.columns[number]) => track.kind === 'FIXED'
      ? `${layoutMillimetres(track.valueMm ?? 0)}mm → ${layoutMillimetres(track.sizeMm)}mm`
      : track.kind === 'FRACTION'
        ? `${layoutMillimetres(track.weight ?? 1)}* → ${layoutMillimetres(track.sizeMm)}mm`
        : `auto → ${layoutMillimetres(track.sizeMm)}mm`;
    const hasOverflow = trace.overflowWidthMm > 0 || trace.overflowHeightMm > 0;
    return (
      <section className="td-structural-facts td-live-stack-facts td-live-grid-facts" aria-label="网格布局实时计算">
        <div className="td-live-stack-head">
          <span>实时 Grid · 浏览器投影</span>
          <strong className={trace.problems.length > 0 || hasOverflow ? 'is-overflowing' : ''}>
            {trace.problems.length > 0 ? `${trace.problems.length} 个约束问题` : hasOverflow ? '轨道溢出' : '计算完成'}
          </strong>
        </div>
        <div className="td-fact-grid td-live-stack-summary">
          <div><span>内容区</span><strong>{layoutMillimetres(trace.contentBox.w)} × {layoutMillimetres(trace.contentBox.h)} mm</strong><small>X {layoutMillimetres(trace.contentBox.x)} · Y {layoutMillimetres(trace.contentBox.y)}；已扣描边与内边距</small></div>
          <div><span>列轨道</span><strong>{trace.columns.length} 条 · gap {layoutMillimetres(trace.columnGapMm)}mm</strong><small>占用 {layoutMillimetres(trace.occupiedWidthMm)}mm · 剩余 {layoutMillimetres(trace.freeWidthMm)}mm · 溢出 {layoutMillimetres(trace.overflowWidthMm)}mm</small></div>
          <div><span>行轨道</span><strong>{trace.rows.length} 条 · gap {layoutMillimetres(trace.rowGapMm)}mm</strong><small>占用 {layoutMillimetres(trace.occupiedHeightMm)}mm · 剩余 {layoutMillimetres(trace.freeHeightMm)}mm · 溢出 {layoutMillimetres(trace.overflowHeightMm)}mm</small></div>
          <div><span>AUTO 贡献</span><strong>{trace.autoConstraintCount} 条约束</strong><small>短跨度优先；跨轨 deficit 只均分给跨度内 AUTO</small></div>
        </div>
        <div className="td-live-grid-tracks" aria-label="网格轨道求解结果">
          <div><span>列</span>{trace.columns.map((track) => <code key={`column-${track.index}`}>{track.index} · {trackSummary(track)}</code>)}</div>
          <div><span>行</span>{trace.rows.map((track) => <code key={`row-${track.index}`}>{track.index} · {trackSummary(track)}</code>)}</div>
        </div>
        {trace.problems.length > 0 ? (
          <div className="td-live-grid-problems" role="alert">
            <AlertTriangle aria-hidden="true" size={13} />
            <span>{trace.problems.join('；')}</span>
          </div>
        ) : (
          <ol className="td-live-stack-placements" aria-label="网格子项实时位置">
            {trace.placements.map((placement) => {
              const child = findNode(state.tree, placement.nodeId);
              return (
                <li key={placement.nodeId}>
                  <span>
                    <b>{placement.order}</b>
                    <strong>{child?.name ?? placement.nodeId}</strong>
                    <em>列 {placement.column} × {placement.columnSpan} · 行 {placement.row} × {placement.rowSpan}</em>
                  </span>
                  <code>X {layoutMillimetres(placement.box.x)} · Y {layoutMillimetres(placement.box.y)} · {layoutMillimetres(placement.box.w)}×{layoutMillimetres(placement.box.h)}</code>
                  <small>
                    单元 {layoutMillimetres(placement.cell.w)}×{layoutMillimetres(placement.cell.h)}mm
                    {' · '}{placement.widthMode}/{placement.heightMode}
                    {' · '}{placement.horizontalAlignSelf}/{placement.verticalAlignSelf}
                    {' · '}外边距 {(['top', 'right', 'bottom', 'left'] as const).map((side) => layoutMillimetres(placement.margins[side])).join('/')}
                  </small>
                </li>
              );
            })}
          </ol>
        )}
        {trace.missingChildIds.length > 0 ? <p className="td-inline-note"><Info size={12} />{trace.missingChildIds.length} 个历史子项没有可视 DraftBox，未进入本轮 definite 投影。</p> : null}
        <p>轨道逐条定义：<code>12</code> 是固定 12mm，<code>auto</code> 适应内容，<code>1*</code> / <code>2*</code> 分配剩余空间。列先于行，显式 row/column 从 0 开始；不做隐式放置。当前 auto 只测量 FIXED 子项，HUG 仍待字体、图片与容器 IntrinsicSize。</p>
      </section>
    );
  }

  if (node.kind === 'group') {
    const conditionalRuntime = projectPrototypeConditionalRuntime(state.tree, state.conditionalPreviewSample);
    const trace = projectPrototypeLayout(state.tree, state.boxes, { excludedNodeIds: conditionalRuntime.excludedNodeIds }).absoluteByContainerId.get(node.id);
    if (!trace) {
      return (
        <section className="td-structural-facts td-live-stack-facts" aria-label="自由分组实时计算">
          <div className="td-live-stack-head"><span>实时 Group</span><strong>等待可视子项</strong></div>
          <p>空 Group 的 HUG 边界为 0；载入左侧“自由分组边界”Demo，或把可视图层拖入后观察子项并集。</p>
        </section>
      );
    }
    const bounds = trace.sourceBounds;
    return (
      <section className="td-structural-facts td-live-stack-facts" aria-label="自由分组实时计算">
        <div className="td-live-stack-head"><span>实时 Group · 浏览器投影</span><strong>HUG 已重算</strong></div>
        <div className="td-fact-grid td-live-stack-summary">
          <div><span>派生 LayoutBox</span><strong>{layoutMillimetres(trace.layoutBox.w)} × {layoutMillimetres(trace.layoutBox.h)} mm</strong><small>X {layoutMillimetres(trace.layoutBox.x)} · Y {layoutMillimetres(trace.layoutBox.y)}；不写回子项坐标</small></div>
          <div><span>子项源并集</span><strong>{bounds ? `${layoutMillimetres(bounds.w)} × ${layoutMillimetres(bounds.h)} mm` : '空'}</strong><small>{bounds ? `局部最小点 ${layoutMillimetres(bounds.x)}, ${layoutMillimetres(bounds.y)}` : '没有可测量子项'}</small></div>
        </div>
        <ol className="td-live-stack-placements" aria-label="自由分组子项实时位置">
          {trace.placements.map((placement) => (
            <li key={placement.nodeId}>
              <span><b>{placement.order}</b><strong>{findNode(state.tree, placement.nodeId)?.name ?? placement.nodeId}</strong><em>ABSOLUTE · 局部坐标保持</em></span>
              <code>局部 {layoutMillimetres(placement.localBox.x)}, {layoutMillimetres(placement.localBox.y)} → 画板 {layoutMillimetres(placement.box.x)}, {layoutMillimetres(placement.box.y)}</code>
            </li>
          ))}
        </ol>
        <p>Group 只有 HUG_CONTENT：它没有填充、描边、内边距或裁剪。子项并集的最小点会归一化到 Group 原点；拖动子项实时改变边界，拖动 Group 只改变整体位置。</p>
      </section>
    );
  }

  if (node.kind === 'frame') {
    const conditionalRuntime = projectPrototypeConditionalRuntime(state.tree, state.conditionalPreviewSample);
    const trace = projectPrototypeLayout(state.tree, state.boxes, { excludedNodeIds: conditionalRuntime.excludedNodeIds }).absoluteByContainerId.get(node.id);
    if (!trace?.contentBox) {
      return (
        <section className="td-structural-facts td-live-stack-facts" aria-label="框架实时计算">
          <div className="td-live-stack-head"><span>实时 Frame</span><strong>等待可视 LayoutBox</strong></div>
          <p>载入左侧“框架内容区”Demo，或把图层拖入一个可视 Frame 后观察局部坐标投影。</p>
        </section>
      );
    }
    return (
      <section className="td-structural-facts td-live-stack-facts" aria-label="框架实时计算">
        <div className="td-live-stack-head"><span>实时 Frame · 浏览器投影</span><strong>ContentBox 已更新</strong></div>
        <div className="td-fact-grid td-live-stack-summary">
          <div><span>LayoutBox</span><strong>{layoutMillimetres(trace.layoutBox.w)} × {layoutMillimetres(trace.layoutBox.h)} mm</strong><small>X {layoutMillimetres(trace.layoutBox.x)} · Y {layoutMillimetres(trace.layoutBox.y)}</small></div>
          <div><span>ContentBox</span><strong>{layoutMillimetres(trace.contentBox.w)} × {layoutMillimetres(trace.contentBox.h)} mm</strong><small>X {layoutMillimetres(trace.contentBox.x)} · Y {layoutMillimetres(trace.contentBox.y)}；已扣双侧向内描边与 padding</small></div>
        </div>
        <ol className="td-live-stack-placements" aria-label="框架子项实时位置">
          {trace.placements.map((placement) => (
            <li key={placement.nodeId}>
              <span><b>{placement.order}</b><strong>{findNode(state.tree, placement.nodeId)?.name ?? placement.nodeId}</strong><em>ABSOLUTE · 相对 ContentBox</em></span>
              <code>局部 {layoutMillimetres(placement.localBox.x)}, {layoutMillimetres(placement.localBox.y)} → 画板 {layoutMillimetres(placement.box.x)}, {layoutMillimetres(placement.box.y)}</code>
            </li>
          ))}
        </ol>
        <p>Frame 保留子项局部绝对坐标；修改描边或四侧内边距会实时移动 ContentBox 与所有子项。需要自动排列时应显式使用 Stack 或 Grid。</p>
        <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'set-notice', notice: '重新挂载不会沿用旧坐标语义：从 Frame 到 Stack/Grid 时必须显式转换 ABSOLUTE placement' })}>演示重新挂载转换</button>
      </section>
    );
  }

  if (node.kind === 'text') {
    return (
      <section className="td-structural-facts">
        <p>属性面板只维护一个“文本值”，不会让作者编辑文本片段数组；创建时必须明确选择字体资产。尺寸和间距使用毫米，字号使用点，DPI 只在输出时设置。</p>
        <p className="td-inline-note"><Info size={12} />保存适配仍会把这个值写入一个完整 Text Run，以兼容当前 DesignDSL；这是内部合同，不是作者操作路径。</p>
        <p>支持横排、竖排、双轴对齐、内边距、描边与自动缩小。显示溢出与最大行数不能同时启用；字体资源失效时模板也会失效。</p>
      </section>
    );
  }

  return null;
}

const sharedManagedPlacementPropertyLabels = new Set([
  'placement.widthMode',
  'placement.heightMode',
  'placement.minWidthMm',
  'placement.maxWidthMm',
  'placement.minHeightMm',
  'placement.maxHeightMm',
  'placement.marginTopMm',
  'placement.marginRightMm',
  'placement.marginBottomMm',
  'placement.marginLeftMm',
]);

const stackPlacementPropertyLabels = new Set([
  'placement.alignSelf',
  'placement.fillWeight',
]);

const gridPlacementPropertyLabels = new Set([
  'placement.row',
  'placement.column',
  'placement.rowSpan',
  'placement.columnSpan',
  'placement.horizontalAlignSelf',
  'placement.verticalAlignSelf',
]);

const managedPlacementPropertyLabels = new Set([
  ...sharedManagedPlacementPropertyLabels,
  ...stackPlacementPropertyLabels,
  ...gridPlacementPropertyLabels,
]);

const repeatWorkflowPropertyLabels = new Set([
  'items',
  'absentPolicy',
  'itemLayout.kind',
  'itemLayout.direction',
  'itemLayout.gapMm',
  'itemLayout.columns',
  'itemLayout.columnGapMm',
  'itemLayout.rowGapMm',
  'instanceLayout.kind',
  'instanceLayout.direction',
  'instanceLayout.gapMm',
  'instanceLayout.columns',
  'instanceLayout.columnGapMm',
  'instanceLayout.rowGapMm',
]);

const conditionalWorkflowPropertyLabels = new Set(['condition', 'absentPolicy']);
const templateUseWorkflowPropertyLabels = new Set(['templateRef.templateId', 'contextSelector', 'contextAbsentPolicy']);

export function Inspector({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const [mode, setMode] = useState<'properties' | 'bindings'>('properties');
  const [collapsedGroups, setCollapsedGroups] = useState<Set<PropertyGroupKey>>(() => new Set());
  const inspectorId = useId();
  const node = findNode(state.tree, state.selectedNodeId) ?? state.tree;
  const parent = findParentNode(state.tree, node.id);
  const selectedConditionalProjection = node.kind === 'conditional'
    ? projectPrototypeConditional(node, state.conditionalPreviewSample)
    : null;
  const layoutOwnedPosition = Boolean(
    parent
    && isLayoutManagingNode(parent)
    && (!selectedConditionalProjection || selectedConditionalProjection.participatesInLayout),
  );
  const conditionalRuntime = projectPrototypeConditionalRuntime(state.tree, state.conditionalPreviewSample);
  const layoutProjection = projectPrototypeLayout(state.tree, state.boxes, {
    excludedNodeIds: conditionalRuntime.excludedNodeIds,
  });
  const groupHugBox = node.kind === 'group' ? layoutProjection.boxByNodeId.get(node.id) : undefined;
  const effectiveBox = layoutOwnedPosition ? layoutProjection.boxByNodeId.get(node.id) : groupHugBox;
  const parentUsesStackLayout = parent?.kind === 'stack'
    || (parent?.kind === 'canvas' && canvasProjection(parent).layoutMode === 'STACK');
  const parentUsesGridLayout = parent?.kind === 'grid'
    || (parent?.kind === 'canvas' && canvasProjection(parent).layoutMode === 'GRID');
  const parentStackTrace = parentUsesStackLayout && parent
    ? layoutProjection.stackByContainerId.get(parent.id)
    : undefined;
  const parentGridTrace = parentUsesGridLayout && parent
    ? layoutProjection.gridByContainerId.get(parent.id)
    : undefined;
  const stackPlacementTrace = parentStackTrace?.placements.find((placement) => placement.nodeId === node.id);
  const gridPlacementTrace = parentGridTrace?.placements.find((placement) => placement.nodeId === node.id);
  const layoutDerivedLabels = new Set<string>(layoutOwnedPosition ? ['xMm', 'yMm'] : []);
  if (groupHugBox) {
    layoutDerivedLabels.add('widthMm');
    layoutDerivedLabels.add('heightMm');
  }
  if (stackPlacementTrace && parentStackTrace) {
    const widthDerived = parentStackTrace.direction === 'HORIZONTAL' ? stackPlacementTrace.mainFill : stackPlacementTrace.crossFill;
    const heightDerived = parentStackTrace.direction === 'HORIZONTAL' ? stackPlacementTrace.crossFill : stackPlacementTrace.mainFill;
    if (widthDerived) layoutDerivedLabels.add('widthMm');
    if (heightDerived) layoutDerivedLabels.add('heightMm');
  }
  if (gridPlacementTrace) {
    if (gridPlacementTrace.widthMode === 'FILL') layoutDerivedLabels.add('widthMm');
    if (gridPlacementTrace.heightMode === 'FILL') layoutDerivedLabels.add('heightMm');
  }
  const canvasLayoutMode = node.kind === 'canvas'
    ? node.props.find((prop) => prop.label === 'layoutMode')?.value ?? 'FREE'
    : null;
  const projectedProps = node.props.map((prop) => {
    if (!effectiveBox || !layoutDerivedLabels.has(prop.label)) return prop;
    const values: Record<string, number> = {
      xMm: effectiveBox.x,
      yMm: effectiveBox.y,
      widthMm: effectiveBox.w,
      heightMm: effectiveBox.h,
    };
    return {
      ...prop,
      value: layoutMillimetres(values[prop.label]!),
      bindable: false,
    };
  });
  const nodeVisibleProps = projectedProps.filter((prop) => {
    if (node.kind === 'repeat' && repeatWorkflowPropertyLabels.has(prop.label)) return false;
    if (node.kind === 'conditional' && conditionalWorkflowPropertyLabels.has(prop.label)) return false;
    if (node.kind === 'templateUse' && templateUseWorkflowPropertyLabels.has(prop.label)) return false;
    if (node.kind === 'canvas') {
      if (['direction', 'gapMm', 'mainAlign', 'crossAlign'].includes(prop.label)) return canvasLayoutMode === 'STACK';
      if (['columns', 'rows', 'columnGapMm', 'rowGapMm'].includes(prop.label)) return canvasLayoutMode === 'GRID';
    }
    if (!managedPlacementPropertyLabels.has(prop.label)) return true;
    if ((!parentUsesStackLayout && !parentUsesGridLayout) || !parent) return false;
    const widthMode = projectedProps.find((candidate) => candidate.label === 'placement.widthMode')?.value ?? 'FIXED';
    const heightMode = projectedProps.find((candidate) => candidate.label === 'placement.heightMode')?.value ?? 'FIXED';
    if (prop.label === 'placement.minWidthMm' || prop.label === 'placement.maxWidthMm') return widthMode === 'FILL';
    if (prop.label === 'placement.minHeightMm' || prop.label === 'placement.maxHeightMm') return heightMode === 'FILL';
    if (sharedManagedPlacementPropertyLabels.has(prop.label)) return true;
    if (stackPlacementPropertyLabels.has(prop.label)) {
      if (!parentUsesStackLayout) return false;
      const direction = parent.kind === 'canvas'
        ? canvasProjection(parent).direction
        : parent.props.find((candidate) => candidate.label === 'direction')?.value === 'HORIZONTAL' ? 'HORIZONTAL' : 'VERTICAL';
      if (prop.label === 'placement.fillWeight') return (direction === 'HORIZONTAL' ? widthMode : heightMode) === 'FILL';
      return (direction === 'HORIZONTAL' ? heightMode : widthMode) !== 'FILL';
    }
    if (!parentUsesGridLayout) return false;
    if (prop.label === 'placement.horizontalAlignSelf') return widthMode !== 'FILL';
    if (prop.label === 'placement.verticalAlignSelf') return heightMode !== 'FILL';
    return gridPlacementPropertyLabels.has(prop.label);
  });
  const visibleProps = mode === 'bindings' ? nodeVisibleProps.filter((prop) => prop.binding) : nodeVisibleProps;
  const authoredOrderByLabel = new Map(visibleProps.map((prop, index) => [prop.label, index]));
  const groupedProps = propertyGroups
    .map((group) => ({
      ...group,
      props: visibleProps
        .filter((prop) => propertyPresentationFor(prop, node.kind).group === group.key)
        .sort((left, right) => {
          const leftPriority = propertyOrderByLabel.get(left.label) ?? Number.MAX_SAFE_INTEGER;
          const rightPriority = propertyOrderByLabel.get(right.label) ?? Number.MAX_SAFE_INTEGER;
          return leftPriority - rightPriority
            || (authoredOrderByLabel.get(left.label) ?? 0) - (authoredOrderByLabel.get(right.label) ?? 0);
        }),
    }))
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
    templateUse: '嵌套模板容器',
  };
  const bindingCount = node.props.filter((prop) => prop.binding).length;
  const workflowFirst = node.kind === 'repeat' || node.kind === 'conditional' || node.kind === 'templateUse';
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
      <div className="rwtd-inspector-tabs" role="tablist" aria-label="右侧检查器">
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'properties'}
          className={mode === 'properties' ? 'active' : ''}
          onClick={() => setMode('properties')}
        >
          属性
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'bindings'}
          className={mode === 'bindings' ? 'active' : ''}
          onClick={() => setMode('bindings')}
        >
          绑定
          {bindingCount > 0 ? <span className="td-tab-count">{bindingCount}</span> : null}
        </button>
      </div>
      {mode === 'properties' && workflowFirst ? <StructuralFacts node={node} state={state} dispatch={dispatch} /> : null}
      <div className="td-inspector-section">
        {mode === 'properties' ? (
          <div className="td-inspector-intro">
            <strong>属性</strong>
            <span>{layoutOwnedPosition ? '父容器实时计算位置与 FILL 尺寸' : '按用途分组 · 行内可绑定'}</span>
          </div>
        ) : null}
        {mode === 'bindings' && groupedProps.length === 0 ? (
          <div className="td-binding-empty">
            <Link2 aria-hidden="true" size={18} />
            <strong>当前节点没有绑定</strong>
            <span>在属性页通过具体属性右侧的“绑定”入口添加。</span>
            <button type="button" className="button ghost-button" onClick={() => setMode('properties')}>返回属性</button>
          </div>
        ) : (
          <div className="td-prop-groups">
            {groupedProps.map((group) => {
              const collapsed = collapsedGroups.has(group.key);
              const headingId = `${inspectorId}-${mode}-${group.key}-heading`;
              const contentId = `${inspectorId}-${mode}-${group.key}-content`;
              return (
                <section key={group.key} className={`td-prop-group${collapsed ? ' is-collapsed' : ''}`} aria-labelledby={headingId}>
                  <div className="td-prop-group-head">
                    <h3 id={headingId}>
                      <button
                        type="button"
                        className="td-prop-group-toggle"
                        aria-expanded={!collapsed}
                        aria-controls={contentId}
                        onClick={() => setCollapsedGroups((current) => {
                          const next = new Set(current);
                          if (next.has(group.key)) next.delete(group.key);
                          else next.add(group.key);
                          return next;
                        })}
                      >
                        <ChevronRight aria-hidden="true" size={13} />
                        <span>{group.label}</span>
                      </button>
                    </h3>
                    <span>{group.props.length} 项</span>
                  </div>
                  {collapsed ? null : (
                    <ul id={contentId} className="td-prop-list">
                      {group.props.map((prop) => (
                        <InspectorPropRow key={prop.label} node={node} prop={prop} state={state} dispatch={dispatch} layoutDerived={layoutDerivedLabels.has(prop.label)} />
                      ))}
                    </ul>
                  )}
                </section>
              );
            })}
          </div>
        )}
      </div>
      {mode === 'properties' && !workflowFirst ? <StructuralFacts node={node} state={state} dispatch={dispatch} /> : null}
    </div>
  );
}

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

function targetTypeOf(nodeKind: NodeKind, label: string): string {
  return bindingTargetValueType(nodeKind, label) ?? 'unsupported';
}

function authoringPropertyKey(node: DesignerNode, prop: InspectorProp): string {
  if (node.kind !== 'text') return prop.label;
  const aliases: Record<string, string> = {
    'runs[0].text': 'text',
    'runs[0].fontRef': 'fontAsset',
    'runs[0].fontSizePt': 'fontSizePt',
    'runs[0].color': 'color',
  };
  return aliases[prop.label] ?? prop.label;
}

function BindingDialogContent({
  target,
  node,
  prop,
  state,
  dispatch,
}: {
  target: { nodeId: string; label: string };
  node: DesignerNode;
  prop: InspectorProp;
  state: DesignerState;
  dispatch: Dispatch<DesignerAction>;
}) {
  const sourceOptions = prototypeBindingSourceOptions(state, node.id, prop.label);
  const [draftSource, setDraftSource] = useState(() => prop.binding?.source ?? '');
  const selectedSourceAvailable = sourceOptions.some((option) => option.source === draftSource);
  const targetPropertyRef = targetPropertyRefOf(prop.label);
  const authoringKey = authoringPropertyKey(node, prop);
  const isFlattenedTextProperty = node.kind === 'text' && prop.label.startsWith('runs[0].');

  const save = () => {
    const trimmed = draftSource.trim();
    if (!trimmed || !selectedSourceAvailable) return;
    dispatch({
      type: 'save-binding',
      nodeId: target.nodeId,
      label: target.label,
      source: trimmed,
      bindingId: prop.binding?.id ?? newCanonicalUuid(),
    });
  };

  return (
    <div className="td-dialog-overlay">
      <div className="td-dialog td-binding-dialog" role="dialog" aria-modal="true" aria-labelledby="td-binding-title">
        <div className="td-binding-dialog-head">
          <h2 id="td-binding-title">{prop.binding ? '编辑绑定' : '设置绑定'}</h2>
          <p>为当前属性选择运行时数据来源，不会离开设计页面。</p>
        </div>
        <div className="td-binding-subject">
          <span className={`td-tree-kind kind-${node.kind}`} aria-hidden="true">{kindIcons[node.kind]}</span>
          <div>
            <strong>{propertyPresentationFor(prop, node.kind).name}</strong>
            <small>{node.name}</small>
          </div>
          <code>{authoringKey}</code>
        </div>
        <label className="td-form-row">
          <span>来源</span>
          <select
            value={draftSource}
            onChange={(event) => setDraftSource(event.target.value)}
          >
            <option value="">选择与目标类型兼容的来源…</option>
            {draftSource && !selectedSourceAvailable ? <option value={draftSource} disabled>当前来源在此位置不可用</option> : null}
            {(['系统字段', '循环域', '模板定义'] as const).map((group) => {
              const options = sourceOptions.filter((option) => option.group === group);
              return options.length ? (
                <optgroup key={group} label={group}>
                  {options.map((option) => (
                    <option key={option.source} value={option.source}>{option.label} · {option.detail}</option>
                  ))}
                </optgroup>
              ) : null;
            })}
          </select>
        </label>
        {sourceOptions.length === 0 ? <p className="td-panel-note">当前属性没有类型兼容且在词法范围内可见的来源。</p> : null}
        <p className="td-panel-note">未绑定时继续使用属性页中的基础值；绑定成功后，运行时数据会覆盖该基础值。</p>
        <details className="td-binding-advanced">
          <summary>目标引用与校验规则</summary>
          <div className="td-binding-advanced-body">
            <div className="td-binding-target">
              <span className="td-panel-subhead">目标属性</span>
              {isFlattenedTextProperty ? (
                <div className="td-binding-target-grid td-binding-target-single-value">
                  <code>{authoringKey}</code>
                  <div>
                    <strong>文本 · {propertyPresentationFor(prop, node.kind).name}</strong>
                    <small>目标类型 <code>{targetTypeOf(node.kind, prop.label)}</code></small>
                    <small>作者侧按单值属性绑定；内部保存适配负责映射到当前 Text Run 合同。</small>
                  </div>
                </div>
              ) : (
                <div className="td-binding-target-grid">
                  <code>{JSON.stringify(targetPropertyRef, null, 2)}</code>
                  <div>
                    <strong>{node.kind} · {authoringKey}</strong>
                    <small>目标类型 <code>{targetTypeOf(node.kind, prop.label)}</code></small>
                    <small>目标引用只在当前节点内生效；保存时不会写入多余的节点或策略标识。</small>
                  </div>
                </div>
              )}
            </div>
            <div className="td-target-shapes" aria-label="支持的目标引用形状">
              {['property', 'property.member', 'property[index]', 'property[index].member', 'property.member[index]'].map((shape) => <code key={shape}>{shape}</code>)}
            </div>
            {!isFlattenedTextProperty && targetPropertyRef.selectors.some((selector) => selector.kind === 'INDEX') ? (
              <div className="td-reorder-note">
                <strong>数组位置没有 item identity</strong>
                <span>重排默认仍指向 numeric index。若作者选择“保持原 Run”,客户端须在同一操作把 INDEX 0 原子改写为新下标。</span>
                <button type="button" className="td-mini-chip" onClick={() => dispatch({ type: 'set-notice', notice: '模拟重排：原数组项与 targetPropertyRef 的 numeric INDEX 已原子改写' })}>模拟保持原 item</button>
              </div>
            ) : null}
            <p className="td-inline-note chip-error-block">
              <AlertTriangle aria-hidden="true" size={12} />
              越界、重复目标和父子目标重叠会阻止保存；绑定值缺失、解析失败或类型不符时不会静默回退基础值。
            </p>
          </div>
        </details>
        <div className="td-dialog-actions">
          {prop.binding ? (
            <button
              type="button"
              className="button td-danger-button"
              onClick={() => dispatch({ type: 'remove-binding', nodeId: target.nodeId, label: target.label })}
            >
              解除绑定
            </button>
          ) : null}
          <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'close-binding' })}>
            取消
          </button>
          <button type="button" className="button primary-button" onClick={save} disabled={!draftSource.trim() || !selectedSourceAvailable}>
            保存绑定
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
      state={state}
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
  const canvas = canvasProjection(state.tree);
  const widthPx = outputPixels(canvas.widthMm, state.dpi);
  const heightPx = outputPixels(canvas.heightMm, state.dpi);
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
