#ifndef RENDERWEAVE_FREETYPE_FTOPTION_V2_H_
#define RENDERWEAVE_FREETYPE_FTOPTION_V2_H_

/* Start from the exact FreeType 2.14.3 option surface.  Candidate 000002
 * intentionally retains the interpreter compile path because upstream tricky
 * face classification is compiled under TT_USE_BYTECODE_INTERPRETER. */
#include <freetype/config/ftoption.h>

#ifndef TT_CONFIG_OPTION_BYTECODE_INTERPRETER
#error "RenderWeave candidate 000002 requires the FreeType bytecode compile option for tricky-face classification"
#endif

#ifndef TT_USE_BYTECODE_INTERPRETER
#error "RenderWeave candidate 000002 requires FreeType's derived tricky-face classification compile guard"
#endif

/* Runtime TrueType hinting remains forbidden.  The Skia patch must enforce
 * FT_LOAD_NO_HINTING and FT_LOAD_NO_AUTOHINT on every actual glyph load. */
#undef FT_CONFIG_OPTION_SVG
#undef TT_CONFIG_OPTION_EMBEDDED_BITMAPS
#undef TT_CONFIG_OPTION_COLOR_LAYERS
#undef TT_CONFIG_OPTION_SUBPIXEL_HINTING
#undef FT_CONFIG_OPTION_SUBPIXEL_RENDERING
#undef FT_CONFIG_OPTION_ENVIRONMENT_PROPERTIES
#undef FT_CONFIG_OPTION_USE_PNG
#undef FT_CONFIG_OPTION_USE_HARFBUZZ
#undef FT_CONFIG_OPTION_USE_HARFBUZZ_DYNAMIC
#undef TT_CONFIG_OPTION_GX_VAR_SUPPORT

/* Derived feature macros forbidden by the candidate.  TT_USE_BYTECODE_INTERPRETER
 * is deliberately retained and may not be removed. */
#undef TT_SUPPORT_SUBPIXEL_HINTING_MINIMAL
#undef TT_SUPPORT_COLRV1

#endif  /* RENDERWEAVE_FREETYPE_FTOPTION_V2_H_ */
