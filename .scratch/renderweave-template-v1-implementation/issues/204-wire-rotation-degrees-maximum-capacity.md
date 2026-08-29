# T204 — Wire rotation-degrees maximum capacity

Type: task
Status: resolved / automated_verified
Claimed by: none
Blocked by: T203 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-113`, `RW-T19-S9-017`, and `RW-T19-S9-019` through
DESIGN_INPUT_EXPRESSION cap-065. Every admitted non-Canvas Transform `rotationDeg` must be less
than or equal to `360`, using `CANONICAL_DECIMAL`, `MAX_INCLUSIVE`, terminal
`DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`, public stage
`TEMPLATE_CLOSURE`, reservation before persisted write or Evaluation, and zero boundary
`ZERO_WRITE_AND_DOWNSTREAM`.

## Frozen scope and seam

- Deepen only the confirmed public `DesignDslAuthority.admit(rawUtf8)` path and injected
  `DesignInputExpressionCapacityAuthority`; add the cap-065 closed `Limit`, not another guard/API.
- Parse each present non-Canvas Transform `rotationDeg` exactly once and reserve the same canonical
  value against cap-064 then cap-065 before `scaleX` and `scaleY`. An absent optional Transform emits
  no Transform observation.
- Strict JSON type/decimal parsing and canonical expansion limits precede both capacity observations.
  Preserve arbitrary precision without modulo-360 normalization, quantization, truncation, saturation,
  or exponent-token leakage. Binding overlay continues to reconstruct and re-admit through the same seam.
- This closes product reservation wiring at 65/65 only. Exact execution-class target/manifests,
  preissuance readiness, formal record issuance, and executable lifecycle remain separate follow-up work.

## Test-first validation

- First strengthen the real public Transform tracer to require min then max observations before both
  scale observations and capture the missing cap-065 observation RED.
- Continue vertical slices for scale-64 below/at/above, canonical trailing zeros and negative zero,
  exact pointer, absent Transform, parser/canonical precedence, and authority reject/invalid/throw.
- Run focused Geometry/public-surface tests, the full Template module, immutable component
  Java/TypeScript replay, canonical vectors, `template`, and `fast`. This is not app wiring, so
  unchanged server/full evidence may be reused.

## Boundary

- Do not create the exact execution-class target/manifests, issue formal Ticket 19 records, register a
  Profile, run a native Renderer build, provider, API Key, real data, production, J1, or A3 action.
- Do not modify the user's Image/Inference dirty work or stashes, and do not push, tag, or create a PR.
  Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- Implementation `12a3f7e69b9a814358133c8d84ddc2b53da84789` adds the closed cap-065 limit and
  reserves each Transform's once-parsed canonical `rotationDeg` in min→max order before scaleX/scaleY,
  without modulo normalization. The strengthened public-seam tracer first produced the exact missing-max
  RED; below/at/above, trailing-zero/negative-zero, exact pointer, absent Transform,
  parser/canonical precedence, and authority reject/invalid/throw are covered.
- Focused Geometry/public-surface tests passed 48/48 and the Template module passed 184/184.
  Immutable component target v17 is SHA-256
  `2369720282648dbc02d91d6b9730762b639e04034cb68094000fdb23213cb0b4`, 21520 bytes,
  with all 57 bound artifacts byte/hash exact.
- Component evidence `.sdlc/evidence/20260829-212445-template-t204-component/` passed Java primary
  195/195 (A1) and TypeScript independent 195/195, 2692 checks (A2). Product reservation wiring is
  complete at 65/65 with remaining 0, while preissuance readiness, formal record issuance, and class
  executability remain false. `template` `.sdlc/evidence/20260829-212656-template/` and `fast`
  `.sdlc/evidence/20260829-212731-fast/` passed/A1 at the implementation revision; Template
  Java/Python remained 211/211.
- A3 is absent, J0 remains pending, and J1 was not approved. This ticket has no app wiring, so
  server/full were not repeated. The claim is released; an exact Design/Input/Expression execution-class
  target, required executor manifests, and independent preissuance replay form the next unregistered and
  unclaimed frontier. No user dirty work/stash was changed and no push/tag/PR occurred.
