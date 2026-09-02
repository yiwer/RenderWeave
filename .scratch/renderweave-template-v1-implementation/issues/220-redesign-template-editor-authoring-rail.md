# T220 — Prototype the Template authoring studio from hbads interaction evidence

Type: task
Status: done
Blocked by: T219 (done)

## Why this ticket changed

The user explicitly narrowed this round to a fast, throwaway prototype for continuous visual iteration. The production
Template Editor and RenderServer are out of scope until the interaction model is understood. The authenticated hbads
page cannot be captured from a clean browser because it redirects to Keycloak, so its checked-in source and the two
user-supplied authenticated screenshots are the visual and behavioral reference.

## What to build

Extend `/prototype/template-designer?variant=A|B|C` with three structurally different, in-memory authoring studios.
Borrow hbads' discoverability and interaction vocabulary while keeping the current RenderWeave DesignDSL names and
boundaries authoritative.

- An ordered rail: Elements, Containers, Images, Data sources, Structure. Nested-template creation is folded into the
  Containers catalog as one TemplateUse host instead of owning an independent rail destination.
- Data sources is an author-facing projection over bindable typed values rather than a new DesignDSL aggregate. It uses
  System, Custom and Derived views. System is selectable/read-only and projects the Template's exact StaticSchema field
  paths. Custom edits only CustomDefinition (fixed invocation scope plus PUBLIC/PRIVATE exposure). Derived groups
  ExpressionDefinition and MappingDefinition (explicit invocation/loopId domain and no exposure); Mapping keeps one
  explicit input, ordered first-match cases and required otherwise. Authored definitions live in the current in-memory
  DesignDSL `definitions[]` working draft, not a component-local or backend-maintained catalog. A loop item/index appears
  only when the selected authored node is lexically inside that concrete Repeat. Each source remains an independent card
  with focused details and authored edit actions; all source previews resolve to the closed ValueSource vocabulary.
- A searchable ten-item element catalog: Text, Image, Rect, Ellipse, Line, Polyline/Polygon, Path, Shape, QR Code,
  Barcode. Element cards support both click-to-auto-place and drag-to-artboard insertion; a drop uses the artboard-local
  pointer coordinate, creates a direct Canvas child and keeps the element's authored default size.
- Each catalog item inserts with an author-meaningful default value set and a distinct canvas projection: authored
  typography, image-fit placeholder, independent rectangle corners, ellipse inner ring, line caps/arrows, projected
  polyline/path/shape geometry, and recognizable local QR/barcode previews. QR/barcode previews remain illustrative
  browser projections rather than certified encoders.
- Container creation for free Group, Frame, one unified Stack, Grid, Repeat, Conditional and a TemplateUse host. Stack
  orientation is not represented by two catalog entries: its Layout `direction` property is the sole horizontal/vertical
  authoring control. Existing nodes are reorganized directly in the Structure tree; the UI does not expose
  selection-based Group, Wrap-in-Stack or Flatten commands.
- Select and Pan modes with V/H/Escape, real viewport panning and a local-only element-outline switch.
- A usable image catalog plus a TemplateUse host in Containers that inserts an unconfigured in-memory node.
- A grouped property inspector covering content, typography, geometry/transform, layout, appearance, data, behavior,
  composition and advanced settings for the currently selected element or container. Labels, controls and options are
  specific to the selected element rather than exposing irrelevant generic fields. Each property group can be collapsed
  and expanded without losing edits. Four-sided padding uses the fixed top/right/bottom/left order and is exposed only
  on Text, Frame, Stack and Grid, matching the RenderWeave node contract; the browser projection draws the resulting
  ContentBox inset as an authoring guide. The inspector keeps separate Properties and Binding
  tabs: bindable rows expose an inline entry in Properties, while Binding lists only bindings already configured on the
  selected node. New bindings start from their concrete property row rather than a second catalog of bindable fields.
  Editing happens in a lightweight dialog without leaving the designer; there is no standalone binding route, page or
  left-rail destination.
