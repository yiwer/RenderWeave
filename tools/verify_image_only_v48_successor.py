#!/usr/bin/env python3
"""Independent, payload-free verifier for the IMAGE_ONLY v48 successor preparation."""

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
V48_ID = "dashscope-qwen38-max-product-v48-hybrid-generic"
V48_SHA = "22f40ef4c865e11778eef4558c20c383e6611e068d8d08be0d080650074d4470"
V47_DIAGNOSTIC_TERMINAL_SHA = "5aad42165c1dd595d02e99c7c22c3c50cd2aeda83d08f6716cb8bd2081f7a664"
V47_DIAGNOSTIC_AUTHORIZATION_SHA = "3cad67afba29007bd00a3aaebd536f10c50dde4df04d425f09251a59bf126be3"
V48_DIAGNOSTIC_TERMINAL_SHA = "316029ebdf55bb5cb1dabe193f4f44b2b87dc971cd145e564d1b0c3006df811c"
V48_DIAGNOSTIC_AUTHORIZATION_SHA = "6f102c53c6192fea00ef02f1a72256f85f73c0a6abb8faee67bf80100118437b"
FAILED_ARTIFACT_SHA = "51942b84ac65efcb28d02fff359222f60b8550fe5b6d5e87389582fc5a48cfc8"
OLD_CYCLE_ID = "c3bde304-b0b2-43f8-ab7e-16896ff04aed"
OLD_AUTHORIZATION_ID = "20260817-iopa-canary5-c3bde304"
OLD_TERMINAL_FILE_SHA = "059c8a3fe38c00e1ce4a76d4c8896fa41eff976378386e21b32297a726e9a197"
DIAGNOSTIC_CYCLE_ID = "4e1f41b7-7c42-40d8-afd6-9fe3a35cc54d"
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
    v47 = load_json(profile_root / f"{V47_ID}.json")
    v48 = load_json(profile_root / f"{V48_ID}.json")
    if list(v47) != list(v48):
        fail("SUCCESSOR_PROFILE_FIELD_ORDER_DRIFT")
    changed = {key for key in v47 if v47[key] != v48[key]}
    if changed != {"profileId", "pipelineVersion", "elementPromptVersion"}:
        fail("SUCCESSOR_PROFILE_DIFF_INVALID")
    if (
        v48["profileId"] != V48_ID
        or v48["pipelineVersion"] != "renderweave-inference-pipeline/4.30"
        or v48["elementPromptVersion"] != "renderweave-visual-elements-prompt/14.0"
        or canonical_profile_sha(v47) != V47_SHA
        or canonical_profile_sha(v48) != V48_SHA
    ):
        fail("SUCCESSOR_PROFILE_IDENTITY_DRIFT")


def require_prompt_contract(repository: Path) -> None:
    prompt = (
        repository
        / "renderweave-inference/src/main/resources/inference-prompts/visual-elements-v14.txt"
    ).read_text(encoding="utf-8")
    normalized = re.sub(r"\s+", " ", prompt)
    required = (
        "at most 32 regions and at most 32 elements",
        "VISUAL_GROUNDING_REGION_ENTRY_INVALID",
        "VISUAL_GROUNDING_REGION_ID_INVALID",
        "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
        "VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID",
        "VISUAL_GROUNDING_REGION_READING_ORDER_INVALID",
        "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID",
        "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID",
        "Generic, unknown, and unlisted rejection codes are not retryable",
    )
    if any(item not in normalized for item in required):
        fail("SUCCESSOR_PROMPT_CONTRACT_DRIFT")
    if "VISUAL_GROUNDING_REGION_INVALID:" in prompt:
        fail("SUCCESSOR_GENERIC_REGION_RETRY_FORBIDDEN")
    if re.search(r"(?i)\b(bus|station|route|stop|fare)\b", prompt):
        fail("SUCCESSOR_PROMPT_DOMAIN_LEAK")


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


