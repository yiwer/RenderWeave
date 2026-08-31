#include "include/core/SkData.h"
#include "include/core/SkFont.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkFontStyle.h"
#include "include/core/SkFontTypes.h"
#include "include/core/SkPath.h"
#include "include/core/SkSpan.h"
#include "include/core/SkTypeface.h"
#include "include/ports/SkFontMgr_data.h"

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MODULE_H
#include FT_XFREE86_H

#include <array>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr FT_Int32 kRequiredLoadFlags =
        FT_LOAD_NO_HINTING | FT_LOAD_NO_AUTOHINT | FT_LOAD_NO_BITMAP | FT_LOAD_NO_SVG;
constexpr FT_Int32 kForbiddenLoadFlags = FT_LOAD_FORCE_AUTOHINT | FT_LOAD_COLOR;

enum class Phase : std::size_t {
    kNone = 0,
    kControlWithoutNoAutoHintFlag = 1,
    kControlRequired = 2,
    kSkiaTricky = 3,
    kSkiaCff = 4,
    kCount = 5,
};

struct Observation {
    std::uint64_t openFaceCount = 0;
    std::uint64_t trickyFaceCount = 0;
    std::uint64_t loadCount = 0;
    std::uint64_t invalidLoadCount = 0;
    std::uint64_t interpreterCallCount = 0;
    std::uint32_t loadFlagsOr = 0;
    std::uint32_t loadFlagsAnd = 0xFFFFFFFFU;
};

std::array<Observation, static_cast<std::size_t>(Phase::kCount)> gObservations{};
Phase gPhase = Phase::kNone;

Observation& currentObservation() {
    return gObservations[static_cast<std::size_t>(gPhase)];
}

Observation& observation(Phase phase) {
    return gObservations[static_cast<std::size_t>(phase)];
}

extern "C" FT_Error TT_RunIns(void* exec);

extern "C" FT_Error renderweaveTrueTypeInterpreterHook(void* exec) {
    currentObservation().interpreterCallCount += 1;
    return TT_RunIns(exec);
}

std::optional<std::vector<std::uint8_t>> readFile(const char* path) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) {
        return std::nullopt;
    }
    const std::streamsize length = input.tellg();
    if (length <= 0) {
        return std::nullopt;
    }
    input.seekg(0, std::ios::beg);
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
    if (!input.read(reinterpret_cast<char*>(bytes.data()), length)) {
        return std::nullopt;
    }
    return bytes;
}

bool loadDirect(const std::vector<std::uint8_t>& bytes,
                Phase phase,
                FT_Int32 flags,
                bool expectTricky,
                std::string_view expectedFormat) {
    gObservations[static_cast<std::size_t>(phase)] = Observation{};
    FT_Library library = nullptr;
    if (FT_Init_FreeType(&library) != 0) {
        std::cerr << "DIRECT_FREETYPE_INIT_FAILED\n";
        return false;
    }

    gPhase = phase;
    FT_Face face = nullptr;
    const FT_Error openError = FT_New_Memory_Face(
            library,
            reinterpret_cast<const FT_Byte*>(bytes.data()),
            static_cast<FT_Long>(bytes.size()),
            0,
            &face);
    if (openError != 0 || face == nullptr) {
        gPhase = Phase::kNone;
        std::cerr << "DIRECT_FACE_OPEN_FAILED error=" << openError
                  << " phase=" << static_cast<std::size_t>(phase)
                  << " truetype=" << (FT_Get_Module(library, "truetype") != nullptr)
                  << " sfnt=" << (FT_Get_Module(library, "sfnt") != nullptr)
                  << " psaux=" << (FT_Get_Module(library, "psaux") != nullptr)
                  << " psnames=" << (FT_Get_Module(library, "psnames") != nullptr)
                  << '\n';
        FT_Done_FreeType(library);
        return false;
    }

    const bool isTricky = FT_IS_TRICKY(face);
    const char* format = FT_Get_X11_Font_Format(face);
    const bool formatMatches = format != nullptr && expectedFormat == format;
    const bool scalable = FT_IS_SCALABLE(face) && face->num_fixed_sizes == 0;
    const FT_UInt glyph = FT_Get_Char_Index(face, static_cast<FT_ULong>('A'));
    const FT_Error sizeError = FT_Set_Pixel_Sizes(face, 0, 32);
    const FT_Error loadError = glyph == 0 ? 1 : FT_Load_Glyph(face, glyph, flags);

    FT_Done_Face(face);
    gPhase = Phase::kNone;
    FT_Done_FreeType(library);

    if (isTricky != expectTricky || !formatMatches || !scalable || sizeError != 0 ||
        loadError != 0) {
        std::cerr << "DIRECT_FACE_CONTRACT_FAILED\n";
        return false;
    }
    return true;
}

