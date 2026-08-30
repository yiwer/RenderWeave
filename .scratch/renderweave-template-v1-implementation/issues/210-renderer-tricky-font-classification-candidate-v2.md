# T210 — Renderer tricky-font classification candidate v2

Type: task
Status: active / claimed
Claimed by: Codex（single writer）
Blocked by: T209（resolved）and product-semantics owner decision（approved 2026-08-31）

## Goal

Issue a new immutable Renderer spike candidate that preserves FreeType's exact
`TT_CONFIG_OPTION_BYTECODE_INTERPRETER` → `TT_USE_BYTECODE_INTERPRETER` compile path so the upstream
`FT_IS_TRICKY` classifier exists, while keeping every production glyph load fail-closed behind
`FT_LOAD_NO_HINTING | FT_LOAD_NO_AUTOHINT | FT_LOAD_NO_BITMAP | FT_LOAD_NO_SVG`.

## Authority and seam

- Candidate `rw-renderer-spike-linux-x86_64-v2-000002` supersedes, but never rewrites, candidate `000001`.
  Supersession is append-only metadata outside the immutable predecessor bytes.
- The stock FreeType 2.14.3 option header defines `TT_CONFIG_OPTION_BYTECODE_INTERPRETER` and derives
  `TT_USE_BYTECODE_INTERPRETER`; the v2 custom header must retain both and fail compilation if either is absent.
- Compiling the interpreter is allowed only to retain the upstream classification path. It does not authorize
  hinted glyph execution. Every actual production `FT_Load_Glyph` path must carry both no-hinting flags; any
  bypass, flag removal, or forbidden hinting/color flag fails build/certification.
- Record the approved semantic delta in additive implementation authority, a v2 candidate, source target,
  prerequisites, supersession record, and an offline compatibility decision. Do not rebind central acceptance
  or issue Renderer Exact Output records in this ticket.

## Test-first validation

- First require the verifier to consume candidate v2 authority and capture RED while those artifacts are absent.
- Cover immutable predecessor binding, exact supersession, retained interpreter macros, mandatory final load flags,
  runtime bytecode non-execution proof remaining pending, and all lifecycle overclaims.
- Run the focused Python suite, compatibility gate, and fresh Template static gate.

## Boundary

- No source download retention, proprietary font bytes, FreeType/Skia build, link, execution, physical Linux,
  Profile registration, certification, READY, or Renderer Exact Output record issuance.
- `buildAuthorized`, target materialization, preissuance, record issuance, Certified, READY, and Ticket 19 closure
  remain false. Runtime non-execution is a future build/instrumentation proof, not a source-level claim here.
- No API/OpenAPI/Web/Flyway/provider/API-key/real-data/production change; do not touch user dirty work or
  push/tag/create a PR.
