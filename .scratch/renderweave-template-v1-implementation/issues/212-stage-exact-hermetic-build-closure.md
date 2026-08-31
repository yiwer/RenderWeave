# T212 — Stage exact hermetic build closure

Type: task
Status: done
Blocked by: T211 (done)

## What to build

Materialize the frozen renderer source and toolchain closure into a reproducible, content-addressed offline build bundle. The bundle must cover the exact pinned Skia and FreeType revisions plus every declared transitive source, generator, compiler/sysroot/runtime, canonical color input, Rust vendor input, and OCI input needed by the frozen Linux x86-64-v2 build contract.

The staging workflow must verify commit/tree identities and declared byte hashes before admitting any input, emit a deterministic inventory, and support a network-disabled completeness check. Downloading the exact pinned source and tool inputs is authorized for this ticket by the user on 2026-08-31. This authorization does not include paid services, authenticated private sources, production systems, or real user data.

## Scope

- A versioned lock/inventory for every build-closure category required by the frozen renderer authority.
- A deterministic fetch/stage workflow that rejects redirects, revisions, trees, archives, packages, OCI layers, or checksums outside the lock.
- A verifier that proves the staged bundle is complete, byte-exact, path-safe, and usable without network access.
- Tests for missing inputs, hash drift, tree drift, path traversal, duplicate logical inputs, and offline completeness.
- Locally stage and verify the authorized exact inputs as implementation evidence; do not commit third-party source payloads unless repository policy explicitly requires it.

## Acceptance criteria

- [x] The lock closes every category named by `hermetic-linux-build-prerequisites-v2.json`; no host-library or floating-tool fallback remains.
- [x] Skia and FreeType satisfy their frozen commit, tree, and archive identities, and the frozen downstream patch/custom-header inputs are included in exact application order.
- [x] The staged inventory is deterministic and binds each logical input to origin, immutable identity, byte length, and SHA-256.
- [x] A fresh staging run followed by a network-disabled verification reports a complete offline closure.
- [x] Corrupt, missing, substituted, duplicated, or path-escaping inputs fail closed before any compile command can run.

## Test plan

- Unit tests for lock parsing, category coverage, identity verification, safe extraction paths, and deterministic inventory serialization.
- Integration test using local fixture sources to exercise fetch, stage, tamper rejection, and offline verification.
- Authorized live staging of the exact pinned public inputs, followed by an offline-only completeness replay.
- Run the narrow renderer/source-integrity checks and the Template gate affected by the new authority.

## Out of scope

- Compiling or linking Skia, FreeType, HarfBuzz, or the Rust renderer.
- Treating Docker, WSL, or the developer host as certification evidence.
- Registering a renderer Profile or changing product rendering behavior.
- Any paid, authenticated, production, or real-data operation.

## Resolution

Done. The exact public source/tool closure is staged and independently replayable offline; no compile or certification claim was made.

- Added a strict versioned lock covering 23 logical inputs across all 11 prerequisite categories. The lock is 27,130 bytes with SHA-256 `96f076ccd36ddc89fe2fe38f5fb5da732757d1fd2138dc63cd4ffc052fc16b38`.
- Added deterministic repository-file, repository-tree, URL, Git archive and OCI staging adapters with exact redirect/origin, byte length, digest, commit/tree, package database and application-order checks. Archive members, links, OCI layers and extraction parents are validated against traversal and special-file escapes before bundle publication.
- Added a content-addressed local cache and atomic bundle publication. A failed final verification leaves no published candidate bundle.
- Fresh staging produced 23 payload files totalling 3,457,935,709 bytes. The deterministic inventory is 27,355 bytes with SHA-256 `42a303e0ac81d37980fc8bc6ffc137b4ac36680ee5eeba06b85fb9dd1f1d19a3`.
- Network-disabled verification with an unavailable repository path completed as `STAGED_EXACT_OFFLINE_CLOSURE`: 2,248,288 checks, `offlineVerified=true`, with `buildAttempted`, `certified`, `ready` and `ticket19MayClose` all false.
- Verification passed: 12 Python tests, the dedicated renderer hermetic closure gate, and the complete Template static gate (185 Java tests plus 211/211 independent kernel replay and registry replay).
- Fixed-point review found zero Spec findings and zero documented Standards violations. Two non-blocking judgement calls remain around repeated source-kind dispatch and duplicated download/cache mechanics; neither changes the closed behavior or ticket result.
