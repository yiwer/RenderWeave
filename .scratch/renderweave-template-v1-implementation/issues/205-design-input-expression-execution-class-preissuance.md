# T205 — Materialize Design/Input/Expression execution-class preissuance

Type: task
Status: resolved / automated_verified
Claimed by: none
Blocked by: T204 (resolved)

## Goal

Promote the completed 65/65 Design/Input/Expression product reservation proof into the exact
`EXEC::DESIGN_INPUT_EXPRESSION::1.0` preissuance closure required by the frozen bootstrap catalog.
Freeze one exact Java + TypeScript product target, issue both required executor manifests, and replay
the full assigned candidate corpus independently without appending formal records.

## Frozen scope and seam

- Materialize `.scratch/renderweave-template-v1/design-input-expression/execution-class-target-v1.json`
  from an exact implementation commit. It must bind immutable component target v17, the observation
  adapter, candidate sources, assigned 195 Case + 195 Oracle subset/digest, executor entrypoints, gate,
  materializer, and independent closure verifier by Git blob hash and byte length.
- Issue exactly the frozen roles `java-semantic-authority` and
  `typescript-independent-authoring-replayer`. The Java role executes the real product authority and
  reservation proofs; the TypeScript role independently interprets the frozen authoring/capacity
  contract. Neither may share semantic helpers or access network/provider/production state.
- Add a Python-stdlib verifier which independently reconstructs all target bindings from the exact Git
  revision, the assigned corpus, the unchanged 58/58 formal-registry boundary, both executor manifests,
  and fresh Java/TypeScript reports. Only this verifier may report `preissuanceReady=true`.
- Add a bounded class gate which first runs the existing component gate, then the independent closure
  verifier. Reports contain identities, counts, digests, and boundary flags only; no RootDocument,
  authored payload, credentials, or external data.

## Test-first validation

- Capture a fail-closed RED while the exact class target/manifests are absent.
- Commit the materializer/verifier/gate implementation, then create target/manifests from that exact
  revision and require byte-identical materializer replay.
- Run the class gate, `template`, and `fast`; expand only if affected evidence requires it. This ticket
  has no app wiring or product-semantic delta.

## Boundary

- Do not append the 195 formal Case/Oracle records, update central executable lifecycle, register or
  certify a Profile, invoke Renderer/provider/API Key/real data/production, or claim J1/A3/READY.
- `recordIssuanceAllowed=false` and `executionClassExecutable=false`; formal issuance and central
  bootstrap updates remain a separate frontier ticket after this preissuance proof passes.
- Do not modify the user's Image/Inference dirty work or stashes, and do not push, tag, or create a PR.
  Claim evidence is A0; J0 pending and J1 not approved.

## Resolution

- Implementation revisions `3d2652cc7793024b78e46307faeeb764376052ac` and
  `bc027d4bb81732e2214642dce429881e529387f0` add the deterministic materializer, bounded class
  gate, and Python-stdlib closure verifier. The initial missing-target run failed before Maven/Node;
  two preflight findings then tightened the full v17 closed boundary and accepted Windows CRLF only
  for runtime evidence while retaining LF-only immutable authority files.
- Target revision `7d004bdc9363ddc19c4d6ba51de477a4668afcf4` commits the exact target and both required
  executor manifests. Target SHA-256 is
  `0629760e9ec6232709ffe20943733232996a379d17303a12dd1596e8118e765c` (5823 bytes), Java
  manifest SHA-256 is `3b582c15572b12f383be852f18ae49710d21c3b330de98c19cc454d24a645259`
  (1784 bytes), and TypeScript manifest SHA-256 is
  `da4e806f63566137fb8391358fa7dfa758bdff4bb1295535c858c074088ca68e` (1673 bytes).
  Materializer replay was byte-identical 3/3; assigned corpus digest is
  `d50b78e0bc2e6bf3bd3708784e4d90001d8d51e76f33068b10272a74ff3a4776`.
- Fresh class evidence `.sdlc/evidence/20260829-214510-template-t205-design-input-expression-class/`
  passed Java primary 195/195 (A1), TypeScript independent 195/195 with 2692 checks (A2), and
  Python independent closure 2/2 roles + 195/195 (A2). The class report SHA-256 is
  `9655593c63e219090d3dcadffd501fe3ee301042c4d7f6e94239355bbd8eb383`; a post-target replay
  produced byte-identical output. Formal registry remains 58 Case / 58 Oracle with this class 0/0,
  `preissuanceReady=true`, `recordIssuanceAllowed=false`, and `executionClassExecutable=false`.
- `template` `.sdlc/evidence/20260829-214555-template/` and `fast`
  `.sdlc/evidence/20260829-214627-fast/` passed/A1; Template Java/Python remained 211/211. This
  ticket had no app wiring or product-semantic delta, so server/full were not repeated. A3 is absent,
  J0 remains pending, and J1 was not approved. No formal records, central executable lifecycle,
  Profile, Renderer/provider, API Key, real data, production, user dirty work/stash, push/tag/PR were
  touched. The claim is released; formal 195+195 issuance is the next unregistered frontier.