- The right inspector has one vertical scroll owner. Wheel input over group headers and focused numeric controls scrolls
  the inspector without mutating values or changing canvas zoom. Its task-first group order is Content, Typography,
  Layout/Constraints, Position/Size, Appearance, Data, Behavior/Visibility, Composition and Advanced; controls within
  each group progress from primary authoring choices to constraints and fallback policy. The inspector action strip omits
  the redundant selected-count label.
- A nested Structure tree with parent/child connectors, collapse state and drag affordances. Dropping on a row's upper
  or lower edge reorders before/after it; dropping in a container's center reparents into it; illegal cycles are rejected.
  Double-clicking a non-Canvas element or container starts inline rename, with F2 as the keyboard equivalent, Enter to
  commit and Escape to cancel. Branch trunks and elbows make nesting visually explicit. Selected leaves and containers
  share the same pale-green fill and external yellow outline; a short interruptible outline transition identifies the
  newly selected row and is disabled by reduced-motion preferences.
- Structure connectors are row-bounded one-pixel trunks and elbows rather than borders spanning an entire nested
  subtree. Each level advances by one compact indentation step; an ancestor trunk is drawn only while that ancestor
  has a following sibling, and the final sibling terminates at its row midpoint.
- Canvas is a selectable configurable DesignRoot in the prototype. Its inspector exposes physical width/height,
  FREE/STACK/GRID root layout, Stack direction/alignment/gap or Grid columns/gaps, and four-sided root padding. The
  artboard, header, tree summary and content-area guide update immediately. A managed Canvas layout owns the transient
  drag/restore behavior of direct children. Root layout and padding are an explicitly non-authoritative T220 product
  delta for interaction validation; this ticket does not change the frozen production Canvas NodeContract.
- Direct canvas transforms in Select mode: pointer-drag moves the primary node and eight edge/corner handles resize it.
  Geometry is clamped to the physical artboard and immediately reflected in the grouped property inspector. Moving a
  container moves the positioned descendants represented by this prototype fixture.
- Parent layout owns drag persistence: children of Group/Frame and a FREE Canvas keep their dragged position; direct
  children of Stack/Grid/Repeat or a managed Canvas follow the pointer only as a transient subtree preview, then animate
  back to the arranged position on release without changing authored geometry. The selected node identifies the owning
  layout and restore behavior.
- Stack has a browser-only, pure derived definite-layout projection. It calculates in authored tree order; subtracts
  inward stroke and top/right/bottom/left padding to obtain ContentBox; applies fixed gap, horizontal/vertical direction,
  START/CENTER/END/SPACE_BETWEEN main alignment and START/CENTER/END/STRETCH cross alignment; and exposes positive free
  space versus overflow. Direct children support signed top/right/bottom/left margins, per-child cross-axis alignment,
  axis FIXED/FILL modes and positive main-axis fillWeight. Multiple main-axis FILL children consume the nonnegative
  remainder through authored-order weighted binary64 allocation; active min/max bounds freeze and the final active child
  receives the exact remainder. Cross-axis FILL uses the signed-margin interval, positive-zero and min/max clamp before
  inherited alignment. Nested Stack receives its effective outer box before arranging descendants. The reducer retains
  authored geometry, while canvas and managed-child read-only geometry use the same derived result. Four bounded in-memory
  scenes make vertical, horizontal-center, horizontal-space-between and bounded weighted-FILL behavior inspectable. HUG
  remains deliberately deferred because it requires intrinsic text/image/container measurement.
