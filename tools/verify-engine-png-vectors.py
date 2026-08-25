#!/usr/bin/env python3
"""Independent stdlib replay for the TV1-T110 solid-alpha Engine PNG kernel."""

from __future__ import annotations

import argparse
import binascii
import hashlib
import importlib.util
import json
import math
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


def load_definite_layout_module() -> Any:
    path = Path(__file__).with_name("verify-definite-layout-vectors.py")
    spec = importlib.util.spec_from_file_location(
        "renderweave_definite_layout_verifier", path
    )
    if spec is None or spec.loader is None:
        raise VerificationFailure("independent definite layout verifier cannot be loaded")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


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


def parse_decimal6(value: Any) -> int:
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
    return scaled


def decimal6(value: Any, positive: bool) -> int:
    scaled = parse_decimal6(value)
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


def mul255(value: int, alpha: int) -> int:
    return (value * alpha + 127) // 255


def premultiply(straight: bytes) -> bytes:
    alpha = straight[3]
    if alpha == 0:
        return b"\0\0\0\0"
    return bytes(
        (
            mul255(straight[0], alpha),
            mul255(straight[1], alpha),
            mul255(straight[2], alpha),
            alpha,
        )
    )


def source_over(destination: bytearray, offset: int, source_straight: bytes) -> None:
    source = premultiply(source_straight)
    source_over_premultiplied(destination, offset, source)


def source_over_premultiplied(
    destination: bytearray, offset: int, source: bytes
) -> None:
    if len(source) != 4:
        raise VerificationFailure("premultiplied source is not RGBA8")
    inverse_source_alpha = 255 - source[3]
    for channel in range(4):
        destination[offset + channel] = min(
            255,
            source[channel]
            + mul255(destination[offset + channel], inverse_source_alpha),
        )


