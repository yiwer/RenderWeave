# T222 — Edit a real Canvas/Rect/Frame/Stack through the prototype interaction shell

Type: task
Status: ready-for-agent
Blocked by: T221 (done)

## What to build

Promote the selected Variant A interaction language into the production `/templates/{templateId}` editor while
retaining the existing open/save/reconciliation/recovery/import/preview spine. Introduce one deep EditorSession command
seam over the exact DesignDSL working copy for selection-independent semantic operations: insert, update, delete,
rename, reparent, sibling reorder, placement conversion and move/resize. Every accepted command participates in the
existing undo/redo history and canonical dirty comparison.

Deliver the first real slice with Canvas, Rect, Frame and Stack. Connect the ordered rail, Structure tree, grouped
inspector, Select/Pan modes, Space temporary pan, pointer-centred wheel zoom, independent selection overlay, context
menu and `children[]`-based Z-order to that command seam instead of copying the prototype `DesignerState`.

## Acceptance criteria

- [ ] The production route uses the new shell while preserving loading, save, conflict, recovery, import and preview
  states from the existing editor.
- [ ] Canvas, Rect, Frame and Stack can be inserted/selected/renamed/deleted; Structure drag supports legal before,
  into and after moves and converts placement atomically or rejects the command without partial mutation.
- [ ] V/H/Escape, held Space pan, wheel zoom, Ctrl+A, pointer move/resize, right-click actions and four sibling Z-order
  operations work against the real working copy; selection chrome never changes paint order.
- [ ] Undo/redo, explicit save and browser reload reproduce the authored structure and geometry through real Template
  APIs.

## Test plan

- Command-model tests cover valid and rejected tree/placement transitions, history and canonical dirty state.
- Component tests cover shortcuts, overlay behavior and existing product-state preservation.
- Playwright exercises the real product route and save/reload; run the affected `web` and `template` gates.

## Out of scope

- Remaining visual nodes, StaticSchema/definitions/bindings, Grid/Repeat/Conditional/TemplateUse and RenderServer.
- Copying prototype-only scenario/save/preview state or its `DraftBox[]` as persisted facts.

## Resolution
