#!/usr/bin/env python3
"""Independent Python implementation of the frozen R5P2 reconciliation policy."""

from __future__ import annotations

import hashlib
import re
import unicodedata
from dataclasses import dataclass
from typing import Any, Sequence


VERSION = "FrozenSourceLineReconciliationPolicy/1.0"
PROJECTION_IDENTITY = "renderweave-r5p-source-projection/1.0"
AREA_OVERLAP_BPS = 5_000
VERTICAL_OVERLAP_BPS = 8_000
POLICY_FRAMES = (
    VERSION,
    f"projection={PROJECTION_IDENTITY}",
    "scope=same-source-cross-view-only/1.0",
    "geometry=intersection-over-smaller-area-5000-bps/1.0",
    "vertical=intersection-over-smaller-height-8000-bps/1.0",
    "center=smaller-center-in-larger-closed-open/1.0",
    "cluster=complete-link-source-order/1.0",
    "representative=pixel-density-confidence-smaller-area-view-line/1.0",
    "representative-payload=original-observation-only/1.0",
    "canonical-text-order=unicode-scalar-lexicographic/1.0",
    "sort=top-left-bottom-right-canonical-text-view-line/1.0",
)
POLICY_IDENTITY = f"{VERSION}:" + hashlib.sha256("\n".join(POLICY_FRAMES).encode()).hexdigest()
if POLICY_IDENTITY != (
    "FrozenSourceLineReconciliationPolicy/1.0:"
    "eead9287d942693156500a090daf5da5c2f9dafe4f6564ee642ac406c0f49443"
):
    raise RuntimeError("R5P2_RECONCILIATION_POLICY_IDENTITY_DRIFT")


class ReconciliationError(ValueError):
    pass


def fail(code: str) -> None:
    raise ReconciliationError(code)


@dataclass(frozen=True)
class PixelBox:
    left: int
    top: int
    right: int
    bottom: int

    def __post_init__(self) -> None:
        if (any(type(value) is not int for value in (self.left, self.top, self.right, self.bottom))
                or self.left < 0 or self.top < 0
                or self.left >= self.right or self.top >= self.bottom):
            fail("R5P2_PROJECTION_PIXEL_BOX_INVALID")

    def require_within(self, width: int, height: int) -> None:
        if (type(width) is not int or type(height) is not int or width < 1 or height < 1
                or self.right > width or self.bottom > height):
            fail("R5P2_PROJECTION_PIXEL_BOX_INVALID")


@dataclass(frozen=True)
class SourceBox:
    left: int
    top: int
    right: int
    bottom: int

    def __post_init__(self) -> None:
        if (any(type(value) is not int for value in (self.left, self.top, self.right, self.bottom))
                or self.left < 0 or self.top < 0 or self.left >= self.right
                or self.top >= self.bottom or self.right > 10_000 or self.bottom > 10_000):
            fail("R5P2_RECONCILIATION_SOURCE_BOX_INVALID")

    @property
    def area(self) -> int:
        return (self.right - self.left) * (self.bottom - self.top)

    @property
    def height(self) -> int:
        return self.bottom - self.top


@dataclass(frozen=True)
class PixelDensity:
    numerator: int
    denominator: int

    def __post_init__(self) -> None:
        if (type(self.numerator) is not int or type(self.denominator) is not int
                or self.numerator < 1 or self.denominator < 1):
            fail("R5P2_RECONCILIATION_DENSITY_INVALID")


def _canonical_text(value: Any) -> str:
    if type(value) is not str or not value:
        fail("R5P2_RECONCILIATION_TEXT_INVALID")
    normalized = unicodedata.normalize("NFC", value)
    if any(unicodedata.category(character) == "Cc" for character in normalized):
        fail("R5P2_RECONCILIATION_TEXT_INVALID")
    canonical = " ".join(normalized.split())
    if value != canonical:
        fail("R5P2_RECONCILIATION_TEXT_INVALID")
    return value


