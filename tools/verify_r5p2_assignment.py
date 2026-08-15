#!/usr/bin/env python3
"""Independent payload-safe verifier for FrozenR5P2Assignment/1.0."""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
import struct
from typing import Any, Mapping


MANIFEST = "renderweave-inference/src/main/resources/visual-eval/r5p2/assignment-v1.json"
IDENTITY_LOCK = "renderweave-inference/src/main/resources/visual-eval/v2/identity-lock.json"
OLD_ASSIGNMENTS = (
    "renderweave-inference/src/main/resources/visual-eval/r5/product-transform-assignment-v1.json",
    "renderweave-inference/src/main/resources/visual-eval/r5p/paired-view-assignment-v1.json",
)
OLD_SHA256 = (
    "46c8e4c9c28b8628bac6532deeeb1a9ee311dda58b1a76f23a1b1d70abe7b540",
    "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8",
)
CONTRACT_VERSION = "FrozenR5P2Assignment/1.0"
IDENTITY_VERSION = "renderweave-r5p2-frozen-assignment/1.0"
EVALUATION_VERSION = "renderweave-r5p2-paired-product-view-evaluation/1.0"
SPEC_IDENTITY = "spec-sha256:e33269e1faa04f21239a0e79d4346fc90439f142b26111b3764164f53ba7d902"
AUTHORITY_IDENTITY = (
    "renderweave-r5p2-authority/1.0:"
    "274585e94941248dd2bea55026c06428f2945aea7cc48ce2b269c21f5f3ccc07"
)
BASELINE_REVISION = "4b756c52cbc2fd389d8ca34f4c4a65b1bc9615db"
CORPUS_VERSION = "renderweave-visual-stage-corpus/2.0"
CORPUS_IDENTITY = (
    f"{CORPUS_VERSION}:"
    "c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c"
)
CORPUS_LOCK_SHA256 = "cf54fd985e89a024fdc0742a737c21442c49718fdf58b0bb05b87e2cffd2247d"
SELECTION_POLICY = "renderweave-r5p2-confirmation-selection/1.0"
SELECTION_FIELDS = ["caseId", "partition", "difficulty", "failureSlices", "caseIdentity"]
NORMALIZATION_PROFILE_ID = "r5p2-offline-evaluation"
DIAGNOSTIC = [
    "transit-board-v3", "restaurant-menu-v3", "hospital-schedule-v3",
    "transit-board-v5", "transit-board-v2", "invoice-lines-v3",
    "school-timetable-v4", "building-directory-v5",
]
CONFIRMATION = [
    "weather-forecast-v3", "warehouse-inventory-v2",
    "event-agenda-v4", "product-catalog-v5",
]
EXPECTED_RANKS = [
    "19f8156bddc9fd7a08e8324e6e3e165060207060fe49c972e51613dabcd1068d",
    "25fc1c7bc6c9f070c90893c9839c5c9859db0e5fd9492b0bcb4ad9be02251535",
    "2552e253b354d65e1e8c5d570f696104c9bf62715e6db11cf2e4453bad15417e",
    "5d7decfb23a7b8090ddc032ead18c40c1d6c7fd2852557c56447cfa58351c3bc",
]
ALLOWED_FAMILIES = {
    "analytics-dashboard", "event-agenda", "product-catalog",
    "warehouse-inventory", "weather-forecast",
}
STRATA = (
    ("DEV", "DENSE_TEXT"),
    ("DEV", "MULTI_COLUMN"),
    ("DEV", "LOW_CONTRAST"),
    ("HOLDOUT", "NOISY"),
)
FORBIDDEN_SELECTION_FIELDS = {
    "ocr", "ocrText", "pairedMetric", "pairedMetrics", "gold", "goldText",
    "candidate", "candidateOutput", "model", "modelResult", "modelOutput",
}
SOURCE_HASHES = {
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/input/InputNormalizer.java":
        "71a4f90ee7298fb3ef3a3550e34880ed3216213fc69f31b80f9b0e496570654a",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/MultiScaleVisualViewPlanner.java":
        "e10d2955c9b463ee3996eac333abf4c8f32c2a82faf38e286796e56b7d52fa0c",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/BoundedVisualInspection.java":
        "99df52f72e1b5ff06c064bf96281149cf56e631f6f285bcbb882eb1e09723e4f",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/LocalProcessDocumentVisionPreprocessor.java":
        "401565b45944ee85929c38415e5d4255f5b5559e80b9d67ee9f83a3419af27d0",
    "tools/r5p2_public_process.py":
        "465c92e971b14fefe04a9cecff214b8c9e8dd1b25a7b87db8252b6ea7c759c32",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/eval/visual/quality/"
    "R5P2SourceLineReconciliation.java":
        "987c9d77aca3350545718c4e055dc142514716d05826d3fb6d75ac316c86eba1",
}


