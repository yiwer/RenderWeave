#!/usr/bin/env python3
"""Freeze the exact Rendering Pipeline target and its two required executor manifests."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any


EXECUTION_CLASS = "EXEC::RENDERING_PIPELINE::1.0"
TARGET_ID = "RENDERING_PIPELINE_TARGET::JAVA_RUST_RESOURCE_FREE_ROOT::1.0"
TARGET_PATH = ".scratch/renderweave-template-v1/rendering-pipeline/execution-class-target-v1.json"
JAVA_MANIFEST_PATH = (
    ".scratch/renderweave-template-v1/rendering-pipeline/"
    "java-evaluator-and-sealer-executor-manifest-v1.json"
)
RUST_MANIFEST_PATH = (
    ".scratch/renderweave-template-v1/rendering-pipeline/"
    "rust-render-document-parser-and-engine-executor-manifest-v1.json"
)
CANDIDATE_CASES = (
    ".scratch/renderweave-template-v1/capacity-boundary/candidate/"
    "conformance-cases-v1.jsonl"
)
CANDIDATE_ORACLES = (
    ".scratch/renderweave-template-v1/capacity-boundary/candidate/"
    "conformance-oracles-v1.jsonl"
)
FORMAL_CASES = ".scratch/renderweave-template-v1/conformance-cases-v1.jsonl"
FORMAL_ORACLES = ".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl"
JAVA_ENTRYPOINTS = (
    "renderweave-template/src/test/java/cn/hbads/renderweave/template/internal/"
    "TemplateClosureCapacityConformanceTest.java",
    "renderweave-rendering/src/test/java/cn/hbads/renderweave/rendering/internal/"
    "RenderingPipelineExecutionClassTest.java",
)
RUST_ENTRYPOINT = (
    "renderer/crates/daemon/tests/rendering_pipeline_execution_class.rs"
)
JAVA_PRODUCT_SEAMS = (
    "renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/"
    "TemplateClosureCapacityGuard.java",
    "renderweave-template/src/main/java/cn/hbads/renderweave/template/internal/"
    "CanonicalTemplateClosureAuthority.java",
    "renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/"
    "RenderingPipelineCapacityGuard.java",
    "renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/"
    "CanonicalEvaluator.java",
    "renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/Sealer.java",
)
RUST_PRODUCT_SEAMS = (
    "renderer/crates/protocol/src/lib.rs",
    "renderer/crates/document/src/lib.rs",
    "renderer/crates/resource/src/pipeline.rs",
    "renderer/crates/engine/src/lib.rs",
    "renderer/crates/daemon/src/lib.rs",
)
ARTIFACTS = (
    ("baseline", ".scratch/renderweave-template-v1/rendering-pipeline/baseline-v1.json"),
    ("fixtureContract", ".scratch/renderweave-template-v1/rendering-pipeline/fixture-contract-v1.json"),
    ("fixtureGeneratorTarget", ".scratch/renderweave-template-v1/rendering-pipeline/generator-target-manifest-v1.json"),
    ("fixtureStaticEvidence", ".scratch/renderweave-template-v1/rendering-pipeline/rendering-pipeline-fixture-static-a2-2026-08-17.json"),
    ("observationAdapter", ".scratch/renderweave-template-v1/rendering-pipeline/observation-adapter-v1.json"),
    ("capacityCoverage", ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json"),
    ("capacityCandidateCases", CANDIDATE_CASES),
    ("capacityCandidateOracles", CANDIDATE_ORACLES),
    ("formalCases", FORMAL_CASES),
    ("formalOracles", FORMAL_ORACLES),
    ("executionClasses", ".scratch/renderweave-template-v1/conformance-execution-classes-v1.json"),
    ("bootstrapOrder", ".scratch/renderweave-template-v1/conformance-bootstrap-order-v1.json"),
    ("acceptanceManifest", ".scratch/renderweave-template-v1/acceptance-manifest-v1.json"),
    ("executionClassVerifier", "tools/verify-rendering-pipeline-execution-class.py"),
    ("executionClassGate", "tools/run-rendering-pipeline-execution-class-gate.ps1"),
    ("targetMaterializer", ".scratch/renderweave-template-v1/rendering-pipeline/materialize-execution-class-target.py"),
)


def git(repo: Path, *args: str, text: bool = False) -> bytes | str:
    return subprocess.run(
        ["git", *args], cwd=repo, check=True, stdout=subprocess.PIPE, text=text
    ).stdout


def blob(repo: Path, revision: str, path: str) -> bytes:
    value = git(repo, "show", f"{revision}:{path}")
    assert isinstance(value, bytes)
    return value


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def binding(path: str, data: bytes) -> dict[str, object]:
    return {"path": path, "sha256": sha256(data), "byteLength": len(data)}


def json_lines(data: bytes) -> list[tuple[bytes, dict[str, Any]]]:
    return [(line + b"\n", json.loads(line)) for line in data.splitlines() if line]


def assigned_corpus(repo: Path, revision: str) -> dict[str, object]:
    case_source = blob(repo, revision, CANDIDATE_CASES)
    oracle_source = blob(repo, revision, CANDIDATE_ORACLES)
    case_records = json_lines(case_source)
    selected_cases = [
        raw for raw, record in case_records
        if record.get("executionClass") == EXECUTION_CLASS
    ]
    oracle_ids = {
        evidence["oracleId"]
        for _, record in case_records
        if record.get("executionClass") == EXECUTION_CLASS
        for coverage in record["coverage"]
        for evidence in coverage["evidence"]
    }
    selected_oracles = [
        raw for raw, record in json_lines(oracle_source)
        if record.get("oracleId") in oracle_ids
    ]
    if (len(selected_cases), len(selected_oracles), len(oracle_ids)) != (156, 156, 156):
        raise SystemExit("Rendering Pipeline assignment must be exactly 156 Case + 156 Oracle")
    case_bytes = b"".join(selected_cases)
    oracle_bytes = b"".join(selected_oracles)
    corpus = (
        b"renderweave-rendering-pipeline-assigned-corpus/1\0"
        + case_bytes + b"\0" + oracle_bytes
    )
    return {
        "sourceCases": binding(CANDIDATE_CASES, case_source),
        "sourceOracles": binding(CANDIDATE_ORACLES, oracle_source),
        "assignedCaseCount": 156,
        "assignedOracleCount": 156,
        "assignedCasesSha256": sha256(case_bytes),
        "assignedOraclesSha256": sha256(oracle_bytes),
        "assignedCorpusDigest": sha256(corpus),
    }


def formal_boundary(repo: Path, revision: str) -> dict[str, object]:
    case_data = blob(repo, revision, FORMAL_CASES)
    oracle_data = blob(repo, revision, FORMAL_ORACLES)
    cases = json_lines(case_data)
    oracles = json_lines(oracle_data)
    if len(cases) != 253 or len(oracles) != 253:
        raise SystemExit("formal registry must remain at 253 Case + 253 Oracle")
    candidate_cases = json_lines(blob(repo, revision, CANDIDATE_CASES))
    assigned_oracles = {
        evidence["oracleId"]
        for _, record in candidate_cases
        if record.get("executionClass") == EXECUTION_CLASS
        for coverage in record["coverage"]
        for evidence in coverage["evidence"]
    }
    if any(record.get("executionClass") == EXECUTION_CLASS for _, record in cases):
        raise SystemExit("formal Case registry already contains Rendering Pipeline records")
    if any(record.get("oracleId") in assigned_oracles for _, record in oracles):
        raise SystemExit("formal Oracle registry already contains Rendering Pipeline records")
    expected_prefix = {
        "EXEC::SPEC_REGISTRY::1.0": 46,
        "EXEC::DOMAIN_SERVICES::1.0": 12,
        "EXEC::DESIGN_INPUT_EXPRESSION::1.0": 195,
    }
    actual = {
        execution_class: sum(
            record.get("executionClass") == execution_class for _, record in cases
        )
        for execution_class in expected_prefix
    }
    if actual != expected_prefix:
        raise SystemExit(f"formal executable prefix drifted: {actual}")
    return {
        "cases": {**binding(FORMAL_CASES, case_data), "recordCount": 253},
        "oracles": {**binding(FORMAL_ORACLES, oracle_data), "recordCount": 253},
        "issuedRenderingPipelineCaseCount": 0,
        "issuedRenderingPipelineOracleCount": 0,
        "appendPerformed": False,
    }


def validate_bootstrap_state(repo: Path, revision: str) -> None:
    catalog = json.loads(blob(
        repo, revision,
        ".scratch/renderweave-template-v1/conformance-execution-classes-v1.json"
    ))
    classes = {item["executionClass"]: item for item in catalog["classes"]}
    for prior in (
        "EXEC::SPEC_REGISTRY::1.0",
        "EXEC::DOMAIN_SERVICES::1.0",
        "EXEC::DESIGN_INPUT_EXPRESSION::1.0",
    ):
        if classes[prior].get("executable") is not True:
            raise SystemExit(f"bootstrap predecessor is not executable: {prior}")
    current = classes[EXECUTION_CLASS]
    if current.get("executable") is not False or current.get("status") != (
        "FIXTURE_GENERATOR_STATIC_A2_PRODUCT_TARGET_PENDING"
    ):
        raise SystemExit("Rendering Pipeline central lifecycle is not preissuance-pending")


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
    parser.add_argument("--rust-executor-output", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    resolved = git(repo, "rev-parse", "--verify", f"{args.revision}^{{commit}}", text=True)
    assert isinstance(resolved, str)
    revision = resolved.strip()
    if revision != args.revision or len(revision) != 40:
        raise SystemExit("revision must be one exact forty-character commit")
    validate_bootstrap_state(repo, revision)

    artifacts = [
        {"role": role, **binding(path, blob(repo, revision, path))}
        for role, path in ARTIFACTS
    ]
    java_entries = [binding(path, blob(repo, revision, path)) for path in JAVA_ENTRYPOINTS]
    rust_entry = binding(RUST_ENTRYPOINT, blob(repo, revision, RUST_ENTRYPOINT))
    java_seams = [binding(path, blob(repo, revision, path)) for path in JAVA_PRODUCT_SEAMS]
    rust_seams = [binding(path, blob(repo, revision, path)) for path in RUST_PRODUCT_SEAMS]
    target = {
        "artifactVersion": "renderweave-rendering-pipeline-execution-class-target/1.0",
        "targetId": TARGET_ID,
        "status": "FROZEN_PREISSUANCE_EXACT_TARGET",
        "implementationRevision": revision,
        "executionClass": EXECUTION_CLASS,
        "targetKind": "one exact resource-free system-empty root render target",
        "requiredExecutorRoles": [
            "java-evaluator-and-sealer",
            "rust-render-document-parser-and-engine",
        ],
        "productWiring": {
            "axisCount": 52,
            "wiredAxisCount": 52,
            "remainingAxisCount": 0,
            "javaTemplateClosureAxisCount": 5,
            "javaRenderingAxisCount": 47,
            "resourceFreeBaselineCount": 1,
        },
        "artifacts": artifacts,
        "javaEntrypoints": java_entries,
        "rustEntrypoint": rust_entry,
        "javaProductSeams": java_seams,
        "rustProductSeams": rust_seams,
        "assignedCorpus": assigned_corpus(repo, revision),
        "runtimeTargets": {
            "java": "21",
            "rust": "1.89",
            "independentClosureVerifier": "Python 3.13",
        },
        "formalRegistryBoundary": formal_boundary(repo, revision),
        "boundary": {
            "productApiSurfaceCreated": False,
            "rendererKernelReplayRequired": True,
            "rendererDaemonOrDeploymentInvoked": False,
            "rendererProfileRegistered": False,
            "networkAndExternalProviderAttemptsAllowed": False,
            "formalRecordsIssued": False,
            "preissuanceReplayRequired": True,
            "recordIssuanceAllowed": False,
            "executionClassExecutable": False,
        },
    }
    target_bytes = write_json(args.target_output, target)
    target_binding = binding(TARGET_PATH, target_bytes)
    manifest_version = "renderweave-rendering-pipeline-executor-manifest/1.0"
    java_manifest = {
        "artifactVersion": manifest_version,
        "executorId": "RENDERING_PIPELINE_EXECUTOR::JAVA_EVALUATOR_AND_SEALER::1.0",
        "role": "java-evaluator-and-sealer",
        "executionClass": EXECUTION_CLASS,
        "targetId": TARGET_ID,
        "targetManifest": target_binding,
        "implementationRevision": revision,
        "runtime": "Java 21",
        "entrypoints": java_entries,
        "productSeams": java_seams,
        "command": (
            "mvn -B -ntp -pl renderweave-rendering -am "
            "-Dtest=TemplateClosureCapacityConformanceTest,RenderingPipelineExecutionClassTest "
            "-D<write-once-evidence-properties> test"
        ),
        "expectedValuesVisibleToExecutor": False,
        "networkReadsAllowed": False,
        "productMutationAllowed": False,
    }
    rust_manifest = {
        "artifactVersion": manifest_version,
        "executorId": "RENDERING_PIPELINE_EXECUTOR::RUST_DOCUMENT_ENGINE::1.0",
        "role": "rust-render-document-parser-and-engine",
        "executionClass": EXECUTION_CLASS,
        "targetId": TARGET_ID,
        "targetManifest": target_binding,
        "implementationRevision": revision,
        "runtime": "Rust 1.89",
        "entrypoint": rust_entry,
        "productSeams": rust_seams,
        "command": (
            "cargo test --manifest-path renderer/Cargo.toml "
            "-p renderweave-renderer-daemon --test rendering_pipeline_execution_class"
        ),
        "expectedValuesVisibleToExecutor": False,
        "networkReadsAllowed": False,
        "productMutationAllowed": False,
    }
    write_json(args.java_executor_output, java_manifest)
    write_json(args.rust_executor_output, rust_manifest)


if __name__ == "__main__":
    main()