- Grid uses the same browser-only derived projection and keeps authored geometry stable. Its row/column authoring adapter
  follows design-layout-draw's ordered GridLength list: each track is independently addable/removable and accepts `12`
  (fixed 12mm), `auto` (content-sized), or positive `1*` / `2*` weights over the remaining space. Commas are the canonical
  compact serialization; the prior `12mm` / `1fr` forms remain input-compatible and normalize on blur. A bare number is
  always one FIXED track, never a track count. The solver is columns-first and stages FIXED, AUTO then FRACTION with stable
  last-remainder allocation. In the current no-HUG subset, FIXED child size plus signed margins contributes to AUTO;
  stable multi-track deficits are ordered by span/start/tree order and split only across covered AUTO tracks. Child row,
  column and spans are zero-based and explicit; cells include internal gaps, overlap follows tree paint order, and there is
  no implicit placement or auto-flow. Independent horizontal/vertical alignment applies to FIXED axes; FILL uses the
  signed-margin interval then min/max clamp. Invalid track text, out-of-range cells and AUTO/FILL cycles fail closed in the
  trace without guessing new boxes. Canvas-as-Grid and nested Stack/Grid receive their effective outer boxes before
  descendants arrange. Three resettable in-memory scenes cover fractional cards, stable AUTO span contribution and
  alignment/FILL constraints; full intrinsic HUG measurement remains deferred.
- Structure rows visually distinguish the root artboard, containers and leaf elements. Containers use a tinted framed
  row, square branch marker, emphasized kind tile and an explicit container badge; the root artboard uses its own dark
  treatment.
- Variant B canvas navigation: hold Space for temporary Pan and restore the prior tool on release; any drag that starts
  outside the white artboard pans while Select remains active; the wheel zooms around the cursor; the viewport outside
  the artboard uses a low-contrast woven texture.
- Canvas content consumes the same zoom factor as node geometry. Text uses its authored point size, while resource and
  container labels, content icons, gaps and content padding scale proportionally. Selection handles and geometry labels
  remain screen-space authoring affordances so they stay operable at low zoom.
- Ctrl+A (or platform-equivalent Meta+A) selects every non-Canvas authored layer. Editable inputs retain their native
  select-all behavior and never trigger the document command. Newly inserted Text, Frame, Stack and Grid nodes start
  all four padding leaves at `0`; authored fixture values already present in the loaded document are preserved.
- Text authoring projects the single supported plain-text Run as one scalar `文本值`; author-facing property and Binding
  surfaces do not expose the `runs[0]` storage path. Every Text exposes a real `字体资产` control that lists only ACTIVE
  FONT assets. Choosing an asset updates the in-memory `fontRef` and the browser canvas typography immediately; the
  browser font-family mapping remains a non-authoritative prototype projection rather than FONT byte loading.
- Canvas paint order follows authored `children[]` preorder without container/element z-index bands. Structure exposes
  bring-to-front/forward/backward/send-to-back as stable sibling-array reorder operations. Selection outlines, labels and
  resize handles live in one independent editor overlay and never participate in authored paint order.
- Canvas nodes and Structure rows share one custom right-click menu. Right-click first selects the target (while retaining
  an existing multi-selection), then offers locate, lock/unlock, show/hide, delete and all four sibling Z-order operations.
  Disabled states are derived from the same `children[]` capabilities used by the Structure toolbar; no browser-native
  context menu or parallel Z-index property is introduced.
- Group and Frame have separate live absolute-layout projections. Group is appearance-free and HUG-only: its direct-child
  local-box union derives its LayoutBox and normalizes the local minimum to the Group origin. Frame retains a fixed
  LayoutBox; inward stroke plus four-sided padding derives ContentBox, and direct children retain local absolute offsets
  from that ContentBox. Two resettable browser-memory demos expose both behaviors.
- Repeat creation is intentionally terse: drag or click one Repeat container, select it, then choose one list-typed
  Template field, CustomDefinition or Computed Definition. Source choice statically proves the item StaticSchema. The
  authored `children[]` item subtree is primary; choosing a compatible exact-schema TemplateUse is an optional shortcut,
  never a mandatory second step. Packing and preview controls remain available without staged question cards. Scalar
  list items map to their system basic schema, reference-list items retain their exact StaticSchemaRef, and source changes
  preserve authored content while surfacing an explicit repair state. Preview occurrences are virtual and never become
  nodes, boxes or Structure rows. Empty arrays, typed ABSENT with EMPTY, and typed ABSENT with ERROR remain distinct; this
  prototype does not add sorting, filtering, paging, item keys or per-occurrence layout overrides.
