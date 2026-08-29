# T191 — Mapping cases per-definition capacity authority

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T126, T190 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-051` and DESIGN_INPUT_EXPRESSION cap-043:
`expression.mappingCasesPerDefinition` is MAX_INCLUSIVE `256`, with observed values `255/256/257`,
contract stage `EXPRESSION_PARSE_AND_STATIC_ANALYSIS`, public render stage `TEMPLATE_CLOSURE`,
code `EXPRESSION_LIMIT_EXCEEDED`, reservation before AST/graph/case/input/list/decimal admission and
lazy execution, and zero boundary `ZERO_WRITE_AND_DOWNSTREAM`.

Every authored Mapping case consumes the static per-definition budget before presence or lazy
execution, including unselected cases and MappingDefinitions never demanded at runtime.

## Interface / seam

- Deepen the existing Rendering.internal `DesignInputExpressionCapacityGuard`; do not add a public
  API/SPI, route, DTO, persistence seam, app wiring, or a second guard.
- `ExpressionCapacityAdmission` must inspect every MappingDefinition in every frozen TemplateSnapshot
  and apply cap-043 to its admitted `cases` array before InputAdmission or lazy Definition evaluation.
- The existing isolated guard tracer proves exact cap-043 identity. The behavioral seam remains public
  `Evaluator.evaluate`: an unused Mapping with exactly 256 valid cases succeeds, while 257 valid but
  unselected cases fail before input resolution, capability state, Asset work, materialization,
  document, or output.
- This ticket does not implement cap-044 `expression.mappingCasesTotal`, later AST/graph/list/decimal
  axes, formal Ticket 19 record issuance, or a complete execution-class target.

## TDD, validation, and boundaries

- First add the missing guard enum test for `255/256/257` and obtain compile RED; then add a public
  Evaluator behavioral test and obtain a pre-integration RED before the smallest product change.
- Keep all Mapping cases structurally and semantically valid; leave the MappingDefinition unused so
  the above-limit test proves static admission of unselected/lazy content and zero downstream work.
- Run focused Rendering tests, the affected Java reactor, `render`, and `fast`; no server/full unless
  impact expands into app wiring.
- No OpenAPI/Web/Flyway/Profile registration/certification/provider/API Key/real data/cost, and no
  push/tag/PR. Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- The existing internal guard now owns cap-043 with the frozen inclusive maximum `256`, exact
  TEMPLATE_CLOSURE / EXPRESSION_LIMIT_EXCEEDED taxonomy, and
  `expression.mappingCasesPerDefinition` identity.
- `ExpressionCapacityAdmission` inspects every MappingDefinition in every frozen TemplateSnapshot and
  admits its complete authored `cases` array before InputAdmission or lazy Definition evaluation. An
  unused Mapping therefore cannot hide unselected cases from the static budget.
- TDD produced the expected missing-enum compile RED. The public Evaluator behavioral RED showed that
  an unused Mapping with 257 valid cases incorrectly reached `SealedDocument`; after wiring it rejects
  at the exact frozen limit with validation-target resolution, capability establish/restore, and state
  load/save all zero. The same real closure path with exactly 256 cases seals successfully.
- Focused guard/Evaluator/ExpressionEngine tests passed `135/135`; the affected Java reactor passed
  `542/542` (Schema `20`, Validation `13`, Template `86`, Asset `92`, Rendering `331`). `render`
  `.sdlc/evidence/20260829-164442-render/metadata.json` and `fast`
  `.sdlc/evidence/20260829-164530-fast/metadata.json` both report `passed` / A1.
- No cap-044 or later axis, T191-specific A2/A3, formal Ticket 19 record issuance, or complete
  execution-class claim was made. J0 remains pending and J1 was not approved. No public API/SPI,
  app, OpenAPI, Web, Flyway, Profile, provider, API Key, real data, cost, push, tag, or PR action occurred.
