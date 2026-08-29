#!/usr/bin/env python3
"""Independently verify Rendering Pipeline preissuance product replay closure."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
from pathlib import Path
from typing import Any


EXECUTION_CLASS = "EXEC::RENDERING_PIPELINE::1.0"
TARGET_ID = "RENDERING_PIPELINE_TARGET::JAVA_RUST_RESOURCE_FREE_ROOT::1.0"
TARGET_PATH = ".scratch/renderweave-template-v1/rendering-pipeline/execution-class-target-v1.json"
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
SHA256 = re.compile(r"sha256:[0-9a-f]{64}\Z")
REVISION = re.compile(r"[0-9a-f]{40}\Z")
FORBIDDEN_FIXTURE_MEMBERS = {
    "expectedTerminal", "expectedAssertions", "plannedAssertions", "plannedOracleId",
    "requirementIds", "resolvedCode", "resolvedKind", "latest", "default", "script",
}
PROBE_FIELDS = {
    "operation.accepted": "accepted",
    "operation.terminalCode": "terminalCode",
    "operation.terminalStage": "terminalStage",
    "capacity.limitId": "limitId",
    "capacity.observedValue": "observedValue",
    "capacity.reservationReached": "reservationReached",
    "capacity.zeroBoundary": "zeroBoundary",
    "operation.downstreamEffects": "downstreamEffects",
}


class VerificationFailure(RuntimeError):
    pass


def require(condition: bool, code: str, detail: Any) -> None:
    if not condition:
        raise VerificationFailure(f"{code}: {detail}")


def duplicate_safe_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        require(key not in value, "JSON_DUPLICATE_MEMBER", key)
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


def read_json(path: Path, *, strict_lf: bool = True) -> tuple[bytes, dict[str, Any]]:
    data = path.read_bytes()
    require(data.endswith(b"\n"), "JSON_TERMINATOR", path)
    if strict_lf:
        require(b"\r" not in data, "JSON_TEXT_FORMAT", path)
    return data, decode_json(data, path)


def json_lines(data: bytes, location: object) -> list[tuple[bytes, dict[str, Any]]]:
    require(data.endswith(b"\n"), "JSONL_TERMINATOR", location)
    records = []
    for index, line in enumerate(data.splitlines(), 1):
        if line:
            records.append((line + b"\n", decode_json(line, f"{location}:{index}")))
    return records


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def domain_digest(domain: bytes, data: bytes) -> str:
    return "sha256:" + hashlib.sha256(domain + data).hexdigest()


def binding(path: str, data: bytes) -> dict[str, object]:
    return {"path": path, "sha256": sha256(data), "byteLength": len(data)}


def git_blob(repo: Path, revision: str, path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def require_binding(
    repo: Path, revision: str, value: dict[str, Any], code: str
) -> bytes:
    require(set(value) == {"path", "sha256", "byteLength"}, code, value)
    path = value["path"]
    require(isinstance(path, str) and path and "\\" not in path, code, path)
    data = git_blob(repo, revision, path)
    require(value == binding(path, data), code, path)
    return data


def assigned_records(
    repo: Path, revision: str
) -> tuple[list[tuple[bytes, dict[str, Any]]], dict[str, dict[str, Any]], dict[str, Any]]:
    case_source = git_blob(repo, revision, CANDIDATE_CASES)
    oracle_source = git_blob(repo, revision, CANDIDATE_ORACLES)
    cases = [
        item for item in json_lines(case_source, CANDIDATE_CASES)
        if item[1].get("executionClass") == EXECUTION_CLASS
    ]
    oracle_ids = {
        evidence["oracleId"]
        for _, record in cases
        for coverage in record["coverage"]
        for evidence in coverage["evidence"]
    }
    oracles = {
        record["oracleId"]: record
        for _, record in json_lines(oracle_source, CANDIDATE_ORACLES)
        if record.get("oracleId") in oracle_ids
    }
    require((len(cases), len(oracles), len(oracle_ids)) == (156, 156, 156),
            "ASSIGNED_COUNTS", (len(cases), len(oracles), len(oracle_ids)))
    case_bytes = b"".join(raw for raw, _ in cases)
    oracle_bytes = b"".join(
        raw for raw, record in json_lines(oracle_source, CANDIDATE_ORACLES)
        if record.get("oracleId") in oracle_ids
    )
    assigned = {
        "sourceCases": binding(CANDIDATE_CASES, case_source),
        "sourceOracles": binding(CANDIDATE_ORACLES, oracle_source),
        "assignedCaseCount": 156,
        "assignedOracleCount": 156,
        "assignedCasesSha256": sha256(case_bytes),
        "assignedOraclesSha256": sha256(oracle_bytes),
        "assignedCorpusDigest": domain_digest(
            b"renderweave-rendering-pipeline-assigned-corpus/1\0",
            case_bytes + b"\0" + oracle_bytes,
        ),
    }
    return cases, oracles, assigned


def formal_boundary(repo: Path, revision: str) -> dict[str, Any]:
    case_data = git_blob(repo, revision, FORMAL_CASES)
    oracle_data = git_blob(repo, revision, FORMAL_ORACLES)
    cases = json_lines(case_data, FORMAL_CASES)
    oracles = json_lines(oracle_data, FORMAL_ORACLES)
    require((len(cases), len(oracles)) == (253, 253),
            "FORMAL_COUNTS", (len(cases), len(oracles)))
    assigned_cases, assigned_oracles, _ = assigned_records(repo, revision)
    assigned_oracle_ids = set(assigned_oracles)
    require(not any(record.get("executionClass") == EXECUTION_CLASS for _, record in cases),
            "FORMAL_RENDERING_CASE_PRESENT", None)
    require(not any(record.get("oracleId") in assigned_oracle_ids for _, record in oracles),
            "FORMAL_RENDERING_ORACLE_PRESENT", None)
    prefix = {
        "EXEC::SPEC_REGISTRY::1.0": 46,
        "EXEC::DOMAIN_SERVICES::1.0": 12,
        "EXEC::DESIGN_INPUT_EXPRESSION::1.0": 195,
    }
    actual = {
        name: sum(record.get("executionClass") == name for _, record in cases)
        for name in prefix
    }
    require(actual == prefix, "FORMAL_PREFIX", actual)
    require(len(assigned_cases) == 156, "ASSIGNED_CASE_COUNT", len(assigned_cases))
    return {
        "cases": {**binding(FORMAL_CASES, case_data), "recordCount": 253},
        "oracles": {**binding(FORMAL_ORACLES, oracle_data), "recordCount": 253},
        "issuedRenderingPipelineCaseCount": 0,
        "issuedRenderingPipelineOracleCount": 0,
        "appendPerformed": False,
    }


def validate_target(repo: Path, path: Path) -> tuple[bytes, dict[str, Any]]:
    data, target = read_json(path)
    require(set(target) == {
        "artifactVersion", "targetId", "status", "implementationRevision",
        "executionClass", "targetKind", "requiredExecutorRoles", "productWiring",
        "artifacts", "javaEntrypoints", "rustEntrypoint", "javaProductSeams",
        "rustProductSeams", "assignedCorpus", "runtimeTargets",
        "formalRegistryBoundary", "boundary",
    }, "TARGET_MEMBERS", sorted(target))
    require(target["artifactVersion"] ==
            "renderweave-rendering-pipeline-execution-class-target/1.0",
            "TARGET_VERSION", target["artifactVersion"])
    require(target["targetId"] == TARGET_ID and target["executionClass"] == EXECUTION_CLASS,
            "TARGET_IDENTITY", target)
    revision = target["implementationRevision"]
    require(isinstance(revision, str) and REVISION.fullmatch(revision) is not None,
            "TARGET_REVISION", revision)
    resolved = subprocess.run(
        ["git", "rev-parse", "--verify", f"{revision}^{{commit}}"],
        cwd=repo, check=True, stdout=subprocess.PIPE, text=True,
    ).stdout.strip()
    require(resolved == revision, "TARGET_REVISION_RESOLUTION", resolved)
    require(target["status"] == "FROZEN_PREISSUANCE_EXACT_TARGET",
            "TARGET_STATUS", target["status"])
    require(target["requiredExecutorRoles"] == [
        "java-evaluator-and-sealer", "rust-render-document-parser-and-engine"
    ], "TARGET_ROLES", target["requiredExecutorRoles"])
    require(target["productWiring"] == {
        "axisCount": 52, "wiredAxisCount": 52, "remainingAxisCount": 0,
        "javaTemplateClosureAxisCount": 5, "javaRenderingAxisCount": 47,
        "resourceFreeBaselineCount": 1,
    }, "TARGET_WIRING", target["productWiring"])
    roles = [item["role"] for item in target["artifacts"]]
    require(len(roles) == len(set(roles)) >= 16, "TARGET_ARTIFACT_ROLES", roles)
    for item in target["artifacts"]:
        require(set(item) == {"role", "path", "sha256", "byteLength"},
                "TARGET_ARTIFACT_MEMBERS", item)
        require_binding(repo, revision, {key: item[key] for key in (
            "path", "sha256", "byteLength")}, "TARGET_ARTIFACT_BINDING")
    for group in (target["javaEntrypoints"], target["javaProductSeams"],
                  target["rustProductSeams"]):
        require(isinstance(group, list) and group, "TARGET_BINDING_GROUP", group)
        for item in group:
            require_binding(repo, revision, item, "TARGET_PRODUCT_BINDING")
    require_binding(repo, revision, target["rustEntrypoint"], "TARGET_RUST_ENTRYPOINT")
    _, _, assigned = assigned_records(repo, revision)
    require(target["assignedCorpus"] == assigned, "TARGET_ASSIGNED_CORPUS", target["assignedCorpus"])
    require(target["formalRegistryBoundary"] == formal_boundary(repo, revision),
            "TARGET_FORMAL_BOUNDARY", target["formalRegistryBoundary"])
    require(target["runtimeTargets"] == {
        "java": "21", "rust": "1.89", "independentClosureVerifier": "Python 3.13"
    }, "TARGET_RUNTIMES", target["runtimeTargets"])
    require(target["boundary"] == {
        "productApiSurfaceCreated": False,
        "rendererKernelReplayRequired": True,
        "rendererDaemonOrDeploymentInvoked": False,
        "rendererProfileRegistered": False,
        "networkAndExternalProviderAttemptsAllowed": False,
        "formalRecordsIssued": False,
        "preissuanceReplayRequired": True,
        "recordIssuanceAllowed": False,
        "executionClassExecutable": False,
    }, "TARGET_BOUNDARY", target["boundary"])
    return data, target


def validate_executor(
    repo: Path,
    path: Path,
    role: str,
    target_bytes: bytes,
    target: dict[str, Any],
) -> bytes:
    data, manifest = read_json(path)
    expected_common = {
        "artifactVersion", "executorId", "role", "executionClass", "targetId",
        "targetManifest", "implementationRevision", "runtime", "productSeams", "command",
        "expectedValuesVisibleToExecutor", "networkReadsAllowed", "productMutationAllowed",
    }
    role_field = "entrypoints" if role == "java-evaluator-and-sealer" else "entrypoint"
    require(set(manifest) == expected_common | {role_field},
            "EXECUTOR_MEMBERS", (role, sorted(manifest)))
    require(manifest["artifactVersion"] ==
            "renderweave-rendering-pipeline-executor-manifest/1.0",
            "EXECUTOR_VERSION", role)
    require(manifest["role"] == role and manifest["executionClass"] == EXECUTION_CLASS,
            "EXECUTOR_ROLE", manifest)
    require(manifest["targetId"] == TARGET_ID,
            "EXECUTOR_TARGET_ID", manifest["targetId"])
    require(manifest["targetManifest"] == binding(TARGET_PATH, target_bytes),
            "EXECUTOR_TARGET_BINDING", role)
    require(manifest["implementationRevision"] == target["implementationRevision"],
            "EXECUTOR_REVISION", role)
    require(manifest["expectedValuesVisibleToExecutor"] is False
            and manifest["networkReadsAllowed"] is False
            and manifest["productMutationAllowed"] is False,
            "EXECUTOR_BOUNDARY", role)
    entries = manifest[role_field] if role_field == "entrypoints" else [manifest[role_field]]
    for item in entries:
        require_binding(repo, target["implementationRevision"], item, "EXECUTOR_ENTRYPOINT")
    for item in manifest["productSeams"]:
        require_binding(repo, target["implementationRevision"], item, "EXECUTOR_PRODUCT_SEAM")
    require(isinstance(manifest["command"], str) and len(manifest["command"]) > 80,
            "EXECUTOR_COMMAND", role)
    if role == "java-evaluator-and-sealer":
        require(manifest["runtime"] == "Java 21" and manifest["executorId"] ==
                "RENDERING_PIPELINE_EXECUTOR::JAVA_EVALUATOR_AND_SEALER::1.0",
                "EXECUTOR_JAVA_IDENTITY", manifest)
    else:
        require(manifest["runtime"] == "Rust 1.89" and manifest["executorId"] ==
                "RENDERING_PIPELINE_EXECUTOR::RUST_DOCUMENT_ENGINE::1.0",
                "EXECUTOR_RUST_IDENTITY", manifest)
    return data


def assert_fixture_no_expectations(value: Any, location: str) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            require(key not in FORBIDDEN_FIXTURE_MEMBERS,
                    "FIXTURE_EXPECTATION_LEAK", (location, key))
            assert_fixture_no_expectations(item, location)
    elif isinstance(value, list):
        for item in value:
            assert_fixture_no_expectations(item, location)


def validate_capacity_reports(
    repo: Path,
    target: dict[str, Any],
    target_bytes: bytes,
    template_path: Path,
    java_path: Path,
) -> tuple[bytes, bytes, dict[str, dict[str, Any]], int]:
    template_bytes, template = read_json(template_path, strict_lf=False)
    java_bytes, java = read_json(java_path, strict_lf=False)
    require((template.get("reportVersion"), template.get("engine"),
             template.get("axisCount"), template.get("caseCount"),
             template.get("acceptedCount"), template.get("rejectedCount")) == (
        "renderweave-rendering-pipeline-template-capacity/1",
        "java-template-closure-authority", 5, 15, 9, 6,
    ), "TEMPLATE_REPORT", template)
    require(template.get("executionClass") == EXECUTION_CLASS
            and template.get("networkAttempts") == 0
            and template.get("externalProviderAttempts") == 0,
            "TEMPLATE_REPORT_BOUNDARY", template)
    require((java.get("reportVersion"), java.get("engine"), java.get("assurance"),
             java.get("axisCount"), java.get("caseCount"),
             java.get("acceptedCount"), java.get("rejectedCount")) == (
        "renderweave-rendering-pipeline-java-executor/1",
        "java-evaluator-and-sealer", "A1_PRODUCT_EXECUTION", 47, 141, 86, 55,
    ), "JAVA_REPORT", java)
    require(java.get("executionClass") == EXECUTION_CLASS,
            "JAVA_REPORT_CLASS", java.get("executionClass"))
    require(java.get("targetManifest") == binding(TARGET_PATH, target_bytes)
            and java.get("implementationRevision") == target["implementationRevision"],
            "JAVA_REPORT_TARGET", java)
    require(java.get("boundary") == {
        "formalRecordsIssued": 0, "recordIssuanceAllowed": False,
        "executionClassExecutable": False, "rendererProfileRegistered": False,
    }, "JAVA_REPORT_BOUNDARY", java.get("boundary"))

    observations: dict[str, dict[str, Any]] = {}
    for report in (template, java):
        items = report.get("observations")
        require(isinstance(items, list) and len(items) == report["caseCount"],
                "OBSERVATION_COUNT", report.get("engine"))
        for item in items:
            require(set(item) == {"caseId", "fixturePath", "fixtureSha256", "observation"},
                    "OBSERVATION_MEMBERS", item)
            case_id = item["caseId"]
            require(case_id not in observations, "OBSERVATION_DUPLICATE", case_id)
            fixture_path = ".scratch/renderweave-template-v1/" + item["fixturePath"]
            fixture_bytes = git_blob(repo, target["implementationRevision"], fixture_path)
            require(item["fixtureSha256"] == sha256(fixture_bytes),
                    "OBSERVATION_FIXTURE_DIGEST", case_id)
            fixture = decode_json(fixture_bytes, fixture_path)
            assert_fixture_no_expectations(fixture, fixture_path)
            require(fixture["scenario"]["scenarioId"] == case_id,
                    "OBSERVATION_FIXTURE_CASE", case_id)
            observations[case_id] = item["observation"]
    require(len(observations) == 156, "OBSERVATION_TOTAL", len(observations))

    cases, oracles, _ = assigned_records(repo, target["implementationRevision"])
    checks = 0
    for _, case in cases:
        case_id = case["caseId"]
        require(case_id in observations, "OBSERVATION_MISSING", case_id)
        oracle_ids = {
            evidence["oracleId"]
            for coverage in case["coverage"]
            for evidence in coverage["evidence"]
        }
        require(len(oracle_ids) == 1, "CASE_ORACLE_IDENTITY", (case_id, oracle_ids))
        oracle = oracles[next(iter(oracle_ids))]
        actual = observations[case_id]
        require(set(actual) == {
            "accepted", "terminalCode", "terminalStage", "publicRenderStage",
            "limitId", "observedValue", "reservationReached", "zeroBoundary",
            "downstreamEffects",
        }, "CLOSED_OBSERVATION_MEMBERS", case_id)
        for assertion in oracle["assertions"]:
            probe = assertion["probeId"]
            require(probe in PROBE_FIELDS, "UNKNOWN_PROBE", probe)
            value = actual[PROBE_FIELDS[probe]]
            operator = assertion["operator"]
            if operator == "ABSENT":
                require(value is None, "ORACLE_ABSENT", (case_id, probe, value))
            elif operator in {"EQ", "SEQUENCE_EQ"}:
                expected = assertion["expected"]
                require(expected == {"kind": "LITERAL", "value": value},
                        "ORACLE_VALUE", (case_id, probe, value, expected))
            else:
                raise VerificationFailure(f"ORACLE_OPERATOR: {operator}")
            checks += 1
    require(checks == 1248, "ORACLE_CHECK_COUNT", checks)
    return template_bytes, java_bytes, observations, checks


def validate_command_and_baseline(
    command_path: Path,
    java_report: dict[str, Any],
) -> tuple[bytes, dict[str, Any]]:
    command_bytes = command_path.read_bytes()
    command = decode_json(command_bytes, command_path)
    require(set(command) == {
        "contractVersion", "requestId", "rendererProfile", "deadlineAt",
        "renderDocumentDigest", "document", "output", "diagnostics",
    }, "COMMAND_MEMBERS", sorted(command))
    require(command["contractVersion"] == "renderweave-render-command/1.0",
            "COMMAND_VERSION", command["contractVersion"])
    require(command["rendererProfile"] == "renderweave-renderer/1.0",
            "COMMAND_PROFILE", command["rendererProfile"])
    require(command["output"] == {
        "profile": "renderweave-output-png/1.0", "dpi": 96
    } and command["diagnostics"] == {"layoutTrace": False},
            "COMMAND_OUTPUT", command)
    canonical = json.dumps(command, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    require(canonical == command_bytes, "COMMAND_CANONICAL", command_path)
    document_bytes = json.dumps(
        command["document"], ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    document_digest = domain_digest(b"renderweave-render-document/1\0", document_bytes)
    require(command["renderDocumentDigest"] == document_digest,
            "COMMAND_DOCUMENT_DIGEST", command["renderDocumentDigest"])
    command_digest = domain_digest(b"renderweave-render-command/1\0", command_bytes)
    baseline = java_report["baseline"]
    require(baseline["commandArtifact"] == binding(command_path.name, command_bytes),
            "JAVA_COMMAND_BINDING", baseline["commandArtifact"])
    require(baseline["commandDigest"] == command_digest
            and baseline["renderDocumentDigest"] == document_digest,
            "JAVA_COMMAND_IDENTITY", baseline)
    require((baseline["evaluatorInvocations"], baseline["closureAuthorityInvocations"],
             baseline["assetResolveCount"], baseline["capabilityDemandCount"],
             baseline["networkAttempts"], baseline["externalProviderAttempts"],
             baseline["nodeCount"], baseline["resourceCount"]) ==
            (1, 1, 0, 0, 0, 0, 1, 0), "JAVA_BASELINE_COUNTS", baseline)
    require(SHA256.fullmatch(baseline["evaluationResultDigest"]) is not None,
            "JAVA_EVALUATION_DIGEST", baseline["evaluationResultDigest"])
    document = command["document"]
    require(document["dslVersion"] == "renderweave-render/1.0"
            and document["layoutProfile"] == "renderweave-layout/1.0"
            and document["resources"] == []
            and document["canvas"]["children"] == []
            and document["canvas"]["occurrenceId"] == "rwocc_0000000000000000",
            "JAVA_DOCUMENT_BASELINE", document)
    return command_bytes, command


def validate_rust_report(
    path: Path,
    image_path: Path,
    command_bytes: bytes,
    command: dict[str, Any],
) -> tuple[bytes, bytes]:
    report_bytes, report = read_json(path, strict_lf=False)
    image = image_path.read_bytes()
    require((report.get("reportVersion"), report.get("engine"), report.get("assurance"),
             report.get("executionClass")) == (
        "renderweave-rendering-pipeline-rust-executor/1",
        "rust-render-document-parser-and-engine",
        "A2_CROSS_LANGUAGE_PRODUCT_EXECUTION", EXECUTION_CLASS,
    ), "RUST_REPORT_IDENTITY", report)
    require(report["commandArtifact"] == {
        "path": Path(report["commandArtifact"]["path"]).name,
        "byteLength": len(command_bytes),
        "commandDigest": domain_digest(b"renderweave-render-command/1\0", command_bytes),
    }, "RUST_COMMAND_BINDING", report["commandArtifact"])
    require(report["renderDocumentDigest"] == command["renderDocumentDigest"],
            "RUST_DOCUMENT_DIGEST", report["renderDocumentDigest"])
    require(report["resourceCount"] == 0 and report["resourceFetchCount"] == 0,
            "RUST_RESOURCE_BOUNDARY", report)
    require(report["terminalFrameCount"] == 2
            and report["terminalFrameTypes"] == ["RESULT_METADATA", "RESULT_IMAGE"],
            "RUST_TERMINAL_FRAMES", report)
    require(image.startswith(b"\x89PNG\r\n\x1a\n") and image.endswith(b"IEND\xaeB`\x82"),
            "PNG_STRUCTURE", image_path)
    require(len(image) >= 33 and image[12:16] == b"IHDR",
            "PNG_IHDR", image[:33])
    width, height = struct.unpack(">II", image[16:24])
    image_binding = binding(image_path.name, image)
    require(report["imageArtifact"] == image_binding,
            "RUST_IMAGE_BINDING", report["imageArtifact"])
    metadata = report["metadata"]
    require(metadata["requestId"] == command["requestId"]
            and metadata["rendererProfile"] == command["rendererProfile"]
            and metadata["dslVersion"] == "renderweave-render/1.0"
            and metadata["layoutProfile"] == "renderweave-layout/1.0"
            and metadata["outputProfile"] == "renderweave-output-png/1.0"
            and metadata["format"] == "PNG"
            and metadata["mediaType"] == "image/png"
            and metadata["dpi"] == 96
            and metadata["widthPx"] == width
            and metadata["heightPx"] == height
            and metadata["byteLength"] == len(image)
            and metadata["contentSha256"] == sha256(image),
            "RUST_METADATA", metadata)
    require(report["boundary"] == {
        "networkAttempts": 0, "externalProviderAttempts": 0,
        "rendererProfileRegistered": False, "formalRecordsIssued": 0,
        "recordIssuanceAllowed": False, "executionClassExecutable": False,
    }, "RUST_BOUNDARY", report["boundary"])
    return report_bytes, image


def write_report(
    path: Path,
    target_bytes: bytes,
    target: dict[str, Any],
    inputs: list[tuple[str, bytes]],
    checks: int,
    command: dict[str, Any],
    rust_report: dict[str, Any],
) -> None:
    report = {
        "reportVersion": "renderweave-rendering-pipeline-execution-class-independent/1",
        "engine": "python-independent-closure-verifier",
        "assurance": "A2_PREISSUANCE_PRODUCT_REPLAY",
        "executionClass": EXECUTION_CLASS,
        "targetManifest": binding(TARGET_PATH, target_bytes),
        "implementationRevision": target["implementationRevision"],
        "assignedCorpusDigest": target["assignedCorpus"]["assignedCorpusDigest"],
        "executorRoleCount": 2,
        "capacityAxisCount": 52,
        "capacityCaseCount": 156,
        "capacityOracleCount": 156,
        "capacityAssertionCount": checks,
        "passedExecutorRoles": [
            "java-evaluator-and-sealer",
            "rust-render-document-parser-and-engine",
        ],
        "rootRenderTarget": {
            "requestId": command["requestId"],
            "renderDocumentDigest": command["renderDocumentDigest"],
            "commandDigest": domain_digest(
                b"renderweave-render-command/1\0",
                next(data for name, data in inputs if name.endswith("command.json")),
            ),
            "outputContentSha256": rust_report["metadata"]["contentSha256"],
            "widthPx": rust_report["metadata"]["widthPx"],
            "heightPx": rust_report["metadata"]["heightPx"],
        },
        "inputBindings": [binding(name, data) for name, data in inputs],
        "boundary": {
            "formalCaseCount": 253,
            "formalOracleCount": 253,
            "formalRecordsIssued": 0,
            "preissuanceReady": True,
            "recordAppendMayProceedInSeparateTicket": True,
            "executionClassExecutable": False,
            "rendererProfileRegistered": False,
            "rendererDaemonOrDeploymentInvoked": False,
            "networkAttempts": 0,
            "externalProviderAttempts": 0,
        },
    }
    data = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(data)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--java-executor", type=Path, required=True)
    parser.add_argument("--rust-executor", type=Path, required=True)
    parser.add_argument("--template-report", type=Path, required=True)
    parser.add_argument("--java-report", type=Path, required=True)
    parser.add_argument("--command", type=Path, required=True)
    parser.add_argument("--rust-report", type=Path, required=True)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    target_bytes, target = validate_target(repo, args.target.resolve())
    java_manifest = validate_executor(
        repo, args.java_executor.resolve(), "java-evaluator-and-sealer",
        target_bytes, target,
    )
    rust_manifest = validate_executor(
        repo, args.rust_executor.resolve(), "rust-render-document-parser-and-engine",
        target_bytes, target,
    )
    template_bytes, java_bytes, _, checks = validate_capacity_reports(
        repo, target, target_bytes, args.template_report.resolve(), args.java_report.resolve()
    )
    _, java_report = read_json(args.java_report.resolve(), strict_lf=False)
    command_bytes, command = validate_command_and_baseline(args.command.resolve(), java_report)
    rust_bytes, image_bytes = validate_rust_report(
        args.rust_report.resolve(), args.image.resolve(), command_bytes, command
    )
    _, rust_report = read_json(args.rust_report.resolve(), strict_lf=False)
    inputs = [
        ("rendering-pipeline/execution-class-target-v1.json", target_bytes),
        ("rendering-pipeline/java-evaluator-and-sealer-executor-manifest-v1.json", java_manifest),
        ("rendering-pipeline/rust-render-document-parser-and-engine-executor-manifest-v1.json", rust_manifest),
        (args.template_report.name, template_bytes),
        (args.java_report.name, java_bytes),
        (args.command.name, command_bytes),
        (args.rust_report.name, rust_bytes),
        (args.image.name, image_bytes),
    ]
    write_report(args.report.resolve(), target_bytes, target, inputs, checks, command, rust_report)
    print("RENDERING_PIPELINE execution class: 2/2 roles, 156/156 capacity, root PNG PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, VerificationFailure, ValueError) as error:
        print(f"Rendering Pipeline execution class failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
