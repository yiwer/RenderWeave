# T188 — Expression source total capacity authority

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T126, T187 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-048` and design-input-expression cap-040:
`expression.sourceUtf8BytesTotal` is MAX_INCLUSIVE `1048576`, with observed values
`1048575/1048576/1048577`, contract stage `EXPRESSION_PARSE_AND_STATIC_ANALYSIS`, public render
stage `TEMPLATE_CLOSURE`, code `EXPRESSION_LIMIT_EXCEEDED`, and zero boundary
`ZERO_WRITE_AND_DOWNSTREAM`.

Ticket 19 defines this as the total across one complete DesignDSL. The counter therefore resets
for every frozen TemplateSnapshot; it does not combine independent root and child DesignDSL totals.
Every ExpressionDefinition consumes the budget even when unused, unselected, or statically
unreachable.

## Interface / seam

- Deepen the existing Rendering.internal `DesignInputExpressionCapacityGuard`; do not add a public
  API/SPI, route, DTO, persistence seam, app wiring, or a second guard.
- Add one internal per-DesignDSL SourceBudget owned by that guard. It checks the already-frozen
  per-Expression source limit first, atomically accumulates exact UTF-8 bytes, then checks cap-040.
- `ExpressionCapacityAdmission` creates exactly one SourceBudget per closure snapshot and passes it
  through the real parser before AST construction. Standalone/defensive parser calls use a fresh
  budget and retain T187 per-Expression behavior.
- Root and child snapshots may each consume exactly `1048576`; one snapshot reaching `1048577`
  returns the exact public problem before InputAdmission, capability state, Asset work,
  materialization, document, or output.
- The isolated guard tracer proves cap-040 identity only; it does not claim formal Ticket 19 record
  issuance, a complete execution-class target, or A2/A3 product replay.

## TDD, validation, and boundaries

- First add missing total-limit/SourceBudget and real Evaluator expectations to obtain compile and
  behavioral RED; then implement the smallest deepening needed for GREEN.
- Cover guard `1048575/1048576/1048577`, real source accumulation, root/child per-DSL reset,
  unused definitions, per-Expression precedence, and zero input/capability/state work on overflow.
- Run focused Rendering tests, the affected Java reactor, `render`, and `fast`; no server/full unless
  impact expands into app wiring.
- No OpenAPI/Web/Flyway/Profile registration/certification/provider/API Key/real data/cost, and no
  push/tag/PR. Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- The existing internal guard now owns cap-040 and a per-DesignDSL `SourceBudget`. It applies the
  per-Expression limit first, accumulates exact UTF-8 byte counts with `BigInteger`, and then applies
  the inclusive `1048576` total limit.
- `ExpressionCapacityAdmission` creates one budget per frozen TemplateSnapshot and passes it into
  the real parser before UTF-8 decoding or AST construction. Standalone parser calls retain a fresh
  budget and T187 behavior.
- TDD captured missing-limit and missing-budget compile REDs plus a real Evaluator behavioral RED
  that sealed an over-budget document before the admission seam was connected. GREEN covers
  `1048575/1048576/1048577`, per-Expression precedence, unused definitions, root and child DSLs each
  at exactly `1048576`, and zero input/capability/state work when one DSL reaches `1048577`.
- Focused guard/parser/Evaluator tests passed `124/124`; the affected Java reactor passed `531/531`
  (Rendering `320/320`). `render`
  `.sdlc/evidence/20260829-160741-render/metadata.json` and `fast`
  `.sdlc/evidence/20260829-160857-fast/metadata.json` both report `passed` / A1.
- No T188-specific A2/A3 or formal Ticket 19 record issuance is claimed. J0 remains pending and J1
  was not approved. No public API/SPI, app, OpenAPI, Web, Flyway, Profile, provider, API Key, real
  data, cost, push, tag, or PR action occurred.
