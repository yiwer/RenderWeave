#!/usr/bin/env python3
"""Independent stdlib replay for Renderer FONT preparation and request cache vectors."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


PROFILE = "renderweave-font-prepare-cache-v1"
RENDERER_PROFILE = "renderweave-renderer/1.0"
MAX_REQUEST_UNIQUE_FONTS = 32
REQUEST_UNIQUE_FONTS_LIMIT_ID = "layoutFontAndRaster.uniqueFonts"
MAX_FONT_TABLES_PER_CONTENT = 256
FONT_TABLES_PER_CONTENT_LIMIT_ID = "layoutFontAndRaster.tablesPerFont"
MAX_REQUEST_FONT_TABLES = 4096
REQUEST_FONT_TABLES_LIMIT_ID = "layoutFontAndRaster.fontTablesTotal"
SFNT_CHECKSUM_MAGIC = 0xB1B0AFBA
HEAD_MAGIC = 0x5F0F3CF5
CFF_MAX_STACK = 48
CFF_MAX_SUBR_DEPTH = 10

EXPECTED_PREPARED_IDS = {"ttf-glyf", "otf-cff"}
EXPECTED_FAILURE_IDS = {
    "ttf-glyf-contour-invalid",
    "ttf-cmap-version-invalid",
    "otf-cff-charstrings-invalid",
}
EXPECTED_CACHE_IDS = {
    "first-prepare",
    "duplicate-hit",
    "lease-expired-before-hit",
    "fact-corruption-evicts-without-refund",
}
EXPECTED_BUDGET_IDS = {
    "per-font-at",
    "per-font-above",
    "request-fonts-and-tables-at",
    "request-fonts-above",
    "request-tables-above",
    "first-error-unique-fonts-before-total-tables",
}
EXPECTED_SCOPE = {
    "fontStructureAndFacts": "A2_PYTHON_STDLIB_INDEPENDENT",
    "assetAcceptanceCorpus": "A2_EXISTING_FONTTOOLS_AND_STDLIB_GATE_REUSED",
    "cacheAndBudgetModel": "A2_PYTHON_STDLIB_INDEPENDENT",
}
EXPECTED_BOUNDARY = {
    "resourceBytes": "FULL_FONT_PARSE_AUTOMATED_VERIFIED_UNWIRED",
    "fontPreparation": "ASSET_APPROVED_TTF_GLYF_CFF_CMAP_DESCRIPTOR_FACTS",
    "fontShaping": "UNWIRED",
    "glyphConsumer": "UNWIRED",
    "nativeFontStack": "BUILD_NOT_AUTHORIZED",
    "preparedCache": "REQUEST_LOCAL_CONTENT_ADDRESSED_32_FONTS_4096_TABLES",
    "daemonOutputPath": "UNWIRED",
    "profileAvailability": "NOT_REGISTERED",
    "certificationStatus": "NOT_CERTIFIED",
    "processRasterImplementation": "ABSENT",
    "productRoute": "CLOSED",
    "providerAttempts": 0,
}
BANNED_TABLES = {
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
COMMON_REQUIRED = {b"cmap", b"head", b"hhea", b"hmtx", b"maxp", b"name", b"OS/2", b"post"}


class VerificationFailure(ValueError):
    pass


class FontInvalid(ValueError):
    pass


@dataclass
class Verifier:
    checks: int = 0

    def require(self, condition: bool, message: str) -> None:
        self.checks += 1
        if not condition:
            raise VerificationFailure(message)


@dataclass(frozen=True)
class FontTable:
    record_offset: int
    checksum: int
    offset: int
    length: int


@dataclass(frozen=True)
class CffIndex:
    count: int
    offsets: tuple[int, ...]
    next_position: int
    data_offset: int
    data_end: int

    def offset_of(self, index: int) -> int:
        if index < 0 or index >= len(self.offsets):
            raise FontInvalid("CFF INDEX offset is out of range")
        return self.data_offset + self.offsets[index]


def strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, nested in pairs:
        if key in value:
            raise VerificationFailure(f"duplicate JSON member: {key}")
        value[key] = nested
    return value


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


def exact_int(value: Any, label: str, minimum: int = 0) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
        raise VerificationFailure(f"{label} is not an integer >= {minimum}")
    return value


def repository_path(verifier: Verifier, repo_root: Path, value: Any, label: str) -> Path:
    verifier.require(
        isinstance(value, str)
        and Path(value).as_posix() == value
        and not Path(value).is_absolute()
        and ".." not in Path(value).parts,
        f"{label} is not a canonical repository-relative path",
    )
    resolved_root = repo_root.resolve()
    resolved = (resolved_root / value).resolve()
    verifier.require(resolved.is_relative_to(resolved_root), f"{label} escapes the repository")
    verifier.require(resolved.is_file(), f"{label} is absent")
    return resolved


def asset_bytes(verifier: Verifier, case: dict[str, Any]) -> bytes:
    case_id = case.get("id", "<unknown>")
    input_value = exact_members(case.get("input"), {"kind", "data"}, f"{case_id} Asset input")
    verifier.require(input_value["kind"] == "BASE64", f"{case_id}: Asset input kind drifted")
    encoded = input_value["data"]
    verifier.require(isinstance(encoded, str), f"{case_id}: Asset base64 is not a string")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise VerificationFailure(f"{case_id}: invalid Asset base64") from error
    verifier.require(base64.b64encode(raw).decode("ascii") == encoded, f"{case_id}: noncanonical base64")
    expected = case.get("expected")
    if isinstance(expected, dict) and expected.get("outcome") == "ADMITTED":
        verifier.require(expected.get("byteLength") == len(raw), f"{case_id}: byte length drifted")
        verifier.require(expected.get("sha256") == hashlib.sha256(raw).hexdigest(), f"{case_id}: sha256 drifted")
    return raw


def read_u16(data: bytes | bytearray, offset: int) -> int:
    if offset < 0 or offset + 2 > len(data):
        raise FontInvalid("u16 exceeds font bytes")
    return struct.unpack_from(">H", data, offset)[0]


def read_i16(data: bytes | bytearray, offset: int) -> int:
    if offset < 0 or offset + 2 > len(data):
        raise FontInvalid("i16 exceeds font bytes")
    return struct.unpack_from(">h", data, offset)[0]


def read_u32(data: bytes | bytearray, offset: int) -> int:
    if offset < 0 or offset + 4 > len(data):
        raise FontInvalid("u32 exceeds font bytes")
    return struct.unpack_from(">I", data, offset)[0]


def write_u32(data: bytearray, offset: int, value: int) -> None:
    if offset < 0 or offset + 4 > len(data):
        raise FontInvalid("u32 write exceeds font bytes")
    struct.pack_into(">I", data, offset, value & 0xFFFF_FFFF)


def table_checksum(data: bytes | bytearray, offset: int, length: int, zero_head_adjustment: bool = False) -> int:
    if offset < 0 or length < 0 or offset + length > len(data):
        raise FontInvalid("table checksum range exceeds font bytes")
    total = 0
    for relative in range(0, length, 4):
        chunk = bytes(data[offset + relative : offset + min(relative + 4, length)]).ljust(4, b"\0")
        if zero_head_adjustment and relative == 8:
            chunk = b"\0\0\0\0"
        total = (total + int.from_bytes(chunk, "big")) & 0xFFFF_FFFF
    return total


def whole_font_checksum(data: bytes | bytearray) -> int:
    return table_checksum(data, 0, len(data))


def parse_directory(data: bytes | bytearray) -> tuple[str, dict[bytes, FontTable]]:
    if len(data) < 12:
        raise FontInvalid("sfnt header is truncated")
    magic = bytes(data[:4])
    if magic == b"\0\1\0\0":
        flavor = "TRUETYPE_GLYF"
    elif magic == b"OTTO":
        flavor = "CFF"
    else:
        raise FontInvalid("sfnt flavor is unsupported")
    count = read_u16(data, 4)
    directory_end = 12 + count * 16
    if count == 0 or count > MAX_FONT_TABLES_PER_CONTENT or directory_end > len(data):
        raise FontInvalid("sfnt directory bounds are invalid")
    tables: dict[bytes, FontTable] = {}
    occupied: list[tuple[int, int]] = []
    for index in range(count):
        record = 12 + index * 16
        tag = bytes(data[record : record + 4])
        checksum = read_u32(data, record + 4)
        offset = read_u32(data, record + 8)
        length = read_u32(data, record + 12)
        end = offset + length
        if length == 0 or offset < directory_end or offset % 4 != 0 or end > len(data):
            raise FontInvalid("sfnt table range is invalid")
        if tag in tables:
            raise FontInvalid("sfnt table tag is duplicated")
        tables[tag] = FontTable(record, checksum, offset, length)
        occupied.append((offset, end))
    occupied.sort()
    if any(left[1] > right[0] for left, right in zip(occupied, occupied[1:])):
        raise FontInvalid("sfnt tables overlap")
    return flavor, tables


def shallow_font_preflight(data: bytes) -> tuple[str, int, int]:
    flavor, tables = parse_directory(data)
    if BANNED_TABLES.intersection(tables):
        raise FontInvalid("font contains an excluded table")
    required = set(COMMON_REQUIRED)
    required.update({b"glyf", b"loca"} if flavor == "TRUETYPE_GLYF" else {b"CFF "})
    if not required.issubset(tables):
        raise FontInvalid("font omits a required table")
    for tag, table in tables.items():
        actual = table_checksum(data, table.offset, table.length, zero_head_adjustment=tag == b"head")
        if actual != table.checksum:
            raise FontInvalid("sfnt table checksum is invalid")
    if whole_font_checksum(data) != SFNT_CHECKSUM_MAGIC:
        raise FontInvalid("whole-font checksum is invalid")
    head = tables[b"head"]
    if head.length < 54 or read_u32(data, head.offset + 12) != HEAD_MAGIC:
        raise FontInvalid("head table is invalid")
    units_per_em = read_u16(data, head.offset + 18)
    if not 16 <= units_per_em <= 16_384:
        raise FontInvalid("unitsPerEm is invalid")
    location_format = read_u16(data, head.offset + 50)
    maxp = tables[b"maxp"]
    if maxp.length < 6:
        raise FontInvalid("maxp table is truncated")
    maxp_version = read_u32(data, maxp.offset)
    glyph_count = read_u16(data, maxp.offset + 4)
    if glyph_count == 0:
        raise FontInvalid("font has no glyphs")
    if flavor == "TRUETYPE_GLYF":
        if maxp_version != 0x0001_0000:
            raise FontInvalid("TrueType maxp version is invalid")
        loca = tables[b"loca"]
        glyf = tables[b"glyf"]
        entry_size = 2 if location_format == 0 else 4 if location_format == 1 else 0
        if entry_size == 0 or loca.length != (glyph_count + 1) * entry_size:
            raise FontInvalid("loca shape is invalid")
        previous = 0
        for index in range(glyph_count + 1):
            position = loca.offset + index * entry_size
            current = read_u16(data, position) * 2 if entry_size == 2 else read_u32(data, position)
            if current < previous or current > glyf.length:
                raise FontInvalid("loca offsets are invalid")
            previous = current
    else:
        if maxp_version != 0x0000_5000:
            raise FontInvalid("CFF maxp version is invalid")
        cff = tables[b"CFF "]
        if cff.length < 4 or data[cff.offset] != 1 or data[cff.offset + 2] < 4:
            raise FontInvalid("CFF header is invalid")
    return flavor, units_per_em, glyph_count


def parse_font_facts(data: bytes, expected_flavor: str) -> dict[str, Any]:
    flavor, tables = parse_directory(data)
    if flavor != expected_flavor:
        raise FontInvalid("font flavor contradicts descriptor")
    head = tables.get(b"head")
    maxp = tables.get(b"maxp")
    cmap = tables.get(b"cmap")
    if head is None or maxp is None or cmap is None or head.length < 54 or maxp.length < 6:
        raise FontInvalid("deep parser required tables are absent")
    units_per_em = read_u16(data, head.offset + 18)
    if not 16 <= units_per_em <= 16_384:
        raise FontInvalid("deep parser unitsPerEm is invalid")
    location_format = read_u16(data, head.offset + 50)
    maxp_version = read_u32(data, maxp.offset)
    glyph_count = read_u16(data, maxp.offset + 4)
    if glyph_count == 0:
        raise FontInvalid("deep parser found no glyphs")
    if flavor == "TRUETYPE_GLYF":
        if maxp_version != 0x0001_0000:
            raise FontInvalid("deep TrueType maxp version is invalid")
        non_empty = validate_glyf(data, tables, glyph_count, location_format)
    else:
        if maxp_version != 0x0000_5000:
            raise FontInvalid("deep CFF maxp version is invalid")
        cff = tables.get(b"CFF ")
        if cff is None:
            raise FontInvalid("CFF table is absent")
        validate_cff(data, cff, glyph_count)
        non_empty = glyph_count
    supported_cmap = validate_cmap(data, cmap)
    layout_tables = [tag.decode("ascii") for tag in (b"GDEF", b"GSUB", b"GPOS", b"kern") if tag in tables]
    return {
        "flavor": flavor,
        "unitsPerEm": units_per_em,
        "tableCount": len(tables),
        "glyphCount": glyph_count,
        "nonEmptyOutlineCount": non_empty,
        "supportedCmapSubtableCount": supported_cmap,
        "layoutTables": layout_tables,
    }


def validate_glyf(data: bytes, tables: dict[bytes, FontTable], glyph_count: int, location_format: int) -> int:
    loca = tables.get(b"loca")
    glyf = tables.get(b"glyf")
    if loca is None or glyf is None:
        raise FontInvalid("TrueType outline tables are absent")
    entry_size = 2 if location_format == 0 else 4 if location_format == 1 else 0
    if entry_size == 0 or loca.length != (glyph_count + 1) * entry_size:
        raise FontInvalid("deep loca shape is invalid")
    offsets: list[int] = []
    for index in range(glyph_count + 1):
        position = loca.offset + index * entry_size
        value = read_u16(data, position) * 2 if entry_size == 2 else read_u32(data, position)
        if value > glyf.length or (offsets and value < offsets[-1]):
            raise FontInvalid("deep loca offsets are invalid")
        offsets.append(value)
    if offsets[-1] != glyf.length:
        raise FontInvalid("deep loca does not consume glyf")
    references: list[list[int] | None] = [None] * glyph_count
    non_empty = 0
    for glyph in range(glyph_count):
        start = glyf.offset + offsets[glyph]
        end = glyf.offset + offsets[glyph + 1]
        if start == end:
            continue
        non_empty += 1
        references[glyph] = parse_glyph(data, start, end, glyph_count)
    validate_composite_graph(references)
    return non_empty


def parse_glyph(data: bytes, start: int, end: int, glyph_count: int) -> list[int] | None:
    if end - start < 10:
        raise FontInvalid("glyph record is truncated")
    contour_count = read_i16(data, start)
    if contour_count < -1:
        raise FontInvalid("glyph contour count is invalid")
    if contour_count == -1:
        return parse_composite_glyph(data, start, end, glyph_count)
    position = start + 10
    points = 0
    for _ in range(contour_count):
        point = read_u16_bounded(data, position, end)
        position += 2
        if point < points:
            raise FontInvalid("simple glyph endpoints are not monotonic")
        points = point + 1
    instruction_length = read_u16_bounded(data, position, end)
    position += 2 + instruction_length
    if position > end:
        raise FontInvalid("simple glyph instructions exceed record")
    flags: list[int] = []
    while len(flags) < points:
        if position >= end:
            raise FontInvalid("simple glyph flags are truncated")
        flag = data[position]
        position += 1
        flags.append(flag)
        if flag & 0x08:
            if position >= end:
                raise FontInvalid("simple glyph flag repeat is truncated")
            repeat = data[position]
            position += 1
            if len(flags) + repeat > points:
                raise FontInvalid("simple glyph flag repeat exceeds point count")
            flags.extend([flag] * repeat)
    for flag in flags:
        position += 1 if flag & 0x02 else 2 if not flag & 0x10 else 0
        if position > end:
            raise FontInvalid("simple glyph x coordinates exceed record")
    for flag in flags:
        position += 1 if flag & 0x04 else 2 if not flag & 0x20 else 0
        if position > end:
            raise FontInvalid("simple glyph y coordinates exceed record")
    return None


def read_u16_bounded(data: bytes, position: int, end: int) -> int:
    if position + 2 > end:
        raise FontInvalid("bounded u16 exceeds record")
    return read_u16(data, position)


def parse_composite_glyph(data: bytes, start: int, end: int, glyph_count: int) -> list[int]:
    position = start + 10
    references: list[int] = []
    final_flags = 0
    while True:
        flags = read_u16_bounded(data, position, end)
        glyph_index = read_u16_bounded(data, position + 2, end)
        position += 4
        if glyph_index >= glyph_count:
            raise FontInvalid("composite glyph reference exceeds glyph count")
        references.append(glyph_index)
        position += 4 if flags & 0x0001 else 2
        if flags & 0x0008:
            position += 2
        elif flags & 0x0040:
            position += 4
        elif flags & 0x0080:
            position += 8
        if position > end:
            raise FontInvalid("composite glyph component exceeds record")
        if not flags & 0x0020:
            final_flags = flags
            break
    if final_flags & 0x0100:
        instruction_length = read_u16_bounded(data, position, end)
        position += 2 + instruction_length
        if position > end:
            raise FontInvalid("composite glyph instructions exceed record")
    return references


def validate_composite_graph(references: list[list[int] | None]) -> None:
    state = [0] * len(references)
    for root, children in enumerate(references):
        if children is None or state[root] == 2:
            continue
        state[root] = 1
        stack: list[tuple[int, int]] = [(root, 0)]
        while stack:
            glyph, next_index = stack[-1]
            glyph_children = references[glyph]
            if glyph_children is None:
                raise FontInvalid("composite traversal entered a simple glyph")
            if next_index == len(glyph_children):
                state[glyph] = 2
                stack.pop()
                continue
            child = glyph_children[next_index]
            stack[-1] = (glyph, next_index + 1)
            if references[child] is None or state[child] == 2:
                continue
            if state[child] == 1:
                raise FontInvalid("composite glyph graph contains a cycle")
            state[child] = 1
            stack.append((child, 0))


def validate_cmap(data: bytes, cmap: FontTable) -> int:
    if cmap.length < 4 or read_u16(data, cmap.offset) != 0:
        raise FontInvalid("cmap header is invalid")
    record_count = read_u16(data, cmap.offset + 2)
    records_end = 4 + record_count * 8
    if record_count == 0 or records_end > cmap.length:
        raise FontInvalid("cmap records are invalid")
    cmap_end = cmap.offset + cmap.length
    supported = 0
    for index in range(record_count):
        record = cmap.offset + 4 + index * 8
        relative = read_u32(data, record + 4)
        if relative >= cmap.length:
            raise FontInvalid("cmap subtable offset is invalid")
        subtable = cmap.offset + relative
        fmt = read_u16(data, subtable)
        valid = False
        if fmt == 4 and subtable + 14 <= cmap_end:
            segment_count_x2 = read_u16(data, subtable + 6)
            valid = segment_count_x2 != 0 and segment_count_x2 % 2 == 0 and subtable + 16 + segment_count_x2 * 4 <= cmap_end
        elif fmt == 12 and subtable + 16 <= cmap_end:
            groups = read_u32(data, subtable + 12)
            valid = groups != 0 and 16 + groups * 12 <= cmap.length - relative
        elif fmt in {0, 6}:
            valid = subtable + 6 <= cmap_end
        if valid:
            supported += 1
    if supported == 0:
        raise FontInvalid("cmap has no supported subtable")
    return supported


def parse_cff_index(data: bytes, position: int, end: int) -> CffIndex:
    if position + 2 > end:
        raise FontInvalid("CFF INDEX count is truncated")
    count = read_u16(data, position)
    position += 2
    if count == 0:
        return CffIndex(0, (0,), position, position, position)
    if position >= end:
        raise FontInvalid("CFF INDEX offSize is absent")
    off_size = data[position]
    position += 1
    encoded_offsets = (count + 1) * off_size
    if not 1 <= off_size <= 4 or position + encoded_offsets > end:
        raise FontInvalid("CFF INDEX offsets are invalid")
    offsets: list[int] = []
    for index in range(count + 1):
        start = position + index * off_size
        value = int.from_bytes(data[start : start + off_size], "big") - 1
        if value < 0:
            raise FontInvalid("CFF INDEX offset is zero")
        offsets.append(value)
    if offsets[0] != 0 or any(left > right for left, right in zip(offsets, offsets[1:])):
        raise FontInvalid("CFF INDEX offsets are not monotonic")
    data_offset = position + encoded_offsets
    data_end = data_offset + offsets[-1]
    if data_end > end:
        raise FontInvalid("CFF INDEX data exceeds table")
    return CffIndex(count, tuple(offsets), data_end, data_offset, data_end)


def read_cff_number(data: bytes, position: int, end: int) -> tuple[int, int]:
    if position >= end:
        raise FontInvalid("CFF number is truncated")
    first = data[position]
    if 32 <= first <= 246:
        return first - 139, position + 1
    if 247 <= first <= 250:
        if position + 1 >= end:
            raise FontInvalid("CFF positive number is truncated")
        return (first - 247) * 256 + data[position + 1] + 108, position + 2
    if 251 <= first <= 254:
        if position + 1 >= end:
            raise FontInvalid("CFF negative number is truncated")
        return -(first - 251) * 256 - data[position + 1] - 108, position + 2
    if first == 28:
        if position + 3 > end:
            raise FontInvalid("CFF short integer is truncated")
        return struct.unpack_from(">h", data, position + 1)[0], position + 3
    if first == 30:
        return 0, parse_cff_real(data, position + 1, end)
    if first == 255:
        if position + 5 > end:
            raise FontInvalid("CFF long integer is truncated")
        return struct.unpack_from(">i", data, position + 1)[0], position + 5
    raise FontInvalid("CFF number encoding is invalid")


def parse_cff_real(data: bytes, position: int, end: int) -> int:
    while position < end:
        byte = data[position]
        for nibble in (byte >> 4, byte & 0x0F):
            if nibble == 0x0F:
                return position + 1
            if nibble >= 0x0A and nibble != 0x0E:
                raise FontInvalid("CFF real contains an invalid nibble")
        position += 1
    raise FontInvalid("CFF real is unterminated")


def dict_operator_count(operator: int, top_level: bool) -> int:
    if operator in {0, 6, 7, 8, 9, 11}:
        return 0
    if operator in {1, 2, 3, 4, 10, 13, 15, 16, 17, 20, 21}:
        return 1
    if operator == 5:
        return 4
    if operator == 18:
        return 2
    if operator == 19 and not top_level:
        return 1
    raise FontInvalid("CFF DICT operator is unsupported")


def escaped_dict_operator_count(operator: int) -> int:
    if operator in {0, 1, 2, 3, 4, 5, 6, 8, 20, 21, 22, 31, 32, 33, 34, 35, 36, 37, 38}:
        return 1
    if operator == 7:
        return 4
    if operator == 23:
        return 2
    if operator == 30:
        return 3
    if 9 <= operator <= 19 or 24 <= operator <= 29:
        return 0
    raise FontInvalid("CFF escaped DICT operator is unsupported")


def parse_cff_dict(data: bytes, start: int, end: int, top_level: bool) -> dict[str, int | None]:
    position = start
    stack: list[int] = []
    result: dict[str, int | None] = {
        "charStrings": None,
        "privateOffset": None,
        "privateSize": None,
        "subrs": None,
    }
    while position < end:
        first = data[position]
        if first == 12:
            if position + 1 >= end:
                raise FontInvalid("CFF escaped DICT operator is truncated")
            count = escaped_dict_operator_count(data[position + 1])
            if len(stack) < count:
                raise FontInvalid("CFF escaped DICT operands are absent")
            if count:
                del stack[-count:]
            position += 2
        elif first <= 21:
            count = dict_operator_count(first, top_level)
            if len(stack) < count:
                raise FontInvalid("CFF DICT operands are absent")
            if first == 17 and top_level:
                result["charStrings"] = stack[-1]
            elif first == 18 and top_level:
                result["privateSize"] = stack[-2]
                result["privateOffset"] = stack[-1]
            elif first == 19 and not top_level:
                result["subrs"] = stack[-1]
            if any(value is not None and value < 0 for value in result.values()):
                raise FontInvalid("CFF DICT offset is negative")
            if count:
                del stack[-count:]
            position += 1
        else:
            if len(stack) >= CFF_MAX_STACK:
                raise FontInvalid("CFF DICT stack exceeds limit")
            value, position = read_cff_number(data, position, end)
            stack.append(value)
    return result


def escaped_charstring_operator_count(operator: int) -> int:
    if operator in {3, 4, 10, 11, 12, 15, 20, 24, 28, 30}:
        return 2
    if operator in {5, 9, 14, 18, 21, 26, 27, 29}:
        return 1
    if operator == 22:
        return 4
    if operator == 23:
        return 0
    if operator == 34:
        return 7
    if operator == 35:
        return 13
    if operator == 36:
        return 9
    if operator == 37:
        return 11
    raise FontInvalid("CFF escaped charstring operator is unsupported")


def validate_cff_charstring(
    data: bytes,
    start: int,
    end: int,
    local_subrs: CffIndex | None,
    global_subrs: CffIndex,
    depth: int,
    active_subrs: list[bool],
    is_subr: bool,
) -> None:
    if depth > CFF_MAX_SUBR_DEPTH or start > end:
        raise FontInvalid("CFF charstring recursion or range is invalid")
    local_count = 0 if local_subrs is None else local_subrs.count
    global_count = global_subrs.count
    position = start
    stack: list[int] = []
    width_allowed = True
    saw_endchar = False
    saw_return = False
    while position < end:
        first = data[position]
        if first in {28, 30, 255} or 32 <= first <= 254:
            if len(stack) >= CFF_MAX_STACK:
                raise FontInvalid("CFF charstring stack exceeds limit")
            value, position = read_cff_number(data, position, end)
            stack.append(value)
            continue
        if first == 12:
            if position + 1 >= end:
                raise FontInvalid("CFF escaped charstring operator is truncated")
            operator = data[position + 1]
            count = escaped_charstring_operator_count(operator)
            if len(stack) != count:
                raise FontInvalid("CFF escaped charstring operand count is invalid")
            if 34 <= operator <= 37:
                width_allowed = False
            stack.clear()
            position += 2
            continue
        count = len(stack)
        if first in {1, 3, 18, 23}:
            if count < 2 or count % 2:
                raise FontInvalid("CFF stem operands are invalid")
            stack.clear()
        elif first in {4, 22}:
            if count != 1 and not (width_allowed and count == 2):
                raise FontInvalid("CFF move operands are invalid")
            stack.clear()
            width_allowed = False
        elif first == 21:
            if count != 2 and not (width_allowed and count == 3):
                raise FontInvalid("CFF rmoveto operands are invalid")
            stack.clear()
            width_allowed = False
        elif 5 <= first <= 7:
            if count < 1:
                raise FontInvalid("CFF line operands are absent")
            stack.clear()
            width_allowed = False
        elif first == 8:
            if count < 6 or count % 6:
                raise FontInvalid("CFF curve operands are invalid")
            stack.clear()
            width_allowed = False
        elif first in {10, 29}:
            if count != 1:
                raise FontInvalid("CFF subroutine operand is invalid")
            subr = stack[0]
            index = local_subrs if first == 10 else global_subrs
            available = local_count if first == 10 else global_count
            slot = subr if first == 10 else local_count + subr
            if index is None or subr < 0 or subr >= available or slot >= len(active_subrs) or active_subrs[slot]:
                raise FontInvalid("CFF subroutine reference is invalid")
            active_subrs[slot] = True
            try:
                validate_cff_charstring(
                    data,
                    index.offset_of(subr),
                    index.offset_of(subr + 1),
                    local_subrs,
                    global_subrs,
                    depth + 1,
                    active_subrs,
                    True,
                )
            finally:
                active_subrs[slot] = False
            stack.clear()
        elif first == 11:
            if stack:
                raise FontInvalid("CFF return retains operands")
            saw_return = True
        elif first == 14:
            if count not in {0, 4} and not (width_allowed and count in {1, 5}):
                raise FontInvalid("CFF endchar operands are invalid")
            stack.clear()
            saw_endchar = True
        elif first in {19, 20}:
            stems = count
            if stems % 2:
                if not width_allowed:
                    raise FontInvalid("CFF hintmask width operand is invalid")
                stems -= 1
            mask_bytes = ((stems // 2) + 7) // 8
            position += 1 + mask_bytes
            if position > end:
                raise FontInvalid("CFF hintmask exceeds charstring")
            stack.clear()
            width_allowed = False
            continue
        elif first in {24, 25}:
            if count < 8 or count % 2:
                raise FontInvalid("CFF line/curve operands are invalid")
            stack.clear()
            width_allowed = False
        elif first in {26, 27}:
            if count < 4 or count % 4 not in {0, 1}:
                raise FontInvalid("CFF vv/hh curve operands are invalid")
            stack.clear()
            width_allowed = False
        elif first in {30, 31}:
            if count < 4 or count % 8 not in {4, 5}:
                raise FontInvalid("CFF vh/hv curve operands are invalid")
            stack.clear()
            width_allowed = False
        else:
            raise FontInvalid("CFF charstring operator is unsupported")
        position += 1
    if (is_subr and not saw_return) or (not is_subr and not saw_endchar):
        raise FontInvalid("CFF charstring has no terminal operator")


def validate_cff(data: bytes, table: FontTable, expected_glyphs: int) -> None:
    end = table.offset + table.length
    if table.offset + 4 > end:
        raise FontInvalid("CFF header is truncated")
    major, minor, header_size, off_size = data[table.offset : table.offset + 4]
    if major != 1 or minor != 0 or header_size < 4 or not 1 <= off_size <= 4 or table.offset + header_size > end:
        raise FontInvalid("CFF header is invalid")
    names = parse_cff_index(data, table.offset + header_size, end)
    if names.count != 1:
        raise FontInvalid("CFF Name INDEX count is invalid")
    top_dicts = parse_cff_index(data, names.next_position, end)
    if top_dicts.count != 1:
        raise FontInvalid("CFF Top DICT INDEX count is invalid")
    strings = parse_cff_index(data, top_dicts.next_position, end)
    global_subrs = parse_cff_index(data, strings.next_position, end)
    top_dict = parse_cff_dict(data, top_dicts.data_offset, top_dicts.data_end, True)
    charstrings_relative = top_dict["charStrings"]
    if charstrings_relative is None or table.offset + charstrings_relative >= end:
        raise FontInvalid("CFF CharStrings offset is invalid")
    charstrings = parse_cff_index(data, table.offset + charstrings_relative, end)
    if charstrings.count != expected_glyphs:
        raise FontInvalid("CFF CharStrings count contradicts maxp")
    private_offset = top_dict["privateOffset"]
    private_size = top_dict["privateSize"]
    local_subrs: CffIndex | None = None
    if (private_offset is None) != (private_size is None):
        raise FontInvalid("CFF Private DICT pair is incomplete")
    if private_offset is not None and private_size is not None:
        private_start = table.offset + private_offset
        private_end = private_start + private_size
        if private_start < table.offset or private_end > end:
            raise FontInvalid("CFF Private DICT exceeds table")
        private_dict = parse_cff_dict(data, private_start, private_end, False)
        if private_dict["subrs"] is not None:
            local_subrs = parse_cff_index(data, private_start + private_dict["subrs"], private_end)
    local_count = 0 if local_subrs is None else local_subrs.count
    active_subrs = [False] * (local_count + global_subrs.count)
    for glyph in range(charstrings.count):
        validate_cff_charstring(
            data,
            charstrings.offset_of(glyph),
            charstrings.offset_of(glyph + 1),
            local_subrs,
            global_subrs,
            0,
            active_subrs,
            False,
        )


def mutate_font(data: bytes, mutation: str) -> bytes:
    if mutation == "NONE":
        return data
    changed = bytearray(data)
    _, tables = parse_directory(changed)
    if mutation == "GLYF_CONTOUR":
        table = tables[b"glyf"]
        struct.pack_into(">h", changed, table.offset, -5)
        changed_tag = b"glyf"
    elif mutation == "CMAP_VERSION":
        table = tables[b"cmap"]
        struct.pack_into(">H", changed, table.offset, 1)
        changed_tag = b"cmap"
    else:
        raise VerificationFailure(f"unknown font mutation {mutation}")
    changed_table = tables[changed_tag]
    write_u32(changed, changed_table.record_offset + 4, table_checksum(changed, changed_table.offset, changed_table.length))
    head = tables[b"head"]
    write_u32(changed, head.offset + 8, 0)
    write_u32(changed, head.record_offset + 4, table_checksum(changed, head.offset, head.length))
    write_u32(changed, head.offset + 8, (SFNT_CHECKSUM_MAGIC - whole_font_checksum(changed)) & 0xFFFF_FFFF)
    if whole_font_checksum(changed) != SFNT_CHECKSUM_MAGIC:
        raise VerificationFailure("rechecksummed font mutation is invalid")
    return bytes(changed)


def replay_cache(case: dict[str, Any]) -> tuple[str, int, int, int]:
    entries = 0
    consumed_fonts = 0
    retained_tables = 0
    lease_expired = False
    corrupt = False
    outcome = "MISS"
    for event in case["events"]:
        if event == "MISS":
            outcome = "MISS"
        elif event == "PARSE":
            outcome = "PARSED"
        elif event == "INSERT":
            entries = 1
            consumed_fonts += 1
            retained_tables += 10
            outcome = "INSERTED"
        elif event == "LEASE_EXPIRED":
            lease_expired = True
        elif event == "CORRUPT_FACTS":
            corrupt = True
        elif event == "HIT":
            if lease_expired:
                outcome = "RESOURCE_LEASE_EXPIRED"
            elif corrupt and entries == 1:
                entries = 0
                outcome = "RENDER_INTERNAL_ERROR"
            elif entries == 1:
                outcome = "HIT"
            else:
                outcome = "MISS"
        else:
            raise VerificationFailure(f"{case['id']}: unknown cache event {event}")
    return outcome, entries, consumed_fonts, retained_tables


def replay_budget(case: dict[str, Any]) -> tuple[str, str | None, int, int]:
    consumed = exact_int(case["consumedUniqueFontsBefore"], f"{case['id']} consumed before")
    retained = exact_int(case["retainedTablesBefore"], f"{case['id']} retained before")
    reserve = exact_int(case["reserveTables"], f"{case['id']} reserve")
    if reserve > MAX_FONT_TABLES_PER_CONTENT:
        return "RESOURCE_BUDGET_EXCEEDED", FONT_TABLES_PER_CONTENT_LIMIT_ID, consumed, retained
    if consumed >= MAX_REQUEST_UNIQUE_FONTS:
        return "RESOURCE_BUDGET_EXCEEDED", REQUEST_UNIQUE_FONTS_LIMIT_ID, consumed, retained
    if retained + reserve > MAX_REQUEST_FONT_TABLES:
        return "RESOURCE_BUDGET_EXCEEDED", REQUEST_FONT_TABLES_LIMIT_ID, consumed, retained
    return "RESERVED", None, consumed + 1, retained + reserve


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
            "preparedCases",
            "failureCases",
            "cacheCases",
            "budgetCases",
            "independentReplayScope",
            "boundary",
        },
        "FONT vector document",
    )
    verifier = Verifier()
    verifier.require(vectors["profile"] == PROFILE, "FONT profile drifted")
    verifier.require(vectors["rendererProfileIdentity"] == RENDERER_PROFILE, "Renderer profile drifted")
    limits = exact_members(
        vectors["limits"],
        {
            "requestUniqueFonts",
            "requestUniqueFontsLimitId",
            "fontTablesPerContent",
            "fontTablesPerContentLimitId",
            "requestFontTables",
            "requestFontTablesLimitId",
        },
        "FONT limits",
    )
    expected_limits = {
        "requestUniqueFonts": MAX_REQUEST_UNIQUE_FONTS,
        "requestUniqueFontsLimitId": REQUEST_UNIQUE_FONTS_LIMIT_ID,
        "fontTablesPerContent": MAX_FONT_TABLES_PER_CONTENT,
        "fontTablesPerContentLimitId": FONT_TABLES_PER_CONTENT_LIMIT_ID,
        "requestFontTables": MAX_REQUEST_FONT_TABLES,
        "requestFontTablesLimitId": REQUEST_FONT_TABLES_LIMIT_ID,
    }
    for key, expected in expected_limits.items():
        verifier.require(limits[key] == expected, f"{key} drifted")
    exact_members(vectors["independentReplayScope"], set(EXPECTED_SCOPE), "independent replay scope")
    for key, expected in EXPECTED_SCOPE.items():
        verifier.require(vectors["independentReplayScope"][key] == expected, f"{key} assurance drifted")
    exact_members(vectors["boundary"], set(EXPECTED_BOUNDARY), "FONT boundary")
    for key, expected in EXPECTED_BOUNDARY.items():
        verifier.require(vectors["boundary"][key] == expected, f"{key} boundary drifted")

    asset_path = repository_path(verifier, repo_root, vectors["assetKernelVectorPath"], "Asset vector path")
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

    prepared_cases = exact_cases(vectors["preparedCases"], 2, "prepared")
    failure_cases = exact_cases(vectors["failureCases"], 3, "failure")
    cache_cases = exact_cases(vectors["cacheCases"], 4, "cache")
    budget_cases = exact_cases(vectors["budgetCases"], 6, "budget")
    seen_ids: set[str] = set()
    for case in prepared_cases:
        exact_members(case, {"id", "assetCaseId", "declaredMediaType", "facts"}, "prepared case")
        case_id = case["id"]
        verifier.require(isinstance(case_id, str) and case_id not in seen_ids, "prepared id is invalid or duplicated")
        seen_ids.add(case_id)
        asset_case = asset_index.get(case["assetCaseId"])
        verifier.require(asset_case is not None, f"{case_id}: Asset case is absent")
        data = asset_bytes(verifier, asset_case)
        expected = asset_case.get("expected", {})
        descriptor = expected.get("descriptor", {})
        verifier.require(expected.get("outcome") == "ADMITTED" and descriptor.get("type") == "FONT", f"{case_id}: Asset case is not an admitted FONT")
        expected_flavor = "TRUETYPE_GLYF" if case["declaredMediaType"] == "font/ttf" else "CFF" if case["declaredMediaType"] == "font/otf" else None
        verifier.require(expected_flavor is not None, f"{case_id}: declared media type drifted")
        shallow_flavor, shallow_units, shallow_glyphs = shallow_font_preflight(data)
        verifier.require(shallow_flavor == expected_flavor, f"{case_id}: shallow flavor drifted")
        facts = exact_members(
            case["facts"],
            {
                "flavor",
                "unitsPerEm",
                "tableCount",
                "glyphCount",
                "nonEmptyOutlineCount",
                "supportedCmapSubtableCount",
                "layoutTables",
            },
            f"{case_id} facts",
        )
        verifier.require(
            descriptor.get("faceIndex") == 0
            and descriptor.get("flavor") == expected_flavor
            and descriptor.get("unitsPerEm") == shallow_units
            and shallow_glyphs == facts["glyphCount"],
            f"{case_id}: Asset descriptor or shallow facts drifted",
        )
        verifier.require(parse_font_facts(data, expected_flavor) == facts, f"{case_id}: deep font facts drifted")
    verifier.require({case["id"] for case in prepared_cases} == EXPECTED_PREPARED_IDS, "prepared case set drifted")

    mutation_corpus = hashlib.sha256()
    for case in failure_cases:
        exact_members(
            case,
            {"id", "sourceAssetCaseId", "descriptorCaseId", "declaredMediaType", "mutation", "expectedCode"},
            "failure case",
        )
        case_id = case["id"]
        verifier.require(isinstance(case_id, str) and case_id not in seen_ids, "failure id is invalid or duplicated")
        seen_ids.add(case_id)
        source_case = asset_index.get(case["sourceAssetCaseId"])
        descriptor_case = asset_index.get(case["descriptorCaseId"])
        verifier.require(source_case is not None and descriptor_case is not None, f"{case_id}: Asset reference is absent")
        source = asset_bytes(verifier, source_case)
        descriptor = descriptor_case.get("expected", {}).get("descriptor", {})
        verifier.require(descriptor_case.get("expected", {}).get("outcome") == "ADMITTED", f"{case_id}: descriptor source is not admitted")
        mutated = mutate_font(source, case["mutation"])
        expected_flavor = "TRUETYPE_GLYF" if case["declaredMediaType"] == "font/ttf" else "CFF" if case["declaredMediaType"] == "font/otf" else None
        verifier.require(expected_flavor is not None and descriptor.get("flavor") == expected_flavor, f"{case_id}: failure media/descriptor drifted")
        shallow_flavor, shallow_units, _ = shallow_font_preflight(mutated)
        verifier.require(shallow_flavor == expected_flavor and shallow_units == descriptor.get("unitsPerEm"), f"{case_id}: mutation did not pass raw preflight")
        failed = False
        try:
            parse_font_facts(mutated, expected_flavor)
        except FontInvalid:
            failed = True
        verifier.require(failed, f"{case_id}: deep parser admitted the failure")
        verifier.require(case["expectedCode"] == "DECODE_FAILED", f"{case_id}: failure code drifted")
        mutation_corpus.update(case_id.encode("utf-8") + b"\0" + hashlib.sha256(mutated).digest())
    verifier.require({case["id"] for case in failure_cases} == EXPECTED_FAILURE_IDS, "failure case set drifted")

    for case in cache_cases:
        exact_members(
            case,
            {"id", "events", "outcome", "uniqueContents", "consumedUniqueFonts", "retainedTables"},
            "cache case",
        )
        case_id = case["id"]
        verifier.require(isinstance(case_id, str) and case_id not in seen_ids, "cache id is invalid or duplicated")
        seen_ids.add(case_id)
        verifier.require(isinstance(case["events"], list) and all(isinstance(event, str) for event in case["events"]), f"{case_id}: cache events are invalid")
        outcome, entries, consumed, retained = replay_cache(case)
        verifier.require(outcome == case["outcome"], f"{case_id}: cache outcome drifted")
        verifier.require(entries == case["uniqueContents"], f"{case_id}: cache cardinality drifted")
        verifier.require(consumed == case["consumedUniqueFonts"], f"{case_id}: consumed font count drifted")
        verifier.require(retained == case["retainedTables"], f"{case_id}: retained table count drifted")
    verifier.require({case["id"] for case in cache_cases} == EXPECTED_CACHE_IDS, "cache case set drifted")

    for case in budget_cases:
        expected = {
            "id",
            "consumedUniqueFontsBefore",
            "retainedTablesBefore",
            "reserveTables",
            "outcome",
            "consumedUniqueFontsAfter",
            "retainedTablesAfter",
        }
        if case.get("outcome") != "RESERVED":
            expected.add("limitId")
        exact_members(case, expected, "budget case")
        case_id = case["id"]
        verifier.require(isinstance(case_id, str) and case_id not in seen_ids, "budget id is invalid or duplicated")
        seen_ids.add(case_id)
        outcome, limit_id, consumed, retained = replay_budget(case)
        verifier.require(outcome == case["outcome"], f"{case_id}: budget outcome drifted")
        verifier.require(limit_id == case.get("limitId"), f"{case_id}: budget limitId drifted")
        verifier.require(consumed == case["consumedUniqueFontsAfter"], f"{case_id}: budget font state drifted")
        verifier.require(retained == case["retainedTablesAfter"], f"{case_id}: budget table state drifted")
    verifier.require({case["id"] for case in budget_cases} == EXPECTED_BUDGET_IDS, "budget case set drifted")

    total = len(prepared_cases) + len(failure_cases) + len(cache_cases) + len(budget_cases)
    return {
        "verifier": "renderweave-font-prepare-cache-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "structuralAssurance": vectors["independentReplayScope"]["fontStructureAndFacts"],
        "assetCorpusAssurance": vectors["independentReplayScope"]["assetAcceptanceCorpus"],
        "cacheBudgetAssurance": vectors["independentReplayScope"]["cacheAndBudgetModel"],
        "preparedCases": len(prepared_cases),
        "failureCases": len(failure_cases),
        "cacheCases": len(cache_cases),
        "budgetCases": len(budget_cases),
        "total": total,
        "passed": total,
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(raw).hexdigest(),
        "assetKernelVectorSha256": asset_sha,
        "mutationCorpusSha256": "sha256:" + mutation_corpus.hexdigest(),
        "rendererProfileIdentity": vectors["rendererProfileIdentity"],
        **limits,
        **vectors["boundary"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    repo_root = Path(__file__).resolve().parents[1]
    parser.add_argument(
        "--vectors",
        type=Path,
        default=repo_root / "renderer" / "font-prepare-cache-vectors-v1.json",
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
            "FONT prepare/cache independent replay: "
            f"{report['passed']}/{report['total']} cases, {report['checks']} checks, "
            f"structure={report['structuralAssurance']}, "
            f"vector={report['vectorSha256']}, mutations={report['mutationCorpusSha256']}"
        )
        return 0
    except (OSError, VerificationFailure, FontInvalid, TypeError, ValueError) as error:
        print(f"FONT prepare/cache independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
