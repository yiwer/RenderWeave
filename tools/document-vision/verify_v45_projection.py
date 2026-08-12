#!/usr/bin/env python3
"""Independently recompute the locked v45 source-pixel projection goldens."""

from __future__ import annotations

import json
import pathlib
from typing import Any


FIXTURE_VERSION = "renderweave-v45-projection-golden/1.0"
FIXTURE = (
    pathlib.Path(__file__).parents[2]
    / "renderweave-inference"
    / "src"
    / "test"
    / "resources"
    / "document-observation"
    / "v45-projection-golden.json"
)


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("V45_PROJECTION_FIXTURE_DUPLICATE_MEMBER")
        result[key] = value
    return result


def _floor(value: int, extent: int) -> int:
    return value * 10_000 // extent


def _ceil(value: int, extent: int) -> int:
    return (value * 10_000 + extent - 1) // extent


def main() -> int:
    document = json.loads(FIXTURE.read_text(encoding="utf-8"), object_pairs_hook=_strict_object)
    if set(document) != {"fixtureVersion", "cases"} or document["fixtureVersion"] != FIXTURE_VERSION:
        raise ValueError("V45_PROJECTION_FIXTURE_VERSION_INVALID")
    cases = document["cases"]
    if not isinstance(cases, list) or not cases:
        raise ValueError("V45_PROJECTION_FIXTURE_CASES_INVALID")
    for case in cases:
        if set(case) != {
            "mediaType", "width", "height", "sourceBox", "expectedBox",
            "confidenceBps", "expectedBucket",
        }:
            raise ValueError("V45_PROJECTION_FIXTURE_SHAPE_INVALID")
        width, height = case["width"], case["height"]
        left, top, right, bottom = case["sourceBox"]
        actual = [
            _floor(left, width),
            _floor(top, height),
            _ceil(right, width),
            _ceil(bottom, height),
        ]
        if actual != case["expectedBox"]:
            raise ValueError("V45_PROJECTION_GOLDEN_MISMATCH")
        confidence = case["confidenceBps"]
        bucket = "LOW" if confidence < 6_000 else "MEDIUM" if confidence < 8_500 else "HIGH"
        if bucket != case["expectedBucket"]:
            raise ValueError("V45_CONFIDENCE_GOLDEN_MISMATCH")
    print(f"v45ProjectionVerification=PASS fixture={FIXTURE_VERSION} cases={len(cases)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
