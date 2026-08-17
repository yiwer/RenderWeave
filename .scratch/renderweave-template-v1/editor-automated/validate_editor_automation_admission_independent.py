from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path


HERE = Path(__file__).resolve().parent
SPEC = HERE.parent
WORKTREE = SPEC.parent.parent
ROOT = "editor-automated"
EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0"
IMPLEMENTATION_REVISION = "editor-automation-admission/1.0"
CONTRACT_PATH = f"{ROOT}/execution-admission-contract-v1.json"
ASSIGNMENT_PATH = f"{ROOT}/non-capacity-assignment-v1.json"
AUDIT_PATH = f"{ROOT}/repository-readiness-audit-v1.json"
RESULT_PATH = f"{ROOT}/admission-independent-result-v1.json"

CLOSED_REJECTION_CODES = [
    "EDITOR_BOOTSTRAP_PREDECESSOR_PENDING",
    "EDITOR_SUPPORT_MATRIX_MISSING",
    "EDITOR_PRODUCT_BUILD_MANIFEST_MISSING",
    "EDITOR_BROWSER_BINARY_IDENTITY_MISSING",
    "EDITOR_OS_TARGET_IDENTITY_MISSING",
    "EDITOR_ENVIRONMENT_PROFILE_INCOMPLETE",
    "EDITOR_RUNNER_MANIFEST_MISSING",
    "EDITOR_CORPUS_ASSIGNMENT_INCOMPLETE",
    "EDITOR_OBSERVATION_ADAPTER_MISMATCH",
    "EDITOR_INDEPENDENT_REPLAY_MISSING",
]

PROPOSED_MANIFEST_PATHS = [
    f"{ROOT}/supported-targets-v1.json",
    f"{ROOT}/product-build-manifest-v1.json",
    f"{ROOT}/exact-target-manifests-v1.json",
    f"{ROOT}/automation-runner-manifests-v1.json",
    f"{ROOT}/assigned-active-corpus-v1.json",
    f"{ROOT}/independent-product-replay-v1.json",
]

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


def read_json(relative_path: str, base: Path = SPEC):
    raw = (base / relative_path).read_bytes()
    check(not raw.startswith(b"\xef\xbb\xbf"), f"BOM forbidden: {relative_path}")
    return json.loads(raw.decode("utf-8"), object_pairs_hook=no_duplicates)


def encoded(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, separators=(",", ": ")) + "\n").encode("utf-8")


def sha256(content: bytes) -> str:
    return "sha256:" + hashlib.sha256(content).hexdigest()


def artifact(relative_path: str, base: Path = SPEC):
    content = (base / relative_path).read_bytes()
    return {"path": relative_path.replace("\\", "/"), "sha256": sha256(content), "byteLength": len(content)}


def requirement_digest(ids: list[str]) -> str:
    digest = hashlib.sha256()
    digest.update(b"renderweave-editor-non-capacity-requirements/1\0")
    digest.update("\n".join(ids).encode("utf-8"))
    return "sha256:" + digest.hexdigest()


def requirements():
    expected_header = ["requirement_id", "source_line", "clause_ordinal_on_line", "family", "normative_summary"]
    rows = []
    for path in sorted((SPEC / "requirements").glob("[0-9][0-9].tsv")):
        raw = path.read_bytes()
        check(not raw.startswith(b"\xef\xbb\xbf"), f"TSV BOM forbidden: {path.name}")
        text = raw.decode("utf-8")
        check(text.endswith("\n"), f"TSV final LF: {path.name}")
        lines = text.rstrip("\n").split("\n")
        header = lines.pop(0).rstrip("\r").split("\t")
        check(header == expected_header, f"TSV header: {path.name}")
        for line in lines:
            fields = line.rstrip("\r").split("\t")
            check(len(fields) == 5, f"TSV width: {path.name}")
            rows.append(dict(zip(header, fields, strict=True)))
    return rows


def exact_keys(value, expected, label):
    check(list(value) == expected, f"{label} keys")


