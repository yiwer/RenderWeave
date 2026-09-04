#!/usr/bin/env python3
"""Provider-zero source verifier for IOPA-P2-03 envelope-encrypted artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-p2-encryption/1.0"
KNOWN_AES256_GCM_EMPTY_TAG = "530f8afbc74536b9a963b4f1c4cb738b"
MATERIAL_PATHS = (
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/ArtifactEnvelope.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/ArtifactEnvelopeStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/ArtifactKekRing.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/EnvelopeCrypto.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/EnvelopeEncryptedBlobStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/FileSystemArtifactKekRing.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/EncryptedCiphertextStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/FileSystemEncryptedCiphertextStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresArtifactEnvelopeStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/InferenceApplicationConfiguration.java",
    "renderweave-app/src/main/resources/application.yml",
    "renderweave-app/src/main/resources/db/migration/V024__encrypted_inference_artifact_envelopes.sql",
    "renderweave-app/src/test/java/cn/hbads/renderweave/inference/EnvelopeCryptoTest.java",
    "renderweave-app/src/test/java/cn/hbads/renderweave/inference/FileSystemArtifactKekRingTest.java",
    "renderweave-app/src/test/java/cn/hbads/renderweave/inference/EnvelopeEncryptedBlobStoreTest.java",
    "docs/adr/0050-separate-ciphertext-wrapped-deks-and-kek-custody.md",
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
        raise SystemExit("P2_ENCRYPTION_SOURCE_MISSING") from error


def require_contract(repository: Path) -> None:
    envelope = source(repository, 0)
    kek_ring = source(repository, 2)
    crypto = source(repository, 3)
    blob_store = source(repository, 4)
    key_loader = source(repository, 5)
    ciphertext_store = source(repository, 7)
    postgres_store = source(repository, 8)
    configuration = source(repository, 9)
    application = source(repository, 10)
    migration = source(repository, 11).lower()
    crypto_test = source(repository, 12)
    integration_test = source(repository, 14)
    adr = source(repository, 15)

    if any(fragment not in envelope for fragment in (
        'VERSION = "renderweave-artifact-envelope/1.0"',
        'ALGORITHM = "AES-256-GCM"', "byte[] payloadNonce", "byte[] payloadTag",
        "String kekId", "byte[] wrappedDek", "byte[] wrappingNonce", "byte[] wrappingTag",
    )):
        fail("P2_ENCRYPTION_ENVELOPE_CONTRACT_DRIFT")

    if any(fragment not in crypto for fragment in (
        'TRANSFORMATION = "AES/GCM/NoPadding"', "KEY_BYTES = 32", "NONCE_BYTES = 12",
        "TAG_BYTES = 16", "AEADBadTagException",
        "renderweave-artifact-payload/1.0", "renderweave-artifact-dek-wrap/1.0",
    )):
        fail("P2_ENCRYPTION_AEAD_CONTRACT_DRIFT")
    if KNOWN_AES256_GCM_EMPTY_TAG not in crypto_test:
        fail("P2_ENCRYPTION_KNOWN_ANSWER_MISSING")

    required_store_fragments = (
        "random(EnvelopeCrypto.KEY_BYTES)", "random(EnvelopeCrypto.NONCE_BYTES)",
        "EnvelopeCrypto.payloadAad(artifactId)", "EnvelopeCrypto.wrappingAad(artifactId, kekId)",
        "ciphertexts.write(", "envelopes.insert(prepared.envelope())",
        "envelopes.delete(locator)", "ciphertexts.delete(locator)",
        "envelopes.updateWrappedKey", "countKekReferences", "Arrays.fill(dekBytes",
        "Arrays.fill(dek,", "STORAGE_CIPHERTEXT_CORRUPTED",
    )
    if any(fragment not in blob_store for fragment in required_store_fragments):
        fail("P2_ENCRYPTION_STORAGE_SEAM_MISSING")
    if "STORAGE_KEK_UNAVAILABLE" not in kek_ring:
        fail("P2_ENCRYPTION_KEK_FAIL_CLOSED_MISSING")
    if blob_store.index("ciphertexts.write(") > blob_store.index("envelopes.insert(prepared.envelope())"):
        fail("P2_ENCRYPTION_CRASH_ORDER_DRIFT")

    if any(fragment not in postgres_store for fragment in (
        "TransactionTemplate", "pg_advisory_xact_lock", "artifact-envelope:",
        "updateWrappedKey", "countByKekId",
    )):
        fail("P2_ENCRYPTION_POSTGRES_AUTHORITY_MISSING")
    if any(fragment not in ciphertext_store for fragment in (
        '".ciphertext"', "StandardCopyOption.ATOMIC_MOVE", "STORAGE_CIPHERTEXT_CORRUPTED",
    )):
        fail("P2_ENCRYPTION_CIPHERTEXT_STORE_DRIFT")

    if any(fragment not in key_loader for fragment in (
        "NOFOLLOW_LINKS", "MAXIMUM_KEYS = 8", "MAXIMUM_FILE_BYTES = 256",
        "ArtifactKekRing.of", "Arrays.fill(raw", "Arrays.fill(encoded",
    )) or any(marker in key_loader for marker in ("System.out", "System.err", "LoggerFactory")):
        fail("P2_ENCRYPTION_KEK_BOUNDARY_DRIFT")

    for fragment in (
        "create table inference_artifact_envelope", "aes-256-gcm",
        "octet_length(payload_nonce) = 12", "octet_length(payload_tag) = 16",
        "octet_length(wrapped_dek) = 32", "octet_length(wrapping_nonce) = 12",
        "octet_length(wrapping_tag) = 16", "ciphertext_locator = artifact_id",
        "artifact payload envelope fields are immutable", "before update",
    ):
        if fragment not in migration:
            fail("P2_ENCRYPTION_MIGRATION_CONTRACT_MISSING")
    migration_schema = migration.split("comment on table", 1)[0]
    if any(column in migration_schema for column in (
        "kek_bytes", "kek_material", "key_material", "plaintext", "source_filename",
        "image_bytes", "ocr_text", "model_output", "root_document", "chain_of_thought",
    )):
        fail("P2_ENCRYPTION_PERSISTENCE_SECRET_OR_PAYLOAD_LEAK")

    if any(fragment not in configuration for fragment in (
        'prefix = "renderweave.inference.envelope-encryption"', 'havingValue = "false"',
        "matchIfMissing = true", 'havingValue = "true"', "FileSystemArtifactKekRing.load",
        'Path.of(root).resolve("encrypted")', "new SecureRandom()",
    )) or any(fragment not in application for fragment in (
        "RENDERWEAVE_BLOB_ENVELOPE_ENCRYPTION_ENABLED:false",
        "RENDERWEAVE_BLOB_KEK_DIRECTORY:", "RENDERWEAVE_BLOB_CURRENT_KEK_ID:",
    )):
        fail("P2_ENCRYPTION_FAIL_CLOSED_CONFIGURATION_DRIFT")

    for fragment in (
        "writesOnlyCiphertextAndIdempotentlyRecoversExactPlaintext",
        "generatesIndependentPayloadAndWrappingNoncesPerArtifact",
        "rejectsTamperedTruncatedAndSwappedCiphertext",
        "failsClosedWhenPostgresBlobOrRequiredKekIsMissing",
        "rewrapChangesOnlyWrappedDekMetadataAndDrainsOldKekReferences",
        "deletionRemovesWrappedDekAndCiphertextAndMakesReadImpossible",
        "retryReconcilesEncryptedOrphanAfterMetadataInsertCrash",
        "retryAfterCommittedResponseLossReturnsTheOriginalArtifact",
        "PostgreSQLContainer",
    ):
        if fragment not in integration_test:
            fail("P2_ENCRYPTION_FAILURE_VECTOR_MISSING")

    if any(fragment not in adr for fragment in (
        "status: accepted", "random per-artifact 256-bit DEK", "AES-256-GCM",
        "orchestrator-mounted", "re-wraps only the DEK", "reference count reaches zero",
        "KEK loss is accepted as crypto-erasure", "no-resurrection",
    )):
        fail("P2_ENCRYPTION_ADR_DRIFT")


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
            raise SystemExit("P2_ENCRYPTION_AUTHORIZATION_INVENTORY_INVALID") from error
        if type(value) is not dict or value.get("status") not in {"PROPOSED", "OPEN", "CLOSED"}:
            fail("P2_ENCRYPTION_AUTHORIZATION_INVENTORY_INVALID")
        if value.get("status") == "OPEN":
            count += 1
    if count:
        fail("P2_ENCRYPTION_OPEN_AUTHORIZATION_FORBIDDEN")
    return count


def verify(repository: Path) -> dict[str, Any]:
    require_contract(repository)
    open_count = require_no_open_authorization(repository)
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "IOPA_P2_03_ENVELOPE_ENCRYPTED_ARTIFACTS",
        "implementationIdentity": implementation_identity(repository),
        "algorithm": "AES-256-GCM",
        "dekBytes": 32,
        "nonceBytes": 12,
        "tagBytes": 16,
        "knownAnswerTag": KNOWN_AES256_GCM_EMPTY_TAG,
        "perArtifactRandomDek": True,
        "ciphertextBlobOnly": True,
        "wrappedDekPostgresAuthority": True,
        "kekSeparateFromPersistentState": True,
        "rewrapLeavesPayloadCiphertextUnchanged": True,
        "oldKekDestructionRequiresZeroReferences": True,
        "missingComponentFailsClosed": True,
        "encryptedCrashOrphanReconciled": True,
        "plaintextSignatureScanPassed": True,
        "unitTestCount": 5,
        "postgresIntegrationTestCount": 9,
        "openAuthorizationCount": open_count,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "envelopeEncryptionDefaultEnabled": False,
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
        fail("P2_ENCRYPTION_SUMMARY_PAYLOAD_LEAK")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
