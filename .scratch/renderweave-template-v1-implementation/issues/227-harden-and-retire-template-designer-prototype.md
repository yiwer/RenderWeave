# T227 — Preserve editor safety paths and retire the throwaway prototype

Type: task
Status: done
Blocked by: T226 (done)

## What to build

Close the production authoring journey without weakening the existing safety surfaces. Exercise conflict reconciliation,
INVALID confirmation, local recovery draft, import/adoption, problem location, Compatibility Read-only, Raw Repair,
keyboard equivalents and accessibility against documents created by the new shell. Remove the three-variant prototype
from normal navigation once the production route covers the approved interaction contract; retain only evidence that is
still useful to maintain pure layout/command behavior.

Update current domain/product documentation to describe the landed behavior and explicit deferrals. Do not convert old
historical Template records or `.sdlc/evidence` into a new status system.

## Acceptance criteria

- [x] Dirty local work survives conflict/reload decisions exactly as before; no force save, auto merge or server draft
  appears.
- [x] INVALID confirmation, recovery, import, Raw Repair and Compatibility Read-only remain reachable, lossless and
  clearly distinct.
- [x] The complete keyboard path covers catalog insertion, Structure operations, property/binding editing, save and
  recovery; exercised pages have zero serious/critical accessibility findings.
- [x] Production route E2E covers the representative data-bound, asset-backed, Stack/Grid, Repeat/Conditional and
  TemplateUse journeys without API interception.
- [x] Obsolete prototype variants/routes and duplicate state models are removed or development-isolated, affected gates
  pass, fixed-point review blockers are resolved, and the ticket has its own commit.

## Test plan

- Extend focused session/recovery/import/conflict/accessibility tests and the real product-route Playwright journey.
- Run `fast`, `template`, `server`, `web` and `e2e`; expand further only if the actual change surface requires it.

## Out of scope

- Renderer certification, production rollout authorization, autosave, collaboration, plugin systems and controlled SVG
  import.

## Resolution

- Landed in `0893a6d5`: production editor keyboard paths now cover save, undo/redo and scoped deletion while preserving
  native editable-field behavior; the keyboard browser journey also covers insertion, rename, property/binding edits,
  INVALID cancellation, reload and local recovery.
- Removed the retired Template Designer and Editor State Model prototype routes, implementations, duplicate audit/state
  model and prototype-only test/style surface. Kept Schema Studio and explicitly dated design evidence.
- Fixed-point review for `3401efcf...0893a6d5`: Standards PASS and Spec PASS, with zero hard blockers. This range has the
  same stable patch-id as the pre-housekeeping review range.
- Focused verification passed: 13 Vitest files / 241 tests, typecheck, affected ESLint, the keyboard-complete Playwright
  journey (1/1), and web gate (58 files / 709 tests plus production build) at
  `.sdlc/evidence/20260904-004009-web`.
- The immediately preceding official no-interception product-route roundtrip passed 5/5 at
  `C:\Users\Administrator\AppData\Local\Temp\renderweave-t226-final-20260904-002\template-roundtrip-journey`, covering
  asset-backed, data-bound, Stack/Grid, Repeat/Conditional and TemplateUse journeys. It was not replayed after this
  Web-only shortcut/prototype retirement change.
- `fast`, `template`, `server`, generic `e2e`, `full`, hash checks and backtests were not replayed: the web gate is the
  affected build surface, while the focused browser journey is the affected runtime path.
