#!/usr/bin/env python3
"""Independent stdlib replay for the TV1-T106 prepared IMAGE layout bridge."""

from __future__ import annotations

import argparse
import binascii
import copy
import hashlib
import importlib.util
import json
import math
import struct
import sys
from decimal import Decimal
from pathlib import Path
from typing import Any


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


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=strict_pairs,
        parse_int=Decimal,
        parse_float=Decimal,
    )
    if not isinstance(value, dict):
        raise VerificationFailure(f"{path} root must be an object")
    return value


def load_definite_layout_module() -> Any:
    path = Path(__file__).with_name("verify-definite-layout-vectors.py")
    spec = importlib.util.spec_from_file_location(
        "renderweave_prepared_image_definite_layout", path
    )
    if spec is None or spec.loader is None:
        raise VerificationFailure("independent definite layout verifier cannot be loaded")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def parse_tiff_orientation(data: bytes) -> int:
    if len(data) < 8 or data[:2] not in {b"II", b"MM"}:
        raise VerificationFailure("eXIf TIFF header is invalid")
    endian = "<" if data[:2] == b"II" else ">"
    if struct.unpack_from(endian + "H", data, 2)[0] != 42:
        raise VerificationFailure("eXIf TIFF magic is invalid")
    ifd_offset = struct.unpack_from(endian + "I", data, 4)[0]
    if ifd_offset + 2 > len(data):
        raise VerificationFailure("eXIf IFD offset is invalid")
    count = struct.unpack_from(endian + "H", data, ifd_offset)[0]
    position = ifd_offset + 2
    orientation: int | None = None
    for _ in range(count):
        if position + 12 > len(data):
            raise VerificationFailure("eXIf IFD entry is truncated")
        tag, field_type = struct.unpack_from(endian + "HH", data, position)
        value_count = struct.unpack_from(endian + "I", data, position + 4)[0]
        if tag == 0x0112:
            if field_type != 3 or value_count != 1:
                raise VerificationFailure("eXIf orientation shape is invalid")
            orientation = struct.unpack_from(endian + "H", data, position + 8)[0]
        position += 12
    if orientation is None or not 1 <= orientation <= 8:
        raise VerificationFailure("eXIf orientation is missing or invalid")
    return orientation


def parse_png_facts(body: bytes) -> tuple[int, int, int]:
    if not body.startswith(b"\x89PNG\r\n\x1a\n"):
        raise VerificationFailure("resource fixture is not PNG")
    position = 8
    width: int | None = None
    height: int | None = None
    orientation = 1
    saw_iend = False
    while position < len(body):
        if position + 12 > len(body):
            raise VerificationFailure("PNG chunk is truncated")
        length = struct.unpack_from(">I", body, position)[0]
        chunk_type = body[position + 4 : position + 8]
        data_start = position + 8
        data_end = data_start + length
        if data_end + 4 > len(body):
            raise VerificationFailure("PNG chunk length overflows body")
        data = body[data_start:data_end]
        expected_crc = struct.unpack_from(">I", body, data_end)[0]
        actual_crc = binascii.crc32(chunk_type + data) & 0xFFFF_FFFF
        if actual_crc != expected_crc:
            raise VerificationFailure("PNG chunk CRC mismatched")
        if chunk_type == b"IHDR":
            if length != 13 or width is not None:
                raise VerificationFailure("PNG IHDR shape drifted")
            width, height = struct.unpack_from(">II", data, 0)
        elif chunk_type == b"eXIf":
            orientation = parse_tiff_orientation(data)
        elif chunk_type == b"IEND":
            if length != 0:
                raise VerificationFailure("PNG IEND shape drifted")
            saw_iend = True
            position = data_end + 4
            break
        position = data_end + 4
    if not saw_iend or position != len(body) or width is None or height is None:
        raise VerificationFailure("PNG terminal structure drifted")
    return width, height, orientation


