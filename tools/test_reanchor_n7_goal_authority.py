#!/usr/bin/env python3
"""Tests for one-shot N7 successor Goal authority materialization."""

from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest


TOOLS = pathlib.Path(__file__).parent
REPOSITORY = TOOLS.parent


def load(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path.name}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


REANCHOR = load("n7_goal_reanchor", TOOLS / "reanchor_n7_goal_authority.py")
VERIFIER = load("n7_live_admission_verifier_for_reanchor",
                TOOLS / "verify_n7_live_admission.py")
EVIDENCE_VERIFIER = load("visual_evidence_verifier_for_reanchor",
                         TOOLS / "verify_visual_eval_evidence.py")


class N7GoalAuthorityReanchorTest(unittest.TestCase):
    def test_reanchor_uses_reachable_byte_identical_source_revision(self) -> None:
        manifest, _identity = VERIFIER.verify_reanchor_manifest(REPOSITORY)

        self.assertEqual(
            "3c1e4d3a62382eb79b46fa644a20690aaea03497",
            manifest["anchorRevision"],
        )
        self.assertEqual(VERIFIER.EXPECTED_SOURCE_ANCHORS, manifest["sourceAnchors"])
        self.assertEqual(VERIFIER.EXPECTED_BASELINE, manifest["baseline"])
        self.assertFalse(manifest["decision"]["liveAuthorizationInherited"])

    def test_materializes_once_and_independent_admission_reconstructs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            goal = pathlib.Path(temporary) / "goal"
            result = REANCHOR.materialize(
                REPOSITORY, goal, "2026-08-13T05:00:00Z")

            self.assertEqual("MATERIALIZED", result["result"])
            self.assertEqual(0, result["providerAttemptsCreated"])
            self.assertEqual(418, result["lifetimeReservations"])
            self.assertEqual(6, result["quarantinedChargedReservations"])
            self.assertEqual(b"", (goal / "goal-budget.lock").read_bytes())

            contract, _identity = VERIFIER.verify_contract(REPOSITORY)
            audit = VERIFIER.inspect_goal(REPOSITORY, contract, goal)
            self.assertEqual("SUCCESSOR_AUTHORITY_EPOCH", audit["authority"])
            self.assertEqual("GOAL_READY", audit["admission"])
            self.assertEqual(0, audit["epochReservations"])
            self.assertEqual(418, audit["totalReservations"])
            self.assertEqual(6, audit["quarantinedChargedReservations"])
            self.assertEqual(179, audit["lifetimeSlots"]["qwen3.7-plus"]["attempts"])
            self.assertEqual(0, audit["slots"]["qwen3.7-plus"]["attempts"])

            guard_value, _guard_raw = EVIDENCE_VERIFIER.read_json(
                goal / "goal-budget.guard.json")
            state_value, _state_raw = EVIDENCE_VERIFIER.read_json(
                goal / "goal-budget.json")
            limits = EVIDENCE_VERIFIER.validate_goal_guard(guard_value)
            reservations, epoch_totals, lifetime_totals, quarantined = \
                EVIDENCE_VERIFIER.validate_goal(state_value, limits)
            self.assertEqual([], reservations)
            self.assertEqual(0, epoch_totals["qwen3.7-plus"]["attempts"])
            self.assertEqual(179, lifetime_totals["qwen3.7-plus"]["attempts"])
            self.assertEqual(6, quarantined)

            state_path = goal / "goal-budget.json"
            original_state = state_path.read_text(encoding="utf-8")
            state_path.write_text(original_state.replace(
                '"quarantinedChargedReservations": 6',
                '"quarantinedChargedReservations": 5', 1), encoding="utf-8")
            with self.assertRaises(VERIFIER.VerificationError):
                VERIFIER.inspect_goal(REPOSITORY, contract, goal)
            state_path.write_text(original_state, encoding="utf-8")

            guard_path = goal / "goal-budget.guard.json"
            original_guard = guard_path.read_text(encoding="utf-8")
            guard_path.write_text(original_guard.replace(
                '"epochMaximumTokensPerModel": 500000',
                '"epochMaximumTokensPerModel": 500001'), encoding="utf-8")
            with self.assertRaises(VERIFIER.VerificationError):
                VERIFIER.inspect_goal(REPOSITORY, contract, goal)
            guard_path.write_text(original_guard, encoding="utf-8")

            with self.assertRaises(REANCHOR.ReanchorError):
                REANCHOR.materialize(REPOSITORY, goal, "2026-08-13T05:00:01Z")

    def test_refuses_partial_preexisting_authority(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            goal = pathlib.Path(temporary) / "goal"
            goal.mkdir()
            (goal / "goal-budget.lock").write_bytes(b"")
            with self.assertRaises(REANCHOR.ReanchorError):
                REANCHOR.materialize(REPOSITORY, goal, "2026-08-13T05:00:00Z")


if __name__ == "__main__":
    unittest.main()
