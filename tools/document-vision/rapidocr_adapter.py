#!/usr/bin/env python3
"""Payload-minimal local RapidOCR adapter for RenderWeave's bounded process protocol."""

from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.metadata
import json
import logging
import math
import os
import pathlib
import socket
import sys
from typing import Any


REQUEST_VERSION = "renderweave-document-vision-request/1.0"
RESPONSE_VERSION = "renderweave-document-vision-response/1.0"
CAPABILITY_VERSION = "renderweave-document-vision-process-capability/1.0"
CAPABILITY_ID = "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"
RAPIDOCR_VERSION = "3.9.2"
OPENVINO_VERSION = "2026.0.0"
MODEL_MANIFEST_SHA256 = "c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5"
MODEL_FILES = {
    "PP-OCRv6_det_small.onnx": "090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f",
    "ch_ppocr_mobile_v2.0_cls_mobile.onnx": "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c",
    "PP-OCRv6_rec_small.onnx": "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884",
}
MAX_REQUEST_BYTES = 42 * 1024 * 1024
MAX_ARTIFACTS = 10
MAX_ARTIFACT_BYTES = 10 * 1024 * 1024
MAX_LINES = 512
MAX_LINE_TEXT_BYTES = 256
MAX_TOTAL_TEXT_BYTES = 32 * 1024


def _deny_network() -> None:
    """Deny Python-level network before importing the OCR/runtime packages."""

    class OfflineSocket(socket.socket):
        def connect(self, _address: Any) -> None:  # pragma: no cover - defensive runtime boundary
            raise OSError("DOCUMENT_VISION_NETWORK_DENIED")

        def connect_ex(self, _address: Any) -> int:  # pragma: no cover - defensive runtime boundary
            raise OSError("DOCUMENT_VISION_NETWORK_DENIED")

    def denied(*_args: Any, **_kwargs: Any) -> None:
        raise OSError("DOCUMENT_VISION_NETWORK_DENIED")

    socket.socket = OfflineSocket
    socket.create_connection = denied
    socket.getaddrinfo = denied


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("DOCUMENT_VISION_DUPLICATE_MEMBER")
        result[key] = value
    return result


