#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v50_successor.py")
SPEC = importlib.util.spec_from_file_location("v50_successor_verifier", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class V50SuccessorVerifierTest(unittest.TestCase):
    def test_mapping_is_ordered_lossless_and_name_independent(self):
        first = {
            "regions": [
                {"regionId": "Root X", "parentRegionId": None, "repeatGroupId": None, "semantic": 1},
                {"regionId": "Rows X", "parentRegionId": "Root X", "repeatGroupId": "G X", "semantic": 2},
                {"regionId": "Item X", "parentRegionId": "Rows X", "repeatGroupId": "G X", "semantic": 3},
            ],
            "elements": [
                {"elementId": "Owner X", "regionIds": ["Rows X"], "semantic": [1, 2]},
                {"elementId": "Slot X", "regionIds": ["Item X"], "semantic": [3]},
            ],
        }
        second = __import__("json").loads(__import__("json").dumps(first).replace(" X", " Y"))
        left, left_count = MODULE.canonicalize_local_ids(first)
        right, right_count = MODULE.canonicalize_local_ids(second)
        self.assertEqual(left, right)
        self.assertEqual(6, left_count)
        self.assertEqual(left_count, right_count)
        self.assertEqual("r2", left["regions"][2]["parentRegionId"])
        self.assertEqual("g1", left["regions"][2]["repeatGroupId"])
        self.assertEqual(["r3"], left["elements"][1]["regionIds"])
        self.assertEqual([1, 2], left["elements"][0]["semantic"])

    def test_ambiguous_or_malformed_graphs_fail_closed(self):
        base = {
            "regions": [
                {"regionId": "root", "parentRegionId": None, "repeatGroupId": None},
                {"regionId": "child", "parentRegionId": "root", "repeatGroupId": None},
            ],
            "elements": [{"elementId": "slot", "regionIds": ["child"]}],
        }
        invalid = []
        duplicate = __import__("copy").deepcopy(base)
        duplicate["regions"][1]["regionId"] = "root"
        invalid.append(duplicate)
        blank = __import__("copy").deepcopy(base)
        blank["regions"][0]["regionId"] = " "
        invalid.append(blank)
        dangling = __import__("copy").deepcopy(base)
        dangling["regions"][1]["parentRegionId"] = "missing"
        invalid.append(dangling)
        wrong_type = __import__("copy").deepcopy(base)
        wrong_type["elements"][0]["regionIds"] = [7]
        invalid.append(wrong_type)
        for value in invalid:
            with self.subTest(value=value):
                with self.assertRaises(SystemExit):
                    MODULE.canonicalize_local_ids(value)


if __name__ == "__main__":
    unittest.main()

