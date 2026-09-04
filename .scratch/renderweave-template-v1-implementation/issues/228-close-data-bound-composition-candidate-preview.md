# T228 — Close data-bound composition through Candidate Preview

Type: task
Status: blocked
Blocked by: T227 (done)

## What to build

Drive the newly landed production authoring semantics through the existing loopback-only Candidate Preview path. Create
real StaticSchema input plus parent and child Templates, author Binding, Repeat, Conditional and TemplateUse through the
product editor, save them, and render two deliberately small inputs through the real Evaluator and native Renderer
candidate. Use only node and layout variants already supported by the candidate; this ticket validates the vertical seam
rather than expanding the Renderer profile.

## Acceptance criteria

- [ ] One bounded command exercises only real product URLs and real Template/Rendering responses, with no API
  interception or synthetic Evaluation/image response.
- [ ] Two distinct RenderInput values visibly change bound output and demonstrate Repeat expansion, Conditional pruning
  and TemplateUse lowering through the native process.
- [ ] PNG and JPEG responses are checked before display for status, media type, byte length, digest, format, dimensions
  and disclosed candidate profile metadata.
- [ ] Candidate output remains explicitly `NOT_CERTIFIED` and `no-store`; the normal Authoritative Preview remains
  fail-closed without a Certified Profile.
- [ ] The ticket has focused regression coverage, affected verification, fixed-point review and its own commit.

## Test plan

- Extend the existing Candidate Preview canary and its focused browser journey.
- Run the single canary plus directly affected tests; run a broad gate only if the implementation changes that surface.

## Out of scope

- Asset fetch/TLS expansion, new Renderer node kinds, exact-output corpus issuance, Profile registration, certification,
  production rollout, IMAGE_ONLY admission, hash replay or historical backtests.

## Resolution

## Blocker

- The executable T228 canary is implemented in `be46ac25` and passes its TypeScript strict check, targeted ESLint,
  Playwright discovery, focused Preview tests (2 files / 35 tests), PowerShell parsing and diff checks.
- The real canary stops before service startup because Docker volume `rw-t217-final-20260901-b` and its pinned build
  image are no longer present after external Docker cleanup. The diagnostic is captured at
  `.sdlc/evidence/20260904-t228-red-diagnostic/metadata.json`.
- Recreating the old candidate requires restaging roughly 3.4 GB and rebuilding 573 targets; a smaller substitute would
  require a new raster feature plus a new native JPEG seam. Neither replay was started because both exceed this fast
  validation ticket.
- Resume when the known T217 candidate artifact is restored or an equivalent already-built candidate is supplied. No
  product failure, certification result or fixed-point Spec PASS is claimed while the native GREEN is absent.
