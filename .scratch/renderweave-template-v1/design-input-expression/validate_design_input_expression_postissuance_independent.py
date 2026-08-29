#!/usr/bin/env python3
"""Independent Python replay of the issued Design/Input/Expression registry suffix."""

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
TARGET_VERSION = "renderweave-design-input-expression-capacity-issuance-target/1.0"
TARGET_ID = "DESIGN_INPUT_EXPRESSION_ISSUANCE::CAPACITY::1.0"
SPEC_ROOT = ".scratch/renderweave-template-v1"
CASE_PATH = f"{SPEC_ROOT}/conformance-cases-v1.jsonl"
ORACLE_PATH = f"{SPEC_ROOT}/conformance-oracles-v1.jsonl"
DEFAULT_TARGET = f"{SPEC_ROOT}/design-input-expression/capacity-record-issuance-target-v1.json"
REVISION = re.compile(r"[0-9a-f]{40}")
SPEC_COUNT = 46
DOMAIN_COUNT = 12
FORMAL_PREFIX_COUNT = SPEC_COUNT + DOMAIN_COUNT
ASSIGNED_COUNT = 195
FORMAL_POST_COUNT = FORMAL_PREFIX_COUNT + ASSIGNED_COUNT
ISSUED_CAPACITY_COUNT = DOMAIN_COUNT + ASSIGNED_COUNT


class DuplicateMember(ValueError):
    pass


def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in values:
        if key in result:
            raise DuplicateMember(key)
        result[key] = value
    return result


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def binding(path: str, data: bytes) -> dict[str, object]:
    return {"path": path, "sha256": sha256(data), "byteLength": len(data)}


def load_json_bytes(data: bytes, label: str) -> dict[str, Any]:
    if data.startswith(b"\xef\xbb\xbf") or b"\r" in data:
        raise ValueError(f"{label}: non-canonical transport")
    value = json.loads(data, object_pairs_hook=pairs)
    if not isinstance(value, dict):
        raise ValueError(f"{label}: object required")
    return value


