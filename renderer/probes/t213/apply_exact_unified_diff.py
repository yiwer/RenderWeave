from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


class PatchError(ValueError):
    pass


@dataclass(frozen=True)
class Hunk:
    old_start: int
    old_count: int
    new_start: int
    new_count: int
    lines: tuple[bytes, ...]


@dataclass(frozen=True)
class FilePatch:
    path: str
    hunks: tuple[Hunk, ...]


HUNK_HEADER = re.compile(
    rb"@@ -([0-9]+)(?:,([0-9]+))? \+([0-9]+)(?:,([0-9]+))? @@(?: .*)?\n?$"
)


def _without_line_ending(line: bytes) -> bytes:
    if line.endswith(b"\r\n"):
        return line[:-2]
    if line.endswith(b"\n"):
        return line[:-1]
    return line


def _decode_path(raw: bytes, prefix: bytes) -> str:
    value = raw.rstrip(b"\r\n")
    if not value.startswith(prefix):
        raise PatchError(f"patch path lacks {prefix.decode('ascii')} prefix")
    try:
        decoded = value[len(prefix) :].decode("utf-8")
    except UnicodeDecodeError as error:
        raise PatchError("patch path is not UTF-8") from error
    path = PurePosixPath(decoded)
    if (
        not decoded
        or "\\" in decoded
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise PatchError(f"unsafe patch path: {decoded!r}")
    return path.as_posix()


def _parse_hunk_header(line: bytes) -> tuple[int, int, int, int]:
    match = HUNK_HEADER.fullmatch(line)
    if match is None:
        raise PatchError(f"invalid hunk header: {line!r}")
    old_start = int(match.group(1))
    old_count = int(match.group(2) or b"1")
    new_start = int(match.group(3))
    new_count = int(match.group(4) or b"1")
    return old_start, old_count, new_start, new_count


def _parse_patch(data: bytes) -> tuple[FilePatch, ...]:
    lines = data.splitlines(keepends=True)
    patches: list[FilePatch] = []
    index = 0
    while index < len(lines):
        header = lines[index].rstrip(b"\r\n").split(b" ")
        if len(header) != 4 or header[:2] != [b"diff", b"--git"]:
            raise PatchError(f"expected diff header at line {index + 1}")
        old_path = _decode_path(header[2], b"a/")
        new_path = _decode_path(header[3], b"b/")
        if old_path != new_path:
            raise PatchError("rename patches are forbidden")
        index += 1

        while index < len(lines) and not lines[index].startswith(b"--- "):
            if lines[index].startswith(b"diff --git "):
                raise PatchError(f"missing file headers for {old_path}")
            if not lines[index].startswith(b"index "):
                raise PatchError(f"unsupported patch metadata for {old_path}")
            index += 1
        if index >= len(lines):
            raise PatchError(f"missing old-file header for {old_path}")
        declared_old = _decode_path(lines[index][4:], b"a/")
        index += 1
        if index >= len(lines) or not lines[index].startswith(b"+++ "):
            raise PatchError(f"missing new-file header for {old_path}")
        declared_new = _decode_path(lines[index][4:], b"b/")
        index += 1
        if declared_old != old_path or declared_new != old_path:
            raise PatchError(f"file header path differs for {old_path}")

        hunks: list[Hunk] = []
        while index < len(lines) and not lines[index].startswith(b"diff --git "):
            old_start, old_count, new_start, new_count = _parse_hunk_header(
                lines[index]
            )
            index += 1
            hunk_lines: list[bytes] = []
            while (
                index < len(lines)
                and not lines[index].startswith(b"@@ ")
                and not lines[index].startswith(b"diff --git ")
            ):
                line = lines[index]
                if not line or line[:1] not in (b" ", b"+", b"-"):
                    raise PatchError(f"unsupported hunk line for {old_path}")
                hunk_lines.append(line)
                index += 1
            actual_old = sum(line[:1] in (b" ", b"-") for line in hunk_lines)
            actual_new = sum(line[:1] in (b" ", b"+") for line in hunk_lines)
            if actual_old != old_count or actual_new != new_count:
                raise PatchError(f"hunk count differs for {old_path}")
            hunks.append(
                Hunk(old_start, old_count, new_start, new_count, tuple(hunk_lines))
            )
        if not hunks:
            raise PatchError(f"patch has no hunks for {old_path}")
        patches.append(FilePatch(old_path, tuple(hunks)))

    paths = [patch.path for patch in patches]
    if len(paths) != len(set(paths)):
        raise PatchError("duplicate file patch")
    return tuple(patches)


def _apply_file(source: bytes, patch: FilePatch) -> bytes:
    source_lines = source.splitlines(keepends=True)
    output: list[bytes] = []
    source_index = 0
    for hunk in patch.hunks:
        hunk_index = hunk.old_start - 1 if hunk.old_start > 0 else 0
        if hunk_index < source_index or hunk_index > len(source_lines):
            raise PatchError(f"hunk position differs for {patch.path}")
        output.extend(source_lines[source_index:hunk_index])
        source_index = hunk_index

        for line in hunk.lines:
            operation = line[:1]
            payload = line[1:]
            if operation in (b" ", b"-"):
                if source_index >= len(source_lines):
                    raise PatchError(f"source ended inside hunk for {patch.path}")
                source_line = source_lines[source_index]
                if _without_line_ending(source_line) != _without_line_ending(payload):
                    raise PatchError(f"source line differs for {patch.path}")
                source_index += 1
                if operation == b" ":
                    output.append(source_line)
            else:
                output.append(payload)

    output.extend(source_lines[source_index:])
    return b"".join(output)


def apply_exact_unified_diff(
    root: Path, patch_data: bytes, allowed_paths: frozenset[str]
) -> None:
    resolved_root = root.resolve(strict=True)
    patches = _parse_patch(patch_data)
    outputs: list[tuple[Path, bytes]] = []
    for patch in patches:
        if patch.path not in allowed_paths:
            raise PatchError(f"patch target is not allowlisted: {patch.path}")
        target = resolved_root.joinpath(*PurePosixPath(patch.path).parts).resolve(
            strict=True
        )
        if not target.is_relative_to(resolved_root) or not target.is_file():
            raise PatchError(f"patch target is not a regular file: {patch.path}")
        outputs.append((target, _apply_file(target.read_bytes(), patch)))

    for target, output in outputs:
        temporary = target.with_name(target.name + ".renderweave-patch.tmp")
        if temporary.exists():
            raise PatchError(f"temporary patch output already exists: {temporary.name}")
        temporary.write_bytes(output)
        os.replace(temporary, target)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--patch", type=Path, required=True)
    parser.add_argument("--allow", action="append", default=[], required=True)
    arguments = parser.parse_args()
    try:
        apply_exact_unified_diff(
            arguments.root,
            arguments.patch.read_bytes(),
            frozenset(arguments.allow),
        )
    except (OSError, PatchError) as error:
        print(f"EXACT_PATCH_REJECTED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
