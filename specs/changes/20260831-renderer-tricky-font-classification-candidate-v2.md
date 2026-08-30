# Renderer tricky-font classification candidate v2

Status: approved additive implementation authority
Approved by: product-semantics owner, 2026-08-31
Scope: Template v1 Renderer spike candidate only

## Decision

Issue `rw-renderer-spike-linux-x86_64-v2-000002` as a new immutable successor to candidate `000001`.
The successor compiles FreeType 2.14.3 with `TT_CONFIG_OPTION_BYTECODE_INTERPRETER` retained, which makes
the stock header derive `TT_USE_BYTECODE_INTERPRETER` and keeps the upstream `tt_check_trickyness` /
`FT_FACE_FLAG_TRICKY` classification path present.

Compiling that code is not permission to execute TrueType hinting. Every production glyph-load path must
finish with all of these flags:

- `FT_LOAD_NO_HINTING`
- `FT_LOAD_NO_AUTOHINT`
- `FT_LOAD_NO_BITMAP`
- `FT_LOAD_NO_SVG`

It must finish with neither `FT_LOAD_FORCE_AUTOHINT` nor `FT_LOAD_COLOR`. A constructor, metrics, image,
path, drawable, cache, or future load path that can reach `FT_Load_Glyph` without the same invariant is a
candidate failure. Certification must additionally prove, with a real built target and instrumentation,
that no TrueType bytecode is executed on the production path.

## Immutability and lifecycle

Candidate `000001` and its T209 contradiction decision remain byte-for-byte immutable. An append-only
supersession artifact links `000001` to `000002`; this authority does not edit the predecessor.

Candidate `000002` is source/configuration authority only. This decision does not authorize a build,
materialize an exact Renderer target, establish preissuance readiness, issue Renderer Exact Output records,
certify a Renderer, register a Profile, declare READY, or close Ticket 19.

## Exact upstream facts

- FreeType commit: `0a0221a1347e2f1e07c395263540026e9a0aa7c7` (2.14.3)
- Stock `ftoption.h` defines `TT_CONFIG_OPTION_BYTECODE_INTERPRETER` and derives
  `TT_USE_BYTECODE_INTERPRETER` under that option.
- `src/truetype/ttobjs.c` guards the tricky-font classification implementation with
  `TT_USE_BYTECODE_INTERPRETER`.

Official mirror references:

- `https://raw.githubusercontent.com/freetype/freetype/0a0221a1347e2f1e07c395263540026e9a0aa7c7/include/freetype/config/ftoption.h`
- `https://raw.githubusercontent.com/freetype/freetype/0a0221a1347e2f1e07c395263540026e9a0aa7c7/src/truetype/ttobjs.c`
