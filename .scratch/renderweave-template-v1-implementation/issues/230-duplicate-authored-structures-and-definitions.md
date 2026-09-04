# T230 — Duplicate authored structures and Definitions atomically

Type: task
Status: ready-for-agent
Blocked by: T227 (done)

## What to build

Add explicit local duplication for an authored node subtree and for a Definition. A duplicate is one atomic editor
command: it keeps valid external references, generates fresh identities for copied entities, and rewrites every internal
identity/domain/target reference needed to preserve the copied intent. It never delegates identity repair to the server.

## Acceptance criteria

- [ ] Structure and Data Sources expose named duplicate actions without adding a global keyboard shortcut; Canvas itself
  is not duplicable.
- [ ] Subtree duplication remaps copied node, Binding, loop and use identities plus all internal references as one closed
  operation, including Repeat, Conditional and TemplateUse content.
- [ ] Definition duplication creates a fresh definition identity, preserves legal external sources and rejects any copy
  whose references cannot remain lexically valid.
- [ ] A rejected duplicate leaves the working copy unchanged; a successful duplicate is one undo/redo and local-recovery
  step and survives save/reopen with distinct identities.
- [ ] Focused command/Shell/browser coverage, the web gate and fixed-point review pass; the ticket has its own commit.

## Test plan

- Add small identity-remap vectors plus focused Structure/Data Sources/Shell tests and one save/reopen product journey.
- Run only directly affected Web tests and the final web gate.

## Out of scope

- Whole-Template copy, cross-scope copy, clipboard interchange, multi-selection duplication, server-side ID repair,
  autosave, collaboration or generic graph-cloning infrastructure.

## Resolution

