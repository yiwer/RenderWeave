from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from dataclasses import dataclass
from pathlib import Path


FIXTURE_ID = "rw-renderer-portable-tricky-font-v1"
CANDIDATE_ID = "rw-renderer-spike-linux-x86_64-v2-000002"
FAMILY_NAME = "RenderWeave cpop Fixture"
POSTSCRIPT_NAME = "RenderWeave-cpop-Fixture"
FIXTURE_ROOT = Path(
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-fixture-v1"
)
FONT_PATH = FIXTURE_ROOT / "renderweave-cpop-fixture-v1.ttf"
LICENSE_PATH = FIXTURE_ROOT / "LICENSE-0BSD.txt"
PROVENANCE_PATH = FIXTURE_ROOT / "provenance-v1.json"
AUTHORITY_PATH = FIXTURE_ROOT / "authority-v1.json"
RECIPE_PATH = Path("tools/generate-renderer-tricky-font-fixture.py")
SFNT_CHECKSUM_MAGIC = 0xB1B0AFBA


@dataclass(frozen=True)
class TableRecord:
    tag: str
    checksum: int
    offset: int
    length: int


def u16(value: int) -> bytes:
    return struct.pack(">H", value)


def i16(value: int) -> bytes:
    return struct.pack(">h", value)


def u32(value: int) -> bytes:
    return struct.pack(">I", value)


def u64(value: int) -> bytes:
    return struct.pack(">Q", value)


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


def build_head(checksum_adjustment: int) -> bytes:
    return b"".join(
        [
            u32(0x00010000),
            u32(0x00010000),
            u32(checksum_adjustment),
            u32(0x5F0F3CF5),
            u16(0x000B),
            u16(1000),
            u64(0),
            u64(0),
            i16(0),
            i16(0),
            i16(500),
            i16(700),
            u16(0),
            u16(8),
            i16(2),
            i16(0),
            i16(0),
        ]
    )


def build_hhea() -> bytes:
    return b"".join(
        [
            u32(0x00010000),
            i16(800),
            i16(-200),
            i16(0),
            u16(500),
            i16(0),
            i16(0),
            i16(500),
            i16(1),
            i16(0),
            i16(0),
            i16(0),
            i16(0),
            i16(0),
            i16(0),
            i16(0),
            u16(2),
        ]
    )


def build_maxp() -> bytes:
    return b"".join(
        [
            u32(0x00010000),
            u16(2),  # numGlyphs
            u16(3),  # maxPoints
            u16(1),  # maxContours
            u16(0),  # maxCompositePoints
            u16(0),  # maxCompositeContours
            u16(2),  # maxZones
            u16(0),  # maxTwilightPoints
            u16(1),  # maxStorage
            u16(0),  # maxFunctionDefs
            u16(0),  # maxInstructionDefs
            u16(2),  # maxStackElements
            u16(1),  # maxSizeOfInstructions
            u16(0),  # maxComponentElements
            u16(0),  # maxComponentDepth
        ]
    )


