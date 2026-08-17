#!/usr/bin/env python3
"""Independent strict-input replay for IMAGE_ONLY Profile certification P0 evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any, Iterable


REPORT_VERSION = "renderweave-image-only-certification-p0-report/1.0"
MANIFEST_VERSION = "renderweave-image-only-certification-manifest/1.0"
EVALUATOR_VERSION = "renderweave-image-only-certification-evaluator/1.0"
R1_VERSION = "renderweave-layered-r1-evaluation/1.0"
LAYERED_EVALUATOR_VERSION = "renderweave-layered-evaluator/1.0"
CORPUS_VERSION = "renderweave-visual-stage-corpus/2.0"
V45 = "dashscope-qwen38-max-product-v45-hybrid-generic"
V46 = "dashscope-qwen38-max-product-v46-hybrid-generic"
V46_SHA = "22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c"
LOCK_SHA = "cf54fd985e89a024fdc0742a737c21442c49718fdf58b0bb05b87e2cffd2247d"


class VerificationError(RuntimeError):
    pass


def _pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in values:
        if key in result:
            raise VerificationError(f"DUPLICATE_JSON_KEY:{key}")
        result[key] = value
    return result


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_pairs)
    except (OSError, json.JSONDecodeError) as exc:
        raise VerificationError(f"INVALID_JSON:{path}") from exc


def require_keys(value: dict[str, Any], expected: set[str], code: str) -> None:
    if set(value) != expected:
        raise VerificationError(f"{code}:expected={sorted(expected)}:actual={sorted(value)}")


def length_hash(values: Iterable[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def require_digest(value: str, prefix: str, code: str) -> None:
    if not re.fullmatch(re.escape(prefix) + r":[0-9a-f]{64}", value):
        raise VerificationError(code)


def verify(report_path: Path, repository: Path) -> dict[str, Any]:
    envelope = load_json(report_path)
    require_keys(envelope, {"reportIdentity", "report"}, "REPORT_ENVELOPE_KEYS")
    report = envelope["report"]
    require_keys(report, {
        "authority", "authorization", "dryRun", "externalProvider", "layeredR1",
        "manifest", "profile", "reportVersion",
    }, "REPORT_KEYS")
    if report["reportVersion"] != REPORT_VERSION:
        raise VerificationError("REPORT_VERSION_INVALID")
    report_bytes = json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    expected_report_identity = REPORT_VERSION + ":" + hashlib.sha256(report_bytes).hexdigest()
    if envelope["reportIdentity"] != expected_report_identity:
        raise VerificationError("REPORT_IDENTITY_MISMATCH")

    profile = report["profile"]
    require_keys(profile, {
        "canonicalSha256", "hiddenFromProductCatalog", "maximumRunCostMicrosCny",
        "maximumTotalCalls", "profileId", "semanticDiffFields",
    }, "PROFILE_PROOF_KEYS")
    v45_path = repository / "renderweave-inference/src/main/resources/inference-profiles" / f"{V45}.json"
    v46_path = repository / "renderweave-inference/src/main/resources/inference-profiles" / f"{V46}.json"
    v45 = load_json(v45_path)
    v46 = load_json(v46_path)
    differences = sorted(key for key in set(v45) | set(v46) if v45.get(key) != v46.get(key))
    if differences != ["maximumEstimatedCostMicrosCny", "maximumTotalCalls", "profileId"]:
        raise VerificationError("V46_SEMANTIC_DIFF_INVALID")
    canonical_v46_sha = hashlib.sha256(
        json.dumps(v46, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    if canonical_v46_sha != V46_SHA or profile != {
        "canonicalSha256": V46_SHA,
        "hiddenFromProductCatalog": True,
        "maximumRunCostMicrosCny": 6_000_000,
        "maximumTotalCalls": 12,
        "profileId": V46,
        "semanticDiffFields": differences,
    }:
        raise VerificationError("V46_PROFILE_PROOF_INVALID")

    authority = report["authority"]
    require_keys(authority, {
        "baselineLifecycle", "inventorySha256", "prohibitedReferences", "reusableReferences",
    }, "AUTHORITY_PROOF_KEYS")
    inventory_path = repository / (
        "renderweave-inference/src/main/resources/image-only-production-admission/"
        "authority-inventory-v1.json"
    )
    inventory = load_json(inventory_path)
    inventory_sha = hashlib.sha256(inventory_path.read_bytes()).hexdigest()
    reusable = sorted(item["referenceId"] for item in inventory["entries"]
                      if item["reuseDisposition"] == "REUSABLE")
    prohibited = sorted(item["referenceId"] for item in inventory["entries"]
                        if item["reuseDisposition"] == "PROHIBITED")
    if authority != {
        "baselineLifecycle": "ACTIVE_EXPERIMENTAL",
        "inventorySha256": inventory_sha,
        "prohibitedReferences": prohibited,
        "reusableReferences": reusable,
    }:
        raise VerificationError("AUTHORITY_PROOF_INVALID")
    accounting = inventory["providerAccounting"]
    if accounting != {"apiKeyReads": 0, "attempts": 0, "costMicrosCny": 0, "reservations": 0}:
        raise VerificationError("AUTHORITY_PROVIDER_ACCOUNTING_NONZERO")

    lock_path = repository / "renderweave-inference/src/main/resources/visual-eval/v2/identity-lock.json"
    if hashlib.sha256(lock_path.read_bytes()).hexdigest() != LOCK_SHA:
        raise VerificationError("LAYERED_CORPUS_LOCK_BYTES_CHANGED")
    lock = load_json(lock_path)
    manifest = report["manifest"]
    require_keys(manifest, {
        "assignmentSeed", "assignments", "canaries", "corpusIdentity", "evaluatorIdentity",
        "manifestIdentity", "profileId", "profileSha256", "r1InfrastructureIdentity",
        "thresholds", "version",
    }, "MANIFEST_PROOF_KEYS")
    if manifest["version"] != MANIFEST_VERSION or manifest["profileId"] != V46 \
            or manifest["profileSha256"] != V46_SHA \
            or manifest["corpusIdentity"] != lock["corpusIdentity"] \
            or manifest["r1InfrastructureIdentity"] != R1_VERSION:
        raise VerificationError("MANIFEST_FIXED_IDENTITY_INVALID")
    expected_thresholds = [
        {"acceptanceThreshold": 5, "caseCount": 5, "stage": "CANARY_5"},
        {"acceptanceThreshold": 18, "caseCount": 20, "stage": "DEV_20"},
        {"acceptanceThreshold": 54, "caseCount": 60, "stage": "FINAL_60"},
    ]
    if manifest["thresholds"] != expected_thresholds:
        raise VerificationError("MANIFEST_THRESHOLDS_INVALID")

    evaluator_values = [
        EVALUATOR_VERSION, R1_VERSION, LAYERED_EVALUATOR_VERSION,
        CORPUS_VERSION, lock["corpusIdentity"],
        "terminal=REVIEW_REQUIRED|COMPLETED", "manual-verdict=required",
        "thresholds=5/5|18/20|54/60", "low-confidence=7999bps-flag-only",
        "keys=snake_case|kebab-manual-normalization",
    ]
    expected_evaluator = EVALUATOR_VERSION + ":" + length_hash(evaluator_values)
    if manifest["evaluatorIdentity"] != expected_evaluator:
        raise VerificationError("EVALUATOR_IDENTITY_MISMATCH")

    canaries = manifest["canaries"]
    if len(canaries) != 5 or canaries != sorted(canaries, key=lambda item: item["caseId"]) \
            or len({item["caseId"] for item in canaries}) != 5 \
            or len({item["artifactSha256"] for item in canaries}) != 5:
        raise VerificationError("CANARY_SET_INVALID")
    for item in canaries:
        require_keys(item, {"artifactSha256", "caseId"}, "CANARY_KEYS")
        if not re.fullmatch(r"[0-9a-f]{64}", item["artifactSha256"]):
            raise VerificationError("CANARY_SHA_INVALID")

    assignments = manifest["assignments"]
    if len(assignments) != 60:
        raise VerificationError("ASSIGNMENT_COUNT_INVALID")
    lock_cases = {item["caseId"]: item for item in lock["cases"]}
    ordered = sorted(lock["cases"], key=lambda item: (
        hashlib.sha256((manifest["assignmentSeed"] + "\0" + item["caseIdentity"]).encode()).hexdigest(),
        item["caseId"],
    ))
    for rank, (actual, source) in enumerate(zip(assignments, ordered, strict=True)):
        require_keys(actual, {"caseId", "caseIdentity", "caseSha256", "rank", "role"},
                     "ASSIGNMENT_KEYS")
        expected_role = "DEV_VISIBLE" if rank < 20 else "FINAL_DEV" if rank < 40 else "HOLDOUT"
        expected = {
            "caseId": source["caseId"],
            "caseIdentity": source["caseIdentity"],
            "caseSha256": source["renderIdentity"].removeprefix("render-sha256:"),
            "rank": rank,
            "role": expected_role,
        }
        if actual != expected or actual["caseId"] not in lock_cases:
            raise VerificationError(f"ASSIGNMENT_MISMATCH:{rank}")
    if [item["role"] for item in assignments].count("HOLDOUT") != 20:
        raise VerificationError("HOLDOUT_COUNT_INVALID")

    material = [
        MANIFEST_VERSION, V46, V46_SHA, lock["corpusIdentity"], R1_VERSION,
        expected_evaluator, manifest["assignmentSeed"],
    ]
    material.extend(
        f"threshold|{item['stage']}|{item['caseCount']}|{item['acceptanceThreshold']}"
        for item in expected_thresholds
    )
    material.extend(f"canary|{item['caseId']}|{item['artifactSha256']}" for item in canaries)
    material.extend(
        f"assignment|{item['rank']}|{item['role']}|{item['caseId']}|"
        f"{item['caseSha256']}|{item['caseIdentity']}" for item in assignments
    )
    expected_manifest = MANIFEST_VERSION + ":" + length_hash(material)
    if manifest["manifestIdentity"] != expected_manifest:
        raise VerificationError("MANIFEST_IDENTITY_MISMATCH")

    layered = report["layeredR1"]
    if layered["corpusIdentity"] != lock["corpusIdentity"] \
            or layered["caseCount"] != 60 or layered["metricCount"] != 58:
        raise VerificationError("LAYERED_R1_PROOF_INVALID")
    require_digest(layered["evaluationIdentity"], "renderweave-layered-evaluation/1.0",
                   "LAYERED_R1_EVALUATION_IDENTITY_INVALID")

    dry = report["dryRun"]
    expected_dry = {
        "canary": ("CANARY_5", 5, 5, True, [item["caseId"] for item in canaries]),
        "dev": ("DEV_20", 18, 18, True,
                [item["caseId"] for item in assignments if item["role"] == "DEV_VISIBLE"]),
        "finalStage": ("FINAL_60", 54, 54, True,
                       [item["caseId"] for item in assignments]),
        "negativeCanary": ("CANARY_5", 5, 4, False,
                           [item["caseId"] for item in canaries]),
        "invalidKeyCanary": ("CANARY_5", 5, 4, False,
                             [item["caseId"] for item in canaries]),
    }
    require_keys(dry, set(expected_dry), "DRY_RUN_KEYS")
    observed_semantics: set[str] = set()
    for name, (stage, threshold, expected_accepted, expected_pass,
               expected_case_ids) in expected_dry.items():
        item = dry[name]
        require_keys(item, {
            "acceptedCases", "evidenceIdentity", "passed", "stage", "totalCases", "verdicts",
        }, "DRY_RUN_STAGE_KEYS")
        if item["stage"] != stage or item["totalCases"] != len(expected_case_ids):
            raise VerificationError(f"DRY_RUN_STAGE_INVALID:{name}")
        verdicts = item["verdicts"]
        if not isinstance(verdicts, list) or len(verdicts) != len(expected_case_ids):
            raise VerificationError(f"DRY_RUN_VERDICT_COUNT_INVALID:{name}")
        evidence_material = [manifest["manifestIdentity"], stage]
        accepted = 0
        for expected_case_id, verdict in zip(expected_case_ids, verdicts, strict=True):
            require_keys(verdict, {
                "caseId", "confidenceBps", "keyShapes", "manuallyAccepted", "terminalState",
            }, "DRY_RUN_VERDICT_KEYS")
            if verdict["caseId"] != expected_case_id:
                raise VerificationError(f"DRY_RUN_VERDICT_ORDER_INVALID:{name}")
            terminal = verdict["terminalState"]
            manually_accepted = verdict["manuallyAccepted"]
            confidence = verdict["confidenceBps"]
            key_shapes = verdict["keyShapes"]
            if terminal not in {"REVIEW_REQUIRED", "COMPLETED", "FAILED"} \
                    or type(manually_accepted) is not bool \
                    or type(confidence) is not int or not 0 <= confidence <= 10_000 \
                    or not isinstance(key_shapes, list) or not key_shapes \
                    or any(shape not in {"SNAKE_CASE", "KEBAB_CASE", "INVALID"}
                           for shape in key_shapes):
                raise VerificationError(f"DRY_RUN_VERDICT_SHAPE_INVALID:{name}")
            observed_semantics.add(f"TERMINAL_{terminal}")
            if not manually_accepted:
                observed_semantics.add("MANUAL_REJECTION")
            flags: list[str] = []
            if confidence < 8_000:
                flags.append("LOW_CONFIDENCE_REVIEW_FLAG")
                observed_semantics.add("LOW_CONFIDENCE_REVIEW_FLAG")
            key_contract_valid = True
            for shape in key_shapes:
                if shape == "SNAKE_CASE":
                    observed_semantics.add("SNAKE_CASE")
                elif shape == "KEBAB_CASE":
                    flags.append("KEBAB_CASE_MANUAL_NORMALIZATION_REQUIRED")
                    observed_semantics.add("KEBAB_CASE_MANUAL_NORMALIZATION_REQUIRED")
                elif shape == "INVALID":
                    flags.append("FIELD_KEY_CONTRACT_INVALID")
                    observed_semantics.add("FIELD_KEY_CONTRACT_INVALID")
                    key_contract_valid = False
            case_accepted = (terminal in {"REVIEW_REQUIRED", "COMPLETED"}
                             and manually_accepted and key_contract_valid)
            if case_accepted:
                accepted += 1
            evidence_material.append(
                f"{expected_case_id}|{terminal}|{str(manually_accepted).lower()}|{confidence}|"
                f"{str(key_contract_valid).lower()}|{str(case_accepted).lower()}|{','.join(flags)}"
            )
        passed = accepted >= threshold
        evidence_material.extend([f"accepted={accepted}", f"passed={str(passed).lower()}"])
        expected_evidence = (
            "renderweave-image-only-certification-stage-evidence/1.0:"
            + length_hash(evidence_material)
        )
        if accepted != expected_accepted or item["acceptedCases"] != accepted \
                or item["passed"] != passed \
                or passed != expected_pass or item["evidenceIdentity"] != expected_evidence:
            raise VerificationError(f"DRY_RUN_EXACT_REPLAY_FAILED:{name}")
    if observed_semantics != {
        "LOW_CONFIDENCE_REVIEW_FLAG",
        "KEBAB_CASE_MANUAL_NORMALIZATION_REQUIRED",
        "FIELD_KEY_CONTRACT_INVALID",
        "SNAKE_CASE",
        "MANUAL_REJECTION",
        "TERMINAL_REVIEW_REQUIRED",
        "TERMINAL_COMPLETED",
        "TERMINAL_FAILED",
    }:
        raise VerificationError("DRY_RUN_SEMANTIC_COVERAGE_INVALID")

    authorization = report["authorization"]
    if authorization["openAuthorizationCount"] != 0 \
            or authorization["maximumWindowHours"] != 48 \
            or authorization["maximumModelTokens"] != 1_000_000:
        raise VerificationError("AUTHORIZATION_PROOF_INVALID")
    schema_path = repository / authorization["schemaPath"]
    template_path = repository / authorization["nonExecutableTemplatePath"]
    schema = load_json(schema_path)
    template = load_json(template_path)
    closure_rule = schema.get("allOf", [{}])[0]
    if schema["properties"]["maximumModelTokens"]["maximum"] != 1_000_000 \
            or closure_rule.get("then", {}).get("properties", {}).get(
                "closedAt", {}).get("type") != "string" \
            or closure_rule.get("else", {}).get("properties", {}).get(
                "closedAt", {}).get("const", "missing") is not None \
            or template.get("status") != "PROPOSED" or template.get("cases") != [] \
            or template.get("maximumRuns") != 0:
        raise VerificationError("NON_EXECUTABLE_AUTHORIZATION_TEMPLATE_INVALID")
    open_v46 = []
    for path in (repository / "plans/live-canary-authorizations").glob("*.json"):
        if V46 not in path.read_text(encoding="utf-8"):
            continue
        document = load_json(path)
        if not isinstance(document, dict):
            continue
        bound = document.get("profileId") == V46 or V46 in document.get("profiles", [])
        if bound and document.get("status") == "OPEN":
            open_v46.append(path.name)
    if open_v46:
        raise VerificationError(f"OPEN_V46_AUTHORIZATION_FORBIDDEN:{open_v46}")

    provider = report["externalProvider"]
    if provider != {"apiKeyReads": 0, "attempts": 0, "costMicrosCny": 0, "reservations": 0}:
        raise VerificationError("EXTERNAL_PROVIDER_ACCOUNTING_NONZERO")
    lowered = report_path.read_text(encoding="utf-8").lower()
    if any(forbidden in lowered for forbidden in (
        "dashscope_api_key", "image/png", "rootdocument", "chain-of-thought", "prompt",
    )):
        raise VerificationError("P0_REPORT_PAYLOAD_SCAN_FAILED")

    migration16 = (repository / "renderweave-app/src/main/resources/db/migration/"
                   "V016__twelve_call_provider_attempt_ordinals.sql").read_text(encoding="utf-8")
    migration17 = (repository / "renderweave-app/src/main/resources/db/migration/"
                   "V017__profile_certification_events.sql").read_text(encoding="utf-8")
    if migration16.count("BETWEEN 0 AND 11") != 2:
        raise VerificationError("TWELVE_CALL_MIGRATION_INVALID")
    if "BEFORE UPDATE OR DELETE" not in migration17 \
            or "acceptance_threshold INTEGER" not in migration17 \
            or "PROFILE_CERTIFICATION_EVENTS_ARE_APPEND_ONLY" not in migration17:
        raise VerificationError("CERTIFICATION_APPEND_ONLY_MIGRATION_INVALID")

    return {
        "summaryVersion": "renderweave-image-only-certification-p0-independent/1.0",
        "result": "PASS",
        "assurance": "A2_STRICT_INPUT_REPLAY",
        "reportIdentity": expected_report_identity,
        "profileSha256": V46_SHA,
        "manifestIdentity": expected_manifest,
        "evaluatorIdentity": expected_evaluator,
        "caseCount": 60,
        "holdoutCount": 20,
        "metricCount": 58,
        "openAuthorizationCount": 0,
        "providerAttempts": 0,
        "providerReservations": 0,
        "providerCostMicrosCny": 0,
        "apiKeyReads": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repository = args.repository.resolve()
    summary = verify(args.report.resolve(), repository)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(summary, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        handle.write("\n")
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
