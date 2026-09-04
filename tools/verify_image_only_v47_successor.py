#!/usr/bin/env python3
"""Independent, payload-free verifier for the IMAGE_ONLY v47 successor preparation."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from pathlib import Path


V46_ID = "dashscope-qwen38-max-product-v46-hybrid-generic"
V46_SHA = "22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c"
V47_ID = "dashscope-qwen38-max-product-v47-hybrid-generic"
V47_SHA = "a9fe98e1cfa4b7cc126db1f74601fdebe60526a1c999924daf189ed5f1ac5eb0"
FAILED_ARTIFACT_SHA = "51942b84ac65efcb28d02fff359222f60b8550fe5b6d5e87389582fc5a48cfc8"
OLD_CYCLE_ID = "c3bde304-b0b2-43f8-ab7e-16896ff04aed"
OLD_AUTHORIZATION_ID = "20260817-iopa-canary5-c3bde304"
OLD_TERMINAL_FILE_SHA = "059c8a3fe38c00e1ce4a76d4c8896fa41eff976378386e21b32297a726e9a197"
DIAGNOSTIC_CYCLE_ID = "4ae94545-2c95-41dc-934e-1661aeb6c121"
DIAGNOSTIC_VERSION = "renderweave-image-only-profile-successor-diagnostic/1.0"
NORMALIZATION_VERSION = "renderweave-image-only-fresh-normalization/1.0"
EVALUATOR_VERSION = (
    "renderweave-image-only-profile-successor-diagnostic-evaluator/1.0"
)


def fail(code: str) -> None:
    raise SystemExit(code)


def load_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as error:
        raise SystemExit("SUCCESSOR_JSON_INVALID") from error
    if type(value) is not dict:
        fail("SUCCESSOR_JSON_INVALID")
    return value


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_profile_sha(value: dict) -> str:
    # InferenceProfileRegistry serializes record components in their declared/resource order.
    encoded = json.dumps(
        value, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    return sha256_bytes(encoded)


def material_sha(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def require_exact_profile_diff(repository: Path) -> None:
    profile_root = repository / "renderweave-inference/src/main/resources/inference-profiles"
    v46 = load_json(profile_root / f"{V46_ID}.json")
    v47 = load_json(profile_root / f"{V47_ID}.json")
    if list(v46) != list(v47):
        fail("SUCCESSOR_PROFILE_FIELD_ORDER_DRIFT")
    changed = {key for key in v46 if v46[key] != v47[key]}
    if changed != {"profileId", "pipelineVersion", "elementPromptVersion"}:
        fail("SUCCESSOR_PROFILE_DIFF_INVALID")
    if (
        v47["profileId"] != V47_ID
        or v47["pipelineVersion"] != "renderweave-inference-pipeline/4.29"
        or v47["elementPromptVersion"]
        != "renderweave-visual-elements-prompt/13.0"
        or canonical_profile_sha(v46) != V46_SHA
        or canonical_profile_sha(v47) != V47_SHA
    ):
        fail("SUCCESSOR_PROFILE_IDENTITY_DRIFT")


def require_prompt_contract(repository: Path) -> None:
    prompt = (
        repository
        / "renderweave-inference/src/main/resources/inference-prompts/visual-elements-v13.txt"
    ).read_text(encoding="utf-8")
    normalized = re.sub(r"\s+", " ", prompt)
    required = (
        "at most 32 regions and at most 32 elements",
        "at most three representative",
        "snake_case",
        "VISUAL_GROUNDING_OUTPUT_TRUNCATED",
        "regenerate one complete, smaller JSON object",
        "Keep only high-confidence reusable structure",
    )
    if any(item not in normalized for item in required):
        fail("SUCCESSOR_PROMPT_CONTRACT_DRIFT")


def require_old_terminal_immutable(repository: Path) -> None:
    terminal_path = (
        repository
        / "plans/image-only-certification-cycles"
        / f"{OLD_CYCLE_ID}-canary5-terminal.json"
    )
    if sha256_bytes(terminal_path.read_bytes()) != OLD_TERMINAL_FILE_SHA:
        fail("SUCCESSOR_OLD_TERMINAL_BYTES_DRIFT")
    terminal = load_json(terminal_path)
    if (
        terminal.get("cycleId") != OLD_CYCLE_ID
        or terminal.get("authorizationId") != OLD_AUTHORIZATION_ID
        or terminal.get("profileId") != V46_ID
        or terminal.get("profileSha256") != V46_SHA
        or terminal.get("result") != "FAILED"
        or terminal.get("lifecycle") != "TERMINAL_CLOSED"
        or terminal.get("providerCalls") != 17
        or terminal.get("modelTokens") != 301409
        or terminal.get("costMicrosCny") != 6338772
        or terminal.get("unsettledReservations") != 0
        or terminal.get("candidateApplied") is not False
        or terminal.get("staticSchemaPublished") is not False
        or terminal.get("nextLiveStageUnlocked") is not False
    ):
        fail("SUCCESSOR_OLD_TERMINAL_FACT_DRIFT")
    authorization = load_json(
        repository
        / "plans/live-canary-authorizations/20260817-image-only-canary-5-c3bde304.json"
    )
    if (
        authorization.get("authorizationId") != OLD_AUTHORIZATION_ID
        or authorization.get("status") != "CLOSED"
        or authorization.get("cycleId") != OLD_CYCLE_ID
    ):
        fail("SUCCESSOR_OLD_AUTHORIZATION_DRIFT")


def require_no_open_authorization(repository: Path) -> None:
    root = repository / "plans/live-canary-authorizations"
    for path in root.glob("*.json"):
        if path.name.startswith("TEMPLATE-") or path.name.endswith(".schema.json"):
            continue
        value = load_json(path)
        if value.get("status") == "OPEN":
            fail("SUCCESSOR_OPEN_AUTHORIZATION_FORBIDDEN")


def png_dimensions(value: bytes) -> tuple[int, int]:
    if len(value) < 24 or value[:8] != b"\x89PNG\r\n\x1a\n" or value[12:16] != b"IHDR":
        fail("SUCCESSOR_DIAGNOSTIC_INPUT_NOT_STATIC_PNG")
    return struct.unpack(">II", value[16:24])


def require_exact_input(input_directory: Path) -> dict:
    if not input_directory.is_dir() or input_directory.is_symlink():
        fail("SUCCESSOR_DIAGNOSTIC_INPUT_DIRECTORY_INVALID")
    matches: list[tuple[bytes, Path]] = []
    for path in input_directory.iterdir():
        if not path.is_file() or path.is_symlink():
            continue
        raw = path.read_bytes()
        if sha256_bytes(raw) == FAILED_ARTIFACT_SHA:
            matches.append((raw, path))
    if len(matches) != 1:
        fail("SUCCESSOR_DIAGNOSTIC_INPUT_SET_MISMATCH")
    raw, _ = matches[0]
    width, height = png_dimensions(raw)
    if len(raw) != 337855 or (width, height) != (3496, 780):
        fail("SUCCESSOR_DIAGNOSTIC_INPUT_METADATA_DRIFT")
    return {
        "artifactSha256": FAILED_ARTIFACT_SHA,
        "encodedBytes": len(raw),
        "width": width,
        "height": height,
    }


def require_diagnostic_identity(repository: Path, input_metadata: dict) -> dict:
    path = (
        repository
        / "plans/image-only-profile-successor-diagnostics"
        / f"{DIAGNOSTIC_CYCLE_ID}.json"
    )
    raw_text = path.read_text(encoding="utf-8")
    lowered = raw_text.lower()
    if any(token in lowered for token in (
        "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
        "modeloutput", "candidatejson", "rootdocument", "chain-of-thought",
    )):
        fail("SUCCESSOR_DIAGNOSTIC_EVIDENCE_NOT_PAYLOAD_FREE")
    value = load_json(path)
    if (
        value.get("cycleId") != DIAGNOSTIC_CYCLE_ID
        or value.get("createdAt") != "2026-08-17T12:34:00Z"
        or value.get("stage") != "PROFILE_SUCCESSOR_DIAGNOSTIC_1"
        or value.get("scoring") is not False
        or value.get("profileId") != V47_ID
        or value.get("profileSha256") != V47_SHA
        or value.get("pipelineVersion") != "renderweave-inference-pipeline/4.29"
        or value.get("elementPromptVersion")
        != "renderweave-visual-elements-prompt/13.0"
        or value.get("inputProvenance") != "USER_PROVIDED"
        or value.get("dataClassification") != "ORDINARY_DESIGN"
        or value.get("certificationCredit") != 0
        or value.get("nextStageUnlocked") is not False
    ):
        fail("SUCCESSOR_DIAGNOSTIC_MANIFEST_DRIFT")
    cases = value.get("cases")
    if cases != [{
        "caseId": "v46-failed-route-82",
        "artifactSha256": FAILED_ARTIFACT_SHA,
    }]:
        fail("SUCCESSOR_DIAGNOSTIC_CASE_DRIFT")
    normalization = value.get("normalization")
    if type(normalization) is not dict or (
        normalization.get("normalizer") != "static-png-identity-verification/1.0"
        or normalization.get("normalizedAt") != value["createdAt"]
        or normalization.get("mediaType") != "image/png"
        or normalization.get("encodedBytes") != input_metadata["encodedBytes"]
        or normalization.get("width") != input_metadata["width"]
        or normalization.get("height") != input_metadata["height"]
    ):
        fail("SUCCESSOR_DIAGNOSTIC_NORMALIZATION_DRIFT")
    expected_normalization = NORMALIZATION_VERSION + ":" + material_sha([
        NORMALIZATION_VERSION,
        "normalizer=static-png-identity-verification/1.0",
        FAILED_ARTIFACT_SHA,
        "image/png",
        str(input_metadata["encodedBytes"]),
        str(input_metadata["width"]),
        str(input_metadata["height"]),
        value["createdAt"],
    ])
    expected_evaluator = EVALUATOR_VERSION + ":" + material_sha([
        EVALUATOR_VERSION,
        "terminal=REVIEW_REQUIRED",
        "manual-acceptance=required",
        "certification-credit=forbidden",
        "grant=forbidden",
    ])
    expected_manifest = DIAGNOSTIC_VERSION + ":" + material_sha([
        DIAGNOSTIC_VERSION,
        DIAGNOSTIC_CYCLE_ID,
        V47_ID,
        V47_SHA,
        expected_normalization,
        cases[0]["caseId"],
        FAILED_ARTIFACT_SHA,
        "USER_PROVIDED",
        "ORDINARY_DESIGN",
        expected_evaluator,
        value["createdAt"],
    ])
    if (
        value.get("normalizationIdentity") != expected_normalization
        or value.get("evaluatorIdentity") != expected_evaluator
        or value.get("manifestIdentity") != expected_manifest
    ):
        fail("SUCCESSOR_DIAGNOSTIC_IDENTITY_DRIFT")
    authorization = value.get("requiredAuthorization")
    if authorization != {
        "authorizationId": "20260817-iopa-v47-diagnostic-4ae94545",
        "status": "PENDING_J1",
        "approvalScope": "IMAGE_ONLY_PROFILE_SUCCESSOR_DIAGNOSTIC_1",
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
    }:
        fail("SUCCESSOR_DIAGNOSTIC_J1_CAPS_DRIFT")
    usage = value.get("externalProviderUsage")
    if usage != {"attempts": 0, "reservations": 0, "costMicrosCny": 0, "apiKeyReads": 0}:
        fail("SUCCESSOR_DIAGNOSTIC_PROVIDER_ZERO_DRIFT")
    return {
        "cycleId": DIAGNOSTIC_CYCLE_ID,
        "normalizationIdentity": expected_normalization,
        "manifestIdentity": expected_manifest,
        "evaluatorIdentity": expected_evaluator,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--input-directory", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    repository = args.repository.resolve()
    require_exact_profile_diff(repository)
    require_prompt_contract(repository)
    require_old_terminal_immutable(repository)
    require_no_open_authorization(repository)
    input_metadata = require_exact_input(args.input_directory.resolve())
    identities = require_diagnostic_identity(repository, input_metadata)
    summary = {
        "version": "renderweave-image-only-v47-successor-provider-zero/1.0",
        "result": "PASS",
        "profileId": V47_ID,
        "profileSha256": V47_SHA,
        "stage": "PROFILE_SUCCESSOR_DIAGNOSTIC_1",
        **identities,
        "diagnosticArtifactSha256": FAILED_ARTIFACT_SHA,
        "maximumRuns": 1,
        "maximumProviderCalls": 5,
        "maximumModelTokens": 100000,
        "maximumCostMicrosCny": 3000000,
        "maximumProviderCallsPerRun": 5,
        "maximumCostPerRunMicrosCny": 3000000,
        "maximumWindowSeconds": 7200,
        "authorizationId": "20260817-iopa-v47-diagnostic-4ae94545",
        "authorizationStatus": "PENDING_J1",
        "externalProviderUsage": {
            "attempts": 0,
            "reservations": 0,
            "costMicrosCny": 0,
            "apiKeyReads": 0,
        },
        "candidateApplied": False,
        "staticSchemaPublished": False,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print("IMAGE_ONLY v47 successor Provider-zero verification: PASS")


if __name__ == "__main__":
    main()
