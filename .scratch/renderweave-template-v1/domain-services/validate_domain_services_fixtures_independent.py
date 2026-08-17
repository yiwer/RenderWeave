from __future__ import annotations

import argparse
import hashlib
import json
import platform
import re
import sys
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
SPEC = HERE.parent
PROFILE = "renderweave-domain-services-generator/1.0"
EXECUTION_CLASS = "EXEC::DOMAIN_SERVICES::1.0"
ADAPTER_ID = "renderweave-domain-services-observation-adapter/1.0"
BASELINE_ID = "baseline.domain-services.minimal-v1"
LIMIT_IDS = [
    "assetsAndFetch.acceptedImageBytesPerContent",
    "assetsAndFetch.acceptedImageEdgePixelsPerContent",
    "assetsAndFetch.acceptedImagePixelsPerContent",
    "assetsAndFetch.acceptedFontBytesPerContent",
]
OMITTED_EXPECTATION_KEYS = {
    "expectedTerminal",
    "expectedAssertions",
    "plannedAssertions",
    "plannedOracleId",
    "requirementIds",
    "resolvedCode",
    "resolvedKind",
}

checks = 0
failures: list[dict[str, str]] = []


def check(condition: bool, code: str, detail: str) -> None:
    global checks
    checks += 1
    if not condition:
        failures.append({"code": code, "detail": detail})


def reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate member: {key}")
        result[key] = value
    return result


def decode_json(data: bytes, source: str) -> Any:
    check(not data.startswith(b"\xef\xbb\xbf"), "JSON_BOM", source)
    check(data.endswith(b"\n") and b"\r" not in data, "JSON_TEXT_FORMAT", source)
    try:
        return json.loads(
            data.decode("utf-8", "strict"),
            object_pairs_hook=reject_duplicate_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)),
        )
    except Exception as error:  # pragma: no cover - captured in result
        failures.append({"code": "JSON_PARSE", "detail": f"{source}:{error}"})
        return None


def path(relative: str) -> Path:
    return SPEC / relative


def raw(relative: str) -> bytes:
    return path(relative).read_bytes()


def read_json(relative: str) -> Any:
    return decode_json(raw(relative), relative)


