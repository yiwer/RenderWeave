#!/usr/bin/env python3
"""Independent, public-process-only R5P2 actual replay and payload-safe evidence writer."""

from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
import pathlib
import re
import struct
import sys
from typing import Any, Callable, Iterable, Sequence

import r5p2_source_line_reconciliation as reconciliation
from r5p2_public_process import BranchArtifact, PublicBranchProcessClient


PROTOCOL_VERSION = "renderweave-r5p2-independent-replay-input/1.0"
EVIDENCE_VERSION = "renderweave-r5p2-independent-replay-evidence/1.0"
ENVELOPE_VERSION = "renderweave-r5p2-independent-replay-envelope/1.0"
ASSURANCE = "A2_CROSS_IMPLEMENTATION_PUBLIC_PROCESS_ACTUAL_REPLAY"
INDEPENDENT_EVALUATOR_IDENTITY = "renderweave-r5p2-independent-actual-replay/1.0"
REPLAY_COMPLETE = "R5P2_INDEPENDENT_REPLAY_COMPLETE"
TERMINAL_INVALID = "R5P2_MEASUREMENT_INVALID"
TERMINAL_NOT_QUALIFIED = "R5P2_PAIRED_VIEW_NOT_QUALIFIED"
TERMINAL_ALLOWED = "R5P2_ACTION_IMPLEMENTATION_ALLOWED"
AUTHORITY_IDENTITY = (
    "renderweave-r5p2-authority/1.0:"
    "274585e94941248dd2bea55026c06428f2945aea7cc48ce2b269c21f5f3ccc07"
)
ASSIGNMENT_IDENTITY = (
    "renderweave-r5p2-frozen-assignment/1.0:"
    "74ec12bc198db1f9597391102a44676918b4c6122851a7b50d338446fe5f7cbd"
)
FIXTURE_SET_IDENTITY = (
    "renderweave-r5p2-repository-raster-fixture-set/1.0:"
    "3e425016eb01d824391deaa91059e13ef0230f3a444a004e2a28504f1d1e7d92"
)
EVALUATION_IDENTITY = (
    "renderweave-r5p2-paired-product-view-evaluation/1.0:"
    "b5a9fb0d38e9b4e2d06b4be93d272bb6704d6ef56d81fa18f1a593d22a946558"
)
THRESHOLD_IDENTITY = (
    "renderweave-r5p2-thresholds/1.0:"
    "ab91362c4738a5feaadc67053604ddfefa861b16012f0523a088e3964430a8e1"
)
CAPABILITY_IDENTITY = "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"
ACTION_POLICY_IDENTITY = (
    "AdaptiveInspectionPolicy/1.0:"
    "6843ae1ce61e0fa1804b3f0ec58c0ff8aba81ecae068d73b612070daa3a5b9bc"
)
ACQUISITION_POLICY_SHA256 = "32ade47685c07163e10f77be8b8ed46e420af7b7d381e1363d30886a19e26c52"
STATIC_PLAN_VERSION = "renderweave-visual-view-plan/1.0"
SUCCESSOR_PLAN_VERSION = "renderweave-visual-view-plan/2.0"
MAX_INPUT_BYTES = 64 * 1024 * 1024
MAX_EVIDENCE_BYTES = 4 * 1024 * 1024
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
IDENTITY_PATTERN = re.compile(r"^[A-Za-z0-9._/-]+:[0-9a-f]{64}$")
CODE_PATTERN = re.compile(r"^R5P2_[A-Z0-9_]+$")

CASES = (
    ("transit-board-v3", "renderweave-layered-case/2.0:688daa21a13118b5591d3057b6f1f15cef8a0e4f80a6549a4b80b19d8b043c0e", "HISTORICAL_DIAGNOSTIC", "DEV"),
    ("restaurant-menu-v3", "renderweave-layered-case/2.0:6910f5288cbbde4ac0d813affb19e3e1df9fb8c3d7bab85249e56801e2e8db78", "HISTORICAL_DIAGNOSTIC", "DEV"),
    ("hospital-schedule-v3", "renderweave-layered-case/2.0:749916a935e98fbf48ae59181e1b6bcde0a0b01a347724af04566c22ac3a92f9", "HISTORICAL_DIAGNOSTIC", "DEV"),
    ("transit-board-v5", "renderweave-layered-case/2.0:c8e155a1da4f8d8d93a646b01c4375773b20c14742f9cb233eebaf5673853c4f", "HISTORICAL_DIAGNOSTIC", "HOLDOUT"),
    ("transit-board-v2", "renderweave-layered-case/2.0:3976013e6e00f4c93fa874804366ff1d12df066985ca320fdd20e47f7c2ee08d", "HISTORICAL_DIAGNOSTIC", "DEV"),
    ("invoice-lines-v3", "renderweave-layered-case/2.0:5906265340b4c556196095d90c2b6e34b86ac6c5300dc0fea6fedabe6a18deea", "HISTORICAL_DIAGNOSTIC", "DEV"),
    ("school-timetable-v4", "renderweave-layered-case/2.0:5c421a2ddace4db33f68e47a39a165474a917d679ae0f33a7f6a4655fdb4a06a", "HISTORICAL_DIAGNOSTIC", "DEV"),
    ("building-directory-v5", "renderweave-layered-case/2.0:ca5be012237e052ca4adc6726bb8d6c75ed9ce2597b6bee130006b412a7baef9", "HISTORICAL_DIAGNOSTIC", "HOLDOUT"),
    ("weather-forecast-v3", "renderweave-layered-case/2.0:b8276787bbfa99b49851308a7c963fcd41e5bbd311ad1db9169885fc766ee890", "SEALED_CONFIRMATION", "DEV"),
    ("warehouse-inventory-v2", "renderweave-layered-case/2.0:9ca1319537a06892e7920a973b66a9a893f0dea1fea31c4bcb21b7efaf4bf456", "SEALED_CONFIRMATION", "DEV"),
    ("event-agenda-v4", "renderweave-layered-case/2.0:8691ed1f501c6597fecbd5c93ac433f42100f5025c7339dcff5474299a9c314f", "SEALED_CONFIRMATION", "DEV"),
    ("product-catalog-v5", "renderweave-layered-case/2.0:2c0e17e489cf1ef4f1f55be50ff9ca2d070114bf58395125a811a30de3444b14", "SEALED_CONFIRMATION", "HOLDOUT"),
)

EXPECTED_STAGE_IDENTITIES = {
    "acquisitionPolicyIdentity": "AcquisitionPolicy/1.0:" + ACQUISITION_POLICY_SHA256,
    "actionModuleSourceSha256": "99df52f72e1b5ff06c064bf96281149cf56e631f6f285bcbb882eb1e09723e4f",
    "actionModuleVersion": "renderweave-bounded-visual-inspection/1.0",
    "actionPolicyIdentity": ACTION_POLICY_IDENTITY,
    "adapterIdentity": "rapidocr-local-process/1.0",
    "adapterSourceSha256": "401565b45944ee85929c38415e5d4255f5b5559e80b9d67ee9f83a3419af27d0",
    "branchProcessContractIdentity": "renderweave-r5p2-complete-branch-process/1.0",
    "capabilityIdentity": CAPABILITY_IDENTITY,
    "caseEvaluatorIdentity": "renderweave-rapidocr-shadow-case-evaluator/1.0",
    "evaluatorIdentity": "renderweave-r5p2-paired-product-view-evaluator/1.0",
    "normalizerIdentity": "renderweave-input-normalizer-source-sha256/1.0:71a4f90ee7298fb3ef3a3550e34880ed3216213fc69f31b80f9b0e496570654a",
    "projectionIdentity": reconciliation.PROJECTION_IDENTITY,
    "publicProcessClientIdentity": "renderweave-r5p2-public-branch-process-client/1.0",
    "publicProcessClientSourceSha256": "465c92e971b14fefe04a9cecff214b8c9e8dd1b25a7b87db8252b6ea7c759c32",
    "reconciliationPolicyIdentity": reconciliation.POLICY_IDENTITY,
    "reconciliationSourceSha256": "987c9d77aca3350545718c4e055dc142514716d05826d3fb6d75ac316c86eba1",
    "runProtocolIdentity": "two-isolated-complete-paired-runs-48-processes/1.0",
    "runtimeIdentity": "renderweave-r5p2-runtime/1.0:c5ad80d38095c7fb55e03b23d1ffddc93904caf4412366bd972cf7720c4a520f",
    "staticPlannerSourceSha256": "e10d2955c9b463ee3996eac333abf4c8f32c2a82faf38e286796e56b7d52fa0c",
    "staticPlannerVersion": STATIC_PLAN_VERSION,
    "successorPlanVersion": SUCCESSOR_PLAN_VERSION,
}

