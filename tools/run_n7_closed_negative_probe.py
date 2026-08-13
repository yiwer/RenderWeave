#!/usr/bin/env python3
"""Run one fixed, credential-free N7 CLOSED-ledger negative probe."""

from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
from typing import Any
import uuid

import verify_layered_evaluation as layered
import verify_n7_live_admission as admission
import verify_n7_live_evidence as evidence_verifier


PROBE_VERSION = "renderweave-n7-closed-negative-probe/1.0"
SNAPSHOT_VERSION = "renderweave-n7-runtime-snapshot/1.0"
EXPECTED_FAILURE = "N7_LIVE_AUTHORIZATION_NOT_OPEN"
FORBIDDEN_CHILD_ENVIRONMENT_NAMES = {
    "DASHSCOPE_TOKEN_API_KEY",
    "DASHSCOPE_TOKEN_API_KEY_FILE",
    "DASHSCOPE_API_KEY",
    "DASHSCOPE_API_KEY_FILE",
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "_JAVA_OPTIONS",
    "MAVEN_OPTS",
    "MAVEN_ARGS",
    "SPRING_APPLICATION_JSON",
}
PASSTHROUGH_ENVIRONMENT_NAMES = {
    "APPDATA", "COMSPEC", "DOCKER_CERT_PATH", "DOCKER_CONTEXT", "DOCKER_HOST",
    "DOCKER_TLS_VERIFY", "HOMEDRIVE", "HOME", "HOMEPATH", "HTTP_PROXY",
    "HTTPS_PROXY", "JAVA_HOME", "LANG", "LC_ALL", "LOCALAPPDATA", "M2_HOME",
    "MAVEN_HOME", "NO_PROXY", "OS", "PATH", "PATHEXT", "PROGRAMDATA",
    "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMW6432", "SYSTEMDRIVE",
    "SYSTEMROOT", "TEMP", "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
    "TESTCONTAINERS_HOST_OVERRIDE", "TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED",
    "TESTCONTAINERS_RYUK_DISABLED", "TMP", "TMPDIR", "USERPROFILE", "WINDIR",
}


class ProbeError(Exception):
    pass


def fail(code: str) -> None:
    raise ProbeError(code)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z",
    )


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def normalized_source_sha256(value: bytes) -> str:
    normalized = value.replace(b"\r\n", b"\n")
    if b"\r" in normalized:
        fail("N7_CLOSED_PROBE_TOOL_LINE_ENDING_INVALID")
    return sha256(normalized)


def canonical_identity(version: str, value: dict[str, Any]) -> str:
    raw = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")
    return f"{version}:{sha256(raw)}"


def runtime_snapshot(
    repository: pathlib.Path,
    evidence_directory: pathlib.Path,
) -> dict[str, Any]:
    audit = evidence_verifier.verify(
        repository, evidence_directory,
        policy=evidence_verifier.VerificationPolicy.AUDIT_OUTCOME,
    )
    goal_directory = repository.joinpath(*admission.GOAL_PATH.parts)
    paths: dict[str, pathlib.Path] = {
        **{
            f"ledger/{selector}.json": repository.joinpath(*relative.parts)
            for selector, relative in admission.LEDGERS.items()
        },
        "goal/goal-budget.json": goal_directory / "goal-budget.json",
        "goal/goal-budget.guard.json": goal_directory / "goal-budget.guard.json",
        "goal/goal-budget.lock": goal_directory / "goal-budget.lock",
        **{
            f"evidence/{name}": evidence_directory / name
            for name in sorted(evidence_verifier.EVIDENCE_FILES)
        },
    }
    files: dict[str, dict[str, Any]] = {}
    with evidence_verifier.lock_evidence_snapshot(evidence_directory, goal_directory):
        for name, path in paths.items():
            if not path.is_file() or path.is_symlink():
                fail("N7_CLOSED_PROBE_INPUT_UNAVAILABLE")
            if path.name.endswith(".lock"):
                if path.stat().st_size != 0:
                    fail("N7_CLOSED_PROBE_LOCK_NOT_EMPTY")
                raw = b""
            else:
                raw = path.read_bytes()
            files[name] = {"bytes": len(raw), "sha256": sha256(raw)}
    snapshot = {
        "providerAttempts": audit["providerAttempts"],
        "providerReservations": audit["providerAttempts"],
        "actualCostMicrosCny": audit["actualCostMicrosCny"],
        "actualInputTokens": audit["actualInputTokens"],
        "actualOutputTokens": audit["actualOutputTokens"],
        "files": files,
    }
    return {
        "snapshotIdentity": canonical_identity(SNAPSHOT_VERSION, snapshot),
        **snapshot,
    }


def matching_process_ids(marker: str) -> list[int]:
    if os.name == "nt":
        script = "\n".join((
            f"$marker = {json.dumps(marker)}",
            "$ids = @(Get-CimInstance Win32_Process | Where-Object {",
            "  $_.ProcessId -ne $PID -and $_.CommandLine -and $_.CommandLine.Contains($marker)",
            "} | ForEach-Object { [int]$_.ProcessId })",
            "$ids | ConvertTo-Json -Compress",
        ))
        encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
        completed = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
            check=True, capture_output=True, text=True, timeout=30,
        )
        output = completed.stdout.strip()
        if not output:
            return []
        value = json.loads(output)
        return sorted(int(item) for item in (value if isinstance(value, list) else [value]))
    completed = subprocess.run(
        ["ps", "-eo", "pid=,args="], check=True, capture_output=True, text=True, timeout=30,
    )
    return sorted(
        int(line.strip().split(maxsplit=1)[0])
        for line in completed.stdout.splitlines()
        if marker in line and line.strip().split(maxsplit=1)[0].isdigit()
    )


