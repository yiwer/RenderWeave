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

    def test_case_identity_dimensions_and_java_numeric_bounds_are_enforced(self) -> None:
        mutations = (
            ("caseIdentity", "identity"),
            ("sourceWidth", 0),
            ("oracleWidth", 1),
            ("oracleHeight", 2_401),
            ("sourceHeight", 2 ** 31),
        )
        for field, value in mutations:
            evidence = verifier_test_evidence()
            evidence["cases"][0][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(
                verifier.VerificationError, "R5_A2_EVIDENCE_CONTRACT_INVALID"
            ):
                verifier.validate_evidence_shape(evidence)

        metrics = verifier_test_metrics()
        metrics["observationCount"] = 2 ** 63
        with self.assertRaisesRegex(
            verifier.VerificationError, "R5_A2_CASE_METRICS_INVALID"
        ):
            verifier.verify_metrics(metrics)


def verifier_test_metrics() -> dict[str, int]:
    return {key: 0 for key in verifier.METRIC_FIELDS}


def verifier_test_evidence() -> dict[str, object]:
    return {
        "acquisitionPolicyIdentity": "AcquisitionPolicy/1.0:" + "1" * 64,
        "actualAcquisitions": 16,
        "annotationSetIdentity": "renderweave-layered-annotation-set/2.0:" + "2" * 64,
        "assignmentIdentity": "renderweave-r5-probe-assignment/1.0:" + "3" * 64,
        "capabilityIdentity": "capability",
        "cases": [verifier_test_case(index) for index in range(4)],
        "contractVersion": verifier.EVIDENCE_VERSION,
        "corpusIdentity": "renderweave-visual-stage-corpus/2.0:" + "4" * 64,
        "deterministicCases": 4,
        "devCases": 3,
        "disposition": "TRIGGERED",
        "evaluationIdentity": "renderweave-r5-oracle-evaluation/1.0:" + "5" * 64,
        "externalProviderUsage": {
            "attempts": 0,
            "reservations": 0,
            "costMicrosCny": 0,
        },
        "holdoutCases": 1,
        "predicates": {key: "PASS" for key in verifier.PREDICATE_FIELDS},
        "protocolIdentity": verifier.PROTOCOL_VERSION + ":" + "6" * 64,
        "reasonCode": "R5_TEST_REASON",
        "runs": 2,
        "transformIdentity": verifier.TRANSFORM_VERSION + ":" + "7" * 64,
        "triggered": True,
    }


def verifier_test_case(index: int) -> dict[str, object]:
    return {
        "baseline": verifier_test_metrics(),
        "caseId": f"case-{index + 1}",
        "caseIdentity": "renderweave-layered-case/2.0:" + str(index + 1) * 64,
        "deterministic": True,
        "oracle": verifier_test_metrics(),
        "oracleHeight": 2,
        "oracleWidth": 2,
        "partition": "DEV" if index < 3 else "HOLDOUT",
        "sourceHeight": 1,
        "sourceWidth": 1,
    }


if __name__ == "__main__":
    unittest.main()
