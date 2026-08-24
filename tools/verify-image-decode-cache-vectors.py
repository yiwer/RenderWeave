#!/usr/bin/env python3
"""Independent stdlib replay for Renderer IMAGE decode vectors and decoded-cache state."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import re
import struct
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


PROFILE = "renderweave-image-decode-cache-v1"
RENDERER_PROFILE = "renderweave-renderer/1.0"
REQUEST_DECODED_CACHE_BYTES = 536_870_912
REQUEST_DECODED_CACHE_LIMIT_ID = "assetsAndFetch.requestDecodedCacheBytes"
DECODER_SCRATCH_BYTES = 134_217_728
DECODER_SCRATCH_LIMIT_ID = "rendererSurfaceAndOutput.decoderScratchBytes"
EXPECTED_DEPENDENCIES = {
    "png": "0.18.1",
    "jpegDecoder": "0.3.2+platform_independent",
    "imageWebp": "0.2.4",
}
EXPECTED_SCOPE = {
    "codecPixels": "A1_RUST_PRIMARY_WITH_FROZEN_EXPECTED_BYTES",
    "vectorIdentityOrientationDigestAndCache": "A2_PYTHON_STDLIB_INDEPENDENT",
}
EXPECTED_BOUNDARY = {
    "resourceBytes": "FULL_IMAGE_DECODE_AUTOMATED_VERIFIED_UNWIRED",
    "imageDecode": "STATIC_PNG_JPEG_WEBP_STRAIGHT_RGBA8_ORIENTED",
    "fontFullParse": "DEFERRED",
    "decodedCache": "REQUEST_LOCAL_CONTENT_ADDRESSED_536870912_BYTES",
    "daemonOutputPath": "UNWIRED",
    "profileAvailability": "NOT_REGISTERED",
    "certificationStatus": "NOT_CERTIFIED",
    "processRasterImplementation": "ABSENT",
    "productRoute": "CLOSED",
    "providerAttempts": 0,
}
EXPECTED_DECODE_IDS = {
    "png-rgba",
    "png-gray",
    "png-indexed",
    "png-srgb",
    "png-exif",
    "png-canonical-icc",
    "jpeg-gray",
    "jpeg-progressive",
    "jpeg-exif",
    "jpeg-canonical-icc",
    "webp-lossy",
    "webp-lossless",
    "webp-exif",
    "webp-canonical-icc",
}
EXPECTED_FAILURE_IDS = {
    "png-corrupt-entropy",
    "png-noncanonical-icc",
    "jpeg-noncanonical-icc",
    "webp-noncanonical-icc",
}
EXPECTED_ORIENTATIONS = {
    "IDENTITY",
    "MIRROR_HORIZONTAL",
    "ROTATE_180",
    "MIRROR_VERTICAL",
    "TRANSPOSE",
    "ROTATE_90_CW",
    "TRANSVERSE",
    "ROTATE_270_CW",
}
EXPECTED_CACHE_IDS = {
    "first-decode",
    "duplicate-hit",
    "lease-expired-before-hit",
    "corruption-evicts",
    "budget-below",
    "budget-at",
    "budget-above",
}
HEX = re.compile(r"^[0-9a-f]+$")


class VerificationFailure(ValueError):
    pass


@dataclass
class Verifier:
    checks: int = 0

    def require(self, condition: bool, message: str) -> None:
        self.checks += 1
        if not condition:
            raise VerificationFailure(message)


def strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, nested in pairs:
        if key in value:
            raise VerificationFailure(f"duplicate JSON member: {key}")
        value[key] = nested
    return value


def load_strict(path: Path) -> tuple[bytes, Any]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw, object_pairs_hook=strict_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"{path} is not strict UTF-8 JSON: {error}") from error
    return raw, value


def reject_null(value: Any, location: str = "$") -> None:
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


def exact_cases(value: Any, count: int, label: str) -> list[dict[str, Any]]:
    if not isinstance(value, list) or len(value) != count:
        raise VerificationFailure(f"{label} case count drifted")
    if not all(isinstance(case, dict) for case in value):
        raise VerificationFailure(f"{label} cases must be objects")
    return value


def repository_path(verifier: Verifier, repo_root: Path, value: Any, label: str) -> Path:
    verifier.require(
        isinstance(value, str)
        and Path(value).as_posix() == value
        and not Path(value).is_absolute()
        and ".." not in Path(value).parts,
        f"{label} is not a canonical repository-relative path",
    )
    resolved_root = repo_root.resolve()
    resolved = (resolved_root / value).resolve()
    verifier.require(resolved.is_relative_to(resolved_root), f"{label} escapes the repository")
    verifier.require(resolved.is_file(), f"{label} is absent")
    return resolved


def asset_bytes(verifier: Verifier, case: dict[str, Any]) -> bytes:
    case_id = case.get("id", "<unknown>")
    input_value = exact_members(case.get("input"), {"kind", "data"}, f"{case_id} Asset input")
    verifier.require(input_value["kind"] == "BASE64", f"{case_id}: Asset input kind drifted")
    encoded = input_value["data"]
    verifier.require(isinstance(encoded, str), f"{case_id}: Asset base64 is not a string")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise VerificationFailure(f"{case_id}: invalid Asset base64") from error
    verifier.require(base64.b64encode(raw).decode("ascii") == encoded, f"{case_id}: Asset base64 is noncanonical")
    expected = case.get("expected")
    if isinstance(expected, dict) and expected.get("outcome") == "ADMITTED":
        verifier.require(expected.get("byteLength") == len(raw), f"{case_id}: Asset byte length drifted")
        verifier.require(
            expected.get("sha256") == hashlib.sha256(raw).hexdigest(),
            f"{case_id}: Asset sha256 drifted",
        )
    return raw


def detect_media(raw: bytes) -> str | None:
    if raw.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if raw.startswith(b"\xff\xd8"):
        return "image/jpeg"
    if len(raw) >= 12 and raw[:4] == b"RIFF" and raw[8:12] == b"WEBP":
        return "image/webp"
    return None


def decode_hex(value: Any, label: str) -> bytes:
    if not isinstance(value, str) or len(value) % 2 != 0 or not HEX.fullmatch(value):
        raise VerificationFailure(f"{label} is not canonical lowercase hex")
    return bytes.fromhex(value)


def png_icc(raw: bytes) -> bytes | None:
    if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
        raise VerificationFailure("PNG ICC source has invalid magic")
    position = 8
    profile: bytes | None = None
    while position < len(raw):
        if position + 12 > len(raw):
            raise VerificationFailure("PNG ICC source is truncated")
        size = struct.unpack_from(">I", raw, position)[0]
        chunk_type = raw[position + 4 : position + 8]
        payload_end = position + 8 + size
        chunk_end = payload_end + 4
        if chunk_end > len(raw):
            raise VerificationFailure("PNG ICC chunk exceeds input")
        payload = raw[position + 8 : payload_end]
        expected_crc = struct.unpack_from(">I", raw, payload_end)[0]
        actual_crc = binascii.crc32(chunk_type + payload) & 0xFFFF_FFFF
        if expected_crc != actual_crc:
            raise VerificationFailure("PNG ICC source CRC drifted")
        if chunk_type == b"iCCP":
            if profile is not None:
                raise VerificationFailure("PNG contains duplicate iCCP")
            separator = payload.find(b"\x00")
            if separator <= 0 or separator + 2 > len(payload) or payload[separator + 1] != 0:
                raise VerificationFailure("PNG iCCP structure drifted")
            try:
                profile = zlib.decompress(payload[separator + 2 :])
            except zlib.error as error:
                raise VerificationFailure("PNG iCCP compression drifted") from error
        position = chunk_end
        if chunk_type == b"IEND":
            break
    if position != len(raw):
        raise VerificationFailure("PNG has trailing bytes")
    return profile


def jpeg_icc(raw: bytes) -> bytes | None:
    if not raw.startswith(b"\xff\xd8"):
        raise VerificationFailure("JPEG ICC source has invalid magic")
    position = 2
    segments: dict[int, bytes] = {}
    segment_count: int | None = None
    while position < len(raw):
        if raw[position] != 0xFF:
            raise VerificationFailure("JPEG marker alignment drifted")
        while position < len(raw) and raw[position] == 0xFF:
            position += 1
        if position >= len(raw):
            raise VerificationFailure("JPEG marker is truncated")
        marker = raw[position]
        position += 1
        if marker in {0xD9, 0xDA}:
            break
        if marker == 0x01 or 0xD0 <= marker <= 0xD7:
            continue
        if position + 2 > len(raw):
            raise VerificationFailure("JPEG segment length is truncated")
        length = struct.unpack_from(">H", raw, position)[0]
        if length < 2 or position + length > len(raw):
            raise VerificationFailure("JPEG segment exceeds input")
        payload = raw[position + 2 : position + length]
        if marker == 0xE2 and payload.startswith(b"ICC_PROFILE\x00"):
            if len(payload) <= 14:
                raise VerificationFailure("JPEG ICC segment is truncated")
            sequence, count = payload[12], payload[13]
            if sequence == 0 or count == 0 or sequence > count or sequence in segments:
                raise VerificationFailure("JPEG ICC sequence drifted")
            if segment_count is not None and segment_count != count:
                raise VerificationFailure("JPEG ICC count drifted")
            segment_count = count
            segments[sequence] = payload[14:]
        position += length
    if not segments:
        return None
    if segment_count is None or set(segments) != set(range(1, segment_count + 1)):
        raise VerificationFailure("JPEG ICC segment set is incomplete")
    return b"".join(segments[index] for index in range(1, segment_count + 1))


def webp_icc(raw: bytes) -> tuple[bytes | None, int | None]:
    if len(raw) < 12 or raw[:4] != b"RIFF" or raw[8:12] != b"WEBP":
        raise VerificationFailure("WebP ICC source has invalid magic")
    if struct.unpack_from("<I", raw, 4)[0] + 8 != len(raw):
        raise VerificationFailure("WebP RIFF length drifted")
    position = 12
    profile: bytes | None = None
    flags: int | None = None
    while position < len(raw):
        if position + 8 > len(raw):
            raise VerificationFailure("WebP chunk header is truncated")
        fourcc = raw[position : position + 4]
        size = struct.unpack_from("<I", raw, position + 4)[0]
        payload_end = position + 8 + size
        chunk_end = payload_end + size % 2
        if chunk_end > len(raw):
            raise VerificationFailure("WebP chunk exceeds input")
        payload = raw[position + 8 : payload_end]
        if fourcc == b"VP8X":
            if flags is not None or len(payload) != 10:
                raise VerificationFailure("WebP VP8X structure drifted")
            flags = payload[0]
        elif fourcc == b"ICCP":
            if profile is not None or not payload:
                raise VerificationFailure("WebP ICCP structure drifted")
            profile = payload
        position = chunk_end
    if position != len(raw):
        raise VerificationFailure("WebP has trailing bytes")
    return profile, flags


def orient_rgba8(source: bytes, width: int, height: int, orientation: str) -> tuple[int, int, bytes]:
    if len(source) != width * height * 4:
        raise VerificationFailure("orientation source length drifted")
    swaps = orientation in {"TRANSPOSE", "ROTATE_90_CW", "TRANSVERSE", "ROTATE_270_CW"}
    target_width, target_height = (height, width) if swaps else (width, height)
    output = bytearray(target_width * target_height * 4)
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
                raise VerificationFailure(f"unknown orientation: {orientation}")
            source_offset = (source_y * width + source_x) * 4
            target_offset = (target_y * target_width + target_x) * 4
            output[target_offset : target_offset + 4] = source[source_offset : source_offset + 4]
    return target_width, target_height, bytes(output)


def replay_cache(case: dict[str, Any]) -> tuple[str, int, int]:
    if "reserveBytes" in case:
        reserve = case["reserveBytes"]
        if not isinstance(reserve, int) or isinstance(reserve, bool) or reserve < 0:
            raise VerificationFailure(f"{case['id']}: reserveBytes is invalid")
        if reserve <= REQUEST_DECODED_CACHE_BYTES:
            return "RESERVED", 0, reserve
        return "RESOURCE_BUDGET_EXCEEDED", 0, 0

    entries = 0
    retained = 0
    lease_active = True
    decoded = False
    corrupted = False
    outcome = "MISS"
    for event in case["events"]:
        if event == "MISS":
            outcome = "MISS"
        elif event == "DECODE":
            decoded = True
            outcome = "DECODED"
        elif event == "INSERT":
            if not decoded or entries != 0:
                raise VerificationFailure(f"{case['id']}: invalid INSERT transition")
            entries = 1
            retained += 4
            outcome = "INSERTED"
        elif event == "LEASE_EXPIRED":
            lease_active = False
        elif event == "CORRUPT":
            if entries != 1:
                raise VerificationFailure(f"{case['id']}: invalid CORRUPT transition")
            corrupted = True
        elif event == "HIT":
            if not lease_active:
                outcome = "RESOURCE_LEASE_EXPIRED"
            elif corrupted:
                entries = 0
                outcome = "RENDER_INTERNAL_ERROR"
            elif entries == 1:
                outcome = "HIT"
            else:
                outcome = "MISS"
        else:
            raise VerificationFailure(f"{case['id']}: unknown cache event {event}")
    return outcome, entries, retained


def verify(vectors_path: Path, repo_root: Path) -> dict[str, Any]:
    raw, vectors = load_strict(vectors_path)
    reject_null(vectors)
    exact_members(
        vectors,
        {
            "profile",
            "rendererProfileIdentity",
            "assetKernelVectorPath",
            "assetKernelVectorSha256",
            "canonicalSrgbIcc",
            "dependencyClosure",
            "limits",
            "decodeCases",
            "failureCases",
            "orientationSource",
            "orientationCases",
            "cacheCases",
            "independentReplayScope",
            "boundary",
        },
        "IMAGE vector document",
    )
    verifier = Verifier()
    verifier.require(vectors["profile"] == PROFILE, "IMAGE profile drifted")
    verifier.require(vectors["rendererProfileIdentity"] == RENDERER_PROFILE, "Renderer profile drifted")
    exact_members(vectors["dependencyClosure"], set(EXPECTED_DEPENDENCIES), "dependency closure")
    for key, expected in EXPECTED_DEPENDENCIES.items():
        verifier.require(vectors["dependencyClosure"][key] == expected, f"{key} dependency drifted")
    exact_members(
        vectors["limits"],
        {
            "requestDecodedCacheBytes",
            "requestDecodedCacheLimitId",
            "decoderScratchBytes",
            "decoderScratchLimitId",
        },
        "IMAGE limits",
    )
    verifier.require(
        vectors["limits"]["requestDecodedCacheBytes"] == REQUEST_DECODED_CACHE_BYTES,
        "decoded cache limit drifted",
    )
    verifier.require(
        vectors["limits"]["requestDecodedCacheLimitId"] == REQUEST_DECODED_CACHE_LIMIT_ID,
        "decoded cache limit id drifted",
    )
    verifier.require(vectors["limits"]["decoderScratchBytes"] == DECODER_SCRATCH_BYTES, "scratch limit drifted")
    verifier.require(
        vectors["limits"]["decoderScratchLimitId"] == DECODER_SCRATCH_LIMIT_ID,
        "scratch limit id drifted",
    )
    exact_members(vectors["independentReplayScope"], set(EXPECTED_SCOPE), "independent replay scope")
    for key, expected in EXPECTED_SCOPE.items():
        verifier.require(vectors["independentReplayScope"][key] == expected, f"{key} assurance drifted")
    exact_members(vectors["boundary"], set(EXPECTED_BOUNDARY), "IMAGE boundary")
    for key, expected in EXPECTED_BOUNDARY.items():
        verifier.require(vectors["boundary"][key] == expected, f"{key} boundary drifted")

    asset_path = repository_path(
        verifier, repo_root, vectors["assetKernelVectorPath"], "Asset vector path"
    )
    asset_raw, asset_vectors = load_strict(asset_path)
    asset_sha = "sha256:" + hashlib.sha256(asset_raw).hexdigest()
    verifier.require(vectors["assetKernelVectorSha256"] == asset_sha, "Asset vector hash drifted")
    exact_members(asset_vectors, {"vectorVersion", "authorityContext", "cases"}, "Asset vector document")
    asset_cases = exact_cases(asset_vectors["cases"], 41, "Asset")
    asset_index: dict[str, dict[str, Any]] = {}
    for asset_case in asset_cases:
        case_id = asset_case.get("id")
        verifier.require(
            isinstance(case_id, str) and case_id not in asset_index,
            "Asset case id is invalid or duplicated",
        )
        asset_index[case_id] = asset_case

    icc_value = exact_members(
        vectors["canonicalSrgbIcc"], {"path", "byteLength", "sha256"}, "canonical ICC"
    )
    icc_path = repository_path(verifier, repo_root, icc_value["path"], "canonical ICC path")
    canonical_icc = icc_path.read_bytes()
    canonical_icc_sha = "sha256:" + hashlib.sha256(canonical_icc).hexdigest()
    verifier.require(icc_value["byteLength"] == len(canonical_icc) == 3144, "canonical ICC length drifted")
    verifier.require(icc_value["sha256"] == canonical_icc_sha, "canonical ICC hash drifted")

    canonical_extractors: dict[str, Callable[[bytes], bytes | None]] = {
        "png-canonical-icc-admitted": png_icc,
        "jpeg-canonical-icc-admitted": jpeg_icc,
        "webp-canonical-icc-admitted": lambda data: webp_icc(data)[0],
    }
    for asset_id, extractor in canonical_extractors.items():
        asset_case = asset_index.get(asset_id)
        verifier.require(asset_case is not None, f"missing canonical ICC Asset case {asset_id}")
        profile = extractor(asset_bytes(verifier, asset_case))
        verifier.require(profile == canonical_icc, f"{asset_id}: canonical ICC equality drifted")
    canonical_webp = asset_bytes(verifier, asset_index["webp-canonical-icc-admitted"])
    _, webp_flags = webp_icc(canonical_webp)
    verifier.require(webp_flags is not None and webp_flags & 0x20 != 0, "canonical WebP ICC flag drifted")

    noncanonical_extractors: dict[str, Callable[[bytes], bytes | None]] = {
        "png-iccp-unsupported": png_icc,
        "jpeg-icc-unsupported": jpeg_icc,
        "webp-iccp-unsupported": lambda data: webp_icc(data)[0],
    }
    for asset_id, extractor in noncanonical_extractors.items():
        asset_case = asset_index.get(asset_id)
        verifier.require(asset_case is not None, f"missing noncanonical ICC Asset case {asset_id}")
        profile = extractor(asset_bytes(verifier, asset_case))
        verifier.require(profile is not None and profile != canonical_icc, f"{asset_id}: noncanonical ICC drifted")

    decode_cases = exact_cases(vectors["decodeCases"], 14, "decode")
    failure_cases = exact_cases(vectors["failureCases"], 4, "failure")
    orientation_cases = exact_cases(vectors["orientationCases"], 8, "orientation")
    cache_cases = exact_cases(vectors["cacheCases"], 7, "cache")
    seen_ids: set[str] = set()
    pixel_corpus = hashlib.sha256()

    for case in decode_cases:
        exact_members(
            case,
            {"id", "assetCaseId", "declaredMediaType", "logicalWidthPx", "logicalHeightPx", "rgba8Hex"},
            "decode case",
        )
        case_id = case["id"]
        verifier.require(isinstance(case_id, str) and case_id not in seen_ids, "decode id is invalid or duplicated")
        seen_ids.add(case_id)
        asset_case = asset_index.get(case["assetCaseId"])
        verifier.require(asset_case is not None, f"{case_id}: missing Asset case")
        data = asset_bytes(verifier, asset_case)
        verifier.require(detect_media(data) == case["declaredMediaType"], f"{case_id}: media type drifted")
        expected = asset_case.get("expected")
        verifier.require(isinstance(expected, dict) and expected.get("outcome") == "ADMITTED", f"{case_id}: Asset case is not admitted")
        descriptor = expected.get("descriptor") if isinstance(expected, dict) else None
        verifier.require(isinstance(descriptor, dict), f"{case_id}: Asset descriptor is absent")
        width = case["logicalWidthPx"]
        height = case["logicalHeightPx"]
        verifier.require(
            isinstance(width, int)
            and not isinstance(width, bool)
            and isinstance(height, int)
            and not isinstance(height, bool)
            and width > 0
            and height > 0,
            f"{case_id}: logical dimensions are invalid",
        )
        verifier.require(
            descriptor.get("logicalWidthPx") == width and descriptor.get("logicalHeightPx") == height,
            f"{case_id}: logical dimensions drifted from Asset descriptor",
        )
        rgba = decode_hex(case["rgba8Hex"], f"{case_id} RGBA8")
        verifier.require(len(rgba) == width * height * 4, f"{case_id}: RGBA8 length drifted")
        pixel_corpus.update(case_id.encode("utf-8") + b"\x00" + rgba)
    verifier.require({case["id"] for case in decode_cases} == EXPECTED_DECODE_IDS, "decode case set drifted")

    for case in failure_cases:
        exact_members(
            case,
            {"id", "assetCaseId", "declaredMediaType", "descriptorCaseId", "expectedCode"},
            "failure case",
        )
        case_id = case["id"]
        verifier.require(isinstance(case_id, str) and case_id not in seen_ids, "failure id is invalid or duplicated")
        seen_ids.add(case_id)
        asset_case = asset_index.get(case["assetCaseId"])
        descriptor_case = asset_index.get(case["descriptorCaseId"])
        verifier.require(asset_case is not None and descriptor_case is not None, f"{case_id}: missing Asset reference")
        data = asset_bytes(verifier, asset_case)
        descriptor_data = asset_bytes(verifier, descriptor_case)
        verifier.require(detect_media(data) == case["declaredMediaType"], f"{case_id}: failure media drifted")
        verifier.require(
            asset_case.get("expected", {}).get("outcome") == "REJECTED"
            and descriptor_case.get("expected", {}).get("outcome") == "ADMITTED"
            and detect_media(descriptor_data) == case["declaredMediaType"],
            f"{case_id}: failure/descriptor contract drifted",
        )
        verifier.require(case["expectedCode"] == "DECODE_FAILED", f"{case_id}: failure code drifted")
    verifier.require({case["id"] for case in failure_cases} == EXPECTED_FAILURE_IDS, "failure case set drifted")

    orientation_source = exact_members(
        vectors["orientationSource"], {"encodedWidthPx", "encodedHeightPx", "straightRgba8Hex"}, "orientation source"
    )
    source_width = orientation_source["encodedWidthPx"]
    source_height = orientation_source["encodedHeightPx"]
    verifier.require(source_width == 3 and source_height == 2, "orientation source dimensions drifted")
    source_rgba = decode_hex(orientation_source["straightRgba8Hex"], "orientation source RGBA8")
    verifier.require(len(source_rgba) == source_width * source_height * 4, "orientation source length drifted")
    for case in orientation_cases:
        exact_members(case, {"orientation", "logicalWidthPx", "logicalHeightPx", "rgba8Hex"}, "orientation case")
        expected_rgba = decode_hex(case["rgba8Hex"], f"{case['orientation']} RGBA8")
        width, height, actual_rgba = orient_rgba8(
            source_rgba, source_width, source_height, case["orientation"]
        )
        verifier.require(
            (width, height) == (case["logicalWidthPx"], case["logicalHeightPx"]),
            f"{case['orientation']}: oriented dimensions drifted",
        )
        verifier.require(actual_rgba == expected_rgba, f"{case['orientation']}: oriented RGBA8 drifted")
    verifier.require(
        {case["orientation"] for case in orientation_cases} == EXPECTED_ORIENTATIONS,
        "orientation case set drifted",
    )

    for case in cache_cases:
        expected_members = {"id", "outcome", "retainedBytes"}
        if "reserveBytes" in case:
            expected_members.add("reserveBytes")
        else:
            expected_members.update({"events", "uniqueContents"})
        exact_members(case, expected_members, "cache case")
        case_id = case["id"]
        verifier.require(isinstance(case_id, str) and case_id not in seen_ids, "cache id is invalid or duplicated")
        seen_ids.add(case_id)
        outcome, unique_contents, retained_bytes = replay_cache(case)
        verifier.require(outcome == case["outcome"], f"{case_id}: cache outcome drifted")
        if "uniqueContents" in case:
            verifier.require(unique_contents == case["uniqueContents"], f"{case_id}: cache cardinality drifted")
        verifier.require(retained_bytes == case["retainedBytes"], f"{case_id}: retained byte count drifted")
    verifier.require({case["id"] for case in cache_cases} == EXPECTED_CACHE_IDS, "cache case set drifted")

    total = len(decode_cases) + len(failure_cases) + len(orientation_cases) + len(cache_cases)
    return {
        "verifier": "renderweave-image-decode-cache-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "codecPixelAssurance": vectors["independentReplayScope"]["codecPixels"],
        "structuralAssurance": vectors["independentReplayScope"]["vectorIdentityOrientationDigestAndCache"],
        "decodeCases": len(decode_cases),
        "failureCases": len(failure_cases),
        "orientationCases": len(orientation_cases),
        "cacheCases": len(cache_cases),
        "total": total,
        "passed": total,
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(raw).hexdigest(),
        "assetKernelVectorSha256": asset_sha,
        "canonicalSrgbIccSha256": canonical_icc_sha,
        "expectedPixelCorpusSha256": "sha256:" + pixel_corpus.hexdigest(),
        "rendererProfileIdentity": vectors["rendererProfileIdentity"],
        "requestDecodedCacheBytes": vectors["limits"]["requestDecodedCacheBytes"],
        "requestDecodedCacheLimitId": vectors["limits"]["requestDecodedCacheLimitId"],
        "decoderScratchBytes": vectors["limits"]["decoderScratchBytes"],
        "decoderScratchLimitId": vectors["limits"]["decoderScratchLimitId"],
        **vectors["boundary"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    repo_root = Path(__file__).resolve().parents[1]
    parser.add_argument(
        "--vectors",
        type=Path,
        default=repo_root / "renderer" / "image-decode-cache-vectors-v1.json",
    )
    parser.add_argument("--repo-root", type=Path, default=repo_root)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.vectors, args.repo_root)
        if args.report is not None:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(
            "IMAGE decode/cache independent replay: "
            f"{report['passed']}/{report['total']} cases, {report['checks']} checks, "
            f"codecPixels={report['codecPixelAssurance']}"
        )
        return 0
    except (OSError, VerificationFailure, TypeError, ValueError) as error:
        print(f"IMAGE decode/cache independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
