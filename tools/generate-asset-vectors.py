#!/usr/bin/env python3
"""One-time generator for the frozen Asset acceptance kernel vector manifest.

Produces renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/
acceptance-kernel-v1/vectors.json from the deterministic fixtures plus byte-level
patches. The manifest is frozen afterwards: the gate only ever replays it.

Run: python tools/generate-asset-vectors.py <fixtures-dir> <manifest-path>
"""
import base64
import binascii
import io
import json
import struct
import sys
import zlib
from pathlib import Path

from PIL import Image

IMAGE_LIMIT = 64 * 1024 * 1024
FONT_LIMIT = 32 * 1024 * 1024
MAX_EDGE = 20_000
MAX_TOTAL = 100_000_000
PROFILE = "renderweave-asset-acceptance/1.0"


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from(">H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from(">I", data, offset)[0]


def write_u16(target: bytearray, offset: int, value: int) -> None:
    struct.pack_into(">H", target, offset, value)


def write_u32(target: bytearray, offset: int, value: int) -> None:
    struct.pack_into(">I", target, offset, value)


# ---------------- PNG builders (port of the Java fixture builder) ----------------

PNG_SIGNATURE = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])


def png(*chunks: bytes) -> bytes:
    out = bytearray(PNG_SIGNATURE)
    for chunk in chunks:
        out += struct.pack(">I", len(chunk) - 4)
        out += chunk
        out += struct.pack(">I", binascii.crc32(chunk) & 0xFFFFFFFF)
    return bytes(out)


def chunk(four_cc: str, data: bytes) -> bytes:
    return four_cc.encode("ascii") + data


def ihdr(width: int, height: int, depth: int, color: int, interlace: int = 0) -> bytes:
    return struct.pack(">IIBBBBB", width, height, depth, color, 0, 0, interlace)


def idat(scanline: bytes) -> bytes:
    return chunk("IDAT", zlib.compress(scanline))


def iccp(name: str, compressed: bytes) -> bytes:
    return chunk("iCCP", name.encode("latin1") + b"\x00\x00" + compressed)


def exif_tiff(orientation: int) -> bytes:
    data = bytearray(26)
    data[0:2] = b"MM"
    write_u16(data, 2, 0x002A)
    write_u32(data, 4, 8)
    write_u16(data, 8, 1)
    write_u16(data, 10, 0x0112)
    write_u16(data, 12, 0x0003)
    write_u32(data, 14, 1)
    write_u16(data, 18, orientation)
    return bytes(data)


def minimal_png(width=1, height=1, depth=8, color=6, scanline=None, extra=None) -> bytes:
    if scanline is None:
        scanline = bytes([0x00] + [0x12, 0x34, 0x56, 0x78][: (3 + (color in (4, 6)))])
    chunks = [chunk("IHDR", ihdr(width, height, depth, color)), idat(scanline)]
    if extra:
        chunks = [chunks[0]] + extra + [chunks[1]]
    chunks.append(chunk("IEND", b""))
    return png(*chunks)


# ---------------- JPEG patching ----------------

def find_marker(data: bytes, marker: int, start: int = 0) -> int:
    for i in range(start, len(data) - 1):
        if data[i] == 0xFF and data[i + 1] == marker:
            return i
    raise AssertionError(f"marker {marker:02x} not found")


def insert_segment(data: bytes, marker: int, payload: bytes) -> bytes:
    length = 2 + len(payload)
    return (
        data[:2]
        + bytes([0xFF, marker, (length >> 8) & 0xFF, length & 0xFF])
        + payload
        + data[2:]
    )


def insert_app1(data: bytes, tiff: bytes) -> bytes:
    payload = b"Exif\x00\x00" + tiff
    length = 2 + len(payload)
    return (
        data[:2]
        + bytes([0xFF, 0xE1, (length >> 8) & 0xFF, length & 0xFF])
        + payload
        + data[2:]
    )


# ---------------- WebP patching ----------------

def find_image_chunk(data: bytes) -> int:
    pos = 12
    while pos + 8 <= len(data):
        if data[pos : pos + 4] in (b"VP8 ", b"VP8L"):
            return pos
        size = struct.unpack_from("<I", data, pos + 4)[0]
        pos += 8 + size + (size % 2)
    raise AssertionError("image chunk not found")


def insert_webp_chunk(data: bytes, four_cc: str, payload: bytes) -> bytes:
    image = find_image_chunk(data)
    body = data[8:image]
    body += four_cc.encode("ascii") + struct.pack("<I", len(payload)) + payload
    if len(payload) % 2:
        body += b"\x00"
    body += data[image:]
    return data[:4] + struct.pack("<I", len(body)) + body


