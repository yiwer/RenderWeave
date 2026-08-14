#!/usr/bin/env python3
"""Strict independent readers for repository-owned offline quality resources."""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
from typing import Any, NamedTuple

from offline_json_contract import (
    JAVA_INT_MAX,
    exact_object,
    payload_safe,
    raw_payload_safe,
    same_json_value,
    strict_nonnegative_int,
    strict_positive_int,
)
import verify_layered_evaluation as layered


PROTOCOL_VERSION = "renderweave-offline-quality-evaluation-protocol/1.0"
CATALOG_VERSION = "renderweave-challenger-capability-catalog/1.0"
CAPABILITY_VERSION = "renderweave-challenger-capability/1.0"
SUCCESSOR_SPEC_SHA256 = "4632b609d4ce5726b0671e8a56fc6674e182f22152231b727622662e14b50a0e"
FINAL_AUTHORITY = "renderweave-visual-stage-corpus/1.0"
CORPUS_VERSION = "renderweave-visual-stage-corpus/2.0"

PROTOCOL_FIELDS = frozenset({
    "protocolVersion", "corpusVersion", "corpusIdentity", "shadowDiagnostic",
    "certificationEligible", "finalAuthorityCorpusVersion", "r3ProbeCaseIds",
    "r5ProbeCaseIds", "thresholds", "structuralMetrics", "downstreamMetrics",
    "winnerTieBreak", "resourceCeilings", "isolationPolicy",
})
THRESHOLD_FIELDS = frozenset({
    "minimumStructuralImprovementBps", "maximumNonRegressionBps",
    "minimumDownstreamMetricsImproved", "maximumCriticalHallucinationIncrease",
})
RESOURCE_FIELDS = frozenset({
    "maximumStartupMillis", "maximumCaseP95Millis", "maximumPeakRamMiB",
    "maximumDiskMiB", "maximumGpuVramMiB",
})
ISOLATION_FIELDS = frozenset({
    "onlySoleDevWinnerMayOpenR2Holdout", "r3R5GoldIsolatedFromR2Selection",
    "resultBasedRetuningForbidden",
})
WINNER_TIE_BREAK = [
    "STRUCTURAL_MARGIN_DESC", "DOWNSTREAM_MARGIN_DESC", "CRITICAL_HALLUCINATIONS_ASC",
    "FAILURE_RATE_ASC", "P95_LATENCY_ASC", "PEAK_RAM_ASC", "CONFIGURATION_ID_ASC",
]

CATALOG_FIELDS = frozenset({
    "catalogVersion", "successorSpecSha256", "optionalThirdChallenger",
    "maximumResourceEnvelope", "challengers",
})
CAPABILITY_FIELDS = frozenset({
    "challengerId", "priority", "role", "adapterKind", "backend", "packagePins",
    "weightPins", "codeLicense", "weightLicense", "preprocessingIdentity",
    "postprocessingIdentity", "resourceEnvelope", "windowsDeployment", "deploymentForm",
    "runtimeNetworkPolicy", "runtimeDownloadAllowed", "provenanceReferences",
    "admissionDisposition", "missingAdmissionDimensions", "executable",
})
LICENSE_FIELDS = frozenset({"spdxExpression", "evidenceReference", "decision", "reasonCode"})
PIN_FIELDS = frozenset({
    "name", "version", "sha256", "sourceReference", "immutableRevision",
    "upstreamObjectIdentity", "pinDisposition",
})


class ResourceContractError(ValueError):
    pass


class VerifiedProtocol(NamedTuple):
    document: dict[str, Any]
    identity: str
    raw: bytes
    cases_by_id: dict[str, dict[str, Any]]


class VerifiedCatalog(NamedTuple):
    document: dict[str, Any]
    identity: str
    raw: bytes


def fail(code: str) -> None:
    raise ResourceContractError(code)


def require(condition: bool, code: str) -> None:
    if not condition:
        fail(code)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("OFFLINE_RESOURCE_DUPLICATE_MEMBER")
        result[key] = value
    return result


def strict_json(raw: bytes) -> Any:
    try:
        text = raw.decode("utf-8", errors="strict")
        value, end = json.JSONDecoder(
            object_pairs_hook=unique_object,
            parse_constant=lambda _value: fail("OFFLINE_RESOURCE_JSON_INVALID"),
        ).raw_decode(text)
        require(not text[end:].strip(), "OFFLINE_RESOURCE_TRAILING_JSON")
        return value
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ResourceContractError("OFFLINE_RESOURCE_JSON_INVALID") from error


