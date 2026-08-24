#!/usr/bin/env python3
"""Independent stdlib replay of canonical Renderer Asset fetch-target vectors."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


EXPECTED_AUTHORITY = {
    "engineStage": "RESOURCE_PREPARATION",
    "assetFetchPathPrefix": "/internal/render-assets",
    "targetInput": "TYPED_RENDER_RESOURCE",
    "transportImplementation": "UNWIRED",
    "resourceBytes": "UNFETCHED",
    "daemonOutputPath": "UNWIRED",
    "profileAvailability": "NOT_REGISTERED",
    "certificationStatus": "NOT_CERTIFIED",
    "processRasterImplementation": "ABSENT",
    "productRoute": "CLOSED",
    "providerAttempts": 0,
}
ORIGIN_PATTERN = re.compile(
    r"https://(?P<host>[a-z0-9-]+(?:\.[a-z0-9-]+)*)(?::(?P<port>[0-9]+))?"
)
PATH_SEGMENT_PATTERN = re.compile(r"[A-Za-z0-9._~-]+")


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


def is_canonical_origin(origin: Any) -> bool:
    if not isinstance(origin, str) or not origin or len(origin.encode("utf-8")) > 2_048:
        return False
    if not origin.isascii():
        return False
    match = ORIGIN_PATTERN.fullmatch(origin)
    if match is None:
        return False
    host = match.group("host")
    if len(host) > 253 or host.startswith(".") or host.endswith("."):
        return False
    for label in host.split("."):
        if not 1 <= len(label) <= 63:
            return False
        if not label[0].isalnum() or not label[-1].isalnum():
            return False
        if any(not (character.islower() or character.isdigit() or character == "-")
               for character in label):
            return False
    port = match.group("port")
    if port is None:
        return True
    if (len(port) > 1 and port.startswith("0")) or len(port) > 5:
        return False
    numeric_port = int(port)
    return 1 <= numeric_port <= 65_535 and numeric_port != 443


def is_canonical_path_suffix(suffix: str) -> bool:
    if not suffix or not suffix.isascii():
        return False
    segments = suffix.split("/")
    return all(
        segment not in {"", ".", ".."}
        and PATH_SEGMENT_PATTERN.fullmatch(segment) is not None
        for segment in segments
    )


def replay_policy(origin: Any) -> dict[str, str]:
    return {"outcome": "ADMITTED" if is_canonical_origin(origin) else "REJECTED"}


def replay_target(origin: Any, fetch_url: Any) -> dict[str, str]:
    if not is_canonical_origin(origin) or not isinstance(fetch_url, str):
        return {"outcome": "REJECTED"}
    target_prefix = origin + EXPECTED_AUTHORITY["assetFetchPathPrefix"] + "/"
    if not fetch_url.startswith(target_prefix):
        return {"outcome": "REJECTED"}
    suffix = fetch_url[len(target_prefix):]
    return {
        "outcome": "ADMITTED" if is_canonical_path_suffix(suffix) else "REJECTED"
    }


def verify(vectors_path: Path) -> dict[str, Any]:
    raw = vectors_path.read_bytes()
    try:
        vectors = json.loads(raw, object_pairs_hook=strict_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"vectors are not strict UTF-8 JSON: {error}") from error
    reject_null(vectors)
    exact_members(
        vectors,
        {"vectorVersion", "authorityContext", "policyCases", "targetCases"},
        "vector document",
    )
    verifier = Verifier()
    verifier.require(
        vectors["vectorVersion"] == "renderweave-resource-fetch-target-vectors/1",
        "vector version drifted",
    )
    authority = exact_members(
        vectors["authorityContext"], set(EXPECTED_AUTHORITY), "authorityContext"
    )
    verifier.require(authority == EXPECTED_AUTHORITY, "authority context drifted")
    verifier.require(len(vectors["policyCases"]) == 14, "policy case count drifted")
    verifier.require(len(vectors["targetCases"]) == 22, "target case count drifted")

    seen: set[str] = set()
    for case in vectors["policyCases"]:
        exact_members(case, {"id", "origin", "expected"}, "policy case")
        exact_members(case["expected"], {"outcome"}, "policy expected")
        verifier.require(
            isinstance(case["id"], str) and case["id"] not in seen,
            "policy case id is invalid or duplicated",
        )
        seen.add(case["id"])
        verifier.require(
            replay_policy(case["origin"]) == case["expected"],
            f"{case['id']}: policy result drifted",
        )

    for case in vectors["targetCases"]:
        exact_members(case, {"id", "origin", "fetchUrl", "expected"}, "target case")
        exact_members(case["expected"], {"outcome"}, "target expected")
        verifier.require(
            isinstance(case["id"], str) and case["id"] not in seen,
            "target case id is invalid or duplicated",
        )
        seen.add(case["id"])
        verifier.require(
            replay_target(case["origin"], case["fetchUrl"]) == case["expected"],
            f"{case['id']}: target result drifted",
        )

    return {
        "verifier": "renderweave-resource-fetch-target-python-independent/1",
        "result": "PASS",
        "assurance": "A2",
        "policyCases": len(vectors["policyCases"]),
        "targetCases": len(vectors["targetCases"]),
        "total": len(seen),
        "passed": len(seen),
        "failed": 0,
        "checks": verifier.checks,
        "vectorSha256": "sha256:" + hashlib.sha256(raw).hexdigest(),
        "engineStage": authority["engineStage"],
        "assetFetchPathPrefix": authority["assetFetchPathPrefix"],
        "targetInput": authority["targetInput"],
        "transportImplementation": authority["transportImplementation"],
        "resourceBytes": authority["resourceBytes"],
        "daemonOutputPath": authority["daemonOutputPath"],
        "profileAvailability": authority["profileAvailability"],
        "certificationStatus": authority["certificationStatus"],
        "processRasterImplementation": authority["processRasterImplementation"],
        "productRoute": authority["productRoute"],
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
            f"Resource fetch target independent replay: "
            f"{report['passed']}/{report['total']} cases, {report['checks']} checks, "
            f"transport={report['transportImplementation']}"
        )
        return 0
    except (OSError, VerificationFailure) as error:
        print(f"Resource fetch target independent replay failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
