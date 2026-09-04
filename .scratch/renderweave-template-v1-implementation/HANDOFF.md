# Template v1 skills-first handoff

> Updated: 2026-09-03
> Audience: the next agent landing the approved Template Designer prototype decisions into the production editor,
> then continuing additive Template v1.

## Outcome at handoff

The reference prototype has been validated and production landing is complete through T224. T225 has a substantial
candidate implementation commit, but it is **not complete**: fixed-point review and a successful real save/reload
journey are still missing. T226 and T227 remain blocked behind it. Template v1, the formal Renderer Profile and
Authoritative Preview are not READY.

## Start state

| Fact | Current value |
|---|---|
| Repository | `D:\Yiwer\code\RenderWeave` |
| Branch / HEAD | `main` / `dd596f692e9d28d1396a21b88592d470508cdf21` |
| Upstream relation | `main` is 141 commits ahead of `origin/main`; nothing from this effort was pushed |
| Goal tracker | `paused`; objective remains continuous Template v1 completion |
| Current ticket | T225 `in-progress` |
| T225 base / candidates | `5e0e2d84` / `b6f40ef8`, plus incremental fix `b16f621f` |
| Next approved tickets | T226, then T227 |

The separate IMAGE_ONLY admission accumulation present at the original handoff was classified on 2026-09-03 into
`2a1c2843` (offline OCR build context), `dcb8b8ef` (recovery and secure admission implementation) and `be3222c8`
(authority/history records). Its local review packs remain payload-bearing workspace artifacts and are intentionally
not tracked. T225 remains in progress after incremental fix `b16f621f`; re-anchor with `git status` before continuing.

The commit after the T225 candidate, `dd596f69`, is the approved progressive-disclosure documentation restructure.
Do not review T225 as `5e0e2d84..HEAD`, because that would mix this later documentation commit into the ticket diff.

## Skills-first read order

1. Read `CONSTITUTION.md`.
2. Read `docs/agents/issue-tracker.md` and `plans/execution-protocol.md`.
3. Fetch T225 and its blocker T224 from `.scratch/renderweave-template-v1-implementation/issues/`.
4. Route through `CONTEXT-MAP.md` to `docs/context/template-editor.md`. Add `asset.md` or `rendering.md` only when the
   selected ticket actually crosses those seams.
5. Read `docs/adr/README.md`, then only the relevant Template ADR sections. T225 is mainly governed by the frozen
   DesignDSL/placement contract; T226 also crosses TemplateRef and lexical-domain semantics. Renderer work remains
   governed by ADR-0044 and ADR-0045.
6. Read the current ticket's relevant sections of
   `specs/changes/20260817-template-v1-implementation-authority.md` and frozen checkpoint records.
7. Call `get_goal`. The goal was paused for this handoff; report/resume it truthfully before implementation.

`plans/renderweave-template-v1-plan.md` is now only a pointer. `map.md`, T01-T210 cards, `docs/history/` and
`.sdlc/evidence/` are historical navigation/build logs, not a second status machine. Do not revive A0-A3/J0-J1,
claim, Phase or checkpoint bookkeeping.

## Completed runway

| Ticket / commit | Delivered result |
|---|---|
| T211-T217 / through `38d46ece` | Portable font fixture, exact offline build closure, native Text-to-PNG, fail-closed glyph paths and exact JPEG output. Renderer remains `NOT_CERTIFIED` and Profile `NOT_REGISTERED`. |
| T218 / `d509863c` | Explicit loopback-only Candidate Preview through saved Template current, production Evaluator and real Renderer process adapter; formal preview remains fail closed. |
| T219 / `cb2be4a4` | Real browser create/edit/save → candidate PNG/JPEG loop, integrity checks and accessibility validation; durable findings are in `docs/validation/template-v1-candidate-preview-v1.md`. |
| T220 / `8e46155b` | Throwaway A/B/C Template Designer prototype. Variant A and its interaction vocabulary became the selected production direction. |
| T221 / `0d9bba21` | Full admitted `renderweave-design/1.0` Web wire recognition and lossless open/resave path. |
| T222 / `e7bfd9bd` | Production `/templates/:templateId` authoring shell, canonical EditorSession commands, Structure tree, inspector, canvas interactions and undo/redo. |
| T223 / `3d3d1820` | All admitted visual leaves plus real ACTIVE Image/FONT Asset authoring; only canonical AssetRefs persist. |
| T224 / `5e0e2d84` | Exact StaticSchema System projection, DSL-owned Custom/Mapping/Expression definitions and policy-authorized property bindings. |

Reference and production code must remain distinct until T227:

- Throwaway reference: `web/src/prototype/template-designer/` and `/prototype/template-designer?variant=A|B|C`.
- Production editor: `web/src/features/template-editor/` and `/templates/:templateId`.

Do not copy the prototype's in-memory `DraftBox[]`, Canvas layout delta, browser-only domain shapes or persistence
shortcuts into production. T221-T224 deliberately use the canonical DesignDSL working copy and real APIs instead.

## T225: exact state and next actions

Candidate commit `b6f40ef8` changes 21 ticket-owned Web/E2E files (about +6.6k/-0.2k lines). It adds:

