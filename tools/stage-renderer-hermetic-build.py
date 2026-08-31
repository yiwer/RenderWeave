#!/usr/bin/env python3
"""Stage and independently verify the pinned Renderer offline build closure."""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any, NamedTuple


LOCK_VERSION_V1 = "renderweave-renderer-hermetic-build-lock/1.0"
LOCK_VERSION_V2 = "renderweave-renderer-hermetic-build-lock/2.0"
LOCK_VERSION_V3 = "renderweave-renderer-hermetic-build-lock/3.0"
REPORT_VERSION = "renderweave-renderer-hermetic-build-closure/1.0"
CANDIDATE_V2_ID = "rw-renderer-spike-linux-x86_64-v2-000002"
CANDIDATE_V3_ID = "rw-renderer-spike-linux-x86_64-v2-000003"
PRODUCTION_TEXT_CANDIDATE_ID = "rw-renderer-production-text-linux-x86_64-v2-000001"


class LockGeneration(NamedTuple):
    candidate_id: str
    predecessor_version: str | None
    predecessor_candidate_id: str | None


LOCK_GENERATIONS = {
    LOCK_VERSION_V1: LockGeneration(CANDIDATE_V2_ID, None, None),
    LOCK_VERSION_V2: LockGeneration(CANDIDATE_V3_ID, LOCK_VERSION_V1, CANDIDATE_V2_ID),
    LOCK_VERSION_V3: LockGeneration(
        PRODUCTION_TEXT_CANDIDATE_ID,
        LOCK_VERSION_V2,
        CANDIDATE_V3_ID,
    ),
}
REQUIRED_CATEGORIES = {
    "build-tools",
    "canonical-icc",
    "downstream-policy",
    "freetype",
    "image-codecs",
    "jpeg-output",
    "oci",
    "rust-vendor",
    "shaping-unicode",
    "skia",
    "toolchain-sysroot",
}
SHA256 = re.compile(r"sha256:[0-9a-f]{64}\Z")
IDENTIFIER = re.compile(r"[a-z0-9]+(?:[.-][a-z0-9]+)*\Z")


class ClosureFailure(RuntimeError):
    pass


class Verifier:
    def __init__(self) -> None:
        self.check_count = 0

    def require(self, condition: bool, code: str, detail: object) -> None:
        self.check_count += 1
        if not condition:
            raise ClosureFailure(f"{code}: {detail}")


def url_origin(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ""
    default_port = 443 if parsed.scheme == "https" else 80
    port = "" if parsed.port in {None, default_port} else f":{parsed.port}"
    return f"{parsed.scheme}://{host.lower()}{port}"


class ExactRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, urls: list[str], allowed_redirect_origins: list[str]) -> None:
        super().__init__()
        self.urls = urls
        self.allowed_redirect_origins = set(allowed_redirect_origins)
        self.redirect_count = 0

    def redirect_request(  # type: ignore[override]
        self,
        req: urllib.request.Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> urllib.request.Request | None:
        self.redirect_count += 1
        if self.redirect_count > 8:
            raise ClosureFailure(f"REMOTE_REDIRECT_LIMIT: {newurl}")
        if req.full_url.startswith("https://") and newurl.startswith("http://"):
            raise ClosureFailure(f"REMOTE_REDIRECT_DOWNGRADE: {newurl}")
        if self.redirect_count < len(self.urls):
            allowed = newurl == self.urls[self.redirect_count]
        else:
            allowed = url_origin(newurl) in self.allowed_redirect_origins
        if not allowed:
            raise ClosureFailure(f"REMOTE_REDIRECT_UNBOUND: {newurl}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def duplicate_safe_pairs(
    verifier: Verifier, pairs: list[tuple[str, Any]]
) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        verifier.require(key not in value, "JSON_DUPLICATE_MEMBER", key)
        value[key] = item
    return value


def decode_json(verifier: Verifier, data: bytes, location: object) -> dict[str, Any]:
    verifier.require(not data.startswith(b"\xef\xbb\xbf"), "JSON_BOM", location)
    verifier.require(data.endswith(b"\n"), "JSON_FINAL_LF", location)
    verifier.require(b"\r" not in data, "JSON_LF_ONLY", location)
    try:
        value = json.loads(
            data.decode("utf-8", "strict"),
            object_pairs_hook=lambda pairs: duplicate_safe_pairs(verifier, pairs),
            parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)),
        )
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
        raise ClosureFailure(f"JSON_INVALID: {location}: {error}") from error
    verifier.require(isinstance(value, dict), "JSON_ROOT", location)
    return value


def require_members(
    verifier: Verifier, value: object, expected: set[str], code: str
) -> dict[str, Any]:
    verifier.require(isinstance(value, dict), f"{code}_TYPE", type(value).__name__)
    assert isinstance(value, dict)
    verifier.require(set(value) == expected, code, sorted(value))
    return value


def safe_relative(verifier: Verifier, value: object, code: str) -> PurePosixPath:
    verifier.require(isinstance(value, str) and bool(value), f"{code}_TYPE", value)
    assert isinstance(value, str)
    path = PurePosixPath(value)
    verifier.require("\\" not in value, f"{code}_SEPARATOR", value)
    verifier.require(not path.is_absolute(), f"{code}_ABSOLUTE", value)
    verifier.require(
        all(part not in {"", ".", ".."} for part in path.parts),
        f"{code}_UNSAFE",
        value,
    )
    return path


def resolved_child(verifier: Verifier, root: Path, relative: PurePosixPath) -> Path:
    result = (root / Path(*relative.parts)).resolve()
    verifier.require(result.is_relative_to(root), "PATH_ESCAPE", str(relative))
    return result


def require_http_origin(verifier: Verifier, value: object, code: str) -> str:
    verifier.require(isinstance(value, str), f"{code}_TYPE", value)
    assert isinstance(value, str)
    parsed = urllib.parse.urlsplit(value)
    verifier.require(parsed.scheme in {"http", "https"}, f"{code}_SCHEME", value)
    verifier.require(bool(parsed.hostname), f"{code}_HOST", value)
    verifier.require(
        parsed.username is None and parsed.password is None,
        f"{code}_CREDENTIAL",
        value,
    )
    verifier.require(
        not parsed.path and not parsed.query and not parsed.fragment,
        f"{code}_SHAPE",
        value,
    )
    verifier.require(url_origin(value) == value, f"{code}_CANONICAL", value)
    return value


def require_oci_descriptor(
    verifier: Verifier,
    value: object,
    code: str,
    media_types: set[str],
) -> dict[str, Any]:
    descriptor = require_members(
        verifier, value, {"mediaType", "digest", "size"}, f"{code}_MEMBERS"
    )
    verifier.require(
        descriptor["mediaType"] in media_types,
        f"{code}_MEDIA_TYPE",
        descriptor["mediaType"],
    )
    verifier.require(
        isinstance(descriptor["digest"], str)
        and SHA256.fullmatch(descriptor["digest"]) is not None,
        f"{code}_DIGEST",
        descriptor["digest"],
    )
    verifier.require(
        isinstance(descriptor["size"], int)
        and not isinstance(descriptor["size"], bool)
        and descriptor["size"] > 0,
        f"{code}_SIZE",
        descriptor["size"],
    )
    return descriptor


def require_git_commit_object(
    verifier: Verifier, source: dict[str, Any]
) -> bytes:
    commit_object = require_members(
        verifier,
        source["commitObject"],
        {"encoding", "byteLength", "sha256", "bytes"},
        "GIT_COMMIT_OBJECT_MEMBERS",
    )
    verifier.require(
        commit_object["encoding"] == "base64",
        "GIT_COMMIT_OBJECT_ENCODING",
        commit_object["encoding"],
    )
    verifier.require(
        isinstance(commit_object["byteLength"], int)
        and not isinstance(commit_object["byteLength"], bool)
        and 1 <= commit_object["byteLength"] <= 1024 * 1024,
        "GIT_COMMIT_OBJECT_LENGTH",
        commit_object["byteLength"],
    )
    verifier.require(
        isinstance(commit_object["sha256"], str)
        and SHA256.fullmatch(commit_object["sha256"]) is not None,
        "GIT_COMMIT_OBJECT_DIGEST",
        commit_object["sha256"],
    )
    verifier.require(
        isinstance(commit_object["bytes"], str),
        "GIT_COMMIT_OBJECT_BYTES",
        type(commit_object["bytes"]).__name__,
    )
    try:
        raw = base64.b64decode(commit_object["bytes"], validate=True)
    except (ValueError, base64.binascii.Error) as error:
        raise ClosureFailure(f"GIT_COMMIT_OBJECT_BASE64: {error}") from error
    verifier.require(
        len(raw) == commit_object["byteLength"],
        "GIT_COMMIT_OBJECT_LENGTH",
        len(raw),
    )
    verifier.require(
        digest(raw) == commit_object["sha256"],
        "GIT_COMMIT_OBJECT_DIGEST",
        digest(raw),
    )
    object_hasher = hashlib.sha1(usedforsecurity=False)
    object_hasher.update(b"commit " + str(len(raw)).encode("ascii") + b"\0" + raw)
    verifier.require(
        object_hasher.hexdigest() == source["commit"],
        "GIT_COMMIT_OBJECT_ID",
        object_hasher.hexdigest(),
    )
    first_line = raw.split(b"\n", 1)[0]
    verifier.require(
        first_line == b"tree " + source["tree"].encode("ascii"),
        "GIT_COMMIT_OBJECT_TREE",
        first_line.decode("ascii", "replace"),
    )
    return raw


