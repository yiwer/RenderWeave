#!/usr/bin/env python3
"""Independently verify a payload-safe fail-closed offline ticket outcome."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

import offline_quality_resources as quality_resources
from offline_json_contract import payload_safe


OUTCOME_PREFIX = "renderweave-offline-repair-terminal-outcome/1.0:"
DECISION_PREFIX = "renderweave-r2r5-trigger-decision/1.0:"
CATALOG_PREFIX = "renderweave-challenger-capability-catalog/1.0:"
CAPABILITY_PREFIX = "renderweave-challenger-capability/1.0:"
AUTHORITATIVE_DECISION_IDENTITY = (
    "renderweave-r2r5-trigger-decision/1.0:"
    "78bc4caa26a37a966530f451a6aab54397ed4cbadfca6674b9f3833a7ac2ea68"
)
AUTHORITATIVE_EVIDENCE_PACK_IDENTITY = (
    "renderweave-frozen-quality-evidence-pack/1.0:"
    "d765aa3d6a9c5e3aa356097092d09c446eaf6506533b9a28ea0443c7405bbab1"
)

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
    "VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY": (
        None,
        "BLOCKED_BY_PREDECESSOR",
        "R2_QUALIFIED_REPAIR_UNAVAILABLE",
    ),
    "VRQ_13_INDEPENDENT_A2_ADMISSION": (
        None,
        "BLOCKED_BY_PREDECESSOR",
        "IMAGE_ONLY_REPLAY_UNAVAILABLE",
    ),
    "VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY": (
        None,
        "LIVE_J1_REQUEST_NOT_ELIGIBLE",
        "INDEPENDENT_OFFLINE_ADMISSION_UNAVAILABLE",
    ),
}

EXPECTED_PREDECESSORS = {
    "VRQ_10_SOLE_DEV_WINNER_SELECTION": {
        "VRQ_08_PP_STRUCTUREV3_DEV_SHADOW",
        "VRQ_09_TESSERACT_DEV_BASELINE",
    },
    "VRQ_11_WINNER_HOLDOUT": {"VRQ_10_SOLE_DEV_WINNER_SELECTION"},
    "VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY": {"VRQ_11_WINNER_HOLDOUT"},
    "VRQ_13_INDEPENDENT_A2_ADMISSION": {"VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY"},
    "VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY": {
        "VRQ_13_INDEPENDENT_A2_ADMISSION"
    },
}

OUTCOME_FIELDS = {
    "contractVersion", "ticket", "rootDecisionIdentity", "rootDisposition",
    "supportingIdentities", "disposition", "reasonCode", "offlineWorkUsage",
    "externalProviderUsage",
}
OFFLINE_WORK_FIELDS = {
    "artifactAcquisitions", "devCasesExecuted", "holdoutCasesAccessed",
    "scriptedWorkflowReplays", "independentAdmissionReplays", "productWrites",
    "apiKeyReads",
}
PROVIDER_USAGE_FIELDS = {"attempts", "reservations", "costMicrosCny"}
CATALOG_IDENTITY_PATTERN = re.compile(r"renderweave-challenger-capability-catalog/1\.0:[0-9a-f]{64}")
CAPABILITY_IDENTITY_PATTERN = re.compile(r"renderweave-challenger-capability/1\.0:[0-9a-f]{64}")
OUTCOME_IDENTITY_PATTERN = re.compile(r"renderweave-offline-repair-terminal-outcome/1\.0:[0-9a-f]{64}")

FORBIDDEN = (
    '"ocrText"', '"ocr_text"', '"imageBytes"', '"promptText"',
    '"providerRequest"', '"providerResponse"', '"modelOutput"',
    '"candidateJson"', '"boundingBox"', '"rootDocument"', '"base64"',
    '"inspectionRequest"', "data:image", "ignore prior instructions", "bearer ",
)


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def require(condition: bool, code: str) -> None:
    if not condition:
        fail(code)


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            fail("OFFLINE_TERMINAL_DUPLICATE_MEMBER")
        result[key] = value
    return result


def strict_json(raw: bytes) -> object:
    try:
        text = raw.decode("utf-8", errors="strict")
        value, end = json.JSONDecoder(
            object_pairs_hook=unique_object,
            parse_constant=lambda _value: fail("OFFLINE_TERMINAL_JSON_INVALID"),
        ).raw_decode(text)
        require(not text[end:].strip(), "OFFLINE_TERMINAL_TRAILING_JSON")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError("OFFLINE_TERMINAL_JSON_INVALID") from error


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def read_envelope(
    path: Path,
    envelope_version: str,
    payload_key: str,
    identity_key: str,
    prefix: str,
) -> tuple[object, str, bytes]:
    raw = path.read_bytes()
    require(0 < len(raw) <= 4 * 1024 * 1024, "OFFLINE_TERMINAL_ENVELOPE_BYTES_INVALID")
    value = strict_json(raw)
    require(isinstance(value, dict), "OFFLINE_TERMINAL_ENVELOPE_INVALID")
    require(raw == canonical(value), "OFFLINE_TERMINAL_NON_CANONICAL_JSON")
    require(set(value) == {"envelopeVersion", identity_key, payload_key},
            "OFFLINE_TERMINAL_ENVELOPE_MEMBERS_INVALID")
    require(value["envelopeVersion"] == envelope_version,
            "OFFLINE_TERMINAL_ENVELOPE_VERSION_INVALID")
    expected = prefix + sha256(canonical(value[payload_key]))
    require(value[identity_key] == expected, "OFFLINE_TERMINAL_ENVELOPE_IDENTITY_DRIFT")
    return value[payload_key], expected, raw


def require_evidence_path(repository: Path, candidate: Path, must_exist: bool = True) -> Path:
    evidence_root = (repository / ".sdlc" / "evidence").resolve(strict=True)
    resolved_parent = candidate.resolve(strict=False).parent.resolve(strict=True)
    resolved = resolved_parent / candidate.name
    require(evidence_root in resolved.parents, "OFFLINE_TERMINAL_EVIDENCE_PATH_ESCAPE")
    require(resolved.exists() is must_exist, "OFFLINE_TERMINAL_EVIDENCE_PATH_STATE_INVALID")
    if must_exist:
        require(resolved.is_file() and not resolved.is_symlink(),
                "OFFLINE_TERMINAL_EVIDENCE_FILE_INVALID")
    return resolved


def zero_values(mapping: dict[str, object]) -> bool:
    return bool(mapping) and all(type(value) is int and value == 0 for value in mapping.values())


def require_outcome_shape(value: object) -> None:
    require(isinstance(value, dict) and set(value) == OUTCOME_FIELDS,
            "OFFLINE_TERMINAL_OUTCOME_MEMBERS_INVALID")
    require(isinstance(value["supportingIdentities"], list)
            and all(type(item) is str for item in value["supportingIdentities"]),
            "OFFLINE_TERMINAL_OUTCOME_SUPPORT_TYPE_INVALID")
    work = value["offlineWorkUsage"]
    provider = value["externalProviderUsage"]
    require(isinstance(work, dict) and set(work) == OFFLINE_WORK_FIELDS,
            "OFFLINE_TERMINAL_WORK_USAGE_MEMBERS_INVALID")
    require(isinstance(provider, dict) and set(provider) == PROVIDER_USAGE_FIELDS,
            "OFFLINE_TERMINAL_PROVIDER_USAGE_MEMBERS_INVALID")


def require_payload_safe(value: object) -> None:
    require(payload_safe(value), "OFFLINE_TERMINAL_DECODED_PAYLOAD_FORBIDDEN")


def require_outcome_semantics(
    value: object,
    expected_ticket: str,
    decision_identity: str,
) -> None:
    require_outcome_shape(value)
    require(expected_ticket in TICKETS, "OFFLINE_TERMINAL_OUTCOME_SEMANTICS_INVALID")
    challenger_id, disposition, reason_code = TICKETS[expected_ticket]
    require(value["contractVersion"] == "OfflineRepairTerminalOutcome/1.0"
            and value["ticket"] == expected_ticket
            and value["rootDecisionIdentity"] == decision_identity
            and value["rootDisposition"] == "STOP_TO_SPEC_R5"
            and value["disposition"] == disposition
            and value["reasonCode"] == reason_code
            and zero_values(value["offlineWorkUsage"])
            and zero_values(value["externalProviderUsage"]),
            "OFFLINE_TERMINAL_OUTCOME_SEMANTICS_INVALID")
    supporting = value["supportingIdentities"]
    require(supporting == sorted(supporting) and len(supporting) == len(set(supporting)),
            "OFFLINE_TERMINAL_OUTCOME_SEMANTICS_INVALID")
    if challenger_id is not None:
        valid_support = len(supporting) == 2 \
            and sum(CATALOG_IDENTITY_PATTERN.fullmatch(item) is not None for item in supporting) == 1 \
            and sum(CAPABILITY_IDENTITY_PATTERN.fullmatch(item) is not None for item in supporting) == 1
    elif expected_ticket == "VRQ_10_SOLE_DEV_WINNER_SELECTION":
        valid_support = len(supporting) == 2 \
            and all(OUTCOME_IDENTITY_PATTERN.fullmatch(item) is not None for item in supporting)
    else:
        valid_support = len(supporting) == 1 \
            and OUTCOME_IDENTITY_PATTERN.fullmatch(supporting[0]) is not None
    require(valid_support, "OFFLINE_TERMINAL_OUTCOME_SEMANTICS_INVALID")


def main() -> int:
    require(__debug__, "OFFLINE_TERMINAL_OPTIMIZED_MODE_FORBIDDEN")
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
    decision, decision_identity, decision_raw = read_envelope(
        decision_path,
        "renderweave-r2r5-trigger-decision-envelope/1.0",
        "decision",
        "decisionIdentity",
        DECISION_PREFIX,
    )
    outcome, outcome_identity, outcome_raw = read_envelope(
        outcome_path,
        "renderweave-offline-repair-terminal-outcome-envelope/1.0",
        "outcome",
        "outcomeIdentity",
        OUTCOME_PREFIX,
    )
    require(isinstance(decision, dict), "OFFLINE_TERMINAL_DECISION_CONTRACT_INVALID")
    require_payload_safe(decision)
    require_payload_safe(outcome)
    require_outcome_semantics(outcome, args.ticket, decision_identity)

    challenger_id, disposition, reason_code = TICKETS[args.ticket]
    require(decision_identity == AUTHORITATIVE_DECISION_IDENTITY,
            "OFFLINE_TERMINAL_DECISION_AUTHORITY_DRIFT")
    require(decision.get("evidencePackIdentity") == AUTHORITATIVE_EVIDENCE_PACK_IDENTITY,
            "OFFLINE_TERMINAL_EVIDENCE_PACK_AUTHORITY_DRIFT")
    require(decision["overallDisposition"] == "STOP_TO_SPEC_R5",
            "OFFLINE_TERMINAL_ROOT_DISPOSITION_INVALID")
    routes = {route["route"]: route for route in decision["routes"]}
    require(routes["R5"]["triggerSatisfied"] is True,
            "OFFLINE_TERMINAL_R5_TRIGGER_INVALID")
    require(routes["R5"]["disposition"] == "TRIGGERED",
            "OFFLINE_TERMINAL_R5_DISPOSITION_INVALID")
    require(zero_values(decision["externalProviderUsage"]),
            "OFFLINE_TERMINAL_DECISION_PROVIDER_USAGE_NONZERO")
    verified_catalog = quality_resources.load_challenger_catalog(repository)
    r2_predicates = {
        predicate["predicateId"]: predicate
        for predicate in routes["R2"]["predicates"]
    }
    require(r2_predicates["R2_CAPABILITY_ADMITTED"]["evidenceReference"]
            == verified_catalog.identity,
            "OFFLINE_TERMINAL_CATALOG_AUTHORITY_DRIFT")

    supporting_identities: list[str]
    captured_raw = [decision_raw, outcome_raw, verified_catalog.raw]
    if challenger_id is not None:
        require(args.predecessor == [], "OFFLINE_TERMINAL_CHALLENGER_PREDECESSOR_INVALID")
        capability = next(item for item in verified_catalog.document["challengers"]
                          if item["challengerId"] == challenger_id)
        require(capability["admissionDisposition"] == "NOT_ADMITTED",
                "OFFLINE_TERMINAL_CAPABILITY_ADMISSION_DRIFT")
        require(capability["executable"] is False,
                "OFFLINE_TERMINAL_CAPABILITY_EXECUTABILITY_DRIFT")
        capability_identity = quality_resources.capability_identity(
            verified_catalog.identity, challenger_id)
        supporting_identities = sorted([verified_catalog.identity, capability_identity])
    else:
        expected_tickets = EXPECTED_PREDECESSORS[args.ticket]
        require(len(args.predecessor) == len(expected_tickets),
                "OFFLINE_TERMINAL_PREDECESSOR_COUNT_INVALID")
        predecessors = [read_envelope(
            require_evidence_path(repository, path),
            "renderweave-offline-repair-terminal-outcome-envelope/1.0",
            "outcome",
            "outcomeIdentity",
            OUTCOME_PREFIX,
        ) for path in args.predecessor]
        captured_raw.extend(raw for _, _, raw in predecessors)
        for predecessor, _, _ in predecessors:
            require_outcome_shape(predecessor)
            predecessor_ticket = predecessor["ticket"]
            require(type(predecessor_ticket) is str and predecessor_ticket in TICKETS,
                    "OFFLINE_TERMINAL_PREDECESSOR_TICKET_INVALID")
            require_payload_safe(predecessor)
            require_outcome_semantics(predecessor, predecessor_ticket, decision_identity)
        require({payload["ticket"] for payload, _, _ in predecessors} == expected_tickets,
                "OFFLINE_TERMINAL_PREDECESSOR_TICKET_SET_INVALID")
        require(all(payload["rootDecisionIdentity"] == decision_identity
                    and payload["rootDisposition"] == "STOP_TO_SPEC_R5"
                    and zero_values(payload["offlineWorkUsage"])
                    and zero_values(payload["externalProviderUsage"])
                    for payload, _, _ in predecessors),
                "OFFLINE_TERMINAL_PREDECESSOR_AUTHORITY_INVALID")
        supporting_identities = sorted(identity for _, identity, _ in predecessors)

    require(outcome["contractVersion"] == "OfflineRepairTerminalOutcome/1.0",
            "OFFLINE_TERMINAL_OUTCOME_VERSION_INVALID")
    require(outcome["ticket"] == args.ticket, "OFFLINE_TERMINAL_OUTCOME_TICKET_INVALID")
    require(outcome["rootDecisionIdentity"] == decision_identity,
            "OFFLINE_TERMINAL_OUTCOME_ROOT_IDENTITY_INVALID")
    require(outcome["rootDisposition"] == "STOP_TO_SPEC_R5",
            "OFFLINE_TERMINAL_OUTCOME_ROOT_DISPOSITION_INVALID")
    require(outcome["supportingIdentities"] == supporting_identities,
            "OFFLINE_TERMINAL_OUTCOME_SUPPORT_INVALID")
    require(outcome["disposition"] == disposition,
            "OFFLINE_TERMINAL_OUTCOME_DISPOSITION_INVALID")
    require(outcome["reasonCode"] == reason_code,
            "OFFLINE_TERMINAL_OUTCOME_REASON_INVALID")
    require(zero_values(outcome["offlineWorkUsage"]),
            "OFFLINE_TERMINAL_OUTCOME_WORK_USAGE_NONZERO")
    require(zero_values(outcome["externalProviderUsage"]),
            "OFFLINE_TERMINAL_OUTCOME_PROVIDER_USAGE_NONZERO")

    combined = b"\n".join(captured_raw).lower()
    require(all(token.lower().encode("utf-8") not in combined for token in FORBIDDEN),
            "OFFLINE_TERMINAL_PAYLOAD_FORBIDDEN")
    if args.ticket == "VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY":
        historical = (
            "n7-04-plus-canary-product-v45-20260814e",
            "renderweave-n7-live-ticket-contract/1.0:",
            "renderweave-visual-evaluation-tree-sha256/2:",
        )
        require(all(value.encode("utf-8") not in combined for value in historical),
                "OFFLINE_TERMINAL_HISTORICAL_IDENTITY_REUSED")
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=repository, check=True,
        capture_output=True, text=True,
    ).stdout.strip()
    require(re.fullmatch(r"[0-9a-f]{40}", revision) is not None,
            "OFFLINE_TERMINAL_REPOSITORY_REVISION_INVALID")
    summary = {
        "apiKeyReads": 0,
        "artifactAcquisitions": 0,
        "authorizationCreated": False,
        "devCasesExecuted": 0,
        "externalProviderAttempts": 0,
        "externalProviderCostMicrosCny": 0,
        "externalProviderReservations": 0,
        "holdoutCasesAccessed": 0,
        "historicalIdentityReuse": False,
        "liveRequestEligible": False,
        "outcomeIdentity": outcome_identity,
        "payloadSafe": True,
        "repositoryRevision": revision,
        "requestEnvelopeCreated": False,
        "status": "PASS",
        "ticket": args.ticket,
        "contractVerification": "A2_CROSS_IMPLEMENTATION_RECONSTRUCTION",
        "executionAccountingVerification": "A1_TOOL_CAPTURED",
        "verificationLevel": "MIXED_A2_CONTRACT_A1_ACCOUNTING",
    }
    output_path.write_bytes(canonical(summary))
    print(f"{args.ticket}: PASS; mixed A2 contract/A1 accounting; zero work; ProviderAttempts=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
