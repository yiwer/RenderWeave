#!/usr/bin/env python3
"""Verify the fail-closed Renderer tricky-font candidate compatibility decision."""

from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any


DECISION_VERSION = "renderweave-renderer-tricky-font-compatibility-decision/1.0"
DECISION_ID = "rw-renderer-tricky-font-compatibility-000001"
DECISION_STATUS = "BLOCKED_CANDIDATE_SEMANTIC_CONTRADICTION"
CANDIDATE_ID = "rw-renderer-spike-linux-x86_64-v2-000001"
DECISION_VERSION_V2 = "renderweave-renderer-tricky-font-compatibility-decision/1.1"
DECISION_ID_V2 = "rw-renderer-tricky-font-compatibility-000002"
DECISION_STATUS_V2 = "NEW_CANDIDATE_CLASSIFICATION_COMPILE_PATH_COMPATIBLE_BUILD_UNPROVEN"
CANDIDATE_ID_V2 = "rw-renderer-spike-linux-x86_64-v2-000002"
DECISION_VERSION_V3 = "renderweave-renderer-tricky-font-compatibility-decision/1.2"
DECISION_ID_V3 = "rw-renderer-tricky-font-compatibility-000003"
DECISION_STATUS_V3 = "SUCCESSOR_MECHANICAL_CONFIGURATION_VALID_EXACT_BUILD_PENDING"
CANDIDATE_ID_V3 = "rw-renderer-spike-linux-x86_64-v2-000003"
FREETYPE_COMMIT = "0a0221a1347e2f1e07c395263540026e9a0aa7c7"
FREETYPE_TREE = "589225074ab1eb876682820c482069693c251e88"
UPSTREAM_SOURCE_SHA256 = "sha256:c381554e81a00f9d5c430e7c51e1d6c289958867426b021a6165eb12b451922d"
INPUT_PATHS = [
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/source-integrity-target-manifest-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-fixture-policy-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftoption.h",
    ".scratch/renderweave-template-v1/renderer-spike/hermetic-linux-build-prerequisites-v1.json",
    "renderer/process-manifest.json",
]
INPUT_PATHS_V2 = [
    "specs/changes/20260831-renderer-tricky-font-classification-candidate-v2.md",
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/candidate-supersessions-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/source-integrity-target-manifest-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-fixture-policy-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftoption-v2.h",
    ".scratch/renderweave-template-v1/renderer-spike/hermetic-linux-build-prerequisites-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/application-order-v2.json",
    "renderer/process-manifest.json",
]
INPUT_PATHS_V3 = [
    "specs/changes/20260831-renderer-candidate-v3-mechanical-correction.md",
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/candidate-supersessions-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/candidate-supersessions-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v3.json",
    ".scratch/renderweave-template-v1/renderer-spike/source-integrity-target-manifest-v3.json",
    ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftoption-v3.h",
    ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftmodule-v3.h",
    ".scratch/renderweave-template-v1/renderer-spike/skia-m151-freetype-policy.patch",
    ".scratch/renderweave-template-v1/renderer-spike/skia-m151-freetype-policy-v3.patch",
    ".scratch/renderweave-template-v1/renderer-spike/hermetic-linux-build-prerequisites-v3.json",
    ".scratch/renderweave-template-v1/renderer-spike/application-order-v3.json",
    "renderer/probes/t213/rehearsal-result-v1.json",
    "renderer/process-manifest.json",
]
REQUIRED_LOAD_FLAGS = [
    "FT_LOAD_NO_HINTING",
    "FT_LOAD_NO_AUTOHINT",
    "FT_LOAD_NO_BITMAP",
    "FT_LOAD_NO_SVG",
]
FORBIDDEN_LOAD_FLAGS = ["FT_LOAD_FORCE_AUTOHINT", "FT_LOAD_COLOR"]
REQUIRED_PROPERTIES = [
    "FreeType classifies the face as FT_IS_TRICKY",
    "the corpus distinguishes NO_HINTING alone from NO_HINTING plus NO_AUTOHINT",
    "source recipe, license, exact bytes, and SHA-256 are committed",
    "independent replay runs on every Certified Renderer target",
]


class VerificationFailure(RuntimeError):
    pass


class Verifier:
    def __init__(self) -> None:
        self.check_count = 0

    def require(self, condition: bool, code: str, detail: Any) -> None:
        self.check_count += 1
        if not condition:
            raise VerificationFailure(f"{code}: {detail}")


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def duplicate_safe_pairs(
    verifier: Verifier, pairs: list[tuple[str, Any]]
) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        verifier.require(key not in value, "JSON_DUPLICATE_MEMBER", key)
        value[key] = item
    return value


def decode_json(verifier: Verifier, data: bytes, location: object) -> dict[str, Any]:
    verifier.require(not data.startswith(b"\xef\xbb\xbf"), "JSON_BOM", location)
    verifier.require(data.endswith(b"\n"), "JSON_FINAL_LF", location)
    verifier.require(b"\r" not in data, "JSON_LF_ONLY", location)
    try:
        value = json.loads(
            data.decode("utf-8", "strict"),
            object_pairs_hook=lambda pairs: duplicate_safe_pairs(verifier, pairs),
            parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)),
        )
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"JSON_INVALID: {location}: {error}") from error
    verifier.require(isinstance(value, dict), "JSON_ROOT", location)
    return value


def resolve_file(verifier: Verifier, repo: Path, relative: str) -> Path:
    pure = PurePosixPath(relative)
    verifier.require(not pure.is_absolute(), "PATH_ABSOLUTE", relative)
    verifier.require("\\" not in relative and ".." not in pure.parts, "PATH_UNSAFE", relative)
    path = (repo / Path(*pure.parts)).resolve()
    root = repo.resolve()
    verifier.require(path.is_relative_to(root), "PATH_ESCAPE", relative)
    verifier.require(path.is_file(), "PATH_MISSING", relative)
    return path


def read_json(
    verifier: Verifier, repo: Path, relative: str
) -> tuple[bytes, dict[str, Any]]:
    path = resolve_file(verifier, repo, relative)
    data = path.read_bytes()
    return data, decode_json(verifier, data, relative)


def binding(relative: str, data: bytes) -> dict[str, Any]:
    return {"path": relative, "sha256": sha256(data), "byteLength": len(data)}


def require_members(
    verifier: Verifier, value: dict[str, Any], expected: set[str], code: str
) -> None:
    verifier.require(set(value) == expected, code, sorted(value))


def macro_directives(data: bytes) -> list[tuple[str, str]]:
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise VerificationFailure(f"HEADER_UTF8: {error}") from error
    return [
        (match.group(1), match.group(2))
        for line in text.splitlines()
        if (match := re.fullmatch(r"\s*#\s*(define|undef)\s+([A-Z0-9_]+).*", line))
    ]