def load_lock(verifier: Verifier, lock_path: Path) -> tuple[dict[str, Any], bytes]:
    lock_bytes = lock_path.read_bytes()
    decoded = decode_json(verifier, lock_bytes, lock_path)
    decoded_version = decoded.get("lockVersion")
    generation = LOCK_GENERATIONS.get(decoded_version)
    if generation is not None and generation.predecessor_version is not None:
        assert generation.predecessor_candidate_id is not None
        successor = require_members(
            verifier,
            decoded,
            {
                "lockVersion",
                "candidateId",
                "baseLock",
                "inputOverrides",
                "inputAdditions",
            },
            "LOCK_MEMBERS",
        )
        verifier.require(
            successor["candidateId"] == generation.candidate_id,
            "CANDIDATE_ID",
            successor["candidateId"],
        )
        base_descriptor = require_members(
            verifier,
            successor["baseLock"],
            {"path", "sha256", "byteLength"},
            "BASE_LOCK_MEMBERS",
        )
        base_relative = safe_relative(verifier, base_descriptor["path"], "BASE_LOCK_PATH")
        base_path = resolved_child(verifier, lock_path.parent.resolve(), base_relative)
        verifier.require(base_path != lock_path.resolve(), "BASE_LOCK_SELF_REFERENCE", base_path)
        verifier.require(
            isinstance(base_descriptor["sha256"], str)
            and SHA256.fullmatch(base_descriptor["sha256"]) is not None,
            "BASE_LOCK_SHA256",
            base_descriptor["sha256"],
        )
        verifier.require(
            isinstance(base_descriptor["byteLength"], int)
            and not isinstance(base_descriptor["byteLength"], bool)
            and base_descriptor["byteLength"] > 0,
            "BASE_LOCK_BYTE_LENGTH",
            base_descriptor["byteLength"],
        )
        base_bytes = read_exact_file(
            verifier,
            base_path,
            base_descriptor["sha256"],
            base_descriptor["byteLength"],
            "BASE_LOCK",
        )
        base_lock, admitted_base_bytes = load_lock(verifier, base_path)
        verifier.require(base_bytes == admitted_base_bytes, "BASE_LOCK_READ_STABILITY", base_path)
        verifier.require(
            base_lock["lockVersion"] == generation.predecessor_version,
            "BASE_LOCK_VERSION",
            base_lock["lockVersion"],
        )
        verifier.require(
            base_lock["candidateId"] == generation.predecessor_candidate_id,
            "BASE_LOCK_CANDIDATE_ID",
            base_lock["candidateId"],
        )
        verifier.require(
            isinstance(successor["inputOverrides"], list)
            and bool(successor["inputOverrides"]),
            "INPUT_OVERRIDES",
            successor["inputOverrides"],
        )
        verifier.require(
            isinstance(successor["inputAdditions"], list)
            and bool(successor["inputAdditions"]),
            "INPUT_ADDITIONS",
            successor["inputAdditions"],
        )
        effective_inputs = {item["id"]: item for item in base_lock["inputs"]}
        override_ids: set[str] = set()
        for raw in successor["inputOverrides"]:
            verifier.require(isinstance(raw, dict), "INPUT_OVERRIDE_TYPE", raw)
            assert isinstance(raw, dict)
            input_id = raw.get("id")
            verifier.require(isinstance(input_id, str), "INPUT_OVERRIDE_ID", input_id)
            assert isinstance(input_id, str)
            verifier.require(input_id in effective_inputs, "INPUT_OVERRIDE_UNKNOWN", input_id)
            verifier.require(input_id not in override_ids, "INPUT_OVERRIDE_DUPLICATE", input_id)
            override_ids.add(input_id)
            effective_inputs[input_id] = raw
        addition_ids: set[str] = set()
        for raw in successor["inputAdditions"]:
            verifier.require(isinstance(raw, dict), "INPUT_ADDITION_TYPE", raw)
            assert isinstance(raw, dict)
            input_id = raw.get("id")
            verifier.require(isinstance(input_id, str), "INPUT_ADDITION_ID", input_id)
            assert isinstance(input_id, str)
            verifier.require(input_id not in effective_inputs, "INPUT_ADDITION_CONFLICT", input_id)
            verifier.require(input_id not in addition_ids, "INPUT_ADDITION_DUPLICATE", input_id)
            addition_ids.add(input_id)
            effective_inputs[input_id] = raw
        lock = {
            "lockVersion": decoded_version,
            "candidateId": successor["candidateId"],
            "target": base_lock["target"],
            "environment": base_lock["environment"],
            "inputs": list(effective_inputs.values()),
        }
    else:
        lock = require_members(
            verifier,
            decoded,
            {"lockVersion", "candidateId", "target", "environment", "inputs"},
            "LOCK_MEMBERS",
        )
    generation = LOCK_GENERATIONS.get(lock["lockVersion"])
    verifier.require(
        generation is not None,
        "LOCK_VERSION",
        lock["lockVersion"],
    )
    assert generation is not None
    verifier.require(
        lock["candidateId"] == generation.candidate_id,
        "CANDIDATE_ID",
        lock["candidateId"],
    )
    target = require_members(
        verifier,
        lock["target"],
        {"operatingSystem", "architecture", "minimumIsa"},
        "TARGET_MEMBERS",
    )
    verifier.require(
        target
        == {
            "operatingSystem": "linux",
            "architecture": "amd64",
            "minimumIsa": "x86-64-v2",
        },
        "TARGET_IDENTITY",
        target,
    )
    environment = require_members(
        verifier,
        lock["environment"],
        {"sourceDateEpoch", "locale", "timezone", "umask", "pathRemapRoot", "allowlist"},
        "ENVIRONMENT_MEMBERS",
    )
    verifier.require(
        isinstance(environment["sourceDateEpoch"], int)
        and not isinstance(environment["sourceDateEpoch"], bool)
        and environment["sourceDateEpoch"] >= 0,
        "SOURCE_DATE_EPOCH",
        environment["sourceDateEpoch"],
    )
    verifier.require(environment["locale"] == "C.UTF-8", "LOCALE", environment["locale"])
    verifier.require(environment["timezone"] == "UTC", "TIMEZONE", environment["timezone"])
    verifier.require(environment["umask"] == "0022", "UMASK", environment["umask"])
    verifier.require(
        environment["pathRemapRoot"] == "/renderweave/src",
        "PATH_REMAP_ROOT",
        environment["pathRemapRoot"],
    )
    verifier.require(
        isinstance(environment["allowlist"], list)
        and environment["allowlist"] == sorted(set(environment["allowlist"])),
        "ENVIRONMENT_ALLOWLIST",
        environment["allowlist"],
    )
    verifier.require(isinstance(lock["inputs"], list), "INPUTS_TYPE", type(lock["inputs"]).__name__)
    verifier.require(bool(lock["inputs"]), "INPUTS_EMPTY", 0)

    ids: set[str] = set()
    bundle_paths: set[str] = set()
    categories: set[str] = set()
    for raw in lock["inputs"]:
        item = require_members(
            verifier,
            raw,
            {"id", "category", "bundlePath", "sha256", "byteLength", "source"},
            "INPUT_MEMBERS",
        )
        verifier.require(
            isinstance(item["id"], str) and IDENTIFIER.fullmatch(item["id"]) is not None,
            "INPUT_ID",
            item["id"],
        )
        verifier.require(item["id"] not in ids, "INPUT_ID_DUPLICATE", item["id"])
        ids.add(item["id"])
        verifier.require(item["category"] in REQUIRED_CATEGORIES, "INPUT_CATEGORY", item["category"])
        categories.add(item["category"])
        bundle_path = safe_relative(verifier, item["bundlePath"], "BUNDLE_PATH")
        verifier.require(bundle_path.parts[0] == "inputs", "BUNDLE_PATH_ROOT", item["bundlePath"])
        verifier.require(item["bundlePath"] not in bundle_paths, "BUNDLE_PATH_DUPLICATE", item["bundlePath"])
        bundle_paths.add(item["bundlePath"])
        verifier.require(
            isinstance(item["sha256"], str) and SHA256.fullmatch(item["sha256"]) is not None,
            "INPUT_SHA256",
            item["sha256"],
        )
        verifier.require(
            isinstance(item["byteLength"], int)
            and not isinstance(item["byteLength"], bool)
            and item["byteLength"] >= 0,
            "INPUT_BYTE_LENGTH",
            item["byteLength"],
        )
        verifier.require(isinstance(item["source"], dict), "SOURCE_TYPE", item["source"])
        assert isinstance(item["source"], dict)
        source_kind = item["source"].get("kind")
        if source_kind == "repository-file":
            source = require_members(
                verifier, item["source"], {"kind", "path"}, "SOURCE_MEMBERS"
            )
            safe_relative(verifier, source["path"], "SOURCE_PATH")
        elif source_kind == "url-file":
            source_members = {"kind", "urls", "allowedRedirectOrigins"}
            if "cachePath" in item["source"]:
                source_members.add("cachePath")
            source = require_members(
                verifier,
                item["source"],
                source_members,
                "SOURCE_MEMBERS",
            )
            if "cachePath" in source:
                cache_path = safe_relative(verifier, source["cachePath"], "CACHE_PATH")
                verifier.require(
                    cache_path.parts[:3]
                    == ("var", "renderer-hermetic-build-v1", "download-cache"),
                    "CACHE_PATH_ROOT",
                    source["cachePath"],
                )
            verifier.require(
                isinstance(source["urls"], list) and 1 <= len(source["urls"]) <= 8,
                "SOURCE_URLS",
                source["urls"],
            )
            verifier.require(
                len(source["urls"]) == len(set(source["urls"])),
                "SOURCE_URL_DUPLICATE",
                source["urls"],
            )
            for url in source["urls"]:
                verifier.require(isinstance(url, str), "SOURCE_URL_TYPE", url)
                parsed = urllib.parse.urlsplit(url)
                verifier.require(parsed.scheme in {"http", "https"}, "SOURCE_URL_SCHEME", url)
                verifier.require(bool(parsed.hostname), "SOURCE_URL_HOST", url)
                verifier.require(parsed.username is None and parsed.password is None,
                                 "SOURCE_URL_CREDENTIAL", url)
                verifier.require(not parsed.fragment, "SOURCE_URL_FRAGMENT", url)
            verifier.require(
                isinstance(source["allowedRedirectOrigins"], list)
                and source["allowedRedirectOrigins"]
                == sorted(set(source["allowedRedirectOrigins"])),
                "REDIRECT_ORIGINS",
                source["allowedRedirectOrigins"],
            )
            for origin in source["allowedRedirectOrigins"]:
                verifier.require(isinstance(origin, str), "REDIRECT_ORIGIN_TYPE", origin)
                parsed = urllib.parse.urlsplit(origin)
                verifier.require(parsed.scheme in {"http", "https"},
                                 "REDIRECT_ORIGIN_SCHEME", origin)
                verifier.require(bool(parsed.hostname), "REDIRECT_ORIGIN_HOST", origin)
                verifier.require(parsed.username is None and parsed.password is None,
                                 "REDIRECT_ORIGIN_CREDENTIAL", origin)
                verifier.require(
                    not parsed.path and not parsed.query and not parsed.fragment,
                    "REDIRECT_ORIGIN_SHAPE",
                    origin,
                )
                verifier.require(url_origin(origin) == origin,
                                 "REDIRECT_ORIGIN_CANONICAL", origin)
        elif source_kind == "git-archive":
            git_members = {
                "kind", "remote", "commit", "tree", "prefix", "gitVersion",
                "archiveConfig", "commitObject",
            }
            if "cachePath" in item["source"]:
                git_members.add("cachePath")
            source = require_members(
                verifier,
                item["source"],
                git_members,
                "SOURCE_MEMBERS",
            )
            if "cachePath" in source:
                cache_path = safe_relative(verifier, source["cachePath"], "CACHE_PATH")
                verifier.require(
                    cache_path.parts[:3]
                    == ("var", "renderer-hermetic-build-v1", "download-cache"),
                    "CACHE_PATH_ROOT",
                    source["cachePath"],
                )
            verifier.require(
                isinstance(source["remote"], str)
                and bool(source["remote"])
                and not source["remote"].startswith("-")
                and "\n" not in source["remote"]
                and "\r" not in source["remote"]
                and "\0" not in source["remote"],
                "GIT_REMOTE",
                source["remote"],
            )
            verifier.require(
                isinstance(source["commit"], str)
                and re.fullmatch(r"[0-9a-f]{40}", source["commit"]) is not None,
                "GIT_COMMIT",
                source["commit"],
            )
            verifier.require(
                isinstance(source["tree"], str)
                and re.fullmatch(r"[0-9a-f]{40}", source["tree"]) is not None,
                "GIT_TREE",
                source["tree"],
            )
            require_git_commit_object(verifier, source)
            verifier.require(
                isinstance(source["prefix"], str) and source["prefix"].endswith("/"),
                "GIT_ARCHIVE_PREFIX",
                source["prefix"],
            )
            safe_relative(verifier, source["prefix"][:-1], "GIT_ARCHIVE_PREFIX")
            verifier.require(
                isinstance(source["gitVersion"], str)
                and source["gitVersion"].startswith("git version "),
                "GIT_VERSION",
                source["gitVersion"],
            )
            archive_config = require_members(
                verifier,
                source["archiveConfig"],
                {"coreAutocrlf"},
                "GIT_ARCHIVE_CONFIG_MEMBERS",
            )
            verifier.require(
                archive_config["coreAutocrlf"] is True,
                "GIT_ARCHIVE_CORE_AUTOCRLF",
                archive_config["coreAutocrlf"],
            )
        elif source_kind == "repository-tree":
            tree_members = {
                "kind", "path", "treeSha256", "fileCount", "prefix",
                "archiveFormat",
            }
            if "excludePaths" in item["source"]:
                tree_members.add("excludePaths")
            source = require_members(
                verifier,
                item["source"],
                tree_members,
                "SOURCE_MEMBERS",
            )
            safe_relative(verifier, source["path"], "SOURCE_PATH")
            if "excludePaths" in source:
                verifier.require(
                    isinstance(source["excludePaths"], list)
                    and source["excludePaths"] == sorted(set(source["excludePaths"])),
                    "TREE_EXCLUDE_PATHS",
                    source["excludePaths"],
                )
                exclusions = [
                    safe_relative(verifier, path, "TREE_EXCLUDE_PATH")
                    for path in source["excludePaths"]
                ]
                verifier.require(
                    not any(
                        left != right and right.is_relative_to(left)
                        for left in exclusions
                        for right in exclusions
                    ),
                    "TREE_EXCLUDE_OVERLAP",
                    source["excludePaths"],
                )
            verifier.require(
                isinstance(source["treeSha256"], str)
                and SHA256.fullmatch(source["treeSha256"]) is not None,
                "TREE_SHA256",
                source["treeSha256"],
            )
            verifier.require(
                isinstance(source["fileCount"], int)
                and not isinstance(source["fileCount"], bool)
                and source["fileCount"] > 0,
                "TREE_FILE_COUNT",
                source["fileCount"],
            )
            verifier.require(
                isinstance(source["prefix"], str) and source["prefix"].endswith("/"),
                "TREE_ARCHIVE_PREFIX",
                source["prefix"],
            )
            safe_relative(verifier, source["prefix"][:-1], "TREE_ARCHIVE_PREFIX")
            verifier.require(
                source["archiveFormat"] == "python-tar-gnu-normalized/1.0",
                "TREE_ARCHIVE_FORMAT",
                source["archiveFormat"],
            )
        elif source_kind == "oci-image":
            oci_members = {
                "kind",
                "registry",
                "repository",
                "authorization",
                "allowedRedirectOrigins",
                "manifest",
                "config",
                "layers",
                "platform",
                "packageDatabase",
                "archiveFormat",
            }
            if "cachePath" in item["source"]:
                oci_members.add("cachePath")
            source = require_members(
                verifier,
                item["source"],
                oci_members,
                "SOURCE_MEMBERS",
            )
            if "cachePath" in source:
                cache_path = safe_relative(verifier, source["cachePath"], "CACHE_PATH")
                verifier.require(
                    cache_path.parts[:3]
                    == ("var", "renderer-hermetic-build-v1", "download-cache"),
                    "CACHE_PATH_ROOT",
                    source["cachePath"],
                )
            require_http_origin(verifier, source["registry"], "OCI_REGISTRY")
            verifier.require(
                isinstance(source["repository"], str)
                and re.fullmatch(
                    r"[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*",
                    source["repository"],
                )
                is not None,
                "OCI_REPOSITORY",
                source["repository"],
            )
            authorization = source["authorization"]
            verifier.require(isinstance(authorization, dict), "OCI_AUTHORIZATION_TYPE", authorization)
            assert isinstance(authorization, dict)
            if authorization.get("kind") == "none":
                require_members(
                    verifier, authorization, {"kind"}, "OCI_AUTHORIZATION_MEMBERS"
                )
            elif authorization.get("kind") == "anonymous-bearer":
                require_members(
                    verifier,
                    authorization,
                    {"kind", "realm", "service", "scope"},
                    "OCI_AUTHORIZATION_MEMBERS",
                )
                realm = authorization["realm"]
                verifier.require(isinstance(realm, str), "OCI_AUTHORIZATION_REALM_TYPE", realm)
                assert isinstance(realm, str)
                parsed_realm = urllib.parse.urlsplit(realm)
                verifier.require(
                    parsed_realm.scheme == "https" and bool(parsed_realm.hostname),
                    "OCI_AUTHORIZATION_REALM",
                    realm,
                )
                verifier.require(
                    parsed_realm.username is None
                    and parsed_realm.password is None
                    and not parsed_realm.query
                    and not parsed_realm.fragment,
                    "OCI_AUTHORIZATION_REALM_SHAPE",
                    realm,
                )
                for field in ("service", "scope"):
                    verifier.require(
                        isinstance(authorization[field], str)
                        and bool(authorization[field])
                        and "\n" not in authorization[field]
                        and "\r" not in authorization[field],
                        f"OCI_AUTHORIZATION_{field.upper()}",
                        authorization[field],
                    )
            else:
                verifier.require(
                    False, "OCI_AUTHORIZATION_KIND", authorization.get("kind")
                )
            verifier.require(
                isinstance(source["allowedRedirectOrigins"], list)
                and source["allowedRedirectOrigins"]
                == sorted(set(source["allowedRedirectOrigins"])),
                "OCI_REDIRECT_ORIGINS",
                source["allowedRedirectOrigins"],
            )
            for origin in source["allowedRedirectOrigins"]:
                require_http_origin(verifier, origin, "OCI_REDIRECT_ORIGIN")
            manifest = require_oci_descriptor(
                verifier,
                source["manifest"],
                "OCI_MANIFEST",
                {"application/vnd.oci.image.manifest.v1+json"},
            )
            config = require_oci_descriptor(
                verifier,
                source["config"],
                "OCI_CONFIG",
                {"application/vnd.oci.image.config.v1+json"},
            )
            verifier.require(
                isinstance(source["layers"], list) and bool(source["layers"]),
                "OCI_LAYERS",
                source["layers"],
            )
            layers = [
                require_oci_descriptor(
                    verifier,
                    layer,
                    "OCI_LAYER",
                    {
                        "application/vnd.oci.image.layer.v1.tar+gzip",
                        "application/vnd.docker.image.rootfs.diff.tar.gzip",
                    },
                )
                for layer in source["layers"]
            ]
            descriptor_digests = [
                manifest["digest"],
                config["digest"],
                *(layer["digest"] for layer in layers),
            ]
            verifier.require(
                len(descriptor_digests) == len(set(descriptor_digests)),
                "OCI_DESCRIPTOR_DUPLICATE",
                descriptor_digests,
            )
            platform = require_members(
                verifier,
                source["platform"],
                {"os", "architecture"},
                "OCI_PLATFORM_MEMBERS",
            )
            verifier.require(
                platform == {"os": "linux", "architecture": "amd64"},
                "OCI_PLATFORM",
                platform,
            )
            package_database = require_members(
                verifier,
                source["packageDatabase"],
                {"path", "sha256", "byteLength", "installedPackageCount"},
                "OCI_PACKAGE_DATABASE_MEMBERS",
            )
            package_path = safe_relative(
                verifier, package_database["path"], "OCI_PACKAGE_DATABASE_PATH"
            )
            verifier.require(
                package_path == PurePosixPath("var/lib/dpkg/status"),
                "OCI_PACKAGE_DATABASE_PATH",
                package_database["path"],
            )
            verifier.require(
                isinstance(package_database["sha256"], str)
                and SHA256.fullmatch(package_database["sha256"]) is not None,
                "OCI_PACKAGE_DATABASE_DIGEST",
                package_database["sha256"],
            )
            verifier.require(
                isinstance(package_database["byteLength"], int)
                and not isinstance(package_database["byteLength"], bool)
                and package_database["byteLength"] > 0,
                "OCI_PACKAGE_DATABASE_LENGTH",
                package_database["byteLength"],
            )
            verifier.require(
                isinstance(package_database["installedPackageCount"], int)
                and not isinstance(package_database["installedPackageCount"], bool)
                and package_database["installedPackageCount"] > 0,
                "OCI_PACKAGE_DATABASE_COUNT",
                package_database["installedPackageCount"],
            )
            verifier.require(
                source["archiveFormat"] == "oci-layout-tar-gnu-normalized/1.0",
                "OCI_ARCHIVE_FORMAT",
                source["archiveFormat"],
            )
        else:
            verifier.require(False, "SOURCE_KIND", source_kind)
    verifier.require(categories == REQUIRED_CATEGORIES, "CATEGORY_CLOSURE", sorted(categories))
    return lock, lock_bytes


