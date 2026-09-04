#!/usr/bin/env python3
"""Provider-zero source and known-answer replay for IOPA-P2-02 live confirmation."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-p2-confirmation/1.0"
MANIFEST_VERSION = "renderweave-live-input-manifest/1.0"
NOTICE_DOMAIN = "renderweave-external-transfer-notice/1.0"
REQUEST_DOMAIN = "renderweave-live-admission-request/1.0"
KNOWN_NOTICE_SHA256 = "733df81318c7ae0c7857da64f9e0f970c5a9542b3c7e5a0236b4307bdc2ac682"
KNOWN_MANIFEST_SHA256 = "0835aae49cbbab6684a17c5e7f45f7724ee91a0c1b3cc7b85c3c1ed2889eafb8"
KNOWN_REQUEST_SHA256 = "f09805d01fe77fdb1047393a362fe15dc5364dff24bf98827c1a6bf25f2f87ea"
FORBIDDEN_SUMMARY_MARKERS = (
    "api key", "private key", "compactjws", "authorization:", "data:image",
    "base64", "filename", "ocrtext", "modeloutput", "rootdocument", "chain-of-thought",
)
MATERIAL_PATHS = (
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/AdmissionDigests.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/ExternalTransferNotice.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/ExternalTransferConfirmation.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/ExternalTransferConfirmationGuard.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/ImageOnlyLiveAdmissionRequest.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/ImageOnlyProductionAdmission.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/InputProvenance.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/LiveAdmissionConfiguration.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/LiveAdmissionConfigurationResolver.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/LiveAdmissionProblem.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/LiveAdmissionStore.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/LiveInputManifest.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/NewLiveInferenceRun.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/SensitivityClass.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/input/InputNormalizer.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/input/ImageNormalizer.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresLiveAdmissionStore.java",
    "renderweave-app/src/main/resources/db/migration/V023__live_input_manifest_and_confirmation.sql",
    "docs/adr/0049-bind-normalized-manifest-and-confirmation-in-one-transaction.md",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def source(repository: Path, relative: str) -> str:
    try:
        return (repository / relative).read_text(encoding="utf-8")
    except Exception as error:
        raise SystemExit("P2_CONFIRMATION_SOURCE_MISSING") from error


def digest(domain: str, *values: object) -> str:
    value = hashlib.sha256()
    for item in (domain, *values):
        encoded = str(item).encode("utf-8")
        value.update(struct.pack(">I", len(encoded)))
        value.update(encoded)
    return value.hexdigest()


def require_known_answers() -> None:
    version = "renderweave-external-transfer-notice/1.0"
    notice_values = (
        version, "zh-CN", "provider-legal", "DASHSCOPE", "qwen3.8-max",
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        "cn-beijing", "schema-candidate", "unknown", "unknown", "possible",
        "profile-1", "1" * 64, 12, 6_000_000, 604_800,
        "policy/1", "2" * 64, "contract/1", "3" * 64,
    )
    notice_sha = digest(NOTICE_DOMAIN, *notice_values)
    manifest_sha = digest(
        MANIFEST_VERSION, 1, 0, "4" * 64, "image/png", 64, 4, 4
    )
    request_sha = digest(
        REQUEST_DOMAIN, "actor-opaque-001", "USER_PROVIDED", "ORDINARY_DESIGN",
        version, "zh-CN", notice_sha, "policy/1", "2" * 64,
        "contract/1", "3" * 64, "DASHSCOPE", "qwen3.8-max",
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        "cn-beijing", "profile-1", "1" * 64, 12, 6_000_000, 604_800,
        MANIFEST_VERSION, manifest_sha,
    )
    if (notice_sha, manifest_sha, request_sha) != (
        KNOWN_NOTICE_SHA256, KNOWN_MANIFEST_SHA256, KNOWN_REQUEST_SHA256
    ):
        fail("P2_CONFIRMATION_KNOWN_ANSWER_DRIFT")


def require_contract(repository: Path) -> None:
    require_known_answers()
    normalizer = source(repository, MATERIAL_PATHS[14])
    image_normalizer = source(repository, MATERIAL_PATHS[15])
    if any(fragment not in normalizer for fragment in (
        "MAX_IMAGES = 10", "MAX_IMAGE_BYTES = 10 * 1024 * 1024",
        "MAX_IMAGE_BATCH_BYTES = 32 * 1024 * 1024",
    )) or "MAX_SOURCE_PIXELS = 25_000_000L" not in image_normalizer:
        fail("P2_CONFIRMATION_INPUT_BOUNDARY_DRIFT")

    request = source(repository, MATERIAL_PATHS[4])
    service = source(repository, MATERIAL_PATHS[5])
    manifest = source(repository, MATERIAL_PATHS[11])
    confirmation = source(repository, MATERIAL_PATHS[2])
    guard = source(repository, MATERIAL_PATHS[3])
    if "externalTransferConfirmed" in request:
        fail("P2_CONFIRMATION_BOOLEAN_AUTHORITY_REINTRODUCED")
    if any(fragment not in service for fragment in (
        "InputProvenance.USER_PROVIDED", "SensitivityClass.ORDINARY_DESIGN",
        "LIVE_TRANSFER_NOTICE_STALE", "GatewayAssertionAuthority.idempotencyKeyDigest",
        "LiveInputManifest.from(normalized)", "store.admit(new NewLiveInferenceRun",
    )):
        fail("P2_CONFIRMATION_PRIMARY_SEAM_MISSING")
    if any(fragment not in manifest for fragment in (
        f'VERSION = "{MANIFEST_VERSION}"', "reference.ordinal()",
        "artifact.artifactId()", "artifact.byteLength()", "artifact.width()", "artifact.height()",
    )) or any(marker in manifest.lower() for marker in ("filename", "originalname")):
        fail("P2_CONFIRMATION_MANIFEST_CONTRACT_DRIFT")
    if any(fragment not in confirmation for fragment in (
        f'FINGERPRINT_DOMAIN = "{REQUEST_DOMAIN}"', "Duration.ofMinutes(15)",
        "Duration.ofHours(2)", "identity.actorId()", "notice.policySha256()",
        "notice.providerContractSha256()", "notice.profileSha256()", "manifest.sha256()",
    )):
        fail("P2_CONFIRMATION_IDENTITY_CONTRACT_DRIFT")
    request_fingerprint_body = confirmation.split("public static String requestFingerprint", 1)[1]
    for volatile in ("requestId", "gatewayJti", "confirmedAt", "confirmationId", "runId"):
        if volatile in request_fingerprint_body.split("}", 1)[0]:
            fail("P2_CONFIRMATION_VOLATILE_IDENTITY_BOUND")
    if any(fragment not in guard for fragment in (
        "LIVE_CONFIRMATION_EXPIRED", "LIVE_PROVIDER_CALL_WINDOW_EXPIRED",
        "LIVE_PROVIDER_ATTEMPT_AMBIGUOUS", "LIVE_INPUT_MANIFEST_MISMATCH",
        "now.isAfter(confirmation.dispatchNotAfter())",
        "now.isAfter(confirmation.providerCallsNotAfter())",
    )):
        fail("P2_CONFIRMATION_TIME_OR_AMBIGUITY_GUARD_MISSING")

    store = source(repository, MATERIAL_PATHS[16])
    migration = source(repository, MATERIAL_PATHS[17]).lower()
    if "@Transactional" not in store or any(fragment not in store for fragment in (
        "runStore.create(command.run())", "insertManifest(command)",
        "insertConfirmation(command.confirmation())", "LIVE_IDEMPOTENCY_CONFLICT",
    )):
        fail("P2_CONFIRMATION_TRANSACTION_SEAM_MISSING")
    if not (store.index("runStore.create(command.run())")
            < store.index("insertManifest(command)")
            < store.index("insertConfirmation(command.confirmation())")):
        fail("P2_CONFIRMATION_TRANSACTION_ORDER_DRIFT")
    for fragment in (
        "create table external_transfer_notice",
        "create table live_input_manifest",
        "create table live_input_manifest_item",
        "create table external_transfer_confirmation",
        "interval '15 minutes'", "interval '2 hours'",
        "reject_live_admission_fact_mutation", "before update or delete",
        "on delete restrict",
    ):
        if fragment not in migration:
            fail("P2_CONFIRMATION_MIGRATION_CONTRACT_MISSING")
    if any(marker in migration for marker in (
        "original_filename", "source_filename", "compact_jws", "image_bytes", "ocr_text",
        "model_output", "root_document", "chain_of_thought",
    )):
        fail("P2_CONFIRMATION_PERSISTENCE_PAYLOAD_LEAK")

    adr = source(repository, MATERIAL_PATHS[18])
    if any(fragment not in adr for fragment in (
        "status: accepted", "one PostgreSQL transaction", "response-loss retry",
        "Source filenames are never", "does not authorize Provider dispatch",
    )):
        fail("P2_CONFIRMATION_ADR_DRIFT")


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
            raise SystemExit("P2_CONFIRMATION_AUTHORIZATION_INVENTORY_INVALID") from error
        if type(value) is not dict or value.get("status") not in {"PROPOSED", "OPEN", "CLOSED"}:
            fail("P2_CONFIRMATION_AUTHORIZATION_INVENTORY_INVALID")
        if value.get("status") == "OPEN":
            count += 1
    if count:
        fail("P2_CONFIRMATION_OPEN_AUTHORIZATION_FORBIDDEN")
    return count


def verify(repository: Path) -> dict[str, Any]:
    require_contract(repository)
    open_count = require_no_open_authorization(repository)
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "IOPA_P2_02_CLASSIFICATION_NOTICE_MANIFEST_CONFIRMATION",
        "implementationIdentity": implementation_identity(repository),
        "manifestVersion": MANIFEST_VERSION,
        "noticeDigestDomain": NOTICE_DOMAIN,
        "requestDigestDomain": REQUEST_DOMAIN,
        "knownAnswerNoticeSha256": KNOWN_NOTICE_SHA256,
        "knownAnswerManifestSha256": KNOWN_MANIFEST_SHA256,
        "knownAnswerRequestSha256": KNOWN_REQUEST_SHA256,
        "maximumImages": 10,
        "maximumImageBytes": 10 * 1024 * 1024,
        "maximumImagePixels": 25_000_000,
        "maximumBatchBytes": 32 * 1024 * 1024,
        "firstDispatchWindowSeconds": 15 * 60,
        "providerCallWindowSeconds": 2 * 60 * 60,
        "runManifestConfirmationAtomic": True,
        "immutableFactsAppendOnly": True,
        "responseLossReplayReturnsOriginal": True,
        "freshGatewayIdentityExcludedFromFingerprint": True,
        "ambiguousAttemptBlindReplayAllowed": False,
        "originalNamePersisted": False,
        "openAuthorizationCount": open_count,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "productionConfirmationCreated": False,
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
        fail("P2_CONFIRMATION_SUMMARY_PAYLOAD_LEAK")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
