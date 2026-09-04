# T225 — Arrange Group/Frame/Stack/Grid from the persisted structure

Type: task
Status: done
Blocked by: T224 (done)

## What to build

Adapt the prototype's pure definite-layout work to exact DesignDSL nodes and placements for Group, Frame, Stack and Grid.
The browser projection must derive positions from persisted structure/properties and remain non-authoritative; authored
geometry is never stored in a parallel `DraftBox[]`. Structure reparent/reorder and parent layout ownership determine
whether a drag persists or restores.

Support formal padding, margins, alignment, FIXED/FILL constraints and ordered Grid tracks. The compact editor syntax
`12, auto, 1*, 2*` is an authoring adapter over formal track objects. Canvas remains a fixed physical FREE board;
authors use an explicit visible root Frame/Stack/Grid for managed root content.

## Acceptance criteria

- [x] Group/Frame FREE children persist legal drag geometry; Stack/Grid-owned children preview the drag and restore to
  deterministic arranged positions unless a structural reorder is committed.
- [x] Stack and Grid produce stable authored-order layout for the supported definite subset, including padding,
  margins, FILL/min/max, gaps, alignment, fixed/auto/fraction tracks and nested definite containers.
- [x] Illegal AUTO/FILL cycles and unsupported intrinsic HUG cases fail visibly without fabricating saved geometry.
- [x] Structure order is the sole paint/Z order and selection/resize overlays remain in an editor-only layer.
- [x] Representative Stack and Grid documents save, reload and reproduce the same local projection from DSL alone.

## Test plan

- Port/adapt pure layout vectors to formal typed fixtures; add command tests for reparent placement conversion.
- Component and Playwright checks for drag restore, tree order, padding/tracks and save/reload.
- Run affected `template` and `web` gates.

## Out of scope

- DOM-measured Text/Image intrinsic HUG, responsive-web layout, hidden Canvas layout state and Renderer parity claims.

## Resolution

- Replaced the prototype-only box model with a deterministic browser projection over persisted DesignDSL structure and
  properties. Group/Frame FREE placement, Stack/Grid ownership, authored-order paint, semantic reparent/reorder and
  editor-only overlays now share the production editor session without saving derived geometry.
- Added the definite Stack/Grid subset for padding, margins, gaps, alignment, FIXED/FILL constraints, min/max bounds,
  ordered fixed/auto/fraction tracks and nested containers. Water filling freezes every same-round bound hit in authored
  order; statically hidden subtrees do not consume intrinsic size, gaps or FILL allocation.
- Added visible failures for illegal sizing cycles and unsupported intrinsic cases, plus focused layout, command,
  component and accessibility coverage. New controls use the shared SelectField and centralized node-kind contracts.
- The real product-route journey authored Frame, Stack, Grid and Rect, saved, reloaded and reproduced the projection;
  the complete Template Editor roundtrip passed `4 / 4` in `.scratch/t225-final-roundtrip`.
- Verification: focused definite-layout tests `29 / 29`; focused editor component tests `77 / 77`; official `web` gate
  passed `60 files / 723 tests` with typecheck, lint and production build, evidence
  `.sdlc/evidence/20260903-204916-web`. The affected diff received final fixed-point Standards and Spec reviews with
  zero blockers. The broader `template`/hash replay was intentionally not repeated because this ticket's final delta is
  Web/E2E-only and the known Renderer manifest mismatch is outside T225.
