# T232 — Restore or copy an exact Template revision safely

Type: task
Status: ready-for-agent
Blocked by: T231 (done)

## What to build

Turn an exact historical revision into a deliberate new authoring baseline in the two approved ways. Restore copies an
old revision onto an ACTIVE Template by appending a new current revision; whole-Template copy creates an independent
same-owner Template at revision 0 from an exact source revision, including a source whose Template is terminally deleted.

## Acceptance criteria

- [ ] Restore requires a clean authoring decision and the current expected revision, preserves local entity identities,
  appends rather than rewinds, and adopts the server canonical result as a fresh editor baseline.
- [ ] Copy pins the exact source Template/revision, preserves DesignDSL local identities but no revision history or
  lineage, and creates only within the trusted same-owner scope.
- [ ] Both operations rerun canonical admission and current dependency validation; complete dependency-only errors use
  the existing bounded two-stage INVALID confirmation while hard errors and drift always write nothing.
- [ ] Opaque and transport-unknown copy results follow the creation safety behavior and cannot trigger automatic retry.
- [ ] Focused domain/PostgreSQL/API/Web verification, representative product journeys, fixed-point review and a ticket
  commit pass.

## Test plan

- Exercise revision 0 → save revision 1 → restore revision 0 as revision 2 and copy revision 1 as a new revision 0,
  including conflict, authorization, confirmation drift and result-unknown cases.
- Run focused tests plus only affected server/web gates.

## Out of scope

- Cross-scope transfer, deep-copying Template/Asset dependencies, automatic field migration, lifecycle reactivation,
  source lineage, semantic diff, server idempotency keys or broad replay gates.

## Resolution
