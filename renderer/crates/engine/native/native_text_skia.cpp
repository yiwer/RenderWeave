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

    sk_sp<SkData> fontData = SkData::MakeWithCopy(fontBytes, fontLength);
    if (!fontData) {
        return kFontDecodeFailed;
    }
    sk_sp<SkFontMgr> fontManager = SkFontMgr_New_Custom_Data(
            SkSpan<sk_sp<SkData>>(&fontData, 1));
    if (!fontManager) {
        return kFontDecodeFailed;
    }
    sk_sp<SkTypeface> typeface = fontManager->makeFromData(fontData, 0);
    if (!typeface) {
        return kFontDecodeFailed;
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
        return kFontDecodeFailed;
    }
    HbHandle<hb_face_t, hb_face_destroy> face(
            hb_face_create(blob.get(), 0), hb_face_destroy);
    if (!face || hb_face_get_glyph_count(face.get()) == 0) {
        return kFontDecodeFailed;
    }
    const unsigned int unitsPerEm = hb_face_get_upem(face.get());
    if (unitsPerEm == 0) {
        return kFontDecodeFailed;
    }
    HbHandle<hb_font_t, hb_font_destroy> shapingFont(
            hb_font_create(face.get()), hb_font_destroy);
    HbHandle<hb_buffer_t, hb_buffer_destroy> buffer(
            hb_buffer_create(), hb_buffer_destroy);
    if (!shapingFont || !buffer) {
        return kShapingFailed;
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
        return kShapingFailed;
    }
    hb_shape(shapingFont.get(), buffer.get(), nullptr, 0);
    if (!hb_buffer_allocation_successful(buffer.get())) {
        return kShapingFailed;
    }

    unsigned int glyphCount = 0;
    const hb_glyph_info_t* glyphInfos =
            hb_buffer_get_glyph_infos(buffer.get(), &glyphCount);
    unsigned int positionCount = 0;
    const hb_glyph_position_t* glyphPositions =
            hb_buffer_get_glyph_positions(buffer.get(), &positionCount);
    if (glyphCount == 0 || positionCount != glyphCount || glyphInfos == nullptr ||
        glyphPositions == nullptr) {
        return kShapingFailed;
    }
    std::unique_ptr<SkGlyphID[]> glyphs(new (std::nothrow) SkGlyphID[glyphCount]);
    std::unique_ptr<SkPoint[]> positions(new (std::nothrow) SkPoint[glyphCount]);
    if (!glyphs || !positions) {
        return kRasterFailed;
    }
    for (unsigned int index = 0; index < glyphCount; ++index) {
        if (glyphInfos[index].codepoint == 0 ||
            glyphInfos[index].codepoint > std::numeric_limits<SkGlyphID>::max()) {
            return kGlyphMissing;
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
        return kShapingFailed;
    }
    for (unsigned int index = 0; index < glyphCount; ++index) {
        const float x = cursorX + static_cast<float>(glyphPositions[index].x_offset) * unitToPixel;
        const float y = baseline -
                (cursorY + static_cast<float>(glyphPositions[index].y_offset) * unitToPixel);
        if (!std::isfinite(x) || !std::isfinite(y)) {
            return kShapingFailed;
        }
        positions[index] = SkPoint::Make(x, y);
        cursorX += static_cast<float>(glyphPositions[index].x_advance) * unitToPixel;
        cursorY += static_cast<float>(glyphPositions[index].y_advance) * unitToPixel;
        if (!std::isfinite(cursorX) || !std::isfinite(cursorY)) {
            return kShapingFailed;
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
        return kRasterFailed;
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
    return kSuccess;
}