EXPECTED_THRESHOLDS = {
    "perCaseTargetImprovementRule": "MATCHED_LINE_GAIN_OR_CHARACTER_ERROR_REDUCTION",
    "maximumPerCaseHallucinationIncrease": 0,
    "minimumConfirmationLineRecallGainBps": 500,
    "minimumConfirmationCharacterErrorReduction": 1,
    "maximumConfirmationOrderRegressionBps": 100,
    "maximumConfirmationRepeatRegressionBps": 100,
    "areaOverlapBps": 5000,
    "verticalOverlapBps": 8000,
    "centerRule": "smaller-center-in-larger-closed-open/1.0",
}


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            fail("R5P2_A2_JSON_DUPLICATE_KEY")
        value[key] = item
    return value


def _strict_int(value: str) -> int:
    parsed = int(value)
    if not -(2**63) <= parsed <= 2**63 - 1:
        fail("R5P2_A2_JSON_INTEGER_INVALID")
    return parsed


def _reject_float(_value: str) -> float:
    fail("R5P2_A2_JSON_NUMBER_INVALID")


def _exact(value: Any, keys: set[str], code: str) -> dict[str, Any]:
    if type(value) is not dict or set(value) != keys:
        fail(code)
    return value


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


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
        fail("R5P2_A2_INPUT_BYTES_INVALID")
    try:
        value = json.loads(
            raw.decode("utf-8"), object_pairs_hook=_strict_object,
            parse_int=_strict_int, parse_float=_reject_float,
            parse_constant=lambda _value: fail("R5P2_A2_JSON_NUMBER_INVALID"),
        )
    except VerificationError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise VerificationError("R5P2_A2_INPUT_JSON_INVALID") from error
    return _validate_protocol_header(value)


def _validate_protocol_header(value: Any) -> dict[str, Any]:
    document = _exact(value, {
        "protocolVersion", "authorityIdentity", "assignmentIdentity", "fixtureSetIdentity",
        "evaluationIdentity", "thresholdIdentity", "stageIdentities", "thresholds",
        "accessBoundary", "externalProviderUsage", "apiKeyReads", "runs",
    }, "R5P2_A2_INPUT_SCHEMA_INVALID")
    expected = {
        "protocolVersion": PROTOCOL_VERSION,
        "authorityIdentity": AUTHORITY_IDENTITY,
        "assignmentIdentity": ASSIGNMENT_IDENTITY,
        "fixtureSetIdentity": FIXTURE_SET_IDENTITY,
        "evaluationIdentity": EVALUATION_IDENTITY,
        "thresholdIdentity": THRESHOLD_IDENTITY,
    }
    if any(document[key] != expected[key] for key in expected):
        fail("R5P2_A2_INPUT_AUTHORITY_INVALID")
    if document["stageIdentities"] != EXPECTED_STAGE_IDENTITIES:
        fail("R5P2_A2_INPUT_STAGE_IDENTITY_INVALID")
    if document["thresholds"] != EXPECTED_THRESHOLDS:
        fail("R5P2_A2_INPUT_THRESHOLD_INVALID")
    access = _exact(document["accessBoundary"], {
        "producerReportReadsDuringReplay", "producerMetricReadsDuringReplay",
        "producerDecisionReadsDuringReplay", "holdoutRole", "holdoutCaseId",
        "holdoutStatus", "holdoutGoldMetricReads",
    }, "R5P2_A2_INPUT_ACCESS_INVALID")
    expected_access = {
        "producerReportReadsDuringReplay": 0, "producerMetricReadsDuringReplay": 0,
        "producerDecisionReadsDuringReplay": 0, "holdoutRole": "INDEPENDENT_REPLAY",
        "holdoutCaseId": "product-catalog-v5", "holdoutStatus": "SEALED",
        "holdoutGoldMetricReads": 1,
    }
    if access != expected_access:
        fail("R5P2_A2_INPUT_ACCESS_INVALID")
    if document["externalProviderUsage"] != {
            "attempts": 0, "reservations": 0, "costMicrosCny": 0} \
            or document["apiKeyReads"] != 0:
        fail("R5P2_A2_INPUT_PROVIDER_USAGE_NONZERO")
    if type(document["runs"]) is not list or len(document["runs"]) != 2:
        fail("R5P2_A2_INPUT_ACCOUNTING_INVALID")
    return document


def _integer(value: Any, minimum: int, maximum: int, code: str) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        fail(code)
    return value


def _box(value: Any, code: str = "R5P2_A2_BOX_INVALID") -> tuple[int, int, int, int]:
    if type(value) is not list or len(value) != 4:
        fail(code)
    left, top, right, bottom = (_integer(item, 0, 10_000, code) for item in value)
    if left >= right or top >= bottom:
        fail(code)
    return left, top, right, bottom


def _intersection(left: Sequence[int], right: Sequence[int]) -> int:
    return max(0, min(left[2], right[2]) - max(left[0], right[0])) * max(
        0, min(left[3], right[3]) - max(left[1], right[1]))


def _area(box: Sequence[int]) -> int:
    return (box[2] - box[0]) * (box[3] - box[1])


def _ratio(numerator: int, denominator: int) -> int:
    return 10_000 if denominator == 0 else numerator * 10_000 // denominator


def _edit_counts(reference: Sequence[Any], prediction: Sequence[Any]) -> tuple[int, int, int]:
    rows, columns = len(reference) + 1, len(prediction) + 1
    distance = [[0] * columns for _ in range(rows)]
    for row in range(rows):
        distance[row][0] = row
    for column in range(columns):
        distance[0][column] = column
    for row in range(1, rows):
        for column in range(1, columns):
            substitution = distance[row - 1][column - 1] + (
                0 if reference[row - 1] == prediction[column - 1] else 1)
            distance[row][column] = min(
                substitution, distance[row][column - 1] + 1,
                distance[row - 1][column] + 1)
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
    return len(reference), len(prediction), *_edit_counts(list(reference), list(prediction))


