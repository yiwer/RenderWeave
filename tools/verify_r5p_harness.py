#!/usr/bin/env python3
"""Independently reconstruct R5P static-plan acquisition evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
from typing import Any


CONTRACT_VERSION = "renderweave-r5p-harness-evidence/1.0"
AUTHORITY_IDENTITY = (
    "renderweave-r5p-authority/1.0:"
    "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d"
)
HARNESS_VERSION = "renderweave-r5p-product-view-harness/1.0"
PLAN_VERSION = "renderweave-visual-view-plan/1.0"
PLAN_IDENTITY_VERSION = "renderweave-r5p-static-plan/1.0"
EVIDENCE_IDENTITY_VERSION = "renderweave-r5p-harness-evidence/1.0"
TERMINAL = "R5P_HARNESS_CONFORMANT"
MAX_EVIDENCE_BYTES = 1_048_576
MAX_VIEWS = 10
MAX_ACQUIRED_BYTES = 30 * 1024 * 1024

TOP_FIELDS = frozenset(
    {
        "apiKeyReads",
        "authorityIdentity",
        "contractVersion",
        "externalProviderUsage",
        "runs",
        "terminalCode",
    }
)
RUN_FIELDS = frozenset(
    {
        "harnessVersion",
        "normalizationProvenance",
        "staticPlanIdentity",
        "viewSummaries",
        "acquisitionTrace",
        "plannedViewCount",
        "acquiredViewCount",
        "blobWrites",
        "blobReads",
        "externalProviderUsage",
        "apiKeyReads",
        "terminalCode",
        "evidenceIdentity",
    }
)
PROVENANCE_FIELDS = frozenset(
    {
        "fixtureId",
        "rawFixtureSha256",
        "inputFingerprint",
        "normalizedArtifactId",
        "mediaType",
        "encodedBytes",
        "width",
        "height",
    }
)
VIEW_FIELDS = frozenset(
    {
        "planOrdinal",
        "viewId",
        "sourceArtifactId",
        "sourceOrdinal",
        "kind",
        "descriptorIdentity",
        "providerArtifactId",
        "width",
        "height",
        "encodedBytes",
        "encodedSha256",
    }
)
ACQUISITION_FIELDS = frozenset(
    {
        "acquisitionOrdinal",
        "viewId",
        "providerArtifactId",
        "width",
        "height",
        "encodedBytes",
        "encodedSha256",
    }
)
USAGE_FIELDS = frozenset({"attempts", "reservations", "costMicrosCny"})
SHA256 = re.compile(r"^[0-9a-f]{64}$")
IDENTITY_SHA256 = re.compile(r"^[a-z0-9-]+(?:/[0-9.]+)?:[0-9a-f]{64}$")
FORBIDDEN_KEYS = frozenset(
    {
        "base64",
        "candidate",
        "chainofthought",
        "derivedbytes",
        "modelinput",
        "modeloutput",
        "ocrtext",
        "prompt",
        "rawbytes",
        "rawimage",
        "rootdocument",
        "sourcebytes",
    }
)


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def strict_json(raw: bytes) -> dict[str, Any]:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                fail("R5P_HARNESS_JSON_INVALID")
            result[key] = value
        return result

    def reject_float(_: str) -> Any:
        fail("R5P_HARNESS_JSON_INVALID")

    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=pairs,
            parse_float=reject_float,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, VerificationError):
        fail("R5P_HARNESS_JSON_INVALID")
    if type(value) is not dict:
        fail("R5P_HARNESS_JSON_INVALID")
    return value


def exact_fields(value: Any, fields: frozenset[str], code: str) -> dict[str, Any]:
    if type(value) is not dict or frozenset(value) != fields:
        fail(code)
    return value


def exact(value: Any, expected: Any, code: str) -> None:
    if type(value) is not type(expected) or value != expected:
        fail(code)


def positive_integer(value: Any, code: str) -> int:
    if type(value) is not int or value <= 0:
        fail(code)
    return value


def zero_usage(value: Any) -> dict[str, Any]:
    usage = exact_fields(value, USAGE_FIELDS, "R5P_HARNESS_PROVIDER_USAGE_INVALID")
    if any(type(usage[field]) is not int or usage[field] != 0 for field in USAGE_FIELDS):
        fail("R5P_HARNESS_PROVIDER_USAGE_NONZERO")
    return usage


def sha(value: Any, code: str) -> str:
    if type(value) is not str or SHA256.fullmatch(value) is None:
        fail(code)
    return value


def identity(value: Any, prefix: str, code: str) -> str:
    if (
        type(value) is not str
        or not value.startswith(prefix + ":")
        or IDENTITY_SHA256.fullmatch(value) is None
    ):
        fail(code)
    return value


def framed_sha256(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def assert_payload_safe(value: Any) -> None:
    if type(value) is dict:
        for key, child in value.items():
            compact = re.sub(r"[^a-z0-9]", "", key.lower())
            if compact in FORBIDDEN_KEYS:
                fail("R5P_HARNESS_PAYLOAD_UNSAFE")
            assert_payload_safe(child)
    elif type(value) is list:
        for child in value:
            assert_payload_safe(child)
    elif type(value) is str:
        lowered = value.lower()
        if (
            "data:image/" in lowered
            or "base64," in lowered
            or "begin private key" in lowered
            or "sk-" in lowered
        ):
            fail("R5P_HARNESS_PAYLOAD_UNSAFE")


def verify_provenance(value: Any) -> list[dict[str, Any]]:
    if type(value) is not list or not value:
        fail("R5P_HARNESS_PROVENANCE_INVALID")
    seen_fixtures: set[str] = set()
    seen_artifacts: set[str] = set()
    for item_value in value:
        item = exact_fields(
            item_value, PROVENANCE_FIELDS, "R5P_HARNESS_PROVENANCE_INVALID"
        )
        if type(item["fixtureId"]) is not str or not item["fixtureId"]:
            fail("R5P_HARNESS_PROVENANCE_INVALID")
        raw = sha(item["rawFixtureSha256"], "R5P_HARNESS_PROVENANCE_INVALID")
        sha(item["inputFingerprint"], "R5P_HARNESS_PROVENANCE_INVALID")
        normalized = sha(
            item["normalizedArtifactId"], "R5P_HARNESS_PROVENANCE_INVALID"
        )
        exact(item["mediaType"], "image/png", "R5P_HARNESS_PROVENANCE_INVALID")
        positive_integer(item["encodedBytes"], "R5P_HARNESS_PROVENANCE_INVALID")
        positive_integer(item["width"], "R5P_HARNESS_PROVENANCE_INVALID")
        positive_integer(item["height"], "R5P_HARNESS_PROVENANCE_INVALID")
        if raw == normalized:
            fail("R5P_HARNESS_NORMALIZATION_NOT_OBSERVED")
        if item["fixtureId"] in seen_fixtures or normalized in seen_artifacts:
            fail("R5P_HARNESS_PROVENANCE_INVALID")
        seen_fixtures.add(item["fixtureId"])
        seen_artifacts.add(normalized)
    return value


def verify_views(
    value: Any, provenance: list[dict[str, Any]], expected_count: int
) -> list[dict[str, Any]]:
    if type(value) is not list or len(value) != expected_count:
        fail("R5P_HARNESS_VIEW_COUNT_INVALID")
    artifacts = {item["normalizedArtifactId"]: ordinal for ordinal, item in enumerate(provenance)}
    seen_views: set[str] = set()
    first_kind_by_source: dict[str, str] = {}
    for ordinal, item_value in enumerate(value):
        item = exact_fields(item_value, VIEW_FIELDS, "R5P_HARNESS_VIEW_INVALID")
        exact(item["planOrdinal"], ordinal, "R5P_HARNESS_VIEW_INVALID")
        if type(item["viewId"]) is not str or not item["viewId"]:
            fail("R5P_HARNESS_VIEW_INVALID")
        if item["viewId"] in seen_views:
            fail("R5P_HARNESS_VIEW_INVALID")
        seen_views.add(item["viewId"])
        if item["sourceArtifactId"] not in artifacts:
            fail("R5P_HARNESS_VIEW_SOURCE_INVALID")
        exact(
            item["sourceOrdinal"],
            artifacts[item["sourceArtifactId"]],
            "R5P_HARNESS_VIEW_SOURCE_INVALID",
        )
        if item["kind"] not in ("OVERVIEW", "TILE"):
            fail("R5P_HARNESS_VIEW_INVALID")
        first_kind_by_source.setdefault(item["sourceArtifactId"], item["kind"])
        identity(
            item["descriptorIdentity"],
            "renderweave-r5p-view-descriptor/1.0",
            "R5P_HARNESS_VIEW_INVALID",
        )
        provider_id = sha(item["providerArtifactId"], "R5P_HARNESS_VIEW_INVALID")
        encoded_sha = sha(item["encodedSha256"], "R5P_HARNESS_VIEW_INVALID")
        if provider_id != encoded_sha:
            fail("R5P_HARNESS_VIEW_INVALID")
        positive_integer(item["width"], "R5P_HARNESS_VIEW_INVALID")
        positive_integer(item["height"], "R5P_HARNESS_VIEW_INVALID")
        positive_integer(item["encodedBytes"], "R5P_HARNESS_VIEW_INVALID")
    if set(first_kind_by_source) != set(artifacts) or any(
        kind != "OVERVIEW" for kind in first_kind_by_source.values()
    ):
        fail("R5P_HARNESS_OVERVIEW_COVERAGE_INVALID")
    return value


def verify_acquisitions(
    value: Any, views: list[dict[str, Any]], expected_count: int
) -> list[dict[str, Any]]:
    if type(value) is not list or len(value) != expected_count:
        fail("R5P_HARNESS_ACQUISITION_COUNT_INVALID")
    total_bytes = 0
    for ordinal, item_value in enumerate(value):
        item = exact_fields(
            item_value, ACQUISITION_FIELDS, "R5P_HARNESS_ACQUISITION_TRACE_INVALID"
        )
        view = views[ordinal]
        expected = {
            "acquisitionOrdinal": ordinal,
            "viewId": view["viewId"],
            "providerArtifactId": view["providerArtifactId"],
            "width": view["width"],
            "height": view["height"],
            "encodedBytes": view["encodedBytes"],
            "encodedSha256": view["encodedSha256"],
        }
        if item != expected:
            fail("R5P_HARNESS_ACQUISITION_TRACE_INVALID")
        total_bytes += item["encodedBytes"]
    if total_bytes > MAX_ACQUIRED_BYTES:
        fail("R5P_HARNESS_ACQUISITION_LIMIT_EXCEEDED")
    return value


def verify_run(value: Any) -> dict[str, Any]:
    run = exact_fields(value, RUN_FIELDS, "R5P_HARNESS_RUN_FIELDS_INVALID")
    exact(run["harnessVersion"], HARNESS_VERSION, "R5P_HARNESS_VERSION_DRIFT")
    exact(run["terminalCode"], TERMINAL, "R5P_HARNESS_TERMINAL_DRIFT")
    zero_usage(run["externalProviderUsage"])
    if type(run["apiKeyReads"]) is not int or run["apiKeyReads"] != 0:
        fail("R5P_HARNESS_API_KEY_READ_NONZERO")
    planned_count = positive_integer(run["plannedViewCount"], "R5P_HARNESS_COUNT_INVALID")
    acquired_count = positive_integer(run["acquiredViewCount"], "R5P_HARNESS_COUNT_INVALID")
    if planned_count > MAX_VIEWS:
        fail("R5P_HARNESS_VIEW_LIMIT_EXCEEDED")
    if planned_count != acquired_count:
        fail("R5P_HARNESS_ACQUISITION_COUNT_INVALID")
    blob_writes = positive_integer(run["blobWrites"], "R5P_HARNESS_BLOB_PATH_INVALID")
    blob_reads = positive_integer(run["blobReads"], "R5P_HARNESS_BLOB_PATH_INVALID")

    provenance = verify_provenance(run["normalizationProvenance"])
    views = verify_views(run["viewSummaries"], provenance, planned_count)
    acquisitions = verify_acquisitions(run["acquisitionTrace"], views, acquired_count)

    plan_frames = [f"plan-version={PLAN_VERSION}", f"view-count={planned_count}"]
    plan_frames.extend(item["descriptorIdentity"] for item in views)
    expected_plan = f"{PLAN_IDENTITY_VERSION}:{framed_sha256(plan_frames)}"
    identity(run["staticPlanIdentity"], PLAN_IDENTITY_VERSION, "R5P_HARNESS_PLAN_ID_INVALID")
    if run["staticPlanIdentity"] != expected_plan:
        fail("R5P_HARNESS_PLAN_IDENTITY_DRIFT")

    evidence_frames = [
        f"harness={HARNESS_VERSION}",
        f"plan={expected_plan}",
    ]
    evidence_frames.extend(
        "normalized="
        f"{item['fixtureId']}:{item['rawFixtureSha256']}:"
        f"{item['normalizedArtifactId']}:{item['width']}x{item['height']}:"
        f"{item['encodedBytes']}"
        for item in provenance
    )
    evidence_frames.extend(f"view={item['descriptorIdentity']}" for item in views)
    evidence_frames.extend(
        "acquired="
        f"{item['acquisitionOrdinal']}:{item['viewId']}:{item['providerArtifactId']}:"
        f"{item['width']}x{item['height']}:{item['encodedBytes']}"
        for item in acquisitions
    )
    evidence_frames.extend(
        (
            f"blob-writes={blob_writes}",
            f"blob-reads={blob_reads}",
            "provider-attempts=0",
            "api-key-reads=0",
            f"terminal={TERMINAL}",
        )
    )
    expected_evidence = (
        f"{EVIDENCE_IDENTITY_VERSION}:{framed_sha256(evidence_frames)}"
    )
    identity(
        run["evidenceIdentity"],
        EVIDENCE_IDENTITY_VERSION,
        "R5P_HARNESS_EVIDENCE_ID_INVALID",
    )
    if run["evidenceIdentity"] != expected_evidence:
        fail("R5P_HARNESS_EVIDENCE_IDENTITY_DRIFT")
    return run


def verify(evidence_path: pathlib.Path) -> dict[str, Any]:
    try:
        raw = evidence_path.resolve().read_bytes()
    except (OSError, ValueError):
        fail("R5P_HARNESS_EVIDENCE_MISSING")
    if len(raw) > MAX_EVIDENCE_BYTES:
        fail("R5P_HARNESS_EVIDENCE_OVERSIZE")
    document = strict_json(raw)
    assert_payload_safe(document)
    exact_fields(document, TOP_FIELDS, "R5P_HARNESS_FIELDS_INVALID")
    exact(document["contractVersion"], CONTRACT_VERSION, "R5P_HARNESS_CONTRACT_DRIFT")
    exact(document["authorityIdentity"], AUTHORITY_IDENTITY, "R5P_HARNESS_AUTHORITY_DRIFT")
    exact(document["terminalCode"], TERMINAL, "R5P_HARNESS_TERMINAL_DRIFT")
    usage = zero_usage(document["externalProviderUsage"])
    if type(document["apiKeyReads"]) is not int or document["apiKeyReads"] != 0:
        fail("R5P_HARNESS_API_KEY_READ_NONZERO")
    if type(document["runs"]) is not list or len(document["runs"]) != 2:
        fail("R5P_HARNESS_RUN_COUNT_INVALID")
    runs = [verify_run(value) for value in document["runs"]]
    if runs[0] != runs[1]:
        fail("R5P_HARNESS_REPEATABILITY_DRIFT")

    run = runs[0]
    return {
        "verifierVersion": "renderweave-r5p-harness-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_INDEPENDENT_PLAN_ACQUISITION_RECONSTRUCTION",
        "authorityIdentity": document["authorityIdentity"],
        "runCount": len(runs),
        "plannedViewCount": run["plannedViewCount"],
        "acquiredViewCount": run["acquiredViewCount"],
        "staticPlanIdentity": run["staticPlanIdentity"],
        "evidenceIdentity": run["evidenceIdentity"],
        "blobWrites": run["blobWrites"],
        "blobReads": run["blobReads"],
        "providerAttempts": usage["attempts"],
        "providerReservations": usage["reservations"],
        "providerCostMicrosCny": usage["costMicrosCny"],
        "apiKeyReads": document["apiKeyReads"],
        "disposition": document["terminalCode"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", nargs="?", type=pathlib.Path)
    parser.add_argument("--evidence", dest="evidence_option", type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if (args.evidence is None) == (args.evidence_option is None):
        parser.error("provide exactly one evidence path")
    result = verify(args.evidence or args.evidence_option)
    encoded = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(encoded + "\n")
    print("R5P harness: CONFORMANT; assurance=A2; Provider=0; J1=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