def normalized_bytes(raw: bytes) -> bytes:
    require(b"\r" not in raw.replace(b"\r\n", b""),
            "OFFLINE_RESOURCE_LINE_ENDING_INVALID")
    return raw.replace(b"\r\n", b"\n")


def read_document(path: pathlib.Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    require(0 < len(raw) <= 4 * 1024 * 1024, "OFFLINE_RESOURCE_BYTES_INVALID")
    require(raw_payload_safe(raw), "OFFLINE_RESOURCE_PAYLOAD_FORBIDDEN")
    value = strict_json(raw)
    require(type(value) is dict, "OFFLINE_RESOURCE_DOCUMENT_INVALID")
    require(payload_safe(value), "OFFLINE_RESOURCE_DECODED_PAYLOAD_FORBIDDEN")
    return value, raw


def resource_identity(version: str, raw: bytes) -> str:
    return f"{version}:{hashlib.sha256(normalized_bytes(raw)).hexdigest()}"


def validate_protocol_document(
    document: Any,
    repository: pathlib.Path,
) -> dict[str, dict[str, Any]]:
    require(exact_object(document, PROTOCOL_FIELDS), "OFFLINE_PROTOCOL_SCHEMA_INVALID")
    lock = layered.verify_corpus_lock(repository)
    cases = lock["cases"]
    cases_by_id = {str(item["caseId"]): item for item in cases}
    require(len(cases) == 60 and len(cases_by_id) == 60,
            "OFFLINE_PROTOCOL_CORPUS_INVALID")
    require(document["protocolVersion"] == PROTOCOL_VERSION
            and document["corpusVersion"] == CORPUS_VERSION
            and document["corpusIdentity"] == lock["corpusIdentity"],
            "OFFLINE_PROTOCOL_AUTHORITY_INVALID")
    require(type(document["shadowDiagnostic"]) is bool
            and document["shadowDiagnostic"] is True
            and type(document["certificationEligible"]) is bool
            and document["certificationEligible"] is False
            and document["finalAuthorityCorpusVersion"] == FINAL_AUTHORITY,
            "OFFLINE_PROTOCOL_CORPUS_AUTHORITY_INVALID")

    assignments: list[list[str]] = []
    for field in ("r3ProbeCaseIds", "r5ProbeCaseIds"):
        values = document[field]
        require(type(values) is list and len(values) == 4
                and all(type(value) is str for value in values)
                and len(set(values)) == 4
                and all(value in cases_by_id for value in values),
                "OFFLINE_PROTOCOL_PROBE_ASSIGNMENT_INVALID")
        partitions = [cases_by_id[value]["partition"] for value in values]
        require(partitions.count("DEV") == 3 and partitions.count("HOLDOUT") == 1,
                "OFFLINE_PROTOCOL_PROBE_PARTITION_INVALID")
        assignments.append(values)
    require(set(assignments[0]).isdisjoint(assignments[1]),
            "OFFLINE_PROTOCOL_PROBE_ISOLATION_INVALID")

    thresholds = document["thresholds"]
    expected_thresholds = {
        "minimumStructuralImprovementBps": 500,
        "maximumNonRegressionBps": 100,
        "minimumDownstreamMetricsImproved": 1,
        "maximumCriticalHallucinationIncrease": 0,
    }
    require(exact_object(thresholds, THRESHOLD_FIELDS)
            and all(strict_nonnegative_int(value, JAVA_INT_MAX) for value in thresholds.values())
            and same_json_value(thresholds, expected_thresholds),
            "OFFLINE_PROTOCOL_THRESHOLD_INVALID")
    structural = document["structuralMetrics"]
    downstream = document["downstreamMetrics"]
    require(type(structural) is list and structural
            and all(type(value) is str for value in structural)
            and "LAYOUT_RECALL_BPS" in structural
            and type(downstream) is list and downstream
            and all(type(value) is str for value in downstream)
            and "CANDIDATE_TOPOLOGY_SIMILARITY_BPS" in downstream
            and same_json_value(document["winnerTieBreak"], WINNER_TIE_BREAK),
            "OFFLINE_PROTOCOL_SCORING_INVALID")
    ceilings = document["resourceCeilings"]
    require(exact_object(ceilings, RESOURCE_FIELDS)
            and all(strict_positive_int(ceilings[field])
                    for field in RESOURCE_FIELDS - {"maximumGpuVramMiB"})
            and strict_nonnegative_int(ceilings["maximumGpuVramMiB"]),
            "OFFLINE_PROTOCOL_RESOURCE_INVALID")
    isolation = document["isolationPolicy"]
    require(exact_object(isolation, ISOLATION_FIELDS)
            and all(type(value) is bool and value is True for value in isolation.values()),
            "OFFLINE_PROTOCOL_ISOLATION_INVALID")
    return cases_by_id


def load_protocol(repository: pathlib.Path) -> VerifiedProtocol:
    path = repository / (
        "renderweave-inference/src/main/resources/visual-eval/quality-repair/"
        "offline-evaluation-protocol-v1.json"
    )
    document, raw = read_document(path)
    cases_by_id = validate_protocol_document(document, repository)
    return VerifiedProtocol(document, resource_identity(PROTOCOL_VERSION, raw), raw, cases_by_id)


def framed_hash(values: list[str]) -> str:
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b":")
        digest.update(encoded)
        digest.update(b"\n")
    return digest.hexdigest()


