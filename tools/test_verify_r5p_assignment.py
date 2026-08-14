import json
import pathlib
import tempfile
import unittest

import verify_r5p_assignment as verifier


REPOSITORY = pathlib.Path(__file__).resolve().parents[1]
ASSIGNMENT = REPOSITORY / (
    "renderweave-inference/src/main/resources/visual-eval/r5p/"
    "paired-view-assignment-v1.json"
)
ASSIGNMENT_IDENTITY = (
    "renderweave-r5p-paired-view-assignment/1.0:"
    "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8"
)
EVALUATION_IDENTITY = (
    "renderweave-r5p-paired-view-evaluation/1.0:"
    "c8ad69263640ca49cd93ca24c6b558c6f913ff89a40c84052634c7cd79f66b65"
)


class R5PairedProductViewAssignmentVerifierTest(unittest.TestCase):
    def test_independently_audits_frozen_assignment_and_repository_inputs(self):
        result = verifier.verify(ASSIGNMENT, REPOSITORY)

        self.assertEqual("PASS", result["result"])
        self.assertEqual(
            "A2_INDEPENDENT_ASSIGNMENT_ACCESS_AUDIT", result["assurance"]
        )
        self.assertEqual(ASSIGNMENT_IDENTITY, result["assignmentIdentity"])
        self.assertEqual(EVALUATION_IDENTITY, result["evaluationIdentity"])
        self.assertEqual(4, result["seenDiagnosticCount"])
        self.assertEqual(4, result["sealedConfirmationCount"])
        self.assertEqual(3, result["confirmationDevCount"])
        self.assertEqual(1, result["confirmationHoldoutCount"])
        self.assertEqual(8, result["verifiedRawFixtureCount"])
        self.assertEqual(5, result["verifiedSourceIdentityCount"])
        self.assertEqual(0, result["qualityResultsRead"])
        self.assertEqual(0, result["providerAttempts"])
        self.assertEqual(0, result["apiKeyReads"])
        self.assertEqual("R5P_ASSIGNMENT_FROZEN", result["disposition"])

    def test_rejects_overlap_partition_region_and_threshold_tampering(self):
        mutations = (
            (
                "overlap",
                lambda value: value["caseAssignments"][4].__setitem__(
                    "caseId", "transit-board-v3"
                ),
                "R5P_ASSIGNMENT_CASE_SET_DRIFT",
            ),
            (
                "partition",
                lambda value: value["caseAssignments"][7].__setitem__(
                    "sourcePartition", "DEV"
                ),
                "R5P_ASSIGNMENT_PARTITION_DRIFT",
            ),
            (
                "region",
                lambda value: value["caseAssignments"][0]["regions"][0][
                    "boundingBox"
                ].__setitem__(0, 201),
                "R5P_ASSIGNMENT_REGION_DRIFT",
            ),
            (
                "threshold",
                lambda value: value["thresholds"].__setitem__(
                    "minimumConfirmationLineRecallGainBps", 499
                ),
                "R5P_ASSIGNMENT_THRESHOLD_DRIFT",
            ),
        )
        for name, mutate, code in mutations:
            with self.subTest(name=name):
                document = self.document()
                mutate(document)
                self.assertDocumentCode(code, document)

    def test_rejects_fixture_source_runtime_and_usage_tampering(self):
        mutations = (
            (
                "fixture",
                lambda value: value["caseAssignments"][0].__setitem__(
                    "rawFixtureSha256", "0" * 64
                ),
                "R5P_ASSIGNMENT_FIXTURE_DRIFT",
            ),
            (
                "source",
                lambda value: value["identities"].__setitem__(
                    "actionModuleSourceSha256", "0" * 64
                ),
                "R5P_ASSIGNMENT_IDENTITY_DRIFT",
            ),
            (
                "runtime",
                lambda value: value["runtimeComponents"].__setitem__(
                    "python", "3.12.12"
                ),
                "R5P_ASSIGNMENT_RUNTIME_DRIFT",
            ),
            (
                "provider",
                lambda value: value["externalProviderUsage"].__setitem__(
                    "attempts", 1
                ),
                "R5P_ASSIGNMENT_PROVIDER_USAGE_NONZERO",
            ),
            (
                "api-key",
                lambda value: value.__setitem__("apiKeyReads", 1),
                "R5P_ASSIGNMENT_API_KEY_READ_NONZERO",
            ),
        )
        for name, mutate, code in mutations:
            with self.subTest(name=name):
                document = self.document()
                mutate(document)
                self.assertDocumentCode(code, document)

    def test_rejects_result_fields_unknown_duplicate_trailing_boolean_and_float(self):
        document = self.document()
        document["observedResult"] = "PASS"
        self.assertDocumentCode("R5P_ASSIGNMENT_FIELDS_INVALID", document)

        raw = ASSIGNMENT.read_text(encoding="utf-8")
        duplicate = raw.replace(
            '"apiKeyReads": 0,', '"apiKeyReads": 0,"apiKeyReads": 0,', 1
        )
        self.assertRawCode("R5P_ASSIGNMENT_JSON_INVALID", duplicate)
        self.assertRawCode("R5P_ASSIGNMENT_JSON_INVALID", raw + "{}\n")

        document = self.document()
        document["apiKeyReads"] = False
        self.assertDocumentCode("R5P_ASSIGNMENT_API_KEY_READ_INVALID", document)

        raw_float = raw.replace(
            '"minimumConfirmationLineRecallGainBps": 500',
            '"minimumConfirmationLineRecallGainBps": 500.0',
            1,
        )
        self.assertRawCode("R5P_ASSIGNMENT_JSON_INVALID", raw_float)

    def document(self):
        return json.loads(ASSIGNMENT.read_text(encoding="utf-8"))

    def assertDocumentCode(self, code, document):
        self.assertRawCode(
            code,
            json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n",
        )

    def assertRawCode(self, code, raw):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "assignment.json"
            path.write_text(raw, encoding="utf-8", newline="\n")
            with self.assertRaises(verifier.VerificationError) as caught:
                verifier.verify(path, REPOSITORY)
        self.assertEqual(code, str(caught.exception))


if __name__ == "__main__":
    unittest.main()
