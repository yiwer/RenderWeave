# T227 — Preserve editor safety paths and retire the throwaway prototype

Type: task
Status: in-progress
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

- [ ] Dirty local work survives conflict/reload decisions exactly as before; no force save, auto merge or server draft
  appears.
- [ ] INVALID confirmation, recovery, import, Raw Repair and Compatibility Read-only remain reachable, lossless and
  clearly distinct.
- [ ] The complete keyboard path covers catalog insertion, Structure operations, property/binding editing, save and
  recovery; exercised pages have zero serious/critical accessibility findings.
- [ ] Production route E2E covers the representative data-bound, asset-backed, Stack/Grid, Repeat/Conditional and
  TemplateUse journeys without API interception.
- [ ] Obsolete prototype variants/routes and duplicate state models are removed or development-isolated, affected gates
  pass, fixed-point review blockers are resolved, and the ticket has its own commit.

## Test plan

- Extend focused session/recovery/import/conflict/accessibility tests and the real product-route Playwright journey.
- Run `fast`, `template`, `server`, `web` and `e2e`; expand further only if the actual change surface requires it.

## Out of scope

- Renderer certification, production rollout authorization, autosave, collaboration, plugin systems and controlled SVG
  import.

## Resolution
