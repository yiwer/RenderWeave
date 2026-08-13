#!/usr/bin/env python3
"""Independently reconstruct and verify an N7 CLOSED negative-probe record."""

from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
import pathlib
import re
import sys
from typing import Any

import run_n7_closed_negative_probe as probe
import verify_layered_evaluation as layered


VERIFIER_VERSION = "renderweave-n7-closed-negative-probe-verifier/1.0"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
MARKER = re.compile(
    r"^renderweave-n7-closed-probe-"
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
RECORD_FIELDS = {
    "probeVersion", "result", "authorizationId", "failureCode",
    "failureCodeOccurrences", "probeInvocationCount", "probeMarker",
    "probeProcessId", "platform", "commandTool", "commandIdentity",
    "probeToolSha256", "startedAt", "completedAt", "exitCode",
    "stdoutSha256", "stderrSha256", "credentialsRemovedFromChild",
    "beforeRuntime", "afterRuntime", "goalAndEvidenceHashesUnchanged",
    "processesBefore", "processesRemaining", "providerAttemptsCreated",
    "providerReservationsCreated", "externalProviderCostMicrosCnyCreated",
    "payloadScan",
}


class VerificationError(Exception):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def canonical_identity(version: str, value: dict[str, Any]) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")
    return f"{version}:{hashlib.sha256(encoded).hexdigest()}"


def normalized_source_sha256(value: bytes) -> str:
    normalized = value.replace(b"\r\n", b"\n")
    if b"\r" in normalized:
        fail("N7_CLOSED_PROBE_TOOL_LINE_ENDING_INVALID")
    return hashlib.sha256(normalized).hexdigest()


def require_snapshot(value: Any) -> dict[str, Any]:
    if type(value) is not dict or set(value) != {
        "snapshotIdentity", "providerAttempts", "providerReservations",
        "actualCostMicrosCny", "actualInputTokens", "actualOutputTokens", "files",
    }:
        fail("N7_CLOSED_PROBE_SNAPSHOT_SHAPE_INVALID")
    metrics = (
        "providerAttempts", "providerReservations", "actualCostMicrosCny",
        "actualInputTokens", "actualOutputTokens",
    )
    if any(type(value[field]) is not int or value[field] < 0 for field in metrics):
        fail("N7_CLOSED_PROBE_SNAPSHOT_METRIC_INVALID")
    files = value["files"]
    if type(files) is not dict or not files:
        fail("N7_CLOSED_PROBE_SNAPSHOT_FILE_SET_INVALID")
    for name, item in files.items():
        if type(name) is not str or not name or type(item) is not dict \
                or set(item) != {"bytes", "sha256"} \
                or type(item["bytes"]) is not int or item["bytes"] < 0 \
                or type(item["sha256"]) is not str or not SHA256.fullmatch(item["sha256"]):
            fail("N7_CLOSED_PROBE_SNAPSHOT_FILE_INVALID")
    body = {key: value[key] for key in value if key != "snapshotIdentity"}
    if value["snapshotIdentity"] != canonical_identity(probe.SNAPSHOT_VERSION, body):
        fail("N7_CLOSED_PROBE_SNAPSHOT_IDENTITY_DRIFT")
    return value


def require_instant(value: Any, field: str) -> datetime:
    if type(value) is not str:
        fail(f"N7_CLOSED_PROBE_{field.upper()}_INVALID")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as failure:
        raise VerificationError(f"N7_CLOSED_PROBE_{field.upper()}_INVALID") from failure
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        fail(f"N7_CLOSED_PROBE_{field.upper()}_INVALID")
    return parsed


def verify_record(
    repository: pathlib.Path,
    evidence_directory: pathlib.Path,
    value: dict[str, Any],
) -> dict[str, Any]:
    if type(value) is not dict or set(value) != RECORD_FIELDS:
        fail("N7_CLOSED_PROBE_RECORD_SHAPE_INVALID")
    exact = {
        "probeVersion": probe.PROBE_VERSION,
        "result": "PASS",
        "authorizationId": "n7-04-plus-canary-product-v45-20260814e",
        "failureCode": probe.EXPECTED_FAILURE,
        "probeInvocationCount": 1,
        "credentialsRemovedFromChild": True,
        "goalAndEvidenceHashesUnchanged": True,
        "processesBefore": 0,
        "processesRemaining": 0,
        "providerAttemptsCreated": 0,
        "providerReservationsCreated": 0,
        "externalProviderCostMicrosCnyCreated": 0,
        "payloadScan": "PASS",
    }
    if any(value[field] != expected for field, expected in exact.items()):
        fail("N7_CLOSED_PROBE_DECISION_INVALID")
    if type(value["failureCodeOccurrences"]) is not int \
            or value["failureCodeOccurrences"] < 1 \
            or type(value["probeProcessId"]) is not int or value["probeProcessId"] < 1 \
            or type(value["exitCode"]) is not int or value["exitCode"] == 0:
        fail("N7_CLOSED_PROBE_EXECUTION_INVALID")
    if type(value["probeMarker"]) is not str or not MARKER.fullmatch(value["probeMarker"]):
        fail("N7_CLOSED_PROBE_MARKER_INVALID")
    tools = {"WINDOWS": "mvn.cmd", "POSIX": "mvn"}
    if value["platform"] not in tools or value["commandTool"] != tools[value["platform"]]:
        fail("N7_CLOSED_PROBE_COMMAND_DRIFT")
    command = {
        "tool": value["commandTool"],
        "platform": value["platform"],
        "test": "DashScopeVisualEvaluationTest",
        "authorizationSelector": "qwen37-plus",
        "batchLimit": 5,
        "credentialsRemoved": True,
        "credentialSystemPropertiesBlank": True,
        "marker": value["probeMarker"],
    }
    if value["commandIdentity"] != canonical_identity(probe.PROBE_VERSION, command):
        fail("N7_CLOSED_PROBE_COMMAND_DRIFT")
    hashes = ("commandIdentity", "probeToolSha256", "stdoutSha256", "stderrSha256")
    if any(type(value[field]) is not str or not SHA256.fullmatch(
            value[field].split(":")[-1]) for field in hashes):
        fail("N7_CLOSED_PROBE_HASH_INVALID")
    current_tool_hash = normalized_source_sha256(pathlib.Path(probe.__file__).read_bytes())
    if value["probeToolSha256"] != current_tool_hash:
        fail("N7_CLOSED_PROBE_TOOL_DRIFT")
    started = require_instant(value["startedAt"], "startedAt")
    completed = require_instant(value["completedAt"], "completedAt")
    if completed < started:
        fail("N7_CLOSED_PROBE_TIME_ORDER_INVALID")
    before = require_snapshot(value["beforeRuntime"])
    after = require_snapshot(value["afterRuntime"])
    if before != after:
        fail("N7_CLOSED_PROBE_SNAPSHOT_DRIFT")
    current = probe.runtime_snapshot(repository.resolve(), evidence_directory.resolve())
    if after != current:
        fail("N7_CLOSED_PROBE_CURRENT_RUNTIME_DRIFT")
    remaining = probe.matching_process_ids(value["probeMarker"])
    if remaining:
        fail("N7_CLOSED_PROBE_PROCESS_REMAINING")
    return {
        "verificationVersion": VERIFIER_VERSION,
        "result": "PASS",
        "assurance": "MIXED_A1_DYNAMIC_A2_STATE",
        "dynamicExecutionAssurance": "A1_TOOL_CAPTURED",
        "runtimeStateAssurance": "A2_INDEPENDENT_READ_ONLY_RECONSTRUCTION",
        "authorizationId": value["authorizationId"],
        "failureCode": value["failureCode"],
        "probeInvocationCount": value["probeInvocationCount"],
        "goalAndEvidenceHashesUnchanged": True,
        "processesRemaining": 0,
        "providerAttemptsCreated": 0,
        "providerReservationsCreated": 0,
        "externalProviderCostMicrosCnyCreated": 0,
        "runtimeSnapshotIdentity": current["snapshotIdentity"],
        "payloadScan": "PASS",
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--evidence-directory", required=True, type=pathlib.Path)
    parser.add_argument("--probe", required=True, type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        raw = args.probe.read_text(encoding="utf-8", errors="strict")
        layered.scan_payload_free(raw, "N7 CLOSED negative probe")
        value = layered.parse_strict_json(raw)
        summary = verify_record(args.repository, args.evidence_directory, value)
        encoded = json.dumps(
            summary, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
        ) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8", newline="\n") as handle:
                handle.write(encoded)
        else:
            sys.stdout.write(encoded)
        return 0
    except (VerificationError, probe.ProbeError, layered.VerificationError,
            OSError, UnicodeError, ValueError) as failure:
        sys.stderr.write(f"N7 CLOSED negative probe verification failed: {failure}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
