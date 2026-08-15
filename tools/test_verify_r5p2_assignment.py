import pathlib
import unittest

import verify_r5p2_assignment as verifier


REPOSITORY = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = "renderweave-inference/src/main/resources/visual-eval/r5p2/assignment-v1.json"
IDENTITY_LOCK = "renderweave-inference/src/main/resources/visual-eval/v2/identity-lock.json"
OLD_R5P = "renderweave-inference/src/main/resources/visual-eval/r5p/paired-view-assignment-v1.json"


class VerifyR5P2AssignmentTest(unittest.TestCase):
    def test_independently_verifies_selection_fixtures_access_and_zero_provider(self):
        summary = verifier.verify(REPOSITORY)

        self.assertEqual("R5P2_ASSIGNMENT_FROZEN", summary["terminalCode"])
        self.assertEqual(8, summary["historicalDiagnosticCount"])
        self.assertEqual(4, summary["sealedConfirmationCount"])
        self.assertEqual(
            ["weather-forecast-v3", "warehouse-inventory-v2",
             "event-agenda-v4", "product-catalog-v5"],
            summary["confirmationCaseIds"],
        )
        self.assertEqual(12, summary["fixtureCount"])
        self.assertEqual(4, summary["freshFixtureCount"])
        self.assertEqual(0, summary["preFreezeGoldReads"])
        self.assertEqual(0, summary["preFreezeMetricReads"])
        self.assertEqual(0, summary["providerAttempts"])
        self.assertEqual(0, summary["apiKeyReads"])
        self.assertEqual(
            "renderweave-r5p2-frozen-assignment/1.0:"
            "74ec12bc198db1f9597391102a44676918b4c6122851a7b50d338446fe5f7cbd",
            summary["assignmentIdentity"],
        )
        self.assertEqual(
            "renderweave-r5p2-repository-raster-fixture-set/1.0:"
            "3e425016eb01d824391deaa91059e13ef0230f3a444a004e2a28504f1d1e7d92",
            summary["fixtureSetIdentity"],
        )
        self.assertEqual(
            "renderweave-r5p2-thresholds/1.0:"
            "ab91362c4738a5feaadc67053604ddfefa861b16012f0523a088e3964430a8e1",
            summary["thresholdIdentity"],
        )
        self.assertEqual(
            "renderweave-r5p2-paired-product-view-evaluation/1.0:"
            "b5a9fb0d38e9b4e2d06b4be93d272bb6704d6ef56d81fa18f1a593d22a946558",
            summary["evaluationIdentity"],
        )

    def test_rejects_forbidden_selection_inputs_prior_overlap_and_mutation(self):
        identity_lock = (REPOSITORY / IDENTITY_LOCK).read_bytes()
        poisoned = identity_lock.replace(
            b'"caseId" : "weather-forecast-v3"',
            b'"caseId" : "weather-forecast-v3", "goldText" : "forbidden"',
        )
        with self.assertRaisesRegex(verifier.AssignmentError,
                                    "R5P2_SELECTION_FORBIDDEN_METADATA"):
            verifier.recompute_selection(poisoned)

        old_r5p = (REPOSITORY / OLD_R5P).read_bytes()
        overlapped = old_r5p.replace(
            b'"caseAssignments": [',
            b'"caseAssignments": [{"caseId":"weather-forecast-v3"},',
        )
        with self.assertRaisesRegex(verifier.AssignmentError,
                                    "R5P2_ASSIGNMENT_PRIOR_PAIRED_OVERLAP"):
            verifier.verify(REPOSITORY, {OLD_R5P: overlapped})

        manifest = (REPOSITORY / MANIFEST).read_bytes()
        mutated = manifest.replace(
            b'"freshRawFixtureGenerations": 4',
            b'"freshRawFixtureGenerations": 5',
        )
        with self.assertRaisesRegex(verifier.AssignmentError,
                                    "R5P2_ASSIGNMENT_ACCESS_STATE_DRIFT"):
            verifier.verify(REPOSITORY, {MANIFEST: mutated})


if __name__ == "__main__":
    unittest.main()
