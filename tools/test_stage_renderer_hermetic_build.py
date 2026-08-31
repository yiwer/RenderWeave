from __future__ import annotations

import json
import base64
import gzip
import hashlib
import io
import os
import threading
import subprocess
import sys
import tarfile
import tempfile
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
STAGER = ROOT / "tools" / "stage-renderer-hermetic-build.py"
CANDIDATE_ID = "rw-renderer-spike-linux-x86_64-v2-000002"
CANDIDATE_V3_ID = "rw-renderer-spike-linux-x86_64-v2-000003"
PRODUCTION_TEXT_CANDIDATE_ID = "rw-renderer-production-text-linux-x86_64-v2-000001"
REQUIRED_CATEGORIES = [
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
]
ABC_SHA256 = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"


class RendererHermeticBuildStagerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        (self.repo / "source.bin").write_bytes(b"abc")
        self.lock = self.repo / "lock.json"
        self.bundle = self.root / "bundle"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_lock(self, source_overrides: dict[str, dict] | None = None) -> None:
        source_overrides = source_overrides or {}
        lock = {
            "lockVersion": "renderweave-renderer-hermetic-build-lock/1.0",
            "candidateId": CANDIDATE_ID,
            "target": {
                "operatingSystem": "linux",
                "architecture": "amd64",
                "minimumIsa": "x86-64-v2",
            },
            "environment": {
                "sourceDateEpoch": 0,
                "locale": "C.UTF-8",
                "timezone": "UTC",
                "umask": "0022",
                "pathRemapRoot": "/renderweave/src",
                "allowlist": ["HOME", "PATH", "SOURCE_DATE_EPOCH", "TZ"],
            },
            "inputs": [
                {
                    "id": category,
                    "category": category,
                    "bundlePath": f"inputs/{category}/source.bin",
                    "sha256": ABC_SHA256,
                    "byteLength": 3,
                    "source": source_overrides.get(
                        category,
                        {
                            "kind": "repository-file",
                            "path": "source.bin",
                        },
                    ),
                }
                for category in REQUIRED_CATEGORIES
            ],
        }
        self.lock.write_text(
            json.dumps(lock, indent=2) + "\n", encoding="utf-8", newline="\n"
        )

    def run_cli(self, operation: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(STAGER),
                operation,
                "--repo",
                str(self.repo),
                "--lock",
                str(self.lock),
                "--bundle",
                str(self.bundle),
            ],
            cwd=self.repo,
            capture_output=True,
            text=True,
            check=False,
        )

    def read_lock(self) -> dict:
        return json.loads(self.lock.read_text(encoding="utf-8"))

    def replace_input(self, input_id: str, **changes: object) -> None:
        lock = self.read_lock()
        target = next(item for item in lock["inputs"] if item["id"] == input_id)
        target.update(changes)
        self.lock.write_text(
            json.dumps(lock, indent=2) + "\n", encoding="utf-8", newline="\n"
        )

    def write_successor_lock(self) -> dict[str, object]:
        self.write_lock()
        base_lock = self.repo / "base-lock.json"
        base_bytes = self.lock.read_bytes()
        base_lock.write_bytes(base_bytes)
        (self.repo / "replacement.bin").write_bytes(b"v3")
        (self.repo / "minimal-cff.otf").write_bytes(b"cff")

        def repository_input(
            input_id: str,
            category: str,
            bundle_path: str,
            source_path: str,
            payload: bytes,
        ) -> dict[str, object]:
            return {
                "id": input_id,
                "category": category,
                "bundlePath": bundle_path,
                "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
                "byteLength": len(payload),
                "source": {"kind": "repository-file", "path": source_path},
            }

        successor: dict[str, object] = {
            "lockVersion": "renderweave-renderer-hermetic-build-lock/2.0",
            "candidateId": CANDIDATE_V3_ID,
            "baseLock": {
                "path": "base-lock.json",
                "sha256": "sha256:" + hashlib.sha256(base_bytes).hexdigest(),
                "byteLength": len(base_bytes),
            },
            "inputOverrides": [
                repository_input(
                    "skia",
                    "skia",
                    "inputs/skia/replacement.bin",
                    "replacement.bin",
                    b"v3",
                )
            ],
            "inputAdditions": [
                repository_input(
                    "minimal-cff-fixture",
                    "downstream-policy",
                    "inputs/downstream-policy/minimal-cff.otf",
                    "minimal-cff.otf",
                    b"cff",
                )
            ],
        }
        self.lock.write_text(
            json.dumps(successor, indent=2) + "\n", encoding="utf-8", newline="\n"
        )
        return successor

    def test_exact_repository_inputs_stage_and_verify_offline(self) -> None:
        self.write_lock()

        staged = self.run_cli("stage")
        self.assertEqual(staged.returncode, 0, staged.stderr)
        stage_report = json.loads(staged.stdout)
        self.assertEqual(stage_report["status"], "STAGED_EXACT_OFFLINE_CLOSURE")
        self.assertEqual(stage_report["inputCount"], len(REQUIRED_CATEGORIES))

        verified = self.run_cli("verify")
        self.assertEqual(verified.returncode, 0, verified.stderr)
        verify_report = json.loads(verified.stdout)
        self.assertEqual(verify_report, stage_report)
        self.assertEqual(
            (self.bundle / "inputs" / "skia" / "source.bin").read_bytes(), b"abc"
        )
        self.assertTrue((self.bundle / "inventory.json").is_file())

    def test_successor_lock_composes_exact_base_overrides_and_additions(self) -> None:
        self.write_successor_lock()

        staged = self.run_cli("stage")
        self.assertEqual(staged.returncode, 0, staged.stderr)
        report = json.loads(staged.stdout)
        self.assertEqual(report["candidateId"], CANDIDATE_V3_ID)
        self.assertEqual(report["inputCount"], len(REQUIRED_CATEGORIES) + 1)
        self.assertEqual(
            (self.bundle / "inputs" / "skia" / "replacement.bin").read_bytes(),
            b"v3",
        )
        self.assertEqual(
            (
                self.bundle
                / "inputs"
                / "downstream-policy"
                / "minimal-cff.otf"
            ).read_bytes(),
            b"cff",
        )

        verified = self.run_cli("verify")
        self.assertEqual(verified.returncode, 0, verified.stderr)
        self.assertEqual(json.loads(verified.stdout), report)

    def test_nested_successor_composes_the_production_text_closure(self) -> None:
        self.write_successor_lock()
        predecessor_bytes = self.lock.read_bytes()
        predecessor = self.repo / "successor-v2.json"
        predecessor.write_bytes(predecessor_bytes)
        (self.repo / "production.bin").write_bytes(b"production")
        (self.repo / "harness.sh").write_bytes(b"#!/bin/sh\n")
        production_lock = {
            "lockVersion": "renderweave-renderer-hermetic-build-lock/3.0",
            "candidateId": PRODUCTION_TEXT_CANDIDATE_ID,
            "baseLock": {
                "path": predecessor.name,
                "sha256": "sha256:" + hashlib.sha256(predecessor_bytes).hexdigest(),
                "byteLength": len(predecessor_bytes),
            },
            "inputOverrides": [
                {
                    "id": "skia",
                    "category": "skia",
                    "bundlePath": "inputs/skia/production.bin",
                    "sha256": "sha256:" + hashlib.sha256(b"production").hexdigest(),
                    "byteLength": 10,
                    "source": {"kind": "repository-file", "path": "production.bin"},
                }
            ],
            "inputAdditions": [
                {
                    "id": "production-text-harness",
                    "category": "downstream-policy",
                    "bundlePath": "inputs/downstream-policy/harness.sh",
                    "sha256": "sha256:" + hashlib.sha256(b"#!/bin/sh\n").hexdigest(),
                    "byteLength": 10,
                    "source": {"kind": "repository-file", "path": "harness.sh"},
                }
            ],
        }
        self.lock.write_text(
            json.dumps(production_lock, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )

        staged = self.run_cli("stage")
        self.assertEqual(staged.returncode, 0, staged.stderr)
        report = json.loads(staged.stdout)
        self.assertEqual(report["candidateId"], PRODUCTION_TEXT_CANDIDATE_ID)
        self.assertEqual(report["inputCount"], len(REQUIRED_CATEGORIES) + 2)
        self.assertEqual(
            (self.bundle / "inputs" / "skia" / "production.bin").read_bytes(),
            b"production",
        )
        self.assertEqual(
            (
                self.bundle
                / "inputs"
                / "downstream-policy"
                / "harness.sh"
            ).read_bytes(),
            b"#!/bin/sh\n",
        )

        verified = self.run_cli("verify")
        self.assertEqual(verified.returncode, 0, verified.stderr)
        self.assertEqual(json.loads(verified.stdout), report)

    def test_successor_lock_rejects_invalid_composition_authority(self) -> None:
        def mutate_candidate(lock: dict[str, object]) -> None:
            lock["candidateId"] = CANDIDATE_ID

        def mutate_base_digest(lock: dict[str, object]) -> None:
            descriptor = lock["baseLock"]
            assert isinstance(descriptor, dict)
            descriptor["sha256"] = "sha256:" + "0" * 64

        def mutate_base_path(lock: dict[str, object]) -> None:
            descriptor = lock["baseLock"]
            assert isinstance(descriptor, dict)
            descriptor["path"] = "../base-lock.json"

        def mutate_unknown_override(lock: dict[str, object]) -> None:
            overrides = lock["inputOverrides"]
            assert isinstance(overrides, list) and isinstance(overrides[0], dict)
            overrides[0]["id"] = "unknown-input"

        def mutate_duplicate_override(lock: dict[str, object]) -> None:
            overrides = lock["inputOverrides"]
            assert isinstance(overrides, list)
            overrides.append(dict(overrides[0]))

        def mutate_addition_conflict(lock: dict[str, object]) -> None:
            additions = lock["inputAdditions"]
            assert isinstance(additions, list) and isinstance(additions[0], dict)
            additions[0]["id"] = "skia"

        cases = [
            (mutate_candidate, "CANDIDATE_ID"),
            (mutate_base_digest, "BASE_LOCK_DIGEST"),
            (mutate_base_path, "BASE_LOCK_PATH_UNSAFE"),
            (mutate_unknown_override, "INPUT_OVERRIDE_UNKNOWN"),
            (mutate_duplicate_override, "INPUT_OVERRIDE_DUPLICATE"),
            (mutate_addition_conflict, "INPUT_ADDITION_CONFLICT"),
        ]
        for mutate, expected in cases:
            with self.subTest(expected=expected):
                lock = self.write_successor_lock()
                mutate(lock)
                self.lock.write_text(
                    json.dumps(lock, indent=2) + "\n",
                    encoding="utf-8",
                    newline="\n",
                )
                failed = self.run_cli("stage")
                self.assertNotEqual(failed.returncode, 0)
                self.assertIn(expected, failed.stderr)

    def test_verify_rejects_tampered_or_missing_bundle_input(self) -> None:
        self.write_lock()
        staged = self.run_cli("stage")
        self.assertEqual(staged.returncode, 0, staged.stderr)
        target = self.bundle / "inputs" / "skia" / "source.bin"

        target.write_bytes(b"abd")
        tampered = self.run_cli("verify")
        self.assertNotEqual(tampered.returncode, 0)
        self.assertIn("BUNDLE_INPUT_DIGEST", tampered.stderr)

        target.unlink()
        missing = self.run_cli("verify")
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("BUNDLE_INPUT_MISSING", missing.stderr)

    def test_lock_rejects_path_escape_and_duplicate_logical_inputs(self) -> None:
        invalid_changes = [
            ("bundlePath", "../escape.bin", "BUNDLE_PATH_UNSAFE"),
            (
                "source",
                {"kind": "repository-file", "path": "../source.bin"},
                "SOURCE_PATH_UNSAFE",
            ),
        ]
        for field, value, expected in invalid_changes:
            with self.subTest(expected=expected):
                self.write_lock()
                self.replace_input("skia", **{field: value})
                failed = self.run_cli("stage")
                self.assertNotEqual(failed.returncode, 0)
                self.assertIn(expected, failed.stderr)

        for field, expected in [
            ("id", "INPUT_ID_DUPLICATE"),
            ("bundlePath", "BUNDLE_PATH_DUPLICATE"),
        ]:
            with self.subTest(expected=expected):
                self.write_lock()
                lock = self.read_lock()
                lock["inputs"][1][field] = lock["inputs"][0][field]
                self.lock.write_text(
                    json.dumps(lock, indent=2) + "\n",
                    encoding="utf-8",
                    newline="\n",
                )
                failed = self.run_cli("stage")
                self.assertNotEqual(failed.returncode, 0)
                self.assertIn(expected, failed.stderr)

    def test_repository_tree_drift_fails_before_archive_admission(self) -> None:
        vendor = self.repo / "vendor"
        vendor.mkdir()
        (vendor / "a.txt").write_bytes(b"A")
        self.write_lock()
        self.replace_input(
            "rust-vendor",
            sha256="sha256:84ff92691f909a05b224e1c56abb4864f01a53b8e3c854e131e34bc50021d074",
            byteLength=10240,
            source={
                "kind": "repository-tree",
                "path": "vendor",
                "treeSha256": "sha256:794e836a3a3d30cab657f4b45f72d6292ff6d0b9f48f1f4f7765b3e4073e845e",
                "fileCount": 1,
                "prefix": "vendor/",
                "archiveFormat": "python-tar-gnu-normalized/1.0",
            },
        )
        (vendor / "a.txt").write_bytes(b"B")

        failed = self.run_cli("stage")
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn("TREE_DIGEST", failed.stderr)

    def test_verify_needs_neither_source_repository_nor_network(self) -> None:
        self.write_lock()
        staged = self.run_cli("stage")
        self.assertEqual(staged.returncode, 0, staged.stderr)

        verified = subprocess.run(
            [
                sys.executable,
                str(STAGER),
                "verify",
                "--repo",
                str(self.root / "repository-is-offline"),
                "--lock",
                str(self.lock),
                "--bundle",
                str(self.bundle),
            ],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(verified.returncode, 0, verified.stderr)

    def test_archive_member_path_escape_is_rejected(self) -> None:
        unsafe_archive = io.BytesIO()
        with tarfile.open(
            fileobj=unsafe_archive, mode="w", format=tarfile.GNU_FORMAT
        ) as archive:
            add = tarfile.TarInfo("../escape.txt")
            add.size = 1
            archive.addfile(add, io.BytesIO(b"x"))
        archive_path = self.repo / "unsafe.tar"
        archive_path.write_bytes(unsafe_archive.getvalue())
        self.write_lock()
        self.replace_input(
            "skia",
            bundlePath="inputs/skia/unsafe.tar",
            sha256="sha256:" + hashlib.sha256(unsafe_archive.getvalue()).hexdigest(),
            byteLength=len(unsafe_archive.getvalue()),
            source={"kind": "repository-file", "path": "unsafe.tar"},
        )

        failed = self.run_cli("stage")
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn("ARCHIVE_MEMBER_PATH_UNSAFE", failed.stderr)
        self.assertFalse(self.bundle.exists())

    def test_remote_input_is_bound_then_verifies_with_server_offline(self) -> None:
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 - stdlib interface
                if self.path != "/source.bin":
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Length", "3")
                self.end_headers()
                self.wfile.write(b"abc")

            def log_message(self, _format: str, *_args: object) -> None:
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = f"http://127.0.0.1:{server.server_port}/source.bin"
        self.write_lock(
            {
                "skia": {
                    "kind": "url-file",
                    "urls": [url],
                    "allowedRedirectOrigins": [],
                }
            }
        )
        try:
            staged = self.run_cli("stage")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

        self.assertEqual(staged.returncode, 0, staged.stderr)
        verified = self.run_cli("verify")
        self.assertEqual(verified.returncode, 0, verified.stderr)
        self.assertEqual(json.loads(verified.stdout), json.loads(staged.stdout))

    def test_remote_redirect_requires_a_locked_origin(self) -> None:
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 - stdlib interface
                if self.path == "/start":
                    self.send_response(302)
                    self.send_header(
                        "Location",
                        f"http://127.0.0.1:{self.server.server_port}/source.bin",
                    )
                    self.end_headers()
                    return
                if self.path == "/source.bin":
                    self.send_response(200)
                    self.send_header("Content-Length", "3")
                    self.end_headers()
                    self.wfile.write(b"abc")
                    return
                self.send_error(404)

            def log_message(self, _format: str, *_args: object) -> None:
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        origin = f"http://127.0.0.1:{server.server_port}"
        self.write_lock(
            {
                "skia": {
                    "kind": "url-file",
                    "urls": [origin + "/start"],
                    "allowedRedirectOrigins": [origin],
                }
            }
        )
        try:
            staged = self.run_cli("stage")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
        self.assertEqual(staged.returncode, 0, staged.stderr)

    def test_git_commit_and_tree_produce_the_locked_normalized_archive(self) -> None:
        origin = self.root / "origin"
        origin.mkdir()
        env = {
            **dict(os.environ),
            "GIT_AUTHOR_NAME": "RenderWeave Fixture",
            "GIT_AUTHOR_EMAIL": "fixture@renderweave.invalid",
            "GIT_COMMITTER_NAME": "RenderWeave Fixture",
            "GIT_COMMITTER_EMAIL": "fixture@renderweave.invalid",
            "GIT_AUTHOR_DATE": "2001-01-01T00:00:00Z",
            "GIT_COMMITTER_DATE": "2001-01-01T00:00:00Z",
        }
        subprocess.run(["git", "init", "--quiet", str(origin)], check=True, env=env)
        (origin / "source.txt").write_text("exact\n", encoding="utf-8", newline="\n")
        subprocess.run(["git", "-C", str(origin), "add", "source.txt"], check=True, env=env)
        subprocess.run(
            ["git", "-C", str(origin), "commit", "--quiet", "-m", "fixture"],
            check=True,
            env=env,
        )
        commit = subprocess.check_output(
            ["git", "-C", str(origin), "rev-parse", "HEAD"], text=True
        ).strip()
        tree = subprocess.check_output(
            ["git", "-C", str(origin), "rev-parse", "HEAD^{tree}"], text=True
        ).strip()
        commit_object = subprocess.check_output(
            ["git", "-C", str(origin), "cat-file", "commit", commit]
        )
        git_version = subprocess.check_output(["git", "--version"], text=True).strip()
        archive = subprocess.check_output(
            [
                "git",
                "-C",
                str(origin),
                "archive",
                "--format=tar",
                f"--prefix=source-{commit}/",
                commit,
            ]
        )

        self.write_lock()
        self.replace_input(
            "skia",
            sha256="sha256:" + hashlib.sha256(archive).hexdigest(),
            byteLength=len(archive),
            source={
                "kind": "git-archive",
                "remote": str(origin),
                "commit": commit,
                "tree": tree,
                "prefix": f"source-{commit}/",
                "gitVersion": git_version,
                "commitObject": {
                    "encoding": "base64",
                    "byteLength": len(commit_object),
                    "sha256": "sha256:" + hashlib.sha256(commit_object).hexdigest(),
                    "bytes": base64.b64encode(commit_object).decode("ascii"),
                },
                "archiveConfig": {"coreAutocrlf": True},
            },
        )

        staged = self.run_cli("stage")
        self.assertEqual(staged.returncode, 0, staged.stderr)
        self.assertEqual(
            (self.bundle / "inputs" / "skia" / "source.bin").read_bytes(), archive
        )

    def test_cached_git_archive_must_bind_the_locked_commit(self) -> None:
        origin = self.root / "origin-cache"
        origin.mkdir()
        environment = {
            **dict(os.environ),
            "GIT_AUTHOR_NAME": "RenderWeave Fixture",
            "GIT_AUTHOR_EMAIL": "fixture@renderweave.invalid",
            "GIT_COMMITTER_NAME": "RenderWeave Fixture",
            "GIT_COMMITTER_EMAIL": "fixture@renderweave.invalid",
            "GIT_AUTHOR_DATE": "2001-01-01T00:00:00Z",
            "GIT_COMMITTER_DATE": "2001-01-01T00:00:00Z",
        }
        subprocess.run(["git", "init", "--quiet", str(origin)], check=True, env=environment)
        (origin / "source.txt").write_text("exact\n", encoding="utf-8", newline="\n")
        subprocess.run(["git", "-C", str(origin), "add", "source.txt"], check=True, env=environment)
        subprocess.run(
            ["git", "-C", str(origin), "commit", "--quiet", "-m", "fixture"],
            check=True,
            env=environment,
        )
        commit = subprocess.check_output(
            ["git", "-C", str(origin), "rev-parse", "HEAD"], text=True
        ).strip()
        tree = subprocess.check_output(
            ["git", "-C", str(origin), "rev-parse", "HEAD^{tree}"], text=True
        ).strip()
        commit_object = subprocess.check_output(
            ["git", "-C", str(origin), "cat-file", "commit", commit]
        )
        cache = (
            self.repo
            / "var"
            / "renderer-hermetic-build-v1"
            / "download-cache"
            / "wrong-commit.tar"
        )
        cache.parent.mkdir(parents=True)
        with tarfile.open(
            cache,
            mode="w",
            format=tarfile.PAX_FORMAT,
            pax_headers={"comment": "0" * 40},
        ) as archive:
            info = tarfile.TarInfo(f"source-{commit}/source.txt")
            info.size = 6
            info.mode = 0o644
            archive.addfile(info, io.BytesIO(b"exact\n"))
        archive_bytes = cache.read_bytes()
        self.write_lock()
        self.replace_input(
            "skia",
            sha256="sha256:" + hashlib.sha256(archive_bytes).hexdigest(),
            byteLength=len(archive_bytes),
            source={
                "kind": "git-archive",
                "remote": str(origin),
                "commit": commit,
                "tree": tree,
                "prefix": f"source-{commit}/",
                "gitVersion": subprocess.check_output(
                    ["git", "--version"], text=True
                ).strip(),
                "commitObject": {
                    "encoding": "base64",
                    "byteLength": len(commit_object),
                    "sha256": "sha256:" + hashlib.sha256(commit_object).hexdigest(),
                    "bytes": base64.b64encode(commit_object).decode("ascii"),
                },
                "cachePath": "var/renderer-hermetic-build-v1/download-cache/wrong-commit.tar",
                "archiveConfig": {"coreAutocrlf": True},
            },
        )

        failed = self.run_cli("stage")
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn("GIT_ARCHIVE_COMMIT", failed.stderr)

    def test_repository_tree_is_staged_as_a_normalized_tar(self) -> None:
        vendor = self.repo / "vendor"
        (vendor / "nested").mkdir(parents=True)
        (vendor / "excluded").mkdir()
        (vendor / "a.txt").write_bytes(b"A")
        (vendor / "nested" / "b.txt").write_bytes(b"B")
        (vendor / "excluded" / "host.bin").write_bytes(b"host")
        self.write_lock()
        self.replace_input(
            "rust-vendor",
            sha256="sha256:48cf6d78dff06c68db6aaccb5663231f9a2d619a3c750164b86281eff843b81b",
            byteLength=10240,
            source={
                "kind": "repository-tree",
                "path": "vendor",
                "treeSha256": "sha256:ccc56457b5442e7e1563d07fb28eabc794d0a00f6c7eeee2011e5f4613b15109",
                "fileCount": 2,
                "prefix": "vendor/",
                "excludePaths": ["excluded"],
                "archiveFormat": "python-tar-gnu-normalized/1.0",
            },
        )

        staged = self.run_cli("stage")
        self.assertEqual(staged.returncode, 0, staged.stderr)
        archive = self.bundle / "inputs" / "rust-vendor" / "source.bin"
        self.assertEqual(archive.stat().st_size, 10240)
        with tarfile.open(archive, mode="r:") as staged_archive:
            self.assertNotIn("vendor/excluded/host.bin", staged_archive.getnames())

        os.utime(vendor / "a.txt", (2_000_000_000, 2_000_000_000))
        second_bundle = self.root / "bundle-second"
        original_bundle = self.bundle
        self.bundle = second_bundle
        try:
            repeated = self.run_cli("stage")
        finally:
            self.bundle = original_bundle
        self.assertEqual(repeated.returncode, 0, repeated.stderr)
        self.assertEqual(
            archive.read_bytes(),
            (second_bundle / "inputs" / "rust-vendor" / "source.bin").read_bytes(),
        )

    def test_exact_oci_image_is_staged_as_a_normalized_layout(self) -> None:
        def add_member(archive: tarfile.TarFile, name: str, data: bytes) -> None:
            member = tarfile.TarInfo(name)
            member.size = len(data)
            member.mode = 0o644
            member.mtime = 0
            member.uid = 0
            member.gid = 0
            member.uname = ""
            member.gname = ""
            archive.addfile(member, io.BytesIO(data))

        status = (
            b"Package: fixture\n"
            b"Status: install ok installed\n"
            b"Version: 1.0\n"
            b"Architecture: amd64\n\n"
        )
        raw_layer = io.BytesIO()
        with tarfile.open(
            fileobj=raw_layer, mode="w", format=tarfile.GNU_FORMAT
        ) as archive:
            add_member(archive, "var/lib/dpkg/status", status)
        compressed_layer = io.BytesIO()
        with gzip.GzipFile(
            fileobj=compressed_layer,
            mode="wb",
            filename="",
            mtime=0,
            compresslevel=9,
        ) as output:
            output.write(raw_layer.getvalue())
        layer = compressed_layer.getvalue()
        config = (
            b'{"architecture":"amd64","config":{},"os":"linux","rootfs":'
            b'{"diff_ids":["sha256:a331ad0a0984e94859c5e06b059096c0a8f03af12188472'
            b'bba15bd782824f294"],"type":"layers"}}\n'
        )
        manifest = (
            b'{"config":{"digest":"sha256:7595fb0d28aa228ea608918b1d7a44cd6a8b726858'
            b'baf550b85423a5615ee72d","mediaType":"application/vnd.oci.image.config.v1+'
            b'json","size":164},"layers":[{"digest":"sha256:bdea7d3f70428e848d3d40688'
            b'dbfc87f6eebf3443017d47639e7fd0d14ce6523","mediaType":"application/vnd.'
            b'oci.image.layer.v1.tar+gzip","size":174}],"mediaType":"application/vnd.'
            b'oci.image.manifest.v1+json","schemaVersion":2}\n'
        )
        blobs = {
            "sha256:efae6a52562c43554cd4409ac549d1afa32cb7cf1ce4ce14ae39adf5771dcb01": manifest,
            "sha256:7595fb0d28aa228ea608918b1d7a44cd6a8b726858baf550b85423a5615ee72d": config,
            "sha256:bdea7d3f70428e848d3d40688dbfc87f6eebf3443017d47639e7fd0d14ce6523": layer,
        }

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 - stdlib interface
                prefix = "/v2/fixture/image/"
                if self.path.startswith(prefix + "manifests/"):
                    digest_value = self.path.removeprefix(prefix + "manifests/")
                elif self.path.startswith(prefix + "blobs/"):
                    digest_value = self.path.removeprefix(prefix + "blobs/")
                else:
                    self.send_error(404)
                    return
                data = blobs.get(digest_value)
                if data is None:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, _format: str, *_args: object) -> None:
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        registry = f"http://127.0.0.1:{server.server_port}"
        self.write_lock()
        self.replace_input(
            "oci",
            sha256="sha256:a8ab9c10a10d17aef39bb142f8aa7763777cdd0adc475ed902a7287339b43f35",
            byteLength=10240,
            source={
                "kind": "oci-image",
                "registry": registry,
                "repository": "fixture/image",
                "authorization": {"kind": "none"},
                "allowedRedirectOrigins": [],
                "manifest": {
                    "mediaType": "application/vnd.oci.image.manifest.v1+json",
                    "digest": "sha256:efae6a52562c43554cd4409ac549d1afa32cb7cf1ce4ce14ae39adf5771dcb01",
                    "size": 402,
                },
                "config": {
                    "mediaType": "application/vnd.oci.image.config.v1+json",
                    "digest": "sha256:7595fb0d28aa228ea608918b1d7a44cd6a8b726858baf550b85423a5615ee72d",
                    "size": 164,
                },
                "layers": [
                    {
                        "mediaType": "application/vnd.oci.image.layer.v1.tar+gzip",
                        "digest": "sha256:bdea7d3f70428e848d3d40688dbfc87f6eebf3443017d47639e7fd0d14ce6523",
                        "size": 174,
                    }
                ],
                "platform": {"os": "linux", "architecture": "amd64"},
                "packageDatabase": {
                    "path": "var/lib/dpkg/status",
                    "sha256": "sha256:bac8df74ab81ee574e852bdd4e700f7f82a9783c91748fba3fdc27bc41d7b887",
                    "byteLength": 80,
                    "installedPackageCount": 1,
                },
                "archiveFormat": "oci-layout-tar-gnu-normalized/1.0",
            },
        )
        try:
            staged = self.run_cli("stage")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

        self.assertEqual(staged.returncode, 0, staged.stderr)
        with tarfile.open(
            self.bundle / "inputs" / "oci" / "source.bin", mode="r:"
        ) as archive:
            self.assertEqual(
                archive.getnames(),
                [
                    "oci-layout",
                    "index.json",
                    "blobs/sha256/7595fb0d28aa228ea608918b1d7a44cd6a8b726858baf550b85423a5615ee72d",
                    "blobs/sha256/bdea7d3f70428e848d3d40688dbfc87f6eebf3443017d47639e7fd0d14ce6523",
                    "blobs/sha256/efae6a52562c43554cd4409ac549d1afa32cb7cf1ce4ce14ae39adf5771dcb01",
                ],
            )


if __name__ == "__main__":
    unittest.main()
