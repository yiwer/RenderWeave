from __future__ import annotations

import argparse
import hashlib
import json
import platform
import re
import sys
from pathlib import Path
from typing import Any


SPEC = Path(__file__).resolve().parent.parent
ROOT = "renderer-exact-output"
PROFILE = "renderweave-renderer-exact-output-generator/1.0"
EXECUTION_CLASS = "EXEC::RENDERER_EXACT_OUTPUT::1.0"
BASELINE_ID = "baseline.renderer-exact-output.minimal-v1"
ADAPTER_ID = "renderweave-renderer-exact-output-observation-adapter/1.0"
BASELINE_PATH = f"{ROOT}/baseline-v1.json"
CONTRACT_PATH = f"{ROOT}/fixture-contract-v1.json"
ADAPTER_PATH = f"{ROOT}/observation-adapter-v1.json"
CATALOG_PATH = f"{ROOT}/capacity-scenarios-v1.json"
GOLDENS_PATH = f"{ROOT}/generator-goldens-v1.json"
TARGET_PATH = f"{ROOT}/generator-target-manifest-v1.json"
IMPLEMENTATION_PATH = f"{ROOT}/generator-implementation-manifest-v1.json"
COVERAGE_PATH = "conformance-capacity-coverage-v1.json"
PROBE_PROFILE_PATH = "conformance-probe-profile-v1.json"
EXPECTED_AXIS_COUNT = 54
EXPECTED_CAPACITY_COUNT = 162
EXPECTED_TOTAL_COUNT = 163
ALLOWED_ENCODINGS = {"CANONICAL_INTEGER", "FORMULA"}
ALLOWED_COMPARATORS = {"EXACT", "FORMULA_MAX", "MAX_INCLUSIVE", "MIN_INCLUSIVE", "READY_MINIMUM"}
OMITTED_EXPECTATION_KEYS = [
    "expectedTerminal",
    "expectedAssertions",
    "plannedAssertions",
    "plannedOracleId",
    "requirementIds",
    "resolvedCode",
    "resolvedKind",
]

checks = 0
failures: list[dict[str, str]] = []


def check(condition: bool, code: str, detail: str) -> None:
    global checks
    checks += 1
    if not condition:
        failures.append({"code": code, "detail": detail})


def path(relative: str) -> Path:
    return SPEC / relative


def raw(relative: str) -> bytes:
    return path(relative).read_bytes()


def read_json(relative: str) -> dict[str, Any]:
    return json.loads(raw(relative).decode("utf-8"))


def encode(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, separators=(",", ": ")) + "\n").encode("utf-8")


def artifact(relative: str) -> dict[str, Any]:
    content = raw(relative)
    return {
        "path": relative,
        "sha256": "sha256:" + hashlib.sha256(content).hexdigest(),
        "byteLength": len(content),
    }


def expected_baseline() -> dict[str, Any]:
    return {
        "fixtureVersion": "renderweave-renderer-exact-output-baseline/1.0",
        "baselineId": BASELINE_ID,
        "executionClass": EXECUTION_CLASS,
        "fixtureOnlyEffectiveCommandContext": {
            "contractVersion": "renderweave-render-command/1.0",
            "requestId": "00000000-0000-4000-8000-000000000000",
            "rendererProfile": "renderweave-renderer/1.0",
            "deadlineAt": "2026-08-17T00:01:00.000Z",
            "documentContract": {
                "dslVersion": "renderweave-render/1.0",
                "layoutProfile": "renderweave-layout/1.0",
                "rootKind": "canvas",
                "rootOccurrenceId": "rwocc_0000000000000000",
                "resourceCount": 0,
            },
            "output": {"profile": "renderweave-output-png/1.0", "dpi": 96},
            "diagnostics": {"layoutTrace": False},
        },
        "resourceState": {
            "renderResources": [],
            "fetchLeases": [],
            "images": [],
            "fonts": [],
        },
        "faultSchedule": {"kind": "NONE"},
        "productCommandConstructed": False,
        "productRenderDocumentBytesPresent": False,
        "renderDocumentDigestPresent": False,
        "rendererTargetInvoked": False,
        "externalReadsAllowed": False,
        "networkReadsAllowed": False,
        "currentTimeReadsAllowed": False,
        "productMutationAllowedByFixtureGeneration": False,
    }