def child_environment() -> dict[str, str]:
    environment = {
        key: value for key, value in os.environ.items()
        if key.upper() in PASSTHROUGH_ENVIRONMENT_NAMES
    }
    environment["RENDERWEAVE_RUN_VISUAL_EVALUATION"] = "true"
    environment["RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION"] = "qwen37-plus"
    return environment


def run_probe(
    repository: pathlib.Path,
    evidence_directory: pathlib.Path,
    timeout_seconds: int = 900,
) -> dict[str, Any]:
    if not repository.is_dir() or not evidence_directory.is_dir():
        fail("N7_CLOSED_PROBE_DIRECTORY_UNAVAILABLE")
    repository = repository.resolve()
    evidence_directory = evidence_directory.resolve()
    before = runtime_snapshot(repository, evidence_directory)
    marker = f"renderweave-n7-closed-probe-{uuid.uuid4()}"
    processes_before = matching_process_ids(marker)
    if processes_before:
        fail("N7_CLOSED_PROBE_PROCESS_PREEXISTING")
    executable = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
    if executable is None:
        fail("N7_CLOSED_PROBE_MAVEN_UNAVAILABLE")
    maven_command = [
        executable, "-B", "-ntp", "-pl", "renderweave-app", "-am",
        "-Dtest=DashScopeVisualEvaluationTest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Drenderweave.visual-evaluation.batch-limit=5",
        f"-Drenderweave.n7.closed-probe-id={marker}",
        "test",
    ]
    command = maven_command
    if os.name == "nt":
        command_processor = shutil.which("cmd.exe")
        if command_processor is None:
            fail("N7_CLOSED_PROBE_COMMAND_PROCESSOR_UNAVAILABLE")
        command = [
            command_processor, "/d", "/s", "/c",
            subprocess.list2cmdline(maven_command),
        ]
    command_contract = {
        "tool": pathlib.Path(executable).name,
        "platform": "WINDOWS" if os.name == "nt" else "POSIX",
        "test": "DashScopeVisualEvaluationTest",
        "authorizationSelector": "qwen37-plus",
        "batchLimit": 5,
        "credentialsRemoved": True,
        "marker": marker,
    }
    started_at = utc_now()
    process = subprocess.Popen(
        command,
        cwd=repository,
        env=child_environment(),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        stdout, stderr = process.communicate(timeout=timeout_seconds)
    except subprocess.TimeoutExpired as timeout:
        process.kill()
        process.communicate()
        raise ProbeError("N7_CLOSED_PROBE_TIMEOUT") from timeout
    completed_at = utc_now()
    if process.poll() is None:
        fail("N7_CLOSED_PROBE_WRAPPER_REMAINING")
    combined = stdout + b"\n" + stderr
    failure_occurrences = combined.count(EXPECTED_FAILURE.encode("ascii"))
    if process.returncode == 0 or failure_occurrences < 1:
        fail("N7_CLOSED_PROBE_EXPECTED_FAILURE_MISSING")
    after = runtime_snapshot(repository, evidence_directory)
    processes_remaining = matching_process_ids(marker)
    if processes_remaining:
        fail("N7_CLOSED_PROBE_PROCESS_REMAINING")
    if before != after:
        fail("N7_CLOSED_PROBE_RUNTIME_MUTATED")
    result = {
        "probeVersion": PROBE_VERSION,
        "result": "PASS",
        "authorizationId": "n7-04-plus-canary-product-v45-20260814e",
        "failureCode": EXPECTED_FAILURE,
        "failureCodeOccurrences": failure_occurrences,
        "probeInvocationCount": 1,
        "probeMarker": marker,
        "probeProcessId": process.pid,
        "platform": command_contract["platform"],
        "commandTool": command_contract["tool"],
        "commandIdentity": canonical_identity(PROBE_VERSION, command_contract),
        "probeToolSha256": normalized_source_sha256(pathlib.Path(__file__).read_bytes()),
        "startedAt": started_at,
        "completedAt": completed_at,
        "exitCode": process.returncode,
        "stdoutSha256": sha256(stdout),
        "stderrSha256": sha256(stderr),
        "credentialsRemovedFromChild": True,
        "beforeRuntime": before,
        "afterRuntime": after,
        "goalAndEvidenceHashesUnchanged": True,
        "processesBefore": 0,
        "processesRemaining": 0,
        "providerAttemptsCreated": 0,
        "providerReservationsCreated": 0,
        "externalProviderCostMicrosCnyCreated": 0,
        "payloadScan": "PASS",
    }
    encoded = json.dumps(
        result, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    )
    layered.scan_payload_free(encoded, "N7 CLOSED negative probe")
    return result


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--evidence-directory", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--timeout-seconds", type=int, default=900)
    args = parser.parse_args(argv)
    try:
        result = run_probe(
            args.repository, args.evidence_directory, args.timeout_seconds,
        )
        encoded = json.dumps(
            result, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
        ) + "\n"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8", newline="\n") as handle:
            handle.write(encoded)
        return 0
    except (ProbeError, evidence_verifier.VerificationError,
            admission.VerificationError, layered.VerificationError,
            OSError, UnicodeError, ValueError, subprocess.SubprocessError) as failure:
        sys.stderr.write(f"N7 CLOSED negative probe failed: {failure}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
