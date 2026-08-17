#ifndef RENDERWEAVE_FREETYPE_FTOPTION_H_
#define RENDERWEAVE_FREETYPE_FTOPTION_H_

/* Start from the exact FreeType 2.14.3 option surface, then fail closed by
 * removing every module feature forbidden by the Renderer spike candidate. */
#include <freetype/config/ftoption.h>

#undef FT_CONFIG_OPTION_SVG
#undef TT_CONFIG_OPTION_EMBEDDED_BITMAPS
#undef TT_CONFIG_OPTION_COLOR_LAYERS
#undef TT_CONFIG_OPTION_BYTECODE_INTERPRETER
#undef TT_CONFIG_OPTION_SUBPIXEL_HINTING
#undef FT_CONFIG_OPTION_SUBPIXEL_RENDERING
#undef FT_CONFIG_OPTION_ENVIRONMENT_PROPERTIES
#undef FT_CONFIG_OPTION_USE_PNG
#undef FT_CONFIG_OPTION_USE_HARFBUZZ
#undef FT_CONFIG_OPTION_USE_HARFBUZZ_DYNAMIC
#undef TT_CONFIG_OPTION_GX_VAR_SUPPORT

/* These are derived by the stock header and must be removed after the
 * controlling options above. */
#undef TT_USE_BYTECODE_INTERPRETER
#undef TT_SUPPORT_SUBPIXEL_HINTING_MINIMAL
#undef TT_SUPPORT_COLRV1

#endif  /* RENDERWEAVE_FREETYPE_FTOPTION_H_ */
