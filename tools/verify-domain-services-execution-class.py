#!/usr/bin/env python3
"""Independently verify the DOMAIN_SERVICES exact target and product replay closure."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


EXECUTION_CLASS = "EXEC::DOMAIN_SERVICES::1.0"
TARGET_ID = "DOMAIN_SERVICES_TARGET::ASSET_AND_POSTGRESQL::1.0"
TARGET_PATH = ".scratch/renderweave-template-v1/domain-services/execution-class-target-v1.json"
TARGET_VERSION = "renderweave-domain-services-execution-class-target/1.0"
EXECUTOR_VERSION = "renderweave-domain-services-executor-manifest/1.0"
POSTGRES_IMAGE = "postgres:16-alpine"
POSTGRES_DIGEST = "sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50"
REVISION = re.compile(r"[0-9a-f]{40}")


class VerificationFailure(RuntimeError):
    pass


def require(condition: bool, code: str, detail: Any) -> None:
    if not condition:
        raise VerificationFailure(f"{code}: {detail}")


def duplicate_safe_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationFailure(f"JSON_DUPLICATE_MEMBER: {key}")
        result[key] = value
    return result


def read_json(path: Path) -> tuple[bytes, dict[str, Any]]:
    data = path.read_bytes()
    require(not data.startswith(b"\xef\xbb\xbf"), "JSON_BOM", path)
    require(data.endswith(b"\n") and b"\r" not in data, "JSON_TEXT_FORMAT", path)
    value = json.loads(
        data.decode("utf-8", "strict"),
        object_pairs_hook=duplicate_safe_pairs,
        parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
    )
    require(isinstance(value, dict), "JSON_ROOT", path)
    return data, value


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def binding(path: str, data: bytes) -> dict[str, Any]:
    return {"path": path, "sha256": sha256(data), "byteLength": len(data)}


def git_blob(repo: Path, revision: str, path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def json_lines(data: bytes) -> list[tuple[bytes, dict[str, Any]]]:
    records = []
    for line in data.splitlines():
        if line:
            records.append((line + b"\n", json.loads(line, object_pairs_hook=duplicate_safe_pairs)))
    return records


def validate_target(repo: Path, target_path: Path) -> tuple[bytes, dict[str, Any]]:
    target_bytes, target = read_json(target_path)
    require(set(target) == {
        "artifactVersion", "targetId", "status", "implementationRevision",
        "executionClass", "requiredExecutorRoles", "componentTargets", "artifacts",
        "assignedCorpus", "postgresqlIntegration", "formalRegistryBoundary", "boundary",
    }, "TARGET_MEMBERS", sorted(target))
    require(target["artifactVersion"] == TARGET_VERSION, "TARGET_VERSION", target["artifactVersion"])
    require(target["targetId"] == TARGET_ID, "TARGET_ID", target["targetId"])
    require(target["status"] == "FROZEN_PREISSUANCE_EXACT_TARGET", "TARGET_STATUS", target["status"])
    revision = target["implementationRevision"]
    require(isinstance(revision, str) and REVISION.fullmatch(revision) is not None,
            "TARGET_REVISION", revision)
    require(target["executionClass"] == EXECUTION_CLASS, "TARGET_CLASS", target["executionClass"])
    require(target["requiredExecutorRoles"] == [
        "java-domain-authority", "transactional-integration-replayer"
    ], "TARGET_ROLES", target["requiredExecutorRoles"])

    role_names: set[str] = set()
    for artifact in target["artifacts"]:
        require(set(artifact) == {"role", "path", "sha256", "byteLength"},
                "TARGET_ARTIFACT_MEMBERS", artifact)
        role = artifact["role"]
        require(role not in role_names, "TARGET_ARTIFACT_ROLE_DUPLICATE", role)
        role_names.add(role)
        expected = binding(artifact["path"], git_blob(repo, revision, artifact["path"]))
        require(artifact == {"role": role, **expected}, "TARGET_ARTIFACT_BINDING", role)
        current = git_blob(repo, "HEAD", artifact["path"])
        require(current == git_blob(repo, revision, artifact["path"]),
                "TARGET_ARTIFACT_HEAD_DRIFT", artifact["path"])
    require(len(role_names) == 16, "TARGET_ARTIFACT_COUNT", len(role_names))

    component = target["componentTargets"]
    require(isinstance(component, list) and len(component) == 1,
            "TARGET_COMPONENT_COUNT", component)
    component_path = ".scratch/renderweave-template-v1/domain-services/product-execution-target-v1.json"
    component_bytes = git_blob(repo, revision, component_path)
    require(component[0] == binding(component_path, component_bytes),
            "TARGET_COMPONENT_BINDING", component[0])
    component_value = json.loads(component_bytes)
    require(component_value.get("targetId") ==
            "DOMAIN_SERVICES_CAPACITY_TARGET::ASSET_CONTENT_GUARD::1.0",
            "TARGET_COMPONENT_ID", component_value.get("targetId"))
    require(component_value.get("boundary", {}).get("formalRecordsIssued") is False,
            "TARGET_COMPONENT_FORMAL_BOUNDARY", component_value.get("boundary"))

    candidate_cases_path = ".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-cases-v1.jsonl"
    candidate_oracles_path = ".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-oracles-v1.jsonl"
    case_source = git_blob(repo, revision, candidate_cases_path)
    oracle_source = git_blob(repo, revision, candidate_oracles_path)
    cases = [raw for raw, record in json_lines(case_source)
             if record.get("executionClass") == EXECUTION_CLASS]
    oracle_ids = {
        edge["oracleId"]
        for _, record in json_lines(case_source)
        if record.get("executionClass") == EXECUTION_CLASS
        for coverage in record["coverage"]
        for edge in coverage["evidence"]
    }
    oracles = [raw for raw, record in json_lines(oracle_source)
               if record.get("oracleId") in oracle_ids]
    require((len(cases), len(oracles), len(oracle_ids)) == (12, 12, 12),
            "TARGET_ASSIGNED_COUNTS", (len(cases), len(oracles), len(oracle_ids)))
    case_bytes = b"".join(cases)
    oracle_bytes = b"".join(oracles)
    assigned = target["assignedCorpus"]
    expected_assigned = {
        "sourceCases": binding(candidate_cases_path, case_source),
        "sourceOracles": binding(candidate_oracles_path, oracle_source),
        "assignedCaseCount": 12,
        "assignedOracleCount": 12,
        "assignedCasesSha256": sha256(case_bytes),
        "assignedOraclesSha256": sha256(oracle_bytes),
        "assignedCorpusDigest": sha256(
            b"renderweave-domain-services-assigned-corpus/1\0"
            + case_bytes + b"\0" + oracle_bytes),
    }
    require(assigned == expected_assigned, "TARGET_ASSIGNED_CORPUS", assigned)

    require(target["postgresqlIntegration"] == {
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
    }, "TARGET_POSTGRESQL", target["postgresqlIntegration"])

    formal = target["formalRegistryBoundary"]
    for key, path in (
        ("cases", ".scratch/renderweave-template-v1/conformance-cases-v1.jsonl"),
        ("oracles", ".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl"),
    ):
        data = git_blob(repo, revision, path)
        require(formal[key] == {**binding(path, data), "recordCount": 46},
                "TARGET_FORMAL_BINDING", key)
    require(formal["issuedDomainCaseCount"] == 0
            and formal["issuedDomainOracleCount"] == 0
            and formal["appendPerformed"] is False,
            "TARGET_FORMAL_COUNTS", formal)
    require(target["boundary"] == {
        "productApiSurfaceCreated": False,
        "nativeRendererInvoked": False,
        "externalProviderAttemptsAllowed": False,
        "formalRecordsIssued": False,
        "preissuanceReplayRequired": True,
        "recordIssuanceAllowed": False,
        "executionClassExecutable": False,
    }, "TARGET_BOUNDARY", target["boundary"])
    return target_bytes, target


def validate_executor(
        path: Path,
        role: str,
        target_bytes: bytes,
        target: dict[str, Any],
) -> tuple[bytes, dict[str, Any]]:
    data, manifest = read_json(path)
    common = {
        "artifactVersion", "executorId", "role", "executionClass", "targetId",
        "targetManifest", "implementationRevision", "runtime", "entrypoint", "command",
        "sharedSemanticLibrary", "networkReadsAllowed", "productMutationAllowed",
    }
    expected_members = common | ({"componentTarget"} if role == "java-domain-authority"
                                 else {"postgresqlImage"})
    require(set(manifest) == expected_members, "EXECUTOR_MEMBERS", (role, sorted(manifest)))
    require(manifest["artifactVersion"] == EXECUTOR_VERSION, "EXECUTOR_VERSION", role)
    require(manifest["role"] == role, "EXECUTOR_ROLE", manifest["role"])
    require(manifest["executionClass"] == EXECUTION_CLASS, "EXECUTOR_CLASS", role)
    require(manifest["targetId"] == TARGET_ID, "EXECUTOR_TARGET_ID", role)
    require(manifest["targetManifest"] == binding(TARGET_PATH, target_bytes),
            "EXECUTOR_TARGET_BINDING", role)
    require(manifest["implementationRevision"] == target["implementationRevision"],
            "EXECUTOR_REVISION", role)
    require(manifest["sharedSemanticLibrary"] is None, "EXECUTOR_SHARED_LIBRARY", role)
    if role == "java-domain-authority":
        require(manifest["executorId"] == "DOMAIN_SERVICES_EXECUTOR::JAVA_DOMAIN_AUTHORITY::1.0",
                "EXECUTOR_ID", role)
        require(manifest["runtime"] == "Java 21", "EXECUTOR_RUNTIME", role)
        require(manifest["networkReadsAllowed"] is False
                and manifest["productMutationAllowed"] is False,
                "EXECUTOR_BOUNDARY", role)
        require(manifest["componentTarget"] == target["componentTargets"][0],
                "EXECUTOR_COMPONENT", role)
    else:
        require(manifest["executorId"] ==
                "DOMAIN_SERVICES_EXECUTOR::POSTGRESQL_TRANSACTION_REPLAYER::1.0",
                "EXECUTOR_ID", role)
        require(manifest["runtime"] == "Java 21 + PostgreSQL 16 Testcontainers",
                "EXECUTOR_RUNTIME", role)
        require(manifest["postgresqlImage"] == {
            "reference": POSTGRES_IMAGE, "digest": POSTGRES_DIGEST
        }, "EXECUTOR_POSTGRESQL", manifest["postgresqlImage"])
        require(manifest["networkReadsAllowed"] == "LOCAL_TESTCONTAINERS_DOCKER_ONLY"
                and manifest["productMutationAllowed"] == "EPHEMERAL_POSTGRESQL_CONTAINER_ONLY",
                "EXECUTOR_BOUNDARY", role)
    return data, manifest


def validate_capacity_reports(
        primary_path: Path,
        independent_path: Path,
        target: dict[str, Any],
) -> tuple[bytes, bytes]:
    primary_bytes, primary = read_json(primary_path)
    independent_bytes, independent = read_json(independent_path)
    require(primary.get("reportVersion") == "renderweave-domain-services-capacity-primary/1",
            "CAPACITY_PRIMARY_VERSION", primary.get("reportVersion"))
    require(primary.get("engine") == "java-domain-authority"
            and primary.get("assurance") == "A1_EXACT_PRODUCT_EXECUTION",
            "CAPACITY_PRIMARY_ROLE", primary)
    require((primary.get("caseCount"), primary.get("passed"), primary.get("failed")) == (12, 12, 0),
            "CAPACITY_PRIMARY_COUNTS", primary)
    component = target["componentTargets"][0]
    require(primary.get("targetManifest") == {
        "path": ".scratch/renderweave-template-v1/domain-services/product-execution-target-v1.json",
        "sha256": component["sha256"],
        "byteLength": component["byteLength"],
    }, "CAPACITY_PRIMARY_TARGET", primary.get("targetManifest"))
    require(independent.get("reportVersion") ==
            "renderweave-domain-services-capacity-independent/1",
            "CAPACITY_INDEPENDENT_VERSION", independent.get("reportVersion"))
    require(independent.get("engine") == "python-independent"
            and independent.get("assurance") == "A2_EXACT_OBSERVATION_REPLAY",
            "CAPACITY_INDEPENDENT_ROLE", independent)
    require((independent.get("caseCount"), independent.get("passed"), independent.get("failed"))
            == (12, 12, 0), "CAPACITY_INDEPENDENT_COUNTS", independent)
    require(independent.get("implementationRevision") == primary.get("implementationRevision"),
            "CAPACITY_REVISION_AGREEMENT", independent.get("implementationRevision"))
    return primary_bytes, independent_bytes


def validate_transactional_report(
        path: Path,
        target_bytes: bytes,
        target: dict[str, Any],
) -> bytes:
    data, report = read_json(path)
    require(set(report) == {
        "reportVersion", "engine", "role", "assurance", "executionClass",
        "targetManifest", "implementationRevision", "postgresql", "scenarioCount",
        "passed", "failed", "scenarios", "boundary",
    }, "TRANSACTION_REPORT_MEMBERS", sorted(report))
    require(report["reportVersion"] == "renderweave-domain-services-transactional-replay/1",
            "TRANSACTION_REPORT_VERSION", report["reportVersion"])
    require(report["engine"] == "java-postgresql-integration"
            and report["role"] == "transactional-integration-replayer"
            and report["assurance"] == "A2_INDEPENDENT_PRODUCT_REPLAY",
            "TRANSACTION_REPORT_ROLE", report)
    require(report["executionClass"] == EXECUTION_CLASS, "TRANSACTION_REPORT_CLASS", report)
    require(report["targetManifest"] == binding(TARGET_PATH, target_bytes),
            "TRANSACTION_REPORT_TARGET", report["targetManifest"])
    require(report["implementationRevision"] == target["implementationRevision"],
            "TRANSACTION_REPORT_REVISION", report["implementationRevision"])
    postgresql = report["postgresql"]
    require(postgresql["imageReference"] == POSTGRES_IMAGE
            and postgresql["expectedImageDigest"] == POSTGRES_DIGEST
            and postgresql["runtimeImageDigest"] == POSTGRES_DIGEST,
            "TRANSACTION_REPORT_IMAGE", postgresql)
    require(re.fullmatch(r"16\.[0-9]+(?:\.[0-9]+)?", postgresql["serverVersion"]) is not None,
            "TRANSACTION_REPORT_SERVER", postgresql["serverVersion"])
    require(postgresql["requiredMigrations"] == [
        "V028__asset_aggregate_and_content.sql", "V029__asset_audit_events.sql"
    ], "TRANSACTION_REPORT_MIGRATIONS", postgresql)

    empty = {
        "aggregateCount": 0, "contentRevisionCount": 0, "auditEventCount": 0,
        "idempotencyCount": 0, "usedBytes": 0,
    }
    committed = {
        "aggregateCount": 1, "contentRevisionCount": 1, "auditEventCount": 1,
        "idempotencyCount": 1, "usedBytes": 332,
    }
    require(report["scenarios"] == [
        {
            "scenarioId": "DOMAIN_TX::COMMIT_CREATE",
            "outcome": "CREATED",
            "transactionCommitted": True,
            "before": empty,
            "after": committed,
        },
        {
            "scenarioId": "DOMAIN_TX::IDEMPOTENCY_READS",
            "outcome": ["REPLAY", "CONFLICT"],
            "transactionCommitted": False,
            "snapshotUnchanged": True,
            "after": committed,
        },
        {
            "scenarioId": "DOMAIN_TX::DUPLICATE_KEY_ROLLBACK",
            "outcome": "ASSET_ID_COLLISION",
            "transactionCommitted": False,
            "attemptedAssetRowCount": 0,
            "snapshotUnchanged": True,
            "after": committed,
        },
    ], "TRANSACTION_REPORT_SCENARIOS", report["scenarios"])
    require((report["scenarioCount"], report["passed"], report["failed"]) == (3, 3, 0),
            "TRANSACTION_REPORT_COUNTS", report)
    require(report["boundary"] == {
        "rawAssetBytesRead": False,
        "externalNetworkAllowed": False,
        "rendererInvoked": False,
        "candidateCaseCount": 12,
        "formalRecordsIssued": 0,
        "recordIssuanceAllowed": False,
    }, "TRANSACTION_REPORT_BOUNDARY", report["boundary"])
    return data


def write_report(
        output: Path,
        target_path: Path,
        target_bytes: bytes,
        target: dict[str, Any],
        inputs: list[tuple[str, Path, bytes]],
) -> None:
    report = {
        "reportVersion": "renderweave-domain-services-execution-class-independent/1",
        "engine": "python-independent-closure-verifier",
        "assurance": "A2_PREISSUANCE_PRODUCT_REPLAY",
        "executionClass": EXECUTION_CLASS,
        "targetManifest": binding(TARGET_PATH, target_bytes),
        "implementationRevision": target["implementationRevision"],
        "assignedCorpusDigest": target["assignedCorpus"]["assignedCorpusDigest"],
        "executorRoleCount": 2,
        "capacityCaseCount": 12,
        "transactionScenarioCount": 3,
        "passedExecutorRoles": [
            "java-domain-authority", "transactional-integration-replayer"
        ],
        "inputBindings": [binding(name, data) for name, _, data in inputs],
        "boundary": {
            "formalRecordsIssued": 0,
            "preissuanceReady": True,
            "recordAppendMayProceedInSeparateTicket": True,
            "executionClassExecutable": False,
            "rendererReady": False,
            "externalProviderAttempts": 0,
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--java-executor", type=Path, required=True)
    parser.add_argument("--transactional-executor", type=Path, required=True)
    parser.add_argument("--capacity-primary", type=Path, required=True)
    parser.add_argument("--capacity-independent", type=Path, required=True)
    parser.add_argument("--transactional-report", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    target_bytes, target = validate_target(repo, args.target.resolve())
    java_bytes, _ = validate_executor(
        args.java_executor.resolve(), "java-domain-authority", target_bytes, target)
    transactional_executor_bytes, _ = validate_executor(
        args.transactional_executor.resolve(), "transactional-integration-replayer",
        target_bytes, target)
    capacity_primary_bytes, capacity_independent_bytes = validate_capacity_reports(
        args.capacity_primary.resolve(), args.capacity_independent.resolve(), target)
    transaction_bytes = validate_transactional_report(
        args.transactional_report.resolve(), target_bytes, target)
    inputs = [
        ("domain-services/execution-class-target-v1.json", args.target, target_bytes),
        ("domain-services/java-domain-authority-executor-manifest-v1.json",
         args.java_executor, java_bytes),
        ("domain-services/transactional-integration-replayer-manifest-v1.json",
         args.transactional_executor, transactional_executor_bytes),
        (args.capacity_primary.name, args.capacity_primary, capacity_primary_bytes),
        (args.capacity_independent.name, args.capacity_independent, capacity_independent_bytes),
        (args.transactional_report.name, args.transactional_report, transaction_bytes),
    ]
    write_report(args.report.resolve(), args.target.resolve(), target_bytes, target, inputs)
    print("DOMAIN_SERVICES execution class: 2/2 roles, 12/12 capacity, 3/3 transaction PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, VerificationFailure, ValueError) as failure:
        print(f"DOMAIN_SERVICES execution class failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure
