#!/usr/bin/env python3

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_v49_diagnostic_postclose.py")
SPEC = importlib.util.spec_from_file_location("v49_postclose", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
PREPARATION = MODULE.load_preparation_verifier()


class V49DiagnosticPostcloseVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]

    def copy_terminal_bundle(self, root: Path) -> tuple[Path, Path, Path]:
        terminal = (
            Path("plans/image-only-profile-successor-diagnostics")
            / f"{PREPARATION.CYCLE_ID}-terminal.json"
        )
        authorization = (
            Path("plans/live-canary-authorizations")
            / "20260818-image-only-v49-diagnostic-432fdfeb.json"
        )
        summary = (
            Path(".sdlc/evidence/20260818-121611-image-only-v49-successor-diagnostic-live")
            / "image-only-v49-diagnostic-live-summary.json"
        )
        for relative in (terminal, authorization, summary):
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.repository / relative, destination)
        return root / terminal, root / authorization, root / summary

    def test_replays_exact_closed_bundle(self):
        authorization = (
            self.repository / "plans/live-canary-authorizations"
            / "20260818-image-only-v49-diagnostic-432fdfeb.json"
        )
        summary = (
            self.repository
            / ".sdlc/evidence/20260818-121611-image-only-v49-successor-diagnostic-live"
            / "image-only-v49-diagnostic-live-summary.json"
        )
        self.assertEqual(MODULE.AUTHORIZATION_SHA, MODULE.sha256(authorization))
        self.assertEqual(MODULE.LIVE_SUMMARY_SHA, MODULE.sha256(summary))
        value, terminal_sha = MODULE.require_terminal(self.repository, PREPARATION)
        self.assertEqual("FAILED", value["result"])
        self.assertEqual(64, len(terminal_sha))

    def test_authorization_and_terminal_tampering_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            terminal, authorization, _ = self.copy_terminal_bundle(root)
            value = json.loads(authorization.read_text(encoding="utf-8"))
            value["maximumProviderCalls"] = 6
            authorization.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(SystemExit) as failure:
                MODULE.require_closed_authorization(root, PREPARATION)
            self.assertEqual(
                "V49_DIAGNOSTIC_CLOSED_AUTHORIZATION_BYTES_DRIFT", str(failure.exception)
            )

            value = json.loads(terminal.read_text(encoding="utf-8"))
            value["providerCalls"] = 6
            terminal.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(SystemExit) as failure:
                MODULE.require_terminal(root, PREPARATION)
            self.assertEqual("V49_DIAGNOSTIC_TERMINAL_FACT_DRIFT", str(failure.exception))

    def test_live_fixed_code_sets_are_exact(self):
        summary = MODULE.require_live_summary(self.repository, PREPARATION)
        attempts = summary["cases"][0]["attempts"]
        self.assertEqual(5, len(attempts))
        self.assertEqual(
            "VISUAL_GROUNDING_ELEMENT_INVALID",
            next(iter(attempts[2]["problemCodeCounts"])),
        )


if __name__ == "__main__":
    unittest.main()
