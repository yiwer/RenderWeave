import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v49_provenance.py")
SPEC = importlib.util.spec_from_file_location("v49_provenance", MODULE_PATH)
v49_provenance = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(v49_provenance)


class V49ProvenanceVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]
        cls.matrix = (
            cls.repository
            / "renderweave-inference/src/test/resources/image-only/"
            "v49-region-fallback-provenance-v1.json"
        )

    def test_exact_matrix_has_complete_canonical_taxonomy(self):
        result = v49_provenance.verify_matrix(self.matrix)
        self.assertEqual(13, result["fixtureCount"])
        self.assertEqual(v49_provenance.CLOSED_DETAILS, result["closedDetailCodes"])

    def test_noncanonical_detail_order_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "matrix.json"
            value = json.loads(self.matrix.read_text(encoding="utf-8"))
            fixture = next(item for item in value["fixtures"]
                           if item["fixtureId"] == "mixed-id-parent-id")
            fixture["expectedDetailCodes"].reverse()
            target.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(SystemExit) as failure:
                v49_provenance.verify_matrix(target)
            self.assertEqual(
                "V49_PROVENANCE_FIXTURE_EXPECTATION_INVALID", str(failure.exception)
            )

    def test_unknown_detail_code_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "matrix.json"
            value = json.loads(self.matrix.read_text(encoding="utf-8"))
            value["fixtures"][0]["regionFailureCodes"] = [["UNAPPROVED_CODE"]]
            target.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(SystemExit) as failure:
                v49_provenance.verify_matrix(target)
            self.assertEqual("V49_PROVENANCE_DETAIL_ENUM_INVALID", str(failure.exception))

    def test_historical_profiles_and_terminals_are_immutable(self):
        result = v49_provenance.require_historical_immutability(self.repository)
        self.assertEqual(
            v49_provenance.V48_TERMINAL_SHA, result["v48TerminalSha256"]
        )

    def test_any_open_authorization_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "plans/live-canary-authorizations"
            target.mkdir(parents=True)
            (target / "unexpected.json").write_text(
                json.dumps({"status": "OPEN"}), encoding="utf-8"
            )
            with self.assertRaises(SystemExit) as failure:
                v49_provenance.require_no_open_authorization(root)
            self.assertEqual(
                "V49_PROVENANCE_OPEN_AUTHORIZATION_FORBIDDEN", str(failure.exception)
            )

    def test_frozen_legacy_open_then_closed_record_is_counted_but_not_rewritten(self):
        self.assertEqual(
            1, v49_provenance.require_no_open_authorization(self.repository)
        )


if __name__ == "__main__":
    unittest.main()
