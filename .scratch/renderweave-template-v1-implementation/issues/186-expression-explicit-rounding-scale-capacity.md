# T186 — Expression explicit rounding scale capacity authority

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T126, T185 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-064` and design-input-expression cap-055:
`expression.explicitRoundingScaleMax` is MAX_INCLUSIVE `64`, with observed values
`63/64/65`, contract stage `EXPRESSION_PARSE_AND_STATIC_ANALYSIS`, public render stage
`TEMPLATE_CLOSURE`, code `EXPRESSION_LIMIT_EXCEEDED`, and zero boundary
`ZERO_WRITE_AND_DOWNSTREAM`.

The existing Expression analyzer happens to compare a literal against a naked `64`, but reports
an overflow as `COMPILE_TIME_LITERAL_REQUIRED`. T186 replaces that accidental behavior with one
versioned internal capacity authority and a distinct capacity outcome carrying the frozen
`limitId`, problem code, and public stage.

## Interface / seam

- Add one Rendering.internal `DesignInputExpressionCapacityGuard`; do not add public API/SPI,
  route, DTO, persistence, app wiring, or a second guard.
- `ExpressionAnalyzer` invokes the guard for every explicit scale operand accepted by
  `divide`, `round`, and both `formatDecimal` scale positions. Valid non-negative integral
  literals at `64` remain admitted; `65` returns the exact capacity outcome rather than a
  syntax/type failure.
- The isolated guard tracer proves cap-055 identity only. A separate parser-to-static-analysis
  product test proves the actual Expression path consumes the same guard before lazy evaluation.
- Do not claim that the candidate conformance probe executes the complete Evaluator, writes
  formal Ticket 19 records, or provides A2/A3 product-path evidence.

## TDD, validation, and boundaries

- First add missing guard and typed analyzer-capacity expectations to obtain compile/behavioral
  RED; then implement the smallest authority and integration needed for GREEN.
- Cover guard `63/64/65`, plus real parser/analyzer paths for `divide`, `round`, and both
  `formatDecimal` scale operands. Preserve ordinary compile-time-literal and rounding-mode
  failures.
- Run focused Rendering tests, affected Java reactor, `render`, and `fast`; no server/full unless
  the impact expands into app wiring.
- No OpenAPI/Web/Flyway/Profile registration/certification/provider/API Key/real data/cost,
  and no push/tag/PR. Claim evidence is A0; J0 pending and J1 not approved.

## Resolution (2026-08-29)

- Added the single internal `renderweave-design-input-expression-capacity-guard/1.0`
  authority. Its cap-055 tracer admits `63/64` and rejects `65` with public
  `TEMPLATE_CLOSURE / EXPRESSION_LIMIT_EXCEEDED /
  expression.explicitRoundingScaleMax`.
- `ExpressionAnalyzer` now returns a distinct `AnalysisLimitExceeded` before ordinary static
  failures for every explicit scale operand in `divide`, `round`, and both `formatDecimal`
  positions. The naked `64` and the false `COMPILE_TIME_LITERAL_REQUIRED` overflow report are
  gone; non-literal/negative/non-integral and rounding-mode failures retain their old taxonomy.
- Added closure-wide `ExpressionCapacityAdmission`. It inspects every frozen snapshot and every
  Expression, including unused definitions, before InputAdmission, CapabilityState, provider,
  Asset, materialization, document, or output work. The Evaluator tracer proves input resolutions,
  capability establish/restore, and state load/save all remain zero on overflow. DefinitionEngine
  also retains the exact capacity identity as a defensive late invariant and performs no lazy
  input demand.
- TDD produced the expected missing-guard/missing-outcome compile RED and an unused-expression
  Evaluator behavioral RED, then focused tests passed 33/33. The affected reactor passed Schema
  20/20, Validation 13/13, Template 86/86, Asset 92/92, Rendering 313/313: 524/524 total.
- `render` evidence `.sdlc/evidence/20260829-154121-render/metadata.json` and `fast` evidence
  `.sdlc/evidence/20260829-154217-fast/metadata.json` are both passed/A1. T186-specific A2/A3 are
  absent; J0 remains pending and J1 is not approved, so lifecycle is `automated_verified` only.
- No API/OpenAPI/Web/Flyway/app/Profile/certification/provider/API Key/real data/cost change; no
  push/tag/PR and no server/full repetition.
