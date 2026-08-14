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


if __name__ == "__main__":
    unittest.main()
