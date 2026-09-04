import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v47_successor.py")
SPEC = importlib.util.spec_from_file_location("successor", MODULE_PATH)
successor = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(successor)


class SuccessorVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]

    def test_profile_diff_and_identity_are_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "renderweave-inference/src/main/resources/inference-profiles"
            target.mkdir(parents=True)
            for profile_id in (successor.V46_ID, successor.V47_ID):
                shutil.copyfile(
                    self.repository
                    / "renderweave-inference/src/main/resources/inference-profiles"
                    / f"{profile_id}.json",
                    target / f"{profile_id}.json",
                )
            successor.require_exact_profile_diff(root)
            v47_path = target / f"{successor.V47_ID}.json"
            value = json.loads(v47_path.read_text(encoding="utf-8"))
            value["maximumOutputTokens"] = 16384
            v47_path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(SystemExit) as failure:
                successor.require_exact_profile_diff(root)
            self.assertEqual("SUCCESSOR_PROFILE_DIFF_INVALID", str(failure.exception))

    def test_diagnostic_identity_and_caps_are_tamper_evident(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "plans/image-only-profile-successor-diagnostics"
            target.mkdir(parents=True)
            source = (
                self.repository
                / "plans/image-only-profile-successor-diagnostics"
                / f"{successor.DIAGNOSTIC_CYCLE_ID}.json"
            )
            destination = target / source.name
            shutil.copyfile(source, destination)
            metadata = {
                "artifactSha256": successor.FAILED_ARTIFACT_SHA,
                "encodedBytes": 337855,
                "width": 3496,
                "height": 780,
            }
            successor.require_diagnostic_identity(root, metadata)
            value = json.loads(destination.read_text(encoding="utf-8"))
            value["requiredAuthorization"]["maximumProviderCalls"] = 6
            destination.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(SystemExit) as failure:
                successor.require_diagnostic_identity(root, metadata)
            self.assertEqual("SUCCESSOR_DIAGNOSTIC_J1_CAPS_DRIFT", str(failure.exception))

    def test_any_open_authorization_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "plans/live-canary-authorizations"
            target.mkdir(parents=True)
            (target / "unexpected.json").write_text(
                json.dumps({"status": "OPEN"}), encoding="utf-8"
            )
            with self.assertRaises(SystemExit) as failure:
                successor.require_no_open_authorization(root)
            self.assertEqual("SUCCESSOR_OPEN_AUTHORIZATION_FORBIDDEN", str(failure.exception))


if __name__ == "__main__":
    unittest.main()
