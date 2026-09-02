# T221 — Open and resave the complete admitted DesignDSL without loss

Type: task
Status: done
Blocked by: T220 (done)

## What to build

Replace the obsolete empty `DesignDslKernel` HTTP/Web projection with the complete closed
`renderweave-design/1.0` wire contract already admitted by Template. Keep `NodeContractCatalog` and
`BindingPolicyCatalog` as semantic authorities, make OpenAPI describe the real HTTP body, regenerate the Web SDK, and
add an exact drift check between those authorities and the generated authoring surface.

Extend the Structured Editor recognition/codec only as needed so a current Template containing every admitted node,
placement, binding and definition shape opens in Structured mode and remains lossless through a targeted edit and real
save/reload. Raw Repair and Compatibility Read-only remain the fallback for malformed or future wire shapes.

## Acceptance criteria

- [x] OpenAPI and generated TypeScript expose closed discriminated types for all 18 v1 node kinds, placements,
  definitions, ValueSource variants, bindings and supporting value objects; `definitions` and `children` are no longer
  typed as empty/unknown arrays.
- [x] A contract/parity test fails if the OpenAPI/Web projection omits or invents a node kind, property identity or
  bindable property relative to `NodeContractCatalog` and `BindingPolicyCatalog`.
- [x] The production editor opens an all-kinds canonical fixture in Structured mode, changes one intended value, saves
  through the real Template endpoint and reloads with every unrelated semantic value preserved.
- [x] Existing revision conflict, canonical-size, INVALID confirmation, Raw Repair and Compatibility Read-only behavior
  remains fail closed.

## Test plan

- Add focused Java/OpenAPI parity tests and generated-contract checks.
- Add Web codec/session tests for all admitted variants and a product-route save/reload E2E without response stubbing.
- Run the affected `template`, `server` and `web` gates because this changes the cross-language HTTP contract.

## Out of scope

- New DesignDSL semantics, a public node-contract endpoint, Canvas layout/padding extensions or Renderer changes.
- Production authoring controls for every property; those enter through later vertical tickets.

## Resolution

The production authoring wire now projects the complete admitted `renderweave-design/1.0` contract instead of the old
empty kernel. OpenAPI and its generated TypeScript expose Canvas plus all 17 child-node variants, four placements, three
definition variants, five ValueSource variants, bindings and their closed supporting objects. `NodeContractCatalog`
owns the exact node member sets and `BindingPolicyCatalog` remains the binding authority; a safe-YAML parity suite fails
on invented or omitted kinds, members, targets, dangling references, or non-string Design token coercion. During review,
the Java authority was also brought back to the frozen closed-union rule for list/enum ValueType and invocation/loop
selector-domain members.

Web now uses one deep structural inspector for current open and import. Every authority-admitted canonical vector and a
shared exact-byte all-kinds fixture opens Structured; malformed trusted current fails before baseline disclosure or
readiness recheck, malformed local imports enter Raw Repair, and complete future profiles/wires remain Compatibility
Read-only. Canonical malformed recovery drafts fail closed as `DRAFT_UNSUPPORTED` without swallowing other errors.

The all-kinds fixture is admitted byte-for-byte by Template and covers 18 node kinds, four placements, three definitions
and all five ValueSource variants. A real product-route journey creates a Template, performs the formal INVALID
offer/confirmation, opens Structured, edits only `displayName`, saves through the normal transport, confirms INVALID,
reloads revision 2 and deep-compares every unrelated semantic value. It uses a fresh local PostgreSQL, the packaged
Spring application, Vite and Chromium without response stubbing or Renderer. The reusable runner now also retains the
validated PostgreSQL data directory and fails its evidence if `pg_ctl stop` cannot confirm shutdown.

Verification on the fixed candidate:

- Focused Java authority/parity/fixture/vector tests passed 18/18. The Template portion of `-Gate template` passed
  Schema 20/20, Template 194/194 and independent kernel 211/211; the gate later hit the pre-existing Renderer
  tricky-font `process-manifest.json` input-binding failure, reproduced unchanged at parent `bffc8161`.
- Isolated Node 24 `-Gate web` passed OpenAPI regeneration, typecheck, repository Web lint, 42 files / 381 tests and
  production build; evidence: `.sdlc/evidence/20260902-231422-web` in the review worktree.
- `-Gate server` completed all non-app modules, including Template 194/194, then the app module reported 24
  Testcontainers startup errors and zero assertion failures because Docker Desktop returned no valid environment. The
  same candidate packaged successfully with tests skipped, and the affected PostgreSQL path was exercised independently
  below rather than substituted with H2/SQLite.
- `tools/run-template-editor-roundtrip-e2e.ps1 -LocalPostgresBin D:\postgresql\bin` passed 1/1 on Chromium against
  revision `a28f9af3`; metadata recorded `workingTreeDirty=false`, `cleanupWarning=""`, and removal of the exact temp
  database directory. Evidence: `.scratch/t221-final-live/template-roundtrip-journey/` in the final worktree.
- Final fixed-point Standards and Spec reviews reported no remaining finding after the PostgreSQL cleanup guard was
  corrected. Scoped `git diff --check` and both PowerShell parsers passed.

T222 is now the frontier. RenderServer, backend DataSource and new layout semantics remain out of scope.
