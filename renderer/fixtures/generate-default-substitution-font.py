#!/usr/bin/env python3
"""Generate the T214 GSUB proof font with fontTools 4.60.1."""

from io import BytesIO
from pathlib import Path
import hashlib
import sys

from fontTools.feaLib.builder import addOpenTypeFeaturesFromString
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import newTable


def rectangle(left: int, right: int, top: int):
    pen = TTGlyphPen(None)
    pen.moveTo((left, 0))
    pen.lineTo((right, 0))
    pen.lineTo((right, top))
    pen.lineTo((left, top))
    pen.closePath()
    return pen.glyph()


def build_font() -> bytes:
    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder([".notdef", "A", "A.alt"])
    builder.setupCharacterMap({0x41: "A"})
    builder.setupGlyf({
        ".notdef": rectangle(0, 100, 100),
        "A": rectangle(0, 200, 700),
        "A.alt": rectangle(300, 500, 700),
    })
    builder.setupHorizontalMetrics({
        ".notdef": (100, 10),
        "A": (600, 10),
        "A.alt": (600, 10),
    })
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({"familyName": "ShapingFixture", "styleName": "Regular"})
    builder.setupOS2(
        sTypoAscender=800,
        sTypoDescender=-200,
        usWinAscent=800,
        usWinDescent=200,
    )
    builder.setupPost()
    builder.font["head"].created = 2082844800
    builder.font["head"].modified = 2082844800
    builder.font.recalcTimestamp = False
    maxp = newTable("maxp")
    maxp.tableVersion = 0x00010000
    maxp.numGlyphs = 3
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
    builder.font["maxp"] = maxp
    addOpenTypeFeaturesFromString(
        builder.font,
        "languagesystem DFLT dflt; feature ccmp { sub A by A.alt; } ccmp;",
    )
    output = BytesIO()
    builder.save(output)
    return output.getvalue()


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: generate-default-substitution-font.py OUTPUT.ttf")
    output = Path(sys.argv[1])
    font = build_font()
    output.write_bytes(font)
    print(f"byteLength={len(font)}")
    print(f"sha256={hashlib.sha256(font).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
