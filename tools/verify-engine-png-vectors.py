#!/usr/bin/env python3
"""Independent stdlib replay for the TV1-T92 empty-Canvas Engine PNG kernel."""

from __future__ import annotations

import argparse
import binascii
import hashlib
import json
import struct
import sys
import zlib
from decimal import Decimal
from pathlib import Path
from typing import Any


LIMITS = {
    "dpi": 600,
    "surfaceEdgePixels": 16_384,
    "surfacePixels": 50_000_000,
    "rgba8SurfaceBytes": 200_000_000,
    "storedDeflateBlockBytes": 65_535,
    "idatPayloadBytes": 1_048_576,
    "encodedImageBytes": 536_870_912,
}


class VerificationFailure(RuntimeError):
    pass


class Verifier:
    def __init__(self) -> None:
        self.checks = 0

    def require(self, condition: bool, message: str) -> None:
        self.checks += 1
        if not condition:
            raise VerificationFailure(message)


def strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationFailure(f"duplicate member: {key}")
        result[key] = value
    return result


def reject_null(value: Any, location: str = "$") -> None:
    if value is None:
        raise VerificationFailure(f"null is forbidden at {location}")
    if isinstance(value, dict):
        for key, child in value.items():
            reject_null(child, f"{location}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_null(child, f"{location}[{index}]")


def exact_members(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise VerificationFailure(f"{label} member set drifted")
    return value


def decimal6(value: Any, positive: bool) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise VerificationFailure("surface decimal is not numeric")
    raw = str(value)
    if "e" in raw.lower() or raw.startswith("+"):
        raise VerificationFailure("surface decimal is not canonical")
    negative = raw.startswith("-")
    digits = raw[1:] if negative else raw
    whole, dot, fraction = digits.partition(".")
    if not whole.isdigit() or (dot and (not fraction.isdigit() or len(fraction) > 6)):
        raise VerificationFailure("surface decimal is not decimal6")
    scaled = int(whole) * 1_000_000 + int(fraction.ljust(6, "0") or "0")
    if negative:
        scaled = -scaled
    if scaled < 0 or (positive and scaled == 0):
        raise VerificationFailure("surface decimal sign is invalid")
    return scaled


def output_failure(code: str, limit_id: str) -> dict[str, str]:
    return {"code": code, "stage": "OUTPUT_PREFLIGHT", "limitId": limit_id}


def surface_dimensions(canvas: dict[str, Any], dpi: Any) -> dict[str, int] | dict[str, str]:
    if type(dpi) is not int or not 1 <= dpi <= LIMITS["dpi"]:
        return output_failure("OUTPUT_BUDGET_EXCEEDED", "rendererSurfaceAndOutput.dpi")
    bleed = exact_members(
        canvas["bleed"], {"bottomPt", "leftPt", "rightPt", "topPt"}, "Canvas bleed"
    )
    width = decimal6(canvas["widthPt"], True)
    height = decimal6(canvas["heightPt"], True)
    left = decimal6(bleed["leftPt"], False)
    right = decimal6(bleed["rightPt"], False)
    top = decimal6(bleed["topPt"], False)
    bottom = decimal6(bleed["bottomPt"], False)
    denominator = 72_000_000
    width_px = ((width + left + right) * dpi + denominator // 2) // denominator
    height_px = ((height + top + bottom) * dpi + denominator // 2) // denominator
    if width_px == 0 or height_px == 0:
        return output_failure("OUTPUT_BUDGET_EXCEEDED", "rendererSurfaceAndOutput.surfacePixels")
    if width_px > LIMITS["surfaceEdgePixels"] or height_px > LIMITS["surfaceEdgePixels"]:
        return output_failure("OUTPUT_BUDGET_EXCEEDED", "rendererSurfaceAndOutput.surfaceEdgePixels")
    pixel_count = width_px * height_px
    if pixel_count > LIMITS["surfacePixels"]:
        return output_failure("OUTPUT_BUDGET_EXCEEDED", "rendererSurfaceAndOutput.surfacePixels")
    if pixel_count * 4 > LIMITS["rgba8SurfaceBytes"]:
        return output_failure("RASTER_BUDGET_EXCEEDED", "rendererSurfaceAndOutput.rgba8SurfaceBytes")
    return {"widthPx": width_px, "heightPx": height_px}


def parse_background(value: Any) -> bytes | dict[str, str]:
    if not isinstance(value, str) or len(value) != 9 or not value.startswith("#"):
        raise VerificationFailure("Canvas background color is invalid")
    try:
        rgba = bytes.fromhex(value[1:])
    except ValueError as error:
        raise VerificationFailure("Canvas background color is invalid") from error
    if rgba[3] == 0:
        return b"\0\0\0\0"
    if rgba[3] == 255:
        return rgba
    return {"feature": "PARTIAL_BACKGROUND_ALPHA"}


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFF_FFFF)
    )


def encode_png(width: int, height: int, dpi: int, pixels: bytes) -> bytes:
    row_bytes = width * 4
    filtered = b"".join(
        b"\0" + pixels[row * row_bytes : (row + 1) * row_bytes]
        for row in range(height)
    )
    zstream = bytearray(b"\x78\x01")
    offset = 0
    while offset < len(filtered):
        length = min(LIMITS["storedDeflateBlockBytes"], len(filtered) - offset)
        final = offset + length == len(filtered)
        zstream.append(1 if final else 0)
        zstream += struct.pack("<HH", length, length ^ 0xFFFF)
        zstream += filtered[offset : offset + length]
        offset += length
    zstream += struct.pack(">I", zlib.adler32(filtered) & 0xFFFF_FFFF)
    ppm = (dpi * 5_000 + 63) // 127
    encoded = b"\x89PNG\r\n\x1a\n"
    encoded += png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    encoded += png_chunk(b"sRGB", b"\0")
    encoded += png_chunk(b"pHYs", struct.pack(">IIB", ppm, ppm, 1))
    encoded += b"".join(
        png_chunk(b"IDAT", bytes(zstream[start : start + LIMITS["idatPayloadBytes"]]))
        for start in range(0, len(zstream), LIMITS["idatPayloadBytes"])
    )
    encoded += png_chunk(b"IEND", b"")
    if len(encoded) > LIMITS["encodedImageBytes"]:
        raise VerificationFailure("encoded image budget unexpectedly exceeded after preflight")
    return encoded


def execute(document: dict[str, Any], dpi: Any) -> dict[str, Any]:
    exact_members(document, {"canvas", "dslVersion", "layoutProfile", "resources"}, "RenderDocument")
    if document["resources"]:
        return {"feature": "RESOURCE_MANIFEST"}
    canvas = document["canvas"]
    if not isinstance(canvas, dict) or not isinstance(canvas.get("children"), list):
        raise VerificationFailure("Canvas shape is invalid")
    if canvas["children"]:
        return {"feature": "NONEMPTY_CANVAS"}
    pixel = parse_background(canvas.get("backgroundColor"))
    if isinstance(pixel, dict):
        return pixel
    dimensions = surface_dimensions(canvas, dpi)
    if "code" in dimensions:
        return dimensions
    width = dimensions["widthPx"]
    height = dimensions["heightPx"]
    pixels = pixel * (width * height)
    encoded = encode_png(width, height, dpi, pixels)
    return {
        "widthPx": width,
        "heightPx": height,
        "mediaType": "image/png",
        "outputProfile": "renderweave-output-png/1.0",
        "byteLength": len(encoded),
        "contentSha256": "sha256:" + hashlib.sha256(encoded).hexdigest(),
        "pixelSha256": "sha256:" + hashlib.sha256(pixels).hexdigest(),
        "exactHex": encoded.hex(),
    }


def verify(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    try:
        vectors = json.loads(raw, object_pairs_hook=strict_pairs, parse_float=Decimal)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"vectors are not strict UTF-8 JSON: {error}") from error
    reject_null(vectors)
    exact_members(
        vectors,
        {"vectorVersion", "boundary", "documents", "renderedCases", "unsupportedCases"},
        "Engine PNG vector manifest",
    )
    verifier = Verifier()
    verifier.require(
        vectors["vectorVersion"] == "renderweave-engine-png-vectors/1",
        "Engine PNG vector identity drifted",
    )
    boundary = exact_members(
        vectors["boundary"],
        {
            "profileAvailability", "certificationStatus", "enginePngKernel",
            "processRasterImplementation", "daemonOutputPath", "productRoute", "providerAttempts",
        },
        "Engine PNG boundary",
    )
    verifier.require(boundary == {
        "profileAvailability": "NOT_REGISTERED",
        "certificationStatus": "NOT_CERTIFIED",
        "enginePngKernel": "EMPTY_CANVAS_PNG_KERNEL_UNWIRED",
        "processRasterImplementation": "ABSENT",
        "daemonOutputPath": "UNWIRED",
        "productRoute": "CLOSED",
        "providerAttempts": 0,
    }, "Engine PNG honest boundary drifted")
    verifier.require(len(vectors["renderedCases"]) == 5, "rendered case count drifted")
    verifier.require(len(vectors["unsupportedCases"]) == 4, "unsupported case count drifted")

    seen: set[str] = set()
    for family in ("renderedCases", "unsupportedCases"):
        for case in vectors[family]:
            exact_members(case, {"id", "documentId", "dpi", "expected"}, "Engine PNG case")
            verifier.require(isinstance(case["id"], str) and case["id"] not in seen, "duplicate case id")
            seen.add(case["id"])
            document = vectors["documents"].get(case["documentId"])
            verifier.require(isinstance(document, dict), f"{case['id']}: document is absent")
            actual = execute(document, case["dpi"])
            verifier.require(actual == case["expected"], f"{case['id']}: result drifted")

    return {
        "verifier": "renderweave-engine-png-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "renderedCases": len(vectors["renderedCases"]),
        "unsupportedCases": len(vectors["unsupportedCases"]),
        "total": len(seen),
        "passed": len(seen),
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(raw).hexdigest(),
        **boundary,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    arguments = parser.parse_args()
    try:
        report = verify(arguments.vectors)
        if arguments.report is not None:
            arguments.report.parent.mkdir(parents=True, exist_ok=True)
            arguments.report.write_text(
                json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n"
            )
        print(
            f"Engine PNG independent replay: {report['passed']}/{report['total']} cases, "
            f"{report['checks']} checks, Profile={report['profileAvailability']}, "
            f"Daemon={report['daemonOutputPath']}"
        )
        return 0
    except (OSError, ValueError, VerificationFailure) as error:
        print(f"Engine PNG independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
