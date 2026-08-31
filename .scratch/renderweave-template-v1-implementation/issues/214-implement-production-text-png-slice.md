# T214 — Implement production Text-to-PNG vertical slice

Type: task
Status: done
Blocked by: T213 (done)

## What to build

Implement the first production glyph-rendering path through the Rust Engine: consume an exact FONT RenderResource, shape and lay out a supported Text node, raster it through the rehearsed Skia/FreeType boundary, and atomically seal a PNG RenderOutput through the existing renderer process contract.

## Scope

- Exact font resource admission and integrity verification at the Engine boundary.
- Frozen-profile shaping, text layout, glyph rasterization, compositing, and PNG output for the smallest complete supported Text slice.
- Deterministic diagnostics and fail-closed problem mapping for font, shaping, layout, glyph, raster, deadline, and output failures.
- End-to-end fixtures crossing Java RenderDocument/Command serialization and the Rust renderer process.

## Acceptance criteria

- A conforming Text RenderDocument with a real admitted font produces the expected complete PNG through the product renderer process.
- Resource hash/media/descriptor mismatches and unsupported text contracts fail before output seal.
- No platform font fallback, host shaping library, hinting path, partial image, or unsealed output is observable.
- Repeated execution of the supported fixture is byte-deterministic under the frozen renderer profile.
- Existing non-Text renderer behavior remains green.

## Test plan

- Rust unit and integration tests for font admission, shaping/layout, rasterization, and atomic output.
- Java-to-Rust contract fixture for one complete Text-to-PNG command.
- Golden PNG/digest replay for the supported slice plus malformed-resource and deadline negatives.
- Run renderer, server, Template, runtime, and relevant end-to-end gates.

## Out of scope

- JPEG output and every Text overflow/writing-mode variant.
- Other previously unsupported RenderNode kinds.
- Renderer Profile certification or public readiness registration.

## Resolution

Implemented the smallest complete production Text slice without registering availability. One fixed, pixel-aligned Text node containing exactly one ASCII Latin letter and one exact FONT resource now traverses the real feature-on process path from `ConcurrentRequestRegistry` through output preflight, resource preparation, Engine execution and atomic two-frame PNG result sealing. The default build remains fail-closed, and broader Text contracts remain explicitly unsupported.

- Replaced cmap-only glyph lookup with the exact pinned HarfBuzz source at commit `9cb1fee51069b206effb4736e443b038d230789d`. The private C++ boundary accepts one scalar, applies default OpenType shaping, then draws the resulting glyph sequence through the exact data-only Skia/FreeType font. A frozen font proves `cmap A -> A` plus default `ccmp A -> A.alt`; its shaped pixel digest is `sha256:8fae18975da6386236ffb7733472b3d7cec1fdd7dad601bd1bbe1daf4646ff58`.
- Kept the closed T214 surface: one run, one ASCII Latin letter (`A-Z` or `a-z`), zero letter spacing, fixed alignment/wrapping/fitting/paint and pixel-aligned bounds. Other printable ASCII fails before shaping; there is no platform font fallback or host shaping dependency. T215 still owns exhaustive `FT_Load_Glyph`, runtime bytecode non-execution and CFF path proof.
- Completed typed, stable Engine failure projection. Font glyph absence is `FONT_GLYPH_MISSING/SHAPING` with both opaque occurrence and resource locators preserved. Java projects those locators through the sealed request-local sidecar into an author-safe path; missing or corrupt mappings fold to `RENDER_INTERNAL_ERROR` rather than inventing identity. Shaping, layout, raster allocation, output budgets, encoder/contract and output-seal failures use closed code/stage enums.
- Added request-scoped cooperative cancellation/deadline checkpoints through resource preparation, layout, shaping, rasterization, encoding and output seal. Output-surface preflight now precedes resource fetch; cancellation after an uninterruptible fetch discards the result before decode/layout; cancellation or deadline after complete image production discards it before atomic release.
- Extended the Rust process problem catalog and Java parser/API catalog for media/decode/glyph/raster/output outcomes. The process tests execute the exact Java-authored command through the concurrent registry, verify replay identity, verify all current Engine mappings, and verify post-seal deadline suppression. Hermetic lock generation relationships now have one descriptor authority, and both offline harnesses consume only bundle-bound inputs.
- The original deterministic 96x48 minimal-font result remains 18,582 bytes with pixel digest `sha256:afdff21b99f5e7101c692c16744602e8a621fb3a814555cd1493b62ae2c5b3a4` and PNG digest `sha256:77c3a0195d424998a55595a52b305344c86efe5f770884f7d1cd639c63be936b`.
- Final repository-independent closure verification for the reviewed code passed with 29 inputs, 2,250,694 checks and inventory `sha256:a217ef2ff732e1c045a0cc61d77add9d4345e881ac4efefe3f8b85a7ea6bb852`. The locked crates tree is `sha256:88d1beca9dff3dfdcfcc37445e11850edc373f7ae0704e0602dd4c70ea2272ea`, its 37-file archive is `sha256:ec0baae67820ea8ddbd0d8352032d1088b3a117e4ba4e717ba9581b9fd55222b`, and the rehearsal harness is `sha256:37d3d6f70835a254937f9fe21d0f7ac018076f6f7304e153e5793745ed8b0e37`. A fresh Docker volume `rw-t214-review-fix-i-20260901`, with no repository mount and `--network none`, rebuilt/audited pinned Skia/FreeType (`binary sha256:4d1aba604c16cb1cd86030941b1d31740cc7ecc3e6bea285fe0858dacdb3a6ed`, `manifest sha256:e570b47eae832fdd4c37aeeef65072d9a4847c41c503261880f6744225e4e151`), built pinned HarfBuzz, passed Engine 11/11, process 3/3, feature-on Engine+daemon Clippy with `-D warnings`, and the release daemon build.

Fixed-point Standards and Spec reviews of `ba13ec39..5a1b7be8` found no blocker; both previously reported Spec blockers are closed. A clean detached worktree at `5a1b7be8` passed renderer, server, Template, runtime and E2E gates. The successful E2E replay completed 26 tests with the live test skipped by policy; an earlier run observed one transient loading-state contrast failure and the unchanged test passed on immediate replay. Profile availability remains `NOT_REGISTERED`; no certification/READY claim, provider call, real data, push, tag or PR occurred.
