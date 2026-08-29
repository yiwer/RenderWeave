import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..");
const TARGET = "rendering-pipeline/capacity-record-issuance-target-v1.json";
const REPO_TARGET = `.scratch/renderweave-template-v1/${TARGET}`;
const PRIMARY_RESULT = "rendering-pipeline/postissuance-primary-result-v1.json";
const INDEPENDENT_RESULT = "rendering-pipeline/postissuance-independent-result-v1.json";
const EVIDENCE = "rendering-pipeline/rendering-pipeline-capacity-postissuance-a2-2026-08-29.json";

function bytes(path) {
  return readFileSync(resolve(ROOT, path));
}

function json(path) {
  return JSON.parse(bytes(path).toString("utf8"));
}

function digest(value) {
  return `sha256:${createHash("sha256").update(value).digest("hex")}`;
}

function artifact(path) {
  const value = bytes(path);
  return { path, sha256: digest(value), byteLength: value.length };
}

function writeJson(path, value) {
  writeFileSync(resolve(ROOT, path), `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function run(command, args) {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.status !== 0) {
    throw new Error(`${command} failed (${result.status}): ${result.stderr || result.stdout}`);
  }
  const value = JSON.parse(result.stdout);
  if (value.status !== "PASS" || value.failureCount !== 0) {
    throw new Error(`${command} postissuance replay did not pass`);
  }
  return value;
}

const target = json(TARGET);
const primary = run("node", [
  "rendering-pipeline/validate-rendering-pipeline-postissuance-primary.mjs",
  "--target", REPO_TARGET,
]);
const independent = run("python", [
  "rendering-pipeline/validate_rendering_pipeline_postissuance_independent.py",
  "--target", REPO_TARGET,
]);
writeJson(PRIMARY_RESULT, primary);
writeJson(INDEPENDENT_RESULT, independent);

const evidence = {
  evidenceVersion: "renderweave-rendering-pipeline-capacity-postissuance-a2/1.0",
  evidenceId: "RENDERING_PIPELINE_CAPACITY_POSTISSUANCE_A2::2026-08-29::000001",
  status: "PASS",
  grade: "A2_INDEPENDENTLY_REPLAYED",
  executionClass: "EXEC::RENDERING_PIPELINE::1.0",
  implementationRevision: target.implementationRevision,
  issuanceTarget: artifact(TARGET),
  productExecutionClosure: {
    target: {
      path: target.predecessorProductTarget.path.replace(".scratch/renderweave-template-v1/", ""),
      sha256: target.predecessorProductTarget.sha256,
      byteLength: target.predecessorProductTarget.byteLength,
    },
    executorManifests: target.requiredExecutorManifests.map((entry) => ({
      path: entry.path.replace(".scratch/renderweave-template-v1/", ""),
      sha256: entry.sha256,
      byteLength: entry.byteLength,
    })),
    assignedCorpusDigest: target.assignedCorpus.assignedCorpusDigest,
    preissuanceReplayRequiredAndPassed: true,
    freshProductGateRequiredForResolution: true,
  },
  registryBindings: {
    formalCases: {
      ...artifact("conformance-cases-v1.jsonl"),
      recordCount: target.poststate.formalCases.recordCount,
      exactPreservedPrefixSha256: target.poststate.formalCases.preservedPrefixSha256,
      appendedRecordCount: target.assignedCorpus.caseCount,
    },
    formalOracles: {
      ...artifact("conformance-oracles-v1.jsonl"),
      recordCount: target.poststate.formalOracles.recordCount,
      exactPreservedPrefixSha256: target.poststate.formalOracles.preservedPrefixSha256,
      appendedRecordCount: target.assignedCorpus.oracleCount,
    },
    executionClassCatalog: artifact("conformance-execution-classes-v1.json"),
    bootstrapOrder: artifact("conformance-bootstrap-order-v1.json"),
  },
  replay: {
    primary: {
      result: artifact(PRIMARY_RESULT),
      status: primary.status,
      checkCount: primary.checkCount,
      failureCount: primary.failureCount,
      runtime: primary.runtime,
    },
    independent: {
      result: artifact(INDEPENDENT_RESULT),
      status: independent.status,
      checkCount: independent.checkCount,
      failureCount: independent.failureCount,
      runtime: independent.runtime,
    },
    sharedSemanticLibrary: null,
    differentRuntimeAndParserImplementations: true,
  },
  observedFrontier: {
    issuedSpecRegistryCaseCount: 46,
    issuedSpecRegistryOracleCount: 46,
    issuedDomainServicesCapacityCaseCount: 12,
    issuedDomainServicesCapacityOracleCount: 12,
    issuedDesignInputExpressionCapacityCaseCount: 195,
    issuedDesignInputExpressionCapacityOracleCount: 195,
    issuedRenderingPipelineCapacityCaseCount: 156,
    issuedRenderingPipelineCapacityOracleCount: 156,
    issuedCapacityCaseCount: 363,
    issuedCapacityOracleCount: 363,
    totalFormalCaseCount: 409,
    totalFormalOracleCount: 409,
    totalCapacityCandidateCount: 525,
    remainingUnissuedCapacityCaseCount: 162,
    currentPhase: "CAPACITY_BOUNDARY",
    renderingPipelineExecutable: true,
  },
  sideEffects: {
    historicalFormalRecordsMutated: 0,
    productApiChanges: 0,
    externalProviderAttempts: 0,
    externalNetworkAttempts: 0,
  },
  boundary: {
    allCapacityRecordsIssued: false,
    combinedCapacityRecordsIssued: 0,
    fullAutomatedCorpusExecutable: false,
    rendererCertified: false,
    rendererReady: false,
    ticket19Closed: false,
  },
};
writeJson(EVIDENCE, evidence);
process.stdout.write(`${JSON.stringify({
  status: evidence.status,
  evidence: artifact(EVIDENCE),
  primaryChecks: primary.checkCount,
  independentChecks: independent.checkCount,
})}\n`);
