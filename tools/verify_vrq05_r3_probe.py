#!/usr/bin/env python3
"""Independent payload-safe verifier for the VRQ-05 R3 causal probe."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from typing import Any

import verify_rapidocr_shadow_evaluation as shadow


EVIDENCE_VERSION = "renderweave-r3-order-repeat-probe/1.0"
IDENTITY_VERSION = "renderweave-r3-order-repeat-probe-evidence/1.0"
ENVELOPE_VERSION = "renderweave-r3-order-repeat-probe-envelope/1.0"
PROTOCOL_VERSION = "renderweave-offline-quality-evaluation-protocol/1.0"
ASSIGNMENT_VERSION = "renderweave-r3-probe-assignment/1.0"
FORBIDDEN = (
    b"base64", b"data:image", b"ocrtext", b"ocr_text", b"prompttext",
    b"modeloutput", b"candidatejson", b"rootdocument", b"boundingbox", b'"bbox"',
)


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


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
        value, end = json.JSONDecoder(object_pairs_hook=unique_object).raw_decode(text)
        if text[end:].strip():
            fail("R3_A2_TRAILING_JSON")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError("R3_A2_JSON_INVALID") from error


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


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
    if not raw or len(raw) > 1024 * 1024 or any(marker in raw.lower() for marker in FORBIDDEN):
        fail("R3_A2_EVIDENCE_BYTES_INVALID")
    envelope = strict_json(raw)
    if set(envelope) != {"envelopeVersion", "evidenceIdentity", "evidence"} \
            or envelope["envelopeVersion"] != ENVELOPE_VERSION:
        fail("R3_A2_ENVELOPE_INVALID")
    evidence = envelope["evidence"]
    identity = f"{IDENTITY_VERSION}:{hashlib.sha256(canonical_json(evidence)).hexdigest()}"
    if envelope["evidenceIdentity"] != identity or evidence["contractVersion"] != EVIDENCE_VERSION:
        fail("R3_A2_IDENTITY_DRIFT")

    protocol_doc, protocol_identity = protocol(repository)
    case_ids = protocol_doc["r3ProbeCaseIds"]
    assignment_identity = f"{ASSIGNMENT_VERSION}:{list_hash([protocol_identity, chr(10).join(case_ids)])}"
    if evidence["protocolIdentity"] != protocol_identity \
            or evidence["assignmentIdentity"] != assignment_identity \
            or evidence["sourceEvaluationIdentity"] != report["evaluationIdentity"] \
            or evidence["sourceReportIdentity"] != report_envelope["reportIdentity"]:
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
        if stable_first != stable_second:
            fail("R3_A2_CASE_NONDETERMINISTIC")
        expected_cases.append(expected_case(first))
    if evidence["cases"] != expected_cases or evidence["runs"] != 2 \
            or evidence["devCases"] != 3 or evidence["holdoutCases"] != 1:
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
    if evidence["predicates"] != predicates or evidence["disposition"] != disposition \
            or evidence["triggered"] or evidence["reasonCode"] != reason:
        fail("R3_A2_DECISION_DRIFT")
    if evidence["externalProviderUsage"] != {
        "attempts": 0, "reservations": 0, "costMicrosCny": 0,
    }:
        fail("R3_A2_PROVIDER_USAGE_NONZERO")
    return {
        "verifierVersion": "renderweave-vrq05-r3-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_CROSS_IMPLEMENTATION_RECOMPUTE",
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
