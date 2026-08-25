#!/usr/bin/env python3
"""Independent stdlib replay for the TV1-T113 prepared IMAGE Engine PNG kernel."""

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
import zlib
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


def load_module(filename: str, name: str) -> Any:
    path = Path(__file__).with_name(filename)
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise VerificationFailure(f"cannot load independent module {filename}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


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


def decode_hex(value: Any, label: str) -> bytes:
    if (
        not isinstance(value, str)
        or len(value) % 2 != 0
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise VerificationFailure(f"{label} is not canonical lowercase hex")
    return bytes.fromhex(value)


def paeth(left: int, above: int, upper_left: int) -> int:
    prediction = left + above - upper_left
    left_distance = abs(prediction - left)
    above_distance = abs(prediction - above)
    upper_left_distance = abs(prediction - upper_left)
    if left_distance <= above_distance and left_distance <= upper_left_distance:
        return left
    if above_distance <= upper_left_distance:
        return above
    return upper_left


def orient_rgba8(
    source: bytes, width: int, height: int, orientation: str
) -> tuple[int, int, bytes]:
    swaps = orientation in {
        "TRANSPOSE",
        "ROTATE_90_CW",
        "TRANSVERSE",
        "ROTATE_270_CW",
    }
    target_width, target_height = (height, width) if swaps else (width, height)
    target = bytearray(target_width * target_height * 4)
    for target_y in range(target_height):
        for target_x in range(target_width):
            if orientation == "IDENTITY":
                source_x, source_y = target_x, target_y
            elif orientation == "MIRROR_HORIZONTAL":
                source_x, source_y = width - 1 - target_x, target_y
            elif orientation == "ROTATE_180":
                source_x, source_y = width - 1 - target_x, height - 1 - target_y
            elif orientation == "MIRROR_VERTICAL":
                source_x, source_y = target_x, height - 1 - target_y
            elif orientation == "TRANSPOSE":
                source_x, source_y = target_y, target_x
            elif orientation == "ROTATE_90_CW":
                source_x, source_y = target_y, height - 1 - target_x
            elif orientation == "TRANSVERSE":
                source_x, source_y = width - 1 - target_y, height - 1 - target_x
            elif orientation == "ROTATE_270_CW":
                source_x, source_y = width - 1 - target_y, target_x
            else:
                raise VerificationFailure(f"unknown orientation {orientation}")
            source_offset = (source_y * width + source_x) * 4
            target_offset = (target_y * target_width + target_x) * 4
            target[target_offset : target_offset + 4] = source[source_offset : source_offset + 4]
    return target_width, target_height, bytes(target)


def decode_png_rgba8(body: bytes, prepared_module: Any) -> tuple[int, int, str, bytes]:
    if not body.startswith(b"\x89PNG\r\n\x1a\n"):
        raise VerificationFailure("resource fixture is not PNG")
    position = 8
    width: int | None = None
    height: int | None = None
    orientation_value = 1
    idat = bytearray()
    saw_iend = False
    while position < len(body):
        if position + 12 > len(body):
            raise VerificationFailure("PNG chunk is truncated")
        length = struct.unpack_from(">I", body, position)[0]
        kind = body[position + 4 : position + 8]
        payload_start = position + 8
        payload_end = payload_start + length
        chunk_end = payload_end + 4
        if chunk_end > len(body):
            raise VerificationFailure("PNG chunk exceeds body")
        payload = body[payload_start:payload_end]
        expected_crc = struct.unpack_from(">I", body, payload_end)[0]
        if binascii.crc32(kind + payload) & 0xFFFF_FFFF != expected_crc:
            raise VerificationFailure("PNG chunk CRC drifted")
        if kind == b"IHDR":
            if width is not None or len(payload) != 13:
                raise VerificationFailure("PNG IHDR shape drifted")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
            if (depth, color, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                raise VerificationFailure("PNG fixture is outside RGBA8 non-interlaced scope")
        elif kind == b"eXIf":
            orientation_value = prepared_module.parse_tiff_orientation(payload)
        elif kind == b"IDAT":
            idat.extend(payload)
        elif kind == b"IEND":
            if payload or saw_iend:
                raise VerificationFailure("PNG IEND shape drifted")
            saw_iend = True
            position = chunk_end
            break
        position = chunk_end
    if not saw_iend or position != len(body) or width is None or height is None:
        raise VerificationFailure("PNG terminal structure drifted")
    try:
        filtered = zlib.decompress(bytes(idat))
    except zlib.error as error:
        raise VerificationFailure("PNG IDAT zlib stream drifted") from error
    row_bytes = width * 4
    if len(filtered) != (row_bytes + 1) * height:
        raise VerificationFailure("PNG filtered byte length drifted")
    decoded = bytearray(row_bytes * height)
    cursor = 0
    for row_index in range(height):
        filter_kind = filtered[cursor]
        cursor += 1
        current = bytearray(filtered[cursor : cursor + row_bytes])
        cursor += row_bytes
        previous_offset = (row_index - 1) * row_bytes
        for index in range(row_bytes):
            left = current[index - 4] if index >= 4 else 0
            above = decoded[previous_offset + index] if row_index > 0 else 0
            upper_left = (
                decoded[previous_offset + index - 4]
                if row_index > 0 and index >= 4
                else 0
            )
            if filter_kind == 0:
                value = current[index]
            elif filter_kind == 1:
                value = current[index] + left
            elif filter_kind == 2:
                value = current[index] + above
            elif filter_kind == 3:
                value = current[index] + ((left + above) // 2)
            elif filter_kind == 4:
                value = current[index] + paeth(left, above, upper_left)
            else:
                raise VerificationFailure("PNG row filter is invalid")
            current[index] = value & 0xFF
        decoded[row_index * row_bytes : (row_index + 1) * row_bytes] = current
    orientation = {
        1: "IDENTITY",
        2: "MIRROR_HORIZONTAL",
        3: "ROTATE_180",
        4: "MIRROR_VERTICAL",
        5: "TRANSPOSE",
        6: "ROTATE_90_CW",
        7: "TRANSVERSE",
        8: "ROTATE_270_CW",
    }[orientation_value]
    logical_width, logical_height, rgba = orient_rgba8(
        bytes(decoded), width, height, orientation
    )
    return logical_width, logical_height, orientation, rgba


def replace_image_resource_ids(value: Any, resource_id: str) -> None:
    if isinstance(value, list):
        for child in value:
            replace_image_resource_ids(child, resource_id)
    elif isinstance(value, dict):
        if "imageResourceId" in value:
            value["imageResourceId"] = resource_id
        for child in value.values():
            replace_image_resource_ids(child, resource_id)


def replace_pointer(document: Any, pointer: str, replacement: Any) -> None:
    if not isinstance(pointer, str) or not pointer.startswith("/"):
        raise VerificationFailure("mutation pointer is invalid")
    segments = [segment.replace("~1", "/").replace("~0", "~") for segment in pointer[1:].split("/")]
    target = document
    for segment in segments[:-1]:
        if isinstance(target, list):
            target = target[int(segment)]
        elif isinstance(target, dict) and segment in target:
            target = target[segment]
        else:
            raise VerificationFailure(f"mutation pointer is absent: {pointer}")
    final = segments[-1]
    if isinstance(target, list):
        target[int(final)] = copy.deepcopy(replacement)
    elif isinstance(target, dict) and final in target:
        target[final] = copy.deepcopy(replacement)
    else:
        raise VerificationFailure(f"mutation pointer is absent: {pointer}")


def materialize_document(
    template: dict[str, Any], fixtures: list[dict[str, Any]], mutations: list[Any]
) -> dict[str, Any]:
    document = copy.deepcopy(template)
    resources = [copy.deepcopy(fixture["resource"]) for fixture in fixtures]
    document["resources"] = resources
    replace_image_resource_ids(document["canvas"], resources[0]["resourceId"])
    for mutation in mutations:
        exact_members(mutation, {"operation", "pointer", "value"}, "mutation")
        if mutation["operation"] != "replace":
            raise VerificationFailure("unsupported vector mutation")
        replace_pointer(document, mutation["pointer"], mutation["value"])
    return document


def layout_number_tokens(value: Any, layout_module: Any) -> Any:
    if isinstance(value, bool):
        return value
    if type(value) is int or isinstance(value, Decimal):
        return layout_module.JsonNumber(str(value))
    if isinstance(value, dict):
        return {
            key: layout_number_tokens(child, layout_module)
            for key, child in value.items()
        }
    if isinstance(value, list):
        return [layout_number_tokens(child, layout_module) for child in value]
    return value


def scene_kinds_supported(children: list[Any]) -> bool:
    for child in children:
        if not isinstance(child, dict):
            return False
        if child.get("kind") in {"rect", "image"}:
            continue
        if child.get("kind") in {"group", "frame", "stack", "grid"} and isinstance(
            child.get("children"), list
        ):
            if scene_kinds_supported(child["children"]):
                continue
        return False
    return True


def centered_unit_quarter_turn(child: dict[str, Any]) -> int | None:
    transform = child.get("transform")
    if not isinstance(transform, dict) or set(transform) != {
        "originX",
        "originY",
        "rotationDeg",
        "scaleX",
        "scaleY",
    }:
        return None
    if (
        transform["originX"] != Decimal("0.5")
        or transform["originY"] != Decimal("0.5")
        or transform["scaleX"] != 1
        or transform["scaleY"] != 1
    ):
        return None
    return {
        -360: 0,
        0: 0,
        360: 0,
        -270: 1,
        90: 1,
        -180: 2,
        180: 2,
        -90: 3,
        270: 3,
    }.get(transform["rotationDeg"])


def rotate_square_rgba8(source: bytes, edge: int, quarter_turn: int) -> bytes:
    if edge < 1 or len(source) != edge * edge * 4 or quarter_turn not in {0, 1, 2, 3}:
        raise VerificationFailure("quarter-turn source shape is invalid")
    if quarter_turn == 0:
        return source
    target = bytearray(len(source))
    for destination_y in range(edge):
        for destination_x in range(edge):
            if quarter_turn == 1:
                source_x, source_y = destination_y, edge - 1 - destination_x
            elif quarter_turn == 2:
                source_x, source_y = edge - 1 - destination_x, edge - 1 - destination_y
            else:
                source_x, source_y = edge - 1 - destination_y, destination_x
            source_offset = (source_y * edge + source_x) * 4
            target_offset = (destination_y * edge + destination_x) * 4
            target[target_offset : target_offset + 4] = source[source_offset : source_offset + 4]
    return bytes(target)


def prepare_image(
    child: dict[str, Any],
    entry: Any,
    prepared_images: dict[str, tuple[int, int, bytes]],
    canvas: dict[str, Any],
    dpi: int,
    surface_width: int,
    surface_height: int,
    draw_enabled: bool,
    active_clip: tuple[int, int, int, int],
    engine: Any,
) -> tuple[Any, ...] | None | dict[str, str]:
    box = engine.require_layout_entry(child, entry, "image", False)
    if not draw_enabled:
        return None
    quarter_turn = centered_unit_quarter_turn(child)
    if quarter_turn is None:
        return {"feature": "IMAGE_PAINT"}
    if child.get("fit") not in {"CONTAIN", "COVER", "FILL"} or child.get(
        "sampling"
    ) not in {"LINEAR", "NEAREST"}:
        raise VerificationFailure("admitted Image fit or sampling token is invalid")
    resource_id = child.get("imageResourceId")
    if resource_id not in prepared_images:
        raise VerificationFailure("prepared IMAGE identity diverged from scene")
    source_width, source_height, source = prepared_images[resource_id]
    origin_x, origin_y, width, height = box
    right = origin_x + width
    bottom = origin_y + height
    if not math.isfinite(right) or not math.isfinite(bottom):
        raise VerificationFailure("Image layout box is not finite")
    bleed = canvas["bleed"]
    bleed_left = engine.decimal6(bleed["leftPt"], False)
    bleed_top = engine.decimal6(bleed["topPt"], False)
    edges = (
        engine.exact_layout_device_edge(origin_x, bleed_left, dpi),
        engine.exact_layout_device_edge(origin_y, bleed_top, dpi),
        engine.exact_layout_device_edge(right, bleed_left, dpi),
        engine.exact_layout_device_edge(bottom, bleed_top, dpi),
    )
    if any(edge is None for edge in edges):
        return {"feature": "NON_PIXEL_ALIGNED_IMAGE"}
    left, top, device_right, device_bottom = (int(edge) for edge in edges)
    if device_right - left != source_width or device_bottom - top != source_height:
        return {"feature": "IMAGE_RESAMPLING"}
    if quarter_turn != 0:
        if source_width != source_height:
            return {"feature": "IMAGE_PAINT"}
        source = rotate_square_rgba8(source, source_width, quarter_turn)
    self_clip = (
        min(max(left, 0), surface_width),
        min(max(top, 0), surface_height),
        min(max(device_right, 0), surface_width),
        min(max(device_bottom, 0), surface_height),
    )
    destination = engine.intersect_clip(self_clip, active_clip)
    if destination[0] == destination[2] or destination[1] == destination[3]:
        return None
    source_left = destination[0] - left
    source_top = destination[1] - top
    copied_width = destination[2] - destination[0]
    copied_height = destination[3] - destination[1]
    if (
        source_left < 0
        or source_top < 0
        or source_left + copied_width > source_width
        or source_top + copied_height > source_height
    ):
        raise VerificationFailure("Image source clip exceeds prepared pixels")
    return (
        "image",
        destination,
        source_left,
        source_top,
        source_width,
        source_height,
        source,
    )


def prepare_scene(
    children: list[Any],
    layout_entries: list[dict[str, Any]],
    layout_cursor: list[int],
    prepared_images: dict[str, tuple[int, int, bytes]],
    canvas: dict[str, Any],
    dpi: int,
    width: int,
    height: int,
    ancestor_draw_enabled: bool,
    active_clip: tuple[int, int, int, int],
    engine: Any,
) -> list[tuple[Any, ...]] | dict[str, str]:
    commands: list[tuple[Any, ...]] = []
    for child in children:
        if layout_cursor[0] >= len(layout_entries):
            raise VerificationFailure("layout preorder ended before scene")
        entry = layout_entries[layout_cursor[0]]
        layout_cursor[0] += 1
        kind = child.get("kind")
        draw_enabled, layer_opacity = engine.node_draw_state(
            child, ancestor_draw_enabled
        )
        if layer_opacity is not None:
            commands.append(("begin-opacity",))
        if kind == "rect":
            rect = engine.prepare_rect(
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
        if kind == "image":
            image = prepare_image(
                child,
                entry,
                prepared_images,
                canvas,
                dpi,
                width,
                height,
                draw_enabled,
                active_clip,
                engine,
            )
            if isinstance(image, dict):
                return image
            if image is not None:
                commands.append(image)
            if layer_opacity is not None:
                commands.append(("end-opacity", layer_opacity))
            continue
        if kind == "group":
            group = engine.prepare_group(child, entry, draw_enabled)
            if isinstance(group, dict):
                return group
            descendant_clip = active_clip
            descendant_draw_enabled = group
        elif kind in {"frame", "stack", "grid"}:
            container = engine.prepare_container(
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
        else:
            return {"feature": "SCENE_STRUCTURE"}
        nested = prepare_scene(
            child["children"],
            layout_entries,
            layout_cursor,
            prepared_images,
            canvas,
            dpi,
            width,
            height,
            descendant_draw_enabled,
            descendant_clip,
            engine,
        )
        if isinstance(nested, dict):
            return nested
        commands.extend(nested)
        if layer_opacity is not None:
            commands.append(("end-opacity", layer_opacity))
    return commands


def multiply_divide_255_round_half_up(value: int, alpha: int) -> int:
    return (value * alpha + 127) // 255


def premultiply_straight_rgba8(straight: bytes | bytearray) -> bytes:
    if len(straight) != 4:
        raise VerificationFailure("straight pixel is not RGBA8")
    alpha = straight[3]
    if alpha == 0:
        return b"\0\0\0\0"
    return bytes(
        [
            multiply_divide_255_round_half_up(straight[0], alpha),
            multiply_divide_255_round_half_up(straight[1], alpha),
            multiply_divide_255_round_half_up(straight[2], alpha),
            alpha,
        ]
    )


def source_over_straight_rgba8(
    destination_premultiplied: bytes | bytearray, source_straight: bytes
) -> bytes:
    if len(destination_premultiplied) != 4:
        raise VerificationFailure("destination pixel is not RGBA8")
    source = premultiply_straight_rgba8(source_straight)
    inverse_source_alpha = 255 - source[3]
    return bytes(
        min(
            255,
            source[channel]
            + multiply_divide_255_round_half_up(
                destination_premultiplied[channel], inverse_source_alpha
            ),
        )
        for channel in range(4)
    )


def unpremultiply_rgba8_surface(pixels: bytearray) -> None:
    if len(pixels) % 4 != 0:
        raise VerificationFailure("surface is not RGBA8 aligned")
    for offset in range(0, len(pixels), 4):
        alpha = pixels[offset + 3]
        if alpha == 0:
            pixels[offset : offset + 4] = b"\0\0\0\0"
            continue
        for channel in range(3):
            premultiplied = pixels[offset + channel]
            pixels[offset + channel] = min(
                255, (premultiplied * 255 + alpha // 2) // alpha
            )


def paint_image(pixels: bytearray, surface_width: int, command: tuple[Any, ...]) -> None:
    _, destination, source_left, source_top, source_width, source_height, source = command
    left, top, right, bottom = destination
    for destination_y in range(top, bottom):
        source_y = source_top + destination_y - top
        if source_y >= source_height:
            raise VerificationFailure("Image source row exceeds prepared pixels")
        for destination_x in range(left, right):
            source_x = source_left + destination_x - left
            if source_x >= source_width:
                raise VerificationFailure("Image source column exceeds prepared pixels")
            source_offset = (source_y * source_width + source_x) * 4
            destination_offset = (destination_y * surface_width + destination_x) * 4
            pixels[destination_offset : destination_offset + 4] = source_over_straight_rgba8(
                pixels[destination_offset : destination_offset + 4],
                source[source_offset : source_offset + 4],
            )


def execute(
    document: dict[str, Any],
    dpi: Any,
    prepared_images: dict[str, tuple[int, int, bytes]],
    layout_module: Any,
    prepared_module: Any,
    engine: Any,
) -> dict[str, Any]:
    exact_members(
        document,
        {"canvas", "dslVersion", "layoutProfile", "resources"},
        "RenderDocument",
    )
    canvas = document["canvas"]
    if not isinstance(canvas, dict) or not isinstance(canvas.get("children"), list):
        raise VerificationFailure("Canvas shape is invalid")
    if not scene_kinds_supported(canvas["children"]):
        return {"feature": "SCENE_STRUCTURE"}
    background = engine.parse_background(canvas.get("backgroundColor"))
    if isinstance(background, dict):
        return background
    dimensions = engine.surface_dimensions(canvas, dpi)
    if "code" in dimensions:
        return dimensions
    width, height = dimensions["widthPx"], dimensions["heightPx"]
    layout_input = layout_number_tokens(document, layout_module)
    try:
        entries = layout_module.DefiniteLayouter().run(layout_input)
    except (layout_module.VerificationFailure, layout_module.Unsupported) as error:
        raise VerificationFailure(f"independent prepared layout failed: {error}") from error
    if not entries:
        raise VerificationFailure("layout omitted Canvas")
    canvas_entry = exact_members(
        entries[0],
        {"occurrenceId", "kind", "layoutBox", "contentBox"},
        "Canvas layout entry",
    )
    if canvas_entry["kind"] != "canvas" or canvas_entry["occurrenceId"] != canvas.get(
        "occurrenceId"
    ):
        raise VerificationFailure("Canvas layout identity drifted")
    engine.layout_box(canvas_entry["layoutBox"], "Canvas LayoutBox")
    engine.layout_box(canvas_entry["contentBox"], "Canvas ContentBox")
    cursor = [1]
    commands = prepare_scene(
        canvas["children"],
        entries,
        cursor,
        prepared_images,
        canvas,
        dpi,
        width,
        height,
        True,
        (0, 0, width, height),
        engine,
    )
    if isinstance(commands, dict):
        return commands
    if cursor[0] != len(entries):
        raise VerificationFailure("layout preorder diverged from scene")
    pixels = bytearray(background * (width * height))
    engine.rasterize_commands(pixels, width, height, commands)
    unpremultiply_rgba8_surface(pixels)
    raw_pixels = bytes(pixels)
    encoded = engine.encode_png(width, height, dpi, raw_pixels)
    return {
        "widthPx": width,
        "heightPx": height,
        "mediaType": "image/png",
        "outputProfile": "renderweave-output-png/1.0",
        "byteLength": len(encoded),
        "contentSha256": "sha256:" + hashlib.sha256(encoded).hexdigest(),
        "pixelSha256": "sha256:" + hashlib.sha256(raw_pixels).hexdigest(),
        "exactHex": encoded.hex(),
    }


def verify(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    try:
        vectors = json.loads(
            raw, object_pairs_hook=strict_pairs, parse_float=Decimal
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"vectors are not strict UTF-8 JSON: {error}") from error
    reject_null(vectors)
    exact_members(
        vectors,
        {
            "vectorVersion",
            "authorityContext",
            "resourceFixtures",
            "documentTemplate",
            "renderedCases",
            "unsupportedCases",
        },
        "prepared IMAGE Engine vector manifest",
    )
    verifier = Verifier()
    verifier.require(
        vectors["vectorVersion"] == "renderweave-engine-prepared-image-png-vectors/3",
        "vector identity drifted",
    )
    authority = exact_members(
        vectors["authorityContext"],
        {
            "layoutProfile",
            "resourcePreparationProfile",
            "imagePixels",
            "degenerateMapping",
            "alphaArithmetic",
            "enginePreparedImageKernel",
            "profileAvailability",
            "certificationStatus",
            "processRasterImplementation",
            "daemonOutputPath",
            "productRoute",
            "providerAttempts",
        },
        "authority context",
    )
    expected_authority = {
        "layoutProfile": "renderweave-layout/1.0",
        "resourcePreparationProfile": "renderweave-renderer/1.0",
        "imagePixels": "EXACT_ORIENTATION_NORMALIZED_STRAIGHT_RGBA8",
        "degenerateMapping": "SOURCE_AND_INTEGER_DEVICE_BOX_EXACT_1_TO_1_CENTERED_UNIT_QUARTER_TURN_NO_RESAMPLE",
        "alphaArithmetic": "STRAIGHT_TO_PREMULTIPLIED_MUL255_SOURCE_OVER_AUTHORED_ORDER_SUBTREE_OPACITY_ROUND_HALF_UP_255_SINGLE_FINAL_UNPREMULTIPLY",
        "enginePreparedImageKernel": "PREPARED_IMAGE_ALPHA_1_TO_1_CENTERED_UNIT_QUARTER_TURN_PREMULTIPLIED_SOURCE_OVER_SUBTREE_OPACITY_ROUND_HALF_UP_ISOLATION_EXACT_PNG_AUTOMATED_VERIFIED_PROFILE_GATED",
        "profileAvailability": "NOT_REGISTERED",
        "certificationStatus": "NOT_CERTIFIED",
        "processRasterImplementation": "ABSENT",
        "daemonOutputPath": "UNWIRED",
        "productRoute": "CLOSED",
        "providerAttempts": 0,
    }
    verifier.require(authority == expected_authority, "authority boundary drifted")

    engine = load_module(
        "verify-engine-png-vectors.py", "renderweave_t107_engine_png"
    )
    prepared_module = load_module(
        "verify-prepared-image-layout-vectors.py", "renderweave_t107_prepared_layout"
    )
    layout_module = engine.load_definite_layout_module()
    fixtures = vectors["resourceFixtures"]
    verifier.require(
        isinstance(fixtures, dict)
        and set(fixtures)
        == {"opaque2x2", "orientedPartial1x2", "alpha2x2", "alpha2x2Second"},
        "resource fixture set drifted",
    )
    prepared_images: dict[str, tuple[int, int, bytes]] = {}
    image_dimensions: dict[str, tuple[int, int]] = {}
    for fixture_id, fixture in fixtures.items():
        exact_members(
            fixture,
            {
                "resource",
                "bodyHex",
                "logicalWidthPx",
                "logicalHeightPx",
                "straightRgba8Hex",
            },
            f"{fixture_id} fixture",
        )
        resource = exact_members(
            fixture["resource"],
            {
                "acceptanceProfileId",
                "byteLength",
                "expiresAt",
                "fetchUrl",
                "kind",
                "mediaType",
                "resourceId",
                "sha256",
                "technicalDescriptor",
            },
            f"{fixture_id} resource",
        )
        body = decode_hex(fixture["bodyHex"], f"{fixture_id} body")
        digest = "sha256:" + hashlib.sha256(body).hexdigest()
        verifier.require(resource["sha256"] == digest, f"{fixture_id} digest drifted")
        verifier.require(resource["byteLength"] == len(body), f"{fixture_id} length drifted")
        logical_width, logical_height, orientation, rgba = decode_png_rgba8(
            body, prepared_module
        )
        verifier.require(
            (logical_width, logical_height)
            == (fixture["logicalWidthPx"], fixture["logicalHeightPx"]),
            f"{fixture_id} logical dimensions drifted",
        )
        verifier.require(
            rgba == decode_hex(fixture["straightRgba8Hex"], f"{fixture_id} pixels"),
            f"{fixture_id} decoded pixels drifted",
        )
        descriptor = resource["technicalDescriptor"]
        verifier.require(
            descriptor.get("logicalWidthPx") == logical_width
            and descriptor.get("logicalHeightPx") == logical_height
            and descriptor.get("orientation") == orientation,
            f"{fixture_id} descriptor drifted",
        )
        resource_id = resource["resourceId"]
        verifier.require(
            isinstance(resource_id, str) and resource_id not in prepared_images,
            f"{fixture_id} resource identity drifted",
        )
        prepared_images[resource_id] = logical_width, logical_height, rgba
        image_dimensions[resource_id] = logical_width, logical_height
    prepared_module.install_prepared_image_adapter(layout_module, image_dimensions)

    template = vectors["documentTemplate"]
    exact_members(
        template,
        {"canvas", "dslVersion", "layoutProfile", "resources"},
        "document template",
    )
    rendered_cases = vectors["renderedCases"]
    unsupported_cases = vectors["unsupportedCases"]
    verifier.require(len(rendered_cases) == 23, "rendered case count drifted")
    verifier.require(len(unsupported_cases) == 3, "unsupported case count drifted")
    seen: set[str] = set()
    for family, cases in (("rendered", rendered_cases), ("unsupported", unsupported_cases)):
        for case in cases:
            expected_members = {"id", "resourceFixtureId", "mutations", "dpi"}
            if family == "rendered" and "additionalResourceFixtureIds" in case:
                expected_members.add("additionalResourceFixtureIds")
            expected_members.add("expected" if family == "rendered" else "expectedFeature")
            exact_members(case, expected_members, f"{family} case")
            case_id = case["id"]
            verifier.require(
                isinstance(case_id, str) and case_id not in seen,
                "case id is invalid or duplicated",
            )
            seen.add(case_id)
            fixture = fixtures.get(case["resourceFixtureId"])
            verifier.require(isinstance(fixture, dict), f"{case_id} fixture is absent")
            additional_ids = case.get("additionalResourceFixtureIds", [])
            verifier.require(
                isinstance(additional_ids, list)
                and all(isinstance(item, str) for item in additional_ids),
                f"{case_id} additional fixture ids are invalid",
            )
            case_fixtures = [fixture]
            for fixture_id in additional_ids:
                additional = fixtures.get(fixture_id)
                verifier.require(
                    isinstance(additional, dict),
                    f"{case_id} additional fixture is absent",
                )
                case_fixtures.append(additional)
            document = materialize_document(template, case_fixtures, case["mutations"])
            case_resource_ids = [
                selected["resource"]["resourceId"] for selected in case_fixtures
            ]
            verifier.require(
                len(case_resource_ids) == len(set(case_resource_ids)),
                f"{case_id} resource identities are duplicated",
            )
            actual = execute(
                document,
                case["dpi"],
                {
                    resource_id: prepared_images[resource_id]
                    for resource_id in case_resource_ids
                },
                layout_module,
                prepared_module,
                engine,
            )
            expected = (
                case["expected"]
                if family == "rendered"
                else {"feature": case["expectedFeature"]}
            )
            verifier.require(actual == expected, f"{case_id} result drifted")
    return {
        "verifier": "renderweave-engine-prepared-image-png-python-independent/3",
        "result": "PASS",
        "assurance": "A2",
        "renderedCases": len(rendered_cases),
        "unsupportedCases": len(unsupported_cases),
        "passed": len(seen),
        "total": len(seen),
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": hashlib.sha256(raw).hexdigest(),
        **authority,
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
            "Prepared IMAGE Engine PNG independent replay: "
            f"{report['passed']}/{report['total']} cases, {report['checks']} checks"
        )
        return 0
    except (VerificationFailure, KeyError, TypeError, ValueError) as error:
        print(f"Prepared IMAGE Engine PNG verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
