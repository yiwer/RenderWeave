import json
import pathlib
import shutil
import tempfile
import unittest

import verify_r5p2_authority as verifier


REPOSITORY = pathlib.Path(__file__).resolve().parents[1]
AUTHORITY = pathlib.Path(
    "renderweave-inference/src/main/resources/visual-eval/r5p2/authority-v1.json"
)
SPEC = pathlib.Path(
    "specs/changes/20260815-r5p2-source-line-reconciliation-successor.md"
)
HISTORICAL_SPEC = pathlib.Path(
    "specs/changes/20260815-r5-paired-product-view-successor.md"
)
CORPUS_LOCK = pathlib.Path(
    "renderweave-inference/src/main/resources/visual-eval/v2/identity-lock.json"
)


class R5P2AuthorityVerifierTest(unittest.TestCase):
    def test_reconstructs_the_new_lock_without_the_java_decision_engine(self):
        result = verifier.verify(REPOSITORY)

        self.assertEqual("PASS", result["result"])
        self.assertEqual("A2_INDEPENDENT_READ_ONLY_RECONSTRUCTION", result["assurance"])
        self.assertEqual("R5P2_AUTHORITY_LOCKED", result["disposition"])
        self.assertEqual("R5P_MEASUREMENT_INVALID", result["historicalTerminal"])
        self.assertEqual(6, result["closedHistoricalTickets"])
        self.assertEqual(0, result["providerAttempts"])
        self.assertEqual(0, result["providerReservations"])
        self.assertEqual(0, result["providerCostMicrosCny"])
        self.assertEqual(0, result["apiKeyReads"])
        self.assertRegex(
            result["authorityIdentity"],
            r"^renderweave-r5p2-authority/1\.0:[0-9a-f]{64}$",
        )

    def test_rejects_spec_history_corpus_and_usage_tampering(self):
        mutations = (
            ("historicalEffectiveTerminal", "R5P_PAIRED_VIEW_NOT_QUALIFIED",
             "R5P2_HISTORICAL_TERMINAL_DRIFT"),
            ("historicalProducerReportSha256", "0" * 64,
             "R5P2_HISTORICAL_EVIDENCE_DRIFT"),
            ("historicalIndependentEvidenceSha256", "0" * 64,
             "R5P2_HISTORICAL_EVIDENCE_DRIFT"),
            ("corpusIdentityLockSha256", "0" * 64, "R5P2_CORPUS_LOCK_DRIFT"),
            ("apiKeyReads", 1, "R5P2_PROVIDER_BOUNDARY_VIOLATED"),
        )
        for field, value, code in mutations:
            with self.subTest(field=field), self.sandbox() as root:
                path = root / AUTHORITY
                document = json.loads(path.read_text(encoding="utf-8"))
                document[field] = value
                path.write_text(
                    json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n",
                    encoding="utf-8",
                    newline="\n",
                )
                self.assertCode(code, lambda: verifier.verify(root))

        with self.sandbox() as root:
            path = root / SPEC
            path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            self.assertCode("R5P2_APPROVED_SPEC_DRIFT", lambda: verifier.verify(root))

    def test_rejects_duplicate_and_trailing_authority_json(self):
        with self.sandbox() as root:
            path = root / AUTHORITY
            raw = path.read_text(encoding="utf-8")
            path.write_text(
                raw.replace(
                    '"authorityVersion": "renderweave-r5p2-authority/1.0",',
                    '"authorityVersion": "renderweave-r5p2-authority/1.0",\n'
                    '  "authorityVersion": "renderweave-r5p2-authority/1.0",',
                ),
                encoding="utf-8",
                newline="\n",
            )
            self.assertCode("R5P2_AUTHORITY_JSON_INVALID", lambda: verifier.verify(root))

        with self.sandbox() as root:
            path = root / AUTHORITY
            path.write_text(path.read_text(encoding="utf-8") + "{}\n", encoding="utf-8")
            self.assertCode("R5P2_AUTHORITY_JSON_INVALID", lambda: verifier.verify(root))

    def assertCode(self, code, action):
        with self.assertRaises(verifier.VerificationError) as caught:
            action()
        self.assertEqual(code, str(caught.exception))

    def sandbox(self):
        temporary = tempfile.TemporaryDirectory()
        root = pathlib.Path(temporary.name)
        for relative in (AUTHORITY, SPEC, HISTORICAL_SPEC, CORPUS_LOCK):
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / relative, target)

        class Sandbox:
            def __enter__(self):
                return root

            def __exit__(self, exc_type, exc_value, traceback):
                temporary.cleanup()

        return Sandbox()


if __name__ == "__main__":
    unittest.main()