def assignment_identity(protocol: VerifiedProtocol, version: str, case_ids: list[str]) -> str:
    return f"{version}:{framed_hash([protocol.identity, chr(10).join(case_ids)])}"


def require_nonblank(value: Any, code: str) -> str:
    require(type(value) is str and bool(value.strip()), code)
    return value


def validate_resource_envelope(value: Any, maximum: dict[str, Any], *, global_value: bool) -> None:
    require(exact_object(value, RESOURCE_FIELDS)
            and all(strict_nonnegative_int(item) for item in value.values()),
            "OFFLINE_CATALOG_RESOURCE_INVALID")
    if global_value:
        require(same_json_value(value, {
            "maximumStartupMillis": 300_000,
            "maximumCaseP95Millis": 120_000,
            "maximumPeakRamMiB": 12_288,
            "maximumDiskMiB": 15_360,
            "maximumGpuVramMiB": 12_288,
        }), "OFFLINE_CATALOG_GLOBAL_RESOURCE_INVALID")
        return
    require(all(value[field] > 0 for field in RESOURCE_FIELDS - {"maximumGpuVramMiB"})
            and all(value[field] <= maximum[field] for field in RESOURCE_FIELDS),
            "OFFLINE_CATALOG_RESOURCE_INVALID")


def validate_license(value: Any) -> None:
    require(exact_object(value, LICENSE_FIELDS), "OFFLINE_CATALOG_LICENSE_INVALID")
    require_nonblank(value["spdxExpression"], "OFFLINE_CATALOG_LICENSE_INVALID")
    require(type(value["evidenceReference"]) is str
            and value["evidenceReference"].startswith("https://")
            and value["decision"] == "J0_PENDING"
            and type(value["reasonCode"]) is str
            and re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", value["reasonCode"]) is not None,
            "OFFLINE_CATALOG_LICENSE_INVALID")


def validate_pins(value: Any) -> None:
    require(type(value) is list and value, "OFFLINE_CATALOG_PIN_INVALID")
    for pin in value:
        require(exact_object(pin, PIN_FIELDS), "OFFLINE_CATALOG_PIN_INVALID")
        require_nonblank(pin["name"], "OFFLINE_CATALOG_PIN_INVALID")
        require_nonblank(pin["immutableRevision"], "OFFLINE_CATALOG_PIN_INVALID")
        require(type(pin["sourceReference"]) is str
                and pin["sourceReference"].startswith("https://"),
                "OFFLINE_CATALOG_PIN_INVALID")
        disposition = pin["pinDisposition"]
        if disposition == "UPSTREAM_PIN_RECORDED_NOT_STAGED":
            require_nonblank(pin["version"], "OFFLINE_CATALOG_PIN_INVALID")
            require(type(pin["sha256"]) is str
                    and re.fullmatch(r"[0-9a-f]{64}", pin["sha256"]) is not None
                    and (pin["upstreamObjectIdentity"] is None
                         or type(pin["upstreamObjectIdentity"]) is str),
                    "OFFLINE_CATALOG_PIN_INVALID")
        elif disposition == "UPSTREAM_REVISION_RECORDED_SHA256_MISSING":
            require_nonblank(pin["version"], "OFFLINE_CATALOG_PIN_INVALID")
            require(pin["sha256"] is None
                    and type(pin["upstreamObjectIdentity"]) is str
                    and re.fullmatch(r"git-blob-sha1:[0-9a-f]{40}",
                                     pin["upstreamObjectIdentity"]) is not None,
                    "OFFLINE_CATALOG_PIN_INVALID")
        else:
            fail("OFFLINE_CATALOG_PIN_INVALID")


