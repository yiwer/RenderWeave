#!/usr/bin/env python3
"""Independent, payload-free verifier for the DocumentObservationIR R0 gate."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from typing import Any, Iterable


REPORT_VERSION = "renderweave-document-observation-r0-gate/1.0"
ANCHOR_REVISION = "c12f23d76a6fc76a6a38042ff89bbd166e6012b5"
SHAPE_VERSION = "renderweave-stage-response-shape-catalog/1.0"
SUCCESSOR_VERSION = "renderweave-document-observation-successor/1.0"
EXPECTED_SHAPE_IDENTITY = "ad46adfbf6dc9e200f4736e693646ee485de5530af35b2f12802f561faa16557"
EXPECTED_SUCCESSOR_IDENTITY = (
    "renderweave-document-observation-successor/1.0:"
    "302917d557bf7df9326b9a7d4af840c190be471041712806c19f932e24e1a3a2"
)
SEMANTIC_SUMMARY = (
    "root=document;schema=document(root)[title:TEXT:e1,items:ARRAY<REFERENCE:item>:e1];"
    "schema=item[ label:TEXT:e2@2300,6300 ]"
)
SEMANTIC_FINGERPRINT = "ee99eb03b4fd94a0970fe2db37041be30913a8d3c63f10d7bb64c3e27ee249a0"
SHA256 = re.compile(r"^[0-9a-f]{64}$")

PROTECTED_PATHS = (
    "renderweave-inference/src/main/resources/inference-profiles/"
    "dashscope-qwen37-flash-product-v45-hybrid-generic.json",
    "renderweave-inference/src/main/resources/inference-profiles/"
    "dashscope-qwen37-plus-product-v45-hybrid-generic.json",
    "renderweave-inference/src/main/resources/inference-profiles/"
    "dashscope-qwen38-max-product-v45-hybrid-generic.json",
    "renderweave-inference/src/main/resources/inference-prompts/document-vision-observations-v1.txt",
    "renderweave-inference/src/main/resources/inference-prompts/schema-candidate-v5.txt",
    "renderweave-inference/src/main/resources/inference-prompts/visual-bindings-v4.txt",
    "renderweave-inference/src/main/resources/inference-prompts/visual-elements-v12.txt",
    "renderweave-inference/src/main/resources/inference-prompts/visual-hierarchy-v7.txt",
    "renderweave-inference/src/main/resources/inference-prompts/visual-hint-generic-v1.txt",
    "renderweave-inference/src/main/resources/replay-corpus/v1/manifest.json",
    "renderweave-inference/src/main/resources/visual-eval/v1/FONT-NOTICE.md",
    "renderweave-inference/src/main/resources/visual-eval/v1/OFL.txt",
    "renderweave-inference/src/main/resources/visual-eval/v1/RenderWeaveVisualEval.ttf",
    "renderweave-inference/src/main/resources/visual-eval/v1/scenes.json",
)

EXPECTED_COVERAGE = {
    "accepted-stage-no-replay",
    "cancellation-before-blob-read",
    "cmyk-explicit-bgr",
    "empty-output",
    "lease-expiry-ir-recompute",
    "limit-enforcement",
    "multi-image-order",
    "out-of-bounds",
    "payload-redaction",
    "png-jpeg",
    "repeated-instance-coalescing",
    "root-child-many-reference",
    "strong-document-sequence",
}
EXPECTED_CHECKS = {
    "java-contract-property-differential",
    "postgres-cancellation",
    "postgres-lease-recovery",
    "postgres-scripted-replay",
    "protected-v45-byte-diff",
    "python-payload-scan",
    "rapidocr-adapter-python",
    "v45-projection-python",
}
REQUIRED_TEST_CASES = {
    "VisualEvidenceAcquisitionContractTest": {
        "normalizedArtifactsAndObservationsAreCanonicalBoundedAndPayloadRedacted",
        "invalidIdentityGeometryTextAndPolicyBoundsFailClosed",
        "observationContractCannotCarrySemanticOrCandidateTypes",
        "liveWorkerDependsOnTheSuccessorSeamAndNotTheLegacyPreprocessor",
    },
    "DocumentObservationCompatibilityProjectionTest": {
        "lockedOddDimensionAndBoundaryGoldensUseV45FloorCeilProjection",
        "projectionIdentityMismatchFailsWithOnlyAStableCode",
    },
    "DocumentObservationSuccessorIdentityTest": {
        "identityBindsTheExactR0ContractPolicyProjectionAndShapeCatalog",
    },
    "StageResponseShapeCatalogTest": {
        "catalogDocumentsAndCombinedIdentityAreCanonicalAndByteStable",
        "machineReadableFixturesHaveTheSameShapeAcceptanceAsStrictV45Codecs",
        "productV45ProfilesStillRequestJsonObjectWithoutTools",
    },
    "LocalProcessVisualEvidenceAcquisitionTest": {
        "exactPolicyProducesCanonicalSourcePixelIrWithNativeConfidence",
        "policyMismatchAndInvalidOutputFailWithPayloadFreeStableCodes",
        "successorProjectionIsObjectAndByteEquivalentToTheV45Oracle",
    },
    "PostgresLiveInferenceWorkflowTest": {
        "productV44RetriesObservationWhenLocalVisionProvesAnOmittedDenseSequence",
        "productV45CoalescesRepeatedInstanceObservationsIntoOneNestedSchemaField",
        "productV45SuccessorRecomputesIrAfterLeaseExpiryWithoutReplayingObserve",
        "cancellationIsAcknowledgedBeforeHybridPreprocessing",
    },
}
FORBIDDEN_PAYLOAD_MARKERS = (
    "OCR_SENTINEL",
    "data:image;base64",
    '"base64"',
    '"prompt"',
    '"modelOutput"',
    '"providerRequest"',
    "Bearer ",
    "DASHSCOPE_TOKEN_API_KEY",
    "DASHSCOPE_API_KEY",
    "ocr-00-000",
)


class VerificationError(Exception):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


def _reject_duplicate(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("duplicate JSON member")
        result[key] = value
    return result


def _reject_non_integer(_value: str) -> Any:
    fail("non-integer JSON number is forbidden")


def parse_strict_json(raw: str) -> Any:
    try:
        return json.loads(
            raw,
            object_pairs_hook=_reject_duplicate,
            parse_float=_reject_non_integer,
            parse_constant=_reject_non_integer,
        )
    except VerificationError:
        raise
    except (json.JSONDecodeError, ValueError) as exc:
        fail(f"invalid JSON: {type(exc).__name__}")


def scan_payload_free(raw: str, source: str) -> None:
    for marker in FORBIDDEN_PAYLOAD_MARKERS:
        if marker in raw:
            fail(f"forbidden payload marker in {source}")


def _require_object(value: Any, name: str) -> dict[str, Any]:
    if type(value) is not dict:
        fail(f"{name} must be an object")
    return value


def _require_list(value: Any, name: str) -> list[Any]:
    if type(value) is not list:
        fail(f"{name} must be an array")
    return value


def _require_keys(value: dict[str, Any], keys: Iterable[str], name: str) -> None:
    expected = set(keys)
    if set(value) != expected:
        fail(f"{name} fields differ")


def _require_exact(value: Any, expected: Any, name: str) -> None:
    if value != expected:
        fail(f"{name} mismatch")


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _framed_hash(values: Iterable[str], prefix: bytes = b"") -> str:
    digest = hashlib.sha256()
    digest.update(prefix)
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def verify_projection_golden(repository: pathlib.Path) -> int:
    path = repository / "renderweave-inference/src/test/resources/document-observation/v45-projection-golden.json"
    document = _require_object(parse_strict_json(path.read_text(encoding="utf-8")), "projection fixture")
    _require_keys(document, ("fixtureVersion", "cases"), "projection fixture")
    _require_exact(document["fixtureVersion"], "renderweave-v45-projection-golden/1.0", "fixture version")
    cases = _require_list(document["cases"], "projection cases")
    if not cases:
        fail("projection cases are empty")
    for raw_case in cases:
        case = _require_object(raw_case, "projection case")
        _require_keys(case, (
            "mediaType", "width", "height", "sourceBox", "expectedBox",
            "confidenceBps", "expectedBucket",
        ), "projection case")
        width, height = case["width"], case["height"]
        if type(width) is not int or type(height) is not int or width < 1 or height < 1:
            fail("projection dimensions are invalid")
        left, top, right, bottom = case["sourceBox"]
        actual = [
            left * 10_000 // width,
            top * 10_000 // height,
            (right * 10_000 + width - 1) // width,
            (bottom * 10_000 + height - 1) // height,
        ]
        _require_exact(actual, case["expectedBox"], "projection golden")
        confidence = case["confidenceBps"]
        bucket = "LOW" if confidence < 6_000 else "MEDIUM" if confidence < 8_500 else "HIGH"
        _require_exact(bucket, case["expectedBucket"], "confidence bucket")
    return len(cases)


def recompute_shape_catalog_identity(repository: pathlib.Path) -> str:
    stages = (
        ("OBSERVE", "observe.schema.json"),
        ("HIERARCHY", "hierarchy.schema.json"),
        ("ELEMENT_BINDING", "element-binding.schema.json"),
    )
    values: list[tuple[str, str]] = []
    root = repository / "renderweave-inference/src/main/resources/response-shapes/1.0"
    for stage, filename in stages:
        document = parse_strict_json((root / filename).read_text(encoding="utf-8"))
        canonical = json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        values.append((stage, _sha256(canonical.encode("utf-8"))))
    material = SHAPE_VERSION + "".join(f"\n{stage}={digest}" for stage, digest in values)
    identity = _sha256(material.encode("utf-8"))
    _require_exact(identity, EXPECTED_SHAPE_IDENTITY, "shape catalog identity")
    return identity


def recompute_successor_identity(repository: pathlib.Path) -> str:
    capability = "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1"
    policy_fields = (
        "AcquisitionPolicy/1.0",
        "DocumentObservationIR/1.0",
        capability,
        "rapidocr-local-process/1.0",
        "rapidocr-openvino-ppocrv6-small",
        "rapidocr-3.9.2+openvino-2026.0.0",
        "a" * 64,
        "explicit-bgr/1.0",
        "rapidocr-lines/1.0",
        "source-pixel-top-left/1.0",
        "half-open-box/1.0",
        "v45-source-to-candidate/1.0",
        "top-left-canonical/1.0",
        "unicode-nfc-whitespace-collapse/1.0",
        "basis-points/1.0",
        "v45-confidence-buckets/1.0",
        "EPHEMERAL_STAGE_CONTEXT_ONLY",
        "10", "512", "256", "32768", "524288", "30000",
    )
    policy_identity = _framed_hash(
        policy_fields, b"renderweave-acquisition-policy\x00"
    )
    shape_identity = recompute_shape_catalog_identity(repository)
    successor_fields = (
        SUCCESSOR_VERSION,
        "DocumentObservationIR/1.0",
        policy_identity,
        capability,
        *policy_fields[3:16],
        SHAPE_VERSION,
        shape_identity,
    )
    identity = SUCCESSOR_VERSION + ":" + _framed_hash(successor_fields)
    _require_exact(identity, EXPECTED_SUCCESSOR_IDENTITY, "successor identity")
    return identity


def require_external_provider_zero(value: Any) -> None:
    telemetry = _require_object(value, "externalProvider")
    _require_keys(telemetry, ("attempts", "reservations", "costMicrosCny"), "externalProvider")
    if telemetry != {"attempts": 0, "reservations": 0, "costMicrosCny": 0}:
        fail("external Provider telemetry must be exactly zero")


def _git(repository: pathlib.Path, *arguments: str) -> bytes:
    try:
        completed = subprocess.run(
            ("git", *arguments), cwd=repository, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        fail(f"git verification failed: {type(exc).__name__}")
    if completed.returncode != 0:
        fail("git verification returned nonzero")
    return completed.stdout


def protected_file_rows(repository: pathlib.Path) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for relative in PROTECTED_PATHS:
        path = repository / relative
        if not path.is_file():
            fail(f"protected file is missing: {relative}")
        current = path.read_bytes()
        difference = _git(
            repository, "diff", "--no-ext-diff", "--binary", ANCHOR_REVISION, "--", relative,
        )
        if difference:
            fail(f"protected v45 bytes changed: {relative}")
        rows.append({"path": relative, "sha256": _sha256(current)})
    return rows


def _verify_test_reports(report: dict[str, Any], report_path: pathlib.Path) -> int:
    paths = _require_list(report["testReports"], "testReports")
    if len(paths) != 2 or any(type(item) is not str for item in paths):
        fail("testReports must name exactly two XML files")
    found: dict[str, set[str]] = {}
    scanned = 0
    evidence_root = report_path.parent.resolve()
    for relative in paths:
        path = (evidence_root / relative).resolve()
        if path.parent != evidence_root or path.suffix.lower() != ".xml" or not path.is_file():
            fail("test report path escapes the evidence directory")
        raw = path.read_text(encoding="utf-8")
        scan_payload_free(raw, path.name)
        try:
            tree = ET.fromstring(raw)
        except ET.ParseError:
            fail("JUnit XML is invalid")
        suites = [tree] if tree.tag == "testsuite" else list(tree.findall("testsuite"))
        if not suites:
            fail("JUnit XML has no test suite")
        for suite in suites:
            if int(suite.attrib.get("failures", "0")) != 0 or int(suite.attrib.get("errors", "0")) != 0:
                fail("JUnit suite is not green")
            for case in suite.findall("testcase"):
                name = case.attrib.get("name", "")
                class_name = case.attrib.get("classname", "").rsplit(".", 1)[-1]
                if case.find("failure") is not None or case.find("error") is not None \
                        or case.find("skipped") is not None:
                    fail("required JUnit case is not green")
                found.setdefault(class_name, set()).add(name)
                scanned += 1
    for suite, required in REQUIRED_TEST_CASES.items():
        if not required.issubset(found.get(suite, set())):
            fail(f"required R0 test evidence is missing: {suite}")
    return scanned


def _verify_report(document: dict[str, Any], raw: str, path: pathlib.Path,
                   repository: pathlib.Path) -> tuple[int, int]:
    scan_payload_free(raw, path.name)
    _require_keys(document, (
        "reportVersion", "result", "assurance", "anchorRevision", "revision",
        "seam", "highestAcceptanceSeam", "architecture", "scope", "identities",
        "behaviorOracle", "coverage", "checks", "externalProvider", "testReports",
        "protectedFiles", "payloadScan",
    ), "report")
    _require_exact(document["reportVersion"], REPORT_VERSION, "report version")
    _require_exact(document["result"], "passed", "result")
    _require_exact(document["assurance"], "A1+A2-strict-input", "assurance")
    _require_exact(document["anchorRevision"], ANCHOR_REVISION, "anchor revision")
    revision = document["revision"]
    if type(revision) is not str or re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        fail("revision is invalid")
    _require_exact(revision, _git(repository, "rev-parse", "HEAD").decode("ascii").strip(), "revision")
    _require_exact(document["seam"], {
        "input": "normalized ArtifactSet + AcquisitionPolicy",
        "output": "DocumentObservationIR/1.0",
    }, "primary seam")
    _require_exact(
        document["highestAcceptanceSeam"],
        "complete IMAGE_ONLY scripted replay -> REVIEW_REQUIRED",
        "highest acceptance seam",
    )
    _require_exact(document["architecture"], {
        "orchestration": "existing-postgresql-durable-typed-state-machine",
        "semanticStages": "serial",
        "localRepair": "validator-driven-bounded",
        "openEndedAgent": False,
        "generalToolExecutor": False,
        "langGraph": False,
        "temporal": False,
    }, "architecture")
    _require_exact(document["scope"], {
        "r0Complete": True,
        "r1Enabled": False,
        "template": False,
        "rootDocumentConnect": False,
        "dataAdaptation": False,
        "publishing": False,
    }, "scope")
    shape_identity = recompute_shape_catalog_identity(repository)
    successor_identity = recompute_successor_identity(repository)
    _require_exact(document["identities"], {
        "observationContract": "DocumentObservationIR/1.0",
        "acquisitionPolicyContract": "AcquisitionPolicy/1.0",
        "compatibilityProjection": "v45-source-to-candidate/1.0",
        "stageShapeCatalog": shape_identity,
        "successor": successor_identity,
        "candidateSemanticFingerprint": SEMANTIC_FINGERPRINT,
    }, "identities")
    _require_exact(document["behaviorOracle"], {
        "terminalState": "REVIEW_REQUIRED",
        "acceptedStages": ["OBSERVE", "HIERARCHY", "ELEMENT_BINDING"],
        "acceptedStageCanonicalPayload":
            "byte-equivalent-by-locked-projection-and-unchanged-v45-codecs",
        "acceptedStageReplayCountAfterRecovery": 0,
        "candidateSemanticSummary": SEMANTIC_SUMMARY,
        "fieldOrder": ["title", "items", "label"],
        "relationshipOrder": ["document-items:MANY"],
        "evidenceTopOrder": [2300, 6300],
        "fixedIssueRouting": [
            "OBSERVE:VISUAL_SEMANTIC_OBSERVE_DOCUMENT_SEQUENCE_GROUP_MISSING",
            "ACQUISITION:DOCUMENT_VISION_PROJECTION_INVALID",
        ],
        "blockers": [{"code": "LOW_CONFIDENCE_UNRESOLVED", "count": 5}],
        "warningCount": 0,
        "scriptedReservationSummary": {
            "attempts": 3, "settled": 3, "inputTokens": 3000,
            "outputTokens": 1500, "costMicrosCny": 18000,
        },
    }, "behavior oracle")
    coverage = _require_list(document["coverage"], "coverage")
    checks = _require_list(document["checks"], "checks")
    if len(coverage) != len(set(coverage)) or set(coverage) != EXPECTED_COVERAGE:
        fail("coverage matrix mismatch")
    if len(checks) != len(set(checks)) or set(checks) != EXPECTED_CHECKS:
        fail("check matrix mismatch")
    require_external_provider_zero(document["externalProvider"])
    expected_protected = protected_file_rows(repository)
    _require_exact(document["protectedFiles"], expected_protected, "protected file manifest")
    _require_exact(document["payloadScan"], {
        "result": "passed",
        "forbiddenMatches": 0,
        "categories": [
            "checkpoint", "database-row", "evidence", "exception", "junit-report",
            "log", "object-string", "report", "stderr", "stdout",
        ],
    }, "payload scan")
    projection_cases = verify_projection_golden(repository)
    test_cases = _verify_test_reports(document, path)
    return projection_cases, test_cases


def verify(report_path: pathlib.Path, repository: pathlib.Path) -> tuple[int, int]:
    try:
        raw = report_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        fail(f"cannot read report: {type(exc).__name__}")
    document = _require_object(parse_strict_json(raw), "report")
    return _verify_report(document, raw, report_path, repository.resolve())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=pathlib.Path)
    parser.add_argument("--repository", type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1])
    arguments = parser.parse_args()
    try:
        projections, tests = verify(arguments.report.resolve(), arguments.repository.resolve())
    except VerificationError as exc:
        print(f"documentObservationR0Verification=FAIL code={exc}", file=sys.stderr)
        return 1
    print(
        "documentObservationR0Verification=PASS "
        f"projectionCases={projections} junitCases={tests} externalProviderAttempts=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
