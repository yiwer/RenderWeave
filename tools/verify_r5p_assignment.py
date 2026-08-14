#!/usr/bin/env python3
"""Independently audit the frozen R5P paired product-view assignment."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import struct
from typing import Any


ASSIGNMENT_VERSION = "renderweave-r5p-paired-view-assignment/1.0"
ASSIGNMENT_SHA256 = "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8"
EVALUATION_VERSION = "renderweave-r5p-paired-view-evaluation/1.0"
AUTHORITY_IDENTITY = (
    "renderweave-r5p-authority/1.0:"
    "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d"
)
BASELINE_REVISION = "57be4d9b249c0aa06a1c0b32abc634c152a97234"
OLD_ASSIGNMENT_IDENTITY = (
    "renderweave-r5-product-transform-assignment/1.0:"
    "46c8e4c9c28b8628bac6532deeeb1a9ee311dda58b1a76f23a1b1d70abe7b540"
)
CORPUS_VERSION = "renderweave-visual-stage-corpus/2.0"
CORPUS_IDENTITY = (
    "renderweave-visual-stage-corpus/2.0:"
    "c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c"
)
ANNOTATION_SET_IDENTITY = (
    "renderweave-layered-annotation-set/2.0:"
    "a6f7796d0433bb59779a3e1b99fa3c20b3e49148d24eb69dfe17682414fa746a"
)
TERMINAL = "R5P_ASSIGNMENT_FROZEN"
MAX_ASSIGNMENT_BYTES = 1_048_576

TOP_FIELDS = frozenset(
    {
        "assignmentVersion", "evaluationVersion", "approvedSpecIdentity",
        "authorityIdentity", "baselineRevision", "oldAssignmentIdentity",
        "corpusVersion", "corpusIdentity", "annotationSetIdentity",
        "selectionPolicy", "seenUsagePolicy", "confirmationUsagePolicy",
        "caseAssignments", "thresholds", "identities", "runtimeComponents",
        "externalProviderUsage", "apiKeyReads", "terminalCode",
    }
)
CASE_FIELDS = frozenset(
    {
        "caseId", "cohort", "sourcePartition", "caseIdentity", "renderIdentity",
        "rawFixtureResource", "rawFixtureSha256", "width", "height", "regions",
    }
)
REGION_FIELDS = frozenset(
    {"baseViewId", "boundingBox", "marginPreset", "resolutionPreset"}
)
THRESHOLD_FIELDS = frozenset(
    {
        "perCaseTargetImprovementRule", "maximumPerCaseHallucinationIncrease",
        "minimumConfirmationLineRecallGainBps",
        "minimumConfirmationCharacterErrorReduction",
        "maximumConfirmationOrderRegressionBps",
        "maximumConfirmationRepeatRegressionBps",
        "coalescingIntersectionOverSmallerAreaBps",
    }
)
IDENTITY_FIELDS = frozenset(
    {
        "normalizerIdentity", "staticPlannerVersion", "staticPlannerSourceSha256",
        "actionModuleVersion", "actionModuleSourceSha256", "successorPlanVersion",
        "actionPolicyIdentity", "transformVersion", "transformSourceSha256",
        "acquisitionPolicyIdentity", "capabilityIdentity", "adapterIdentity",
        "adapterSourceSha256", "projectionIdentity", "coalescingIdentity",
        "coalescingTextRule", "coalescingGeometryRule", "caseEvaluatorIdentity",
        "evaluatorIdentity", "runProtocolIdentity", "runtimeIdentity",
    }
)
RUNTIME_FIELDS = frozenset(
    {
        "os", "arch", "javaVendor", "javaRuntime", "python", "rapidocr",
        "openvino", "imageRuntime", "adapterSha256", "modelSha256",
    }
)
USAGE_FIELDS = frozenset({"attempts", "reservations", "costMicrosCny"})

HEADER = {
    "assignmentVersion": ASSIGNMENT_VERSION,
    "evaluationVersion": EVALUATION_VERSION,
    "approvedSpecIdentity": (
        "spec-sha256:"
        "650ad1632347592d1fc34325983744c02563b43d8a565b9b1cd24e1a805a892a"
    ),
    "authorityIdentity": AUTHORITY_IDENTITY,
    "baselineRevision": BASELINE_REVISION,
    "oldAssignmentIdentity": OLD_ASSIGNMENT_IDENTITY,
    "corpusVersion": CORPUS_VERSION,
    "corpusIdentity": CORPUS_IDENTITY,
    "annotationSetIdentity": ANNOTATION_SET_IDENTITY,
    "selectionPolicy": "case-domain-difficulty-failure-slice-metadata-only-pre-result/1.0",
    "seenUsagePolicy": "veto-only-no-confirmation-no-holdout-no-ac021/1.0",
    "confirmationUsagePolicy": "sealed-confirmation-only-no-ac021/1.0",
}

# case, cohort, partition, case identity suffix, raster sha, width, height, difficulty
EXPECTED_CASES = (
    ("transit-board-v3", "SEEN_DIAGNOSTIC", "DEV",
     "688daa21a13118b5591d3057b6f1f15cef8a0e4f80a6549a4b80b19d8b043c0e",
     "2970b3648b580b46d87253560755fec752babcf3ff2435aa7e1c2c7fdd499790",
     1024, 768, "DENSE_TEXT"),
    ("restaurant-menu-v3", "SEEN_DIAGNOSTIC", "DEV",
     "6910f5288cbbde4ac0d813affb19e3e1df9fb8c3d7bab85249e56801e2e8db78",
     "409aa4d0634e0c3cafb05573c5b4f3fbbc7b6712ca4c61536b958f6f1d830e26",
     1024, 768, "DENSE_TEXT"),
    ("hospital-schedule-v3", "SEEN_DIAGNOSTIC", "DEV",
     "749916a935e98fbf48ae59181e1b6bcde0a0b01a347724af04566c22ac3a92f9",
     "a1d78928a11a598c3a4aaf1fc3f3fecae578604c990dbcc6e79bfdf43b1007d1",
     1024, 768, "DENSE_TEXT"),
    ("transit-board-v5", "SEEN_DIAGNOSTIC", "HOLDOUT",
     "c8e155a1da4f8d8d93a646b01c4375773b20c14742f9cb233eebaf5673853c4f",
     "e4c5a31cddf063b23cc1b00f9efe7e8ccad138dc7104c43d39ddfa4273c6ce90",
     1800, 1200, "NOISY"),
    ("transit-board-v2", "SEALED_CONFIRMATION", "DEV",
     "3976013e6e00f4c93fa874804366ff1d12df066985ca320fdd20e47f7c2ee08d",
     "be09af5b9a417ef30a743df10a741f41f944242b7774dbbd815df4a30a065f27",
     1000, 1600, "MULTI_COLUMN"),
    ("invoice-lines-v3", "SEALED_CONFIRMATION", "DEV",
     "5906265340b4c556196095d90c2b6e34b86ac6c5300dc0fea6fedabe6a18deea",
     "f415dc8b5b6560b4ba1840cccfced2322367209f23d18fa2b59e1d86dbe18f53",
     1024, 768, "DENSE_TEXT"),
    ("school-timetable-v4", "SEALED_CONFIRMATION", "DEV",
     "5c421a2ddace4db33f68e47a39a165474a917d679ae0f33a7f6a4655fdb4a06a",
     "5b25baf008d216e5e114142643709d57d677efe4ba1aad688ba4717488edd419",
     1400, 900, "LOW_CONTRAST"),
    ("building-directory-v5", "SEALED_CONFIRMATION", "HOLDOUT",
     "ca5be012237e052ca4adc6726bb8d6c75ed9ce2597b6bee130006b412a7baef9",
     "fee491f5b7f52e34c16119927c72d9172a6f0e3a97eb956227b43d75bc4149e7",
     1800, 1200, "NOISY"),
)

THRESHOLDS = {
    "perCaseTargetImprovementRule": "MATCHED_LINE_INCREASE_OR_CHARACTER_ERROR_REDUCTION",
    "maximumPerCaseHallucinationIncrease": 0,
    "minimumConfirmationLineRecallGainBps": 500,
    "minimumConfirmationCharacterErrorReduction": 1,
    "maximumConfirmationOrderRegressionBps": 100,
    "maximumConfirmationRepeatRegressionBps": 100,
    "coalescingIntersectionOverSmallerAreaBps": 5000,
}

IDENTITIES = {
    "normalizerIdentity": (
        "renderweave-input-normalizer-source-sha256/1.0:"
        "71a4f90ee7298fb3ef3a3550e34880ed3216213fc69f31b80f9b0e496570654a"
    ),
    "staticPlannerVersion": "renderweave-visual-view-plan/1.0",
    "staticPlannerSourceSha256": "e10d2955c9b463ee3996eac333abf4c8f32c2a82faf38e286796e56b7d52fa0c",
    "actionModuleVersion": "renderweave-bounded-visual-inspection/1.0",
    "actionModuleSourceSha256": "99df52f72e1b5ff06c064bf96281149cf56e631f6f285bcbb882eb1e09723e4f",
    "successorPlanVersion": "renderweave-visual-view-plan/2.0",
    "actionPolicyIdentity": (
        "AdaptiveInspectionPolicy/1.0:"
        "6843ae1ce61e0fa1804b3f0ec58c0ff8aba81ecae068d73b612070daa3a5b9bc"
    ),
    "transformVersion": "renderweave-r5-product-raster-transform/1.0",
    "transformSourceSha256": "3d1b0fd84a3d0600227f20c415fd4c1f333a0a26b8299812f5c412626058e552",
    "acquisitionPolicyIdentity": (
        "AcquisitionPolicy/1.0:"
        "32ade47685c07163e10f77be8b8ed46e420af7b7d381e1363d30886a19e26c52"
    ),
    "capabilityIdentity": "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1",
    "adapterIdentity": "rapidocr-local-process/1.0",
    "adapterSourceSha256": "d715b44731171e5d29f4e405ef2320c5e6e0ea13c8129d53da06715d13875b84",
    "projectionIdentity": "renderweave-r5p-source-projection/1.0",
    "coalescingIdentity": "renderweave-r5p-observation-coalescing/1.0",
    "coalescingTextRule": "unicode-nfc-whitespace-collapse-exact/1.0",
    "coalescingGeometryRule": "intersection-over-smaller-area-at-least-5000-bps/1.0",
    "caseEvaluatorIdentity": "renderweave-rapidocr-shadow-case-evaluator/1.0",
    "evaluatorIdentity": "renderweave-r5p-paired-product-view-evaluator/1.0",
    "runProtocolIdentity": "two-isolated-complete-paired-runs/1.0",
    "runtimeIdentity": (
        "renderweave-r5p-runtime/1.0:"
        "901eb9abb883969797387fdd45d4aeb7fccf2447743901de15087b2c4eac6e34"
    ),
}

RUNTIME = {
    "os": "Windows 11", "arch": "amd64", "javaVendor": "Oracle Corporation",
    "javaRuntime": "21.0.11+9-LTS-211", "python": "3.12.13",
    "rapidocr": "3.9.2", "openvino": "2026.0.0",
    "imageRuntime": "java2d-bicubic+java-imageio-png/1.0",
    "adapterSha256": "d715b44731171e5d29f4e405ef2320c5e6e0ea13c8129d53da06715d13875b84",
    "modelSha256": "c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5",
}

SOURCE_BINDINGS = (
    ("normalizerIdentity", "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/input/InputNormalizer.java", True),
    ("staticPlannerSourceSha256", "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/MultiScaleVisualViewPlanner.java", False),
    ("actionModuleSourceSha256", "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/BoundedVisualInspection.java", False),
    ("transformSourceSha256", "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/R5ProductRasterTransform.java", False),
    ("adapterSourceSha256", "tools/document-vision/rapidocr_adapter.py", False),
)


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def strict_json(raw: bytes, code: str = "R5P_ASSIGNMENT_JSON_INVALID") -> dict[str, Any]:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                fail(code)
            result[key] = value
        return result

    def reject_float(_: str) -> Any:
        fail(code)

    try:
        value = json.loads(
            raw.decode("utf-8"), object_pairs_hook=pairs, parse_float=reject_float
        )
    except (UnicodeDecodeError, json.JSONDecodeError, VerificationError):
        fail(code)
    if type(value) is not dict:
        fail(code)
    return value


def exact_fields(value: Any, fields: frozenset[str], code: str) -> dict[str, Any]:
    if type(value) is not dict or frozenset(value) != fields:
        fail(code)
    return value


def exact(value: Any, expected: Any, code: str) -> None:
    if type(value) is not type(expected) or value != expected:
        fail(code)


def read(path: pathlib.Path, code: str) -> bytes:
    try:
        return path.resolve(strict=True).read_bytes()
    except (OSError, ValueError):
        fail(code)


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_source_sha(raw: bytes) -> str:
    return sha256(raw.replace(b"\r\n", b"\n"))


def framed_sha256(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def regions(case_id: str) -> list[dict[str, Any]]:
    boxes = (
        ([200, 200, 9800, 2900], [200, 2900, 9800, 9800])
        if case_id.startswith("transit-board-")
        else ([200, 200, 9800, 2500], [200, 2500, 9800, 9800])
    )
    return [
        {
            "baseViewId": "view-00-overview-00",
            "boundingBox": box,
            "marginPreset": "TIGHT_0000_BPS",
            "resolutionPreset": "INSPECT_LONG_EDGE_2400",
        }
        for box in boxes
    ]


def png_dimensions(raw: bytes) -> tuple[int, int]:
    if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
        fail("R5P_ASSIGNMENT_FIXTURE_DRIFT")
    return struct.unpack(">II", raw[16:24])


def verify_corpus_and_old_assignment(repository: pathlib.Path) -> None:
    resources = repository / "renderweave-inference/src/main/resources"
    old_path = resources / "visual-eval/r5/product-transform-assignment-v1.json"
    old_raw = read(old_path, "R5P_ASSIGNMENT_OLD_ASSIGNMENT_DRIFT")
    if sha256(old_raw) != OLD_ASSIGNMENT_IDENTITY.rsplit(":", 1)[1]:
        fail("R5P_ASSIGNMENT_OLD_ASSIGNMENT_DRIFT")
    old = strict_json(old_raw, "R5P_ASSIGNMENT_OLD_ASSIGNMENT_DRIFT")
    expected_old_fields = frozenset(
        {"assignmentVersion", "corpusVersion", "corpusIdentity", "staticViewKind",
         "staticLongEdge", "caseAssignments"}
    )
    exact_fields(old, expected_old_fields, "R5P_ASSIGNMENT_OLD_ASSIGNMENT_DRIFT")
    if (
        old["assignmentVersion"] != OLD_ASSIGNMENT_IDENTITY.rsplit(":", 1)[0]
        or old["corpusVersion"] != CORPUS_VERSION
        or old["corpusIdentity"] != CORPUS_IDENTITY
        or old["staticViewKind"] != "OVERVIEW"
        or type(old["staticLongEdge"]) is not int
        or old["staticLongEdge"] != 768
        or type(old["caseAssignments"]) is not list
        or len(old["caseAssignments"]) != 4
    ):
        fail("R5P_ASSIGNMENT_OLD_ASSIGNMENT_DRIFT")
    for item, expected in zip(old["caseAssignments"], EXPECTED_CASES[:4], strict=True):
        expected_old = {
            "caseId": expected[0], "partition": expected[2],
            "regions": regions(expected[0]),
        }
        if item != expected_old:
            fail("R5P_ASSIGNMENT_SEEN_INHERITANCE_DRIFT")

    lock_raw = read(
        resources / "visual-eval/v2/identity-lock.json",
        "R5P_ASSIGNMENT_CORPUS_DRIFT",
    )
    lock = strict_json(lock_raw, "R5P_ASSIGNMENT_CORPUS_DRIFT")
    if (
        lock.get("corpusVersion") != CORPUS_VERSION
        or lock.get("corpusIdentity") != CORPUS_IDENTITY
        or lock.get("annotationSetIdentity") != ANNOTATION_SET_IDENTITY
        or type(lock.get("cases")) is not list
    ):
        fail("R5P_ASSIGNMENT_CORPUS_DRIFT")
    by_id = {
        item.get("caseId"): item
        for item in lock["cases"]
        if type(item) is dict and type(item.get("caseId")) is str
    }
    for case_id, _, partition, case_sha, raster_sha, _, _, difficulty in EXPECTED_CASES:
        item = by_id.get(case_id)
        expected_domain = "transit-board" if case_id.startswith("transit-board-") else "generic"
        expected_slices = (
            [difficulty, "REPEATED_LIST"]
            if difficulty in {"DENSE_TEXT", "MULTI_COLUMN"}
            else ["REPEATED_LIST"]
        )
        if (
            item is None
            or item.get("partition") != partition
            or item.get("difficulty") != difficulty
            or item.get("domain") != expected_domain
            or item.get("failureSlices") != expected_slices
            or item.get("caseIdentity") != f"renderweave-layered-case/2.0:{case_sha}"
            or item.get("renderIdentity") != f"render-sha256:{raster_sha}"
        ):
            fail("R5P_ASSIGNMENT_CORPUS_DRIFT")


def verify_cases(value: Any, repository: pathlib.Path) -> list[dict[str, Any]]:
    if type(value) is not list or len(value) != len(EXPECTED_CASES):
        fail("R5P_ASSIGNMENT_CASE_SET_DRIFT")
    resources = (repository / "renderweave-inference/src/main/resources").resolve()
    verified: list[dict[str, Any]] = []
    for item_value, expected in zip(value, EXPECTED_CASES, strict=True):
        item = exact_fields(item_value, CASE_FIELDS, "R5P_ASSIGNMENT_CASE_FIELDS_INVALID")
        case_id, cohort, partition, case_sha, raster_sha, width, height, _ = expected
        if item["caseId"] != case_id or item["cohort"] != cohort:
            fail("R5P_ASSIGNMENT_CASE_SET_DRIFT")
        if item["sourcePartition"] != partition:
            fail("R5P_ASSIGNMENT_PARTITION_DRIFT")
        if (
            item["caseIdentity"] != f"renderweave-layered-case/2.0:{case_sha}"
            or item["renderIdentity"] != f"render-sha256:{raster_sha}"
        ):
            fail("R5P_ASSIGNMENT_CASE_IDENTITY_DRIFT")
        expected_resource = f"visual-eval/r5p/raw/{case_id}.png"
        if (
            item["rawFixtureResource"] != expected_resource
            or item["rawFixtureSha256"] != raster_sha
            or type(item["width"]) is not int
            or type(item["height"]) is not int
            or item["width"] != width
            or item["height"] != height
        ):
            fail("R5P_ASSIGNMENT_FIXTURE_DRIFT")
        if type(item["regions"]) is not list or len(item["regions"]) != 2:
            fail("R5P_ASSIGNMENT_REGION_DRIFT")
        for region in item["regions"]:
            exact_fields(region, REGION_FIELDS, "R5P_ASSIGNMENT_REGION_DRIFT")
        if item["regions"] != regions(case_id):
            fail("R5P_ASSIGNMENT_REGION_DRIFT")
        fixture_path = (resources / expected_resource).resolve()
        try:
            fixture_path.relative_to(resources)
        except ValueError:
            fail("R5P_ASSIGNMENT_FIXTURE_DRIFT")
        raw = read(fixture_path, "R5P_ASSIGNMENT_FIXTURE_MISSING")
        if sha256(raw) != raster_sha or png_dimensions(raw) != (width, height):
            fail("R5P_ASSIGNMENT_FIXTURE_DRIFT")
        verified.append(item)

    seen = [item["caseId"] for item in verified if item["cohort"] == "SEEN_DIAGNOSTIC"]
    confirmation = [
        item for item in verified if item["cohort"] == "SEALED_CONFIRMATION"
    ]
    if seen != [item[0] for item in EXPECTED_CASES[:4]]:
        fail("R5P_ASSIGNMENT_CASE_SET_DRIFT")
    if [item["caseId"] for item in confirmation] != [item[0] for item in EXPECTED_CASES[4:]]:
        fail("R5P_ASSIGNMENT_COHORT_OVERLAP")
    if (
        len({item["caseIdentity"] for item in verified}) != len(verified)
        or len({item["rawFixtureSha256"] for item in verified}) != len(verified)
        or sum(item["sourcePartition"] == "DEV" for item in confirmation) != 3
        or sum(item["sourcePartition"] == "HOLDOUT" for item in confirmation) != 1
        or "transit-board-v5" in {item["caseId"] for item in confirmation}
    ):
        fail("R5P_ASSIGNMENT_COHORT_OVERLAP")
    return verified


def verify_runtime(value: Any, runtime_identity: Any) -> None:
    runtime = exact_fields(value, RUNTIME_FIELDS, "R5P_ASSIGNMENT_RUNTIME_DRIFT")
    if runtime != RUNTIME:
        fail("R5P_ASSIGNMENT_RUNTIME_DRIFT")
    frames = [
        f"os={runtime['os']}", f"arch={runtime['arch']}",
        f"java-vendor={runtime['javaVendor']}",
        f"java-runtime={runtime['javaRuntime']}", f"python={runtime['python']}",
        f"rapidocr={runtime['rapidocr']}", f"openvino={runtime['openvino']}",
        f"image-runtime={runtime['imageRuntime']}",
        f"adapter-sha256={runtime['adapterSha256']}",
        f"model-sha256={runtime['modelSha256']}",
    ]
    expected = f"renderweave-r5p-runtime/1.0:{framed_sha256(frames)}"
    if runtime_identity != expected:
        fail("R5P_ASSIGNMENT_RUNTIME_DRIFT")


def verify_sources(identities: dict[str, Any], repository: pathlib.Path) -> None:
    for field, relative, prefixed in SOURCE_BINDINGS:
        raw = read(repository / relative, "R5P_ASSIGNMENT_SOURCE_MISSING")
        actual = canonical_source_sha(raw)
        expected = identities[field].rsplit(":", 1)[1] if prefixed else identities[field]
        if actual != expected:
            fail("R5P_ASSIGNMENT_SOURCE_DRIFT")


def threshold_record_string(thresholds: dict[str, Any]) -> str:
    return (
        "Thresholds["
        f"perCaseTargetImprovementRule={thresholds['perCaseTargetImprovementRule']}, "
        f"maximumPerCaseHallucinationIncrease={thresholds['maximumPerCaseHallucinationIncrease']}, "
        f"minimumConfirmationLineRecallGainBps={thresholds['minimumConfirmationLineRecallGainBps']}, "
        f"minimumConfirmationCharacterErrorReduction={thresholds['minimumConfirmationCharacterErrorReduction']}, "
        f"maximumConfirmationOrderRegressionBps={thresholds['maximumConfirmationOrderRegressionBps']}, "
        f"maximumConfirmationRepeatRegressionBps={thresholds['maximumConfirmationRepeatRegressionBps']}, "
        f"coalescingIntersectionOverSmallerAreaBps={thresholds['coalescingIntersectionOverSmallerAreaBps']}]"
    )


def verify(assignment_path: pathlib.Path, repository: pathlib.Path) -> dict[str, Any]:
    repository = repository.resolve()
    raw = read(assignment_path, "R5P_ASSIGNMENT_RESOURCE_MISSING")
    if len(raw) > MAX_ASSIGNMENT_BYTES:
        fail("R5P_ASSIGNMENT_RESOURCE_OVERSIZE")
    document = strict_json(raw)
    exact_fields(document, TOP_FIELDS, "R5P_ASSIGNMENT_FIELDS_INVALID")
    for field, expected in HEADER.items():
        exact(document[field], expected, "R5P_ASSIGNMENT_AUTHORITY_DRIFT")

    verify_corpus_and_old_assignment(repository)
    cases = verify_cases(document["caseAssignments"], repository)

    thresholds = exact_fields(
        document["thresholds"], THRESHOLD_FIELDS, "R5P_ASSIGNMENT_THRESHOLD_DRIFT"
    )
    if thresholds != THRESHOLDS or any(type(value) is bool for value in thresholds.values()):
        fail("R5P_ASSIGNMENT_THRESHOLD_DRIFT")
    identities = exact_fields(
        document["identities"], IDENTITY_FIELDS, "R5P_ASSIGNMENT_IDENTITY_DRIFT"
    )
    if identities != IDENTITIES:
        fail("R5P_ASSIGNMENT_IDENTITY_DRIFT")
    verify_runtime(document["runtimeComponents"], identities["runtimeIdentity"])
    verify_sources(identities, repository)

    usage = exact_fields(
        document["externalProviderUsage"], USAGE_FIELDS,
        "R5P_ASSIGNMENT_PROVIDER_USAGE_INVALID",
    )
    if any(type(usage[field]) is not int for field in USAGE_FIELDS):
        fail("R5P_ASSIGNMENT_PROVIDER_USAGE_INVALID")
    if any(usage[field] != 0 for field in USAGE_FIELDS):
        fail("R5P_ASSIGNMENT_PROVIDER_USAGE_NONZERO")
    if type(document["apiKeyReads"]) is not int:
        fail("R5P_ASSIGNMENT_API_KEY_READ_INVALID")
    if document["apiKeyReads"] != 0:
        fail("R5P_ASSIGNMENT_API_KEY_READ_NONZERO")
    exact(document["terminalCode"], TERMINAL, "R5P_ASSIGNMENT_TERMINAL_DRIFT")

    assignment_identity = f"{ASSIGNMENT_VERSION}:{sha256(raw)}"
    if assignment_identity != f"{ASSIGNMENT_VERSION}:{ASSIGNMENT_SHA256}":
        fail("R5P_ASSIGNMENT_RESOURCE_DRIFT")
    frames = [
        f"assignment={assignment_identity}", f"authority={AUTHORITY_IDENTITY}",
        f"baseline={BASELINE_REVISION}",
        f"policy={identities['actionPolicyIdentity']}",
        f"acquisition={identities['acquisitionPolicyIdentity']}",
        f"runtime={identities['runtimeIdentity']}",
        f"evaluator={identities['evaluatorIdentity']}",
        f"thresholds={threshold_record_string(thresholds)}",
    ]
    frames.extend(
        f"case={item['cohort']}:{item['caseIdentity']}:{item['rawFixtureSha256']}"
        for item in cases
    )
    evaluation_identity = f"{EVALUATION_VERSION}:{framed_sha256(frames)}"
    confirmation = [item for item in cases if item["cohort"] == "SEALED_CONFIRMATION"]
    return {
        "verifierVersion": "renderweave-r5p-assignment-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_INDEPENDENT_ASSIGNMENT_ACCESS_AUDIT",
        "assignmentIdentity": assignment_identity,
        "evaluationIdentity": evaluation_identity,
        "seenDiagnosticCount": len(cases) - len(confirmation),
        "sealedConfirmationCount": len(confirmation),
        "confirmationDevCount": sum(
            item["sourcePartition"] == "DEV" for item in confirmation
        ),
        "confirmationHoldoutCount": sum(
            item["sourcePartition"] == "HOLDOUT" for item in confirmation
        ),
        "verifiedRawFixtureCount": len(cases),
        "verifiedSourceIdentityCount": len(SOURCE_BINDINGS),
        "qualityResultsRead": 0,
        "providerAttempts": usage["attempts"],
        "providerReservations": usage["reservations"],
        "providerCostMicrosCny": usage["costMicrosCny"],
        "apiKeyReads": document["apiKeyReads"],
        "disposition": document["terminalCode"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--assignment", required=True, type=pathlib.Path)
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    result = verify(args.assignment, args.repository)
    encoded = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(encoded + "\n")
    print("R5P assignment: FROZEN; assurance=A2; quality-results=0; Provider=0; J1=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
