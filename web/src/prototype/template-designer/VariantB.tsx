import { Layers3, PanelLeftClose, PanelRightClose } from 'lucide-react';
import { useState } from 'react';

import {
  AuthoringPanel,
  AuthoringRail,
  AuthoringToolbar,
  InspectorDock,
  PrototypeCanvas,
  StudioChrome,
  StudioStatusBar,
} from './AuthoringStudio';
import { findNode } from './model';
import type { PartProps } from './SharedParts';

/** B — canvas-first: palette and inspector float above a maximized artboard. */
export function VariantB(props: PartProps) {
  const [showPalette, setShowPalette] = useState(true);
  const [showInspector, setShowInspector] = useState(true);
  const selected = findNode(props.state.tree, props.state.selectedNodeId) ?? props.state.tree;
  return (
    <div className="rwtd rwtd-v2 rwtd-v2-b">
      <StudioChrome state={props.state} dispatch={props.dispatch} layoutName="B · Canvas Focus" />
      <AuthoringToolbar state={props.state} dispatch={props.dispatch} compact interactionHint="空格临时平移 · 画板外拖动 · 滚轮缩放" />
      <main className="rwtd-v2-b-stage" id="main-content">
        <PrototypeCanvas state={props.state} dispatch={props.dispatch} backgroundPan wheelZoom />
        {showPalette ? (
          <aside className="rwtd-v2-b-palette">
            <AuthoringRail state={props.state} dispatch={props.dispatch} />
            <AuthoringPanel state={props.state} dispatch={props.dispatch} />
          </aside>
        ) : null}
        {showInspector ? <aside className="rwtd-v2-b-inspector"><InspectorDock state={props.state} dispatch={props.dispatch} compactActions /></aside> : null}
        <div className="rwtd-v2-b-selection"><Layers3 size={13} /><strong>{selected.name}</strong><span>{props.state.selectedNodeIds.length} 个已选</span></div>
        <div className="rwtd-v2-b-dock" role="toolbar" aria-label="沉浸画布面板">
          <button type="button" className={showPalette ? 'active' : ''} aria-pressed={showPalette} onClick={() => setShowPalette((value) => !value)}><PanelLeftClose size={14} />组件</button>
          <button type="button" className={showInspector ? 'active' : ''} aria-pressed={showInspector} onClick={() => setShowInspector((value) => !value)}><PanelRightClose size={14} />属性</button>
        </div>
      </main>
      <StudioStatusBar state={props.state} />
    </div>
  );
}
