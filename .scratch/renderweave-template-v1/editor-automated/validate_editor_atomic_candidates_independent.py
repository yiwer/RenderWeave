from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path


HERE = Path(__file__).resolve().parent
SPEC = HERE.parent
ROOT = "editor-automated"
EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0"
CONTRACT_PATH = f"{ROOT}/atomic-candidate-contract-v1.json"
CANDIDATES_PATH = f"{ROOT}/atomic-scenario-candidates-v1.json"
AUDIT_PATH = f"{ROOT}/atomic-candidate-readiness-audit-v1.json"
RESULT_PATH = f"{ROOT}/atomic-candidate-independent-result-v1.json"
TERMINAL_ADJUDICATION_PATH = f"{ROOT}/terminal-adjudication-v1.json"
FAULT_CONTRACT_PATH = f"{ROOT}/fault-schedule-contract-v1.json"
FAULT_CATALOG_PATH = f"{ROOT}/fault-schedule-catalog-v1.json"
INPUT_FIXTURE_CONTRACT_PATH = f"{ROOT}/input-fixture-contract-v1.json"
INPUT_FIXTURE_CATALOG_PATH = f"{ROOT}/input-fixture-catalog-v1.json"
TARGET_BINDING_CONTRACT_PATH = f"{ROOT}/target-binding-contract-v1.json"
TARGET_BINDING_CATALOG_PATH = f"{ROOT}/target-binding-catalog-v1.json"
SEMANTIC_PROJECTION_CONTRACT_PATH = f"{ROOT}/semantic-projection-contract-v1.json"
SEMANTIC_PROJECTION_CATALOG_PATH = f"{ROOT}/semantic-projection-catalog-v1.json"
CONTENT_SOURCE_CONTRACT_PATH = f"{ROOT}/content-source-contract-v1.json"
CONTENT_SOURCE_CATALOG_PATH = f"{ROOT}/content-source-catalog-v1.json"
DIRTY_GUARD_CLEAN_DESIGN_PATH = f"{ROOT}/content-sources/dirty-guard-clean-baseline.design.json"
DIRTY_GUARD_WORKING_DESIGN_PATH = f"{ROOT}/content-sources/dirty-guard-working-copy.design.json"
DIRTY_GUARD_SOURCE_RECORD_PATH = f"{ROOT}/content-sources/ecs-j10-012-pa011.json"
DIRTY_GUARD_CANDIDATE_ID = "EDC::J10::012"
DIRTY_GUARD_PLAN_ASSERTION_ID = "PA011"
DIRTY_GUARD_SOURCE_SLOT_ID = "ECS::J10::012::PA011"
CANDIDATE_PROBE_PROFILE_PATH = f"{ROOT}/probe-profile-candidate-v1_1.json"
CANDIDATE_PROBE_ADJUDICATION_PATH = f"{ROOT}/probe-profile-adjudication-v1.json"
CANDIDATE_PROBE_PROFILE_ID = "renderweave-conformance-probes/1.1"

EXPECTED_BLOCKERS = [
    "EXACT_PRODUCT_FIXTURE_ARTIFACT_MISSING",
    "EXACT_BROWSER_OS_TARGET_MISSING",
    "EXECUTOR_MANIFEST_MISSING",
    "INDEPENDENT_PRODUCT_REPLAY_MISSING",
    "FAULT_SCHEDULE_ARTIFACT_MISSING",
    "EXPECTED_TERMINAL_CODE_OR_STAGE_UNBOUND",
    "TARGET_LITERAL_OR_ARTIFACT_MISSING",
    "EDITOR_PROBE_PROFILE_CANDIDATE_NOT_ISSUED",
]

EXPECTED_COMMAND_RESULTS = {
    "EDC::J02::001": "COMMAND_APPLIED",
    "EDC::J02::002": "COMMAND_APPLIED",
    "EDC::J02::003": "COMMAND_APPLIED",
    "EDC::J02::004": "COMMAND_REJECTED_ATOMIC",
    "EDC::J02::005": "COMMAND_APPLIED",
    "EDC::J02::006": "COMMAND_APPLIED",
    "EDC::J02::007": "COMMAND_APPLIED",
    "EDC::J02::008": "COMMAND_APPLIED",
    "EDC::J02::009": "COMMAND_APPLIED",
    "EDC::J03::001": "BINDING_UNBOUND",
    "EDC::J03::002": "BINDING_PRESENT",
    "EDC::J03::003": "BINDING_ABSENT",
    "EDC::J03::004": "BINDING_ERROR",
    "EDC::J03::005": "BINDING_TYPE_INVALID",
    "EDC::J03::006": "BINDING_PROPERTY_INVALID",
    "EDC::J03::007": "COMMAND_APPLIED",
    "EDC::J04::001": "COMMAND_APPLIED",
    "EDC::J04::002": "COMMAND_APPLIED",
    "EDC::J04::003": "COMMAND_REJECTED_ATOMIC",
    "EDC::J04::004": "COMMAND_REJECTED_ATOMIC",
    "EDC::J04::005": "COMMAND_REJECTED_ATOMIC",
    "EDC::J10::012": "COMMAND_REJECTED_ATOMIC",
}

EXPECTED_PREVIEW_GENERATION_DELTAS = {
    "EDC::J03::008": 1,
    "EDC::J11::001": 1,
    "EDC::J11::002": 0,
    "EDC::J11::003": 0,
    "EDC::J11::004": 1,
    "EDC::J11::005": 1,
    "EDC::J11::006": 1,
    "EDC::J11::007": 1,
    "EDC::J11::008": 1,
    "EDC::J11::009": 1,
    "EDC::J11::010": 1,
    "EDC::J11::011": 1,
    "EDC::J11::012": 1,
    "EDC::J11::013": 1,
    "EDC::J11::014": 0,
    "EDC::J11::016": 1,
    "EDC::J11::017": 0,
    "EDC::J11::018": 0,
    "EDC::J11::019": 1,
    "EDC::J11::020": 0,
}

EXPECTED_CONTENT_PREREQUISITES = {
    "editor.workingCopyDigest": ["EXACT_WORKING_COPY_CANONICAL_DESIGN_DSL_BYTES"],
    "editor.canonicalBaselineBytes": [
        "EXACT_SERVER_CANONICAL_REVISION",
        "EXACT_SERVER_CANONICAL_CONTENT_HASH",
        "EXACT_WORKING_COPY_CANONICAL_DESIGN_DSL_BYTES",
        "CANONICAL_BASELINE_PROJECTION_ENCODER",
    ],
    "editor.previewBasisDigest": [
        "EXACT_SAVED_CURRENT_IDENTITY",
        "EXACT_LOCAL_DIVERGENCE_STATE",
        "EXACT_SAMPLE_INPUT_IDENTITY",
        "EXACT_OUTPUT_PARAMETER_VECTOR",
        "EXACT_CURRENT_READINESS",
        "PREVIEW_BASIS_CANONICAL_ENCODER",
    ],
    "editor.recoveryDraftEnvelopeBytes": [
        "EXACT_RECOVERY_WORKING_COPY_DIGEST",
        "EXACT_RECOVERY_BASE_FACTS",
        "RECOVERY_ENVELOPE_CANONICAL_ENCODER",
    ],
    "editor.compatibilityOriginalDigest": ["EXACT_COMPATIBILITY_ORIGINAL_BYTES"],
    "editor.normalizationSummaryBytes": [
        "EXACT_SERVER_CANONICAL_BEFORE_AFTER_BYTES",
        "EXACT_NORMALIZATION_CATEGORY_COUNTS",
        "NORMALIZATION_SUMMARY_CANONICAL_ENCODER",
    ],
}

EXPECTED_UI_PENDING_COUNTS = {
    "editor.accessibilityTreeBytes": 17,
    "editor.announcementSequence": 1,
    "editor.focusSequence": 14,
    "editor.problemPanelBytes": 30,
}

check_count = 0


def check(condition: bool, label: str) -> None:
    global check_count
    check_count += 1
    if not condition:
        raise AssertionError(label)


def no_duplicates(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON member: {key}")
        value[key] = item
    return value


def read_json(relative_path: str):
    content = (SPEC / relative_path).read_bytes()
    check(not content.startswith(b"\xef\xbb\xbf"), f"BOM forbidden: {relative_path}")
    check(content.endswith(b"\n"), f"final LF required: {relative_path}")
    check(b"\r" not in content, f"CR forbidden: {relative_path}")
    return json.loads(content.decode("utf-8"), object_pairs_hook=no_duplicates)


def encoded(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, separators=(",", ": ")) + "\n").encode("utf-8")