def selected_axes() -> list[dict[str, Any]]:
    selected = [axis for axis in read_json(COVERAGE_PATH)["axes"] if axis["executionClass"] == EXECUTION_CLASS]
    check(len(selected) == EXPECTED_AXIS_COUNT, "AXIS_COUNT", str(len(selected)))
    seen: set[str] = set()
    for axis in selected:
        check(axis["limitId"] not in seen, "DUPLICATE_LIMIT", axis["limitId"])
        seen.add(axis["limitId"])
        check(axis["valueEncoding"] in ALLOWED_ENCODINGS, "VALUE_ENCODING", axis["limitId"])
        check(axis["comparator"] in ALLOWED_COMPARATORS, "COMPARATOR", axis["limitId"])
        check([entry["variant"] for entry in axis["variants"]] == ["below", "at", "above"], "VARIANT_ORDER", axis["limitId"])
        if axis["valueEncoding"] == "FORMULA":
            check(axis["limitId"] == "layoutFontAndRaster.stackWaterFillRoundsPerContainer", "FORMULA_AXIS", axis["limitId"])
            check(axis["comparator"] == "FORMULA_MAX" and axis["deltaId"] is None, "FORMULA_CONTRACT", axis["limitId"])
            check(axis["limitValue"] == "fillChildCount+1", "FORMULA_LIMIT", axis["limitId"])
            check([entry["stimulusValue"] for entry in axis["variants"]] == ["fillChildCount", "fillChildCount+1", "fillChildCount+2"], "FORMULA_VARIANTS", axis["limitId"])
        else:
            check(axis["comparator"] != "FORMULA_MAX", "FORMULA_COMPARATOR", axis["limitId"])
    check(sum(1 for axis in selected if axis["valueEncoding"] == "FORMULA") == 1, "FORMULA_AXIS_COUNT", str(len(selected)))
    return selected


def planned_assertions(axis: dict[str, Any], variant: dict[str, Any]) -> list[dict[str, Any]]:
    expected = variant["expectedAssertions"]
    terminal_code = (
        {"assertionId": "A002", "probeId": "operation.terminalCode", "operator": "ABSENT"}
        if "terminalCode" not in expected
        else {"assertionId": "A002", "probeId": "operation.terminalCode", "operator": "EQ", "expected": {"kind": "LITERAL", "value": expected["terminalCode"]}}
    )
    terminal_stage = (
        {"assertionId": "A003", "probeId": "operation.terminalStage", "operator": "ABSENT"}
        if "terminalStage" not in expected
        else {"assertionId": "A003", "probeId": "operation.terminalStage", "operator": "EQ", "expected": {"kind": "LITERAL", "value": expected["terminalStage"]}}
    )
    zero_boundary = (
        {"assertionId": "A007", "probeId": "capacity.zeroBoundary", "operator": "ABSENT"}
        if "zeroBoundary" not in expected
        else {"assertionId": "A007", "probeId": "capacity.zeroBoundary", "operator": "EQ", "expected": {"kind": "LITERAL", "value": expected["zeroBoundary"]}}
    )
    return [
        {"assertionId": "A001", "probeId": "operation.accepted", "operator": "EQ", "expected": {"kind": "LITERAL", "value": expected["accepted"]}},
        terminal_code,
        terminal_stage,
        {"assertionId": "A004", "probeId": "capacity.limitId", "operator": "EQ", "expected": {"kind": "LITERAL", "value": axis["limitId"]}},
        {"assertionId": "A005", "probeId": "capacity.observedValue", "operator": "EQ", "expected": {"kind": "LITERAL", "value": variant["stimulusValue"]}},
        {"assertionId": "A006", "probeId": "capacity.reservationReached", "operator": "EQ", "expected": {"kind": "LITERAL", "value": True}},
        zero_boundary,
        {"assertionId": "A008", "probeId": "operation.downstreamEffects", "operator": "SEQUENCE_EQ", "expected": {"kind": "LITERAL", "value": expected["downstreamEffects"]}},
    ]