bool loadThroughSkia(const std::vector<std::uint8_t>& bytes,
                     Phase phase,
                     const char* familyName) {
    gObservations[static_cast<std::size_t>(phase)] = Observation{};
    gPhase = phase;

    sk_sp<SkData> data = SkData::MakeWithCopy(bytes.data(), bytes.size());
    if (!data) {
        gPhase = Phase::kNone;
        std::cerr << "SKIA_DATA_FAILED\n";
        return false;
    }
    std::array<sk_sp<SkData>, 1> fontData{data};
    sk_sp<SkFontMgr> manager = SkFontMgr_New_Custom_Data(
            SkSpan<sk_sp<SkData>>(fontData.data(), fontData.size()));
    if (!manager) {
        gPhase = Phase::kNone;
        std::cerr << "SKIA_FONT_MANAGER_FAILED\n";
        return false;
    }
    sk_sp<SkTypeface> typeface = manager->matchFamilyStyle(familyName, SkFontStyle());
    if (!typeface) {
        gPhase = Phase::kNone;
        std::cerr << "SKIA_TYPEFACE_FAILED\n";
        return false;
    }

    const SkGlyphID glyph = typeface->unicharToGlyph('A');
    SkFont font(typeface, 32.0f);
    font.setHinting(SkFontHinting::kNone);
    const std::optional<SkPath> path = font.getPath(glyph);
    const bool validPath = glyph != 0 && path.has_value() && !path->isEmpty() &&
                           !path->getBounds().isEmpty();
    gPhase = Phase::kNone;
    if (!validPath) {
        std::cerr << "SKIA_GLYPH_PATH_FAILED\n";
        return false;
    }
    return true;
}

bool productionObservationPassed(const Observation& value) {
    return value.loadCount > 0 && value.invalidLoadCount == 0 &&
           value.interpreterCallCount == 0 &&
           (value.loadFlagsAnd & static_cast<std::uint32_t>(kRequiredLoadFlags)) ==
                   static_cast<std::uint32_t>(kRequiredLoadFlags) &&
           (value.loadFlagsOr & static_cast<std::uint32_t>(kForbiddenLoadFlags)) == 0;
}

void writeObservation(const char* name, const Observation& value) {
    std::cout << '"' << name << "\":{";
    std::cout << "\"openFaceCount\":" << value.openFaceCount << ',';
    std::cout << "\"trickyFaceCount\":" << value.trickyFaceCount << ',';
    std::cout << "\"loadCount\":" << value.loadCount << ',';
    std::cout << "\"invalidLoadCount\":" << value.invalidLoadCount << ',';
    std::cout << "\"interpreterCallCount\":" << value.interpreterCallCount << ',';
    std::cout << "\"loadFlagsOr\":" << value.loadFlagsOr << ',';
    std::cout << "\"loadFlagsAnd\":" << value.loadFlagsAnd << '}';
}

}  // namespace

extern "C" FT_Error __real_FT_New_Library(FT_Memory memory, FT_Library* library);
extern "C" FT_Error __wrap_FT_New_Library(FT_Memory memory, FT_Library* library) {
    const FT_Error error = __real_FT_New_Library(memory, library);
    if (error == 0 && library != nullptr && *library != nullptr) {
        FT_Set_Debug_Hook(
                *library, FT_DEBUG_HOOK_TRUETYPE, renderweaveTrueTypeInterpreterHook);
    }
    return error;
}

extern "C" FT_Error __real_FT_Open_Face(
        FT_Library library, const FT_Open_Args* args, FT_Long faceIndex, FT_Face* face);
