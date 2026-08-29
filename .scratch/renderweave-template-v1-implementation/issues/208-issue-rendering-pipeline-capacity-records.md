# T208 — Issue Rendering Pipeline capacity records

Type: task
Status: resolved / automated_verified
Claimed by: unclaimed（single-writer claim released）
Blocked by: T207（resolved）

## Goal

Append exactly the frozen 156 Case + 156 Oracle records assigned to
`EXEC::RENDERING_PIPELINE::1.0`, independently replay the resulting formal registry, and atomically
advance bootstrap ordinal 4 to `EXECUTABLE_A2_REPLAYED` without issuing any later class.

## Frozen scope and seam

- The formal Case/Oracle registries must preserve the current 253-record bytes exactly and append only
  the Rendering Pipeline subset from the 525-record candidate corpus in candidate transport order.
- A deterministic fail-closed issuance target binds the T207 exact product target and both required
  executor manifests, the exact 253/253 prestate, candidate sources, assigned suffix/digest, expected
  409/409 poststate, central catalog/bootstrap/acceptance prestates, and every issuance/replay entrypoint.
- The materializer accepts only the exact prestate or a byte-identical complete poststate. Partial,
  duplicate, reordered, mutated, or cross-class issuance fails without repair or overwrite fallback.
- Node primary and Python independent postissuance replayers observe the public registry/manifest seam
  and independently verify prefix preservation, suffix identity, canonical JSONL/schema, probe/operator,
  capacity coverage/oracle closure, IDs/signatures/supersession, and exact target bindings.
- The same atomic closure sets ordinal 4 to `EXECUTABLE_A2_REPLAYED`, formal capacity counts to 363/525,
  total formal registries to 409/409, and refreshes SPEC Registry and Editor-derived evidence. Renderer
  Exact Output remains pending and Ticket 19 remains open.

## Test-first validation

- First capture a fail-closed RED while the T208 issuer/target/postissuance replay is absent.
- Commit the issuer/replayer/gate implementation, materialize the immutable target from that exact
  revision, require byte-identical target replay, then apply once and independently replay the poststate.
- Run the fresh T207 class gate before issuance, the T208 issuance gate, `template`, and `fast`; expand
  only if affected evidence requires it. This ticket has no app wiring or product-semantic delta.

## Boundary

- Do not issue the remaining 162 Renderer Exact Output capacity records, combined/non-capacity records,
  or Editor J1 records.
- Do not change product API/OpenAPI/Web/Flyway/Template/Rendering semantics, register or certify a
  Profile, invoke Renderer deployment/provider/API Key/real data/production, or claim J1/A3/READY/Ticket 19 closure.
- Do not modify the user's Image/Inference dirty work or stash, and do not push, tag, or create a PR.
  Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- Implementation `5da2bd93`, immutable target `6966be31`, derived-chain hardening `525db9bc`,
  `86ae31ee`, `c2f8e827`, and `731e7dcb`, and formal issuance `7530c324` completed the ticket.
- The exact 253/253 prefix is preserved and only the assigned 156 Case + 156 Oracle suffix was appended.
  Formal registries are 409/409, capacity issuance is 363/525, and ordinal 4 is
  `EXECUTABLE_A2_REPLAYED`.
- Issuance target SHA-256 is `eaa7a953...7da9c428`; assigned corpus digest is
  `a91ad6cc...5365ad4c`. Node/Python postissuance replay passed 4567/4567 checks; SPEC Registry
  replay passed 24519/24427 checks and Editor replay retained a 0/0 formal namespace.
- A1: fresh `template` evidence `20260829-233035-template` and `fast` evidence
  `20260829-233111-fast` passed. A2: fresh issuance evidence
  `20260829-232541-rendering-pipeline-record-issuance` and tracked Rendering/SPEC independent
  replay evidence passed. A3 absent; J0 pending; J1 not approved and not required for this
  provider-zero internal issuance ticket.
- Renderer Exact Output 162 isolated records, combined/non-capacity records, Editor J1, Profile,
  certification, READY, and Ticket 19 closure remain pending. Provider/network/API-key/real-data
  attempts were zero; no push, tag, PR, or user dirty-work mutation occurred. Claim released.
