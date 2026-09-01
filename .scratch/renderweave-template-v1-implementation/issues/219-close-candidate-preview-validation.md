# T219 — Close the Candidate Preview idea-validation loop

Type: task
Status: ready-for-agent
Blocked by: T218

## What to build

Run one bounded real-browser journey against the loopback Candidate Preview environment: create and open a supported
Template, make and save an authored change, produce a real PNG and JPEG through the native Renderer candidate, and
display each only after browser integrity verification. Fix only defects that block this representative flow, then
record which core Template-v1 assumptions were validated and which remain unknown.

## Acceptance criteria

- [ ] One repeatable local command starts ephemeral PostgreSQL, the app, Web and the exact native Renderer candidate
  without external model calls, real data, production access or secrets.
- [ ] Playwright exercises only real product URLs plus the explicit local candidate opt-in; it does not intercept or
  synthesize Template, save, Evaluation or image responses.
- [ ] The journey proves create/open → authored edit → explicit save → candidate PNG → candidate JPEG, including
  `NOT_CERTIFIED` UI disclosure and complete response integrity metadata.
- [ ] The same environment proves the normal Authoritative Preview still refuses to render without a Certified Profile.
- [ ] Serious/critical accessibility findings are zero for the exercised desktop journey, and async pending/error
  feedback is observable without relying on color alone.
- [ ] A concise validation note separates demonstrated facts, rejected assumptions and deferred formal work.

## Test plan

- Add the narrow Playwright journey and an orchestration smoke test with deterministic cleanup.
- Run the canary twice for repeatability, the focused browser test, and the affected Web/server/render gates.

## Out of scope

- Expanding beyond the single representative supported Template or fixing non-blocking polish and refactor debt.
- Formal exact-output records/corpus, physical-host certification, Profile registration, production rollout or READY.
- Provider calls, real customer data, push, tag or pull request.

## Resolution
