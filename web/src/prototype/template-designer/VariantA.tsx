import {
  AuthoringPanel,
  AuthoringRail,
  AuthoringToolbar,
  InspectorDock,
  PrototypeCanvas,
  StudioChrome,
  StudioStatusBar,
} from './AuthoringStudio';
import type { PartProps } from './SharedParts';

/** A — recommended: hbads-style component library + docked canvas + grouped properties. */
export function VariantA(props: PartProps) {
  return (
    <div className="rwtd rwtd-v2 rwtd-v2-a">
      <StudioChrome state={props.state} dispatch={props.dispatch} layoutName="A · Library Studio" />
      <AuthoringToolbar state={props.state} dispatch={props.dispatch} />
      <div className="rwtd-v2-a-body">
        <div className="rwtd-v2-a-library">
          <AuthoringRail state={props.state} dispatch={props.dispatch} />
          <AuthoringPanel state={props.state} dispatch={props.dispatch} />
        </div>
        <main className="rwtd-v2-a-canvas" id="main-content">
          <PrototypeCanvas state={props.state} dispatch={props.dispatch} />
        </main>
        <aside className="rwtd-v2-a-inspector">
          <InspectorDock state={props.state} dispatch={props.dispatch} />
        </aside>
      </div>
      <StudioStatusBar state={props.state} />
    </div>
  );
}
