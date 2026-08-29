# T203 — Wire rotation-degrees minimum capacity

Type: task
Status: resolved / automated_verified
Claimed by: none
Blocked by: T202 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-113`, `RW-T19-S9-017`, and `RW-T19-S9-019` through
DESIGN_INPUT_EXPRESSION cap-064. Every admitted non-Canvas Transform `rotationDeg` must be greater
than or equal to `-360`, using `CANONICAL_DECIMAL`, `MIN_INCLUSIVE`, terminal
`DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`, public stage
`TEMPLATE_CLOSURE`, reservation before persisted write or Evaluation, and zero boundary
`ZERO_WRITE_AND_DOWNSTREAM`.

## Frozen scope and seam

- Deepen only the confirmed public `DesignDslAuthority.admit(rawUtf8)` path and injected
  `DesignInputExpressionCapacityAuthority`; add the cap-064 closed `Limit`, not another guard/API.
- For every present non-Canvas `transform`, parse `rotationDeg` once and reserve its canonical value
  before `scaleX` and `scaleY`, following the frozen Transform member order. An absent optional
  Transform produces no rotation observation.
- Strict JSON type/decimal parsing and canonical expansion limits precede capacity. Preserve the exact
  arbitrary-precision degree value: no modulo-360 normalization, quantization, truncation, saturation,
  or exponent-token leakage. Binding overlay continues to reconstruct and re-admit through the same seam.
- cap-065 `geometry.rotationDegreesMax` remains deferred; this ticket does not claim the upper bound.

## TDD and validation

- First add one tracer test through the real public Transform admission vector requiring the canonical
  cap-064 observation before existing scale observations, and capture the missing-observation RED.
- Continue in vertical slices for scale-64 below/at/above, canonical negative zero/trailing zeros, exact
  rotation pointer, absent Transform, parser/canonical precedence, and authority reject/invalid/throw.
- Run focused Geometry/public-surface tests, the full Template module, immutable component
  Java/TypeScript replay, canonical vectors, `template`, and `fast`. This is not app wiring, so
  unchanged server/full evidence may be reused.

## Boundary

- Do not wire cap-065, issue formal Ticket 19 records/class manifests, create/register a Profile, run a
  native Renderer build, provider, API Key, real data, production, J1, or A3 action.
- Do not modify the user's Image/Inference dirty work or stashes, and do not push, tag, or create a PR.
  Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- Implementation `c235ca095f2c518cb9736a8158064c276a0f9d73` adds the closed cap-064 limit and
  observes each present Transform's once-parsed canonical `rotationDeg` before `scaleX`/`scaleY`,
  without modulo normalization. The public-seam tracer first produced the expected missing-observation
  RED; below/at/above, negative-zero/trailing-zero, exact pointer, absent Transform,
  parser/canonical precedence, and authority reject/invalid/throw are covered.
- Focused Geometry/public-surface tests passed 45/45 and the Template module passed 181/181.
  Immutable component target v16 is SHA-256
  `abedd1ddc9ffe07b4a3b032e6aa23bcc85046ce541c42be12dde44f3650653f9`, 21603 bytes.
- Component evidence `.sdlc/evidence/20260829-211202-template-t203-component/` passed Java primary
  195/195 (A1) and TypeScript independent 195/195, 2692 checks (A2), with 64/65 wired,
  remaining geometry 1, and zero formal records. `template`
  `.sdlc/evidence/20260829-211232-template/` and `fast`
  `.sdlc/evidence/20260829-211310-fast/` passed/A1 at the implementation revision; Template
  Java/Python remained 211/211.
- A3 is absent, J0 remains pending, and J1 was not approved. This ticket has no app wiring, so
  server/full were not repeated. The claim is released; cap-065 `geometry.rotationDegreesMax`
  is the next unregistered and unclaimed frontier. No user dirty work/stash was changed and no
  push/tag/PR occurred.
