#!/usr/bin/env python3
"""Independent R1 gate and payload-scan tests."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import pathlib
import subprocess
import unittest


TOOLS = pathlib.Path(__file__).resolve().parent
REPOSITORY = TOOLS.parent


def load(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path.name}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VERIFIER = load("layered_verifier_for_gate_test", TOOLS / "verify_layered_evaluation.py")
FIXTURE = load("layered_verifier_fixture", TOOLS / "test_verify_layered_evaluation.py")
GATE = load("layered_gate_verifier", TOOLS / "verify_layered_evaluation_gate.py")


class LayeredEvaluationGateVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.protected = GATE.protected_file_rows(REPOSITORY)
        cls.revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=REPOSITORY, check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        ).stdout.strip()

    def test_full_cross_language_gate_and_payload_scan_pass(self) -> None:
        envelope = FIXTURE.make_envelope()
        independent = VERIFIER.verify_envelope(envelope, REPOSITORY)
        summary = gate_summary(independent, self.protected, self.revision)
        proof = r0_proof(self.revision)
        payloads = evidence_payloads(envelope, independent, summary, self.revision)

        result = GATE.verify_documents(
            envelope, independent, summary, REPOSITORY, payloads, proof,
        )

        self.assertEqual("PASS", result["result"])
        self.assertEqual("A2_STRICT_INPUT_REPLAY", result["assurance"])
        self.assertEqual(independent["reportIdentity"], result["reportIdentity"])
        self.assertEqual(independent["recomputedMetricsIdentity"], result["recomputedMetricsIdentity"])
        self.assertEqual(0, result["providerAttempts"])
        self.assertEqual("EXPERIMENTAL", result["productV45Lifecycle"])
        self.assertEqual("J0", result["visualDiffJudgement"])
        self.assertEqual(0, result["payloadForbiddenMatches"])

    def test_active_authorization_slots_are_not_frozen_product_snapshot_bytes(self) -> None:
        paths = {row["path"] for row in self.protected}

        self.assertFalse(any(path.startswith(".sdlc/live/") for path in paths))
        self.assertIn(
            "renderweave-inference/src/main/resources/inference-prompts/"
            "visual-elements-v12.txt",
            paths,
        )

    def test_token_plan_secret_name_is_forbidden_in_final_r1_evidence(self) -> None:
        with self.assertRaisesRegex(
                GATE.GateVerificationError, "FORBIDDEN_EVIDENCE_MEMBER"):
            GATE._scan_evidence_payload(
                "evidence.txt", b"DASHSCOPE_TOKEN_API_KEY",
            )

    def test_lifecycle_future_gate_provider_history_and_payload_tampering_fail(self) -> None:
        envelope = FIXTURE.make_envelope()
        independent = VERIFIER.verify_envelope(envelope, REPOSITORY)
        baseline = gate_summary(independent, self.protected, self.revision)
        proof = r0_proof(self.revision)

        mutations: list[tuple[str, dict, dict[str, bytes]]] = []
        lifecycle = copy.deepcopy(baseline)
        lifecycle["lifecycle"]["productV45"] = "CERTIFIED"
        mutations.append(("GATE_LIFECYCLE_INVALID", lifecycle,
                          evidence_payloads(envelope, independent, lifecycle, self.revision)))

        future = copy.deepcopy(baseline)
        future["futureEvidenceGates"]["R2"]["triggered"] = True
        mutations.append(("FUTURE_GATE_AUTO_TRIGGERED", future,
                          evidence_payloads(envelope, independent, future, self.revision)))

        provider = copy.deepcopy(baseline)
        provider["externalProvider"]["attempts"] = 1
        mutations.append(("GATE_PROVIDER_USAGE_NONZERO", provider,
                          evidence_payloads(envelope, independent, provider, self.revision)))

        history = copy.deepcopy(baseline)
        history["historicalBytes"]["protectedFiles"][0]["sha256"] = "0" * 64
        mutations.append(("PROTECTED_FILE_MANIFEST_DRIFT", history,
                          evidence_payloads(envelope, independent, history, self.revision)))

        visual = copy.deepcopy(baseline)
        visual["visualDiff"]["evidenceIncluded"] = True
        mutations.append(("VISUAL_DIFF_EVIDENCE_INVALID", visual,
                          evidence_payloads(envelope, independent, visual, self.revision)))

        r0 = copy.deepcopy(baseline)
        r0["r0Prerequisite"]["result"] = "UNVERIFIED"
        mutations.append(("R0_PREREQUISITE_INVALID", r0,
                          evidence_payloads(envelope, independent, r0, self.revision)))

        forbidden_payloads = evidence_payloads(envelope, independent, baseline, self.revision)
        forbidden_payloads["unexpected.json"] = b'{"providerRequest":"secret"}'
        mutations.append(("FORBIDDEN_EVIDENCE_MEMBER", baseline, forbidden_payloads))

        image_payloads = evidence_payloads(envelope, independent, baseline, self.revision)
        image_payloads["unexpected.png"] = b"\x89PNG\r\n\x1a\n"
        mutations.append(("IMAGE_EVIDENCE_FORBIDDEN", baseline, image_payloads))

        for expected, changed, payloads in mutations:
            with self.subTest(expected=expected):
                with self.assertRaisesRegex(GATE.GateVerificationError, expected):
                    GATE.verify_documents(
                        envelope, independent, changed, REPOSITORY, payloads, proof,
                    )


def gate_summary(
        independent: dict, protected: list[dict[str, str]], revision: str,
) -> dict:
    return {
        "reportVersion": GATE.GATE_VERSION,
        "result": "passed",
        "assurance": "A1+A2-strict-input",
        "anchorRevision": GATE.ANCHOR_REVISION,
        "revision": revision,
        "seams": {
            "primary": "normalized ArtifactSet + AcquisitionPolicy -> DocumentObservationIR/1.0",
            "highestAcceptance": "complete IMAGE_ONLY scripted replay -> REVIEW_REQUIRED",
        },
        "architecture": {
            "orchestration": "existing-postgresql-durable-typed-state-machine",
            "semanticStages": "serial",
            "localRepair": "validator-driven-bounded",
            "openEndedAgent": False,
            "generalToolExecutor": False,
            "langGraph": False,
            "temporal": False,
        },
        "scope": {
            "r0Complete": True,
            "r1Complete": True,
            "template": False,
            "rootDocumentConnect": False,
            "dataAdaptation": False,
            "publishing": False,
        },
        "identities": {
            key: independent[key] for key in (
                "reportIdentity", "evaluationIdentity", "corpusIdentity",
                "annotationSetIdentity", "recordSetIdentity", "caseAssignmentIdentity",
                "recomputedMetricsIdentity", "corpusLockIdentity",
            )
        },
        "crossLanguage": {
            "java": "PASS",
            "python": "PASS",
            "exactIdentity": True,
            "exactCaseAccounting": True,
            "exactAllMetrics": True,
            "caseCount": independent["caseCount"],
            "metricCount": independent["metricCount"],
            "sliceAggregateCount": independent["sliceAggregateCount"],
        },
        "caseAccounting": {
            "expected": 60,
            "observed": independent["caseCount"],
            "partitions": independent["partitions"],
            "domains": independent["domains"],
            "difficulties": independent["difficulties"],
            "failureSlices": independent["failureSlices"],
        },
        "externalProvider": {
            "attempts": 0,
            "reservations": 0,
            "costMicrosCny": 0,
        },
        "historicalBytes": {
            "unchanged": True,
            "protectedFiles": protected,
        },
        "r0Prerequisite": r0_proof(revision),
        "lifecycle": {
            "productV45": "EXPERIMENTAL",
            "n7": "in_progress",
            "ac021": "not_satisfied",
            "acVr010": "not_satisfied",
            "finalBusinessVisualJudgement": "J0",
        },
        "futureEvidenceGates": {
            "R2": {"triggered": False, "code": "R2_BASELINE_GAP_AND_LICENSE_EVIDENCE_REQUIRED"},
            "R3": {"triggered": False, "code": "R3_REPRODUCIBLE_ORDER_FAILURE_EVIDENCE_REQUIRED"},
            "R4": {"triggered": False, "code": "R4_STRICT_PROTOCOL_AND_SHAPE_BOTTLENECK_EVIDENCE_REQUIRED"},
            "R5": {"triggered": False, "code": "R5_STATIC_VIEW_BOTTLENECK_EVIDENCE_REQUIRED"},
            "R6": {"triggered": False, "code": "R6_ORCHESTRATION_PRESSURE_EVIDENCE_REQUIRED"},
        },
        "visualDiff": {
            "scope": "local-allowlisted-only",
            "automatedEvidence": "A1",
            "humanReview": "human_review_pending",
            "judgement": "J0",
            "evidenceIncluded": False,
        },
        "payloadScan": {
            "result": "passed",
            "scanner": GATE.SCANNER_VERSION,
            "forbiddenMatches": 0,
            "files": [
                "document-observation-r0-summary.json", "layered-report.json",
                "python-verifier-summary.json", "layered-r1-summary.json",
            ],
        },
    }


def r0_payload(revision: str) -> bytes:
    return VERIFIER.canonical_json({
        "result": "passed", "revision": revision, "terminalState": "REVIEW_REQUIRED",
        "externalProvider": {"attempts": 0, "reservations": 0, "costMicrosCny": 0},
    })


def r0_proof(revision: str) -> dict[str, object]:
    payload = r0_payload(revision)
    return {
        "proofVersion": GATE.R0_PROOF_VERSION,
        "result": "PASS",
        "assurance": "A2_STRICT_INPUT_REPLAY",
        "reportFile": "document-observation-r0-summary.json",
        "reportSha256": hashlib.sha256(payload).hexdigest(),
        "revision": revision,
        "terminalState": "REVIEW_REQUIRED",
        "providerAttempts": 0,
        "providerReservations": 0,
        "externalProviderCostMicrosCny": 0,
    }


def evidence_payloads(
        envelope: dict, independent: dict, summary: dict, revision: str,
) -> dict[str, bytes]:
    return {
        "document-observation-r0-summary.json": r0_payload(revision),
        "layered-report.json": VERIFIER.canonical_json(envelope),
        "python-verifier-summary.json": VERIFIER.canonical_json(independent),
        "layered-r1-summary.json": VERIFIER.canonical_json(summary),
    }


if __name__ == "__main__":
    unittest.main()
