#!/usr/bin/env python3
"""Regression tests for decoded payload safety in the VRQ-07 verifier."""

from __future__ import annotations

import unittest

import verify_vrq07_offline_decision as verifier


class Vrq07OfflineDecisionVerifierTest(unittest.TestCase):
    def test_unicode_escape_cannot_hide_forbidden_member(self) -> None:
        decoded = verifier.strict_json(b'{"\\u006dodelOutput":"secret"}')
        with self.assertRaisesRegex(
            verifier.VerificationError, "VRQ07_A2_DECODED_PAYLOAD_FORBIDDEN"
        ):
            verifier.ensure_decoded_payload_safe(decoded)

    def test_component_summary_schema_is_closed(self) -> None:
        with self.assertRaisesRegex(
            verifier.VerificationError, "VRQ07_A2_COMPONENT_SUMMARY_SCHEMA_INVALID"
        ):
            verifier.validate_component_summary("RAPIDOCR_CAUSAL", {"result": "PASS"})

    def test_provider_payload_markers_match_java_boundary(self) -> None:
        for decoded in (
            {"providerRequest": "secret"},
            {"providerResponse": "secret"},
            {"authorization": "Bearer secret"},
        ):
            with self.subTest(decoded=decoded), self.assertRaisesRegex(
                verifier.VerificationError, "VRQ07_A2_DECODED_PAYLOAD_FORBIDDEN"
            ):
                verifier.ensure_decoded_payload_safe(decoded)

    def test_component_summary_rejects_json_type_confusion(self) -> None:
        valid = {
            "assignmentIdentity": "renderweave-r3-probe-assignment/1.0:" + "a" * 64,
            "assurance": "A2_CROSS_IMPLEMENTATION_RECOMPUTE",
            "caseCount": 4,
            "devCases": 3,
            "disposition": "MISSING",
            "evidenceIdentity": "renderweave-r3-order-repeat-probe-evidence/1.0:" + "b" * 64,
            "externalProviderCostMicrosCny": 0,
            "holdoutCases": 1,
            "providerAttempts": 0,
            "providerReservations": 0,
            "reasonCode": "R3_OCR_OMISSION_NOT_EXCLUDED",
            "repositoryRevision": "c" * 40,
            "result": "PASS",
            "runs": 2,
            "triggered": False,
            "verifierVersion": "renderweave-r3-probe-independent-verifier/1.0",
        }
        verifier.validate_component_summary("R3_PROBE", valid)
        for field, confused in (
            ("holdoutCases", True),
            ("triggered", 0),
            ("runs", 2.0),
        ):
            tampered = dict(valid)
            tampered[field] = confused
            with self.subTest(field=field), self.assertRaisesRegex(
                verifier.VerificationError, "VRQ07_A2_COMPONENT_SUMMARY_SCHEMA_INVALID"
            ):
                verifier.validate_component_summary("R3_PROBE", tampered)


if __name__ == "__main__":
    unittest.main()
