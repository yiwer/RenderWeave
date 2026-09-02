/**
 * PROTOTYPE — throwaway authoring surface grounded in hbads-template-v2 source evidence.
 * Every mutation is reducer-only browser memory; no Template or RenderServer API is called.
 */
import {
  AlertTriangle,
  ArrowDown,
  ArrowLeft,
  ArrowLeftRight,
  ArrowRight,
  ArrowUp,
  Box,
  Braces,
  BringToFront,
  Check,
  ChevronDown,
  ChevronRight,
  Database,
  Eye,
  EyeOff,
  Frame,
  GitBranch,
  Grid3X3,
  GripVertical,
  Group,
  Hand,
  Image,
  Layers3,
  ListTree,
  Lock,
  LockKeyhole,
  MousePointer2,
  Pencil,
  Plus,
  Puzzle,
  Redo2,
  Repeat2,
  RotateCcw,
  Save,
  Search,
  SendToBack,
  Split,
  Trash2,
  Undo2,
  Unlock,
  X,
} from 'lucide-react';
import { useEffect, useRef, useState, type CSSProperties, type Dispatch, type DragEvent as ReactDragEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

import { Inspector, type PartProps } from './SharedParts';
import {
  projectPrototypeConditional,
  projectPrototypeConditionalRuntime,
  type ConditionalProjection,
} from './conditional-layout';
import { kindIcons } from './kind-icons';
import { projectPrototypeRepeat, type RepeatProjection } from './repeat-layout';
import { projectPrototypeLayout } from './stack-layout';
import {
  assets,
  canvasProjection,
  containerCatalog,
  definitionDomain,
  definitionDomainLabel,
  definitionValueType,
  elementCatalog,
  findNode,
  findParentNode,
  flattenDesignerTree,
  isContainerNodeKind,
  isLayoutManagingNode,
  isManagedLayoutKind,
  layerOrderCapabilities,
  nodeIds,
  repeatSourcesForDefinitions,
  schemaFields,
  templateMeta,
  valueSourceSummary,
  type AuthoringTab,
  type AbsoluteDemoPreset,
  type CanvasTool,
  type ConditionalDemoPreset,
  type ContainerPreset,
  type DesignerAction,
  type DesignerDefinition,
  type DesignerDefinitionDomain,
  type DesignerMappingCase,
  type DesignerMappingDefinition,
  type DesignerNode,
  type DesignerState,
  type DesignerValueSource,
  type DesignerValueType,
  type DraftBox,
  type ElementCatalogEntry,
  type GridDemoPreset,
  type ManagedLayoutKind,
  type RepeatDemoPreset,
  type StackDemoPreset,
  type TreeDropPlacement,
} from './model';

interface TabDefinition {
  key: AuthoringTab;
  label: string;
  kicker: string;
  title: string;
  icon: ReactNode;
}

const authoringTabs: TabDefinition[] = [
  { key: 'elements', label: '元素', kicker: 'COMPONENT LIBRARY', title: '基础元素', icon: <Plus aria-hidden="true" size={17} /> },
  { key: 'containers', label: '容器', kicker: 'STRUCTURE & COMPOSE', title: '容器', icon: <Box aria-hidden="true" size={17} /> },
  { key: 'images', label: '图片', kicker: 'IMAGE CATALOG', title: '图片素材', icon: <Image aria-hidden="true" size={17} /> },
  { key: 'sources', label: '数据源', kicker: 'AUTHORING SOURCES', title: '数据来源', icon: <Database aria-hidden="true" size={17} /> },
  { key: 'structure', label: '结构', kicker: 'LAYER TREE', title: '图层结构', icon: <Layers3 aria-hidden="true" size={17} /> },
];

function activeAuthoringTab(value: DesignerState['leftTab']): AuthoringTab {
  return authoringTabs.some((tab) => tab.key === value) ? value as AuthoringTab : 'elements';
}

export function StudioChrome({ state, dispatch, layoutName }: Pick<PartProps, 'state' | 'dispatch'> & { layoutName: string }) {
  const canvas = canvasProjection(state.tree);
  return (
    <header className="rwtd-v2-chrome">
      <div className="rwtd-v2-brand">
        <button type="button" aria-label="返回模板列表" title="原型：返回模板列表" onClick={() => dispatch({ type: 'set-notice', notice: '原型：返回模板列表' })}>
          <ArrowLeft aria-hidden="true" size={16} />
        </button>
        <span className="weave-mark" aria-hidden="true">RW</span>
        <div>
          <strong>活动价签 · {canvas.widthMm}×{canvas.heightMm}mm</strong>
          <span>{layoutName} · 快速交互原型</span>
        </div>
      </div>
      <div className="rwtd-v2-history" role="group" aria-label="原型历史">
        <button type="button" aria-label="撤销" title="撤销 · Ctrl+Z" onClick={() => dispatch({ type: 'set-notice', notice: '原型历史尚未持久化：本轮先验证交互路径' })}><Undo2 size={15} /></button>
        <button type="button" aria-label="重做" title="重做 · Ctrl+Shift+Z" onClick={() => dispatch({ type: 'set-notice', notice: '原型历史尚未持久化：本轮先验证交互路径' })}><Redo2 size={15} /></button>
      </div>
      <div className="rwtd-v2-document-meta">
        <span><Lock aria-hidden="true" size={11} />{templateMeta.schemaRef}</span>
        <code>{templateMeta.dslVersion}</code>
      </div>
      <div className="rwtd-v2-actions">
        <span className={`rwtd-v2-dirty${state.dirty ? ' is-dirty' : ''}`}><i />{state.dirty ? '内存草稿' : '本页已记录'}</span>
        <button type="button" className="rwtd-v2-save" onClick={() => dispatch({ type: 'save' })}><Save aria-hidden="true" size={15} />记录方案</button>
      </div>
    </header>
  );
}

const toolDefinitions: Array<{ value: CanvasTool; label: string; description: string; shortcut: string; icon: ReactNode }> = [
  { value: 'select', label: '选择', description: '选择、移动或缩放画布元素', shortcut: 'V', icon: <MousePointer2 aria-hidden="true" size={16} /> },
  { value: 'pan', label: '平移', description: '拖动画布视口，不修改模板内容', shortcut: 'H', icon: <Hand aria-hidden="true" size={16} /> },
];

export function AuthoringToolbar({
  state,
  dispatch,
  compact = false,
  interactionHint = 'V / H 切换 · Esc 返回选择',
}: Pick<PartProps, 'state' | 'dispatch'> & { compact?: boolean; interactionHint?: string }) {
  const effectiveTool: CanvasTool = state.spacePanActive ? 'pan' : state.activeTool;
  return (
    <div className={`rwtd-v2-toolbar${compact ? ' is-compact' : ''}`} role="toolbar" aria-label="画布操作模式">
      <div className="rwtd-v2-tool-modes">
        {toolDefinitions.map((tool) => (
          <button
            key={tool.value}
            type="button"
            aria-label={`${tool.label}工具`}
            aria-pressed={effectiveTool === tool.value}
            className={effectiveTool === tool.value ? 'active' : ''}
            onClick={() => dispatch({ type: 'set-tool', tool: tool.value })}
          >
            <span className="rwtd-v2-tool-icon">{tool.icon}</span>
            <span className="rwtd-v2-tool-copy"><strong>{tool.label}</strong>{compact ? null : <small>{tool.description}</small>}</span>
            <kbd>{tool.shortcut}</kbd>
          </button>
        ))}
      </div>
      <span className="rwtd-v2-key-hint">{state.spacePanActive ? 'Space 按住中 · 松开恢复' : interactionHint}</span>
      <button
        type="button"
        role="switch"
        aria-checked={state.showElementOutlines}
        className={`rwtd-v2-outline-toggle${state.showElementOutlines ? ' active' : ''}`}
        onClick={() => dispatch({ type: 'toggle-element-outlines' })}
      >
        <span><strong>元素边框</strong>{compact ? null : <small>仅辅助查看，不写入文档</small>}</span>
        <i aria-hidden="true"><span /></i>
      </button>
      <label className="rwtd-v2-zoom">
        <span>缩放</span>
        <select value={state.zoom} onChange={(event) => dispatch({ type: 'set-zoom', zoom: Number(event.target.value) })} aria-label="画布缩放">
          {[25, 50, 75, 100, 125, 150, 175, 200, 225, 250, 275, 300].map((zoom) => <option key={zoom} value={zoom}>{zoom}%</option>)}
        </select>
      </label>
      <button type="button" className="rwtd-v2-reset" onClick={() => dispatch({ type: 'reset-view' })}><RotateCcw aria-hidden="true" size={14} />复位</button>
    </div>
  );
}

export function AuthoringRail({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const active = activeAuthoringTab(state.leftTab);
  return (
    <nav className="rwtd-v2-rail" aria-label="模板创作面板">
      {authoringTabs.map((tab) => (
        <button
          key={tab.key}
          type="button"
          className={active === tab.key ? 'active' : ''}
          aria-pressed={active === tab.key}
          aria-label={tab.label}
          onClick={() => dispatch({ type: 'set-tab', tab: tab.key })}
        >
          {tab.icon}
          <span>{tab.label}</span>
        </button>
      ))}
    </nav>
  );
}

export function AuthoringTabStrip({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const active = activeAuthoringTab(state.leftTab);
  return (
    <nav className="rwtd-v2-tab-strip" aria-label="模板创作面板">
      {authoringTabs.map((tab) => (
        <button key={tab.key} type="button" className={active === tab.key ? 'active' : ''} aria-pressed={active === tab.key} onClick={() => dispatch({ type: 'set-tab', tab: tab.key })}>
          {tab.icon}<span>{tab.label}</span>
        </button>
      ))}
    </nav>
  );
}

function PanelHeading({ tab }: { tab: TabDefinition }) {
  return (
    <header className="rwtd-v2-panel-heading">
      <span>{tab.kicker}</span>
      <h2>{tab.title}</h2>
    </header>
  );
}

function catalogMatches(entry: ElementCatalogEntry, query: string): boolean {
  const normalized = query.trim().toLocaleLowerCase('zh-CN');
  if (!normalized) return true;
  return [entry.label, entry.description, entry.kind, ...entry.searchTerms].join(' ').toLocaleLowerCase('zh-CN').includes(normalized);
}

const ELEMENT_DRAG_MIME = 'application/x-renderweave-element-kind';

interface NodeContextRequest {
  nodeId: string;
  x: number;
  y: number;
}

function ElementLibrary({ dispatch }: { dispatch: Dispatch<DesignerAction> }) {
  const [query, setQuery] = useState('');
  const [draggingKind, setDraggingKind] = useState<ElementCatalogEntry['kind'] | null>(null);
  const filtered = elementCatalog.filter((entry) => catalogMatches(entry, query));
  return (
    <div className="rwtd-v2-panel-flow">
      <label className="rwtd-v2-search"><Search aria-hidden="true" size={16} /><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="文本 / 图形 / 二维码…" aria-label="搜索元素" /></label>
      <div className="rwtd-v2-catalog-summary"><span>{filtered.length} 个可用元素</span><span>统一 pt 文档 · 统一历史</span></div>
      {(['basic', 'domain'] as const).map((group) => {
        const entries = filtered.filter((entry) => entry.group === group);
        if (entries.length === 0) return null;
        return (
          <section key={group} className="rwtd-v2-catalog-section">
            <div className="rwtd-v2-section-title"><strong>{group === 'basic' ? '基础视觉' : '领域功能'}</strong><span>{group === 'basic' ? '视觉投影' : 'ADAPTER 渲染'}</span></div>
            <div className="rwtd-v2-component-grid">
              {entries.map((entry) => (
                <button
                  key={entry.kind}
                  type="button"
                  draggable
                  className={draggingKind === entry.kind ? 'is-dragging' : ''}
                  aria-label={`添加${entry.label}`}
                  title="拖到画板定位，或点击自动添加"
                  onClick={() => dispatch({ type: 'insert-node', kind: entry.kind })}
                  onDragStart={(event) => {
                    event.dataTransfer.effectAllowed = 'copy';
                    event.dataTransfer.setData(ELEMENT_DRAG_MIME, entry.kind);
                    event.dataTransfer.setData('text/plain', entry.kind);
                    setDraggingKind(entry.kind);
                  }}
                  onDragEnd={() => setDraggingKind(null)}
                >
                  <span className="rwtd-v2-card-icon">{kindIcons[entry.kind]}{entry.adapter ? <i>ADAPTER</i> : null}</span>
                  <span className="rwtd-v2-card-copy"><strong>{entry.label}</strong><small>{entry.description}</small><em>{entry.slotSummary}</em></span>
                </button>
              ))}
            </div>
          </section>
        );
      })}
      {filtered.length === 0 ? <div className="rwtd-v2-empty"><Search size={20} /><strong>没有匹配的元素</strong><span>试试“形状”“条码”或英文 kind。</span></div> : null}
      <p className="rwtd-v2-panel-note">拖到画板可按落点创建；点击则自动排布。两种方式都进入同一份内存文档、结构树和选择模型。</p>
    </div>
  );
}

function wrapPresetIcon(preset: ContainerPreset): ReactNode {
  if (preset === 'group') return <Group aria-hidden="true" size={16} />;
  if (preset === 'frame') return <Frame aria-hidden="true" size={16} />;
  if (preset === 'stack') return kindIcons.stack;
  if (preset === 'grid') return <Grid3X3 aria-hidden="true" size={16} />;
  if (preset === 'repeat') return <Repeat2 aria-hidden="true" size={16} />;
  if (preset === 'templateUse') return <Puzzle aria-hidden="true" size={16} />;
  return <Layers3 aria-hidden="true" size={16} />;
}

const stackDemos: Array<{ preset: StackDemoPreset; icon: ReactNode; title: string; detail: string }> = [
  { preset: 'vertical-start', icon: <ArrowDown aria-hidden="true" size={16} />, title: '纵向卡片列', detail: 'START · STRETCH · padding 4/6mm' },
  { preset: 'horizontal-center', icon: <ArrowRight aria-hidden="true" size={16} />, title: '横向居中栏', detail: 'CENTER / CENTER · gap 2mm' },
  { preset: 'horizontal-between', icon: <ArrowLeftRight aria-hidden="true" size={16} />, title: '横向两端分布', detail: 'SPACE_BETWEEN · STRETCH' },
  { preset: 'horizontal-fill', icon: <ArrowLeftRight aria-hidden="true" size={16} />, title: '横向权重填充', detail: 'FILL 1:2 · max freeze · signed margin' },
];

const absoluteDemos: Array<{ preset: AbsoluteDemoPreset; icon: ReactNode; title: string; detail: string }> = [
  { preset: 'group-hug', icon: <Group aria-hidden="true" size={16} />, title: '自由分组边界', detail: 'HUG · 子项并集 · 原点归一化' },
  { preset: 'frame-content', icon: <Frame aria-hidden="true" size={16} />, title: '框架内容区', detail: 'ContentBox · 局部绝对定位 · clip' },
];

const gridDemos: Array<{ preset: GridDemoPreset; icon: ReactNode; title: string; detail: string }> = [
  { preset: 'fraction-cards', icon: <Grid3X3 aria-hidden="true" size={16} />, title: '比例卡片网格', detail: '1* / 2* / 1* · 跨列标题' },
  { preset: 'auto-span', icon: <ArrowLeftRight aria-hidden="true" size={16} />, title: 'AUTO 跨轨约束', detail: 'auto / auto / 1* · deficit 均分' },
  { preset: 'alignment-fill', icon: <Frame aria-hidden="true" size={16} />, title: '单元对齐与填充', detail: '2×2 · span · signed margin · FILL' },
];

const repeatDemos: Array<{ preset: RepeatDemoPreset; icon: ReactNode; title: string; detail: string }> = [
  { preset: 'scalar-tags', icon: <Repeat2 aria-hidden="true" size={16} />, title: '标量标签数组', detail: 'array[text] · authored 单项子树 · 3 列' },
  { preset: 'reference-offers', icon: <Layers3 aria-hidden="true" size={16} />, title: '引用对象数组', detail: 'offer-card@v2 · 显式 TemplateUse · 2 列' },
  { preset: 'repair-state', icon: <RotateCcw aria-hidden="true" size={16} />, title: '切换数据源待修复', detail: '保留 authored 内容 · 显式 schema 冲突' },
];

const conditionalDemos: Array<{ preset: ConditionalDemoPreset; icon: ReactNode; title: string; detail: string }> = [
  { preset: 'condition-true', icon: <Split aria-hidden="true" size={16} />, title: '条件为 TRUE', detail: 'true branch → 无外观 Frame · 参与布局' },
  { preset: 'condition-false', icon: <EyeOff aria-hidden="true" size={16} />, title: '条件为 FALSE', detail: '整棵子树剪枝 · Stack 后项前移' },
  { preset: 'condition-absent', icon: <AlertTriangle aria-hidden="true" size={16} />, title: '条件值缺失', detail: 'typed ABSENT · FALSE / ERROR 策略' },
];

function ContainerLibrary({ dispatch }: Pick<PartProps, 'dispatch'>) {
  const [draggingPreset, setDraggingPreset] = useState<ContainerPreset | null>(null);
  return (
    <div className="rwtd-v2-panel-flow">
      <p className="rwtd-v2-lead">布局容器管理 children[] 与坐标语义；嵌套模板容器是无 authored children 的 TemplateUse 宿主，通过属性和兼容模板生成内容。</p>
      <section className="rwtd-v2-stack-demo-section" aria-labelledby="rwtd-absolute-demo-title">
        <div className="rwtd-v2-section-title"><strong id="rwtd-absolute-demo-title">自由定位 Demo</strong><span>GROUP · FRAME</span></div>
        <div className="rwtd-v2-stack-demo-list">
          {absoluteDemos.map((demo) => (
            <button
              key={demo.preset}
              type="button"
              aria-label={`载入${demo.title}演示`}
              onClick={() => dispatch({ type: 'load-absolute-demo', preset: demo.preset })}
            >
              <span aria-hidden="true">{demo.icon}</span>
              <span><strong>{demo.title}</strong><small>{demo.detail}</small></span>
              <em>载入</em>
            </button>
          ))}
        </div>
        <p>Group 没有自身外观，边界由子项并集实时派生；Frame 拥有固定 LayoutBox，描边与内边距形成子项坐标原点。</p>
      </section>
      <section className="rwtd-v2-stack-demo-section" aria-labelledby="rwtd-stack-demo-title">
        <div className="rwtd-v2-section-title"><strong id="rwtd-stack-demo-title">实时堆叠 Demo</strong><span>DEFINITE · MM</span></div>
        <div className="rwtd-v2-stack-demo-list">
          {stackDemos.map((demo) => (
            <button
              key={demo.preset}
              type="button"
              aria-label={`载入${demo.title}演示`}
              onClick={() => dispatch({ type: 'load-stack-demo', preset: demo.preset })}
            >
              <span aria-hidden="true">{demo.icon}</span>
              <span><strong>{demo.title}</strong><small>{demo.detail}</small></span>
              <em>载入</em>
            </button>
          ))}
        </div>
        <p>载入会替换当前浏览器内存场景；刷新即可回到原始样例。选中容器后直接修改布局属性观察实时重排。</p>
      </section>
      <section className="rwtd-v2-stack-demo-section rwtd-v2-grid-demo-section" aria-labelledby="rwtd-grid-demo-title">
        <div className="rwtd-v2-section-title"><strong id="rwtd-grid-demo-title">实时网格 Demo</strong><span>FIXED · AUTO · FRACTION</span></div>
        <div className="rwtd-v2-stack-demo-list">
          {gridDemos.map((demo) => (
            <button
              key={demo.preset}
              type="button"
              aria-label={`载入${demo.title}演示`}
              onClick={() => dispatch({ type: 'load-grid-demo', preset: demo.preset })}
            >
              <span aria-hidden="true">{demo.icon}</span>
              <span><strong>{demo.title}</strong><small>{demo.detail}</small></span>
              <em>载入</em>
            </button>
          ))}
        </div>
        <p>轨道逐条定义：<code>12</code> 是固定 12mm，<code>auto</code> 适应内容，<code>1*</code> / <code>2*</code> 按权重分配剩余空间；子项显式设置起始行列与跨度。</p>
      </section>
      <section className="rwtd-v2-stack-demo-section rwtd-v2-repeat-demo-section" aria-labelledby="rwtd-repeat-demo-title">
        <div className="rwtd-v2-section-title"><strong id="rwtd-repeat-demo-title">循环容器 Demo</strong><span>COLLECTION → ITEM SUBTREE → PACKING</span></div>
        <div className="rwtd-v2-stack-demo-list">
          {repeatDemos.map((demo) => (
            <button
              key={demo.preset}
              type="button"
              aria-label={`载入${demo.title}演示`}
              onClick={() => dispatch({ type: 'load-repeat-demo', preset: demo.preset })}
            >
              <span aria-hidden="true">{demo.icon}</span>
              <span><strong>{demo.title}</strong><small>{demo.detail}</small></span>
              <em>载入</em>
            </button>
          ))}
        </div>
        <p>Repeat 只选择数组来源并维护一份 authored 单项子树；若子树显式含 TemplateUse，它使用完整循环项。数组字段不会进入独立嵌套模板的属性候选。</p>
      </section>
      <section className="rwtd-v2-stack-demo-section rwtd-v2-conditional-demo-section" aria-labelledby="rwtd-conditional-demo-title">
        <div className="rwtd-v2-section-title"><strong id="rwtd-conditional-demo-title">条件容器 Demo</strong><span>BOOLEAN → PRUNE / FRAME</span></div>
        <div className="rwtd-v2-stack-demo-list">
          {conditionalDemos.map((demo) => (
            <button
              key={demo.preset}
              type="button"
              aria-label={`载入${demo.title}演示`}
              onClick={() => dispatch({ type: 'load-conditional-demo', preset: demo.preset })}
            >
              <span aria-hidden="true">{demo.icon}</span>
              <span><strong>{demo.title}</strong><small>{demo.detail}</small></span>
              <em>载入</em>
            </button>
          ))}
        </div>
        <p>Conditional 只有 true 分支。FALSE 会在 Binding、布局、Asset 与输出前删除整棵子树；编辑器仍保留可选择的虚线占位。</p>
      </section>
      <div className="rwtd-v2-component-grid rwtd-v2-container-grid">
        {containerCatalog.map((entry) => (
          <button
            key={entry.preset}
            type="button"
            draggable
            data-container-preset={entry.preset}
            className={draggingPreset === entry.preset ? 'is-dragging' : ''}
            title="拖到画板定位，或点击自动添加"
            onClick={() => dispatch({ type: 'insert-node', kind: entry.kind, preset: entry.preset })}
            onDragStart={(event) => {
              event.dataTransfer.effectAllowed = 'copy';
              event.dataTransfer.setData(ELEMENT_DRAG_MIME, entry.kind);
              event.dataTransfer.setData('text/plain', entry.kind);
              setDraggingPreset(entry.preset);
            }}
            onDragEnd={() => setDraggingPreset(null)}
          >
            <span className="rwtd-v2-card-icon">{wrapPresetIcon(entry.preset)}</span>
            <span className="rwtd-v2-card-copy"><strong>{entry.label}</strong><small>{entry.description}</small><em>{entry.kind === 'stack' ? '方向在布局属性中设置' : entry.kind === 'templateUse' ? 'PROPERTY → TEMPLATE' : entry.preset}</em></span>
          </button>
        ))}
      </div>
      <p className="rwtd-v2-panel-note">Stack / Grid 派生画布几何；TemplateUse 仍是 DesignDSL 叶子，分类到容器栏只是为了符合作者的创建心智。</p>
    </div>
  );
}

function ImagesLibrary({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const [query, setQuery] = useState('');
  const selected = findNode(state.tree, state.selectedNodeId);
  const rows = assets.filter((asset) => asset.kind === 'IMAGE' && asset.status === 'ACTIVE').filter((asset) => asset.name.toLocaleLowerCase().includes(query.toLocaleLowerCase()));
  return (
    <div className="rwtd-v2-panel-flow">
      <label className="rwtd-v2-search"><Search aria-hidden="true" size={16} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索图片名称或标签…" aria-label="搜索图片" /></label>
      <div className="rwtd-v2-catalog-summary"><span>{rows.length} 个可用图片</span><span>{selected?.kind === 'image' ? '点击替换当前图片' : '点击添加图片元素'}</span></div>
      <div className="rwtd-v2-image-list">
        {rows.map((asset) => (
          <button key={asset.id} type="button" onClick={() => dispatch({ type: 'bind-image', assetId: asset.id })}>
            <span className="rwtd-v2-image-icon"><Image aria-hidden="true" size={19} /></span>
            <span><strong>{asset.name}</strong><small>{asset.detail}</small><em>{asset.tags.join(' · ')}</em></span>
            <ChevronRight aria-hidden="true" size={15} />
          </button>
        ))}
      </div>
      <div className="rwtd-v2-source-boundary"><Lock size={13} /><span>原型只使用现有图片目录项；不上传文件、不复制内容、不虚构资源引用。</span></div>
    </div>
  );
}

type SourceSection = 'system' | 'custom' | 'derived';

interface SchemaSourceConstraint {
  keyword: string;
  value: string;
  description: string;
}

interface SchemaSourceField {
  id: string;
  label: string;
  pointer: string;
  type: string;
  presence: 'required' | 'optional';
  scope: string;
  expression?: string;
  description: string;
  constraints?: SchemaSourceConstraint[];
  childSchemaRef?: string;
  children?: SchemaSourceField[];
}

interface PrototypeMappingRule {
  id: string;
  operator: DesignerMappingCase['operator'];
  operand: string;
  result: string;
}

interface CustomDefinitionDraft {
  editingId?: string;
  kind: 'CUSTOM';
  name: string;
  valueType: DesignerValueType;
  exposure: 'PUBLIC' | 'PRIVATE';
  defaultValue: string;
}

interface ExpressionDefinitionDraft {
  editingId?: string;
  kind: 'EXPRESSION';
  name: string;
  valueType: DesignerValueType;
  domain: DesignerDefinitionDomain;
  input: DesignerValueSource;
  source: string;
}

type DefinitionDraft = CustomDefinitionDraft | ExpressionDefinitionDraft;

interface MappingDraft {
  editingId?: string;
  name: string;
  input: DesignerValueSource;
  outputType: DesignerValueType;
  domain: DesignerDefinitionDomain;
  rules: PrototypeMappingRule[];
  otherwise: string;
}

const schemaSourceTree: SchemaSourceField[] = [
  {
    id: 'schema-title', label: '标题', pointer: '/title', type: 'text', presence: 'required', scope: '调用上下文', expression: 'context(invocation, /title)', description: '模板根数据中的标题文本。',
    constraints: [
      { keyword: 'minLength', value: '1', description: '至少 1 个 Unicode code point' },
      { keyword: 'maxLength', value: '48', description: '最多 48 个 Unicode code points' },
    ],
  },
  {
    id: 'schema-price', label: '价格', pointer: '/price', type: 'decimal', presence: 'required', scope: '调用上下文', expression: 'context(invocation, /price)', description: '模板根数据中的十进制定价。',
    constraints: [
      { keyword: 'min', value: '0', description: '数值不得小于 0' },
      { keyword: 'multipleOf', value: '0.01', description: '数值必须是 0.01 的整数倍' },
    ],
  },
  { id: 'schema-promotion-enabled', label: '促销启用', pointer: '/promotionEnabled', type: 'boolean', presence: 'required', scope: '调用上下文', expression: 'context(invocation, /promotionEnabled)', description: '可直接用于 Conditional 的布尔来源。' },
  { id: 'schema-member-eligible', label: '会员适用', pointer: '/memberEligible', type: 'boolean', presence: 'optional', scope: '调用上下文', expression: 'context(invocation, /memberEligible)', description: '可选布尔字段；使用方必须显式处理 typed ABSENT。' },
  { id: 'schema-launch-date', label: '上线日期', pointer: '/launchDate', type: 'date', presence: 'optional', scope: '调用上下文', expression: 'context(invocation, /launchDate)', description: '可选日期字段；缺失不会回退属性基线。' },
  {
    id: 'schema-tags', label: '标签列表', pointer: '/tags', type: 'list<text>', presence: 'optional', scope: '调用上下文', expression: 'context(invocation, /tags)',
    description: 'Repeat 可用的标量数组；进入循环后，单项映射到 system-basic-text@v1。', childSchemaRef: 'system-basic-text@v1',
    constraints: [{ keyword: 'maxItems', value: '6', description: '数组最多包含 6 项' }],
  },
  {
    id: 'schema-offers', label: '优惠卡列表', pointer: '/offers', type: 'list<reference>', presence: 'required', scope: '调用上下文', expression: 'context(invocation, /offers)',
    description: 'Repeat 可用的引用数组；每项精确引用 offer-card@v2。', childSchemaRef: 'offer-card@v2',
    children: [
      { id: 'schema-offers-name', label: '优惠名称', pointer: '/name', type: 'text', presence: 'required', scope: '循环项上下文', description: '当前优惠项用于展示的名称。' },
      { id: 'schema-offers-price', label: '优惠价', pointer: '/price', type: 'decimal', presence: 'required', scope: '循环项上下文', description: '当前优惠项的十进制定价。' },
      { id: 'schema-offers-badge', label: '角标', pointer: '/badge', type: 'text', presence: 'optional', scope: '循环项上下文', description: '当前优惠项可选的短角标文本。' },
    ],
  },
  {
    id: 'schema-brand', label: '品牌', pointer: '/brand', type: 'reference', presence: 'required', scope: '调用上下文',
    description: '引用本身不是可绑定标量；内部标量字段可用于属性绑定。', childSchemaRef: 'brand@v1',
    children: [
      { id: 'schema-brand-name', label: '品牌名称', pointer: '/brand/name', type: 'text', presence: 'required', scope: '调用上下文', expression: 'context(invocation, /brand/name)', description: '沿精确引用路径解析的品牌名称。' },
    ],
  },
  { id: 'schema-sku', label: '商品编码', pointer: '/sku', type: 'text', presence: 'required', scope: '调用上下文', expression: 'context(invocation, /sku)', description: '模板根数据中的稳定商品编码。' },
];

const definitionValueTypes: DesignerValueType[] = [
  'text',
  'decimal',
  'boolean',
  'date',
  'time',
  'color',
  'imageRef',
  'fontRef',
  'list<text>',
  'list<decimal>',
  'list<boolean>',
  'list<date>',
  'list<time>',
  'list<imageRef>',
  'list<fontRef>',
];

interface DefinitionDomainOption {
  key: string;
  label: string;
  detail: string;
  domain: DesignerDefinitionDomain;
}

interface BindableSourceOption {
  key: string;
  label: string;
  detail: string;
  type: string;
  source: DesignerValueSource;
}

function domainKey(domain: DesignerDefinitionDomain): string {
  return domain === 'invocation' ? 'invocation' : `loop:${domain.loopId}`;
}

function definitionDomainOptions(tree: DesignerNode): DefinitionDomainOption[] {
  return [
    { key: 'invocation', label: '模板范围', detail: templateMeta.schemaRef, domain: 'invocation' },
    ...flattenDesignerTree(tree)
      .filter((node) => node.kind === 'repeat' && node.loopId)
      .map((node) => ({
        key: `loop:${node.loopId}`,
        label: `每个 ${node.name}`,
        detail: node.loopId!,
        domain: { kind: 'loop' as const, loopId: node.loopId! },
      })),
  ];
}

function flattenSchemaSourceFields(fields: readonly SchemaSourceField[]): SchemaSourceField[] {
  return fields.flatMap((field) => [field, ...flattenSchemaSourceFields(field.children ?? [])]);
}

function sourceOptionKey(source: DesignerValueSource): string {
  return JSON.stringify(source);
}

function bindableSourceOptions(state: DesignerState, domain: DesignerDefinitionDomain): BindableSourceOption[] {
  if (domain === 'invocation') {
    return flattenSchemaSourceFields(schemaSourceTree)
      .filter((field) => field.expression && field.type !== 'reference' && !field.type.startsWith('list<'))
      .map((field) => {
        const source: DesignerValueSource = { kind: 'context', domain: 'invocation', pointer: field.pointer };
        return { key: sourceOptionKey(source), label: field.label, detail: field.pointer, type: field.type, source };
      });
  }

  const repeat = flattenDesignerTree(state.tree).find((node) => node.kind === 'repeat' && node.loopId === domain.loopId);
  const expression = repeat?.props.find((property) => property.label === 'items')?.value ?? '';
  const repeatSource = repeatSourcesForDefinitions(state.definitions).find((source) => source.expression === expression);
  const indexSource: DesignerValueSource = { kind: 'loopIndex', loopId: domain.loopId };
  const indexOption: BindableSourceOption = {
    key: sourceOptionKey(indexSource),
    label: '原始索引',
    detail: `loopIndex · ${domain.loopId.slice(0, 8)}`,
    type: 'decimal',
    source: indexSource,
  };
  if (!repeatSource) return [indexOption];
  if (repeatSource.sourceType === 'SCALAR_LIST') {
    const source: DesignerValueSource = { kind: 'context', domain, pointer: '/value' };
    return [indexOption, {
      key: sourceOptionKey(source),
      label: '当前值',
      detail: `/value · ${repeatSource.itemStaticSchemaRef}`,
      type: repeatSource.itemValueType ?? 'text',
      source,
    }];
  }
  return [
    indexOption,
    ...([
      ['优惠名称', '/name', 'text'],
      ['优惠价', '/price', 'decimal'],
      ['角标', '/badge', 'text'],
    ] as const).map(([label, pointer, type]) => {
      const source: DesignerValueSource = { kind: 'context', domain, pointer };
      return { key: sourceOptionKey(source), label, detail: `${pointer} · ${repeatSource.itemStaticSchemaRef}`, type, source };
    }),
  ];
}

function designerNodePath(tree: DesignerNode, nodeId: string, ancestors: DesignerNode[] = []): DesignerNode[] | null {
  const path = [...ancestors, tree];
  if (tree.id === nodeId) return path;
  for (const child of tree.children) {
    const nested = designerNodePath(child, nodeId, path);
    if (nested) return nested;
  }
  return null;
}

function activeLoopNodes(state: DesignerState): DesignerNode[] {
  const path = designerNodePath(state.tree, state.selectedNodeId) ?? [];
  return path.slice(0, -1).filter((node) => node.kind === 'repeat' && node.loopId);
}

function sourceFieldMatches(field: SchemaSourceField, query: string): boolean {
  const normalized = query.trim().toLocaleLowerCase('zh-CN');
  if (!normalized) return true;
  const selfMatches = [field.label, field.pointer, field.type, field.childSchemaRef, field.description]
    .filter(Boolean)
    .join(' ')
    .toLocaleLowerCase('zh-CN')
    .includes(normalized);
  return selfMatches || Boolean(field.children?.some((child) => sourceFieldMatches(child, query)));
}

function findSchemaSourceField(id: string, fields: readonly SchemaSourceField[] = schemaSourceTree): SchemaSourceField | undefined {
  for (const field of fields) {
    if (field.id === id) return field;
    const nested = field.children ? findSchemaSourceField(id, field.children) : undefined;
    if (nested) return nested;
  }
  return undefined;
}

function schemaSourceFieldKey(field: SchemaSourceField): string {
  const segment = field.pointer.split('/').filter(Boolean).at(-1) ?? field.pointer;
  return segment.replace(/~1/g, '/').replace(/~0/g, '~');
}

function isReferenceSourceField(field: SchemaSourceField): boolean {
  return field.type === 'reference' || field.type.includes('<reference>');
}

function SourceFieldTree({
  fields,
  depth,
  query,
  expanded,
  selectedId,
  onToggle,
  onSelect,
  onView,
}: {
  fields: readonly SchemaSourceField[];
  depth: number;
  query: string;
  expanded: ReadonlySet<string>;
  selectedId: string;
  onToggle(id: string): void;
  onSelect(id: string): void;
  onView(id: string): void;
}) {
  return fields.map((field) => {
    if (!sourceFieldMatches(field, query)) return null;
    const hasChildren = Boolean(field.children?.length);
    const canExpand = hasChildren && !isReferenceSourceField(field);
    const isExpanded = canExpand && (Boolean(query.trim()) || expanded.has(field.id));
    return (
      <div className="rwtd-v2-source-tree-branch" key={field.id}>
        <div
          className={`rwtd-v2-source-field${selectedId === field.id ? ' is-selected' : ''}`}
          style={{ '--rwtd-source-depth': depth } as CSSProperties}
        >
          {canExpand ? (
            <button type="button" className="rwtd-v2-source-disclosure" aria-label={`${isExpanded ? '折叠' : '展开'}${field.label}`} aria-expanded={isExpanded} onClick={() => onToggle(field.id)}>
              {isExpanded ? <ChevronDown aria-hidden="true" size={13} /> : <ChevronRight aria-hidden="true" size={13} />}
            </button>
          ) : <span className="rwtd-v2-source-disclosure is-leaf" aria-hidden="true" />}
          <button type="button" className="rwtd-v2-source-field-main" aria-pressed={selectedId === field.id} onClick={() => onSelect(field.id)}>
            <span className="rwtd-v2-source-node-icon">{hasChildren ? <Braces aria-hidden="true" size={13} /> : <span aria-hidden="true" />}</span>
            <span className="rwtd-v2-source-field-copy">
              <strong>{field.label}</strong>
              <span className="rwtd-v2-source-key" title={`完整字段路径：${field.pointer}`}><small>KEY</small><code>{schemaSourceFieldKey(field)}</code></span>
            </span>
            <span className="rwtd-v2-source-field-meta"><em>{field.type}</em><i data-presence={field.presence}>{field.presence === 'required' ? '必填' : '可选'}</i></span>
          </button>
          <button type="button" className="rwtd-v2-source-card-action" aria-label={`查看数据源 ${field.label}`} onClick={() => onView(field.id)}><Eye aria-hidden="true" size={11} />查看</button>
        </div>
        {canExpand && isExpanded ? (
          <div className="rwtd-v2-source-tree-children">
            <div className="rwtd-v2-source-schema-edge" style={{ '--rwtd-source-depth': depth + 1 } as CSSProperties}>
              <LockKeyhole aria-hidden="true" size={11} /><code>{field.childSchemaRef}</code><span>只读字段</span>
            </div>
            <SourceFieldTree fields={field.children ?? []} depth={depth + 1} query={query} expanded={expanded} selectedId={selectedId} onToggle={onToggle} onSelect={onSelect} onView={onView} />
          </div>
        ) : null}
      </div>
    );
  });
}

function ReferenceStructureList({ fields, depth = 0 }: { fields: readonly SchemaSourceField[]; depth?: number }) {
  return (
    <ul className="rwtd-v2-reference-structure-list">
      {fields.map((nestedField) => (
        <li key={nestedField.id} style={{ '--rwtd-reference-depth': depth } as CSSProperties}>
          <span className="rwtd-v2-reference-structure-marker" aria-hidden="true">{nestedField.children?.length ? <Braces size={12} /> : <span />}</span>
          <div className="rwtd-v2-reference-structure-copy">
            <strong>{nestedField.label}</strong>
            <span className="rwtd-v2-source-key"><small>KEY</small><code>{schemaSourceFieldKey(nestedField)}</code></span>
          </div>
          <span className="rwtd-v2-reference-structure-meta"><code>{nestedField.type}</code><i data-presence={nestedField.presence}>{nestedField.presence === 'required' ? '必填' : '可选'}</i></span>
          <p>{nestedField.description}</p>
          {nestedField.children?.length ? <ReferenceStructureList fields={nestedField.children} depth={depth + 1} /> : null}
        </li>
      ))}
    </ul>
  );
}

function schemaOwnerFor(field: SchemaSourceField): string {
  if (field.id.startsWith('schema-tags-')) return 'system-basic-text@v1';
  if (field.id.startsWith('schema-offers-')) return 'offer-card@v2';
  if (field.id.startsWith('schema-brand-')) return 'brand@v1';
  return templateMeta.schemaRef;
}

function SourceDetailsDialog({
  field,
  definition,
  onClose,
  onEdit,
}: {
  field?: SchemaSourceField;
  definition?: DesignerDefinition;
  onClose(): void;
  onEdit(definition: DesignerDefinition): void;
}) {
  const isSystem = Boolean(field);
  const title = field?.label ?? definition?.name ?? '数据源';
  const sourceKind = isSystem
    ? '系统数据源'
    : definition?.kind === 'CUSTOM'
      ? '自定义输入'
      : definition?.kind === 'MAPPING'
        ? '映射定义'
        : '表达式定义';
  const valueType = field?.type ?? (definition ? definitionValueType(definition) : '—');
  const domain = field?.scope ?? (definition ? definitionDomainLabel(definition) : '—');
  const identity = field?.pointer ?? definition?.id ?? '—';
  const definitionSummary = definition?.kind === 'CUSTOM'
    ? definition.defaultValue
    : definition?.kind === 'MAPPING'
      ? valueSourceSummary(definition.input)
      : definition?.source ?? '—';
  const definitionSummaryLabel = definition?.kind === 'CUSTOM'
    ? '默认值'
    : definition?.kind === 'MAPPING'
      ? '输入来源'
      : '表达式';
  const definitionSummaryKind = definition?.kind === 'CUSTOM'
    ? 'typed literal'
    : definition?.kind === 'MAPPING'
      ? 'ValueSource'
      : 'closed expression';
  const definitionConstraints = [
    `输出必须符合声明的 ${valueType} 类型。`,
    definition && definitionDomain(definition) !== 'invocation'
      ? '只能在声明的循环词法域及其后代中使用。'
      : '属于当前模板调用；不会自动进入嵌套模板。',
    definition?.kind === 'CUSTOM'
      ? '默认值是非 null typed literal；只有 PUBLIC 可被根输入覆盖或作为 child fill 目标。'
      : definition?.kind === 'MAPPING'
        ? '按 authored 顺序首个 case 命中；otherwise 必填。'
        : '只读取显式 alias 输入，不拥有隐式环境或任意 IO。',
  ];
  const dialog = (
    <div className="rwtd-v2-source-dialog-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="rwtd-v2-source-dialog" role="dialog" aria-modal="true" aria-labelledby="rwtd-source-dialog-title">
        <header>
          <span className="rwtd-v2-source-dialog-icon">{isSystem ? <Database size={18} /> : definition?.kind === 'MAPPING' ? <GitBranch size={18} /> : <Braces size={18} />}</span>
          <div><small>SOURCE DETAILS</small><h3 id="rwtd-source-dialog-title">{title}</h3></div>
          <span className={`rwtd-v2-source-dialog-kind${isSystem ? ' is-readonly' : ''}`}>{isSystem ? <LockKeyhole size={11} /> : null}{sourceKind}</span>
          <button type="button" aria-label="关闭数据源详情" autoFocus onClick={onClose}><X size={16} /></button>
        </header>

        <div className="rwtd-v2-source-dialog-body">
            <dl className="rwtd-v2-source-facts">
              <div><dt>数据名称</dt><dd>{title}</dd></div>
              <div><dt>数据类型</dt><dd><code>{valueType}</code></dd></div>
              <div><dt>{isSystem ? '字段路径' : '稳定标识'}</dt><dd><code>{identity}</code></dd></div>
              <div><dt>作用范围</dt><dd>{domain}</dd></div>
              <div><dt>{isSystem ? '所属 Schema' : definition?.kind === 'CUSTOM' ? '外部暴露' : '定义类别'}</dt><dd>{isSystem ? <code>{schemaOwnerFor(field!)}</code> : definition?.kind === 'CUSTOM' ? definition.exposure : 'Computed Definition'}</dd></div>
              <div><dt>{isSystem ? '是否必填' : '存放位置'}</dt><dd>{isSystem ? field!.presence === 'required' ? '必填' : '可选' : 'DesignDSL · definitions[]'}</dd></div>
              {field?.childSchemaRef ? <div><dt>{field.type === 'reference' ? '引用目标' : '循环单项 Schema'}</dt><dd><code>{field.childSchemaRef}</code></dd></div> : null}
              {field ? <div className="is-wide"><dt>属性说明</dt><dd>{field.description}</dd></div> : null}
            </dl>
            {field?.children?.length ? (
              <section className="rwtd-v2-source-dialog-section rwtd-v2-reference-structure">
                <header><h4>引用结构</h4><span>{field.childSchemaRef} · {field.children.length} 个直接字段</span></header>
                <ReferenceStructureList fields={field.children} />
              </section>
            ) : null}
            {field ? (
              <section className="rwtd-v2-source-dialog-section">
                <header><strong>StaticSchema 属性约束</strong><span>{field.constraints?.length ? `${field.constraints.length} 条声明` : 'constraints {}'}</span></header>
                {field.constraints?.length ? (
                  <dl className="rwtd-v2-schema-constraints">
                    {field.constraints.map((constraint) => (
                      <div key={constraint.keyword}>
                        <dt><code>{constraint.keyword}</code></dt>
                        <dd><strong>{constraint.value}</strong><span>{constraint.description}</span></dd>
                      </div>
                    ))}
                  </dl>
                ) : (
                  <div className="rwtd-v2-schema-constraints-empty">
                    <Check aria-hidden="true" size={14} />
                    <span><strong>未声明额外属性约束</strong><small>该字段仍须符合上方的数据类型与必填性。</small></span>
                  </div>
                )}
              </section>
            ) : (
              <>
                <section className="rwtd-v2-source-dialog-section">
                  <header><strong>类型与约束</strong><span>{definitionConstraints.length} 项</span></header>
                  <ul>{definitionConstraints.map((constraint) => <li key={constraint}><Check aria-hidden="true" size={12} /><span>{constraint}</span></li>)}</ul>
                </section>
                <section className="rwtd-v2-source-dialog-section">
                  <header><strong>{definitionSummaryLabel}</strong><span>{definitionSummaryKind}</span></header>
                  <code className="rwtd-v2-source-dialog-code">{definitionSummary}</code>
                  <p>{definition?.detail}</p>
                </section>
              </>
            )}
            {definition?.kind === 'MAPPING' ? (
              <section className="rwtd-v2-source-dialog-section">
                <header><strong>映射规则</strong><span>first match</span></header>
                <ol className="rwtd-v2-source-dialog-rules">{definition.cases.map((rule, index) => <li key={rule.id}><i>{index + 1}</i><span>{rule.operator === 'EQ' ? '等于' : '模式匹配'} {rule.operand}</span><strong>→ {rule.then}</strong></li>)}</ol>
                <div className="rwtd-v2-source-dialog-otherwise"><span>otherwise</span><strong>→ {definition.otherwise}</strong></div>
              </section>
            ) : null}
        </div>

        <footer>
          <button type="button" onClick={onClose}>关闭</button>
          {definition ? <button type="button" className="is-primary" onClick={() => onEdit(definition)}><Pencil size={13} />编辑属性</button> : null}
        </footer>
      </section>
    </div>
  );
  if (typeof document === 'undefined') return dialog;
  return createPortal(<div className="rwtd-root rwtd-v2-portal-root">{dialog}</div>, document.body);
}

function SourceEditorModal({
  label,
  onClose,
  children,
}: {
  label: string;
  onClose(): void;
  children: ReactNode;
}) {
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const closeRef = useRef(onClose);
  useEffect(() => {
    closeRef.current = onClose;
  }, [onClose]);
  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusableSelector = 'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])';
    const focusInitialControl = window.requestAnimationFrame(() => {
      const dialog = dialogRef.current;
      const initialControl = dialog?.querySelector<HTMLElement>('[data-dialog-initial-focus="true"]')
        ?? dialog?.querySelector<HTMLElement>(focusableSelector)
        ?? dialog;
      initialControl?.focus();
    });
    const handleKeyDown = (event: KeyboardEvent) => {
      const dialog = dialogRef.current;
      if (!dialog) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        closeRef.current();
        return;
      }
      if (event.key !== 'Tab') return;
      const controls = [...dialog.querySelectorAll<HTMLElement>(focusableSelector)];
      if (!controls.length) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = controls[0]!;
      const last = controls.at(-1)!;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', handleKeyDown, true);
    return () => {
      window.cancelAnimationFrame(focusInitialControl);
      window.removeEventListener('keydown', handleKeyDown, true);
      previousFocus?.focus();
    };
  }, []);
  const modal = (
    <div className="rwtd-v2-source-dialog-backdrop rwtd-v2-source-editor-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) closeRef.current(); }}>
      <div ref={dialogRef} className="rwtd-v2-source-editor-dialog" role="dialog" aria-modal="true" aria-label={label} tabIndex={-1}>
        {children}
      </div>
    </div>
  );
  if (typeof document === 'undefined') return modal;
  return createPortal(<div className="rwtd-root rwtd-v2-portal-root">{modal}</div>, document.body);
}

function DefinitionEditor({
  draft,
  state,
  onChange,
  onCancel,
  onSave,
}: {
  draft: DefinitionDraft;
  state: DesignerState;
  onChange(draft: DefinitionDraft): void;
  onCancel(): void;
  onSave(): void;
}) {
  const domains = definitionDomainOptions(state.tree);
  const inputOptions = draft.kind === 'EXPRESSION' ? bindableSourceOptions(state, draft.domain) : [];
  return (
    <section className="rwtd-v2-source-editor" aria-label={draft.editingId ? '编辑定义' : '新建定义'}>
      <header><div><span>DESIGN DSL · DEFINITIONS[]</span><strong>{draft.editingId ? '编辑' : '新建'}{draft.kind === 'CUSTOM' ? '定义数据源' : '派生表达式'}</strong></div><button type="button" aria-label="关闭定义编辑器" onClick={onCancel}><X size={14} /></button></header>
      <div className="rwtd-v2-source-form-grid">
        <label className="is-wide"><span>名称</span><input data-dialog-initial-focus="true" value={draft.name} placeholder="例如 memberPriceText" onChange={(event) => onChange({ ...draft, name: event.target.value })} /></label>
        <label><span>{draft.kind === 'CUSTOM' ? '值类型' : '输出类型'}</span><select value={draft.valueType} onChange={(event) => onChange({ ...draft, valueType: event.target.value as DesignerValueType })}>{definitionValueTypes.map((type) => <option key={type}>{type}</option>)}</select></label>
        {draft.kind === 'CUSTOM' ? (
          <>
            <label><span>外部暴露</span><select value={draft.exposure} onChange={(event) => onChange({ ...draft, exposure: event.target.value as CustomDefinitionDraft['exposure'] })}><option value="PRIVATE">PRIVATE</option><option value="PUBLIC">PUBLIC</option></select></label>
            <label className="is-wide"><span>Typed literal 默认值</span><input value={draft.defaultValue} placeholder={draft.valueType.startsWith('list<') ? '["A", "B"]' : '海博优选'} onChange={(event) => onChange({ ...draft, defaultValue: event.target.value })} /><small>Custom 固定属于模板范围；没有循环域选项。</small></label>
          </>
        ) : (
          <>
            <label><span>声明范围</span><select value={domainKey(draft.domain)} onChange={(event) => {
              const domain = domains.find((candidate) => candidate.key === event.target.value)?.domain ?? 'invocation';
              const input = bindableSourceOptions(state, domain)[0]?.source ?? draft.input;
              onChange({ ...draft, domain, input });
            }}>{domains.map((option) => <option key={option.key} value={option.key}>{option.label}</option>)}</select></label>
            <label className="is-wide"><span>显式输入 · value</span><select value={sourceOptionKey(draft.input)} onChange={(event) => {
              const input = inputOptions.find((option) => option.key === event.target.value)?.source;
              if (input) onChange({ ...draft, input });
            }}>{inputOptions.map((option) => <option key={option.key} value={option.key}>{option.label} · {option.detail} · {option.type}</option>)}</select></label>
            <label className="is-wide"><span>Expression 1.0</span><input value={draft.source} placeholder="concat('¥ ', input.value)" onChange={(event) => onChange({ ...draft, source: event.target.value })} /></label>
          </>
        )}
      </div>
      <footer><button type="button" onClick={onCancel}>取消</button><button type="button" className="is-primary" disabled={!draft.name.trim() || !(draft.kind === 'CUSTOM' ? draft.defaultValue : draft.source).trim()} onClick={onSave}><Check size={13} />{draft.editingId ? '保存修改' : '写入 DSL'}</button></footer>
    </section>
  );
}

function MappingEditor({
  draft,
  state,
  onChange,
  onCancel,
  onSave,
}: {
  draft: MappingDraft;
  state: DesignerState;
  onChange(draft: MappingDraft): void;
  onCancel(): void;
  onSave(): void;
}) {
  const domains = definitionDomainOptions(state.tree);
  const inputOptions = bindableSourceOptions(state, draft.domain);
  const input = inputOptions.find((candidate) => candidate.key === sourceOptionKey(draft.input));
  const updateRule = (ruleId: string, patch: Partial<PrototypeMappingRule>) => onChange({
    ...draft,
    rules: draft.rules.map((rule) => rule.id === ruleId ? { ...rule, ...patch } : rule),
  });
  const moveRule = (index: number, offset: -1 | 1) => {
    const target = index + offset;
    if (target < 0 || target >= draft.rules.length) return;
    const rules = [...draft.rules];
    [rules[index], rules[target]] = [rules[target]!, rules[index]!];
    onChange({ ...draft, rules });
  };
  return (
    <section className="rwtd-v2-source-editor rwtd-v2-mapping-editor" aria-label={draft.editingId ? '编辑映射' : '新建映射'}>
      <header><div><span>DESIGN DSL · DEFINITIONS[]</span><strong>{draft.editingId ? '编辑派生映射' : '新建派生映射'}</strong></div><button type="button" aria-label="关闭映射编辑器" onClick={onCancel}><X size={14} /></button></header>
      <div className="rwtd-v2-mapping-stage"><b>1</b><div><strong>选择唯一输入</strong><span>输入是一个显式 ValueSource</span></div></div>
      <div className="rwtd-v2-source-form-grid">
        <label className="is-wide"><span>名称</span><input data-dialog-initial-focus="true" value={draft.name} placeholder="例如 promotionLabel" onChange={(event) => onChange({ ...draft, name: event.target.value })} /></label>
        <label><span>输出类型</span><select value={draft.outputType} onChange={(event) => onChange({ ...draft, outputType: event.target.value as DesignerValueType })}>{definitionValueTypes.map((type) => <option key={type}>{type}</option>)}</select></label>
        <label><span>声明范围</span><select value={domainKey(draft.domain)} onChange={(event) => {
          const domain = domains.find((candidate) => candidate.key === event.target.value)?.domain ?? 'invocation';
          const nextInput = bindableSourceOptions(state, domain)[0]?.source ?? draft.input;
          onChange({ ...draft, domain, input: nextInput });
        }}>{domains.map((option) => <option key={option.key} value={option.key}>{option.label}</option>)}</select></label>
        <label className="is-wide"><span>唯一输入</span><select value={sourceOptionKey(draft.input)} onChange={(event) => {
          const source = inputOptions.find((option) => option.key === event.target.value)?.source;
          if (source) onChange({ ...draft, input: source });
        }}>{inputOptions.map((option) => <option key={option.key} value={option.key}>{option.label} · {option.detail} · {option.type}</option>)}</select><small>静态类型：{input?.type ?? '待解析'}</small></label>
      </div>
      <div className="rwtd-v2-mapping-stage"><b>2</b><div><strong>按顺序匹配</strong><span>首个命中的 case 胜出</span></div><button type="button" onClick={() => onChange({ ...draft, rules: [...draft.rules, { id: crypto.randomUUID(), operator: 'EQ', operand: '', result: '' }] })}><Plus size={12} />规则</button></div>
      <div className="rwtd-v2-mapping-rules">
        {draft.rules.map((rule, index) => (
          <div className="rwtd-v2-mapping-rule" key={rule.id}>
            <span>{index + 1}</span>
            <select aria-label={`规则 ${index + 1} 运算符`} value={rule.operator} onChange={(event) => updateRule(rule.id, { operator: event.target.value as PrototypeMappingRule['operator'] })}><option value="EQ">等于</option><option value="PATTERN_MATCH">模式匹配</option></select>
            <input aria-label={`规则 ${index + 1} 匹配值`} value={rule.operand} placeholder="匹配值" onChange={(event) => updateRule(rule.id, { operand: event.target.value })} />
            <span aria-hidden="true">→</span>
            <input aria-label={`规则 ${index + 1} 结果`} value={rule.result} placeholder="结果" onChange={(event) => updateRule(rule.id, { result: event.target.value })} />
            <div><button type="button" aria-label={`上移规则 ${index + 1}`} disabled={index === 0} onClick={() => moveRule(index, -1)}><ArrowUp size={11} /></button><button type="button" aria-label={`下移规则 ${index + 1}`} disabled={index === draft.rules.length - 1} onClick={() => moveRule(index, 1)}><ArrowDown size={11} /></button><button type="button" aria-label={`删除规则 ${index + 1}`} disabled={draft.rules.length === 1} onClick={() => onChange({ ...draft, rules: draft.rules.filter((candidate) => candidate.id !== rule.id) })}><Trash2 size={11} /></button></div>
          </div>
        ))}
      </div>
      <div className="rwtd-v2-mapping-stage"><b>3</b><div><strong>必填 otherwise</strong><span>所有 case 未命中时仍返回确定值</span></div></div>
      <label className="rwtd-v2-mapping-otherwise"><span>otherwise →</span><input value={draft.otherwise} placeholder="DEFAULT" onChange={(event) => onChange({ ...draft, otherwise: event.target.value })} /></label>
      <footer><button type="button" onClick={onCancel}>取消</button><button type="button" className="is-primary" disabled={!draft.name.trim() || !draft.otherwise.trim() || draft.rules.some((rule) => !rule.operand.trim() || !rule.result.trim())} onClick={onSave}><Check size={13} />{draft.editingId ? '保存修改' : '写入 DSL'}</button></footer>
    </section>
  );
}

function DataSourceLibrary({
  state,
  dispatch,
}: Pick<PartProps, 'state' | 'dispatch'>) {
  const [section, setSection] = useState<SourceSection>('system');
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState('schema-price');
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [definitionDraft, setDefinitionDraft] = useState<DefinitionDraft | null>(null);
  const [mappingDraft, setMappingDraft] = useState<MappingDraft | null>(null);
  const [sourceDialog, setSourceDialog] = useState<string | null>(null);
  const customRows = state.definitions.filter((definition) => definition.kind === 'CUSTOM');
  const expressionRows = state.definitions.filter((definition) => definition.kind === 'EXPRESSION');
  const mappingRows = state.definitions.filter((definition): definition is DesignerMappingDefinition => definition.kind === 'MAPPING');
  const dialogField = sourceDialog ? findSchemaSourceField(sourceDialog) : undefined;
  const dialogDefinition = sourceDialog ? state.definitions.find((definition) => definition.id === sourceDialog) : undefined;
  const loopsInScope = activeLoopNodes(state);
  useEffect(() => {
    if (!sourceDialog) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      setSourceDialog(null);
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [sourceDialog]);
  const switchSection = (next: SourceSection) => {
    setSection(next);
    setQuery('');
    setDefinitionDraft(null);
    setMappingDraft(null);
    setSourceDialog(null);
    setSelectedId(next === 'system' ? 'schema-price' : next === 'custom' ? customRows[0]?.id ?? '' : expressionRows[0]?.id ?? mappingRows[0]?.id ?? '');
  };
  const toggleField = (fieldId: string) => setExpanded((current) => {
    const next = new Set(current);
    if (next.has(fieldId)) next.delete(fieldId);
    else next.add(fieldId);
    return next;
  });
  const openSourceDialog = (id: string) => {
    setSelectedId(id);
    setSourceDialog(id);
  };
  const firstInput = (domain: DesignerDefinitionDomain = 'invocation') => bindableSourceOptions(state, domain)[0]?.source
    ?? { kind: 'context' as const, domain: 'invocation' as const, pointer: '/title' };
  const openNewCustom = () => {
    setSourceDialog(null);
    setMappingDraft(null);
    setDefinitionDraft({ kind: 'CUSTOM', name: '', valueType: 'text', exposure: 'PRIVATE', defaultValue: '' });
  };
  const openNewExpression = () => {
    setSourceDialog(null);
    setMappingDraft(null);
    setDefinitionDraft({ kind: 'EXPRESSION', name: '', valueType: 'text', domain: 'invocation', input: firstInput(), source: 'input.value' });
  };
  const editDefinition = (definition: DesignerDefinition) => {
    setSourceDialog(null);
    if (definition.kind === 'MAPPING') {
      setDefinitionDraft(null);
      setMappingDraft({
        editingId: definition.id,
        name: definition.name,
        input: structuredClone(definition.input),
        outputType: definition.output,
        domain: structuredClone(definition.domain),
        rules: definition.cases.map((rule) => ({ id: rule.id, operator: rule.operator, operand: rule.operand, result: rule.then })),
        otherwise: definition.otherwise,
      });
      return;
    }
    setMappingDraft(null);
    setDefinitionDraft(definition.kind === 'CUSTOM'
      ? {
          editingId: definition.id,
          kind: 'CUSTOM',
          name: definition.name,
          valueType: definition.valueType,
          exposure: definition.exposure,
          defaultValue: definition.defaultValue,
        }
      : {
          editingId: definition.id,
          kind: 'EXPRESSION',
          name: definition.name,
          valueType: definition.valueType,
          domain: structuredClone(definition.domain),
          input: structuredClone(definition.inputs[0]?.source ?? firstInput(definition.domain)),
          source: definition.source,
        });
  };
  const saveDefinition = () => {
    if (!definitionDraft?.name.trim()) return;
    const id = definitionDraft.editingId ?? crypto.randomUUID().toLowerCase();
    const next: DesignerDefinition = definitionDraft.kind === 'CUSTOM'
      ? {
          id,
          name: definitionDraft.name.trim(),
          kind: 'CUSTOM',
          valueType: definitionDraft.valueType,
          exposure: definitionDraft.exposure,
          defaultValue: definitionDraft.defaultValue.trim(),
          detail: `Typed literal 默认值 · ${definitionDraft.exposure}`,
        }
      : {
          id,
          name: definitionDraft.name.trim(),
          kind: 'EXPRESSION',
          valueType: definitionDraft.valueType,
          domain: structuredClone(definitionDraft.domain),
          inputs: [{ alias: 'value', source: structuredClone(definitionDraft.input) }],
          source: definitionDraft.source.trim(),
          detail: definitionDraft.source.trim(),
        };
    dispatch({ type: 'save-definition', definition: next });
    setSelectedId(next.id);
    setDefinitionDraft(null);
  };
  const newMappingDraft = (): MappingDraft => ({
    name: '', input: firstInput(), outputType: 'text', domain: 'invocation',
    rules: [{ id: crypto.randomUUID(), operator: 'EQ', operand: 'true', result: '促销' }], otherwise: '常规',
  });
  const openNewMapping = () => {
    setSourceDialog(null);
    setDefinitionDraft(null);
    setMappingDraft(newMappingDraft());
  };
  const saveMapping = () => {
    if (!mappingDraft?.name.trim() || !mappingDraft.otherwise.trim()) return;
    const id = mappingDraft.editingId ?? crypto.randomUUID().toLowerCase();
    const next: DesignerMappingDefinition = {
      id,
      name: mappingDraft.name.trim(),
      kind: 'MAPPING',
      valueType: mappingDraft.outputType,
      output: mappingDraft.outputType,
      domain: structuredClone(mappingDraft.domain),
      input: structuredClone(mappingDraft.input),
      cases: mappingDraft.rules.map((rule) => ({ id: rule.id, operator: rule.operator, operand: rule.operand, then: rule.result })),
      otherwise: mappingDraft.otherwise.trim(),
      detail: `有序 Mapping · ${mappingDraft.rules.length} cases · required otherwise`,
    };
    dispatch({ type: 'save-definition', definition: next });
    setSelectedId(id);
    setMappingDraft(null);
  };
  const normalizedQuery = query.trim().toLocaleLowerCase('zh-CN');
  const matchesQuery = (definition: DesignerDefinition) => [definition.name, definition.kind, definitionDomainLabel(definition), definition.detail, definitionValueType(definition)]
    .join(' ').toLocaleLowerCase('zh-CN').includes(normalizedQuery);
  const visibleCustom = customRows.filter(matchesQuery);
  const visibleExpressions = expressionRows.filter(matchesQuery);
  const visibleMappings = mappingRows.filter(matchesQuery);
  return (
    <div className="rwtd-v2-panel-flow rwtd-v2-source-browser">
      <div className="rwtd-v2-dsl-source-banner"><span>DESIGN DSL</span><strong>definitions[]</strong><em>随模板草稿保存</em></div>
      {loopsInScope.length > 0 ? (
        <section className="rwtd-v2-loop-context-card" aria-label="当前循环项">
          <header><Repeat2 size={14} /><strong>当前循环项</strong><span>仅在此结构位置可用</span></header>
          {loopsInScope.map((loop) => {
            const domain: DesignerDefinitionDomain = { kind: 'loop', loopId: loop.loopId! };
            return <div key={loop.loopId}><b>{loop.name}</b>{bindableSourceOptions(state, domain).map((option) => <code key={option.key}>{option.label} · {option.detail}</code>)}</div>;
          })}
        </section>
      ) : null}
      <div className="rwtd-v2-source-tabs" role="tablist" aria-label="数据来源类别">
        {([
          { key: 'system' as const, label: '系统', count: schemaFields.length, icon: <Database size={14} /> },
          { key: 'custom' as const, label: '自定义', count: customRows.length, icon: <Braces size={14} /> },
          { key: 'derived' as const, label: '派生', count: expressionRows.length + mappingRows.length, icon: <GitBranch size={14} /> },
        ]).map((item) => <button key={item.key} type="button" role="tab" aria-selected={section === item.key} className={section === item.key ? 'active' : ''} onClick={() => switchSection(item.key)}>{item.icon}<span>{item.label}</span><i>{item.count}</i></button>)}
      </div>
      <label className="rwtd-v2-search"><Search aria-hidden="true" size={16} /><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={section === 'system' ? '字段名 / 路径 / 类型…' : '名称 / 类型 / 范围…'} aria-label="搜索数据来源" /></label>

      {section === 'system' ? (
        <section className="rwtd-v2-system-source" role="tabpanel" aria-label="系统数据源">
          <header><span><ListTree aria-hidden="true" size={16} /></span><div><strong>{templateMeta.schemaRef}</strong><small>当前模板的 exact StaticSchema 投影</small></div><em><LockKeyhole size={10} />READ ONLY</em></header>
          <div className="rwtd-v2-source-tree" role="tree" aria-label={`${templateMeta.schemaRef} 字段树`} aria-readonly="true">
            <SourceFieldTree fields={schemaSourceTree} depth={0} query={query} expanded={expanded} selectedId={selectedId} onToggle={toggleField} onSelect={setSelectedId} onView={openSourceDialog} />
          </div>
          <p className="rwtd-v2-panel-note">来源随 StaticSchema 变化；这里只能查看和引用，不能在模板中改名或改类型。</p>
        </section>
      ) : null}

      {section === 'custom' ? (
        <section className="rwtd-v2-authored-sources" role="tabpanel" aria-label="自定义数据源">
          <div className="rwtd-v2-source-section-head"><div><strong>自定义输入</strong><span>Custom · 固定模板范围 · PUBLIC / PRIVATE</span></div><button type="button" onClick={openNewCustom}><Plus size={13} />新建定义</button></div>
          <div className="rwtd-v2-definition-list">
            {visibleCustom.map((definition) => (
              <article key={definition.id} className={selectedId === definition.id ? 'is-selected' : ''}>
                <button type="button" className="rwtd-v2-definition-card-main" aria-pressed={selectedId === definition.id} onClick={() => setSelectedId(definition.id)}><span><Database size={14} /></span><div><strong>{definition.name}</strong><small>自定义输入 · {definition.exposure}</small><code>{definition.detail}</code></div><em>{definition.valueType}</em></button>
                <div className="rwtd-v2-source-card-actions"><button type="button" aria-label={`查看数据源 ${definition.name}`} onClick={() => openSourceDialog(definition.id)}><Eye size={11} />查看</button><button type="button" aria-label={`编辑数据源 ${definition.name}`} onClick={() => editDefinition(definition)}><Pencil size={11} />编辑</button></div>
              </article>
            ))}
          </div>
          {!visibleCustom.length ? <div className="rwtd-v2-empty"><Search size={20} /><strong>没有匹配的自定义输入</strong><span>清空搜索或创建一个 typed literal 默认值。</span></div> : null}
        </section>
      ) : null}

      {section === 'derived' ? (
        <section className="rwtd-v2-authored-sources" role="tabpanel" aria-label="派生数据源">
          <div className="rwtd-v2-source-section-head"><div><strong>派生值</strong><span>Expression / Mapping · 显式声明范围</span></div><div className="rwtd-v2-source-create-actions"><button type="button" onClick={openNewExpression}><Plus size={13} />表达式</button><button type="button" onClick={openNewMapping}><Plus size={13} />映射</button></div></div>
          {visibleExpressions.length > 0 ? <div className="rwtd-v2-source-scope-title"><span>表达式</span><i>{visibleExpressions.length}</i></div> : null}
          <div className="rwtd-v2-definition-list">
            {visibleExpressions.map((definition) => (
              <article key={definition.id} className={selectedId === definition.id ? 'is-selected' : ''}>
                <button type="button" className="rwtd-v2-definition-card-main" aria-pressed={selectedId === definition.id} onClick={() => setSelectedId(definition.id)}><span><Braces size={14} /></span><div><strong>{definition.name}</strong><small>{definitionDomainLabel(definition)}</small><code>{definition.source}</code></div><em>{definition.valueType}</em></button>
                <div className="rwtd-v2-source-card-actions"><button type="button" aria-label={`查看数据源 ${definition.name}`} onClick={() => openSourceDialog(definition.id)}><Eye size={11} />查看</button><button type="button" aria-label={`编辑数据源 ${definition.name}`} onClick={() => editDefinition(definition)}><Pencil size={11} />编辑</button></div>
              </article>
            ))}
          </div>
          {visibleMappings.length > 0 ? <div className="rwtd-v2-source-scope-title"><span>有序映射</span><i>{visibleMappings.length}</i></div> : null}
          <div className="rwtd-v2-mapping-list">
            {visibleMappings.map((definition) => (
              <article key={definition.id} className={selectedId === definition.id ? 'is-selected' : ''}>
                <button type="button" className="rwtd-v2-mapping-summary" aria-pressed={selectedId === definition.id} onClick={() => setSelectedId(definition.id)}><span><GitBranch size={15} /></span><div><strong>{definition.name}</strong><small>{valueSourceSummary(definition.input)} → {definition.output}</small></div><em>{definitionDomainLabel(definition)}</em></button>
                <ol>{definition.cases.map((rule) => <li key={rule.id}><span>{rule.operator === 'EQ' ? '=' : '≈'} {rule.operand}</span><strong>→ {rule.then}</strong></li>)}</ol>
                <div className="rwtd-v2-mapping-fallback"><span>otherwise</span><strong>→ {definition.otherwise}</strong><div className="rwtd-v2-source-card-actions"><button type="button" aria-label={`查看数据源 ${definition.name}`} onClick={() => openSourceDialog(definition.id)}><Eye size={11} />查看</button><button type="button" aria-label={`编辑数据源 ${definition.name}`} onClick={() => editDefinition(definition)}><Pencil size={11} />编辑</button><button type="button" aria-label={`编辑映射规则 ${definition.name}`} onClick={() => editDefinition(definition)}><GitBranch size={11} />规则</button></div></div>
              </article>
            ))}
          </div>
          {!visibleMappings.length && !visibleExpressions.length ? <div className="rwtd-v2-empty"><GitBranch size={20} /><strong>没有匹配的派生值</strong><span>创建表达式，或用有序映射把一个显式输入转换为确定输出。</span></div> : null}
        </section>
      ) : null}
      {sourceDialog && (dialogField || dialogDefinition) ? (
        <SourceDetailsDialog
          key={sourceDialog}
          field={dialogField}
          definition={dialogDefinition}
          onClose={() => setSourceDialog(null)}
          onEdit={editDefinition}
        />
      ) : null}
      {definitionDraft ? (
        <SourceEditorModal
          label={`${definitionDraft.editingId ? '编辑' : '新建'}${definitionDraft.kind === 'CUSTOM' ? '定义数据源' : '派生表达式'}`}
          onClose={() => setDefinitionDraft(null)}
        >
          <DefinitionEditor draft={definitionDraft} state={state} onChange={setDefinitionDraft} onCancel={() => setDefinitionDraft(null)} onSave={saveDefinition} />
        </SourceEditorModal>
      ) : null}
      {mappingDraft ? (
        <SourceEditorModal label={`${mappingDraft.editingId ? '编辑' : '新建'}派生映射`} onClose={() => setMappingDraft(null)}>
          <MappingEditor draft={mappingDraft} state={state} onChange={setMappingDraft} onCancel={() => setMappingDraft(null)} onSave={saveMapping} />
        </SourceEditorModal>
      ) : null}
    </div>
  );
}

const TREE_DRAG_MIME = 'application/x-renderweave-layer';
const TREE_INDENT_PX = 18;
const TREE_MARKER_CENTER_PX = 13;
const treeBranchPalette = ['var(--color-accent-deep)', 'var(--color-info)', 'var(--color-warning)', 'var(--color-lavender)', 'var(--color-success)'];

const treeKindLabels: Record<DesignerNode['kind'], string> = {
  canvas: '画板', group: '自由分组', frame: '框架', stack: '堆叠', grid: '网格', text: '文本', image: '图片',
  rect: '矩形', ellipse: '椭圆', line: '直线', polygon: '多边形', polyline: '折线', path: '路径',
  qrCode: '二维码', barcode: '条形码', repeat: '循环', conditional: '条件', templateUse: '嵌套模板',
};

function NodeContextMenu({
  request,
  state,
  dispatch,
  onClose,
}: {
  request: NodeContextRequest;
  state: DesignerState;
  dispatch: Dispatch<DesignerAction>;
  onClose(): void;
}) {
  const menuRef = useRef<HTMLDivElement | null>(null);
  const firstActionRef = useRef<HTMLButtonElement | null>(null);
  const node = findNode(state.tree, request.nodeId);
  const selectedIds = state.selectedNodeIds.includes(request.nodeId) ? state.selectedNodeIds : [request.nodeId];
  const layerOrder = layerOrderCapabilities(state.tree, selectedIds);
  const isRoot = node?.kind === 'canvas';
  const locked = node ? nodeProp(node, 'locked') === 'true' : false;
  const hidden = node ? nodeProp(node, 'visible') === 'false' : false;
  const left = Math.max(8, Math.min(request.x, (typeof window === 'undefined' ? 1440 : window.innerWidth) - 230));
  const top = Math.max(8, Math.min(request.y, (typeof window === 'undefined' ? 900 : window.innerHeight) - 408));

  useEffect(() => {
    firstActionRef.current?.focus();
    const dismissFromPointer = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) onClose();
    };
    const dismissFromKeyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onClose();
      }
    };
    window.addEventListener('pointerdown', dismissFromPointer, true);
    window.addEventListener('keydown', dismissFromKeyboard);
    window.addEventListener('resize', onClose);
    window.addEventListener('scroll', onClose, true);
    return () => {
      window.removeEventListener('pointerdown', dismissFromPointer, true);
      window.removeEventListener('keydown', dismissFromKeyboard);
      window.removeEventListener('resize', onClose);
      window.removeEventListener('scroll', onClose, true);
    };
  }, [onClose, request.nodeId]);

  if (!node) return null;
  const run = (action: DesignerAction) => {
    dispatch(action);
    onClose();
  };
  const menu = (
    <div
      ref={menuRef}
      className="rwtd-v2-node-context-menu"
      role="menu"
      aria-label={`${node.name} 操作`}
      style={{ left, top }}
      onContextMenu={(event) => event.preventDefault()}
    >
      <header>
        <span>{kindIcons[node.kind]}</span>
        <div><strong>{node.name}</strong><small>{treeKindLabels[node.kind]}</small></div>
        {selectedIds.length > 1 ? <i>{selectedIds.length} 项</i> : null}
      </header>
      <div className="rwtd-v2-context-section" role="group" aria-label="常规操作">
        <button ref={firstActionRef} type="button" role="menuitem" onClick={() => run({ type: 'set-tab', tab: 'structure' })}><ListTree aria-hidden="true" size={14} /><span>在结构中定位</span></button>
        <button type="button" role="menuitem" disabled={isRoot} onClick={() => run({ type: 'update-prop', nodeId: node.id, label: 'locked', value: locked ? 'false' : 'true' })}>{locked ? <Unlock aria-hidden="true" size={14} /> : <Lock aria-hidden="true" size={14} />}<span>{locked ? '解锁' : '锁定'}</span></button>
        <button type="button" role="menuitem" disabled={isRoot} onClick={() => run({ type: 'update-prop', nodeId: node.id, label: 'visible', value: hidden ? 'true' : 'false' })}>{hidden ? <Eye aria-hidden="true" size={14} /> : <EyeOff aria-hidden="true" size={14} />}<span>{hidden ? '显示' : '隐藏'}</span></button>
      </div>
      <div className="rwtd-v2-context-section" role="group" aria-label="Z 轴顺序">
        <p><span>Z 轴顺序</span><small>调整同级 children[]</small></p>
        <button type="button" role="menuitem" disabled={!layerOrder.front} onClick={() => run({ type: 'reorder-selection', operation: 'front' })}><BringToFront aria-hidden="true" size={14} /><span>置于顶层</span></button>
        <button type="button" role="menuitem" disabled={!layerOrder.forward} onClick={() => run({ type: 'reorder-selection', operation: 'forward' })}><ArrowUp aria-hidden="true" size={14} /><span>上移一层</span></button>
        <button type="button" role="menuitem" disabled={!layerOrder.backward} onClick={() => run({ type: 'reorder-selection', operation: 'backward' })}><ArrowDown aria-hidden="true" size={14} /><span>下移一层</span></button>
        <button type="button" role="menuitem" disabled={!layerOrder.back} onClick={() => run({ type: 'reorder-selection', operation: 'back' })}><SendToBack aria-hidden="true" size={14} /><span>置于底层</span></button>
      </div>
      <div className="rwtd-v2-context-section is-danger" role="group" aria-label="删除操作">
        <button type="button" role="menuitem" disabled={isRoot} onClick={() => run({ type: 'delete-selection' })}><Trash2 aria-hidden="true" size={14} /><span>删除{selectedIds.length > 1 ? ` ${selectedIds.length} 项` : ''}</span></button>
      </div>
    </div>
  );
  if (typeof document === 'undefined') return menu;
  return createPortal(<div className="rwtd-root rwtd-v2-portal-root">{menu}</div>, document.body);
}