@dataclass(frozen=True)
class ProjectedLine:
    observation_id: str
    source_artifact_id: str
    source_box: SourceBox
    confidence_bps: int
    text: str
    view_ordinal: int
    line_ordinal: int
    pixel_density: PixelDensity

    def __post_init__(self) -> None:
        if type(self.observation_id) is not str or not re.fullmatch(r"[a-z][a-z0-9-]{0,127}", self.observation_id):
            fail("R5P2_RECONCILIATION_OBSERVATION_ID_INVALID")
        if type(self.source_artifact_id) is not str or not re.fullmatch(r"[0-9a-f]{64}", self.source_artifact_id):
            fail("R5P2_RECONCILIATION_SOURCE_ID_INVALID")
        if (type(self.source_box) is not SourceBox or type(self.pixel_density) is not PixelDensity
                or type(self.confidence_bps) is not int or not 0 <= self.confidence_bps <= 10_000
                or type(self.view_ordinal) is not int or not 0 <= self.view_ordinal < 10
                or type(self.line_ordinal) is not int or not 0 <= self.line_ordinal < 4_096):
            fail("R5P2_RECONCILIATION_LINE_BOUNDS_INVALID")
        _canonical_text(self.text)


@dataclass(frozen=True)
class Outcome:
    representatives: tuple[ProjectedLine, ...]
    input_count: int
    cluster_count: int
    policy_identity: str = POLICY_IDENTITY

    def __post_init__(self) -> None:
        if (not self.representatives or self.input_count < 1
                or self.cluster_count != len(self.representatives)
                or self.policy_identity != POLICY_IDENTITY):
            fail("R5P2_RECONCILIATION_OUTCOME_INVALID")


def _floor_ratio(value: int, multiplier: int, divisor: int) -> int:
    if divisor < 1:
        fail("R5P2_PROJECTION_DIMENSIONS_INVALID")
    return value * multiplier // divisor


def _ceil_ratio(value: int, multiplier: int, divisor: int) -> int:
    if divisor < 1:
        fail("R5P2_PROJECTION_DIMENSIONS_INVALID")
    return (value * multiplier + divisor - 1) // divisor


def _project_floor(crop_start: int, crop_size: int, coordinate: int, source_size: int) -> int:
    if source_size < 1:
        fail("R5P2_PROJECTION_DIMENSIONS_INVALID")
    return (crop_start * 10_000 + coordinate * crop_size) // source_size


def _project_ceil(crop_start: int, crop_size: int, coordinate: int, source_size: int) -> int:
    if source_size < 1:
        fail("R5P2_PROJECTION_DIMENSIONS_INVALID")
    numerator = crop_start * 10_000 + coordinate * crop_size
    return (numerator + source_size - 1) // source_size


def project(
        observation_id: str, source_artifact_id: str, view_ordinal: int, line_ordinal: int,
        view_width: int, view_height: int, source_width: int, source_height: int,
        source_crop: PixelBox, view_line: PixelBox, confidence_bps: int,
        text: str) -> ProjectedLine:
    source_crop.require_within(source_width, source_height)
    view_line.require_within(view_width, view_height)
    view_canonical = SourceBox(
        _floor_ratio(view_line.left, 10_000, view_width),
        _floor_ratio(view_line.top, 10_000, view_height),
        _ceil_ratio(view_line.right, 10_000, view_width),
        _ceil_ratio(view_line.bottom, 10_000, view_height),
    )
    crop_width = source_crop.right - source_crop.left
    crop_height = source_crop.bottom - source_crop.top
    source_box = SourceBox(
        _project_floor(source_crop.left, crop_width, view_canonical.left, source_width),
        _project_floor(source_crop.top, crop_height, view_canonical.top, source_height),
        _project_ceil(source_crop.left, crop_width, view_canonical.right, source_width),
        _project_ceil(source_crop.top, crop_height, view_canonical.bottom, source_height),
    )
    return ProjectedLine(
        observation_id, source_artifact_id, source_box, confidence_bps, text,
        view_ordinal, line_ordinal,
        PixelDensity(view_width * view_height, crop_width * crop_height),
    )


def _at_least_bps(numerator: int, denominator: int, threshold: int) -> bool:
    return denominator > 0 and numerator * 10_000 >= denominator * threshold


def area_threshold_allows(intersection_area: int, smaller_area: int) -> bool:
    return _at_least_bps(intersection_area, smaller_area, AREA_OVERLAP_BPS)


def vertical_threshold_allows(intersection_height: int, smaller_height: int) -> bool:
    return _at_least_bps(intersection_height, smaller_height, VERTICAL_OVERLAP_BPS)


