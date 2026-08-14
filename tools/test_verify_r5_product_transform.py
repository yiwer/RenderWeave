#!/usr/bin/env python3
"""Adversarial contract tests for the R5 product-transform A2 verifier."""

from __future__ import annotations

import copy
import unittest

import verify_r5_product_transform as verifier


class R5ProductTransformVerifierTest(unittest.TestCase):
    def test_every_evidence_layer_is_closed(self) -> None:
        evidence = sample_evidence()
        verifier.validate_evidence_shape(evidence)
        targets = (
            evidence,
            evidence["runs"][0],
            evidence["runs"][0]["cases"][0],
            evidence["runs"][0]["cases"][0]["staticView"],
            evidence["runs"][0]["cases"][0]["staticResource"],
        )
        for index in range(len(targets)):
            tampered = copy.deepcopy(evidence)
            selected = (
                tampered,
                tampered["runs"][0],
                tampered["runs"][0]["cases"][0],
                tampered["runs"][0]["cases"][0]["staticView"],
                tampered["runs"][0]["cases"][0]["staticResource"],
            )[index]
            selected["unexpectedEvidence"] = 0
            with self.subTest(index=index), self.assertRaises(verifier.VerificationError):
                verifier.validate_evidence_shape(tampered)

    def test_boolean_float_and_integer_values_are_not_interchangeable(self) -> None:
        mutations = (
            ("actualAcquisitions", False),
            ("deterministicCases", 4.0),
            ("qualified", 1),
        )
        for field, value in mutations:
            evidence = sample_evidence()
            evidence[field] = value
            with self.subTest(field=field), self.assertRaises(verifier.VerificationError):
                verifier.validate_evidence_shape(evidence)
        evidence = sample_evidence()
        evidence["externalProviderUsage"]["reservations"] = 0.0
        with self.assertRaises(verifier.VerificationError):
            verifier.validate_evidence_shape(evidence)

    def test_decoded_payload_markers_and_duplicate_members_fail_closed(self) -> None:
        decoded = verifier.strict_json(b'{"\\u006dodelOutput":"secret"}')
        self.assertFalse(verifier.payload_safe(decoded))
        with self.assertRaisesRegex(verifier.VerificationError, "DUPLICATE_MEMBER"):
            verifier.strict_json(b'{"x":1,"x":2}')

    def test_geometry_derivation_uses_integer_source_projection(self) -> None:
        derived = verifier.derive_view(1_024, 768, {
            "boundingBox": [200, 2_900, 9_800, 9_800],
        })
        self.assertEqual((20, 222, 1_004, 753), derived["pixel"])
        self.assertEqual((195, 2_890, 9_805, 9_805), derived["canonical"])
        self.assertEqual(2_400, derived["width"])
        self.assertEqual(1_295, derived["height"])

    def test_combined_encoded_bytes_above_thirty_mebibytes_fail_closed(self) -> None:
        evidence = sample_evidence()
        selected = evidence["runs"][0]["cases"][0]
        selected["staticEncodedBytes"] = 1
        selected["staticResource"]["encodedBytes"] = 1
        selected["inspectedResources"][0]["encodedBytes"] = 16 * 1024 * 1024
        selected["inspectedResources"][1]["encodedBytes"] = 16 * 1024 * 1024
        selected["inspectedEncodedBytes"] = 32 * 1024 * 1024

        with self.assertRaisesRegex(verifier.VerificationError, "RESOURCE_LIMIT_EXCEEDED"):
            verifier.validate_evidence_shape(evidence)

    def test_exact_five_hundred_and_four_hundred_ninety_nine_bps_boundaries(self) -> None:
        exact = threshold_cases(500)
        below = threshold_cases(499)

        exact_result = verifier.recompute_measurements(exact, copy.deepcopy(exact))
        below_result = verifier.recompute_measurements(below, copy.deepcopy(below))

        self.assertEqual(500, exact_result["aggregateLineRecallGainBps"])
        self.assertTrue(exact_result["reportedQualified"])
        self.assertEqual(499, below_result["aggregateLineRecallGainBps"])
        self.assertFalse(below_result["reportedQualified"])

    def test_verifier_contract_cannot_claim_a2(self) -> None:
        self.assertEqual("A1_PRODUCER_REPORT_CONSISTENCY_ONLY", verifier.ACCEPTED_ASSURANCE)
        self.assertEqual("NOT_ESTABLISHED", verifier.A2_DISPOSITION)


