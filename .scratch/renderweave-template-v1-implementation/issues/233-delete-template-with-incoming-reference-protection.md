# T233 — Delete a Template with incoming-reference protection

Type: task
Status: ready-for-agent
Blocked by: T231 (done), T232 (done)

## What to build

Let an authorized author terminally delete an ACTIVE Template at an expected revision. The mutation must atomically check
incoming ACTIVE-current TemplateRef usage: referenced targets remain unchanged with a bounded, disclosure-safe summary;
unreferenced targets become DELETED while their permanent identity, StaticSchema binding, current and complete revision
history remain intact.

## Acceptance criteria

- [ ] Deletion requires the exact expected revision and independent delete authorization; conflict, hidden, forbidden,
  already-deleted and unavailable outcomes write nothing and disclose no extra object facts.
- [ ] Any incoming ACTIVE-current TemplateRef blocks deletion without force or cascade and returns only an authorized,
  bounded reference summary.
- [ ] Successful deletion removes the Template from the active catalog and from active dependency resolution; current,
  save, render and new references fail with their approved terminal behavior.
- [ ] Exact history/export/copy remain available under their own authorization after deletion; editing, lifecycle restore,
  identity reuse and purge remain impossible.
- [ ] Focused domain/PostgreSQL/API/Web verification, one reference-blocked then successful product journey, fixed-point
  review and a ticket commit pass.

## Test plan

- Create A → B reference, prove B deletion blocked, remove the reference, delete B, then verify terminal product behavior
  and retained exact history/export/copy.
- Run focused tests plus only affected server/web gates.

## Out of scope

- Force delete, cascade delete, Template lifecycle restore, purge, retention automation, workspace ownership changes,
  certification or unrelated gate/hash/backtest expansion.

## Resolution
