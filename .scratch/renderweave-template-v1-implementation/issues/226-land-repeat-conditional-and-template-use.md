# T226 — Author Repeat, Conditional and TemplateUse composition end to end

Type: task
Status: done
Blocked by: T225 (done)

## What to build

Land the three structural composition workflows on the exact DesignDSL contract. Repeat selects an eligible list
ValueSource, owns one authored item subtree and independently configures item/instance packing. Conditional selects one
boolean source and owns one true branch. TemplateUse selects a direct-reference context and an exact compatible active
Template, with explicit fills targeting child public CustomDefinitions.

The Structure tree always shows authored nodes once; repeated occurrences and suppressed conditional output are local
canvas projections, never persisted clones. Loop item/index sources appear only in the exact lexical Repeat domain.

## Acceptance criteria

- [x] Repeat handles eligible StaticSchema/definition lists, scalar item schemas, exact reference item schemas,
  ABSENT/empty/error states and source changes without silently replacing incompatible authored content.
- [x] Conditional TRUE/FALSE/ABSENT behavior updates local layout while retaining its authored branch and exact problem
  state.
- [x] TemplateUse filters by exact context schema/readiness, preserves loop context when nested in Repeat, edits fills,
  and survives real save/reload.
- [x] No primitive `{value}` TemplateUse adapter, array-as-TemplateUse selector, dynamic parent scope or backend source
  aggregate is introduced.

## Test plan

- Exact source eligibility/domain/compatibility unit tests and definite projection tests.
- Product-route E2E for one scalar Repeat, one reference Repeat/TemplateUse and Conditional suppression.
- Run affected `server`, `template` and `web` gates.

## Out of scope

- Else branches, runtime execution in the browser, renderer changes and unresolved primitive TemplateUse proposals.

## Resolution

- Landed structural authoring in `da3bfef3`, completed the reviewed workflows in `293a2636`, and fixed the remaining
  tokenized preview-control standard in `557f76ea`.
- Repeat now supports exact list/definition sources, scalar/reference item contexts, independent item/instance packing
  and VALUES/EMPTY/ABSENT/ERROR projections without persisted occurrence clones. Conditional retains its authored
  branch across local states. TemplateUse supports exact READY targets, whole/reference/loop/empty context selectors,
  ERROR/SKIP, typed PUBLIC fills and removal back to child defaults.
- Fixed-point review of `9dd2174a..557f76ea`: Standards PASS; Spec PASS.
- Verification: six focused Template Editor suites (156 tests), TypeScript and targeted ESLint passed; the official
  live Template roundtrip passed 5/5 at
  `C:/Users/Administrator/AppData/Local/Temp/renderweave-t226-final-20260904-002/template-roundtrip-journey`; `web` gate
  passed at `.sdlc/evidence/20260904-001045-web` (63 files / 751 tests plus production build).
- No backend or contract files changed, so `server`, `template`, `full`, hash verification and historical backtests
  were intentionally not replayed.
