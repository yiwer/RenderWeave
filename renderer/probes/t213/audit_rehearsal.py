from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
from pathlib import Path
from typing import Any


CANDIDATE_ID = "rw-renderer-spike-linux-x86_64-v2-000003"
REHEARSAL_CONFIGURATION_ID = "rw-renderer-t213-exact-rehearsal-000002"
REQUIRED_LOAD_FLAGS = 0x0100800A
FORBIDDEN_LOAD_FLAGS = 0x00100020
EXPECTED_NEEDED = ["libc.so.6", "libgcc_s.so.1", "libm.so.6", "libstdc++.so.6"]
REQUIRED_SYMBOLS = [
    "FT_Load_Glyph",
    "TT_RunIns",
    "__wrap_FT_Load_Glyph",
    "__wrap_FT_New_Library",
    "__wrap_FT_Open_Face",
    "cff_driver_class",
    "ft_smooth_renderer_class",
    "psaux_module_class",
    "psnames_module_class",
    "sfnt_module_class",
    "tt_driver_class",
]
FORBIDDEN_SYMBOL_TOKENS = [
    "autofit_module_class",
    "bdf_driver_class",
    "ft_bitmap_sdf_renderer_class",
    "ft_raster1_renderer_class",
    "ft_sdf_renderer_class",
    "ft_svg_renderer_class",
    "fontconfig",
    "pfr_driver_class",
    "pcf_driver_class",
    "pshinter_module_class",
    "t1cid_driver_class",
    "t42_driver_class",
    "type1_driver_class",
    "winfnt_driver_class",
]
FORBIDDEN_DISPATCH_SYMBOL_TOKENS = [
    "SkCpu::CacheRuntimeFeatures",
    "SkCpu::Supports",
    "__cpu_indicator_init",
    "__cpu_model",
]
FORBIDDEN_MNEMONICS = {
    "andn",
    "bextr",
    "blsi",
    "blsmsk",
    "blsr",
    "bzhi",
    "cpuid",
    "lzcnt",
    "movbe",
    "mulx",
    "pdep",
    "pext",
    "rdrand",
    "rdseed",
    "rorx",
    "sarx",
    "shlx",
    "shrx",
    "tzcnt",
    "xgetbv",
}
PROBE_KEYS = {
    "artifactVersion",
    "candidateId",
    "rehearsalConfigurationId",
    "status",
    "controlNoAutoHint",
    "controlRequired",
    "skiaTricky",
    "skiaCff",
}
OBSERVATION_KEYS = {
    "openFaceCount",
    "trickyFaceCount",
    "loadCount",
    "invalidLoadCount",
    "interpreterCallCount",
    "loadFlagsOr",
    "loadFlagsAnd",
}
EXPECTED_DYNAMIC_EXPORTS = [
    {
        "name": "_ZStplIcSt11char_traitsIcESaIcEENSt7__cxx1112basic_stringIT_T0_T1_EEPKS5_RKS8_",
        "type": "W",
    },
    {"name": "__libc_single_threaded@GLIBC_2.32", "type": "B"},
    {"name": "stderr@GLIBC_2.2.5", "type": "B"},
]
EXPECTED_IMPLEMENTATION_FILE_COUNT = 596
EXPECTED_IMPLEMENTATION_PATH_DIGEST = (
    "sha256:794a3ef6c0a031c828363a4c271e6137a665815d46ae4475f5262a132045f539"
)
IMPLEMENTATION_SUFFIXES = {".c", ".cc", ".cpp", ".cxx"}
GLYPH_LOAD_PATTERN = re.compile(
    rb"(?<![A-Za-z0-9_])FT_Load_Glyph[ \t\r\n]*\("
)
EXPECTED_GLYPH_LOAD_OCCURRENCES = [
    (
        "src/ports/SkFontHost_FreeType.cpp",
        "FT_Load_Glyph(fFace, glyph_id, fLoadGlyphFlags)",
        "CALL",
        "letter-cbox-metrics",
    ),
    (
        "src/ports/SkFontHost_FreeType.cpp",
        "FT_Load_Glyph(fFace, layerGlyphIndex, flags)",
        "CALL",
        "color-layer-metrics",
    ),
    (
        "src/ports/SkFontHost_FreeType.cpp",
        "FT_Load_Glyph(fFace, glyph.getGlyphID(), fLoadGlyphFlags | FT_LOAD_BITMAP_METRICS_ONLY)",
        "CALL",
        "base-glyph-metrics",
    ),
    (
        "src/ports/SkFontHost_FreeType.cpp",
        "FT_Load_Glyph(fFace, glyph.getGlyphID(), fLoadGlyphFlags)",
        "CALL",
        "svg-image",
    ),
    (
        "src/ports/SkFontHost_FreeType.cpp",
        "FT_Load_Glyph(fFace, glyph.getGlyphID(), fLoadGlyphFlags)",
        "CALL",
        "image",
    ),
    (
        "src/ports/SkFontHost_FreeType.cpp",
        "FT_Load_Glyph(fFace, glyph.getGlyphID(), fLoadGlyphFlags)",
        "CALL",
        "svg-drawable",
    ),
    (
        "src/ports/SkFontHost_FreeType.cpp",
        "FT_Load_Glyph(fFace, glyphID, flags)",
        "CALL",
        "path",
    ),
    (
        "src/ports/SkFontHost_FreeType.cpp",
        "FT_Load_Glyph(fFace, gid, fLoadGlyphFlags)",
        "CALL",
        "bitmap-embolden",
    ),
    (
        "src/ports/SkFontHost_FreeType_common.cpp",
        "FT_Load_Glyph(face, glyphID, flags)",
        "CALL",
        "static-path",
    ),
    (
        "src/ports/SkFontHost_FreeType_common.cpp",
        "FT_Load_Glyph(face, glyphID, flags)",
        "CALL",
        "common-glyph-data",
    ),
    (
        "third_party/externals/freetype/src/base/ftadvanc.c",
        "FT_Load_Glyph( face, gindex + nn, flags )",
        "CALL",
        "advance-fallback",
    ),
    (
        "third_party/externals/freetype/src/base/ftobjs.c",
        "FT_Load_Glyph( FT_Face face, FT_UInt glyph_index, FT_Int32 load_flags )",
        "DEFINITION",
        "freetype-api-entrypoint",
    ),
    (
        "third_party/externals/freetype/src/base/ftobjs.c",
        "FT_Load_Glyph( face, glyph_index, load_flags )",
        "CALL",
        "load-char-delegation",
    ),
    (
        "third_party/externals/freetype/src/base/ftobjs.c",
        "FT_Load_Glyph( face, glyph_index, load_flags )",
        "CALL",
        "color-layer-recursion",
    ),
]
class AuditError(RuntimeError):
    pass


