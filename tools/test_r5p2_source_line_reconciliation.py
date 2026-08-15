import json
import pathlib
import unittest

import r5p2_source_line_reconciliation as reconciliation


REPOSITORY = pathlib.Path(__file__).resolve().parents[1]
GOLDEN = REPOSITORY / (
    "renderweave-inference/src/test/resources/visual-eval/r5p2/"
    "source-line-reconciliation-golden-v1.json"
)


class R5P2SourceLineReconciliationTest(unittest.TestCase):
    def test_exact_threshold_boundaries(self):
        self.assertFalse(reconciliation.area_threshold_allows(4_999, 10_000))
        self.assertTrue(reconciliation.area_threshold_allows(5_000, 10_000))
        self.assertFalse(reconciliation.vertical_threshold_allows(7_999, 10_000))
        self.assertTrue(reconciliation.vertical_threshold_allows(8_000, 10_000))

    def test_replays_the_shared_java_python_golden(self):
        document = json.loads(GOLDEN.read_text(encoding="utf-8"))

        self.assertEqual(reconciliation.POLICY_IDENTITY, document["policyIdentity"])
        for vector in document["thresholds"]:
            with self.subTest(threshold=vector["thresholdId"]):
                operation = {
                    "area": reconciliation.area_threshold_allows,
                    "vertical": reconciliation.vertical_threshold_allows,
                }[vector["kind"]]
                self.assertEqual(vector["expected"], operation(
                    vector["numerator"], vector["denominator"]))
        for vector in document["projections"]:
            with self.subTest(projection=vector["projectionId"]):
                projected = reconciliation.project(
                    vector["observationId"], vector["sourceArtifactId"],
                    vector["viewOrdinal"], vector["lineOrdinal"],
                    vector["viewWidth"], vector["viewHeight"],
                    vector["sourceWidth"], vector["sourceHeight"],
                    reconciliation.PixelBox(*vector["sourceCrop"]),
                    reconciliation.PixelBox(*vector["viewLine"]),
                    vector["confidenceBps"], vector["text"],
                )
                self.assertEqual(reconciliation.SourceBox(*vector["expectedBox"]),
                                 projected.source_box)
                self.assertEqual(reconciliation.PixelDensity(*vector["expectedPixelDensity"]),
                                 projected.pixel_density)
        for pair in document["representativePairs"]:
            with self.subTest(pair=pair["pairId"]):
                self.assertEqual(
                    pair["expected"], reconciliation.prefers_representative(
                        reconciliation.projected_line(pair["candidate"]),
                        reconciliation.projected_line(pair["existing"])))
        for vector in document["invalidTexts"]:
            with self.subTest(invalid=vector["caseId"]):
                with self.assertRaises(reconciliation.ReconciliationError):
                    self.detailed_line(
                        vector["caseId"], 0, 0, reconciliation.SourceBox(0, 0, 10, 10),
                        8000, reconciliation.PixelDensity(1, 1), text=vector["text"])
        for case in document["cases"]:
            with self.subTest(case=case["caseId"]):
                lines = [reconciliation.projected_line(value) for value in case["lines"]]
                outcome = reconciliation.reconcile(lines)
                self.assertEqual(
                    case["expectedRepresentativeIds"],
                    [line.observation_id for line in outcome.representatives],
                )

    def test_projection_and_8000_vertical_boundary_are_exact(self):
        projected = reconciliation.project(
            "projected", "a" * 64, 0, 0, 200, 200, 1000, 1000,
            reconciliation.PixelBox(100, 200, 300, 400),
            reconciliation.PixelBox(0, 0, 100, 100), 9000, "route A",
        )
        self.assertEqual(reconciliation.SourceBox(1000, 2000, 2000, 3000), projected.source_box)

        reference = self.line("reference", 0, reconciliation.SourceBox(0, 0, 100, 5000))
        below = self.line("below", 1, reconciliation.SourceBox(0, 1001, 100, 6001))
        exact = self.line("exact", 1, reconciliation.SourceBox(0, 1000, 100, 6000))
        self.assertFalse(reconciliation.same_source_line_candidate(reference, below))
        self.assertTrue(reconciliation.same_source_line_candidate(reference, exact))

        center_reference = self.line(
            "center-reference", 0, reconciliation.SourceBox(50, 0, 150, 100))
        left_closed = self.line(
            "left-closed", 1, reconciliation.SourceBox(40, 0, 60, 100))
        right_open = self.line(
            "right-open", 1, reconciliation.SourceBox(140, 0, 160, 100))
        self.assertTrue(reconciliation.same_source_line_candidate(center_reference, left_closed))
        self.assertFalse(reconciliation.same_source_line_candidate(center_reference, right_open))

    def test_representative_order_handles_cross_product_overflow(self):
        maximum = (1 << 63) - 1
        existing = self.detailed_line(
            "existing", 4, 9, reconciliation.SourceBox(0, 0, 100, 100), 8000,
            reconciliation.PixelDensity(maximum - 1, maximum))
        density = self.detailed_line(
            "density", 5, 8, existing.source_box, 1,
            reconciliation.PixelDensity(maximum, maximum - 1))
        self.assertTrue(reconciliation.prefers_representative(density, existing))
        self.assertTrue(reconciliation.prefers_representative(
            self.detailed_line("confidence", 5, 8, existing.source_box, 8001,
                               existing.pixel_density), existing))
        self.assertTrue(reconciliation.prefers_representative(
            self.detailed_line("smaller", 5, 8, reconciliation.SourceBox(0, 0, 99, 100),
                               8000, existing.pixel_density), existing))
        self.assertTrue(reconciliation.prefers_representative(
            self.detailed_line("lower-view", 3, 99, existing.source_box, 8000,
                               existing.pixel_density), existing))
        self.assertTrue(reconciliation.prefers_representative(
            self.detailed_line("lower-line", 4, 8, existing.source_box, 8000,
                               existing.pixel_density), existing))

    def test_canonical_text_and_unicode_scalar_order(self):
        invalid = ("e\u0301", "two  spaces", "non\u00a0breaking", "line\nfeed")
        for index, text in enumerate(invalid):
            with self.subTest(text=repr(text)):
                with self.assertRaises(reconciliation.ReconciliationError):
                    reconciliation.ProjectedLine(
                        f"invalid-{index}", "a" * 64,
                        reconciliation.SourceBox(0, 0, 10, 10), 8000, text, 0, 0,
                        reconciliation.PixelDensity(1, 1))
        supplementary = self.detailed_line(
            "supplementary", 0, 1, reconciliation.SourceBox(0, 0, 10, 10),
            8000, reconciliation.PixelDensity(1, 1), text="\U00010000")
        private_use = self.detailed_line(
            "private-use", 0, 0, reconciliation.SourceBox(0, 0, 10, 10),
            8000, reconciliation.PixelDensity(1, 1), text="\ue000")
        self.assertEqual(
            ["private-use", "supplementary"],
            [line.observation_id for line in
             reconciliation.reconcile([supplementary, private_use]).representatives])

    def line(self, identity, view, box):
        return reconciliation.ProjectedLine(
            identity, "a" * 64, box, 9000, identity, view, 0,
            reconciliation.PixelDensity(1, 1),
        )

    def detailed_line(self, identity, view, ordinal, box, confidence, density, text=None):
        return reconciliation.ProjectedLine(
            identity, "a" * 64, box, confidence, text or identity, view, ordinal, density,
        )


if __name__ == "__main__":
    unittest.main()
