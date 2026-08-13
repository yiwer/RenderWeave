#!/usr/bin/env python3
"""Tamper and quality-gate tests for the independent N7 live evidence verifier."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import pathlib
import unittest


PATH = pathlib.Path(__file__).with_name("verify_n7_live_evidence.py")
SPEC = importlib.util.spec_from_file_location("n7_live_evidence_verifier", PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("N7 live evidence verifier cannot be loaded")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
REPOSITORY = PATH.parent.parent


class N7LiveEvidenceVerifierTest(unittest.TestCase):
    def test_exact_five_case_report_and_terminal_journal_pass(self) -> None:
        fixture = make_fixture()
        results, terminals, latency = VERIFIER.validate_n7_journal(
            fixture["journal"], fixture["authorization"], fixture["metadata"],
            fixture["reservations"],
        )
        summary = VERIFIER.validate_report_envelope(
            fixture["envelope"], fixture["contract"], fixture["authorization"],
            results, fixture["metadata"], fixture["contractIdentity"],
        )
        self.assertEqual(["REVIEW_REQUIRED"] * 5, terminals)
        self.assertEqual(500, latency)
        self.assertEqual("PASS", summary["qualityDecision"])
        self.assertEqual(10_000, summary["averageBundleContractBps"])
        self.assertEqual(10_000, summary["evidenceCoverageBps"])
        self.assertEqual(10_000, summary["averageDagValidityBps"])
        self.assertEqual(0, summary["criticalHallucinations"])

    def test_terminal_usage_metric_and_identity_tampering_fail_closed(self) -> None:
        fixture = make_fixture()
        mutations = []

        terminal = copy.deepcopy(fixture)
        terminal["journal"]["executions"][0]["terminalState"] = "SUCCEEDED"
        mutations.append((terminal, "journal"))

        usage = copy.deepcopy(fixture)
        usage["journal"]["executions"][0]["attempts"][0]["inputTokens"] += 1
        mutations.append((usage, "journal"))

        metric = copy.deepcopy(fixture)
        metric["envelope"]["report"]["global"]["finalCandidate"][
            "criticalHallucinations"
        ] = 1
        metric["envelope"]["reportIdentity"] = VERIFIER.report_identity(
            metric["envelope"]["report"]
        )
        mutations.append((metric, "report"))

        identity = copy.deepcopy(fixture)
        identity["envelope"]["reportIdentity"] = (
            VERIFIER.REPORT_VERSION + ":" + "0" * 64
        )
        mutations.append((identity, "report"))

        for changed, seam in mutations:
            with self.subTest(seam=seam):
                if seam == "journal":
                    with self.assertRaises(VERIFIER.VerificationError):
                        VERIFIER.validate_n7_journal(
                            changed["journal"], changed["authorization"],
                            changed["metadata"], changed["reservations"],
                        )
                else:
                    results, _terminals, _latency = VERIFIER.validate_n7_journal(
                        changed["journal"], changed["authorization"],
                        changed["metadata"], changed["reservations"],
                    )
                    with self.assertRaises(VERIFIER.VerificationError):
                        VERIFIER.validate_report_envelope(
                            changed["envelope"], changed["contract"],
                            changed["authorization"], results, changed["metadata"],
                            changed["contractIdentity"],
                        )

    def test_payload_markers_and_duplicate_members_are_rejected(self) -> None:
        with self.assertRaises(VERIFIER.VerificationError):
            VERIFIER.parse_payload_free_json('{"prompt":"forbidden"}', "unit")
        with self.assertRaises(VERIFIER.VerificationError):
            VERIFIER.parse_payload_free_json('{"result":1,"result":2}', "unit")


def make_fixture() -> dict[str, object]:
    contract, contract_identity = VERIFIER.admission.verify_contract(REPOSITORY)
    corpus, raw = VERIFIER.visual.read_json(
        REPOSITORY.joinpath(*VERIFIER.SEMANTIC_CORPUS_PATH.parts), payload_free=False,
    )
    _all_cases, metadata = VERIFIER.visual.corpus_cases(
        corpus, hashlib.sha256(raw.encode("utf-8")).hexdigest(),
    )
    evaluation_identity = "renderweave-visual-evaluation-tree-sha256/2:" + "a" * 64
    authorization = {
        "authorizationVersion": VERIFIER.visual.AUTH_VERSION,
        "authorizationId": contract["authorizationId"],
        "status": "CLOSED",
        "phase": "CANARY",
        "inputClassification": contract["inputClassification"],
        "corpusVersion": contract["corpusVersion"],
        "corpusSourceSha256": contract["corpusSourceSha256"],
        "evaluationIdentity": evaluation_identity,
        "profileId": contract["profileId"],
        "profileSnapshotSha256": contract["profileSnapshotSha256"],
        "model": contract["model"],
        "caseIds": contract["caseIds"],
        "maximumProviderAttempts": contract["maximumProviderAttempts"],
        "maximumTotalTokens": contract["maximumTotalTokens"],
        "maximumCostMicrosCny": contract["maximumCostMicrosCny"],
        "maximumCasesPerBatch": contract["maximumCasesPerBatch"],
        "approvedBy": "yiwer",
        "approvedAt": "2026-08-13T13:00:00Z",
        "expiresAt": "2026-08-14T13:00:00Z",
        "approvalScope": contract_identity,
    }
    reservations = []
    executions = []
    results = []
    for index, case_id in enumerate(contract["caseIds"]):
        run_id = f"00000000-0000-4000-8000-{index:012d}"
        reservation_id = f"10000000-0000-4000-8000-{index:012d}"
        reservation = {
            "reservationId": reservation_id,
            "authorizationId": contract["authorizationId"],
            "profileId": contract["profileId"],
            "model": contract["model"],
            "runId": run_id,
            "attemptOrdinal": 0,
            "stage": "OBSERVE",
            "reservedTokens": 200,
            "reservedCostMicrosCny": 1_000,
            "actualInputTokens": 100,
            "actualOutputTokens": 50,
            "actualCostMicrosCny": 500,
            "state": "SETTLED",
            "createdAt": "2026-08-13T13:00:00Z",
            "updatedAt": "2026-08-13T13:00:01Z",
        }
        result = evaluation(case_id, metadata[case_id])
        reservations.append(reservation)
        results.append(result)
        executions.append({
            "assignmentKey": f'{contract["profileId"]}|{case_id}',
            "executionId": f"20000000-0000-4000-8000-{index:012d}",
            "caseId": case_id,
            "profileId": contract["profileId"],
            "model": contract["model"],
            "runId": run_id,
            "status": "COMPLETED",
            "evaluation": result,
            "attempts": [{
                "reservationId": reservation_id,
                "attemptOrdinal": 0,
                "stage": "OBSERVE",
                "outcomeCode": "LIVE_OUTPUT_ACCEPTED",
                "model": contract["model"],
                "inputTokens": 100,
                "outputTokens": 50,
                "costMicrosCny": 500,
                "latencyMillis": 100,
                "problemCodeCounts": {},
            }],
            "terminalState": "REVIEW_REQUIRED",
            "startedAt": "2026-08-13T13:00:00Z",
            "updatedAt": "2026-08-13T13:00:01Z",
            "completedAt": "2026-08-13T13:00:01Z",
        })
    journal = {
        "journalVersion": VERIFIER.JOURNAL_VERSION,
        "authorizationId": contract["authorizationId"],
        "executions": executions,
        "createdAt": "2026-08-13T13:00:00Z",
        "updatedAt": "2026-08-13T13:00:01Z",
    }
    report = VERIFIER.expected_report(
        contract, authorization, results, metadata, contract_identity,
    )
    envelope = {
        "envelopeVersion": VERIFIER.REPORT_ENVELOPE_VERSION,
        "reportIdentity": VERIFIER.report_identity(report),
        "report": report,
    }
    return {
        "contract": contract, "contractIdentity": contract_identity,
        "authorization": authorization, "metadata": metadata,
        "reservations": reservations, "journal": journal, "envelope": envelope,
    }


def counts() -> dict[str, int]:
    return {"expected": 1, "actual": 1, "matched": 1}


def evaluation(case_id: str, metadata: dict[str, str]) -> dict[str, object]:
    return {
        "caseId": case_id,
        "partition": metadata["partition"],
        "domainPack": metadata["domainPack"],
        "style": metadata["style"],
        "outcomeCode": "EVALUATED",
        "providerCalls": 1,
        "repairRounds": 0,
        "slots": counts(),
        "groups": counts(),
        "grounding": {
            "expected": 2, "semanticallyMatched": 2,
            "matchedAtIou50": 2, "matchedIouBpsSum": 20_000,
        },
        "entities": counts(),
        "relationships": counts(),
        "bindings": counts(),
        "survival": {
            "expectedSlots": 1, "observedSlots": 1,
            "correctlyBoundSlots": 1, "candidateSlots": 1,
        },
        "treeEditDistance": 0,
        "treeEditDenominator": 1,
        "calibrationBins": [
            {
                "binIndex": index, "count": 1 if index == 9 else 0,
                "correct": 1 if index == 9 else 0,
                "confidenceBpsSum": 10_000 if index == 9 else 0,
                "squaredErrorBpsSum": 0,
            }
            for index in range(10)
        ],
        "finalCandidate": {
            "outcomeCode": "EVALUATED", "passed": True,
            "bundleContractBps": 10_000,
            "entities": counts(), "fields": counts(), "relationships": counts(),
            "supportedTypeExpected": 1, "supportedTypeMatched": 1,
            "evidenceExpected": 1, "evidencePresent": 1,
            "dagValidityBps": 10_000, "criticalHallucinations": 0, "blockers": 0,
        },
    }


if __name__ == "__main__":
    unittest.main()