def encode(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def artifact(relative: str) -> dict[str, Any]:
    data = raw(relative)
    return {"path": relative, "sha256": digest(data), "byteLength": len(data)}


def expected_baseline() -> dict[str, Any]:
    return {
        "fixtureVersion": "renderweave-domain-services-baseline/1.0",
        "baselineId": BASELINE_ID,
        "executionClass": EXECUTION_CLASS,
        "ownerScopeId": "00000000-0000-4000-8000-000000000001",
        "actorId": "00000000-0000-4000-8000-000000000002",
        "templateState": {
            "templateId": "00000000-0000-4000-8000-000000000003",
            "lifecycle": "ACTIVE",
            "revision": 0,
            "readiness": "READY",
            "staticSchemaRef": "system-empty@v1",
        },
        "assetState": {
            "assets": [],
            "blobs": [],
            "idempotencyRecords": [],
            "auditEvents": [],
            "domainEvents": [],
        },
        "faultSchedule": {"kind": "NONE"},
        "externalReadsAllowed": False,
        "networkReadsAllowed": False,
        "currentTimeReadsAllowed": False,
        "productMutationAllowedByFixtureGeneration": False,
    }


def literal(assertion_id: str, probe: str, value: Any) -> dict[str, Any]:
    return {
        "assertionId": assertion_id,
        "probeId": probe,
        "operator": "EQ",
        "expected": {"kind": "LITERAL", "value": value},
    }


def absent(assertion_id: str, probe: str) -> dict[str, Any]:
    return {"assertionId": assertion_id, "probeId": probe, "operator": "ABSENT"}


def sequence(assertion_id: str, probe: str, value: list[str]) -> dict[str, Any]:
    return {
        "assertionId": assertion_id,
        "probeId": probe,
        "operator": "SEQUENCE_EQ",
        "expected": {"kind": "LITERAL", "value": value},
    }


def assertions(axis: dict[str, Any], variant: dict[str, Any]) -> list[dict[str, Any]]:
    values = variant["expectedAssertions"]
    return [
        literal("A001", "operation.accepted", values["accepted"]),
        literal("A002", "operation.terminalCode", values["terminalCode"])
        if "terminalCode" in values
        else absent("A002", "operation.terminalCode"),
        literal("A003", "operation.terminalStage", values["terminalStage"])
        if "terminalStage" in values
        else absent("A003", "operation.terminalStage"),
        literal("A004", "capacity.limitId", axis["limitId"]),
        literal("A005", "capacity.observedValue", variant["stimulusValue"]),
        literal("A006", "capacity.reservationReached", True),
        literal("A007", "capacity.zeroBoundary", values["zeroBoundary"])
        if "zeroBoundary" in values
        else absent("A007", "capacity.zeroBoundary"),
        sequence("A008", "operation.downstreamEffects", values["downstreamEffects"]),
    ]


def parameters(axis: dict[str, Any], variant: dict[str, Any]) -> dict[str, Any]:
    return {
        "comparator": axis["comparator"],
        "contractStage": axis["resolvedOracle"]["contractStage"],
        "deltaId": axis["deltaId"],
        "executionClass": axis["executionClass"],
        "limitId": axis["limitId"],
        "limitValue": axis["limitValue"],
        "mode": "CAPACITY_BOUNDARY",
        "plannedAssertions": assertions(axis, variant),
        "plannedOracleId": variant["plannedOracleId"],
        "publicRenderStage": axis["resolvedOracle"]["publicRenderStage"],
        "requirementIds": axis["requirementIds"],
        "reservationPoint": axis["resolvedOracle"]["reservationPoint"],
        "resolvedCode": axis["resolvedOracle"]["code"],
        "resolvedKind": axis["resolvedOracle"]["kind"],
        "stimulusValue": variant["stimulusValue"],
        "valueEncoding": axis["valueEncoding"],
        "variant": variant["variant"],
        "zeroBoundary": axis["resolvedOracle"]["zeroBoundary"],
    }


def fixture_name(limit_id: str, variant: str) -> str:
    short = limit_id.removeprefix("assetsAndFetch.")
    short = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", short).lower()
    return f"domain-services/fixtures/cap-{short}-{variant}.json"


def common_fixture() -> dict[str, Any]:
    baseline = artifact("domain-services/baseline-v1.json")
    adapter = artifact("domain-services/observation-adapter-v1.json")
    return {
        "fixtureVersion": "renderweave-domain-services-fixture/1.0",
        "generatorProfile": PROFILE,
        "executionClass": EXECUTION_CLASS,
        "baseline": {
            "baselineId": BASELINE_ID,
            "path": baseline["path"],
            "sha256": baseline["sha256"],
            "byteLength": baseline["byteLength"],
        },
        "observationAdapter": {
            "adapterId": ADAPTER_ID,
            "path": adapter["path"],
            "sha256": adapter["sha256"],
        },
    }


def capacity_fixture(axis: dict[str, Any], variant: dict[str, Any]) -> dict[str, Any]:
    common = common_fixture()
    return {
        "fixtureVersion": common["fixtureVersion"],
        "generatorProfile": common["generatorProfile"],
        "executionClass": common["executionClass"],
        "baseline": common["baseline"],
        "scenario": {
            "mode": "CAPACITY_BOUNDARY",
            "scenarioId": variant["caseId"],
            "operationId": "main",
            "entrypoint": "ASSET_CONTENT_ADMISSION_CAPACITY_GUARD",
            "guardContractId": "renderweave-domain-asset-content-capacity-guard/1.0",
            "limitId": axis["limitId"],
            "observedValue": variant["stimulusValue"],
            "valueEncoding": axis["valueEncoding"],
            "comparator": axis["comparator"],
            "variant": variant["variant"],
            "contractStage": axis["resolvedOracle"]["contractStage"],
            "publicRenderStage": axis["resolvedOracle"]["publicRenderStage"],
            "reservationPoint": axis["resolvedOracle"]["reservationPoint"],
            "zeroBoundary": axis["resolvedOracle"]["zeroBoundary"],
            "faultSchedule": {"kind": "NONE"},
        },
        "observationAdapter": common["observationAdapter"],
        "targetContract": {
            "exactProductionGuardRequired": True,
            "duplicateGuardImplementationForbidden": True,
            "productApiSurfaceCreated": False,
            "databaseRequiredForThisProbe": False,
            "mediaPayloadRequiredForThisProbe": False,
            "fullUploadPathProvenByThisProbe": False,
        },
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
            "scenarioId": "DOMAIN-BASELINE-NOOP",
            "operationId": "main",
            "entrypoint": "BASELINE_CONTRACT_CHECK",
            "faultSchedule": {"kind": "NONE"},
        },
        "observationAdapter": common["observationAdapter"],
        "targetContract": {
            "exactProductionGuardRequired": False,
            "duplicateGuardImplementationForbidden": True,
            "productApiSurfaceCreated": False,
            "databaseRequiredForThisProbe": False,
            "mediaPayloadRequiredForThisProbe": False,
            "fullUploadPathProvenByThisProbe": False,
        },
    }


