#!/usr/bin/env python3
"""Independent standard-library replay of the exact RenderDocument authority."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


RESOURCE_LIMITS = {
    "entries": 2_048,
    "uniqueExactContents": 128,
    "occurrenceRawBytes": 2_147_483_648,
    "occurrenceImagePixels": 1_000_000_000,
    "occurrenceFontBytes": 536_870_912,
    "uniqueRawBytes": 268_435_456,
    "uniqueImagePixels": 125_000_000,
    "uniqueFontBytes": 67_108_864,
    "manifestBytes": 4_194_304,
    "fetchUrlUtf8BytesPerEntry": 2_048,
    "fetchUrlUtf8BytesTotal": 4_194_304,
    "imageBytesPerContent": 67_108_864,
    "imageEdgePixelsPerContent": 20_000,
    "imagePixelsPerContent": 100_000_000,
    "fontBytesPerContent": 33_554_432,
}


class Rejected(ValueError):
    pass


def strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise Rejected("duplicate object member")
        result[key] = value
    return result


def load_json_bytes(path: Path) -> tuple[bytes, Any]:
    raw = path.read_bytes()
    return raw, json.loads(raw, object_pairs_hook=strict_pairs)


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_prefixed(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def render_digest(document: str) -> str:
    return sha256_prefixed(b"renderweave-render-document/1\0" + document.encode("utf-8"))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise Rejected(message)


def exact_members(value: Any, allowed: list[str], required: list[str], label: str) -> dict[str, Any]:
    require(isinstance(value, dict), f"{label} must be an object")
    require(set(value) <= set(allowed), f"{label} has an unknown member")
    require(set(required) <= set(value), f"{label} misses a required member")
    return value


def lower_name(name: str, catalog: dict[str, Any]) -> str | None:
    if name == "render":
        return None
    if name in catalog["resourceLowering"]:
        return catalog["resourceLowering"][name]
    return name[:-2] + "Pt" if name.endswith("Mm") else name


@dataclass
class Admission:
    catalog: dict[str, Any]
    next_occurrence: int = 0
    demands: list[tuple[str, str]] = field(default_factory=list)
    static_kinds: set[str] = field(default_factory=set)

    def node_contract(self, kind: str) -> tuple[list[str], list[str]]:
        contract = self.catalog["kinds"][kind]
        self.static_kinds.add(kind)
        allowed = {"kind", "occurrenceId"}
        required = set(allowed)
        for member in contract["properties"]:
            lowered = lower_name(member, self.catalog)
            if lowered is not None:
                allowed.add(lowered)
        for member in contract["requiredProperties"]:
            lowered = lower_name(member, self.catalog)
            if lowered is not None:
                required.add(lowered)
        if kind != "canvas":
            required.update(lower_name(member, self.catalog)
                            for member in self.catalog["commonNodeDefaults"])
        required.update(lower_name(member, self.catalog) for member in contract["defaults"])
        required.update(contract["defaultObjects"])
        if contract["container"] and kind != "compositionViewport":
            allowed.add("children")
            required.add("children")
        return sorted(allowed), sorted(required)

    def occurrence(self, node: dict[str, Any]) -> None:
        expected = f"rwocc_{self.next_occurrence:016x}"
        require(node.get("occurrenceId") == expected, "occurrence preorder drifted")
        self.next_occurrence += 1

    def node(self, raw: Any, root: bool, placement: str | None,
             parent_direction: str | None = None) -> None:
        require(isinstance(raw, dict), "node must be an object")
        kind = raw.get("kind")
        require(kind in self.catalog["kinds"], "unknown RenderDSL kind")
        contract = self.catalog["kinds"][kind]
        require(root == (kind == "canvas"), "Canvas root-only mismatch")
        self.occurrence(raw)
        allowed, required = self.node_contract(kind)
        exact_members(raw, allowed, required, "node")
        self.no_residue(raw)
        if root:
            require("placement" not in raw, "root Canvas has placement")
        else:
            self.placement(raw["placement"], placement, parent_direction)
            require(type(raw["visible"]) is bool, "visible must be boolean")
            require_number(raw["opacity"], "opacity")
            transform = exact_members(raw["transform"],
                                      ["originX", "originY", "rotationDeg", "scaleX", "scaleY"],
                                      ["originX", "originY", "rotationDeg", "scaleX", "scaleY"],
                                      "transform")
            for value in transform.values():
                require_number(value, "transform")
        self.composites(raw)
        if kind in {"rect", "ellipse", "polygon", "path"}:
            require("fill" in raw or "stroke" in raw, "shape has no paint")
        if kind == "image":
            resource_id(raw["imageResourceId"])
            self.demands.append((raw["imageResourceId"], "image"))
        if kind == "text":
            for run in raw["runs"]:
                resource_id(run["fontResourceId"])
                self.demands.append((run["fontResourceId"], "font"))
        if kind == "compositionViewport":
            self.source_canvas(raw["sourceCanvas"])
        elif contract["container"]:
            require(isinstance(raw["children"], list), "children must be an array")
            child_placement = "STACK" if kind == "stack" else "GRID" if kind == "grid" else "ABSOLUTE"
            direction = raw.get("direction") if kind == "stack" else None
            for child in raw["children"]:
                self.node(child, False, child_placement, direction)

    def source_canvas(self, raw: Any) -> None:
        contract = self.catalog["sourceCanvasContract"]
        source = exact_members(raw, contract["members"], contract["requiredMembers"], "sourceCanvas")
        self.no_residue(source)
        self.occurrence(source)
        require("kind" not in source and "bleed" not in source, "sourceCanvas residue")
        for child in source["children"]:
            self.node(child, False, "ABSOLUTE")

    def placement(self, raw: Any, expected: str | None, parent_direction: str | None) -> None:
        require(isinstance(raw, dict), "placement must be an object")
        variant = raw.get("type")
        require(variant == expected and variant != "PACK", "placement variant mismatch")
        contract = self.catalog["renderPlacementContracts"].get(variant)
        require(contract is not None, "unknown placement")
        exact_members(raw, contract["members"], contract["requiredMembers"], "placement")
        width_mode = raw.get("widthMode")
        height_mode = raw.get("heightMode")
        require(width_mode in {"FIXED", "HUG_CONTENT", "FILL"}, "width mode")
        require(height_mode in {"FIXED", "HUG_CONTENT", "FILL"}, "height mode")
        self.axis(raw, width_mode, "widthPt")
        self.axis(raw, height_mode, "heightPt")
        if variant == "ABSOLUTE":
            require_number(raw["xPt"], "xPt")
            require_number(raw["yPt"], "yPt")
            require((width_mode == "FILL") == ("rightInsetPt" in raw), "right inset")
            require((height_mode == "FILL") == ("bottomInsetPt" in raw), "bottom inset")
        elif variant == "STACK":
            for member in ("marginTopPt", "marginRightPt", "marginBottomPt", "marginLeftPt"):
                require_number(raw[member], member)
            require(parent_direction in {"ROW", "COLUMN"}, "parent Stack direction")
            main_fill = width_mode == "FILL" if parent_direction == "ROW" else height_mode == "FILL"
            require(main_fill == ("fillWeight" in raw), "fillWeight presence")
        else:
            for member in ("row", "column"):
                require(type(raw[member]) is int and raw[member] >= 0, member)
            for member in ("rowSpan", "columnSpan"):
                require(type(raw[member]) is int and raw[member] > 0, member)

    @staticmethod
    def axis(raw: dict[str, Any], mode: str, member: str) -> None:
        require((mode == "FIXED") == (member in raw), "fixed size presence")
        if member in raw:
            require_number(raw[member], member)

    def composites(self, node: dict[str, Any]) -> None:
        for name, defaults in self.catalog["objectDefaults"].items():
            if name in node:
                members = sorted(lower_name(member, self.catalog) for member in defaults)
                value = exact_members(node[name], members, members, name)
                for nested in value.values():
                    require_number(nested, name)
        if "fill" in node:
            exact_members(node["fill"], ["color"], ["color"], "fill")
        if "stroke" in node:
            stroke = exact_members(node["stroke"], ["cap", "color", "join", "widthPt"],
                                   ["cap", "color", "join", "widthPt"], "stroke")
            require_number(stroke["widthPt"], "stroke width")
        for name in ("start", "end"):
            if name in node:
                point(node[name])
        for value in node.get("points", []):
            point(value)
        for member in ("rows", "columns"):
            if member in node:
                tracks(node[member])
        if "lineHeight" in node:
            line_height(node["lineHeight"])
        if "runs" in node:
            runs(node["runs"])
        if "commands" in node:
            commands(node["commands"])

    def no_residue(self, value: Any) -> None:
        forbidden = set(self.catalog["dynamicResidueMembers"])

        def walk(item: Any) -> None:
            require(item is not None, "null residue")
            if isinstance(item, dict):
                require(not (set(item) & forbidden), "authored/dynamic residue")
                for nested in item.values():
                    walk(nested)
            elif isinstance(item, list):
                for nested in item:
                    walk(nested)

        walk(value)

    def resources(self, raw: Any) -> None:
        require(isinstance(raw, list), "resources must be an array")
        require(len(raw) == len(self.demands), "resource cardinality")
        require(len(raw) <= RESOURCE_LIMITS["entries"], "resource entries")
        require(len(canonical(raw).encode("utf-8")) <= RESOURCE_LIMITS["manifestBytes"],
                "resource manifest bytes")
        seen: set[str] = set()
        exact_contents: dict[tuple[str, str, int, str], str] = {}
        occurrence_raw = 0
        occurrence_image_pixels = 0
        occurrence_font_bytes = 0
        unique_raw = 0
        unique_image_pixels = 0
        unique_font_bytes = 0
        fetch_url_bytes = 0
        contract = self.catalog["renderResourceContract"]
        for item, demand in zip(raw, self.demands, strict=True):
            resource = exact_members(item, contract["members"], contract["requiredMembers"], "resource")
            resource_id(resource["resourceId"])
            require((resource["resourceId"], resource["kind"]) == demand, "resource order/kind")
            require(resource["resourceId"] not in seen, "duplicate resource")
            seen.add(resource["resourceId"])
            fetch_url = resource["fetchUrl"]
            require(isinstance(fetch_url, str) and fetch_url, "fetchUrl")
            encoded_url_bytes = len(fetch_url.encode("utf-8"))
            require(encoded_url_bytes <= RESOURCE_LIMITS["fetchUrlUtf8BytesPerEntry"],
                    "fetchUrl entry bytes")
            fetch_url_bytes += encoded_url_bytes
            require(fetch_url_bytes <= RESOURCE_LIMITS["fetchUrlUtf8BytesTotal"],
                    "fetchUrl total bytes")
            positive_integer(resource["expiresAt"], "expiresAt")
            digest = resource["sha256"]
            require(isinstance(digest, str) and digest.startswith("sha256:")
                    and len(digest) == 71
                    and all(char in "0123456789abcdef" for char in digest[7:]), "sha256")
            require(resource["acceptanceProfileId"] == "renderweave-asset-acceptance/1.0",
                    "acceptance profile")
            media_type = resource["mediaType"]
            allowed_media = {
                "image": {"image/png", "image/jpeg", "image/webp"},
                "font": {"font/ttf", "font/otf"},
            }
            require(media_type in allowed_media[demand[1]], "kind/mediaType")
            byte_length = positive_integer(resource["byteLength"], "byteLength")
            per_content_limit = (RESOURCE_LIMITS["imageBytesPerContent"]
                                 if demand[1] == "image"
                                 else RESOURCE_LIMITS["fontBytesPerContent"])
            require(byte_length <= per_content_limit, "per-content bytes")
            descriptor_value, image_pixels = descriptor(resource["technicalDescriptor"], demand[1])

            occurrence_raw += byte_length
            require(occurrence_raw <= RESOURCE_LIMITS["occurrenceRawBytes"], "occurrence raw")
            if demand[1] == "image":
                occurrence_image_pixels += image_pixels
                require(occurrence_image_pixels <= RESOURCE_LIMITS["occurrenceImagePixels"],
                        "occurrence image pixels")
            else:
                occurrence_font_bytes += byte_length
                require(occurrence_font_bytes <= RESOURCE_LIMITS["occurrenceFontBytes"],
                        "occurrence font bytes")

            key = (demand[1], digest, byte_length, media_type)
            if key in exact_contents:
                require(exact_contents[key] == descriptor_value, "same-content descriptor drift")
            else:
                require(len(exact_contents) < RESOURCE_LIMITS["uniqueExactContents"],
                        "unique exact contents")
                unique_raw += byte_length
                require(unique_raw <= RESOURCE_LIMITS["uniqueRawBytes"], "unique raw")
                if demand[1] == "image":
                    unique_image_pixels += image_pixels
                    require(unique_image_pixels <= RESOURCE_LIMITS["uniqueImagePixels"],
                            "unique image pixels")
                else:
                    unique_font_bytes += byte_length
                    require(unique_font_bytes <= RESOURCE_LIMITS["uniqueFontBytes"],
                            "unique font bytes")
                exact_contents[key] = descriptor_value


def require_number(value: Any, label: str) -> None:
    require(type(value) in {int, float}, f"{label} must be numeric")


def point(raw: Any) -> None:
    value = exact_members(raw, ["xPt", "yPt"], ["xPt", "yPt"], "point")
    require_number(value["xPt"], "xPt")
    require_number(value["yPt"], "yPt")


def tracks(raw: Any) -> None:
    require(isinstance(raw, list) and raw, "tracks must be nonempty")
    for track in raw:
        kind = track.get("type") if isinstance(track, dict) else None
        if kind == "AUTO":
            exact_members(track, ["type"], ["type"], "track")
        elif kind == "FIXED":
            exact_members(track, ["type", "valuePt"], ["type", "valuePt"], "track")
            require_number(track["valuePt"], "track value")
        elif kind == "FRACTION":
            exact_members(track, ["type", "weight"], ["type", "weight"], "track")
            require_number(track["weight"], "track weight")
        else:
            raise Rejected("track type")


def line_height(raw: Any) -> None:
    require(isinstance(raw, dict), "lineHeight")
    if raw.get("type") == "FACTOR":
        exact_members(raw, ["factor", "type"], ["factor", "type"], "lineHeight")
        require_number(raw["factor"], "lineHeight factor")
    elif raw.get("type") == "FIXED":
        exact_members(raw, ["type", "valuePt"], ["type", "valuePt"], "lineHeight")
        require_number(raw["valuePt"], "lineHeight value")
    else:
        raise Rejected("lineHeight type")


def runs(raw: Any) -> None:
    require(isinstance(raw, list) and raw, "runs must be nonempty")
    allowed = ["color", "decoration", "fontResourceId", "fontSizePt",
               "letterSpacingFactor", "letterSpacingPt", "text"]
    required = ["color", "decoration", "fontResourceId", "fontSizePt", "text"]
    for run in raw:
        exact_members(run, allowed, required, "run")
        require(("letterSpacingPt" in run) != ("letterSpacingFactor" in run), "letter spacing")


def commands(raw: Any) -> None:
    require(isinstance(raw, list) and raw, "commands must be nonempty")
    variants = {
        "MOVE_TO": ["xPt", "yPt"], "LINE_TO": ["xPt", "yPt"],
        "QUAD_TO": ["cxPt", "cyPt", "xPt", "yPt"],
        "CUBIC_TO": ["c1xPt", "c1yPt", "c2xPt", "c2yPt", "xPt", "yPt"],
        "CLOSE": [],
    }
    for command in raw:
        coordinates = variants.get(command.get("type")) if isinstance(command, dict) else None
        require(coordinates is not None, "command type")
        members = ["type", *coordinates]
        exact_members(command, members, members, "command")


def resource_id(value: Any) -> None:
    require(isinstance(value, str) and len(value) == 70 and value.startswith("rwres_")
            and all(char in "0123456789abcdef" for char in value[6:]), "resourceId")


def positive_integer(value: Any, label: str) -> int:
    require(type(value) is int and value > 0, label)
    return value


def descriptor(raw: Any, kind: str) -> tuple[str, int]:
    require(isinstance(raw, dict) and raw.get("kind") == kind, "descriptor kind")
    if kind == "image":
        members = ["colorEncoding", "encodedHeightPx", "encodedWidthPx", "frameCount", "kind",
                   "logicalHeightPx", "logicalWidthPx", "orientation"]
        exact_members(raw, members, members, "descriptor")
        encoded_width = positive_integer(raw["encodedWidthPx"], "encodedWidthPx")
        encoded_height = positive_integer(raw["encodedHeightPx"], "encodedHeightPx")
        logical_width = positive_integer(raw["logicalWidthPx"], "logicalWidthPx")
        logical_height = positive_integer(raw["logicalHeightPx"], "logicalHeightPx")
        require(encoded_width <= RESOURCE_LIMITS["imageEdgePixelsPerContent"]
                and encoded_height <= RESOURCE_LIMITS["imageEdgePixelsPerContent"], "image edge")
        encoded_pixels = encoded_width * encoded_height
        require(encoded_pixels <= RESOURCE_LIMITS["imagePixelsPerContent"], "image pixels")
        orientations = {
            "IDENTITY": False,
            "MIRROR_HORIZONTAL": False,
            "ROTATE_180": False,
            "MIRROR_VERTICAL": False,
            "TRANSPOSE": True,
            "ROTATE_90_CW": True,
            "TRANSVERSE": True,
            "ROTATE_270_CW": True,
        }
        require(raw["orientation"] in orientations, "orientation")
        expected = ((encoded_height, encoded_width) if orientations[raw["orientation"]]
                    else (encoded_width, encoded_height))
        require((logical_width, logical_height) == expected, "logical dimensions")
        require(raw["frameCount"] == 1 and type(raw["frameCount"]) is int, "frameCount")
        require(raw["colorEncoding"] == "SRGB_8BIT", "colorEncoding")
        return canonical(raw), logical_width * logical_height
    else:
        members = ["faceIndex", "flavor", "kind", "unitsPerEm"]
        exact_members(raw, members, members, "descriptor")
        require(type(raw["faceIndex"]) is int and raw["faceIndex"] == 0, "faceIndex")
        require(raw["flavor"] in {"TRUETYPE_GLYF", "CFF"}, "font flavor")
        units_per_em = positive_integer(raw["unitsPerEm"], "unitsPerEm")
        require(16 <= units_per_em <= 16_384, "unitsPerEm")
        return canonical(raw), 0


def admit(document: str, catalog: dict[str, Any]) -> tuple[int, int, set[str]]:
    value = json.loads(document, object_pairs_hook=strict_pairs)
    require(canonical(value) == document, "document is not canonical")
    contract = catalog["renderDocumentContract"]
    root = exact_members(value, contract["members"], contract["requiredMembers"], "document")
    require(root["dslVersion"] == catalog["renderDslVersion"], "dsl identity")
    require(root["layoutProfile"] == catalog["layoutProfile"], "layout identity")
    admission = Admission(catalog)
    admission.node(root["canvas"], True, None)
    admission.resources(root["resources"])
    return admission.next_occurrence, len(root["resources"]), admission.static_kinds


def pointer_parent(value: Any, pointer: str) -> tuple[Any, str]:
    parts = pointer.split("/")[1:]
    current = value
    for token in parts[:-1]:
        token = token.replace("~1", "/").replace("~0", "~")
        current = current[int(token)] if isinstance(current, list) else current[token]
    return current, parts[-1].replace("~1", "/").replace("~0", "~")


def mutate(base: str, case: dict[str, Any]) -> str:
    if case["operation"] == "rawPrefix":
        return case["value"] + base
    value = json.loads(base, object_pairs_hook=strict_pairs)
    parent, token = pointer_parent(value, case["pointer"])
    if case["operation"] == "remove":
        if isinstance(parent, list):
            del parent[int(token)]
        else:
            del parent[token]
    elif isinstance(parent, list):
        parent[int(token)] = copy.deepcopy(case["value"])
    else:
        parent[token] = copy.deepcopy(case["value"])
    return canonical(value)


def apply_resource_mutation(value: Any, mutation: dict[str, Any]) -> None:
    parent, token = pointer_parent(value, mutation["pointer"])
    require(isinstance(parent, dict), "resource mutation parent")
    if mutation["operation"] == "replace":
        parent[token] = copy.deepcopy(mutation["value"])
    elif mutation["operation"] == "stringUtf8Bytes":
        prefix = mutation["prefix"]
        length = mutation["utf8Bytes"]
        require(isinstance(prefix, str) and prefix.isascii() and len(prefix) <= length,
                "string mutation")
        parent[token] = prefix + "x" * (length - len(prefix))
    else:
        raise Rejected("resource mutation operation")


def aggregate_resource_document(base: str, case: dict[str, Any]) -> str:
    document = json.loads(base, object_pairs_hook=strict_pairs)
    kind = case["kind"]
    count = case["count"]
    unique_contents = case["uniqueContents"]
    byte_length = case["byteLength"]
    url_bytes = case.get("fetchUrlUtf8Bytes", 11)
    descriptor_drift = case.get("descriptorDriftAtIndex")
    old_children = copy.deepcopy(document["canvas"]["children"])
    old_resources = copy.deepcopy(document["resources"])
    resources: list[dict[str, Any]] = []
    children: list[dict[str, Any]] = []

    def generated_resource(base_resource: dict[str, Any], index: int,
                           image_width: int = 1, image_height: int = 1) -> dict[str, Any]:
        resource = copy.deepcopy(base_resource)
        resource_id_value = f"rwres_{index:064x}"
        content = index % unique_contents
        resource["resourceId"] = resource_id_value
        resource["sha256"] = f"sha256:{content:064x}"
        resource["byteLength"] = byte_length
        require(url_bytes >= len("https://a/"), "generated URL size")
        resource["fetchUrl"] = "https://a/" + "x" * (url_bytes - len("https://a/"))
        if kind == "image":
            descriptor_value = resource["technicalDescriptor"]
            descriptor_value["encodedWidthPx"] = image_width
            descriptor_value["logicalWidthPx"] = image_width
            descriptor_value["encodedHeightPx"] = image_height
            descriptor_value["logicalHeightPx"] = image_height
        elif descriptor_drift == index:
            resource["technicalDescriptor"]["unitsPerEm"] = 1001
        return resource

    if kind == "font":
        text = copy.deepcopy(old_children[4])
        text["occurrenceId"] = "rwocc_0000000000000001"
        base_run = copy.deepcopy(text["runs"][0])
        runs = []
        for index in range(count):
            resource_id_value = f"rwres_{index:064x}"
            run = copy.deepcopy(base_run)
            run["fontResourceId"] = resource_id_value
            runs.append(run)
            resources.append(generated_resource(old_resources[0], index))
        text["runs"] = runs
        children.append(text)
    else:
        width = case.get("imageWidthPx", 1)
        height = case.get("imageHeightPx", 1)
        for index in range(count):
            resource_id_value = f"rwres_{index:064x}"
            image = copy.deepcopy(old_children[5])
            image["occurrenceId"] = f"rwocc_{index + 1:016x}"
            image["imageResourceId"] = resource_id_value
            children.append(image)
            resources.append(generated_resource(old_resources[1], index, width, height))
    document["canvas"]["children"] = children
    document["resources"] = resources
    return canonical(document)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--all-kinds", type=Path, required=True)
    parser.add_argument("--protocol-vectors", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    catalog_raw, catalog = load_json_bytes(args.catalog)
    vectors_raw, vectors = load_json_bytes(args.vectors)
    _, protocol = load_json_bytes(args.protocol_vectors)
    all_kinds = args.all_kinds.read_text(encoding="utf-8").rstrip("\r\n")
    authority = vectors["authorityContext"]
    require(vectors["vectorVersion"] == "renderweave-render-document-vectors/2", "vector identity")
    require(authority["catalogVersion"] == catalog["catalogVersion"], "catalog version")
    require(authority["catalogSha256"] == sha256_prefixed(catalog_raw), "catalog digest")
    require(len(catalog["kinds"]) == 16, "kind inventory")
    require(authority["profileAvailability"] == "NOT_REGISTERED", "profile state")
    require(authority["certificationStatus"] == "NOT_CERTIFIED", "certification state")
    require(authority["rasterImplementation"] == "ABSENT", "raster state")
    require(authority["resourceAdmission"] == "TYPED_MANIFEST_STATIC_PREFLIGHT_ONLY",
            "resource admission boundary")
    require(authority["resourceLimits"] == RESOURCE_LIMITS, "resource limits")

    minimal = next(case for case in protocol["cases"] if case["id"] == "png-command")[
        "documentCanonicalJson"]
    bases = {
        "minimal-default-explicit": minimal,
        "all-static-kinds-default-explicit": all_kinds,
    }
    passed = 0
    for case in vectors["positiveCases"]:
        document = bases[case["id"]]
        occurrences, resources_count, static_kinds = admit(document, catalog)
        require(occurrences == case["occurrenceCount"], case["id"] + " occurrences")
        require(resources_count == case["resourceCount"], case["id"] + " resources")
        require(static_kinds == set(case["staticKinds"]), case["id"] + " kinds")
        require(render_digest(document) == case["renderDocumentDigest"], case["id"] + " digest")
        if "canonicalSha256" in case:
            require(sha256_prefixed(document.encode()) == case["canonicalSha256"], "canonical hash")
            require(len(document.encode()) == case["canonicalBytes"], "canonical bytes")
        passed += 1
    for case in vectors["negativeCases"]:
        invalid = mutate(bases[case["baseCase"]], case)
        try:
            admit(invalid, catalog)
        except (Rejected, KeyError, TypeError, ValueError, json.JSONDecodeError):
            passed += 1
        else:
            raise Rejected("negative case admitted: " + case["id"])

    for case in vectors["resourceCases"]:
        value = json.loads(all_kinds, object_pairs_hook=strict_pairs)
        for mutation in case["mutations"]:
            apply_resource_mutation(value, mutation)
        document = canonical(value)
        try:
            admit(document, catalog)
            outcome = "ADMITTED"
        except (Rejected, KeyError, TypeError, ValueError, json.JSONDecodeError):
            outcome = "REJECTED"
        require(outcome == case["expected"], "resource case outcome: " + case["id"])
        passed += 1

    for case in vectors["resourceAggregateCases"]:
        document = aggregate_resource_document(all_kinds, case)
        try:
            admit(document, catalog)
            outcome = "ADMITTED"
        except (Rejected, KeyError, TypeError, ValueError, json.JSONDecodeError):
            outcome = "REJECTED"
        require(outcome == case["expected"], "resource aggregate outcome: " + case["id"])
        passed += 1

    total = (len(vectors["positiveCases"]) + len(vectors["negativeCases"])
             + len(vectors["resourceCases"]) + len(vectors["resourceAggregateCases"]))

    report = {
        "verifier": "renderweave-render-document-python-independent/2",
        "result": "PASS",
        "assurance": "A2",
        "passed": passed,
        "total": total,
        "documentCases": len(vectors["positiveCases"]) + len(vectors["negativeCases"]),
        "resourceCases": len(vectors["resourceCases"]),
        "resourceAggregateCases": len(vectors["resourceAggregateCases"]),
        "checks": total + len(RESOURCE_LIMITS) + 7,
        "catalogSha256": sha256_prefixed(catalog_raw),
        "vectorsSha256": sha256_prefixed(vectors_raw),
        "allKindsCanonicalSha256": sha256_prefixed(all_kinds.encode()),
        "resourceAdmission": authority["resourceAdmission"],
        "resourceBytes": "UNFETCHED",
        "profileAvailability": authority["profileAvailability"],
        "certificationStatus": authority["certificationStatus"],
        "rasterImplementation": authority["rasterImplementation"],
        "providerAttempts": 0,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"RenderDocument independent replay: PASS ({passed}/{report['total']})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # noqa: BLE001 - closed verifier boundary
        print(f"RenderDocument independent replay: FAIL ({error})", file=sys.stderr)
        raise SystemExit(1)
