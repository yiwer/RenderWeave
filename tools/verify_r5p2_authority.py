#!/usr/bin/env python3
"""Independent, payload-safe reconstruction of the R5P2 authority lock."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from typing import Any


AUTHORITY_VERSION = "renderweave-r5p2-authority/1.0"
AUTHORITY_SHA256 = "274585e94941248dd2bea55026c06428f2945aea7cc48ce2b269c21f5f3ccc07"
SPEC_SHA256 = "e33269e1faa04f21239a0e79d4346fc90439f142b26111b3764164f53ba7d902"
HISTORICAL_SPEC_SHA256 = "650ad1632347592d1fc34325983744c02563b43d8a565b9b1cd24e1a805a892a"
CORPUS_LOCK_SHA256 = "cf54fd985e89a024fdc0742a737c21442c49718fdf58b0bb05b87e2cffd2247d"
BASELINE_REVISION = "4b756c52cbc2fd389d8ca34f4c4a65b1bc9615db"
AUTHORITY_PATH = pathlib.Path(
    "renderweave-inference/src/main/resources/visual-eval/r5p2/authority-v1.json"
)
SPEC_PATH = pathlib.Path(
    "specs/changes/20260815-r5p2-source-line-reconciliation-successor.md"
)
HISTORICAL_SPEC_PATH = pathlib.Path(
    "specs/changes/20260815-r5-paired-product-view-successor.md"
)
CORPUS_LOCK_PATH = pathlib.Path(
    "renderweave-inference/src/main/resources/visual-eval/v2/identity-lock.json"
)
HISTORICAL_TERMINAL = "R5P_MEASUREMENT_INVALID"
PRODUCER_REPORT_IDENTITY = (
    "renderweave-r5p-paired-product-view-report/1.0:"
    "2f15a068bd6c5eb8416a1d7da7c8fd679278a8f734cd78d2d35ade6ab01ff783"
)
PRODUCER_REPORT_SHA256 = "df622da5089f069ed4b6bd2a929fec6539839af6375d3669d18f896397082625"
INDEPENDENT_EVIDENCE_IDENTITY = (
    "renderweave-r5p-independent-replay-evidence/1.0:"
    "2ccd12203e15ac572d72036530973ad181e76f0a08ebd4b84b2d4b14aaca5281"
)
INDEPENDENT_EVIDENCE_SHA256 = (
    "1086bbee024a126d7c665995a44461faee36e4a7ee541e73f8bccd2f2fc393d6"
)
EVALUATION_IDENTITY = (
    "renderweave-r5p-paired-view-evaluation/1.0:"
    "c8ad69263640ca49cd93ca24c6b558c6f913ff89a40c84052634c7cd79f66b65"
)
CORPUS_IDENTITY = (
    "renderweave-visual-stage-corpus/2.0:"
    "c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c"
)
CLOSED_TICKETS = ["R5P-07", "R5P-08", "R5P-09", "R5P-10", "R5P-11", "R5P-12"]
PROHIBITED_IDENTITIES = [
    "renderweave-r5p-authority/1.0:"
    "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d",
    "renderweave-r5p-paired-view-assignment/1.0:"
    "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8",
    EVALUATION_IDENTITY,
    PRODUCER_REPORT_IDENTITY,
    INDEPENDENT_EVIDENCE_IDENTITY,
]
AUTHORITY_FIELDS = frozenset(
    {
        "authorityVersion",
        "approvedSpecPath",
        "approvedSpecSha256",
        "baselineRevision",
        "historicalSpecPath",
        "historicalSpecSha256",
        "historicalEffectiveTerminal",
        "historicalProducerReportIdentity",
        "historicalProducerReportSha256",
        "historicalIndependentEvidenceIdentity",
        "historicalIndependentEvidenceSha256",
        "historicalEvaluationIdentity",
        "closedTicketIds",
        "corpusIdentity",
        "corpusIdentityLockSha256",
        "prohibitedIdentityValues",
        "externalProviderUsage",
        "apiKeyReads",
        "terminalCode",
    }
)


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def read_bytes(path: pathlib.Path, code: str) -> bytes:
    try:
        return path.read_bytes()
    except (OSError, ValueError):
        fail(code)


def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("R5P2_AUTHORITY_JSON_INVALID")
        result[key] = value
    return result


def strict_json(value: bytes) -> dict[str, Any]:
    try:
        text = value.decode("utf-8")
        decoder = json.JSONDecoder(object_pairs_hook=strict_object)
        document, end = decoder.raw_decode(text)
        if text[end:].strip() or type(document) is not dict:
            fail("R5P2_AUTHORITY_JSON_INVALID")
        return document
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError):
        fail("R5P2_AUTHORITY_JSON_INVALID")


def require_exact(document: dict[str, Any], field: str, expected: Any, code: str) -> None:
    if document.get(field) != expected or type(document.get(field)) is not type(expected):
        fail(code)


def verify(root: pathlib.Path) -> dict[str, Any]:
    root = pathlib.Path(root).resolve()
    authority_bytes = read_bytes(root / AUTHORITY_PATH, "R5P2_AUTHORITY_RESOURCE_MISSING")
    document = strict_json(authority_bytes)
    if frozenset(document) != AUTHORITY_FIELDS:
        fail("R5P2_AUTHORITY_FIELDS_INVALID")

    require_exact(document, "authorityVersion", AUTHORITY_VERSION, "R5P2_AUTHORITY_DRIFT")
    require_exact(document, "approvedSpecPath", SPEC_PATH.as_posix(), "R5P2_AUTHORITY_DRIFT")
    require_exact(document, "approvedSpecSha256", SPEC_SHA256, "R5P2_AUTHORITY_DRIFT")
    require_exact(document, "baselineRevision", BASELINE_REVISION, "R5P2_AUTHORITY_DRIFT")
    require_exact(
        document, "historicalSpecPath", HISTORICAL_SPEC_PATH.as_posix(),
        "R5P2_HISTORICAL_EVIDENCE_DRIFT",
    )
    require_exact(
        document, "historicalSpecSha256", HISTORICAL_SPEC_SHA256,
        "R5P2_HISTORICAL_EVIDENCE_DRIFT",
    )
    require_exact(
        document, "historicalEffectiveTerminal", HISTORICAL_TERMINAL,
        "R5P2_HISTORICAL_TERMINAL_DRIFT",
    )
    require_exact(
        document, "historicalProducerReportIdentity", PRODUCER_REPORT_IDENTITY,
        "R5P2_HISTORICAL_EVIDENCE_DRIFT",
    )
    require_exact(
        document, "historicalProducerReportSha256", PRODUCER_REPORT_SHA256,
        "R5P2_HISTORICAL_EVIDENCE_DRIFT",
    )
    require_exact(
        document, "historicalIndependentEvidenceIdentity", INDEPENDENT_EVIDENCE_IDENTITY,
        "R5P2_HISTORICAL_EVIDENCE_DRIFT",
    )
    require_exact(
        document, "historicalIndependentEvidenceSha256", INDEPENDENT_EVIDENCE_SHA256,
        "R5P2_HISTORICAL_EVIDENCE_DRIFT",
    )
    require_exact(
        document, "historicalEvaluationIdentity", EVALUATION_IDENTITY,
        "R5P2_HISTORICAL_EVIDENCE_DRIFT",
    )
    require_exact(document, "closedTicketIds", CLOSED_TICKETS, "R5P2_ROUTE_REOPENED")
    require_exact(document, "corpusIdentity", CORPUS_IDENTITY, "R5P2_CORPUS_LOCK_DRIFT")
    require_exact(
        document, "corpusIdentityLockSha256", CORPUS_LOCK_SHA256,
        "R5P2_CORPUS_LOCK_DRIFT",
    )
    require_exact(
        document, "prohibitedIdentityValues", PROHIBITED_IDENTITIES,
        "R5P2_HISTORICAL_IDENTITY_DRIFT",
    )
    require_exact(document, "terminalCode", "R5P2_AUTHORITY_LOCKED", "R5P2_AUTHORITY_DRIFT")

    provider = document.get("externalProviderUsage")
    if type(provider) is not dict or provider != {
        "attempts": 0,
        "reservations": 0,
        "costMicrosCny": 0,
    } or type(document.get("apiKeyReads")) is not int or document["apiKeyReads"] != 0:
        fail("R5P2_PROVIDER_BOUNDARY_VIOLATED")

    if sha256(read_bytes(root / SPEC_PATH, "R5P2_APPROVED_SPEC_MISSING")) != SPEC_SHA256:
        fail("R5P2_APPROVED_SPEC_DRIFT")
    if sha256(read_bytes(root / HISTORICAL_SPEC_PATH, "R5P2_HISTORICAL_SPEC_MISSING")) \
            != HISTORICAL_SPEC_SHA256:
        fail("R5P2_HISTORICAL_EVIDENCE_DRIFT")
    if sha256(read_bytes(root / CORPUS_LOCK_PATH, "R5P2_CORPUS_LOCK_MISSING")) \
            != CORPUS_LOCK_SHA256:
        fail("R5P2_CORPUS_LOCK_DRIFT")
    if sha256(authority_bytes) != AUTHORITY_SHA256:
        fail("R5P2_AUTHORITY_RESOURCE_DRIFT")

    return {
        "result": "PASS",
        "assurance": "A2_INDEPENDENT_READ_ONLY_RECONSTRUCTION",
        "authorityIdentity": f"{AUTHORITY_VERSION}:{AUTHORITY_SHA256}",
        "approvedSpecIdentity": f"spec-sha256:{SPEC_SHA256}",
        "baselineRevision": BASELINE_REVISION,
        "historicalTerminal": HISTORICAL_TERMINAL,
        "closedHistoricalTickets": len(CLOSED_TICKETS),
        "providerAttempts": 0,
        "providerReservations": 0,
        "providerCostMicrosCny": 0,
        "apiKeyReads": 0,
        "disposition": "R5P2_AUTHORITY_LOCKED",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=pathlib.Path, default=pathlib.Path.cwd())
    arguments = parser.parse_args()
    try:
        print(json.dumps(verify(arguments.repository), sort_keys=True, separators=(",", ":")))
        return 0
    except VerificationError as failure:
        print(json.dumps({"result": "FAIL", "code": str(failure)}, sort_keys=True))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
