# Issue tracker: local Markdown

Issues and specs for this repository live under `.scratch/`. No external issue tracker is configured.

## Conventions

- One effort per directory: `.scratch/<effort>/`.
- A spec, when needed, is `.scratch/<effort>/spec.md`.
- Each implementation ticket is a separate file: `.scratch/<effort>/issues/<NN>-<slug>.md`.
- Publish blockers before dependants so `Blocked by:` can reference stable ticket identifiers.
- Append discussion only when it contains a durable decision; Git already records ordinary edit history.

New implementation tickets use this repository binding of the `to-tickets` local template:

```markdown
# T211 — Observable outcome

Type: task
Status: ready-for-agent
Blocked by: T210 (done)

## What to build
## Acceptance criteria
- [ ] Observable criterion
## Test plan
## Out of scope
## Resolution
```

## Status and frontier

- `ready-for-agent`: blockers are done and the ticket can be implemented from a fresh context.
- `in-progress`: the current implementation run is working on this ticket.
- `done`: implementation, affected verification, review and commit are complete.
- `blocked`: a named unresolved dependency or required user decision prevents progress.
- The frontier is every `ready-for-agent` ticket whose blockers are `done`; choose the smallest identifier unless the goal states another priority.
- One `implement` run handles one ticket. Status is coordination metadata, not an assurance grade.

Historical Template tickets retain `resolved`, `automated_verified`, `claimed` and A/J language exactly as written. For dependency calculation only, a historical `resolved` ticket counts as `done`. Do not bulk-rewrite old tickets or historical evidence.

## Skill operations

- When a skill says “publish to the issue tracker”, create one ticket file under the relevant `.scratch/<effort>/issues/` directory.
- When a skill says “fetch the relevant ticket”, read the referenced file and its blockers.
- `to-tickets` owns decomposition and user approval; `implement` owns one ready ticket; `code-review` follows the fixed-point order in `plans/execution-protocol.md`.
- `map.md` may remain a navigation index for large efforts, but it is not a mandatory status ledger. `NOTES.md`, checkpoint files and `.sdlc/evidence/` updates are not ticket completion requirements.
- Do not create GitHub/GitLab issues, comments, labels or pull requests without explicit authorization.

## Template v1

The active effort directory is `.scratch/renderweave-template-v1-implementation/`. Tickets T01–T210 and the large map are historical input. New work continues at T211 using the compact format above and the approved Template authority.
