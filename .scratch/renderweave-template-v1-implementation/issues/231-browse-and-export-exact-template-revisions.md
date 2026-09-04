# T231 — Browse and export exact Template revisions

Type: task
Status: ready-for-agent
Blocked by: T227 (done)

## What to build

Expose the existing immutable Template revision facts as a bounded author history. An authorized author can page revision
metadata, inspect one exact revision and download one integrity-checked exact revision envelope without changing current
or rewriting stored content. The same read surface remains meaningful for a terminally deleted Template once deletion is
implemented.

## Acceptance criteria

- [ ] Template history is bounded, stable across pages and discloses only authorized metadata; it never treats current
  readiness/report as a historical revision fact.
- [ ] Exact revision read re-canonicalizes persisted DesignDSL and verifies both bytes/hash before returning content;
  missing, hidden, deleted and corrupted cases remain distinct only to the degree authorization permits.
- [ ] Exact export seals the approved media envelope with source Template/revision/StaticSchema identity and verified
  content hash; it never exports partial or suspect content.
- [ ] The production editor provides a keyboard-accessible history/export flow and protects dirty local work before
  replacing any viewed context.
- [ ] Focused domain/PostgreSQL/API/Web verification, one product journey, fixed-point review and a ticket commit pass.

## Test plan

- Drive the public application/API through revision 0, revision 1, paged history, exact read/export and corruption
  fail-closed cases; exercise the production Web flow once.
- Run focused tests plus only the affected server/web gates.

## Out of scope

- Revision restore, whole-Template copy, Template deletion, semantic diff, history mutation, purge, certification,
  hash-registry replay or unrelated full gates.

## Resolution