def design_canonical(value) -> str:
    if value is None:
        raise ValueError("DesignDSL canonical fixture forbids null")
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        if abs(value) > 9007199254740991:
            raise ValueError("DesignDSL fixture integer exceeds independently replayable range")
        return str(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(design_canonical(item) for item in value) + "]"
    if isinstance(value, dict):
        keys = sorted(value, key=lambda item: item.encode("utf-8"))
        return "{" + ",".join(
            json.dumps(key, ensure_ascii=False, separators=(",", ":")) + ":" + design_canonical(value[key])
            for key in keys
        ) + "}"
    raise ValueError(f"unsupported DesignDSL canonical value: {type(value).__name__}")


def read_design_canonical(relative_path: str):
    content = (SPEC / relative_path).read_bytes()
    check(not content.startswith(b"\xef\xbb\xbf"), f"DesignDSL BOM forbidden: {relative_path}")
    check(b"\r" not in content and b"\n" not in content, f"DesignDSL insignificant whitespace forbidden: {relative_path}")
    value = json.loads(content.decode("utf-8"), object_pairs_hook=no_duplicates)
    check(content == design_canonical(value).encode("utf-8"), f"exact DesignDSL canonical bytes: {relative_path}")
    return value, content


def sha256(content: bytes) -> str:
    return "sha256:" + hashlib.sha256(content).hexdigest()


def artifact(relative_path: str):
    content = (SPEC / relative_path).read_bytes()
    return {"path": relative_path, "sha256": sha256(content), "byteLength": len(content)}


def stable(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def identity(domain: str, value) -> str:
    return sha256((domain + "\0" + stable(value)).encode("utf-8"))


def formal_fault_identity(value_without_identity) -> str:
    canonical = json.dumps(value_without_identity, ensure_ascii=False, separators=(",", ":"))
    return sha256(("renderweave-conformance-fault-identity/1\0" + canonical).encode("utf-8"))


def formal_input_identity(value_without_identity) -> str:
    canonical = json.dumps(value_without_identity, ensure_ascii=False, separators=(",", ":"))
    return sha256(("renderweave-conformance-input-identity/1\0" + canonical).encode("utf-8"))


def terminal_projection(terminal):
    value = {"operationId": terminal["operationId"], "outcome": terminal["outcome"]}
    if terminal["codeBinding"]["status"] == "EXACT":
        value["code"] = terminal["codeBinding"]["code"]
    else:
        check(terminal["codeBinding"]["status"] == "NOT_REQUIRED", "terminal code exact or absent")
    if terminal["stageBinding"]["status"] == "EXACT":
        value["stage"] = terminal["stageBinding"]["stage"]
    else:
        check(terminal["stageBinding"]["status"] == "NOT_REQUIRED", "terminal stage exact or absent")
    return value


def jsonl_records(relative_path: str):
    result = []
    for index, line in enumerate((SPEC / relative_path).read_text(encoding="utf-8").splitlines(), start=1):
        if not line:
            continue
        result.append(json.loads(line, object_pairs_hook=no_duplicates))
        check(result[-1] is not None, f"JSONL record {relative_path}:{index}")
    return result


def main() -> None:
    contract = read_json(CONTRACT_PATH)
    catalog = read_json(CANDIDATES_PATH)
    audit = read_json(AUDIT_PATH)
    assignment = read_json(f"{ROOT}/non-capacity-assignment-v1.json")
    probe_profile = read_json("conformance-probe-profile-v1.json")
    candidate_probe_profile = read_json(CANDIDATE_PROBE_PROFILE_PATH)
    terminal_adjudication = read_json(TERMINAL_ADJUDICATION_PATH)
    fault_contract = read_json(FAULT_CONTRACT_PATH)
    fault_catalog = read_json(FAULT_CATALOG_PATH)
    input_fixture_contract = read_json(INPUT_FIXTURE_CONTRACT_PATH)
    input_fixture_catalog = read_json(INPUT_FIXTURE_CATALOG_PATH)
    semantic_projection_contract = read_json(SEMANTIC_PROJECTION_CONTRACT_PATH)
    semantic_projection_catalog = read_json(SEMANTIC_PROJECTION_CATALOG_PATH)
    content_source_contract = read_json(CONTENT_SOURCE_CONTRACT_PATH)
    content_source_catalog = read_json(CONTENT_SOURCE_CATALOG_PATH)
    target_binding_contract = read_json(TARGET_BINDING_CONTRACT_PATH)
    target_binding_catalog = read_json(TARGET_BINDING_CATALOG_PATH)

    for path, value in [
        (CONTRACT_PATH, contract),
        (CANDIDATES_PATH, catalog),
        (AUDIT_PATH, audit),
        (TERMINAL_ADJUDICATION_PATH, terminal_adjudication),
        (FAULT_CONTRACT_PATH, fault_contract),
        (FAULT_CATALOG_PATH, fault_catalog),
        (INPUT_FIXTURE_CONTRACT_PATH, input_fixture_contract),
        (INPUT_FIXTURE_CATALOG_PATH, input_fixture_catalog),
        (SEMANTIC_PROJECTION_CONTRACT_PATH, semantic_projection_contract),
        (SEMANTIC_PROJECTION_CATALOG_PATH, semantic_projection_catalog),
        (CONTENT_SOURCE_CONTRACT_PATH, content_source_contract),
        (CONTENT_SOURCE_CATALOG_PATH, content_source_catalog),
        (TARGET_BINDING_CONTRACT_PATH, target_binding_contract),
        (TARGET_BINDING_CATALOG_PATH, target_binding_catalog),
    ]:
        check((SPEC / path).read_bytes() == encoded(value), f"canonical pretty JSON: {path}")

    check(contract["artifactVersion"] == "renderweave-editor-atomic-candidate-contract/1.5", "contract version")
    check(contract["contractId"] == "renderweave-editor-atomic-candidate/1.5", "contract id")
    check(contract["status"] == "FROZEN_PLANNING_INTERFACE_FORMAL_ISSUANCE_FORBIDDEN", "contract status")
    check(contract["executionClass"] == EXECUTION_CLASS, "contract execution class")
    check(contract["candidateIdentity"]["namespaceMeaning"] == "planning-only Editor Decomposition Candidate", "candidate namespace meaning")
    check(contract["candidateIdentity"]["formalCasePatternMatchAllowed"] is False, "formal Case match forbidden")
    check(contract["candidateIdentity"]["formalOraclePatternMatchAllowed"] is False, "formal Oracle match forbidden")
    check(contract["closedBlockers"] == EXPECTED_BLOCKERS, "closed blocker order")
    check(contract["appendBoundary"]["appendAllowedByThisContract"] is False, "append forbidden")
    check(contract["appendBoundary"]["candidateFilesAreNotRegistryRecords"] is True, "candidate non-record boundary")
    check(contract["j1Boundary"]["browserOrHumanExecutionPerformed"] is False, "J1 execution absent")
    check(contract["j1Boundary"]["mayCountAsJ1"] is False, "J1 counting forbidden")

    check(catalog["artifactVersion"] == "renderweave-editor-atomic-scenario-candidates/1.5", "catalog version")
    check(catalog["status"] == "PLANNING_CANDIDATES_ONLY_FORMAL_ISSUANCE_FORBIDDEN", "catalog status")
    check(catalog["executionClass"] == EXECUTION_CLASS, "catalog class")
    check(catalog["contract"] == artifact(CONTRACT_PATH), "catalog contract artifact")
    check(catalog["sourceAssignment"] == artifact(f"{ROOT}/non-capacity-assignment-v1.json"), "catalog assignment artifact")
    check(catalog["candidateProbeProfile"] == artifact(CANDIDATE_PROBE_PROFILE_PATH), "catalog candidate probe profile artifact")
    check(catalog["terminalAdjudication"] == artifact(TERMINAL_ADJUDICATION_PATH), "catalog terminal adjudication artifact")
    check(catalog["faultScheduleContract"] == artifact(FAULT_CONTRACT_PATH), "catalog fault contract artifact")
    check(catalog["faultScheduleCatalog"] == artifact(FAULT_CATALOG_PATH), "catalog fault catalog artifact")
    check(catalog["inputFixtureContract"] == artifact(INPUT_FIXTURE_CONTRACT_PATH), "catalog input fixture contract artifact")
    check(catalog["inputFixtureCatalog"] == artifact(INPUT_FIXTURE_CATALOG_PATH), "catalog input fixture catalog artifact")
    check(catalog["semanticProjectionContract"] == artifact(SEMANTIC_PROJECTION_CONTRACT_PATH), "catalog semantic projection contract artifact")
    check(catalog["semanticProjectionCatalog"] == artifact(SEMANTIC_PROJECTION_CATALOG_PATH), "catalog semantic projection catalog artifact")
    check(catalog["contentSourceContract"] == artifact(CONTENT_SOURCE_CONTRACT_PATH), "catalog content source contract artifact")
    check(catalog["contentSourceCatalog"] == artifact(CONTENT_SOURCE_CATALOG_PATH), "catalog content source catalog artifact")
    check(catalog["targetBindingContract"] == artifact(TARGET_BINDING_CONTRACT_PATH), "catalog target binding contract artifact")
    check(catalog["targetBindingCatalog"] == artifact(TARGET_BINDING_CATALOG_PATH), "catalog target binding catalog artifact")
    candidates = catalog["candidates"]
    check(catalog["candidateCount"] == len(candidates) == 108, "candidate count")

    check(terminal_adjudication["artifactVersion"] == "renderweave-editor-terminal-adjudication/1.0", "terminal adjudication version")
    check(terminal_adjudication["status"] == "EXACT_PLANNING_BINDINGS_PRODUCT_UNPROVEN", "terminal adjudication status")
    check(terminal_adjudication["executionClass"] == EXECUTION_CLASS, "terminal adjudication class")
    check(terminal_adjudication["counts"] == {
        "candidateTerminalCount": 108,
        "adjudicatedFromPendingCount": 33,
        "exactCodeCandidateCount": 33,
        "exactStageCandidateCount": 21,
        "pendingCodeOrStageCount": 0,
    }, "terminal adjudication counts")
    check(len(terminal_adjudication["decisions"]) == 33, "terminal adjudication decision count")
    check(len({entry["candidateId"] for entry in terminal_adjudication["decisions"]}) == 33, "terminal adjudication unique candidates")
    check(terminal_adjudication["zeroExecutionBoundary"] == {
        "productCodeChanged": False,
        "browserStarted": False,
        "networkUsed": False,
        "formalJsonlAppended": False,
        "productTerminalObserved": False,
        "j1Executed": False,
        "readyClaimed": False,
    }, "terminal adjudication zero-execution boundary")

    check(fault_contract["artifactVersion"] == "renderweave-editor-fault-schedule-contract/1.0", "fault contract version")
    check(fault_contract["profileId"] == "renderweave-editor-fault-schedule/1.0", "fault contract profile")
    check(fault_contract["status"] == "FROZEN_PLANNING_ARTIFACT_INTERFACE_PRODUCT_ADAPTER_PENDING", "fault contract status")
    check(fault_contract["executionClass"] == EXECUTION_CLASS, "fault contract class")
    check(fault_contract["artifactMembersInOrder"] == ["artifactVersion", "candidateId", "executionClass", "events", "expectedTerminal"], "fault artifact members")
    check(fault_contract["eventMembersInOrder"] == ["at", "action", "parameters"], "fault event members")
    check(fault_contract["eventOrderSemantic"] is True and fault_contract["exactOnce"] is True, "fault event semantics")
    check(len(fault_contract["allowedEventSignatures"]) > 0, "fault event catalog nonempty")
    check(fault_contract["forbiddenMechanisms"] == ["wildcard", "regex trigger", "arbitrary script", "ambient time", "ambient entropy", "network lookup", "hidden default", "unspecified extra event"], "fault forbidden mechanisms")

    check(fault_catalog["artifactVersion"] == "renderweave-editor-fault-schedule-catalog/1.0", "fault catalog version")
    check(fault_catalog["status"] == "EXACT_PLANNING_ARTIFACTS_PRODUCT_ADAPTER_UNPROVEN", "fault catalog status")
    check(fault_catalog["executionClass"] == EXECUTION_CLASS, "fault catalog class")
    check(fault_catalog["contract"] == artifact(FAULT_CONTRACT_PATH), "fault catalog contract artifact")
    none_base = {"kind": "NONE"}
    check(fault_catalog["formalNoneBinding"] == {**none_base, "identitySha256": formal_fault_identity(none_base)}, "formal NONE binding")
    check(fault_catalog["counts"] == {
        "candidateCount": 108,
        "noneCandidateCount": 71,
        "artifactCandidateCount": 37,
        "orphanArtifactCount": 0,
    }, "fault catalog counts")
    check(len(fault_catalog["artifacts"]) == 37, "fault artifact count")
    check(len({entry["candidateId"] for entry in fault_catalog["artifacts"]}) == 37, "fault artifact candidate uniqueness")
    check(len({entry["artifact"]["path"] for entry in fault_catalog["artifacts"]}) == 37, "fault artifact path uniqueness")
    for entry in fault_catalog["artifacts"]:
        path = entry["artifact"]["path"]
        value = read_json(path)
        check((SPEC / path).read_bytes() == encoded(value), f"canonical pretty JSON: {path}")
        check(entry["artifact"] == artifact(path), f"fault artifact binding: {path}")
        check(value["artifactVersion"] == "renderweave-editor-fault-schedule/1.0", f"fault artifact version: {path}")
        check(value["candidateId"] == entry["candidateId"], f"fault artifact candidate: {path}")
        check(value["executionClass"] == EXECUTION_CLASS, f"fault artifact class: {path}")
        check(len(value["events"]) > 0, f"fault artifact events: {path}")
        check(list(value) == fault_contract["artifactMembersInOrder"], f"fault artifact member order: {path}")
        check(all(list(event) == fault_contract["eventMembersInOrder"] for event in value["events"]), f"fault event member order: {path}")
        formal_base = {
            "kind": "ARTIFACT",
            "artifactPath": path,
            "artifactSha256": entry["artifact"]["sha256"],
        }
        check(entry["formalBinding"] == {**formal_base, "identitySha256": formal_fault_identity(formal_base)}, f"fault formal binding: {path}")

    check(input_fixture_contract["artifactVersion"] == "renderweave-editor-input-fixture-contract/1.0", "input fixture contract version")
    check(input_fixture_contract["profileId"] == "renderweave-editor-input-fixture/1.0", "input fixture profile")
    check(input_fixture_contract["status"] == "FROZEN_SEMANTIC_FIXTURE_INTERFACE_PRODUCT_ADAPTER_PENDING", "input fixture contract status")
    check(input_fixture_contract["executionClass"] == EXECUTION_CLASS, "input fixture execution class")
    check(input_fixture_contract["artifactMembersInOrder"] == ["artifactVersion", "candidateId", "executionClass", "baselineId", "parameters", "actionScript"], "input fixture artifact members")
    check(input_fixture_contract["actionMembersInOrder"] == ["action", "parameters"], "input fixture action members")
    check(input_fixture_contract["formalInputBindingMembersInOrder"] == ["kind", "artifactPath", "mediaType", "artifactSha256", "identitySha256"], "input fixture formal binding members")
    check(input_fixture_contract["formalInputIdentityDomain"] == "renderweave-conformance-input-identity/1", "input fixture identity domain")
    check(input_fixture_catalog["artifactVersion"] == "renderweave-editor-input-fixture-catalog/1.0", "input fixture catalog version")
    check(input_fixture_catalog["status"] == "EXACT_SEMANTIC_FIXTURES_PRODUCT_ADAPTER_UNPROVEN", "input fixture catalog status")
    check(input_fixture_catalog["contract"] == artifact(INPUT_FIXTURE_CONTRACT_PATH), "input fixture catalog contract")
    check(input_fixture_catalog["counts"] == {"candidateCount": 108, "artifactCandidateCount": 108, "orphanArtifactCount": 0}, "input fixture catalog counts")
    check(len(input_fixture_catalog["artifacts"]) == 108, "input fixture artifact count")
    check(len({entry["candidateId"] for entry in input_fixture_catalog["artifacts"]}) == 108, "input fixture candidate uniqueness")
    check(len({entry["artifact"]["path"] for entry in input_fixture_catalog["artifacts"]}) == 108, "input fixture path uniqueness")
    for entry in input_fixture_catalog["artifacts"]:
        path = entry["artifact"]["path"]
        value = read_json(path)
        check((SPEC / path).read_bytes() == encoded(value), f"canonical pretty JSON: {path}")
        check(entry["artifact"] == artifact(path), f"input fixture artifact binding: {path}")
        check(value["artifactVersion"] == "renderweave-editor-input-fixture/1.0", f"input fixture version: {path}")
        check(value["candidateId"] == entry["candidateId"], f"input fixture candidate: {path}")
        check(value["executionClass"] == EXECUTION_CLASS, f"input fixture class: {path}")
        check(list(value) == input_fixture_contract["artifactMembersInOrder"], f"input fixture member order: {path}")
        check(all(list(action) == input_fixture_contract["actionMembersInOrder"] for action in value["actionScript"]), f"input fixture action member order: {path}")
        formal_base = {
            "kind": "ARTIFACT",
            "artifactPath": path,
            "mediaType": "application/json",
            "artifactSha256": entry["artifact"]["sha256"],
        }
        check(entry["formalBinding"] == {**formal_base, "identitySha256": formal_input_identity(formal_base)}, f"input fixture formal binding: {path}")

    check(semantic_projection_contract["artifactVersion"] == "renderweave-editor-semantic-projection-contract/1.1", "semantic projection contract version")
    check(semantic_projection_contract["status"] == "FROZEN_PLANNING_PROJECTION_INTERFACE_PRODUCT_ADAPTER_PENDING", "semantic projection contract status")
    check(semantic_projection_contract["module"]["name"] == "EditorSemanticProjection", "semantic projection module name")
    check(semantic_projection_contract["commandResult"]["allowedCodes"] == [
        "COMMAND_APPLIED",
        "COMMAND_REJECTED_ATOMIC",
        "BINDING_UNBOUND",
        "BINDING_PRESENT",
        "BINDING_ABSENT",
        "BINDING_ERROR",
        "BINDING_TYPE_INVALID",
        "BINDING_PROPERTY_INVALID",
    ], "semantic command-result code catalog")
    check("currentGeneration" in semantic_projection_contract["previewEligibilityGeneration"]["initialGenerationRule"], "semantic generation initial rule")
    check("remains pending" in semantic_projection_contract["noInferenceRule"], "semantic no-inference rule")
    check(semantic_projection_contract["workingCopyAvailability"]["digestAbsent"].startswith("Raw Repair"), "semantic no-working-copy absence rule")
    check(semantic_projection_catalog["artifactVersion"] == "renderweave-editor-semantic-projection-catalog/1.1", "semantic projection catalog version")
    check(semantic_projection_catalog["status"] == "42_EXACT_SEMANTIC_LITERALS_58_CONTENT_PREREQUISITES_62_UI_OBSERVATIONS_EXCLUDED", "semantic projection catalog status")
    check(semantic_projection_catalog["contract"] == artifact(SEMANTIC_PROJECTION_CONTRACT_PATH), "semantic projection catalog contract")
    check(semantic_projection_catalog["counts"] == {
        "sourcePendingExpectationCount": 308,
        "priorMechanicalExactCount": 146,
        "semanticInputPendingExpectationCount": 162,
        "excludedUiObservedCount": 62,
        "nonUiDecisionCount": 100,
        "exactCommandResultCount": 22,
        "exactPreviewGenerationCount": 20,
        "exactLiteralBindingCount": 42,
        "contentDerivedPendingCount": 58,
    }, "semantic projection counts")
    check({entry["probeId"]: entry["count"] for entry in semantic_projection_catalog["excludedUiObservedByProbe"]} == EXPECTED_UI_PENDING_COUNTS, "semantic UI exclusions")
    check(len(semantic_projection_catalog["decisions"]) == 100, "semantic decision count")
    check(len({(entry["candidateId"], entry["planAssertionId"]) for entry in semantic_projection_catalog["decisions"]}) == 100, "semantic decision identity uniqueness")
    command_decisions = {entry["candidateId"]: entry for entry in semantic_projection_catalog["decisions"] if entry["probeId"] == "editor.commandResult"}
    check(set(command_decisions) == set(EXPECTED_COMMAND_RESULTS), "semantic command-result candidate set")
    for candidate_id, expected_code in EXPECTED_COMMAND_RESULTS.items():
        decision = command_decisions[candidate_id]
        check(decision["resolution"] == "EXACT_COMMAND_RESULT_CODE", f"semantic command resolution: {candidate_id}")
        check(decision["expected"] == {"kind": "LITERAL", "value": expected_code}, f"semantic command value: {candidate_id}")
    generation_decisions = {entry["candidateId"]: entry for entry in semantic_projection_catalog["decisions"] if entry["probeId"] == "editor.previewEligibilityGeneration"}
    check(set(generation_decisions) == set(EXPECTED_PREVIEW_GENERATION_DELTAS), "semantic generation candidate set")
    input_fixture_by_candidate = {entry["candidateId"]: read_json(entry["artifact"]["path"]) for entry in input_fixture_catalog["artifacts"]}
    for candidate_id, expected_delta in EXPECTED_PREVIEW_GENERATION_DELTAS.items():
        decision = generation_decisions[candidate_id]
        expected_initial = input_fixture_by_candidate[candidate_id]["parameters"].get("currentGeneration", 0)
        check(decision["resolution"] == "EXACT_PREVIEW_ELIGIBILITY_GENERATION", f"semantic generation resolution: {candidate_id}")
        check(decision["initialGeneration"] == expected_initial, f"semantic generation initial value: {candidate_id}")
        check(decision["generationDelta"] == expected_delta, f"semantic generation delta: {candidate_id}")
        check(decision["expected"] == {"kind": "LITERAL", "value": expected_initial + expected_delta}, f"semantic generation value: {candidate_id}")
    content_decisions = [entry for entry in semantic_projection_catalog["decisions"] if entry["resolution"] == "WAIT_FOR_EXACT_CONTENT_PREREQUISITES"]
    check(len(content_decisions) == 58, "semantic content prerequisite decision count")
    for decision in content_decisions:
        check(decision["probeId"] in EXPECTED_CONTENT_PREREQUISITES, f"semantic content probe: {decision['probeId']}")
        check(decision["prerequisiteIds"] == EXPECTED_CONTENT_PREREQUISITES[decision["probeId"]], f"semantic content prerequisites: {decision['candidateId']}:{decision['planAssertionId']}")

    check(content_source_contract["artifactVersion"] == "renderweave-editor-content-source-contract/1.1", "content source contract version")
    check(content_source_contract["profileId"] == "renderweave-editor-content-source/1.1", "content source profile")
    check(content_source_contract["module"]["name"] == "EditorContentSource", "content source module name")
    check(content_source_contract["canonicalFacts"]["designDslCanonicalProfile"] == "renderweave-design-c14n/1.0", "content source DesignDSL canonical profile")
    check("renderweave-design-content/1\\0" in content_source_contract["canonicalFacts"]["contentHash"], "content source contentHash domain")
    check(content_source_contract["bindingUnion"]["UNBOUND"]["reasonCode"] == "EXACT_EDITOR_CONTENT_SOURCE_ARTIFACT_MISSING", "content source unbound reason")
    check(content_source_contract["sourceRecord"]["profile"] == "renderweave-editor-content-source-record/1.0", "content source record profile")
    check(content_source_catalog["artifactVersion"] == "renderweave-editor-content-source-catalog/1.1", "content source catalog version")
    check(content_source_catalog["status"] == "ONE_IMMUTABLE_SPEC_FIXTURE_BOUND_46_SOURCE_SLOTS_UNBOUND", "content source catalog status")
    check(content_source_catalog["contract"] == artifact(CONTENT_SOURCE_CONTRACT_PATH), "content source catalog contract")
    check(content_source_catalog["semanticProjectionCatalog"] == artifact(SEMANTIC_PROJECTION_CATALOG_PATH), "content source semantic catalog")
    check(content_source_catalog["counts"] == {
        "sourceSlotCount": 47,
        "workingCopySourceSlotCount": 35,
        "canonicalBaselineSourceSlotCount": 12,
        "postSettledActionWorkingCopySlotCount": 34,
        "atomicRejectionFixtureSlotCount": 1,
        "serverCanonicalResponseSlotCount": 10,
        "existingTrustedBaselineSlotCount": 2,
        "exactSourceBindingCount": 1,
        "unboundSourceBindingCount": 46,
        "sourceRecordArtifactCount": 1,
        "canonicalDesignDslArtifactCount": 2,
        "sourceArtifactCount": 3,
    }, "content source catalog counts")
    content_slots = content_source_catalog["slots"]
    check(len(content_slots) == 47 and len({entry["sourceSlotId"] for entry in content_slots}) == 47, "content source slot uniqueness")
    check(sum(entry["binding"]["status"] == "EXACT_SOURCE" for entry in content_slots) == 1, "one exact content source slot")
    check(all(entry["binding"] == {"status": "UNBOUND", "reasonCode": "EXACT_EDITOR_CONTENT_SOURCE_ARTIFACT_MISSING"} for entry in content_slots if entry["sourceSlotId"] != DIRTY_GUARD_SOURCE_SLOT_ID), "remaining content source slots fail closed")
    expected_content_slot_keys = sorted(
        (entry["candidateId"], entry["planAssertionId"])
        for entry in content_decisions
        if entry["probeId"] in {"editor.workingCopyDigest", "editor.canonicalBaselineBytes"}
    )
    actual_content_slot_keys = sorted((entry["candidateId"], entry["planAssertionId"]) for entry in content_slots)
    check(actual_content_slot_keys == expected_content_slot_keys, "content source slots exactly cover first-layer decisions")
    check(all(entry["semanticInputFixture"] == artifact(f"{ROOT}/input-fixtures/{entry['candidateId'].lower().replace('::', '-')}.json") for entry in content_slots), "content source semantic fixture links")

    dirty_guard_slot = next(entry for entry in content_slots if entry["sourceSlotId"] == DIRTY_GUARD_SOURCE_SLOT_ID)
    dirty_guard_record = read_json(DIRTY_GUARD_SOURCE_RECORD_PATH)
    clean_design, clean_design_bytes = read_design_canonical(DIRTY_GUARD_CLEAN_DESIGN_PATH)
    working_design, working_design_bytes = read_design_canonical(DIRTY_GUARD_WORKING_DESIGN_PATH)
    clean_expected = {
        "dslVersion": "renderweave-design/1.0",
        "expressionProfile": "renderweave-expression/1.0",
        "displayName": "Clean Baseline",
        "definitions": [],
        "designRoot": {
            "nodeId": "00000000-0000-4000-8000-000000000001",
            "kind": "canvas",
            "widthMm": 210,
            "heightMm": 297,
            "bindings": [],
            "children": [],
        },
    }
    working_expected = {**clean_expected, "displayName": "Dirty Before Replacement"}
    check(clean_design == clean_expected, "dirty guard clean baseline exact DesignDSL")
    check(working_design == working_expected, "dirty guard working copy exact DesignDSL")
    check({key for key in clean_design if clean_design[key] != working_design[key]} == {"displayName"}, "dirty guard fixture differs only by authored displayName")
    clean_digest = sha256(clean_design_bytes)
    working_digest = sha256(working_design_bytes)
    clean_content_hash = sha256(b"renderweave-design-content/1\0" + clean_design_bytes)
    check(clean_digest != working_digest, "dirty guard working copy is dirty")
    check(dirty_guard_slot["candidateId"] == DIRTY_GUARD_CANDIDATE_ID and dirty_guard_slot["planAssertionId"] == DIRTY_GUARD_PLAN_ASSERTION_ID, "dirty guard source slot identity")
    check(dirty_guard_slot["acquisitionMode"] == "ATOMIC_REJECTION_PRESERVES_INITIAL_WORKING_COPY", "dirty guard source acquisition mode")
    check(dirty_guard_slot["binding"] == {"status": "EXACT_SOURCE", "adapterKind": "IMMUTABLE_SPEC_FIXTURE", "sourceRecordArtifact": artifact(DIRTY_GUARD_SOURCE_RECORD_PATH)}, "dirty guard source slot binding")
    check(list(dirty_guard_record) == content_source_contract["sourceRecord"]["exactMembersInOrder"], "content source record member order")
    check(dirty_guard_record["artifactVersion"] == "renderweave-editor-content-source-record/1.0", "content source record version")
    check(dirty_guard_record["sourceSlotId"] == DIRTY_GUARD_SOURCE_SLOT_ID and dirty_guard_record["candidateId"] == DIRTY_GUARD_CANDIDATE_ID and dirty_guard_record["planAssertionId"] == DIRTY_GUARD_PLAN_ASSERTION_ID, "content source record identity")
    check(dirty_guard_record["adapterKind"] == "IMMUTABLE_SPEC_FIXTURE" and dirty_guard_record["sourceKind"] == "WORKING_COPY_CANONICAL_DESIGN_DSL", "content source record adapter")
    proof_contract = content_source_contract["sourceRecord"]["immutableSpecFixtureProof"]
    proof = dirty_guard_record["stateProof"]
    check(list(proof) == proof_contract["exactMembersInOrder"], "dirty guard proof member order")
    check(list(proof["canonicalBaseline"]) == proof_contract["canonicalBaselineMembersInOrder"], "dirty guard baseline proof member order")
    check(list(proof["preActionWorkingCopy"]) == proof_contract["preActionWorkingCopyMembersInOrder"], "dirty guard working proof member order")
    check(list(proof["expectedTerminal"]) == proof_contract["expectedTerminalMembersInOrder"], "dirty guard terminal proof member order")
    check(proof["canonicalBaseline"] == {"revision": 0, "contentHash": clean_content_hash, "workingCopyDigest": clean_digest, "canonicalDesignDslArtifact": artifact(DIRTY_GUARD_CLEAN_DESIGN_PATH)}, "dirty guard canonical baseline proof")
    check(proof["preActionWorkingCopy"] == {"canonicalDesignDslArtifact": artifact(DIRTY_GUARD_WORKING_DESIGN_PATH), "workingCopyDigest": working_digest}, "dirty guard pre-action proof")
    check(proof["expectedTerminal"] == {"operationId": "replaceWorkingDraft", "outcome": "NONTERMINAL_REJECTION", "code": "EDITOR_DIRTY_DRAFT_REPLACEMENT_BLOCKED"}, "dirty guard exact terminal proof")
    check(proof["postActionRule"] == "BYTE_IDENTICAL_TO_PRE_ACTION", "dirty guard post-action rule")
    check(list(dirty_guard_record["result"]) == content_source_contract["sourceRecord"]["resultMembersBySourceKind"]["WORKING_COPY_CANONICAL_DESIGN_DSL"], "dirty guard result member order")
    check(dirty_guard_record["result"] == {"canonicalDesignDslArtifact": artifact(DIRTY_GUARD_WORKING_DESIGN_PATH), "workingCopyDigest": working_digest}, "dirty guard result replay")
    check(content_source_catalog["sourceRecords"] == [{"sourceSlotId": DIRTY_GUARD_SOURCE_SLOT_ID, "candidateId": DIRTY_GUARD_CANDIDATE_ID, "planAssertionId": DIRTY_GUARD_PLAN_ASSERTION_ID, "artifact": artifact(DIRTY_GUARD_SOURCE_RECORD_PATH)}], "content source record catalog")
    check(content_source_catalog["supportingArtifacts"] == [
        {"role": "CANONICAL_BASELINE_DESIGN_DSL", "artifact": artifact(DIRTY_GUARD_CLEAN_DESIGN_PATH)},
        {"role": "PRE_AND_POST_ACTION_WORKING_COPY_DESIGN_DSL", "artifact": artifact(DIRTY_GUARD_WORKING_DESIGN_PATH)},
    ], "content source supporting artifact catalog")

    check(target_binding_contract["artifactVersion"] == "renderweave-editor-target-binding-contract/1.3", "target binding contract version")
    check(target_binding_contract["status"] == "FROZEN_SEMANTIC_TARGET_RESOLUTION_INTERFACE_PRODUCT_OBSERVATION_PENDING", "target binding contract status")
    check(target_binding_contract["semanticProjectionContract"] == artifact(SEMANTIC_PROJECTION_CONTRACT_PATH), "target semantic projection contract")
    check(target_binding_contract["semanticProjectionCatalog"] == artifact(SEMANTIC_PROJECTION_CATALOG_PATH), "target semantic projection catalog")
    check(target_binding_contract["contentSourceContract"] == artifact(CONTENT_SOURCE_CONTRACT_PATH), "target content source contract")
    check(target_binding_contract["contentSourceCatalog"] == artifact(CONTENT_SOURCE_CATALOG_PATH), "target content source catalog")
    check([rule["ruleId"] for rule in target_binding_contract["resolutionRules"]] == ["EXACT_TERMINAL_VECTOR_ARTIFACT", "EXACT_NETWORK_REQUEST_SEQUENCE_LITERAL", "EXACT_COMMAND_RESULT_CODE", "EXACT_PREVIEW_ELIGIBILITY_GENERATION", "EXACT_WORKING_COPY_DIGEST_FROM_CONTENT_SOURCE"], "target resolution rules")
    check("remains pending" in target_binding_contract["noInferenceRule"], "target no-inference rule")
    check(target_binding_catalog["artifactVersion"] == "renderweave-editor-target-binding-catalog/1.3", "target binding catalog version")
    check(target_binding_catalog["status"] == "189_EXACT_TARGETS_BOUND_119_UNDERDETERMINED_FAIL_CLOSED", "target binding catalog status")
    check(target_binding_catalog["contract"] == artifact(TARGET_BINDING_CONTRACT_PATH), "target binding catalog contract")
    check(target_binding_catalog["counts"] == {
        "originalPendingExpectationCount": 308,
        "exactArtifactBindingCount": 108,
        "exactLiteralBindingCount": 81,
        "exactBindingCount": 189,
        "remainingPendingLiteralCount": 56,
        "remainingPendingArtifactCount": 63,
        "remainingPendingCount": 119,
        "targetArtifactCount": 108,
        "orphanTargetArtifactCount": 0,
    }, "target binding catalog counts")
    check(sum(entry["count"] for entry in target_binding_catalog["pendingByProbe"]) == 119, "target pending probe count")
    check(len(target_binding_catalog["artifacts"]) == 108, "target artifact count")
    check(len(target_binding_catalog["decisions"]) == 308, "target decision count")
    check(len({(entry["candidateId"], entry["planAssertionId"]) for entry in target_binding_catalog["decisions"]}) == 308, "target decision identity uniqueness")
    for entry in target_binding_catalog["artifacts"]:
        path = entry["artifact"]["path"]
        value = read_json(path)
        check((SPEC / path).read_bytes() == encoded(value), f"canonical target JSON: {path}")
        check(entry["artifact"] == artifact(path), f"target artifact binding: {path}")
        check(isinstance(value, list) and len(value) == 1, f"one terminal target vector: {path}")
        check(list(value[0]) in [["operationId", "outcome"], ["operationId", "outcome", "code"], ["operationId", "outcome", "stage"], ["operationId", "outcome", "code", "stage"]], f"terminal target member order: {path}")

    seed_by_id = {seed["journeySeedId"]: seed for seed in assignment["automatedJourneySeeds"]}
    check(len(seed_by_id) == 12, "seed count")
    candidate_pattern = re.compile(contract["candidateIdentity"]["pattern"])
    seen_ids = set()
    seen_digests = set()
    per_journey_ordinals: dict[str, list[int]] = {}
    planned_requirement_ids = set()
    exact_literal_assertions = 0
    exact_artifact_assertions = 0
    exact_absent_assertions = 0
    pending_assertions = 0
    candidate_profile_probe_ids = set()
    candidate_profile_binding_assertions = 0
    candidate_profile_bound_candidates = 0

    probe_by_id = {probe["probeId"]: probe for probe in probe_profile["probes"]}
    editor_probe_ids = {
        probe["probeId"] for probe in probe_profile["probes"]
        if EXECUTION_CLASS in probe["executionClasses"]
    }
    check(len(editor_probe_ids) == 31, "current Editor probe count")
    candidate_probe_by_id = {probe["probeId"]: probe for probe in candidate_probe_profile["probes"]}
    candidate_editor_probe_ids = {
        probe["probeId"] for probe in candidate_probe_profile["probes"]
        if EXECUTION_CLASS in probe["executionClasses"]
    }
    check(candidate_probe_profile["candidateProbeProfileId"] == CANDIDATE_PROBE_PROFILE_ID, "candidate probe profile identity")
    check(candidate_probe_profile["recordMayReference"] is False, "candidate probe profile remains unissued")
    check(len(candidate_editor_probe_ids) == 40, "candidate Editor probe count")

    for candidate in candidates:
        candidate_id = candidate["candidateId"]
        check(candidate_pattern.fullmatch(candidate_id) is not None, f"candidate ID shape: {candidate_id}")
        check(not candidate_id.startswith("CONF::"), f"formal Case namespace absent: {candidate_id}")
        check(not candidate_id.startswith("ORC::"), f"formal Oracle namespace absent: {candidate_id}")
        check(candidate_id not in seen_ids, f"unique candidate ID: {candidate_id}")
        seen_ids.add(candidate_id)
        journey = candidate_id.split("::")[1]
        ordinal = int(candidate_id.split("::")[2])
        per_journey_ordinals.setdefault(journey, []).append(ordinal)

        check(candidate["journeySeedId"] in seed_by_id, f"known seed: {candidate_id}")
        seed = seed_by_id[candidate["journeySeedId"]]
        check(candidate["sourceJ1CaseId"] == seed["sourceJ1CaseId"], f"J1 source: {candidate_id}")
        check(candidate["status"] == "PREISSUANCE_BLOCKED", f"candidate blocked: {candidate_id}")
        check(candidate["formalIssuanceAllowed"] is False, f"candidate issuance false: {candidate_id}")
        check(len(candidate["blockers"]) > 0, f"candidate blockers: {candidate_id}")
        check(candidate["blockers"] == sorted(set(candidate["blockers"])), f"candidate blockers sorted unique: {candidate_id}")
        check(all(blocker in EXPECTED_BLOCKERS for blocker in candidate["blockers"]), f"closed blockers: {candidate_id}")
        for required in ["EXACT_BROWSER_OS_TARGET_MISSING", "EXECUTOR_MANIFEST_MISSING", "INDEPENDENT_PRODUCT_REPLAY_MISSING"]:
            check(required in candidate["blockers"], f"universal blocker {required}: {candidate_id}")
        check("EXACT_PRODUCT_FIXTURE_ARTIFACT_MISSING" not in candidate["blockers"], f"semantic fixture artifact blocker cleared: {candidate_id}")

        input_plan = candidate["stimulusPlan"]["inputPlan"]
        input_base = {key: value for key, value in input_plan.items() if key != "planIdentityDigest"}
        check(input_plan["profile"] == "renderweave-editor-atomic-input-plan/1.1", f"input profile: {candidate_id}")
        check(len(input_plan["actionScript"]) > 0, f"action script: {candidate_id}")
        check(input_plan["planIdentityDigest"] == identity("renderweave-editor-atomic-input-plan/1", input_base), f"input digest: {candidate_id}")
        input_binding = input_plan["formalBinding"]
        input_formal_base = {key: value for key, value in input_binding.items() if key != "identitySha256"}
        check(input_binding["identitySha256"] == formal_input_identity(input_formal_base), f"input formal identity: {candidate_id}")
        matching_input_artifacts = [entry for entry in input_fixture_catalog["artifacts"] if entry["candidateId"] == candidate_id]
        check(len(matching_input_artifacts) == 1, f"input fixture catalog entry: {candidate_id}")
        check(input_binding == matching_input_artifacts[0]["formalBinding"], f"input fixture formal binding: {candidate_id}")
        input_value = read_json(input_binding["artifactPath"])
        check(input_value["baselineId"] == input_plan["baselineId"], f"input baseline alignment: {candidate_id}")
        check(input_value["parameters"] == input_plan["parameters"], f"input parameters alignment: {candidate_id}")
        check(input_value["actionScript"] == input_plan["actionScript"], f"input actions alignment: {candidate_id}")

        fault_plan = candidate["stimulusPlan"]["faultSchedulePlan"]
        fault_base = {key: value for key, value in fault_plan.items() if key != "planIdentityDigest"}
        check(fault_plan["profile"] == "renderweave-editor-atomic-fault-plan/1.1", f"fault profile: {candidate_id}")
        check(fault_plan["kind"] in ["NONE", "PLANNED_SEQUENCE"], f"fault kind: {candidate_id}")
        check((fault_plan["kind"] == "NONE") == (fault_plan["events"] == []), f"fault shape: {candidate_id}")
        check(fault_plan["planIdentityDigest"] == identity("renderweave-editor-atomic-fault-plan/1", fault_base), f"fault digest: {candidate_id}")
        formal_binding = fault_plan["formalBinding"]
        formal_base = {key: value for key, value in formal_binding.items() if key != "identitySha256"}
        check(formal_binding["identitySha256"] == formal_fault_identity(formal_base), f"fault formal identity: {candidate_id}")
        if fault_plan["kind"] == "NONE":
            check(formal_binding == fault_catalog["formalNoneBinding"], f"fault NONE binding: {candidate_id}")
        else:
            matching_artifacts = [entry for entry in fault_catalog["artifacts"] if entry["candidateId"] == candidate_id]
            check(len(matching_artifacts) == 1, f"fault artifact catalog entry: {candidate_id}")
            check(formal_binding == matching_artifacts[0]["formalBinding"], f"fault artifact formal binding: {candidate_id}")
            value = read_json(formal_binding["artifactPath"])
            check(value["events"] == fault_plan["events"], f"fault artifact event alignment: {candidate_id}")
        check("FAULT_SCHEDULE_ARTIFACT_MISSING" not in candidate["blockers"], f"fault blocker cleared: {candidate_id}")

        terminals = candidate["expectedTerminalPlan"]
        check(len(terminals) == 1, f"one terminal for ordinary Editor candidate: {candidate_id}")
        for terminal in terminals:
            check(terminal["outcome"] in ["SUCCESS", "PROBLEM", "NONTERMINAL_REJECTION"], f"terminal outcome: {candidate_id}")
            check(terminal["codeBinding"]["status"] in ["NOT_REQUIRED", "EXACT"], f"code binding: {candidate_id}")
            check(terminal["stageBinding"]["status"] in ["NOT_REQUIRED", "EXACT"], f"stage binding: {candidate_id}")
        check("EXPECTED_TERMINAL_CODE_OR_STAGE_UNBOUND" not in candidate["blockers"], f"terminal blocker cleared: {candidate_id}")

        assertions = candidate["assertionPlan"]
        check(len(assertions) > 0, f"assertion plan: {candidate_id}")
        assertion_ids = []
        has_pending = False
        has_candidate_profile = False
        for index, assertion in enumerate(assertions, start=1):
            expected_id = f"PA{index:03d}"
            check(assertion["planAssertionId"] == expected_id, f"assertion ordinal {candidate_id}:{expected_id}")
            assertion_ids.append(expected_id)
            binding = assertion["probeBinding"]
            if binding["status"] == "CURRENT_PROFILE":
                probe_id = binding["probeId"]
                check(probe_id in editor_probe_ids, f"Editor probe binding {candidate_id}:{probe_id}")
                check(assertion["operator"] in probe_by_id[probe_id]["allowedOperators"], f"operator binding {candidate_id}:{probe_id}")
            else:
                check(binding["status"] == "CANDIDATE_PROFILE_NOT_ISSUED", f"candidate binding shape: {candidate_id}")
                check(binding["probeProfile"] == CANDIDATE_PROBE_PROFILE_ID, f"candidate profile identity: {candidate_id}")
                check(binding["probeId"] in candidate_editor_probe_ids, f"candidate Editor probe binding: {candidate_id}:{binding['probeId']}")
                check(assertion["operator"] in candidate_probe_by_id[binding["probeId"]]["allowedOperators"], f"candidate operator binding: {candidate_id}:{binding['probeId']}")
                candidate_profile_probe_ids.add(binding["probeId"])
                candidate_profile_binding_assertions += 1
                has_candidate_profile = True
            expectation_status = assertion["expectation"]["status"]
            check(expectation_status in ["EXACT_LITERAL", "EXACT_ARTIFACT", "EXACT_ABSENT", "PENDING_TARGET_LITERAL", "PENDING_TARGET_ARTIFACT"], f"expectation status: {candidate_id}")
            if expectation_status == "EXACT_LITERAL":
                exact_literal_assertions += 1
                check(assertion["operator"] != "ABSENT", f"literal assertion is not ABSENT: {candidate_id}")
                check(assertion["expectation"]["expected"]["kind"] == "LITERAL", f"exact literal shape: {candidate_id}")
            elif expectation_status == "EXACT_ARTIFACT":
                exact_artifact_assertions += 1
                expected = assertion["expectation"]["expected"]
                check(assertion["operator"] == "BYTES_EQ", f"artifact assertion operator: {candidate_id}")
                check(expected["kind"] == "ARTIFACT" and expected["mediaType"] == "application/json", f"exact artifact shape: {candidate_id}")
                check(expected["artifactSha256"] == artifact(expected["artifactPath"])["sha256"], f"exact artifact digest: {candidate_id}")
            elif expectation_status == "EXACT_ABSENT":
                exact_absent_assertions += 1
                check(assertion["operator"] == "ABSENT", f"exact absent operator: {candidate_id}")
                check(list(assertion["expectation"]) == ["status"], f"exact absent shape: {candidate_id}")
            else:
                pending_assertions += 1
                has_pending = True
        check(len(assertion_ids) == len(set(assertion_ids)), f"unique local assertion IDs: {candidate_id}")
        check((not has_pending) or ("TARGET_LITERAL_OR_ARTIFACT_MISSING" in candidate["blockers"]), f"expectation blocker: {candidate_id}")
        if has_candidate_profile:
            candidate_profile_bound_candidates += 1
        check((not has_candidate_profile) or ("EDITOR_PROBE_PROFILE_CANDIDATE_NOT_ISSUED" in candidate["blockers"]), f"probe blocker: {candidate_id}")

        current_assertions = {
            assertion["probeBinding"]["probeId"]: assertion
            for assertion in assertions
            if assertion["probeBinding"]["status"] == "CURRENT_PROFILE"
        }
        for required_probe in ["operation.terminalCode", "operation.terminalStage", "operation.terminalParametersBytes", "operation.terminalsBytes"]:
            check(required_probe in current_assertions, f"terminal assertion present {candidate_id}:{required_probe}")
        terminal = terminals[0]
        code_assertion = current_assertions["operation.terminalCode"]
        stage_assertion = current_assertions["operation.terminalStage"]
        if terminal["codeBinding"]["status"] == "EXACT":
            check(code_assertion["expectation"]["status"] == "EXACT_LITERAL" and code_assertion["expectation"]["expected"]["value"] == terminal["codeBinding"]["code"], f"terminal code aligned: {candidate_id}")
        elif terminal["codeBinding"]["status"] == "NOT_REQUIRED":
            check(code_assertion["expectation"]["status"] == "EXACT_ABSENT", f"terminal code absent: {candidate_id}")
        else:
            raise AssertionError(f"terminal code not adjudicated: {candidate_id}")
        if terminal["stageBinding"]["status"] == "EXACT":
            check(stage_assertion["expectation"]["status"] == "EXACT_LITERAL" and stage_assertion["expectation"]["expected"]["value"] == terminal["stageBinding"]["stage"], f"terminal stage aligned: {candidate_id}")
        elif terminal["stageBinding"]["status"] == "NOT_REQUIRED":
            check(stage_assertion["expectation"]["status"] == "EXACT_ABSENT", f"terminal stage absent: {candidate_id}")
        else:
            raise AssertionError(f"terminal stage not adjudicated: {candidate_id}")
        check(current_assertions["operation.terminalParametersBytes"]["expectation"]["status"] == "EXACT_ABSENT", f"terminal parameters absent: {candidate_id}")
        check(current_assertions["operation.terminalsBytes"]["expectation"]["status"] == "EXACT_ARTIFACT", f"terminal vector exact artifact: {candidate_id}")
        terminal_target = read_json(current_assertions["operation.terminalsBytes"]["expectation"]["expected"]["artifactPath"])
        check(terminal_target == [terminal_projection(terminal)], f"terminal vector target alignment: {candidate_id}")
        network_assertion = current_assertions["editor.networkRequestSequence"]
        check(network_assertion["expectation"]["status"] == "EXACT_LITERAL", f"network sequence exact: {candidate_id}")

        coverage = candidate["coveragePlan"]
        check(len(coverage) > 0, f"coverage plan: {candidate_id}")
        covered_here = set()
        for edge in coverage:
            requirement_id = edge["requirementId"]
            check(edge["status"] == "PLANNED_NOT_EVIDENCE", f"planned evidence boundary: {candidate_id}:{requirement_id}")
            check(requirement_id in seed["requirementIds"], f"coverage stays in seed: {candidate_id}:{requirement_id}")
            check(len(edge["assertionPlanIds"]) > 0, f"coverage assertion links: {candidate_id}:{requirement_id}")
            check(all(assertion_id in assertion_ids for assertion_id in edge["assertionPlanIds"]), f"coverage assertion IDs exist: {candidate_id}:{requirement_id}")
            check(requirement_id not in covered_here, f"unique coverage edge: {candidate_id}:{requirement_id}")
            covered_here.add(requirement_id)
            planned_requirement_ids.add(requirement_id)

        digest_object = {
            "inputPlan": input_plan,
            "faultSchedulePlan": fault_plan,
            "expectedTerminalPlan": terminals,
            "assertionPlan": assertions,
        }
        check(candidate["identityDigest"] == identity("renderweave-editor-atomic-candidate-identity/1", digest_object), f"candidate digest: {candidate_id}")
        check(candidate["identityDigest"] not in seen_digests, f"unique candidate digest: {candidate_id}")
        seen_digests.add(candidate["identityDigest"])

    candidate_by_id = {candidate["candidateId"]: candidate for candidate in candidates}
    for decision in target_binding_catalog["decisions"]:
        candidate = candidate_by_id[decision["candidateId"]]
        matching = [assertion for assertion in candidate["assertionPlan"] if assertion["planAssertionId"] == decision["planAssertionId"]]
        check(len(matching) == 1, f"target decision assertion exists: {decision['candidateId']}:{decision['planAssertionId']}")
        assertion = matching[0]
        check(assertion["probeBinding"]["probeId"] == decision["probeId"], f"target decision probe alignment: {decision['candidateId']}:{decision['planAssertionId']}")
        check(assertion["operator"] == decision["operator"], f"target decision operator alignment: {decision['candidateId']}:{decision['planAssertionId']}")
        if decision["resultStatus"] == "EXACT_ARTIFACT":
            check(decision["resolution"] == "EXACT_TERMINAL_VECTOR_ARTIFACT", f"terminal target resolution: {decision['candidateId']}")
            check(assertion["expectation"] == {"status": "EXACT_ARTIFACT", "expected": decision["expected"]}, f"terminal target expectation alignment: {decision['candidateId']}")
        elif decision["resultStatus"] == "EXACT_LITERAL":
            expected_resolution = {
                "editor.networkRequestSequence": "EXACT_NETWORK_REQUEST_SEQUENCE_LITERAL",
                "editor.commandResult": "EXACT_COMMAND_RESULT_CODE",
                "editor.previewEligibilityGeneration": "EXACT_PREVIEW_ELIGIBILITY_GENERATION",
                "editor.workingCopyDigest": "EXACT_WORKING_COPY_DIGEST_FROM_CONTENT_SOURCE",
            }.get(decision["probeId"])
            check(decision["resolution"] == expected_resolution, f"literal target resolution: {decision['candidateId']}:{decision['probeId']}")
            check(assertion["expectation"] == {"status": "EXACT_LITERAL", "expected": decision["expected"]}, f"network target expectation alignment: {decision['candidateId']}")
            if decision["probeId"] in ["editor.commandResult", "editor.previewEligibilityGeneration"]:
                semantic_matches = [entry for entry in semantic_projection_catalog["decisions"] if entry["candidateId"] == decision["candidateId"] and entry["planAssertionId"] == decision["planAssertionId"]]
                check(len(semantic_matches) == 1, f"semantic target decision exists: {decision['candidateId']}:{decision['planAssertionId']}")
                check(semantic_matches[0]["expected"] == decision["expected"], f"semantic target value alignment: {decision['candidateId']}:{decision['planAssertionId']}")
            if decision["probeId"] == "editor.workingCopyDigest":
                check(decision["candidateId"] == DIRTY_GUARD_CANDIDATE_ID and decision["planAssertionId"] == DIRTY_GUARD_PLAN_ASSERTION_ID, "content-source target identity")
                check(decision["sourceSlotId"] == DIRTY_GUARD_SOURCE_SLOT_ID, "content-source target slot")
                check(decision["sourceRecordArtifact"] == artifact(DIRTY_GUARD_SOURCE_RECORD_PATH), "content-source target record")
                check(decision["expected"] == {"kind": "LITERAL", "value": working_digest}, "content-source target digest")
        else:
            check(decision["resolution"] == "REMAIN_PENDING_FAIL_CLOSED", f"pending target resolution: {decision['candidateId']}:{decision['planAssertionId']}")
            check(assertion["expectation"]["status"] == decision["resultStatus"], f"pending target status alignment: {decision['candidateId']}:{decision['planAssertionId']}")
            check(len(decision["reason"]) > 0, f"pending target reason: {decision['candidateId']}:{decision['planAssertionId']}")

    for journey, ordinals in per_journey_ordinals.items():
        check(ordinals == list(range(1, len(ordinals) + 1)), f"continuous journey ordinals: {journey}")
    check(len(per_journey_ordinals) == 12, "all journey candidate groups")

    seed_union = sorted({requirement_id for seed in assignment["automatedJourneySeeds"] for requirement_id in seed["requirementIds"]})
    check(len(seed_union) == 138, "seed requirement union count")
    check(sorted(planned_requirement_ids) == seed_union, "planned requirement closure")
    check(exact_literal_assertions == 749, "exact literal assertion count")
    check(exact_artifact_assertions == 108, "exact artifact assertion count")
    check(exact_absent_assertions == 289, "exact absent assertion count")
    check(pending_assertions == 119, "pending assertion count")
    check(candidate_profile_binding_assertions == 109, "candidate profile binding assertion count")
    check(candidate_profile_bound_candidates == 82, "candidate profile bound candidate count")
    check(len(candidate_profile_probe_ids) == 9, "candidate profile probe usage count")

    for candidate_id in ["EDC::J10::008", "EDC::J10::010"]:
        working_copy_assertions = [
            assertion for assertion in candidate_by_id[candidate_id]["assertionPlan"]
            if assertion["probeBinding"]["probeId"] == "editor.workingCopyDigest"
        ]
        check(len(working_copy_assertions) == 1, f"one no-working-copy assertion: {candidate_id}")
        check(working_copy_assertions[0]["operator"] == "ABSENT" and working_copy_assertions[0]["expectation"] == {"status": "EXACT_ABSENT"}, f"exact no-working-copy absence: {candidate_id}")

    check(audit["artifactVersion"] == "renderweave-editor-atomic-candidate-readiness-audit/1.5", "audit version")
    check(audit["status"] == "ONE_CONTENT_SOURCE_AND_DETERMINISTIC_TARGETS_BOUND_PREISSUANCE_BLOCKED", "audit status")
    check(audit["contract"] == artifact(CONTRACT_PATH), "audit contract artifact")
    check(audit["candidates"] == artifact(CANDIDATES_PATH), "audit candidates artifact")
    check(audit["sourceAssignment"] == artifact(f"{ROOT}/non-capacity-assignment-v1.json"), "audit assignment artifact")
    check(audit["probeProfiles"] == {
        "current": artifact("conformance-probe-profile-v1.json"),
        "candidate": artifact(CANDIDATE_PROBE_PROFILE_PATH),
        "adjudication": artifact(CANDIDATE_PROBE_ADJUDICATION_PATH),
    }, "audit probe profile artifacts")
    check(audit["terminalAndFaultBindings"] == {
        "terminalAdjudication": artifact(TERMINAL_ADJUDICATION_PATH),
        "faultScheduleContract": artifact(FAULT_CONTRACT_PATH),
        "faultScheduleCatalog": artifact(FAULT_CATALOG_PATH),
    }, "audit terminal and fault artifacts")
    check(audit["inputAndTargetBindings"] == {
        "inputFixtureContract": artifact(INPUT_FIXTURE_CONTRACT_PATH),
        "inputFixtureCatalog": artifact(INPUT_FIXTURE_CATALOG_PATH),
        "semanticProjectionContract": artifact(SEMANTIC_PROJECTION_CONTRACT_PATH),
        "semanticProjectionCatalog": artifact(SEMANTIC_PROJECTION_CATALOG_PATH),
        "contentSourceContract": artifact(CONTENT_SOURCE_CONTRACT_PATH),
        "contentSourceCatalog": artifact(CONTENT_SOURCE_CATALOG_PATH),
        "targetBindingContract": artifact(TARGET_BINDING_CONTRACT_PATH),
        "targetBindingCatalog": artifact(TARGET_BINDING_CATALOG_PATH),
    }, "audit input and target artifacts")
    check(audit["counts"] == {
        "journeySeedCount": 12,
        "candidateCount": 108,
        "seedRequirementUnionCount": 138,
        "plannedRequirementUnionCount": 138,
        "assertionPlanCount": 1265,
        "exactLiteralAssertionPlanCount": 749,
        "exactArtifactAssertionPlanCount": 108,
        "exactAbsentAssertionPlanCount": 289,
        "exactExpectationAssertionPlanCount": 1146,
        "pendingExpectationAssertionPlanCount": 119,
        "inputFixtureArtifactBindingCount": 108,
        "targetArtifactBindingCount": 108,
        "targetLiteralAdjudicatedFromPendingCount": 81,
        "semanticProjectionExactLiteralBindingCount": 42,
        "semanticProjectionContentPrerequisiteCount": 58,
        "contentPrerequisiteResolvedBySourceCount": 1,
        "contentPrerequisiteRemainingCount": 57,
        "contentSourceSlotCount": 47,
        "contentSourceExactBindingCount": 1,
        "contentSourceUnboundBindingCount": 46,
        "contentSourceRecordArtifactCount": 1,
        "contentSourceCanonicalDesignDslArtifactCount": 2,
        "uiObservationPendingCount": 62,
        "terminalAdjudicatedFromPendingCount": 33,
        "pendingTerminalBindingCount": 0,
        "faultArtifactBindingCount": 37,
        "faultNoneBindingCount": 71,
        "proposedProbeCount": 0,
        "candidateProfileBindingAssertionCount": 109,
        "candidateProfileBoundCandidateCount": 82,
        "formalCaseCount": 58,
        "formalOracleCount": 58,
        "formalEditorCaseCount": 0,
        "formalEditorOracleCount": 0,
    }, "audit counts")
    check(audit["requirementClosure"]["exactSeedUnionMatch"] is True, "audit requirement closure")
    check(audit["requirementClosure"]["missingRequirementIds"] == [], "no missing requirements")
    check(audit["requirementClosure"]["extraneousRequirementIds"] == [], "no extraneous requirements")
    check(audit["identityAudit"] == {
        "candidateIdsUnique": True,
        "identityDigestsUnique": True,
        "formalCaseNamespaceUsed": False,
        "formalOracleNamespaceUsed": False,
    }, "audit identity result")
    check(audit["currentProfileAudit"]["unknownCurrentProbeBindings"] == [], "no unknown current probes")
    check(audit["currentProfileAudit"]["unknownCandidateProbeBindings"] == [], "no unknown candidate probes")
    check(audit["currentProfileAudit"]["proposedProbeCandidates"] == [], "no unresolved proposed probe audit entries")
    check(audit["currentProfileAudit"]["candidateProfileIssued"] is False, "candidate probe profile not issued")
    check(audit["currentProfileAudit"]["profileMutationPerformed"] is False, "probe profile not mutated")
    check([entry["code"] for entry in audit["blockers"]] == EXPECTED_BLOCKERS, "audit blocker order")
    check({entry["code"]: entry["candidateCount"] for entry in audit["blockers"]} == {
        "EXACT_PRODUCT_FIXTURE_ARTIFACT_MISSING": 0,
        "EXACT_BROWSER_OS_TARGET_MISSING": 108,
        "EXECUTOR_MANIFEST_MISSING": 108,
        "INDEPENDENT_PRODUCT_REPLAY_MISSING": 108,
        "FAULT_SCHEDULE_ARTIFACT_MISSING": 0,
        "EXPECTED_TERMINAL_CODE_OR_STAGE_UNBOUND": 0,
        "TARGET_LITERAL_OR_ARTIFACT_MISSING": 93,
        "EDITOR_PROBE_PROFILE_CANDIDATE_NOT_ISSUED": 82,
    }, "audit blocker counts")
    check(audit["decision"]["formalRecordIssuanceAllowed"] is False, "audit issuance forbidden")
    check(all(value is False for value in audit["zeroExecutionBoundary"].values()), "zero execution boundary")

    formal_cases = jsonl_records("conformance-cases-v1.jsonl")
    formal_oracles = jsonl_records("conformance-oracles-v1.jsonl")
    editor_formal_cases = [record for record in formal_cases if record.get("executionClass") == EXECUTION_CLASS]
    editor_formal_oracles = [record for record in formal_oracles if record.get("oracleId", "").startswith("ORC::EDITOR_AUTOMATED::")]
    check(len(formal_cases) == 58 and len(formal_oracles) == 58, "global formal registries include issued Domain Services suffix")
    check(not editor_formal_cases and not editor_formal_oracles, "Editor formal namespaces remain empty")
    formal_ids = {record["caseId"] for record in formal_cases} | {record["oracleId"] for record in formal_oracles}
    check(not (seen_ids & formal_ids), "candidate IDs absent from formal registry")

    admission = read_json(f"{ROOT}/repository-readiness-audit-v1.json")
    check(admission["admissionDecision"]["result"] == "REJECTED", "product admission still rejected")
    check(admission["admissionDecision"]["recordIssuanceAllowed"] is False, "admission forbids formal issuance")

    result = {
        "artifactVersion": "renderweave-editor-atomic-candidate-independent-result/1.5",
        "implementationRevision": "editor-atomic-candidate-independent-python/1.5",
        "target": {
            "contract": artifact(CONTRACT_PATH),
            "candidates": artifact(CANDIDATES_PATH),
            "audit": artifact(AUDIT_PATH),
            "sourceAssignment": artifact(f"{ROOT}/non-capacity-assignment-v1.json"),
            "probeProfile": artifact("conformance-probe-profile-v1.json"),
            "candidateProbeProfile": artifact(CANDIDATE_PROBE_PROFILE_PATH),
            "candidateProbeAdjudication": artifact(CANDIDATE_PROBE_ADJUDICATION_PATH),
            "terminalAdjudication": artifact(TERMINAL_ADJUDICATION_PATH),
            "faultScheduleContract": artifact(FAULT_CONTRACT_PATH),
            "faultScheduleCatalog": artifact(FAULT_CATALOG_PATH),
            "inputFixtureContract": artifact(INPUT_FIXTURE_CONTRACT_PATH),
            "inputFixtureCatalog": artifact(INPUT_FIXTURE_CATALOG_PATH),
            "semanticProjectionContract": artifact(SEMANTIC_PROJECTION_CONTRACT_PATH),
            "semanticProjectionCatalog": artifact(SEMANTIC_PROJECTION_CATALOG_PATH),
            "contentSourceContract": artifact(CONTENT_SOURCE_CONTRACT_PATH),
            "contentSourceCatalog": artifact(CONTENT_SOURCE_CATALOG_PATH),
            "targetBindingContract": artifact(TARGET_BINDING_CONTRACT_PATH),
            "targetBindingCatalog": artifact(TARGET_BINDING_CATALOG_PATH),
            "formalCases": artifact("conformance-cases-v1.jsonl"),
            "formalOracles": artifact("conformance-oracles-v1.jsonl"),
        },
        "status": "PASS_STATIC_CANDIDATE_DECOMPOSITION",
        "checkCount": check_count,
        "failureCount": 0,
        "observed": {
            "journeySeedCount": 12,
            "candidateCount": 108,
            "plannedRequirementUnionCount": 138,
            "assertionPlanCount": exact_literal_assertions + exact_artifact_assertions + exact_absent_assertions + pending_assertions,
            "exactLiteralAssertionPlanCount": exact_literal_assertions,
            "exactArtifactAssertionPlanCount": exact_artifact_assertions,
            "exactAbsentAssertionPlanCount": exact_absent_assertions,
            "exactExpectationAssertionPlanCount": exact_literal_assertions + exact_artifact_assertions + exact_absent_assertions,
            "pendingExpectationAssertionPlanCount": pending_assertions,
            "inputFixtureArtifactBindingCount": input_fixture_catalog["counts"]["artifactCandidateCount"],
            "targetArtifactBindingCount": target_binding_catalog["counts"]["exactArtifactBindingCount"],
            "targetLiteralAdjudicatedFromPendingCount": target_binding_catalog["counts"]["exactLiteralBindingCount"],
            "semanticProjectionExactLiteralBindingCount": semantic_projection_catalog["counts"]["exactLiteralBindingCount"],
            "semanticProjectionContentPrerequisiteCount": semantic_projection_catalog["counts"]["contentDerivedPendingCount"],
            "contentPrerequisiteResolvedBySourceCount": audit["counts"]["contentPrerequisiteResolvedBySourceCount"],
            "contentPrerequisiteRemainingCount": audit["counts"]["contentPrerequisiteRemainingCount"],
            "contentSourceSlotCount": content_source_catalog["counts"]["sourceSlotCount"],
            "contentSourceExactBindingCount": content_source_catalog["counts"]["exactSourceBindingCount"],
            "contentSourceUnboundBindingCount": content_source_catalog["counts"]["unboundSourceBindingCount"],
            "contentSourceRecordArtifactCount": content_source_catalog["counts"]["sourceRecordArtifactCount"],
            "contentSourceCanonicalDesignDslArtifactCount": content_source_catalog["counts"]["canonicalDesignDslArtifactCount"],
            "uiObservationPendingCount": semantic_projection_catalog["counts"]["excludedUiObservedCount"],
            "proposedProbeCount": 0,
            "candidateProfileProbeUsageCount": len(candidate_profile_probe_ids),
            "candidateProfileBindingAssertionCount": candidate_profile_binding_assertions,
            "candidateProfileBoundCandidateCount": candidate_profile_bound_candidates,
            "terminalAdjudicatedFromPendingCount": terminal_adjudication["counts"]["adjudicatedFromPendingCount"],
            "pendingTerminalBindingCount": terminal_adjudication["counts"]["pendingCodeOrStageCount"],
            "faultArtifactBindingCount": fault_catalog["counts"]["artifactCandidateCount"],
            "faultNoneBindingCount": fault_catalog["counts"]["noneCandidateCount"],
            "formalCaseCount": len(formal_cases),
            "formalOracleCount": len(formal_oracles),
            "formalEditorCaseCount": len(editor_formal_cases),
            "formalEditorOracleCount": len(editor_formal_oracles),
        },
        "boundary": "Independent static replay only; no browser, product build, product code, network, Editor formal registry append, J1, or READY claim.",
    }
    (SPEC / RESULT_PATH).write_bytes(encoded(result))
    print(json.dumps({"status": result["status"], "checkCount": result["checkCount"], **result["observed"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise
