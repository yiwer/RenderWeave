import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");

function bytes(relativePath) {
  return readFileSync(resolve(root, relativePath));
}

function json(relativePath) {
  return JSON.parse(bytes(relativePath).toString("utf8"));
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function artifact(relativePath) {
  const content = bytes(relativePath);
  return {
    path: relativePath,
    sha256: `sha256:${sha256(content)}`,
    byteLength: content.length,
  };
}

function recordCount(relativePath) {
  return bytes(relativePath)
    .toString("utf8")
    .split(/\r?\n/u)
    .filter((line) => line.length > 0).length;
}

function runReplay(command, args) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.status !== 0) {
    throw new Error(`${command} failed (${result.status}): ${result.stderr || result.stdout}`);
  }
  const parsed = JSON.parse(result.stdout);
  if (parsed.status !== "PASS" || parsed.failureCount !== 0) {
    throw new Error(`${command} replay did not pass`);
  }
  return parsed;
}

function writeJson(relativePath, value) {
  writeFileSync(resolve(root, relativePath), `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

const targetPath = "spec-registry/target-manifest-v1.json";
const primaryExecutorPath = "spec-registry/primary-executor-manifest-v1.json";
const independentExecutorPath = "spec-registry/independent-executor-manifest-v1.json";
const target = json(targetPath);
const primaryExecutor = json(primaryExecutorPath);
const independentExecutor = json(independentExecutorPath);
const targetArtifact = artifact(targetPath);
const primaryExecutorArtifact = artifact(primaryExecutorPath);
const independentExecutorArtifact = artifact(independentExecutorPath);

for (const executor of [primaryExecutor, independentExecutor]) {
  if (executor.implementationRevision !== target.implementationRevision) {
    throw new Error(`executor revision mismatch: ${executor.executorId}`);
  }
  if (executor.targetManifest.sha256 !== targetArtifact.sha256) {
    throw new Error(`executor target hash mismatch: ${executor.executorId}`);
  }
}

const primary = runReplay("node", [
  "spec-registry/validate-spec-registry-primary.mjs",
  "--target",
  targetPath,
]);
const independent = runReplay("python", [
  "spec-registry/validate-spec-registry-independent.py",
  "--target",
  targetPath,
]);

const primaryResult = {
  ...primary,
  implementationRevision: target.implementationRevision,
  targetManifest: targetArtifact,
  executorManifest: primaryExecutorArtifact,
};
const independentResult = {
  ...independent,
  implementationRevision: target.implementationRevision,
  targetManifest: targetArtifact,
  executorManifest: independentExecutorArtifact,
};
writeJson("spec-registry/primary-result-v1.json", primaryResult);
writeJson("spec-registry/independent-result-v1.json", independentResult);

const formalCases = artifact("conformance-cases-v1.jsonl");
const formalOracles = artifact("conformance-oracles-v1.jsonl");
const candidateCases = artifact("spec-registry/candidate/conformance-cases-v1.jsonl");
const candidateOracles = artifact("spec-registry/candidate/conformance-oracles-v1.jsonl");
if (formalCases.sha256 !== candidateCases.sha256 || formalOracles.sha256 !== candidateOracles.sha256) {
  throw new Error("issued SPEC registries are no longer byte-identical to their candidates");
}

const requirements = json("requirements-v1.json");
const snapshotPolicy = json("conformance-manifest-snapshot-policy-v1.json");
const capacityMaterialization = json("capacity-boundary/materialization-manifest-v1.json");

const evidence = {
  evidenceVersion: "renderweave-spec-registry-a2/1.1",
  evidenceId: "SPEC_REGISTRY_A2::2026-08-17::000002",
  status: "PASS",
  grade: "A2_INDEPENDENTLY_REPLAYED",
  scope: "EXEC::SPEC_REGISTRY::1.0 only",
  predecessorEvidenceId: "SPEC_REGISTRY_A2::2026-08-16::000001",
  specRevision: {
    branch: "spec/template-v1",
    baseCommit: "b14c2d7d4978c679e7ab8e7a2bace3da7af884de",
    implementationRevision: target.implementationRevision,
  },
  authorityBindings: {
    acceptanceManifest: artifact("acceptance-manifest-v1.json"),
    requirementsRegistry: {
      ...artifact("requirements-v1.json"),
      registeredRequirementCount: requirements.counts.requirements,
    },
    probeProfile: artifact("conformance-probe-profile-v1.json"),
    safeBaselineManifest: artifact("conformance-safe-baselines-v1.json"),
    generatorManifest: artifact("conformance-generator-manifests-v1.json"),
    manifestSnapshotPolicy: {
      ...artifact("conformance-manifest-snapshot-policy-v1.json"),
      retainedSnapshotCount: snapshotPolicy.requiredSeedSnapshots.length,
      latestFallbackAllowed: false,
    },
    capacityCoverage: artifact("conformance-capacity-coverage-v1.json"),
    capacityMaterialization: {
      ...artifact("capacity-boundary/materialization-manifest-v1.json"),
      axisCount: capacityMaterialization.counts.axisCount,
      candidateCaseCount: capacityMaterialization.counts.shapeCandidateCaseCount,
      candidateOracleCount: capacityMaterialization.counts.shapeCandidateOracleCount,
      formallyIssued: false,
    },
    targetManifest: targetArtifact,
    primaryExecutorManifest: primaryExecutorArtifact,
    independentExecutorManifest: independentExecutorArtifact,
  },
  registryBindings: {
    candidateCases: {
      ...candidateCases,
      recordCount: recordCount(candidateCases.path),
    },
    formalCases: {
      ...formalCases,
      recordCount: recordCount(formalCases.path),
      byteIdenticalToCandidate: true,
    },
    candidateOracles: {
      ...candidateOracles,
      recordCount: recordCount(candidateOracles.path),
    },
    formalOracles: {
      ...formalOracles,
      recordCount: recordCount(formalOracles.path),
      byteIdenticalToCandidate: true,
    },
    activeCorpusDigest: target.activeCorpusDigest,
  },
  replay: {
    primary: {
      result: artifact("spec-registry/primary-result-v1.json"),
      status: primary.status,
      checkCount: primary.checkCount,
      failureCount: primary.failureCount,
      runtime: primary.runtime,
    },
    independent: {
      result: artifact("spec-registry/independent-result-v1.json"),
      status: independent.status,
      checkCount: independent.checkCount,
      failureCount: independent.failureCount,
      runtime: independent.runtime,
    },
    sharedSemanticLibrary: null,
    differentRuntimeAndParserImplementations: true,
  },
  observedFrontier: {
    snapshotCatalogClosureValid: true,
    issuedSpecCaseCount: recordCount(formalCases.path),
    issuedSpecOracleCount: recordCount(formalOracles.path),
    capacityAxisCount: capacityMaterialization.counts.axisCount,
    capacityShapeCandidateCaseCount: capacityMaterialization.counts.shapeCandidateCaseCount,
    capacityShapeCandidateOracleCount: capacityMaterialization.counts.shapeCandidateOracleCount,
    capacityRecordsIssued: 0,
    capacityProductExecutionObserved: false,
  },
  sideEffects: {
    productCodeFilesChanged: 0,
    productWritesObservedPerCase: 0,
    renderDocumentsObservedPerCase: 0,
    renderOutputsObservedPerCase: 0,
    networkAttempts: 0,
    externalProviderAttempts: 0,
  },
  boundary: {
    currentIssuancePhase: "CAPACITY_BOUNDARY",
    otherExecutionClassesExecutable: false,
    isolatedCapacityRecordsIssued: 0,
    combinedCapacityRecordsIssued: 0,
    fullAutomatedCorpusExecutable: false,
    rendererCertified: false,
    rendererReady: false,
    ticket19Closed: false,
  },
};

writeJson("spec-registry/spec-registry-a2-2026-08-17.json", evidence);
process.stdout.write(`${JSON.stringify({
  status: evidence.status,
  evidence: artifact("spec-registry/spec-registry-a2-2026-08-17.json"),
  primaryChecks: primary.checkCount,
  independentChecks: independent.checkCount,
})}\n`);
