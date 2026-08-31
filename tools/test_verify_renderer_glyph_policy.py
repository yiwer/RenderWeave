from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "tools/verify-renderer-glyph-policy.py"
INPUTS = [
    "renderer/crates/engine/native/native_text_skia.cpp",
    "renderer/crates/engine/build.rs",
    "renderer/probes/t213/audit_rehearsal.py",
    "tools/renderer-production-text-rehearsal.sh",
    "tools/renderer-exact-build-rehearsal.sh",
]
REQUIRED_FLAGS = [
    "FT_LOAD_NO_HINTING",
    "FT_LOAD_NO_AUTOHINT",
    "FT_LOAD_NO_BITMAP",
    "FT_LOAD_NO_SVG",
]


class RendererGlyphPolicyVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary.name)
        for relative in INPUTS:
            target = self.repo / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_verifier(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VERIFIER), "--repo", str(self.repo)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_production_glyph_boundary_is_closed(self) -> None:
        result = self.run_verifier()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("PASS_PRODUCTION_GLYPH_POLICY", result.stdout)

    def test_removing_each_required_flag_is_rejected(self) -> None:
        source_path = self.repo / INPUTS[0]
        original = source_path.read_text(encoding="utf-8")
        for flag in REQUIRED_FLAGS:
            with self.subTest(flag=flag):
                source_path.write_text(
                    original.replace(flag, f"REMOVED_{flag}"),
                    encoding="utf-8",
                    newline="\n",
                )
                result = self.run_verifier()
                self.assertNotEqual(0, result.returncode)
                self.assertIn(flag, result.stderr)
        source_path.write_text(original, encoding="utf-8", newline="\n")

    def test_removing_either_link_interposition_is_rejected(self) -> None:
        script_path = self.repo / INPUTS[3]
        original = script_path.read_text(encoding="utf-8")
        for symbol in ["FT_Load_Glyph", "FT_New_Library"]:
            with self.subTest(symbol=symbol):
                script_path.write_text(
                    original.replace(f"-Wl,--wrap={symbol}", f"-Wl,REMOVED_WRAP={symbol}"),
                    encoding="utf-8",
                    newline="\n",
                )
                result = self.run_verifier()
                self.assertNotEqual(0, result.returncode)
                self.assertIn(symbol, result.stderr)
        script_path.write_text(original, encoding="utf-8", newline="\n")

    def test_exact_audit_checks_every_native_archive(self) -> None:
        script = (ROOT / INPUTS[3]).read_text(encoding="utf-8")
        self.assertIn('find "$RW_TARGET/release/build"', script)
        self.assertIn("while IFS= read -r native_archive", script)
        self.assertIn('archive_count="$((archive_count + 1))"', script)
        self.assertNotIn("native archive identity is ambiguous", script)

    def test_direct_native_glyph_load_is_rejected(self) -> None:
        source_path = self.repo / INPUTS[0]
        source_path.write_text(
            source_path.read_text(encoding="utf-8")
            + "\nvoid unregistered() { FT_Load_Glyph(face, glyph, flags); }\n",
            encoding="utf-8",
            newline="\n",
        )

        result = self.run_verifier()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("unregistered FT_Load_Glyph call", result.stderr)

    def test_unregistered_native_compile_input_is_rejected(self) -> None:
        build_path = self.repo / INPUTS[1]
        build_path.write_text(
            build_path.read_text(encoding="utf-8").replace(
                '.arg("native/native_text_skia.cpp")',
                '.arg("native/native_text_skia.cpp")\n'
                '            .arg("native/unregistered.cpp")',
            ),
            encoding="utf-8",
            newline="\n",
        )

        result = self.run_verifier()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("compile-input inventory differs", result.stderr)

    def test_exact_dependency_closure_capture_is_required(self) -> None:
        script_path = self.repo / INPUTS[4]
        script_path.write_text(
            script_path.read_text(encoding="utf-8").replace(
                "ninja-deps.txt", "removed-dependency-closure.txt"
            ),
            encoding="utf-8",
            newline="\n",
        )

        result = self.run_verifier()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("ninja-deps.txt", result.stderr)


if __name__ == "__main__":
    unittest.main()