def json_lines(data: bytes, label: str) -> list[tuple[bytes, dict[str, Any]]]:
    if data.startswith(b"\xef\xbb\xbf") or b"\r" in data or not data.endswith(b"\n"):
        raise ValueError(f"{label}: BOM-free LF-terminated JSONL required")
    result: list[tuple[bytes, dict[str, Any]]] = []
    for index, raw_line in enumerate(data.splitlines(), 1):
        if not raw_line:
            raise ValueError(f"{label}: blank line {index}")
        result.append((raw_line + b"\n", load_json_bytes(raw_line, f"{label}:{index}")))
    return result


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
        self.check(target.get("artifactVersion") == TARGET_VERSION, "TARGET_VERSION", target.get("artifactVersion"))
        self.check(target.get("targetId") == TARGET_ID, "TARGET_ID", target.get("targetId"))
        self.check(target.get("status") == "FROZEN_APPEND_ONLY_ISSUANCE_TARGET", "TARGET_STATUS", target.get("status"))
        revision = target.get("implementationRevision")
        self.check(isinstance(revision, str) and REVISION.fullmatch(revision or "") is not None,
                   "TARGET_REVISION", revision)
        self.check(target.get("executionClass") == EXECUTION_CLASS, "TARGET_CLASS", target.get("executionClass"))
        if not isinstance(revision, str) or REVISION.fullmatch(revision) is None:
            return self.report(target_bytes, target, [], [])

        artifact_paths: set[str] = set()
        for artifact in target.get("artifacts", []):
            path = artifact.get("path")
            self.check(isinstance(path, str) and path not in artifact_paths,
                       "TARGET_ARTIFACT_PATH", path)
            if not isinstance(path, str):
                continue
            artifact_paths.add(path)
            data = git_blob(self.repo, revision, path)
            self.check(artifact == binding(path, data), "TARGET_ARTIFACT_BINDING", path)

        predecessor_path = f"{SPEC_ROOT}/design-input-expression/execution-class-target-v1.json"
        predecessor = git_blob(self.repo, revision, predecessor_path)
        self.check(target.get("predecessorProductTarget") == binding(predecessor_path, predecessor),
                   "PREDECESSOR_TARGET_BINDING", predecessor_path)
        predecessor_value = load_json_bytes(predecessor, predecessor_path)
        self.check(predecessor_value.get("assignedCorpus", {}).get("assignedCorpusDigest") ==
                   target.get("assignedCorpus", {}).get("assignedCorpusDigest"),
                   "PREDECESSOR_ASSIGNED_DIGEST", predecessor_value.get("assignedCorpus"))
        self.check(predecessor_value.get("assignedCorpus", {}).get("assignedCaseCount") == ASSIGNED_COUNT and
                   predecessor_value.get("assignedCorpus", {}).get("assignedOracleCount") == ASSIGNED_COUNT,
                   "PREDECESSOR_ASSIGNED_COUNTS", predecessor_value.get("assignedCorpus"))

        for manifest in target.get("requiredExecutorManifests", []):
            manifest_path = manifest.get("path")
            manifest_bytes = git_blob(self.repo, revision, manifest_path)
            manifest_value = load_json_bytes(manifest_bytes, manifest_path)
            self.check(manifest == binding(manifest_path, manifest_bytes),
                       "EXECUTOR_MANIFEST_BINDING", manifest_path)
            self.check(manifest_value.get("executionClass") == EXECUTION_CLASS and
                       manifest_value.get("targetManifest", {}).get("sha256") ==
                       target.get("predecessorProductTarget", {}).get("sha256"),
                       "EXECUTOR_MANIFEST_TARGET", manifest_path)

        candidate_cases_path = target["assignedCorpus"]["sourceCases"]["path"]
        candidate_oracles_path = target["assignedCorpus"]["sourceOracles"]["path"]
        candidate_cases = git_blob(self.repo, revision, candidate_cases_path)
        candidate_oracles = git_blob(self.repo, revision, candidate_oracles_path)
        self.check(target["assignedCorpus"]["sourceCases"] == binding(candidate_cases_path, candidate_cases),
                   "CANDIDATE_CASE_BINDING", candidate_cases_path)
        self.check(target["assignedCorpus"]["sourceOracles"] == binding(candidate_oracles_path, candidate_oracles),
                   "CANDIDATE_ORACLE_BINDING", candidate_oracles_path)
        candidate_case_rows = json_lines(candidate_cases, "candidate cases")
        candidate_oracle_rows = json_lines(candidate_oracles, "candidate oracles")
        assigned_case_rows = [row for row in candidate_case_rows if row[1].get("executionClass") == EXECUTION_CLASS]
        assigned_oracle_ids = {
            evidence["oracleId"]
            for _, record in assigned_case_rows
            for coverage in record["coverage"]
            for evidence in coverage["evidence"]
        }
        assigned_oracle_rows = [row for row in candidate_oracle_rows if row[1].get("oracleId") in assigned_oracle_ids]
        assigned_case_bytes = b"".join(row[0] for row in assigned_case_rows)
        assigned_oracle_bytes = b"".join(row[0] for row in assigned_oracle_rows)
        self.check((len(assigned_case_rows), len(assigned_oracle_rows), len(assigned_oracle_ids)) ==
                   (ASSIGNED_COUNT, ASSIGNED_COUNT, ASSIGNED_COUNT),
                   "ASSIGNED_COUNTS", [len(assigned_case_rows), len(assigned_oracle_rows), len(assigned_oracle_ids)])
        self.check(target["assignedCorpus"]["caseBytesSha256"] == sha256(assigned_case_bytes),
                   "ASSIGNED_CASE_BYTES", target["assignedCorpus"]["caseBytesSha256"])
        self.check(target["assignedCorpus"]["oracleBytesSha256"] == sha256(assigned_oracle_bytes),
                   "ASSIGNED_ORACLE_BYTES", target["assignedCorpus"]["oracleBytesSha256"])
        self.check(target["assignedCorpus"].get("caseCount") == ASSIGNED_COUNT and
                   target["assignedCorpus"].get("oracleCount") == ASSIGNED_COUNT,
                   "TARGET_ASSIGNED_COUNTS", target["assignedCorpus"])
        self.check(target["assignedCorpus"].get("caseIds") ==
                   [record["caseId"] for _, record in assigned_case_rows],
                   "TARGET_ASSIGNED_CASE_IDS", len(target["assignedCorpus"].get("caseIds", [])))
        self.check(target["assignedCorpus"].get("oracleIds") ==
                   [record["oracleId"] for _, record in assigned_oracle_rows],
                   "TARGET_ASSIGNED_ORACLE_IDS", len(target["assignedCorpus"].get("oracleIds", [])))
        assigned_digest = sha256(
            b"renderweave-design-input-expression-assigned-corpus/1\0" +
            assigned_case_bytes + b"\0" + assigned_oracle_bytes
        )
        self.check(target["assignedCorpus"].get("assignedCorpusDigest") == assigned_digest,
                   "TARGET_ASSIGNED_CORPUS_DIGEST", assigned_digest)

        base_case_path = target["prestate"]["formalCases"]["path"]
        base_oracle_path = target["prestate"]["formalOracles"]["path"]
        base_cases = git_blob(self.repo, revision, base_case_path)
        base_oracles = git_blob(self.repo, revision, base_oracle_path)
        previous_path = target["prestate"]["previousCapacityIssuance"]["path"]
        previous_bytes = git_blob(self.repo, revision, previous_path)
        previous = load_json_bytes(previous_bytes, previous_path)
        self.check(target["prestate"]["previousCapacityIssuance"] == binding(previous_path, previous_bytes),
                   "PREVIOUS_ISSUANCE_BINDING", previous_path)
        self.check(previous.get("executionClass") == "EXEC::DOMAIN_SERVICES::1.0" and
                   previous.get("poststate", {}).get("formalCases", {}).get("sha256") == sha256(base_cases) and
                   previous.get("poststate", {}).get("formalOracles", {}).get("sha256") == sha256(base_oracles),
                   "PREVIOUS_ISSUANCE_POSTSTATE", previous.get("executionClass"))
        spec_cases = git_blob(self.repo, revision,
                              f"{SPEC_ROOT}/spec-registry/candidate/conformance-cases-v1.jsonl")
        spec_oracles = git_blob(self.repo, revision,
                                f"{SPEC_ROOT}/spec-registry/candidate/conformance-oracles-v1.jsonl")
        self.check(base_cases.startswith(spec_cases), "SPEC_CASE_PREFIX", sha256(spec_cases))
        self.check(base_oracles.startswith(spec_oracles), "SPEC_ORACLE_PREFIX", sha256(spec_oracles))
        self.check(sha256(base_cases[len(spec_cases):]) == previous["assignedCorpus"]["caseBytesSha256"],
                   "DOMAIN_CASE_SUFFIX", sha256(base_cases[len(spec_cases):]))
        self.check(sha256(base_oracles[len(spec_oracles):]) == previous["assignedCorpus"]["oracleBytesSha256"],
                   "DOMAIN_ORACLE_SUFFIX", sha256(base_oracles[len(spec_oracles):]))
        formal_cases = self.read(CASE_PATH)
        formal_oracles = self.read(ORACLE_PATH)
        self.check(target["prestate"]["formalCases"] ==
                   {**binding(base_case_path, base_cases), "recordCount": FORMAL_PREFIX_COUNT},
                   "PRESTATE_CASE_BINDING", base_case_path)
        self.check(target["prestate"]["formalOracles"] ==
                   {**binding(base_oracle_path, base_oracles), "recordCount": FORMAL_PREFIX_COUNT},
                   "PRESTATE_ORACLE_BINDING", base_oracle_path)
        self.check(formal_cases == base_cases + assigned_case_bytes, "FORMAL_CASE_EXACT_APPEND", sha256(formal_cases))
        self.check(formal_oracles == base_oracles + assigned_oracle_bytes, "FORMAL_ORACLE_EXACT_APPEND", sha256(formal_oracles))
        self.check(target["poststate"]["formalCases"] == {
            **binding(CASE_PATH, formal_cases),
            "recordCount": FORMAL_POST_COUNT,
            "preservedPrefixSha256": sha256(base_cases),
        },
                   "POSTSTATE_CASE_BINDING", sha256(formal_cases))
        self.check(target["poststate"]["formalOracles"] == {
            **binding(ORACLE_PATH, formal_oracles),
            "recordCount": FORMAL_POST_COUNT,
            "preservedPrefixSha256": sha256(base_oracles),
        },
                   "POSTSTATE_ORACLE_BINDING", sha256(formal_oracles))

        formal_case_rows = json_lines(formal_cases, "formal cases")
        formal_oracle_rows = json_lines(formal_oracles, "formal oracles")
        self.check((len(formal_case_rows), len(formal_oracle_rows)) == (FORMAL_POST_COUNT, FORMAL_POST_COUNT),
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
                       "DESIGN_CASE_ROUTING", record.get("caseId"))
            requirement_ids = [edge["requirementId"] for edge in record["coverage"]]
            self.check(requirement_ids == sorted(requirement_ids, key=utf8_key),
                       "DESIGN_COVERAGE_ORDER", record["caseId"])
            for coverage in record["coverage"]:
                for evidence in coverage["evidence"]:
                    oracle_id = evidence["oracleId"]
                    referenced.add(oracle_id)
                    oracle = oracle_by_id.get(oracle_id)
                    self.check(oracle is not None, "DESIGN_ORACLE_REFERENCE", oracle_id)
                    if oracle is not None:
                        assertion_ids = {item["assertionId"] for item in oracle["assertions"]}
                        self.check(set(evidence["assertionIds"]).issubset(assertion_ids),
                                   "DESIGN_ASSERTION_REFERENCE", f"{record['caseId']}:{oracle_id}")
        self.check(referenced == assigned_oracle_ids, "DESIGN_NO_ORPHAN_ORACLE", sorted(referenced))

        profile = load_json_bytes(self.read(f"{SPEC_ROOT}/conformance-probe-profile-v1.json"), "probe profile")
        probes = {probe["probeId"]: probe for probe in profile["probes"]}
        for _, oracle in assigned_oracle_rows:
            self.check(oracle.get("probeProfile") == "renderweave-conformance-probes/1.0",
                       "DESIGN_ORACLE_PROFILE", oracle.get("oracleId"))
            for index, assertion in enumerate(oracle["assertions"], 1):
                self.check(assertion.get("assertionId") == f"A{index:03d}",
                           "DESIGN_ASSERTION_ID", f"{oracle['oracleId']}:{assertion.get('assertionId')}")
                probe = probes.get(assertion.get("probeId"))
                self.check(probe is not None and EXECUTION_CLASS in probe.get("executionClasses", []) and
                           assertion.get("operator") in probe.get("allowedOperators", []),
                           "DESIGN_ASSERTION_PROBE", f"{oracle['oracleId']}:{assertion.get('probeId')}")

        execution_catalog = load_json_bytes(self.read(f"{SPEC_ROOT}/conformance-execution-classes-v1.json"),
                                             "execution catalog")
        design_class = next(item for item in execution_catalog["classes"] if item["executionClass"] == EXECUTION_CLASS)
        domain_class = next(item for item in execution_catalog["classes"]
                            if item["executionClass"] == "EXEC::DOMAIN_SERVICES::1.0")
        self.check(design_class.get("status") == "EXECUTABLE_A2_REPLAYED" and
                   design_class.get("caseRecordCount") == ASSIGNED_COUNT and
                   design_class.get("oracleRecordCount") == ASSIGNED_COUNT and
                   design_class.get("executable") is True,
                   "DESIGN_CLASS_EXECUTABLE", design_class.get("status"))
        self.check(domain_class.get("status") == "EXECUTABLE_A2_REPLAYED" and
                   domain_class.get("caseRecordCount") == DOMAIN_COUNT and
                   domain_class.get("oracleRecordCount") == DOMAIN_COUNT and
                   domain_class.get("executable") is True,
                   "DOMAIN_PREDECESSOR_EXECUTABLE", domain_class.get("status"))
        bootstrap = load_json_bytes(self.read(f"{SPEC_ROOT}/conformance-bootstrap-order-v1.json"), "bootstrap")
        step = next(item for item in bootstrap["steps"] if item["executionClass"] == EXECUTION_CLASS)
        self.check(step.get("assignedCorpusStatus") == "ISSUED_195_CASES_195_ORACLES" and
                   step.get("executable") is True,
                   "DESIGN_BOOTSTRAP_EXECUTABLE", step.get("assignedCorpusStatus"))
        self.check(bootstrap.get("caseRegistryRecordCount") == FORMAL_POST_COUNT and
                   bootstrap.get("oracleRegistryRecordCount") == FORMAL_POST_COUNT and
                   bootstrap.get("currentPhase") == "CAPACITY_BOUNDARY",
                   "BOOTSTRAP_FORMAL_COUNTS", [bootstrap.get("caseRegistryRecordCount"), bootstrap.get("oracleRegistryRecordCount")])
        acceptance = load_json_bytes(self.read(f"{SPEC_ROOT}/acceptance-manifest-v1.json"), "acceptance")
        design_acceptance = acceptance["conformanceRegistries"]["designInputExpressionFixtureBootstrap"]
        self.check(design_acceptance.get("status") == "EXECUTABLE_A2_REPLAYED" and
                   design_acceptance.get("formalCapacityRecordCount") == ASSIGNED_COUNT and
                   design_acceptance.get("executable") is True,
                   "DESIGN_ACCEPTANCE_EXECUTABLE", design_acceptance.get("status"))
        counts = acceptance["counts"]
        self.check(counts.get("issuedCapacityBoundaryCases") == ISSUED_CAPACITY_COUNT and
                   counts.get("issuedCapacityBoundaryOracles") == ISSUED_CAPACITY_COUNT and
                   counts.get("executableContractBoundaryCases") == ISSUED_CAPACITY_COUNT,
                   "ACCEPTANCE_CAPACITY_COUNTS", counts)
        materialization = execution_catalog["capacityBoundaryMaterialization"]
        self.check(materialization.get("formalCapacityCaseCount") == ISSUED_CAPACITY_COUNT and
                   materialization.get("formalCapacityOracleCount") == ISSUED_CAPACITY_COUNT,
                   "CATALOG_CAPACITY_COUNTS", materialization)
        phase = next(item for item in bootstrap["recordIssuancePhases"]
                     if item["phase"] == "CAPACITY_BOUNDARY")
        self.check(phase.get("formalIssuedCaseCount") == ISSUED_CAPACITY_COUNT and
                   phase.get("formalIssuedOracleCount") == ISSUED_CAPACITY_COUNT,
                   "BOOTSTRAP_CAPACITY_COUNTS", phase)
        spec_target = load_json_bytes(self.read(f"{SPEC_ROOT}/spec-registry/target-manifest-v1.json"), "SPEC target")
        issuance = spec_target["registryBindings"]["appendOnlyIssuance"]
        self.check(spec_target.get("implementationRevision") == "spec-registry-bootstrap/1.15" and
                   issuance.get("appendedExecutionClass") == EXECUTION_CLASS and
                   issuance.get("appendedCaseCount") == ASSIGNED_COUNT and
                   issuance.get("appendedOracleCount") == ASSIGNED_COUNT and
                   issuance.get("assignedCorpusDigest") == target["assignedCorpus"]["assignedCorpusDigest"],
                   "SPEC_TARGET_ISSUANCE", spec_target.get("implementationRevision"))
        expected_target_path = self.target_path.removeprefix(f"{SPEC_ROOT}/")
        self.check(issuance.get("target", {}).get("path") == expected_target_path and
                   issuance.get("target", {}).get("sha256") == sha256(target_bytes) and
                   issuance.get("target", {}).get("byteLength") == len(target_bytes) and
                   issuance.get("predecessorIssuance", {}).get("appendedExecutionClass") ==
                   "EXEC::DOMAIN_SERVICES::1.0",
                   "SPEC_TARGET_ISSUANCE_CHAIN", issuance.get("target"))
        return self.report(target_bytes, target, formal_case_rows, formal_oracle_rows)

    def report(
        self,
        target_bytes: bytes,
        target: dict[str, Any],
        cases: list[tuple[bytes, dict[str, Any]]],
        oracles: list[tuple[bytes, dict[str, Any]]],
    ) -> dict[str, Any]:
        return {
            "reportVersion": "renderweave-design-input-expression-postissuance-independent/1",
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
            "issuedDesignInputExpressionCaseCount": ASSIGNED_COUNT if len(cases) == FORMAL_POST_COUNT else 0,
            "issuedDesignInputExpressionOracleCount": ASSIGNED_COUNT if len(oracles) == FORMAL_POST_COUNT else 0,
            "issuedCapacityCaseCount": ISSUED_CAPACITY_COUNT if len(cases) == FORMAL_POST_COUNT else 0,
            "issuedCapacityOracleCount": ISSUED_CAPACITY_COUNT if len(oracles) == FORMAL_POST_COUNT else 0,
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
    except Exception as error:  # fail closed with a bounded diagnostic
        replay.failures.append({"code": "UNEXPECTED_REPLAY_FAILURE", "detail": str(error)})
        report = replay.report(b"", {}, [], [])
    payload = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(payload)
    sys.stdout.buffer.write(json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n")
    raise SystemExit(0 if report["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()
