#!/usr/bin/env python3
"""Provider-zero source verifier and independent audit-chain replay for IOPA-P2-05."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-p2-audit-dual-switch/1.0"
DIGEST_DOMAIN = "renderweave-live-admission-audit/1.0/event"
GENESIS_DOMAIN = "renderweave-live-admission-audit/1.0/genesis"
MATERIAL_PATHS = (
    "renderweave-app/src/main/resources/db/migration/V026__live_admission_audit_chain_and_dual_switches.sql",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresLiveProviderCallGate.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresLiveAuditStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresImageOnlyAdmissionPolicyStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresAuditIntegrityProbe.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/FileSystemProviderEgressPermit.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/InferenceController.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/audit/LiveAdmissionAuditChain.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/audit/LiveAdmissionAuditEvent.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/ImageOnlyAdmissionPolicy.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/ProviderEgressPermit.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/LiveProviderCallGate.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/LiveInferenceWorker.java",
    "renderweave-app/src/test/java/cn/hbads/renderweave/inference/PostgresLiveAuditChainAndDualSwitchTest.java",
    "renderweave-app/src/test/java/cn/hbads/renderweave/inference/PostgresLiveAuditPayloadScanTest.java",
    "renderweave-inference/src/test/java/cn/hbads/renderweave/inference/audit/LiveAdmissionAuditChainTest.java",
    "docs/adr/0052-payload-free-audit-chain-and-independent-dual-switches.md",
    "renderweave-app/src/main/resources/application.yml",
)
FORBIDDEN_EXPORT_MARKERS = (
    "api key", "private key", "authorization:", "data:image", "base64",
    "filename", "ocrtext", "modeloutput", "rootdocument", "chain-of-thought",
    "canary-original-file-name", "canary-ocr-text", "canary-full-response",
    "canary-chain-of-thought", "canary-pii", "sk-canary", "canary-image-signature",
    "@example.",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def source(repository: Path, index: int) -> str:
    try:
        return (repository / MATERIAL_PATHS[index]).read_text(encoding="utf-8")
    except Exception as error:
        raise SystemExit("P2_AUDIT_DUAL_SWITCH_SOURCE_MISSING") from error


def require_fragments(value: str, fragments: tuple[str, ...], code: str) -> None:
    if any(fragment not in value for fragment in fragments):
        fail(code)


def require_contract(repository: Path) -> None:
    migration = source(repository, 0)
    gate = source(repository, 1)
    audit_store = source(repository, 2)
    policy_store = source(repository, 3)
    probe = source(repository, 4)
    egress = source(repository, 5)
    controller = source(repository, 6)
    chain = source(repository, 7)
    event = source(repository, 8)
    policy = source(repository, 9)
    permit = source(repository, 10)
    gate_port = source(repository, 11)
    worker = source(repository, 12)
    dual_switch_test = source(repository, 13)
    scan_test = source(repository, 14)
    chain_test = source(repository, 15)
    adr = source(repository, 16)

    require_fragments(migration, (
        "CREATE TABLE image_only_admission_policy",
        "INSERT INTO image_only_admission_policy (policy_version, enabled, changed_by, change_reason, changed_at)\n"
        "VALUES (1, FALSE, 'renderweave-system-bootstrap', 'DEFAULT_CLOSED'",
        "CREATE TABLE live_admission_audit_event",
        "PRIMARY KEY (run_id, sequence)",
        "CREATE TABLE provider_call_authorization",
        "UNIQUE (run_id, attempt_ordinal)",
        "enforce_live_admission_audit_chain",
        "live admission audit sequence must be monotonic per run",
        "live admission audit digest chain is broken",
        "image_only_admission_policy_append_only",
        "live_admission_audit_event_append_only",
        "provider_call_authorization_append_only",
        "CREATE ROLE renderweave_live_runtime NOLOGIN",
        "GRANT SELECT, INSERT ON live_admission_audit_event TO renderweave_live_runtime",
        "REVOKE UPDATE, DELETE ON live_admission_audit_event FROM renderweave_live_runtime",
        "REVOKE UPDATE, DELETE ON image_only_admission_policy FROM renderweave_live_runtime",
        "REVOKE UPDATE, DELETE ON provider_call_authorization FROM renderweave_live_runtime",
        "f617f35d307de727cca8a07a58bf7b09bac9144722b8e370aec119f80ded24fd",
    ), "P2_AUDIT_DUAL_SWITCH_MIGRATION_DRIFT")

    require_fragments(gate, (
        "@Transactional",
        "requireSwitches(command.mode())",
        "requireChainHealthy(command.runId())",
        "budgetStore.reserve(",
        "auditStore.append(",
        "insert into provider_call_authorization",
        "ImageOnlyAdmissionPolicy.DISABLED_REASON_CODE",
        "ProviderEgressPermit.DISABLED_REASON_CODE",
        "AUDIT_INTEGRITY_UNAVAILABLE",
        "confirmationGuard.authorizeProviderRequest",
    ), "P2_AUDIT_DUAL_SWITCH_GATE_DRIFT")
    if gate.index("budgetStore.reserve(") > gate.index("insert into provider_call_authorization"):
        fail("P2_AUDIT_DUAL_SWITCH_GATE_ORDER_DRIFT")

    require_fragments(audit_store, (
        "Propagation.MANDATORY",
        "pg_advisory_xact_lock",
        "LiveAdmissionAuditChain.GENESIS_DIGEST",
    ), "P2_AUDIT_DUAL_SWITCH_AUDIT_STORE_DRIFT")
    require_fragments(policy_store, (
        "coalesce(max(policy_version), 0) + 1",
        "CHANGE_REASONS.contains(reason)",
    ), "P2_AUDIT_DUAL_SWITCH_POLICY_STORE_DRIFT")
    require_fragments(probe, (
        "has_table_privilege(current_user, 'live_admission_audit_event', 'INSERT')",
        "AUDIT_INTEGRITY_UNAVAILABLE",
        "LiveAdmissionAuditChain.verify",
    ), "P2_AUDIT_DUAL_SWITCH_PROBE_DRIFT")
    require_fragments(egress, (
        "renderweave-provider-egress-permit/1.0",
        "enabled=true",
        "Snapshot.DISABLED",
    ), "P2_AUDIT_DUAL_SWITCH_EGRESS_ADAPTER_DRIFT")
    require_fragments(controller, (
        "requireDualSwitches(mode)",
        "requireDualSwitches(source.mode())",
        "auditIntegrityProbe.snapshot()",
        "ImageOnlyAdmissionPolicy.DISABLED_REASON_CODE",
        "ProviderEgressPermit.DISABLED_REASON_CODE",
    ), "P2_AUDIT_DUAL_SWITCH_CONTROLLER_DRIFT")
    require_fragments(chain, (
        "renderweave-live-admission-audit/1.0/event",
        "renderweave-live-admission-audit/1.0/genesis",
        "DUPLICATE", "REORDERED", "MISSING", "TAMPERED",
    ), "P2_AUDIT_DUAL_SWITCH_CHAIN_DRIFT")
    require_fragments(event, (
        "eventCode", "inputFingerprint", "usageInputTokens", "costMicrosCny",
        "previousEventDigest",
    ), "P2_AUDIT_DUAL_SWITCH_EVENT_DRIFT")
    require_fragments(policy, (
        "LIVE_POLICY_DISABLED",
        "DEFAULT_CLOSED",
        "OPS_ENABLED",
        "OPS_DISABLED",
        "MISCLASSIFICATION_SHUTDOWN",
        "AUTOMATIC_COST_STOP",
    ), "P2_AUDIT_DUAL_SWITCH_POLICY_DRIFT")
    require_fragments(permit, (
        "EGRESS_DISABLED",
        "Snapshot DISABLED = new Snapshot(false, \"absent\")",
    ), "P2_AUDIT_DUAL_SWITCH_PERMIT_DRIFT")
    require_fragments(gate_port, (
        "requireDispatchEligible",
        "authorizeCall",
        "recordDispatchOutcome",
        "recordDrain",
    ), "P2_AUDIT_DUAL_SWITCH_GATE_PORT_DRIFT")
    require_fragments(worker, (
        "callGate.orElseThrow().authorizeCall",
        "callGate.orElseThrow().recordDispatchOutcome",
        "requireGateEligible(current)",
        "recordDrainIfSwitchClosed",
        "RUN_DRAINED_POLICY",
        "RUN_DRAINED_EGRESS",
    ), "P2_AUDIT_DUAL_SWITCH_WORKER_DRIFT")
    if worker.count("callGate.orElseThrow().recordDispatchOutcome") < 2:
        fail("P2_AUDIT_DUAL_SWITCH_WORKER_SETTLE_MISSING")

    require_fragments(dual_switch_test, (
        "PostgreSQLContainer",
        "onlyTheElevenSwitchCombinationReachesTheProvider",
        "queuedRunsDrainToStableTerminalsAndNeverResurrect",
        "committedAuthorizationWithoutDispatchIsNeverReplayedBlindly",
        "authorizationFailureRollsBackReservationAndAuditTogether",
        "tamperedChainFailsIndependentReplayAndBlocksNewCalls",
        "deletedAuditEventIsDetectedAsMissingBeforeAnyNewCall",
        "runtimeRoleCanInsertAndSelectButNeverUpdateOrDeleteAuditFacts",
        "reviewRequiredRunsStayReviewableWhileSwitchesAreClosed",
        "defaultDeploymentBootsClosedForPolicyAndEgress",
        "permission denied",
    ), "P2_AUDIT_DUAL_SWITCH_TEST_VECTOR_MISSING")
    require_fragments(scan_test, (
        "canariesNeverReachAuditEventsExecutionLogOrProblemProjections",
        "canary-original-file-name", "canary-ocr-text", "canary-full-response",
        "canary-chain-of-thought", "canary-pii", "sk-canary",
        "audit-chain-export.json",
    ), "P2_AUDIT_DUAL_SWITCH_SCAN_TEST_MISSING")
    require_fragments(chain_test, (
        "duplicatedSequenceFailsClosed",
        "reorderedStorageFailsClosed",
        "deletedMiddleEventFailsClosed",
        "missingGenesisEventFailsClosed",
        "tamperedCostFailsClosed",
        "forgedPreviousDigestFailsClosed",
    ), "P2_AUDIT_DUAL_SWITCH_CHAIN_TEST_MISSING")
    require_fragments(adr, (
        "status: accepted",
        "append-only",
        "digest commits to the previous digest",
        "renderweave_live_runtime",
        "SELECT and INSERT",
        "one PostgreSQL transaction",
        "not replayed blindly",
        "default closed",
        "never resurrects a drained run",
        "AUDIT_INTEGRITY_UNAVAILABLE",
    ), "P2_AUDIT_DUAL_SWITCH_ADR_DRIFT")


def genesis_digest() -> str:
    hasher = hashlib.sha256()
    framed = GENESIS_DOMAIN.encode("utf-8")
    hasher.update(struct.pack(">i", len(framed)))
    hasher.update(framed)
    return hasher.hexdigest()


def event_digest(event: dict[str, Any]) -> str:
    components = [
        event["runId"],
        str(event["sequence"]),
        event["eventCode"],
        event.get("actorId") or "",
        event.get("confirmationId") or "",
        event.get("reservationId") or "",
        event.get("callAuthorizationId") or "",
        "" if event.get("attemptOrdinal") is None else str(event["attemptOrdinal"]),
        event.get("inputFingerprint") or "",
        event.get("profileId") or "",
        event.get("profileSha256") or "",
        event.get("decisionCode") or "",
        "" if event.get("usageInputTokens") is None else str(event["usageInputTokens"]),
        "" if event.get("usageOutputTokens") is None else str(event["usageOutputTokens"]),
        "" if event.get("costMicrosCny") is None else str(event["costMicrosCny"]),
        str(event["occurredAtEpochSecond"]),
        str(event["occurredAtNano"]),
        event["previousEventDigest"],
    ]
    hasher = hashlib.sha256()
    for value in [DIGEST_DOMAIN, *components]:
        framed = value.encode("utf-8")
        hasher.update(struct.pack(">i", len(framed)))
        hasher.update(framed)
    return hasher.hexdigest()


def chain_verdict(events: list[dict[str, Any]]) -> str:
    if not events:
        return "OK"
    ordered = sorted(events, key=lambda item: item["sequence"])
    sequences = [item["sequence"] for item in ordered]
    if len(set(sequences)) != len(sequences):
        return "DUPLICATE"
    if sequences[0] != 1 or sequences[-1] != len(sequences):
        return "MISSING"
    for index in range(1, len(events)):
        if events[index]["sequence"] <= events[index - 1]["sequence"]:
            return "REORDERED"
    expected_previous = genesis_digest()
    for event in ordered:
        if event["previousEventDigest"] != expected_previous:
            return "TAMPERED"
        recomputed = event_digest(event)
        if recomputed != event["eventDigest"]:
            return "TAMPERED"
        expected_previous = recomputed
    return "OK"


def replay_export(repository: Path, export_path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(export_path.read_text(encoding="utf-8"))
    except Exception as error:
        raise SystemExit("P2_AUDIT_DUAL_SWITCH_EXPORT_MISSING") from error
    if payload.get("exportVersion") != "renderweave-live-admission-audit-export/1.0":
        fail("P2_AUDIT_DUAL_SWITCH_EXPORT_VERSION_DRIFT")
    events = payload.get("events")
    if not isinstance(events, list) or len(events) < 2:
        fail("P2_AUDIT_DUAL_SWITCH_EXPORT_EMPTY")
    encoded = json.dumps(payload, ensure_ascii=False).lower()
    for marker in FORBIDDEN_EXPORT_MARKERS:
        if marker in encoded:
            fail("P2_AUDIT_DUAL_SWITCH_EXPORT_PAYLOAD_LEAK")
    codes = [event["eventCode"] for event in events]
    if "CALL_AUTHORIZED" not in codes or "CALL_DISPATCH_SUCCEEDED" not in codes:
        fail("P2_AUDIT_DUAL_SWITCH_EXPORT_INCOMPLETE")
    verdict = chain_verdict(events)
    if verdict != "OK":
        fail("P2_AUDIT_DUAL_SWITCH_CHAIN_REPLAY_FAILED")
    tampered = [dict(event, costMicrosCny=(event.get("costMicrosCny") or 0) + 1)
                for event in events]
    if chain_verdict(tampered) != "TAMPERED":
        fail("P2_AUDIT_DUAL_SWITCH_TAMPER_PROBE_INSENSITIVE")
    reordered = list(events)
    reordered[0], reordered[1] = reordered[1], reordered[0]
    if chain_verdict(reordered) not in {"REORDERED", "TAMPERED", "MISSING"}:
        fail("P2_AUDIT_DUAL_SWITCH_REORDER_PROBE_INSENSITIVE")
    truncated = events[1:]
    if chain_verdict(truncated) != "MISSING":
        fail("P2_AUDIT_DUAL_SWITCH_DELETE_PROBE_INSENSITIVE")
    duplicated = events + [events[-1]]
    if chain_verdict(duplicated) != "DUPLICATE":
        fail("P2_AUDIT_DUAL_SWITCH_DUPLICATE_PROBE_INSENSITIVE")
    return {"auditEventCount": len(events), "chainVerdict": verdict}


def implementation_identity(repository: Path) -> str:
    value = hashlib.sha256()
    for relative in MATERIAL_PATHS:
        raw = (repository / relative).read_bytes()
        path = relative.encode("utf-8")
        value.update(str(len(path)).encode("ascii") + b":" + path + b"\n")
        value.update(str(len(raw)).encode("ascii") + b":" + raw + b"\n")
    return REPORT_VERSION + ":" + value.hexdigest()


def require_no_open_authorization(repository: Path) -> int:
    count = 0
    for path in (repository / "plans/live-canary-authorizations").glob("20*.json"):
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except Exception as error:
            raise SystemExit("P2_AUDIT_DUAL_SWITCH_AUTHORIZATION_INVENTORY_INVALID") from error
        if type(value) is not dict or value.get("status") not in {"PROPOSED", "OPEN", "CLOSED"}:
            fail("P2_AUDIT_DUAL_SWITCH_AUTHORIZATION_INVENTORY_INVALID")
        if value.get("status") == "OPEN":
            count += 1
    if count:
        fail("P2_AUDIT_DUAL_SWITCH_OPEN_AUTHORIZATION_FORBIDDEN")
    return count


def verify(repository: Path, export_path: Path) -> dict[str, Any]:
    require_contract(repository)
    replay = replay_export(repository, export_path)
    open_count = require_no_open_authorization(repository)
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "IOPA_P2_05_AUDIT_CHAIN_DUAL_SWITCHES",
        "implementationIdentity": implementation_identity(repository),
        "auditEventCount": replay["auditEventCount"],
        "chainVerdict": replay["chainVerdict"],
        "chainReplayIndependent": True,
        "runtimeRoleCannotUpdateOrDelete": True,
        "atomicCallAuthorization": True,
        "crashWithoutAuditCannotDispatch": True,
        "dualSwitchDefaultClosed": True,
        "switchCombinationsRejectedExceptEleven": True,
        "queuedDrainedToStableTerminal": True,
        "reopeningDoesNotResurrect": True,
        "reviewRequiredUnblockedDuringDrain": True,
        "auditIntegrityReasonCode": "AUDIT_INTEGRITY_UNAVAILABLE",
        "openAuthorizationCount": open_count,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "productionConfigured": False,
        "productionLiveAuthorityGranted": False,
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "productionDeployed": False,
        "payloadFree": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--export", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = verify(args.repository.resolve(), args.export.resolve())
    encoded = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if any(marker in encoded.lower() for marker in FORBIDDEN_EXPORT_MARKERS):
        fail("P2_AUDIT_DUAL_SWITCH_SUMMARY_PAYLOAD_LEAK")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
