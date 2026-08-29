#!/usr/bin/env python3
"""Materialize and explicitly apply the exact Rendering Pipeline capacity issuance."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any


EXECUTION_CLASS = "EXEC::RENDERING_PIPELINE::1.0"
PREDECESSOR_CLASS = "EXEC::DESIGN_INPUT_EXPRESSION::1.0"
TARGET_ID = "RENDERING_PIPELINE_ISSUANCE::CAPACITY::1.0"
TARGET_VERSION = "renderweave-rendering-pipeline-capacity-issuance-target/1.0"
FORMAL_PREFIX_CASE_COUNT = 253
FORMAL_PREFIX_ORACLE_COUNT = 253
ASSIGNED_CASE_COUNT = 156
ASSIGNED_ORACLE_COUNT = 156
FORMAL_POST_CASE_COUNT = FORMAL_PREFIX_CASE_COUNT + ASSIGNED_CASE_COUNT
FORMAL_POST_ORACLE_COUNT = FORMAL_PREFIX_ORACLE_COUNT + ASSIGNED_ORACLE_COUNT
ISSUED_CAPACITY_CASE_COUNT = 12 + 195 + ASSIGNED_CASE_COUNT
ISSUED_CAPACITY_ORACLE_COUNT = 12 + 195 + ASSIGNED_ORACLE_COUNT
SPEC_IMPLEMENTATION_REVISION = "spec-registry-bootstrap/1.15"
SPEC_ROOT = ".scratch/renderweave-template-v1"
TARGET_PATH = f"{SPEC_ROOT}/rendering-pipeline/capacity-record-issuance-target-v1.json"
CASE_PATH = f"{SPEC_ROOT}/conformance-cases-v1.jsonl"
ORACLE_PATH = f"{SPEC_ROOT}/conformance-oracles-v1.jsonl"
CANDIDATE_CASE_PATH = f"{SPEC_ROOT}/capacity-boundary/candidate/conformance-cases-v1.jsonl"
CANDIDATE_ORACLE_PATH = f"{SPEC_ROOT}/capacity-boundary/candidate/conformance-oracles-v1.jsonl"
EXECUTION_CATALOG_PATH = f"{SPEC_ROOT}/conformance-execution-classes-v1.json"
BOOTSTRAP_PATH = f"{SPEC_ROOT}/conformance-bootstrap-order-v1.json"
ACCEPTANCE_PATH = f"{SPEC_ROOT}/acceptance-manifest-v1.json"
SPEC_TARGET_PATH = f"{SPEC_ROOT}/spec-registry/target-manifest-v1.json"
PREVIOUS_ISSUANCE_PATH = (
    f"{SPEC_ROOT}/design-input-expression/capacity-record-issuance-target-v1.json"
)
PRODUCT_TARGET_PATH = f"{SPEC_ROOT}/rendering-pipeline/execution-class-target-v1.json"
JAVA_EXECUTOR_PATH = (
    f"{SPEC_ROOT}/rendering-pipeline/java-evaluator-and-sealer-executor-manifest-v1.json"
)
RUST_EXECUTOR_PATH = (
    f"{SPEC_ROOT}/rendering-pipeline/"
    "rust-render-document-parser-and-engine-executor-manifest-v1.json"
)
POST_EVIDENCE_RELATIVE = (
    "rendering-pipeline/rendering-pipeline-capacity-postissuance-a2-2026-08-29.json"
)

ARTIFACT_PATHS = (
    PRODUCT_TARGET_PATH,
    JAVA_EXECUTOR_PATH,
    RUST_EXECUTOR_PATH,
    f"{SPEC_ROOT}/rendering-pipeline/observation-adapter-v1.json",
    PREVIOUS_ISSUANCE_PATH,
    f"{SPEC_ROOT}/capacity-boundary/materialization-manifest-v1.json",
    f"{SPEC_ROOT}/capacity-boundary/primary-result-v1.json",
    f"{SPEC_ROOT}/capacity-boundary/independent-result-v1.json",
    f"{SPEC_ROOT}/capacity-boundary/capacity-boundary-static-a2-2026-08-17.json",
    f"{SPEC_ROOT}/conformance-case-record-schema-v1.json",
    f"{SPEC_ROOT}/conformance-oracle-record-schema-v1.json",
    f"{SPEC_ROOT}/conformance-canonical-profile-v1.json",
    f"{SPEC_ROOT}/conformance-probe-profile-v1.json",
    f"{SPEC_ROOT}/conformance-manifest-snapshot-policy-v1.json",
    f"{SPEC_ROOT}/rendering-pipeline/materialize-rendering-pipeline-capacity-issuance.py",
    f"{SPEC_ROOT}/rendering-pipeline/validate-rendering-pipeline-postissuance-primary.mjs",
    f"{SPEC_ROOT}/rendering-pipeline/validate_rendering_pipeline_postissuance_independent.py",
    f"{SPEC_ROOT}/rendering-pipeline/write-rendering-pipeline-postissuance-a2-evidence.mjs",
    f"{SPEC_ROOT}/spec-registry/refresh-spec-registry-postissuance-target.mjs",
    f"{SPEC_ROOT}/spec-registry/validate-spec-registry-primary.mjs",
    f"{SPEC_ROOT}/spec-registry/validate-spec-registry-independent.py",
    f"{SPEC_ROOT}/spec-registry/write-spec-registry-a2-evidence.mjs",
    "tools/run-rendering-pipeline-record-issuance-gate.ps1",
    "tools/run-template-static-gate.ps1",
)


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def binding(path: str, data: bytes) -> dict[str, object]:
    return {"path": path, "sha256": sha256(data), "byteLength": len(data)}


def git_blob(repo: Path, revision: str, path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def resolve_revision(repo: Path, revision: str) -> str:
    return subprocess.run(
        ["git", "rev-parse", f"{revision}^{{commit}}"],
        cwd=repo,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()


def json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def parse_json(data: bytes, label: str) -> dict[str, Any]:
    value = json.loads(data)
    if not isinstance(value, dict):
        raise SystemExit(f"{label} must be a JSON object")
    return value


def json_lines(data: bytes, label: str) -> list[tuple[bytes, dict[str, Any]]]:
    if data.startswith(b"\xef\xbb\xbf") or b"\r" in data or not data.endswith(b"\n"):
        raise SystemExit(f"{label} must be BOM-free LF-terminated UTF-8 JSONL")
    rows: list[tuple[bytes, dict[str, Any]]] = []
    for index, raw in enumerate(data.splitlines(), 1):
        if not raw:
            raise SystemExit(f"{label} contains a blank line at {index}")
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise SystemExit(f"{label} line {index} must be an object")
        rows.append((raw + b"\n", value))
    return rows


def assigned_corpus(case_source: bytes, oracle_source: bytes) -> dict[str, Any]:
    case_rows = json_lines(case_source, "capacity candidate Case registry")
    oracle_rows = json_lines(oracle_source, "capacity candidate Oracle registry")
    assigned_case_rows = [
        (raw, record)
        for raw, record in case_rows
        if record.get("executionClass") == EXECUTION_CLASS
    ]
    oracle_ids = {
        evidence["oracleId"]
        for _, record in assigned_case_rows
        for coverage in record["coverage"]
        for evidence in coverage["evidence"]
    }
    assigned_oracle_rows = [
        (raw, record)
        for raw, record in oracle_rows
        if record.get("oracleId") in oracle_ids
    ]
    if (
        len(assigned_case_rows),
        len(assigned_oracle_rows),
        len(oracle_ids),
    ) != (ASSIGNED_CASE_COUNT, ASSIGNED_ORACLE_COUNT, ASSIGNED_ORACLE_COUNT):
        raise SystemExit("assigned Rendering Pipeline corpus must be exactly 156 Case + 156 Oracle")
    case_bytes = b"".join(raw for raw, _ in assigned_case_rows)
    oracle_bytes = b"".join(raw for raw, _ in assigned_oracle_rows)
    return {
        "caseBytes": case_bytes,
        "oracleBytes": oracle_bytes,
        "caseIds": [record["caseId"] for _, record in assigned_case_rows],
        "oracleIds": [record["oracleId"] for _, record in assigned_oracle_rows],
        "digest": sha256(
            b"renderweave-rendering-pipeline-assigned-corpus/1\0"
            + case_bytes
            + b"\0"
            + oracle_bytes
        ),
    }


def require_predecessor_poststate(
    base_cases: bytes,
    base_oracles: bytes,
    previous: dict[str, Any],
) -> None:
    previous_case_prestate = previous["prestate"]["formalCases"]
    previous_oracle_prestate = previous["prestate"]["formalOracles"]
    case_prefix_length = int(previous_case_prestate["byteLength"])
    oracle_prefix_length = int(previous_oracle_prestate["byteLength"])
    case_prefix = base_cases[:case_prefix_length]
    oracle_prefix = base_oracles[:oracle_prefix_length]
    if previous.get("executionClass") != PREDECESSOR_CLASS:
        raise SystemExit("formal prestate predecessor must be Design/Input/Expression issuance")
    if previous["poststate"]["formalCases"] != {
        **binding(CASE_PATH, base_cases),
        "recordCount": FORMAL_PREFIX_CASE_COUNT,
        "preservedPrefixSha256": sha256(case_prefix),
    }:
        raise SystemExit("formal Case prestate must equal exact predecessor poststate")
    if previous["poststate"]["formalOracles"] != {
        **binding(ORACLE_PATH, base_oracles),
        "recordCount": FORMAL_PREFIX_ORACLE_COUNT,
        "preservedPrefixSha256": sha256(oracle_prefix),
    }:
        raise SystemExit("formal Oracle prestate must equal exact predecessor poststate")
    if previous_case_prestate != {
        **binding(CASE_PATH, case_prefix),
        "recordCount": int(previous_case_prestate["recordCount"]),
    }:
        raise SystemExit("predecessor Case prefix drifted")
    if previous_oracle_prestate != {
        **binding(ORACLE_PATH, oracle_prefix),
        "recordCount": int(previous_oracle_prestate["recordCount"]),
    }:
        raise SystemExit("predecessor Oracle prefix drifted")
    if sha256(base_cases[case_prefix_length:]) != previous["assignedCorpus"]["caseBytesSha256"]:
        raise SystemExit("predecessor Case suffix drifted")
    if sha256(base_oracles[oracle_prefix_length:]) != previous["assignedCorpus"]["oracleBytesSha256"]:
        raise SystemExit("predecessor Oracle suffix drifted")


def build_target(repo: Path, revision: str) -> tuple[dict[str, Any], bytes, bytes]:
    resolved_revision = resolve_revision(repo, revision)
    base_cases = git_blob(repo, resolved_revision, CASE_PATH)
    base_oracles = git_blob(repo, resolved_revision, ORACLE_PATH)
    if len(json_lines(base_cases, "formal Case prestate")) != FORMAL_PREFIX_CASE_COUNT:
        raise SystemExit("formal Case prestate must contain exactly 253 records")
    if len(json_lines(base_oracles, "formal Oracle prestate")) != FORMAL_PREFIX_ORACLE_COUNT:
        raise SystemExit("formal Oracle prestate must contain exactly 253 records")
    previous_bytes = git_blob(repo, resolved_revision, PREVIOUS_ISSUANCE_PATH)
    previous = parse_json(previous_bytes, PREVIOUS_ISSUANCE_PATH)
    require_predecessor_poststate(base_cases, base_oracles, previous)

    product_target_bytes = git_blob(repo, resolved_revision, PRODUCT_TARGET_PATH)
    product_target = parse_json(product_target_bytes, PRODUCT_TARGET_PATH)
    candidate_cases = git_blob(repo, resolved_revision, CANDIDATE_CASE_PATH)
    candidate_oracles = git_blob(repo, resolved_revision, CANDIDATE_ORACLE_PATH)
    assigned = assigned_corpus(candidate_cases, candidate_oracles)
    predecessor_assigned = product_target.get("assignedCorpus", {})
    if (
        product_target.get("executionClass") != EXECUTION_CLASS
        or predecessor_assigned.get("assignedCaseCount") != ASSIGNED_CASE_COUNT
        or predecessor_assigned.get("assignedOracleCount") != ASSIGNED_ORACLE_COUNT
        or predecessor_assigned.get("assignedCasesSha256") != sha256(assigned["caseBytes"])
        or predecessor_assigned.get("assignedOraclesSha256") != sha256(assigned["oracleBytes"])
        or predecessor_assigned.get("assignedCorpusDigest") != assigned["digest"]
    ):
        raise SystemExit("T207 predecessor target does not bind the exact assigned corpus")
    predecessor_boundary = product_target.get("formalRegistryBoundary", {})
    if (
        predecessor_boundary.get("cases")
        != {**binding(CASE_PATH, base_cases), "recordCount": FORMAL_PREFIX_CASE_COUNT}
        or predecessor_boundary.get("oracles")
        != {**binding(ORACLE_PATH, base_oracles), "recordCount": FORMAL_PREFIX_ORACLE_COUNT}
        or predecessor_boundary.get("issuedRenderingPipelineCaseCount") != 0
        or predecessor_boundary.get("issuedRenderingPipelineOracleCount") != 0
        or predecessor_boundary.get("appendPerformed") is not False
    ):
        raise SystemExit("T207 predecessor target does not bind the exact zero-issued formal prestate")

    post_cases = base_cases + assigned["caseBytes"]
    post_oracles = base_oracles + assigned["oracleBytes"]
    artifacts = [binding(path, git_blob(repo, resolved_revision, path)) for path in ARTIFACT_PATHS]
    target = {
        "artifactVersion": TARGET_VERSION,
        "targetId": TARGET_ID,
        "status": "FROZEN_APPEND_ONLY_ISSUANCE_TARGET",
        "implementationRevision": resolved_revision,
        "executionClass": EXECUTION_CLASS,
        "predecessorProductTarget": binding(PRODUCT_TARGET_PATH, product_target_bytes),
        "requiredExecutorManifests": [
            binding(JAVA_EXECUTOR_PATH, git_blob(repo, resolved_revision, JAVA_EXECUTOR_PATH)),
            binding(RUST_EXECUTOR_PATH, git_blob(repo, resolved_revision, RUST_EXECUTOR_PATH)),
        ],
        "artifacts": artifacts,
        "prestate": {
            "formalCases": {**binding(CASE_PATH, base_cases), "recordCount": FORMAL_PREFIX_CASE_COUNT},
            "formalOracles": {**binding(ORACLE_PATH, base_oracles), "recordCount": FORMAL_PREFIX_ORACLE_COUNT},
            "previousCapacityIssuance": binding(PREVIOUS_ISSUANCE_PATH, previous_bytes),
            "executionClassCatalog": binding(
                EXECUTION_CATALOG_PATH,
                git_blob(repo, resolved_revision, EXECUTION_CATALOG_PATH),
            ),
            "bootstrapOrder": binding(
                BOOTSTRAP_PATH,
                git_blob(repo, resolved_revision, BOOTSTRAP_PATH),
            ),
            "acceptanceManifest": binding(
                ACCEPTANCE_PATH,
                git_blob(repo, resolved_revision, ACCEPTANCE_PATH),
            ),
            "specRegistryTarget": binding(
                SPEC_TARGET_PATH,
                git_blob(repo, resolved_revision, SPEC_TARGET_PATH),
            ),
        },
        "assignedCorpus": {
            "sourceCases": binding(CANDIDATE_CASE_PATH, candidate_cases),
            "sourceOracles": binding(CANDIDATE_ORACLE_PATH, candidate_oracles),
            "caseCount": ASSIGNED_CASE_COUNT,
            "oracleCount": ASSIGNED_ORACLE_COUNT,
            "caseIds": assigned["caseIds"],
            "oracleIds": assigned["oracleIds"],
            "caseBytesSha256": sha256(assigned["caseBytes"]),
            "oracleBytesSha256": sha256(assigned["oracleBytes"]),
            "assignedCorpusDigest": assigned["digest"],
        },
        "poststate": {
            "formalCases": {
                **binding(CASE_PATH, post_cases),
                "recordCount": FORMAL_POST_CASE_COUNT,
                "preservedPrefixSha256": sha256(base_cases),
            },
            "formalOracles": {
                **binding(ORACLE_PATH, post_oracles),
                "recordCount": FORMAL_POST_ORACLE_COUNT,
                "preservedPrefixSha256": sha256(base_oracles),
            },
        },
        "issuanceRules": {
            "appendOnly": True,
            "existingRecordMutationAllowed": False,
            "partialAppendAllowed": False,
            "candidateTransportOrderPreserved": True,
            "otherExecutionClassRecordsIssued": 0,
        },
        "boundary": {
            "issuedCapacityCaseCount": ISSUED_CAPACITY_CASE_COUNT,
            "issuedCapacityOracleCount": ISSUED_CAPACITY_ORACLE_COUNT,
            "totalCapacityCandidateCount": 525,
            "remainingRendererExactOutputCaseCount": 162,
            "combinedCapacityRecordsIssued": 0,
            "rendererReady": False,
            "ticket19Closed": False,
            "externalProviderAttempts": 0,
        },
    }
    return target, post_cases, post_oracles


def relative_binding(value: dict[str, object]) -> dict[str, object]:
    prefix = SPEC_ROOT + "/"
    path = str(value["path"])
    return {**value, "path": path[len(prefix):] if path.startswith(prefix) else path}


def load_worktree_json(repo: Path, path: str) -> dict[str, Any]:
    return parse_json((repo / path).read_bytes(), path)


def write_worktree_json(repo: Path, path: str, value: dict[str, Any]) -> None:
    (repo / path).write_bytes(json_bytes(value))


def require_complete_poststate(
    repo: Path,
    expected_target: dict[str, Any],
    target_binding: dict[str, object],
) -> None:
    execution_catalog = load_worktree_json(repo, EXECUTION_CATALOG_PATH)
    rendering = next(
        entry
        for entry in execution_catalog["classes"]
        if entry["executionClass"] == EXECUTION_CLASS
    )
    if (
        rendering.get("status") != "EXECUTABLE_A2_REPLAYED"
        or rendering.get("caseRecordCount") != ASSIGNED_CASE_COUNT
        or rendering.get("oracleRecordCount") != ASSIGNED_ORACLE_COUNT
        or rendering.get("assignedActiveCorpusDigest")
        != expected_target["assignedCorpus"]["assignedCorpusDigest"]
        or rendering.get("recordIssuanceTarget") != relative_binding(target_binding)
        or rendering.get("executable") is not True
    ):
        raise SystemExit("formal registries are complete but execution-class catalog is not exact")
    bootstrap = load_worktree_json(repo, BOOTSTRAP_PATH)
    step = next(item for item in bootstrap["steps"] if item["executionClass"] == EXECUTION_CLASS)
    if (
        step.get("assignedCorpusStatus") != "ISSUED_156_CASES_156_ORACLES"
        or step.get("recordIssuanceTarget") != relative_binding(target_binding)
        or step.get("executable") is not True
        or bootstrap.get("caseRegistryRecordCount") != FORMAL_POST_CASE_COUNT
        or bootstrap.get("oracleRegistryRecordCount") != FORMAL_POST_ORACLE_COUNT
    ):
        raise SystemExit("formal registries are complete but bootstrap order is not exact")
    acceptance = load_worktree_json(repo, ACCEPTANCE_PATH)
    rendering_acceptance = acceptance["conformanceRegistries"]["renderingPipelineFixtureBootstrap"]
    if (
        rendering_acceptance.get("status") != "EXECUTABLE_A2_REPLAYED"
        or rendering_acceptance.get("formalCapacityRecordCount") != ASSIGNED_CASE_COUNT
        or rendering_acceptance.get("recordIssuanceTarget") != relative_binding(target_binding)
        or rendering_acceptance.get("executable") is not True
        or acceptance["counts"].get("issuedCapacityBoundaryCases")
        != ISSUED_CAPACITY_CASE_COUNT
        or acceptance["counts"].get("issuedCapacityBoundaryOracles")
        != ISSUED_CAPACITY_ORACLE_COUNT
    ):
        raise SystemExit("formal registries are complete but acceptance manifest is not exact")
    spec_target = load_worktree_json(repo, SPEC_TARGET_PATH)
    bindings = spec_target.get("registryBindings", {})
    issuance = bindings.get("appendOnlyIssuance", {})
    predecessor = issuance.get("predecessorIssuance", {})
    if (
        spec_target.get("status") != "ISSUED_APPEND_ONLY_EXACT_TARGET"
        or bindings.get("formalStatus") != "ISSUED_APPEND_ONLY_PREFIX"
        or bindings.get("formalCases", {}).get("expectedSha256")
        != expected_target["poststate"]["formalCases"]["sha256"]
        or bindings.get("formalCases", {}).get("observedSha256")
        != expected_target["poststate"]["formalCases"]["sha256"]
        or bindings.get("formalOracles", {}).get("expectedSha256")
        != expected_target["poststate"]["formalOracles"]["sha256"]
        or bindings.get("formalOracles", {}).get("observedSha256")
        != expected_target["poststate"]["formalOracles"]["sha256"]
        or issuance.get("target") != relative_binding(target_binding)
        or issuance.get("preservedCasePrefixSha256")
        != expected_target["poststate"]["formalCases"]["preservedPrefixSha256"]
        or issuance.get("preservedOraclePrefixSha256")
        != expected_target["poststate"]["formalOracles"]["preservedPrefixSha256"]
        or issuance.get("appendedExecutionClass") != EXECUTION_CLASS
        or issuance.get("appendedCaseCount") != ASSIGNED_CASE_COUNT
        or issuance.get("appendedOracleCount") != ASSIGNED_ORACLE_COUNT
        or issuance.get("assignedCorpusDigest")
        != expected_target["assignedCorpus"]["assignedCorpusDigest"]
        or predecessor.get("target")
        != relative_binding(expected_target["prestate"]["previousCapacityIssuance"])
        or predecessor.get("appendedExecutionClass") != PREDECESSOR_CLASS
    ):
        raise SystemExit("formal registries are complete but SPEC target issuance chain is not exact")


def apply_poststate(
    repo: Path,
    target_path: Path,
    expected_target: dict[str, Any],
    post_cases: bytes,
    post_oracles: bytes,
) -> None:
    expected_target_bytes = json_bytes(expected_target)
    if target_path.read_bytes() != expected_target_bytes:
        raise SystemExit("issuance target is not byte-identical to exact revision materialization")
    target_binding = binding(TARGET_PATH, expected_target_bytes)
    current_cases = (repo / CASE_PATH).read_bytes()
    current_oracles = (repo / ORACLE_PATH).read_bytes()
    base_cases_sha = expected_target["prestate"]["formalCases"]["sha256"]
    base_oracles_sha = expected_target["prestate"]["formalOracles"]["sha256"]
    post_cases_sha = expected_target["poststate"]["formalCases"]["sha256"]
    post_oracles_sha = expected_target["poststate"]["formalOracles"]["sha256"]
    if sha256(current_cases) not in {base_cases_sha, post_cases_sha}:
        raise SystemExit("formal Case registry is neither exact prestate nor complete poststate")
    if sha256(current_oracles) not in {base_oracles_sha, post_oracles_sha}:
        raise SystemExit("formal Oracle registry is neither exact prestate nor complete poststate")
    case_is_prestate = sha256(current_cases) == base_cases_sha
    oracle_is_prestate = sha256(current_oracles) == base_oracles_sha
    if case_is_prestate != oracle_is_prestate:
        raise SystemExit("formal Case and Oracle registries are in a mixed partial state")
    if not case_is_prestate:
        require_complete_poststate(repo, expected_target, target_binding)
        return

    central_prestates = {
        EXECUTION_CATALOG_PATH: expected_target["prestate"]["executionClassCatalog"],
        BOOTSTRAP_PATH: expected_target["prestate"]["bootstrapOrder"],
        ACCEPTANCE_PATH: expected_target["prestate"]["acceptanceManifest"],
        SPEC_TARGET_PATH: expected_target["prestate"]["specRegistryTarget"],
    }
    for path, expected in central_prestates.items():
        current = (repo / path).read_bytes()
        if binding(path, current) != expected:
            raise SystemExit(f"{path} is not the exact central prestate; refusing issuance")

    (repo / CASE_PATH).write_bytes(post_cases)
    (repo / ORACLE_PATH).write_bytes(post_oracles)

    execution_catalog = load_worktree_json(repo, EXECUTION_CATALOG_PATH)
    rendering = next(
        entry
        for entry in execution_catalog["classes"]
        if entry["executionClass"] == EXECUTION_CLASS
    )
    rendering.update(
        {
            "status": "EXECUTABLE_A2_REPLAYED",
            "exactProductTargetManifestStatus": "FROZEN",
            "executorManifestStatus": "FROZEN",
            "independentProductReplayStatus": "A2_PREISSUANCE_AND_POSTISSUANCE_REPLAYED",
            "exactProductTargetManifest": relative_binding(
                expected_target["predecessorProductTarget"]
            ),
            "executorManifests": [
                relative_binding(item)
                for item in expected_target["requiredExecutorManifests"]
            ],
            "recordIssuanceTarget": relative_binding(target_binding),
            "assignedActiveCorpusDigest": expected_target["assignedCorpus"][
                "assignedCorpusDigest"
            ],
            "caseRecordCount": ASSIGNED_CASE_COUNT,
            "oracleRecordCount": ASSIGNED_ORACLE_COUNT,
            "replayEvidence": POST_EVIDENCE_RELATIVE,
            "executable": True,
        }
    )
    execution_catalog["status"] = (
        "SPEC_REGISTRY_DOMAIN_SERVICES_DESIGN_INPUT_EXPRESSION_AND_RENDERING_PIPELINE_"
        "EXECUTABLE_RENDERER_EXACT_OUTPUT_PENDING"
    )
    execution_catalog["capacityBoundaryMaterialization"].update(
        {
            "status": (
                "STATIC_A2_SHAPES_DOMAIN_SERVICES_12_DESIGN_INPUT_EXPRESSION_195_AND_"
                "RENDERING_PIPELINE_156_ISSUED_RENDERER_EXACT_OUTPUT_PRODUCT_GATE_PENDING"
            ),
            "formalCapacityCaseCount": ISSUED_CAPACITY_CASE_COUNT,
            "formalCapacityOracleCount": ISSUED_CAPACITY_ORACLE_COUNT,
            "recordIssuanceAllowed": False,
            "executionEvidence": True,
        }
    )
    execution_catalog["executorManifestStatus"] = (
        "SPEC_REGISTRY_DOMAIN_SERVICES_DESIGN_INPUT_EXPRESSION_AND_RENDERING_PIPELINE_"
        "FROZEN_RENDERER_EXACT_OUTPUT_PENDING"
    )
    execution_catalog["targetManifestStatus"] = execution_catalog["executorManifestStatus"]
    write_worktree_json(repo, EXECUTION_CATALOG_PATH, execution_catalog)

    bootstrap = load_worktree_json(repo, BOOTSTRAP_PATH)
    step = next(item for item in bootstrap["steps"] if item["executionClass"] == EXECUTION_CLASS)
    step.update(
        {
            "observationAdapterStatus": "FROZEN",
            "executorManifestStatus": "FROZEN",
            "targetManifestStatus": "FROZEN",
            "assignedCorpusStatus": "ISSUED_156_CASES_156_ORACLES",
            "independentReplayStatus": "A2_PREISSUANCE_AND_POSTISSUANCE_REPLAYED",
            "activeCorpusDigest": expected_target["assignedCorpus"]["assignedCorpusDigest"],
            "targetManifest": relative_binding(expected_target["predecessorProductTarget"]),
            "executorManifests": [
                relative_binding(item)
                for item in expected_target["requiredExecutorManifests"]
            ],
            "recordIssuanceTarget": relative_binding(target_binding),
            "replayEvidence": POST_EVIDENCE_RELATIVE,
            "executable": True,
        }
    )
    phase = next(
        item for item in bootstrap["recordIssuancePhases"] if item["phase"] == "CAPACITY_BOUNDARY"
    )
    phase.update(
        {
            "formalIssuedCaseCount": ISSUED_CAPACITY_CASE_COUNT,
            "formalIssuedOracleCount": ISSUED_CAPACITY_ORACLE_COUNT,
            "executionGateStatus": (
                "DOMAIN_SERVICES_12_DESIGN_INPUT_EXPRESSION_195_AND_RENDERING_PIPELINE_156_"
                "ISSUED_AND_EXECUTABLE_RENDERER_EXACT_OUTPUT_PRODUCT_GATE_PENDING"
            ),
        }
    )
    bootstrap.update(
        {
            "status": (
                "DOMAIN_SERVICES_12_DESIGN_INPUT_EXPRESSION_195_AND_RENDERING_PIPELINE_156_"
                "CAPACITY_RECORDS_ISSUED_A2_RENDERER_EXACT_OUTPUT_PENDING"
            ),
            "currentPhaseStatus": (
                "DOMAIN_SERVICES_DESIGN_INPUT_EXPRESSION_AND_RENDERING_PIPELINE_EXECUTABLE_"
                "363_OF_525_ISSUED_RENDERER_EXACT_OUTPUT_BLOCKED"
            ),
            "caseRegistryRecordCount": FORMAL_POST_CASE_COUNT,
            "oracleRegistryRecordCount": FORMAL_POST_ORACLE_COUNT,
            "issuedRegistryInvariant": (
                "The first 46 Case and 46 Oracle records remain the byte-identical SPEC_REGISTRY candidates; "
                "the next 12 Case and 12 Oracle records remain the byte-identical DOMAIN_SERVICES assigned "
                "capacity suffix; the next 195 Case and 195 Oracle records remain the byte-identical "
                "DESIGN_INPUT_EXPRESSION assigned capacity suffix; the next 156 Case and 156 Oracle records "
                "are the byte-identical RENDERING_PIPELINE assigned capacity suffix."
            ),
        }
    )
    write_worktree_json(repo, BOOTSTRAP_PATH, bootstrap)

    acceptance = load_worktree_json(repo, ACCEPTANCE_PATH)
    registries = acceptance["conformanceRegistries"]
    registries["status"] = (
        "spec-registry-domain-services-design-input-expression-and-rendering-pipeline-issued-a2-"
        "replayed-363-of-525-capacity-records-executable-renderer-exact-output-pending"
    )
    registries["capacityBoundaryMaterialization"].update(
        {
            "status": (
                "DOMAIN_SERVICES_12_DESIGN_INPUT_EXPRESSION_195_AND_RENDERING_PIPELINE_156_"
                "ISSUED_A2_RENDERER_EXACT_OUTPUT_BLOCKED"
            ),
            "formalCapacityCaseCount": ISSUED_CAPACITY_CASE_COUNT,
            "formalCapacityOracleCount": ISSUED_CAPACITY_ORACLE_COUNT,
            "productExecutionEvidence": True,
            "recordIssuanceAllowed": False,
        }
    )
    rendering_acceptance = registries["renderingPipelineFixtureBootstrap"]
    rendering_acceptance.update(
        {
            "status": "EXECUTABLE_A2_REPLAYED",
            "exactProductTarget": relative_binding(expected_target["predecessorProductTarget"]),
            "executorManifests": [
                relative_binding(item)
                for item in expected_target["requiredExecutorManifests"]
            ],
            "recordIssuanceTarget": relative_binding(target_binding),
            "assignedActiveCorpusDigest": expected_target["assignedCorpus"][
                "assignedCorpusDigest"
            ],
            "remainingBlockers": [],
            "productExecutionEvidence": True,
            "formalCapacityRecordCount": ASSIGNED_CASE_COUNT,
            "recordIssuanceAllowed": True,
            "postIssuanceEvidence": POST_EVIDENCE_RELATIVE,
            "executable": True,
        }
    )
    case_binding = expected_target["poststate"]["formalCases"]
    oracle_binding = expected_target["poststate"]["formalOracles"]
    registries["caseRegistry"].update(
        {
            "recordCount": case_binding["recordCount"],
            "sha256": str(case_binding["sha256"]).removeprefix("sha256:"),
        }
    )
    registries["oracleRegistry"].update(
        {
            "recordCount": oracle_binding["recordCount"],
            "sha256": str(oracle_binding["sha256"]).removeprefix("sha256:"),
        }
    )
    acceptance["contractBoundaryCorpus"].update(
        {
            "executableBoundaryCaseCount": ISSUED_CAPACITY_CASE_COUNT,
            "materializationStatus": (
                "domain-services-12-design-input-expression-195-and-rendering-pipeline-156-"
                "issued-a2-renderer-exact-output-product-gate-and-formal-issuance-pending"
            ),
        }
    )
    acceptance["counts"].update(
        {
            "issuedCapacityBoundaryCases": ISSUED_CAPACITY_CASE_COUNT,
            "issuedCapacityBoundaryOracles": ISSUED_CAPACITY_ORACLE_COUNT,
            "executableContractBoundaryCases": ISSUED_CAPACITY_CASE_COUNT,
            "fullAutomatedCorpus": (
                "46 executable SPEC_REGISTRY cases plus 12 executable DOMAIN_SERVICES, 195 executable "
                "DESIGN_INPUT_EXPRESSION, and 156 executable RENDERING_PIPELINE capacity cases; the "
                "remaining 162 isolated capacity, 18 combined, 108 non-issued Editor candidates, and "
                "still-unmapped nonCapacityAtomicCases remain non-executable"
            ),
        }
    )
    write_worktree_json(repo, ACCEPTANCE_PATH, acceptance)

    spec_target = load_worktree_json(repo, SPEC_TARGET_PATH)
    spec_target.update(
        {
            "status": "ISSUED_APPEND_ONLY_EXACT_TARGET",
            "implementationRevision": SPEC_IMPLEMENTATION_REVISION,
        }
    )
    bindings = spec_target["registryBindings"]
    bindings["formalCases"].update(
        {
            "expectedSha256": case_binding["sha256"],
            "observedSha256": case_binding["sha256"],
        }
    )
    bindings["formalOracles"].update(
        {
            "expectedSha256": oracle_binding["sha256"],
            "observedSha256": oracle_binding["sha256"],
        }
    )
    bindings["formalStatus"] = "ISSUED_APPEND_ONLY_PREFIX"
    previous_append = bindings["appendOnlyIssuance"]
    bindings["appendOnlyIssuance"] = {
        "predecessorIssuance": previous_append,
        "target": relative_binding(target_binding),
        "preservedCasePrefixSha256": case_binding["preservedPrefixSha256"],
        "preservedOraclePrefixSha256": oracle_binding["preservedPrefixSha256"],
        "appendedExecutionClass": EXECUTION_CLASS,
        "appendedCaseCount": ASSIGNED_CASE_COUNT,
        "appendedOracleCount": ASSIGNED_ORACLE_COUNT,
        "assignedCorpusDigest": expected_target["assignedCorpus"]["assignedCorpusDigest"],
    }
    write_worktree_json(repo, SPEC_TARGET_PATH, spec_target)


def write_new(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(data)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--implementation-revision", required=True)
    parser.add_argument("--target-output", type=Path, required=True)
    parser.add_argument("--expected-case-output", type=Path)
    parser.add_argument("--expected-oracle-output", type=Path)
    parser.add_argument("--apply-poststate", action="store_true")
    args = parser.parse_args()
    repo = args.repo.resolve()
    target, post_cases, post_oracles = build_target(repo, args.implementation_revision)
    target_bytes = json_bytes(target)
    if args.apply_poststate:
        apply_poststate(repo, args.target_output.resolve(), target, post_cases, post_oracles)
    else:
        write_new(args.target_output, target_bytes)
        if args.expected_case_output:
            write_new(args.expected_case_output, post_cases)
        if args.expected_oracle_output:
            write_new(args.expected_oracle_output, post_oracles)
    print(
        json.dumps(
            {
                "status": "APPLIED" if args.apply_poststate else "MATERIALIZED",
                "implementationRevision": target["implementationRevision"],
                "target": binding(TARGET_PATH, target_bytes),
                "postCaseRegistry": target["poststate"]["formalCases"],
                "postOracleRegistry": target["poststate"]["formalOracles"],
                "assignedCorpusDigest": target["assignedCorpus"]["assignedCorpusDigest"],
            },
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    main()
