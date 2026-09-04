#!/usr/bin/env python3
"""Independent payload-free verifier for the closed IMAGE_ONLY v49 diagnostic."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v49-diagnostic-postclose/1.0"
AUTHORIZATION_SHA = "3eabfef97ad0a9f1c7fe7d947c527587109be30386bd0702a7cb1d671f90c0fa"
LIVE_SUMMARY_SHA = "e8c8cfd44bd5cfd2b40660689a4faa4a78ef3c4bf180cb2fd7c79ae9f456815e"
FORBIDDEN_MARKERS = (
    "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
    "modeloutput", "candidatejson", "rootdocument", "chain-of-thought", "bearer ",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def load_preparation_verifier() -> Any:
    path = Path(__file__).with_name("verify_image_only_v49_diagnostic_preparation.py")
    spec = importlib.util.spec_from_file_location("v49_diagnostic_preparation", path)
    if spec is None or spec.loader is None:
        fail("V49_DIAGNOSTIC_POSTCLOSE_PREPARATION_VERIFIER_MISSING")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as error:
        raise SystemExit("V49_DIAGNOSTIC_POSTCLOSE_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V49_DIAGNOSTIC_POSTCLOSE_JSON_INVALID")
    return value


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_payload_free(path: Path) -> None:
    lowered = path.read_text(encoding="utf-8").lower()
    if any(marker in lowered for marker in FORBIDDEN_MARKERS):
        fail("V49_DIAGNOSTIC_POSTCLOSE_NOT_PAYLOAD_FREE")


def require_closed_authorization(repository: Path, preparation: Any) -> dict[str, Any]:
    path = (
        repository / "plans/live-canary-authorizations"
        / "20260818-image-only-v49-diagnostic-432fdfeb.json"
    )
    require_payload_free(path)
    if sha256(path) != AUTHORIZATION_SHA:
        fail("V49_DIAGNOSTIC_CLOSED_AUTHORIZATION_BYTES_DRIFT")
    value = load_json(path)
    expected = {
        "version": "renderweave-image-only-certification-authorization/1.0",
        "authorizationId": preparation.AUTHORIZATION_ID,
        "status": "CLOSED",
        "cycleId": preparation.CYCLE_ID,
        "stage": "PROFILE_SUCCESSOR_DIAGNOSTIC_1",
        "profileId": preparation.PROFILE_ID,
        "profileSha256": preparation.PROFILE_SHA,
        "manifestIdentity": preparation.MANIFEST_IDENTITY,
        "evaluatorIdentity": preparation.EVALUATOR_IDENTITY,
        "normalizationIdentity": preparation.NORMALIZATION_IDENTITY,
        "provider": "DASHSCOPE",
        "model": "qwen3.8-max",
        "providerBaseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "inputProvenance": "USER_PROVIDED",
        "dataClassification": "ORDINARY_DESIGN",
        "maximumRuns": 1,
        "maximumProviderCalls": 5,
        "maximumModelTokens": 100000,
        "maximumCostMicrosCny": 3000000,
        "maximumProviderCallsPerRun": 5,
        "maximumCostPerRunMicrosCny": 3000000,
        "effectiveAt": "2026-08-18T04:15:42.6112014Z",
        "expiresAt": "2026-08-18T06:15:42.6112014Z",
        "approvedAt": "2026-08-18T04:15:42.6112014Z",
        "approvalScope": "IMAGE_ONLY_PROFILE_SUCCESSOR_DIAGNOSTIC_1",
        "closedAt": "2026-08-18T04:20:31.3152151Z",
        "closureReason": (
            "PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_"
            "VISUAL_GROUNDING_REGION_FIELDS_INVALID"
        ),
    }
    if any(value.get(key) != expected_value for key, expected_value in expected.items()):
        fail("V49_DIAGNOSTIC_CLOSED_AUTHORIZATION_FACT_DRIFT")
    if value.get("cases") != [{
        "caseId": preparation.CASE_ID,
        "artifactSha256": preparation.ARTIFACT_SHA,
    }]:
        fail("V49_DIAGNOSTIC_CLOSED_AUTHORIZATION_CASE_DRIFT")
    return value


def require_live_summary(repository: Path, preparation: Any) -> dict[str, Any]:
    path = (
        repository / ".sdlc/evidence/20260818-121611-image-only-v49-successor-diagnostic-live"
        / "image-only-v49-diagnostic-live-summary.json"
    )
    require_payload_free(path)
    if sha256(path) != LIVE_SUMMARY_SHA:
        fail("V49_DIAGNOSTIC_LIVE_SUMMARY_BYTES_DRIFT")
    value = load_json(path)
    ledger = value.get("ledger")
    cases = value.get("cases")
    if (
        value.get("authorizationId") != preparation.AUTHORIZATION_ID
        or value.get("cycleId") != preparation.CYCLE_ID
        or value.get("profileId") != preparation.PROFILE_ID
        or value.get("profileSha256") != preparation.PROFILE_SHA
        or value.get("manifestIdentity") != preparation.MANIFEST_IDENTITY
        or value.get("evaluatorIdentity") != preparation.EVALUATOR_IDENTITY
        or value.get("normalizationIdentity") != preparation.NORMALIZATION_IDENTITY
        or value.get("lifecycle") != "PROVIDER_DIAGNOSTIC_CLOSED_TERMINAL"
        or value.get("diagnosticPassed") is not False
        or value.get("certificationCredit") != 0
        or value.get("nextStageUnlocked") is not False
        or value.get("candidateApplied") is not False
        or value.get("staticSchemaPublished") is not False
        or type(ledger) is not dict
        or ledger.get("status") != "CLOSED"
        or ledger.get("startedRuns") != 1
        or ledger.get("providerCalls") != 5
        or ledger.get("exposedModelTokens") != 67373
        or ledger.get("exposedCostMicrosCny") != 1086900
        or ledger.get("unsettledReservations") != 0
        or ledger.get("closureReason") != "PROFILE_SUCCESSOR_DIAGNOSTIC_PROVIDER_HALTED"
        or type(cases) is not list
        or len(cases) != 1
        or cases[0].get("state") != "FAILED"
        or cases[0].get("failureCode") != "VISUAL_GROUNDING_REGION_FIELDS_INVALID"
        or cases[0].get("reviewRequired") is not False
        or len(cases[0].get("attempts", [])) != 5
    ):
        fail("V49_DIAGNOSTIC_LIVE_SUMMARY_FACT_DRIFT")
    expected_sets = [
        ["VISUAL_GROUNDING_REGION_ID_INVALID",
         "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
         "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID"],
        ["VISUAL_GROUNDING_REGION_ID_INVALID",
         "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID"],
        ["VISUAL_GROUNDING_ELEMENT_INVALID"],
        ["VISUAL_GROUNDING_REGION_ID_INVALID",
         "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
         "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID"],
        ["VISUAL_GROUNDING_REGION_ID_INVALID",
         "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
         "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID"],
    ]
    observed_sets = [sorted(attempt.get("problemCodeCounts", {}).keys())
                     for attempt in cases[0]["attempts"]]
    if observed_sets != expected_sets:
        fail("V49_DIAGNOSTIC_LIVE_FIXED_CODE_SET_DRIFT")
    return value


def require_terminal(repository: Path, preparation: Any) -> tuple[dict[str, Any], str]:
    path = (
        repository / "plans/image-only-profile-successor-diagnostics"
        / f"{preparation.CYCLE_ID}-terminal.json"
    )
    require_payload_free(path)
    value = load_json(path)
    breaker = value.get("equivalentRejectBreaker")
    hard_cap = value.get("hardCap")
    cases = value.get("cases")
    if (
        value.get("result") != "FAILED"
        or value.get("lifecycle") != "TERMINAL_CLOSED"
        or value.get("authorizationId") != preparation.AUTHORIZATION_ID
        or value.get("profileId") != preparation.PROFILE_ID
        or value.get("profileSha256") != preparation.PROFILE_SHA
        or value.get("normalizationIdentity") != preparation.NORMALIZATION_IDENTITY
        or value.get("terminalReason") != "VISUAL_GROUNDING_REGION_FIELDS_INVALID"
        or value.get("startedRuns") != 1
        or value.get("reviewRequiredRuns") != 0
        or value.get("failedRuns") != 1
        or value.get("providerCalls") != 5
        or value.get("modelTokens") != 67373
        or value.get("costMicrosCny") != 1086900
        or value.get("unsettledReservations") != 0
        or value.get("liveSummarySha256") != LIVE_SUMMARY_SHA
        or value.get("closedAuthorizationSha256") != AUTHORIZATION_SHA
        or value.get("diagnosticPassed") is not False
        or value.get("certificationCredit") != 0
        or value.get("nextStageUnlocked") is not False
        or value.get("automaticRerunAllowed") is not False
        or value.get("reviewPackCreated") is not False
        or value.get("candidateApplied") is not False
        or value.get("staticSchemaPublished") is not False
        or value.get("productionDeployed") is not False
        or type(breaker) is not dict
        or breaker.get("countedEquivalentRejectedAttempts") != 3
        or breaker.get("nextReservationIssued") is not False
        or type(hard_cap) is not dict
        or hard_cap.get("providerCalls") != 5
        or hard_cap.get("sixthReservationIssued") is not False
        or type(cases) is not list
        or len(cases) != 1
        or cases[0].get("state") != "FAILED"
    ):
        fail("V49_DIAGNOSTIC_TERMINAL_FACT_DRIFT")
    return value, sha256(path)


def require_no_review_payload(repository: Path, preparation: Any) -> None:
    directory = (
        repository / ".scratch/image-only-profile-successor-diagnostic-reviews"
        / preparation.AUTHORIZATION_ID
    )
    if not directory.is_dir() or any(item.is_file() for item in directory.iterdir()):
        fail("V49_DIAGNOSTIC_REVIEW_PACK_DRIFT")


def verify(repository: Path, input_directory: Path) -> dict[str, Any]:
    preparation = load_preparation_verifier()
    successor = preparation.load_successor_verifier()
    successor_report = successor.verify(repository)
    metadata = preparation.require_exact_input(input_directory)
    identities = preparation.require_preparation(
        repository, metadata, require_prelive_outputs_absent=False)
    authorization = require_closed_authorization(repository, preparation)
    summary = require_live_summary(repository, preparation)
    terminal, terminal_sha = require_terminal(repository, preparation)
    require_no_review_payload(repository, preparation)
    open_count = preparation.require_no_open_authorization(repository)
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "lifecycle": "TERMINAL_CLOSED",
        "terminalResult": "FAILED",
        "profileId": preparation.PROFILE_ID,
        "profileSha256": preparation.PROFILE_SHA,
        "cycleId": preparation.CYCLE_ID,
        "authorizationId": preparation.AUTHORIZATION_ID,
        "authorizationStatus": authorization["status"],
        **identities,
        "closedAuthorizationSha256": AUTHORIZATION_SHA,
        "terminalSha256": terminal_sha,
        "liveSummarySha256": LIVE_SUMMARY_SHA,
        "terminalReason": terminal["terminalReason"],
        "diagnosticProviderUsage": {
            "startedRuns": 1,
            "providerCalls": summary["ledger"]["providerCalls"],
            "modelTokens": summary["ledger"]["exposedModelTokens"],
            "costMicrosCny": summary["ledger"]["exposedCostMicrosCny"],
            "unsettledReservations": summary["ledger"]["unsettledReservations"],
        },
        "goalModelTokenUsage": 67373,
        "goalModelTokenHardCap": 1500000,
        "goalModelTokensRemaining": 1432627,
        "postcloseVerificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "openAuthorizationCount": open_count,
        "certificationCredit": 0,
        "nextStageUnlocked": False,
        "automaticRerunAllowed": False,
        "reviewPackCreated": False,
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "productionDeployed": False,
        "successorImplementationIdentity": successor_report["registryImplementationIdentity"],
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
        fail("V49_DIAGNOSTIC_POSTCLOSE_SUMMARY_NOT_PAYLOAD_FREE")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
