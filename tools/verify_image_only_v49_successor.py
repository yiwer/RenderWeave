#!/usr/bin/env python3
"""Independent, payload-free verifier for the immutable IMAGE_ONLY v49 successor."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v49-successor-provider-zero/1.0"
V46_ID = "dashscope-qwen38-max-product-v46-hybrid-generic"
V46_SHA = "22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c"
V47_ID = "dashscope-qwen38-max-product-v47-hybrid-generic"
V47_SHA = "a9fe98e1cfa4b7cc126db1f74601fdebe60526a1c999924daf189ed5f1ac5eb0"
V48_ID = "dashscope-qwen38-max-product-v48-hybrid-generic"
V48_SHA = "22f40ef4c865e11778eef4558c20c383e6611e068d8d08be0d080650074d4470"
V49_ID = "dashscope-qwen38-max-product-v49-hybrid-generic"
V49_SHA = "acffdd4dd56ca2f1f7260fc5d37aa48ca3da488a0ae2718f2095bf1530e86eaf"
V14_PROMPT_SHA = "ab722901853eb34e8ceef34475fd04f80070cf1e8b9561d9f3496e9466844e75"
V15_PROMPT_SHA = "107edf6a5a2abf31e718fdc8245b640ec251a5dab9a496f502e38bbf396ceacf"
ALLOWED_DIFF = {"profileId", "pipelineVersion", "elementPromptVersion"}
FORBIDDEN_SUMMARY_MARKERS = (
    "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
    "modeloutput", "candidatejson", "rootdocument", "chain-of-thought",
)


def load_historical_verifier() -> Any:
    path = Path(__file__).with_name("verify_image_only_v48_successor.py")
    spec = importlib.util.spec_from_file_location("v48_successor_history", path)
    if spec is None or spec.loader is None:
        raise SystemExit("V49_SUCCESSOR_HISTORICAL_VERIFIER_MISSING")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fail(code: str) -> None:
    raise SystemExit(code)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as error:
        raise SystemExit("V49_SUCCESSOR_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V49_SUCCESSOR_JSON_INVALID")
    return value


def canonical_profile_sha(value: dict[str, Any]) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def exact_profile_diff(left: dict[str, Any], right: dict[str, Any]) -> set[str]:
    if list(left) != list(right):
        fail("V49_SUCCESSOR_PROFILE_FIELD_ORDER_DRIFT")
    return {key for key in left if left[key] != right[key]}


def open_authorization_count(repository: Path) -> int:
    count = 0
    for path in (repository / "plans/live-canary-authorizations").glob("20*.json"):
        if load_json(path).get("status") == "OPEN":
            count += 1
    return count


def require_historical_immutability(repository: Path) -> dict[str, str]:
    historical = load_historical_verifier()
    historical.require_exact_profile_diff(repository)
    historical.require_prompt_contract(repository)
    historical.require_old_terminal_immutable(repository)
    historical.require_v47_diagnostic_immutable(repository)
    v48_terminal = historical.require_v48_diagnostic_immutable(repository)
    historical.require_no_open_authorization(repository)
    profile_root = repository / "renderweave-inference/src/main/resources/inference-profiles"
    expected = {V46_ID: V46_SHA, V47_ID: V47_SHA, V48_ID: V48_SHA}
    for profile_id, expected_sha in expected.items():
        if canonical_profile_sha(load_json(profile_root / f"{profile_id}.json")) != expected_sha:
            fail("V49_SUCCESSOR_HISTORICAL_PROFILE_DRIFT")
    if v48_terminal.get("terminalSha256") != historical.V48_DIAGNOSTIC_TERMINAL_SHA:
        fail("V49_SUCCESSOR_V48_TERMINAL_REPLAY_DRIFT")
    return {
        "v46CanaryTerminalSha256": historical.OLD_TERMINAL_FILE_SHA,
        "v47DiagnosticTerminalSha256": historical.V47_DIAGNOSTIC_TERMINAL_SHA,
        "v47ClosedAuthorizationSha256": historical.V47_DIAGNOSTIC_AUTHORIZATION_SHA,
        "v48DiagnosticTerminalSha256": historical.V48_DIAGNOSTIC_TERMINAL_SHA,
        "v48ClosedAuthorizationSha256": historical.V48_DIAGNOSTIC_AUTHORIZATION_SHA,
        "v48LiveSummarySha256": v48_terminal["liveSummarySha256"],
    }


def require_v49_profile(repository: Path) -> None:
    root = repository / "renderweave-inference/src/main/resources/inference-profiles"
    v48 = load_json(root / f"{V48_ID}.json")
    v49 = load_json(root / f"{V49_ID}.json")
    if exact_profile_diff(v48, v49) != ALLOWED_DIFF:
        fail("V49_SUCCESSOR_PROFILE_DIFF_INVALID")
    if (
        v49.get("profileId") != V49_ID
        or v49.get("pipelineVersion") != "renderweave-inference-pipeline/4.31"
        or v49.get("elementPromptVersion") != "renderweave-visual-elements-prompt/15.0"
        or v49.get("certification") != "EXPERIMENTAL"
        or canonical_profile_sha(v48) != V48_SHA
        or canonical_profile_sha(v49) != V49_SHA
    ):
        fail("V49_SUCCESSOR_PROFILE_IDENTITY_DRIFT")


def require_v49_prompt(repository: Path) -> None:
    root = repository / "renderweave-inference/src/main/resources/inference-prompts"
    v14_bytes = (root / "visual-elements-v14.txt").read_bytes()
    v15_bytes = (root / "visual-elements-v15.txt").read_bytes()
    if hashlib.sha256(v14_bytes).hexdigest() != V14_PROMPT_SHA:
        fail("V49_SUCCESSOR_V14_PROMPT_DRIFT")
    if hashlib.sha256(v15_bytes).hexdigest() != V15_PROMPT_SHA:
        fail("V49_SUCCESSOR_V15_PROMPT_DRIFT")
    prompt = v15_bytes.decode("utf-8")
    required = (
        "VISUAL_GROUNDING_REGION_FIELDS_INVALID",
        "VISUAL_GROUNDING_REGION_UNCLASSIFIED",
        "canonical detail set",
        "Correct every listed detail code together",
        "Never echo field values, coordinates, local ids, or a prior response",
        "VISUAL_GROUNDING_REGION_ENTRY_INVALID",
        "VISUAL_GROUNDING_REGION_ID_INVALID",
        "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
        "VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID",
        "VISUAL_GROUNDING_REGION_READING_ORDER_INVALID",
        "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID",
        "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID",
    )
    if any(fragment not in prompt for fragment in required):
        fail("V49_SUCCESSOR_PROMPT_CONTRACT_DRIFT")
    if re.search(r"(?i)\b(bus|station|route|stop|fare)\b", prompt):
        fail("V49_SUCCESSOR_PROMPT_DOMAIN_LEAK")


def require_registry_contract(repository: Path) -> str:
    paths = (
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/profile/InferenceProfileRegistry.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/profile/InferencePromptRegistry.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/profile/InferenceProfile.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/profile/VisualModelCapability.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/provider/ProfileRunBudgetPolicy.java",
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualObservationCorrectionPolicy.java",
    )
    sources = [(path, (repository / path).read_text(encoding="utf-8")) for path in paths]
    combined = "\n".join(source for _, source in sources)
    required = (
        V49_ID, "VISUAL_ELEMENTS_V15", "visual-elements-v15.txt", "productPromptV49",
        '"renderweave-inference-pipeline/4.31"', "IMAGE_ONLY_V49_PROFILE_ID",
        "MIXED_RETRYABLE_DETAIL_CODES",
    )
    if any(fragment not in combined for fragment in required):
        fail("V49_SUCCESSOR_REGISTRY_CONTRACT_MISSING")
    registry = sources[0][1]
    product_block = re.search(
        r"PRODUCT_LIVE_PROFILE_IDS\s*=\s*java\.util\.List\.of\((.*?)\);",
        registry, re.DOTALL,
    )
    candidate_block = re.search(
        r"CERTIFICATION_CANDIDATE_PROFILE_IDS\s*=\s*java\.util\.List\.of\((.*?)\);",
        registry, re.DOTALL,
    )
    if product_block is None or V49_ID in product_block.group(1):
        fail("V49_SUCCESSOR_PRODUCT_LIVE_EXPOSURE")
    if candidate_block is None or V49_ID not in candidate_block.group(1):
        fail("V49_SUCCESSOR_HIDDEN_CANDIDATE_MISSING")
    digest = hashlib.sha256()
    for path, source in sources:
        digest.update(path.encode("utf-8"))
        digest.update(b"\0")
        digest.update(source.encode("utf-8"))
        digest.update(b"\0")
    return "renderweave-image-only-v49-registry-implementation/1.0:" + digest.hexdigest()


def verify(repository: Path) -> dict[str, Any]:
    historical = require_historical_immutability(repository)
    require_v49_profile(repository)
    require_v49_prompt(repository)
    registry_identity = require_registry_contract(repository)
    open_count = open_authorization_count(repository)
    if open_count != 0:
        fail("V49_SUCCESSOR_OPEN_AUTHORIZATION_FORBIDDEN")
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "PROVIDER_ZERO_IMMUTABLE_SUCCESSOR",
        "profileId": V49_ID,
        "profileSha256": V49_SHA,
        "pipelineVersion": "renderweave-inference-pipeline/4.31",
        "elementPromptVersion": "renderweave-visual-elements-prompt/15.0",
        "elementPromptSha256": V15_PROMPT_SHA,
        "changedProfileFields": sorted(ALLOWED_DIFF),
        "registryImplementationIdentity": registry_identity,
        "historicalDigests": historical,
        "hidden": True,
        "experimental": True,
        "certificationGranted": False,
        "productLive": False,
        "openAuthorizationCount": open_count,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "candidateApplied": False,
        "staticSchemaPublished": False,
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
        fail("V49_SUCCESSOR_SUMMARY_NOT_PAYLOAD_FREE")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