- Standalone nested-template creation is `property → compatible template`: first select a non-array root property, then
  select a Template whose input StaticSchema matches it. A directly referenced StaticSchema such as `/brand → brand@v1`
  is the authoritative case. Primitive properties are represented only as an explicit browser proposal for immutable
  value-only system schemas plus deterministic `scalar → { value }` adaptation, with no loop `index`; this proposal does
  not silently change the current ContextSelector or production StaticSchema authority. Array properties remain Repeat
  sources and never appear in the standalone property picker. The author-facing "container" remains a DesignDSL
  TemplateUse leaf: its selected Template expansion supplies visual content and authors cannot drag children into it.
- Conditional uses a staged browser-only authoring projection: first prove a literal or context ValueSource as boolean and
  choose `FALSE|ERROR` handling for typed ABSENT; second author the single non-empty true branch in `children[]`; third
  inspect the runtime expansion order. TRUE lowers to an appearance-free, padding-free Frame whose children use ABSOLUTE
  local placement. FALSE, `render:false` and ABSENT→FALSE remove the host and entire branch before child Binding, layout,
  Asset resolution and output; ABSENT→ERROR stops Evaluation without partial output, while a missing required context field
  remains an invalid RootDocument sample rather than a false condition. Runtime-pruned nodes do not occupy Stack/Grid
  layout, so following siblings reflow, but authored Structure rows remain visible with textual suppression state and the
  canvas keeps a non-output editor ghost for selection. There is no else branch or new lexical frame.

All state is browser memory only and disappears on refresh. Do not call Template APIs or RenderServer.

## Acceptance criteria

- [x] Variant A closely follows the supplied hbads rail/library density; Variant B is the current review surface.
- [x] Variants B and C are structurally different alternatives, not cosmetic recolors, and share the same live state.
- [x] The rail contains Elements, Containers, Images, Data sources and Structure in that order; Containers owns the
  nested-template entry.
- [x] Data sources has System / Custom / Derived nested views rather than one mixed list. System visibly binds
  `campaign-card@v3`, exposes its nine root fields and nested exact schema boundaries, remains selectable/viewable but
  has no edit/delete affordances. Each source is an independent card; its dialog exposes name, type, identity, scope and
  constraints without emphasizing a separate invocation-context card. Authored sources add View and Edit and mutate the
  same in-memory DesignDSL `definitions[]`; no backend source catalog is introduced. Custom alone exposes PUBLIC/PRIVATE
  and is fixed to invocation scope; Expression/Mapping expose an exact concrete domain without exposure. Mapping editing
  preserves a single typed input, reorderable first-match cases and required otherwise. The UI label does not introduce
  a persisted `DataSource` domain object or confuse StaticSchema paths, Definitions and ValueSource.
- [x] Search, click insertion, element and container drag-to-artboard insertion, image insert/replace and TemplateUse-host
  insertion work; drag insertion follows the artboard-local drop coordinate and creates a direct Canvas child.
- [x] A standalone TemplateUse host starts unconfigured, lists no array properties, clears its target when the property
  changes, and filters target Templates by exact context compatibility. Direct StaticSchema references are visibly
  distinct from proposed no-index primitive adapters. A TemplateUse already inside Repeat keeps the complete loop item
  context and sends array/template editing back to the parent Repeat workflow.
- [x] The container catalog exposes one Stack entry rather than horizontal/vertical variants; changing its Layout
  direction between horizontal and vertical updates the layout-owned interaction behavior.
- [x] All ten element kinds have visibly distinct canvas projections, useful default authored values and inspector groups
  containing only the properties represented by that element kind; editable paint values update the canvas immediately.
- [x] Structure-tree drag and drop supports before/after sibling ordering and moving into containers, with cycle guards.
- [x] Right-clicking a canvas node or Structure row selects that node and opens the same custom menu with locate,
  lock/unlock, show/hide, delete and four Z-order operations. Z-order actions only reorder sibling `children[]`, update
  canvas paint order immediately and expose correct disabled states at each boundary.
