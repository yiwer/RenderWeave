#!/usr/bin/env python3
"""Independent payload-free verifier for the v52 item-parent envelope successor."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v52-successor-provider-zero/1.0"
V51_ID = "dashscope-qwen38-max-product-v51-hybrid-generic"
V52_ID = "dashscope-qwen38-max-product-v52-hybrid-generic"
V51_SHA = "972001414977a7cc788def6e8e106b2c7f146a306d1fa328d48ff053d472d3bd"
V52_SHA = "d8014b605dfa01a5aa1e6062696c61eb896da9e146b2a6ab3c5dae3ca9957332"
PROMPT_SHA = "c01f11fb68d846b0fe6a71d42615dfb410d7d9945458f57b2f50748ef61451b5"
V51_AUTH_SHA = "3ba6ef75552f6b259d4ec271b6f2b0c9558a03a94c7d0c8478f074bf1cbab483"
V51_TERMINAL_SHA = "1e9825a4f3feb59972d03671c1952e934de915d281759403e933f3cbb75075bb"
V51_LIVE_SUMMARY_SHA = "102fb5a99fe8ebf313fef77c1834ee33e81d6cbad8aebd915cf37c079ae602f1"
V51_POSTCLOSE_SHA = "598c02e3897d67375abecc39b04674ea80a8595c6758db3f4a0d4da4cdac406b"
EXPECTED_DIFF = {"profileId", "pipelineVersion"}
MAX_ENVELOPES = 8
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
            fail("V52_SUCCESSOR_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=strict_object_pairs
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SystemExit("V52_SUCCESSOR_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V52_SUCCESSOR_JSON_INVALID")
    return value


def sha256_bytes(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise SystemExit("V52_SUCCESSOR_REQUIRED_ARTIFACT_MISSING") from error


def canonical_profile_sha(value: dict[str, Any]) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def open_authorization_count(repository: Path) -> int:
    count = 0
    for path in (repository / "plans/live-canary-authorizations").glob("20*.json"):
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise SystemExit("V52_SUCCESSOR_AUTHORIZATION_JSON_INVALID") from error
        if type(value) is not dict:
            fail("V52_SUCCESSOR_AUTHORIZATION_JSON_INVALID")
        if value.get("status") == "OPEN":
            count += 1
    return count


def source_identity(repository: Path) -> str:
    relative_paths = (
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualGroundingJsonCodec.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualObservationCorrectionPolicy.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/LiveInferenceWorker.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/provider/ProfileRunBudgetPolicy.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/profile/InferenceProfile.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/profile/InferenceProfileRegistry.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/profile/VisualModelCapability.java",
        "renderweave-inference/src/test/java/cn/hbads/renderweave/inference/live/VisualGroundingContractTest.java",
        "renderweave-inference/src/test/java/cn/hbads/renderweave/inference/live/VisualObservationCorrectionPolicyTest.java",
        "renderweave-inference/src/test/java/cn/hbads/renderweave/inference/certification/ImageOnlyV52ProfileTest.java",
        "renderweave-inference/src/test/java/cn/hbads/renderweave/inference/profile/InferenceProfileRegistryTest.java",
        "renderweave-app/src/test/java/cn/hbads/renderweave/inference/PostgresLiveInferenceWorkflowTest.java",
    )
    required = (
        "renderweave-inference-pipeline/4.34",
        "MAX_NORMALIZED_REPEATED_GROUP_ENVELOPES = 8",
        "normalizeRepeatedGroupItemParentEnvelopes",
        "VISUAL_GROUNDING_REPEATED_GROUP_ENVELOPE_NORMALIZED",
        "ITEM_PARENT_ENVELOPE_NORMALIZATION_PIPELINE_VERSION",
        "v52RejectsNineEnvelopeRewritesWithoutPartialCommit",
        "pipelineFourPointThirtyFourNormalizesExactItemParentEnvelopeBeforeSecondPermit",
        "class ImageOnlyV52ProfileTest",
    )
    digest = hashlib.sha256()
    joined = ""
    for relative in relative_paths:
        try:
            source = (repository / relative).read_text(encoding="utf-8")
        except OSError as error:
            raise SystemExit("V52_SUCCESSOR_SOURCE_MISSING") from error
        joined += source
        digest.update(relative.encode("utf-8") + b"\0" + source.encode("utf-8") + b"\0")
    if any(fragment not in joined for fragment in required):
        fail("V52_SUCCESSOR_IMPLEMENTATION_CONTRACT_MISSING")
    return "renderweave-image-only-v52-implementation/1.0:" + digest.hexdigest()


def verify(repository: Path) -> dict[str, Any]:
    profile_root = repository / "renderweave-inference/src/main/resources/inference-profiles"
    v51 = load_json(profile_root / f"{V51_ID}.json")
    v52 = load_json(profile_root / f"{V52_ID}.json")
    if canonical_profile_sha(v51) != V51_SHA or canonical_profile_sha(v52) != V52_SHA:
        fail("V52_SUCCESSOR_PROFILE_IDENTITY_DRIFT")
    diff = {key for key in set(v51) | set(v52) if v51.get(key) != v52.get(key)}
    if diff != EXPECTED_DIFF:
        fail("V52_SUCCESSOR_PROFILE_DIFF_INVALID")
    if (v52.get("profileId") != V52_ID
            or v52.get("pipelineVersion") != "renderweave-inference-pipeline/4.34"
            or v52.get("elementPromptVersion") != "renderweave-visual-elements-prompt/16.0"
            or v52.get("certification") != "EXPERIMENTAL"):
        fail("V52_SUCCESSOR_PROFILE_FIELDS_INVALID")
    prompt = repository / (
        "renderweave-inference/src/main/resources/inference-prompts/visual-elements-v16.txt"
    )
    if sha256_bytes(prompt) != PROMPT_SHA:
        fail("V52_SUCCESSOR_PROMPT_IDENTITY_DRIFT")

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
    if product is None or V52_ID in product.group(1):
        fail("V52_SUCCESSOR_PRODUCT_LIVE_EXPOSURE")
    if candidate is None or V52_ID not in candidate.group(1):
        fail("V52_SUCCESSOR_HIDDEN_CANDIDATE_MISSING")

    historical = (
        (repository / "plans/live-canary-authorizations/20260818-image-only-v51-diagnostic-7d929b74.json", V51_AUTH_SHA),
        (repository / "plans/image-only-profile-successor-diagnostics/7d929b74-47ca-40a7-bfd5-061e070c2bd2-terminal.json", V51_TERMINAL_SHA),
        (repository / ".sdlc/evidence/20260818-143933-image-only-v51-successor-diagnostic-live/image-only-v51-diagnostic-live-summary.json", V51_LIVE_SUMMARY_SHA),
        (repository / ".sdlc/evidence/20260818-144607-image-only-v51-diagnostic-postclose/image-only-v51-diagnostic-postclose-summary.json", V51_POSTCLOSE_SHA),
    )
    if any(sha256_bytes(path) != expected for path, expected in historical):
        fail("V52_SUCCESSOR_V51_TERMINAL_DRIFT")
    open_count = open_authorization_count(repository)
    if open_count != 0:
        fail("V52_SUCCESSOR_OPEN_AUTHORIZATION_FORBIDDEN")

    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "PROVIDER_ZERO_ITEM_PARENT_ENVELOPE_NORMALIZATION_SUCCESSOR",
        "profileId": V52_ID,
        "profileSha256": V52_SHA,
        "promptSha256": PROMPT_SHA,
        "pipelineVersion": "renderweave-inference-pipeline/4.34",
        "implementationIdentity": source_identity(repository),
        "relativeProfileDiff": sorted(EXPECTED_DIFF),
        "v51ClosedArtifactsReplayed": len(historical),
        "maximumNormalizedRepeatedGroupEnvelopes": MAX_ENVELOPES,
        "directItemChildrenOnly": True,
        "noPadding": True,
        "atomicRollback": True,
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
        fail("V52_SUCCESSOR_SUMMARY_NOT_PAYLOAD_FREE")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
