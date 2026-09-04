#!/usr/bin/env python3
"""Independent Provider-zero verifier for the fresh IMAGE_ONLY v49 diagnostic authority."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v49-diagnostic-preparation/1.0"
DIAGNOSTIC_VERSION = "renderweave-image-only-profile-successor-diagnostic/1.0"
NORMALIZATION_VERSION = "renderweave-image-only-fresh-normalization/1.0"
EVALUATOR_VERSION = (
    "renderweave-image-only-profile-successor-diagnostic-evaluator/1.0"
)
PROFILE_ID = "dashscope-qwen38-max-product-v49-hybrid-generic"
PROFILE_SHA = "acffdd4dd56ca2f1f7260fc5d37aa48ca3da488a0ae2718f2095bf1530e86eaf"
CYCLE_ID = "432fdfeb-c5ab-4cff-92f4-e066a0d98c8c"
AUTHORIZATION_ID = "20260818-iopa-v49-diagnostic-432fdfeb"
CREATED_AT = "2026-08-18T03:50:00Z"
CASE_ID = "v46-failed-route-82"
ARTIFACT_SHA = "51942b84ac65efcb28d02fff359222f60b8550fe5b6d5e87389582fc5a48cfc8"
NORMALIZATION_IDENTITY = (
    NORMALIZATION_VERSION
    + ":3096deba42aeab03be175074e6717ccf6898d4a628950d19eaa6891674d62375"
)
EVALUATOR_IDENTITY = (
    EVALUATOR_VERSION
    + ":b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e"
)
MANIFEST_IDENTITY = (
    DIAGNOSTIC_VERSION
    + ":8ff24a6161223f9e1c8bfb586ffd89421a1ee0ad393622e72870848509f0c8e2"
)
FORBIDDEN_MARKERS = (
    "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
    "modeloutput", "candidatejson", "rootdocument", "chain-of-thought", "api key",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def load_successor_verifier() -> Any:
    path = Path(__file__).with_name("verify_image_only_v49_successor.py")
    spec = importlib.util.spec_from_file_location("v49_successor", path)
    if spec is None or spec.loader is None:
        fail("V49_DIAGNOSTIC_SUCCESSOR_VERIFIER_MISSING")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as error:
        raise SystemExit("V49_DIAGNOSTIC_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V49_DIAGNOSTIC_JSON_INVALID")
    return value


def material_sha(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def png_dimensions(value: bytes) -> tuple[int, int]:
    if len(value) < 24 or value[:8] != b"\x89PNG\r\n\x1a\n" or value[12:16] != b"IHDR":
        fail("V49_DIAGNOSTIC_INPUT_NOT_STATIC_PNG")
    return struct.unpack(">II", value[16:24])


def require_exact_input(input_directory: Path) -> dict[str, Any]:
    if not input_directory.is_dir() or input_directory.is_symlink():
        fail("V49_DIAGNOSTIC_INPUT_DIRECTORY_INVALID")
    matches: list[bytes] = []
    for path in input_directory.iterdir():
        if not path.is_file() or path.is_symlink():
            continue
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() == ARTIFACT_SHA:
            matches.append(raw)
    if len(matches) != 1:
        fail("V49_DIAGNOSTIC_INPUT_SET_MISMATCH")
    raw = matches[0]
    width, height = png_dimensions(raw)
    if len(raw) != 337855 or (width, height) != (3496, 780):
        fail("V49_DIAGNOSTIC_INPUT_METADATA_DRIFT")
    return {
        "artifactSha256": ARTIFACT_SHA,
        "mediaType": "image/png",
        "encodedBytes": len(raw),
        "width": width,
        "height": height,
    }


def require_no_open_authorization(repository: Path) -> int:
    count = 0
    for path in (repository / "plans/live-canary-authorizations").glob("20*.json"):
        if load_json(path).get("status") == "OPEN":
            count += 1
    if count:
        fail("V49_DIAGNOSTIC_OPEN_AUTHORIZATION_FORBIDDEN")
    return count


def require_preparation(
    repository: Path,
    metadata: dict[str, Any],
    require_prelive_outputs_absent: bool = True,
) -> dict[str, str]:
    path = (
        repository / "plans/image-only-profile-successor-diagnostics" / f"{CYCLE_ID}.json"
    )
    raw = path.read_text(encoding="utf-8")
    if any(marker in raw.lower() for marker in FORBIDDEN_MARKERS):
        fail("V49_DIAGNOSTIC_PREPARATION_NOT_PAYLOAD_FREE")
    value = load_json(path)
    expected_top = {
        "version": "renderweave-image-only-profile-successor-diagnostic-preparation/1.0",
        "cycleId": CYCLE_ID,
        "createdAt": CREATED_AT,
        "stage": "PROFILE_SUCCESSOR_DIAGNOSTIC_1",
        "scoring": False,
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "pipelineVersion": "renderweave-inference-pipeline/4.31",
        "elementPromptVersion": "renderweave-visual-elements-prompt/15.0",
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "manifestIdentity": MANIFEST_IDENTITY,
        "evaluatorIdentity": EVALUATOR_IDENTITY,
        "inputProvenance": "USER_PROVIDED",
        "dataClassification": "ORDINARY_DESIGN",
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "certificationCredit": 0,
        "nextStageUnlocked": False,
        "productionDeploymentAllowed": False,
    }
    if any(value.get(key) != expected for key, expected in expected_top.items()):
        fail("V49_DIAGNOSTIC_PREPARATION_FACT_DRIFT")
    cases = [{"caseId": CASE_ID, "artifactSha256": ARTIFACT_SHA}]
    if value.get("cases") != cases:
        fail("V49_DIAGNOSTIC_CASE_DRIFT")
    normalization = value.get("normalization")
    if normalization != {
        "normalizer": "static-png-identity-verification/1.0",
        "normalizedAt": CREATED_AT,
        "mediaType": metadata["mediaType"],
        "encodedBytes": metadata["encodedBytes"],
        "width": metadata["width"],
        "height": metadata["height"],
    }:
        fail("V49_DIAGNOSTIC_NORMALIZATION_METADATA_DRIFT")
    expected_normalization = NORMALIZATION_VERSION + ":" + material_sha([
        NORMALIZATION_VERSION,
        "normalizer=static-png-identity-verification/1.0",
        ARTIFACT_SHA,
        "image/png",
        str(metadata["encodedBytes"]),
        str(metadata["width"]),
        str(metadata["height"]),
        CREATED_AT,
    ])
    expected_evaluator = EVALUATOR_VERSION + ":" + material_sha([
        EVALUATOR_VERSION,
        "terminal=REVIEW_REQUIRED",
        "manual-acceptance=required",
        "certification-credit=forbidden",
        "grant=forbidden",
    ])
    expected_manifest = DIAGNOSTIC_VERSION + ":" + material_sha([
        DIAGNOSTIC_VERSION, CYCLE_ID, PROFILE_ID, PROFILE_SHA, expected_normalization,
        CASE_ID, ARTIFACT_SHA, "USER_PROVIDED", "ORDINARY_DESIGN",
        expected_evaluator, CREATED_AT,
    ])
    if (expected_normalization != NORMALIZATION_IDENTITY
            or expected_evaluator != EVALUATOR_IDENTITY
            or expected_manifest != MANIFEST_IDENTITY):
        fail("V49_DIAGNOSTIC_IDENTITY_REPLAY_DRIFT")
    expected_authorization = {
        "authorizationId": AUTHORIZATION_ID,
        "status": "PENDING_J1",
        "approvalScope": "IMAGE_ONLY_PROFILE_SUCCESSOR_DIAGNOSTIC_1",
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "provider": "DASHSCOPE",
        "model": "qwen3.8-max",
        "providerBaseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "maximumRuns": 1,
        "maximumProviderCalls": 5,
        "maximumModelTokens": 100000,
        "maximumCostMicrosCny": 3000000,
        "maximumProviderCallsPerRun": 5,
        "maximumCostPerRunMicrosCny": 3000000,
        "maximumWindowSeconds": 7200,
    }
    if value.get("requiredAuthorization") != expected_authorization:
        fail("V49_DIAGNOSTIC_J1_CAPS_DRIFT")
    if value.get("externalProviderUsage") != {
        "attempts": 0, "reservations": 0, "modelTokens": 0,
        "costMicrosCny": 0, "apiKeyReads": 0,
    }:
        fail("V49_DIAGNOSTIC_PROVIDER_ZERO_DRIFT")
    authorization_path = (
        repository / "plans/live-canary-authorizations"
        / "20260818-image-only-v49-diagnostic-432fdfeb.json"
    )
    terminal_path = path.with_name(f"{CYCLE_ID}-terminal.json")
    if require_prelive_outputs_absent and (authorization_path.exists() or terminal_path.exists()):
        fail("V49_DIAGNOSTIC_PRELIVE_OUTPUT_ALREADY_EXISTS")
    return {
        "normalizationIdentity": expected_normalization,
        "manifestIdentity": expected_manifest,
        "evaluatorIdentity": expected_evaluator,
    }


def require_authority_contract(repository: Path) -> str:
    paths = (
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/certification/ImageOnlyCertificationAuthorization.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/certification/ImageOnlyCertificationPreflight.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/certification/ProfileSuccessorDiagnosticManifest.java",
        "plans/live-canary-authorizations/image-only-profile-certification-authorization.schema.json",
        "tools/run-image-only-v49-profile-successor-diagnostic-live.ps1",
        "renderweave-app/src/test/java/cn/hbads/renderweave/inference/ImageOnlyV49ProfileSuccessorDiagnosticLiveTest.java",
    )
    sources = []
    for relative in paths:
        source = (repository / relative).read_text(encoding="utf-8")
        sources.append((relative, source))
    combined = "\n".join(source for _, source in sources)
    required = (
        "String normalizationIdentity",
        "PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH",
        "IMAGE_ONLY_V49_PROFILE_ID",
        AUTHORIZATION_ID,
        CYCLE_ID,
        NORMALIZATION_IDENTITY,
        "RENDERWEAVE_RUN_V49_PROFILE_SUCCESSOR_DIAGNOSTIC",
        "PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED",
        "automatic rerun",
    )
    if any(fragment not in combined for fragment in required):
        fail("V49_DIAGNOSTIC_AUTHORITY_CONTRACT_MISSING")
    digest = hashlib.sha256()
    for relative, source in sources:
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(source.encode("utf-8"))
        digest.update(b"\0")
    return "renderweave-image-only-v49-diagnostic-authority/1.0:" + digest.hexdigest()


def verify(repository: Path, input_directory: Path) -> dict[str, Any]:
    successor = load_successor_verifier()
    successor_report = successor.verify(repository)
    metadata = require_exact_input(input_directory)
    identities = require_preparation(repository, metadata)
    authority_identity = require_authority_contract(repository)
    open_count = require_no_open_authorization(repository)
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "PROFILE_SUCCESSOR_DIAGNOSTIC_1_PREPARATION",
        "scoring": False,
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "cycleId": CYCLE_ID,
        "authorizationId": AUTHORIZATION_ID,
        "authorizationStatus": "PENDING_J1",
        **identities,
        "case": metadata,
        "maximumRuns": 1,
        "maximumProviderCalls": 5,
        "maximumModelTokens": 100000,
        "maximumCostMicrosCny": 3000000,
        "maximumProviderCallsPerRun": 5,
        "maximumCostPerRunMicrosCny": 3000000,
        "maximumWindowSeconds": 7200,
        "authorityImplementationIdentity": authority_identity,
        "successorImplementationIdentity": successor_report["registryImplementationIdentity"],
        "openAuthorizationCount": open_count,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "certificationCredit": 0,
        "nextStageUnlocked": False,
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "productionDeploymentAllowed": False,
        "payloadFree": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--input-directory", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = verify(args.repository.resolve(), args.input_directory.resolve())
    encoded = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if any(marker in encoded.lower() for marker in FORBIDDEN_MARKERS):
        fail("V49_DIAGNOSTIC_SUMMARY_NOT_PAYLOAD_FREE")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