def sha256_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def file_identity(path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    return {
        "path": path.as_posix(),
        "sha256": sha256_bytes(data),
        "byteLength": len(data),
    }


def run(command: list[str]) -> bytes:
    completed = subprocess.run(command, check=False, capture_output=True)
    if completed.returncode != 0:
        raise AuditError(
            f"command failed ({completed.returncode}): {' '.join(command)}\n"
            + completed.stderr.decode("utf-8", errors="replace")
        )
    return completed.stdout


def exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        raise AuditError(
            f"{label} keys differ: missing={sorted(expected - actual)} "
            f"unknown={sorted(actual - expected)}"
        )


def verify_probe(path: Path) -> dict[str, Any]:
    probe = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(probe, dict):
        raise AuditError("probe result must be an object")
    exact_keys(probe, PROBE_KEYS, "probe")
    if probe["artifactVersion"] != "renderweave-renderer-instrumented-probe/1.2":
        raise AuditError("unexpected probe artifactVersion")
    if (
        probe["candidateId"] != CANDIDATE_ID
        or probe["rehearsalConfigurationId"] != REHEARSAL_CONFIGURATION_ID
        or probe["status"] != "PASS_EXACT_CANDIDATE_REHEARSAL"
    ):
        raise AuditError("probe identity or status differs")
    for name in ["controlNoAutoHint", "controlRequired", "skiaTricky", "skiaCff"]:
        value = probe[name]
        if not isinstance(value, dict):
            raise AuditError(f"probe {name} must be an object")
        exact_keys(value, OBSERVATION_KEYS, f"probe {name}")
        if any(not isinstance(item, int) or item < 0 for item in value.values()):
            raise AuditError(f"probe {name} contains a non-counter value")
    if probe["controlNoAutoHint"]["interpreterCallCount"] <= 0:
        raise AuditError("NO_HINTING-only control did not execute TrueType bytecode")
    if probe["controlRequired"]["interpreterCallCount"] != 0:
        raise AuditError("required direct flags executed TrueType bytecode")
    for name in ["controlRequired", "skiaTricky", "skiaCff"]:
        value = probe[name]
        if value["loadCount"] <= 0:
            raise AuditError(f"{name} observed no FT_Load_Glyph calls")
        if value["invalidLoadCount"] != 0:
            raise AuditError(f"{name} observed an invalid FT_Load_Glyph call")
        if value["loadFlagsAnd"] & REQUIRED_LOAD_FLAGS != REQUIRED_LOAD_FLAGS:
            raise AuditError(f"{name} omitted required load flags")
        if value["loadFlagsOr"] & FORBIDDEN_LOAD_FLAGS:
            raise AuditError(f"{name} observed forbidden load flags")
        if value["interpreterCallCount"] != 0:
            raise AuditError(f"{name} executed TrueType bytecode")
    return probe


def dependency_implementation_files(
    source_root: Path, dependency_manifest: Path
) -> list[Path]:
    source_root = source_root.resolve()
    build_root = source_root / "out/T213"
    files: set[Path] = set()
    for raw_line in dependency_manifest.read_text(encoding="utf-8").splitlines():
        if not raw_line.startswith("    "):
            continue
        raw_path = Path(raw_line.strip())
        path = raw_path if raw_path.is_absolute() else build_root / raw_path
        path = path.resolve()
        if path.suffix.lower() not in IMPLEMENTATION_SUFFIXES:
            continue
        try:
            path.relative_to(source_root)
        except ValueError as error:
            raise AuditError(
                f"compiled implementation source escapes exact source root: {path}"
            ) from error
        if not path.is_file():
            raise AuditError(f"compiled implementation source is absent: {path}")
        files.add(path)
    if not files:
        raise AuditError("exact build dependency manifest contains no implementation files")
    return sorted(files, key=lambda item: item.relative_to(source_root).as_posix())


def implementation_path_digest(source_root: Path, files: list[Path]) -> str:
    digest = hashlib.sha256()
    digest.update(b"renderweave-exact-build-implementation-files/1\0")
    for path in files:
        relative = path.relative_to(source_root).as_posix().encode("utf-8")
        digest.update(struct.pack(">Q", len(relative)))
        digest.update(relative)
    return "sha256:" + digest.hexdigest()


def discover_glyph_load_calls(
    source_root: Path, dependency_manifest: Path
) -> list[dict[str, Any]]:
    source_root = source_root.resolve()
    calls: list[dict[str, Any]] = []
    for path in dependency_implementation_files(source_root, dependency_manifest):
        source = path.read_bytes()
        for match in GLYPH_LOAD_PATTERN.finditer(source):
            start = match.start()
            open_parenthesis = source.find(b"(", start, match.end())
            depth = 0
            end = None
            for index in range(open_parenthesis, len(source)):
                if source[index] == ord("("):
                    depth += 1
                elif source[index] == ord(")"):
                    depth -= 1
                    if depth == 0:
                        end = index + 1
                        break
            if end is None:
                raise AuditError(
                    "unbalanced FT_Load_Glyph occurrence: "
                    f"{path}:{source.count(b'\n', 0, start) + 1}"
                )
            expression_bytes = re.sub(rb"\s+", b" ", source[start:end])
            try:
                expression = expression_bytes.decode("ascii")
            except UnicodeDecodeError as error:
                raise AuditError(
                    f"non-ASCII FT_Load_Glyph occurrence: {path}"
                ) from error
            calls.append(
                {
                    "path": path.relative_to(source_root).as_posix(),
                    "line": source.count(b"\n", 0, start) + 1,
                    "expression": expression,
                }
            )
    return calls


def glyph_load_inventory(
    source_root: Path,
    dependency_manifest: Path,
    *,
    enforce_exact_closure: bool = True,
) -> dict[str, Any]:
    source_root = source_root.resolve()
    implementation_files = dependency_implementation_files(
        source_root, dependency_manifest
    )
    path_digest = implementation_path_digest(source_root, implementation_files)
    if (
        enforce_exact_closure
        and len(implementation_files) != EXPECTED_IMPLEMENTATION_FILE_COUNT
    ):
        raise AuditError(
            "exact build implementation-file count differs: "
            f"expected={EXPECTED_IMPLEMENTATION_FILE_COUNT} "
            f"actual={len(implementation_files)}"
        )
    if enforce_exact_closure and path_digest != EXPECTED_IMPLEMENTATION_PATH_DIGEST:
        raise AuditError(
            "exact build implementation-path digest differs: "
            f"expected={EXPECTED_IMPLEMENTATION_PATH_DIGEST} actual={path_digest}"
        )
    discovered = discover_glyph_load_calls(source_root, dependency_manifest)
    actual = [(item["path"], item["expression"]) for item in discovered]
    expected = [
        (path, expression)
        for path, expression, _kind, _consumer in EXPECTED_GLYPH_LOAD_OCCURRENCES
    ]
    if actual != expected:
        raise AuditError(
            f"glyph-load path inventory differs: expected={expected} actual={actual}"
        )
    call_sites = []
    definitions = []
    for item, (_, _, kind, consumer) in zip(
        discovered, EXPECTED_GLYPH_LOAD_OCCURRENCES
    ):
        target = call_sites if kind == "CALL" else definitions
        target.append({**item, "consumer": consumer})
    return {
        "coverage": "all-exact-build-dependency-implementation-files",
        "implementationFileCount": len(implementation_files),
        "implementationPathDigest": path_digest,
        "sourceCallSiteCount": len(call_sites),
        "sourceDefinitionCount": len(definitions),
        "allSourceCallSitesRegistered": True,
        "runtimeEnforcement": "link-time FT_Load_Glyph wrapper rejects every observed invalid flag set",
        "callSites": call_sites,
        "definitions": definitions,
    }


def verify_dynamic_exports(output: str) -> list[dict[str, str]]:
    exports = []
    for line in output.splitlines():
        if not line.strip():
            continue
        fields = line.split()
        if len(fields) < 2:
            raise AuditError(f"unrecognized dynamic export line: {line}")
        exports.append({"name": fields[0], "type": fields[1]})
    exports.sort(key=lambda item: item["name"])
    expected = sorted(EXPECTED_DYNAMIC_EXPORTS, key=lambda item: item["name"])
    if exports != expected:
        raise AuditError(
            f"dynamic export set differs: expected={expected} actual={exports}"
        )
    return exports


def dynamic_export_audit(nm: str, binary: Path) -> dict[str, Any]:
    output = run([nm, "-D", "--defined-only", "--format=posix", str(binary)]).decode(
        "utf-8", errors="strict"
    )
    return {
        "exports": verify_dynamic_exports(output),
        "dynamicSymbolTableSha256": sha256_bytes(output.encode("utf-8")),
    }


def source_tree_identity(root: Path) -> dict[str, Any]:
    digest = hashlib.sha256()
    digest.update(b"renderweave-renderer-source-tree/1\0")
    count = 0
    total = 0
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        if not path.is_file() or "out" in path.relative_to(root).parts:
            continue
        relative = path.relative_to(root).as_posix().encode("utf-8")
        data = path.read_bytes()
        digest.update(struct.pack(">Q", len(relative)))
        digest.update(relative)
        digest.update(struct.pack(">Q", len(data)))
        digest.update(hashlib.sha256(data).digest())
        count += 1
        total += len(data)
    return {
        "algorithm": "renderweave-renderer-source-tree/1",
        "sha256": "sha256:" + digest.hexdigest(),
        "fileCount": count,
        "byteLength": total,
        "excludedTopLevelPaths": ["out"],
    }


def elf_sections(binary: Path) -> list[dict[str, Any]]:
    data = binary.read_bytes()
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise AuditError("probe is not ELF64 little-endian")
    section_offset = struct.unpack_from("<Q", data, 40)[0]
    section_entry_size = struct.unpack_from("<H", data, 58)[0]
    section_count = struct.unpack_from("<H", data, 60)[0]
    names_index = struct.unpack_from("<H", data, 62)[0]
    if section_entry_size < 64 or section_count == 0 or names_index >= section_count:
        raise AuditError("invalid ELF section table")

    def header(index: int) -> tuple[int, int, int, int]:
        offset = section_offset + index * section_entry_size
        if offset + 64 > len(data):
            raise AuditError("ELF section header exceeds file")
        name_offset, section_type = struct.unpack_from("<II", data, offset)
        file_offset, byte_length = struct.unpack_from("<QQ", data, offset + 24)
        return name_offset, section_type, file_offset, byte_length

    _, _, names_offset, names_length = header(names_index)
    names = data[names_offset : names_offset + names_length]
    sections: list[dict[str, Any]] = []
    for index in range(section_count):
        name_offset, section_type, file_offset, byte_length = header(index)
        end = names.find(b"\0", name_offset)
        if end < 0:
            raise AuditError("unterminated ELF section name")
        name = names[name_offset:end].decode("utf-8", errors="strict")
        content = b"" if section_type == 8 else data[file_offset : file_offset + byte_length]
        if section_type != 8 and len(content) != byte_length:
            raise AuditError(f"ELF section exceeds file: {name}")
        sections.append(
            {
                "index": index,
                "name": name,
                "type": section_type,
                "byteLength": byte_length,
                "contentSha256": sha256_bytes(content),
            }
        )
    return sections


def dynamic_dependencies(readelf: str, binary: Path) -> list[str]:
    output = run([readelf, "-d", str(binary)]).decode("utf-8")
    needed = sorted(re.findall(r"Shared library: \[([^]]+)]", output))
    if needed != EXPECTED_NEEDED:
        raise AuditError(f"dynamic dependency set differs: {needed}")
    return needed


def runtime_identities(binary: Path) -> list[dict[str, Any]]:
    output = run(["ldd", str(binary)]).decode("utf-8")
    paths: dict[str, Path] = {}
    for raw_line in output.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("linux-vdso.so.1"):
            continue
        if "=>" in line:
            soname, remainder = line.split("=>", 1)
            resolved = remainder.strip().split(" ", 1)[0]
        elif line.startswith("/"):
            resolved = line.split(" ", 1)[0]
            soname = Path(resolved).name
        else:
            raise AuditError(f"unrecognized ldd line: {line}")
        path = Path(resolved)
        if not path.is_file():
            raise AuditError(f"ldd path is absent: {path}")
        paths[soname.strip()] = path
    expected = set(EXPECTED_NEEDED) | {"ld-linux-x86-64.so.2"}
    if set(paths) != expected:
        raise AuditError(f"runtime dependency resolution differs: {sorted(paths)}")
    return [
        {"soname": soname, **file_identity(path.resolve())}
        for soname, path in sorted(paths.items())
    ]


def symbol_audit(nm: str, binary: Path) -> dict[str, Any]:
    output = run([nm, "-C", "--defined-only", str(binary)]).decode(
        "utf-8", errors="replace"
    )
    missing = [symbol for symbol in REQUIRED_SYMBOLS if symbol not in output]
    forbidden = [token for token in FORBIDDEN_SYMBOL_TOKENS if token.lower() in output.lower()]
    dispatch = [token for token in FORBIDDEN_DISPATCH_SYMBOL_TOKENS if token in output]
    if missing or forbidden or dispatch:
        raise AuditError(
            f"symbol audit failed: missing={missing} forbidden={forbidden} dispatch={dispatch}"
        )
    return {
        "requiredSymbols": REQUIRED_SYMBOLS,
        "forbiddenSymbolsObserved": [],
        "runtimeDispatchSymbolsObserved": [],
        "symbolTableSha256": sha256_bytes(output.encode("utf-8")),
    }


def forbidden_isa_mnemonics(mnemonics: set[str]) -> tuple[list[str], list[str]]:
    vex = sorted(
        value
        for value in mnemonics
        if value.startswith("v") and value not in {"verr", "verw"}
    )
    opmask = {value for value in mnemonics if value.startswith("k")}
    forbidden = sorted((mnemonics & FORBIDDEN_MNEMONICS) | opmask)
    return vex, forbidden


def isa_audit(objdump: str, binary: Path) -> dict[str, Any]:
    output = run(
        [
            objdump,
            "--disassemble",
            "--no-show-raw-insn",
            "--x86-asm-syntax=intel",
            str(binary),
        ]
    )
    mnemonics: set[str] = set()
    for line in output.decode("utf-8", errors="replace").splitlines():
        match = re.match(r"^\s*[0-9a-f]+:\s+([a-z][a-z0-9.]*)\b", line)
        if match:
            mnemonics.add(match.group(1))
    vex, forbidden = forbidden_isa_mnemonics(mnemonics)
    if vex or forbidden:
        raise AuditError(f"ISA audit failed: vex={vex} forbidden={forbidden}")
    return {
        "target": "x86-64-v2",
        "instructionMnemonicCount": len(mnemonics),
        "vexOrEvexMnemonicsObserved": [],
        "v3OrV4MnemonicsObserved": [],
        "disassemblySha256": sha256_bytes(output),
    }


def command_identities(root: Path) -> list[dict[str, Any]]:
    expected = [
        "gn-generate.txt",
        "ninja-build.txt",
        "ninja-commands.txt",
        "ninja-deps.txt",
        "probe-link.txt",
    ]
    identities = []
    for name in expected:
        path = root / name
        if not path.is_file():
            raise AuditError(f"missing command record: {name}")
        identities.append(file_identity(path))
    return identities


def generated_identities(source_root: Path, binary: Path) -> list[dict[str, Any]]:
    paths = [
        source_root / "out/T213/args.gn",
        source_root / "out/T213/build.ninja",
        source_root / "out/T213/toolchain.ninja",
        source_root / "out/T213/libskia.a",
        source_root / "out/T213/libfreetype2.a",
        source_root / "out/T213/libskcms.a",
        binary,
    ]
    return [file_identity(path) for path in paths]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Audit one exact T213 rehearsal output.")
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--probe-json", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--commands", type=Path, required=True)
    parser.add_argument("--toolchain-bin", type=Path, required=True)
    parser.add_argument("--gn", type=Path, required=True)
    parser.add_argument("--ninja", type=Path, required=True)
    parser.add_argument("--tricky-font", type=Path, required=True)
    parser.add_argument("--cff-font", type=Path, required=True)
    parser.add_argument("--args-gn", type=Path, required=True)
    parser.add_argument("--patch", type=Path, required=True)
    parser.add_argument("--ftoption", type=Path, required=True)
    parser.add_argument("--ftmodule", type=Path, required=True)
    parser.add_argument("--bundle-inventory", type=Path, required=True)
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        probe = verify_probe(args.probe_json)
        binary = args.binary.resolve()
        source_root = args.source_root.resolve()
        toolchain = args.toolchain_bin.resolve()
        manifest = {
            "artifactVersion": "renderweave-renderer-build-rehearsal/1.2",
            "candidateId": CANDIDATE_ID,
            "rehearsalConfigurationId": REHEARSAL_CONFIGURATION_ID,
            "status": "EXACT_CANDIDATE_REHEARSAL_PASSED",
            "target": {
                "operatingSystem": "linux",
                "architecture": "amd64",
                "minimumIsa": "x86-64-v2",
                "virtualized": True,
                "networkDuringBuild": "DISABLED_BY_CONTAINER",
            },
            "inputClosure": {
                "inventory": file_identity(args.bundle_inventory.resolve()),
                "offlineBundleOnly": True,
                "hostRepositoryBuildInputsUsed": False,
            },
            "candidateConfiguration": {
                "adaptersUsed": [],
                "applicationOrder": "native FreeType headers, exact candidate v3 headers, exact Skia patch, exact GN args",
                "patch": file_identity(args.patch.resolve()),
                "optionsHeader": file_identity(args.ftoption.resolve()),
                "modulesHeader": file_identity(args.ftmodule.resolve()),
                "gnArgs": file_identity(args.args_gn.resolve()),
            },
            "executor": file_identity(args.harness.resolve()),
            "sourceTree": source_tree_identity(source_root),
            "fixtures": [file_identity(args.tricky_font), file_identity(args.cff_font)],
            "tools": [
                file_identity(toolchain / "clang"),
                file_identity(toolchain / "ld.lld"),
                file_identity(args.gn.resolve()),
                file_identity(args.ninja.resolve()),
            ],
            "commands": command_identities(args.commands.resolve()),
            "generated": generated_identities(source_root, binary),
            "elf": {
                "binary": file_identity(binary),
                "sections": elf_sections(binary),
                "needed": dynamic_dependencies(str(toolchain / "llvm-readelf"), binary),
                "resolvedRuntime": runtime_identities(binary),
            },
            "symbols": symbol_audit(str(toolchain / "llvm-nm"), binary),
            "dynamicExports": dynamic_export_audit(
                str(toolchain / "llvm-nm"), binary
            ),
            "glyphLoadPaths": glyph_load_inventory(
                source_root, args.commands.resolve() / "ninja-deps.txt"
            ),
            "isa": isa_audit(str(toolchain / "llvm-objdump"), binary),
            "probe": probe,
            "boundary": {
                "certified": False,
                "ready": False,
                "physicalLinuxReplayComplete": False,
                "rendererExactOutputRecordIssuanceAllowed": False,
                "ticket19MayClose": False,
            },
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        print(json.dumps({
            "status": manifest["status"],
            "binarySha256": manifest["elf"]["binary"]["sha256"],
            "sourceTreeSha256": manifest["sourceTree"]["sha256"],
            "manifestSha256": sha256_bytes(args.output.read_bytes()),
        }, sort_keys=True))
        return 0
    except (AuditError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"T213_AUDIT_FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
