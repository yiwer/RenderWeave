#!/usr/bin/env python3
"""Independent stdlib replay for Renderer media preflight and raw caching."""

from __future__ import annotations

import argparse
import base64
import binascii
import copy
import hashlib
import json
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


PROFILE = "renderweave-resource-media-raw-cache-v1"
RENDERER_PROFILE = "renderweave-renderer/1.0"
RAW_CACHE_BYTES = 268_435_456
RAW_CACHE_LIMIT_ID = "assetsAndFetch.requestRawCacheBytes"
MAX_FONT_TABLES = 256
MAX_IMAGE_EDGE = 20_000
MAX_IMAGE_PIXELS = 100_000_000
SFNT_CHECKSUM_MAGIC = 0xB1B0AFBA
HEAD_MAGIC = 0x5F0F3CF5

MEDIA_MAGIC = {
    "image/png": b"\x89PNG\r\n\x1a\n",
    "image/jpeg": b"\xff\xd8",
    "font/ttf": b"\x00\x01\x00\x00",
    "font/otf": b"OTTO",
}
ORIENTATIONS = {
    1: "IDENTITY",
    2: "MIRROR_HORIZONTAL",
    3: "ROTATE_180",
    4: "MIRROR_VERTICAL",
    5: "TRANSPOSE",
    6: "ROTATE_90_CW",
    7: "TRANSVERSE",
    8: "ROTATE_270_CW",
}
BANNED_FONT_TABLES = {
    b"COLR",
    b"CPAL",
    b"CBDT",
    b"CBLC",
    b"sbix",
    b"SVG ",
    b"EBDT",
    b"EBLC",
    b"EBSC",
    b"bdat",
    b"bloc",
    b"fvar",
    b"gvar",
    b"CFF2",
    b"Silf",
    b"Glat",
    b"Gloc",
    b"morx",
    b"mort",
    b"feat",
}
COMMON_FONT_TABLES = {
    b"cmap",
    b"head",
    b"hhea",
    b"hmtx",
    b"maxp",
    b"name",
    b"OS/2",
    b"post",
}
EXPECTED_BOUNDARY = {
    "resourceBytes": "MEDIA_DESCRIPTOR_PREFLIGHT_AUTOMATED_VERIFIED",
    "imageDecode": "DEFERRED",
    "fontFullParse": "DEFERRED",
    "decodedCache": "ABSENT",
    "daemonOutputPath": "UNWIRED",
    "profileAvailability": "NOT_REGISTERED",
    "certificationStatus": "NOT_CERTIFIED",
    "processRasterImplementation": "ABSENT",
    "productRoute": "CLOSED",
    "providerAttempts": 0,
}


class VerificationFailure(ValueError):
    pass


class DecodeFailure(ValueError):
    pass


@dataclass
class Verifier:
    checks: int = 0

    def require(self, condition: bool, message: str) -> None:
        self.checks += 1
        if not condition:
            raise VerificationFailure(message)


def strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationFailure(f"duplicate JSON member: {key}")
        result[key] = value
    return result


def load_strict(path: Path) -> tuple[bytes, Any]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw, object_pairs_hook=strict_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"{path} is not strict UTF-8 JSON: {error}") from error
    return raw, value


def reject_null(value: Any, location: str = "$") -> None:
    if value is None:
        raise VerificationFailure(f"null is forbidden at {location}")
    if isinstance(value, dict):
        for key, nested in value.items():
            reject_null(nested, f"{location}.{key}")
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            reject_null(nested, f"{location}[{index}]")


