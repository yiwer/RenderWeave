import base64
import hashlib
import json
import pathlib
import re
import unittest

import r5p2_public_process as protocol


CAPABILITY = "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"


class FakeExecutor:
    def __init__(self):
        self.calls = []
        self.response_mutator = lambda value: value

    def __call__(self, command, stdin, timeout_seconds, environment):
        self.calls.append((tuple(command), bytes(stdin), dict(environment)))
        if "--capability" in command:
            value = {
                "protocolVersion": "renderweave-document-vision-process-capability/1.0",
                "capabilityId": CAPABILITY,
                "engine": "rapidocr-openvino-ppocrv6-small",
                "engineVersion": "rapidocr-3.9.2+openvino-2026.0.0",
                "modelManifestSha256": "c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5",
            }
        else:
            request = json.loads(stdin.decode("utf-8"))
            value = {
                "protocolVersion": "renderweave-document-vision-response/1.0",
                "capabilityId": CAPABILITY,
                "artifacts": [
                    {
                        "artifactId": item["artifactId"],
                        "sourceOrdinal": item["sourceOrdinal"],
                        "lines": [
                            {
                                "left": 0,
                                "top": 0,
                                "right": item["width"],
                                "bottom": item["height"],
                                "confidenceBps": 9000,
                                "text": f"artifact {item['sourceOrdinal']}",
                            }
                        ],
                    }
                    for item in request["artifacts"]
                ],
            }
            value = self.response_mutator(value)
        return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


class R5P2PublicProcessTest(unittest.TestCase):
    def test_one_complete_branch_is_one_public_process_with_separate_accounting(self):
        executor = FakeExecutor()
        client = protocol.PublicBranchProcessClient(
            ["python", "rapidocr_adapter.py"], "model-root", CAPABILITY, executor=executor
        )
        artifacts = [self.artifact(0, b"first"), self.artifact(1, b"second")]

        capability = client.probe()
        response = client.acquire_complete_branch(artifacts, [item.artifact_id for item in artifacts])

        self.assertEqual(CAPABILITY, capability["capabilityId"])
        self.assertEqual(2, len(response))
        self.assertEqual(2, len(executor.calls))
        self.assertIn("--capability", executor.calls[0][0])
        self.assertNotIn("--capability", executor.calls[1][0])
        request = json.loads(executor.calls[1][1].decode("utf-8"))
        self.assertEqual([item.artifact_id for item in artifacts], [
            item["artifactId"] for item in request["artifacts"]
        ])
        self.assertEqual(
            protocol.ProcessAccounting(capability_probe_processes=1,
                                       branch_acquisition_processes=1,
                                       artifact_views=2),
            client.accounting,
        )
        self.assertEqual("*", executor.calls[1][2]["NO_PROXY"])
        self.assertNotIn("DASHSCOPE_TOKEN_API_KEY", executor.calls[1][2])

    def test_rejects_partial_plan_and_reordered_response(self):
        executor = FakeExecutor()
        client = protocol.PublicBranchProcessClient(
            ["python", "rapidocr_adapter.py"], "model-root", CAPABILITY, executor=executor
        )
        first = self.artifact(0, b"first")
        second = self.artifact(1, b"second")
        client.probe()

        self.assertCode(
            "R5P2_BRANCH_PLAN_INCOMPLETE",
            lambda: client.acquire_complete_branch([first], [first.artifact_id, second.artifact_id]),
        )

        executor.response_mutator = lambda value: {
            **value,
            "artifacts": list(reversed(value["artifacts"])),
        }
        self.assertCode(
            "R5P2_BRANCH_RESPONSE_ARTIFACT_ORDER_INVALID",
            lambda: client.acquire_complete_branch(
                [first, second], [first.artifact_id, second.artifact_id]
            ),
        )

    def test_rejects_duplicate_trailing_unknown_and_noncanonical_payload(self):
        executor = FakeExecutor()
        client = protocol.PublicBranchProcessClient(
            ["python", "rapidocr_adapter.py"], "model-root", CAPABILITY, executor=executor
        )
        artifact = self.artifact(0, b"first")
        client.probe()

        bad_values = (
            b'{"protocolVersion":"renderweave-document-vision-response/1.0",'
            b'"protocolVersion":"renderweave-document-vision-response/1.0",'
            b'"capabilityId":"' + CAPABILITY.encode() + b'","artifacts":[]}',
            b'{"protocolVersion":"renderweave-document-vision-response/1.0",'
            b'"capabilityId":"' + CAPABILITY.encode() + b'","artifacts":[]}{}',
            json.dumps({
                "protocolVersion": "renderweave-document-vision-response/1.0",
                "capabilityId": CAPABILITY,
                "artifacts": [],
                "unknown": True,
            }).encode(),
        )
        for raw in bad_values:
            with self.subTest(raw=raw):
                executor.__call__ = lambda *args, value=raw: value
                client._executor = lambda *args, value=raw: value
                self.assertCode(
                    "R5P2_BRANCH_RESPONSE_JSON_INVALID",
                    lambda: client.acquire_complete_branch([artifact], [artifact.artifact_id]),
                )

    def test_module_never_imports_or_names_private_adapter_functions(self):
        source = pathlib.Path(protocol.__file__).read_text(encoding="utf-8")
        self.assertNotIn("rapidocr_adapter", source)
        for forbidden in ("_engine", "_artifact", "_preprocess"):
            self.assertIsNone(re.search(rf"\b{forbidden}\s*\(", source))

    def artifact(self, ordinal, payload):
        return protocol.BranchArtifact(
            artifact_id=hashlib.sha256(payload).hexdigest(),
            source_ordinal=ordinal,
            media_type="image/png",
            width=10,
            height=8,
            payload=payload,
        )

    def assertCode(self, code, action):
        with self.assertRaises(protocol.ProtocolError) as caught:
            action()
        self.assertEqual(code, str(caught.exception))


if __name__ == "__main__":
    unittest.main()
