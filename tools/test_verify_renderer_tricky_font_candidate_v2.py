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
DECISION = ".scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v2.json"
CANDIDATE_V1 = ".scratch/renderweave-template-v1/renderer-spike-candidate-v1.json"
CANDIDATE_V2 = ".scratch/renderweave-template-v1/renderer-spike-candidate-v2.json"
SUPERSESSIONS = ".scratch/renderweave-template-v1/renderer-spike/candidate-supersessions-v1.json"
HEADER_V2 = ".scratch/renderweave-template-v1/renderer-spike/rw-freetype-ftoption-v2.h"
SOURCE_TARGET_V2 = ".scratch/renderweave-template-v1/renderer-spike/source-integrity-target-manifest-v2.json"
INPUTS = [
    "specs/changes/20260831-renderer-tricky-font-classification-candidate-v2.md",
    CANDIDATE_V1,
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v1.json",
    SUPERSESSIONS,
    CANDIDATE_V2,
    SOURCE_TARGET_V2,
    ".scratch/renderweave-template-v1/renderer-spike/tricky-font-fixture-policy-v2.json",
    HEADER_V2,
    ".scratch/renderweave-template-v1/renderer-spike/hermetic-linux-build-prerequisites-v2.json",
    ".scratch/renderweave-template-v1/renderer-spike/application-order-v2.json",
    "renderer/process-manifest.json",
]


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


class RendererTrickyFontCandidateV2VerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary.name)
        for relative in [DECISION, *INPUTS]:
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

    def refresh_decision_binding(self, relative: str) -> None:
        data = (self.repo / relative).read_bytes()
        decision = self.read_json(DECISION)
        item = next(entry for entry in decision["inputs"] if entry["path"] == relative)
        item["sha256"] = digest(data)
        item["byteLength"] = len(data)
        self.write_json(DECISION, decision)

    def refresh_successor_binding(self) -> None:
        successor_bytes = (self.repo / CANDIDATE_V2).read_bytes()
        supersessions = self.read_json(SUPERSESSIONS)
        supersessions["records"][0]["successorSha256"] = digest(successor_bytes)
        supersessions["records"][0]["successorByteLength"] = len(successor_bytes)
        self.write_json(SUPERSESSIONS, supersessions)
        self.refresh_decision_binding(SUPERSESSIONS)

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

    def test_exact_successor_is_classification_compatible_and_fail_closed(self) -> None:
        result = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = self.read_json("report.json")
        self.assertEqual(
            report["status"],
            "PASS_NEW_CANDIDATE_CLASSIFICATION_COMPATIBLE_FAIL_CLOSED",
        )
        self.assertTrue(report["observedCompatibility"]["classificationImplementationCompiled"])
        self.assertTrue(report["observedCompatibility"]["currentCandidateCanSatisfyPortableAuthority"])
        self.assertFalse(report["observedCompatibility"]["runtimeBytecodeNonExecutionProven"])
        self.assertFalse(report["boundary"]["exactRendererTargetMayMaterialize"])
        self.assertFalse(report["boundary"]["certified"])
        self.assertFalse(report["boundary"]["ready"])

    def test_immutable_predecessor_mutation_fails(self) -> None:
        predecessor = self.read_json(CANDIDATE_V1)
        predecessor["status"] = "MUTATED"
        self.write_json(CANDIDATE_V1, predecessor)
        self.refresh_decision_binding(CANDIDATE_V1)
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("PREDECESSOR_IMMUTABLE_BYTES", result.stderr)

    def test_candidate_must_require_both_no_hinting_flags(self) -> None:
        candidate = self.read_json(CANDIDATE_V2)
        candidate["candidateBuildContract"]["freetypePatchPolicy"]["requiredFinalLoadFlags"].remove(
            "FT_LOAD_NO_AUTOHINT"
        )
        self.write_json(CANDIDATE_V2, candidate)
        self.refresh_decision_binding(CANDIDATE_V2)
        self.refresh_successor_binding()
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CANDIDATE_REQUIRED_LOAD_FLAGS", result.stderr)

    def test_header_cannot_remove_derived_interpreter_macro(self) -> None:
        header = self.repo / HEADER_V2
        header.write_text(
            header.read_text(encoding="utf-8").replace(
                "#ifndef TT_USE_BYTECODE_INTERPRETER",
                "#undef TT_USE_BYTECODE_INTERPRETER\n#ifndef TT_USE_BYTECODE_INTERPRETER",
                1,
            ),
            encoding="utf-8",
            newline="\n",
        )
        header_hash = digest(header.read_bytes()).removeprefix("sha256:")
        candidate = self.read_json(CANDIDATE_V2)
        options = next(
            item
            for item in candidate["candidateBuildContract"]["freetypePatchPolicy"]["customFreetypeHeaders"]
            if item["macro"] == "FT_CONFIG_OPTIONS_H"
        )
        options["bytesSha256"] = header_hash
        self.write_json(CANDIDATE_V2, candidate)
        source = self.read_json(SOURCE_TARGET_V2)
        source["candidateArtifactSha256"]["ftoption"] = header_hash
        self.write_json(SOURCE_TARGET_V2, source)
        self.refresh_decision_binding(HEADER_V2)
        self.refresh_decision_binding(CANDIDATE_V2)
        self.refresh_decision_binding(SOURCE_TARGET_V2)
        self.refresh_successor_binding()
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("HEADER_DERIVED_INTERPRETER_DISABLED", result.stderr)

    def test_runtime_proof_and_target_materialization_overclaim_fails(self) -> None:
        decision = self.read_json(DECISION)
        decision["observedCompatibility"]["runtimeBytecodeNonExecutionProven"] = True
        decision["enforcedBoundary"]["exactRendererTargetMayMaterialize"] = True
        self.write_json(DECISION, decision)
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("OBSERVED_COMPATIBILITY", result.stderr)


if __name__ == "__main__":
    unittest.main()
