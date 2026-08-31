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
DECISION = ".scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v3.json"
CANDIDATE_V3 = ".scratch/renderweave-template-v1/renderer-spike-candidate-v3.json"
SOURCE_TARGET_V3 = (
    ".scratch/renderweave-template-v1/renderer-spike/source-integrity-target-manifest-v3.json"
)
SUPERSESSIONS_V2 = (
    ".scratch/renderweave-template-v1/renderer-spike/candidate-supersessions-v2.json"
)
V2_INPUTS = [
    "specs/changes/20260831-renderer-tricky-font-classification-candidate-v2.md",
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/candidate-supersessions-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/source-integrity-target-manifest-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-fixture-policy-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftoption-v2.h",
    ".scratch/renderweave-template-v1/renderer-spike/hermetic-linux-build-prerequisites-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/application-order-v2.json",
    "renderer/process-manifest.json",
]
V3_INPUTS = [
    "specs/changes/20260831-renderer-candidate-v3-mechanical-correction.md",
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/candidate-supersessions-v1.json",
    ".scratch/renderweave-template-v1/renderer-spike/candidate-supersessions-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike-candidate-v3.json",
    ".scratch/renderweave-template-v1/renderer-spike/source-integrity-target-manifest-v3.json",
    ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftoption-v3.h",
    ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftmodule-v3.h",
    ".scratch/renderweave-template-v1/renderer-spike/skia-m151-freetype-policy.patch",
    ".scratch/renderweave-template-v1/renderer-spike/skia-m151-freetype-policy-v3.patch",
    ".scratch/renderweave-template-v1/renderer-spike/hermetic-linux-build-prerequisites-v3.json",
    ".scratch/renderweave-template-v1/renderer-spike/application-order-v3.json",
    "renderer/probes/t213/rehearsal-result-v1.json",
    "renderer/process-manifest.json",
]


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


