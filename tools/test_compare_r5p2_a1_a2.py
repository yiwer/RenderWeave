import copy
import pathlib
import unittest

import compare_r5p2_a1_a2 as comparison


class R5P2A1A2ComparisonTest(unittest.TestCase):
    def test_exact_inputs_publish_only_a_sealed_comparison_not_a_formal_terminal(self) -> None:
        producer, independent = comparison.synthetic_inputs_for_tests()

        result = comparison.compare_documents(producer, independent)
        envelope = comparison.envelope(result)

        self.assertTrue(result["comparisonExact"])
        self.assertTrue(result["measurementValid"])
        self.assertEqual("R5P2_A1_A2_EXACT", result["terminalCode"])
        self.assertEqual("R5P2_PAIRED_VIEW_NOT_QUALIFIED", result["candidateTerminal"])
        self.assertRegex(
            envelope["comparisonIdentity"],
            r"^renderweave-r5p2-a1-a2-comparison/1\.0:[0-9a-f]{64}$",
        )

    def test_identity_and_accounting_mismatches_precede_metrics(self) -> None:
        producer, independent = comparison.synthetic_inputs_for_tests()
        independent["evidence"]["stageIdentities"]["adapterIdentity"] = "drift"
        independent["evidence"]["accounting"]["artifactViews"] = 1
        independent["evidence"]["cases"][0]["baseline"]["metrics"]["matchedLines"] = 1

        result = comparison.compare_documents(producer, independent)

        self.assertFalse(result["comparisonExact"])
        self.assertEqual("R5P2_MEASUREMENT_INVALID", result["terminalCode"])
        self.assertEqual(
            "R5P2_A1_A2_STAGE_IDENTITIES_MISMATCH", result["firstMismatchStage"]
        )

        producer, independent = comparison.synthetic_inputs_for_tests()
        independent["evidence"]["accounting"]["artifactViews"] = 1
        independent["evidence"]["cases"][0]["baseline"]["metrics"]["matchedLines"] = 1
        result = comparison.compare_documents(producer, independent)
        self.assertEqual(
            "R5P2_A1_A2_ACCOUNTING_MISMATCH", result["firstMismatchStage"]
        )

    def test_metric_mismatch_has_payload_safe_case_stage_only(self) -> None:
        producer, independent = comparison.synthetic_inputs_for_tests()
        independent["evidence"]["cases"][0]["baseline"]["metrics"]["matchedLines"] = 1

        result = comparison.compare_documents(producer, independent)

        self.assertEqual(
            "R5P2_A1_A2_CASE_METRICS_MISMATCH", result["firstMismatchStage"]
        )
        self.assertNotIn("transit-board-v3", str(result))

    def test_comparison_source_reads_sealed_a2_before_producer(self) -> None:
        source = pathlib.Path(comparison.__file__).read_text(encoding="utf-8")
        body = source[source.index("def compare_files("):source.index("def main(")]
        self.assertLess(body.index("read_a2("), body.index("read_producer("))

        runner = pathlib.Path(comparison.__file__).with_name(
            "run-r5p2-independent-a2.ps1"
        ).read_text(encoding="utf-8")
        self.assertNotIn("Get-Content -LiteralPath $resolvedProducerReport", runner)
        self.assertLess(
            runner.index("$a2Raw = Get-Content"),
            runner.index("r5p2-post-seal-exact-a1-a2-comparison"),
        )


if __name__ == "__main__":
    unittest.main()
