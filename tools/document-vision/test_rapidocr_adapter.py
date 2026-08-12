#!/usr/bin/env python3
"""Fast, dependency-free regression tests for the local RapidOCR adapter seam."""

from __future__ import annotations

import base64
import io
import importlib.util
import json
import pathlib
import sys
import types
import unittest
from unittest import mock


ADAPTER_PATH = pathlib.Path(__file__).with_name("rapidocr_adapter.py")
SPEC = importlib.util.spec_from_file_location("renderweave_rapidocr_adapter", ADAPTER_PATH)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - importlib defensive boundary
    raise RuntimeError("rapidocr_adapter.py cannot be loaded")
ADAPTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ADAPTER)


class _RecordingEngine:
    def __init__(self) -> None:
        self.image = None
        self.images = []

    def __call__(self, image, **_kwargs):
        self.image = image
        self.images.append(image)
        return types.SimpleNamespace(boxes=[], txts=[], scores=[])


class _DecodedImage:
    ndim = 3
    shape = (1, 1, 3)


class _Input:
    def __init__(self, payload: bytes) -> None:
        self.buffer = io.BytesIO(payload)


class RapidOcrAdapterTest(unittest.TestCase):
    def test_png_jpeg_and_cmyk_jpeg_take_the_explicit_bgr_decode_path(self) -> None:
        decoded_image = _DecodedImage()
        fake_numpy = types.ModuleType("numpy")
        fake_numpy.uint8 = object()
        fake_numpy.frombuffer = lambda value, dtype: (value, dtype)
        fake_cv2 = types.ModuleType("cv2")
        fake_cv2.IMREAD_COLOR = 1
        decode_calls = []
        fake_cv2.imdecode = lambda encoded, mode: decode_calls.append((encoded, mode)) or decoded_image
        engine = _RecordingEngine()

        with mock.patch.dict(sys.modules, {"cv2": fake_cv2, "numpy": fake_numpy}):
            for media_type, payload in (
                    ("image/png", b"png-raster"),
                    ("image/jpeg", b"rgb-jpeg-raster"),
                    ("image/jpeg", b"cmyk-jpeg-raster")):
                with self.subTest(media_type=media_type, payload=payload):
                    ADAPTER._artifact(engine, {
                        "artifactId": "0" * 64,
                        "sourceOrdinal": 0,
                        "mediaType": media_type,
                        "width": 1,
                        "height": 1,
                        "base64": base64.b64encode(payload).decode("ascii"),
                    })

        self.assertEqual(3, len(engine.images))
        self.assertTrue(all(image is decoded_image for image in engine.images))
        self.assertEqual([fake_cv2.IMREAD_COLOR] * 3, [mode for _encoded, mode in decode_calls])

    def test_multiple_images_are_independently_decoded_and_kept_in_source_order(self) -> None:
        decoded_image = _DecodedImage()
        fake_numpy = types.ModuleType("numpy")
        fake_numpy.uint8 = object()
        fake_numpy.frombuffer = lambda value, dtype: (value, dtype)
        fake_cv2 = types.ModuleType("cv2")
        fake_cv2.IMREAD_COLOR = 1
        fake_cv2.imdecode = lambda _encoded, _mode: decoded_image
        engine = _RecordingEngine()
        request = {
            "protocolVersion": ADAPTER.REQUEST_VERSION,
            "artifacts": [
                {
                    "artifactId": "0" * 64,
                    "sourceOrdinal": 0,
                    "mediaType": "image/png",
                    "width": 1,
                    "height": 1,
                    "base64": base64.b64encode(b"first").decode("ascii"),
                },
                {
                    "artifactId": "1" * 64,
                    "sourceOrdinal": 1,
                    "mediaType": "image/jpeg",
                    "width": 1,
                    "height": 1,
                    "base64": base64.b64encode(b"second").decode("ascii"),
                },
            ],
        }

        with (mock.patch.dict(sys.modules, {"cv2": fake_cv2, "numpy": fake_numpy}),
              mock.patch.object(ADAPTER, "_engine", return_value=engine),
              mock.patch.object(ADAPTER.sys, "stdin", _Input(json.dumps(request).encode("utf-8")))):
            result = ADAPTER._preprocess(pathlib.Path("unused-model-root"))

        self.assertEqual(2, len(engine.images))
        self.assertEqual([0, 1], [item["sourceOrdinal"] for item in result["artifacts"]])
        self.assertEqual(["0" * 64, "1" * 64], [item["artifactId"] for item in result["artifacts"]])


if __name__ == "__main__":
    unittest.main()