def _exact_keys(value: Any, expected: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError("DOCUMENT_VISION_SHAPE_INVALID")
    return value


def _sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _models(model_root: pathlib.Path) -> dict[str, pathlib.Path]:
    root = model_root.resolve(strict=True)
    if not root.is_dir():
        raise ValueError("DOCUMENT_VISION_MODEL_ROOT_INVALID")
    result: dict[str, pathlib.Path] = {}
    manifest = hashlib.sha256()
    for name, expected in MODEL_FILES.items():
        path = (root / name).resolve(strict=True)
        if path.parent != root or not path.is_file() or _sha256(path) != expected:
            raise ValueError("DOCUMENT_VISION_MODEL_IDENTITY_MISMATCH")
        encoded = name.encode("utf-8")
        manifest.update(len(encoded).to_bytes(4, "big"))
        manifest.update(encoded)
        manifest.update(bytes.fromhex(expected))
        result[name] = path
    if manifest.hexdigest() != MODEL_MANIFEST_SHA256:
        raise ValueError("DOCUMENT_VISION_MODEL_MANIFEST_MISMATCH")
    return result


def _engine(model_root: pathlib.Path):
    if importlib.metadata.version("rapidocr") != RAPIDOCR_VERSION:
        raise ValueError("DOCUMENT_VISION_ENGINE_VERSION_MISMATCH")
    if importlib.metadata.version("openvino") != OPENVINO_VERSION:
        raise ValueError("DOCUMENT_VISION_RUNTIME_VERSION_MISMATCH")
    models = _models(model_root)
    _deny_network()
    logging.disable(logging.CRITICAL)
    from rapidocr import RapidOCR
    from rapidocr.utils.typings import EngineType

    return RapidOCR(params={
        "Det.engine_type": EngineType.OPENVINO,
        "Rec.engine_type": EngineType.OPENVINO,
        "Cls.engine_type": EngineType.OPENVINO,
        "Det.model_path": str(models["PP-OCRv6_det_small.onnx"]),
        "Rec.model_path": str(models["PP-OCRv6_rec_small.onnx"]),
        "Cls.model_path": str(models["ch_ppocr_mobile_v2.0_cls_mobile.onnx"]),
    })


def _capability(model_root: pathlib.Path) -> dict[str, Any]:
    _engine(model_root)
    return {
        "protocolVersion": CAPABILITY_VERSION,
        "capabilityId": CAPABILITY_ID,
        "engine": "rapidocr-openvino-ppocrv6-small",
        "engineVersion": f"rapidocr-{RAPIDOCR_VERSION}+openvino-{OPENVINO_VERSION}",
        "modelManifestSha256": MODEL_MANIFEST_SHA256,
    }


def _artifact(engine: Any, raw: Any) -> dict[str, Any]:
    value = _exact_keys(raw, {"artifactId", "sourceOrdinal", "mediaType", "width", "height", "base64"})
    artifact_id = value["artifactId"]
    ordinal = value["sourceOrdinal"]
    media_type = value["mediaType"]
    width = value["width"]
    height = value["height"]
    if (not isinstance(artifact_id, str) or len(artifact_id) != 64
            or any(char not in "0123456789abcdef" for char in artifact_id)
            or not isinstance(ordinal, int) or isinstance(ordinal, bool) or ordinal < 0 or ordinal >= MAX_ARTIFACTS
            or media_type not in ("image/png", "image/jpeg")
            or not isinstance(width, int) or isinstance(width, bool) or width < 1 or width > 4096
            or not isinstance(height, int) or isinstance(height, bool) or height < 1 or height > 4096
            or width * height > 16_000_000 or not isinstance(value["base64"], str)):
        raise ValueError("DOCUMENT_VISION_ARTIFACT_INVALID")
    image = base64.b64decode(value["base64"], validate=True)
    if not image or len(image) > MAX_ARTIFACT_BYTES:
        raise ValueError("DOCUMENT_VISION_ARTIFACT_INVALID")
    # RapidOCR's bytes loader goes through PIL. For CMYK JPEGs its generic four-channel
    # conversion treats K as alpha and can erase all text. Decode to an explicit BGR
    # raster first so every accepted media type has one deterministic color path.
    import cv2
    import numpy as np

    decoded = cv2.imdecode(np.frombuffer(image, dtype=np.uint8), cv2.IMREAD_COLOR)
    if (decoded is None or decoded.ndim != 3 or decoded.shape[2] != 3
            or decoded.shape[1] != width or decoded.shape[0] != height):
        raise ValueError("DOCUMENT_VISION_ARTIFACT_INVALID")
    output = engine(decoded, use_det=True, use_cls=True, use_rec=True, text_score=0.35)
    boxes = [] if output.boxes is None else output.boxes
    texts = [] if output.txts is None else output.txts
    scores = [] if output.scores is None else output.scores
    if len(boxes) != len(texts) or len(texts) != len(scores) or len(texts) > MAX_LINES:
        raise ValueError("DOCUMENT_VISION_ENGINE_OUTPUT_INVALID")
    lines: list[dict[str, Any]] = []
    for box, text, score in zip(boxes, texts, scores):
        points = box.tolist() if hasattr(box, "tolist") else box
        if (not isinstance(points, list) or len(points) != 4
                or any(not isinstance(point, list) or len(point) != 2 for point in points)):
            raise ValueError("DOCUMENT_VISION_ENGINE_OUTPUT_INVALID")
        xs = [float(point[0]) for point in points]
        ys = [float(point[1]) for point in points]
        if not all(math.isfinite(item) for item in xs + ys):
            raise ValueError("DOCUMENT_VISION_ENGINE_OUTPUT_INVALID")
        left = max(0, math.floor(min(xs)))
        top = max(0, math.floor(min(ys)))
        right = min(width, math.ceil(max(xs)))
        bottom = min(height, math.ceil(max(ys)))
        if left >= right or top >= bottom or not isinstance(text, str) or not text.strip():
            continue
        encoded_text = text.encode("utf-8")
        if (len(encoded_text) > MAX_LINE_TEXT_BYTES
                or any(ord(character) < 32 or 127 <= ord(character) <= 159 for character in text)):
            raise ValueError("DOCUMENT_VISION_ENGINE_OUTPUT_INVALID")
        confidence = float(score)
        if not math.isfinite(confidence):
            raise ValueError("DOCUMENT_VISION_ENGINE_OUTPUT_INVALID")
        lines.append({
            "left": left,
            "top": top,
            "right": right,
            "bottom": bottom,
            "confidenceBps": max(0, min(10_000, round(confidence * 10_000))),
            "text": text,
        })
    lines.sort(key=lambda item: (
        item["top"], item["left"], item["bottom"], item["right"], item["text"]
    ))
    return {"artifactId": artifact_id, "sourceOrdinal": ordinal, "lines": lines}


def _preprocess(model_root: pathlib.Path) -> dict[str, Any]:
    raw = sys.stdin.buffer.read(MAX_REQUEST_BYTES + 1)
    if not raw or len(raw) > MAX_REQUEST_BYTES:
        raise ValueError("DOCUMENT_VISION_REQUEST_TOO_LARGE")
    request = _exact_keys(json.loads(raw, object_pairs_hook=_strict_object), {"protocolVersion", "artifacts"})
    if (request["protocolVersion"] != REQUEST_VERSION
            or not isinstance(request["artifacts"], list)
            or not 1 <= len(request["artifacts"]) <= MAX_ARTIFACTS):
        raise ValueError("DOCUMENT_VISION_REQUEST_INVALID")
    engine = _engine(model_root)
    artifacts = [_artifact(engine, item) for item in request["artifacts"]]
    if (len({item["artifactId"] for item in artifacts}) != len(artifacts)
            or len({item["sourceOrdinal"] for item in artifacts}) != len(artifacts)):
        raise ValueError("DOCUMENT_VISION_ARTIFACT_DUPLICATE")
    lines = [line for artifact in artifacts for line in artifact["lines"]]
    if (len(lines) > MAX_LINES
            or sum(len(line["text"].encode("utf-8")) for line in lines) > MAX_TOTAL_TEXT_BYTES):
        raise ValueError("DOCUMENT_VISION_ENGINE_OUTPUT_TOO_LARGE")
    return {
        "protocolVersion": RESPONSE_VERSION,
        "capabilityId": CAPABILITY_ID,
        "artifacts": artifacts,
    }


def main() -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--capability", action="store_true")
    parser.add_argument("--model-root", required=True)
    args = parser.parse_args()
    model_root = pathlib.Path(args.model_root)
    result = _capability(model_root) if args.capability else _preprocess(model_root)
    sys.stdout.write(json.dumps(result, ensure_ascii=False, separators=(",", ":"), sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception:
        sys.stderr.write("DOCUMENT_VISION_PROCESS_FAILED\n")
        raise SystemExit(2)
