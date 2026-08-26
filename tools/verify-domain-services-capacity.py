#!/usr/bin/env python3
"""Independently replay the frozen DOMAIN_SERVICES capacity observations.

The verifier intentionally shares no Java implementation or expected-value fixture fields with
the primary executor. It derives MAX_INCLUSIVE outcomes from the frozen coverage authority,
validates the closed scalar fixtures byte-for-byte against an exact product target, and then
compares those independently derived observations with the Java product-guard report.
"""

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
GUARD_CONTRACT = "renderweave-domain-asset-content-capacity-guard/1.0"
TARGET_VERSION = "renderweave-domain-services-capacity-product-target/1.0"
TARGET_ID = "DOMAIN_SERVICES_CAPACITY_TARGET::ASSET_CONTENT_GUARD::1.0"
TARGET_PATH = ".scratch/renderweave-template-v1/domain-services/product-execution-target-v1.json"
PRIMARY_VERSION = "renderweave-domain-services-capacity-primary/1"
INDEPENDENT_VERSION = "renderweave-domain-services-capacity-independent/1"
CANONICAL_INTEGER = re.compile(r"0|[1-9][0-9]*")
REVISION = re.compile(r"[0-9a-f]{40}")

AXIS_IDS = (
    "assetsAndFetch.acceptedImageBytesPerContent",
    "assetsAndFetch.acceptedImageEdgePixelsPerContent",
    "assetsAndFetch.acceptedImagePixelsPerContent",
    "assetsAndFetch.acceptedFontBytesPerContent",
)
VARIANTS = ("below", "at", "above")
FIXTURE_MEMBERS = {
    "fixtureVersion",
    "generatorProfile",
    "executionClass",
    "baseline",
    "scenario",
    "observationAdapter",
    "targetContract",
}
SCENARIO_MEMBERS = {
    "mode",
    "scenarioId",
    "operationId",
    "entrypoint",
    "guardContractId",
    "limitId",
    "observedValue",
    "valueEncoding",
    "comparator",
    "variant",
    "contractStage",
    "publicRenderStage",
    "reservationPoint",
    "zeroBoundary",
    "faultSchedule",
}
FORBIDDEN_MEMBERS = {
    "expectedTerminal",
    "expectedAssertions",
    "plannedAssertions",
    "plannedOracleId",
    "requirementIds",
    "resolvedCode",
    "resolvedKind",
    "latest",
    "default",
    "script",
}
OBSERVATION_MEMBERS = {
    "accepted",
    "terminalCode",
    "terminalStage",
    "limitId",
    "observedValue",
    "reservationReached",
    "zeroBoundary",
    "downstreamEffects",
}
BOUNDARY = {
    "mediaPayloadAllocated": False,
    "databaseUsed": False,
    "renderDocumentCount": 0,
    "renderOutputCount": 0,
    "formalRecordsIssued": 0,
}
DIRECT_CONSUMERS = (
    "cn.hbads.renderweave.asset.internal.CanonicalAssetAcceptanceAuthority",
    "cn.hbads.renderweave.asset.internal.PngAdmission",
    "cn.hbads.renderweave.asset.internal.JpegAdmission",
    "cn.hbads.renderweave.asset.internal.WebpAdmission",
)
BEHAVIORAL_TESTS = (
    "cn.hbads.renderweave.asset.internal.AssetAcceptanceKernelTest#enforcesRawByteBudgetsPerKind",
    "cn.hbads.renderweave.asset.internal.AssetAcceptanceKernelTest#enforcesImageDimensionLimitsBeforeDecode",
    "cn.hbads.renderweave.asset.internal.JpegAdmissionTest#rejectsOversizedFrameThroughTheSharedCapacityGuardBeforeDecode",
    "cn.hbads.renderweave.asset.internal.WebpAdmissionTest#rejectsOversizedCanvasThroughTheSharedCapacityGuardBeforeFrameDecode",
)
ARTIFACT_PATHS = {
    "productionGuard": "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/AssetContentCapacityGuard.java",
    "rawBytesConsumer": "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/CanonicalAssetAcceptanceAuthority.java",
    "pngConsumer": "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/PngAdmission.java",
    "jpegConsumer": "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/JpegAdmission.java",
    "webpConsumer": "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/WebpAdmission.java",
    "guardContractTest": "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/AssetContentCapacityGuardTest.java",
    "primaryExecutor": "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/DomainServicesCapacityConformanceTest.java",
    "architectureProof": "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/AssetModuleArchitectureTest.java",
    "assetKernelIntegrationProof": "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/AssetAcceptanceKernelTest.java",
    "jpegIntegrationProof": "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/JpegAdmissionTest.java",
    "webpIntegrationProof": "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/WebpAdmissionTest.java",
    "independentExecutor": "tools/verify-domain-services-capacity.py",
    "capacityCoverage": ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json",
    "fixtureContract": ".scratch/renderweave-template-v1/domain-services/fixture-contract-v1.json",
    "observationAdapter": ".scratch/renderweave-template-v1/domain-services/observation-adapter-v1.json",
}