function treeBranchColor(nodeId: string): string {
  const hash = Array.from(nodeId).reduce((value, character) => value + character.charCodeAt(0), 0);
  return treeBranchPalette[hash % treeBranchPalette.length]!;
}

function conditionalTreeStatus(projection: ConditionalProjection): { label: string; tone: 'ok' | 'pruned' | 'error' | 'pending' } {
  switch (projection.outcome) {
    case 'INCLUDED': return { label: 'TRUE', tone: 'ok' };
    case 'PRUNED_FALSE': return { label: 'FALSE · 剪枝', tone: 'pruned' };
    case 'PRUNED_ABSENT': return { label: '缺失 → FALSE', tone: 'pruned' };
    case 'RENDER_DISABLED': return { label: 'render:false', tone: 'pruned' };
    case 'ABSENT_ERROR':
    case 'INPUT_INVALID': return { label: '条件错误', tone: 'error' };
    case 'INVALID': return { label: '待配置', tone: 'pending' };
  }
}

function dropPlacementFromEvent(event: ReactDragEvent<HTMLElement>, isContainer: boolean, isRoot: boolean): TreeDropPlacement {
  if (isRoot) return 'into';
  const bounds = event.currentTarget.getBoundingClientRect();
  const ratio = bounds.height === 0 ? 0.5 : (event.clientY - bounds.top) / bounds.height;
  if (!isContainer) return ratio < 0.5 ? 'before' : 'after';
  if (ratio < 0.28) return 'before';
  if (ratio > 0.72) return 'after';
  return 'into';
}

