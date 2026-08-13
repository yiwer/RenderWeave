#!/usr/bin/env python3
"""One-shot, zero-Provider materializer for the N7 successor Goal authority epoch."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import sys
import uuid
from typing import Any

import verify_layered_evaluation as layered
import verify_n7_live_admission as admission


MATERIALIZER_VERSION = "renderweave-n7-goal-authority-reanchor-materializer/1.0"


class ReanchorError(Exception):
    pass


def fail(code: str) -> None:
    raise ReanchorError(code)


def require_instant(value: str) -> str:
    if not isinstance(value, str) or not value.endswith("Z"):
        fail("CREATED_AT_INVALID")
    try:
        parsed = dt.datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        fail("CREATED_AT_INVALID")
    if parsed.utcoffset() != dt.timedelta(0):
        fail("CREATED_AT_INVALID")
    return value


def encode(value: dict[str, Any]) -> bytes:
    text = json.dumps(value, ensure_ascii=False, indent=2, separators=(",", ": ")) + "\n"
    layered.scan_payload_free(text, "successor-goal-authority")
    return text.encode("utf-8")


def write_temporary(directory: pathlib.Path, name: str, content: bytes) -> pathlib.Path:
    temporary = directory / f".{name}.{uuid.uuid4().hex}.tmp"
    with temporary.open("xb") as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())
    return temporary


def materialize(
    repository: pathlib.Path,
    goal_directory: pathlib.Path,
    created_at: str,
) -> dict[str, Any]:
    repository = repository.resolve(strict=True)
    goal_directory = goal_directory.resolve(strict=False)
    created_at = require_instant(created_at)
    if goal_directory.is_symlink():
        fail("GOAL_DIRECTORY_SYMLINK_FORBIDDEN")
    targets = {
        "state": goal_directory / "goal-budget.json",
        "guard": goal_directory / "goal-budget.guard.json",
        "lock": goal_directory / "goal-budget.lock",
    }
    present = {name: path.exists() for name, path in targets.items()}
    if all(present.values()):
        fail("AUTHORITY_ALREADY_EXISTS")
    if any(present.values()):
        fail("AUTHORITY_PARTIAL_STATE")

    manifest, manifest_identity = admission.verify_reanchor_manifest(repository)
    authority_epoch = {
        "epochVersion": admission.AUTHORITY_EPOCH_VERSION,
        "epochId": admission.SUCCESSOR_AUTHORITY_EPOCH_ID,
        "kind": "CONSERVATIVE_REANCHOR",
        "predecessorEpochId": manifest["predecessorEpochId"],
        "predecessorDisposition": manifest["predecessorDisposition"],
        "reanchorManifestSha256": manifest_identity,
    }
    state = {
        "stateVersion": admission.SUCCESSOR_GOAL_VERSION,
        "goalId": manifest["goalId"],
        "authorityEpoch": authority_epoch,
        "historicalBaseline": manifest["baseline"],
        "reservations": [],
        "createdAt": created_at,
        "updatedAt": created_at,
    }
    limits = manifest["epochLimits"]
    guard = {
        "guardVersion": admission.SUCCESSOR_GUARD_VERSION,
        "goalId": manifest["goalId"],
        "authorityEpochId": manifest["authorityEpochId"],
        "reanchorManifestSha256": manifest_identity,
        "epochMaximumTokensPerModel": limits["maximumTokensPerModel"],
        "epochMaximumAttemptsPerModel": limits["maximumAttemptsPerModel"],
        "epochMaximumCostMicrosCnyByModel": limits["maximumCostMicrosCnyByModel"],
    }
    state_bytes = encode(state)
    guard_bytes = encode(guard)

    goal_directory.mkdir(parents=True, exist_ok=True)
    temporaries: list[pathlib.Path] = []
    created: list[pathlib.Path] = []
    try:
        temporaries.append(write_temporary(goal_directory, targets["state"].name, state_bytes))
        temporaries.append(write_temporary(goal_directory, targets["guard"].name, guard_bytes))
        with targets["lock"].open("xb") as lock:
            lock.flush()
            os.fsync(lock.fileno())
        created.append(targets["lock"])
        os.replace(temporaries[0], targets["state"])
        created.append(targets["state"])
        os.replace(temporaries[1], targets["guard"])
        created.append(targets["guard"])

        contract, _identity = admission.verify_contract(repository)
        audit = admission.inspect_goal(repository, contract, goal_directory)
        if audit["authority"] != "SUCCESSOR_AUTHORITY_EPOCH" \
                or audit["admission"] != "GOAL_READY":
            fail("MATERIALIZED_AUTHORITY_FAILED_INDEPENDENT_AUDIT")
    except Exception:
        for temporary in temporaries:
            temporary.unlink(missing_ok=True)
        for path in reversed(created):
            path.unlink(missing_ok=True)
        raise

    return {
        "materializerVersion": MATERIALIZER_VERSION,
        "result": "MATERIALIZED",
        "goalId": manifest["goalId"],
        "authorityEpochId": manifest["authorityEpochId"],
        "reanchorManifestSha256": manifest_identity,
        "stateSha256": hashlib.sha256(state_bytes).hexdigest(),
        "guardSha256": hashlib.sha256(guard_bytes).hexdigest(),
        "lifetimeReservations": manifest["baseline"]["totalReservations"],
        "quarantinedChargedReservations":
            manifest["baseline"]["quarantinedChargedReservations"],
        "epochReservations": 0,
        "providerAttemptsCreated": 0,
        "providerReservationsCreated": 0,
        "externalProviderCostMicrosCnyCreated": 0,
        "liveAuthorizationInherited": False,
        "refundCreated": False,
        "payloadStored": False,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--goal-directory", required=True, type=pathlib.Path)
    parser.add_argument("--created-at", default=dt.datetime.now(dt.timezone.utc)
                        .isoformat(timespec="seconds").replace("+00:00", "Z"))
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        summary = materialize(args.repository, args.goal_directory, args.created_at)
        encoded = json.dumps(summary, ensure_ascii=False, sort_keys=True,
                             separators=(",", ":")) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded, encoding="utf-8", newline="\n")
        else:
            sys.stdout.write(encoded)
        return 0
    except (ReanchorError, admission.VerificationError, layered.VerificationError,
            OSError, UnicodeError, ValueError) as failure:
        sys.stderr.write(f"N7 Goal authority reanchor failed: {failure}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
