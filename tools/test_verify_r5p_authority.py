import json
import pathlib
import shutil
import tempfile
import unittest

import verify_r5p_authority as verifier


REPOSITORY = pathlib.Path(__file__).resolve().parents[1]
AUTHORITY = pathlib.Path(
    "renderweave-inference/src/main/resources/visual-eval/r5p/"
    "paired-product-view-authority-v1.json"
)
OLD_AUTHORITY = pathlib.Path(
    "renderweave-inference/src/main/resources/visual-eval/r5/"
    "product-transform-authority-v2.json"
)
OLD_RUNNER = pathlib.Path(
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/"
    "R5ProductTransformEvaluation.java"
)
SPEC = pathlib.Path("specs/changes/20260815-r5-paired-product-view-successor.md")


class R5PairedProductViewAuthorityVerifierTest(unittest.TestCase):
    def test_reconstructs_the_exact_lock_without_the_java_decision_engine(self):
        result = verifier.verify(REPOSITORY)

        self.assertEqual("PASS", result["result"])
        self.assertEqual("A2_INDEPENDENT_READ_ONLY_RECONSTRUCTION", result["assurance"])
        self.assertEqual("R5P_AUTHORITY_LOCKED", result["disposition"])
        self.assertEqual(0, result["providerAttempts"])
        self.assertEqual(0, result["providerReservations"])
        self.assertEqual(0, result["providerCostMicrosCny"])
        self.assertEqual(0, result["apiKeyReads"])
        self.assertEqual(
            "spec-sha256:650ad1632347592d1fc34325983744c02563b43d8a565b9b1cd24e1a805a892a",
            result["approvedSpecIdentity"],
        )
        self.assertRegex(
            result["authorityIdentity"],
            r"^renderweave-r5p-authority/1\.0:[0-9a-f]{64}$",
        )

    def test_rejects_state_identity_and_runner_tampering(self):
        mutations = (
            ("n704Decision", "PASS", "R5P_N7_AUTHORITY_STATE_DRIFT"),
            ("n704AuthorizationStatus", "OPEN", "R5P_N7_AUTHORITY_STATE_DRIFT"),
            ("n705TicketId", "R5P-renamed-05", "R5P_N7_AUTHORITY_STATE_DRIFT"),
            ("oldR5AuthoritySha256", "0" * 64, "R5P_OLD_R5_AUTHORITY_DRIFT"),
            ("oldR5RunnerDisposition", "OPEN", "R5P_OLD_R5_RUNNER_REOPENED"),
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
            runner = root / OLD_RUNNER
            runner.write_text(
                runner.read_text(encoding="utf-8").replace(
                    "R5_PRODUCT_TRANSFORM_ROUTE_CLOSED", "OPEN"
                ),
                encoding="utf-8",
                newline="\n",
            )
            self.assertCode("R5P_OLD_R5_RUNNER_REOPENED", lambda: verifier.verify(root))

    def test_rejects_duplicate_and_trailing_authority_json(self):
        with self.sandbox() as root:
            path = root / AUTHORITY
            raw = path.read_text(encoding="utf-8")
            path.write_text(
                raw.replace(
                    '"authorityVersion": "renderweave-r5p-authority/1.0",',
                    '"authorityVersion": "renderweave-r5p-authority/1.0",\n'
                    '  "authorityVersion": "renderweave-r5p-authority/1.0",',
                ),
                encoding="utf-8",
                newline="\n",
            )
            self.assertCode("R5P_AUTHORITY_JSON_INVALID", lambda: verifier.verify(root))

        with self.sandbox() as root:
            path = root / AUTHORITY
            path.write_text(
                path.read_text(encoding="utf-8") + "{}\n",
                encoding="utf-8",
                newline="\n",
            )
            self.assertCode("R5P_AUTHORITY_JSON_INVALID", lambda: verifier.verify(root))

    def assertCode(self, code, action):
        with self.assertRaises(verifier.VerificationError) as caught:
            action()
        self.assertEqual(code, str(caught.exception))

    def sandbox(self):
        temporary = tempfile.TemporaryDirectory()
        root = pathlib.Path(temporary.name)
        for relative in (AUTHORITY, OLD_AUTHORITY, OLD_RUNNER, SPEC):
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
