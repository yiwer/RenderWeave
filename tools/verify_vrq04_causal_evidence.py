#!/usr/bin/env python3
"""Independent payload-safe verifier for VRQ-04 causal evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from typing import Any

import verify_rapidocr_shadow_evaluation as shadow


PACK_VERSION = "renderweave-rapidocr-causal-evidence-pack/1.0"
PACK_ID_VERSION = "renderweave-rapidocr-causal-evidence/1.0"
ENVELOPE_VERSION = "renderweave-rapidocr-causal-evidence-envelope/1.0"
PROTOCOL_VERSION = "renderweave-offline-quality-evaluation-protocol/1.0"
N7_AUDIT_SHA256 = "e1f550b28e7c57fd4944c3b83297e8c85a167ba147683e4aff655b00f0a59655"
FORBIDDEN = (
    b"base64", b"data:image", b"ocrtext", b"ocr_text", b"prompttext",
    b"modeloutput", b"candidatejson", b"rootdocument", b"boundingbox", b'"bbox"',
)


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def strict_json(raw: bytes) -> Any:
    try:
        text = raw.decode("utf-8", errors="strict")
        decoder = json.JSONDecoder(object_pairs_hook=unique_object)
        value, end = decoder.raw_decode(text)
        if text[end:].strip():
            fail("CAUSAL_TRAILING_JSON")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError("CAUSAL_JSON_INVALID") from error


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("CAUSAL_DUPLICATE_MEMBER")
        result[key] = value
    return result


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def protocol_identity(repository: pathlib.Path) -> str:
    path = repository / (
        "renderweave-inference/src/main/resources/visual-eval/quality-repair/"
        "offline-evaluation-protocol-v1.json"
    )
    raw = path.read_bytes()
    if b"\r" in raw.replace(b"\r\n", b""):
        fail("CAUSAL_PROTOCOL_LINE_ENDING_INVALID")
    normalized = raw.replace(b"\r\n", b"\n")
    return f"{PROTOCOL_VERSION}:{hashlib.sha256(normalized).hexdigest()}"


def scoped_metrics(run: dict[str, Any]) -> dict[str, Any]:
    result = {"GLOBAL": stable_scope(run["global"])}
    for prefix, field in (
        ("PARTITION/", "partitions"),
        ("DOMAIN/", "domains"),
        ("DIFFICULTY/", "difficulties"),
        ("DIAGNOSTIC/", "diagnosticSlices"),
        ("FAILURE/", "failureSlices"),
    ):
        for key, value in run[field].items():
            scope = prefix + key
            if scope in result:
                fail("CAUSAL_SCOPE_DUPLICATE")
            result[scope] = stable_scope(value)
    return result


def stable_scope(value: dict[str, Any]) -> dict[str, Any]:
    return {"caseCount": value["caseCount"], "metricsBps": value["metricsBps"]}


def expected_attributions(pack: dict[str, Any]) -> dict[str, Any]:
    evaluation = pack["evaluationIdentity"]
    protocol = pack["protocolIdentity"]
    return {
        "OBSERVATION": attribution(
            "OBSERVED_CONTRIBUTOR", "OBSERVATION_TWO_RUN_STABLE_GAP_CONFIRMED", evaluation),
        "LAYOUT": attribution(
            "OBSERVED_CONTRIBUTOR", "LAYOUT_RECALL_GAP_CONFIRMED", evaluation),
        "ORDER_REPEAT": attribution(
            "MISSING", "ORDER_REPEAT_CAUSAL_PROBE_REQUIRED", protocol),
        "SHAPE_CODEC": attribution(
            "EXCLUDED_BY_CURRENT_EVIDENCE", "SHAPE_CODEC_NOT_PRIMARY_BOTTLENECK",
            "sha256:" + N7_AUDIT_SHA256),
        "SEMANTIC": attribution(
            "OBSERVED_CONTRIBUTOR", "SEMANTIC_TOPOLOGY_GROUNDING_FAILURE_CONFIRMED",
            "sha256:" + N7_AUDIT_SHA256),
        "STATIC_VIEW": attribution(
            "MISSING", "ORACLE_STATIC_VIEW_DIFFERENTIAL_REQUIRED", protocol),
        "MATERIALIZER": attribution(
            "MISSING", "MATERIALIZER_CAUSAL_SEPARATION_REQUIRED", protocol),
        "SCORER": attribution(
            "EXCLUDED_BY_CURRENT_EVIDENCE", "SCORER_INDEPENDENT_RECOMPUTE_PASSED", evaluation),
    }


def attribution(result: str, reason: str, evidence: str) -> dict[str, Any]:
    return {"evidenceReference": evidence, "reasonCode": reason, "result": result}


def verify(
    report_path: pathlib.Path,
    causal_path: pathlib.Path,
    repository: pathlib.Path,
) -> dict[str, Any]:
    shadow_summary = shadow.verify_file(report_path, repository)
    report_envelope = strict_json(report_path.read_bytes())
    report = report_envelope["report"]
    raw = causal_path.read_bytes()
    if not raw or len(raw) > 4 * 1024 * 1024:
        fail("CAUSAL_BYTES_INVALID")
    lowered = raw.lower()
    if any(marker in lowered for marker in FORBIDDEN):
        fail("CAUSAL_PAYLOAD_FORBIDDEN")
    envelope = strict_json(raw)
    if set(envelope) != {"envelopeVersion", "evidence", "evidenceIdentity"} \
            or envelope["envelopeVersion"] != ENVELOPE_VERSION:
        fail("CAUSAL_ENVELOPE_INVALID")
    pack = envelope["evidence"]
    expected_pack_fields = {
        "contractVersion", "evaluationIdentity", "protocolIdentity", "corpusIdentity",
        "annotationSetIdentity", "capabilityIdentity", "acquisitionPolicyIdentity",
        "accounting", "metrics", "evidenceFacts", "attributions", "externalProviderUsage",
    }
    if not isinstance(pack, dict) or set(pack) != expected_pack_fields \
            or pack["contractVersion"] != PACK_VERSION:
        fail("CAUSAL_PACK_CONTRACT_INVALID")
    computed_identity = f"{PACK_ID_VERSION}:{hashlib.sha256(canonical_json(pack)).hexdigest()}"
    if envelope["evidenceIdentity"] != computed_identity:
        fail("CAUSAL_IDENTITY_DRIFT")
    components = report["evaluationComponents"]
    expected_identity_fields = {
        "evaluationIdentity": report["evaluationIdentity"],
        "protocolIdentity": protocol_identity(repository),
        "corpusIdentity": report["corpusIdentity"],
        "annotationSetIdentity": report["annotationSetIdentity"],
        "capabilityIdentity": components["capabilityIdentity"],
        "acquisitionPolicyIdentity": components["acquisitionPolicyIdentity"],
    }
    for key, value in expected_identity_fields.items():
        if pack[key] != value:
            fail("CAUSAL_SOURCE_IDENTITY_DRIFT")
    expected_accounting = {
        "runs": 2, "casesPerRun": 60, "devPerRun": 45, "holdoutPerRun": 15,
        "actualAcquisitions": 120, "metricsEquivalentCases": 60,
        "observationEquivalentCases": 60,
    }
    if pack["accounting"] != expected_accounting \
            or shadow_summary["metricsEquivalentCases"] != 60 \
            or shadow_summary["observationEquivalentCases"] != 60:
        fail("CAUSAL_ACCOUNTING_DRIFT")
    first_metrics = scoped_metrics(report["runs"][0])
    second_metrics = scoped_metrics(report["runs"][1])
    if first_metrics != second_metrics or pack["metrics"] != first_metrics:
        fail("CAUSAL_METRIC_DRIFT")
    if pack["evidenceFacts"] != report["evidenceFacts"]:
        fail("CAUSAL_FACT_DRIFT")
    if pack["attributions"] != expected_attributions(pack):
        fail("CAUSAL_ATTRIBUTION_DRIFT")
    if pack["externalProviderUsage"] != {
        "attempts": 0, "reservations": 0, "costMicrosCny": 0,
    }:
        fail("CAUSAL_PROVIDER_USAGE_NONZERO")
    return {
        "verifierVersion": "renderweave-vrq04-causal-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_CROSS_IMPLEMENTATION_RECOMPUTE",
        "evidenceIdentity": computed_identity,
        "evaluationIdentity": pack["evaluationIdentity"],
        "protocolIdentity": pack["protocolIdentity"],
        "caseCount": 60,
        "runCount": 2,
        "actualAcquisitions": 120,
        "metricsEquivalentCases": 60,
        "observationEquivalentCases": 60,
        "providerAttempts": 0,
        "providerReservations": 0,
        "externalProviderCostMicrosCny": 0,
        "attributionResults": {
            key: value["result"] for key, value in sorted(pack["attributions"].items())
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=pathlib.Path)
    parser.add_argument("causal", type=pathlib.Path)
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    summary = verify(args.report.resolve(), args.causal.resolve(), args.repository.resolve())
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(canonical_json(summary).decode("utf-8") + "\n")
    print("VRQ-04 causal evidence: PASS; A2; ProviderAttempts=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
