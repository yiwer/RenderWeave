#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkFont.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkFontMetrics.h"
#include "include/core/SkImageInfo.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPoint.h"
#include "include/core/SkSpan.h"
#include "include/ports/SkFontMgr_data.h"

#include "hb.h"
#include "hb-ot.h"

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MODULE_H

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>

namespace {

constexpr int kSuccess = 0;
constexpr int kInvalidArgument = 1;
constexpr int kFontDecodeFailed = 2;
constexpr int kGlyphMissing = 3;
constexpr int kShapingFailed = 4;
constexpr int kRasterFailed = 5;
constexpr int kGlyphPolicyViolation = 6;

constexpr FT_Int32 kRequiredLoadFlags =
        FT_LOAD_NO_HINTING | FT_LOAD_NO_AUTOHINT | FT_LOAD_NO_BITMAP | FT_LOAD_NO_SVG;
constexpr FT_Int32 kForbiddenLoadFlags = FT_LOAD_FORCE_AUTOHINT | FT_LOAD_COLOR;

struct GlyphPolicyObservation {
    bool active = false;
    std::uint64_t loadCount = 0;
    std::uint64_t invalidLoadCount = 0;
    std::uint64_t interpreterCallCount = 0;
    std::uint32_t loadFlagsOr = 0;
    std::uint32_t loadFlagsAnd = std::numeric_limits<std::uint32_t>::max();
};

thread_local GlyphPolicyObservation gGlyphPolicyObservation{};

bool glyphFlagsAreAllowed(FT_Int32 flags) {
    return (flags & kRequiredLoadFlags) == kRequiredLoadFlags &&
           (flags & kForbiddenLoadFlags) == 0;
}

void beginGlyphPolicyObservation() {
    gGlyphPolicyObservation = GlyphPolicyObservation{};
    gGlyphPolicyObservation.active = true;
}

int finishGlyphPolicyObservation(int result) {
    const bool observedViolation =
            gGlyphPolicyObservation.invalidLoadCount != 0 ||
            gGlyphPolicyObservation.interpreterCallCount != 0;
    const bool successfulLoadWasProved =
            result != kSuccess ||
            (gGlyphPolicyObservation.loadCount != 0 &&
             (gGlyphPolicyObservation.loadFlagsAnd &
              static_cast<std::uint32_t>(kRequiredLoadFlags)) ==
                     static_cast<std::uint32_t>(kRequiredLoadFlags) &&
             (gGlyphPolicyObservation.loadFlagsOr &
              static_cast<std::uint32_t>(kForbiddenLoadFlags)) == 0);
    gGlyphPolicyObservation.active = false;
    return observedViolation || !successfulLoadWasProved ? kGlyphPolicyViolation : result;
}

FT_Error renderweaveRejectTrueTypeInterpreter(void*) {
    if (gGlyphPolicyObservation.active) {
        gGlyphPolicyObservation.interpreterCallCount += 1;
    }
    return FT_Err_Invalid_Argument;
}

template <typename T, void (*Destroy)(T*)>
using HbHandle = std::unique_ptr<T, decltype(Destroy)>;

bool validBufferLength(std::uint32_t width,
                       std::uint32_t height,
                       std::size_t outputLength) {
    if (width == 0 || height == 0) {
        return false;
    }
    const std::uint64_t required =
            static_cast<std::uint64_t>(width) * static_cast<std::uint64_t>(height) * 4U;
    return required <= std::numeric_limits<std::size_t>::max() &&
           outputLength == static_cast<std::size_t>(required);
}

}  // namespace

extern "C" FT_Error __real_FT_Load_Glyph(
        FT_Face face, FT_UInt glyphIndex, FT_Int32 flags);

extern "C" FT_Error __wrap_FT_Load_Glyph(
        FT_Face face, FT_UInt glyphIndex, FT_Int32 flags) {
    if (gGlyphPolicyObservation.active) {
        const std::uint32_t observed = static_cast<std::uint32_t>(flags);
        gGlyphPolicyObservation.loadCount += 1;
        gGlyphPolicyObservation.loadFlagsOr |= observed;
        gGlyphPolicyObservation.loadFlagsAnd &= observed;
    }
    if (!glyphFlagsAreAllowed(flags)) {
        if (gGlyphPolicyObservation.active) {
            gGlyphPolicyObservation.invalidLoadCount += 1;
        }
        return FT_Err_Invalid_Argument;
    }
    return __real_FT_Load_Glyph(face, glyphIndex, flags);
}

