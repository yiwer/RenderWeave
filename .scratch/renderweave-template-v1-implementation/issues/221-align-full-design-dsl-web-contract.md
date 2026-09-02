# T221 — Open and resave the complete admitted DesignDSL without loss

Type: task
Status: ready-for-agent
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

- [ ] OpenAPI and generated TypeScript expose closed discriminated types for all 18 v1 node kinds, placements,
  definitions, ValueSource variants, bindings and supporting value objects; `definitions` and `children` are no longer
  typed as empty/unknown arrays.
- [ ] A contract/parity test fails if the OpenAPI/Web projection omits or invents a node kind, property identity or
  bindable property relative to `NodeContractCatalog` and `BindingPolicyCatalog`.
- [ ] The production editor opens an all-kinds canonical fixture in Structured mode, changes one intended value, saves
  through the real Template endpoint and reloads with every unrelated semantic value preserved.
- [ ] Existing revision conflict, canonical-size, INVALID confirmation, Raw Repair and Compatibility Read-only behavior
  remains fail closed.

## Test plan

- Add focused Java/OpenAPI parity tests and generated-contract checks.
- Add Web codec/session tests for all admitted variants and a product-route save/reload E2E without response stubbing.
- Run the affected `template`, `server` and `web` gates because this changes the cross-language HTTP contract.

## Out of scope

- New DesignDSL semantics, a public node-contract endpoint, Canvas layout/padding extensions or Renderer changes.
- Production authoring controls for every property; those enter through later vertical tickets.

## Resolution
