#!/usr/bin/env python3
"""Independent, payload-free verifier for IMAGE_ONLY v49 fallback provenance."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-v49-successor-provider-zero/1.0"
MATRIX_VERSION = "renderweave-image-only-v49-region-fallback-provenance/1.0"
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
EXPECTED_FIXTURE_IDS = {
    "valid-region-collection",
    "single-entry",
    "single-id",
    "single-parent-id",
    "single-multiplicity",
    "single-reading-order",
    "single-repeat-group-id",
    "single-evidence-view-conversion",
    "mixed-id-parent-id",
    "mixed-order-and-duplicate-stability",
    "mixed-all-closed-detail-codes",
    "unclassified-region-collection",
    "unclassified-constructor-or-runtime",
}
PROFILE_HASHES = {
    "dashscope-qwen38-max-product-v46-hybrid-generic":
        "22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c",
    "dashscope-qwen38-max-product-v47-hybrid-generic":
        "a9fe98e1cfa4b7cc126db1f74601fdebe60526a1c999924daf189ed5f1ac5eb0",
    "dashscope-qwen38-max-product-v48-hybrid-generic":
        "22f40ef4c865e11778eef4558c20c383e6611e068d8d08be0d080650074d4470",
}
V46_TERMINAL_SHA = "059c8a3fe38c00e1ce4a76d4c8896fa41eff976378386e21b32297a726e9a197"
V47_TERMINAL_SHA = "5aad42165c1dd595d02e99c7c22c3c50cd2aeda83d08f6716cb8bd2081f7a664"
V47_AUTHORIZATION_SHA = "3cad67afba29007bd00a3aaebd536f10c50dde4df04d425f09251a59bf126be3"
V48_TERMINAL_SHA = "316029ebdf55bb5cb1dabe193f4f44b2b87dc971cd145e564d1b0c3006df811c"
V48_AUTHORIZATION_SHA = "6f102c53c6192fea00ef02f1a72256f85f73c0a6abb8faee67bf80100118437b"
V48_LIVE_SUMMARY_SHA = "ee1cdee1506ae0301aa7985b26c8728819f2f9d42a3606ee76e208ccb3f80ae1"
LEGACY_DUPLICATE_STATUS_AUTHORIZATION = "20260817-deepseek-ocr-spike.json"
LEGACY_DUPLICATE_STATUS_AUTHORIZATION_SHA = (
    "f061fd6d6a7065756d55da20a63bb28d7bf0156d787f3b859559ba8ea95b7d5f"
)
FORBIDDEN_PAYLOAD_MARKERS = (
    "f:\\", "data:image", "base64", "providerrequest", "providerresponse",
    "modeloutput", "candidatejson", "rootdocument", "chain-of-thought",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def _pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in values:
        if key in result:
            fail("V49_PROVENANCE_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_pairs)
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit("V49_PROVENANCE_JSON_INVALID") from error
    if type(value) is not dict:
        fail("V49_PROVENANCE_JSON_INVALID")
    return value


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_profile_sha(value: dict[str, Any]) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return sha256_bytes(encoded)


def require_exact_keys(value: dict[str, Any], expected: set[str], code: str) -> None:
    if set(value) != expected:
        fail(code)


def expected_classification(
        mode: str,
        region_failure_codes: list[list[str]],
) -> tuple[str, str | None, list[str], int]:
    if mode in {"UNCLASSIFIED_COLLECTION", "UNCLASSIFIED_VALIDATOR_EXCEPTION"}:
        return "UNCLASSIFIED", UNCLASSIFIED_PRIMARY, [], 0
    if mode != "KNOWN":
        fail("V49_PROVENANCE_FIXTURE_MODE_INVALID")
    observed: set[str] = set()
    for region in region_failure_codes:
        if type(region) is not list or any(type(code) is not str for code in region):
            fail("V49_PROVENANCE_FIXTURE_REGION_INVALID")
        if CLOSED_DETAILS[0] in region and region != [CLOSED_DETAILS[0]]:
            fail("V49_PROVENANCE_ENTRY_FIXTURE_INVALID")
        for code in region:
            if code not in CLOSED_DETAILS:
                fail("V49_PROVENANCE_DETAIL_ENUM_INVALID")
            observed.add(code)
    ordered = [code for code in CLOSED_DETAILS if code in observed]
    if not ordered:
        return "VALID", None, [], 0
    if len(ordered) == 1:
        return "KNOWN_SINGLE", ordered[0], [], 1
    return "KNOWN_MIXED", MIXED_PRIMARY, ordered, len(ordered)


def verify_matrix(path: Path) -> dict[str, Any]:
    raw = path.read_text(encoding="utf-8")
    lowered = raw.lower()
    if any(marker in lowered for marker in FORBIDDEN_PAYLOAD_MARKERS):
        fail("V49_PROVENANCE_MATRIX_NOT_PAYLOAD_FREE")
    matrix = load_json(path)
    require_exact_keys(matrix, {"version", "closedDetailCodes", "fixtures"},
                       "V49_PROVENANCE_MATRIX_KEYS_INVALID")
    if matrix["version"] != MATRIX_VERSION or matrix["closedDetailCodes"] != CLOSED_DETAILS:
        fail("V49_PROVENANCE_MATRIX_IDENTITY_INVALID")
    fixtures = matrix["fixtures"]
    if type(fixtures) is not list:
        fail("V49_PROVENANCE_FIXTURES_INVALID")
    fixture_ids: set[str] = set()
    single_codes: set[str] = set()
    mixed_count = 0
    unclassified_count = 0
    for fixture in fixtures:
        if type(fixture) is not dict:
            fail("V49_PROVENANCE_FIXTURE_INVALID")
        require_exact_keys(fixture, {
            "fixtureId", "mode", "regionFailureCodes", "expectedDisposition",
            "expectedPrimaryCode", "expectedDetailCodes",
            "expectedKnownFieldFamilyCount",
        }, "V49_PROVENANCE_FIXTURE_KEYS_INVALID")
        fixture_id = fixture["fixtureId"]
        if type(fixture_id) is not str or fixture_id in fixture_ids:
            fail("V49_PROVENANCE_FIXTURE_ID_INVALID")
        fixture_ids.add(fixture_id)
        regions = fixture["regionFailureCodes"]
        if type(regions) is not list:
            fail("V49_PROVENANCE_FIXTURE_REGIONS_INVALID")
        expected = expected_classification(fixture["mode"], regions)
        actual = (
            fixture["expectedDisposition"], fixture["expectedPrimaryCode"],
            fixture["expectedDetailCodes"], fixture["expectedKnownFieldFamilyCount"],
        )
        if actual != expected:
            fail("V49_PROVENANCE_FIXTURE_EXPECTATION_INVALID")
        disposition, primary, details, _ = expected
        if disposition == "KNOWN_SINGLE":
            single_codes.add(primary)
        elif disposition == "KNOWN_MIXED":
            mixed_count += 1
            if details != [code for code in CLOSED_DETAILS if code in details]:
                fail("V49_PROVENANCE_DETAIL_ORDER_INVALID")
        elif disposition == "UNCLASSIFIED":
            unclassified_count += 1
    if fixture_ids != EXPECTED_FIXTURE_IDS or single_codes != set(CLOSED_DETAILS):
        fail("V49_PROVENANCE_TAXONOMY_COVERAGE_INVALID")
    all_codes = next(
        value for value in fixtures
        if value["fixtureId"] == "mixed-all-closed-detail-codes"
    )
    if all_codes["expectedDetailCodes"] != CLOSED_DETAILS:
        fail("V49_PROVENANCE_CLOSED_ENUM_INCOMPLETE")
    matrix_identity = MATRIX_VERSION + ":" + sha256_bytes(
        json.dumps(matrix, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        .encode("utf-8")
    )
    return {
        "matrixIdentity": matrix_identity,
        "fixtureCount": len(fixtures),
        "knownMixedFixtureCount": mixed_count,
        "unclassifiedFixtureCount": unclassified_count,
        "closedDetailCodes": CLOSED_DETAILS,
    }


def require_implementation_contract(repository: Path) -> None:
    classifier_path = repository / (
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/"
        "VisualRegionFallbackClassifier.java"
    )
    codec_path = repository / (
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/"
        "VisualGroundingJsonCodec.java"
    )
    worker_path = repository / (
        "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/"
        "LiveInferenceWorker.java"
    )
    classifier = classifier_path.read_text(encoding="utf-8")
    codec = codec_path.read_text(encoding="utf-8")
    worker = worker_path.read_text(encoding="utf-8")
    family_block = re.search(
        r"enum FailureFamily \{(?P<body>.*?)\n\s*private final String code;",
        classifier, re.DOTALL,
    )
    if family_block is None:
        fail("V49_PROVENANCE_IMPLEMENTATION_ENUM_MISSING")
    actual_codes = re.findall(r'\b[A-Z_]+\("([A-Z0-9_]+)"\)', family_block.group("body"))
    if actual_codes != CLOSED_DETAILS:
        fail("V49_PROVENANCE_IMPLEMENTATION_ENUM_DRIFT")
    if (
        f'"{MIXED_PRIMARY}"' not in classifier
        or f'"{UNCLASSIFIED_PRIMARY}"' not in classifier
        or "MIXED_PROVENANCE" not in codec
    ):
        fail("V49_PROVENANCE_IMPLEMENTATION_CONTRACT_MISSING")
    if "MIXED_PROVENANCE" in worker and "InferenceRejectionEnvelope" not in worker:
        fail("V49_PROVENANCE_WORKER_ACTIVATION_WITHOUT_ENVELOPE")


def require_historical_immutability(repository: Path) -> dict[str, Any]:
    profile_root = repository / "renderweave-inference/src/main/resources/inference-profiles"
    profiles: dict[str, dict[str, Any]] = {}
    for profile_id, expected_hash in PROFILE_HASHES.items():
        value = load_json(profile_root / f"{profile_id}.json")
        if value.get("profileId") != profile_id or canonical_profile_sha(value) != expected_hash:
            fail("V49_PROVENANCE_HISTORICAL_PROFILE_DRIFT")
        profiles[profile_id] = value
    profile_ids = list(PROFILE_HASHES)
    for before_id, after_id in zip(profile_ids, profile_ids[1:]):
        before = profiles[before_id]
        after = profiles[after_id]
        if list(before) != list(after):
            fail("V49_PROVENANCE_HISTORICAL_PROFILE_ORDER_DRIFT")
        changed = {key for key in before if before[key] != after[key]}
        if changed != {"profileId", "pipelineVersion", "elementPromptVersion"}:
            fail("V49_PROVENANCE_HISTORICAL_PROFILE_DIFF_DRIFT")

    file_digests = {
        "v46TerminalSha256": (
            repository / "plans/image-only-certification-cycles/"
            "c3bde304-b0b2-43f8-ab7e-16896ff04aed-canary5-terminal.json",
            V46_TERMINAL_SHA,
        ),
        "v47TerminalSha256": (
            repository / "plans/image-only-profile-successor-diagnostics/"
            "4ae94545-2c95-41dc-934e-1661aeb6c121-terminal.json",
            V47_TERMINAL_SHA,
        ),
        "v47AuthorizationSha256": (
            repository / "plans/live-canary-authorizations/"
            "20260817-image-only-v47-diagnostic-4ae94545.json",
            V47_AUTHORIZATION_SHA,
        ),
        "v48TerminalSha256": (
            repository / "plans/image-only-profile-successor-diagnostics/"
            "4e1f41b7-7c42-40d8-afd6-9fe3a35cc54d-terminal.json",
            V48_TERMINAL_SHA,
        ),
        "v48AuthorizationSha256": (
            repository / "plans/live-canary-authorizations/"
            "20260818-image-only-v48-diagnostic-4e1f41b7.json",
            V48_AUTHORIZATION_SHA,
        ),
        "v48LiveSummarySha256": (
            repository / ".sdlc/evidence/"
            "20260818-084802-image-only-v48-successor-diagnostic-live/"
            "image-only-v48-diagnostic-live-summary.json",
            V48_LIVE_SUMMARY_SHA,
        ),
    }
    result: dict[str, Any] = {"profileSha256": PROFILE_HASHES}
    for key, (path, expected) in file_digests.items():
        if not path.is_file() or sha256_bytes(path.read_bytes()) != expected:
            fail("V49_PROVENANCE_HISTORICAL_ARTIFACT_DRIFT")
        result[key] = expected
    return result


def require_no_open_authorization(repository: Path) -> int:
    root = repository / "plans/live-canary-authorizations"
    legacy_duplicate_status_closed = 0
    for path in root.glob("*.json"):
        if path.name.startswith("TEMPLATE-") or path.name.endswith(".schema.json"):
            continue
        try:
            status = load_json(path).get("status")
        except SystemExit as error:
            if (
                str(error) != "V49_PROVENANCE_DUPLICATE_JSON_KEY"
                or path.name != LEGACY_DUPLICATE_STATUS_AUTHORIZATION
                or sha256_bytes(path.read_bytes())
                != LEGACY_DUPLICATE_STATUS_AUTHORIZATION_SHA
            ):
                raise
            top_level_pairs = json.loads(
                path.read_text(encoding="utf-8"), object_pairs_hook=lambda pairs: pairs
            )
            statuses = [value for key, value in top_level_pairs if key == "status"]
            if statuses != ["OPEN", "CLOSED"]:
                fail("V49_PROVENANCE_LEGACY_AUTHORIZATION_STATE_INVALID")
            status = "CLOSED"
            legacy_duplicate_status_closed += 1
        if status == "OPEN":
            fail("V49_PROVENANCE_OPEN_AUTHORIZATION_FORBIDDEN")
    return legacy_duplicate_status_closed


def verify(repository: Path, matrix_path: Path) -> dict[str, Any]:
    require_implementation_contract(repository)
    matrix = verify_matrix(matrix_path)
    historical = require_historical_immutability(repository)
    legacy_duplicate_status_closed = require_no_open_authorization(repository)
    v49_resource = repository / (
        "renderweave-inference/src/main/resources/inference-profiles/"
        "dashscope-qwen38-max-product-v49-hybrid-generic.json"
    )
    if v49_resource.exists():
        fail("V49_PROVENANCE_PROFILE_CREATION_PREMATURE")
    return {
        "version": REPORT_VERSION,
        "result": "PASS",
        "stage": "PROVIDER_ZERO_PROVENANCE",
        **matrix,
        "historical": historical,
        "v49ProfileCreated": False,
        "openAuthorizationCount": 0,
        "legacyDuplicateStatusClosedCount": legacy_duplicate_status_closed,
        "verificationProviderUsage": {
            "attempts": 0,
            "reservations": 0,
            "costMicrosCny": 0,
            "apiKeyReads": 0,
        },
        "candidateApplied": False,
        "staticSchemaPublished": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--matrix", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    summary = verify(args.repository.resolve(), args.matrix.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print("IMAGE_ONLY v49 fallback provenance verification: PASS")


if __name__ == "__main__":
    main()
