#!/usr/bin/env python3
"""Regression tests for the fail-closed terminal outcome verifier."""

from __future__ import annotations

import pathlib
import subprocess
import sys
import unittest

import verify_offline_repair_terminal_outcome as verifier


class OfflineRepairTerminalVerifierTest(unittest.TestCase):
    def test_duplicate_json_member_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            verifier.VerificationError, "OFFLINE_TERMINAL_DUPLICATE_MEMBER"
        ):
            verifier.strict_json(b'{"ticket":"VRQ_08","ticket":"VRQ_09"}')

    def test_boolean_does_not_count_as_zero_execution(self) -> None:
        self.assertFalse(verifier.zero_values({"providerAttempts": False}))

    def test_outcome_payload_schema_is_closed(self) -> None:
        outcome = {
            "contractVersion": "OfflineRepairTerminalOutcome/1.0",
            "ticket": "VRQ_08_PP_STRUCTUREV3_DEV_SHADOW",
            "rootDecisionIdentity": verifier.AUTHORITATIVE_DECISION_IDENTITY,
            "rootDisposition": "STOP_TO_SPEC_R5",
            "supportingIdentities": [],
            "disposition": "STOPPED_FOR_R5_SUCCESSOR_SPEC",
            "reasonCode": "R5_TRIGGERED_REQUIRES_SUCCESSOR_SPEC",
            "offlineWorkUsage": {
                "artifactAcquisitions": 0,
                "devCasesExecuted": 0,
                "holdoutCasesAccessed": 0,
                "scriptedWorkflowReplays": 0,
                "independentAdmissionReplays": 0,
                "productWrites": 0,
                "apiKeyReads": 0,
            },
            "externalProviderUsage": {
                "attempts": 0,
                "reservations": 0,
                "costMicrosCny": 0,
            },
            "unexpectedEvidence": "caller-attested",
        }
        with self.assertRaisesRegex(
            verifier.VerificationError, "OFFLINE_TERMINAL_OUTCOME_MEMBERS_INVALID"
        ):
            verifier.require_outcome_shape(outcome)

    def test_python_optimized_mode_is_rejected_before_argument_parsing(self) -> None:
        script = pathlib.Path(verifier.__file__).resolve()
        completed = subprocess.run(
            [sys.executable, "-O", str(script), "--help"],
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        self.assertNotEqual(0, completed.returncode)
        self.assertIn(
            "OFFLINE_TERMINAL_OPTIMIZED_MODE_FORBIDDEN",
            completed.stdout + completed.stderr,
        )


if __name__ == "__main__":
    unittest.main()