def verify_exact_file(
    verifier: Verifier, path: Path, expected_sha256: str, expected_length: int, code: str
) -> None:
    verifier.require(path.is_file(), f"{code}_MISSING", path)
    verifier.require(path.stat().st_size == expected_length, f"{code}_LENGTH", path)
    hasher = hashlib.sha256()
    total = 0
    with path.open("rb") as source:
        while chunk := source.read(4 * 1024 * 1024):
            total += len(chunk)
            hasher.update(chunk)
    verifier.require(total == expected_length, f"{code}_LENGTH", path)
    actual_sha256 = "sha256:" + hasher.hexdigest()
    verifier.require(
        actual_sha256 == expected_sha256,
        f"{code}_DIGEST",
        {"path": str(path), "expected": expected_sha256, "actual": actual_sha256},
    )


def read_exact_file(
    verifier: Verifier, path: Path, expected_sha256: str, expected_length: int, code: str
) -> bytes:
    verifier.require(expected_length <= 64 * 1024 * 1024, f"{code}_READ_LIMIT", expected_length)
    verify_exact_file(verifier, path, expected_sha256, expected_length, code)
    return path.read_bytes()


def normalized_archive_path(
    verifier: Verifier, raw_name: str, code: str
) -> PurePosixPath | None:
    verifier.require(
        isinstance(raw_name, str)
        and len(raw_name) <= 16 * 1024
        and "\\" not in raw_name
        and "\0" not in raw_name
        and not any(ord(character) < 32 for character in raw_name),
        f"{code}_PATH_SHAPE",
        raw_name,
    )
    while raw_name.startswith("./"):
        raw_name = raw_name[2:]
    raw_name = raw_name.rstrip("/")
    if raw_name in {"", "."}:
        return None
    path = PurePosixPath(raw_name)
    verifier.require(
        not path.is_absolute()
        and all(part not in {"", ".", ".."} for part in path.parts)
        and re.fullmatch(r"[A-Za-z]:", path.parts[0]) is None,
        f"{code}_PATH_UNSAFE",
        raw_name,
    )
    return path