def vp8x_chunk(flags: int, width: int, height: int) -> bytes:
    data = bytearray(10)
    data[0] = flags
    data[4:7] = (width - 1).to_bytes(3, "little")
    data[7:10] = (height - 1).to_bytes(3, "little")
    return bytes(data)


def insert_webp_vp8x_and_chunk(data: bytes, four_cc: str, payload: bytes, flags: int) -> bytes:
    image = find_image_chunk(data)
    body = data[8:image]
    body += b"VP8X" + struct.pack("<I", 10) + vp8x_chunk(flags, 2, 3)
    body += four_cc.encode("ascii") + struct.pack("<I", len(payload)) + payload
    if len(payload) % 2:
        body += b"\x00"
    body += data[image:]
    return data[:4] + struct.pack("<I", len(body)) + body


def insert_webp_vp8x_and_exif(data: bytes, tiff: bytes) -> bytes:
    image = find_image_chunk(data)
    body = data[8:image]
    body += b"VP8X" + struct.pack("<I", 10) + vp8x_chunk(0x08, 2, 3)
    body += b"EXIF" + struct.pack("<I", len(tiff)) + tiff + (b"\x00" if len(tiff) % 2 else b"")
    body += data[image:]
    return data[:4] + struct.pack("<I", len(body)) + body


# ---------------- sfnt patching (checksum-fixing) ----------------

def table_checksum(data: bytes, offset: int, length: int) -> int:
    total = 0
    for i in range(0, length, 4):
        remaining = min(4, length - i)
        chunk = data[offset + i : offset + i + remaining]
        chunk = chunk + b"\x00" * (4 - len(chunk))
        total = (total + struct.unpack(">I", chunk)[0]) & 0xFFFFFFFF
    return total


def directory_index(data: bytes, tag: str) -> int:
    count = u16(data, 4)
    for i in range(count):
        base = 12 + i * 16
        if data[base : base + 4] == tag.encode("ascii"):
            return i
    raise AssertionError(f"table {tag} not found")


def patch_and_fix(data: bytes, tag: str, relative: int, replacement: bytes) -> bytes:
    raw = bytearray(data)
    record = 12 + directory_index(raw, tag) * 16
    table_offset = u32(raw, record + 8)
    table_length = u32(raw, record + 12)
    raw[table_offset + relative : table_offset + relative + len(replacement)] = replacement
    write_u32(raw, record + 4, table_checksum(raw, table_offset, table_length))
    head_record = 12 + directory_index(raw, "head") * 16
    head_offset = u32(raw, head_record + 8)
    write_u32(raw, head_offset + 8, 0)
    write_u32(raw, head_record + 4, table_checksum(raw, head_offset, u32(raw, head_record + 12)))
    adjustment = (0xB1B0AFBA - table_checksum(raw, 0, len(raw))) & 0xFFFFFFFF
    write_u32(raw, head_offset + 8, adjustment)
    return bytes(raw)


# ---------------- case assembly ----------------

def image_admitted(case_id: str, raw: bytes, width: int, height: int, orientation: str, logical_w: int, logical_h: int) -> dict:
    return {
        "id": case_id,
        "assetKind": "IMAGE",
        "input": {"kind": "BASE64", "data": base64.b64encode(raw).decode("ascii")},
        "expected": {
            "outcome": "ADMITTED",
            "kind": "IMAGE",
            "byteLength": len(raw),
            "sha256": __import__("hashlib").sha256(raw).hexdigest(),
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
        },
    }


def font_admitted(case_id: str, raw: bytes, flavor: str, units_per_em: int) -> dict:
    return {
        "id": case_id,
        "assetKind": "FONT",
        "input": {"kind": "BASE64", "data": base64.b64encode(raw).decode("ascii")},
        "expected": {
            "outcome": "ADMITTED",
            "kind": "FONT",
            "byteLength": len(raw),
            "sha256": __import__("hashlib").sha256(raw).hexdigest(),
            "acceptanceProfileId": PROFILE,
            "descriptor": {"type": "FONT", "faceIndex": 0, "flavor": flavor, "unitsPerEm": units_per_em},
        },
    }


