#!/usr/bin/env python3
"""Independent, payload-safe actual replay for the frozen R5P paired product views."""

from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import json
import pathlib
import re
import struct
import sys
import unicodedata
from typing import Any, Callable, Iterable, Sequence


PROTOCOL_VERSION = "renderweave-r5p-independent-replay-input/1.0"
EVIDENCE_VERSION = "renderweave-r5p-independent-replay-evidence/1.0"
ENVELOPE_VERSION = "renderweave-r5p-independent-replay-envelope/1.0"
ASSURANCE = "A2_CROSS_IMPLEMENTATION_ACTUAL_REPLAY"
AUTHORITY_IDENTITY = (
    "renderweave-r5p-authority/1.0:"
    "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d"
)
ASSIGNMENT_IDENTITY = (
    "renderweave-r5p-paired-view-assignment/1.0:"
    "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8"
)
EVALUATION_IDENTITY = (
    "renderweave-r5p-paired-view-evaluation/1.0:"
    "c8ad69263640ca49cd93ca24c6b558c6f913ff89a40c84052634c7cd79f66b65"
)
INDEPENDENT_EVALUATOR_IDENTITY = "renderweave-r5p-independent-actual-replay/1.0"
CAPABILITY_IDENTITY = "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"
ADAPTER_IDENTITY = "rapidocr-local-process/1.0"
RUN_PROTOCOL_IDENTITY = "two-isolated-complete-paired-runs/1.0"
PROJECTION_IDENTITY = "renderweave-r5p-source-projection/1.0"
COALESCING_IDENTITY = "renderweave-r5p-observation-coalescing/1.0"
ACTION_POLICY_IDENTITY = (
    "AdaptiveInspectionPolicy/1.0:"
    "6843ae1ce61e0fa1804b3f0ec58c0ff8aba81ecae068d73b612070daa3a5b9bc"
)
STATIC_PLAN_VERSION = "renderweave-visual-view-plan/1.0"
SUCCESSOR_PLAN_VERSION = "renderweave-visual-view-plan/2.0"
TERMINAL_INVALID = "R5P_MEASUREMENT_INVALID"
TERMINAL_NOT_QUALIFIED = "R5P_PAIRED_VIEW_NOT_QUALIFIED"
TERMINAL_ALLOWED = "R5P_ACTION_IMPLEMENTATION_ALLOWED"
MAX_INPUT_BYTES = 42 * 1024 * 1024
MAX_EVIDENCE_BYTES = 4 * 1024 * 1024
SHA256 = re.compile(r"^[0-9a-f]{64}$")
IDENTITY = re.compile(r"^[A-Za-z0-9._/-]+:[0-9a-f]{64}$")
CASE_ID = re.compile(r"^[a-z][a-z0-9-]{0,127}$")

TOP_KEYS = {
    "protocolVersion", "authorityIdentity", "assignmentIdentity", "evaluationIdentity",
    "capabilityIdentity", "adapterIdentity", "runProtocolIdentity", "projectionIdentity",
    "coalescingIdentity", "thresholds", "externalProviderUsage", "apiKeyReads", "runs",
}
THRESHOLD_KEYS = {
    "minimumConfirmationLineRecallGainBps", "minimumConfirmationCharacterErrorReduction",
    "maximumConfirmationOrderRegressionBps", "maximumConfirmationRepeatRegressionBps",
    "maximumPerCaseHallucinationIncrease", "coalescingIntersectionOverSmallerAreaBps",
}
ZERO_PROVIDER_KEYS = {"attempts", "reservations", "costMicrosCny"}

CASES = [
    ("transit-board-v3", "688daa21a13118b5591d3057b6f1f15cef8a0e4f80a6549a4b80b19d8b043c0e",
     "SEEN_DIAGNOSTIC", "DEV", "2970b3648b580b46d87253560755fec752babcf3ff2435aa7e1c2c7fdd499790", 1024, 768, 2900),
    ("restaurant-menu-v3", "6910f5288cbbde4ac0d813affb19e3e1df9fb8c3d7bab85249e56801e2e8db78",
     "SEEN_DIAGNOSTIC", "DEV", "409aa4d0634e0c3cafb05573c5b4f3fbbc7b6712ca4c61536b958f6f1d830e26", 1024, 768, 2500),
    ("hospital-schedule-v3", "749916a935e98fbf48ae59181e1b6bcde0a0b01a347724af04566c22ac3a92f9",
     "SEEN_DIAGNOSTIC", "DEV", "a1d78928a11a598c3a4aaf1fc3f3fecae578604c990dbcc6e79bfdf43b1007d1", 1024, 768, 2500),
    ("transit-board-v5", "c8e155a1da4f8d8d93a646b01c4375773b20c14742f9cb233eebaf5673853c4f",
     "SEEN_DIAGNOSTIC", "HOLDOUT", "e4c5a31cddf063b23cc1b00f9efe7e8ccad138dc7104c43d39ddfa4273c6ce90", 1800, 1200, 2900),
    ("transit-board-v2", "3976013e6e00f4c93fa874804366ff1d12df066985ca320fdd20e47f7c2ee08d",
     "SEALED_CONFIRMATION", "DEV", "be09af5b9a417ef30a743df10a741f41f944242b7774dbbd815df4a30a065f27", 1000, 1600, 2900),
    ("invoice-lines-v3", "5906265340b4c556196095d90c2b6e34b86ac6c5300dc0fea6fedabe6a18deea",
     "SEALED_CONFIRMATION", "DEV", "f415dc8b5b6560b4ba1840cccfced2322367209f23d18fa2b59e1d86dbe18f53", 1024, 768, 2500),
    ("school-timetable-v4", "5c421a2ddace4db33f68e47a39a165474a917d679ae0f33a7f6a4655fdb4a06a",
     "SEALED_CONFIRMATION", "DEV", "5b25baf008d216e5e114142643709d57d677efe4ba1aad688ba4717488edd419", 1400, 900, 2500),
    ("building-directory-v5", "ca5be012237e052ca4adc6726bb8d6c75ed9ce2597b6bee130006b412a7baef9",
     "SEALED_CONFIRMATION", "HOLDOUT", "fee491f5b7f52e34c16119927c72d9172a6f0e3a97eb956227b43d75bc4149e7", 1800, 1200, 2500),
]


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("R5P_A2_DUPLICATE_MEMBER")
        result[key] = value
    return result


def _strict_int(value: str) -> int:
    parsed = int(value)
    if parsed < -(2**31) or parsed > 2**31 - 1:
        fail("R5P_A2_INTEGER_OVERFLOW")
    return parsed


def _reject_float(_value: str) -> float:
    fail("R5P_A2_FLOAT_FORBIDDEN")


def _exact_object(value: Any, keys: set[str], code: str) -> dict[str, Any]:
    if type(value) is not dict or set(value) != keys:
        fail(code)
    return value


def _integer(value: Any, minimum: int, maximum: int, code: str) -> int:
    if type(value) is not int or value < minimum or value > maximum:
        fail(code)
    return value


