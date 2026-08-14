#!/usr/bin/env python3
"""Unit and adversarial tests for the independent R5P actual replay."""

from __future__ import annotations

import importlib.util
import json
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).with_name("replay_r5p_paired_a2.py")
SPEC = importlib.util.spec_from_file_location("renderweave_r5p_a2", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("replay_r5p_paired_a2.py cannot be loaded")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class R5PIndependentReplayTest(unittest.TestCase):
    def test_metric_golden_matches_center_containment_edit_and_structure_rules(self) -> None:
        gold = {
            "lines": [
                {"lineId": "line-title", "text": "AB C", "box": [0, 0, 5000, 2000]},
                {"lineId": "line-item-1", "text": "D", "box": [0, 2000, 5000, 4000]},
            ],
            "regionIds": ["title", "item-1"],
            "precedenceEdges": [{"beforeRegionId": "title", "afterRegionId": "item-1"}],
            "repeatGroups": [{"groupRegionId": "group", "items": [
                {"itemRegionId": "item-1", "memberRegionIds": ["item-1"]}
            ]}],
        }
        actual = [
            {"text": "AB X", "box": [100, 100, 4900, 1900], "confidenceBps": 9000},
            {"text": "D", "box": [100, 2100, 4900, 3900], "confidenceBps": 8000},
        ]

        measured = VERIFIER.score_case(gold, actual)

        self.assertEqual(2, measured["matchedLines"])
        self.assertEqual(10_000, measured["lineRecallBps"])
        self.assertEqual(1, measured["characterErrors"])
        self.assertEqual(0, measured["hallucinationCases"])
        self.assertEqual(10_000, measured["orderAccuracyBps"])
        self.assertEqual(10_000, measured["repeatRecallBps"])

    def test_quality_truth_table_keeps_measurement_and_quality_failure_distinct(self) -> None:
        passed = VERIFIER.terminal_for(True, True, True)
        quality_failed = VERIFIER.terminal_for(True, False, True)
        invalid = VERIFIER.terminal_for(False, True, True)

        self.assertEqual("R5P_ACTION_IMPLEMENTATION_ALLOWED", passed)
        self.assertEqual("R5P_PAIRED_VIEW_NOT_QUALIFIED", quality_failed)
        self.assertEqual("R5P_MEASUREMENT_INVALID", invalid)

    def test_confirmation_threshold_boundaries_are_exact(self) -> None:
        summary = VERIFIER.synthetic_evidence_for_tests()["confirmationSummary"]
        summary.update({
            "baselineLineRecallBps": 5000, "successorLineRecallBps": 5500,
            "lineRecallGainBps": 500, "baselineCharacterErrors": 10,
            "successorCharacterErrors": 9, "characterErrorReduction": 1,
            "baselineOrderAccuracyBps": 9000, "successorOrderAccuracyBps": 8900,
            "orderAccuracyDeltaBps": -100, "baselineRepeatRecallBps": 9000,
            "successorRepeatRecallBps": 8900, "repeatRecallDeltaBps": -100,
        })
        self.assertTrue(VERIFIER._threshold_pass(summary, True))
        for field, failing in (
                ("lineRecallGainBps", 499), ("characterErrorReduction", 0),
                ("orderAccuracyDeltaBps", -101), ("repeatRecallDeltaBps", -101)):
            changed = dict(summary)
            changed[field] = failing
            self.assertFalse(VERIFIER._threshold_pass(changed, True), field)

    def test_strict_json_rejects_unknown_duplicate_trailing_coercion_and_overflow(self) -> None:
        valid = VERIFIER.synthetic_protocol_for_tests()
        encoded = json.dumps(valid, separators=(",", ":"))
        mutations = [
            encoded.replace("}", ',"unknown":0}'),
            encoded.replace('"runs":[]', '"runs":[],"runs":[]'),
            encoded + "{}",
            encoded.replace('"runs":[]', '"runs":false'),
            encoded.replace('"runs":[]', '"runs":0.0'),
            encoded.replace('"runs":[]', '"runs":"[]"'),
            encoded.replace('"runs":[]', '"runs":[2147483648]'),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with self.assertRaises(VERIFIER.VerificationError):
                    VERIFIER.read_protocol_bytes(mutation.encode("utf-8"))

    def test_payload_safe_evidence_rejects_decoded_markers(self) -> None:
        evidence = VERIFIER.synthetic_evidence_for_tests()
        evidence["cases"][0]["caseId"] = "data:image/png;base64,AAAA"
        with self.assertRaises(VERIFIER.VerificationError):
            VERIFIER.validate_evidence(evidence)


if __name__ == "__main__":
    unittest.main()
