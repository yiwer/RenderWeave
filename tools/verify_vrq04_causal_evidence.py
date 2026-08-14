#!/usr/bin/env python3
"""Independent payload-safe verifier for VRQ-04 causal evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
from typing import Any

import verify_rapidocr_shadow_evaluation as shadow
import offline_quality_resources as quality_resources
from offline_json_contract import (
    exact_object,
    payload_safe,
    raw_payload_safe,
    same_json_value,
    strict_nonnegative_int,
)


PACK_VERSION = "renderweave-rapidocr-causal-evidence-pack/1.0"
PACK_ID_VERSION = "renderweave-rapidocr-causal-evidence/1.0"
ENVELOPE_VERSION = "renderweave-rapidocr-causal-evidence-envelope/1.0"
PROTOCOL_VERSION = "renderweave-offline-quality-evaluation-protocol/1.0"
N7_AUDIT_SHA256 = "e1f550b28e7c57fd4944c3b83297e8c85a167ba147683e4aff655b00f0a59655"
PACK_FIELDS = frozenset({
    "contractVersion", "evaluationIdentity", "protocolIdentity", "corpusIdentity",
    "annotationSetIdentity", "capabilityIdentity", "acquisitionPolicyIdentity",
    "accounting", "metrics", "evidenceFacts", "attributions", "externalProviderUsage",
})
ACCOUNTING_FIELDS = frozenset({
    "runs", "casesPerRun", "devPerRun", "holdoutPerRun", "actualAcquisitions",
    "metricsEquivalentCases", "observationEquivalentCases",
})
EVIDENCE_FACT_FIELDS = frozenset({
    "challengerRiskReviews", "denseOrSmallTextMissDevCases",
    "denseOrSmallTextMissHoldoutCases", "oracleCropImprovementCases",
    "recalledOrderOrRepeatErrorDevCases", "recalledOrderOrRepeatErrorHoldoutCases",
    "stableOcrOrLayoutGapCases", "stableOcrOrLayoutGapDevCases",
    "stableOcrOrLayoutGapHoldoutCases", "strictShapeProtocolEvidenceCases",
})
ATTRIBUTION_FIELDS = frozenset({
    "OBSERVATION", "LAYOUT", "ORDER_REPEAT", "SHAPE_CODEC", "SEMANTIC",
    "STATIC_VIEW", "MATERIALIZER", "SCORER",
})
ATTRIBUTION_VALUE_FIELDS = frozenset({"evidenceReference", "reasonCode", "result"})
PROVIDER_USAGE_FIELDS = frozenset({"attempts", "reservations", "costMicrosCny"})
METRIC_SCOPE_FIELDS = frozenset({"caseCount", "metricsBps"})


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
        fail("CAUSAL_REPOSITORY_REVISION_INVALID")
    return revision


def strict_json(raw: bytes) -> Any:
    try:
        text = raw.decode("utf-8", errors="strict")
        decoder = json.JSONDecoder(
            object_pairs_hook=unique_object,
            parse_constant=lambda _value: fail("CAUSAL_JSON_INVALID"),
        )
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


def ensure_decoded_payload_safe(value: Any) -> None:
    if not payload_safe(value):
        fail("CAUSAL_DECODED_PAYLOAD_FORBIDDEN")


def validate_pack_shape(pack: Any) -> None:
    if not exact_object(pack, PACK_FIELDS) or pack["contractVersion"] != PACK_VERSION:
        fail("CAUSAL_PACK_CONTRACT_INVALID")
    if any(type(pack[field]) is not str for field in (
        "evaluationIdentity", "protocolIdentity", "corpusIdentity",
        "annotationSetIdentity", "capabilityIdentity", "acquisitionPolicyIdentity",
    )):
        fail("CAUSAL_PACK_CONTRACT_INVALID")
    accounting = pack["accounting"]
    if not exact_object(accounting, ACCOUNTING_FIELDS) or not all(
        strict_nonnegative_int(value) for value in accounting.values()
    ):
        fail("CAUSAL_PACK_CONTRACT_INVALID")
    facts = pack["evidenceFacts"]
    if not exact_object(facts, EVIDENCE_FACT_FIELDS) or not all(
        strict_nonnegative_int(value) for value in facts.values()
    ):
        fail("CAUSAL_PACK_CONTRACT_INVALID")
    attributions = pack["attributions"]
    if not exact_object(attributions, ATTRIBUTION_FIELDS) or any(
        not exact_object(value, ATTRIBUTION_VALUE_FIELDS)
        or any(type(item) is not str for item in value.values())
        for value in attributions.values()
    ):
        fail("CAUSAL_PACK_CONTRACT_INVALID")
    usage = pack["externalProviderUsage"]
    if not exact_object(usage, PROVIDER_USAGE_FIELDS) or not all(
        strict_nonnegative_int(value) for value in usage.values()
    ):
        fail("CAUSAL_PACK_CONTRACT_INVALID")
    metrics = pack["metrics"]
    if type(metrics) is not dict or not metrics or any(
        type(scope) is not str
        or not exact_object(value, METRIC_SCOPE_FIELDS)
        or not strict_nonnegative_int(value["caseCount"])
        or type(value["metricsBps"]) is not dict
        or any(type(key) is not str or not strict_nonnegative_int(metric)
               for key, metric in value["metricsBps"].items())
        for scope, value in metrics.items()
    ):
        fail("CAUSAL_PACK_CONTRACT_INVALID")


def protocol_identity(repository: pathlib.Path) -> str:
    protocol = quality_resources.load_protocol(repository)
    if not protocol.identity.startswith(PROTOCOL_VERSION + ":"):
        fail("CAUSAL_PROTOCOL_IDENTITY_INVALID")
    return protocol.identity


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
    verified_report = shadow.load_verified_file(report_path, repository)
    shadow_summary = verified_report.summary
    report_envelope = verified_report.envelope
    if report_envelope["reportIdentity"] != shadow_summary["reportIdentity"]:
        fail("CAUSAL_SHADOW_REPORT_SNAPSHOT_DRIFT")
    report = report_envelope["report"]
    raw = causal_path.read_bytes()
    if not raw or len(raw) > 4 * 1024 * 1024:
        fail("CAUSAL_BYTES_INVALID")
    if not raw_payload_safe(raw):
        fail("CAUSAL_PAYLOAD_FORBIDDEN")
    envelope = strict_json(raw)
    if set(envelope) != {"envelopeVersion", "evidence", "evidenceIdentity"} \
            or envelope["envelopeVersion"] != ENVELOPE_VERSION:
        fail("CAUSAL_ENVELOPE_INVALID")
    pack = envelope["evidence"]
    ensure_decoded_payload_safe(envelope)
    validate_pack_shape(pack)
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
        if not same_json_value(pack[key], value):
            fail("CAUSAL_SOURCE_IDENTITY_DRIFT")
    expected_accounting = {
        "runs": 2, "casesPerRun": 60, "devPerRun": 45, "holdoutPerRun": 15,
        "actualAcquisitions": 120, "metricsEquivalentCases": 60,
        "observationEquivalentCases": 60,
    }
    if not same_json_value(pack["accounting"], expected_accounting) \
            or not same_json_value(shadow_summary["metricsEquivalentCases"], 60) \
            or not same_json_value(shadow_summary["observationEquivalentCases"], 60):
        fail("CAUSAL_ACCOUNTING_DRIFT")
    first_metrics = scoped_metrics(report["runs"][0])
    second_metrics = scoped_metrics(report["runs"][1])
    if not same_json_value(first_metrics, second_metrics) \
            or not same_json_value(pack["metrics"], first_metrics):
        fail("CAUSAL_METRIC_DRIFT")
    if not same_json_value(pack["evidenceFacts"], report["evidenceFacts"]):
        fail("CAUSAL_FACT_DRIFT")
    if not same_json_value(pack["attributions"], expected_attributions(pack)):
        fail("CAUSAL_ATTRIBUTION_DRIFT")
    if not same_json_value(pack["externalProviderUsage"], {
        "attempts": 0, "reservations": 0, "costMicrosCny": 0,
    }):
        fail("CAUSAL_PROVIDER_USAGE_NONZERO")
    return {
        "verifierVersion": "renderweave-vrq04-causal-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_CROSS_IMPLEMENTATION_RECOMPUTE",
        "repositoryRevision": repository_revision(repository),
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
