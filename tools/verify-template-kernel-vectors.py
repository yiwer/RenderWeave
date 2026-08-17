#!/usr/bin/env python3
"""Independent replay for the Template v1 minimal DesignDSL canonical kernel."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
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


def validate_and_normalize(parsed: Any) -> dict[str, Any]:
    reject_null(parsed)
    root = require_object(parsed, "")
    reject_unknown(root, ROOT_MEMBERS, "")
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
    if definitions:
        raise semantic_rejection("DESIGN_KERNEL_SCOPE_UNSUPPORTED", "/definitions")

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
    if children:
        raise semantic_rejection("DESIGN_KERNEL_SCOPE_UNSUPPORTED", "/designRoot/children")

    normalized_canvas = dict(canvas)
    if "displayName" in canvas:
        normalized_canvas["displayName"] = metadata(
            canvas,
            "displayName",
            128,
            False,
            "/designRoot/displayName",
        )
    normalized_root = dict(root)
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
    if manifest["vectorVersion"] != "renderweave-template-canonical-kernel-v1/1":
        raise AssertionError("Unexpected vector version")
    if manifest["authorityContext"]["staticSchemaProfile"] != "system-empty@v1":
        raise AssertionError("Unexpected external StaticSchema context")
    if manifest["authorityContext"]["profileAvailability"] != "NOT_REGISTERED":
        raise AssertionError("Partial DesignDSL Profile must remain unavailable")
    if len(manifest["cases"]) != 33:
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
