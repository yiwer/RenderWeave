#!/usr/bin/env python3
"""Materialize and, when explicitly requested, apply the exact Domain Services issuance."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any


EXECUTION_CLASS = "EXEC::DOMAIN_SERVICES::1.0"
TARGET_ID = "DOMAIN_SERVICES_ISSUANCE::CAPACITY::1.0"
TARGET_VERSION = "renderweave-domain-services-capacity-issuance-target/1.0"
SPEC_PREFIX_CASE_COUNT = 46
SPEC_PREFIX_ORACLE_COUNT = 46
DOMAIN_CASE_COUNT = 12
DOMAIN_ORACLE_COUNT = 12
SPEC_IMPLEMENTATION_REVISION = "spec-registry-bootstrap/1.14"
SPEC_ROOT = ".scratch/renderweave-template-v1"
TARGET_PATH = f"{SPEC_ROOT}/domain-services/capacity-record-issuance-target-v1.json"
CASE_PATH = f"{SPEC_ROOT}/conformance-cases-v1.jsonl"
ORACLE_PATH = f"{SPEC_ROOT}/conformance-oracles-v1.jsonl"
CANDIDATE_CASE_PATH = f"{SPEC_ROOT}/capacity-boundary/candidate/conformance-cases-v1.jsonl"
CANDIDATE_ORACLE_PATH = f"{SPEC_ROOT}/capacity-boundary/candidate/conformance-oracles-v1.jsonl"
EXECUTION_CATALOG_PATH = f"{SPEC_ROOT}/conformance-execution-classes-v1.json"
BOOTSTRAP_PATH = f"{SPEC_ROOT}/conformance-bootstrap-order-v1.json"
ACCEPTANCE_PATH = f"{SPEC_ROOT}/acceptance-manifest-v1.json"
SPEC_TARGET_PATH = f"{SPEC_ROOT}/spec-registry/target-manifest-v1.json"
POST_EVIDENCE_RELATIVE = "domain-services/domain-services-capacity-postissuance-a2-2026-08-26.json"

ARTIFACT_PATHS = (
    f"{SPEC_ROOT}/domain-services/execution-class-target-v1.json",
    f"{SPEC_ROOT}/domain-services/java-domain-authority-executor-manifest-v1.json",
    f"{SPEC_ROOT}/domain-services/transactional-integration-replayer-manifest-v1.json",
    f"{SPEC_ROOT}/domain-services/observation-adapter-v1.json",
    f"{SPEC_ROOT}/capacity-boundary/materialization-manifest-v1.json",
    f"{SPEC_ROOT}/capacity-boundary/primary-result-v1.json",
    f"{SPEC_ROOT}/capacity-boundary/independent-result-v1.json",
    f"{SPEC_ROOT}/capacity-boundary/capacity-boundary-static-a2-2026-08-17.json",
    f"{SPEC_ROOT}/conformance-case-record-schema-v1.json",
    f"{SPEC_ROOT}/conformance-oracle-record-schema-v1.json",
    f"{SPEC_ROOT}/conformance-canonical-profile-v1.json",
    f"{SPEC_ROOT}/conformance-probe-profile-v1.json",
    f"{SPEC_ROOT}/conformance-manifest-snapshot-policy-v1.json",
    f"{SPEC_ROOT}/domain-services/materialize-domain-services-capacity-issuance.py",
    f"{SPEC_ROOT}/domain-services/validate-domain-services-postissuance-primary.mjs",
    f"{SPEC_ROOT}/domain-services/validate_domain_services_postissuance_independent.py",
    f"{SPEC_ROOT}/domain-services/write-domain-services-postissuance-a2-evidence.mjs",
    f"{SPEC_ROOT}/spec-registry/refresh-spec-registry-postissuance-target.mjs",
    f"{SPEC_ROOT}/spec-registry/validate-spec-registry-primary.mjs",
    f"{SPEC_ROOT}/spec-registry/validate-spec-registry-independent.py",
    f"{SPEC_ROOT}/spec-registry/write-spec-registry-a2-evidence.mjs",
    "tools/run-domain-services-record-issuance-gate.ps1",
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
    cases = [raw for raw, record in case_rows if record.get("executionClass") == EXECUTION_CLASS]
    oracle_ids = {
        evidence["oracleId"]
        for _, record in case_rows
        if record.get("executionClass") == EXECUTION_CLASS
        for coverage in record["coverage"]
        for evidence in coverage["evidence"]
    }
    oracles = [raw for raw, record in oracle_rows if record.get("oracleId") in oracle_ids]
    case_ids = [
        record["caseId"] for _, record in case_rows
        if record.get("executionClass") == EXECUTION_CLASS
    ]
    ordered_oracle_ids = [
        record["oracleId"] for _, record in oracle_rows
        if record.get("oracleId") in oracle_ids
    ]
    if (len(cases), len(oracles), len(oracle_ids)) != (
        DOMAIN_CASE_COUNT, DOMAIN_ORACLE_COUNT, DOMAIN_ORACLE_COUNT
    ):
        raise SystemExit("assigned Domain Services corpus must be exactly 12 Case + 12 Oracle")
    case_bytes = b"".join(cases)
    oracle_bytes = b"".join(oracles)
    return {
        "caseBytes": case_bytes,
        "oracleBytes": oracle_bytes,
        "caseIds": case_ids,
        "oracleIds": ordered_oracle_ids,
        "digest": sha256(
            b"renderweave-domain-services-assigned-corpus/1\0"
            + case_bytes + b"\0" + oracle_bytes
        ),
    }


def build_target(repo: Path, revision: str) -> tuple[dict[str, Any], bytes, bytes]:
    resolved_revision = resolve_revision(repo, revision)
    base_cases = git_blob(repo, resolved_revision, CASE_PATH)
    base_oracles = git_blob(repo, resolved_revision, ORACLE_PATH)
    if len(json_lines(base_cases, "formal Case prestate")) != SPEC_PREFIX_CASE_COUNT:
        raise SystemExit("formal Case prestate must contain exactly 46 records")
    if len(json_lines(base_oracles, "formal Oracle prestate")) != SPEC_PREFIX_ORACLE_COUNT:
        raise SystemExit("formal Oracle prestate must contain exactly 46 records")
    spec_cases = git_blob(
        repo, resolved_revision, f"{SPEC_ROOT}/spec-registry/candidate/conformance-cases-v1.jsonl"
    )
    spec_oracles = git_blob(
        repo, resolved_revision, f"{SPEC_ROOT}/spec-registry/candidate/conformance-oracles-v1.jsonl"
    )
    if base_cases != spec_cases or base_oracles != spec_oracles:
        raise SystemExit("formal prestate must be byte-identical to the issued SPEC_REGISTRY candidates")
    candidate_cases = git_blob(repo, resolved_revision, CANDIDATE_CASE_PATH)
    candidate_oracles = git_blob(repo, resolved_revision, CANDIDATE_ORACLE_PATH)
    assigned = assigned_corpus(candidate_cases, candidate_oracles)
    post_cases = base_cases + assigned["caseBytes"]
    post_oracles = base_oracles + assigned["oracleBytes"]
    artifacts = [binding(path, git_blob(repo, resolved_revision, path)) for path in ARTIFACT_PATHS]
    target = {
        "artifactVersion": TARGET_VERSION,
        "targetId": TARGET_ID,
        "status": "FROZEN_APPEND_ONLY_ISSUANCE_TARGET",
        "implementationRevision": resolved_revision,
        "executionClass": EXECUTION_CLASS,
        "predecessorProductTarget": binding(
            f"{SPEC_ROOT}/domain-services/execution-class-target-v1.json",
            git_blob(repo, resolved_revision, f"{SPEC_ROOT}/domain-services/execution-class-target-v1.json"),
        ),
        "requiredExecutorManifests": [
            binding(
                f"{SPEC_ROOT}/domain-services/java-domain-authority-executor-manifest-v1.json",
                git_blob(repo, resolved_revision, f"{SPEC_ROOT}/domain-services/java-domain-authority-executor-manifest-v1.json"),
            ),
            binding(
                f"{SPEC_ROOT}/domain-services/transactional-integration-replayer-manifest-v1.json",
                git_blob(repo, resolved_revision, f"{SPEC_ROOT}/domain-services/transactional-integration-replayer-manifest-v1.json"),
            ),
        ],
        "artifacts": artifacts,
        "prestate": {
            "formalCases": {**binding(CASE_PATH, base_cases), "recordCount": SPEC_PREFIX_CASE_COUNT},
            "formalOracles": {**binding(ORACLE_PATH, base_oracles), "recordCount": SPEC_PREFIX_ORACLE_COUNT},
            "executionClassCatalog": binding(
                EXECUTION_CATALOG_PATH, git_blob(repo, resolved_revision, EXECUTION_CATALOG_PATH)
            ),
            "bootstrapOrder": binding(BOOTSTRAP_PATH, git_blob(repo, resolved_revision, BOOTSTRAP_PATH)),
            "acceptanceManifest": binding(
                ACCEPTANCE_PATH, git_blob(repo, resolved_revision, ACCEPTANCE_PATH)
            ),
            "specRegistryTarget": binding(
                SPEC_TARGET_PATH, git_blob(repo, resolved_revision, SPEC_TARGET_PATH)
            ),
        },
        "assignedCorpus": {
            "sourceCases": binding(CANDIDATE_CASE_PATH, candidate_cases),
            "sourceOracles": binding(CANDIDATE_ORACLE_PATH, candidate_oracles),
            "caseCount": DOMAIN_CASE_COUNT,
            "oracleCount": DOMAIN_ORACLE_COUNT,
            "caseIds": assigned["caseIds"],
            "oracleIds": assigned["oracleIds"],
            "caseBytesSha256": sha256(assigned["caseBytes"]),
            "oracleBytesSha256": sha256(assigned["oracleBytes"]),
            "assignedCorpusDigest": assigned["digest"],
        },
        "poststate": {
            "formalCases": {
                **binding(CASE_PATH, post_cases),
                "recordCount": SPEC_PREFIX_CASE_COUNT + DOMAIN_CASE_COUNT,
                "preservedPrefixSha256": sha256(base_cases),
            },
            "formalOracles": {
                **binding(ORACLE_PATH, post_oracles),
                "recordCount": SPEC_PREFIX_ORACLE_COUNT + DOMAIN_ORACLE_COUNT,
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
            "issuedCapacityCaseCount": DOMAIN_CASE_COUNT,
            "issuedCapacityOracleCount": DOMAIN_ORACLE_COUNT,
            "totalCapacityCandidateCount": 525,
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


def apply_poststate(
    repo: Path,
    target_path: Path,
    expected_target: dict[str, Any],
    post_cases: bytes,
    post_oracles: bytes,
) -> None:
    expected_target_bytes = json_bytes(expected_target)
    if target_path.read_bytes() != expected_target_bytes:
        raise SystemExit("issuance target is not byte-identical to the exact implementation-revision materialization")
    target_binding = binding(TARGET_PATH, expected_target_bytes)
    current_cases = (repo / CASE_PATH).read_bytes()
    current_oracles = (repo / ORACLE_PATH).read_bytes()
    base_cases_sha = expected_target["prestate"]["formalCases"]["sha256"]
    base_oracles_sha = expected_target["prestate"]["formalOracles"]["sha256"]
    if sha256(current_cases) not in {base_cases_sha, expected_target["poststate"]["formalCases"]["sha256"]}:
        raise SystemExit("formal Case registry is neither the exact prestate nor the exact complete poststate")
    if sha256(current_oracles) not in {base_oracles_sha, expected_target["poststate"]["formalOracles"]["sha256"]}:
        raise SystemExit("formal Oracle registry is neither the exact prestate nor the exact complete poststate")
    (repo / CASE_PATH).write_bytes(post_cases)
    (repo / ORACLE_PATH).write_bytes(post_oracles)

    execution_catalog = load_worktree_json(repo, EXECUTION_CATALOG_PATH)
    domain = next(
        entry for entry in execution_catalog["classes"]
        if entry["executionClass"] == EXECUTION_CLASS
    )
    domain.update({
        "status": "EXECUTABLE_A2_REPLAYED",
        "exactProductTargetManifestStatus": "FROZEN",
        "executorManifestStatus": "FROZEN",
        "independentProductReplayStatus": "A2_PREISSUANCE_AND_POSTISSUANCE_REPLAYED",
        "exactProductTargetManifest": relative_binding(expected_target["predecessorProductTarget"]),
        "executorManifests": [relative_binding(item) for item in expected_target["requiredExecutorManifests"]],
        "recordIssuanceTarget": relative_binding(target_binding),
        "assignedActiveCorpusDigest": expected_target["assignedCorpus"]["assignedCorpusDigest"],
        "caseRecordCount": DOMAIN_CASE_COUNT,
        "oracleRecordCount": DOMAIN_ORACLE_COUNT,
        "replayEvidence": POST_EVIDENCE_RELATIVE,
        "executable": True,
    })
    execution_catalog["status"] = (
        "SPEC_REGISTRY_AND_DOMAIN_SERVICES_EXECUTABLE_OTHER_CLASS_PRODUCT_MANIFESTS_PENDING"
    )
    execution_catalog["capacityBoundaryMaterialization"].update({
        "status": "STATIC_A2_SHAPES_DOMAIN_SERVICES_12_ISSUED_OTHER_CLASS_PRODUCT_GATES_PENDING",
        "formalCapacityCaseCount": DOMAIN_CASE_COUNT,
        "formalCapacityOracleCount": DOMAIN_ORACLE_COUNT,
        "recordIssuanceAllowed": False,
        "executionEvidence": True,
    })
    execution_catalog["executorManifestStatus"] = (
        "SPEC_REGISTRY_AND_DOMAIN_SERVICES_FROZEN_OTHER_CLASSES_PENDING"
    )
    execution_catalog["targetManifestStatus"] = (
        "SPEC_REGISTRY_AND_DOMAIN_SERVICES_FROZEN_OTHER_CLASSES_PENDING"
    )
    write_worktree_json(repo, EXECUTION_CATALOG_PATH, execution_catalog)

    bootstrap = load_worktree_json(repo, BOOTSTRAP_PATH)
    step = next(item for item in bootstrap["steps"] if item["executionClass"] == EXECUTION_CLASS)
    step.update({
        "observationAdapterStatus": "FROZEN",
        "executorManifestStatus": "FROZEN",
        "targetManifestStatus": "FROZEN",
        "assignedCorpusStatus": "ISSUED_12_CASES_12_ORACLES",
        "independentReplayStatus": "A2_PREISSUANCE_AND_POSTISSUANCE_REPLAYED",
        "activeCorpusDigest": expected_target["assignedCorpus"]["assignedCorpusDigest"],
        "targetManifest": relative_binding(expected_target["predecessorProductTarget"]),
        "executorManifests": [relative_binding(item) for item in expected_target["requiredExecutorManifests"]],
        "recordIssuanceTarget": relative_binding(target_binding),
        "replayEvidence": POST_EVIDENCE_RELATIVE,
        "executable": True,
    })
    phase = next(item for item in bootstrap["recordIssuancePhases"] if item["phase"] == "CAPACITY_BOUNDARY")
    phase.update({
        "formalIssuedCaseCount": DOMAIN_CASE_COUNT,
        "formalIssuedOracleCount": DOMAIN_ORACLE_COUNT,
        "executionGateStatus": (
            "DOMAIN_SERVICES_12_ISSUED_AND_EXECUTABLE_OTHER_THREE_ASSIGNED_CLASSES_PRODUCT_GATES_PENDING"
        ),
    })
    bootstrap.update({
        "status": "DOMAIN_SERVICES_12_CAPACITY_RECORDS_ISSUED_A2_OTHER_CLASS_PRODUCT_GATES_PENDING",
        "currentPhaseStatus": (
            "DOMAIN_SERVICES_EXECUTABLE_12_OF_525_ISSUED_OTHER_ASSIGNED_CLASSES_BLOCKED"
        ),
        "caseRegistryRecordCount": SPEC_PREFIX_CASE_COUNT + DOMAIN_CASE_COUNT,
        "oracleRegistryRecordCount": SPEC_PREFIX_ORACLE_COUNT + DOMAIN_ORACLE_COUNT,
        "issuedRegistryInvariant": (
            "The first 46 Case and 46 Oracle records remain the byte-identical SPEC_REGISTRY candidates; "
            "the next 12 Case and 12 Oracle records are the byte-identical DOMAIN_SERVICES assigned capacity suffix."
        ),
    })
    write_worktree_json(repo, BOOTSTRAP_PATH, bootstrap)

    acceptance = load_worktree_json(repo, ACCEPTANCE_PATH)
    registries = acceptance["conformanceRegistries"]
    registries["status"] = (
        "spec-registry-and-domain-services-issued-a2-replayed-12-of-525-capacity-records-executable-"
        "other-class-product-gates-pending"
    )
    registries["capacityBoundaryMaterialization"].update({
        "status": "DOMAIN_SERVICES_12_ISSUED_A2_OTHER_CAPACITY_CLASSES_BLOCKED",
        "formalCapacityCaseCount": DOMAIN_CASE_COUNT,
        "formalCapacityOracleCount": DOMAIN_ORACLE_COUNT,
        "productExecutionEvidence": True,
        "recordIssuanceAllowed": False,
    })
    domain_acceptance = registries["domainServicesFixtureBootstrap"]
    domain_acceptance.update({
        "status": "EXECUTABLE_A2_REPLAYED",
        "exactProductTarget": relative_binding(expected_target["predecessorProductTarget"]),
        "executorManifests": [relative_binding(item) for item in expected_target["requiredExecutorManifests"]],
        "recordIssuanceTarget": relative_binding(target_binding),
        "assignedActiveCorpusDigest": expected_target["assignedCorpus"]["assignedCorpusDigest"],
        "remainingBlockers": [],
        "productExecutionEvidence": True,
        "formalCapacityRecordCount": DOMAIN_CASE_COUNT,
        "recordIssuanceAllowed": True,
        "postIssuanceEvidence": POST_EVIDENCE_RELATIVE,
        "executable": True,
    })
    case_binding = expected_target["poststate"]["formalCases"]
    oracle_binding = expected_target["poststate"]["formalOracles"]
    registries["caseRegistry"].update({
        "recordCount": case_binding["recordCount"],
        "sha256": str(case_binding["sha256"]).removeprefix("sha256:"),
    })
    registries["oracleRegistry"].update({
        "recordCount": oracle_binding["recordCount"],
        "sha256": str(oracle_binding["sha256"]).removeprefix("sha256:"),
    })
    acceptance["contractBoundaryCorpus"].update({
        "executableBoundaryCaseCount": DOMAIN_CASE_COUNT,
        "materializationStatus": (
            "domain-services-12-issued-a2-other-capacity-class-product-gates-and-formal-issuance-pending"
        ),
    })
    acceptance["counts"].update({
        "issuedCapacityBoundaryCases": DOMAIN_CASE_COUNT,
        "issuedCapacityBoundaryOracles": DOMAIN_ORACLE_COUNT,
        "executableContractBoundaryCases": DOMAIN_CASE_COUNT,
        "fullAutomatedCorpus": (
            "46 executable SPEC_REGISTRY cases plus 12 executable DOMAIN_SERVICES capacity cases; "
            "the remaining 513 isolated capacity, 18 combined, 108 non-issued Editor candidates, and "
            "still-unmapped nonCapacityAtomicCases remain non-executable"
        ),
    })
    write_worktree_json(repo, ACCEPTANCE_PATH, acceptance)

    spec_target = load_worktree_json(repo, SPEC_TARGET_PATH)
    spec_target.update({
        "status": "ISSUED_APPEND_ONLY_EXACT_TARGET",
        "implementationRevision": SPEC_IMPLEMENTATION_REVISION,
    })
    bindings = spec_target["registryBindings"]
    bindings["formalCases"].update({
        "expectedSha256": case_binding["sha256"],
        "observedSha256": case_binding["sha256"],
    })
    bindings["formalOracles"].update({
        "expectedSha256": oracle_binding["sha256"],
        "observedSha256": oracle_binding["sha256"],
    })
    bindings["formalStatus"] = "ISSUED_APPEND_ONLY_PREFIX"
    bindings["appendOnlyIssuance"] = {
        "target": relative_binding(target_binding),
        "preservedCasePrefixSha256": case_binding["preservedPrefixSha256"],
        "preservedOraclePrefixSha256": oracle_binding["preservedPrefixSha256"],
        "appendedExecutionClass": EXECUTION_CLASS,
        "appendedCaseCount": DOMAIN_CASE_COUNT,
        "appendedOracleCount": DOMAIN_ORACLE_COUNT,
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
    print(json.dumps({
        "status": "APPLIED" if args.apply_poststate else "MATERIALIZED",
        "implementationRevision": target["implementationRevision"],
        "target": binding(TARGET_PATH, target_bytes),
        "postCaseRegistry": target["poststate"]["formalCases"],
        "postOracleRegistry": target["poststate"]["formalOracles"],
        "assignedCorpusDigest": target["assignedCorpus"]["assignedCorpusDigest"],
    }, separators=(",", ":")))


if __name__ == "__main__":
    main()
