# T220 Template Designer prototype — design QA

Date: 2026-09-01
Route: `/prototype/template-designer?variant=A|B|C`
Primary review surface: Variant B · Canvas Studio

## Source evidence

- The requested live hbads URL is reachable, but a clean Chrome session redirects to Keycloak. No user profile,
  credential or authenticated browser state was read.
- Visual truth for the authenticated editor came from the two user-supplied screenshots:
  `Snipaste_2026-09-01_14-24-53.png` and `Snipaste_2026-09-01_14-27-09.png`.
- Behavioral truth came from the checked-in hbads source: `TemplateEditorPage.tsx`, `element-contract.ts`,
  `layerCommands.ts` and `editor/property-inspector/`.

## Same-input comparisons

- Elements: `.scratch/shots/t220-compare-elements.png`
- Select/Pan/outlines: `.scratch/shots/t220-compare-tools.png`
- Full Variant A: `.scratch/shots/t220-variant-a.png`
- Full Variant B: `.scratch/shots/t220-variant-b.png`
- Variant B temporary-Pan / outside-artboard texture iteration: `.scratch/shots/t220-variant-b-interactions.png`
- Full Variant C: `.scratch/shots/t220-variant-c.png`
- 1280 × 800 fit check at default 175%: `.scratch/shots/t220-variant-a-1280-175.png`
- Post-interaction state: `.scratch/shots/t220-interaction-final.png`
- Latest Structure tree and binding-free inspector: `.scratch/shots/t220-layer-tree.png`
- Canvas move/resize handles and distinct container rows: `.scratch/shots/t220-canvas-transform-tree-containers.png`

## Visual findings

- Variant A keeps the reference's narrow dark icon rail, cream component library, compact metadata, two-column cards,
  ten primitive elements and Adapter distinction. The visible rail intentionally uses the user-approved order:
  Elements, Containers, Images, Nested templates, Structure.
- Select/Pan moved to the top toolbar as requested. It retains the reference's current-state treatment, V/H keycaps,
  Escape hint and local-only outline switch.
- The RenderWeave inspector keeps the stronger existing grouped-property design instead of copying hbads labels
  blindly. Geometry, content, typography, layout, appearance, data, behavior and composition remain discoverable;
  the Binding tab and per-property binding actions have been removed for this prototype round.
- Variant B is canvas-first with floating palette/inspector. Variant C is structure-first with a persistent tree and a
  horizontal component tray. They are structurally different, not theme variants.
- Variant B now distinguishes the white artboard from a low-contrast woven viewport. The toolbar and canvas footer
  expose Space temporary Pan, outside-artboard drag and wheel zoom without adding another floating control.
- Structure now follows the useful hbads layer-tree vocabulary: visible parent/child connectors, deterministic branch
  dots, expand/collapse controls, node type and child count, and a drag handle. Upper/lower row edges show insertion
  lines for sibling ordering; a container center shows the nested-drop target.
- Group and Wrap-in-Stack actions are no longer present. Users create an empty container and drag nodes into it in the
  Structure tree, keeping one direct manipulation model for hierarchy changes.
- The primary canvas selection now uses a familiar blue transform frame with eight edge/corner handles, an element-name
  label and a live millimetre geometry readout. The selected node is draggable in Select mode; the same gesture never
  pans the viewport.
- Structure rows now have three visual tiers: a dark root artboard, tinted framed container rows with square markers and
  `容器` badges, and quieter leaf rows. Empty containers retain a dashed outline so their drop-target role stays visible.
- No cropped primary controls, broken spacing, unreadable contrast or overlapping chrome were observed at 1600 × 1000.
  At 1280 × 800 the default 175% artboard fits between the library and inspector.

## Browser interaction QA

Chrome DevTools Protocol was used against the real Vite page; Playwright and the user's Chrome profile were not used.

- Rail order: Elements → Containers → Images → Nested templates → Structure.
- Element insertion: canvas node count 10 → 11 after Rect.
- Image catalog: `logo-badge` inserted as `图片 19`.
- Nested template catalog: `标签胶囊调用 20` inserted.
- Grouped property edit: X position updated the selected canvas box.
- Element outlines: visible projection changed; document status did not.
- Pan: H entered Pan and a real pointer drag changed viewport transform from `(0, 0)` to `(82, 48)`.
- Escape and V returned to Select.
- Variant B Space keydown changed Select to temporary Pan and keyup restored Select; the toolbar, canvas metadata and
  status bar reflected the temporary state.
- In Select mode an outside-artboard drag changed the viewport transform from `(0, 0)` to `(64, 38)`; the same drag
  beginning inside the artboard kept the transform at `(0, 0)`.
- One upward wheel step changed zoom from 175% to 200%; normalized cursor-anchor drift was `0.000162`.
- Variant B computed style contains both woven texture gradients.
- Latest control audit: inspector tabs `0`, per-property Bind buttons `0`, Group/Wrap-in-Stack buttons `0`.
- Structure reorder: dragging `dateLine` to the upper edge of `titleText` moved it before `titleText`; the visible drop
  placement was `before`.
- Structure reparent: dragging `brandLogo` to the center of `priceBand` changed its ARIA tree level from 3 to 4; the
  visible drop placement was `into`.
- Cycle guard: dragging parent `priceBand` relative to its descendant `brandLogo` was rejected without mutating the
  tree. The rendered tree exposes five connector branches and fifteen drag handles in the fixture.
- Direct canvas move: a real pointer drag moved `titleText` from `(4, 4)` to `(11.26, 8.23)` mm. The canvas box and the
  inspector changed together while the viewport remained `translate(0px, 0px)`.
- Direct canvas resize: dragging the southeast handle changed `titleText` from `58 × 6` to `64.95 × 10.54` mm. Eight
  resize handles remained available after the reducer update.
- Container styling audit found one root row, four container rows, eleven element rows and five root/container badges;
  computed root, container and leaf backgrounds are distinct.
- Browser console: 0 errors or unhandled exceptions.

The affected Web gate passed typecheck, lint, all 35 Vitest files / 281 tests, and the production build. Latest evidence:
`.sdlc/evidence/20260901-164026-web`.

## Scope guard

All prototype mutations are reducer-only browser memory. The route does not call Template APIs or RenderServer.

final result: passed