class AssignmentError(ValueError):
    pass


def fail(code: str) -> None:
    raise AssignmentError(code)


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("R5P2_JSON_DUPLICATE_KEY")
        result[key] = value
    return result


def _reject_float(value: str) -> Any:
    fail("R5P2_JSON_NUMBER_INVALID")


def strict_json(raw: bytes) -> Any:
    try:
        text = raw.decode("utf-8")
        return json.loads(
            text,
            object_pairs_hook=_pairs,
            parse_float=_reject_float,
            parse_constant=_reject_float,
        )
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("R5P2_JSON_INVALID")


def _exact_keys(value: Any, expected: set[str], code: str) -> dict[str, Any]:
    if type(value) is not dict or set(value) != expected:
        fail(code)
    return value


def _text(value: Any, code: str) -> str:
    if type(value) is not str or not value:
        fail(code)
    return value


def _integer(value: Any, code: str) -> int:
    if type(value) is not int:
        fail(code)
    return value


def _read(
        repository: pathlib.Path, relative: str,
        overrides: Mapping[str, bytes] | None = None) -> bytes:
    if overrides and relative in overrides:
        return bytes(overrides[relative])
    path = (repository / relative).resolve()
    root = repository.resolve()
    if root not in path.parents or not path.is_file():
        fail("R5P2_ASSIGNMENT_RESOURCE_MISSING")
    return path.read_bytes()


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _family(case_id: str) -> str:
    family = re.sub(r"-v[0-9]+$", "", case_id)
    if family == case_id or not re.fullmatch(r"[a-z][a-z0-9-]{0,127}", family):
        fail("R5P2_SELECTION_FAMILY_INVALID")
    return family


def _selection_rank(partition: str, difficulty: str, case_identity: str) -> str:
    value = f"{SELECTION_POLICY}|{partition}|{difficulty}|{case_identity}"
    return _sha256(value.encode("utf-8"))


def recompute_selection(identity_lock: bytes) -> list[dict[str, Any]]:
    document = strict_json(identity_lock)
    if (type(document) is not dict
            or document.get("corpusVersion") != CORPUS_VERSION
            or document.get("corpusIdentity") != CORPUS_IDENTITY
            or type(document.get("cases")) is not list):
        fail("R5P2_SELECTION_METADATA_INVALID")
    metadata: list[dict[str, Any]] = []
    seen: set[str] = set()
    for value in document["cases"]:
        if type(value) is not dict:
            fail("R5P2_SELECTION_METADATA_INVALID")
        if FORBIDDEN_SELECTION_FIELDS.intersection(value):
            fail("R5P2_SELECTION_FORBIDDEN_METADATA")
        case_id = _text(value.get("caseId"), "R5P2_SELECTION_METADATA_INVALID")
        partition = _text(value.get("partition"), "R5P2_SELECTION_METADATA_INVALID")
        difficulty = _text(value.get("difficulty"), "R5P2_SELECTION_METADATA_INVALID")
        identity = _text(value.get("caseIdentity"), "R5P2_SELECTION_METADATA_INVALID")
        slices = value.get("failureSlices")
        if (case_id in seen or type(slices) is not list
                or any(type(item) is not str for item in slices)
                or len(set(slices)) != len(slices)
                or partition not in ("DEV", "HOLDOUT")
                or difficulty not in (
                    "BASELINE", "MULTI_COLUMN", "DENSE_TEXT", "LOW_CONTRAST", "NOISY")
                or not re.fullmatch(r"renderweave-layered-case/2\.0:[0-9a-f]{64}", identity)):
            fail("R5P2_SELECTION_METADATA_INVALID")
        seen.add(case_id)
        metadata.append({
            "caseId": case_id,
            "partition": partition,
            "difficulty": difficulty,
            "failureSlices": slices,
            "caseIdentity": identity,
            "family": _family(case_id),
        })
    selected: list[dict[str, Any]] = []
    used_families: set[str] = set()
    for partition, difficulty in STRATA:
        ranked = []
        for item in metadata:
            if (item["partition"] == partition and item["difficulty"] == difficulty
                    and item["family"] in ALLOWED_FAMILIES
                    and "REPEATED_LIST" in item["failureSlices"]):
                ranked.append({
                    **item,
                    "rankSha256": _selection_rank(
                        partition, difficulty, item["caseIdentity"]),
                })
        ranked.sort(key=lambda item: (item["rankSha256"], item["caseId"]))
        chosen = next((item for item in ranked if item["family"] not in used_families), None)
        if chosen is None:
            fail("R5P2_SELECTION_STRATUM_EMPTY")
        used_families.add(chosen["family"])
        selected.append(chosen)
    return selected


