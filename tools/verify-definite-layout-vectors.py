#!/usr/bin/env python3
"""Independent replay for definite ABSOLUTE, singleton-main-FILL Stack, and fixed Grid boxes."""

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

    def main_size(self, direction: str) -> float:
        return self.width if direction == "ROW" else self.height

    def main_leading_margin(self, direction: str) -> float:
        return self.margin_left if direction == "ROW" else self.margin_top

    def main_trailing_margin(self, direction: str) -> float:
        return self.margin_right if direction == "ROW" else self.margin_bottom

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
        )


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
            empty_container_hug_axis(node, role, placement, "Width", current)
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
            empty_container_hug_axis(node, role, placement, "Height", current)
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
        else:
            self.entries.append(
                {
                    "occurrenceId": current,
                    "kind": kind,
                    "layoutBox": layout_box.bits(),
                    "contentBox": None,
                }
            )

    def visit_stack_children(self, stack: dict[str, Any], content_box: Box) -> None:
        current = occurrence(stack)
        direction = text(stack.get("direction"), f"{current} direction")
        if direction not in {"ROW", "COLUMN"}:
            raise VerificationFailure(f"{current} invalid Stack direction")
        justification = text(
            stack.get("justifyContent"), f"{current} justifyContent"
        )
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
                    child, content_box, direction
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

        available = content_box.width if direction == "ROW" else content_box.height
        if len(fill_indices) > 1:
            first_fill = fill_indices[0]
            child = object_value(children[first_fill], f"{current} child")
            measurements[first_fill] = Unsupported(
                "STACK_MAIN_FILL", occurrence(child)
            )
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
            measurements[fill_index] = measured.with_main_size(direction, size)

        occupied = used_without_fill
        if len(fill_indices) == 1:
            measured = measurements[fill_indices[0]]
            if isinstance(measured, StackChildMeasurement):
                occupied += measured.main_size(direction)
        occupied = occupied if occupied > 0.0 else 0.0
        remaining = available - occupied
        free = remaining if remaining > 0.0 else 0.0
        leading, between = stack_distribution(justification, free, len(children))
        cursor = leading

        for index, (raw_child, measured) in enumerate(zip(children, measurements)):
            child = object_value(raw_child, f"{current} child")
            if isinstance(measured, StackChildMeasurement):
                cursor += measured.main_leading_margin(direction)
                layout_box = stack_child_box(
                    content_box, measured, direction, cursor
                )
                cursor += measured.main_size(direction)
                cursor += measured.main_trailing_margin(direction)
                if index + 1 < len(children):
                    cursor += gap
                    cursor += between[index]
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
        )
        rows = definite_grid_axis(
            grid,
            children,
            "ROW",
            content_box.y,
            content_box.height,
            current,
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
            children, axis, auto_indices, sizes, gap, current
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
            raise Unsupported("HUG_CONTENT", child_occurrence)
        if mode != "FIXED":
            raise VerificationFailure(
                f"{child_occurrence} invalid placement.{mode_member} across AUTO"
            )
        size = required_decimal(
            placement,
            size_member,
            child_occurrence,
            f"placement.{size_member}",
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
) -> tuple[float, float]:
    size = (
        empty_container_hug_axis(node, role, placement, axis, current)
        if mode == "HUG_CONTENT"
        else stack_axis_size(
            placement,
            mode,
            cell_size,
            leading_margin,
            trailing_margin,
            axis,
            current,
        )
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


def measure_stack_child(
    node: dict[str, Any], parent: Box, direction: str
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
    if width_mode == "HUG_CONTENT":
        width = empty_container_hug_axis(node, role, placement, "Width", current)
    elif direction == "ROW" and main_fill:
        width = 0.0
    else:
        width = stack_axis_size(
            placement,
            width_mode,
            parent.width,
            margin_left,
            margin_right,
            "Width",
            current,
        )
    if height_mode == "HUG_CONTENT":
        height = empty_container_hug_axis(node, role, placement, "Height", current)
    elif direction == "COLUMN" and main_fill:
        height = 0.0
    else:
        height = stack_axis_size(
            placement,
            height_mode,
            parent.height,
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
    )


def stack_child_has_main_fill(node: dict[str, Any], direction: str) -> bool:
    placement = node.get("placement")
    if not isinstance(placement, dict) or placement.get("type") != "STACK":
        return False
    member = "widthMode" if direction == "ROW" else "heightMode"
    return placement.get(member) == "FILL"


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
    natural = empty_container_outer_extent(node, axis, content_extent, current)
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


def empty_container_outer_extent(
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
    size_member = f"{axis.lower()}Pt"
    if mode == "FIXED":
        return required_decimal(
            placement, size_member, current, f"placement.{size_member}"
        )
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
        vectors["vectorVersion"] == "renderweave-definite-layout-vectors/8",
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
        "layoutImplementation": "RESOURCE_FREE_DEFINITE_ABSOLUTE_STACK_SINGLE_MAIN_FILL_AND_FIXED_SINGLE_FRACTION_INDEPENDENT_MULTI_AUTO_GRID_EMPTY_INTRINSIC_CONTAINER_BOX_KERNEL",
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
    verifier.require(len(vectors["laidOutCases"]) == 40, "laid-out case count drifted")
    verifier.require(
        len(vectors["unsupportedCases"]) == 13,
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
        "verifier": "renderweave-definite-layout-python-independent/8",
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
