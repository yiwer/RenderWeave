#!/usr/bin/env python3
"""Independent strict verifier for the frozen N7 qualification protocol."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from typing import Any, Iterable

import verify_layered_evaluation as layered


VERSION = "renderweave-n7-qualification-protocol/1.0"
VERIFIER_VERSION = "renderweave-n7-qualification-protocol-verifier/1.0"
RESOURCE = pathlib.PurePosixPath(
    "renderweave-inference/src/main/resources/visual-eval/n7/qualification-protocol-v1.json")
TRIGGER_REASONS = {
    "R2": "R2_CHALLENGER_RISK_REVIEW_ABSENT",
    "R3": "R3_ATTRIBUTED_DOWNSTREAM_TOPOLOGY_EVIDENCE_ABSENT",
    "R4": "R4_STRICT_PROVIDER_PROTOCOL_EVIDENCE_ABSENT",
    "R5": "R5_ORACLE_CROP_AND_STATIC_VIEW_ATTRIBUTION_ABSENT",
}
THRESHOLDS = {
    "terminalReviewRequiredBps": 10_000, "bundleContractBps": 10_000,
    "schemaEntityF1Bps": 9_000, "fieldMicroF1Bps": 9_000,
    "supportedTypeAccuracyBps": 9_500, "parentChildEdgeF1Bps": 9_500,
    "evidenceCoverageBps": 10_000, "dagValidityBps": 10_000,
    "maximumCriticalHallucinations": 0, "maximumPayloadViolations": 0,
    "maximumIdentityViolations": 0, "maximumBudgetViolations": 0,
}


class VerificationError(Exception):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def framed_hash(values: Iterable[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def assignment_identity(version: str, case_ids: list[str]) -> str:
    return f"{version}:{framed_hash(case_ids)}"


def weakest_margin(metrics: dict[str, int]) -> int:
    return min(
        metrics["schemaEntityF1Bps"] - 9_000,
        metrics["fieldMicroF1Bps"] - 9_000,
        metrics["supportedTypeAccuracyBps"] - 9_500,
        metrics["parentChildEdgeF1Bps"] - 9_500,
    )


def passes(metrics: dict[str, int]) -> bool:
    return weakest_margin(metrics) >= 0


def route(evidence: dict[str, Any], flash_available: bool = False) -> str:
    if evidence.get("algorithmChangeRequired") is True:
        return "STOP_TO_SPEC"
    global_metrics = evidence.get("global")
    hard_metrics = evidence.get("hardNested")
    if not isinstance(global_metrics, dict) or not isinstance(hard_metrics, dict):
        fail("QUALIFICATION_METRICS_MISSING")
    integrity = all(evidence.get(key) is True for key in (
        "assignmentExact", "identityExact", "holdoutUntouched"))
    integrity = integrity and all(
        evidence.get(key) == expected for key, expected in {
            "terminalReviewRequiredBps": 10_000, "bundleContractBps": 10_000,
            "evidenceCoverageBps": 10_000, "dagValidityBps": 10_000,
            "criticalHallucinations": 0, "payloadViolations": 0,
            "identityViolations": 0, "budgetViolations": 0,
        }.items())
    if not integrity or not passes(global_metrics):
        return "NO_CHALLENGER"
    if not passes(hard_metrics):
        return "MAX_ELIGIBLE"
    return "FLASH_ELIGIBLE" if flash_available else "STOP_TO_SPEC"


def select(candidates: list[dict[str, Any]], noninferiority_bps: int = 200) -> dict[str, Any]:
    eligible = [item for item in candidates if item.get("qualified") is True]
    if not eligible:
        return {"profileId": "", "reasonCode": "NO_FINALIST"}
    best = max(weakest_margin(item["metrics"]) for item in eligible)
    band = [item for item in eligible if weakest_margin(item["metrics"]) >= best - noninferiority_bps]
    selected = min(band, key=lambda item: (
        item["estimatedCostMicrosCny"], item["p95LatencyMillis"], item["profileId"]))
    return {"profileId": selected["profileId"], "reasonCode": "FINALIST_SELECTED"}


def verify_document(document: dict[str, Any], raw: bytes, repository: pathlib.Path) -> dict[str, Any]:
    if document.get("protocolVersion") != VERSION:
        fail("PROTOCOL_VERSION_INVALID")
    anchor = document.get("evidenceAnchor")
    expected_anchor = {
        "baseRevision": "b50d04e710f3a176b5e95336f912460809939d89",
        "implementationRevision": "2f3ad8aff29f53cb79f1b546f323a2966fbf489c",
        "shadowEvaluationIdentity": "renderweave-rapidocr-shadow-evaluation/1.0:91af6d7d79b2c3bc7d8b5446c79d6b4874e16e1cb28b7fffb3fcc3b7a3b25e3a",
        "shadowReportIdentity": "renderweave-rapidocr-shadow-report/1.0:fc2cc3523ba59e9832ba8eb6fa651fd2fac9088751a4b9b72c7fa4bab476f8a5",
        "shadowAggregateIdentity": "renderweave-rapidocr-shadow-recomputed-aggregates/1.0:8f23fc9ddc7825f5cb4479352bd4dbdf8e013f9f73124a16306b31bb2cb15635",
    }
    if anchor != expected_anchor or document.get("continuationCode") != "CONTINUE_N7_CURRENT_BEHAVIOR":
        fail("EVIDENCE_ANCHOR_OR_CONTINUATION_INVALID")
    triggers = document.get("triggerDispositions")
    if not isinstance(triggers, dict) or set(triggers) != set(TRIGGER_REASONS):
        fail("TRIGGER_SET_INVALID")
    for key, reason in TRIGGER_REASONS.items():
        item = triggers[key]
        if item.get("status") != "NOT_TRIGGERED" or item.get("reasonCode") != reason \
                or not str(item.get("factCode", "")).isupper():
            fail("TRIGGER_DISPOSITION_INVALID")

    lock = layered.verify_corpus_lock(repository)
    cases = lock["cases"]
    by_id = {item["caseId"]: item for item in cases}
    canary = document.get("canaryCaseIds")
    qualification = document.get("qualificationCaseIds")
    hard = document.get("hardNestedCaseIds")
    if not isinstance(canary, list) or len(canary) != 5 or len(set(canary)) != 5 \
            or not isinstance(qualification, list) or len(qualification) != 20 \
            or len(set(qualification)) != 20 or not set(canary) <= set(qualification) \
            or not isinstance(hard, list) or len(hard) != 12 or not set(hard) <= set(qualification):
        fail("QUALIFICATION_ASSIGNMENTS_INVALID")
    if any(case_id not in by_id or by_id[case_id]["partition"] != "DEV" for case_id in qualification):
        fail("QUALIFICATION_HOLDOUT_EXPOSED")
    if any(by_id[case_id]["difficulty"] not in ("MULTI_COLUMN", "DENSE_TEXT")
           and "PROMPT_INJECTION" not in by_id[case_id]["failureSlices"] for case_id in hard):
        fail("HARD_NESTED_ASSIGNMENT_INVALID")
    if document.get("thresholds") != THRESHOLDS or document.get("nonInferiorityBps") != 200:
        fail("THRESHOLDS_INVALID")

    final_ids = document.get("finalCaseIds")
    final_holdout = document.get("finalHoldoutCaseIds")
    expected_ids = [item["caseId"] for item in cases]
    expected_holdout = [item["caseId"] for item in cases if item["partition"] == "HOLDOUT"]
    if document.get("finalCorpusVersion") != "renderweave-visual-stage-corpus/1.0" \
            or document.get("finalCorpusSourceSha256") != "ca53d88763af161a1b1b22fa50774c56eae929affe5316157ae355fdb005b8b3" \
            or final_ids != expected_ids or final_holdout != expected_holdout:
        fail("FINAL_CORPUS_AUTHORITY_INVALID")

    profiles = document.get("profiles")
    expected_profiles = {
        "PLUS": ("dashscope-qwen37-plus-product-v45-hybrid-generic", "qwen3.7-plus", True,
                 "EXACT_PRODUCT_V45_PROFILE_AVAILABLE"),
        "MAX": ("dashscope-qwen38-max-product-v45-hybrid-generic", "qwen3.8-max", True,
                "EXACT_PRODUCT_V45_PROFILE_AVAILABLE"),
        "FLASH": ("UNRESOLVED_PINNED_PRODUCT_V45_PROFILE", "qwen3.7-flash-2026-07-15", False,
                  "PINNED_PRODUCT_V45_PROFILE_ABSENT"),
    }
    if not isinstance(profiles, dict) or set(profiles) != set(expected_profiles):
        fail("PROFILE_SET_INVALID")
    profile_hashes: dict[str, str] = {}
    for key, expected in expected_profiles.items():
        item = profiles[key]
        if (item.get("profileId"), item.get("model"), item.get("available"), item.get("reasonCode")) != expected:
            fail("PROFILE_BINDING_INVALID")
        if item["available"]:
            path = repository / "renderweave-inference/src/main/resources/inference-profiles" \
                   / f"{item['profileId']}.json"
            profile = layered.parse_strict_json(path.read_text(encoding="utf-8"))
            expected_contract = {
                "model": item["model"], "pipelineVersion": "renderweave-inference-pipeline/4.28",
                "elementPromptVersion": "renderweave-visual-elements-prompt/12.0",
                "hierarchyPromptVersion": "renderweave-visual-hierarchy-prompt/7.0",
                "bindingPromptVersion": "renderweave-visual-bindings-prompt/4.0",
                "certification": "EXPERIMENTAL",
            }
            if any(profile.get(field) != value for field, value in expected_contract.items()):
                fail("PROFILE_CONTRACT_DRIFT")
            profile_hashes[key] = hashlib.sha256(layered.canonical_json(profile)).hexdigest()
    product_v45 = list((repository / "renderweave-inference/src/main/resources/inference-profiles")
                      .glob("*product-v45*.json"))
    if any(layered.parse_strict_json(path.read_text(encoding="utf-8")).get("model")
           == "qwen3.7-flash-2026-07-15" for path in product_v45):
        fail("FLASH_AUTHORITY_CHANGED")

    evidence_mapping = document.get("evidenceMapping")
    expected_ac = {f"AC-VR-{index:03d}" for index in range(1, 11)} | {"AC-021"}
    if not isinstance(evidence_mapping, dict) or set(evidence_mapping) != expected_ac:
        fail("EVIDENCE_MAPPING_INVALID")
    architecture = document.get("architecture")
    if architecture != {
        "orchestration": "EXISTING_POSTGRESQL_DURABLE_TYPED_STATE_MACHINE",
        "semanticStages": "SERIAL", "localRepair": "VALIDATOR_DRIVEN_BOUNDED",
        "openEndedAgent": False, "generalToolExecutor": False,
        "langGraph": False, "temporal": False,
    }:
        fail("ARCHITECTURE_BOUNDARY_INVALID")
    return {
        "verifierVersion": VERIFIER_VERSION, "result": "PASS",
        "assurance": "A2_STRICT_INPUT_REPLAY",
        "protocolIdentity": f"{VERSION}:{hashlib.sha256(raw).hexdigest()}",
        "canaryAssignmentIdentity": assignment_identity(
            "renderweave-n7-canary-assignment/1.0", canary),
        "qualificationAssignmentIdentity": assignment_identity(
            "renderweave-n7-qualification-assignment/1.0", qualification),
        "finalAssignmentIdentity": assignment_identity(
            "renderweave-n7-final-assignment/1.0", final_ids),
        "canaryCases": 5, "qualificationCases": 20, "finalCases": 60,
        "finalDevCases": 45, "finalHoldoutCases": 15,
        "triggerReasons": TRIGGER_REASONS, "continuationCode": document["continuationCode"],
        "flashProfileStatus": "PINNED_PRODUCT_V45_PROFILE_ABSENT",
        "profileSnapshotSha256": profile_hashes,
        "providerAttempts": 0, "providerReservations": 0, "externalProviderCostMicrosCny": 0,
    }


def verify_protocol(repository: pathlib.Path) -> dict[str, Any]:
    path = repository.joinpath(*RESOURCE.parts).resolve(strict=True)
    raw = path.read_bytes()
    document = layered.parse_strict_json(raw.decode("utf-8", errors="strict"))
    if not isinstance(document, dict):
        fail("PROTOCOL_NOT_OBJECT")
    return verify_document(document, raw, repository)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        summary = verify_protocol(args.repository.resolve(strict=True))
        encoded = json.dumps(summary, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
        if args.output:
            args.output.write_text(encoded, encoding="utf-8", newline="\n")
        else:
            sys.stdout.write(encoded)
        return 0
    except (VerificationError, layered.VerificationError, OSError, UnicodeError, ValueError) as failure:
        sys.stderr.write(f"N7 qualification protocol verification failed: {failure}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
