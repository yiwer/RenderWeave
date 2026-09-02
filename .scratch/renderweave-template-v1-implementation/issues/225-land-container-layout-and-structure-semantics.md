# T225 — Arrange Group/Frame/Stack/Grid from the persisted structure

Type: task
Status: blocked
Blocked by: T224

## What to build

Adapt the prototype's pure definite-layout work to exact DesignDSL nodes and placements for Group, Frame, Stack and Grid.
The browser projection must derive positions from persisted structure/properties and remain non-authoritative; authored
geometry is never stored in a parallel `DraftBox[]`. Structure reparent/reorder and parent layout ownership determine
whether a drag persists or restores.

Support formal padding, margins, alignment, FIXED/FILL constraints and ordered Grid tracks. The compact editor syntax
`12, auto, 1*, 2*` is an authoring adapter over formal track objects. Canvas remains a fixed physical FREE board;
authors use an explicit visible root Frame/Stack/Grid for managed root content.

## Acceptance criteria

- [ ] Group/Frame FREE children persist legal drag geometry; Stack/Grid-owned children preview the drag and restore to
  deterministic arranged positions unless a structural reorder is committed.
- [ ] Stack and Grid produce stable authored-order layout for the supported definite subset, including padding,
  margins, FILL/min/max, gaps, alignment, fixed/auto/fraction tracks and nested definite containers.
- [ ] Illegal AUTO/FILL cycles and unsupported intrinsic HUG cases fail visibly without fabricating saved geometry.
- [ ] Structure order is the sole paint/Z order and selection/resize overlays remain in an editor-only layer.
- [ ] Representative Stack and Grid documents save, reload and reproduce the same local projection from DSL alone.

## Test plan

- Port/adapt pure layout vectors to formal typed fixtures; add command tests for reparent placement conversion.
- Component and Playwright checks for drag restore, tree order, padding/tracks and save/reload.
- Run affected `template` and `web` gates.

## Out of scope

- DOM-measured Text/Image intrinsic HUG, responsive-web layout, hidden Canvas layout state and Renderer parity claims.

## Resolution
