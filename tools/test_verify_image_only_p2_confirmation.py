#!/usr/bin/env python3

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_p2_confirmation.py")
SPEC = importlib.util.spec_from_file_location("p2_confirmation", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class P2ConfirmationVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]

    def copy_material(self, root: Path) -> None:
        for relative in MODULE.MATERIAL_PATHS:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.repository / relative, destination)

    def test_replays_current_contract_and_known_answers(self):
        report = MODULE.verify(self.repository)
        self.assertEqual("PASS", report["result"])
        self.assertTrue(report["runManifestConfirmationAtomic"])
        self.assertFalse(report["ambiguousAttemptBlindReplayAllowed"])
        self.assertEqual(MODULE.KNOWN_REQUEST_SHA256, report["knownAnswerRequestSha256"])
        self.assertEqual(0, report["verificationProviderUsage"]["attempts"])

    def test_deadline_or_transaction_drift_fails_closed(self):
        for relative, old, new in (
            (MODULE.MATERIAL_PATHS[17], "INTERVAL '15 minutes'", "INTERVAL '16 minutes'"),
            (MODULE.MATERIAL_PATHS[16], "@Transactional", "@Deprecated"),
        ):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_material(root)
                path = root / relative
                path.write_text(path.read_text(encoding="utf-8").replace(old, new, 1),
                                encoding="utf-8")
                with self.assertRaises(SystemExit):
                    MODULE.require_contract(root)


if __name__ == "__main__":
    unittest.main()