def verify_v1(repo: Path, decision_path: str) -> dict[str, Any]:
    verifier = Verifier()
    decision_bytes, decision = read_json(verifier, repo, decision_path)
    require_members(
        verifier,
        decision,
        {
            "artifactVersion", "decisionId", "status", "candidateId", "sourceFact",
            "inputs", "observedContradiction", "enforcedBoundary", "unresolvedDecision",
        },
        "DECISION_MEMBERS",
    )
    verifier.require(decision["artifactVersion"] == DECISION_VERSION, "DECISION_VERSION", decision)
    verifier.require(decision["decisionId"] == DECISION_ID, "DECISION_ID", decision)
    verifier.require(decision["status"] == DECISION_STATUS, "DECISION_STATUS", decision)
    verifier.require(decision["candidateId"] == CANDIDATE_ID, "CANDIDATE_ID", decision)

    inputs = decision["inputs"]
    verifier.require(isinstance(inputs, list), "INPUTS_TYPE", type(inputs).__name__)
    verifier.require([item.get("path") for item in inputs] == INPUT_PATHS, "INPUT_ORDER", inputs)
    input_values: dict[str, dict[str, Any]] = {}
    input_bytes: dict[str, bytes] = {}
    for item, relative in zip(inputs, INPUT_PATHS, strict=True):
        require_members(verifier, item, {"path", "sha256", "byteLength"}, "INPUT_MEMBERS")
        data = resolve_file(verifier, repo, relative).read_bytes()
        verifier.require(item == binding(relative, data), "INPUT_BINDING", relative)
        input_bytes[relative] = data
        if relative.endswith(".json"):
            input_values[relative] = decode_json(verifier, data, relative)

    source_target = input_values[INPUT_PATHS[1]]
    verifier.require(source_target.get("targetKind") ==
                     "source identity and candidate patch applicability only",
                     "SOURCE_TARGET_KIND", source_target.get("targetKind"))
    freetype = source_target.get("freetype")
    verifier.require(isinstance(freetype, dict), "SOURCE_FREETYPE", freetype)
    verifier.require(freetype.get("commit") == FREETYPE_COMMIT, "SOURCE_COMMIT", freetype)
    verifier.require(freetype.get("tree") == FREETYPE_TREE, "SOURCE_TREE", freetype)
    patch_policy = (input_values[INPUT_PATHS[0]].get("candidateBuildContract", {})
                    .get("freetypePatchPolicy", {}))
    custom_headers = patch_policy.get("customFreetypeHeaders", [])
    options_headers = [
        item for item in custom_headers if item.get("macro") == "FT_CONFIG_OPTIONS_H"
    ]
    verifier.require(len(options_headers) == 1, "CANDIDATE_OPTIONS_HEADER", custom_headers)
    verifier.require(source_target.get("candidateArtifactSha256", {}).get("ftoption") ==
                     options_headers[0].get("bytesSha256"),
                     "CANDIDATE_FTOPTION_CROSS_BINDING", source_target)
    verifier.require(source_target.get("candidateArtifactSha256", {}).get("ftoption") ==
                     sha256(input_bytes[INPUT_PATHS[3]]).removeprefix("sha256:"),
                     "SOURCE_FTOPTION_BINDING", source_target)

    source_fact = decision["sourceFact"]
    require_members(
        verifier,
        source_fact,
        {
            "upstream", "commit", "tree", "path", "rawMirror", "sha256", "byteLength",
            "classificationCompileGuard", "classificationFunction", "assignedFaceFlag",
            "reviewedAt", "sourceRetained",
        },
        "SOURCE_FACT_MEMBERS",
    )
    verifier.require(source_fact == {
        "upstream": "FreeType",
        "commit": FREETYPE_COMMIT,
        "tree": FREETYPE_TREE,
        "path": "src/truetype/ttobjs.c",
        "rawMirror": (
            "https://raw.githubusercontent.com/freetype/freetype/"
            f"{FREETYPE_COMMIT}/src/truetype/ttobjs.c"
        ),
        "sha256": UPSTREAM_SOURCE_SHA256,
        "byteLength": 40600,
        "classificationCompileGuard": "TT_USE_BYTECODE_INTERPRETER",
        "classificationFunction": "tt_check_trickyness",
        "assignedFaceFlag": "FT_FACE_FLAG_TRICKY",
        "reviewedAt": "2026-08-29",
        "sourceRetained": False,
    }, "SOURCE_FACT", source_fact)

    candidate = input_values[INPUT_PATHS[0]]
    verifier.require(candidate.get("candidateId") == CANDIDATE_ID, "CANDIDATE_MANIFEST_ID", candidate)
    verifier.require(candidate.get("status") == "SPIKE_CANDIDATE", "CANDIDATE_STATUS", candidate)
    current = candidate.get("currentEvidence", {})
    verifier.require(current.get("ready") is False, "CANDIDATE_READY", current)
    verifier.require(current.get("certified") is False, "CANDIDATE_CERTIFIED", current)
    verifier.require(current.get("ticket19MayClose") is False, "CANDIDATE_TICKET19", current)
    verifier.require("TT_CONFIG_OPTION_BYTECODE_INTERPRETER" in
                     patch_policy.get("disabledFreetypeOptions", []),
                     "CANDIDATE_CONFIG_DISABLED", patch_policy)
    verifier.require("TT_USE_BYTECODE_INTERPRETER" in
                     patch_policy.get("disabledDerivedFreetypeMacros", []),
                     "CANDIDATE_DERIVED_DISABLED", patch_policy)

    policy = input_values[INPUT_PATHS[2]]
    verifier.require(policy.get("candidateId") == CANDIDATE_ID, "POLICY_CANDIDATE", policy)
    verifier.require(policy.get("status") == "POLICY_FROZEN_FIXTURE_BYTES_PENDING",
                     "POLICY_STATUS", policy)
    portable = policy.get("portableAuthority", {})
    verifier.require(portable.get("required") is True, "PORTABLE_REQUIRED", portable)
    verifier.require(portable.get("requiredProperties") == REQUIRED_PROPERTIES,
                     "PORTABLE_PROPERTIES", portable)
    verifier.require(portable.get("currentState") == "fixture source, bytes, and hash pending",
                     "PORTABLE_STATE", portable)
    verifier.require(policy.get("readyMayRelyOnlyOnProprietaryDiagnostic") is False,
                     "PROPRIETARY_NOT_AUTHORITY", policy)

    directives = macro_directives(input_bytes[INPUT_PATHS[3]])
    verifier.require(("undef", "TT_CONFIG_OPTION_BYTECODE_INTERPRETER") in directives,
                     "CONFIG_INTERPRETER_NOT_DISABLED", directives)
    verifier.require(("undef", "TT_USE_BYTECODE_INTERPRETER") in directives,
                     "DERIVED_INTERPRETER_NOT_DISABLED", directives)
    verifier.require(("define", "TT_CONFIG_OPTION_BYTECODE_INTERPRETER") not in directives,
                     "CONFIG_INTERPRETER_REDEFINED", directives)
    verifier.require(("define", "TT_USE_BYTECODE_INTERPRETER") not in directives,
                     "DERIVED_INTERPRETER_REDEFINED", directives)

    prerequisites = input_values[INPUT_PATHS[4]]
    verifier.require(prerequisites.get("candidateId") == CANDIDATE_ID,
                     "PREREQUISITE_CANDIDATE", prerequisites)
    verifier.require(prerequisites.get("status") == "PREREQUISITES_FROZEN_BUILD_NOT_AUTHORIZED",
                     "PREREQUISITE_STATUS", prerequisites)
    portable_gate = prerequisites.get("portableTrickyFontGate", {})
    verifier.require(portable_gate.get("currentStatus") == "PENDING", "PORTABLE_GATE", portable_gate)
    verifier.require("FT_IS_TRICKY proof" in portable_gate.get("requiredBindings", []),
                     "PORTABLE_PROOF_BINDING", portable_gate)
    for field in ("buildAuthorized", "buildAttemptedByThisDecision", "certified", "ready",
                  "ticket19MayClose"):
        verifier.require(prerequisites.get(field) is False, "PREREQUISITE_FALSE", field)

    process = input_values[INPUT_PATHS[5]]
    verifier.require(process.get("certificationStatus") == "NOT_CERTIFIED",
                     "PROCESS_CERTIFICATION", process)
    verifier.require(process.get("rasterImplementation") == "ABSENT", "PROCESS_RASTER", process)
    verifier.require(process.get("profileAvailability") == "NOT_REGISTERED",
                     "PROCESS_PROFILE", process)
    verifier.require(process.get("rendererProfiles") == [], "PROCESS_PROFILES", process)
    verifier.require(process.get("physicalCertificationRecords") == [], "PROCESS_PHYSICAL", process)

    expected_contradiction = {
        "portableAuthorityRequiresFtIsTricky": True,
        "candidateUndefinesTtConfigOptionBytecodeInterpreter": True,
        "candidateUndefinesTtUseBytecodeInterpreter": True,
        "classificationImplementationCompiled": False,
        "currentCandidateCanSatisfyPortableAuthority": False,
    }
    verifier.require(decision["observedContradiction"] == expected_contradiction,
                     "OBSERVED_CONTRADICTION", decision["observedContradiction"])
    expected_boundary = {
        "buildAuthorized": False,
        "buildAttemptedByThisDecision": False,
        "exactRendererTargetMayMaterialize": False,
        "rendererExactOutputPreissuanceReady": False,
        "rendererExactOutputRecordIssuanceAllowed": False,
        "certified": False,
        "ready": False,
        "ticket19MayClose": False,
    }
    verifier.require(decision["enforcedBoundary"] == expected_boundary,
                     "ENFORCED_BOUNDARY", decision["enforcedBoundary"])
    verifier.require(decision["unresolvedDecision"] == {
        "required": True,
        "authority": "product-semantics-owner",
        "choiceMadeByThisArtifact": False,
        "nextAction": (
            "Issue a new candidate configuration or revise the portable-authority contract "
            "before exact target materialization."
        ),
    }, "UNRESOLVED_DECISION", decision["unresolvedDecision"])

    return {
        "reportVersion": "renderweave-renderer-tricky-font-compatibility-gate/1.0",
        "status": "PASS_FAIL_CLOSED",
        "decisionStatus": DECISION_STATUS,
        "candidateId": CANDIDATE_ID,
        "checkCount": verifier.check_count,
        "failureCount": 0,
        "decision": binding(decision_path, decision_bytes),
        "inputs": inputs,
        "observedContradiction": expected_contradiction,
        "boundary": {
            **expected_boundary,
            "vendorSourceRetained": False,
            "fontBytesRead": 0,
            "networkAttempts": 0,
            "providerAttempts": 0,
        },
    }


