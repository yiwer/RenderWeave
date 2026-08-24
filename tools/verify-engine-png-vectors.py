#!/usr/bin/env python3
"""Independent stdlib replay for the TV1-T93 Engine PNG scene/raster kernel."""

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


def parse_rgba(value: Any, label: str) -> bytes:
    if not isinstance(value, str) or len(value) != 9 or not value.startswith("#"):
        raise VerificationFailure(f"{label} color is invalid")
    try:
        return bytes.fromhex(value[1:])
    except ValueError as error:
        raise VerificationFailure(f"{label} color is invalid") from error


def parse_background(value: Any) -> bytes | dict[str, str]:
    rgba = parse_rgba(value, "Canvas background")
    if rgba[3] == 0:
        return b"\0\0\0\0"
    if rgba[3] == 255:
        return rgba
    return {"feature": "PARTIAL_BACKGROUND_ALPHA"}


def number_is(value: Any, expected: str) -> bool:
    if type(value) is int:
        return Decimal(value) == Decimal(expected)
    return isinstance(value, Decimal) and value == Decimal(expected)


def has_exact_members(value: Any, expected: set[str]) -> bool:
    return isinstance(value, dict) and set(value) == expected


def exact_device_edge(parts: list[int], dpi: int) -> int | None:
    numerator = sum(parts) * dpi
    edge, remainder = divmod(numerator, 72_000_000)
    return edge if remainder == 0 else None


def paint_single_rect(
    child: Any,
    canvas: dict[str, Any],
    dpi: int,
    width: int,
    height: int,
    pixels: bytearray,
) -> dict[str, str] | None:
    if not isinstance(child, dict) or child.get("kind") != "rect":
        return {"feature": "SCENE_STRUCTURE"}
    if (
        child.get("visible") is not True
        or not number_is(child.get("opacity"), "1")
        or "stroke" in child
    ):
        return {"feature": "RECT_PAINT"}

    transform = child.get("transform")
    if not has_exact_members(
        transform, {"originX", "originY", "rotationDeg", "scaleX", "scaleY"}
    ) or not all((
        number_is(transform["originX"], "0.5"),
        number_is(transform["originY"], "0.5"),
        number_is(transform["rotationDeg"], "0"),
        number_is(transform["scaleX"], "1"),
        number_is(transform["scaleY"], "1"),
    )):
        return {"feature": "RECT_PAINT"}

    radii = child.get("cornerRadii")
    radius_members = {"bottomLeftPt", "bottomRightPt", "topLeftPt", "topRightPt"}
    if not has_exact_members(radii, radius_members) or not all(
        number_is(radii[member], "0") for member in radius_members
    ):
        return {"feature": "RECT_PAINT"}

    fill = child.get("fill")
    if not has_exact_members(fill, {"color"}):
        return {"feature": "RECT_PAINT"}
    color = parse_rgba(fill["color"], "Rect fill")
    if color[3] != 255:
        return {"feature": "NON_OPAQUE_RECT_ALPHA"}

    placement = child.get("placement")
    placement_members = {
        "heightMode", "heightPt", "type", "widthMode", "widthPt", "xPt", "yPt"
    }
    if (
        not has_exact_members(placement, placement_members)
        or placement["type"] != "ABSOLUTE"
        or placement["widthMode"] != "FIXED"
        or placement["heightMode"] != "FIXED"
    ):
        return {"feature": "RECT_PAINT"}

    bleed = canvas["bleed"]
    left_bleed = decimal6(bleed["leftPt"], False)
    top_bleed = decimal6(bleed["topPt"], False)
    x = decimal6(placement["xPt"], False)
    y = decimal6(placement["yPt"], False)
    rect_width = decimal6(placement["widthPt"], True)
    rect_height = decimal6(placement["heightPt"], True)
    edges = (
        exact_device_edge([left_bleed, x], dpi),
        exact_device_edge([top_bleed, y], dpi),
        exact_device_edge([left_bleed, x, rect_width], dpi),
        exact_device_edge([top_bleed, y, rect_height], dpi),
    )
    if any(edge is None for edge in edges):
        return {"feature": "NON_PIXEL_ALIGNED_RECT"}
    left, top, right, bottom = (int(edge) for edge in edges)
    if right < left or bottom < top:
        raise VerificationFailure("Rect device box is not monotonic")
    left = min(max(left, 0), width)
    right = min(max(right, 0), width)
    top = min(max(top, 0), height)
    bottom = min(max(bottom, 0), height)
    for row in range(top, bottom):
        for column in range(left, right):
            offset = (row * width + column) * 4
            pixels[offset : offset + 4] = color
    return None


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
    if len(canvas["children"]) > 1:
        return {"feature": "SCENE_STRUCTURE"}
    pixel = parse_background(canvas.get("backgroundColor"))
    if isinstance(pixel, dict):
        return pixel
    dimensions = surface_dimensions(canvas, dpi)
    if "code" in dimensions:
        return dimensions
    width = dimensions["widthPx"]
    height = dimensions["heightPx"]
    pixels = bytearray(pixel * (width * height))
    if canvas["children"]:
        problem = paint_single_rect(canvas["children"][0], canvas, dpi, width, height, pixels)
        if problem is not None:
            return problem
    pixels = bytes(pixels)
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
        "enginePngKernel": "PIXEL_ALIGNED_OPAQUE_RECT_PNG_KERNEL_UNWIRED",
        "processRasterImplementation": "ABSENT",
        "daemonOutputPath": "UNWIRED",
        "productRoute": "CLOSED",
        "providerAttempts": 0,
    }, "Engine PNG honest boundary drifted")
    verifier.require(len(vectors["renderedCases"]) == 7, "rendered case count drifted")
    verifier.require(len(vectors["unsupportedCases"]) == 8, "unsupported case count drifted")

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
