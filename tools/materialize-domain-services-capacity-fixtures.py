#!/usr/bin/env python3
"""Export the target-bound LF fixture blobs without mutating the checkout."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


REVISION = re.compile(r"[0-9a-f]{40}")
FIXTURE_PREFIX = "domain-services/fixtures/"
REPOSITORY_PREFIX = ".scratch/renderweave-template-v1/"


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    repo = arguments.repo.resolve()
    target = json.loads(arguments.target.read_bytes().decode("utf-8", "strict"))

    revision = target.get("implementationRevision")
    if not isinstance(revision, str) or REVISION.fullmatch(revision) is None:
        fail("target implementation revision is not exact")
    if target.get("targetId") != "DOMAIN_SERVICES_CAPACITY_TARGET::ASSET_CONTENT_GUARD::1.0":
        fail("unexpected target identity")
    fixtures = target.get("fixtures")
    if not isinstance(fixtures, list) or len(fixtures) != 12:
        fail("target must bind exactly 12 fixtures")

    output = arguments.output.resolve()
    if output.exists():
        fail("fixture snapshot output already exists")
    output.mkdir(parents=True)
    seen: set[str] = set()
    for entry in fixtures:
        if set(entry) != {"path", "sha256", "byteLength"}:
            fail("fixture binding members drifted")
        relative = entry["path"]
        if not isinstance(relative, str) or not relative.startswith(FIXTURE_PREFIX):
            fail("fixture binding path escaped the closed prefix")
        name = relative.removeprefix(FIXTURE_PREFIX)
        if not re.fullmatch(r"cap-[a-z0-9-]+\.json", name) or name in seen:
            fail("fixture binding name is invalid or duplicated")
        seen.add(name)
        data = subprocess.run(
            ["git", "show", f"{revision}:{REPOSITORY_PREFIX}{relative}"],
            cwd=repo,
            check=True,
            stdout=subprocess.PIPE,
        ).stdout
        observed_sha = "sha256:" + hashlib.sha256(data).hexdigest()
        if observed_sha != entry["sha256"] or len(data) != entry["byteLength"]:
            fail("fixture blob does not match the exact target")
        with (output / name).open("xb") as stream:
            stream.write(data)
    if len(seen) != 12:
        fail("fixture snapshot cardinality drifted")


if __name__ == "__main__":
    main()
