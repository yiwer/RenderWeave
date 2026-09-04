#!/usr/bin/env python3

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v52_diagnostic_postclose.py")
SPEC = importlib.util.spec_from_file_location("v52_postclose", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class V52DiagnosticPostcloseVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]

    def copy_documents(self, root: Path) -> tuple[Path, Path, Path, Path]:
        relatives = (
            Path("plans/live-canary-authorizations/20260818-image-only-v52-diagnostic-981d7262.json"),
            Path("plans/image-only-profile-successor-diagnostics/981d7262-d802-45bb-96ce-d34b4468f9f9-terminal.json"),
            Path(".sdlc/evidence/20260818-152950-image-only-v52-successor-diagnostic-live/image-only-v52-diagnostic-live-summary.json"),
            Path(".scratch/image-only-profile-successor-diagnostic-reviews/20260818-iopa-v52-diagnostic-981d7262/v46-failed-route-82.json"),
        )
        destinations = []
        for relative in relatives:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.repository / relative, destination)
            destinations.append(destination)
        return tuple(destinations)

    def test_replays_exact_closed_review_pending_terminal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_documents(root)
            digests = MODULE.require_terminal_documents(root)
            self.assertEqual(MODULE.AUTH_SHA, digests["authorizationSha256"])
            self.assertEqual(MODULE.TERMINAL_SHA, digests["terminalSha256"])
            self.assertEqual(MODULE.REVIEW_PACK_SHA, digests["reviewPackSha256"])

    def test_terminal_ledger_or_review_tampering_fails_closed(self):
        for index, key, value in (
            (1, "modelTokens", 37_452),
            (2, "unsettledReservations", 1),
            (3, "manualVerdict", "ACCEPTED"),
        ):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                paths = self.copy_documents(root)
                document = json.loads(paths[index].read_text(encoding="utf-8"))
                if index == 2:
                    document["ledger"][key] = value
                else:
                    document[key] = value
                paths[index].write_text(json.dumps(document), encoding="utf-8")
                with self.assertRaises(SystemExit):
                    MODULE.require_terminal_documents(root)


if __name__ == "__main__":
    unittest.main()
