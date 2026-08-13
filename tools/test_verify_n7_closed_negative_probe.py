#!/usr/bin/env python3
"""Tamper tests for independent N7 CLOSED negative-probe verification."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
import pathlib
import unittest
from unittest import mock


PATH = pathlib.Path(__file__).with_name("verify_n7_closed_negative_probe.py")
SPEC = importlib.util.spec_from_file_location("n7_closed_probe_verifier", PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("N7 CLOSED probe verifier cannot be loaded")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class N7ClosedProbeVerifierTest(unittest.TestCase):
    def test_fixed_probe_and_unchanged_current_runtime_pass(self) -> None:
        runtime = runtime_fixture()
        record = record_fixture(runtime)
        with mock.patch.object(
                VERIFIER.probe, "runtime_snapshot", return_value=runtime), \
                mock.patch.object(
                    VERIFIER.probe, "matching_process_ids", return_value=[]):
            result = VERIFIER.verify_record(
                pathlib.Path("repository"), pathlib.Path("evidence"), record,
            )

        self.assertEqual("PASS", result["result"])
        self.assertEqual("MIXED_A1_DYNAMIC_A2_STATE", result["assurance"])
        self.assertEqual("A1_TOOL_CAPTURED", result["dynamicExecutionAssurance"])
        self.assertEqual(
            "A2_INDEPENDENT_READ_ONLY_RECONSTRUCTION",
            result["runtimeStateAssurance"],
        )
        self.assertEqual(0, result["processesRemaining"])

    def test_tool_hash_is_line_ending_stable(self) -> None:
        self.assertEqual(
            VERIFIER.normalized_source_sha256(b"first\nsecond\n"),
            VERIFIER.normalized_source_sha256(b"first\r\nsecond\r\n"),
        )

    def test_mutated_snapshot_process_or_command_binding_fails_closed(self) -> None:
        runtime = runtime_fixture()
        mutations = []
        changed_runtime = copy.deepcopy(record_fixture(runtime))
        changed_runtime["afterRuntime"]["actualCostMicrosCny"] += 1
        mutations.append((changed_runtime, [], "N7_CLOSED_PROBE_SNAPSHOT_IDENTITY_DRIFT"))
        lingering = record_fixture(runtime)
        mutations.append((lingering, [999], "N7_CLOSED_PROBE_PROCESS_REMAINING"))
        changed_command = record_fixture(runtime)
        changed_command["commandIdentity"] = VERIFIER.probe.PROBE_VERSION + ":" + "0" * 64
        mutations.append((changed_command, [], "N7_CLOSED_PROBE_COMMAND_DRIFT"))

        for record, processes, code in mutations:
            with self.subTest(code=code), mock.patch.object(
                    VERIFIER.probe, "runtime_snapshot", return_value=runtime), \
                    mock.patch.object(
                        VERIFIER.probe, "matching_process_ids", return_value=processes):
                with self.assertRaisesRegex(VERIFIER.VerificationError, code):
                    VERIFIER.verify_record(
                        pathlib.Path("repository"), pathlib.Path("evidence"), record,
                    )


def runtime_fixture() -> dict[str, object]:
    body = {
        "providerAttempts": 27,
        "providerReservations": 27,
        "actualCostMicrosCny": 681_194,
        "actualInputTokens": 151_105,
        "actualOutputTokens": 47_373,
        "files": {"goal/goal-budget.json": {"bytes": 10, "sha256": "a" * 64}},
    }
    raw = json.dumps(
        body, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")
    return {
        "snapshotIdentity": VERIFIER.probe.SNAPSHOT_VERSION + ":" + hashlib.sha256(raw).hexdigest(),
        **body,
    }


def record_fixture(runtime: dict[str, object]) -> dict[str, object]:
    marker = "renderweave-n7-closed-probe-00000000-0000-4000-8000-000000000001"
    platform = "WINDOWS" if os.name == "nt" else "POSIX"
    tool = "mvn.cmd" if platform == "WINDOWS" else "mvn"
    command = {
        "tool": tool,
        "platform": platform,
        "test": "DashScopeVisualEvaluationTest",
        "authorizationSelector": "qwen37-plus",
        "batchLimit": 5,
        "credentialsRemoved": True,
        "credentialSystemPropertiesBlank": True,
        "marker": marker,
    }
    return {
        "probeVersion": VERIFIER.probe.PROBE_VERSION,
        "result": "PASS",
        "authorizationId": "n7-04-plus-canary-product-v45-20260814e",
        "failureCode": VERIFIER.probe.EXPECTED_FAILURE,
        "failureCodeOccurrences": 1,
        "probeInvocationCount": 1,
        "probeMarker": marker,
        "probeProcessId": 1234,
        "platform": platform,
        "commandTool": tool,
        "commandIdentity": VERIFIER.canonical_identity(
            VERIFIER.probe.PROBE_VERSION, command,
        ),
        "probeToolSha256": VERIFIER.normalized_source_sha256(
            pathlib.Path(VERIFIER.probe.__file__).read_bytes(),
        ),
        "startedAt": "2026-08-13T18:00:00.000Z",
        "completedAt": "2026-08-13T18:00:01.000Z",
        "exitCode": 1,
        "stdoutSha256": "b" * 64,
        "stderrSha256": "c" * 64,
        "credentialsRemovedFromChild": True,
        "beforeRuntime": copy.deepcopy(runtime),
        "afterRuntime": copy.deepcopy(runtime),
        "goalAndEvidenceHashesUnchanged": True,
        "processesBefore": 0,
        "processesRemaining": 0,
        "providerAttemptsCreated": 0,
        "providerReservationsCreated": 0,
        "externalProviderCostMicrosCnyCreated": 0,
        "payloadScan": "PASS",
    }


if __name__ == "__main__":
    unittest.main()
