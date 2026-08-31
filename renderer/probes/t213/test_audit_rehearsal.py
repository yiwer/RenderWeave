from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from audit_rehearsal import (
    AuditError,
    discover_glyph_load_calls,
    forbidden_isa_mnemonics,
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
            discover_glyph_load_calls(Path(directory.name)),
        )


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
