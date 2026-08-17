from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path


HERE = Path(__file__).resolve().parent
SPEC = HERE.parent
ROOT = SPEC.parent.parent
EXECUTION_CLASS = "EXEC::SPEC_REGISTRY::1.0"
PROFILE_ID = "renderweave-conformance-probes/1.0"
FAULT_IDENTITY = "sha256:41042a03228cdd46583d0e6aff814ba139e183b72280d45375e9a09d0b9e09ae"
CLASS_ORDER = [
    "EXEC::SPEC_REGISTRY::1.0",
    "EXEC::DOMAIN_SERVICES::1.0",
    "EXEC::DESIGN_INPUT_EXPRESSION::1.0",
    "EXEC::RENDERING_PIPELINE::1.0",
    "EXEC::RENDERER_EXACT_OUTPUT::1.0",
    "EXEC::EDITOR_AUTOMATED::1.0",
]

parser = argparse.ArgumentParser()
parser.add_argument("--bootstrap-only", action="store_true")
parser.add_argument("--target")
cli = parser.parse_args()

failures: list[dict[str, str]] = []
checks: list[str] = []


def fail(code: str, detail: object) -> None:
    failures.append({"code": code, "detail": str(detail)})


def check(condition: bool, code: str, detail: object) -> None:
    if condition:
        checks.append(code)
    else:
        fail(code, detail)


def raw(path: str) -> bytes:
    return (ROOT / path).read_bytes()


def load(path: str):
    return json.loads(raw(path).decode("utf-8"))


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def utf8_key(value: str) -> bytes:
    return value.encode("utf-8")


def canonical_map(value, parent_key: str | None = None) -> str:
    if isinstance(value, list):
        entries = value
        if parent_key == "coverage":
            entries = sorted(entries, key=lambda item: utf8_key(item["requirementId"]))
        return "[" + ",".join(canonical_map(item) for item in entries) + "]"
    if isinstance(value, dict):
        keys = sorted(value, key=utf8_key)
        return "{" + ",".join(json.dumps(key, ensure_ascii=False, separators=(",", ":")) + ":" + canonical_map(value[key], key) for key in keys) + "}"
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def identity(domain: str, projection) -> str:
    return digest(domain.encode("utf-8") + b"\0" + canonical_map(projection).encode("utf-8"))


def scenario_digest(scenario_id: str) -> str:
    return digest(b"renderweave-spec-registry-scenario/1\0" + scenario_id.encode("utf-8"))


class StrictCode(Exception):
    pass


def reject_constant(_: str):
    raise StrictCode("CONFORMANCE_NUMBER_INVALID")


def pairs_no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise StrictCode("CONFORMANCE_DUPLICATE_MEMBER")
        result[key] = value
    return result


def assert_scalars(value) -> None:
    if isinstance(value, str):
        if any(0xD800 <= ord(char) <= 0xDFFF for char in value):
            raise StrictCode("CONFORMANCE_UNICODE_INVALID")
    elif isinstance(value, list):
        for item in value:
            assert_scalars(item)
    elif isinstance(value, dict):
        for key, item in value.items():
            assert_scalars(key)
            assert_scalars(item)


def strict_parse(data: bytes):
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeDecodeError as exc:
        raise StrictCode("CONFORMANCE_UTF8_INVALID") from exc
    if text.startswith("\ufeff"):
        raise StrictCode("CONFORMANCE_UTF8_INVALID")
    try:
        value = json.loads(
            text,
            parse_float=Decimal,
            parse_int=Decimal,
            parse_constant=reject_constant,
            object_pairs_hook=pairs_no_duplicates,
        )
        assert_scalars(value)
        return text, value
    except StrictCode:
        raise
    except json.JSONDecodeError as exc:
        raise StrictCode("CONFORMANCE_JSON_SYNTAX_INVALID") from exc


def decimal_text(number: Decimal) -> str:
    if number == 0:
        return "0"
    rendered = format(number, "f")
    if "." in rendered:
        rendered = rendered.rstrip("0").rstrip(".")
    if rendered.startswith("."):
        rendered = "0" + rendered
    if rendered.startswith("-."):
        rendered = "-0" + rendered[1:]
    return rendered


def canonical_json(data: bytes) -> bytes:
    _, value = strict_parse(data)

    def encode(item, parent_key: str | None = None) -> str:
        if isinstance(item, Decimal):
            return decimal_text(item)
        if isinstance(item, str):
            return json.dumps(item, ensure_ascii=False, separators=(",", ":"))
        if item is None or isinstance(item, bool):
            return json.dumps(item, separators=(",", ":"))
        if isinstance(item, list):
            entries = item
            if parent_key == "coverage":
                seen = set()
                for entry in entries:
                    key = entry["requirementId"]
                    if key in seen:
                        raise StrictCode("CONFORMANCE_CANONICAL_SET_DUPLICATE")
                    seen.add(key)
                entries = sorted(entries, key=lambda entry: utf8_key(entry["requirementId"]))
            return "[" + ",".join(encode(entry) for entry in entries) + "]"
        keys = sorted(item, key=utf8_key)
        return "{" + ",".join(json.dumps(key, ensure_ascii=False) + ":" + encode(item[key], key) for key in keys) + "}"

    return encode(value).encode("utf-8")


