from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
SPEC = HERE.parent
ROOT = SPEC.parent.parent
MANIFEST = Path(".scratch/renderweave-template-v1/capacity-boundary/materialization-manifest-v1.json")
FAULT_IDENTITY = "sha256:41042a03228cdd46583d0e6aff814ba139e183b72280d45375e9a09d0b9e09ae"
CLASS_AXIS_COUNTS = {
    "EXEC::DOMAIN_SERVICES::1.0": 4,
    "EXEC::DESIGN_INPUT_EXPRESSION::1.0": 65,
    "EXEC::RENDERING_PIPELINE::1.0": 52,
    "EXEC::RENDERER_EXACT_OUTPUT::1.0": 54,
}


checks = 0
failures: list[dict[str, str]] = []


def check(condition: bool, code: str, detail: str) -> None:
    global checks
    checks += 1
    if not condition:
        failures.append({"code": code, "detail": detail})


def file_bytes(path: str | Path) -> bytes:
    return (ROOT / path).read_bytes()


def sha(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate member: {key}")
        result[key] = value
    return result


def decode_json_bytes(data: bytes, source: str) -> Any:
    check(not data.startswith(b"\xef\xbb\xbf"), "JSON_BOM", source)
    try:
        text = data.decode("utf-8", "strict")
        return json.loads(text, object_pairs_hook=reject_duplicate_pairs, parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)))
    except Exception as error:  # pragma: no cover - reported as evidence
        failures.append({"code": "JSON_PARSE", "detail": f"{source}:{error}"})
        return None


def read_json(path: str | Path) -> Any:
    return decode_json_bytes(file_bytes(path), str(path))


