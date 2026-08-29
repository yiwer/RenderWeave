#!/usr/bin/env python3
"""Independently verify the DESIGN_INPUT_EXPRESSION preissuance closure."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


EXECUTION_CLASS = "EXEC::DESIGN_INPUT_EXPRESSION::1.0"
TARGET_ID = "DESIGN_INPUT_EXPRESSION_TARGET::JAVA_TYPESCRIPT_PRODUCT::1.0"
TARGET_PATH = ".scratch/renderweave-template-v1/design-input-expression/execution-class-target-v1.json"
COMPONENT_PATH = ".scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v17.json"
OBSERVATION_ADAPTER_PATH = ".scratch/renderweave-template-v1/design-input-expression/observation-adapter-v1.json"
CANDIDATE_CASES_PATH = ".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-cases-v1.jsonl"
CANDIDATE_ORACLES_PATH = ".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-oracles-v1.jsonl"
FORMAL_CASES_PATH = ".scratch/renderweave-template-v1/conformance-cases-v1.jsonl"
FORMAL_ORACLES_PATH = ".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl"
TARGET_VERSION = "renderweave-design-input-expression-execution-class-target/1.0"
EXECUTOR_VERSION = "renderweave-design-input-expression-executor-manifest/1.0"
COMPONENT_TARGET_ID = "DESIGN_INPUT_EXPRESSION_TARGET::CAPACITY_AUTHORITY_PRODUCT_WIRING_COMPLETE::17.0"
COMPONENT_IMPLEMENTATION_REVISION = "12a3f7e69b9a814358133c8d84ddc2b53da84789"
OBSERVATION_DIGEST = "sha256:e760c63ae7a2cf364adc33dc366150e31c5e03eb70c0348e4c2d82c8de304796"
JAVA_ENTRYPOINT = (
    "renderweave-template/src/test/java/cn/hbads/renderweave/template/internal/"
    "DesignInputExpressionCapacityConformanceTest.java"
)
TYPESCRIPT_ENTRYPOINT = "tools/verify-design-input-expression-capacity.ts"
ARTIFACT_PATHS = {
    "capacityComponentTarget": COMPONENT_PATH,
    "observationAdapter": OBSERVATION_ADAPTER_PATH,
    "capacityCoverage": ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json",
    "capacityCandidateCases": CANDIDATE_CASES_PATH,
    "capacityCandidateOracles": CANDIDATE_ORACLES_PATH,
    "javaSemanticExecutor": JAVA_ENTRYPOINT,
    "typescriptIndependentExecutor": TYPESCRIPT_ENTRYPOINT,
    "componentGate": "tools/run-design-input-expression-capacity-gate.ps1",
    "nodeToolchainProvisioner": "tools/ensure-node24.ps1",
    "executionClassIndependentReplay": "tools/verify-design-input-expression-execution-class.py",
    "executionClassGate": "tools/run-design-input-expression-execution-class-gate.ps1",
    "targetMaterializer": ".scratch/renderweave-template-v1/design-input-expression/materialize-execution-class-target.py",
}
REVISION = re.compile(r"[0-9a-f]{40}")


class VerificationFailure(RuntimeError):
    pass


def require(condition: bool, code: str, detail: Any) -> None:
    if not condition:
        raise VerificationFailure(f"{code}: {detail}")


def duplicate_safe_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise VerificationFailure(f"JSON_DUPLICATE_MEMBER: {key}")
        value[key] = item
    return value


def decode_json(data: bytes, location: object) -> dict[str, Any]:
    require(not data.startswith(b"\xef\xbb\xbf"), "JSON_BOM", location)
    value = json.loads(
        data.decode("utf-8", "strict"),
        object_pairs_hook=duplicate_safe_pairs,
        parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)),
    )
    require(isinstance(value, dict), "JSON_ROOT", location)
    return value


def read_json(path: Path) -> tuple[bytes, dict[str, Any]]:
    data = path.read_bytes()
    require(data.endswith(b"\n") and b"\r" not in data, "JSON_TEXT_FORMAT", path)
    return data, decode_json(data, path)


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
    return [
        (line + b"\n", decode_json(line, "jsonl record"))
        for line in data.splitlines()
        if line
    ]


def assigned_records(
    repo: Path, revision: str
) -> tuple[bytes, bytes, bytes, bytes, list[dict[str, Any]], set[str]]:
    case_source = git_blob(repo, revision, CANDIDATE_CASES_PATH)
    oracle_source = git_blob(repo, revision, CANDIDATE_ORACLES_PATH)
    case_records = json_lines(case_source)
    selected_records = [
        record for _, record in case_records
        if record.get("executionClass") == EXECUTION_CLASS
    ]
    selected_cases = [
        raw for raw, record in case_records
        if record.get("executionClass") == EXECUTION_CLASS
    ]
    oracle_ids = {
        edge["oracleId"]
        for record in selected_records
        for coverage in record["coverage"]
        for edge in coverage["evidence"]
    }
    selected_oracles = [
        raw for raw, record in json_lines(oracle_source)
        if record.get("oracleId") in oracle_ids
    ]
    require(
        (len(selected_cases), len(selected_oracles), len(oracle_ids)) == (195, 195, 195),
        "ASSIGNED_COUNTS",
        (len(selected_cases), len(selected_oracles), len(oracle_ids)),
    )
    case_ids = {record["caseId"] for record in selected_records}
    require(len(case_ids) == 195, "ASSIGNED_CASE_IDS", len(case_ids))
    return (
        case_source,
        oracle_source,
        b"".join(selected_cases),
        b"".join(selected_oracles),
        selected_records,
        oracle_ids,
    )


def validate_target(repo: Path, path: Path) -> tuple[bytes, dict[str, Any], dict[str, Any]]:
    target_bytes, target = read_json(path)
    require(set(target) == {
        "artifactVersion", "targetId", "status", "implementationRevision",
        "executionClass", "requiredExecutorRoles", "componentTargets", "artifacts",
        "assignedCorpus", "runtimeTargets", "formalRegistryBoundary", "boundary",
    }, "TARGET_MEMBERS", sorted(target))
    require(target["artifactVersion"] == TARGET_VERSION, "TARGET_VERSION", target["artifactVersion"])
    require(target["targetId"] == TARGET_ID, "TARGET_ID", target["targetId"])
    require(target["status"] == "FROZEN_PREISSUANCE_EXACT_TARGET", "TARGET_STATUS", target["status"])
    revision = target["implementationRevision"]
    require(isinstance(revision, str) and REVISION.fullmatch(revision) is not None,
            "TARGET_REVISION", revision)
    require(target["executionClass"] == EXECUTION_CLASS, "TARGET_CLASS", target["executionClass"])
    roles = ["java-semantic-authority", "typescript-independent-authoring-replayer"]
    require(target["requiredExecutorRoles"] == roles, "TARGET_ROLES", target["requiredExecutorRoles"])

    artifacts = target["artifacts"]
    require(len(artifacts) == len(ARTIFACT_PATHS), "TARGET_ARTIFACT_COUNT", len(artifacts))
    observed_roles: set[str] = set()
    for artifact in artifacts:
        require(set(artifact) == {"role", "path", "sha256", "byteLength"},
                "TARGET_ARTIFACT_MEMBERS", artifact)
        role = artifact["role"]
        require(role not in observed_roles and role in ARTIFACT_PATHS,
                "TARGET_ARTIFACT_ROLE", role)
        observed_roles.add(role)
        require(artifact["path"] == ARTIFACT_PATHS[role], "TARGET_ARTIFACT_PATH", artifact)
        revision_bytes = git_blob(repo, revision, artifact["path"])
        require(artifact == {"role": role, **binding(artifact["path"], revision_bytes)},
                "TARGET_ARTIFACT_BINDING", role)
        require(git_blob(repo, "HEAD", artifact["path"]) == revision_bytes,
                "TARGET_ARTIFACT_HEAD_DRIFT", artifact["path"])
    require(observed_roles == set(ARTIFACT_PATHS), "TARGET_ARTIFACT_ROLES", sorted(observed_roles))

    component_bytes = git_blob(repo, revision, COMPONENT_PATH)
    component = decode_json(component_bytes, COMPONENT_PATH)
    require(target["componentTargets"] == [binding(COMPONENT_PATH, component_bytes)],
            "TARGET_COMPONENT_BINDING", target["componentTargets"])
    require(component.get("targetId") == COMPONENT_TARGET_ID, "TARGET_COMPONENT_ID", component.get("targetId"))
    require(component.get("implementationRevision") == COMPONENT_IMPLEMENTATION_REVISION,
            "TARGET_COMPONENT_REVISION", component.get("implementationRevision"))
    product = component.get("productWiring", {})
    require((product.get("wiredAxisCount"), product.get("remainingAxisCount"),
             product.get("productReservationProofComplete")) == (65, 0, True),
            "TARGET_COMPONENT_PRODUCT_PROOF", product)
    require(component.get("boundary") == {
        "formalRecordsIssued": 0,
        "preissuanceReady": False,
        "recordIssuanceAllowed": False,
        "executionClassExecutable": False,
    }, "TARGET_COMPONENT_BOUNDARY", component.get("boundary"))

    case_source, oracle_source, case_bytes, oracle_bytes, _, oracle_ids = assigned_records(repo, revision)
    expected_assigned = {
        "sourceCases": binding(CANDIDATE_CASES_PATH, case_source),
        "sourceOracles": binding(CANDIDATE_ORACLES_PATH, oracle_source),
        "assignedCaseCount": 195,
        "assignedOracleCount": 195,
        "assignedCasesSha256": sha256(case_bytes),
        "assignedOraclesSha256": sha256(oracle_bytes),
        "assignedCorpusDigest": sha256(
            b"renderweave-design-input-expression-assigned-corpus/1\0"
            + case_bytes + b"\0" + oracle_bytes
        ),
    }
    require(target["assignedCorpus"] == expected_assigned,
            "TARGET_ASSIGNED_CORPUS", target["assignedCorpus"])
    require(target["runtimeTargets"] == {
        "java": "21", "node": "24.19.0", "independentClosureVerifier": "Python 3.13"
    }, "TARGET_RUNTIMES", target["runtimeTargets"])

    formal = target["formalRegistryBoundary"]
    formal_cases = git_blob(repo, revision, FORMAL_CASES_PATH)
    formal_oracles = git_blob(repo, revision, FORMAL_ORACLES_PATH)
    formal_case_records = json_lines(formal_cases)
    formal_oracle_records = json_lines(formal_oracles)
    require(len(formal_case_records) == 58 and len(formal_oracle_records) == 58,
            "TARGET_FORMAL_COUNTS", (len(formal_case_records), len(formal_oracle_records)))
    require(not any(record.get("executionClass") == EXECUTION_CLASS
                    for _, record in formal_case_records), "TARGET_FORMAL_CASE_APPEND", None)
    require(not any(record.get("oracleId") in oracle_ids for _, record in formal_oracle_records),
            "TARGET_FORMAL_ORACLE_APPEND", None)
    require(sum(record.get("executionClass") == "EXEC::DOMAIN_SERVICES::1.0"
                for _, record in formal_case_records) == 12,
            "TARGET_FORMAL_DOMAIN_PREFIX", None)
    require(formal == {
        "cases": {**binding(FORMAL_CASES_PATH, formal_cases), "recordCount": 58},
        "oracles": {**binding(FORMAL_ORACLES_PATH, formal_oracles), "recordCount": 58},
        "issuedDesignInputExpressionCaseCount": 0,
        "issuedDesignInputExpressionOracleCount": 0,
        "appendPerformed": False,
    }, "TARGET_FORMAL_BOUNDARY", formal)
    require(target["boundary"] == {
        "productApiSurfaceCreated": False,
        "nativeRendererInvoked": False,
        "externalProviderAttemptsAllowed": False,
        "formalRecordsIssued": False,
        "preissuanceReplayRequired": True,
        "recordIssuanceAllowed": False,
        "executionClassExecutable": False,
    }, "TARGET_BOUNDARY", target["boundary"])
    return target_bytes, target, component


def validate_executor(
    path: Path, role: str, target_bytes: bytes, target: dict[str, Any]
) -> bytes:
    data, manifest = read_json(path)
    require(set(manifest) == {
        "artifactVersion", "executorId", "role", "executionClass", "targetId",
        "targetManifest", "implementationRevision", "runtime", "entrypoint", "command",
        "componentTarget", "observationAdapter", "sharedSemanticLibrary",
        "networkReadsAllowed", "productMutationAllowed",
    }, "EXECUTOR_MEMBERS", (role, sorted(manifest)))
    require(manifest["artifactVersion"] == EXECUTOR_VERSION, "EXECUTOR_VERSION", role)
    require(manifest["role"] == role and manifest["executionClass"] == EXECUTION_CLASS,
            "EXECUTOR_ROLE", manifest)
    require(manifest["targetId"] == TARGET_ID, "EXECUTOR_TARGET_ID", role)
    require(manifest["targetManifest"] == binding(TARGET_PATH, target_bytes),
            "EXECUTOR_TARGET_BINDING", role)
    require(manifest["implementationRevision"] == target["implementationRevision"],
            "EXECUTOR_REVISION", role)
    require(manifest["componentTarget"] == target["componentTargets"][0],
            "EXECUTOR_COMPONENT", role)
    adapter = next(item for item in target["artifacts"] if item["role"] == "observationAdapter")
    require(manifest["observationAdapter"] == {
        "path": adapter["path"], "sha256": adapter["sha256"], "byteLength": adapter["byteLength"]
    }, "EXECUTOR_ADAPTER", role)
    require(manifest["sharedSemanticLibrary"] is None
            and manifest["networkReadsAllowed"] is False
            and manifest["productMutationAllowed"] is False,
            "EXECUTOR_BOUNDARY", role)
    if role == "java-semantic-authority":
        require(manifest["executorId"] ==
                "DESIGN_INPUT_EXPRESSION_EXECUTOR::JAVA_SEMANTIC_AUTHORITY::1.0",
                "EXECUTOR_ID", role)
        require(manifest["runtime"] == "Java 21", "EXECUTOR_RUNTIME", role)
        entrypoint = JAVA_ENTRYPOINT
    else:
        require(manifest["executorId"] ==
                "DESIGN_INPUT_EXPRESSION_EXECUTOR::TYPESCRIPT_AUTHORING_REPLAYER::1.0",
                "EXECUTOR_ID", role)
        require(manifest["runtime"] == "Node.js 24.19.0", "EXECUTOR_RUNTIME", role)
        entrypoint = TYPESCRIPT_ENTRYPOINT
    expected_entrypoint = binding(
        entrypoint, git_blob(Path.cwd(), target["implementationRevision"], entrypoint)
    )
    require(manifest["entrypoint"] == expected_entrypoint, "EXECUTOR_ENTRYPOINT", role)
    require(isinstance(manifest["command"], str) and len(manifest["command"]) > 80,
            "EXECUTOR_COMMAND", role)
    return data


def validate_reports(
    primary_path: Path,
    independent_path: Path,
    target: dict[str, Any],
    component: dict[str, Any],
) -> tuple[bytes, bytes]:
    primary_bytes, primary = read_json(primary_path)
    independent_bytes, independent = read_json(independent_path)
    component_binding = target["componentTargets"][0]
    require(primary.get("reportVersion") ==
            "renderweave-design-input-expression-capacity-primary/1",
            "PRIMARY_VERSION", primary.get("reportVersion"))
    require(primary.get("engine") == "java-semantic-authority"
            and primary.get("role") == "primary-product-capacity-interface-executor"
            and primary.get("assurance") == "A1_PRODUCT_COMPONENT_EXECUTION",
            "PRIMARY_ROLE", primary)
    require(primary.get("executionClass") == EXECUTION_CLASS, "PRIMARY_CLASS", primary)
    require(primary.get("targetManifest") == component_binding, "PRIMARY_TARGET", primary.get("targetManifest"))
    require(primary.get("implementationRevision") == component.get("implementationRevision"),
            "PRIMARY_REVISION", primary.get("implementationRevision"))
    require((primary.get("axisCount"), primary.get("caseCount"), primary.get("acceptedCount"),
             primary.get("rejectedCount"), primary.get("passed"), primary.get("failed")) ==
            (65, 195, 125, 70, 195, 0), "PRIMARY_COUNTS", primary)
    observations = primary.get("observations")
    require(isinstance(observations, list) and len(observations) == 195,
            "PRIMARY_OBSERVATIONS", type(observations))
    report_case_ids = {item.get("caseId") for item in observations}
    _, _, _, _, candidate_records, _ = assigned_records(Path.cwd(), target["implementationRevision"])
    require(report_case_ids == {record["caseId"] for record in candidate_records},
            "PRIMARY_ASSIGNED_CASE_SET", len(report_case_ids))
    require(primary.get("boundary") == {
        "scalarGuardOnly": True,
        "wiredProductAxisCount": 65,
        "remainingProductAxisCount": 0,
        "parserOrCanonicalizerExecutedByScalarProbe": False,
        "productReservationProofSeparate": True,
        "productReservationProofComplete": True,
        "formalRecordsIssued": 0,
        "preissuanceReady": False,
        "recordIssuanceAllowed": False,
        "executionClassExecutable": False,
    }, "PRIMARY_BOUNDARY", primary.get("boundary"))

    require(independent.get("reportVersion") ==
            "renderweave-design-input-expression-capacity-independent/1",
            "INDEPENDENT_VERSION", independent.get("reportVersion"))
    require(independent.get("engine") == "typescript-independent-authoring-replayer"
            and independent.get("role") == "independent-capacity-profile-replayer"
            and independent.get("assurance") ==
            "A2_COMPONENT_SCALAR_REPLAY_COMPLETE_PRODUCT_WIRING",
            "INDEPENDENT_ROLE", independent)
    require(independent.get("executionClass") == EXECUTION_CLASS, "INDEPENDENT_CLASS", independent)
    require(independent.get("targetManifest") == component_binding,
            "INDEPENDENT_TARGET", independent.get("targetManifest"))
    require(independent.get("primaryReportSha256") == sha256(primary_bytes),
            "INDEPENDENT_PRIMARY_BINDING", independent.get("primaryReportSha256"))
    require((independent.get("axisCount"), independent.get("caseCount"),
             independent.get("acceptedCount"), independent.get("rejectedCount"),
             independent.get("passed"), independent.get("failed")) ==
            (65, 195, 125, 70, 195, 0), "INDEPENDENT_COUNTS", independent)
    require(independent.get("checkCount") == 2692, "INDEPENDENT_CHECKS", independent.get("checkCount"))
    require(independent.get("observationDigest") == OBSERVATION_DIGEST,
            "INDEPENDENT_OBSERVATION_DIGEST", independent.get("observationDigest"))
    require(independent.get("boundary") == {
        "scalarGuardOnly": True,
        "wiredProductAxisCount": 65,
        "remainingProductAxisCount": 0,
        "productReservationProofComplete": True,
        "preissuanceReady": False,
        "formalRecordsIssued": 0,
        "recordIssuanceAllowed": False,
        "executionClassExecutable": False,
    }, "INDEPENDENT_BOUNDARY", independent.get("boundary"))
    return primary_bytes, independent_bytes


def write_report(
    output: Path,
    target_bytes: bytes,
    target: dict[str, Any],
    component: dict[str, Any],
    inputs: list[tuple[str, bytes]],
) -> None:
    report = {
        "reportVersion": "renderweave-design-input-expression-execution-class-independent/1",
        "engine": "python-independent-closure-verifier",
        "assurance": "A2_PREISSUANCE_PRODUCT_REPLAY",
        "executionClass": EXECUTION_CLASS,
        "targetManifest": binding(TARGET_PATH, target_bytes),
        "implementationRevision": target["implementationRevision"],
        "componentImplementationRevision": component["implementationRevision"],
        "assignedCorpusDigest": target["assignedCorpus"]["assignedCorpusDigest"],
        "executorRoleCount": 2,
        "capacityCaseCount": 195,
        "passedExecutorRoles": [
            "java-semantic-authority", "typescript-independent-authoring-replayer"
        ],
        "inputBindings": [binding(name, data) for name, data in inputs],
        "boundary": {
            "formalCaseCount": 58,
            "formalOracleCount": 58,
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
    parser.add_argument("--typescript-executor", type=Path, required=True)
    parser.add_argument("--capacity-primary", type=Path, required=True)
    parser.add_argument("--capacity-independent", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    # All git lookups in helper validation use the exact caller repository.
    previous = Path.cwd()
    try:
        import os
        os.chdir(repo)
        target_bytes, target, component = validate_target(repo, args.target.resolve())
        java_bytes = validate_executor(
            args.java_executor.resolve(), "java-semantic-authority", target_bytes, target
        )
        typescript_bytes = validate_executor(
            args.typescript_executor.resolve(), "typescript-independent-authoring-replayer",
            target_bytes, target
        )
        primary_bytes, independent_bytes = validate_reports(
            args.capacity_primary.resolve(), args.capacity_independent.resolve(), target, component
        )
        inputs = [
            ("design-input-expression/execution-class-target-v1.json", target_bytes),
            ("design-input-expression/java-semantic-authority-executor-manifest-v1.json", java_bytes),
            ("design-input-expression/typescript-independent-authoring-replayer-manifest-v1.json", typescript_bytes),
            (args.capacity_primary.name, primary_bytes),
            (args.capacity_independent.name, independent_bytes),
        ]
        write_report(args.report.resolve(), target_bytes, target, component, inputs)
    finally:
        import os
        os.chdir(previous)
    print("DESIGN_INPUT_EXPRESSION execution class: 2/2 roles, 195/195 capacity PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, VerificationFailure, ValueError) as failure:
        print(f"DESIGN_INPUT_EXPRESSION execution class failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure
