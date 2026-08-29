# T207 — Materialize Rendering Pipeline execution-class preissuance

Type: task
Status: resolved / automated_verified
Claimed by: none（single-writer claim released）
Blocked by: T206, T21, T22, T122, T123, T136–T182（all resolved）

## Goal

Promote the completed Rendering Pipeline product seams into the exact preissuance closure required for
bootstrap ordinal 4. Freeze one exact resource-free root render target, issue the required
`java-evaluator-and-sealer` and `rust-render-document-parser-and-engine` manifests, and independently
replay the assigned 156 Case + 156 Oracle candidate subset without appending formal records.

## Frozen scope and seams

- The Java role observes only the frozen `Evaluator.evaluate` seam. It must freeze the minimal
  `system-empty@v1` root closure, admit the exact RenderInput, seal canonical RenderDocument bytes and a
  Renderer Command, and execute every assigned capacity observation through existing product reservation
  seams. No duplicate guard, expected-value input, or product test bypass is allowed.
- The Rust role consumes the exact Java command/document bytes only through the public product chain
  `parse_command` → `validate_render_document` → resource-free preparation/Engine PNG →
  `seal_prepared_png_result`, yielding one complete terminal metadata/image pair. It must not reinterpret
  DesignDSL, call Java helpers, fetch a resource, or expose expected output to the target.
- A deterministic target materializer binds the exact implementation revision, observation adapter,
  candidate sources and assigned corpus digest, Java/Rust entrypoints, class gate, independent verifier,
  and current 253/253 formal-registry boundary by Git blob hash and byte length.
- A Python-stdlib verifier independently reconstructs every target/manifest/report binding and is the only
  component allowed to report `preissuanceReady=true`. Formal append and central executable lifecycle remain
  a separate frontier ticket.

## Test-first validation

- Capture a fail-closed RED while the T207 class gate/target/manifests are absent.
- Add one vertical Java-seal → Rust-terminal tracer, then the assigned capacity replay and independent
  closure. Commit implementation before materializing the immutable target/manifests from that exact revision.
- Require byte-identical target replay, then run the class gate, `render`, `template`, and `fast`; expand only
  if affected evidence requires it. No app wiring or product-semantic delta is planned.

## Boundary

- Do not append the 156 formal Case/Oracle records, update ordinal 4 to executable, issue Renderer Exact
  Output records, close Ticket 19, or claim Renderer/Template READY.
- Do not register/certify a Renderer Profile, perform a native/deployment/physical-host rehearsal, add or
  change API/OpenAPI/Web/Flyway/product routes, invoke provider/API Key/real data/production, or claim J1/A3.
- Keep `recordIssuanceAllowed=false` and `executionClassExecutable=false`; J0 is pending and J1 is not
  approved. Do not modify the user's Image/Inference dirty work or stash, and do not push, tag, or create a PR.

## Resolution

- Product guard/executor/gate implementation landed in `665f9568`; PATH-safe gate invocation in `3da7892a`;
  result-digest evidence alignment in `9782359c`; immutable target/manifests in `9f1a3f44`.
- The frozen target binds implementation revision `9782359c57f2ae0284408db64a68695ac4c3b4bd`, target SHA-256
  `a834125e93cd8debe2748b55a4f9b0d1dbd933214a15ca19a9068a70e1c99bd9`, and assigned-corpus digest
  `sha256:a91ad6cc0a8a3e52f926004af9b4115f3d2ef9ac1a9fcbf5481e0a655365ad4c`. Fresh materialization was
  3/3 byte-identical.
- Fresh class evidence at
  `.sdlc/evidence/template-v1-t207-rendering-pipeline-20260829-225411/` passed both required roles, all
  52 product-wired capacity axes, 156 Cases, 156 Oracles, and 1,248 assertions (A2). The exact Java-sealed
  command passed the Rust public parser/document/resource-free Engine/result chain and produced a complete
  794x1123 PNG without fetch or profile bypass.
- Fresh `render` evidence `.sdlc/evidence/20260829-225515-render/`, `template` evidence
  `.sdlc/evidence/20260829-225630-template/`, and `fast` evidence
  `.sdlc/evidence/20260829-225704-fast/` passed (A1).
- Formal registries remain 253/253, this class issued zero records, ordinal 4 remains pending,
  `recordIssuanceAllowed=false`, and `executionClassExecutable=false`. A3 is absent; J0 remains pending and
  J1 was not approved. Network/provider attempts were zero; no API key, real data, production, Profile,
  deployment, user dirty work/stash, push, tag, or PR was touched. Claim released.
