#!/usr/bin/env python3
"""Independent standard-library replay of surface preflight and exact PNG vectors."""

from __future__ import annotations

import argparse
import binascii
import hashlib
import json
import struct
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class VerificationFailure(ValueError):
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


def parse_decimal6(raw: Any, positive: bool) -> int:
    if not isinstance(raw, str) or not raw:
        raise VerificationFailure("surface decimal must be nonempty text")
    if raw.startswith(("+", "-")) or raw.startswith(".") or raw.endswith("."):
        raise VerificationFailure("surface decimal is not canonical nonnegative plain notation")
    if raw.count(".") > 1 or any(character not in "0123456789." for character in raw):
        raise VerificationFailure("surface decimal contains an invalid character")
    integer, separator, fraction = raw.partition(".")
    if len(integer) > 1 and integer.startswith("0"):
        raise VerificationFailure("surface decimal has a leading zero")
    if len(fraction) > 6 or (separator and fraction.endswith("0")):
        raise VerificationFailure("surface decimal has noncanonical precision")
    scaled = int(integer) * 1_000_000
    if separator:
        scaled += int(fraction) * 10 ** (6 - len(fraction))
    if positive and scaled == 0:
        raise VerificationFailure("trim dimension must be positive")
    return scaled


LIMITS = {
    "dpi": 600,
    "surfaceEdgePixels": 16_384,
    "surfacePixels": 50_000_000,
    "rgba8SurfaceBytes": 200_000_000,
    "encoderScratchBytes": 67_108_864,
    "encodedImageBytes": 536_870_912,
    "storedDeflateBlockBytes": 65_535,
    "idatPayloadBytes": 1_048_576,
}


def rejected(limit_id: str, code: str = "OUTPUT_BUDGET_EXCEEDED") -> dict[str, Any]:
    return {
        "status": "REJECTED",
        "code": code,
        "stage": "OUTPUT_PREFLIGHT",
        "limitId": limit_id,
    }


def preflight(case: dict[str, Any]) -> dict[str, Any]:
    dpi = case["dpi"]
    if type(dpi) is not int or not 1 <= dpi <= LIMITS["dpi"]:
        return rejected("rendererSurfaceAndOutput.dpi")
    bleed = exact_members(case["bleedPt"], {"top", "right", "bottom", "left"}, "bleedPt")
    width = parse_decimal6(case["widthPt"], True)
    height = parse_decimal6(case["heightPt"], True)
    left = parse_decimal6(bleed["left"], False)
    right = parse_decimal6(bleed["right"], False)
    top = parse_decimal6(bleed["top"], False)
    bottom = parse_decimal6(bleed["bottom"], False)
    denominator = 72_000_000
    width_px = ((width + left + right) * dpi + denominator // 2) // denominator
    height_px = ((height + top + bottom) * dpi + denominator // 2) // denominator
    if width_px == 0 or height_px == 0:
        return rejected("rendererSurfaceAndOutput.surfacePixels")
    if width_px > LIMITS["surfaceEdgePixels"] or height_px > LIMITS["surfaceEdgePixels"]:
        return rejected("rendererSurfaceAndOutput.surfaceEdgePixels")
    pixels = width_px * height_px
    if pixels > LIMITS["surfacePixels"]:
        return rejected("rendererSurfaceAndOutput.surfacePixels")
    rgba8_bytes = pixels * 4
    if rgba8_bytes > LIMITS["rgba8SurfaceBytes"]:
        return rejected("rendererSurfaceAndOutput.rgba8SurfaceBytes", "RASTER_BUDGET_EXCEEDED")
    raw_scanline_bytes = height_px * (1 + width_px * 4)
    stored_blocks = (raw_scanline_bytes + LIMITS["storedDeflateBlockBytes"] - 1) // LIMITS["storedDeflateBlockBytes"]
    zlib_bytes = 2 + raw_scanline_bytes + stored_blocks * 5 + 4
    idat_chunks = (zlib_bytes + LIMITS["idatPayloadBytes"] - 1) // LIMITS["idatPayloadBytes"]
    png_encoded_bytes = 79 + zlib_bytes + idat_chunks * 12
    if png_encoded_bytes > LIMITS["encodedImageBytes"]:
        return rejected("rendererSurfaceAndOutput.encodedImageBytes")
    return {
        "status": "ADMITTED",
        "widthPx": width_px,
        "heightPx": height_px,
        "rgba8Bytes": rgba8_bytes,
        "pngEncodedBytes": png_encoded_bytes,
    }


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFF_FFFF)
    )


