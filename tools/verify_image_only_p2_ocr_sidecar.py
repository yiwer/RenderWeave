#!/usr/bin/env python3
"""Provider-zero source and evidence verifier for IOPA-P2-06 OCR sidecar admission."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


REPORT_VERSION = "renderweave-image-only-p2-ocr-sidecar/1.0"
CAPABILITY_ID = "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"
ENGINE_VERSION = "rapidocr-3.9.2+openvino-2026.0.0"
MODEL_MANIFEST_SHA256 = "c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5"
BASE_IMAGE_DIGEST = (
    "python:3.12-slim-bookworm@sha256:"
    "356b0d18f9385f4bdcc673af60e1e64c9d1504952e4ec36ee32044c722a6bc4e"
)
MATERIAL_PATHS = (
    "docker/ocr-sidecar/Dockerfile",
    "docker/ocr-sidecar/sidecar_server.py",
    "docker/ocr-sidecar/probe_client.py",
    "docker/ocr-sidecar/equivalence_probe.py",
    "docker/ocr-sidecar/extract_models.py",
    "docker/ocr-sidecar/requirements.lock",
    "docker/ocr-sidecar/headless.lock",
    "docker/ocr-sidecar/NOTICE.md",
    "compose.yaml",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/UnixDomainSocketDocumentVisionRunner.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/LocalProcessDocumentVisionPreprocessor.java",
    "renderweave-app/src/main/java/cn/hbads/renderweave/inference/InferenceApplicationConfiguration.java",
    "renderweave-app/src/test/java/cn/hbads/renderweave/inference/UnixDomainSocketDocumentVisionRunnerTest.java",
    "tools/document-vision/rapidocr_adapter.py",
    "tools/document-vision/model-manifest.json",
    "docs/adr/0053-run-production-ocr-as-no-ip-unix-socket-sidecar.md",
)
FORBIDDEN_MARKERS = (
    "api key", "private key", "authorization:", "data:image",
    "chain-of-thought", "sk-canary",
)


def fail(code: str) -> None:
    raise SystemExit(code)


def source(repository: Path, index: int) -> str:
    try:
        return (repository / MATERIAL_PATHS[index]).read_text(encoding="utf-8")
    except Exception as error:
        raise SystemExit("P2_OCR_SIDECAR_SOURCE_MISSING") from error


def require_fragments(value: str, fragments: tuple[str, ...], code: str) -> None:
    if any(fragment not in value for fragment in fragments):
        fail(code)


def require_contract(repository: Path) -> None:
    dockerfile = source(repository, 0)
    server = source(repository, 1)
    client = source(repository, 2)
    equivalence = source(repository, 3)
    extract = source(repository, 4)
    lock = source(repository, 5)
    headless = source(repository, 6)
    notice = source(repository, 7)
    compose = source(repository, 8)
    runner = source(repository, 9)
    preprocessor = source(repository, 10)
    configuration = source(repository, 11)
    runner_test = source(repository, 12)
    stdio_adapter = source(repository, 13)
    manifest = json.loads(source(repository, 14))
    adr = source(repository, 15)

    require_fragments(dockerfile, (
        BASE_IMAGE_DIGEST,
        "--no-index", "--require-hashes",
        "extract_models.py",
        "useradd --system --uid 10001",
        "OPENBLAS_NUM_THREADS=1",
        "USER ocrsidecar",
    ), "P2_OCR_SIDECAR_DOCKERFILE_DRIFT")
    if any(line.strip().startswith("EXPOSE") for line in dockerfile.splitlines()):
        fail("P2_OCR_SIDECAR_NETWORK_EXPOSE_FORBIDDEN")
    require_fragments(server, (
        "CAPABILITY_ID = \"" + CAPABILITY_ID + "\"",
        "MODEL_MANIFEST_SHA256 = \"" + MODEL_MANIFEST_SHA256 + "\"",
        "UnixStreamServer",
        "run_synthetic_probe",
        "[\"RW01\", \"RW02\"]",
        "_deny_network",
        "def log_message",
    ), "P2_OCR_SIDECAR_SERVER_DRIFT")
    require_fragments(client, (
        "AF_UNIX", "/health", "/capability", "/ocr",
    ), "P2_OCR_SIDECAR_CLIENT_DRIFT")
    require_fragments(equivalence, (
        "OCR_EQUIVALENCE_MISMATCH", "byteIdentical", "rapidocr_adapter.py",
    ), "P2_OCR_SIDECAR_EQUIVALENCE_DRIFT")
    require_fragments(extract, (
        "090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f",
        "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c",
        "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884",
        "OCR_SIDECAR_MODEL_IDENTITY_MISMATCH",
    ), "P2_OCR_SIDECAR_EXTRACT_DRIFT")
    for required in ("rapidocr==3.9.2", "openvino==2026.0.0", "omegaconf==2.3.0",
                     "antlr4-python3-runtime==4.9.3", "--hash=sha256:"):
        if required not in lock:
            fail("P2_OCR_SIDECAR_LOCK_DRIFT")
    if "--hash=sha256:" not in headless or "opencv-python-headless==5.0.0.93" not in headless:
        fail("P2_OCR_SIDECAR_HEADLESS_LOCK_DRIFT")
    require_fragments(notice, (
        "Apache-2.0", "Baidu/PaddleOCR", "owner license J1", "opencv-python-headless",
        "antlr4-python3-runtime 4.9.3",
    ), "P2_OCR_SIDECAR_NOTICE_DRIFT")
    require_fragments(compose, (
        "ocr-sidecar", 'network_mode: "none"', "read_only: true",
        "no-new-privileges:true", "mem_limit: 2g", "cpus: 2", "pids_limit: 64",
        "renderweave-ocr-socket",
        "RENDERWEAVE_INFERENCE_DOCUMENT_VISION_UD_SOCKET",
    ), "P2_OCR_SIDECAR_COMPOSE_DRIFT")
    require_fragments(runner, (
        "StandardProtocolFamily.UNIX", "UnixDomainSocketAddress",
        "DOCUMENT_VISION_TIMEOUT", "/capability", "/ocr",
    ), "P2_OCR_SIDECAR_RUNNER_DRIFT")
    if "java.net.Socket" in runner or "new URL(" in runner:
        fail("P2_OCR_SIDECAR_TCP_FORBIDDEN")
    require_fragments(preprocessor, (
        "forUnixSocket", "UnixDomainSocketDocumentVisionRunner",
        "DOCUMENT_VISION_SOCKET_MISSING",
    ), "P2_OCR_SIDECAR_PREPROCESSOR_DRIFT")
    require_fragments(configuration, (
        "renderweave.inference.document-vision.ud-socket",
        "LocalProcessDocumentVisionPreprocessor.forUnixSocket",
    ), "P2_OCR_SIDECAR_CONFIGURATION_DRIFT")
    require_fragments(runner_test, (
        "capabilityProbeAndPreprocessRoundTripSucceedOverUnixSocket",
        "typedSidecarErrorsPropagateAsDocumentVisionCodes",
        "unresponsiveSidecarFailsClosedWithTimeout",
        "missingSocketFailsClosedBeforeAnyDispatch",
    ), "P2_OCR_SIDECAR_RUNNER_TEST_DRIFT")
    require_fragments(stdio_adapter, (
        "CAPABILITY_ID = \"" + CAPABILITY_ID + "\"",
    ), "P2_OCR_SIDECAR_STDIO_ADAPTER_DRIFT")
    if manifest.get("manifestSha256") != MODEL_MANIFEST_SHA256:
        fail("P2_OCR_SIDECAR_MANIFEST_DRIFT")
    require_fragments(adr, (
        "status: accepted", "no IP networking", "byte-identical",
        "opencv-python-headless", "antlr4-python3-runtime==4.9.3",
        "DOCUMENT_VISION_UNAVAILABLE", "owner license J1",
    ), "P2_OCR_SIDECAR_ADR_DRIFT")


def load_probe(evidence: Path, name: str, code: str) -> dict[str, Any]:
    try:
        # utf-8-sig tolerates the BOM written by Windows PowerShell 5.1 Set-Content.
        return json.loads((evidence / name).read_text(encoding="utf-8-sig"))
    except Exception as error:
        raise SystemExit(code) from error


def require_probes(evidence: Path) -> dict[str, Any]:
    startup = load_probe(evidence, "sidecar-startup-probe.json",
                         "P2_OCR_SIDECAR_STARTUP_PROBE_MISSING")
    if startup.get("capabilityId") != CAPABILITY_ID:
        fail("P2_OCR_SIDECAR_STARTUP_CAPABILITY_DRIFT")
    if startup.get("engineVersion") != ENGINE_VERSION:
        fail("P2_OCR_SIDECAR_STARTUP_ENGINE_DRIFT")
    if startup.get("modelManifestSha256") != MODEL_MANIFEST_SHA256:
        fail("P2_OCR_SIDECAR_STARTUP_MANIFEST_DRIFT")

    uds = load_probe(evidence, "sidecar-uds-probe.json", "P2_OCR_SIDECAR_UDS_PROBE_MISSING")
    if uds.get("health", {}).get("status") != 200 \
            or uds.get("health", {}).get("body", {}).get("status") != "ok":
        fail("P2_OCR_SIDECAR_UDS_HEALTH_DRIFT")
    if uds.get("capability", {}).get("status") != 200 \
            or uds.get("capability", {}).get("body", {}).get("capabilityId") != CAPABILITY_ID:
        fail("P2_OCR_SIDECAR_UDS_CAPABILITY_DRIFT")
    ocr = uds.get("ocr", {})
    if ocr.get("status") != 200:
        fail("P2_OCR_SIDECAR_UDS_OCR_DRIFT")
    lines = ocr.get("body", {}).get("artifacts", [{}])[0].get("lines", [])
    if [line.get("text") for line in lines] != ["RW01", "RW02"] \
            or any(line.get("confidenceBps", 0) < 9_000 for line in lines):
        fail("P2_OCR_SIDECAR_UDS_SYNTHETIC_DRIFT")

    equivalence = load_probe(evidence, "sidecar-equivalence.json",
                             "P2_OCR_SIDECAR_EQUIVALENCE_MISSING")
    if equivalence.get("result") != "PASS" or equivalence.get("byteIdentical") is not True:
        fail("P2_OCR_SIDECAR_EQUIVALENCE_FAILED")
    if equivalence.get("capabilityId") != CAPABILITY_ID:
        fail("P2_OCR_SIDECAR_EQUIVALENCE_CAPABILITY_DRIFT")

    hardening = load_probe(evidence, "sidecar-hardening.json",
                           "P2_OCR_SIDECAR_HARDENING_MISSING")
    if hardening.get("runtimeUid") != 10001:
        fail("P2_OCR_SIDECAR_NONROOT_DRIFT")
    if hardening.get("rootfsWritable") is not False:
        fail("P2_OCR_SIDECAR_READONLY_DRIFT")
    if hardening.get("effectiveCapabilities") != "0000000000000000":
        fail("P2_OCR_SIDECAR_CAPS_DRIFT")
    if hardening.get("imageId") == "":
        fail("P2_OCR_SIDECAR_IMAGE_ID_MISSING")
    return {
        "imageId": hardening.get("imageId"),
        "equivalenceLineCount": equivalence.get("lineCount"),
    }


def implementation_identity(repository: Path) -> str:
    value = hashlib.sha256()
    for relative in MATERIAL_PATHS:
        raw = (repository / relative).read_bytes()
        path = relative.encode("utf-8")
        value.update(str(len(path)).encode("ascii") + b":" + path + b"\n")
        value.update(str(len(raw)).encode("ascii") + b":" + raw + b"\n")
    return REPORT_VERSION + ":" + value.hexdigest()


def require_no_open_authorization(repository: Path) -> int:
    count = 0
    for path in (repository / "plans/live-canary-authorizations").glob("20*.json"):
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            raise SystemExit("P2_OCR_SIDECAR_AUTHORIZATION_INVENTORY_INVALID")
        if type(value) is not dict or value.get("status") not in {"PROPOSED", "OPEN", "CLOSED"}:
            fail("P2_OCR_SIDECAR_AUTHORIZATION_INVENTORY_INVALID")
        if value.get("status") == "OPEN":
            count += 1
    if count:
        fail("P2_OCR_SIDECAR_OPEN_AUTHORIZATION_FORBIDDEN")
    return count


def verify(repository: Path, evidence: Path) -> dict[str, Any]:
    require_contract(repository)
    probes = require_probes(evidence)
    open_count = require_no_open_authorization(repository)
    return {
        "reportVersion": REPORT_VERSION,
        "result": "PASS",
        "stage": "IOPA_P2_06_OCR_SIDECAR",
        "implementationIdentity": implementation_identity(repository),
        "capabilityId": CAPABILITY_ID,
        "engineVersion": ENGINE_VERSION,
        "modelManifestSha256": MODEL_MANIFEST_SHA256,
        "baseImage": BASE_IMAGE_DIGEST,
        "imageId": probes["imageId"],
        "offlineBuild": True,
        "networkModeNone": True,
        "readOnlyRootfs": True,
        "nonRoot": True,
        "capabilitiesDropped": True,
        "pidLimit": 64,
        "memoryLimit": "2g",
        "cpuLimit": 2,
        "startupProbeBlocking": True,
        "syntheticProbeFixedOutput": True,
        "r0EquivalenceByteIdentical": True,
        "equivalenceLineCount": probes["equivalenceLineCount"],
        "capabilityAdmitted": False,
        "licenseJudgement": "J0_PENDING",
        "openAuthorizationCount": open_count,
        "verificationProviderUsage": {
            "attempts": 0, "reservations": 0, "modelTokens": 0,
            "costMicrosCny": 0, "apiKeyReads": 0,
        },
        "productionConfigured": False,
        "productionLiveAuthorityGranted": False,
        "candidateApplied": False,
        "staticSchemaPublished": False,
        "productionDeployed": False,
        "payloadFree": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = verify(args.repository.resolve(), args.evidence.resolve())
    encoded = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if any(marker in encoded.lower() for marker in FORBIDDEN_MARKERS):
        fail("P2_OCR_SIDECAR_SUMMARY_PAYLOAD_LEAK")
    args.output.write_text(encoded, encoding="utf-8")


if __name__ == "__main__":
    main()
