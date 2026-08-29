# T187 — Expression source UTF-8 capacity authority

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T126, T186 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-047` and design-input-expression cap-039:
`expression.sourceUtf8BytesPerExpression` is MAX_INCLUSIVE `65536`, with observed values
`65535/65536/65537`, contract stage `EXPRESSION_PARSE_AND_STATIC_ANALYSIS`, public render stage
`TEMPLATE_CLOSURE`, code `EXPRESSION_LIMIT_EXCEEDED`, and zero boundary
`ZERO_WRITE_AND_DOWNSTREAM`.

The existing parser happens to compare source bytes against a naked `65_536`, but exposes the
overflow only as an ordinary parse failure. Closure-wide static admission therefore folds the
frozen capacity outcome into an internal fault. T187 replaces that accidental split authority
with the existing versioned internal capacity guard and preserves the exact public problem.

## Interface / seam

- Deepen the existing Rendering.internal `DesignInputExpressionCapacityGuard`; do not add a
  public API/SPI, route, DTO, persistence seam, app wiring, or a second guard.
- `ExpressionParser` consumes the same guard before UTF-8 decoding, AST construction, graph/case/
  input/list/decimal admission, or lazy execution. Exact-at remains accepted; above returns a
  distinct typed capacity outcome carrying the frozen problem.
- Closure-wide `ExpressionCapacityAdmission` propagates that outcome for every snapshot and every
  Expression, including unused definitions, before InputAdmission or any downstream work.
- `DefinitionEngine` preserves the exact capacity identity defensively if reached through a late
  internal path, without demanding expression inputs or capabilities.
- The isolated guard tracer proves cap-039 identity only; it does not claim formal Ticket 19 record
  issuance, a complete execution-class target, or A2/A3 product replay.

## TDD, validation, and boundaries

- First add missing guard enum/typed parser outcome and closure fail-fast expectations to obtain
  compile and behavioral RED; then implement the smallest guard/parser/admission integration.
- Cover guard and valid ASCII Expression source at `65535/65536/65537` UTF-8 bytes, ordinary syntax
  failures, an unused oversized definition, and defensive late-path zero demand.
- Run focused Rendering tests, the affected Java reactor, `render`, and `fast`; no server/full unless
  impact expands into app wiring.
- No OpenAPI/Web/Flyway/Profile registration/certification/provider/API Key/real data/cost, and no
  push/tag/PR. Claim evidence is A0; J0 pending and J1 not approved.

## Resolution (2026-08-29)

- Deepened the single internal `renderweave-design-input-expression-capacity-guard/1.0`
  authority with cap-039. Its tracer admits `65535/65536` and rejects `65537` with public
  `TEMPLATE_CLOSURE / EXPRESSION_LIMIT_EXCEEDED /
  expression.sourceUtf8BytesPerExpression`.
- `ExpressionParser` now consumes the guard before UTF-8 decoding or AST construction and returns
  a distinct typed `ParseLimitExceeded`; the naked `65_536`, duplicate limitId string, and ordinary
  `SOURCE_LIMIT_EXCEEDED` parse taxonomy were removed. Exact-at parses as a valid Expression and
  ordinary syntax/AST failures retain their prior behavior.
- Closure-wide `ExpressionCapacityAdmission` propagates the exact problem for every frozen
  snapshot and unused Expression before InputAdmission, CapabilityState, Asset, materialization,
  document, or output work. `DefinitionEngine` preserves the same identity defensively and the
  RANDOM-input tests prove zero provider demand.
- TDD produced the expected missing-limit/missing-outcome compile RED, then focused tests passed
  120/120. The affected reactor passed Schema 20/20, Validation 13/13, Template 86/86, Asset 92/92,
  Rendering 316/316: 527/527 total.
- `render` evidence `.sdlc/evidence/20260829-155413-render/metadata.json` and `fast` evidence
  `.sdlc/evidence/20260829-155510-fast/metadata.json` are both passed/A1. T187-specific A2/A3 are
  absent; J0 remains pending and J1 is not approved, so lifecycle is `automated_verified` only.
- No API/OpenAPI/Web/Flyway/app/Profile/certification/provider/API Key/real data/cost change; no
  push/tag/PR and no server/full repetition.