def archive_link_stays_inside(
    member_path: PurePosixPath,
    link_name: str,
    symbolic: bool,
    allow_absolute: bool,
) -> bool:
    if (
        not link_name
        or "\\" in link_name
        or "\0" in link_name
        or any(ord(character) < 32 for character in link_name)
    ):
        return False
    absolute = PurePosixPath(link_name).is_absolute()
    if absolute and not allow_absolute:
        return False
    parts = [] if absolute else list(member_path.parent.parts if symbolic else ())
    if absolute:
        link_name = link_name.lstrip("/")
    for part in PurePosixPath(link_name).parts:
        if part in {"", "."}:
            continue
        if part == "..":
            if not parts:
                return False
            parts.pop()
        else:
            if re.fullmatch(r"[A-Za-z]:", part) is not None:
                return False
            parts.append(part)
    return bool(parts)


def verify_tar_members(
    verifier: Verifier,
    archive: tarfile.TarFile,
    code: str,
    allow_absolute_links: bool = False,
) -> None:
    paths: set[PurePosixPath] = set()
    symbolic_paths: set[PurePosixPath] = set()
    member_count = 0
    for member in archive:
        member_count += 1
        verifier.require(member_count <= 1_000_000, f"{code}_MEMBER_LIMIT", member_count)
        path = normalized_archive_path(verifier, member.name, f"{code}_MEMBER")
        if path is None:
            continue
        verifier.require(path not in paths, f"{code}_MEMBER_DUPLICATE", str(path))
        paths.add(path)
        verifier.require(
            member.isfile()
            or member.isdir()
            or member.issym()
            or member.islnk(),
            f"{code}_MEMBER_TYPE",
            member.name,
        )
        if member.issym() or member.islnk():
            verifier.require(
                archive_link_stays_inside(
                    path,
                    member.linkname,
                    member.issym(),
                    allow_absolute_links,
                ),
                f"{code}_LINK_UNSAFE",
                {"path": member.name, "target": member.linkname},
            )
        if member.issym():
            symbolic_paths.add(path)
    for path in paths:
        verifier.require(
            not any(parent in symbolic_paths for parent in path.parents),
            f"{code}_SYMLINK_PARENT",
            str(path),
        )


