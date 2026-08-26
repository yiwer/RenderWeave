#!/usr/bin/env python3
"""Independent Python replay of the issued Domain Services registry suffix."""

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
TARGET_VERSION = "renderweave-domain-services-capacity-issuance-target/1.0"
TARGET_ID = "DOMAIN_SERVICES_ISSUANCE::CAPACITY::1.0"
SPEC_ROOT = ".scratch/renderweave-template-v1"
CASE_PATH = f"{SPEC_ROOT}/conformance-cases-v1.jsonl"
ORACLE_PATH = f"{SPEC_ROOT}/conformance-oracles-v1.jsonl"
DEFAULT_TARGET = f"{SPEC_ROOT}/domain-services/capacity-record-issuance-target-v1.json"
REVISION = re.compile(r"[0-9a-f]{40}")


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

        predecessor_path = f"{SPEC_ROOT}/domain-services/execution-class-target-v1.json"
        predecessor = git_blob(self.repo, revision, predecessor_path)
        self.check(target.get("predecessorProductTarget") == binding(predecessor_path, predecessor),
                   "PREDECESSOR_TARGET_BINDING", predecessor_path)
        predecessor_value = load_json_bytes(predecessor, predecessor_path)
        self.check(predecessor_value.get("assignedCorpus", {}).get("assignedCorpusDigest") ==
                   target.get("assignedCorpus", {}).get("assignedCorpusDigest"),
                   "PREDECESSOR_ASSIGNED_DIGEST", predecessor_value.get("assignedCorpus"))

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
        self.check((len(assigned_case_rows), len(assigned_oracle_rows), len(assigned_oracle_ids)) == (12, 12, 12),
                   "ASSIGNED_COUNTS", [len(assigned_case_rows), len(assigned_oracle_rows), len(assigned_oracle_ids)])
        self.check(target["assignedCorpus"]["caseBytesSha256"] == sha256(assigned_case_bytes),
                   "ASSIGNED_CASE_BYTES", target["assignedCorpus"]["caseBytesSha256"])
        self.check(target["assignedCorpus"]["oracleBytesSha256"] == sha256(assigned_oracle_bytes),
                   "ASSIGNED_ORACLE_BYTES", target["assignedCorpus"]["oracleBytesSha256"])

        base_case_path = target["prestate"]["formalCases"]["path"]
        base_oracle_path = target["prestate"]["formalOracles"]["path"]
        base_cases = git_blob(self.repo, revision, base_case_path)
        base_oracles = git_blob(self.repo, revision, base_oracle_path)
        formal_cases = self.read(CASE_PATH)
        formal_oracles = self.read(ORACLE_PATH)
        self.check(target["prestate"]["formalCases"] == {**binding(base_case_path, base_cases), "recordCount": 46},
                   "PRESTATE_CASE_BINDING", base_case_path)
        self.check(target["prestate"]["formalOracles"] == {**binding(base_oracle_path, base_oracles), "recordCount": 46},
                   "PRESTATE_ORACLE_BINDING", base_oracle_path)
        self.check(formal_cases == base_cases + assigned_case_bytes, "FORMAL_CASE_EXACT_APPEND", sha256(formal_cases))
        self.check(formal_oracles == base_oracles + assigned_oracle_bytes, "FORMAL_ORACLE_EXACT_APPEND", sha256(formal_oracles))
        self.check(target["poststate"]["formalCases"]["sha256"] == sha256(formal_cases),
                   "POSTSTATE_CASE_BINDING", sha256(formal_cases))
        self.check(target["poststate"]["formalOracles"]["sha256"] == sha256(formal_oracles),
                   "POSTSTATE_ORACLE_BINDING", sha256(formal_oracles))

        formal_case_rows = json_lines(formal_cases, "formal cases")
        formal_oracle_rows = json_lines(formal_oracles, "formal oracles")
        self.check((len(formal_case_rows), len(formal_oracle_rows)) == (58, 58),
                   "FORMAL_COUNTS", [len(formal_case_rows), len(formal_oracle_rows)])
        self.check(formal_case_rows[46:] == assigned_case_rows, "FORMAL_CASE_SUFFIX", "not candidate-identical")
        self.check(formal_oracle_rows[46:] == assigned_oracle_rows, "FORMAL_ORACLE_SUFFIX", "not candidate-identical")
        case_ids = [record["caseId"] for _, record in formal_case_rows]
        oracle_ids = [record["oracleId"] for _, record in formal_oracle_rows]
        self.check(len(case_ids) == len(set(case_ids)), "CASE_ID_UNIQUE", len(case_ids))
        self.check(len(oracle_ids) == len(set(oracle_ids)), "ORACLE_ID_UNIQUE", len(oracle_ids))

        oracle_by_id = {record["oracleId"]: record for _, record in formal_oracle_rows}
        referenced: set[str] = set()
        for _, record in assigned_case_rows:
            self.check(record.get("suite") == "CAPACITY_BOUNDARY" and
                       record.get("executionClass") == EXECUTION_CLASS,
                       "DOMAIN_CASE_ROUTING", record.get("caseId"))
            requirement_ids = [edge["requirementId"] for edge in record["coverage"]]
            self.check(requirement_ids == sorted(requirement_ids, key=utf8_key),
                       "DOMAIN_COVERAGE_ORDER", record["caseId"])
            for coverage in record["coverage"]:
                for evidence in coverage["evidence"]:
                    oracle_id = evidence["oracleId"]
                    referenced.add(oracle_id)
                    oracle = oracle_by_id.get(oracle_id)
                    self.check(oracle is not None, "DOMAIN_ORACLE_REFERENCE", oracle_id)
                    if oracle is not None:
                        assertion_ids = {item["assertionId"] for item in oracle["assertions"]}
                        self.check(set(evidence["assertionIds"]).issubset(assertion_ids),
                                   "DOMAIN_ASSERTION_REFERENCE", f"{record['caseId']}:{oracle_id}")
        self.check(referenced == assigned_oracle_ids, "DOMAIN_NO_ORPHAN_ORACLE", sorted(referenced))

        profile = load_json_bytes(self.read(f"{SPEC_ROOT}/conformance-probe-profile-v1.json"), "probe profile")
        probes = {probe["probeId"]: probe for probe in profile["probes"]}
        for _, oracle in assigned_oracle_rows:
            self.check(oracle.get("probeProfile") == "renderweave-conformance-probes/1.0",
                       "DOMAIN_ORACLE_PROFILE", oracle.get("oracleId"))
            for index, assertion in enumerate(oracle["assertions"], 1):
                self.check(assertion.get("assertionId") == f"A{index:03d}",
                           "DOMAIN_ASSERTION_ID", f"{oracle['oracleId']}:{assertion.get('assertionId')}")
                probe = probes.get(assertion.get("probeId"))
                self.check(probe is not None and EXECUTION_CLASS in probe.get("executionClasses", []) and
                           assertion.get("operator") in probe.get("allowedOperators", []),
                           "DOMAIN_ASSERTION_PROBE", f"{oracle['oracleId']}:{assertion.get('probeId')}")

        execution_catalog = load_json_bytes(self.read(f"{SPEC_ROOT}/conformance-execution-classes-v1.json"),
                                             "execution catalog")
        domain_class = next(item for item in execution_catalog["classes"] if item["executionClass"] == EXECUTION_CLASS)
        self.check(domain_class.get("status") == "EXECUTABLE_A2_REPLAYED" and
                   domain_class.get("caseRecordCount") == 12 and
                   domain_class.get("oracleRecordCount") == 12 and
                   domain_class.get("executable") is True,
                   "DOMAIN_CLASS_EXECUTABLE", domain_class.get("status"))
        bootstrap = load_json_bytes(self.read(f"{SPEC_ROOT}/conformance-bootstrap-order-v1.json"), "bootstrap")
        step = next(item for item in bootstrap["steps"] if item["executionClass"] == EXECUTION_CLASS)
        self.check(step.get("assignedCorpusStatus") == "ISSUED_12_CASES_12_ORACLES" and
                   step.get("executable") is True,
                   "DOMAIN_BOOTSTRAP_EXECUTABLE", step.get("assignedCorpusStatus"))
        self.check(bootstrap.get("caseRegistryRecordCount") == 58 and
                   bootstrap.get("oracleRegistryRecordCount") == 58 and
                   bootstrap.get("currentPhase") == "CAPACITY_BOUNDARY",
                   "BOOTSTRAP_FORMAL_COUNTS", [bootstrap.get("caseRegistryRecordCount"), bootstrap.get("oracleRegistryRecordCount")])
        return self.report(target_bytes, target, formal_case_rows, formal_oracle_rows)

    def report(
        self,
        target_bytes: bytes,
        target: dict[str, Any],
        cases: list[tuple[bytes, dict[str, Any]]],
        oracles: list[tuple[bytes, dict[str, Any]]],
    ) -> dict[str, Any]:
        return {
            "reportVersion": "renderweave-domain-services-postissuance-independent/1",
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
            "issuedDomainCaseCount": 12 if len(cases) == 58 else 0,
            "issuedDomainOracleCount": 12 if len(oracles) == 58 else 0,
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
