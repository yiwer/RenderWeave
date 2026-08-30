from __future__ import annotations

import hashlib
import json
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "tools" / "verify-renderer-tricky-font-fixture.py"
AUTHORITY = (
    ".scratch/renderweave-template-v1/renderer-spike/"
    "tricky-font-fixture-v1/authority-v1.json"
)
FONT = (
    ".scratch/renderweave-template-v1/renderer-spike/"
    "tricky-font-fixture-v1/renderweave-cpop-fixture-v1.ttf"
)
LICENSE = (
    ".scratch/renderweave-template-v1/renderer-spike/"
    "tricky-font-fixture-v1/LICENSE-0BSD.txt"
)
PROVENANCE = (
    ".scratch/renderweave-template-v1/renderer-spike/"
    "tricky-font-fixture-v1/provenance-v1.json"
)
RECIPE = "tools/generate-renderer-tricky-font-fixture.py"
FILES = [AUTHORITY, FONT, LICENSE, PROVENANCE, RECIPE]
SFNT_CHECKSUM_MAGIC = 0xB1B0AFBA


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def sfnt_checksum(data: bytes) -> int:
    padded = data + b"\0" * ((-len(data)) % 4)
    return sum(struct.unpack(f">{len(padded) // 4}I", padded)) & 0xFFFFFFFF


class TrickyFontFixtureVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary.name)
        for relative in FILES:
            target = self.repo / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def read_json(self, relative: str) -> dict:
        return json.loads((self.repo / relative).read_text(encoding="utf-8"))

    def write_json(self, relative: str, value: dict) -> None:
        (self.repo / relative).write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )

    def run_verifier(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VERIFY), "--repo", str(self.repo)],
            cwd=self.repo,
            capture_output=True,
            text=True,
            check=False,
        )

    def refresh_font_binding(self) -> None:
        font = (self.repo / FONT).read_bytes()
        authority = self.read_json(AUTHORITY)
        authority["fixture"]["sha256"] = digest(font)
        authority["fixture"]["byteLength"] = len(font)
        self.write_json(AUTHORITY, authority)

    def refresh_source_binding(self, relative: str, member: str) -> None:
        authority = self.read_json(AUTHORITY)
        authority["authority"][member] = digest((self.repo / relative).read_bytes())
        self.write_json(AUTHORITY, authority)

    @staticmethod
    def table_record(font: bytes, wanted: bytes) -> tuple[int, int, int]:
        count = struct.unpack_from(">H", font, 4)[0]
        for index in range(count):
            position = 12 + index * 16
            tag, _, offset, length = struct.unpack_from(">4sIII", font, position)
            if tag == wanted:
                return position, offset, length
        raise AssertionError(f"missing table {wanted!r}")

    def replace_family_classifier_token(self) -> None:
        font = bytearray((self.repo / FONT).read_bytes())
        name_position, name_offset, name_length = self.table_record(font, b"name")
        old_family = "RenderWeave cpop Fixture"
        new_family = "RenderWeave xxxx Fixture"
        old = old_family.encode("utf-16-be")
        new = new_family.encode("utf-16-be")
        name = bytes(font[name_offset:name_offset + name_length])
        self.assertEqual(name.count(old), 2)
        font[name_offset:name_offset + name_length] = name.replace(old, new)
        new_name = bytes(font[name_offset:name_offset + name_length])
        struct.pack_into(">I", font, name_position + 4, sfnt_checksum(new_name))

        _, head_offset, _ = self.table_record(font, b"head")
        struct.pack_into(">I", font, head_offset + 8, 0)
        adjustment = (SFNT_CHECKSUM_MAGIC - sfnt_checksum(bytes(font))) & 0xFFFFFFFF
        struct.pack_into(">I", font, head_offset + 8, adjustment)
        self.assertEqual(sfnt_checksum(bytes(font)), SFNT_CHECKSUM_MAGIC)
        (self.repo / FONT).write_bytes(font)

        provenance = self.read_json(PROVENANCE)
        provenance["syntheticDesign"]["familyName"] = new_family
        self.write_json(PROVENANCE, provenance)

        authority = self.read_json(AUTHORITY)
        authority["authority"]["provenanceSha256"] = digest(
            (self.repo / PROVENANCE).read_bytes()
        )
        authority["fixture"]["familyName"] = new_family
        authority["fixture"]["sha256"] = digest(bytes(font))
        authority["fixture"]["byteLength"] = len(font)
        name_binding = next(
            item for item in authority["fixture"]["tables"] if item["tag"] == "name"
        )
        name_binding["checksumHex"] = f"{sfnt_checksum(new_name):08x}"
        self.write_json(AUTHORITY, authority)

    def test_repository_fixture_is_reproducible_and_fail_closed(self) -> None:
        completed = self.run_verifier()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(
            report["status"],
            "PASS_PORTABLE_TRICKY_FONT_FIXTURE_SOURCE_VERIFIED_BUILD_PENDING",
        )
        self.assertTrue(report["reproducible"])
        self.assertEqual(report["classification"]["path"], "FAMILY_NAME_SUBSTRING")
        self.assertEqual(report["classification"]["matchedToken"], "cpop")
        self.assertFalse(report["boundary"]["exactBuiltTargetObserved"])
        self.assertFalse(report["boundary"]["runtimeBytecodeNonExecutionProven"])
        self.assertFalse(report["boundary"]["certified"])

    def test_changed_fixture_bytes_fail(self) -> None:
        path = self.repo / FONT
        font = bytearray(path.read_bytes())
        font[-1] ^= 1
        path.write_bytes(font)
        completed = self.run_verifier()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("FILE_DIGEST", completed.stderr)

    def test_stale_fixture_digest_fails(self) -> None:
        authority = self.read_json(AUTHORITY)
        authority["fixture"]["sha256"] = "sha256:" + "0" * 64
        self.write_json(AUTHORITY, authority)
        completed = self.run_verifier()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("FILE_DIGEST", completed.stderr)

    def test_malformed_table_offset_fails_after_digest_refresh(self) -> None:
        path = self.repo / FONT
        font = bytearray(path.read_bytes())
        cmap_position, cmap_offset, _ = self.table_record(font, b"cmap")
        struct.pack_into(">I", font, cmap_position + 8, cmap_offset + 2)
        path.write_bytes(font)
        self.refresh_font_binding()
        completed = self.run_verifier()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("SFNT_TABLE_ALIGNMENT", completed.stderr)

    def test_missing_license_fails(self) -> None:
        (self.repo / LICENSE).unlink()
        completed = self.run_verifier()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("PATH_MISSING", completed.stderr)

    def test_missing_provenance_fails(self) -> None:
        (self.repo / PROVENANCE).unlink()
        completed = self.run_verifier()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("PATH_MISSING", completed.stderr)

    def test_third_party_font_provenance_overclaim_fails(self) -> None:
        provenance = self.read_json(PROVENANCE)
        provenance["thirdPartyFontBytes"] = True
        self.write_json(PROVENANCE, provenance)
        self.refresh_source_binding(PROVENANCE, "provenanceSha256")
        completed = self.run_verifier()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("PROVENANCE_THIRD_PARTY", completed.stderr)

    def test_fixture_without_exact_classifier_token_fails(self) -> None:
        self.replace_family_classifier_token()
        completed = self.run_verifier()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("CLASSIFICATION_TOKEN_ABSENT", completed.stderr)

    def test_lifecycle_overclaim_fails(self) -> None:
        authority = self.read_json(AUTHORITY)
        authority["boundary"]["certified"] = True
        self.write_json(AUTHORITY, authority)
        completed = self.run_verifier()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("BOUNDARY_OVERCLAIM", completed.stderr)


if __name__ == "__main__":
    unittest.main()