def validate_catalog_document(document: Any) -> None:
    require(exact_object(document, CATALOG_FIELDS), "OFFLINE_CATALOG_SCHEMA_INVALID")
    require(document["catalogVersion"] == CATALOG_VERSION
            and document["successorSpecSha256"] == SUCCESSOR_SPEC_SHA256
            and document["optionalThirdChallenger"] == "NONE",
            "OFFLINE_CATALOG_AUTHORITY_INVALID")
    maximum = document["maximumResourceEnvelope"]
    validate_resource_envelope(maximum, maximum, global_value=True)
    challengers = document["challengers"]
    require(type(challengers) is list and len(challengers) == 2
            and [item.get("challengerId") if type(item) is dict else None for item in challengers]
            == ["pp-structurev3", "tesseract-tsv-hocr"],
            "OFFLINE_CATALOG_CHALLENGER_SET_INVALID")
    for challenger in challengers:
        require(exact_object(challenger, CAPABILITY_FIELDS),
                "OFFLINE_CATALOG_CAPABILITY_SCHEMA_INVALID")
        require(type(challenger["challengerId"]) is str
                and re.fullmatch(r"[a-z][a-z0-9-]{0,127}", challenger["challengerId"])
                is not None
                and strict_positive_int(challenger["priority"], JAVA_INT_MAX),
                "OFFLINE_CATALOG_CAPABILITY_INVALID")
        for field in ("role", "adapterKind", "backend", "preprocessingIdentity",
                      "postprocessingIdentity"):
            require_nonblank(challenger[field], "OFFLINE_CATALOG_CAPABILITY_INVALID")
        require(challenger["runtimeNetworkPolicy"] == "DENY_ALL"
                and type(challenger["runtimeDownloadAllowed"]) is bool
                and challenger["runtimeDownloadAllowed"] is False,
                "OFFLINE_CATALOG_RUNTIME_INVALID")
        validate_resource_envelope(challenger["resourceEnvelope"], maximum, global_value=False)
        validate_license(challenger["codeLicense"])
        validate_license(challenger["weightLicense"])
        require(challenger["codeLicense"]["evidenceReference"]
                != challenger["weightLicense"]["evidenceReference"],
                "OFFLINE_CATALOG_LICENSE_EVIDENCE_INVALID")
        validate_pins(challenger["packagePins"])
        validate_pins(challenger["weightPins"])
        missing = challenger["missingAdmissionDimensions"]
        require(type(missing) is list and missing and len(set(missing)) == len(missing)
                and all(type(value) is str
                        and re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", value) is not None
                        for value in missing)
                and "LICENSE_J1" in missing,
                "OFFLINE_CATALOG_MISSING_DIMENSION_INVALID")
        require(challenger["admissionDisposition"] == "NOT_ADMITTED"
                and type(challenger["executable"]) is bool
                and challenger["executable"] is False,
                "OFFLINE_CATALOG_PREMATURE_ADMISSION")
        require(challenger["windowsDeployment"] == "UNVERIFIED_J0"
                and challenger["deploymentForm"] == "LOCAL_PROCESS_SHADOW_ONLY",
                "OFFLINE_CATALOG_DEPLOYMENT_INVALID")
        provenance = challenger["provenanceReferences"]
        require(type(provenance) is list and len(provenance) >= 2
                and all(type(value) is str and value.startswith("https://")
                        for value in provenance),
                "OFFLINE_CATALOG_PROVENANCE_INVALID")


def load_challenger_catalog(repository: pathlib.Path) -> VerifiedCatalog:
    path = repository / (
        "renderweave-inference/src/main/resources/visual-eval/quality-repair/"
        "challenger-capabilities-v1.json"
    )
    document, raw = read_document(path)
    validate_catalog_document(document)
    return VerifiedCatalog(document, resource_identity(CATALOG_VERSION, raw), raw)


def capability_identity(catalog_identity: str, challenger_id: str) -> str:
    return f"{CAPABILITY_VERSION}:{framed_hash([catalog_identity, challenger_id])}"
