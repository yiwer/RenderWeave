#!/usr/bin/env python3
"""Generate deterministic Asset acceptance fixtures with the pinned Pillow/fontTools toolchain.

Usage: python tools/generate-asset-fixtures.py <fixtures-dir>

Produces small, deterministic IMAGE/FONT fixture files that the Java kernel tests consume as
inputs. Fixture bytes are inputs only: production admission never reads these paths.
"""
import pathlib
import sys

from PIL import Image
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import newTable


def build_minimal_ttf() -> bytes:
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder([".notdef", "A"])
    fb.setupCharacterMap({0x41: "A"})
    glyphs = {}
    pen = TTGlyphPen(None)
    pen.moveTo((0, 0))
    pen.lineTo((500, 0))
    pen.lineTo((500, 700))
    pen.lineTo((0, 700))
    pen.closePath()
    glyphs["A"] = pen.glyph()
    pen = TTGlyphPen(None)
    pen.moveTo((0, 0))
    pen.lineTo((100, 0))
    pen.lineTo((100, 100))
    pen.lineTo((0, 100))
    pen.closePath()
    glyphs[".notdef"] = pen.glyph()
    fb.setupGlyf(glyphs)
    fb.setupHorizontalMetrics({".notdef": (100, 10), "A": (600, 10)})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupNameTable({"familyName": "AssetFixture", "styleName": "Regular"})
    fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    fb.setupPost()
    maxp = newTable("maxp")
    maxp.tableVersion = 0x00010000
    maxp.numGlyphs = 2
    maxp.maxPoints = 4
    maxp.maxContours = 1
    maxp.maxCompositePoints = 0
    maxp.maxCompositeContours = 0
    maxp.maxZones = 1
    maxp.maxTwilightPoints = 0
    maxp.maxStorage = 0
    maxp.maxFunctionDefs = 0
    maxp.maxInstructionDefs = 0
    maxp.maxStackElements = 0
    maxp.maxSizeOfInstructions = 0
    maxp.maxComponentElements = 0
    maxp.maxComponentDepth = 0
    fb.font["maxp"] = maxp
    from io import BytesIO

    buffer = BytesIO()
    fb.save(buffer)
    return buffer.getvalue()


def build_minimal_otf() -> bytes:
    fb = FontBuilder(1000, isTTF=False)
    fb.setupGlyphOrder([".notdef", "A"])
    fb.setupCharacterMap({0x41: "A"})
    charstrings = {}
    pen = T2CharStringPen(1000, None)
    pen.moveTo((0, 0))
    pen.lineTo((500, 0))
    pen.lineTo((500, 700))
    pen.lineTo((0, 700))
    pen.closePath()
    charstrings["A"] = pen.getCharString()
    pen = T2CharStringPen(1000, None)
    pen.moveTo((0, 0))
    pen.lineTo((100, 0))
    pen.lineTo((100, 100))
    pen.lineTo((0, 100))
    pen.closePath()
    charstrings[".notdef"] = pen.getCharString()
    fb.setupCFF(
        "AssetFixture-Regular",
        {"FullName": "AssetFixture Regular"},
        charstrings,
        {},
    )
    fb.setupHorizontalMetrics({".notdef": (100, 10), "A": (600, 10)})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupNameTable({"familyName": "AssetFixture", "styleName": "Regular"})
    fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    fb.setupPost()
    maxp = newTable("maxp")
    maxp.tableVersion = 0x00005000
    maxp.numGlyphs = 2
    fb.font["maxp"] = maxp
    from io import BytesIO

    buffer = BytesIO()
    fb.save(buffer)
    return buffer.getvalue()


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    out = pathlib.Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)

    # 2x3 grayscale, single component, baseline (non-progressive).
    Image.new("L", (2, 3), color=127).save(
        out / "grayscale-baseline.jpg", "JPEG", quality=90, progressive=False
    )

    # 2x3 RGB saved as 3-component YCbCr, progressive.
    Image.new("RGB", (2, 3), color=(10, 20, 30)).save(
        out / "ycbcr-progressive.jpg", "JPEG", quality=90, progressive=True
    )

    # 2x3 CMYK: 4 components with Adobe APP14 transform=0.
    Image.new("CMYK", (2, 3), color=(0, 0, 0, 0)).save(
        out / "cmyk.jpg", "JPEG", quality=90
    )

    # RGB with an embedded non-sRGB ICC profile.
    Image.new("RGB", (2, 3), color=(10, 20, 30)).save(
        out / "icc-profile.jpg",
        "JPEG",
        quality=90,
        icc_profile=b"garbage-icc-profile-bytes-not-a-real-profile",
    )

    # WebP fixtures for the WebP analyzer slice.
    Image.new("RGB", (2, 3), color=(10, 20, 30)).save(out / "lossy.webp", "WEBP", lossless=False)
    Image.new("RGB", (2, 3), color=(10, 20, 30)).save(out / "lossless.webp", "WEBP", lossless=True)

    # Animated WebP: two frames with animation chunks.
    frames = [
        Image.new("RGB", (2, 3), color=(10, 20, 30)),
        Image.new("RGB", (2, 3), color=(200, 100, 50)),
    ]
    frames[0].save(
        out / "animated.webp",
        "WEBP",
        save_all=True,
        append_images=frames[1:],
        duration=100,
        loop=0,
    )

    # Minimal single-face, non-variable, monochrome-outline TrueType (glyf) font.
    (out / "minimal-ttf.ttf").write_bytes(build_minimal_ttf())

    # Minimal single-face, non-variable, monochrome-outline CFF-flavored OpenType font.
    (out / "minimal-otf.otf").write_bytes(build_minimal_otf())

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
