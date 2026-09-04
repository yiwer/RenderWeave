# OCR sidecar provenance and license notice (IOPA-P2-06)

This build context vendors every artifact required for the production RapidOCR sidecar. The
build is fully offline: `pip --no-index --require-hashes` over `vendor/`, base image pinned by
digest, no downloads at build or startup.

## License disposition summary (owner license J1 required before capability admission)

- **RapidOCR 3.9.2** — Apache-2.0 (`License-Expression: Apache-2.0` in the vendored wheel
  metadata). The upstream wheel ships no LICENSE/NOTICE file; this notice records the
  disposition per ticket 08 §7 and does not substitute the owner's legal review.
- **OpenVINO 2026.0.0** — Apache-2.0 upstream.
- **PP-OCRv6-small / ch_ppocr_mobile_v2.0 ONNX models** — extracted byte-for-byte from the
  vendored RapidOCR wheel at build time (`extract_models.py` verifies each SHA-256 against
  `tools/document-vision/model-manifest.json`). Upstream states OCR model copyright belongs to
  Baidu/PaddleOCR (Apache-2.0 model releases); exact transformed-model disposition is part of
  the owner license J1.
- **opencv-python metadata + opencv-python-headless binaries (5.0.0.93)** — Apache-2.0.
  RapidOCR declares a dependency on `opencv_python`; the headless wheel overlays the identical
  cv2 binaries without GUI/X11 linkage so the sidecar needs no libGL/X stack. Both dist-infos
  are installed; the resulting cv2 module is the headless binary set. This substitution is
  recorded here and in the ADR; it changes no pixel-path behavior used by the adapter
  (`cv2.imdecode` with `IMREAD_COLOR`).
- **antlr4-python3-runtime 4.9.3** — internal wheel promoted from the verified PyPI sdist in the
  fixed builder (BSD-3-Clause upstream), required because the binary-only resolver otherwise
  selects an incompatible 2.0.0 line via omegaconf (ticket 03).
- **Transitive Python packages** — locked with exact versions and SHA-256 in
  `requirements.lock`/`headless.lock` (certifi, charset-normalizer, colorlog, idna, numpy,
  omegaconf, opencv-python, opencv-python-headless, openvino, openvino-telemetry, pillow,
  pyclipper, pyyaml, rapidocr, requests, shapely, six, tqdm, urllib3).
- **System packages** — only the Debian bookworm base image layers
  (`python:3.12-slim-bookworm@sha256:356b0d18…`); no additional OS packages are installed.

## Runtime surface

No IP networking (compose `network_mode: none` + Python-level outbound denial), read-only rootfs,
non-root UID 10001, all capabilities dropped, 2 CPU / 2 GiB / PID 64 / 60 s, OOM scoped to the
sidecar. The only interface is HTTP/1.1 over `/run/ocr/document-vision.sock`.

## SBOM

The software bill of materials for this capability is the pair of hash-locked requirement files
plus this notice and the base image digest; the gate summary re-asserts every identity
(`renderweave-image-only-p2-ocr-sidecar` verifier). CVE/malware scanning and signed attestation
digests are produced by the release gate chain before capability admission; any missing element
keeps the capability inadmissible and ImageOnlyReadiness fail-closed.