def verify_zip_members(verifier: Verifier, path: Path, code: str) -> None:
    paths: set[PurePosixPath] = set()
    with zipfile.ZipFile(path) as archive:
        verifier.require(
            len(archive.infolist()) <= 1_000_000,
            f"{code}_MEMBER_LIMIT",
            len(archive.infolist()),
        )
        for member in archive.infolist():
            normalized = normalized_archive_path(
                verifier, member.filename, f"{code}_MEMBER"
            )
            if normalized is None:
                continue
            verifier.require(
                normalized not in paths,
                f"{code}_MEMBER_DUPLICATE",
                str(normalized),
            )
            paths.add(normalized)
            unix_mode = member.external_attr >> 16
            verifier.require(
                not stat.S_ISLNK(unix_mode),
                f"{code}_SYMLINK",
                member.filename,
            )


def verify_git_archive_identity(
    verifier: Verifier, path: Path, source: dict[str, Any]
) -> None:
    require_git_commit_object(verifier, source)
    with tarfile.open(path, mode="r:") as archive:
        verifier.require(
            archive.pax_headers.get("comment") == source["commit"],
            "GIT_ARCHIVE_COMMIT",
            archive.pax_headers.get("comment"),
        )
        verify_tar_members(verifier, archive, "GIT_ARCHIVE")


def verify_oci_layer_archive_paths(
    verifier: Verifier, layout_path: Path, source: dict[str, Any]
) -> None:
    with tarfile.open(layout_path, mode="r:") as layout:
        members = {member.name: member for member in layout if member.isfile()}
        for descriptor in source["layers"]:
            name = "blobs/sha256/" + descriptor["digest"].removeprefix("sha256:")
            verifier.require(name in members, "OCI_LAYOUT_LAYER_MISSING", name)
            layer_stream = layout.extractfile(members[name])
            verifier.require(layer_stream is not None, "OCI_LAYOUT_LAYER_READ", name)
            assert layer_stream is not None
            with tarfile.open(fileobj=layer_stream, mode="r|*") as layer:
                verify_tar_members(
                    verifier,
                    layer,
                    "OCI_LAYER_ARCHIVE",
                    allow_absolute_links=True,
                )


def verify_archive_path_safety(
    verifier: Verifier,
    item: dict[str, Any],
    path: Path,
) -> None:
    bundle_path = item["bundlePath"].lower()
    if bundle_path.endswith((".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz")):
        with tarfile.open(path, mode="r:*") as archive:
            verify_tar_members(verifier, archive, "ARCHIVE")
    elif bundle_path.endswith(".zip"):
        verify_zip_members(verifier, path, "ARCHIVE")
    if item["source"]["kind"] == "git-archive":
        verify_git_archive_identity(verifier, path, item["source"])
    elif item["source"]["kind"] == "oci-image":
        verify_oci_layer_archive_paths(verifier, path, item["source"])


def download_exact_file(
    verifier: Verifier,
    urls: list[str],
    allowed_redirect_origins: list[str],
    destination: Path,
    expected_sha256: str,
    expected_length: int,
) -> None:
    redirect_handler = ExactRedirectHandler(urls, allowed_redirect_origins)
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        redirect_handler,
    )
    request = urllib.request.Request(
        urls[0],
        headers={
            "Accept": "application/octet-stream,*/*;q=0.1",
            "Accept-Encoding": "identity",
            "User-Agent": "renderweave-hermetic-stager/1.0",
        },
        method="GET",
    )
    hasher = hashlib.sha256()
    total = 0
    with opener.open(request, timeout=600) as response, destination.open("xb") as output:
        verifier.require(
            redirect_handler.redirect_count >= len(urls) - 1,
            "REMOTE_REDIRECT_CHAIN",
            redirect_handler.redirect_count,
        )
        if redirect_handler.redirect_count == len(urls) - 1:
            verifier.require(response.geturl() == urls[-1],
                             "REMOTE_FINAL_URL", response.geturl())
        else:
            verifier.require(
                url_origin(response.geturl()) in set(allowed_redirect_origins),
                "REMOTE_FINAL_ORIGIN",
                response.geturl(),
            )
        content_length = response.headers.get("Content-Length")
        if content_length is not None:
            verifier.require(
                content_length.isdecimal() and int(content_length) == expected_length,
                "REMOTE_CONTENT_LENGTH",
                content_length,
            )
        while True:
            chunk = response.read(min(1024 * 1024, expected_length - total + 1))
            if not chunk:
                break
            total += len(chunk)
            verifier.require(total <= expected_length, "REMOTE_LENGTH_OVERFLOW", total)
            hasher.update(chunk)
            output.write(chunk)
    verifier.require(total == expected_length, "REMOTE_LENGTH", total)
    verifier.require(
        "sha256:" + hasher.hexdigest() == expected_sha256,
        "REMOTE_DIGEST",
        urls[-1],
    )


def stage_url_file(
    verifier: Verifier,
    repo: Path,
    source: dict[str, Any],
    destination: Path,
    expected_sha256: str,
    expected_length: int,
) -> None:
    cache_path: Path | None = None
    if "cachePath" in source:
        cache_relative = safe_relative(verifier, source["cachePath"], "CACHE_PATH")
        cache_path = resolved_child(verifier, repo, cache_relative)
    if cache_path is not None and cache_path.exists():
        verify_exact_file(
            verifier, cache_path, expected_sha256, expected_length, "CACHE_INPUT"
        )
        with cache_path.open("rb") as input_file, destination.open("xb") as output_file:
            shutil.copyfileobj(input_file, output_file, length=4 * 1024 * 1024)
        return
    download_exact_file(
        verifier,
        source["urls"],
        source["allowedRedirectOrigins"],
        destination,
        expected_sha256,
        expected_length,
    )
    if cache_path is not None:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        temporary_cache = cache_path.with_name(cache_path.name + ".partial")
        verifier.require(not temporary_cache.exists(), "CACHE_PARTIAL_EXISTS", temporary_cache)
        try:
            with destination.open("rb") as input_file, temporary_cache.open("xb") as output_file:
                shutil.copyfileobj(input_file, output_file, length=4 * 1024 * 1024)
            os.replace(temporary_cache, cache_path)
        finally:
            if temporary_cache.is_file():
                temporary_cache.unlink()


def decode_unframed_json(
    verifier: Verifier, data: bytes, location: object
) -> dict[str, Any]:
    verifier.require(not data.startswith(b"\xef\xbb\xbf"), "JSON_BOM", location)
    try:
        value = json.loads(
            data.decode("utf-8", "strict"),
            object_pairs_hook=lambda pairs: duplicate_safe_pairs(verifier, pairs),
            parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)),
        )
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
        raise ClosureFailure(f"JSON_INVALID: {location}: {error}") from error
    verifier.require(isinstance(value, dict), "JSON_ROOT", location)
    return value