def main():
    contract = read_json(CONTRACT_PATH)
    assignment = read_json(ASSIGNMENT_PATH)
    audit = read_json(AUDIT_PATH)
    for path, value in [(CONTRACT_PATH, contract), (ASSIGNMENT_PATH, assignment), (AUDIT_PATH, audit)]:
        check((SPEC / path).read_bytes() == encoded(value), f"pretty JSON bytes: {path}")

    exact_keys(contract, [
        "artifactVersion", "contractId", "status", "executionClass", "seam", "admissionOrder",
        "requiredManifestKinds", "requiredEnvironmentProfiles", "targetRules", "runnerRules",
        "independentReplayRules", "closedRejectionCodes", "issuanceBoundary", "j1Boundary"
    ], "contract")
    check(contract["artifactVersion"] == "renderweave-editor-execution-admission-contract/1.0", "contract version")
    check(contract["contractId"] == "renderweave-editor-execution-admission/1.0", "contract id")
    check(contract["status"] == "FROZEN_STATIC_INTERFACE", "contract status")
    check(contract["executionClass"] == EXECUTION_CLASS, "contract class")
    check(contract["seam"]["name"] == "EditorAutomationAdmission", "seam name")
    check(contract["seam"]["resultUnion"] == ["ADMITTED", "REJECTED"], "result union")
    check(contract["seam"]["ambientDiscoveryAllowed"] is False, "ambient discovery")
    check(contract["seam"]["partialAdmissionAllowed"] is False, "partial admission")
    check(contract["admissionOrder"] == [
        "BOOTSTRAP_PREDECESSORS", "SUPPORTED_TARGET_MATRIX", "PRODUCT_BUILD", "BROWSER_OS_TARGET",
        "ENVIRONMENT_PROFILES", "AUTOMATION_RUNNER", "ASSIGNED_ACTIVE_CORPUS",
        "OBSERVATION_ADAPTER", "INDEPENDENT_REPLAY"
    ], "admission order")
    kinds = [entry["kind"] for entry in contract["requiredManifestKinds"]]
    check(kinds == ["SUPPORTED_TARGET_MATRIX", "PRODUCT_BUILD", "BROWSER_OS_TARGET", "AUTOMATION_RUNNER", "ASSIGNED_ACTIVE_CORPUS", "INDEPENDENT_REPLAY"], "manifest kinds")
    for entry in contract["requiredManifestKinds"]:
        exact_keys(entry, ["kind", "requiredFacts", "forbiddenSubstitutes"], f"manifest kind {entry['kind']}")
        check(len(entry["requiredFacts"]) > 0, f"manifest facts {entry['kind']}")
        check(len(entry["forbiddenSubstitutes"]) > 0, f"manifest substitutes {entry['kind']}")
    profiles = [entry["profileId"] for entry in contract["requiredEnvironmentProfiles"]]
    check(profiles == ["DEFAULT_100", "ZOOM_200", "REDUCED_MOTION", "SUPPORTED_HIGH_CONTRAST"], "environment profiles")
    check(contract["closedRejectionCodes"] == CLOSED_REJECTION_CODES, "closed rejection codes")
    check(len(set(contract["closedRejectionCodes"])) == len(CLOSED_REJECTION_CODES), "unique rejection codes")
    check(contract["issuanceBoundary"] == {
        "routingInventoryMayExistBeforeAdmission": True,
        "formalCaseOrOracleIssuanceBeforeAtomicSplit": False,
        "formalRecordIssuanceBeforeTargetAndRunnerAdmission": False,
        "executableClaimBeforeIndependentReplay": False,
    }, "issuance boundary")
    check(contract["j1Boundary"] == {
        "separateHumanRecord": True,
        "requiredCaseCount": 12,
        "countedInAutomatedCorpus": False,
        "maySubstituteAutomatedCoverage": False,
        "automatedAdmissionMaySubstituteJ1": False,
    }, "J1 boundary")

    rows = requirements()
    by_id = {row["requirement_id"]: row for row in rows}
    check(len(rows) == 3651, "registry requirement count")
    check(len(by_id) == len(rows), "unique requirement IDs")
    primary = sorted(row["requirement_id"] for row in rows if row["family"] == "EDITOR_AUTOMATED")
    j1 = read_json("j1-editor-checklist-v1.json")
    journey_union = sorted({item for case in j1["cases"] for item in case["requirementIds"]})
    for requirement_id in journey_union:
        check(requirement_id in by_id, f"J1 requirement exists: {requirement_id}")
    cross_family = sorted(item for item in journey_union if by_id[item]["family"] != "EDITOR_AUTOMATED")
    assigned = sorted(set(primary + cross_family))
    check(len(primary) == 410, "primary Editor family count")
    check(len(cross_family) == 4, "cross-family J1 count")
    check(len(assigned) == 414, "assigned count")
    check(len(journey_union) == 138, "journey union count")

    exact_keys(assignment, [
        "artifactVersion", "status", "executionClass", "authorityBoundary", "sourceRegistry",
        "j1Checklist", "observationAdapter", "selectionRule", "assignedRequirementSet",
        "routingGroups", "automatedJourneySeeds", "classProbeSet", "counts", "issuance"
    ], "assignment")
    check(assignment["artifactVersion"] == "renderweave-editor-non-capacity-assignment/1.0", "assignment version")
    check(assignment["status"] == "ROUTING_INVENTORY_FROZEN_ATOMIC_CASE_SPLIT_PENDING", "assignment status")
    check(assignment["executionClass"] == EXECUTION_CLASS, "assignment class")
    check(assignment["sourceRegistry"] == artifact("requirements-v1.json"), "requirements artifact")
    check(assignment["j1Checklist"] == artifact("j1-editor-checklist-v1.json"), "J1 artifact")
    check(assignment["observationAdapter"] == artifact(f"{ROOT}/observation-adapter-v1.json"), "adapter artifact")
    check(assignment["assignedRequirementSet"]["requirementIds"] == assigned, "assigned IDs")
    check(assignment["assignedRequirementSet"]["digest"] == requirement_digest(assigned), "assigned digest")
    for requirement_id in assigned:
        check(requirement_id in by_id, f"assigned requirement exists: {requirement_id}")

    observed_groups = {}
    flattened = []
    for group in assignment["routingGroups"]:
        exact_keys(group, ["groupId", "meaning", "requirementCount", "requirementIds"], f"routing group {group['groupId']}")
        check(group["groupId"] not in observed_groups, f"unique routing group {group['groupId']}")
        check(group["requirementCount"] == len(group["requirementIds"]), f"routing count {group['groupId']}")
        observed_groups[group["groupId"]] = group["requirementIds"]
        flattened.extend(group["requirementIds"])
    check(len(observed_groups) == 45, "routing group count")
    check(sorted(flattened) == assigned, "routing group closure")
    check(len(flattened) == len(set(flattened)), "routing group uniqueness")
    for requirement_id in primary:
        match = re.match(r"^RW-T(\d{2})-S(\d+)-", requirement_id)
        check(match is not None, f"Editor ID shape: {requirement_id}")
        expected_group = f"EDITOR-FAMILY-T{match.group(1)}-S{match.group(2)}"
        check(requirement_id in observed_groups[expected_group], f"Editor routing: {requirement_id}")
    check(observed_groups["J1-CROSS-FAMILY-DEPENDENCIES"] == cross_family, "cross-family routing")

    check(len(assignment["automatedJourneySeeds"]) == 12, "journey seed count")
    for seed, source in zip(assignment["automatedJourneySeeds"], j1["cases"], strict=True):
        exact_keys(seed, ["journeySeedId", "sourceJ1CaseId", "title", "meaning", "requirementIds", "atomicSplitRule"], f"journey seed {source['caseId']}")
        check(seed["sourceJ1CaseId"] == source["caseId"], f"journey source {source['caseId']}")
        check(seed["requirementIds"] == sorted(source["requirementIds"]), f"journey requirements {source['caseId']}")
        check("not one Case identity" in seed["meaning"], f"journey non-case boundary {source['caseId']}")

    probe_profile = read_json("conformance-probe-profile-v1.json")
    expected_probes = [probe["probeId"] for probe in probe_profile["probes"] if EXECUTION_CLASS in probe["executionClasses"]]
    check(len(expected_probes) == 31, "expected Editor probes")
    check(assignment["classProbeSet"]["probeProfileId"] == probe_profile["candidateProbeProfileId"], "probe profile ID")
    check(assignment["classProbeSet"]["probeIds"] == expected_probes, "probe set")
    for probe_id in expected_probes:
        check(probe_id in assignment["classProbeSet"]["probeIds"], f"probe assigned: {probe_id}")
    check(assignment["counts"] == {
        "registryRequirementCount": 3651,
        "primaryEditorFamilyRequirementCount": 410,
        "crossFamilyJ1DependencyCount": 4,
        "assignedRequirementCount": 414,
        "routingGroupCount": 45,
        "automatedJourneySeedCount": 12,
        "journeySeedRequirementUnionCount": 138,
        "assignedOutsideJourneySeedsCount": 276,
        "formalCaseCount": 0,
        "formalOracleCount": 0,
    }, "assignment counts")
    check(assignment["issuance"]["formalRecordIssuanceAllowed"] is False, "assignment issuance")

    package_json = read_json("web/package.json", WORKTREE)
    package_lock = read_json("web/package-lock.json", WORKTREE)
    config = (WORKTREE / "web/playwright.config.ts").read_text(encoding="utf-8")
    check(audit["repositoryFacts"]["webPackage"] == artifact("web/package.json", WORKTREE), "audit package artifact")
    check(audit["repositoryFacts"]["webPackageLock"] == artifact("web/package-lock.json", WORKTREE), "audit lock artifact")
    check(audit["repositoryFacts"]["playwrightConfig"] == artifact("web/playwright.config.ts", WORKTREE), "audit config artifact")
    check(audit["repositoryFacts"]["nodeEngineRange"] == package_json["engines"]["node"], "audit Node range")
    check(audit["repositoryFacts"]["playwrightDependency"] == package_json["devDependencies"]["@playwright/test"], "audit Playwright dependency")
    check(audit["repositoryFacts"]["lockedPlaywrightPackageVersion"] == package_lock["packages"]["node_modules/@playwright/test"]["version"], "audit Playwright lock")
    check(audit["repositoryFacts"]["configuredProject"] == re.search(r"name:\s*'([^']+)'", config).group(1), "audit project")
    check(audit["repositoryFacts"]["configuredDeviceAlias"] == re.search(r"devices\['([^']+)'\]", config).group(1), "audit device alias")
    viewport = re.search(r"viewport:\s*\{\s*width:\s*(\d+),\s*height:\s*(\d+)\s*\}", config)
    check(audit["repositoryFacts"]["configuredViewport"] == {"width": int(viewport.group(1)), "height": int(viewport.group(2))}, "audit viewport")
    check(audit["repositoryFacts"]["reuseExistingServer"] is ("reuseExistingServer: true" in config), "audit server reuse")
    for path in PROPOSED_MANIFEST_PATHS:
        check(not (SPEC / path).exists(), f"proposed manifest remains absent: {path}")
    check([entry["path"] for entry in audit["missingManifestPaths"]] == PROPOSED_MANIFEST_PATHS, "missing manifest path order")
    bootstrap = read_json("conformance-bootstrap-order-v1.json")
    predecessors = [step["executionClass"] for step in bootstrap["steps"] if 2 <= step["ordinal"] <= 5 and step["executable"] is not True]
    check(audit["predecessorExecutionClassesPending"] == predecessors, "predecessor set")
    check(len(predecessors) == 4, "predecessor count")
    expected_audit_codes = [item for item in CLOSED_REJECTION_CODES if item != "EDITOR_OBSERVATION_ADAPTER_MISMATCH"]
    check(audit["admissionDecision"]["rejectionCodes"] == expected_audit_codes, "audit rejection codes")
    check(audit["admissionDecision"]["result"] == "REJECTED", "audit decision")
    for field in ["exactTargetManifestIssued", "automationRunnerManifestIssued", "browserAutomationExecuted", "productExecutionEvidence", "recordIssuanceAllowed", "executable"]:
        check(audit["admissionDecision"][field] is False, f"audit negative boundary: {field}")
    check(audit["admissionDecision"]["formalEditorCaseCount"] == 0, "audit formal cases")
    check(audit["admissionDecision"]["formalEditorOracleCount"] == 0, "audit formal oracles")
    check(audit["j1Boundary"] == {
        "status": "PENDING_SEPARATE_HUMAN_RECORD",
        "evaluatedByThisAudit": False,
        "blocksAutomatedEvidenceSubstitution": True,
    }, "audit J1 boundary")

    result = {
        "resultVersion": "renderweave-editor-automation-admission-static-result/1.0",
        "executorId": "EDITOR_ADMISSION_STATIC::PYTHON::1.0",
        "role": "independent-editor-admission-contract-replayer",
        "executionClass": EXECUTION_CLASS,
        "implementationRevision": IMPLEMENTATION_REVISION,
        "status": "PASS_INDEPENDENT_STATIC_CONTRACT_REJECTED_PRODUCT_ADMISSION",
        "checkCount": check_count,
        "failureCount": 0,
        "contract": artifact(CONTRACT_PATH),
        "assignment": artifact(ASSIGNMENT_PATH),
        "readinessAudit": artifact(AUDIT_PATH),
        "assignedRequirementCount": assignment["counts"]["assignedRequirementCount"],
        "exactTargetManifestIssued": False,
        "automationRunnerManifestIssued": False,
        "browserAutomationExecuted": False,
        "productExecutionEvidence": False,
        "recordIssuanceAllowed": False,
    }
    (SPEC / RESULT_PATH).write_bytes(encoded(result))
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"independent Editor automation admission validation failed: {error}", file=sys.stderr)
        raise
