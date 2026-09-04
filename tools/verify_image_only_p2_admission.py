#!/usr/bin/env python3
"""Provider-zero source replay for IOPA-P2-01 gateway identity admission."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-p2-admission/1.0"
ASSERTION_VERSION = "renderweave-gateway-assertion/1.0"
DIGEST_VERSION = "renderweave-gateway-idempotency-key/1.0"
FORBIDDEN_SUMMARY_MARKERS = (
    "api key", "private key", "compactjws", "authorization:", "data:image",
    "base64", "filename", "ocrtext", "modeloutput", "rootdocument", "chain-of-thought",
)
MATERIAL_PATHS = (
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/GatewayAssertionAuthority.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/GatewayAssertionRequest.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/GatewayRequestIdentity.java",
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/admission/GatewayAssertionReplayStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/PostgresGatewayAssertionReplayStore.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/GatewayPublicKeySet.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/GatewayAssertionFilter.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/InternalActuatorMtlsFilter.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/ProductionEdgeSecurityConfiguration.java",
    "renderweave-app/src/main/resources/db/migration/V022__gateway_assertion_replay_guard.sql",
    "renderweave-app/src/main/resources/application.yml",
    "docker/nginx.conf",
    "docs/adr/0048-single-gateway-assertion-authority.md",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def source(repository: Path, relative: str) -> str:
    path = repository / relative
    try:
        return path.read_text(encoding="utf-8")
    except Exception as error:
        raise SystemExit("P2_ADMISSION_SOURCE_MISSING") from error


def implementation_identity(repository: Path) -> str:
    digest = hashlib.sha256()
    for relative in MATERIAL_PATHS:
        raw = (repository / relative).read_bytes()
        encoded = relative.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii") + b":" + encoded + b"\n")
        digest.update(str(len(raw)).encode("ascii") + b":" + raw + b"\n")
    return "renderweave-image-only-p2-admission/1.0:" + digest.hexdigest()


def require_contract(repository: Path) -> None:
    authority = source(repository, MATERIAL_PATHS[0])
    required_authority = (
        f'ASSERTION_VERSION = "{ASSERTION_VERSION}"',
        f'"{DIGEST_VERSION}"',
        'Duration.ofSeconds(60)',
        'Duration.ofSeconds(30)',
        '"EdDSA"',
        'Signature.getInstance("Ed25519")',
        'StreamReadFeature.STRICT_DUPLICATE_DETECTION',
        'FAIL_ON_UNKNOWN_PROPERTIES',
        'GATEWAY_ASSERTION_REPLAY_GUARD_UNAVAILABLE',
        'TIME_AUTHORITY_UNAVAILABLE',
        'replayStore.consume(identity, now)',
    )
    if any(fragment not in authority for fragment in required_authority):
        fail("P2_ADMISSION_AUTHORITY_CONTRACT_MISSING")
    if authority.index("requireValidSignature(parts, key)") > authority.index(
            "replayStore.consume(identity, now)"):
        fail("P2_ADMISSION_REPLAY_BEFORE_SIGNATURE")

    store = source(repository, MATERIAL_PATHS[4])
    migration = source(repository, MATERIAL_PATHS[9]).lower()
    if ('on conflict (jti) do nothing' not in store.lower()
            or 'gateway_assertion_replay' not in migration
            or "interval '60 seconds'" not in migration
            or "interval '30 seconds'" not in migration):
        fail("P2_ADMISSION_REPLAY_STORE_CONTRACT_MISSING")
    if any(marker in migration for marker in (
            "compact_jws", "full_token", "email", "filename", "payload", "private_key"
    )):
        fail("P2_ADMISSION_REPLAY_STORE_PAYLOAD_LEAK")

    filters = source(repository, MATERIAL_PATHS[6]) + source(repository, MATERIAL_PATHS[7])
    configuration = source(repository, MATERIAL_PATHS[8])
    application = source(repository, MATERIAL_PATHS[10])
    nginx = source(repository, MATERIAL_PATHS[11])
    if any(fragment not in filters + configuration + application for fragment in (
        "X-RenderWeave-Gateway-Assertion",
        "GATEWAY_MTLS_IDENTITY_INVALID",
        "ACTUATOR_MTLS_IDENTITY_INVALID",
        "allowed-certificate-sha256",
        "client-auth: ${RENDERWEAVE_API_MTLS_CLIENT_AUTH:need}",
        "client-auth: ${RENDERWEAVE_MANAGEMENT_MTLS_CLIENT_AUTH:need}",
        "port: ${RENDERWEAVE_MANAGEMENT_PORT:9090}",
    )):
        fail("P2_ADMISSION_EDGE_CONTRACT_MISSING")
    if ('location ^~ /actuator/' not in nginx or 'return 404;' not in nginx
            or 'proxy_set_header X-RenderWeave-Actor-Id "";' not in nginx
            or 'proxy_set_header X-RenderWeave-Request-Id "";' not in nginx):
        fail("P2_ADMISSION_PUBLIC_EDGE_NOT_CLOSED")
    actuator_block = nginx.split("location ^~ /actuator/", 1)[1].split("}", 1)[0]
    if "proxy_pass" in actuator_block:
        fail("P2_ADMISSION_PUBLIC_ACTUATOR_EXPOSED")

    adr = source(repository, MATERIAL_PATHS[12])
    if any(fragment not in adr for fragment in (
            "状态：accepted", "EdDSA/Ed25519", "PostgreSQL",
            "不构成 `ExternalTransferConfirmation`", "Candidate apply/publish"
    )):
        fail("P2_ADMISSION_ADR_DRIFT")


def require_no_open_authorization(repository: Path) -> int:
    count = 0
    for path in (repository / "plans/live-canary-authorizations").glob("20*.json"):
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except Exception as error:
            raise SystemExit("P2_ADMISSION_AUTHORIZATION_INVENTORY_INVALID") from error
        if type(value) is not dict or value.get("status") not in {"PROPOSED", "OPEN", "CLOSED"}:
            fail("P2_ADMISSION_AUTHORIZATION_INVENTORY_INVALID")
        if value.get("status") == "OPEN":
            count += 1
    if count:
        fail("P2_ADMISSION_OPEN_AUTHORIZATION_FORBIDDEN")
    return count


def verify(repository: Path) -> dict[str, Any]:
    require_contract(repository)
    open_count = require_no_open_authorization(repository)
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "IOPA_P2_01_GATEWAY_IDENTITY",
        "implementationIdentity": implementation_identity(repository),
        "gatewayAssertionVersion": ASSERTION_VERSION,
        "jwsAlgorithm": "EdDSA/Ed25519",
        "maximumAssertionLifetimeSeconds": 60,
        "assertionClockSkewSeconds": 30,
        "idempotencyDigestVersion": DIGEST_VERSION,
        "mutationReplayStore": "PostgreSQL/V022",
        "mutationJtiAtomicConsume": True,
        "timeRollbackFailClosed": True,
        "gatewayMtlsExactCertificateFingerprint": True,
        "actuatorMtlsExactCertificateFingerprint": True,
        "publicActuatorExposed": False,
        "clientIdentityHeadersTrusted": False,
        "fullAssertionPersisted": False,
        "openAuthorizationCount": open_count,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "externalTransferConfirmationGranted": False,
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
        fail("P2_ADMISSION_SUMMARY_PAYLOAD_LEAK")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
