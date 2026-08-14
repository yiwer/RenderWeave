#!/usr/bin/env python3
"""Independent payload-safe verifier for the VRQ-06 R5 oracle differential."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
from typing import Any

import offline_quality_resources as quality_resources
from offline_json_contract import (
    JAVA_INT_MAX,
    exact_object,
    payload_safe,
    raw_payload_safe,
    same_json_value,
    strict_nonnegative_int,
    strict_positive_int,
)


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
EVIDENCE_FIELDS = frozenset({
    "acquisitionPolicyIdentity", "actualAcquisitions", "annotationSetIdentity",
    "assignmentIdentity", "capabilityIdentity", "cases", "contractVersion",
    "corpusIdentity", "deterministicCases", "devCases", "disposition",
    "evaluationIdentity", "externalProviderUsage", "holdoutCases", "predicates",
    "protocolIdentity", "reasonCode", "runs", "transformIdentity", "triggered",
})
CASE_FIELDS = frozenset({
    "baseline", "caseId", "caseIdentity", "deterministic", "oracle", "oracleHeight",
    "oracleWidth", "partition", "sourceHeight", "sourceWidth",
})
METRIC_FIELDS = frozenset({
    "observationCount", "expectedLines", "matchedLines", "characterErrors",
    "hallucinationCases", "expectedPrecedenceEdges", "comparablePrecedenceEdges",
    "correctPrecedenceEdges", "expectedRepeatMemberships", "observableRepeatMemberships",
})
PREDICATE_FIELDS = frozenset({
    "EXACT_ASSIGNMENT", "TWO_RUN_DETERMINISM", "REPOSITORY_ONLY_INPUT",
    "FIXED_HIGHER_RESOLUTION_TRANSFORM", "STATIC_VIEW_UNREADABLE",
    "TARGET_SLICE_IMPROVED", "CRITICAL_HALLUCINATION_NON_INCREASE", "STATIC_VIEW_CAUSALITY",
})
PROVIDER_USAGE_FIELDS = frozenset({"attempts", "reservations", "costMicrosCny"})
SOURCE_DIMENSIONS_BY_VARIANT = {
    1: (1_600, 1_000),
    2: (1_000, 1_600),
    3: (1_024, 768),
    4: (1_400, 900),
    5: (1_800, 1_200),
}


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def repository_revision(repository: pathlib.Path) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"],
        check=True, capture_output=True, text=True, encoding="utf-8",
    )
    revision = completed.stdout.strip()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        fail("R5_A2_REPOSITORY_REVISION_INVALID")
    return revision


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
        value, end = json.JSONDecoder(
            object_pairs_hook=unique_object,
            parse_constant=lambda _value: fail("R5_A2_JSON_INVALID"),
        ).raw_decode(text)
        if text[end:].strip():
            fail("R5_A2_TRAILING_JSON")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError("R5_A2_JSON_INVALID") from error


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def ensure_decoded_payload_safe(value: Any) -> None:
    if not payload_safe(value):
        fail("R5_A2_DECODED_PAYLOAD_FORBIDDEN")


def validate_evidence_shape(evidence: Any) -> None:
    if not exact_object(evidence, EVIDENCE_FIELDS) \
            or evidence["contractVersion"] != EVIDENCE_VERSION:
        fail("R5_A2_EVIDENCE_CONTRACT_INVALID")
    identity_pattern = r"[A-Za-z][A-Za-z0-9._+/-]{0,127}:[0-9a-f]{64}"
    if any(type(evidence[field]) is not str
           or re.fullmatch(identity_pattern, evidence[field]) is None
           for field in (
               "acquisitionPolicyIdentity", "annotationSetIdentity", "assignmentIdentity",
               "corpusIdentity", "evaluationIdentity", "protocolIdentity", "transformIdentity",
            )) \
            or type(evidence["capabilityIdentity"]) is not str \
            or not evidence["capabilityIdentity"].strip() \
            or type(evidence["disposition"]) is not str \
            or evidence["disposition"] not in {"TRIGGERED", "NOT_TRIGGERED", "MISSING"} \
            or type(evidence["reasonCode"]) is not str \
            or re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", evidence["reasonCode"]) is None \
            or type(evidence["triggered"]) is not bool:
        fail("R5_A2_EVIDENCE_CONTRACT_INVALID")
    if any(not strict_nonnegative_int(evidence[field]) for field in (
        "actualAcquisitions", "deterministicCases", "devCases", "holdoutCases", "runs",
    )) or any(evidence[field] > JAVA_INT_MAX for field in (
        "actualAcquisitions", "deterministicCases", "devCases", "holdoutCases", "runs",
    )):
        fail("R5_A2_EVIDENCE_CONTRACT_INVALID")
    predicates = evidence["predicates"]
    if not exact_object(predicates, PREDICATE_FIELDS) \
            or any(type(value) is not str or value not in {"PASS", "FAIL", "MISSING"}
                   for value in predicates.values()):
        fail("R5_A2_EVIDENCE_CONTRACT_INVALID")
    usage = evidence["externalProviderUsage"]
    if not exact_object(usage, PROVIDER_USAGE_FIELDS) \
            or not all(strict_nonnegative_int(value) for value in usage.values()):
        fail("R5_A2_EVIDENCE_CONTRACT_INVALID")
    cases = evidence["cases"]
    if type(cases) is not list or len(cases) != 4:
        fail("R5_A2_EVIDENCE_CONTRACT_INVALID")
    case_ids: list[str] = []
    partitions: list[str] = []
    for item in cases:
        if not exact_object(item, CASE_FIELDS) \
                or type(item["caseId"]) is not str \
                or re.fullmatch(r"[a-z][a-z0-9-]{0,127}", item["caseId"]) is None \
                or type(item["caseIdentity"]) is not str \
                or re.fullmatch(identity_pattern, item["caseIdentity"]) is None \
                or type(item["partition"]) is not str \
                or item["partition"] not in {"DEV", "HOLDOUT"} \
                or type(item["deterministic"]) is not bool \
                or any(not strict_positive_int(item[field], JAVA_INT_MAX) for field in (
                    "oracleHeight", "oracleWidth", "sourceHeight", "sourceWidth",
                )) \
                or item["oracleWidth"] <= item["sourceWidth"] \
                or item["oracleHeight"] <= item["sourceHeight"] \
                or item["oracleWidth"] > 2_400 or item["oracleHeight"] > 2_400:
            fail("R5_A2_EVIDENCE_CONTRACT_INVALID")
        verify_metrics(item["baseline"])
        verify_metrics(item["oracle"])
        case_ids.append(item["caseId"])
        partitions.append(item["partition"])
    if len(set(case_ids)) != 4 or partitions.count("DEV") != 3 \
            or partitions.count("HOLDOUT") != 1:
        fail("R5_A2_EVIDENCE_CONTRACT_INVALID")


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
    verified = quality_resources.load_protocol(repository)
    if not verified.identity.startswith(PROTOCOL_VERSION + ":"):
        fail("R5_A2_PROTOCOL_IDENTITY_INVALID")
    return verified.document, verified.identity


def oracle_dimensions(width: int, height: int) -> tuple[int, int]:
    if width * 2 <= 2400 and height * 2 <= 2400:
        return width * 2, height * 2
    if width >= height:
        return 2400, height * 2400 // width
    return width * 2400 // height, 2400


def expected_source_dimensions(case_id: str) -> tuple[int, int]:
    match = re.fullmatch(r"[a-z][a-z0-9-]{0,124}-v([1-5])", case_id)
    if match is None:
        fail("R5_A2_CASE_DIMENSION_AUTHORITY_INVALID")
    return SOURCE_DIMENSIONS_BY_VARIANT[int(match.group(1))]


def verify_metrics(value: dict[str, Any]) -> None:
    if not exact_object(value, METRIC_FIELDS) \
            or any(not strict_nonnegative_int(item) for item in value.values()):
        fail("R5_A2_CASE_METRICS_INVALID")
    if value["matchedLines"] > value["expectedLines"] \
            or value["correctPrecedenceEdges"] > value["comparablePrecedenceEdges"] \
            or value["comparablePrecedenceEdges"] > value["expectedPrecedenceEdges"] \
            or value["observableRepeatMemberships"] > value["expectedRepeatMemberships"]:
        fail("R5_A2_CASE_METRICS_INVALID")


def verify(evidence_path: pathlib.Path, repository: pathlib.Path) -> dict[str, Any]:
    raw = evidence_path.read_bytes()
    if not raw or len(raw) > 1024 * 1024 or not raw_payload_safe(raw):
        fail("R5_A2_EVIDENCE_BYTES_INVALID")
    envelope = strict_json(raw)
    if set(envelope) != {"envelopeVersion", "evidenceIdentity", "evidence"} \
            or envelope["envelopeVersion"] != ENVELOPE_VERSION:
        fail("R5_A2_ENVELOPE_INVALID")
    evidence = envelope["evidence"]
    ensure_decoded_payload_safe(envelope)
    validate_evidence_shape(evidence)
    identity = f"{IDENTITY_VERSION}:{hashlib.sha256(canonical_json(evidence)).hexdigest()}"
    if not same_json_value(envelope["evidenceIdentity"], identity):
        fail("R5_A2_IDENTITY_DRIFT")

    verified_protocol = quality_resources.load_protocol(repository)
    protocol_doc = verified_protocol.document
    protocol_identity = verified_protocol.identity
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
    if any(not same_json_value(evidence.get(key), value)
           for key, value in expected_identities.items()):
        fail("R5_A2_SOURCE_IDENTITY_DRIFT")
    if not same_json_value(evidence["runs"], 2) \
            or not same_json_value(evidence["devCases"], 3) \
            or not same_json_value(evidence["holdoutCases"], 1) \
            or not same_json_value(evidence["actualAcquisitions"], 16):
        fail("R5_A2_ACCOUNTING_INVALID")

    cases = evidence["cases"]
    if not isinstance(cases, list) or [item.get("caseId") for item in cases] != case_ids:
        fail("R5_A2_ASSIGNMENT_DRIFT")
    dev = 0
    holdout = 0
    for item in cases:
        locked = verified_protocol.cases_by_id[item["caseId"]]
        if item["caseIdentity"] != locked["caseIdentity"] \
                or item["partition"] != locked["partition"]:
            fail("R5_A2_CASE_IDENTITY_DRIFT")
        if (item["sourceWidth"], item["sourceHeight"]) \
                != expected_source_dimensions(item["caseId"]):
            fail("R5_A2_CASE_DIMENSION_AUTHORITY_DRIFT")
        if item["partition"] == "DEV":
            dev += 1
        elif item["partition"] == "HOLDOUT":
            holdout += 1
        else:
            fail("R5_A2_PARTITION_INVALID")
        expected_dimensions = oracle_dimensions(item["sourceWidth"], item["sourceHeight"])
        if (item["oracleWidth"], item["oracleHeight"]) != expected_dimensions:
            fail("R5_A2_TRANSFORM_DRIFT")
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
    if not same_json_value(evidence["deterministicCases"], deterministic_cases) \
            or not same_json_value(evidence["predicates"], predicates) \
            or not same_json_value(evidence["disposition"], disposition) \
            or not same_json_value(evidence["triggered"], causal) \
            or not same_json_value(evidence["reasonCode"], reason):
        fail("R5_A2_DECISION_DRIFT")
    if not same_json_value(evidence["externalProviderUsage"], {
        "attempts": 0, "reservations": 0, "costMicrosCny": 0,
    }):
        fail("R5_A2_PROVIDER_USAGE_NONZERO")
    return {
        "verifierVersion": "renderweave-vrq06-r5-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_CROSS_IMPLEMENTATION_RECOMPUTE",
        "repositoryRevision": repository_revision(repository),
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
