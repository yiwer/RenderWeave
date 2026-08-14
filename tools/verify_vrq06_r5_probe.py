#!/usr/bin/env python3
"""Independent payload-safe verifier for the VRQ-06 R5 oracle differential."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from typing import Any


EVIDENCE_VERSION = "renderweave-r5-oracle-probe/1.0"
IDENTITY_VERSION = "renderweave-r5-oracle-probe-evidence/1.0"
ENVELOPE_VERSION = "renderweave-r5-oracle-probe-envelope/1.0"
PROTOCOL_VERSION = "renderweave-offline-quality-evaluation-protocol/1.0"
ASSIGNMENT_VERSION = "renderweave-r5-probe-assignment/1.0"
TRANSFORM_VERSION = "renderweave-r5-oracle-higher-resolution/1.0"
EVALUATION_VERSION = "renderweave-r5-oracle-evaluation/1.0"
RUNNER_VERSION = "renderweave-r5-oracle-probe-runner/1.0"
EXPECTED_POLICY = "AcquisitionPolicy/1.0:32ade47685c07163e10f77be8b8ed46e420af7b7d381e1363d30886a19e26c52"
EXPECTED_CAPABILITY = "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"
EXPECTED_ANNOTATION = "renderweave-layered-annotation-set/2.0:a6f7796d0433bb59779a3e1b99fa3c20b3e49148d24eb69dfe17682414fa746a"
FORBIDDEN = (
    b"base64", b"data:image", b"ocrtext", b"ocr_text", b"prompttext", b"modeloutput",
    b"candidatejson", b"rootdocument", b"boundingbox", b'"bbox"', b"inspectionrequest",
)


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("R5_A2_DUPLICATE_MEMBER")
        result[key] = value
    return result


def strict_json(raw: bytes) -> Any:
    try:
        text = raw.decode("utf-8", errors="strict")
        value, end = json.JSONDecoder(object_pairs_hook=unique_object).raw_decode(text)
        if text[end:].strip():
            fail("R5_A2_TRAILING_JSON")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError("R5_A2_JSON_INVALID") from error


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def framed_hash(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        raw = value.encode("utf-8")
        digest.update(str(len(raw)).encode("ascii"))
        digest.update(b":")
        digest.update(raw)
        digest.update(b"\n")
    return digest.hexdigest()


def load_protocol(repository: pathlib.Path) -> tuple[dict[str, Any], str]:
    path = repository / (
        "renderweave-inference/src/main/resources/visual-eval/quality-repair/"
        "offline-evaluation-protocol-v1.json"
    )
    raw = path.read_bytes()
    if b"\r" in raw.replace(b"\r\n", b""):
        fail("R5_A2_PROTOCOL_LINE_ENDING_INVALID")
    normalized = raw.replace(b"\r\n", b"\n")
    return strict_json(raw), f"{PROTOCOL_VERSION}:{hashlib.sha256(normalized).hexdigest()}"


def oracle_dimensions(width: int, height: int) -> tuple[int, int]:
    if width * 2 <= 2400 and height * 2 <= 2400:
        return width * 2, height * 2
    if width >= height:
        return 2400, height * 2400 // width
    return width * 2400 // height, 2400


def verify_metrics(value: dict[str, Any]) -> None:
    expected = {
        "observationCount", "expectedLines", "matchedLines", "characterErrors", "hallucinationCases",
        "expectedPrecedenceEdges", "comparablePrecedenceEdges", "correctPrecedenceEdges",
        "expectedRepeatMemberships", "observableRepeatMemberships",
    }
    if set(value) != expected or any(not isinstance(item, int) or item < 0 for item in value.values()):
        fail("R5_A2_CASE_METRICS_INVALID")
    if value["matchedLines"] > value["expectedLines"] \
            or value["correctPrecedenceEdges"] > value["comparablePrecedenceEdges"] \
            or value["comparablePrecedenceEdges"] > value["expectedPrecedenceEdges"] \
            or value["observableRepeatMemberships"] > value["expectedRepeatMemberships"]:
        fail("R5_A2_CASE_METRICS_INVALID")


def verify(evidence_path: pathlib.Path, repository: pathlib.Path) -> dict[str, Any]:
    raw = evidence_path.read_bytes()
    if not raw or len(raw) > 1024 * 1024 or any(marker in raw.lower() for marker in FORBIDDEN):
        fail("R5_A2_EVIDENCE_BYTES_INVALID")
    envelope = strict_json(raw)
    if set(envelope) != {"envelopeVersion", "evidenceIdentity", "evidence"} \
            or envelope["envelopeVersion"] != ENVELOPE_VERSION:
        fail("R5_A2_ENVELOPE_INVALID")
    evidence = envelope["evidence"]
    identity = f"{IDENTITY_VERSION}:{hashlib.sha256(canonical_json(evidence)).hexdigest()}"
    if envelope["evidenceIdentity"] != identity or evidence["contractVersion"] != EVIDENCE_VERSION:
        fail("R5_A2_IDENTITY_DRIFT")

    protocol_doc, protocol_identity = load_protocol(repository)
    case_ids = protocol_doc["r5ProbeCaseIds"]
    assignment_identity = f"{ASSIGNMENT_VERSION}:{framed_hash([protocol_identity, chr(10).join(case_ids)])}"
    transform_identity = f"{TRANSFORM_VERSION}:{framed_hash([
        TRANSFORM_VERSION,
        'repository-scene-vector-rerender/1.0',
        'maximum-scale=2x',
        'maximum-dimension=2400',
        'aspect-ratio=floor-limited-dimension/1.0',
        'product-inspection-request=absent',
    ])}"
    corpus_identity = protocol_doc["corpusIdentity"]
    expected_evaluation = f"{EVALUATION_VERSION}:{framed_hash([
        RUNNER_VERSION, protocol_identity, assignment_identity, corpus_identity, EXPECTED_ANNOTATION,
        EXPECTED_POLICY, EXPECTED_CAPABILITY, transform_identity,
        'two-isolated-baseline-and-oracle-runs/1.0',
        'provider-attempts-reservations-cost-zero/1.0',
    ])}"
    expected_identities = {
        "protocolIdentity": protocol_identity,
        "assignmentIdentity": assignment_identity,
        "transformIdentity": transform_identity,
        "evaluationIdentity": expected_evaluation,
        "corpusIdentity": corpus_identity,
        "annotationSetIdentity": EXPECTED_ANNOTATION,
        "capabilityIdentity": EXPECTED_CAPABILITY,
        "acquisitionPolicyIdentity": EXPECTED_POLICY,
    }
    if any(evidence.get(key) != value for key, value in expected_identities.items()):
        fail("R5_A2_SOURCE_IDENTITY_DRIFT")
    if evidence["runs"] != 2 or evidence["devCases"] != 3 or evidence["holdoutCases"] != 1 \
            or evidence["actualAcquisitions"] != 16:
        fail("R5_A2_ACCOUNTING_INVALID")

    cases = evidence["cases"]
    if not isinstance(cases, list) or [item.get("caseId") for item in cases] != case_ids:
        fail("R5_A2_ASSIGNMENT_DRIFT")
    dev = 0
    holdout = 0
    for item in cases:
        if item["partition"] == "DEV":
            dev += 1
        elif item["partition"] == "HOLDOUT":
            holdout += 1
        else:
            fail("R5_A2_PARTITION_INVALID")
        expected_dimensions = oracle_dimensions(item["sourceWidth"], item["sourceHeight"])
        if (item["oracleWidth"], item["oracleHeight"]) != expected_dimensions:
            fail("R5_A2_TRANSFORM_DRIFT")
        verify_metrics(item["baseline"])
        verify_metrics(item["oracle"])
        if not isinstance(item["deterministic"], bool):
            fail("R5_A2_DETERMINISM_INVALID")
    if dev != 3 or holdout != 1:
        fail("R5_A2_PARTITION_INVALID")

    deterministic_cases = sum(1 for item in cases if item["deterministic"])
    deterministic = deterministic_cases == 4
    unreadable = all(item["baseline"]["matchedLines"] < item["baseline"]["expectedLines"] for item in cases)
    improved = all(
        item["oracle"]["matchedLines"] > item["baseline"]["matchedLines"]
        or item["oracle"]["characterErrors"] < item["baseline"]["characterErrors"]
        for item in cases
    )
    hallucination_safe = all(
        item["oracle"]["hallucinationCases"] <= item["baseline"]["hallucinationCases"]
        for item in cases
    )
    causal = deterministic and unreadable and improved and hallucination_safe
    predicates = {
        "EXACT_ASSIGNMENT": "PASS",
        "TWO_RUN_DETERMINISM": "PASS" if deterministic else "MISSING",
        "REPOSITORY_ONLY_INPUT": "PASS",
        "FIXED_HIGHER_RESOLUTION_TRANSFORM": "PASS",
        "STATIC_VIEW_UNREADABLE": "PASS" if unreadable else "FAIL",
        "TARGET_SLICE_IMPROVED": "PASS" if improved else "FAIL",
        "CRITICAL_HALLUCINATION_NON_INCREASE": "PASS" if hallucination_safe else "FAIL",
        "STATIC_VIEW_CAUSALITY": "PASS" if causal else ("FAIL" if deterministic else "MISSING"),
    }
    disposition = "MISSING" if not deterministic else ("TRIGGERED" if causal else "NOT_TRIGGERED")
    reason = "R5_TWO_RUN_DETERMINISM_MISSING" if not deterministic else (
        "R5_STATIC_VIEW_UNREADABILITY_NOT_OBSERVED" if not unreadable else (
            "R5_ORACLE_IMPROVEMENT_NOT_PROVEN" if not improved else (
                "R5_CRITICAL_HALLUCINATION_REGRESSION" if not hallucination_safe
                else "R5_ORACLE_DIFFERENTIAL_CONFIRMED"
            )
        )
    )
    if evidence["deterministicCases"] != deterministic_cases \
            or evidence["predicates"] != predicates \
            or evidence["disposition"] != disposition \
            or evidence["triggered"] != causal \
            or evidence["reasonCode"] != reason:
        fail("R5_A2_DECISION_DRIFT")
    if evidence["externalProviderUsage"] != {
        "attempts": 0, "reservations": 0, "costMicrosCny": 0,
    }:
        fail("R5_A2_PROVIDER_USAGE_NONZERO")
    return {
        "verifierVersion": "renderweave-vrq06-r5-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_CROSS_IMPLEMENTATION_RECOMPUTE",
        "evidenceIdentity": identity,
        "evaluationIdentity": expected_evaluation,
        "assignmentIdentity": assignment_identity,
        "transformIdentity": transform_identity,
        "caseCount": 4,
        "devCases": 3,
        "holdoutCases": 1,
        "runs": 2,
        "actualAcquisitions": 16,
        "deterministicCases": deterministic_cases,
        "disposition": disposition,
        "triggered": causal,
        "reasonCode": reason,
        "providerAttempts": 0,
        "providerReservations": 0,
        "externalProviderCostMicrosCny": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=pathlib.Path)
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    summary = verify(args.evidence.resolve(), args.repository.resolve())
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(canonical_json(summary).decode("utf-8") + "\n")
    print("VRQ-06 R5 probe: PASS; A2; disposition=" + summary["disposition"] + "; ProviderAttempts=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
