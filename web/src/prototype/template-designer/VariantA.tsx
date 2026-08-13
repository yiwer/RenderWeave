import { useState } from 'react';

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
  return (
    <div className="td-shell td-a">
      <TdChrome {...props} layoutName="A · 三栏工作台" />
      <ScenarioBar state={state} dispatch={props.dispatch} />
      <div className="td-a-body">
        <RailButtons state={state} dispatch={props.dispatch} />
        <aside className="td-a-panel" aria-label="资源面板">
          <LeftPanel state={state} dispatch={props.dispatch} />
        </aside>
        <main className="td-a-canvas" id="main-content">
          <Artboard state={state} dispatch={props.dispatch} />
        </main>
        <aside className="td-a-inspector" aria-label="检查器">
          <Inspector state={state} dispatch={props.dispatch} />
        </aside>
      </div>
      <footer className="td-a-problems" aria-label="问题与权威预览">
        <div className="td-a-bottom-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={bottomTab === 'problems'}
            className={bottomTab === 'problems' ? 'active' : ''}
            onClick={() => setBottomTab('problems')}
          >
            问题
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={bottomTab === 'preview'}
            className={bottomTab === 'preview' ? 'active' : ''}
            onClick={() => setBottomTab('preview')}
          >
            权威预览
          </button>
        </div>
        {bottomTab === 'problems' ? (
          <ProblemsList state={state} dispatch={props.dispatch} />
        ) : (
          <PreviewPanel {...props} />
        )}
      </footer>
    </div>
  );
}