def expected_pixels(case: dict[str, Any]) -> bytes:
    fixture = exact_members(case["pixels"], {"kind", "hex"}, f"{case['id']} pixels")
    try:
        seed = bytes.fromhex(fixture["hex"])
    except (TypeError, ValueError) as error:
        raise VerificationFailure(f"{case['id']} has invalid pixel hex") from error
    if fixture["kind"] == "EXACT_HEX":
        pixels = seed
    elif fixture["kind"] == "SOLID_RGBA":
        if len(seed) != 4:
            raise VerificationFailure(f"{case['id']} solid pixel is not RGBA8")
        pixels = seed * (case["widthPx"] * case["heightPx"])
    else:
        raise VerificationFailure(f"{case['id']} has unknown pixel fixture kind")
    expected_length = case["widthPx"] * case["heightPx"] * 4
    if len(pixels) != expected_length:
        raise VerificationFailure(f"{case['id']} pixel byte length drifted")
    if any(pixels[index + 3] == 0 and pixels[index : index + 3] != b"\0\0\0"
           for index in range(0, len(pixels), 4)):
        raise VerificationFailure(f"{case['id']} has noncanonical transparent RGB")
    return pixels


def encode_png(case: dict[str, Any], pixels: bytes) -> tuple[bytes, bytes, list[int], list[int]]:
    width = case["widthPx"]
    height = case["heightPx"]
    dpi = case["dpi"]
    if not (1 <= width <= LIMITS["surfaceEdgePixels"]
            and 1 <= height <= LIMITS["surfaceEdgePixels"]
            and 1 <= dpi <= LIMITS["dpi"]):
        raise VerificationFailure(f"{case['id']} PNG dimensions are outside the kernel contract")
    row_bytes = width * 4
    filtered = b"".join(
        b"\0" + pixels[row * row_bytes : (row + 1) * row_bytes]
        for row in range(height)
    )
    zstream = bytearray(b"\x78\x01")
    stored_lengths: list[int] = []
    offset = 0
    while offset < len(filtered):
        length = min(LIMITS["storedDeflateBlockBytes"], len(filtered) - offset)
        final = offset + length == len(filtered)
        zstream.append(1 if final else 0)
        zstream += struct.pack("<HH", length, length ^ 0xFFFF)
        zstream += filtered[offset : offset + length]
        stored_lengths.append(length)
        offset += length
    zstream += struct.pack(">I", zlib.adler32(filtered) & 0xFFFF_FFFF)
    idat_payloads = [
        bytes(zstream[offset : offset + LIMITS["idatPayloadBytes"]])
        for offset in range(0, len(zstream), LIMITS["idatPayloadBytes"])
    ]
    pixels_per_meter = (dpi * 5_000 + 63) // 127
    encoded = b"\x89PNG\r\n\x1a\n"
    encoded += png_chunk("IHDR".encode("ascii"), struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    encoded += png_chunk("sRGB".encode("ascii"), b"\0")
    encoded += png_chunk("pHYs".encode("ascii"), struct.pack(">IIB", pixels_per_meter, pixels_per_meter, 1))
    encoded += b"".join(png_chunk(b"IDAT", payload) for payload in idat_payloads)
    encoded += png_chunk(b"IEND", b"")
    return encoded, filtered, stored_lengths, [len(payload) for payload in idat_payloads]


def inspect_png(encoded: bytes) -> tuple[list[str], int, bytes]:
    if not encoded.startswith(b"\x89PNG\r\n\x1a\n"):
        raise VerificationFailure("PNG signature drifted")
    offset = 8
    chunk_types: list[str] = []
    pixels_per_meter = -1
    idat = bytearray()
    while offset < len(encoded):
        if offset + 12 > len(encoded):
            raise VerificationFailure("truncated PNG chunk")
        length = struct.unpack(">I", encoded[offset : offset + 4])[0]
        end = offset + 12 + length
        if end > len(encoded):
            raise VerificationFailure("PNG chunk length exceeds bytes")
        kind = encoded[offset + 4 : offset + 8]
        payload = encoded[offset + 8 : offset + 8 + length]
        crc = struct.unpack(">I", encoded[offset + 8 + length : end])[0]
        if crc != binascii.crc32(kind + payload) & 0xFFFF_FFFF:
            raise VerificationFailure("PNG CRC drifted")
        name = kind.decode("ascii")
        chunk_types.append(name)
        if name == "pHYs":
            x, y, unit = struct.unpack(">IIB", payload)
            if x != y or unit != 1:
                raise VerificationFailure("pHYs contract drifted")
            pixels_per_meter = x
        elif name == "IDAT":
            idat += payload
        offset = end
    if offset != len(encoded) or pixels_per_meter < 0:
        raise VerificationFailure("PNG framing drifted")
    return chunk_types, pixels_per_meter, bytes(idat)


def parse_stored_stream(zstream: bytes) -> tuple[list[int], bytes]:
    if zstream[:2] != b"\x78\x01" or len(zstream) < 11:
        raise VerificationFailure("zlib header or length drifted")
    offset = 2
    blocks: list[int] = []
    raw = bytearray()
    while True:
        header = zstream[offset]
        offset += 1
        if header not in (0, 1):
            raise VerificationFailure("DEFLATE stored block header drifted")
        length, complement = struct.unpack("<HH", zstream[offset : offset + 4])
        offset += 4
        if length ^ 0xFFFF != complement:
            raise VerificationFailure("DEFLATE LEN/NLEN drifted")
        raw += zstream[offset : offset + length]
        offset += length
        blocks.append(length)
        if header == 1:
            break
    if offset + 4 != len(zstream):
        raise VerificationFailure("zlib stored stream has trailing or missing bytes")
    expected_adler = struct.unpack(">I", zstream[offset:])[0]
    if expected_adler != zlib.adler32(raw) & 0xFFFF_FFFF:
        raise VerificationFailure("Adler-32 drifted")
    return blocks, bytes(raw)


def verify(vectors_path: Path) -> dict[str, Any]:
    raw = vectors_path.read_bytes()
    try:
        vectors = json.loads(raw, object_pairs_hook=strict_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"vectors are not strict UTF-8 JSON: {error}") from error
    reject_null(vectors)
    verifier = Verifier()
    exact_members(
        vectors,
        {"manifestVersion", "outputProfile", "inputContract", "limits", "surfaceCases", "pngCases", "boundary"},
        "vector manifest",
    )
    verifier.require(vectors["manifestVersion"] == "renderweave-output-png-vectors/1.0", "manifest version drifted")
    verifier.require(vectors["outputProfile"] == "renderweave-output-png/1.0", "output Profile drifted")
    verifier.require(vectors["inputContract"] == "canonical-straight-rgba8-row-major", "input contract drifted")
    verifier.require(vectors["limits"] == LIMITS, "frozen limit set drifted")
    verifier.require(len(vectors["surfaceCases"]) == 10, "surface case count drifted")
    verifier.require(len(vectors["pngCases"]) == 6, "PNG case count drifted")
    boundary = exact_members(
        vectors["boundary"],
        {"profileAvailability", "certificationStatus", "rasterImplementation", "daemonOutputPath", "physicalHostCertification", "providerAttempts"},
        "boundary",
    )
    verifier.require(boundary == {
        "profileAvailability": "NOT_REGISTERED",
        "certificationStatus": "NOT_CERTIFIED",
        "rasterImplementation": "ABSENT",
        "daemonOutputPath": "UNWIRED",
        "physicalHostCertification": False,
        "providerAttempts": 0,
    }, "honest boundary drifted")

    seen: set[str] = set()
    for case in vectors["surfaceCases"]:
        exact_members(case, {"id", "widthPt", "heightPt", "bleedPt", "dpi", "expected"}, "surface case")
        verifier.require(isinstance(case["id"], str) and case["id"] not in seen, "duplicate surface case id")
        seen.add(case["id"])
        actual = preflight(case)
        verifier.require(actual == case["expected"], f"{case['id']}: surface result drifted")

    for case in vectors["pngCases"]:
        exact_members(case, {"id", "widthPx", "heightPx", "dpi", "pixels", "expected"}, "PNG case")
        verifier.require(isinstance(case["id"], str) and case["id"] not in seen, "duplicate PNG case id")
        seen.add(case["id"])
        expected = case["expected"]
        required_expected = {"byteLength", "sha256", "pixelsPerMeter", "chunkTypes", "storedBlockLengths", "idatPayloadLengths"}
        verifier.require(set(expected) in (required_expected, required_expected | {"exactHex"}), f"{case['id']}: expected member set drifted")
        pixels = expected_pixels(case)
        encoded, filtered, generated_blocks, generated_idat_lengths = encode_png(case, pixels)
        verifier.require(len(encoded) == expected["byteLength"], f"{case['id']}: encoded length drifted")
        digest = "sha256:" + hashlib.sha256(encoded).hexdigest()
        verifier.require(digest == expected["sha256"], f"{case['id']}: encoded SHA-256 drifted")
        if "exactHex" in expected:
            verifier.require(encoded.hex() == expected["exactHex"], f"{case['id']}: exact bytes drifted")
        chunk_types, pixels_per_meter, zstream = inspect_png(encoded)
        verifier.require(chunk_types == expected["chunkTypes"], f"{case['id']}: chunk order drifted")
        verifier.require(pixels_per_meter == expected["pixelsPerMeter"], f"{case['id']}: pHYs drifted")
        idat_lengths = [
            len(zstream[offset : offset + LIMITS["idatPayloadBytes"]])
            for offset in range(0, len(zstream), LIMITS["idatPayloadBytes"])
        ]
        verifier.require(idat_lengths == expected["idatPayloadLengths"] == generated_idat_lengths, f"{case['id']}: IDAT split drifted")
        stored_blocks, parsed_raw = parse_stored_stream(zstream)
        verifier.require(stored_blocks == expected["storedBlockLengths"] == generated_blocks, f"{case['id']}: DEFLATE split drifted")
        verifier.require(parsed_raw == filtered, f"{case['id']}: stored bytes drifted")
        verifier.require(zlib.decompress(zstream) == filtered, f"{case['id']}: stdlib zlib decode drifted")

    return {
        "verifier": "renderweave-output-png-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "surfaceCases": len(vectors["surfaceCases"]),
        "pngCases": len(vectors["pngCases"]),
        "total": len(seen),
        "passed": len(seen),
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(raw).hexdigest(),
        "outputProfile": vectors["outputProfile"],
        "profileAvailability": boundary["profileAvailability"],
        "certificationStatus": boundary["certificationStatus"],
        "rasterImplementation": boundary["rasterImplementation"],
        "daemonOutputPath": boundary["daemonOutputPath"],
        "physicalHostCertification": boundary["physicalHostCertification"],
        "providerAttempts": boundary["providerAttempts"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        report = verify(arguments.vectors)
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(
            f"Output PNG independent replay: {report['passed']}/{report['total']} cases, "
            f"{report['checks']} checks, Profile={report['profileAvailability']}, "
            f"Raster={report['rasterImplementation']}"
        )
        return 0
    except (OSError, VerificationFailure) as error:
        print(f"Output PNG independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
