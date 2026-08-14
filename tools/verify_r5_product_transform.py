#!/usr/bin/env python3
"""Fail-closed audit of the rejected R5 product-transform producer report.

The historical implementation did not expose raster bytes, OCR observations, or independently
grounded Provider accounting to this process. This tool can check report consistency only; it must
never issue A2 or admission.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
from typing import Any

import offline_quality_resources as quality_resources
from offline_json_contract import (
    JAVA_INT_MAX,
    JAVA_LONG_MAX,
    exact_object,
    payload_safe,
    raw_payload_safe,
    same_json_value,
    strict_nonnegative_int,
    strict_positive_int,
)


EVIDENCE_VERSION = "renderweave-r5-product-transform-evidence/1.0"
IDENTITY_VERSION = "renderweave-r5-product-transform-evidence/1.0"
ENVELOPE_VERSION = "renderweave-r5-product-transform-envelope/1.0"
ASSIGNMENT_VERSION = "renderweave-r5-product-transform-assignment/1.0"
TRANSFORM_VERSION = "renderweave-r5-product-raster-transform/1.0"
EVALUATION_VERSION = "renderweave-r5-product-transform-evaluation/1.0"
RUNNER_VERSION = "renderweave-r5-product-transform-runner/1.0"
EXPECTED_POLICY = "AcquisitionPolicy/1.0:32ade47685c07163e10f77be8b8ed46e420af7b7d381e1363d30886a19e26c52"
EXPECTED_CAPABILITY = "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"
EXPECTED_ANNOTATION = "renderweave-layered-annotation-set/2.0:a6f7796d0433bb59779a3e1b99fa3c20b3e49148d24eb69dfe17682414fa746a"
EXPECTED_CASE_IDS = [
    "transit-board-v3", "restaurant-menu-v3", "hospital-schedule-v3", "transit-board-v5",
]
MAX_ENCODED_BYTES = 30 * 1024 * 1024
ACCEPTED_ASSURANCE = "A1_PRODUCER_REPORT_CONSISTENCY_ONLY"
A2_DISPOSITION = "NOT_ESTABLISHED"
REJECTION_REASON_CODES = [
    "NORMALIZED_RASTER_INPUT_NOT_PROVEN",
    "PRODUCT_STATIC_ACQUISITION_NOT_PROVEN",
    "INDEPENDENT_LAYERED_METRICS_NOT_REPLAYED",
    "PROVIDER_ZERO_NOT_INDEPENDENTLY_GROUNDED",
    "PER_CASE_HALLUCINATION_NON_INCREASE",
    "PER_CASE_TARGET_IMPROVEMENT",
]
SOURCE_DIMENSIONS = {3: (1_024, 768), 5: (1_800, 1_200)}
EVIDENCE_FIELDS = frozenset({
    "contractVersion", "assignmentIdentity", "transformIdentity", "evaluationIdentity",
    "corpusIdentity", "annotationSetIdentity", "capabilityIdentity", "acquisitionPolicyIdentity",
    "runsCompleted", "caseCount", "devCases", "holdoutCases", "actualAcquisitions",
    "deterministicCases", "runs", "predicates", "aggregateStaticLineRecallBps",
    "aggregateInspectedLineRecallBps", "aggregateLineRecallGainBps",
    "aggregateStaticCharacterErrors", "aggregateInspectedCharacterErrors", "disposition",
    "qualified", "reasonCode", "externalProviderUsage",
})
RUN_FIELDS = frozenset({"runOrdinal", "cases"})
CASE_FIELDS = frozenset({
    "caseId", "caseIdentity", "partition", "sourceWidth", "sourceHeight",
    "staticPlanIdentity", "requestIdentity", "inspectedPlanIdentity", "staticViewCount",
    "inspectedViewCount", "staticDecodedPixels", "inspectedDecodedPixels",
    "staticEncodedBytes", "inspectedEncodedBytes", "staticAcquisitionMicros",
    "inspectedAcquisitionMicros", "staticResource", "inspectedResources", "staticView", "inspected",
})
METRIC_FIELDS = frozenset({
    "observationCount", "expectedLines", "matchedLines", "predictedCharacters",
    "characterErrors", "hallucinationCases", "expectedPrecedenceEdges",
    "comparablePrecedenceEdges", "correctPrecedenceEdges", "expectedRepeatMemberships",
    "observableRepeatMemberships",
})
VIEW_FIELDS = frozenset({"identity", "artifactId", "width", "height", "encodedBytes"})
PREDICATE_FIELDS = frozenset({
    "EXACT_FROZEN_ASSIGNMENT", "NORMALIZED_RASTER_ONLY", "TWO_RUN_DETERMINISM",
    "PER_CASE_TARGET_IMPROVEMENT", "AGGREGATE_LINE_RECALL_GAIN_0500_BPS",
    "AGGREGATE_CHARACTER_ERROR_REDUCTION", "PER_CASE_HALLUCINATION_NON_INCREASE",
    "EXTERNAL_PROVIDER_ZERO",
})
USAGE_FIELDS = frozenset({"attempts", "reservations", "costMicrosCny"})
ASSIGNMENT_FIELDS = frozenset({
    "assignmentVersion", "corpusVersion", "corpusIdentity", "staticViewKind",
    "staticLongEdge", "caseAssignments",
})
ASSIGNED_CASE_FIELDS = frozenset({"caseId", "partition", "regions"})
REGION_FIELDS = frozenset({"baseViewId", "boundingBox", "marginPreset", "resolutionPreset"})
IDENTITY_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9._+/-]{0,127}:[0-9a-f]{64}")


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("R5_PRODUCT_A2_DUPLICATE_MEMBER")
        result[key] = value
    return result


def strict_json(raw: bytes) -> Any:
    try:
        text = raw.decode("utf-8", errors="strict")
        value, end = json.JSONDecoder(
            object_pairs_hook=unique_object,
            parse_constant=lambda _value: fail("R5_PRODUCT_A2_JSON_INVALID"),
        ).raw_decode(text)
        if text[end:].strip():
            fail("R5_PRODUCT_A2_TRAILING_JSON")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError("R5_PRODUCT_A2_JSON_INVALID") from error


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def framed_hash(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        raw = value.encode("utf-8")
        digest.update(str(len(raw)).encode("ascii"))
        digest.update(b":")
        digest.update(raw)
        digest.update(b"\n")
    return digest.hexdigest()


def require_identity(value: Any) -> bool:
    return type(value) is str and IDENTITY_PATTERN.fullmatch(value) is not None


def validate_metrics(value: Any) -> None:
    if not exact_object(value, METRIC_FIELDS) or any(
        not strict_nonnegative_int(item) for item in value.values()
    ):
        fail("R5_PRODUCT_A2_METRICS_INVALID")
    if value["matchedLines"] > value["expectedLines"] \
            or value["correctPrecedenceEdges"] > value["comparablePrecedenceEdges"] \
            or value["comparablePrecedenceEdges"] > value["expectedPrecedenceEdges"] \
            or value["observableRepeatMemberships"] > value["expectedRepeatMemberships"]:
        fail("R5_PRODUCT_A2_METRICS_INVALID")


def validate_view(value: Any) -> None:
    if not exact_object(value, VIEW_FIELDS) or not require_identity(value["identity"]) \
            or type(value["artifactId"]) is not str \
            or re.fullmatch(r"[0-9a-f]{64}", value["artifactId"]) is None \
            or not strict_positive_int(value["width"], 2_400) \
            or not strict_positive_int(value["height"], 2_400) \
            or not strict_positive_int(value["encodedBytes"], MAX_ENCODED_BYTES):
        fail("R5_PRODUCT_A2_VIEW_INVALID")


def validate_evidence_shape(evidence: Any) -> None:
    if not exact_object(evidence, EVIDENCE_FIELDS) \
            or evidence["contractVersion"] != EVIDENCE_VERSION \
            or evidence["transformIdentity"] != TRANSFORM_VERSION:
        fail("R5_PRODUCT_A2_EVIDENCE_CONTRACT_INVALID")
    for field in (
        "assignmentIdentity", "evaluationIdentity", "corpusIdentity", "annotationSetIdentity",
        "acquisitionPolicyIdentity",
    ):
        if not require_identity(evidence[field]):
            fail("R5_PRODUCT_A2_EVIDENCE_CONTRACT_INVALID")
    if type(evidence["capabilityIdentity"]) is not str or not evidence["capabilityIdentity"].strip() \
            or type(evidence["qualified"]) is not bool \
            or evidence["disposition"] not in {"QUALIFIED", "NOT_QUALIFIED"} \
            or type(evidence["reasonCode"]) is not str \
            or re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", evidence["reasonCode"]) is None:
        fail("R5_PRODUCT_A2_EVIDENCE_CONTRACT_INVALID")
    for field in (
        "runsCompleted", "caseCount", "devCases", "holdoutCases", "actualAcquisitions",
        "deterministicCases", "aggregateStaticLineRecallBps", "aggregateInspectedLineRecallBps",
    ):
        if not strict_nonnegative_int(evidence[field], JAVA_INT_MAX):
            fail("R5_PRODUCT_A2_EVIDENCE_CONTRACT_INVALID")
    if type(evidence["aggregateLineRecallGainBps"]) is not int \
            or not -10_000 <= evidence["aggregateLineRecallGainBps"] <= 10_000 \
            or not strict_nonnegative_int(evidence["aggregateStaticCharacterErrors"]) \
            or not strict_nonnegative_int(evidence["aggregateInspectedCharacterErrors"]):
        fail("R5_PRODUCT_A2_EVIDENCE_CONTRACT_INVALID")
    predicates = evidence["predicates"]
    if not exact_object(predicates, PREDICATE_FIELDS) \
            or any(type(value) is not str or value not in {"PASS", "FAIL"}
                   for value in predicates.values()):
        fail("R5_PRODUCT_A2_EVIDENCE_CONTRACT_INVALID")
    usage = evidence["externalProviderUsage"]
    if not exact_object(usage, USAGE_FIELDS) \
            or any(not strict_nonnegative_int(value) for value in usage.values()):
        fail("R5_PRODUCT_A2_EVIDENCE_CONTRACT_INVALID")
    runs = evidence["runs"]
    if type(runs) is not list or len(runs) != 2:
        fail("R5_PRODUCT_A2_EVIDENCE_CONTRACT_INVALID")
    for run in runs:
        if not exact_object(run, RUN_FIELDS) or not strict_positive_int(run["runOrdinal"], 2) \
                or type(run["cases"]) is not list or len(run["cases"]) != 4:
            fail("R5_PRODUCT_A2_EVIDENCE_CONTRACT_INVALID")
        for case in run["cases"]:
            validate_case_shape(case)


def validate_case_shape(case: Any) -> None:
    if not exact_object(case, CASE_FIELDS) \
            or type(case["caseId"]) is not str \
            or re.fullmatch(r"[a-z][a-z0-9-]{0,127}", case["caseId"]) is None \
            or not require_identity(case["caseIdentity"]) \
            or case["partition"] not in {"DEV", "HOLDOUT"} \
            or any(not require_identity(case[field]) for field in (
                "staticPlanIdentity", "requestIdentity", "inspectedPlanIdentity",
            )):
        fail("R5_PRODUCT_A2_CASE_INVALID")
    for field in ("sourceWidth", "sourceHeight", "staticViewCount", "inspectedViewCount"):
        if not strict_positive_int(case[field], JAVA_INT_MAX):
            fail("R5_PRODUCT_A2_CASE_INVALID")
    for field in (
        "staticDecodedPixels", "inspectedDecodedPixels", "staticEncodedBytes", "inspectedEncodedBytes",
    ):
        if not strict_positive_int(case[field], JAVA_LONG_MAX):
            fail("R5_PRODUCT_A2_CASE_INVALID")
    for field in ("staticAcquisitionMicros", "inspectedAcquisitionMicros"):
        if not strict_nonnegative_int(case[field], JAVA_LONG_MAX):
            fail("R5_PRODUCT_A2_CASE_INVALID")
    validate_view(case["staticResource"])
    resources = case["inspectedResources"]
    if type(resources) is not list or len(resources) != 2:
        fail("R5_PRODUCT_A2_CASE_INVALID")
    for resource in resources:
        validate_view(resource)
    if case["staticEncodedBytes"] + case["inspectedEncodedBytes"] > MAX_ENCODED_BYTES:
        fail("R5_PRODUCT_A2_RESOURCE_LIMIT_EXCEEDED")
    validate_metrics(case["staticView"])
    validate_metrics(case["inspected"])


def load_assignment(repository: pathlib.Path, corpus_identity: str) -> tuple[dict[str, Any], str]:
    path = repository / "renderweave-inference/src/main/resources/visual-eval/r5/product-transform-assignment-v1.json"
    raw = path.read_bytes()
    document = strict_json(raw)
    if not exact_object(document, ASSIGNMENT_FIELDS) \
            or document["assignmentVersion"] != ASSIGNMENT_VERSION \
            or document["corpusVersion"] != "renderweave-visual-stage-corpus/2.0" \
            or document["corpusIdentity"] != corpus_identity \
            or document["staticViewKind"] != "OVERVIEW" \
            or not same_json_value(document["staticLongEdge"], 768) \
            or type(document["caseAssignments"]) is not list \
            or len(document["caseAssignments"]) != 4:
        fail("R5_PRODUCT_A2_ASSIGNMENT_INVALID")
    if [item.get("caseId") for item in document["caseAssignments"]] != EXPECTED_CASE_IDS:
        fail("R5_PRODUCT_A2_ASSIGNMENT_INVALID")
    for index, item in enumerate(document["caseAssignments"]):
        if not exact_object(item, ASSIGNED_CASE_FIELDS) \
                or item["partition"] != ("DEV" if index < 3 else "HOLDOUT") \
                or type(item["regions"]) is not list or len(item["regions"]) != 2:
            fail("R5_PRODUCT_A2_ASSIGNMENT_INVALID")
        seen: set[tuple[Any, ...]] = set()
        for region in item["regions"]:
            if not exact_object(region, REGION_FIELDS) \
                    or region["baseViewId"] != "view-00-overview-00" \
                    or region["marginPreset"] != "TIGHT_0000_BPS" \
                    or region["resolutionPreset"] != "INSPECT_LONG_EDGE_2400" \
                    or type(region["boundingBox"]) is not list or len(region["boundingBox"]) != 4 \
                    or any(type(value) is not int for value in region["boundingBox"]):
                fail("R5_PRODUCT_A2_ASSIGNMENT_INVALID")
            left, top, right, bottom = region["boundingBox"]
            if left < 0 or top < 0 or right > 10_000 or bottom > 10_000 \
                    or left >= right or top >= bottom:
                fail("R5_PRODUCT_A2_ASSIGNMENT_INVALID")
            key = (region["baseViewId"], left, top, right, bottom,
                   region["marginPreset"], region["resolutionPreset"])
            if key in seen:
                fail("R5_PRODUCT_A2_ASSIGNMENT_INVALID")
            seen.add(key)
    return document, f"{ASSIGNMENT_VERSION}:{hashlib.sha256(raw).hexdigest()}"


def source_dimensions(case_id: str) -> tuple[int, int]:
    match = re.fullmatch(r"[a-z][a-z0-9-]{0,124}-v([35])", case_id)
    if match is None:
        fail("R5_PRODUCT_A2_SOURCE_DIMENSIONS_INVALID")
    return SOURCE_DIMENSIONS[int(match.group(1))]


def floor_project(value: int, size: int) -> int:
    return value * size // 10_000


def ceil_project(value: int, size: int) -> int:
    return (value * size + 9_999) // 10_000


def derive_view(source_width: int, source_height: int, region: dict[str, Any]) -> dict[str, Any]:
    left, top, right, bottom = region["boundingBox"]
    pixel = (
        floor_project(left, source_width), floor_project(top, source_height),
        ceil_project(right, source_width), ceil_project(bottom, source_height),
    )
    crop_width = pixel[2] - pixel[0]
    crop_height = pixel[3] - pixel[1]
    if crop_width >= crop_height:
        width, height = 2_400, max(1, crop_height * 2_400 // crop_width)
    else:
        width, height = max(1, crop_width * 2_400 // crop_height), 2_400
    canonical = (
        pixel[0] * 10_000 // source_width,
        pixel[1] * 10_000 // source_height,
        (pixel[2] * 10_000 + source_width - 1) // source_width,
        (pixel[3] * 10_000 + source_height - 1) // source_height,
    )
    return {"pixel": pixel, "canonical": canonical, "width": width, "height": height}


def request_identity(assignment_identity: str, case: dict[str, Any]) -> str:
    values = [assignment_identity, case["caseId"]]
    for region in case["regions"]:
        box = ",".join(str(value) for value in region["boundingBox"])
        values.append("|".join((region["baseViewId"], box,
                                region["marginPreset"], region["resolutionPreset"])))
    return "renderweave-r5-inspection-request-fixture/1.0:" + framed_hash(values)


def static_descriptors(source_id: str, width: int, height: int) -> list[str]:
    scale = min(1.0, 768 / max(width, height))
    result = [f"view-00-overview-00|{source_id}|OVERVIEW|{max(1, int(width * scale))}x{max(1, int(height * scale))}"]
    columns = (width + 1_399) // 1_400
    rows = (height + 1_399) // 1_400
    if columns == 1 and rows == 1:
        return result
    ordinal = 0
    for row in range(rows):
        for column in range(columns):
            crop_width = (column + 1) * width // columns - column * width // columns
            crop_height = (row + 1) * height // rows - row * height // rows
            scale = min(1.0, 1_400 / max(crop_width, crop_height))
            result.append(
                f"view-00-tile-{ordinal:02d}|{source_id}|TILE|"
                f"{max(1, int(crop_width * scale))}x{max(1, int(crop_height * scale))}"
            )
            ordinal += 1
    return result[:10]


def verify_case(
        case: dict[str, Any],
        assigned: dict[str, Any],
        locked: dict[str, Any],
        assignment_identity: str,
) -> None:
    width, height = source_dimensions(case["caseId"])
    source_id = locked["renderIdentity"].removeprefix("render-sha256:")
    if case["caseIdentity"] != locked["caseIdentity"] \
            or case["partition"] != locked["partition"] \
            or not same_json_value(case["sourceWidth"], width) \
            or not same_json_value(case["sourceHeight"], height) \
            or not same_json_value(case["staticViewCount"], 1) \
            or not same_json_value(case["inspectedViewCount"], 2):
        fail("R5_PRODUCT_A2_CASE_AUTHORITY_DRIFT")

    static = case["staticResource"]
    overview_scale = min(1.0, 768 / max(width, height))
    overview_width = max(1, int(width * overview_scale))
    overview_height = max(1, int(height * overview_scale))
    expected_static_identity = "renderweave-r5-static-view/1.0:" + framed_hash([
        "view-00-overview-00", source_id, "OVERVIEW",
        f"{overview_width}x{overview_height}", static["artifactId"],
    ])
    expected_static_plan = "renderweave-r5-static-plan/1.0:" + framed_hash(
        static_descriptors(source_id, width, height)
    )
    if static["identity"] != expected_static_identity \
            or not same_json_value(static["width"], overview_width) \
            or not same_json_value(static["height"], overview_height) \
            or case["staticPlanIdentity"] != expected_static_plan:
        fail("R5_PRODUCT_A2_STATIC_VIEW_DRIFT")

    expected_request = request_identity(assignment_identity, assigned)
    if case["requestIdentity"] != expected_request:
        fail("R5_PRODUCT_A2_REQUEST_IDENTITY_DRIFT")
    expected_view_identities: list[str] = []
    expected_pixels = 0
    for resource, region in zip(case["inspectedResources"], assigned["regions"]):
        derived = derive_view(width, height, region)
        canonical = ",".join(str(value) for value in derived["canonical"])
        request_box = ",".join(str(value) for value in region["boundingBox"])
        expected_identity = "renderweave-r5-product-raster-view/1.0:" + framed_hash([
            TRANSFORM_VERSION,
            f"source={source_id}",
            "base-view=view-00-overview-00",
            "base-kind=OVERVIEW",
            f"request={request_box}",
            "margin-bps=0",
            "long-edge=2400",
            f"source-crop={canonical}",
            f"dimensions={derived['width']}x{derived['height']}",
            f"artifact={resource['artifactId']}",
            "codec=java-imageio-png/1.0",
            "interpolation=java2d-bicubic/1.0",
        ])
        if resource["identity"] != expected_identity \
                or not same_json_value(resource["width"], derived["width"]) \
                or not same_json_value(resource["height"], derived["height"]):
            fail("R5_PRODUCT_A2_INSPECTED_VIEW_DRIFT")
        expected_view_identities.append(expected_identity)
        expected_pixels += derived["width"] * derived["height"]
    expected_plan = "renderweave-r5-inspected-plan/1.0:" + framed_hash(
        [assignment_identity, expected_request, *expected_view_identities]
    )
    if case["inspectedPlanIdentity"] != expected_plan \
            or not same_json_value(case["staticDecodedPixels"], overview_width * overview_height) \
            or not same_json_value(case["inspectedDecodedPixels"], expected_pixels) \
            or not same_json_value(case["staticEncodedBytes"], static["encodedBytes"]) \
            or not same_json_value(case["inspectedEncodedBytes"], sum(
                resource["encodedBytes"] for resource in case["inspectedResources"]
            )) \
            or expected_pixels > 11_520_000:
        fail("R5_PRODUCT_A2_RESOURCE_DRIFT")
    if case["staticView"]["expectedLines"] != case["inspected"]["expectedLines"]:
        fail("R5_PRODUCT_A2_GOLD_DENOMINATOR_DRIFT")


def ratio(numerator: int, denominator: int) -> int:
    return 10_000 if denominator == 0 else numerator * 10_000 // denominator


def recompute_measurements(first: list[dict[str, Any]], second: list[dict[str, Any]]) -> dict[str, Any]:
    """Recompute only facts derivable from the producer report; this is not independent A2."""
    second_by_id = {item["caseId"]: item for item in second}
    ignored = {"staticAcquisitionMicros", "inspectedAcquisitionMicros"}
    deterministic_cases = sum(
        1 for item in first
        if item["caseId"] in second_by_id
        and all(same_json_value(item[key], second_by_id[item["caseId"]][key])
                for key in CASE_FIELDS - ignored)
    )
    deterministic = deterministic_cases == 4
    improved = all(
        item["inspected"]["matchedLines"] > item["staticView"]["matchedLines"]
        or item["inspected"]["characterErrors"] < item["staticView"]["characterErrors"]
        for item in first
    )
    hallucination_safe = all(
        item["inspected"]["hallucinationCases"] <= item["staticView"]["hallucinationCases"]
        for item in first
    )
    expected_lines = sum(item["staticView"]["expectedLines"] for item in first)
    static_matched = sum(item["staticView"]["matchedLines"] for item in first)
    inspected_matched = sum(item["inspected"]["matchedLines"] for item in first)
    static_recall = ratio(static_matched, expected_lines)
    inspected_recall = ratio(inspected_matched, expected_lines)
    recall_gain = inspected_recall - static_recall
    static_errors = sum(item["staticView"]["characterErrors"] for item in first)
    inspected_errors = sum(item["inspected"]["characterErrors"] for item in first)
    reported_qualified = deterministic and improved and hallucination_safe \
        and recall_gain >= 500 and inspected_errors < static_errors
    return {
        "deterministicCases": deterministic_cases,
        "twoRunDeterminism": deterministic,
        "perCaseTargetImprovement": improved,
        "perCaseHallucinationNonIncrease": hallucination_safe,
        "aggregateStaticLineRecallBps": static_recall,
        "aggregateInspectedLineRecallBps": inspected_recall,
        "aggregateLineRecallGainBps": recall_gain,
        "aggregateStaticCharacterErrors": static_errors,
        "aggregateInspectedCharacterErrors": inspected_errors,
        "reportedQualified": reported_qualified,
    }


def repository_revision(repository: pathlib.Path) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"],
        check=True, capture_output=True, text=True, encoding="utf-8",
    )
    revision = completed.stdout.strip()
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        fail("R5_PRODUCT_A2_REPOSITORY_REVISION_INVALID")
    return revision


def verify(evidence_path: pathlib.Path, repository: pathlib.Path) -> dict[str, Any]:
    raw = evidence_path.read_bytes()
    if not raw or len(raw) > 1024 * 1024 or not raw_payload_safe(raw):
        fail("R5_PRODUCT_A2_EVIDENCE_BYTES_INVALID")
    envelope = strict_json(raw)
    if not exact_object(envelope, frozenset({"envelopeVersion", "evidenceIdentity", "evidence"})) \
            or envelope["envelopeVersion"] != ENVELOPE_VERSION \
            or not payload_safe(envelope):
        fail("R5_PRODUCT_A2_ENVELOPE_INVALID")
    evidence = envelope["evidence"]
    validate_evidence_shape(evidence)
    evidence_identity = f"{IDENTITY_VERSION}:{hashlib.sha256(canonical_json(evidence)).hexdigest()}"
    if not same_json_value(envelope["evidenceIdentity"], evidence_identity):
        fail("R5_PRODUCT_A2_EVIDENCE_IDENTITY_DRIFT")

    protocol = quality_resources.load_protocol(repository)
    assignment, assignment_identity = load_assignment(repository, protocol.document["corpusIdentity"])
    expected_evaluation = f"{EVALUATION_VERSION}:{framed_hash([
        RUNNER_VERSION, protocol.document['corpusIdentity'], EXPECTED_ANNOTATION, assignment_identity,
        TRANSFORM_VERSION, EXPECTED_POLICY, EXPECTED_CAPABILITY,
        'two-isolated-static-and-inspected-runs/1.0',
        'provider-attempts-reservations-cost-api-key-reads-zero/1.0',
    ])}"
    expected_identities = {
        "assignmentIdentity": assignment_identity,
        "evaluationIdentity": expected_evaluation,
        "corpusIdentity": protocol.document["corpusIdentity"],
        "annotationSetIdentity": EXPECTED_ANNOTATION,
        "capabilityIdentity": EXPECTED_CAPABILITY,
        "acquisitionPolicyIdentity": EXPECTED_POLICY,
    }
    if any(not same_json_value(evidence[field], expected)
           for field, expected in expected_identities.items()):
        fail("R5_PRODUCT_A2_AUTHORITY_DRIFT")
    expected_accounting = {
        "runsCompleted": 2, "caseCount": 4, "devCases": 3, "holdoutCases": 1,
        "actualAcquisitions": 16,
    }
    if any(not same_json_value(evidence[field], expected)
           for field, expected in expected_accounting.items()):
        fail("R5_PRODUCT_A2_ACCOUNTING_DRIFT")

    assigned_by_id = {item["caseId"]: item for item in assignment["caseAssignments"]}
    for run_index, run in enumerate(evidence["runs"], start=1):
        if not same_json_value(run["runOrdinal"], run_index) \
                or [item["caseId"] for item in run["cases"]] != EXPECTED_CASE_IDS:
            fail("R5_PRODUCT_A2_RUN_DRIFT")
        for case in run["cases"]:
            verify_case(case, assigned_by_id[case["caseId"]], protocol.cases_by_id[case["caseId"]],
                        assignment_identity)

    first = evidence["runs"][0]["cases"]
    measurements = recompute_measurements(first, evidence["runs"][1]["cases"])
    deterministic_cases = measurements["deterministicCases"]
    reported_qualified = measurements["reportedQualified"]
    predicates = {
        "EXACT_FROZEN_ASSIGNMENT": "PASS",
        # These two values reproduce the producer report for forensic consistency only. The audit
        # result below explicitly rejects them as independently ungrounded.
        "NORMALIZED_RASTER_ONLY": "PASS",
        "TWO_RUN_DETERMINISM": "PASS" if measurements["twoRunDeterminism"] else "FAIL",
        "PER_CASE_TARGET_IMPROVEMENT": "PASS" if measurements["perCaseTargetImprovement"] else "FAIL",
        "AGGREGATE_LINE_RECALL_GAIN_0500_BPS": "PASS"
            if measurements["aggregateLineRecallGainBps"] >= 500 else "FAIL",
        "AGGREGATE_CHARACTER_ERROR_REDUCTION": "PASS"
            if measurements["aggregateInspectedCharacterErrors"]
            < measurements["aggregateStaticCharacterErrors"] else "FAIL",
        "PER_CASE_HALLUCINATION_NON_INCREASE": "PASS"
            if measurements["perCaseHallucinationNonIncrease"] else "FAIL",
        "EXTERNAL_PROVIDER_ZERO": "PASS",
    }
    expected_decision = {
        "deterministicCases": deterministic_cases,
        "predicates": predicates,
        "aggregateStaticLineRecallBps": measurements["aggregateStaticLineRecallBps"],
        "aggregateInspectedLineRecallBps": measurements["aggregateInspectedLineRecallBps"],
        "aggregateLineRecallGainBps": measurements["aggregateLineRecallGainBps"],
        "aggregateStaticCharacterErrors": measurements["aggregateStaticCharacterErrors"],
        "aggregateInspectedCharacterErrors": measurements["aggregateInspectedCharacterErrors"],
        "disposition": "QUALIFIED" if reported_qualified else "NOT_QUALIFIED",
        "qualified": reported_qualified,
        "reasonCode": "R5_PRODUCT_TRANSFORM_QUALIFIED" if reported_qualified
            else "R5_PRODUCT_TRANSFORM_NOT_QUALIFIED",
        "externalProviderUsage": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
    }
    if any(not same_json_value(evidence[field], expected)
           for field, expected in expected_decision.items()):
        fail("R5_PRODUCT_A2_DECISION_DRIFT")

    return {
        "verifierVersion": "renderweave-r5-product-transform-verifier/1.1",
        "result": "REJECTED",
        "reportConsistency": "PASS",
        "assurance": ACCEPTED_ASSURANCE,
        "a2Disposition": A2_DISPOSITION,
        "verificationRevision": repository_revision(repository),
        "evidenceIdentity": evidence_identity,
        "evaluationIdentity": expected_evaluation,
        "assignmentIdentity": assignment_identity,
        "transformIdentity": TRANSFORM_VERSION,
        "caseCount": 4,
        "devCases": 3,
        "holdoutCases": 1,
        "runs": 2,
        "actualAcquisitions": 16,
        "deterministicCases": deterministic_cases,
        "reportedDisposition": expected_decision["disposition"],
        "reportedQualified": reported_qualified,
        "disposition": "R5_PRODUCT_TRANSFORM_NOT_QUALIFIED",
        "freshJ1Disposition": "LIVE_J1_REQUEST_NOT_ELIGIBLE",
        "rejectionReasonCodes": REJECTION_REASON_CODES,
        "reportedProviderAttempts": evidence["externalProviderUsage"]["attempts"],
        "reportedProviderReservations": evidence["externalProviderUsage"]["reservations"],
        "reportedExternalProviderCostMicrosCny": evidence["externalProviderUsage"]["costMicrosCny"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=pathlib.Path)
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    summary = verify(args.evidence.resolve(), args.repository.resolve())
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(canonical_json(summary).decode("utf-8") + "\n")
    print("R5 product transform: REJECTED; A2=NOT_ESTABLISHED; route=CLOSED")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
