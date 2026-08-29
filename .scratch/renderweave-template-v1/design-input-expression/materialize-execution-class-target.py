#!/usr/bin/env python3
"""Freeze the exact DESIGN_INPUT_EXPRESSION target and required executor manifests."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
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
COMPONENT_TARGET_ID = "DESIGN_INPUT_EXPRESSION_TARGET::CAPACITY_AUTHORITY_PRODUCT_WIRING_COMPLETE::17.0"
COMPONENT_IMPLEMENTATION_REVISION = "12a3f7e69b9a814358133c8d84ddc2b53da84789"
JAVA_ENTRYPOINT = (
    "renderweave-template/src/test/java/cn/hbads/renderweave/template/internal/"
    "DesignInputExpressionCapacityConformanceTest.java"
)
TYPESCRIPT_ENTRYPOINT = "tools/verify-design-input-expression-capacity.ts"
ARTIFACTS = (
    ("capacityComponentTarget", COMPONENT_PATH),
    ("observationAdapter", OBSERVATION_ADAPTER_PATH),
    ("capacityCoverage", ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json"),
    ("capacityCandidateCases", CANDIDATE_CASES_PATH),
    ("capacityCandidateOracles", CANDIDATE_ORACLES_PATH),
    ("javaSemanticExecutor", JAVA_ENTRYPOINT),
    ("typescriptIndependentExecutor", TYPESCRIPT_ENTRYPOINT),
    ("componentGate", "tools/run-design-input-expression-capacity-gate.ps1"),
    ("nodeToolchainProvisioner", "tools/ensure-node24.ps1"),
    ("executionClassIndependentReplay", "tools/verify-design-input-expression-execution-class.py"),
    ("executionClassGate", "tools/run-design-input-expression-execution-class-gate.ps1"),
    ("targetMaterializer", ".scratch/renderweave-template-v1/design-input-expression/materialize-execution-class-target.py"),
)


def git(repo: Path, *arguments: str, text: bool = False) -> bytes | str:
    return subprocess.run(
        ["git", *arguments], cwd=repo, check=True, stdout=subprocess.PIPE, text=text
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
    return [
        (line + b"\n", json.loads(line))
        for line in data.splitlines()
        if line
    ]


def assigned_corpus(repo: Path, revision: str) -> dict[str, object]:
    case_source = blob(repo, revision, CANDIDATE_CASES_PATH)
    oracle_source = blob(repo, revision, CANDIDATE_ORACLES_PATH)
    case_records = json_lines(case_source)
    selected_cases = [
        raw for raw, record in case_records
        if record.get("executionClass") == EXECUTION_CLASS
    ]
    oracle_ids = {
        edge["oracleId"]
        for _, record in case_records
        if record.get("executionClass") == EXECUTION_CLASS
        for coverage in record["coverage"]
        for edge in coverage["evidence"]
    }
    selected_oracles = [
        raw for raw, record in json_lines(oracle_source)
        if record.get("oracleId") in oracle_ids
    ]
    if (len(selected_cases), len(selected_oracles), len(oracle_ids)) != (195, 195, 195):
        raise SystemExit(
            "DESIGN_INPUT_EXPRESSION assigned corpus must be exactly 195 Case + 195 Oracle"
        )
    case_bytes = b"".join(selected_cases)
    oracle_bytes = b"".join(selected_oracles)
    digest_input = (
        b"renderweave-design-input-expression-assigned-corpus/1\0"
        + case_bytes
        + b"\0"
        + oracle_bytes
    )
    return {
        "sourceCases": binding(CANDIDATE_CASES_PATH, case_source),
        "sourceOracles": binding(CANDIDATE_ORACLES_PATH, oracle_source),
        "assignedCaseCount": 195,
        "assignedOracleCount": 195,
        "assignedCasesSha256": sha256(case_bytes),
        "assignedOraclesSha256": sha256(oracle_bytes),
        "assignedCorpusDigest": sha256(digest_input),
    }


def formal_boundary(repo: Path, revision: str) -> dict[str, object]:
    candidate_cases = json_lines(blob(repo, revision, CANDIDATE_CASES_PATH))
    design_oracle_ids = {
        edge["oracleId"]
        for _, record in candidate_cases
        if record.get("executionClass") == EXECUTION_CLASS
        for coverage in record["coverage"]
        for edge in coverage["evidence"]
    }
    case_data = blob(repo, revision, FORMAL_CASES_PATH)
    oracle_data = blob(repo, revision, FORMAL_ORACLES_PATH)
    case_records = json_lines(case_data)
    oracle_records = json_lines(oracle_data)
    if len(case_records) != 58 or len(oracle_records) != 58:
        raise SystemExit("formal registry must remain at 58 Case + 58 Oracle records")
    if any(record.get("executionClass") == EXECUTION_CLASS for _, record in case_records):
        raise SystemExit("formal Case registry already contains DESIGN_INPUT_EXPRESSION records")
    if any(record.get("oracleId") in design_oracle_ids for _, record in oracle_records):
        raise SystemExit("formal Oracle registry already contains DESIGN_INPUT_EXPRESSION records")
    if sum(
        record.get("executionClass") == "EXEC::DOMAIN_SERVICES::1.0"
        for _, record in case_records
    ) != 12:
        raise SystemExit("formal registry no longer contains the exact 12 Domain Services prefix")
    return {
        "cases": {**binding(FORMAL_CASES_PATH, case_data), "recordCount": 58},
        "oracles": {**binding(FORMAL_ORACLES_PATH, oracle_data), "recordCount": 58},
        "issuedDesignInputExpressionCaseCount": 0,
        "issuedDesignInputExpressionOracleCount": 0,
        "appendPerformed": False,
    }


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
    parser.add_argument("--typescript-executor-output", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    resolved = git(repo, "rev-parse", "--verify", f"{args.revision}^{{commit}}", text=True)
    assert isinstance(resolved, str)
    revision = resolved.strip()
    if revision != args.revision:
        raise SystemExit("revision must be the exact forty-character commit identity")

    artifacts = [
        {"role": role, **binding(path, blob(repo, revision, path))}
        for role, path in ARTIFACTS
    ]
    component_bytes = blob(repo, revision, COMPONENT_PATH)
    component = json.loads(component_bytes)
    if component.get("targetId") != COMPONENT_TARGET_ID:
        raise SystemExit("capacity component target identity drifted")
    if component.get("implementationRevision") != COMPONENT_IMPLEMENTATION_REVISION:
        raise SystemExit("capacity component implementation revision drifted")
    if component.get("productWiring", {}).get("wiredAxisCount") != 65:
        raise SystemExit("capacity component target is not wired 65/65")
    if component.get("productWiring", {}).get("remainingAxisCount") != 0:
        raise SystemExit("capacity component target still has remaining axes")
    if component.get("productWiring", {}).get("productReservationProofComplete") is not True:
        raise SystemExit("capacity component product reservation proof is incomplete")

    target = {
        "artifactVersion": "renderweave-design-input-expression-execution-class-target/1.0",
        "targetId": TARGET_ID,
        "status": "FROZEN_PREISSUANCE_EXACT_TARGET",
        "implementationRevision": revision,
        "executionClass": EXECUTION_CLASS,
        "requiredExecutorRoles": [
            "java-semantic-authority",
            "typescript-independent-authoring-replayer",
        ],
        "componentTargets": [binding(COMPONENT_PATH, component_bytes)],
        "artifacts": artifacts,
        "assignedCorpus": assigned_corpus(repo, revision),
        "runtimeTargets": {
            "java": "21",
            "node": "24.19.0",
            "independentClosureVerifier": "Python 3.13",
        },
        "formalRegistryBoundary": formal_boundary(repo, revision),
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
    target_bytes = write_json(args.target_output, target)
    target_binding = binding(TARGET_PATH, target_bytes)
    component_binding = binding(COMPONENT_PATH, component_bytes)
    adapter_bytes = blob(repo, revision, OBSERVATION_ADAPTER_PATH)
    adapter_binding = binding(OBSERVATION_ADAPTER_PATH, adapter_bytes)
    manifest_version = "renderweave-design-input-expression-executor-manifest/1.0"

    java_manifest = {
        "artifactVersion": manifest_version,
        "executorId": "DESIGN_INPUT_EXPRESSION_EXECUTOR::JAVA_SEMANTIC_AUTHORITY::1.0",
        "role": "java-semantic-authority",
        "executionClass": EXECUTION_CLASS,
        "targetId": TARGET_ID,
        "targetManifest": target_binding,
        "implementationRevision": revision,
        "runtime": "Java 21",
        "entrypoint": binding(JAVA_ENTRYPOINT, blob(repo, revision, JAVA_ENTRYPOINT)),
        "command": (
            "mvn -B -ntp -pl renderweave-app -am "
            "-Dtest=<frozen-design-input-expression-product-proof-suite> "
            "-Drenderweave.designInputExpression.target=<component-target> "
            "-Drenderweave.designInputExpression.primaryReport=<evidence> test"
        ),
        "componentTarget": component_binding,
        "observationAdapter": adapter_binding,
        "sharedSemanticLibrary": None,
        "networkReadsAllowed": False,
        "productMutationAllowed": False,
    }
    typescript_manifest = {
        "artifactVersion": manifest_version,
        "executorId": "DESIGN_INPUT_EXPRESSION_EXECUTOR::TYPESCRIPT_AUTHORING_REPLAYER::1.0",
        "role": "typescript-independent-authoring-replayer",
        "executionClass": EXECUTION_CLASS,
        "targetId": TARGET_ID,
        "targetManifest": target_binding,
        "implementationRevision": revision,
        "runtime": "Node.js 24.19.0",
        "entrypoint": binding(
            TYPESCRIPT_ENTRYPOINT, blob(repo, revision, TYPESCRIPT_ENTRYPOINT)
        ),
        "command": (
            "node tools/verify-design-input-expression-capacity.ts --repo <repo> "
            "--target <component-target> --primary-report <java-report> "
            "--report <typescript-report>"
        ),
        "componentTarget": component_binding,
        "observationAdapter": adapter_binding,
        "sharedSemanticLibrary": None,
        "networkReadsAllowed": False,
        "productMutationAllowed": False,
    }
    write_json(args.java_executor_output, java_manifest)
    write_json(args.typescript_executor_output, typescript_manifest)


if __name__ == "__main__":
    main()