def _case_ids(raw: bytes) -> set[str]:
    document = strict_json(raw)
    values = document.get("caseAssignments") if type(document) is dict else None
    if type(values) is not list:
        fail("R5P2_ASSIGNMENT_PRIOR_PAIRED_DRIFT")
    result: set[str] = set()
    for value in values:
        if type(value) is not dict or type(value.get("caseId")) is not str:
            fail("R5P2_ASSIGNMENT_PRIOR_PAIRED_DRIFT")
        if value["caseId"] in result:
            fail("R5P2_ASSIGNMENT_PRIOR_PAIRED_DRIFT")
        result.add(value["caseId"])
    return result


def _normalization_fingerprint(source_reference: str, raw_fixture: bytes) -> str:
    digest = hashlib.sha256()
    for value in (
            b"image-only", NORMALIZATION_PROFILE_ID.encode(), source_reference.encode(),
            b"image/png", raw_fixture):
        digest.update(struct.pack(">I", len(value)))
        digest.update(value)
    return digest.hexdigest()


def _png_dimensions(value: bytes) -> tuple[int, int]:
    if (len(value) < 24 or value[:8] != b"\x89PNG\r\n\x1a\n"
            or value[12:16] != b"IHDR"):
        fail("R5P2_ASSIGNMENT_FIXTURE_DRIFT")
    width, height = struct.unpack(">II", value[16:24])
    if width < 1 or height < 1:
        fail("R5P2_ASSIGNMENT_FIXTURE_DRIFT")
    return width, height


