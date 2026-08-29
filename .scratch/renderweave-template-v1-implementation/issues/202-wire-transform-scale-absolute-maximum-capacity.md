# T202 — Wire transform-scale absolute maximum capacity

Type: task
Status: resolved / automated_verified
Claimed by: none
Blocked by: T201 (resolved)

## Goal

Materialize Ticket 19 `RW-T19-S7-112`, `RW-T19-S9-017`, and `RW-T19-S9-019` through
DESIGN_INPUT_EXPRESSION cap-063. Every admitted non-Canvas Transform `scaleX` and `scaleY` must
have absolute value less than or equal to `100`, using `CANONICAL_DECIMAL`, `MAX_INCLUSIVE`,
terminal `DESIGN_PROPERTY_CONSTRAINT_INVALID / DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION`, public
stage `TEMPLATE_CLOSURE`, reservation before persisted write or Evaluation, and zero boundary
`ZERO_WRITE_AND_DOWNSTREAM`.

## Frozen scope and seam

- Deepen only the existing public `DesignDslAuthority.admit(rawUtf8)` path and injected
  `DesignInputExpressionCapacityAuthority`; add the cap-063 closed `Limit`, not another guard/API.
- For every present non-Canvas `transform`, process `scaleX` then `scaleY` and observe each once as
  canonical absolute magnitude before moving to the next authored Node. An absent optional Transform
  produces no scale observation.
- Parse/type validation and the existing non-zero scale contract precede capacity. Thus all zero
  spellings retain `DESIGN_VALUE_INVALID` with no scale-capacity observation; a permissive injected
  authority cannot admit zero. The maximum reservation then owns positive and negative above-boundary
  failure identity.
- Preserve canonical decimal rules without quantization, truncation, saturation, angle normalization,
  or exponent-token leakage. Binding overlay continues to reconstruct and re-admit through the same seam.
- cap-064 `geometry.rotationDegreesMin` and cap-065 `geometry.rotationDegreesMax` remain deferred.

## TDD and validation

- First add one tracer test through the real public Transform admission vector requiring ordered
  `scaleX`, `scaleY` absolute observations, and capture the missing-observation RED.
- Continue in vertical slices for positive/negative below/at/scale-64 above, canonical values, exact
  scale pointers, absent Transform, local-zero precedence, and authority reject/invalid/throw behavior.
- Run focused Geometry/public-surface tests, the full Template module, immutable component
  Java/TypeScript replay, canonical vectors, `template`, and `fast`. This is not app wiring, so
  unchanged server/full evidence may be reused.

## Boundary

- Do not wire cap-064..065, issue formal Ticket 19 records/class manifests, create/register a Profile,
  run a native Renderer build, provider, API Key, real data, production, J1, or A3 action.
- Do not modify the user's Image/Inference dirty work or stashes, and do not push, tag, or create a PR.
  Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- Implementation revision `93ad46fb39215e3f9e99e3649737e1efc425fcff` wires every present
  non-Canvas Transform through one parsed decimal value per scale and reserves cap-063 in authored
  `scaleX` then `scaleY` order using canonical absolute magnitude. Existing non-zero validation remains
  earlier than capacity, and absent Transform emits no scale observation.
- RED-to-GREEN coverage proves positive and negative below/at/scale-64 above boundaries, canonical
  magnitude and order, exact scale pointers, local-zero precedence, absent Transform, malformed/type
  precedence, and injected authority reject/invalid/throw fail-closed behavior. Focused tests are 40/40;
  the Template module is 176/176.
- Immutable component target v15 is SHA-256
  `b3e5829cd42275a3706e2c6f313939850ab75fcf33795dcbb98c302333d678f9`, 21566 bytes. Component
  evidence `.sdlc/evidence/20260829-205530-template-t202-component/` is Java primary 195/195 (A1) and
  TypeScript independent 195/195 with 2692 checks (A2), wired 63/65, remaining geometry 2, formal
  records 0.
- `template` evidence `.sdlc/evidence/20260829-205551-template/metadata.json` and `fast` evidence
  `.sdlc/evidence/20260829-205622-fast/metadata.json` both report passed/A1 at the implementation
  revision; Template Java/Python replay is 211/211. This non-app-wiring ticket did not repeat
  server/full. A3 absent, J0 pending, J1 not approved; no provider, API Key, real data, Profile,
  production, push, tag, or PR action occurred. Claim is released; cap-064 is the next frontier.