def _words(value: str) -> list[str]:
    normalized = value.strip()
    return [] if not normalized else re.split(r"\s+", normalized)


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
    candidates: list[tuple[int, int, str, int, dict[str, Any], dict[str, Any], int]] = []
    for gold_line in gold["lines"]:
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
                gold_line["text"], actual_line["text"])
            errors = substitutions + insertions + deletions
            similarity = max(0, 10_000 - errors * 10_000 // max(1, ref_units, pred_units))
            predicted_coverage = _ratio(intersection, _area(actual_box))
            gold_coverage = _ratio(intersection, _area(gold_box))
            candidates.append((-similarity, -predicted_coverage, gold_line["lineId"], order,
                               gold_line, actual_line, gold_coverage))
    candidates.sort(key=lambda item: item[:4])
    used_gold: set[str] = set()
    used_actual: set[int] = set()
    matches: list[tuple[dict[str, Any], dict[str, Any], int, int]] = []
    for candidate in candidates:
        gold_line, actual_line, order = candidate[4], candidate[5], candidate[3]
        if gold_line["lineId"] not in used_gold and order not in used_actual:
            used_gold.add(gold_line["lineId"])
            used_actual.add(order)
            matches.append((gold_line, actual_line, order, candidate[6]))
    matches.sort(key=lambda item: item[2])
    actual_by_gold = {item[0]["lineId"]: item[1]["text"] for item in matches}
    matched_orders = {item[2] for item in matches}
    character_errors = 0
    for line in gold["lines"]:
        counts = _characters(line["text"], actual_by_gold.get(line["lineId"], ""))
        character_errors += sum(counts[2:])
        _edit_counts(_words(line["text"]), _words(actual_by_gold.get(line["lineId"], "")))
    for order, line in enumerate(actual):
        if order not in matched_orders:
            character_errors += sum(_characters("", line["text"])[2:])
            _edit_counts([], _words(line["text"]))
    region_by_line = _region_by_line(gold)
    observed_regions = {region_by_line[item[0]["lineId"]]
                        for item in matches if item[0]["lineId"] in region_by_line}
    actual_order: dict[str, int] = {}
    for gold_line, _actual, order, _coverage in matches:
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
                1 for region in item["memberRegionIds"] if region in observed_regions)
    return {
        "expectedLines": len(gold["lines"]), "matchedLines": len(matches),
        "lineRecallBps": _ratio(len(matches), len(gold["lines"])),
        "characterErrors": character_errors,
        "hallucinationCases": 1 if len(actual) > len(matches) else 0,
        "orderExpectedEdges": len(gold["precedenceEdges"]),
        "orderComparableEdges": comparable, "orderCorrectEdges": correct,
        "orderAccuracyBps": _ratio(correct, comparable),
        "repeatExpectedMemberships": expected_memberships,
        "repeatObservableMemberships": observable_memberships,
        "repeatRecallBps": _ratio(observable_memberships, expected_memberships),
    }


def pair_metrics(baseline: dict[str, int], successor: dict[str, int]) -> dict[str, Any]:
    matched_gain = successor["matchedLines"] - baseline["matchedLines"]
    error_reduction = baseline["characterErrors"] - successor["characterErrors"]
    hallucination_increase = successor["hallucinationCases"] - baseline["hallucinationCases"]
    return {
        "matchedLineGain": matched_gain,
        "lineRecallGainBps": successor["lineRecallBps"] - baseline["lineRecallBps"],
        "characterErrorReduction": error_reduction,
        "hallucinationIncrease": hallucination_increase,
        "orderAccuracyDeltaBps": successor["orderAccuracyBps"] - baseline["orderAccuracyBps"],
        "repeatRecallDeltaBps": successor["repeatRecallBps"] - baseline["repeatRecallBps"],
        "targetImproved": matched_gain > 0 or error_reduction > 0,
        "hallucinationNonIncrease": hallucination_increase <= 0,
    }


def candidate_terminal(measurement_valid: bool, diagnostic_pass: bool,
                       confirmation_pass: bool) -> str:
    if not measurement_valid:
        return TERMINAL_INVALID
    return TERMINAL_ALLOWED if diagnostic_pass and confirmation_pass else TERMINAL_NOT_QUALIFIED


def terminal_input_identity(accounting: dict[str, Any],
                            diagnostic: dict[str, Any],
                            confirmation: dict[str, Any], candidate: str) -> str:
    return "renderweave-r5p2-terminal-input/1.0:" + framed_sha256([
        "authority=" + AUTHORITY_IDENTITY, "assignment=" + ASSIGNMENT_IDENTITY,
        "evaluation=" + EVALUATION_IDENTITY, "threshold=" + THRESHOLD_IDENTITY,
        "stages=" + sha256(canonical_json(EXPECTED_STAGE_IDENTITIES)),
        "accounting=" + sha256(canonical_json(accounting)),
        "diagnostic=" + sha256(canonical_json(diagnostic)),
        "confirmation=" + sha256(canonical_json(confirmation)),
        "candidate=" + candidate,
    ])


