#!/usr/bin/env python3
"""In-container UDS probe client for the OCR sidecar (stdlib only).

Used by the build gate to exercise /health, /capability and /ocr over the Unix socket without
ever touching IP networking.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import http.client
import json
import socket
import sys


class UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, socket_path: str, timeout: float = 60.0):
        super().__init__("localhost", timeout=timeout)
        self._socket_path = socket_path

    def connect(self) -> None:
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self._socket_path)


def main() -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--socket", default="/run/ocr/document-vision.sock")
    parser.add_argument("--mode", default="all", choices=("health", "capability", "ocr", "all"))
    args = parser.parse_args()

    results: dict[str, object] = {}
    connection = UnixHTTPConnection(args.socket)
    if args.mode in ("health", "all"):
        connection.request("GET", "/health")
        response = connection.getresponse()
        results["health"] = {"status": response.status, "body": json.loads(response.read())}
    if args.mode in ("capability", "all"):
        connection.request("GET", "/capability")
        response = connection.getresponse()
        results["capability"] = {"status": response.status, "body": json.loads(response.read())}
    if args.mode in ("ocr", "all"):
        from sidecar_server import REQUEST_VERSION, synthetic_probe_image

        payload = synthetic_probe_image()
        artifact_id = hashlib.sha256(b"renderweave-ocr-sidecar-synthetic-probe/1.0").hexdigest()
        request = json.dumps({
            "protocolVersion": REQUEST_VERSION,
            "artifacts": [{
                "artifactId": artifact_id,
                "sourceOrdinal": 0,
                "mediaType": "image/png",
                "width": 640,
                "height": 160,
                "base64": base64.b64encode(payload).decode("ascii"),
            }],
        }).encode("utf-8")
        connection.request(
                "POST", "/ocr", body=request,
                headers={"Content-Type": "application/json", "Content-Length": str(len(request))})
        response = connection.getresponse()
        results["ocr"] = {"status": response.status, "body": json.loads(response.read())}
    connection.close()
    sys.stdout.write(json.dumps(results, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
