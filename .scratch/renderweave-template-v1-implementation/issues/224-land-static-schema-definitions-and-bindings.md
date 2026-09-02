# T224 — Bind real StaticSchema fields and DesignDSL definitions in the production editor

Type: task
Status: blocked
Blocked by: T223

## What to build

Implement the production `数据源` authoring projection without creating a DataSource domain object. Resolve the
Template's permanent exact StaticSchemaRef through existing Schema APIs for the read-only System view, and use the
current DesignDSL working copy as the only source for CustomDefinition, ExpressionDefinition and MappingDefinition.

Connect bindable property rows to the exact Binding/TargetPropertyRef/ValueSource contracts and
`BindingPolicyCatalog`. Keep Properties and configured Bindings as separate inspector tabs; creation begins from the
concrete property row. Enforce invocation versus concrete Repeat loop domain lexically.

## Acceptance criteria

- [ ] System cards show exact field identity, Chinese type projection and StaticSchema constraints, resolving reference
  detail only in the view dialog; no system edit/delete action exists.
- [ ] Definition create/edit dialogs mutate `definitions[]` through semantic commands with exact type, exposure and
  domain rules; no backend table, route or catalog is added.
- [ ] Binding creation/removal uses only policy-authorized target properties and closed ValueSource variants, survives
  undo/redo and real save/reload, and locates server problems back to the relevant node/property/definition.
- [ ] A real journey binds a StaticSchema field to Text, saves, reloads and retains the exact source and target identity.

## Test plan

- Schema-to-authoring projection, definition command, lexical-domain and binding-policy tests.
- Component accessibility tests for dialogs and inspector tabs.
- Product-route E2E with real Schema and Template APIs; run affected `server`, `template` and `web` gates.

## Out of scope

- Connector management, backend DataSource persistence, arbitrary evaluation in the browser and Repeat authoring itself.

## Resolution
