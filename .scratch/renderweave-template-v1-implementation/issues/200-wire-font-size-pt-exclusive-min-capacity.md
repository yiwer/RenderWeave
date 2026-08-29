# T200 — Wire font-size pt exclusive-min capacity

Type: task
Status: resolved / automated_verified
Claimed by: none (Codex released after verification)
Blocked by: T199 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-111`, `RW-T19-S9-017`, and `RW-T19-S9-019` through
DESIGN_INPUT_EXPRESSION cap-061. Every admitted Text Run `fontSizePt` must be strictly greater
than `0`, using `CANONICAL_DECIMAL`, `MIN_EXCLUSIVE`, terminal
`DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`, public stage
`TEMPLATE_CLOSURE`, reservation before persisted write or Evaluation, and zero boundary
`ZERO_WRITE_AND_DOWNSTREAM`.

## Frozen scope and seam

- Deepen only the existing public `DesignDslAuthority.admit(rawUtf8)` path and injected
  `DesignInputExpressionCapacityAuthority`; add the cap-061 closed `Limit`, not another guard/API.
- Observe every authored `text.runs[*].fontSizePt` once in authored Run order. The same authority is
  reused automatically when Binding overlay reconstructs and re-admits a document during Evaluation.
- Parse and type-check the decimal before capacity. Reserve the canonical value before the legacy
  local `> 0` fallback so the default authority owns the frozen below/at terminal identity; a
  permissive injected authority still cannot bypass the existing positive-value contract.
- Preserve canonical decimal rules: all zero spellings observe `0`; nonzero values use
  `stripTrailingZeros().toPlainString()` without quantization, truncation, saturation, or exponent
  token leakage. Oversized plain expansion remains dominated by the canonical-byte failure.
- `lineHeight.valuePt`, `letterSpacingPt`, Text `stroke.widthPt`, unitless values, cap-062
  `fontSizePtMax`, and cap-063..065 remain deferred.

## TDD and validation

- Obtain RED through the real public Text admission vector, proving both Run observations are absent.
- Cover Run order, below/at/scale-64 above, canonical zero/nonzero, exact Run pointer, default and
  injected authority reject/invalid/throw fail-closed behavior, local fallback ordering, and
  oversized canonical expansion.
- Run focused Geometry/public-surface tests, the full Template module, immutable component
  Java/TypeScript replay, canonical vectors, `template`, and `fast`. This is not app wiring, so
  unchanged server/full evidence may be reused.

## Boundary

- Do not wire cap-062..065, issue formal Ticket 19 records/class manifests, create/register a
  Profile, run a native Renderer build, provider, API Key, real data, production, J1, or A3 action.
- Do not modify the user's Image/Inference dirty work or stashes, and do not push, tag, or create a PR.
  Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- Implementation commit `3f51f8203ad7669f66be67efac0a4e38d07a1958` added the closed cap-061
  `DesignDslAuthority.Limit` and routed every Text Run `fontSizePt` through the existing bounded
  canonical-decimal reservation helper. Number/type validation remains first; the capacity decision
  precedes the legacy positive-value fallback, so the default below/at terminal has the frozen
  property identity while a permissive injected authority still cannot admit a non-positive value.
- Public-seam TDD first captured two missing observations, then proved authored Run order, scale-64
  below/at/above, canonical zero/trailing-zero handling, exact Run pointers, type/canonical-expansion
  precedence, and authority reject/invalid/throw fail-closed behavior. Focused Geometry/public-surface
  passed 32/32; the Template module passed 168/168.
- Immutable component target v13 is
  `.scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v13.json`,
  SHA-256 `836f12acc9a586ad09f355a6487a65fe61d47c4c5c98ac69e96aeaba485408b2`,
  21490 bytes, with exact v12 predecessor binding, wired 61/65, remaining geometry 4, and formal
  records still 0. Component evidence `.sdlc/evidence/20260829-202538-template-t200-component/`
  records Java primary 195/195 (A1) and TypeScript independent 195/195 with 2692 checks (A2).
- `template` `.sdlc/evidence/20260829-202614-template/metadata.json` and `fast`
  `.sdlc/evidence/20260829-202648-fast/metadata.json` both report `passed` / A1 at the implementation
  revision; Template Java/Python replay remains 211/211. No app wiring changed, so server/full were
  not repeated. A3 is absent, J0 remains pending, and J1 was not approved. No formal issuance,
  Profile, Renderer build, provider, API Key, real-data, production, push, tag, or PR action occurred;
  user dirty work and stashes were untouched. Claim released; cap-062 `geometry.fontSizePtMax` is the
  next frontier.
