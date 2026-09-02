# T226 — Author Repeat, Conditional and TemplateUse composition end to end

Type: task
Status: blocked
Blocked by: T225

## What to build

Land the three structural composition workflows on the exact DesignDSL contract. Repeat selects an eligible list
ValueSource, owns one authored item subtree and independently configures item/instance packing. Conditional selects one
boolean source and owns one true branch. TemplateUse selects a direct-reference context and an exact compatible active
Template, with explicit fills targeting child public CustomDefinitions.

The Structure tree always shows authored nodes once; repeated occurrences and suppressed conditional output are local
canvas projections, never persisted clones. Loop item/index sources appear only in the exact lexical Repeat domain.

## Acceptance criteria

- [ ] Repeat handles eligible StaticSchema/definition lists, scalar item schemas, exact reference item schemas,
  ABSENT/empty/error states and source changes without silently replacing incompatible authored content.
- [ ] Conditional TRUE/FALSE/ABSENT behavior updates local layout while retaining its authored branch and exact problem
  state.
- [ ] TemplateUse filters by exact context schema/readiness, preserves loop context when nested in Repeat, edits fills,
  and survives real save/reload.
- [ ] No primitive `{value}` TemplateUse adapter, array-as-TemplateUse selector, dynamic parent scope or backend source
  aggregate is introduced.

## Test plan

- Exact source eligibility/domain/compatibility unit tests and definite projection tests.
- Product-route E2E for one scalar Repeat, one reference Repeat/TemplateUse and Conditional suppression.
- Run affected `server`, `template` and `web` gates.

## Out of scope

- Else branches, runtime execution in the browser, renderer changes and unresolved primitive TemplateUse proposals.

## Resolution
