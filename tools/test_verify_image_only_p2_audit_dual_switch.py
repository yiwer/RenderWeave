#!/usr/bin/env python3

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_image_only_p2_audit_dual_switch.py")
SPEC = importlib.util.spec_from_file_location("p2_audit_dual_switch", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def build_event(run_id: str, sequence: int, code: str, previous: str) -> dict:
    event = {
        "runId": run_id,
        "sequence": sequence,
        "eventCode": code,
        "actorId": "actor-opaque-001",
        "confirmationId": None,
        "reservationId": None,
        "callAuthorizationId": None,
        "attemptOrdinal": sequence - 1,
        "inputFingerprint": "a" * 64,
        "profileId": "dashscope-qwen37-flash-v1",
        "profileSha256": "b" * 64,
        "decisionCode": None,
        "usageInputTokens": 1000,
        "usageOutputTokens": 500,
        "costMicrosCny": 42,
        "occurredAtEpochSecond": 1787054400,
        "occurredAtNano": 0,
        "previousEventDigest": previous,
        "eventDigest": "",
    }
    event["eventDigest"] = MODULE.event_digest(event)
    return event


def build_export() -> dict:
    run_id = "11111111-1111-4111-8111-111111111111"
    first = build_event(run_id, 1, "CALL_AUTHORIZED", MODULE.genesis_digest())
    second = build_event(run_id, 2, "CALL_DISPATCH_SUCCEEDED", first["eventDigest"])
    return {
        "exportVersion": "renderweave-live-admission-audit-export/1.0",
        "events": [first, second],
    }


class P2AuditDualSwitchVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[1]

    def copy_material(self, root: Path) -> None:
        for relative in MODULE.MATERIAL_PATHS:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.repository / relative, destination)

    def test_independent_chain_replay_classifies_vectors(self):
        export = build_export()
        self.assertEqual("OK", MODULE.chain_verdict(export["events"]))
        tampered = [dict(export["events"][0], costMicrosCny=999), export["events"][1]]
        self.assertEqual("TAMPERED", MODULE.chain_verdict(tampered))
        reordered = [export["events"][1], export["events"][0]]
        self.assertEqual("REORDERED", MODULE.chain_verdict(reordered))
        self.assertEqual("MISSING", MODULE.chain_verdict([export["events"][1]]))
        duplicated = export["events"] + [export["events"][1]]
        self.assertEqual("DUPLICATE", MODULE.chain_verdict(duplicated))

    def test_verifies_current_provider_zero_audit_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            export_path = Path(directory) / "audit-chain-export.json"
            export_path.write_text(json.dumps(build_export()), encoding="utf-8")
            report = MODULE.verify(self.repository, export_path)
        self.assertEqual("PASS", report["result"])
        self.assertEqual("OK", report["chainVerdict"])
        self.assertTrue(report["dualSwitchDefaultClosed"])
        self.assertTrue(report["atomicCallAuthorization"])
        self.assertEqual(0, report["verificationProviderUsage"]["attempts"])

    def test_contract_drift_fails_closed(self):
        export = build_export()
        mutations = (
            (0, "VALUES (1, FALSE, 'renderweave-system-bootstrap', 'DEFAULT_CLOSED'",
             "VALUES (1, TRUE, 'renderweave-system-bootstrap', 'DEFAULT_CLOSED'"),
            (1, "requireChainHealthy(command.runId())", "// chain check removed"),
            (0, "REVOKE UPDATE, DELETE ON live_admission_audit_event FROM renderweave_live_runtime",
             "-- revoke removed"),
        )
        for index, old, new in mutations:
            with self.subTest(relative=MODULE.MATERIAL_PATHS[index]), \
                    tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_material(root)
                path = root / MODULE.MATERIAL_PATHS[index]
                path.write_text(
                        path.read_text(encoding="utf-8").replace(old, new, 1),
                        encoding="utf-8")
                export_path = Path(directory) / "audit-chain-export.json"
                export_path.write_text(json.dumps(export), encoding="utf-8")
                with self.assertRaises(SystemExit):
                    MODULE.verify(root, export_path)


if __name__ == "__main__":
    unittest.main()
