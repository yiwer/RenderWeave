#!/usr/bin/env python3
"""Independent payload-safe verifier for the VRQ-05 R3 causal probe."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
from typing import Any

import verify_rapidocr_shadow_evaluation as shadow
from offline_json_contract import (
    exact_object,
    payload_safe,
    raw_payload_safe,
    same_json_value,
    strict_nonnegative_int,
)


EVIDENCE_VERSION = "renderweave-r3-order-repeat-probe/1.0"
IDENTITY_VERSION = "renderweave-r3-order-repeat-probe-evidence/1.0"
ENVELOPE_VERSION = "renderweave-r3-order-repeat-probe-envelope/1.0"
PROTOCOL_VERSION = "renderweave-offline-quality-evaluation-protocol/1.0"
ASSIGNMENT_VERSION = "renderweave-r3-probe-assignment/1.0"
EVIDENCE_FIELDS = frozenset({
    "assignmentIdentity", "cases", "contractVersion", "devCases", "disposition",
    "externalProviderUsage", "holdoutCases", "predicates", "protocolIdentity",
    "reasonCode", "runs", "sourceEvaluationIdentity", "sourceReportIdentity", "triggered",
})
CASE_FIELDS = frozenset({
    "allReferencedRegionsObserved", "caseId", "caseIdentity", "comparablePrecedenceEdges",
    "correctPrecedenceEdges", "expectedLines", "expectedPrecedenceEdges",
    "expectedRepeatMemberships", "matchedLines", "observableRepeatMemberships",
    "orderOrRepeatDefectObserved", "partition",
})
CASE_INTEGER_FIELDS = frozenset({
    "comparablePrecedenceEdges", "correctPrecedenceEdges", "expectedLines",
    "expectedPrecedenceEdges", "expectedRepeatMemberships", "matchedLines",
    "observableRepeatMemberships",
})
CASE_BOOLEAN_FIELDS = frozenset({
    "allReferencedRegionsObserved", "orderOrRepeatDefectObserved",
})
PREDICATE_FIELDS = frozenset({
    "EXACT_ASSIGNMENT", "TWO_RUN_DETERMINISM", "COMPATIBILITY_PROJECTION_REPLAYED",
    "GOLD_PRECEDENCE_COMPARED", "GOLD_REPEAT_MEMBERSHIP_COMPARED",
    "ORDER_OR_REPEAT_DEFECT_OBSERVED", "OCR_OMISSION_EXCLUDED", "PROMPT_SHAPE_EXCLUDED",
    "MATERIALIZER_EXCLUDED", "SCORER_EXCLUDED", "EXCLUSIVE_ORDER_REPEAT_CAUSALITY",
})
PROVIDER_USAGE_FIELDS = frozenset({"attempts", "reservations", "costMicrosCny"})


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
        fail("R3_A2_REPOSITORY_REVISION_INVALID")
    return revision


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("R3_A2_DUPLICATE_MEMBER")
        result[key] = value
    return result


def strict_json(raw: bytes) -> Any:
    try:
        text = raw.decode("utf-8", errors="strict")
        value, end = json.JSONDecoder(
            object_pairs_hook=unique_object,
            parse_constant=lambda _value: fail("R3_A2_JSON_INVALID"),
        ).raw_decode(text)
        if text[end:].strip():
            fail("R3_A2_TRAILING_JSON")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError("R3_A2_JSON_INVALID") from error


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def ensure_decoded_payload_safe(value: Any) -> None:
    if not payload_safe(value):
        fail("R3_A2_DECODED_PAYLOAD_FORBIDDEN")


def validate_evidence_shape(evidence: Any) -> None:
    if not exact_object(evidence, EVIDENCE_FIELDS) \
            or evidence["contractVersion"] != EVIDENCE_VERSION:
        fail("R3_A2_EVIDENCE_CONTRACT_INVALID")
    if any(type(evidence[field]) is not str for field in (
        "assignmentIdentity", "disposition", "protocolIdentity", "reasonCode",
        "sourceEvaluationIdentity", "sourceReportIdentity",
    )) or type(evidence["triggered"]) is not bool:
        fail("R3_A2_EVIDENCE_CONTRACT_INVALID")
    if any(not strict_nonnegative_int(evidence[field])
           for field in ("devCases", "holdoutCases", "runs")):
        fail("R3_A2_EVIDENCE_CONTRACT_INVALID")
    predicates = evidence["predicates"]
    if not exact_object(predicates, PREDICATE_FIELDS) \
            or any(type(value) is not str for value in predicates.values()):
        fail("R3_A2_EVIDENCE_CONTRACT_INVALID")
    usage = evidence["externalProviderUsage"]
    if not exact_object(usage, PROVIDER_USAGE_FIELDS) \
            or not all(strict_nonnegative_int(value) for value in usage.values()):
        fail("R3_A2_EVIDENCE_CONTRACT_INVALID")
    cases = evidence["cases"]
    if type(cases) is not list:
        fail("R3_A2_EVIDENCE_CONTRACT_INVALID")
    for item in cases:
        if not exact_object(item, CASE_FIELDS) \
                or any(type(item[field]) is not str for field in ("caseId", "caseIdentity", "partition")) \
                or any(not strict_nonnegative_int(item[field]) for field in CASE_INTEGER_FIELDS) \
                or any(type(item[field]) is not bool for field in CASE_BOOLEAN_FIELDS):
            fail("R3_A2_EVIDENCE_CONTRACT_INVALID")


def protocol(repository: pathlib.Path) -> tuple[dict[str, Any], str]:
    path = repository / (
        "renderweave-inference/src/main/resources/visual-eval/quality-repair/"
        "offline-evaluation-protocol-v1.json"
    )
    raw = path.read_bytes()
    if b"\r" in raw.replace(b"\r\n", b""):
        fail("R3_A2_PROTOCOL_LINE_ENDING_INVALID")
    identity = f"{PROTOCOL_VERSION}:{hashlib.sha256(raw.replace(b'\r\n', b'\n')).hexdigest()}"
    return strict_json(raw), identity


def list_hash(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        raw = value.encode("utf-8")
        digest.update(str(len(raw)).encode("ascii"))
        digest.update(b":")
        digest.update(raw)
        digest.update(b"\n")
    return digest.hexdigest()


def expected_case(record: dict[str, Any]) -> dict[str, Any]:
    order = record["order"]
    repeat = record["repeat"]
    layout = record["layout"]["lines"]
    symptom = order["comparableEdges"] > order["correctEdges"] \
        or repeat["observableMemberships"] < repeat["expectedMemberships"]
    return {
        "caseId": record["caseId"],
        "caseIdentity": record["caseIdentity"],
        "partition": record["partition"],
        "expectedLines": layout["expected"],
        "matchedLines": layout["matched"],
        "expectedPrecedenceEdges": order["expectedEdges"],
        "comparablePrecedenceEdges": order["comparableEdges"],
        "correctPrecedenceEdges": order["correctEdges"],
        "allReferencedRegionsObserved": order["allReferencedRegionsObserved"],
        "expectedRepeatMemberships": repeat["expectedMemberships"],
        "observableRepeatMemberships": repeat["observableMemberships"],
        "orderOrRepeatDefectObserved": symptom,
    }


def verify(report_path: pathlib.Path, evidence_path: pathlib.Path, repository: pathlib.Path) -> dict[str, Any]:
    shadow_summary = shadow.verify_file(report_path, repository)
    report_envelope = strict_json(report_path.read_bytes())
    report = report_envelope["report"]
    raw = evidence_path.read_bytes()
    if not raw or len(raw) > 1024 * 1024 or not raw_payload_safe(raw):
        fail("R3_A2_EVIDENCE_BYTES_INVALID")
    envelope = strict_json(raw)
    if set(envelope) != {"envelopeVersion", "evidenceIdentity", "evidence"} \
            or envelope["envelopeVersion"] != ENVELOPE_VERSION:
        fail("R3_A2_ENVELOPE_INVALID")
    evidence = envelope["evidence"]
    ensure_decoded_payload_safe(envelope)
    validate_evidence_shape(evidence)
    identity = f"{IDENTITY_VERSION}:{hashlib.sha256(canonical_json(evidence)).hexdigest()}"
    if not same_json_value(envelope["evidenceIdentity"], identity):
        fail("R3_A2_IDENTITY_DRIFT")

    protocol_doc, protocol_identity = protocol(repository)
    case_ids = protocol_doc["r3ProbeCaseIds"]
    assignment_identity = f"{ASSIGNMENT_VERSION}:{list_hash([protocol_identity, chr(10).join(case_ids)])}"
    if not same_json_value(evidence["protocolIdentity"], protocol_identity) \
            or not same_json_value(evidence["assignmentIdentity"], assignment_identity) \
            or not same_json_value(evidence["sourceEvaluationIdentity"], report["evaluationIdentity"]) \
            or not same_json_value(evidence["sourceReportIdentity"], report_envelope["reportIdentity"]):
        fail("R3_A2_SOURCE_IDENTITY_DRIFT")
    if shadow_summary["metricsEquivalentCases"] != 60 \
            or shadow_summary["observationEquivalentCases"] != 60:
        fail("R3_A2_SOURCE_NONDETERMINISTIC")

    run_records = [
        {item["caseId"]: item for item in run["records"]}
        for run in report["runs"]
    ]
    expected_cases: list[dict[str, Any]] = []
    for case_id in case_ids:
        first = run_records[0].get(case_id)
        second = run_records[1].get(case_id)
        if first is None or second is None:
            fail("R3_A2_CASE_MISSING")
        stable_first = {key: value for key, value in first.items() if key != "acquisitionMicros"}
        stable_second = {key: value for key, value in second.items() if key != "acquisitionMicros"}
        if not same_json_value(stable_first, stable_second):
            fail("R3_A2_CASE_NONDETERMINISTIC")
        expected_cases.append(expected_case(first))
    if not same_json_value(evidence["cases"], expected_cases) \
            or not same_json_value(evidence["runs"], 2) \
            or not same_json_value(evidence["devCases"], 3) \
            or not same_json_value(evidence["holdoutCases"], 1):
        fail("R3_A2_CASE_PROJECTION_DRIFT")

    symptom = any(item["orderOrRepeatDefectObserved"] for item in expected_cases)
    omission_excluded = all(
        item["matchedLines"] == item["expectedLines"] and item["allReferencedRegionsObserved"]
        for item in expected_cases
    )
    predicates = {
        "EXACT_ASSIGNMENT": "PASS",
        "TWO_RUN_DETERMINISM": "PASS",
        "COMPATIBILITY_PROJECTION_REPLAYED": "PASS",
        "GOLD_PRECEDENCE_COMPARED": "PASS",
        "GOLD_REPEAT_MEMBERSHIP_COMPARED": "PASS",
        "ORDER_OR_REPEAT_DEFECT_OBSERVED": "PASS" if symptom else "FAIL",
        "OCR_OMISSION_EXCLUDED": "PASS" if omission_excluded else "FAIL",
        "PROMPT_SHAPE_EXCLUDED": "MISSING",
        "MATERIALIZER_EXCLUDED": "MISSING",
        "SCORER_EXCLUDED": "PASS",
        "EXCLUSIVE_ORDER_REPEAT_CAUSALITY": "MISSING",
    }
    disposition = "MISSING" if symptom else "NOT_TRIGGERED"
    reason = "R3_ORDER_REPEAT_DEFECT_NOT_OBSERVED" if not symptom else (
        "R3_OCR_OMISSION_NOT_EXCLUDED" if not omission_excluded
        else "R3_DOWNSTREAM_CAUSAL_SEPARATION_MISSING"
    )
    if not same_json_value(evidence["predicates"], predicates) \
            or not same_json_value(evidence["disposition"], disposition) \
            or not same_json_value(evidence["triggered"], False) \
            or not same_json_value(evidence["reasonCode"], reason):
        fail("R3_A2_DECISION_DRIFT")
    if not same_json_value(evidence["externalProviderUsage"], {
        "attempts": 0, "reservations": 0, "costMicrosCny": 0,
    }):
        fail("R3_A2_PROVIDER_USAGE_NONZERO")
    return {
        "verifierVersion": "renderweave-vrq05-r3-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_CROSS_IMPLEMENTATION_RECOMPUTE",
        "repositoryRevision": repository_revision(repository),
        "evidenceIdentity": identity,
        "assignmentIdentity": assignment_identity,
        "caseCount": 4,
        "devCases": 3,
        "holdoutCases": 1,
        "runs": 2,
        "disposition": disposition,
        "triggered": False,
        "reasonCode": reason,
        "providerAttempts": 0,
        "providerReservations": 0,
        "externalProviderCostMicrosCny": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=pathlib.Path)
    parser.add_argument("evidence", type=pathlib.Path)
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    summary = verify(args.report.resolve(), args.evidence.resolve(), args.repository.resolve())
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(canonical_json(summary).decode("utf-8") + "\n")
    print("VRQ-05 R3 probe: PASS; A2; disposition=" + summary["disposition"] + "; ProviderAttempts=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
