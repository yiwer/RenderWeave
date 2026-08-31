# T214 — Implement production Text-to-PNG vertical slice

Type: task
Status: ready-for-agent
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
