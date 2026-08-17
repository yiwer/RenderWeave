from __future__ import annotations

import hashlib
import json
import platform
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0"
BASE_PROFILE_ID = "renderweave-conformance-probes/1.0"
CANDIDATE_PROFILE_ID = "renderweave-conformance-probes/1.1"
BASE_HASHES = {
    "conformance-probe-profile-v1.json": "sha256:f800eb1e6e138215c26c7761ed80e0fc9cf77fc3ce051be4e3c5ba530cd6053d",
    "spec-registry/assertion-vectors-v1.json": "sha256:291d450f0a1bfa84124664827b2e83fb562545561de9c9fd973070d0eb26762c",
    "editor-automated/observation-adapter-v1.json": "sha256:7f65c78c0d390e7ab972275b43341e94174d267ec4dadd28b71c60223fbe45d7",
}
PATHS = {
    "base_profile": "conformance-probe-profile-v1.json",
    "base_vectors": "spec-registry/assertion-vectors-v1.json",
    "base_adapter": "editor-automated/observation-adapter-v1.json",
    "adjudication": "editor-automated/probe-profile-adjudication-v1.json",
    "vectors": "editor-automated/probe-assertion-vectors-candidate-v1_1.json",
    "profile": "editor-automated/probe-profile-candidate-v1_1.json",
    "adapter": "editor-automated/observation-adapter-candidate-v1_1.json",
    "audit": "editor-automated/probe-profile-candidate-readiness-audit-v1_1.json",
    "primary": "editor-automated/probe-profile-candidate-primary-result-v1_1.json",
    "independent": "editor-automated/probe-profile-candidate-independent-result-v1_1.json",
}


def read_json(relative_path: str):
    return json.loads((ROOT / relative_path).read_text(encoding="utf-8"))


def digest(relative_path: str) -> str:
    return "sha256:" + hashlib.sha256((ROOT / relative_path).read_bytes()).hexdigest()


def artifact(relative_path: str) -> dict:
    target = ROOT / relative_path
    return {"path": relative_path, "sha256": digest(relative_path), "byteLength": target.stat().st_size}


checks: list[dict] = []


def check(name: str, passed: bool, details=None) -> None:
    checks.append({"name": name, "pass": bool(passed), "details": details})


base_profile = read_json(PATHS["base_profile"])
base_vectors = read_json(PATHS["base_vectors"])
base_adapter = read_json(PATHS["base_adapter"])
adjudication = read_json(PATHS["adjudication"])
vectors = read_json(PATHS["vectors"])
profile = read_json(PATHS["profile"])
adapter = read_json(PATHS["adapter"])
audit = read_json(PATHS["audit"])
primary = read_json(PATHS["primary"])

for relative_path, expected_hash in BASE_HASHES.items():
    check(f"base artifact hash remains exact: {relative_path}", digest(relative_path) == expected_hash, artifact(relative_path))

check(
    "base identities remain issued 1.0",
    base_profile.get("candidateProbeProfileId") == BASE_PROFILE_ID
    and base_vectors.get("probeProfile") == BASE_PROFILE_ID
    and base_adapter.get("probeProfile") == BASE_PROFILE_ID,
)

decisions = adjudication.get("decisions", [])
source_ids = [entry.get("sourceProbeId") for entry in decisions]
candidate_ids_from_decisions = {entry.get("candidateProbeId") for entry in decisions}
check("adjudication has 12 unique source proposals", len(decisions) == 12 and len(set(source_ids)) == 12, source_ids)
check("adjudication converges to 9 candidate probes", len(candidate_ids_from_decisions) == 9, sorted(candidate_ids_from_decisions))
check(
    "adjudication split is 7 unchanged plus 5 consolidated",
    sum(entry.get("decision") == "APPROVED_UNCHANGED" for entry in decisions) == 7
    and sum(entry.get("decision") == "CONSOLIDATED_INTO" for entry in decisions) == 5,
    adjudication.get("counts"),
)
check(
    "baseline triple converges to one closed probe",
    {
        entry.get("candidateProbeId")
        for entry in decisions
        if entry.get("sourceProbeId")
        in {
            "editor.canonicalBaselineContentHash",
            "editor.canonicalBaselineDigest",
            "editor.canonicalBaselineRevision",
        }
    }
    == {"editor.canonicalBaselineBytes"},
)
check(
    "recovery pair converges to one safe envelope probe",
    {
        entry.get("candidateProbeId")
        for entry in decisions
        if entry.get("sourceProbeId") in {"editor.recoveryDraftBytes", "editor.recoveryDraftPresent"}
    }
    == {"editor.recoveryDraftEnvelopeBytes"},
)
design_rule = adjudication.get("designRule", {})
check(
    "generic and raw-state test seams remain forbidden",
    design_rule.get("genericStateSnapshotAllowed") is False
    and design_rule.get("rawRecoveryDraftAllowed") is False
    and design_rule.get("rawWorkingCopyAllowed") is False
    and design_rule.get("arbitraryScriptAllowed") is False
    and design_rule.get("wildcardAllowed") is False,
    design_rule,
)