class VerificationFailure(Exception):
    pass


def require(condition: bool, code: str, detail: object) -> None:
    if not condition:
        raise VerificationFailure(f"{code}: {detail}")


def reject_constant(value: str) -> None:
    raise VerificationFailure(f"JSON_NONFINITE_NUMBER: {value}")


def reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, "JSON_DUPLICATE_MEMBER", key)
        result[key] = value
    return result


def load_json(path: Path) -> tuple[bytes, Any]:
    data = path.read_bytes()
    require(not data.startswith(b"\xef\xbb\xbf"), "JSON_BOM_FORBIDDEN", path)
    try:
        text = data.decode("utf-8", "strict")
        value = json.loads(
            text,
            parse_constant=reject_constant,
            object_pairs_hook=reject_duplicate_pairs,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise VerificationFailure(f"JSON_PARSE_FAILED: {path}: {failure}") from failure
    return data, value


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def artifact(path: Path, relative: str) -> dict[str, Any]:
    data = path.read_bytes()
    return {"path": relative, "sha256": sha256(data), "byteLength": len(data)}


def committed_artifact(repo: Path, revision: str, relative: str) -> dict[str, Any]:
    result = subprocess.run(
        ["git", "show", f"{revision}:{relative}"],
        cwd=repo,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    require(result.returncode == 0, "TARGET_REVISION_ARTIFACT_MISSING", relative)
    return {"path": relative, "sha256": sha256(result.stdout), "byteLength": len(result.stdout)}


def require_exact_members(value: Any, members: set[str], code: str) -> None:
    require(isinstance(value, dict), code, "not an object")
    require(set(value) == members, code, f"members={sorted(value)}")


def require_no_forbidden_members(value: Any, location: str = "$") -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            require(key not in FORBIDDEN_MEMBERS, "FIXTURE_EXPECTATION_LEAK", location + "." + key)
            require_no_forbidden_members(nested, location + "." + key)
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            require_no_forbidden_members(nested, f"{location}[{index}]")


def safe_repo_path(repo: Path, relative: str) -> Path:
    require("\\" not in relative, "TARGET_PATH_NOT_CANONICAL", relative)
    candidate = (repo / relative).resolve()
    try:
        candidate.relative_to(repo)
    except ValueError as failure:
        raise VerificationFailure(f"TARGET_PATH_ESCAPE: {relative}") from failure
    require(candidate.is_file(), "TARGET_ARTIFACT_MISSING", relative)
    return candidate


def validate_target(repo: Path, target_path: Path) -> tuple[dict[str, Any], bytes]:
    target_bytes, target = load_json(target_path)
    require_exact_members(
        target,
        {
            "artifactVersion",
            "targetId",
            "status",
            "implementationRevision",
            "executionClass",
            "guardContractId",
            "comparator",
            "artifacts",
            "fixtures",
            "integrationProof",
            "boundary",
        },
        "TARGET_MEMBERS",
    )
    require(target["artifactVersion"] == TARGET_VERSION, "TARGET_VERSION", target["artifactVersion"])
    require(target["targetId"] == TARGET_ID, "TARGET_ID", target["targetId"])
    require(target["status"] == "ISSUED_EXACT_PRODUCT_TARGET", "TARGET_STATUS", target["status"])
    require(REVISION.fullmatch(target["implementationRevision"]) is not None,
            "TARGET_IMPLEMENTATION_REVISION", target["implementationRevision"])
    revision = target["implementationRevision"]
    revision_check = subprocess.run(
        ["git", "rev-parse", "--verify", f"{revision}^{{commit}}"],
        cwd=repo,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    require(revision_check.returncode == 0, "TARGET_IMPLEMENTATION_REVISION_MISSING", revision)
    require(target["executionClass"] == EXECUTION_CLASS, "TARGET_EXECUTION_CLASS", target["executionClass"])
    require(target["guardContractId"] == GUARD_CONTRACT, "TARGET_GUARD_CONTRACT", target["guardContractId"])
    require(target["comparator"] == "MAX_INCLUSIVE", "TARGET_COMPARATOR", target["comparator"])

    artifacts = target["artifacts"]
    require(isinstance(artifacts, list), "TARGET_ARTIFACTS", type(artifacts).__name__)
    require(len(artifacts) == len(ARTIFACT_PATHS), "TARGET_ARTIFACT_COUNT", len(artifacts))
    by_role: dict[str, dict[str, Any]] = {}
    for entry in artifacts:
        require_exact_members(entry, {"role", "path", "sha256", "byteLength"}, "TARGET_ARTIFACT_MEMBERS")
        role = entry["role"]
        require(role not in by_role, "TARGET_ARTIFACT_ROLE_DUPLICATE", role)
        by_role[role] = entry
    require(set(by_role) == set(ARTIFACT_PATHS), "TARGET_ARTIFACT_ROLES", sorted(by_role))
    for role, relative in ARTIFACT_PATHS.items():
        entry = by_role[role]
        require(entry["path"] == relative, "TARGET_ARTIFACT_PATH", f"{role}:{entry['path']}")
        safe_repo_path(repo, relative)
        observed = committed_artifact(repo, revision, relative)
        require(entry == {"role": role, **observed}, "TARGET_ARTIFACT_DIGEST", role)
        clean = subprocess.run(
            ["git", "diff", "--quiet", revision, "--", relative],
            cwd=repo,
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        require(clean.returncode == 0, "TARGET_ARTIFACT_WORKTREE_DRIFT", relative)

    proof = target["integrationProof"]
    require_exact_members(
        proof,
        {
            "directConsumers",
            "behavioralTests",
            "authoritativeAdmissionPathProvenSeparately",
            "fullUploadPathProvenByScalarProbe",
        },
        "TARGET_INTEGRATION_PROOF_MEMBERS",
    )
    require(proof["directConsumers"] == list(DIRECT_CONSUMERS),
            "TARGET_DIRECT_CONSUMERS", proof["directConsumers"])
    require(proof["behavioralTests"] == list(BEHAVIORAL_TESTS),
            "TARGET_BEHAVIORAL_TESTS", proof["behavioralTests"])
    require(proof["authoritativeAdmissionPathProvenSeparately"] is True,
            "TARGET_ADMISSION_PROOF", proof)
    require(proof["fullUploadPathProvenByScalarProbe"] is False,
            "TARGET_SCALAR_BOUNDARY", proof)
    require(target["boundary"] == {
        "productApiSurfaceCreated": False,
        "mediaPayloadRequiredForScalarProbe": False,
        "databaseRequiredForScalarProbe": False,
        "nativeRendererInvoked": False,
        "formalRecordsIssued": False,
        "recordIssuanceAllowed": False,
    }, "TARGET_BOUNDARY", target["boundary"])

    main_sources = list((repo / "renderweave-asset/src/main/java").rglob("*.java"))
    limit_literals = ("67_108_864L", "20_000L", "100_000_000L", "33_554_432L")
    for literal in limit_literals:
        owners = [path for path in main_sources if literal in path.read_text(encoding="utf-8")]
        require(owners == [safe_repo_path(repo, ARTIFACT_PATHS["productionGuard"])],
                "DUPLICATE_GUARD_LIMIT", f"{literal}:{owners}")
    for role in ("rawBytesConsumer", "pngConsumer", "jpegConsumer", "webpConsumer"):
        source = safe_repo_path(repo, ARTIFACT_PATHS[role]).read_text(encoding="utf-8")
        require("AssetContentCapacityGuard" in source, "PRODUCT_GUARD_NOT_WIRED", role)

    return target, target_bytes


def validate_coverage(coverage_path: Path) -> tuple[dict[str, dict[str, Any]], bytes]:
    coverage_bytes, coverage = load_json(coverage_path)
    require(coverage.get("artifactVersion") == "renderweave-capacity-coverage/1.0",
            "COVERAGE_VERSION", coverage.get("artifactVersion"))
    axes = [axis for axis in coverage.get("axes", []) if axis.get("executionClass") == EXECUTION_CLASS]
    require(len(axes) == 4, "DOMAIN_AXIS_COUNT", len(axes))
    by_id = {axis.get("limitId"): axis for axis in axes}
    require(tuple(by_id) == AXIS_IDS, "DOMAIN_AXIS_ORDER", tuple(by_id))
    for axis_id, axis in by_id.items():
        require(axis.get("valueEncoding") == "CANONICAL_INTEGER", "AXIS_ENCODING", axis_id)
        require(axis.get("comparator") == "MAX_INCLUSIVE", "AXIS_COMPARATOR", axis_id)
        limit_text = axis.get("limitValue")
        require(isinstance(limit_text, str) and CANONICAL_INTEGER.fullmatch(limit_text) is not None,
                "AXIS_LIMIT", axis_id)
        oracle = axis.get("resolvedOracle", {})
        require(oracle.get("code") == "ASSET_CONTENT_LIMIT_EXCEEDED", "AXIS_CODE", axis_id)
        require(oracle.get("contractStage") == "ASSET_CONTENT_ADMISSION", "AXIS_STAGE", axis_id)
        require(oracle.get("publicRenderStage") == "ASSET_ADMISSION", "AXIS_PUBLIC_STAGE", axis_id)
        require(oracle.get("zeroBoundary") == "ZERO_DOCUMENT_OUTPUT", "AXIS_ZERO_BOUNDARY", axis_id)
        variants = axis.get("variants", [])
        require([item.get("variant") for item in variants] == list(VARIANTS), "AXIS_VARIANTS", axis_id)
        limit = int(limit_text)
        for item, variant, value in zip(variants, VARIANTS, (limit - 1, limit, limit + 1), strict=True):
            require(item.get("caseId") == f"CAP::{axis_id}::{variant}", "AXIS_CASE_ID", axis_id)
            require(item.get("stimulusValue") == str(value), "AXIS_STIMULUS", axis_id)
    return by_id, coverage_bytes


def validate_fixtures(
    fixture_root: Path,
    axes: dict[str, dict[str, Any]],
    target: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    paths = sorted(fixture_root.glob("cap-*.json"), key=lambda path: path.name)
    require(len(paths) == 12, "FIXTURE_COUNT", len(paths))
    target_fixtures = target["fixtures"]
    require(isinstance(target_fixtures, list) and len(target_fixtures) == 12,
            "TARGET_FIXTURE_COUNT", len(target_fixtures) if isinstance(target_fixtures, list) else "not-list")
    target_by_path = {entry.get("path"): entry for entry in target_fixtures}
    require(len(target_by_path) == 12, "TARGET_FIXTURE_PATH_DUPLICATE", len(target_by_path))

    inventory: list[dict[str, Any]] = []
    scenarios: dict[str, dict[str, Any]] = {}
    seen_variants: set[tuple[str, str]] = set()
    for path in paths:
        data, fixture = load_json(path)
        require_exact_members(fixture, FIXTURE_MEMBERS, "FIXTURE_MEMBERS")
        require_no_forbidden_members(fixture)
        require(fixture["fixtureVersion"] == "renderweave-domain-services-fixture/1.0",
                "FIXTURE_VERSION", path.name)
        require(fixture["generatorProfile"] == "renderweave-domain-services-generator/1.0",
                "FIXTURE_GENERATOR", path.name)
        require(fixture["executionClass"] == EXECUTION_CLASS, "FIXTURE_CLASS", path.name)
        scenario = fixture["scenario"]
        require_exact_members(scenario, SCENARIO_MEMBERS, "FIXTURE_SCENARIO_MEMBERS")
        require(scenario["mode"] == "CAPACITY_BOUNDARY", "FIXTURE_MODE", path.name)
        require(scenario["operationId"] == "main", "FIXTURE_OPERATION", path.name)
        require(scenario["entrypoint"] == "ASSET_CONTENT_ADMISSION_CAPACITY_GUARD",
                "FIXTURE_ENTRYPOINT", path.name)
        require(scenario["guardContractId"] == GUARD_CONTRACT, "FIXTURE_GUARD", path.name)
        require(scenario["valueEncoding"] == "CANONICAL_INTEGER", "FIXTURE_ENCODING", path.name)
        require(scenario["comparator"] == "MAX_INCLUSIVE", "FIXTURE_COMPARATOR", path.name)
        require(scenario["contractStage"] == "ASSET_CONTENT_ADMISSION", "FIXTURE_STAGE", path.name)
        require(scenario["publicRenderStage"] == "ASSET_ADMISSION", "FIXTURE_PUBLIC_STAGE", path.name)
        require(scenario["zeroBoundary"] == "ZERO_DOCUMENT_OUTPUT", "FIXTURE_ZERO_BOUNDARY", path.name)
        require(scenario["faultSchedule"] == {"kind": "NONE"}, "FIXTURE_FAULT", path.name)
        axis_id = scenario["limitId"]
        variant = scenario["variant"]
        require(axis_id in axes and variant in VARIANTS, "FIXTURE_AXIS_VARIANT", path.name)
        require((axis_id, variant) not in seen_variants, "FIXTURE_AXIS_VARIANT_DUPLICATE", path.name)
        seen_variants.add((axis_id, variant))
        axis_variant = next(item for item in axes[axis_id]["variants"] if item["variant"] == variant)
        require(scenario["scenarioId"] == axis_variant["caseId"], "FIXTURE_CASE_ID", path.name)
        require(scenario["observedValue"] == axis_variant["stimulusValue"], "FIXTURE_VALUE", path.name)
        relative = "domain-services/fixtures/" + path.name
        observed_artifact = {"path": relative, "sha256": sha256(data), "byteLength": len(data)}
        require(target_by_path.get(relative) == observed_artifact, "TARGET_FIXTURE_DIGEST", relative)
        inventory.append(observed_artifact)
        scenarios[scenario["scenarioId"]] = scenario

    require(seen_variants == {(axis_id, variant) for axis_id in AXIS_IDS for variant in VARIANTS},
            "FIXTURE_MATRIX", sorted(seen_variants))
    require([entry["path"] for entry in target_fixtures] == [entry["path"] for entry in inventory],
            "TARGET_FIXTURE_ORDER", "fixture order")
    return inventory, scenarios


def expected_observation(scenario: dict[str, Any], limit: int) -> dict[str, Any]:
    observed_text = scenario["observedValue"]
    require(CANONICAL_INTEGER.fullmatch(observed_text) is not None,
            "OBSERVED_VALUE_CANONICAL", observed_text)
    accepted = int(observed_text) <= limit
    return {
        "accepted": accepted,
        "terminalCode": None if accepted else "ASSET_CONTENT_LIMIT_EXCEEDED",
        "terminalStage": None if accepted else "ASSET_CONTENT_ADMISSION",
        "limitId": scenario["limitId"],
        "observedValue": observed_text,
        "reservationReached": True,
        "zeroBoundary": None if accepted else "ZERO_DOCUMENT_OUTPUT",
        "downstreamEffects": ["targetAxisAccepted=1"] if accepted else [
            "renderDocuments=0",
            "engineCommands=0",
            "renderOutputs=0",
        ],
    }


def validate_primary(
    primary_path: Path,
    target: dict[str, Any],
    target_bytes: bytes,
    axes: dict[str, dict[str, Any]],
    inventory: list[dict[str, Any]],
    scenarios: dict[str, dict[str, Any]],
) -> tuple[bytes, list[dict[str, Any]]]:
    primary_bytes, primary = load_json(primary_path)
    require_exact_members(primary, {
        "reportVersion", "engine", "role", "assurance", "executionClass",
        "guardContractId", "targetManifest", "implementationRevision", "caseCount",
        "passed", "failed", "observations", "boundary",
    }, "PRIMARY_MEMBERS")
    require(primary["reportVersion"] == PRIMARY_VERSION, "PRIMARY_VERSION", primary["reportVersion"])
    require(primary["engine"] == "java-domain-authority", "PRIMARY_ENGINE", primary["engine"])
    require(primary["role"] == "primary-exact-product-guard-executor", "PRIMARY_ROLE", primary["role"])
    require(primary["assurance"] == "A1_EXACT_PRODUCT_EXECUTION", "PRIMARY_ASSURANCE", primary["assurance"])
    require(primary["executionClass"] == EXECUTION_CLASS, "PRIMARY_CLASS", primary["executionClass"])
    require(primary["guardContractId"] == GUARD_CONTRACT, "PRIMARY_GUARD", primary["guardContractId"])
    require(primary["targetManifest"] == {
        "path": TARGET_PATH,
        "sha256": sha256(target_bytes),
        "byteLength": len(target_bytes),
    }, "PRIMARY_TARGET_BINDING", primary["targetManifest"])
    require(primary["implementationRevision"] == target["implementationRevision"],
            "PRIMARY_IMPLEMENTATION_REVISION", primary["implementationRevision"])
    require((primary["caseCount"], primary["passed"], primary["failed"]) == (12, 12, 0),
            "PRIMARY_COUNTS", (primary["caseCount"], primary["passed"], primary["failed"]))
    require(primary["boundary"] == BOUNDARY, "PRIMARY_BOUNDARY", primary["boundary"])

    observations = primary["observations"]
    require(isinstance(observations, list) and len(observations) == 12,
            "PRIMARY_OBSERVATION_COUNT", len(observations) if isinstance(observations, list) else "not-list")
    inventory_by_path = {entry["path"]: entry for entry in inventory}
    require([item.get("fixturePath") for item in observations] == [entry["path"] for entry in inventory],
            "PRIMARY_OBSERVATION_ORDER", "fixture order")
    replayed: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in observations:
        require_exact_members(item, {"caseId", "fixturePath", "fixtureSha256", "observation"},
                              "PRIMARY_OBSERVATION_MEMBERS")
        case_id = item["caseId"]
        require(case_id not in seen and case_id in scenarios, "PRIMARY_CASE_ID", case_id)
        seen.add(case_id)
        fixture_entry = inventory_by_path.get(item["fixturePath"])
        require(fixture_entry is not None, "PRIMARY_FIXTURE_PATH", item["fixturePath"])
        require(item["fixtureSha256"] == fixture_entry["sha256"], "PRIMARY_FIXTURE_SHA", case_id)
        observation = item["observation"]
        require_exact_members(observation, OBSERVATION_MEMBERS, "PRIMARY_CLOSED_OBSERVATION_MEMBERS")
        scenario = scenarios[case_id]
        expected = expected_observation(scenario, int(axes[scenario["limitId"]]["limitValue"]))
        require(observation == expected, "PRIMARY_OBSERVATION_DRIFT", case_id)
        replayed.append({"caseId": case_id, "observation": expected})
    require(seen == set(scenarios), "PRIMARY_CASE_COVERAGE", sorted(seen))
    return primary_bytes, replayed


def write_report(
    report_path: Path,
    target: dict[str, Any],
    target_bytes: bytes,
    primary_path: Path,
    primary_bytes: bytes,
    coverage_path: Path,
    coverage_bytes: bytes,
    inventory: list[dict[str, Any]],
    replayed: list[dict[str, Any]],
) -> None:
    fixture_digest_input = "\n".join(entry["sha256"] for entry in inventory).encode("ascii")
    replay_bytes = json.dumps(replayed, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    report = {
        "reportVersion": INDEPENDENT_VERSION,
        "engine": "python-independent",
        "role": "independent-exact-observation-replayer",
        "assurance": "A2_EXACT_OBSERVATION_REPLAY",
        "executionClass": EXECUTION_CLASS,
        "guardContractId": GUARD_CONTRACT,
        "targetManifest": {
            "path": TARGET_PATH,
            "sha256": sha256(target_bytes),
            "byteLength": len(target_bytes),
        },
        "implementationRevision": target["implementationRevision"],
        "primaryReport": {
            "path": primary_path.name,
            "sha256": sha256(primary_bytes),
            "byteLength": len(primary_bytes),
        },
        "coverage": {
            "path": ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json",
            "sha256": sha256(coverage_bytes),
            "byteLength": len(coverage_bytes),
        },
        "fixtureSetSha256": sha256(fixture_digest_input),
        "observationReplaySha256": sha256(replay_bytes),
        "caseCount": 12,
        "passed": 12,
        "failed": 0,
        "boundary": {
            **BOUNDARY,
            "sharedSemanticLibrary": None,
            "expectedFixtureFieldsRead": False,
            "networkAttempts": 0,
            "externalProviderAttempts": 0,
            "recordIssuanceAllowed": False,
        },
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with report_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--coverage", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--primary-report", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    arguments = parser.parse_args()

    repo = arguments.repo.resolve()
    target, target_bytes = validate_target(repo, arguments.target.resolve())
    axes, coverage_bytes = validate_coverage(arguments.coverage.resolve())
    inventory, scenarios = validate_fixtures(arguments.fixtures.resolve(), axes, target)
    primary_bytes, replayed = validate_primary(
        arguments.primary_report.resolve(), target, target_bytes, axes, inventory, scenarios
    )
    write_report(
        arguments.report.resolve(),
        target,
        target_bytes,
        arguments.primary_report.resolve(),
        primary_bytes,
        arguments.coverage.resolve(),
        coverage_bytes,
        inventory,
        replayed,
    )
    print("DOMAIN_SERVICES capacity replay: 12/12 PASS (A2 exact observation replay)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, VerificationFailure) as failure:
        print(f"DOMAIN_SERVICES capacity replay failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure
