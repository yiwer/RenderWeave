#!/usr/bin/env python3
"""R0 behavior-equivalence probe: the UDS sidecar must reproduce the stdio adapter byte-for-byte.

Runs inside the sidecar container. Feeds identical request bytes to (a) the dev/offline stdio
adapter and (b) the production UDS server, then asserts exact JSON equality of the responses.
"""

from __future__ import annotations

import base64
import hashlib
import http.client
import io
import json
import pathlib
import socket
import subprocess
import sys

REQUEST_VERSION = "renderweave-document-vision-request/1.0"
ADAPTER_PATH = pathlib.Path("/opt/equivalence/rapidocr_adapter.py")
MODEL_ROOT = pathlib.Path("/opt/models")
SOCKET_PATH = "/run/ocr/document-vision.sock"


def render(text_a: str, text_b: str) -> bytes:
    from PIL import Image, ImageDraw, ImageFont

    image = Image.new("RGB", (640, 160), (255, 255, 255))
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=56)
    draw.text((24, 24), text_a, fill=(0, 0, 0), font=font)
    draw.text((24, 92), text_b, fill=(0, 0, 0), font=font)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def artifact(payload: bytes, ordinal: int, width: int, height: int) -> dict:
    return {
        "artifactId": hashlib.sha256(payload).hexdigest(),
        "sourceOrdinal": ordinal,
        "mediaType": "image/png",
        "width": width,
        "height": height,
        "base64": base64.b64encode(payload).decode("ascii"),
    }


def stdio_response(request_bytes: bytes) -> bytes:
    process = subprocess.run(
            [sys.executable, str(ADAPTER_PATH), "--model-root", str(MODEL_ROOT)],
            input=request_bytes, capture_output=True, timeout=120)
    if process.returncode != 0:
        sys.stderr.write(process.stderr.decode("utf-8", "replace"))
        raise SystemExit("OCR_EQUIVALENCE_STDIO_FAILED")
    return process.stdout


class UnixHTTPConnection(http.client.HTTPConnection):
    def connect(self) -> None:
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(120)
        self.sock.connect(SOCKET_PATH)


def uds_response(request_bytes: bytes) -> bytes:
    connection = UnixHTTPConnection("localhost")
    connection.request("POST", "/ocr", body=request_bytes, headers={
        "Content-Type": "application/json",
        "Content-Length": str(len(request_bytes)),
    })
    response = connection.getresponse()
    body = response.read()
    connection.close()
    if response.status != 200:
        sys.stderr.write(body.decode("utf-8", "replace"))
        raise SystemExit("OCR_EQUIVALENCE_UDS_FAILED")
    return body


def main() -> int:
    images = [render("RW01", "RW02"), render("AB12", "CD34")]
    request = {
        "protocolVersion": REQUEST_VERSION,
        "artifacts": [
            artifact(images[0], 0, 640, 160),
            artifact(images[1], 1, 640, 160),
        ],
    }
    request_bytes = json.dumps(request, separators=(",", ":"), sort_keys=True).encode("utf-8")

    via_stdio = stdio_response(request_bytes)
    via_uds = uds_response(request_bytes)

    parsed_stdio = json.loads(via_stdio)
    parsed_uds = json.loads(via_uds)
    if parsed_stdio != parsed_uds:
        sys.stderr.write("OCR_EQUIVALENCE_MISMATCH\n")
        return 2
    line_count = sum(len(item["lines"]) for item in parsed_uds["artifacts"])
    if line_count < 2:
        sys.stderr.write("OCR_EQUIVALENCE_OUTPUT_EMPTY\n")
        return 2
    summary = {
        "probeVersion": "renderweave-ocr-sidecar-equivalence/1.0",
        "result": "PASS",
        "capabilityId": parsed_uds["capabilityId"],
        "artifactCount": len(parsed_uds["artifacts"]),
        "lineCount": line_count,
        "byteIdentical": via_stdio == via_uds,
    }
    sys.stdout.write(json.dumps(summary, separators=(",", ":"), sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
