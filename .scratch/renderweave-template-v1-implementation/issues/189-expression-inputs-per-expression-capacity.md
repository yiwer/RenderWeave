# T189 — Expression inputs per-expression capacity authority

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T126, T188 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-049` and DESIGN_INPUT_EXPRESSION cap-041:
`expression.inputsPerExpression` is MAX_INCLUSIVE `32`, with observed values `31/32/33`,
contract stage `EXPRESSION_PARSE_AND_STATIC_ANALYSIS`, public render stage `TEMPLATE_CLOSURE`,
code `EXPRESSION_LIMIT_EXCEEDED`, reservation before AST/graph/case/input/list/decimal admission and
lazy execution, and zero boundary `ZERO_WRITE_AND_DOWNSTREAM`.

Every declared Expression input consumes the static budget before presence or lazy execution,
including inputs that are unused or belong to an ExpressionDefinition never demanded at runtime.

## Interface / seam

- Deepen the existing Rendering.internal `DesignInputExpressionCapacityGuard`; do not add a public
  API/SPI, route, DTO, persistence seam, app wiring, or a second guard.
- `ExpressionCapacityAdmission` must read each ExpressionDefinition's admitted `inputs` array and
  apply cap-041 before source parsing or AST/static analysis. It continues to inspect every
  ExpressionDefinition in every frozen TemplateSnapshot.
- The existing isolated guard tracer proves exact cap-041 identity. The behavioral seam remains
  public `Evaluator.evaluate`: one valid Expression with exactly 32 used inputs succeeds, while 33
  declared inputs win over invalid/unused source semantics and fail before InputAdmission,
  capability state, Asset work, materialization, document, or output.
- This ticket does not implement cap-042 `expression.inputsTotal`, AST/graph/case/list/decimal axes,
  formal Ticket 19 record issuance, or a complete execution-class target.

## TDD, validation, and boundaries

- First add the missing guard enum test for `31/32/33` and obtain compile RED; then add a public
  Evaluator behavioral test and obtain a pre-integration RED before the smallest product change.
- Keep exact-at input aliases unique and all used. Make the above-limit definition statically
  irrelevant/invalid so the test proves capacity precedence and zero downstream work.
- Run focused Rendering tests, the affected Java reactor, `render`, and `fast`; no server/full unless
  impact expands into app wiring.
- No OpenAPI/Web/Flyway/Profile registration/certification/provider/API Key/real data/cost, and no
  push/tag/PR. Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- The existing internal guard now owns cap-041. `ExpressionCapacityAdmission` requires each
  ExpressionDefinition's admitted `inputs` array and applies the inclusive `32` limit before it
  reads or parses the source.
- TDD first produced a compile RED for the missing guard enum. The public Evaluator RED then showed
  that an above-limit definition still entered the parser; after the seam was connected, 33 inputs
  return TEMPLATE_CLOSURE / EXPRESSION_LIMIT_EXCEEDED / `expression.inputsPerExpression` before
  source parsing and all downstream work.
- The above-limit fixture lexically references all 33 unique aliases but places the input
  expressions adjacently without operators, proving capacity precedence over AST/static semantics.
  One declared input is RANDOM,
  and input resolution, capability establish/restore, and state load/save all remain zero. A valid
  Expression with exactly 32 unique, fully-used literal inputs seals successfully.
- Focused guard/Evaluator/ExpressionEngine tests passed `127/127`; the affected Java reactor passed
  `534/534` (Rendering `323/323`). `render`
  `.sdlc/evidence/20260829-162134-render/metadata.json` and `fast`
  `.sdlc/evidence/20260829-162224-fast/metadata.json` both report `passed` / A1.
- No cap-042 implementation, T189-specific A2/A3, formal Ticket 19 record issuance, or complete
  execution-class claim was made. J0 remains pending and J1 was not approved. No public API/SPI,
  app, OpenAPI, Web, Flyway, Profile, provider, API Key, real data, cost, push, tag, or PR action
  occurred.