def metrics() -> dict[str, int]:
    return {
        "observationCount": 1, "expectedLines": 2, "matchedLines": 1,
        "predictedCharacters": 3, "characterErrors": 1, "hallucinationCases": 0,
        "expectedPrecedenceEdges": 0, "comparablePrecedenceEdges": 0,
        "correctPrecedenceEdges": 0, "expectedRepeatMemberships": 0,
        "observableRepeatMemberships": 0,
    }


def view(seed: str) -> dict[str, object]:
    return {
        "identity": "renderweave-view/1.0:" + seed * 64,
        "artifactId": seed * 64,
        "width": 1,
        "height": 1,
        "encodedBytes": 1,
    }


def case(index: int) -> dict[str, object]:
    return {
        "caseId": f"case-{index}",
        "caseIdentity": "renderweave-case/1.0:" + str(index) * 64,
        "partition": "DEV" if index < 4 else "HOLDOUT",
        "sourceWidth": 1,
        "sourceHeight": 1,
        "staticPlanIdentity": "renderweave-plan/1.0:" + "a" * 64,
        "requestIdentity": "renderweave-request/1.0:" + "b" * 64,
        "inspectedPlanIdentity": "renderweave-plan/1.0:" + "c" * 64,
        "staticViewCount": 1,
        "inspectedViewCount": 2,
        "staticDecodedPixels": 1,
        "inspectedDecodedPixels": 2,
        "staticEncodedBytes": 1,
        "inspectedEncodedBytes": 2,
        "staticAcquisitionMicros": 0,
        "inspectedAcquisitionMicros": 0,
        "staticResource": view("d"),
        "inspectedResources": [view("e"), view("f")],
        "staticView": metrics(),
        "inspected": metrics(),
    }


def sample_evidence() -> dict[str, object]:
    return {
        "contractVersion": verifier.EVIDENCE_VERSION,
        "assignmentIdentity": verifier.ASSIGNMENT_VERSION + ":" + "1" * 64,
        "transformIdentity": verifier.TRANSFORM_VERSION,
        "evaluationIdentity": verifier.EVALUATION_VERSION + ":" + "2" * 64,
        "corpusIdentity": "renderweave-corpus/1.0:" + "3" * 64,
        "annotationSetIdentity": "renderweave-annotation/1.0:" + "4" * 64,
        "capabilityIdentity": "capability",
        "acquisitionPolicyIdentity": "AcquisitionPolicy/1.0:" + "5" * 64,
        "runsCompleted": 2,
        "caseCount": 4,
        "devCases": 3,
        "holdoutCases": 1,
        "actualAcquisitions": 16,
        "deterministicCases": 4,
        "runs": [
            {"runOrdinal": 1, "cases": [case(index) for index in range(1, 5)]},
            {"runOrdinal": 2, "cases": [case(index) for index in range(1, 5)]},
        ],
        "predicates": {key: "PASS" for key in verifier.PREDICATE_FIELDS},
        "aggregateStaticLineRecallBps": 5_000,
        "aggregateInspectedLineRecallBps": 5_500,
        "aggregateLineRecallGainBps": 500,
        "aggregateStaticCharacterErrors": 4,
        "aggregateInspectedCharacterErrors": 3,
        "disposition": "QUALIFIED",
        "qualified": True,
        "reasonCode": "R5_PRODUCT_TRANSFORM_QUALIFIED",
        "externalProviderUsage": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
    }


def threshold_cases(gain_bps: int) -> list[dict[str, object]]:
    if gain_bps not in {499, 500}:
        raise ValueError("test fixture")
    improvements = [125, 125, 125, 125 if gain_bps == 500 else 124]
    result = [case(index) for index in range(1, 5)]
    for item, improvement in zip(result, improvements):
        item["staticView"].update({
            "expectedLines": 2_500,
            "matchedLines": 100,
            "characterErrors": 100,
            "hallucinationCases": 0,
        })
        item["inspected"].update({
            "expectedLines": 2_500,
            "matchedLines": 100 + improvement,
            "characterErrors": 50,
            "hallucinationCases": 0,
        })
    return result


if __name__ == "__main__":
    unittest.main()
