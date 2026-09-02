import {
  AuthoringPanel,
  AuthoringTabStrip,
  AuthoringToolbar,
  InspectorDock,
  PrototypeCanvas,
  StructureActions,
  StructureTree,
  StudioChrome,
  StudioStatusBar,
} from './AuthoringStudio';
import type { PartProps } from './SharedParts';

/** C — structure-first: canvas/palette on the left, persistent hierarchy and properties on the right. */
export function VariantC(props: PartProps) {
  return (
    <div className="rwtd rwtd-v2 rwtd-v2-c">
      <StudioChrome state={props.state} dispatch={props.dispatch} layoutName="C · Structure Bench" />
      <AuthoringToolbar state={props.state} dispatch={props.dispatch} compact />
      <div className="rwtd-v2-c-body">
        <main className="rwtd-v2-c-workspace" id="main-content">
          <section className="rwtd-v2-c-canvas"><PrototypeCanvas state={props.state} dispatch={props.dispatch} /></section>
          <section className="rwtd-v2-c-library"><AuthoringTabStrip state={props.state} dispatch={props.dispatch} /><AuthoringPanel state={props.state} dispatch={props.dispatch} /></section>
        </main>
        <aside className="rwtd-v2-c-structure" aria-label="持久结构树">
          <header><span>DOCUMENT TREE</span><strong>结构编排</strong></header>
          <StructureActions state={props.state} dispatch={props.dispatch} />
          <div><StructureTree state={props.state} dispatch={props.dispatch} /></div>
        </aside>
        <aside className="rwtd-v2-c-inspector"><InspectorDock state={props.state} dispatch={props.dispatch} compactActions /></aside>
      </div>
      <StudioStatusBar state={props.state} />
    </div>
  );
}
