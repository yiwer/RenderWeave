#!/usr/bin/env python3
"""Dependency-free negative and recomputation tests for the R0 evidence verifier."""

from __future__ import annotations

import importlib.util
import pathlib
import unittest


REPOSITORY = pathlib.Path(__file__).resolve().parents[1]
VERIFIER_PATH = pathlib.Path(__file__).with_name("verify_document_observation_r0.py")
SPEC = importlib.util.spec_from_file_location("renderweave_document_observation_r0", VERIFIER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("R0 verifier cannot be loaded")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class DocumentObservationR0VerifierTest(unittest.TestCase):
    def test_independently_recomputes_locked_projection_shape_and_successor_identities(self) -> None:
        self.assertEqual(4, VERIFIER.verify_projection_golden(REPOSITORY))
        self.assertEqual(
            "ad46adfbf6dc9e200f4736e693646ee485de5530af35b2f12802f561faa16557",
            VERIFIER.recompute_shape_catalog_identity(REPOSITORY),
        )
        self.assertEqual(
            "renderweave-document-observation-successor/1.0:"
            "302917d557bf7df9326b9a7d4af840c190be471041712806c19f932e24e1a3a2",
            VERIFIER.recompute_successor_identity(REPOSITORY),
        )

    def test_duplicate_members_and_forbidden_payload_fail_closed(self) -> None:
        with self.assertRaisesRegex(VERIFIER.VerificationError, "duplicate JSON member"):
            VERIFIER.parse_strict_json('{"result":"passed","result":"failed"}')
        with self.assertRaisesRegex(VERIFIER.VerificationError, "forbidden payload"):
            VERIFIER.scan_payload_free("safe-prefix OCR_SENTINEL secret", "unit-test")

    def test_external_provider_telemetry_must_remain_exactly_zero(self) -> None:
        with self.assertRaisesRegex(VERIFIER.VerificationError, "external Provider telemetry"):
            VERIFIER.require_external_provider_zero({
                "attempts": 1,
                "reservations": 0,
                "costMicrosCny": 0,
            })

    def test_active_authorization_slots_can_advance_without_weakening_product_snapshot_protection(self) -> None:
        rows = VERIFIER.protected_file_rows(REPOSITORY)
        paths = {row["path"] for row in rows}

        self.assertFalse(any(path.startswith(".sdlc/live/") for path in paths))
        self.assertIn(
            "renderweave-inference/src/main/resources/inference-profiles/"
            "dashscope-qwen37-plus-product-v45-hybrid-generic.json",
            paths,
        )


if __name__ == "__main__":
    unittest.main()
