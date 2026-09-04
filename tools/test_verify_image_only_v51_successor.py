#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v51_successor.py")
SPEC = importlib.util.spec_from_file_location("v51_successor_verifier", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class V51SuccessorVerifierTest(unittest.TestCase):
    def test_known_details_require_exact_canonical_order(self):
        details = [MODULE.DETAIL_CODES[0], MODULE.DETAIL_CODES[3]]
        self.assertEqual(details, MODULE.canonical_containment_details(details))
        with self.assertRaises(SystemExit):
            MODULE.canonical_containment_details(list(reversed(details)))
        with self.assertRaises(SystemExit):
            MODULE.canonical_containment_details([details[0], details[0]])
        with self.assertRaises(SystemExit):
            MODULE.canonical_containment_details(["UNKNOWN_RUNTIME_DETAIL"])

    def test_atomic_and_unclassified_are_exclusive(self):
        self.assertEqual(
            [MODULE.DETAIL_CODES[4]],
            MODULE.canonical_containment_details([MODULE.DETAIL_CODES[4]]),
        )
        self.assertEqual(
            [MODULE.DETAIL_CODES[5]],
            MODULE.canonical_containment_details([MODULE.DETAIL_CODES[5]]),
        )
        for exclusive in MODULE.DETAIL_CODES[4:]:
            with self.subTest(exclusive=exclusive), self.assertRaises(SystemExit):
                MODULE.canonical_containment_details(
                    [MODULE.DETAIL_CODES[0], exclusive]
                )


if __name__ == "__main__":
    unittest.main()