def parameters(axis: dict[str, Any], variant: dict[str, Any]) -> dict[str, Any]:
    oracle = axis["resolvedOracle"]
    return {
        "comparator": axis["comparator"],
        "contractStage": oracle["contractStage"],
        "deltaId": axis["deltaId"],
        "executionClass": axis["executionClass"],
        "limitId": axis["limitId"],
        "limitValue": axis["limitValue"],
        "mode": "CAPACITY_BOUNDARY",
        "plannedAssertions": planned_assertions(axis, variant),
        "plannedOracleId": variant["plannedOracleId"],
        "publicRenderStage": oracle["publicRenderStage"],
        "requirementIds": axis["requirementIds"],
        "reservationPoint": oracle["reservationPoint"],
        "resolvedCode": oracle["code"],
        "resolvedKind": oracle["kind"],
        "stimulusValue": variant["stimulusValue"],
        "valueEncoding": axis["valueEncoding"],
        "variant": variant["variant"],
        "zeroBoundary": oracle["zeroBoundary"],
    }


def slug(limit_id: str) -> str:
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", limit_id)
    return re.sub(r"^-+|-+$", "", re.sub(r"[^a-zA-Z0-9]+", "-", value)).lower()


def fixture_path(axis_index: int, limit_id: str, variant: str) -> str:
    return f"{ROOT}/fixtures/cap-{axis_index + 1:03d}-{slug(limit_id)}-{variant}.json"


def common_fixture() -> dict[str, Any]:
    baseline = artifact(BASELINE_PATH)
    adapter = artifact(ADAPTER_PATH)
    return {
        "fixtureVersion": "renderweave-renderer-exact-output-fixture/1.0",
        "generatorProfile": PROFILE,
        "executionClass": EXECUTION_CLASS,
        "baseline": {"baselineId": BASELINE_ID, "path": baseline["path"], "sha256": baseline["sha256"], "byteLength": baseline["byteLength"]},
        "observationAdapter": {"adapterId": ADAPTER_ID, "path": adapter["path"], "sha256": adapter["sha256"]},
    }


def validate_observed(value: Any, encoding: str) -> None:
    check(isinstance(value, str), "OBSERVED_VALUE_TYPE", str(value))
    if isinstance(value, str) and encoding == "CANONICAL_INTEGER":
        check(re.fullmatch(r"-?(0|[1-9][0-9]*)", value) is not None, "CANONICAL_INTEGER", value)
    elif isinstance(value, str):
        check(encoding == "FORMULA", "VALUE_ENCODING", encoding)
        check(re.fullmatch(r"fillChildCount(?:\+[12])?", value) is not None, "CLOSED_FORMULA", value)


def target_contract(exact: bool) -> dict[str, Any]:
    return {
        "exactProductionGuardRequired": exact,
        "duplicateGuardImplementationForbidden": True,
        "productApiSurfaceCreated": False,
        "productCommandConstructedByThisProbe": False,
        "renderDocumentParsedByThisProbe": False,
        "resourceFetchOrDecodeExecutedByThisProbe": False,
        "layoutOrShapingExecutedByThisProbe": False,
        "rasterOrEncodingExecutedByThisProbe": False,
        "rendererOrOutputExecutedByThisProbe": False,
        "exactRendererOutputProvenByThisProbe": False,
    }


