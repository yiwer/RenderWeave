#!/usr/bin/env python3
"""Independent, payload-free verifier for v49 bounded mixed-field correction."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v49-correction-provider-zero/1.0"
MIXED_PRIMARY = "VISUAL_GROUNDING_REGION_FIELDS_INVALID"
UNCLASSIFIED_PRIMARY = "VISUAL_GROUNDING_REGION_UNCLASSIFIED"
PROMPT_COVERED_DETAILS = [
    "VISUAL_GROUNDING_REGION_ENTRY_INVALID",
    "VISUAL_GROUNDING_REGION_ID_INVALID",
    "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
    "VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID",
    "VISUAL_GROUNDING_REGION_READING_ORDER_INVALID",
    "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID",
    "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID",
]
BREAKER_THRESHOLD = 3
V48_PROFILE_SHA = "22f40ef4c865e11778eef4558c20c383e6611e068d8d08be0d080650074d4470"
FORBIDDEN_SUMMARY_MARKERS = (
    "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
    "modeloutput", "candidatejson", "rootdocument", "chain-of-thought",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def canonical_detail_key(primary: str, stage: str, details: list[str]) -> tuple[str, ...]:
    if primary != MIXED_PRIMARY or stage != "OBSERVE" or type(details) is not list:
        fail("V49_CORRECTION_ENVELOPE_NOT_ELIGIBLE")
    if any(type(code) is not str for code in details):
        fail("V49_CORRECTION_DETAIL_TYPE_INVALID")
    canonical = [code for code in PROMPT_COVERED_DETAILS if code in details]
    if len(details) < 2 or details != canonical:
        fail("V49_CORRECTION_DETAIL_SET_NOT_PROMPT_COVERED")
    return tuple(details)


def breaker_reached(previous_equivalent_rejections: int) -> bool:
    if type(previous_equivalent_rejections) is not int or previous_equivalent_rejections < 0:
        fail("V49_CORRECTION_BREAKER_COUNT_INVALID")
    return previous_equivalent_rejections + 1 >= BREAKER_THRESHOLD


def read_required(repository: Path, relative: str) -> str:
    try:
        return (repository / relative).read_text(encoding="utf-8")
    except OSError as error:
        raise SystemExit("V49_CORRECTION_REQUIRED_SOURCE_MISSING") from error


def require_fragments(source: str, fragments: tuple[str, ...], code: str) -> None:
    if any(fragment not in source for fragment in fragments):
        fail(code)


def canonical_profile_sha(path: Path) -> str:
    value = json.loads(path.read_text(encoding="utf-8"))
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def open_authorization_count(repository: Path) -> int:
    count = 0
    for path in (repository / "plans/live-canary-authorizations").glob("20*.json"):
        if json.loads(path.read_text(encoding="utf-8")).get("status") == "OPEN":
            count += 1
    return count


def verify_sources(repository: Path) -> str:
    paths = {
        "policy": "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualObservationCorrectionPolicy.java",
        "worker": "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/LiveInferenceWorker.java",
        "envelope": "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/replay/InferenceRejectionEnvelope.java",
        "tests": "renderweave-app/src/test/java/cn/hbads/renderweave/inference/PostgresLiveInferenceWorkflowTest.java",
    }
    sources = {name: read_required(repository, path) for name, path in paths.items()}
    require_fragments(sources["policy"], (
        '"renderweave-inference-pipeline/4.31"', "MIXED_RETRYABLE_DETAIL_CODES",
        "rejectionEnvelope.filter", "mixedDetailsPromptCovered(envelope.detailCodes())",
        "MIXED_RETRYABLE_DETAIL_CODES.containsAll(detailCodes)",
        "detailCodes.equals(InferenceRejectionEnvelope.REGION_DETAIL_CODES.stream()",
        "UNCLASSIFIED_REGION_PRIMARY_CODE", "return false",
    ), "V49_CORRECTION_POLICY_CONTRACT_MISSING")
    for code in PROMPT_COVERED_DETAILS:
        if code not in sources["policy"]:
            fail("V49_CORRECTION_PROMPT_ALLOWLIST_DRIFT")
    require_fragments(sources["worker"], (
        "MAX_EQUIVALENT_REJECTED_ATTEMPTS = 3", "accumulatedRetryProblemCodes",
        "problemCodeCounts().keySet()", "workflowStore.recordAttempt",
        "equivalentRejectedAttemptCount", "attempt.rejectionEnvelope()",
        "filter(envelope::equals).isPresent()", "previous + 1 >=",
        ">= MAX_EQUIVALENT_REJECTED_ATTEMPTS", "persistedTerminalRejection",
    ), "V49_CORRECTION_WORKER_CONTRACT_MISSING")
    require_fragments(sources["envelope"], (
        MIXED_PRIMARY, UNCLASSIFIED_PRIMARY, "detailCodeCounts",
    ), "V49_CORRECTION_ENVELOPE_CONTRACT_MISSING")
    require_fragments(sources["tests"], (
        "pipelineFourPointThirtyOneCorrectsPromptCoveredMixedFieldsToReviewRequired",
        "pipelineFourPointThirtyOneBreaksBeforeTheFourthEquivalentMixedSet",
        "changingMixedSetsRemainIsolatedButCannotBypassTheTotalCallCap",
        "expiredLeaseAfterThirdEquivalentMixedSetStopsBeforeAnotherProviderPermit",
        "postgresRoundTripsUnclassifiedEnvelopeBeforePrimaryOnlyTerminal",
        "pipelineFourPointThirtyStopsAfterTheThirdAllowlistedEquivalentRejectedAttempt",
        "equivalentRejectCountsAreIsolatedByCodeAndStage",
    ), "V49_CORRECTION_TRACERS_MISSING")

    digest = hashlib.sha256()
    for name in sorted(paths):
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(sources[name].encode("utf-8"))
        digest.update(b"\0")
    return "renderweave-image-only-v49-correction-implementation/1.0:" + digest.hexdigest()


def verify(repository: Path) -> dict[str, Any]:
    implementation_identity = verify_sources(repository)
    v48_path = repository / (
        "renderweave-inference/src/main/resources/inference-profiles/"
        "dashscope-qwen38-max-product-v48-hybrid-generic.json"
    )
    if canonical_profile_sha(v48_path) != V48_PROFILE_SHA:
        fail("V49_CORRECTION_V48_PROFILE_DRIFT")
    v49_profile = repository / (
        "renderweave-inference/src/main/resources/inference-profiles/"
        "dashscope-qwen38-max-product-v49-hybrid-generic.json"
    )
    v49_prompt = repository / "renderweave-inference/src/main/resources/inference-prompts/visual-elements-v15.txt"
    open_count = open_authorization_count(repository)
    if v49_profile.exists() or v49_prompt.exists() or open_count != 0:
        fail("V49_CORRECTION_PROVIDER_ZERO_BOUNDARY_VIOLATED")
    canonical_detail_key(MIXED_PRIMARY, "OBSERVE", PROMPT_COVERED_DETAILS[:2])
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "PROVIDER_ZERO_CORRECTION",
        "implementationIdentity": implementation_identity,
        "eligiblePrimaryCode": MIXED_PRIMARY,
        "promptCoveredDetailCodes": PROMPT_COVERED_DETAILS,
        "canonicalBreakerThreshold": BREAKER_THRESHOLD,
        "unclassifiedRetryable": False,
        "v48ProfileSha256": V48_PROFILE_SHA,
        "v49ProfileCreated": False,
        "v49PromptCreated": False,
        "openAuthorizationCount": open_count,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "payloadFree": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = verify(args.repository.resolve())
    encoded = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if any(marker in encoded.lower() for marker in FORBIDDEN_SUMMARY_MARKERS):
        fail("V49_CORRECTION_SUMMARY_NOT_PAYLOAD_FREE")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