def unpremultiply_surface(pixels: bytearray) -> bytes:
    for offset in range(0, len(pixels), 4):
        alpha = pixels[offset + 3]
        if alpha == 0:
            pixels[offset : offset + 4] = b"\0\0\0\0"
            continue
        for channel in range(3):
            pixels[offset + channel] = min(
                255,
                (pixels[offset + channel] * 255 + alpha // 2) // alpha,
            )
    return bytes(pixels)


def parse_background(value: Any) -> bytes:
    return premultiply(parse_rgba(value, "Canvas background"))


def number_is(value: Any, expected: str) -> bool:
    if type(value) is int:
        return Decimal(value) == Decimal(expected)
    return isinstance(value, Decimal) and value == Decimal(expected)


def has_exact_members(value: Any, expected: set[str]) -> bool:
    return isinstance(value, dict) and set(value) == expected


def exact_layout_device_edge(
    coordinate: float, bleed_scaled: int, dpi: int
) -> int | None:
    if not math.isfinite(coordinate):
        raise VerificationFailure("Paint layout coordinate is not finite")
    coordinate_numerator, coordinate_denominator = coordinate.as_integer_ratio()
    point_numerator = (
        coordinate_numerator * 1_000_000
        + bleed_scaled * coordinate_denominator
    )
    point_denominator = coordinate_denominator * 1_000_000
    device_numerator = point_numerator * dpi
    device_denominator = point_denominator * 72
    edge, remainder = divmod(device_numerator, device_denominator)
    return edge if remainder == 0 else None


def identity_transform(node: dict[str, Any]) -> bool:
    transform = node.get("transform")
    return has_exact_members(
        transform, {"originX", "originY", "rotationDeg", "scaleX", "scaleY"}
    ) and all((
        number_is(transform["originX"], "0.5"),
        number_is(transform["originY"], "0.5"),
        number_is(transform["rotationDeg"], "0"),
        number_is(transform["scaleX"], "1"),
        number_is(transform["scaleY"], "1"),
    ))


def zero_corner_radii(node: dict[str, Any]) -> bool:
    radii = node.get("cornerRadii")
    members = {"bottomLeftPt", "bottomRightPt", "topLeftPt", "topRightPt"}
    return has_exact_members(radii, members) and all(
        number_is(radii[member], "0") for member in members
    )


def binary64_from_bits(value: Any, label: str) -> float:
    if not isinstance(value, str) or len(value) != 16:
        raise VerificationFailure(f"{label} is not an IEEE-754 binary64 bit string")
    try:
        result = struct.unpack(">d", bytes.fromhex(value))[0]
    except (ValueError, struct.error) as error:
        raise VerificationFailure(
            f"{label} is not an IEEE-754 binary64 bit string"
        ) from error
    if not math.isfinite(result):
        raise VerificationFailure(f"{label} is not finite")
    return result


def layout_box(value: Any, label: str) -> tuple[float, float, float, float]:
    value = exact_members(
        value, {"xBits", "yBits", "widthBits", "heightBits"}, label
    )
    result = (
        binary64_from_bits(value["xBits"], f"{label}.xBits"),
        binary64_from_bits(value["yBits"], f"{label}.yBits"),
        binary64_from_bits(value["widthBits"], f"{label}.widthBits"),
        binary64_from_bits(value["heightBits"], f"{label}.heightBits"),
    )
    if result[2] < 0.0 or result[3] < 0.0:
        raise VerificationFailure(f"{label} has a negative extent")
    return result


def require_layout_entry(
    node: dict[str, Any],
    entry: Any,
    kind: str,
    has_content_box: bool,
) -> tuple[float, float, float, float]:
    entry = exact_members(
        entry, {"occurrenceId", "kind", "layoutBox", "contentBox"},
        "Definite layout entry",
    )
    if entry["kind"] != kind or entry["occurrenceId"] != node.get("occurrenceId"):
        raise VerificationFailure(
            "Engine PNG layout entry identity diverged from the admitted scene"
        )
    box = layout_box(entry["layoutBox"], f"{kind} LayoutBox")
    if has_content_box:
        layout_box(entry["contentBox"], f"{kind} ContentBox")
    elif entry["contentBox"] is not None:
        raise VerificationFailure(
            "Engine PNG layout entry content shape diverged from the admitted scene"
        )
    return box


def intersect_clip(
    left: tuple[int, int, int, int],
    right: tuple[int, int, int, int],
) -> tuple[int, int, int, int]:
    clipped_left = max(left[0], right[0])
    clipped_top = max(left[1], right[1])
    clipped_right = max(clipped_left, min(left[2], right[2]))
    clipped_bottom = max(clipped_top, min(left[3], right[3]))
    return clipped_left, clipped_top, clipped_right, clipped_bottom


def prepare_layout_bounds(
    box: tuple[float, float, float, float],
    canvas: dict[str, Any],
    dpi: int,
    width: int,
    height: int,
    misaligned_feature: str,
) -> tuple[int, int, int, int] | dict[str, str]:
    bleed = canvas["bleed"]
    left_bleed = decimal6(bleed["leftPt"], False)
    top_bleed = decimal6(bleed["topPt"], False)
    origin_x, origin_y, rect_width, rect_height = box
    right_edge = origin_x + rect_width
    bottom_edge = origin_y + rect_height
    if (
        not math.isfinite(right_edge)
        or not math.isfinite(bottom_edge)
        or right_edge < origin_x
        or bottom_edge < origin_y
    ):
        raise VerificationFailure("Paint layout box is not finite and monotonic")
    edges = (
        exact_layout_device_edge(origin_x, left_bleed, dpi),
        exact_layout_device_edge(origin_y, top_bleed, dpi),
        exact_layout_device_edge(right_edge, left_bleed, dpi),
        exact_layout_device_edge(bottom_edge, top_bleed, dpi),
    )
    if any(edge is None for edge in edges):
        return {"feature": misaligned_feature}
    left, top, right, bottom = (int(edge) for edge in edges)
    if right < left or bottom < top:
        raise VerificationFailure("Paint device box is not monotonic")
    return (
        min(max(left, 0), width),
        min(max(top, 0), height),
        min(max(right, 0), width),
        min(max(bottom, 0), height),
    )


def prepare_layout_rect(
    box: tuple[float, float, float, float],
    color: bytes,
    canvas: dict[str, Any],
    dpi: int,
    width: int,
    height: int,
    active_clip: tuple[int, int, int, int],
) -> tuple[int, int, int, int, bytes] | dict[str, str]:
    bounds = prepare_layout_bounds(
        box,
        canvas,
        dpi,
        width,
        height,
        "NON_PIXEL_ALIGNED_RECT",
    )
    if isinstance(bounds, dict):
        return bounds
    left, top, right, bottom = intersect_clip(bounds, active_clip)
    return left, top, right, bottom, color


def prepare_rect(
    child: Any,
    entry: Any,
    canvas: dict[str, Any],
    dpi: int,
    width: int,
    height: int,
    draw_enabled: bool,
    active_clip: tuple[int, int, int, int],
) -> tuple[int, int, int, int, bytes] | None | dict[str, str]:
    if not isinstance(child, dict) or child.get("kind") != "rect":
        return {"feature": "SCENE_STRUCTURE"}
    box = require_layout_entry(child, entry, "rect", False)
    if not draw_enabled:
        return None
    if "stroke" in child:
        return {"feature": "RECT_PAINT"}

    if not identity_transform(child):
        return {"feature": "RECT_PAINT"}

    if not zero_corner_radii(child):
        return {"feature": "RECT_PAINT"}

    fill = child.get("fill")
    if not has_exact_members(fill, {"color"}):
        return {"feature": "RECT_PAINT"}
    color = parse_rgba(fill["color"], "Rect fill")

    return prepare_layout_rect(
        box, color, canvas, dpi, width, height, active_clip
    )


def prepare_group(
    child: Any, entry: Any, draw_enabled: bool
) -> bool | dict[str, str]:
    if not isinstance(child, dict) or child.get("kind") != "group":
        return {"feature": "SCENE_STRUCTURE"}
    require_layout_entry(child, entry, "group", False)
    if draw_enabled and not identity_transform(child):
        return {"feature": "SCENE_STRUCTURE"}
    return draw_enabled


def prepare_container(
    child: Any,
    entry: Any,
    canvas: dict[str, Any],
    dpi: int,
    width: int,
    height: int,
    draw_enabled: bool,
    active_clip: tuple[int, int, int, int],
) -> tuple[
    tuple[int, int, int, int, bytes] | None,
    tuple[int, int, int, int],
    bool,
] | dict[str, str]:
    if (
        not isinstance(child, dict)
        or child.get("kind") not in {"frame", "stack", "grid"}
        or not isinstance(child.get("clipContent"), bool)
    ):
        return {"feature": "FRAME_PAINT"}

    box = require_layout_entry(child, entry, child["kind"], True)
    if not draw_enabled:
        return None, active_clip, False
    if (
        "stroke" in child
        or not identity_transform(child)
        or not zero_corner_radii(child)
    ):
        return {"feature": "FRAME_PAINT"}
    clip_content = child["clipContent"]
    bounds = None
    if clip_content:
        bounds = prepare_layout_bounds(
            box, canvas, dpi, width, height, "NON_PIXEL_ALIGNED_CLIP"
        )
        if isinstance(bounds, dict):
            return bounds
    elif "fill" in child:
        bounds = prepare_layout_bounds(
            box, canvas, dpi, width, height, "NON_PIXEL_ALIGNED_RECT"
        )
        if isinstance(bounds, dict):
            return bounds

    paint = None
    if "fill" in child:
        fill = child["fill"]
        if not has_exact_members(fill, {"color"}):
            return {"feature": "FRAME_PAINT"}
        color = parse_rgba(fill["color"], "Container fill")
        if bounds is None:
            raise VerificationFailure("Container fill is missing prepared device bounds")
        left, top, right, bottom = intersect_clip(bounds, active_clip)
        paint = left, top, right, bottom, color
    descendant_clip = (
        intersect_clip(bounds, active_clip)
        if clip_content and bounds is not None
        else active_clip
    )
    return paint, descendant_clip, True


def node_draw_state(
    child: dict[str, Any], ancestor_draw_enabled: bool
) -> tuple[bool, int | None]:
    visible = child.get("visible")
    if not isinstance(visible, bool):
        raise VerificationFailure("Scene visibility is not boolean")
    opacity = child.get("opacity")
    if type(opacity) is int:
        opacity = Decimal(opacity)
    if not isinstance(opacity, Decimal) or opacity < 0 or opacity > 1:
        raise VerificationFailure("Node opacity is outside 0..1")
    scaled = decimal6(opacity, False)
    if not ancestor_draw_enabled or not visible or scaled == 0:
        return False, None
    if scaled == 1_000_000:
        return True, None
    return True, (scaled * 255 + 500_000) // 1_000_000


def scene_kinds_supported(children: list[Any]) -> bool:
    for child in children:
        if not isinstance(child, dict):
            return False
        if child.get("kind") == "rect":
            continue
        if child.get("kind") == "compositionViewport":
            source = child.get("sourceCanvas")
            if (
                isinstance(source, dict)
                and isinstance(source.get("children"), list)
                and scene_kinds_supported(source["children"])
            ):
                continue
        if child.get("kind") in {"group", "frame", "stack", "grid"} and isinstance(
            child.get("children"), list
        ):
            if scene_kinds_supported(child["children"]):
                continue
        return False
    return True


def prepare_scene(
    children: list[Any],
    layout_entries: list[dict[str, Any]],
    layout_cursor: list[int],
    canvas: dict[str, Any],
    dpi: int,
    width: int,
    height: int,
    ancestor_draw_enabled: bool,
    active_clip: tuple[int, int, int, int],
) -> list[tuple[Any, ...]] | dict[str, str]:
    commands: list[tuple[Any, ...]] = []
    for child in children:
        if layout_cursor[0] >= len(layout_entries):
            raise VerificationFailure(
                "Engine PNG layout preorder ended before the admitted scene"
            )
        entry = layout_entries[layout_cursor[0]]
        layout_cursor[0] += 1
        draw_enabled, layer_opacity = node_draw_state(
            child, ancestor_draw_enabled
        )
        if layer_opacity is not None:
            commands.append(("begin-opacity",))
        if child["kind"] == "rect":
            rect = prepare_rect(
                child,
                entry,
                canvas,
                dpi,
                width,
                height,
                draw_enabled,
                active_clip,
            )
            if isinstance(rect, dict):
                return rect
            if rect is not None:
                commands.append(("rect", rect))
            if layer_opacity is not None:
                commands.append(("end-opacity", layer_opacity))
            continue

        if child["kind"] == "compositionViewport":
            require_layout_entry(child, entry, "compositionViewport", False)
            source = child.get("sourceCanvas")
            if not isinstance(source, dict) or not isinstance(source.get("children"), list):
                return {"feature": "SCENE_STRUCTURE"}
            if layout_cursor[0] >= len(layout_entries):
                raise VerificationFailure(
                    "Engine PNG layout preorder ended before the composition source Canvas"
                )
            source_entry = layout_entries[layout_cursor[0]]
            layout_cursor[0] += 1
            source_box = require_layout_entry(source, source_entry, "canvas", True)
            descendant_clip = active_clip
            descendant_draw_enabled = False
            if draw_enabled:
                if not identity_transform(child):
                    return {"feature": "SCENE_STRUCTURE"}
                host_box = layout_box(entry["layoutBox"], "compositionViewport LayoutBox")
                host_bounds = prepare_layout_bounds(
                    host_box,
                    canvas,
                    dpi,
                    width,
                    height,
                    "NON_PIXEL_ALIGNED_CLIP",
                )
                if isinstance(host_bounds, dict):
                    return host_bounds
                source_bounds = prepare_layout_bounds(
                    source_box,
                    canvas,
                    dpi,
                    width,
                    height,
                    "NON_PIXEL_ALIGNED_CLIP",
                )
                if isinstance(source_bounds, dict):
                    return source_bounds
                descendant_clip = intersect_clip(
                    intersect_clip(active_clip, host_bounds), source_bounds
                )
                source_color = parse_rgba(
                    source.get("backgroundColor"), "composition source Canvas background"
                )
                left, top, right, bottom = descendant_clip
                commands.append(("rect", (left, top, right, bottom, source_color)))
                descendant_draw_enabled = True
            nested = prepare_scene(
                source["children"],
                layout_entries,
                layout_cursor,
                canvas,
                dpi,
                width,
                height,
                descendant_draw_enabled,
                descendant_clip,
            )
            if isinstance(nested, dict):
                return nested
            commands.extend(nested)
            if layer_opacity is not None:
                commands.append(("end-opacity", layer_opacity))
            continue

        if child["kind"] == "group":
            group = prepare_group(child, entry, draw_enabled)
            if isinstance(group, dict):
                return group
            descendant_clip = active_clip
            descendant_draw_enabled = group
        else:
            container = prepare_container(
                child,
                entry,
                canvas,
                dpi,
                width,
                height,
                draw_enabled,
                active_clip,
            )
            if isinstance(container, dict):
                return container
            container_paint, descendant_clip, descendant_draw_enabled = container
            if container_paint is not None:
                commands.append(("rect", container_paint))
        nested = prepare_scene(
            child["children"],
            layout_entries,
            layout_cursor,
            canvas,
            dpi,
            width,
            height,
            descendant_draw_enabled,
            descendant_clip,
        )
        if isinstance(nested, dict):
            return nested
        commands.extend(nested)
        if layer_opacity is not None:
            commands.append(("end-opacity", layer_opacity))
    return commands


def paint_rect(
    pixels: bytearray,
    width: int,
    rect: tuple[int, int, int, int, bytes],
) -> None:
    left, top, right, bottom, color = rect
    for row in range(top, bottom):
        for column in range(left, right):
            offset = (row * width + column) * 4
            source_over(pixels, offset, color)


def paint_command_row(
    row: bytearray, width: int, row_index: int, command: tuple[Any, ...]
) -> tuple[int, int] | None:
    if command[0] == "rect":
        left, top, right, bottom, color = command[1]
        if row_index < top or row_index >= bottom or left >= right:
            return None
        for column in range(left, right):
            source_over(row, column * 4, color)
        return left, right
    if command[0] == "image":
        (
            _, destination, source_left, source_top,
            source_width, source_height, source,
        ) = command
        left, top, right, bottom = destination
        if row_index < top or row_index >= bottom or left >= right:
            return None
        source_y = source_top + row_index - top
        if source_y < 0 or source_y >= source_height:
            raise VerificationFailure("Image source row exceeds prepared pixels")
        for destination_x in range(left, right):
            source_x = source_left + destination_x - left
            if source_x < 0 or source_x >= source_width:
                raise VerificationFailure("Image source column exceeds prepared pixels")
            source_offset = (source_y * source_width + source_x) * 4
            source_over(
                row,
                destination_x * 4,
                source[source_offset : source_offset + 4],
            )
        return left, right
    raise VerificationFailure("unknown paint command")


def union_dirty_range(
    current: tuple[int, int] | None, added: tuple[int, int]
) -> tuple[int, int] | None:
    if added[0] >= added[1]:
        return current
    if current is None:
        return added
    return min(current[0], added[0]), max(current[1], added[1])


def composite_opacity_row(
    destination: bytearray,
    source: bytearray,
    dirty: tuple[int, int],
    opacity: int,
) -> None:
    if opacity < 0 or opacity > 255:
        raise VerificationFailure("opacity8 is outside 0..255")
    for column in range(dirty[0], dirty[1]):
        offset = column * 4
        scaled = bytes(
            mul255(source[offset + channel], opacity) for channel in range(4)
        )
        source_over_premultiplied(destination, offset, scaled)


def maximum_opacity_depth(commands: list[tuple[Any, ...]]) -> int:
    depth = 0
    maximum = 0
    for command in commands:
        if command[0] == "begin-opacity":
            depth += 1
            maximum = max(maximum, depth)
        elif command[0] == "end-opacity":
            depth -= 1
            if depth < 0:
                raise VerificationFailure("opacity command stack underflowed")
    if depth != 0:
        raise VerificationFailure("opacity command stack remained open")
    return maximum


def rasterize_commands(
    pixels: bytearray,
    width: int,
    height: int,
    commands: list[tuple[Any, ...]],
) -> None:
    row_bytes = width * 4
    if len(pixels) != row_bytes * height:
        raise VerificationFailure("surface length diverged from dimensions")
    maximum_depth = maximum_opacity_depth(commands)
    layer_rows = [bytearray(row_bytes) for _ in range(maximum_depth)]
    layer_dirty: list[tuple[int, int] | None] = [None] * maximum_depth
    for row_index in range(height):
        row_start = row_index * row_bytes
        surface_row = bytearray(pixels[row_start : row_start + row_bytes])
        active_depth = 0
        for command in commands:
            if command[0] == "begin-opacity":
                if active_depth >= maximum_depth:
                    raise VerificationFailure("opacity depth exceeded scratch")
                previous = layer_dirty[active_depth]
                if previous is not None:
                    layer_rows[active_depth][previous[0] * 4 : previous[1] * 4] = (
                        b"\0" * ((previous[1] - previous[0]) * 4)
                    )
                layer_dirty[active_depth] = None
                active_depth += 1
                continue
            if command[0] == "end-opacity":
                active_depth -= 1
                if active_depth < 0:
                    raise VerificationFailure("opacity command stack underflowed")
                source_index = active_depth
                dirty = layer_dirty[source_index]
                if dirty is not None:
                    destination = (
                        surface_row
                        if active_depth == 0
                        else layer_rows[active_depth - 1]
                    )
                    composite_opacity_row(
                        destination, layer_rows[source_index], dirty, command[1]
                    )
                    if active_depth > 0:
                        destination_index = active_depth - 1
                        layer_dirty[destination_index] = union_dirty_range(
                            layer_dirty[destination_index], dirty
                        )
                continue
            target = surface_row if active_depth == 0 else layer_rows[active_depth - 1]
            dirty = paint_command_row(target, width, row_index, command)
            if dirty is not None and active_depth > 0:
                layer_index = active_depth - 1
                layer_dirty[layer_index] = union_dirty_range(
                    layer_dirty[layer_index], dirty
                )
        if active_depth != 0:
            raise VerificationFailure("opacity command stack remained open")
        pixels[row_start : row_start + row_bytes] = surface_row


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


def execute(
    document: dict[str, Any],
    dpi: Any,
    layout_document: dict[str, Any],
    layout_module: Any,
) -> dict[str, Any]:
    exact_members(document, {"canvas", "dslVersion", "layoutProfile", "resources"}, "RenderDocument")
    if document["resources"]:
        return {"feature": "RESOURCE_MANIFEST"}
    canvas = document["canvas"]
    if not isinstance(canvas, dict) or not isinstance(canvas.get("children"), list):
        raise VerificationFailure("Canvas shape is invalid")
    if not scene_kinds_supported(canvas["children"]):
        return {"feature": "SCENE_STRUCTURE"}
    pixel = parse_background(canvas.get("backgroundColor"))
    dimensions = surface_dimensions(canvas, dpi)
    if "code" in dimensions:
        return dimensions
    width = dimensions["widthPx"]
    height = dimensions["heightPx"]
    try:
        layout_entries = layout_module.DefiniteLayouter().run(layout_document)
    except (layout_module.VerificationFailure, layout_module.Unsupported) as error:
        raise VerificationFailure(f"independent definite layout failed: {error}") from error
    if not layout_entries:
        raise VerificationFailure("Engine PNG layout omitted the Canvas entry")
    canvas_entry = exact_members(
        layout_entries[0],
        {"occurrenceId", "kind", "layoutBox", "contentBox"},
        "Canvas layout entry",
    )
    if (
        canvas_entry["kind"] != "canvas"
        or canvas_entry["occurrenceId"] != canvas.get("occurrenceId")
    ):
        raise VerificationFailure(
            "Engine PNG Canvas layout identity diverged from the admitted scene"
        )
    layout_box(canvas_entry["layoutBox"], "Canvas LayoutBox")
    layout_box(canvas_entry["contentBox"], "Canvas ContentBox")
    layout_cursor = [1]
    commands = prepare_scene(
        canvas["children"],
        layout_entries,
        layout_cursor,
        canvas,
        dpi,
        width,
        height,
        True,
        (0, 0, width, height),
    )
    if isinstance(commands, dict):
        return commands
    if layout_cursor[0] != len(layout_entries):
        raise VerificationFailure(
            "Engine PNG layout preorder diverged from the admitted scene"
        )
    pixels = bytearray(pixel * (width * height))
    rasterize_commands(pixels, width, height, commands)
    pixels = unpremultiply_surface(pixels)
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
    layout_module = load_definite_layout_module()
    try:
        vectors = json.loads(raw, object_pairs_hook=strict_pairs, parse_float=Decimal)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"vectors are not strict UTF-8 JSON: {error}") from error
    reject_null(vectors)
    layout_vectors = layout_module.load_strict(raw, "Engine PNG vectors")
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
        "enginePngKernel": "PREORDER_DEFINITE_IDENTITY_GROUP_FRAME_STACK_GRID_COMPOSITION_VIEWPORT_SOURCE_CANVAS_BACKGROUND_CONTAIN_CENTER_HOST_SOURCE_HARD_CLIP_RECT_PIXEL_ALIGNED_SOLID_ALPHA_PREMULTIPLIED_SOURCE_OVER_SUBTREE_OPACITY_ROUND_HALF_UP_ISOLATION_RECTANGULAR_CLIP_VISIBILITY_ZERO_OPACITY_SUPPRESSION_PNG_KERNEL_PROFILE_GATED",
        "processRasterImplementation": "ABSENT",
        "daemonOutputPath": "UNWIRED",
        "productRoute": "CLOSED",
        "providerAttempts": 0,
    }, "Engine PNG honest boundary drifted")
    verifier.require(len(vectors["renderedCases"]) == 27, "rendered case count drifted")
    verifier.require(len(vectors["unsupportedCases"]) == 11, "unsupported case count drifted")

    seen: set[str] = set()
    for family in ("renderedCases", "unsupportedCases"):
        for case in vectors[family]:
            exact_members(case, {"id", "documentId", "dpi", "expected"}, "Engine PNG case")
            verifier.require(isinstance(case["id"], str) and case["id"] not in seen, "duplicate case id")
            seen.add(case["id"])
            document = vectors["documents"].get(case["documentId"])
            verifier.require(isinstance(document, dict), f"{case['id']}: document is absent")
            layout_document = layout_vectors["documents"].get(case["documentId"])
            if not isinstance(layout_document, dict):
                raise VerificationFailure(f"{case['id']}: layout document is absent")
            actual = execute(
                document, case["dpi"], layout_document, layout_module
            )
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