def capacity_fixture(axis: dict[str, Any], variant: dict[str, Any]) -> dict[str, Any]:
    validate_observed(variant["stimulusValue"], axis["valueEncoding"])
    common = common_fixture()
    oracle = axis["resolvedOracle"]
    return {
        "fixtureVersion": common["fixtureVersion"],
        "generatorProfile": common["generatorProfile"],
        "executionClass": common["executionClass"],
        "baseline": common["baseline"],
        "scenario": {
            "mode": "CAPACITY_BOUNDARY",
            "scenarioId": variant["caseId"],
            "operationId": "main",
            "entrypoint": "RENDERER_EXACT_OUTPUT_CAPACITY_GUARD",
            "guardContractId": "renderweave-renderer-exact-output-capacity-guard/1.0",
            "limitId": axis["limitId"],
            "observedValue": variant["stimulusValue"],
            "valueEncoding": axis["valueEncoding"],
            "comparator": axis["comparator"],
            "variant": variant["variant"],
            "contractStage": oracle["contractStage"],
            "publicRenderStage": oracle["publicRenderStage"],
            "reservationPoint": oracle["reservationPoint"],
            "zeroBoundary": oracle["zeroBoundary"],
            "faultSchedule": {"kind": "NONE"},
        },
        "observationAdapter": common["observationAdapter"],
        "targetContract": target_contract(True),
    }


def named_fixture() -> dict[str, Any]:
    common = common_fixture()
    return {
        "fixtureVersion": common["fixtureVersion"],
        "generatorProfile": common["generatorProfile"],
        "executionClass": common["executionClass"],
        "baseline": common["baseline"],
        "scenario": {
            "mode": "NAMED_SCENARIO",
            "scenarioId": "RENDERER-EXACT-OUTPUT-BASELINE-CONTEXT",
            "operationId": "main",
            "entrypoint": "BASELINE_CONTRACT_CHECK",
            "faultSchedule": {"kind": "NONE"},
        },
        "observationAdapter": common["observationAdapter"],
        "targetContract": target_contract(False),
    }


def expected_scenarios(axes: list[dict[str, Any]]) -> list[dict[str, Any]]:
    scenarios: list[dict[str, Any]] = []
    for axis_index, axis in enumerate(axes):
        for variant in axis["variants"]:
            scenarios.append({
                "scenarioId": variant["caseId"],
                "mode": "CAPACITY_BOUNDARY",
                "parameters": parameters(axis, variant),
                "fixtureArtifactPath": fixture_path(axis_index, axis["limitId"], variant["variant"]),
            })
    named_path = f"{ROOT}/fixtures/named-renderer-exact-output-baseline-context.json"
    named_artifact = artifact(named_path)
    scenarios.append({
        "scenarioId": "RENDERER-EXACT-OUTPUT-BASELINE-CONTEXT",
        "mode": "NAMED_SCENARIO",
        "parameters": {
            "mode": "NAMED_SCENARIO",
            "scenarioId": "RENDERER-EXACT-OUTPUT-BASELINE-CONTEXT",
            "fixtureArtifactPath": named_path,
            "fixtureArtifactSha256": named_artifact["sha256"],
            "expectedObservationProfile": ADAPTER_ID,
        },
        "fixtureArtifactPath": named_path,
    })
    check(len(scenarios) == EXPECTED_TOTAL_COUNT, "SCENARIO_COUNT", str(len(scenarios)))
    return scenarios


