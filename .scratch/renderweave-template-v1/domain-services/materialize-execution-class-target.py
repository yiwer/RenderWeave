#!/usr/bin/env python3
"""Freeze the exact DOMAIN_SERVICES preissuance target and both executor manifests."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any


EXECUTION_CLASS = "EXEC::DOMAIN_SERVICES::1.0"
TARGET_ID = "DOMAIN_SERVICES_TARGET::ASSET_AND_POSTGRESQL::1.0"
TARGET_PATH = ".scratch/renderweave-template-v1/domain-services/execution-class-target-v1.json"
POSTGRES_IMAGE = "postgres:16-alpine"
POSTGRES_DIGEST = "sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50"
ARTIFACTS = (
    ("capacityComponentTarget", ".scratch/renderweave-template-v1/domain-services/product-execution-target-v1.json"),
    ("observationAdapter", ".scratch/renderweave-template-v1/domain-services/observation-adapter-v1.json"),
    ("capacityCoverage", ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json"),
    ("capacityCandidateCases", ".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-cases-v1.jsonl"),
    ("capacityCandidateOracles", ".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-oracles-v1.jsonl"),
    ("domainApplication", "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/CanonicalAssetApplication.java"),
    ("persistenceSeam", "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/spi/AssetPersistence.java"),
    ("postgresqlAdapter", "renderweave-app/src/main/java/cn/hbads/renderweave/app/asset/PostgresAssetPersistence.java"),
    ("aggregateMigration", "renderweave-app/src/main/resources/db/migration/V028__asset_aggregate_and_content.sql"),
    ("auditMigration", "renderweave-app/src/main/resources/db/migration/V029__asset_audit_events.sql"),
    ("javaDomainExecutor", "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/DomainServicesCapacityConformanceTest.java"),
    ("transactionalExecutor", "renderweave-app/src/test/java/cn/hbads/renderweave/app/asset/DomainServicesTransactionalConformanceTest.java"),
    ("capacityIndependentReplay", "tools/verify-domain-services-capacity.py"),
    ("executionClassIndependentReplay", "tools/verify-domain-services-execution-class.py"),
    ("executionGate", "tools/run-domain-services-gate.ps1"),
    ("targetMaterializer", ".scratch/renderweave-template-v1/domain-services/materialize-execution-class-target.py"),
)


def git(repo: Path, *arguments: str, text: bool = False) -> bytes | str:
    return subprocess.run(
        ["git", *arguments],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
        text=text,
    ).stdout


def blob(repo: Path, revision: str, path: str) -> bytes:
    result = git(repo, "show", f"{revision}:{path}")
    assert isinstance(result, bytes)
    return result


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def binding(path: str, data: bytes) -> dict[str, object]:
    return {"path": path, "sha256": sha256(data), "byteLength": len(data)}


def json_lines(data: bytes) -> list[tuple[bytes, dict[str, Any]]]:
    lines = []
    for raw in data.splitlines():
        if raw:
            lines.append((raw + b"\n", json.loads(raw)))
    return lines


def assigned_corpus(repo: Path, revision: str) -> dict[str, object]:
    case_path = ARTIFACTS[3][1]
    oracle_path = ARTIFACTS[4][1]
    case_source = blob(repo, revision, case_path)
    oracle_source = blob(repo, revision, oracle_path)
    cases = [
        raw for raw, record in json_lines(case_source)
        if record.get("executionClass") == EXECUTION_CLASS
    ]
    oracle_ids = {
        edge["oracleId"]
        for _, record in json_lines(case_source)
        if record.get("executionClass") == EXECUTION_CLASS
        for coverage in record["coverage"]
        for edge in coverage["evidence"]
    }
    oracles = [
        raw for raw, record in json_lines(oracle_source)
        if record.get("oracleId") in oracle_ids
    ]
    if len(cases) != 12 or len(oracles) != 12 or len(oracle_ids) != 12:
        raise SystemExit("DOMAIN_SERVICES assigned candidate corpus must be exactly 12 Case + 12 Oracle")
    case_bytes = b"".join(cases)
    oracle_bytes = b"".join(oracles)
    digest_input = (
        b"renderweave-domain-services-assigned-corpus/1\0"
        + case_bytes
        + b"\0"
        + oracle_bytes
    )
    return {
        "sourceCases": binding(case_path, case_source),
        "sourceOracles": binding(oracle_path, oracle_source),
        "assignedCaseCount": len(cases),
        "assignedOracleCount": len(oracles),
        "assignedCasesSha256": sha256(case_bytes),
        "assignedOraclesSha256": sha256(oracle_bytes),
        "assignedCorpusDigest": sha256(digest_input),
    }


def formal_boundary(repo: Path, revision: str) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, path in (
        ("cases", ".scratch/renderweave-template-v1/conformance-cases-v1.jsonl"),
        ("oracles", ".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl"),
    ):
        data = blob(repo, revision, path)
        records = json_lines(data)
        if len(records) != 46:
            raise SystemExit(f"formal {key} registry must remain at 46 records")
        if key == "cases" and any(
            record.get("executionClass") == EXECUTION_CLASS for _, record in records
        ):
            raise SystemExit("formal Case registry already contains DOMAIN_SERVICES records")
        result[key] = {**binding(path, data), "recordCount": len(records)}
    result["issuedDomainCaseCount"] = 0
    result["issuedDomainOracleCount"] = 0
    result["appendPerformed"] = False
    return result


def write_json(path: Path, value: dict[str, object]) -> bytes:
    data = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(data)
    return data


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--target-output", type=Path, required=True)
    parser.add_argument("--java-executor-output", type=Path, required=True)
    parser.add_argument("--transactional-executor-output", type=Path, required=True)
    arguments = parser.parse_args()
    repo = arguments.repo.resolve()
    resolved = git(repo, "rev-parse", "--verify", f"{arguments.revision}^{{commit}}", text=True)
    assert isinstance(resolved, str)
    resolved_revision = resolved.strip()
    if resolved_revision != arguments.revision:
        raise SystemExit("revision must be the exact forty-character commit identity")

    artifacts = [
        {"role": role, **binding(path, blob(repo, resolved_revision, path))}
        for role, path in ARTIFACTS
    ]
    component_path = ARTIFACTS[0][1]
    component_bytes = blob(repo, resolved_revision, component_path)
    component = json.loads(component_bytes)
    if component.get("targetId") != "DOMAIN_SERVICES_CAPACITY_TARGET::ASSET_CONTENT_GUARD::1.0":
        raise SystemExit("capacity component target drifted")

    target = {
        "artifactVersion": "renderweave-domain-services-execution-class-target/1.0",
        "targetId": TARGET_ID,
        "status": "FROZEN_PREISSUANCE_EXACT_TARGET",
        "implementationRevision": resolved_revision,
        "executionClass": EXECUTION_CLASS,
        "requiredExecutorRoles": [
            "java-domain-authority",
            "transactional-integration-replayer",
        ],
        "componentTargets": [binding(component_path, component_bytes)],
        "artifacts": artifacts,
        "assignedCorpus": assigned_corpus(repo, resolved_revision),
        "postgresqlIntegration": {
            "imageReference": POSTGRES_IMAGE,
            "imageDigest": POSTGRES_DIGEST,
            "majorVersion": "16",
            "adapter": "cn.hbads.renderweave.app.asset.PostgresAssetPersistence",
            "transactionScenarios": [
                "DOMAIN_TX::COMMIT_CREATE",
                "DOMAIN_TX::IDEMPOTENCY_READS",
                "DOMAIN_TX::DUPLICATE_KEY_ROLLBACK",
            ],
            "databaseSubstitutionAllowed": False,
        },
        "formalRegistryBoundary": formal_boundary(repo, resolved_revision),
        "boundary": {
            "productApiSurfaceCreated": False,
            "nativeRendererInvoked": False,
            "externalProviderAttemptsAllowed": False,
            "formalRecordsIssued": False,
            "preissuanceReplayRequired": True,
            "recordIssuanceAllowed": False,
            "executionClassExecutable": False,
        },
    }
    target_bytes = write_json(arguments.target_output, target)
    target_binding = binding(TARGET_PATH, target_bytes)

    executor_version = "renderweave-domain-services-executor-manifest/1.0"
    java_manifest = {
        "artifactVersion": executor_version,
        "executorId": "DOMAIN_SERVICES_EXECUTOR::JAVA_DOMAIN_AUTHORITY::1.0",
        "role": "java-domain-authority",
        "executionClass": EXECUTION_CLASS,
        "targetId": TARGET_ID,
        "targetManifest": target_binding,
        "implementationRevision": resolved_revision,
        "runtime": "Java 21",
        "entrypoint": binding(ARTIFACTS[10][1], blob(repo, resolved_revision, ARTIFACTS[10][1])),
        "command": "mvn -B -ntp -pl renderweave-asset -am -Drenderweave.domainServices.primaryReport=<evidence> -Drenderweave.domainServices.target=<capacity-target> test",
        "componentTarget": binding(component_path, component_bytes),
        "sharedSemanticLibrary": None,
        "networkReadsAllowed": False,
        "productMutationAllowed": False,
    }
    transactional_manifest = {
        "artifactVersion": executor_version,
        "executorId": "DOMAIN_SERVICES_EXECUTOR::POSTGRESQL_TRANSACTION_REPLAYER::1.0",
        "role": "transactional-integration-replayer",
        "executionClass": EXECUTION_CLASS,
        "targetId": TARGET_ID,
        "targetManifest": target_binding,
        "implementationRevision": resolved_revision,
        "runtime": "Java 21 + PostgreSQL 16 Testcontainers",
        "entrypoint": binding(ARTIFACTS[11][1], blob(repo, resolved_revision, ARTIFACTS[11][1])),
        "command": "mvn -B -ntp -pl renderweave-app -am -Dtest=cn.hbads.renderweave.app.asset.DomainServicesTransactionalConformanceTest -Drenderweave.domainServices.executionClassTarget=<target> -Drenderweave.domainServices.transactionalReport=<evidence> test",
        "postgresqlImage": {
            "reference": POSTGRES_IMAGE,
            "digest": POSTGRES_DIGEST,
        },
        "sharedSemanticLibrary": None,
        "networkReadsAllowed": "LOCAL_TESTCONTAINERS_DOCKER_ONLY",
        "productMutationAllowed": "EPHEMERAL_POSTGRESQL_CONTAINER_ONLY",
    }
    write_json(arguments.java_executor_output, java_manifest)
    write_json(arguments.transactional_executor_output, transactional_manifest)


if __name__ == "__main__":
    main()
