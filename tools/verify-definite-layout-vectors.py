#!/usr/bin/env python3
"""Independent standard-library replay for definite ABSOLUTE local-box vectors."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class VerificationFailure(ValueError):
    pass


@dataclass(frozen=True)
class JsonNumber:
    token: str


def number_token(token: str) -> JsonNumber:
    return JsonNumber(token)


def strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationFailure(f"duplicate JSON member: {key}")
        result[key] = value
    return result


def load_strict(raw: bytes, label: str, *, allow_null: bool = False) -> Any:
    try:
        value = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=strict_pairs,
            parse_int=number_token,
            parse_float=number_token,
            parse_constant=lambda token: (_ for _ in ()).throw(
                VerificationFailure(f"non-finite JSON number in {label}: {token}")
            ),
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"strict JSON failed for {label}: {error}") from error
    if not allow_null:
        reject_null(value, label)
    return value


def reject_null(value: Any, location: str) -> None:
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


def integer(value: Any, label: str) -> int:
    if not isinstance(value, JsonNumber) or not re.fullmatch(r"0|[1-9][0-9]*", value.token):
        raise VerificationFailure(f"{label} must be a canonical nonnegative integer")
    return int(value.token)


def text(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise VerificationFailure(f"{label} must be text")
    return value


@dataclass
class Verifier:
    checks: int = 0

    def require(self, condition: bool, message: str) -> None:
        self.checks += 1
        if not condition:
            raise VerificationFailure(message)


@dataclass(frozen=True)
class Unsupported(Exception):
    feature: str
    occurrence_id: str


@dataclass(frozen=True)
class Box:
    x: float
    y: float
    width: float
    height: float

    def bits(self) -> dict[str, str]:
        return {
            "xBits": binary64_bits(self.x),
            "yBits": binary64_bits(self.y),
            "widthBits": binary64_bits(self.width),
            "heightBits": binary64_bits(self.height),
        }


PLAIN_DECIMAL6 = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,6})?")
SUPPORTED_LEAVES = {
    "rect",
    "ellipse",
    "line",
    "polygon",
    "polyline",
    "path",
    "qrCode",
    "barcode",
}


def binary64_bits(value: float) -> str:
    return struct.pack(">d", value).hex()


def decimal6(value: Any, occurrence_id: str, prop: str) -> float:
    if not isinstance(value, JsonNumber) or PLAIN_DECIMAL6.fullmatch(value.token) is None:
        raise VerificationFailure(f"{occurrence_id} {prop} is not plain decimal6")
    result = float(value.token)
    if value.token.startswith("-") and result == 0.0:
        raise VerificationFailure(f"{occurrence_id} {prop} is negative zero")
    if not (-float("inf") < result < float("inf")):
        raise VerificationFailure(f"{occurrence_id} {prop} is not finite binary64")
    return result


def required_decimal(
    obj: dict[str, Any], member: str, occurrence_id: str, prop: str
) -> float:
    return decimal6(obj.get(member), occurrence_id, prop)


def optional_decimal(
    obj: dict[str, Any], member: str, occurrence_id: str, prop: str
) -> float | None:
    return required_decimal(obj, member, occurrence_id, prop) if member in obj else None


def nonnegative_decimal(
    obj: dict[str, Any], member: str, occurrence_id: str, prop: str
) -> float:
    value = required_decimal(obj, member, occurrence_id, prop)
    if value < 0.0:
        raise VerificationFailure(f"{occurrence_id} {prop} is negative")
    return value


def object_value(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise VerificationFailure(f"{label} must be an object")
    return value


def array_value(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise VerificationFailure(f"{label} must be an array")
    return value


def occurrence(node: dict[str, Any]) -> str:
    return text(node.get("occurrenceId"), "occurrenceId")


class DefiniteLayouter:
    def __init__(self) -> None:
        self.entries: list[dict[str, Any]] = []

    def run(self, document: dict[str, Any]) -> list[dict[str, Any]]:
        canvas = object_value(document.get("canvas"), "canvas")
        current = occurrence(canvas)
        width = required_decimal(canvas, "widthPt", current, "widthPt")
        height = required_decimal(canvas, "heightPt", current, "heightPt")
        if width <= 0.0 or height <= 0.0:
            raise VerificationFailure("Canvas dimensions must be positive")
        canvas_box = Box(0.0, 0.0, width, height)
        self.entries.append(
            {
                "occurrenceId": current,
                "kind": "canvas",
                "layoutBox": canvas_box.bits(),
                "contentBox": canvas_box.bits(),
            }
        )
        for child in array_value(canvas.get("children"), f"{current} children"):
            self.visit_node(object_value(child, f"{current} child"), canvas_box)
        return self.entries

    def visit_node(self, node: dict[str, Any], parent_content: Box) -> None:
        current = occurrence(node)
        kind = text(node.get("kind"), f"{current} kind")
        if kind == "group":
            raise Unsupported("GROUP", current)
        if kind == "stack":
            raise Unsupported("STACK", current)
        if kind == "grid":
            raise Unsupported("GRID", current)
        if kind == "compositionViewport":
            raise Unsupported("COMPOSITION_VIEWPORT", current)
        if kind in {"text", "image"}:
            raise Unsupported("RESOURCE_DEPENDENT_KIND", current)
        if kind != "frame" and kind not in SUPPORTED_LEAVES:
            raise VerificationFailure(f"{current} unexpected kind {kind}")

        placement = object_value(node.get("placement"), f"{current} placement")
        if placement.get("type") != "ABSOLUTE":
            raise Unsupported("NON_ABSOLUTE_PLACEMENT", current)
        width_mode = placement.get("widthMode")
        height_mode = placement.get("heightMode")
        if width_mode == "HUG_CONTENT" or height_mode == "HUG_CONTENT":
            raise Unsupported("HUG_CONTENT", current)
        if width_mode not in {"FIXED", "FILL"} or height_mode not in {"FIXED", "FILL"}:
            raise VerificationFailure(f"{current} invalid definite size mode")

        authored_x = required_decimal(placement, "xPt", current, "placement.xPt")
        authored_y = required_decimal(placement, "yPt", current, "placement.yPt")
        width = definite_axis_size(
            placement,
            text(width_mode, "width mode"),
            parent_content.width,
            authored_x,
            "Width",
            "rightInsetPt",
            current,
        )
        height = definite_axis_size(
            placement,
            text(height_mode, "height mode"),
            parent_content.height,
            authored_y,
            "Height",
            "bottomInsetPt",
            current,
        )
        layout_box = Box(
            parent_content.x + authored_x,
            parent_content.y + authored_y,
            width,
            height,
        )
        if kind == "frame":
            content_box = frame_content_box(node, layout_box, current)
            self.entries.append(
                {
                    "occurrenceId": current,
                    "kind": kind,
                    "layoutBox": layout_box.bits(),
                    "contentBox": content_box.bits(),
                }
            )
            for child in array_value(node.get("children"), f"{current} children"):
                self.visit_node(object_value(child, f"{current} child"), content_box)
        else:
            self.entries.append(
                {
                    "occurrenceId": current,
                    "kind": kind,
                    "layoutBox": layout_box.bits(),
                    "contentBox": None,
                }
            )


def definite_axis_size(
    placement: dict[str, Any],
    mode: str,
    parent_size: float,
    start: float,
    axis: str,
    end_inset_member: str,
    current: str,
) -> float:
    size_member = f"{axis.lower()}Pt"
    if mode == "FIXED":
        return required_decimal(
            placement, size_member, current, f"placement.{size_member}"
        )
    end_inset = required_decimal(
        placement,
        end_inset_member,
        current,
        f"placement.{end_inset_member}",
    )
    remaining = (parent_size - start) - end_inset
    size = remaining if remaining > 0.0 else 0.0
    minimum_member = f"min{axis}Pt"
    minimum = optional_decimal(
        placement, minimum_member, current, f"placement.{minimum_member}"
    )
    if minimum is not None and size < minimum:
        size = minimum
    maximum_member = f"max{axis}Pt"
    maximum = optional_decimal(
        placement, maximum_member, current, f"placement.{maximum_member}"
    )
    if maximum is not None and size > maximum:
        size = maximum
    return size


def frame_content_box(node: dict[str, Any], layout_box: Box, current: str) -> Box:
    if "stroke" in node:
        stroke = object_value(node["stroke"], f"{current} stroke")
        stroke_width = nonnegative_decimal(
            stroke, "widthPt", current, "stroke.widthPt"
        )
    else:
        stroke_width = 0.0
    inner_width = subtract_content_inset(layout_box.width, stroke_width, current)
    inner_width = subtract_content_inset(inner_width, stroke_width, current)
    inner_height = subtract_content_inset(layout_box.height, stroke_width, current)
    inner_height = subtract_content_inset(inner_height, stroke_width, current)
    inner_x = layout_box.x + stroke_width
    inner_y = layout_box.y + stroke_width

    padding = object_value(node.get("padding"), f"{current} padding")
    top = nonnegative_decimal(padding, "topPt", current, "padding.topPt")
    right = nonnegative_decimal(padding, "rightPt", current, "padding.rightPt")
    bottom = nonnegative_decimal(padding, "bottomPt", current, "padding.bottomPt")
    left = nonnegative_decimal(padding, "leftPt", current, "padding.leftPt")
    content_width = subtract_content_inset(inner_width, left, current)
    content_width = subtract_content_inset(content_width, right, current)
    content_height = subtract_content_inset(inner_height, top, current)
    content_height = subtract_content_inset(content_height, bottom, current)
    return Box(inner_x + left, inner_y + top, content_width, content_height)


def subtract_content_inset(size: float, inset: float, current: str) -> float:
    remaining = size - inset
    if remaining < 0.0:
        raise Unsupported("DEGENERATE_CONTENT_INSET", current)
    return remaining


def decode_pointer(token: str) -> str:
    return token.replace("~1", "/").replace("~0", "~")


def resolve_pointer(root: Any, pointer: str) -> Any:
    value = root
    for raw in pointer.split("/")[1:]:
        token = decode_pointer(raw)
        value = value[int(token)] if isinstance(value, list) else value[token]
    return value


def resolve_parent(root: Any, pointer: str) -> tuple[Any, str]:
    parent_pointer, token = pointer.rsplit("/", 1)
    return resolve_pointer(root, parent_pointer), decode_pointer(token)


def apply_mutations(document: Any, mutations: list[Any]) -> None:
    for mutation in mutations:
        operation = mutation["operation"]
        parent, token = resolve_parent(document, mutation["pointer"])
        if operation == "remove":
            del parent[token]
        elif operation in {"add", "replace"}:
            parent[token] = copy.deepcopy(mutation["value"])
        else:
            raise VerificationFailure(f"unknown mutation operation: {operation}")


def renumber_node(node: dict[str, Any], next_occurrence: list[int]) -> None:
    node["occurrenceId"] = f"rwocc_{next_occurrence[0]:016x}"
    next_occurrence[0] += 1
    if node.get("kind") == "compositionViewport":
        source = object_value(node.get("sourceCanvas"), "sourceCanvas")
        source["occurrenceId"] = f"rwocc_{next_occurrence[0]:016x}"
        next_occurrence[0] += 1
        for child in array_value(source.get("children"), "sourceCanvas children"):
            renumber_node(object_value(child, "sourceCanvas child"), next_occurrence)
    elif "children" in node:
        for child in array_value(node["children"], "children"):
            renumber_node(object_value(child, "child"), next_occurrence)


def case_document(
    case: dict[str, Any],
    fixtures: dict[str, Any],
    layout_preflight_fixtures: dict[str, Any],
    all_kinds: dict[str, Any],
) -> dict[str, Any]:
    source = case["baseSource"]
    if source == "fixtures":
        document = copy.deepcopy(fixtures["documents"][case["baseCase"]])
    elif source == "layoutPreflight":
        document = copy.deepcopy(
            layout_preflight_fixtures["documents"][case["baseCase"]]
        )
    elif source == "allKinds":
        document = copy.deepcopy(all_kinds)
    else:
        raise VerificationFailure(f"unknown base source: {source}")

    if "retainCanvasChildren" in case:
        children = document["canvas"]["children"]
        document["canvas"]["children"] = [
            copy.deepcopy(children[integer(index, "child index")])
            for index in case["retainCanvasChildren"]
        ]
    if "retainResources" in case:
        resources = document["resources"]
        document["resources"] = [
            copy.deepcopy(resources[integer(index, "resource index")])
            for index in case["retainResources"]
        ]
    if case.get("renumberOccurrences") is True:
        renumber_node(document["canvas"], [0])
    apply_mutations(document, case.get("mutations", []))
    return document


def validate_occurrence_sequence(document: dict[str, Any]) -> int:
    expected = [0]

    def visit(node: dict[str, Any]) -> None:
        wanted = f"rwocc_{expected[0]:016x}"
        if occurrence(node) != wanted:
            raise VerificationFailure("occurrenceId preorder sequence drifted")
        expected[0] += 1
        if node.get("kind") == "compositionViewport":
            source = object_value(node.get("sourceCanvas"), "sourceCanvas")
            if occurrence(source) != f"rwocc_{expected[0]:016x}":
                raise VerificationFailure("sourceCanvas occurrence sequence drifted")
            expected[0] += 1
            for child in array_value(source.get("children"), "sourceCanvas children"):
                visit(object_value(child, "sourceCanvas child"))
        elif "children" in node:
            for child in array_value(node["children"], "children"):
                visit(object_value(child, "child"))

    visit(object_value(document.get("canvas"), "canvas"))
    return expected[0]


def expected_entries(raw: Any) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for index, entry in enumerate(array_value(raw, "expected entries")):
        entry = exact_members(
            entry,
            {"occurrenceId", "kind", "layoutBox", "contentBox"},
            f"expected entry {index}",
        )
        layout_box = expected_box(entry["layoutBox"], f"entry {index} LayoutBox")
        content_box = (
            None
            if entry["contentBox"] is None
            else expected_box(entry["contentBox"], f"entry {index} ContentBox")
        )
        result.append(
            {
                "occurrenceId": text(entry["occurrenceId"], "expected occurrence"),
                "kind": text(entry["kind"], "expected kind"),
                "layoutBox": layout_box,
                "contentBox": content_box,
            }
        )
    return result


def expected_box(raw: Any, label: str) -> dict[str, str]:
    box = exact_members(
        raw, {"xBits", "yBits", "widthBits", "heightBits"}, label
    )
    result = {member: text(value, f"{label} {member}") for member, value in box.items()}
    if any(re.fullmatch(r"[0-9a-f]{16}", value) is None for value in result.values()):
        raise VerificationFailure(f"{label} contains a non-binary64 bit string")
    return result


def verify(
    vectors_path: Path,
    fixtures_path: Path,
    layout_preflight_fixtures_path: Path,
    all_kinds_path: Path,
) -> dict[str, Any]:
    vector_raw = vectors_path.read_bytes()
    fixture_raw = fixtures_path.read_bytes()
    layout_preflight_fixture_raw = layout_preflight_fixtures_path.read_bytes()
    all_kinds_raw = all_kinds_path.read_bytes()
    vectors = load_strict(vector_raw, "vectors", allow_null=True)
    fixtures = load_strict(fixture_raw, "fixtures")
    layout_preflight_fixtures = load_strict(
        layout_preflight_fixture_raw, "layout preflight fixtures"
    )
    all_kinds = load_strict(all_kinds_raw, "allKinds")
    verifier = Verifier()

    exact_members(
        vectors,
        {
            "vectorVersion",
            "authorityContext",
            "boundary",
            "fixturesPath",
            "externalSources",
            "laidOutCases",
            "unsupportedCases",
        },
        "vector manifest",
    )
    verifier.require(
        vectors["vectorVersion"] == "renderweave-definite-layout-vectors/1",
        "vector identity drifted",
    )
    authority = exact_members(
        vectors["authorityContext"],
        {"layoutProfile", "documentContract", "numericEncoding", "arithmetic"},
        "authority context",
    )
    verifier.require(
        authority["layoutProfile"] == "renderweave-layout/1.0",
        "Layout Profile drifted",
    )
    verifier.require(
        authority["documentContract"] == "renderweave-render-node-contract-v1/2",
        "document contract drifted",
    )
    verifier.require(
        authority["numericEncoding"] == "canonical-plain-decimal6",
        "numeric encoding drifted",
    )
    verifier.require(
        authority["arithmetic"] == "IEEE-754-binary64-fixed-order",
        "arithmetic contract drifted",
    )
    verifier.require(
        vectors["fixturesPath"] == "renderer/definite-layout-fixtures-v1.json",
        "fixture path drifted",
    )
    verifier.require(
        vectors["externalSources"]
        == {
            "layoutPreflight": "renderer/layout-preflight-fixtures-v1.json",
            "allKinds": "renderer/render-document-all-kinds-v1.json",
        },
        "external source paths drifted",
    )
    boundary = exact_members(
        vectors["boundary"],
        {
            "profileAvailability",
            "certificationStatus",
            "layoutImplementation",
            "worldTransformImplementation",
            "sceneImplementation",
            "rasterImplementation",
            "daemonOutputPath",
            "providerAttempts",
        },
        "boundary",
    )
    expected_boundary = {
        "profileAvailability": "NOT_REGISTERED",
        "certificationStatus": "NOT_CERTIFIED",
        "layoutImplementation": "DEFINITE_ABSOLUTE_LOCAL_BOX_KERNEL_ONLY",
        "worldTransformImplementation": "ABSENT",
        "sceneImplementation": "ABSENT",
        "rasterImplementation": "ABSENT",
        "daemonOutputPath": "UNWIRED",
        "providerAttempts": JsonNumber("0"),
    }
    verifier.require(boundary == expected_boundary, "honest boundary drifted")
    verifier.require(
        integer(boundary["providerAttempts"], "provider attempts") == 0,
        "provider attempts must be zero",
    )
    verifier.require(
        fixtures["fixtureVersion"] == "renderweave-definite-layout-fixtures/1",
        "fixture identity drifted",
    )
    verifier.require(
        set(fixtures["documents"])
        == {"fixedRect", "nestedFrame", "fillNested", "degenerateFrame"},
        "fixture inventory drifted",
    )
    verifier.require(
        layout_preflight_fixtures["fixtureVersion"]
        == "renderweave-layout-preflight-fixtures/1",
        "layout preflight fixture identity drifted",
    )
    verifier.require(len(vectors["laidOutCases"]) == 6, "laid-out case count drifted")
    verifier.require(
        len(vectors["unsupportedCases"]) == 9,
        "unsupported case count drifted",
    )

    seen: set[str] = set()
    passed = 0
    for case in vectors["laidOutCases"]:
        case_id = text(case["id"], "case id")
        verifier.require(case_id not in seen, f"duplicate case id: {case_id}")
        seen.add(case_id)
        document = case_document(
            case, fixtures, layout_preflight_fixtures, all_kinds
        )
        occurrence_count = validate_occurrence_sequence(document)
        actual = DefiniteLayouter().run(document)
        expected = expected_entries(case["expected"]["entries"])
        verifier.require(
            len(actual) == occurrence_count,
            f"{case_id}: output/occurrence cardinality drifted",
        )
        verifier.require(actual == expected, f"{case_id}: exact box bits drifted")
        passed += 1

    for case in vectors["unsupportedCases"]:
        case_id = text(case["id"], "case id")
        verifier.require(case_id not in seen, f"duplicate case id: {case_id}")
        seen.add(case_id)
        document = case_document(
            case, fixtures, layout_preflight_fixtures, all_kinds
        )
        validate_occurrence_sequence(document)
        try:
            DefiniteLayouter().run(document)
        except Unsupported as unsupported:
            expected = exact_members(
                case["expected"], {"feature", "occurrenceId"}, "unsupported result"
            )
            verifier.require(
                unsupported.feature == text(expected["feature"], "unsupported feature")
                and unsupported.occurrence_id
                == text(expected["occurrenceId"], "unsupported occurrence"),
                f"{case_id}: unsupported boundary drifted",
            )
            passed += 1
        else:
            raise VerificationFailure(f"{case_id}: unsupported case produced a layout")

    return {
        "verifier": "renderweave-definite-layout-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "laidOutCases": len(vectors["laidOutCases"]),
        "unsupportedCases": len(vectors["unsupportedCases"]),
        "total": len(seen),
        "passed": passed,
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(vector_raw).hexdigest(),
        "fixturesSha256": "sha256:" + hashlib.sha256(fixture_raw).hexdigest(),
        "layoutPreflightFixturesSha256": "sha256:"
        + hashlib.sha256(layout_preflight_fixture_raw).hexdigest(),
        "allKindsSha256": "sha256:" + hashlib.sha256(all_kinds_raw).hexdigest(),
        "layoutProfile": authority["layoutProfile"],
        "profileAvailability": boundary["profileAvailability"],
        "certificationStatus": boundary["certificationStatus"],
        "layoutImplementation": boundary["layoutImplementation"],
        "worldTransformImplementation": boundary["worldTransformImplementation"],
        "sceneImplementation": boundary["sceneImplementation"],
        "rasterImplementation": boundary["rasterImplementation"],
        "daemonOutputPath": boundary["daemonOutputPath"],
        "providerAttempts": integer(boundary["providerAttempts"], "provider attempts"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--layout-preflight-fixtures", type=Path, required=True)
    parser.add_argument("--all-kinds", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        report = verify(
            arguments.vectors,
            arguments.fixtures,
            arguments.layout_preflight_fixtures,
            arguments.all_kinds,
        )
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(
            json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n"
        )
        print(
            f"Definite layout independent replay: {report['passed']}/{report['total']} "
            f"cases, {report['checks']} checks, Profile={report['profileAvailability']}, "
            f"Layout={report['layoutImplementation']}"
        )
        return 0
    except (
        OSError,
        VerificationFailure,
        KeyError,
        IndexError,
        TypeError,
    ) as error:
        print(f"Definite layout independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
