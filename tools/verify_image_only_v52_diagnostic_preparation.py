#!/usr/bin/env python3
"""Provider-zero verifier for the exact IMAGE_ONLY v52 successor diagnostic."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v52-diagnostic-preparation/1.0"
PREPARATION_VERSION = "renderweave-image-only-profile-successor-diagnostic-preparation/1.0"
DIAGNOSTIC_VERSION = "renderweave-image-only-profile-successor-diagnostic/1.0"
NORMALIZATION_VERSION = "renderweave-image-only-fresh-normalization/1.0"
EVALUATOR_VERSION = "renderweave-image-only-profile-successor-diagnostic-evaluator/1.0"
CANONICALIZER_ID = "renderweave-image-only-local-id-canonicalizer/1.0"
ENVELOPE_CODE = "VISUAL_GROUNDING_REPEATED_GROUP_ENVELOPE_NORMALIZED"
IMPLEMENTATION_ID = (
    "renderweave-image-only-v52-implementation/1.0:"
    "293fec9792df98131d72acffdc22ed4b4d65e0d8edaea1b46743e9e7da2b7405"
)
PROFILE_ID = "dashscope-qwen38-max-product-v52-hybrid-generic"
PROFILE_SHA = "d8014b605dfa01a5aa1e6062696c61eb896da9e146b2a6ab3c5dae3ca9957332"
CYCLE_ID = "981d7262-d802-45bb-96ce-d34b4468f9f9"
AUTHORIZATION_ID = "20260818-iopa-v52-diagnostic-981d7262"
CREATED_AT = "2026-08-18T07:17:59Z"
CASE_ID = "v46-failed-route-82"
ARTIFACT_SHA = "51942b84ac65efcb28d02fff359222f60b8550fe5b6d5e87389582fc5a48cfc8"
NORMALIZATION_IDENTITY = (
    NORMALIZATION_VERSION
    + ":e0e505c515ff3c7c7bac57e0ddc19e714721e301fd2216830bc6ac82f98cae35"
)
EVALUATOR_IDENTITY = (
    EVALUATOR_VERSION
    + ":b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e"
)
MANIFEST_IDENTITY = (
    DIAGNOSTIC_VERSION
    + ":4a81d0718abb9b8db3e95052a4c268767c9ac01ce9ed90f117894dc1aed63d20"
)
GOAL_TOKEN_CAP = 1_500_000
GOAL_TOKENS_BEFORE = 121_618
FORBIDDEN_MARKERS = (
    "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
    "modeloutput", "candidatejson", "rootdocument", "chain-of-thought", "api key",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def strict_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("V52_DIAGNOSTIC_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=strict_object_pairs
        )
    except SystemExit:
        raise
    except Exception as error:
        raise SystemExit("V52_DIAGNOSTIC_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V52_DIAGNOSTIC_JSON_INVALID")
    return value


def load_successor_verifier() -> Any:
    path = Path(__file__).with_name("verify_image_only_v52_successor.py")
    spec = importlib.util.spec_from_file_location("v52_successor", path)
    if spec is None or spec.loader is None:
        fail("V52_DIAGNOSTIC_SUCCESSOR_VERIFIER_MISSING")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def material_sha(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def require_exact_input(input_directory: Path) -> dict[str, Any]:
    if not input_directory.is_dir() or input_directory.is_symlink():
        fail("V52_DIAGNOSTIC_INPUT_DIRECTORY_INVALID")
    matches: list[bytes] = []
    for path in input_directory.iterdir():
        if not path.is_file() or path.is_symlink():
            continue
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() == ARTIFACT_SHA:
            matches.append(raw)
    if len(matches) != 1:
        fail("V52_DIAGNOSTIC_INPUT_SET_MISMATCH")
    raw = matches[0]
    if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
        fail("V52_DIAGNOSTIC_INPUT_NOT_STATIC_PNG")
    width, height = struct.unpack(">II", raw[16:24])
    if len(raw) != 337855 or (width, height) != (3496, 780):
        fail("V52_DIAGNOSTIC_INPUT_METADATA_DRIFT")
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
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except Exception as error:
            raise SystemExit("V52_DIAGNOSTIC_AUTHORIZATION_INVENTORY_INVALID") from error
        if type(value) is not dict or value.get("status") not in {"PROPOSED", "OPEN", "CLOSED"}:
            fail("V52_DIAGNOSTIC_AUTHORIZATION_INVENTORY_INVALID")
        if value.get("status") == "OPEN":
            count += 1
    if count:
        fail("V52_DIAGNOSTIC_OPEN_AUTHORIZATION_FORBIDDEN")
    return count


def require_preparation(
    repository: Path,
    metadata: dict[str, Any],
    require_prelive_outputs_absent: bool = True,
) -> dict[str, str]:
    path = repository / "plans/image-only-profile-successor-diagnostics" / f"{CYCLE_ID}.json"
    raw = path.read_text(encoding="utf-8")
    if any(marker in raw.lower() for marker in FORBIDDEN_MARKERS):
        fail("V52_DIAGNOSTIC_PREPARATION_NOT_PAYLOAD_FREE")
    value = load_json(path)
    expected_top = {
        "version": PREPARATION_VERSION,
        "cycleId": CYCLE_ID,
        "createdAt": CREATED_AT,
        "stage": "PROFILE_SUCCESSOR_DIAGNOSTIC_1",
        "scoring": False,
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "pipelineVersion": "renderweave-inference-pipeline/4.34",
        "elementPromptVersion": "renderweave-visual-elements-prompt/16.0",
        "canonicalizerIdentity": CANONICALIZER_ID,
        "itemParentEnvelopeTelemetryCode": ENVELOPE_CODE,
        "successorImplementationIdentity": IMPLEMENTATION_ID,
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "manifestIdentity": MANIFEST_IDENTITY,
        "evaluatorIdentity": EVALUATOR_IDENTITY,
        "inputProvenance": "USER_PROVIDED",
        "dataClassification": "ORDINARY_DESIGN",
        "goalModelTokenCap": GOAL_TOKEN_CAP,
        "goalAggregateModelTokensBefore": GOAL_TOKENS_BEFORE,
        "goalWorstCaseModelTokensAfter": GOAL_TOKENS_BEFORE + 100_000,
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "certificationCredit": 0,
        "nextStageUnlocked": False,
        "productionDeploymentAllowed": False,
    }
    if any(value.get(key) != expected for key, expected in expected_top.items()):
        fail("V52_DIAGNOSTIC_PREPARATION_FACT_DRIFT")
    if value.get("cases") != [{"caseId": CASE_ID, "artifactSha256": ARTIFACT_SHA}]:
        fail("V52_DIAGNOSTIC_CASE_DRIFT")
    expected_normalization_object = {
        "normalizer": "static-png-identity-verification/1.0",
        "normalizedAt": CREATED_AT,
        "mediaType": metadata["mediaType"],
        "encodedBytes": metadata["encodedBytes"],
        "width": metadata["width"],
        "height": metadata["height"],
        "canonicalizerIdentity": CANONICALIZER_ID,
        "successorImplementationIdentity": IMPLEMENTATION_ID,
    }
    if value.get("normalization") != expected_normalization_object:
        fail("V52_DIAGNOSTIC_NORMALIZATION_METADATA_DRIFT")
    expected_normalization = NORMALIZATION_VERSION + ":" + material_sha([
        NORMALIZATION_VERSION,
        "normalizer=static-png-identity-verification/1.0",
        ARTIFACT_SHA, "image/png", str(metadata["encodedBytes"]),
        str(metadata["width"]), str(metadata["height"]), CANONICALIZER_ID,
        IMPLEMENTATION_ID, CREATED_AT,
    ])
    expected_evaluator = EVALUATOR_VERSION + ":" + material_sha([
        EVALUATOR_VERSION, "terminal=REVIEW_REQUIRED", "manual-acceptance=required",
        "certification-credit=forbidden", "grant=forbidden",
    ])
    expected_manifest = DIAGNOSTIC_VERSION + ":" + material_sha([
        DIAGNOSTIC_VERSION, CYCLE_ID, PROFILE_ID, PROFILE_SHA, expected_normalization,
        CASE_ID, ARTIFACT_SHA, "USER_PROVIDED", "ORDINARY_DESIGN",
        expected_evaluator, CREATED_AT,
    ])
    if (expected_normalization != NORMALIZATION_IDENTITY
            or expected_evaluator != EVALUATOR_IDENTITY
            or expected_manifest != MANIFEST_IDENTITY):
        fail("V52_DIAGNOSTIC_IDENTITY_REPLAY_DRIFT")
    required_authorization = {
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
    if value.get("requiredAuthorization") != required_authorization:
        fail("V52_DIAGNOSTIC_J1_CAPS_DRIFT")
    if value.get("externalProviderUsage") != {
        "attempts": 0, "reservations": 0, "modelTokens": 0,
        "costMicrosCny": 0, "apiKeyReads": 0,
    }:
        fail("V52_DIAGNOSTIC_PROVIDER_ZERO_DRIFT")
    authorization_path = repository / "plans/live-canary-authorizations" / (
        "20260818-image-only-v52-diagnostic-981d7262.json"
    )
    terminal_path = path.with_name(f"{CYCLE_ID}-terminal.json")
    if require_prelive_outputs_absent and (authorization_path.exists() or terminal_path.exists()):
        fail("V52_DIAGNOSTIC_PRELIVE_OUTPUT_ALREADY_EXISTS")
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
        "tools/run-image-only-v52-profile-successor-diagnostic-live.ps1",
        "renderweave-app/src/test/java/cn/hbads/renderweave/inference/ImageOnlyV52ProfileSuccessorDiagnosticLiveTest.java",
    )
    sources = [(relative, (repository / relative).read_text(encoding="utf-8")) for relative in paths]
    combined = "\n".join(source for _, source in sources)
    required = (
        "String normalizationIdentity",
        "PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH",
        "IMAGE_ONLY_V52_PROFILE_ID",
        AUTHORIZATION_ID, CYCLE_ID, NORMALIZATION_IDENTITY,
        "RENDERWEAVE_RUN_V52_PROFILE_SUCCESSOR_DIAGNOSTIC",
        "PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED",
        "automatic rerun",
        "goalAggregateModelTokens",
    )
    if any(fragment not in combined for fragment in required):
        fail("V52_DIAGNOSTIC_AUTHORITY_CONTRACT_MISSING")
    digest = hashlib.sha256()
    for relative, source in sources:
        digest.update(relative.encode("utf-8") + b"\0" + source.encode("utf-8") + b"\0")
    return "renderweave-image-only-v52-diagnostic-authority/1.0:" + digest.hexdigest()


def verify(repository: Path, input_directory: Path) -> dict[str, Any]:
    successor = load_successor_verifier()
    successor_report = successor.verify(repository)
    if successor_report["implementationIdentity"] != IMPLEMENTATION_ID:
        fail("V52_DIAGNOSTIC_SUCCESSOR_IMPLEMENTATION_DRIFT")
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
        "canonicalizerIdentity": CANONICALIZER_ID,
        "itemParentEnvelopeTelemetryCode": ENVELOPE_CODE,
        "successorImplementationIdentity": IMPLEMENTATION_ID,
        "case": metadata,
        "maximumRuns": 1,
        "maximumProviderCalls": 5,
        "maximumModelTokens": 100000,
        "maximumCostMicrosCny": 3000000,
        "maximumProviderCallsPerRun": 5,
        "maximumCostPerRunMicrosCny": 3000000,
        "maximumWindowSeconds": 7200,
        "goalModelTokenCap": GOAL_TOKEN_CAP,
        "goalAggregateModelTokensBefore": GOAL_TOKENS_BEFORE,
        "goalWorstCaseModelTokensAfter": GOAL_TOKENS_BEFORE + 100_000,
        "authorityImplementationIdentity": authority_identity,
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
        fail("V52_DIAGNOSTIC_SUMMARY_NOT_PAYLOAD_FREE")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