- [x] Structure-tree element and container rows support double-click inline rename plus F2/Enter/Escape keyboard
  operation. Branch connectors and indentation remain legible at nested depths. Selected leaves and containers share
  the same pale-green fill, external yellow outline and reduced-motion-safe selection transition.
- [x] Structure connectors use compact 1px row-bounded elbows, terminate at the final sibling and do not draw a
  subtree-height border rail; each nesting level adds one consistent indentation step.
- [x] Selecting Canvas exposes editable physical width/height, FREE/STACK/GRID root layout and four-sided padding.
  Edits immediately update the artboard dimensions, layout/content guide, header and Structure summary; managed Canvas
  layout constrains direct-child drag persistence. The UI identifies this as a browser-only prototype delta.
- [x] The primary selected canvas node can be dragged and resized from eight handles without panning the viewport;
  inspector X/Y/width/height values update live and geometry stays inside the artboard.
- [x] A Stack/Grid/Repeat or managed-Canvas child visibly follows the pointer but restores with its represented subtree
  on release, while a Frame/Group/FREE-Canvas child keeps the new position; constrained previews never update authored
  geometry or inspector X/Y.
- [x] The definite Stack prototype lays out horizontal and vertical children from the derived ContentBox in tree order.
  Stroke, four-sided padding, signed child margins, gap, main/cross alignment, per-child alignSelf, cross-axis FILL,
  nested stretched Stack, positive free space and overflow produce exact millimetre positions. Main-axis FILL uses
  positive fillWeight, authored-order binary64 remainder and iterative min/max freezing. Inspector edits and Structure
  reorder re-project immediately without persisting derived geometry. The Container panel exposes four resettable
  browser-memory demos and the inspector shows ContentBox, fill budget, allocation, effective gaps, margins and child boxes.
- [x] The definite Grid prototype solves explicit FIXED/AUTO/FRACTION rows and columns in columns-first order from the
  derived ContentBox. Track gaps, stable AUTO span deficits, FRACTION remainder, zero-based row/column spans, overlap order,
  signed margins, independent alignment and bounded axis FILL produce exact millimetre boxes. Inspector edits and Structure
  reorder re-project immediately without persisting derived geometry. Invalid tracks, ranges and AUTO/FILL cycles are
  visible and fail closed. The Container panel exposes three resettable Grid demos and the inspector shows solved tracks,
  cell/child boxes, AUTO constraint count, free space and overflow. Row and column definitions are ordered per-track editors
  with add/remove controls and the author shorthand `12`, `auto`, `1*` / `2*`; plain numbers are fixed millimetres.
- [x] A Repeat can be dragged from Containers, selected, and made ready by choosing a list property plus a compatible
  item template from two compact controls. The inspector does not show staged problem/question cards; item/instance
  packing and preview remain collapsed under an optional advanced section. Template selection leaves exactly one authored
  TemplateUse child while preview occurrences remain virtual.
- [x] Conditional exposes the ordered boolean-source, true-branch and Evaluation stages without duplicating `condition`
  or `absentPolicy` as generic property rows. Resettable TRUE/FALSE/ABSENT demos distinguish included, pre-layout-pruned,
  ABSENT→FALSE, ABSENT→ERROR and invalid-required-input states. TRUE paints the authored branch through an appearance-free
  Frame; pruned states remove every descendant and cause following Stack/Grid siblings to reflow while Structure preserves
  authored rows with explicit “运行时不求值” text and the canvas ghost is identified as editor-only.
- [x] Root artboard, container rows and ordinary element rows have visibly different Structure-tree treatments.
- [x] V selects, H pans, Escape exits Pan; dragging in Pan moves only the viewport.
- [x] In Variant B, Space temporarily overrides to Pan, outside-artboard drags always pan, and wheel zoom preserves the
  cursor anchor without turning an artboard Select drag into a pan.
