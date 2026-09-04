#!/usr/bin/env python3

import importlib.util
import json
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v52_successor.py")
SPEC = importlib.util.spec_from_file_location("v52_successor_verifier", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class V52SuccessorVerifierTest(unittest.TestCase):
    def test_canonical_profile_hash_preserves_declared_field_order(self):
        value = {"profileId": "p", "pipelineVersion": "v"}
        expected = __import__("hashlib").sha256(
            json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        self.assertEqual(expected, MODULE.canonical_profile_sha(value))

    def test_strict_json_rejects_duplicate_keys(self):
        with self.assertRaises(SystemExit):
            json.loads('{"a":1,"a":2}', object_pairs_hook=MODULE.strict_object_pairs)

    def test_envelope_bound_is_exactly_eight(self):
        self.assertEqual(8, MODULE.MAX_ENVELOPES)


if __name__ == "__main__":
    unittest.main()
