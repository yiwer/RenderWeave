# T211 — Materialize a portable tricky-font fixture

Type: task
Status: done
Blocked by: T210 (done)

## What to build

Commit deterministic, license-safe font fixture bytes and their reproducible source recipe so the Renderer certification corpus has a portable face intended to exercise FreeType's exact `FT_IS_TRICKY` classification path without relying on a proprietary font.

## Scope

- Add an append-only fixture authority; do not mutate T209/T210 decisions, either immutable candidate or the existing v2 fixture policy artifact.
- The fixture must be synthetic from repository-owned source or derived from an explicitly compatible open licence. Commit its licence/provenance, deterministic recipe, exact bytes, byte length and SHA-256.
- Add an independent verifier that validates the recipe output, SFNT structure and the source-level facts needed by FreeType 2.14.3 `tt_check_trickyness` to select the fixture.
- Bind the fixture authority into the existing v2 compatibility gate while keeping built-target, runtime bytecode non-execution, physical Linux certification and record issuance false.

## Acceptance criteria

- [x] A clean checkout can reproduce byte-identical fixture bytes using repository tooling only and without network access.
- [x] The fixture contains no copied proprietary font bytes or glyph outlines, and its committed provenance permits repository distribution.
- [x] Primary generation and independent verification agree on SHA-256, byte length, relevant table checksums and expected tricky-face match.
- [x] Mutation tests reject changed bytes, stale digests, malformed SFNT tables, missing licence/provenance and a fixture that no longer satisfies the exact source-level classifier facts.
- [x] The focused tricky-font gate and Template static gate pass; all later build/certification lifecycle flags remain false.
- [x] T211 makes no claim that runtime execution distinguishes `FT_LOAD_NO_HINTING` alone from `FT_LOAD_NO_HINTING | FT_LOAD_NO_AUTOHINT`; that built-target/instrumentation proof remains pending for a later approved ticket.

## Test plan

- First make the compatibility verifier require the new authority and capture failure while it is absent.
- Add deterministic recipe golden tests and an implementation-independent parser/checksum verifier.
- Run the focused fixture tests, the existing tricky-font compatibility gate and the Template static gate.
- Create a local candidate commit, then run `code-review` from the ticket base against this ticket and the T210 authority; amend any blocking fixes before marking done.

## Out of scope

- No FreeType/Skia target build, proprietary font download, hinted glyph execution, physical Linux run, Profile registration, Renderer Exact Output issuance, certification, READY declaration or Ticket 19 closure.
- No API, OpenAPI, Web, Flyway, provider, API key, real data, production, push, tag or pull request.

## Resolution

Done. Fixed-point review is clean.

- Added a repository-owned, 0BSD synthetic TrueType fixture and deterministic stdlib-only generator. The committed 996-byte output is `sha256:315504d5386a2e53f0c96cd3efbf71b9ccc3b1fef237dbec9e7d25cdbcf7139f`.
- Added an implementation-independent verifier for strict authority/provenance binding, SFNT directory and global checksums, `head`/`maxp`/`name`/`cmap`/`loca`/`glyf` semantics, synthetic programs and the exact case-sensitive FreeType family substring `cpop` path.
- Added nine golden/mutation tests covering byte drift, stale digest, malformed table layout, missing licence/provenance, third-party provenance drift, classifier drift and lifecycle overclaim.
- Bound fixture reproduction and verification into the existing v2 compatibility gate and Template static summary. Focused compatibility checks and `tools/run-gate.ps1 -Gate template` pass.
- Added checkout attributes that keep the TTF binary and the hashed recipe LF-stable. A fresh Windows clone with `core.autocrlf=true` reproduced the exact fixture and passed the independent verifier.
- Kept exact built-target observation, runtime bytecode non-execution, NO_HINTING/NO_AUTOHINT distinction, physical Linux replay, issuance, certification, READY and Ticket 19 closure false.
- Fixed-point `code-review` rerun after amendments reported zero Standards findings and zero Spec findings.
