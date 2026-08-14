#!/usr/bin/env python3
"""Regression tests for the VRQ-04 independent evidence verifier."""

from __future__ import annotations

import unittest

import verify_vrq04_causal_evidence as verifier


class Vrq04CausalEvidenceVerifierTest(unittest.TestCase):
    def test_json_comparison_does_not_coerce_boolean_or_float(self) -> None:
        expected = {"attempts": 0, "reservations": 0}
        self.assertFalse(verifier.same_json_value(
            {"attempts": False, "reservations": 0}, expected))
        self.assertFalse(verifier.same_json_value(
            {"attempts": 0, "reservations": 0.0}, expected))

    def test_decoded_payload_markers_are_rejected(self) -> None:
        for decoded in (
            verifier.strict_json(b'{"\\u006dodelOutput":"secret"}'),
            {"providerRequest": "secret"},
            {"providerResponse": "secret"},
            {"authorization": "Bearer secret"},
        ):
            with self.subTest(decoded=decoded), self.assertRaisesRegex(
                verifier.VerificationError, "CAUSAL_DECODED_PAYLOAD_FORBIDDEN"
            ):
                verifier.ensure_decoded_payload_safe(decoded)

    def test_pack_nested_contract_is_closed_and_typed(self) -> None:
        pack = verifier_test_pack()
        verifier.validate_pack_shape(pack)

        pack["accounting"]["attempts"] = 0
        with self.assertRaisesRegex(
            verifier.VerificationError, "CAUSAL_PACK_CONTRACT_INVALID"
        ):
            verifier.validate_pack_shape(pack)

        pack = verifier_test_pack()
        pack["externalProviderUsage"]["attempts"] = False
        with self.assertRaisesRegex(
            verifier.VerificationError, "CAUSAL_PACK_CONTRACT_INVALID"
        ):
            verifier.validate_pack_shape(pack)


def verifier_test_pack() -> dict[str, object]:
    return {
        "contractVersion": verifier.PACK_VERSION,
        "evaluationIdentity": "evaluation",
        "protocolIdentity": "protocol",
        "corpusIdentity": "corpus",
        "annotationSetIdentity": "annotation",
        "capabilityIdentity": "capability",
        "acquisitionPolicyIdentity": "policy",
        "accounting": {
            "runs": 2,
            "casesPerRun": 60,
            "devPerRun": 45,
            "holdoutPerRun": 15,
            "actualAcquisitions": 120,
            "metricsEquivalentCases": 60,
            "observationEquivalentCases": 60,
        },
        "metrics": {
            "GLOBAL": {"caseCount": 60, "metricsBps": {"ocr.cer": 0}},
        },
        "evidenceFacts": {
            key: 0 for key in verifier.EVIDENCE_FACT_FIELDS
        },
        "attributions": {
            key: {
                "evidenceReference": "evidence",
                "reasonCode": "reason",
                "result": "MISSING",
            }
            for key in verifier.ATTRIBUTION_FIELDS
        },
        "externalProviderUsage": {
            "attempts": 0,
            "reservations": 0,
            "costMicrosCny": 0,
        },
    }


if __name__ == "__main__":
    unittest.main()
