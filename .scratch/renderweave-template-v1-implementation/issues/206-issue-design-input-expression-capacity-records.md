# T206 — Issue Design/Input/Expression capacity records

Type: task
Status: active / claimed
Claimed by: Codex `/root`（single-writer）
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

