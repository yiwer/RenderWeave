#!/usr/bin/env python3
"""Independent payload-free replay of the immutable IMAGE_ONLY v52 diagnostic terminal."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v52-diagnostic-postclose/1.0"
CYCLE_ID = "981d7262-d802-45bb-96ce-d34b4468f9f9"
AUTHORIZATION_ID = "20260818-iopa-v52-diagnostic-981d7262"
PROFILE_ID = "dashscope-qwen38-max-product-v52-hybrid-generic"
PROFILE_SHA = "d8014b605dfa01a5aa1e6062696c61eb896da9e146b2a6ab3c5dae3ca9957332"
NORMALIZATION_IDENTITY = (
    "renderweave-image-only-fresh-normalization/1.0:"
    "e0e505c515ff3c7c7bac57e0ddc19e714721e301fd2216830bc6ac82f98cae35"
)
MANIFEST_IDENTITY = (
    "renderweave-image-only-profile-successor-diagnostic/1.0:"
    "4a81d0718abb9b8db3e95052a4c268767c9ac01ce9ed90f117894dc1aed63d20"
)
EVALUATOR_IDENTITY = (
    "renderweave-image-only-profile-successor-diagnostic-evaluator/1.0:"
    "b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e"
)
PREPARATION_AUTHORITY_IDENTITY = (
    "renderweave-image-only-v52-diagnostic-authority/1.0:"
    "d46775b3998d910e9ad524a36753b5f93e8707c768bdb655fc1e69abd6f87473"
)
PREPARATION_SUMMARY_SHA = "f670651c0d0662dcd00349566d26fe6c07dc9cd74d726a24a50ec0a6ef4b3e6d"
AUTH_SHA = "5556553bd21008427561b8a3fed8465f68b3f545b58086796920a5585ff6f4c6"
TERMINAL_SHA = "c0a1863086ed1380b7a2ef6afc47f872ff90d19f5250bfa0b3539bb679cc5449"
LIVE_SUMMARY_SHA = "6bbea94002315f7c1283ee385aa705a6b4e188c3c8ade98bc74db066d9fb5eea"
REVIEW_PACK_SHA = "fe61bf267049a6fed74daad41d4ebeaca30012b19e2806a10be0e53e4f29ccdc"
CASE_ID = "v46-failed-route-82"
CASE_SHA = "51942b84ac65efcb28d02fff359222f60b8550fe5b6d5e87389582fc5a48cfc8"
RUN_ID = "80d2aca8-ee47-468a-b346-21caa97f78c2"
ENVELOPE_CODE = "VISUAL_GROUNDING_REPEATED_GROUP_ENVELOPE_NORMALIZED"
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
            fail("V52_POSTCLOSE_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load_json(path: Path, *, payload_free: bool = True) -> tuple[bytes, dict[str, Any]]:
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    try:
        value = json.loads(text, object_pairs_hook=strict_pairs)
    except SystemExit:
        raise
    except Exception as error:
        raise SystemExit("V52_POSTCLOSE_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V52_POSTCLOSE_JSON_INVALID")
    if payload_free and any(marker in text.lower() for marker in FORBIDDEN_MARKERS):
        fail("V52_POSTCLOSE_PAYLOAD_LEAK")
    return raw, value


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def load_preparation_verifier() -> Any:
    path = Path(__file__).with_name("verify_image_only_v52_diagnostic_preparation.py")
    spec = importlib.util.spec_from_file_location("v52_preparation", path)
    if spec is None or spec.loader is None:
        fail("V52_POSTCLOSE_PREPARATION_VERIFIER_MISSING")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_preparation_summary(repository: Path) -> str:
    path = repository / ".sdlc/evidence" / (
        "20260818-152744-image-only-v52-diagnostic-preparation"
    ) / "image-only-v52-diagnostic-preparation-summary.json"
    raw, value = load_json(path)
    if sha(raw) != PREPARATION_SUMMARY_SHA:
        fail("V52_POSTCLOSE_PREPARATION_SUMMARY_DRIFT")
    expected = {
        "result": "PASS",
        "authorizationStatus": "PENDING_J1",
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "cycleId": CYCLE_ID,
        "authorizationId": AUTHORIZATION_ID,
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "manifestIdentity": MANIFEST_IDENTITY,
        "evaluatorIdentity": EVALUATOR_IDENTITY,
        "authorityImplementationIdentity": PREPARATION_AUTHORITY_IDENTITY,
        "openAuthorizationCount": 0,
        "goalModelTokenCap": 1_500_000,
        "goalAggregateModelTokensBefore": 121_618,
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "productionDeploymentAllowed": False,
        "payloadFree": True,
    }
    if any(value.get(key) != expected_value for key, expected_value in expected.items()):
        fail("V52_POSTCLOSE_PREPARATION_SUMMARY_FACT_DRIFT")
    return sha(raw)


def require_review_pack(repository: Path) -> str:
    review_directory = (
        repository / ".scratch/image-only-profile-successor-diagnostic-reviews"
        / AUTHORIZATION_ID
    )
    files = sorted(path for path in review_directory.iterdir() if path.is_file())
    expected_path = review_directory / f"{CASE_ID}.json"
    if files != [expected_path]:
        fail("V52_POSTCLOSE_REVIEW_PACK_SET_DRIFT")
    raw, value = load_json(expected_path, payload_free=False)
    if sha(raw) != REVIEW_PACK_SHA:
        fail("V52_POSTCLOSE_REVIEW_PACK_DIGEST_DRIFT")
    expected = {
        "version": "renderweave-image-only-profile-successor-diagnostic-review/1.0",
        "authorizationId": AUTHORIZATION_ID,
        "cycleId": CYCLE_ID,
        "profileId": PROFILE_ID,
        "caseId": CASE_ID,
        "artifactSha256": CASE_SHA,
        "runId": RUN_ID,
        "candidateRevision": 0,
        "manualVerdict": "PENDING_OWNER_REVIEW",
        "certificationCredit": 0,
        "applyAllowed": False,
    }
    if any(value.get(key) != expected_value for key, expected_value in expected.items()):
        fail("V52_POSTCLOSE_REVIEW_PACK_FACT_DRIFT")
    if type(value.get("candidate")) is not dict or type(value.get("problems")) is not list:
        fail("V52_POSTCLOSE_REVIEW_PACK_SHAPE_DRIFT")
    return sha(raw)


def require_terminal_documents(repository: Path) -> dict[str, Any]:
    auth_path = repository / "plans/live-canary-authorizations" / (
        "20260818-image-only-v52-diagnostic-981d7262.json"
    )
    terminal_path = repository / "plans/image-only-profile-successor-diagnostics" / (
        f"{CYCLE_ID}-terminal.json"
    )
    live_path = repository / ".sdlc/evidence" / (
        "20260818-152950-image-only-v52-successor-diagnostic-live"
    ) / "image-only-v52-diagnostic-live-summary.json"
    auth_raw, auth = load_json(auth_path)
    terminal_raw, terminal = load_json(terminal_path)
    live_raw, live = load_json(live_path)
    if (sha(auth_raw) != AUTH_SHA or sha(terminal_raw) != TERMINAL_SHA
            or sha(live_raw) != LIVE_SUMMARY_SHA):
        fail("V52_POSTCLOSE_DIGEST_DRIFT")

    expected_auth = {
        "authorizationId": AUTHORIZATION_ID,
        "status": "CLOSED",
        "cycleId": CYCLE_ID,
        "stage": "PROFILE_SUCCESSOR_DIAGNOSTIC_1",
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "manifestIdentity": MANIFEST_IDENTITY,
        "evaluatorIdentity": EVALUATOR_IDENTITY,
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "provider": "DASHSCOPE",
        "model": "qwen3.8-max",
        "inputProvenance": "USER_PROVIDED",
        "dataClassification": "ORDINARY_DESIGN",
        "maximumRuns": 1,
        "maximumProviderCalls": 5,
        "maximumModelTokens": 100_000,
        "maximumCostMicrosCny": 3_000_000,
        "maximumProviderCallsPerRun": 5,
        "maximumCostPerRunMicrosCny": 3_000_000,
        "closedAt": "2026-08-18T07:31:08.255921Z",
        "closureReason": "PROFILE_SUCCESSOR_DIAGNOSTIC_REVIEW_REQUIRED_OWNER_REVIEW_PENDING",
    }
    if any(auth.get(key) != value for key, value in expected_auth.items()):
        fail("V52_POSTCLOSE_AUTHORIZATION_DRIFT")
    if auth.get("cases") != [{"caseId": CASE_ID, "artifactSha256": CASE_SHA}]:
        fail("V52_POSTCLOSE_AUTHORIZATION_CASE_DRIFT")

    expected_terminal = {
        "cycleId": CYCLE_ID,
        "authorizationId": AUTHORIZATION_ID,
        "lifecycle": "TERMINAL_CLOSED",
        "result": "REVIEW_REQUIRED",
        "terminalReason": "PROFILE_SUCCESSOR_DIAGNOSTIC_REVIEW_REQUIRED",
        "manualReviewStatus": "PENDING_OWNER_REVIEW",
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "manifestIdentity": MANIFEST_IDENTITY,
        "evaluatorIdentity": EVALUATOR_IDENTITY,
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "caseId": CASE_ID,
        "caseSha256": CASE_SHA,
        "runId": RUN_ID,
        "providerCalls": 3,
        "modelTokens": 37_451,
        "costMicrosCny": 521_364,
        "unsettledReservations": 0,
        "itemParentEnvelopeNormalized": 1,
        "reviewPackPresent": True,
        "reviewPackSha256": REVIEW_PACK_SHA,
        "certificationCredit": 0,
        "nextStageUnlocked": False,
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "productionDeployed": False,
        "goalAggregateModelTokens": 159_069,
        "goalModelTokenCap": 1_500_000,
        "closedAt": auth["closedAt"],
    }
    if any(terminal.get(key) != value for key, value in expected_terminal.items()):
        fail("V52_POSTCLOSE_TERMINAL_DRIFT")

    expected_live = {
        "authorizationId": AUTHORIZATION_ID,
        "cycleId": CYCLE_ID,
        "stage": "PROFILE_SUCCESSOR_DIAGNOSTIC_1",
        "profileId": PROFILE_ID,
        "profileSha256": PROFILE_SHA,
        "manifestIdentity": MANIFEST_IDENTITY,
        "evaluatorIdentity": EVALUATOR_IDENTITY,
        "normalizationIdentity": NORMALIZATION_IDENTITY,
        "inputProvenance": "USER_PROVIDED",
        "dataClassification": "ORDINARY_DESIGN",
        "lifecycle": "PROVIDER_RUN_CLOSED_REVIEW_PENDING",
        "manualReviewStatus": "PENDING_OWNER_REVIEW",
        "harnessFailureCode": None,
        "certificationCredit": 0,
        "nextStageUnlocked": False,
        "candidateApplied": False,
        "staticSchemaPublished": False,
    }
    if any(live.get(key) != value for key, value in expected_live.items()):
        fail("V52_POSTCLOSE_LIVE_SUMMARY_DRIFT")
    live_case = live.get("diagnosticCase")
    if type(live_case) is not dict:
        fail("V52_POSTCLOSE_LIVE_CASE_DRIFT")
    expected_case = {
        "caseId": CASE_ID,
        "artifactSha256": CASE_SHA,
        "runId": RUN_ID,
        "state": "REVIEW_REQUIRED",
        "failureCode": None,
        "reviewRequired": True,
        "candidateSchemas": 2,
        "candidateFields": 8,
        "blockerProblems": 10,
        "warningProblems": 0,
    }
    if any(live_case.get(key) != value for key, value in expected_case.items()):
        fail("V52_POSTCLOSE_LIVE_CASE_DRIFT")
    attempts = live_case.get("attempts")
    expected_attempts = (
        (0, "OBSERVE", "LIVE_VISUAL_GROUNDING_ACCEPTED", 11_235, 2_447, 222_912,
         {"VISUAL_GROUNDING_LOCAL_ID_CLASSES_CANONICALIZED": 14, ENVELOPE_CODE: 1}),
        (1, "HIERARCHY", "LIVE_VISUAL_HIERARCHY_V2_ACCEPTED", 12_092, 332, 157_056,
         {"VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED": 1}),
        (2, "ELEMENT_BINDING", "LIVE_VISUAL_BINDINGS_V2_ACCEPTED", 11_126, 219, 141_396, {}),
    )
    if type(attempts) is not list or len(attempts) != len(expected_attempts):
        fail("V52_POSTCLOSE_LIVE_ATTEMPT_DRIFT")
    for attempt, expected in zip(attempts, expected_attempts, strict=True):
        ordinal, stage, outcome, input_tokens, output_tokens, cost, codes = expected
        if any((
            attempt.get("attemptOrdinal") != ordinal,
            attempt.get("stage") != stage,
            attempt.get("status") != "SUCCEEDED",
            attempt.get("outcomeCode") != outcome,
            attempt.get("providerModel") != "qwen3.8-max",
            attempt.get("inputTokens") != input_tokens,
            attempt.get("outputTokens") != output_tokens,
            attempt.get("costMicrosCny") != cost,
            attempt.get("problemCodeCounts") != codes,
        )):
            fail("V52_POSTCLOSE_LIVE_ATTEMPT_DRIFT")
    ledger = live.get("ledger")
    if type(ledger) is not dict or any((
        ledger.get("status") != "CLOSED",
        ledger.get("startedRuns") != 1,
        ledger.get("providerCalls") != 3,
        ledger.get("exposedModelTokens") != 37_451,
        ledger.get("exposedCostMicrosCny") != 521_364,
        ledger.get("unsettledReservations") != 0,
        ledger.get("closureReason") != "PROFILE_SUCCESSOR_DIAGNOSTIC_PROVIDER_RUN_COMPLETED",
    )):
        fail("V52_POSTCLOSE_LEDGER_DRIFT")
    return {
        "authorizationSha256": sha(auth_raw),
        "terminalSha256": sha(terminal_raw),
        "liveSummarySha256": sha(live_raw),
        "reviewPackSha256": require_review_pack(repository),
    }


def require_closed_stubs(repository: Path) -> str:
    paths = (
        "tools/run-image-only-v52-profile-successor-diagnostic-live.ps1",
        "renderweave-app/src/test/java/cn/hbads/renderweave/inference/ImageOnlyV52ProfileSuccessorDiagnosticLiveTest.java",
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
        "RENDERWEAVE_RUN_V52_PROFILE_SUCCESSOR_DIAGNOSTIC",
        "PROFILE_SUCCESSOR_DIAGNOSTIC_REVIEW_REQUIRED_OWNER_REVIEW_PENDING",
    )
    if any(fragment not in combined for fragment in required):
        fail("V52_POSTCLOSE_STUB_CONTRACT_MISSING")
    return "renderweave-image-only-v52-closed-harness/1.0:" + digest.hexdigest()


def verify(repository: Path, input_directory: Path) -> dict[str, Any]:
    preparation = load_preparation_verifier()
    metadata = preparation.require_exact_input(input_directory)
    identities = preparation.require_preparation(
        repository, metadata, require_prelive_outputs_absent=False
    )
    preparation_sha = require_preparation_summary(repository)
    digests = require_terminal_documents(repository)
    open_count = preparation.require_no_open_authorization(repository)
    if open_count != 0:
        fail("V52_POSTCLOSE_OPEN_AUTHORIZATION")
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
        "preparationSummarySha256": preparation_sha,
        "preparationAuthorityIdentity": PREPARATION_AUTHORITY_IDENTITY,
        "diagnosticResult": "REVIEW_REQUIRED",
        "manualReviewStatus": "PENDING_OWNER_REVIEW",
        "itemParentEnvelopeNormalized": 1,
        "closedHarnessIdentity": require_closed_stubs(repository),
        "openAuthorizationCount": open_count,
        "providerCalls": 3,
        "modelTokens": 37_451,
        "costMicrosCny": 521_364,
        "unsettledReservations": 0,
        "goalAggregateModelTokens": 159_069,
        "goalModelTokenCap": 1_500_000,
        "goalRemainingModelTokens": 1_340_931,
        "postcloseProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
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
        fail("V52_POSTCLOSE_SUMMARY_PAYLOAD_LEAK")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
