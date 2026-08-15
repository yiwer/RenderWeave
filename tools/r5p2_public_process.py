#!/usr/bin/env python3
"""Strict public-process client for one complete R5P2 branch acquisition."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import pathlib
import subprocess
import unicodedata
from dataclasses import dataclass
from typing import Any, Callable, Sequence


CAPABILITY_VERSION = "renderweave-document-vision-process-capability/1.0"
REQUEST_VERSION = "renderweave-document-vision-request/1.0"
RESPONSE_VERSION = "renderweave-document-vision-response/1.0"
ENGINE = "rapidocr-openvino-ppocrv6-small"
ENGINE_VERSION = "rapidocr-3.9.2+openvino-2026.0.0"
MODEL_MANIFEST_SHA256 = "c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5"
MAX_ARTIFACTS = 10
MAX_ARTIFACT_BYTES = 10 * 1024 * 1024
MAX_REQUEST_BYTES = 42 * 1024 * 1024
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_LINES = 4_096
MAX_TEXT_BYTES = 256 * 1024
MAX_DIMENSION = 4_096
MAX_PIXELS = 16_000_000


class ProtocolError(ValueError):
    pass


def fail(code: str) -> None:
    raise ProtocolError(code)


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            fail("R5P2_BRANCH_RESPONSE_JSON_INVALID")
        value[key] = item
    return value


def _reject_float(_: str) -> None:
    fail("R5P2_BRANCH_RESPONSE_JSON_INVALID")


def _strict_json(raw: bytes) -> dict[str, Any]:
    if not raw or len(raw) > MAX_RESPONSE_BYTES:
        fail("R5P2_BRANCH_RESPONSE_BOUNDS_INVALID")
    try:
        text = raw.decode("utf-8")
        decoder = json.JSONDecoder(object_pairs_hook=_strict_object, parse_float=_reject_float)
        value, end = decoder.raw_decode(text)
        if text[end:].strip() or type(value) is not dict:
            fail("R5P2_BRANCH_RESPONSE_JSON_INVALID")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError):
        fail("R5P2_BRANCH_RESPONSE_JSON_INVALID")


def _exact_keys(value: Any, expected: set[str], code: str) -> dict[str, Any]:
    if type(value) is not dict or set(value) != expected:
        fail(code)
    return value


def _integer(value: Any, code: str) -> int:
    if type(value) is not int:
        fail(code)
    return value


def _canonical_text(value: Any) -> str:
    if type(value) is not str or not value or any(unicodedata.category(char) == "Cc" for char in value):
        fail("R5P2_BRANCH_RESPONSE_TEXT_INVALID")
    canonical = " ".join(unicodedata.normalize("NFC", value).split())
    if value != canonical:
        fail("R5P2_BRANCH_RESPONSE_TEXT_INVALID")
    return value


@dataclass(frozen=True)
class BranchArtifact:
    artifact_id: str
    source_ordinal: int
    media_type: str
    width: int
    height: int
    payload: bytes

    def __post_init__(self) -> None:
        payload = bytes(self.payload)
        object.__setattr__(self, "payload", payload)
        if self.artifact_id != hashlib.sha256(payload).hexdigest():
            fail("R5P2_BRANCH_ARTIFACT_IDENTITY_INVALID")
        if type(self.source_ordinal) is not int or self.source_ordinal < 0:
            fail("R5P2_BRANCH_ARTIFACT_ORDINAL_INVALID")
        if self.media_type not in ("image/png", "image/jpeg"):
            fail("R5P2_BRANCH_ARTIFACT_MEDIA_TYPE_INVALID")
        if not payload or len(payload) > MAX_ARTIFACT_BYTES:
            fail("R5P2_BRANCH_ARTIFACT_BOUNDS_INVALID")
        if (type(self.width) is not int or type(self.height) is not int
                or not 1 <= self.width <= MAX_DIMENSION
                or not 1 <= self.height <= MAX_DIMENSION
                or self.width * self.height > MAX_PIXELS):
            fail("R5P2_BRANCH_ARTIFACT_BOUNDS_INVALID")


@dataclass(frozen=True)
class ProcessAccounting:
    capability_probe_processes: int = 0
    branch_acquisition_processes: int = 0
    artifact_views: int = 0


Executor = Callable[[Sequence[str], bytes, int, dict[str, str]], bytes]


def _minimal_environment() -> dict[str, str]:
    result: dict[str, str] = {}
    for key in ("SystemRoot", "WINDIR", "ComSpec", "PATHEXT", "TEMP", "TMP", "LANG"):
        value = os.environ.get(key)
        if value:
            result[key] = value
    result.update({
        "PYTHONUTF8": "1",
        "PYTHONNOUSERSITE": "1",
        "PYTHONDONTWRITEBYTECODE": "1",
        "NO_PROXY": "*",
        "no_proxy": "*",
    })
    return result


def _default_executor(
        command: Sequence[str], stdin: bytes, timeout_seconds: int,
        environment: dict[str, str]) -> bytes:
    try:
        completed = subprocess.run(
            list(command), input=stdin, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            timeout=timeout_seconds, check=False, shell=False, env=environment,
        )
    except subprocess.TimeoutExpired:
        fail("R5P2_BRANCH_PROCESS_TIMEOUT")
    except OSError:
        fail("R5P2_BRANCH_PROCESS_FAILED")
    if completed.returncode != 0:
        fail("R5P2_BRANCH_PROCESS_FAILED")
    if len(completed.stdout) > MAX_RESPONSE_BYTES:
        fail("R5P2_BRANCH_RESPONSE_BOUNDS_INVALID")
    return completed.stdout


class PublicBranchProcessClient:
    def __init__(
            self, command_prefix: Sequence[str], model_root: str | pathlib.Path,
            expected_capability_id: str, timeout_seconds: int = 60,
            executor: Executor = _default_executor) -> None:
        if (not command_prefix or any(type(item) is not str or not item for item in command_prefix)
                or type(expected_capability_id) is not str or not expected_capability_id
                or type(timeout_seconds) is not int or not 1 <= timeout_seconds <= 60):
            fail("R5P2_BRANCH_CLIENT_CONFIGURATION_INVALID")
        self._command_prefix = tuple(command_prefix)
        self._model_root = str(model_root)
        self._expected_capability_id = expected_capability_id
        self._timeout_seconds = timeout_seconds
        self._executor = executor
        self._accounting = ProcessAccounting()
        self._probed = False

    @property
    def accounting(self) -> ProcessAccounting:
        return self._accounting

    def probe(self) -> dict[str, Any]:
        if self._probed:
            fail("R5P2_CAPABILITY_PROBE_DUPLICATED")
        self._accounting = ProcessAccounting(
            capability_probe_processes=self._accounting.capability_probe_processes + 1,
            branch_acquisition_processes=self._accounting.branch_acquisition_processes,
            artifact_views=self._accounting.artifact_views,
        )
        raw = self._executor(
            (*self._command_prefix, "--capability", "--model-root", self._model_root),
            b"", self._timeout_seconds, _minimal_environment(),
        )
        value = _strict_json(raw)
        _exact_keys(value, {
            "protocolVersion", "capabilityId", "engine", "engineVersion",
            "modelManifestSha256",
        }, "R5P2_CAPABILITY_RESPONSE_INVALID")
        expected = {
            "protocolVersion": CAPABILITY_VERSION,
            "capabilityId": self._expected_capability_id,
            "engine": ENGINE,
            "engineVersion": ENGINE_VERSION,
            "modelManifestSha256": MODEL_MANIFEST_SHA256,
        }
        if value != expected:
            fail("R5P2_CAPABILITY_MISMATCH")
        self._probed = True
        return value

    def acquire_complete_branch(
            self, artifacts: Sequence[BranchArtifact],
            expected_plan_artifact_ids: Sequence[str]) -> list[dict[str, Any]]:
        if not self._probed:
            fail("R5P2_CAPABILITY_NOT_PROBED")
        artifacts = tuple(artifacts)
        expected_ids = tuple(expected_plan_artifact_ids)
        if not 1 <= len(artifacts) <= MAX_ARTIFACTS:
            fail("R5P2_BRANCH_ARTIFACT_COUNT_INVALID")
        if tuple(item.artifact_id for item in artifacts) != expected_ids:
            fail("R5P2_BRANCH_PLAN_INCOMPLETE")
        if tuple(item.source_ordinal for item in artifacts) != tuple(range(len(artifacts))):
            fail("R5P2_BRANCH_ARTIFACT_ORDER_INVALID")
        if len(set(expected_ids)) != len(expected_ids):
            fail("R5P2_BRANCH_ARTIFACT_IDENTITY_DUPLICATED")

        request = {
            "protocolVersion": REQUEST_VERSION,
            "artifacts": [
                {
                    "artifactId": item.artifact_id,
                    "sourceOrdinal": item.source_ordinal,
                    "mediaType": item.media_type,
                    "width": item.width,
                    "height": item.height,
                    "base64": base64.b64encode(item.payload).decode("ascii"),
                }
                for item in artifacts
            ],
        }
        encoded = json.dumps(request, sort_keys=True, separators=(",", ":")).encode("utf-8")
        if len(encoded) > MAX_REQUEST_BYTES:
            fail("R5P2_BRANCH_REQUEST_BOUNDS_INVALID")
        self._accounting = ProcessAccounting(
            capability_probe_processes=self._accounting.capability_probe_processes,
            branch_acquisition_processes=self._accounting.branch_acquisition_processes + 1,
            artifact_views=self._accounting.artifact_views + len(artifacts),
        )
        raw = self._executor(
            (*self._command_prefix, "--model-root", self._model_root), encoded,
            self._timeout_seconds, _minimal_environment(),
        )
        return self._validate_response(raw, artifacts)

    def _validate_response(
            self, raw: bytes, artifacts: Sequence[BranchArtifact]) -> list[dict[str, Any]]:
        value = _strict_json(raw)
        _exact_keys(value, {"protocolVersion", "capabilityId", "artifacts"},
                    "R5P2_BRANCH_RESPONSE_JSON_INVALID")
        if (value["protocolVersion"] != RESPONSE_VERSION
                or value["capabilityId"] != self._expected_capability_id):
            fail("R5P2_CAPABILITY_MISMATCH")
        response_artifacts = value["artifacts"]
        if type(response_artifacts) is not list or len(response_artifacts) != len(artifacts):
            fail("R5P2_BRANCH_RESPONSE_ARTIFACT_COUNT_INVALID")

        total_lines = 0
        total_text_bytes = 0
        validated: list[dict[str, Any]] = []
        for expected, response in zip(artifacts, response_artifacts, strict=True):
            _exact_keys(response, {"artifactId", "sourceOrdinal", "lines"},
                        "R5P2_BRANCH_RESPONSE_JSON_INVALID")
            if (response["artifactId"] != expected.artifact_id
                    or response["sourceOrdinal"] != expected.source_ordinal):
                fail("R5P2_BRANCH_RESPONSE_ARTIFACT_ORDER_INVALID")
            if type(response["lines"]) is not list:
                fail("R5P2_BRANCH_RESPONSE_LINES_INVALID")
            lines: list[dict[str, Any]] = []
            for line in response["lines"]:
                _exact_keys(line, {
                    "left", "top", "right", "bottom", "confidenceBps", "text",
                }, "R5P2_BRANCH_RESPONSE_JSON_INVALID")
                left = _integer(line["left"], "R5P2_BRANCH_RESPONSE_GEOMETRY_INVALID")
                top = _integer(line["top"], "R5P2_BRANCH_RESPONSE_GEOMETRY_INVALID")
                right = _integer(line["right"], "R5P2_BRANCH_RESPONSE_GEOMETRY_INVALID")
                bottom = _integer(line["bottom"], "R5P2_BRANCH_RESPONSE_GEOMETRY_INVALID")
                confidence = _integer(
                    line["confidenceBps"], "R5P2_BRANCH_RESPONSE_CONFIDENCE_INVALID")
                text = _canonical_text(line["text"])
                if not (0 <= left < right <= expected.width
                        and 0 <= top < bottom <= expected.height):
                    fail("R5P2_BRANCH_RESPONSE_GEOMETRY_INVALID")
                if not 0 <= confidence <= 10_000:
                    fail("R5P2_BRANCH_RESPONSE_CONFIDENCE_INVALID")
                total_lines += 1
                total_text_bytes += len(text.encode("utf-8"))
                lines.append(dict(line))
            if lines != sorted(
                    lines, key=lambda item: (
                        item["top"], item["left"], item["bottom"], item["right"], item["text"]
                    )):
                fail("R5P2_BRANCH_RESPONSE_LINE_ORDER_INVALID")
            validated.append({
                "artifactId": response["artifactId"],
                "sourceOrdinal": response["sourceOrdinal"],
                "lines": lines,
            })
        if total_lines > MAX_LINES or total_text_bytes > MAX_TEXT_BYTES:
            fail("R5P2_BRANCH_RESPONSE_BOUNDS_INVALID")
        return validated
