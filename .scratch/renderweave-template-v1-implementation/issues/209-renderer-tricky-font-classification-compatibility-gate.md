# T209 — Renderer tricky-font classification compatibility gate

Type: task
Status: active / claimed
Claimed by: Codex `/root`（single-writer）
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

Pending.
