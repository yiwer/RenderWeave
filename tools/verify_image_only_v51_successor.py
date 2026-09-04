#!/usr/bin/env python3
"""Independent payload-free verifier for the v51 containment-provenance successor."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v51-successor-provider-zero/1.0"
V50_ID = "dashscope-qwen38-max-product-v50-hybrid-generic"
V51_ID = "dashscope-qwen38-max-product-v51-hybrid-generic"
V50_SHA = "62f333aee7096f09d6d04dea004641e8b0a9c425ee133d09a563594d81200691"
V51_SHA = "972001414977a7cc788def6e8e106b2c7f146a306d1fa328d48ff053d472d3bd"
PROMPT_SHA = "c01f11fb68d846b0fe6a71d42615dfb410d7d9945458f57b2f50748ef61451b5"
V50_AUTH_SHA = "3c2cf2d70e71766a592b8912dc9a36fe93dd929ce381f3c971f24f00de2d1329"
V50_TERMINAL_SHA = "aaec9fa058dc2caeba724af5915194c0030c9c8fe12aa8df8ea42931681b1ca5"
V50_LIVE_SUMMARY_SHA = "1ce3d3fec22a59834b49c221bdd20e04ff107ee4f195735bcc543a1dd332d2d5"
PRIMARY = "VISUAL_GROUNDING_PARENT_CONTAINMENT_CLASSIFIED"
DETAIL_CODES = (
    "VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_ZERO_COMPATIBLE",
    "VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_AMBIGUOUS_COMPATIBLE",
    "VISUAL_GROUNDING_PARENT_CONTAINMENT_NON_ITEM_ZERO_COMPATIBLE",
    "VISUAL_GROUNDING_PARENT_CONTAINMENT_NON_ITEM_AMBIGUOUS_COMPATIBLE",
    "VISUAL_GROUNDING_PARENT_CONTAINMENT_ATOMIC_ROLLBACK",
    "VISUAL_GROUNDING_PARENT_CONTAINMENT_UNCLASSIFIED",
)
EXPECTED_DIFF = {"profileId", "pipelineVersion"}
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
            fail("V51_SUCCESSOR_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=strict_object_pairs
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SystemExit("V51_SUCCESSOR_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V51_SUCCESSOR_JSON_INVALID")
    return value


def sha256_bytes(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise SystemExit("V51_SUCCESSOR_REQUIRED_ARTIFACT_MISSING") from error


def canonical_profile_sha(value: dict[str, Any]) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def canonical_containment_details(details: list[str]) -> list[str]:
    if type(details) is not list or not details or any(type(item) is not str for item in details):
        fail("V51_CONTAINMENT_DETAILS_INVALID")
    canonical = [code for code in DETAIL_CODES if code in details]
    if details != canonical:
        fail("V51_CONTAINMENT_DETAILS_NOT_CANONICAL")
    if DETAIL_CODES[4] in details and len(details) != 1:
        fail("V51_CONTAINMENT_ATOMIC_NOT_EXCLUSIVE")
    if DETAIL_CODES[5] in details and len(details) != 1:
        fail("V51_CONTAINMENT_UNCLASSIFIED_NOT_EXCLUSIVE")
    return canonical


def open_authorization_count(repository: Path) -> int:
    count = 0
    for path in (repository / "plans/live-canary-authorizations").glob("20*.json"):
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise SystemExit("V51_SUCCESSOR_AUTHORIZATION_JSON_INVALID") from error
        if type(value) is dict and value.get("status") == "OPEN":
            count += 1
    return count


def source_identity(repository: Path) -> str:
    relative_paths = (
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/replay/InferenceRejectionEnvelope.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualParentContainmentClassifier.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualGroundingJsonCodec.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualObservationCorrectionPolicy.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/LiveInferenceWorker.java",
        "renderweave-app/src/main/resources/db/migration/V021__parent_containment_rejection_envelope.sql",
        "renderweave-inference/src/test/java/cn/hbads/renderweave/inference/live/VisualParentContainmentClassifierTest.java",
        "renderweave-app/src/test/java/cn/hbads/renderweave/inference/PostgresLiveInferenceWorkflowTest.java",
    )
    required = (
        PRIMARY, *DETAIL_CODES, "renderweave-inference-pipeline/4.33",
        "MIXED_PROVENANCE_AND_CONTAINMENT",
        "pipelineFourPointThirtyThreeClassifiesContainmentAndStopsBeforeSecondPermit",
    )
    digest = hashlib.sha256()
    joined = ""
    for relative in relative_paths:
        try:
            source = (repository / relative).read_text(encoding="utf-8")
        except OSError as error:
            raise SystemExit("V51_SUCCESSOR_SOURCE_MISSING") from error
        joined += source
        digest.update(relative.encode("utf-8") + b"\0" + source.encode("utf-8") + b"\0")
    if any(fragment not in joined for fragment in required):
        fail("V51_SUCCESSOR_IMPLEMENTATION_CONTRACT_MISSING")
    return "renderweave-image-only-v51-implementation/1.0:" + digest.hexdigest()


def verify(repository: Path) -> dict[str, Any]:
    profile_root = repository / "renderweave-inference/src/main/resources/inference-profiles"
    v50 = load_json(profile_root / f"{V50_ID}.json")
    v51 = load_json(profile_root / f"{V51_ID}.json")
    if canonical_profile_sha(v50) != V50_SHA or canonical_profile_sha(v51) != V51_SHA:
        fail("V51_SUCCESSOR_PROFILE_IDENTITY_DRIFT")
    diff = {key for key in set(v50) | set(v51) if v50.get(key) != v51.get(key)}
    if diff != EXPECTED_DIFF:
        fail("V51_SUCCESSOR_PROFILE_DIFF_INVALID")
    if (v51.get("profileId") != V51_ID
            or v51.get("pipelineVersion") != "renderweave-inference-pipeline/4.33"
            or v51.get("elementPromptVersion") != "renderweave-visual-elements-prompt/16.0"
            or v51.get("certification") != "EXPERIMENTAL"):
        fail("V51_SUCCESSOR_PROFILE_FIELDS_INVALID")
    prompt = repository / (
        "renderweave-inference/src/main/resources/inference-prompts/visual-elements-v16.txt"
    )
    if sha256_bytes(prompt) != PROMPT_SHA:
        fail("V51_SUCCESSOR_PROMPT_IDENTITY_DRIFT")

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
    if product is None or V51_ID in product.group(1):
        fail("V51_SUCCESSOR_PRODUCT_LIVE_EXPOSURE")
    if candidate is None or V51_ID not in candidate.group(1):
        fail("V51_SUCCESSOR_HIDDEN_CANDIDATE_MISSING")

    canonical_containment_details([DETAIL_CODES[0], DETAIL_CODES[3]])
    canonical_containment_details([DETAIL_CODES[4]])
    canonical_containment_details([DETAIL_CODES[5]])

    historical = (
        (repository / "plans/live-canary-authorizations/20260818-image-only-v50-diagnostic-82f1d86b.json", V50_AUTH_SHA),
        (repository / "plans/image-only-profile-successor-diagnostics/82f1d86b-065b-4357-924e-19945daf1077-terminal.json", V50_TERMINAL_SHA),
        (repository / ".sdlc/evidence/20260818-132607-image-only-v50-successor-diagnostic-live/image-only-v50-diagnostic-live-summary.json", V50_LIVE_SUMMARY_SHA),
    )
    if any(sha256_bytes(path) != expected for path, expected in historical):
        fail("V51_SUCCESSOR_V50_TERMINAL_DRIFT")
    open_count = open_authorization_count(repository)
    if open_count != 0:
        fail("V51_SUCCESSOR_OPEN_AUTHORIZATION_FORBIDDEN")

    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "PROVIDER_ZERO_PARENT_CONTAINMENT_PROVENANCE_SUCCESSOR",
        "profileId": V51_ID,
        "profileSha256": V51_SHA,
        "promptSha256": PROMPT_SHA,
        "pipelineVersion": "renderweave-inference-pipeline/4.33",
        "primaryCode": PRIMARY,
        "detailCodeCount": len(DETAIL_CODES),
        "implementationIdentity": source_identity(repository),
        "relativeProfileDiff": sorted(EXPECTED_DIFF),
        "v50ClosedArtifactsReplayed": 3,
        "openAuthorizationCount": open_count,
        "firstClassifiedRejectionTerminal": True,
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
        fail("V51_SUCCESSOR_SUMMARY_NOT_PAYLOAD_FREE")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
