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

    def test_closed_contract_rejects_unknown_members_at_every_level(self) -> None:
        mutations = (
            ("report", lambda value: value["report"].__setitem__("unexpectedEvidence", 0)),
            ("run", lambda value: value["report"]["runs"][0].__setitem__("unexpectedEvidence", 0)),
            ("record", lambda value: value["report"]["runs"][0]["records"][0]
             .__setitem__("unexpectedEvidence", 0)),
            ("aggregate", lambda value: value["report"]["runs"][0]["global"]
             .__setitem__("unexpectedEvidence", 0)),
            ("ocr", lambda value: value["report"]["runs"][0]["records"][0]["ocr"]
             .__setitem__("unexpectedEvidence", 0)),
            ("binary", lambda value: value["report"]["runs"][0]["records"][0]
             ["layout"]["lines"].__setitem__("unexpectedEvidence", 0)),
            ("layout", lambda value: value["report"]["runs"][0]["records"][0]["layout"]
             .__setitem__("unexpectedEvidence", 0)),
            ("order", lambda value: value["report"]["runs"][0]["records"][0]["order"]
             .__setitem__("unexpectedEvidence", 0)),
            ("repeat", lambda value: value["report"]["runs"][0]["records"][0]["repeat"]
             .__setitem__("unexpectedEvidence", 0)),
            ("confidence", lambda value: value["report"]["runs"][0]["records"][0]
             ["confidence"].__setitem__("unexpectedEvidence", 0)),
            ("latency", lambda value: value["report"]["runs"][0]["global"]
             ["acquisitionLatency"].__setitem__("unexpectedEvidence", 0)),
            ("determinism", lambda value: value["report"]["determinism"]
             .__setitem__("unexpectedEvidence", 0)),
            ("facts", lambda value: value["report"]["evidenceFacts"]
             .__setitem__("unexpectedEvidence", 0)),
            ("trigger", lambda value: value["report"]["triggers"]["R2"]
             .__setitem__("unexpectedEvidence", 0)),
            ("provider", lambda value: value["report"]["externalProvider"]
             .__setitem__("unexpectedEvidence", 0)),
        )
        for name, mutate in mutations:
            with self.subTest(name=name):
                changed = copy.deepcopy(make_envelope())
                mutate(changed)
                reidentify(changed)
                with self.assertRaises(VERIFIER.VerificationError):
                    VERIFIER.verify_envelope(changed, REPOSITORY)

    def test_contract_rejects_bool_float_and_java_numeric_overflow(self) -> None:
        mutations = (
            ("report-int-bool", lambda value: value["report"].__setitem__("expectedCaseCount", True)),
            ("run-int-float", lambda value: value["report"]["runs"][0]
             .__setitem__("runOrdinal", 1.0)),
            ("record-int-bool", lambda value: value["report"]["runs"][0]["records"][0]
             .__setitem__("observationCount", False)),
            ("record-long-overflow", lambda value: value["report"]["runs"][0]["records"][0]
             .__setitem__("acquisitionMicros", 9_223_372_036_854_775_808)),
            ("ocr-long-bool", lambda value: value["report"]["runs"][0]["records"][0]["ocr"]
             .__setitem__("cases", True)),
            ("binary-long-float", lambda value: value["report"]["runs"][0]["records"][0]
             ["layout"]["lines"].__setitem__("expected", 1.0)),
            ("order-bool-int", lambda value: value["report"]["runs"][0]["records"][0]["order"]
             .__setitem__("allReferencedRegionsObserved", 1)),
            ("confidence-long-bool", lambda value: value["report"]["runs"][0]["records"][0]
             ["confidence"].__setitem__("observations", False)),
            ("determinism-int-long", lambda value: value["report"]["determinism"]
             .__setitem__("comparedCases", 2_147_483_648)),
            ("provider-long-bool", lambda value: value["report"]["externalProvider"]
             .__setitem__("attempts", False)),
        )
        for name, mutate in mutations:
            with self.subTest(name=name):
                changed = copy.deepcopy(make_envelope())
                mutate(changed)
                reidentify(changed)
                with self.assertRaises(VERIFIER.VerificationError):
                    VERIFIER.verify_envelope(changed, REPOSITORY)

    def test_nested_accounting_relations_fail_closed(self) -> None:
        mutations = (
            ("ocr", lambda record: record["ocr"].__setitem__("hallucinationCases", 2)),
            ("binary", lambda record: record["layout"]["lines"].__setitem__("matched", 1)),
            ("layout", lambda record: record["layout"].__setitem__("centerContainedMatches", 1)),
            ("order", lambda record: record["order"].__setitem__("correctEdges", 1)),
            ("repeat", lambda record: record["repeat"].__setitem__("completeGroups", 1)),
            ("confidence", lambda record: record["confidence"].__setitem__("lowCount", 1)),
        )
        for name, mutate in mutations:
            with self.subTest(name=name):
                changed = copy.deepcopy(make_envelope())
                mutate(changed["report"]["runs"][0]["records"][0])
                reidentify(changed)
                with self.assertRaises(VERIFIER.VerificationError):
                    VERIFIER.verify_envelope(changed, REPOSITORY)

    def test_decoded_payload_marker_cannot_hide_behind_unicode_escape(self) -> None:
        changed = copy.deepcopy(make_envelope())
        changed["report"]["modelOutput"] = "opaque"
        reidentify(changed)
        raw = VERIFIER.canonical_json(changed).replace(
            b'"modelOutput"', b'"\\u006dodelOutput"')
        self.assertNotIn(b'"modelOutput"', raw)
        with self.assertRaises(VERIFIER.VerificationError):
            VERIFIER.verify_bytes(raw, REPOSITORY)

    def test_java_zero_denominator_metric_semantics(self) -> None:
        self.assertEqual(0, VERIFIER.precision({"expected": 1, "predicted": 0, "matched": 0}))
        self.assertEqual(10_000, VERIFIER.precision({"expected": 0, "predicted": 0, "matched": 0}))
        self.assertEqual(10_000, VERIFIER.ocr_error_rate(1, 0))
        self.assertEqual(0, VERIFIER.ocr_error_rate(0, 0))

    def test_load_verified_file_reads_and_binds_one_snapshot(self) -> None:
        raw = VERIFIER.canonical_json(make_envelope())

        class SingleReadSource:
            reads = 0

            def read_bytes(self) -> bytes:
                self.reads += 1
                if self.reads > 1:
                    raise AssertionError("shadow report was read more than once")
                return raw

        source = SingleReadSource()
        verified = VERIFIER.load_verified_file(source, REPOSITORY)
        self.assertEqual(1, source.reads)
        self.assertEqual("PASS", verified.summary["result"])
        self.assertEqual(verified.summary["reportIdentity"],
                         verified.envelope["reportIdentity"])
        self.assertEqual(VERIFIER.hashlib.sha256(raw).hexdigest(), verified.raw_sha256)


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


def reidentify(envelope: dict[str, object]) -> None:
    envelope["reportIdentity"] = VERIFIER.report_identity(envelope["report"])


if __name__ == "__main__":
    unittest.main()