- `template-editor-definite-layout.ts`: pure, non-authoritative projection from persisted DesignDSL for Group, Frame,
  Stack and Grid, including definite HUG containers, padding, signed margins, alignment, FIXED/FILL bounds, stable
  Stack allocation, ordered Grid FIXED/AUTO/FRACTION solving and fail-closed cycle/intrinsic errors.
- `template-editor-grid-tracks.ts`: lossless compact `12, auto, 1*, 2*` syntax over formal track objects.
- Semantic placement/property commands, reparent conversion and Group compensation without a parallel geometry store.
- Inspector controls and canvas projections for managed layout; managed moves are transient and restore, while legal
  FREE geometry changes persist. Selection chrome remains an editor-only overlay.
- Unit/component coverage and an expanded real-route Stack/Grid roundtrip journey.

Verification truth:

- Web gate passed on the candidate working tree: 60 files / 721 tests, typecheck/lint and a 2,190-module build
  (`.sdlc/evidence/20260903-093528-web`). It has not been replayed from a clean worktree at `b6f40ef8`.
- Template Java 195/195, independent kernel 211/211 and AssetRef replay 3/3 passed. The composite gate then failed at
  the pre-existing tricky-font `INPUT_BINDING: renderer/process-manifest.json` mismatch
  (`.sdlc/evidence/20260903-090018-template`). The immutable record binds `f814c98e...`, while the current manifest is
  `7ff83532...`.
- No T225 real roundtrip passed. One attempt lacked the app JAR; a second ended with
  `You cannot call a method on a null-valued expression` during orchestration/cleanup. Treat the journey as unverified.
- T225 remains `in-progress`; its acceptance boxes are unchecked and Resolution is empty.

Continue T225 in this order:

1. Run `code-review` on explicit fixed point `5e0e2d84..b6f40ef8`, separating Standards and Spec findings. Do not
   include `dd596f69` or the dirty IMAGE_ONLY diff.
2. Fix blocking findings with focused tests. Because another commit follows the candidate, avoid history rewriting in
   the dirty shared worktree; use a narrowly staged T225 follow-up commit if needed.
3. Build the app, then rerun:
   `powershell -ExecutionPolicy Bypass -File tools/run-template-editor-roundtrip-e2e.ps1 -LocalPostgresBin D:\postgresql\bin`.
   Diagnose the null orchestration failure if it recurs; do not weaken cleanup or substitute H2/SQLite.
4. Replay `-Gate web`. Run `-Gate template` and report the Template passes separately from the known immutable
   Renderer-manifest blocker unless a separately approved successor-authority ticket has repaired it.
5. Only after review, affected verification and a successful Stack/Grid save/reload journey: check acceptance boxes,
   write a concise Resolution, set T225 to `done`, and commit only T225-owned paths.

## Approved frontier after T225

- **T226 — Repeat, Conditional and TemplateUse composition.** It owns exact list/boolean source eligibility, lexical
  Repeat domains, authored-once versus virtual occurrences, TemplateUse exact schema/readiness filtering and fills.
  It does not invent a primitive `{value}` adapter, browser runtime evaluation or backend DataSource aggregate.
- **T227 — safety closure and prototype retirement.** It revalidates conflict reconciliation, INVALID confirmation,
  recovery/import, Raw Repair, Compatibility Read-only, keyboard/a11y and representative real-route journeys, then
  removes or development-isolates obsolete prototype routes/state. It does not authorize Renderer certification or
  production rollout.

These tickets are already published and approved. Each ready ticket gets one `implement` run, affected gates, local
commit and fixed-point `code-review`.

## Work still needed after T227

T227 finishes the approved production-editor landing set, not all Template v1. Before declaring the goal complete,
run a fresh spec-to-code gap analysis and use `to-tickets` to present the next tracer-bullet decomposition for user
approval. Known residual groups include:

- Append-only repair for the legacy Renderer process-manifest/tricky-font authority, using a successor record or
  versioned historical-manifest lookup rather than rewriting frozen v1/v2/v3 bytes.
- Remaining exact Renderer surface: unsupported Text/RenderNode variants, LayoutTrace multipart and the deferred 162
  exact output/conformance records.
- Physical Linux certification on required CPU families, certified manifest lifecycle, Profile registration and READY
  transition before formal Render/Authoritative Preview can become available.
- Final product-level Template v1 acceptance against the frozen authority, including remaining capacity, security,
  cancellation/recovery and browser journeys.

Formal certification was explicitly deferred during T218/T219. Do not infer authorization to register a Profile,
claim READY, use production systems or mutate immutable records.

## Boundaries

- No push, tag or PR without explicit authorization.
- No paid/live model call, real data, production action, secret read or external side effect.
- No placeholder route/table/interface/module/Profile registration or test-only bypass.
- PostgreSQL behavior uses PostgreSQL/Testcontainers or the explicit local runner, never H2/SQLite.
- Preserve unrelated dirty IMAGE_ONLY work. Use explicit path lists for `git add`; never `git add -A`.
- Keep Candidate Preview `NOT_CERTIFIED`; normal Authoritative Preview stays fail closed until certification is real.
