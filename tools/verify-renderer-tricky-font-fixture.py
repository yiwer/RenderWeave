#!/usr/bin/env python3
"""Independently verify the portable synthetic FreeType tricky-font fixture."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import subprocess
import sys
from pathlib import Path, PurePosixPath
from typing import Any


FIXTURE_ID = "rw-renderer-portable-tricky-font-v1"
CANDIDATE_ID = "rw-renderer-spike-linux-x86_64-v2-000002"
AUTHORITY_PATH = (
    ".scratch/renderweave-template-v1/renderer-spike/"
    "tricky-font-fixture-v1/authority-v1.json"
)
FONT_PATH = (
    ".scratch/renderweave-template-v1/renderer-spike/"
    "tricky-font-fixture-v1/renderweave-cpop-fixture-v1.ttf"
)
LICENSE_PATH = (
    ".scratch/renderweave-template-v1/renderer-spike/"
    "tricky-font-fixture-v1/LICENSE-0BSD.txt"
)
PROVENANCE_PATH = (
    ".scratch/renderweave-template-v1/renderer-spike/"
    "tricky-font-fixture-v1/provenance-v1.json"
)
RECIPE_PATH = "tools/generate-renderer-tricky-font-fixture.py"
POSTSCRIPT_NAME = "RenderWeave-cpop-Fixture"
TRICKY_TOKEN = "cpop"
SFNT_CHECKSUM_MAGIC = 0xB1B0AFBA
EXPECTED_TAGS = [
    "OS/2", "cmap", "cvt ", "fpgm", "glyf", "head", "hhea",
    "hmtx", "loca", "maxp", "name", "post", "prep",
]
BOUNDARY = {
    "exactBuiltTargetObserved": False,
    "runtimeBytecodeNonExecutionProven": False,
    "noHintingVersusNoAutoHintDistinguished": False,
    "physicalLinuxReplayComplete": False,
    "rendererExactOutputRecordIssuanceAllowed": False,
    "certified": False,
    "ready": False,
    "ticket19MayClose": False,
}


class VerificationFailure(RuntimeError):
    pass


class Verifier:
    def __init__(self) -> None:
        self.check_count = 0

    def require(self, condition: bool, code: str, detail: object) -> None:
        self.check_count += 1
        if not condition:
            raise VerificationFailure(f"{code}: {detail}")


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def padded(data: bytes) -> bytes:
    return data + b"\0" * ((-len(data)) % 4)


def sfnt_checksum(data: bytes) -> int:
    block = padded(data)
    return sum(struct.unpack(f">{len(block) // 4}I", block)) & 0xFFFFFFFF


def table_checksum(tag: str, data: bytes) -> int:
    if tag != "head":
        return sfnt_checksum(data)
    zeroed = bytearray(data)
    zeroed[8:12] = b"\0\0\0\0"
    return sfnt_checksum(bytes(zeroed))


def duplicate_safe_pairs(
    verifier: Verifier, pairs: list[tuple[str, Any]]
) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        verifier.require(key not in value, "JSON_DUPLICATE_MEMBER", key)
        value[key] = item
    return value


def decode_json(verifier: Verifier, data: bytes, location: str) -> dict[str, Any]:
    verifier.require(not data.startswith(b"\xef\xbb\xbf"), "JSON_BOM", location)
    verifier.require(data.endswith(b"\n"), "JSON_FINAL_LF", location)
    verifier.require(b"\r" not in data, "JSON_LF_ONLY", location)
    try:
        value = json.loads(
            data.decode("utf-8", "strict"),
            object_pairs_hook=lambda pairs: duplicate_safe_pairs(verifier, pairs),
            parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)),
        )
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"JSON_INVALID: {location}: {error}") from error
    verifier.require(isinstance(value, dict), "JSON_ROOT", location)
    return value


def require_members(
    verifier: Verifier, value: object, expected: set[str], code: str
) -> dict[str, Any]:
    verifier.require(isinstance(value, dict), f"{code}_TYPE", type(value).__name__)
    assert isinstance(value, dict)
    verifier.require(set(value) == expected, code, sorted(value))
    return value


def resolve_file(verifier: Verifier, repo: Path, relative: str) -> Path:
    pure = PurePosixPath(relative)
    verifier.require(not pure.is_absolute(), "PATH_ABSOLUTE", relative)
    verifier.require("\\" not in relative and ".." not in pure.parts, "PATH_UNSAFE", relative)
    path = (repo / Path(*pure.parts)).resolve()
    verifier.require(path.is_relative_to(repo), "PATH_ESCAPE", relative)
    verifier.require(path.is_file(), "PATH_MISSING", relative)
    return path


def read_bound_file(
    verifier: Verifier, repo: Path, relative: str, expected_sha256: object
) -> bytes:
    path = resolve_file(verifier, repo, relative)
    data = path.read_bytes()
    verifier.require(sha256(data) == expected_sha256, "FILE_DIGEST", relative)
    return data


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from(">H", data, offset)[0]


def i16(data: bytes, offset: int) -> int:
    return struct.unpack_from(">h", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from(">I", data, offset)[0]


def parse_sfnt(
    verifier: Verifier, font: bytes, authority_tables: object
) -> dict[str, bytes]:
    verifier.require(len(font) >= 12, "SFNT_HEADER_TRUNCATED", len(font))
    version, count, search_range, entry_selector, range_shift = struct.unpack_from(
        ">IHHHH", font, 0
    )
    verifier.require(version == 0x00010000, "SFNT_VERSION", f"{version:08x}")
    verifier.require(count == len(EXPECTED_TAGS), "SFNT_TABLE_COUNT", count)
    verifier.require((search_range, entry_selector, range_shift) == (128, 3, 80),
                     "SFNT_SEARCH_FIELDS",
                     (search_range, entry_selector, range_shift))
    verifier.require(len(font) >= 12 + count * 16, "SFNT_DIRECTORY_TRUNCATED", len(font))

    records: list[dict[str, object]] = []
    tables: dict[str, bytes] = {}
    cursor = 12 + count * 16
    for index in range(count):
        position = 12 + index * 16
        raw_tag, checksum, offset, length = struct.unpack_from(">4sIII", font, position)
        try:
            tag = raw_tag.decode("ascii", "strict")
        except UnicodeDecodeError as error:
            raise VerificationFailure(f"SFNT_TAG_ASCII: {index}: {error}") from error
        verifier.require(tag not in tables, "SFNT_DUPLICATE_TAG", tag)
        verifier.require(tag == EXPECTED_TAGS[index], "SFNT_TAG_ORDER", tag)
        verifier.require(offset % 4 == 0, "SFNT_TABLE_ALIGNMENT", tag)
        verifier.require(offset == cursor, "SFNT_TABLE_LAYOUT", (tag, offset, cursor))
        verifier.require(length > 0 and offset + length <= len(font),
                         "SFNT_TABLE_BOUNDS", (tag, offset, length))
        table = font[offset:offset + length]
        verifier.require(table_checksum(tag, table) == checksum,
                         "SFNT_TABLE_CHECKSUM", tag)
        end = offset + len(padded(table))
        verifier.require(end <= len(font), "SFNT_TABLE_PAD_BOUNDS", tag)
        verifier.require(font[offset + length:end] == b"\0" * (end - offset - length),
                         "SFNT_TABLE_PADDING", tag)
        tables[tag] = table
        records.append({
            "tag": tag,
            "checksumHex": f"{checksum:08x}",
            "offset": offset,
            "byteLength": length,
        })
        cursor = end
    verifier.require(cursor == len(font), "SFNT_TRAILING_BYTES", (cursor, len(font)))
    verifier.require(records == authority_tables, "SFNT_AUTHORITY_TABLES", records)
    verifier.require(sfnt_checksum(font) == SFNT_CHECKSUM_MAGIC,
                     "SFNT_GLOBAL_CHECKSUM", f"{sfnt_checksum(font):08x}")
    return tables


def parse_names(verifier: Verifier, data: bytes) -> dict[int, str]:
    verifier.require(len(data) >= 6, "NAME_HEADER", len(data))
    format_number, count, string_offset = struct.unpack_from(">HHH", data, 0)
    verifier.require(format_number == 0, "NAME_FORMAT", format_number)
    verifier.require(string_offset == 6 + count * 12, "NAME_STRING_OFFSET", string_offset)
    verifier.require(string_offset <= len(data), "NAME_RECORD_BOUNDS", string_offset)
    result: dict[int, str] = {}
    for index in range(count):
        fields = struct.unpack_from(">HHHHHH", data, 6 + index * 12)
        platform, encoding, language, name_id, length, offset = fields
        verifier.require((platform, encoding, language) == (3, 1, 0x0409),
                         "NAME_RECORD_IDENTITY", fields[:4])
        start = string_offset + offset
        end = start + length
        verifier.require(length % 2 == 0 and end <= len(data),
                         "NAME_STRING_BOUNDS", fields)
        try:
            text = data[start:end].decode("utf-16-be", "strict")
        except UnicodeDecodeError as error:
            raise VerificationFailure(f"NAME_UTF16: {name_id}: {error}") from error
        verifier.require(name_id not in result, "NAME_DUPLICATE_ID", name_id)
        result[name_id] = text
    return result


def cmap_glyph(verifier: Verifier, data: bytes, codepoint: int) -> int:
    verifier.require(len(data) >= 12, "CMAP_HEADER", len(data))
    version, count = struct.unpack_from(">HH", data, 0)
    verifier.require((version, count) == (0, 1), "CMAP_DIRECTORY", (version, count))
    platform, encoding, offset = struct.unpack_from(">HHI", data, 4)
    verifier.require((platform, encoding) == (3, 1), "CMAP_ENCODING", (platform, encoding))
    verifier.require(offset + 14 <= len(data), "CMAP_SUBTABLE_BOUNDS", offset)
    verifier.require(u16(data, offset) == 4, "CMAP_FORMAT", u16(data, offset))
    length = u16(data, offset + 2)
    verifier.require(offset + length <= len(data), "CMAP_FORMAT4_LENGTH", length)
    segment_count = u16(data, offset + 6) // 2
    verifier.require(segment_count == 2, "CMAP_SEGMENTS", segment_count)
    end_codes = offset + 14
    start_codes = end_codes + segment_count * 2 + 2
    deltas = start_codes + segment_count * 2
    range_offsets = deltas + segment_count * 2
    for index in range(segment_count):
        start = u16(data, start_codes + index * 2)
        end = u16(data, end_codes + index * 2)
        if start <= codepoint <= end:
            delta = u16(data, deltas + index * 2)
            range_offset = u16(data, range_offsets + index * 2)
            if range_offset == 0:
                return (codepoint + delta) & 0xFFFF
            word_position = range_offsets + index * 2 + range_offset
            word_position += (codepoint - start) * 2
            verifier.require(word_position + 2 <= offset + length,
                             "CMAP_GLYPH_BOUNDS", word_position)
            glyph = u16(data, word_position)
            return 0 if glyph == 0 else (glyph + delta) & 0xFFFF
    return 0


def verify_glyphs(
    verifier: Verifier, head: bytes, maxp: bytes, loca: bytes, glyf: bytes,
    expected_instruction: bytes,
) -> None:
    verifier.require(len(head) == 54, "HEAD_LENGTH", len(head))
    verifier.require(u32(head, 0) == 0x00010000, "HEAD_VERSION", head[:4].hex())
    verifier.require(u32(head, 12) == 0x5F0F3CF5, "HEAD_MAGIC", head[12:16].hex())
    verifier.require(u16(head, 18) == 1000, "HEAD_UNITS_PER_EM", u16(head, 18))
    verifier.require(i16(head, 50) == 0, "HEAD_LOCA_FORMAT", i16(head, 50))
    verifier.require(u32(head, 8) != 0, "HEAD_CHECKSUM_ADJUSTMENT_ZERO", 0)
    verifier.require(len(maxp) == 32 and u32(maxp, 0) == 0x00010000,
                     "MAXP_SHAPE", len(maxp))
    verifier.require(u16(maxp, 4) == 2, "MAXP_GLYPHS", u16(maxp, 4))
    verifier.require(len(loca) == 6, "LOCA_LENGTH", len(loca))
    offsets = [u16(loca, index * 2) * 2 for index in range(3)]
    verifier.require(offsets == [0, 12, 42], "LOCA_OFFSETS", offsets)
    verifier.require(offsets[-1] == len(glyf), "GLYF_LENGTH", len(glyf))

    notdef = glyf[offsets[0]:offsets[1]]
    verifier.require(len(notdef) == 12 and i16(notdef, 0) == 0,
                     "GLYF_NOTDEF_HEADER", notdef.hex())
    verifier.require(notdef[2:10] == b"\0" * 8 and u16(notdef, 10) == 0,
                     "GLYF_NOTDEF_EMPTY", notdef.hex())

    glyph = glyf[offsets[1]:offsets[2]]
    verifier.require(struct.unpack_from(">hhhhh", glyph, 0) == (1, 0, 0, 500, 700),
                     "GLYF_TRIANGLE_HEADER", glyph[:10].hex())
    verifier.require(u16(glyph, 10) == 2, "GLYF_TRIANGLE_ENDPOINT", u16(glyph, 10))
    instruction_length = u16(glyph, 12)
    instructions = glyph[14:14 + instruction_length]
    verifier.require(instructions == expected_instruction,
                     "GLYF_INSTRUCTIONS", instructions.hex())
    position = 14 + instruction_length
    flags = list(glyph[position:position + 3])
    verifier.require(flags == [1, 1, 1], "GLYF_FLAGS", flags)
    position += 3
    x_deltas = [i16(glyph, position + index * 2) for index in range(3)]
    position += 6
    y_deltas = [i16(glyph, position + index * 2) for index in range(3)]
    position += 6
    verifier.require(position == len(glyph), "GLYF_TRAILING_BYTES", position)
    points: list[tuple[int, int]] = []
    x = y = 0
    for dx, dy in zip(x_deltas, y_deltas, strict=True):
        x += dx
        y += dy
        points.append((x, y))
    verifier.require(points == [(0, 0), (500, 0), (250, 700)],
                     "GLYF_TRIANGLE_POINTS", points)


def verify_license(verifier: Verifier, data: bytes) -> None:
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise VerificationFailure(f"LICENSE_UTF8: {error}") from error
    verifier.require(text.startswith("BSD Zero Clause License\n\n"),
                     "LICENSE_TITLE", text[:32])
    verifier.require(
        "Permission to use, copy, modify, and/or distribute this software "
        "for any purpose with or without fee is hereby granted." in text,
        "LICENSE_PERMISSION", LICENSE_PATH,
    )
    verifier.require("THE SOFTWARE IS PROVIDED \"AS IS\"" in text,
                     "LICENSE_DISCLAIMER", LICENSE_PATH)


def verify_provenance(verifier: Verifier, value: dict[str, Any]) -> dict[str, Any]:
    require_members(verifier, value, {
        "artifactVersion", "fixtureId", "origin", "copyright", "licenseSpdx",
        "thirdPartyFontBytes", "copiedGlyphOutlines", "syntheticDesign",
        "externalInputs", "networkRequiredToGenerate",
    }, "PROVENANCE_MEMBERS")
    verifier.require(
        value["artifactVersion"] ==
        "renderweave-renderer-tricky-font-fixture-provenance/1.0",
        "PROVENANCE_VERSION", value["artifactVersion"],
    )
    verifier.require(value["fixtureId"] == FIXTURE_ID, "PROVENANCE_FIXTURE", value)
    verifier.require(value["licenseSpdx"] == "0BSD", "PROVENANCE_LICENSE", value)
    verifier.require(value["thirdPartyFontBytes"] is False,
                     "PROVENANCE_THIRD_PARTY", value)
    verifier.require(value["copiedGlyphOutlines"] is False,
                     "PROVENANCE_COPIED_OUTLINES", value)
    verifier.require(value["externalInputs"] == [], "PROVENANCE_EXTERNAL_INPUTS", value)
    verifier.require(value["networkRequiredToGenerate"] is False,
                     "PROVENANCE_NETWORK", value)
    design = require_members(verifier, value["syntheticDesign"], {
        "familyName", "glyphs", "bytecodePurpose", "classifierTokenPurpose",
    }, "PROVENANCE_DESIGN_MEMBERS")
    verifier.require(
        isinstance(design["familyName"], str) and bool(design["familyName"]),
        "PROVENANCE_FAMILY", design,
    )
    verifier.require(design["glyphs"] == [
        ".notdef empty glyph", "U+0041 three-point triangle"
    ], "PROVENANCE_GLYPHS", design)
    return design


def verify(repo: Path) -> dict[str, Any]:
    verifier = Verifier()
    authority_path = resolve_file(verifier, repo, AUTHORITY_PATH)
    authority_bytes = authority_path.read_bytes()
    authority = decode_json(verifier, authority_bytes, AUTHORITY_PATH)
    require_members(verifier, authority, {
        "artifactVersion", "status", "fixtureId", "candidateId", "authority",
        "fixture", "classification", "syntheticPrograms", "boundary",
    }, "AUTHORITY_MEMBERS")
    verifier.require(
        authority["artifactVersion"] ==
        "renderweave-renderer-tricky-font-fixture-authority/1.0",
        "AUTHORITY_VERSION", authority["artifactVersion"],
    )
    verifier.require(authority["status"] == "SOURCE_LEVEL_VERIFIED_BUILD_PENDING",
                     "AUTHORITY_STATUS", authority["status"])
    verifier.require(authority["fixtureId"] == FIXTURE_ID,
                     "AUTHORITY_FIXTURE_ID", authority["fixtureId"])
    verifier.require(authority["candidateId"] == CANDIDATE_ID,
                     "AUTHORITY_CANDIDATE_ID", authority["candidateId"])

    source = require_members(verifier, authority["authority"], {
        "kind", "recipePath", "recipeSha256", "licensePath", "licenseSpdx",
        "licenseSha256", "provenancePath", "provenanceSha256",
    }, "SOURCE_AUTHORITY_MEMBERS")
    verifier.require(source["kind"] == "SYNTHETIC_REPOSITORY_OWNED",
                     "SOURCE_AUTHORITY_KIND", source["kind"])
    verifier.require(source["recipePath"] == RECIPE_PATH, "RECIPE_PATH", source)
    verifier.require(source["licensePath"] == LICENSE_PATH, "LICENSE_PATH", source)
    verifier.require(source["licenseSpdx"] == "0BSD", "LICENSE_SPDX", source)
    verifier.require(source["provenancePath"] == PROVENANCE_PATH,
                     "PROVENANCE_PATH", source)
    recipe = read_bound_file(verifier, repo, RECIPE_PATH, source["recipeSha256"])
    license_bytes = read_bound_file(verifier, repo, LICENSE_PATH, source["licenseSha256"])
    provenance_bytes = read_bound_file(
        verifier, repo, PROVENANCE_PATH, source["provenanceSha256"]
    )
    verifier.require(b"urllib" not in recipe and b"requests" not in recipe,
                     "RECIPE_NETWORK_PRIMITIVE", RECIPE_PATH)
    verify_license(verifier, license_bytes)
    provenance = decode_json(verifier, provenance_bytes, PROVENANCE_PATH)
    provenance_design = verify_provenance(verifier, provenance)

    fixture = require_members(verifier, authority["fixture"], {
        "path", "sha256", "byteLength", "sfntVersionHex", "familyName",
        "postScriptName", "glyphCount", "unitsPerEm", "tables",
    }, "FIXTURE_MEMBERS")
    verifier.require(fixture["path"] == FONT_PATH, "FIXTURE_PATH", fixture["path"])
    font = read_bound_file(verifier, repo, FONT_PATH, fixture["sha256"])
    verifier.require(fixture["byteLength"] == len(font), "FIXTURE_LENGTH", len(font))
    verifier.require(fixture["sfntVersionHex"] == "00010000",
                     "FIXTURE_SFNT_VERSION", fixture["sfntVersionHex"])
    verifier.require(
        isinstance(fixture["familyName"], str) and bool(fixture["familyName"]),
        "FIXTURE_FAMILY_AUTHORITY", fixture["familyName"],
    )
    verifier.require(fixture["postScriptName"] == POSTSCRIPT_NAME,
                     "FIXTURE_POSTSCRIPT_AUTHORITY", fixture["postScriptName"])
    verifier.require(fixture["glyphCount"] == 2, "FIXTURE_GLYPH_COUNT", fixture)
    verifier.require(fixture["unitsPerEm"] == 1000, "FIXTURE_UNITS_PER_EM", fixture)
    tables = parse_sfnt(verifier, font, fixture["tables"])

    names = parse_names(verifier, tables["name"])
    verifier.require(names.get(1) == fixture["familyName"],
                     "FONT_FAMILY_NAME", names.get(1))
    verifier.require(names.get(6) == POSTSCRIPT_NAME,
                     "FONT_POSTSCRIPT_NAME", names.get(6))
    verifier.require(provenance_design["familyName"] == fixture["familyName"],
                     "PROVENANCE_FIXTURE_FAMILY", provenance_design["familyName"])
    family = names[1]
    pdf_subset = len(family) > 6 and all("A" <= char <= "Z" for char in family[:6])
    pdf_subset = pdf_subset and family[6] == "+"
    classified_family = family[7:] if pdf_subset else family

    classification = require_members(verifier, authority["classification"], {
        "upstream", "version", "commit", "sourcePath", "sourceSha256", "function",
        "requiredCompileMacro", "path", "matchedToken", "pdfSubsetPrefixApplied",
        "expectedFtIsTricky",
    }, "CLASSIFICATION_MEMBERS")
    verifier.require(classification == {
        "upstream": "FreeType",
        "version": "2.14.3",
        "commit": "0a0221a1347e2f1e07c395263540026e9a0aa7c7",
        "sourcePath": "src/truetype/ttobjs.c",
        "sourceSha256":
        "sha256:c381554e81a00f9d5c430e7c51e1d6c289958867426b021a6165eb12b451922d",
        "function": "tt_check_trickyness_family",
        "requiredCompileMacro": "TT_USE_BYTECODE_INTERPRETER",
        "path": "FAMILY_NAME_SUBSTRING",
        "matchedToken": TRICKY_TOKEN,
        "pdfSubsetPrefixApplied": False,
        "expectedFtIsTricky": True,
    }, "CLASSIFICATION_AUTHORITY", classification)
    verifier.require(pdf_subset is classification["pdfSubsetPrefixApplied"],
                     "CLASSIFICATION_PDF_PREFIX", family)
    verifier.require(TRICKY_TOKEN in classified_family,
                     "CLASSIFICATION_TOKEN_ABSENT", classified_family)

    programs = require_members(verifier, authority["syntheticPrograms"], {
        "cvtHex", "fpgmHex", "prepHex", "glyphInstructionHex",
        "runtimeExecutionClaimed",
    }, "PROGRAM_MEMBERS")
    verifier.require(programs == {
        "cvtHex": "0000", "fpgmHex": "00", "prepHex": "00",
        "glyphInstructionHex": "00", "runtimeExecutionClaimed": False,
    }, "PROGRAM_AUTHORITY", programs)
    verifier.require(tables["cvt "] == bytes.fromhex(programs["cvtHex"]),
                     "CVT_BYTES", tables["cvt "].hex())
    verifier.require(tables["fpgm"] == bytes.fromhex(programs["fpgmHex"]),
                     "FPGM_BYTES", tables["fpgm"].hex())
    verifier.require(tables["prep"] == bytes.fromhex(programs["prepHex"]),
                     "PREP_BYTES", tables["prep"].hex())
    verify_glyphs(
        verifier, tables["head"], tables["maxp"], tables["loca"], tables["glyf"],
        bytes.fromhex(programs["glyphInstructionHex"]),
    )
    verifier.require(cmap_glyph(verifier, tables["cmap"], 0x0041) == 1,
                     "CMAP_U0041", cmap_glyph(verifier, tables["cmap"], 0x0041))

    boundary = require_members(verifier, authority["boundary"], set(BOUNDARY),
                               "BOUNDARY_MEMBERS")
    verifier.require(boundary == BOUNDARY, "BOUNDARY_OVERCLAIM", boundary)

    recipe_result = subprocess.run(
        [sys.executable, str(repo / RECIPE_PATH), "--repo", str(repo), "--check"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    verifier.require(recipe_result.returncode == 0, "RECIPE_REPRODUCIBILITY",
                     recipe_result.stderr.strip() or recipe_result.stdout.strip())

    return {
        "reportVersion": "renderweave-renderer-tricky-font-fixture-gate/1.0",
        "status": "PASS_PORTABLE_TRICKY_FONT_FIXTURE_SOURCE_VERIFIED_BUILD_PENDING",
        "fixtureId": FIXTURE_ID,
        "candidateId": CANDIDATE_ID,
        "checkCount": verifier.check_count,
        "failureCount": 0,
        "reproducible": True,
        "fixture": {
            "path": FONT_PATH,
            "sha256": sha256(font),
            "byteLength": len(font),
            "tableCount": len(tables),
        },
        "classification": {
            "path": "FAMILY_NAME_SUBSTRING",
            "familyName": family,
            "matchedToken": TRICKY_TOKEN,
            "expectedFtIsTricky": True,
        },
        "boundary": BOUNDARY,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        repo = args.repo.resolve()
        report = verify(repo)
        encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.report is None:
            sys.stdout.write(encoded)
        else:
            if args.report.exists():
                raise VerificationFailure(f"REPORT_EXISTS: {args.report}")
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(encoded, encoding="utf-8", newline="\n")
            print(
                "Renderer tricky-font fixture: "
                f"{report['status']} checks={report['checkCount']}"
            )
        return 0
    except (OSError, VerificationFailure, struct.error) as error:
        print(f"Renderer tricky-font fixture failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
