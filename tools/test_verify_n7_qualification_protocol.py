#!/usr/bin/env python3
"""Boundary, tamper, authority and deterministic-selection tests for N7 protocol A2."""

from __future__ import annotations

import copy
import importlib.util
import pathlib
import unittest


PATH = pathlib.Path(__file__).with_name("verify_n7_qualification_protocol.py")
SPEC = importlib.util.spec_from_file_location("n7_protocol_verifier", PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("N7 protocol verifier cannot be loaded")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
REPOSITORY = PATH.parent.parent


class N7QualificationProtocolVerifierTest(unittest.TestCase):
    def test_frozen_protocol_replays(self) -> None:
        summary = VERIFIER.verify_protocol(REPOSITORY)
        self.assertEqual("PASS", summary["result"])
        self.assertEqual(5, summary["canaryCases"])
        self.assertEqual(20, summary["qualificationCases"])
        self.assertEqual(15, summary["finalHoldoutCases"])
        self.assertEqual(0, summary["providerAttempts"])

    def test_matrix_and_selection_boundaries_are_fixed(self) -> None:
        passing = metrics(9_500)
        hard_failure = metrics(9_500)
        hard_failure["schemaEntityF1Bps"] = 8_999
        self.assertEqual("STOP_TO_SPEC", VERIFIER.route(evidence(passing, passing)))
        self.assertEqual("MAX_ELIGIBLE", VERIFIER.route(evidence(passing, hard_failure)))
        self.assertEqual("NO_CHALLENGER", VERIFIER.route(evidence(hard_failure, hard_failure)))
        broken = evidence(passing, passing)
        broken["bundleContractBps"] = 9_999
        self.assertEqual("NO_CHALLENGER", VERIFIER.route(broken))

        plus = candidate("dashscope-qwen37-plus-product-v45-hybrid-generic", 9_500, 10, 20)
        max_near = candidate("dashscope-qwen38-max-product-v45-hybrid-generic", 9_600, 20, 10)
        self.assertEqual(plus["profileId"], VERIFIER.select([plus, max_near])["profileId"])
        max_better = candidate("dashscope-qwen38-max-product-v45-hybrid-generic", 9_900, 20, 10)
        self.assertEqual(max_better["profileId"], VERIFIER.select([plus, max_better])["profileId"])

    def test_trigger_threshold_assignment_profile_and_architecture_tamper_fail(self) -> None:
        resource = REPOSITORY.joinpath(*VERIFIER.RESOURCE.parts)
        raw = resource.read_bytes()
        original = VERIFIER.layered.parse_strict_json(raw.decode("utf-8"))
        changes = []
        trigger = copy.deepcopy(original)
        trigger["triggerDispositions"]["R2"]["status"] = "TRIGGERED"
        changes.append(trigger)
        threshold = copy.deepcopy(original)
        threshold["thresholds"]["fieldMicroF1Bps"] = 8_999
        changes.append(threshold)
        holdout = copy.deepcopy(original)
        holdout["qualificationCaseIds"][0] = "transit-board-v5"
        changes.append(holdout)
        flash = copy.deepcopy(original)
        flash["profiles"]["FLASH"]["available"] = True
        changes.append(flash)
        architecture = copy.deepcopy(original)
        architecture["architecture"]["langGraph"] = True
        changes.append(architecture)
        for changed in changes:
            with self.subTest(index=changes.index(changed)):
                with self.assertRaises(VERIFIER.VerificationError):
                    VERIFIER.verify_document(changed, VERIFIER.layered.canonical_json(changed), REPOSITORY)


def metrics(value: int) -> dict[str, int]:
    return {
        "schemaEntityF1Bps": value, "fieldMicroF1Bps": value,
        "supportedTypeAccuracyBps": max(value, 9_500),
        "parentChildEdgeF1Bps": max(value, 9_500),
    }


def evidence(global_metrics: dict[str, int], hard: dict[str, int]) -> dict[str, object]:
    return {
        "assignmentExact": True, "identityExact": True, "holdoutUntouched": True,
        "terminalReviewRequiredBps": 10_000, "bundleContractBps": 10_000,
        "evidenceCoverageBps": 10_000, "dagValidityBps": 10_000,
        "criticalHallucinations": 0, "payloadViolations": 0,
        "identityViolations": 0, "budgetViolations": 0,
        "global": global_metrics, "hardNested": hard, "algorithmChangeRequired": False,
    }


def candidate(profile_id: str, value: int, cost: int, latency: int) -> dict[str, object]:
    return {"profileId": profile_id, "qualified": True, "metrics": metrics(value),
            "estimatedCostMicrosCny": cost, "p95LatencyMillis": latency}


if __name__ == "__main__":
    unittest.main()
