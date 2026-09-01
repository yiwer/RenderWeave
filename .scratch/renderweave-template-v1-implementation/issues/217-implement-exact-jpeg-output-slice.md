# T217 — Implement exact JPEG output vertical slice

Type: task
Status: done
Blocked by: T215 (done)

## Goal

Add the first complete production JPEG path without registering renderer availability: consume the same
canonical straight RGBA8 surface as PNG, apply the frozen opaque-white matte, encode through the pinned static
libjpeg-turbo 3.2.0 boundary, and atomically seal the JPEG result through the existing daemon protocol.

## Scope

- Introduce one deep `output-jpeg` module whose public seam is canonical RGBA8 + dimensions + DPI + quality.
- Build only the T212-pinned libjpeg-turbo source in the network-disabled exact Linux rehearsal; disable SIMD and
  reject host/system codec fallback.
- Freeze `JDCT_ISLOW`, baseline single-scan 4:4:4, non-arithmetic coding, fixed Annex K Huffman tables, explicit
  quality-derived quantization tables, no optimized Huffman/restart/smoothing/Adobe APP14, canonical sRGB ICC,
  and exact marker ordering.
- Route JPEG commands through Engine and daemon output preflight, controlled encoding, and atomic result seal.
- Bind exact golden bytes and structural inspection for quality `1/24/25/49/50/90/99/100`, `1x1`, non-8-multiple
  surfaces, white matte, block padding, byte stuffing, tables, markers, ICC, DPI, and cancellation/deadline.

## Acceptance criteria

- A supported RenderDocument can produce complete deterministic `image/jpeg` bytes and sealed JPEG metadata.
- The target explicitly sets and audits every frozen codec field/table; ambient defaults cannot change output.
- Invalid quality/surface/pixels, codec drift, malformed output, cancellation, and deadline fail before result seal.
- Exact Docker rehearsal uses the staged offline bundle with `--network none` and passes feature-on tests, Clippy,
  release build, dependency/symbol audit, and repeated-byte identity.
- PNG behavior remains unchanged and Profile state remains `NOT_REGISTERED`.

## Out of scope

- Remaining Text variants or RenderNode kinds, LayoutTrace multipart, and the complete exact-output corpus.
- Physical-host certification, Certified/READY lifecycle, Profile registration, public rollout, or issuance of the
  162 Renderer Exact Output records.
- Provider calls, real data, push, tag, or pull request.

## Resolution

Implemented the first complete JPEG output path while keeping Renderer availability unregistered. The Engine now
materializes one canonical straight RGBA8 surface for either output format, and the JPEG path applies the frozen
opaque-white matte, exact quality tables, pinned Annex K Huffman tables, canonical sRGB ICC, and a statically linked
libjpeg-turbo 3.2.0 encoder before the daemon atomically seals the closed JPEG result metadata and image frame.

- Added the `output-jpeg` deep module with fail-closed surface/quality/budget validation, row-level cooperative
  checkpoints, exact marker/table/entropy validation, and golden cases for qualities 1/24/25/49/50/90/99/100,
  1x1 transparency, 9x7 block padding, byte stuffing, and white matte. The 5,429-byte vector manifest is
  `sha256:3d18fd35b4f22164f72111dfe088243b76061d5eb1cbffcbebba053969d83d48`.
- Extended the existing Engine and daemon seams without changing PNG behavior. JPEG success carries
  `renderweave-output-jpeg/1.0`, `image/jpeg`, exact DPI/quality, byte length and content digest; cancellation or
  deadline at encoding/output-seal discards complete bytes before terminal release.
- Updated the inherited hermetic lock and both offline harnesses. The final 29-input bundle independently verified
  2,250,754 entries with inventory
  `sha256:10e71a7a33da2e22712ba6d7cc981062b8f752eb88a6f23a532430e69afcfe3d`.
- A fresh `--network none`, 4-vCPU/8-GiB Docker volume rebuilt the 573-target exact Skia/FreeType baseline and passed
  its probe (`sha256:4d1aba604c16cb1cd86030941b1d31740cc7ecc3e6bea285fe0858dacdb3a6ed`). It then built static no-SIMD
  libjpeg-turbo (`libjpeg.a` `sha256:1733d987efec132dec9520e55a73fed236541a0a0fa4b39353475354cad70df8`),
  produced two byte-identical native JPEG archives
  (`sha256:b4f9e9929a0ec5e666d262c0160eeb26b96cdfa40818674a44aed9bd93aa5b68`), and passed JPEG 7/7,
  Engine 15/15, daemon 7/7, feature-on Clippy, release build, glyph inventory, and JPEG symbol/dynamic-dependency
  audits.
- The default renderer workspace tests and Rust formatting pass. No Profile was registered, no physical-host or
  READY/certification claim was made, and no provider, real-data, push, tag, or PR action occurred.

## Review and verification

- The final Linux feature-on daemon source was replayed in the exact, network-disabled Docker environment. The
  production executor test now sends the frozen Text command as JPEG through request admission, ordered resource
  fetch, Engine execution, atomic result sealing, and exact terminal replay; daemon unit tests passed 4/4 and public
  result tests passed 4/4.
- `powershell -ExecutionPolicy Bypass -File tools/run-gate.ps1 -Gate render` passed from a clean detached worktree
  after the executor-level review fix. Evidence is under
  `.sdlc/evidence/20260901-110034-render` in that review worktree.
- Standards review found no hard violation. Its format-neutral naming and shared PNG/JPEG harness observations are
  deliberate follow-up refactors rather than T217 correctness work. Spec review's missing executor-level proof was
  closed by the feature-on test above.

## Remaining blocker

The additional Template gate reaches Java 185/185 and canonical replay 211/211, then stops in
`renderer-tricky-font-compatibility`: immutable v1/v2/v3 decision records bind an older
`renderer/process-manifest.json` digest while T217 necessarily advances that manifest. Rewriting those frozen bytes
would violate their own append-only authority. A successor record or versioned historical-manifest lookup is needed
before the legacy authority chain can pass again. This is governance/evidence plumbing rather than a JPEG behavior
failure. On 2026-09-01 the owner approved the T218/T219 idea-validation path and explicitly deferred formal
certification work, so this legacy authority repair moved out of T217 rather than mutating frozen records. T217 is
therefore done on its functional scope and makes no formal Template/Renderer READY claim.
