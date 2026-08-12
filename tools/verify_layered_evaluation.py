#!/usr/bin/env python3
"""Dependency-free, payload-safe verifier for RenderWeave layered evaluation reports."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
from typing import Any, Iterable, Sequence


ENVELOPE_VERSION = "renderweave-layered-report-envelope/1.0"
REPORT_VERSION = "renderweave-layered-evaluation-report/1.0"
RECORD_VERSION = "renderweave-layered-evaluation-record/1.0"
EVALUATION_VERSION = "renderweave-layered-evaluation/1.0"
RECORD_SET_VERSION = "renderweave-layered-record-set/1.0"
VERIFIER_VERSION = "renderweave-layered-python-verifier/1.0"
RECOMPUTED_METRICS_VERSION = "renderweave-layered-recomputed-metrics/1.0"
CASE_ASSIGNMENT_VERSION = "renderweave-layered-case-assignment/1.0"

REGION_KINDS = ("TITLE", "SLOT", "GROUP", "REPEATED_GROUP", "ITEM")
PARTITIONS = ("DEV", "HOLDOUT")
DIFFICULTIES = (
    "BASELINE", "DENSE_TEXT", "MULTI_COLUMN", "REPEATED_LIST", "PROMPT_INJECTION",
    "LOW_CONTRAST", "NOISY",
)
FAILURE_SLICES = (
    "DENSE_TEXT", "MULTI_COLUMN", "REPEATED_LIST", "PROMPT_INJECTION", "OCR_MISS",
    "LAYOUT_MISS", "ORDER_ERROR", "REPEAT_ERROR", "SEMANTIC_ERROR", "CANDIDATE_ERROR",
    "RECOVERY",
)
STAGES = ("ACQUISITION", "HIERARCHY", "ELEMENT_BINDING", "CANDIDATE")
RECOVERY_CODES = ("NONE", "FIXED_RETRY", "LEASE_RECOVERY")
EVALUATION_COMPONENT_KEYS = (
    "inputSetIdentity", "annotationVersion", "annotationSetIdentity",
    "normalizationRenderIdentity", "observationSuccessorIdentity",
    "observationContractIdentity", "acquisitionPolicyIdentity", "adapterIdentity",
    "weightIdentity", "projectionIdentity", "orderIdentity", "shapeCatalogIdentity",
    "providerProfileReplayIdentity", "promptIdentity", "validatorIdentity",
    "materializerIdentity", "evaluatorIdentity", "budgetIdentity", "decodingModeIdentity",
)
SHA_IDENTITY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+/-]{1,160}:[0-9a-f]{64}$")
CASE_ID = re.compile(r"^[a-z][a-z0-9-]{0,127}$")
CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")
DOMAIN = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
MAXIMUM_REPORT_BYTES = 16 * 1024 * 1024
FORBIDDEN_PAYLOAD_MARKERS = (
    '"ocrText"', '"text"', '"image"', '"imageBytes"', '"prompt"', '"promptText"',
    '"providerRequest"', '"providerResponse"', '"modelOutput"', '"candidateJson"',
    '"boundingBox"', '"polygon"', '"rootDocument"', '"base64"', '"chainOfThought"',
    "data:image", "ignore prior instructions", "summer night", "bearer ",
)


class VerificationError(Exception):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def _reject_duplicate(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("DUPLICATE_JSON_MEMBER")
        result[key] = value
    return result


def _reject_non_integer(_value: str) -> Any:
    fail("NON_INTEGER_JSON_NUMBER")


def parse_strict_json(raw: str) -> Any:
    try:
        return json.loads(
            raw,
            object_pairs_hook=_reject_duplicate,
            parse_float=_reject_non_integer,
            parse_constant=_reject_non_integer,
        )
    except VerificationError:
        raise
    except (json.JSONDecodeError, UnicodeError, ValueError):
        fail("INVALID_JSON")


def canonical_json(value: Any) -> bytes:
    try:
        return json.dumps(
            value, ensure_ascii=False, allow_nan=False, separators=(",", ":"), sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeError):
        fail("CANONICAL_JSON_FAILED")


def scan_payload_free(raw: str, source: str) -> None:
    lowered = raw.lower()
    for marker in FORBIDDEN_PAYLOAD_MARKERS:
        if marker.lower() in lowered:
            fail(f"FORBIDDEN_PAYLOAD:{source}")


def _hash_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _length_prefixed_hash(values: Iterable[str], prefix: str | None = None) -> str:
    digest = hashlib.sha256()
    if prefix is not None:
        digest.update(prefix.encode("utf-8"))
        digest.update(b"\n")
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def evaluation_identity(components: dict[str, Any]) -> str:
    require_keys(components, EVALUATION_COMPONENT_KEYS, "EVALUATION_COMPONENTS")
    values: list[str] = []
    for key in EVALUATION_COMPONENT_KEYS:
        value = require_string(components[key], f"EVALUATION_COMPONENT:{key}", 1, 256)
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+/:=-]{0,255}", value):
            fail(f"EVALUATION_COMPONENT_INVALID:{key}")
        values.append(value)
    return f"{EVALUATION_VERSION}:{_length_prefixed_hash(values, EVALUATION_VERSION)}"


def record_identity(record: dict[str, Any]) -> str:
    return f"{RECORD_VERSION}:{_hash_bytes(canonical_json(record))}"


def record_set_identity(entries: Sequence[dict[str, Any]]) -> str:
    identities = [require_string(item["recordIdentity"], "RECORD_IDENTITY") for item in entries]
    return f"{RECORD_SET_VERSION}:{_length_prefixed_hash(identities)}"


def report_identity(report: dict[str, Any]) -> str:
    return f"{REPORT_VERSION}:{_hash_bytes(canonical_json(report))}"


def edit_counts(reference: Sequence[Any], prediction: Sequence[Any]) -> tuple[int, int, int, int, int]:
    reference = list(reference)
    prediction = list(prediction)
    rows = len(reference) + 1
    columns = len(prediction) + 1
    distance = [[0] * columns for _ in range(rows)]
    for row in range(rows):
        distance[row][0] = row
    for column in range(columns):
        distance[0][column] = column
    for row in range(1, rows):
        for column in range(1, columns):
            substitution = distance[row - 1][column - 1]
            if reference[row - 1] != prediction[column - 1]:
                substitution += 1
            distance[row][column] = min(
                substitution, distance[row][column - 1] + 1, distance[row - 1][column] + 1,
            )
    substitutions = 0
    insertions = 0
    deletions = 0
    row = len(reference)
    column = len(prediction)
    while row > 0 or column > 0:
        if (row > 0 and column > 0 and reference[row - 1] == prediction[column - 1]
                and distance[row][column] == distance[row - 1][column - 1]):
            row -= 1
            column -= 1
        elif row > 0 and column > 0 and distance[row][column] == distance[row - 1][column - 1] + 1:
            substitutions += 1
            row -= 1
            column -= 1
        elif column > 0 and distance[row][column] == distance[row][column - 1] + 1:
            insertions += 1
            column -= 1
        else:
            deletions += 1
            row -= 1
    return len(reference), len(prediction), substitutions, insertions, deletions


def edit_rate_bps(counts: tuple[int, int, int, int, int]) -> int:
    reference, _prediction, substitutions, insertions, deletions = counts
    errors = substitutions + insertions + deletions
    if reference == 0:
        return 0 if errors == 0 else 10_000
    return errors * 10_000 // reference


def iou_bps(expected: tuple[int, int, int, int], predicted: tuple[int, int, int, int]) -> int:
    validate_box(expected)
    validate_box(predicted)
    intersection_width = max(0, min(expected[2], predicted[2]) - max(expected[0], predicted[0]))
    intersection_height = max(0, min(expected[3], predicted[3]) - max(expected[1], predicted[1]))
    intersection = intersection_width * intersection_height
    expected_area = (expected[2] - expected[0]) * (expected[3] - expected[1])
    predicted_area = (predicted[2] - predicted[0]) * (predicted[3] - predicted[1])
    union = expected_area + predicted_area - intersection
    return 0 if union == 0 else intersection * 10_000 // union


def detection_score(
        expected: Sequence[tuple[str, str, tuple[int, int, int, int], int]],
        predicted: Sequence[tuple[str, str, tuple[int, int, int, int], int]],
) -> dict[str, Any]:
    expected = list(expected)
    predicted = list(predicted)
    require_distinct([item[0] for item in expected], "DUPLICATE_GOLD_DETECTION")
    require_distinct([item[0] for item in predicted], "DUPLICATE_PREDICTED_DETECTION")
    if not expected and not predicted:
        return {
            "expected": 0, "predicted": 0, "matchedByIouThreshold": [0] * 10,
            "matchedAtIou50": 0, "matchedIouBpsSum": 0, "ap5095Bps": 10_000,
        }
    ordered = sorted(predicted, key=lambda item: (-item[3], item[0]))
    threshold_matches: list[int] = []
    ap_sum = 0
    matched_at_50 = 0
    matched_iou_sum = 0
    for threshold_index in range(10):
        threshold = 5_000 + threshold_index * 500
        matched, iou_sum, true_positive = _match_detections(expected, ordered, threshold)
        threshold_matches.append(matched)
        ap_sum += _interpolated_ap_bps(len(expected), true_positive)
        if threshold_index == 0:
            matched_at_50 = matched
            matched_iou_sum = iou_sum
    return {
        "expected": len(expected), "predicted": len(predicted),
        "matchedByIouThreshold": threshold_matches,
        "matchedAtIou50": matched_at_50, "matchedIouBpsSum": matched_iou_sum,
        "ap5095Bps": ap_sum // 10,
    }


def _match_detections(
        expected: list[tuple[str, str, tuple[int, int, int, int], int]],
        predicted: list[tuple[str, str, tuple[int, int, int, int], int]],
        threshold: int,
) -> tuple[int, int, list[bool]]:
    used = [False] * len(expected)
    true_positive: list[bool] = []
    matched = 0
    iou_sum = 0
    for actual in predicted:
        best_index = -1
        best_iou = -1
        for index, gold in enumerate(expected):
            if used[index] or gold[1] != actual[1]:
                continue
            overlap = iou_bps(gold[2], actual[2])
            if overlap >= threshold and (overlap > best_iou
                    or (overlap == best_iou and best_index >= 0 and gold[0] < expected[best_index][0])):
                best_index = index
                best_iou = overlap
        if best_index >= 0:
            used[best_index] = True
            matched += 1
            iou_sum += best_iou
            true_positive.append(True)
        else:
            true_positive.append(False)
    return matched, iou_sum, true_positive


def _interpolated_ap_bps(expected: int, true_positive: Sequence[bool]) -> int:
    if expected == 0:
        return 10_000 if not true_positive else 0
    if not true_positive:
        return 0
    cumulative: list[int] = []
    count = 0
    for value in true_positive:
        if value:
            count += 1
        cumulative.append(count)
    total = 0
    for recall_percent in range(101):
        best_precision = 0
        for index, matched in enumerate(cumulative):
            if matched * 100 >= recall_percent * expected:
                best_precision = max(best_precision, matched * 10_000 // (index + 1))
        total += best_precision
    return total // 101


def set_counts(expected: Sequence[str], predicted: Sequence[str]) -> dict[str, int]:
    expected = list(expected)
    predicted = list(predicted)
    require_distinct(expected, "DUPLICATE_EXPECTED_MEMBER")
    require_distinct(predicted, "DUPLICATE_PREDICTED_MEMBER")
    expected_set = set(expected)
    return {
        "expected": len(expected), "predicted": len(predicted),
        "matched": sum(1 for value in predicted if value in expected_set),
    }


def precision_bps(counts: dict[str, int]) -> int:
    actual = counts["predicted"]
    if actual == 0:
        return 10_000 if counts["expected"] == 0 else 0
    return counts["matched"] * 10_000 // actual


def recall_bps(counts: dict[str, int]) -> int:
    return 10_000 if counts["expected"] == 0 else counts["matched"] * 10_000 // counts["expected"]


def f1_bps(counts: dict[str, int]) -> int:
    denominator = counts["expected"] + counts["predicted"]
    return 10_000 if denominator == 0 else 2 * counts["matched"] * 10_000 // denominator


def repeat_score(
    expected: Sequence[tuple[str, str, str]],
    predicted: Sequence[tuple[str, str, str]],
    expected_item_count: int,
    predicted_item_count: int,
) -> dict[str, Any]:
    if (
        type(expected_item_count) is not int
        or type(predicted_item_count) is not int
        or expected_item_count < 0
        or predicted_item_count < 0
    ):
        fail("ITEM_COUNT_INVALID")

    def membership_keys(values: Sequence[tuple[str, str, str]]) -> list[str]:
        keys: list[str] = []
        for value in values:
            if (
                not isinstance(value, tuple)
                or len(value) != 3
                or any(type(part) is not str or not part for part in value)
            ):
                fail("MEMBERSHIP_INVALID")
            keys.append(">".join(value))
        return keys

    return {
        "itemCountAbsoluteError": abs(expected_item_count - predicted_item_count),
        "memberships": set_counts(membership_keys(expected), membership_keys(predicted)),
    }


def has_cycle(edges: Sequence[str]) -> bool:
    edges = list(edges)
    require_distinct(edges, "DUPLICATE_GRAPH_EDGE")
    graph: dict[str, list[str]] = {}
    indegree: dict[str, int] = {}
    for edge in edges:
        parts = edge.split(">")
        if len(parts) != 2 or not parts[0] or not parts[1] or parts[0] == parts[1]:
            fail("GRAPH_EDGE_INVALID")
        indegree.setdefault(parts[0], 0)
        indegree[parts[1]] = indegree.get(parts[1], 0) + 1
        graph.setdefault(parts[0], []).append(parts[1])
    queue = [node for node, degree in indegree.items() if degree == 0]
    visited = 0
    while queue:
        node = queue.pop(0)
        visited += 1
        for child in graph.get(node, []):
            indegree[child] -= 1
            if indegree[child] == 0:
                queue.append(child)
    return visited != len(indegree)


def tree_edit_distance(expected: Sequence[str], predicted: Sequence[str]) -> int:
    expected = list(expected)
    predicted = list(predicted)
    require_distinct(expected, "DUPLICATE_EXPECTED_TREE_EDGE")
    require_distinct(predicted, "DUPLICATE_PREDICTED_TREE_EDGE")
    return len(set(expected) - set(predicted)) + len(set(predicted) - set(expected))


def expected_calibration_error_bps(bins: Sequence[dict[str, int]]) -> int:
    total = sum(item["count"] for item in bins)
    if total == 0:
        return 0
    weighted = 0
    for item in bins:
        if item["count"] == 0:
            continue
        confidence = item["confidenceBpsSum"] // item["count"]
        accuracy = item["correct"] * 10_000 // item["count"]
        weighted += item["count"] * abs(confidence - accuracy)
    return weighted // total


def brier_score_bps(bins: Sequence[dict[str, int]]) -> int:
    total = sum(item["count"] for item in bins)
    return 0 if total == 0 else sum(item["squaredErrorBpsSum"] for item in bins) // total


def aggregate_records(records: Sequence[dict[str, Any]]) -> dict[str, Any]:
    records = list(records)
    ocr_keys = (
        "cases", "referenceCharacters", "predictedCharacters", "characterSubstitutions",
        "characterInsertions", "characterDeletions", "referenceWords", "predictedWords",
        "wordSubstitutions", "wordInsertions", "wordDeletions", "emptyReferenceCases",
        "hallucinationCases", "completeMissCases",
    )
    ocr = {key: sum(int(item["ocr"][key]) for item in records) for key in ocr_keys}
    by_kind = {
        kind: _aggregate_detection([item["layout"]["byKind"][kind] for item in records])
        for kind in REGION_KINDS
    }
    layout = {
        "byKind": by_kind,
        "evidence": _aggregate_binary([item["layout"]["evidence"] for item in records]),
        "falseEvidence": sum(int(item["layout"]["falseEvidence"]) for item in records),
    }
    order = {
        "precedenceEdges": _aggregate_binary([item["order"]["precedenceEdges"] for item in records]),
        "cycleCases": sum(int(item["order"]["cycleCases"]) for item in records),
        "evaluatedCases": sum(int(item["order"]["evaluatedCases"]) for item in records),
    }
    repeat = {
        "groups": _aggregate_binary([item["repeat"]["groups"] for item in records]),
        "items": _aggregate_binary([item["repeat"]["items"] for item in records]),
        "itemCountAbsoluteError": sum(int(item["repeat"]["itemCountAbsoluteError"]) for item in records),
        "memberships": _aggregate_binary([item["repeat"]["memberships"] for item in records]),
    }
    semantic = {
        key: _aggregate_binary([item["semantic"][key] for item in records])
        for key in ("slots", "groups", "entities", "relationships", "cardinalities", "bindings",
                    "ownerContainment")
    }
    semantic["survival"] = {
        key: sum(int(item["semantic"]["survival"][key]) for item in records)
        for key in ("expectedSlots", "observedSlots", "boundSlots", "candidateSlots")
    }
    semantic["repairAttempts"] = sum(int(item["semantic"]["repairAttempts"]) for item in records)
    semantic["repairSuccesses"] = sum(int(item["semantic"]["repairSuccesses"]) for item in records)
    candidate = {
        key: sum(int(item["candidate"][key]) for item in records)
        for key in (
            "evaluatedCases", "contractValidCases", "dagValidCases", "criticalHallucinations",
            "blockers", "topologyExpectedCases", "topologyPreservedCases",
        )
    }
    candidate.update({
        key: _aggregate_binary([item["candidate"][key] for item in records])
        for key in ("entities", "fields", "relationships", "supportedTypes", "evidence")
    })
    bins = []
    for index in range(10):
        bins.append({
            "binIndex": index,
            "count": sum(int(item["calibration"]["bins"][index]["count"]) for item in records),
            "correct": sum(int(item["calibration"]["bins"][index]["correct"]) for item in records),
            "confidenceBpsSum": sum(
                int(item["calibration"]["bins"][index]["confidenceBpsSum"]) for item in records
            ),
            "squaredErrorBpsSum": sum(
                int(item["calibration"]["bins"][index]["squaredErrorBpsSum"]) for item in records
            ),
        })
    calibration = {
        "bins": bins,
        "unresolved": _aggregate_binary([item["calibration"]["unresolved"] for item in records]),
        "reviewRequiredReachedCases": sum(
            int(item["calibration"]["reviewRequiredReachedCases"]) for item in records
        ),
        "successfulCases": sum(int(item["calibration"]["successfulCases"]) for item in records),
        "evaluatedCases": sum(int(item["calibration"]["evaluatedCases"]) for item in records),
    }
    latency = {}
    for stage in STAGES:
        values = sorted(
            int(item["runtime"]["latencyMicros"][stage]) for item in records
            if stage in item["runtime"]["latencyMicros"]
        )
        latency[stage] = {
            "count": len(values), "p50Micros": _percentile(values, 50),
            "p95Micros": _percentile(values, 95),
        }
    runtime = {
        key: sum(int(item["runtime"][key]) for item in records)
        for key in (
            "scriptedCalls", "inputTokens", "outputTokens", "estimatedCostMicrosCny",
            "settledCostMicrosCny", "recoveryCount", "acceptedStageReplayCount",
            "providerAttempts", "providerReservations", "externalProviderCostMicrosCny",
        )
    }
    runtime["latency"] = latency
    runtime["recoveryCodes"] = {
        code: sum(1 for item in records if item["runtime"]["recoveryCode"] == code)
        for code in RECOVERY_CODES
    }
    metrics = metric_summary(ocr, layout, order, repeat, semantic, candidate, calibration)
    return {
        "caseCount": len(records), "ocr": ocr, "layout": layout, "order": order,
        "repeat": repeat, "semantic": semantic, "candidate": candidate,
        "calibration": calibration, "runtime": runtime, "metricsBps": metrics,
    }


def _aggregate_binary(values: Sequence[dict[str, Any]]) -> dict[str, int]:
    return {
        key: sum(int(item[key]) for item in values)
        for key in ("expected", "predicted", "matched")
    }


def _aggregate_detection(values: Sequence[dict[str, Any]]) -> dict[str, Any]:
    return {
        "expected": sum(int(item["expected"]) for item in values),
        "predicted": sum(int(item["predicted"]) for item in values),
        "matchedByIouThreshold": [
            sum(int(item["matchedByIouThreshold"][index]) for item in values) for index in range(10)
        ],
        "semanticallyMatched": sum(int(item["semanticallyMatched"]) for item in values),
        "matchedIouBpsSum": sum(int(item["matchedIouBpsSum"]) for item in values),
        "ap5095BpsSum": sum(int(item["ap5095BpsSum"]) for item in values),
        "evaluatedCases": sum(int(item["evaluatedCases"]) for item in values),
    }


def _percentile(sorted_values: Sequence[int], percentile: int) -> int:
    if not sorted_values:
        return 0
    index = max(0, (len(sorted_values) * percentile + 99) // 100 - 1)
    return int(sorted_values[index])


def metric_summary(
        ocr: dict[str, Any], layout: dict[str, Any], order: dict[str, Any],
        repeat: dict[str, Any], semantic: dict[str, Any], candidate: dict[str, Any],
        calibration: dict[str, Any],
) -> dict[str, int]:
    result: dict[str, int] = {
        "ocr.cer": _edit_rate_from_stats(ocr, "referenceCharacters", (
            "characterSubstitutions", "characterInsertions", "characterDeletions",
        )),
        "ocr.wer": _edit_rate_from_stats(ocr, "referenceWords", (
            "wordSubstitutions", "wordInsertions", "wordDeletions",
        )),
        "ocr.emptyReferenceInsertionRate": _error_rate(
            int(ocr["emptyReferenceCases"]), int(ocr["cases"])),
        "ocr.hallucinationRate": _error_rate(int(ocr["hallucinationCases"]), int(ocr["cases"])),
        "ocr.completeMissRate": _error_rate(int(ocr["completeMissCases"]), int(ocr["cases"])),
    }
    for kind in REGION_KINDS:
        detection = layout["byKind"][kind]
        counts = {
            "expected": int(detection["expected"]), "predicted": int(detection["predicted"]),
            "matched": int(detection["semanticallyMatched"]),
        }
        prefix = f"layout.{kind}."
        result[prefix + "precision"] = precision_bps(counts)
        result[prefix + "recall"] = recall_bps(counts)
        evaluated = int(detection["evaluatedCases"])
        result[prefix + "ap5095"] = 10_000 if evaluated == 0 else int(detection["ap5095BpsSum"]) // evaluated
        matched = int(detection["semanticallyMatched"])
        result[prefix + "meanMatchedIou"] = (
            0 if matched == 0 else int(detection["matchedIouBpsSum"]) // matched
        )
    result["layout.evidenceRecall"] = recall_bps(layout["evidence"])
    result["layout.falseEvidenceRate"] = _error_rate(
        int(layout["falseEvidence"]), int(layout["evidence"]["predicted"]),
    )
    result["order.precision"] = precision_bps(order["precedenceEdges"])
    result["order.recall"] = recall_bps(order["precedenceEdges"])
    result["order.f1"] = f1_bps(order["precedenceEdges"])
    result["order.cycleRate"] = _error_rate(int(order["cycleCases"]), int(order["evaluatedCases"]))
    result["repeat.groupRecall"] = recall_bps(repeat["groups"])
    result["repeat.itemRecall"] = recall_bps(repeat["items"])
    result["repeat.membershipAccuracy"] = f1_bps(repeat["memberships"])
    result["semantic.slotRecall"] = recall_bps(semantic["slots"])
    result["semantic.groupRecall"] = recall_bps(semantic["groups"])
    result["semantic.entityF1"] = f1_bps(semantic["entities"])
    result["semantic.relationshipF1"] = f1_bps(semantic["relationships"])
    result["semantic.cardinalityAccuracy"] = recall_bps(semantic["cardinalities"])
    result["semantic.bindingAccuracy"] = recall_bps(semantic["bindings"])
    result["semantic.ownerContainment"] = recall_bps(semantic["ownerContainment"])
    survival = semantic["survival"]
    result["semantic.observationSurvival"] = _success_rate(
        int(survival["observedSlots"]), int(survival["expectedSlots"]),
    )
    result["semantic.bindingSurvival"] = _success_rate(
        int(survival["boundSlots"]), int(survival["expectedSlots"]),
    )
    result["semantic.candidateSurvival"] = _success_rate(
        int(survival["candidateSlots"]), int(survival["expectedSlots"]),
    )
    result["semantic.repairYield"] = _success_rate(
        int(semantic["repairSuccesses"]), int(semantic["repairAttempts"]),
    )
    result["candidate.contractValidity"] = _success_rate(
        int(candidate["contractValidCases"]), int(candidate["evaluatedCases"]),
    )
    result["candidate.entityF1"] = f1_bps(candidate["entities"])
    result["candidate.fieldF1"] = f1_bps(candidate["fields"])
    result["candidate.relationshipF1"] = f1_bps(candidate["relationships"])
    result["candidate.supportedTypeAccuracy"] = recall_bps(candidate["supportedTypes"])
    result["candidate.evidenceCoverage"] = recall_bps(candidate["evidence"])
    result["candidate.dagValidity"] = _success_rate(
        int(candidate["dagValidCases"]), int(candidate["evaluatedCases"]),
    )
    result["candidate.topologyPreservation"] = _success_rate(
        int(candidate["topologyPreservedCases"]), int(candidate["topologyExpectedCases"]),
    )
    result["calibration.ece"] = expected_calibration_error_bps(calibration["bins"])
    result["calibration.brier"] = brier_score_bps(calibration["bins"])
    result["calibration.unresolvedPrecision"] = precision_bps(calibration["unresolved"])
    result["calibration.reviewRequiredReachability"] = _success_rate(
        int(calibration["reviewRequiredReachedCases"]), int(calibration["evaluatedCases"]),
    )
    result["calibration.success"] = _success_rate(
        int(calibration["successfulCases"]), int(calibration["evaluatedCases"]),
    )
    return dict(sorted(result.items()))


def _edit_rate_from_stats(ocr: dict[str, Any], reference_key: str, error_keys: Sequence[str]) -> int:
    reference = int(ocr[reference_key])
    errors = sum(int(ocr[key]) for key in error_keys)
    if reference == 0:
        return 0 if errors == 0 else 10_000
    return errors * 10_000 // reference


def _success_rate(numerator: int, denominator: int) -> int:
    return 10_000 if denominator == 0 else numerator * 10_000 // denominator


def _error_rate(numerator: int, denominator: int) -> int:
    return 0 if denominator == 0 else numerator * 10_000 // denominator


def verify_envelope(envelope: Any) -> dict[str, Any]:
    envelope = require_object(envelope, "ENVELOPE")
    require_keys(envelope, ("envelopeVersion", "reportIdentity", "report"), "ENVELOPE")
    if envelope["envelopeVersion"] != ENVELOPE_VERSION:
        fail("ENVELOPE_VERSION_INVALID")
    report = require_object(envelope["report"], "REPORT")
    expected_report_identity = report_identity(report)
    if envelope["reportIdentity"] != expected_report_identity:
        fail("REPORT_IDENTITY_DRIFT")
    _validate_report_shape(report)
    components = require_object(report["evaluationComponents"], "EVALUATION_COMPONENTS")
    if report["evaluationIdentity"] != evaluation_identity(components):
        fail("EVALUATION_IDENTITY_DRIFT")
    if report["corpusIdentity"] != components["inputSetIdentity"]:
        fail("CORPUS_IDENTITY_DRIFT")
    if report["annotationSetIdentity"] != components["annotationSetIdentity"]:
        fail("ANNOTATION_IDENTITY_DRIFT")
    if not re.fullmatch(
        r"renderweave-zero-provider-budget/1\.0:[0-9a-f]{64}",
        components["budgetIdentity"],
    ):
        fail("BUDGET_IDENTITY_INVALID")

    entries = require_list(report["records"], "RECORDS")
    if len(entries) != 60:
        fail("RECORD_COUNT_INVALID")
    records: list[dict[str, Any]] = []
    case_ids: set[str] = set()
    case_identities: set[str] = set()
    record_identities: set[str] = set()
    for index, raw_entry in enumerate(entries):
        entry = require_object(raw_entry, f"RECORD_ENTRY:{index}")
        require_keys(entry, ("recordIdentity", "record"), f"RECORD_ENTRY:{index}")
        record = require_object(entry["record"], f"RECORD:{index}")
        validate_record(record)
        actual_identity = require_identity(entry["recordIdentity"], f"RECORD_IDENTITY:{index}")
        if actual_identity != record_identity(record):
            fail("RECORD_IDENTITY_DRIFT")
        case_id = require_string(record["caseId"], "CASE_ID")
        case_identity = require_identity(record["caseIdentity"], "CASE_IDENTITY")
        if case_id in case_ids or case_identity in case_identities or actual_identity in record_identities:
            fail("DUPLICATE_RECORD_IDENTITY")
        case_ids.add(case_id)
        case_identities.add(case_identity)
        record_identities.add(actual_identity)
        records.append(record)
    if report["recordSetIdentity"] != record_set_identity(entries):
        fail("RECORD_SET_IDENTITY_DRIFT")

    if (
        report["expectedCaseCount"] != 60
        or report["observedCaseCount"] != 60
        or report["complete"] is not True
        or report["missingCaseIds"] != []
    ):
        fail("REPORT_COMPLETENESS_INVALID")
    partition_counts = {partition: sum(1 for item in records if item["partition"] == partition)
                        for partition in PARTITIONS}
    if partition_counts != {"DEV": 45, "HOLDOUT": 15}:
        fail("PARTITION_ACCOUNTING_INVALID")
    domain_counts = {domain: sum(1 for item in records if item["domain"] == domain)
                     for domain in sorted({str(item["domain"]) for item in records})}
    if domain_counts != {"generic": 55, "transit-board": 5}:
        fail("DOMAIN_ACCOUNTING_INVALID")

    recomputed_global = aggregate_records(records)
    _require_equal(report["global"], recomputed_global, "GLOBAL_AGGREGATE_DRIFT")
    recomputed_partitions = _verify_slices(
        report["partitions"], PARTITIONS, records,
        lambda item, value: item["partition"] == value, "PARTITION",
    )
    recomputed_domains = _verify_slices(
        report["domains"], tuple(domain_counts), records,
        lambda item, value: item["domain"] == value, "DOMAIN",
    )
    recomputed_difficulties = _verify_slices(
        report["difficulties"], DIFFICULTIES, records,
        lambda item, value: item["difficulty"] == value, "DIFFICULTY",
    )
    recomputed_failures = _verify_slices(
        report["failureSlices"], FAILURE_SLICES, records,
        lambda item, value: value in item["failureSlices"], "FAILURE",
    )

    recomputed_metrics = {
        "global": recomputed_global,
        "partitions": recomputed_partitions,
        "domains": recomputed_domains,
        "difficulties": recomputed_difficulties,
        "failureSlices": recomputed_failures,
    }
    metrics_identity = (
        f"{RECOMPUTED_METRICS_VERSION}:"
        f"{_hash_bytes(canonical_json(recomputed_metrics))}"
    )
    assignments = [
        {
            "caseId": item["caseId"],
            "caseIdentity": item["caseIdentity"],
            "partition": item["partition"],
            "domain": item["domain"],
            "difficulty": item["difficulty"],
            "failureSlices": item["failureSlices"],
        }
        for item in records
    ]
    assignment_identity = (
        f"{CASE_ASSIGNMENT_VERSION}:"
        f"{_hash_bytes(canonical_json(assignments))}"
    )

    serialized = canonical_json(envelope).decode("utf-8")
    scan_payload_free(serialized, "REPORT")
    global_runtime = report["global"]["runtime"]
    for key in (
        "estimatedCostMicrosCny", "settledCostMicrosCny", "providerAttempts",
        "providerReservations", "externalProviderCostMicrosCny",
    ):
        if global_runtime[key] != 0:
            fail("EXTERNAL_PROVIDER_USAGE_NONZERO")
    return {
        "verifierVersion": VERIFIER_VERSION,
        "result": "PASS",
        "reportIdentity": expected_report_identity,
        "evaluationIdentity": report["evaluationIdentity"],
        "corpusIdentity": report["corpusIdentity"],
        "annotationSetIdentity": report["annotationSetIdentity"],
        "recordSetIdentity": report["recordSetIdentity"],
        "caseAssignmentIdentity": assignment_identity,
        "recomputedMetricsIdentity": metrics_identity,
        "caseCount": 60,
        "partitions": partition_counts,
        "domains": domain_counts,
        "difficulties": {
            value: sum(1 for item in records if item["difficulty"] == value)
            for value in DIFFICULTIES
        },
        "failureSlices": {
            value: sum(1 for item in records if value in item["failureSlices"])
            for value in FAILURE_SLICES
        },
        "metricCount": len(report["global"]["metricsBps"]),
        "sliceAggregateCount": (
            len(recomputed_partitions) + len(recomputed_domains)
            + len(recomputed_difficulties) + len(recomputed_failures)
        ),
        "providerAttempts": 0,
        "providerReservations": 0,
        "externalProviderCostMicrosCny": 0,
    }


def _validate_report_shape(report: dict[str, Any]) -> None:
    require_keys(report, (
        "reportVersion", "evaluationIdentity", "evaluationComponents", "corpusIdentity",
        "annotationSetIdentity", "recordSetIdentity", "expectedCaseCount", "observedCaseCount",
        "complete", "missingCaseIds", "records", "global", "partitions", "domains",
        "difficulties", "failureSlices",
    ), "REPORT")
    if report["reportVersion"] != REPORT_VERSION:
        fail("REPORT_VERSION_INVALID")
    require_identity(report["evaluationIdentity"], "EVALUATION_IDENTITY")
    require_identity(report["corpusIdentity"], "CORPUS_IDENTITY")
    require_identity(report["annotationSetIdentity"], "ANNOTATION_SET_IDENTITY")
    require_identity(report["recordSetIdentity"], "RECORD_SET_IDENTITY")
    require_int(report["expectedCaseCount"], "EXPECTED_CASE_COUNT", 0)
    require_int(report["observedCaseCount"], "OBSERVED_CASE_COUNT", 0)
    if type(report["complete"]) is not bool:
        fail("REPORT_COMPLETE_INVALID")
    require_list(report["missingCaseIds"], "MISSING_CASE_IDS")
    for key in ("global", "partitions", "domains", "difficulties", "failureSlices"):
        require_object(report[key], key.upper())


def validate_record(record: dict[str, Any]) -> None:
    require_keys(record, (
        "recordVersion", "caseId", "caseIdentity", "partition", "domain", "difficulty",
        "failureSlices", "outcomeCode", "ocr", "layout", "order", "repeat", "semantic",
        "candidate", "calibration", "runtime",
    ), "RECORD")
    if record["recordVersion"] != RECORD_VERSION:
        fail("RECORD_VERSION_INVALID")
    case_id = require_string(record["caseId"], "CASE_ID")
    if not CASE_ID.fullmatch(case_id):
        fail("CASE_ID_INVALID")
    require_identity(record["caseIdentity"], "CASE_IDENTITY")
    if record["partition"] not in PARTITIONS:
        fail("PARTITION_INVALID")
    domain = require_string(record["domain"], "DOMAIN")
    if not DOMAIN.fullmatch(domain):
        fail("DOMAIN_INVALID")
    if record["difficulty"] not in DIFFICULTIES:
        fail("DIFFICULTY_INVALID")
    failures = require_list(record["failureSlices"], "FAILURE_SLICES")
    if len(failures) != len(set(failures)) or any(item not in FAILURE_SLICES for item in failures):
        fail("FAILURE_SLICES_INVALID")
    outcome = require_string(record["outcomeCode"], "OUTCOME_CODE")
    if not CODE.fullmatch(outcome):
        fail("OUTCOME_CODE_INVALID")
    _validate_ocr(require_object(record["ocr"], "OCR"))
    _validate_layout(require_object(record["layout"], "LAYOUT"))
    _validate_order(require_object(record["order"], "ORDER"))
    _validate_repeat(require_object(record["repeat"], "REPEAT"))
    _validate_semantic(require_object(record["semantic"], "SEMANTIC"))
    _validate_candidate(require_object(record["candidate"], "CANDIDATE"))
    _validate_calibration(require_object(record["calibration"], "CALIBRATION"))
    _validate_runtime(require_object(record["runtime"], "RUNTIME"))


def _validate_ocr(value: dict[str, Any]) -> None:
    keys = (
        "cases", "referenceCharacters", "predictedCharacters", "characterSubstitutions",
        "characterInsertions", "characterDeletions", "referenceWords", "predictedWords",
        "wordSubstitutions", "wordInsertions", "wordDeletions", "emptyReferenceCases",
        "hallucinationCases", "completeMissCases",
    )
    require_keys(value, keys, "OCR")
    for key in keys:
        require_int(value[key], f"OCR:{key}", 0)
    if value["cases"] != 1 or any(value[key] > value["cases"] for key in (
            "emptyReferenceCases", "hallucinationCases", "completeMissCases")):
        fail("OCR_ACCOUNTING_INVALID")


def _validate_layout(value: dict[str, Any]) -> None:
    require_keys(value, ("byKind", "evidence", "falseEvidence"), "LAYOUT")
    by_kind = require_object(value["byKind"], "LAYOUT_BY_KIND")
    require_keys(by_kind, REGION_KINDS, "LAYOUT_BY_KIND")
    for kind in REGION_KINDS:
        _validate_detection(require_object(by_kind[kind], f"DETECTION:{kind}"))
    evidence = require_object(value["evidence"], "LAYOUT_EVIDENCE")
    _validate_binary(evidence, "LAYOUT_EVIDENCE")
    false_evidence = require_int(value["falseEvidence"], "FALSE_EVIDENCE", 0)
    if false_evidence > evidence["predicted"]:
        fail("FALSE_EVIDENCE_INVALID")


def _validate_detection(value: dict[str, Any]) -> None:
    require_keys(value, (
        "expected", "predicted", "matchedByIouThreshold", "semanticallyMatched",
        "matchedIouBpsSum", "ap5095BpsSum", "evaluatedCases",
    ), "DETECTION")
    for key in ("expected", "predicted", "semanticallyMatched", "matchedIouBpsSum",
                "ap5095BpsSum", "evaluatedCases"):
        require_int(value[key], f"DETECTION:{key}", 0)
    thresholds = require_list(value["matchedByIouThreshold"], "DETECTION_THRESHOLDS")
    if len(thresholds) != 10:
        fail("DETECTION_THRESHOLD_COUNT_INVALID")
    for item in thresholds:
        matched = require_int(item, "DETECTION_THRESHOLD", 0)
        if matched > value["expected"] or matched > value["predicted"]:
            fail("DETECTION_THRESHOLD_INVALID")
    if value["semanticallyMatched"] > value["expected"] or value["semanticallyMatched"] > value["predicted"]:
        fail("DETECTION_MATCH_INVALID")
    if value["matchedIouBpsSum"] > 10_000 * value["semanticallyMatched"]:
        fail("DETECTION_IOU_INVALID")
    if value["ap5095BpsSum"] > 10_000 * value["evaluatedCases"]:
        fail("DETECTION_AP_INVALID")


def _validate_order(value: dict[str, Any]) -> None:
    require_keys(value, ("precedenceEdges", "cycleCases", "evaluatedCases"), "ORDER")
    _validate_binary(require_object(value["precedenceEdges"], "PRECEDENCE"), "PRECEDENCE")
    cycles = require_int(value["cycleCases"], "CYCLE_CASES", 0)
    cases = require_int(value["evaluatedCases"], "ORDER_CASES", 0)
    if cases != 1 or cycles > cases:
        fail("ORDER_ACCOUNTING_INVALID")


def _validate_repeat(value: dict[str, Any]) -> None:
    require_keys(value, ("groups", "items", "itemCountAbsoluteError", "memberships"), "REPEAT")
    for key in ("groups", "items", "memberships"):
        _validate_binary(require_object(value[key], f"REPEAT:{key}"), f"REPEAT:{key}")
    require_int(value["itemCountAbsoluteError"], "ITEM_COUNT_ABSOLUTE_ERROR", 0)


def _validate_semantic(value: dict[str, Any]) -> None:
    binary_keys = ("slots", "groups", "entities", "relationships", "cardinalities", "bindings",
                   "ownerContainment")
    require_keys(value, binary_keys + ("survival", "repairAttempts", "repairSuccesses"), "SEMANTIC")
    for key in binary_keys:
        _validate_binary(require_object(value[key], f"SEMANTIC:{key}"), f"SEMANTIC:{key}")
    survival = require_object(value["survival"], "SURVIVAL")
    require_keys(survival, ("expectedSlots", "observedSlots", "boundSlots", "candidateSlots"), "SURVIVAL")
    for key in survival:
        require_int(survival[key], f"SURVIVAL:{key}", 0)
    if not (survival["candidateSlots"] <= survival["boundSlots"] <= survival["observedSlots"]
            <= survival["expectedSlots"]):
        fail("SURVIVAL_ACCOUNTING_INVALID")
    attempts = require_int(value["repairAttempts"], "REPAIR_ATTEMPTS", 0)
    successes = require_int(value["repairSuccesses"], "REPAIR_SUCCESSES", 0)
    if successes > attempts:
        fail("REPAIR_ACCOUNTING_INVALID")


def _validate_candidate(value: dict[str, Any]) -> None:
    scalar_keys = (
        "evaluatedCases", "contractValidCases", "dagValidCases", "criticalHallucinations", "blockers",
        "topologyExpectedCases", "topologyPreservedCases",
    )
    binary_keys = ("entities", "fields", "relationships", "supportedTypes", "evidence")
    require_keys(value, scalar_keys + binary_keys, "CANDIDATE")
    for key in scalar_keys:
        require_int(value[key], f"CANDIDATE:{key}", 0)
    for key in binary_keys:
        _validate_binary(require_object(value[key], f"CANDIDATE:{key}"), f"CANDIDATE:{key}")
    if (
        value["evaluatedCases"] != 1
        or value["contractValidCases"] > 1
        or value["dagValidCases"] > 1
        or value["topologyExpectedCases"] > 1
        or value["topologyPreservedCases"] > value["topologyExpectedCases"]
    ):
        fail("CANDIDATE_ACCOUNTING_INVALID")


def _validate_calibration(value: dict[str, Any]) -> None:
    require_keys(value, (
        "bins", "unresolved", "reviewRequiredReachedCases", "successfulCases", "evaluatedCases",
    ), "CALIBRATION")
    bins = require_list(value["bins"], "CALIBRATION_BINS")
    if len(bins) != 10:
        fail("CALIBRATION_BIN_COUNT_INVALID")
    for index, raw_bin in enumerate(bins):
        item = require_object(raw_bin, f"CALIBRATION_BIN:{index}")
        require_keys(item, (
            "binIndex", "count", "correct", "confidenceBpsSum", "squaredErrorBpsSum",
        ), f"CALIBRATION_BIN:{index}")
        for key in item:
            require_int(item[key], f"CALIBRATION_BIN:{key}", 0)
        if (
            item["binIndex"] != index
            or item["correct"] > item["count"]
            or item["confidenceBpsSum"] > 10_000 * item["count"]
            or item["squaredErrorBpsSum"] > 10_000 * item["count"]
        ):
            fail("CALIBRATION_BIN_INVALID")
    _validate_binary(require_object(value["unresolved"], "UNRESOLVED"), "UNRESOLVED")
    cases = require_int(value["evaluatedCases"], "CALIBRATION_CASES", 0)
    reached = require_int(value["reviewRequiredReachedCases"], "REVIEW_REACHED", 0)
    successful = require_int(value["successfulCases"], "SUCCESSFUL_CASES", 0)
    if cases != 1 or reached > cases or successful > cases:
        fail("CALIBRATION_ACCOUNTING_INVALID")


def _validate_runtime(value: dict[str, Any]) -> None:
    keys = (
        "scriptedCalls", "inputTokens", "outputTokens", "estimatedCostMicrosCny",
        "settledCostMicrosCny", "latencyMicros", "recoveryCode", "recoveryCount",
        "acceptedStageReplayCount", "providerAttempts", "providerReservations",
        "externalProviderCostMicrosCny",
    )
    require_keys(value, keys, "RUNTIME")
    for key in keys:
        if key not in ("latencyMicros", "recoveryCode"):
            require_int(value[key], f"RUNTIME:{key}", 0)
    latency = require_object(value["latencyMicros"], "LATENCY_MICROS")
    require_keys(latency, STAGES, "LATENCY_MICROS")
    for stage in STAGES:
        require_int(latency[stage], f"LATENCY:{stage}", 0)
    if value["recoveryCode"] not in RECOVERY_CODES:
        fail("RECOVERY_CODE_INVALID")
    for key in ("estimatedCostMicrosCny", "settledCostMicrosCny", "providerAttempts",
                "providerReservations", "externalProviderCostMicrosCny"):
        if value[key] != 0:
            fail("EXTERNAL_PROVIDER_USAGE_NONZERO")


def _validate_binary(value: dict[str, Any], name: str) -> None:
    require_keys(value, ("expected", "predicted", "matched"), name)
    expected = require_int(value["expected"], f"{name}:EXPECTED", 0)
    predicted = require_int(value["predicted"], f"{name}:PREDICTED", 0)
    matched = require_int(value["matched"], f"{name}:MATCHED", 0)
    if matched > expected or matched > predicted:
        fail(f"{name}:ACCOUNTING_INVALID")


def _verify_slices(
        raw: Any, keys: Sequence[str], records: list[dict[str, Any]],
        predicate: Any, name: str,
) -> dict[str, dict[str, Any]]:
    value = require_object(raw, f"{name}_SLICES")
    require_keys(value, keys, f"{name}_SLICES")
    result: dict[str, dict[str, Any]] = {}
    for key in keys:
        expected = aggregate_records([item for item in records if predicate(item, key)])
        _require_equal(value[key], expected, f"{name}_SLICE_DRIFT:{key}")
        result[key] = expected
    return result


def _require_equal(actual: Any, expected: Any, code: str) -> None:
    if actual != expected:
        fail(code)


def require_object(value: Any, name: str) -> dict[str, Any]:
    if type(value) is not dict:
        fail(f"{name}:OBJECT_REQUIRED")
    return value


def require_list(value: Any, name: str) -> list[Any]:
    if type(value) is not list:
        fail(f"{name}:ARRAY_REQUIRED")
    return value


def require_keys(value: dict[str, Any], keys: Iterable[str], name: str) -> None:
    if set(value) != set(keys):
        fail(f"{name}:MEMBERS_INVALID")


def require_string(value: Any, name: str, minimum: int = 1, maximum: int = 512) -> str:
    if type(value) is not str or len(value) < minimum or len(value) > maximum:
        fail(f"{name}:STRING_INVALID")
    return value


def require_identity(value: Any, name: str) -> str:
    value = require_string(value, name, 1, 256)
    if not SHA_IDENTITY.fullmatch(value):
        fail(f"{name}:IDENTITY_INVALID")
    return value


def require_int(value: Any, name: str, minimum: int | None = None,
                maximum: int | None = None) -> int:
    if type(value) is not int:
        fail(f"{name}:INTEGER_REQUIRED")
    if minimum is not None and value < minimum:
        fail(f"{name}:INTEGER_TOO_SMALL")
    if maximum is not None and value > maximum:
        fail(f"{name}:INTEGER_TOO_LARGE")
    return value


def require_distinct(values: Sequence[str], code: str) -> None:
    if len(values) != len(set(values)):
        fail(code)


def validate_box(value: tuple[int, int, int, int]) -> None:
    if (len(value) != 4 or any(type(item) is not int for item in value)
            or value[0] < 0 or value[1] < 0 or value[0] >= value[2] or value[1] >= value[3]):
        fail("METRIC_BOX_INVALID")


def verify_file(path: pathlib.Path) -> dict[str, Any]:
    path = path.resolve()
    if not path.is_file() or path.stat().st_size <= 0 or path.stat().st_size > MAXIMUM_REPORT_BYTES:
        fail("REPORT_FILE_INVALID")
    try:
        raw = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        fail("REPORT_FILE_READ_FAILED")
    scan_payload_free(raw, "REPORT_FILE")
    return verify_envelope(parse_strict_json(raw))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify a payload-safe layered evaluation report")
    parser.add_argument("report", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    arguments = parser.parse_args(argv)
    try:
        summary = verify_file(arguments.report)
        encoded = canonical_json(summary).decode("utf-8")
        scan_payload_free(encoded, "VERIFIER_SUMMARY")
        if arguments.output is None:
            print(encoded)
        else:
            output = arguments.output.resolve()
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(encoded + "\n", encoding="utf-8", newline="\n")
        return 0
    except VerificationError as failure:
        print(f"LAYERED_VERIFY_FAILED:{failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
