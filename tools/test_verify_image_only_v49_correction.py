#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v49_correction.py")
SPEC = importlib.util.spec_from_file_location("v49_correction_verifier", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class V49CorrectionVerifierTest(unittest.TestCase):
    def test_exact_canonical_sets_are_independent_breaker_keys(self):
        set_a = MODULE.canonical_detail_key(
            MODULE.MIXED_PRIMARY, "OBSERVE", MODULE.PROMPT_COVERED_DETAILS[1:3]
        )
        set_b = MODULE.canonical_detail_key(
            MODULE.MIXED_PRIMARY, "OBSERVE",
            [MODULE.PROMPT_COVERED_DETAILS[1], MODULE.PROMPT_COVERED_DETAILS[4]],
        )
        self.assertNotEqual(set_a, set_b)
        self.assertFalse(MODULE.breaker_reached(1))
        self.assertTrue(MODULE.breaker_reached(2))

    def test_unknown_unclassified_single_and_noncanonical_sets_fail_closed(self):
        invalid = [
            (MODULE.UNCLASSIFIED_PRIMARY, "OBSERVE", []),
            (MODULE.MIXED_PRIMARY, "HIERARCHY", MODULE.PROMPT_COVERED_DETAILS[:2]),
            (MODULE.MIXED_PRIMARY, "OBSERVE", MODULE.PROMPT_COVERED_DETAILS[:1]),
            (MODULE.MIXED_PRIMARY, "OBSERVE", [MODULE.PROMPT_COVERED_DETAILS[0], "UNKNOWN"]),
            (MODULE.MIXED_PRIMARY, "OBSERVE", list(reversed(MODULE.PROMPT_COVERED_DETAILS[:2]))),
        ]
        for primary, stage, details in invalid:
            with self.subTest(primary=primary, stage=stage, details=details):
                with self.assertRaises(SystemExit):
                    MODULE.canonical_detail_key(primary, stage, details)


if __name__ == "__main__":
    unittest.main()
