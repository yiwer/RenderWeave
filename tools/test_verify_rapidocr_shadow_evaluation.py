#!/usr/bin/env python3
"""Goldens and tamper tests for the independent RapidOCR shadow verifier."""

from __future__ import annotations

import copy
import importlib.util
import pathlib
import unittest


PATH = pathlib.Path(__file__).with_name("verify_rapidocr_shadow_evaluation.py")
SPEC = importlib.util.spec_from_file_location("rapidocr_shadow_verifier", PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("RapidOCR shadow verifier cannot be loaded")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
REPOSITORY = PATH.parent.parent


class RapidOcrShadowVerifierTest(unittest.TestCase):
    def test_integer_metric_goldens(self) -> None:
        self.assertEqual(6_666, VERIFIER.success_rate(2, 3))
        self.assertEqual(3_333, VERIFIER.error_rate(1, 3))
        self.assertEqual(10_000, VERIFIER.success_rate(0, 0))
        self.assertEqual(0, VERIFIER.error_rate(0, 0))
        self.assertEqual(3, VERIFIER.percentile([1, 2, 3, 4, 5], 50))
        self.assertEqual(5, VERIFIER.percentile([1, 2, 3, 4, 5], 95))

    def test_complete_report_replays_and_single_point_tamper_fails(self) -> None:
        envelope = make_envelope()
        summary = VERIFIER.verify_envelope(envelope, REPOSITORY)
        self.assertEqual("PASS", summary["result"])
        self.assertEqual(60, summary["caseCount"])
        self.assertEqual(2, summary["runCount"])
        self.assertEqual(60, summary["metricsEquivalentCases"])
        self.assertEqual(0, summary["providerAttempts"])

        mutations = []
        identity = copy.deepcopy(envelope)
        identity["report"]["evaluationComponents"]["adapterIdentity"] = "changed/1.0"
        mutations.append(identity)
        metric = copy.deepcopy(envelope)
        metric["report"]["runs"][0]["global"]["metricsBps"]["ocr.cer"] += 1
        mutations.append(metric)
        assignment = copy.deepcopy(envelope)
        assignment["report"]["runs"][0]["records"][0]["diagnosticSlices"] = []
        mutations.append(assignment)
        trigger = copy.deepcopy(envelope)
        trigger["report"]["triggers"]["R2"]["triggered"] = True
        mutations.append(trigger)
        report_identity = copy.deepcopy(envelope)
        report_identity["reportIdentity"] = "renderweave-rapidocr-shadow-report/1.0:" + "0" * 64
        mutations.append(report_identity)
        for changed in mutations:
            with self.subTest(index=mutations.index(changed)):
                with self.assertRaises(VERIFIER.VerificationError):
                    VERIFIER.verify_envelope(changed, REPOSITORY)

    def test_payload_and_duplicate_members_fail_closed(self) -> None:
        with self.assertRaises(VERIFIER.layered.VerificationError):
            VERIFIER.layered.parse_strict_json('{"x":1,"x":2}')
        with self.assertRaises(VERIFIER.layered.VerificationError):
            VERIFIER.layered.scan_payload_free('{"ocrText":"secret"}', "unit")


def empty_record(locked: dict[str, object], diagnostics: list[str], latency: int) -> dict[str, object]:
    return {
        "recordVersion": VERIFIER.RECORD_VERSION,
        "caseId": locked["caseId"], "caseIdentity": locked["caseIdentity"],
        "partition": locked["partition"], "domain": locked["domain"],
        "difficulty": locked["difficulty"], "failureSlices": locked["failureSlices"],
        "diagnosticSlices": diagnostics,
        "ocr": {
            "cases": 1, "referenceCharacters": 1, "predictedCharacters": 0,
            "characterSubstitutions": 0, "characterInsertions": 0, "characterDeletions": 1,
            "referenceWords": 1, "predictedWords": 0, "wordSubstitutions": 0,
            "wordInsertions": 0, "wordDeletions": 1, "emptyReferenceCases": 0,
            "hallucinationCases": 0, "completeMissCases": 1,
        },
        "layout": {
            "lines": {"expected": 1, "predicted": 0, "matched": 0},
            "centerContainedMatches": 0, "predictedCoverageBpsSum": 0,
            "goldCoverageBpsSum": 0, "observedRegions": 0,
        },
        "order": {"expectedEdges": 0, "comparableEdges": 0, "correctEdges": 0,
                  "allReferencedRegionsObserved": True},
        "repeat": {"expectedGroups": 0, "completeGroups": 0, "expectedItems": 0,
                   "completeItems": 0, "expectedMemberships": 0, "observableMemberships": 0},
        "confidence": {"observations": 0, "nativeValueBpsSum": 0, "lowCount": 0,
                       "mediumCount": 0, "highCount": 0},
        "observationCount": 0, "acquisitionMicros": latency,
    }


def run_report(records: list[dict[str, object]], ordinal: int) -> dict[str, object]:
    return {
        "runOrdinal": ordinal, "expectedCaseCount": 60, "observedCaseCount": 60,
        "complete": True, "records": records, "global": VERIFIER.aggregate(records),
        "partitions": {key: VERIFIER.aggregate([item for item in records if item["partition"] == key])
                       for key in VERIFIER.layered.PARTITIONS},
        "domains": {key: VERIFIER.aggregate([item for item in records if item["domain"] == key])
                    for key in sorted({str(item["domain"]) for item in records})},
        "difficulties": {key: VERIFIER.aggregate([item for item in records if item["difficulty"] == key])
                         for key in VERIFIER.layered.DIFFICULTIES},
        "diagnosticSlices": {key: VERIFIER.aggregate([item for item in records
                                                       if key in item["diagnosticSlices"]])
                             for key in VERIFIER.DIAGNOSTIC_SLICES},
        "failureSlices": {key: VERIFIER.aggregate([item for item in records
                                                    if key in item["failureSlices"]])
                          for key in VERIFIER.layered.FAILURE_SLICES},
    }


def make_envelope() -> dict[str, object]:
    lock = VERIFIER.layered.verify_corpus_lock(REPOSITORY)
    diagnostics = VERIFIER.diagnostic_assignments(REPOSITORY)
    first = [empty_record(item, diagnostics[item["caseId"]], 10 + index)
             for index, item in enumerate(lock["cases"])]
    second = [empty_record(item, diagnostics[item["caseId"]], 20 + index)
              for index, item in enumerate(lock["cases"])]
    components = VERIFIER.expected_components(REPOSITORY)
    second_by_id = {item["caseId"]: item for item in second}
    facts = VERIFIER.evidence_facts(first, second_by_id)
    trigger = {"requiredEvidencePresent": False, "triggered": False,
               "reasonCode": "NOT_TRIGGERED_EVIDENCE_ABSENT"}
    report = {
        "reportVersion": VERIFIER.REPORT_VERSION,
        "evaluationIdentity": VERIFIER.evaluation_identity(components),
        "evaluationComponents": components,
        "corpusIdentity": lock["corpusIdentity"],
        "annotationSetIdentity": lock["annotationSetIdentity"],
        "shadowDiagnostic": True, "certificationEligible": False, "expectedCaseCount": 60,
        "runs": [run_report(first, 1), run_report(second, 2)],
        "determinism": {"comparedCases": 60, "metricsEquivalentCases": 60,
                        "observationEquivalentCases": 60, "deterministic": True,
                        "verdictCode": "DETERMINISTIC_TWO_RUNS"},
        "evidenceFacts": facts,
        "triggers": {key: copy.deepcopy(trigger) for key in VERIFIER.TRIGGERS},
        "externalProvider": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
    }
    return {"envelopeVersion": VERIFIER.ENVELOPE_VERSION,
            "reportIdentity": VERIFIER.report_identity(report), "report": report}


if __name__ == "__main__":
    unittest.main()
