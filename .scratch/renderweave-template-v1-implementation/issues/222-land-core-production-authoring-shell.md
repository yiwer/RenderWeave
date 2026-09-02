# T222 — Edit a real Canvas/Rect/Frame/Stack through the prototype interaction shell

Type: task
Status: done
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

- [x] The production route uses the new shell while preserving loading, save, conflict, recovery, import and preview
  states from the existing editor.
- [x] Canvas, Rect, Frame and Stack can be inserted/selected/renamed/deleted; Structure drag supports legal before,
  into and after moves and converts placement atomically or rejects the command without partial mutation.
- [x] V/H/Escape, held Space pan, wheel zoom, Ctrl+A, pointer move/resize, right-click actions and four sibling Z-order
  operations work against the real working copy; selection chrome never changes paint order.
- [x] Undo/redo, explicit save and browser reload reproduce the authored structure and geometry through real Template
  APIs.

## Test plan

- Command-model tests cover valid and rejected tree/placement transitions, history and canonical dirty state.
- Component tests cover shortcuts, overlay behavior and existing product-state preservation.
- Playwright exercises the real product route and save/reload; run the affected `web` and `template` gates.

## Out of scope

- Remaining visual nodes, StaticSchema/definitions/bindings, Grid/Repeat/Conditional/TemplateUse and RenderServer.
- Copying prototype-only scenario/save/preview state or its `DraftBox[]` as persisted facts.

## Resolution

The production Template route now uses the selected Variant A shell over the exact canonical DesignDSL working copy.
One semantic command seam owns core Canvas/Rect/Frame/Stack insertion, rename, delete, reparent, sibling reorder,
placement conversion, geometry and property changes; accepted commands are canonical history steps and rejected tree
commands return the original session without partial mutation. A shared exhaustive node contract keeps the browser wire
inspector, command capability, Structure tree, Inspector and placement safety projections aligned.

Structure authoring now provides legal drag and keyboard before/into/after moves, four children[] Z-order operations,
rename and delete, while suppressing positions whose real parent is outside the T222 core slice. The local fail-closed
safety proof preserves global node/binding/loop/use/definition identity, ContentModel placement, surviving references and
actual Repeat lexical reachability. Nodes without loop references may legally enter or leave a Repeat scope; nodes whose
ValueSource would become unreachable are rejected atomically. Context menus restore focus after action, Escape and
outside dismissal, including a safe fallback when delete removes the trigger row.

The canvas uses Select/Pan modes, V/H/Escape, held Space, pointer-centred wheel zoom, Ctrl+A, direct move/resize and an
independent editor overlay for selection chrome. Paint order is exactly authored children[] order. The grouped Inspector
edits only supported properties while preserving lossless numbers and uses the same Java-compatible 1..128 Unicode
display-name normalization as the session and command layers. Assets and Definitions retain their formal domain names;
existing load/save/conflict/recovery/import/preview states remain on the original product spine.

Verification on the fixed candidate:

- Final fixed-point Standards and Spec reviews both passed with no remaining blocker or should-fix finding. Review
  regressions cover legal/unreachable Repeat-scope moves, non-core parent move capabilities, shared display names,
  context-menu focus restoration and exact domain vocabulary.
- A clean Node 24 `-Gate web` passed OpenAPI regeneration, typecheck, repository Web lint, 49 files / 444 tests and the
  production build (2175 modules). Evidence:
  `.scratch/worktrees/t222-web-42b1be10/.sdlc/evidence/20260903-015027-web`.
- The Template portion of a clean `-Gate template` passed repository diff, Schema 20/20, Template 194/194, independent
  kernel 211/211 and asset-ref replay 3/3. The gate then hit the pre-existing Renderer tricky-font
  `INPUT_BINDING: renderer/process-manifest.json` failure. The failing manifest/decision/verifier files are unchanged
  across this ticket; the manifest is SHA-256 `7ff8353272715dfdb911c0354b04d33b86f203b8c0a4bd4c1d5762e524e32734`
  while the historical decision binds `f814c98e415e1bee96af198bb36a2eefd91726f3264f26909217e4270afbdeeb`.
  Evidence: `.scratch/worktrees/t222-template-42b1be10/.sdlc/evidence/20260903-015027-template`.
- `tools/run-template-editor-roundtrip-e2e.ps1 -LocalPostgresBin D:\postgresql\bin` passed 2/2 in Chromium against a
  fresh local PostgreSQL: the all-kinds wire opens and survives save/reload, and the production shell authors
  Frame/Stack/Rect then reloads the exact tree and geometry through real Template APIs.
- The dirty shared worktree typecheck still reports only five separately authored Inference `rejectionEnvelope` SDK
  errors. The clean Web gate proves this ticket's generated API, typecheck, lint, tests and build without masking or
  committing those unrelated changes.

T223 is now the frontier. Remaining visual nodes and real Image/Font Asset authoring are intentionally deferred there;
StaticSchema/Definitions/bindings, Grid/Repeat/Conditional/TemplateUse, Renderer and backend DataSource remain outside
T222.
