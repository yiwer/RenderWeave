#!/usr/bin/env python3
"""Independent payload-free replay of the immutable IMAGE_ONLY v50 diagnostic terminal."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v50-diagnostic-postclose/1.0"
CYCLE_ID = "82f1d86b-065b-4357-924e-19945daf1077"
AUTHORIZATION_ID = "20260818-iopa-v50-diagnostic-82f1d86b"
PROFILE_ID = "dashscope-qwen38-max-product-v50-hybrid-generic"
PROFILE_SHA = "62f333aee7096f09d6d04dea004641e8b0a9c425ee133d09a563594d81200691"
NORMALIZATION_IDENTITY = (
    "renderweave-image-only-fresh-normalization/1.0:"
    "146c27620edad71fd40618772c3c1fc8613684d83b91bf20edc5d944b7a4b8b4"
)
MANIFEST_IDENTITY = (
    "renderweave-image-only-profile-successor-diagnostic/1.0:"
    "4715941eb4cfe8ae6d44e8943a8ec2592ad290f044f3b05ea362ec5afb6ac76e"
)
AUTH_SHA = "3c2cf2d70e71766a592b8912dc9a36fe93dd929ce381f3c971f24f00de2d1329"
TERMINAL_SHA = "aaec9fa058dc2caeba724af5915194c0030c9c8fe12aa8df8ea42931681b1ca5"
LIVE_SUMMARY_SHA = "1ce3d3fec22a59834b49c221bdd20e04ff107ee4f195735bcc543a1dd332d2d5"
CASE_SHA = "51942b84ac65efcb28d02fff359222f60b8550fe5b6d5e87389582fc5a48cfc8"
FAILURE_CODE = "VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID"
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
            fail("V50_POSTCLOSE_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load_json(path: Path) -> tuple[bytes, dict[str, Any]]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=strict_pairs)
    except SystemExit:
        raise
    except Exception as error:
        raise SystemExit("V50_POSTCLOSE_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V50_POSTCLOSE_JSON_INVALID")
    if any(marker in raw.decode("utf-8").lower() for marker in FORBIDDEN_MARKERS):
        fail("V50_POSTCLOSE_PAYLOAD_LEAK")
    return raw, value


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def load_preparation_verifier() -> Any:
    path = Path(__file__).with_name("verify_image_only_v50_diagnostic_preparation.py")
    spec = importlib.util.spec_from_file_location("v50_preparation", path)
    if spec is None or spec.loader is None:
        fail("V50_POSTCLOSE_PREPARATION_VERIFIER_MISSING")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_terminal_documents(repository: Path) -> dict[str, Any]:
    auth_path = repository / "plans/live-canary-authorizations" / (
        "20260818-image-only-v50-diagnostic-82f1d86b.json"
    )
    terminal_path = repository / "plans/image-only-profile-successor-diagnostics" / (
        f"{CYCLE_ID}-terminal.json"
    )
    live_path = repository / ".sdlc/evidence" / (
        "20260818-132607-image-only-v50-successor-diagnostic-live"
    ) / "image-only-v50-diagnostic-live-summary.json"
    auth_raw, auth = load_json(auth_path)
    terminal_raw, terminal = load_json(terminal_path)
    live_raw, live = load_json(live_path)
    if sha(auth_raw) != AUTH_SHA or sha(terminal_raw) != TERMINAL_SHA or sha(live_raw) != LIVE_SUMMARY_SHA:
        fail("V50_POSTCLOSE_DIGEST_DRIFT")
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
        "closureReason": "PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_" + FAILURE_CODE,
    }
    if any(auth.get(key) != value for key, value in expected_auth.items()):
        fail("V50_POSTCLOSE_AUTHORIZATION_DRIFT")
    if auth.get("closedAt") != terminal.get("closedAt"):
        fail("V50_POSTCLOSE_CLOSURE_TIME_DRIFT")
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
        "providerCalls": 3,
        "modelTokens": 40400,
        "costMicrosCny": 645000,
        "unsettledReservations": 0,
        "liveSummarySha256": LIVE_SUMMARY_SHA,
        "closedAuthorizationSha256": AUTH_SHA,
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
        fail("V50_POSTCLOSE_TERMINAL_DRIFT")
    breaker = terminal.get("equivalentRejectBreaker")
    if breaker != {
        "stage": "OBSERVE",
        "primaryCode": FAILURE_CODE,
        "detailCodes": [FAILURE_CODE],
        "countedEquivalentRejectedAttempts": 3,
        "attemptOrdinals": [0, 1, 2],
        "nextReservationIssued": False,
    }:
        fail("V50_POSTCLOSE_BREAKER_DRIFT")
    attempts = terminal.get("attemptFixedCodeSets")
    if attempts != [
        {"attemptOrdinal": ordinal, "codes": [FAILURE_CODE]} for ordinal in range(3)
    ]:
        fail("V50_POSTCLOSE_FIXED_CODE_SET_DRIFT")
    cases = terminal.get("cases")
    if type(cases) is not list or len(cases) != 1 or cases[0].get("artifactSha256") != CASE_SHA:
        fail("V50_POSTCLOSE_CASE_DRIFT")
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
        fail("V50_POSTCLOSE_LIVE_SUMMARY_DRIFT")
    ledger = live.get("ledger")
    if type(ledger) is not dict or any((
        ledger.get("status") != "CLOSED",
        ledger.get("startedRuns") != 1,
        ledger.get("providerCalls") != 3,
        ledger.get("exposedModelTokens") != 40400,
        ledger.get("exposedCostMicrosCny") != 645000,
        ledger.get("unsettledReservations") != 0,
    )):
        fail("V50_POSTCLOSE_LEDGER_DRIFT")
    live_cases = live.get("cases")
    if type(live_cases) is not list or len(live_cases) != 1:
        fail("V50_POSTCLOSE_LIVE_CASE_DRIFT")
    live_attempts = live_cases[0].get("attempts")
    if type(live_attempts) is not list or len(live_attempts) != 3:
        fail("V50_POSTCLOSE_LIVE_ATTEMPT_DRIFT")
    for ordinal, attempt in enumerate(live_attempts):
        if (attempt.get("attemptOrdinal") != ordinal
                or attempt.get("stage") != "OBSERVE"
                or attempt.get("status") != "REJECTED"
                or attempt.get("problemCodeCounts") != {FAILURE_CODE: 1}):
            fail("V50_POSTCLOSE_LIVE_ATTEMPT_DRIFT")
    return {
        "authorizationSha256": sha(auth_raw),
        "terminalSha256": sha(terminal_raw),
        "liveSummarySha256": sha(live_raw),
    }


def require_closed_stubs(repository: Path) -> str:
    paths = (
        "tools/run-image-only-v50-profile-successor-diagnostic-live.ps1",
        "renderweave-app/src/test/java/cn/hbads/renderweave/inference/ImageOnlyV50ProfileSuccessorDiagnosticLiveTest.java",
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
        "RENDERWEAVE_RUN_V50_PROFILE_SUCCESSOR_DIAGNOSTIC",
    )
    if any(fragment not in combined for fragment in required):
        fail("V50_POSTCLOSE_STUB_CONTRACT_MISSING")
    return "renderweave-image-only-v50-closed-harness/1.0:" + digest.hexdigest()


def verify(repository: Path, input_directory: Path) -> dict[str, Any]:
    preparation = load_preparation_verifier()
    metadata = preparation.require_exact_input(input_directory)
    identities = preparation.require_preparation(
        repository, metadata, require_prelive_outputs_absent=False
    )
    digests = require_terminal_documents(repository)
    open_count = preparation.require_no_open_authorization(repository)
    if open_count != 0:
        fail("V50_POSTCLOSE_OPEN_AUTHORIZATION")
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
        "closedHarnessIdentity": require_closed_stubs(repository),
        "openAuthorizationCount": open_count,
        "providerCalls": 3,
        "modelTokens": 40400,
        "costMicrosCny": 645000,
        "unsettledReservations": 0,
        "goalAggregateModelTokens": 107773,
        "goalModelTokenCap": 1500000,
        "goalRemainingModelTokens": 1392227,
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
        fail("V50_POSTCLOSE_SUMMARY_PAYLOAD_LEAK")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
