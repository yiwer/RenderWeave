#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v49_envelope.py")
SPEC = importlib.util.spec_from_file_location("v49_envelope_verifier", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class V49EnvelopeVerifierTest(unittest.TestCase):
    def test_accepts_only_canonical_mixed_and_empty_unclassified(self):
        mixed = MODULE.canonical_envelope(
            MODULE.MIXED_PRIMARY, "OBSERVE", MODULE.CLOSED_DETAILS[:2]
        )
        unclassified = MODULE.canonical_envelope(
            MODULE.UNCLASSIFIED_PRIMARY, "OBSERVE", []
        )
        self.assertEqual(2, mixed["detailCodeCount"])
        self.assertEqual(0, unclassified["detailCodeCount"])

    def test_rejects_single_duplicate_unknown_order_stage_and_primary_drift(self):
        invalid = [
            (MODULE.MIXED_PRIMARY, "OBSERVE", MODULE.CLOSED_DETAILS[:1]),
            (MODULE.MIXED_PRIMARY, "OBSERVE", [MODULE.CLOSED_DETAILS[0]] * 2),
            (MODULE.MIXED_PRIMARY, "OBSERVE", [MODULE.CLOSED_DETAILS[1], MODULE.CLOSED_DETAILS[0]]),
            (MODULE.MIXED_PRIMARY, "OBSERVE", [MODULE.CLOSED_DETAILS[0], "UNKNOWN"]),
            (MODULE.MIXED_PRIMARY, "HIERARCHY", MODULE.CLOSED_DETAILS[:2]),
            ("UNKNOWN", "OBSERVE", []),
            (MODULE.UNCLASSIFIED_PRIMARY, "OBSERVE", MODULE.CLOSED_DETAILS[:1]),
        ]
        for primary, stage, details in invalid:
            with self.subTest(primary=primary, stage=stage, details=details):
                with self.assertRaises(SystemExit):
                    MODULE.canonical_envelope(primary, stage, details)


if __name__ == "__main__":
    unittest.main()