class RendererTrickyFontCandidateV3VerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary.name)
        for relative in dict.fromkeys([DECISION, *V2_INPUTS, *V3_INPUTS]):
            target = self.repo / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def read_json(self, relative: str) -> dict:
        return json.loads((self.repo / relative).read_text(encoding="utf-8"))

    def write_json(self, relative: str, value: dict) -> None:
        (self.repo / relative).write_text(
            json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n"
        )

    def run_verifier(self) -> subprocess.CompletedProcess[str]:
        report_path = self.repo / "report.json"
        if report_path.exists():
            report_path.unlink()
        return subprocess.run(
            [
                sys.executable,
                str(VERIFIER),
                "--repo",
                str(self.repo),
                "--decision",
                DECISION,
                "--report",
                str(report_path),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def refresh_decision_binding(self, relative: str) -> None:
        data = (self.repo / relative).read_bytes()
        decision = self.read_json(DECISION)
        item = next(entry for entry in decision["inputs"] if entry["path"] == relative)
        item["sha256"] = digest(data)
        item["byteLength"] = len(data)
        self.write_json(DECISION, decision)

    def refresh_header_bindings(self, relative: str, artifact_key: str) -> None:
        header_hash = digest((self.repo / relative).read_bytes()).removeprefix("sha256:")
        candidate = self.read_json(CANDIDATE_V3)
        header = next(
            item
            for item in candidate["candidateBuildContract"]["mechanicalCorrectionContract"][
                "customFreetypeHeaders"
            ]
            if item["sourcePath"].endswith(Path(relative).name)
        )
        header["bytesSha256"] = header_hash
        self.write_json(CANDIDATE_V3, candidate)
        self.refresh_candidate_derivatives(relative, artifact_key, header_hash)

    def refresh_application_order_bindings(self, relative: str) -> None:
        order_hash = digest((self.repo / relative).read_bytes()).removeprefix("sha256:")
        candidate = self.read_json(CANDIDATE_V3)
        candidate["candidateBuildContract"]["mechanicalCorrectionContract"][
            "applicationOrder"
        ]["sha256"] = order_hash
        self.write_json(CANDIDATE_V3, candidate)
        self.refresh_candidate_derivatives(relative, "applicationOrder", order_hash)

    def refresh_candidate_derivatives(
        self, changed_relative: str, artifact_key: str, artifact_hash: str
    ) -> None:
        candidate_data = (self.repo / CANDIDATE_V3).read_bytes()
        source = self.read_json(SOURCE_TARGET_V3)
        source["candidateArtifactSha256"][artifact_key] = artifact_hash
        source["candidateArtifactSha256"]["candidate"] = digest(candidate_data).removeprefix(
            "sha256:"
        )
        self.write_json(SOURCE_TARGET_V3, source)

        supersessions = self.read_json(SUPERSESSIONS_V2)
        successor = supersessions["records"][1]
        successor["successorSha256"] = digest(candidate_data)
        successor["successorByteLength"] = len(candidate_data)
        self.write_json(SUPERSESSIONS_V2, supersessions)

        for changed in (
            changed_relative,
            CANDIDATE_V3,
            SOURCE_TARGET_V3,
            SUPERSESSIONS_V2,
        ):
            self.refresh_decision_binding(changed)

    def test_exact_successor_is_mechanically_buildable_and_fail_closed(self) -> None:
        result = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = self.read_json("report.json")
        self.assertEqual(
            report["status"],
            "PASS_SUCCESSOR_MECHANICALLY_BUILDABLE_BUILD_PENDING",
        )
        self.assertEqual(
            report["candidateId"],
            "rw-renderer-spike-linux-x86_64-v2-000003",
        )
        self.assertTrue(report["observedCompatibility"]["stockOptionsReachable"])
        self.assertTrue(report["observedCompatibility"]["moduleListRepeatable"])
        self.assertFalse(report["boundary"]["exactBuiltTargetObserved"])
        self.assertFalse(report["boundary"]["certified"])
        self.assertFalse(report["boundary"]["ready"])

    def test_options_header_cannot_resolve_to_itself(self) -> None:
        header_path = (
            ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftoption-v3.h"
        )
        header = self.repo / header_path
        header.write_text(
            header.read_text(encoding="utf-8").replace(
                '#include "../freetype/config/ftoption.h"',
                "#include <renderweave/ftoption.h>",
                1,
            ),
            encoding="utf-8",
            newline="\n",
        )
        self.refresh_header_bindings(header_path, "ftoption")
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("OPTIONS_V3_STOCK_INCLUDE", result.stderr)

    def test_modules_header_cannot_gain_an_include_guard(self) -> None:
        header_path = (
            ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftmodule-v3.h"
        )
        header = self.repo / header_path
        original = header.read_text(encoding="utf-8")
        header.write_text(
            "#ifndef RENDERWEAVE_MUTATED_MODULES_H_\n"
            "#define RENDERWEAVE_MUTATED_MODULES_H_\n"
            f"{original}"
            "#endif\n",
            encoding="utf-8",
            newline="\n",
        )
        self.refresh_header_bindings(header_path, "ftmodule")
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MODULES_V3_REPEATABILITY_GUARD", result.stderr)

    def test_immutable_v2_predecessor_cannot_be_rewritten(self) -> None:
        predecessor_path = ".scratch/renderweave-template-v1/renderer-spike-candidate-v2.json"
        predecessor = self.read_json(predecessor_path)
        predecessor["status"] = "MUTATED"
        self.write_json(predecessor_path, predecessor)
        self.refresh_decision_binding(predecessor_path)
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("PREDECESSOR_V2_IMMUTABLE_BYTES", result.stderr)

    def test_source_decision_cannot_overclaim_an_exact_build(self) -> None:
        decision = self.read_json(DECISION)
        decision["observedCompatibility"]["exactBuiltTargetObserved"] = True
        self.write_json(DECISION, decision)
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("OBSERVED_COMPATIBILITY_V3", result.stderr)

    def test_application_order_cannot_install_options_under_the_shadowing_root(self) -> None:
        order_path = (
            ".scratch/renderweave-template-v1/renderer-spike/application-order-v3.json"
        )
        order = self.read_json(order_path)
        order["steps"][1]["target"] = (
            "third_party/freetype2/include/renderweave-freetype/freetype/config/ftoption.h"
        )
        self.write_json(order_path, order)
        self.refresh_application_order_bindings(order_path)
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("APPLICATION_ORDER_V3_CORRECTION_PATHS", result.stderr)

    def test_stale_artifact_binding_fails_before_semantic_validation(self) -> None:
        header_path = (
            ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftoption-v3.h"
        )
        header = self.repo / header_path
        header.write_text(
            header.read_text(encoding="utf-8") + "\n",
            encoding="utf-8",
            newline="\n",
        )
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("INPUT_V3_BINDING", result.stderr)


if __name__ == "__main__":
    unittest.main()