interface TreeRowProps {
  node: DesignerNode;
  depth: number;
  state: DesignerState;
  dispatch: Dispatch<DesignerAction>;
  collapsed: ReadonlySet<string>;
  branchColor: string;
  ancestorContinuations: boolean[];
  isLast: boolean;
  draggedId: string | null;
  dropHint: { targetId: string; placement: TreeDropPlacement } | null;
  runtimeSuppressed: boolean;
  onToggle(nodeId: string): void;
  onDragStart(nodeId: string): void;
  onDragEnd(): void;
  onDropHint(hint: { targetId: string; placement: TreeDropPlacement } | null): void;
  onOpenContextMenu(request: NodeContextRequest): void;
}

function TreeRow({ node, depth, state, dispatch, collapsed, branchColor, ancestorContinuations, isLast, draggedId, dropHint, runtimeSuppressed, onToggle, onDragStart, onDragEnd, onDropHint, onOpenContextMenu }: TreeRowProps) {
  const [renaming, setRenaming] = useState(false);
  const [draftName, setDraftName] = useState(node.name);
  const renameInput = useRef<HTMLInputElement | null>(null);
  const renameFinished = useRef(false);
  const selected = state.selectedNodeIds.includes(node.id);
  const hidden = node.props.find((prop) => prop.label === 'visible')?.value === 'false';
  const isRoot = node.kind === 'canvas';
  const isContainer = isContainerNodeKind(node.kind);
  const hasChildren = node.children.length > 0;
  const expanded = !collapsed.has(node.id);
  const activeDrop = dropHint?.targetId === node.id ? dropHint.placement : undefined;
  const rowKindClass = isRoot ? ' is-root' : isContainer ? ' is-container' : ' is-element';
  const rootCanvas = isRoot ? canvasProjection(state.tree) : null;
  const rootLayoutLabel = rootCanvas?.layoutMode === 'STACK'
    ? `堆叠${rootCanvas.direction === 'HORIZONTAL' ? '横向' : '纵向'}`
    : rootCanvas?.layoutMode === 'GRID'
      ? '网格布局'
      : '自由布局';
  const rootDetail = rootCanvas
    ? `${rootCanvas.widthMm}×${rootCanvas.heightMm}mm · ${rootLayoutLabel} · 内边距 ${rootCanvas.padding.topMm}/${rootCanvas.padding.rightMm}/${rootCanvas.padding.bottomMm}/${rootCanvas.padding.leftMm}mm`
    : node.detail;
  const repeatProjection = node.kind === 'repeat'
    ? projectPrototypeRepeat(node, state.boxes, state.repeatPreviewSample, state.definitions)
    : null;
  const conditionalProjection = node.kind === 'conditional'
    ? projectPrototypeConditional(node, state.conditionalPreviewSample)
    : null;
  const conditionalStatus = conditionalProjection ? conditionalTreeStatus(conditionalProjection) : null;
  const suppressChildrenAtRuntime = runtimeSuppressed
    || Boolean(conditionalProjection && conditionalProjection.outcome !== 'INCLUDED');

  useEffect(() => {
    if (!renaming) return;
    renameInput.current?.focus();
    renameInput.current?.select();
  }, [renaming]);

  const beginRename = () => {
    if (isRoot) return;
    renameFinished.current = false;
    setDraftName(node.name);
    setRenaming(true);
  };

  const finishRename = (commit: boolean) => {
    if (renameFinished.current) return;
    renameFinished.current = true;
    if (commit) dispatch({ type: 'rename-node', nodeId: node.id, name: draftName });
    setRenaming(false);
  };

  return (
    <div className="rwtd-v2-tree-branch" style={{ '--rwtd-tree-branch': branchColor } as CSSProperties}>
      <div
        role="treeitem"
        aria-level={depth + 1}
        aria-selected={selected}
        aria-expanded={hasChildren ? expanded : undefined}
        className={`rwtd-v2-tree-row${rowKindClass}${isContainer && !hasChildren ? ' is-empty-container' : ''}${selected ? ' selected' : ''}${draggedId === node.id ? ' is-dragging' : ''}${renaming ? ' is-renaming' : ''}${runtimeSuppressed ? ' is-runtime-pruned' : ''}`}
        data-drop={activeDrop}
        data-node-kind={node.kind}
        data-node-id={node.id}
        data-conditional-outcome={conditionalProjection?.outcome.toLocaleLowerCase()}
        data-runtime-suppressed={runtimeSuppressed || undefined}
        style={{
          '--rwtd-tree-indent': `${depth * TREE_INDENT_PX}px`,
          '--rwtd-tree-guide-offset': `${-depth * TREE_INDENT_PX}px`,
        } as CSSProperties}
        draggable={!isRoot && !renaming}
        aria-label={`${node.name}，${treeKindLabels[node.kind]}${isRoot ? '' : '，双击或按 F2 重命名'}`}
        title={`${node.name} · ${treeKindLabels[node.kind]}`}
        onClick={(event) => dispatch({ type: 'select-node', nodeId: node.id, additive: event.shiftKey })}
        onContextMenu={(event) => {
          event.preventDefault();
          event.stopPropagation();
          if (!selected) dispatch({ type: 'select-node', nodeId: node.id });
          onOpenContextMenu({ nodeId: node.id, x: event.clientX, y: event.clientY });
        }}
        onDoubleClick={(event) => {
          if (isRoot || (event.target as HTMLElement).closest('button, input, .rwtd-v2-tree-drag')) return;
          event.preventDefault();
          event.stopPropagation();
          beginRename();
        }}
        onKeyDown={(event) => {
          if (event.key === 'F2' && !isRoot) {
            event.preventDefault();
            beginRename();
          } else if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            dispatch({ type: 'select-node', nodeId: node.id, additive: event.shiftKey });
          } else if (event.key === 'ArrowRight' && hasChildren && !expanded) {
            event.preventDefault();
            onToggle(node.id);
          } else if (event.key === 'ArrowLeft' && hasChildren && expanded) {
            event.preventDefault();
            onToggle(node.id);
          }
        }}
        onDragStart={(event) => {
          if (isRoot) return;
          event.dataTransfer.setData(TREE_DRAG_MIME, node.id);
          event.dataTransfer.setData('text/plain', node.id);
          event.dataTransfer.effectAllowed = 'move';
          onDragStart(node.id);
        }}
        onDragEnd={onDragEnd}
        onDragOver={(event) => {
          event.preventDefault();
          event.dataTransfer.dropEffect = 'move';
          const placement = dropPlacementFromEvent(event, isContainer, isRoot);
          if (dropHint?.targetId !== node.id || dropHint.placement !== placement) onDropHint({ targetId: node.id, placement });
        }}
        onDragLeave={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget as Node | null)) onDropHint(null);
        }}
        onDrop={(event) => {
          event.preventDefault();
          event.stopPropagation();
          const draggedNodeId = event.dataTransfer.getData(TREE_DRAG_MIME) || event.dataTransfer.getData('text/plain');
          const placement = dropHint?.targetId === node.id ? dropHint.placement : dropPlacementFromEvent(event, isContainer, isRoot);
          onDropHint(null);
          onDragEnd();
          if (draggedNodeId && draggedNodeId !== node.id) dispatch({ type: 'drop-node', draggedId: draggedNodeId, targetId: node.id, placement });
        }}
        tabIndex={0}
      >
        {depth > 0 ? (
          <span className="rwtd-v2-tree-guides" aria-hidden="true">
            {ancestorContinuations.map((continues, index) => continues ? (
              <i
                key={`guide-${index}`}
                className="rwtd-v2-tree-guide"
                style={{ '--rwtd-tree-guide-position': `${TREE_MARKER_CENTER_PX + index * TREE_INDENT_PX}px` } as CSSProperties}
              />
            ) : null)}
            <i
              className={`rwtd-v2-tree-elbow${isLast ? ' is-last' : ''}`}
              style={{ '--rwtd-tree-guide-position': `${TREE_MARKER_CENTER_PX + (depth - 1) * TREE_INDENT_PX}px` } as CSSProperties}
            />
          </span>
        ) : null}
        {hasChildren ? (
          <button
            type="button"
            className="rwtd-v2-tree-dot is-container"
            aria-label={`${expanded ? '折叠' : '展开'}${node.name}`}
            aria-expanded={expanded}
            onClick={(event) => {
              event.stopPropagation();
              onToggle(node.id);
            }}
          >
            <ChevronRight aria-hidden="true" size={10} />
          </button>
        ) : <span className={`rwtd-v2-tree-dot${isContainer ? ' is-empty-container' : ' is-leaf'}`} aria-hidden="true" />}
        <span className="rwtd-v2-kind">{kindIcons[node.kind]}</span>
        <span className="rwtd-v2-tree-copy">
          {renaming ? (
            <input
              ref={renameInput}
              className="rwtd-v2-tree-rename"
              value={draftName}
              maxLength={80}
              aria-label={`重命名 ${node.name}`}
              onChange={(event) => setDraftName(event.target.value)}
              onClick={(event) => event.stopPropagation()}
              onDoubleClick={(event) => event.stopPropagation()}
              onBlur={() => finishRename(true)}
              onKeyDown={(event) => {
                event.stopPropagation();
                if (event.key === 'Enter') {
                  event.preventDefault();
                  finishRename(true);
                } else if (event.key === 'Escape') {
                  event.preventDefault();
                  finishRename(false);
                }
              }}
            />
          ) : <strong>{node.name}</strong>}
          <small>{isRoot ? rootDetail : `${treeKindLabels[node.kind]}${hasChildren ? ` · ${node.children.length} 个 authored 子项` : ''}${repeatProjection ? ` · ×${repeatProjection.occurrences.length} 预览` : ''}${runtimeSuppressed ? ' · 运行时不求值' : ''}`}</small>
        </span>
        <span className="rwtd-v2-tree-meta">
          {isContainer ? (
            <span className="rwtd-v2-tree-container-tag" data-tone={conditionalStatus?.tone}>
              {isRoot ? '画板' : repeatProjection ? `×${repeatProjection.occurrences.length}` : conditionalStatus?.label ?? '容器'}
            </span>
          ) : null}
          {hidden ? <span className="rwtd-v2-tree-state">隐藏</span> : null}
        </span>
        {isRoot ? null : <GripVertical className="rwtd-v2-tree-drag" aria-label="拖动图层" size={13} />}
      </div>
      {hasChildren && expanded ? (
        <div className="rwtd-v2-tree-children" role="group">
          {node.children.map((child, index) => (
            <TreeRow
              key={child.id}
              node={child}
              depth={depth + 1}
              state={state}
              dispatch={dispatch}
              collapsed={collapsed}
              branchColor={isRoot ? treeBranchColor(child.id) : branchColor}
              ancestorContinuations={depth === 0 ? [] : [...ancestorContinuations, !isLast]}
              isLast={index === node.children.length - 1}
              draggedId={draggedId}
              dropHint={dropHint}
              runtimeSuppressed={suppressChildrenAtRuntime}
              onToggle={onToggle}
              onDragStart={onDragStart}
              onDragEnd={onDragEnd}
              onDropHint={onDropHint}
              onOpenContextMenu={onOpenContextMenu}
            />
          ))}
        </div>
      ) : null}
    </div>
  );
}

