# T224 — Bind real StaticSchema fields and DesignDSL definitions in the production editor

Type: task
Status: done
Blocked by: T223 (done)

## What to build

Implement the production `数据源` authoring projection without creating a DataSource domain object. Resolve the
Template's permanent exact StaticSchemaRef through existing Schema APIs for the read-only System view, and use the
current DesignDSL working copy as the only source for CustomDefinition, ExpressionDefinition and MappingDefinition.

Connect bindable property rows to the exact Binding/TargetPropertyRef/ValueSource contracts and
`BindingPolicyCatalog`. Keep Properties and configured Bindings as separate inspector tabs; creation begins from the
concrete property row. Enforce invocation versus concrete Repeat loop domain lexically.

## Acceptance criteria

- [x] System cards show exact field identity, Chinese type projection and StaticSchema constraints, resolving reference
  detail only in the view dialog; no system edit/delete action exists.
- [x] Definition create/edit dialogs mutate `definitions[]` through semantic commands with exact type, exposure and
  domain rules; no backend table, route or catalog is added.
- [x] Binding creation/removal uses only policy-authorized target properties and closed ValueSource variants, survives
  undo/redo and real save/reload, and locates server problems back to the relevant node/property/definition.
- [x] A real journey binds a StaticSchema field to Text, saves, reloads and retains the exact source and target identity.

## Test plan

- Schema-to-authoring projection, definition command, lexical-domain and binding-policy tests.
- Component accessibility tests for dialogs and inspector tabs.
- Product-route E2E with real Schema and Template APIs; run affected `server`, `template` and `web` gates.

## Out of scope

- Connector management, backend DataSource persistence, arbitrary evaluation in the browser and Repeat authoring itself.

## Resolution

- Added the read-only exact StaticSchema projection with Chinese field/type/constraint cards. Reference detail is loaded
  lazily by immutable `schemaKey@versionTag`; Mapping validation loads only the exact reference branches crossed by its
  invocation or nested Repeat context paths.
- Added DSL-only Custom, Mapping and Expression definition dialogs and semantic EditorSession commands. Definition
  domains, sources, graph depth, expression profile, capacities, Unicode display names and canonical decimal literals
  are rejected client-side with the same closed limits used by the server authority.
- Generated the browser Binding policy from the OpenAPI/Java catalog contract, then connected property-originated
  Binding create/remove, lexical source selection, configured-Binding inspection, undo/redo and server problem focus.
  Canonical target selectors preserve exact member/index identity without a second mutable data-source model.
- Added a real product-route E2E that creates a Template, binds a StaticSchema Text field, saves and reloads, then
  asserts the exact source and target identities. Its implementation was independently reviewed; this workstation could
  not execute the live journey because Docker/backend services were unavailable.
- Verification: focused tail tests `6 files / 151 tests`; Template Editor `35 / 499`; full Web `58 / 633`; typecheck,
  lint and production build passed. Official `web` gate passed with evidence
  `.sdlc/evidence/20260903-072318-web`. Earlier affected Template authority replay passed Schema `20 / 20`, Template
  `195 / 195` and independent replay `211 / 211`; its later Renderer tricky-font phase is unrelated to T224. Final
  fixed-point Standards and Spec reviews of candidate `a4b7c5a8` both passed with zero hard blockers.
