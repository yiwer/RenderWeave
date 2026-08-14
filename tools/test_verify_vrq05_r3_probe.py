#!/usr/bin/env python3
"""Regression tests for the VRQ-05 independent evidence verifier."""

from __future__ import annotations

import copy
import unittest

import verify_vrq05_r3_probe as verifier


class Vrq05R3ProbeVerifierTest(unittest.TestCase):
    def test_evidence_and_case_contracts_are_closed(self) -> None:
        evidence = verifier_test_evidence()
        verifier.validate_evidence_shape(evidence)

        for mutation in ("top", "case"):
            tampered = copy.deepcopy(evidence)
            target = tampered if mutation == "top" else tampered["cases"][0]
            target["unexpectedEvidence"] = "caller-attested"
            with self.subTest(mutation=mutation), self.assertRaisesRegex(
                verifier.VerificationError, "R3_A2_EVIDENCE_CONTRACT_INVALID"
            ):
                verifier.validate_evidence_shape(tampered)

    def test_boolean_and_integer_types_are_not_interchangeable(self) -> None:
        for field, value in (
            ("triggered", 0),
            ("runs", 2.0),
        ):
            evidence = verifier_test_evidence()
            evidence[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(
                verifier.VerificationError, "R3_A2_EVIDENCE_CONTRACT_INVALID"
            ):
                verifier.validate_evidence_shape(evidence)

        evidence = verifier_test_evidence()
        evidence["externalProviderUsage"]["attempts"] = False
        with self.assertRaisesRegex(
            verifier.VerificationError, "R3_A2_EVIDENCE_CONTRACT_INVALID"
        ):
            verifier.validate_evidence_shape(evidence)

    def test_decoded_payload_markers_are_rejected(self) -> None:
        for decoded in (
            verifier.strict_json(b'{"\\u006dodelOutput":"secret"}'),
            {"providerRequest": "secret"},
            {"providerResponse": "secret"},
            {"authorization": "Bearer secret"},
        ):
            with self.subTest(decoded=decoded), self.assertRaisesRegex(
                verifier.VerificationError, "R3_A2_DECODED_PAYLOAD_FORBIDDEN"
            ):
                verifier.ensure_decoded_payload_safe(decoded)


def verifier_test_evidence() -> dict[str, object]:
    return {
        "assignmentIdentity": "assignment",
        "cases": [{
            "allReferencedRegionsObserved": True,
            "caseId": "case-1",
            "caseIdentity": "identity",
            "comparablePrecedenceEdges": 1,
            "correctPrecedenceEdges": 1,
            "expectedLines": 1,
            "expectedPrecedenceEdges": 1,
            "expectedRepeatMemberships": 0,
            "matchedLines": 1,
            "observableRepeatMemberships": 0,
            "orderOrRepeatDefectObserved": False,
            "partition": "DEV",
        }],
        "contractVersion": verifier.EVIDENCE_VERSION,
        "devCases": 3,
        "disposition": "MISSING",
        "externalProviderUsage": {
            "attempts": 0,
            "reservations": 0,
            "costMicrosCny": 0,
        },
        "holdoutCases": 1,
        "predicates": {key: "PASS" for key in verifier.PREDICATE_FIELDS},
        "protocolIdentity": "protocol",
        "reasonCode": "reason",
        "runs": 2,
        "sourceEvaluationIdentity": "evaluation",
        "sourceReportIdentity": "report",
        "triggered": False,
    }


if __name__ == "__main__":
    unittest.main()