def _center_inside(smaller: SourceBox, larger: SourceBox) -> bool:
    center_x2 = smaller.left + smaller.right
    center_y2 = smaller.top + smaller.bottom
    return (larger.left * 2 <= center_x2 < larger.right * 2
            and larger.top * 2 <= center_y2 < larger.bottom * 2)


def same_source_line_candidate(left: ProjectedLine, right: ProjectedLine) -> bool:
    if left.source_artifact_id != right.source_artifact_id or left.view_ordinal == right.view_ordinal:
        return False
    intersection_width = max(
        0, min(left.source_box.right, right.source_box.right)
        - max(left.source_box.left, right.source_box.left),
    )
    intersection_height = max(
        0, min(left.source_box.bottom, right.source_box.bottom)
        - max(left.source_box.top, right.source_box.top),
    )
    intersection = intersection_width * intersection_height
    if (intersection == 0
            or not area_threshold_allows(
                intersection, min(left.source_box.area, right.source_box.area))
            or not vertical_threshold_allows(
                intersection_height, min(left.source_box.height, right.source_box.height))):
        return False
    if left.source_box.area < right.source_box.area:
        return _center_inside(left.source_box, right.source_box)
    if right.source_box.area < left.source_box.area:
        return _center_inside(right.source_box, left.source_box)
    return (_center_inside(left.source_box, right.source_box)
            and _center_inside(right.source_box, left.source_box))


def _source_order(value: ProjectedLine) -> tuple[Any, ...]:
    box = value.source_box
    return (box.top, box.left, box.bottom, box.right, value.text, value.view_ordinal,
            value.line_ordinal)


def prefers_representative(candidate: ProjectedLine, existing: ProjectedLine) -> bool:
    left = candidate.pixel_density.numerator * existing.pixel_density.denominator
    right = existing.pixel_density.numerator * candidate.pixel_density.denominator
    if left != right:
        return left > right
    if candidate.confidence_bps != existing.confidence_bps:
        return candidate.confidence_bps > existing.confidence_bps
    if candidate.source_box.area != existing.source_box.area:
        return candidate.source_box.area < existing.source_box.area
    if candidate.view_ordinal != existing.view_ordinal:
        return candidate.view_ordinal < existing.view_ordinal
    return candidate.line_ordinal < existing.line_ordinal


def reconcile(lines: Sequence[ProjectedLine]) -> Outcome:
    lines = tuple(lines)
    if not 1 <= len(lines) <= 4_096 or any(type(line) is not ProjectedLine for line in lines):
        fail("R5P2_RECONCILIATION_INPUT_INVALID")
    if len({line.observation_id for line in lines}) != len(lines):
        fail("R5P2_RECONCILIATION_OBSERVATION_DUPLICATED")
    clusters: list[list[ProjectedLine]] = []
    for candidate in sorted(lines, key=_source_order):
        for cluster in clusters:
            if all(same_source_line_candidate(candidate, existing) for existing in cluster):
                cluster.append(candidate)
                break
        else:
            clusters.append([candidate])
    representatives: list[ProjectedLine] = []
    for cluster in clusters:
        representative = cluster[0]
        for candidate in cluster:
            if prefers_representative(candidate, representative):
                representative = candidate
        representatives.append(representative)
    representatives.sort(key=_source_order)
    return Outcome(tuple(representatives), len(lines), len(clusters))


def projected_line(value: dict[str, Any]) -> ProjectedLine:
    expected = {
        "observationId", "sourceArtifactId", "box", "confidenceBps", "text",
        "viewOrdinal", "lineOrdinal", "density",
    }
    if type(value) is not dict or set(value) != expected:
        fail("R5P2_RECONCILIATION_GOLDEN_INVALID")
    box = value["box"]
    density = value["density"]
    if (type(box) is not list or len(box) != 4
            or type(density) is not list or len(density) != 2):
        fail("R5P2_RECONCILIATION_GOLDEN_INVALID")
    return ProjectedLine(
        value["observationId"], value["sourceArtifactId"], SourceBox(*box),
        value["confidenceBps"], value["text"], value["viewOrdinal"],
        value["lineOrdinal"], PixelDensity(*density),
    )
