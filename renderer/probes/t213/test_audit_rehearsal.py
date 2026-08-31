from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from apply_exact_unified_diff import PatchError, apply_exact_unified_diff
from audit_rehearsal import (
    AuditError,
    EXPECTED_GLYPH_LOAD_OCCURRENCES,
    discover_glyph_load_calls,
    forbidden_isa_mnemonics,
    glyph_load_inventory,
    verify_dynamic_exports,
    verify_probe,
)

REQUIRED_LOAD_FLAGS = 0x0100800A
FORBIDDEN_LOAD_FLAGS = 0x00100020


def observation(**overrides: int) -> dict[str, int]:
    value = {
        "openFaceCount": 0,
        "trickyFaceCount": 0,
        "loadCount": 1,
        "invalidLoadCount": 0,
        "interpreterCallCount": 0,
        "loadFlagsOr": REQUIRED_LOAD_FLAGS,
        "loadFlagsAnd": REQUIRED_LOAD_FLAGS,
    }
    value.update(overrides)
    return value


def passing_probe() -> dict[str, object]:
    return {
        "artifactVersion": "renderweave-renderer-instrumented-probe/1.2",
        "candidateId": "rw-renderer-spike-linux-x86_64-v2-000003",
        "rehearsalConfigurationId": "rw-renderer-t213-exact-rehearsal-000002",
        "status": "PASS_EXACT_CANDIDATE_REHEARSAL",
        "controlNoAutoHint": observation(interpreterCallCount=1),
        "controlRequired": observation(),
        "skiaTricky": observation(),
        "skiaCff": observation(),
    }


class IsaAuditTest(unittest.TestCase):
    def test_accepts_v2_mnemonics_and_legacy_verification_instructions(self) -> None:
        self.assertEqual(([], []), forbidden_isa_mnemonics({"mov", "verr", "verw"}))

    def test_rejects_vex_and_v3_or_v4_mnemonics(self) -> None:
        self.assertEqual(
            (["vaddps"], ["cpuid", "kandw", "kmovq", "tzcnt"]),
            forbidden_isa_mnemonics(
                {"mov", "vaddps", "tzcnt", "cpuid", "kandw", "kmovq"}
            ),
        )


