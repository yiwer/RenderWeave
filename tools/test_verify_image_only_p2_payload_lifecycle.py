#!/usr/bin/env python3

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_p2_payload_lifecycle.py")
SPEC = importlib.util.spec_from_file_location("p2_payload_lifecycle", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class P2PayloadLifecycleVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]

    def copy_material(self, root: Path) -> None:
        for relative in MODULE.MATERIAL_PATHS:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.repository / relative, destination)

    def test_verifies_current_provider_zero_payload_lifecycle_contract(self):
        report = MODULE.verify(self.repository)
        self.assertEqual("PASS", report["result"])
        self.assertTrue(report["tombstoneFirst"])
        self.assertTrue(report["readRetryProviderApplyGuarded"])
        self.assertFalse(report["payloadLifecycleDefaultEnabled"])
        self.assertEqual(0, report["verificationProviderUsage"]["attempts"])

    def test_retention_guard_and_activation_drift_fail_closed(self):
        mutations = (
            (0, "MAX_RETENTION = Duration.ofDays(7)", "MAX_RETENTION = Duration.ofDays(8)"),
            (9,
             "payloadAccessGuard.require(current.runId(), PayloadAccess.PROVIDER_CALL)",
             "payloadAccessGuard.require(current.runId(), PayloadAccess.READ)"),
            (17, "RENDERWEAVE_PAYLOAD_LIFECYCLE_ENABLED:false",
             "RENDERWEAVE_PAYLOAD_LIFECYCLE_ENABLED:true"),
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
