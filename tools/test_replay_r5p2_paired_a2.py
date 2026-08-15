import copy
import pathlib
import unittest

import replay_r5p2_paired_a2 as replay


class R5P2IndependentReplayTest(unittest.TestCase):
    def test_quality_candidate_is_separate_from_replay_completion(self) -> None:
        self.assertEqual(
            "R5P2_PAIRED_VIEW_NOT_QUALIFIED",
            replay.candidate_terminal(True, False, False),
        )
        self.assertEqual(
            "R5P2_ACTION_IMPLEMENTATION_ALLOWED",
            replay.candidate_terminal(True, True, True),
        )
        self.assertEqual(
            "R5P2_MEASUREMENT_INVALID",
            replay.candidate_terminal(False, True, True),
        )

    def test_metric_scoring_and_pair_direction_match_the_frozen_contract(self) -> None:
        gold = {
            "lines": [{"lineId": "line-title", "text": "Alpha", "box": [0, 0, 5000, 2000]}],
            "regionIds": ["title"],
            "precedenceEdges": [],
            "repeatGroups": [],
        }
        baseline = replay.score_case(gold, [])
        successor = replay.score_case(
            gold, [{"text": "Alpha", "box": [0, 0, 5000, 2000]}]
        )
        pair = replay.pair_metrics(baseline, successor)
        self.assertEqual(1, pair["matchedLineGain"])
        self.assertGreater(pair["characterErrorReduction"], 0)
        self.assertTrue(pair["targetImproved"])
        self.assertTrue(pair["hallucinationNonIncrease"])

    def test_payload_safe_evidence_is_strict_and_content_addressed(self) -> None:
        evidence = replay.synthetic_evidence_for_tests()
        validated = replay.validate_evidence(copy.deepcopy(evidence))
        envelope = replay.envelope(validated)
        self.assertRegex(
            envelope["evidenceIdentity"],
            r"^renderweave-r5p2-independent-replay-evidence/1\.0:[0-9a-f]{64}$",
        )
        mutated = copy.deepcopy(evidence)
        mutated["accounting"]["branchAcquisitionProcesses"] = 47
        with self.assertRaisesRegex(
            replay.VerificationError, "R5P2_A2_EVIDENCE_ACCOUNTING_INVALID"
        ):
            replay.validate_evidence(mutated)

    def test_verifier_source_cannot_import_private_adapter_or_producer(self) -> None:
        source = pathlib.Path(replay.__file__).read_text(encoding="utf-8")
        for forbidden in (
            "._artifact", "._engine", "._preprocess", "importlib.util",
            "R5P2PairedProductViewEvaluation", "paired-product-view-report",
            "import socket", "import requests", "import urllib", "http://", "https://",
        ):
            self.assertNotIn(forbidden, source)

    def test_resource_counts_are_recomputed_from_the_complete_plan(self) -> None:
        views = [
            {"encodedBytes": 10, "width": 100, "height": 50},
            {"encodedBytes": 20, "width": 64, "height": 32},
        ]
        raw = {
            "totalViews": 2, "inspectedViews": 1, "totalEncodedBytes": 30,
            "totalPixels": 7_048, "inspectedPixels": 2_048,
            "additionalVisualTokens": 4, "localTransformMillis": 3,
        }

        validated = replay.validate_resources("SUCCESSOR", views, raw, 1)

        self.assertEqual(0, validated["acquisitionMicros"])
        mutated = copy.deepcopy(raw)
        mutated["totalPixels"] += 1
        with self.assertRaisesRegex(
            replay.VerificationError, "R5P2_A2_RESOURCE_ACCOUNTING_INVALID"
        ):
            replay.validate_resources("SUCCESSOR", views, mutated, 1)


if __name__ == "__main__":
    unittest.main()