probes = profile.get("probes", [])
probe_ids = [probe.get("probeId") for probe in probes]
new_probes = probes[len(base_profile.get("probes", [])) :]
check(
    "candidate is complete and not issued",
    profile.get("candidateProbeProfileId") == CANDIDATE_PROFILE_ID
    and profile.get("status") == "COMPLETE_CANDIDATE_NOT_ISSUED"
    and profile.get("recordMayReference") is False
    and bool(profile.get("issuanceBlockers")),
)
check(
    "base probes are retained exactly before 9 additions",
    probes[: len(base_profile.get("probes", []))] == base_profile.get("probes", [])
    and len(new_probes) == 9
    and len(probes) == 119
    and profile.get("probeCount") == 119,
)
check("candidate probe IDs are unique", len(set(probe_ids)) == len(probe_ids))
check("new probe set matches adjudication", {probe.get("probeId") for probe in new_probes} == candidate_ids_from_decisions)
check(
    "working-copy digest explicitly permits absence when no trusted DesignDSL working copy exists",
    next(probe for probe in new_probes if probe.get("probeId") == "editor.workingCopyDigest").get("allowedOperators")
    == ["EQ", "ABSENT"],
)
check(
    "every new probe is Editor-only and closed-operator bounded",
    all(
        probe.get("executionClasses") == [EXECUTION_CLASS]
        and probe.get("allowedOperators")
        and set(probe.get("allowedOperators", [])) <= {"EQ", "ABSENT", "BYTES_EQ"}
        for probe in new_probes
    ),
)

probe_by_id = {probe["probeId"]: probe for probe in probes}
accepted = vectors.get("acceptedVectors", [])
rejected = vectors.get("rejectedVectors", [])
accepted_keys: dict[tuple[str, str], int] = {}
for vector in accepted:
    assertion = vector.get("assertion", {})
    key = (vector.get("probeId"), assertion.get("operator"))
    accepted_keys[key] = accepted_keys.get(key, 0) + 1

check(
    "accepted vectors cover each allowed operator exactly once",
    all(accepted_keys.get((probe["probeId"], operator)) == 1 for probe in probes for operator in probe["allowedOperators"]),
)
check(
    "vector counts are 157 accepted and 119 rejected",
    len(accepted) == vectors.get("acceptedVectorCount") == 157
    and len(rejected) == vectors.get("rejectedVectorCount") == 119,
    {"accepted": len(accepted), "rejected": len(rejected)},
)
check(
    "one rejected vector exists per probe",
    len({entry.get("probeId") for entry in rejected}) == 119
    and all(sum(entry.get("probeId") == probe_id for entry in rejected) == 1 for probe_id in probe_ids),
)
check(
    "delta vector IDs are contiguous and exact",
    vectors.get("delta", {}).get("acceptedVectorIds") == [f"ASSERT-OK-{ordinal:03d}" for ordinal in range(144, 158)]
    and vectors.get("delta", {}).get("rejectedVectorIds") == [f"ASSERT-NO-{ordinal:03d}" for ordinal in range(111, 120)],
    vectors.get("delta"),
)


def accepted_shape(vector: dict) -> bool:
    probe = probe_by_id.get(vector.get("probeId"))
    assertion = vector.get("assertion", {})
    if probe is None or assertion.get("operator") not in probe.get("allowedOperators", []):
        return False
    operator = assertion.get("operator")
    if operator == "ABSENT":
        return "expected" not in assertion
    expected = assertion.get("expected", {})
    if operator == "BYTES_EQ":
        return expected.get("kind") == "ARTIFACT" and isinstance(expected.get("artifactSha256"), str) and len(expected["artifactSha256"]) == 71
    if operator == "SEQUENCE_EQ":
        value = expected.get("value")
        return expected.get("kind") == "LITERAL" and isinstance(value, list)
    if operator == "WITHIN":
        value = expected.get("value", {})
        return expected.get("kind") == "LITERAL" and all(
            isinstance(value.get(bound), dict)
            and isinstance(value[bound].get("numerator"), int)
            and isinstance(value[bound].get("denominator"), int)
            and value[bound]["denominator"] > 0
            for bound in ("minimum", "maximum")
        )
    if operator != "EQ" or expected.get("kind") != "LITERAL":
        return False
    value = expected.get("value")
    value_type = probe.get("valueType")
    if value_type == "BOOLEAN":
        return type(value) is bool
    if value_type == "INTEGER":
        return type(value) is int
    if value_type in {"TEXT", "CODE", "STAGE", "DIGEST", "DECIMAL"}:
        return isinstance(value, str)
    return False


