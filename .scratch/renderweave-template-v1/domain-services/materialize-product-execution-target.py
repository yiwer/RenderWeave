#!/usr/bin/env python3
"""Materialize the exact DOMAIN_SERVICES Asset capacity product target."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


ARTIFACTS = (
    ("productionGuard", "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/AssetContentCapacityGuard.java"),
    ("rawBytesConsumer", "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/CanonicalAssetAcceptanceAuthority.java"),
    ("pngConsumer", "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/PngAdmission.java"),
    ("jpegConsumer", "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/JpegAdmission.java"),
    ("webpConsumer", "renderweave-asset/src/main/java/cn/hbads/renderweave/asset/internal/WebpAdmission.java"),
    ("guardContractTest", "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/AssetContentCapacityGuardTest.java"),
    ("primaryExecutor", "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/DomainServicesCapacityConformanceTest.java"),
    ("architectureProof", "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/AssetModuleArchitectureTest.java"),
    ("assetKernelIntegrationProof", "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/AssetAcceptanceKernelTest.java"),
    ("jpegIntegrationProof", "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/JpegAdmissionTest.java"),
    ("webpIntegrationProof", "renderweave-asset/src/test/java/cn/hbads/renderweave/asset/internal/WebpAdmissionTest.java"),
    ("independentExecutor", "tools/verify-domain-services-capacity.py"),
    ("capacityCoverage", ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json"),
    ("fixtureContract", ".scratch/renderweave-template-v1/domain-services/fixture-contract-v1.json"),
    ("observationAdapter", ".scratch/renderweave-template-v1/domain-services/observation-adapter-v1.json"),
)
DIRECT_CONSUMERS = (
    "cn.hbads.renderweave.asset.internal.CanonicalAssetAcceptanceAuthority",
    "cn.hbads.renderweave.asset.internal.PngAdmission",
    "cn.hbads.renderweave.asset.internal.JpegAdmission",
    "cn.hbads.renderweave.asset.internal.WebpAdmission",
)
BEHAVIORAL_TESTS = (
    "cn.hbads.renderweave.asset.internal.AssetAcceptanceKernelTest#enforcesRawByteBudgetsPerKind",
    "cn.hbads.renderweave.asset.internal.AssetAcceptanceKernelTest#enforcesImageDimensionLimitsBeforeDecode",
    "cn.hbads.renderweave.asset.internal.JpegAdmissionTest#rejectsOversizedFrameThroughTheSharedCapacityGuardBeforeDecode",
    "cn.hbads.renderweave.asset.internal.WebpAdmissionTest#rejectsOversizedCanvasThroughTheSharedCapacityGuardBeforeFrameDecode",
)


def blob(repo: Path, revision: str, path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def binding(path: str, data: bytes) -> dict[str, object]:
    return {
        "path": path,
        "sha256": "sha256:" + hashlib.sha256(data).hexdigest(),
        "byteLength": len(data),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    repo = arguments.repo.resolve()

    resolved_revision = subprocess.run(
        ["git", "rev-parse", "--verify", f"{arguments.revision}^{{commit}}"],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    if resolved_revision != arguments.revision:
        raise SystemExit("revision must be the exact forty-character commit identity")

    artifacts = [
        {"role": role, **binding(path, blob(repo, resolved_revision, path))}
        for role, path in ARTIFACTS
    ]
    fixture_prefix = ".scratch/renderweave-template-v1/domain-services/fixtures/"
    fixture_names = sorted(
        path.name
        for path in (repo / fixture_prefix).glob("cap-*.json")
    )
    if len(fixture_names) != 12:
        raise SystemExit(f"expected 12 frozen capacity fixtures, found {len(fixture_names)}")
    fixtures = []
    for name in fixture_names:
        repository_path = fixture_prefix + name
        fixtures.append(binding(
            "domain-services/fixtures/" + name,
            blob(repo, resolved_revision, repository_path),
        ))

    target = {
        "artifactVersion": "renderweave-domain-services-capacity-product-target/1.0",
        "targetId": "DOMAIN_SERVICES_CAPACITY_TARGET::ASSET_CONTENT_GUARD::1.0",
        "status": "ISSUED_EXACT_PRODUCT_TARGET",
        "implementationRevision": resolved_revision,
        "executionClass": "EXEC::DOMAIN_SERVICES::1.0",
        "guardContractId": "renderweave-domain-asset-content-capacity-guard/1.0",
        "comparator": "MAX_INCLUSIVE",
        "artifacts": artifacts,
        "fixtures": fixtures,
        "integrationProof": {
            "directConsumers": list(DIRECT_CONSUMERS),
            "behavioralTests": list(BEHAVIORAL_TESTS),
            "authoritativeAdmissionPathProvenSeparately": True,
            "fullUploadPathProvenByScalarProbe": False,
        },
        "boundary": {
            "productApiSurfaceCreated": False,
            "mediaPayloadRequiredForScalarProbe": False,
            "databaseRequiredForScalarProbe": False,
            "nativeRendererInvoked": False,
            "formalRecordsIssued": False,
            "recordIssuanceAllowed": False,
        },
    }
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with arguments.output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(target, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    main()
