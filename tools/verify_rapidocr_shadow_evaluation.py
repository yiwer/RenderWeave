#!/usr/bin/env python3
"""Independent payload-safe verifier for the actual RapidOCR corpus-v2 re-anchor."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
from typing import Any, Iterable, NamedTuple

import offline_json_contract as json_contract
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
REPORT_FIELDS = frozenset((
    "reportVersion", "evaluationIdentity", "evaluationComponents", "corpusIdentity",
    "annotationSetIdentity", "shadowDiagnostic", "certificationEligible",
    "expectedCaseCount", "runs", "determinism", "evidenceFacts", "triggers",
    "externalProvider",
))
RUN_FIELDS = frozenset((
    "runOrdinal", "expectedCaseCount", "observedCaseCount", "complete", "records",
    "global", "partitions", "domains", "difficulties", "diagnosticSlices",
    "failureSlices",
))
RECORD_FIELDS = frozenset((
    "recordVersion", "caseId", "caseIdentity", "partition", "domain", "difficulty",
    "failureSlices", "diagnosticSlices", "ocr", "layout", "order", "repeat",
    "confidence", "observationCount", "acquisitionMicros",
))
OCR_FIELDS = (
    "cases", "referenceCharacters", "predictedCharacters", "characterSubstitutions",
    "characterInsertions", "characterDeletions", "referenceWords", "predictedWords",
    "wordSubstitutions", "wordInsertions", "wordDeletions", "emptyReferenceCases",
    "hallucinationCases", "completeMissCases",
)
BINARY_FIELDS = frozenset(("expected", "predicted", "matched"))
LAYOUT_FIELDS = frozenset((
    "lines", "centerContainedMatches", "predictedCoverageBpsSum",
    "goldCoverageBpsSum", "observedRegions",
))
ORDER_FIELDS = frozenset((
    "expectedEdges", "comparableEdges", "correctEdges", "allReferencedRegionsObserved",
))
REPEAT_FIELDS = (
    "expectedGroups", "completeGroups", "expectedItems", "completeItems",
    "expectedMemberships", "observableMemberships",
)
CONFIDENCE_FIELDS = (
    "observations", "nativeValueBpsSum", "lowCount", "mediumCount", "highCount",
)
AGGREGATE_FIELDS = frozenset((
    "caseCount", "ocr", "layout", "order", "repeat", "confidence",
    "acquisitionLatency", "metricsBps",
))
LATENCY_FIELDS = frozenset(("count", "p50Micros", "p95Micros"))
METRIC_KEYS = frozenset((
    "ocr.cer", "ocr.wer", "ocr.completeMissRate", "ocr.hallucinationRate",
    "layout.linePrecision", "layout.lineRecall", "layout.meanPredictedCoverage",
    "layout.meanGoldCoverage", "order.comparableCoverage", "order.accuracy",
    "repeat.groupRecall", "repeat.itemRecall", "repeat.membershipRecall",
    "confidence.meanNativeValue",
))
DETERMINISM_FIELDS = frozenset((
    "comparedCases", "metricsEquivalentCases", "observationEquivalentCases",
    "deterministic", "verdictCode",
))
EVIDENCE_FACT_FIELDS = frozenset((
    "stableOcrOrLayoutGapCases", "stableOcrOrLayoutGapDevCases",
    "stableOcrOrLayoutGapHoldoutCases", "recalledOrderOrRepeatErrorDevCases",
    "recalledOrderOrRepeatErrorHoldoutCases", "denseOrSmallTextMissDevCases",
    "denseOrSmallTextMissHoldoutCases", "challengerRiskReviews",
    "strictShapeProtocolEvidenceCases", "oracleCropImprovementCases",
))
TRIGGER_FIELDS = frozenset(("requiredEvidencePresent", "triggered", "reasonCode"))
PROVIDER_FIELDS = frozenset(("attempts", "reservations", "costMicrosCny"))
IDENTITY_PATTERN = re.compile(r"^[a-z][a-z0-9._+/-]{1,127}:[0-9a-f]{64}$")
CASE_ID_PATTERN = re.compile(r"^[a-z][a-z0-9-]{0,127}$")
DOMAIN_PATTERN = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")


class VerificationError(Exception):
    pass


class VerifiedShadowReport(NamedTuple):
    summary: dict[str, Any]
    envelope: dict[str, Any]
    raw_sha256: str


def fail(code: str) -> None:
    raise VerificationError(code)


def require_object(value: Any, fields: set[str] | frozenset[str], code: str) -> dict[str, Any]:
    if not json_contract.exact_object(value, fields):
        fail(f"{code}_CONTRACT_INVALID")
    return value


def require_nonnegative_long(value: Any, code: str) -> int:
    if not json_contract.strict_nonnegative_int(value, json_contract.JAVA_LONG_MAX):
        fail(f"{code}_LONG_INVALID")
    return value


def require_nonnegative_int(value: Any, code: str) -> int:
    if not json_contract.strict_nonnegative_int(value, json_contract.JAVA_INT_MAX):
        fail(f"{code}_INT_INVALID")
    return value


def require_bool(value: Any, code: str) -> bool:
    if type(value) is not bool:
        fail(f"{code}_BOOLEAN_INVALID")
    return value


def require_string(value: Any, pattern: re.Pattern[str], code: str) -> str:
    if type(value) is not str or pattern.fullmatch(value) is None:
        fail(f"{code}_STRING_INVALID")
    return value


def checked_sum(values: Iterable[int], code: str = "NUMERIC_SUM") -> int:
    result = 0
    for value in values:
        if type(value) is not int or value < 0 or value > json_contract.JAVA_LONG_MAX - result:
            fail(f"{code}_OVERFLOW")
        result += value
    return result


def checked_bps_product(value: int, code: str) -> int:
    if value > json_contract.JAVA_LONG_MAX // 10_000:
        fail(f"{code}_OVERFLOW")
    return value * 10_000


def require_same(actual: Any, expected: Any, code: str) -> None:
    if not json_contract.same_json_value(actual, expected):
        fail(code)


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
    return {key: checked_sum((item[key] for item in values), f"BINARY_{key.upper()}")
            for key in ("expected", "predicted", "matched")}


def success_rate(numerator: int, denominator: int) -> int:
    if denominator == 0:
        return 10_000
    result = checked_bps_product(numerator, "SUCCESS_RATE") // denominator
    if result > json_contract.JAVA_INT_MAX:
        fail("SUCCESS_RATE_INT_OVERFLOW")
    return result


def error_rate(numerator: int, denominator: int) -> int:
    if denominator == 0:
        return 0
    result = checked_bps_product(numerator, "ERROR_RATE") // denominator
    if result > json_contract.JAVA_INT_MAX:
        fail("ERROR_RATE_INT_OVERFLOW")
    return result


def ocr_error_rate(errors: int, reference: int) -> int:
    if reference == 0:
        return 0 if errors == 0 else 10_000
    result = checked_bps_product(errors, "OCR_ERROR_RATE") // reference
    if result > json_contract.JAVA_INT_MAX:
        fail("OCR_ERROR_RATE_INT_OVERFLOW")
    return result


def precision(counts: dict[str, int]) -> int:
    if counts["predicted"] == 0:
        return 10_000 if counts["expected"] == 0 else 0
    return success_rate(counts["matched"], counts["predicted"])


def recall(counts: dict[str, int]) -> int:
    return success_rate(counts["matched"], counts["expected"])


def percentile(values: list[int], value: int) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = max(0, (len(ordered) * value + 99) // 100 - 1)
    return ordered[index]


def metric_summary(
        ocr: dict[str, int], layout: dict[str, Any], order: dict[str, Any],
        repeat: dict[str, int], confidence: dict[str, int],
) -> dict[str, int]:
    line_counts = layout["lines"]
    matched = line_counts["matched"]
    comparable = order["comparableEdges"]
    confidence_count = confidence["observations"]
    character_errors = checked_sum((
        ocr["characterSubstitutions"], ocr["characterInsertions"],
        ocr["characterDeletions"],
    ), "OCR_CHARACTER_ERRORS")
    word_errors = checked_sum((
        ocr["wordSubstitutions"], ocr["wordInsertions"], ocr["wordDeletions"],
    ), "OCR_WORD_ERRORS")
    return {
        "ocr.cer": ocr_error_rate(character_errors, ocr["referenceCharacters"]),
        "ocr.wer": ocr_error_rate(word_errors, ocr["referenceWords"]),
        "ocr.completeMissRate": error_rate(ocr["completeMissCases"], ocr["cases"]),
        "ocr.hallucinationRate": error_rate(ocr["hallucinationCases"], ocr["cases"]),
        "layout.linePrecision": precision(line_counts),
        "layout.lineRecall": recall(line_counts),
        "layout.meanPredictedCoverage": (
            0 if matched == 0 else layout["predictedCoverageBpsSum"] // matched),
        "layout.meanGoldCoverage": (
            0 if matched == 0 else layout["goldCoverageBpsSum"] // matched),
        "order.comparableCoverage": success_rate(comparable, order["expectedEdges"]),
        "order.accuracy": (
            10_000 if comparable == 0 else success_rate(order["correctEdges"], comparable)),
        "repeat.groupRecall": success_rate(repeat["completeGroups"], repeat["expectedGroups"]),
        "repeat.itemRecall": success_rate(repeat["completeItems"], repeat["expectedItems"]),
        "repeat.membershipRecall": success_rate(
            repeat["observableMemberships"], repeat["expectedMemberships"]),
        "confidence.meanNativeValue": (
            0 if confidence_count == 0 else confidence["nativeValueBpsSum"] // confidence_count),
    }


def aggregate(records: list[dict[str, Any]]) -> dict[str, Any]:
    ocr = {key: checked_sum((item["ocr"][key] for item in records), f"OCR_{key.upper()}")
           for key in OCR_FIELDS}
    line_counts = binary([item["layout"]["lines"] for item in records])
    layout = {
        "lines": line_counts,
        "centerContainedMatches": checked_sum(
            (item["layout"]["centerContainedMatches"] for item in records), "LAYOUT_CENTER"),
        "predictedCoverageBpsSum": checked_sum(
            (item["layout"]["predictedCoverageBpsSum"] for item in records), "LAYOUT_PREDICTED"),
        "goldCoverageBpsSum": checked_sum(
            (item["layout"]["goldCoverageBpsSum"] for item in records), "LAYOUT_GOLD"),
        "observedRegions": checked_sum(
            (item["layout"]["observedRegions"] for item in records), "LAYOUT_REGIONS"),
    }
    order = {
        "expectedEdges": checked_sum(
            (item["order"]["expectedEdges"] for item in records), "ORDER_EXPECTED"),
        "comparableEdges": checked_sum(
            (item["order"]["comparableEdges"] for item in records), "ORDER_COMPARABLE"),
        "correctEdges": checked_sum(
            (item["order"]["correctEdges"] for item in records), "ORDER_CORRECT"),
        "allReferencedRegionsObserved": all(
            item["order"]["allReferencedRegionsObserved"] for item in records),
    }
    repeat = {key: checked_sum((item["repeat"][key] for item in records), f"REPEAT_{key.upper()}")
              for key in REPEAT_FIELDS}
    confidence = {
        key: checked_sum((item["confidence"][key] for item in records), f"CONFIDENCE_{key.upper()}")
        for key in CONFIDENCE_FIELDS
    }
    metrics = metric_summary(ocr, layout, order, repeat, confidence)
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


def validate_ocr(value: Any, code: str) -> dict[str, Any]:
    result = require_object(value, frozenset(OCR_FIELDS), code)
    for key in OCR_FIELDS:
        require_nonnegative_long(result[key], f"{code}_{key.upper()}")
    cases = result["cases"]
    if any(result[key] > cases for key in (
            "emptyReferenceCases", "hallucinationCases", "completeMissCases")):
        fail(f"{code}_ACCOUNTING_INVALID")
    return result


def validate_binary(value: Any, code: str) -> dict[str, Any]:
    result = require_object(value, BINARY_FIELDS, code)
    for key in BINARY_FIELDS:
        require_nonnegative_long(result[key], f"{code}_{key.upper()}")
    if result["matched"] > result["expected"] or result["matched"] > result["predicted"]:
        fail(f"{code}_ACCOUNTING_INVALID")
    return result


def validate_layout(value: Any, code: str) -> dict[str, Any]:
    result = require_object(value, LAYOUT_FIELDS, code)
    lines = validate_binary(result["lines"], f"{code}_LINES")
    for key in LAYOUT_FIELDS - {"lines"}:
        require_nonnegative_long(result[key], f"{code}_{key.upper()}")
    coverage_limit = checked_bps_product(lines["matched"], f"{code}_COVERAGE")
    if result["centerContainedMatches"] > lines["matched"] \
            or result["observedRegions"] > lines["expected"] \
            or result["predictedCoverageBpsSum"] > coverage_limit \
            or result["goldCoverageBpsSum"] > coverage_limit:
        fail(f"{code}_ACCOUNTING_INVALID")
    return result


def validate_order(value: Any, code: str) -> dict[str, Any]:
    result = require_object(value, ORDER_FIELDS, code)
    for key in ("expectedEdges", "comparableEdges", "correctEdges"):
        require_nonnegative_long(result[key], f"{code}_{key.upper()}")
    require_bool(result["allReferencedRegionsObserved"], f"{code}_ALL_OBSERVED")
    if result["correctEdges"] > result["comparableEdges"] \
            or result["comparableEdges"] > result["expectedEdges"]:
        fail(f"{code}_ACCOUNTING_INVALID")
    return result


def validate_repeat(value: Any, code: str) -> dict[str, Any]:
    result = require_object(value, frozenset(REPEAT_FIELDS), code)
    for key in REPEAT_FIELDS:
        require_nonnegative_long(result[key], f"{code}_{key.upper()}")
    if result["completeGroups"] > result["expectedGroups"] \
            or result["completeItems"] > result["expectedItems"] \
            or result["observableMemberships"] > result["expectedMemberships"]:
        fail(f"{code}_ACCOUNTING_INVALID")
    return result


def validate_confidence(value: Any, code: str) -> dict[str, Any]:
    result = require_object(value, frozenset(CONFIDENCE_FIELDS), code)
    for key in CONFIDENCE_FIELDS:
        require_nonnegative_long(result[key], f"{code}_{key.upper()}")
    bucket_count = checked_sum(
        (result["lowCount"], result["mediumCount"], result["highCount"]),
        f"{code}_BUCKETS",
    )
    native_limit = checked_bps_product(result["observations"], f"{code}_NATIVE_VALUE")
    if bucket_count != result["observations"] or result["nativeValueBpsSum"] > native_limit:
        fail(f"{code}_ACCOUNTING_INVALID")
    return result


def validate_latency(value: Any, code: str) -> dict[str, Any]:
    result = require_object(value, LATENCY_FIELDS, code)
    for key in LATENCY_FIELDS:
        require_nonnegative_long(result[key], f"{code}_{key.upper()}")
    if result["p95Micros"] < result["p50Micros"] \
            or result["count"] == 0 and (result["p50Micros"] != 0 or result["p95Micros"] != 0):
        fail(f"{code}_ACCOUNTING_INVALID")
    return result


def validate_aggregate(value: Any, code: str) -> dict[str, Any]:
    result = require_object(value, AGGREGATE_FIELDS, code)
    case_count = require_nonnegative_long(result["caseCount"], f"{code}_CASE_COUNT")
    ocr = validate_ocr(result["ocr"], f"{code}_OCR")
    layout = validate_layout(result["layout"], f"{code}_LAYOUT")
    order = validate_order(result["order"], f"{code}_ORDER")
    repeat = validate_repeat(result["repeat"], f"{code}_REPEAT")
    confidence = validate_confidence(result["confidence"], f"{code}_CONFIDENCE")
    latency = validate_latency(result["acquisitionLatency"], f"{code}_LATENCY")
    metrics = require_object(result["metricsBps"], METRIC_KEYS, f"{code}_METRICS")
    for key in METRIC_KEYS:
        require_nonnegative_int(metrics[key], f"{code}_METRIC_{key}")
    if ocr["cases"] != case_count or latency["count"] != case_count:
        fail(f"{code}_CASE_ACCOUNTING_INVALID")
    require_same(metrics, metric_summary(ocr, layout, order, repeat, confidence),
                 f"{code}_METRIC_DRIFT")
    return result


def validate_aggregate_map(
        value: Any, keys: set[str] | frozenset[str], code: str,
) -> dict[str, Any]:
    result = require_object(value, keys, code)
    for key in keys:
        validate_aggregate(result[key], f"{code}_{key}")
    return result


def validate_run_shape(value: Any, code: str) -> dict[str, Any]:
    run = require_object(value, RUN_FIELDS, code)
    require_nonnegative_int(run["runOrdinal"], f"{code}_ORDINAL")
    require_nonnegative_int(run["expectedCaseCount"], f"{code}_EXPECTED_CASES")
    require_nonnegative_int(run["observedCaseCount"], f"{code}_OBSERVED_CASES")
    require_bool(run["complete"], f"{code}_COMPLETE")
    if type(run["records"]) is not list:
        fail(f"{code}_RECORDS_CONTRACT_INVALID")
    validate_aggregate(run["global"], f"{code}_GLOBAL")
    validate_aggregate_map(run["partitions"], frozenset(layered.PARTITIONS),
                           f"{code}_PARTITIONS")
    domains = run["domains"]
    if type(domains) is not dict or not domains or any(
            type(key) is not str or DOMAIN_PATTERN.fullmatch(key) is None for key in domains):
        fail(f"{code}_DOMAINS_CONTRACT_INVALID")
    for key, aggregate_value in domains.items():
        validate_aggregate(aggregate_value, f"{code}_DOMAIN_{key}")
    validate_aggregate_map(run["difficulties"], frozenset(layered.DIFFICULTIES),
                           f"{code}_DIFFICULTIES")
    validate_aggregate_map(run["diagnosticSlices"], frozenset(DIAGNOSTIC_SLICES),
                           f"{code}_DIAGNOSTIC_SLICES")
    validate_aggregate_map(run["failureSlices"], frozenset(layered.FAILURE_SLICES),
                           f"{code}_FAILURE_SLICES")
    return run


def validate_report_shape(value: Any) -> dict[str, Any]:
    report = require_object(value, REPORT_FIELDS, "REPORT")
    if type(report["reportVersion"]) is not str or report["reportVersion"] != REPORT_VERSION:
        fail("REPORT_VERSION_INVALID")
    require_string(report["evaluationIdentity"], IDENTITY_PATTERN, "EVALUATION_IDENTITY")
    require_string(report["corpusIdentity"], IDENTITY_PATTERN, "CORPUS_IDENTITY")
    require_string(report["annotationSetIdentity"], IDENTITY_PATTERN, "ANNOTATION_IDENTITY")
    components = require_object(
        report["evaluationComponents"], frozenset(COMPONENT_KEYS), "EVALUATION_COMPONENTS")
    for key, component in components.items():
        if type(component) is not str or not component.strip() or any(
                ord(character) <= 0x1F or 0x7F <= ord(character) <= 0x9F
                for character in component):
            fail(f"EVALUATION_COMPONENT_INVALID:{key}")
    require_bool(report["shadowDiagnostic"], "REPORT_SHADOW_DIAGNOSTIC")
    require_bool(report["certificationEligible"], "REPORT_CERTIFICATION_ELIGIBLE")
    require_nonnegative_int(report["expectedCaseCount"], "REPORT_EXPECTED_CASE_COUNT")
    if type(report["runs"]) is not list or len(report["runs"]) != 2:
        fail("REPORT_RUNS_CONTRACT_INVALID")
    for index, run in enumerate(report["runs"], 1):
        validate_run_shape(run, f"RUN_{index}")

    determinism = require_object(report["determinism"], DETERMINISM_FIELDS, "DETERMINISM")
    for key in ("comparedCases", "metricsEquivalentCases", "observationEquivalentCases"):
        require_nonnegative_int(determinism[key], f"DETERMINISM_{key.upper()}")
    require_bool(determinism["deterministic"], "DETERMINISM_DETERMINISTIC")
    require_string(determinism["verdictCode"], CODE_PATTERN, "DETERMINISM_VERDICT")
    complete = determinism["metricsEquivalentCases"] == determinism["comparedCases"] \
        and determinism["observationEquivalentCases"] == determinism["comparedCases"]
    if determinism["metricsEquivalentCases"] > determinism["comparedCases"] \
            or determinism["observationEquivalentCases"] > determinism["comparedCases"] \
            or determinism["deterministic"] is not complete:
        fail("DETERMINISM_ACCOUNTING_INVALID")

    facts = require_object(report["evidenceFacts"], EVIDENCE_FACT_FIELDS, "EVIDENCE_FACTS")
    for key in EVIDENCE_FACT_FIELDS:
        require_nonnegative_long(facts[key], f"EVIDENCE_FACT_{key.upper()}")
    triggers = require_object(report["triggers"], frozenset(TRIGGERS), "TRIGGERS")
    for key in TRIGGERS:
        trigger = require_object(triggers[key], TRIGGER_FIELDS, f"TRIGGER_{key}")
        required = require_bool(trigger["requiredEvidencePresent"], f"TRIGGER_{key}_REQUIRED")
        triggered = require_bool(trigger["triggered"], f"TRIGGER_{key}_TRIGGERED")
        require_string(trigger["reasonCode"], CODE_PATTERN, f"TRIGGER_{key}_REASON")
        if triggered and not required:
            fail(f"TRIGGER_{key}_ACCOUNTING_INVALID")
    provider = require_object(report["externalProvider"], PROVIDER_FIELDS, "EXTERNAL_PROVIDER")
    for key in PROVIDER_FIELDS:
        require_nonnegative_long(provider[key], f"EXTERNAL_PROVIDER_{key.upper()}")
    return report


def validate_record(record: dict[str, Any], locked: dict[str, Any], diagnostics: list[str]) -> None:
    record = require_object(record, RECORD_FIELDS, "RECORD")
    if type(record["recordVersion"]) is not str or record["recordVersion"] != RECORD_VERSION:
        fail("RECORD_VERSION_INVALID")
    require_string(record["caseId"], CASE_ID_PATTERN, "RECORD_CASE_ID")
    require_string(record["caseIdentity"], IDENTITY_PATTERN, "RECORD_CASE_IDENTITY")
    if type(record["partition"]) is not str or record["partition"] not in layered.PARTITIONS:
        fail("RECORD_PARTITION_INVALID")
    require_string(record["domain"], DOMAIN_PATTERN, "RECORD_DOMAIN")
    if type(record["difficulty"]) is not str or record["difficulty"] not in layered.DIFFICULTIES:
        fail("RECORD_DIFFICULTY_INVALID")
    failures = record["failureSlices"]
    if type(failures) is not list \
            or any(type(item) is not str or item not in layered.FAILURE_SLICES for item in failures) \
            or len(failures) != len(set(failures)):
        fail("RECORD_FAILURE_SLICES_INVALID")
    diagnostic_slices = record["diagnosticSlices"]
    if type(diagnostic_slices) is not list \
            or any(type(item) is not str or item not in DIAGNOSTIC_SLICES
                   for item in diagnostic_slices) \
            or len(diagnostic_slices) != len(set(diagnostic_slices)):
        fail("RECORD_DIAGNOSTIC_SLICES_INVALID")
    for key in ("caseId", "caseIdentity", "partition", "domain", "difficulty", "failureSlices"):
        require_same(record[key], locked[key], "CASE_ASSIGNMENT_DRIFT")
    require_same(diagnostic_slices, diagnostics, "DIAGNOSTIC_ASSIGNMENT_DRIFT")
    validate_ocr(record["ocr"], "RECORD_OCR")
    layout = validate_layout(record["layout"], "RECORD_LAYOUT")
    validate_order(record["order"], "RECORD_ORDER")
    validate_repeat(record["repeat"], "RECORD_REPEAT")
    confidence = validate_confidence(record["confidence"], "RECORD_CONFIDENCE")
    observations = require_nonnegative_int(record["observationCount"], "RECORD_OBSERVATION_COUNT")
    require_nonnegative_long(record["acquisitionMicros"], "RECORD_ACQUISITION_MICROS")
    if observations != layout["lines"]["predicted"] \
            or observations != confidence["observations"]:
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
        if not json_contract.same_json_value(
                metric_view(item), metric_view(second[item["caseId"]])):
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
    if not json_contract.payload_safe(envelope):
        fail("FORBIDDEN_DECODED_PAYLOAD")
    envelope = require_object(
        envelope, frozenset(("envelopeVersion", "reportIdentity", "report")), "ENVELOPE")
    if type(envelope["envelopeVersion"]) is not str \
            or envelope["envelopeVersion"] != ENVELOPE_VERSION:
        fail("ENVELOPE_CONTRACT_INVALID")
    require_string(envelope["reportIdentity"], IDENTITY_PATTERN, "REPORT_IDENTITY")
    report = validate_report_shape(envelope["report"])
    require_same(envelope["reportIdentity"], report_identity(report), "REPORT_IDENTITY_DRIFT")
    components = report["evaluationComponents"]
    expected = expected_components(repository)
    if not json_contract.same_json_value(components, expected) \
            or not json_contract.same_json_value(
                report["evaluationIdentity"], evaluation_identity(expected)):
        fail("EVALUATION_IDENTITY_DRIFT")
    if not json_contract.same_json_value(report["corpusIdentity"], expected["inputSetIdentity"]) \
            or not json_contract.same_json_value(
                report["annotationSetIdentity"], expected["annotationSetIdentity"]):
        fail("CORPUS_AUTHORITY_DRIFT")
    if report["shadowDiagnostic"] is not True or report["certificationEligible"] is not False \
            or not json_contract.same_json_value(report["expectedCaseCount"], 60):
        fail("SHADOW_AUTHORITY_INVALID")
    if not json_contract.same_json_value(
            report["externalProvider"], {"attempts": 0, "reservations": 0, "costMicrosCny": 0}):
        fail("EXTERNAL_PROVIDER_USAGE_NONZERO")

    lock = layered.verify_corpus_lock(repository)
    locked = {str(item["caseId"]): item for item in lock["cases"]}
    diagnostics = diagnostic_assignments(repository)
    runs = report["runs"]
    validated_runs: list[list[dict[str, Any]]] = []
    for ordinal, run in enumerate(runs, 1):
        if not json_contract.same_json_value(run["runOrdinal"], ordinal) \
                or not json_contract.same_json_value(run["expectedCaseCount"], 60) \
                or not json_contract.same_json_value(run["observedCaseCount"], 60) \
                or run["complete"] is not True:
            fail("RUN_CONTRACT_INVALID")
        records = run["records"]
        if len(records) != 60 or any(type(item) is not dict for item in records) \
                or not json_contract.same_json_value(
                    [item.get("caseId") for item in records],
                    [item["caseId"] for item in lock["cases"]]):
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
            if not json_contract.same_json_value(run[key], value):
                fail(f"RUN_{key.upper()}_AGGREGATE_DRIFT")
        validated_runs.append(records)

    first = validated_runs[0]
    second = {item["caseId"]: item for item in validated_runs[1]}
    metrics_equivalent = sum(
        json_contract.same_json_value(metric_view(item), metric_view(second[item["caseId"]]))
        for item in first)
    determinism = report["determinism"]
    expected_determinism = {
        "comparedCases": 60,
        "metricsEquivalentCases": metrics_equivalent,
        "observationEquivalentCases": 60,
        "deterministic": True,
        "verdictCode": "DETERMINISTIC_TWO_RUNS",
    }
    if not json_contract.same_json_value(determinism, expected_determinism) \
            or metrics_equivalent != 60:
        fail("DETERMINISM_VERDICT_DRIFT")
    facts = evidence_facts(first, second)
    if not json_contract.same_json_value(report["evidenceFacts"], facts):
        fail("EVIDENCE_FACTS_DRIFT")
    triggers = report["triggers"]
    expected_trigger = {
        "requiredEvidencePresent": False, "triggered": False,
        "reasonCode": "NOT_TRIGGERED_EVIDENCE_ABSENT",
    }
    if not json_contract.same_json_value(
            triggers, {key: expected_trigger for key in TRIGGERS}):
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


def verify_bytes(raw: bytes, repository: pathlib.Path) -> VerifiedShadowReport:
    if len(raw) == 0 or len(raw) > 16 * 1024 * 1024:
        fail("REPORT_BYTES_INVALID")
    text = raw.decode("utf-8", errors="strict")
    layered.scan_payload_free(text, "rapidocr-shadow-report")
    value = layered.parse_strict_json(text)
    if not isinstance(value, dict):
        fail("ENVELOPE_NOT_OBJECT")
    if not json_contract.payload_safe(value):
        fail("FORBIDDEN_DECODED_PAYLOAD")
    summary = verify_envelope(value, repository)
    if summary["reportIdentity"] != value["reportIdentity"]:
        fail("REPORT_SNAPSHOT_IDENTITY_DRIFT")
    return VerifiedShadowReport(summary, value, hashlib.sha256(raw).hexdigest())


def load_verified_file(path: pathlib.Path, repository: pathlib.Path) -> VerifiedShadowReport:
    return verify_bytes(path.read_bytes(), repository)


def verify_file(path: pathlib.Path, repository: pathlib.Path) -> dict[str, Any]:
    return load_verified_file(path, repository).summary


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