def exact_members(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise VerificationFailure(f"{label} member set drifted")
    return value


def exact_cases(value: Any, count: int, label: str) -> list[dict[str, Any]]:
    if not isinstance(value, list) or len(value) != count:
        raise VerificationFailure(f"{label} case count drifted")
    if not all(isinstance(case, dict) for case in value):
        raise VerificationFailure(f"{label} cases must be objects")
    return value


def u16be(data: bytes, offset: int) -> int:
    if offset < 0 or offset + 2 > len(data):
        raise DecodeFailure("truncated u16")
    return struct.unpack_from(">H", data, offset)[0]


def u32be(data: bytes, offset: int) -> int:
    if offset < 0 or offset + 4 > len(data):
        raise DecodeFailure("truncated u32")
    return struct.unpack_from(">I", data, offset)[0]


def u16le(data: bytes, offset: int) -> int:
    if offset < 0 or offset + 2 > len(data):
        raise DecodeFailure("truncated u16")
    return struct.unpack_from("<H", data, offset)[0]


def u32le(data: bytes, offset: int) -> int:
    if offset < 0 or offset + 4 > len(data):
        raise DecodeFailure("truncated u32")
    return struct.unpack_from("<I", data, offset)[0]


def detect_media(data: bytes) -> str | None:
    for media_type, magic in MEDIA_MAGIC.items():
        if data.startswith(magic):
            return media_type
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def image_descriptor(width: int, height: int, orientation: str) -> dict[str, Any]:
    if (
        width <= 0
        or height <= 0
        or width > MAX_IMAGE_EDGE
        or height > MAX_IMAGE_EDGE
        or width * height > MAX_IMAGE_PIXELS
    ):
        raise DecodeFailure("image dimensions are outside the frozen subset")
    swaps = orientation in {"TRANSPOSE", "ROTATE_90_CW", "TRANSVERSE", "ROTATE_270_CW"}
    logical_width, logical_height = (height, width) if swaps else (width, height)
    return {
        "type": "IMAGE",
        "encodedWidthPx": width,
        "encodedHeightPx": height,
        "orientation": orientation,
        "logicalWidthPx": logical_width,
        "logicalHeightPx": logical_height,
        "frameCount": 1,
        "colorEncoding": "SRGB_8BIT",
    }


def exif_orientation(data: bytes) -> str:
    if data.startswith(b"Exif\x00\x00"):
        data = data[6:]
    if len(data) < 8 or data[:2] not in {b"II", b"MM"}:
        raise DecodeFailure("invalid TIFF header")
    order = "<" if data[:2] == b"II" else ">"
    if struct.unpack_from(order + "H", data, 2)[0] != 42:
        raise DecodeFailure("invalid TIFF marker")
    ifd_offset = struct.unpack_from(order + "I", data, 4)[0]
    if ifd_offset + 2 > len(data):
        raise DecodeFailure("invalid IFD offset")
    count = struct.unpack_from(order + "H", data, ifd_offset)[0]
    position = ifd_offset + 2
    orientation: str | None = None
    for _ in range(count):
        if position + 12 > len(data):
            raise DecodeFailure("truncated IFD entry")
        tag, kind = struct.unpack_from(order + "HH", data, position)
        values = struct.unpack_from(order + "I", data, position + 4)[0]
        if tag == 0x0112:
            if orientation is not None or kind != 3 or values != 1:
                raise DecodeFailure("invalid orientation entry")
            raw_value = struct.unpack_from(order + "H", data, position + 8)[0]
            if raw_value not in ORIENTATIONS:
                raise DecodeFailure("orientation is outside 1..8")
            orientation = ORIENTATIONS[raw_value]
        position += 12
    return orientation or "IDENTITY"


def parse_png(data: bytes) -> dict[str, Any]:
    if not data.startswith(MEDIA_MAGIC["image/png"]):
        raise DecodeFailure("PNG magic mismatch")
    position = 8
    width = height = bit_depth = color_type = 0
    saw_ihdr = saw_plte = saw_trns = saw_idat = saw_iend = False
    ended_idat = saw_srgb = saw_exif = False
    palette_entries = 0
    orientation = "IDENTITY"
    while position < len(data):
        if position + 12 > len(data):
            raise DecodeFailure("truncated PNG chunk")
        length = u32be(data, position)
        payload_start = position + 8
        payload_end = payload_start + length
        chunk_end = payload_end + 4
        if chunk_end > len(data):
            raise DecodeFailure("truncated PNG payload")
        chunk_type = data[position + 4 : position + 8]
        if binascii.crc32(data[position + 4 : payload_end]) & 0xFFFFFFFF != u32be(data, payload_end):
            raise DecodeFailure("PNG CRC mismatch")
        payload = data[payload_start:payload_end]
        if not saw_ihdr and chunk_type != b"IHDR":
            raise DecodeFailure("IHDR must be first")
        if chunk_type == b"IHDR":
            if saw_ihdr or length != 13:
                raise DecodeFailure("invalid IHDR")
            saw_ihdr = True
            width, height = u32be(payload, 0), u32be(payload, 4)
            bit_depth, color_type = payload[8], payload[9]
            valid_depth = (
                (color_type in {0, 3} and bit_depth in {1, 2, 4, 8})
                or (color_type in {2, 4, 6} and bit_depth == 8)
            )
            if not valid_depth or payload[10] != 0 or payload[11] != 0 or payload[12] not in {0, 1}:
                raise DecodeFailure("unsupported IHDR")
            image_descriptor(width, height, "IDENTITY")
        elif chunk_type == b"PLTE":
            if (
                not saw_ihdr
                or saw_plte
                or saw_idat
                or color_type in {0, 4}
                or length == 0
                or length % 3
                or length > 768
            ):
                raise DecodeFailure("invalid PLTE")
            palette_entries = length // 3
            if color_type == 3 and palette_entries > 1 << bit_depth:
                raise DecodeFailure("palette exceeds bit depth")
            saw_plte = True
        elif chunk_type == b"tRNS":
            valid = (
                (color_type == 0 and length == 2)
                or (color_type == 2 and length == 6)
                or (color_type == 3 and saw_plte and 0 < length <= palette_entries)
            )
            if saw_trns or saw_idat or not valid:
                raise DecodeFailure("invalid tRNS")
            saw_trns = True
        elif chunk_type == b"sRGB":
            if saw_srgb or length != 1 or payload[0] > 3:
                raise DecodeFailure("invalid sRGB")
            saw_srgb = True
        elif chunk_type == b"iCCP":
            raise DecodeFailure("ICC handling is deferred")
        elif chunk_type == b"eXIf":
            if saw_exif:
                raise DecodeFailure("duplicate EXIF")
            saw_exif = True
            orientation = exif_orientation(payload)
        elif chunk_type in {b"acTL", b"fcTL", b"fdAT"}:
            raise DecodeFailure("APNG is excluded")
        elif chunk_type == b"IDAT":
            if ended_idat or length == 0:
                raise DecodeFailure("invalid IDAT sequence")
            saw_idat = True
        elif chunk_type == b"IEND":
            if length != 0 or not saw_idat or saw_iend:
                raise DecodeFailure("invalid IEND")
            saw_iend = True
            position = chunk_end
            break
        elif chunk_type[0] & 0x20 == 0:
            raise DecodeFailure("unknown critical PNG chunk")
        if saw_idat and chunk_type != b"IDAT":
            ended_idat = True
        position = chunk_end
    if not (saw_ihdr and saw_idat and saw_iend) or position != len(data):
        raise DecodeFailure("incomplete PNG")
    if color_type == 3 and not saw_plte:
        raise DecodeFailure("indexed PNG has no palette")
    return image_descriptor(width, height, orientation)


def parse_jpeg(data: bytes) -> dict[str, Any]:
    if len(data) < 4 or not data.startswith(MEDIA_MAGIC["image/jpeg"]):
        raise DecodeFailure("JPEG magic mismatch")
    position = 2
    width = height = 0
    component_ids: list[int] = []
    saw_sof = saw_sos = saw_eoi = saw_dqt = saw_dht = False
    saw_exif = saw_adobe = False
    orientation = "IDENTITY"
    while position < len(data):
        if data[position] != 0xFF:
            raise DecodeFailure("JPEG marker expected")
        marker_start = position
        while position < len(data) and data[position] == 0xFF:
            position += 1
        if position >= len(data):
            raise DecodeFailure("truncated JPEG marker")
        marker = data[position]
        position += 1
        if marker == 0xD9:
            saw_eoi = True
            break
        if marker == 0x00 or marker == 0xD8 or 0xD0 <= marker <= 0xD7:
            raise DecodeFailure("invalid standalone JPEG marker")
        segment_length = u16be(data, position)
        if segment_length < 2 or position + segment_length > len(data):
            raise DecodeFailure("invalid JPEG segment length")
        segment_end = position + segment_length
        payload = data[position + 2 : segment_end]
        if marker == 0xDA:
            if not saw_sof or not payload:
                raise DecodeFailure("SOS before SOF")
            saw_sos = True
            position = segment_end
            while True:
                if position >= len(data):
                    raise DecodeFailure("entropy data has no terminal marker")
                if data[position] != 0xFF:
                    position += 1
                    continue
                next_marker_start = position
                position += 1
                while position < len(data) and data[position] == 0xFF:
                    position += 1
                if position >= len(data):
                    raise DecodeFailure("truncated entropy marker")
                if data[position] == 0x00 or 0xD0 <= data[position] <= 0xD7:
                    position += 1
                    continue
                position = next_marker_start
                break
            continue
        if marker in {0xC0, 0xC2}:
            if saw_sof or len(payload) < 6 or payload[0] != 8:
                raise DecodeFailure("unsupported SOF")
            saw_sof = True
            height, width = u16be(payload, 1), u16be(payload, 3)
            components = payload[5]
            if components not in {1, 3} or len(payload) != 6 + components * 3:
                raise DecodeFailure("unsupported JPEG components")
            component_ids = [payload[6 + index * 3] for index in range(components)]
            if components == 3 and component_ids != [1, 2, 3]:
                raise DecodeFailure("JPEG is not the frozen YCbCr subset")
            image_descriptor(width, height, "IDENTITY")
        elif marker in {
            0xC1,
            0xC3,
            0xC5,
            0xC6,
            0xC7,
            0xC9,
            0xCA,
            0xCB,
            0xCD,
            0xCE,
            0xCF,
            0xCC,
            0xDC,
            0xDE,
            0xDF,
        }:
            raise DecodeFailure("JPEG coding mode is excluded")
        elif marker == 0xDB:
            saw_dqt = True
        elif marker == 0xC4:
            saw_dht = True
        elif marker == 0xE1 and payload.startswith(b"Exif\x00\x00"):
            if saw_exif:
                raise DecodeFailure("duplicate JPEG EXIF")
            saw_exif = True
            orientation = exif_orientation(payload[6:])
        elif marker == 0xE2 and payload.startswith(b"ICC_PROFILE\x00"):
            raise DecodeFailure("ICC handling is deferred")
        elif marker == 0xEE and payload.startswith(b"Adobe"):
            if saw_adobe or len(payload) < 12 or payload[11] in {0, 2}:
                raise DecodeFailure("Adobe JPEG transform is excluded")
            saw_adobe = True
        position = segment_end
        if position <= marker_start:
            raise DecodeFailure("JPEG parser made no progress")
    if not (saw_sof and saw_sos and saw_eoi and saw_dqt and saw_dht):
        raise DecodeFailure("incomplete JPEG")
    if position != len(data) or not component_ids:
        raise DecodeFailure("trailing JPEG bytes or missing components")
    return image_descriptor(width, height, orientation)


def parse_webp(data: bytes) -> dict[str, Any]:
    if len(data) < 20 or detect_media(data) != "image/webp":
        raise DecodeFailure("WebP magic mismatch")
    if u32le(data, 4) + 8 != len(data):
        raise DecodeFailure("WebP RIFF length mismatch")
    position = 12
    canvas: tuple[int, int] | None = None
    image: tuple[int, int] | None = None
    saw_vp8x = saw_image = saw_alpha = saw_exif = False
    orientation = "IDENTITY"
    while position < len(data):
        if position + 8 > len(data):
            raise DecodeFailure("truncated WebP chunk")
        fourcc = data[position : position + 4]
        size = u32le(data, position + 4)
        payload_end = position + 8 + size
        chunk_end = payload_end + (size & 1)
        if chunk_end > len(data):
            raise DecodeFailure("truncated WebP payload")
        payload = data[position + 8 : payload_end]
        if fourcc == b"VP8X":
            if saw_vp8x or saw_image or size != 10:
                raise DecodeFailure("invalid VP8X")
            saw_vp8x = True
            flags = payload[0]
            if flags & 0xC3 or flags & 0x20:
                raise DecodeFailure("reserved or animated VP8X flags")
            width = int.from_bytes(payload[4:7], "little") + 1
            height = int.from_bytes(payload[7:10], "little") + 1
            canvas = (width, height)
        elif fourcc in {b"ICCP", b"ANIM", b"ANMF"}:
            raise DecodeFailure("WebP feature is deferred or excluded")
        elif fourcc == b"EXIF":
            if saw_exif or saw_image:
                raise DecodeFailure("invalid WebP EXIF ordering")
            saw_exif = True
            orientation = exif_orientation(payload)
        elif fourcc == b"ALPH":
            if saw_alpha or saw_image or not payload:
                raise DecodeFailure("invalid WebP alpha chunk")
            saw_alpha = True
        elif fourcc == b"VP8 ":
            if saw_image or len(payload) < 10 or payload[3:6] != b"\x9d\x01\x2a":
                raise DecodeFailure("invalid VP8 frame header")
            saw_image = True
            image = (u16le(payload, 6) & 0x3FFF, u16le(payload, 8) & 0x3FFF)
        elif fourcc == b"VP8L":
            if saw_image or len(payload) < 5 or payload[0] != 0x2F:
                raise DecodeFailure("invalid VP8L frame header")
            saw_image = True
            b1, b2, b3, b4 = payload[1:5]
            image = ((b1 | ((b2 & 0x3F) << 8)) + 1, ((b2 >> 6) | (b3 << 2) | ((b4 & 0x0F) << 10)) + 1)
        elif fourcc == b"XMP ":
            if saw_image:
                raise DecodeFailure("invalid WebP XMP ordering")
        else:
            raise DecodeFailure("unknown WebP chunk")
        position = chunk_end
    if not saw_image or image is None or position != len(data) or (canvas is not None and canvas != image):
        raise DecodeFailure("incomplete or inconsistent WebP")
    return image_descriptor(image[0], image[1], orientation)


def table_checksum(data: bytes, tag: bytes, offset: int, length: int) -> int:
    total = 0
    for relative in range(0, length, 4):
        chunk = bytearray(data[offset + relative : offset + min(relative + 4, length)])
        chunk.extend(b"\x00" * (4 - len(chunk)))
        if tag == b"head" and relative == 8:
            chunk = bytearray(4)
        total = (total + int.from_bytes(chunk, "big")) & 0xFFFFFFFF
    return total


def full_font_checksum(data: bytes) -> int:
    total = 0
    for offset in range(0, len(data), 4):
        chunk = data[offset : offset + 4].ljust(4, b"\x00")
        total = (total + int.from_bytes(chunk, "big")) & 0xFFFFFFFF
    return total


def parse_font(data: bytes, media_type: str) -> dict[str, Any]:
    if len(data) < 12:
        raise DecodeFailure("truncated SFNT header")
    actual_flavor = "TRUETYPE_GLYF" if data[:4] == b"\x00\x01\x00\x00" else "CFF" if data[:4] == b"OTTO" else None
    expected_flavor = "TRUETYPE_GLYF" if media_type == "font/ttf" else "CFF"
    if actual_flavor != expected_flavor:
        raise DecodeFailure("SFNT flavor mismatch")
    table_count = u16be(data, 4)
    directory_end = 12 + table_count * 16
    if table_count == 0 or table_count > MAX_FONT_TABLES or directory_end > len(data):
        raise DecodeFailure("invalid SFNT directory")
    tables: dict[bytes, tuple[int, int, int]] = {}
    occupied: list[tuple[int, int]] = []
    for index in range(table_count):
        base = 12 + index * 16
        tag = data[base : base + 4]
        checksum = u32be(data, base + 4)
        offset = u32be(data, base + 8)
        length = u32be(data, base + 12)
        end = offset + length
        if length == 0 or offset < directory_end or offset % 4 or end > len(data) or tag in tables:
            raise DecodeFailure("invalid SFNT table record")
        tables[tag] = (checksum, offset, length)
        occupied.append((offset, end))
    occupied.sort()
    if any(left[1] > right[0] for left, right in zip(occupied, occupied[1:])):
        raise DecodeFailure("overlapping SFNT tables")
    if BANNED_FONT_TABLES & tables.keys() or not COMMON_FONT_TABLES <= tables.keys():
        raise DecodeFailure("required or banned SFNT tables drifted")
    if actual_flavor == "TRUETYPE_GLYF" and not {b"glyf", b"loca"} <= tables.keys():
        raise DecodeFailure("TrueType outline tables missing")
    if actual_flavor == "CFF" and b"CFF " not in tables:
        raise DecodeFailure("CFF table missing")
    for tag, (checksum, offset, length) in tables.items():
        if table_checksum(data, tag, offset, length) != checksum:
            raise DecodeFailure("SFNT table checksum mismatch")
    if full_font_checksum(data) != SFNT_CHECKSUM_MAGIC:
        raise DecodeFailure("SFNT checksum adjustment mismatch")
    _, head_offset, head_length = tables[b"head"]
    if head_length < 54 or u32be(data, head_offset + 12) != HEAD_MAGIC:
        raise DecodeFailure("invalid head table")
    units_per_em = u16be(data, head_offset + 18)
    if not 16 <= units_per_em <= 16_384:
        raise DecodeFailure("unitsPerEm outside frozen bounds")
    index_to_loc_format = u16be(data, head_offset + 50)
    _, maxp_offset, maxp_length = tables[b"maxp"]
    if maxp_length < 6:
        raise DecodeFailure("invalid maxp table")
    maxp_version = u32be(data, maxp_offset)
    glyph_count = u16be(data, maxp_offset + 4)
    if glyph_count == 0:
        raise DecodeFailure("font has no glyphs")
    if actual_flavor == "TRUETYPE_GLYF":
        if maxp_version != 0x00010000 or index_to_loc_format not in {0, 1}:
            raise DecodeFailure("invalid TrueType metrics")
        _, loca_offset, loca_length = tables[b"loca"]
        _, _, glyf_length = tables[b"glyf"]
        entry_size = 2 if index_to_loc_format == 0 else 4
        if loca_length != (glyph_count + 1) * entry_size:
            raise DecodeFailure("loca length mismatch")
        previous = 0
        for index in range(glyph_count + 1):
            offset = loca_offset + index * entry_size
            current = u16be(data, offset) * 2 if entry_size == 2 else u32be(data, offset)
            if current < previous or current > glyf_length:
                raise DecodeFailure("invalid loca offset")
            previous = current
    else:
        if maxp_version != 0x00005000:
            raise DecodeFailure("invalid CFF maxp version")
        _, cff_offset, cff_length = tables[b"CFF "]
        if cff_length < 4 or data[cff_offset] != 1 or data[cff_offset + 2] < 4:
            raise DecodeFailure("invalid CFF header")
    return {
        "type": "FONT",
        "faceIndex": 0,
        "flavor": actual_flavor,
        "unitsPerEm": units_per_em,
    }


def parse_media(data: bytes, media_type: str) -> dict[str, Any]:
    if media_type == "image/png":
        return parse_png(data)
    if media_type == "image/jpeg":
        return parse_jpeg(data)
    if media_type == "image/webp":
        return parse_webp(data)
    if media_type in {"font/ttf", "font/otf"}:
        return parse_font(data, media_type)
    raise DecodeFailure("unknown declared media type")


def asset_bytes(case: dict[str, Any]) -> bytes:
    exact_members(case, {"id", "assetKind", "input", "expected"}, f"Asset case {case.get('id')}")
    exact_members(case["input"], {"kind", "data"}, f"Asset input {case['id']}")
    if case["input"]["kind"] != "BASE64" or not isinstance(case["input"]["data"], str):
        raise VerificationFailure(f"{case['id']}: Asset input is not base64")
    try:
        return base64.b64decode(case["input"]["data"], validate=True)
    except (ValueError, binascii.Error) as error:
        raise VerificationFailure(f"{case['id']}: invalid base64") from error


def admitted_descriptor(case: dict[str, Any], data: bytes) -> dict[str, Any]:
    expected = case["expected"]
    if not isinstance(expected, dict) or expected.get("outcome") != "ADMITTED":
        raise VerificationFailure(f"{case['id']}: referenced Asset case is not ADMITTED")
    if expected.get("byteLength") != len(data) or expected.get("sha256") != hashlib.sha256(data).hexdigest():
        raise VerificationFailure(f"{case['id']}: Asset integrity facts drifted")
    if expected.get("acceptanceProfileId") != "renderweave-asset-acceptance/1.0":
        raise VerificationFailure(f"{case['id']}: Asset acceptance profile drifted")
    descriptor = expected.get("descriptor")
    if not isinstance(descriptor, dict):
        raise VerificationFailure(f"{case['id']}: Asset descriptor is absent")
    return descriptor


def mutate_descriptor(descriptor: dict[str, Any], mutation: str) -> dict[str, Any]:
    result = copy.deepcopy(descriptor)
    if mutation == "IMAGE_WIDTH_PLUS_ONE":
        result["encodedWidthPx"] += 1
        result["logicalWidthPx"] += 1
    elif mutation == "IMAGE_HEIGHT_PLUS_ONE":
        result["encodedHeightPx"] += 1
        result["logicalHeightPx"] += 1
    elif mutation == "IMAGE_ORIENTATION_IDENTITY":
        result["orientation"] = "IDENTITY"
        result["logicalWidthPx"] = result["encodedWidthPx"]
        result["logicalHeightPx"] = result["encodedHeightPx"]
    elif mutation == "IMAGE_ORIENTATION_ROTATE_90":
        result["orientation"] = "ROTATE_90_CW"
        result["logicalWidthPx"] = result["encodedHeightPx"]
        result["logicalHeightPx"] = result["encodedWidthPx"]
    elif mutation == "FONT_UNITS_PER_EM_PLUS_ONE":
        result["unitsPerEm"] += 1
    else:
        raise VerificationFailure(f"unknown descriptor mutation: {mutation}")
    return result


def replay_cache(case: dict[str, Any]) -> tuple[str, int, int]:
    if "reserveBytes" in case:
        reserve = case["reserveBytes"]
        if not isinstance(reserve, int) or isinstance(reserve, bool) or reserve < 0:
            raise VerificationFailure(f"{case['id']}: invalid reserveBytes")
        if reserve <= RAW_CACHE_BYTES:
            return "RESERVED", 0, reserve
        return "RESOURCE_BUDGET_EXCEEDED", 0, 0
    entries = 0
    retained = 0
    lease_active = True
    outcome = "NO_TERMINAL_EVENT"
    for event in case["events"]:
        if event == "MISS":
            outcome = "MISS"
        elif event == "INSERT":
            if entries == 0:
                entries = 1
                retained += 70
            outcome = "INSERTED"
        elif event == "LEASE_EXPIRED":
            lease_active = False
        elif event == "CORRUPT":
            outcome = "CORRUPTED"
        elif event == "HIT":
            if not lease_active:
                outcome = "RESOURCE_LEASE_EXPIRED"
            elif outcome == "CORRUPTED":
                entries = 0
                outcome = "RENDER_INTERNAL_ERROR"
            elif entries == 1:
                outcome = "HIT"
            else:
                outcome = "MISS"
        else:
            raise VerificationFailure(f"{case['id']}: unknown cache event {event}")
    return outcome, entries, retained


def verify(vectors_path: Path, repo_root: Path) -> dict[str, Any]:
    raw, vectors = load_strict(vectors_path)
    reject_null(vectors)
    exact_members(
        vectors,
        {
            "profile",
            "rendererProfileIdentity",
            "assetKernelVectorPath",
            "assetKernelVectorSha256",
            "limits",
            "supportedAssetCases",
            "defensiveAssetCases",
            "descriptorCases",
            "deferredAssetCases",
            "cacheCases",
            "boundary",
        },
        "media vector document",
    )
    verifier = Verifier()
    verifier.require(vectors["profile"] == PROFILE, "media profile drifted")
    verifier.require(vectors["rendererProfileIdentity"] == RENDERER_PROFILE, "Renderer profile identity drifted")
    exact_members(vectors["limits"], {"requestRawCacheBytes", "requestRawCacheLimitId", "fontTablesPerContent"}, "limits")
    verifier.require(vectors["limits"]["requestRawCacheBytes"] == RAW_CACHE_BYTES, "raw cache limit drifted")
    verifier.require(vectors["limits"]["requestRawCacheLimitId"] == RAW_CACHE_LIMIT_ID, "raw cache limit id drifted")
    verifier.require(vectors["limits"]["fontTablesPerContent"] == MAX_FONT_TABLES, "font table limit drifted")
    exact_members(vectors["boundary"], set(EXPECTED_BOUNDARY), "boundary")
    for key, expected in EXPECTED_BOUNDARY.items():
        verifier.require(vectors["boundary"][key] == expected, f"{key} boundary drifted")

    relative_asset_path = vectors["assetKernelVectorPath"]
    verifier.require(
        isinstance(relative_asset_path, str)
        and Path(relative_asset_path).as_posix() == relative_asset_path
        and not Path(relative_asset_path).is_absolute()
        and ".." not in Path(relative_asset_path).parts,
        "Asset vector path is not a canonical repository-relative path",
    )
    asset_path = (repo_root / relative_asset_path).resolve()
    verifier.require(asset_path.is_relative_to(repo_root.resolve()), "Asset vector path escapes the repository")
    asset_raw, asset_vectors = load_strict(asset_path)
    asset_sha = "sha256:" + hashlib.sha256(asset_raw).hexdigest()
    verifier.require(vectors["assetKernelVectorSha256"] == asset_sha, "Asset vector hash drifted")
    exact_members(asset_vectors, {"vectorVersion", "authorityContext", "cases"}, "Asset vector document")
    asset_cases = exact_cases(asset_vectors["cases"], 41, "Asset")
    asset_index: dict[str, dict[str, Any]] = {}
    for asset_case in asset_cases:
        case_id = asset_case.get("id")
        verifier.require(isinstance(case_id, str) and case_id not in asset_index, "Asset case id is invalid or duplicated")
        asset_index[case_id] = asset_case

    supported = exact_cases(vectors["supportedAssetCases"], 13, "supported")
    defensive = exact_cases(vectors["defensiveAssetCases"], 22, "defensive")
    descriptors = exact_cases(vectors["descriptorCases"], 5, "descriptor")
    deferred = exact_cases(vectors["deferredAssetCases"], 7, "deferred")
    cache_cases = exact_cases(vectors["cacheCases"], 7, "cache")
    seen_ids: set[str] = set()
    partition_ids: set[str] = set()

    for case in supported:
        exact_members(case, {"id", "assetCaseId", "declaredMediaType"}, "supported case")
        verifier.require(isinstance(case["id"], str) and case["id"] not in seen_ids, "supported id is invalid or duplicated")
        seen_ids.add(case["id"])
        asset_case = asset_index.get(case["assetCaseId"])
        verifier.require(asset_case is not None, f"{case['id']}: missing Asset case")
        data = asset_bytes(asset_case)
        descriptor = admitted_descriptor(asset_case, data)
        verifier.require(detect_media(data) == case["declaredMediaType"], f"{case['id']}: media magic drifted")
        try:
            parsed = parse_media(data, case["declaredMediaType"])
        except DecodeFailure as error:
            raise VerificationFailure(f"{case['id']}: supported header rejected: {error}") from error
        verifier.require(parsed == descriptor, f"{case['id']}: parsed descriptor drifted")
        partition_ids.add(case["assetCaseId"])

    for case in defensive:
        exact_members(case, {"id", "assetCaseId", "declaredMediaType", "descriptorCaseId", "expectedCode"}, "defensive case")
        verifier.require(isinstance(case["id"], str) and case["id"] not in seen_ids, "defensive id is invalid or duplicated")
        seen_ids.add(case["id"])
        asset_case = asset_index.get(case["assetCaseId"])
        descriptor_case = asset_index.get(case["descriptorCaseId"])
        verifier.require(asset_case is not None and descriptor_case is not None, f"{case['id']}: missing Asset reference")
        data = asset_bytes(asset_case)
        descriptor_data = asset_bytes(descriptor_case)
        admitted_descriptor(descriptor_case, descriptor_data)
        actual_media = detect_media(data)
        if actual_media != case["declaredMediaType"]:
            outcome = "MEDIA_MISMATCH"
        else:
            try:
                parse_media(data, case["declaredMediaType"])
                outcome = "VERIFIED"
            except DecodeFailure:
                outcome = "DECODE_FAILED"
        verifier.require(outcome == case["expectedCode"], f"{case['id']}: defensive code drifted")
        partition_ids.add(case["assetCaseId"])

    for case in descriptors:
        exact_members(case, {"id", "assetCaseId", "declaredMediaType", "mutation", "expectedCode"}, "descriptor case")
        verifier.require(isinstance(case["id"], str) and case["id"] not in seen_ids, "descriptor id is invalid or duplicated")
        seen_ids.add(case["id"])
        asset_case = asset_index.get(case["assetCaseId"])
        verifier.require(asset_case is not None, f"{case['id']}: missing Asset case")
        data = asset_bytes(asset_case)
        declared = admitted_descriptor(asset_case, data)
        try:
            parsed = parse_media(data, case["declaredMediaType"])
        except DecodeFailure as error:
            raise VerificationFailure(f"{case['id']}: descriptor source rejected: {error}") from error
        mutated = mutate_descriptor(declared, case["mutation"])
        verifier.require(parsed != mutated and case["expectedCode"] == "RENDER_INTERNAL_ERROR", f"{case['id']}: descriptor drift was not fail-closed")

    expected_deferred_reasons = {
        "png-canonical-icc-admitted": "CANONICAL_ICC_DECOMPRESSION",
        "jpeg-canonical-icc-admitted": "CANONICAL_ICC_SEGMENT_ASSEMBLY",
        "webp-canonical-icc-admitted": "CANONICAL_ICC_EQUALITY",
        "png-corrupt-idat-invalid": "FULL_IMAGE_ENTROPY_DECODE",
        "png-iccp-unsupported": "FULL_IMAGE_CANONICAL_ICC_EQUALITY",
        "jpeg-icc-unsupported": "FULL_IMAGE_CANONICAL_ICC_EQUALITY",
        "font-cff-charstrings-invalid": "FULL_CFF_PARSE",
    }
    for case in deferred:
        exact_members(case, {"assetCaseId", "reason"}, "deferred case")
        asset_case_id = case["assetCaseId"]
        verifier.require(asset_case_id in asset_index and asset_case_id not in partition_ids, "deferred Asset id is absent or duplicated")
        verifier.require(expected_deferred_reasons.get(asset_case_id) == case["reason"], f"{asset_case_id}: deferred reason drifted")
        data = asset_bytes(asset_index[asset_case_id])
        verifier.require(detect_media(data) is not None, f"{asset_case_id}: deferred media magic is absent")
        partition_ids.add(asset_case_id)
    verifier.require(partition_ids == set(asset_index), "Asset corpus partition is not exact")

    for case in cache_cases:
        expected_members = {"id", "outcome", "retainedBytes"}
        if "reserveBytes" in case:
            expected_members.add("reserveBytes")
        else:
            expected_members.update({"events", "uniqueContents"})
        exact_members(case, expected_members, "cache case")
        verifier.require(isinstance(case["id"], str) and case["id"] not in seen_ids, "cache id is invalid or duplicated")
        seen_ids.add(case["id"])
        outcome, unique_contents, retained_bytes = replay_cache(case)
        verifier.require(outcome == case["outcome"], f"{case['id']}: cache outcome drifted")
        if "uniqueContents" in case:
            verifier.require(unique_contents == case["uniqueContents"], f"{case['id']}: cache cardinality drifted")
        verifier.require(retained_bytes == case["retainedBytes"], f"{case['id']}: retained byte count drifted")

    total = len(supported) + len(defensive) + len(descriptors) + len(deferred) + len(cache_cases)
    return {
        "verifier": "renderweave-resource-media-raw-cache-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "supportedCases": len(supported),
        "defensiveCases": len(defensive),
        "descriptorCases": len(descriptors),
        "deferredCases": len(deferred),
        "cacheCases": len(cache_cases),
        "total": total,
        "passed": total,
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(raw).hexdigest(),
        "assetKernelVectorSha256": asset_sha,
        "rendererProfileIdentity": vectors["rendererProfileIdentity"],
        "requestRawCacheBytes": vectors["limits"]["requestRawCacheBytes"],
        "requestRawCacheLimitId": vectors["limits"]["requestRawCacheLimitId"],
        **vectors["boundary"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    repo_root = Path(__file__).resolve().parents[1]
    parser.add_argument(
        "--vectors",
        type=Path,
        default=repo_root / "renderer" / "resource-media-raw-cache-vectors-v1.json",
    )
    parser.add_argument("--repo-root", type=Path, default=repo_root)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.vectors, args.repo_root)
        if args.report is not None:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(
            "Resource media/raw-cache independent replay: "
            f"{report['passed']}/{report['total']} cases, {report['checks']} checks, "
            f"resourceBytes={report['resourceBytes']}"
        )
        return 0
    except (OSError, VerificationFailure, TypeError, ValueError) as error:
        print(f"Resource media/raw-cache independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
