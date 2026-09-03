# Domain docs

How engineering skills consume RenderWeave domain documentation.

## Layout

Multi-context layout: root `CONTEXT-MAP.md` holds the ubiquitous-language discipline, bounded-context
map, identity/path rules, lifecycles and cross-version authority; the glossary itself is split into
`docs/context/` slices (`schema-inference`, `live-admission`, `template-editor`, `asset`, `rendering`,
`conformance`).

## Before exploring

- Read root `CONTEXT-MAP.md`, then load **only the `docs/context/` slices its routing table selects**
  for the area being changed. Never bulk-read all slices.
- Read `docs/adr/README.md`, then open only the ADRs that touch the area. Large ADRs (flagged in the
  index) are read section-wise, not whole.
- Read the governing spec or approved spec delta named by the current ticket.
- For Template v1, also read `specs/changes/20260817-template-v1-implementation-authority.md` and only
  the frozen source records relevant to the ticket.

## Vocabulary

Use exact glossary terms from the loaded `docs/context/` slices in ticket titles, tests, interfaces and
review findings. Do not create synonyms for established concepts. If a required concept is genuinely
missing, note the gap and use `domain-modeling` to resolve it before spreading new terminology.
Domain docs hold structure and invariants only: ticket progress lives in `.scratch/` and git; retired
workflow records live in `docs/history/` and must not be written back into domain docs.

## ADR conflicts

Never silently override an ADR. State the conflict and why reopening the decision may be necessary,
for example:

> Contradicts ADR-0045 (Renderer process protocol) — reopening is required because the frozen wire
> contract cannot express the new terminal state.

If the current authority uniquely resolves an apparent conflict, follow it and update stale
documentation in the same ticket.
