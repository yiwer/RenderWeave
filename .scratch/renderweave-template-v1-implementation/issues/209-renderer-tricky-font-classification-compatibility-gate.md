# T209 — Renderer tricky-font classification compatibility gate

Type: task
Status: resolved / automated_verified
Claimed by: none（single-writer claim released）
Blocked by: T208（resolved）

## Goal

Turn the discovered incompatibility between the frozen portable tricky-font requirement and the current
FreeType candidate configuration into an offline, machine-replayable fail-closed gate. The gate must make it
impossible to materialize or certify a Renderer Exact Output target while the candidate compiles out the
only upstream classification path that can set `FT_FACE_FLAG_TRICKY`.

## Frozen observation seam

- Observe only the exact source-integrity target manifest, candidate manifest, portable fixture policy,
  custom `FT_CONFIG_OPTIONS_H`, hermetic-build prerequisites, Renderer process manifest, and one closed
  compatibility decision artifact.
- Bind the reviewed upstream FreeType 2.14.3 commit/path/blob fact without retaining vendor source. Parse the
  local custom header lexically; do not execute or build acquired code and do not inspect proprietary fonts.
- Emit one payload-free report with the exact candidate identity, input bindings, contradiction facts, and
  enforced false lifecycle values. The report is evidence of fail-closed truth only, not Renderer behavior.

## Test-first validation

- Capture RED while the decision artifact and verifier are absent.
- Add a strict Python-stdlib verifier and a PowerShell gate, including malformed/mutated fixture tests.
- Wire the compatibility gate into the Template static gate and run focused tests plus fresh `template`.

## Boundary

- Do not choose or implement a new candidate configuration, revise the portable-authority semantics, create
  font bytes, build/link/execute FreeType or Skia, invoke a Renderer deployment, or use physical Linux hosts.
- Keep `buildAuthorized=false`, `certified=false`, `ready=false`, Renderer Exact Output formal records at zero,
  and Ticket 19 open. No API/OpenAPI/Web/Flyway/Profile/provider/API-key/real-data/production changes.
- A1 is the focused/tool-captured gate; A2/A3 are absent. J0 is pending; the product-semantic resolution still
  requires explicit J1. Do not touch the user's dirty work or push/tag/create a PR.

## Resolution

- Implementation `c194178e` added the closed decision artifact, strict Python verifier, five-case mutation
  suite, focused PowerShell gate, and Template static-gate integration. Decision SHA-256 is
  `8c2488ea27920b7762824f155f0db6a986216e65be2c35de609e3190d62ce5a5`.
- The source fact binds FreeType commit `0a0221a1347e2f1e07c395263540026e9a0aa7c7`, tree
  `589225074ab1eb876682820c482069693c251e88`, `src/truetype/ttobjs.c` SHA-256
  `c381554e81a00f9d5c430e7c51e1d6c289958867426b021a6165eb12b451922d`, and 40,600 bytes without retaining
  vendor source. One bounded official-mirror source read supported that A1 fact; the committed gate is offline.
- Focused tests passed 5/5. Evidence
  `.sdlc/evidence/20260829-235027-template-t209-tricky-font-compatibility/` passed 401 checks and kept every
  build/target/issuance/certification lifecycle false. Fresh Template evidence
  `.sdlc/evidence/20260829-235046-template-t209-integration/` passed Editor 38/21867, SPEC Registry
  24519/24427, artifact count 404, and authorityDiff=0 while carrying the same fail-closed boundary.
- A1 passed; T209-specific A2/A3 are absent. J0 remains pending and J1 is required only to choose the product
  semantic resolution. No font bytes, build, Renderer execution, Profile, provider, API key, real data,
  production, push, tag, PR, or user dirty-work mutation occurred. Claim released.