export function StructureTree({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const treeRef = useRef<HTMLDivElement | null>(null);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set());
  const [draggedId, setDraggedId] = useState<string | null>(null);
  const [dropHint, setDropHint] = useState<{ targetId: string; placement: TreeDropPlacement } | null>(null);
  const [contextMenu, setContextMenu] = useState<NodeContextRequest | null>(null);
  const toggle = (nodeId: string) => setCollapsed((current) => {
    const next = new Set(current);
    if (next.has(nodeId)) next.delete(nodeId);
    else next.add(nodeId);
    return next;
  });
  const endDrag = () => {
    setDraggedId(null);
    setDropHint(null);
  };
  useEffect(() => {
    const frame = window.requestAnimationFrame(() => {
      const row = treeRef.current?.querySelector<HTMLElement>(`[data-node-id="${state.selectedNodeId}"]`);
      row?.scrollIntoView?.({ block: 'nearest' });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [state.selectedNodeId]);
  return (
    <>
      <div ref={treeRef} className="rwtd-v2-tree" role="tree" aria-label="DesignDSL 图层树">
        <TreeRow
        node={state.tree}
        depth={0}
        state={state}
        dispatch={dispatch}
        collapsed={collapsed}
        branchColor="var(--color-accent-deep)"
        ancestorContinuations={[]}
        isLast
        draggedId={draggedId}
        dropHint={dropHint}
        runtimeSuppressed={false}
        onToggle={toggle}
        onDragStart={setDraggedId}
        onDragEnd={endDrag}
          onDropHint={setDropHint}
          onOpenContextMenu={setContextMenu}
        />
      </div>
      {contextMenu ? <NodeContextMenu request={contextMenu} state={state} dispatch={dispatch} onClose={() => setContextMenu(null)} /> : null}
    </>
  );
}

export function StructureActions({
  state,
  dispatch,
  compact = false,
  hideSummary = false,
  showLayerActions = false,
}: Pick<PartProps, 'state' | 'dispatch'> & { compact?: boolean; hideSummary?: boolean; showLayerActions?: boolean }) {
  const selected = findNode(state.tree, state.selectedNodeId);
  const hasSelection = state.selectedNodeIds.some((id) => id !== nodeIds.canvas);
  const layerOrder = layerOrderCapabilities(state.tree, state.selectedNodeIds);
  return (
    <section className={`rwtd-v2-structure-actions${compact ? ' is-compact' : ''}`}>
      {hideSummary ? null : (
        <div className="rwtd-v2-selection-summary"><strong>{state.selectedNodeIds.length} 个已选</strong><span>{selected?.name ?? '未选择'} · 拖动图层调整层级与顺序</span></div>
      )}
      <div className="rwtd-v2-structure-toolbar" role="toolbar" aria-label="结构操作">
        {showLayerActions ? (
          <>
            <button type="button" aria-label="置于顶层" title="置于顶层（同级 children[] 末尾）" disabled={!layerOrder.front} onClick={() => dispatch({ type: 'reorder-selection', operation: 'front' })}><BringToFront aria-hidden="true" size={14} />顶层</button>
            <button type="button" aria-label="上移一层" title="上移一层" disabled={!layerOrder.forward} onClick={() => dispatch({ type: 'reorder-selection', operation: 'forward' })}><ArrowUp aria-hidden="true" size={14} />上移</button>
            <button type="button" aria-label="下移一层" title="下移一层" disabled={!layerOrder.backward} onClick={() => dispatch({ type: 'reorder-selection', operation: 'backward' })}><ArrowDown aria-hidden="true" size={14} />下移</button>
            <button type="button" aria-label="置于底层" title="置于底层（同级 children[] 起始）" disabled={!layerOrder.back} onClick={() => dispatch({ type: 'reorder-selection', operation: 'back' })}><SendToBack aria-hidden="true" size={14} />底层</button>
          </>
        ) : null}
        <button type="button" disabled={!hasSelection} className="danger" onClick={() => dispatch({ type: 'delete-selection' })}><Trash2 size={14} />删除</button>
      </div>
    </section>
  );
}

function StructurePanel({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  return (
    <div className="rwtd-v2-structure-panel">
      <StructureActions state={state} dispatch={dispatch} compact showLayerActions />
      <div className="rwtd-v2-tree-scroll"><StructureTree state={state} dispatch={dispatch} /></div>
      <p className="rwtd-v2-tree-hint">右键图层操作 · Ctrl+A 全选 · 双击 / F2 重命名 · 拖动移入或排序</p>
    </div>
  );
}

export function AuthoringPanel({ state, dispatch }: Pick<PartProps, 'state' | 'dispatch'>) {
  const active = activeAuthoringTab(state.leftTab);
  const tab = authoringTabs.find((candidate) => candidate.key === active) ?? authoringTabs[0]!;
  return (
    <section className="rwtd-v2-panel" aria-label={`${tab.title}面板`}>
      <PanelHeading tab={tab} />
      <div className="rwtd-v2-panel-scroll">
        {active === 'elements' ? <ElementLibrary dispatch={dispatch} /> : null}
        {active === 'containers' ? <ContainerLibrary dispatch={dispatch} /> : null}
        {active === 'images' ? <ImagesLibrary state={state} dispatch={dispatch} /> : null}
        {active === 'sources' ? <DataSourceLibrary state={state} dispatch={dispatch} /> : null}
        {active === 'structure' ? <StructurePanel state={state} dispatch={dispatch} /> : null}
      </div>
    </section>
  );
}

function nodeProp(node: DesignerNode, label: string): string | undefined {
  return node.props.find((prop) => prop.label === label)?.value;
}

function assetFromRef(value: string | undefined) {
  const assetId = value?.match(/assetId:([^}]+)/)?.[1];
  return assets.find((asset) => asset.id === assetId);
}

function assetNameFromRef(value: string | undefined): string {
  return assetFromRef(value)?.name ?? '选择图片';
}

const canvasPaintTokens: Record<string, string> = {
  surface: 'var(--color-surface)',
  'accent-wash': 'var(--color-accent-wash)',
  ink: 'var(--color-ink)',
  coral: 'var(--color-coral)',
  hairline: 'var(--color-hairline-strong)',
  transparent: 'transparent',
};

function canvasPaint(value: string | undefined, fallback: string): string {
  if (!value) return fallback;
  return canvasPaintTokens[value] ?? value;
}

function numericNodeProp(node: DesignerNode, label: string, fallback: number): number {
  const value = Number(nodeProp(node, label));
  return Number.isFinite(value) ? value : fallback;
}

function canvasPaddingStyle(node: DesignerNode, canvasScale: number): CSSProperties {
  const pxPerMm = (96 / 25.4) * canvasScale;
  return {
    paddingTop: `${Math.max(0, numericNodeProp(node, 'padding.topMm', 0)) * pxPerMm}px`,
    paddingRight: `${Math.max(0, numericNodeProp(node, 'padding.rightMm', 0)) * pxPerMm}px`,
    paddingBottom: `${Math.max(0, numericNodeProp(node, 'padding.bottomMm', 0)) * pxPerMm}px`,
    paddingLeft: `${Math.max(0, numericNodeProp(node, 'padding.leftMm', 0)) * pxPerMm}px`,
  };
}

function normalizedPolylinePoints(value: string | undefined): string {
  const values = value?.match(/-?\d+(?:\.\d+)?/g)?.map(Number) ?? [];
  const points: Array<[number, number]> = [];
  for (let index = 0; index + 1 < values.length; index += 2) points.push([values[index]!, values[index + 1]!]);
  if (points.length < 2) return '6,50 34,8 62,42 94,12';
  const xs = points.map(([x]) => x);
  const ys = points.map(([, y]) => y);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  const width = Math.max(1, maxX - minX);
  const height = Math.max(1, maxY - minY);
  return points.map(([x, y]) => `${6 + ((x - minX) / width) * 88},${6 + ((y - minY) / height) * 48}`).join(' ');
}

function regularShapePoints(count: number, innerRatio = 1): string {
  const safeCount = Math.max(3, Math.min(16, Math.round(count)));
  const points = Array.from({ length: innerRatio < 1 ? safeCount * 2 : safeCount }, (_, index) => {
    const radius = innerRatio < 1 && index % 2 === 1 ? 44 * innerRatio : 44;
    const angle = -Math.PI / 2 + (Math.PI * 2 * index) / (innerRatio < 1 ? safeCount * 2 : safeCount);
    return `${50 + Math.cos(angle) * radius},${50 + Math.sin(angle) * radius}`;
  });
  return points.join(' ');
}

function qrModules(value: string): Array<[number, number]> {
  const modules: Array<[number, number]> = [];
  const safe = value || 'renderweave';
  for (let y = 0; y < 21; y += 1) {
    for (let x = 0; x < 21; x += 1) {
      const inFinder = (x < 8 && y < 8) || (x > 12 && y < 8) || (x < 8 && y > 12);
      if (inFinder) continue;
      const seed = safe.charCodeAt((x * 3 + y * 5) % safe.length);
      if ((seed + x * 7 + y * 11 + x * y) % 5 < 2) modules.push([x, y]);
    }
  }
  return modules;
}

function barcodeBars(value: string): Array<{ x: number; width: number; tall: boolean }> {
  const safe = value || '6901234567892';
  const bars: Array<{ x: number; width: number; tall: boolean }> = [];
  let x = 4;
  for (let index = 0; index < 38 && x < 116; index += 1) {
    const seed = safe.charCodeAt(index % safe.length) + index * 13;
    const width = 1 + (seed % 3);
    bars.push({ x, width, tall: index < 3 || index > 34 || index % 6 === 0 });
    x += width + 1 + ((seed >> 2) % 2);
  }
  return bars;
}

function QrFinder({ x, y, foreground, background }: { x: number; y: number; foreground: string; background: string }) {
  return (
    <g>
      <rect x={x} y={y} width="7" height="7" fill={foreground} />
      <rect x={x + 1} y={y + 1} width="5" height="5" fill={background} />
      <rect x={x + 2} y={y + 2} width="3" height="3" fill={foreground} />
    </g>
  );
}

function RepeatCanvasPreview({
  node,
  projection,
  state,
  dispatch,
  canvasScale,
}: {
  node: DesignerNode;
  projection: RepeatProjection;
  state: DesignerState;
  dispatch: Dispatch<DesignerAction>;
  canvasScale: number;
}) {
  const pxPerMm = (96 / 25.4) * canvasScale;
  const activeIndex = Math.min(Math.max(0, state.repeatActiveIndex), Math.max(0, projection.occurrences.length - 1));
  return (
    <span
      className="rwtd-v2-repeat-preview"
      data-repeat-phase={projection.phase.toLocaleLowerCase()}
      data-repeat-outcome={projection.outcome.toLocaleLowerCase()}
      data-repeat-virtual-count={projection.occurrences.length}
    >
      <span className="rwtd-v2-repeat-preview-head">
        <span><Repeat2 aria-hidden="true" size={11 * canvasScale} />{projection.source?.path ?? '选择数组'}</span>
        <i>{projection.occurrences.length > 0 ? `×${projection.occurrences.length}` : projection.phase === 'READY' ? '×0' : '未完成'}</i>
      </span>
      {projection.outcome === 'PROJECTED' ? (
        <span className="rwtd-v2-repeat-occurrences">
          {projection.occurrences.map((occurrence, index) => {
            const value = occurrence.value;
            const objectValue = typeof value === 'object' ? value : null;
            const primaryLabel = objectValue ? objectValue.name : String(value);
            const active = index === activeIndex;
            return (
              <button
                key={occurrence.virtualId}
                type="button"
                className={`rwtd-v2-repeat-occurrence${active ? ' is-active' : ''}${state.repeatPreviewMode === 'item' && !active ? ' is-muted' : ''}`}
                data-repeat-occurrence-index={occurrence.inputIndex}
                aria-label={`预览循环实例 ${occurrence.inputIndex + 1}：${occurrence.label}`}
                style={{
                  left: occurrence.xMm * pxPerMm,
                  top: occurrence.yMm * pxPerMm,
                  width: Math.max(12, occurrence.widthMm * pxPerMm),
                  height: Math.max(10, occurrence.heightMm * pxPerMm),
                }}
                onPointerDown={(event) => {
                  event.preventDefault();
                  event.stopPropagation();
                  dispatch({ type: 'select-node', nodeId: node.id });
                  dispatch({ type: 'set-repeat-active-index', index });
                }}
                onClick={(event) => {
                  event.stopPropagation();
                  dispatch({ type: 'select-node', nodeId: node.id });
                  dispatch({ type: 'set-repeat-active-index', index });
                }}
              >
                <span className="rwtd-v2-repeat-occurrence-index">{occurrence.inputIndex + 1}</span>
                <span className="rwtd-v2-repeat-occurrence-copy">
                  <strong>{primaryLabel}</strong>
                  {objectValue ? <small><b>{objectValue.price}</b><i>{objectValue.badge}</i></small> : <small>{projection.itemLayout.children.length} 个 authored 节点</small>}
                </span>
                {projection.itemLayout.children.some((child) => child.kind === 'rect') ? <span className="rwtd-v2-repeat-marker" aria-hidden="true" /> : null}
              </button>
            );
          })}
        </span>
      ) : (
        <span className={`rwtd-v2-repeat-placeholder is-${projection.outcome.toLocaleLowerCase()}`}>
          {projection.outcome === 'ABSENT_ERROR' || projection.outcome === 'INVALID' ? <AlertTriangle aria-hidden="true" size={16 * canvasScale} /> : <Repeat2 aria-hidden="true" size={16 * canvasScale} />}
          <strong>{projection.outcome === 'EMPTY' ? '0 个实例' : projection.phase === 'NEEDS_REPAIR' ? '单项内容需修复' : projection.phase === 'CONTENT_REQUIRED' ? '设计一份单项内容' : '选择数组数据源'}</strong>
          <small>{projection.message}</small>
        </span>
      )}
    </span>
  );
}

function ConditionalCanvasPreview({
  projection,
  canvasScale,
}: {
  projection: ConditionalProjection;
  canvasScale: number;
}) {
  const included = projection.outcome === 'INCLUDED';
  const label = included
    ? 'TRUE · FRAME'
    : projection.outcome === 'PRUNED_FALSE'
      ? 'FALSE · 已剪枝'
      : projection.outcome === 'PRUNED_ABSENT'
        ? 'ABSENT → FALSE'
        : projection.outcome === 'RENDER_DISABLED'
          ? 'render:false'
          : projection.outcome === 'INVALID'
            ? '条件待配置'
            : 'Evaluation 错误';
  return (
    <span
      className={`rwtd-v2-conditional-preview${included ? ' is-included' : ' is-editor-placeholder'}${projection.outcome === 'ABSENT_ERROR' || projection.outcome === 'INPUT_INVALID' ? ' is-error' : ''}`}
      data-conditional-outcome={projection.outcome.toLocaleLowerCase()}
      data-runtime-layout={projection.participatesInLayout ? 'included' : 'excluded'}
    >
      <span className="rwtd-v2-conditional-status">
        {projection.outcome === 'ABSENT_ERROR' || projection.outcome === 'INPUT_INVALID'
          ? <AlertTriangle aria-hidden="true" size={10 * canvasScale} />
          : projection.outcome === 'PRUNED_FALSE' || projection.outcome === 'PRUNED_ABSENT' || projection.outcome === 'RENDER_DISABLED'
            ? <EyeOff aria-hidden="true" size={10 * canvasScale} />
            : kindIcons.conditional}
        <strong>{label}</strong>
      </span>
      {!included ? <small>编辑器占位 · 不进入输出</small> : null}
    </span>
  );
}

function CanvasNodeContent({ node, canvasScale, state, dispatch }: { node: DesignerNode; canvasScale: number; state: DesignerState; dispatch: Dispatch<DesignerAction> }) {
  if (node.kind === 'text') {
    const authoredFontSizePt = Number(nodeProp(node, 'runs[0].fontSizePt') ?? 8);
    const safeFontSizePt = Number.isFinite(authoredFontSizePt) && authoredFontSizePt > 0 ? authoredFontSizePt : 8;
    const fontAsset = assetFromRef(nodeProp(node, 'runs[0].fontRef'));
    const horizontalAlign = nodeProp(node, 'horizontalAlign') ?? 'LEFT';
    const verticalAlign = nodeProp(node, 'verticalAlign') ?? 'TOP';
    return (
      <span
        className="rwtd-v2-canvas-text"
        data-font-asset-id={fontAsset?.kind === 'FONT' ? fontAsset.id : ''}
        data-font-asset-name={fontAsset?.kind === 'FONT' ? fontAsset.name : ''}
        style={{
          alignItems: verticalAlign === 'CENTER' ? 'center' : verticalAlign === 'BOTTOM' ? 'flex-end' : 'flex-start',
          ...canvasPaddingStyle(node, canvasScale),
          boxSizing: 'border-box',
          color: canvasPaint(nodeProp(node, 'runs[0].color'), 'var(--color-ink)'),
          fontFamily: fontAsset?.kind === 'FONT' ? fontAsset.previewFamily : undefined,
          fontSize: `${safeFontSizePt * (96 / 72) * canvasScale}px`,
          justifyContent: horizontalAlign === 'CENTER' ? 'center' : horizontalAlign === 'RIGHT' ? 'flex-end' : 'flex-start',
          textAlign: horizontalAlign === 'CENTER' ? 'center' : horizontalAlign === 'RIGHT' ? 'right' : 'left',
          writingMode: nodeProp(node, 'writingMode') === 'VERTICAL_RL' ? 'vertical-rl' : 'horizontal-tb',
        }}
      >
        {nodeProp(node, 'runs[0].text') ?? node.name}
      </span>
    );
  }
  if (node.kind === 'image') {
    const radius = numericNodeProp(node, 'cornerRadiusMm', 0) * (96 / 25.4) * canvasScale;
    return (
      <span className="rwtd-v2-image-preview" style={{ borderRadius: `${radius}px` }}>
        <span className="rwtd-v2-image-mark"><Image aria-hidden="true" size={18 * canvasScale} /></span>
        <small>{assetNameFromRef(nodeProp(node, 'imageRef'))}</small>
        <em>{nodeProp(node, 'fit') === 'CONTAIN' ? '完整显示' : nodeProp(node, 'fit') === 'FILL' ? '拉伸填充' : '覆盖裁剪'}</em>
      </span>
    );
  }
  if (node.kind === 'rect') {
    const pxPerMm = (96 / 25.4) * canvasScale;
    const radius = (label: string) => numericNodeProp(node, label, numericNodeProp(node, 'cornerRadii.topLeftMm', 0)) * pxPerMm;
    return (
      <span
        className="rwtd-v2-element-visual rwtd-v2-rect-visual"
        style={{
          background: canvasPaint(nodeProp(node, 'fill.color'), 'transparent'),
          borderColor: canvasPaint(nodeProp(node, 'stroke.color'), 'var(--color-ink)'),
          borderRadius: `${radius('cornerRadii.topLeftMm')}px ${radius('cornerRadii.topRightMm')}px ${radius('cornerRadii.bottomRightMm')}px ${radius('cornerRadii.bottomLeftMm')}px`,
          borderWidth: `${Math.max(0, numericNodeProp(node, 'stroke.widthMm', 0) * pxPerMm)}px`,
        }}
      />
    );
  }
  if (node.kind === 'ellipse') {
    const fill = canvasPaint(nodeProp(node, 'fill.color'), 'transparent');
    const stroke = canvasPaint(nodeProp(node, 'stroke.color'), 'var(--color-ink)');
    const strokeWidth = Math.max(0.8, numericNodeProp(node, 'stroke.widthMm', 0.3) * 4);
    const innerRadius = Math.max(0, Math.min(40, numericNodeProp(node, 'innerRadiusMm', 0) * 4));
    return (
      <svg className="rwtd-v2-element-visual" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        <ellipse cx="50" cy="50" rx={48 - strokeWidth / 2} ry={48 - strokeWidth / 2} fill={fill} stroke={stroke} strokeWidth={strokeWidth} />
        {innerRadius > 0 ? <ellipse cx="50" cy="50" rx={innerRadius} ry={innerRadius} fill="var(--color-surface)" stroke={stroke} strokeWidth={strokeWidth / 2} /> : null}
      </svg>
    );
  }
  if (node.kind === 'line') {
    const stroke = canvasPaint(nodeProp(node, 'stroke.color'), 'var(--color-ink)');
    const strokeWidth = Math.max(1, numericNodeProp(node, 'stroke.widthMm', 0.6) * 4);
    const startArrow = nodeProp(node, 'startArrow') ?? 'NONE';
    const endArrow = nodeProp(node, 'endArrow') ?? 'NONE';
    return (
      <svg className="rwtd-v2-element-visual" viewBox="0 0 100 20" preserveAspectRatio="none" aria-hidden="true">
        <line x1={startArrow === 'NONE' ? 2 : 11} y1="10" x2={endArrow === 'NONE' ? 98 : 89} y2="10" stroke={stroke} strokeWidth={strokeWidth} strokeLinecap={(nodeProp(node, 'lineCap') ?? 'ROUND').toLocaleLowerCase() as 'butt' | 'round' | 'square'} />
        {startArrow === 'ARROW' ? <path d="M1 10 13 2 13 18Z" fill={stroke} /> : null}
        {startArrow === 'CIRCLE' ? <circle cx="7" cy="10" r="5" fill={stroke} /> : null}
        {endArrow === 'ARROW' ? <path d="M99 10 87 2 87 18Z" fill={stroke} /> : null}
        {endArrow === 'CIRCLE' ? <circle cx="93" cy="10" r="5" fill={stroke} /> : null}
      </svg>
    );
  }
  if (node.kind === 'polyline') {
    const closed = nodeProp(node, 'closed') === 'true';
    const points = normalizedPolylinePoints(nodeProp(node, 'points'));
    const stroke = canvasPaint(nodeProp(node, 'stroke.color'), 'var(--color-ink)');
    const fill = closed ? canvasPaint(nodeProp(node, 'fill.color'), 'var(--color-accent-wash)') : 'none';
    const strokeWidth = Math.max(1, numericNodeProp(node, 'stroke.widthMm', 0.7) * 3.5);
    const shapeProps = { fill, points, stroke, strokeLinecap: 'round' as const, strokeLinejoin: (nodeProp(node, 'lineJoin') ?? 'ROUND').toLocaleLowerCase() as 'bevel' | 'miter' | 'round', strokeWidth };
    return (
      <svg className="rwtd-v2-element-visual" viewBox="0 0 100 60" preserveAspectRatio="none" aria-hidden="true">
        {closed ? <polygon {...shapeProps} /> : <polyline {...shapeProps} />}
      </svg>
    );
  }
  if (node.kind === 'path') {
    return (
      <svg className="rwtd-v2-element-visual" viewBox="0 0 100 70" preserveAspectRatio="none" aria-hidden="true">
        <path
          d={nodeProp(node, 'pathData') ?? 'M8 56 C28 4 52 4 62 34 C72 62 88 58 94 20 L94 62 L8 62 Z'}
          fill={canvasPaint(nodeProp(node, 'fill.color'), 'var(--color-accent-wash)')}
          fillRule={nodeProp(node, 'fillRule') === 'EVEN_ODD' ? 'evenodd' : 'nonzero'}
          stroke={canvasPaint(nodeProp(node, 'stroke.color'), 'var(--color-ink)')}
          strokeWidth={Math.max(1, numericNodeProp(node, 'stroke.widthMm', 0.35) * 3.5)}
        />
      </svg>
    );
  }
  if (node.kind === 'polygon') {
    const preset = nodeProp(node, 'preset') ?? 'STAR';
    const count = numericNodeProp(node, 'pointsCount', 5);
    const innerRadius = numericNodeProp(node, 'innerRadiusMm', 5);
    const fill = canvasPaint(nodeProp(node, 'fill.color'), 'var(--color-accent-wash)');
    const stroke = canvasPaint(nodeProp(node, 'stroke.color'), 'var(--color-ink)');
    const strokeWidth = Math.max(1, numericNodeProp(node, 'stroke.widthMm', 0.3) * 3.5);
    return (
      <svg className="rwtd-v2-element-visual" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        {preset === 'ARROW' ? (
          <path d="M5 34 H58 V14 L96 50 58 86 V66 H5 Z" fill={fill} stroke={stroke} strokeWidth={strokeWidth} strokeLinejoin="round" />
        ) : (
          <polygon points={regularShapePoints(preset === 'TRIANGLE' ? 3 : count, preset === 'STAR' ? Math.max(0.18, Math.min(0.78, innerRadius / 10)) : 1)} fill={fill} stroke={stroke} strokeWidth={strokeWidth} strokeLinejoin="round" />
        )}
      </svg>
    );
  }
  if (node.kind === 'qrCode') {
    const foreground = canvasPaint(nodeProp(node, 'foregroundColor'), 'var(--color-ink)');
    const background = canvasPaint(nodeProp(node, 'backgroundColor'), 'var(--color-surface)');
    const modules = qrModules(nodeProp(node, 'content') ?? 'renderweave');
    return (
      <svg className="rwtd-v2-element-visual rwtd-v2-code-visual" viewBox="-2 -2 25 25" aria-hidden="true">
        <rect x="-2" y="-2" width="25" height="25" fill={background} />
        {modules.map(([x, y]) => <rect key={`${x}-${y}`} x={x} y={y} width="1" height="1" fill={foreground} />)}
        <QrFinder x={0} y={0} foreground={foreground} background={background} />
        <QrFinder x={14} y={0} foreground={foreground} background={background} />
        <QrFinder x={0} y={14} foreground={foreground} background={background} />
      </svg>
    );
  }
  if (node.kind === 'barcode') {
    const foreground = canvasPaint(nodeProp(node, 'foregroundColor'), 'var(--color-ink)');
    const background = canvasPaint(nodeProp(node, 'backgroundColor'), 'var(--color-surface)');
    const code = nodeProp(node, 'code') ?? '6901234567892';
    const showText = nodeProp(node, 'showText') !== 'false';
    return (
      <svg className="rwtd-v2-element-visual rwtd-v2-code-visual" viewBox="0 0 120 60" preserveAspectRatio="none" aria-hidden="true">
        <rect width="120" height="60" fill={background} />
        {barcodeBars(code).map((bar, index) => <rect key={`${bar.x}-${index}`} x={bar.x} y="5" width={bar.width} height={bar.tall ? 44 : 38} fill={foreground} />)}
        {showText ? <text x="60" y="57" fill={foreground} fontSize="9" textAnchor="middle" fontFamily="monospace" letterSpacing="1.4">{code}</text> : null}
      </svg>
    );
  }
  if (node.kind === 'frame' || node.kind === 'stack' || node.kind === 'grid') {
    const pxPerMm = (96 / 25.4) * canvasScale;
    const radius = (label: string) => Math.max(0, numericNodeProp(node, label, 0)) * pxPerMm;
    return (
      <span
        className={`rwtd-v2-container-surface kind-${node.kind}`}
        style={{
          background: canvasPaint(nodeProp(node, 'fill.color'), 'var(--color-surface)'),
          borderColor: canvasPaint(nodeProp(node, 'stroke.color'), 'var(--color-hairline-strong)'),
          borderRadius: `${radius('cornerRadii.topLeftMm')}px ${radius('cornerRadii.topRightMm')}px ${radius('cornerRadii.bottomRightMm')}px ${radius('cornerRadii.bottomLeftMm')}px`,
          borderWidth: `${Math.max(0, numericNodeProp(node, 'stroke.widthMm', 0)) * pxPerMm}px`,
          overflow: nodeProp(node, 'clipContent') === 'true' ? 'hidden' : 'visible',
        }}
      >
        <span className="rwtd-v2-container-padding" style={canvasPaddingStyle(node, canvasScale)}>
          <span className="rwtd-v2-container-content-box">
            <span className="rwtd-v2-container-label">{node.name}</span>
          </span>
        </span>
      </span>
    );
  }
  if (node.kind === 'templateUse') return <span className="rwtd-v2-canvas-resource"><Puzzle aria-hidden="true" size={14 * canvasScale} />{node.name}</span>;
  if (node.kind === 'repeat') return (
    <RepeatCanvasPreview
      node={node}
      projection={projectPrototypeRepeat(node, state.boxes, state.repeatPreviewSample, state.definitions)}
      state={state}
      dispatch={dispatch}
      canvasScale={canvasScale}
    />
  );
  if (node.kind === 'conditional') return (
    <ConditionalCanvasPreview
      projection={projectPrototypeConditional(node, state.conditionalPreviewSample)}
      canvasScale={canvasScale}
    />
  );
  if (node.kind === 'group') return null;
  if (isContainerNodeKind(node.kind)) return <span className="rwtd-v2-container-label">{node.name}</span>;
  return null;
}

type ResizeHandle = 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w' | 'nw';

const resizeHandles: ResizeHandle[] = ['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw'];

interface CanvasGesture {
  pointerId: number;
  mode: 'move' | 'resize';
  handle: ResizeHandle | null;
  startClientX: number;
  startClientY: number;
  origin: DraftBox;
  constraint: LayoutConstraint | null;
  previewMemberIds: string[];
  moved: boolean;
}

interface LayoutConstraint {
  kind: ManagedLayoutKind | 'canvas';
  label: string;
  parentId: string;
  parentName: string;
}

interface LayoutDragPreview {
  nodeId: string;
  memberIds: string[];
  dxPx: number;
  dyPx: number;
  restoring: boolean;
  constraint: LayoutConstraint;
}

interface CanvasPaintEntry {
  box: DraftBox;
  node: DesignerNode;
}

function repeatAuthoredDescendantIds(tree: DesignerNode): Set<string> {
  const hidden = new Set<string>();
  const visit = (node: DesignerNode, insideRepeat: boolean) => {
    node.children.forEach((child) => {
      if (insideRepeat || node.kind === 'repeat') hidden.add(child.id);
      visit(child, insideRepeat || node.kind === 'repeat');
    });
  };
  visit(tree, false);
  return hidden;
}

function layoutConstraintForNode(tree: DesignerNode, nodeId: string): LayoutConstraint | null {
  const parent = findParentNode(tree, nodeId);
  if (!parent || !isLayoutManagingNode(parent)) return null;
  if (parent.kind === 'canvas') {
    const canvas = canvasProjection(parent);
    const label = canvas.layoutMode === 'GRID'
      ? 'Canvas 网格'
      : `Canvas 堆叠${canvas.direction === 'HORIZONTAL' ? '横向' : '纵向'}`;
    return { kind: 'canvas', label, parentId: parent.id, parentName: parent.name };
  }
  if (!isManagedLayoutKind(parent.kind)) return null;
  const direction = nodeProp(parent, 'direction');
  const label = parent.kind === 'stack'
    ? `Stack ${direction === 'HORIZONTAL' ? '横向' : '纵向'}`
    : parent.kind === 'grid'
      ? 'Grid 网格'
      : 'Repeat 循环';
  return { kind: parent.kind, label, parentId: parent.id, parentName: parent.name };
}

function clampCanvasValue(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}

function resizedBox(
  origin: DraftBox,
  handle: ResizeHandle,
  dx: number,
  dy: number,
  canvas: ReturnType<typeof canvasProjection>,
): Pick<DraftBox, 'x' | 'y' | 'w' | 'h'> {
  const minimum = 0.5;
  let left = origin.x;
  let top = origin.y;
  let right = origin.x + origin.w;
  let bottom = origin.y + origin.h;
  if (handle.includes('w')) left = clampCanvasValue(origin.x + dx, 0, right - minimum);
  if (handle.includes('e')) right = clampCanvasValue(origin.x + origin.w + dx, left + minimum, canvas.widthMm);
  if (handle.includes('n')) top = clampCanvasValue(origin.y + dy, 0, bottom - minimum);
  if (handle.includes('s')) bottom = clampCanvasValue(origin.y + origin.h + dy, top + minimum, canvas.heightMm);
  return { x: left, y: top, w: right - left, h: bottom - top };
}

function geometryLabel(box: DraftBox): string {
  const value = (number: number) => Number.isInteger(number) ? String(number) : number.toFixed(1);
  return `${value(box.x)}, ${value(box.y)} · ${value(box.w)} × ${value(box.h)} mm`;
}

function CanvasBox({
  box,
  node,
  state,
  dispatch,
  compact,
  tool,
  layoutPreview,
  onLayoutPreview,
  onLayoutRestore,
  onLayoutPreviewClear,
  onOpenContextMenu,
}: {
  box: DraftBox;
  node: DesignerNode;
  state: DesignerState;
  dispatch: Dispatch<DesignerAction>;
  compact: boolean;
  tool: CanvasTool;
  layoutPreview: LayoutDragPreview | null;
  onLayoutPreview: (preview: LayoutDragPreview) => void;
  onLayoutRestore: (nodeId: string) => void;
  onLayoutPreviewClear: (nodeId: string) => void;
  onOpenContextMenu: (request: NodeContextRequest) => void;
}) {
  const gesture = useRef<CanvasGesture | null>(null);
  const canvasScale = (compact ? 100 : state.zoom) / 100;
  const pxPerMm = (96 / 25.4) * canvasScale;
  const authoredBox = state.boxes.find((candidate) => candidate.nodeId === box.nodeId);
  const selected = state.selectedNodeIds.includes(box.nodeId);
  const primary = state.selectedNodeId === box.nodeId;
  const isContainer = isContainerNodeKind(node.kind);
  const layoutConstraint = layoutConstraintForNode(state.tree, node.id);
  const previewApplies = layoutPreview?.memberIds.includes(box.nodeId) ?? false;
  const previewX = previewApplies ? layoutPreview!.dxPx : 0;
  const previewY = previewApplies ? layoutPreview!.dyPx : 0;
  const previewOwner = layoutPreview?.nodeId === box.nodeId;
  const locked = nodeProp(node, 'locked') === 'true';
  const opacity = Math.max(0.08, Math.min(1, Number(nodeProp(node, 'opacity') ?? 100) / 100));
  const rotation = Number(nodeProp(node, 'rotationDeg') ?? 0);
  const hidden = nodeProp(node, 'visible') === 'false';
  const rendered = nodeProp(node, 'render') !== 'false';
  const style = {
    left: box.x * pxPerMm,
    top: box.y * pxPerMm,
    width: Math.max(2, box.w * pxPerMm),
    height: Math.max(2, box.h * pxPerMm),
    opacity: hidden ? 0.32 : opacity,
    transform: `translate(${previewX}px, ${previewY}px) rotate(${rotation}deg)`,
    '--rwtd-canvas-scale': canvasScale,
  } as CSSProperties;
  const className = `rwtd-v2-node kind-${node.kind} tone-${box.tone}${selected ? ' is-selected' : ''}${primary ? ' is-primary' : ''}${isContainer ? ' is-container' : ''}${layoutConstraint ? ' is-layout-managed' : ''}${previewApplies && !layoutPreview?.restoring ? ' is-layout-previewing' : ''}${previewApplies && layoutPreview?.restoring ? ' is-layout-restoring' : ''}${locked ? ' is-locked' : ''}${hidden ? ' is-hidden' : ''}${!rendered ? ' is-not-rendered' : ''}`;
  if (compact) return <span className={className} style={style} aria-hidden="true"><CanvasNodeContent node={node} canvasScale={canvasScale} state={state} dispatch={dispatch} /></span>;
  return (
    <div
      role={node.kind === 'repeat' ? 'group' : 'button'}
      tabIndex={0}
      className={className}
      style={style}
      aria-pressed={node.kind === 'repeat' ? undefined : selected}
      aria-label={`选择并移动节点 ${node.name}`}
      data-canvas-node-id={node.id}
      data-canvas-node-name={node.name}
      data-layout-constraint={layoutConstraint?.kind}
      data-layout-preview={previewOwner ? (layoutPreview?.restoring ? 'restoring' : 'dragging') : undefined}
      data-layout-derived={layoutConstraint ? 'true' : undefined}
      data-layout-x-mm={box.x}
      data-layout-y-mm={box.y}
      data-layout-width-mm={box.w}
      data-layout-height-mm={box.h}
      onContextMenu={(event) => {
        event.preventDefault();
        event.stopPropagation();
        if (!selected) dispatch({ type: 'select-node', nodeId: node.id });
        onOpenContextMenu({ nodeId: node.id, x: event.clientX, y: event.clientY });
      }}
      onPointerDown={(event) => {
        if (tool !== 'select' || event.button !== 0) return;
        event.preventDefault();
        event.stopPropagation();
        if (event.shiftKey) {
          dispatch({ type: 'select-node', nodeId: node.id, additive: true });
          return;
        }
        if (!primary || state.selectedNodeIds.length > 1) dispatch({ type: 'select-node', nodeId: node.id });
        if (locked) {
          dispatch({ type: 'set-notice', notice: `“${node.name}”已锁定，无法在画布中变换` });
          return;
        }
        onLayoutPreviewClear(node.id);
        event.currentTarget.setPointerCapture(event.pointerId);
        const constraint = layoutConstraint;
        gesture.current = {
          pointerId: event.pointerId,
          mode: 'move',
          handle: null,
          startClientX: event.clientX,
          startClientY: event.clientY,
          origin: { ...(authoredBox ?? box) },
          constraint,
          previewMemberIds: constraint ? flattenDesignerTree(node).map((member) => member.id) : [],
          moved: false,
        };
      }}
      onPointerMove={(event) => {
        const active = gesture.current;
        if (!active || active.pointerId !== event.pointerId) return;
        event.preventDefault();
        event.stopPropagation();
        const dxPx = event.clientX - active.startClientX;
        const dyPx = event.clientY - active.startClientY;
        active.moved = active.moved || Math.abs(dxPx) > 1 || Math.abs(dyPx) > 1;
        if (active.mode === 'move' && active.constraint) {
          onLayoutPreview({
            nodeId: node.id,
            memberIds: active.previewMemberIds,
            dxPx,
            dyPx,
            restoring: false,
            constraint: active.constraint,
          });
          return;
        }
        const dx = dxPx / pxPerMm;
        const dy = dyPx / pxPerMm;
        dispatch({
          type: 'transform-box',
          nodeId: node.id,
          box: { x: active.origin.x + dx, y: active.origin.y + dy, w: active.origin.w, h: active.origin.h },
          mode: 'move',
        });
      }}
      onPointerUp={(event) => {
        const active = gesture.current;
        if (!active || active.pointerId !== event.pointerId) return;
        gesture.current = null;
        if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
        if (active.constraint && active.moved) {
          onLayoutRestore(node.id);
          dispatch({ type: 'set-notice', notice: `“${node.name}”受 ${active.constraint.label}“${active.constraint.parentName}”约束，已复原到布局位置` });
        } else if (active.constraint) {
          onLayoutPreviewClear(node.id);
        }
      }}
      onPointerCancel={(event) => {
        const active = gesture.current;
        if (!active || active.pointerId !== event.pointerId) return;
        gesture.current = null;
        if (active.constraint && active.moved) onLayoutRestore(node.id);
        else if (active.constraint) onLayoutPreviewClear(node.id);
      }}
      onTransitionEnd={(event) => {
        if (event.target === event.currentTarget && event.propertyName === 'transform' && previewOwner && layoutPreview?.restoring) {
          onLayoutPreviewClear(node.id);
        }
      }}
      onClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          dispatch({ type: 'select-node', nodeId: node.id, additive: event.shiftKey });
        }
      }}
    >
      <CanvasNodeContent node={node} canvasScale={canvasScale} state={state} dispatch={dispatch} />
    </div>
  );
}

