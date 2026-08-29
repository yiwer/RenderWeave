# T194 — Expression AST nodes total capacity authority

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T126, T193 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-054` and DESIGN_INPUT_EXPRESSION cap-046:
`expression.astNodesTotal` is MAX_INCLUSIVE `65536`, with observed values `65535/65536/65537`,
contract stage `EXPRESSION_PARSE_AND_STATIC_ANALYSIS`, public render stage `TEMPLATE_CLOSURE`,
code `EXPRESSION_LIMIT_EXCEEDED`, reservation before AST/graph/case/input/list/decimal admission and
lazy execution, and zero boundary `ZERO_WRITE_AND_DOWNSTREAM`.

Every parsed AST node consumes the static total before presence or lazy execution, including nodes in
ExpressionDefinitions never demanded at runtime. The total is owned by one DesignDSL and resets for
each frozen TemplateSnapshot.

## Interface / seam

- Deepen the existing Rendering.internal `DesignInputExpressionCapacityGuard`; do not add a public
  API/SPI, route, DTO, persistence seam, app wiring, duplicate maximum, or second guard.
- Add one per-DesignDSL `AstNodeBudget`. Each node reservation must enforce cap-045 using the current
  per-Expression count before cap-046 cumulative admission and commit the cumulative value only when
  both checks pass.
- Add an internal parser overload accepting the shared AST budget. Existing standalone/defensive
  parser calls create a fresh budget; `ExpressionCapacityAdmission` creates one budget per frozen
  TemplateSnapshot and passes it to every Expression parse.
- Public `Evaluator.evaluate` must prove 16 unused 4,096-node Expressions seal at 65,536, the first
  node of a 17th Expression rejects with zero downstream work, and separate root/child DesignDSL
  totals do not leak across snapshots.
- This ticket does not implement cap-047 graph edges or later graph/list/decimal axes, formal Ticket 19
  record issuance, or a complete execution-class target.

## TDD, validation, and boundaries

- First add the missing guard enum/budget tests for `65535/65536/65537` and obtain compile RED; then
  add a public Evaluator behavioral test and obtain a pre-integration RED before the smallest product
  change. Keep each Expression at or below 4,096 nodes and use balanced, semantically valid decimals.
- Run focused Rendering tests, the affected Java reactor, `render`, and `fast`; no server/full unless
  impact expands into app wiring.
- No OpenAPI/Web/Flyway/Profile registration/certification/provider/API Key/real data/cost, and no
  push/tag/PR. Initial claim evidence was A0; J0 pending and J1 not approved.

## Resolution

- `DesignInputExpressionCapacityGuard` now owns cap-046 and creates one `AstNodeBudget` per
  DesignDSL. Every node first reuses cap-045 with the current per-Expression count, then checks the
  projected cap-046 total, and commits the cumulative value only after both checks accept it.
- `ExpressionParser` accepts the shared budget through an internal overload; standalone and
  defensive callers receive a fresh local budget. `ExpressionCapacityAdmission` creates exactly one
  shared budget for each frozen TemplateSnapshot, so all unused ExpressionDefinitions are counted
  statically while root and child DesignDSL totals remain isolated.
- TDD captured the missing-enum/missing-budget compile RED and a public Evaluator behavioral RED in
  which 65,537 valid unused AST nodes incorrectly produced `SealedDocument`. The integrated path now
  rejects that input with exact TEMPLATE_CLOSURE / EXPRESSION_LIMIT_EXCEEDED /
  `expression.astNodesTotal` identity before validation-target, capability-runtime, or state-store
  work. Sixteen 4,096-node Expressions seal at the exact 65,536 limit, and root plus child snapshots
  each independently seal at 65,536.
- Focused Rendering tests passed 148/148. The affected Maven reactor passed 555/555 (Schema 20,
  Validation 13, Template 86, Asset 92, Rendering 344). `render`
  `.sdlc/evidence/20260829-170856-render/metadata.json` and `fast`
  `.sdlc/evidence/20260829-170947-fast/metadata.json` both report `passed` / A1.
- Cap-047 and later axes, formal Ticket 19 record issuance, and a complete execution-class target
  remain deferred. T194-specific A2/A3 are absent; J0 remains pending and J1 was not approved. No
  public API/SPI, app wiring, OpenAPI, Web, Flyway, Profile, provider, API Key, real-data, cost,
  push, tag, or PR action occurred.
