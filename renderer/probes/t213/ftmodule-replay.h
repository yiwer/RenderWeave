/*
 * FreeType expands FT_CONFIG_MODULES_H more than once with different
 * FT_USE_MODULE definitions.  Keep the frozen candidate-v2 header bytes
 * untouched, then deliberately make its declarations replayable.
 */
#include <renderweave-freetype/freetype/config/ftmodule.h>
#undef RENDERWEAVE_FREETYPE_FTMODULE_H_
