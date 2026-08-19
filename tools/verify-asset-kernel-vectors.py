#!/usr/bin/env python3
"""Independent replay for the Asset acceptance kernel frozen vectors.

Re-derives every expected outcome from the frozen spec using Pillow/fontTools and
standalone structural checks, then compares the frozen manifest, the Java primary
report and this independent computation.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import struct
import zlib
from pathlib import Path
from typing import Any

from PIL import Image
from fontTools.ttLib import TTFont

VECTOR_VERSION = "renderweave-asset-acceptance-kernel-v1/1"
PROFILE = "renderweave-asset-acceptance/1.0"
IMAGE_LIMIT = 64 * 1024 * 1024
FONT_LIMIT = 32 * 1024 * 1024
MAX_EDGE = 20_000
MAX_TOTAL = 100_000_000
CHECKSUM_MAGIC = 0xB1B0AFBA
HEAD_MAGIC = 0x5F0F3CF5

ORIENTATIONS = [
    "IDENTITY",
    "MIRROR_HORIZONTAL",
    "ROTATE_180",
    "MIRROR_VERTICAL",
    "TRANSPOSE",
    "ROTATE_90_CW",
    "TRANSVERSE",
    "ROTATE_270_CW",
]

BANNED_TABLES = {
    "COLR",
    "CPAL",
    "CBDT",
    "CBLC",
    "sbix",
    "SVG ",
    "EBDT",
    "EBLC",
    "EBSC",
    "bdat",
    "bloc",
    "fvar",
    "gvar",
    "CFF2",
    "Silf",
    "Glat",
    "Gloc",
    "morx",
    "mort",
    "feat",
}
GLYF_REQUIRED = {"cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "OS/2", "post"}
CFF_REQUIRED = {"CFF ", "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post"}


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from(">H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from(">I", data, offset)[0]


def table_checksum(data: bytes, offset: int, length: int) -> int:
    total = 0
    for i in range(0, length, 4):
        remaining = min(4, length - i)
        chunk = data[offset + i : offset + i + remaining]
        chunk = chunk + b"\x00" * (4 - len(chunk))
        total = (total + struct.unpack(">I", chunk)[0]) & 0xFFFFFFFF
    return total


def admitted_image(raw: bytes, width: int, height: int, orientation: str) -> dict[str, Any]:
    swap = orientation in ("TRANSPOSE", "ROTATE_90_CW", "TRANSVERSE", "ROTATE_270_CW")
    logical_w = height if swap else width
    logical_h = width if swap else height
    return {
        "outcome": "ADMITTED",
        "kind": "IMAGE",
        "byteLength": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "acceptanceProfileId": PROFILE,
        "descriptor": {
            "type": "IMAGE",
            "encodedWidthPx": width,
            "encodedHeightPx": height,
            "orientation": orientation,
            "logicalWidthPx": logical_w,
            "logicalHeightPx": logical_h,
            "frameCount": 1,
            "colorEncoding": "SRGB_8BIT",
        },
    }


def admitted_font(raw: bytes, flavor: str, units_per_em: int) -> dict[str, Any]:
    return {
        "outcome": "ADMITTED",
        "kind": "FONT",
        "byteLength": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "acceptanceProfileId": PROFILE,
        "descriptor": {"type": "FONT", "faceIndex": 0, "flavor": flavor, "unitsPerEm": units_per_em},
    }


def rejected(code: str, stage: str, pointer: str, limit: str | None = None) -> dict[str, Any]:
    return {"outcome": "REJECTED", "code": code, "stage": stage, "pointer": pointer, "limit": limit}


class Malformed(Exception):
    def __init__(self, result: dict[str, Any]) -> None:
        super().__init__(result["pointer"])
        self.result = result


def exif_orientation(data: bytes) -> int:
    if len(data) < 8:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/EXIF"))
    if data[:2] in (b"II", b"MM"):
        little = data[:2] == b"II"
        if u16(data, 2) != 0x002A:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/EXIF"))
        ifd0 = u32(data, 4)
        if ifd0 > len(data) - 2:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/EXIF"))
        count = u16(data, ifd0)
        offset = ifd0 + 2
        orientation = 0
        for _ in range(count):
            if offset > len(data) - 12:
                raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/EXIF"))
            tag = struct.unpack_from("<H" if little else ">H", data, offset)[0]
            typ = struct.unpack_from("<H" if little else ">H", data, offset + 2)[0]
            n = struct.unpack_from("<I" if little else ">I", data, offset + 4)[0]
            if tag == 0x0112:
                if typ != 3 or n != 1:
                    raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/EXIF"))
                value = struct.unpack_from("<H" if little else ">H", data, offset + 8)[0]
                if orientation not in (0, value):
                    raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/EXIF"))
                orientation = value
            offset += 12
        if orientation == 0:
            return 0
        if not 1 <= orientation <= 8:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/EXIF"))
        return orientation
    return 0


def png_admit(raw: bytes) -> dict[str, Any]:
    if len(raw) < 8 or raw[:8] != bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]):
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    position = 8
    first = True
    width = height = 0
    color = -1
    saw_plte = False
    srgb = False
    iccp = False
    orientation = 0
    while position < len(raw):
        if len(raw) - position < 12:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        length = u32(raw, position)
        chunk_type = raw[position + 4 : position + 8]
        if len(raw) - position < 12 + length:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        stored = u32(raw, position + 8 + length)
        actual = binascii.crc32(raw[position + 4 : position + 8 + length]) & 0xFFFFFFFF
        if actual != stored:
            raise Malformed(
                rejected(
                    "ASSET_CONTENT_INVALID",
                    "ASSET_STRUCTURE",
                    "/" + chunk_type.decode("latin1"),
                )
            )
        if first:
            if chunk_type != b"IHDR":
                raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
            first = False
            width = u32(raw, position + 8)
            height = u32(raw, position + 12)
            depth = raw[position + 16]
            color = raw[position + 17]
            if width <= 0 or height <= 0:
                raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/ihdr"))
            if width > MAX_EDGE or height > MAX_EDGE:
                raise Malformed(
                    rejected(
                        "ASSET_CONTENT_LIMIT_EXCEEDED",
                        "ASSET_STRUCTURE",
                        "/ihdr",
                        "assetAcceptance.imageEdgePixels",
                    )
                )
            if width * height > MAX_TOTAL:
                raise Malformed(
                    rejected(
                        "ASSET_CONTENT_LIMIT_EXCEEDED",
                        "ASSET_STRUCTURE",
                        "/ihdr",
                        "assetAcceptance.imageTotalPixels",
                    )
                )
            if depth == 16:
                raise Malformed(
                    rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/ihdr")
                )
        elif chunk_type == b"acTL":
            raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/acTL"))
        elif chunk_type == b"PLTE":
            if color in (0, 4) or length == 0 or length % 3 != 0 or length > 768:
                raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/PLTE"))
            saw_plte = True
        elif chunk_type == b"tRNS":
            if color in (4, 6):
                raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/tRNS"))
        elif chunk_type == b"sRGB":
            srgb = True
        elif chunk_type == b"iCCP":
            iccp = True
        elif chunk_type == b"eXIf":
            orientation = exif_orientation(raw[position + 8 : position + 8 + length])
        elif chunk_type == b"IEND":
            break
        elif chunk_type[0] & 0x20 == 0 and chunk_type not in (b"IHDR", b"PLTE", b"IDAT", b"IEND"):
            raise Malformed(
                rejected(
                    "ASSET_CONTENT_INVALID",
                    "ASSET_STRUCTURE",
                    "/" + chunk_type.decode("latin1"),
                )
            )
        position += 12 + length
    if srgb and iccp:
        raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_DESCRIPTOR", "/iCCP"))
    if iccp:
        raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_DESCRIPTOR", "/iCCP"))
    if color == 3 and not saw_plte:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/PLTE"))
    try:
        image = Image.open(_BytesIO(raw))
        image.load()
    except Exception:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_DECODE", "/IDAT")) from None
    if image.width != width or image.height != height:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_DECODE", "/IDAT"))
    return admitted_image(
        raw, width, height, ORIENTATIONS[orientation - 1] if orientation else "IDENTITY"
    )


def jpeg_admit(raw: bytes) -> dict[str, Any]:
    if len(raw) < 4 or raw[:2] != b"\xff\xd8":
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    position = 2
    saw_sof = saw_sos = saw_eoi = False
    width = height = 0
    components = 0
    adobe = -1
    orientation = 0
    iccp = False
    while position < len(raw):
        if raw[position] != 0xFF or position + 1 >= len(raw):
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        marker = raw[position + 1]
        if marker == 0xD9:
            saw_eoi = True
            break
        if marker == 0x00 or marker == 0xFF or marker == 0xD8:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        if 0xD0 <= marker <= 0xD7:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        if position + 4 > len(raw):
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        length = struct.unpack_from(">H", raw, position + 2)[0]
        if length < 2 or position + 2 + length > len(raw):
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        payload = raw[position + 4 : position + 2 + length]
        if marker in (0xC0, 0xC2):
            if saw_sof:
                raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/sof"))
            saw_sof = True
            if payload[0] != 8:
                raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/sof"))
            height = struct.unpack_from(">H", payload, 1)[0]
            width = struct.unpack_from(">H", payload, 3)[0]
            components = payload[5]
            if components not in (1, 3):
                raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/sof"))
        elif marker in (0xC1, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/sof"))
        elif marker == 0xCC:
            raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/dac"))
        elif marker == 0xDC or marker == 0xDE or marker == 0xDF:
            raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/"))
        elif marker == 0xDA:
            if not saw_sof:
                raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
            saw_sos = True
            position += 2 + length
            while position < len(raw):
                if raw[position] != 0xFF:
                    position += 1
                    continue
                if position + 1 >= len(raw):
                    raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
                following = raw[position + 1]
                if following == 0x00:
                    position += 2
                elif following == 0xFF:
                    position += 1
                elif 0xD0 <= following <= 0xD7:
                    position += 2
                else:
                    break
            continue
        elif 0xE0 <= marker <= 0xEF:
            if payload.startswith(b"Exif\x00\x00"):
                orientation = exif_orientation(payload[6:])
            elif payload.startswith(b"ICC_PROFILE\x00"):
                iccp = True
            elif payload.startswith(b"Adobe") and len(payload) >= 12:
                adobe = payload[11]
        position += 2 + length
    if not (saw_sof and saw_sos and saw_eoi):
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    if iccp:
        raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_DESCRIPTOR", "/ICC"))
    if components == 3 and adobe == 0:
        raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/sof"))
    if adobe == 2:
        raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/sof"))
    try:
        image = Image.open(_BytesIO(raw))
        image.load()
    except Exception:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_DECODE", "/SOS")) from None
    if image.mode == "CMYK":
        raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/sof"))
    if image.width != width or image.height != height:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_DECODE", "/SOS"))
    return admitted_image(
        raw, width, height, ORIENTATIONS[orientation - 1] if orientation else "IDENTITY"
    )


def webp_admit(raw: bytes) -> dict[str, Any]:
    if len(raw) < 12 or raw[:4] != b"RIFF" or raw[8:12] != b"WEBP":
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    riff_size = struct.unpack_from("<I", raw, 4)[0]
    if riff_size + 8 != len(raw):
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    position = 12
    saw_image = False
    width = height = 0
    orientation = 0
    iccp = False
    while position < len(raw):
        if position + 8 > len(raw):
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        four_cc = raw[position : position + 4]
        size = struct.unpack_from("<I", raw, position + 4)[0]
        padded = position + 8 + size + (size % 2)
        if padded > len(raw):
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        if saw_image:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
        if four_cc == b"VP8X":
            flags = raw[position + 8]
            if flags & 0x20:
                raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/VP8X"))
            width = int.from_bytes(raw[position + 12 : position + 15], "little") + 1
            height = int.from_bytes(raw[position + 15 : position + 18], "little") + 1
        elif four_cc in (b"ANIM", b"ANMF"):
            raise Malformed(
                rejected(
                    "ASSET_CONTENT_UNSUPPORTED",
                    "ASSET_STRUCTURE",
                    "/" + four_cc.decode("latin1"),
                )
            )
        elif four_cc == b"ICCP":
            iccp = True
        elif four_cc == b"EXIF":
            orientation = exif_orientation(raw[position + 8 : position + 8 + size])
        elif four_cc in (b"VP8 ", b"VP8L"):
            saw_image = True
            if four_cc == b"VP8L":
                if size < 5 or raw[position + 8] != 0x2F:
                    raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/VP8L"))
                b1 = raw[position + 9]
                b2 = raw[position + 10]
                b3 = raw[position + 11]
                b4 = raw[position + 12]
                frame_w = (b1 | ((b2 & 0x3F) << 8)) + 1
                frame_h = ((b2 >> 6) | (b3 << 2) | ((b4 & 0x0F) << 10)) + 1
            else:
                if size < 10:
                    raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/VP8 "))
                frame_w = raw[position + 14] | ((raw[position + 15] & 0x3F) << 8)
                frame_h = raw[position + 16] | ((raw[position + 17] & 0x3F) << 8)
            if width and (frame_w != width or frame_h != height):
                raise Malformed(
                    rejected(
                        "ASSET_CONTENT_INVALID",
                        "ASSET_STRUCTURE",
                        "/" + four_cc.decode("latin1"),
                    )
                )
            width, height = frame_w, frame_h
        elif four_cc not in (b"ALPH", b"XMP "):
            raise Malformed(
                rejected(
                    "ASSET_CONTENT_INVALID",
                    "ASSET_STRUCTURE",
                    "/" + four_cc.decode("latin1"),
                )
            )
        position = padded
    if not saw_image:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    if iccp:
        raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_DESCRIPTOR", "/ICCP"))
    try:
        image = Image.open(_BytesIO(raw))
        image.load()
    except Exception:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_DECODE", "/")) from None
    if getattr(image, "is_animated", False):
        raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/ANIM"))
    if image.width != width or image.height != height:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_DECODE", "/"))
    return admitted_image(
        raw, width, height, ORIENTATIONS[orientation - 1] if orientation else "IDENTITY"
    )


def font_admit(raw: bytes) -> dict[str, Any]:
    if len(raw) < 12:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    magic = u32(raw, 0)
    glyf_flavor = magic == 0x00010000
    if magic != 0x00010000 and magic != 0x4F54544F:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    count = u16(raw, 4)
    if count == 0 or 12 + count * 16 > len(raw):
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/tables"))
    tables: dict[str, tuple[int, int, int]] = {}
    for i in range(count):
        base = 12 + i * 16
        tag = raw[base : base + 4].decode("latin1")
        checksum, offset, length = struct.unpack_from(">III", raw, base + 4)
        if offset + length > len(raw):
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/tables"))
        if tag in tables:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/tables"))
        tables[tag] = (checksum, offset, length)
    for banned in BANNED_TABLES:
        if banned in tables:
            raise Malformed(rejected("ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/tables"))
    for required in (GLYF_REQUIRED if glyf_flavor else CFF_REQUIRED):
        if required not in tables:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/tables"))
    for tag, (checksum, offset, length) in tables.items():
        actual = table_checksum(raw, offset, length)
        if tag == "head":
            actual = (actual - u32(raw, offset + 8)) & 0xFFFFFFFF
        if actual != checksum:
            raise Malformed(
                rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/" + tag.strip())
            )
    if table_checksum(raw, 0, len(raw)) != CHECKSUM_MAGIC:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/head"))
    _, head_offset, head_length = tables["head"]
    if head_length < 54 or u32(raw, head_offset + 12) != HEAD_MAGIC:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/head"))
    units_per_em = u16(raw, head_offset + 18)
    if not 16 <= units_per_em <= 16384:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/head"))
    _, maxp_offset, maxp_length = tables["maxp"]
    version = u32(raw, maxp_offset)
    if version not in (0x00005000, 0x00010000):
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/maxp"))
    num_glyphs = u16(raw, maxp_offset + 4)
    if num_glyphs == 0:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/maxp"))
    if glyf_flavor:
        if version != 0x00010000:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/maxp"))
        _, loca_offset, loca_length = tables["loca"]
        _, glyf_offset, glyf_length = tables["glyf"]
        if loca_length != (num_glyphs + 1) * 2:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/loca"))
        if u16(raw, loca_offset + num_glyphs * 2) * 2 != glyf_length:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/loca"))
    else:
        if version != 0x00005000:
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/maxp"))
        _, cff_offset, cff_length = tables["CFF "]
        if not _cff_charstrings_count_matches(raw, cff_offset, cff_length, num_glyphs):
            raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/CFF "))
    try:
        font = TTFont(_BytesIO(raw), lazy=False)
    except Exception:
        raise Malformed(rejected("ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/")) from None
    return admitted_font(raw, "TRUETYPE_GLYF" if glyf_flavor else "CFF", units_per_em)


def _parse_index(raw: bytes, position: int, end: int) -> tuple[int, list[int], int, int, int] | None:
    if position + 2 > end:
        return None
    count = u16(raw, position)
    position += 2
    if count == 0:
        return count, [0], position, position, position
    if position >= end:
        return None
    off_size = raw[position]
    position += 1
    if off_size < 1 or off_size > 4 or position + (count + 1) * off_size > end:
        return None
    offsets = []
    for i in range(count + 1):
        offsets.append(
            int.from_bytes(
                raw[position + i * off_size : position + (i + 1) * off_size],
                "big",
            )
            - 1
        )
    data_offset = position + (count + 1) * off_size
    data_end = data_offset + offsets[-1] - offsets[0]
    if offsets[0] != 0 or data_end > end:
        return None
    return count, offsets, data_end, data_offset, data_end


def _cff_charstrings_count_matches(raw: bytes, offset: int, length: int, expected: int) -> bool:
    end = offset + length
    if offset + 4 > end or raw[offset] != 1:
        return False
    name = _parse_index(raw, offset + raw[offset + 2], end)
    if name is None or name[0] != 1:
        return False
    top = _parse_index(raw, name[2], end)
    if top is None or top[0] != 1:
        return False
    string = _parse_index(raw, top[2], end)
    if string is None:
        return False
    gsubr = _parse_index(raw, string[2], end)
    if gsubr is None:
        return False
    char_strings_offset = -1
    stack: list[int] = []
    position = top[3]
    while position < top[4]:
        b0 = raw[position]
        if b0 <= 21:
            if b0 == 17 and stack:
                char_strings_offset = stack[-1]
            stack = []
            position += 1
        elif b0 == 12:
            position += 2
            stack = []
        elif b0 >= 32 and b0 <= 246:
            stack.append(b0 - 139)
            position += 1
        elif b0 >= 247 and b0 <= 250:
            if position + 1 >= top[4]:
                return False
            stack.append((b0 - 247) * 256 + raw[position + 1] + 108)
            position += 2
        elif b0 >= 251 and b0 <= 254:
            if position + 1 >= top[4]:
                return False
            stack.append(-(b0 - 251) * 256 - raw[position + 1] - 108)
            position += 2
        elif b0 == 28:
            if position + 2 >= top[4]:
                return False
            stack.append(int.from_bytes(raw[position + 1 : position + 3], "big", signed=True))
            position += 3
        elif b0 == 29:
            if position + 4 >= top[4]:
                return False
            stack.append(int.from_bytes(raw[position + 1 : position + 5], "big", signed=True))
            position += 5
        elif b0 == 30:
            position += 1
            while position < top[4] and raw[position] & 0x0F != 0x0F:
                position += 1
            position += 1
            stack.append(0)
        elif b0 == 255:
            if position + 4 >= top[4]:
                return False
            stack.append(int.from_bytes(raw[position + 1 : position + 5], "big", signed=True))
            position += 5
        else:
            return False
    if char_strings_offset < 0 or offset + char_strings_offset + 2 > end:
        return False
    char_strings = _parse_index(raw, offset + char_strings_offset, end)
    return char_strings is not None and char_strings[0] == expected


class _BytesIO:
    def __init__(self, data: bytes) -> None:
        import io

        self._stream = io.BytesIO(data)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._stream, name)


def replay_case(vector: dict[str, Any]) -> dict[str, Any]:
    case_id = vector["id"]
    raw = base64.b64decode(vector["input"]["data"])
    kind = vector["assetKind"]
    try:
        if kind == "IMAGE":
            if raw[:2] == b"\xff\xd8":
                actual = jpeg_admit(raw)
            elif raw[:4] == b"RIFF":
                actual = webp_admit(raw)
            else:
                actual = png_admit(raw)
        else:
            actual = font_admit(raw)
    except Malformed as failure:
        actual = failure.result
    expected = vector["expected"]
    if actual != expected:
        raise AssertionError(f"{case_id}: expected {expected!r}, got {actual!r}")
    return {"id": case_id, **actual}


def load_json(path: Path) -> tuple[bytes, dict[str, Any]]:
    raw = path.read_bytes()
    return raw, json.loads(raw.decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", required=True, type=Path)
    parser.add_argument("--primary-report", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    vector_bytes, manifest = load_json(args.vectors)
    _, primary = load_json(args.primary_report)
    if manifest["vectorVersion"] != VECTOR_VERSION:
        raise AssertionError("Unexpected vector version")
    if manifest["authorityContext"]["profileAvailability"] != "NOT_REGISTERED":
        raise AssertionError("Partial acceptance Profile must remain unavailable")
    if len(manifest["cases"]) != 38:
        raise AssertionError("Vector case count drift")

    results = [replay_case(vector) for vector in manifest["cases"]]
    vector_sha256 = hashlib.sha256(vector_bytes).hexdigest()
    if primary["reportVersion"] != "renderweave-asset-kernel-primary/1":
        raise AssertionError("Unexpected Java primary report version")
    if primary["engine"] != "java-primary":
        raise AssertionError("Unexpected primary engine")
    if primary["vectorSha256"] != vector_sha256:
        raise AssertionError("Primary vector bytes drift")
    if primary["acceptanceProfileAvailability"] != "NOT_REGISTERED":
        raise AssertionError("Primary report registered a partial Profile")
    if primary["cases"] != len(results) or primary["passed"] != len(results) or primary["failed"] != 0:
        raise AssertionError("Primary report counts differ")
    if primary["results"] != results:
        raise AssertionError("Java primary and independent Python results differ")

    report = {
        "reportVersion": "renderweave-asset-kernel-independent/1",
        "engine": "python-independent",
        "assurance": "A2",
        "vectorVersion": manifest["vectorVersion"],
        "vectorSha256": vector_sha256,
        "primaryReportSha256": hashlib.sha256(args.primary_report.read_bytes()).hexdigest(),
        "acceptanceProfileAvailability": "NOT_REGISTERED",
        "cases": len(results),
        "passed": len(results),
        "failed": 0,
        "results": results,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"Asset kernel independent replay: {len(results)} cases, 0 failures, "
        f"vector sha256:{vector_sha256}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
