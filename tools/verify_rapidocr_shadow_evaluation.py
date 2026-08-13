#!/usr/bin/env python3
"""Independent payload-safe verifier for the actual RapidOCR corpus-v2 re-anchor."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from typing import Any, Iterable

import verify_layered_evaluation as layered


ENVELOPE_VERSION = "renderweave-rapidocr-shadow-envelope/1.0"
REPORT_VERSION = "renderweave-rapidocr-shadow-report/1.0"
RECORD_VERSION = "renderweave-rapidocr-shadow-case-record/1.0"
EVALUATION_VERSION = "renderweave-rapidocr-shadow-evaluation/1.0"
VERIFIER_VERSION = "renderweave-rapidocr-shadow-python-verifier/1.0"
CAPABILITY = "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"
MODEL_MANIFEST = "c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5"
DIAGNOSTIC_SLICES = ("DENSE_TEXT", "SMALL_TEXT")
TRIGGERS = ("R2", "R3", "R4", "R5")
COMPONENT_KEYS = (
    "inputSetIdentity", "annotationSetIdentity", "annotationVersion",
    "normalizationRenderIdentity", "observationContractIdentity",
    "acquisitionPolicyIdentity", "capabilityIdentity", "adapterIdentity",
    "engineIdentity", "weightIdentity", "preprocessingIdentity",
    "postprocessingIdentity", "coordinateIdentity", "projectionIdentity",
    "orderIdentity", "confidenceIdentity", "canonicalizationIdentity",
    "caseEvaluatorIdentity", "reporterIdentity", "runProtocolIdentity",
    "smallTextSliceIdentity", "authorityIdentity", "budgetIdentity",
)


class VerificationError(Exception):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, allow_nan=False,
                      separators=(",", ":"), sort_keys=True).encode("utf-8")


def framed_hash(values: Iterable[str], prefix: bytes = b"") -> str:
    digest = hashlib.sha256()
    digest.update(prefix)
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def evaluation_identity(components: dict[str, str]) -> str:
    material: list[str] = []
    for key in sorted(components):
        material.extend((key, components[key]))
    return f"{EVALUATION_VERSION}:{framed_hash(material)}"


def report_identity(report: dict[str, Any]) -> str:
    return f"{REPORT_VERSION}:{hashlib.sha256(canonical_json(report)).hexdigest()}"


def policy_identity() -> str:
    fields = (
        "AcquisitionPolicy/1.0", "DocumentObservationIR/1.0", CAPABILITY,
        "rapidocr-local-process/1.0", "rapidocr-openvino-ppocrv6-small",
        "rapidocr-3.9.2+openvino-2026.0.0", MODEL_MANIFEST,
        "explicit-bgr/1.0", "rapidocr-lines/1.0", "source-pixel-top-left/1.0",
        "half-open-box/1.0", "v45-source-to-candidate/1.0",
        "top-left-canonical/1.0", "unicode-nfc-whitespace-collapse/1.0",
        "basis-points/1.0", "v45-confidence-buckets/1.0",
        "EPHEMERAL_STAGE_CONTEXT_ONLY", "10", "512", "256", "32768",
        "524288", "30000",
    )
    return framed_hash(fields, b"renderweave-acquisition-policy\x00")


def expected_components(repository: pathlib.Path) -> dict[str, str]:
    lock = layered.verify_corpus_lock(repository)
    lock_document = layered.parse_strict_json((
        repository / "renderweave-inference/src/main/resources/visual-eval/v2/identity-lock.json"
    ).read_text(encoding="utf-8"))
    return {
        "inputSetIdentity": str(lock["corpusIdentity"]),
        "annotationSetIdentity": str(lock["annotationSetIdentity"]),
        "annotationVersion": "renderweave-layered-annotation/1.0",
        "normalizationRenderIdentity": str(lock_document["renderContractIdentity"]),
        "observationContractIdentity": "DocumentObservationIR/1.0",
        "acquisitionPolicyIdentity": f"AcquisitionPolicy/1.0:{policy_identity()}",
        "capabilityIdentity": CAPABILITY,
        "adapterIdentity": "rapidocr-local-process/1.0",
        "engineIdentity": "rapidocr-openvino-ppocrv6-small:rapidocr-3.9.2+openvino-2026.0.0",
        "weightIdentity": f"weight-sha256:{MODEL_MANIFEST}",
        "preprocessingIdentity": "explicit-bgr/1.0",
        "postprocessingIdentity": "rapidocr-lines/1.0",
        "coordinateIdentity": "source-pixel-top-left/1.0:half-open-box/1.0",
        "projectionIdentity": "v45-source-to-candidate/1.0",
        "orderIdentity": "top-left-canonical/1.0",
        "confidenceIdentity": "basis-points/1.0:v45-confidence-buckets/1.0",
        "canonicalizationIdentity": "unicode-nfc-whitespace-collapse/1.0",
        "caseEvaluatorIdentity": "renderweave-rapidocr-shadow-case-evaluator/1.0",
        "reporterIdentity": "renderweave-rapidocr-shadow-reporter/1.0",
        "runProtocolIdentity": "two-isolated-complete-runs/1.0",
        "smallTextSliceIdentity": "slot-height-at-most-1800-bps/1.0",
        "authorityIdentity": "corpus-v2-shadow-diagnostic-only/1.0",
        "budgetIdentity": "zero-provider-attempts-reservations-cost/1.0",
    }


def binary(values: list[dict[str, int]]) -> dict[str, int]:
    return {key: sum(item[key] for item in values) for key in ("expected", "predicted", "matched")}


def success_rate(numerator: int, denominator: int) -> int:
    return 10_000 if denominator == 0 else numerator * 10_000 // denominator


def error_rate(numerator: int, denominator: int) -> int:
    return 0 if denominator == 0 else numerator * 10_000 // denominator


def precision(counts: dict[str, int]) -> int:
    return success_rate(counts["matched"], counts["predicted"])


def recall(counts: dict[str, int]) -> int:
    return success_rate(counts["matched"], counts["expected"])


def percentile(values: list[int], value: int) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = max(0, (len(ordered) * value + 99) // 100 - 1)
    return ordered[index]


def aggregate(records: list[dict[str, Any]]) -> dict[str, Any]:
    ocr_keys = (
        "cases", "referenceCharacters", "predictedCharacters", "characterSubstitutions",
        "characterInsertions", "characterDeletions", "referenceWords", "predictedWords",
        "wordSubstitutions", "wordInsertions", "wordDeletions", "emptyReferenceCases",
        "hallucinationCases", "completeMissCases",
    )
    ocr = {key: sum(item["ocr"][key] for item in records) for key in ocr_keys}
    line_counts = binary([item["layout"]["lines"] for item in records])
    layout = {
        "lines": line_counts,
        "centerContainedMatches": sum(item["layout"]["centerContainedMatches"] for item in records),
        "predictedCoverageBpsSum": sum(item["layout"]["predictedCoverageBpsSum"] for item in records),
        "goldCoverageBpsSum": sum(item["layout"]["goldCoverageBpsSum"] for item in records),
        "observedRegions": sum(item["layout"]["observedRegions"] for item in records),
    }
    order = {
        "expectedEdges": sum(item["order"]["expectedEdges"] for item in records),
        "comparableEdges": sum(item["order"]["comparableEdges"] for item in records),
        "correctEdges": sum(item["order"]["correctEdges"] for item in records),
        "allReferencedRegionsObserved": all(
            item["order"]["allReferencedRegionsObserved"] for item in records),
    }
    repeat_keys = (
        "expectedGroups", "completeGroups", "expectedItems", "completeItems",
        "expectedMemberships", "observableMemberships",
    )
    repeat = {key: sum(item["repeat"][key] for item in records) for key in repeat_keys}
    confidence_keys = ("observations", "nativeValueBpsSum", "lowCount", "mediumCount", "highCount")
    confidence = {key: sum(item["confidence"][key] for item in records) for key in confidence_keys}
    matched = line_counts["matched"]
    comparable = order["comparableEdges"]
    confidence_count = confidence["observations"]
    metrics = {
        "ocr.cer": error_rate(
            ocr["characterSubstitutions"] + ocr["characterInsertions"] + ocr["characterDeletions"],
            ocr["referenceCharacters"]),
        "ocr.wer": error_rate(
            ocr["wordSubstitutions"] + ocr["wordInsertions"] + ocr["wordDeletions"],
            ocr["referenceWords"]),
        "ocr.completeMissRate": error_rate(ocr["completeMissCases"], ocr["cases"]),
        "ocr.hallucinationRate": error_rate(ocr["hallucinationCases"], ocr["cases"]),
        "layout.linePrecision": precision(line_counts),
        "layout.lineRecall": recall(line_counts),
        "layout.meanPredictedCoverage": 0 if matched == 0 else layout["predictedCoverageBpsSum"] // matched,
        "layout.meanGoldCoverage": 0 if matched == 0 else layout["goldCoverageBpsSum"] // matched,
        "order.comparableCoverage": success_rate(comparable, order["expectedEdges"]),
        "order.accuracy": 10_000 if comparable == 0 else order["correctEdges"] * 10_000 // comparable,
        "repeat.groupRecall": success_rate(repeat["completeGroups"], repeat["expectedGroups"]),
        "repeat.itemRecall": success_rate(repeat["completeItems"], repeat["expectedItems"]),
        "repeat.membershipRecall": success_rate(
            repeat["observableMemberships"], repeat["expectedMemberships"]),
        "confidence.meanNativeValue": 0 if confidence_count == 0
        else confidence["nativeValueBpsSum"] // confidence_count,
    }
    latencies = [item["acquisitionMicros"] for item in records]
    return {
        "caseCount": len(records), "ocr": ocr, "layout": layout, "order": order,
        "repeat": repeat, "confidence": confidence,
        "acquisitionLatency": {
            "count": len(latencies), "p50Micros": percentile(latencies, 50),
            "p95Micros": percentile(latencies, 95),
        },
        "metricsBps": metrics,
    }


def diagnostic_assignments(repository: pathlib.Path) -> dict[str, list[str]]:
    scenes_path = repository / "renderweave-inference/src/main/resources/visual-eval/v1/scenes.json"
    raw = layered.parse_strict_json(scenes_path.read_text(encoding="utf-8"))
    scenes = {item["sceneId"]: item for item in raw["scenes"]}
    lock = layered.verify_corpus_lock(repository)
    result: dict[str, list[str]] = {}
    for item in lock["cases"]:
        slices: list[str] = []
        if item["difficulty"] == "DENSE_TEXT" or "DENSE_TEXT" in item["failureSlices"]:
            slices.append("DENSE_TEXT")
        scene_id = str(item["caseId"]).rsplit("-v", 1)[0]
        scene = scenes.get(scene_id)
        if scene is None:
            fail("DIAGNOSTIC_SCENE_MISSING")
        if any(element["kind"] == "SLOT"
               and element["boundingBox"][3] - element["boundingBox"][1] <= 1_800
               for element in scene["elements"]):
            slices.append("SMALL_TEXT")
        result[str(item["caseId"])] = slices
    return result


def validate_record(record: dict[str, Any], locked: dict[str, Any], diagnostics: list[str]) -> None:
    required = {
        "recordVersion", "caseId", "caseIdentity", "partition", "domain", "difficulty",
        "failureSlices", "diagnosticSlices", "ocr", "layout", "order", "repeat",
        "confidence", "observationCount", "acquisitionMicros",
    }
    if set(record) != required or record["recordVersion"] != RECORD_VERSION:
        fail("RECORD_CONTRACT_INVALID")
    for key in ("caseId", "caseIdentity", "partition", "domain", "difficulty", "failureSlices"):
        if record[key] != locked[key]:
            fail("CASE_ASSIGNMENT_DRIFT")
    if record["diagnosticSlices"] != diagnostics:
        fail("DIAGNOSTIC_ASSIGNMENT_DRIFT")
    if not isinstance(record["acquisitionMicros"], int) or record["acquisitionMicros"] < 0:
        fail("RECORD_LATENCY_INVALID")
    if record["observationCount"] != record["layout"]["lines"]["predicted"] \
            or record["observationCount"] != record["confidence"]["observations"]:
        fail("RECORD_OBSERVATION_ACCOUNTING_INVALID")


def metric_view(record: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in record.items() if key != "acquisitionMicros"}


def evidence_facts(records: list[dict[str, Any]], second: dict[str, dict[str, Any]]) -> dict[str, int]:
    values = {
        "stableOcrOrLayoutGapCases": 0, "stableOcrOrLayoutGapDevCases": 0,
        "stableOcrOrLayoutGapHoldoutCases": 0, "recalledOrderOrRepeatErrorDevCases": 0,
        "recalledOrderOrRepeatErrorHoldoutCases": 0, "denseOrSmallTextMissDevCases": 0,
        "denseOrSmallTextMissHoldoutCases": 0, "challengerRiskReviews": 0,
        "strictShapeProtocolEvidenceCases": 0, "oracleCropImprovementCases": 0,
    }
    for item in records:
        if metric_view(item) != metric_view(second[item["caseId"]]):
            continue
        gap = item["ocr"]["characterSubstitutions"] + item["ocr"]["characterInsertions"] \
            + item["ocr"]["characterDeletions"] > 0 \
            or item["layout"]["lines"]["matched"] < item["layout"]["lines"]["expected"]
        suffix = "DevCases" if item["partition"] == "DEV" else "HoldoutCases"
        if gap:
            values["stableOcrOrLayoutGapCases"] += 1
            values[f"stableOcrOrLayoutGap{suffix}"] += 1
        order_or_repeat = item["layout"]["lines"]["matched"] > 0 and (
            item["order"]["comparableEdges"] - item["order"]["correctEdges"] > 0
            or item["repeat"]["observableMemberships"] < item["repeat"]["expectedMemberships"])
        if order_or_repeat:
            values[f"recalledOrderOrRepeatError{suffix}"] += 1
        dense_miss = bool(item["diagnosticSlices"]) and (
            item["layout"]["lines"]["matched"] < item["layout"]["lines"]["expected"])
        if dense_miss:
            values[f"denseOrSmallTextMiss{suffix}"] += 1
    return values


def verify_envelope(envelope: dict[str, Any], repository: pathlib.Path) -> dict[str, Any]:
    if set(envelope) != {"envelopeVersion", "reportIdentity", "report"} \
            or envelope["envelopeVersion"] != ENVELOPE_VERSION:
        fail("ENVELOPE_CONTRACT_INVALID")
    report = envelope["report"]
    if not isinstance(report, dict) or report.get("reportVersion") != REPORT_VERSION:
        fail("REPORT_CONTRACT_INVALID")
    if envelope["reportIdentity"] != report_identity(report):
        fail("REPORT_IDENTITY_DRIFT")
    components = report.get("evaluationComponents")
    if not isinstance(components, dict) or tuple(sorted(components)) != tuple(sorted(COMPONENT_KEYS)):
        fail("EVALUATION_COMPONENTS_INVALID")
    expected = expected_components(repository)
    if components != expected or report.get("evaluationIdentity") != evaluation_identity(expected):
        fail("EVALUATION_IDENTITY_DRIFT")
    if report.get("corpusIdentity") != expected["inputSetIdentity"] \
            or report.get("annotationSetIdentity") != expected["annotationSetIdentity"]:
        fail("CORPUS_AUTHORITY_DRIFT")
    if report.get("shadowDiagnostic") is not True or report.get("certificationEligible") is not False \
            or report.get("expectedCaseCount") != 60:
        fail("SHADOW_AUTHORITY_INVALID")
    if report.get("externalProvider") != {"attempts": 0, "reservations": 0, "costMicrosCny": 0}:
        fail("EXTERNAL_PROVIDER_USAGE_NONZERO")

    lock = layered.verify_corpus_lock(repository)
    locked = {str(item["caseId"]): item for item in lock["cases"]}
    diagnostics = diagnostic_assignments(repository)
    runs = report.get("runs")
    if not isinstance(runs, list) or len(runs) != 2:
        fail("RUN_SET_INVALID")
    validated_runs: list[list[dict[str, Any]]] = []
    for ordinal, run in enumerate(runs, 1):
        if not isinstance(run, dict) or run.get("runOrdinal") != ordinal \
                or run.get("expectedCaseCount") != 60 or run.get("observedCaseCount") != 60 \
                or run.get("complete") is not True:
            fail("RUN_CONTRACT_INVALID")
        records = run.get("records")
        if not isinstance(records, list) or len(records) != 60 \
                or [item.get("caseId") for item in records] != [item["caseId"] for item in lock["cases"]]:
            fail("RUN_CASE_SET_INVALID")
        for record in records:
            case_id = record.get("caseId")
            if case_id not in locked:
                fail("CASE_UNKNOWN")
            validate_record(record, locked[case_id], diagnostics[case_id])
        expected_slices = {
            "global": aggregate(records),
            "partitions": {key: aggregate([item for item in records if item["partition"] == key])
                           for key in layered.PARTITIONS},
            "domains": {key: aggregate([item for item in records if item["domain"] == key])
                        for key in sorted({item["domain"] for item in records})},
            "difficulties": {key: aggregate([item for item in records if item["difficulty"] == key])
                             for key in layered.DIFFICULTIES},
            "diagnosticSlices": {key: aggregate([item for item in records
                                                  if key in item["diagnosticSlices"]])
                                 for key in DIAGNOSTIC_SLICES},
            "failureSlices": {key: aggregate([item for item in records
                                               if key in item["failureSlices"]])
                              for key in layered.FAILURE_SLICES},
        }
        for key, value in expected_slices.items():
            if run.get(key) != value:
                fail(f"RUN_{key.upper()}_AGGREGATE_DRIFT")
        validated_runs.append(records)

    first = validated_runs[0]
    second = {item["caseId"]: item for item in validated_runs[1]}
    metrics_equivalent = sum(metric_view(item) == metric_view(second[item["caseId"]]) for item in first)
    determinism = report.get("determinism")
    if not isinstance(determinism, dict) or determinism.get("comparedCases") != 60 \
            or determinism.get("metricsEquivalentCases") != metrics_equivalent \
            or determinism.get("observationEquivalentCases") != 60 \
            or determinism.get("deterministic") is not True \
            or determinism.get("verdictCode") != "DETERMINISTIC_TWO_RUNS" \
            or metrics_equivalent != 60:
        fail("DETERMINISM_VERDICT_DRIFT")
    facts = evidence_facts(first, second)
    if report.get("evidenceFacts") != facts:
        fail("EVIDENCE_FACTS_DRIFT")
    triggers = report.get("triggers")
    expected_trigger = {
        "requiredEvidencePresent": False, "triggered": False,
        "reasonCode": "NOT_TRIGGERED_EVIDENCE_ABSENT",
    }
    if triggers != {key: expected_trigger for key in TRIGGERS}:
        fail("TRIGGER_REPORT_DRIFT")
    return {
        "verifierVersion": VERIFIER_VERSION, "result": "PASS",
        "assurance": "A2_STRICT_INPUT_REPLAY", "reportIdentity": envelope["reportIdentity"],
        "evaluationIdentity": report["evaluationIdentity"], "corpusIdentity": report["corpusIdentity"],
        "caseCount": 60, "runCount": 2, "metricsEquivalentCases": metrics_equivalent,
        "observationEquivalentCases": 60, "providerAttempts": 0,
        "providerReservations": 0, "externalProviderCostMicrosCny": 0,
        "triggerCodes": {key: expected_trigger["reasonCode"] for key in TRIGGERS},
        "aggregateIdentity": "renderweave-rapidocr-shadow-recomputed-aggregates/1.0:"
        + hashlib.sha256(canonical_json({"runs": [aggregate(items) for items in validated_runs],
                                         "facts": facts})).hexdigest(),
    }


def verify_file(path: pathlib.Path, repository: pathlib.Path) -> dict[str, Any]:
    raw = path.read_bytes()
    if len(raw) == 0 or len(raw) > 16 * 1024 * 1024:
        fail("REPORT_BYTES_INVALID")
    text = raw.decode("utf-8", errors="strict")
    layered.scan_payload_free(text, "rapidocr-shadow-report")
    value = layered.parse_strict_json(text)
    if not isinstance(value, dict):
        fail("ENVELOPE_NOT_OBJECT")
    return verify_envelope(value, repository)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=pathlib.Path)
    parser.add_argument("--repository", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        summary = verify_file(args.report.resolve(strict=True), args.repository.resolve(strict=True))
        encoded = json.dumps(summary, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
        if args.output is not None:
            args.output.write_text(encoded, encoding="utf-8", newline="\n")
        else:
            sys.stdout.write(encoded)
        return 0
    except (VerificationError, layered.VerificationError, OSError, UnicodeError, ValueError) as failure:
        sys.stderr.write(f"rapidocr shadow verification failed: {failure}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