def require_v47_diagnostic_immutable(repository: Path) -> None:
    terminal = repository / "plans/image-only-profile-successor-diagnostics/4ae94545-2c95-41dc-934e-1661aeb6c121-terminal.json"
    authorization = repository / "plans/live-canary-authorizations/20260817-image-only-v47-diagnostic-4ae94545.json"
    if sha256_bytes(terminal.read_bytes()) != V47_DIAGNOSTIC_TERMINAL_SHA:
        fail("SUCCESSOR_V47_TERMINAL_BYTES_DRIFT")
    if sha256_bytes(authorization.read_bytes()) != V47_DIAGNOSTIC_AUTHORIZATION_SHA:
        fail("SUCCESSOR_V47_AUTHORIZATION_BYTES_DRIFT")
    terminal_value = load_json(terminal)
    authorization_value = load_json(authorization)
    if (
        terminal_value.get("lifecycle") != "TERMINAL_CLOSED"
        or terminal_value.get("result") != "FAILED"
        or terminal_value.get("providerCalls") != 3
        or terminal_value.get("unsettledReservations") != 0
        or authorization_value.get("status") != "CLOSED"
        or authorization_value.get("profileId") != V47_ID
        or authorization_value.get("profileSha256") != V47_SHA
    ):
        fail("SUCCESSOR_V47_TERMINAL_FACT_DRIFT")


