#!/usr/bin/env python3
"""Independent metric golden, aggregation, identity, and tamper tests for the R1 verifier."""

from __future__ import annotations

import ast
import copy
import hashlib
import importlib.util
import json
import pathlib
import unittest


VERIFIER_PATH = pathlib.Path(__file__).with_name("verify_layered_evaluation.py")
SPEC = importlib.util.spec_from_file_location("renderweave_layered_evaluation", VERIFIER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("layered verifier cannot be loaded")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class LayeredEvaluationVerifierTest(unittest.TestCase):
    def test_metric_hand_goldens_match_frozen_java_contract(self) -> None:
        characters = VERIFIER.edit_counts(list("kitten"), list("sitting"))
        words = VERIFIER.edit_counts("alpha beta gamma".split(), "alpha delta".split())
        insertion = VERIFIER.edit_counts([], list("abc"))
        self.assertEqual((6, 7, 2, 1, 0), characters)
        self.assertEqual(5_000, VERIFIER.edit_rate_bps(characters))
        self.assertEqual((3, 2, 1, 0, 1), words)
        self.assertEqual(6_666, VERIFIER.edit_rate_bps(words))
        self.assertEqual((0, 3, 0, 3, 0), insertion)
        self.assertEqual(10_000, VERIFIER.edit_rate_bps(insertion))
        self.assertEqual(0, VERIFIER.edit_rate_bps((0, 0, 0, 0, 0)))

        self.assertEqual(10_000, VERIFIER.iou_bps((0, 0, 100, 100), (0, 0, 100, 100)))
        self.assertEqual(3_333, VERIFIER.iou_bps((0, 0, 100, 100), (50, 0, 150, 100)))
        score = VERIFIER.detection_score(
            [("a", "SLOT", (0, 0, 100, 100), 10_000),
             ("b", "SLOT", (200, 0, 300, 100), 10_000)],
            [("pa", "SLOT", (0, 0, 100, 100), 9_000),
             ("noise", "SLOT", (500, 500, 600, 600), 8_000)],
        )
        self.assertEqual(1, score["matchedAtIou50"])
        self.assertEqual(5_049, score["ap5095Bps"])
        self.assertEqual(10_000, VERIFIER.detection_score([], [])["ap5095Bps"])
        self.assertEqual(0, VERIFIER.detection_score(
            [("a", "SLOT", (0, 0, 100, 100), 10_000)], [],
        )["ap5095Bps"])

        counts = VERIFIER.set_counts(["a>b", "b>c", "c>d"], ["a>b", "b>c", "x>y"])
        self.assertEqual({"expected": 3, "predicted": 3, "matched": 2}, counts)
        self.assertEqual(6_666, VERIFIER.precision_bps(counts))
        self.assertEqual(6_666, VERIFIER.recall_bps(counts))
        self.assertEqual(6_666, VERIFIER.f1_bps(counts))
        self.assertFalse(VERIFIER.has_cycle(["a>b", "b>c"]))
        self.assertTrue(VERIFIER.has_cycle(["a>b", "b>c", "c>a"]))
        repeat = VERIFIER.repeat_score(
            [("g", "i1", "slot-a"), ("g", "i2", "slot-b")],
            [("g", "i1", "slot-a")],
            2,
            1,
        )
        self.assertEqual(1, repeat["itemCountAbsoluteError"])
        self.assertEqual(5_000, VERIFIER.recall_bps(repeat["memberships"]))
        self.assertEqual(10_000, VERIFIER.precision_bps(repeat["memberships"]))
        bindings = VERIFIER.set_counts(
            ["slot-a>entity-a", "slot-b>entity-b"],
            ["slot-a>entity-a", "slot-b>entity-a"],
        )
        self.assertEqual(5_000, VERIFIER.f1_bps(bindings))
        self.assertEqual(0, VERIFIER.tree_edit_distance(
            ["root>items>item", "item>label>text"],
            ["root>items>item", "item>label>text"],
        ))
        self.assertEqual(2, VERIFIER.tree_edit_distance(
            ["root>items>item", "item>label>text"],
            ["root>items>item", "item>price>decimal"],
        ))
        bins = [
            {"count": 0, "correct": 0, "confidenceBpsSum": 0, "squaredErrorBpsSum": 0},
            {"count": 2, "correct": 1, "confidenceBpsSum": 14_000, "squaredErrorBpsSum": 1_000},
            {"count": 2, "correct": 2, "confidenceBpsSum": 18_000, "squaredErrorBpsSum": 200},
        ]
        self.assertEqual(1_500, VERIFIER.expected_calibration_error_bps(bins))
        self.assertEqual(300, VERIFIER.brier_score_bps(bins))
        self.assertEqual(0, VERIFIER.expected_calibration_error_bps([]))
        self.assertEqual(0, VERIFIER.brier_score_bps([]))

    def test_strict_json_and_payload_boundary_fail_closed(self) -> None:
        with self.assertRaisesRegex(VERIFIER.VerificationError, "DUPLICATE_JSON_MEMBER"):
            VERIFIER.parse_strict_json('{"result":"PASS","result":"FAIL"}')
        with self.assertRaisesRegex(VERIFIER.VerificationError, "NON_INTEGER_JSON_NUMBER"):
            VERIFIER.parse_strict_json('{"value":0.5}')
        with self.assertRaisesRegex(VERIFIER.VerificationError, "FORBIDDEN_PAYLOAD"):
            VERIFIER.scan_payload_free('{"ocrText":"secret"}', "unit")

    def test_full_report_recomputes_and_all_single_point_tamper_classes_fail(self) -> None:
        envelope = make_envelope()
        summary = VERIFIER.verify_envelope(envelope)
        self.assertEqual("PASS", summary["result"])
        self.assertEqual(60, summary["caseCount"])
        self.assertEqual({"DEV": 45, "HOLDOUT": 15}, summary["partitions"])
        self.assertRegex(
            summary["recomputedMetricsIdentity"],
            r"^renderweave-layered-recomputed-metrics/1\.0:[0-9a-f]{64}$",
        )
        self.assertRegex(
            summary["caseAssignmentIdentity"],
            r"^renderweave-layered-case-assignment/1\.0:[0-9a-f]{64}$",
        )
        self.assertEqual(22, summary["sliceAggregateCount"])
        self.assertEqual(60, sum(summary["difficulties"].values()))
        self.assertEqual(0, summary["providerAttempts"])
        self.assertEqual(0, summary["providerReservations"])
        self.assertEqual(0, summary["externalProviderCostMicrosCny"])

        mutations = []
        annotation = copy.deepcopy(envelope)
        annotation["report"]["evaluationComponents"]["annotationSetIdentity"] = identity("changed")
        mutations.append(annotation)
        assignment = copy.deepcopy(envelope)
        assignment["report"]["records"][0]["record"]["partition"] = "HOLDOUT"
        mutations.append(assignment)
        identity_drift = copy.deepcopy(envelope)
        identity_drift["report"]["evaluationIdentity"] = identity("changed-evaluation")
        mutations.append(identity_drift)
        record = copy.deepcopy(envelope)
        record["report"]["records"][0]["record"]["ocr"]["characterInsertions"] = 1
        mutations.append(record)
        report = copy.deepcopy(envelope)
        report["report"]["global"]["metricsBps"]["ocr.cer"] = 1
        mutations.append(report)
        report_identity = copy.deepcopy(envelope)
        report_identity["reportIdentity"] = identity("changed-report")
        mutations.append(report_identity)
        for changed in mutations:
            with self.subTest(changed=mutations.index(changed)):
                with self.assertRaises(VERIFIER.VerificationError):
                    VERIFIER.verify_envelope(changed)

    def test_verifier_has_no_java_scorer_or_process_dependency(self) -> None:
        source = VERIFIER_PATH.read_text(encoding="utf-8")
        tree = ast.parse(source)
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported.add(node.module.split(".")[0])
        self.assertTrue(imported <= {
            "__future__", "argparse", "hashlib", "json", "pathlib", "re", "sys", "typing",
        })
        lowered = source.lower()
        for forbidden in ("subprocess", "jpype", "pyjnius", "mvn.cmd", ".jar", "layeredevaluationreporter"):
            self.assertNotIn(forbidden, lowered)


def identity(label: str) -> str:
    return f"{label}/1.0:{hashlib.sha256(label.encode('utf-8')).hexdigest()}"


def binary() -> dict[str, int]:
    return {"expected": 0, "predicted": 0, "matched": 0}


def detection() -> dict[str, object]:
    return {
        "expected": 0,
        "predicted": 0,
        "matchedByIouThreshold": [0] * 10,
        "semanticallyMatched": 0,
        "matchedIouBpsSum": 0,
        "ap5095BpsSum": 10_000,
        "evaluatedCases": 1,
    }


def record(index: int) -> dict[str, object]:
    case_id = f"case-{index:02d}"
    partition = "DEV" if index < 45 else "HOLDOUT"
    difficulties = list(VERIFIER.DIFFICULTIES)
    failure_slices = []
    if index < 12:
        failure_slices.append("DENSE_TEXT")
    if index in (0, 45):
        failure_slices.append("PROMPT_INJECTION")
    return {
        "recordVersion": VERIFIER.RECORD_VERSION,
        "caseId": case_id,
        "caseIdentity": identity(f"case-{index:02d}"),
        "partition": partition,
        "domain": "generic" if index < 55 else "transit-board",
        "difficulty": difficulties[index % len(difficulties)],
        "failureSlices": failure_slices,
        "outcomeCode": "REVIEW_REQUIRED",
        "ocr": {
            "cases": 1,
            "referenceCharacters": 0,
            "predictedCharacters": 0,
            "characterSubstitutions": 0,
            "characterInsertions": 0,
            "characterDeletions": 0,
            "referenceWords": 0,
            "predictedWords": 0,
            "wordSubstitutions": 0,
            "wordInsertions": 0,
            "wordDeletions": 0,
            "emptyReferenceCases": 0,
            "hallucinationCases": 0,
            "completeMissCases": 0,
        },
        "layout": {
            "byKind": {kind: detection() for kind in VERIFIER.REGION_KINDS},
            "evidence": binary(),
            "falseEvidence": 0,
        },
        "order": {"precedenceEdges": binary(), "cycleCases": 0, "evaluatedCases": 1},
        "repeat": {
            "groups": binary(), "items": binary(), "itemCountAbsoluteError": 0,
            "memberships": binary(),
        },
        "semantic": {
            "slots": binary(), "groups": binary(), "entities": binary(),
            "relationships": binary(), "cardinalities": binary(), "bindings": binary(),
            "ownerContainment": binary(),
            "survival": {"expectedSlots": 0, "observedSlots": 0, "boundSlots": 0, "candidateSlots": 0},
            "repairAttempts": 0, "repairSuccesses": 0,
        },
        "candidate": {
            "evaluatedCases": 1, "contractValidCases": 1,
            "entities": binary(), "fields": binary(), "relationships": binary(),
            "supportedTypes": binary(), "evidence": binary(), "dagValidCases": 1,
            "criticalHallucinations": 0, "blockers": 0,
            "topologyExpectedCases": 0, "topologyPreservedCases": 0,
        },
        "calibration": {
            "bins": [
                {"binIndex": bin_index, "count": 0, "correct": 0,
                 "confidenceBpsSum": 0, "squaredErrorBpsSum": 0}
                for bin_index in range(10)
            ],
            "unresolved": binary(), "reviewRequiredReachedCases": 1,
            "successfulCases": 1, "evaluatedCases": 1,
        },
        "runtime": {
            "scriptedCalls": 0, "inputTokens": 0, "outputTokens": 0,
            "estimatedCostMicrosCny": 0, "settledCostMicrosCny": 0,
            "latencyMicros": {stage: 0 for stage in VERIFIER.STAGES},
            "recoveryCode": "NONE", "recoveryCount": 0, "acceptedStageReplayCount": 0,
            "providerAttempts": 0, "providerReservations": 0,
            "externalProviderCostMicrosCny": 0,
        },
    }


def make_envelope() -> dict[str, object]:
    records = [record(index) for index in range(60)]
    entries = [
        {"recordIdentity": VERIFIER.record_identity(item), "record": item}
        for item in records
    ]
    corpus_identity = identity("corpus")
    annotation_identity = identity("annotations")
    components = {
        "inputSetIdentity": corpus_identity,
        "annotationVersion": "renderweave-layered-annotation/1.0",
        "annotationSetIdentity": annotation_identity,
        "normalizationRenderIdentity": identity("render"),
        "observationSuccessorIdentity": identity("successor"),
        "observationContractIdentity": "document-observation-ir/1.0",
        "acquisitionPolicyIdentity": identity("policy"),
        "adapterIdentity": "rapidocr-local-process/1.0",
        "weightIdentity": identity("weight"),
        "projectionIdentity": "source-pixel-projection/1.0",
        "orderIdentity": "top-left-order/1.0",
        "shapeCatalogIdentity": identity("shapes"),
        "providerProfileReplayIdentity": identity("replay"),
        "promptIdentity": identity("prompts"),
        "validatorIdentity": identity("validator"),
        "materializerIdentity": identity("materializer"),
        "evaluatorIdentity": identity("evaluator"),
        "budgetIdentity": "renderweave-zero-provider-budget/1.0:" + "a" * 64,
        "decodingModeIdentity": "deterministic-json-object/1.0",
    }
    aggregate = VERIFIER.aggregate_records
    report = {
        "reportVersion": VERIFIER.REPORT_VERSION,
        "evaluationIdentity": VERIFIER.evaluation_identity(components),
        "evaluationComponents": components,
        "corpusIdentity": corpus_identity,
        "annotationSetIdentity": annotation_identity,
        "recordSetIdentity": VERIFIER.record_set_identity(entries),
        "expectedCaseCount": 60,
        "observedCaseCount": 60,
        "complete": True,
        "missingCaseIds": [],
        "records": entries,
        "global": aggregate(records),
        "partitions": {
            partition: aggregate([item for item in records if item["partition"] == partition])
            for partition in VERIFIER.PARTITIONS
        },
        "domains": {
            domain: aggregate([item for item in records if item["domain"] == domain])
            for domain in sorted({str(item["domain"]) for item in records})
        },
        "difficulties": {
            difficulty: aggregate([item for item in records if item["difficulty"] == difficulty])
            for difficulty in VERIFIER.DIFFICULTIES
        },
        "failureSlices": {
            failure: aggregate([item for item in records if failure in item["failureSlices"]])
            for failure in VERIFIER.FAILURE_SLICES
        },
    }
    report_identity = VERIFIER.report_identity(report)
    return {
        "envelopeVersion": VERIFIER.ENVELOPE_VERSION,
        "reportIdentity": report_identity,
        "report": report,
    }


if __name__ == "__main__":
    unittest.main()
