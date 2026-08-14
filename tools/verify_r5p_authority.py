#!/usr/bin/env python3
"""Independent, payload-safe reconstruction of the R5P successor authority lock."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from typing import Any


AUTHORITY_VERSION = "renderweave-r5p-authority/1.0"
AUTHORITY_SHA256 = "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d"
SPEC_SHA256 = "650ad1632347592d1fc34325983744c02563b43d8a565b9b1cd24e1a805a892a"
BASELINE_REVISION = "57be4d9b249c0aa06a1c0b32abc634c152a97234"
OLD_R5_AUTHORITY_SHA256 = "a6ef7ee0820ea906cb371371d66a8eaef3ba77ac569ae24d6e4935e144ef4475"
OLD_R5_RUNNER_SHA256 = "3f7e03764f5a71d6c796ffc82e1c468d7458ca5338bc3e012166c389a3776178"
AUTHORITY_PATH = pathlib.Path(
    "renderweave-inference/src/main/resources/visual-eval/r5p/"
    "paired-product-view-authority-v1.json"
)
OLD_AUTHORITY_PATH = pathlib.Path(
    "renderweave-inference/src/main/resources/visual-eval/r5/"
    "product-transform-authority-v2.json"
)
OLD_RUNNER_PATH = pathlib.Path(
    "renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/"
    "R5ProductTransformEvaluation.java"
)
SPEC_PATH = pathlib.Path("specs/changes/20260815-r5-paired-product-view-successor.md")
AUTHORITY_FIELDS = frozenset(
    {
        "authorityVersion",
        "approvedSpecPath",
        "approvedSpecSha256",
        "baselineRevision",
        "n704TicketId",
        "n704EvidenceAuthoritySha256",
        "n704AuditSha256",
        "n704Decision",
        "n704AuthorizationId",
        "n704AuthorizationStatus",
        "n705TicketId",
        "n705DependencyStatus",
        "oldR5AuthorityVersion",
        "oldR5AuthoritySha256",
        "oldR5RunnerVersion",
        "oldR5RunnerSourceSha256",
        "oldR5RunnerDisposition",
        "prohibitedIdentityValues",
        "externalProviderUsage",
        "apiKeyReads",
        "terminalCode",
    }
)
PROVIDER_FIELDS = frozenset({"attempts", "reservations", "costMicrosCny"})
PROHIBITED_IDENTITIES = [
    "N7-04",
    "N7-05",
    "n7-04-plus-canary-product-v45-20260814e",
    "renderweave-n7-live-ticket-contract/1.0:"
    "caa98a6831d5a5e8dd263265822c4568d1b26117f0d438c8a90dabdf4f422843",
    "renderweave-visual-evaluation-tree-sha256/2:"
    "cfa3e9708b031f9383195edcd3e4a04a447982a7633d1fe9bc95ec27d2c5c650",
    "renderweave-r5-product-transform-assignment/1.0:"
    "46c8e4c9c28b8628bac6532deeeb1a9ee311dda58b1a76f23a1b1d70abe7b540",
    "renderweave-r5-product-transform-evaluation/1.0:"
    "e25bb4531545a399ee2e83082cc7b3dbceda0cbaa8e2e7965a570e3484925d46",
    "renderweave-r5-product-transform-evidence/1.0:"
    "3041df28a17167c9b9eb322d0f60cf2508b2cff0ac8da344449c544908211528",
    "renderweave-r5-product-transform-authority/1.1:"
    "a6ef7ee0820ea906cb371371d66a8eaef3ba77ac569ae24d6e4935e144ef4475",
    "renderweave-r5-product-transform-runner/1.0",
]


class VerificationError(ValueError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def normalized_lf(value: bytes, code: str) -> bytes:
    if b"\r" in value.replace(b"\r\n", b""):
        fail(code)
    return value.replace(b"\r\n", b"\n")


def read_once(path: pathlib.Path, code: str) -> bytes:
    try:
        return path.read_bytes()
    except (OSError, ValueError):
        fail(code)


def strict_json(raw: bytes, code: str) -> dict[str, Any]:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                fail(code)
            result[key] = value
        return result

    def reject_float(_: str) -> Any:
        fail(code)

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_float=reject_float)
    except (UnicodeDecodeError, json.JSONDecodeError, VerificationError):
        fail(code)
    if type(value) is not dict:
        fail(code)
    return value


def exact_fields(value: dict[str, Any], expected: frozenset[str], code: str) -> None:
    if frozenset(value) != expected:
        fail(code)


def exact(value: Any, expected: Any, code: str) -> None:
    if type(value) is not type(expected) or value != expected:
        fail(code)


def verify(repository: pathlib.Path) -> dict[str, Any]:
    repository = repository.resolve()
    authority_raw = read_once(repository / AUTHORITY_PATH, "R5P_AUTHORITY_RESOURCE_MISSING")
    authority = strict_json(authority_raw, "R5P_AUTHORITY_JSON_INVALID")
    exact_fields(authority, AUTHORITY_FIELDS, "R5P_AUTHORITY_FIELDS_INVALID")

    exact(authority["authorityVersion"], AUTHORITY_VERSION, "R5P_AUTHORITY_DRIFT")
    exact(authority["approvedSpecPath"], SPEC_PATH.as_posix(), "R5P_SPEC_IDENTITY_DRIFT")
    exact(authority["approvedSpecSha256"], SPEC_SHA256, "R5P_SPEC_IDENTITY_DRIFT")
    exact(authority["baselineRevision"], BASELINE_REVISION, "R5P_BASELINE_REVISION_DRIFT")
    n7_expected = {
        "n704TicketId": "N7-04",
        "n704EvidenceAuthoritySha256":
            "e2cb4a0455f712b35618f8239e369e3a92bbd50a5a274d24a6eb39ee6734b78f",
        "n704AuditSha256":
            "e1f550b28e7c57fd4944c3b83297e8c85a167ba147683e4aff655b00f0a59655",
        "n704Decision": "FAIL",
        "n704AuthorizationId": "n7-04-plus-canary-product-v45-20260814e",
        "n704AuthorizationStatus": "CLOSED",
        "n705TicketId": "N7-05",
        "n705DependencyStatus": "PERMANENTLY_BLOCKED",
    }
    if any(type(authority[key]) is not str or authority[key] != value
           for key, value in n7_expected.items()):
        fail("R5P_N7_AUTHORITY_STATE_DRIFT")
    exact(
        authority["oldR5AuthorityVersion"],
        "renderweave-r5-product-transform-authority/1.1",
        "R5P_OLD_R5_AUTHORITY_DRIFT",
    )
    exact(
        authority["oldR5AuthoritySha256"],
        OLD_R5_AUTHORITY_SHA256,
        "R5P_OLD_R5_AUTHORITY_DRIFT",
    )
    runner_expected = {
        "oldR5RunnerVersion": "renderweave-r5-product-transform-runner/1.0",
        "oldR5RunnerSourceSha256": OLD_R5_RUNNER_SHA256,
        "oldR5RunnerDisposition": "R5_PRODUCT_TRANSFORM_ROUTE_CLOSED",
    }
    if any(type(authority[key]) is not str or authority[key] != value
           for key, value in runner_expected.items()):
        fail("R5P_OLD_R5_RUNNER_REOPENED")
    exact(
        authority["prohibitedIdentityValues"],
        PROHIBITED_IDENTITIES,
        "R5P_PROHIBITED_IDENTITY_SET_DRIFT",
    )
    usage = authority["externalProviderUsage"]
    if type(usage) is not dict:
        fail("R5P_PROVIDER_USAGE_INVALID")
    exact_fields(usage, PROVIDER_FIELDS, "R5P_PROVIDER_USAGE_INVALID")
    if any(type(usage[key]) is not int or usage[key] != 0 for key in PROVIDER_FIELDS):
        fail("R5P_PROVIDER_USAGE_NONZERO")
    if type(authority["apiKeyReads"]) is not int or authority["apiKeyReads"] != 0:
        fail("R5P_API_KEY_READ_NONZERO")
    exact(authority["terminalCode"], "R5P_AUTHORITY_LOCKED", "R5P_TERMINAL_DRIFT")

    spec_raw = read_once(repository / SPEC_PATH, "R5P_SPEC_MISSING")
    if sha256(normalized_lf(spec_raw, "R5P_SPEC_IDENTITY_DRIFT")) != SPEC_SHA256:
        fail("R5P_SPEC_IDENTITY_DRIFT")
    old_authority_raw = read_once(
        repository / OLD_AUTHORITY_PATH, "R5P_OLD_R5_AUTHORITY_MISSING"
    )
    if sha256(old_authority_raw) != OLD_R5_AUTHORITY_SHA256:
        fail("R5P_OLD_R5_AUTHORITY_DRIFT")
    old_authority = strict_json(old_authority_raw, "R5P_OLD_R5_AUTHORITY_INVALID")
    if (
        old_authority.get("authorityVersion")
        != "renderweave-r5-product-transform-authority/1.1"
        or old_authority.get("a2Disposition") != "NOT_ESTABLISHED"
        or old_authority.get("disposition") != "R5_PRODUCT_TRANSFORM_NOT_QUALIFIED"
        or old_authority.get("freshJ1Disposition") != "LIVE_J1_REQUEST_NOT_ELIGIBLE"
        or old_authority.get("reportedApiKeyReads") != 0
        or old_authority.get("reportedExternalProviderUsage")
        != {"attempts": 0, "costMicrosCny": 0, "reservations": 0}
    ):
        fail("R5P_OLD_R5_AUTHORITY_DRIFT")
    old_runner_raw = read_once(repository / OLD_RUNNER_PATH, "R5P_OLD_R5_RUNNER_MISSING")
    if sha256(normalized_lf(old_runner_raw, "R5P_OLD_R5_RUNNER_REOPENED")) \
            != OLD_R5_RUNNER_SHA256:
        fail("R5P_OLD_R5_RUNNER_REOPENED")
    if sha256(authority_raw) != AUTHORITY_SHA256:
        fail("R5P_AUTHORITY_RESOURCE_DRIFT")

    return {
        "verifierVersion": "renderweave-r5p-authority-verifier/1.0",
        "result": "PASS",
        "assurance": "A2_INDEPENDENT_READ_ONLY_RECONSTRUCTION",
        "authorityIdentity": f"{AUTHORITY_VERSION}:{AUTHORITY_SHA256}",
        "approvedSpecIdentity": f"spec-sha256:{SPEC_SHA256}",
        "baselineRevision": BASELINE_REVISION,
        "oldR5AuthorityIdentity":
            f"renderweave-r5-product-transform-authority/1.1:{OLD_R5_AUTHORITY_SHA256}",
        "prohibitedIdentityCount": len(PROHIBITED_IDENTITIES),
        "providerAttempts": usage["attempts"],
        "providerReservations": usage["reservations"],
        "providerCostMicrosCny": usage["costMicrosCny"],
        "apiKeyReads": authority["apiKeyReads"],
        "disposition": authority["terminalCode"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    result = verify(args.repository)
    encoded = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(encoded + "\n")
    print("R5P authority: LOCKED; assurance=A2; Provider=0; J1=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
