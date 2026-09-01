# T218 — Run a local Candidate Preview through the real Template-to-Renderer path

Type: task
Status: done
Blocked by: T217 (done)

## What to build

Add one explicitly opt-in, loopback-only Candidate Preview path for idea validation. It must reuse the saved Template
current, the production Evaluator and the production Renderer process adapter, but select the mechanically proven
native candidate without registering or certifying `renderweave-renderer/1.0`. The normal Formal Output and
Authoritative Preview endpoints remain unchanged and fail closed while no Certified Profile exists.

The existing Template Editor may activate this path only through an explicit local candidate mode. Every control,
pending state, result and error must say that the output is a non-certified candidate; a response is displayable only
after the existing image length/digest/Profile checks plus a candidate-status header pass.

## Acceptance criteria

- [x] Candidate server assembly is absent by default, requires an explicit property, and rejects non-loopback calls.
- [x] Normal `/api/v1/templates/{templateId}/authoritative-preview` behavior and Profile availability stay fail closed.
- [x] Candidate execution uses the real `TemplateClosureAuthority` → Evaluator → Renderer process → atomic
  `RenderOutput` chain for one saved, supported Template; it does not inject RenderDocument or image fixtures.
- [x] The Editor exposes candidate mode only through an explicit local opt-in, labels it `NOT_CERTIFIED`, and retains
  the existing single-slot, save-before-preview, abort, generation, digest and stale-result protections.
- [x] Both PNG and JPEG responses carry and pass exact candidate-status, media, length, digest and Profile metadata
  checks before browser display.
- [x] OpenAPI, Certified Profile registration, production defaults and external-provider behavior are unchanged.

## Test plan

- Add app configuration/controller contract tests for default absence, loopback admission, formal fail-closed isolation,
  candidate headers and complete real application delegation.
- Add Web transport/coordinator/DOM tests for explicit selection, honest labels, header enforcement, async feedback,
  keyboard focus and old-result withdrawal.
- Run focused Maven and Web tests, then the affected `server`, `web` and `render` gates as risk requires.

## Out of scope

- Formal Renderer certification, Profile registration, public Candidate API/OpenAPI, deployment or READY claims.
- Full Renderer corpus, unsupported Design/Render node kinds, LayoutTrace, public cancel or preview history.
- The final real-browser orchestration and journey, which belongs to T219.

## Resolution

- Added a property-gated, loopback-only internal Candidate Preview assembly. It binds the production Evaluator and
  exact `RendererProcessAdapter` behind a type-separated application while the formal Profile authority remains the
  fail-closed bean. Candidate delivery adds `NOT_CERTIFIED` plus `no-store` without changing OpenAPI or defaults.
- Added the exact local query opt-in and honest Candidate UI. PNG/JPEG bytes are shown only after candidate-status,
  media, length, digest, output selection and Profile metadata verification. Assurance changes now synchronously
  hide pending/problem/rendered state before the cleanup effect, so an old candidate can never be relabelled formal.
- TDD/verification: the initial Web RED had 6 candidate assertions fail while 274 existing assertions passed; the
  initial Maven RED was missing Candidate types. Focused post-review runs passed 18 Maven tests and 39 Web tests,
  plus Web typecheck and focused lint. A real-Evaluator assembly test now proves closure freeze → canonical
  RenderDocument → exact process-adapter command → sealed `RenderOutput` delegation.
- Affected gates passed: `server` (`BUILD SUCCESS`, Application 465 tests, evidence
  `.sdlc/evidence/20260901-114328-server`) and `web` (281 tests plus typecheck/lint/build, evidence
  `.sdlc/evidence/20260901-115543-web`). Renderer sources were unchanged, so the already-passed T217 exact render
  gate remains the relevant renderer evidence; the native-binary browser chain is T219.
- Fixed-point review from `38d46ece` found one tracker transition violation and one assurance-display race; both are
  closed by this amend. Thin-wrapper and repeated assurance-copy observations are non-blocking v1 refactor debt.