def verify_v2(repo: Path, decision_path: str) -> dict[str, Any]:
    verifier = Verifier()
    decision_bytes, decision = read_json(verifier, repo, decision_path)
    require_members(
        verifier,
        decision,
        {
            "artifactVersion", "decisionId", "status", "candidateId",
            "supersedesDecisionId", "sourceFacts", "inputs",
            "approvedSemanticResolution", "observedCompatibility",
            "enforcedBoundary", "nextRequiredEvidence",
        },
        "DECISION_V2_MEMBERS",
    )
    verifier.require(
        decision["artifactVersion"] == DECISION_VERSION_V2,
        "DECISION_V2_VERSION",
        decision,
    )
    verifier.require(decision["decisionId"] == DECISION_ID_V2, "DECISION_V2_ID", decision)
    verifier.require(
        decision["status"] == DECISION_STATUS_V2,
        "DECISION_V2_STATUS",
        decision,
    )
    verifier.require(decision["candidateId"] == CANDIDATE_ID_V2, "CANDIDATE_V2_ID", decision)
    verifier.require(
        decision["supersedesDecisionId"] == DECISION_ID,
        "DECISION_V2_PREDECESSOR",
        decision,
    )

    inputs = decision["inputs"]
    verifier.require(isinstance(inputs, list), "INPUTS_V2_TYPE", type(inputs).__name__)
    verifier.require(
        [item.get("path") for item in inputs] == INPUT_PATHS_V2,
        "INPUTS_V2_ORDER",
        inputs,
    )
    input_values: dict[str, dict[str, Any]] = {}
    input_bytes: dict[str, bytes] = {}
    for item, relative in zip(inputs, INPUT_PATHS_V2, strict=True):
        require_members(verifier, item, {"path", "sha256", "byteLength"}, "INPUT_V2_MEMBERS")
        data = resolve_file(verifier, repo, relative).read_bytes()
        verifier.require(item == binding(relative, data), "INPUT_V2_BINDING", relative)
        input_bytes[relative] = data
        if relative.endswith(".json"):
            input_values[relative] = decode_json(verifier, data, relative)

    authority_path = INPUT_PATHS_V2[0]
    predecessor_path = INPUT_PATHS_V2[1]
    predecessor_decision_path = INPUT_PATHS_V2[2]
    supersessions_path = INPUT_PATHS_V2[3]
    candidate_path = INPUT_PATHS_V2[4]
    source_target_path = INPUT_PATHS_V2[5]
    policy_path = INPUT_PATHS_V2[6]
    header_path = INPUT_PATHS_V2[7]
    prerequisites_path = INPUT_PATHS_V2[8]
    application_order_path = INPUT_PATHS_V2[9]
    process_path = INPUT_PATHS_V2[10]

    predecessor = input_values[predecessor_path]
    predecessor_decision = input_values[predecessor_decision_path]
    verifier.require(predecessor.get("candidateId") == CANDIDATE_ID, "PREDECESSOR_ID", predecessor)
    verifier.require(
        predecessor_decision.get("decisionId") == DECISION_ID
        and predecessor_decision.get("status") == DECISION_STATUS
        and predecessor_decision.get("candidateId") == CANDIDATE_ID,
        "PREDECESSOR_DECISION",
        predecessor_decision,
    )
    verifier.require(
        sha256(input_bytes[predecessor_path])
        == "sha256:c649e60e94bd56785074a8bbf514af856885e615d32ecd1ea680cb27fb0358f8",
        "PREDECESSOR_IMMUTABLE_BYTES",
        predecessor_path,
    )
    verifier.require(
        sha256(input_bytes[predecessor_decision_path]) ==
        "sha256:8c2488ea27920b7762824f155f0db6a986216e65be2c35de609e3190d62ce5a5",
        "PREDECESSOR_DECISION_IMMUTABLE_BYTES",
        predecessor_decision_path,
    )

    supersessions = input_values[supersessions_path]
    require_members(
        verifier,
        supersessions,
        {"artifactVersion", "status", "mutationAllowed", "records"},
        "SUPERSESSIONS_MEMBERS",
    )
    verifier.require(
        supersessions["artifactVersion"] ==
        "renderweave-renderer-spike-candidate-supersessions/1.0",
        "SUPERSESSIONS_VERSION",
        supersessions,
    )
    verifier.require(
        supersessions["status"] == "APPEND_ONLY"
        and supersessions["mutationAllowed"] is False,
        "SUPERSESSIONS_APPEND_ONLY",
        supersessions,
    )
    records = supersessions["records"]
    verifier.require(isinstance(records, list) and len(records) == 1, "SUPERSESSION_COUNT", records)
    record = records[0]
    require_members(
        verifier,
        record,
        {
            "ordinal", "predecessorCandidateId", "predecessorPath",
            "predecessorSha256", "predecessorByteLength", "predecessorDecisionPath",
            "predecessorDecisionSha256", "successorCandidateId", "successorPath",
            "successorSha256", "successorByteLength", "semanticAuthorityPath",
            "semanticAuthoritySha256", "approvedAt", "reason",
        },
        "SUPERSESSION_RECORD_MEMBERS",
    )
    verifier.require(record["ordinal"] == 1, "SUPERSESSION_ORDINAL", record)
    verifier.require(
        record["predecessorCandidateId"] == CANDIDATE_ID
        and record["predecessorPath"] == predecessor_path
        and record["predecessorSha256"] == sha256(input_bytes[predecessor_path])
        and record["predecessorByteLength"] == len(input_bytes[predecessor_path]),
        "SUPERSESSION_PREDECESSOR_BINDING",
        record,
    )
    verifier.require(
        record["predecessorDecisionPath"] == predecessor_decision_path
        and record["predecessorDecisionSha256"] == sha256(input_bytes[predecessor_decision_path]),
        "SUPERSESSION_PREDECESSOR_DECISION_BINDING",
        record,
    )
    verifier.require(
        record["successorCandidateId"] == CANDIDATE_ID_V2
        and record["successorPath"] == candidate_path
        and record["successorSha256"] == sha256(input_bytes[candidate_path])
        and record["successorByteLength"] == len(input_bytes[candidate_path]),
        "SUPERSESSION_SUCCESSOR_BINDING",
        record,
    )
    verifier.require(
        record["semanticAuthorityPath"] == authority_path
        and record["semanticAuthoritySha256"] == sha256(input_bytes[authority_path])
        and record["approvedAt"] == "2026-08-31",
        "SUPERSESSION_AUTHORITY_BINDING",
        record,
    )

    candidate = input_values[candidate_path]
    verifier.require(candidate.get("candidateId") == CANDIDATE_ID_V2, "CANDIDATE_V2_MANIFEST_ID", candidate)
    verifier.require(candidate.get("status") == "SPIKE_CANDIDATE", "CANDIDATE_V2_STATUS", candidate)
    verifier.require(
        candidate.get("supersedesCandidateId") == CANDIDATE_ID
        and candidate.get("supersededBy") is None,
        "CANDIDATE_V2_SUCCESSION",
        candidate,
    )
    verifier.require(candidate.get("target") == predecessor.get("target"), "CANDIDATE_V2_TARGET_DRIFT", candidate)
    verifier.require(
        candidate.get("pinnedCandidates") == predecessor.get("pinnedCandidates"),
        "CANDIDATE_V2_PINS_DRIFT",
        candidate,
    )
    semantic = candidate.get("semanticAuthority", {})
    verifier.require(
        semantic.get("path") == authority_path
        and semantic.get("sha256") == sha256(input_bytes[authority_path]).removeprefix("sha256:")
        and semantic.get("byteLength") == len(input_bytes[authority_path])
        and semantic.get("approvedAt") == "2026-08-31",
        "CANDIDATE_V2_AUTHORITY",
        semantic,
    )
    patch_policy = candidate.get("candidateBuildContract", {}).get("freetypePatchPolicy", {})
    verifier.require(
        patch_policy.get("requiredFinalLoadFlags") == REQUIRED_LOAD_FLAGS,
        "CANDIDATE_REQUIRED_LOAD_FLAGS",
        patch_policy.get("requiredFinalLoadFlags"),
    )
    verifier.require(
        patch_policy.get("forbiddenFinalLoadFlags") == FORBIDDEN_LOAD_FLAGS,
        "CANDIDATE_FORBIDDEN_LOAD_FLAGS",
        patch_policy.get("forbiddenFinalLoadFlags"),
    )
    compile_policy = patch_policy.get("classificationCompilePolicy", {})
    verifier.require(
        compile_policy.get("requiredFreetypeOptions") ==
        ["TT_CONFIG_OPTION_BYTECODE_INTERPRETER"],
        "CANDIDATE_CONFIG_REQUIRED",
        compile_policy,
    )
    verifier.require(
        compile_policy.get("requiredDerivedFreetypeMacros") ==
        ["TT_USE_BYTECODE_INTERPRETER"],
        "CANDIDATE_DERIVED_REQUIRED",
        compile_policy,
    )
    verifier.require(
        compile_policy.get("runtimeHintingAuthorized") is False,
        "CANDIDATE_RUNTIME_HINTING",
        compile_policy,
    )
    verifier.require(
        "TT_CONFIG_OPTION_BYTECODE_INTERPRETER" not in
        patch_policy.get("disabledFreetypeOptions", []),
        "CANDIDATE_CONFIG_DISABLED",
        patch_policy,
    )
    verifier.require(
        "TT_USE_BYTECODE_INTERPRETER" not in
        patch_policy.get("disabledDerivedFreetypeMacros", []),
        "CANDIDATE_DERIVED_DISABLED",
        patch_policy,
    )
    runtime_policy = patch_policy.get("runtimeBytecodePolicy", {})
    verifier.require(
        runtime_policy == {
            "executionAllowed": False,
            "sourceConfigurationProofSufficient": False,
            "instrumentedBuiltTargetProofRequired": True,
            "currentProof": "PENDING",
        },
        "CANDIDATE_RUNTIME_POLICY",
        runtime_policy,
    )
    current = candidate.get("currentEvidence", {})
    for field in (
        "buildAuthorized", "exactRendererTargetMayMaterialize",
        "rendererExactOutputPreissuanceReady",
        "rendererExactOutputRecordIssuanceAllowed", "ready", "certified",
        "ticket19MayClose",
    ):
        verifier.require(current.get(field) is False, "CANDIDATE_V2_LIFECYCLE_FALSE", field)

    source_target = input_values[source_target_path]
    verifier.require(
        source_target.get("candidateId") == CANDIDATE_ID_V2,
        "SOURCE_TARGET_V2_CANDIDATE",
        source_target,
    )
    verifier.require(
        source_target.get("targetKind") ==
        "source identity and candidate configuration compatibility only",
        "SOURCE_TARGET_V2_KIND",
        source_target,
    )
    freetype = source_target.get("freetype", {})
    verifier.require(
        freetype.get("commit") == FREETYPE_COMMIT
        and freetype.get("tree") == FREETYPE_TREE,
        "SOURCE_TARGET_V2_FREETYPE",
        freetype,
    )
    verifier.require(
        freetype.get("classificationCompileFacts") == {
            "requiredOption": "TT_CONFIG_OPTION_BYTECODE_INTERPRETER",
            "derivedGuard": "TT_USE_BYTECODE_INTERPRETER",
            "classificationPath": "src/truetype/ttobjs.c::tt_check_trickyness",
            "assignedFlag": "FT_FACE_FLAG_TRICKY",
        },
        "SOURCE_TARGET_V2_CLASSIFICATION",
        freetype,
    )
    verifier.require(
        source_target.get("classificationCompilePathCompatible") is True
        and source_target.get("runtimeBytecodeNonExecutionProven") is False
        and source_target.get("rendererTarget") is False
        and source_target.get("ready") is False,
        "SOURCE_TARGET_V2_BOUNDARY",
        source_target,
    )
    artifacts = source_target.get("candidateArtifactSha256", {})
    verifier.require(
        artifacts.get("ftoption") == sha256(input_bytes[header_path]).removeprefix("sha256:"),
        "SOURCE_TARGET_V2_FTOPTION_BINDING",
        artifacts,
    )
    verifier.require(
        artifacts.get("applicationOrder") ==
        sha256(input_bytes[application_order_path]).removeprefix("sha256:"),
        "SOURCE_TARGET_V2_APPLICATION_ORDER_BINDING",
        artifacts,
    )
    verifier.require(
        artifacts.get("fixturePolicy") == sha256(input_bytes[policy_path]).removeprefix("sha256:"),
        "SOURCE_TARGET_V2_POLICY_BINDING",
        artifacts,
    )
    custom_headers = patch_policy.get("customFreetypeHeaders", [])
    options_headers = [
        item for item in custom_headers if item.get("macro") == "FT_CONFIG_OPTIONS_H"
    ]
    verifier.require(len(options_headers) == 1, "CANDIDATE_V2_OPTIONS_HEADER", custom_headers)
    verifier.require(
        options_headers[0].get("path") == "renderer-spike/rw-freetype-ftoption-v2.h"
        and options_headers[0].get("bytesSha256") == artifacts.get("ftoption"),
        "CANDIDATE_V2_FTOPTION_CROSS_BINDING",
        options_headers,
    )
    patch_identity = patch_policy.get("patchIdentity", {})
    verifier.require(
        patch_identity.get("applicationOrderPath") == "renderer-spike/application-order-v2.json"
        and patch_identity.get("applicationOrderSha256") == artifacts.get("applicationOrder"),
        "CANDIDATE_V2_APPLICATION_ORDER_CROSS_BINDING",
        patch_identity,
    )
    fixture_policy = patch_policy.get("trickyFontFixturePolicy", {})
    verifier.require(
        fixture_policy.get("path") == "renderer-spike/tricky-font-fixture-policy-v2.json"
        and fixture_policy.get("sha256") == artifacts.get("fixturePolicy"),
        "CANDIDATE_V2_POLICY_CROSS_BINDING",
        fixture_policy,
    )

    header_bytes = input_bytes[header_path]
    directives = macro_directives(header_bytes)
    verifier.require(
        ("undef", "TT_CONFIG_OPTION_BYTECODE_INTERPRETER") not in directives,
        "HEADER_CONFIG_INTERPRETER_DISABLED",
        directives,
    )
    verifier.require(
        ("undef", "TT_USE_BYTECODE_INTERPRETER") not in directives,
        "HEADER_DERIVED_INTERPRETER_DISABLED",
        directives,
    )
    verifier.require(
        ("define", "TT_CONFIG_OPTION_BYTECODE_INTERPRETER") not in directives
        and ("define", "TT_USE_BYTECODE_INTERPRETER") not in directives,
        "HEADER_INTERPRETER_REDEFINED",
        directives,
    )
    header_text = header_bytes.decode("utf-8", "strict")
    verifier.require(
        "#include <freetype/config/ftoption.h>" in header_text,
        "HEADER_STOCK_OPTIONS_INCLUDE",
        header_path,
    )
    verifier.require(
        re.search(r"(?m)^#ifndef TT_CONFIG_OPTION_BYTECODE_INTERPRETER$", header_text)
        is not None,
        "HEADER_CONFIG_ASSERTION",
        header_path,
    )
    verifier.require(
        re.search(r"(?m)^#ifndef TT_USE_BYTECODE_INTERPRETER$", header_text) is not None,
        "HEADER_DERIVED_ASSERTION",
        header_path,
    )

    policy = input_values[policy_path]
    verifier.require(policy.get("candidateId") == CANDIDATE_ID_V2, "POLICY_V2_CANDIDATE", policy)
    verifier.require(
        policy.get("status") == "POLICY_FROZEN_FIXTURE_BYTES_PENDING",
        "POLICY_V2_STATUS",
        policy,
    )
    verifier.require(
        policy.get("portableAuthority", {}).get("requiredProperties") == REQUIRED_PROPERTIES,
        "POLICY_V2_PROPERTIES",
        policy,
    )
    verifier.require(
        policy.get("classificationCompilePolicy", {}).get("sourceCompatibilityOnly") is True,
        "POLICY_V2_SOURCE_ONLY",
        policy,
    )
    runtime_gate = policy.get("runtimeNonExecutionPolicy", {})
    verifier.require(
        runtime_gate.get("requiredFinalLoadFlags") == REQUIRED_LOAD_FLAGS
        and runtime_gate.get("forbiddenFinalLoadFlags") == FORBIDDEN_LOAD_FLAGS
        and runtime_gate.get("instrumentedBuiltTargetProofRequired") is True
        and runtime_gate.get("currentState") == "pending",
        "POLICY_V2_RUNTIME_GATE",
        runtime_gate,
    )

    prerequisites = input_values[prerequisites_path]
    verifier.require(
        prerequisites.get("candidateId") == CANDIDATE_ID_V2
        and prerequisites.get("supersedesCandidateId") == CANDIDATE_ID,
        "PREREQUISITES_V2_CANDIDATE",
        prerequisites,
    )
    verifier.require(
        prerequisites.get("status") == "PREREQUISITES_FROZEN_BUILD_NOT_AUTHORIZED",
        "PREREQUISITES_V2_STATUS",
        prerequisites,
    )
    classification_gate = prerequisites.get("classificationAndRuntimeGate", {})
    verifier.require(
        classification_gate.get("classificationCompileMacrosRequired") == [
            "TT_CONFIG_OPTION_BYTECODE_INTERPRETER", "TT_USE_BYTECODE_INTERPRETER"
        ]
        and classification_gate.get("productionLoadFlagsRequired") == REQUIRED_LOAD_FLAGS
        and classification_gate.get("productionLoadFlagsForbidden") == FORBIDDEN_LOAD_FLAGS
        and classification_gate.get("allReachableLoadPathsMustBeInventoried") is True
        and classification_gate.get("instrumentedRuntimeBytecodeNonExecutionRequired") is True
        and classification_gate.get("instrumentedRuntimeBytecodeNonExecutionCurrent") == "PENDING",
        "PREREQUISITES_V2_RUNTIME_GATE",
        classification_gate,
    )
    for field in (
        "buildAuthorized", "buildAttemptedByThisDecision",
        "exactRendererTargetMayMaterialize", "rendererExactOutputPreissuanceReady",
        "rendererExactOutputRecordIssuanceAllowed", "certified", "ready",
        "ticket19MayClose",
    ):
        verifier.require(prerequisites.get(field) is False, "PREREQUISITES_V2_FALSE", field)

    application_order = input_values[application_order_path]
    verifier.require(
        application_order.get("candidateId") == CANDIDATE_ID_V2
        and application_order.get("supersedesCandidateId") == CANDIDATE_ID,
        "APPLICATION_ORDER_V2_CANDIDATE",
        application_order,
    )
    steps = application_order.get("steps", [])
    verifier.require(
        [step.get("ordinal") for step in steps] == [1, 2, 3, 4, 5],
        "APPLICATION_ORDER_V2_ORDINALS",
        steps,
    )
    verifier.require(
        steps[1].get("source") == "renderer-spike/rw-freetype-ftoption-v2.h"
        and steps[4].get("source") == "renderer-spike-candidate-v2.json candidateBuildContract",
        "APPLICATION_ORDER_V2_SOURCES",
        steps,
    )

    process = input_values[process_path]
    verifier.require(
        process.get("certificationStatus") == "NOT_CERTIFIED"
        and process.get("rasterImplementation") == "ABSENT"
        and process.get("profileAvailability") == "NOT_REGISTERED"
        and process.get("rendererProfiles") == []
        and process.get("physicalCertificationRecords") == [],
        "PROCESS_V2_BOUNDARY",
        process,
    )

    source_facts = decision["sourceFacts"]
    verifier.require(source_facts == {
        "upstream": "FreeType",
        "commit": FREETYPE_COMMIT,
        "tree": FREETYPE_TREE,
        "optionHeaderPath": "include/freetype/config/ftoption.h",
        "optionHeaderBlob": "b857e0ebbd9a5fed2b088c28ce409d9fbdbfd64e",
        "requiredConfigurationMacro": "TT_CONFIG_OPTION_BYTECODE_INTERPRETER",
        "derivedClassificationGuard": "TT_USE_BYTECODE_INTERPRETER",
        "classificationSourcePath": "src/truetype/ttobjs.c",
        "classificationSourceSha256": UPSTREAM_SOURCE_SHA256,
        "classificationSourceByteLength": 40600,
        "classificationFunction": "tt_check_trickyness",
        "assignedFaceFlag": "FT_FACE_FLAG_TRICKY",
        "reviewedAt": "2026-08-31",
        "sourceRetained": False,
    }, "SOURCE_FACTS_V2", source_facts)
    verifier.require(decision["approvedSemanticResolution"] == {
        "authority": "product-semantics-owner",
        "approvedAt": "2026-08-31",
        "choice": (
            "compile the exact FreeType interpreter path for tricky-face classification "
            "while forbidding runtime hinting through mandatory production glyph-load flags"
        ),
        "oldCandidateMutated": False,
        "buildOrCertificationAuthorized": False,
    }, "APPROVED_SEMANTIC_RESOLUTION", decision["approvedSemanticResolution"])
    expected_compatibility = {
        "portableAuthorityRequiresFtIsTricky": True,
        "candidateRetainsTtConfigOptionBytecodeInterpreter": True,
        "candidateRetainsTtUseBytecodeInterpreter": True,
        "candidateHeaderAssertsBothMacros": True,
        "classificationImplementationCompiled": True,
        "mandatoryNoHintingLoadFlagsDeclared": True,
        "currentCandidateCanSatisfyPortableAuthority": True,
        "runtimeBytecodeNonExecutionProven": False,
        "exactBuiltTargetObserved": False,
    }
    verifier.require(
        decision["observedCompatibility"] == expected_compatibility,
        "OBSERVED_COMPATIBILITY",
        decision["observedCompatibility"],
    )
    expected_boundary = {
        "buildAuthorized": False,
        "buildAttemptedByThisDecision": False,
        "exactRendererTargetMayMaterialize": False,
        "rendererExactOutputPreissuanceReady": False,
        "rendererExactOutputRecordIssuanceAllowed": False,
        "certified": False,
        "ready": False,
        "ticket19MayClose": False,
    }
    verifier.require(
        decision["enforcedBoundary"] == expected_boundary,
        "ENFORCED_BOUNDARY_V2",
        decision["enforcedBoundary"],
    )
    verifier.require(decision["nextRequiredEvidence"] == {
        "portableFixtureBytes": "PENDING",
        "hermeticBuild": "PENDING_FRESH_AUTHORIZATION",
        "allGlyphLoadPathAudit": "PENDING",
        "instrumentedRuntimeBytecodeNonExecution": "PENDING",
        "physicalLinuxReplay": "PENDING",
        "centralAcceptanceRebind": "PENDING_SEPARATE_TICKET",
    }, "NEXT_REQUIRED_EVIDENCE_V2", decision["nextRequiredEvidence"])

    return {
        "reportVersion": "renderweave-renderer-tricky-font-compatibility-gate/1.1",
        "status": "PASS_NEW_CANDIDATE_CLASSIFICATION_COMPATIBLE_FAIL_CLOSED",
        "decisionStatus": DECISION_STATUS_V2,
        "candidateId": CANDIDATE_ID_V2,
        "predecessorCandidateId": CANDIDATE_ID,
        "checkCount": verifier.check_count,
        "failureCount": 0,
        "decision": binding(decision_path, decision_bytes),
        "inputs": inputs,
        "observedCompatibility": expected_compatibility,
        "boundary": {
            **expected_boundary,
            "vendorSourceRetained": False,
            "fontBytesRead": 0,
            "networkAttempts": 0,
            "providerAttempts": 0,
        },
    }


