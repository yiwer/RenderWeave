from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


REQUIRED_FLAGS = (
    "FT_LOAD_NO_HINTING",
    "FT_LOAD_NO_AUTOHINT",
    "FT_LOAD_NO_BITMAP",
    "FT_LOAD_NO_SVG",
)
FORBIDDEN_FLAGS = ("FT_LOAD_FORCE_AUTOHINT", "FT_LOAD_COLOR")
DIRECT_GLYPH_LOAD = re.compile(r"(?<![A-Za-z0-9_])FT_Load_Glyph\s*\(")


class GlyphPolicyError(ValueError):
    pass


def require_token(text: str, token: str, subject: str) -> None:
    if token.startswith("FT_"):
        present = re.search(rf"(?<![A-Za-z0-9_]){re.escape(token)}(?![A-Za-z0-9_])", text)
    else:
        present = token in text
    if not present:
        raise GlyphPolicyError(f"{subject} is missing {token}")


def verify_native_policy(source: str) -> None:
    for token in REQUIRED_FLAGS:
        require_token(source, token, "native glyph policy")
    for token in FORBIDDEN_FLAGS:
        require_token(source, token, "native glyph policy")
    for token in (
        'extern "C" FT_Error __wrap_FT_Load_Glyph',
        "__real_FT_Load_Glyph(face, glyphIndex, flags)",
        'extern "C" FT_Error __wrap_FT_New_Library',
        "FT_Set_Debug_Hook",
        "FT_DEBUG_HOOK_TRUETYPE",
        "renderweaveRejectTrueTypeInterpreter",
        "finishGlyphPolicyObservation",
        "renderweave_glyph_policy_accepts_flags",
    ):
        require_token(source, token, "native glyph policy")
    if "return TT_RunIns(" in source:
        raise GlyphPolicyError("native glyph policy delegates to the TrueType interpreter")


def verify_build_script(source: str) -> None:
    require_token(
        source,
        'third_party/externals/freetype/include',
        "native adapter build",
    )
    for symbol in ("FT_Load_Glyph", "FT_New_Library"):
        require_token(
            source,
            f"cargo:rustc-link-arg=-Wl,--wrap={symbol}",
            "native adapter build",
        )
    compiled_sources = re.findall(
        r'\.arg\("([^"]+\.(?:c|cc|cpp|cxx))"\)', source
    )
    if compiled_sources != ["native/native_text_skia.cpp"]:
        raise GlyphPolicyError(
            f"native adapter compile-input inventory differs: {compiled_sources}"
        )


def verify_no_direct_glyph_load(source: str, subject: str) -> None:
    if DIRECT_GLYPH_LOAD.search(source):
        raise GlyphPolicyError(f"{subject} contains an unregistered FT_Load_Glyph call")


def verify_exact_rehearsal(source: str) -> None:
    for symbol in ("FT_Load_Glyph", "FT_New_Library"):
        require_token(
            source,
            f"-Wl,--wrap={symbol}",
            "exact production rehearsal",
        )
    require_token(source, "audit-glyph-policy", "exact production rehearsal")
    for token in (
        "glyph_load_inventory",
        "ninja-deps.txt",
        "native/native_text_skia.cpp",
        "harfbuzz.cc",
        "unregistered production FT_Load_Glyph call",
    ):
        require_token(source, token, "exact production rehearsal")


def verify_exact_build_rehearsal(source: str) -> None:
    require_token(source, '"$RW_COMMANDS/ninja-deps.txt"', "exact build rehearsal")
    require_token(source, '"$RW_OUT" -t deps', "exact build rehearsal")


def verify_inventory_authority(source: str) -> None:
    for token in (
        "EXPECTED_IMPLEMENTATION_PATH_DIGEST",
        "dependency_implementation_files",
        "EXPECTED_GLYPH_LOAD_OCCURRENCES",
        "discover_glyph_load_calls",
        "glyph_load_inventory",
        "allSourceCallSitesRegistered",
    ):
        require_token(source, token, "exact glyph-load inventory")


def verify_repository(repo: Path) -> dict[str, object]:
    native = (repo / "renderer/crates/engine/native/native_text_skia.cpp").read_text(
        encoding="utf-8"
    )
    build = (repo / "renderer/crates/engine/build.rs").read_text(encoding="utf-8")
    rehearsal = (repo / "tools/renderer-production-text-rehearsal.sh").read_text(
        encoding="utf-8"
    )
    inventory = (repo / "renderer/probes/t213/audit_rehearsal.py").read_text(
        encoding="utf-8"
    )
    exact_build = (repo / "tools/renderer-exact-build-rehearsal.sh").read_text(
        encoding="utf-8"
    )
    verify_native_policy(native)
    verify_no_direct_glyph_load(native, "native adapter")
    verify_build_script(build)
    verify_exact_rehearsal(rehearsal)
    verify_exact_build_rehearsal(exact_build)
    verify_inventory_authority(inventory)
    return {
        "status": "PASS_PRODUCTION_GLYPH_POLICY",
        "requiredLoadFlags": list(REQUIRED_FLAGS),
        "forbiddenLoadFlags": list(FORBIDDEN_FLAGS),
        "interposedSymbols": ["FT_Load_Glyph", "FT_New_Library"],
        "sourceInventory": "T213_EXACT_BUILD_DEPENDENCY_IMPLEMENTATION_FILES",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Verify the T215 production glyph policy.")
    parser.add_argument("--repo", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        print(json.dumps(verify_repository(args.repo.resolve()), sort_keys=True))
        return 0
    except (GlyphPolicyError, OSError) as error:
        print(f"T215_GLYPH_POLICY_FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