def expected_scenarios(coverage: dict[str, Any]) -> list[dict[str, Any]]:
    axes = [axis for axis in coverage["axes"] if axis["executionClass"] == EXECUTION_CLASS]
    check(len(axes) == 4, "AXIS_COUNT", str(len(axes)))
    check([axis["limitId"] for axis in axes] == LIMIT_IDS, "AXIS_ORDER", "|".join(axis["limitId"] for axis in axes))
    scenarios: list[dict[str, Any]] = []
    for axis in axes:
        check(axis["comparator"] == "MAX_INCLUSIVE", "COMPARATOR", axis["limitId"])
        check(axis["valueEncoding"] == "CANONICAL_INTEGER", "VALUE_ENCODING", axis["limitId"])
        check(axis["resolvedOracle"]["contractStage"] == "ASSET_CONTENT_ADMISSION", "CONTRACT_STAGE", axis["limitId"])
        check([item["variant"] for item in axis["variants"]] == ["below", "at", "above"], "VARIANT_ORDER", axis["limitId"])
        for variant in axis["variants"]:
            scenarios.append(
                {
                    "scenarioId": variant["caseId"],
                    "mode": "CAPACITY_BOUNDARY",
                    "parameters": parameters(axis, variant),
                    "fixtureArtifactPath": fixture_name(axis["limitId"], variant["variant"]),
                }
            )
    named_path = "domain-services/fixtures/named-domain-baseline-noop.json"
    named_artifact = artifact(named_path)
    scenarios.append(
        {
            "scenarioId": "DOMAIN-BASELINE-NOOP",
            "mode": "NAMED_SCENARIO",
            "parameters": {
                "mode": "NAMED_SCENARIO",
                "scenarioId": "DOMAIN-BASELINE-NOOP",
                "fixtureArtifactPath": named_path,
                "fixtureArtifactSha256": named_artifact["sha256"],
                "expectedObservationProfile": ADAPTER_ID,
            },
            "fixtureArtifactPath": named_path,
        }
    )
    return scenarios


