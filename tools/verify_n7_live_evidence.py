#!/usr/bin/env python3
"""Independent, payload-safe A2 verifier for the exact N7-04 live canary."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
import pathlib
import sys
from typing import Any, Iterator

import verify_layered_evaluation as layered
import verify_n7_live_admission as admission
import verify_visual_eval_evidence as visual


VERIFIER_VERSION = "renderweave-n7-live-evidence-verifier/1.0"
JOURNAL_VERSION = "renderweave-n7-visual-evaluation-journal/2.0"
JOURNAL_GUARD_VERSION = "renderweave-n7-visual-evaluation-journal-guard/2.0"
REPORT_ENVELOPE_VERSION = "renderweave-n7-live-semantic-report-envelope/1.0"
REPORT_VERSION = "renderweave-n7-live-semantic-report/1.0"
SEMANTIC_CORPUS_PATH = pathlib.PurePosixPath(
    "renderweave-inference/src/main/resources/visual-eval/v1/scenes.json"
)
EVIDENCE_FILES = {
    "state.json", "state.guard.json", "report.json", "state.lock", "batch.lock",
}
LIVE_STAGE_ACCEPTANCE = {
    "OBSERVE": "LIVE_VISUAL_GROUNDING_ACCEPTED",
    "HIERARCHY": "LIVE_VISUAL_HIERARCHY_V2_ACCEPTED",
    "ELEMENT_BINDING": "LIVE_VISUAL_BINDINGS_V2_ACCEPTED",
}
LIVE_STAGE_TRANSITIONS = {
    ("OBSERVE", "OBSERVE"),
    ("OBSERVE", "HIERARCHY"),
    ("HIERARCHY", "HIERARCHY"),
    ("HIERARCHY", "OBSERVE"),
    ("HIERARCHY", "ELEMENT_BINDING"),
    ("ELEMENT_BINDING", "ELEMENT_BINDING"),
    ("ELEMENT_BINDING", "HIERARCHY"),
}


class VerificationError(Exception):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def parse_payload_free_json(text: str, source: str) -> dict[str, Any]:
    try:
        layered.scan_payload_free(text, source)
        value = layered.parse_strict_json(text)
    except layered.VerificationError as failure:
        raise VerificationError(str(failure)) from failure
    if type(value) is not dict:
        fail(f"{source}:ROOT_NOT_OBJECT")
    return value


def read_json(path: pathlib.Path, payload_free: bool = True) -> dict[str, Any]:
    try:
        text = path.read_text(encoding="utf-8", errors="strict")
    except (OSError, UnicodeError) as failure:
        raise VerificationError(f"INPUT_UNAVAILABLE:{path.name}") from failure
    if payload_free:
        return parse_payload_free_json(text, path.name)
    try:
        value = layered.parse_strict_json(text)
    except layered.VerificationError as failure:
        raise VerificationError(str(failure)) from failure
    if type(value) is not dict:
        fail(f"{path.name}:ROOT_NOT_OBJECT")
    return value


def require_instant(value: Any, name: str) -> datetime:
    if type(value) is not str or not value:
        fail(f"{name}:INVALID_INSTANT")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as failure:
        raise VerificationError(f"{name}:INVALID_INSTANT") from failure
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        fail(f"{name}:INVALID_INSTANT")
    return parsed.astimezone(timezone.utc)


def require_time_in_authorization(
    value: Any,
    name: str,
    approved: datetime,
    expires: datetime,
) -> datetime:
    parsed = require_instant(value, name)
    if parsed < approved or parsed >= expires:
        fail(f"{name}:OUTSIDE_EXACT_J1_WINDOW")
    return parsed


def validate_stage_trace(
    attempts: list[dict[str, Any]],
    maximum_attempts: int,
    allow_exhausted_failure: bool = False,
) -> None:
    if len(attempts) < len(LIVE_STAGE_ACCEPTANCE) or len(attempts) > maximum_attempts:
        fail("N7_LIVE_STAGE_TRACE_COUNT_INVALID")
    if attempts[0]["stage"] != "OBSERVE":
        fail("N7_LIVE_STAGE_TRACE_MUST_START_AT_OBSERVE")
    for index, attempt in enumerate(attempts):
        stage = attempt["stage"]
        if stage not in LIVE_STAGE_ACCEPTANCE or attempt["attemptOrdinal"] != index:
            fail("N7_LIVE_STAGE_TRACE_IDENTITY_INVALID")
        if index == 0:
            continue
        previous = attempts[index - 1]
        transition = (previous["stage"], stage)
        if transition not in LIVE_STAGE_TRANSITIONS:
            fail("N7_LIVE_STAGE_TRACE_TRANSITION_INVALID")
        accepted = LIVE_STAGE_ACCEPTANCE[previous["stage"]]
        if stage == previous["stage"] and previous["outcomeCode"] == accepted:
            fail("N7_LIVE_ACCEPTED_STAGE_REPEATED")
        if transition in (("OBSERVE", "HIERARCHY"), ("HIERARCHY", "ELEMENT_BINDING")) \
                and previous["outcomeCode"] != accepted:
            fail("N7_LIVE_STAGE_ADVANCED_WITHOUT_ACCEPTANCE")
        if transition in (("HIERARCHY", "OBSERVE"), ("ELEMENT_BINDING", "HIERARCHY")) \
                and previous["outcomeCode"] != "LIVE_VISUAL_ANALYSIS_REJECTED":
            fail("N7_LIVE_STAGE_REWIND_WITHOUT_VALIDATOR_REJECTION")
    final = attempts[-1]
    complete = final["stage"] == "ELEMENT_BINDING" \
        and final["outcomeCode"] == LIVE_STAGE_ACCEPTANCE["ELEMENT_BINDING"]
    if not complete and not allow_exhausted_failure:
        fail("N7_LIVE_STAGE_TRACE_NOT_COMPLETE")
    if not complete and len(attempts) != maximum_attempts:
        fail("N7_LIVE_FAILED_TRACE_NOT_EXHAUSTED")


def validate_closed_authorization(
    value: dict[str, Any],
    contract: dict[str, Any],
    contract_identity: str,
    repository_identity: str,
) -> dict[str, Any]:
    try:
        admission.validate_ledger_shape(value)
    except admission.VerificationError as failure:
        raise VerificationError(str(failure)) from failure
    expected = {
        "authorizationVersion": visual.AUTH_VERSION,
        "authorizationId": contract["authorizationId"],
        "status": "CLOSED",
        "phase": "CANARY",
        "inputClassification": contract["inputClassification"],
        "corpusVersion": contract["corpusVersion"],
        "corpusSourceSha256": contract["corpusSourceSha256"],
        "evaluationIdentity": repository_identity,
        "profileId": contract["profileId"],
        "profileSnapshotSha256": contract["profileSnapshotSha256"],
        "model": contract["model"],
        "caseIds": contract["caseIds"],
        "maximumProviderAttempts": contract["maximumProviderAttempts"],
        "maximumTotalTokens": contract["maximumTotalTokens"],
        "maximumCostMicrosCny": contract["maximumCostMicrosCny"],
        "maximumCasesPerBatch": contract["maximumCasesPerBatch"],
        "approvalScope": contract_identity,
    }
    for field, expected_value in expected.items():
        if value.get(field) != expected_value:
            fail(f"AUTHORIZATION_BINDING_DRIFT:{field}")
    if type(value.get("approvedBy")) is not str or not value["approvedBy"].strip():
        fail("AUTHORIZATION_APPROVER_MISSING")
    approved = require_instant(value.get("approvedAt"), "approvedAt")
    expires = require_instant(value.get("expiresAt"), "expiresAt")
    window = expires - approved
    if window <= timedelta(0) or window > timedelta(
            seconds=contract["maximumAuthorizationWindowSeconds"]):
        fail("AUTHORIZATION_WINDOW_INVALID")
    return value


def validate_n7_journal_guard(
    value: dict[str, Any], authorization: dict[str, Any],
) -> None:
    fields = (
        "guardVersion", "authorizationVersion", "authorizationId", "phase",
        "inputClassification", "corpusVersion", "corpusSourceSha256", "evaluationIdentity",
        "profileId", "profileSnapshotSha256", "model", "caseIds", "maximumProviderAttempts",
        "maximumTotalTokens", "maximumCostMicrosCny", "maximumCasesPerBatch",
    )
    visual.require_keys(value, fields, "N7 journal guard")
    expected = {field: authorization[field] for field in fields if field != "guardVersion"}
    expected["guardVersion"] = JOURNAL_GUARD_VERSION
    if value != expected:
        fail("N7_JOURNAL_GUARD_BINDING_DRIFT")


def validate_n7_journal(
    value: dict[str, Any],
    authorization: dict[str, Any],
    metadata: dict[str, dict[str, str]],
    reservations: list[dict[str, Any]],
    require_review_required: bool = True,
) -> tuple[list[dict[str, Any]], list[str], int]:
    visual.require_keys(
        value, ("journalVersion", "authorizationId", "executions", "createdAt", "updatedAt"),
        "N7 journal",
    )
    if value["journalVersion"] != JOURNAL_VERSION \
            or value["authorizationId"] != authorization["authorizationId"]:
        fail("N7_JOURNAL_IDENTITY_DRIFT")
    approved = require_instant(authorization["approvedAt"], "approvedAt")
    expires = require_instant(authorization["expiresAt"], "expiresAt")
    journal_created = require_time_in_authorization(
        value["createdAt"], "journal.createdAt", approved, expires,
    )
    journal_updated = require_time_in_authorization(
        value["updatedAt"], "journal.updatedAt", approved, expires,
    )
    if journal_updated < journal_created:
        fail("N7_JOURNAL_TIME_ORDER_INVALID")
    executions = visual.require_list(value["executions"], "journal.executions")
    if len(executions) != len(authorization["caseIds"]):
        fail("N7_JOURNAL_CASE_COUNT_INVALID")
    by_reservation = {item["reservationId"]: item for item in reservations}
    auth_reservations = [
        item for item in reservations
        if item["authorizationId"] == authorization["authorizationId"]
    ]
    if any(item["state"] != "SETTLED" for item in auth_reservations):
        fail("N7_AUTHORIZATION_RESERVATION_NOT_SETTLED")
    expected_reservations = {item["reservationId"] for item in auth_reservations}
    linked_reservations: set[str] = set()
    execution_ids: set[str] = set()
    run_ids: set[str] = set()
    results: list[dict[str, Any]] = []
    terminal_states: list[str] = []
    provider_latency_millis = 0
    previous_completed: datetime | None = None
    execution_fields = (
        "assignmentKey", "executionId", "caseId", "profileId", "model", "runId", "status",
        "evaluation", "attempts", "terminalState", "startedAt", "updatedAt", "completedAt",
    )
    for index, (raw, expected_case_id) in enumerate(
            zip(executions, authorization["caseIds"], strict=True)):
        item = visual.require_object(raw, f"execution[{index}]")
        visual.require_keys(item, execution_fields, f"execution[{index}]")
        if item["caseId"] != expected_case_id \
                or item["assignmentKey"] != f'{authorization["profileId"]}|{expected_case_id}' \
                or item["profileId"] != authorization["profileId"] \
                or item["model"] != authorization["model"]:
            fail("N7_EXECUTION_ASSIGNMENT_DRIFT")
        execution_id = visual.require_string(item["executionId"], "executionId", visual.UUID)
        run_id = visual.require_string(item["runId"], "runId", visual.UUID)
        if execution_id in execution_ids or run_id in run_ids:
            fail("N7_EXECUTION_ID_DUPLICATE")
        execution_ids.add(execution_id)
        run_ids.add(run_id)
        allowed_terminals = {"REVIEW_REQUIRED"} if require_review_required \
            else {"REVIEW_REQUIRED", "FAILED"}
        if item["status"] != "COMPLETED" or item["terminalState"] not in allowed_terminals \
                or item["evaluation"] is None or item["completedAt"] is None:
            fail("N7_EXECUTION_TERMINAL_INVALID")
        started = require_time_in_authorization(
            item["startedAt"], f"execution[{index}].startedAt", approved, expires,
        )
        updated = require_time_in_authorization(
            item["updatedAt"], f"execution[{index}].updatedAt", approved, expires,
        )
        completed = require_time_in_authorization(
            item["completedAt"], f"execution[{index}].completedAt", approved, expires,
        )
        if not journal_created <= started <= updated <= completed <= journal_updated:
            fail("N7_EXECUTION_TIME_ORDER_INVALID")
        if previous_completed is not None and started < previous_completed:
            fail("N7_EXECUTION_MODE_SERIAL_VIOLATED")
        previous_completed = completed
        result = visual.evaluation(
            item["evaluation"], metadata, set(authorization["caseIds"]),
        )
        if result["caseId"] != expected_case_id:
            fail("N7_EVALUATION_CASE_DRIFT")
        attempts = [visual.validate_attempt(attempt) for attempt in
                    visual.require_list(item["attempts"], f"execution[{index}].attempts")]
        maximum_attempts = authorization["maximumProviderAttempts"] // len(
            authorization["caseIds"]
        )
        validate_stage_trace(
            attempts, maximum_attempts,
            allow_exhausted_failure=item["terminalState"] == "FAILED",
        )
        if result["providerCalls"] != len(attempts):
            fail("N7_EVALUATION_CALL_COUNT_DRIFT")
        ordinals: set[int] = set()
        for attempt in attempts:
            reservation_id = attempt["reservationId"]
            if reservation_id in linked_reservations or attempt["attemptOrdinal"] in ordinals:
                fail("N7_ATTEMPT_DUPLICATE")
            linked_reservations.add(reservation_id)
            ordinals.add(attempt["attemptOrdinal"])
            reservation = by_reservation.get(reservation_id)
            if reservation is None or reservation["authorizationId"] != authorization["authorizationId"] \
                    or reservation["profileId"] != authorization["profileId"] \
                    or reservation["model"] != authorization["model"] \
                    or reservation["runId"] != run_id \
                    or reservation["attemptOrdinal"] != attempt["attemptOrdinal"] \
                    or reservation["stage"] != attempt["stage"] \
                    or attempt["model"] != authorization["model"] \
                    or reservation["state"] != "SETTLED":
                fail("N7_ATTEMPT_RESERVATION_BINDING_DRIFT")
            observed_usage = (
                attempt["inputTokens"], attempt["outputTokens"], attempt["costMicrosCny"],
            )
            settled_usage = (
                reservation["actualInputTokens"], reservation["actualOutputTokens"],
                reservation["actualCostMicrosCny"],
            )
            if observed_usage != settled_usage:
                fail("N7_ATTEMPT_USAGE_DRIFT")
            reservation_created = require_time_in_authorization(
                reservation["createdAt"], "reservation.createdAt", approved, expires,
            )
            reservation_updated = require_time_in_authorization(
                reservation["updatedAt"], "reservation.updatedAt", approved, expires,
            )
            if not started <= reservation_created <= reservation_updated <= completed:
                fail("N7_RESERVATION_TIME_ORDER_INVALID")
            provider_latency_millis += attempt["latencyMillis"]
        results.append(result)
        terminal_states.append(item["terminalState"])
    if linked_reservations != expected_reservations:
        fail("N7_RESERVATION_LINK_SET_DRIFT")
    attempts = len(auth_reservations)
    tokens = sum(item["actualInputTokens"] + item["actualOutputTokens"]
                 for item in auth_reservations)
    cost = sum(item["actualCostMicrosCny"] for item in auth_reservations)
    if attempts > authorization["maximumProviderAttempts"] \
            or tokens > authorization["maximumTotalTokens"] \
            or cost > authorization["maximumCostMicrosCny"]:
        fail("N7_AUTHORIZATION_BUDGET_EXCEEDED")
    return results, terminal_states, provider_latency_millis


def expected_report(
    contract: dict[str, Any],
    authorization: dict[str, Any],
    results: list[dict[str, Any]],
    metadata: dict[str, dict[str, str]],
    contract_identity: str,
) -> dict[str, Any]:
    if authorization["approvalScope"] != contract_identity:
        fail("N7_REPORT_APPROVAL_SCOPE_DRIFT")
    observed_case_ids = [item["caseId"] for item in results]

    def slices(values: tuple[str, ...], field: str) -> dict[str, Any]:
        return {
            value: visual.aggregate([item for item in results if item[field] == value])
            for value in values
        }

    return {
        "reportVersion": REPORT_VERSION,
        "evaluatorIdentity": contract["evaluatorIdentity"],
        "authorizationId": authorization["authorizationId"],
        "phase": authorization["phase"],
        "repositoryEvaluationIdentity": authorization["evaluationIdentity"],
        "profileId": authorization["profileId"],
        "profileSnapshotSha256": authorization["profileSnapshotSha256"],
        "qualificationProtocolIdentity": contract["qualificationProtocolIdentity"],
        "assignmentIdentity": contract["assignmentIdentity"],
        "corpusVersion": contract["corpusVersion"],
        "corpusIdentity": contract["corpusIdentity"],
        "corpusSourceSha256": contract["corpusSourceSha256"],
        "expectedCaseIds": contract["caseIds"],
        "observedCaseIds": observed_case_ids,
        "complete": observed_case_ids == contract["caseIds"],
        "global": visual.aggregate(results),
        "partitions": slices(("DEV", "HOLDOUT"), "partition"),
        "styles": slices((
            "WIDE_LIGHT", "PORTRAIT_DARK", "COMPACT_DENSE", "LOW_CONTRAST", "HOLDOUT_NOISY",
        ), "style"),
        "domainPacks": slices(("GENERIC", "TRANSIT_BOARD"), "domainPack"),
    }


def report_identity(report: dict[str, Any]) -> str:
    encoded = json.dumps(
        report, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")
    return f"{REPORT_VERSION}:{hashlib.sha256(encoded).hexdigest()}"


def ratio_bps(numerator: int, denominator: int) -> int:
    return 10_000 if denominator == 0 else (numerator * 10_000) // denominator


def validate_report_envelope(
    value: dict[str, Any],
    contract: dict[str, Any],
    authorization: dict[str, Any],
    results: list[dict[str, Any]],
    metadata: dict[str, dict[str, str]],
    contract_identity: str,
    require_quality: bool = True,
) -> dict[str, Any]:
    visual.require_keys(value, ("envelopeVersion", "reportIdentity", "report"), "N7 report envelope")
    report = visual.require_object(value["report"], "N7 report")
    if value["envelopeVersion"] != REPORT_ENVELOPE_VERSION \
            or value["reportIdentity"] != report_identity(report):
        fail("N7_REPORT_IDENTITY_DRIFT")
    expected = expected_report(contract, authorization, results, metadata, contract_identity)
    if report != expected:
        fail("N7_REPORT_RECOMPUTATION_DRIFT")
    if not report["complete"] or report["observedCaseIds"] != contract["caseIds"] \
            or len(results) != 5:
        fail("N7_REPORT_ASSIGNMENT_INCOMPLETE")
    final = report["global"]["finalCandidate"]
    case_count = report["global"]["caseCount"]
    average_bundle = final["bundleContractBpsSum"] // case_count
    evidence_coverage = ratio_bps(final["evidencePresent"], final["evidenceExpected"])
    average_dag = final["dagValidityBpsSum"] // case_count
    quality_passed = not (
        report["global"]["passedCandidateCases"] != 5 or final["passedCases"] != 5 \
            or average_bundle != 10_000 or evidence_coverage != 10_000 \
            or average_dag != 10_000 or final["criticalHallucinations"] != 0 \
            or final["blockers"] != 0
    )
    if require_quality and not quality_passed:
        fail("N7_CANARY_QUALITY_GATE_FAILED")
    return {
        "qualityDecision": "PASS" if quality_passed else "FAIL",
        "averageBundleContractBps": average_bundle,
        "evidenceCoverageBps": evidence_coverage,
        "averageDagValidityBps": average_dag,
        "criticalHallucinations": final["criticalHallucinations"],
        "blockers": final["blockers"],
        "passedCandidateCases": report["global"]["passedCandidateCases"],
        "passedFinalCandidateCases": final["passedCases"],
        "reportIdentity": value["reportIdentity"],
    }


def audit_gate_summary(
    quality: dict[str, Any], terminal_states: list[str],
) -> dict[str, Any]:
    terminal_passed = all(state == "REVIEW_REQUIRED" for state in terminal_states)
    report_passed = quality["qualityDecision"] == "PASS"
    failures: list[str] = []
    if not terminal_passed:
        failures.append("N7_EXECUTION_TERMINAL_INVALID")
    if not report_passed:
        failures.append("N7_CANARY_QUALITY_GATE_FAILED")
    return {
        **quality,
        "qualityDecision": "PASS" if terminal_passed and report_passed else "FAIL",
        "terminalDecision": "PASS" if terminal_passed else "FAIL",
        "reportQualityDecision": "PASS" if report_passed else "FAIL",
        "gateFailureCodes": failures,
    }


def validate_evidence_directory(directory: pathlib.Path) -> None:
    if not directory.is_dir() or directory.is_symlink():
        fail("N7_EVIDENCE_DIRECTORY_UNAVAILABLE")
    entries = list(directory.iterdir())
    names = {entry.name for entry in entries}
    if names != EVIDENCE_FILES:
        fail("N7_EVIDENCE_FILE_SET_INVALID")
    if any(entry.is_symlink() or not entry.is_file() for entry in entries):
        fail("N7_EVIDENCE_ENTRY_INVALID")
    for name in ("state.lock", "batch.lock"):
        lock = directory / name
        if lock.is_file() and lock.stat().st_size != 0:
            fail("N7_EVIDENCE_LOCK_NOT_EMPTY")


def acquire_nonblocking_file_lock(handle: Any) -> None:
    handle.seek(0)
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.lockf(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB, 1)
    except OSError as failure:
        raise VerificationError("N7_EVIDENCE_LOCK_HELD") from failure


def release_file_lock(handle: Any) -> None:
    handle.seek(0)
    if os.name == "nt":
        import msvcrt
        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
    else:
        import fcntl
        fcntl.lockf(handle.fileno(), fcntl.LOCK_UN, 1)


@contextmanager
def lock_evidence_snapshot(
    directory: pathlib.Path,
    goal_directory: pathlib.Path | None = None,
) -> Iterator[None]:
    """Hold writer-compatible OS locks while all mutable live inputs are read."""
    validate_evidence_directory(directory)
    lock_paths = [directory / "batch.lock"]
    if goal_directory is not None:
        goal_lock = goal_directory / "goal-budget.lock"
        if not goal_lock.is_file() or goal_lock.is_symlink() \
                or goal_lock.stat().st_size != 0:
            fail("N7_GOAL_LOCK_INVALID")
        lock_paths.append(goal_lock)
    lock_paths.append(directory / "state.lock")
    handles: list[Any] = []
    try:
        for path in lock_paths:
            handle = path.open("r+b", buffering=0)
            try:
                acquire_nonblocking_file_lock(handle)
            except BaseException:
                handle.close()
                raise
            handles.append(handle)
        validate_evidence_directory(directory)
        yield
    finally:
        for handle in reversed(handles):
            try:
                release_file_lock(handle)
            finally:
                handle.close()


def verify(
    repository: pathlib.Path,
    evidence_directory: pathlib.Path | None = None,
    audit_outcome: bool = False,
) -> dict[str, Any]:
    contract, contract_identity = admission.verify_contract(repository)
    ledger_paths = [repository.joinpath(*path.parts) for path in admission.LEDGERS.values()]
    repository_identity, tracked = visual.repository_identity(repository, ledger_paths)
    ledgers = {
        selector: read_json(repository.joinpath(*relative.parts))
        for selector, relative in admission.LEDGERS.items()
    }
    for value in ledgers.values():
        admission.validate_ledger_shape(value)
    if ledgers["qwen37-flash"]["status"] != "CLOSED" \
            or ledgers["qwen38-max"]["status"] != "CLOSED":
        fail("N7_CONCURRENT_LEDGER_NOT_CLOSED")
    authorization = validate_closed_authorization(
        ledgers["qwen37-plus"], contract, contract_identity, repository_identity,
    )
    profile_path = repository.joinpath(*admission.PROFILE_PATH.parts)
    corpus_path = repository.joinpath(*SEMANTIC_CORPUS_PATH.parts)
    visual.require_tracked_regular(repository, profile_path, tracked, "N7 profile")
    visual.require_tracked_regular(repository, corpus_path, tracked, "N7 semantic corpus")
    visual.validate_profile(profile_path, authorization)
    corpus = read_json(corpus_path, payload_free=False)
    corpus_hash = hashlib.sha256(corpus_path.read_bytes()).hexdigest()
    if corpus_hash != contract["corpusSourceSha256"]:
        fail("N7_SEMANTIC_CORPUS_SOURCE_DRIFT")
    _case_ids, metadata = visual.corpus_cases(corpus, corpus_hash)

    goal_directory = repository.joinpath(*admission.GOAL_PATH.parts)
    evidence = evidence_directory or repository / ".sdlc" / "evidence" / contract["authorizationId"]
    evidence = evidence.resolve()
    with lock_evidence_snapshot(evidence, goal_directory):
        goal_guard = read_json(goal_directory / "goal-budget.guard.json")
        goal = read_json(goal_directory / "goal-budget.json")
        journal_guard = read_json(evidence / "state.guard.json")
        journal = read_json(evidence / "state.json")
        envelope = read_json(evidence / "report.json")
    guard_limits = visual.validate_goal_guard(goal_guard)
    reservations, model_totals, lifetime_totals, quarantined = visual.validate_goal(
        goal, guard_limits,
    )
    if any(item["state"] != "SETTLED" for item in reservations):
        fail("N7_GOAL_NONTERMINAL_OR_BREACHED_RESERVATION")

    validate_n7_journal_guard(journal_guard, authorization)
    results, terminal_states, provider_latency = validate_n7_journal(
        journal, authorization, metadata, reservations,
        require_review_required=not audit_outcome,
    )
    quality = validate_report_envelope(
        envelope, contract, authorization, results, metadata, contract_identity,
        require_quality=not audit_outcome,
    )
    if audit_outcome:
        quality = audit_gate_summary(quality, terminal_states)
    auth_reservations = [
        item for item in reservations
        if item["authorizationId"] == authorization["authorizationId"]
    ]
    slot = visual.MODEL_TO_GOAL_SLOT[authorization["model"]]
    return {
        "verificationVersion": VERIFIER_VERSION,
        "result": "AUDIT_COMPLETE" if audit_outcome else "PASS",
        "assurance": "A2_INDEPENDENT_READ_ONLY_RECONSTRUCTION",
        "ticketId": contract["ticketId"],
        "authorizationId": authorization["authorizationId"],
        "authorizationStatus": authorization["status"],
        "repositoryEvaluationIdentity": repository_identity,
        "contractIdentity": contract_identity,
        "evaluatorIdentity": contract["evaluatorIdentity"],
        "completedCases": len(results),
        "terminalStates": {
            "REVIEW_REQUIRED": terminal_states.count("REVIEW_REQUIRED"),
            **({"FAILED": terminal_states.count("FAILED")} if audit_outcome else {}),
        },
        "providerAttempts": len(auth_reservations),
        "actualInputTokens": sum(item["actualInputTokens"] for item in auth_reservations),
        "actualOutputTokens": sum(item["actualOutputTokens"] for item in auth_reservations),
        "actualCostMicrosCny": sum(item["actualCostMicrosCny"] for item in auth_reservations),
        "providerLatencyMillis": provider_latency,
        "epochModelTokens": model_totals[slot]["tokens"],
        "epochModelCostMicrosCny": model_totals[slot]["cost"],
        "lifetimeModelTokens": lifetime_totals[slot]["tokens"],
        "lifetimeModelCostMicrosCny": lifetime_totals[slot]["cost"],
        "quarantinedChargedReservations": quarantined,
        **quality,
        "payloadScan": "PASS",
        "holdoutAccess": False,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--evidence-directory", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--audit-outcome", action="store_true")
    args = parser.parse_args(argv)
    try:
        summary = verify(
            args.repository.resolve(strict=True),
            args.evidence_directory.resolve(strict=True) if args.evidence_directory else None,
            audit_outcome=args.audit_outcome,
        )
        encoded = json.dumps(summary, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded, encoding="utf-8", newline="\n")
        else:
            sys.stdout.write(encoded)
        return 0
    except (VerificationError, admission.VerificationError, visual.VerificationError,
            layered.VerificationError, OSError, UnicodeError, ValueError) as failure:
        sys.stderr.write(f"N7 live evidence verification failed: {failure}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
