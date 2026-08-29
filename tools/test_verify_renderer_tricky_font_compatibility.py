from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "tools/verify-renderer-tricky-font-compatibility.py"
DECISION = ".scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v1.json"
INPUTS = [
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/source-integrity-target-manifest-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-fixture-policy-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftoption.h",
    ".scratch/renderweave-template-v1/renderer-spike/hermetic-linux-build-prerequisites-v1.json",
    "renderer/process-manifest.json",
]


class RendererTrickyFontCompatibilityVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary.name)
        for relative in [DECISION, *INPUTS]:
            target = self.repo / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_verifier(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(VERIFIER),
                "--repo", str(self.repo),
                "--decision", DECISION,
                "--report", str(self.repo / "report.json"),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def mutate_json(self, relative: str, mutation) -> None:
        path = self.repo / relative
        value = json.loads(path.read_text(encoding="utf-8"))
        mutation(value)
        path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n")
        if relative != DECISION:
            data = path.read_bytes()
            decision_path = self.repo / DECISION
            decision = json.loads(decision_path.read_text(encoding="utf-8"))
            entry = next(item for item in decision["inputs"] if item["path"] == relative)
            entry["sha256"] = "sha256:" + hashlib.sha256(data).hexdigest()
            entry["byteLength"] = len(data)
            decision_path.write_text(
                json.dumps(decision, indent=2) + "\n", encoding="utf-8", newline="\n"
            )

    def test_exact_candidate_is_reported_fail_closed(self) -> None:
        result = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads((self.repo / "report.json").read_text(encoding="utf-8"))
        self.assertEqual(report["status"], "PASS_FAIL_CLOSED")
        self.assertFalse(report["boundary"]["exactRendererTargetMayMaterialize"])
        self.assertFalse(report["boundary"]["certified"])
        self.assertFalse(report["boundary"]["ready"])

    def test_duplicate_decision_member_fails(self) -> None:
        path = self.repo / DECISION
        text = path.read_text(encoding="utf-8")
        path.write_text(
            text.replace(
                '  "status": "BLOCKED_CANDIDATE_SEMANTIC_CONTRADICTION",',
                '  "status": "BLOCKED_CANDIDATE_SEMANTIC_CONTRADICTION",\n'
                '  "status": "BLOCKED_CANDIDATE_SEMANTIC_CONTRADICTION",',
                1,
            ),
            encoding="utf-8",
            newline="\n",
        )
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("JSON_DUPLICATE_MEMBER", result.stderr)

    def test_policy_without_ft_is_tricky_requirement_fails(self) -> None:
        self.mutate_json(
            INPUTS[2],
            lambda value: value["portableAuthority"]["requiredProperties"].pop(0),
        )
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("PORTABLE_PROPERTIES", result.stderr)

    def test_candidate_certification_overclaim_fails(self) -> None:
        self.mutate_json(
            INPUTS[0], lambda value: value["currentEvidence"].__setitem__("certified", True)
        )
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CANDIDATE_CERTIFIED", result.stderr)

    def test_decision_target_materialization_overclaim_fails(self) -> None:
        self.mutate_json(
            DECISION,
            lambda value: value["enforcedBoundary"].__setitem__(
                "exactRendererTargetMayMaterialize", True
            ),
        )
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ENFORCED_BOUNDARY", result.stderr)


if __name__ == "__main__":
    unittest.main()
