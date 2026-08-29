#!/usr/bin/env python3
"""Verify the fail-closed Renderer tricky-font candidate compatibility decision."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any


DECISION_VERSION = "renderweave-renderer-tricky-font-compatibility-decision/1.0"
DECISION_ID = "rw-renderer-tricky-font-compatibility-000001"
DECISION_STATUS = "BLOCKED_CANDIDATE_SEMANTIC_CONTRADICTION"
CANDIDATE_ID = "rw-renderer-spike-linux-x86_64-v2-000001"
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


def verify(repo: Path, decision_path: str) -> dict[str, Any]:
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