def anonymous_oci_bearer_token(
    verifier: Verifier, authorization: dict[str, Any]
) -> str | None:
    if authorization["kind"] == "none":
        return None
    token_url = authorization["realm"] + "?" + urllib.parse.urlencode(
        {
            "service": authorization["service"],
            "scope": authorization["scope"],
        }
    )
    redirect_handler = ExactRedirectHandler([token_url], [])
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), redirect_handler
    )
    request = urllib.request.Request(
        token_url,
        headers={
            "Accept": "application/json",
            "Accept-Encoding": "identity",
            "User-Agent": "renderweave-hermetic-stager/1.0",
        },
        method="GET",
    )
    with opener.open(request, timeout=60) as response:
        verifier.require(response.geturl() == token_url, "OCI_TOKEN_FINAL_URL", response.geturl())
        token_document = decode_unframed_json(
            verifier, response.read(1024 * 1024 + 1), "OCI_TOKEN_RESPONSE"
        )
    verifier.require(
        "token" in token_document or "access_token" in token_document,
        "OCI_TOKEN_MISSING",
        sorted(token_document),
    )
    token = token_document.get("token", token_document.get("access_token"))
    verifier.require(
        isinstance(token, str)
        and bool(token)
        and len(token) <= 64 * 1024
        and "\n" not in token
        and "\r" not in token,
        "OCI_TOKEN_INVALID",
        type(token).__name__,
    )
    assert isinstance(token, str)
    return token


def download_oci_descriptor(
    verifier: Verifier,
    source: dict[str, Any],
    route: str,
    descriptor: dict[str, Any],
    destination: Path,
    bearer_token: str | None,
) -> None:
    url = (
        source["registry"]
        + "/v2/"
        + source["repository"]
        + f"/{route}/"
        + descriptor["digest"]
    )
    redirect_handler = ExactRedirectHandler(
        [url], source["allowedRedirectOrigins"]
    )
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), redirect_handler
    )
    headers = {
        "Accept": descriptor["mediaType"],
        "Accept-Encoding": "identity",
        "User-Agent": "renderweave-hermetic-stager/1.0",
    }
    if bearer_token is not None:
        headers["Authorization"] = "Bearer " + bearer_token
    request = urllib.request.Request(url, headers=headers, method="GET")
    hasher = hashlib.sha256()
    total = 0
    with opener.open(request, timeout=600) as response, destination.open("xb") as output:
        if redirect_handler.redirect_count == 0:
            verifier.require(response.geturl() == url, "OCI_FINAL_URL", response.geturl())
        else:
            verifier.require(
                url_origin(response.geturl())
                in set(source["allowedRedirectOrigins"]),
                "OCI_FINAL_ORIGIN",
                response.geturl(),
            )
        content_length = response.headers.get("Content-Length")
        if content_length is not None:
            verifier.require(
                content_length.isdecimal()
                and int(content_length) == descriptor["size"],
                "OCI_CONTENT_LENGTH",
                content_length,
            )
        while True:
            chunk = response.read(
                min(1024 * 1024, descriptor["size"] - total + 1)
            )
            if not chunk:
                break
            total += len(chunk)
            verifier.require(total <= descriptor["size"], "OCI_LENGTH_OVERFLOW", total)
            hasher.update(chunk)
            output.write(chunk)
    verifier.require(total == descriptor["size"], "OCI_LENGTH", total)
    verifier.require(
        "sha256:" + hasher.hexdigest() == descriptor["digest"],
        "OCI_DIGEST",
        descriptor["digest"],
    )


def canonical_json_bytes(value: object) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def add_normalized_tar_bytes(
    archive: tarfile.TarFile, name: str, data: bytes
) -> None:
    member = tarfile.TarInfo(name)
    member.size = len(data)
    member.mode = 0o644
    member.mtime = 0
    member.uid = 0
    member.gid = 0
    member.uname = ""
    member.gname = ""
    archive.addfile(member, io.BytesIO(data))


def add_normalized_tar_file(
    archive: tarfile.TarFile, name: str, path: Path, size: int
) -> None:
    member = tarfile.TarInfo(name)
    member.size = size
    member.mode = 0o644
    member.mtime = 0
    member.uid = 0
    member.gid = 0
    member.uname = ""
    member.gname = ""
    with path.open("rb") as source:
        archive.addfile(member, source)


def normalized_layer_member_name(
    verifier: Verifier, raw_name: str
) -> PurePosixPath:
    verifier.require("\\" not in raw_name and "\0" not in raw_name, "OCI_LAYER_PATH", raw_name)
    while raw_name.startswith("./"):
        raw_name = raw_name[2:]
    verifier.require(bool(raw_name), "OCI_LAYER_PATH_EMPTY", raw_name)
    path = PurePosixPath(raw_name)
    verifier.require(
        not path.is_absolute()
        and all(part not in {"", ".", ".."} for part in path.parts),
        "OCI_LAYER_PATH_UNSAFE",
        raw_name,
    )
    return path


def verify_oci_package_database(
    verifier: Verifier,
    layer_paths: list[Path],
    package_database: dict[str, Any],
) -> None:
    target = PurePosixPath(package_database["path"])
    status_data: bytes | None = None
    whiteout = target.parent / (".wh." + target.name)
    opaque_markers = {
        PurePosixPath(*target.parts[:index]) / ".wh..wh..opq"
        for index in range(1, len(target.parts))
    }
    for layer_path in layer_paths:
        layer_target: tarfile.TarInfo | None = None
        layer_removes_target = False
        with tarfile.open(layer_path, mode="r:*") as layer:
            seen_relevant: set[PurePosixPath] = set()
            for member in layer:
                path = normalized_layer_member_name(verifier, member.name)
                if path == target or path == whiteout or path in opaque_markers:
                    verifier.require(
                        path not in seen_relevant,
                        "OCI_LAYER_RELEVANT_PATH_DUPLICATE",
                        path,
                    )
                    seen_relevant.add(path)
                if path == whiteout or path in opaque_markers:
                    layer_removes_target = True
                elif path == target:
                    verifier.require(member.isfile(), "OCI_PACKAGE_DATABASE_TYPE", member.name)
                    layer_target = member
            if layer_removes_target:
                status_data = None
            if layer_target is not None:
                verifier.require(
                    layer_target.size <= 32 * 1024 * 1024,
                    "OCI_PACKAGE_DATABASE_LIMIT",
                    layer_target.size,
                )
                extracted = layer.extractfile(layer_target)
                verifier.require(extracted is not None, "OCI_PACKAGE_DATABASE_READ", target)
                assert extracted is not None
                status_data = extracted.read(layer_target.size + 1)
                verifier.require(
                    len(status_data) == layer_target.size,
                    "OCI_PACKAGE_DATABASE_LAYER_LENGTH",
                    len(status_data),
                )
    verifier.require(status_data is not None, "OCI_PACKAGE_DATABASE_MISSING", target)
    assert status_data is not None
    verifier.require(
        len(status_data) == package_database["byteLength"],
        "OCI_PACKAGE_DATABASE_LENGTH",
        len(status_data),
    )
    verifier.require(
        digest(status_data) == package_database["sha256"],
        "OCI_PACKAGE_DATABASE_DIGEST",
        digest(status_data),
    )
    verifier.require(b"\r" not in status_data, "OCI_PACKAGE_DATABASE_CR", target)
    try:
        status_text = status_data.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise ClosureFailure(f"OCI_PACKAGE_DATABASE_UTF8: {error}") from error
    installed_count = sum(
        1
        for paragraph in status_text.split("\n\n")
        if "Status: install ok installed" in paragraph.splitlines()
    )
    verifier.require(
        installed_count == package_database["installedPackageCount"],
        "OCI_PACKAGE_DATABASE_COUNT",
        installed_count,
    )


