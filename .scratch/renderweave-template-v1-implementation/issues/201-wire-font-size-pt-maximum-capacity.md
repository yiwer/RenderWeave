# T201 — Wire font-size pt maximum capacity

Type: task
Status: resolved / automated_verified
Claimed by: none (Codex released after verification)
Blocked by: T200 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-111`, `RW-T19-S9-017`, and `RW-T19-S9-019` through
DESIGN_INPUT_EXPRESSION cap-062. Every admitted Text Run `fontSizePt` must be less than or equal
to `4096`, using `CANONICAL_DECIMAL`, `MAX_INCLUSIVE`, terminal
`DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`, public stage
`TEMPLATE_CLOSURE`, reservation before persisted write or Evaluation, and zero boundary
`ZERO_WRITE_AND_DOWNSTREAM`.

## Frozen scope and seam

- Deepen only the existing public `DesignDslAuthority.admit(rawUtf8)` path and injected
  `DesignInputExpressionCapacityAuthority`; add the cap-062 closed `Limit`, not another guard/API.
- Reuse the decimal parsed once for each authored `text.runs[*].fontSizePt`. Observe cap-061 then
  cap-062 for that Run before moving to the next authored Run, preserving fail-fast ordering.
- Parse/type/canonical-byte failures dominate both reservations. The exclusive minimum reservation
  dominates the inclusive maximum reservation; the existing local positive fallback remains after
  capacity so a permissive authority cannot weaken the DesignDSL contract.
- Preserve canonical decimal rules without quantization, truncation, saturation, or exponent-token
  leakage. The frozen maximum boundary is below/at accepted and scale-64 above rejected at the exact
  Run pointer.
- `lineHeight.valuePt`, `letterSpacingPt`, Text `stroke.widthPt`, unitless values, and cap-063..065
  remain deferred.

## TDD and validation

- Obtain RED through the real public Text admission vector by requiring min→max observation order
  for two authored Runs before cap-062 exists.
- Cover below/at/scale-64 above, canonical nonzero handling, exact Run pointer, minimum-before-maximum
  fail-fast, and injected authority reject/invalid/throw behavior.
- Run focused Geometry/public-surface tests, the full Template module, immutable component
  Java/TypeScript replay, canonical vectors, `template`, and `fast`. This is not app wiring, so
  unchanged server/full evidence may be reused.

## Boundary

- Do not wire cap-063..065, issue formal Ticket 19 records/class manifests, create/register a
  Profile, run a native Renderer build, provider, API Key, real data, production, J1, or A3 action.
- Do not modify the user's Image/Inference dirty work or stashes, and do not push, tag, or create a PR.
  Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- Implementation commit `1f8fc3596f43d6844ed9cabde3ef23f9d4cd3a7b` added the closed cap-062
  `DesignDslAuthority.Limit` and reused each Run's single parsed decimal for ordered cap-061 then
  cap-062 reservation. Number/type/canonical-byte failures still dominate; minimum rejection stops
  before maximum, while the legacy positive fallback remains fail-closed behind a permissive authority.
- Public-seam TDD first captured the exact missing maximum observations, then proved authored Run
  order, below/at/scale-64 above, canonical values, exact pointers, minimum-before-maximum behavior,
  and authority reject/invalid/throw failure identity. Focused Geometry/public-surface passed 34/34;
  the Template module passed 170/170.
- Immutable component target v14 is
  `.scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v14.json`,
  SHA-256 `ab0c270c754312ca4c03a52061d6b5a7147cb894179a9a99a8a5e6232bf471b8`,
  21522 bytes, with exact immutable v13 predecessor binding, wired 62/65, remaining geometry 3, and
  formal records still 0. Component evidence `.sdlc/evidence/20260829-204006-template-t201-component/`
  records Java primary 195/195 (A1) and TypeScript independent 195/195 with 2692 checks (A2).
- `template` `.sdlc/evidence/20260829-204028-template/metadata.json` and `fast`
  `.sdlc/evidence/20260829-204102-fast/metadata.json` both report `passed` / A1 at the implementation
  revision; Template Java/Python replay remains 211/211. No app wiring changed, so server/full were
  not repeated. A3 is absent, J0 remains pending, and J1 was not approved. No formal issuance,
  Profile, Renderer build, provider, API Key, real-data, production, push, tag, or PR action occurred;
  user dirty work and stashes were untouched. Claim released; cap-063
  `geometry.transformScaleAbsoluteMax` is the next frontier.