def _string(value: Any, pattern: re.Pattern[str] | None, code: str) -> str:
    if type(value) is not str or pattern is not None and pattern.fullmatch(value) is None:
        fail(code)
    return value


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def framed_sha256(values: Iterable[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def read_protocol_bytes(raw: bytes) -> dict[str, Any]:
    if not raw or len(raw) > MAX_INPUT_BYTES:
        fail("R5P_A2_INPUT_BYTES_INVALID")
    try:
        value = json.loads(
            raw.decode("utf-8", errors="strict"), object_pairs_hook=_strict_object,
            parse_int=_strict_int, parse_float=_reject_float,
            parse_constant=lambda _value: fail("R5P_A2_NONFINITE_FORBIDDEN"),
        )
    except VerificationError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise VerificationError("R5P_A2_INPUT_JSON_INVALID") from error
    document = _exact_object(value, TOP_KEYS, "R5P_A2_INPUT_SCHEMA_INVALID")
    if document["protocolVersion"] != PROTOCOL_VERSION or type(document["runs"]) is not list:
        fail("R5P_A2_INPUT_SCHEMA_INVALID")
    return document


def synthetic_protocol_for_tests() -> dict[str, Any]:
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "authorityIdentity": AUTHORITY_IDENTITY,
        "assignmentIdentity": ASSIGNMENT_IDENTITY,
        "evaluationIdentity": EVALUATION_IDENTITY,
        "capabilityIdentity": CAPABILITY_IDENTITY,
        "adapterIdentity": ADAPTER_IDENTITY,
        "runProtocolIdentity": RUN_PROTOCOL_IDENTITY,
        "projectionIdentity": PROJECTION_IDENTITY,
        "coalescingIdentity": COALESCING_IDENTITY,
        "thresholds": {
            "minimumConfirmationLineRecallGainBps": 500,
            "minimumConfirmationCharacterErrorReduction": 1,
            "maximumConfirmationOrderRegressionBps": 100,
            "maximumConfirmationRepeatRegressionBps": 100,
            "maximumPerCaseHallucinationIncrease": 0,
            "coalescingIntersectionOverSmallerAreaBps": 5000,
        },
        "externalProviderUsage": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
        "apiKeyReads": 0,
        "runs": [],
    }


def _edit_counts(reference: Sequence[Any], prediction: Sequence[Any]) -> tuple[int, int, int]:
    rows = len(reference) + 1
    columns = len(prediction) + 1
    distance = [[0] * columns for _ in range(rows)]
    for row in range(rows):
        distance[row][0] = row
    for column in range(columns):
        distance[0][column] = column
    for row in range(1, rows):
        for column in range(1, columns):
            substitution = distance[row - 1][column - 1] + (
                0 if reference[row - 1] == prediction[column - 1] else 1
            )
            distance[row][column] = min(
                substitution, distance[row][column - 1] + 1, distance[row - 1][column] + 1
            )
    substitutions = insertions = deletions = 0
    row, column = len(reference), len(prediction)
    while row > 0 or column > 0:
        if (row > 0 and column > 0 and reference[row - 1] == prediction[column - 1]
                and distance[row][column] == distance[row - 1][column - 1]):
            row -= 1
            column -= 1
        elif (row > 0 and column > 0
              and distance[row][column] == distance[row - 1][column - 1] + 1):
            substitutions += 1
            row -= 1
            column -= 1
        elif column > 0 and distance[row][column] == distance[row][column - 1] + 1:
            insertions += 1
            column -= 1
        else:
            deletions += 1
            row -= 1
    return substitutions, insertions, deletions


def _characters(reference: str, prediction: str) -> tuple[int, int, int, int, int]:
    counts = _edit_counts(list(reference), list(prediction))
    return len(reference), len(prediction), *counts


def _words(value: str) -> list[str]:
    normalized = value.strip()
    return [] if not normalized else re.split(r"\s+", normalized)


def _box(value: Any, code: str = "R5P_A2_BOX_INVALID") -> tuple[int, int, int, int]:
    if type(value) is not list or len(value) != 4:
        fail(code)
    left, top, right, bottom = (_integer(item, 0, 10_000, code) for item in value)
    if left >= right or top >= bottom:
        fail(code)
    return left, top, right, bottom


def _intersection(left: Sequence[int], right: Sequence[int]) -> int:
    return max(0, min(left[2], right[2]) - max(left[0], right[0])) * max(
        0, min(left[3], right[3]) - max(left[1], right[1])
    )


def _area(box: Sequence[int]) -> int:
    return (box[2] - box[0]) * (box[3] - box[1])


def _ratio(numerator: int, denominator: int) -> int:
    return 10_000 if denominator == 0 else numerator * 10_000 // denominator


def _region_by_line(gold: dict[str, Any]) -> dict[str, str]:
    regions = sorted(gold["regionIds"], key=lambda item: (-len(item), item))
    result: dict[str, str] = {}
    for line in gold["lines"]:
        for region in regions:
            prefix = "line-" + region
            if line["lineId"] == prefix or line["lineId"].startswith(prefix + "-"):
                result[line["lineId"]] = region
                break
    return result


def score_case(gold: dict[str, Any], actual: list[dict[str, Any]]) -> dict[str, int]:
    lines = gold["lines"]
    candidates: list[tuple[int, int, str, int, dict[str, Any], dict[str, Any], int]] = []
    for gold_line in lines:
        gold_box = _box(gold_line["box"])
        for order, actual_line in enumerate(actual):
            actual_box = _box(actual_line["box"])
            intersection = _intersection(gold_box, actual_box)
            center_x = (actual_box[0] + actual_box[2]) // 2
            center_y = (actual_box[1] + actual_box[3]) // 2
            if (intersection == 0 or not (gold_box[0] <= center_x < gold_box[2]
                                         and gold_box[1] <= center_y < gold_box[3])):
                continue
            ref_units, pred_units, substitutions, insertions, deletions = _characters(
                gold_line["text"], actual_line["text"]
            )
            errors = substitutions + insertions + deletions
            similarity = max(0, 10_000 - errors * 10_000 // max(1, ref_units, pred_units))
            predicted_coverage = _ratio(intersection, _area(actual_box))
            gold_coverage = _ratio(intersection, _area(gold_box))
            candidates.append((
                -similarity, -predicted_coverage, gold_line["lineId"], order,
                gold_line, actual_line, gold_coverage,
            ))
    candidates.sort(key=lambda item: item[:4])
    used_gold: set[str] = set()
    used_actual: set[int] = set()
    matches: list[tuple[dict[str, Any], dict[str, Any], int, int]] = []
    for candidate in candidates:
        gold_line, actual_line = candidate[4], candidate[5]
        order = candidate[3]
        if gold_line["lineId"] not in used_gold and order not in used_actual:
            used_gold.add(gold_line["lineId"])
            used_actual.add(order)
            matches.append((gold_line, actual_line, order, candidate[6]))
    matches.sort(key=lambda item: item[2])
    actual_by_gold = {item[0]["lineId"]: item[1]["text"] for item in matches}
    matched_orders = {item[2] for item in matches}
    character_errors = 0
    for line in lines:
        counts = _characters(line["text"], actual_by_gold.get(line["lineId"], ""))
        character_errors += sum(counts[2:])
        _edit_counts(_words(line["text"]), _words(actual_by_gold.get(line["lineId"], "")))
    for order, line in enumerate(actual):
        if order not in matched_orders:
            character_errors += sum(_characters("", line["text"])[2:])
            _edit_counts([], _words(line["text"]))
    region_by_line = _region_by_line(gold)
    observed_regions = {
        region_by_line[item[0]["lineId"]]
        for item in matches if item[0]["lineId"] in region_by_line
    }
    actual_order: dict[str, int] = {}
    for gold_line, _actual_line, order, _coverage in matches:
        region = region_by_line.get(gold_line["lineId"])
        if region is not None:
            actual_order[region] = min(order, actual_order.get(region, order))
    comparable = correct = 0
    for edge in gold["precedenceEdges"]:
        before = actual_order.get(edge["beforeRegionId"])
        after = actual_order.get(edge["afterRegionId"])
        if before is not None and after is not None:
            comparable += 1
            if before < after:
                correct += 1
    expected_memberships = observable_memberships = 0
    for group in gold["repeatGroups"]:
        for item in group["items"]:
            expected_memberships += len(item["memberRegionIds"])
            observable_memberships += sum(
                1 for region in item["memberRegionIds"] if region in observed_regions
            )
    return {
        "expectedLines": len(lines),
        "matchedLines": len(matches),
        "lineRecallBps": _ratio(len(matches), len(lines)),
        "characterErrors": character_errors,
        "hallucinationCases": 1 if len(actual) > len(matches) else 0,
        "orderExpectedEdges": len(gold["precedenceEdges"]),
        "orderComparableEdges": comparable,
        "orderCorrectEdges": correct,
        "orderAccuracyBps": _ratio(correct, comparable),
        "repeatExpectedMemberships": expected_memberships,
        "repeatObservableMemberships": observable_memberships,
        "repeatRecallBps": _ratio(observable_memberships, expected_memberships),
    }


def terminal_for(measurement_valid: bool, seen_pass: bool, confirmation_pass: bool) -> str:
    if not measurement_valid:
        return TERMINAL_INVALID
    return TERMINAL_ALLOWED if seen_pass and confirmation_pass else TERMINAL_NOT_QUALIFIED


def _payload_safe(value: Any) -> None:
    if type(value) is dict:
        for key, child in value.items():
            lowered = key.lower()
            if ("base64" in lowered or lowered == "text" or "boundingbox" in lowered
                    or "sourcepixelbox" in lowered or "imagebytes" in lowered):
                fail("R5P_A2_DECODED_PAYLOAD_FORBIDDEN")
            _payload_safe(child)
    elif type(value) is list:
        for child in value:
            _payload_safe(child)
    elif type(value) is str:
        lowered = value.lower()
        if ("data:image" in lowered or "ignore prior instructions" in lowered
                or lowered.startswith("bearer ")):
            fail("R5P_A2_DECODED_PAYLOAD_FORBIDDEN")


CASE_DECISION_KEYS = {
    "caseId", "caseIdentity", "cohort", "normalizationReplayIdentity",
    "baselineExecutionIdentity", "successorExecutionIdentity", "baselineViewCount",
    "successorViewCount", "matchedLineGain", "lineRecallGainBps",
    "characterErrorReduction", "hallucinationIncrease", "orderAccuracyDeltaBps",
    "repeatRecallDeltaBps", "targetImproved", "hallucinationNonIncrease", "deterministic",
}
COHORT_KEYS = {
    "caseCount", "targetImprovementCases", "hallucinationNonIncreaseCases",
    "baselineMatchedLines", "successorMatchedLines", "baselineLineRecallBps",
    "successorLineRecallBps", "lineRecallGainBps", "baselineCharacterErrors",
    "successorCharacterErrors", "characterErrorReduction", "baselineHallucinations",
    "successorHallucinations", "baselineOrderAccuracyBps", "successorOrderAccuracyBps",
    "orderAccuracyDeltaBps", "baselineRepeatRecallBps", "successorRepeatRecallBps",
    "repeatRecallDeltaBps", "thresholdPass",
}
EVIDENCE_KEYS = {
    "evidenceVersion", "assurance", "authorityIdentity", "assignmentIdentity",
    "evaluationIdentity", "independentEvaluatorIdentity", "capabilityIdentity", "runCount",
    "caseCount", "executedBranchCount", "actualAcquisitionCalls", "normalizationReplays",
    "actionExecutions", "determinism", "cases", "seenSummary", "confirmationSummary",
    "measurementValid", "qualityPass", "externalProviderUsage", "apiKeyReads",
    "producerDecisionEngineCalls", "producerReportReads", "payloadBoundary", "terminalCode",
}


def validate_evidence(value: Any) -> dict[str, Any]:
    evidence = _exact_object(value, EVIDENCE_KEYS, "R5P_A2_EVIDENCE_SCHEMA_INVALID")
    _payload_safe(evidence)
    if (evidence["evidenceVersion"] != EVIDENCE_VERSION or evidence["assurance"] != ASSURANCE
            or evidence["authorityIdentity"] != AUTHORITY_IDENTITY
            or evidence["assignmentIdentity"] != ASSIGNMENT_IDENTITY
            or evidence["evaluationIdentity"] != EVALUATION_IDENTITY
            or evidence["independentEvaluatorIdentity"] != INDEPENDENT_EVALUATOR_IDENTITY
            or evidence["capabilityIdentity"] != CAPABILITY_IDENTITY):
        fail("R5P_A2_EVIDENCE_AUTHORITY_INVALID")
    if type(evidence["measurementValid"]) is not bool or type(evidence["qualityPass"]) is not bool:
        fail("R5P_A2_EVIDENCE_DECISION_INVALID")
    fixed_accounting = {
        "runCount": 2, "caseCount": 8, "executedBranchCount": 32,
        "actualAcquisitionCalls": 32, "normalizationReplays": 16, "actionExecutions": 16,
    }
    for field, expected in fixed_accounting.items():
        if (type(evidence[field]) is not int
                or evidence["measurementValid"] and evidence[field] != expected
                or not evidence["measurementValid"] and evidence[field] < 0):
            fail("R5P_A2_EVIDENCE_ACCOUNTING_INVALID")
    for field in ("apiKeyReads", "producerDecisionEngineCalls", "producerReportReads"):
        if type(evidence[field]) is not int or evidence[field] != 0:
            fail("R5P_A2_EVIDENCE_ACCOUNTING_INVALID")
    provider = _exact_object(
        evidence["externalProviderUsage"], ZERO_PROVIDER_KEYS, "R5P_A2_EVIDENCE_SCHEMA_INVALID"
    )
    if any(type(provider[key]) is not int or provider[key] != 0 for key in ZERO_PROVIDER_KEYS):
        fail("R5P_A2_EVIDENCE_PROVIDER_USAGE_NONZERO")
    boundary = _exact_object(evidence["payloadBoundary"], {
        "imagePersisted", "encodedImagePayloadPersisted", "geometryPayloadPersisted",
        "ocrTextPersisted", "goldTextPersisted", "providerPayloadPersisted",
    }, "R5P_A2_EVIDENCE_SCHEMA_INVALID")
    if any(type(item) is not bool or item for item in boundary.values()):
        fail("R5P_A2_EVIDENCE_PAYLOAD_INVALID")
    determinism = _exact_object(evidence["determinism"], {
        "comparedCases", "equivalentCases", "comparedBranches", "equivalentBranches",
        "deterministic", "verdictCode",
    }, "R5P_A2_EVIDENCE_DETERMINISM_INVALID")
    if (determinism["comparedCases"] != 8 or determinism["comparedBranches"] != 16
            or type(determinism["equivalentCases"]) is not int
            or not 0 <= determinism["equivalentCases"] <= 8
            or type(determinism["equivalentBranches"]) is not int
            or not 0 <= determinism["equivalentBranches"] <= 16
            or type(determinism["deterministic"]) is not bool
            or determinism["deterministic"] != (
                determinism["equivalentCases"] == 8 and determinism["equivalentBranches"] == 16)
            or determinism["verdictCode"] != "R5P_A2_TWO_RUN_DETERMINISTIC"):
        fail("R5P_A2_EVIDENCE_DETERMINISM_INVALID")
    decisions = evidence["cases"]
    expected_case_count = 8 if evidence["measurementValid"] else 0
    if type(decisions) is not list or len(decisions) != expected_case_count:
        fail("R5P_A2_EVIDENCE_CASE_INVALID")
    for index, decision in enumerate(decisions):
        item = _exact_object(decision, CASE_DECISION_KEYS, "R5P_A2_EVIDENCE_CASE_INVALID")
        expected = CASES[index]
        if (item["caseId"] != expected[0]
                or item["caseIdentity"] != "renderweave-layered-case/2.0:" + expected[1]
                or item["cohort"] != expected[2]
                or type(item["targetImproved"]) is not bool
                or type(item["hallucinationNonIncrease"]) is not bool
                or type(item["deterministic"]) is not bool or not item["deterministic"]):
            fail("R5P_A2_EVIDENCE_CASE_INVALID")
        for identity_field in (
                "normalizationReplayIdentity", "baselineExecutionIdentity",
                "successorExecutionIdentity"):
            _string(item[identity_field], IDENTITY, "R5P_A2_EVIDENCE_CASE_INVALID")
        for numeric in (
                "baselineViewCount", "successorViewCount", "matchedLineGain",
                "lineRecallGainBps", "characterErrorReduction", "hallucinationIncrease",
                "orderAccuracyDeltaBps", "repeatRecallDeltaBps"):
            if type(item[numeric]) is not int:
                fail("R5P_A2_EVIDENCE_CASE_INVALID")
        if (not 1 <= item["baselineViewCount"] < item["successorViewCount"] <= 10
                or item["targetImproved"] != (
                    item["matchedLineGain"] > 0 or item["characterErrorReduction"] > 0)
                or item["hallucinationNonIncrease"] != (item["hallucinationIncrease"] <= 0)):
            fail("R5P_A2_EVIDENCE_CASE_INVALID")
    summaries = []
    for field in ("seenSummary", "confirmationSummary"):
        summary = _exact_object(evidence[field], COHORT_KEYS, "R5P_A2_EVIDENCE_COHORT_INVALID")
        integer_fields = COHORT_KEYS - {"thresholdPass"}
        if (any(type(summary[name]) is not int for name in integer_fields)
                or type(summary["thresholdPass"]) is not bool or summary["caseCount"] != 4
                or not 0 <= summary["targetImprovementCases"] <= 4
                or not 0 <= summary["hallucinationNonIncreaseCases"] <= 4
                or any(summary[name] < 0 for name in (
                    "baselineMatchedLines", "successorMatchedLines", "baselineCharacterErrors",
                    "successorCharacterErrors", "baselineHallucinations", "successorHallucinations"))
                or any(not 0 <= summary[name] <= 10_000 for name in (
                    "baselineLineRecallBps", "successorLineRecallBps",
                    "baselineOrderAccuracyBps", "successorOrderAccuracyBps",
                    "baselineRepeatRecallBps", "successorRepeatRecallBps"))
                or summary["lineRecallGainBps"] != (
                    summary["successorLineRecallBps"] - summary["baselineLineRecallBps"])
                or summary["characterErrorReduction"] != (
                    summary["baselineCharacterErrors"] - summary["successorCharacterErrors"])
                or summary["orderAccuracyDeltaBps"] != (
                    summary["successorOrderAccuracyBps"] - summary["baselineOrderAccuracyBps"])
                or summary["repeatRecallDeltaBps"] != (
                    summary["successorRepeatRecallBps"] - summary["baselineRepeatRecallBps"])):
            fail("R5P_A2_EVIDENCE_COHORT_INVALID")
        summaries.append(summary)
    if (summaries[0]["thresholdPass"] != _threshold_pass(summaries[0], False)
            or summaries[1]["thresholdPass"] != _threshold_pass(summaries[1], True)):
        fail("R5P_A2_EVIDENCE_COHORT_INVALID")
    expected_quality = (evidence["measurementValid"] and evidence["seenSummary"]["thresholdPass"]
                        and evidence["confirmationSummary"]["thresholdPass"])
    if evidence["qualityPass"] != expected_quality or evidence["terminalCode"] != terminal_for(
            evidence["measurementValid"], evidence["seenSummary"]["thresholdPass"],
            evidence["confirmationSummary"]["thresholdPass"]):
        fail("R5P_A2_EVIDENCE_DECISION_INVALID")
    return evidence


def synthetic_evidence_for_tests() -> dict[str, Any]:
    cases = []
    for index, expected in enumerate(CASES):
        suffix = format(index, "x")
        cases.append({
            "caseId": expected[0],
            "caseIdentity": "renderweave-layered-case/2.0:" + expected[1],
            "cohort": expected[2],
            "normalizationReplayIdentity": "renderweave-r5p-a2-normalization/1.0:" + "0" * 63 + suffix,
            "baselineExecutionIdentity": "renderweave-r5p-a2-branch/1.0:" + "1" * 63 + suffix,
            "successorExecutionIdentity": "renderweave-r5p-a2-branch/1.0:" + "2" * 63 + suffix,
            "baselineViewCount": 1, "successorViewCount": 3,
            "matchedLineGain": 1, "lineRecallGainBps": 500,
            "characterErrorReduction": 1, "hallucinationIncrease": 0,
            "orderAccuracyDeltaBps": 0, "repeatRecallDeltaBps": 0,
            "targetImproved": True, "hallucinationNonIncrease": True, "deterministic": True,
        })
    summary = {
        "caseCount": 4, "targetImprovementCases": 4, "hallucinationNonIncreaseCases": 4,
        "baselineMatchedLines": 10, "successorMatchedLines": 14,
        "baselineLineRecallBps": 5000, "successorLineRecallBps": 7000,
        "lineRecallGainBps": 2000, "baselineCharacterErrors": 40,
        "successorCharacterErrors": 36, "characterErrorReduction": 4,
        "baselineHallucinations": 0, "successorHallucinations": 0,
        "baselineOrderAccuracyBps": 10_000, "successorOrderAccuracyBps": 10_000,
        "orderAccuracyDeltaBps": 0, "baselineRepeatRecallBps": 10_000,
        "successorRepeatRecallBps": 10_000, "repeatRecallDeltaBps": 0,
        "thresholdPass": True,
    }
    return {
        "evidenceVersion": EVIDENCE_VERSION, "assurance": ASSURANCE,
        "authorityIdentity": AUTHORITY_IDENTITY, "assignmentIdentity": ASSIGNMENT_IDENTITY,
        "evaluationIdentity": EVALUATION_IDENTITY,
        "independentEvaluatorIdentity": INDEPENDENT_EVALUATOR_IDENTITY,
        "capabilityIdentity": CAPABILITY_IDENTITY, "runCount": 2, "caseCount": 8,
        "executedBranchCount": 32, "actualAcquisitionCalls": 32,
        "normalizationReplays": 16, "actionExecutions": 16,
        "determinism": {"comparedCases": 8, "equivalentCases": 8, "comparedBranches": 16,
                        "equivalentBranches": 16, "deterministic": True,
                        "verdictCode": "R5P_A2_TWO_RUN_DETERMINISTIC"},
        "cases": cases, "seenSummary": dict(summary), "confirmationSummary": dict(summary),
        "measurementValid": True, "qualityPass": True,
        "externalProviderUsage": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
        "apiKeyReads": 0, "producerDecisionEngineCalls": 0, "producerReportReads": 0,
        "payloadBoundary": {"imagePersisted": False, "encodedImagePayloadPersisted": False,
                            "geometryPayloadPersisted": False, "ocrTextPersisted": False,
                            "goldTextPersisted": False, "providerPayloadPersisted": False},
        "terminalCode": TERMINAL_ALLOWED,
    }


def _decode_raster(value: Any, expected_sha: str, expected_width: int, expected_height: int) -> bytes:
    if type(value) is not str:
        fail("R5P_A2_RASTER_INVALID")
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as error:
        raise VerificationError("R5P_A2_RASTER_INVALID") from error
    if not raw or sha256(raw) != expected_sha:
        fail("R5P_A2_RASTER_IDENTITY_DRIFT")
    if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
        fail("R5P_A2_RASTER_INVALID")
    width, height = struct.unpack(">II", raw[16:24])
    if width != expected_width or height != expected_height:
        fail("R5P_A2_RASTER_DIMENSION_DRIFT")
    return raw


def _ceil_div(value: int, divisor: int) -> int:
    return -(-value // divisor)


def _canonical_crop(crop: Sequence[int], width: int, height: int) -> list[int]:
    return [crop[0] * 10_000 // width, crop[1] * 10_000 // height,
            _ceil_div(crop[2] * 10_000, width), _ceil_div(crop[3] * 10_000, height)]


def _resize_dimensions(crop: Sequence[int], maximum: int, force_long_edge: bool) -> tuple[int, int]:
    width, height = crop[2] - crop[0], crop[3] - crop[1]
    if force_long_edge:
        return ((maximum, max(1, height * maximum // width)) if width >= height
                else (max(1, width * maximum // height), maximum))
    if max(width, height) <= maximum:
        return width, height
    return max(1, width * maximum // max(width, height)), max(1, height * maximum // max(width, height))


def _static_shapes(width: int, height: int) -> list[dict[str, Any]]:
    full = [0, 0, width, height]
    overview_width, overview_height = _resize_dimensions(full, 768, False)
    result = [{"viewId": "view-00-overview-00", "kind": "OVERVIEW", "crop": full,
               "sourceBoundingBox": [0, 0, 10_000, 10_000],
               "width": overview_width, "height": overview_height}]
    columns, rows = _ceil_div(width, 1400), _ceil_div(height, 1400)
    if columns != 1 or rows != 1:
        ordinal = 0
        for row in range(rows):
            for column in range(columns):
                crop = [column * width // columns, row * height // rows,
                        (column + 1) * width // columns, (row + 1) * height // rows]
                view_width, view_height = _resize_dimensions(crop, 1400, False)
                result.append({"viewId": f"view-00-tile-{ordinal:02d}", "kind": "TILE",
                               "crop": crop, "sourceBoundingBox": _canonical_crop(crop, width, height),
                               "width": view_width, "height": view_height})
                ordinal += 1
    return result


def _inspection_shapes(width: int, height: int, split: int) -> list[dict[str, Any]]:
    result = []
    for ordinal, canonical in enumerate(([200, 200, 9800, split], [200, split, 9800, 9800])):
        crop = [canonical[0] * width // 10_000, canonical[1] * height // 10_000,
                _ceil_div(canonical[2] * width, 10_000), _ceil_div(canonical[3] * height, 10_000)]
        view_width, view_height = _resize_dimensions(crop, 2400, True)
        result.append({"viewId": f"view-00-inspected-{ordinal:02d}", "kind": "TARGETED_CROP",
                       "crop": crop, "sourceBoundingBox": _canonical_crop(crop, width, height),
                       "width": view_width, "height": view_height})
    return result


def _input_fingerprint(case_id: str, raw_sha: str, raw: bytes) -> str:
    source_reference = "r5p-fixture-set:" + framed_sha256([case_id + ":" + raw_sha])
    digest = hashlib.sha256()
    for frame in (b"image-only", b"r5p-offline-harness", source_reference.encode("utf-8"),
                  b"image/png", raw):
        digest.update(len(frame).to_bytes(4, "big"))
        digest.update(frame)
    return digest.hexdigest()


def _validate_gold(value: Any) -> dict[str, Any]:
    gold = _exact_object(value, {"lines", "regionIds", "precedenceEdges", "repeatGroups"},
                         "R5P_A2_GOLD_SCHEMA_INVALID")
    if type(gold["lines"]) is not list or not gold["lines"] or len(gold["lines"]) > 512:
        fail("R5P_A2_GOLD_SCHEMA_INVALID")
    line_ids: set[str] = set()
    for line in gold["lines"]:
        item = _exact_object(line, {"lineId", "text", "box"}, "R5P_A2_GOLD_SCHEMA_INVALID")
        if (_string(item["lineId"], CASE_ID, "R5P_A2_GOLD_SCHEMA_INVALID") in line_ids
                or type(item["text"]) is not str or not item["text"]):
            fail("R5P_A2_GOLD_SCHEMA_INVALID")
        line_ids.add(item["lineId"])
        _box(item["box"], "R5P_A2_GOLD_SCHEMA_INVALID")
    if (type(gold["regionIds"]) is not list or len(set(gold["regionIds"])) != len(gold["regionIds"])
            or any(type(item) is not str for item in gold["regionIds"])):
        fail("R5P_A2_GOLD_SCHEMA_INVALID")
    if type(gold["precedenceEdges"]) is not list or type(gold["repeatGroups"]) is not list:
        fail("R5P_A2_GOLD_SCHEMA_INVALID")
    for edge in gold["precedenceEdges"]:
        _exact_object(edge, {"beforeRegionId", "afterRegionId"}, "R5P_A2_GOLD_SCHEMA_INVALID")
    for group in gold["repeatGroups"]:
        group = _exact_object(group, {"groupRegionId", "items"}, "R5P_A2_GOLD_SCHEMA_INVALID")
        if type(group["items"]) is not list:
            fail("R5P_A2_GOLD_SCHEMA_INVALID")
        for item in group["items"]:
            item = _exact_object(item, {"itemRegionId", "memberRegionIds"},
                                 "R5P_A2_GOLD_SCHEMA_INVALID")
            if type(item["memberRegionIds"]) is not list:
                fail("R5P_A2_GOLD_SCHEMA_INVALID")
    return gold


def _view_frame(ordinal: int, view: dict[str, Any]) -> str:
    box = ",".join(str(item) for item in view["sourceBoundingBox"])
    return (f"{ordinal}:{view['viewId']}:{view['sourceArtifactId']}:{view['sourceOrdinal']}:"
            f"{view['kind']}:{box}:{view['width']}x{view['height']}:"
            f"{view['providerArtifactId']}:{view['encodedBytes']}")


def _static_plan_identity(views: list[dict[str, Any]]) -> str:
    descriptors = []
    for ordinal, view in enumerate(views):
        box = ",".join(str(item) for item in view["sourceBoundingBox"])
        descriptor = framed_sha256([
            f"ordinal={ordinal}", f"view-id={view['viewId']}",
            f"source={view['sourceArtifactId']}", f"source-ordinal={view['sourceOrdinal']}",
            f"kind={view['kind']}", f"source-box={box}",
            f"dimensions={view['width']}x{view['height']}",
            f"provider-artifact={view['providerArtifactId']}",
            f"encoded-bytes={view['encodedBytes']}",
        ])
        descriptors.append("renderweave-r5p-view-descriptor/1.0:" + descriptor)
    return "renderweave-r5p-static-plan/1.0:" + framed_sha256(
        [f"plan-version={STATIC_PLAN_VERSION}", f"view-count={len(views)}"]
        + descriptors
    )


def _base_plan_identity(views: list[dict[str, Any]]) -> str:
    return "renderweave-r5p-action-base-plan/1.0:" + framed_sha256(
        [f"plan-version={STATIC_PLAN_VERSION}", f"view-count={len(views)}"]
        + [_view_frame(index, view) for index, view in enumerate(views)]
    )


def _request_identity(base_plan: str, source_sha: str, width: int, height: int,
                      split: int) -> str:
    regions = ([200, 200, 9800, split], [200, split, 9800, 9800])
    frames = ["contract=InspectionRequest/1.0", "base-plan=" + base_plan,
              "policy=" + ACTION_POLICY_IDENTITY, f"source=0:{source_sha}:{width}x{height}"]
    for box in regions:
        frames.append("region=view-00-overview-00:" + ",".join(map(str, box))
                      + ":TIGHT_0000_BPS:INSPECT_LONG_EDGE_2400")
    return "renderweave-inspection-request/1.0:" + framed_sha256(frames)


def _successor_plan_identity(base_plan: str, request_identity: str,
                             views: list[dict[str, Any]]) -> str:
    frames = [f"plan-version={SUCCESSOR_PLAN_VERSION}", "base-plan=" + base_plan,
              "request=" + request_identity, "policy=" + ACTION_POLICY_IDENTITY,
              f"view-count={len(views)}"]
    frames.extend(_view_frame(index, view) for index, view in enumerate(views))
    return SUCCESSOR_PLAN_VERSION + ":" + framed_sha256(frames)


def _validate_view(raw: Any, expected: dict[str, Any], source_sha: str,
                   source_width: int, source_height: int, ordinal: int) -> tuple[dict[str, Any], bytes]:
    keys = {"planOrdinal", "viewId", "sourceArtifactId", "sourceOrdinal", "kind",
            "sourceBoundingBox", "width", "height", "sourceWidth", "sourceHeight", "crop",
            "providerArtifactId", "mediaType", "encodedBytes", "encodedSha256", "encodedImage"}
    view = _exact_object(raw, keys, "R5P_A2_VIEW_SCHEMA_INVALID")
    if (view["planOrdinal"] != ordinal or view["viewId"] != expected["viewId"]
            or view["kind"] != expected["kind"] or view["sourceArtifactId"] != source_sha
            or view["sourceOrdinal"] != 0 or view["sourceBoundingBox"] != expected["sourceBoundingBox"]
            or view["width"] != expected["width"] or view["height"] != expected["height"]
            or view["sourceWidth"] != source_width or view["sourceHeight"] != source_height
            or view["crop"] != expected["crop"] or view["mediaType"] != "image/png"):
        fail("R5P_A2_VIEW_COVERAGE_DRIFT")
    encoded_sha = _string(view["encodedSha256"], SHA256, "R5P_A2_VIEW_IDENTITY_DRIFT")
    if view["providerArtifactId"] != encoded_sha:
        fail("R5P_A2_VIEW_IDENTITY_DRIFT")
    image = _decode_raster(view["encodedImage"], encoded_sha, view["width"], view["height"])
    if type(view["encodedBytes"]) is not int or view["encodedBytes"] != len(image):
        fail("R5P_A2_VIEW_IDENTITY_DRIFT")
    return view, image


def _canonical_text(value: str) -> str:
    normalized = re.sub(r"\s+", " ", unicodedata.normalize("NFC", value)).strip()
    if not normalized or any(unicodedata.category(character) == "Cc" for character in normalized):
        fail("R5P_A2_OBSERVATION_INVALID")
    return normalized


def _project(view: dict[str, Any], line: dict[str, Any], view_ordinal: int,
             line_ordinal: int) -> dict[str, Any]:
    width, height = view["width"], view["height"]
    local = [line["left"] * 10_000 // width, line["top"] * 10_000 // height,
             _ceil_div(line["right"] * 10_000, width),
             _ceil_div(line["bottom"] * 10_000, height)]
    crop = view["crop"]
    crop_width, crop_height = crop[2] - crop[0], crop[3] - crop[1]
    source_width, source_height = view["sourceWidth"], view["sourceHeight"]
    projected = [
        (crop[0] * 10_000 + local[0] * crop_width) // source_width,
        (crop[1] * 10_000 + local[1] * crop_height) // source_height,
        _ceil_div(crop[0] * 10_000 + local[2] * crop_width, source_width),
        _ceil_div(crop[1] * 10_000 + local[3] * crop_height, source_height),
    ]
    projected = [max(0, min(9999, projected[0])), max(0, min(9999, projected[1])),
                 max(1, min(10_000, projected[2])), max(1, min(10_000, projected[3]))]
    _box(projected, "R5P_A2_PROJECTION_INVALID")
    confidence = _integer(line["confidenceBps"], 0, 10_000, "R5P_A2_OBSERVATION_INVALID")
    return {"text": _canonical_text(line["text"]), "box": projected,
            "confidenceBps": confidence, "viewOrdinal": view_ordinal,
            "lineOrdinal": line_ordinal}


def _coalesce(lines: list[dict[str, Any]]) -> list[dict[str, Any]]:
    ordered = sorted(lines, key=lambda item: (
        item["box"][1], item["box"][0], item["box"][3], item["box"][2], item["text"],
        item["viewOrdinal"], item["lineOrdinal"],
    ))
    result: list[dict[str, Any]] = []
    for candidate in ordered:
        matched = -1
        for index, existing in enumerate(result):
            smaller = min(_area(candidate["box"]), _area(existing["box"]))
            if (candidate["text"] == existing["text"] and smaller > 0
                    and _intersection(candidate["box"], existing["box"]) * 10_000 >= smaller * 5000):
                matched = index
                break
        if matched < 0:
            result.append(candidate)
        else:
            existing = result[matched]
            prefer = (candidate["confidenceBps"] > existing["confidenceBps"]
                      or candidate["confidenceBps"] == existing["confidenceBps"]
                      and (_area(candidate["box"]) < _area(existing["box"])
                           or _area(candidate["box"]) == _area(existing["box"])
                           and (candidate["viewOrdinal"], candidate["lineOrdinal"])
                           < (existing["viewOrdinal"], existing["lineOrdinal"])))
            if prefer:
                result[matched] = candidate
    return sorted(result, key=lambda item: (
        item["box"][1], item["box"][0], item["box"][3], item["box"][2], item["text"],
        item["viewOrdinal"], item["lineOrdinal"],
    ))


def _branch_execution_identity(branch: dict[str, Any], observations: list[dict[str, Any]],
                               coalesced: list[dict[str, Any]], metrics: dict[str, int]) -> str:
    frames = [f"branch={branch['branch']}", f"plan={branch['planVersion']}:{branch['planIdentity']}"]
    for ordinal, (view, lines) in enumerate(zip(branch["views"], observations)):
        frames.append(f"view={ordinal}:{view['viewId']}:{view['encodedSha256']}:"
                      f"{view['width']}x{view['height']}:{view['encodedBytes']}")
        for line_ordinal, line in enumerate(lines):
            frames.append("raw=" + f"{ordinal}:{line_ordinal}:{line['left']},{line['top']},"
                          f"{line['right']},{line['bottom']}:{line['confidenceBps']}:{line['text']}")
    for line in coalesced:
        frames.append("metric=" + ",".join(map(str, line["box"]))
                      + f":{line['confidenceBps']}:{line['text']}")
    frames.append("metrics=" + canonical_json(metrics).decode("utf-8"))
    return "renderweave-r5p-a2-branch-execution/1.0:" + framed_sha256(frames)


def _pair_metrics(baseline: dict[str, int], successor: dict[str, int]) -> dict[str, Any]:
    matched_gain = successor["matchedLines"] - baseline["matchedLines"]
    character_reduction = baseline["characterErrors"] - successor["characterErrors"]
    hallucination_increase = successor["hallucinationCases"] - baseline["hallucinationCases"]
    return {
        "matchedLineGain": matched_gain,
        "lineRecallGainBps": successor["lineRecallBps"] - baseline["lineRecallBps"],
        "characterErrorReduction": character_reduction,
        "hallucinationIncrease": hallucination_increase,
        "orderAccuracyDeltaBps": successor["orderAccuracyBps"] - baseline["orderAccuracyBps"],
        "repeatRecallDeltaBps": successor["repeatRecallBps"] - baseline["repeatRecallBps"],
        "targetImproved": matched_gain > 0 or character_reduction > 0,
        "hallucinationNonIncrease": hallucination_increase <= 0,
    }


def _cohort_summary(results: list[dict[str, Any]], confirmation: bool) -> dict[str, Any]:
    baseline = [item["baselineMetrics"] for item in results]
    successor = [item["successorMetrics"] for item in results]
    baseline_expected = sum(item["expectedLines"] for item in baseline)
    successor_expected = sum(item["expectedLines"] for item in successor)
    baseline_matched = sum(item["matchedLines"] for item in baseline)
    successor_matched = sum(item["matchedLines"] for item in successor)
    baseline_errors = sum(item["characterErrors"] for item in baseline)
    successor_errors = sum(item["characterErrors"] for item in successor)
    baseline_hallucinations = sum(item["hallucinationCases"] for item in baseline)
    successor_hallucinations = sum(item["hallucinationCases"] for item in successor)
    baseline_comparable = sum(item["orderComparableEdges"] for item in baseline)
    successor_comparable = sum(item["orderComparableEdges"] for item in successor)
    baseline_correct = sum(item["orderCorrectEdges"] for item in baseline)
    successor_correct = sum(item["orderCorrectEdges"] for item in successor)
    baseline_expected_repeat = sum(item["repeatExpectedMemberships"] for item in baseline)
    successor_expected_repeat = sum(item["repeatExpectedMemberships"] for item in successor)
    baseline_observed_repeat = sum(item["repeatObservableMemberships"] for item in baseline)
    successor_observed_repeat = sum(item["repeatObservableMemberships"] for item in successor)
    baseline_recall, successor_recall = (_ratio(baseline_matched, baseline_expected),
                                         _ratio(successor_matched, successor_expected))
    baseline_order, successor_order = (_ratio(baseline_correct, baseline_comparable),
                                       _ratio(successor_correct, successor_comparable))
    baseline_repeat, successor_repeat = (_ratio(baseline_observed_repeat, baseline_expected_repeat),
                                         _ratio(successor_observed_repeat, successor_expected_repeat))
    target_cases = sum(1 for item in results if item["pairMetrics"]["targetImproved"])
    hallucination_cases = sum(
        1 for item in results if item["pairMetrics"]["hallucinationNonIncrease"]
    )
    character_reduction = baseline_errors - successor_errors
    summary = {
        "caseCount": 4, "targetImprovementCases": target_cases,
        "hallucinationNonIncreaseCases": hallucination_cases,
        "baselineMatchedLines": baseline_matched, "successorMatchedLines": successor_matched,
        "baselineLineRecallBps": baseline_recall, "successorLineRecallBps": successor_recall,
        "lineRecallGainBps": successor_recall - baseline_recall,
        "baselineCharacterErrors": baseline_errors, "successorCharacterErrors": successor_errors,
        "characterErrorReduction": character_reduction,
        "baselineHallucinations": baseline_hallucinations,
        "successorHallucinations": successor_hallucinations,
        "baselineOrderAccuracyBps": baseline_order, "successorOrderAccuracyBps": successor_order,
        "orderAccuracyDeltaBps": successor_order - baseline_order,
        "baselineRepeatRecallBps": baseline_repeat, "successorRepeatRecallBps": successor_repeat,
        "repeatRecallDeltaBps": successor_repeat - baseline_repeat,
        "thresholdPass": False,
    }
    summary["thresholdPass"] = _threshold_pass(summary, confirmation)
    return summary


def _threshold_pass(summary: dict[str, Any], confirmation: bool) -> bool:
    per_case = (summary["targetImprovementCases"] == summary["caseCount"]
                and summary["hallucinationNonIncreaseCases"] == summary["caseCount"]
                and summary["successorHallucinations"] - summary["baselineHallucinations"] <= 0)
    return per_case and (not confirmation
                         or summary["lineRecallGainBps"] >= 500
                         and summary["characterErrorReduction"] >= 1
                         and summary["orderAccuracyDeltaBps"] >= -100
                         and summary["repeatRecallDeltaBps"] >= -100)


def _load_adapter(path: pathlib.Path) -> Any:
    resolved = path.resolve(strict=True)
    spec = importlib.util.spec_from_file_location("renderweave_r5p_frozen_adapter", resolved)
    if spec is None or spec.loader is None:
        fail("R5P_A2_ADAPTER_INVALID")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if module.CAPABILITY_ID != CAPABILITY_IDENTITY:
        fail("R5P_A2_CAPABILITY_DRIFT")
    return module


def replay(document: dict[str, Any], adapter: Any, model_root: pathlib.Path) -> dict[str, Any]:
    if (document["authorityIdentity"] != AUTHORITY_IDENTITY
            or document["assignmentIdentity"] != ASSIGNMENT_IDENTITY
            or document["evaluationIdentity"] != EVALUATION_IDENTITY
            or document["capabilityIdentity"] != CAPABILITY_IDENTITY
            or document["adapterIdentity"] != ADAPTER_IDENTITY
            or document["runProtocolIdentity"] != RUN_PROTOCOL_IDENTITY
            or document["projectionIdentity"] != PROJECTION_IDENTITY
            or document["coalescingIdentity"] != COALESCING_IDENTITY):
        fail("R5P_A2_AUTHORITY_DRIFT")
    thresholds = _exact_object(document["thresholds"], THRESHOLD_KEYS, "R5P_A2_THRESHOLD_DRIFT")
    expected_thresholds = synthetic_protocol_for_tests()["thresholds"]
    if thresholds != expected_thresholds:
        fail("R5P_A2_THRESHOLD_DRIFT")
    provider = _exact_object(document["externalProviderUsage"], ZERO_PROVIDER_KEYS,
                             "R5P_A2_PROVIDER_USAGE_NONZERO")
    if any(type(provider[key]) is not int or provider[key] != 0 for key in ZERO_PROVIDER_KEYS) \
            or document["apiKeyReads"] != 0:
        fail("R5P_A2_PROVIDER_USAGE_NONZERO")
    runs = document["runs"]
    if len(runs) != 2:
        fail("R5P_A2_RUN_ACCOUNTING_INVALID")
    replayed_runs: list[list[dict[str, Any]]] = []
    acquisition_calls = normalization_replays = action_executions = 0
    for run_index, raw_run in enumerate(runs, 1):
        engine = adapter._engine(model_root.resolve(strict=True))
        run = _exact_object(raw_run, {"runOrdinal", "cases"}, "R5P_A2_RUN_SCHEMA_INVALID")
        if run["runOrdinal"] != run_index or type(run["cases"]) is not list or len(run["cases"]) != 8:
            fail("R5P_A2_RUN_ACCOUNTING_INVALID")
        replayed_cases: list[dict[str, Any]] = []
        for case_index, raw_case in enumerate(run["cases"]):
            expected = CASES[case_index]
            case = _exact_object(raw_case, {
                "caseId", "caseIdentity", "cohort", "sourcePartition", "rawFixtureSha256",
                "rawBytes", "normalization", "gold", "inspectionRequest", "branches",
            }, "R5P_A2_CASE_SCHEMA_INVALID")
            case_id, case_sha, cohort, partition, raw_sha, width, height, split = expected
            if (case["caseId"] != case_id
                    or case["caseIdentity"] != "renderweave-layered-case/2.0:" + case_sha
                    or case["cohort"] != cohort or case["sourcePartition"] != partition
                    or case["rawFixtureSha256"] != raw_sha):
                fail("R5P_A2_CASE_AUTHORITY_DRIFT")
            raw_bytes = _decode_raster(case["rawBytes"], raw_sha, width, height)
            normalization = _exact_object(case["normalization"], {
                "inputFingerprint", "normalizedArtifactId", "mediaType", "encodedBytes",
                "width", "height", "blobWrites", "blobReads", "normalizedBytes",
            }, "R5P_A2_NORMALIZATION_SCHEMA_INVALID")
            normalized = _decode_raster(normalization["normalizedBytes"], raw_sha, width, height)
            if (normalized != raw_bytes or normalization["normalizedArtifactId"] != raw_sha
                    or normalization["mediaType"] != "image/png"
                    or normalization["encodedBytes"] != len(normalized)
                    or normalization["width"] != width or normalization["height"] != height
                    or normalization["blobWrites"] != 1 or normalization["blobReads"] != 1
                    or normalization["inputFingerprint"] != _input_fingerprint(case_id, raw_sha, raw_bytes)):
                fail("R5P_A2_NORMALIZATION_DRIFT")
            normalization_replays += 1
            gold = _validate_gold(case["gold"])
            request = _exact_object(case["inspectionRequest"], {"contractVersion", "regions"},
                                    "R5P_A2_REQUEST_SCHEMA_INVALID")
            expected_regions = [
                {"baseViewId": "view-00-overview-00", "boundingBox": [200, 200, 9800, split],
                 "marginPreset": "TIGHT_0000_BPS", "resolutionPreset": "INSPECT_LONG_EDGE_2400"},
                {"baseViewId": "view-00-overview-00", "boundingBox": [200, split, 9800, 9800],
                 "marginPreset": "TIGHT_0000_BPS", "resolutionPreset": "INSPECT_LONG_EDGE_2400"},
            ]
            if request != {"contractVersion": "InspectionRequest/1.0", "regions": expected_regions}:
                fail("R5P_A2_REQUEST_DRIFT")
            branches = case["branches"]
            if type(branches) is not list or len(branches) != 2:
                fail("R5P_A2_BRANCH_ACCOUNTING_INVALID")
            static_shapes = _static_shapes(width, height)
            inspected_shapes = _inspection_shapes(width, height, split)
            expected_successor = [static_shapes[0], *inspected_shapes, *static_shapes[1:]]
            branch_results: list[dict[str, Any]] = []
            materialized_static: list[dict[str, Any]] | None = None
            base_identity = request_identity = None
            for branch_index, raw_branch in enumerate(branches):
                branch = _exact_object(raw_branch, {
                    "branch", "planVersion", "planIdentity", "requestIdentity", "policyIdentity",
                    "resources", "views",
                }, "R5P_A2_BRANCH_SCHEMA_INVALID")
                branch_name = "BASELINE" if branch_index == 0 else "SUCCESSOR"
                expected_shapes = static_shapes if branch_index == 0 else expected_successor
                expected_version = STATIC_PLAN_VERSION if branch_index == 0 else SUCCESSOR_PLAN_VERSION
                if branch["branch"] != branch_name or branch["planVersion"] != expected_version \
                        or type(branch["views"]) is not list or len(branch["views"]) != len(expected_shapes):
                    fail("R5P_A2_BRANCH_COVERAGE_DRIFT")
                views: list[dict[str, Any]] = []
                images: list[bytes] = []
                for ordinal, (raw_view, shape) in enumerate(zip(branch["views"], expected_shapes)):
                    view, image = _validate_view(raw_view, shape, raw_sha, width, height, ordinal)
                    views.append(view)
                    images.append(image)
                branch["views"] = views
                if branch_index == 0:
                    materialized_static = views
                    if branch["planIdentity"] != _static_plan_identity(views) \
                            or branch["requestIdentity"] != "NONE" or branch["policyIdentity"] != "NONE":
                        fail("R5P_A2_STATIC_PLAN_IDENTITY_DRIFT")
                    base_identity = _base_plan_identity(views)
                    request_identity = _request_identity(base_identity, raw_sha, width, height, split)
                else:
                    assert materialized_static is not None and base_identity is not None \
                        and request_identity is not None
                    if (branch["requestIdentity"] != request_identity
                            or branch["policyIdentity"] != ACTION_POLICY_IDENTITY
                            or branch["planIdentity"] != _successor_plan_identity(
                                base_identity, request_identity, views)):
                        fail("R5P_A2_SUCCESSOR_PLAN_IDENTITY_DRIFT")
                    action_executions += 1
                resources = _exact_object(branch["resources"], {
                    "totalViews", "inspectedViews", "totalEncodedBytes", "totalPixels",
                    "inspectedPixels", "additionalVisualTokens", "localTransformMillis",
                }, "R5P_A2_RESOURCE_SCHEMA_INVALID")
                total_bytes = sum(len(image) for image in images)
                total_pixels = sum(view["width"] * view["height"] for view in views)
                inspected_pixels = sum(
                    view["width"] * view["height"] for view in views if view["kind"] == "TARGETED_CROP"
                )
                expected_inspected = 0 if branch_index == 0 else 2
                expected_tokens = 0 if branch_index == 0 else sum(
                    _ceil_div(view["width"] * view["height"], 1024) + 2
                    for view in views if view["kind"] == "TARGETED_CROP"
                )
                if (resources["totalViews"] != len(views)
                        or resources["inspectedViews"] != expected_inspected
                        or resources["totalEncodedBytes"] != total_bytes
                        or resources["totalPixels"] != total_pixels
                        or resources["inspectedPixels"] != inspected_pixels
                        or resources["additionalVisualTokens"] != expected_tokens
                        or type(resources["localTransformMillis"]) is not int
                        or not 0 <= resources["localTransformMillis"] <= 10_000):
                    fail("R5P_A2_RESOURCE_DRIFT")
                observations: list[list[dict[str, Any]]] = []
                projected: list[dict[str, Any]] = []
                for ordinal, (view, image) in enumerate(zip(views, images)):
                    response = adapter._artifact(engine, {
                        "artifactId": view["providerArtifactId"], "sourceOrdinal": ordinal,
                        "mediaType": "image/png", "width": view["width"], "height": view["height"],
                        "base64": base64.b64encode(image).decode("ascii"),
                    })
                    lines = response["lines"]
                    observations.append(lines)
                    for line_ordinal, line in enumerate(lines):
                        projected.append(_project(view, line, ordinal, line_ordinal))
                acquisition_calls += 1
                coalesced = _coalesce(projected)
                metrics = score_case(gold, coalesced)
                execution_identity = _branch_execution_identity(branch, observations, coalesced, metrics)
                branch_results.append({"metrics": metrics, "executionIdentity": execution_identity,
                                       "viewCount": len(views)})
            pair = _pair_metrics(branch_results[0]["metrics"], branch_results[1]["metrics"])
            normalization_identity = "renderweave-r5p-a2-normalization-replay/1.0:" + framed_sha256([
                f"case={case_id}", f"raw={raw_sha}", f"input={normalization['inputFingerprint']}",
                f"normalized={normalization['normalizedArtifactId']}:{width}x{height}:{len(normalized)}",
                "blob=1:1",
            ])
            replayed_cases.append({
                "caseId": case_id, "caseIdentity": case["caseIdentity"], "cohort": cohort,
                "normalizationIdentity": normalization_identity,
                "baselineIdentity": branch_results[0]["executionIdentity"],
                "successorIdentity": branch_results[1]["executionIdentity"],
                "baselineViewCount": branch_results[0]["viewCount"],
                "successorViewCount": branch_results[1]["viewCount"],
                "baselineMetrics": branch_results[0]["metrics"],
                "successorMetrics": branch_results[1]["metrics"], "pairMetrics": pair,
            })
        replayed_runs.append(replayed_cases)
    if acquisition_calls != 32 or normalization_replays != 16 or action_executions != 16:
        fail("R5P_A2_ACCOUNTING_DRIFT")
    equivalent_cases = equivalent_branches = 0
    for left, right in zip(replayed_runs[0], replayed_runs[1]):
        baseline_equal = left["baselineIdentity"] == right["baselineIdentity"]
        successor_equal = left["successorIdentity"] == right["successorIdentity"]
        equivalent_branches += int(baseline_equal) + int(successor_equal)
        equivalent_cases += int(left == right)
    if equivalent_cases != 8 or equivalent_branches != 16:
        fail("R5P_A2_TWO_RUN_DRIFT")
    first = replayed_runs[0]
    seen = _cohort_summary(first[:4], False)
    confirmation = _cohort_summary(first[4:], True)
    decisions = []
    for item in first:
        pair = item["pairMetrics"]
        decisions.append({
            "caseId": item["caseId"], "caseIdentity": item["caseIdentity"],
            "cohort": item["cohort"],
            "normalizationReplayIdentity": item["normalizationIdentity"],
            "baselineExecutionIdentity": item["baselineIdentity"],
            "successorExecutionIdentity": item["successorIdentity"],
            "baselineViewCount": item["baselineViewCount"],
            "successorViewCount": item["successorViewCount"],
            **pair, "deterministic": True,
        })
    quality = seen["thresholdPass"] and confirmation["thresholdPass"]
    evidence = {
        "evidenceVersion": EVIDENCE_VERSION, "assurance": ASSURANCE,
        "authorityIdentity": AUTHORITY_IDENTITY, "assignmentIdentity": ASSIGNMENT_IDENTITY,
        "evaluationIdentity": EVALUATION_IDENTITY,
        "independentEvaluatorIdentity": INDEPENDENT_EVALUATOR_IDENTITY,
        "capabilityIdentity": CAPABILITY_IDENTITY, "runCount": 2, "caseCount": 8,
        "executedBranchCount": 32, "actualAcquisitionCalls": acquisition_calls,
        "normalizationReplays": normalization_replays, "actionExecutions": action_executions,
        "determinism": {"comparedCases": 8, "equivalentCases": equivalent_cases,
                        "comparedBranches": 16, "equivalentBranches": equivalent_branches,
                        "deterministic": True, "verdictCode": "R5P_A2_TWO_RUN_DETERMINISTIC"},
        "cases": decisions, "seenSummary": seen, "confirmationSummary": confirmation,
        "measurementValid": True, "qualityPass": quality,
        "externalProviderUsage": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
        "apiKeyReads": 0, "producerDecisionEngineCalls": 0, "producerReportReads": 0,
        "payloadBoundary": {"imagePersisted": False, "encodedImagePayloadPersisted": False,
                            "geometryPayloadPersisted": False, "ocrTextPersisted": False,
                            "goldTextPersisted": False, "providerPayloadPersisted": False},
        "terminalCode": terminal_for(True, seen["thresholdPass"], confirmation["thresholdPass"]),
    }
    return validate_evidence(evidence)


def _invalid_evidence() -> dict[str, Any]:
    evidence = synthetic_evidence_for_tests()
    evidence.update({"runCount": 0, "caseCount": 0, "executedBranchCount": 0,
                     "actualAcquisitionCalls": 0, "normalizationReplays": 0,
                     "actionExecutions": 0, "cases": [], "measurementValid": False,
                     "qualityPass": False, "terminalCode": TERMINAL_INVALID})
    evidence["determinism"] = {"comparedCases": 8, "equivalentCases": 0,
                               "comparedBranches": 16, "equivalentBranches": 0,
                               "deterministic": False,
                               "verdictCode": "R5P_A2_TWO_RUN_DETERMINISTIC"}
    evidence["seenSummary"]["thresholdPass"] = False
    evidence["confirmationSummary"]["thresholdPass"] = False
    evidence["seenSummary"]["targetImprovementCases"] = 0
    evidence["confirmationSummary"]["targetImprovementCases"] = 0
    return evidence


def envelope(evidence: dict[str, Any]) -> dict[str, Any]:
    identity = EVIDENCE_VERSION + ":" + sha256(canonical_json(evidence))
    return {"envelopeVersion": ENVELOPE_VERSION, "evidenceIdentity": identity,
            "evidence": evidence}


def _write_new(path: pathlib.Path, payload: bytes) -> None:
    path.parent.resolve(strict=True)
    with path.open("xb") as stream:
        stream.write(payload)


def main() -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--adapter", required=True)
    parser.add_argument("--model-root", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = pathlib.Path(args.output).resolve()
    try:
        document = read_protocol_bytes(sys.stdin.buffer.read(MAX_INPUT_BYTES + 1))
        evidence = replay(document, _load_adapter(pathlib.Path(args.adapter)), pathlib.Path(args.model_root))
    except Exception:
        evidence = _invalid_evidence()
    encoded = canonical_json(envelope(evidence))
    if len(encoded) > MAX_EVIDENCE_BYTES:
        fail("R5P_A2_EVIDENCE_BYTES_INVALID")
    _write_new(output, encoded)
    sys.stdout.write(evidence["terminalCode"] + "\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception:
        sys.stderr.write("R5P_A2_PROCESS_FAILED\n")
        raise SystemExit(2)