def compact(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def canonical_projection(value: Any) -> str:
    if isinstance(value, list):
        return "[" + ",".join(canonical_projection(item) for item in value) + "]"
    if isinstance(value, dict):
        keys = sorted(value.keys(), key=lambda item: item.encode("utf-8"))
        return "{" + ",".join(compact(key) + ":" + canonical_projection(value[key]) for key in keys) + "}"
    return compact(value)


def identity(domain: str, projection: Any) -> str:
    return sha((domain + "\0" + canonical_projection(projection)).encode("utf-8"))


def read_jsonl(path: str) -> list[dict[str, Any]]:
    data = file_bytes(path)
    check(data.endswith(b"\n") and not data.endswith(b"\n\n"), "JSONL_TERMINATOR", path)
    records: list[dict[str, Any]] = []
    for ordinal, line in enumerate(data.splitlines(), start=1):
        check(len(line) > 0, "JSONL_BLANK", f"{path}:{ordinal}")
        record = decode_json_bytes(line, f"{path}:{ordinal}")
        if isinstance(record, dict):
            check(compact(record).encode("utf-8") == line, "JSONL_NONCANONICAL", f"{path}:{ordinal}")
            records.append(record)
    return records


def requirements() -> set[str]:
    registry = read_json(".scratch/renderweave-template-v1/requirements-v1.json")
    result: set[str] = set()
    expected_header = "requirement_id\tsource_line\tclause_ordinal_on_line\tfamily\tnormative_summary"
    for ticket in registry["tickets"]:
        path = ".scratch/renderweave-template-v1/" + ticket["registryPath"]
        data = file_bytes(path)
        check(hashlib.sha256(data).hexdigest() == ticket["sha256"], "TSV_DIGEST", ticket["registryPath"])
        rows = data.decode("utf-8").rstrip("\r\n").splitlines()
        check(rows[0] == expected_header, "TSV_HEADER", ticket["registryPath"])
        for row in rows[1:]:
            columns = row.split("\t")
            check(len(columns) == 5, "TSV_COLUMNS", ticket["registryPath"])
            if columns:
                check(columns[0] not in result, "REQUIREMENT_DUPLICATE", columns[0])
                result.add(columns[0])
    check(len(result) == registry["counts"]["requirements"], "REQUIREMENT_COUNT", str(len(result)))
    return result


def literal(probe: str, value: Any) -> dict[str, Any]:
    return {"probeId": probe, "operator": "EQ", "expected": {"kind": "LITERAL", "value": value}}


def absent(probe: str) -> dict[str, Any]:
    return {"probeId": probe, "operator": "ABSENT"}


def sequence(probe: str, value: list[str]) -> dict[str, Any]:
    return {"probeId": probe, "operator": "SEQUENCE_EQ", "expected": {"kind": "LITERAL", "value": value}}


def assertions_for(axis: dict[str, Any], variant: dict[str, Any]) -> list[dict[str, Any]]:
    values = variant["expectedAssertions"]
    raw_assertions = [
        literal("operation.accepted", values["accepted"]),
        literal("operation.terminalCode", values["terminalCode"]) if "terminalCode" in values else absent("operation.terminalCode"),
        literal("operation.terminalStage", values["terminalStage"]) if "terminalStage" in values else absent("operation.terminalStage"),
        literal("capacity.limitId", axis["limitId"]),
        literal("capacity.observedValue", variant["stimulusValue"]),
        literal("capacity.reservationReached", True),
        literal("capacity.zeroBoundary", values["zeroBoundary"]) if "zeroBoundary" in values else absent("capacity.zeroBoundary"),
        sequence("operation.downstreamEffects", values["downstreamEffects"]),
    ]
    return [{"assertionId": f"A{index:03d}", **assertion} for index, assertion in enumerate(raw_assertions, start=1)]


def assertion_valid(probe: dict[str, Any], assertion: dict[str, Any]) -> bool:
    if assertion["operator"] not in probe["allowedOperators"]:
        return False
    if assertion["operator"] == "ABSENT":
        return "expected" not in assertion
    expected = assertion.get("expected")
    if not isinstance(expected, dict) or expected.get("kind") != "LITERAL":
        return False
    value = expected.get("value")
    if assertion["operator"] == "SEQUENCE_EQ":
        return probe["valueType"] == "TEXT_SEQUENCE" and isinstance(value, list) and all(isinstance(item, str) for item in value)
    if assertion["operator"] != "EQ":
        return False
    if probe["valueType"] == "BOOLEAN":
        return isinstance(value, bool)
    return probe["valueType"] in {"TEXT", "CODE", "STAGE", "DIGEST"} and isinstance(value, str)


def expected_parameters(axis: dict[str, Any], variant: dict[str, Any], assertions: list[dict[str, Any]]) -> dict[str, Any]:
    values = {
        "mode": "CAPACITY_BOUNDARY",
        "limitId": axis["limitId"],
        "variant": variant["variant"],
        "limitValue": axis["limitValue"],
        "valueEncoding": axis["valueEncoding"],
        "comparator": axis["comparator"],
        "deltaId": axis["deltaId"],
        "stimulusValue": variant["stimulusValue"],
        "resolvedKind": axis["resolvedOracle"]["kind"],
        "resolvedCode": axis["resolvedOracle"]["code"],
        "contractStage": axis["resolvedOracle"]["contractStage"],
        "publicRenderStage": axis["resolvedOracle"]["publicRenderStage"],
        "reservationPoint": axis["resolvedOracle"]["reservationPoint"],
        "zeroBoundary": axis["resolvedOracle"]["zeroBoundary"],
        "executionClass": axis["executionClass"],
        "requirementIds": sorted(axis["requirementIds"], key=lambda item: item.encode("utf-8")),
        "plannedOracleId": variant["plannedOracleId"],
        "plannedAssertions": assertions,
    }
    return {key: values[key] for key in sorted(values.keys(), key=lambda item: item.encode("utf-8"))}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    manifest = read_json(MANIFEST)
    check(manifest["status"] == "STATIC_SHAPE_CANDIDATES_READY_RECORD_ISSUANCE_BLOCKED", "MANIFEST_STATUS", manifest["status"])
    check(manifest["evidenceBoundary"]["staticMaterializationOnly"] is True, "STATIC_ONLY", "true")
    check(manifest["evidenceBoundary"]["recordIssuanceAllowed"] is False, "ISSUANCE_BLOCKED", "false")
    for binding in manifest["inputs"].values():
        data = file_bytes(".scratch/renderweave-template-v1/" + binding["path"])
        check(sha(data) == binding["sha256"], "INPUT_DIGEST", binding["path"])
        check(len(data) == binding["byteLength"], "INPUT_LENGTH", binding["path"])
    for binding in manifest["outputs"].values():
        data = file_bytes(".scratch/renderweave-template-v1/" + binding["path"])
        check(sha(data) == binding["sha256"], "OUTPUT_DIGEST", binding["path"])
        check(len(data) == binding["byteLength"], "OUTPUT_LENGTH", binding["path"])

    coverage = read_json(".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json")
    probe_profile = read_json(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json")
    generator_catalog = read_json(".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json")
    baseline_catalog = read_json(".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json")
    execution_catalog = read_json(".scratch/renderweave-template-v1/conformance-execution-classes-v1.json")
    domain_goldens = read_json(".scratch/renderweave-template-v1/domain-services/generator-goldens-v1.json")
    domain_golden_by_scenario = {entry["scenarioId"]: entry for entry in domain_goldens["scenarios"]}
    design_goldens = read_json(".scratch/renderweave-template-v1/design-input-expression/generator-goldens-v1.json")
    design_golden_by_scenario = {entry["scenarioId"]: entry for entry in design_goldens["scenarios"]}
    rendering_goldens = read_json(".scratch/renderweave-template-v1/rendering-pipeline/generator-goldens-v1.json")
    rendering_golden_by_scenario = {entry["scenarioId"]: entry for entry in rendering_goldens["scenarios"]}
    renderer_goldens = read_json(".scratch/renderweave-template-v1/renderer-exact-output/generator-goldens-v1.json")
    renderer_golden_by_scenario = {entry["scenarioId"]: entry for entry in renderer_goldens["scenarios"]}
    static_goldens_by_class = {
        "EXEC::DOMAIN_SERVICES::1.0": domain_golden_by_scenario,
        "EXEC::DESIGN_INPUT_EXPRESSION::1.0": design_golden_by_scenario,
        "EXEC::RENDERING_PIPELINE::1.0": rendering_golden_by_scenario,
        "EXEC::RENDERER_EXACT_OUTPUT::1.0": renderer_golden_by_scenario,
    }
    snapshot_policy = read_json(".scratch/renderweave-template-v1/conformance-manifest-snapshot-policy-v1.json")
    known_requirements = requirements()
    generator_digest = sha(file_bytes(".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json"))
    baseline_digest = sha(file_bytes(".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json"))
    check(coverage["inputs"]["generatorManifest"]["sha256"] == generator_digest, "COVERAGE_GENERATOR_DIGEST", generator_digest)
    check(coverage["inputs"]["safeBaselineManifest"]["sha256"] == baseline_digest, "COVERAGE_BASELINE_DIGEST", baseline_digest)
    check(snapshot_policy["seedSnapshotCount"] == len(snapshot_policy["requiredSeedSnapshots"]) and snapshot_policy["seedSnapshotCount"] >= 2, "SNAPSHOT_COUNT", str(snapshot_policy["seedSnapshotCount"]))
    snapshot_keys: set[str] = set()
    for snapshot in snapshot_policy["requiredSeedSnapshots"]:
        data = file_bytes(".scratch/renderweave-template-v1/" + snapshot["snapshotPath"])
        expected_path = "conformance-manifest-snapshots/" + snapshot["sha256"].replace("sha256:", "sha256-") + ".json"
        check(sha(data) == snapshot["sha256"], "SNAPSHOT_DIGEST", snapshot["snapshotPath"])
        check(snapshot["snapshotPath"] == expected_path, "SNAPSHOT_PATH", snapshot["snapshotPath"])
        check(len(data) == snapshot["byteLength"], "SNAPSHOT_LENGTH", snapshot["snapshotPath"])
        key = snapshot["kind"] + "|" + snapshot["sha256"]
        check(key not in snapshot_keys, "SNAPSHOT_DUPLICATE", key)
        snapshot_keys.add(key)
    check(snapshot_policy["currentCatalogSnapshotCount"] == 2 == len(snapshot_policy["currentCatalogSnapshots"]), "CURRENT_SNAPSHOT_COUNT", str(snapshot_policy["currentCatalogSnapshotCount"]))
    for current in snapshot_policy["currentCatalogSnapshots"]:
        check(current["kind"] + "|" + current["sha256"] in snapshot_keys, "CURRENT_SNAPSHOT_RETAINED", current["kind"])
        check(sha(file_bytes(".scratch/renderweave-template-v1/" + current["sourcePathAtCapture"])) == current["sha256"], "CURRENT_SNAPSHOT_SOURCE", current["sourcePathAtCapture"])

    cases = read_jsonl(".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-cases-v1.jsonl")
    oracles = read_jsonl(".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-oracles-v1.jsonl")
    check(len(cases) == 525 == len(oracles), "CANDIDATE_COUNTS", f"{len(cases)}/{len(oracles)}")
    case_by_id = {record["caseId"]: record for record in cases}
    oracle_by_id = {record["oracleId"]: record for record in oracles}
    check(len(case_by_id) == len(cases), "CASE_ID_UNIQUE", str(len(case_by_id)))
    check(len(oracle_by_id) == len(oracles), "ORACLE_ID_UNIQUE", str(len(oracle_by_id)))
    probes = {probe["probeId"]: probe for probe in probe_profile["probes"]}
    generators = {entry["executionClass"]: entry for entry in generator_catalog["generators"]}
    baselines = {entry["executionClass"]: entry for entry in baseline_catalog["baselines"]}
    executions = {entry["executionClass"]: entry for entry in execution_catalog["classes"]}
    class_counts: dict[str, int] = {}
    seen_case_signatures: set[str] = set()
    seen_oracle_signatures: set[str] = set()
    oracle_ordinal = 1

    for axis in coverage["axes"]:
        execution_class = axis["executionClass"]
        class_counts[execution_class] = class_counts.get(execution_class, 0) + 1
        check(execution_class in CLASS_AXIS_COUNTS, "AXIS_CLASS", axis["limitId"])
        check([variant["variant"] for variant in axis["variants"]] == ["below", "at", "above"], "VARIANT_ORDER", axis["limitId"])
        check(len(axis["requirementIds"]) == len(set(axis["requirementIds"])) > 0, "AXIS_REQUIREMENTS", axis["limitId"])
        for requirement_id in axis["requirementIds"]:
            check(requirement_id in known_requirements, "REQUIREMENT_EXISTS", requirement_id)
        binding = coverage["classBindings"][execution_class]
        check(generators[execution_class]["generatorProfile"] == binding["generatorProfile"], "GENERATOR_BINDING", axis["limitId"])
        check(baselines[execution_class]["baselineId"] == binding["safeBaselineId"], "BASELINE_BINDING", axis["limitId"])

        for variant in axis["variants"]:
            case_record = case_by_id.get(variant["caseId"])
            oracle = oracle_by_id.get(variant["plannedOracleId"])
            check(case_record is not None, "CASE_PRESENT", variant["caseId"])
            check(oracle is not None, "ORACLE_PRESENT", variant["plannedOracleId"])
            if case_record is None or oracle is None:
                continue
            check(variant["plannedOracleId"] == f"ORC::CAPACITY::{oracle_ordinal:06d}", "ORACLE_CONTINUITY", variant["plannedOracleId"])
            oracle_ordinal += 1
            check(list(case_record.keys()) == ["recordVersion", "caseId", "suite", "executionClass", "stimulus", "expectedTerminals", "coverage", "supersedes"], "CASE_KEYS", case_record["caseId"])
            check(re.fullmatch(r"CAP::[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)+::(?:below|at|above)", case_record["caseId"]) is not None, "CASE_ID_WIRE", case_record["caseId"])
            check(case_record["recordVersion"] == "renderweave-conformance-case-record/1.0" and case_record["suite"] == "CAPACITY_BOUNDARY", "CASE_VERSION_SUITE", case_record["caseId"])
            check(case_record["executionClass"] == execution_class, "CASE_CLASS", case_record["caseId"])
            check(case_record["expectedTerminals"] == [variant["expectedTerminal"]], "CASE_TERMINAL", case_record["caseId"])
            check(case_record["supersedes"] == [], "CASE_SUPERSEDES", case_record["caseId"])
            check(list(case_record["stimulus"].keys()) == ["input", "faultSchedule"], "STIMULUS_KEYS", case_record["caseId"])
            input_value = case_record["stimulus"]["input"]
            expected_input_keys = ["kind", "generatorProfile", "generatorManifestSha256", "parameters", "safeBaselineId", "safeBaselineManifestSha256", "identitySha256"]
            check(list(input_value.keys()) == expected_input_keys, "INPUT_KEYS", case_record["caseId"])
            projection = {key: input_value[key] for key in expected_input_keys[:-1]}
            check(input_value["identitySha256"] == identity("renderweave-conformance-input-identity/1", projection), "INPUT_IDENTITY", case_record["caseId"])
            check(input_value["generatorManifestSha256"] == generator_digest and input_value["safeBaselineManifestSha256"] == baseline_digest, "INPUT_MANIFESTS", case_record["caseId"])
            check(input_value["generatorProfile"] == binding["generatorProfile"] and input_value["safeBaselineId"] == binding["safeBaselineId"], "INPUT_BINDING", case_record["caseId"])
            check(case_record["stimulus"]["faultSchedule"] == {"kind": "NONE", "identitySha256": FAULT_IDENTITY}, "FAULT_IDENTITY", case_record["caseId"])

            expected_assertions = assertions_for(axis, variant)
            expected_params = expected_parameters(axis, variant, expected_assertions)
            check(input_value["parameters"] == expected_params, "PARAMETERS_EXACT", case_record["caseId"])
            static_goldens = static_goldens_by_class.get(execution_class)
            if static_goldens is not None:
                golden = static_goldens.get(case_record["caseId"])
                check(golden is not None, "STATIC_GENERATOR_GOLDEN_PRESENT", case_record["caseId"])
                if golden is not None:
                    check(golden["parameters"] == input_value["parameters"], "STATIC_GENERATOR_PARAMETERS_MATCH_CANDIDATE", case_record["caseId"])
                    fixture_binding = golden["expectedFixtureArtifact"]
                    check(sha(file_bytes(".scratch/renderweave-template-v1/" + fixture_binding["path"])) == fixture_binding["sha256"], "STATIC_GENERATOR_FIXTURE_BINDING", case_record["caseId"])
            check(list(input_value["parameters"].keys()) == sorted(input_value["parameters"].keys(), key=lambda item: item.encode("utf-8")), "PARAMETER_KEY_ORDER", case_record["caseId"])
            sorted_requirements = sorted(axis["requirementIds"], key=lambda item: item.encode("utf-8"))
            expected_coverage = [
                {
                    "requirementId": requirement_id,
                    "evidence": [{"oracleId": oracle["oracleId"], "assertionIds": [f"A{index:03d}" for index in range(1, 9)]}],
                }
                for requirement_id in sorted_requirements
            ]
            check(case_record["coverage"] == expected_coverage, "COVERAGE_EXACT", case_record["caseId"])
            check(list(oracle.keys()) == ["recordVersion", "oracleId", "probeProfile", "assertions", "supersedes"], "ORACLE_KEYS", oracle["oracleId"])
            check(re.fullmatch(r"ORC::CAPACITY::[0-9]{6}", oracle["oracleId"]) is not None, "ORACLE_ID_WIRE", oracle["oracleId"])
            check(oracle["recordVersion"] == "renderweave-conformance-oracle-record/1.0" and oracle["probeProfile"] == "renderweave-conformance-probes/1.0", "ORACLE_VERSION_PROFILE", oracle["oracleId"])
            check(oracle["assertions"] == expected_assertions and oracle["supersedes"] == [], "ORACLE_EXACT", oracle["oracleId"])
            for assertion in oracle["assertions"]:
                probe = probes.get(assertion["probeId"])
                check(probe is not None, "PROBE_EXISTS", f'{oracle["oracleId"]}:{assertion["assertionId"]}')
                if probe is not None:
                    check(execution_class in probe["executionClasses"], "PROBE_CLASS", f'{oracle["oracleId"]}:{assertion["assertionId"]}')
                    check(assertion_valid(probe, assertion), "ASSERTION_VALID", f'{oracle["oracleId"]}:{assertion["assertionId"]}')
            case_signature = identity("renderweave-conformance-case-signature/1", {"stimulus": case_record["stimulus"], "expectedTerminals": case_record["expectedTerminals"]})
            oracle_signature = identity("renderweave-conformance-oracle-signature/1", {"probeProfile": oracle["probeProfile"], "assertions": oracle["assertions"]})
            check(case_signature not in seen_case_signatures, "CASE_SIGNATURE_DUPLICATE", case_record["caseId"])
            check(oracle_signature not in seen_oracle_signatures, "ORACLE_SIGNATURE_DUPLICATE", oracle["oracleId"])
            seen_case_signatures.add(case_signature)
            seen_oracle_signatures.add(oracle_signature)

    check(oracle_ordinal == 526, "ORACLE_ORDINAL_END", str(oracle_ordinal))
    check(len(domain_golden_by_scenario) == 13, "DOMAIN_GOLDEN_COUNT", str(len(domain_golden_by_scenario)))
    check(len(design_golden_by_scenario) == 196, "DESIGN_GOLDEN_COUNT", str(len(design_golden_by_scenario)))
    check(len(rendering_golden_by_scenario) == 157, "RENDERING_GOLDEN_COUNT", str(len(rendering_golden_by_scenario)))
    check(len(renderer_golden_by_scenario) == 163, "RENDERER_GOLDEN_COUNT", str(len(renderer_golden_by_scenario)))
    for execution_class, expected_count in CLASS_AXIS_COUNTS.items():
        check(class_counts.get(execution_class) == expected_count, "CLASS_AXIS_COUNT", execution_class)
        is_static_bootstrapped = execution_class in static_goldens_by_class
        check(generators[execution_class]["recordMayReference"] is is_static_bootstrapped, "GENERATOR_REFERENCEABILITY", execution_class)
        check(baselines[execution_class]["recordMayReference"] is is_static_bootstrapped, "BASELINE_REFERENCEABILITY", execution_class)
        execution = executions[execution_class]
        check(execution.get("executable") is not True and "targetManifest" not in execution, "EXECUTION_NOT_EXECUTABLE", execution_class)
        check(("observationAdapter" in execution) is is_static_bootstrapped, "EXECUTION_ADAPTER", execution_class)
        if is_static_bootstrapped:
            bindings = [
                generators[execution_class]["implementationManifest"],
                generators[execution_class]["targetManifest"],
                generators[execution_class]["goldenVectors"],
                baselines[execution_class]["fixtureArtifact"],
                baselines[execution_class]["observationAdapter"],
                execution["observationAdapter"],
            ]
            for binding in bindings:
                check(sha(file_bytes(".scratch/renderweave-template-v1/" + binding["path"])) == binding["sha256"], "STATIC_BINDING_DIGEST", binding["path"])
        readiness = next(item for item in manifest["classReadiness"] if item["executionClass"] == execution_class)
        check(readiness["axisCount"] == expected_count and readiness["candidateCaseCount"] == expected_count * 3, "READINESS_COUNTS", execution_class)
        expected_blockers = (
            ["EXACT_TARGET_MANIFEST_PENDING", "REQUIRED_EXECUTOR_MANIFESTS_PENDING", "INDEPENDENT_EXECUTION_REPLAY_PENDING"]
            if is_static_bootstrapped
            else [
                "SAFE_BASELINE_FIXTURE_SCHEMA_AND_ADAPTER_PENDING",
                "GENERATOR_IMPLEMENTATION_TARGET_AND_GOLDENS_PENDING",
                "OBSERVATION_ADAPTER_PENDING",
                "EXACT_TARGET_MANIFEST_PENDING",
                "REQUIRED_EXECUTOR_MANIFESTS_PENDING",
                "INDEPENDENT_EXECUTION_REPLAY_PENDING",
            ]
        )
        check(readiness["blockers"] == expected_blockers and readiness["recordIssuanceAllowed"] is False, "READINESS_BLOCKERS", execution_class)

    formal_cases = read_jsonl(".scratch/renderweave-template-v1/conformance-cases-v1.jsonl")
    formal_oracles = read_jsonl(".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl")
    check(len(formal_cases) == 46 and all(not record["caseId"].startswith("CAP::") for record in formal_cases), "FORMAL_CASES_UNCHANGED", str(len(formal_cases)))
    check(len(formal_oracles) == 46 and all(not record["oracleId"].startswith("ORC::CAPACITY::") for record in formal_oracles), "FORMAL_ORACLES_UNCHANGED", str(len(formal_oracles)))
    check(manifest["formalRegistries"]["appendPerformedByThisMaterialization"] is False, "NO_FORMAL_APPEND", "false")

    result = {
        "resultVersion": "renderweave-capacity-boundary-static-replay-result/1.0",
        "executorId": "CAPACITY_STATIC_EXECUTOR::PYTHON::1.0",
        "role": "independent-capacity-materialization-replayer",
        "status": "PASS" if not failures else "FAIL",
        "checkCount": checks,
        "failureCount": len(failures),
        "failures": failures,
        "runtime": f"CPython {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
        "materializationManifestSha256": sha(file_bytes(MANIFEST)),
        "candidateCasesSha256": sha(file_bytes(".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-cases-v1.jsonl")),
        "candidateOraclesSha256": sha(file_bytes(".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-oracles-v1.jsonl")),
        "scope": "static candidate materialization only; no class executor or target invoked",
        "recordIssuanceAllowed": False,
    }
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        (ROOT / args.output).write_text(rendered, encoding="utf-8", newline="\n")
    sys.stdout.write(rendered)
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
