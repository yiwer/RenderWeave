#!/usr/bin/env python3
"""Independent stdlib replay for the bounded Renderer HTTPS fetch contract."""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


EXPECTED_LIMITS = {
    "allowedIpCountMax": 16,
    "attempts": 2,
    "backoffMillis": 100,
    "attemptMillis": 5000,
    "resourcePhaseMillis": 20000,
    "streamChunkBytes": 1048576,
    "physicalFetchBytes": 536870912,
    "responseHeaderBytes": 65536,
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


def require_case_count(values: Any, expected: int, label: str) -> list[dict[str, Any]]:
    if not isinstance(values, list) or len(values) != expected:
        raise VerificationFailure(f"{label} case count drifted")
    return values


def canonical_ip(value: str) -> bool:
    if "%" in value:
        return False
    try:
        parsed = ipaddress.ip_address(value)
    except ValueError:
        return False
    return str(parsed) == value


def admit_egress(values: list[str]) -> bool:
    return (
        1 <= len(values) <= EXPECTED_LIMITS["allowedIpCountMax"]
        and len(set(values)) == len(values)
        and all(canonical_ip(value) for value in values)
    )


def classify_response(case: dict[str, object]) -> tuple[str, bool]:
    status = int(case["status"])
    lengths = list(case["contentLengths"])
    encodings = list(case["contentEncodings"])
    transfers = list(case["transferEncodings"])
    declared_length = int(case["declaredLength"])
    if 500 <= status <= 599:
        return "FETCH_FAILED", True
    if status != 200:
        return "FETCH_FAILED", False
    if transfers or len(encodings) > 1 or (encodings and encodings[0] != "identity"):
        return "FETCH_FAILED", False
    if len(lengths) != 1 or "," in lengths[0]:
        return "FETCH_FAILED", False
    try:
        actual_length = int(lengths[0], 10)
    except ValueError:
        return "FETCH_FAILED", False
    if not lengths[0].isascii() or not lengths[0].isdigit():
        return "FETCH_FAILED", False
    if actual_length != declared_length:
        return "LENGTH_MISMATCH", False
    return "BODY", False


def replay_schedule(events: list[str]) -> tuple[str, int, int]:
    attempts = 0
    backoffs = 0
    for event in events:
        if event == "DEADLINE":
            return "RENDER_DEADLINE_EXCEEDED", attempts, backoffs
        if event == "LEASE_EXPIRED":
            return "RESOURCE_LEASE_EXPIRED", attempts, backoffs
        attempts += 1
        if event == "HTTP_200_VALID":
            return "VERIFIED", attempts, backoffs
        if event == "LENGTH_MISMATCH":
            return "LENGTH_MISMATCH", attempts, backoffs
        if event == "HASH_MISMATCH":
            return "HASH_MISMATCH", attempts, backoffs
        retryable = event == "TRANSPORT" or event.startswith("HTTP_5")
        if not retryable or attempts >= EXPECTED_LIMITS["attempts"]:
            return "FETCH_FAILED", attempts, backoffs
        backoffs += 1
    raise AssertionError("schedule has no terminal event")


def verify(vectors_path: Path) -> dict[str, Any]:
    raw = vectors_path.read_bytes()
    try:
        vectors = json.loads(raw, object_pairs_hook=strict_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"vectors are not strict UTF-8 JSON: {error}") from error
    reject_null(vectors)
    exact_members(
        vectors,
        {
            "profile",
            "transportImplementation",
            "resourceBytes",
            "limits",
            "fixedRequestHeaders",
            "egressCases",
            "responseCases",
            "scheduleCases",
        },
        "vector document",
    )
    verifier = Verifier()
    verifier.require(
        vectors["profile"] == "renderweave-resource-fetch-transport-v1",
        "transport profile drifted",
    )
    verifier.require(
        vectors["transportImplementation"] == "RUSTLS_HTTPS_AUTOMATED_VERIFIED",
        "transport implementation boundary drifted",
    )
    verifier.require(
        vectors["resourceBytes"] == "FETCHED_AND_INTEGRITY_VERIFIED",
        "resource byte boundary drifted",
    )
    limits = exact_members(vectors["limits"], set(EXPECTED_LIMITS), "limits")
    for key, expected in EXPECTED_LIMITS.items():
        verifier.require(limits[key] == expected, f"{key} limit drifted")
    expected_headers = {
        "accept-encoding": "identity",
        "connection": "close",
    }
    headers = exact_members(
        vectors["fixedRequestHeaders"], set(expected_headers), "fixedRequestHeaders"
    )
    for key, expected in expected_headers.items():
        verifier.require(headers[key] == expected, f"{key} request header drifted")

    egress_cases = require_case_count(vectors["egressCases"], 9, "egress")
    response_cases = require_case_count(vectors["responseCases"], 12, "response")
    schedule_cases = require_case_count(vectors["scheduleCases"], 12, "schedule")

    seen: set[str] = set()
    for case in egress_cases:
        exact_members(case, {"id", "values", "accepted"}, "egress case")
        verifier.require(
            isinstance(case["id"], str) and case["id"] not in seen,
            "egress case id is invalid or duplicated",
        )
        seen.add(case["id"])
        verifier.require(
            admit_egress(list(case["values"])) is bool(case["accepted"]),
            f"{case['id']}: egress result drifted",
        )

    for case in response_cases:
        exact_members(
            case,
            {
                "id",
                "status",
                "contentLengths",
                "contentEncodings",
                "transferEncodings",
                "declaredLength",
                "outcome",
                "retryable",
            },
            "response case",
        )
        verifier.require(
            isinstance(case["id"], str) and case["id"] not in seen,
            "response case id is invalid or duplicated",
        )
        seen.add(case["id"])
        outcome, retryable = classify_response(case)
        verifier.require(outcome == case["outcome"], f"{case['id']}: outcome drifted")
        verifier.require(
            retryable is bool(case["retryable"]),
            f"{case['id']}: retryability drifted",
        )

    for case in schedule_cases:
        exact_members(
            case,
            {"id", "events", "outcome", "attempts", "backoffs"},
            "schedule case",
        )
        verifier.require(
            isinstance(case["id"], str) and case["id"] not in seen,
            "schedule case id is invalid or duplicated",
        )
        seen.add(case["id"])
        outcome, attempts, backoffs = replay_schedule(list(case["events"]))
        verifier.require(outcome == case["outcome"], f"{case['id']}: outcome drifted")
        verifier.require(attempts == int(case["attempts"]), f"{case['id']}: attempts drifted")
        verifier.require(
            backoffs == int(case["backoffs"]), f"{case['id']}: backoffs drifted"
        )

    return {
        "verifier": "renderweave-resource-fetch-transport-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "egressCases": len(egress_cases),
        "responseCases": len(response_cases),
        "scheduleCases": len(schedule_cases),
        "total": len(seen),
        "passed": len(seen),
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(raw).hexdigest(),
        "transportImplementation": vectors["transportImplementation"],
        "resourceBytes": vectors["resourceBytes"],
        "daemonOutputPath": "UNWIRED",
        "profileAvailability": "NOT_REGISTERED",
        "certificationStatus": "NOT_CERTIFIED",
        "processRasterImplementation": "ABSENT",
        "productRoute": "CLOSED",
        "providerAttempts": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--vectors",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "renderer"
        / "resource-fetch-transport-vectors-v1.json",
    )
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.vectors)
        if args.report is not None:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(
                json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n"
            )
        print(
            "Resource fetch transport independent replay: "
            f"{report['passed']}/{report['total']} cases, {report['checks']} checks, "
            f"transport={report['transportImplementation']}"
        )
        return 0
    except (OSError, VerificationFailure, TypeError, ValueError) as error:
        print(f"Resource fetch transport independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
