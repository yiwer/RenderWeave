# T206 — Issue Design/Input/Expression capacity records

Type: task
Status: resolved / automated_verified
Claimed by: none（single-writer claim released）
Blocked by: T205（resolved）

## Goal

Append exactly the frozen 195 Case + 195 Oracle records assigned to
`EXEC::DESIGN_INPUT_EXPRESSION::1.0`, independently replay the resulting formal registry, and
atomically advance bootstrap ordinal 3 to `EXECUTABLE_A2_REPLAYED` without issuing any later class.

## Frozen scope and seam

- The formal Case/Oracle registries must preserve the current 58-record bytes exactly and append only
  the Design/Input/Expression subset from the 525-record candidate corpus in candidate transport order.
- A deterministic fail-closed issuance target binds the T205 exact product target and both required
  executor manifests, the exact 58/58 prestate, candidate sources, assigned suffix/digest, expected
  253/253 poststate, central catalog/bootstrap/acceptance prestates, and every issuance/replay entrypoint.
- The materializer accepts only the exact prestate or a byte-identical complete poststate. Partial,
  duplicate, reordered, mutated, or cross-class issuance fails without repair or overwrite fallback.
- Node primary and Python independent postissuance replayers observe the public registry/manifest seam
  and independently verify prefix preservation, suffix identity, canonical JSONL/schema, probe/operator,
  capacity coverage/oracle closure, IDs/signatures/supersession, and exact target bindings.
- The same atomic closure sets ordinal 3 to `EXECUTABLE_A2_REPLAYED`, formal capacity counts to 207/525,
  total formal registries to 253/253, and refreshes SPEC Registry and Editor derived evidence. Rendering
  and Renderer Exact Output classes remain pending and Ticket 19 remains open.

## Test-first validation

- First capture a fail-closed RED while the T206 issuer/target/postissuance replay is absent.
- Commit the issuer/replayer/gate implementation, materialize the immutable target from that exact
  revision, require byte-identical target replay, then apply once and independently replay the poststate.
- Run the fresh T205 class gate before issuance, the T206 issuance gate, `template`, and `fast`; expand
  only if affected evidence requires it. This ticket has no app wiring or product-semantic delta.

## Boundary

- Do not issue the remaining 318 capacity records, combined/non-capacity records, or Editor J1 records.
- Do not change product API/OpenAPI/Web/Flyway/Template/Rendering semantics, register or certify a
  Profile, invoke Renderer/provider/API Key/real data/production, or claim J1/A3/READY/Ticket 19 closure.
- Do not modify the user's Image/Inference dirty work or stash, and do not push, tag, or create a PR.
  Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- Issuer/replayer/gate implementation landed in `7d10f776`; transport hardening in `74ebc88c`; immutable
  target binding in `eb843497`; formal issuance and central lifecycle update in `e75ba4c4`.
- Target SHA-256 is `b5227039abe50a0b2f5f14ba90070e01dc28e34c783e141cd13ed67211dbed9d`;
  assigned corpus digest is
  `sha256:d50b78e0bc2e6bf3bd3708784e4d90001d8d51e76f33068b10272a74ff3a4776`.
- The original 58/58 prefix remained byte-identical. Exactly 195 Cases and 195 Oracles were appended,
  yielding 253/253 formal records and 207/525 issued capacity records. Ordinal 3 is now
  `EXECUTABLE_A2_REPLAYED`; Rendering and Renderer Exact Output remain pending and Ticket 19 remains open.
- Fresh preissuance evidence is
  `.sdlc/evidence/20260829-221033-template-t206-design-input-expression-preissuance/`; fresh issuance evidence
  is `.sdlc/evidence/20260829-221211-template-t206-design-input-expression-issuance/`. Node primary and Python
  independent postissuance replay each passed 5,632 checks (A2), and poststate materialization replay was
  6/6 byte-identical.
- SPEC Registry 1.15 replay passed 23,377 Node and 23,285 Python checks over 404 artifacts. Editor-derived
  replay passed 38 primary and 21,555 independent checks while retaining zero formal Editor records.
- The first template-static replay correctly exposed a Git-for-Windows long-path transport failure at
  `.sdlc/evidence/20260829-221324-template/`; binding `GIT_WORK_TREE` to the real repository root removed that
  cwd-dependent failure without changing authority bytes. Fresh `template` evidence
  `.sdlc/evidence/20260829-221731-template/` and `fast` evidence
  `.sdlc/evidence/20260829-221902-fast/` both passed (A1).
- A3 is absent; J0 remains pending and J1 was not approved. No app wiring or product-semantic delta was
  introduced, so server/full were not repeated. Provider attempts remained zero; no API key, real data,
  production, user dirty work/stash, push, tag, or PR was touched. Claim released.
