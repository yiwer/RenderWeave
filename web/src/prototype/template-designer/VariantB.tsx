import { AlertTriangle, Eye, Link2, Play, Save } from 'lucide-react';
import { useState } from 'react';

import { findNode, problemsFor } from './model';
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
  TdChrome,
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
  const [showNavigator, setShowNavigator] = useState(true);
  const [showInspector, setShowInspector] = useState(true);
  const selected = findNode(state.tree, state.selectedNodeId) ?? state.tree;
  const problems = problemsFor(state.scenario);

  return (
    <div className="td-shell td-b rwtd rwtd-canvas-focus">
      <TdChrome {...props} layoutName="B · Canvas Focus" />
      <ScenarioBar state={state} dispatch={dispatch} />
      <main className="td-b-stage" id="main-content">
        <div className="td-b-canvas-area">
          <Artboard state={state} dispatch={dispatch} />
          <div className="td-b-context" aria-label="选中节点情境工具条">
            <span className="rwtd-context-kind">{selected.kind}</span>
            <strong>{selected.name}</strong>
            {['render', 'visible', 'opacity'].map((target) => (
              <button key={target} type="button" onClick={() => dispatch({ type: 'mark-dirty' })}>
                <Link2 aria-hidden="true" size={11} />
                {target}
              </button>
            ))}
            <button type="button" aria-label="切换可见性" title="切换可见性" onClick={() => dispatch({ type: 'mark-dirty' })}>
              <Eye aria-hidden="true" size={13} />
            </button>
          </div>
        </div>

        {showNavigator ? (
          <aside className="td-b-left" aria-label="结构与资源面板">
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
                  {{ tree: '结构', library: '节点', assets: '资产', definitions: '定义', exchange: '交换' }[tab]}
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
          </aside>
        ) : null}

        {showInspector ? (
          <aside className="td-b-right" aria-label="检查器面板">
            <Inspector state={state} dispatch={dispatch} />
          </aside>
        ) : null}

        {showData ? (
          <section className="td-b-data" aria-label="数据与预览面板">
            <DataPanel state={state} />
            <PreviewPanel {...props} />
          </section>
        ) : null}

        {showProblems ? (
          <section className="td-b-problems" aria-label="问题面板">
            <ProblemsList state={state} dispatch={dispatch} />
          </section>
        ) : null}

        <div className="td-b-dock" aria-label="画布工作区控制">
          <div className="rwtd-dock-group">
            <button
              type="button"
              className={`td-dock-button${showNavigator ? ' active' : ''}`}
              aria-pressed={showNavigator}
              onClick={() => setShowNavigator((value) => !value)}
            >
              导航
            </button>
            <button
              type="button"
              className={`td-dock-button${showInspector ? ' active' : ''}`}
              aria-pressed={showInspector}
              onClick={() => setShowInspector((value) => !value)}
            >
              属性
            </button>
          </div>
          <button
            type="button"
            className={`td-dock-button${showData ? ' active' : ''}`}
            aria-pressed={showData}
            onClick={() => setShowData((value) => !value)}
          >
            数据
          </button>
          <button type="button" className="td-dock-button" onClick={onRunPreview}>
            <Play aria-hidden="true" size={14} />
            预览
          </button>
          <button
            type="button"
            className={`td-dock-button${problems.length > 0 ? ' has-problems' : ''}${showProblems ? ' active' : ''}`}
            aria-pressed={showProblems}
            onClick={() => setShowProblems((value) => !value)}
          >
            <AlertTriangle aria-hidden="true" size={14} />
            {problems.length}
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
