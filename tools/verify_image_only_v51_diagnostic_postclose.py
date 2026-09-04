#!/usr/bin/env python3
"""Independent payload-free replay of the immutable IMAGE_ONLY v51 diagnostic terminal."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v51-diagnostic-postclose/1.0"
CYCLE_ID = "7d929b74-47ca-40a7-bfd5-061e070c2bd2"
AUTHORIZATION_ID = "20260818-iopa-v51-diagnostic-7d929b74"
PROFILE_ID = "dashscope-qwen38-max-product-v51-hybrid-generic"
PROFILE_SHA = "972001414977a7cc788def6e8e106b2c7f146a306d1fa328d48ff053d472d3bd"
NORMALIZATION_IDENTITY = (
    "renderweave-image-only-fresh-normalization/1.0:"
    "632c601ccdcbd561fcb9502777a888712a564a81352f9f19b163b4a0e9a6b4cc"
)
MANIFEST_IDENTITY = (
    "renderweave-image-only-profile-successor-diagnostic/1.0:"
    "e2a01f1d788c52bcf87838a242201a32d1b28dec741640abd6b6a2be8d690925"
)
AUTH_SHA = "3ba6ef75552f6b259d4ec271b6f2b0c9558a03a94c7d0c8478f074bf1cbab483"
TERMINAL_SHA = "1e9825a4f3feb59972d03671c1952e934de915d281759403e933f3cbb75075bb"
LIVE_SUMMARY_SHA = "102fb5a99fe8ebf313fef77c1834ee33e81d6cbad8aebd915cf37c079ae602f1"
CASE_SHA = "51942b84ac65efcb28d02fff359222f60b8550fe5b6d5e87389582fc5a48cfc8"
FAILURE_CODE = "VISUAL_GROUNDING_PARENT_CONTAINMENT_CLASSIFIED"
DETAIL_CODE = "VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_ZERO_COMPATIBLE"
FORBIDDEN_MARKERS = (
    "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
    "modeloutput", "candidatejson", "rootdocument", "chain-of-thought", "api key",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("V51_POSTCLOSE_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load_json(path: Path) -> tuple[bytes, dict[str, Any]]:
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    try:
        value = json.loads(text, object_pairs_hook=strict_pairs)
    except SystemExit:
        raise
    except Exception as error:
        raise SystemExit("V51_POSTCLOSE_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V51_POSTCLOSE_JSON_INVALID")
    if any(marker in text.lower() for marker in FORBIDDEN_MARKERS):
        fail("V51_POSTCLOSE_PAYLOAD_LEAK")
    return raw, value


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def load_preparation_verifier() -> Any:
    path = Path(__file__).with_name("verify_image_only_v51_diagnostic_preparation.py")
    spec = importlib.util.spec_from_file_location("v51_preparation", path)
    if spec is None or spec.loader is None:
        fail("V51_POSTCLOSE_PREPARATION_VERIFIER_MISSING")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_terminal_documents(repository: Path) -> dict[str, Any]:
    auth_path = repository / "plans/live-canary-authorizations" / (
        "20260818-image-only-v51-diagnostic-7d929b74.json"
    )
    terminal_path = repository / "plans/image-only-profile-successor-diagnostics" / (
        f"{CYCLE_ID}-terminal.json"
    )
    live_path = repository / ".sdlc/evidence" / (
        "20260818-143933-image-only-v51-successor-diagnostic-live"
    ) / "image-only-v51-diagnostic-live-summary.json"
    auth_raw, auth = load_json(auth_path)
    terminal_raw, terminal = load_json(terminal_path)
    live_raw, live = load_json(live_path)
    if sha(auth_raw) != AUTH_SHA or sha(terminal_raw) != TERMINAL_SHA or sha(live_raw) != LIVE_SUMMARY_SHA:
        fail("V51_POSTCLOSE_DIGEST_DRIFT")
    expected_auth = {
        "authorizationId": AUTHORIZATION_ID,
        "status": "CLOSED",
        "cycleId": CYCLE_ID,
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "manifestIdentity": MANIFEST_IDENTITY,
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "maximumRuns": 1,
        "maximumProviderCalls": 5,
        "maximumModelTokens": 100000,
        "maximumCostMicrosCny": 3000000,
        "maximumProviderCallsPerRun": 5,
        "maximumCostPerRunMicrosCny": 3000000,
        "closedAt": "2026-08-18T06:40:42.959996Z",
        "closureReason": "PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_" + FAILURE_CODE,
    }
    if any(auth.get(key) != value for key, value in expected_auth.items()):
        fail("V51_POSTCLOSE_AUTHORIZATION_DRIFT")
    if auth.get("closedAt") != terminal.get("closedAt"):
        fail("V51_POSTCLOSE_CLOSURE_TIME_DRIFT")
    expected_terminal = {
        "cycleId": CYCLE_ID,
        "authorizationId": AUTHORIZATION_ID,
        "result": "FAILED",
        "lifecycle": "TERMINAL_CLOSED",
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "manifestIdentity": MANIFEST_IDENTITY,
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "terminalReason": FAILURE_CODE,
        "authorizedRuns": 1,
        "startedRuns": 1,
        "reviewRequiredRuns": 0,
        "failedRuns": 1,
        "providerCalls": 1,
        "modelTokens": 13845,
        "costMicrosCny": 228780,
        "unsettledReservations": 0,
        "liveSummarySha256": LIVE_SUMMARY_SHA,
        "closedAuthorizationSha256": AUTH_SHA,
        "goalAggregateModelTokens": 121618,
        "goalModelTokenCap": 1500000,
        "goalRemainingModelTokens": 1378382,
        "diagnosticPassed": False,
        "certificationCredit": 0,
        "nextStageUnlocked": False,
        "automaticRerunAllowed": False,
        "reviewPackCreated": False,
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "productionDeployed": False,
    }
    if any(terminal.get(key) != value for key, value in expected_terminal.items()):
        fail("V51_POSTCLOSE_TERMINAL_DRIFT")
    classification = terminal.get("nonRetryableClassification")
    if classification != {
        "stage": "OBSERVE",
        "primaryCode": FAILURE_CODE,
        "detailCodes": [DETAIL_CODE],
        "countedRejectedAttempts": 1,
        "attemptOrdinals": [0],
        "secondReservationIssued": False,
    }:
        fail("V51_POSTCLOSE_CLASSIFICATION_DRIFT")
    if terminal.get("attemptFixedCodeSets") != [
        {"attemptOrdinal": 0, "codes": [DETAIL_CODE]}
    ]:
        fail("V51_POSTCLOSE_FIXED_CODE_SET_DRIFT")
    cases = terminal.get("cases")
    if (type(cases) is not list or len(cases) != 1
            or cases[0].get("artifactSha256") != CASE_SHA
            or cases[0].get("failureCode") != FAILURE_CODE
            or cases[0].get("providerCalls") != 1):
        fail("V51_POSTCLOSE_CASE_DRIFT")
    live_expected = {
        "authorizationId": AUTHORIZATION_ID,
        "cycleId": CYCLE_ID,
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "manifestIdentity": MANIFEST_IDENTITY,
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "lifecycle": "PROVIDER_DIAGNOSTIC_CLOSED_TERMINAL",
        "diagnosticPassed": False,
        "certificationCredit": 0,
        "nextStageUnlocked": False,
        "candidateApplied": False,
        "staticSchemaPublished": False,
    }
    if any(live.get(key) != value for key, value in live_expected.items()):
        fail("V51_POSTCLOSE_LIVE_SUMMARY_DRIFT")
    ledger = live.get("ledger")
    if type(ledger) is not dict or any((
        ledger.get("status") != "CLOSED",
        ledger.get("startedRuns") != 1,
        ledger.get("providerCalls") != 1,
        ledger.get("exposedModelTokens") != 13845,
        ledger.get("exposedCostMicrosCny") != 228780,
        ledger.get("unsettledReservations") != 0,
    )):
        fail("V51_POSTCLOSE_LEDGER_DRIFT")
    live_cases = live.get("cases")
    if type(live_cases) is not list or len(live_cases) != 1:
        fail("V51_POSTCLOSE_LIVE_CASE_DRIFT")
    attempts = live_cases[0].get("attempts")
    if type(attempts) is not list or len(attempts) != 1:
        fail("V51_POSTCLOSE_LIVE_ATTEMPT_DRIFT")
    attempt = attempts[0]
    if any((
        attempt.get("attemptOrdinal") != 0,
        attempt.get("stage") != "OBSERVE",
        attempt.get("status") != "REJECTED",
        attempt.get("outcomeCode") != "LIVE_VISUAL_ANALYSIS_REJECTED",
        attempt.get("problemCodeCounts") != {DETAIL_CODE: 1},
        attempt.get("inputTokens") + attempt.get("outputTokens") != 13845,
        attempt.get("costMicrosCny") != 228780,
    )):
        fail("V51_POSTCLOSE_LIVE_ATTEMPT_DRIFT")
    review = repository / ".scratch/image-only-profile-successor-diagnostic-reviews" / AUTHORIZATION_ID
    if review.exists() and any(path.is_file() for path in review.iterdir()):
        fail("V51_POSTCLOSE_REVIEW_PACK_FORBIDDEN")
    return {
        "authorizationSha256": sha(auth_raw),
        "terminalSha256": sha(terminal_raw),
        "liveSummarySha256": sha(live_raw),
    }


def require_closed_stubs(repository: Path) -> str:
    paths = (
        "tools/run-image-only-profile-successor-diagnostic-live.ps1",
        "renderweave-app/src/test/java/cn/hbads/renderweave/inference/ImageOnlyProfileSuccessorDiagnosticLiveTest.java",
    )
    digest = hashlib.sha256()
    combined = ""
    for relative in paths:
        source = (repository / relative).read_text(encoding="utf-8")
        combined += source
        digest.update(relative.encode("utf-8") + b"\0" + source.encode("utf-8") + b"\0")
    required = (
        AUTHORIZATION_ID, CYCLE_ID, NORMALIZATION_IDENTITY,
        "AuthorizationStatus.CLOSED", "automatic rerun is forbidden",
        "RENDERWEAVE_RUN_V51_PROFILE_SUCCESSOR_DIAGNOSTIC",
    )
    if any(fragment not in combined for fragment in required):
        fail("V51_POSTCLOSE_STUB_CONTRACT_MISSING")
    return "renderweave-image-only-v51-closed-harness/1.0:" + digest.hexdigest()


def verify(repository: Path, input_directory: Path) -> dict[str, Any]:
    preparation = load_preparation_verifier()
    metadata = preparation.require_exact_input(input_directory)
    identities = preparation.require_preparation(
        repository, metadata, require_prelive_outputs_absent=False
    )
    digests = require_terminal_documents(repository)
    open_count = preparation.require_no_open_authorization(repository)
    if open_count != 0:
        fail("V51_POSTCLOSE_OPEN_AUTHORIZATION")
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "PROFILE_SUCCESSOR_DIAGNOSTIC_1_POST_CLOSE",
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "cycleId": CYCLE_ID,
        "authorizationId": AUTHORIZATION_ID,
        "authorizationStatus": "CLOSED",
        **identities,
        **digests,
        "failureCode": FAILURE_CODE,
        "detailCode": DETAIL_CODE,
        "closedHarnessIdentity": require_closed_stubs(repository),
        "openAuthorizationCount": open_count,
        "providerCalls": 1,
        "modelTokens": 13845,
        "costMicrosCny": 228780,
        "unsettledReservations": 0,
        "goalAggregateModelTokens": 121618,
        "goalModelTokenCap": 1500000,
        "goalRemainingModelTokens": 1378382,
        "certificationCredit": 0,
        "nextStageUnlocked": False,
        "automaticRerunAllowed": False,
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "productionDeployed": False,
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
        fail("V51_POSTCLOSE_SUMMARY_PAYLOAD_LEAK")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