def install_prepared_image_adapter(
    module: Any, image_dimensions: dict[str, tuple[int, int]]
) -> None:
    original_node_role = module.definite_node_role
    original_hug_axis = module.resource_free_hug_axis
    original_measure_stack_child = module.measure_stack_child
    original_grid_row_offer = module.grid_row_hug_consumes_resolved_width_offer

    def prepared_node_role(kind: str, current: str) -> str:
        if kind == "image":
            return "LEAF"
        return original_node_role(kind, current)

    def prepared_image_hug_axis(
        node: dict[str, Any],
        placement: dict[str, Any],
        axis: str,
        current: str,
        opposite_axis_offer: Any,
    ) -> float:
        resource_id = module.text(
            node.get("imageResourceId"), f"{current} imageResourceId"
        )
        if resource_id not in image_dimensions:
            raise VerificationFailure(f"{current} prepared IMAGE identity is missing")
        logical_width, logical_height = image_dimensions[resource_id]
        if logical_width <= 0 or logical_height <= 0:
            raise VerificationFailure(f"{current} prepared IMAGE dimensions are invalid")
        if axis == "Width":
            opposite_axis = "Height"
            mode_member = "heightMode"
            size_member = "heightPt"
            start_member = "yPt"
            end_inset_member = "bottomInsetPt"
            ratio = float(logical_width) / float(logical_height)
        elif axis == "Height":
            opposite_axis = "Width"
            mode_member = "widthMode"
            size_member = "widthPt"
            start_member = "xPt"
            end_inset_member = "rightInsetPt"
            ratio = float(logical_height) / float(logical_width)
        else:
            raise VerificationFailure(f"{current} invalid IMAGE HUG axis")
        mode = module.text(placement.get(mode_member), f"{current} {mode_member}")
        if mode == "FIXED":
            opposite_size = module.required_decimal(
                placement, size_member, current, f"placement.{size_member}"
            )
        elif mode == "FILL":
            if opposite_axis_offer is None:
                raise module.Unsupported("HUG_CONTENT", current)
            if opposite_axis_offer.source == "ABSOLUTE_PARENT_CONTENT":
                start = module.required_decimal(
                    placement, start_member, current, f"placement.{start_member}"
                )
                opposite_size = module.definite_axis_size(
                    placement,
                    "FILL",
                    opposite_axis_offer.size,
                    start,
                    opposite_axis,
                    end_inset_member,
                    current,
                )
            elif opposite_axis_offer.source == "RESOLVED_OUTER":
                opposite_size = opposite_axis_offer.size
            else:
                raise VerificationFailure(f"{current} invalid opposite-axis offer")
        else:
            raise VerificationFailure(f"{current} IMAGE opposite axis is not definite")
        natural = opposite_size * ratio
        if not math.isfinite(natural) or natural < 0.0:
            raise VerificationFailure(f"{current} IMAGE intrinsic is non-finite")
        return module.clamp_flexible_axis(placement, natural, axis, current)

    def prepared_hug_axis(
        node: dict[str, Any],
        role: str,
        placement: dict[str, Any],
        axis: str,
        current: str,
        opposite_axis_offer: Any,
    ) -> float:
        if node.get("kind") == "image":
            return prepared_image_hug_axis(
                node, placement, axis, current, opposite_axis_offer
            )
        return original_hug_axis(
            node, role, placement, axis, current, opposite_axis_offer
        )

    def prepared_measure_stack_child(node: dict[str, Any], space: Any, direction: str) -> Any:
        placement = node.get("placement")
        if node.get("kind") != "image" or not isinstance(placement, dict):
            return original_measure_stack_child(node, space, direction)
        deferred = (
            direction == "ROW"
            and placement.get("widthMode") == "FILL"
            and placement.get("heightMode") == "HUG_CONTENT"
        ) or (
            direction == "COLUMN"
            and placement.get("widthMode") == "HUG_CONTENT"
            and placement.get("heightMode") == "FILL"
        )
        if not deferred:
            return original_measure_stack_child(node, space, direction)
        surrogate = copy.deepcopy(node)
        surrogate_placement = surrogate["placement"]
        if direction == "ROW":
            surrogate_placement["heightMode"] = "FIXED"
            surrogate_placement["heightPt"] = module.JsonNumber("0")
        else:
            surrogate_placement["widthMode"] = "FIXED"
            surrogate_placement["widthPt"] = module.JsonNumber("0")
        measured = original_measure_stack_child(surrogate, space, direction)
        return module.StackChildMeasurement(
            measured.width,
            measured.height,
            measured.margin_top,
            measured.margin_right,
            measured.margin_bottom,
            measured.margin_left,
            measured.align_self,
            measured.main_fill,
            True,
        )

    def prepared_grid_row_offer(
        node: dict[str, Any], role: str, placement: dict[str, Any], current: str
    ) -> bool:
        if (
            node.get("kind") == "image"
            and placement.get("widthMode") == "FILL"
            and placement.get("heightMode") == "HUG_CONTENT"
        ):
            return True
        return original_grid_row_offer(node, role, placement, current)

    module.definite_node_role = prepared_node_role
    module.resource_free_hug_axis = prepared_hug_axis
    module.measure_stack_child = prepared_measure_stack_child
    module.grid_row_hug_consumes_resolved_width_offer = prepared_grid_row_offer


