#!/usr/bin/env python3
"""Regression tests for decoded payload safety in the VRQ-07 verifier."""

from __future__ import annotations

import pathlib
import tempfile
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

    def test_canonical_contract_comparison_rejects_nested_number_coercion(self) -> None:
        expected = {"externalProviderUsage": {"attempts": 0, "reservations": 0}}
        for confused in (
            {"externalProviderUsage": {"attempts": False, "reservations": 0}},
            {"externalProviderUsage": {"attempts": 0, "reservations": 0.0}},
        ):
            with self.subTest(confused=confused):
                self.assertFalse(verifier.same_json_value(confused, expected))

    def test_component_evidence_envelope_is_closed(self) -> None:
        value = {
            "envelopeVersion": "test-envelope/1.0",
            "evidenceIdentity": "test-evidence/1.0:" + "a" * 64,
            "evidence": {},
            "unexpectedEvidence": "caller-attested",
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / "evidence.json"
            path.write_bytes(verifier.canonical_json(value))
            with self.assertRaisesRegex(
                verifier.VerificationError, "VRQ07_A2_INPUT_ENVELOPE_INVALID"
            ):
                verifier.envelope(
                    path,
                    "test-envelope/1.0",
                    "evidenceIdentity",
                    "evidence",
                )

    def test_component_envelope_identity_and_digest_share_one_snapshot(self) -> None:
        value = {
            "envelopeVersion": "test-envelope/1.0",
            "evidenceIdentity": "test-evidence/1.0:" + "a" * 64,
            "evidence": {},
        }
        raw = verifier.canonical_json(value)

        class SingleReadSource:
            reads = 0

            def read_bytes(self) -> bytes:
                self.reads += 1
                if self.reads > 1:
                    raise AssertionError("component envelope was read more than once")
                return raw

        source = SingleReadSource()
        payload, identity, captured = verifier.envelope(
            source,
            "test-envelope/1.0",
            "evidenceIdentity",
            "evidence",
        )
        self.assertEqual(1, source.reads)
        self.assertEqual({}, payload)
        self.assertEqual(value["evidenceIdentity"], identity)
        self.assertEqual(raw, captured)


if __name__ == "__main__":
    unittest.main()