def validate_contract_and_adapter() -> None:
    contract = read_json("domain-services/fixture-contract-v1.json")
    check(contract["status"] == "FROZEN_STATIC_FIXTURE_CONTRACT", "CONTRACT_STATUS", contract["status"])
    check(contract["allowedModes"] == ["CAPACITY_BOUNDARY", "NAMED_SCENARIO"], "CONTRACT_MODES", str(contract["allowedModes"]))
    check(contract["capacityBoundary"]["allowedLimitIds"] == LIMIT_IDS, "CONTRACT_LIMITS", str(contract["capacityBoundary"]["allowedLimitIds"]))
    target = contract["capacityBoundary"]["targetContract"]
    check(target["exactProductionGuardRequired"] is True, "EXACT_GUARD_REQUIRED", "true")
    check(target["duplicateGuardImplementationForbidden"] is True, "DUPLICATE_GUARD_FORBIDDEN", "true")
    check(target["fullUploadPathProvenByThisProbe"] is False, "NO_UPLOAD_PROOF", "false")
    check(contract["namedScenario"]["parameterSchemaId"] == "renderweave-named-scenario-parameters/1.0", "NAMED_SCHEMA", contract["namedScenario"]["parameterSchemaId"])
    check(contract["evidenceBoundary"]["fixtureGenerationCannotProve"].find("capacity enforcement") >= 0, "STATIC_BOUNDARY", "capacity enforcement")

    profile = read_json("conformance-probe-profile-v1.json")
    adapter = read_json("domain-services/observation-adapter-v1.json")
    admitted = [probe for probe in profile["probes"] if EXECUTION_CLASS in probe["executionClasses"]]
    check(len(admitted) == 25 == adapter["mappingCount"], "ADAPTER_COUNT", f"{len(admitted)}/{adapter['mappingCount']}")
    check(adapter["expectedValuesVisibleToTarget"] is False, "NO_EXPECTED_VALUES", "false")
    check(adapter["genericJsonPathAllowed"] is False and adapter["arbitraryScriptAllowed"] is False, "CLOSED_ADAPTER", "false")
    for probe, mapping in zip(admitted, adapter["mappings"], strict=True):
        check(mapping["probeId"] == probe["probeId"], "ADAPTER_PROBE_ORDER", probe["probeId"])
        check(mapping["valueType"] == probe["valueType"], "ADAPTER_TYPE", probe["probeId"])
        check(mapping["source"] == "closedObservation." + probe["probeId"], "ADAPTER_SOURCE", probe["probeId"])
        expected_absent = "EXPLICIT_ABSENT" if "ABSENT" in probe["allowedOperators"] else "MUST_BE_PRESENT"
        check(mapping["absentPolicy"] == expected_absent, "ADAPTER_ABSENT_POLICY", probe["probeId"])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    check(encode(expected_baseline()) == raw("domain-services/baseline-v1.json"), "BASELINE_BYTES", BASELINE_ID)
    validate_contract_and_adapter()
    coverage = read_json("conformance-capacity-coverage-v1.json")
    scenarios = expected_scenarios(coverage)

    expected_catalog = {
        "artifactVersion": "renderweave-domain-services-scenario-catalog/1.0",
        "status": "FROZEN_12_CAPACITY_PLUS_1_NAMED",
        "generatorProfile": PROFILE,
        "executionClass": EXECUTION_CLASS,
        "sourceRule": "The twelve CAPACITY_BOUNDARY parameter objects are copied from the fully expanded four-axis mapping; no matrix default remains. The named scenario is closed locally.",
        "baseline": artifact("domain-services/baseline-v1.json"),
        "observationAdapter": artifact("domain-services/observation-adapter-v1.json"),
        "scenarios": scenarios,
        "scenarioCount": 13,
        "capacityScenarioCount": 12,
        "namedScenarioCount": 1,
    }
    check(encode(expected_catalog) == raw("domain-services/capacity-scenarios-v1.json"), "SCENARIO_CATALOG_BYTES", "capacity-scenarios-v1.json")

    axes = {axis["limitId"]: axis for axis in coverage["axes"] if axis["executionClass"] == EXECUTION_CLASS}
    fixture_artifacts: list[dict[str, Any]] = []
    golden_scenarios: list[dict[str, Any]] = []
    for scenario in scenarios:
        if scenario["mode"] == "CAPACITY_BOUNDARY":
            axis = axes[scenario["parameters"]["limitId"]]
            variant = next(item for item in axis["variants"] if item["variant"] == scenario["parameters"]["variant"])
            expected_fixture = capacity_fixture(axis, variant)
        else:
            expected_fixture = named_fixture()
        relative = scenario["fixtureArtifactPath"]
        fixture_bytes = raw(relative)
        check(encode(expected_fixture) == fixture_bytes, "FIXTURE_BYTES", scenario["scenarioId"])
        fixture_artifact = artifact(relative)
        fixture_artifacts.append(fixture_artifact)
        golden_scenarios.append(
            {
                "scenarioId": scenario["scenarioId"],
                "mode": scenario["mode"],
                "parameters": scenario["parameters"],
                "expectedFixtureArtifact": fixture_artifact,
            }
        )
        fixture_text = fixture_bytes.decode("utf-8")
        for forbidden in OMITTED_EXPECTATION_KEYS:
            check(f'"{forbidden}"' not in fixture_text, "FIXTURE_EXPECTATION_LEAK", f"{scenario['scenarioId']}:{forbidden}")

    expected_goldens = {
        "artifactVersion": "renderweave-domain-services-generator-goldens/1.0",
        "generatorProfile": PROFILE,
        "executionClass": EXECUTION_CLASS,
        "baseline": artifact("domain-services/baseline-v1.json"),
        "observationAdapter": artifact("domain-services/observation-adapter-v1.json"),
        "scenarios": golden_scenarios,
        "goldenCount": 13,
        "capacityGoldenCount": 12,
        "namedGoldenCount": 1,
    }
    check(encode(expected_goldens) == raw("domain-services/generator-goldens-v1.json"), "GOLDEN_BYTES", "generator-goldens-v1.json")

    expected_target = {
        "artifactVersion": "renderweave-domain-services-generator-target/1.0",
        "targetId": "DOMAIN_SERVICES_GENERATOR_TARGET::FIXTURE::1.0",
        "generatorProfile": PROFILE,
        "status": "FROZEN_STATIC_GENERATOR_TARGET",
        "implementationRevision": "domain-services-fixture-generator/1.0",
        "entrypoint": artifact("domain-services/generate-domain-services-fixtures.mjs"),
        "baseline": artifact("domain-services/baseline-v1.json"),
        "fixtureContract": artifact("domain-services/fixture-contract-v1.json"),
        "observationAdapter": artifact("domain-services/observation-adapter-v1.json"),
        "scenarioCatalog": artifact("domain-services/capacity-scenarios-v1.json"),
        "goldenVectors": artifact("domain-services/generator-goldens-v1.json"),
        "fixtureArtifacts": fixture_artifacts,
        "expectedScenarioCount": 13,
        "productTarget": False,
        "productExecutionAllowed": False,
        "networkReadsAllowed": False,
        "environmentReadsAllowed": False,
        "currentTimeReadsAllowed": False,
    }
    check(encode(expected_target) == raw("domain-services/generator-target-manifest-v1.json"), "TARGET_BYTES", "generator-target-manifest-v1.json")

    expected_implementation = {
        "artifactVersion": "renderweave-domain-services-generator-implementation/1.0",
        "generatorProfile": PROFILE,
        "status": "FROZEN_GOLDENS_PRESENT_STATIC_ONLY",
        "implementationRevision": "domain-services-fixture-generator/1.0",
        "runtime": "Node.js >=24",
        "entrypoint": artifact("domain-services/generate-domain-services-fixtures.mjs"),
        "baseline": artifact("domain-services/baseline-v1.json"),
        "fixtureContract": artifact("domain-services/fixture-contract-v1.json"),
        "observationAdapter": artifact("domain-services/observation-adapter-v1.json"),
        "scenarioCatalog": artifact("domain-services/capacity-scenarios-v1.json"),
        "goldenVectors": artifact("domain-services/generator-goldens-v1.json"),
        "targetManifest": artifact("domain-services/generator-target-manifest-v1.json"),
        "omittedExpectationKeys": [
            "expectedTerminal",
            "expectedAssertions",
            "plannedAssertions",
            "plannedOracleId",
            "requirementIds",
            "resolvedCode",
            "resolvedKind",
        ],
        "productExecutionAllowed": False,
        "environmentReadsAllowed": False,
        "networkReadsAllowed": False,
        "currentTimeReadsAllowed": False,
        "hiddenDefaultsAllowed": False,
    }
    check(encode(expected_implementation) == raw("domain-services/generator-implementation-manifest-v1.json"), "IMPLEMENTATION_BYTES", "generator-implementation-manifest-v1.json")

    result = {
        "resultVersion": "renderweave-domain-services-fixture-generator-result/1.0",
        "executorId": "DOMAIN_SERVICES_FIXTURE_GENERATOR::PYTHON::1.0",
        "role": "independent-domain-services-fixture-generator-replayer",
        "status": "PASS" if not failures else "FAIL",
        "checkCount": checks,
        "failureCount": len(failures),
        "runtime": f"CPython {platform.python_version()}",
        "generatorTargetSha256": artifact("domain-services/generator-target-manifest-v1.json")["sha256"],
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
