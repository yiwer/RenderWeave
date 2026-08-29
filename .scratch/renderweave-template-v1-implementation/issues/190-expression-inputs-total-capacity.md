# T190 — Expression inputs total capacity authority

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T126, T189 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-050` and DESIGN_INPUT_EXPRESSION cap-042:
`expression.inputsTotal` is MAX_INCLUSIVE `4096`, with observed values `4095/4096/4097`,
contract stage `EXPRESSION_PARSE_AND_STATIC_ANALYSIS`, public render stage `TEMPLATE_CLOSURE`,
code `EXPRESSION_LIMIT_EXCEEDED`, reservation before AST/graph/case/input/list/decimal admission and
lazy execution, and zero boundary `ZERO_WRITE_AND_DOWNSTREAM`.

Every declared Expression input consumes the static total before presence or lazy execution,
including inputs of ExpressionDefinitions that are never demanded. The total is owned by one
DesignDSL and resets for each frozen TemplateSnapshot.

## Interface / seam

- Deepen the existing Rendering.internal `DesignInputExpressionCapacityGuard`; do not add a public
  API/SPI, route, DTO, persistence seam, app wiring, or a second guard.
- `ExpressionCapacityAdmission` must apply cap-041 per-expression before cap-042 cumulative admission,
  and must reserve both before reading or parsing that Expression source.
- The existing isolated guard tracer proves exact cap-042 identity. The behavioral seam remains public
  `Evaluator.evaluate`: one valid DesignDSL with exactly 4,096 declared inputs succeeds, while the next
  input wins over invalid source semantics and fails before InputAdmission, capability state, Asset work,
  materialization, document, or output. A multi-snapshot closure proves the total resets per DesignDSL.
- This ticket does not implement cap-043 Mapping cases, later AST/graph/list/decimal axes, formal Ticket 19
  record issuance, or a complete execution-class target.

## TDD, validation, and boundaries

- First add the missing guard budget test for `4095/4096/4097` and obtain compile RED; then add a public
  Evaluator behavioral test and obtain a pre-integration RED before the smallest product change.
- Keep every Expression at or below 32 inputs. Use valid, unique, fully referenced aliases for exact-at;
  make the total-above terminal Expression source invalid only after all aliases are lexically referenced,
  proving capacity precedence and zero downstream work.
- Run focused Rendering tests, the affected Java reactor, `render`, and `fast`; no server/full unless impact
  expands into app wiring.
- No OpenAPI/Web/Flyway/Profile registration/certification/provider/API Key/real data/cost, and no
  push/tag/PR. Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- The existing internal guard now owns cap-042 and exposes one request-local `InputBudget` used once
  per DesignDSL. Each reservation checks cap-041 first, then an overflow-safe cap-042 projected total;
  only accepted reservations commit the cumulative value.
- `ExpressionCapacityAdmission` creates a fresh input budget for every frozen TemplateSnapshot and
  reserves each ExpressionDefinition's admitted `inputs` before reading or parsing its source.
- TDD produced a missing-enum compile RED, a missing-budget compile RED, and a public Evaluator RED
  where the 4,097-input fixture reached invalid source parsing and returned `RENDER_INTERNAL_ERROR`.
  After wiring, it returns TEMPLATE_CLOSURE / EXPRESSION_LIMIT_EXCEEDED / `expression.inputsTotal`
  with input resolution, capability establish/restore, and state load/save all zero.
- A valid single DesignDSL with 4,096 fully referenced literal inputs seals successfully. A root and
  child snapshot each carrying 4,096 inputs also seal, proving the total resets per DesignDSL.
- Focused guard/Evaluator/ExpressionEngine tests passed `132/132`; the affected Java reactor passed
  `539/539` (Rendering `328/328`). `render`
  `.sdlc/evidence/20260829-163521-render/metadata.json` and `fast`
  `.sdlc/evidence/20260829-163610-fast/metadata.json` both report `passed` / A1.
- No cap-043 implementation, T190-specific A2/A3, formal Ticket 19 record issuance, or complete
  execution-class claim was made. J0 remains pending and J1 was not approved. No public API/SPI,
  app, OpenAPI, Web, Flyway, Profile, provider, API Key, real data, cost, push, tag, or PR action occurred.