def require_v48_diagnostic_immutable(repository: Path) -> dict:
    terminal_path = (
        repository
        / "plans/image-only-profile-successor-diagnostics"
        / f"{DIAGNOSTIC_CYCLE_ID}-terminal.json"
    )
    authorization_path = (
        repository
        / "plans/live-canary-authorizations"
        / "20260818-image-only-v48-diagnostic-4e1f41b7.json"
    )
    if sha256_bytes(terminal_path.read_bytes()) != V48_DIAGNOSTIC_TERMINAL_SHA:
        fail("SUCCESSOR_V48_TERMINAL_BYTES_DRIFT")
    if sha256_bytes(authorization_path.read_bytes()) != V48_DIAGNOSTIC_AUTHORIZATION_SHA:
        fail("SUCCESSOR_V48_AUTHORIZATION_BYTES_DRIFT")
    terminal = load_json(terminal_path)
    authorization = load_json(authorization_path)
    expected_case = {
        "caseId": "v46-failed-route-82",
        "artifactSha256": FAILED_ARTIFACT_SHA,
    }
    if (
        authorization.get("status") != "CLOSED"
        or authorization.get("authorizationId")
        != "20260818-iopa-v48-diagnostic-4e1f41b7"
        or authorization.get("cycleId") != DIAGNOSTIC_CYCLE_ID
        or authorization.get("profileId") != V48_ID
        or authorization.get("profileSha256") != V48_SHA
        or authorization.get("cases") != [expected_case]
        or authorization.get("maximumRuns") != 1
        or authorization.get("maximumProviderCalls") != 5
        or authorization.get("maximumModelTokens") != 100000
        or authorization.get("maximumCostMicrosCny") != 3000000
        or authorization.get("maximumProviderCallsPerRun") != 5
        or authorization.get("maximumCostPerRunMicrosCny") != 3000000
        or authorization.get("closedAt") != "2026-08-18T00:49:20.995281Z"
        or authorization.get("closureReason")
        != "PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_VISUAL_GROUNDING_REGION_INVALID"
    ):
        fail("SUCCESSOR_V48_AUTHORIZATION_FACT_DRIFT")
    cases = terminal.get("cases")
    reject_terminal = terminal.get("nonAllowlistedRejectTerminal")
    if (
        terminal.get("lifecycle") != "TERMINAL_CLOSED"
        or terminal.get("result") != "FAILED"
        or terminal.get("cycleId") != DIAGNOSTIC_CYCLE_ID
        or terminal.get("profileId") != V48_ID
        or terminal.get("profileSha256") != V48_SHA
        or terminal.get("terminalReason") != "VISUAL_GROUNDING_REGION_INVALID"
        or terminal.get("startedRuns") != 1
        or terminal.get("reviewRequiredRuns") != 0
        or terminal.get("failedRuns") != 1
        or terminal.get("providerCalls") != 1
        or terminal.get("modelTokens") != 13394
        or terminal.get("costMicrosCny") != 218928
        or terminal.get("unsettledReservations") != 0
        or terminal.get("closedAuthorizationSha256")
        != V48_DIAGNOSTIC_AUTHORIZATION_SHA
        or terminal.get("diagnosticPassed") is not False
        or terminal.get("certificationCredit") != 0
        or terminal.get("nextStageUnlocked") is not False
        or terminal.get("automaticRerunAllowed") is not False
        or terminal.get("reviewPackCreated") is not False
        or terminal.get("candidateApplied") is not False
        or terminal.get("staticSchemaPublished") is not False
        or type(reject_terminal) is not dict
        or reject_terminal.get("diagnosticCode")
        != "VISUAL_GROUNDING_REGION_INVALID"
        or reject_terminal.get("allowlistMatched") is not False
        or reject_terminal.get("countedRejectedAttempts") != 1
        or reject_terminal.get("nextReservationIssued") is not False
        or type(cases) is not list
        or len(cases) != 1
        or cases[0].get("caseId") != expected_case["caseId"]
        or cases[0].get("artifactSha256") != FAILED_ARTIFACT_SHA
        or cases[0].get("state") != "FAILED"
        or cases[0].get("failureCode") != "VISUAL_GROUNDING_REGION_INVALID"
    ):
        fail("SUCCESSOR_V48_TERMINAL_FACT_DRIFT")
    evidence_directory = terminal.get("evidenceDirectory")
    if type(evidence_directory) is not str:
        fail("SUCCESSOR_V48_LIVE_EVIDENCE_POINTER_INVALID")
    summary_path = repository / evidence_directory / "image-only-v48-diagnostic-live-summary.json"
    if (
        not summary_path.is_file()
        or sha256_bytes(summary_path.read_bytes()) != terminal.get("liveSummarySha256")
    ):
        fail("SUCCESSOR_V48_LIVE_SUMMARY_DIGEST_DRIFT")
    summary = load_json(summary_path)
    summary_cases = summary.get("cases")
    ledger = summary.get("ledger")
    if (
        summary.get("authorizationId") != authorization.get("authorizationId")
        or summary.get("cycleId") != DIAGNOSTIC_CYCLE_ID
        or summary.get("profileId") != V48_ID
        or summary.get("profileSha256") != V48_SHA
        or summary.get("lifecycle") != "PROVIDER_DIAGNOSTIC_CLOSED_TERMINAL"
        or summary.get("diagnosticPassed") is not False
        or summary.get("candidateApplied") is not False
        or summary.get("staticSchemaPublished") is not False
        or type(ledger) is not dict
        or ledger.get("status") != "CLOSED"
        or ledger.get("startedRuns") != 1
        or ledger.get("providerCalls") != 1
        or ledger.get("exposedModelTokens") != 13394
        or ledger.get("exposedCostMicrosCny") != 218928
        or ledger.get("unsettledReservations") != 0
        or type(summary_cases) is not list
        or len(summary_cases) != 1
        or summary_cases[0].get("state") != "FAILED"
        or summary_cases[0].get("failureCode")
        != "VISUAL_GROUNDING_REGION_INVALID"
        or len(summary_cases[0].get("attempts", [])) != 1
    ):
        fail("SUCCESSOR_V48_LIVE_SUMMARY_FACT_DRIFT")
    return {
        "terminalResult": "FAILED",
        "terminalSha256": V48_DIAGNOSTIC_TERMINAL_SHA,
        "closedAuthorizationSha256": V48_DIAGNOSTIC_AUTHORIZATION_SHA,
        "liveSummarySha256": terminal["liveSummarySha256"],
        "diagnosticProviderUsage": {
            "startedRuns": 1,
            "providerCalls": 1,
            "modelTokens": 13394,
            "costMicrosCny": 218928,
            "unsettledReservations": 0,
        },
    }


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
        or value.get("createdAt") != "2026-08-17T17:46:31Z"
        or value.get("stage") != "PROFILE_SUCCESSOR_DIAGNOSTIC_1"
        or value.get("scoring") is not False
        or value.get("profileId") != V48_ID
        or value.get("profileSha256") != V48_SHA
        or value.get("pipelineVersion") != "renderweave-inference-pipeline/4.30"
        or value.get("elementPromptVersion")
        != "renderweave-visual-elements-prompt/14.0"
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
        V48_ID,
        V48_SHA,
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
        "authorizationId": "20260818-iopa-v48-diagnostic-4e1f41b7",
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
    require_v47_diagnostic_immutable(repository)
    require_no_open_authorization(repository)
    input_metadata = require_exact_input(args.input_directory.resolve())
    identities = require_diagnostic_identity(repository, input_metadata)
    terminal = require_v48_diagnostic_immutable(repository)
    summary = {
        "version": "renderweave-image-only-v48-successor-provider-zero/1.0",
        "result": "PASS",
        "profileId": V48_ID,
        "profileSha256": V48_SHA,
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
        "authorizationId": "20260818-iopa-v48-diagnostic-4e1f41b7",
        "authorizationStatus": "CLOSED",
        **terminal,
        "verificationProviderUsage": {
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
    print("IMAGE_ONLY v48 successor Provider-zero verification: PASS")


if __name__ == "__main__":
    main()