- [x] Between 50% and 175%, canvas text computed size changes by the same ratio as its owning node geometry; authored
  font-size differences remain visible, while screen-space selection affordances do not become unusably small.
- [x] Element outlines toggle without changing the in-memory authored document or dirty state.
- [x] Ctrl+A selects all non-Canvas layers while inputs and rename fields retain native Ctrl+A behavior. New Text,
  Frame, Stack and Grid nodes expose top/right/bottom/left padding with all four values initially `0`.
- [x] Every Text exposes one editable `文本值` without an author-visible `runs[0]` path, plus a controlled `字体资产`
  picker containing only ACTIVE FONT assets. Text and font edits update the canvas immediately, and the Binding dialog
  uses the same flattened authoring identity while the in-memory adapter retains the frozen Text Run contract.
- [x] The property inspector is grouped by author intent, keeps separate Properties and Binding tabs, exposes inline
  binding actions on bindable property rows, and lets editable controls update the selected node. The Binding tab
  lists only the selected node's configured bindings and provides an empty state when there are none; it does not repeat
  a catalog of all bindable properties. No standalone binding route, full-page surface or left-rail destination is
  introduced. Property groups independently collapse/expand. Text, Frame, Stack and Grid expose bindable top/right/bottom/left
  padding controls and update their canvas ContentBox projection immediately; node kinds outside that contract do not
  receive generic padding fields.
- [x] The inspector uses one vertical scrolling surface. Wheel input over group headings and focused numeric fields moves
  that surface promptly, never changes a numeric value accidentally and never reaches canvas zoom. Groups and their
  controls follow the documented task-first order, and the right action strip does not repeat the selection count.
- [x] No Group, Wrap-in-Stack or Flatten action remains in the visible prototype UI or keyboard shortcuts.
- [x] No RenderServer or production Template Editor code is changed in this round.
- [x] Web build/typecheck and real-browser visual/interaction QA pass for the prototype route.

## Source evidence

- `D:/Yiwer/code/hbads-design-v2/web/src/app/design/templates/[id]/TemplateEditorPage.tsx`
- `D:/Yiwer/code/hbads-design-v2/web/src/features/design-prototype/editor/element-contract.ts`
- `D:/Yiwer/code/hbads-design-v2/web/src/features/design-prototype/editor/layerCommands.ts`
- `D:/Yiwer/code/hbads-design-v2/web/src/features/design-prototype/editor/LayerTree.tsx`
- `D:/Yiwer/code/hbads-design-v2/web/src/features/design-prototype/editor/property-inspector/`
- `D:/Yiwer/code/design-layout-draw/ts-layout-wpf/src/shared/mixins/LayoutMixin.ts`
- `D:/Yiwer/code/design-layout-draw/ts-layout-wpf/src/v3/layouts/canvas/CanvasLayout.ts`
- `D:/Yiwer/code/design-layout-draw/ts-layout-wpf/src/v3/layouts/stack/StackArrange.ts`
- `D:/Yiwer/code/design-layout-draw/ts-layout-wpf/src/v3/layouts/grid/GridArrange.ts`
- `D:/Yiwer/code/design-layout-draw/ts-layout-wpf/src/v3/layouts/grid/GridDefinitions.ts`
- `D:/Yiwer/code/design-layout-draw/svg-edit-web/src/views/Editor/layouts/panel/rightPanel/attrs/gridAttr.vue`
- `D:/Yiwer/code/design-layout-draw/svg-edit-web/src/views/Editor/layouts/panel/rightPanel/attrs/textAttr.vue`
- `D:/Yiwer/code/design-layout-draw/canvaskit/src/drawCore/V2/renderers/WpfText/layout/layoutBox.ts`
- `D:/Yiwer/code/design-layout-draw/canvaskit/src/editor.ts`
- `CONTEXT.md` (`LayoutBox` / `ContentBox` domain definitions)
- `renderweave-rendering/src/main/resources/cn/hbads/renderweave/rendering/render-node-contract-v1.json`
- `renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/NodeContractCatalog.java`
- `renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/BindingPolicyCatalog.java`
- `renderer/crates/layout/src/lib.rs` (`measure_and_allocate_stack_children` / `stack_main_fill_allocations`)
- User screenshots `Snipaste_2026-09-01_14-24-53.png` and `Snipaste_2026-09-01_14-27-09.png`

