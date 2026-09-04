#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v49_successor.py")
SPEC = importlib.util.spec_from_file_location("v49_successor_verifier", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class V49SuccessorVerifierTest(unittest.TestCase):
    def test_accepts_only_the_three_approved_profile_fields(self):
        v48 = {
            "profileId": MODULE.V48_ID,
            "pipelineVersion": "renderweave-inference-pipeline/4.30",
            "elementPromptVersion": "renderweave-visual-elements-prompt/14.0",
            "model": "qwen3.8-max",
        }
        v49 = dict(v48)
        v49.update({
            "profileId": MODULE.V49_ID,
            "pipelineVersion": "renderweave-inference-pipeline/4.31",
            "elementPromptVersion": "renderweave-visual-elements-prompt/15.0",
        })
        self.assertEqual(MODULE.ALLOWED_DIFF, MODULE.exact_profile_diff(v48, v49))

    def test_rejects_field_order_drift(self):
        left = {"profileId": MODULE.V48_ID, "model": "qwen3.8-max"}
        right = {"model": "qwen3.8-max", "profileId": MODULE.V49_ID}
        with self.assertRaises(SystemExit):
            MODULE.exact_profile_diff(left, right)


if __name__ == "__main__":
    unittest.main()
