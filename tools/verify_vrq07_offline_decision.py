#!/usr/bin/env python3
"""Independent reconstruction of the sole VRQ-07 R2-R5 offline decision."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from typing import Any


PACK_VERSION = "renderweave-frozen-quality-evidence-pack/1.0"
PACK_ENVELOPE = "renderweave-frozen-quality-evidence-pack-envelope/1.0"
DECISION_VERSION = "R2R5TriggerDecision/1.0"
DECISION_ID_VERSION = "renderweave-r2r5-trigger-decision/1.0"
DECISION_ENVELOPE = "renderweave-r2r5-trigger-decision-envelope/1.0"
BASE_REVISION = "604849e9b400abf98bca9c12951a50b1488f043b"
AUTHORITY_SHA = "e2cb4a0455f712b35618f8239e369e3a92bbd50a5a274d24a6eb39ee6734b78f"
AUDIT_SHA = "e1f550b28e7c57fd4944c3b83297e8c85a167ba147683e4aff655b00f0a59655"
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
            fail("VRQ07_A2_DUPLICATE_MEMBER")
        result[key] = value
    return result


def strict_json(raw: bytes) -> Any:
    try:
        text = raw.decode("utf-8", errors="strict")
        value, end = json.JSONDecoder(object_pairs_hook=unique_object).raw_decode(text)
        if text[end:].strip():
            fail("VRQ07_A2_TRAILING_JSON")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError("VRQ07_A2_JSON_INVALID") from error


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def envelope(path: pathlib.Path, version: str, identity_field: str, payload_field: str) -> tuple[dict[str, Any], str]:
    raw = path.read_bytes()
    if not raw or len(raw) > 4 * 1024 * 1024 or any(marker in raw.lower() for marker in FORBIDDEN):
        fail("VRQ07_A2_INPUT_BYTES_INVALID")
    value = strict_json(raw)
    if value.get("envelopeVersion") != version or identity_field not in value or payload_field not in value:
        fail("VRQ07_A2_INPUT_ENVELOPE_INVALID")
    return value[payload_field], value[identity_field]


def component_identity(path: pathlib.Path, envelope_version: str, identity_field: str, payload_field: str,
                       identity_version: str) -> tuple[dict[str, Any], str]:
    payload, identity = envelope(path, envelope_version, identity_field, payload_field)
    computed = f"{identity_version}:{hashlib.sha256(canonical_json(payload)).hexdigest()}"
    if identity != computed:
        fail("VRQ07_A2_COMPONENT_IDENTITY_DRIFT")
    return payload, identity


def normalized_resource_identity(path: pathlib.Path, version: str) -> str:
    raw = path.read_bytes()
    if b"\r" in raw.replace(b"\r\n", b""):
        fail("VRQ07_A2_RESOURCE_LINE_ENDING_INVALID")
    return f"{version}:{hashlib.sha256(raw.replace(b'\r\n', b'\n')).hexdigest()}"


def predicate(predicate_id: str, result: str, reason: str, evidence: str) -> dict[str, Any]:
    return {
        "predicateId": predicate_id,
        "expectedEvidenceClass": "A1_A2",
        "result": result,
        "reasonCode": reason,
        "evidenceReference": evidence,
    }


def route(route_id: str, predicates: list[dict[str, Any]]) -> dict[str, Any]:
    return {"route": route_id, "predicates": sorted(predicates, key=lambda item: item["predicateId"])}


def route_decision(route_item: dict[str, Any]) -> dict[str, Any]:
    predicates = route_item["predicates"]
    all_pass = all(item["result"] == "PASS" for item in predicates)
    any_fail = any(item["result"] == "FAIL" for item in predicates)
    disposition = "TRIGGERED" if all_pass else (
        "REJECTED_BY_CURRENT_EVIDENCE" if any_fail else "EVIDENCE_REQUIRED"
    )
    return {
        "route": route_item["route"],
        "triggerSatisfied": all_pass,
        "disposition": disposition,
        "predicates": predicates,
    }


def verify(args: argparse.Namespace) -> dict[str, Any]:
    rapid, rapid_identity = component_identity(
        args.rapidocr, "renderweave-rapidocr-causal-evidence-envelope/1.0", "evidenceIdentity", "evidence",
        "renderweave-rapidocr-causal-evidence/1.0")
    r3, r3_identity = component_identity(
        args.r3, "renderweave-r3-order-repeat-probe-envelope/1.0", "evidenceIdentity", "evidence",
        "renderweave-r3-order-repeat-probe-evidence/1.0")
    r5, r5_identity = component_identity(
        args.r5, "renderweave-r5-oracle-probe-envelope/1.0", "evidenceIdentity", "evidence",
        "renderweave-r5-oracle-probe-evidence/1.0")
    if rapid["externalProviderUsage"] != {"attempts": 0, "reservations": 0, "costMicrosCny": 0} \
            or r3["externalProviderUsage"] != {"attempts": 0, "reservations": 0, "costMicrosCny": 0} \
            or r5["externalProviderUsage"] != {"attempts": 0, "reservations": 0, "costMicrosCny": 0}:
        fail("VRQ07_A2_PROVIDER_USAGE_NONZERO")
    if rapid["protocolIdentity"] != r3["protocolIdentity"] \
            or rapid["protocolIdentity"] != r5["protocolIdentity"] \
            or rapid["evaluationIdentity"] != r3["sourceEvaluationIdentity"] \
            or rapid["corpusIdentity"] != r5["corpusIdentity"] \
            or rapid["annotationSetIdentity"] != r5["annotationSetIdentity"] \
            or rapid["capabilityIdentity"] != r5["capabilityIdentity"] \
            or rapid["acquisitionPolicyIdentity"] != r5["acquisitionPolicyIdentity"]:
        fail("VRQ07_A2_COMPONENT_CLOSURE_INVALID")

    catalog_path = args.repository / (
        "renderweave-inference/src/main/resources/visual-eval/quality-repair/challenger-capabilities-v1.json"
    )
    catalog = strict_json(catalog_path.read_bytes())
    catalog_identity = normalized_resource_identity(
        catalog_path, "renderweave-challenger-capability-catalog/1.0")
    admitted = all(
        item["admissionDisposition"] == "ADMITTED" and item["executable"]
        and not item["missingAdmissionDimensions"] for item in catalog["challengers"]
    )
    r3_result = {"TRIGGERED": "PASS", "NOT_TRIGGERED": "FAIL", "MISSING": "MISSING"}[r3["disposition"]]
    r5_result = {"TRIGGERED": "PASS", "NOT_TRIGGERED": "FAIL", "MISSING": "MISSING"}[r5["disposition"]]
    routes = sorted([
        route("R2", [
            predicate("R2_CAPABILITY_ADMITTED", "PASS" if admitted else "MISSING",
                      "R2_CAPABILITY_ADMITTED" if admitted else "R2_CAPABILITY_NOT_ADMITTED", catalog_identity),
            predicate("R2_SHADOW_NET_BENEFIT", "MISSING", "R2_SHADOW_NOT_RUN", rapid["protocolIdentity"]),
            predicate("R2_STABLE_PERCEPTION_GAP", "PASS", "R2_RAPIDOCR_STABLE_GAP_PRESENT", rapid_identity),
        ]),
        route("R3", [predicate("R3_CAUSAL_ORDER_REPEAT_DEFECT", r3_result, r3["reasonCode"], r3_identity)]),
        route("R4", [predicate("R4_SHAPE_CODEC_BOTTLENECK", "FAIL",
                               "R4_SEMANTIC_BOTTLENECK_DOMINATES", "sha256:" + AUDIT_SHA)]),
        route("R5", [predicate("R5_STATIC_VIEW_CAUSAL_GAIN", r5_result, r5["reasonCode"], r5_identity)]),
    ], key=lambda item: item["route"])
    expected_pack = {
        "contractVersion": PACK_VERSION,
        "baseRevision": BASE_REVISION,
        "n704EvidenceAuthoritySha256": AUTHORITY_SHA,
        "n704AuditSha256": AUDIT_SHA,
        "n704Decision": "FAIL",
        "n704AuthorizationStatus": "CLOSED",
        "n705DependencyStatus": "BLOCKED",
        "routes": routes,
        "successorIdentities": [],
        "externalProviderUsage": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
    }
    pack, pack_identity = envelope(
        args.pack, PACK_ENVELOPE, "evidencePackIdentity", "evidencePack")
    computed_pack_identity = f"{PACK_VERSION}:{hashlib.sha256(canonical_json(expected_pack)).hexdigest()}"
    if pack != expected_pack or pack_identity != computed_pack_identity:
        fail("VRQ07_A2_EVIDENCE_PACK_DRIFT")

    decision_routes = [route_decision(item) for item in routes]
    triggered = [item["route"] for item in decision_routes if item["triggerSatisfied"]]
    if len(triggered) > 1:
        overall = "STOP_TO_SPEC_MULTIPLE"
    elif triggered == ["R3"]:
        overall = "STOP_TO_SPEC_R3"
    elif triggered == ["R5"]:
        overall = "STOP_TO_SPEC_R5"
    elif triggered == ["R2"]:
        overall = "R2_SHADOW_ALLOWED"
    elif all(item["disposition"] == "REJECTED_BY_CURRENT_EVIDENCE" for item in decision_routes):
        overall = "NO_REPAIR_ROUTE"
    else:
        overall = "OFFLINE_EVIDENCE_REQUIRED"
    expected_decision = {
        "decisionVersion": DECISION_VERSION,
        "evidencePackIdentity": pack_identity,
        "routes": decision_routes,
        "overallDisposition": overall,
        "externalProviderUsage": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
    }
    decision, decision_identity = envelope(
        args.decision, DECISION_ENVELOPE, "decisionIdentity", "decision")
    computed_decision_identity = f"{DECISION_ID_VERSION}:{hashlib.sha256(canonical_json(expected_decision)).hexdigest()}"
    if decision != expected_decision or decision_identity != computed_decision_identity:
        fail("VRQ07_A2_DECISION_DRIFT")
    if overall != "STOP_TO_SPEC_R5":
        fail("VRQ07_A2_UNEXPECTED_OVERALL_DISPOSITION")
    return {
        "verifierVersion": "renderweave-vrq07-offline-decision-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_CROSS_IMPLEMENTATION_RECONSTRUCTION",
        "evidencePackIdentity": pack_identity,
        "decisionIdentity": decision_identity,
        "overallDisposition": overall,
        "routeDispositions": {item["route"]: item["disposition"] for item in decision_routes},
        "providerAttempts": 0,
        "providerReservations": 0,
        "externalProviderCostMicrosCny": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rapidocr", required=True, type=pathlib.Path)
    parser.add_argument("--r3", required=True, type=pathlib.Path)
    parser.add_argument("--r5", required=True, type=pathlib.Path)
    parser.add_argument("--pack", required=True, type=pathlib.Path)
    parser.add_argument("--decision", required=True, type=pathlib.Path)
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    args.repository = args.repository.resolve()
    summary = verify(args)
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(canonical_json(summary).decode("utf-8") + "\n")
    print("VRQ-07 offline decision: PASS; A2; overall=" + summary["overallDisposition"] + "; ProviderAttempts=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