def _framed_sha256(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def _validate_sources(repository: pathlib.Path) -> None:
    for relative, expected in SOURCE_HASHES.items():
        raw = _read(repository, relative)
        canonical = raw.replace(b"\r\n", b"\n")
        if _sha256(canonical) != expected:
            fail("R5P2_ASSIGNMENT_SOURCE_IDENTITY_DRIFT")


def verify(
        repository: pathlib.Path,
        overrides: Mapping[str, bytes] | None = None) -> dict[str, Any]:
    repository = pathlib.Path(repository)
    manifest_raw = _read(repository, MANIFEST, overrides)
    manifest = _exact_keys(strict_json(manifest_raw), {
        "contractVersion", "identityVersion", "evaluationVersion", "approvedSpecIdentity",
        "authorityIdentity", "baselineRevision", "corpusVersion", "corpusIdentity",
        "corpusIdentityLockResource", "corpusIdentityLockSha256", "selectionPolicyIdentity",
        "selectionAllowedFields", "priorPairedAssignmentResources",
        "priorPairedAssignmentSha256", "historicalUsagePolicy",
        "confirmationUsagePolicy", "normalizationProfileId", "caseAssignments",
        "thresholds", "identities", "runtimeComponents", "accessState",
        "externalProviderUsage", "apiKeyReads", "terminalCode",
    }, "R5P2_ASSIGNMENT_INVALID")
    expected_header = {
        "contractVersion": CONTRACT_VERSION,
        "identityVersion": IDENTITY_VERSION,
        "evaluationVersion": EVALUATION_VERSION,
        "approvedSpecIdentity": SPEC_IDENTITY,
        "authorityIdentity": AUTHORITY_IDENTITY,
        "baselineRevision": BASELINE_REVISION,
        "corpusVersion": CORPUS_VERSION,
        "corpusIdentity": CORPUS_IDENTITY,
        "corpusIdentityLockResource": "visual-eval/v2/identity-lock.json",
        "corpusIdentityLockSha256": CORPUS_LOCK_SHA256,
        "selectionPolicyIdentity": SELECTION_POLICY,
        "selectionAllowedFields": SELECTION_FIELDS,
        "priorPairedAssignmentResources": [
            value.removeprefix("renderweave-inference/src/main/resources/")
            for value in OLD_ASSIGNMENTS
        ],
        "priorPairedAssignmentSha256": list(OLD_SHA256),
        "historicalUsagePolicy":
            "diagnostic-veto-only-no-confirmation-no-holdout-claim/1.0",
        "confirmationUsagePolicy":
            "sealed-fresh-confirmation-no-holdout-acceptance-claim/1.0",
        "normalizationProfileId": NORMALIZATION_PROFILE_ID,
    }
    if any(manifest.get(key) != value for key, value in expected_header.items()):
        fail("R5P2_ASSIGNMENT_AUTHORITY_DRIFT")

    identity_lock = _read(repository, IDENTITY_LOCK, overrides)
    if _sha256(identity_lock) != CORPUS_LOCK_SHA256:
        fail("R5P2_ASSIGNMENT_CORPUS_LOCK_DRIFT")
    selected = recompute_selection(identity_lock)
    if ([item["caseId"] for item in selected] != CONFIRMATION
            or [item["rankSha256"] for item in selected] != EXPECTED_RANKS):
        fail("R5P2_ASSIGNMENT_SELECTION_DRIFT")

    selected_ids = set(CONFIRMATION)
    selected_families = {_family(value) for value in CONFIRMATION}
    for relative, expected_sha in zip(OLD_ASSIGNMENTS, OLD_SHA256, strict=True):
        raw = _read(repository, relative, overrides)
        prior_ids = _case_ids(raw)
        if (selected_ids.intersection(prior_ids)
                or selected_families.intersection(_family(value) for value in prior_ids)):
            fail("R5P2_ASSIGNMENT_PRIOR_PAIRED_OVERLAP")
        if _sha256(raw) != expected_sha:
            fail("R5P2_ASSIGNMENT_PRIOR_PAIRED_DRIFT")

    identity_document = strict_json(identity_lock)
    metadata_by_id = {item["caseId"]: item for item in identity_document["cases"]}
    selection_by_id = {item["caseId"]: item for item in selected}
    cases = manifest["caseAssignments"]
    if type(cases) is not list or [item.get("caseId") for item in cases] != DIAGNOSTIC + CONFIRMATION:
        fail("R5P2_ASSIGNMENT_CASE_SET_DRIFT")
    raw_hashes: set[str] = set()
    case_identities: set[str] = set()
    fixture_frames = ["contract=renderweave-r5p2-repository-raster-fixture-set/1.0"]
    fresh_count = 0
    for index, value in enumerate(cases):
        case = _exact_keys(value, {
            "caseId", "cohort", "partition", "difficulty", "failureSlices", "family",
            "caseIdentity", "selectionRankSha256", "renderIdentity", "rawFixtureResource",
            "rawFixtureSha256", "fixtureOrigin", "width", "height",
            "normalizationSourceReference", "normalizationFingerprint", "regions",
        }, "R5P2_ASSIGNMENT_INVALID")
        case_id = case["caseId"]
        metadata = metadata_by_id.get(case_id)
        if (type(metadata) is not dict
                or case["partition"] != metadata.get("partition")
                or case["difficulty"] != metadata.get("difficulty")
                or case["failureSlices"] != metadata.get("failureSlices")
                or case["family"] != _family(case_id)
                or case["caseIdentity"] != metadata.get("caseIdentity")):
            fail("R5P2_ASSIGNMENT_METADATA_DRIFT")
        diagnostic = index < len(DIAGNOSTIC)
        expected_resource = (
            f"visual-eval/r5p/raw/{case_id}.png" if diagnostic
            else f"visual-eval/r5p2/raw/{case_id}.png"
        )
        if diagnostic:
            if (case["cohort"] != "HISTORICAL_DIAGNOSTIC"
                    or case["selectionRankSha256"] is not None
                    or case["fixtureOrigin"] != "R5P_FROZEN_REUSE"):
                fail("R5P2_ASSIGNMENT_DIAGNOSTIC_DRIFT")
        else:
            selected_value = selection_by_id.get(case_id)
            if (case["cohort"] != "SEALED_CONFIRMATION"
                    or selected_value is None
                    or case["selectionRankSha256"] != selected_value["rankSha256"]
                    or case["fixtureOrigin"] != "R5P2_FRESH_ONE_SHOT"):
                fail("R5P2_ASSIGNMENT_SELECTION_DRIFT")
            fresh_count += 1
        if case["rawFixtureResource"] != expected_resource:
            fail("R5P2_ASSIGNMENT_FIXTURE_DRIFT")
        fixture_relative = (
            "renderweave-inference/src/main/resources/" + case["rawFixtureResource"])
        raw = _read(repository, fixture_relative, overrides)
        raw_sha = _sha256(raw)
        width, height = _png_dimensions(raw)
        source_reference = f"r5p2-raw-fixture:{case_id}:{raw_sha}"
        if (case["rawFixtureSha256"] != raw_sha
                or case["renderIdentity"] != f"render-sha256:{raw_sha}"
                or case["width"] != width or case["height"] != height
                or case["normalizationSourceReference"] != source_reference
                or case["normalizationFingerprint"]
                != _normalization_fingerprint(source_reference, raw)):
            fail("R5P2_ASSIGNMENT_FIXTURE_DRIFT")
        split = 2900 if case_id.startswith("transit-board-") else 2500
        expected_regions = [
            {"baseViewId": "view-00-overview-00", "boundingBox": [200, 200, 9800, split],
             "marginPreset": "TIGHT_0000_BPS", "resolutionPreset": "INSPECT_LONG_EDGE_2400"},
            {"baseViewId": "view-00-overview-00", "boundingBox": [200, split, 9800, 9800],
             "marginPreset": "TIGHT_0000_BPS", "resolutionPreset": "INSPECT_LONG_EDGE_2400"},
        ]
        if case["regions"] != expected_regions:
            fail("R5P2_ASSIGNMENT_REGION_DRIFT")
        if raw_sha in raw_hashes or case["caseIdentity"] in case_identities:
            fail("R5P2_ASSIGNMENT_COHORT_OVERLAP")
        raw_hashes.add(raw_sha)
        case_identities.add(case["caseIdentity"])
        fixture_frames.append(
            f"fixture={case_id}:{raw_sha}:{width}x{height}:"
            f"{case['normalizationFingerprint']}")
    if fresh_count != 4 or len({_family(value) for value in CONFIRMATION}) != 4:
        fail("R5P2_ASSIGNMENT_COHORT_OVERLAP")

    thresholds = _exact_keys(manifest["thresholds"], {
        "perCaseTargetImprovementRule", "maximumPerCaseHallucinationIncrease",
        "minimumConfirmationLineRecallGainBps", "minimumConfirmationCharacterErrorReduction",
        "maximumConfirmationOrderRegressionBps", "maximumConfirmationRepeatRegressionBps",
        "areaOverlapBps", "verticalOverlapBps", "centerRule",
    }, "R5P2_ASSIGNMENT_THRESHOLD_DRIFT")
    if thresholds != {
        "perCaseTargetImprovementRule": "MATCHED_LINE_GAIN_OR_CHARACTER_ERROR_REDUCTION",
        "maximumPerCaseHallucinationIncrease": 0,
        "minimumConfirmationLineRecallGainBps": 500,
        "minimumConfirmationCharacterErrorReduction": 1,
        "maximumConfirmationOrderRegressionBps": 100,
        "maximumConfirmationRepeatRegressionBps": 100,
        "areaOverlapBps": 5000,
        "verticalOverlapBps": 8000,
        "centerRule": "smaller-center-in-larger-closed-open/1.0",
    }:
        fail("R5P2_ASSIGNMENT_THRESHOLD_DRIFT")

    identities = manifest["identities"]
    if (type(identities) is not dict
            or identities.get("branchProcessContractIdentity")
            != "renderweave-r5p2-complete-branch-process/1.0"
            or identities.get("capabilityIdentity")
            != "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"
            or identities.get("reconciliationPolicyIdentity")
            != "FrozenSourceLineReconciliationPolicy/1.0:"
               "eead9287d942693156500a090daf5da5c2f9dafe4f6564ee642ac406c0f49443"
            or identities.get("runProtocolIdentity")
            != "two-isolated-complete-paired-runs-48-processes/1.0"):
        fail("R5P2_ASSIGNMENT_IDENTITY_DRIFT")
    _validate_sources(repository)

    runtime = manifest["runtimeComponents"]
    runtime_frames = [
        f"os={runtime.get('os')}", f"arch={runtime.get('arch')}",
        f"java-vendor={runtime.get('javaVendor')}",
        f"java-runtime={runtime.get('javaRuntime')}",
        f"python={runtime.get('python')}", f"rapidocr={runtime.get('rapidocr')}",
        f"openvino={runtime.get('openvino')}",
        f"image-runtime={runtime.get('imageRuntime')}",
        f"adapter-sha256={runtime.get('adapterSha256')}",
        f"model-sha256={runtime.get('modelManifestSha256')}",
    ]
    if identities.get("runtimeIdentity") != (
            "renderweave-r5p2-runtime/1.0:" + _framed_sha256(runtime_frames)):
        fail("R5P2_ASSIGNMENT_RUNTIME_DRIFT")

    access = _exact_keys(manifest["accessState"], {
        "state", "freshRawFixtureGenerations", "historicalRawFixtureReuses",
        "preFreezeGoldReads", "preFreezeMetricReads", "officialProducerGoldMetricReads",
        "independentReplayGoldMetricReads", "exploratoryRuns", "postFreezeMutations",
    }, "R5P2_ASSIGNMENT_ACCESS_STATE_DRIFT")
    if access != {
        "state": "FROZEN_PRE_RESULT", "freshRawFixtureGenerations": 4,
        "historicalRawFixtureReuses": 8, "preFreezeGoldReads": 0,
        "preFreezeMetricReads": 0, "officialProducerGoldMetricReads": 0,
        "independentReplayGoldMetricReads": 0, "exploratoryRuns": 0,
        "postFreezeMutations": 0,
    }:
        fail("R5P2_ASSIGNMENT_ACCESS_STATE_DRIFT")
    usage = manifest["externalProviderUsage"]
    if usage != {"attempts": 0, "reservations": 0, "costMicrosCny": 0}:
        fail("R5P2_ASSIGNMENT_PROVIDER_USAGE_NONZERO")
    if manifest["apiKeyReads"] != 0:
        fail("R5P2_ASSIGNMENT_API_KEY_READ_NONZERO")
    if manifest["terminalCode"] != "R5P2_ASSIGNMENT_FROZEN":
        fail("R5P2_ASSIGNMENT_TERMINAL_DRIFT")

    assignment_identity = f"{IDENTITY_VERSION}:{_sha256(manifest_raw)}"
    fixture_set_identity = (
        "renderweave-r5p2-repository-raster-fixture-set/1.0:"
        + _framed_sha256(fixture_frames)
    )
    threshold_frames = [
        f"per-case={thresholds['perCaseTargetImprovementRule']}",
        f"hallucination-max={thresholds['maximumPerCaseHallucinationIncrease']}",
        f"confirmation-line-gain-bps={thresholds['minimumConfirmationLineRecallGainBps']}",
        "confirmation-character-reduction="
        f"{thresholds['minimumConfirmationCharacterErrorReduction']}",
        "confirmation-order-regression-bps="
        f"{thresholds['maximumConfirmationOrderRegressionBps']}",
        "confirmation-repeat-regression-bps="
        f"{thresholds['maximumConfirmationRepeatRegressionBps']}",
        f"area-overlap-bps={thresholds['areaOverlapBps']}",
        f"vertical-overlap-bps={thresholds['verticalOverlapBps']}",
        f"center-rule={thresholds['centerRule']}",
    ]
    threshold_identity = (
        "renderweave-r5p2-thresholds/1.0:" + _framed_sha256(threshold_frames)
    )
    evaluation_frames = [
        f"assignment={assignment_identity}",
        f"fixtures={fixture_set_identity}",
        f"authority={AUTHORITY_IDENTITY}",
        f"baseline={BASELINE_REVISION}",
        f"process={identities['branchProcessContractIdentity']}",
        f"reconciliation={identities['reconciliationPolicyIdentity']}",
        f"runtime={identities['runtimeIdentity']}",
        f"thresholds={threshold_identity}",
    ]
    for case in cases:
        evaluation_frames.append(
            f"case={case['cohort']}:{case['caseIdentity']}:{case['rawFixtureSha256']}")
    evaluation_identity = (
        f"{EVALUATION_VERSION}:" + _framed_sha256(evaluation_frames)
    )
    return {
        "assignmentIdentity": assignment_identity,
        "fixtureSetIdentity": fixture_set_identity,
        "thresholdIdentity": threshold_identity,
        "evaluationIdentity": evaluation_identity,
        "historicalDiagnosticCount": len(DIAGNOSTIC),
        "sealedConfirmationCount": len(CONFIRMATION),
        "confirmationCaseIds": CONFIRMATION,
        "fixtureCount": len(cases),
        "freshFixtureCount": fresh_count,
        "preFreezeGoldReads": access["preFreezeGoldReads"],
        "preFreezeMetricReads": access["preFreezeMetricReads"],
        "providerAttempts": usage["attempts"],
        "apiKeyReads": manifest["apiKeyReads"],
        "terminalCode": manifest["terminalCode"],
    }


if __name__ == "__main__":
    print(json.dumps(verify(pathlib.Path(__file__).resolve().parents[1]), sort_keys=True))
