#!/usr/bin/env python3
"""Independent, read-only and payload-safe verifier for N7 G-LIVE admission."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
from typing import Any

import verify_layered_evaluation as layered
import verify_n7_qualification_protocol as protocol_verifier
import verify_visual_eval_evidence as visual


VERIFIER_VERSION = "renderweave-n7-live-admission-verifier/1.0"
CONTRACT_VERSION = "renderweave-n7-live-ticket-contract/1.0"
CONTRACT_PATH = pathlib.PurePosixPath(
    "renderweave-app/src/test/resources/visual-eval/n7/live-contracts/n7-04-plus-canary.json"
)
PROTOCOL_PATH = protocol_verifier.RESOURCE
PROFILE_PATH = pathlib.PurePosixPath(
    "renderweave-inference/src/main/resources/inference-profiles/"
    "dashscope-qwen37-plus-product-v45-hybrid-generic.json"
)
GOAL_PATH = pathlib.PurePosixPath(
    ".sdlc/evidence/renderweave-visual-recognition-vnext-20260810"
)
REANCHOR_PATH = pathlib.PurePosixPath(
    "renderweave-app/src/test/resources/visual-eval/n7/goal-authority/"
    "n7-closeout-successor-20260813.json"
)
SUCCESSOR_GOAL_VERSION = "renderweave-visual-evaluation-goal-budget/2.0"
SUCCESSOR_GUARD_VERSION = "renderweave-visual-evaluation-goal-guard/5.0"
AUTHORITY_EPOCH_VERSION = "renderweave-visual-evaluation-goal-authority-epoch/1.0"
BASELINE_VERSION = "renderweave-visual-evaluation-goal-baseline/1.0"
REANCHOR_VERSION = "renderweave-visual-evaluation-goal-reanchor/1.0"
SUCCESSOR_AUTHORITY_EPOCH_ID = "n7-closeout-successor-20260813"
REANCHOR_MANIFEST_SHA256 = (
    "541f5efd137cd13009db5b722584c1353c1d3f6b0de39685ef161a1e3696efaa"
)
LEDGERS = {
    "qwen37-flash": pathlib.PurePosixPath(".sdlc/live/visual-evaluation-qwen37-flash.json"),
    "qwen37-plus": pathlib.PurePosixPath(".sdlc/live/visual-evaluation-qwen37-plus.json"),
    "qwen38-max": pathlib.PurePosixPath(".sdlc/live/visual-evaluation-qwen38-max.json"),
}
EXPECTED_CASES = [
    "transit-board-v3", "restaurant-menu-v2", "invoice-lines-v4",
    "building-directory-v1", "low-information-poster-v3",
]
GOAL_LIMITS = {
    "qwen3.8-max": {"attempts": 180, "tokens": 1_500_000, "cost": 18_000_000},
    "qwen3.7-plus": {"attempts": 180, "tokens": 1_500_000, "cost": 10_000_000},
    "qwen3.7-flash": {"attempts": 180, "tokens": 1_500_000, "cost": 10_000_000},
}
SUCCESSOR_EPOCH_LIMITS = {
    "maximumTokensPerModel": 500_000,
    "maximumAttemptsPerModel": 180,
    "maximumCostMicrosCnyByModel": {
        "qwen3.8-max": 18_000_000,
        "qwen3.7-plus": 10_000_000,
        "qwen3.7-flash": 10_000_000,
    },
}
MODEL_SLOTS = {
    "qwen3.8-max": "qwen3.8-max",
    "qwen3.7-plus": "qwen3.7-plus",
    "qwen3.7-flash": "qwen3.7-flash",
    "qwen3.7-flash-2026-07-15": "qwen3.7-flash",
}
EXPECTED_BASELINE = {
    "baselineVersion": BASELINE_VERSION,
    "totalReservations": 418,
    "settledReservations": 412,
    "quarantinedChargedReservations": 6,
    "breachedReservations": 0,
    "slots": {
        "qwen3.8-max": {
            "attempts": 82, "exposedTokens": 491_919,
            "exposedCostMicrosCny": 10_289_316, "settledReservations": 82,
            "quarantinedChargedReservations": 0, "breachedReservations": 0,
        },
        "qwen3.7-plus": {
            "attempts": 179, "exposedTokens": 1_087_500,
            "exposedCostMicrosCny": 4_159_620, "settledReservations": 174,
            "quarantinedChargedReservations": 5, "breachedReservations": 0,
        },
        "qwen3.7-flash": {
            "attempts": 157, "exposedTokens": 1_148_324,
            "exposedCostMicrosCny": 560_618, "settledReservations": 156,
            "quarantinedChargedReservations": 1, "breachedReservations": 0,
        },
    },
}
EXPECTED_SOURCE_ANCHORS = [
    {
        "path": "docs/adr/0029-pinned-flash-snapshot-and-additive-goal-budget.md",
        "sha256": "cce89516cf03f39291af26cbd1593be580f3e4eec1070867d9c1338bf9ff7d3a",
    },
    {
        "path": "plans/renderweave-visual-recognition-vnext-plan.md",
        "sha256": "643c386caf07b26d4d71fd964d5e7c1a3b531f84e2049d4b67ba64c00143635e",
    },
    {
        "path": "plans/renderweave-v1-plan.md",
        "sha256": "f892c3d8499b7165bda30e7ad7a4d029f0ef47e84f072770d8d1e05c00783ee3",
    },
    {
        "path": "plans/logs/P6-T6-5-N7.md",
        "sha256": "552d72ad303e0f3502d7e2d2fa6a5057a6d13557a9dc9065209984cc9664dac8",
    },
]


class VerificationError(Exception):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def read_json(path: pathlib.Path, payload_free: bool = True) -> tuple[Any, bytes]:
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8", errors="strict")
    except (OSError, UnicodeError):
        fail("INPUT_UNAVAILABLE")
    if payload_free:
        layered.scan_payload_free(text, path.name)
    return layered.parse_strict_json(text), raw


def require_exact_keys(value: dict[str, Any], keys: tuple[str, ...], code: str) -> None:
    if not isinstance(value, dict) or tuple(value) != keys:
        fail(code)


def normalized_lf_sha256(raw: bytes) -> str:
    normalized = raw.replace(b"\r\n", b"\n")
    if b"\r" in normalized:
        fail("LINE_ENDING_INVALID")
    return hashlib.sha256(normalized).hexdigest()


def verify_reanchor_manifest(repository: pathlib.Path) -> tuple[dict[str, Any], str]:
    manifest, raw = read_json(repository.joinpath(*REANCHOR_PATH.parts))
    require_exact_keys(manifest, (
        "reanchorVersion", "goalId", "authorityEpochId", "predecessorEpochId",
        "predecessorDisposition", "anchorRevision", "sourceAnchors", "baseline",
        "epochLimits", "decision",
    ), "REANCHOR_SHAPE_DRIFT")
    identity = normalized_lf_sha256(raw)
    if identity != REANCHOR_MANIFEST_SHA256:
        fail("REANCHOR_IDENTITY_DRIFT")
    expected = {
        "reanchorVersion": REANCHOR_VERSION,
        "goalId": visual.GOAL_ID,
        "authorityEpochId": SUCCESSOR_AUTHORITY_EPOCH_ID,
        "predecessorEpochId": "legacy-through-product-v40",
        "predecessorDisposition": "LOST_UNRECOVERABLE",
        "anchorRevision": "e3230398b7d6978d93527813af29df98fa7b35e6",
        "sourceAnchors": EXPECTED_SOURCE_ANCHORS,
        "baseline": EXPECTED_BASELINE,
        "epochLimits": SUCCESSOR_EPOCH_LIMITS,
        "decision": {
            "status": "APPROVED", "approvedOn": "2026-08-13",
            "scope": "SUCCESSOR_AUTHORITY_REANCHOR_ONLY",
            "liveAuthorizationInherited": False, "refundCreated": False,
        },
    }
    if manifest != expected:
        fail("REANCHOR_VALUE_DRIFT")
    for anchor in manifest["sourceAnchors"]:
        try:
            completed = subprocess.run(
                ["git", "show", f'{manifest["anchorRevision"]}:{anchor["path"]}'],
                cwd=repository, check=True, capture_output=True,
            )
        except (OSError, subprocess.CalledProcessError):
            fail("REANCHOR_SOURCE_UNAVAILABLE")
        if hashlib.sha256(completed.stdout).hexdigest() != anchor["sha256"]:
            fail("REANCHOR_SOURCE_DRIFT")
    return manifest, identity


def profile_snapshot(profile: dict[str, Any]) -> str:
    if tuple(profile) != protocol_verifier.PROFILE_FIELDS:
        fail("PROFILE_COMPONENT_ORDER_DRIFT")
    encoded = json.dumps(
        {field: profile[field] for field in protocol_verifier.PROFILE_FIELDS},
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def verify_contract(repository: pathlib.Path) -> tuple[dict[str, Any], str]:
    contract, raw = read_json(repository.joinpath(*CONTRACT_PATH.parts))
    keys = (
        "contractVersion", "ticketId", "authorizationId", "lifecycle", "provider", "model",
        "profileId", "profileSnapshotSha256", "pipelineVersion", "candidatePromptVersion",
        "elementPromptVersion", "hierarchyPromptVersion", "bindingPromptVersion",
        "corpusVersion", "corpusIdentity", "corpusSourceSha256",
        "qualificationProtocolIdentity", "assignmentIdentity", "evaluatorIdentity", "caseIds",
        "inputClassification", "maximumProviderAttempts", "maximumTotalTokens",
        "maximumCostMicrosCny", "maximumCasesPerBatch",
        "maximumAuthorizationWindowSeconds", "executionMode", "holdoutAccess",
    )
    require_exact_keys(contract, keys, "CONTRACT_SHAPE_DRIFT")
    protocol_summary = protocol_verifier.verify_protocol(repository)
    profile, _ = read_json(repository.joinpath(*PROFILE_PATH.parts), payload_free=False)
    expected = {
        "contractVersion": CONTRACT_VERSION,
        "ticketId": "N7-04",
        "authorizationId": "n7-04-plus-canary-product-v45-20260813",
        "lifecycle": "PROPOSED_NOT_OPEN",
        "provider": "DASHSCOPE",
        "model": "qwen3.7-plus",
        "profileId": "dashscope-qwen37-plus-product-v45-hybrid-generic",
        "profileSnapshotSha256": profile_snapshot(profile),
        "pipelineVersion": "renderweave-inference-pipeline/4.28",
        "candidatePromptVersion": "renderweave-schema-candidate-prompt/5.0",
        "elementPromptVersion": "renderweave-visual-elements-prompt/12.0",
        "hierarchyPromptVersion": "renderweave-visual-hierarchy-prompt/7.0",
        "bindingPromptVersion": "renderweave-visual-bindings-prompt/4.0",
        "corpusVersion": "renderweave-visual-stage-corpus/2.0",
        "corpusIdentity": "renderweave-visual-stage-corpus/2.0:"
                          "c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c",
        "corpusSourceSha256": "ca53d88763af161a1b1b22fa50774c56eae929affe5316157ae355fdb005b8b3",
        "qualificationProtocolIdentity": protocol_summary["protocolIdentity"],
        "assignmentIdentity": protocol_summary["canaryAssignmentIdentity"],
        "evaluatorIdentity": "renderweave-layered-evaluator/1.0:"
                             "3d880bc058c6ff0f6a66a7ee88cf7342416200f5da67a2210c5676d65b6b38d0",
        "caseIds": EXPECTED_CASES,
        "inputClassification": "REPOSITORY_SYNTHETIC_ONLY",
        "maximumProviderAttempts": 35,
        "maximumTotalTokens": 500_000,
        "maximumCostMicrosCny": 5_000_000,
        "maximumCasesPerBatch": 5,
        "maximumAuthorizationWindowSeconds": 86_400,
        "executionMode": "SERIAL",
        "holdoutAccess": False,
    }
    if contract != expected:
        fail("CONTRACT_VALUE_DRIFT")
    normalized = raw.replace(b"\r\n", b"\n")
    if b"\r" in normalized:
        fail("CONTRACT_LINE_ENDING_INVALID")
    identity = f"{CONTRACT_VERSION}:{hashlib.sha256(normalized).hexdigest()}"
    return contract, identity


def validate_ledger_shape(value: dict[str, Any]) -> None:
    keys = (
        "authorizationVersion", "authorizationId", "status", "phase", "inputClassification",
        "corpusVersion", "corpusSourceSha256", "evaluationIdentity", "profileId",
        "profileSnapshotSha256", "model", "caseIds", "maximumProviderAttempts",
        "maximumTotalTokens", "maximumCostMicrosCny", "maximumCasesPerBatch", "approvedBy",
        "approvedAt", "expiresAt", "approvalScope",
    )
    require_exact_keys(value, keys, "LEDGER_SHAPE_DRIFT")
    if value["authorizationVersion"] != visual.AUTH_VERSION:
        fail("LEDGER_VERSION_DRIFT")


def verify_ledgers(
    repository: pathlib.Path, contract: dict[str, Any], contract_identity: str,
) -> dict[str, Any]:
    paths = [repository.joinpath(*path.parts) for path in LEDGERS.values()]
    repository_identity, _tracked = visual.repository_identity(repository, paths)
    ledgers: dict[str, dict[str, Any]] = {}
    for selector, relative in LEDGERS.items():
        value, _raw = read_json(repository.joinpath(*relative.parts))
        validate_ledger_shape(value)
        ledgers[selector] = value
    statuses = {key: value["status"] for key, value in ledgers.items()}
    plus = ledgers["qwen37-plus"]
    target = plus.get("authorizationId") == contract["authorizationId"]
    if target:
        exact = {
            "status": "PROPOSED", "phase": "CANARY",
            "inputClassification": contract["inputClassification"],
            "corpusVersion": contract["corpusVersion"],
            "corpusSourceSha256": contract["corpusSourceSha256"],
            "evaluationIdentity": repository_identity,
            "profileId": contract["profileId"],
            "profileSnapshotSha256": contract["profileSnapshotSha256"],
            "model": contract["model"], "caseIds": contract["caseIds"],
            "maximumProviderAttempts": contract["maximumProviderAttempts"],
            "maximumTotalTokens": contract["maximumTotalTokens"],
            "maximumCostMicrosCny": contract["maximumCostMicrosCny"],
            "maximumCasesPerBatch": contract["maximumCasesPerBatch"],
            "approvedBy": None, "approvedAt": None, "expiresAt": None,
            "approvalScope": contract_identity,
        }
        for field, expected in exact.items():
            if plus.get(field) != expected:
                fail(f"PROPOSAL_BINDING_DRIFT:{field}")
        if statuses["qwen37-flash"] != "CLOSED" or statuses["qwen38-max"] != "CLOSED":
            fail("CONCURRENT_LEDGER_NOT_CLOSED")
        lifecycle = "PROPOSED_NOT_OPEN"
    else:
        if set(statuses.values()) != {"CLOSED"}:
            fail("HISTORICAL_LEDGER_NOT_CLOSED")
        lifecycle = "PRE_PROPOSAL_ALL_HISTORICAL_CLOSED"
    return {
        "repositoryEvaluationIdentity": repository_identity,
        "statuses": statuses,
        "lifecycle": lifecycle,
        "historicalLedgersClosed": all(
            value["status"] == "CLOSED" for key, value in ledgers.items()
            if key != "qwen37-plus" or not target
        ),
        "targetProposalPresent": target,
    }


def reservation_exposure(value: dict[str, Any]) -> tuple[int, int]:
    state = value.get("state")
    reserved_tokens = value.get("reservedTokens")
    reserved_cost = value.get("reservedCostMicrosCny")
    if not isinstance(reserved_tokens, int) or reserved_tokens < 1 \
            or not isinstance(reserved_cost, int) or reserved_cost < 1:
        fail("GOAL_RESERVATION_BOUND_INVALID")
    if state == "RESERVED":
        if any(value.get(field) is not None for field in (
                "actualInputTokens", "actualOutputTokens", "actualCostMicrosCny")):
            fail("GOAL_RESERVED_ACTUAL_PRESENT")
        return reserved_tokens, reserved_cost
    if state not in ("SETTLED", "BREACHED"):
        fail("GOAL_RESERVATION_STATE_INVALID")
    actual = (value.get("actualInputTokens"), value.get("actualOutputTokens"),
              value.get("actualCostMicrosCny"))
    if any(not isinstance(item, int) or item < 0 for item in actual):
        fail("GOAL_RESERVATION_ACTUAL_INVALID")
    tokens = actual[0] + actual[1]
    cost = actual[2]
    if state == "SETTLED" and (tokens > reserved_tokens or cost > reserved_cost):
        fail("GOAL_SETTLEMENT_EXCEEDS_BOUND")
    return (max(tokens, reserved_tokens), max(cost, reserved_cost)) \
        if state == "BREACHED" else (tokens, cost)


def inspect_goal(
    repository: pathlib.Path,
    contract: dict[str, Any],
    goal_directory: pathlib.Path | None = None,
) -> dict[str, Any]:
    directory = goal_directory or repository.joinpath(*GOAL_PATH.parts)
    state_path = directory / "goal-budget.json"
    guard_path = directory / "goal-budget.guard.json"
    lock_path = directory / "goal-budget.lock"
    existing = [path.is_file() for path in (state_path, guard_path, lock_path)]
    if not any(existing):
        return {
            "authority": "MISSING", "admission": "DENIED_GOAL_AUTHORITY_MISSING",
            "totalReservations": 0, "nonTerminalReservations": 0,
            "epochReservations": 0, "quarantinedChargedReservations": 0,
            "breachedReservations": 0, "slots": {}, "lifetimeSlots": {},
        }
    if not all(existing):
        fail("GOAL_PARTIAL_STATE")
    guard, guard_raw = read_json(guard_path)
    state, state_raw = read_json(state_path)
    if not isinstance(state, dict) or state.get("goalId") != visual.GOAL_ID:
        fail("GOAL_STATE_DRIFT")
    state_version = state.get("stateVersion")
    if state_version == visual.GOAL_VERSION:
        expected_guard = {
            "guardVersion": visual.GOAL_GUARD_VERSION,
            "goalId": visual.GOAL_ID,
            "maximumTokensPerModel": 1_500_000,
            "maximumAttemptsPerModel": 180,
            "maximumCostMicrosCnyByModel": {
                "qwen3.8-max": 18_000_000, "qwen3.7-plus": 10_000_000,
                "qwen3.7-flash": 10_000_000,
            },
        }
        if guard != expected_guard:
            fail("GOAL_GUARD_DRIFT")
        require_exact_keys(state,
                           ("stateVersion", "goalId", "reservations", "createdAt", "updatedAt"),
                           "GOAL_STATE_DRIFT")
        baseline = {
            "totalReservations": 0, "settledReservations": 0,
            "quarantinedChargedReservations": 0, "breachedReservations": 0,
            "slots": {model: {
                "attempts": 0, "exposedTokens": 0, "exposedCostMicrosCny": 0,
                "settledReservations": 0, "quarantinedChargedReservations": 0,
                "breachedReservations": 0,
            } for model in GOAL_LIMITS},
        }
        epoch_limits = {
            "maximumTokensPerModel": 1_500_000,
            "maximumAttemptsPerModel": 180,
            "maximumCostMicrosCnyByModel": {
                model: limits["cost"] for model, limits in GOAL_LIMITS.items()
            },
        }
        authority = "AUTHORITATIVE_LEDGER"
        authority_epoch_id = "legacy-exact-history"
        manifest_identity = None
    elif state_version == SUCCESSOR_GOAL_VERSION:
        manifest, manifest_identity = verify_reanchor_manifest(repository)
        expected_guard = {
            "guardVersion": SUCCESSOR_GUARD_VERSION,
            "goalId": visual.GOAL_ID,
            "authorityEpochId": SUCCESSOR_AUTHORITY_EPOCH_ID,
            "reanchorManifestSha256": manifest_identity,
            "epochMaximumTokensPerModel": 500_000,
            "epochMaximumAttemptsPerModel": 180,
            "epochMaximumCostMicrosCnyByModel":
                SUCCESSOR_EPOCH_LIMITS["maximumCostMicrosCnyByModel"],
        }
        if guard != expected_guard:
            fail("GOAL_GUARD_DRIFT")
        require_exact_keys(state, (
            "stateVersion", "goalId", "authorityEpoch", "historicalBaseline",
            "reservations", "createdAt", "updatedAt",
        ), "GOAL_STATE_DRIFT")
        expected_epoch = {
            "epochVersion": AUTHORITY_EPOCH_VERSION,
            "epochId": SUCCESSOR_AUTHORITY_EPOCH_ID,
            "kind": "CONSERVATIVE_REANCHOR",
            "predecessorEpochId": "legacy-through-product-v40",
            "predecessorDisposition": "LOST_UNRECOVERABLE",
            "reanchorManifestSha256": manifest_identity,
        }
        if state["authorityEpoch"] != expected_epoch \
                or state["historicalBaseline"] != manifest["baseline"]:
            fail("GOAL_REANCHOR_DRIFT")
        baseline = manifest["baseline"]
        epoch_limits = manifest["epochLimits"]
        authority = "SUCCESSOR_AUTHORITY_EPOCH"
        authority_epoch_id = SUCCESSOR_AUTHORITY_EPOCH_ID
    else:
        fail("GOAL_STATE_DRIFT")
    if not isinstance(state.get("reservations"), list):
        fail("GOAL_STATE_DRIFT")
    slots = {key: {"attempts": 0, "tokens": 0, "costMicrosCny": 0, "breached": False}
             for key in GOAL_LIMITS}
    reservation_ids: set[str] = set()
    attempt_ids: set[tuple[str, int]] = set()
    nonterminal = 0
    breached = 0
    for reservation in state["reservations"]:
        if not isinstance(reservation, dict):
            fail("GOAL_RESERVATION_INVALID")
        reservation_id = reservation.get("reservationId")
        attempt_id = (reservation.get("runId"), reservation.get("attemptOrdinal"))
        if not isinstance(reservation_id, str) or reservation_id in reservation_ids \
                or attempt_id in attempt_ids:
            fail("GOAL_RESERVATION_DUPLICATE")
        reservation_ids.add(reservation_id)
        attempt_ids.add(attempt_id)
        model = reservation.get("model")
        if model not in MODEL_SLOTS:
            fail("GOAL_MODEL_INVALID")
        slot = slots[MODEL_SLOTS[model]]
        tokens, cost = reservation_exposure(reservation)
        slot["attempts"] += 1
        slot["tokens"] += tokens
        slot["costMicrosCny"] += cost
        if reservation.get("state") == "RESERVED":
            nonterminal += 1
        if reservation.get("state") == "BREACHED":
            breached += 1
            slot["breached"] = True
    for key, usage in slots.items():
        if not usage["breached"] and (
                usage["attempts"] > epoch_limits["maximumAttemptsPerModel"]
                or usage["tokens"] > epoch_limits["maximumTokensPerModel"]
                or usage["costMicrosCny"]
                > epoch_limits["maximumCostMicrosCnyByModel"][key]):
            fail("GOAL_AGGREGATE_EXCEEDS_LIMIT")
    lifetime_slots = {
        key: {
            "attempts": baseline["slots"][key]["attempts"] + usage["attempts"],
            "tokens": baseline["slots"][key]["exposedTokens"] + usage["tokens"],
            "costMicrosCny": baseline["slots"][key]["exposedCostMicrosCny"]
                             + usage["costMicrosCny"],
            "breached": baseline["slots"][key]["breachedReservations"] > 0
                        or usage["breached"],
        }
        for key, usage in slots.items()
    }
    plus = slots["qwen3.7-plus"]
    capacity = plus["attempts"] + contract["maximumProviderAttempts"] \
        <= epoch_limits["maximumAttemptsPerModel"] \
        and plus["tokens"] + contract["maximumTotalTokens"] \
        <= epoch_limits["maximumTokensPerModel"] \
        and plus["costMicrosCny"] + contract["maximumCostMicrosCny"] \
        <= epoch_limits["maximumCostMicrosCnyByModel"]["qwen3.7-plus"]
    if nonterminal:
        admission = "DENIED_NONTERMINAL_RESERVATION"
    elif breached or baseline["breachedReservations"]:
        admission = "DENIED_BREACHED_RESERVATION"
    elif not capacity:
        admission = "DENIED_INSUFFICIENT_CUMULATIVE_CAPACITY"
    else:
        admission = "GOAL_READY"
    return {
        "authority": authority, "authorityEpochId": authority_epoch_id,
        "admission": admission,
        "totalReservations": baseline["totalReservations"] + len(state["reservations"]),
        "epochReservations": len(state["reservations"]),
        "nonTerminalReservations": nonterminal,
        "quarantinedChargedReservations": baseline["quarantinedChargedReservations"],
        "breachedReservations": baseline["breachedReservations"] + breached,
        "slots": slots, "lifetimeSlots": lifetime_slots, "epochLimits": epoch_limits,
        "reanchorManifestSha256": manifest_identity,
        "stateSha256": hashlib.sha256(state_raw).hexdigest(),
        "guardSha256": hashlib.sha256(guard_raw).hexdigest(),
    }


def verify(repository: pathlib.Path) -> dict[str, Any]:
    contract, contract_identity = verify_contract(repository)
    ledger = verify_ledgers(repository, contract, contract_identity)
    goal = inspect_goal(repository, contract)
    return {
        "verifierVersion": VERIFIER_VERSION,
        "result": "PASS",
        "assurance": "A2_INDEPENDENT_READ_ONLY_RECONSTRUCTION",
        "ticketId": contract["ticketId"],
        "contractIdentity": contract_identity,
        "proposalLifecycle": ledger["lifecycle"],
        "repositoryEvaluationIdentity": ledger["repositoryEvaluationIdentity"],
        "ledgerStatuses": ledger["statuses"],
        "historicalLedgersClosed": ledger["historicalLedgersClosed"],
        "goal": goal,
        "admissionDecision": "NOT_OPEN" if ledger["targetProposalPresent"] else goal["admission"],
        "providerAttemptsCreated": 0,
        "providerReservationsCreated": 0,
        "externalProviderCostMicrosCnyCreated": 0,
        "secretValuesRead": False,
        "payloadStored": False,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        summary = verify(args.repository.resolve(strict=True))
        encoded = json.dumps(summary, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded, encoding="utf-8", newline="\n")
        else:
            sys.stdout.write(encoded)
        return 0
    except (VerificationError, layered.VerificationError, protocol_verifier.VerificationError,
            visual.VerificationError, OSError, UnicodeError, ValueError) as failure:
        sys.stderr.write(f"N7 live admission verification failed: {failure}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
