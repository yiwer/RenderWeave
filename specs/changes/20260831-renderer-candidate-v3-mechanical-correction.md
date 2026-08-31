# Renderer candidate v3 mechanical correction

Status: approved additive implementation authority
Approved by: product-semantics owner, 2026-08-31
Scope: Template v1 Renderer spike candidate only

## Decision

Issue `rw-renderer-spike-linux-x86_64-v2-000003` as an immutable successor to candidate `000002`.
Candidate `000003` preserves every semantic choice, pinned upstream revision, no-hinting rule and fixture
binding from `000002`; it corrects only two mechanical configuration defects exposed by T213's exact build
rehearsal.

The options header is installed as
`third_party/externals/freetype/include/renderweave/ftoption.h` and selected with
`FT_CONFIG_OPTIONS_H=<renderweave/ftoption.h>`. From that location it includes the exact stock header through
the quoted relative path `../freetype/config/ftoption.h`. The include therefore cannot resolve to the custom
header itself.

The module list is installed as
`third_party/externals/freetype/include/renderweave/ftmodule.h` and selected with
`FT_CONFIG_MODULES_H=<renderweave/ftmodule.h>`. It deliberately has no include guard or `#pragma once`, because
FreeType's `src/base/ftinit.c` includes `FT_CONFIG_MODULES_H` twice with different `FT_USE_MODULE` definitions.
Each inclusion must expand the same six retained modules.

The successor Skia patch changes only those two configuration-header macro paths and their include root. The
glyph-load invariant, removed sources and all other patched behavior remain byte-for-byte equivalent to the v2
patch meaning.

## Immutability and composition

Candidates `000001` and `000002`, their decisions, headers and application orders remain byte-for-byte
immutable. Candidate `000003` composes the exact `000002` contract with the closed mechanical correction in
its own artifact. A v3 supersession registry copies the already-issued record and appends `000002 → 000003`;
the v1 registry is not rewritten.

Any further source, header, install-path or application-order change requires another candidate ID. A build
adapter that force-includes the stock options header, undefines a module-header guard, replays a wrapper module
header or falls back to host FreeType is not candidate `000003` evidence.

## Lifecycle boundary

The user separately authorized T213's non-authenticated Docker/WSL exact build rehearsal. This source decision
does not itself perform or certify that build. Exact built-target observation, physical-host replay, Renderer
Exact Output issuance, Profile registration, certification, READY and Ticket 19 closure remain false here.
