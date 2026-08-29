# T199 — Wire authored mm coordinate/length absolute capacity

Type: task
Status: resolved / automated_verified
Claimed by: none (Codex released after verification)
Blocked by: T198 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-110`, `RW-T19-S9-017`, and `RW-T19-S9-019` through
DESIGN_INPUT_EXPRESSION cap-060. Every admitted authored geometry coordinate or length expressed in
millimeters has absolute magnitude at most `10000`, using `CANONICAL_DECIMAL`, `MAX_INCLUSIVE`,
terminal `DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`, public
stage `TEMPLATE_CLOSURE`, reservation before persisted write or Evaluation, and zero boundary
`ZERO_WRITE_AND_DOWNSTREAM`.

## Frozen scope and seam

- Deepen only the existing public `DesignDslAuthority.admit(rawUtf8)` path and the injected
  `DesignInputExpressionCapacityAuthority`; add the cap-060 closed `Limit`, not another guard or API.
- Cover every v1 authored mm leaf admitted outside Canvas trim/bleed: ABSOLUTE coordinates/insets;
  all placement fixed sizes, min/max, and STACK/GRID margins; Frame/Stack/Grid/Text padding;
  corner radii and shape/layout stroke widths; Stack/Grid/Repeat packing gaps; FIXED Grid track
  lengths; PointMm and Path command coordinates.
- Canvas trim and bleed remain governed by their already-wired stricter cap-056..059 reservations.
  Do not double-reserve them under cap-060. Pt typography/stroke, unitless ratios/weights/scales,
  and degree rotation remain owned by cap-061..065 or their existing property contracts.
- Preserve authored traversal and member order. First parse/type and existing sign constraints, then
  reserve the canonical absolute magnitude once per legal mm leaf, before aggregate comparisons,
  bindings/children completion, canonical output, persisted write, or Evaluation. Signed `-0`
  observes `0`; negative coordinates/margins/insets observe their positive magnitude.
- Authority reject, invalid response, or throw fails closed with the exact authored pointer and
  cap-060 limit identity. Oversized plain-decimal expansion remains dominated by the existing
  canonical-byte failure before an observation is allocated.

## TDD and validation

- Obtain RED through real public admission documents that currently admit mm values without any
  cap-060 observation. Add vertical slices for placement, box/container, Repeat/Grid, and vector
  families, including positive/negative scale-64 below/at/above values and exact rejection pointers.
- Prove local sign validation still precedes cap-060 where negatives are forbidden, aggregate checks
  still follow leaf reservations, Canvas/bleed do not double-reserve, and authority reject/invalid/
  throw all fail closed.
- Run focused Template tests, the full Template module, immutable component Java/TypeScript replay,
  canonical vectors, `template`, and `fast`. This ticket has no app wiring, so unchanged server/full
  evidence may be reused.

## Boundary

- Do not wire cap-061..065, issue formal Ticket 19 records/class manifests, create a Profile, run a
  native Renderer build, provider, API Key, real data, production, J1, or A3 action.
- Do not modify the user's Image/Inference dirty work or stashes, and do not push, tag, or create a PR.
  Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- Implementation commit `86e7169cc19c33661ef7d536bc489fd2e006afc8` added the closed cap-060
  `DesignDslAuthority.Limit` and one shared bounded canonical-decimal reservation helper. Legal signed
  leaves reserve their absolute magnitude; positive/non-negative property validation runs first;
  min/max aggregate comparisons run after each leaf reservation. Canonical expansion still fails at
  `DESIGN_CANONICAL_COUNT` before an observation can be allocated.
- Every admitted authored mm family is wired at its existing traversal point: placement coordinates,
  insets, fixed sizes, min/max and margins; layout/Text padding, corner radii and mm stroke; Stack/Grid
  and both Repeat packing gaps; FIXED Grid tracks; PointMm and every Path coordinate. Canvas trim and
  bleed produce no cap-060 observation because cap-056..059 remain their stricter authorities.
- TDD captured four behavioral RED slices before the corresponding GREEN changes. The final public
  seam suite proves positive/negative scale-64 below/at/above, canonical absolute values, exact leaf
  pointers, local-validation/capacity/aggregate ordering, authority reject/invalid/throw fail-closed,
  and all frozen semantic families. Focused Geometry passed 23/23; the Template module passed 162/162.
- Immutable component target v12 is
  `.scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v12.json`,
  SHA-256 `9237af14b1bd8bab2232fc55c7d3499aa6e9b3477ad9d8f311c305ffd333b9cd`,
  21449 bytes, with exact v11 predecessor binding, wired 60/65, remaining geometry 5, and formal
  records still 0. Component evidence `.sdlc/evidence/20260829-201458-template-t199-component-final/` records
  Java primary 195/195 (A1) and TypeScript independent 195/195 with 2692 checks (A2).
- `template` `.sdlc/evidence/20260829-200955-template/metadata.json` and `fast`
  `.sdlc/evidence/20260829-201026-fast/metadata.json` both report `passed` / A1; Template Java/Python
  replay remains 211/211. No app wiring changed, so server/full were not repeated. A3 is absent, J0
  remains pending, and J1 was not approved. No formal issuance, Profile, Renderer build, provider,
  API Key, real-data, production, push, tag, or PR action occurred; user dirty work and stashes were
  untouched. Claim released; cap-061 `geometry.fontSizePtExclusiveMin` is the next frontier.