def validate_contract_and_adapter() -> None:
    check(encode(expected_baseline()) == raw(BASELINE_PATH), "BASELINE_BYTES", BASELINE_ID)
    contract = read_json(CONTRACT_PATH)
    check(contract["status"] == "FROZEN_STATIC_FIXTURE_CONTRACT", "CONTRACT_STATUS", contract["status"])
    check(contract["allowedModes"] == ["CAPACITY_BOUNDARY", "NAMED_SCENARIO"], "CONTRACT_MODES", str(contract["allowedModes"]))
    check(contract["capacityBoundary"]["entrypoint"] == "RENDERER_EXACT_OUTPUT_CAPACITY_GUARD", "CONTRACT_ENTRYPOINT", "guard")
    target = contract["capacityBoundary"]["targetContract"]
    check(target["exactProductionGuardRequired"] is True, "EXACT_GUARD_REQUIRED", "true")
    check(target["exactRendererOutputProvenByThisProbe"] is False, "NO_PRODUCT_PATH_PROOF", "false")
    check("terminal output" in contract["evidenceBoundary"]["fixtureGenerationCannotProve"], "STATIC_BOUNDARY", "terminal output")

    profile = read_json(PROBE_PROFILE_PATH)
    adapter = read_json(ADAPTER_PATH)
    admitted = [probe for probe in profile["probes"] if EXECUTION_CLASS in probe["executionClasses"]]
    check(len(admitted) == 32 == adapter["mappingCount"], "ADAPTER_COUNT", f"{len(admitted)}/{adapter['mappingCount']}")
    check(adapter["genericJsonPathAllowed"] is False and adapter["arbitraryScriptAllowed"] is False, "CLOSED_ADAPTER", "false")
    check(adapter["expectedValuesVisibleToTarget"] is False, "NO_EXPECTED_VALUES", "false")
    for probe, mapping in zip(admitted, adapter["mappings"], strict=True):
        check(mapping["probeId"] == probe["probeId"], "ADAPTER_PROBE_ORDER", probe["probeId"])
        check(mapping["valueType"] == probe["valueType"], "ADAPTER_TYPE", probe["probeId"])
        check(mapping["source"] == "closedObservation." + probe["probeId"], "ADAPTER_SOURCE", probe["probeId"])
        absent = "EXPLICIT_ABSENT" if "ABSENT" in probe["allowedOperators"] else "MUST_BE_PRESENT"
        check(mapping["absentPolicy"] == absent, "ADAPTER_ABSENT_POLICY", probe["probeId"])


def expected_target(fixture_artifacts: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "artifactVersion": "renderweave-renderer-exact-output-generator-target/1.0",
        "targetId": "RENDERER_EXACT_OUTPUT_GENERATOR_TARGET::FIXTURE::1.0",
        "generatorProfile": PROFILE,
        "status": "FROZEN_STATIC_GENERATOR_TARGET",
        "implementationRevision": "renderer-exact-output-fixture-generator/1.0",
        "entrypoint": artifact(f"{ROOT}/generate-renderer-exact-output-fixtures.mjs"),
        "baseline": artifact(BASELINE_PATH),
        "fixtureContract": artifact(CONTRACT_PATH),
        "observationAdapter": artifact(ADAPTER_PATH),
        "scenarioCatalog": artifact(CATALOG_PATH),
        "goldenVectors": artifact(GOLDENS_PATH),
        "fixtureArtifacts": fixture_artifacts,
        "expectedScenarioCount": EXPECTED_TOTAL_COUNT,
        "productTarget": False,
        "productExecutionAllowed": False,
        "networkReadsAllowed": False,
        "environmentReadsAllowed": False,
        "currentTimeReadsAllowed": False,
    }


