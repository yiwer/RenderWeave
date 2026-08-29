# T192 — Mapping cases total capacity authority

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T126, T191 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-052` and DESIGN_INPUT_EXPRESSION cap-044:
`expression.mappingCasesTotal` is MAX_INCLUSIVE `8192`, with observed values `8191/8192/8193`,
contract stage `EXPRESSION_PARSE_AND_STATIC_ANALYSIS`, public render stage `TEMPLATE_CLOSURE`,
code `EXPRESSION_LIMIT_EXCEEDED`, reservation before AST/graph/case/input/list/decimal admission and
lazy execution, and zero boundary `ZERO_WRITE_AND_DOWNSTREAM`.

Every authored Mapping case consumes the static total before presence or lazy execution, including
unselected cases and MappingDefinitions never demanded at runtime. The total is owned by one
DesignDSL and resets for each frozen TemplateSnapshot.

## Interface / seam

- Deepen the existing Rendering.internal `DesignInputExpressionCapacityGuard`; do not add a public
  API/SPI, route, DTO, persistence seam, app wiring, duplicate maximum, or second guard.
- Add one per-DesignDSL Mapping-case budget. Each reservation must enforce cap-043 per-definition
  before cap-044 cumulative admission and commit the cumulative value only when both checks pass.
- `ExpressionCapacityAdmission` must create a fresh budget for every frozen TemplateSnapshot and
  reserve every MappingDefinition's complete admitted `cases` before InputAdmission or lazy Definition
  evaluation.
- The existing isolated guard tracer proves exact cap-044 identity. The public `Evaluator.evaluate`
  seam must prove 32 unused Mappings of 256 cases seal, a 33rd Mapping's first case rejects with zero
  downstream work, and separate root/child DesignDSL totals do not leak across snapshots.
- This ticket does not implement cap-045 AST nodes or later graph/list/decimal axes, formal Ticket 19
  record issuance, or a complete execution-class target.

## TDD, validation, and boundaries

- First add the missing guard enum/budget tests for `8191/8192/8193` and obtain compile RED; then add
  a public Evaluator behavioral test and obtain a pre-integration RED before the smallest product change.
- Keep every Mapping at or below 256 cases and every case structurally and semantically valid. Leave
  all MappingDefinitions unused so the tests prove static admission of unselected/lazy content.
- Run focused Rendering tests, the affected Java reactor, `render`, and `fast`; no server/full unless
  impact expands into app wiring.
- No OpenAPI/Web/Flyway/Profile registration/certification/provider/API Key/real data/cost, and no
  push/tag/PR. Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- The existing internal guard now owns cap-044 and exposes one `MappingCaseBudget` per DesignDSL.
  Each reservation checks cap-043 first, then an overflow-safe cap-044 projected total; only accepted
  reservations commit the cumulative value.
- `ExpressionCapacityAdmission` creates a fresh Mapping-case budget for every frozen TemplateSnapshot
  and reserves every MappingDefinition's complete authored `cases` before InputAdmission or lazy
  Definition evaluation.
- TDD produced the expected missing-enum/missing-budget compile RED. The public Evaluator behavioral
  RED showed that 8,193 valid but unused cases incorrectly reached `SealedDocument`; after wiring it
  returns TEMPLATE_CLOSURE / EXPRESSION_LIMIT_EXCEEDED / `expression.mappingCasesTotal` with
  validation-target resolution, capability establish/restore, and state load/save all zero.
- Thirty-two unused Mappings with 256 cases each seal at the exact 8,192 maximum. A root and child
  snapshot each carrying that exact total also seal, proving the budget resets per DesignDSL.
- Focused guard/Evaluator/ExpressionEngine tests passed `140/140`; the affected Java reactor passed
  `547/547` (Schema `20`, Validation `13`, Template `86`, Asset `92`, Rendering `336`). `render`
  `.sdlc/evidence/20260829-165304-render/metadata.json` and `fast`
  `.sdlc/evidence/20260829-165352-fast/metadata.json` both report `passed` / A1.
- No cap-045 or later axis, T192-specific A2/A3, formal Ticket 19 record issuance, or complete
  execution-class claim was made. J0 remains pending and J1 was not approved. No public API/SPI,
  app, OpenAPI, Web, Flyway, Profile, provider, API Key, real data, cost, push, tag, or PR action occurred.
