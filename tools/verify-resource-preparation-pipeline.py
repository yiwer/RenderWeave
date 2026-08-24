#!/usr/bin/env python3
"""Independent stdlib replay for manifest-order Renderer resource preparation."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


PROFILE = "renderweave-resource-preparation-pipeline-vectors/1"
RENDERER_PROFILE = "renderweave-renderer/1.0"
RESOURCE_ID = re.compile(r"rwres_[0-9a-f]{64}\Z")
EXPECTED_SUCCESS_IDS = {"font-image-duplicate-font", "empty-manifest"}
EXPECTED_PREPARATION_FAILURE_IDS = {
    "first-media-mismatch-stops-next-fetch",
    "first-corrupt-png-stops-next-fetch",
}
EXPECTED_FETCH_FAILURE_IDS = {"second-fetch-failure-stops-third"}
EXPECTED_CONTROL_IDS = {
    "deadline-before-first-fetch",
    "target-policy-drift-before-first-fetch",
}
EXPECTED_PUBLIC_CODES = [
    "RESOURCE_LEASE_EXPIRED",
    "RESOURCE_BUDGET_EXCEEDED",
    "FETCH_FAILED",
    "LENGTH_MISMATCH",
    "HASH_MISMATCH",
    "MEDIA_MISMATCH",
    "DECODE_FAILED",
]
EXPECTED_BOUNDARY = {
    "resourcePreparationPipeline": "MANIFEST_ORDER_FETCH_RAW_IMAGE_FONT_AUTOMATED_VERIFIED",
    "resourceManifest": "IMMUTABLE_COMPLETE_ONLY",
    "fontShaping": "UNWIRED",
    "glyphConsumer": "UNWIRED",
    "nativeFontStack": "BUILD_NOT_AUTHORIZED",
    "sceneConsumer": "UNWIRED",
    "daemonOutputPath": "UNWIRED",
    "profileAvailability": "NOT_REGISTERED",
    "certificationStatus": "NOT_CERTIFIED",
    "processRasterImplementation": "ABSENT",
    "productRoute": "CLOSED",
    "providerAttempts": 0,
}


class VerificationFailure(ValueError):
    pass


@dataclass
class Verifier:
    checks: int = 0

    def require(self, condition: bool, message: str) -> None:
        self.checks += 1
        if not condition:
            raise VerificationFailure(message)


def exact_members(value: dict[str, Any], expected: set[str], context: str) -> None:
    actual = set(value)
    if actual != expected:
        raise VerificationFailure(
            f"{context}: members drifted missing={sorted(expected - actual)} extra={sorted(actual - expected)}"
        )


def sha256_prefixed(raw: bytes) -> str:
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def load_json(path: Path) -> tuple[bytes, dict[str, Any]]:
    raw = path.read_bytes()
    value = json.loads(raw.decode("utf-8"))
    if not isinstance(value, dict):
        raise VerificationFailure(f"{path}: root must be an object")
    return raw, value


def case_map(asset_vectors: dict[str, Any]) -> dict[str, dict[str, Any]]:
    cases = asset_vectors.get("cases")
    if not isinstance(cases, list):
        raise VerificationFailure("Asset corpus cases must be an array")
    result: dict[str, dict[str, Any]] = {}
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("id"), str):
            raise VerificationFailure("Asset corpus case identity is invalid")
        if case["id"] in result:
            raise VerificationFailure("Asset corpus case identity is duplicated")
        result[case["id"]] = case
    return result


def admitted_case(asset_cases: dict[str, dict[str, Any]], case_id: str) -> dict[str, Any]:
    case = asset_cases.get(case_id)
    if case is None or case.get("expected", {}).get("outcome") != "ADMITTED":
        raise VerificationFailure(f"{case_id}: referenced Asset case is not admitted")
    if case.get("input", {}).get("kind") != "BASE64":
        raise VerificationFailure(f"{case_id}: referenced Asset body is not exact BASE64")
    body = base64.b64decode(case["input"]["data"], validate=True)
    expected = case["expected"]
    if len(body) != expected["byteLength"]:
        raise VerificationFailure(f"{case_id}: Asset byte length drifted")
    if hashlib.sha256(body).hexdigest() != expected["sha256"]:
        raise VerificationFailure(f"{case_id}: Asset digest drifted")
    return case


def media_type(case_id: str) -> str:
    if case_id.startswith("png-"):
        return "image/png"
    if case_id.startswith("jpeg-"):
        return "image/jpeg"
    if case_id.startswith("webp-"):
        return "image/webp"
    if case_id.startswith("font-ttf-"):
        return "font/ttf"
    if case_id.startswith("font-otf-"):
        return "font/otf"
    raise VerificationFailure(f"{case_id}: media type is not mapped")


def raw_key(case: dict[str, Any], case_id: str) -> tuple[Any, ...]:
    expected = case["expected"]
    return (
        RENDERER_PROFILE,
        expected["kind"],
        expected["sha256"],
        expected["byteLength"],
        media_type(case_id),
    )


def font_table_counts(font_vectors: dict[str, Any]) -> dict[str, int]:
    prepared = font_vectors.get("preparedCases")
    if not isinstance(prepared, list):
        raise VerificationFailure("FONT prepared cases must be an array")
    result: dict[str, int] = {}
    for case in prepared:
        result[case["assetCaseId"]] = case["facts"]["tableCount"]
    return result


def validate_resource_shape(
    verifier: Verifier,
    resource: dict[str, Any],
    asset_cases: dict[str, dict[str, Any]],
    context: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    required = {"resourceId", "documentAssetCaseId", "bodyAssetCaseId"}
    optional = {"expectedKind", "expectedRawCacheHit", "expectedSemanticCacheHit"}
    verifier.require(
        required <= set(resource) <= required | optional,
        f"{context}: resource members drifted",
    )
    verifier.require(
        isinstance(resource["resourceId"], str) and RESOURCE_ID.fullmatch(resource["resourceId"]) is not None,
        f"{context}: resourceId is not canonical",
    )
    document_case = admitted_case(asset_cases, resource["documentAssetCaseId"])
    body_case = admitted_case(asset_cases, resource["bodyAssetCaseId"])
    verifier.require(
        document_case["expected"]["acceptanceProfileId"] == "renderweave-asset-acceptance/1.0",
        f"{context}: document Asset profile drifted",
    )
    verifier.require(
        body_case["expected"]["acceptanceProfileId"] == "renderweave-asset-acceptance/1.0",
        f"{context}: body Asset profile drifted",
    )
    return document_case, body_case


def replay_success(
    verifier: Verifier,
    case: dict[str, Any],
    asset_cases: dict[str, dict[str, Any]],
    table_counts: dict[str, int],
) -> None:
    exact_members(case, {"id", "resources", "expectedStats"}, f"success {case.get('id')}")
    verifier.require(isinstance(case["resources"], list), f"{case['id']}: resources must be an array")
    seen_ids: set[str] = set()
    raw_seen: set[tuple[Any, ...]] = set()
    image_seen: set[tuple[Any, ...]] = set()
    font_seen: set[tuple[Any, ...]] = set()
    physical_bytes = 0
    raw_retained_bytes = 0
    image_retained_bytes = 0
    font_retained_tables = 0
    for index, resource in enumerate(case["resources"]):
        document_case, body_case = validate_resource_shape(
            verifier, resource, asset_cases, f"{case['id']}[{index}]"
        )
        verifier.require(resource["resourceId"] not in seen_ids, f"{case['id']}: duplicate resourceId")
        seen_ids.add(resource["resourceId"])
        verifier.require(
            resource["documentAssetCaseId"] == resource["bodyAssetCaseId"],
            f"{case['id']}: success body differs from descriptor authority",
        )
        expected = document_case["expected"]
        verifier.require(resource["expectedKind"] == expected["kind"], f"{case['id']}: kind drifted")
        key = raw_key(body_case, resource["bodyAssetCaseId"])
        raw_hit = key in raw_seen
        verifier.require(resource["expectedRawCacheHit"] is raw_hit, f"{case['id']}: raw hit drifted")
        physical_bytes += body_case["expected"]["byteLength"]
        if not raw_hit:
            raw_seen.add(key)
            raw_retained_bytes += body_case["expected"]["byteLength"]
        semantic_seen = image_seen if expected["kind"] == "IMAGE" else font_seen
        semantic_hit = key in semantic_seen
        verifier.require(
            resource["expectedSemanticCacheHit"] is semantic_hit,
            f"{case['id']}: semantic hit drifted",
        )
        if not semantic_hit:
            semantic_seen.add(key)
            if expected["kind"] == "IMAGE":
                descriptor = expected["descriptor"]
                image_retained_bytes += descriptor["logicalWidthPx"] * descriptor["logicalHeightPx"] * 4
            else:
                font_retained_tables += table_counts[resource["documentAssetCaseId"]]
    expected_stats = case["expectedStats"]
    exact_members(
        expected_stats,
        {
            "physicalFetchBytes",
            "rawUniqueContent",
            "rawRetainedBytes",
            "decodedImageUniqueContent",
            "decodedImageRetainedBytes",
            "preparedFontUniqueContent",
            "preparedFontRetainedTables",
        },
        f"{case['id']}: expectedStats",
    )
    actual = {
        "physicalFetchBytes": physical_bytes,
        "rawUniqueContent": len(raw_seen),
        "rawRetainedBytes": raw_retained_bytes,
        "decodedImageUniqueContent": len(image_seen),
        "decodedImageRetainedBytes": image_retained_bytes,
        "preparedFontUniqueContent": len(font_seen),
        "preparedFontRetainedTables": font_retained_tables,
    }
    verifier.require(actual == expected_stats, f"{case['id']}: stats drifted")


def png_crc_valid(body: bytes) -> bool:
    if not body.startswith(b"\x89PNG\r\n\x1a\n"):
        return False
    offset = 8
    saw_iend = False
    while offset < len(body):
        if offset + 12 > len(body):
            return False
        length = int.from_bytes(body[offset : offset + 4], "big")
        end = offset + 12 + length
        if end > len(body):
            return False
        kind = body[offset + 4 : offset + 8]
        payload = body[offset + 8 : offset + 8 + length]
        expected_crc = int.from_bytes(body[offset + 8 + length : end], "big")
        if binascii.crc32(kind + payload) & 0xFFFFFFFF != expected_crc:
            return False
        offset = end
        if kind == b"IEND":
            saw_iend = True
            break
    return saw_iend and offset == len(body)


def replay_preparation_failure(
    verifier: Verifier,
    case: dict[str, Any],
    asset_cases: dict[str, dict[str, Any]],
    mutation_corpus: Any,
) -> None:
    expected_members = {
        "id",
        "resources",
        "expectedCode",
        "expectedResourceId",
        "expectedFetchedResourceIds",
    }
    if "mutation" in case:
        expected_members.add("mutation")
    exact_members(case, expected_members, f"preparation failure {case.get('id')}")
    resources = case["resources"]
    verifier.require(isinstance(resources, list) and len(resources) >= 2, f"{case['id']}: resources drifted")
    first_document, first_body = validate_resource_shape(
        verifier, resources[0], asset_cases, f"{case['id']}[0]"
    )
    for index, resource in enumerate(resources[1:], start=1):
        validate_resource_shape(verifier, resource, asset_cases, f"{case['id']}[{index}]")
    verifier.require(
        case["expectedFetchedResourceIds"] == [resources[0]["resourceId"]],
        f"{case['id']}: preparation failure did not stop before next fetch",
    )
    verifier.require(case["expectedResourceId"] == resources[0]["resourceId"], f"{case['id']}: locator drifted")
    if case["expectedCode"] == "MEDIA_MISMATCH":
        verifier.require(
            first_document["expected"]["kind"] != first_body["expected"]["kind"],
            f"{case['id']}: media mismatch stimulus drifted",
        )
        verifier.require("mutation" not in case, f"{case['id']}: unexpected mutation")
    elif case["expectedCode"] == "DECODE_FAILED":
        verifier.require(case.get("mutation") == "FLIP_LAST_BYTE", f"{case['id']}: mutation drifted")
        original = base64.b64decode(first_body["input"]["data"], validate=True)
        mutated = original[:-1] + bytes([original[-1] ^ 1])
        verifier.require(png_crc_valid(original), f"{case['id']}: source PNG is invalid")
        verifier.require(not png_crc_valid(mutated), f"{case['id']}: mutation did not invalidate PNG")
        mutation_corpus.update(case["id"].encode() + b"\0" + hashlib.sha256(mutated).digest())
    else:
        verifier.require(False, f"{case['id']}: unsupported expected preparation code")


def replay_fetch_failure(
    verifier: Verifier,
    case: dict[str, Any],
    asset_cases: dict[str, dict[str, Any]],
) -> None:
    exact_members(
        case,
        {
            "id",
            "failureIndex",
            "resources",
            "expectedCode",
            "expectedResourceId",
            "expectedFetchedResourceIds",
        },
        f"fetch failure {case.get('id')}",
    )
    resources = case["resources"]
    verifier.require(isinstance(resources, list) and resources, f"{case['id']}: resources drifted")
    for index, resource in enumerate(resources):
        validate_resource_shape(verifier, resource, asset_cases, f"{case['id']}[{index}]")
    failure_index = case["failureIndex"]
    verifier.require(isinstance(failure_index, int) and 0 <= failure_index < len(resources), f"{case['id']}: failureIndex drifted")
    expected_observations = [resource["resourceId"] for resource in resources[: failure_index + 1]]
    verifier.require(case["expectedFetchedResourceIds"] == expected_observations, f"{case['id']}: stop order drifted")
    verifier.require(case["expectedCode"] == "FETCH_FAILED", f"{case['id']}: code drifted")
    verifier.require(case["expectedResourceId"] == resources[failure_index]["resourceId"], f"{case['id']}: locator drifted")


def verify(vector_path: Path, repo_root: Path) -> dict[str, Any]:
    verifier = Verifier()
    raw, vectors = load_json(vector_path)
    exact_members(
        vectors,
        {
            "profile",
            "rendererProfileIdentity",
            "assetKernelVectorPath",
            "assetKernelVectorSha256",
            "imageDecodeVectorPath",
            "imageDecodeVectorSha256",
            "fontPrepareVectorPath",
            "fontPrepareVectorSha256",
            "successCases",
            "preparationFailureCases",
            "fetchFailureCases",
            "controlCases",
            "problemProjection",
            "boundary",
        },
        "pipeline vectors",
    )
    verifier.require(vectors["profile"] == PROFILE, "pipeline profile drifted")
    verifier.require(vectors["rendererProfileIdentity"] == RENDERER_PROFILE, "Renderer Profile drifted")

    asset_raw, asset_vectors = load_json(repo_root / vectors["assetKernelVectorPath"])
    image_raw, image_vectors = load_json(repo_root / vectors["imageDecodeVectorPath"])
    font_raw, font_vectors = load_json(repo_root / vectors["fontPrepareVectorPath"])
    verifier.require(sha256_prefixed(asset_raw) == vectors["assetKernelVectorSha256"], "Asset corpus digest drifted")
    verifier.require(sha256_prefixed(image_raw) == vectors["imageDecodeVectorSha256"], "IMAGE vector digest drifted")
    verifier.require(sha256_prefixed(font_raw) == vectors["fontPrepareVectorSha256"], "FONT vector digest drifted")
    verifier.require(image_vectors["rendererProfileIdentity"] == RENDERER_PROFILE, "IMAGE Profile drifted")
    verifier.require(font_vectors["rendererProfileIdentity"] == RENDERER_PROFILE, "FONT Profile drifted")
    asset_cases = case_map(asset_vectors)
    table_counts = font_table_counts(font_vectors)

    success_cases = vectors["successCases"]
    preparation_cases = vectors["preparationFailureCases"]
    fetch_cases = vectors["fetchFailureCases"]
    control_cases = vectors["controlCases"]
    verifier.require(isinstance(success_cases, list), "successCases must be an array")
    verifier.require(isinstance(preparation_cases, list), "preparationFailureCases must be an array")
    verifier.require(isinstance(fetch_cases, list), "fetchFailureCases must be an array")
    verifier.require(isinstance(control_cases, list), "controlCases must be an array")
    verifier.require({case["id"] for case in success_cases} == EXPECTED_SUCCESS_IDS, "success case set drifted")
    verifier.require(
        {case["id"] for case in preparation_cases} == EXPECTED_PREPARATION_FAILURE_IDS,
        "preparation failure case set drifted",
    )
    verifier.require({case["id"] for case in fetch_cases} == EXPECTED_FETCH_FAILURE_IDS, "fetch failure case set drifted")
    verifier.require({case["id"] for case in control_cases} == EXPECTED_CONTROL_IDS, "control case set drifted")

    for case in success_cases:
        replay_success(verifier, case, asset_cases, table_counts)
    mutation_corpus = hashlib.sha256()
    for case in preparation_cases:
        replay_preparation_failure(verifier, case, asset_cases, mutation_corpus)
    for case in fetch_cases:
        replay_fetch_failure(verifier, case, asset_cases)

    for case in control_cases:
        exact_members(
            case,
            {"id", "expectedCode", "expectedResourceId", "expectedFetchedResourceIds"}
            | ({"fetchUrl"} if "fetchUrl" in case else set()),
            f"control {case.get('id')}",
        )
        verifier.require(case["expectedResourceId"] is None, f"{case['id']}: control locator leaked")
        verifier.require(case["expectedFetchedResourceIds"] == [], f"{case['id']}: control fetched bytes")
        if case["id"] == "deadline-before-first-fetch":
            verifier.require(case["expectedCode"] == "RENDER_DEADLINE_EXCEEDED", "deadline code drifted")
            verifier.require("fetchUrl" not in case, "deadline case gained target")
        else:
            verifier.require(case["expectedCode"] == "RENDER_INTERNAL_ERROR", "target code drifted")
            verifier.require(
                case["fetchUrl"] == "https://evil.example/internal/render-assets/token",
                "target drift stimulus changed",
            )

    projection = vectors["problemProjection"]
    exact_members(
        projection,
        {"engineStage", "publicResourceCodes", "internalCodesWithoutLocator"},
        "problemProjection",
    )
    verifier.require(projection["engineStage"] == "RESOURCE_PREPARATION", "problem stage drifted")
    verifier.require(projection["publicResourceCodes"] == EXPECTED_PUBLIC_CODES, "public resource codes drifted")
    verifier.require(
        projection["internalCodesWithoutLocator"]
        == ["RENDER_INTERNAL_ERROR", "RENDER_DEADLINE_EXCEEDED"],
        "internal locator boundary drifted",
    )
    verifier.require(vectors["boundary"] == EXPECTED_BOUNDARY, "honest boundary drifted")

    total = len(success_cases) + len(preparation_cases) + len(fetch_cases) + len(control_cases)
    return {
        "verifier": "renderweave-resource-preparation-pipeline-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "pipelineAssurance": "A2_PYTHON_STDLIB_ORDER_CACHE_PROBLEM_STATE_MODEL",
        "codecAssurance": "A2_EXISTING_IMAGE_FONT_INDEPENDENT_VECTORS_REUSED",
        "successCases": len(success_cases),
        "preparationFailureCases": len(preparation_cases),
        "fetchFailureCases": len(fetch_cases),
        "controlCases": len(control_cases),
        "total": total,
        "passed": total,
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": sha256_prefixed(raw),
        "assetKernelVectorSha256": sha256_prefixed(asset_raw),
        "imageDecodeVectorSha256": sha256_prefixed(image_raw),
        "fontPrepareVectorSha256": sha256_prefixed(font_raw),
        "mutationCorpusSha256": "sha256:" + mutation_corpus.hexdigest(),
        "rendererProfileIdentity": vectors["rendererProfileIdentity"],
        **vectors["boundary"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    repo_root = Path(__file__).resolve().parents[1]
    parser.add_argument(
        "--vectors",
        type=Path,
        default=repo_root / "renderer" / "resource-preparation-pipeline-vectors-v1.json",
    )
    parser.add_argument("--repo-root", type=Path, default=repo_root)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.vectors, args.repo_root)
        if args.report is not None:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(
                json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n"
            )
        print(
            "Resource preparation pipeline independent replay: "
            f"{report['passed']}/{report['total']} cases, {report['checks']} checks, "
            f"vector={report['vectorSha256']}, mutations={report['mutationCorpusSha256']}"
        )
        return 0
    except (OSError, VerificationFailure, TypeError, ValueError, binascii.Error) as error:
        print(f"Resource preparation pipeline independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