def expected_implementation() -> dict[str, Any]:
    return {
        "artifactVersion": "renderweave-renderer-exact-output-generator-implementation/1.0",
        "generatorProfile": PROFILE,
        "status": "FROZEN_GOLDENS_PRESENT_STATIC_ONLY",
        "implementationRevision": "renderer-exact-output-fixture-generator/1.0",
        "runtime": "Node.js >=24",
        "entrypoint": artifact(f"{ROOT}/generate-renderer-exact-output-fixtures.mjs"),
        "baseline": artifact(BASELINE_PATH),
        "fixtureContract": artifact(CONTRACT_PATH),
        "observationAdapter": artifact(ADAPTER_PATH),
        "scenarioCatalog": artifact(CATALOG_PATH),
        "goldenVectors": artifact(GOLDENS_PATH),
        "targetManifest": artifact(TARGET_PATH),
        "omittedExpectationKeys": OMITTED_EXPECTATION_KEYS,
        "productExecutionAllowed": False,
        "environmentReadsAllowed": False,
        "networkReadsAllowed": False,
        "currentTimeReadsAllowed": False,
        "hiddenDefaultsAllowed": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    validate_contract_and_adapter()
    axes = selected_axes()
    scenarios = expected_scenarios(axes)
    expected_catalog = {
        "artifactVersion": "renderweave-renderer-exact-output-scenario-catalog/1.0",
        "status": "FROZEN_162_CAPACITY_PLUS_1_NAMED",
        "generatorProfile": PROFILE,
        "executionClass": EXECUTION_CLASS,
        "sourceRule": "The 162 CAPACITY_BOUNDARY parameter objects are copied from the fully expanded 54-axis mapping; no matrix default or value interpretation remains. The named scenario is closed locally.",
        "baseline": artifact(BASELINE_PATH),
        "observationAdapter": artifact(ADAPTER_PATH),
        "scenarios": scenarios,
        "scenarioCount": EXPECTED_TOTAL_COUNT,
        "capacityScenarioCount": EXPECTED_CAPACITY_COUNT,
        "namedScenarioCount": 1,
    }
    check(encode(expected_catalog) == raw(CATALOG_PATH), "SCENARIO_CATALOG_BYTES", CATALOG_PATH)

    by_limit = {axis["limitId"]: axis for axis in axes}
    fixture_artifacts: list[dict[str, Any]] = []
    golden_scenarios: list[dict[str, Any]] = []
    for scenario in scenarios:
        if scenario["mode"] == "NAMED_SCENARIO":
            expected_fixture = named_fixture()
        else:
            axis = by_limit[scenario["parameters"]["limitId"]]
            variant = next(item for item in axis["variants"] if item["variant"] == scenario["parameters"]["variant"])
            check(parameters(axis, variant) == scenario["parameters"], "PARAMETERS", scenario["scenarioId"])
            expected_fixture = capacity_fixture(axis, variant)
        relative = scenario["fixtureArtifactPath"]
        actual = raw(relative)
        check(encode(expected_fixture) == actual, "FIXTURE_BYTES", scenario["scenarioId"])
        fixture_artifact = artifact(relative)
        fixture_artifacts.append(fixture_artifact)
        golden_scenarios.append({
            "scenarioId": scenario["scenarioId"],
            "mode": scenario["mode"],
            "parameters": scenario["parameters"],
            "expectedFixtureArtifact": fixture_artifact,
        })
        text = actual.decode("utf-8")
        for forbidden in OMITTED_EXPECTATION_KEYS:
            check(f'"{forbidden}"' not in text, "FIXTURE_EXPECTATION_LEAK", f"{scenario['scenarioId']}:{forbidden}")

    expected_goldens = {
        "artifactVersion": "renderweave-renderer-exact-output-generator-goldens/1.0",
        "generatorProfile": PROFILE,
        "executionClass": EXECUTION_CLASS,
        "baseline": artifact(BASELINE_PATH),
        "observationAdapter": artifact(ADAPTER_PATH),
        "scenarios": golden_scenarios,
        "goldenCount": EXPECTED_TOTAL_COUNT,
        "capacityGoldenCount": EXPECTED_CAPACITY_COUNT,
        "namedGoldenCount": 1,
    }
    check(encode(expected_goldens) == raw(GOLDENS_PATH), "GOLDEN_BYTES", GOLDENS_PATH)
    check(encode(expected_target(fixture_artifacts)) == raw(TARGET_PATH), "TARGET_BYTES", TARGET_PATH)
    check(encode(expected_implementation()) == raw(IMPLEMENTATION_PATH), "IMPLEMENTATION_BYTES", IMPLEMENTATION_PATH)

    result = {
        "resultVersion": "renderweave-renderer-exact-output-fixture-generator-result/1.0",
        "executorId": "RENDERER_EXACT_OUTPUT_FIXTURE_GENERATOR::PYTHON::1.0",
        "role": "independent-renderer-exact-output-fixture-generator-replayer",
        "status": "PASS" if not failures else "FAIL",
        "checkCount": checks,
        "failureCount": len(failures),
        "runtime": f"CPython {platform.python_version()}",
        "generatorTargetSha256": artifact(TARGET_PATH)["sha256"],
        "fixtureCount": len(fixture_artifacts),
        "productExecutionObserved": False,
        "recordIssuanceAllowed": False,
    }
    if failures:
        result["failures"] = failures
    output = encode(result)
    if args.output:
        path(args.output).write_bytes(output)
    sys.stdout.buffer.write(output)
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
