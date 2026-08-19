#!/usr/bin/env python3
"""Independent replay for the Template v1 minimal DesignDSL canonical kernel."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, localcontext
from pathlib import Path
from typing import Any


MAX_RAW_UTF8_BYTES = 16 * 1024 * 1024
MAX_CANONICAL_BYTES = 16 * 1024 * 1024
MAX_JSON_DEPTH = 64
MAX_OBJECT_MEMBERS = 1_024
MAX_ARRAY_ITEMS = 100_000
MAX_TOTAL_VALUES_AND_CONTAINERS = 1_000_000
MAX_STRING_UTF8_BYTES = 1 * 1024 * 1024
MAX_MEMBER_NAME_UTF8_BYTES = 256
MAX_NUMBER_TOKEN_BYTES = 256
HASH_DOMAIN = b"renderweave-design-content/1\0"
UUID_V4 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
RGBA = re.compile(r"^#[0-9A-F]{8}$")
ROOT_MEMBERS = {
    "dslVersion",
    "expressionProfile",
    "displayName",
    "description",
    "definitions",
    "designRoot",
}
CANVAS_MEMBERS = {
    "nodeId",
    "kind",
    "displayName",
    "widthMm",
    "heightMm",
    "backgroundColor",
    "bleed",
    "bindings",
    "children",
}
BLEED_MEMBER_ORDER = ("topMm", "rightMm", "bottomMm", "leftMm")
BLEED_MEMBERS = set(BLEED_MEMBER_ORDER)

# --- Node contract (T14 increment 1: containers) ------------------------------

KIND_BY_NAME = {
    "canvas": "CANVAS",
    "group": "GROUP",
    "frame": "FRAME",
    "stack": "STACK",
    "grid": "GRID",
    "repeat": "REPEAT",
    "text": "TEXT",
    "image": "IMAGE",
    "rect": "RECT",
    "ellipse": "ELLIPSE",
    "line": "LINE",
    "polygon": "POLYGON",
    "polyline": "POLYLINE",
    "path": "PATH",
    "qrCode": "QRCODE",
    "barcode": "BARCODE",
}
FUTURE_KINDS = {"conditional", "templateUse"}
COMMON_NODE_MEMBERS = {
    "nodeId",
    "kind",
    "displayName",
    "bindings",
    "placement",
    "render",
    "visible",
    "opacity",
    "transform",
}
CONTAINER_MEMBERS = {"children"}
APPEARANCE_MEMBERS = {"fill", "stroke", "cornerRadii", "padding", "clipContent"}
STACK_MEMBERS = {"direction", "gapMm", "justifyContent", "alignItems"}
GRID_MEMBERS = {"rows", "columns", "rowGapMm", "columnGapMm"}
REPEAT_MEMBERS = {"loopId", "items", "absentPolicy", "itemLayout", "instanceLayout"}
ABSENT_POLICY_TOKENS = {"ERROR", "EMPTY"}
REPEAT_ITEM_TYPES = {"text", "decimal", "date", "time", "boolean"}
STACK_PACKING_SPEC_MEMBERS = {"kind", "direction", "gapMm"}
GRID_PACKING_SPEC_MEMBERS = {"kind", "columns", "columnGapMm", "rowGapMm"}

# --- Visual leaf members (ticket 09 §6-§7) -------------------------------------

TEXT_MEMBERS = {
    "runs",
    "writingMode",
    "horizontalAlign",
    "verticalAlign",
    "lineBreak",
    "overflow",
    "lineHeight",
    "maxLines",
    "padding",
    "stroke",
    "fitMode",
    "minScale",
}
RUN_MEMBERS = {
    "text",
    "fontRef",
    "fontSizePt",
    "color",
    "decoration",
    "letterSpacingPt",
    "letterSpacingFactor",
}
LINE_HEIGHT_MEMBERS = {"type", "factor", "valuePt"}
IMAGE_MEMBERS = {"imageRef", "fit", "sampling"}
RECT_MEMBERS = {"fill", "stroke", "cornerRadii"}
ELLIPSE_MEMBERS = {"fill", "stroke"}
LINE_MEMBERS = {"start", "end", "stroke"}
POLYGON_MEMBERS = {"points", "fill", "stroke"}
POLYLINE_MEMBERS = {"points", "stroke"}
PATH_MEMBERS = {"commands", "fill", "stroke", "fillRule"}
QRCODE_MEMBERS = {"content", "errorCorrectionLevel", "foregroundColor", "backgroundColor"}
BARCODE_MEMBERS = {"format", "value", "foregroundColor", "backgroundColor"}
POINT_MM_MEMBERS = {"xMm", "yMm"}
MOVE_TO_COMMAND_MEMBERS = {"type", "xMm", "yMm"}
LINE_TO_COMMAND_MEMBERS = {"type", "xMm", "yMm"}
QUAD_TO_COMMAND_MEMBERS = {"type", "cxMm", "cyMm", "xMm", "yMm"}
CUBIC_TO_COMMAND_MEMBERS = {"type", "c1xMm", "c1yMm", "c2xMm", "c2yMm", "xMm", "yMm"}
CLOSE_COMMAND_MEMBERS = {"type"}
WRITING_MODE_TOKENS = {"HORIZONTAL_TB", "VERTICAL_RL"}
HORIZONTAL_ALIGN_TOKENS = {"LEFT", "CENTER", "RIGHT", "JUSTIFY", "SPACE_EVENLY"}
VERTICAL_ALIGN_TOKENS = {"TOP", "CENTER", "BOTTOM", "JUSTIFY", "SPACE_EVENLY"}
LINE_BREAK_TOKENS = {"NONE", "WORD", "CHAR"}
TEXT_OVERFLOW_TOKENS = {"VISIBLE", "CLIP", "ELLIPSIS", "FAIL"}
DECORATION_TOKENS = {"NONE", "UNDERLINE", "LINE_THROUGH"}
LINE_HEIGHT_TYPE_TOKENS = {"FACTOR", "FIXED"}
FIT_MODE_TOKENS = {"NONE", "SHRINK_TO_FIT"}
IMAGE_FIT_TOKENS = {"CONTAIN", "COVER", "FILL"}
IMAGE_SAMPLING_TOKENS = {"LINEAR", "NEAREST"}
FILL_RULE_TOKENS = {"NONZERO", "EVEN_ODD"}
QR_ERROR_CORRECTION_TOKENS = {"L", "M", "Q", "H"}
BARCODE_FORMAT_TOKENS = {"EAN_8", "EAN_13", "UPC_A", "CODE_128"}
PATH_COMMAND_TYPES = {"MOVE_TO", "LINE_TO", "QUAD_TO", "CUBIC_TO", "CLOSE"}
LEAF_KINDS = {
    "TEXT",
    "IMAGE",
    "RECT",
    "ELLIPSE",
    "LINE",
    "POLYGON",
    "POLYLINE",
    "PATH",
    "QRCODE",
    "BARCODE",
}
NON_CANVAS_KINDS = {
    "GROUP",
    "FRAME",
    "STACK",
    "GRID",
    "REPEAT",
    "TEXT",
    "IMAGE",
    "RECT",
    "ELLIPSE",
    "LINE",
    "POLYGON",
    "POLYLINE",
    "PATH",
    "QRCODE",
    "BARCODE",
}
FILL_MEMBERS = {"color"}
STROKE_MM_MEMBERS = {"color", "widthMm", "cap", "join"}
PADDING_MEMBER_ORDER = ("topMm", "rightMm", "bottomMm", "leftMm")
PADDING_MEMBERS = set(PADDING_MEMBER_ORDER)
CORNER_RADII_MEMBER_ORDER = ("topLeftMm", "topRightMm", "bottomRightMm", "bottomLeftMm")
CORNER_RADII_MEMBERS = set(CORNER_RADII_MEMBER_ORDER)
TRANSFORM_MEMBERS = {"rotationDeg", "scaleX", "scaleY", "originX", "originY"}
ABSOLUTE_PLACEMENT_MEMBERS = {
    "type",
    "xMm",
    "yMm",
    "widthMode",
    "heightMode",
    "widthMm",
    "heightMm",
    "minWidthMm",
    "minHeightMm",
    "maxWidthMm",
    "maxHeightMm",
    "rightInsetMm",
    "bottomInsetMm",
}
STACK_PLACEMENT_MEMBERS = {
    "type",
    "widthMode",
    "heightMode",
    "widthMm",
    "heightMm",
    "minWidthMm",
    "minHeightMm",
    "maxWidthMm",
    "maxHeightMm",
    "marginTopMm",
    "marginRightMm",
    "marginBottomMm",
    "marginLeftMm",
    "alignSelf",
    "fillWeight",
}
GRID_PLACEMENT_MEMBERS = {
    "type",
    "widthMode",
    "heightMode",
    "widthMm",
    "heightMm",
    "minWidthMm",
    "minHeightMm",
    "maxWidthMm",
    "maxHeightMm",
    "row",
    "column",
    "rowSpan",
    "columnSpan",
    "marginTopMm",
    "marginRightMm",
    "marginBottomMm",
    "marginLeftMm",
    "horizontalAlignSelf",
    "verticalAlignSelf",
}
PACK_PLACEMENT_MEMBERS = {
    "type",
    "widthMode",
    "heightMode",
    "widthMm",
    "heightMm",
    "minWidthMm",
    "minHeightMm",
    "maxWidthMm",
    "maxHeightMm",
}
SIZE_MODE_TOKENS = {"FIXED", "HUG_CONTENT", "FILL"}
STROKE_CAP_TOKENS = {"BUTT", "ROUND", "SQUARE"}
STROKE_JOIN_TOKENS = {"MITER", "ROUND", "BEVEL"}
STACK_DIRECTION_TOKENS = {"ROW", "COLUMN"}
JUSTIFY_CONTENT_TOKENS = {
    "START",
    "CENTER",
    "END",
    "SPACE_BETWEEN",
    "SPACE_AROUND",
    "SPACE_EVENLY",
}
ALIGN_ITEMS_TOKENS = {"START", "CENTER", "END"}
EXPECTED_VARIANT = {
    "CANVAS": "ABSOLUTE",
    "FRAME": "ABSOLUTE",
    "GROUP": "ABSOLUTE",
    "STACK": "STACK",
    "GRID": "GRID",
    "REPEAT": "PACK",
}
SIZE_MODES = {
    "GROUP": {"HUG_CONTENT"},
    "RECT": {"FIXED", "FILL"},
    "ELLIPSE": {"FIXED", "FILL"},
    "QRCODE": {"FIXED", "FILL"},
    "BARCODE": {"FIXED", "FILL"},
    "CANVAS": {"FIXED", "HUG_CONTENT", "FILL"},
    "FRAME": {"FIXED", "HUG_CONTENT", "FILL"},
    "STACK": {"FIXED", "HUG_CONTENT", "FILL"},
    "GRID": {"FIXED", "HUG_CONTENT", "FILL"},
    "REPEAT": {"FIXED", "HUG_CONTENT", "FILL"},
    "TEXT": {"FIXED", "HUG_CONTENT", "FILL"},
    "LINE": {"FIXED", "HUG_CONTENT", "FILL"},
    "POLYGON": {"FIXED", "HUG_CONTENT", "FILL"},
    "POLYLINE": {"FIXED", "HUG_CONTENT", "FILL"},
    "PATH": {"FIXED", "HUG_CONTENT", "FILL"},
    "IMAGE": {"FIXED", "HUG_CONTENT", "FILL"},
}
ALLOWS_CHILDREN_KINDS = {"GROUP", "FRAME", "STACK", "GRID", "REPEAT"}

# --- Definition contract (T15: custom/mapping/expression + ValueSource) ----------

DEFINITION_KINDS = {"custom", "mapping", "expression"}
COMMON_DEFINITION_MEMBERS = {"definitionId", "kind", "displayName"}
CUSTOM_MEMBERS = {"exposure", "valueType", "defaultValue"}
MAPPING_MEMBERS = {"domain", "output", "input", "cases", "otherwise"}
EXPRESSION_MEMBERS = {"domain", "output", "inputs", "source"}
EXPOSURE_TOKENS = {"PUBLIC", "PRIVATE"}
BASE_VALUE_TYPES = {
    "text",
    "decimal",
    "boolean",
    "date",
    "time",
    "color",
    "imageRef",
    "fontRef",
}
LIST_ITEM_TYPES = {"text", "decimal", "boolean", "date", "time", "imageRef", "fontRef"}
VALUE_TYPE_MEMBERS = {"type", "items", "catalogId"}
VALUE_SOURCE_KINDS = {"literal", "context", "loopIndex", "definition", "capability"}
LITERAL_SOURCE_MEMBERS = {"kind", "valueType", "value"}
CONTEXT_SOURCE_MEMBERS = {"kind", "domain", "pointer"}
LOOP_INDEX_SOURCE_MEMBERS = {"kind", "loopId"}
DEFINITION_SOURCE_MEMBERS = {"kind", "definitionId"}
CAPABILITY_SOURCE_MEMBERS = {"kind", "capability", "operation"}
MAPPING_OPERATORS = {
    "IS_ABSENT",
    "IS_PRESENT",
    "EQ",
    "NOT_EQ",
    "GT",
    "GTE",
    "LT",
    "LTE",
    "CONTAINS",
    "STARTS_WITH",
    "ENDS_WITH",
    "PATTERN_MATCH",
    "IS_BLANK",
    "IS_NOT_BLANK",
}
NO_OPERAND_OPERATORS = {"IS_ABSENT", "IS_PRESENT"}
CAPABILITY_OPERATIONS = {
    "CLOCK": {"UTC_DATE", "UTC_TIME"},
    "RANDOM": {"UNIFORM_DECIMAL_0_1"},
}
CASE_MEMBERS = {"operator", "operand", "then"}
OPERAND_MEMBERS = {"valueType", "value"}
EXPRESSION_INPUT_MEMBERS = {"alias", "source"}
DOMAIN_LOOP_MEMBERS = {"kind", "loopId"}
ASSET_REF_MEMBERS = {"assetId"}
ALIAS_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")
TIME_PATTERN = re.compile(r"^\d{2}:\d{2}:\d{2}$")
COLOR_PATTERN = re.compile(r"^#[0-9A-F]{8}$")
MAX_CONTEXT_POINTER_SEGMENTS = 32
MAX_CONTEXT_POINTER_UTF8_BYTES = 1024


@dataclass(frozen=True)
class NumberToken:
    token: str


ZERO = NumberToken("0")


class Rejection(Exception):
    def __init__(
        self,
        code: str,
        stage: str,
        pointer: str = "",
        limit: str | None = None,
    ) -> None:
        super().__init__(code)
        self.result = {
            "outcome": "REJECTED",
            "code": code,
            "stage": stage,
            "pointer": pointer,
            "limit": limit,
        }


def parse_rejection(code: str) -> Rejection:
    return Rejection(code, "DESIGN_PARSE")


def parse_limit(limit: str) -> Rejection:
    return Rejection("DESIGN_DSL_LIMIT_EXCEEDED", "DESIGN_PARSE", limit=limit)


def semantic_rejection(code: str, pointer: str) -> Rejection:
    return Rejection(code, "DESIGN_SEMANTIC_VALIDATION", pointer=pointer)


class Scanner:
    def __init__(self, source: str) -> None:
        self.source = source
        self.offset = 0
        self.total = 0

    def scan(self) -> Any:
        self._whitespace()
        value = self._value(0)
        self._whitespace()
        if self.offset != len(self.source):
            raise parse_rejection("DESIGN_JSON_INVALID")
        return value

    def _value(self, container_depth: int) -> Any:
        self.total += 1
        if self.total > MAX_TOTAL_VALUES_AND_CONTAINERS:
            raise parse_limit("designDslParser.totalValuesAndContainers")
        if self.offset >= len(self.source):
            raise parse_rejection("DESIGN_JSON_INVALID")
        current = self.source[self.offset]
        if current == "{":
            return self._object(self._reserve_depth(container_depth))
        if current == "[":
            return self._array(self._reserve_depth(container_depth))
        if current == '"':
            value = self._string()
            self._bounded_utf8(value, MAX_STRING_UTF8_BYTES, "designDslParser.stringUtf8Bytes")
            return value
        if current == "t" and self._literal("true"):
            return True
        if current == "f" and self._literal("false"):
            return False
        if current == "n" and self._literal("null"):
            return None
        if current == "-" or current.isdigit():
            token = self._number()
            if len(token.encode("ascii")) > MAX_NUMBER_TOKEN_BYTES:
                raise parse_limit("designDslParser.numberTokenBytes")
            return ZERO if token == "0" else NumberToken(token)
        raise parse_rejection("DESIGN_JSON_INVALID")

    def _object(self, container_depth: int) -> dict[str, Any]:
        self.offset += 1
        result: dict[str, Any] = {}
        self._whitespace()
        if self._consume("}"):
            return result
        members = 0
        while True:
            if self.offset >= len(self.source) or self.source[self.offset] != '"':
                raise parse_rejection("DESIGN_JSON_INVALID")
            members += 1
            if members > MAX_OBJECT_MEMBERS:
                raise parse_limit("designDslParser.objectMembers")
            name = self._string()
            self._bounded_utf8(
                name,
                MAX_MEMBER_NAME_UTF8_BYTES,
                "designDslParser.memberNameUtf8Bytes",
            )
            if name in result:
                raise parse_rejection("DESIGN_DUPLICATE_MEMBER")
            self._whitespace()
            if not self._consume(":"):
                raise parse_rejection("DESIGN_JSON_INVALID")
            self._whitespace()
            result[name] = self._value(container_depth)
            self._whitespace()
            if self._consume("}"):
                return result
            if not self._consume(","):
                raise parse_rejection("DESIGN_JSON_INVALID")
            self._whitespace()

    def _array(self, container_depth: int) -> list[Any]:
        self.offset += 1
        result: list[Any] = []
        self._whitespace()
        if self._consume("]"):
            return result
        while True:
            if len(result) >= MAX_ARRAY_ITEMS:
                raise parse_limit("designDslParser.arrayItems")
            result.append(self._value(container_depth))
            self._whitespace()
            if self._consume("]"):
                return result
            if not self._consume(","):
                raise parse_rejection("DESIGN_JSON_INVALID")
            self._whitespace()

    def _string(self) -> str:
        self.offset += 1
        value: list[str] = []
        while self.offset < len(self.source):
            current = self.source[self.offset]
            self.offset += 1
            if current == '"':
                joined = "".join(value)
                try:
                    joined.encode("utf-8")
                except UnicodeEncodeError as error:
                    raise parse_rejection("DESIGN_UTF8_INVALID") from error
                return joined
            if ord(current) < 0x20:
                raise parse_rejection("DESIGN_JSON_INVALID")
            if current != "\\":
                value.append(current)
                continue
            if self.offset >= len(self.source):
                raise parse_rejection("DESIGN_JSON_INVALID")
            escaped = self.source[self.offset]
            self.offset += 1
            simple = {
                '"': '"',
                "\\": "\\",
                "/": "/",
                "b": "\b",
                "f": "\f",
                "n": "\n",
                "r": "\r",
                "t": "\t",
            }
            if escaped in simple:
                value.append(simple[escaped])
                continue
            if escaped != "u":
                raise parse_rejection("DESIGN_JSON_INVALID")
            first = self._hex_quad()
            if 0xD800 <= first <= 0xDBFF:
                if self.source[self.offset : self.offset + 2] == "\\u":
                    self.offset += 2
                    second = self._hex_quad()
                    if 0xDC00 <= second <= 0xDFFF:
                        value.append(chr(0x10000 + ((first - 0xD800) << 10) + second - 0xDC00))
                        continue
                    value.append(chr(first))
                    value.append(chr(second))
                    continue
            value.append(chr(first))
        raise parse_rejection("DESIGN_JSON_INVALID")

    def _hex_quad(self) -> int:
        if self.offset + 4 > len(self.source):
            raise parse_rejection("DESIGN_JSON_INVALID")
        token = self.source[self.offset : self.offset + 4]
        if any(character not in "0123456789abcdefABCDEF" for character in token):
            raise parse_rejection("DESIGN_JSON_INVALID")
        self.offset += 4
        return int(token, 16)

    def _number(self) -> str:
        start = self.offset
        self._consume("-")
        if self._consume("0"):
            if self.offset < len(self.source) and self.source[self.offset].isdigit():
                raise parse_rejection("DESIGN_JSON_INVALID")
        else:
            if self.offset >= len(self.source) or self.source[self.offset] not in "123456789":
                raise parse_rejection("DESIGN_JSON_INVALID")
            self.offset += 1
            while self.offset < len(self.source) and self.source[self.offset].isdigit():
                self.offset += 1
        if self._consume("."):
            digits = self.offset
            while self.offset < len(self.source) and self.source[self.offset].isdigit():
                self.offset += 1
            if self.offset == digits:
                raise parse_rejection("DESIGN_JSON_INVALID")
        if self.offset < len(self.source) and self.source[self.offset] in "eE":
            self.offset += 1
            if self.offset < len(self.source) and self.source[self.offset] in "+-":
                self.offset += 1
            digits = self.offset
            while self.offset < len(self.source) and self.source[self.offset].isdigit():
                self.offset += 1
            if self.offset == digits:
                raise parse_rejection("DESIGN_JSON_INVALID")
        return self.source[start : self.offset]

    def _literal(self, value: str) -> bool:
        if self.source.startswith(value, self.offset):
            self.offset += len(value)
            return True
        return False

    def _reserve_depth(self, current: int) -> int:
        if current >= MAX_JSON_DEPTH:
            raise parse_limit("designDslParser.jsonDepth")
        return current + 1

    def _bounded_utf8(self, value: str, maximum: int, limit: str) -> None:
        try:
            size = len(value.encode("utf-8"))
        except UnicodeEncodeError as error:
            raise parse_rejection("DESIGN_UTF8_INVALID") from error
        if size > maximum:
            raise parse_limit(limit)

    def _whitespace(self) -> None:
        while self.offset < len(self.source) and self.source[self.offset] in " \t\r\n":
            self.offset += 1

    def _consume(self, value: str) -> bool:
        if self.source.startswith(value, self.offset):
            self.offset += len(value)
            return True
        return False


def parse(raw: bytes) -> Any:
    if len(raw) > MAX_RAW_UTF8_BYTES:
        raise parse_limit("designDslParser.rawUtf8Bytes")
    if raw.startswith(b"\xef\xbb\xbf"):
        raise parse_rejection("DESIGN_UTF8_INVALID")
    try:
        source = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise parse_rejection("DESIGN_UTF8_INVALID") from error
    return Scanner(source).scan()


def escape_pointer(token: str) -> str:
    return token.replace("~", "~0").replace("/", "~1")


def reject_null(value: Any, pointer: str = "") -> None:
    if value is None:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    if isinstance(value, dict):
        for name, child in value.items():
            reject_null(child, pointer + "/" + escape_pointer(name))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_null(child, pointer + "/" + str(index))


def require_object(value: Any, pointer: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise semantic_rejection("DESIGN_STRUCTURE_INVALID", pointer)
    return value


def require_array(value: Any, pointer: str) -> list[Any]:
    if not isinstance(value, list):
        raise semantic_rejection("DESIGN_STRUCTURE_INVALID", pointer)
    return value


def require_member(value: dict[str, Any], name: str, pointer: str) -> Any:
    if name not in value:
        raise semantic_rejection("DESIGN_STRUCTURE_INVALID", pointer)
    return value[name]


def require_string(value: Any, pointer: str) -> str:
    if not isinstance(value, str):
        raise semantic_rejection("DESIGN_STRUCTURE_INVALID", pointer)
    return value


def reject_unknown(value: dict[str, Any], allowed: set[str], pointer: str) -> None:
    for name in value:
        if name not in allowed:
            raise semantic_rejection("DESIGN_MEMBER_UNKNOWN", pointer + "/" + escape_pointer(name))


def java_trim(value: str) -> str:
    start = 0
    end = len(value)
    while start < end and ord(value[start]) <= 0x20:
        start += 1
    while end > start and ord(value[end - 1]) <= 0x20:
        end -= 1
    return value[start:end]


def metadata(
    value: dict[str, Any],
    name: str,
    maximum: int,
    blank_may_disappear: bool,
    pointer: str,
) -> str:
    normalized = java_trim(require_string(require_member(value, name, pointer), pointer))
    length = len(normalized)
    if (not blank_may_disappear and length == 0) or length > maximum:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    return normalized


def decimal_member(
    value: dict[str, Any],
    name: str,
    pointer: str,
    allow_zero: bool,
) -> None:
    token = require_member(value, name, pointer)
    if not isinstance(token, NumberToken):
        raise semantic_rejection("DESIGN_STRUCTURE_INVALID", pointer)
    try:
        number = Decimal(token.token)
    except InvalidOperation as error:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer) from error
    if number < 0 or (not allow_zero and number == 0):
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def decimal_value(value: Any, pointer: str) -> Decimal:
    if not isinstance(value, NumberToken):
        raise semantic_rejection("DESIGN_STRUCTURE_INVALID", pointer)
    try:
        return Decimal(value.token)
    except InvalidOperation as error:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer) from error


def any_decimal_member(value: dict[str, Any], name: str, pointer: str) -> None:
    decimal_value(require_member(value, name, pointer), pointer)


def positive_decimal_member(value: dict[str, Any], name: str, pointer: str) -> None:
    number = decimal_value(require_member(value, name, pointer), pointer)
    if number <= 0:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def non_negative_decimal_member(value: dict[str, Any], name: str, pointer: str) -> None:
    number = decimal_value(require_member(value, name, pointer), pointer)
    if number < 0:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def non_zero_decimal_member(value: dict[str, Any], name: str, pointer: str) -> None:
    number = decimal_value(require_member(value, name, pointer), pointer)
    if number == 0:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def ranged_decimal_member(
    value: dict[str, Any],
    name: str,
    pointer: str,
    minimum: int,
    maximum: int,
) -> None:
    number = decimal_value(require_member(value, name, pointer), pointer)
    if number < minimum or number > maximum:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def ranged_decimal_value(value: Any, pointer: str, minimum: int, maximum: int) -> None:
    number = decimal_value(value, pointer)
    if number < minimum or number > maximum:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def non_negative_integer_member(value: dict[str, Any], name: str, pointer: str) -> None:
    number = decimal_value(require_member(value, name, pointer), pointer)
    if number < 0 or number != number.to_integral_value():
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def positive_integer_member(value: dict[str, Any], name: str, pointer: str) -> None:
    number = decimal_value(require_member(value, name, pointer), pointer)
    if number <= 0 or number != number.to_integral_value():
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def boolean_member(value: dict[str, Any], name: str, pointer: str) -> None:
    member = require_member(value, name, pointer)
    if not isinstance(member, bool):
        raise semantic_rejection("DESIGN_STRUCTURE_INVALID", pointer)


def enum_member(value: dict[str, Any], name: str, allowed: set[str], pointer: str) -> None:
    token = require_string(require_member(value, name, pointer), pointer)
    if token not in allowed:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def size_mode_member(value: dict[str, Any], name: str, pointer: str) -> str:
    token = require_string(require_member(value, name, pointer), pointer)
    if token not in SIZE_MODE_TOKENS:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    return token


def color_member(value: dict[str, Any], name: str, pointer: str) -> None:
    color = require_string(require_member(value, name, pointer), pointer)
    if RGBA.fullmatch(color) is None:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def validate_fill(value: Any, pointer: str) -> None:
    fill = require_object(value, pointer)
    reject_unknown(fill, FILL_MEMBERS, pointer)
    color_member(fill, "color", pointer + "/color")


def validate_stroke_mm(value: Any, pointer: str) -> None:
    stroke = require_object(value, pointer)
    reject_unknown(stroke, STROKE_MM_MEMBERS, pointer)
    color_member(stroke, "color", pointer + "/color")
    positive_decimal_member(stroke, "widthMm", pointer + "/widthMm")
    enum_member(stroke, "cap", STROKE_CAP_TOKENS, pointer + "/cap")
    enum_member(stroke, "join", STROKE_JOIN_TOKENS, pointer + "/join")


def validate_padding(value: Any, pointer: str) -> None:
    padding = require_object(value, pointer)
    reject_unknown(padding, PADDING_MEMBERS, pointer)
    for member in PADDING_MEMBER_ORDER:
        non_negative_decimal_member(padding, member, pointer + "/" + member)


def validate_corner_radii(value: Any, pointer: str) -> None:
    radii = require_object(value, pointer)
    reject_unknown(radii, CORNER_RADII_MEMBERS, pointer)
    for member in CORNER_RADII_MEMBER_ORDER:
        non_negative_decimal_member(radii, member, pointer + "/" + member)


def validate_appearance_members(node: dict[str, Any], pointer: str) -> None:
    if "fill" in node:
        validate_fill(node["fill"], pointer + "/fill")
    if "stroke" in node:
        validate_stroke_mm(node["stroke"], pointer + "/stroke")
    if "cornerRadii" in node:
        validate_corner_radii(node["cornerRadii"], pointer + "/cornerRadii")
    if "padding" in node:
        validate_padding(node["padding"], pointer + "/padding")
    if "clipContent" in node:
        boolean_member(node, "clipContent", pointer + "/clipContent")


def validate_tracks(node: dict[str, Any], name: str, pointer: str) -> None:
    tracks = require_array(require_member(node, name, pointer), pointer)
    if not tracks:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    for index, item in enumerate(tracks):
        track_pointer = pointer + "/" + str(index)
        track = require_object(item, track_pointer)
        type_token = require_string(
            require_member(track, "type", track_pointer + "/type"),
            track_pointer + "/type",
        )
        if type_token == "FIXED":
            reject_unknown(track, {"type", "valueMm"}, track_pointer)
            positive_decimal_member(track, "valueMm", track_pointer + "/valueMm")
        elif type_token == "FRACTION":
            reject_unknown(track, {"type", "weight"}, track_pointer)
            positive_decimal_member(track, "weight", track_pointer + "/weight")
        elif type_token == "AUTO":
            reject_unknown(track, {"type"}, track_pointer)
        else:
            raise semantic_rejection("DESIGN_VALUE_INVALID", track_pointer + "/type")


def validate_stack_members(node: dict[str, Any], pointer: str) -> str:
    direction = "COLUMN"
    if "direction" in node:
        enum_member(node, "direction", STACK_DIRECTION_TOKENS, pointer + "/direction")
        direction = node["direction"]
    if "gapMm" in node:
        non_negative_decimal_member(node, "gapMm", pointer + "/gapMm")
    if "justifyContent" in node:
        enum_member(node, "justifyContent", JUSTIFY_CONTENT_TOKENS, pointer + "/justifyContent")
    if "alignItems" in node:
        enum_member(node, "alignItems", ALIGN_ITEMS_TOKENS, pointer + "/alignItems")
    return direction


def validate_grid_members(node: dict[str, Any], pointer: str) -> None:
    if "rowGapMm" in node:
        non_negative_decimal_member(node, "rowGapMm", pointer + "/rowGapMm")
    if "columnGapMm" in node:
        non_negative_decimal_member(node, "columnGapMm", pointer + "/columnGapMm")
    validate_tracks(node, "rows", pointer + "/rows")
    validate_tracks(node, "columns", pointer + "/columns")


def validate_min_max(placement: dict[str, Any], pointer: str) -> None:
    for axis in ("Width", "Height"):
        min_name = "min" + axis + "Mm"
        max_name = "max" + axis + "Mm"
        if min_name in placement:
            non_negative_decimal_member(placement, min_name, pointer + "/" + min_name)
        if max_name in placement:
            positive_decimal_member(placement, max_name, pointer + "/" + max_name)
        if min_name in placement and max_name in placement:
            minimum = decimal_value(placement[min_name], pointer + "/" + min_name)
            maximum = decimal_value(placement[max_name], pointer + "/" + max_name)
            if minimum > maximum:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/" + min_name)
        if "widthMm" in placement and axis == "Width":
            fixed = decimal_value(placement["widthMm"], pointer + "/widthMm")
            minimum = (
                decimal_value(placement[min_name], pointer + "/" + min_name)
                if min_name in placement
                else None
            )
            maximum = (
                decimal_value(placement[max_name], pointer + "/" + max_name)
                if max_name in placement
                else None
            )
            if minimum is not None and fixed < minimum:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/widthMm")
            if maximum is not None and fixed > maximum:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/widthMm")
        if "heightMm" in placement and axis == "Height":
            fixed = decimal_value(placement["heightMm"], pointer + "/heightMm")
            minimum = (
                decimal_value(placement[min_name], pointer + "/" + min_name)
                if min_name in placement
                else None
            )
            maximum = (
                decimal_value(placement[max_name], pointer + "/" + max_name)
                if max_name in placement
                else None
            )
            if minimum is not None and fixed < minimum:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/heightMm")
            if maximum is not None and fixed > maximum:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/heightMm")


def validate_transform(value: Any, pointer: str) -> None:
    transform = require_object(value, pointer)
    reject_unknown(transform, TRANSFORM_MEMBERS, pointer)
    any_decimal_member(transform, "rotationDeg", pointer + "/rotationDeg")
    non_zero_decimal_member(transform, "scaleX", pointer + "/scaleX")
    non_zero_decimal_member(transform, "scaleY", pointer + "/scaleY")
    ranged_decimal_member(transform, "originX", pointer + "/originX", 0, 1)
    ranged_decimal_member(transform, "originY", pointer + "/originY", 0, 1)


def validate_placement(
    placement: dict[str, Any],
    pointer: str,
    kind: str,
    parent_kind: str,
    parent_direction: str | None,
) -> None:
    variant_token = require_string(
        require_member(placement, "type", pointer + "/type"),
        pointer + "/type",
    )
    expected = EXPECTED_VARIANT[parent_kind]
    if variant_token not in ("ABSOLUTE", "STACK", "GRID", "PACK"):
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/type")
    if variant_token != expected:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/type")
    if variant_token == "ABSOLUTE":
        reject_unknown(placement, ABSOLUTE_PLACEMENT_MEMBERS, pointer)
        any_decimal_member(placement, "xMm", pointer + "/xMm")
        any_decimal_member(placement, "yMm", pointer + "/yMm")
    elif variant_token == "STACK":
        reject_unknown(placement, STACK_PLACEMENT_MEMBERS, pointer)
    elif variant_token == "GRID":
        reject_unknown(placement, GRID_PLACEMENT_MEMBERS, pointer)
        non_negative_integer_member(placement, "row", pointer + "/row")
        non_negative_integer_member(placement, "column", pointer + "/column")
        if "rowSpan" in placement:
            positive_integer_member(placement, "rowSpan", pointer + "/rowSpan")
        if "columnSpan" in placement:
            positive_integer_member(placement, "columnSpan", pointer + "/columnSpan")
        if "horizontalAlignSelf" in placement:
            enum_member(
                placement,
                "horizontalAlignSelf",
                ALIGN_ITEMS_TOKENS,
                pointer + "/horizontalAlignSelf",
            )
        if "verticalAlignSelf" in placement:
            enum_member(
                placement,
                "verticalAlignSelf",
                ALIGN_ITEMS_TOKENS,
                pointer + "/verticalAlignSelf",
            )
    else:
        reject_unknown(placement, PACK_PLACEMENT_MEMBERS, pointer)

    width_mode = size_mode_member(placement, "widthMode", pointer + "/widthMode")
    height_mode = size_mode_member(placement, "heightMode", pointer + "/heightMode")
    modes = SIZE_MODES[kind]
    if variant_token == "PACK" and "FILL" in modes:
        modes = {"FIXED", "HUG_CONTENT"}
    if width_mode not in modes:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/widthMode")
    if height_mode not in modes:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/heightMode")
    if kind == "IMAGE" and width_mode == "HUG_CONTENT" and height_mode == "HUG_CONTENT":
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/heightMode")
    if width_mode == "FIXED":
        positive_decimal_member(placement, "widthMm", pointer + "/widthMm")
    elif "widthMm" in placement:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/widthMm")
    if height_mode == "FIXED":
        positive_decimal_member(placement, "heightMm", pointer + "/heightMm")
    elif "heightMm" in placement:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/heightMm")

    if kind == "GROUP":
        for member in ("minWidthMm", "minHeightMm", "maxWidthMm", "maxHeightMm"):
            if member in placement:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/" + member)
    else:
        validate_min_max(placement, pointer)

    if variant_token == "ABSOLUTE":
        if "rightInsetMm" in placement:
            if width_mode != "FILL":
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/rightInsetMm")
            any_decimal_member(placement, "rightInsetMm", pointer + "/rightInsetMm")
        if "bottomInsetMm" in placement:
            if height_mode != "FILL":
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/bottomInsetMm")
            any_decimal_member(placement, "bottomInsetMm", pointer + "/bottomInsetMm")
    if variant_token == "STACK":
        for member in ("marginTopMm", "marginRightMm", "marginBottomMm", "marginLeftMm"):
            if member in placement:
                any_decimal_member(placement, member, pointer + "/" + member)
        if "alignSelf" in placement:
            enum_member(placement, "alignSelf", ALIGN_ITEMS_TOKENS, pointer + "/alignSelf")
            cross_axis_fill = (
                height_mode == "FILL" if parent_direction == "ROW" else width_mode == "FILL"
            )
            if cross_axis_fill:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/alignSelf")
        if "fillWeight" in placement:
            main_axis_fill = (
                width_mode == "FILL" if parent_direction == "ROW" else height_mode == "FILL"
            )
            if not main_axis_fill:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/fillWeight")
            positive_decimal_member(placement, "fillWeight", pointer + "/fillWeight")
    if variant_token == "GRID":
        for member in ("marginTopMm", "marginRightMm", "marginBottomMm", "marginLeftMm"):
            if member in placement:
                any_decimal_member(placement, member, pointer + "/" + member)
        if width_mode == "FILL" and "horizontalAlignSelf" in placement:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/horizontalAlignSelf")
        if height_mode == "FILL" and "verticalAlignSelf" in placement:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/verticalAlignSelf")


def allowed_members(kind: str) -> set[str]:
    members = set(COMMON_NODE_MEMBERS)
    if kind in ALLOWS_CHILDREN_KINDS:
        members |= CONTAINER_MEMBERS
    if kind in ("FRAME", "STACK", "GRID"):
        members |= APPEARANCE_MEMBERS
    if kind == "STACK":
        members |= STACK_MEMBERS
    elif kind == "GRID":
        members |= GRID_MEMBERS
    elif kind == "REPEAT":
        members |= REPEAT_MEMBERS
    elif kind == "TEXT":
        members |= TEXT_MEMBERS
    elif kind == "IMAGE":
        members |= IMAGE_MEMBERS
    elif kind == "RECT":
        members |= RECT_MEMBERS
    elif kind == "ELLIPSE":
        members |= ELLIPSE_MEMBERS
    elif kind == "LINE":
        members |= LINE_MEMBERS
    elif kind == "POLYGON":
        members |= POLYGON_MEMBERS
    elif kind == "POLYLINE":
        members |= POLYLINE_MEMBERS
    elif kind == "PATH":
        members |= PATH_MEMBERS
    elif kind == "QRCODE":
        members |= QRCODE_MEMBERS
    elif kind == "BARCODE":
        members |= BARCODE_MEMBERS
    return members


def collect_loop_ids(value: Any, loop_ids: set[str]) -> None:
    if isinstance(value, dict):
        kind = value.get("kind")
        loop_id = value.get("loopId")
        if isinstance(kind, str) and kind == "repeat" and isinstance(loop_id, str):
            loop_ids.add(loop_id)
        for member in value.values():
            collect_loop_ids(member, loop_ids)
    elif isinstance(value, list):
        for item in value:
            collect_loop_ids(item, loop_ids)


def validate_children(
    children: list[Any],
    pointer: str,
    parent_kind: str,
    parent_direction: str | None,
    seen_node_ids: set[str],
    seen_loop_ids: set[str],
    output_types: dict[str, str],
    loop_ids: set[str],
) -> list[Any]:
    normalized: list[Any] = []
    for index, item in enumerate(children):
        child_pointer = pointer + "/" + str(index)
        child = require_object(item, child_pointer)
        normalized.append(
            validate_non_canvas_node(
                child, child_pointer, parent_kind, parent_direction, seen_node_ids,
                seen_loop_ids, output_types, loop_ids
            )
        )
    return normalized


def validate_non_canvas_node(
    node: dict[str, Any],
    pointer: str,
    parent_kind: str,
    parent_direction: str | None,
    seen_node_ids: set[str],
    seen_loop_ids: set[str],
    output_types: dict[str, str],
    loop_ids: set[str],
) -> dict[str, Any]:
    kind_token = require_string(require_member(node, "kind", pointer + "/kind"), pointer + "/kind")
    kind = KIND_BY_NAME.get(kind_token)
    if kind is None:
        if kind_token in FUTURE_KINDS:
            raise semantic_rejection("DESIGN_KERNEL_SCOPE_UNSUPPORTED", pointer + "/kind")
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/kind")
    if kind == "CANVAS":
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/kind")
    reject_unknown(node, allowed_members(kind), pointer)
    node_id = require_string(require_member(node, "nodeId", pointer + "/nodeId"), pointer + "/nodeId")
    if UUID_V4.fullmatch(node_id) is None or node_id in seen_node_ids:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/nodeId")
    seen_node_ids.add(node_id)
    bindings = require_array(
        require_member(node, "bindings", pointer + "/bindings"),
        pointer + "/bindings",
    )
    if bindings:
        raise semantic_rejection("DESIGN_KERNEL_SCOPE_UNSUPPORTED", pointer + "/bindings")

    normalized = dict(node)
    if "displayName" in node:
        normalized["displayName"] = metadata(
            node, "displayName", 128, False, pointer + "/displayName"
        )
    if "render" in node:
        boolean_member(node, "render", pointer + "/render")
    if "visible" in node:
        boolean_member(node, "visible", pointer + "/visible")
    if "opacity" in node:
        ranged_decimal_member(node, "opacity", pointer + "/opacity", 0, 1)
    if "transform" in node:
        validate_transform(node["transform"], pointer + "/transform")
    placement = require_object(
        require_member(node, "placement", pointer + "/placement"),
        pointer + "/placement",
    )
    validate_placement(placement, pointer + "/placement", kind, parent_kind, parent_direction)
    own_direction = None
    if kind in ("FRAME", "STACK", "GRID"):
        validate_appearance_members(node, pointer)
    if kind == "STACK":
        own_direction = validate_stack_members(node, pointer)
    elif kind == "GRID":
        validate_grid_members(node, pointer)
    elif kind == "REPEAT":
        validate_repeat_members(node, pointer, seen_loop_ids, output_types, loop_ids)
    elif kind == "TEXT":
        validate_text_members(node, pointer)
    elif kind == "IMAGE":
        validate_image_members(node, pointer)
    elif kind == "RECT":
        validate_rect_members(node, pointer)
    elif kind == "ELLIPSE":
        validate_ellipse_members(node, pointer)
    elif kind == "LINE":
        validate_line_members(node, pointer)
    elif kind == "POLYGON":
        validate_polygon_members(node, pointer)
    elif kind == "POLYLINE":
        validate_polyline_members(node, pointer)
    elif kind == "PATH":
        validate_path_members(node, pointer)
    elif kind == "QRCODE":
        validate_qrcode_members(node, pointer)
    elif kind == "BARCODE":
        validate_barcode_members(node, pointer)
    if kind in ALLOWS_CHILDREN_KINDS:
        children = require_array(
            require_member(node, "children", pointer + "/children"),
            pointer + "/children",
        )
        if kind == "REPEAT" and not children:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/children")
        normalized["children"] = validate_children(
            children, pointer + "/children", kind, own_direction, seen_node_ids,
            seen_loop_ids, output_types, loop_ids
        )
    return normalized


def validate_repeat_members(
    node: dict[str, Any],
    pointer: str,
    seen_loop_ids: set[str],
    output_types: dict[str, str],
    loop_ids: set[str],
) -> None:
    loop_id = require_string(
        require_member(node, "loopId", pointer + "/loopId"), pointer + "/loopId"
    )
    if UUID_V4.fullmatch(loop_id) is None or loop_id in seen_loop_ids:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/loopId")
    seen_loop_ids.add(loop_id)
    validate_repeat_items(
        require_member(node, "items", pointer + "/items"),
        pointer + "/items",
        output_types,
        loop_ids,
    )
    enum_member(node, "absentPolicy", ABSENT_POLICY_TOKENS, pointer + "/absentPolicy")
    validate_repeat_packing_spec(
        require_member(node, "itemLayout", pointer + "/itemLayout"), pointer + "/itemLayout"
    )
    validate_repeat_packing_spec(
        require_member(node, "instanceLayout", pointer + "/instanceLayout"),
        pointer + "/instanceLayout",
    )


def validate_repeat_items(
    value: Any,
    pointer: str,
    output_types: dict[str, str],
    loop_ids: set[str],
) -> None:
    source = require_object(value, pointer)
    kind = require_string(require_member(source, "kind", pointer + "/kind"), pointer + "/kind")
    if kind == "literal":
        reject_unknown(source, LITERAL_SOURCE_MEMBERS, pointer)
        value_type = validate_value_type(
            require_member(source, "valueType", pointer + "/valueType"),
            pointer + "/valueType",
        )
        if not is_repeat_list_type(value_type):
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/valueType")
        validate_literal(
            require_member(source, "value", pointer + "/value"), value_type, pointer + "/value"
        )
    elif kind == "definition":
        reject_unknown(source, DEFINITION_SOURCE_MEMBERS, pointer)
        target = require_string(
            require_member(source, "definitionId", pointer + "/definitionId"),
            pointer + "/definitionId",
        )
        if UUID_V4.fullmatch(target) is None:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/definitionId")
        output = output_types.get(target)
        if output is None or not is_repeat_list_type(output):
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/definitionId")
    elif kind == "context":
        reject_unknown(source, CONTEXT_SOURCE_MEMBERS, pointer)
        validate_domain(
            require_member(source, "domain", pointer + "/domain"), pointer + "/domain", loop_ids
        )
        validate_context_pointer(
            require_string(
                require_member(source, "pointer", pointer + "/pointer"), pointer + "/pointer"
            ),
            pointer + "/pointer",
        )
    else:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/kind")


def is_repeat_list_type(type_key: str) -> bool:
    if not type_key.startswith("list<") or not type_key.endswith(">"):
        return False
    return type_key[len("list<") : -1] in REPEAT_ITEM_TYPES


def validate_repeat_packing_spec(value: Any, pointer: str) -> None:
    spec = require_object(value, pointer)
    kind = require_string(require_member(spec, "kind", pointer + "/kind"), pointer + "/kind")
    if kind == "STACK":
        reject_unknown(spec, STACK_PACKING_SPEC_MEMBERS, pointer)
        enum_member(spec, "direction", STACK_DIRECTION_TOKENS, pointer + "/direction")
        if "gapMm" in spec:
            non_negative_decimal_member(spec, "gapMm", pointer + "/gapMm")
    elif kind == "GRID":
        reject_unknown(spec, GRID_PACKING_SPEC_MEMBERS, pointer)
        positive_integer_member(spec, "columns", pointer + "/columns")
        if "columnGapMm" in spec:
            non_negative_decimal_member(spec, "columnGapMm", pointer + "/columnGapMm")
        if "rowGapMm" in spec:
            non_negative_decimal_member(spec, "rowGapMm", pointer + "/rowGapMm")
    else:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/kind")


def validate_definitions(
    definitions: list[Any], loop_ids: set[str]
) -> tuple[list[Any], dict[str, str]]:
    seen_ids: set[str] = set()
    ids: list[str] = []
    edges_by_definition: list[list[tuple[str, str]]] = []
    normalized: list[Any] = []
    output_types: dict[str, str] = {}
    for index, item in enumerate(definitions):
        pointer = "/definitions/" + str(index)
        entry = require_object(item, pointer)
        normalized.append(
            validate_definition(
                entry, pointer, seen_ids, ids, edges_by_definition, loop_ids, output_types
            )
        )
    validate_definition_graph(ids, edges_by_definition)
    normalized.sort(key=definition_id_of)
    return normalized, output_types


def validate_definition(
    entry: dict[str, Any],
    pointer: str,
    seen_ids: set[str],
    ids: list[str],
    edges_by_definition: list[list[tuple[str, str]]],
    loop_ids: set[str],
    output_types: dict[str, str],
) -> dict[str, Any]:
    kind_token = require_string(require_member(entry, "kind", pointer + "/kind"), pointer + "/kind")
    if kind_token not in DEFINITION_KINDS:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/kind")
    allowed = set(COMMON_DEFINITION_MEMBERS)
    if kind_token == "custom":
        allowed |= CUSTOM_MEMBERS
    elif kind_token == "mapping":
        allowed |= MAPPING_MEMBERS
    else:
        allowed |= EXPRESSION_MEMBERS
    reject_unknown(entry, allowed, pointer)
    definition_id = require_string(
        require_member(entry, "definitionId", pointer + "/definitionId"),
        pointer + "/definitionId",
    )
    if UUID_V4.fullmatch(definition_id) is None or definition_id in seen_ids:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/definitionId")
    seen_ids.add(definition_id)
    normalized = dict(entry)
    normalized["displayName"] = metadata(
        entry, "displayName", 128, False, pointer + "/displayName"
    )
    edges: list[tuple[str, str]] = []
    if kind_token == "custom":
        validate_custom_definition(entry, pointer)
        output_types[definition_id] = validate_value_type(
            require_member(entry, "valueType", pointer + "/valueType"), pointer + "/valueType"
        )
    elif kind_token == "mapping":
        validate_mapping_definition(entry, pointer, edges, loop_ids)
        output_types[definition_id] = validate_value_type(
            require_member(entry, "output", pointer + "/output"), pointer + "/output"
        )
    else:
        normalized["inputs"] = validate_expression_definition(entry, pointer, edges, loop_ids)
        output_types[definition_id] = validate_value_type(
            require_member(entry, "output", pointer + "/output"), pointer + "/output"
        )
    ids.append(definition_id)
    edges_by_definition.append(edges)
    return normalized


def validate_custom_definition(entry: dict[str, Any], pointer: str) -> None:
    enum_member(entry, "exposure", EXPOSURE_TOKENS, pointer + "/exposure")
    value_type = validate_value_type(
        require_member(entry, "valueType", pointer + "/valueType"), pointer + "/valueType"
    )
    validate_literal(
        require_member(entry, "defaultValue", pointer + "/defaultValue"),
        value_type,
        pointer + "/defaultValue",
    )


def validate_mapping_definition(
    entry: dict[str, Any],
    pointer: str,
    edges: list[tuple[str, str]],
    loop_ids: set[str],
) -> None:
    validate_domain(require_member(entry, "domain", pointer + "/domain"), pointer + "/domain", loop_ids)
    output = validate_value_type(require_member(entry, "output", pointer + "/output"), pointer + "/output")
    validate_value_source(
        require_member(entry, "input", pointer + "/input"),
        pointer + "/input",
        False,
        edges,
        loop_ids,
    )
    cases = require_array(require_member(entry, "cases", pointer + "/cases"), pointer + "/cases")
    if not cases:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/cases")
    for index, item in enumerate(cases):
        case_pointer = pointer + "/cases/" + str(index)
        case_entry = require_object(item, case_pointer)
        reject_unknown(case_entry, CASE_MEMBERS, case_pointer)
        operator = require_string(
            require_member(case_entry, "operator", case_pointer + "/operator"),
            case_pointer + "/operator",
        )
        if operator not in MAPPING_OPERATORS:
            raise semantic_rejection("DESIGN_VALUE_INVALID", case_pointer + "/operator")
        if operator in NO_OPERAND_OPERATORS:
            if "operand" in case_entry:
                raise semantic_rejection("DESIGN_VALUE_INVALID", case_pointer + "/operand")
        else:
            operand_pointer = case_pointer + "/operand"
            operand = require_object(require_member(case_entry, "operand", operand_pointer), operand_pointer)
            reject_unknown(operand, OPERAND_MEMBERS, operand_pointer)
            operand_type = validate_value_type(
                require_member(operand, "valueType", operand_pointer + "/valueType"),
                operand_pointer + "/valueType",
            )
            validate_literal(
                require_member(operand, "value", operand_pointer + "/value"),
                operand_type,
                operand_pointer + "/value",
            )
        then_type = validate_value_source(
            require_member(case_entry, "then", case_pointer + "/then"),
            case_pointer + "/then",
            False,
            edges,
            loop_ids,
        )
        if then_type is not None and then_type != output:
            raise semantic_rejection("DESIGN_VALUE_INVALID", case_pointer + "/then/valueType")
    otherwise_type = validate_value_source(
        require_member(entry, "otherwise", pointer + "/otherwise"),
        pointer + "/otherwise",
        False,
        edges,
        loop_ids,
    )
    if otherwise_type is not None and otherwise_type != output:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/otherwise/valueType")


def validate_expression_definition(
    entry: dict[str, Any],
    pointer: str,
    edges: list[tuple[str, str]],
    loop_ids: set[str],
) -> list[Any]:
    validate_domain(require_member(entry, "domain", pointer + "/domain"), pointer + "/domain", loop_ids)
    validate_value_type(require_member(entry, "output", pointer + "/output"), pointer + "/output")
    inputs = require_array(require_member(entry, "inputs", pointer + "/inputs"), pointer + "/inputs")
    aliases: dict[str, str] = {}
    normalized_inputs: list[Any] = []
    for index, item in enumerate(inputs):
        input_pointer = pointer + "/inputs/" + str(index)
        input_entry = require_object(item, input_pointer)
        reject_unknown(input_entry, EXPRESSION_INPUT_MEMBERS, input_pointer)
        alias = require_string(
            require_member(input_entry, "alias", input_pointer + "/alias"), input_pointer + "/alias"
        )
        if ALIAS_PATTERN.fullmatch(alias) is None or alias in aliases:
            raise semantic_rejection("DESIGN_VALUE_INVALID", input_pointer + "/alias")
        aliases[alias] = input_pointer + "/alias"
        validate_value_source(
            require_member(input_entry, "source", input_pointer + "/source"),
            input_pointer + "/source",
            True,
            edges,
            loop_ids,
        )
        normalized_inputs.append(input_entry)
    source = require_string(require_member(entry, "source", pointer + "/source"), pointer + "/source")
    used: set[str] = set()
    scan_expression_input_usage(source, used)
    for alias in aliases:
        if alias not in used:
            raise semantic_rejection("DESIGN_VALUE_INVALID", aliases[alias])
    normalized_inputs.sort(key=lambda item: require_string(item["alias"], ""))
    return normalized_inputs


def scan_expression_input_usage(source: str, used: set[str]) -> None:
    index = 0
    length = len(source)
    while index < length:
        current = source[index]
        if current == "'":
            index = skip_expression_string(source, index + 1)
            continue
        if is_ascii_identifier_start(current):
            token_start = index
            while index < length and is_ascii_identifier_part(source[index]):
                index += 1
            if source[token_start:index] == "input":
                index = skip_expression_whitespace(source, index)
                if index < length and source[index] == ".":
                    index = skip_expression_whitespace(source, index + 1)
                    alias_start = index
                    while index < length and is_ascii_identifier_part(source[index]):
                        index += 1
                    if index > alias_start:
                        used.add(source[alias_start:index])
            continue
        index += 1


def skip_expression_string(source: str, index: int) -> int:
    length = len(source)
    while index < length:
        current = source[index]
        if current == "\\":
            index += 2
            continue
        if current == "'":
            return index + 1
        index += 1
    return index


def skip_expression_whitespace(source: str, index: int) -> int:
    length = len(source)
    while index < length and source[index] in " \t\r\n":
        index += 1
    return index


def is_ascii_identifier_start(value: str) -> bool:
    return "a" <= value <= "z" or "A" <= value <= "Z" or value == "_"


def is_ascii_identifier_part(value: str) -> bool:
    return is_ascii_identifier_start(value) or "0" <= value <= "9"


def validate_domain(value: Any, pointer: str, loop_ids: set[str]) -> None:
    if isinstance(value, str):
        if value != "invocation":
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
        return
    domain = require_object(value, pointer)
    reject_unknown(domain, DOMAIN_LOOP_MEMBERS, pointer)
    kind = require_string(require_member(domain, "kind", pointer + "/kind"), pointer + "/kind")
    if kind != "loop":
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/kind")
    loop_id = require_string(
        require_member(domain, "loopId", pointer + "/loopId"), pointer + "/loopId"
    )
    if UUID_V4.fullmatch(loop_id) is None or loop_id not in loop_ids:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/loopId")


def validate_value_type(value: Any, pointer: str) -> str:
    if isinstance(value, str):
        if value not in BASE_VALUE_TYPES:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
        return value
    object_value = require_object(value, pointer)
    reject_unknown(object_value, VALUE_TYPE_MEMBERS, pointer)
    type_token = require_string(
        require_member(object_value, "type", pointer + "/type"), pointer + "/type"
    )
    if type_token == "list":
        items = require_string(
            require_member(object_value, "items", pointer + "/items"), pointer + "/items"
        )
        if items not in LIST_ITEM_TYPES:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/items")
        return "list<" + items + ">"
    if type_token == "enum":
        require_string(
            require_member(object_value, "catalogId", pointer + "/catalogId"),
            pointer + "/catalogId",
        )
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/catalogId")
    raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/type")


def validate_literal(value: Any, type_key: str, pointer: str) -> None:
    if type_key.startswith("list<"):
        item_type = type_key[len("list<") : -1]
        if not isinstance(value, list):
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
        for index, item in enumerate(value):
            validate_literal(item, item_type, pointer + "/" + str(index))
        return
    if type_key == "text":
        if not isinstance(value, str):
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    elif type_key == "decimal":
        if not isinstance(value, NumberToken):
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    elif type_key == "boolean":
        if not isinstance(value, bool):
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    elif type_key == "date":
        if not isinstance(value, str) or DATE_PATTERN.fullmatch(value) is None:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    elif type_key == "time":
        if not isinstance(value, str) or TIME_PATTERN.fullmatch(value) is None:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    elif type_key == "color":
        if not isinstance(value, str) or COLOR_PATTERN.fullmatch(value) is None:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    elif type_key in ("imageRef", "fontRef"):
        if not isinstance(value, dict):
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
        reject_unknown(value, ASSET_REF_MEMBERS, pointer)
        asset_id = require_string(
            require_member(value, "assetId", pointer + "/assetId"), pointer + "/assetId"
        )
        if UUID_V4.fullmatch(asset_id) is None:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/assetId")
    else:
        raise AssertionError(f"Unreachable value type: {type_key}")


def validate_value_source(
    value: Any,
    pointer: str,
    capability_allowed: bool,
    edges: list[tuple[str, str]],
    loop_ids: set[str],
) -> str | None:
    source = require_object(value, pointer)
    kind = require_string(require_member(source, "kind", pointer + "/kind"), pointer + "/kind")
    if kind == "literal":
        reject_unknown(source, LITERAL_SOURCE_MEMBERS, pointer)
        value_type = validate_value_type(
            require_member(source, "valueType", pointer + "/valueType"),
            pointer + "/valueType",
        )
        validate_literal(
            require_member(source, "value", pointer + "/value"), value_type, pointer + "/value"
        )
        return value_type
    if kind == "context":
        reject_unknown(source, CONTEXT_SOURCE_MEMBERS, pointer)
        validate_domain(
            require_member(source, "domain", pointer + "/domain"), pointer + "/domain", loop_ids
        )
        validate_context_pointer(
            require_string(require_member(source, "pointer", pointer + "/pointer"), pointer + "/pointer"),
            pointer + "/pointer",
        )
        return None
    if kind == "loopIndex":
        reject_unknown(source, LOOP_INDEX_SOURCE_MEMBERS, pointer)
        loop_id = require_string(
            require_member(source, "loopId", pointer + "/loopId"), pointer + "/loopId"
        )
        if UUID_V4.fullmatch(loop_id) is None or loop_id not in loop_ids:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/loopId")
        return None
    if kind == "definition":
        reject_unknown(source, DEFINITION_SOURCE_MEMBERS, pointer)
        target = require_string(
            require_member(source, "definitionId", pointer + "/definitionId"),
            pointer + "/definitionId",
        )
        if UUID_V4.fullmatch(target) is None:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/definitionId")
        edges.append((target, pointer + "/definitionId"))
        return None
    if kind == "capability":
        if not capability_allowed:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/kind")
        reject_unknown(source, CAPABILITY_SOURCE_MEMBERS, pointer)
        capability = require_string(
            require_member(source, "capability", pointer + "/capability"),
            pointer + "/capability",
        )
        operations = CAPABILITY_OPERATIONS.get(capability)
        if operations is None:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/capability")
        operation = require_string(
            require_member(source, "operation", pointer + "/operation"), pointer + "/operation"
        )
        if operation not in operations:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/operation")
        return None
    raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/kind")


def validate_context_pointer(context_pointer: str, pointer: str) -> None:
    if (
        not context_pointer
        or not context_pointer.startswith("/")
        or context_pointer == "/"
    ):
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    segments = 0
    for index, current in enumerate(context_pointer):
        if current == "/":
            segments += 1
            continue
        if current == "~" and (
            index + 1 >= len(context_pointer)
            or (context_pointer[index + 1] != "0" and context_pointer[index + 1] != "1")
        ):
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    if segments > MAX_CONTEXT_POINTER_SEGMENTS:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)
    decoded = context_pointer.replace("~1", "/").replace("~0", "~")
    if len(decoded.encode("utf-8")) > MAX_CONTEXT_POINTER_UTF8_BYTES:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer)


def validate_definition_graph(
    ids: list[str], edges_by_definition: list[list[tuple[str, str]]]
) -> None:
    index_by_id = {definition_id: index for index, definition_id in enumerate(ids)}
    state = [0] * len(ids)
    for start in range(len(ids)):
        if state[start] != 0:
            continue
        path: list[int] = [start]
        cursors: list[int] = [0]
        state[start] = 1
        while path:
            node = path[-1]
            edges = edges_by_definition[node]
            cursor = cursors.pop()
            if cursor >= len(edges):
                state[node] = 2
                path.pop()
                continue
            cursors.append(cursor + 1)
            target, edge_pointer = edges[cursor]
            target_index = index_by_id.get(target)
            if target_index is None:
                raise semantic_rejection("DESIGN_VALUE_INVALID", edge_pointer)
            if state[target_index] == 1:
                raise semantic_rejection("DESIGN_VALUE_INVALID", edge_pointer)
            if state[target_index] == 0:
                state[target_index] = 1
                path.append(target_index)
                cursors.append(0)


def definition_id_of(value: Any) -> str:
    return require_string(value["definitionId"], "")


def validate_text_members(node: dict[str, Any], pointer: str) -> None:
    runs = require_array(require_member(node, "runs", pointer + "/runs"), pointer + "/runs")
    if not runs:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/runs")
    for index, item in enumerate(runs):
        run_pointer = pointer + "/runs/" + str(index)
        run = require_object(item, run_pointer)
        reject_unknown(run, RUN_MEMBERS, run_pointer)
        text = require_string(require_member(run, "text", run_pointer + "/text"), run_pointer + "/text")
        for character in text:
            if ord(character) < 0x20 and character != "\n":
                raise semantic_rejection("DESIGN_VALUE_INVALID", run_pointer + "/text")
        validate_asset_ref(require_member(run, "fontRef", run_pointer + "/fontRef"), run_pointer + "/fontRef")
        positive_decimal_member(run, "fontSizePt", run_pointer + "/fontSizePt")
        color_member(run, "color", run_pointer + "/color")
        enum_member(run, "decoration", DECORATION_TOKENS, run_pointer + "/decoration")
        has_pt = "letterSpacingPt" in run
        has_factor = "letterSpacingFactor" in run
        if not has_pt and not has_factor:
            raise semantic_rejection("DESIGN_STRUCTURE_INVALID", run_pointer + "/letterSpacingPt")
        if has_pt and has_factor:
            raise semantic_rejection("DESIGN_VALUE_INVALID", run_pointer + "/letterSpacingFactor")
        if has_pt:
            any_decimal_member(run, "letterSpacingPt", run_pointer + "/letterSpacingPt")
        else:
            any_decimal_member(run, "letterSpacingFactor", run_pointer + "/letterSpacingFactor")
    if "writingMode" in node:
        enum_member(node, "writingMode", WRITING_MODE_TOKENS, pointer + "/writingMode")
    if "horizontalAlign" in node:
        enum_member(node, "horizontalAlign", HORIZONTAL_ALIGN_TOKENS, pointer + "/horizontalAlign")
    if "verticalAlign" in node:
        enum_member(node, "verticalAlign", VERTICAL_ALIGN_TOKENS, pointer + "/verticalAlign")
    if "lineBreak" in node:
        enum_member(node, "lineBreak", LINE_BREAK_TOKENS, pointer + "/lineBreak")
    overflow = "CLIP"
    if "overflow" in node:
        enum_member(node, "overflow", TEXT_OVERFLOW_TOKENS, pointer + "/overflow")
        overflow = require_string(node["overflow"], pointer + "/overflow")
    if "lineHeight" in node:
        validate_line_height(node["lineHeight"], pointer + "/lineHeight")
    if "maxLines" in node:
        positive_integer_member(node, "maxLines", pointer + "/maxLines")
        if overflow == "VISIBLE":
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/maxLines")
    if "padding" in node:
        validate_padding(node["padding"], pointer + "/padding")
    if "stroke" in node:
        validate_stroke_pt(node["stroke"], pointer + "/stroke")
    if "fitMode" in node:
        enum_member(node, "fitMode", FIT_MODE_TOKENS, pointer + "/fitMode")
        fit_mode = require_string(node["fitMode"], pointer + "/fitMode")
        if fit_mode == "SHRINK_TO_FIT":
            min_scale = require_member(node, "minScale", pointer + "/minScale")
            ranged_decimal_value(min_scale, pointer + "/minScale", 0, 1)
            if decimal_value(min_scale, pointer + "/minScale") == 0:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/minScale")
        elif "minScale" in node:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/minScale")
    elif "minScale" in node:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/minScale")


def validate_line_height(value: Any, pointer: str) -> None:
    line_height = require_object(value, pointer)
    reject_unknown(line_height, LINE_HEIGHT_MEMBERS, pointer)
    type_token = require_string(
        require_member(line_height, "type", pointer + "/type"), pointer + "/type"
    )
    if type_token == "FACTOR":
        if "valuePt" in line_height:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/valuePt")
        positive_decimal_member(line_height, "factor", pointer + "/factor")
    elif type_token == "FIXED":
        if "factor" in line_height:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/factor")
        positive_decimal_member(line_height, "valuePt", pointer + "/valuePt")
    else:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/type")


def validate_stroke_pt(value: Any, pointer: str) -> None:
    stroke = require_object(value, pointer)
    reject_unknown(stroke, {"color", "widthPt", "cap", "join"}, pointer)
    color_member(stroke, "color", pointer + "/color")
    positive_decimal_member(stroke, "widthPt", pointer + "/widthPt")
    enum_member(stroke, "cap", STROKE_CAP_TOKENS, pointer + "/cap")
    enum_member(stroke, "join", STROKE_JOIN_TOKENS, pointer + "/join")


def validate_image_members(node: dict[str, Any], pointer: str) -> None:
    validate_asset_ref(require_member(node, "imageRef", pointer + "/imageRef"), pointer + "/imageRef")
    if "fit" in node:
        enum_member(node, "fit", IMAGE_FIT_TOKENS, pointer + "/fit")
    if "sampling" in node:
        enum_member(node, "sampling", IMAGE_SAMPLING_TOKENS, pointer + "/sampling")


def validate_rect_members(node: dict[str, Any], pointer: str) -> None:
    validate_optional_fill_stroke(node, pointer)
    if "cornerRadii" in node:
        validate_corner_radii(node["cornerRadii"], pointer + "/cornerRadii")


def validate_ellipse_members(node: dict[str, Any], pointer: str) -> None:
    validate_optional_fill_stroke(node, pointer)


def validate_line_members(node: dict[str, Any], pointer: str) -> None:
    start = validate_point_mm(require_member(node, "start", pointer + "/start"), pointer + "/start")
    end = validate_point_mm(require_member(node, "end", pointer + "/end"), pointer + "/end")
    if start[0] == end[0] and start[1] == end[1]:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/end")
    validate_stroke_mm(require_member(node, "stroke", pointer + "/stroke"), pointer + "/stroke")


def validate_polygon_members(node: dict[str, Any], pointer: str) -> None:
    points = validate_point_array(node, pointer, "points", 3)
    if points[0][0] == points[-1][0] and points[0][1] == points[-1][1]:
        raise semantic_rejection(
            "DESIGN_VALUE_INVALID", pointer + "/points/" + str(len(points) - 1)
        )
    collinear = True
    with localcontext() as context:
        context.prec = 100
        for index in range(2, len(points)):
            cross = (points[1][0] - points[0][0]) * (points[index][1] - points[0][1]) - (
                points[1][1] - points[0][1]
            ) * (points[index][0] - points[0][0])
            if cross != 0:
                collinear = False
                break
    if collinear:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/points")
    validate_optional_fill_stroke(node, pointer)


def validate_polyline_members(node: dict[str, Any], pointer: str) -> None:
    validate_point_array(node, pointer, "points", 2)
    validate_stroke_mm(require_member(node, "stroke", pointer + "/stroke"), pointer + "/stroke")


def validate_path_members(node: dict[str, Any], pointer: str) -> None:
    commands = require_array(
        require_member(node, "commands", pointer + "/commands"), pointer + "/commands"
    )
    if not commands:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/commands")
    has_drawing = False
    for index, item in enumerate(commands):
        command_pointer = pointer + "/commands/" + str(index)
        command = require_object(item, command_pointer)
        type_token = require_string(
            require_member(command, "type", command_pointer + "/type"), command_pointer + "/type"
        )
        if type_token == "MOVE_TO":
            reject_unknown(command, MOVE_TO_COMMAND_MEMBERS, command_pointer)
            any_decimal_member(command, "xMm", command_pointer + "/xMm")
            any_decimal_member(command, "yMm", command_pointer + "/yMm")
        elif type_token == "LINE_TO":
            reject_unknown(command, LINE_TO_COMMAND_MEMBERS, command_pointer)
            any_decimal_member(command, "xMm", command_pointer + "/xMm")
            any_decimal_member(command, "yMm", command_pointer + "/yMm")
            has_drawing = True
        elif type_token == "QUAD_TO":
            reject_unknown(command, QUAD_TO_COMMAND_MEMBERS, command_pointer)
            any_decimal_member(command, "cxMm", command_pointer + "/cxMm")
            any_decimal_member(command, "cyMm", command_pointer + "/cyMm")
            any_decimal_member(command, "xMm", command_pointer + "/xMm")
            any_decimal_member(command, "yMm", command_pointer + "/yMm")
            has_drawing = True
        elif type_token == "CUBIC_TO":
            reject_unknown(command, CUBIC_TO_COMMAND_MEMBERS, command_pointer)
            any_decimal_member(command, "c1xMm", command_pointer + "/c1xMm")
            any_decimal_member(command, "c1yMm", command_pointer + "/c1yMm")
            any_decimal_member(command, "c2xMm", command_pointer + "/c2xMm")
            any_decimal_member(command, "c2yMm", command_pointer + "/c2yMm")
            any_decimal_member(command, "xMm", command_pointer + "/xMm")
            any_decimal_member(command, "yMm", command_pointer + "/yMm")
            has_drawing = True
        elif type_token == "CLOSE":
            reject_unknown(command, CLOSE_COMMAND_MEMBERS, command_pointer)
            if index == 0:
                raise semantic_rejection("DESIGN_VALUE_INVALID", command_pointer + "/type")
            if index + 1 < len(commands) and peek_command_type(commands[index + 1]) != "MOVE_TO":
                raise semantic_rejection(
                    "DESIGN_VALUE_INVALID", pointer + "/commands/" + str(index + 1) + "/type"
                )
        else:
            raise semantic_rejection("DESIGN_VALUE_INVALID", command_pointer + "/type")
    first_type = peek_command_type(commands[0])
    if first_type != "MOVE_TO":
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/commands/0/type")
    if not has_drawing:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/commands")
    validate_optional_fill_stroke(node, pointer)
    if "fillRule" in node:
        enum_member(node, "fillRule", FILL_RULE_TOKENS, pointer + "/fillRule")


def peek_command_type(command: Any) -> str | None:
    if isinstance(command, dict) and isinstance(command.get("type"), str):
        return command["type"]
    return None


def validate_qrcode_members(node: dict[str, Any], pointer: str) -> None:
    content = require_string(require_member(node, "content", pointer + "/content"), pointer + "/content")
    if not content:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/content")
    if "errorCorrectionLevel" in node:
        enum_member(node, "errorCorrectionLevel", QR_ERROR_CORRECTION_TOKENS, pointer + "/errorCorrectionLevel")
    if "foregroundColor" in node:
        color_member(node, "foregroundColor", pointer + "/foregroundColor")
    if "backgroundColor" in node:
        color_member(node, "backgroundColor", pointer + "/backgroundColor")


def validate_barcode_members(node: dict[str, Any], pointer: str) -> None:
    format_token = require_string(require_member(node, "format", pointer + "/format"), pointer + "/format")
    if format_token not in BARCODE_FORMAT_TOKENS:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/format")
    value = require_string(require_member(node, "value", pointer + "/value"), pointer + "/value")
    if format_token == "CODE_128":
        if not value or len(value) > 128:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/value")
        for character in value:
            if ord(character) < 0x20 or ord(character) > 0x7E:
                raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/value")
    else:
        expected_length = {"EAN_8": 8, "EAN_13": 13, "UPC_A": 12}[format_token]
        if len(value) != expected_length or not value.isdigit():
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/value")
        total = 0
        for at in range(expected_length - 1):
            digit = int(value[at])
            odd_position = at % 2 == 0
            weight_three = (not odd_position) if format_token == "EAN_13" else odd_position
            total += digit * (3 if weight_three else 1)
        check = (10 - total % 10) % 10
        if int(value[expected_length - 1]) != check:
            raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/value")
    if "foregroundColor" in node:
        color_member(node, "foregroundColor", pointer + "/foregroundColor")
    if "backgroundColor" in node:
        color_member(node, "backgroundColor", pointer + "/backgroundColor")


def validate_point_array(
    node: dict[str, Any], pointer: str, name: str, minimum: int
) -> list[tuple[Decimal, Decimal]]:
    points = require_array(require_member(node, name, pointer + "/" + name), pointer + "/" + name)
    if len(points) < minimum:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/" + name)
    parsed: list[tuple[Decimal, Decimal]] = []
    for index, item in enumerate(points):
        point_pointer = pointer + "/" + name + "/" + str(index)
        parsed_point = validate_point_mm(item, point_pointer)
        if index > 0:
            previous = parsed[index - 1]
            if previous[0] == parsed_point[0] and previous[1] == parsed_point[1]:
                raise semantic_rejection("DESIGN_VALUE_INVALID", point_pointer)
        parsed.append(parsed_point)
    return parsed


def validate_point_mm(value: Any, pointer: str) -> tuple[Decimal, Decimal]:
    point = require_object(value, pointer)
    reject_unknown(point, POINT_MM_MEMBERS, pointer)
    x = decimal_value(require_member(point, "xMm", pointer + "/xMm"), pointer + "/xMm")
    y = decimal_value(require_member(point, "yMm", pointer + "/yMm"), pointer + "/yMm")
    return x, y


def validate_asset_ref(value: Any, pointer: str) -> None:
    ref = require_object(value, pointer)
    reject_unknown(ref, ASSET_REF_MEMBERS, pointer)
    asset_id = require_string(require_member(ref, "assetId", pointer + "/assetId"), pointer + "/assetId")
    if UUID_V4.fullmatch(asset_id) is None:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/assetId")


def validate_optional_fill_stroke(node: dict[str, Any], pointer: str) -> None:
    if "fill" in node:
        validate_fill(node["fill"], pointer + "/fill")
    if "stroke" in node:
        validate_stroke_mm(node["stroke"], pointer + "/stroke")
    if "fill" not in node and "stroke" not in node:
        raise semantic_rejection("DESIGN_VALUE_INVALID", pointer + "/fill")


def validate_and_normalize(parsed: Any) -> dict[str, Any]:
    reject_null(parsed)
    root = require_object(parsed, "")
    reject_unknown(root, ROOT_MEMBERS, "")
    # Best-effort pre-pass: collect authored Repeat loopIds so Definition loop
    # domains / loopIndex sources can resolve before tree validation runs.
    loop_ids: set[str] = set()
    pre_canvas = root.get("designRoot")
    if isinstance(pre_canvas, dict):
        pre_children = pre_canvas.get("children")
        if pre_children is not None:
            collect_loop_ids(pre_children, loop_ids)
    dsl_version = require_string(require_member(root, "dslVersion", "/dslVersion"), "/dslVersion")
    if dsl_version != "renderweave-design/1.0":
        raise semantic_rejection("DESIGN_VERSION_UNSUPPORTED", "/dslVersion")
    expression_profile = require_string(
        require_member(root, "expressionProfile", "/expressionProfile"),
        "/expressionProfile",
    )
    if expression_profile != "renderweave-expression/1.0":
        raise semantic_rejection("DESIGN_VERSION_UNSUPPORTED", "/expressionProfile")
    display_name = metadata(root, "displayName", 128, False, "/displayName")
    definitions = require_array(require_member(root, "definitions", "/definitions"), "/definitions")
    normalized_definitions, output_types = validate_definitions(definitions, loop_ids)

    canvas = require_object(require_member(root, "designRoot", "/designRoot"), "/designRoot")
    reject_unknown(canvas, CANVAS_MEMBERS, "/designRoot")
    kind = require_string(require_member(canvas, "kind", "/designRoot/kind"), "/designRoot/kind")
    if kind != "canvas":
        raise semantic_rejection("DESIGN_VALUE_INVALID", "/designRoot/kind")
    node_id = require_string(
        require_member(canvas, "nodeId", "/designRoot/nodeId"),
        "/designRoot/nodeId",
    )
    if UUID_V4.fullmatch(node_id) is None:
        raise semantic_rejection("DESIGN_VALUE_INVALID", "/designRoot/nodeId")
    decimal_member(canvas, "widthMm", "/designRoot/widthMm", False)
    decimal_member(canvas, "heightMm", "/designRoot/heightMm", False)
    if "backgroundColor" in canvas:
        color = require_string(canvas["backgroundColor"], "/designRoot/backgroundColor")
        if RGBA.fullmatch(color) is None:
            raise semantic_rejection("DESIGN_VALUE_INVALID", "/designRoot/backgroundColor")
    if "bleed" in canvas:
        bleed = require_object(canvas["bleed"], "/designRoot/bleed")
        reject_unknown(bleed, BLEED_MEMBERS, "/designRoot/bleed")
        for name in BLEED_MEMBER_ORDER:
            decimal_member(bleed, name, "/designRoot/bleed/" + name, True)
    bindings = require_array(
        require_member(canvas, "bindings", "/designRoot/bindings"),
        "/designRoot/bindings",
    )
    children = require_array(
        require_member(canvas, "children", "/designRoot/children"),
        "/designRoot/children",
    )
    if bindings:
        raise semantic_rejection("DESIGN_KERNEL_SCOPE_UNSUPPORTED", "/designRoot/bindings")
    normalized_children = validate_children(
        children, "/designRoot/children", "CANVAS", None, set(), set(), output_types, loop_ids
    )

    normalized_canvas = dict(canvas)
    normalized_canvas["children"] = normalized_children
    if "displayName" in canvas:
        normalized_canvas["displayName"] = metadata(
            canvas,
            "displayName",
            128,
            False,
            "/designRoot/displayName",
        )
    normalized_root = dict(root)
    normalized_root["definitions"] = normalized_definitions
    normalized_root["displayName"] = display_name
    if "description" in root:
        description = metadata(root, "description", 2048, True, "/description")
        if description:
            normalized_root["description"] = description
        else:
            del normalized_root["description"]
    normalized_root["designRoot"] = normalized_canvas
    return normalized_root


class CountingSink:
    def __init__(self) -> None:
        self.count = 0

    def emit(self, value: bytes) -> None:
        if len(value) > MAX_CANONICAL_BYTES - self.count:
            raise Rejection(
                "DESIGN_DSL_LIMIT_EXCEEDED",
                "DESIGN_CANONICAL_COUNT",
                limit="designDslParser.canonicalBytes",
            )
        self.count += len(value)

    def zeros(self, count: int) -> None:
        remaining = count
        chunk = b"0" * 4096
        while remaining:
            length = min(remaining, len(chunk))
            self.emit(chunk[:length])
            remaining -= length


class BytesSink(CountingSink):
    def __init__(self) -> None:
        super().__init__()
        self.output = bytearray()

    def emit(self, value: bytes) -> None:
        super().emit(value)
        self.output.extend(value)


def write_json(value: Any, sink: CountingSink) -> None:
    if isinstance(value, dict):
        sink.emit(b"{")
        for index, name in enumerate(sorted(value, key=lambda item: item.encode("utf-8"))):
            if index:
                sink.emit(b",")
            write_string(name, sink)
            sink.emit(b":")
            write_json(value[name], sink)
        sink.emit(b"}")
    elif isinstance(value, list):
        sink.emit(b"[")
        for index, item in enumerate(value):
            if index:
                sink.emit(b",")
            write_json(item, sink)
        sink.emit(b"]")
    elif isinstance(value, str):
        write_string(value, sink)
    elif isinstance(value, NumberToken):
        write_number(value.token, sink)
    elif isinstance(value, bool):
        sink.emit(b"true" if value else b"false")
    else:
        raise AssertionError(f"Unexpected modeled value: {type(value)!r}")


def write_string(value: str, sink: CountingSink) -> None:
    sink.emit(b'"')
    escapes = {
        '"': b'\\"',
        "\\": b"\\\\",
        "\b": b"\\b",
        "\f": b"\\f",
        "\n": b"\\n",
        "\r": b"\\r",
        "\t": b"\\t",
    }
    for character in value:
        if character in escapes:
            sink.emit(escapes[character])
        elif ord(character) < 0x20:
            sink.emit(f"\\u{ord(character):04x}".encode("ascii"))
        else:
            sink.emit(character.encode("utf-8"))
    sink.emit(b'"')


def write_number(token: str, sink: CountingSink) -> None:
    number = Decimal(token)
    if number == 0:
        sink.emit(b"0")
        return
    sign, digits_tuple, exponent = number.as_tuple()
    digits = list(digits_tuple)
    while digits and digits[-1] == 0:
        digits.pop()
        exponent += 1
    rendered = "".join(str(digit) for digit in digits)
    if sign:
        sink.emit(b"-")
    if exponent >= 0:
        sink.emit(rendered.encode("ascii"))
        sink.zeros(exponent)
        return
    point = len(rendered) + exponent
    if point <= 0:
        sink.emit(b"0.")
        sink.zeros(-point)
        sink.emit(rendered.encode("ascii"))
        return
    sink.emit(rendered[:point].encode("ascii"))
    sink.emit(b".")
    sink.emit(rendered[point:].encode("ascii"))


def canonical_bytes(value: Any) -> bytes:
    counter = CountingSink()
    write_json(value, counter)
    output = BytesSink()
    write_json(value, output)
    if output.count != counter.count:
        raise AssertionError("Independent canonical count/output drift")
    return bytes(output.output)


def admit(raw: bytes) -> tuple[dict[str, Any], bytes | None]:
    try:
        modeled = validate_and_normalize(parse(raw))
        canonical = canonical_bytes(modeled)
        return (
            {
                "outcome": "ADMITTED",
                "canonicalBytes": len(canonical),
                "canonicalSha256": hashlib.sha256(canonical).hexdigest(),
                "contentHash": "sha256:"
                + hashlib.sha256(HASH_DOMAIN + canonical).hexdigest(),
            },
            canonical,
        )
    except Rejection as rejected:
        return rejected.result, None


def canvas(spec: dict[str, Any], width_token: str = "210") -> bytes:
    dsl_version = spec.get("dslVersion", "renderweave-design/1.0")
    definitions = spec.get("definitions", "[]")
    children = spec.get("children", "[]")
    canvas_prefix = spec.get("canvasPrefix", "")
    root_suffix = spec.get("rootSuffix", "")
    node_kind = spec.get("nodeKind", "canvas")
    raw = (
        '{"dslVersion":"'
        + dsl_version
        + '","expressionProfile":"renderweave-expression/1.0",'
        + '"displayName":"Baseline","definitions":'
        + definitions
        + ',"designRoot":{'
        + canvas_prefix
        + '"nodeId":"00000000-0000-4000-8000-000000000001",'
        + '"kind":"'
        + node_kind
        + '\",\"widthMm\":'
        + width_token
        + ',"heightMm":297,"bindings":[],"children":'
        + children
        + "}"
        + root_suffix
        + "}"
    )
    return raw.encode("utf-8")


def comma_zeros(count: int) -> str:
    if count == 0:
        return ""
    return "0," * (count - 1) + "0"


def vector_input(spec: dict[str, Any]) -> bytes:
    kind = spec["kind"]
    if kind == "UTF8":
        return spec["text"].encode("utf-8")
    if kind == "HEX":
        return bytes.fromhex(spec["hex"])
    if kind == "UTF8_BOM":
        return b"\xef\xbb\xbf" + spec["text"].encode("utf-8")
    if kind == "CANVAS":
        return canvas(spec)
    if kind == "PADDED_CANVAS":
        raw = canvas(spec)
        return raw + b" " * (spec["totalBytes"] - len(raw))
    if kind == "NESTED_ARRAY":
        depth = spec["depth"]
        return ("[" * depth + "0" + "]" * depth).encode("ascii")
    if kind == "OBJECT_MEMBERS":
        body = ",".join(f'"m{index}":0' for index in range(spec["count"]))
        return ("{" + body + "}").encode("ascii")
    if kind == "ARRAY_ITEMS":
        return ("[" + comma_zeros(spec["count"]) + "]").encode("ascii")
    if kind == "TOTAL_VALUES":
        full = "[" + comma_zeros(spec["fullArrayItems"]) + "]"
        arrays = [full] * spec["fullArrays"]
        arrays.append("[" + comma_zeros(spec["lastArrayItems"]) + "]")
        return ("[" + ",".join(arrays) + "]").encode("ascii")
    if kind == "STRING_BYTES":
        return b'"' + b"a" * spec["count"] + b'"'
    if kind == "MEMBER_NAME_BYTES":
        return b'{"' + b"a" * spec["count"] + b'":0}'
    if kind == "CANVAS_WIDTH_DIGITS":
        return canvas(spec, "1" * spec["count"])
    if kind == "CANVAS_WIDTH_EXPONENT":
        return canvas(spec, "1e" + str(spec["exponent"]))
    raise AssertionError(f"Unknown vector input kind: {kind}")


def expected_pointer(expected: dict[str, Any]) -> str:
    if "pointer" in expected:
        return expected["pointer"]
    repeated = expected["pointerRepeat"]
    return repeated["prefix"] + repeated["value"] * repeated["count"]


def replay_case(vector: dict[str, Any]) -> dict[str, Any]:
    actual, canonical = admit(vector_input(vector["input"]))
    expected = vector["expected"]
    case_id = vector["id"]
    if expected["outcome"] == "ADMITTED":
        frozen = {
            "outcome": "ADMITTED",
            "canonicalBytes": expected["canonicalBytes"],
            "canonicalSha256": expected["canonicalSha256"],
            "contentHash": expected["contentHash"],
        }
        if actual != frozen:
            raise AssertionError(f"{case_id}: expected {frozen!r}, got {actual!r}")
        if "canonicalUtf8" in expected and canonical != expected["canonicalUtf8"].encode("utf-8"):
            raise AssertionError(f"{case_id}: canonical UTF-8 bytes differ")
    else:
        frozen = {
            "outcome": "REJECTED",
            "code": expected["code"],
            "stage": expected["stage"],
            "pointer": expected_pointer(expected),
            "limit": expected["limit"],
        }
        if actual != frozen:
            raise AssertionError(f"{case_id}: expected {frozen!r}, got {actual!r}")
    return {"id": case_id, **actual}


def no_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for name, value in pairs:
        if name in result:
            raise AssertionError(f"Duplicate member in evidence JSON: {name}")
        result[name] = value
    return result


def load_json(path: Path) -> tuple[bytes, dict[str, Any]]:
    raw = path.read_bytes()
    return raw, json.loads(raw.decode("utf-8"), object_pairs_hook=no_duplicate_pairs)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", required=True, type=Path)
    parser.add_argument("--primary-report", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    vector_bytes, manifest = load_json(args.vectors)
    _, primary = load_json(args.primary_report)
    if manifest["vectorVersion"] != "renderweave-template-canonical-kernel-v1/5":
        raise AssertionError("Unexpected vector version")
    if manifest["authorityContext"]["staticSchemaProfile"] != "system-empty@v1":
        raise AssertionError("Unexpected external StaticSchema context")
    if manifest["authorityContext"]["profileAvailability"] != "NOT_REGISTERED":
        raise AssertionError("Partial DesignDSL Profile must remain unavailable")
    if len(manifest["cases"]) != 152:
        raise AssertionError("Vector case count drift")

    results = [replay_case(vector) for vector in manifest["cases"]]
    vector_sha256 = hashlib.sha256(vector_bytes).hexdigest()
    if primary["reportVersion"] != "renderweave-template-kernel-primary/1":
        raise AssertionError("Unexpected Java primary report version")
    if primary["engine"] != "java-primary":
        raise AssertionError("Unexpected primary engine")
    if primary["vectorVersion"] != manifest["vectorVersion"]:
        raise AssertionError("Primary vector version drift")
    if primary["vectorSha256"] != vector_sha256:
        raise AssertionError("Primary vector bytes drift")
    if primary["profileAvailability"] != "NOT_REGISTERED":
        raise AssertionError("Primary report registered a partial Profile")
    if primary["cases"] != len(results) or primary["passed"] != len(results) or primary["failed"] != 0:
        raise AssertionError("Primary report counts differ")
    if primary["results"] != results:
        raise AssertionError("Java primary and independent Python results differ")

    report = {
        "reportVersion": "renderweave-template-kernel-independent/1",
        "engine": "python-independent",
        "assurance": "A2",
        "vectorVersion": manifest["vectorVersion"],
        "vectorSha256": vector_sha256,
        "primaryReportSha256": hashlib.sha256(args.primary_report.read_bytes()).hexdigest(),
        "profileAvailability": "NOT_REGISTERED",
        "cases": len(results),
        "passed": len(results),
        "failed": 0,
        "results": results,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"Template kernel independent replay: {len(results)} cases, "
        f"0 failures, vector sha256:{vector_sha256}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
