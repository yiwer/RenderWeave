#!/usr/bin/env python3

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_p2_encryption.py")
SPEC = importlib.util.spec_from_file_location("p2_encryption", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class P2EncryptionVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]

    def copy_material(self, root: Path) -> None:
        for relative in MODULE.MATERIAL_PATHS:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.repository / relative, destination)

    def test_verifies_current_provider_zero_encryption_contract(self):
        report = MODULE.verify(self.repository)
        self.assertEqual("PASS", report["result"])
        self.assertTrue(report["perArtifactRandomDek"])
        self.assertTrue(report["missingComponentFailsClosed"])
        self.assertFalse(report["envelopeEncryptionDefaultEnabled"])
        self.assertEqual(0, report["verificationProviderUsage"]["attempts"])

    def test_algorithm_migration_or_configuration_drift_fails_closed(self):
        mutations = (
            (3, 'TRANSFORMATION = "AES/GCM/NoPadding"', 'TRANSFORMATION = "AES/CBC/PKCS5Padding"'),
            (11, "octet_length(payload_nonce) = 12", "octet_length(payload_nonce) = 16"),
            (10, "RENDERWEAVE_BLOB_ENVELOPE_ENCRYPTION_ENABLED:false",
             "RENDERWEAVE_BLOB_ENVELOPE_ENCRYPTION_ENABLED:true"),
        )
        for index, old, new in mutations:
            with self.subTest(relative=MODULE.MATERIAL_PATHS[index]), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_material(root)
                path = root / MODULE.MATERIAL_PATHS[index]
                path.write_text(path.read_text(encoding="utf-8").replace(old, new, 1), encoding="utf-8")
                with self.assertRaises(SystemExit):
                    MODULE.require_contract(root)


if __name__ == "__main__":
    unittest.main()
