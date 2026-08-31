# T215 — Prove production glyph paths fail closed

Type: task
Status: blocked
Blocked by: T214

## What to build

Turn the T214 production glyph slice into a closed, auditable glyph-loading boundary. Inventory every production `FT_Load_Glyph` path, enforce the frozen `NO_HINTING`, `NO_AUTOHINT`, `NO_BITMAP`, and `NO_SVG` policy at each call site, prove TrueType bytecode cannot execute at runtime, and add the required CFF regression coverage.

## Scope

- Machine-checked inventory of all production glyph-load call sites and wrappers.
- Central policy enforcement that cannot be bypassed by shaping, fallback, measurement, raster, or diagnostic paths.
- Runtime instrumentation/tests proving bytecode non-execution and forbidden auto-hint/bitmap/SVG behavior.
- CFF and tricky-font regressions bound to exact fixture identities.

## Acceptance criteria

- Every reachable production glyph-load path is inventoried and carries the exact required flag set.
- Adding an unregistered glyph-load call site or weakening a required flag fails an automated test or build check.
- TrueType bytecode execution, auto-hinting, embedded bitmap loading, and SVG glyph loading are demonstrably unreachable for production commands.
- CFF rendering remains functional under the frozen custom-header policy and has deterministic regression evidence.
- Failures remain atomic and map to the frozen renderer problem boundary.

## Test plan

- Static/source inventory checks plus runtime load-flag instrumentation.
- Tricky TrueType and CFF positive/negative fixtures.
- Mutation-style tests that remove each required flag or add an unregistered call path and expect failure.
- Run renderer, Template, runtime, and relevant end-to-end gates.

## Out of scope

- Remaining RenderNode kinds, JPEG, or the complete exact-output corpus.
- Profile certification, production rollout, or public readiness.

