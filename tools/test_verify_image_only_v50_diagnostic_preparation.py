#!/usr/bin/env python3

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v50_diagnostic_preparation.py")
SPEC = importlib.util.spec_from_file_location("v50_diagnostic", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class V50DiagnosticPreparationVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]
        cls.metadata = {
            "artifactSha256": MODULE.ARTIFACT_SHA,
            "mediaType": "image/png",
            "encodedBytes": 337855,
            "width": 3496,
            "height": 780,
        }

    def copy_preparation(self, root: Path) -> Path:
        relative = Path("plans/image-only-profile-successor-diagnostics") / f"{MODULE.CYCLE_ID}.json"
        destination = root / relative
        destination.parent.mkdir(parents=True)
        shutil.copyfile(self.repository / relative, destination)
        (root / "plans/live-canary-authorizations").mkdir(parents=True)
        return destination

    def test_replays_exact_canonicalizer_bound_identities_and_caps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_preparation(root)
            identities = MODULE.require_preparation(root, self.metadata)
            self.assertEqual(MODULE.MANIFEST_IDENTITY, identities["manifestIdentity"])
            self.assertEqual(MODULE.NORMALIZATION_IDENTITY, identities["normalizationIdentity"])

    def test_identity_and_cap_tampering_fail_closed(self):
        for field, value, expected in (
            ("successorImplementationIdentity", "renderweave-image-only-v50-implementation/1.0:" + "f" * 64,
             "V50_DIAGNOSTIC_PREPARATION_FACT_DRIFT"),
            ("normalizationIdentity", MODULE.NORMALIZATION_VERSION + ":" + "f" * 64,
             "V50_DIAGNOSTIC_PREPARATION_FACT_DRIFT"),
            ("maximumProviderCalls", 6, "V50_DIAGNOSTIC_J1_CAPS_DRIFT"),
        ):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                path = self.copy_preparation(root)
                document = json.loads(path.read_text(encoding="utf-8"))
                if field == "maximumProviderCalls":
                    document["requiredAuthorization"][field] = value
                else:
                    document[field] = value
                path.write_text(json.dumps(document), encoding="utf-8")
                with self.assertRaises(SystemExit) as failure:
                    MODULE.require_preparation(root, self.metadata)
                self.assertEqual(expected, str(failure.exception))

    def test_any_open_authorization_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "plans/live-canary-authorizations"
            target.mkdir(parents=True)
            (target / "20260818-unexpected.json").write_text(
                json.dumps({"status": "OPEN"}), encoding="utf-8"
            )
            with self.assertRaises(SystemExit) as failure:
                MODULE.require_no_open_authorization(root)
            self.assertEqual(
                "V50_DIAGNOSTIC_OPEN_AUTHORIZATION_FORBIDDEN", str(failure.exception)
            )


if __name__ == "__main__":
    unittest.main()
