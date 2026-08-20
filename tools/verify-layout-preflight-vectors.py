#!/usr/bin/env python3
"""Independent standard-library replay for the static Layout Profile preflight vectors."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import sys
from dataclasses import dataclass, field
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


def load_strict(raw: bytes, label: str) -> Any:
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
class Problem(Exception):
    code: str
    occurrence_id: str
    property: str
    parameters: dict[str, str] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "occurrenceId": self.occurrence_id,
            "property": self.property,
            "parameters": self.parameters,
        }


PLAIN_DECIMAL6 = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,6})?")
SCALE = 1_000_000
TRACK_LIMIT = 64


def decimal6(value: Any, occurrence: str, prop: str) -> int:
    if not isinstance(value, JsonNumber) or PLAIN_DECIMAL6.fullmatch(value.token) is None:
        raise Problem("LAYOUT_NUMERIC_ERROR", occurrence, prop)
    negative = value.token.startswith("-")
    unsigned = value.token[1:] if negative else value.token
    whole, separator, fraction = unsigned.partition(".")
    scaled = int(whole) * SCALE
    if separator:
        scaled += int(fraction) * 10 ** (6 - len(fraction))
    if negative:
        if scaled == 0:
            raise Problem("LAYOUT_NUMERIC_ERROR", occurrence, prop)
        scaled = -scaled
    return scaled


def required_decimal(obj: dict[str, Any], member: str, occurrence: str, prop: str) -> int:
    return decimal6(obj.get(member), occurrence, prop)


def positive(obj: dict[str, Any], member: str, occurrence: str, prop: str) -> int:
    value = required_decimal(obj, member, occurrence, prop)
    if value <= 0:
        raise Problem("LAYOUT_CONSTRAINT_INVALID", occurrence, prop)
    return value


def nonnegative(obj: dict[str, Any], member: str, occurrence: str, prop: str) -> int:
    value = required_decimal(obj, member, occurrence, prop)
    if value < 0:
        raise Problem("LAYOUT_CONSTRAINT_INVALID", occurrence, prop)
    return value


def occurrence(obj: dict[str, Any]) -> str:
    value = obj.get("occurrenceId")
    if not isinstance(value, str):
        raise Problem("LAYOUT_CONSTRAINT_INVALID", "rwocc_0000000000000000", "occurrenceId")
    return value


def object_value(value: Any, occurrence_id: str, prop: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise Problem("LAYOUT_CONSTRAINT_INVALID", occurrence_id, prop)
    return value


def array_value(value: Any, occurrence_id: str, prop: str) -> list[Any]:
    if not isinstance(value, list):
        raise Problem("LAYOUT_CONSTRAINT_INVALID", occurrence_id, prop)
    return value


def size_mode(placement: dict[str, Any], member: str, occurrence_id: str) -> str:
    value = placement.get(member)
    if value not in {"FIXED", "HUG_CONTENT", "FILL"}:
        raise Problem("LAYOUT_CONSTRAINT_INVALID", occurrence_id, f"placement.{member}")
    return value


def integer_member(obj: dict[str, Any], member: str, occurrence_id: str, prop: str) -> int:
    value = obj.get(member)
    try:
        return integer(value, prop)
    except VerificationFailure as error:
        raise Problem("LAYOUT_CONSTRAINT_INVALID", occurrence_id, prop) from error


@dataclass
class Summary:
    occurrence_count: int = 0
    tree_edge_count: int = 0
    max_depth: int = 0
    grid_count: int = 0
    grid_track_count: int = 0
    grid_cell_count: int = 0

    def as_dict(self) -> dict[str, int]:
        return {
            "occurrenceCount": self.occurrence_count,
            "treeEdgeCount": self.tree_edge_count,
            "maxDepth": self.max_depth,
            "gridCount": self.grid_count,
            "gridTrackCount": self.grid_track_count,
            "gridCellCount": self.grid_cell_count,
        }


class LayoutPreflight:
    def __init__(self) -> None:
        self.summary = Summary()

    def run(self, document: dict[str, Any]) -> Summary:
        self.visit_canvas(object_value(document.get("canvas"), "rwocc_0000000000000000", "canvas"), 1)
        return self.summary

    def reserve_occurrence(self, depth: int) -> None:
        self.summary.occurrence_count += 1
        self.summary.max_depth = max(self.summary.max_depth, depth)

    def reserve_edge(self) -> None:
        self.summary.tree_edge_count += 1

    def visit_canvas(self, canvas: dict[str, Any], depth: int) -> None:
        current = occurrence(canvas)
        self.reserve_occurrence(depth)
        positive(canvas, "widthPt", current, "widthPt")
        positive(canvas, "heightPt", current, "heightPt")
        if "bleed" in canvas:
            bleed = object_value(canvas["bleed"], current, "bleed")
            for member in ("topPt", "rightPt", "bottomPt", "leftPt"):
                nonnegative(bleed, member, current, f"bleed.{member}")
        for child in array_value(canvas.get("children"), current, "children"):
            self.reserve_edge()
            self.visit_node(object_value(child, current, "children"), depth + 1, ("canvas",))

    def visit_node(self, node: dict[str, Any], depth: int, parent: tuple[Any, ...]) -> None:
        current = occurrence(node)
        self.reserve_occurrence(depth)
        kind = node.get("kind")
        if not isinstance(kind, str):
            raise Problem("LAYOUT_CONSTRAINT_INVALID", current, "kind")
        placement = object_value(node.get("placement"), current, "placement")
        width_mode = size_mode(placement, "widthMode", current)
        height_mode = size_mode(placement, "heightMode", current)
        self.validate_capability(kind, width_mode, height_mode, current)
        self.validate_placement_numbers(placement, current)
        if kind == "group":
            for member in ("minWidthPt", "minHeightPt", "maxWidthPt", "maxHeightPt"):
                if member in placement:
                    raise Problem("LAYOUT_CONSTRAINT_INVALID", current, f"placement.{member}")
        self.validate_axis(placement, width_mode, "Width", current)
        self.validate_axis(placement, height_mode, "Height", current)
        self.validate_parent(parent, placement, width_mode, height_mode, current)

        if "maxLines" in node:
            max_lines = integer_member(node, "maxLines", current, "maxLines")
            if max_lines == 0 or node.get("overflow") == "VISIBLE":
                raise Problem("LAYOUT_CONSTRAINT_INVALID", current, "maxLines")
        if kind == "qrCode" and width_mode == "FIXED" and height_mode == "FIXED":
            width = required_decimal(placement, "widthPt", current, "placement.widthPt")
            height = required_decimal(placement, "heightPt", current, "placement.heightPt")
            if width != height:
                raise Problem("LAYOUT_CONSTRAINT_INVALID", current, "placement.heightPt")

        if kind == "compositionViewport":
            self.reserve_edge()
            self.visit_canvas(object_value(node.get("sourceCanvas"), current, "sourceCanvas"), depth + 1)
        elif kind in {"group", "frame"}:
            self.visit_children(node, depth, ("absolute", kind, width_mode, height_mode), current)
        elif kind == "stack":
            if node.get("direction") not in {"ROW", "COLUMN"}:
                raise Problem("LAYOUT_CONSTRAINT_INVALID", current, "direction")
            nonnegative(node, "gapPt", current, "gapPt")
            self.visit_children(node, depth, ("stack", width_mode, height_mode), current)
        elif kind == "grid":
            nonnegative(node, "rowGapPt", current, "rowGapPt")
            nonnegative(node, "columnGapPt", current, "columnGapPt")
            rows = self.validate_tracks(node, "rows", height_mode, current)
            columns = self.validate_tracks(node, "columns", width_mode, current)
            children = array_value(node.get("children"), current, "children")
            self.summary.grid_count += 1
            self.summary.grid_track_count += len(rows) + len(columns)
            self.summary.grid_cell_count += len(children)
            self.visit_children(node, depth, ("grid", rows, columns), current)

    def visit_children(
        self,
        node: dict[str, Any],
        depth: int,
        parent: tuple[Any, ...],
        current: str,
    ) -> None:
        for child in array_value(node.get("children"), current, "children"):
            self.reserve_edge()
            self.visit_node(object_value(child, current, "children"), depth + 1, parent)

    @staticmethod
    def validate_capability(kind: str, width: str, height: str, current: str) -> None:
        def allowed(mode: str) -> bool:
            if kind == "group":
                return mode == "HUG_CONTENT"
            if kind in {"rect", "ellipse", "qrCode", "barcode"}:
                return mode != "HUG_CONTENT"
            return True

        if not allowed(width):
            raise Problem("LAYOUT_CONSTRAINT_INVALID", current, "placement.widthMode")
        if not allowed(height):
            raise Problem("LAYOUT_CONSTRAINT_INVALID", current, "placement.heightMode")
        if kind == "image" and width == "HUG_CONTENT" and height == "HUG_CONTENT":
            raise Problem("LAYOUT_CONSTRAINT_INVALID", current, "placement.heightMode")

    @staticmethod
    def validate_placement_numbers(placement: dict[str, Any], current: str) -> None:
        for member in (
            "xPt", "yPt", "widthPt", "heightPt", "minWidthPt", "minHeightPt",
            "maxWidthPt", "maxHeightPt", "rightInsetPt", "bottomInsetPt",
            "marginTopPt", "marginRightPt", "marginBottomPt", "marginLeftPt", "fillWeight",
        ):
            if member in placement:
                required_decimal(placement, member, current, f"placement.{member}")
        if "fillWeight" in placement:
            positive(placement, "fillWeight", current, "placement.fillWeight")

    @staticmethod
    def validate_axis(placement: dict[str, Any], mode: str, axis: str, current: str) -> None:
        lower = axis.lower()
        size_name = f"{lower}Pt"
        min_name = f"min{axis}Pt"
        max_name = f"max{axis}Pt"
        minimum = required_decimal(placement, min_name, current, f"placement.{min_name}") if min_name in placement else None
        maximum = required_decimal(placement, max_name, current, f"placement.{max_name}") if max_name in placement else None
        if minimum is not None and minimum < 0:
            raise Problem("LAYOUT_CONSTRAINT_INVALID", current, f"placement.{min_name}")
        if maximum is not None and maximum <= 0:
            raise Problem("LAYOUT_CONSTRAINT_INVALID", current, f"placement.{max_name}")
        if minimum is not None and maximum is not None and minimum > maximum:
            raise Problem("LAYOUT_CONSTRAINT_INVALID", current, f"placement.{min_name}")
        if mode == "FIXED":
            size = required_decimal(placement, size_name, current, f"placement.{size_name}")
            if size <= 0 or (minimum is not None and size < minimum) or (maximum is not None and size > maximum):
                raise Problem("LAYOUT_CONSTRAINT_INVALID", current, f"placement.{size_name}")

    @staticmethod
    def validate_parent(
        parent: tuple[Any, ...],
        placement: dict[str, Any],
        width: str,
        height: str,
        current: str,
    ) -> None:
        if parent[0] == "canvas":
            return
        if parent[0] == "absolute":
            _, kind, parent_width, parent_height = parent
            if kind in {"group", "frame"}:
                reject_hug_fill(parent_width, width, current, "placement.widthMode")
                reject_hug_fill(parent_height, height, current, "placement.heightMode")
            return
        if parent[0] == "stack":
            _, parent_width, parent_height = parent
            reject_hug_fill(parent_width, width, current, "placement.widthMode")
            reject_hug_fill(parent_height, height, current, "placement.heightMode")
            return
        _, rows, columns = parent
        row = integer_member(placement, "row", current, "placement.row")
        column = integer_member(placement, "column", current, "placement.column")
        row_span = integer_member(placement, "rowSpan", current, "placement.rowSpan")
        column_span = integer_member(placement, "columnSpan", current, "placement.columnSpan")
        validate_range(row, row_span, len(rows), current, "row", "rowSpan")
        validate_range(column, column_span, len(columns), current, "column", "columnSpan")
        if width == "FILL" and "AUTO" in columns[column : column + column_span]:
            raise Problem("LAYOUT_CYCLE", current, "placement.widthMode")
        if height == "FILL" and "AUTO" in rows[row : row + row_span]:
            raise Problem("LAYOUT_CYCLE", current, "placement.heightMode")

    @staticmethod
    def validate_tracks(node: dict[str, Any], member: str, axis_mode: str, current: str) -> list[str]:
        tracks = array_value(node.get(member), current, member)
        if len(tracks) > TRACK_LIMIT:
            raise Problem(
                "LAYOUT_BUDGET_EXCEEDED",
                current,
                member,
                {"limitId": "designDsl.gridTracksPerAxis"},
            )
        kinds: list[str] = []
        for index, raw in enumerate(tracks):
            track = object_value(raw, current, member)
            kind = track.get("type")
            if kind == "FIXED":
                positive(track, "valuePt", current, f"{member}[{index}].valuePt")
            elif kind == "FRACTION":
                positive(track, "weight", current, f"{member}[{index}].weight")
                if axis_mode == "HUG_CONTENT":
                    raise Problem("LAYOUT_CONSTRAINT_INVALID", current, f"{member}[{index}].type")
            elif kind != "AUTO":
                raise Problem("LAYOUT_CONSTRAINT_INVALID", current, f"{member}[{index}].type")
            kinds.append(kind)
        return kinds


def reject_hug_fill(parent: str, child: str, current: str, prop: str) -> None:
    if parent == "HUG_CONTENT" and child == "FILL":
        raise Problem("LAYOUT_CYCLE", current, prop)


def validate_range(start: int, span: int, count: int, current: str, start_prop: str, span_prop: str) -> None:
    if start >= count:
        raise Problem("LAYOUT_CONSTRAINT_INVALID", current, f"placement.{start_prop}")
    if span <= 0 or start + span > count:
        raise Problem("LAYOUT_CONSTRAINT_INVALID", current, f"placement.{span_prop}")


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
        pointer = mutation["pointer"]
        if operation == "repeat":
            parent, token = resolve_parent(document, pointer)
            parent[token] = [copy.deepcopy(mutation["value"]) for _ in range(integer(mutation["count"], "repeat count"))]
        elif operation == "append":
            resolve_pointer(document, pointer).append(copy.deepcopy(mutation["value"]))
        else:
            parent, token = resolve_parent(document, pointer)
            if operation == "remove":
                del parent[token]
            elif operation in {"add", "replace"}:
                parent[token] = copy.deepcopy(mutation["value"])
            else:
                raise VerificationFailure(f"unknown mutation operation: {operation}")


def case_document(
    case: dict[str, Any],
    fixtures: dict[str, Any],
    vectors: dict[str, Any],
    all_kinds: dict[str, Any],
) -> dict[str, Any]:
    base = case["baseCase"]
    document = copy.deepcopy(all_kinds if base == "allKinds" else fixtures["documents"][base])
    if "preset" in case:
        apply_mutations(document, vectors["presets"][case["preset"]])
    apply_mutations(document, case.get("mutations", []))
    return document


def expected_summary(raw: dict[str, Any]) -> dict[str, int]:
    return {key: integer(value, key) for key, value in raw.items()}


def expected_problem(raw: dict[str, Any]) -> dict[str, Any]:
    parameters = exact_members(raw["parameters"], set(raw["parameters"]), "problem parameters")
    return {
        "code": text(raw["code"], "problem code"),
        "occurrenceId": text(raw["occurrenceId"], "problem occurrence"),
        "property": text(raw["property"], "problem property"),
        "parameters": {key: text(value, f"problem parameter {key}") for key, value in parameters.items()},
    }


def verify(vectors_path: Path, fixtures_path: Path, all_kinds_path: Path) -> dict[str, Any]:
    vector_raw = vectors_path.read_bytes()
    fixture_raw = fixtures_path.read_bytes()
    all_kinds_raw = all_kinds_path.read_bytes()
    vectors = load_strict(vector_raw, "vectors")
    fixtures = load_strict(fixture_raw, "fixtures")
    all_kinds = load_strict(all_kinds_raw, "allKinds")
    verifier = Verifier()

    exact_members(
        vectors,
        {"vectorVersion", "authorityContext", "boundary", "fixturesPath", "externalSources", "presets", "positiveCases", "negativeCases"},
        "vector manifest",
    )
    verifier.require(vectors["vectorVersion"] == "renderweave-layout-preflight-vectors/1", "vector identity drifted")
    authority = exact_members(
        vectors["authorityContext"],
        {"layoutProfile", "documentContract", "gridTracksPerAxisLimit", "numericEncoding"},
        "authority context",
    )
    verifier.require(authority["layoutProfile"] == "renderweave-layout/1.0", "Layout Profile drifted")
    verifier.require(authority["documentContract"] == "renderweave-render-node-contract-v1/2", "document contract drifted")
    verifier.require(integer(authority["gridTracksPerAxisLimit"], "track limit") == TRACK_LIMIT, "track limit drifted")
    verifier.require(authority["numericEncoding"] == "canonical-plain-decimal6", "numeric encoding drifted")
    verifier.require(vectors["fixturesPath"] == "renderer/layout-preflight-fixtures-v1.json", "fixture path drifted")
    verifier.require(vectors["externalSources"] == {"allKinds": "renderer/render-document-all-kinds-v1.json"}, "source path drifted")
    boundary = exact_members(
        vectors["boundary"],
        {"profileAvailability", "certificationStatus", "layoutImplementation", "rasterImplementation", "daemonOutputPath", "providerAttempts"},
        "boundary",
    )
    expected_boundary = {
        "profileAvailability": "NOT_REGISTERED",
        "certificationStatus": "NOT_CERTIFIED",
        "layoutImplementation": "STATIC_PREFLIGHT_ONLY",
        "rasterImplementation": "ABSENT",
        "daemonOutputPath": "UNWIRED",
        "providerAttempts": JsonNumber("0"),
    }
    verifier.require(boundary == expected_boundary, "honest boundary drifted")
    verifier.require(integer(boundary["providerAttempts"], "provider attempts") == 0, "provider attempts must be zero")
    verifier.require(fixtures["fixtureVersion"] == "renderweave-layout-preflight-fixtures/1", "fixture identity drifted")
    verifier.require(set(fixtures["documents"]) == {"minimal", "group", "frame", "stack", "grid"}, "fixture inventory drifted")
    verifier.require(len(vectors["positiveCases"]) == 7, "positive case count drifted")
    verifier.require(len(vectors["negativeCases"]) == 25, "negative case count drifted")

    seen: set[str] = set()
    passed = 0
    for case in vectors["positiveCases"]:
        case_id = text(case["id"], "case id")
        verifier.require(case_id not in seen, f"duplicate case id: {case_id}")
        seen.add(case_id)
        actual = LayoutPreflight().run(case_document(case, fixtures, vectors, all_kinds)).as_dict()
        verifier.require(actual == expected_summary(case["expected"]), f"{case_id}: summary drifted")
        passed += 1

    for case in vectors["negativeCases"]:
        case_id = text(case["id"], "case id")
        verifier.require(case_id not in seen, f"duplicate case id: {case_id}")
        seen.add(case_id)
        try:
            LayoutPreflight().run(case_document(case, fixtures, vectors, all_kinds))
        except Problem as problem:
            verifier.require(problem.as_dict() == expected_problem(case["expected"]), f"{case_id}: problem drifted")
            passed += 1
        else:
            raise VerificationFailure(f"{case_id}: negative case passed")

    return {
        "verifier": "renderweave-layout-preflight-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "positiveCases": len(vectors["positiveCases"]),
        "negativeCases": len(vectors["negativeCases"]),
        "total": len(seen),
        "passed": passed,
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(vector_raw).hexdigest(),
        "fixturesSha256": "sha256:" + hashlib.sha256(fixture_raw).hexdigest(),
        "allKindsSha256": "sha256:" + hashlib.sha256(all_kinds_raw).hexdigest(),
        "layoutProfile": authority["layoutProfile"],
        "profileAvailability": boundary["profileAvailability"],
        "certificationStatus": boundary["certificationStatus"],
        "layoutImplementation": boundary["layoutImplementation"],
        "rasterImplementation": boundary["rasterImplementation"],
        "daemonOutputPath": boundary["daemonOutputPath"],
        "providerAttempts": integer(boundary["providerAttempts"], "provider attempts"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--all-kinds", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        report = verify(arguments.vectors, arguments.fixtures, arguments.all_kinds)
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(
            f"Layout preflight independent replay: {report['passed']}/{report['total']} cases, "
            f"{report['checks']} checks, Profile={report['profileAvailability']}, "
            f"Layout={report['layoutImplementation']}"
        )
        return 0
    except (OSError, VerificationFailure, KeyError, IndexError, TypeError) as error:
        print(f"Layout preflight independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
