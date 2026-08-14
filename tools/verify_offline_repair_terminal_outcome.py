#!/usr/bin/env python3
"""Independently verify a payload-safe fail-closed offline ticket outcome."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


OUTCOME_PREFIX = "renderweave-offline-repair-terminal-outcome/1.0:"
DECISION_PREFIX = "renderweave-r2r5-trigger-decision/1.0:"
CATALOG_PREFIX = "renderweave-challenger-capability-catalog/1.0:"
CAPABILITY_PREFIX = "renderweave-challenger-capability/1.0:"

TICKETS = {
    "VRQ_08_PP_STRUCTUREV3_DEV_SHADOW": (
        "pp-structurev3",
        "STOPPED_FOR_R5_SUCCESSOR_SPEC",
        "R5_TRIGGERED_REQUIRES_SUCCESSOR_SPEC",
    ),
    "VRQ_09_TESSERACT_DEV_BASELINE": (
        "tesseract-tsv-hocr",
        "STOPPED_FOR_R5_SUCCESSOR_SPEC",
        "R5_TRIGGERED_REQUIRES_SUCCESSOR_SPEC",
    ),
    "VRQ_10_SOLE_DEV_WINNER_SELECTION": (
        None,
        "BLOCKED_BY_PREDECESSOR",
        "R2_DEV_REPORTS_UNAVAILABLE",
    ),
    "VRQ_11_WINNER_HOLDOUT": (
        None,
        "BLOCKED_BY_PREDECESSOR",
        "R2_SOLE_WINNER_UNAVAILABLE",
    ),
}

EXPECTED_PREDECESSORS = {
    "VRQ_10_SOLE_DEV_WINNER_SELECTION": {
        "VRQ_08_PP_STRUCTUREV3_DEV_SHADOW",
        "VRQ_09_TESSERACT_DEV_BASELINE",
    },
    "VRQ_11_WINNER_HOLDOUT": {"VRQ_10_SOLE_DEV_WINNER_SELECTION"},
}

FORBIDDEN = (
    '"ocrText"', '"ocr_text"', '"imageBytes"', '"promptText"',
    '"providerRequest"', '"providerResponse"', '"modelOutput"',
    '"candidateJson"', '"boundingBox"', '"rootDocument"', '"base64"',
    '"inspectionRequest"', "data:image", "ignore prior instructions", "bearer ",
)


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def read_envelope(path: Path, envelope_version: str, payload_key: str, identity_key: str, prefix: str):
    raw = path.read_bytes()
    value = json.loads(raw)
    assert raw == canonical(value), f"{path.name}: non-canonical JSON"
    assert set(value) == {"envelopeVersion", identity_key, payload_key}
    assert value["envelopeVersion"] == envelope_version
    expected = prefix + sha256(canonical(value[payload_key]))
    assert value[identity_key] == expected, f"{path.name}: identity drift"
    return value[payload_key], expected


def framed_identity(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode()
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def require_evidence_path(repository: Path, candidate: Path, must_exist: bool = True) -> Path:
    evidence_root = (repository / ".sdlc" / "evidence").resolve(strict=True)
    resolved_parent = candidate.resolve(strict=False).parent.resolve(strict=True)
    resolved = resolved_parent / candidate.name
    assert evidence_root in resolved.parents, "evidence path escapes .sdlc/evidence"
    assert resolved.exists() is must_exist, f"unexpected evidence path state: {resolved.name}"
    if must_exist:
        assert resolved.is_file() and not resolved.is_symlink()
    return resolved


def zero_values(mapping: dict[str, object]) -> bool:
    return bool(mapping) and all(isinstance(value, int) and value == 0 for value in mapping.values())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ticket", required=True, choices=sorted(TICKETS))
    parser.add_argument("--decision", required=True, type=Path)
    parser.add_argument("--outcome", required=True, type=Path)
    parser.add_argument("--predecessor", action="append", default=[], type=Path)
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    repository = args.repository.resolve(strict=True)
    decision_path = require_evidence_path(repository, args.decision)
    outcome_path = require_evidence_path(repository, args.outcome)
    output_path = require_evidence_path(repository, args.output, must_exist=False)
    decision, decision_identity = read_envelope(
        decision_path,
        "renderweave-r2r5-trigger-decision-envelope/1.0",
        "decision",
        "decisionIdentity",
        DECISION_PREFIX,
    )
    outcome, outcome_identity = read_envelope(
        outcome_path,
        "renderweave-offline-repair-terminal-outcome-envelope/1.0",
        "outcome",
        "outcomeIdentity",
        OUTCOME_PREFIX,
    )

    challenger_id, disposition, reason_code = TICKETS[args.ticket]
    assert decision["overallDisposition"] == "STOP_TO_SPEC_R5"
    routes = {route["route"]: route for route in decision["routes"]}
    assert routes["R5"]["triggerSatisfied"] is True
    assert routes["R5"]["disposition"] == "TRIGGERED"
    assert zero_values(decision["externalProviderUsage"])

    supporting_identities: list[str]
    if challenger_id is not None:
        assert args.predecessor == []
        catalog_path = repository / "renderweave-inference" / "src" / "main" / "resources" \
            / "visual-eval" / "quality-repair" / "challenger-capabilities-v1.json"
        catalog_bytes = catalog_path.read_bytes().replace(b"\r\n", b"\n")
        assert b"\r" not in catalog_bytes
        catalog_identity = CATALOG_PREFIX + sha256(catalog_bytes)
        catalog = json.loads(catalog_bytes)
        capability = next(item for item in catalog["challengers"]
                          if item["challengerId"] == challenger_id)
        assert capability["admissionDisposition"] == "NOT_ADMITTED"
        assert capability["executable"] is False
        capability_identity = CAPABILITY_PREFIX + framed_identity([catalog_identity, challenger_id])
        supporting_identities = sorted([catalog_identity, capability_identity])
    else:
        expected_tickets = EXPECTED_PREDECESSORS[args.ticket]
        assert len(args.predecessor) == len(expected_tickets)
        predecessors = [read_envelope(
            require_evidence_path(repository, path),
            "renderweave-offline-repair-terminal-outcome-envelope/1.0",
            "outcome",
            "outcomeIdentity",
            OUTCOME_PREFIX,
        ) for path in args.predecessor]
        assert {payload["ticket"] for payload, _ in predecessors} == expected_tickets
        assert all(payload["rootDecisionIdentity"] == decision_identity
                   and payload["rootDisposition"] == "STOP_TO_SPEC_R5"
                   and zero_values(payload["offlineWorkUsage"])
                   and zero_values(payload["externalProviderUsage"])
                   for payload, _ in predecessors)
        supporting_identities = sorted(identity for _, identity in predecessors)

    assert outcome["contractVersion"] == "OfflineRepairTerminalOutcome/1.0"
    assert outcome["ticket"] == args.ticket
    assert outcome["rootDecisionIdentity"] == decision_identity
    assert outcome["rootDisposition"] == "STOP_TO_SPEC_R5"
    assert outcome["supportingIdentities"] == supporting_identities
    assert outcome["disposition"] == disposition
    assert outcome["reasonCode"] == reason_code
    assert zero_values(outcome["offlineWorkUsage"])
    assert zero_values(outcome["externalProviderUsage"])

    combined = (decision_path.read_text(encoding="utf-8")
                + outcome_path.read_text(encoding="utf-8")
                + "".join(require_evidence_path(repository, path).read_text(encoding="utf-8")
                          for path in args.predecessor)).lower()
    assert all(token.lower() not in combined for token in FORBIDDEN)
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=repository, check=True,
        capture_output=True, text=True,
    ).stdout.strip()
    summary = {
        "apiKeyReads": 0,
        "artifactAcquisitions": 0,
        "devCasesExecuted": 0,
        "externalProviderAttempts": 0,
        "externalProviderCostMicrosCny": 0,
        "externalProviderReservations": 0,
        "holdoutCasesAccessed": 0,
        "outcomeIdentity": outcome_identity,
        "payloadSafe": True,
        "repositoryRevision": revision,
        "status": "PASS",
        "ticket": args.ticket,
        "verificationLevel": "A2",
    }
    output_path.write_bytes(canonical(summary))
    print(f"{args.ticket}: PASS; A2; zero work; ProviderAttempts=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