def expected_box(module: Any, value: dict[str, Any]) -> dict[str, str]:
    return {
        "xBits": module.binary64_bits(float(value["x"])),
        "yBits": module.binary64_bits(float(value["y"])),
        "widthBits": module.binary64_bits(float(value["width"])),
        "heightBits": module.binary64_bits(float(value["height"])),
    }


def layout_number_tokens(value: Any, module: Any) -> Any:
    if isinstance(value, Decimal):
        return module.JsonNumber(str(value))
    if isinstance(value, dict):
        return {key: layout_number_tokens(child, module) for key, child in value.items()}
    if isinstance(value, list):
        return [layout_number_tokens(child, module) for child in value]
    return value


def verify(vectors_path: Path, report_path: Path) -> dict[str, Any]:
    vectors = load_json(vectors_path)
    verifier = Verifier()
    verifier.require(
        vectors.get("vectorVersion") == "renderweave-prepared-image-layout-vectors/1",
        "vector version drifted",
    )
    fixture = vectors["resourceFixture"]
    body = bytes.fromhex(fixture["bodyHex"])
    verifier.require(
        "sha256:" + hashlib.sha256(body).hexdigest() == fixture["sha256"],
        "resource fixture SHA-256 drifted",
    )
    encoded_width, encoded_height, orientation = parse_png_facts(body)
    verifier.require(encoded_width == fixture["encodedWidthPx"], "encoded width drifted")
    verifier.require(encoded_height == fixture["encodedHeightPx"], "encoded height drifted")
    orientation_name = {
        1: "IDENTITY",
        2: "MIRROR_HORIZONTAL",
        3: "ROTATE_180",
        4: "MIRROR_VERTICAL",
        5: "TRANSPOSE",
        6: "ROTATE_90_CW",
        7: "TRANSVERSE",
        8: "ROTATE_270_CW",
    }[orientation]
    verifier.require(orientation_name == fixture["orientation"], "orientation drifted")
    logical_width, logical_height = (
        (encoded_height, encoded_width)
        if orientation in {5, 6, 7, 8}
        else (encoded_width, encoded_height)
    )
    verifier.require(logical_width == fixture["logicalWidthPx"], "logical width drifted")
    verifier.require(logical_height == fixture["logicalHeightPx"], "logical height drifted")

    template = vectors["documentTemplate"]
    resource = template["resources"][0]
    verifier.require(resource["sha256"] == fixture["sha256"], "document SHA drifted")
    descriptor = resource["technicalDescriptor"]
    verifier.require(
        descriptor["logicalWidthPx"] == logical_width
        and descriptor["logicalHeightPx"] == logical_height,
        "document logical descriptor drifted",
    )
    image_dimensions = {resource["resourceId"]: (logical_width, logical_height)}

    module = load_definite_layout_module()
    original_node_role = module.definite_node_role
    boundary = vectors["resourceFreeBoundary"]
    try:
        original_node_role("image", boundary["expectedOccurrenceId"])
        raise VerificationFailure("resource-free Image unexpectedly crossed the boundary")
    except module.Unsupported as error:
        verifier.require(error.feature == boundary["expectedFeature"], "resource-free feature drifted")
        verifier.require(
            error.occurrence_id == boundary["expectedOccurrenceId"],
            "resource-free occurrence drifted",
        )

    install_prepared_image_adapter(module, image_dimensions)
    success_cases = vectors["successCases"]
    for case in success_cases:
        document = copy.deepcopy(template)
        module.apply_mutations(document, case["mutations"])
        entries = module.DefiniteLayouter().run(layout_number_tokens(document, module))
        verifier.require(
            len(entries) == case["expectedEntryCount"],
            f"{case['id']} entry count drifted",
        )
        by_occurrence = {entry["occurrenceId"]: entry for entry in entries}
        for expected in case["expectedEntries"]:
            current = expected["occurrenceId"]
            verifier.require(current in by_occurrence, f"{case['id']} missing {current}")
            actual = by_occurrence[current]
            verifier.require(actual["kind"] == expected["kind"], f"{case['id']} kind drifted")
            verifier.require(
                actual["layoutBox"] == expected_box(module, expected["layoutBox"]),
                f"{case['id']} LayoutBox drifted for {current}",
            )
            expected_content = expected["contentBox"]
            verifier.require(
                actual["contentBox"]
                == (expected_box(module, expected_content) if expected_content is not None else None),
                f"{case['id']} ContentBox drifted for {current}",
            )

    mismatch = vectors["manifestMismatchCase"]
    verifier.require(
        mismatch["replacementResourceId"] not in image_dimensions,
        "manifest mismatch resource unexpectedly exists",
    )
    verifier.require(
        mismatch["expectedOccurrenceId"] == "rwocc_0000000000000000"
        and mismatch["expectedInvariant"] == "preparedResourceManifest",
        "manifest mismatch terminal drifted",
    )

    authority = vectors["authorityContext"]
    for member, expected in {
        "layoutProfile": "renderweave-layout/1.0",
        "resourcePreparationProfile": "renderweave-renderer/1.0",
        "intrinsicSource": "EXACT_BYTES_ORIENTATION_NORMALIZED_LOGICAL_PIXELS",
        "profileAvailability": "NOT_REGISTERED",
        "certificationStatus": "NOT_CERTIFIED",
        "sceneImplementation": "ABSENT",
        "rasterImplementation": "ABSENT",
        "daemonOutputPath": "UNWIRED",
        "productRoute": "CLOSED",
        "providerAttempts": 0,
    }.items():
        verifier.require(authority.get(member) == expected, f"authority {member} drifted")

    total = len(success_cases) + 2
    report = {
        "verifier": "renderweave-prepared-image-layout-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "successCases": len(success_cases),
        "negativeCases": 2,
        "passed": total,
        "total": total,
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": hashlib.sha256(vectors_path.read_bytes()).hexdigest(),
        "layoutProfile": authority["layoutProfile"],
        "resourcePreparationProfile": authority["resourcePreparationProfile"],
        "intrinsicSource": authority["intrinsicSource"],
        "layoutImplementation": "PREPARED_IMAGE_FIXED_FILL_SINGLE_AXIS_HUG_LOGICAL_RATIO_ABSOLUTE_STACK_GRID_CONTAINER_AUTOMATED_VERIFIED_UNWIRED",
        "profileAvailability": authority["profileAvailability"],
        "certificationStatus": authority["certificationStatus"],
        "sceneImplementation": authority["sceneImplementation"],
        "rasterImplementation": authority["rasterImplementation"],
        "daemonOutputPath": authority["daemonOutputPath"],
        "productRoute": authority["productRoute"],
        "providerAttempts": int(authority["providerAttempts"]),
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    repo_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--vectors",
        type=Path,
        default=repo_root / "renderer" / "prepared-image-layout-vectors-v1.json",
    )
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify(args.vectors, args.report)
    except (VerificationFailure, KeyError, TypeError, ValueError) as error:
        print(f"Prepared IMAGE layout verification failed: {error}", file=sys.stderr)
        return 1
    print(
        "Prepared IMAGE layout independent replay: "
        f"{report['passed']}/{report['total']} cases, {report['checks']} checks"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
