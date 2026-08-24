#!/usr/bin/env python3
"""Independent, standard-library-only replay of the T22 renderer process authority."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import struct
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


PROCESS_VERSION = "renderweave-renderer-process/1.0"
CAPABILITIES = [
    "render-command-v1",
    "render-cancel-v1",
    "render-document-v1",
    "render-result-v1",
    "render-problem-v1",
]
FRAME_TYPES = {
    "CLIENT_HELLO": 0x01,
    "SERVER_HELLO": 0x02,
    "COMMAND": 0x10,
    "CANCEL": 0x11,
    "RESULT_METADATA": 0x20,
    "RESULT_IMAGE": 0x21,
    "PROBLEM": 0x30,
}
CASE_TYPES = {
    "client-hello": 0x01,
    "server-hello": 0x02,
    "png-command": 0x10,
    "cancel": 0x11,
    "problem": 0x30,
    "png-result-metadata": 0x20,
    "png-result-image": 0x21,
}
UUID_V4 = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
SHA256_PREFIXED = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA256_RAW = re.compile(r"^[0-9a-f]{64}$")
DEADLINE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$")


class VerificationFailure(RuntimeError):
    pass


class Verifier:
    def __init__(self) -> None:
        self.checks = 0

    def require(self, condition: bool, message: str) -> None:
        self.checks += 1
        if not condition:
            raise VerificationFailure(message)


def reject_duplicate_members(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationFailure(f"duplicate JSON member: {key}")
        result[key] = value
    return result


def reject_non_finite(value: str) -> Any:
    raise VerificationFailure(f"non-finite JSON number: {value}")


def strict_json_bytes(raw: bytes, label: str) -> Any:
    try:
        return json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=reject_duplicate_members,
            parse_constant=reject_non_finite,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"{label} is not strict UTF-8 JSON: {error}") from error


def canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
    ).encode("utf-8")


def require_no_null(value: Any, label: str) -> None:
    if value is None:
        raise VerificationFailure(f"{label} contains null")
    if isinstance(value, dict):
        for key, member in value.items():
            require_no_null(member, f"{label}.{key}")
    elif isinstance(value, list):
        for index, member in enumerate(value):
            require_no_null(member, f"{label}[{index}]")


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def prefixed_sha256(raw: bytes) -> str:
    return "sha256:" + sha256(raw)


def domain_digest(domain: bytes, raw: bytes) -> str:
    return prefixed_sha256(domain + raw)


def decode_base64(value: str, label: str) -> bytes:
    try:
        return base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as error:
        raise VerificationFailure(f"{label} is not canonical base64") from error


def uuid_network_bytes(value: str) -> bytes:
    compact = value.replace("-", "")
    return bytes.fromhex(compact)


def load_json(path: Path, label: str) -> tuple[bytes, Any]:
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise VerificationFailure(f"cannot read {label}: {error}") from error
    value = strict_json_bytes(raw, label)
    require_no_null(value, label)
    return raw, value


def verify_manifest(
    verifier: Verifier,
    manifest_path: Path,
    cargo_lock_path: Path,
    vendor_path: Path,
) -> tuple[dict[str, Any], str, str, int]:
    manifest_raw, manifest = load_json(manifest_path, "process manifest")
    verifier.require(isinstance(manifest, dict), "process manifest must be an object")
    expected_members = {
        "manifestVersion",
        "processContractVersion",
        "frameTypes",
        "protocolCapabilities",
        "rustToolchain",
        "rustEdition",
        "cargoLockSha256",
        "vendorTreeSha256",
        "vendorFileCount",
        "directDependencies",
        "supportedProductionTargets",
        "rendererProfiles",
        "profileAvailability",
        "certificationStatus",
        "rasterImplementation",
        "physicalCertificationRecords",
    }
    verifier.require(set(manifest) == expected_members, "process manifest member set drifted")
    verifier.require(
        manifest["manifestVersion"] == "renderweave-renderer-process-manifest/1.0",
        "process manifest version drifted",
    )
    verifier.require(manifest["processContractVersion"] == PROCESS_VERSION, "process version drifted")
    verifier.require(manifest["frameTypes"] == FRAME_TYPES, "frame type table drifted")
    verifier.require(manifest["protocolCapabilities"] == CAPABILITIES, "capabilities drifted")
    verifier.require(manifest["rustToolchain"] == "1.89.0", "Rust toolchain drifted")
    verifier.require(manifest["rustEdition"] == "2024", "Rust edition drifted")
    verifier.require(
        manifest["directDependencies"]
        == [
            "hex@0.4.3",
            "image-webp@0.2.4",
            "jpeg-decoder@0.3.2+platform_independent",
            "png@0.18.1",
            "serde@1.0.228",
            "serde_json@1.0.149",
            "sha2@0.10.9",
            "ureq@3.4.0",
        ],
        "direct dependency pins drifted",
    )
    verifier.require(
        manifest["supportedProductionTargets"]
        == ["x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu"],
        "production target list drifted",
    )
    verifier.require(manifest["rendererProfiles"] == [], "T22 must not register a renderer profile")
    verifier.require(manifest["profileAvailability"] == "NOT_REGISTERED", "profile status drifted")
    verifier.require(manifest["certificationStatus"] == "NOT_CERTIFIED", "certification drifted")
    verifier.require(manifest["rasterImplementation"] == "ABSENT", "raster boundary drifted")
    verifier.require(
        manifest["physicalCertificationRecords"] == [],
        "T22 must not claim physical certification",
    )

    cargo_lock_raw = cargo_lock_path.read_bytes()
    cargo_lock_digest = prefixed_sha256(cargo_lock_raw)
    verifier.require(SHA256_PREFIXED.fullmatch(cargo_lock_digest) is not None, "invalid lock digest")
    verifier.require(manifest["cargoLockSha256"] == cargo_lock_digest, "Cargo.lock digest drifted")

    if not vendor_path.is_dir():
        raise VerificationFailure("vendored Cargo source directory is absent")
    files = sorted(
        (path for path in vendor_path.rglob("*") if path.is_file()),
        key=lambda path: path.relative_to(vendor_path).as_posix(),
    )
    tree_hasher = hashlib.sha256()
    for path in files:
        relative = path.relative_to(vendor_path).as_posix()
        tree_hasher.update(f"{sha256(path.read_bytes())}  {relative}\n".encode("utf-8"))
    vendor_digest = "sha256:" + tree_hasher.hexdigest()
    verifier.require(manifest["vendorFileCount"] == len(files), "vendor file count drifted")
    verifier.require(manifest["vendorTreeSha256"] == vendor_digest, "vendor tree digest drifted")

    manifest_digest = prefixed_sha256(manifest_raw)
    verifier.require(SHA256_PREFIXED.fullmatch(manifest_digest) is not None, "invalid manifest digest")
    return manifest, manifest_digest, vendor_digest, len(files)


def verify_frame(verifier: Verifier, case: dict[str, Any]) -> bytes:
    case_id = case["id"]
    verifier.require(case["frameType"] == CASE_TYPES[case_id], f"{case_id}: frame type drifted")
    if case["kind"] == "jsonFrame" or case["kind"] == "commandFrame":
        payload = case["canonicalJson"].encode("utf-8")
        parsed = strict_json_bytes(payload, case_id)
        require_no_null(parsed, case_id)
        verifier.require(canonical_json(parsed) == payload, f"{case_id}: JSON is not canonical")
    elif case["kind"] == "binaryFrame":
        payload = decode_base64(case["payloadBase64"], f"{case_id}.payloadBase64")
    else:
        raise VerificationFailure(f"{case_id}: unknown case kind")
    expected = decode_base64(case["expectedFrameBase64"], f"{case_id}.expectedFrameBase64")
    actual = struct.pack(">I", 1 + len(payload)) + bytes([case["frameType"]]) + payload
    verifier.require(actual == expected, f"{case_id}: exact frame bytes drifted")
    verifier.require(len(expected) >= 5, f"{case_id}: frame is truncated")
    framed_length = struct.unpack(">I", expected[:4])[0]
    verifier.require(framed_length == len(expected) - 4, f"{case_id}: length prefix drifted")
    verifier.require(expected[4] == case["frameType"], f"{case_id}: encoded type drifted")
    verifier.require(expected[5:] == payload, f"{case_id}: encoded payload drifted")
    return payload


def verify_vectors(
    verifier: Verifier,
    vectors_path: Path,
    manifest: dict[str, Any],
    manifest_digest: str,
) -> tuple[str, int]:
    vectors_raw, vectors = load_json(vectors_path, "protocol vectors")
    verifier.require(isinstance(vectors, dict), "protocol vectors must be an object")
    verifier.require(set(vectors) == {"vectorVersion", "authorityContext", "cases"}, "vector members drifted")
    verifier.require(
        vectors["vectorVersion"] == "renderweave-renderer-process-vectors/1",
        "vector version drifted",
    )
    authority = vectors["authorityContext"]
    verifier.require(
        set(authority)
        == {
            "processContractVersion",
            "machineManifestSha256",
            "frameLengthIncludesTypeByte",
            "profileAvailability",
            "certificationStatus",
        },
        "vector authority members drifted",
    )
    verifier.require(authority["processContractVersion"] == PROCESS_VERSION, "authority process drifted")
    verifier.require(authority["machineManifestSha256"] == manifest_digest, "manifest authority drifted")
    verifier.require(authority["frameLengthIncludesTypeByte"] is True, "frame length rule drifted")
    verifier.require(authority["profileAvailability"] == "NOT_REGISTERED", "authority profile drifted")
    verifier.require(authority["certificationStatus"] == "NOT_CERTIFIED", "authority certification drifted")

    cases = vectors["cases"]
    verifier.require(isinstance(cases, list), "vector cases must be an array")
    verifier.require(len(cases) == len(CASE_TYPES), "vector case count drifted")
    by_id = {case.get("id"): case for case in cases}
    verifier.require(len(by_id) == len(cases), "vector case IDs are duplicated")
    verifier.require(set(by_id) == set(CASE_TYPES), "vector case inventory drifted")
    payloads = {case_id: verify_frame(verifier, by_id[case_id]) for case_id in CASE_TYPES}

    client = strict_json_bytes(payloads["client-hello"], "client hello")
    verifier.require(
        client
        == {
            "contractVersion": PROCESS_VERSION,
            "manifestSha256": manifest_digest,
            "requiredCapabilities": CAPABILITIES,
        },
        "client hello drifted",
    )
    server = strict_json_bytes(payloads["server-hello"], "server hello")
    verifier.require(
        server
        == {
            "contractVersion": PROCESS_VERSION,
            "manifestSha256": manifest_digest,
            "capabilities": CAPABILITIES,
            "rendererProfiles": [],
            "profileAvailability": "NOT_REGISTERED",
            "certificationStatus": "NOT_CERTIFIED",
        },
        "server hello drifted",
    )

    command_case = by_id["png-command"]
    command_raw = payloads["png-command"]
    command = strict_json_bytes(command_raw, "command")
    verifier.require(UUID_V4.fullmatch(command["requestId"]) is not None, "request ID is not UUID v4")
    verifier.require(DEADLINE.fullmatch(command["deadlineAt"]) is not None, "deadline format drifted")
    try:
        datetime.fromisoformat(command["deadlineAt"].replace("Z", "+00:00"))
    except ValueError as error:
        raise VerificationFailure("deadline is not a valid UTC timestamp") from error
    verifier.require(command["contractVersion"] == "renderweave-render-command/1.0", "command version drifted")
    document_raw = canonical_json(command["document"])
    verifier.require(
        document_raw == command_case["documentCanonicalJson"].encode("utf-8"),
        "embedded RenderDocument bytes drifted",
    )
    document_digest = domain_digest(b"renderweave-render-document/1\0", document_raw)
    verifier.require(SHA256_PREFIXED.fullmatch(document_digest) is not None, "invalid document digest")
    verifier.require(command["renderDocumentDigest"] == document_digest, "command document digest drifted")
    verifier.require(command_case["renderDocumentDigest"] == document_digest, "vector document digest drifted")
    command_digest = domain_digest(b"renderweave-render-command/1\0", command_raw)
    verifier.require(command_case["rendererCommandDigest"] == command_digest, "command digest drifted")
    verifier.require(command_case["requestId"] == command["requestId"], "command request ID field drifted")
    verifier.require(command_case["deadlineAt"] == command["deadlineAt"], "command deadline field drifted")
    verifier.require(
        command["output"] == {"profile": "renderweave-output-png/1.0", "dpi": 96},
        "PNG output selection drifted",
    )
    verifier.require(command["diagnostics"] == {"layoutTrace": False}, "diagnostics drifted")

    cancel = strict_json_bytes(payloads["cancel"], "cancel")
    verifier.require(cancel["requestId"] == command["requestId"], "cancel request ID drifted")
    verifier.require(cancel["rendererCommandDigest"] == command_digest, "cancel digest drifted")
    verifier.require(cancel["deadlineAt"] == command["deadlineAt"], "cancel deadline drifted")

    problem = strict_json_bytes(payloads["problem"], "problem")
    verifier.require(
        problem
        == {
            "contractVersion": "renderweave-render-problem/1.0",
            "requestId": command["requestId"],
            "code": "RENDER_INTERNAL_ERROR",
            "engineStage": "COMMAND_ADMISSION",
            "parameters": {},
        },
        "T22 stable terminal problem drifted",
    )

    metadata_case = by_id["png-result-metadata"]
    metadata = strict_json_bytes(payloads["png-result-metadata"], "result metadata")
    image = decode_base64(metadata_case["imageBase64"], "result image")
    image_digest = sha256(image)
    verifier.require(SHA256_RAW.fullmatch(image_digest) is not None, "invalid image digest")
    verifier.require(metadata["requestId"] == command["requestId"], "result request ID drifted")
    verifier.require(metadata["byteLength"] == len(image), "result byte length drifted")
    verifier.require(metadata["contentSha256"] == image_digest, "result content digest drifted")
    verifier.require(metadata_case["contentSha256"] == image_digest, "vector image digest drifted")
    verifier.require(
        metadata["format"] == "PNG" and metadata["mediaType"] == "image/png",
        "result media identity drifted",
    )
    verifier.require(metadata["widthPx"] == 1 and metadata["heightPx"] == 1, "result dimensions drifted")
    verifier.require(metadata["dpi"] == 96, "result dpi drifted")

    image_payload = payloads["png-result-image"]
    verifier.require(image_payload[:16] == uuid_network_bytes(command["requestId"]), "binary request ID drifted")
    verifier.require(image_payload[16:] == image, "binary image payload drifted")
    verifier.require(
        manifest["rendererProfiles"] == server["rendererProfiles"],
        "manifest/server profile registry drifted",
    )
    return sha256(vectors_raw), len(cases)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--cargo-lock", required=True, type=Path)
    parser.add_argument("--vendor", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    verifier = Verifier()
    try:
        manifest, manifest_digest, vendor_digest, vendor_files = verify_manifest(
            verifier,
            arguments.manifest,
            arguments.cargo_lock,
            arguments.vendor,
        )
        vector_digest, vector_cases = verify_vectors(
            verifier,
            arguments.vectors,
            manifest,
            manifest_digest,
        )
        report = {
            "reportVersion": "renderweave-renderer-process-independent/1.0",
            "engine": "python-stdlib-independent",
            "assurance": "A2",
            "status": "PASS",
            "checks": verifier.checks,
            "failed": 0,
            "vectorCases": vector_cases,
            "vectorSha256": "sha256:" + vector_digest,
            "manifestSha256": manifest_digest,
            "cargoLockSha256": manifest["cargoLockSha256"],
            "vendorTreeSha256": vendor_digest,
            "vendorFileCount": vendor_files,
            "profileAvailability": "NOT_REGISTERED",
            "certificationStatus": "NOT_CERTIFIED",
            "rasterImplementation": "ABSENT",
            "providerAttempts": 0,
        }
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        print(
            f"Renderer process independent replay: {verifier.checks} checks, "
            f"{vector_cases} vectors, vendor={vendor_files}, Profile=NOT_REGISTERED"
        )
        return 0
    except (OSError, KeyError, TypeError, VerificationFailure, ValueError) as error:
        print(f"renderer process independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
