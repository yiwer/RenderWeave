#!/usr/bin/env python3
"""Independent payload-free verifier for the v50 local-id successor."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v50-successor-provider-zero/1.0"
V49_ID = "dashscope-qwen38-max-product-v49-hybrid-generic"
V50_ID = "dashscope-qwen38-max-product-v50-hybrid-generic"
V49_SHA = "acffdd4dd56ca2f1f7260fc5d37aa48ca3da488a0ae2718f2095bf1530e86eaf"
V50_SHA = "62f333aee7096f09d6d04dea004641e8b0a9c425ee133d09a563594d81200691"
V15_SHA = "107edf6a5a2abf31e718fdc8245b640ec251a5dab9a496f502e38bbf396ceacf"
V16_SHA = "c01f11fb68d846b0fe6a71d42615dfb410d7d9945458f57b2f50748ef61451b5"
V49_AUTH_SHA = "3eabfef97ad0a9f1c7fe7d947c527587109be30386bd0702a7cb1d671f90c0fa"
V49_TERMINAL_SHA = "773463556c05d94d79b4a9d4acad240218971f8d65a56e0e42cac60cd9a2fb4b"
V49_LIVE_SUMMARY_SHA = "e8c8cfd44bd5cfd2b40660689a4faa4a78ef3c4bf180cb2fd7c79ae9f456815e"
CANONICALIZER_ID = "renderweave-image-only-local-id-canonicalizer/1.0"
EXPECTED_DIFF = {"profileId", "pipelineVersion", "elementPromptVersion"}
FORBIDDEN_SUMMARY_MARKERS = (
    "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
    "modeloutput", "candidatejson", "rootdocument", "chain-of-thought",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def strict_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("V50_SUCCESSOR_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=strict_object_pairs
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SystemExit("V50_SUCCESSOR_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V50_SUCCESSOR_JSON_INVALID")
    return value


def sha256_bytes(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise SystemExit("V50_SUCCESSOR_REQUIRED_ARTIFACT_MISSING") from error


def canonical_profile_sha(value: dict[str, Any]) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def opaque_label(value: Any) -> str:
    if type(value) is not str or not value.strip():
        fail("V50_CANONICALIZER_LABEL_INVALID")
    return value


def declarations(values: list[Any], field: str, prefix: str) -> dict[str, str]:
    if type(values) is not list or len(values) > 32:
        fail("V50_CANONICALIZER_DECLARATION_BOUND_INVALID")
    result: dict[str, str] = {}
    for ordinal, item in enumerate(values, 1):
        if type(item) is not dict:
            fail("V50_CANONICALIZER_DECLARATION_SHAPE_INVALID")
        raw = opaque_label(item.get(field))
        if raw in result:
            fail("V50_CANONICALIZER_DECLARATION_DUPLICATE")
        result[raw] = f"{prefix}{ordinal}"
    return result


def reference(mapping: dict[str, str], value: Any) -> str:
    raw = opaque_label(value)
    if raw not in mapping:
        fail("V50_CANONICALIZER_REFERENCE_UNKNOWN")
    return mapping[raw]


def canonicalize_local_ids(source: dict[str, Any]) -> tuple[dict[str, Any], int]:
    value = copy.deepcopy(source)
    regions = value.get("regions")
    elements = value.get("elements")
    region_ids = declarations(regions, "regionId", "r")
    element_ids = declarations(elements, "elementId", "e")
    repeat_ids: dict[str, str] = {}
    for region in regions:
        raw_id = region["regionId"]
        region["regionId"] = region_ids[raw_id]
        parent = region.get("parentRegionId")
        if parent is not None:
            region["parentRegionId"] = reference(region_ids, parent)
        repeat = region.get("repeatGroupId")
        if repeat is not None:
            repeat = opaque_label(repeat)
            if repeat not in repeat_ids:
                if len(repeat_ids) >= 32:
                    fail("V50_CANONICALIZER_REPEAT_BOUND_INVALID")
                repeat_ids[repeat] = f"g{len(repeat_ids) + 1}"
            region["repeatGroupId"] = repeat_ids[repeat]
    for element in elements:
        raw_id = element["elementId"]
        element["elementId"] = element_ids[raw_id]
        region_refs = element.get("regionIds")
        if type(region_refs) is not list or any(type(item) is not str for item in region_refs):
            fail("V50_CANONICALIZER_REFERENCE_SHAPE_INVALID")
        element["regionIds"] = [reference(region_ids, item) for item in region_refs]
    return value, len(region_ids) + len(element_ids) + len(repeat_ids)


def open_authorization_count(repository: Path) -> int:
    count = 0
    for path in (repository / "plans/live-canary-authorizations").glob("20*.json"):
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise SystemExit("V50_SUCCESSOR_AUTHORIZATION_JSON_INVALID") from error
        if type(value) is dict and value.get("status") == "OPEN":
            count += 1
    return count


def source_identity(repository: Path) -> str:
    relative_paths = (
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualGroundingJsonCodec.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualObservationCorrectionPolicy.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/LiveInferenceWorker.java",
        "renderweave-inference/src/test/java/cn/hbads/renderweave/inference/live/VisualGroundingContractTest.java",
        "renderweave-app/src/test/java/cn/hbads/renderweave/inference/PostgresLiveInferenceWorkflowTest.java",
    )
    required = (
        CANONICALIZER_ID, "LOSSLESS_OPAQUE_LABELS", "putDeclaration",
        "requireReference", "requireCanonicalizableLocalIdShapes",
        "VISUAL_GROUNDING_LOCAL_ID_CANONICALIZATION_INVALID",
        "VISUAL_GROUNDING_LOCAL_ID_CLASSES_CANONICALIZED",
        '"renderweave-inference-pipeline/4.32"',
        "pipelineFourPointThirtyTwoCanonicalizesOpaqueLocalIdsWithoutARepairCall",
    )
    digest = hashlib.sha256()
    joined = ""
    for relative in relative_paths:
        try:
            source = (repository / relative).read_text(encoding="utf-8")
        except OSError as error:
            raise SystemExit("V50_SUCCESSOR_SOURCE_MISSING") from error
        joined += source
        digest.update(relative.encode("utf-8") + b"\0" + source.encode("utf-8") + b"\0")
    if any(fragment not in joined for fragment in required):
        fail("V50_SUCCESSOR_IMPLEMENTATION_CONTRACT_MISSING")
    return "renderweave-image-only-v50-implementation/1.0:" + digest.hexdigest()


def verify(repository: Path) -> dict[str, Any]:
    profile_root = repository / "renderweave-inference/src/main/resources/inference-profiles"
    prompt_root = repository / "renderweave-inference/src/main/resources/inference-prompts"
    v49 = load_json(profile_root / f"{V49_ID}.json")
    v50 = load_json(profile_root / f"{V50_ID}.json")
    if canonical_profile_sha(v49) != V49_SHA or canonical_profile_sha(v50) != V50_SHA:
        fail("V50_SUCCESSOR_PROFILE_IDENTITY_DRIFT")
    diff = {key for key in set(v49) | set(v50) if v49.get(key) != v50.get(key)}
    if diff != EXPECTED_DIFF:
        fail("V50_SUCCESSOR_PROFILE_DIFF_INVALID")
    if (v50.get("profileId") != V50_ID
            or v50.get("pipelineVersion") != "renderweave-inference-pipeline/4.32"
            or v50.get("elementPromptVersion") != "renderweave-visual-elements-prompt/16.0"
            or v50.get("certification") != "EXPERIMENTAL"):
        fail("V50_SUCCESSOR_PROFILE_FIELDS_INVALID")
    v15_path = prompt_root / "visual-elements-v15.txt"
    v16_path = prompt_root / "visual-elements-v16.txt"
    if sha256_bytes(v15_path) != V15_SHA or sha256_bytes(v16_path) != V16_SHA:
        fail("V50_SUCCESSOR_PROMPT_IDENTITY_DRIFT")
    prompt = v16_path.read_text(encoding="utf-8")
    if any(fragment not in prompt for fragment in (
        "Pipeline 4.32", "stage-local opaque", "lossless local-id canonicalization",
        "unique nonblank local-id declarations with exact references",
        "Never echo field values, coordinates, local ids, or a prior response",
    )) or "Pipeline 4.31" in prompt or "Local ids must be lowercase" in prompt:
        fail("V50_SUCCESSOR_PROMPT_CONTRACT_INVALID")

    registry = (repository / (
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/profile/"
        "InferenceProfileRegistry.java"
    )).read_text(encoding="utf-8")
    product = re.search(
        r"PRODUCT_LIVE_PROFILE_IDS\s*=\s*java\.util\.List\.of\((.*?)\);",
        registry, re.DOTALL,
    )
    candidate = re.search(
        r"CERTIFICATION_CANDIDATE_PROFILE_IDS\s*=\s*java\.util\.List\.of\((.*?)\);",
        registry, re.DOTALL,
    )
    if product is None or V50_ID in product.group(1):
        fail("V50_SUCCESSOR_PRODUCT_LIVE_EXPOSURE")
    if candidate is None or V50_ID not in candidate.group(1):
        fail("V50_SUCCESSOR_HIDDEN_CANDIDATE_MISSING")

    fixture_a = {
        "regions": [
            {"regionId": "Root A", "parentRegionId": None, "repeatGroupId": None, "kind": "ROOT"},
            {"regionId": "Rows A", "parentRegionId": "Root A", "repeatGroupId": "Group A", "kind": "REPEATED_GROUP"},
            {"regionId": "Item A", "parentRegionId": "Rows A", "repeatGroupId": "Group A", "kind": "ITEM"},
        ],
        "elements": [
            {"elementId": "Owner A", "regionIds": ["Rows A"], "kind": "GROUP"},
            {"elementId": "Slot A", "regionIds": ["Item A"], "kind": "SLOT"},
        ],
    }
    fixture_b = json.loads(json.dumps(fixture_a).replace(" A", " B"))
    canonical_a, classes_a = canonicalize_local_ids(fixture_a)
    canonical_b, classes_b = canonicalize_local_ids(fixture_b)
    if canonical_a != canonical_b or classes_a != 6 or classes_b != 6:
        fail("V50_SUCCESSOR_DIFFERENTIAL_CANONICALIZATION_INVALID")

    historical = (
        (repository / "plans/live-canary-authorizations/20260818-image-only-v49-diagnostic-432fdfeb.json", V49_AUTH_SHA),
        (repository / "plans/image-only-profile-successor-diagnostics/432fdfeb-c5ab-4cff-92f4-e066a0d98c8c-terminal.json", V49_TERMINAL_SHA),
        (repository / ".sdlc/evidence/20260818-121611-image-only-v49-successor-diagnostic-live/image-only-v49-diagnostic-live-summary.json", V49_LIVE_SUMMARY_SHA),
    )
    if any(sha256_bytes(path) != expected for path, expected in historical):
        fail("V50_SUCCESSOR_V49_TERMINAL_DRIFT")
    open_count = open_authorization_count(repository)
    if open_count != 0:
        fail("V50_SUCCESSOR_OPEN_AUTHORIZATION_FORBIDDEN")

    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "PROVIDER_ZERO_CANONICALIZATION_SUCCESSOR",
        "profileId": V50_ID,
        "profileSha256": V50_SHA,
        "promptSha256": V16_SHA,
        "pipelineVersion": "renderweave-inference-pipeline/4.32",
        "canonicalizerIdentity": CANONICALIZER_ID,
        "implementationIdentity": source_identity(repository),
        "relativeProfileDiff": sorted(EXPECTED_DIFF),
        "differentialFixtureCount": 2,
        "canonicalizedFixtureClassCount": classes_a,
        "v49ProfileSha256": V49_SHA,
        "v49PromptSha256": V15_SHA,
        "v49ClosedArtifactsReplayed": 3,
        "openAuthorizationCount": open_count,
        "hiddenExperimental": True,
        "productLive": False,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "productionDeployed": False,
        "payloadFree": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = verify(args.repository.resolve())
    encoded = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if any(marker in encoded.lower() for marker in FORBIDDEN_SUMMARY_MARKERS):
        fail("V50_SUCCESSOR_SUMMARY_NOT_PAYLOAD_FREE")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
