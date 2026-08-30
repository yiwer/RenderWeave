# Domain docs

How engineering skills consume RenderWeave domain documentation.

## Before exploring

- Read root `CONTEXT.md` for the ubiquitous language, bounded contexts, deep-module ownership and dependency direction.
- Read ADRs in `docs/adr/` that touch the area being changed.
- Read the governing spec or approved spec delta named by the current ticket.
- For Template v1, also read `specs/changes/20260817-template-v1-implementation-authority.md` and only the frozen source records relevant to the ticket.

This repository currently uses one root context document. If `CONTEXT-MAP.md` is introduced later, follow it to the context-specific documents relevant to the task.

## Vocabulary

Use exact glossary terms from `CONTEXT.md` in ticket titles, tests, interfaces and review findings. Do not create synonyms for established concepts. If a required concept is genuinely missing, note the gap and use `domain-modeling` to resolve it before spreading new terminology.

## ADR conflicts

Never silently override an ADR. State the conflict and why reopening the decision may be necessary, for example:

> Contradicts ADR-0045 (Renderer process protocol) — reopening is required because the frozen wire contract cannot express the new terminal state.

If the current authority uniquely resolves an apparent conflict, follow it and update stale documentation in the same ticket.
