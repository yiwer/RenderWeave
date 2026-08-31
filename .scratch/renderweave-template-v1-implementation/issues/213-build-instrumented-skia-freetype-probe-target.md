# T213 — Build instrumented Skia/FreeType probe target

Type: task
Status: blocked
Blocked by: T212

## What to build

Consume T212's verified offline closure to perform the first exact Linux x86-64-v2 Skia/FreeType build rehearsal. Apply the frozen patches and custom headers in their declared order, build an instrumented probe target, and use the T211 synthetic tricky font to prove the classifier, glyph-load flags, symbol boundary, ELF closure, and ISA contract.

Docker and WSL execution of this non-authenticated exact build rehearsal is authorized by the user on 2026-08-31. The result remains rehearsal evidence only and must not be represented as renderer certification or physical-machine conformance.

## Scope

- Hermetic configure/build entry point that consumes only the T212 offline bundle.
- Frozen Skia/FreeType patch and custom-header application with exact pre/post-image checks.
- An instrumented probe executable covering tricky-font classification and glyph loading.
- ELF dependency, exported-symbol, forbidden-symbol, and x86-64-v2 ISA audits.
- Reproducible command and evidence manifest for Docker/WSL rehearsal.

## Acceptance criteria

- Build succeeds with networking disabled and without host font, graphics, codec, or shaping libraries.
- Patch/header application order and resulting bytes match the frozen authority.
- The tricky font is classified as expected and every observed glyph load carries the required fail-closed flags.
- ELF closure and symbol audits show only the allowed runtime boundary; forbidden hinting/interpreter paths are absent or unreachable as required by the probe contract.
- A repeated clean rehearsal produces the same declared build identities, or any permitted nondeterminism is explicitly bounded and excluded from identity.

## Test plan

- Negative configure/build tests for missing offline inputs and forbidden host fallbacks.
- Probe tests for classifier result, load flags, custom CFF behavior, symbols, ELF dependencies, and ISA instructions.
- Two clean network-disabled Docker/WSL rehearsal runs from the verified T212 bundle.
- Run affected renderer and Template gates.

## Out of scope

- Renderer certification or Profile registration.
- Production Text node rendering.
- Physical x86-64-v2 host certification.
- Network access during configure, compile, link, or probe execution.