def build_glyf_and_loca() -> tuple[bytes, bytes]:
    notdef = b"".join(
        [i16(0), i16(0), i16(0), i16(0), i16(0), u16(0)]
    )
    triangle = b"".join(
        [
            i16(1),
            i16(0),
            i16(0),
            i16(500),
            i16(700),
            u16(2),
            u16(1),
            b"\x00",
            b"\x01\x01\x01",
            i16(0),
            i16(500),
            i16(-250),
            i16(0),
            i16(0),
            i16(700),
        ]
    )
    glyf = notdef + triangle
    loca = u16(0) + u16(len(notdef) // 2) + u16(len(glyf) // 2)
    return glyf, loca


def build_cmap() -> bytes:
    end_codes = u16(0x0041) + u16(0xFFFF)
    start_codes = u16(0x0041) + u16(0xFFFF)
    id_deltas = u16((1 - 0x0041) & 0xFFFF) + u16(1)
    id_range_offsets = u16(0) + u16(0)
    format4 = b"".join(
        [
            u16(4),
            u16(32),
            u16(0),
            u16(4),
            u16(4),
            u16(1),
            u16(0),
            end_codes,
            u16(0),
            start_codes,
            id_deltas,
            id_range_offsets,
        ]
    )
    return u16(0) + u16(1) + u16(3) + u16(1) + u32(12) + format4


def build_name() -> bytes:
    values = {
        0: "Copyright (C) 2026 RenderWeave contributors; 0BSD",
        1: FAMILY_NAME,
        2: "Regular",
        3: "RenderWeave-cpop-Fixture-1.0",
        4: FAMILY_NAME,
        5: "Version 1.0",
        6: POSTSCRIPT_NAME,
    }
    strings = bytearray()
    records = bytearray()
    for name_id, value in values.items():
        encoded = value.encode("utf-16-be")
        records.extend(
            u16(3)
            + u16(1)
            + u16(0x0409)
            + u16(name_id)
            + u16(len(encoded))
            + u16(len(strings))
        )
        strings.extend(encoded)
    string_offset = 6 + len(records)
    return u16(0) + u16(len(values)) + u16(string_offset) + bytes(records) + bytes(strings)


def build_os2() -> bytes:
    panose = bytes([2, 0, 5, 3, 0, 0, 0, 0, 0, 0])
    return b"".join(
        [
            u16(0),
            i16(500),
            u16(400),
            u16(5),
            u16(0),
            i16(650),
            i16(600),
            i16(0),
            i16(75),
            i16(650),
            i16(600),
            i16(0),
            i16(350),
            i16(50),
            i16(250),
            i16(0),
            panose,
            u32(0x00000001),
            u32(0),
            u32(0),
            u32(0),
            b"RWV1",
            u16(0x0040),
            u16(0x0041),
            u16(0x0041),
            i16(800),
            i16(-200),
            i16(0),
            u16(800),
            u16(200),
        ]
    )


def build_post() -> bytes:
    return b"".join(
        [
            u32(0x00030000),
            u32(0),
            i16(-75),
            i16(50),
            u32(0),
            u32(0),
            u32(0),
            u32(0),
            u32(0),
        ]
    )


def build_tables(head: bytes) -> dict[str, bytes]:
    glyf, loca = build_glyf_and_loca()
    return {
        "OS/2": build_os2(),
        "cmap": build_cmap(),
        "cvt ": i16(0),
        "fpgm": b"\x00",
        "glyf": glyf,
        "head": head,
        "hhea": build_hhea(),
        "hmtx": u16(500) + i16(0) + u16(500) + i16(0),
        "loca": loca,
        "maxp": build_maxp(),
        "name": build_name(),
        "post": build_post(),
        "prep": b"\x00",
    }


def assemble_sfnt(tables: dict[str, bytes]) -> tuple[bytes, list[TableRecord]]:
    tags = sorted(tables)
    num_tables = len(tags)
    max_power = 1 << (num_tables.bit_length() - 1)
    search_range = max_power * 16
    entry_selector = max_power.bit_length() - 1
    range_shift = num_tables * 16 - search_range
    header = (
        u32(0x00010000)
        + u16(num_tables)
        + u16(search_range)
        + u16(entry_selector)
        + u16(range_shift)
    )
    offset = 12 + num_tables * 16
    records: list[TableRecord] = []
    body = bytearray()
    directory = bytearray()
    for tag in tags:
        data = tables[tag]
        record = TableRecord(tag, table_checksum(tag, data), offset, len(data))
        records.append(record)
        directory.extend(tag.encode("ascii"))
        directory.extend(u32(record.checksum))
        directory.extend(u32(record.offset))
        directory.extend(u32(record.length))
        block = padded(data)
        body.extend(block)
        offset += len(block)
    return header + bytes(directory) + bytes(body), records


def build_font() -> tuple[bytes, list[TableRecord]]:
    zero_head = build_head(0)
    provisional, records = assemble_sfnt(build_tables(zero_head))
    adjustment = (SFNT_CHECKSUM_MAGIC - sfnt_checksum(provisional)) & 0xFFFFFFFF
    final_head = build_head(adjustment)
    final, final_records = assemble_sfnt(build_tables(final_head))
    if sfnt_checksum(final) != SFNT_CHECKSUM_MAGIC:
        raise AssertionError("invalid final SFNT checksum")
    if records != final_records:
        raise AssertionError("head checksumAdjustment changed directory facts")
    return final, final_records


def authority(repo: Path, font: bytes, records: list[TableRecord]) -> dict[str, object]:
    recipe = (repo / RECIPE_PATH).read_bytes()
    license_bytes = (repo / LICENSE_PATH).read_bytes()
    provenance = (repo / PROVENANCE_PATH).read_bytes()
    return {
        "artifactVersion": "renderweave-renderer-tricky-font-fixture-authority/1.0",
        "status": "SOURCE_LEVEL_VERIFIED_BUILD_PENDING",
        "fixtureId": FIXTURE_ID,
        "candidateId": CANDIDATE_ID,
        "authority": {
            "kind": "SYNTHETIC_REPOSITORY_OWNED",
            "recipePath": RECIPE_PATH.as_posix(),
            "recipeSha256": sha256(recipe),
            "licensePath": LICENSE_PATH.as_posix(),
            "licenseSpdx": "0BSD",
            "licenseSha256": sha256(license_bytes),
            "provenancePath": PROVENANCE_PATH.as_posix(),
            "provenanceSha256": sha256(provenance),
        },
        "fixture": {
            "path": FONT_PATH.as_posix(),
            "sha256": sha256(font),
            "byteLength": len(font),
            "sfntVersionHex": "00010000",
            "familyName": FAMILY_NAME,
            "postScriptName": POSTSCRIPT_NAME,
            "glyphCount": 2,
            "unitsPerEm": 1000,
            "tables": [
                {
                    "tag": record.tag,
                    "checksumHex": f"{record.checksum:08x}",
                    "offset": record.offset,
                    "byteLength": record.length,
                }
                for record in records
            ],
        },
        "classification": {
            "upstream": "FreeType",
            "version": "2.14.3",
            "commit": "0a0221a1347e2f1e07c395263540026e9a0aa7c7",
            "sourcePath": "src/truetype/ttobjs.c",
            "sourceSha256": "sha256:c381554e81a00f9d5c430e7c51e1d6c289958867426b021a6165eb12b451922d",
            "function": "tt_check_trickyness_family",
            "requiredCompileMacro": "TT_USE_BYTECODE_INTERPRETER",
            "path": "FAMILY_NAME_SUBSTRING",
            "matchedToken": "cpop",
            "pdfSubsetPrefixApplied": False,
            "expectedFtIsTricky": True,
        },
        "syntheticPrograms": {
            "cvtHex": "0000",
            "fpgmHex": "00",
            "prepHex": "00",
            "glyphInstructionHex": "00",
            "runtimeExecutionClaimed": False,
        },
        "boundary": {
            "exactBuiltTargetObserved": False,
            "runtimeBytecodeNonExecutionProven": False,
            "noHintingVersusNoAutoHintDistinguished": False,
            "physicalLinuxReplayComplete": False,
            "rendererExactOutputRecordIssuanceAllowed": False,
            "certified": False,
            "ready": False,
            "ticket19MayClose": False,
        },
    }


def encode_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate or check the deterministic RenderWeave tricky-font fixture."
    )
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo = args.repo.resolve()
    font, records = build_font()
    authority_bytes = encode_json(authority(repo, font, records))
    font_path = repo / FONT_PATH
    authority_path = repo / AUTHORITY_PATH

    if args.write:
        font_path.parent.mkdir(parents=True, exist_ok=True)
        font_path.write_bytes(font)
        authority_path.write_bytes(authority_bytes)
        print(f"wrote {FONT_PATH.as_posix()} {sha256(font)} bytes={len(font)}")
        return 0

    errors: list[str] = []
    if not font_path.is_file() or font_path.read_bytes() != font:
        errors.append(f"{FONT_PATH.as_posix()} is absent or not byte-identical")
    if not authority_path.is_file() or authority_path.read_bytes() != authority_bytes:
        errors.append(f"{AUTHORITY_PATH.as_posix()} is absent or not byte-identical")
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(f"reproduced {sha256(font)} bytes={len(font)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