extern "C" FT_Error __wrap_FT_Open_Face(
        FT_Library library, const FT_Open_Args* args, FT_Long faceIndex, FT_Face* face) {
    const FT_Error error = __real_FT_Open_Face(library, args, faceIndex, face);
    if (gPhase != Phase::kNone && error == 0 && face != nullptr && *face != nullptr) {
        Observation& value = currentObservation();
        value.openFaceCount += 1;
        if (FT_IS_TRICKY(*face)) {
            value.trickyFaceCount += 1;
        }
    }
    return error;
}

extern "C" FT_Error __real_FT_Load_Glyph(FT_Face face, FT_UInt glyphIndex, FT_Int32 flags);
extern "C" FT_Error __wrap_FT_Load_Glyph(
        FT_Face face, FT_UInt glyphIndex, FT_Int32 flags) {
    if (gPhase != Phase::kNone) {
        Observation& value = currentObservation();
        const std::uint32_t observed = static_cast<std::uint32_t>(flags);
        value.loadCount += 1;
        value.loadFlagsOr |= observed;
        value.loadFlagsAnd &= observed;
        if (gPhase == Phase::kSkiaTricky || gPhase == Phase::kSkiaCff) {
            if ((flags & kRequiredLoadFlags) != kRequiredLoadFlags ||
                (flags & kForbiddenLoadFlags) != 0) {
                value.invalidLoadCount += 1;
            }
        }
    }
    return __real_FT_Load_Glyph(face, glyphIndex, flags);
}

int main(int argc, char** argv) {
    if (argc != 3) {
        std::cerr << "usage: instrumented_probe TRICKY_TTF CFF_OTF\n";
        return 2;
    }
    const auto trickyBytes = readFile(argv[1]);
    const auto cffBytes = readFile(argv[2]);
    if (!trickyBytes || !cffBytes) {
        std::cerr << "PROBE_INPUT_READ_FAILED\n";
        return 1;
    }

    const FT_Int32 controlFlags =
            FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP | FT_LOAD_NO_SVG;
    if (!loadDirect(*trickyBytes,
                    Phase::kControlWithoutNoAutoHintFlag,
                    controlFlags,
                    true,
                    "TrueType") ||
        !loadDirect(*trickyBytes,
                    Phase::kControlRequired,
                    kRequiredLoadFlags,
                    true,
                    "TrueType") ||
        !loadThroughSkia(*trickyBytes,
                         Phase::kSkiaTricky,
                         "RenderWeave cpop Fixture") ||
        !loadDirect(*cffBytes, Phase::kNone, kRequiredLoadFlags, false, "CFF") ||
        !loadThroughSkia(*cffBytes, Phase::kSkiaCff, "AssetFixture")) {
        return 1;
    }

    const Observation& noAutoHint = observation(Phase::kControlWithoutNoAutoHintFlag);
    const Observation& required = observation(Phase::kControlRequired);
    const Observation& skiaTricky = observation(Phase::kSkiaTricky);
    const Observation& skiaCff = observation(Phase::kSkiaCff);
    if (noAutoHint.interpreterCallCount == 0 || required.interpreterCallCount != 0 ||
        !productionObservationPassed(skiaTricky) ||
        !productionObservationPassed(skiaCff)) {
        std::cerr << "INSTRUMENTED_POLICY_ASSERTION_FAILED\n";
        return 1;
    }

    std::cout << "{\"artifactVersion\":\"renderweave-renderer-instrumented-probe/1.1\",";
    std::cout << "\"candidateId\":\"rw-renderer-spike-linux-x86_64-v2-000002\",";
    std::cout << "\"rehearsalConfigurationId\":\"rw-renderer-t213-adapter-rehearsal-000001\",";
    std::cout << "\"status\":\"PASS_ADAPTER_REHEARSAL\",";
    writeObservation("controlNoAutoHint", noAutoHint);
    std::cout << ',';
    writeObservation("controlRequired", required);
    std::cout << ',';
    writeObservation("skiaTricky", skiaTricky);
    std::cout << ',';
    writeObservation("skiaCff", skiaCff);
    std::cout << "}\n";
    return 0;
}
