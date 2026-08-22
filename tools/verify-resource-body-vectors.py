#!/usr/bin/env python3
"""Independent standard-library replay of request-local resource body integrity vectors."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


MAX_U64 = (1 << 64) - 1
RESOURCE_ID = "rwres_" + "a" * 64
EXPECTED_AUTHORITY = {
    "physicalFetchBytesLimit": 536_870_912,
    "physicalFetchBytesLimitId": "assetsAndFetch.physicalFetchBytesIncludingRetries",
    "engineStage": "RESOURCE_PREPARATION",
    "integrityOrder": [
        "PHYSICAL_FETCH_BUDGET",
        "DECLARED_LENGTH",
        "LOWERCASE_SHA256",
    ],
    "resourceInput": "CALLER_SUPPLIED_CHUNKS",
    "resourceBytes": "UNFETCHED",
    "daemonOutputPath": "UNWIRED",
    "profileAvailability": "NOT_REGISTERED",
    "certificationStatus": "NOT_CERTIFIED",
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


def strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationFailure(f"duplicate JSON member: {key}")
        result[key] = value
    return result


def reject_null(value: Any, location: str = "$") -> None:
    if value is None:
        raise VerificationFailure(f"null is forbidden at {location}")
    if isinstance(value, dict):
        for key, nested in value.items():
            reject_null(nested, f"{location}.{key}")
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            reject_null(nested, f"{location}[{index}]")


def exact_members(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise VerificationFailure(f"{label} member set drifted")
    return value


def require_u64(value: Any, label: str) -> int:
    if type(value) is not int or not 0 <= value <= MAX_U64:
        raise VerificationFailure(f"{label} must be a u64")
    return value


def reserve(accepted: int, chunk_bytes: int) -> tuple[int, bool]:
    if accepted > MAX_U64 - chunk_bytes:
        return accepted, False
    candidate = accepted + chunk_bytes
    if candidate > EXPECTED_AUTHORITY["physicalFetchBytesLimit"]:
        return accepted, False
    return candidate, True


def budget_problem(accepted: int, failed_index: int | None = None) -> dict[str, Any]:
    problem: dict[str, Any] = {
        "outcome": "RESOURCE_BUDGET_EXCEEDED",
        "acceptedBytes": accepted,
        "engineStage": "RESOURCE_PREPARATION",
        "resourceId": RESOURCE_ID,
        "limitId": EXPECTED_AUTHORITY["physicalFetchBytesLimitId"],
    }
    if failed_index is not None:
        problem["failedChunkIndex"] = failed_index
    return problem


def integrity_problem(code: str, accepted: int) -> dict[str, Any]:
    return {
        "outcome": code,
        "acceptedBytes": accepted,
        "engineStage": "RESOURCE_PREPARATION",
        "resourceId": RESOURCE_ID,
    }


def replay_budget_case(case: dict[str, Any]) -> dict[str, Any]:
    accepted = 0
    for index, raw_chunk_bytes in enumerate(case["chunkByteCounts"]):
        chunk_bytes = require_u64(raw_chunk_bytes, f"{case['id']} chunkByteCounts[{index}]")
        accepted, allowed = reserve(accepted, chunk_bytes)
        if not allowed:
            return budget_problem(accepted, index)
    return {"outcome": "ACCEPTED", "acceptedBytes": accepted}


def canonical_hex(value: Any, label: str) -> bytes:
    if not isinstance(value, str) or len(value) % 2 or not re.fullmatch(r"[0-9a-f]*", value):
        raise VerificationFailure(f"{label} is not canonical lowercase hex")
    return bytes.fromhex(value)


def replay_body_case(case: dict[str, Any]) -> dict[str, Any]:
    declared_length = require_u64(case["declaredByteLength"], f"{case['id']} declaredByteLength")
    if declared_length == 0:
        raise VerificationFailure(f"{case['id']} declaredByteLength must be positive")
    declared_sha256 = case["declaredSha256"]
    if not isinstance(declared_sha256, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", declared_sha256):
        raise VerificationFailure(f"{case['id']} declaredSha256 is not canonical")

    accepted = 0
    for index, raw_chunk_bytes in enumerate(case["initialChunkByteCounts"]):
        chunk_bytes = require_u64(raw_chunk_bytes, f"{case['id']} initialChunkByteCounts[{index}]")
        accepted, allowed = reserve(accepted, chunk_bytes)
        if not allowed:
            raise VerificationFailure(f"{case['id']} initial budget reservation failed")

    actual_length = 0
    digest = hashlib.sha256()
    for index, raw_chunk in enumerate(case["chunksHex"]):
        chunk = canonical_hex(raw_chunk, f"{case['id']} chunksHex[{index}]")
        accepted, allowed = reserve(accepted, len(chunk))
        if not allowed:
            return budget_problem(accepted)
        actual_length += len(chunk)
        if actual_length > declared_length:
            return integrity_problem("LENGTH_MISMATCH", accepted)
        digest.update(chunk)
    if actual_length != declared_length:
        return integrity_problem("LENGTH_MISMATCH", accepted)
    actual_sha256 = "sha256:" + digest.hexdigest()
    if actual_sha256 != declared_sha256:
        return integrity_problem("HASH_MISMATCH", accepted)
    return {
        "outcome": "VERIFIED",
        "acceptedBytes": accepted,
        "resourceId": RESOURCE_ID,
        "byteLength": actual_length,
        "sha256": actual_sha256,
    }


def verify(vectors_path: Path) -> dict[str, Any]:
    raw = vectors_path.read_bytes()
    try:
        vectors = json.loads(raw, object_pairs_hook=strict_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"vectors are not strict UTF-8 JSON: {error}") from error
    reject_null(vectors)
    verifier = Verifier()
    exact_members(
        vectors,
        {"vectorVersion", "authorityContext", "budgetCases", "bodyCases"},
        "vector document",
    )
    verifier.require(
        vectors["vectorVersion"] == "renderweave-resource-body-vectors/1",
        "vector version drifted",
    )
    authority = exact_members(
        vectors["authorityContext"], set(EXPECTED_AUTHORITY), "authorityContext"
    )
    verifier.require(authority == EXPECTED_AUTHORITY, "authority context drifted")
    verifier.require(len(vectors["budgetCases"]) == 6, "budget case count drifted")
    verifier.require(len(vectors["bodyCases"]) == 9, "body case count drifted")

    seen: set[str] = set()
    for case in vectors["budgetCases"]:
        exact_members(case, {"id", "chunkByteCounts", "expected"}, "budget case")
        verifier.require(
            isinstance(case["id"], str) and case["id"] not in seen,
            "budget case id is invalid or duplicated",
        )
        seen.add(case["id"])
        verifier.require(
            replay_budget_case(case) == case["expected"],
            f"{case['id']}: budget result drifted",
        )

    for case in vectors["bodyCases"]:
        exact_members(
            case,
            {
                "id",
                "initialChunkByteCounts",
                "chunksHex",
                "declaredByteLength",
                "declaredSha256",
                "expected",
            },
            "body case",
        )
        verifier.require(
            isinstance(case["id"], str) and case["id"] not in seen,
            "body case id is invalid or duplicated",
        )
        seen.add(case["id"])
        verifier.require(
            replay_body_case(case) == case["expected"],
            f"{case['id']}: body result drifted",
        )

    return {
        "verifier": "renderweave-resource-body-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "budgetCases": len(vectors["budgetCases"]),
        "bodyCases": len(vectors["bodyCases"]),
        "total": len(seen),
        "passed": len(seen),
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(raw).hexdigest(),
        "physicalFetchBytesLimit": authority["physicalFetchBytesLimit"],
        "physicalFetchBytesLimitId": authority["physicalFetchBytesLimitId"],
        "integrityOrder": authority["integrityOrder"],
        "resourceInput": authority["resourceInput"],
        "resourceBytes": authority["resourceBytes"],
        "daemonOutputPath": authority["daemonOutputPath"],
        "profileAvailability": authority["profileAvailability"],
        "certificationStatus": authority["certificationStatus"],
        "providerAttempts": authority["providerAttempts"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        report = verify(arguments.vectors)
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(
            json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n"
        )
        print(
            f"Resource body independent replay: {report['passed']}/{report['total']} cases, "
            f"{report['checks']} checks, bytes={report['resourceBytes']}, "
            f"daemon={report['daemonOutputPath']}"
        )
        return 0
    except (OSError, VerificationFailure) as error:
        print(f"Resource body independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
