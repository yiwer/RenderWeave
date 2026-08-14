import { useState } from 'react';

import { findNode, problemsFor } from './model';
import type { PartProps } from './SharedParts';
import {
  Artboard,
  Inspector,
  LeftPanel,
  PreviewPanel,
  ProblemsList,
  RailButtons,
  ScenarioBar,
  TdChrome,
} from './SharedParts';

/**
 * 变体 A — Docked Workbench 三栏工作台。
 * hbads-design-v2 式经典 IDE:chrome + 图标 rail + 停靠面板 + 中央画布 + 右侧检查器
 * + 底部停靠条(问题 / 权威预览两个页签)。
 * 主要供能:画布与面板全部常显,信息密度最高。
 */
export function VariantA(props: PartProps) {
  const { state } = props;
  const [bottomTab, setBottomTab] = useState<'problems' | 'preview'>('problems');
  const [bottomOpen, setBottomOpen] = useState(false);
  const selected = findNode(state.tree, state.selectedNodeId) ?? state.tree;
  const problemCount = problemsFor(state.scenario).length;
  return (
    <div className="td-shell td-a rwtd rwtd-classic">
      <TdChrome {...props} layoutName="A · Studio Classic" />
      <ScenarioBar state={state} dispatch={props.dispatch} />
      <div className="td-a-body">
        <div className="rwtd-left-dock">
          <RailButtons state={state} dispatch={props.dispatch} />
          <aside className="td-a-panel" aria-label="资源面板">
            <LeftPanel state={state} dispatch={props.dispatch} />
          </aside>
        </div>
        <main className="td-a-canvas" id="main-content">
          <Artboard state={state} dispatch={props.dispatch} />
        </main>
        <aside className="td-a-inspector" aria-label="检查器">
          <Inspector state={state} dispatch={props.dispatch} />
        </aside>
      </div>
      <footer className="td-a-problems" data-open={bottomOpen} aria-label="问题与权威预览">
        <div className="td-a-bottom-tabs">
          <div className="rwtd-bottom-tabset" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={bottomTab === 'problems'}
              className={bottomTab === 'problems' ? 'active' : ''}
              onClick={() => {
                setBottomTab('problems');
                setBottomOpen(true);
              }}
            >
              问题 <span>{problemCount}</span>
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={bottomTab === 'preview'}
              className={bottomTab === 'preview' ? 'active' : ''}
              onClick={() => {
                setBottomTab('preview');
                setBottomOpen(true);
              }}
            >
              权威预览
            </button>
          </div>
          <button
            type="button"
            className="rwtd-bottom-toggle"
            aria-expanded={bottomOpen}
            onClick={() => setBottomOpen((value) => !value)}
          >
            {bottomOpen ? '收起' : '展开'}
          </button>
        </div>
        {bottomOpen ? (
          <div className="rwtd-bottom-content">
            {bottomTab === 'problems' ? (
              <ProblemsList state={state} dispatch={props.dispatch} />
            ) : (
              <PreviewPanel {...props} />
            )}
          </div>
        ) : null}
      </footer>
      <div className="rwtd-statusbar" role="status">
        <span>草稿画布 · 非权威</span>
        <span>{selected.name}</span>
        <span>{problemCount} 个问题</span>
        <span>{state.zoom}%</span>
      </div>
    </div>
  );
}
