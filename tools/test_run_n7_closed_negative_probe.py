#!/usr/bin/env python3
"""Tests for the fixed, zero-credential N7 CLOSED negative probe."""

from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import unittest
from unittest import mock


PATH = pathlib.Path(__file__).with_name("run_n7_closed_negative_probe.py")
SPEC = importlib.util.spec_from_file_location("n7_closed_negative_probe", PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("N7 CLOSED negative probe cannot be loaded")
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


class N7ClosedNegativeProbeTest(unittest.TestCase):
    @mock.patch.dict("os.environ", {
        "DASHSCOPE_TOKEN_API_KEY": "must-not-reach-child",
        "DASHSCOPE_TOKEN_API_KEY_FILE": "must-not-reach-child",
        "DASHSCOPE_API_KEY": "legacy-must-not-reach-child",
    })
    @mock.patch.object(subprocess, "Popen")
    @mock.patch.object(pathlib.Path, "is_dir", return_value=True)
    def test_expected_closed_failure_is_captured_without_credentials_or_mutation(
        self, _is_dir: mock.Mock, popen: mock.Mock,
    ) -> None:
        snapshot = {
            "snapshotIdentity": "renderweave-n7-runtime-snapshot/1.0:" + "a" * 64,
            "providerAttempts": 27,
            "providerReservations": 27,
            "actualCostMicrosCny": 681_194,
            "files": {"goal-budget.json": {"bytes": 1, "sha256": "b" * 64}},
        }
        process = popen.return_value
        process.pid = 1234
        process.returncode = 1
        process.communicate.return_value = (
            b"N7_LIVE_AUTHORIZATION_NOT_OPEN\n", b"maven failed as expected\n",
        )
        process.poll.return_value = 1

        with mock.patch.object(PROBE, "runtime_snapshot", side_effect=[snapshot, snapshot]), \
                mock.patch.object(PROBE, "matching_process_ids", side_effect=[[], []]), \
                mock.patch.object(PROBE.shutil, "which", side_effect=tool_path):
            result = PROBE.run_probe(pathlib.Path("repository"), pathlib.Path("evidence"))

        self.assertEqual("PASS", result["result"])
        self.assertEqual("N7_LIVE_AUTHORIZATION_NOT_OPEN", result["failureCode"])
        self.assertEqual(0, result["providerAttemptsCreated"])
        self.assertEqual(0, result["providerReservationsCreated"])
        self.assertEqual(0, result["externalProviderCostMicrosCnyCreated"])
        self.assertTrue(result["goalAndEvidenceHashesUnchanged"])
        self.assertEqual(0, result["processesRemaining"])
        self.assertEqual(1, result["probeInvocationCount"])

        child_environment = popen.call_args.kwargs["env"]
        child_keys = {key.upper() for key in child_environment}
        self.assertTrue(PROBE.SECRET_ENVIRONMENT_NAMES.isdisjoint(child_keys))
        self.assertEqual("true", child_environment["RENDERWEAVE_RUN_VISUAL_EVALUATION"])
        self.assertEqual(
            "qwen37-plus",
            child_environment["RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION"],
        )
        launched = popen.call_args.args[0]
        self.assertEqual(
            "cmd.exe" if PROBE.os.name == "nt" else "mvn",
            pathlib.Path(launched[0]).name,
        )

    @mock.patch.object(subprocess, "Popen")
    @mock.patch.object(pathlib.Path, "is_dir", return_value=True)
    def test_any_runtime_mutation_fails_closed(
        self, _is_dir: mock.Mock, popen: mock.Mock,
    ) -> None:
        before = {
            "snapshotIdentity": "renderweave-n7-runtime-snapshot/1.0:" + "a" * 64,
            "providerAttempts": 27,
            "providerReservations": 27,
            "actualCostMicrosCny": 681_194,
            "files": {},
        }
        after = {**before, "snapshotIdentity":
                 "renderweave-n7-runtime-snapshot/1.0:" + "b" * 64}
        process = popen.return_value
        process.pid = 1234
        process.returncode = 1
        process.communicate.return_value = (b"N7_LIVE_AUTHORIZATION_NOT_OPEN", b"")
        process.poll.return_value = 1

        with mock.patch.object(PROBE, "runtime_snapshot", side_effect=[before, after]), \
                mock.patch.object(PROBE, "matching_process_ids", side_effect=[[], []]), \
                mock.patch.object(PROBE.shutil, "which", side_effect=tool_path):
            with self.assertRaisesRegex(
                    PROBE.ProbeError, "N7_CLOSED_PROBE_RUNTIME_MUTATED"):
                PROBE.run_probe(pathlib.Path("repository"), pathlib.Path("evidence"))


def tool_path(name: str) -> str:
    return {"mvn.cmd": "mvn.cmd", "mvn": "mvn", "cmd.exe": "cmd.exe"}[name]


if __name__ == "__main__":
    unittest.main()
