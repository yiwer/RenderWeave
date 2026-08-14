#!/usr/bin/env python3
"""Regression tests for the VRQ-06 independent evidence verifier."""

from __future__ import annotations

import copy
import unittest

import verify_vrq06_r5_probe as verifier


class Vrq06R5ProbeVerifierTest(unittest.TestCase):
    def test_evidence_case_and_metric_contracts_are_closed(self) -> None:
        evidence = verifier_test_evidence()
        verifier.validate_evidence_shape(evidence)

        for mutation in ("top", "case", "metric"):
            tampered = copy.deepcopy(evidence)
            targets = {
                "top": tampered,
                "case": tampered["cases"][0],
                "metric": tampered["cases"][0]["baseline"],
            }
            targets[mutation]["unexpectedEvidence"] = 0
            with self.subTest(mutation=mutation), self.assertRaisesRegex(
                verifier.VerificationError, "R5_A2_EVIDENCE_CONTRACT_INVALID|R5_A2_CASE_METRICS_INVALID"
            ):
                verifier.validate_evidence_shape(tampered)

    def test_numeric_and_boolean_types_are_not_interchangeable(self) -> None:
        for field, value in (("triggered", 1), ("runs", 2.0)):
            evidence = verifier_test_evidence()
            evidence[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(
                verifier.VerificationError, "R5_A2_EVIDENCE_CONTRACT_INVALID"
            ):
                verifier.validate_evidence_shape(evidence)

        evidence = verifier_test_evidence()
        evidence["externalProviderUsage"]["reservations"] = 0.0
        with self.assertRaisesRegex(
            verifier.VerificationError, "R5_A2_EVIDENCE_CONTRACT_INVALID"
        ):
            verifier.validate_evidence_shape(evidence)

        metrics = verifier_test_metrics()
        metrics["observationCount"] = True
        with self.assertRaisesRegex(
            verifier.VerificationError, "R5_A2_CASE_METRICS_INVALID"
        ):
            verifier.verify_metrics(metrics)

    def test_decoded_payload_markers_are_rejected(self) -> None:
        for decoded in (
            verifier.strict_json(b'{"\\u006dodelOutput":"secret"}'),
            {"providerRequest": "secret"},
            {"providerResponse": "secret"},
            {"authorization": "Bearer secret"},
        ):
            with self.subTest(decoded=decoded), self.assertRaisesRegex(
                verifier.VerificationError, "R5_A2_DECODED_PAYLOAD_FORBIDDEN"
            ):
                verifier.ensure_decoded_payload_safe(decoded)


def verifier_test_metrics() -> dict[str, int]:
    return {key: 0 for key in verifier.METRIC_FIELDS}


def verifier_test_evidence() -> dict[str, object]:
    return {
        "acquisitionPolicyIdentity": "policy",
        "actualAcquisitions": 16,
        "annotationSetIdentity": "annotation",
        "assignmentIdentity": "assignment",
        "capabilityIdentity": "capability",
        "cases": [{
            "baseline": verifier_test_metrics(),
            "caseId": "case-1",
            "caseIdentity": "identity",
            "deterministic": True,
            "oracle": verifier_test_metrics(),
            "oracleHeight": 2,
            "oracleWidth": 2,
            "partition": "DEV",
            "sourceHeight": 1,
            "sourceWidth": 1,
        }],
        "contractVersion": verifier.EVIDENCE_VERSION,
        "corpusIdentity": "corpus",
        "deterministicCases": 4,
        "devCases": 3,
        "disposition": "TRIGGERED",
        "evaluationIdentity": "evaluation",
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
        "transformIdentity": "transform",
        "triggered": True,
    }


if __name__ == "__main__":
    unittest.main()