class GlyphLoadInventoryTest(unittest.TestCase):
    @staticmethod
    def dependency_manifest(root: Path, sources: list[Path]) -> Path:
        manifest = root / "ninja-deps.txt"
        manifest.write_text(
            "\n".join(
                f"obj/{index}.o: #deps 1, deps mtime 0 (VALID)\n    {source.resolve()}"
                for index, source in enumerate(sources)
            )
            + "\n",
            encoding="utf-8",
        )
        return manifest

    def test_discovers_source_call_sites_in_stable_path_and_line_order(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        ports = Path(directory.name) / "src/ports"
        ports.mkdir(parents=True)
        source = ports / "SkFontHost_FreeType.cpp"
        source.write_text(
            "void first() {\n  if (FT_Load_Glyph(face, 1, flags)) {}\n}\n"
            "void second() { FT_Load_Glyph(face, 2, flags | extra); }\n",
            encoding="utf-8",
        )
        dependencies = self.dependency_manifest(Path(directory.name), [source])
        self.assertEqual(
            [
                {
                    "path": "src/ports/SkFontHost_FreeType.cpp",
                    "line": 2,
                    "expression": "FT_Load_Glyph(face, 1, flags)",
                },
                {
                    "path": "src/ports/SkFontHost_FreeType.cpp",
                    "line": 4,
                    "expression": "FT_Load_Glyph(face, 2, flags | extra)",
                },
            ],
            discover_glyph_load_calls(Path(directory.name), dependencies),
        )

    def test_rejects_an_unregistered_source_call_site(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        ports = Path(directory.name) / "src/ports"
        ports.mkdir(parents=True)
        source = ports / "SkFontHost_FreeType.cpp"
        source.write_text(
            "void unregistered() { FT_Load_Glyph(face, glyph, flags); }\n",
            encoding="utf-8",
        )
        dependencies = self.dependency_manifest(Path(directory.name), [source])
        with self.assertRaisesRegex(AuditError, "glyph-load path inventory differs"):
            glyph_load_inventory(
                Path(directory.name), dependencies, enforce_exact_closure=False
            )

    def test_rejects_an_unregistered_call_outside_the_font_host_directory(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        by_path: dict[str, list[str]] = {}
        for path, expression, _kind, _consumer in EXPECTED_GLYPH_LOAD_OCCURRENCES:
            by_path.setdefault(path, []).append(expression)
        sources = []
        for relative_path, expressions in by_path.items():
            source = root / relative_path
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(
                "\n".join(
                    f"void registered_{index}() {{ {expression}; }}"
                    for index, expression in enumerate(expressions)
                )
                + "\n",
                encoding="utf-8",
            )
            sources.append(source)
        unregistered = root / "src/gpu/UnregisteredGlyphLoad.cpp"
        unregistered.parent.mkdir(parents=True, exist_ok=True)
        unregistered.write_text(
            "void unregistered() { FT_Load_Glyph(face, glyph, flags); }\n",
            encoding="utf-8",
        )
        dependencies = self.dependency_manifest(root, [*sources, unregistered])

        with self.assertRaisesRegex(AuditError, "glyph-load path inventory differs"):
            glyph_load_inventory(root, dependencies, enforce_exact_closure=False)


class HermeticHarnessTest(unittest.TestCase):
    def test_exact_build_records_the_ninja_dependency_closure(self) -> None:
        repository = Path(__file__).resolve().parents[3]
        harness = (
            repository / "tools/renderer-exact-build-rehearsal.sh"
        ).read_text(encoding="utf-8")
        self.assertIn('"$RW_OUT" -t deps', harness)
        self.assertIn('"$RW_COMMANDS/ninja-deps.txt"', harness)

    def test_production_rehearsal_replays_the_source_inventory(self) -> None:
        repository = Path(__file__).resolve().parents[3]
        harness = (
            repository / "tools/renderer-production-text-rehearsal.sh"
        ).read_text(encoding="utf-8")
        self.assertIn("from audit_rehearsal import glyph_load_inventory", harness)
        self.assertIn("T215_EXACT_BUILD_GLYPH_SOURCE_INVENTORY_PASSED", harness)
        self.assertIn('"$RW_HARFBUZZ_ROOT/src/harfbuzz.cc"', harness)

    def test_tar_xz_inputs_use_the_pinned_python_runtime(self) -> None:
        repository = Path(__file__).resolve().parents[3]
        for relative_path in (
            "tools/renderer-exact-build-rehearsal.sh",
            "tools/renderer-production-text-rehearsal.sh",
        ):
            harness = (repository / relative_path).read_text(encoding="utf-8")
            with self.subTest(harness=relative_path):
                self.assertIn('readonly RW_BOOTSTRAP_PYTHON="/usr/local/bin/python3"', harness)
                self.assertIn("import lzma", harness)
                self.assertIn("stream_xz", harness)
                self.assertNotIn('tar --no-same-owner -xf "$RW_RUST_ARCHIVE"', harness)
                self.assertNotIn('tar --no-same-owner -xf "$llvm_archive"', harness)

    def test_zip_inputs_use_the_pinned_python_runtime(self) -> None:
        repository = Path(__file__).resolve().parents[3]
        harness = (
            repository / "tools/renderer-exact-build-rehearsal.sh"
        ).read_text(encoding="utf-8")
        self.assertIn("import zipfile", harness)
        self.assertIn("extract_zip", harness)
        self.assertNotIn("unzip -q", harness)

    def test_patch_application_uses_the_pinned_python_runtime(self) -> None:
        repository = Path(__file__).resolve().parents[3]
        harness = (
            repository / "tools/renderer-exact-build-rehearsal.sh"
        ).read_text(encoding="utf-8")
        self.assertIn("apply_exact_unified_diff.py", harness)
        self.assertNotIn("git apply", harness)

    def test_exact_environment_exposes_only_the_pinned_python_bin(self) -> None:
        repository = Path(__file__).resolve().parents[3]
        harness = (
            repository / "tools/renderer-exact-build-rehearsal.sh"
        ).read_text(encoding="utf-8")
        self.assertIn(
            'PATH="$RW_WORK/toolchain/bin:$RW_WORK/tools/gn:'
            '$RW_WORK/tools/ninja:/usr/local/bin:/usr/bin:/bin"',
            harness,
        )

    def test_harness_archive_guards_match_the_v3_lock(self) -> None:
        repository = Path(__file__).resolve().parents[3]
        lock = json.loads(
            (
                repository
                / ".scratch/renderweave-template-v1/renderer-spike/hermetic-build-lock-v3.json"
            ).read_text(encoding="utf-8")
        )
        inputs = {
            item["id"]: item
            for section in ("inputOverrides", "inputAdditions")
            for item in lock[section]
        }
        exact_harness = (
            repository / "tools/renderer-exact-build-rehearsal.sh"
        ).read_text(encoding="utf-8")
        production_harness = (
            repository / "tools/renderer-production-text-rehearsal.sh"
        ).read_text(encoding="utf-8")

        root_sha = inputs["renderer-root-inputs"]["sha256"].removeprefix("sha256:")
        crates_sha = inputs["renderer-crates-source"]["sha256"].removeprefix(
            "sha256:"
        )
        self.assertIn(root_sha, exact_harness)
        self.assertIn(root_sha, production_harness)
        self.assertIn(crates_sha, production_harness)


class ExactUnifiedDiffTest(unittest.TestCase):
    def test_preserves_source_context_endings_and_uses_patch_addition_endings(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        target = root / "sample.txt"
        target.write_bytes(b"alpha\r\nbeta one\r\ngamma\r\n")
        patch = (
            b"diff --git a/sample.txt b/sample.txt\n"
            b"--- a/sample.txt\n"
            b"+++ b/sample.txt\n"
            b"@@ -1,3 +1,4 @@\n"
            b" alpha\n"
            b"-beta one\n"
            b"+beta two\n"
            b"+inserted\n"
            b" gamma\n"
        )

        apply_exact_unified_diff(root, patch, frozenset({"sample.txt"}))

        self.assertEqual(
            b"alpha\r\nbeta two\ninserted\ngamma\r\n",
            target.read_bytes(),
        )

    def test_rejects_unregistered_target_before_writing(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        target = root / "sample.txt"
        target.write_bytes(b"before\n")
        patch = (
            b"diff --git a/sample.txt b/sample.txt\n"
            b"--- a/sample.txt\n"
            b"+++ b/sample.txt\n"
            b"@@ -1 +1 @@\n-before\n+after\n"
        )

        with self.assertRaisesRegex(PatchError, "not allowlisted"):
            apply_exact_unified_diff(root, patch, frozenset())

        self.assertEqual(b"before\n", target.read_bytes())

    def test_rejects_context_drift_before_writing(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        target = root / "sample.txt"
        target.write_bytes(b"drifted\n")
        patch = (
            b"diff --git a/sample.txt b/sample.txt\n"
            b"--- a/sample.txt\n"
            b"+++ b/sample.txt\n"
            b"@@ -1 +1 @@\n-before\n+after\n"
        )

        with self.assertRaisesRegex(PatchError, "source line differs"):
            apply_exact_unified_diff(root, patch, frozenset({"sample.txt"}))

        self.assertEqual(b"drifted\n", target.read_bytes())


class DynamicExportAuditTest(unittest.TestCase):
    def test_accepts_only_the_closed_exact_runtime_exports(self) -> None:
        output = (
            "stderr@GLIBC_2.2.5 B 3c4200 8\n"
            "_ZStplIcSt11char_traitsIcESaIcEENSt7__cxx1112basic_stringIT_T0_T1_EEPKS5_RKS8_ W 326759 63\n"
            "__libc_single_threaded@GLIBC_2.32 B 3c4208 1\n"
        )
        self.assertEqual(3, len(verify_dynamic_exports(output)))

    def test_rejects_an_unexpected_dynamic_export(self) -> None:
        with self.assertRaisesRegex(AuditError, "dynamic export set differs"):
            verify_dynamic_exports("unexpected T 100 1\n")


class ProbeContractTest(unittest.TestCase):
    def write_probe(self, value: dict[str, object]) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "probe.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_accepts_closed_passing_probe(self) -> None:
        probe = passing_probe()
        self.assertEqual(probe, verify_probe(self.write_probe(probe)))

    def test_rejects_unknown_probe_member(self) -> None:
        probe = passing_probe()
        probe["unexpected"] = True
        with self.assertRaisesRegex(AuditError, "keys differ"):
            verify_probe(self.write_probe(probe))

    def test_rejects_interpreter_execution_on_skia_path(self) -> None:
        probe = passing_probe()
        probe["skiaTricky"] = observation(interpreterCallCount=1)
        with self.assertRaisesRegex(AuditError, "executed TrueType bytecode"):
            verify_probe(self.write_probe(probe))

    def test_rejects_missing_required_load_flag_even_when_probe_counter_is_zero(self) -> None:
        probe = passing_probe()
        probe["skiaTricky"] = observation(
            invalidLoadCount=0,
            loadFlagsAnd=REQUIRED_LOAD_FLAGS & ~0x00008000,
        )
        with self.assertRaisesRegex(AuditError, "required load flags"):
            verify_probe(self.write_probe(probe))

    def test_rejects_forbidden_load_flag_even_when_probe_counter_is_zero(self) -> None:
        probe = passing_probe()
        probe["skiaCff"] = observation(
            invalidLoadCount=0,
            loadFlagsOr=REQUIRED_LOAD_FLAGS | 0x00000020,
        )
        with self.assertRaisesRegex(AuditError, "forbidden load flags"):
            verify_probe(self.write_probe(probe))


class RehearsalResultTest(unittest.TestCase):
    def test_result_is_reproducible_rehearsal_evidence_only(self) -> None:
        path = Path(__file__).with_name("rehearsal-result-v2.json")
        result = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(
            {
                "artifactVersion",
                "candidateId",
                "rehearsalConfigurationId",
                "status",
                "execution",
                "inputClosure",
                "candidateConfiguration",
                "identities",
                "probe",
                "elfAudit",
                "glyphLoadPathInventory",
                "negativeTests",
                "evidence",
                "boundary",
            },
            set(result),
        )
        self.assertEqual(
            "EXACT_CANDIDATE_REHEARSAL_PASSED",
            result["status"],
        )
        self.assertEqual(
            "rw-renderer-spike-linux-x86_64-v2-000003",
            result["candidateId"],
        )
        self.assertEqual(
            "rw-renderer-t213-exact-rehearsal-000002",
            result["rehearsalConfigurationId"],
        )
        self.assertEqual(2, result["execution"]["cleanRunCount"])
        self.assertTrue(result["identities"]["manifestByteIdenticalAcrossRuns"])
        self.assertTrue(result["identities"]["probeResultByteIdenticalAcrossRuns"])
        self.assertEqual([], result["candidateConfiguration"]["adaptersUsed"])
        self.assertEqual(
            "sha256:9fb58e637b9793149c108ac4cf97f04f71c29a22238a86ee6dd4b03cf0e7db52",
            result["candidateConfiguration"]["patchSha256"],
        )
        self.assertTrue(all(result["negativeTests"].values()))
        self.assertTrue(all(result["evidence"].values()))
        self.assertFalse(any(result["boundary"].values()))


if __name__ == "__main__":
    unittest.main()
