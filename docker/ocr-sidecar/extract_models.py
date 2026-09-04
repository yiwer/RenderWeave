#!/usr/bin/env python3
"""Build-time extraction of the exact PP-OCRv6-small ONNX models from the vendored RapidOCR wheel.

Verifies each file against the frozen SHA-256 manifest before writing; fails closed on drift.
"""

from __future__ import annotations

import hashlib
import pathlib
import sys
import zipfile

MODEL_WHEEL = "rapidocr-3.9.2-py3-none-any.whl"
MODEL_FILES = {
    "rapidocr/models/PP-OCRv6_det_small.onnx": (
        "PP-OCRv6_det_small.onnx",
        9929594,
        "090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f",
    ),
    "rapidocr/models/ch_ppocr_mobile_v2.0_cls_mobile.onnx": (
        "ch_ppocr_mobile_v2.0_cls_mobile.onnx",
        585532,
        "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c",
    ),
    "rapidocr/models/PP-OCRv6_rec_small.onnx": (
        "PP-OCRv6_rec_small.onnx",
        21234383,
        "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884",
    ),
}


def main() -> int:
    vendor = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path("vendor")
    target = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else pathlib.Path("models")
    wheel = vendor / MODEL_WHEEL
    if not wheel.is_file():
        sys.stderr.write("OCR_SIDECAR_MODEL_WHEEL_MISSING\n")
        return 2
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(wheel) as archive:
        for entry, (name, size, sha256) in MODEL_FILES.items():
            payload = archive.read(entry)
            if len(payload) != size or hashlib.sha256(payload).hexdigest() != sha256:
                sys.stderr.write("OCR_SIDECAR_MODEL_IDENTITY_MISMATCH\n")
                return 2
            destination = target / name
            destination.write_bytes(payload)
            destination.chmod(0o444)
    sys.stdout.write("OCR_SIDECAR_MODELS_EXTRACTED\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
