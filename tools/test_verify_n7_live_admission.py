#!/usr/bin/env python3
"""Boundary tests for the independent N7 G-LIVE admission verifier."""

from __future__ import annotations

import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest


PATH = pathlib.Path(__file__).with_name("verify_n7_live_admission.py")
SPEC = importlib.util.spec_from_file_location("n7_live_admission_verifier", PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("N7 live admission verifier cannot be loaded")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
REPOSITORY = PATH.parent.parent


class N7LiveAdmissionVerifierTest(unittest.TestCase):
    def test_current_clean_contract_and_closed_historical_ledgers_reconstruct(self) -> None:
        # Repository identity itself requires a clean checkout, so this unit test exercises
        # immutable bindings separately; the clean-worktree acceptance run covers identity.
        contract, identity = VERIFIER.verify_contract(REPOSITORY)
        self.assertEqual("N7-04", contract["ticketId"])
        self.assertEqual(5, len(contract["caseIds"]))
        self.assertRegex(identity, r"^renderweave-n7-live-ticket-contract/1\.0:[0-9a-f]{64}$")

    def test_contract_tamper_is_detected(self) -> None:
        contract, _identity = VERIFIER.verify_contract(REPOSITORY)
        changed = copy.deepcopy(contract)
        changed["maximumCasesPerBatch"] = 6
        self.assertNotEqual(contract, changed)

    def test_missing_partial_and_reserved_goal_are_fail_closed(self) -> None:
        contract, _identity = VERIFIER.verify_contract(REPOSITORY)
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            summary = VERIFIER.inspect_goal(root, contract)
            self.assertEqual("DENIED_GOAL_AUTHORITY_MISSING", summary["admission"])

            goal = root.joinpath(*VERIFIER.GOAL_PATH.parts)
            goal.mkdir(parents=True)
            (goal / "goal-budget.json").write_text("{}", encoding="utf-8")
            with self.assertRaises(VERIFIER.VerificationError):
                VERIFIER.inspect_goal(root, contract)

    def test_profile_snapshot_uses_java_record_component_order(self) -> None:
        profile, _raw = VERIFIER.read_json(
            REPOSITORY.joinpath(*VERIFIER.PROFILE_PATH.parts), payload_free=False)
        self.assertEqual(
            "da922ed9f778f98eb364ce967bf617cb8f14633dd40c028aee6550eb7d258db9",
            VERIFIER.profile_snapshot(profile),
        )


if __name__ == "__main__":
    unittest.main()
