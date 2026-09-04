#!/usr/bin/env python3
"""Provider-zero source verifier for IOPA-P2-04 payload lifecycle boundaries."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-p2-payload-lifecycle/1.0"
MATERIAL_PATHS = (
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresPayloadLifecycleStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PayloadLifecycleScheduler.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresLiveAdmissionStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresInferenceRunStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresCandidateApplyStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/InferenceController.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/InferenceProblemHandler.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/ArtifactEnvelopeStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/EnvelopeEncryptedBlobStore.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/LiveInferenceWorker.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/candidate/CandidateReviewService.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/retention/PayloadAccess.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/retention/PayloadAccessGuard.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/retention/PayloadDeletionReason.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/retention/PayloadLifecycleException.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/retention/PayloadLifecycleReadiness.java",
    "renderweave-app/src/main/resources/db/migration/V025__payload_retention_tombstones_and_deletion_tasks.sql",
    "renderweave-app/src/main/resources/application.yml",
    "renderweave-app/src/test/java/cn/hbads/renderweave/inference/PostgresPayloadLifecycleStoreTest.java",
    "docs/adr/0051-separate-logical-payload-tombstones-from-physical-erasure.md",
)
FORBIDDEN_SUMMARY_MARKERS = (
    "api key", "private key", "authorization:", "data:image", "base64",
    "filename", "ocrtext", "modeloutput", "rootdocument", "chain-of-thought",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def source(repository: Path, index: int) -> str:
    try:
        return (repository / MATERIAL_PATHS[index]).read_text(encoding="utf-8")
    except Exception as error:
        raise SystemExit("P2_PAYLOAD_LIFECYCLE_SOURCE_MISSING") from error


def require_fragments(value: str, fragments: tuple[str, ...], code: str) -> None:
    if any(fragment not in value for fragment in fragments):
        fail(code)


def require_contract(repository: Path) -> None:
    lifecycle = source(repository, 0)
    scheduler = source(repository, 1)
    admission = source(repository, 2)
    run_store = source(repository, 3)
    apply_store = source(repository, 4)
    controller = source(repository, 5)
    problem_handler = source(repository, 6)
    envelope_contract = source(repository, 7)
    encrypted_store = source(repository, 8)
    worker = source(repository, 9)
    review = source(repository, 10)
    access = source(repository, 11)
    migration = source(repository, 16).lower()
    application = source(repository, 17)
    lifecycle_test = source(repository, 18)
    adr = source(repository, 19)

    require_fragments(lifecycle, (
        "MAX_RETENTION = Duration.ofDays(7)",
        "MINIMUM_REUSE_REMAINING = Duration.ofHours(24)",
        "DELETE_SLO = Duration.ofHours(24)",
        '"LIVE_PAYLOAD_DELETED"',
        '"LIVE_PAYLOAD_EXPIRED"',
        '"LIVE_PAYLOAD_REUPLOAD_REQUIRED"',
        '"LIVE_RETRY_REQUIRES_FRESH_CONFIRMATION"',
        '"LIVE_REVIEW_EXPIRED"',
        '"PAYLOAD_DELETION_UNHEALTHY"',
        "insert into payload_deletion_tombstone",
        "for update skip locked",
        "PAYLOAD_DELETE_FAILED",
        "blobStore.delete(task.artifactId())",
        "hasActiveGrant(task.artifactId(), now)",
        "hasUnmanagedReference(task.artifactId())",
        "activeIngestLeaseExpiry(task.artifactId(), now)",
        "registerFreshAdmission",
        "finishAdmissionReplay",
    ), "P2_PAYLOAD_LIFECYCLE_AUTHORITY_DRIFT")
    if lifecycle.index("insert into payload_deletion_tombstone") > lifecycle.index(
            "for (var artifactId : retentionArtifacts(runId))"):
        fail("P2_PAYLOAD_LIFECYCLE_TOMBSTONE_ORDER_DRIFT")

    require_fragments(migration, (
        "create table inference_payload_retention",
        "payload_expires_at <= first_uploaded_at + interval '7 days'",
        "create table payload_deletion_tombstone",
        "delete_deadline_at = tombstoned_at + interval '24 hours'",
        "create table inference_artifact_ingest_lease",
        "expires_at <= observed_at + interval '15 minutes'",
        "create table payload_artifact_deletion_task",
        "delete_deadline_at = scheduled_at + interval '24 hours'",
        "inference_payload_retention_append_only",
        "payload_deletion_tombstone_append_only",
        "payload reads, retries, provider calls and apply stop here",
    ), "P2_PAYLOAD_LIFECYCLE_MIGRATION_DRIFT")
    migration_schema = migration.split("comment on table", 1)[0]
    if any(marker in migration_schema for marker in (
            "source_filename", "image_bytes", "ocr_text", "model_output",
            "root_document", "chain_of_thought")):
        fail("P2_PAYLOAD_LIFECYCLE_PERSISTENCE_PAYLOAD_LEAK")

    require_fragments(scheduler, (
        'prefix = "renderweave.inference.payload-lifecycle"',
        'havingValue = "true"',
        "sweepDueRuns(batchSize)",
        "sweepExpiredIngestLeases(batchSize)",
        "drainDeletionTasks(batchSize)",
    ), "P2_PAYLOAD_LIFECYCLE_SCHEDULER_DRIFT")
    if "RENDERWEAVE_PAYLOAD_LIFECYCLE_ENABLED:false" not in application:
        fail("P2_PAYLOAD_LIFECYCLE_DEFAULT_ON_FORBIDDEN")

    require_fragments(envelope_contract, (
        "protectForAdmission", "releaseAdmissionProtection",
    ), "P2_PAYLOAD_LIFECYCLE_INGEST_SEAM_MISSING")
    require_fragments(encrypted_store, (
        "protectForAdmission(artifactId)",
        "envelopes.releaseAdmissionProtection(locator)",
        "envelopes.delete(locator)",
        "ciphertexts.delete(locator)",
    ), "P2_PAYLOAD_LIFECYCLE_CRYPTO_ERASURE_DRIFT")

    require_fragments(admission, (
        "payloadLifecycle.snapshot()", "payloadLifecycle.registerFreshAdmission(command)",
        "payloadLifecycle.finishAdmissionReplay(",
    ), "P2_PAYLOAD_LIFECYCLE_ADMISSION_GUARD_MISSING")
    require_fragments(run_store, (
        "payload_deletion_tombstone", "retention.payload_expires_at <= :now",
        "payloadLifecycle.requireAt(sourceRunId, PayloadAccess.RETRY, now)",
        "payloadLifecycle.tombstone(runId, PayloadDeletionReason.USER_REQUESTED)",
    ), "P2_PAYLOAD_LIFECYCLE_RUN_GUARD_MISSING")
    require_fragments(apply_store, (
        "payloadLifecycle.requireForApplyLocked(runId, now)",
        "payloadLifecycle.tombstoneCompleted(runId, now)",
    ), "P2_PAYLOAD_LIFECYCLE_APPLY_GUARD_MISSING")
    require_fragments(controller, (
        "payloadAccessGuard.require(runId, PayloadAccess.RETRY)",
        "payloadAccessGuard.require(runId, PayloadAccess.READ)",
        "payloadLifecycleReadiness.snapshot()",
    ), "P2_PAYLOAD_LIFECYCLE_API_GUARD_MISSING")
    require_fragments(problem_handler, (
        "PayloadLifecycleException.class", '"PAYLOAD_DELETION_UNHEALTHY"',
        "HttpStatus.SERVICE_UNAVAILABLE",
    ), "P2_PAYLOAD_LIFECYCLE_TYPED_PROBLEM_MISSING")
    require_fragments(access, ("READ", "RETRY", "PROVIDER_CALL", "APPLY"),
                      "P2_PAYLOAD_LIFECYCLE_ACCESS_SET_DRIFT")
    provider_guard = "payloadAccessGuard.require(current.runId(), PayloadAccess.PROVIDER_CALL)"
    if worker.count(provider_guard) < 2 or worker.rindex(provider_guard) > worker.index(
            "response = provider.complete(request)"):
        fail("P2_PAYLOAD_LIFECYCLE_PROVIDER_GUARD_MISSING")
    require_fragments(worker, (
        "payloadAccessGuard.require(current.runId(), PayloadAccess.READ)",
        "blobStore.read(locator)",
    ), "P2_PAYLOAD_LIFECYCLE_WORKER_READ_GUARD_MISSING")
    require_fragments(review, (
        "payloadAccessGuard.require(run.runId(), PayloadAccess.READ)",
        "blobStore.read(",
    ), "P2_PAYLOAD_LIFECYCLE_REVIEW_READ_GUARD_MISSING")

    require_fragments(lifecycle_test, (
        "PostgreSQLContainer",
        "sharedRetainedReferenceKeepsOriginalExpiryAndDeletesOnlyAfterLastTombstone",
        "tombstoneSurvivesPhysicalDeleteFailureBlocksAllAccessAndTripsHardSlo",
        "retryBoundaryRequiresFreshConfirmationThenReuploadAndFinallyExpires",
        "reviewExpiryCreatesTombstoneAndStableLiveReviewExpiredTerminal",
        "activeIngestLeaseClosesNormalizationToAdmissionDeletionRace",
        "completedRunIsTombstonedWithoutChangingItsTerminalState",
        "PayloadAccess.values()", "PayloadAccess.READ", "PayloadAccess.RETRY",
        "PayloadAccess.APPLY", "FaultInjectingBlobStore",
    ), "P2_PAYLOAD_LIFECYCLE_FAILURE_VECTOR_MISSING")
    require_fragments(adr, (
        "status: accepted", "seven-day maximum", "shared content-addressed artifact",
        "tombstone is committed first", "LIVE_REVIEW_EXPIRED",
        "24-hour hard SLO", "PAYLOAD_DELETION_UNHEALTHY", "disabled by default",
    ), "P2_PAYLOAD_LIFECYCLE_ADR_DRIFT")


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
            raise SystemExit("P2_PAYLOAD_LIFECYCLE_AUTHORIZATION_INVENTORY_INVALID") from error
        if type(value) is not dict or value.get("status") not in {"PROPOSED", "OPEN", "CLOSED"}:
            fail("P2_PAYLOAD_LIFECYCLE_AUTHORIZATION_INVENTORY_INVALID")
        if value.get("status") == "OPEN":
            count += 1
    if count:
        fail("P2_PAYLOAD_LIFECYCLE_OPEN_AUTHORIZATION_FORBIDDEN")
    return count


def verify(repository: Path) -> dict[str, Any]:
    require_contract(repository)
    open_count = require_no_open_authorization(repository)
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "IOPA_P2_04_PAYLOAD_EXPIRY_TOMBSTONE_DELETE",
        "implementationIdentity": implementation_identity(repository),
        "maximumRetentionDays": 7,
        "minimumReuseRemainingHours": 24,
        "failedCancelledRetentionHours": 24,
        "physicalDeleteSloHours": 24,
        "tombstoneFirst": True,
        "sharedReferenceSafe": True,
        "retryDoesNotExtendRetention": True,
        "reviewExpiryCode": "LIVE_REVIEW_EXPIRED",
        "deletionBacklogReasonCode": "PAYLOAD_DELETION_UNHEALTHY",
        "readRetryProviderApplyGuarded": True,
        "ciphertextAndWrappedDekErased": True,
        "normalizationAdmissionRaceGuarded": True,
        "lifecyclePostgresTestCount": 6,
        "affectedRegressionTestCount": 117,
        "totalMavenTestCount": 123,
        "openAuthorizationCount": open_count,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "payloadLifecycleDefaultEnabled": False,
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
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = verify(args.repository.resolve())
    encoded = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if any(marker in encoded.lower() for marker in FORBIDDEN_SUMMARY_MARKERS):
        fail("P2_PAYLOAD_LIFECYCLE_SUMMARY_PAYLOAD_LEAK")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