def verify_v3(repo: Path, decision_path: str) -> dict[str, Any]:
    verifier = Verifier()
    decision_bytes, decision = read_json(verifier, repo, decision_path)
    require_members(
        verifier,
        decision,
        {
            "artifactVersion", "decisionId", "status", "candidateId",
            "supersedesDecisionId", "diagnosis", "inputs",
            "approvedMechanicalResolution", "observedCompatibility",
            "enforcedBoundary", "nextRequiredEvidence",
        },
        "DECISION_V3_MEMBERS",
    )
    verifier.require(
        decision["artifactVersion"] == DECISION_VERSION_V3,
        "DECISION_V3_VERSION",
        decision,
    )
    verifier.require(decision["decisionId"] == DECISION_ID_V3, "DECISION_V3_ID", decision)
    verifier.require(
        decision["status"] == DECISION_STATUS_V3,
        "DECISION_V3_STATUS",
        decision,
    )
    verifier.require(decision["candidateId"] == CANDIDATE_ID_V3, "CANDIDATE_V3_ID", decision)
    verifier.require(
        decision["supersedesDecisionId"] == DECISION_ID_V2,
        "DECISION_V3_PREDECESSOR",
        decision,
    )

    inputs = decision["inputs"]
    verifier.require(isinstance(inputs, list), "INPUTS_V3_TYPE", type(inputs).__name__)
    verifier.require(
        [item.get("path") for item in inputs] == INPUT_PATHS_V3,
        "INPUTS_V3_ORDER",
        inputs,
    )
    input_values: dict[str, dict[str, Any]] = {}
    input_bytes: dict[str, bytes] = {}
    for item, relative in zip(inputs, INPUT_PATHS_V3, strict=True):
        require_members(verifier, item, {"path", "sha256", "byteLength"}, "INPUT_V3_MEMBERS")
        data = resolve_file(verifier, repo, relative).read_bytes()
        verifier.require(item == binding(relative, data), "INPUT_V3_BINDING", relative)
        input_bytes[relative] = data
        if relative.endswith(".json"):
            input_values[relative] = decode_json(verifier, data, relative)

    authority_path = INPUT_PATHS_V3[0]
    predecessor_decision_path = INPUT_PATHS_V3[1]
    predecessor_path = INPUT_PATHS_V3[2]
    prior_registry_path = INPUT_PATHS_V3[3]
    supersessions_path = INPUT_PATHS_V3[4]
    candidate_path = INPUT_PATHS_V3[5]
    source_target_path = INPUT_PATHS_V3[6]
    options_path = INPUT_PATHS_V3[7]
    modules_path = INPUT_PATHS_V3[8]
    predecessor_patch_path = INPUT_PATHS_V3[9]
    patch_path = INPUT_PATHS_V3[10]
    prerequisites_path = INPUT_PATHS_V3[11]
    application_order_path = INPUT_PATHS_V3[12]
    diagnosis_path = INPUT_PATHS_V3[13]
    process_path = INPUT_PATHS_V3[14]

    verifier.require(
        sha256(input_bytes[predecessor_decision_path]) ==
        "sha256:c7672cb5ec6627414521f29a342a6d8d4d71804a7cd147e1eb3564999e1a7e79",
        "PREDECESSOR_V2_DECISION_IMMUTABLE_BYTES",
        predecessor_decision_path,
    )
    verifier.require(
        sha256(input_bytes[predecessor_path]) ==
        "sha256:f245a597df86105ad6e5635e7b0041e0fbd2fe50f2f4499183842ebbe0351b71",
        "PREDECESSOR_V2_IMMUTABLE_BYTES",
        predecessor_path,
    )
    verifier.require(
        sha256(input_bytes[prior_registry_path]) ==
        "sha256:1882d188ed8c689973d08b55a53b2d209ea28a1646879be26aebab026c178a49",
        "SUPERSESSIONS_V1_IMMUTABLE_BYTES",
        prior_registry_path,
    )
    verifier.require(
        sha256(input_bytes[predecessor_patch_path]) ==
        "sha256:a573de549efe6deb7673aec7046f39731a9847ce9453a78b076b10eed338e28d",
        "PREDECESSOR_V2_PATCH_IMMUTABLE_BYTES",
        predecessor_patch_path,
    )
    base_report = verify_v2(repo, predecessor_decision_path)
    verifier.require(
        base_report["candidateId"] == CANDIDATE_ID_V2
        and base_report["failureCount"] == 0,
        "PREDECESSOR_V2_REPLAY",
        base_report,
    )

    prior_registry = input_values[prior_registry_path]
    supersessions = input_values[supersessions_path]
    require_members(
        verifier,
        supersessions,
        {"artifactVersion", "status", "mutationAllowed", "priorRegistry", "records"},
        "SUPERSESSIONS_V2_MEMBERS",
    )
    verifier.require(
        supersessions["artifactVersion"] ==
        "renderweave-renderer-spike-candidate-supersessions/1.1"
        and supersessions["status"] == "APPEND_ONLY_SUCCESSOR"
        and supersessions["mutationAllowed"] is False,
        "SUPERSESSIONS_V2_HEADER",
        supersessions,
    )
    verifier.require(
        supersessions["priorRegistry"] == {
            "path": "renderer-spike/candidate-supersessions-v1.json",
            "sha256": sha256(input_bytes[prior_registry_path]),
            "byteLength": len(input_bytes[prior_registry_path]),
        },
        "SUPERSESSIONS_V2_PRIOR_BINDING",
        supersessions["priorRegistry"],
    )
    records = supersessions["records"]
    verifier.require(isinstance(records, list) and len(records) == 2, "SUPERSESSIONS_V2_COUNT", records)
    verifier.require(
        records[0] == prior_registry["records"][0],
        "SUPERSESSIONS_V2_PRIOR_RECORD",
        records[0],
    )
    successor_record = records[1]
    verifier.require(successor_record.get("ordinal") == 2, "SUPERSESSION_V3_ORDINAL", successor_record)
    verifier.require(
        successor_record.get("predecessorCandidateId") == CANDIDATE_ID_V2
        and successor_record.get("predecessorPath") == predecessor_path
        and successor_record.get("predecessorSha256") == sha256(input_bytes[predecessor_path])
        and successor_record.get("predecessorByteLength") == len(input_bytes[predecessor_path])
        and successor_record.get("predecessorDecisionPath") == predecessor_decision_path
        and successor_record.get("predecessorDecisionSha256") ==
        sha256(input_bytes[predecessor_decision_path]),
        "SUPERSESSION_V3_PREDECESSOR_BINDING",
        successor_record,
    )
    verifier.require(
        successor_record.get("successorCandidateId") == CANDIDATE_ID_V3
        and successor_record.get("successorPath") == candidate_path
        and successor_record.get("successorSha256") == sha256(input_bytes[candidate_path])
        and successor_record.get("successorByteLength") == len(input_bytes[candidate_path])
        and successor_record.get("semanticAuthorityPath") == authority_path
        and successor_record.get("semanticAuthoritySha256") == sha256(input_bytes[authority_path])
        and successor_record.get("approvedAt") == "2026-08-31"
        and successor_record.get("diagnosedBy") == "T213 d05ca309",
        "SUPERSESSION_V3_SUCCESSOR_BINDING",
        successor_record,
    )

    predecessor = input_values[predecessor_path]
    candidate = input_values[candidate_path]
    require_members(
        verifier,
        candidate,
        {
            "artifactVersion", "candidateId", "status", "authority",
            "supersedesCandidateId", "supersededBy", "semanticAuthority",
            "baseCandidate", "identityRules", "target", "candidateBuildContract",
            "currentEvidence",
        },
        "CANDIDATE_V3_MEMBERS",
    )
    verifier.require(
        candidate["artifactVersion"] == "renderweave-renderer-spike-candidate/1.1"
        and candidate["candidateId"] == CANDIDATE_ID_V3
        and candidate["status"] == "SPIKE_CANDIDATE_SOURCE_CORRECTION_BUILD_PENDING",
        "CANDIDATE_V3_HEADER",
        candidate,
    )
    verifier.require(
        candidate["supersedesCandidateId"] == CANDIDATE_ID_V2
        and candidate["supersededBy"] is None,
        "CANDIDATE_V3_SUCCESSION",
        candidate,
    )
    verifier.require(candidate["target"] == predecessor["target"], "CANDIDATE_V3_TARGET_DRIFT", candidate)
    verifier.require(
        candidate["baseCandidate"] == {
            "path": predecessor_path,
            "candidateId": CANDIDATE_ID_V2,
            "sha256": sha256(input_bytes[predecessor_path]),
            "byteLength": len(input_bytes[predecessor_path]),
            "compositionRule": (
                "inherit the complete immutable v2 contract and replace only the closed "
                "mechanicalCorrectionContract artifacts and paths"
            ),
        },
        "CANDIDATE_V3_BASE_BINDING",
        candidate["baseCandidate"],
    )
    semantic = candidate["semanticAuthority"]
    verifier.require(
        semantic.get("path") == authority_path
        and semantic.get("sha256") == sha256(input_bytes[authority_path]).removeprefix("sha256:")
        and semantic.get("byteLength") == len(input_bytes[authority_path])
        and semantic.get("approvedAt") == "2026-08-31",
        "CANDIDATE_V3_AUTHORITY_BINDING",
        semantic,
    )
    verifier.require(
        candidate["identityRules"] == {
            "mutableInPlace": False,
            "predecessorMutationAllowed": False,
            "failureOrSourceChange": "issue a new candidateId and append a new supersession artifact",
            "certificationUpgradeInPlace": False,
        },
        "CANDIDATE_V3_IDENTITY_RULES",
        candidate["identityRules"],
    )

    build_contract = candidate["candidateBuildContract"]
    require_members(
        verifier,
        build_contract,
        {"linkage", "mechanicalCorrectionContract", "unchangedRuntimePolicy"},
        "CANDIDATE_V3_BUILD_MEMBERS",
    )
    verifier.require(build_contract["linkage"] == "static", "CANDIDATE_V3_LINKAGE", build_contract)
    runtime_policy = build_contract["unchangedRuntimePolicy"]
    verifier.require(
        runtime_policy == {
            "classificationCompileMacrosRequired": [
                "TT_CONFIG_OPTION_BYTECODE_INTERPRETER", "TT_USE_BYTECODE_INTERPRETER"
            ],
            "productionLoadFlagsRequired": REQUIRED_LOAD_FLAGS,
            "productionLoadFlagsForbidden": FORBIDDEN_LOAD_FLAGS,
            "runtimeHintingAuthorized": False,
            "instrumentedRuntimeBytecodeNonExecutionRequired": True,
            "instrumentedRuntimeBytecodeNonExecutionCurrent": "PENDING",
        },
        "CANDIDATE_V3_RUNTIME_POLICY",
        runtime_policy,
    )
    correction = build_contract["mechanicalCorrectionContract"]
    require_members(
        verifier,
        correction,
        {
            "nativeFreetypeIncludeRoot", "customFreetypeHeaders", "patch",
            "applicationOrder", "prerequisites", "forbiddenAdapters",
        },
        "CANDIDATE_V3_CORRECTION_MEMBERS",
    )
    verifier.require(
        correction["nativeFreetypeIncludeRoot"] ==
        "third_party/externals/freetype/include",
        "CANDIDATE_V3_INCLUDE_ROOT",
        correction,
    )
    verifier.require(
        correction["forbiddenAdapters"] == [
            "force-include stock ftoption.h",
            "undefine or replay a guarded module header",
            "host FreeType or fontconfig fallback",
        ],
        "CANDIDATE_V3_FORBIDDEN_ADAPTERS",
        correction,
    )
    headers = correction["customFreetypeHeaders"]
    verifier.require(isinstance(headers, list) and len(headers) == 2, "CANDIDATE_V3_HEADER_COUNT", headers)
    options_header, modules_header = headers
    verifier.require(
        options_header.get("macro") == "FT_CONFIG_OPTIONS_H"
        and options_header.get("macroValue") == "<renderweave/ftoption.h>"
        and options_header.get("sourcePath") == "renderer-spike/rw-freetype-ftoption-v3.h"
        and options_header.get("installPath") ==
        "third_party/externals/freetype/include/renderweave/ftoption.h"
        and options_header.get("bytesSha256") ==
        sha256(input_bytes[options_path]).removeprefix("sha256:"),
        "CANDIDATE_V3_OPTIONS_BINDING",
        options_header,
    )
    verifier.require(
        modules_header.get("macro") == "FT_CONFIG_MODULES_H"
        and modules_header.get("macroValue") == "<renderweave/ftmodule.h>"
        and modules_header.get("sourcePath") == "renderer-spike/rw-freetype-ftmodule-v3.h"
        and modules_header.get("installPath") ==
        "third_party/externals/freetype/include/renderweave/ftmodule.h"
        and modules_header.get("bytesSha256") ==
        sha256(input_bytes[modules_path]).removeprefix("sha256:"),
        "CANDIDATE_V3_MODULES_BINDING",
        modules_header,
    )
    verifier.require(
        correction["patch"] == {
            "path": "renderer-spike/skia-m151-freetype-policy-v3.patch",
            "sha256": sha256(input_bytes[patch_path]).removeprefix("sha256:"),
            "semanticDeltaFromV2": "configuration header macro paths and include root only",
        },
        "CANDIDATE_V3_PATCH_BINDING",
        correction["patch"],
    )
    verifier.require(
        correction["applicationOrder"] == {
            "path": "renderer-spike/application-order-v3.json",
            "sha256": sha256(input_bytes[application_order_path]).removeprefix("sha256:"),
        },
        "CANDIDATE_V3_ORDER_BINDING",
        correction["applicationOrder"],
    )
    verifier.require(
        correction["prerequisites"] == {
            "path": "renderer-spike/hermetic-linux-build-prerequisites-v3.json",
            "sha256": sha256(input_bytes[prerequisites_path]).removeprefix("sha256:"),
        },
        "CANDIDATE_V3_PREREQUISITES_BINDING",
        correction["prerequisites"],
    )

    options_text = input_bytes[options_path].decode("utf-8", "strict")
    options_directives = macro_directives(input_bytes[options_path])
    verifier.require(
        options_text.count('#include "../freetype/config/ftoption.h"') == 1,
        "OPTIONS_V3_STOCK_INCLUDE",
        options_path,
    )
    verifier.require(
        "#include <freetype/config/ftoption.h>" not in options_text,
        "OPTIONS_V3_SELF_SHADOWING_INCLUDE",
        options_path,
    )
    verifier.require(
        ("undef", "TT_CONFIG_OPTION_BYTECODE_INTERPRETER") not in options_directives
        and ("undef", "TT_USE_BYTECODE_INTERPRETER") not in options_directives,
        "OPTIONS_V3_INTERPRETER_DISABLED",
        options_directives,
    )
    verifier.require(
        re.search(r"(?m)^#ifndef TT_CONFIG_OPTION_BYTECODE_INTERPRETER$", options_text)
        is not None
        and re.search(r"(?m)^#ifndef TT_USE_BYTECODE_INTERPRETER$", options_text) is not None,
        "OPTIONS_V3_CLASSIFICATION_ASSERTIONS",
        options_path,
    )
    resolved_stock = posixpath.normpath(
        posixpath.join(
            posixpath.dirname(options_header["installPath"]),
            "../freetype/config/ftoption.h",
        )
    )
    verifier.require(
        resolved_stock ==
        "third_party/externals/freetype/include/freetype/config/ftoption.h"
        and resolved_stock != options_header["installPath"],
        "OPTIONS_V3_RESOLVED_STOCK_PATH",
        resolved_stock,
    )

    modules_text = input_bytes[modules_path].decode("utf-8", "strict")
    verifier.require(
        re.search(r"(?m)^\s*#\s*(?:if|ifdef|ifndef|define|pragma)\b", modules_text)
        is None,
        "MODULES_V3_REPEATABILITY_GUARD",
        modules_path,
    )
    expected_modules = [
        "FT_USE_MODULE(FT_Driver_ClassRec, tt_driver_class)",
        "FT_USE_MODULE(FT_Driver_ClassRec, cff_driver_class)",
        "FT_USE_MODULE(FT_Module_Class, sfnt_module_class)",
        "FT_USE_MODULE(FT_Module_Class, psaux_module_class)",
        "FT_USE_MODULE(FT_Module_Class, psnames_module_class)",
        "FT_USE_MODULE(FT_Renderer_Class, ft_smooth_renderer_class)",
    ]
    module_body = re.sub(r"/\*.*?\*/", "", modules_text, flags=re.DOTALL)
    module_lines = [line.strip() for line in module_body.splitlines() if line.strip()]
    verifier.require(module_lines == expected_modules, "MODULES_V3_EXACT_EXPANSION", module_lines)

    predecessor_patch = input_bytes[predecessor_patch_path]
    expected_patch = predecessor_patch.replace(
        b"FT_CONFIG_MODULES_H=<renderweave-freetype/freetype/config/ftmodule.h>",
        b"FT_CONFIG_MODULES_H=<renderweave/ftmodule.h>",
    ).replace(
        b"FT_CONFIG_OPTIONS_H=<renderweave-freetype/freetype/config/ftoption.h>",
        b"FT_CONFIG_OPTIONS_H=<renderweave/ftoption.h>",
    ).replace(
        b'+    public_include_dirs += [ "include/renderweave-freetype" ]\n',
        b"",
    ).replace(
        b"@@ -36,19 +36,11 @@ if (skia_use_system_freetype2) {",
        b"@@ -36,19 +36,10 @@ if (skia_use_system_freetype2) {",
    )
    def normalize_build_index(value: bytes) -> bytes:
        return re.sub(
            rb"(?m)^index 631b909\.\.[0-9a-f]{7} 100644$",
            b"index 631b909..POSTIMG 100644",
            value,
        )

    verifier.require(
        normalize_build_index(input_bytes[patch_path]) == normalize_build_index(expected_patch),
        "PATCH_V3_MECHANICAL_DELTA_ONLY",
        patch_path,
    )

    application_order = input_values[application_order_path]
    verifier.require(
        application_order.get("candidateId") == CANDIDATE_ID_V3
        and application_order.get("supersedesCandidateId") == CANDIDATE_ID_V2
        and application_order.get("status") ==
        "SOURCE_APPLICATION_ORDER_FROZEN_EXACT_BUILD_PENDING",
        "APPLICATION_ORDER_V3_HEADER",
        application_order,
    )
    steps = application_order.get("steps", [])
    verifier.require(
        [step.get("ordinal") for step in steps] == [1, 2, 3, 4, 5],
        "APPLICATION_ORDER_V3_ORDINALS",
        steps,
    )
    verifier.require(
        steps[1].get("source") == "renderer-spike/rw-freetype-ftoption-v3.h"
        and steps[1].get("target") == options_header["installPath"]
        and steps[2].get("source") == "renderer-spike/rw-freetype-ftmodule-v3.h"
        and steps[2].get("target") == modules_header["installPath"]
        and steps[3].get("source") == "renderer-spike/skia-m151-freetype-policy-v3.patch",
        "APPLICATION_ORDER_V3_CORRECTION_PATHS",
        steps,
    )
    verifier.require(
        application_order.get("forbiddenAdapters") == correction["forbiddenAdapters"],
        "APPLICATION_ORDER_V3_ADAPTERS",
        application_order,
    )

    prerequisites = input_values[prerequisites_path]
    verifier.require(
        prerequisites.get("candidateId") == CANDIDATE_ID_V3
        and prerequisites.get("supersedesCandidateId") == CANDIDATE_ID_V2,
        "PREREQUISITES_V3_CANDIDATE",
        prerequisites,
    )
    mechanical = prerequisites.get("mechanicalConfiguration", {})
    verifier.require(
        mechanical.get("nativeFreetypeIncludeRoot") ==
        correction["nativeFreetypeIncludeRoot"]
        and mechanical.get("customOptionsPath") == options_header["installPath"]
        and mechanical.get("customModulesPath") == modules_header["installPath"]
        and mechanical.get("moduleHeaderMustBeRepeatable") is True
        and mechanical.get("adapterAllowed") is False,
        "PREREQUISITES_V3_MECHANICAL",
        mechanical,
    )
    authorization = prerequisites.get("t213BuildAuthorization", {})
    verifier.require(
        authorization.get("authorized") is True
        and authorization.get("exercisedByThisDecision") is False,
        "PREREQUISITES_V3_BUILD_AUTHORIZATION",
        authorization,
    )
    for field in (
        "exactBuiltTargetObserved", "rendererExactOutputPreissuanceReady",
        "rendererExactOutputRecordIssuanceAllowed", "physicalLinuxReplayComplete",
        "certified", "ready", "ticket19MayClose",
    ):
        verifier.require(prerequisites.get(field) is False, "PREREQUISITES_V3_FALSE", field)

    source_target = input_values[source_target_path]
    verifier.require(
        source_target.get("candidateId") == CANDIDATE_ID_V3
        and source_target.get("targetKind") ==
        "source identity and mechanically composable candidate configuration only",
        "SOURCE_TARGET_V3_HEADER",
        source_target,
    )
    upstream = source_target.get("upstream", {})
    verifier.require(
        upstream.get("skiaCommit") == predecessor["pinnedCandidates"]["skia"]["gitCommit"]
        and upstream.get("skiaTree") == predecessor["pinnedCandidates"]["skia"]["gitTree"]
        and upstream.get("freetypeCommit") == FREETYPE_COMMIT
        and upstream.get("freetypeTree") == FREETYPE_TREE,
        "SOURCE_TARGET_V3_UPSTREAM",
        upstream,
    )
    artifacts = source_target.get("candidateArtifactSha256", {})
    verifier.require(
        artifacts == {
            "candidate": sha256(input_bytes[candidate_path]).removeprefix("sha256:"),
            "patch": sha256(input_bytes[patch_path]).removeprefix("sha256:"),
            "ftoption": sha256(input_bytes[options_path]).removeprefix("sha256:"),
            "ftmodule": sha256(input_bytes[modules_path]).removeprefix("sha256:"),
            "applicationOrder": sha256(input_bytes[application_order_path]).removeprefix("sha256:"),
            "prerequisites": sha256(input_bytes[prerequisites_path]).removeprefix("sha256:"),
        },
        "SOURCE_TARGET_V3_ARTIFACT_BINDINGS",
        artifacts,
    )
    expected_mechanical = {
        "nativeFreetypeIncludeRoot": "third_party/externals/freetype/include",
        "optionsHeaderInstallPath": options_header["installPath"],
        "optionsHeaderStockRelativeInclude": "../freetype/config/ftoption.h",
        "resolvedStockOptionsPath": resolved_stock,
        "optionsHeaderSelfShadowing": False,
        "modulesHeaderInstallPath": modules_header["installPath"],
        "modulesHeaderIncludeGuard": False,
        "moduleExpansionCountPerInclusion": len(module_lines),
        "t213AdapterRequired": False,
    }
    verifier.require(
        source_target.get("mechanicalCompatibility") == expected_mechanical,
        "SOURCE_TARGET_V3_MECHANICAL",
        source_target.get("mechanicalCompatibility"),
    )
    verifier.require(
        source_target.get("classificationCompilePathCompatible") is True
        and source_target.get("mechanicalConfigurationDefectsCorrected") is True
        and source_target.get("runtimeBytecodeNonExecutionProven") is False
        and source_target.get("exactBuiltTargetObserved") is False
        and source_target.get("rendererTarget") is False
        and source_target.get("ready") is False,
        "SOURCE_TARGET_V3_BOUNDARY",
        source_target,
    )

    diagnosis = input_values[diagnosis_path]
    adapter_ids = [item.get("id") for item in diagnosis.get("candidateV2BuildAdapters", [])]
    verifier.require(
        diagnosis.get("status") == "ADAPTER_REHEARSAL_PASSED_EXACT_CANDIDATE_BUILD_BLOCKED"
        and adapter_ids == ["stock-ftoption-preinclude", "ftmodule-repeat-include-shim"],
        "DIAGNOSIS_V3_ADAPTERS",
        diagnosis,
    )
    verifier.require(
        decision["diagnosis"] == {
            "path": diagnosis_path,
            "sha256": sha256(input_bytes[diagnosis_path]),
            "status": "ADAPTER_REHEARSAL_PASSED_EXACT_CANDIDATE_BUILD_BLOCKED",
            "candidateV2BuildAdapters": adapter_ids,
        },
        "DECISION_V3_DIAGNOSIS_BINDING",
        decision["diagnosis"],
    )
    process = input_values[process_path]
    verifier.require(
        process.get("certificationStatus") == "NOT_CERTIFIED"
        and process.get("rasterImplementation") == "ABSENT"
        and process.get("profileAvailability") == "NOT_REGISTERED"
        and process.get("rendererProfiles") == []
        and process.get("physicalCertificationRecords") == [],
        "PROCESS_V3_BOUNDARY",
        process,
    )
    verifier.require(
        decision["approvedMechanicalResolution"] == {
            "authority": "product-semantics-owner",
            "approvedAt": "2026-08-31",
            "choice": (
                "issue immutable candidate 000003 with non-shadowing stock options inclusion "
                "and repeatable guard-free module expansion"
            ),
            "semanticBehaviorChanged": False,
            "predecessorBytesMutated": False,
            "t213ExactBuildRehearsalAuthorizedSeparately": True,
        },
        "APPROVED_MECHANICAL_RESOLUTION_V3",
        decision["approvedMechanicalResolution"],
    )
    expected_compatibility = {
        "portableAuthorityRequiresFtIsTricky": True,
        "candidateRetainsTtConfigOptionBytecodeInterpreter": True,
        "candidateRetainsTtUseBytecodeInterpreter": True,
        "stockOptionsReachable": True,
        "optionsHeaderSelfShadowing": False,
        "moduleListRepeatable": True,
        "moduleExpansionCountPerInclusion": 6,
        "t213AdapterRequired": False,
        "mandatoryNoHintingLoadFlagsDeclared": True,
        "currentCandidateMechanicallyBuildable": True,
        "runtimeBytecodeNonExecutionProven": False,
        "exactBuiltTargetObserved": False,
    }
    verifier.require(
        decision["observedCompatibility"] == expected_compatibility,
        "OBSERVED_COMPATIBILITY_V3",
        decision["observedCompatibility"],
    )
    expected_boundary = {
        "buildAttemptedByThisDecision": False,
        "exactBuiltTargetObserved": False,
        "rendererExactOutputPreissuanceReady": False,
        "rendererExactOutputRecordIssuanceAllowed": False,
        "physicalLinuxReplayComplete": False,
        "certified": False,
        "ready": False,
        "ticket19MayClose": False,
    }
    verifier.require(
        decision["enforcedBoundary"] == expected_boundary,
        "ENFORCED_BOUNDARY_V3",
        decision["enforcedBoundary"],
    )
    expected_next = {
        "successorOfflineClosure": "T213",
        "exactBuildWithoutAdapters": "T213",
        "allGlyphLoadPathAudit": "T213",
        "instrumentedRuntimeBytecodeNonExecution": "T213",
        "twoCleanReproducibleRehearsals": "T213",
        "physicalLinuxReplay": "T214",
        "centralAcceptanceRebind": "T215",
    }
    verifier.require(
        decision["nextRequiredEvidence"] == expected_next,
        "NEXT_REQUIRED_EVIDENCE_V3",
        decision["nextRequiredEvidence"],
    )
    for field in (
        "exactBuiltTargetObserved", "rendererExactOutputPreissuanceReady",
        "rendererExactOutputRecordIssuanceAllowed", "physicalLinuxReplayComplete",
        "ready", "certified", "ticket19MayClose",
    ):
        verifier.require(candidate["currentEvidence"].get(field) is False, "CANDIDATE_V3_FALSE", field)

    return {
        "reportVersion": "renderweave-renderer-tricky-font-compatibility-gate/1.2",
        "status": "PASS_SUCCESSOR_MECHANICALLY_BUILDABLE_BUILD_PENDING",
        "decisionStatus": DECISION_STATUS_V3,
        "candidateId": CANDIDATE_ID_V3,
        "predecessorCandidateId": CANDIDATE_ID_V2,
        "checkCount": base_report["checkCount"] + verifier.check_count,
        "failureCount": 0,
        "decision": binding(decision_path, decision_bytes),
        "inputs": inputs,
        "observedCompatibility": expected_compatibility,
        "boundary": {
            **expected_boundary,
            "vendorSourceRetained": False,
            "fontBytesRead": 0,
            "networkAttempts": 0,
            "providerAttempts": 0,
        },
    }


def verify(repo: Path, decision_path: str) -> dict[str, Any]:
    probe = Verifier()
    _, decision = read_json(probe, repo, decision_path)
    decision_id = decision.get("decisionId")
    if decision_id == DECISION_ID:
        return verify_v1(repo, decision_path)
    if decision_id == DECISION_ID_V2:
        return verify_v2(repo, decision_path)
    if decision_id == DECISION_ID_V3:
        return verify_v3(repo, decision_path)
    raise VerificationFailure(f"DECISION_UNSUPPORTED: {decision_id}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--decision", required=True)
    parser.add_argument("--report", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        repo = args.repo.resolve()
        if args.report.exists():
            raise VerificationFailure(f"REPORT_EXISTS: {args.report}")
        report = verify(repo, args.decision)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(
            "Renderer tricky-font compatibility: "
            f"{report['status']} checks={report['checkCount']} candidate={report['candidateId']}"
        )
        return 0
    except (OSError, VerificationFailure) as error:
        print(f"Renderer tricky-font compatibility failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
