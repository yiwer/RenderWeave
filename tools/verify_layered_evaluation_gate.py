#!/usr/bin/env python3
"""Independent, payload-safe R1 completion gate for layered visual evaluation."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import pathlib
import re
import subprocess
import sys
from typing import Any, Iterable, Mapping, Sequence


GATE_VERSION = "renderweave-layered-r1-gate/1.0"
SCANNER_VERSION = "renderweave-layered-evidence-scanner/1.0"
INDEPENDENT_VERSION = "renderweave-layered-r1-independent-verifier/1.0"
R0_PROOF_VERSION = "renderweave-layered-r0-prerequisite-proof/1.0"
ANCHOR_REVISION = "19e22854e0be236d0068336a32969356a6befaf8"

PRIMARY_SEAM = "normalized ArtifactSet + AcquisitionPolicy -> DocumentObservationIR/1.0"
HIGHEST_ACCEPTANCE_SEAM = "complete IMAGE_ONLY scripted replay -> REVIEW_REQUIRED"

PROTECTED_PATHS = (
    ".sdlc/live/visual-evaluation-qwen37-flash.json",
    ".sdlc/live/visual-evaluation-qwen37-plus.json",
    ".sdlc/live/visual-evaluation-qwen38-max.json",
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

PROFILE_IDS = (
    "dashscope-qwen37-flash-product-v45-hybrid-generic",
    "dashscope-qwen37-plus-product-v45-hybrid-generic",
    "dashscope-qwen38-max-product-v45-hybrid-generic",
)

FUTURE_EVIDENCE_GATES = {
    "R2": "R2_BASELINE_GAP_AND_LICENSE_EVIDENCE_REQUIRED",
    "R3": "R3_REPRODUCIBLE_ORDER_FAILURE_EVIDENCE_REQUIRED",
    "R4": "R4_STRICT_PROTOCOL_AND_SHAPE_BOTTLENECK_EVIDENCE_REQUIRED",
    "R5": "R5_STATIC_VIEW_BOTTLENECK_EVIDENCE_REQUIRED",
    "R6": "R6_ORCHESTRATION_PRESSURE_EVIDENCE_REQUIRED",
}

IMAGE_EXTENSIONS = {
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".tif", ".tiff", ".pdf",
}
IMAGE_MAGIC = (
    b"\x89PNG\r\n\x1a\n", b"\xff\xd8\xff", b"GIF87a", b"GIF89a", b"BM", b"RIFF",
    b"II*\x00", b"MM\x00*", b"%PDF-",
)
FORBIDDEN_PAYLOAD_MARKERS = (
    b'"ocrtext"', b'"runtimetext"', b'"prompttext"', b'"modeloutput"',
    b'"providerrequest"', b'"providerresponse"', b'"candidatejson"',
    b'"rootdocument"', b'"boundingbox"', b'"bbox"', b'"polygon"',
    b'"imagebytes"', b'"chainofthought"', b"data:image", b"ignore prior instructions",
    b"bearer ", b"dashscope_api_key",
)
LONG_BASE64 = re.compile(rb"(?:[A-Za-z0-9+/]{256,}={0,2})")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REVISION = re.compile(r"^[0-9a-f]{40}$")
MAXIMUM_EVIDENCE_BYTES = 32 * 1024 * 1024


def _load_layered_verifier():
    path = pathlib.Path(__file__).with_name("verify_layered_evaluation.py")
    spec = importlib.util.spec_from_file_location("renderweave_layered_evaluation_for_gate", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("layered verifier cannot be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


LAYERED = _load_layered_verifier()


def _load_r0_verifier():
    path = pathlib.Path(__file__).with_name("verify_document_observation_r0.py")
    spec = importlib.util.spec_from_file_location("renderweave_document_observation_r0_for_r1", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("R0 verifier cannot be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


R0 = _load_r0_verifier()


class GateVerificationError(Exception):
    pass


def fail(code: str) -> None:
    raise GateVerificationError(code)


def _git(repository: pathlib.Path, *arguments: str) -> bytes:
    try:
        completed = subprocess.run(
            ("git", *arguments), cwd=repository, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        fail("GIT_VERIFICATION_FAILED")
    if completed.returncode != 0:
        fail("GIT_VERIFICATION_FAILED")
    return completed.stdout


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _require_object(value: Any, code: str) -> dict[str, Any]:
    if type(value) is not dict:
        fail(code)
    return value


def _require_list(value: Any, code: str) -> list[Any]:
    if type(value) is not list:
        fail(code)
    return value


def _require_keys(value: dict[str, Any], expected: Iterable[str], code: str) -> None:
    if set(value) != set(expected):
        fail(code)


def _require_exact(value: Any, expected: Any, code: str) -> None:
    if value != expected:
        fail(code)


def protected_file_rows(repository: pathlib.Path) -> list[dict[str, str]]:
    repository = repository.resolve(strict=True)
    rows: list[dict[str, str]] = []
    for relative in PROTECTED_PATHS:
        path = repository / relative
        if path.is_symlink() or not path.is_file():
            fail("PROTECTED_FILE_MISSING")
        current = path.read_bytes()
        difference = _git(
            repository, "diff", "--no-ext-diff", "--binary", ANCHOR_REVISION, "--", relative,
        )
        if difference:
            fail("PROTECTED_FILE_BYTES_CHANGED")
        rows.append({"path": relative, "sha256": _sha256(current)})
    return rows


def _verify_product_profiles(repository: pathlib.Path) -> None:
    root = repository / "renderweave-inference/src/main/resources/inference-profiles"
    for profile_id in PROFILE_IDS:
        path = root / f"{profile_id}.json"
        try:
            profile = LAYERED.parse_strict_json(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, LAYERED.VerificationError):
            fail("PRODUCT_V45_PROFILE_INVALID")
        profile = _require_object(profile, "PRODUCT_V45_PROFILE_INVALID")
        expected = {
            "profileId": profile_id,
            "pipelineVersion": "renderweave-inference-pipeline/4.28",
            "certification": "EXPERIMENTAL",
            "responseFormat": "JSON_OBJECT",
            "toolsAllowed": False,
            "remoteMediaAllowed": False,
            "maximumRepairRounds": 0,
            "supportedModes": ["IMAGE_ONLY"],
        }
        for key, value in expected.items():
            if profile.get(key) != value:
                fail("PRODUCT_V45_PROFILE_INVALID")


def _scan_evidence_payload(name: str, payload: bytes) -> None:
    if not name or pathlib.PurePath(name).name != name:
        fail("EVIDENCE_NAME_INVALID")
    lowered_name = name.lower()
    if pathlib.PurePath(lowered_name).suffix in IMAGE_EXTENSIONS:
        fail("IMAGE_EVIDENCE_FORBIDDEN")
    if len(payload) > MAXIMUM_EVIDENCE_BYTES:
        fail("EVIDENCE_TOO_LARGE")
    if any(payload.startswith(magic) for magic in IMAGE_MAGIC):
        fail("IMAGE_EVIDENCE_FORBIDDEN")
    lowered = payload.lower()
    if any(marker in lowered for marker in FORBIDDEN_PAYLOAD_MARKERS) or LONG_BASE64.search(payload):
        fail("FORBIDDEN_EVIDENCE_MEMBER")
    if pathlib.PurePath(lowered_name).suffix == ".json":
        try:
            LAYERED.parse_strict_json(payload.decode("utf-8"))
        except (UnicodeError, LAYERED.VerificationError):
            fail("EVIDENCE_JSON_INVALID")


def _verify_payloads(summary: dict[str, Any], payloads: Mapping[str, bytes]) -> None:
    scan = _require_object(summary["payloadScan"], "PAYLOAD_SCAN_INVALID")
    _require_keys(scan, ("result", "scanner", "forbiddenMatches", "files"), "PAYLOAD_SCAN_INVALID")
    expected_files = _require_list(scan["files"], "PAYLOAD_SCAN_INVALID")
    if (
        scan["result"] != "passed"
        or scan["scanner"] != SCANNER_VERSION
        or scan["forbiddenMatches"] != 0
        or not expected_files
        or any(type(name) is not str for name in expected_files)
        or len(expected_files) != len(set(expected_files))
    ):
        fail("PAYLOAD_SCAN_INVALID")
    for name, payload in payloads.items():
        if type(name) is not str or type(payload) is not bytes:
            fail("EVIDENCE_PAYLOAD_INVALID")
        _scan_evidence_payload(name, payload)
    if set(expected_files) != set(payloads):
        fail("EVIDENCE_FILE_SET_INVALID")


def verify_r0_report(path: pathlib.Path, repository: pathlib.Path) -> dict[str, Any]:
    try:
        R0.verify(path, repository)
        raw = path.read_bytes()
        document = LAYERED.parse_strict_json(raw.decode("utf-8"))
    except (OSError, UnicodeError, R0.VerificationError, LAYERED.VerificationError):
        fail("R0_PREREQUISITE_VERIFICATION_FAILED")
    document = _require_object(document, "R0_PREREQUISITE_VERIFICATION_FAILED")
    try:
        revision = document["revision"]
        terminal = document["behaviorOracle"]["terminalState"]
        provider = document["externalProvider"]
    except (KeyError, TypeError):
        fail("R0_PREREQUISITE_VERIFICATION_FAILED")
    return {
        "proofVersion": R0_PROOF_VERSION,
        "result": "PASS",
        "assurance": "A2_STRICT_INPUT_REPLAY",
        "reportFile": path.name,
        "reportSha256": _sha256(raw),
        "revision": revision,
        "terminalState": terminal,
        "providerAttempts": provider.get("attempts") if type(provider) is dict else None,
        "providerReservations": provider.get("reservations") if type(provider) is dict else None,
        "externalProviderCostMicrosCny": provider.get("costMicrosCny") if type(provider) is dict else None,
    }


def verify_documents(
        envelope: Any,
        claimed_independent: Any,
        gate_summary: Any,
        repository: pathlib.Path,
        evidence_payloads: Mapping[str, bytes],
        r0_verification: Mapping[str, Any],
) -> dict[str, Any]:
    repository = repository.resolve(strict=True)
    try:
        recomputed = LAYERED.verify_envelope(envelope, repository)
    except LAYERED.VerificationError as exc:
        fail(f"LAYERED_REPORT_INVALID:{exc}")
    claimed = _require_object(claimed_independent, "INDEPENDENT_SUMMARY_INVALID")
    if claimed != recomputed:
        fail("CROSS_LANGUAGE_SUMMARY_DRIFT")

    summary = _require_object(gate_summary, "GATE_SUMMARY_INVALID")
    _require_keys(summary, (
        "reportVersion", "result", "assurance", "anchorRevision", "revision",
        "seams", "architecture", "scope", "identities", "crossLanguage",
        "caseAccounting", "externalProvider", "historicalBytes", "lifecycle",
        "r0Prerequisite", "futureEvidenceGates", "visualDiff", "payloadScan",
    ), "GATE_SUMMARY_FIELDS_INVALID")
    _require_exact(summary["reportVersion"], GATE_VERSION, "GATE_VERSION_INVALID")
    _require_exact(summary["result"], "passed", "GATE_RESULT_INVALID")
    _require_exact(summary["assurance"], "A1+A2-strict-input", "GATE_ASSURANCE_INVALID")
    _require_exact(summary["anchorRevision"], ANCHOR_REVISION, "GATE_ANCHOR_INVALID")
    revision = summary["revision"]
    if type(revision) is not str or not REVISION.fullmatch(revision):
        fail("GATE_REVISION_INVALID")
    current_revision = _git(repository, "rev-parse", "HEAD").decode("ascii").strip()
    _require_exact(revision, current_revision, "GATE_REVISION_INVALID")

    _require_exact(summary["seams"], {
        "primary": PRIMARY_SEAM,
        "highestAcceptance": HIGHEST_ACCEPTANCE_SEAM,
    }, "GATE_SEAMS_INVALID")
    _require_exact(summary["architecture"], {
        "orchestration": "existing-postgresql-durable-typed-state-machine",
        "semanticStages": "serial",
        "localRepair": "validator-driven-bounded",
        "openEndedAgent": False,
        "generalToolExecutor": False,
        "langGraph": False,
        "temporal": False,
    }, "GATE_ARCHITECTURE_INVALID")
    _require_exact(summary["scope"], {
        "r0Complete": True,
        "r1Complete": True,
        "template": False,
        "rootDocumentConnect": False,
        "dataAdaptation": False,
        "publishing": False,
    }, "GATE_SCOPE_INVALID")
    r0_report_sha = r0_verification.get("reportSha256")
    if type(r0_report_sha) is not str or not SHA256.fullmatch(r0_report_sha):
        fail("R0_PREREQUISITE_INVALID")
    expected_r0 = {
        "proofVersion": R0_PROOF_VERSION,
        "result": "PASS",
        "assurance": "A2_STRICT_INPUT_REPLAY",
        "reportFile": "document-observation-r0-summary.json",
        "reportSha256": r0_report_sha,
        "revision": revision,
        "terminalState": "REVIEW_REQUIRED",
        "providerAttempts": 0,
        "providerReservations": 0,
        "externalProviderCostMicrosCny": 0,
    }
    if dict(r0_verification) != expected_r0 or summary["r0Prerequisite"] != expected_r0:
        fail("R0_PREREQUISITE_INVALID")
    if expected_r0["reportFile"] not in evidence_payloads:
        fail("R0_PREREQUISITE_EVIDENCE_MISSING")

    identity_keys = (
        "reportIdentity", "evaluationIdentity", "corpusIdentity", "annotationSetIdentity",
        "recordSetIdentity", "caseAssignmentIdentity", "recomputedMetricsIdentity",
        "corpusLockIdentity",
    )
    _require_exact(summary["identities"], {
        key: recomputed[key] for key in identity_keys
    }, "GATE_IDENTITY_DRIFT")
    _require_exact(summary["crossLanguage"], {
        "java": "PASS",
        "python": "PASS",
        "exactIdentity": True,
        "exactCaseAccounting": True,
        "exactAllMetrics": True,
        "caseCount": recomputed["caseCount"],
        "metricCount": recomputed["metricCount"],
        "sliceAggregateCount": recomputed["sliceAggregateCount"],
    }, "CROSS_LANGUAGE_ACCOUNTING_DRIFT")
    _require_exact(summary["caseAccounting"], {
        "expected": 60,
        "observed": recomputed["caseCount"],
        "partitions": recomputed["partitions"],
        "domains": recomputed["domains"],
        "difficulties": recomputed["difficulties"],
        "failureSlices": recomputed["failureSlices"],
    }, "CASE_ACCOUNTING_DRIFT")

    _require_exact(summary["externalProvider"], {
        "attempts": 0, "reservations": 0, "costMicrosCny": 0,
    }, "GATE_PROVIDER_USAGE_NONZERO")
    historical = _require_object(summary["historicalBytes"], "PROTECTED_FILE_MANIFEST_DRIFT")
    _require_keys(historical, ("unchanged", "protectedFiles"), "PROTECTED_FILE_MANIFEST_DRIFT")
    protected = protected_file_rows(repository)
    if historical["unchanged"] is not True or historical["protectedFiles"] != protected:
        fail("PROTECTED_FILE_MANIFEST_DRIFT")

    _verify_product_profiles(repository)
    _require_exact(summary["lifecycle"], {
        "productV45": "EXPERIMENTAL",
        "n7": "in_progress",
        "ac021": "not_satisfied",
        "acVr010": "not_satisfied",
        "finalBusinessVisualJudgement": "J0",
    }, "GATE_LIFECYCLE_INVALID")
    expected_future = {
        key: {"triggered": False, "code": value}
        for key, value in FUTURE_EVIDENCE_GATES.items()
    }
    _require_exact(summary["futureEvidenceGates"], expected_future, "FUTURE_GATE_AUTO_TRIGGERED")
    _require_exact(summary["visualDiff"], {
        "scope": "local-allowlisted-only",
        "automatedEvidence": "A1",
        "humanReview": "human_review_pending",
        "judgement": "J0",
        "evidenceIncluded": False,
    }, "VISUAL_DIFF_EVIDENCE_INVALID")
    _verify_payloads(summary, evidence_payloads)

    protected_identity = (
        "renderweave-protected-v45-manifest/1.0:"
        + _sha256(LAYERED.canonical_json(protected))
    )
    return {
        "verifierVersion": INDEPENDENT_VERSION,
        "result": "PASS",
        "assurance": "A2_STRICT_INPUT_REPLAY",
        **{key: recomputed[key] for key in identity_keys},
        "caseCount": recomputed["caseCount"],
        "metricCount": recomputed["metricCount"],
        "sliceAggregateCount": recomputed["sliceAggregateCount"],
        "providerAttempts": 0,
        "providerReservations": 0,
        "externalProviderCostMicrosCny": 0,
        "protectedManifestIdentity": protected_identity,
        "productV45Lifecycle": "EXPERIMENTAL",
        "n7": "in_progress",
        "r0Prerequisite": "A2_STRICT_INPUT_REPLAY",
        "visualDiffJudgement": "J0",
        "futureEvidenceGatesTriggered": False,
        "payloadForbiddenMatches": 0,
        "evidenceFiles": sorted(evidence_payloads),
    }


def _strict_json_file(path: pathlib.Path) -> tuple[Any, bytes]:
    raw = path.read_bytes()
    if len(raw) > MAXIMUM_EVIDENCE_BYTES:
        fail("EVIDENCE_TOO_LARGE")
    try:
        document = LAYERED.parse_strict_json(raw.decode("utf-8"))
    except (UnicodeError, LAYERED.VerificationError):
        fail("EVIDENCE_JSON_INVALID")
    return document, raw


def _confined_input(path: pathlib.Path, evidence_root: pathlib.Path) -> pathlib.Path:
    try:
        resolved = path.resolve(strict=True)
    except OSError:
        fail("EVIDENCE_FILE_MISSING")
    if path.is_symlink() or not resolved.is_file() or not resolved.is_relative_to(evidence_root):
        fail("EVIDENCE_PATH_INVALID")
    return resolved


def _confined_output(path: pathlib.Path, evidence_root: pathlib.Path) -> pathlib.Path:
    absolute = path.absolute()
    if absolute.exists() or absolute.is_symlink():
        fail("EVIDENCE_OUTPUT_EXISTS")
    try:
        parent = absolute.parent.resolve(strict=True)
    except OSError:
        fail("EVIDENCE_OUTPUT_PARENT_INVALID")
    if not parent.is_relative_to(evidence_root):
        fail("EVIDENCE_OUTPUT_PATH_INVALID")
    return parent / absolute.name


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify the RenderWeave layered R1 completion gate")
    parser.add_argument("--report", required=True, type=pathlib.Path)
    parser.add_argument("--verifier-summary", required=True, type=pathlib.Path)
    parser.add_argument("--gate-summary", required=True, type=pathlib.Path)
    parser.add_argument("--r0-report", required=True, type=pathlib.Path)
    parser.add_argument("--repository", type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1])
    parser.add_argument("--evidence-file", action="append", default=[], type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    arguments = parser.parse_args(argv)
    try:
        repository = arguments.repository.resolve(strict=True)
        evidence_root = (repository / ".sdlc/evidence").resolve(strict=True)
        report_path = _confined_input(arguments.report, evidence_root)
        verifier_path = _confined_input(arguments.verifier_summary, evidence_root)
        gate_path = _confined_input(arguments.gate_summary, evidence_root)
        r0_path = _confined_input(arguments.r0_report, evidence_root)
        output_path = _confined_output(arguments.output, evidence_root)
        paths = [report_path, verifier_path, gate_path, r0_path]
        paths.extend(_confined_input(path, evidence_root) for path in arguments.evidence_file)
        unique: dict[str, pathlib.Path] = {}
        for path in paths:
            if path.name in unique and unique[path.name] != path:
                fail("EVIDENCE_NAME_COLLISION")
            unique[path.name] = path
        envelope, _ = _strict_json_file(report_path)
        claimed, _ = _strict_json_file(verifier_path)
        summary, _ = _strict_json_file(gate_path)
        payloads = {name: path.read_bytes() for name, path in unique.items()}
        r0_verification = verify_r0_report(r0_path, repository)
        result = verify_documents(
            envelope, claimed, summary, repository, payloads, r0_verification,
        )
        output_path.write_bytes(LAYERED.canonical_json(result) + b"\n")
    except (GateVerificationError, OSError) as exc:
        code = str(exc) if isinstance(exc, GateVerificationError) else "EVIDENCE_IO_FAILED"
        print(f"layeredR1GateVerification=FAIL code={code}", file=sys.stderr)
        return 1
    print(
        "layeredR1GateVerification=PASS assurance=A2_STRICT_INPUT_REPLAY "
        "cases=60 externalProviderAttempts=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
