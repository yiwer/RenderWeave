# T229 — Complete Template creation reachability and result safety

Type: task
Status: ready-for-agent
Blocked by: T227 (done)

## What to build

Let an author create a Template against any visible exact StaticSchema instead of only the first catalog page, including
an exact handoff from a StaticSchema detail. Present readable, opaque and transport-unknown creation outcomes according
to their actual disclosure and certainty so an uncertain response never becomes an unsafe automatic retry.

## Acceptance criteria

- [ ] The creation surface supports searching and paging the complete visible StaticSchema catalog and can preselect one
  exact schema identity supplied by the product navigation flow.
- [ ] A readable commit opens the new Template; an opaque commit shows only its permitted receipt and does not pretend a
  readable editor baseline exists.
- [ ] A transport-unknown result refreshes the Template catalog, preserves the entered intent and warns that retrying may
  create a duplicate; it is not rendered as an ordinary safe retry.
- [ ] Keyboard and accessible status/error behavior remain complete for the exercised creation flow.
- [ ] Focused tests, one affected product-route browser journey, the web gate and fixed-point review pass; the ticket has
  its own commit.

## Test plan

- Add focused creation state/transport tests and one browser journey covering exact preselection, pagination and the
  three outcome classes.
- Run only directly affected Web tests and the final web gate.

## Out of scope

- Server idempotency keys, source lineage, automatic duplicate detection, Template lifecycle/history, cross-scope copy,
  server contract expansion or broad backend gates.

## Resolution

