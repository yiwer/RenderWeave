# T193 — Expression AST nodes per-expression capacity authority

Type: task
Status: resolved / automated_verified
Claimed by: Codex (single-writer)
Blocked by: T21, T126, T192 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-053` and DESIGN_INPUT_EXPRESSION cap-045:
`expression.astNodesPerExpression` is MAX_INCLUSIVE `4096`, with observed values `4095/4096/4097`,
contract stage `EXPRESSION_PARSE_AND_STATIC_ANALYSIS`, public render stage `TEMPLATE_CLOSURE`,
code `EXPRESSION_LIMIT_EXCEEDED`, reservation before AST/graph/case/input/list/decimal admission and
lazy execution, and zero boundary `ZERO_WRITE_AND_DOWNSTREAM`.

Every parsed AST node consumes the static per-Expression budget before presence or lazy execution,
including nodes in ExpressionDefinitions never demanded at runtime.

## Interface / seam

- Deepen the existing Rendering.internal `DesignInputExpressionCapacityGuard`; do not add a public
  API/SPI, route, DTO, persistence seam, app wiring, duplicate maximum, or second guard.
- `ExpressionParser` already counts AST nodes but owns a duplicate `MAX_AST_NODES` and projects
  overflow as parser-internal `ParseRejected`. Remove the duplicate authority, reserve each node through
  the guard, and return `ParseLimitExceeded` with the exact frozen Rendering problem.
- `ExpressionCapacityAdmission` already parses every ExpressionDefinition in every frozen snapshot;
  its existing `ParseLimitExceeded` branch must carry cap-045 before InputAdmission or lazy evaluation.
- Use balanced, semantically valid decimal ASTs so exact 4,096 reaches static analysis without Java
  stack depth becoming the tested limit. Public `Evaluator.evaluate` must prove an unused 4,096-node
  Expression seals and 4,097 nodes reject with zero downstream work.
- This ticket does not implement cap-046 `expression.astNodesTotal`, later graph/list/decimal axes,
  formal Ticket 19 record issuance, or a complete execution-class target.

## TDD, validation, and boundaries

- First add the missing guard enum test for `4095/4096/4097` and obtain compile RED. Then freeze parser
  and public Evaluator boundaries: current above-limit parser rejection/public internal-error behavior
  is the behavioral RED before the smallest production change.
- Run focused Rendering tests, the affected Java reactor, `render`, and `fast`; no server/full unless
  impact expands into app wiring.
- No OpenAPI/Web/Flyway/Profile registration/certification/provider/API Key/real data/cost, and no
  push/tag/PR. Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- The existing internal guard now owns cap-045. `ExpressionParser` no longer contains
  `MAX_AST_NODES`, a manual comparison, a duplicated limitId, or an AST-specific parser rejection kind.
- Every constructed AST node is admitted through the guard. A capacity problem immediately stops the
  parser control flow and is returned as `ParseLimitExceeded`; syntax failures remain `ParseRejected`.
  Existing closure admission therefore preserves the exact Rendering problem for every Expression,
  including definitions never demanded at runtime.
- TDD produced the expected missing-enum compile RED. The parser behavioral RED returned
  `ParseRejected` for 4,097 nodes, and the public Evaluator RED folded that into
  `RENDER_INTERNAL_ERROR`; after integration both return TEMPLATE_CLOSURE /
  EXPRESSION_LIMIT_EXCEEDED / `expression.astNodesPerExpression`.
- Balanced, semantically valid decimal ASTs prove 4,095 and 4,096 parser admission, 4,097 exact
  rejection, exact-at static analysis and document seal, and above-limit validation-target resolution,
  capability establish/restore, and state load/save all zero.
- Focused guard/Evaluator/ExpressionEngine tests passed `143/143`; the affected Java reactor passed
  `550/550` (Schema `20`, Validation `13`, Template `86`, Asset `92`, Rendering `339`). `render`
  `.sdlc/evidence/20260829-170140-render/metadata.json` and `fast`
  `.sdlc/evidence/20260829-170228-fast/metadata.json` both report `passed` / A1.
- No cap-046 or later axis, T193-specific A2/A3, formal Ticket 19 record issuance, or complete
  execution-class claim was made. J0 remains pending and J1 was not approved. No public API/SPI,
  app, OpenAPI, Web, Flyway, Profile, provider, API Key, real data, cost, push, tag, or PR action occurred.
