import copy
import json
import pathlib
import tempfile
import unittest

import verify_r5p_harness as verifier


REPOSITORY = pathlib.Path(__file__).resolve().parents[1]
EVIDENCE = REPOSITORY / (
    "renderweave-inference/src/test/resources/visual-eval/r5p/"
    "harness-evidence-v1.json"
)
PLAN_IDENTITY = (
    "renderweave-r5p-static-plan/1.0:"
    "27df2fe62b4cc47a4f5dc6876feb0a79ec40bf4d6a46efe91f1c15ac2ecd140b"
)
EVIDENCE_IDENTITY = (
    "renderweave-r5p-harness-evidence/1.0:"
    "d4236af62e32e8af8a0437dc531ec8007e16cfc094927b5b89e69769f4ee8c1e"
)


class ProductViewHarnessVerifierTest(unittest.TestCase):
    def test_reconstructs_exact_plan_and_acquisition_coverage(self):
        result = verifier.verify(EVIDENCE)

        self.assertEqual("PASS", result["result"])
        self.assertEqual(
            "A2_INDEPENDENT_PLAN_ACQUISITION_RECONSTRUCTION", result["assurance"]
        )
        self.assertEqual(2, result["runCount"])
        self.assertEqual(5, result["plannedViewCount"])
        self.assertEqual(5, result["acquiredViewCount"])
        self.assertEqual(PLAN_IDENTITY, result["staticPlanIdentity"])
        self.assertEqual(EVIDENCE_IDENTITY, result["evidenceIdentity"])
        self.assertEqual(0, result["providerAttempts"])
        self.assertEqual(0, result["apiKeyReads"])
        self.assertEqual("R5P_HARNESS_CONFORMANT", result["disposition"])

    def test_rejects_missing_reordered_replaced_dimension_and_extra_acquisitions(self):
        mutations = (
            (
                "missing",
                lambda run: run["acquisitionTrace"].pop(),
                "R5P_HARNESS_ACQUISITION_COUNT_INVALID",
            ),
            (
                "reordered",
                lambda run: run["acquisitionTrace"].__setitem__(
                    slice(0, 2), reversed(run["acquisitionTrace"][:2])
                ),
                "R5P_HARNESS_ACQUISITION_TRACE_INVALID",
            ),
            (
                "replaced-digest",
                lambda run: run["acquisitionTrace"][0].__setitem__(
                    "encodedSha256", "0" * 64
                ),
                "R5P_HARNESS_ACQUISITION_TRACE_INVALID",
            ),
            (
                "dimension",
                lambda run: run["acquisitionTrace"][0].__setitem__("width", 767),
                "R5P_HARNESS_ACQUISITION_TRACE_INVALID",
            ),
            (
                "extra",
                lambda run: run["acquisitionTrace"].append(
                    copy.deepcopy(run["acquisitionTrace"][-1])
                ),
                "R5P_HARNESS_ACQUISITION_COUNT_INVALID",
            ),
        )
        for name, mutate, code in mutations:
            with self.subTest(name=name):
                document = self.document()
                mutate(document["runs"][0])
                self.assertDocumentCode(code, document)

    def test_rejects_external_usage_api_reads_and_two_run_drift(self):
        mutations = (
            (
                "top-provider",
                lambda document: document["externalProviderUsage"].__setitem__(
                    "attempts", 1
                ),
                "R5P_HARNESS_PROVIDER_USAGE_NONZERO",
            ),
            (
                "run-provider",
                lambda document: document["runs"][0][
                    "externalProviderUsage"
                ].__setitem__("reservations", 1),
                "R5P_HARNESS_PROVIDER_USAGE_NONZERO",
            ),
            (
                "api-read",
                lambda document: document["runs"][0].__setitem__("apiKeyReads", 1),
                "R5P_HARNESS_API_KEY_READ_NONZERO",
            ),
            (
                "run-drift",
                lambda document: document["runs"][1]["normalizationProvenance"][
                    0
                ].__setitem__("inputFingerprint", "f" * 64),
                "R5P_HARNESS_REPEATABILITY_DRIFT",
            ),
        )
        for name, mutate, code in mutations:
            with self.subTest(name=name):
                document = self.document()
                mutate(document)
                self.assertDocumentCode(code, document)

    def test_rejects_unknown_duplicate_trailing_boolean_and_float_json(self):
        document = self.document()
        document["unexpected"] = "field"
        self.assertDocumentCode("R5P_HARNESS_FIELDS_INVALID", document)

        raw = EVIDENCE.read_text(encoding="utf-8")
        duplicate = raw.replace(
            '"apiKeyReads":0,', '"apiKeyReads":0,"apiKeyReads":0,', 1
        )
        self.assertRawCode("R5P_HARNESS_JSON_INVALID", duplicate)
        self.assertRawCode("R5P_HARNESS_JSON_INVALID", raw + "{}\n")

        document = self.document()
        document["runs"][0]["plannedViewCount"] = True
        self.assertDocumentCode("R5P_HARNESS_COUNT_INVALID", document)

        raw_float = raw.replace('"blobReads":1', '"blobReads":1.0', 1)
        self.assertRawCode("R5P_HARNESS_JSON_INVALID", raw_float)

    def document(self):
        return json.loads(EVIDENCE.read_text(encoding="utf-8"))

    def assertDocumentCode(self, code, document):
        self.assertRawCode(
            code,
            json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n",
        )

    def assertRawCode(self, code, raw):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "evidence.json"
            path.write_text(raw, encoding="utf-8", newline="\n")
            with self.assertRaises(verifier.VerificationError) as caught:
                verifier.verify(path)
        self.assertEqual(code, str(caught.exception))


if __name__ == "__main__":
    unittest.main()
