# T215 — Prove production glyph paths fail closed

Type: task
Status: done
Blocked by: T214 (done)

## What to build

Turn the T214 production glyph slice into a closed, auditable glyph-loading boundary. Inventory every production `FT_Load_Glyph` path, enforce the frozen `NO_HINTING`, `NO_AUTOHINT`, `NO_BITMAP`, and `NO_SVG` policy at each call site, prove TrueType bytecode cannot execute at runtime, and add the required CFF regression coverage.

## Scope

- Machine-checked inventory of all production glyph-load call sites and wrappers.
- Central policy enforcement that cannot be bypassed by shaping, fallback, measurement, raster, or diagnostic paths.
- Runtime instrumentation/tests proving bytecode non-execution and forbidden auto-hint/bitmap/SVG behavior.
- CFF and tricky-font regressions bound to exact fixture identities.

## Acceptance criteria

- Every reachable production glyph-load path is inventoried and carries the exact required flag set.
- Adding an unregistered glyph-load call site or weakening a required flag fails an automated test or build check.
- TrueType bytecode execution, auto-hinting, embedded bitmap loading, and SVG glyph loading are demonstrably unreachable for production commands.
- CFF rendering remains functional under the frozen custom-header policy and has deterministic regression evidence.
- Failures remain atomic and map to the frozen renderer problem boundary.

## Test plan

- Static/source inventory checks plus runtime load-flag instrumentation.
- Tricky TrueType and CFF positive/negative fixtures.
- Mutation-style tests that remove each required flag or add an unregistered call path and expect failure.
- Run renderer, Template, runtime, and relevant end-to-end gates.

## Out of scope

- Remaining RenderNode kinds, JPEG, or the complete exact-output corpus.
- Profile certification, production rollout, or public readiness.

## Resolution

Closed the production glyph-loading boundary without registering renderer availability. The exact production binary now link-interposes both `FT_Load_Glyph` and `FT_New_Library`: every load must carry `FT_LOAD_NO_HINTING | FT_LOAD_NO_AUTOHINT | FT_LOAD_NO_BITMAP | FT_LOAD_NO_SVG`, `FT_LOAD_FORCE_AUTOHINT | FT_LOAD_COLOR` is rejected, and the installed TrueType debug hook fails closed if interpreter execution is ever attempted. Request-local observations additionally require a real compliant glyph load before a successful result can seal; policy failures remain atomic and project through the existing closed shaping-problem boundary.

- The frozen Skia/FreeType source audit now derives its inventory from the exact `ninja -t deps` build closure rather than a directory glob. It locks 596 implementation files (`sha256:794a3ef6c0a031c828363a4c271e6137a665815d46ae4475f5262a132045f539`), inventories 13 direct calls plus the one FreeType API definition, rejects closure/path/call drift, and separately proves that the only repository-native C++ compile input and the pinned HarfBuzz unity source add no direct call. The locked patch executor remains path-allowlisted and uses the pinned OCI Python runtime instead of ambient `git`, `xz`, or `unzip` behavior.
- Mutation coverage removes each required flag, adds each forbidden mode, removes either linker interposition, adds an unregistered native compile input, and adds an unregistered call in a different compiled source directory. The exact instrumented probe proves its `NO_HINTING`-only control executes TrueType bytecode while the complete direct control, tricky TrueType path, and CFF path execute it zero times and observe no invalid flags.
- Exact fixture identities are bound in test code: CFF `sha256:eeef766ac75aecac694bbd82fbb3cd2b9a315075db14d91ff0cbe1bdec20f77f` produces pixel digest `sha256:630063ab4f18a8c0dd5341ec961b9fd95a4e1dabde296499c64cfdf0571a2215` and PNG digest `sha256:555f5dca44d2be490a21f1731367d0b5b3d07ad7aa0485a45c58f49ebf252895`; tricky TTF `sha256:315504d5386a2e53f0c96cd3efbf71b9ccc3b1fef237dbec9e7d25cdbcf7139f` produces pixel digest `sha256:c698e4d98a1d073bce25dbc5d1638c74bf3beaab9e490ec8039cd8a093608cab` and PNG digest `sha256:cd6c38b7dcef6b5998d166e3480a133e21fa070b7ccf0da94114ba569049f6ee`.
- Repository-independent staging passed with 29 inputs, 2,250,706 checks, and inventory `sha256:9b7b586c1ad911976139b74461e689c87dff6254387f271b47c243af1da599f4`. A fresh Docker volume `rw-t215-inventory-20260901-a`, with no repository mount and `--network none`, first replayed the exact T213 candidate at 573/573 (`binary sha256:4d1aba604c16cb1cd86030941b1d31740cc7ecc3e6bea285fe0858dacdb3a6ed`, `manifest sha256:e6035cac81697c4a13dbdd8576d27ce3ac7d7ec8b5ba051aa50c1e4e0a690498`) and then passed Engine unit 2/2, native vectors 13/13, daemon production tests 3/3, feature-on Clippy with `-D warnings`, release daemon build, the exact-build source inventory, and every discovered native-archive policy audit.

Clean detached revision `1d460963` passed the `render` gate at
`.sdlc/evidence/20260901-040905-render/` and the `template` gate at
`.sdlc/evidence/20260901-041039-template/`; both captured a clean worktree and the exact revision. The earlier
clean `server`, `runtime`, and E2E evidence remains applicable because the final blocker fix changed only the
offline source-inventory/rehearsal verification boundary, not product Java/Rust/Web behavior. Fixed-point
Standards and Spec reviews of `dfdb2f13..1d460963` both reported `NO BLOCKERS` and independently confirmed that
the former directory-glob escape is closed by the exact dependency inventory and mutation coverage.

Profile state remains `NOT_REGISTERED`; no certification/READY claim, provider call, real data, push, tag or PR
occurred.