def _ceil_div(value: int, divisor: int) -> int:
    return -(-value // divisor)


def _canonical_crop(crop: Sequence[int], width: int, height: int) -> list[int]:
    return [crop[0] * 10_000 // width, crop[1] * 10_000 // height,
            _ceil_div(crop[2] * 10_000, width), _ceil_div(crop[3] * 10_000, height)]


def _resize_dimensions(crop: Sequence[int], maximum: int,
                       force_long_edge: bool) -> tuple[int, int]:
    width, height = crop[2] - crop[0], crop[3] - crop[1]
    if force_long_edge:
        return ((maximum, max(1, height * maximum // width)) if width >= height
                else (max(1, width * maximum // height), maximum))
    if max(width, height) <= maximum:
        return width, height
    longest = max(width, height)
    return max(1, width * maximum // longest), max(1, height * maximum // longest)


def _static_shapes(width: int, height: int) -> list[dict[str, Any]]:
    full = [0, 0, width, height]
    output_width, output_height = _resize_dimensions(full, 768, False)
    result = [{"viewId": "view-00-overview-00", "kind": "OVERVIEW", "crop": full,
               "sourceBoundingBox": [0, 0, 10_000, 10_000],
               "width": output_width, "height": output_height}]
    columns, rows = _ceil_div(width, 1400), _ceil_div(height, 1400)
    if columns != 1 or rows != 1:
        ordinal = 0
        for row in range(rows):
            for column in range(columns):
                crop = [column * width // columns, row * height // rows,
                        (column + 1) * width // columns, (row + 1) * height // rows]
                view_width, view_height = _resize_dimensions(crop, 1400, False)
                result.append({"viewId": f"view-00-tile-{ordinal:02d}", "kind": "TILE",
                               "crop": crop,
                               "sourceBoundingBox": _canonical_crop(crop, width, height),
                               "width": view_width, "height": view_height})
                ordinal += 1
    return result[:10]


def _inspection_shapes(request: dict[str, Any], width: int,
                       height: int) -> list[dict[str, Any]]:
    request = _exact(request, {"contractVersion", "regions"},
                     "R5P2_A2_INSPECTION_REQUEST_INVALID")
    if request["contractVersion"] != "InspectionRequest/1.0" \
            or type(request["regions"]) is not list or len(request["regions"]) != 2:
        fail("R5P2_A2_INSPECTION_REQUEST_INVALID")
    result = []
    for ordinal, raw_region in enumerate(request["regions"]):
        region = _exact(raw_region, {
            "baseViewId", "boundingBox", "marginPreset", "resolutionPreset",
        }, "R5P2_A2_INSPECTION_REQUEST_INVALID")
        canonical = list(_box(region["boundingBox"], "R5P2_A2_INSPECTION_REQUEST_INVALID"))
        if (region["baseViewId"] != "view-00-overview-00"
                or region["marginPreset"] != "TIGHT_0000_BPS"
                or region["resolutionPreset"] != "INSPECT_LONG_EDGE_2400"):
            fail("R5P2_A2_INSPECTION_REQUEST_INVALID")
        crop = [canonical[0] * width // 10_000, canonical[1] * height // 10_000,
                _ceil_div(canonical[2] * width, 10_000),
                _ceil_div(canonical[3] * height, 10_000)]
        view_width, view_height = _resize_dimensions(crop, 2400, True)
        result.append({"viewId": f"view-00-inspected-{ordinal:02d}",
                       "kind": "TARGETED_CROP", "crop": crop,
                       "sourceBoundingBox": _canonical_crop(crop, width, height),
                       "width": view_width, "height": view_height})
    return result


def _decode_raster(value: Any, expected_sha: str,
                   expected_width: int, expected_height: int) -> bytes:
    if type(value) is not str:
        fail("R5P2_A2_RASTER_INVALID")
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as error:
        raise VerificationError("R5P2_A2_RASTER_INVALID") from error
    if not raw or sha256(raw) != expected_sha:
        fail("R5P2_A2_RASTER_IDENTITY_DRIFT")
    if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
        fail("R5P2_A2_RASTER_INVALID")
    width, height = struct.unpack(">II", raw[16:24])
    if width != expected_width or height != expected_height:
        fail("R5P2_A2_RASTER_DIMENSION_DRIFT")
    return raw


def _input_fingerprint(source_reference: str, raw: bytes) -> str:
    digest = hashlib.sha256()
    for frame in (b"image-only", b"r5p2-offline-evaluation",
                  source_reference.encode(), b"image/png", raw):
        digest.update(len(frame).to_bytes(4, "big"))
        digest.update(frame)
    return digest.hexdigest()


def _view_frame(ordinal: int, view: dict[str, Any]) -> str:
    box = ",".join(str(item) for item in view["sourceBoundingBox"])
    return (f"{ordinal}:{view['viewId']}:{view['sourceArtifactId']}:"
            f"{view['sourceOrdinal']}:{view['kind']}:{box}:{view['width']}x{view['height']}:"
            f"{view['providerArtifactId']}:{view['encodedBytes']}")


def _static_plan_identity(views: list[dict[str, Any]]) -> str:
    descriptors = []
    for ordinal, view in enumerate(views):
        box = ",".join(str(item) for item in view["sourceBoundingBox"])
        descriptor = framed_sha256([
            f"ordinal={ordinal}", f"view-id={view['viewId']}",
            f"source={view['sourceArtifactId']}",
            f"source-ordinal={view['sourceOrdinal']}", f"kind={view['kind']}",
            f"source-box={box}", f"dimensions={view['width']}x{view['height']}",
            f"provider-artifact={view['providerArtifactId']}",
            f"encoded-bytes={view['encodedBytes']}",
        ])
        descriptors.append("renderweave-r5p-view-descriptor/1.0:" + descriptor)
    return "renderweave-r5p-static-plan/1.0:" + framed_sha256(
        [f"plan-version={STATIC_PLAN_VERSION}", f"view-count={len(views)}"] + descriptors)


def _base_plan_identity(views: list[dict[str, Any]]) -> str:
    return "renderweave-r5p-action-base-plan/1.0:" + framed_sha256(
        [f"plan-version={STATIC_PLAN_VERSION}", f"view-count={len(views)}"]
        + [_view_frame(index, view) for index, view in enumerate(views)])


def _action_request_identity(base_plan: str, source_sha: str, width: int,
                             height: int, request: dict[str, Any]) -> str:
    frames = ["contract=InspectionRequest/1.0", "base-plan=" + base_plan,
              "policy=" + ACTION_POLICY_IDENTITY,
              f"source=0:{source_sha}:{width}x{height}"]
    for region in request["regions"]:
        frames.append("region=" + region["baseViewId"] + ":"
                      + ",".join(map(str, region["boundingBox"])) + ":"
                      + region["marginPreset"] + ":" + region["resolutionPreset"])
    return "renderweave-inspection-request/1.0:" + framed_sha256(frames)


def _successor_plan_identity(base_plan: str, request_identity: str,
                             views: list[dict[str, Any]]) -> str:
    frames = [f"plan-version={SUCCESSOR_PLAN_VERSION}", "base-plan=" + base_plan,
              "request=" + request_identity, "policy=" + ACTION_POLICY_IDENTITY,
              f"view-count={len(views)}"]
    frames.extend(_view_frame(index, view) for index, view in enumerate(views))
    return SUCCESSOR_PLAN_VERSION + ":" + framed_sha256(frames)


def _validate_view(value: Any, expected: dict[str, Any], source_sha: str,
                   source_width: int, source_height: int,
                   ordinal: int) -> tuple[dict[str, Any], bytes]:
    view = _exact(value, {
        "planOrdinal", "viewId", "sourceArtifactId", "sourceOrdinal", "kind",
        "sourceBoundingBox", "width", "height", "sourceWidth", "sourceHeight",
        "crop", "providerArtifactId", "mediaType", "encodedBytes", "encodedSha256",
        "encodedImage",
    }, "R5P2_A2_VIEW_SCHEMA_INVALID")
    if (view["planOrdinal"] != ordinal or view["viewId"] != expected["viewId"]
            or view["kind"] != expected["kind"] or view["sourceArtifactId"] != source_sha
            or view["sourceOrdinal"] != 0
            or view["sourceBoundingBox"] != expected["sourceBoundingBox"]
            or view["width"] != expected["width"] or view["height"] != expected["height"]
            or view["sourceWidth"] != source_width or view["sourceHeight"] != source_height
            or view["crop"] != expected["crop"] or view["mediaType"] != "image/png"):
        fail("R5P2_A2_VIEW_COVERAGE_DRIFT")
    encoded_sha = view["encodedSha256"]
    if type(encoded_sha) is not str or not SHA256_PATTERN.fullmatch(encoded_sha) \
            or view["providerArtifactId"] != encoded_sha:
        fail("R5P2_A2_VIEW_IDENTITY_DRIFT")
    image = _decode_raster(view["encodedImage"], encoded_sha, view["width"], view["height"])
    if type(view["encodedBytes"]) is not int or view["encodedBytes"] != len(image):
        fail("R5P2_A2_VIEW_IDENTITY_DRIFT")
    return view, image


def _validate_gold(value: Any) -> dict[str, Any]:
    gold = _exact(value, {"lines", "regionIds", "precedenceEdges", "repeatGroups"},
                  "R5P2_A2_GOLD_SCHEMA_INVALID")
    if type(gold["lines"]) is not list or not gold["lines"] or len(gold["lines"]) > 512:
        fail("R5P2_A2_GOLD_SCHEMA_INVALID")
    line_ids: set[str] = set()
    for raw_line in gold["lines"]:
        line = _exact(raw_line, {"lineId", "text", "box"},
                      "R5P2_A2_GOLD_SCHEMA_INVALID")
        if type(line["lineId"]) is not str or line["lineId"] in line_ids \
                or type(line["text"]) is not str or not line["text"]:
            fail("R5P2_A2_GOLD_SCHEMA_INVALID")
        line_ids.add(line["lineId"])
        _box(line["box"], "R5P2_A2_GOLD_SCHEMA_INVALID")
    if (type(gold["regionIds"]) is not list
            or len(set(gold["regionIds"])) != len(gold["regionIds"])
            or any(type(item) is not str for item in gold["regionIds"])
            or type(gold["precedenceEdges"]) is not list
            or type(gold["repeatGroups"]) is not list):
        fail("R5P2_A2_GOLD_SCHEMA_INVALID")
    return gold


def _branch_request_identity(branch: str, plan_version: str, plan_identity: str,
                             views: list[dict[str, Any]]) -> str:
    frames = ["contract=renderweave-r5p2-complete-branch-process/1.0",
              "branch=" + branch, "plan=" + plan_version + ":" + plan_identity,
              "policy=" + ACQUISITION_POLICY_SHA256,
              f"artifact-count={len(views)}"]
    for ordinal, view in enumerate(views):
        frames.append(f"artifact={ordinal}:{view['providerArtifactId']}:image/png:"
                      f"{view['width']}x{view['height']}:{view['encodedBytes']}")
    return "renderweave-r5p2-branch-request/1.0:" + framed_sha256(frames)


def _view_trace(views: list[dict[str, Any]],
                observations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result = []
    for ordinal, (view, observed) in enumerate(zip(views, observations, strict=True)):
        box = ",".join(map(str, view["sourceBoundingBox"]))
        view_identity = "renderweave-r5p2-executed-view/1.0:" + framed_sha256([
            f"ordinal={ordinal}", f"view-id={view['viewId']}",
            f"source={view['sourceArtifactId']}",
            f"source-ordinal={view['sourceOrdinal']}", f"kind={view['kind']}",
            f"source-box={box}", f"dimensions={view['width']}x{view['height']}",
            f"provider-artifact={view['providerArtifactId']}",
            f"encoded-bytes={view['encodedBytes']}",
        ])
        result.append({
            "planOrdinal": ordinal, "viewId": view["viewId"], "kind": view["kind"],
            "viewIdentity": view_identity, "sourceArtifactId": view["sourceArtifactId"],
            "providerArtifactId": view["providerArtifactId"], "width": view["width"],
            "height": view["height"], "encodedBytes": view["encodedBytes"],
            "encodedSha256": view["encodedSha256"],
            "observationCount": len(observed["lines"]),
        })
    return result


def _observation_frames(observations: list[dict[str, Any]],
                        views: list[dict[str, Any]], include_provenance: bool) -> list[str]:
    frames = ["policy=" + ACQUISITION_POLICY_SHA256,
              "capability=" + CAPABILITY_IDENTITY]
    if include_provenance:
        provenance = (
            "Provenance[capabilityIdentity=" + CAPABILITY_IDENTITY
            + ", adapterIdentity=rapidocr-local-process/1.0"
            + ", engine=rapidocr-openvino-ppocrv6-small"
            + ", engineVersion=rapidocr-3.9.2+openvino-2026.0.0"
            + ", modelManifestSha256=c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5"
            + ", preprocessingIdentity=explicit-bgr/1.0"
            + ", postprocessingIdentity=rapidocr-lines/1.0"
            + ", readingOrderDerivationIdentity=top-left-canonical/1.0"
            + ", projectionIdentity=v45-source-to-candidate/1.0"
            + ", confidenceScaleIdentity=basis-points/1.0"
            + ", confidenceBucketProjectionIdentity=v45-confidence-buckets/1.0"
            + ", canonicalizationIdentity=unicode-nfc-whitespace-collapse/1.0]"
        )
        frames.append("provenance=" + provenance)
    for ordinal, (view, observed) in enumerate(zip(views, observations, strict=True)):
        frames.append(f"artifact={ordinal}:{view['providerArtifactId']}:"
                      f"{view['width']}x{view['height']}")
        for line_ordinal, line in enumerate(observed["lines"]):
            frames.append(f"line=ocr-{ordinal:02d}-{line_ordinal:03d}:{line_ordinal}:"
                          f"{line['left']},{line['top']},{line['right']},{line['bottom']}:"
                          f"{line['confidenceBps']}:{line['text']}")
    return frames


def _reconciled_identity(source_sha: str, width: int, height: int,
                         outcome: reconciliation.Outcome) -> str:
    frames = ["projection=" + reconciliation.PROJECTION_IDENTITY,
              "reconciliation=" + reconciliation.POLICY_IDENTITY,
              f"source={source_sha}:{width}x{height}",
              f"counts={outcome.input_count}:{outcome.cluster_count}"]
    for line in outcome.representatives:
        box = line.source_box
        frames.append(f"line={box.left},{box.top},{box.right},{box.bottom}:"
                      f"{line.confidence_bps}:{line.text}:{line.view_ordinal}:"
                      f"{line.line_ordinal}")
    return "renderweave-r5p2-reconciled-metric-input/1.0:" + framed_sha256(frames)


def _resource_identity(resources: dict[str, Any]) -> str:
    return "renderweave-r5p2-branch-resources/1.0:" + framed_sha256([
        f"total-views={resources['totalViews']}",
        f"inspected-views={resources['inspectedViews']}",
        f"total-encoded-bytes={resources['totalEncodedBytes']}",
        f"total-pixels={resources['totalPixels']}",
        f"inspected-pixels={resources['inspectedPixels']}",
        f"additional-visual-tokens={resources['additionalVisualTokens']}",
    ])


def validate_resources(branch: str, views: list[dict[str, Any]], value: Any,
                       expected_inspected_views: int) -> dict[str, Any]:
    resources = _exact(value, {
        "totalViews", "inspectedViews", "totalEncodedBytes", "totalPixels",
        "inspectedPixels", "additionalVisualTokens", "localTransformMillis",
    }, "R5P2_A2_RESOURCE_ACCOUNTING_INVALID")
    if (branch not in ("BASELINE", "SUCCESSOR")
            or type(expected_inspected_views) is not int
            or expected_inspected_views < 0
            or expected_inspected_views > len(views)):
        fail("R5P2_A2_RESOURCE_ACCOUNTING_INVALID")
    total_bytes = sum(item["encodedBytes"] for item in views)
    total_pixels = sum(item["width"] * item["height"] for item in views)
    inspected = views[len(views) - expected_inspected_views:] \
        if expected_inspected_views else []
    inspected_pixels = sum(item["width"] * item["height"] for item in inspected)
    additional_tokens = sum(_ceil_div(item["width"] * item["height"], 1_024) + 2
                            for item in inspected)
    expected = {
        "totalViews": len(views), "inspectedViews": expected_inspected_views,
        "totalEncodedBytes": total_bytes, "totalPixels": total_pixels,
        "inspectedPixels": inspected_pixels,
        "additionalVisualTokens": additional_tokens,
    }
    if any(resources.get(key) != expected_value for key, expected_value in expected.items()) \
            or type(resources["localTransformMillis"]) is not int \
            or resources["localTransformMillis"] < 0:
        fail("R5P2_A2_RESOURCE_ACCOUNTING_INVALID")
    return {**resources, "acquisitionMicros": 0}


def _cohort_summary(results: list[dict[str, Any]], confirmation: bool) -> dict[str, Any]:
    baseline = [item["baseline"]["metrics"] for item in results]
    successor = [item["successor"]["metrics"] for item in results]
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
    summary = {
        "caseCount": len(results),
        "targetImprovementCases": sum(1 for item in results
                                      if item["pairMetrics"]["targetImproved"]),
        "hallucinationNonIncreaseCases": sum(1 for item in results
                                             if item["pairMetrics"]["hallucinationNonIncrease"]),
        "baselineMatchedLines": baseline_matched, "successorMatchedLines": successor_matched,
        "baselineLineRecallBps": baseline_recall, "successorLineRecallBps": successor_recall,
        "lineRecallGainBps": successor_recall - baseline_recall,
        "baselineCharacterErrors": baseline_errors,
        "successorCharacterErrors": successor_errors,
        "characterErrorReduction": baseline_errors - successor_errors,
        "baselineHallucinations": baseline_hallucinations,
        "successorHallucinations": successor_hallucinations,
        "baselineOrderAccuracyBps": baseline_order,
        "successorOrderAccuracyBps": successor_order,
        "orderAccuracyDeltaBps": successor_order - baseline_order,
        "baselineRepeatRecallBps": baseline_repeat,
        "successorRepeatRecallBps": successor_repeat,
        "repeatRecallDeltaBps": successor_repeat - baseline_repeat,
        "thresholdPass": False,
    }
    per_case = (summary["targetImprovementCases"] == len(results)
                and summary["hallucinationNonIncreaseCases"] == len(results))
    aggregate = (not confirmation
                 or summary["lineRecallGainBps"] >= 500
                 and summary["characterErrorReduction"] >= 1
                 and summary["orderAccuracyDeltaBps"] >= -100
                 and summary["repeatRecallDeltaBps"] >= -100)
    summary["thresholdPass"] = per_case and aggregate
    return summary


ClientFactory = Callable[[int], PublicBranchProcessClient]


def _evaluate_branch(client: PublicBranchProcessClient, case: dict[str, Any],
                     branch: dict[str, Any], expected_shapes: list[dict[str, Any]],
                     plan_identity: str, gold: dict[str, Any]) -> dict[str, Any]:
    if type(branch["views"]) is not list or len(branch["views"]) != len(expected_shapes):
        fail("R5P2_A2_BRANCH_PLAN_INCOMPLETE")
    views: list[dict[str, Any]] = []
    artifacts: list[BranchArtifact] = []
    for ordinal, (raw_view, shape) in enumerate(zip(branch["views"], expected_shapes, strict=True)):
        view, encoded = _validate_view(raw_view, shape, case["rawFixtureSha256"],
                                       case["width"], case["height"], ordinal)
        views.append(view)
        artifacts.append(BranchArtifact(
            view["providerArtifactId"], ordinal, "image/png",
            view["width"], view["height"], encoded))
    observations = client.acquire_complete_branch(
        artifacts, [item.artifact_id for item in artifacts])
    projected: list[reconciliation.ProjectedLine] = []
    for view_ordinal, (view, observed) in enumerate(zip(views, observations, strict=True)):
        for line_ordinal, line in enumerate(observed["lines"]):
            projected.append(reconciliation.project(
                f"ocr-{view_ordinal:02d}-{line_ordinal:03d}",
                case["rawFixtureSha256"], view_ordinal, line_ordinal,
                view["width"], view["height"], case["width"], case["height"],
                reconciliation.PixelBox(*view["crop"]),
                reconciliation.PixelBox(
                    line["left"], line["top"], line["right"], line["bottom"]),
                line["confidenceBps"], line["text"]))
    outcome = reconciliation.reconcile(projected)
    metric_lines = [{"text": line.text,
                     "box": [line.source_box.left, line.source_box.top,
                             line.source_box.right, line.source_box.bottom]}
                    for line in outcome.representatives]
    metrics = score_case(gold, metric_lines)
    traces = _view_trace(views, observations)
    request_identity = _branch_request_identity(
        branch["branch"], branch["planVersion"], plan_identity, views)
    resources = validate_resources(
        branch["branch"], views, branch["resources"],
        0 if branch["branch"] == "BASELINE" else 2)
    return {
        "branch": branch["branch"], "planVersion": branch["planVersion"],
        "planIdentity": plan_identity, "requestIdentity": request_identity,
        "plannedViewCount": len(views), "acquiredViewCount": len(observations),
        "branchAcquisitionProcesses": 1, "artifactViews": len(views),
        "viewTrace": traces,
        "totalEncodedBytes": sum(item["encodedBytes"] for item in views),
        "totalPixels": sum(item["width"] * item["height"] for item in views),
        "rawObservationCount": len(projected), "projectedObservationCount": len(projected),
        "reconciledObservationCount": len(outcome.representatives),
        "rawObservationIdentity": "renderweave-r5p2-raw-observation/1.0:"
                                  + framed_sha256(_observation_frames(
                                      observations, views, False)),
        "canonicalObservationIdentity": "renderweave-r5p2-canonical-observation/1.0:"
                                        + framed_sha256(_observation_frames(
                                            observations, views, True)),
        "reconciledMetricInputIdentity": _reconciled_identity(
            case["rawFixtureSha256"], case["width"], case["height"], outcome),
        "metrics": metrics, "resourceIdentity": _resource_identity(resources),
        "resources": resources,
        "externalProviderUsage": {"attempts": 0, "reservations": 0,
                                  "costMicrosCny": 0},
        "apiKeyReads": 0,
    }


def _evaluate_case(client: PublicBranchProcessClient,
                   case: dict[str, Any], expected: tuple[str, str, str, str]) -> dict[str, Any]:
    case = _exact(case, {
        "caseId", "caseIdentity", "cohort", "partition", "width", "height",
        "normalizationSourceReference", "rawFixtureSha256", "rawBytes", "normalization",
        "gold", "inspectionRequest", "branches",
    }, "R5P2_A2_CASE_SCHEMA_INVALID")
    if tuple(case[key] for key in ("caseId", "caseIdentity", "cohort", "partition")) != expected:
        fail("R5P2_A2_CASE_ASSIGNMENT_DRIFT")
    width = _integer(case["width"], 1, 4096, "R5P2_A2_CASE_SCHEMA_INVALID")
    height = _integer(case["height"], 1, 4096, "R5P2_A2_CASE_SCHEMA_INVALID")
    raw_sha = case["rawFixtureSha256"]
    if type(raw_sha) is not str or not SHA256_PATTERN.fullmatch(raw_sha):
        fail("R5P2_A2_CASE_SCHEMA_INVALID")
    raw = _decode_raster(case["rawBytes"], raw_sha, width, height)
    normalization = _exact(case["normalization"], {
        "inputFingerprint", "normalizedArtifactId", "mediaType", "encodedBytes",
        "width", "height", "blobWrites", "blobReads", "normalizedBytes",
    }, "R5P2_A2_NORMALIZATION_INVALID")
    normalized = _decode_raster(normalization["normalizedBytes"], raw_sha, width, height)
    fingerprint = _input_fingerprint(case["normalizationSourceReference"], raw)
    if (normalized != raw or normalization["inputFingerprint"] != fingerprint
            or normalization["normalizedArtifactId"] != raw_sha
            or normalization["mediaType"] != "image/png"
            or normalization["encodedBytes"] != len(raw)
            or normalization["width"] != width or normalization["height"] != height
            or normalization["blobWrites"] != 1 or normalization["blobReads"] != 1):
        fail("R5P2_A2_NORMALIZATION_INVALID")
    gold = _validate_gold(case["gold"])
    if type(case["branches"]) is not list or len(case["branches"]) != 2:
        fail("R5P2_A2_BRANCH_SCHEMA_INVALID")
    baseline_input, successor_input = case["branches"]
    baseline_input = _exact(baseline_input, {
        "branch", "planVersion", "planIdentity", "actionRequestIdentity",
        "actionPolicyIdentity", "resources", "views",
    }, "R5P2_A2_BRANCH_SCHEMA_INVALID")
    successor_input = _exact(successor_input, set(baseline_input),
                             "R5P2_A2_BRANCH_SCHEMA_INVALID")
    static_shapes = _static_shapes(width, height)
    inspected_shapes = _inspection_shapes(case["inspectionRequest"], width, height)
    expected_successor_shapes = static_shapes + inspected_shapes
    if (baseline_input["branch"] != "BASELINE"
            or baseline_input["planVersion"] != STATIC_PLAN_VERSION
            or baseline_input["actionRequestIdentity"] is not None
            or baseline_input["actionPolicyIdentity"] is not None
            or successor_input["branch"] != "SUCCESSOR"
            or successor_input["planVersion"] != SUCCESSOR_PLAN_VERSION):
        fail("R5P2_A2_BRANCH_SCHEMA_INVALID")
    validated_static = [_validate_view(raw_view, shape, raw_sha, width, height, ordinal)[0]
                        for ordinal, (raw_view, shape) in enumerate(
                            zip(baseline_input["views"], static_shapes, strict=True))]
    static_plan = _static_plan_identity(validated_static)
    if baseline_input["planIdentity"] != static_plan:
        fail("R5P2_A2_STATIC_PLAN_IDENTITY_DRIFT")
    base_plan = _base_plan_identity(validated_static)
    action_request = _action_request_identity(
        base_plan, raw_sha, width, height, case["inspectionRequest"])
    if (successor_input["actionRequestIdentity"] != action_request
            or successor_input["actionPolicyIdentity"] != ACTION_POLICY_IDENTITY):
        fail("R5P2_A2_ACTION_IDENTITY_DRIFT")
    validated_successor = [
        _validate_view(raw_view, shape, raw_sha, width, height, ordinal)[0]
        for ordinal, (raw_view, shape) in enumerate(
            zip(successor_input["views"], expected_successor_shapes, strict=True))]
    successor_plan = _successor_plan_identity(base_plan, action_request, validated_successor)
    if successor_input["planIdentity"] != successor_plan:
        fail("R5P2_A2_SUCCESSOR_PLAN_IDENTITY_DRIFT")
    baseline = _evaluate_branch(
        client, case, baseline_input, static_shapes, static_plan, gold)
    successor = _evaluate_branch(
        client, case, successor_input, expected_successor_shapes, successor_plan, gold)
    return {
        "caseId": case["caseId"], "caseIdentity": case["caseIdentity"],
        "cohort": case["cohort"], "partition": case["partition"],
        "normalization": {
            "sourceReference": case["normalizationSourceReference"],
            "rawFixtureSha256": raw_sha, "inputFingerprint": fingerprint,
            "normalizedArtifactId": raw_sha, "mediaType": "image/png",
            "encodedBytes": len(raw), "width": width, "height": height,
            "blobWrites": 1, "blobReads": 1,
        },
        "baseline": baseline, "successor": successor,
        "pairMetrics": pair_metrics(baseline["metrics"], successor["metrics"]),
    }


def replay(document: dict[str, Any], client_factory: ClientFactory) -> dict[str, Any]:
    document = _validate_protocol_header(document)
    run_results: list[list[dict[str, Any]]] = []
    total_artifact_views = 0
    total_branch_processes = 0
    total_probes = 0
    for run_index, raw_run in enumerate(document["runs"], start=1):
        run = _exact(raw_run, {"runOrdinal", "cases"}, "R5P2_A2_RUN_SCHEMA_INVALID")
        if run["runOrdinal"] != run_index or type(run["cases"]) is not list \
                or len(run["cases"]) != 12:
            fail("R5P2_A2_RUN_SCHEMA_INVALID")
        client = client_factory(run_index)
        client.probe()
        cases = [_evaluate_case(client, raw_case, CASES[index])
                 for index, raw_case in enumerate(run["cases"])]
        if client.accounting.capability_probe_processes != 1 \
                or client.accounting.branch_acquisition_processes != 24:
            fail("R5P2_A2_PROCESS_ACCOUNTING_INVALID")
        total_probes += client.accounting.capability_probe_processes
        total_branch_processes += client.accounting.branch_acquisition_processes
        total_artifact_views += client.accounting.artifact_views
        run_results.append(cases)
    equivalent_cases = equivalent_branches = 0
    for first, second in zip(run_results[0], run_results[1], strict=True):
        baseline_equal = first["baseline"] == second["baseline"]
        successor_equal = first["successor"] == second["successor"]
        equivalent_branches += int(baseline_equal) + int(successor_equal)
        if (first["caseId"] == second["caseId"]
                and first["caseIdentity"] == second["caseIdentity"]
                and first["cohort"] == second["cohort"]
                and first["partition"] == second["partition"]
                and first["normalization"] == second["normalization"]
                and first["pairMetrics"] == second["pairMetrics"]
                and baseline_equal and successor_equal):
            equivalent_cases += 1
    if equivalent_cases != 12 or equivalent_branches != 24:
        fail("R5P2_A2_SECOND_RUN_DRIFT")
    first = run_results[0]
    diagnostic = _cohort_summary(first[:8], False)
    confirmation = _cohort_summary(first[8:], True)
    transit_pair = first[0]["pairMetrics"]
    transit = {
        "caseId": "transit-board-v3", "targetImproved": transit_pair["targetImproved"],
        "hallucinationNonIncrease": transit_pair["hallucinationNonIncrease"],
        "pass": transit_pair["targetImproved"] and transit_pair["hallucinationNonIncrease"],
    }
    quality = diagnostic["thresholdPass"] and confirmation["thresholdPass"]
    candidate = candidate_terminal(True, diagnostic["thresholdPass"],
                                   confirmation["thresholdPass"])
    accounting = {
        "capabilityProbeProcesses": total_probes,
        "branchAcquisitionProcesses": total_branch_processes,
        "artifactViews": total_artifact_views,
        "normalizationExecutions": 24, "actionExecutions": 24,
    }
    terminal_input = terminal_input_identity(
        accounting, diagnostic, confirmation, candidate)
    evidence = {
        "evidenceVersion": EVIDENCE_VERSION, "assurance": ASSURANCE,
        "authorityIdentity": AUTHORITY_IDENTITY, "assignmentIdentity": ASSIGNMENT_IDENTITY,
        "fixtureSetIdentity": FIXTURE_SET_IDENTITY, "evaluationIdentity": EVALUATION_IDENTITY,
        "thresholdIdentity": THRESHOLD_IDENTITY,
        "independentEvaluatorIdentity": INDEPENDENT_EVALUATOR_IDENTITY,
        "stageIdentities": copy.deepcopy(EXPECTED_STAGE_IDENTITIES),
        "accounting": accounting,
        "determinism": {"comparedCases": 12, "equivalentCases": 12,
                        "comparedBranches": 24, "equivalentBranches": 24,
                        "deterministic": True,
                        "verdictCode": "R5P2_A2_TWO_RUN_DETERMINISTIC"},
        "cases": first, "diagnosticSummary": diagnostic,
        "confirmationSummary": confirmation, "transitBoardV3": transit,
        "measurementValid": True, "qualityObservationPass": quality,
        "terminalInputIdentity": terminal_input, "candidateTerminal": candidate,
        "holdoutAccess": {"role": "INDEPENDENT_REPLAY", "caseId": "product-catalog-v5",
                          "status": "SEALED", "goldMetricReads": 1},
        "accessAudit": {"producerReportReadsDuringReplay": 0,
                        "producerMetricReadsDuringReplay": 0,
                        "producerDecisionReadsDuringReplay": 0},
        "externalProviderUsage": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
        "apiKeyReads": 0,
        "payloadBoundary": {"imagePersisted": False, "geometryPayloadPersisted": False,
                            "ocrTextPersisted": False, "goldTextPersisted": False,
                            "promptCandidateOrRootDocumentPersisted": False},
        "firstFailureStage": None, "terminalCode": REPLAY_COMPLETE,
    }
    return validate_evidence(evidence)


def _payload_safe(value: Any) -> None:
    if type(value) is dict:
        for key, child in value.items():
            lowered = key.lower()
            if (lowered == "text" or "base64" in lowered or "boundingbox" in lowered
                    or "sourcepixelbox" in lowered or "rawbytes" in lowered
                    or "normalizedbytes" in lowered or "encodedimage" in lowered):
                fail("R5P2_A2_DECODED_PAYLOAD_FORBIDDEN")
            _payload_safe(child)
    elif type(value) is list:
        for child in value:
            _payload_safe(child)
    elif type(value) is str:
        lowered = value.lower()
        if "data:image" in lowered or lowered.startswith("bearer "):
            fail("R5P2_A2_DECODED_PAYLOAD_FORBIDDEN")


def validate_evidence(value: Any) -> dict[str, Any]:
    evidence = _exact(value, {
        "evidenceVersion", "assurance", "authorityIdentity", "assignmentIdentity",
        "fixtureSetIdentity", "evaluationIdentity", "thresholdIdentity",
        "independentEvaluatorIdentity", "stageIdentities", "accounting", "determinism",
        "cases", "diagnosticSummary", "confirmationSummary", "transitBoardV3",
        "measurementValid", "qualityObservationPass", "terminalInputIdentity",
        "candidateTerminal", "holdoutAccess", "accessAudit", "externalProviderUsage",
        "apiKeyReads", "payloadBoundary", "firstFailureStage", "terminalCode",
    }, "R5P2_A2_EVIDENCE_SCHEMA_INVALID")
    _payload_safe(evidence)
    expected_header = {
        "evidenceVersion": EVIDENCE_VERSION, "assurance": ASSURANCE,
        "authorityIdentity": AUTHORITY_IDENTITY, "assignmentIdentity": ASSIGNMENT_IDENTITY,
        "fixtureSetIdentity": FIXTURE_SET_IDENTITY, "evaluationIdentity": EVALUATION_IDENTITY,
        "thresholdIdentity": THRESHOLD_IDENTITY,
        "independentEvaluatorIdentity": INDEPENDENT_EVALUATOR_IDENTITY,
    }
    if any(evidence[key] != expected_header[key] for key in expected_header) \
            or evidence["stageIdentities"] != EXPECTED_STAGE_IDENTITIES:
        fail("R5P2_A2_EVIDENCE_AUTHORITY_INVALID")
    if evidence["externalProviderUsage"] != {
            "attempts": 0, "reservations": 0, "costMicrosCny": 0} \
            or evidence["apiKeyReads"] != 0:
        fail("R5P2_A2_EVIDENCE_PROVIDER_USAGE_NONZERO")
    access = evidence["accessAudit"]
    if access != {"producerReportReadsDuringReplay": 0,
                  "producerMetricReadsDuringReplay": 0,
                  "producerDecisionReadsDuringReplay": 0}:
        fail("R5P2_A2_EVIDENCE_ACCESS_INVALID")
    if type(evidence["measurementValid"]) is not bool:
        fail("R5P2_A2_EVIDENCE_DECISION_INVALID")
    if evidence["measurementValid"]:
        expected_accounting = {"capabilityProbeProcesses": 2,
                               "branchAcquisitionProcesses": 48,
                               "artifactViews": 136,
                               "normalizationExecutions": 24,
                               "actionExecutions": 24}
        if evidence["accounting"] != expected_accounting:
            fail("R5P2_A2_EVIDENCE_ACCOUNTING_INVALID")
        if type(evidence["cases"]) is not list or len(evidence["cases"]) != 12:
            fail("R5P2_A2_EVIDENCE_CASE_INVALID")
        for item, expected in zip(evidence["cases"], CASES, strict=True):
            if tuple(item[key] for key in ("caseId", "caseIdentity", "cohort", "partition")) \
                    != expected:
                fail("R5P2_A2_EVIDENCE_CASE_INVALID")
            if item["pairMetrics"] != pair_metrics(
                    item["baseline"]["metrics"], item["successor"]["metrics"]):
                fail("R5P2_A2_EVIDENCE_CASE_INVALID")
        if evidence["terminalCode"] != REPLAY_COMPLETE or evidence["firstFailureStage"] is not None:
            fail("R5P2_A2_EVIDENCE_DECISION_INVALID")
        expected_candidate = candidate_terminal(
            True, evidence["diagnosticSummary"]["thresholdPass"],
            evidence["confirmationSummary"]["thresholdPass"])
        if evidence["candidateTerminal"] != expected_candidate \
                or evidence["qualityObservationPass"] != (
                    expected_candidate == TERMINAL_ALLOWED):
            fail("R5P2_A2_EVIDENCE_DECISION_INVALID")
        if evidence["terminalInputIdentity"] != terminal_input_identity(
                evidence["accounting"], evidence["diagnosticSummary"],
                evidence["confirmationSummary"], expected_candidate):
            fail("R5P2_A2_EVIDENCE_DECISION_INVALID")
    else:
        if (evidence["candidateTerminal"] != TERMINAL_INVALID
                or evidence["terminalCode"] != TERMINAL_INVALID
                or type(evidence["firstFailureStage"]) is not str
                or not CODE_PATTERN.fullmatch(evidence["firstFailureStage"])):
            fail("R5P2_A2_EVIDENCE_DECISION_INVALID")
    if type(evidence["terminalInputIdentity"]) is not str \
            or not IDENTITY_PATTERN.fullmatch(evidence["terminalInputIdentity"]):
        fail("R5P2_A2_EVIDENCE_DECISION_INVALID")
    return evidence


def synthetic_evidence_for_tests() -> dict[str, Any]:
    zero_metrics = {
        "expectedLines": 0, "matchedLines": 0, "lineRecallBps": 10_000,
        "characterErrors": 0, "hallucinationCases": 0, "orderExpectedEdges": 0,
        "orderComparableEdges": 0, "orderCorrectEdges": 0, "orderAccuracyBps": 10_000,
        "repeatExpectedMemberships": 0, "repeatObservableMemberships": 0,
        "repeatRecallBps": 10_000,
    }
    cases = []
    for index, expected in enumerate(CASES):
        branch = {"metrics": copy.deepcopy(zero_metrics)}
        cases.append({"caseId": expected[0], "caseIdentity": expected[1],
                      "cohort": expected[2], "partition": expected[3],
                      "normalization": {}, "baseline": copy.deepcopy(branch),
                      "successor": copy.deepcopy(branch),
                      "pairMetrics": pair_metrics(zero_metrics, zero_metrics)})
    summary8 = _cohort_summary(cases[:8], False)
    summary4 = _cohort_summary(cases[8:], True)
    evidence = {
        "evidenceVersion": EVIDENCE_VERSION, "assurance": ASSURANCE,
        "authorityIdentity": AUTHORITY_IDENTITY, "assignmentIdentity": ASSIGNMENT_IDENTITY,
        "fixtureSetIdentity": FIXTURE_SET_IDENTITY, "evaluationIdentity": EVALUATION_IDENTITY,
        "thresholdIdentity": THRESHOLD_IDENTITY,
        "independentEvaluatorIdentity": INDEPENDENT_EVALUATOR_IDENTITY,
        "stageIdentities": copy.deepcopy(EXPECTED_STAGE_IDENTITIES),
        "accounting": {"capabilityProbeProcesses": 2, "branchAcquisitionProcesses": 48,
                       "artifactViews": 136, "normalizationExecutions": 24,
                       "actionExecutions": 24},
        "determinism": {"comparedCases": 12, "equivalentCases": 12,
                        "comparedBranches": 24, "equivalentBranches": 24,
                        "deterministic": True,
                        "verdictCode": "R5P2_A2_TWO_RUN_DETERMINISTIC"},
        "cases": cases, "diagnosticSummary": summary8, "confirmationSummary": summary4,
        "transitBoardV3": {"caseId": "transit-board-v3", "targetImproved": False,
                           "hallucinationNonIncrease": True, "pass": False},
        "measurementValid": True, "qualityObservationPass": False,
        "terminalInputIdentity": "renderweave-r5p2-terminal-input/1.0:" + "0" * 64,
        "candidateTerminal": TERMINAL_NOT_QUALIFIED,
        "holdoutAccess": {"role": "INDEPENDENT_REPLAY", "caseId": "product-catalog-v5",
                          "status": "SEALED", "goldMetricReads": 1},
        "accessAudit": {"producerReportReadsDuringReplay": 0,
                        "producerMetricReadsDuringReplay": 0,
                        "producerDecisionReadsDuringReplay": 0},
        "externalProviderUsage": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
        "apiKeyReads": 0,
        "payloadBoundary": {"imagePersisted": False, "geometryPayloadPersisted": False,
                            "ocrTextPersisted": False, "goldTextPersisted": False,
                            "promptCandidateOrRootDocumentPersisted": False},
        "firstFailureStage": None, "terminalCode": REPLAY_COMPLETE,
    }
    evidence["terminalInputIdentity"] = terminal_input_identity(
        evidence["accounting"], evidence["diagnosticSummary"],
        evidence["confirmationSummary"], evidence["candidateTerminal"])
    return validate_evidence(evidence)


def _invalid_evidence(code: str) -> dict[str, Any]:
    if not CODE_PATTERN.fullmatch(code):
        code = "R5P2_A2_REPLAY_FAILED"
    evidence = synthetic_evidence_for_tests()
    evidence.update({
        "accounting": {"capabilityProbeProcesses": 0, "branchAcquisitionProcesses": 0,
                       "artifactViews": 0, "normalizationExecutions": 0,
                       "actionExecutions": 0},
        "cases": [], "measurementValid": False, "qualityObservationPass": False,
        "terminalInputIdentity": "renderweave-r5p2-terminal-input/1.0:"
                                 + sha256(code.encode()),
        "candidateTerminal": TERMINAL_INVALID, "firstFailureStage": code,
        "terminalCode": TERMINAL_INVALID,
    })
    return validate_evidence(evidence)


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
        command = (sys.executable, str(pathlib.Path(args.adapter).resolve(strict=True)))
        model_root = pathlib.Path(args.model_root).resolve(strict=True)
        evidence = replay(document, lambda _run: PublicBranchProcessClient(
            command, model_root, CAPABILITY_IDENTITY, timeout_seconds=60))
    except Exception as error:
        evidence = _invalid_evidence(str(error))
    encoded = canonical_json(envelope(evidence))
    if len(encoded) > MAX_EVIDENCE_BYTES:
        fail("R5P2_A2_EVIDENCE_BYTES_INVALID")
    _write_new(output, encoded)
    sys.stdout.write(evidence["terminalCode"] + "\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception:
        sys.stderr.write("R5P2_A2_PROCESS_FAILED\n")
        raise SystemExit(2)