function CanvasSelectionOverlay({
  entries,
  state,
  dispatch,
  tool,
  layoutPreview,
}: {
  entries: CanvasPaintEntry[];
  state: DesignerState;
  dispatch: Dispatch<DesignerAction>;
  tool: CanvasTool;
  layoutPreview: LayoutDragPreview | null;
}) {
  const gesture = useRef<CanvasGesture | null>(null);
  const canvasScale = state.zoom / 100;
  const pxPerMm = (96 / 25.4) * canvasScale;
  const canvas = canvasProjection(state.tree);
  const selectedEntries = entries.filter(({ node }) => state.selectedNodeIds.includes(node.id));

  return (
    <div
      className="rwtd-v2-editor-overlay"
      data-editor-layer="selection"
      onClick={(event) => event.stopPropagation()}
    >
      {selectedEntries.map(({ box, node }) => {
        const primary = state.selectedNodeId === node.id;
        const layoutConstraint = layoutConstraintForNode(state.tree, node.id);
        const previewApplies = layoutPreview?.memberIds.includes(node.id) ?? false;
        const previewX = previewApplies ? layoutPreview!.dxPx : 0;
        const previewY = previewApplies ? layoutPreview!.dyPx : 0;
        const rotation = Number(nodeProp(node, 'rotationDeg') ?? 0);
        const locked = nodeProp(node, 'locked') === 'true';
        const resizable = node.kind !== 'group';
        const style = {
          left: box.x * pxPerMm,
          top: box.y * pxPerMm,
          width: Math.max(2, box.w * pxPerMm),
          height: Math.max(2, box.h * pxPerMm),
          transform: `translate(${previewX}px, ${previewY}px) rotate(${rotation}deg)`,
          '--rwtd-canvas-scale': canvasScale,
        } as CSSProperties;
        return (
          <span
            key={node.id}
            className={`rwtd-v2-selection-outline${primary ? ' is-primary' : ''}${previewApplies && !layoutPreview?.restoring ? ' is-layout-previewing' : ''}${previewApplies && layoutPreview?.restoring ? ' is-layout-restoring' : ''}${locked ? ' is-locked' : ''}`}
            style={style}
            data-node-id={node.id}
            aria-hidden="true"
          >
            <span className="rwtd-v2-node-id">{node.name}</span>
            {primary && tool === 'select' ? (
              <>
                <span className="rwtd-v2-node-geometry">{geometryLabel(box)}</span>
                {layoutConstraint ? <span className="rwtd-v2-node-layout-constraint">{layoutConstraint.label} 约束 · 松手复位</span> : null}
                {resizable ? resizeHandles.map((handle) => (
                  <span
                    key={handle}
                    className={`rwtd-v2-resize-handle handle-${handle}`}
                    data-resize-handle={handle}
                    onPointerDown={(event) => {
                      if (event.button !== 0) return;
                      event.preventDefault();
                      event.stopPropagation();
                      if (locked) {
                        dispatch({ type: 'set-notice', notice: `“${node.name}”已锁定，无法在画布中变换` });
                        return;
                      }
                      const authoredBox = state.boxes.find((candidate) => candidate.nodeId === node.id) ?? box;
                      event.currentTarget.setPointerCapture(event.pointerId);
                      gesture.current = {
                        pointerId: event.pointerId,
                        mode: 'resize',
                        handle,
                        startClientX: event.clientX,
                        startClientY: event.clientY,
                        origin: { ...authoredBox },
                        constraint: null,
                        previewMemberIds: [],
                        moved: false,
                      };
                    }}
                    onPointerMove={(event) => {
                      const active = gesture.current;
                      if (!active || active.pointerId !== event.pointerId || active.mode !== 'resize') return;
                      event.preventDefault();
                      event.stopPropagation();
                      const dx = (event.clientX - active.startClientX) / pxPerMm;
                      const dy = (event.clientY - active.startClientY) / pxPerMm;
                      active.moved = active.moved || Math.abs(dx) > 0.1 || Math.abs(dy) > 0.1;
                      dispatch({
                        type: 'transform-box',
                        nodeId: node.id,
                        box: resizedBox(active.origin, handle, dx, dy, canvas),
                        mode: 'resize',
                      });
                    }}
                    onPointerUp={(event) => {
                      const active = gesture.current;
                      if (!active || active.pointerId !== event.pointerId) return;
                      event.preventDefault();
                      event.stopPropagation();
                      gesture.current = null;
                      if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
                    }}
                    onPointerCancel={(event) => {
                      const active = gesture.current;
                      if (!active || active.pointerId !== event.pointerId) return;
                      event.stopPropagation();
                      gesture.current = null;
                    }}
                  />
                )) : null}
              </>
            ) : null}
          </span>
        );
      })}
    </div>
  );
}

