# T211 — Materialize a portable tricky-font fixture

Type: task
Status: ready-for-agent
Blocked by: T210 (done)

## What to build

Commit deterministic, license-safe font fixture bytes and their reproducible source recipe so the Renderer certification corpus has a portable face intended to exercise FreeType's exact `FT_IS_TRICKY` classification path without relying on a proprietary font.

## Scope

- Add an append-only fixture authority; do not mutate T209/T210 decisions, either immutable candidate or the existing v2 fixture policy artifact.
- The fixture must be synthetic from repository-owned source or derived from an explicitly compatible open licence. Commit its licence/provenance, deterministic recipe, exact bytes, byte length and SHA-256.
- Add an independent verifier that validates the recipe output, SFNT structure and the source-level facts needed by FreeType 2.14.3 `tt_check_trickyness` to select the fixture.
- Bind the fixture authority into the existing v2 compatibility gate while keeping built-target, runtime bytecode non-execution, physical Linux certification and record issuance false.

## Acceptance criteria

- [ ] A clean checkout can reproduce byte-identical fixture bytes using repository tooling only and without network access.
- [ ] The fixture contains no copied proprietary font bytes or glyph outlines, and its committed provenance permits repository distribution.
- [ ] Primary generation and independent verification agree on SHA-256, byte length, relevant table checksums and expected tricky-face match.
- [ ] Mutation tests reject changed bytes, stale digests, malformed SFNT tables, missing licence/provenance and a fixture that no longer satisfies the exact source-level classifier facts.
- [ ] The focused tricky-font gate and Template static gate pass; all later build/certification lifecycle flags remain false.
- [ ] T211 makes no claim that runtime execution distinguishes `FT_LOAD_NO_HINTING` alone from `FT_LOAD_NO_HINTING | FT_LOAD_NO_AUTOHINT`; that built-target/instrumentation proof remains pending for a later approved ticket.

## Test plan

- First make the compatibility verifier require the new authority and capture failure while it is absent.
- Add deterministic recipe golden tests and an implementation-independent parser/checksum verifier.
- Run the focused fixture tests, the existing tricky-font compatibility gate and the Template static gate.
- Create a local candidate commit, then run `code-review` from the ticket base against this ticket and the T210 authority; amend any blocking fixes before marking done.

## Out of scope

- No FreeType/Skia target build, proprietary font download, hinted glyph execution, physical Linux run, Profile registration, Renderer Exact Output issuance, certification, READY declaration or Ticket 19 closure.
- No API, OpenAPI, Web, Flyway, provider, API key, real data, production, push, tag or pull request.

## Resolution

Pending.