## Out of scope

- Persistence, autosave, undo/redo history implementation, API contracts, migration or permissions.
- RenderServer, authoritative preview, renderer profiles or renderer certification.
- Production-ready drag/resize/rotate, asset upload, rich text editing, binding evaluation or Template publication.
- Stack HUG/intrinsic text, image and container measurement. The current prototype must not infer HUG from browser DOM
  scroll or font metrics.

## Resolution

Variant A is the selected interaction direction for production landing. T220 now provides one browser-memory authoring
studio with the approved Elements, Containers, Images, Data Sources and Structure rail; Select/Pan interaction; direct
canvas move/resize; tree rename/reparent/reorder and sibling Z-order; grouped Properties/Binding inspection; and compact
context menus. All mutations remain inside the shared prototype reducer and disappear on refresh.

The final projection covers the admitted visual vocabulary and the Group, Frame, Stack, Grid, Repeat, Conditional and
TemplateUse authoring concepts. Stack/Grid use pure deterministic definite-layout functions, authored tree order and
formal-looking placement controls without claiming Renderer authority. Repeat owns one authored item subtree with
virtual occurrences; Conditional retains one authored true branch and prunes its local projection before layout;
TemplateUse distinguishes exact reference contexts from the unresolved primitive adapter proposal. Text exposes a
single author-facing value and ACTIVE font picker while the prototype adapter retains the Text Run vocabulary.

The Data Sources panel is explicitly an authoring projection, not a domain aggregate: System cards project read-only
StaticSchema fields and constraints; Custom, Expression and Mapping cards mutate the in-memory DesignDSL
`definitions[]`; loop values appear only in their concrete lexical Repeat. Reference-typed fields remain compact in the
main tree and expose nested structure only in the read-only detail dialog. No Template API, backend DataSource, production
DesignDSL contract, Template Editor, Renderer or RenderServer was changed.

Closure verification:

- On Node 24.12.0, `npm exec -- vitest run src/prototype/template-designer` passed 5 files / 47 tests and targeted ESLint
  passed for the prototype plus its browser spec. The final binding fix has dedicated coverage for exact
  `(nodeKind, property identity)` typing, enum fail-closed behavior, boolean sources and Repeat lexical scope.
- The exact candidate `94a6eaef` passed the isolated `web` gate on pinned Node 24.19.0: OpenAPI generation, typecheck,
  repository-wide Web lint, 40 files / 327 tests and production build. Evidence is under the isolated worktree at
  `.sdlc/evidence/20260902-214552-web`.
- `npm exec -- playwright test e2e/template-designer-canvas-interactions.spec.ts --project=chromium-canary` passed 3/3,
  covering wheel zoom, Ellipse resize containment and reference-schema detail. The broader isolated E2E run passed 27,
  skipped 2 and failed 2 pre-existing assertions; the same Schema Studio contrast assertion and old Template Rect default
  assertion failed unchanged at parent `cb2be4a4`, while all three T220 browser tests passed.
- Fixed-point Standards and Spec reviews found no remaining blocker after lifecycle/readiness were separated and binding
  choices were closed over exact target type plus lexical scope. `git diff --check` passed over the ticket delta.

The dirty main worktree's direct typecheck still reports only the separately authored Inference `rejectionEnvelope` SDK
drift; isolated candidate verification proves T220 without altering or masking that work.

Production landing continues in T221–T227. Canvas layout/padding remains a visibly labelled prototype-only delta;
intrinsic Text/Image/container HUG, the primitive TemplateUse adapter, authoritative QR/Barcode output, arbitrary SVG and
RenderServer work remain deferred.