check("accepted vector shapes replay independently", all(accepted_shape(vector) for vector in accepted))

editor_probe_ids = {
    probe["probeId"] for probe in probes if EXECUTION_CLASS in probe.get("executionClasses", [])
}
mappings = adapter.get("mappings", [])
mapping_ids = [mapping.get("probeId") for mapping in mappings]
check(
    "candidate Adapter maps all and only 40 Editor probes once",
    adapter.get("probeProfile") == CANDIDATE_PROFILE_ID
    and adapter.get("mappingCount") == len(mappings) == 40
    and len(set(mapping_ids)) == 40
    and set(mapping_ids) == editor_probe_ids,
    {"adapter": len(mappings), "profile": len(editor_probe_ids)},
)
check(
    "working-copy Adapter preserves explicit absence",
    next(mapping for mapping in mappings if mapping.get("probeId") == "editor.workingCopyDigest").get("absentPolicy")
    == "EXPLICIT_ABSENT",
)
check(
    "base Adapter mappings are retained exactly",
    mappings[: len(base_adapter.get("mappings", []))] == base_adapter.get("mappings", []),
)
check(
    "candidate Adapter remains a narrow non-generic seam",
    adapter.get("genericJsonPathAllowed") is False
    and adapter.get("arbitraryScriptAllowed") is False
    and adapter.get("fallbackAllowed") is False
    and adapter.get("expectedValuesVisibleToTarget") is False,
)
recovery_contract = adapter.get("projectionContracts", {}).get("recoveryDraftEnvelopeBytes", {})
check(
    "recovery projection excludes runtime and raw content",
    recovery_contract.get("rawRecoveryDraftAllowed") is False
    and recovery_contract.get("requiredFalseMembers")
    == ["containsRootDocument", "containsCustomValues", "containsPreviewImage", "containsAssetBytes"],
    recovery_contract,
)
check(
    "readiness and primary results agree on static pass without issuance",
    audit.get("status") == "STATIC_CANDIDATE_COMPLETE_NOT_ISSUED"
    and audit.get("issuanceBoundary", {}).get("candidateRecordMayReference") is False
    and primary.get("status") == "PASS_STATIC_CANDIDATE_NOT_ISSUED"
    and primary.get("passed") is True,
)
check(
    "global Profile issuance stays separate from Editor record execution",
    profile.get("issuanceBlockers")
    == [
        "EXPLICIT_PROFILE_1_1_ISSUANCE_AUTHORITY_PENDING",
        "CURRENT_PROFILE_1_0_SUPERSESSION_RECORD_PENDING",
    ]
    and audit.get("gateSeparation", {}).get("globalProfileCandidateGate", {}).get("editorProductTargetRequiredForProfileIssuance") is False
    and audit.get("gateSeparation", {}).get("editorRecordGate", {}).get("independentProductReplayPending") is True,
    audit.get("gateSeparation"),
)
check(
    "zero-execution boundary remains false",
    all(value is False for key, value in audit.get("issuanceBoundary", {}).items() if key not in {"candidateComplete"}),
    audit.get("issuanceBoundary"),
)

passed = all(entry["pass"] for entry in checks)
result = {
    "artifactVersion": "renderweave-editor-probe-profile-candidate-independent-result/1.1",
    "status": "PASS_STATIC_CANDIDATE_NOT_ISSUED" if passed else "FAIL",
    "runtime": {
        "engine": "python",
        "version": platform.python_version(),
        "role": "independent-candidate-schema-and-coverage-replayer",
    },
    "artifacts": {
        "adjudication": artifact(PATHS["adjudication"]),
        "probeProfile": artifact(PATHS["profile"]),
        "assertionVectors": artifact(PATHS["vectors"]),
        "observationAdapter": artifact(PATHS["adapter"]),
        "readinessAudit": artifact(PATHS["audit"]),
        "primaryResult": artifact(PATHS["primary"]),
    },
    "checkCount": len(checks),
    "failureCount": sum(not entry["pass"] for entry in checks),
    "checks": checks,
    "passed": passed,
    "boundary": "Independent static replay only; no profile issuance, product code, browser, network, formal JSONL, J1, or READY evidence.",
}
(ROOT / PATHS["independent"]).write_bytes((json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
print(json.dumps({"status": result["status"], "checkCount": len(checks), "failed": [entry["name"] for entry in checks if not entry["pass"]]}, ensure_ascii=False, indent=2))
raise SystemExit(0 if passed else 1)
