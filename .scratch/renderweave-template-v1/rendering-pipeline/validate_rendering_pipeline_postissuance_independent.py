#!/usr/bin/env python3
"""Independently replay Rendering Pipeline formal capacity issuance."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Any


SPEC_ROOT = ".scratch/renderweave-template-v1"
EXECUTION_CLASS = "EXEC::RENDERING_PIPELINE::1.0"
PREDECESSOR_CLASS = "EXEC::DESIGN_INPUT_EXPRESSION::1.0"
TARGET_VERSION = "renderweave-rendering-pipeline-capacity-issuance-target/1.0"
TARGET_ID = "RENDERING_PIPELINE_ISSUANCE::CAPACITY::1.0"
DEFAULT_TARGET = f"{SPEC_ROOT}/rendering-pipeline/capacity-record-issuance-target-v1.json"
PRODUCT_TARGET_PATH = f"{SPEC_ROOT}/rendering-pipeline/execution-class-target-v1.json"
CASE_PATH = f"{SPEC_ROOT}/conformance-cases-v1.jsonl"
ORACLE_PATH = f"{SPEC_ROOT}/conformance-oracles-v1.jsonl"
FORMAL_PREFIX_COUNT = 253
ASSIGNED_COUNT = 156
FORMAL_POST_COUNT = FORMAL_PREFIX_COUNT + ASSIGNED_COUNT
ISSUED_CAPACITY_COUNT = 12 + 195 + ASSIGNED_COUNT


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def binding(path: str, data: bytes) -> dict[str, object]:
    return {"path": path, "sha256": sha256(data), "byteLength": len(data)}


def duplicate_safe_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate member: {key}")
        value[key] = item
    return value


def load_json_bytes(data: bytes, label: str) -> dict[str, Any]:
    if data.startswith(b"\xef\xbb\xbf") or b"\r" in data:
        raise ValueError(f"{label}: non-canonical transport")
    value = json.loads(
        data.decode("utf-8", "strict"),
        object_pairs_hook=duplicate_safe_pairs,
        parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)),
    )
    if not isinstance(value, dict):
        raise ValueError(f"{label}: root must be object")
    return value


def json_lines(data: bytes, label: str) -> list[tuple[bytes, dict[str, Any]]]:
    if data.startswith(b"\xef\xbb\xbf") or b"\r" in data or not data.endswith(b"\n"):
        raise ValueError(f"{label}: BOM-free LF-terminated JSONL required")
    rows: list[tuple[bytes, dict[str, Any]]] = []
    for index, line in enumerate(data.splitlines(), 1):
        if not line:
            raise ValueError(f"{label}: blank line {index}")
        rows.append((line + b"\n", load_json_bytes(line, f"{label}:{index}")))
    return rows


def git_blob(repo: Path, revision: str, path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def utf8_key(value: str) -> bytes:
    return value.encode("utf-8")


class Replay:
    def __init__(self, repo: Path, target_path: str) -> None:
        self.repo = repo
        self.target_path = target_path
        self.check_count = 0
        self.failures: list[dict[str, object]] = []

    def check(self, condition: bool, code: str, detail: object) -> None:
        self.check_count += 1
        if not condition:
            self.failures.append({"code": code, "detail": detail})

    def read(self, path: str) -> bytes:
        return (self.repo / path).read_bytes()

    def replay(self) -> dict[str, Any]:
        target_bytes = self.read(self.target_path)
        target = load_json_bytes(target_bytes, self.target_path)
        self.check(target.get("artifactVersion") == TARGET_VERSION,
                   "TARGET_VERSION", target.get("artifactVersion"))
        self.check(target.get("targetId") == TARGET_ID, "TARGET_ID", target.get("targetId"))
        self.check(target.get("status") == "FROZEN_APPEND_ONLY_ISSUANCE_TARGET",
                   "TARGET_STATUS", target.get("status"))
        revision = target.get("implementationRevision")
        self.check(isinstance(revision, str) and len(revision) == 40 and
                   all(character in "0123456789abcdef" for character in revision),
                   "TARGET_REVISION", revision)
        self.check(target.get("executionClass") == EXECUTION_CLASS,
                   "TARGET_CLASS", target.get("executionClass"))
        if not isinstance(revision, str):
            raise ValueError("target implementation revision unavailable")

        artifact_paths: set[str] = set()
        for artifact in target["artifacts"]:
            path = artifact["path"]
            self.check(path not in artifact_paths, "TARGET_ARTIFACT_PATH", path)
            artifact_paths.add(path)
            value = git_blob(self.repo, revision, path)
            self.check(artifact == binding(path, value), "TARGET_ARTIFACT_BINDING", path)

        product_target_bytes = git_blob(self.repo, revision, PRODUCT_TARGET_PATH)
        product_target = load_json_bytes(product_target_bytes, PRODUCT_TARGET_PATH)
        self.check(target["predecessorProductTarget"] ==
                   binding(PRODUCT_TARGET_PATH, product_target_bytes),
                   "PREDECESSOR_TARGET_BINDING", PRODUCT_TARGET_PATH)
        self.check(product_target.get("executionClass") == EXECUTION_CLASS and
                   product_target.get("assignedCorpus", {}).get("assignedCorpusDigest") ==
                   target["assignedCorpus"]["assignedCorpusDigest"],
                   "PREDECESSOR_ASSIGNED_DIGEST",
                   product_target.get("assignedCorpus", {}).get("assignedCorpusDigest"))
        product_assigned = product_target["assignedCorpus"]
        self.check((product_assigned.get("assignedCaseCount"),
                    product_assigned.get("assignedOracleCount")) ==
                   (ASSIGNED_COUNT, ASSIGNED_COUNT),
                   "PREDECESSOR_ASSIGNED_COUNTS",
                   [product_assigned.get("assignedCaseCount"),
                    product_assigned.get("assignedOracleCount")])
        product_boundary = product_target["formalRegistryBoundary"]
        self.check(product_boundary["cases"]["sha256"] ==
                   target["prestate"]["formalCases"]["sha256"] and
                   product_boundary["oracles"]["sha256"] ==
                   target["prestate"]["formalOracles"]["sha256"] and
                   product_boundary.get("appendPerformed") is False,
                   "PREDECESSOR_FORMAL_BOUNDARY", product_boundary)

        executor_roles: list[str] = []
        for manifest in target["requiredExecutorManifests"]:
            path = manifest["path"]
            value = git_blob(self.repo, revision, path)
            parsed = load_json_bytes(value, path)
            self.check(manifest == binding(path, value), "EXECUTOR_MANIFEST_BINDING", path)
            self.check(parsed.get("executionClass") == EXECUTION_CLASS and
                       parsed.get("targetManifest", {}).get("sha256") ==
                       target["predecessorProductTarget"]["sha256"],
                       "EXECUTOR_MANIFEST_TARGET", path)
            executor_roles.append(parsed.get("role"))
        self.check(executor_roles == [
            "java-evaluator-and-sealer",
            "rust-render-document-parser-and-engine",
        ], "EXECUTOR_ROLES", executor_roles)

        candidate_case_path = target["assignedCorpus"]["sourceCases"]["path"]
        candidate_oracle_path = target["assignedCorpus"]["sourceOracles"]["path"]
        candidate_cases = git_blob(self.repo, revision, candidate_case_path)
        candidate_oracles = git_blob(self.repo, revision, candidate_oracle_path)
        self.check(target["assignedCorpus"]["sourceCases"] ==
                   binding(candidate_case_path, candidate_cases),
                   "CANDIDATE_CASE_BINDING", candidate_case_path)
        self.check(target["assignedCorpus"]["sourceOracles"] ==
                   binding(candidate_oracle_path, candidate_oracles),
                   "CANDIDATE_ORACLE_BINDING", candidate_oracle_path)
        candidate_case_rows = json_lines(candidate_cases, "candidate cases")
        candidate_oracle_rows = json_lines(candidate_oracles, "candidate oracles")
        assigned_case_rows = [
            row for row in candidate_case_rows
            if row[1].get("executionClass") == EXECUTION_CLASS
        ]
        assigned_oracle_ids = {
            evidence["oracleId"]
            for _, record in assigned_case_rows
            for coverage in record["coverage"]
            for evidence in coverage["evidence"]
        }
        assigned_oracle_rows = [
            row for row in candidate_oracle_rows
            if row[1].get("oracleId") in assigned_oracle_ids
        ]
        assigned_case_bytes = b"".join(raw for raw, _ in assigned_case_rows)
        assigned_oracle_bytes = b"".join(raw for raw, _ in assigned_oracle_rows)
        self.check((len(assigned_case_rows), len(assigned_oracle_rows),
                    len(assigned_oracle_ids)) ==
                   (ASSIGNED_COUNT, ASSIGNED_COUNT, ASSIGNED_COUNT),
                   "ASSIGNED_COUNTS",
                   [len(assigned_case_rows), len(assigned_oracle_rows),
                    len(assigned_oracle_ids)])
        self.check(target["assignedCorpus"]["caseBytesSha256"] == sha256(assigned_case_bytes),
                   "ASSIGNED_CASE_BYTES", sha256(assigned_case_bytes))
        self.check(target["assignedCorpus"]["oracleBytesSha256"] ==
                   sha256(assigned_oracle_bytes),
                   "ASSIGNED_ORACLE_BYTES", sha256(assigned_oracle_bytes))
        self.check((target["assignedCorpus"]["caseCount"],
                    target["assignedCorpus"]["oracleCount"]) ==
                   (ASSIGNED_COUNT, ASSIGNED_COUNT),
                   "TARGET_ASSIGNED_COUNTS",
                   [target["assignedCorpus"]["caseCount"],
                    target["assignedCorpus"]["oracleCount"]])
        self.check(target["assignedCorpus"]["caseIds"] ==
                   [record["caseId"] for _, record in assigned_case_rows],
                   "TARGET_ASSIGNED_CASE_IDS", len(target["assignedCorpus"]["caseIds"]))
        self.check(target["assignedCorpus"]["oracleIds"] ==
                   [record["oracleId"] for _, record in assigned_oracle_rows],
                   "TARGET_ASSIGNED_ORACLE_IDS", len(target["assignedCorpus"]["oracleIds"]))
        assigned_digest = sha256(
            b"renderweave-rendering-pipeline-assigned-corpus/1\0"
            + assigned_case_bytes + b"\0" + assigned_oracle_bytes
        )
        self.check(target["assignedCorpus"]["assignedCorpusDigest"] == assigned_digest,
                   "TARGET_ASSIGNED_CORPUS_DIGEST", assigned_digest)

        base_case_path = target["prestate"]["formalCases"]["path"]
        base_oracle_path = target["prestate"]["formalOracles"]["path"]
        base_cases = git_blob(self.repo, revision, base_case_path)
        base_oracles = git_blob(self.repo, revision, base_oracle_path)
        previous_path = target["prestate"]["previousCapacityIssuance"]["path"]
        previous_bytes = git_blob(self.repo, revision, previous_path)
        previous = load_json_bytes(previous_bytes, previous_path)
        self.check(target["prestate"]["previousCapacityIssuance"] ==
                   binding(previous_path, previous_bytes),
                   "PREVIOUS_ISSUANCE_BINDING", previous_path)
        self.check(previous.get("executionClass") == PREDECESSOR_CLASS and
                   previous.get("poststate", {}).get("formalCases", {}).get("sha256") ==
                   sha256(base_cases) and
                   previous.get("poststate", {}).get("formalOracles", {}).get("sha256") ==
                   sha256(base_oracles),
                   "PREVIOUS_ISSUANCE_POSTSTATE", previous.get("executionClass"))
        case_prefix_length = previous["prestate"]["formalCases"]["byteLength"]
        oracle_prefix_length = previous["prestate"]["formalOracles"]["byteLength"]
        self.check(sha256(base_cases[:case_prefix_length]) ==
                   previous["prestate"]["formalCases"]["sha256"] and
                   sha256(base_cases[case_prefix_length:]) ==
                   previous["assignedCorpus"]["caseBytesSha256"],
                   "PREVIOUS_CASE_CHAIN", sha256(base_cases))
        self.check(sha256(base_oracles[:oracle_prefix_length]) ==
                   previous["prestate"]["formalOracles"]["sha256"] and
                   sha256(base_oracles[oracle_prefix_length:]) ==
                   previous["assignedCorpus"]["oracleBytesSha256"],
                   "PREVIOUS_ORACLE_CHAIN", sha256(base_oracles))

        formal_cases = self.read(CASE_PATH)
        formal_oracles = self.read(ORACLE_PATH)
        self.check(target["prestate"]["formalCases"] ==
                   {**binding(CASE_PATH, base_cases), "recordCount": FORMAL_PREFIX_COUNT},
                   "PRESTATE_CASE_BINDING", sha256(base_cases))
        self.check(target["prestate"]["formalOracles"] ==
                   {**binding(ORACLE_PATH, base_oracles), "recordCount": FORMAL_PREFIX_COUNT},
                   "PRESTATE_ORACLE_BINDING", sha256(base_oracles))
        self.check(formal_cases == base_cases + assigned_case_bytes,
                   "FORMAL_CASE_EXACT_APPEND", sha256(formal_cases))
        self.check(formal_oracles == base_oracles + assigned_oracle_bytes,
                   "FORMAL_ORACLE_EXACT_APPEND", sha256(formal_oracles))
        self.check(target["poststate"]["formalCases"] == {
            **binding(CASE_PATH, formal_cases),
            "recordCount": FORMAL_POST_COUNT,
            "preservedPrefixSha256": sha256(base_cases),
        }, "POSTSTATE_CASE_BINDING", sha256(formal_cases))
        self.check(target["poststate"]["formalOracles"] == {
            **binding(ORACLE_PATH, formal_oracles),
            "recordCount": FORMAL_POST_COUNT,
            "preservedPrefixSha256": sha256(base_oracles),
        }, "POSTSTATE_ORACLE_BINDING", sha256(formal_oracles))

        formal_case_rows = json_lines(formal_cases, "formal cases")
        formal_oracle_rows = json_lines(formal_oracles, "formal oracles")
        self.check((len(formal_case_rows), len(formal_oracle_rows)) ==
                   (FORMAL_POST_COUNT, FORMAL_POST_COUNT),
                   "FORMAL_COUNTS", [len(formal_case_rows), len(formal_oracle_rows)])
        self.check(formal_case_rows[FORMAL_PREFIX_COUNT:] == assigned_case_rows,
                   "FORMAL_CASE_SUFFIX", "not candidate-identical")
        self.check(formal_oracle_rows[FORMAL_PREFIX_COUNT:] == assigned_oracle_rows,
                   "FORMAL_ORACLE_SUFFIX", "not candidate-identical")
        case_ids = [record["caseId"] for _, record in formal_case_rows]
        oracle_ids = [record["oracleId"] for _, record in formal_oracle_rows]
        self.check(len(case_ids) == len(set(case_ids)), "CASE_ID_UNIQUE", len(case_ids))
        self.check(len(oracle_ids) == len(set(oracle_ids)), "ORACLE_ID_UNIQUE", len(oracle_ids))

        oracle_by_id = {record["oracleId"]: record for _, record in formal_oracle_rows}
        referenced: set[str] = set()
        for _, record in assigned_case_rows:
            self.check(record.get("suite") == "CAPACITY_BOUNDARY" and
                       record.get("executionClass") == EXECUTION_CLASS,
                       "RENDERING_CASE_ROUTING", record.get("caseId"))
            requirement_ids = [edge["requirementId"] for edge in record["coverage"]]
            self.check(requirement_ids == sorted(requirement_ids, key=utf8_key),
                       "RENDERING_COVERAGE_ORDER", record["caseId"])
            for coverage in record["coverage"]:
                for evidence in coverage["evidence"]:
                    oracle_id = evidence["oracleId"]
                    referenced.add(oracle_id)
                    oracle = oracle_by_id.get(oracle_id)
                    self.check(oracle is not None, "RENDERING_ORACLE_REFERENCE", oracle_id)
                    if oracle is not None:
                        assertion_ids = {
                            assertion["assertionId"] for assertion in oracle["assertions"]
                        }
                        self.check(set(evidence["assertionIds"]).issubset(assertion_ids),
                                   "RENDERING_ASSERTION_REFERENCE",
                                   f"{record['caseId']}:{oracle_id}")
        self.check(referenced == assigned_oracle_ids,
                   "RENDERING_NO_ORPHAN_ORACLE", sorted(referenced))

        profile = load_json_bytes(
            self.read(f"{SPEC_ROOT}/conformance-probe-profile-v1.json"),
            "probe profile",
        )
        probes = {probe["probeId"]: probe for probe in profile["probes"]}
        for _, oracle in assigned_oracle_rows:
            self.check(oracle.get("probeProfile") == "renderweave-conformance-probes/1.0",
                       "RENDERING_ORACLE_PROFILE", oracle.get("oracleId"))
            for index, assertion in enumerate(oracle["assertions"], 1):
                self.check(assertion.get("assertionId") == f"A{index:03d}",
                           "RENDERING_ASSERTION_ID",
                           f"{oracle['oracleId']}:{assertion.get('assertionId')}")
                probe = probes.get(assertion.get("probeId"))
                self.check(probe is not None and
                           EXECUTION_CLASS in probe.get("executionClasses", []) and
                           assertion.get("operator") in probe.get("allowedOperators", []),
                           "RENDERING_ASSERTION_PROBE",
                           f"{oracle['oracleId']}:{assertion.get('probeId')}")

        execution_catalog = load_json_bytes(
            self.read(f"{SPEC_ROOT}/conformance-execution-classes-v1.json"),
            "execution catalog",
        )
        rendering_class = next(
            item for item in execution_catalog["classes"]
            if item["executionClass"] == EXECUTION_CLASS
        )
        predecessor_class = next(
            item for item in execution_catalog["classes"]
            if item["executionClass"] == PREDECESSOR_CLASS
        )
        self.check(rendering_class.get("status") == "EXECUTABLE_A2_REPLAYED" and
                   rendering_class.get("caseRecordCount") == ASSIGNED_COUNT and
                   rendering_class.get("oracleRecordCount") == ASSIGNED_COUNT and
                   rendering_class.get("executable") is True,
                   "RENDERING_CLASS_EXECUTABLE", rendering_class.get("status"))
        self.check(predecessor_class.get("status") == "EXECUTABLE_A2_REPLAYED" and
                   predecessor_class.get("caseRecordCount") == 195 and
                   predecessor_class.get("oracleRecordCount") == 195 and
                   predecessor_class.get("executable") is True,
                   "PREDECESSOR_CLASS_EXECUTABLE", predecessor_class.get("status"))
        bootstrap = load_json_bytes(
            self.read(f"{SPEC_ROOT}/conformance-bootstrap-order-v1.json"),
            "bootstrap",
        )
        step = next(
            item for item in bootstrap["steps"]
            if item["executionClass"] == EXECUTION_CLASS
        )
        self.check(step.get("assignedCorpusStatus") == "ISSUED_156_CASES_156_ORACLES" and
                   step.get("executable") is True,
                   "RENDERING_BOOTSTRAP_EXECUTABLE", step.get("assignedCorpusStatus"))
        self.check(bootstrap.get("caseRegistryRecordCount") == FORMAL_POST_COUNT and
                   bootstrap.get("oracleRegistryRecordCount") == FORMAL_POST_COUNT and
                   bootstrap.get("currentPhase") == "CAPACITY_BOUNDARY",
                   "BOOTSTRAP_FORMAL_COUNTS",
                   [bootstrap.get("caseRegistryRecordCount"),
                    bootstrap.get("oracleRegistryRecordCount")])
        acceptance = load_json_bytes(
            self.read(f"{SPEC_ROOT}/acceptance-manifest-v1.json"),
            "acceptance",
        )
        rendering_acceptance = acceptance["conformanceRegistries"][
            "renderingPipelineFixtureBootstrap"
        ]
        self.check(rendering_acceptance.get("status") == "EXECUTABLE_A2_REPLAYED" and
                   rendering_acceptance.get("formalCapacityRecordCount") == ASSIGNED_COUNT and
                   rendering_acceptance.get("executable") is True,
                   "RENDERING_ACCEPTANCE_EXECUTABLE", rendering_acceptance.get("status"))
        counts = acceptance["counts"]
        self.check(counts.get("issuedCapacityBoundaryCases") == ISSUED_CAPACITY_COUNT and
                   counts.get("issuedCapacityBoundaryOracles") == ISSUED_CAPACITY_COUNT and
                   counts.get("executableContractBoundaryCases") == ISSUED_CAPACITY_COUNT,
                   "ACCEPTANCE_CAPACITY_COUNTS", counts)
        materialization = execution_catalog["capacityBoundaryMaterialization"]
        self.check(materialization.get("formalCapacityCaseCount") == ISSUED_CAPACITY_COUNT and
                   materialization.get("formalCapacityOracleCount") == ISSUED_CAPACITY_COUNT,
                   "CATALOG_CAPACITY_COUNTS", materialization)
        phase = next(
            item for item in bootstrap["recordIssuancePhases"]
            if item["phase"] == "CAPACITY_BOUNDARY"
        )
        self.check(phase.get("formalIssuedCaseCount") == ISSUED_CAPACITY_COUNT and
                   phase.get("formalIssuedOracleCount") == ISSUED_CAPACITY_COUNT,
                   "BOOTSTRAP_CAPACITY_COUNTS", phase)
        spec_target = load_json_bytes(
            self.read(f"{SPEC_ROOT}/spec-registry/target-manifest-v1.json"),
            "SPEC target",
        )
        spec_bindings = spec_target["registryBindings"]
        issuance = spec_bindings["appendOnlyIssuance"]
        self.check(spec_target.get("status") == "ISSUED_APPEND_ONLY_EXACT_TARGET" and
                   spec_bindings.get("formalStatus") == "ISSUED_APPEND_ONLY_PREFIX" and
                   spec_bindings["formalCases"].get("expectedSha256") ==
                   target["poststate"]["formalCases"]["sha256"] and
                   spec_bindings["formalCases"].get("observedSha256") ==
                   target["poststate"]["formalCases"]["sha256"] and
                   spec_bindings["formalOracles"].get("expectedSha256") ==
                   target["poststate"]["formalOracles"]["sha256"] and
                   spec_bindings["formalOracles"].get("observedSha256") ==
                   target["poststate"]["formalOracles"]["sha256"] and
                   issuance.get("appendedExecutionClass") == EXECUTION_CLASS and
                   issuance.get("appendedCaseCount") == ASSIGNED_COUNT and
                   issuance.get("appendedOracleCount") == ASSIGNED_COUNT and
                   issuance.get("assignedCorpusDigest") ==
                   target["assignedCorpus"]["assignedCorpusDigest"] and
                   issuance.get("preservedCasePrefixSha256") ==
                   target["poststate"]["formalCases"]["preservedPrefixSha256"] and
                   issuance.get("preservedOraclePrefixSha256") ==
                   target["poststate"]["formalOracles"]["preservedPrefixSha256"],
                   "SPEC_TARGET_ISSUANCE", spec_target.get("implementationRevision"))
        expected_target_path = self.target_path.removeprefix(f"{SPEC_ROOT}/")
        expected_predecessor_path = target["prestate"]["previousCapacityIssuance"]["path"].removeprefix(
            f"{SPEC_ROOT}/"
        )
        self.check(issuance.get("target", {}).get("path") == expected_target_path and
                   issuance.get("target", {}).get("sha256") == sha256(target_bytes) and
                   issuance.get("target", {}).get("byteLength") == len(target_bytes) and
                   issuance.get("predecessorIssuance", {}).get("target", {}).get("path") ==
                   expected_predecessor_path and
                   issuance.get("predecessorIssuance", {}).get("target", {}).get("sha256") ==
                   target["prestate"]["previousCapacityIssuance"]["sha256"] and
                   issuance.get("predecessorIssuance", {}).get("target", {}).get("byteLength") ==
                   target["prestate"]["previousCapacityIssuance"]["byteLength"] and
                   issuance.get("predecessorIssuance", {}).get("appendedExecutionClass") ==
                   PREDECESSOR_CLASS,
                   "SPEC_TARGET_ISSUANCE_CHAIN", issuance.get("target"))
        return self.report(target_bytes, target, formal_case_rows, formal_oracle_rows)

    def report(
        self,
        target_bytes: bytes,
        target: dict[str, Any],
        cases: list[tuple[bytes, dict[str, Any]]],
        oracles: list[tuple[bytes, dict[str, Any]]],
    ) -> dict[str, Any]:
        complete = len(cases) == FORMAL_POST_COUNT and len(oracles) == FORMAL_POST_COUNT
        return {
            "reportVersion": "renderweave-rendering-pipeline-postissuance-independent/1",
            "engine": "python-independent-registry-replayer",
            "runtime": f"Python {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
            "status": "PASS" if not self.failures else "FAIL",
            "checkCount": self.check_count,
            "failureCount": len(self.failures),
            "failures": self.failures,
            "targetManifest": binding(self.target_path, target_bytes),
            "implementationRevision": target.get("implementationRevision"),
            "executionClass": EXECUTION_CLASS,
            "formalCaseCount": len(cases),
            "formalOracleCount": len(oracles),
            "issuedRenderingPipelineCaseCount": ASSIGNED_COUNT if complete else 0,
            "issuedRenderingPipelineOracleCount": ASSIGNED_COUNT if complete else 0,
            "issuedCapacityCaseCount": ISSUED_CAPACITY_COUNT if complete else 0,
            "issuedCapacityOracleCount": ISSUED_CAPACITY_COUNT if complete else 0,
            "assignedCorpusDigest": target.get("assignedCorpus", {}).get("assignedCorpusDigest"),
            "boundary": {
                "productMutationPerformed": False,
                "externalNetworkAllowed": False,
                "rendererReady": False,
                "ticket19Closed": False,
            },
        }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--target", default=DEFAULT_TARGET)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    replay = Replay(args.repo.resolve(), args.target)
    try:
        report = replay.replay()
    except Exception as error:  # fail closed with one bounded diagnostic
        replay.failures.append({"code": "UNEXPECTED_REPLAY_FAILURE", "detail": str(error)})
        report = replay.report(b"", {}, [], [])
    payload = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(payload)
    sys.stdout.buffer.write(
        json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
    )
    raise SystemExit(0 if report["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()