def rejected(case_id: str, raw: bytes, code: str, stage: str, pointer: str, limit: str | None = None) -> dict:
    case = {
        "id": case_id,
        "assetKind": "FONT" if case_id.startswith("font-") else "IMAGE",
        "input": {"kind": "BASE64", "data": base64.b64encode(raw).decode("ascii")},
        "expected": {"outcome": "REJECTED", "code": code, "stage": stage, "pointer": pointer, "limit": limit},
    }
    return case


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    fixtures = Path(sys.argv[1])
    manifest_path = Path(sys.argv[2])
    canonical = Path(sys.argv[3]).read_bytes()

    def load(name: str) -> bytes:
        return (fixtures / name).read_bytes()

    cases: list[dict] = []

    # PNG
    cases.append(image_admitted("png-rgba-admitted", minimal_png(), 1, 1, "IDENTITY", 1, 1))
    gray = minimal_png(1, 1, 8, 0, scanline=b"\x00\x40")
    cases.append(image_admitted("png-gray-admitted", gray, 1, 1, "IDENTITY", 1, 1))
    indexed = minimal_png(1, 1, 8, 3, scanline=b"\x00\x00", extra=[chunk("PLTE", b"\x10\x20\x30")])
    cases.append(image_admitted("png-indexed-admitted", indexed, 1, 1, "IDENTITY", 1, 1))
    srgb = minimal_png(extra=[chunk("sRGB", b"\x00")])
    cases.append(image_admitted("png-srgb-admitted", srgb, 1, 1, "IDENTITY", 1, 1))
    exif_png = minimal_png(
        2, 1, 8, 6, scanline=b"\x00" + bytes(range(8)), extra=[chunk("eXIf", exif_tiff(6))]
    )
    cases.append(image_admitted("png-exif-orientation-admitted", exif_png, 2, 1, "ROTATE_90_CW", 1, 2))
    cases.append(rejected("png-16bit-unsupported", minimal_png(1, 1, 16, 6), "ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/ihdr"))
    apng = minimal_png(extra=[chunk("acTL", b"\x00\x00\x00\x01\x00\x00\x00\x00")])
    cases.append(rejected("png-apng-unsupported", apng, "ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/acTL"))
    bad_crc = bytearray(minimal_png())
    bad_crc[16] ^= 0x01
    cases.append(rejected("png-bad-crc-invalid", bytes(bad_crc), "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/IHDR"))
    corrupt_idat = minimal_png(scanline=b"\x00")
    cases.append(rejected("png-corrupt-idat-invalid", corrupt_idat, "ASSET_CONTENT_INVALID", "ASSET_DECODE", "/IDAT"))
    edge = minimal_png(20001, 1, 8, 6, scanline=b"\x00")
    cases.append(rejected("png-edge-limit", edge, "ASSET_CONTENT_LIMIT_EXCEEDED", "ASSET_STRUCTURE", "/ihdr", "assetAcceptance.imageEdgePixels"))
    dense = minimal_png(10001, 10001, 8, 6, scanline=b"\x00")
    cases.append(rejected("png-total-limit", dense, "ASSET_CONTENT_LIMIT_EXCEEDED", "ASSET_STRUCTURE", "/ihdr", "assetAcceptance.imageTotalPixels"))
    iccp_png = minimal_png(extra=[iccp("profile", zlib.compress(b"\x01\x02\x03"))])
    cases.append(rejected("png-iccp-unsupported", iccp_png, "ASSET_CONTENT_UNSUPPORTED", "ASSET_DESCRIPTOR", "/iCCP"))
    conflict = minimal_png(extra=[chunk("sRGB", b"\x00"), iccp("profile", zlib.compress(b"\x01\x02\x03"))])
    cases.append(rejected("png-srgb-iccp-conflict-unsupported", conflict, "ASSET_CONTENT_UNSUPPORTED", "ASSET_DESCRIPTOR", "/iCCP"))
    no_plte = minimal_png(1, 1, 8, 3, scanline=b"\x00\x00")
    cases.append(rejected("png-indexed-no-plte-invalid", no_plte, "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/PLTE"))
    canonical_png = minimal_png(extra=[iccp("sRGB", zlib.compress(canonical))])
    cases.append(image_admitted("png-canonical-icc-admitted", canonical_png, 1, 1, "IDENTITY", 1, 1))

    # JPEG
    cases.append(image_admitted("jpeg-gray-admitted", load("grayscale-baseline.jpg"), 2, 3, "IDENTITY", 2, 3))
    cases.append(image_admitted("jpeg-progressive-admitted", load("ycbcr-progressive.jpg"), 2, 3, "IDENTITY", 2, 3))
    cases.append(rejected("jpeg-cmyk-unsupported", load("cmyk.jpg"), "ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/sof"))
    cases.append(rejected("jpeg-icc-unsupported", load("icc-profile.jpg"), "ASSET_CONTENT_UNSUPPORTED", "ASSET_DESCRIPTOR", "/ICC"))
    precision12 = bytearray(load("grayscale-baseline.jpg"))
    precision12[find_marker(precision12, 0xC0) + 4] = 12
    cases.append(rejected("jpeg-precision12-unsupported", bytes(precision12), "ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/sof"))
    dac = insert_segment(load("grayscale-baseline.jpg"), 0xCC, b"\x00\x00")
    cases.append(rejected("jpeg-dac-unsupported", dac, "ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/dac"))
    cases.append(rejected("jpeg-truncated-invalid", load("grayscale-baseline.jpg")[:20], "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    exif_jpeg = insert_app1(load("grayscale-baseline.jpg"), exif_tiff(6))
    cases.append(image_admitted("jpeg-exif-admitted", exif_jpeg, 2, 3, "ROTATE_90_CW", 3, 2))
    canonical_jpeg_buffer = io.BytesIO()
    Image.new("RGB", (2, 3), color=(10, 20, 30)).save(
        canonical_jpeg_buffer, "JPEG", quality=90, icc_profile=canonical
    )
    cases.append(
        image_admitted("jpeg-canonical-icc-admitted", canonical_jpeg_buffer.getvalue(), 2, 3, "IDENTITY", 2, 3)
    )

    # WebP
    cases.append(image_admitted("webp-lossy-admitted", load("lossy.webp"), 2, 3, "IDENTITY", 2, 3))
    cases.append(image_admitted("webp-lossless-admitted", load("lossless.webp"), 2, 3, "IDENTITY", 2, 3))
    cases.append(rejected("webp-animated-unsupported", load("animated.webp"), "ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/ANIM"))
    webp_iccp = insert_webp_chunk(load("lossy.webp"), "ICCP", b"\x01\x02\x03")
    cases.append(rejected("webp-iccp-unsupported", webp_iccp, "ASSET_CONTENT_UNSUPPORTED", "ASSET_DESCRIPTOR", "/ICCP"))
    webp_junk = insert_webp_chunk(load("lossy.webp"), "JUNK", b"\x00\x00")
    cases.append(rejected("webp-unknown-fourcc-invalid", webp_junk, "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/JUNK"))
    bad_magic = bytearray(load("lossy.webp"))
    bad_magic[11] = ord("C")
    cases.append(rejected("webp-magic-invalid", bytes(bad_magic), "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    cases.append(rejected("webp-truncated-invalid", load("lossy.webp")[:20], "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    webp_exif = insert_webp_vp8x_and_exif(load("lossy.webp"), exif_tiff(6))
    cases.append(image_admitted("webp-exif-admitted", webp_exif, 2, 3, "ROTATE_90_CW", 3, 2))
    webp_canonical = insert_webp_vp8x_and_chunk(load("lossy.webp"), "ICCP", canonical, 0x20)
    cases.append(image_admitted("webp-canonical-icc-admitted", webp_canonical, 2, 3, "IDENTITY", 2, 3))

    # FONT
    cases.append(font_admitted("font-ttf-admitted", load("minimal-ttf.ttf"), "TRUETYPE_GLYF", 1000))
    cases.append(font_admitted("font-otf-admitted", load("minimal-otf.otf"), "CFF", 1000))
    woff = bytearray(load("minimal-ttf.ttf"))
    woff[0:4] = b"wOFF"
    cases.append(rejected("font-woff-magic-invalid", bytes(woff), "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    ttcf = bytearray(load("minimal-ttf.ttf"))
    ttcf[0:4] = b"ttcf"
    cases.append(rejected("font-ttcf-magic-invalid", bytes(ttcf), "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/"))
    fvar = bytearray(load("minimal-ttf.ttf"))
    record = 12 + directory_index(fvar, "post") * 16
    fvar[record : record + 4] = b"fvar"
    cases.append(rejected("font-fvar-unsupported", bytes(fvar), "ASSET_CONTENT_UNSUPPORTED", "ASSET_STRUCTURE", "/tables"))
    corrupt_glyf = bytearray(load("minimal-ttf.ttf"))
    glyf_record = 12 + directory_index(corrupt_glyf, "glyf") * 16
    corrupt_glyf[u32(corrupt_glyf, glyf_record + 8) + 20] ^= 0x01
    cases.append(rejected("font-glyf-checksum-invalid", bytes(corrupt_glyf), "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/glyf"))
    bad_upem = patch_and_fix(load("minimal-ttf.ttf"), "head", 18, b"\x40\x01")
    cases.append(rejected("font-unitsperem-invalid", bad_upem, "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/head"))
    bad_cff = patch_and_fix(load("minimal-otf.otf"), "maxp", 4, b"\x00\x03")
    cases.append(rejected("font-cff-charstrings-invalid", bad_cff, "ASSET_CONTENT_INVALID", "ASSET_STRUCTURE", "/CFF "))

    manifest = {
        "vectorVersion": "renderweave-asset-acceptance-kernel-v1/1",
        "authorityContext": {
            "acceptanceProfile": PROFILE,
            "profileAvailability": "NOT_REGISTERED",
            "imageRawByteLimit": IMAGE_LIMIT,
            "fontRawByteLimit": FONT_LIMIT,
            "imageEdgePixelLimit": MAX_EDGE,
            "imageTotalPixelLimit": MAX_TOTAL,
        },
        "cases": cases,
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"wrote {len(cases)} cases to {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
