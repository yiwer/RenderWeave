#!/usr/bin/env python3
"""Fast, dependency-free regression tests for the local RapidOCR adapter seam."""

from __future__ import annotations

import base64
import importlib.util
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

    def __call__(self, image, **_kwargs):
        self.image = image
        return types.SimpleNamespace(boxes=[], txts=[], scores=[])


class _DecodedImage:
    ndim = 3
    shape = (1, 1, 3)


class RapidOcrAdapterTest(unittest.TestCase):
    def test_artifact_decodes_encoded_image_before_ocr(self) -> None:
        decoded_image = _DecodedImage()
        fake_numpy = types.ModuleType("numpy")
        fake_numpy.uint8 = object()
        fake_numpy.frombuffer = lambda value, dtype: (value, dtype)
        fake_cv2 = types.ModuleType("cv2")
        fake_cv2.IMREAD_COLOR = 1
        fake_cv2.imdecode = lambda encoded, mode: decoded_image
        engine = _RecordingEngine()
        raw = {
            "artifactId": "0" * 64,
            "sourceOrdinal": 0,
            "mediaType": "image/jpeg",
            "width": 1,
            "height": 1,
            "base64": base64.b64encode(b"encoded-image").decode("ascii"),
        }

        with mock.patch.dict(sys.modules, {"cv2": fake_cv2, "numpy": fake_numpy}):
            ADAPTER._artifact(engine, raw)

        self.assertIs(engine.image, decoded_image)


if __name__ == "__main__":
    unittest.main()