extern "C" FT_Error __real_FT_New_Library(FT_Memory memory, FT_Library* library);

extern "C" FT_Error __wrap_FT_New_Library(FT_Memory memory, FT_Library* library) {
    const FT_Error result = __real_FT_New_Library(memory, library);
    if (result == 0 && library != nullptr && *library != nullptr) {
        FT_Set_Debug_Hook(
                *library, FT_DEBUG_HOOK_TRUETYPE, renderweaveRejectTrueTypeInterpreter);
    }
    return result;
}

extern "C" int renderweave_glyph_policy_accepts_flags(std::uint32_t flags) {
    return glyphFlagsAreAllowed(static_cast<FT_Int32>(flags)) ? 1 : 0;
}

extern "C" int renderweave_skia_raster_text(
        const std::uint8_t* fontBytes,
        std::size_t fontLength,
        std::uint32_t codepoint,
        float fontSizePx,
        std::uint8_t red,
        std::uint8_t green,
        std::uint8_t blue,
        std::uint8_t alpha,
        std::uint32_t width,
        std::uint32_t height,
        std::uint8_t* output,
        std::size_t outputLength) {
    if (fontBytes == nullptr || fontLength == 0 ||
        fontLength > std::numeric_limits<unsigned int>::max() || output == nullptr ||
        !(fontSizePx > 0.0F) || !std::isfinite(fontSizePx) ||
        !validBufferLength(width, height, outputLength)) {
        return kInvalidArgument;
    }

    beginGlyphPolicyObservation();

    sk_sp<SkData> fontData = SkData::MakeWithCopy(fontBytes, fontLength);
    if (!fontData) {
        return finishGlyphPolicyObservation(kFontDecodeFailed);
    }
    sk_sp<SkFontMgr> fontManager = SkFontMgr_New_Custom_Data(
            SkSpan<sk_sp<SkData>>(&fontData, 1));
    if (!fontManager) {
        return finishGlyphPolicyObservation(kFontDecodeFailed);
    }
    sk_sp<SkTypeface> typeface = fontManager->makeFromData(fontData, 0);
    if (!typeface) {
        return finishGlyphPolicyObservation(kFontDecodeFailed);
    }

    HbHandle<hb_blob_t, hb_blob_destroy> blob(
            hb_blob_create(
                    reinterpret_cast<const char*>(fontBytes),
                    static_cast<unsigned int>(fontLength),
                    HB_MEMORY_MODE_READONLY,
                    nullptr,
                    nullptr),
            hb_blob_destroy);
    if (!blob || hb_blob_get_length(blob.get()) != fontLength) {
        return finishGlyphPolicyObservation(kFontDecodeFailed);
    }
    HbHandle<hb_face_t, hb_face_destroy> face(
            hb_face_create(blob.get(), 0), hb_face_destroy);
    if (!face || hb_face_get_glyph_count(face.get()) == 0) {
        return finishGlyphPolicyObservation(kFontDecodeFailed);
    }
    const unsigned int unitsPerEm = hb_face_get_upem(face.get());
    if (unitsPerEm == 0) {
        return finishGlyphPolicyObservation(kFontDecodeFailed);
    }
    HbHandle<hb_font_t, hb_font_destroy> shapingFont(
            hb_font_create(face.get()), hb_font_destroy);
    HbHandle<hb_buffer_t, hb_buffer_destroy> buffer(
            hb_buffer_create(), hb_buffer_destroy);
    if (!shapingFont || !buffer) {
        return finishGlyphPolicyObservation(kShapingFailed);
    }
    hb_ot_font_set_funcs(shapingFont.get());
    hb_font_set_scale(
            shapingFont.get(),
            static_cast<int>(unitsPerEm),
            static_cast<int>(unitsPerEm));
    hb_buffer_set_cluster_level(buffer.get(), HB_BUFFER_CLUSTER_LEVEL_MONOTONE_CHARACTERS);
    hb_buffer_set_content_type(buffer.get(), HB_BUFFER_CONTENT_TYPE_UNICODE);
    hb_buffer_set_direction(buffer.get(), HB_DIRECTION_LTR);
    hb_buffer_set_script(buffer.get(), HB_SCRIPT_LATIN);
    hb_buffer_set_language(buffer.get(), hb_language_from_string("und", -1));
    hb_buffer_add(buffer.get(), codepoint, 0);
    if (!hb_buffer_allocation_successful(buffer.get())) {
        return finishGlyphPolicyObservation(kShapingFailed);
    }
    hb_shape(shapingFont.get(), buffer.get(), nullptr, 0);
    if (!hb_buffer_allocation_successful(buffer.get())) {
        return finishGlyphPolicyObservation(kShapingFailed);
    }

    unsigned int glyphCount = 0;
    const hb_glyph_info_t* glyphInfos =
            hb_buffer_get_glyph_infos(buffer.get(), &glyphCount);
    unsigned int positionCount = 0;
    const hb_glyph_position_t* glyphPositions =
            hb_buffer_get_glyph_positions(buffer.get(), &positionCount);
    if (glyphCount == 0 || positionCount != glyphCount || glyphInfos == nullptr ||
        glyphPositions == nullptr) {
        return finishGlyphPolicyObservation(kShapingFailed);
    }
    std::unique_ptr<SkGlyphID[]> glyphs(new (std::nothrow) SkGlyphID[glyphCount]);
    std::unique_ptr<SkPoint[]> positions(new (std::nothrow) SkPoint[glyphCount]);
    if (!glyphs || !positions) {
        return finishGlyphPolicyObservation(kRasterFailed);
    }
    for (unsigned int index = 0; index < glyphCount; ++index) {
        if (glyphInfos[index].codepoint == 0 ||
            glyphInfos[index].codepoint > std::numeric_limits<SkGlyphID>::max()) {
            return finishGlyphPolicyObservation(kGlyphMissing);
        }
        glyphs[index] = static_cast<SkGlyphID>(glyphInfos[index].codepoint);
    }

    SkFont font(typeface, fontSizePx);
    font.setEdging(SkFont::Edging::kAntiAlias);
    font.setHinting(SkFontHinting::kNone);
    font.setSubpixel(true);
    font.setLinearMetrics(true);
    font.setEmbeddedBitmaps(false);
    font.setForceAutoHinting(false);
    SkFontMetrics metrics;
    font.getMetrics(&metrics);
    const float unitToPixel = fontSizePx / static_cast<float>(unitsPerEm);
    float cursorX = 0.0F;
    float cursorY = 0.0F;
    const SkScalar baseline = -metrics.fAscent;
    if (!std::isfinite(unitToPixel) || !std::isfinite(baseline)) {
        return finishGlyphPolicyObservation(kShapingFailed);
    }
    for (unsigned int index = 0; index < glyphCount; ++index) {
        const float x = cursorX + static_cast<float>(glyphPositions[index].x_offset) * unitToPixel;
        const float y = baseline -
                (cursorY + static_cast<float>(glyphPositions[index].y_offset) * unitToPixel);
        if (!std::isfinite(x) || !std::isfinite(y)) {
            return finishGlyphPolicyObservation(kShapingFailed);
        }
        positions[index] = SkPoint::Make(x, y);
        cursorX += static_cast<float>(glyphPositions[index].x_advance) * unitToPixel;
        cursorY += static_cast<float>(glyphPositions[index].y_advance) * unitToPixel;
        if (!std::isfinite(cursorX) || !std::isfinite(cursorY)) {
            return finishGlyphPolicyObservation(kShapingFailed);
        }
    }

    const SkImageInfo imageInfo = SkImageInfo::Make(
            static_cast<int>(width),
            static_cast<int>(height),
            kRGBA_8888_SkColorType,
            kPremul_SkAlphaType,
            SkColorSpace::MakeSRGB());
    std::unique_ptr<SkCanvas> canvas =
            SkCanvas::MakeRasterDirect(imageInfo, output, static_cast<std::size_t>(width) * 4U);
    if (!canvas) {
        return finishGlyphPolicyObservation(kRasterFailed);
    }
    canvas->clear(SK_ColorTRANSPARENT);
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setColor(SkColorSetARGB(alpha, red, green, blue));
    canvas->drawGlyphs(
            SkSpan<const SkGlyphID>(glyphs.get(), glyphCount),
            SkSpan<const SkPoint>(positions.get(), glyphCount),
            SkPoint::Make(0.0F, 0.0F),
            font,
            paint);
    return finishGlyphPolicyObservation(kSuccess);
}
