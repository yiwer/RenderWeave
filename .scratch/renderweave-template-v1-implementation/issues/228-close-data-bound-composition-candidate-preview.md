# T228 — Close data-bound composition through Candidate Preview

Type: task
Status: ready-for-agent
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
