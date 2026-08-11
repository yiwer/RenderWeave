#!/usr/bin/env python3
"""Independent, payload-free verifier for RenderWeave visual evaluation evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterable


VERIFIER_VERSION = "renderweave-visual-evidence-verifier/1.0"
AUTH_VERSION = "renderweave-visual-evaluation-authorization/1.0"
CORPUS_VERSION = "renderweave-visual-stage-corpus/1.0"
JOURNAL_VERSION = "renderweave-visual-evaluation-journal/1.0"
GOAL_VERSION = "renderweave-visual-evaluation-goal-budget/1.0"
GOAL_ID = "renderweave-visual-recognition-vnext-20260810"
GOAL_GUARD_VERSION = "renderweave-visual-evaluation-goal-guard/4.0"
PREVIOUS_GOAL_GUARD_VERSION = "renderweave-visual-evaluation-goal-guard/3.0"
PREVIOUS_V2_GOAL_GUARD_VERSION = "renderweave-visual-evaluation-goal-guard/2.0"
LEGACY_GOAL_GUARD_VERSION = "renderweave-visual-evaluation-goal-guard/1.0"
JOURNAL_GUARD_VERSION = "renderweave-visual-evaluation-journal-guard/1.0"
REPORT_VERSION = "renderweave-visual-stage-report/1.0"
LEGACY_IDENTITY_VERSION = "renderweave-visual-evaluation-tree-sha256/1"
IDENTITY_VERSION = "renderweave-visual-evaluation-tree-sha256/2"
SUPPORTED_IDENTITY_VERSIONS = {LEGACY_IDENTITY_VERSION, IDENTITY_VERSION}
REGULAR_GIT_MODES = {b"100644", b"100755"}
EXPECTED_LEDGER_PATHS = {
    ".sdlc/live/visual-evaluation-qwen38-max.json",
    ".sdlc/live/visual-evaluation-qwen37-plus.json",
    ".sdlc/live/visual-evaluation-qwen37-flash.json",
}
MODEL_LIMITS = {
    "qwen3.8-max": {"attempts": 180, "cost": 18_000_000},
    "qwen3.7-plus": {"attempts": 180, "cost": 10_000_000},
    "qwen3.7-flash": {"attempts": 180, "cost": 10_000_000},
}
HISTORICAL_MODEL_LIMITS = {
    "qwen3.8-max": {"attempts": 180, "cost": 18_000_000},
    "qwen3.7-plus": {"attempts": 180, "cost": 4_000_000},
    "qwen3.7-flash": {"attempts": 180, "cost": 400_000},
}
MODEL_TO_GOAL_SLOT = {
    "qwen3.8-max": "qwen3.8-max",
    "qwen3.7-plus": "qwen3.7-plus",
    "qwen3.7-flash": "qwen3.7-flash",
    "qwen3.7-flash-2026-07-15": "qwen3.7-flash",
}
GOAL_GUARD_LIMITS = {
    LEGACY_GOAL_GUARD_VERSION: {
        "tokens": 500_000, "models": HISTORICAL_MODEL_LIMITS,
    },
    PREVIOUS_V2_GOAL_GUARD_VERSION: {
        "tokens": 1_000_000, "models": HISTORICAL_MODEL_LIMITS,
    },
    PREVIOUS_GOAL_GUARD_VERSION: {
        "tokens": 1_500_000, "models": HISTORICAL_MODEL_LIMITS,
    },
    GOAL_GUARD_VERSION: {
        "tokens": 1_500_000, "models": MODEL_LIMITS,
    },
}
MAXIMUM_AUTHORIZATION_TOKENS = 500_000
PROFILE_FIELDS = (
    "profileVersion", "profileId", "provider", "model", "networkAllowed", "providerProtocol",
    "providerEndpoint", "apiKeyEnvironmentVariable", "pipelineVersion", "candidateContractVersion",
    "promptVersion", "elementPromptVersion", "hierarchyPromptVersion", "bindingPromptVersion",
    "visualHintPackVersion", "documentVisionCapabilityId", "documentVisionPromptVersion",
    "responseFormat", "thinkingEnabled", "toolsAllowed", "remoteMediaAllowed", "inputClassification",
    "supportedModes", "lowConfidenceThresholdBps", "maximumRepairRounds", "maximumTotalCalls",
    "stageTimeoutSeconds", "maximumOutputTokens", "maximumOutputBytes",
    "maximumEstimatedCostMicrosCny", "inputMicrosCnyPerMillionTokens",
    "outputMicrosCnyPerMillionTokens", "pricingEffectiveDate", "certification",
)
OPTIONAL_PROFILE_FIELDS = {
    "visualHintPackVersion", "documentVisionCapabilityId", "documentVisionPromptVersion",
}
FORBIDDEN = (
    '"providerRequestId"', '"candidateJson"', '"prompt"', '"missingEntities"',
    '"unexpectedEntities"', '"missingFields"', '"unexpectedFields"',
    '"missingRootFields"', '"unexpectedRootFields"', '"typeMismatches"',
    '"edgeMismatches"', '"shapeMismatches"', '"jsonPointer"', '"itemId"',
    '"args"', '"apiKey"', "DASHSCOPE_API_KEY", "data:image;base64", "Bearer ",
)
ID = re.compile(r"^[a-z][a-z0-9-]{0,127}$")
CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class VerificationError(Exception):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


def reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("duplicate JSON member")
        result[key] = value
    return result


def reject_float(value: str) -> Any:
    del value
    fail("floating-point JSON number is forbidden")


def read_json(path: Path, payload_free: bool = True) -> tuple[dict[str, Any], str]:
    try:
        raw = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        fail(f"cannot read {path.name}: {type(exc).__name__}")
    if payload_free and any(marker in raw for marker in FORBIDDEN):
        fail(f"forbidden payload marker in {path.name}")
    try:
        value = json.loads(raw, object_pairs_hook=reject_duplicate_pairs,
                           parse_float=reject_float, parse_constant=reject_float)
    except VerificationError:
        raise
    except (json.JSONDecodeError, ValueError) as exc:
        fail(f"invalid JSON in {path.name}: {type(exc).__name__}")
    if type(value) is not dict:
        fail(f"{path.name} root must be an object")
    return value, raw


def require_object(value: Any, name: str) -> dict[str, Any]:
    if type(value) is not dict:
        fail(f"{name} must be an object")
    return value


def require_list(value: Any, name: str) -> list[Any]:
    if type(value) is not list:
        fail(f"{name} must be an array")
    return value


def require_string(value: Any, name: str, pattern: re.Pattern[str] | None = None) -> str:
    if type(value) is not str or not value:
        fail(f"{name} must be a non-empty string")
    if pattern is not None and pattern.fullmatch(value) is None:
        fail(f"{name} format is invalid")
    return value


def require_int(value: Any, name: str, minimum: int = 0, maximum: int = 2**63 - 1) -> int:
    if type(value) is not int or value < minimum or value > maximum:
        fail(f"{name} must be an in-range integer")
    return value


def require_bool(value: Any, name: str) -> bool:
    if type(value) is not bool:
        fail(f"{name} must be boolean")
    return value


def require_nullable_int(value: Any, name: str) -> int | None:
    if value is None:
        return None
    return require_int(value, name)


def require_keys(value: dict[str, Any], expected: Iterable[str], name: str) -> None:
    expected_set = set(expected)
    actual = set(value)
    if actual != expected_set:
        fail(f"{name} fields differ: missing={sorted(expected_set - actual)}, unknown={sorted(actual - expected_set)}")


def unique_strings(value: Any, name: str, pattern: re.Pattern[str]) -> list[str]:
    items = require_list(value, name)
    result = [require_string(item, f"{name}[]", pattern) for item in items]
    if len(result) != len(set(result)):
        fail(f"{name} must be unique")
    return result


def corpus_cases(document: dict[str, Any], source_hash: str) -> tuple[list[str], dict[str, dict[str, str]]]:
    require_keys(document, ("corpusVersion", "scenes"), "corpus")
    if document["corpusVersion"] != CORPUS_VERSION:
        fail("corpus version mismatch")
    scenes = require_list(document["scenes"], "corpus.scenes")
    if len(scenes) != 12:
        fail("corpus must have 12 scenes")
    styles = ("WIDE_LIGHT", "PORTRAIT_DARK", "COMPACT_DENSE", "LOW_CONTRAST", "HOLDOUT_NOISY")
    case_ids: list[str] = []
    metadata: dict[str, dict[str, str]] = {}
    for scene_index, raw_scene in enumerate(scenes):
        scene = require_object(raw_scene, f"scene[{scene_index}]")
        scene_id = require_string(scene.get("sceneId"), "sceneId", ID)
        domain = require_string(scene.get("domainPack"), "domainPack")
        if domain not in ("GENERIC", "TRANSIT_BOARD"):
            fail("invalid domain pack")
        for variant in range(1, 6):
            case_id = f"{scene_id}-v{variant}"
            partition = "HOLDOUT" if variant == 5 or variant == 4 and scene_index < 3 else "DEV"
            case_ids.append(case_id)
            metadata[case_id] = {"partition": partition, "style": styles[variant - 1],
                                 "domainPack": domain, "sourceSha256": source_hash}
    if len(case_ids) != 60 or len(set(case_ids)) != 60:
        fail("corpus expansion is not exactly 60 unique cases")
    if sum(1 for item in metadata.values() if item["partition"] == "DEV") != 45:
        fail("corpus DEV/HOLDOUT split is invalid")
    return case_ids, metadata


def git_output(repository: Path, *arguments: str, input_bytes: bytes | None = None) -> bytes:
    environment = os.environ.copy()
    for key in ("DASHSCOPE_API_KEY", "DASHSCOPE_API_KEY_FILE", "RENDERWEAVE_RUN_LIVE_CANARY",
                "RENDERWEAVE_RUN_LIVE_CERTIFICATION", "RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION",
                "RENDERWEAVE_RUN_VISUAL_EVALUATION", "RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION"):
        environment.pop(key, None)
    environment["RENDERWEAVE_LIVE_AI_ENABLED"] = "false"
    environment["RENDERWEAVE_LIVE_UPLOAD_ENABLED"] = "false"
    try:
        completed = subprocess.run(("git", *arguments), cwd=repository, env=environment,
                                   input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   timeout=10, check=False)
    except (OSError, subprocess.SubprocessError) as exc:
        fail(f"git identity command failed: {type(exc).__name__}")
    if completed.returncode != 0:
        fail("git identity command returned nonzero")
    return completed.stdout


def git_paths(repository: Path, *arguments: str) -> list[str]:
    try:
        return [item.decode("utf-8").replace("\\", "/")
                for item in git_output(repository, *arguments).split(b"\0") if item]
    except UnicodeError:
        fail("git identity path is not UTF-8")


def git_index_entries(repository: Path) -> list[tuple[bytes, bytes, bytes, str]]:
    entries: list[tuple[bytes, bytes, bytes, str]] = []
    paths: set[bytes] = set()
    for record in git_output(repository, "ls-files", "--stage", "-z", "--cached").split(b"\0"):
        if not record:
            continue
        try:
            metadata, path_bytes = record.split(b"\t", 1)
            mode, object_id, stage = metadata.split(b" ")
            path = path_bytes.decode("utf-8")
        except (UnicodeError, ValueError):
            fail("git index entry is invalid")
        if stage != b"0" or mode not in REGULAR_GIT_MODES | {b"120000", b"160000"} \
                or re.fullmatch(rb"[0-9a-f]{40}|[0-9a-f]{64}", object_id) is None \
                or not path_bytes or b"\\" in path_bytes or path_bytes in paths:
            fail("git index entry is invalid")
        paths.add(path_bytes)
        entries.append((path_bytes, mode, object_id, path))
    return sorted(entries, key=lambda entry: entry[0])


def require_visible_index(repository: Path,
                          entries: list[tuple[bytes, bytes, bytes, str]]) -> None:
    expected = {entry[3] for entry in entries}
    for tagged in git_paths(repository, "ls-files", "-v", "-z", "--cached"):
        if not tagged.startswith("H ") or tagged[2:] not in expected:
            fail("git index contains hidden tracked state")
        expected.remove(tagged[2:])
    if expected:
        fail("git index contains hidden tracked state")


def canonical_git_blobs(repository: Path,
                        entries: list[tuple[bytes, bytes, bytes, str]]) -> list[bytes]:
    query = b"".join(entry[2] + b"\n" for entry in entries)
    output = git_output(repository, "cat-file", "--batch", input_bytes=query)
    position = 0
    blobs: list[bytes] = []
    for _path_bytes, _mode, object_id, _path in entries:
        newline = output.find(b"\n", position)
        if newline < 0:
            fail("git blob batch is truncated")
        header = output[position:newline].split(b" ")
        if len(header) != 3 or header[0] != object_id or header[1] != b"blob" \
                or re.fullmatch(rb"[0-9]+", header[2]) is None:
            fail("git blob batch header is invalid")
        size = int(header[2])
        start = newline + 1
        end = start + size
        if end >= len(output) or output[end:end + 1] != b"\n":
            fail("git blob batch body is invalid")
        blobs.append(output[start:end])
        position = end + 1
    if position != len(output):
        fail("git blob batch has trailing bytes")
    return blobs


def repository_identity(repository: Path, excluded_files: list[Path],
                        identity_version: str = IDENTITY_VERSION) -> tuple[str, set[str]]:
    repository = repository.resolve()
    if identity_version not in SUPPORTED_IDENTITY_VERSIONS:
        fail("repository evaluation identity version is unsupported")
    if identity_version == LEGACY_IDENTITY_VERSION:
        entries: list[tuple[bytes, bytes, bytes, str]] = []
        tracked = sorted(git_paths(repository, "ls-files", "-z", "--cached"))
    else:
        entries = git_index_entries(repository)
        require_visible_index(repository, entries)
        tracked = [entry[3] for entry in entries]
    if git_paths(repository, "status", "--porcelain=v1", "-z", "--untracked-files=no"):
        fail("repository contains tracked changes")
    if git_paths(repository, "ls-files", "-z", "--others", "--exclude-standard"):
        fail("repository contains untracked files")
    excluded: set[str] = set()
    for path in excluded_files:
        absolute = path.resolve()
        try:
            relative = absolute.relative_to(repository).as_posix()
        except ValueError:
            fail("excluded authorization is outside repository")
        if relative in excluded:
            fail("excluded authorization paths are duplicated")
        excluded.add(relative)
        if relative not in tracked or not absolute.is_file() or absolute.is_symlink():
            fail("excluded authorization is not a tracked regular file")
        if identity_version == IDENTITY_VERSION:
            entry = next(item for item in entries if item[3] == relative)
            if entry[1] not in REGULAR_GIT_MODES:
                fail("excluded authorization is not a tracked regular file")
    if excluded != EXPECTED_LEDGER_PATHS:
        fail("visual evaluation must exclude exactly the three fixed tracked ledgers")
    digest = hashlib.sha256()
    digest.update(f"{identity_version}\n".encode("utf-8"))
    if identity_version == LEGACY_IDENTITY_VERSION:
        inputs = [path for path in tracked if path not in excluded]
        if not inputs:
            fail("repository evaluation identity is empty")
        for relative in inputs:
            path = repository / relative
            if not path.is_file() or path.is_symlink():
                fail("tracked evaluation input is unavailable")
            path_bytes = relative.encode("utf-8")
            content = path.read_bytes()
            digest.update(struct.pack(">i", len(path_bytes)))
            digest.update(path_bytes)
            digest.update(struct.pack(">q", len(content)))
            digest.update(content)
    else:
        canonical_inputs = [entry for entry in entries if entry[3] not in excluded]
        if not canonical_inputs:
            fail("repository evaluation identity is empty")
        if any(entry[1] not in REGULAR_GIT_MODES for entry in canonical_inputs):
            fail("tracked evaluation input is not a regular file")
        if any(not (repository / entry[3]).is_file() or (repository / entry[3]).is_symlink()
               for entry in canonical_inputs):
            fail("tracked evaluation input is unavailable")
        blobs = canonical_git_blobs(repository, canonical_inputs)
        for entry, content in zip(canonical_inputs, blobs, strict=True):
            path_bytes, mode, _object_id, _path = entry
            digest.update(struct.pack(">i", len(path_bytes)))
            digest.update(path_bytes)
            digest.update(struct.pack(">i", len(mode)))
            digest.update(mode)
            digest.update(struct.pack(">q", len(content)))
            digest.update(content)
    return f"{identity_version}:{digest.hexdigest()}", set(tracked)


def require_tracked_regular(repository: Path, path: Path, tracked: set[str], name: str) -> str:
    absolute = path.resolve()
    try:
        relative = absolute.relative_to(repository.resolve()).as_posix()
    except ValueError:
        fail(f"{name} is outside repository")
    if relative not in tracked or not absolute.is_file() or absolute.is_symlink():
        fail(f"{name} is not a tracked regular file")
    return relative


def validate_profile(path: Path, authorization: dict[str, Any]) -> None:
    # The immutable profile legitimately names the API-key environment variable; it never
    # contains the key value and is repository identity input rather than runtime evidence.
    profile, _ = read_json(path, payload_free=False)
    pipeline = profile.get("pipelineVersion")
    if pipeline in (
            "renderweave-inference-pipeline/4.2",
            "renderweave-inference-pipeline/4.10",
            "renderweave-inference-pipeline/4.11",
            "renderweave-inference-pipeline/4.12",
            "renderweave-inference-pipeline/4.13",
            "renderweave-inference-pipeline/4.14",
            "renderweave-inference-pipeline/4.15",
            "renderweave-inference-pipeline/4.16",
            "renderweave-inference-pipeline/4.17",
            "renderweave-inference-pipeline/4.18",
            "renderweave-inference-pipeline/4.19",
            "renderweave-inference-pipeline/4.20",
            "renderweave-inference-pipeline/4.21",
            "renderweave-inference-pipeline/4.22",
    ):
        required_optional = OPTIONAL_PROFILE_FIELDS
    elif pipeline in (
            "renderweave-inference-pipeline/4.1",
            "renderweave-inference-pipeline/4.3",
            "renderweave-inference-pipeline/4.4",
            "renderweave-inference-pipeline/4.5",
            "renderweave-inference-pipeline/4.6",
            "renderweave-inference-pipeline/4.7",
            "renderweave-inference-pipeline/4.8",
            "renderweave-inference-pipeline/4.9",
    ):
        required_optional = {"visualHintPackVersion"}
    else:
        required_optional = set()
    expected_fields = tuple(
        field for field in PROFILE_FIELDS
        if field not in OPTIONAL_PROFILE_FIELDS or field in required_optional
    )
    require_keys(profile, expected_fields, "profile")
    for field in required_optional:
        require_string(profile[field], f"profile.{field}")
    if profile.get("profileId") != authorization["profileId"] \
            or profile.get("model") != authorization["model"]:
        fail("profile identity differs from authorization")
    # InferenceProfile is a Java record; its immutable snapshot follows record-component order.
    canonical_profile = {field: profile[field] for field in expected_fields}
    canonical = json.dumps(canonical_profile, ensure_ascii=False, separators=(",", ":"))
    profile_hash = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    if profile_hash != authorization["profileSnapshotSha256"]:
        fail("canonical profile snapshot hash differs from authorization")


def validate_authorization(value: dict[str, Any], corpus_hash: str,
                           known_cases: set[str]) -> dict[str, Any]:
    fields = (
        "authorizationVersion", "authorizationId", "status", "phase", "inputClassification",
        "corpusVersion", "corpusSourceSha256", "evaluationIdentity", "profileId",
        "profileSnapshotSha256", "model", "caseIds", "maximumProviderAttempts",
        "maximumTotalTokens", "maximumCostMicrosCny", "maximumCasesPerBatch", "approvedBy",
        "approvedAt", "expiresAt", "approvalScope",
    )
    require_keys(value, fields, "authorization")
    if value["authorizationVersion"] != AUTH_VERSION or value["inputClassification"] != "REPOSITORY_SYNTHETIC_ONLY":
        fail("authorization envelope mismatch")
    authorization_id = require_string(value["authorizationId"], "authorizationId", ID)
    if value["status"] not in ("OPEN", "CLOSED"):
        fail("evidence requires an OPEN or CLOSED authorization")
    if value["phase"] not in ("BASELINE", "ABLATION", "CANARY", "FINAL"):
        fail("authorization phase is invalid")
    if value["corpusVersion"] != CORPUS_VERSION or value["corpusSourceSha256"] != corpus_hash:
        fail("authorization corpus mismatch")
    identity = require_string(value["evaluationIdentity"], "evaluationIdentity")
    if re.fullmatch(r"renderweave-visual-evaluation-tree-sha256/[12]:[0-9a-f]{64}", identity) is None:
        fail("authorization evaluation identity is invalid")
    require_string(value["profileId"], "profileId", ID)
    require_string(value["profileSnapshotSha256"], "profileSnapshotSha256", SHA256)
    model = require_string(value["model"], "model")
    if model not in MODEL_TO_GOAL_SLOT:
        fail("authorization model is invalid")
    goal_slot = MODEL_TO_GOAL_SLOT[model]
    cases = unique_strings(value["caseIds"], "caseIds", ID)
    if not cases or not set(cases).issubset(known_cases):
        fail("authorization cases are invalid")
    attempts = require_int(value["maximumProviderAttempts"], "maximumProviderAttempts", 1,
                           min(180, len(cases) * 8))
    tokens = require_int(value["maximumTotalTokens"], "maximumTotalTokens", 1,
                         MAXIMUM_AUTHORIZATION_TOKENS)
    cost = require_int(value["maximumCostMicrosCny"], "maximumCostMicrosCny", 1,
                       MODEL_LIMITS[goal_slot]["cost"])
    require_int(value["maximumCasesPerBatch"], "maximumCasesPerBatch", 1, 5)
    for field in ("approvedBy", "approvedAt", "expiresAt", "approvalScope"):
        require_string(value[field], field)
    return {"authorizationId": authorization_id, "profileId": value["profileId"], "model": model,
            "caseIds": cases, "maximumProviderAttempts": attempts, "maximumTotalTokens": tokens,
            "maximumCostMicrosCny": cost}


def stage_counts(value: Any, name: str) -> dict[str, int]:
    item = require_object(value, name)
    require_keys(item, ("expected", "actual", "matched"), name)
    expected = require_int(item["expected"], f"{name}.expected")
    actual = require_int(item["actual"], f"{name}.actual")
    matched = require_int(item["matched"], f"{name}.matched", 0, min(expected, actual))
    return {"expected": expected, "actual": actual, "matched": matched}


def grounding(value: Any, name: str) -> dict[str, int]:
    item = require_object(value, name)
    require_keys(item, ("expected", "semanticallyMatched", "matchedAtIou50", "matchedIouBpsSum"), name)
    expected = require_int(item["expected"], f"{name}.expected")
    semantic = require_int(item["semanticallyMatched"], f"{name}.semanticallyMatched", 0, expected)
    matched = require_int(item["matchedAtIou50"], f"{name}.matchedAtIou50", 0, semantic)
    iou = require_int(item["matchedIouBpsSum"], f"{name}.matchedIouBpsSum", 0, semantic * 10_000)
    return {"expected": expected, "semanticallyMatched": semantic,
            "matchedAtIou50": matched, "matchedIouBpsSum": iou}


def survival(value: Any, name: str) -> dict[str, int]:
    item = require_object(value, name)
    require_keys(item, ("expectedSlots", "observedSlots", "correctlyBoundSlots", "candidateSlots"), name)
    expected = require_int(item["expectedSlots"], f"{name}.expectedSlots")
    observed = require_int(item["observedSlots"], f"{name}.observedSlots", 0, expected)
    bound = require_int(item["correctlyBoundSlots"], f"{name}.correctlyBoundSlots", 0, observed)
    candidate = require_int(item["candidateSlots"], f"{name}.candidateSlots", 0, bound)
    return {"expectedSlots": expected, "observedSlots": observed,
            "correctlyBoundSlots": bound, "candidateSlots": candidate}


def calibration(value: Any, name: str) -> list[dict[str, int]]:
    items = require_list(value, name)
    if len(items) != 10:
        fail(f"{name} must contain 10 bins")
    result: list[dict[str, int]] = []
    for index, raw in enumerate(items):
        item = require_object(raw, f"{name}[{index}]")
        require_keys(item, ("binIndex", "count", "correct", "confidenceBpsSum",
                            "squaredErrorBpsSum"), f"{name}[{index}]")
        if require_int(item["binIndex"], "binIndex", 0, 9) != index:
            fail("calibration bin order is invalid")
        count = require_int(item["count"], "count")
        correct = require_int(item["correct"], "correct", 0, count)
        confidence = require_int(item["confidenceBpsSum"], "confidenceBpsSum", 0, count * 10_000)
        squared = require_int(item["squaredErrorBpsSum"], "squaredErrorBpsSum", 0, count * 10_000)
        result.append({"binIndex": index, "count": count, "correct": correct,
                       "confidenceBpsSum": confidence, "squaredErrorBpsSum": squared})
    return result


def final_metrics(value: Any, name: str) -> dict[str, Any]:
    item = require_object(value, name)
    fields = ("outcomeCode", "passed", "bundleContractBps", "entities", "fields",
              "relationships", "supportedTypeExpected", "supportedTypeMatched", "evidenceExpected",
              "evidencePresent", "dagValidityBps", "criticalHallucinations", "blockers")
    require_keys(item, fields, name)
    result: dict[str, Any] = {
        "outcomeCode": require_string(item["outcomeCode"], f"{name}.outcomeCode"),
        "passed": require_bool(item["passed"], f"{name}.passed"),
        "bundleContractBps": require_int(item["bundleContractBps"], "bundleContractBps", 0, 10_000),
        "entities": stage_counts(item["entities"], f"{name}.entities"),
        "fields": stage_counts(item["fields"], f"{name}.fields"),
        "relationships": stage_counts(item["relationships"], f"{name}.relationships"),
    }
    expected_type = require_int(item["supportedTypeExpected"], "supportedTypeExpected")
    result["supportedTypeExpected"] = expected_type
    result["supportedTypeMatched"] = require_int(item["supportedTypeMatched"],
                                                  "supportedTypeMatched", 0, expected_type)
    expected_evidence = require_int(item["evidenceExpected"], "evidenceExpected")
    result["evidenceExpected"] = expected_evidence
    result["evidencePresent"] = require_int(item["evidencePresent"], "evidencePresent", 0,
                                            expected_evidence)
    result["dagValidityBps"] = require_int(item["dagValidityBps"], "dagValidityBps", 0, 10_000)
    result["criticalHallucinations"] = require_int(item["criticalHallucinations"],
                                                    "criticalHallucinations")
    result["blockers"] = require_int(item["blockers"], "blockers")
    return result


def evaluation(value: Any, metadata: dict[str, dict[str, str]], authorized: set[str]) -> dict[str, Any]:
    item = require_object(value, "evaluation")
    fields = ("caseId", "partition", "domainPack", "style", "outcomeCode", "providerCalls",
              "repairRounds", "slots", "groups", "grounding", "entities", "relationships",
              "bindings", "survival", "treeEditDistance", "treeEditDenominator",
              "calibrationBins", "finalCandidate")
    require_keys(item, fields, "evaluation")
    case_id = require_string(item["caseId"], "evaluation.caseId", ID)
    if case_id not in authorized:
        fail("evaluation case is not authorized")
    gold = metadata[case_id]
    for field in ("partition", "domainPack", "style"):
        if item[field] != gold[field]:
            fail(f"evaluation {field} differs from corpus")
    result: dict[str, Any] = {
        "caseId": case_id, "partition": item["partition"], "domainPack": item["domainPack"],
        "style": item["style"], "outcomeCode": require_string(item["outcomeCode"], "outcomeCode", CODE),
        "providerCalls": require_int(item["providerCalls"], "providerCalls", 0, 8),
        "repairRounds": require_int(item["repairRounds"], "repairRounds", 0, 2),
        "slots": stage_counts(item["slots"], "slots"),
        "groups": stage_counts(item["groups"], "groups"),
        "grounding": grounding(item["grounding"], "grounding"),
        "entities": stage_counts(item["entities"], "entities"),
        "relationships": stage_counts(item["relationships"], "relationships"),
        "bindings": stage_counts(item["bindings"], "bindings"),
        "survival": survival(item["survival"], "survival"),
    }
    denominator = require_int(item["treeEditDenominator"], "treeEditDenominator", 1)
    result["treeEditDistance"] = require_int(item["treeEditDistance"], "treeEditDistance", 0,
                                             denominator)
    result["treeEditDenominator"] = denominator
    result["calibrationBins"] = calibration(item["calibrationBins"], "calibrationBins")
    result["finalCandidate"] = final_metrics(item["finalCandidate"], "finalCandidate")
    return result


def validate_goal(value: dict[str, Any], guard_limits: dict[str, Any]) \
        -> tuple[list[dict[str, Any]], dict[str, dict[str, int]]]:
    require_keys(value, ("stateVersion", "goalId", "reservations", "createdAt", "updatedAt"), "goal")
    if value["stateVersion"] != GOAL_VERSION or value["goalId"] != GOAL_ID:
        fail("goal budget identity mismatch")
    reservations = require_list(value["reservations"], "goal.reservations")
    parsed: list[dict[str, Any]] = []
    ids: set[str] = set()
    calls: set[tuple[str, int]] = set()
    totals = {model: {"attempts": 0, "tokens": 0, "cost": 0}
              for model in guard_limits["models"]}
    fields = ("reservationId", "authorizationId", "profileId", "model", "runId",
              "attemptOrdinal", "stage", "reservedTokens", "reservedCostMicrosCny",
              "actualInputTokens", "actualOutputTokens", "actualCostMicrosCny", "state",
              "createdAt", "updatedAt")
    for index, raw in enumerate(reservations):
        item = require_object(raw, f"reservation[{index}]")
        require_keys(item, fields, f"reservation[{index}]")
        reservation_id = require_string(item["reservationId"], "reservationId", UUID)
        if reservation_id in ids:
            fail("duplicate reservation id")
        ids.add(reservation_id)
        run_id = require_string(item["runId"], "runId", UUID)
        ordinal = require_int(item["attemptOrdinal"], "attemptOrdinal", 0, 7)
        if (run_id, ordinal) in calls:
            fail("duplicate run attempt")
        calls.add((run_id, ordinal))
        model = require_string(item["model"], "model")
        if model not in MODEL_TO_GOAL_SLOT:
            fail("reservation model is invalid")
        goal_slot = MODEL_TO_GOAL_SLOT[model]
        state = item["state"]
        if state not in ("RESERVED", "SETTLED", "BREACHED"):
            fail("reservation state is invalid")
        if state == "BREACHED":
            fail("a breached Goal reservation permanently invalidates evaluation evidence")
        reserved_tokens = require_int(item["reservedTokens"], "reservedTokens", 1)
        reserved_cost = require_int(item["reservedCostMicrosCny"], "reservedCostMicrosCny", 1)
        actual_input = require_nullable_int(item["actualInputTokens"], "actualInputTokens")
        actual_output = require_nullable_int(item["actualOutputTokens"], "actualOutputTokens")
        actual_cost = require_nullable_int(item["actualCostMicrosCny"], "actualCostMicrosCny")
        if state == "RESERVED" and (actual_input is not None or actual_output is not None or actual_cost is not None):
            fail("reserved attempt carries actual usage")
        if state != "RESERVED" and (actual_input is None or actual_output is None or actual_cost is None):
            fail("final attempt lacks actual usage")
        if state == "SETTLED" and (actual_input + actual_output > reserved_tokens or actual_cost > reserved_cost):
            fail("settled attempt exceeds reservation")
        exposed_tokens = reserved_tokens if actual_input is None else actual_input + actual_output
        exposed_cost = reserved_cost if actual_cost is None else actual_cost
        if state == "BREACHED":
            exposed_tokens = max(reserved_tokens, exposed_tokens)
            exposed_cost = max(reserved_cost, exposed_cost)
        totals[goal_slot]["attempts"] += 1
        totals[goal_slot]["tokens"] += exposed_tokens
        totals[goal_slot]["cost"] += exposed_cost
        parsed.append({**item, "reservationId": reservation_id, "runId": run_id,
                       "attemptOrdinal": ordinal, "model": model, "state": state,
                       "actualInputTokens": actual_input, "actualOutputTokens": actual_output,
                       "actualCostMicrosCny": actual_cost})
    for model, total in totals.items():
        limit = guard_limits["models"][model]
        if total["attempts"] > limit["attempts"] \
                or total["tokens"] > guard_limits["tokens"] \
                or total["cost"] > limit["cost"]:
            fail("cross-ledger model budget exceeded")
    return parsed, totals


def validate_goal_guard(value: dict[str, Any]) -> dict[str, Any]:
    require_keys(value, ("guardVersion", "goalId", "maximumTokensPerModel",
                         "maximumAttemptsPerModel", "maximumCostMicrosCnyByModel"), "goal guard")
    guard_version = require_string(value["guardVersion"], "guardVersion")
    if guard_version not in GOAL_GUARD_LIMITS or value["goalId"] != GOAL_ID:
        fail("goal guard identity or caps differ")
    limits = GOAL_GUARD_LIMITS[guard_version]
    if require_int(value["maximumTokensPerModel"], "maximumTokensPerModel") \
            != limits["tokens"] \
            or require_int(value["maximumAttemptsPerModel"], "maximumAttemptsPerModel") != 180:
        fail("goal guard identity or caps differ")
    costs = require_object(value["maximumCostMicrosCnyByModel"], "maximumCostMicrosCnyByModel")
    if costs != {model: model_limits["cost"]
                 for model, model_limits in limits["models"].items()}:
        fail("goal guard model cost caps differ")
    return limits


def validate_journal_guard(value: dict[str, Any], authorization: dict[str, Any]) -> None:
    fields = ("guardVersion", "authorizationVersion", "authorizationId", "phase",
              "inputClassification", "corpusVersion", "corpusSourceSha256", "evaluationIdentity",
              "profileId", "profileSnapshotSha256", "model", "caseIds", "maximumProviderAttempts",
              "maximumTotalTokens", "maximumCostMicrosCny", "maximumCasesPerBatch")
    require_keys(value, fields, "journal guard")
    expected = {field: authorization[field] for field in fields if field != "guardVersion"}
    expected["guardVersion"] = JOURNAL_GUARD_VERSION
    if value != expected:
        fail("journal guard differs from immutable authorization scope")


def validate_attempt(value: Any) -> dict[str, Any]:
    item = require_object(value, "attempt")
    fields = ("reservationId", "attemptOrdinal", "stage", "outcomeCode", "model",
              "inputTokens", "outputTokens", "costMicrosCny", "latencyMillis", "problemCodeCounts")
    require_keys(item, fields, "attempt")
    counts = require_object(item["problemCodeCounts"], "problemCodeCounts")
    for code, count in counts.items():
        require_string(code, "problem code", CODE)
        require_int(count, "problem count", 1, 100_000)
    return {
        "reservationId": require_string(item["reservationId"], "reservationId", UUID),
        "attemptOrdinal": require_int(item["attemptOrdinal"], "attemptOrdinal", 0, 7),
        "stage": require_string(item["stage"], "stage", CODE),
        "outcomeCode": require_string(item["outcomeCode"], "outcomeCode", CODE),
        "model": require_string(item["model"], "model"),
        "inputTokens": require_nullable_int(item["inputTokens"], "inputTokens"),
        "outputTokens": require_nullable_int(item["outputTokens"], "outputTokens"),
        "costMicrosCny": require_nullable_int(item["costMicrosCny"], "costMicrosCny"),
        "latencyMillis": require_int(item["latencyMillis"], "latencyMillis", 0, 3_600_000),
        "problemCodeCounts": counts,
    }


def validate_journal(value: dict[str, Any], auth: dict[str, Any], metadata: dict[str, dict[str, str]],
                     reservations: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], int]:
    require_keys(value, ("journalVersion", "authorizationId", "executions", "createdAt", "updatedAt"),
                 "journal")
    if value["journalVersion"] != JOURNAL_VERSION or value["authorizationId"] != auth["authorizationId"]:
        fail("journal identity mismatch")
    by_reservation = {item["reservationId"]: item for item in reservations}
    auth_reservations = [item for item in reservations if item["authorizationId"] == auth["authorizationId"]]
    executions = require_list(value["executions"], "journal.executions")
    assignment_ids: set[str] = set()
    execution_ids: set[str] = set()
    run_ids: set[str] = set()
    linked_reservations: set[str] = set()
    completed: list[dict[str, Any]] = []
    abandoned = 0
    provider_latency_millis = 0
    fields = ("assignmentKey", "executionId", "caseId", "profileId", "model", "runId",
              "status", "evaluation", "attempts", "startedAt", "updatedAt", "completedAt")
    for index, raw in enumerate(executions):
        item = require_object(raw, f"execution[{index}]")
        require_keys(item, fields, f"execution[{index}]")
        case_id = require_string(item["caseId"], "caseId", ID)
        if case_id not in auth["caseIds"]:
            fail("journal case is not authorized")
        expected_assignment = f"{auth['profileId']}|{case_id}"
        if item["assignmentKey"] != expected_assignment or expected_assignment in assignment_ids:
            fail("journal assignment identity is invalid")
        assignment_ids.add(expected_assignment)
        execution_id = require_string(item["executionId"], "executionId", UUID)
        if execution_id in execution_ids:
            fail("duplicate execution id")
        execution_ids.add(execution_id)
        if item["profileId"] != auth["profileId"] or item["model"] != auth["model"]:
            fail("journal profile/model mismatch")
        run_id = item["runId"]
        if run_id is not None:
            run_id = require_string(run_id, "runId", UUID)
            if run_id in run_ids:
                fail("duplicate journal run id")
            run_ids.add(run_id)
        status = item["status"]
        attempts = [validate_attempt(attempt) for attempt in require_list(item["attempts"], "attempts")]
        if status == "IN_PROGRESS":
            if item["evaluation"] is not None or attempts or item["completedAt"] is not None:
                fail("in-progress execution contains terminal evidence")
            continue
        if status == "ABANDONED_AFTER_RESERVATION":
            if run_id is None or item["evaluation"] is not None or attempts or item["completedAt"] is None:
                fail("abandoned execution shape is invalid")
            abandoned += 1
            related = [reservation for reservation in auth_reservations if reservation["runId"] == run_id]
            if not related:
                fail("abandoned execution has no irreversible reservation")
            linked_reservations.update(item["reservationId"] for item in related)
            continue
        if status != "COMPLETED" or run_id is None or item["evaluation"] is None \
                or item["completedAt"] is None:
            fail("execution terminal state is invalid")
        result = evaluation(item["evaluation"], metadata, set(auth["caseIds"]))
        if result["caseId"] != case_id or result["providerCalls"] != len(attempts):
            fail("evaluation call/case binding mismatch")
        ordinals: set[int] = set()
        for attempt in attempts:
            provider_latency_millis += attempt["latencyMillis"]
            reservation_id = attempt["reservationId"]
            if reservation_id in linked_reservations or attempt["attemptOrdinal"] in ordinals:
                fail("duplicate journal attempt")
            linked_reservations.add(reservation_id)
            ordinals.add(attempt["attemptOrdinal"])
            reservation = by_reservation.get(reservation_id)
            if reservation is None or reservation["authorizationId"] != auth["authorizationId"] \
                    or reservation["profileId"] != auth["profileId"] or reservation["model"] != auth["model"] \
                    or reservation["runId"] != run_id or reservation["attemptOrdinal"] != attempt["attemptOrdinal"] \
                    or reservation["stage"] != attempt["stage"] or attempt["model"] != auth["model"]:
                fail("journal attempt does not match Goal reservation")
            actual = (reservation["actualInputTokens"], reservation["actualOutputTokens"],
                      reservation["actualCostMicrosCny"])
            observed = (attempt["inputTokens"], attempt["outputTokens"], attempt["costMicrosCny"])
            if reservation["state"] == "SETTLED" and observed != actual:
                fail("journal usage differs from settled reservation")
            if reservation["state"] == "RESERVED" and any(value is not None for value in observed):
                fail("journal usage exists for unsettled reservation")
            if reservation["state"] == "BREACHED":
                fail("breached reservation cannot be accepted as evaluation evidence")
        completed.append(result)
    expected_links = {item["reservationId"] for item in auth_reservations}
    if linked_reservations != expected_links:
        fail("authorization reservation set is not fully linked to journal executions")
    if len(auth_reservations) > auth["maximumProviderAttempts"]:
        fail("authorization attempt cap exceeded")
    exposed_tokens = sum((item["reservedTokens"] if item["actualInputTokens"] is None
                          else item["actualInputTokens"] + item["actualOutputTokens"])
                         for item in auth_reservations)
    exposed_cost = sum(item["reservedCostMicrosCny"] if item["actualCostMicrosCny"] is None
                       else item["actualCostMicrosCny"] for item in auth_reservations)
    if exposed_tokens > auth["maximumTotalTokens"] or exposed_cost > auth["maximumCostMicrosCny"]:
        fail("authorization budget exceeded")
    return completed, abandoned, provider_latency_millis


def add_counts(target: dict[str, int], source: dict[str, int]) -> None:
    for key in target:
        target[key] += source[key]


def empty_counts() -> dict[str, int]:
    return {"expected": 0, "actual": 0, "matched": 0}


def aggregate(results: list[dict[str, Any]]) -> dict[str, Any]:
    slots, groups, entities, relationships, bindings = (empty_counts() for _ in range(5))
    grounding_sum = {"expected": 0, "semanticallyMatched": 0, "matchedAtIou50": 0,
                     "matchedIouBpsSum": 0}
    survival_sum = {"expectedSlots": 0, "observedSlots": 0, "correctlyBoundSlots": 0,
                    "candidateSlots": 0}
    bins = [{"binIndex": index, "count": 0, "correct": 0, "confidenceBpsSum": 0,
             "squaredErrorBpsSum": 0} for index in range(10)]
    final_entities, final_fields, final_relationships = empty_counts(), empty_counts(), empty_counts()
    final = {"caseCount": len(results), "passedCases": 0, "bundleContractBpsSum": 0,
             "entities": final_entities, "fields": final_fields, "relationships": final_relationships,
             "supportedTypeExpected": 0, "supportedTypeMatched": 0, "evidenceExpected": 0,
             "evidencePresent": 0, "dagValidityBpsSum": 0, "criticalHallucinations": 0,
             "blockers": 0}
    result: dict[str, Any] = {"caseCount": len(results), "passedCandidateCases": 0,
                              "providerCalls": 0, "repairAttemptedCases": 0,
                              "repairSuccessfulCases": 0, "slots": slots, "groups": groups,
                              "grounding": grounding_sum, "entities": entities,
                              "relationships": relationships, "bindings": bindings,
                              "survival": survival_sum, "treeEditDistance": 0,
                              "treeEditDenominator": 0, "calibrationBins": bins,
                              "finalCandidate": final}
    for item in results:
        for target, key in ((slots, "slots"), (groups, "groups"), (entities, "entities"),
                            (relationships, "relationships"), (bindings, "bindings")):
            add_counts(target, item[key])
        for key in grounding_sum:
            grounding_sum[key] += item["grounding"][key]
        for key in survival_sum:
            survival_sum[key] += item["survival"][key]
        for index in range(10):
            for key in ("count", "correct", "confidenceBpsSum", "squaredErrorBpsSum"):
                bins[index][key] += item["calibrationBins"][index][key]
        result["providerCalls"] += item["providerCalls"]
        result["treeEditDistance"] += item["treeEditDistance"]
        result["treeEditDenominator"] += item["treeEditDenominator"]
        if item["repairRounds"] > 0:
            result["repairAttemptedCases"] += 1
            if item["finalCandidate"]["passed"]:
                result["repairSuccessfulCases"] += 1
        candidate = item["finalCandidate"]
        if candidate["passed"]:
            result["passedCandidateCases"] += 1
            final["passedCases"] += 1
        final["bundleContractBpsSum"] += candidate["bundleContractBps"]
        final["dagValidityBpsSum"] += candidate["dagValidityBps"]
        for target, key in ((final_entities, "entities"), (final_fields, "fields"),
                            (final_relationships, "relationships")):
            add_counts(target, candidate[key])
        for key in ("supportedTypeExpected", "supportedTypeMatched", "evidenceExpected",
                    "evidencePresent", "criticalHallucinations", "blockers"):
            final[key] += candidate[key]
    return result


def expected_report(case_ids: list[str], metadata: dict[str, dict[str, str]],
                    corpus_hash: str, results: list[dict[str, Any]]) -> dict[str, Any]:
    observed = {item["caseId"] for item in results}
    def slices(values: Iterable[str], field: str) -> dict[str, Any]:
        return {value: aggregate([item for item in results if item[field] == value]) for value in values}
    return {
        "reportVersion": REPORT_VERSION, "corpusVersion": CORPUS_VERSION,
        "corpusSourceSha256": corpus_hash, "expectedCaseCount": 60,
        "observedCaseCount": len(results), "complete": len(results) == 60,
        "missingCaseIds": [case_id for case_id in case_ids if case_id not in observed],
        "global": aggregate(results),
        "partitions": slices(("DEV", "HOLDOUT"), "partition"),
        "styles": slices(("WIDE_LIGHT", "PORTRAIT_DARK", "COMPACT_DENSE", "LOW_CONTRAST",
                           "HOLDOUT_NOISY"), "style"),
        "domainPacks": slices(("GENERIC", "TRANSIT_BOARD"), "domainPack"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--excluded-authorization", type=Path, action="append", required=True)
    parser.add_argument("--authorization", type=Path, required=True)
    parser.add_argument("--journal", type=Path, required=True)
    parser.add_argument("--journal-guard", type=Path, required=True)
    parser.add_argument("--goal-budget", type=Path, required=True)
    parser.add_argument("--goal-guard", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()

    corpus, corpus_raw = read_json(args.corpus, payload_free=False)
    del corpus_raw
    corpus_hash = hashlib.sha256(args.corpus.read_bytes()).hexdigest()
    all_case_ids, metadata = corpus_cases(corpus, corpus_hash)
    authorization_raw, _ = read_json(args.authorization)
    authorization = validate_authorization(authorization_raw, corpus_hash, set(all_case_ids))
    identity_version = authorization_raw["evaluationIdentity"].split(":", 1)[0]
    actual_identity, tracked = repository_identity(
        args.repository_root, args.excluded_authorization, identity_version)
    require_tracked_regular(args.repository_root, args.corpus, tracked, "corpus")
    require_tracked_regular(args.repository_root, args.profile, tracked, "profile")
    authorization_relative = require_tracked_regular(
        args.repository_root, args.authorization, tracked, "authorization")
    if authorization_relative not in EXPECTED_LEDGER_PATHS:
        fail("selected authorization is not one of the fixed ledgers")
    if authorization_raw["evaluationIdentity"] != actual_identity:
        fail("authorization evaluation identity differs from clean repository")
    validate_profile(args.profile, authorization_raw)
    journal_guard_raw, _ = read_json(args.journal_guard)
    validate_journal_guard(journal_guard_raw, authorization_raw)
    goal_guard_raw, _ = read_json(args.goal_guard)
    guard_limits = validate_goal_guard(goal_guard_raw)
    goal_raw, _ = read_json(args.goal_budget)
    reservations, model_totals = validate_goal(goal_raw, guard_limits)
    journal_raw, _ = read_json(args.journal)
    results, abandoned, provider_latency_millis = validate_journal(
        journal_raw, authorization, metadata, reservations)
    report_raw, _ = read_json(args.report)
    expected = expected_report(all_case_ids, metadata, corpus_hash, results)
    if report_raw != expected:
        fail("report does not equal independently recomputed stage aggregates")
    if args.require_complete and (not expected["complete"] or abandoned != 0
                                  or set(authorization["caseIds"]) != set(all_case_ids)):
        fail("complete 60-case evidence was required")
    auth_reservations = [item for item in reservations
                         if item["authorizationId"] == authorization["authorizationId"]]
    summary = {
        "verificationVersion": VERIFIER_VERSION,
        "result": "PASS",
        "authorizationId": authorization["authorizationId"],
        "model": authorization["model"],
        "completedCases": len(results),
        "abandonedCases": abandoned,
        "providerAttempts": len(auth_reservations),
        "actualInputTokens": sum(item["actualInputTokens"] or 0 for item in auth_reservations),
        "actualOutputTokens": sum(item["actualOutputTokens"] or 0 for item in auth_reservations),
        "providerLatencyMillis": provider_latency_millis,
        "exposedModelTokens": model_totals[MODEL_TO_GOAL_SLOT[authorization["model"]]]["tokens"],
        "exposedModelCostMicrosCny": model_totals[MODEL_TO_GOAL_SLOT[authorization["model"]]]["cost"],
        "reportComplete": expected["complete"],
        "payloadScan": "PASS",
    }
    print(json.dumps(summary, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        print(json.dumps({"verificationVersion": VERIFIER_VERSION, "result": "FAIL",
                          "reason": str(error)}, separators=(",", ":")), file=sys.stderr)
        raise SystemExit(2)