export function PrototypeCanvas({
  state,
  dispatch,
  compact = false,
  backgroundPan = false,
  wheelZoom = true,
}: Pick<PartProps, 'state' | 'dispatch'> & { compact?: boolean; backgroundPan?: boolean; wheelZoom?: boolean }) {
  const drag = useRef<{ pointerId: number; x: number; y: number; moved: boolean } | null>(null);
  const didPan = useRef(false);
  const [layoutPreview, setLayoutPreview] = useState<LayoutDragPreview | null>(null);
  const [catalogDropActive, setCatalogDropActive] = useState(false);
  const [contextMenu, setContextMenu] = useState<NodeContextRequest | null>(null);
  const effectiveTool: CanvasTool = state.spacePanActive ? 'pan' : state.activeTool;
  const pxPerMm = (96 / 25.4) * ((compact ? 100 : state.zoom) / 100);
  const canvas = canvasProjection(state.tree);
  const conditionalRuntime = projectPrototypeConditionalRuntime(state.tree, state.conditionalPreviewSample);
  const layoutProjection = projectPrototypeLayout(state.tree, state.boxes, {
    excludedNodeIds: conditionalRuntime.excludedNodeIds,
  });
  const authoredBranchProjection = conditionalRuntime.excludedNodeIds.size > 0
    ? projectPrototypeLayout(state.tree, state.boxes)
    : layoutProjection;
  const repeatItemNodeIds = repeatAuthoredDescendantIds(state.tree);
  const paintEntries: CanvasPaintEntry[] = flattenDesignerTree(state.tree).flatMap((node) => {
    if (node.kind === 'canvas' || repeatItemNodeIds.has(node.id) || conditionalRuntime.hiddenDescendantIds.has(node.id)) return [];
    const box = node.kind === 'conditional' && conditionalRuntime.excludedNodeIds.has(node.id)
      ? authoredBranchProjection.boxByNodeId.get(node.id)
      : layoutProjection.boxByNodeId.get(node.id);
    return box ? [{ box, node }] : [];
  });
  const canvasNode = state.tree;
  const width = canvas.widthMm * pxPerMm;
  const height = canvas.heightMm * pxPerMm;
  const sizeLabel = `${canvas.widthMm} × ${canvas.heightMm} mm`;
  const layoutLabel = canvas.layoutMode === 'STACK'
    ? `堆叠 · ${canvas.direction === 'HORIZONTAL' ? '横向' : '纵向'}`
    : canvas.layoutMode === 'GRID'
      ? '网格布局'
      : '自由布局';
  const paddingLabel = `${canvas.padding.topMm}/${canvas.padding.rightMm}/${canvas.padding.bottomMm}/${canvas.padding.leftMm}mm`;
  const contentGuideStyle = {
    top: canvas.padding.topMm * pxPerMm,
    right: canvas.padding.rightMm * pxPerMm,
    bottom: canvas.padding.bottomMm * pxPerMm,
    left: canvas.padding.leftMm * pxPerMm,
  } as CSSProperties;
  return (
    <section className={`rwtd-v2-canvas${compact ? ' is-compact' : ''}`} aria-label="模板画布">
      <div className="rwtd-v2-canvas-meta"><span><i data-tool={effectiveTool} />{state.spacePanActive ? '临时平移 · Space' : effectiveTool === 'select' ? '选择模式' : '平移模式'}</span><span>{sizeLabel}</span><span>{layoutLabel}</span><span>{layoutProjection.stackByContainerId.size + layoutProjection.gridByContainerId.size > 0 ? `${layoutProjection.stackByContainerId.size} Stack / ${layoutProjection.gridByContainerId.size} Grid 实时计算` : '浏览器原型投影'} · {conditionalRuntime.byNodeId.size} Conditional · 非 RenderServer</span></div>
      <div
        className={`rwtd-v2-canvas-viewport tool-${effectiveTool}${backgroundPan ? ' background-pan' : ''}`}
        tabIndex={0}
        aria-label={effectiveTool === 'pan' ? '画布视口，拖动可平移' : backgroundPan ? '画布视口，选择后可移动或缩放；画板外拖动可平移' : '画布视口，选择后可移动或缩放元素'}
        onPointerDown={(event) => {
          const startsOutsideArtboard = backgroundPan && event.target === event.currentTarget;
          if (effectiveTool !== 'pan' && !startsOutsideArtboard) return;
          event.preventDefault();
          event.currentTarget.setPointerCapture(event.pointerId);
          didPan.current = false;
          drag.current = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, moved: false };
        }}
        onPointerMove={(event) => {
          if (!drag.current || drag.current.pointerId !== event.pointerId) return;
          const dx = event.clientX - drag.current.x;
          const dy = event.clientY - drag.current.y;
          drag.current = {
            pointerId: event.pointerId,
            x: event.clientX,
            y: event.clientY,
            moved: drag.current.moved || Math.abs(dx) + Math.abs(dy) > 2,
          };
          dispatch({ type: 'pan-by', dx, dy });
        }}
        onPointerUp={(event) => {
          if (drag.current?.pointerId !== event.pointerId) return;
          didPan.current = drag.current.moved;
          drag.current = null;
        }}
        onPointerCancel={(event) => {
          if (drag.current?.pointerId === event.pointerId) drag.current = null;
        }}
        onWheel={(event) => {
          if (!wheelZoom || event.deltaY === 0) return;
          event.preventDefault();
          const zoom = Math.max(25, Math.min(300, state.zoom + (event.deltaY < 0 ? 25 : -25)));
          if (zoom === state.zoom) return;
          const bounds = event.currentTarget.getBoundingClientRect();
          const cursorX = event.clientX - (bounds.left + bounds.width / 2);
          const cursorY = event.clientY - (bounds.top + bounds.height / 2);
          const ratio = zoom / state.zoom;
          dispatch({
            type: 'set-zoom-at',
            zoom,
            offset: {
              x: state.canvasOffset.x + (cursorX - state.canvasOffset.x) * (1 - ratio),
              y: state.canvasOffset.y + (cursorY - state.canvasOffset.y) * (1 - ratio),
            },
          });
        }}
        onClick={() => {
          if (didPan.current) {
            didPan.current = false;
            return;
          }
          if (effectiveTool === 'select') dispatch({ type: 'select-node', nodeId: nodeIds.canvas });
        }}
      >
        <div className="rwtd-v2-canvas-stage" style={{ transform: `translate(${state.canvasOffset.x}px, ${state.canvasOffset.y}px)` }}>
          <div
            className={`rwtd-v2-artboard${state.selectedNodeIds.includes(nodeIds.canvas) ? ' is-selected' : ''}${state.showElementOutlines ? ' show-outlines' : ''}${catalogDropActive ? ' is-element-drop-target' : ''}`}
            data-canvas-layout={canvas.layoutMode.toLowerCase()}
            data-canvas-direction={canvas.direction.toLowerCase()}
            style={{
              width,
              height,
              background: canvasPaint(nodeProp(canvasNode, 'backgroundColor'), 'var(--color-surface)'),
              '--rwtd-canvas-scale': (compact ? 100 : state.zoom) / 100,
            } as CSSProperties}
            onContextMenu={(event) => {
              if (compact || (event.target as HTMLElement).closest('[data-canvas-node-id]')) return;
              event.preventDefault();
              event.stopPropagation();
              dispatch({ type: 'select-node', nodeId: nodeIds.canvas });
              setContextMenu({ nodeId: nodeIds.canvas, x: event.clientX, y: event.clientY });
            }}
            onDragEnter={(event) => {
              if (!Array.from(event.dataTransfer.types).includes(ELEMENT_DRAG_MIME)) return;
              event.preventDefault();
              event.stopPropagation();
              setCatalogDropActive(true);
            }}
            onDragOver={(event) => {
              if (!Array.from(event.dataTransfer.types).includes(ELEMENT_DRAG_MIME)) return;
              event.preventDefault();
              event.stopPropagation();
              event.dataTransfer.dropEffect = 'copy';
              setCatalogDropActive(true);
            }}
            onDragLeave={(event) => {
              if (event.currentTarget.contains(event.relatedTarget as Node | null)) return;
              setCatalogDropActive(false);
            }}
            onDrop={(event) => {
              const kind = event.dataTransfer.getData(ELEMENT_DRAG_MIME);
              const elementEntry = elementCatalog.find((candidate) => candidate.kind === kind);
              const containerEntry = containerCatalog.find((candidate) => candidate.kind === kind);
              setCatalogDropActive(false);
              if (!elementEntry && !containerEntry) return;
              event.preventDefault();
              event.stopPropagation();
              const bounds = event.currentTarget.getBoundingClientRect();
              dispatch({
                type: 'insert-node',
                kind: elementEntry?.kind ?? containerEntry!.kind,
                preset: containerEntry?.preset,
                parentId: nodeIds.canvas,
                positionMm: {
                  x: Math.max(0, Math.min(canvas.widthMm, (event.clientX - bounds.left) / pxPerMm)),
                  y: Math.max(0, Math.min(canvas.heightMm, (event.clientY - bounds.top) / pxPerMm)),
                },
              });
            }}
          >
            <span className="rwtd-v2-artboard-size">{sizeLabel.replaceAll(' ', '')}</span>
            <span className="rwtd-v2-canvas-content-guide" style={contentGuideStyle} aria-hidden="true">
              <small>{layoutLabel} · 内边距 {paddingLabel}</small>
            </span>
            {catalogDropActive ? (
              <span className="rwtd-v2-element-drop-hint" role="status">
                <Plus aria-hidden="true" size={18} />
                <strong>放开以添加到此处</strong>
                <small>将按当前画板坐标创建</small>
              </span>
            ) : null}
            {paintEntries.map(({ box, node }) => (
              <CanvasBox
                key={box.nodeId}
                box={box}
                node={node}
                state={state}
                dispatch={dispatch}
                compact={compact}
                tool={effectiveTool}
                layoutPreview={layoutPreview}
                onLayoutPreview={setLayoutPreview}
                onLayoutRestore={(nodeId) => setLayoutPreview((current) => current?.nodeId === nodeId
                  ? { ...current, dxPx: 0, dyPx: 0, restoring: true }
                  : current)}
                onLayoutPreviewClear={(nodeId) => setLayoutPreview((current) => current?.nodeId === nodeId ? null : current)}
                onOpenContextMenu={setContextMenu}
              />
            ))}
            {compact ? null : (
              <CanvasSelectionOverlay
                entries={paintEntries}
                state={state}
                dispatch={dispatch}
                tool={effectiveTool}
                layoutPreview={layoutPreview}
              />
            )}
          </div>
        </div>
      </div>
      {compact ? null : <footer className="rwtd-v2-canvas-foot"><span>{state.selectedNodeIds.length} 个已选</span><span>左侧组件拖放定位 · 右键操作 · Ctrl+A 全选</span><span>{backgroundPan ? 'Space 临时平移 · 画板外拖动 · 滚轮缩放' : effectiveTool === 'pan' ? '拖动视口 · Esc 返回选择' : 'V 选择 · H 平移'}</span></footer>}
      {!compact && contextMenu ? <NodeContextMenu request={contextMenu} state={state} dispatch={dispatch} onClose={() => setContextMenu(null)} /> : null}
    </section>
  );
}

export function InspectorDock({ state, dispatch, compactActions = false }: Pick<PartProps, 'state' | 'dispatch'> & { compactActions?: boolean }) {
  return (
    <section className="rwtd-v2-inspector-dock" aria-label="属性与结构操作">
      <StructureActions state={state} dispatch={dispatch} compact={compactActions} hideSummary />
      <div className="rwtd-v2-inspector-scroll"><Inspector state={state} dispatch={dispatch} /></div>
    </section>
  );
}

export function StudioStatusBar({ state }: { state: DesignerState }) {
  const selected = findNode(state.tree, state.selectedNodeId) ?? state.tree;
  const effectiveTool: CanvasTool = state.spacePanActive ? 'pan' : state.activeTool;
  return (
    <footer className="rwtd-v2-status" role="status">
      <span>PROTOTYPE · 内存状态</span><span>{state.spacePanActive ? 'Space 临时平移' : effectiveTool === 'select' ? 'V 选择' : 'H 平移'}</span><span>{selected.name}</span><span>{flattenDesignerTree(state.tree).length - 1} 个节点</span><span>{state.zoom}%</span>
    </footer>
  );
}
