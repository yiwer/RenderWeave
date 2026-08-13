import { AlertTriangle, Eye, Link2, Lock, Play, Save } from 'lucide-react';
import { useState } from 'react';

import { findNode, problemsFor, templateMeta } from './model';
import type { PartProps } from './SharedParts';
import {
  Artboard,
  AssetsPanel,
  DataPanel,
  DefinitionsPanel,
  ExchangePanel,
  Inspector,
  LibraryPanel,
  NodeTree,
  PreviewPanel,
  ProblemsList,
  ScenarioBar,
  StatusChip,
} from './SharedParts';

type FloatTab = 'tree' | 'library' | 'assets' | 'definitions' | 'exchange';

/**
 * 变体 B — Immersive Canvas 沉浸画布。
 * design-layout-draw 的「中央情境工具条」推到极端:画布占满舞台,面板全部浮动,
 * 选中节点头顶浮出情境工具条;底部 dock 承载保存/预览/问题。
 * 主要供能:画板本身;UI 按需浮现。
 */
export function VariantB(props: PartProps) {
  const { state, dispatch, onRunPreview } = props;
  const [floatTab, setFloatTab] = useState<FloatTab>('tree');
  const [showData, setShowData] = useState(false);
  const [showProblems, setShowProblems] = useState(false);
  const selected = findNode(state.tree, state.selectedNodeId) ?? state.tree;
  const problems = problemsFor(state.scenario);

  return (
    <div className="td-shell td-b">
      <main className="td-b-stage" id="main-content">
        <div className="td-b-canvas-area">
          <Artboard state={state} dispatch={dispatch} />
        </div>

        <div className="td-b-topbar">
          <div className="td-b-identity td-float">
            <span className="weave-mark" aria-hidden="true">RW</span>
            <strong>{templateMeta.name}</strong>
            <span className="td-schema-pill">
              <Lock aria-hidden="true" size={11} />
              {templateMeta.schemaRef}
            </span>
            <StatusChip state={state} />
          </div>
          <div className="td-b-scenarios td-float">
            <ScenarioBar state={state} dispatch={dispatch} />
          </div>
        </div>

        <div className="td-b-context td-float" aria-label="选中节点情境工具条">
          <span className="td-mini-chip">{selected.kind}</span>
          <strong>{selected.name}</strong>
          {['render', 'visible', 'opacity'].map((target) => (
            <button key={target} type="button" className="td-mini-chip" onClick={() => dispatch({ type: 'mark-dirty' })}>
              <Link2 aria-hidden="true" size={10} />
              {target}
            </button>
          ))}
          <button type="button" className="td-mini-chip" aria-label="切换可见性" onClick={() => dispatch({ type: 'mark-dirty' })}>
            <Eye aria-hidden="true" size={11} />
          </button>
        </div>

        <div className="td-b-left td-float" aria-label="结构与资源浮层">
          <div className="td-b-tabs" role="tablist">
            {(['tree', 'library', 'assets', 'definitions', 'exchange'] as FloatTab[]).map((tab) => (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={floatTab === tab}
                className={floatTab === tab ? 'active' : ''}
                onClick={() => setFloatTab(tab)}
              >
                {{ tree: '结构', library: '节点库', assets: '资产', definitions: '定义', exchange: '交换' }[tab]}
              </button>
            ))}
          </div>
          <div className="td-b-float-body">
            {floatTab === 'tree' && <NodeTree state={state} dispatch={dispatch} />}
            {floatTab === 'library' && <LibraryPanel dispatch={dispatch} />}
            {floatTab === 'assets' && <AssetsPanel state={state} dispatch={dispatch} />}
            {floatTab === 'definitions' && <DefinitionsPanel dispatch={dispatch} />}
            {floatTab === 'exchange' && <ExchangePanel state={state} dispatch={dispatch} />}
          </div>
        </div>

        <aside className="td-b-right td-float" aria-label="检查器浮层">
          <Inspector state={state} dispatch={dispatch} />
        </aside>

        {showData ? (
          <div className="td-b-data td-float" aria-label="数据与预览浮层">
            <DataPanel state={state} />
            <PreviewPanel {...props} />
          </div>
        ) : null}

        {showProblems ? (
          <div className="td-b-problems td-float" aria-label="问题浮层">
            <ProblemsList state={state} dispatch={dispatch} />
          </div>
        ) : null}

        <div className="td-b-dock td-float" aria-label="主操作坞">
          <button
            type="button"
            className={`td-dock-button${showData ? ' active' : ''}`}
            aria-pressed={showData}
            onClick={() => setShowData((value) => !value)}
          >
            数据 · 预览
          </button>
          <button type="button" className="td-dock-button" onClick={onRunPreview}>
            <Play aria-hidden="true" size={14} />
            权威预览
          </button>
          <button
            type="button"
            className={`td-dock-button${problems.length > 0 ? ' has-problems' : ''}${showProblems ? ' active' : ''}`}
            aria-pressed={showProblems}
            onClick={() => setShowProblems((value) => !value)}
          >
            <AlertTriangle aria-hidden="true" size={14} />
            问题 {problems.length}
          </button>
          <button type="button" className="button primary-button" onClick={() => dispatch({ type: 'save' })}>
            <Save aria-hidden="true" size={14} />
            保存
          </button>
        </div>
      </main>
    </div>
  );
}
