#!/usr/bin/env python3
"""Independent, payload-free verifier for the IMAGE_ONLY v49 rejection envelope."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v49-envelope-provider-zero/1.0"
MIXED_PRIMARY = "VISUAL_GROUNDING_REGION_FIELDS_INVALID"
UNCLASSIFIED_PRIMARY = "VISUAL_GROUNDING_REGION_UNCLASSIFIED"
CLOSED_DETAILS = [
    "VISUAL_GROUNDING_REGION_ENTRY_INVALID",
    "VISUAL_GROUNDING_REGION_ID_INVALID",
    "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
    "VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID",
    "VISUAL_GROUNDING_REGION_READING_ORDER_INVALID",
    "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID",
    "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID",
]
V48_PROFILE_SHA = "22f40ef4c865e11778eef4558c20c383e6611e068d8d08be0d080650074d4470"
FORBIDDEN_SUMMARY_MARKERS = (
    "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
    "modeloutput", "candidatejson", "rootdocument", "chain-of-thought",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def canonical_envelope(primary: str, stage: str, details: list[str]) -> dict[str, Any]:
    if stage != "OBSERVE" or type(details) is not list or any(type(code) is not str for code in details):
        fail("V49_ENVELOPE_SHAPE_INVALID")
    if primary == MIXED_PRIMARY:
        canonical = [code for code in CLOSED_DETAILS if code in details]
        if len(details) < 2 or len(details) > 7 or details != canonical:
            fail("V49_ENVELOPE_MIXED_DETAILS_INVALID")
    elif primary == UNCLASSIFIED_PRIMARY:
        if details:
            fail("V49_ENVELOPE_UNCLASSIFIED_DETAILS_INVALID")
    else:
        fail("V49_ENVELOPE_PRIMARY_INVALID")
    return {
        "primaryCode": primary,
        "earliestStage": stage,
        "detailCodes": details,
        "detailCodeCount": len(details),
    }


def read_required(repository: Path, relative: str) -> str:
    path = repository / relative
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        raise SystemExit("V49_ENVELOPE_REQUIRED_SOURCE_MISSING") from error


def require_fragments(source: str, fragments: tuple[str, ...], code: str) -> None:
    if any(fragment not in source for fragment in fragments):
        fail(code)


def canonical_profile_sha(path: Path) -> str:
    value = json.loads(path.read_text(encoding="utf-8"))
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def open_authorization_count(repository: Path) -> int:
    count = 0
    root = repository / "plans/live-canary-authorizations"
    for path in root.glob("20*.json"):
        value = json.loads(path.read_text(encoding="utf-8"))
        if value.get("status") == "OPEN":
            count += 1
    return count


def verify_sources(repository: Path) -> str:
    relative_paths = {
        "envelope": "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/replay/InferenceRejectionEnvelope.java",
        "codec": "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/replay/InferenceRejectionEnvelopeJsonCodec.java",
        "attempt": "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/replay/InferenceAttempt.java",
        "worker": "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/LiveInferenceWorker.java",
        "policy": "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/VisualObservationCorrectionPolicy.java",
        "store": "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresInferenceRunStore.java",
        "migration": "renderweave-app/src/main/resources/db/migration/V020__bounded_inference_rejection_envelope.sql",
        "controller": "renderweave-app/src/main/java/cn/hbads/renderweave/inference/InferenceController.java",
        "openapi": "openapi/renderweave-v1.yaml",
        "web": "web/src/features/inference/InferenceExecutionLogPanel.tsx",
    }
    sources = {name: read_required(repository, path) for name, path in relative_paths.items()}
    detail_block = re.search(
        r"REGION_DETAIL_CODES\s*=\s*List\.of\((?P<body>.*?)\);",
        sources["envelope"], re.DOTALL,
    )
    if detail_block is None or re.findall(r'"([A-Z0-9_]+)"', detail_block.group("body")) != CLOSED_DETAILS:
        fail("V49_ENVELOPE_CLOSED_ENUM_DRIFT")
    require_fragments(sources["envelope"], (
        MIXED_PRIMARY, UNCLASSIFIED_PRIMARY, "detailCodes.size() < 2",
        "detailCodes.equals(REGION_DETAIL_CODES.stream()", "earliestStage != InferenceStage.OBSERVE",
    ), "V49_ENVELOPE_DOMAIN_CONTRACT_MISSING")
    require_fragments(sources["codec"], (
        "STRICT_DUPLICATE_DETECTION", "FAIL_ON_UNKNOWN_PROPERTIES",
        "FAIL_ON_MISSING_CREATOR_PROPERTIES", "FAIL_ON_NULL_FOR_PRIMITIVES", "MAX_BYTES = 4 * 1024",
    ), "V49_ENVELOPE_CODEC_NOT_STRICT")
    require_fragments(sources["attempt"], (
        "Optional<InferenceRejectionEnvelope> rejectionEnvelope", "LIVE_VISUAL_ANALYSIS_REJECTED",
        "envelope.detailCodeCounts()",
    ), "V49_ENVELOPE_ATTEMPT_CONTRACT_MISSING")
    require_fragments(sources["worker"], (
        "persistedTerminalRejection", "rejectionEnvelope", "::detailCodeCounts",
        "workflowStore.recordAttempt", "invalid.diagnosticCode()", "MIXED_REGION_RECOVERY_HYBRID_VISUAL_PIPELINE",
    ), "V49_ENVELOPE_WORKER_CONTRACT_MISSING")
    require_fragments(sources["policy"], (
        '"renderweave-inference-pipeline/4.31"', MIXED_PRIMARY, UNCLASSIFIED_PRIMARY, "return false",
    ), "V49_ENVELOPE_FAIL_CLOSED_POLICY_MISSING")
    require_fragments(sources["store"], (
        "rejection_envelope", "REJECTION_ENVELOPE_CODEC::write", "REJECTION_ENVELOPE_CODEC::parse",
    ), "V49_ENVELOPE_STORE_CONTRACT_MISSING")
    require_fragments(sources["migration"], (
        "renderweave_valid_inference_rejection_envelope", "jsonb_object_keys",
        "detailCodeCount", "details <> canonical", "status = 'REJECTED'", "stage = 'OBSERVE'",
    ), "V49_ENVELOPE_DATABASE_CONSTRAINT_MISSING")
    require_fragments(sources["controller"] + sources["openapi"] + sources["web"], (
        "rejectionEnvelope", "primaryCode", "earliestStage", "detailCodes", "detailCodeCount",
    ), "V49_ENVELOPE_QUERY_PROJECTION_MISSING")

    digest = hashlib.sha256()
    for name in sorted(relative_paths):
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(sources[name].encode("utf-8"))
        digest.update(b"\0")
    return "renderweave-image-only-v49-envelope-implementation/1.0:" + digest.hexdigest()


def verify(repository: Path) -> dict[str, Any]:
    implementation_identity = verify_sources(repository)
    v48_path = repository / (
        "renderweave-inference/src/main/resources/inference-profiles/"
        "dashscope-qwen38-max-product-v48-hybrid-generic.json"
    )
    if canonical_profile_sha(v48_path) != V48_PROFILE_SHA:
        fail("V49_ENVELOPE_V48_PROFILE_DRIFT")
    v49_profile = repository / (
        "renderweave-inference/src/main/resources/inference-profiles/"
        "dashscope-qwen38-max-product-v49-hybrid-generic.json"
    )
    v49_prompt = repository / "renderweave-inference/src/main/resources/inference-prompts/visual-elements-v15.txt"
    open_count = open_authorization_count(repository)
    if v49_profile.exists() or v49_prompt.exists() or open_count != 0:
        fail("V49_ENVELOPE_PROVIDER_ZERO_BOUNDARY_VIOLATED")
    canonical_envelope(MIXED_PRIMARY, "OBSERVE", CLOSED_DETAILS[:2])
    canonical_envelope(UNCLASSIFIED_PRIMARY, "OBSERVE", [])
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "PROVIDER_ZERO_ENVELOPE",
        "implementationIdentity": implementation_identity,
        "primaryCodes": [MIXED_PRIMARY, UNCLASSIFIED_PRIMARY],
        "closedDetailCodes": CLOSED_DETAILS,
        "v48ProfileSha256": V48_PROFILE_SHA,
        "v49ProfileCreated": False,
        "v49PromptCreated": False,
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
        fail("V49_ENVELOPE_SUMMARY_NOT_PAYLOAD_FREE")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
