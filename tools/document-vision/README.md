# RenderWeave local document vision adapter

This optional adapter runs RapidOCR 3.9.2 with OpenVINO 2026.0.0 and the exact PP-OCRv6-small files listed in
`model-manifest.json`. It is disabled by default and is not downloaded or installed by an ordinary build.

The Java service invokes the script directly without a shell, clears inherited proxy/credential variables, imposes
input/output/time limits, and requires the exact capability id. The script also denies Python-level network access
before importing the OCR engine. Image bytes and OCR text use stdin/stdout only; they are not written to disk.

For an isolated local setup, create a Python environment, install `requirements.txt`, and copy the three model files
from the installed RapidOCR package's `rapidocr/models` directory into a dedicated model directory. Do not substitute
different files under the same names: startup probing verifies every SHA-256 and fails closed on drift.

RapidOCR engineering code is Apache-2.0. Its project states that OCR model copyright belongs to Baidu/PaddleOCR;
review the upstream notices before redistribution. RenderWeave currently references, but does not vendor, those model
assets.
