#!/usr/bin/env python3
"""Post-seal, payload-safe exact comparison of the R5P2 A1 and independent A2."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import pathlib
import re
import sys
from typing import Any, Callable

import replay_r5p2_paired_a2 as replay


VERSION = "renderweave-r5p2-a1-a2-comparison/1.0"
ENVELOPE_VERSION = "renderweave-r5p2-a1-a2-comparison-envelope/1.0"
ASSURANCE = "A2_POST_SEAL_EXACT_A1_A2_COMPARISON"
EXACT = "R5P2_A1_A2_EXACT"
INVALID = "R5P2_MEASUREMENT_INVALID"
MAX_INPUT_BYTES = 4 * 1024 * 1024
MAX_OUTPUT_BYTES = 256 * 1024
IDENTITY = re.compile(r"^[A-Za-z0-9._/-]+:[0-9a-f]{64}$")

COMPARISON_ORDER = (
    "R5P2_A1_A2_AUTHORITY_IDENTITIES_MISMATCH",
    "R5P2_A1_A2_STAGE_IDENTITIES_MISMATCH",
    "R5P2_A1_A2_ACCOUNTING_MISMATCH",
    "R5P2_A1_A2_DETERMINISM_MISMATCH",
    "R5P2_A1_A2_CASE_ASSIGNMENT_MISMATCH",
    "R5P2_A1_A2_NORMALIZATION_MISMATCH",
    "R5P2_A1_A2_PLAN_MISMATCH",
    "R5P2_A1_A2_VIEW_PROCESS_MISMATCH",
    "R5P2_A1_A2_OBSERVATION_IDENTITY_MISMATCH",
    "R5P2_A1_A2_RECONCILIATION_IDENTITY_MISMATCH",
    "R5P2_A1_A2_RESOURCE_COUNTS_MISMATCH",
    "R5P2_A1_A2_CASE_METRICS_MISMATCH",
    "R5P2_A1_A2_PAIR_METRICS_MISMATCH",
    "R5P2_A1_A2_COHORT_SUMMARIES_MISMATCH",
    "R5P2_A1_A2_TRANSIT_GATE_MISMATCH",
    "R5P2_A1_A2_CANDIDATE_TERMINAL_MISMATCH",
    "R5P2_A1_A2_TERMINAL_INPUT_IDENTITY_MISMATCH",
)


class ComparisonError(ValueError):
    pass


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ComparisonError("R5P2_A1_A2_INPUT_DUPLICATE_KEY")
        result[key] = value
    return result


def _strict_int(value: str) -> int:
    parsed = int(value)
    if parsed < -(2 ** 63) or parsed > 2 ** 63 - 1:
        raise ComparisonError("R5P2_A1_A2_INPUT_INTEGER_INVALID")
    return parsed


def _reject_float(_value: str) -> float:
    raise ComparisonError("R5P2_A1_A2_INPUT_FLOAT_INVALID")


def _read_json(path: pathlib.Path) -> dict[str, Any]:
    raw = path.read_bytes()
    if not raw or len(raw) > MAX_INPUT_BYTES:
        raise ComparisonError("R5P2_A1_A2_INPUT_BYTES_INVALID")
    try:
        decoded = raw.decode("utf-8")
        decoder = json.JSONDecoder(object_pairs_hook=_strict_object,
                                   parse_int=_strict_int, parse_float=_reject_float,
                                   parse_constant=_reject_float)
        value, end = decoder.raw_decode(decoded)
        if decoded[end:].strip() or type(value) is not dict:
            raise ComparisonError("R5P2_A1_A2_INPUT_JSON_INVALID")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ComparisonError("R5P2_A1_A2_INPUT_JSON_INVALID") from error


def _exact(value: Any, keys: set[str], code: str) -> dict[str, Any]:
    if type(value) is not dict or set(value) != keys:
        raise ComparisonError(code)
    return value


def _payload_safe(value: Any) -> None:
    forbidden_keys = {
        "rawbytes", "normalizedbytes", "encodedimage", "boundingbox",
        "sourceboundingbox", "sourcepixelbox", "ocrtext", "goldtext",
        "prompttext", "providerrequest", "providerresponse", "modeloutput",
        "candidatejson", "rootdocument", "base64",
    }
    if type(value) is dict:
        for key, child in value.items():
            if key.lower() in forbidden_keys:
                raise ComparisonError("R5P2_A1_A2_PAYLOAD_FORBIDDEN")
            _payload_safe(child)
    elif type(value) is list:
        for child in value:
            _payload_safe(child)
    elif type(value) is str:
        lowered = value.lower()
        if "data:image" in lowered or lowered.startswith("bearer "):
            raise ComparisonError("R5P2_A1_A2_PAYLOAD_FORBIDDEN")


def read_a2(path: pathlib.Path) -> dict[str, Any]:
    envelope = _exact(_read_json(path), {
        "envelopeVersion", "evidence", "evidenceIdentity",
    }, "R5P2_A1_A2_A2_ENVELOPE_INVALID")
    if envelope["envelopeVersion"] != replay.ENVELOPE_VERSION:
        raise ComparisonError("R5P2_A1_A2_A2_ENVELOPE_INVALID")
    identity = replay.EVIDENCE_VERSION + ":" + sha256(
        canonical_json(envelope["evidence"]))
    if envelope["evidenceIdentity"] != identity:
        raise ComparisonError("R5P2_A1_A2_A2_IDENTITY_INVALID")
    replay.validate_evidence(envelope["evidence"])
    _payload_safe(envelope)
    return envelope


def read_producer(path: pathlib.Path) -> dict[str, Any]:
    envelope = _exact(_read_json(path), {
        "envelopeVersion", "report", "reportIdentity",
    }, "R5P2_A1_A2_A1_ENVELOPE_INVALID")
    report = envelope["report"]
    if (envelope["envelopeVersion"] !=
            "renderweave-r5p2-paired-product-view-envelope/1.0"
            or type(report) is not dict
            or report.get("reportVersion") !=
            "renderweave-r5p2-paired-product-view-report/1.0"):
        raise ComparisonError("R5P2_A1_A2_A1_ENVELOPE_INVALID")
    identity = report["reportVersion"] + ":" + sha256(canonical_json(report))
    if envelope["reportIdentity"] != identity:
        raise ComparisonError("R5P2_A1_A2_A1_IDENTITY_INVALID")
    _payload_safe(envelope)
    return envelope


def _stable_resources(value: dict[str, Any]) -> dict[str, Any]:
    return {key: value[key] for key in (
        "totalViews", "inspectedViews", "totalEncodedBytes", "totalPixels",
        "inspectedPixels", "additionalVisualTokens",
    )}


def _branch_stage(value: dict[str, Any], fields: tuple[str, ...]) -> dict[str, Any]:
    return {key: value[key] for key in fields}


def _first_mismatch(producer: dict[str, Any], independent: dict[str, Any]) -> str | None:
    headers = (
        "authorityIdentity", "assignmentIdentity", "fixtureSetIdentity",
        "evaluationIdentity", "thresholdIdentity",
    )
    if any(producer.get(key) != independent.get(key) for key in headers):
        return COMPARISON_ORDER[0]
    if producer.get("stageIdentities") != independent.get("stageIdentities"):
        return COMPARISON_ORDER[1]
    if producer.get("accounting") != independent.get("accounting"):
        return COMPARISON_ORDER[2]
    producer_determinism = producer.get("determinism", {})
    independent_determinism = independent.get("determinism", {})
    deterministic_fields = (
        "comparedCases", "equivalentCases", "comparedBranches",
        "equivalentBranches", "deterministic",
    )
    if any(producer_determinism.get(key) != independent_determinism.get(key)
           for key in deterministic_fields):
        return COMPARISON_ORDER[3]
    runs = producer.get("runs")
    cases = independent.get("cases")
    if type(runs) is not list or len(runs) != 2 or type(cases) is not list \
            or len(cases) != 12:
        return COMPARISON_ORDER[4]
    first_cases = runs[0].get("caseResults") if type(runs[0]) is dict else None
    if type(first_cases) is not list or len(first_cases) != 12:
        return COMPARISON_ORDER[4]
    assignment_fields = ("caseId", "caseIdentity", "cohort", "partition")
    for left, right in zip(first_cases, cases, strict=True):
        if any(left.get(key) != right.get(key) for key in assignment_fields):
            return COMPARISON_ORDER[4]
        if left.get("normalization") != right.get("normalization"):
            return COMPARISON_ORDER[5]
        for branch_name in ("baseline", "successor"):
            left_branch = left.get(branch_name, {})
            right_branch = right.get(branch_name, {})
            if _branch_stage(left_branch, ("branch", "planVersion", "planIdentity")) != \
                    _branch_stage(right_branch, ("branch", "planVersion", "planIdentity")):
                return COMPARISON_ORDER[6]
            view_fields = (
                "requestIdentity", "plannedViewCount", "acquiredViewCount",
                "branchAcquisitionProcesses", "artifactViews", "viewTrace",
                "totalEncodedBytes", "totalPixels", "rawObservationCount",
                "projectedObservationCount", "reconciledObservationCount",
            )
            if _branch_stage(left_branch, view_fields) != \
                    _branch_stage(right_branch, view_fields):
                return COMPARISON_ORDER[7]
            observation_fields = (
                "rawObservationIdentity", "canonicalObservationIdentity",
            )
            if _branch_stage(left_branch, observation_fields) != \
                    _branch_stage(right_branch, observation_fields):
                return COMPARISON_ORDER[8]
            if left_branch.get("reconciledMetricInputIdentity") != \
                    right_branch.get("reconciledMetricInputIdentity"):
                return COMPARISON_ORDER[9]
            try:
                resources_equal = (_stable_resources(left_branch["resources"]) ==
                                   _stable_resources(right_branch["resources"]))
            except (KeyError, TypeError):
                resources_equal = False
            if (left_branch.get("resourceIdentity") != right_branch.get("resourceIdentity")
                    or not resources_equal
                    or left_branch.get("externalProviderUsage") !=
                    right_branch.get("externalProviderUsage")
                    or left_branch.get("apiKeyReads") != right_branch.get("apiKeyReads")):
                return COMPARISON_ORDER[10]
            if left_branch.get("metrics") != right_branch.get("metrics"):
                return COMPARISON_ORDER[11]
        if left.get("pairMetrics") != right.get("pairMetrics"):
            return COMPARISON_ORDER[12]
    if (producer.get("diagnosticSummary") != independent.get("diagnosticSummary")
            or producer.get("confirmationSummary") !=
            independent.get("confirmationSummary")):
        return COMPARISON_ORDER[13]
    if producer.get("transitBoardV3") != independent.get("transitBoardV3"):
        return COMPARISON_ORDER[14]
    producer_candidate = replay.candidate_terminal(
        True, bool(producer["diagnosticSummary"]["thresholdPass"]),
        bool(producer["confirmationSummary"]["thresholdPass"]))
    if (independent.get("candidateTerminal") != producer_candidate
            or independent.get("qualityObservationPass") !=
            producer.get("producerQualityObservationPass")):
        return COMPARISON_ORDER[15]
    expected_terminal_input = replay.terminal_input_identity(
        independent["accounting"], independent["diagnosticSummary"],
        independent["confirmationSummary"], independent["candidateTerminal"])
    if independent.get("terminalInputIdentity") != expected_terminal_input:
        return COMPARISON_ORDER[16]
    return None


def _result(producer_identity: str, independent_identity: str,
            mismatch: str | None, candidate: str) -> dict[str, Any]:
    exact = mismatch is None
    result = {
        "comparisonVersion": VERSION,
        "assurance": ASSURANCE,
        "producerReportIdentity": producer_identity,
        "independentEvidenceIdentity": independent_identity,
        "comparisonOrderIdentity": "renderweave-r5p2-a1-a2-comparison-order/1.0:"
                                   + sha256(canonical_json(COMPARISON_ORDER)),
        "comparedCases": 12 if exact else 0,
        "comparisonExact": exact,
        "measurementValid": exact,
        "firstMismatchStage": mismatch,
        "candidateTerminal": candidate if exact else INVALID,
        "externalProviderUsage": {"attempts": 0, "reservations": 0,
                                  "costMicrosCny": 0},
        "apiKeyReads": 0,
        "payloadBoundary": {
            "imagePersisted": False, "geometryPayloadPersisted": False,
            "ocrTextPersisted": False, "goldTextPersisted": False,
            "promptCandidateOrRootDocumentPersisted": False,
        },
        "terminalCode": EXACT if exact else INVALID,
    }
    _payload_safe(result)
    return result


def compare_documents(producer_envelope: dict[str, Any],
                      independent_envelope: dict[str, Any]) -> dict[str, Any]:
    producer = producer_envelope["report"]
    independent = independent_envelope["evidence"]
    mismatch = _first_mismatch(producer, independent)
    return _result(
        producer_envelope["reportIdentity"],
        independent_envelope["evidenceIdentity"], mismatch,
        independent.get("candidateTerminal", INVALID))


def envelope(result: dict[str, Any]) -> dict[str, Any]:
    identity = VERSION + ":" + sha256(canonical_json(result))
    return {"envelopeVersion": ENVELOPE_VERSION,
            "comparison": result, "comparisonIdentity": identity}


def compare_files(producer_path: pathlib.Path, a2_path: pathlib.Path) -> dict[str, Any]:
    independent = read_a2(a2_path)
    producer = read_producer(producer_path)
    return envelope(compare_documents(producer, independent))


def _write_new(path: pathlib.Path, payload: bytes) -> None:
    if len(payload) > MAX_OUTPUT_BYTES:
        raise ComparisonError("R5P2_A1_A2_OUTPUT_BYTES_INVALID")
    with path.open("xb") as stream:
        stream.write(payload)
        stream.write(b"\n")


def synthetic_inputs_for_tests() -> tuple[dict[str, Any], dict[str, Any]]:
    evidence = replay.synthetic_evidence_for_tests()
    zero_usage = {"attempts": 0, "reservations": 0, "costMicrosCny": 0}
    for index, item in enumerate(evidence["cases"]):
        item["normalization"] = {
            "sourceReference": f"r5p2-raw-fixture:{item['caseId']}:" + "1" * 64,
            "rawFixtureSha256": "1" * 64, "inputFingerprint": "2" * 64,
            "normalizedArtifactId": "1" * 64, "mediaType": "image/png",
            "encodedBytes": 1, "width": 1, "height": 1,
            "blobWrites": 1, "blobReads": 1,
        }
        for name, branch in (("baseline", item["baseline"]),
                             ("successor", item["successor"])):
            branch.update({
                "branch": name.upper(),
                "planVersion": (replay.STATIC_PLAN_VERSION if name == "baseline"
                                else replay.SUCCESSOR_PLAN_VERSION),
                "planIdentity": "renderweave-test-plan/1.0:" + "3" * 64,
                "requestIdentity": "renderweave-test-request/1.0:" + "4" * 64,
                "plannedViewCount": 1, "acquiredViewCount": 1,
                "branchAcquisitionProcesses": 1, "artifactViews": 1,
                "viewTrace": [{"viewIdentity": "renderweave-test-view/1.0:" + "5" * 64}],
                "totalEncodedBytes": 1, "totalPixels": 1,
                "rawObservationCount": 1, "projectedObservationCount": 1,
                "reconciledObservationCount": 1,
                "rawObservationIdentity": "renderweave-test-raw/1.0:" + "6" * 64,
                "canonicalObservationIdentity": "renderweave-test-canonical/1.0:" + "7" * 64,
                "reconciledMetricInputIdentity": "renderweave-test-reconciled/1.0:" + "8" * 64,
                "resourceIdentity": "renderweave-test-resources/1.0:" + "9" * 64,
                "resources": {"totalViews": 1, "inspectedViews": 0,
                              "totalEncodedBytes": 1, "totalPixels": 1,
                              "inspectedPixels": 0, "additionalVisualTokens": 0,
                              "localTransformMillis": 0, "acquisitionMicros": 0},
                "externalProviderUsage": copy.deepcopy(zero_usage), "apiKeyReads": 0,
            })
    evidence["terminalInputIdentity"] = replay.terminal_input_identity(
        evidence["accounting"], evidence["diagnosticSummary"],
        evidence["confirmationSummary"], evidence["candidateTerminal"])
    independent = replay.envelope(evidence)
    run_cases = copy.deepcopy(evidence["cases"])
    producer_report = {
        "reportVersion": "renderweave-r5p2-paired-product-view-report/1.0",
        "authorityIdentity": evidence["authorityIdentity"],
        "assignmentIdentity": evidence["assignmentIdentity"],
        "fixtureSetIdentity": evidence["fixtureSetIdentity"],
        "evaluationIdentity": evidence["evaluationIdentity"],
        "thresholdIdentity": evidence["thresholdIdentity"],
        "stageIdentities": copy.deepcopy(evidence["stageIdentities"]),
        "accounting": copy.deepcopy(evidence["accounting"]),
        "runs": [
            {"runOrdinal": 1,
             "accounting": {"capabilityProbeProcesses": 1,
                            "branchAcquisitionProcesses": 24, "artifactViews": 68,
                            "normalizationExecutions": 12, "actionExecutions": 12},
             "caseResults": copy.deepcopy(run_cases)},
            {"runOrdinal": 2,
             "accounting": {"capabilityProbeProcesses": 1,
                            "branchAcquisitionProcesses": 24, "artifactViews": 68,
                            "normalizationExecutions": 12, "actionExecutions": 12},
             "caseResults": copy.deepcopy(run_cases)},
        ],
        "determinism": {"comparedCases": 12, "equivalentCases": 12,
                        "comparedBranches": 24, "equivalentBranches": 24,
                        "deterministic": True,
                        "verdictCode": "R5P2_PAIRED_TWO_RUN_DETERMINISTIC"},
        "diagnosticSummary": copy.deepcopy(evidence["diagnosticSummary"]),
        "confirmationSummary": copy.deepcopy(evidence["confirmationSummary"]),
        "transitBoardV3": copy.deepcopy(evidence["transitBoardV3"]),
        "producerQualityObservationPass": evidence["qualityObservationPass"],
        "holdoutAccess": {"role": "OFFICIAL_PRODUCER", "caseId": "product-catalog-v5",
                          "status": "SEALED", "goldMetricReads": 1},
        "externalProviderUsage": copy.deepcopy(zero_usage), "apiKeyReads": 0,
        "finalTerminalClaimed": False,
        "terminalCode": "R5P2_PAIRED_PRODUCER_COMPLETE",
    }
    producer = {
        "envelopeVersion": "renderweave-r5p2-paired-product-view-envelope/1.0",
        "report": producer_report,
        "reportIdentity": producer_report["reportVersion"] + ":"
                          + sha256(canonical_json(producer_report)),
    }
    return producer, independent


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--producer", required=True)
    parser.add_argument("--a2", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = pathlib.Path(args.output).resolve()
    try:
        result = compare_files(pathlib.Path(args.producer).resolve(strict=True),
                               pathlib.Path(args.a2).resolve(strict=True))
    except (ComparisonError, replay.VerificationError, KeyError, TypeError, IndexError) as error:
        code = str(error)
        if code not in COMPARISON_ORDER:
            code = "R5P2_A1_A2_INPUT_INVALID"
        result = envelope(_result(
            "renderweave-r5p2-paired-product-view-report/1.0:" + "0" * 64,
            replay.EVIDENCE_VERSION + ":" + "0" * 64, code, INVALID))
    encoded = canonical_json(result)
    _write_new(output, encoded)
    sys.stdout.write(result["comparison"]["terminalCode"] + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