def stage_oci_image(
    verifier: Verifier,
    repo: Path,
    source: dict[str, Any],
    destination: Path,
    work_root: Path,
    expected_sha256: str,
    expected_length: int,
) -> None:
    bearer_token: str | None = None
    token_initialized = False
    manifest_path = work_root / "manifest.json"
    config_path = work_root / "config.json"
    layer_paths = [work_root / f"layer-{index:04d}.tar" for index in range(len(source["layers"]))]
    cache_root: Path | None = None
    if "cachePath" in source:
        cache_root = resolved_child(
            verifier,
            repo,
            safe_relative(verifier, source["cachePath"], "CACHE_PATH"),
        )

    def stage_descriptor(
        route: str,
        descriptor: dict[str, Any],
        path: Path,
        cache_name: str,
    ) -> None:
        nonlocal bearer_token, token_initialized
        cache_path = cache_root / cache_name if cache_root is not None else None
        if cache_path is not None and cache_path.exists():
            verify_exact_file(
                verifier,
                cache_path,
                descriptor["digest"],
                descriptor["size"],
                "OCI_CACHE",
            )
            with cache_path.open("rb") as input_file, path.open("xb") as output_file:
                shutil.copyfileobj(input_file, output_file, length=4 * 1024 * 1024)
            return
        if not token_initialized:
            bearer_token = anonymous_oci_bearer_token(
                verifier, source["authorization"]
            )
            token_initialized = True
        download_oci_descriptor(
            verifier, source, route, descriptor, path, bearer_token
        )
        if cache_path is not None:
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            temporary_cache = cache_path.with_name(cache_path.name + ".partial")
            verifier.require(
                not temporary_cache.exists(), "OCI_CACHE_PARTIAL_EXISTS", temporary_cache
            )
            try:
                with path.open("rb") as input_file, temporary_cache.open("xb") as output_file:
                    shutil.copyfileobj(input_file, output_file, length=4 * 1024 * 1024)
                os.replace(temporary_cache, cache_path)
            finally:
                if temporary_cache.is_file():
                    temporary_cache.unlink()

    stage_descriptor("manifests", source["manifest"], manifest_path, "manifest.json")
    stage_descriptor("blobs", source["config"], config_path, "config.json")
    for index, (descriptor, path) in enumerate(
        zip(source["layers"], layer_paths, strict=True)
    ):
        stage_descriptor("blobs", descriptor, path, f"layer-{index:04d}.tar")

    manifest_document = decode_unframed_json(
        verifier, manifest_path.read_bytes(), "OCI_MANIFEST"
    )
    verifier.require(manifest_document.get("schemaVersion") == 2, "OCI_SCHEMA_VERSION", manifest_document.get("schemaVersion"))
    verifier.require(manifest_document.get("mediaType") == source["manifest"]["mediaType"], "OCI_MANIFEST_MEDIA_TYPE", manifest_document.get("mediaType"))
    verifier.require(manifest_document.get("config") == source["config"], "OCI_MANIFEST_CONFIG", manifest_document.get("config"))
    verifier.require(manifest_document.get("layers") == source["layers"], "OCI_MANIFEST_LAYERS", manifest_document.get("layers"))
    config_document = decode_unframed_json(
        verifier, config_path.read_bytes(), "OCI_CONFIG"
    )
    verifier.require(config_document.get("os") == source["platform"]["os"], "OCI_CONFIG_OS", config_document.get("os"))
    verifier.require(config_document.get("architecture") == source["platform"]["architecture"], "OCI_CONFIG_ARCHITECTURE", config_document.get("architecture"))
    verify_oci_package_database(verifier, layer_paths, source["packageDatabase"])

    layout = canonical_json_bytes({"imageLayoutVersion": "1.0.0"})
    index = canonical_json_bytes(
        {
            "schemaVersion": 2,
            "manifests": [
                {
                    **source["manifest"],
                    "platform": source["platform"],
                }
            ],
        }
    )
    blob_paths = {
        source["manifest"]["digest"]: (
            manifest_path,
            source["manifest"]["size"],
        ),
        source["config"]["digest"]: (config_path, source["config"]["size"]),
        **{
            descriptor["digest"]: (path, descriptor["size"])
            for descriptor, path in zip(source["layers"], layer_paths, strict=True)
        },
    }
    with destination.open("xb") as raw_archive:
        with tarfile.open(fileobj=raw_archive, mode="w", format=tarfile.GNU_FORMAT) as archive:
            add_normalized_tar_bytes(archive, "oci-layout", layout)
            add_normalized_tar_bytes(archive, "index.json", index)
            for descriptor_digest, (path, size) in sorted(blob_paths.items()):
                add_normalized_tar_file(
                    archive,
                    "blobs/sha256/" + descriptor_digest.removeprefix("sha256:"),
                    path,
                    size,
                )
    verify_exact_file(
        verifier, destination, expected_sha256, expected_length, "OCI_ARCHIVE"
    )


