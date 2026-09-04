#!/usr/bin/env python3

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_p2_ocr_sidecar.py")
SPEC = importlib.util.spec_from_file_location("p2_ocr_sidecar", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def write_probes(evidence: Path) -> None:
    capability = {
        "protocolVersion": "renderweave-document-vision-process-capability/1.0",
        "capabilityId": MODULE.CAPABILITY_ID,
        "engine": "rapidocr-openvino-ppocrv6-small",
        "engineVersion": MODULE.ENGINE_VERSION,
        "modelManifestSha256": MODULE.MODEL_MANIFEST_SHA256,
    }
    (evidence / "sidecar-startup-probe.json").write_text(
            json.dumps(capability), encoding="utf-8")
    uds = {
        "health": {"status": 200, "body": {"status": "ok"}},
        "capability": {"status": 200, "body": capability},
        "ocr": {"status": 200, "body": {
            "protocolVersion": "renderweave-document-vision-response/1.0",
            "capabilityId": MODULE.CAPABILITY_ID,
            "artifacts": [{"artifactId": "a" * 64, "sourceOrdinal": 0, "lines": [
                {"left": 1, "top": 2, "right": 30, "bottom": 12,
                 "confidenceBps": 9500, "text": "RW01"},
                {"left": 1, "top": 40, "right": 30, "bottom": 60,
                 "confidenceBps": 9400, "text": "RW02"},
            ]}],
        }},
    }
    (evidence / "sidecar-uds-probe.json").write_text(json.dumps(uds), encoding="utf-8")
    (evidence / "sidecar-equivalence.json").write_text(json.dumps({
        "probeVersion": "renderweave-ocr-sidecar-equivalence/1.0",
        "result": "PASS", "capabilityId": MODULE.CAPABILITY_ID,
        "artifactCount": 2, "lineCount": 4, "byteIdentical": True,
    }), encoding="utf-8")
    (evidence / "sidecar-hardening.json").write_text(json.dumps({
        "runtimeUid": 10001, "rootfsWritable": False,
        "effectiveCapabilities": "0000000000000000",
        "imageId": "sha256:" + "0" * 64,
    }), encoding="utf-8")


class P2OcrSidecarVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]

    def copy_material(self, root: Path) -> None:
        for relative in MODULE.MATERIAL_PATHS:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.repository / relative, destination)

    def test_verifies_current_provider_zero_sidecar_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence"
            evidence.mkdir()
            write_probes(evidence)
            report = MODULE.verify(self.repository, evidence)
        self.assertEqual("PASS", report["result"])
        self.assertTrue(report["r0EquivalenceByteIdentical"])
        self.assertFalse(report["capabilityAdmitted"])
        self.assertEqual("J0_PENDING", report["licenseJudgement"])
        self.assertEqual(0, report["verificationProviderUsage"]["attempts"])

    def test_contract_drift_fails_closed(self):
        mutations = (
            (0, MODULE.BASE_IMAGE_DIGEST, "python:3.12-slim-bookworm@sha256:" + "1" * 64),
            (5, "rapidocr==3.9.2", "rapidocr==3.9.3"),
            (8, 'network_mode: "none"', 'network_mode: "bridge"'),
        )
        for index, old, new in mutations:
            with self.subTest(relative=MODULE.MATERIAL_PATHS[index]), \
                    tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_material(root)
                evidence = root / "evidence"
                evidence.mkdir()
                write_probes(evidence)
                path = root / MODULE.MATERIAL_PATHS[index]
                path.write_text(
                        path.read_text(encoding="utf-8").replace(old, new, 1),
                        encoding="utf-8")
                with self.assertRaises(SystemExit):
                    MODULE.verify(root, evidence)


if __name__ == "__main__":
    unittest.main()
