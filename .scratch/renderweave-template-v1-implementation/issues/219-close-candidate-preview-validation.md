# T219 — Close the Candidate Preview idea-validation loop

Type: task
Status: done
Blocked by: T218

## What to build

Run one bounded real-browser journey against the loopback Candidate Preview environment: create and open a supported
Template, make and save an authored change, produce a real PNG and JPEG through the native Renderer candidate, and
display each only after browser integrity verification. Fix only defects that block this representative flow, then
record which core Template-v1 assumptions were validated and which remain unknown.

## Acceptance criteria

- [x] One repeatable local command starts ephemeral PostgreSQL, the app, Web and the exact native Renderer candidate
  without external model calls, real data, production access or secrets.
- [x] Playwright exercises only real product URLs plus the explicit local candidate opt-in; it does not intercept or
  synthesize Template, save, Evaluation or image responses.
- [x] The journey proves create/open → authored edit → explicit save → candidate PNG → candidate JPEG, including
  `NOT_CERTIFIED` UI disclosure and complete response integrity metadata.
- [x] The same environment proves the normal Authoritative Preview still refuses to render without a Certified Profile.
- [x] Serious/critical accessibility findings are zero for the exercised desktop journey, and async pending/error
  feedback is observable without relying on color alone.
- [x] A concise validation note separates demonstrated facts, rejected assumptions and deferred formal work.

## Test plan

- Add the narrow Playwright journey and an orchestration smoke test with deterministic cleanup.
- Run the canary twice for repeatability, the focused browser test, and the affected Web/server/render gates.

## Out of scope

- Expanding beyond the single representative supported Template or fixing non-blocking polish and refactor debt.
- Formal exact-output records/corpus, physical-host certification, Profile registration, production rollout or READY.
- Provider calls, real customer data, push, tag or pull request.

## Resolution

Implemented a single-command, payload-free local canary around fresh PostgreSQL, the real app/Web, and the exact T217
native Renderer candidate. The real-browser journey creates a Template, adds and saves a visible Rect at revision 1,
verifies PNG and JPEG response bytes plus metadata before display, runs the scoped accessibility check, and proves the
formal Authoritative Preview still returns `503 RENDERER_UNAVAILABLE` without Candidate headers.

The first real execution rejected the editor's arbitrary millimetre default as `NonPixelAlignedRect`; the authored
Rect default now uses one-inch geometry that lowers exactly at integral DPI. The preview image scroll region is also
keyboard focusable. Two fresh repeatability runs passed with identical output identities under
`.sdlc/evidence/20260901-124732-template-candidate-preview` and
`.sdlc/evidence/20260901-124818-template-candidate-preview`; both cleaned all exact Docker resources. The complete
build-and-run command also passed under `.sdlc/evidence/20260901-125059-template-candidate-preview`. Durable findings
and deferred formal work are recorded in `docs/validation/template-v1-candidate-preview-v1.md`. The affected Web gate
passed 281 tests plus generated-contract check, typecheck, lint, and production build under
`.sdlc/evidence/20260901-125234-web`.