def run_git(arguments: list[str], environment: dict[str, str]) -> str:
    completed = subprocess.run(
        ["git", *arguments],
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise ClosureFailure(f"GIT_COMMAND_FAILED: {arguments[0]}: {detail}")
    return completed.stdout.strip()


def stage_git_archive(
    verifier: Verifier,
    source: dict[str, Any],
    destination: Path,
    work_root: Path,
    expected_sha256: str,
    expected_length: int,
) -> None:
    environment = {
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_TERMINAL_PROMPT": "0",
        "HOME": str(work_root),
        "LC_ALL": "C",
        "PATH": os.environ.get("PATH", ""),
        "TZ": "UTC",
    }
    actual_version = run_git(["--version"], environment)
    verifier.require(actual_version == source["gitVersion"], "GIT_VERSION", actual_version)
    repository = work_root / "source.git"
    run_git(["init", "--quiet", "--bare", str(repository)], environment)
    run_git(
        [
            "-c",
            "credential.helper=",
            "-c",
            "core.askPass=",
            "-c",
            "http.followRedirects=false",
            "-c",
            "protocol.file.allow=always",
            "--git-dir",
            str(repository),
            "fetch",
            "--quiet",
            "--no-tags",
            "--depth=1",
            source["remote"],
            source["commit"],
        ],
        environment,
    )
    actual_commit = run_git(
        ["--git-dir", str(repository), "rev-parse", "FETCH_HEAD^{commit}"],
        environment,
    )
    verifier.require(actual_commit == source["commit"], "GIT_COMMIT", actual_commit)
    actual_tree = run_git(
        ["--git-dir", str(repository), "rev-parse", "FETCH_HEAD^{tree}"],
        environment,
    )
    verifier.require(actual_tree == source["tree"], "GIT_TREE", actual_tree)
    with destination.open("xb") as output:
        completed = subprocess.run(
            [
                "git",
                "-c",
                "core.autocrlf=true",
                "--git-dir",
                str(repository),
                "archive",
                "--format=tar",
                f"--prefix={source['prefix']}",
                "FETCH_HEAD",
            ],
            env=environment,
            stdout=output,
            stderr=subprocess.PIPE,
            check=False,
        )
    if completed.returncode != 0:
        raise ClosureFailure(
            "GIT_ARCHIVE_FAILED: "
            + completed.stderr.decode("utf-8", "replace").strip()
        )
    verify_exact_file(
        verifier,
        destination,
        expected_sha256,
        expected_length,
        "GIT_ARCHIVE",
    )


def stage_cached_git_archive(
    verifier: Verifier,
    repo: Path,
    source: dict[str, Any],
    destination: Path,
    work_root: Path,
    expected_sha256: str,
    expected_length: int,
) -> None:
    cache_path: Path | None = None
    if "cachePath" in source:
        cache_path = resolved_child(
            verifier,
            repo,
            safe_relative(verifier, source["cachePath"], "CACHE_PATH"),
        )
    if cache_path is not None and cache_path.exists():
        verify_exact_file(
            verifier, cache_path, expected_sha256, expected_length, "GIT_CACHE"
        )
        verify_git_archive_identity(verifier, cache_path, source)
        with cache_path.open("rb") as input_file, destination.open("xb") as output_file:
            shutil.copyfileobj(input_file, output_file, length=4 * 1024 * 1024)
        return
    stage_git_archive(
        verifier,
        source,
        destination,
        work_root,
        expected_sha256,
        expected_length,
    )
    verify_git_archive_identity(verifier, destination, source)
    if cache_path is not None:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        temporary_cache = cache_path.with_name(cache_path.name + ".partial")
        verifier.require(
            not temporary_cache.exists(), "GIT_CACHE_PARTIAL_EXISTS", temporary_cache
        )
        try:
            with destination.open("rb") as input_file, temporary_cache.open("xb") as output_file:
                shutil.copyfileobj(input_file, output_file, length=4 * 1024 * 1024)
            os.replace(temporary_cache, cache_path)
        finally:
            if temporary_cache.is_file():
                temporary_cache.unlink()


def stage_repository_tree(
    verifier: Verifier,
    repo: Path,
    source: dict[str, Any],
    destination: Path,
    expected_sha256: str,
    expected_length: int,
) -> None:
    relative_root = safe_relative(verifier, source["path"], "SOURCE_PATH")
    root = resolved_child(verifier, repo, relative_root)
    verifier.require(root.is_dir() and not root.is_symlink(), "TREE_ROOT", root)
    exclusions = [PurePosixPath(path) for path in source.get("excludePaths", [])]
    entries = sorted(
        (
            path
            for path in root.rglob("*")
            if not any(
                PurePosixPath(path.relative_to(root).as_posix()).is_relative_to(exclusion)
                for exclusion in exclusions
            )
        ),
        key=lambda path: path.relative_to(root).as_posix(),
    )
    for entry in entries:
        verifier.require(not entry.is_symlink(), "TREE_SYMLINK", entry)
        verifier.require(entry.is_dir() or entry.is_file(), "TREE_SPECIAL_FILE", entry)
    files = [entry for entry in entries if entry.is_file()]
    verifier.require(len(files) == source["fileCount"], "TREE_FILE_COUNT", len(files))

    tree_hasher = hashlib.sha256()
    contents: list[tuple[str, bytes]] = []
    casefold_paths: set[str] = set()
    for path in files:
        relative = path.relative_to(root).as_posix()
        safe_relative(verifier, relative, "TREE_FILE_PATH")
        folded = relative.casefold()
        verifier.require(folded not in casefold_paths, "TREE_CASE_COLLISION", relative)
        casefold_paths.add(folded)
        data = path.read_bytes()
        tree_hasher.update(
            f"{hashlib.sha256(data).hexdigest()}  {relative}\n".encode("utf-8")
        )
        contents.append((relative, data))
    actual_tree = "sha256:" + tree_hasher.hexdigest()
    verifier.require(actual_tree == source["treeSha256"], "TREE_DIGEST", actual_tree)

    with destination.open("xb") as raw_archive:
        with tarfile.open(
            fileobj=raw_archive,
            mode="w",
            format=tarfile.GNU_FORMAT,
        ) as archive:
            for relative, data in contents:
                member = tarfile.TarInfo(source["prefix"] + relative)
                member.size = len(data)
                member.mode = 0o644
                member.mtime = 0
                member.uid = 0
                member.gid = 0
                member.uname = ""
                member.gname = ""
                archive.addfile(member, io.BytesIO(data))
    verify_exact_file(
        verifier,
        destination,
        expected_sha256,
        expected_length,
        "TREE_ARCHIVE",
    )


def remove_owned_work_tree(path: Path, staging_root: Path) -> None:
    resolved = path.resolve()
    work_root = (staging_root / ".work").resolve()
    if not resolved.is_relative_to(work_root) or resolved == work_root:
        raise ClosureFailure(f"WORK_CLEANUP_ESCAPE: {resolved}")

    for directory, child_directories, files in os.walk(resolved, topdown=False):
        for name in files:
            os.chmod(
                Path(directory) / name,
                stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR,
            )
        for name in child_directories:
            os.chmod(
                Path(directory) / name,
                stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR,
            )
    os.chmod(resolved, stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)

    def make_writable_and_retry(function: Any, failing_path: str, _error: BaseException) -> None:
        os.chmod(failing_path, stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
        function(failing_path)

    shutil.rmtree(resolved, onexc=make_writable_and_retry)


def remove_owned_staging_tree(path: Path, bundle: Path) -> None:
    resolved = path.resolve()
    parent = bundle.parent.resolve()
    if (
        resolved.parent != parent
        or not resolved.name.startswith(f".{bundle.name}-")
        or resolved == parent
    ):
        raise ClosureFailure(f"STAGING_CLEANUP_ESCAPE: {resolved}")
    for directory, child_directories, files in os.walk(resolved, topdown=False):
        for name in files:
            os.chmod(Path(directory) / name, stat.S_IRUSR | stat.S_IWUSR)
        for name in child_directories:
            os.chmod(
                Path(directory) / name,
                stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR,
            )
    os.chmod(resolved, stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
    shutil.rmtree(resolved)


def inventory_bytes(lock: dict[str, Any], lock_bytes: bytes) -> bytes:
    inventory = {
        "inventoryVersion": "renderweave-renderer-hermetic-build-inventory/1.0",
        "candidateId": lock["candidateId"],
        "lockSha256": digest(lock_bytes),
        "target": lock["target"],
        "environment": lock["environment"],
        "inputs": [
            {
                "id": item["id"],
                "category": item["category"],
                "bundlePath": item["bundlePath"],
                "sha256": item["sha256"],
                "byteLength": item["byteLength"],
                "source": item["source"],
            }
            for item in sorted(lock["inputs"], key=lambda value: value["id"])
        ],
        "boundary": {
            "buildAttempted": False,
            "certified": False,
            "ready": False,
            "ticket19MayClose": False,
        },
    }
    return (json.dumps(inventory, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def report_for(verifier: Verifier, lock: dict[str, Any], inventory_data: bytes) -> dict[str, Any]:
    return {
        "reportVersion": REPORT_VERSION,
        "status": "STAGED_EXACT_OFFLINE_CLOSURE",
        "candidateId": lock["candidateId"],
        "inputCount": len(lock["inputs"]),
        "inventorySha256": digest(inventory_data),
        "checkCount": verifier.check_count,
        "offlineVerified": True,
        "buildAttempted": False,
        "certified": False,
        "ready": False,
        "ticket19MayClose": False,
    }


def verify_bundle(repo: Path, lock_path: Path, bundle: Path) -> dict[str, Any]:
    del repo  # Verification is intentionally network- and repository-independent.
    verifier = Verifier()
    lock, lock_bytes = load_lock(verifier, lock_path)
    verifier.require(bundle.is_dir(), "BUNDLE_MISSING", bundle)
    expected_inventory = inventory_bytes(lock, lock_bytes)
    inventory_path = bundle / "inventory.json"
    actual_inventory = read_exact_file(
        verifier,
        inventory_path,
        digest(expected_inventory),
        len(expected_inventory),
        "INVENTORY",
    )
    decode_json(verifier, actual_inventory, inventory_path)
    for item in lock["inputs"]:
        relative = safe_relative(verifier, item["bundlePath"], "BUNDLE_PATH")
        path = resolved_child(verifier, bundle, relative)
        verify_exact_file(
            verifier, path, item["sha256"], item["byteLength"], "BUNDLE_INPUT"
        )
        verify_archive_path_safety(verifier, item, path)
    return report_for(verifier, lock, actual_inventory)


def stage_bundle(repo: Path, lock_path: Path, bundle: Path) -> dict[str, Any]:
    verifier = Verifier()
    lock, lock_bytes = load_lock(verifier, lock_path)
    verifier.require(repo.is_dir(), "REPOSITORY_MISSING", repo)
    verifier.require(not bundle.exists(), "BUNDLE_EXISTS", bundle)
    bundle.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{bundle.name}-", dir=bundle.parent)).resolve()
    try:
        for item in lock["inputs"]:
            destination_relative = safe_relative(
                verifier, item["bundlePath"], "BUNDLE_PATH"
            )
            destination = resolved_child(verifier, temporary, destination_relative)
            destination.parent.mkdir(parents=True, exist_ok=True)
            source_kind = item["source"]["kind"]
            if source_kind == "repository-file":
                source_relative = safe_relative(
                    verifier, item["source"]["path"], "SOURCE_PATH"
                )
                source = resolved_child(verifier, repo, source_relative)
                verify_exact_file(
                    verifier,
                    source,
                    item["sha256"],
                    item["byteLength"],
                    "SOURCE_INPUT",
                )
                with source.open("rb") as input_file, destination.open("xb") as output_file:
                    shutil.copyfileobj(input_file, output_file, length=4 * 1024 * 1024)
            elif source_kind == "url-file":
                stage_url_file(
                    verifier,
                    repo,
                    item["source"],
                    destination,
                    item["sha256"],
                    item["byteLength"],
                )
            elif source_kind == "git-archive":
                work_root = temporary / ".work" / item["id"]
                work_root.mkdir(parents=True, exist_ok=False)
                stage_cached_git_archive(
                    verifier,
                    repo,
                    item["source"],
                    destination,
                    work_root,
                    item["sha256"],
                    item["byteLength"],
                )
                remove_owned_work_tree(work_root, temporary)
            elif source_kind == "oci-image":
                work_root = temporary / ".work" / item["id"]
                work_root.mkdir(parents=True, exist_ok=False)
                stage_oci_image(
                    verifier,
                    repo,
                    item["source"],
                    destination,
                    work_root,
                    item["sha256"],
                    item["byteLength"],
                )
                remove_owned_work_tree(work_root, temporary)
            else:
                stage_repository_tree(
                    verifier,
                    repo,
                    item["source"],
                    destination,
                    item["sha256"],
                    item["byteLength"],
                )
        work_parent = temporary / ".work"
        if work_parent.is_dir():
            work_parent.rmdir()
        inventory = inventory_bytes(lock, lock_bytes)
        (temporary / "inventory.json").write_bytes(inventory)
        report = verify_bundle(repo, lock_path, temporary)
        os.replace(temporary, bundle)
    except BaseException:
        if temporary.is_dir() and temporary.parent == bundle.parent.resolve():
            remove_owned_staging_tree(temporary, bundle)
        raise
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("stage", "verify"))
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        repo = args.repo.resolve()
        lock = args.lock.resolve()
        bundle = args.bundle.resolve()
        report = (
            stage_bundle(repo, lock, bundle)
            if args.operation == "stage"
            else verify_bundle(repo, lock, bundle)
        )
        sys.stdout.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        return 0
    except (ClosureFailure, OSError, ValueError) as error:
        print(f"Renderer hermetic closure failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
