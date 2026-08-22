#!/usr/bin/env python3
"""Independent replay for definite boxes and resource-free intrinsic layout."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
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


@dataclass(frozen=True)
class HugOppositeAxisOffer:
    source: str
    size: float


@dataclass(frozen=True)
class StackChildMeasurement:
    width: float
    height: float
    margin_top: float
    margin_right: float
    margin_bottom: float
    margin_left: float
    align_self: str
    main_fill: bool
    deferred_cross_hug_after_main_fill: bool

    def main_size(self, direction: str) -> float:
        return self.width if direction == "ROW" else self.height

    def main_leading_margin(self, direction: str) -> float:
        return self.margin_left if direction == "ROW" else self.margin_top

    def main_trailing_margin(self, direction: str) -> float:
        return self.margin_right if direction == "ROW" else self.margin_bottom

    def cross_size(self, direction: str) -> float:
        return self.height if direction == "ROW" else self.width

    def cross_leading_margin(self, direction: str) -> float:
        return self.margin_top if direction == "ROW" else self.margin_left

    def cross_trailing_margin(self, direction: str) -> float:
        return self.margin_bottom if direction == "ROW" else self.margin_right

    def with_main_size(self, direction: str, size: float) -> StackChildMeasurement:
        return StackChildMeasurement(
            size if direction == "ROW" else self.width,
            self.height if direction == "ROW" else size,
            self.margin_top,
            self.margin_right,
            self.margin_bottom,
            self.margin_left,
            self.align_self,
            self.main_fill,
            self.deferred_cross_hug_after_main_fill,
        )

    def with_cross_size(self, direction: str, size: float) -> StackChildMeasurement:
        return StackChildMeasurement(
            self.width if direction == "ROW" else size,
            size if direction == "ROW" else self.height,
            self.margin_top,
            self.margin_right,
            self.margin_bottom,
            self.margin_left,
            self.align_self,
            self.main_fill,
            self.deferred_cross_hug_after_main_fill,
        )


@dataclass(frozen=True)
class StackMeasurementSpace:
    width: float | None
    height: float | None

    @classmethod
    def definite(cls, content_box: Box) -> StackMeasurementSpace:
        return cls(content_box.width, content_box.height)

    @classmethod
    def cross_hug(
        cls, direction: str, main_available: float
    ) -> StackMeasurementSpace:
        return (
            cls(main_available, None)
            if direction == "ROW"
            else cls(None, main_available)
        )

    @classmethod
    def main_hug(
        cls, direction: str, cross_available: float
    ) -> StackMeasurementSpace:
        return (
            cls(None, cross_available)
            if direction == "ROW"
            else cls(cross_available, None)
        )

    def main_available(self, direction: str) -> float | None:
        return self.width if direction == "ROW" else self.height


@dataclass(frozen=True)
class ResolvedStackChildren:
    direction: str
    gap: float
    leading: float
    between: list[float]
    measurements: list[StackChildMeasurement | Unsupported]


@dataclass(frozen=True)
class DefiniteGridAxis:
    origins: list[float]
    sizes: list[float]
    gap: float

    def cell(self, start: int, span: int) -> tuple[float, float]:
        size = 0.0
        for index in range(start, start + span):
            size += self.sizes[index]
            if index + 1 < start + span:
                size += self.gap
        return self.origins[start], size


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
            self.visit_absolute_node(object_value(child, f"{current} child"), canvas_box)
        return self.entries

    def visit_absolute_node(self, node: dict[str, Any], parent_content: Box) -> None:
        current = occurrence(node)
        kind = text(node.get("kind"), f"{current} kind")
        role = definite_node_role(kind, current)

        placement = object_value(node.get("placement"), f"{current} placement")
        if placement.get("type") != "ABSOLUTE":
            raise Unsupported("NON_ABSOLUTE_PLACEMENT", current)
        width_mode = placement.get("widthMode")
        height_mode = placement.get("heightMode")
        if width_mode not in {"FIXED", "HUG_CONTENT", "FILL"} or height_mode not in {
            "FIXED",
            "HUG_CONTENT",
            "FILL",
        }:
            raise VerificationFailure(f"{current} invalid definite size mode")

        authored_x = required_decimal(placement, "xPt", current, "placement.xPt")
        authored_y = required_decimal(placement, "yPt", current, "placement.yPt")
        width = (
            resource_free_hug_axis(
                node,
                role,
                placement,
                "Width",
                current,
                HugOppositeAxisOffer("ABSOLUTE_PARENT_CONTENT", parent_content.height),
            )
            if width_mode == "HUG_CONTENT"
            else definite_axis_size(
                placement,
                text(width_mode, "width mode"),
                parent_content.width,
                authored_x,
                "Width",
                "rightInsetPt",
                current,
            )
        )
        height = (
            resource_free_hug_axis(
                node,
                role,
                placement,
                "Height",
                current,
                HugOppositeAxisOffer("ABSOLUTE_PARENT_CONTENT", parent_content.width),
            )
            if height_mode == "HUG_CONTENT"
            else definite_axis_size(
                placement,
                text(height_mode, "height mode"),
                parent_content.height,
                authored_y,
                "Height",
                "bottomInsetPt",
                current,
            )
        )
        layout_box = Box(
            parent_content.x + authored_x,
            parent_content.y + authored_y,
            width,
            height,
        )
        self.emit_positioned_node(node, kind, role, layout_box)

    def visit_stack_child(
        self,
        node: dict[str, Any],
        measured: StackChildMeasurement | Unsupported,
        layout_box: Box,
    ) -> None:
        current = occurrence(node)
        kind = text(node.get("kind"), f"{current} kind")
        role = definite_node_role(kind, current)
        if isinstance(measured, Unsupported):
            raise measured
        self.emit_positioned_node(node, kind, role, layout_box)

    def emit_positioned_node(
        self, node: dict[str, Any], kind: str, role: str, layout_box: Box
    ) -> None:
        current = occurrence(node)
        if role in {"FRAME", "STACK", "GRID"}:
            content_box = container_content_box(node, layout_box, current)
            self.entries.append(
                {
                    "occurrenceId": current,
                    "kind": kind,
                    "layoutBox": layout_box.bits(),
                    "contentBox": content_box.bits(),
                }
            )
            if role == "FRAME":
                for child in array_value(node.get("children"), f"{current} children"):
                    self.visit_absolute_node(
                        object_value(child, f"{current} child"), content_box
                    )
            elif role == "STACK":
                self.visit_stack_children(node, content_box)
            else:
                self.visit_grid_children(node, content_box)
        elif role == "GROUP":
            self.entries.append(
                {
                    "occurrenceId": current,
                    "kind": kind,
                    "layoutBox": layout_box.bits(),
                    "contentBox": None,
                }
            )
            self.visit_group_children(node, layout_box)
        else:
            self.entries.append(
                {
                    "occurrenceId": current,
                    "kind": kind,
                    "layoutBox": layout_box.bits(),
                    "contentBox": None,
                }
            )

    def visit_group_children(self, group: dict[str, Any], layout_box: Box) -> None:
        current = occurrence(group)
        children = array_value(group.get("children"), f"{current} children")
        if not children:
            return
        horizontal = resource_free_group_hug_axis_union(group, "Width", current)
        vertical = resource_free_group_hug_axis_union(group, "Height", current)
        normalized_parent = Box(
            finite_group_normalization_value(
                layout_box.x - horizontal[0], current
            ),
            finite_group_normalization_value(layout_box.y - vertical[0], current),
            layout_box.width,
            layout_box.height,
        )
        for raw_child in children:
            self.visit_absolute_node(
                object_value(raw_child, f"{current} child"), normalized_parent
            )

    def visit_stack_children(self, stack: dict[str, Any], content_box: Box) -> None:
        current = occurrence(stack)
        children = array_value(stack.get("children"), f"{current} children")
        resolved = measure_and_allocate_stack_children(
            stack, StackMeasurementSpace.definite(content_box)
        )
        cursor = resolved.leading

        for index, (raw_child, measured) in enumerate(
            zip(children, resolved.measurements)
        ):
            child = object_value(raw_child, f"{current} child")
            if isinstance(measured, StackChildMeasurement):
                cursor += measured.main_leading_margin(resolved.direction)
                layout_box = stack_child_box(
                    content_box, measured, resolved.direction, cursor
                )
                cursor += measured.main_size(resolved.direction)
                cursor += measured.main_trailing_margin(resolved.direction)
                if index + 1 < len(children):
                    cursor += resolved.gap
                    cursor += resolved.between[index]
            else:
                layout_box = Box(content_box.x, content_box.y, 0.0, 0.0)
            self.visit_stack_child(child, measured, layout_box)

    def visit_grid_children(self, grid: dict[str, Any], content_box: Box) -> None:
        current = occurrence(grid)
        children = array_value(grid.get("children"), f"{current} children")
        # The frozen profile always solves columns first, then rows.
        columns = definite_grid_axis(
            grid,
            children,
            "COLUMN",
            content_box.x,
            content_box.width,
            current,
            None,
        )
        rows = definite_grid_axis(
            grid,
            children,
            "ROW",
            content_box.y,
            content_box.height,
            current,
            columns,
        )
        for raw_child in children:
            self.visit_grid_child(
                object_value(raw_child, f"{current} child"), columns, rows
            )

    def visit_grid_child(
        self,
        node: dict[str, Any],
        columns: DefiniteGridAxis,
        rows: DefiniteGridAxis,
    ) -> None:
        current = occurrence(node)
        kind = text(node.get("kind"), f"{current} kind")
        role = definite_node_role(kind, current)
        placement = object_value(node.get("placement"), f"{current} placement")
        if placement.get("type") != "GRID":
            raise Unsupported("NON_ABSOLUTE_PLACEMENT", current)
        width_mode = text(placement.get("widthMode"), f"{current} width mode")
        height_mode = text(placement.get("heightMode"), f"{current} height mode")
        if width_mode not in {"FIXED", "HUG_CONTENT", "FILL"} or height_mode not in {
            "FIXED",
            "HUG_CONTENT",
            "FILL",
        }:
            raise VerificationFailure(f"{current} invalid definite size mode")

        column = integer(placement.get("column"), f"{current} placement.column")
        column_span = integer(
            placement.get("columnSpan"), f"{current} placement.columnSpan"
        )
        row = integer(placement.get("row"), f"{current} placement.row")
        row_span = integer(
            placement.get("rowSpan"), f"{current} placement.rowSpan"
        )
        cell_x, cell_width = columns.cell(column, column_span)
        cell_y, cell_height = rows.cell(row, row_span)
        margin_top = required_decimal(
            placement, "marginTopPt", current, "placement.marginTopPt"
        )
        margin_right = required_decimal(
            placement, "marginRightPt", current, "placement.marginRightPt"
        )
        margin_bottom = required_decimal(
            placement, "marginBottomPt", current, "placement.marginBottomPt"
        )
        margin_left = required_decimal(
            placement, "marginLeftPt", current, "placement.marginLeftPt"
        )
        resolved_width_fill = resolved_grid_fill_outer_size(
            placement,
            width_mode,
            cell_width,
            margin_left,
            margin_right,
            "Width",
            current,
        )
        resolved_height_fill = resolved_grid_fill_outer_size(
            placement,
            height_mode,
            cell_height,
            margin_top,
            margin_bottom,
            "Height",
            current,
        )
        consumes_resolved_width_offer = (
            grid_row_hug_consumes_resolved_width_offer(
                node, role, placement, current
            )
        )
        x, width = grid_axis_arrangement(
            node,
            role,
            placement,
            width_mode,
            cell_x,
            cell_width,
            margin_left,
            margin_right,
            "Width",
            "horizontalAlignSelf",
            current,
            resolved_width_fill,
            resolved_height_fill
            if role == "FRAME" and width_mode == "HUG_CONTENT"
            else None,
        )
        y, height = grid_axis_arrangement(
            node,
            role,
            placement,
            height_mode,
            cell_y,
            cell_height,
            margin_top,
            margin_bottom,
            "Height",
            "verticalAlignSelf",
            current,
            resolved_height_fill,
            resolved_width_fill if consumes_resolved_width_offer else None,
        )
        self.emit_positioned_node(node, kind, role, Box(x, y, width, height))


def definite_node_role(kind: str, current: str) -> str:
    if kind == "frame":
        return "FRAME"
    if kind == "stack":
        return "STACK"
    if kind == "grid":
        return "GRID"
    if kind == "group":
        return "GROUP"
    if kind in SUPPORTED_LEAVES:
        return "LEAF"
    if kind == "compositionViewport":
        raise Unsupported("COMPOSITION_VIEWPORT", current)
    if kind in {"text", "image"}:
        raise Unsupported("RESOURCE_DEPENDENT_KIND", current)
    raise VerificationFailure(f"{current} unexpected kind {kind}")


def definite_grid_axis(
    grid: dict[str, Any],
    children: list[Any],
    axis: str,
    origin: float,
    available: float,
    current: str,
    resolved_columns: DefiniteGridAxis | None,
) -> DefiniteGridAxis:
    if axis == "COLUMN":
        tracks_member = "columns"
        gap_member = "columnGapPt"
    elif axis == "ROW":
        tracks_member = "rows"
        gap_member = "rowGapPt"
    else:
        raise VerificationFailure(f"invalid Grid axis: {axis}")
    gap = nonnegative_decimal(grid, gap_member, current, gap_member)
    tracks = array_value(grid.get(tracks_member), f"{current} {tracks_member}")
    sizes: list[float] = []
    auto_indices: list[int] = []
    fraction_indices: list[int] = []

    for index, raw_track in enumerate(tracks):
        track = object_value(raw_track, f"{current} {tracks_member}[{index}]")
        track_type = text(
            track.get("type"), f"{current} {tracks_member}[{index}].type"
        )
        if track_type == "FIXED":
            size = required_decimal(
                track,
                "valuePt",
                current,
                f"{tracks_member}[{index}].valuePt",
            )
            sizes.append(size)
        elif track_type == "AUTO":
            auto_indices.append(index)
            sizes.append(0.0)
        elif track_type == "FRACTION":
            required_decimal(
                track,
                "weight",
                current,
                f"{tracks_member}[{index}].weight",
            )
            fraction_indices.append(index)
            sizes.append(0.0)
        else:
            raise VerificationFailure(
                f"{current} invalid {tracks_member}[{index}].type"
            )
    # The Profile solves FIXED, then AUTO, then FRACTION. The complete authored
    # scan above keeps that stage order independent of authored track order.
    if auto_indices:
        apply_independent_grid_auto(
            children,
            axis,
            auto_indices,
            sizes,
            gap,
            current,
            resolved_columns,
        )
    if len(fraction_indices) > 1:
        raise Unsupported("GRID_FRACTION_TRACK", current)
    if fraction_indices:
        used_without_fraction = grid_span_extent(sizes, gap, 0, len(sizes))
        remaining = available - used_without_fraction
        sizes[fraction_indices[0]] = remaining if remaining > 0.0 else 0.0

    origins: list[float] = []
    cursor = origin
    for index, size in enumerate(sizes):
        origins.append(cursor)
        cursor += size
        if index + 1 < len(sizes):
            cursor += gap
    return DefiniteGridAxis(origins, sizes, gap)


def apply_independent_grid_auto(
    children: list[Any],
    axis: str,
    auto_indices: list[int],
    sizes: list[float],
    gap: float,
    grid_occurrence: str,
    resolved_columns: DefiniteGridAxis | None,
) -> None:
    if axis == "COLUMN":
        start_member = "column"
        span_member = "columnSpan"
        mode_member = "widthMode"
        size_member = "widthPt"
        leading_margin_member = "marginLeftPt"
        trailing_margin_member = "marginRightPt"
    elif axis == "ROW":
        start_member = "row"
        span_member = "rowSpan"
        mode_member = "heightMode"
        size_member = "heightPt"
        leading_margin_member = "marginTopPt"
        trailing_margin_member = "marginBottomPt"
    else:
        raise VerificationFailure(f"invalid Grid axis: {axis}")

    constraints: list[tuple[int, int, int, int, float]] = []
    for materialized_order, raw_child in enumerate(children):
        child = object_value(raw_child, f"{grid_occurrence} child")
        child_occurrence = occurrence(child)
        placement = object_value(
            child.get("placement"), f"{child_occurrence} placement"
        )
        if placement.get("type") != "GRID":
            raise Unsupported("NON_ABSOLUTE_PLACEMENT", child_occurrence)
        start = integer(
            placement.get(start_member),
            f"{child_occurrence} placement.{start_member}",
        )
        span = integer(
            placement.get(span_member),
            f"{child_occurrence} placement.{span_member}",
        )
        covered_auto_indices = [
            index for index in auto_indices if start <= index < start + span
        ]
        if not covered_auto_indices:
            continue
        if len(covered_auto_indices) > 1:
            raise Unsupported("GRID_AUTO_TRACK", grid_occurrence)
        auto_index = covered_auto_indices[0]

        mode = text(
            placement.get(mode_member),
            f"{child_occurrence} placement.{mode_member}",
        )
        if mode == "HUG_CONTENT":
            kind = text(child.get("kind"), f"{child_occurrence} kind")
            role = definite_node_role(kind, child_occurrence)
            opposite_axis_offer = grid_row_auto_opposite_offer(
                child,
                placement,
                role,
                axis,
                resolved_columns,
                child_occurrence,
            )
            size = resource_free_hug_axis(
                child,
                role,
                placement,
                "Width" if axis == "COLUMN" else "Height",
                child_occurrence,
                opposite_axis_offer,
            )
        elif mode == "FIXED":
            size = required_decimal(
                placement,
                size_member,
                child_occurrence,
                f"placement.{size_member}",
            )
        else:
            raise VerificationFailure(
                f"{child_occurrence} invalid placement.{mode_member} across AUTO"
            )
        leading_margin = required_decimal(
            placement,
            leading_margin_member,
            child_occurrence,
            f"placement.{leading_margin_member}",
        )
        trailing_margin = required_decimal(
            placement,
            trailing_margin_member,
            child_occurrence,
            f"placement.{trailing_margin_member}",
        )
        contribution = (size + leading_margin) + trailing_margin
        constraints.append(
            (
                span,
                start,
                materialized_order,
                auto_index,
                contribution if contribution > 0.0 else 0.0,
            )
        )

    constraints.sort(key=lambda constraint: constraint[:3])
    for span, start, _materialized_order, auto_index, contribution in constraints:
        occupied = grid_span_extent(sizes, gap, start, span)
        deficit = contribution - occupied
        if deficit > 0.0:
            sizes[auto_index] += deficit


def grid_row_auto_opposite_offer(
    node: dict[str, Any],
    placement: dict[str, Any],
    role: str,
    axis: str,
    resolved_columns: DefiniteGridAxis | None,
    current: str,
) -> HugOppositeAxisOffer | None:
    if (
        axis != "ROW"
        or resolved_columns is None
        or not grid_row_hug_consumes_resolved_width_offer(
            node, role, placement, current
        )
    ):
        return None
    column = integer(placement.get("column"), f"{current} placement.column")
    column_span = integer(
        placement.get("columnSpan"), f"{current} placement.columnSpan"
    )
    _, cell_width = resolved_columns.cell(column, column_span)
    margin_left = required_decimal(
        placement, "marginLeftPt", current, "placement.marginLeftPt"
    )
    margin_right = required_decimal(
        placement, "marginRightPt", current, "placement.marginRightPt"
    )
    outer_width = stack_axis_size(
        placement,
        "FILL",
        cell_width,
        margin_left,
        margin_right,
        "Width",
        current,
    )
    return HugOppositeAxisOffer("RESOLVED_OUTER", outer_width)


def grid_row_hug_consumes_resolved_width_offer(
    node: dict[str, Any],
    role: str,
    placement: dict[str, Any],
    current: str,
) -> bool:
    if (
        placement.get("widthMode") != "FILL"
        or placement.get("heightMode") != "HUG_CONTENT"
    ):
        return False
    if role in {"FRAME", "GRID"}:
        return True
    if role == "STACK":
        return text(node.get("direction"), f"{current} direction") == "ROW"
    return False


def grid_span_extent(
    sizes: list[float], gap: float, start: int, span: int
) -> float:
    extent = 0.0
    for index in range(start, start + span):
        extent += sizes[index]
        if index + 1 < start + span:
            extent += gap
    return extent


def grid_axis_arrangement(
    node: dict[str, Any],
    role: str,
    placement: dict[str, Any],
    mode: str,
    cell_origin: float,
    cell_size: float,
    leading_margin: float,
    trailing_margin: float,
    axis: str,
    alignment_member: str,
    current: str,
    resolved_fill_outer: float | None,
    opposite_fill_outer: float | None,
) -> tuple[float, float]:
    if mode == "HUG_CONTENT":
        size = resource_free_hug_axis(
            node,
            role,
            placement,
            axis,
            current,
            HugOppositeAxisOffer("RESOLVED_OUTER", opposite_fill_outer)
            if opposite_fill_outer is not None
            else None,
        )
    elif mode == "FILL":
        if resolved_fill_outer is None:
            raise VerificationFailure(f"{current} missing resolved Grid {axis} FILL")
        size = resolved_fill_outer
    else:
        size = stack_axis_size(
            placement,
            mode,
            cell_size,
            leading_margin,
            trailing_margin,
            axis,
            current,
        )
    if mode in {"FIXED", "HUG_CONTENT"}:
        alignment = text(
            placement.get(alignment_member), f"{current} {alignment_member}"
        )
        position = aligned_cross_position(
            cell_origin,
            cell_size,
            leading_margin,
            trailing_margin,
            size,
            alignment,
        )
    elif mode == "FILL":
        position = cell_origin + leading_margin
    else:
        raise VerificationFailure(f"{current} invalid placement.{axis}Mode")
    return position, size


def resolved_grid_fill_outer_size(
    placement: dict[str, Any],
    mode: str,
    cell_size: float,
    leading_margin: float,
    trailing_margin: float,
    axis: str,
    current: str,
) -> float | None:
    if mode != "FILL":
        return None
    return stack_axis_size(
        placement,
        mode,
        cell_size,
        leading_margin,
        trailing_margin,
        axis,
        current,
    )


def stack_distribution(
    justification: str, free: float, child_count: int
) -> tuple[float, list[float]]:
    leading = 0.0
    between = [0.0] * max(0, child_count - 1)
    if justification == "END":
        leading = free
    elif justification == "CENTER":
        leading = free / 2.0
    elif justification == "SPACE_BETWEEN" and child_count > 1:
        between = equal_binary64_slots(free, child_count - 1)
    elif justification == "SPACE_AROUND" and child_count > 0:
        slots = equal_binary64_slots(free, child_count)
        leading = slots[0] / 2.0
        between = [
            (slots[index] / 2.0) + (slots[index + 1] / 2.0)
            for index in range(child_count - 1)
        ]
    elif justification == "SPACE_EVENLY" and child_count > 0:
        slots = equal_binary64_slots(free, child_count + 1)
        leading = slots[0]
        between = slots[1:child_count]
    return leading, between


def equal_binary64_slots(total: float, count: int) -> list[float]:
    if count == 0:
        return []
    unit = total / float(count)
    remaining = total
    result: list[float] = []
    for index in range(count):
        slot = remaining if index + 1 == count else unit
        result.append(slot)
        remaining -= slot
    return result


def stack_child_box(
    parent: Box,
    child: StackChildMeasurement,
    direction: str,
    main_position: float,
) -> Box:
    if direction == "ROW":
        return Box(
            parent.x + main_position,
            aligned_cross_position(
                parent.y,
                parent.height,
                child.margin_top,
                child.margin_bottom,
                child.height,
                child.align_self,
            ),
            child.width,
            child.height,
        )
    return Box(
        aligned_cross_position(
            parent.x,
            parent.width,
            child.margin_left,
            child.margin_right,
            child.width,
            child.align_self,
        ),
        parent.y + main_position,
        child.width,
        child.height,
    )


def aligned_cross_position(
    parent_origin: float,
    parent_size: float,
    leading_margin: float,
    trailing_margin: float,
    child_size: float,
    alignment: str,
) -> float:
    interval = (parent_size - leading_margin) - trailing_margin
    if alignment == "START":
        extra = 0.0
    elif alignment == "CENTER":
        extra = (interval - child_size) / 2.0
    elif alignment == "END":
        extra = interval - child_size
    else:
        raise VerificationFailure(f"invalid Stack alignment: {alignment}")
    return (parent_origin + leading_margin) + extra


def measure_and_allocate_stack_children(
    stack: dict[str, Any], space: StackMeasurementSpace
) -> ResolvedStackChildren:
    current = occurrence(stack)
    direction = text(stack.get("direction"), f"{current} direction")
    if direction not in {"ROW", "COLUMN"}:
        raise VerificationFailure(f"{current} invalid Stack direction")
    justification = text(stack.get("justifyContent"), f"{current} justifyContent")
    if justification not in {
        "START",
        "CENTER",
        "END",
        "SPACE_BETWEEN",
        "SPACE_AROUND",
        "SPACE_EVENLY",
    }:
        raise VerificationFailure(f"{current} invalid Stack justification")
    gap = nonnegative_decimal(stack, "gapPt", current, "gapPt")
    children = array_value(stack.get("children"), f"{current} children")
    measurements: list[StackChildMeasurement | Unsupported] = []
    used_without_fill = 0.0
    fill_indices: list[int] = []
    for index, raw_child in enumerate(children):
        child = object_value(raw_child, f"{current} child")
        if stack_child_has_main_fill(child, direction):
            fill_indices.append(index)
        try:
            measured: StackChildMeasurement | Unsupported = measure_stack_child(
                child, space, direction
            )
        except Unsupported as error:
            measured = error
        if isinstance(measured, StackChildMeasurement):
            used_without_fill += measured.main_leading_margin(direction)
            if not measured.main_fill:
                used_without_fill += measured.main_size(direction)
            used_without_fill += measured.main_trailing_margin(direction)
        if index + 1 < len(children):
            used_without_fill += gap
        measurements.append(measured)

    available = space.main_available(direction)
    if available is None:
        raise VerificationFailure(f"{current} missing definite Stack main offer")
    if len(fill_indices) > 1:
        first_fill = fill_indices[0]
        child = object_value(children[first_fill], f"{current} child")
        measurements[first_fill] = Unsupported("STACK_MAIN_FILL", occurrence(child))
    elif fill_indices and isinstance(
        measurements[fill_indices[0]], StackChildMeasurement
    ):
        fill_index = fill_indices[0]
        child = object_value(children[fill_index], f"{current} child")
        child_occurrence = occurrence(child)
        placement = object_value(
            child.get("placement"), f"{child_occurrence} placement"
        )
        remaining = available - used_without_fill
        offered = remaining if remaining > 0.0 else 0.0
        size = clamp_flexible_axis(
            placement,
            offered,
            "Width" if direction == "ROW" else "Height",
            child_occurrence,
        )
        measured = measurements[fill_index]
        if not isinstance(measured, StackChildMeasurement):
            raise VerificationFailure(
                "a measurable singleton Stack main FILL must remain measurable"
            )
        measured = measured.with_main_size(direction, size)
        try:
            measurements[fill_index] = remeasure_stack_child_cross_hug_after_main_fill(
                child, measured, direction
            )
        except Unsupported as error:
            measurements[fill_index] = error

    occupied = used_without_fill
    if len(fill_indices) == 1:
        measured = measurements[fill_indices[0]]
        if isinstance(measured, StackChildMeasurement):
            occupied += measured.main_size(direction)
    occupied = occupied if occupied > 0.0 else 0.0
    remaining = available - occupied
    free = remaining if remaining > 0.0 else 0.0
    leading, between = stack_distribution(justification, free, len(children))
    return ResolvedStackChildren(direction, gap, leading, between, measurements)


def measure_stack_child(
    node: dict[str, Any], space: StackMeasurementSpace, direction: str
) -> StackChildMeasurement:
    current = occurrence(node)
    kind = text(node.get("kind"), f"{current} kind")
    role = definite_node_role(kind, current)
    placement = object_value(node.get("placement"), f"{current} placement")
    if placement.get("type") != "STACK":
        raise Unsupported("NON_ABSOLUTE_PLACEMENT", current)
    width_mode = text(placement.get("widthMode"), f"{current} width mode")
    height_mode = text(placement.get("heightMode"), f"{current} height mode")
    if width_mode not in {"FIXED", "HUG_CONTENT", "FILL"} or height_mode not in {
        "FIXED",
        "HUG_CONTENT",
        "FILL",
    }:
        raise VerificationFailure(f"{current} invalid definite size mode")
    main_mode = width_mode if direction == "ROW" else height_mode
    main_fill = main_mode == "FILL"
    deferred_role = (
        role == "FRAME"
        or (role == "GRID" and direction == "ROW")
        or role == "STACK"
    )
    deferred_cross_hug_after_main_fill = deferred_role and (
        (
            direction == "ROW"
            and width_mode == "FILL"
            and height_mode == "HUG_CONTENT"
        )
        or (
            direction == "COLUMN"
            and width_mode == "HUG_CONTENT"
            and height_mode == "FILL"
        )
    )

    margin_top = required_decimal(
        placement, "marginTopPt", current, "placement.marginTopPt"
    )
    margin_right = required_decimal(
        placement, "marginRightPt", current, "placement.marginRightPt"
    )
    margin_bottom = required_decimal(
        placement, "marginBottomPt", current, "placement.marginBottomPt"
    )
    margin_left = required_decimal(
        placement, "marginLeftPt", current, "placement.marginLeftPt"
    )
    resolved_cross_outer_offer: float | None
    if (
        role == "FRAME"
        and direction == "ROW"
        and width_mode == "HUG_CONTENT"
        and height_mode == "FILL"
    ):
        resolved_cross_outer_offer = stack_axis_size_from_offer(
            placement,
            "FILL",
            space.height,
            margin_top,
            margin_bottom,
            "Height",
            current,
        )
    elif (
        role == "FRAME"
        and direction == "COLUMN"
        and width_mode == "FILL"
        and height_mode == "HUG_CONTENT"
    ):
        resolved_cross_outer_offer = stack_axis_size_from_offer(
            placement,
            "FILL",
            space.width,
            margin_left,
            margin_right,
            "Width",
            current,
        )
    else:
        resolved_cross_outer_offer = None
    if (
        width_mode == "HUG_CONTENT"
        and deferred_cross_hug_after_main_fill
        and direction == "COLUMN"
    ):
        width = 0.0
    elif width_mode == "HUG_CONTENT":
        offer = (
            HugOppositeAxisOffer("RESOLVED_OUTER", resolved_cross_outer_offer)
            if direction == "ROW" and resolved_cross_outer_offer is not None
            else None
        )
        width = resource_free_hug_axis(
            node, role, placement, "Width", current, offer
        )
    elif direction == "ROW" and main_fill:
        width = 0.0
    elif (
        direction == "COLUMN"
        and width_mode == "FILL"
        and resolved_cross_outer_offer is not None
    ):
        width = resolved_cross_outer_offer
    else:
        width = stack_axis_size_from_offer(
            placement,
            width_mode,
            space.width,
            margin_left,
            margin_right,
            "Width",
            current,
        )
    if (
        height_mode == "HUG_CONTENT"
        and deferred_cross_hug_after_main_fill
        and direction == "ROW"
    ):
        height = 0.0
    elif height_mode == "HUG_CONTENT":
        offer = (
            HugOppositeAxisOffer("RESOLVED_OUTER", resolved_cross_outer_offer)
            if direction == "COLUMN" and resolved_cross_outer_offer is not None
            else None
        )
        height = resource_free_hug_axis(
            node, role, placement, "Height", current, offer
        )
    elif direction == "COLUMN" and main_fill:
        height = 0.0
    elif (
        direction == "ROW"
        and height_mode == "FILL"
        and resolved_cross_outer_offer is not None
    ):
        height = resolved_cross_outer_offer
    else:
        height = stack_axis_size_from_offer(
            placement,
            height_mode,
            space.height,
            margin_top,
            margin_bottom,
            "Height",
            current,
        )
    align_self = text(placement.get("alignSelf"), f"{current} alignSelf")
    if align_self not in {"START", "CENTER", "END"}:
        raise VerificationFailure(f"{current} invalid Stack alignment")
    return StackChildMeasurement(
        width,
        height,
        margin_top,
        margin_right,
        margin_bottom,
        margin_left,
        align_self,
        main_fill,
        deferred_cross_hug_after_main_fill,
    )


def remeasure_stack_child_cross_hug_after_main_fill(
    node: dict[str, Any], measurement: StackChildMeasurement, direction: str
) -> StackChildMeasurement:
    if not measurement.deferred_cross_hug_after_main_fill:
        return measurement
    current = occurrence(node)
    kind = text(node.get("kind"), f"{current} kind")
    role = definite_node_role(kind, current)
    placement = object_value(node.get("placement"), f"{current} placement")
    cross_axis = "Height" if direction == "ROW" else "Width"
    cross_size = resource_free_hug_axis(
        node,
        role,
        placement,
        cross_axis,
        current,
        HugOppositeAxisOffer("RESOLVED_OUTER", measurement.main_size(direction)),
    )
    return measurement.with_cross_size(direction, cross_size)


def stack_child_has_main_fill(node: dict[str, Any], direction: str) -> bool:
    placement = node.get("placement")
    if not isinstance(placement, dict) or placement.get("type") != "STACK":
        return False
    member = "widthMode" if direction == "ROW" else "heightMode"
    return placement.get(member) == "FILL"


def resource_free_hug_axis(
    node: dict[str, Any],
    role: str,
    placement: dict[str, Any],
    axis: str,
    current: str,
    opposite_axis_offer: HugOppositeAxisOffer | None,
) -> float:
    if role == "LEAF":
        raise Unsupported("HUG_CONTENT", current)
    children = array_value(node.get("children"), f"{current} children")
    if not children:
        return empty_container_hug_axis(node, role, placement, axis, current)
    if role == "GROUP":
        union = resource_free_group_hug_axis_union(node, axis, current)
        size = finite_group_union_value(union[1] - union[0], current)
        if size < 0.0:
            raise VerificationFailure(f"{current} invalid Group union")
        return size
    if role == "FRAME":
        content_extent = resource_free_frame_hug_content_extent(
            node, placement, axis, current, opposite_axis_offer
        )
    elif role == "STACK":
        content_extent = resource_free_stack_hug_content_extent(
            node, placement, axis, current, opposite_axis_offer
        )
    elif role == "GRID":
        content_extent = resource_free_grid_hug_content_extent(
            node, placement, axis, current, opposite_axis_offer
        )
    else:
        raise Unsupported("HUG_CONTENT", current)
    natural = container_outer_extent(node, axis, content_extent, current)
    return clamp_flexible_axis(placement, natural, axis, current)


def resource_free_frame_hug_content_extent(
    frame: dict[str, Any],
    placement: dict[str, Any],
    axis: str,
    current: str,
    opposite_axis_offer: HugOppositeAxisOffer | None,
) -> float:
    cross_axis_fill_offer = definite_frame_opposite_content_offer(
        frame, placement, axis, current, opposite_axis_offer
    )
    extent = 0.0
    for raw_child in array_value(frame.get("children"), f"{current} children"):
        child = object_value(raw_child, f"{current} child")
        interval = resource_free_absolute_child_axis_interval(
            child, axis, cross_axis_fill_offer
        )
        if interval[1] > extent:
            extent = interval[1]
    return extent


def definite_frame_opposite_content_offer(
    frame: dict[str, Any],
    placement: dict[str, Any],
    hug_axis: str,
    current: str,
    opposite_axis_offer: HugOppositeAxisOffer | None,
) -> float | None:
    if hug_axis == "Width":
        opposite_axis = "Height"
        mode_member = "heightMode"
        size_member = "heightPt"
        start_member = "yPt"
        end_inset_member = "bottomInsetPt"
    elif hug_axis == "Height":
        opposite_axis = "Width"
        mode_member = "widthMode"
        size_member = "widthPt"
        start_member = "xPt"
        end_inset_member = "rightInsetPt"
    else:
        raise VerificationFailure(f"{current} invalid HUG axis")
    mode = text(placement.get(mode_member), f"{current} placement.{mode_member}")
    if mode == "FIXED":
        outer_size = required_decimal(
            placement, size_member, current, f"placement.{size_member}"
        )
    elif mode == "FILL":
        if opposite_axis_offer is None:
            return None
        if opposite_axis_offer.source == "ABSOLUTE_PARENT_CONTENT":
            start = required_decimal(
                placement, start_member, current, f"placement.{start_member}"
            )
            outer_size = definite_axis_size(
                placement,
                mode,
                opposite_axis_offer.size,
                start,
                opposite_axis,
                end_inset_member,
                current,
            )
        elif opposite_axis_offer.source == "RESOLVED_OUTER":
            outer_size = opposite_axis_offer.size
        else:
            raise VerificationFailure(f"{current} invalid opposite-axis offer source")
    elif mode == "HUG_CONTENT":
        return None
    else:
        raise VerificationFailure(f"{current} invalid placement.{mode_member}")
    content_size = container_axis_content_size(
        frame, outer_size, opposite_axis, current
    )
    if not math.isfinite(content_size):
        raise VerificationFailure(
            f"{current} invalid definite opposite {opposite_axis} content offer"
        )
    return content_size


def container_axis_content_size(
    node: dict[str, Any], outer_size: float, axis: str, current: str
) -> float:
    if axis == "Width":
        leading_padding, trailing_padding = "leftPt", "rightPt"
    elif axis == "Height":
        leading_padding, trailing_padding = "topPt", "bottomPt"
    else:
        raise VerificationFailure(f"{current} invalid content axis")
    if "stroke" in node:
        stroke = object_value(node["stroke"], f"{current} stroke")
        stroke_width = nonnegative_decimal(
            stroke, "widthPt", current, "stroke.widthPt"
        )
    else:
        stroke_width = 0.0
    content_size = subtract_content_inset(outer_size, stroke_width)
    content_size = subtract_content_inset(content_size, stroke_width)
    padding = object_value(node.get("padding"), f"{current} padding")
    content_size = subtract_content_inset(
        content_size,
        nonnegative_decimal(
            padding,
            leading_padding,
            current,
            f"padding.{leading_padding}",
        ),
    )
    return subtract_content_inset(
        content_size,
        nonnegative_decimal(
            padding,
            trailing_padding,
            current,
            f"padding.{trailing_padding}",
        ),
    )


def resource_free_group_hug_axis_union(
    group: dict[str, Any], axis: str, current: str
) -> tuple[float, float]:
    union: tuple[float, float] | None = None
    for raw_child in array_value(group.get("children"), f"{current} children"):
        child = object_value(raw_child, f"{current} child")
        interval = resource_free_absolute_child_axis_interval(child, axis, None)
        if union is None:
            union = interval
        else:
            union = (min(union[0], interval[0]), max(union[1], interval[1]))
    if union is None:
        raise VerificationFailure(f"{current} invalid empty Group union")
    return union


def resource_free_absolute_child_axis_interval(
    child: dict[str, Any], axis: str, cross_axis_fill_offer: float | None
) -> tuple[float, float]:
    child_occurrence = occurrence(child)
    kind = text(child.get("kind"), f"{child_occurrence} kind")
    role = definite_node_role(kind, child_occurrence)
    placement = object_value(
        child.get("placement"), f"{child_occurrence} placement"
    )
    if placement.get("type") != "ABSOLUTE":
        raise Unsupported("NON_ABSOLUTE_PLACEMENT", child_occurrence)
    position, size = resource_free_absolute_child_axis_geometry(
        child,
        role,
        placement,
        axis,
        child_occurrence,
        cross_axis_for_quarter_turn=False,
        axis_fill_offer=None,
        opposite_axis_hug_offer=(
            HugOppositeAxisOffer("ABSOLUTE_PARENT_CONTENT", cross_axis_fill_offer)
            if cross_axis_fill_offer is not None
            else None
        ),
    )
    transform = object_value(child.get("transform"), f"{child_occurrence} transform")
    rotation = required_decimal(
        transform, "rotationDeg", child_occurrence, "transform.rotationDeg"
    )
    quarter_turn = exact_quarter_turn(rotation)
    if quarter_turn is None:
        raise Unsupported("CHILD_ROTATION", child_occurrence)
    if quarter_turn == 0 and rotation == 0.0:
        return zero_rotation_affine_axis_interval(
            child, position, size, axis, child_occurrence
        )
    if quarter_turn in (0, 2):
        return axis_preserving_affine_axis_interval(
            transform,
            position,
            size,
            axis,
            reverse=quarter_turn == 2,
            current=child_occurrence,
        )
    if axis == "Width":
        cross_axis = "Height"
    elif axis == "Height":
        cross_axis = "Width"
    else:
        raise VerificationFailure(f"{child_occurrence} invalid HUG axis")
    cross_position, cross_size = resource_free_absolute_child_axis_geometry(
        child,
        role,
        placement,
        cross_axis,
        child_occurrence,
        cross_axis_for_quarter_turn=True,
        axis_fill_offer=cross_axis_fill_offer,
        opposite_axis_hug_offer=None,
    )
    return quarter_turn_affine_axis_interval(
        transform,
        position,
        size,
        cross_position,
        cross_size,
        axis,
        quarter_turn,
        child_occurrence,
    )


def resource_free_absolute_child_axis_geometry(
    child: dict[str, Any],
    role: str,
    placement: dict[str, Any],
    axis: str,
    current: str,
    *,
    cross_axis_for_quarter_turn: bool,
    axis_fill_offer: float | None,
    opposite_axis_hug_offer: HugOppositeAxisOffer | None,
) -> tuple[float, float]:
    if axis == "Width":
        position_member, mode_member, size_member, end_inset_member = (
            "xPt",
            "widthMode",
            "widthPt",
            "rightInsetPt",
        )
    elif axis == "Height":
        position_member, mode_member, size_member, end_inset_member = (
            "yPt",
            "heightMode",
            "heightPt",
            "bottomInsetPt",
        )
    else:
        raise VerificationFailure(f"{current} invalid HUG axis")
    position = required_decimal(
        placement, position_member, current, f"placement.{position_member}"
    )
    mode = text(placement.get(mode_member), f"{current} placement.{mode_member}")
    if mode == "FIXED":
        size = required_decimal(
            placement, size_member, current, f"placement.{size_member}"
        )
    elif mode == "HUG_CONTENT":
        size = resource_free_hug_axis(
            child, role, placement, axis, current, opposite_axis_hug_offer
        )
    elif cross_axis_for_quarter_turn and mode == "FILL":
        if axis_fill_offer is None:
            raise Unsupported("CHILD_ROTATION", current)
        size = definite_axis_size(
            placement,
            mode,
            axis_fill_offer,
            position,
            axis,
            end_inset_member,
            current,
        )
    else:
        raise VerificationFailure(
            f"{current} invalid placement.{mode_member} in HUG container"
        )
    return position, size


def exact_quarter_turn(rotation: float) -> int | None:
    if rotation in (-360.0, 0.0, 360.0):
        return 0
    if rotation in (-270.0, 90.0):
        return 1
    if rotation in (-180.0, 180.0):
        return 2
    if rotation in (-90.0, 270.0):
        return 3
    return None


def zero_rotation_affine_axis_interval(
    node: dict[str, Any], position: float, size: float, axis: str, current: str
) -> tuple[float, float]:
    transform = object_value(node.get("transform"), f"{current} transform")
    rotation = required_decimal(
        transform, "rotationDeg", current, "transform.rotationDeg"
    )
    if rotation != 0.0:
        raise Unsupported("CHILD_ROTATION", current)
    if axis == "Width":
        origin_member, scale_member = "originX", "scaleX"
    elif axis == "Height":
        origin_member, scale_member = "originY", "scaleY"
    else:
        raise VerificationFailure(f"{current} invalid HUG axis")
    origin_ratio = required_decimal(
        transform, origin_member, current, f"transform.{origin_member}"
    )
    scale = required_decimal(
        transform, scale_member, current, f"transform.{scale_member}"
    )
    if scale == 0.0:
        raise VerificationFailure(f"{current} transform.{scale_member} is zero")

    origin_offset = finite_transform_value(origin_ratio * size, current)
    transform_origin = finite_transform_value(position + origin_offset, current)
    near_delta = finite_transform_value(position - transform_origin, current)
    near_scaled = finite_transform_value(scale * near_delta, current)
    near = finite_transform_value(transform_origin + near_scaled, current)
    far_position = finite_transform_value(position + size, current)
    far_delta = finite_transform_value(far_position - transform_origin, current)
    far_scaled = finite_transform_value(scale * far_delta, current)
    far = finite_transform_value(transform_origin + far_scaled, current)
    return min(near, far), max(near, far)


def axis_preserving_affine_axis_interval(
    transform: dict[str, Any],
    position: float,
    size: float,
    axis: str,
    *,
    reverse: bool,
    current: str,
) -> tuple[float, float]:
    if axis == "Width":
        origin_member, scale_member = "originX", "scaleX"
    elif axis == "Height":
        origin_member, scale_member = "originY", "scaleY"
    else:
        raise VerificationFailure(f"{current} invalid HUG axis")
    origin_ratio = required_decimal(
        transform, origin_member, current, f"transform.{origin_member}"
    )
    scale = required_decimal(
        transform, scale_member, current, f"transform.{scale_member}"
    )
    if scale == 0.0:
        raise VerificationFailure(f"{current} transform.{scale_member} is zero")

    origin_offset = finite_transform_value(origin_ratio * size, current)
    transform_origin = finite_transform_value(position + origin_offset, current)
    near_delta = finite_transform_value(position - transform_origin, current)
    near_scaled = finite_transform_value(scale * near_delta, current)
    near = signed_transform_endpoint(transform_origin, near_scaled, reverse, current)
    far_position = finite_transform_value(position + size, current)
    far_delta = finite_transform_value(far_position - transform_origin, current)
    far_scaled = finite_transform_value(scale * far_delta, current)
    far = signed_transform_endpoint(transform_origin, far_scaled, reverse, current)
    return min(near, far), max(near, far)


def quarter_turn_affine_axis_interval(
    transform: dict[str, Any],
    target_position: float,
    target_size: float,
    source_position: float,
    source_size: float,
    axis: str,
    quarter_turn: int,
    current: str,
) -> tuple[float, float]:
    members = {
        ("Width", 1): ("originX", "originY", "scaleY", True),
        ("Height", 1): ("originY", "originX", "scaleX", False),
        ("Width", 3): ("originX", "originY", "scaleY", False),
        ("Height", 3): ("originY", "originX", "scaleX", True),
    }
    try:
        target_origin_member, source_origin_member, source_scale_member, reverse = (
            members[(axis, quarter_turn)]
        )
    except KeyError as error:
        raise VerificationFailure(f"{current} invalid quarter turn") from error

    target_origin_ratio = required_decimal(
        transform,
        target_origin_member,
        current,
        f"transform.{target_origin_member}",
    )
    source_origin_ratio = required_decimal(
        transform,
        source_origin_member,
        current,
        f"transform.{source_origin_member}",
    )
    source_scale = required_decimal(
        transform,
        source_scale_member,
        current,
        f"transform.{source_scale_member}",
    )
    if source_scale == 0.0:
        raise VerificationFailure(f"{current} transform.{source_scale_member} is zero")

    target_origin_offset = finite_transform_value(
        target_origin_ratio * target_size, current
    )
    target_origin = finite_transform_value(
        target_position + target_origin_offset, current
    )
    source_origin_offset = finite_transform_value(
        source_origin_ratio * source_size, current
    )
    source_origin = finite_transform_value(
        source_position + source_origin_offset, current
    )
    near_delta = finite_transform_value(source_position - source_origin, current)
    near_scaled = finite_transform_value(source_scale * near_delta, current)
    near = signed_transform_endpoint(target_origin, near_scaled, reverse, current)
    far_position = finite_transform_value(source_position + source_size, current)
    far_delta = finite_transform_value(far_position - source_origin, current)
    far_scaled = finite_transform_value(source_scale * far_delta, current)
    far = signed_transform_endpoint(target_origin, far_scaled, reverse, current)
    return min(near, far), max(near, far)


def signed_transform_endpoint(
    origin: float, scaled_delta: float, reverse: bool, current: str
) -> float:
    if reverse:
        return finite_transform_value(origin - scaled_delta, current)
    return finite_transform_value(origin + scaled_delta, current)


def finite_transform_value(value: float, current: str) -> float:
    if not (-float("inf") < value < float("inf")):
        raise VerificationFailure(f"{current} transform produced non-finite binary64")
    return value


def finite_group_union_value(value: float, current: str) -> float:
    if not (-float("inf") < value < float("inf")):
        raise VerificationFailure(f"{current} Group union produced non-finite binary64")
    return value


def finite_group_normalization_value(value: float, current: str) -> float:
    if not (-float("inf") < value < float("inf")):
        raise VerificationFailure(
            f"{current} Group normalization produced non-finite binary64"
        )
    return value


def resource_free_grid_hug_content_extent(
    grid: dict[str, Any],
    placement: dict[str, Any],
    axis: str,
    current: str,
    opposite_axis_offer: HugOppositeAxisOffer | None,
) -> float:
    children = array_value(grid.get("children"), f"{current} children")
    if axis == "Width":
        resolved = definite_grid_axis(
            grid, children, "COLUMN", 0.0, 0.0, current, None
        )
    elif axis == "Height":
        column_content_offer = definite_grid_column_content_offer(
            grid, placement, current, opposite_axis_offer
        )
        if column_content_offer is None:
            resolved = definite_grid_axis(
                grid, children, "ROW", 0.0, 0.0, current, None
            )
        else:
            columns = definite_grid_axis(
                grid,
                children,
                "COLUMN",
                0.0,
                column_content_offer,
                current,
                None,
            )
            resolved = definite_grid_axis(
                grid, children, "ROW", 0.0, 0.0, current, columns
            )
    else:
        raise VerificationFailure(f"{current} invalid Grid HUG axis")
    return grid_span_extent(resolved.sizes, resolved.gap, 0, len(resolved.sizes))


def definite_grid_column_content_offer(
    grid: dict[str, Any],
    placement: dict[str, Any],
    current: str,
    opposite_axis_offer: HugOppositeAxisOffer | None,
) -> float | None:
    if placement.get("widthMode") != "FILL":
        return None
    if opposite_axis_offer is None:
        return None
    if opposite_axis_offer.source == "RESOLVED_OUTER":
        outer_width = opposite_axis_offer.size
    elif opposite_axis_offer.source == "ABSOLUTE_PARENT_CONTENT":
        authored_x = required_decimal(
            placement, "xPt", current, "placement.xPt"
        )
        outer_width = definite_axis_size(
            placement,
            "FILL",
            opposite_axis_offer.size,
            authored_x,
            "Width",
            "rightInsetPt",
            current,
        )
    else:
        raise VerificationFailure(f"{current} invalid Grid column offer source")
    content_width = container_axis_content_size(
        grid, outer_width, "Width", current
    )
    if not (-float("inf") < content_width < float("inf")):
        raise VerificationFailure(
            f"{current} definite Grid column content offer is not finite"
        )
    return content_width


def resource_free_stack_hug_content_extent(
    stack: dict[str, Any],
    placement: dict[str, Any],
    axis: str,
    current: str,
    opposite_axis_offer: HugOppositeAxisOffer | None,
) -> float:
    direction = text(stack.get("direction"), f"{current} direction")
    if direction not in {"ROW", "COLUMN"} or axis not in {"Width", "Height"}:
        raise VerificationFailure(f"{current} invalid Stack HUG axis")
    main_axis = (direction == "ROW" and axis == "Width") or (
        direction == "COLUMN" and axis == "Height"
    )
    if main_axis:
        cross_content_offer = definite_stack_cross_content_offer(
            stack, placement, direction, current, opposite_axis_offer
        )
        if cross_content_offer is not None:
            return resource_free_stack_main_hug_content_extent(
                stack, direction, cross_content_offer
            )
    if not main_axis:
        main_content_offer = definite_stack_main_content_offer(
            stack, placement, direction, current, opposite_axis_offer
        )
        if main_content_offer is not None:
            return resource_free_stack_cross_hug_content_extent(
                stack, direction, main_content_offer
            )
    children = array_value(stack.get("children"), f"{current} children")
    gap = nonnegative_decimal(stack, "gapPt", current, "gapPt")

    if main_axis:
        cursor = 0.0
        farthest = 0.0
        for index, raw_child in enumerate(children):
            child = object_value(raw_child, f"{current} child")
            child_current = occurrence(child)
            child_placement = stack_hug_child_placement(child, child_current)
            leading, trailing = stack_hug_axis_margins(
                child_placement, axis, child_current
            )
            child_size = resource_free_stack_child_axis_size(
                child, child_placement, axis, child_current
            )
            for addition in (leading, child_size, trailing):
                cursor += addition
                if cursor > farthest:
                    farthest = cursor
            if index + 1 < len(children):
                cursor += gap
                if cursor > farthest:
                    farthest = cursor
        return farthest

    farthest = 0.0
    for raw_child in children:
        child = object_value(raw_child, f"{current} child")
        child_current = occurrence(child)
        child_placement = stack_hug_child_placement(child, child_current)
        leading, trailing = stack_hug_axis_margins(
            child_placement, axis, child_current
        )
        margin_extent_end = leading
        margin_extent_end += resource_free_stack_child_axis_size(
            child, child_placement, axis, child_current
        )
        margin_extent_end += trailing
        if margin_extent_end > farthest:
            farthest = margin_extent_end
    return farthest


def definite_stack_cross_content_offer(
    stack: dict[str, Any],
    placement: dict[str, Any],
    direction: str,
    current: str,
    opposite_axis_offer: HugOppositeAxisOffer | None,
) -> float | None:
    if direction == "ROW":
        cross_axis, mode_member = "Height", "heightMode"
    elif direction == "COLUMN":
        cross_axis, mode_member = "Width", "widthMode"
    else:
        raise VerificationFailure(f"{current} invalid Stack direction")
    mode = text(placement.get(mode_member), f"{current} placement.{mode_member}")
    if mode != "FILL" or opposite_axis_offer is None:
        return None
    if opposite_axis_offer.source != "RESOLVED_OUTER":
        return None
    cross_content_size = container_axis_content_size(
        stack, opposite_axis_offer.size, cross_axis, current
    )
    if not math.isfinite(cross_content_size):
        raise VerificationFailure(
            f"{current} invalid definite {cross_axis} content offer"
        )
    return cross_content_size


def resource_free_stack_main_hug_content_extent(
    stack: dict[str, Any], direction: str, cross_content_offer: float
) -> float:
    current = occurrence(stack)
    children = array_value(stack.get("children"), f"{current} children")
    gap = nonnegative_decimal(stack, "gapPt", current, "gapPt")
    space = StackMeasurementSpace.main_hug(direction, cross_content_offer)
    extent = 0.0
    for index, raw_child in enumerate(children):
        child = object_value(raw_child, f"{current} child")
        measured = measure_stack_child(child, space, direction)
        if measured.main_fill:
            raise Unsupported("STACK_MAIN_FILL", occurrence(child))
        extent += measured.main_leading_margin(direction)
        extent += measured.main_size(direction)
        extent += measured.main_trailing_margin(direction)
        if index + 1 < len(children):
            extent += gap
    return extent


def definite_stack_main_content_offer(
    stack: dict[str, Any],
    placement: dict[str, Any],
    direction: str,
    current: str,
    opposite_axis_offer: HugOppositeAxisOffer | None,
) -> float | None:
    if direction == "ROW":
        main_axis, mode_member = "Width", "widthMode"
    elif direction == "COLUMN":
        main_axis, mode_member = "Height", "heightMode"
    else:
        raise VerificationFailure(f"{current} invalid Stack direction")
    mode = text(placement.get(mode_member), f"{current} placement.{mode_member}")
    if mode != "FILL" or opposite_axis_offer is None:
        return None
    if opposite_axis_offer.source != "RESOLVED_OUTER":
        return None
    main_content_size = container_axis_content_size(
        stack, opposite_axis_offer.size, main_axis, current
    )
    if not math.isfinite(main_content_size):
        raise VerificationFailure(
            f"{current} invalid definite {main_axis} content offer"
        )
    return main_content_size


def resource_free_stack_cross_hug_content_extent(
    stack: dict[str, Any], direction: str, main_content_offer: float
) -> float:
    resolved = measure_and_allocate_stack_children(
        stack, StackMeasurementSpace.cross_hug(direction, main_content_offer)
    )
    farthest = 0.0
    for measured in resolved.measurements:
        if isinstance(measured, Unsupported):
            raise measured
        margin_extent_end = (
            measured.cross_leading_margin(direction) + measured.cross_size(direction)
        ) + measured.cross_trailing_margin(direction)
        if margin_extent_end > farthest:
            farthest = margin_extent_end
    return farthest


def stack_hug_child_placement(
    child: dict[str, Any], current: str
) -> dict[str, Any]:
    placement = object_value(child.get("placement"), f"{current} placement")
    if placement.get("type") != "STACK":
        raise Unsupported("NON_ABSOLUTE_PLACEMENT", current)
    return placement


def stack_hug_axis_margins(
    placement: dict[str, Any], axis: str, current: str
) -> tuple[float, float]:
    if axis == "Width":
        leading_member, trailing_member = "marginLeftPt", "marginRightPt"
    elif axis == "Height":
        leading_member, trailing_member = "marginTopPt", "marginBottomPt"
    else:
        raise VerificationFailure(f"{current} invalid Stack HUG axis")
    return (
        required_decimal(
            placement, leading_member, current, f"placement.{leading_member}"
        ),
        required_decimal(
            placement, trailing_member, current, f"placement.{trailing_member}"
        ),
    )


def resource_free_stack_child_axis_size(
    child: dict[str, Any],
    placement: dict[str, Any],
    axis: str,
    current: str,
) -> float:
    kind = text(child.get("kind"), f"{current} kind")
    role = definite_node_role(kind, current)
    mode_member = f"{axis.lower()}Mode"
    mode = text(placement.get(mode_member), f"{current} {mode_member}")
    if mode == "FIXED":
        size_member = f"{axis.lower()}Pt"
        return required_decimal(
            placement, size_member, current, f"placement.{size_member}"
        )
    if mode == "HUG_CONTENT":
        return resource_free_hug_axis(child, role, placement, axis, current, None)
    if mode == "FILL":
        raise VerificationFailure(f"{current} unexpected Stack HUG/FILL cycle")
    raise VerificationFailure(f"{current} invalid Stack HUG size mode")


def empty_container_hug_axis(
    node: dict[str, Any],
    role: str,
    placement: dict[str, Any],
    axis: str,
    current: str,
) -> float:
    if role == "LEAF":
        raise Unsupported("HUG_CONTENT", current)
    children = array_value(node.get("children"), f"{current} children")
    if children:
        raise Unsupported("GROUP" if role == "GROUP" else "HUG_CONTENT", current)
    if role == "GROUP":
        return 0.0

    content_extent = (
        empty_grid_track_extent(node, axis, current) if role == "GRID" else 0.0
    )
    natural = container_outer_extent(node, axis, content_extent, current)
    return clamp_flexible_axis(placement, natural, axis, current)


def empty_grid_track_extent(
    grid: dict[str, Any], axis: str, current: str
) -> float:
    if axis == "Width":
        tracks_member, gap_member = "columns", "columnGapPt"
    elif axis == "Height":
        tracks_member, gap_member = "rows", "rowGapPt"
    else:
        raise VerificationFailure(f"{current} invalid HUG axis")
    tracks = array_value(grid.get(tracks_member), f"{current} {tracks_member}")
    gap = nonnegative_decimal(grid, gap_member, current, gap_member)
    extent = 0.0
    for index, raw_track in enumerate(tracks):
        track = object_value(raw_track, f"{current} {tracks_member}[{index}]")
        track_type = text(
            track.get("type"), f"{current} {tracks_member}[{index}].type"
        )
        if track_type == "FIXED":
            extent += required_decimal(
                track,
                "valuePt",
                current,
                f"{tracks_member}[{index}].valuePt",
            )
        elif track_type != "AUTO":
            raise VerificationFailure(
                f"{current} invalid empty HUG {tracks_member}[{index}].type"
            )
        if index + 1 < len(tracks):
            extent += gap
    return extent


def container_outer_extent(
    node: dict[str, Any], axis: str, extent: float, current: str
) -> float:
    raw_stroke = node.get("stroke")
    if raw_stroke is None:
        stroke_width = 0.0
    else:
        stroke = object_value(raw_stroke, f"{current} stroke")
        stroke_width = nonnegative_decimal(
            stroke, "widthPt", current, "stroke.widthPt"
        )
    padding = object_value(node.get("padding"), f"{current} padding")
    if axis == "Width":
        leading_member, trailing_member = "leftPt", "rightPt"
    elif axis == "Height":
        leading_member, trailing_member = "topPt", "bottomPt"
    else:
        raise VerificationFailure(f"{current} invalid HUG axis")
    extent += nonnegative_decimal(
        padding, leading_member, current, f"padding.{leading_member}"
    )
    extent += nonnegative_decimal(
        padding, trailing_member, current, f"padding.{trailing_member}"
    )
    extent += stroke_width
    extent += stroke_width
    return extent


def stack_axis_size(
    placement: dict[str, Any],
    mode: str,
    parent_size: float,
    leading_margin: float,
    trailing_margin: float,
    axis: str,
    current: str,
) -> float:
    return stack_axis_size_from_offer(
        placement,
        mode,
        parent_size,
        leading_margin,
        trailing_margin,
        axis,
        current,
    )


def stack_axis_size_from_offer(
    placement: dict[str, Any],
    mode: str,
    parent_size: float | None,
    leading_margin: float,
    trailing_margin: float,
    axis: str,
    current: str,
) -> float:
    size_member = f"{axis.lower()}Pt"
    if mode == "FIXED":
        return required_decimal(
            placement, size_member, current, f"placement.{size_member}"
        )
    if parent_size is None:
        raise VerificationFailure(f"{current} missing definite {axis} offer")
    remaining = (parent_size - leading_margin) - trailing_margin
    size = remaining if remaining > 0.0 else 0.0
    return clamp_flexible_axis(placement, size, axis, current)


def clamp_flexible_axis(
    placement: dict[str, Any], size: float, axis: str, current: str
) -> float:
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


def container_content_box(node: dict[str, Any], layout_box: Box, current: str) -> Box:
    if "stroke" in node:
        stroke = object_value(node["stroke"], f"{current} stroke")
        stroke_width = nonnegative_decimal(
            stroke, "widthPt", current, "stroke.widthPt"
        )
    else:
        stroke_width = 0.0
    inner_width = subtract_content_inset(layout_box.width, stroke_width)
    inner_width = subtract_content_inset(inner_width, stroke_width)
    inner_height = subtract_content_inset(layout_box.height, stroke_width)
    inner_height = subtract_content_inset(inner_height, stroke_width)
    inner_x = layout_box.x + stroke_width
    inner_y = layout_box.y + stroke_width

    padding = object_value(node.get("padding"), f"{current} padding")
    top = nonnegative_decimal(padding, "topPt", current, "padding.topPt")
    right = nonnegative_decimal(padding, "rightPt", current, "padding.rightPt")
    bottom = nonnegative_decimal(padding, "bottomPt", current, "padding.bottomPt")
    left = nonnegative_decimal(padding, "leftPt", current, "padding.leftPt")
    content_width = subtract_content_inset(inner_width, left)
    content_width = subtract_content_inset(content_width, right)
    content_height = subtract_content_inset(inner_height, top)
    content_height = subtract_content_inset(content_height, bottom)
    return Box(inner_x + left, inner_y + top, content_width, content_height)


def subtract_content_inset(size: float, inset: float) -> float:
    remaining = size - inset
    return remaining if remaining > 0.0 else 0.0


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
        key: str | int = int(token) if isinstance(parent, list) else token
        if operation == "remove":
            del parent[key]
        elif operation == "add" and isinstance(parent, list):
            parent.insert(key, copy.deepcopy(mutation["value"]))
        elif operation in {"add", "replace"}:
            parent[key] = copy.deepcopy(mutation["value"])
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
        vectors["vectorVersion"] == "renderweave-definite-layout-vectors/25",
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
        "layoutImplementation": "RESOURCE_FREE_DEFINITE_ABSOLUTE_STACK_SINGLE_MAIN_FILL_AND_FIXED_SINGLE_FRACTION_INDEPENDENT_MULTI_AUTO_GRID_EMPTY_CONTAINER_STACK_HUG_GRID_AUTO_HUG_CONTRIBUTION_GRID_HUG_EXACT_QUARTER_TURN_AFFINE_FRAME_GROUP_HUG_FIXED_OPPOSITE_AXIS_CROSS_FILL_DEFINITE_ABSOLUTE_PARENT_OFFER_DEFINITE_STACK_CROSS_OUTER_OFFER_STACK_MAIN_FILL_CROSS_HUG_REMEASURE_NESTED_STACK_MAIN_OFFER_PROPAGATION_COLUMNS_FIRST_GRID_CELL_OUTER_OFFER_STACK_MAIN_OFFER_COLUMNS_FIRST_GRID_CROSS_HUG_ABSOLUTE_PARENT_OFFER_COLUMNS_FIRST_GRID_CROSS_HUG_GRID_CELL_OFFER_COLUMNS_FIRST_NESTED_GRID_CROSS_HUG_GRID_CELL_OFFER_STACK_MAIN_FIRST_CROSS_HUG_DIRECTION_CHANGING_STACK_CROSS_OFFER_MAIN_HUG_NORMALIZATION_BOX_KERNEL",
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
        fixtures["fixtureVersion"] == "renderweave-definite-layout-fixtures/3",
        "fixture identity drifted",
    )
    verifier.require(
        set(fixtures["documents"])
        == {
            "fixedRect",
            "nestedFrame",
            "fillNested",
            "degenerateFrame",
            "rowStack",
            "columnStack",
            "nestedStack",
            "singleStack",
            "remainderStack",
            "fixedGrid",
            "stackDfsUnsupported",
        },
        "fixture inventory drifted",
    )
    verifier.require(
        layout_preflight_fixtures["fixtureVersion"]
        == "renderweave-layout-preflight-fixtures/1",
        "layout preflight fixture identity drifted",
    )
    verifier.require(len(vectors["laidOutCases"]) == 114, "laid-out case count drifted")
    verifier.require(
        len(vectors["unsupportedCases"]) == 15,
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
        "verifier": "renderweave-definite-layout-python-independent/25",
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