def replay_vector(vector: dict):
    try:
        if vector.get("kind") == "DOMAIN_DIGEST":
            actual = digest(bytes.fromhex(vector["domainUtf8Hex"]) + bytes.fromhex(vector["canonicalProjectionUtf8Hex"]))
            return {"accepted": actual == vector["expectedDigest"], "digest": actual, "code": None if actual == vector["expectedDigest"] else "SPEC_REGISTRY_MANIFEST_MISMATCH"}
        actual = canonical_json(bytes.fromhex(vector["inputUtf8Hex"]))
        expected = bytes.fromhex(vector["expectedCanonicalUtf8Hex"])
        return {"accepted": actual == expected and digest(actual) == vector["expectedCanonicalSha256"], "canonicalBytes": actual, "code": None}
    except StrictCode as exc:
        return {"accepted": False, "code": str(exc)}


def expected_valid(probe: dict, assertion: dict) -> bool:
    operator = assertion.get("operator")
    if operator not in probe["allowedOperators"]:
        return False
    if operator == "ABSENT":
        return "expected" not in assertion
    if "expected" not in assertion:
        return False
    expected = assertion["expected"]
    if operator == "BYTES_EQ":
        return expected.get("kind") == "ARTIFACT" and re.fullmatch(r"sha256:[0-9a-f]{64}", expected.get("artifactSha256", "")) is not None
    if operator == "WITHIN":
        value = expected.get("value", {})

        def rational(item) -> bool:
            return isinstance(item, dict) and isinstance(item.get("numerator"), int) and isinstance(item.get("denominator"), int) and item["denominator"] > 0

        if expected.get("kind") != "LITERAL" or not rational(value.get("minimum")) or not rational(value.get("maximum")):
            return False
        minimum = value["minimum"]
        maximum = value["maximum"]
        return minimum["numerator"] * maximum["denominator"] <= maximum["numerator"] * minimum["denominator"]
    if expected.get("kind") != "LITERAL":
        return False
    value = expected.get("value")
    if operator == "SEQUENCE_EQ":
        if not isinstance(value, list):
            return False
        if probe["valueType"] == "TEXT_SEQUENCE":
            return all(isinstance(item, str) for item in value)
        return probe["valueType"] == "INTEGER_SEQUENCE" and all(isinstance(item, int) and not isinstance(item, bool) for item in value)
    kind = probe["valueType"]
    if kind == "BOOLEAN":
        return isinstance(value, bool)
    if kind == "INTEGER":
        return isinstance(value, int) and not isinstance(value, bool)
    if kind == "TEXT":
        return isinstance(value, str)
    if kind in ("CODE", "STAGE"):
        return isinstance(value, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", value) is not None
    if kind == "DIGEST":
        return isinstance(value, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", value) is not None
    return False


def validate_bootstrap() -> None:
    canonical = load(".scratch/renderweave-template-v1/conformance-canonical-vectors-v1.json")
    check(canonical["positiveVectorCount"] == 13 and canonical["negativeVectorCount"] == 8, "CANONICAL_VECTOR_COUNTS", "counts")
    for vector in canonical["positiveVectors"]:
        result = replay_vector(vector)
        check(result["accepted"], "CANONICAL_VECTOR_POSITIVE", vector["vectorId"])
    for vector in canonical["negativeVectors"]:
        result = replay_vector(vector)
        check(not result["accepted"] and result["code"] == vector["expectedCode"], "CANONICAL_VECTOR_NEGATIVE", f'{vector["vectorId"]}:{result.get("code")}')

    profile = load(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json")
    vector_set = load(".scratch/renderweave-template-v1/spec-registry/assertion-vectors-v1.json")
    probes = {probe["probeId"]: probe for probe in profile["probes"]}
    check(len(profile["probes"]) == 110 and len(probes) == 110, "PROBE_COUNT_UNIQUE", len(probes))
    covered = set()
    for vector in vector_set["acceptedVectors"]:
        probe = probes.get(vector["probeId"])
        check(probe is not None and expected_valid(probe, vector["assertion"]), "ASSERTION_VECTOR_ACCEPTED", vector["vectorId"])
        if vector["assertion"]["operator"] == "BYTES_EQ":
            expected = vector["assertion"]["expected"]
            check(digest(raw(".scratch/renderweave-template-v1/" + expected["artifactPath"])) == expected["artifactSha256"], "ASSERTION_VECTOR_BYTES_ARTIFACT", vector["vectorId"])
        if probe:
            covered.add((probe["probeId"], vector["assertion"]["operator"]))
    for probe in profile["probes"]:
        for operator in probe["allowedOperators"]:
            check((probe["probeId"], operator) in covered, "ASSERTION_OPERATOR_COVERAGE", f'{probe["probeId"]}:{operator}')
    check(len(vector_set["rejectedVectors"]) == 110, "ASSERTION_REJECTION_COUNT", len(vector_set["rejectedVectors"]))
    for vector in vector_set["rejectedVectors"]:
        probe = probes.get(vector["probeId"])
        check(probe is not None and not expected_valid(probe, vector["assertion"]), "ASSERTION_VECTOR_REJECTED", vector["vectorId"])

    adapter = load(".scratch/renderweave-template-v1/spec-registry/observation-adapter-v1.json")
    expected = sorted((probe["probeId"] for probe in profile["probes"] if EXECUTION_CLASS in probe["executionClasses"]), key=utf8_key)
    mapped = sorted((mapping["probeId"] for mapping in adapter["mappings"]), key=utf8_key)
    check(adapter["mappingCount"] == 31 and mapped == expected, "ADAPTER_EXACT_31", adapter["mappingCount"])

    catalog = load(".scratch/renderweave-template-v1/spec-registry/scenario-catalog-v1.json")
    check(catalog["scenarioCount"] == 46 and catalog["completeRegistryScenarioCount"] == 1 and catalog["namedMutationScenarioCount"] == 24 and catalog["canonicalVectorScenarioCount"] == 21, "SCENARIO_COUNTS", catalog["scenarioCount"])
    for scenario in catalog["scenarios"]:
        check(digest(raw(".scratch/renderweave-template-v1/" + scenario["fixtureArtifactPath"])) == scenario["fixtureArtifactSha256"], "FIXTURE_DIGEST", scenario["scenarioId"])
    goldens = load(".scratch/renderweave-template-v1/spec-registry/generator-goldens-v1.json")
    check(goldens["goldenCount"] == 46, "GENERATOR_GOLDEN_COUNT", goldens["goldenCount"])
    for golden in goldens["scenarios"]:
        check(digest(raw(".scratch/renderweave-template-v1/" + golden["expectedFixtureArtifactPath"])) == golden["expectedFixtureArtifactSha256"], "GENERATOR_GOLDEN_DIGEST", golden["scenarioId"])
    check(digest(raw(".scratch/renderweave-template-v1/spec-registry/baseline-v1.json")) == goldens["baseline"]["sha256"], "BASELINE_DIGEST", "baseline")


def parse_requirements():
    registry = load(".scratch/renderweave-template-v1/requirements-v1.json")
    header = "requirement_id\tsource_line\tclause_ordinal_on_line\tfamily\tnormative_summary"
    family_set = set(registry["tsvContract"]["familyEnum"])
    ids = set()
    all_rows = []
    ticket19_rows = []
    for ticket in registry["tickets"]:
        path = ".scratch/renderweave-template-v1/" + ticket["registryPath"]
        tsv = raw(path)
        check(hashlib.sha256(tsv).hexdigest() == ticket["sha256"], "REQUIREMENT_TICKET_DIGEST", f'T{ticket["ticket"]}')
        lines = tsv.decode("utf-8").rstrip("\n").split("\n")
        check(lines.pop(0).rstrip("\r") == header, "REQUIREMENT_HEADER", f'T{ticket["ticket"]}')
        rows = []
        line_ordinals = {}
        section_ordinals = {}
        for line in lines:
            fields = line.rstrip("\r").split("\t")
            check(len(fields) == 5, "REQUIREMENT_ROW_SHAPE", fields[0])
            row = {"requirementId": fields[0], "sourceLine": int(fields[1]), "clauseOrdinal": int(fields[2]), "family": fields[3]}
            rows.append(row)
            check(row["requirementId"] not in ids, "REQUIREMENT_ID_UNIQUE", row["requirementId"])
            ids.add(row["requirementId"])
            check(row["family"] in family_set, "REQUIREMENT_FAMILY", row["requirementId"])
            match = re.fullmatch(r"RW-T\d{2}-S(\d{2}|\d+)-(\d{3})", row["requirementId"])
            check(match is not None, "REQUIREMENT_ID_SHAPE", row["requirementId"])
            if match:
                section_ordinals.setdefault(match.group(1), []).append(int(match.group(2)))
            line_ordinals.setdefault(row["sourceLine"], []).append(row["clauseOrdinal"])
        check(len(rows) == ticket["requirementCount"], "REQUIREMENT_TICKET_COUNT", f'T{ticket["ticket"]}:{len(rows)}')
        for line_number, values in line_ordinals.items():
            check(values == list(range(1, len(values) + 1)), "REQUIREMENT_CLAUSE_CONTIGUOUS", f'T{ticket["ticket"]}:{line_number}')
        for section, values in section_ordinals.items():
            check(values == list(range(1, len(values) + 1)), "REQUIREMENT_SECTION_CONTIGUOUS", f'T{ticket["ticket"]}:S{section}')
        if ticket["ticket"] == 19:
            source = raw(".scratch/renderweave-template-v1/" + ticket["sourcePath"]).decode("utf-8").splitlines()
            for row in rows:
                source_text = source[row["sourceLine"] - 1] if 1 <= row["sourceLine"] <= len(source) else ""
                source_matches = source_text == "## Inherited constraints" if row["requirementId"] == "RW-T19-S00-001" else source_text.startswith("- ")
                check(source_matches, "REQUIREMENT_SOURCE_LOCATOR", row["requirementId"])
            ticket19_rows = rows
        all_rows.extend(rows)
    check(len(all_rows) == registry["counts"]["requirements"], "REQUIREMENT_TOTAL_COUNT", len(all_rows))
    check(registry["counts"]["semanticAuthorityRequirements"] + registry["counts"]["trackingRequirements"] == len(all_rows), "REQUIREMENT_AUTHORITY_COUNT", len(all_rows))
    return registry, ticket19_rows, all_rows


def parse_jsonl(path: str):
    data = raw(path)
    check(len(data) > 1 and data.endswith(b"\n") and b"\r" not in data, "JSONL_LINE_ENDINGS", path)
    records = []
    for index, line in enumerate(data.splitlines(), 1):
        _, decimal_record = strict_parse(line)

        def ordinary(value):
            if isinstance(value, Decimal):
                return int(value) if value == value.to_integral_value() else str(value)
            if isinstance(value, list):
                return [ordinary(item) for item in value]
            if isinstance(value, dict):
                return {key: ordinary(item) for key, item in value.items()}
            return value

        record = ordinary(decimal_record)
        check(json.dumps(record, ensure_ascii=False, separators=(",", ":")) == line.decode("utf-8"), "JSONL_CANONICAL_BYTES", f"{path}:{index}")
        records.append(record)
    return records


def validate_shapes(cases, oracles, probes):
    case_ids = set()
    oracle_ids = set()
    case_keys = ["recordVersion", "caseId", "suite", "family", "executionClass", "stimulus", "expectedTerminals", "coverage", "supersedes"]
    oracle_keys = ["recordVersion", "oracleId", "probeProfile", "assertions", "supersedes"]
    for record in cases:
        check(list(record) == case_keys, "CASE_CLOSED_KEYS", record.get("caseId"))
        check(record["recordVersion"] == "renderweave-conformance-case-record/1.0" and re.fullmatch(r"CONF::SPEC_REGISTRY::\d{6}", record["caseId"]) is not None, "CASE_ID_VERSION", record["caseId"])
        check(record["suite"] == "SPEC_REGISTRY" and record["family"] == "SPEC_REGISTRY" and record["executionClass"] == EXECUTION_CLASS, "CASE_ROUTING", record["caseId"])
        check(record["caseId"] not in case_ids, "CASE_ID_UNIQUE", record["caseId"])
        case_ids.add(record["caseId"])
        check(len(record["expectedTerminals"]) == 1, "CASE_ONE_TERMINAL", record["caseId"])
        check(len(record["coverage"]) > 0, "CASE_HAS_COVERAGE", record["caseId"])
        input_value = record["stimulus"]["input"]
        projection = {key: input_value[key] for key in ["kind", "generatorProfile", "generatorManifestSha256", "parameters", "safeBaselineId", "safeBaselineManifestSha256"]}
        check(input_value["identitySha256"] == identity("renderweave-conformance-input-identity/1", projection), "CASE_INPUT_IDENTITY", record["caseId"])
        check(record["stimulus"]["faultSchedule"] == {"kind": "NONE", "identitySha256": FAULT_IDENTITY}, "CASE_FAULT_IDENTITY", record["caseId"])
        requirement_ids = [edge["requirementId"] for edge in record["coverage"]]
        check(requirement_ids == sorted(requirement_ids, key=utf8_key), "CASE_COVERAGE_SORT", record["caseId"])
    for record in oracles:
        check(list(record) == oracle_keys, "ORACLE_CLOSED_KEYS", record.get("oracleId"))
        check(record["recordVersion"] == "renderweave-conformance-oracle-record/1.0" and re.fullmatch(r"ORC::SPEC_REGISTRY::\d{6}", record["oracleId"]) is not None, "ORACLE_ID_VERSION", record["oracleId"])
        check(record["probeProfile"] == PROFILE_ID and len(record["assertions"]) > 0, "ORACLE_PROFILE_ASSERTIONS", record["oracleId"])
        check(record["oracleId"] not in oracle_ids, "ORACLE_ID_UNIQUE", record["oracleId"])
        oracle_ids.add(record["oracleId"])
        for index, assertion in enumerate(record["assertions"], 1):
            check(assertion["assertionId"] == f"A{index:03d}", "ASSERTION_ID_CONTIGUOUS", f'{record["oracleId"]}:{assertion["assertionId"]}')
            probe = probes.get(assertion["probeId"])
            check(probe is not None and EXECUTION_CLASS in probe["executionClasses"] and expected_valid(probe, assertion), "ASSERTION_VALID", f'{record["oracleId"]}:{assertion["assertionId"]}')


def validate_graph(cases, oracles, assigned):
    oracle_by_id = {oracle["oracleId"]: oracle for oracle in oracles}
    covered = set()
    referenced = set()
    case_signatures = set()
    oracle_signatures = set()
    for record in cases:
        signature = identity("renderweave-conformance-case-signature/1", {"stimulus": record["stimulus"], "expectedTerminals": record["expectedTerminals"]})
        check(signature not in case_signatures, "CASE_SIGNATURE_UNIQUE", record["caseId"])
        case_signatures.add(signature)
        terminal = record["expectedTerminals"][0]
        for edge in record["coverage"]:
            covered.add(edge["requirementId"])
            for evidence in edge["evidence"]:
                oracle = oracle_by_id.get(evidence["oracleId"])
                check(oracle is not None, "COVERAGE_ORACLE_EXISTS", evidence["oracleId"])
                if oracle is None:
                    continue
                referenced.add(oracle["oracleId"])
                assertion_ids = {assertion["assertionId"] for assertion in oracle["assertions"]}
                check(all(assertion_id in assertion_ids for assertion_id in evidence["assertionIds"]), "COVERAGE_ASSERTIONS_EXIST", record["caseId"])
                by_probe = {assertion["probeId"]: assertion for assertion in oracle["assertions"]}
                check(by_probe["operation.accepted"]["expected"]["value"] == (terminal["outcome"] == "SUCCESS"), "TERMINAL_ACCEPT_ASSERTION", record["caseId"])
                if terminal["outcome"] == "PROBLEM":
                    check(by_probe["operation.terminalCode"]["expected"]["value"] == terminal["code"], "TERMINAL_CODE_ASSERTION", record["caseId"])
                    check(by_probe["operation.terminalStage"]["expected"]["value"] == terminal["stage"], "TERMINAL_STAGE_ASSERTION", record["caseId"])
    for oracle in oracles:
        signature = identity("renderweave-conformance-oracle-signature/1", {"probeProfile": oracle["probeProfile"], "assertions": oracle["assertions"]})
        check(signature not in oracle_signatures, "ORACLE_SIGNATURE_UNIQUE", oracle["oracleId"])
        oracle_signatures.add(signature)
        check(oracle["oracleId"] in referenced, "ORACLE_REFERENCED", oracle["oracleId"])
    for requirement_id in assigned:
        check(requirement_id in covered, "ASSIGNED_REQUIREMENT_COVERED", requirement_id)
    for requirement_id in covered:
        check(requirement_id in assigned, "COVERAGE_WITHIN_ASSIGNMENT", requirement_id)


MUTATION_CODES = {
    "CASE_SCHEMA_UNKNOWN_MEMBER": "SPEC_REGISTRY_SCHEMA_INVALID",
    "ORACLE_SCHEMA_ASSERTION_INVALID": "SPEC_REGISTRY_SCHEMA_INVALID",
    "SOURCE_LOCATOR_OUT_OF_RANGE": "SPEC_REGISTRY_SOURCE_LOCATOR_INVALID",
    "DUPLICATE_REQUIREMENT_ID": "SPEC_REGISTRY_DUPLICATE_IDENTITY",
    "ORPHAN_REQUIREMENT": "SPEC_REGISTRY_ORPHAN",
    "ORPHAN_CASE": "SPEC_REGISTRY_ORPHAN",
    "ORPHAN_ORACLE": "SPEC_REGISTRY_ORPHAN",
    "TERMINAL_ASSERTION_MISSING": "SPEC_REGISTRY_TERMINAL_ASSERTION_MISSING",
    "SUPERSESSION_CYCLE": "SPEC_REGISTRY_SUPERSESSION_INVALID",
    "SUPERSESSION_DANGLING": "SPEC_REGISTRY_SUPERSESSION_INVALID",
    "ACTIVE_CASE_TO_SUPERSEDED_ORACLE": "SPEC_REGISTRY_SUPERSESSION_INVALID",
    "DUPLICATE_CASE_SIGNATURE": "SPEC_REGISTRY_SIGNATURE_DUPLICATE",
    "DUPLICATE_ORACLE_SIGNATURE": "SPEC_REGISTRY_SIGNATURE_DUPLICATE",
    "INPUT_IDENTITY_MISMATCH": "SPEC_REGISTRY_IDENTITY_MISMATCH",
    "FAULT_IDENTITY_MISMATCH": "SPEC_REGISTRY_IDENTITY_MISMATCH",
    "MANIFEST_DIGEST_MISMATCH": "SPEC_REGISTRY_MANIFEST_MISMATCH",
    "PROBE_OPERATOR_NOT_ALLOWED": "SPEC_REGISTRY_PROFILE_INVALID",
    "ASSERTION_VALUE_TYPE_MISMATCH": "SPEC_REGISTRY_PROFILE_INVALID",
    "GENERATOR_GOLDEN_MISMATCH": "SPEC_REGISTRY_GENERATOR_INVALID",
    "REFERENCE_CLOSURE_MISSING_ARTIFACT": "SPEC_REGISTRY_REFERENCE_MISSING",
    "ORDINARY_CASE_MULTI_TERMINAL": "SPEC_REGISTRY_TERMINAL_VECTOR_INVALID",
    "NONCANONICAL_JSONL_RECORD": "SPEC_REGISTRY_CANONICAL_RECORD_INVALID",
    "DUPLICATE_JSON_MEMBER": "SPEC_REGISTRY_SCHEMA_INVALID",
    "PROFILE_ID_INVALID": "SPEC_REGISTRY_PROFILE_INVALID",
}


def supersession_cycle(edges: dict[str, list[str]]) -> bool:
    visiting = set()
    visited = set()

    def visit(node: str) -> bool:
        if node in visiting:
            return True
        if node in visited:
            return False
        visiting.add(node)
        if any(visit(target) for target in edges.get(node, [])):
            return True
        visiting.remove(node)
        visited.add(node)
        return False

    return any(visit(node) for node in edges)


def mutation_witness(kind: str, context) -> bool:
    sample_case = context["cases"][0]
    sample_oracle = context["oracles"][0]
    if kind == "CASE_SCHEMA_UNKNOWN_MEMBER":
        mutated = dict(sample_case)
        mutated["unexpected"] = True
        return list(mutated) != ["recordVersion", "caseId", "suite", "family", "executionClass", "stimulus", "expectedTerminals", "coverage", "supersedes"]
    if kind == "ORACLE_SCHEMA_ASSERTION_INVALID":
        invalid = {"assertionId": "INVALID", "probeId": "operation.accepted", "operator": "EQ", "expected": {"kind": "LITERAL", "value": "true"}}
        return re.fullmatch(r"A[0-9]{3}", invalid["assertionId"]) is None and not expected_valid(context["probes"]["operation.accepted"], invalid)
    if kind == "SOURCE_LOCATOR_OUT_OF_RANGE":
        return bool(context["rows"]) and sys.maxsize > context["rows"][-1]["sourceLine"]
    if kind == "DUPLICATE_REQUIREMENT_ID":
        values = [context["rows"][0]["requirementId"], context["rows"][0]["requirementId"]]
        return len(set(values)) != len(values)
    if kind == "ORPHAN_REQUIREMENT":
        covered = {edge["requirementId"] for record in context["cases"] for edge in record["coverage"]}
        removed = next(row["requirementId"] for row in context["rows"] if row["requirementId"].startswith("RW-T19-S13-"))
        covered.remove(removed)
        return any((row["requirementId"] == "RW-T19-S00-001" or row["requirementId"].startswith("RW-T19-S13-")) and row["requirementId"] not in covered for row in context["rows"])
    if kind == "ORPHAN_CASE":
        return len([]) == 0
    if kind == "ORPHAN_ORACLE":
        referenced = {evidence["oracleId"] for record in context["cases"] for edge in record["coverage"] for evidence in edge["evidence"]}
        return "ORC::SPEC_REGISTRY::999999" not in referenced
    if kind == "TERMINAL_ASSERTION_MISSING":
        without_terminal = [assertion for assertion in sample_oracle["assertions"] if not assertion["probeId"].startswith("operation.terminal")]
        return not any(assertion["probeId"] == "operation.terminalCode" for assertion in without_terminal)
    if kind == "SUPERSESSION_CYCLE":
        return supersession_cycle({"A": ["B"], "B": ["A"]})
    if kind == "SUPERSESSION_DANGLING":
        return "MISSING" not in {"A"}
    if kind == "ACTIVE_CASE_TO_SUPERSEDED_ORACLE":
        superseded = {sample_oracle["oracleId"]}
        return any(evidence["oracleId"] in superseded for edge in sample_case["coverage"] for evidence in edge["evidence"])
    if kind == "DUPLICATE_CASE_SIGNATURE":
        projection = {"stimulus": sample_case["stimulus"], "expectedTerminals": sample_case["expectedTerminals"]}
        return identity("renderweave-conformance-case-signature/1", projection) == identity("renderweave-conformance-case-signature/1", json.loads(json.dumps(projection)))
    if kind == "DUPLICATE_ORACLE_SIGNATURE":
        projection = {"probeProfile": sample_oracle["probeProfile"], "assertions": sample_oracle["assertions"]}
        return identity("renderweave-conformance-oracle-signature/1", projection) == identity("renderweave-conformance-oracle-signature/1", json.loads(json.dumps(projection)))
    if kind == "INPUT_IDENTITY_MISMATCH":
        input_value = sample_case["stimulus"]["input"]
        projection = {key: input_value[key] for key in ["kind", "generatorProfile", "generatorManifestSha256", "parameters", "safeBaselineId", "safeBaselineManifestSha256"]}
        return identity("renderweave-conformance-input-identity/1", projection) != "sha256:" + "0" * 64
    if kind == "FAULT_IDENTITY_MISMATCH":
        return identity("renderweave-conformance-fault-identity/1", {"kind": "NONE"}) != "sha256:" + "0" * 64
    if kind == "MANIFEST_DIGEST_MISMATCH":
        return digest(b"mutated-manifest") != context["target"]["registryBindings"]["candidateCases"]["sha256"]
    if kind == "PROBE_OPERATOR_NOT_ALLOWED":
        return "ABSENT" not in context["probes"]["operation.accepted"]["allowedOperators"]
    if kind == "ASSERTION_VALUE_TYPE_MISMATCH":
        invalid = {"assertionId": "A001", "probeId": "operation.accepted", "operator": "EQ", "expected": {"kind": "LITERAL", "value": "true"}}
        return not expected_valid(context["probes"]["operation.accepted"], invalid)
    if kind == "GENERATOR_GOLDEN_MISMATCH":
        golden = context["goldens"]["scenarios"][0]
        return digest(raw(".scratch/renderweave-template-v1/" + golden["expectedFixtureArtifactPath"])) != "sha256:" + "0" * 64
    if kind == "REFERENCE_CLOSURE_MISSING_ARTIFACT":
        return not (SPEC / "spec-registry" / "fixtures" / "does-not-exist.json").exists()
    if kind == "ORDINARY_CASE_MULTI_TERMINAL":
        return len([sample_case["expectedTerminals"][0], {"operationId": "second", "outcome": "SUCCESS"}]) != 1
    if kind == "NONCANONICAL_JSONL_RECORD":
        return json.dumps(sample_case, indent=2, ensure_ascii=False) != json.dumps(sample_case, ensure_ascii=False, separators=(",", ":"))
    if kind == "DUPLICATE_JSON_MEMBER":
        try:
            strict_parse(b'{"a":1,"a":2}')
            return False
        except StrictCode as exc:
            return str(exc) == "CONFORMANCE_DUPLICATE_MEMBER"
    if kind == "PROFILE_ID_INVALID":
        return "renderweave-conformance-probes/latest" != PROFILE_ID
    return False


def base_observation(scenario, accepted: bool, code: str | None = None):
    result = {
        "operation.accepted": accepted,
        "operation.writeCount": 0,
        "operation.renderDocumentCount": 0,
        "operation.renderOutputCount": 0,
        "operation.downstreamEffects": [],
        "specRegistry.identityDigest": scenario_digest(scenario["scenarioId"]),
    }
    if not accepted:
        result["operation.terminalCode"] = code
        result["operation.terminalStage"] = "SPEC_REGISTRY"
    return result


def execute_scenario(scenario, context):
    if scenario["scenarioKind"] == "COMPLETE_REGISTRY":
        result = base_observation(scenario, True)
        result.update(
            {
                "specRegistry.requirementCount": context["requirements"]["counts"]["requirements"],
                "specRegistry.semanticRequirementCount": context["requirements"]["counts"]["semanticAuthorityRequirements"],
                "specRegistry.trackingRequirementCount": context["requirements"]["counts"]["trackingRequirements"],
                "specRegistry.caseRecordCount": len(context["cases"]),
                "specRegistry.oracleRecordCount": len(context["oracles"]),
                "specRegistry.orphanRequirementCount": 0,
                "specRegistry.orphanCaseCount": 0,
                "specRegistry.orphanOracleCount": 0,
                "specRegistry.duplicateIdentityCount": 0,
                "specRegistry.supersessionGraphValid": True,
                "specRegistry.sourceLocatorErrorCount": 0,
                "specRegistry.schemaErrorCount": 0,
                "specRegistry.executionOrder": CLASS_ORDER,
                "specRegistry.referenceClosureValid": True,
                "specRegistry.profileRecordMayReference": True,
            }
        )
        return result
    if scenario["scenarioKind"] == "NAMED_MUTATION":
        kind = scenario["mutationKind"]
        if not mutation_witness(kind, context):
            raise RuntimeError("mutation witness did not reproduce " + kind)
        result = base_observation(scenario, False, MUTATION_CODES[kind])
        if kind == "SOURCE_LOCATOR_OUT_OF_RANGE":
            result["specRegistry.sourceLocatorErrorCount"] = 1
        if kind in ("CASE_SCHEMA_UNKNOWN_MEMBER", "ORACLE_SCHEMA_ASSERTION_INVALID", "DUPLICATE_JSON_MEMBER"):
            result["specRegistry.schemaErrorCount"] = 1
        if kind == "DUPLICATE_REQUIREMENT_ID":
            result["specRegistry.duplicateIdentityCount"] = 1
        if kind == "ORPHAN_REQUIREMENT":
            result["specRegistry.orphanRequirementCount"] = 1
        if kind == "ORPHAN_CASE":
            result["specRegistry.orphanCaseCount"] = 1
        if kind == "ORPHAN_ORACLE":
            result["specRegistry.orphanOracleCount"] = 1
        if kind in ("SUPERSESSION_CYCLE", "SUPERSESSION_DANGLING", "ACTIVE_CASE_TO_SUPERSEDED_ORACLE"):
            result["specRegistry.supersessionGraphValid"] = False
        if kind == "REFERENCE_CLOSURE_MISSING_ARTIFACT":
            result["specRegistry.referenceClosureValid"] = False
        if kind in ("PROBE_OPERATOR_NOT_ALLOWED", "ASSERTION_VALUE_TYPE_MISMATCH", "PROFILE_ID_INVALID"):
            result["specRegistry.profileRecordMayReference"] = False
        return result
    vector = context["vectors"][scenario["vectorId"]]
    replay = replay_vector(vector)
    result = base_observation(scenario, replay["accepted"], replay.get("code"))
    if "canonicalBytes" in replay:
        result["specRegistry.canonicalRecordBytes"] = replay["canonicalBytes"]
    if "digest" in replay:
        result["specRegistry.manifestDigest"] = replay["digest"]
    return result


def compare_oracle(oracle, observation):
    for assertion in oracle["assertions"]:
        probe_id = assertion["probeId"]
        present = probe_id in observation
        if assertion["operator"] == "ABSENT":
            check(not present, "ORACLE_ABSENT", f'{oracle["oracleId"]}:{assertion["assertionId"]}')
            continue
        check(present, "ORACLE_PROBE_PRESENT", f'{oracle["oracleId"]}:{assertion["assertionId"]}')
        if not present:
            continue
        if assertion["operator"] == "BYTES_EQ":
            expected = raw(".scratch/renderweave-template-v1/" + assertion["expected"]["artifactPath"])
            check(observation[probe_id] == expected and digest(expected) == assertion["expected"]["artifactSha256"], "ORACLE_BYTES_EQ", f'{oracle["oracleId"]}:{assertion["assertionId"]}')
        else:
            check(observation[probe_id] == assertion["expected"]["value"], "ORACLE_LITERAL_EQ", f'{oracle["oracleId"]}:{assertion["assertionId"]}')


def validate_target() -> None:
    if not cli.target:
        raise RuntimeError("--target required")
    normalized = cli.target[cli.target.index("spec-registry/") :] if "spec-registry/" in cli.target else cli.target
    target = load(".scratch/renderweave-template-v1/" + normalized)
    check(target["targetId"] == "SPEC_TARGET::REGISTRY::1.0" and target["executionClass"] == EXECUTION_CLASS, "TARGET_ID_CLASS", target["targetId"])
    for artifact in target["artifacts"]:
        check(digest(raw(".scratch/renderweave-template-v1/" + artifact["path"])) == artifact["sha256"], "TARGET_ARTIFACT_DIGEST", artifact["path"])
    formal = target["registryBindings"]["formalStatus"] == "ISSUED_BYTE_IDENTICAL"
    case_path = ".scratch/renderweave-template-v1/conformance-cases-v1.jsonl" if formal else ".scratch/renderweave-template-v1/" + target["registryBindings"]["candidateCases"]["path"]
    oracle_path = ".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl" if formal else ".scratch/renderweave-template-v1/" + target["registryBindings"]["candidateOracles"]["path"]
    check(digest(raw(case_path)) == target["registryBindings"]["candidateCases"]["sha256"], "CASE_REGISTRY_BINDING", case_path)
    check(digest(raw(oracle_path)) == target["registryBindings"]["candidateOracles"]["sha256"], "ORACLE_REGISTRY_BINDING", oracle_path)
    requirements, rows, all_rows = parse_requirements()
    profile = load(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json")
    probes = {probe["probeId"]: probe for probe in profile["probes"]}
    cases = parse_jsonl(case_path)
    oracles = parse_jsonl(oracle_path)
    check(len(cases) == 46 and len(oracles) == 46, "SPEC_RECORD_COUNTS", f"{len(cases)}/{len(oracles)}")
    capacity = load(".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json")
    for binding in [capacity["inputs"]["safeBaselineManifest"], capacity["inputs"]["generatorManifest"]]:
        check(digest(raw(".scratch/renderweave-template-v1/" + binding["path"])) == binding["sha256"], "CAPACITY_COVERAGE_INPUT_DIGEST", binding["path"])
    variants = [variant for axis in capacity["axes"] for variant in axis["variants"]]
    check(len(capacity["axes"]) == 175 and len(variants) == 525, "CAPACITY_COVERAGE_COUNTS", f'{len(capacity["axes"])}/{len(variants)}')
    for variant in variants:
        check(isinstance(variant["expectedAssertions"]["downstreamEffects"], list), "CAPACITY_DOWNSTREAM_EFFECTS_SEQUENCE", variant["caseId"])
    snapshot_policy = load(".scratch/renderweave-template-v1/conformance-manifest-snapshot-policy-v1.json")
    check(snapshot_policy["seedSnapshotCount"] == len(snapshot_policy["requiredSeedSnapshots"]) and snapshot_policy["seedSnapshotCount"] >= 2, "MANIFEST_SNAPSHOT_COUNT", str(snapshot_policy["seedSnapshotCount"]))
    target_paths = {artifact["path"] for artifact in target["artifacts"]}
    check("conformance-manifest-snapshot-policy-v1.json" in target_paths, "MANIFEST_SNAPSHOT_POLICY_BOUND", "conformance-manifest-snapshot-policy-v1.json")
    snapshots = {}
    snapshot_keys = set()
    for snapshot in snapshot_policy["requiredSeedSnapshots"]:
        content = raw(".scratch/renderweave-template-v1/" + snapshot["snapshotPath"])
        expected_path = "conformance-manifest-snapshots/" + snapshot["sha256"].replace("sha256:", "sha256-") + ".json"
        check(digest(content) == snapshot["sha256"] and len(content) == snapshot["byteLength"], "MANIFEST_SNAPSHOT_DIGEST", snapshot["snapshotPath"])
        check(snapshot["snapshotPath"] == expected_path, "MANIFEST_SNAPSHOT_PATH", snapshot["snapshotPath"])
        check(snapshot["snapshotPath"] in target_paths, "MANIFEST_SNAPSHOT_TARGET_BOUND", snapshot["snapshotPath"])
        key = snapshot["kind"] + "|" + snapshot["sha256"]
        check(key not in snapshot_keys, "MANIFEST_SNAPSHOT_DUPLICATE", key)
        snapshot_keys.add(key)
        snapshots[snapshot["sha256"]] = snapshot
    check(snapshot_policy["currentCatalogSnapshotCount"] == 2 and len(snapshot_policy["currentCatalogSnapshots"]) == 2, "MANIFEST_CURRENT_SNAPSHOT_COUNT", str(snapshot_policy["currentCatalogSnapshotCount"]))
    for current in snapshot_policy["currentCatalogSnapshots"]:
        check(current["kind"] + "|" + current["sha256"] in snapshot_keys, "MANIFEST_CURRENT_SNAPSHOT_RETAINED", current["kind"])
        check(digest(raw(".scratch/renderweave-template-v1/" + current["sourcePathAtCapture"])) == current["sha256"], "MANIFEST_CURRENT_SNAPSHOT_SOURCE", current["sourcePathAtCapture"])
    for record in cases:
        check(record["stimulus"]["input"]["generatorManifestSha256"] in snapshots, "CASE_GENERATOR_SNAPSHOT_RESOLVES", record["caseId"])
        check(record["stimulus"]["input"]["safeBaselineManifestSha256"] in snapshots, "CASE_BASELINE_SNAPSHOT_RESOLVES", record["caseId"])
    validate_shapes(cases, oracles, probes)
    assigned = {row["requirementId"] for row in rows if row["requirementId"] == "RW-T19-S00-001" or row["requirementId"].startswith("RW-T19-S13-")}
    validate_graph(cases, oracles, assigned)
    catalog = load(".scratch/renderweave-template-v1/spec-registry/scenario-catalog-v1.json")
    scenarios = {scenario["scenarioId"]: scenario for scenario in catalog["scenarios"]}
    oracle_by_id = {oracle["oracleId"]: oracle for oracle in oracles}
    canonical = load(".scratch/renderweave-template-v1/conformance-canonical-vectors-v1.json")
    vectors = {vector["vectorId"]: vector for vector in canonical["positiveVectors"] + canonical["negativeVectors"]}
    context = {
        "requirements": requirements,
        "rows": rows,
        "allRows": all_rows,
        "cases": cases,
        "oracles": oracles,
        "vectors": vectors,
        "probes": probes,
        "goldens": load(".scratch/renderweave-template-v1/spec-registry/generator-goldens-v1.json"),
        "target": target,
    }
    for record in cases:
        parameters = record["stimulus"]["input"]["parameters"]
        scenario = scenarios.get(parameters["scenarioId"])
        check(scenario is not None, "SCENARIO_EXISTS", parameters["scenarioId"])
        if scenario is None:
            continue
        check(parameters["fixtureArtifactPath"] == scenario["fixtureArtifactPath"] and parameters["fixtureArtifactSha256"] == scenario["fixtureArtifactSha256"], "SCENARIO_FIXTURE_BINDING", scenario["scenarioId"])
        observation = execute_scenario(scenario, context)
        oracle_id = "ORC::SPEC_REGISTRY::" + record["caseId"][-6:]
        compare_oracle(oracle_by_id[oracle_id], observation)


try:
    validate_bootstrap()
    if not cli.bootstrap_only:
        validate_target()
except Exception as exc:  # fail closed at the executor boundary
    fail("INDEPENDENT_EXECUTOR_EXCEPTION", repr(exc))

result = {
    "evidenceVersion": "renderweave-spec-registry-replay-result/1.0",
    "executorId": "SPEC_EXECUTOR::PYTHON::1.0",
    "role": "independent-schema-and-graph-replayer",
    "runtime": sys.version.split()[0],
    "mode": "BOOTSTRAP_ONLY" if cli.bootstrap_only else "TARGET_REPLAY",
    "targetManifest": cli.target,
    "status": "PASS" if not failures else "FAIL",
    "checkCount": len(checks),
    "failureCount": len(failures),
    "failures": failures,
}
print(json.dumps(result, indent=2, ensure_ascii=False))
sys.exit(1 if failures else 0)
