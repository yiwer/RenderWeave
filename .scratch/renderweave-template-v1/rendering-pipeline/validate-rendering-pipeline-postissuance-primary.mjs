import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "..", "..", "..");
const SPEC_ROOT = ".scratch/renderweave-template-v1";
const EXECUTION_CLASS = "EXEC::RENDERING_PIPELINE::1.0";
const PREDECESSOR_CLASS = "EXEC::DESIGN_INPUT_EXPRESSION::1.0";
const TARGET_VERSION = "renderweave-rendering-pipeline-capacity-issuance-target/1.0";
const TARGET_ID = "RENDERING_PIPELINE_ISSUANCE::CAPACITY::1.0";
const DEFAULT_TARGET = `${SPEC_ROOT}/rendering-pipeline/capacity-record-issuance-target-v1.json`;
const PRODUCT_TARGET_PATH = `${SPEC_ROOT}/rendering-pipeline/execution-class-target-v1.json`;
const CASE_PATH = `${SPEC_ROOT}/conformance-cases-v1.jsonl`;
const ORACLE_PATH = `${SPEC_ROOT}/conformance-oracles-v1.jsonl`;
const FORMAL_PREFIX_COUNT = 253;
const ASSIGNED_COUNT = 156;
const FORMAL_POST_COUNT = FORMAL_PREFIX_COUNT + ASSIGNED_COUNT;
const ISSUED_CAPACITY_COUNT = 12 + 195 + ASSIGNED_COUNT;

const args = process.argv.slice(2);
function argument(name, fallback = null) {
  const index = args.indexOf(name);
  return index === -1 ? fallback : args[index + 1];
}
const targetPath = argument("--target", DEFAULT_TARGET);
const outputPath = argument("--output");

let checkCount = 0;
const failures = [];
function check(condition, code, detail) {
  checkCount += 1;
  if (!condition) failures.push({ code, detail });
}

function bytes(path) {
  return readFileSync(resolve(REPO, path));
}

function digest(value) {
  return `sha256:${createHash("sha256").update(value).digest("hex")}`;
}

function binding(path, value) {
  return { path, sha256: digest(value), byteLength: value.length };
}

function strictJson(value, label) {
  if (value.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf])) || value.includes(0x0d)) {
    throw new Error(`${label}: non-canonical transport`);
  }
  return JSON.parse(value.toString("utf8"));
}

function json(path) {
  return strictJson(bytes(path), path);
}

function jsonLines(value, label) {
  if (value.length === 0 || value.at(-1) !== 0x0a || value.includes(0x0d)) {
    throw new Error(`${label}: BOM-free LF-terminated JSONL required`);
  }
  return value.toString("utf8").slice(0, -1).split("\n").map((line, index) => {
    if (line.length === 0) throw new Error(`${label}: blank line ${index + 1}`);
    return { raw: Buffer.from(`${line}\n`, "utf8"), record: JSON.parse(line) };
  });
}

function gitBlob(revision, path) {
  const result = spawnSync("git", ["show", `${revision}:${path}`], {
    cwd: REPO,
    encoding: null,
    maxBuffer: 32 * 1024 * 1024,
    windowsHide: true,
  });
  if (result.status !== 0) throw new Error(`git show failed for ${revision}:${path}`);
  return result.stdout;
}

function utf8Sort(values) {
  return [...values].sort((left, right) =>
    Buffer.compare(Buffer.from(left, "utf8"), Buffer.from(right, "utf8")));
}

function replay() {
  const targetBytes = bytes(targetPath);
  const target = strictJson(targetBytes, targetPath);
  check(target.artifactVersion === TARGET_VERSION, "TARGET_VERSION", target.artifactVersion);
  check(target.targetId === TARGET_ID, "TARGET_ID", target.targetId);
  check(target.status === "FROZEN_APPEND_ONLY_ISSUANCE_TARGET", "TARGET_STATUS", target.status);
  check(/^[0-9a-f]{40}$/u.test(target.implementationRevision), "TARGET_REVISION", target.implementationRevision);
  check(target.executionClass === EXECUTION_CLASS, "TARGET_CLASS", target.executionClass);

  const artifactPaths = new Set();
  for (const artifact of target.artifacts) {
    check(typeof artifact.path === "string" && !artifactPaths.has(artifact.path),
      "TARGET_ARTIFACT_PATH", artifact.path);
    artifactPaths.add(artifact.path);
    const value = gitBlob(target.implementationRevision, artifact.path);
    check(JSON.stringify(artifact) === JSON.stringify(binding(artifact.path, value)),
      "TARGET_ARTIFACT_BINDING", artifact.path);
  }

  const productTargetBytes = gitBlob(target.implementationRevision, PRODUCT_TARGET_PATH);
  const productTarget = strictJson(productTargetBytes, PRODUCT_TARGET_PATH);
  check(JSON.stringify(target.predecessorProductTarget) ===
    JSON.stringify(binding(PRODUCT_TARGET_PATH, productTargetBytes)),
  "PREDECESSOR_TARGET_BINDING", PRODUCT_TARGET_PATH);
  check(productTarget.executionClass === EXECUTION_CLASS &&
    productTarget.assignedCorpus.assignedCorpusDigest === target.assignedCorpus.assignedCorpusDigest,
  "PREDECESSOR_ASSIGNED_DIGEST", productTarget.assignedCorpus?.assignedCorpusDigest);
  check(productTarget.assignedCorpus.assignedCaseCount === ASSIGNED_COUNT &&
    productTarget.assignedCorpus.assignedOracleCount === ASSIGNED_COUNT,
  "PREDECESSOR_ASSIGNED_COUNTS",
  `${productTarget.assignedCorpus.assignedCaseCount}/${productTarget.assignedCorpus.assignedOracleCount}`);
  check(productTarget.formalRegistryBoundary.cases.sha256 === target.prestate.formalCases.sha256 &&
    productTarget.formalRegistryBoundary.oracles.sha256 === target.prestate.formalOracles.sha256 &&
    productTarget.formalRegistryBoundary.appendPerformed === false,
  "PREDECESSOR_FORMAL_BOUNDARY", productTarget.formalRegistryBoundary);

  const executorRoles = [];
  for (const manifest of target.requiredExecutorManifests) {
    const value = gitBlob(target.implementationRevision, manifest.path);
    const parsed = strictJson(value, manifest.path);
    check(JSON.stringify(manifest) === JSON.stringify(binding(manifest.path, value)),
      "EXECUTOR_MANIFEST_BINDING", manifest.path);
    check(parsed.executionClass === EXECUTION_CLASS &&
      parsed.targetManifest.sha256 === target.predecessorProductTarget.sha256,
    "EXECUTOR_MANIFEST_TARGET", manifest.path);
    executorRoles.push(parsed.role);
  }
  check(JSON.stringify(executorRoles) === JSON.stringify([
    "java-evaluator-and-sealer", "rust-render-document-parser-and-engine",
  ]), "EXECUTOR_ROLES", executorRoles);

  const candidateCasePath = target.assignedCorpus.sourceCases.path;
  const candidateOraclePath = target.assignedCorpus.sourceOracles.path;
  const candidateCases = gitBlob(target.implementationRevision, candidateCasePath);
  const candidateOracles = gitBlob(target.implementationRevision, candidateOraclePath);
  check(JSON.stringify(target.assignedCorpus.sourceCases) ===
    JSON.stringify(binding(candidateCasePath, candidateCases)),
  "CANDIDATE_CASE_BINDING", candidateCasePath);
  check(JSON.stringify(target.assignedCorpus.sourceOracles) ===
    JSON.stringify(binding(candidateOraclePath, candidateOracles)),
  "CANDIDATE_ORACLE_BINDING", candidateOraclePath);
  const candidateCaseRows = jsonLines(candidateCases, "candidate cases");
  const candidateOracleRows = jsonLines(candidateOracles, "candidate oracles");
  const assignedCases = candidateCaseRows.filter(({ record }) =>
    record.executionClass === EXECUTION_CLASS);
  const assignedOracleIds = new Set(assignedCases.flatMap(({ record }) =>
    record.coverage.flatMap((coverage) =>
      coverage.evidence.map((evidence) => evidence.oracleId))));
  const assignedOracles = candidateOracleRows.filter(({ record }) =>
    assignedOracleIds.has(record.oracleId));
  const assignedCaseBytes = Buffer.concat(assignedCases.map(({ raw }) => raw));
  const assignedOracleBytes = Buffer.concat(assignedOracles.map(({ raw }) => raw));
  check(assignedCases.length === ASSIGNED_COUNT && assignedOracles.length === ASSIGNED_COUNT &&
    assignedOracleIds.size === ASSIGNED_COUNT,
  "ASSIGNED_COUNTS", `${assignedCases.length}/${assignedOracles.length}/${assignedOracleIds.size}`);
  check(target.assignedCorpus.caseBytesSha256 === digest(assignedCaseBytes),
    "ASSIGNED_CASE_BYTES", digest(assignedCaseBytes));
  check(target.assignedCorpus.oracleBytesSha256 === digest(assignedOracleBytes),
    "ASSIGNED_ORACLE_BYTES", digest(assignedOracleBytes));
  check(target.assignedCorpus.caseCount === ASSIGNED_COUNT &&
    target.assignedCorpus.oracleCount === ASSIGNED_COUNT,
  "TARGET_ASSIGNED_COUNTS", `${target.assignedCorpus.caseCount}/${target.assignedCorpus.oracleCount}`);
  check(JSON.stringify(target.assignedCorpus.caseIds) ===
    JSON.stringify(assignedCases.map(({ record }) => record.caseId)),
  "TARGET_ASSIGNED_CASE_IDS", target.assignedCorpus.caseIds.length);
  check(JSON.stringify(target.assignedCorpus.oracleIds) ===
    JSON.stringify(assignedOracles.map(({ record }) => record.oracleId)),
  "TARGET_ASSIGNED_ORACLE_IDS", target.assignedCorpus.oracleIds.length);
  const assignedCorpusDigest = digest(Buffer.concat([
    Buffer.from("renderweave-rendering-pipeline-assigned-corpus/1\0", "utf8"),
    assignedCaseBytes,
    Buffer.from([0]),
    assignedOracleBytes,
  ]));
  check(target.assignedCorpus.assignedCorpusDigest === assignedCorpusDigest,
    "TARGET_ASSIGNED_CORPUS_DIGEST", assignedCorpusDigest);

  const baseCases = gitBlob(target.implementationRevision, target.prestate.formalCases.path);
  const baseOracles = gitBlob(target.implementationRevision, target.prestate.formalOracles.path);
  const previousPath = target.prestate.previousCapacityIssuance.path;
  const previousBytes = gitBlob(target.implementationRevision, previousPath);
  const previous = strictJson(previousBytes, previousPath);
  check(JSON.stringify(target.prestate.previousCapacityIssuance) ===
    JSON.stringify(binding(previousPath, previousBytes)),
  "PREVIOUS_ISSUANCE_BINDING", previousPath);
  check(previous.executionClass === PREDECESSOR_CLASS &&
    previous.poststate.formalCases.sha256 === digest(baseCases) &&
    previous.poststate.formalOracles.sha256 === digest(baseOracles),
  "PREVIOUS_ISSUANCE_POSTSTATE", previous.executionClass);
  const casePrefixLength = previous.prestate.formalCases.byteLength;
  const oraclePrefixLength = previous.prestate.formalOracles.byteLength;
  check(digest(baseCases.subarray(0, casePrefixLength)) === previous.prestate.formalCases.sha256 &&
    digest(baseCases.subarray(casePrefixLength)) === previous.assignedCorpus.caseBytesSha256,
  "PREVIOUS_CASE_CHAIN", digest(baseCases));
  check(digest(baseOracles.subarray(0, oraclePrefixLength)) === previous.prestate.formalOracles.sha256 &&
    digest(baseOracles.subarray(oraclePrefixLength)) === previous.assignedCorpus.oracleBytesSha256,
  "PREVIOUS_ORACLE_CHAIN", digest(baseOracles));

  const formalCases = bytes(CASE_PATH);
  const formalOracles = bytes(ORACLE_PATH);
  check(JSON.stringify(target.prestate.formalCases) === JSON.stringify({
    ...binding(CASE_PATH, baseCases), recordCount: FORMAL_PREFIX_COUNT,
  }), "PRESTATE_CASE_BINDING", digest(baseCases));
  check(JSON.stringify(target.prestate.formalOracles) === JSON.stringify({
    ...binding(ORACLE_PATH, baseOracles), recordCount: FORMAL_PREFIX_COUNT,
  }), "PRESTATE_ORACLE_BINDING", digest(baseOracles));
  check(formalCases.equals(Buffer.concat([baseCases, assignedCaseBytes])),
    "FORMAL_CASE_EXACT_APPEND", digest(formalCases));
  check(formalOracles.equals(Buffer.concat([baseOracles, assignedOracleBytes])),
    "FORMAL_ORACLE_EXACT_APPEND", digest(formalOracles));
  check(JSON.stringify(target.poststate.formalCases) === JSON.stringify({
    ...binding(CASE_PATH, formalCases),
    recordCount: FORMAL_POST_COUNT,
    preservedPrefixSha256: digest(baseCases),
  }), "POSTSTATE_CASE_BINDING", digest(formalCases));
  check(JSON.stringify(target.poststate.formalOracles) === JSON.stringify({
    ...binding(ORACLE_PATH, formalOracles),
    recordCount: FORMAL_POST_COUNT,
    preservedPrefixSha256: digest(baseOracles),
  }), "POSTSTATE_ORACLE_BINDING", digest(formalOracles));

  const formalCaseRows = jsonLines(formalCases, "formal cases");
  const formalOracleRows = jsonLines(formalOracles, "formal oracles");
  check(formalCaseRows.length === FORMAL_POST_COUNT && formalOracleRows.length === FORMAL_POST_COUNT,
    "FORMAL_COUNTS", `${formalCaseRows.length}/${formalOracleRows.length}`);
  check(Buffer.concat(formalCaseRows.slice(FORMAL_PREFIX_COUNT).map(({ raw }) => raw))
    .equals(assignedCaseBytes), "FORMAL_CASE_SUFFIX", digest(assignedCaseBytes));
  check(Buffer.concat(formalOracleRows.slice(FORMAL_PREFIX_COUNT).map(({ raw }) => raw))
    .equals(assignedOracleBytes), "FORMAL_ORACLE_SUFFIX", digest(assignedOracleBytes));
  const caseIds = formalCaseRows.map(({ record }) => record.caseId);
  const oracleIds = formalOracleRows.map(({ record }) => record.oracleId);
  check(new Set(caseIds).size === caseIds.length, "CASE_ID_UNIQUE", caseIds.length);
  check(new Set(oracleIds).size === oracleIds.length, "ORACLE_ID_UNIQUE", oracleIds.length);

  const oracleById = new Map(formalOracleRows.map(({ record }) => [record.oracleId, record]));
  const referenced = new Set();
  for (const { record } of assignedCases) {
    check(record.suite === "CAPACITY_BOUNDARY" && record.executionClass === EXECUTION_CLASS,
      "RENDERING_CASE_ROUTING", record.caseId);
    const requirementIds = record.coverage.map((edge) => edge.requirementId);
    check(JSON.stringify(requirementIds) === JSON.stringify(utf8Sort(requirementIds)),
      "RENDERING_COVERAGE_ORDER", record.caseId);
    for (const coverage of record.coverage) {
      for (const evidence of coverage.evidence) {
        referenced.add(evidence.oracleId);
        const oracle = oracleById.get(evidence.oracleId);
        check(Boolean(oracle), "RENDERING_ORACLE_REFERENCE", evidence.oracleId);
        if (oracle) {
          const assertionIds = new Set(oracle.assertions.map((assertion) => assertion.assertionId));
          check(evidence.assertionIds.every((id) => assertionIds.has(id)),
            "RENDERING_ASSERTION_REFERENCE", `${record.caseId}:${evidence.oracleId}`);
        }
      }
    }
  }
  check(JSON.stringify(utf8Sort(referenced)) === JSON.stringify(utf8Sort(assignedOracleIds)),
    "RENDERING_NO_ORPHAN_ORACLE", [...referenced]);

  const profile = json(`${SPEC_ROOT}/conformance-probe-profile-v1.json`);
  const probeById = new Map(profile.probes.map((probe) => [probe.probeId, probe]));
  for (const { record } of assignedOracles) {
    check(record.probeProfile === "renderweave-conformance-probes/1.0",
      "RENDERING_ORACLE_PROFILE", record.oracleId);
    record.assertions.forEach((assertion, index) => {
      check(assertion.assertionId === `A${String(index + 1).padStart(3, "0")}`,
        "RENDERING_ASSERTION_ID", `${record.oracleId}:${assertion.assertionId}`);
      const probe = probeById.get(assertion.probeId);
      check(Boolean(probe) && probe.executionClasses.includes(EXECUTION_CLASS) &&
        probe.allowedOperators.includes(assertion.operator),
      "RENDERING_ASSERTION_PROBE", `${record.oracleId}:${assertion.probeId}`);
    });
  }

  const executionCatalog = json(`${SPEC_ROOT}/conformance-execution-classes-v1.json`);
  const renderingClass = executionCatalog.classes.find((entry) =>
    entry.executionClass === EXECUTION_CLASS);
  const predecessorClass = executionCatalog.classes.find((entry) =>
    entry.executionClass === PREDECESSOR_CLASS);
  check(renderingClass?.status === "EXECUTABLE_A2_REPLAYED" &&
    renderingClass.caseRecordCount === ASSIGNED_COUNT &&
    renderingClass.oracleRecordCount === ASSIGNED_COUNT && renderingClass.executable === true,
  "RENDERING_CLASS_EXECUTABLE", renderingClass?.status);
  check(predecessorClass?.status === "EXECUTABLE_A2_REPLAYED" &&
    predecessorClass.caseRecordCount === 195 && predecessorClass.oracleRecordCount === 195 &&
    predecessorClass.executable === true,
  "PREDECESSOR_CLASS_EXECUTABLE", predecessorClass?.status);
  const bootstrap = json(`${SPEC_ROOT}/conformance-bootstrap-order-v1.json`);
  const step = bootstrap.steps.find((entry) => entry.executionClass === EXECUTION_CLASS);
  check(step?.assignedCorpusStatus === "ISSUED_156_CASES_156_ORACLES" && step.executable === true,
    "RENDERING_BOOTSTRAP_EXECUTABLE", step?.assignedCorpusStatus);
  check(bootstrap.caseRegistryRecordCount === FORMAL_POST_COUNT &&
    bootstrap.oracleRegistryRecordCount === FORMAL_POST_COUNT &&
    bootstrap.currentPhase === "CAPACITY_BOUNDARY",
  "BOOTSTRAP_FORMAL_COUNTS",
  `${bootstrap.caseRegistryRecordCount}/${bootstrap.oracleRegistryRecordCount}`);
  const acceptance = json(`${SPEC_ROOT}/acceptance-manifest-v1.json`);
  const renderingAcceptance = acceptance.conformanceRegistries.renderingPipelineFixtureBootstrap;
  check(renderingAcceptance.status === "EXECUTABLE_A2_REPLAYED" &&
    renderingAcceptance.formalCapacityRecordCount === ASSIGNED_COUNT &&
    renderingAcceptance.executable === true,
  "RENDERING_ACCEPTANCE_EXECUTABLE", renderingAcceptance.status);
  check(acceptance.counts.issuedCapacityBoundaryCases === ISSUED_CAPACITY_COUNT &&
    acceptance.counts.issuedCapacityBoundaryOracles === ISSUED_CAPACITY_COUNT &&
    acceptance.counts.executableContractBoundaryCases === ISSUED_CAPACITY_COUNT,
  "ACCEPTANCE_CAPACITY_COUNTS", JSON.stringify(acceptance.counts));
  check(executionCatalog.capacityBoundaryMaterialization.formalCapacityCaseCount ===
    ISSUED_CAPACITY_COUNT &&
    executionCatalog.capacityBoundaryMaterialization.formalCapacityOracleCount ===
    ISSUED_CAPACITY_COUNT,
  "CATALOG_CAPACITY_COUNTS", JSON.stringify(executionCatalog.capacityBoundaryMaterialization));
  const phase = bootstrap.recordIssuancePhases.find((entry) => entry.phase === "CAPACITY_BOUNDARY");
  check(phase?.formalIssuedCaseCount === ISSUED_CAPACITY_COUNT &&
    phase?.formalIssuedOracleCount === ISSUED_CAPACITY_COUNT,
  "BOOTSTRAP_CAPACITY_COUNTS", JSON.stringify(phase));
  const specTarget = json(`${SPEC_ROOT}/spec-registry/target-manifest-v1.json`);
  const issuance = specTarget.registryBindings.appendOnlyIssuance;
  check(specTarget.implementationRevision === "spec-registry-bootstrap/1.15" &&
    issuance.appendedExecutionClass === EXECUTION_CLASS &&
    issuance.appendedCaseCount === ASSIGNED_COUNT &&
    issuance.appendedOracleCount === ASSIGNED_COUNT &&
    issuance.assignedCorpusDigest === target.assignedCorpus.assignedCorpusDigest,
  "SPEC_TARGET_ISSUANCE", specTarget.implementationRevision);
  check(issuance.target.path === targetPath.replace(`${SPEC_ROOT}/`, "") &&
    issuance.target.sha256 === digest(targetBytes) &&
    issuance.target.byteLength === targetBytes.length &&
    issuance.predecessorIssuance.appendedExecutionClass === PREDECESSOR_CLASS,
  "SPEC_TARGET_ISSUANCE_CHAIN", issuance.target.path);

  return { targetBytes, target, formalCaseCount: formalCaseRows.length,
    formalOracleCount: formalOracleRows.length };
}

let state = { targetBytes: Buffer.alloc(0), target: {}, formalCaseCount: 0, formalOracleCount: 0 };
try {
  state = replay();
} catch (error) {
  failures.push({ code: "UNEXPECTED_REPLAY_FAILURE", detail: String(error.message || error) });
}
const report = {
  reportVersion: "renderweave-rendering-pipeline-postissuance-primary/1",
  engine: "node-primary-registry-replayer",
  runtime: process.version,
  status: failures.length === 0 ? "PASS" : "FAIL",
  checkCount,
  failureCount: failures.length,
  failures,
  targetManifest: binding(targetPath, state.targetBytes),
  implementationRevision: state.target.implementationRevision,
  executionClass: EXECUTION_CLASS,
  formalCaseCount: state.formalCaseCount,
  formalOracleCount: state.formalOracleCount,
  issuedRenderingPipelineCaseCount: state.formalCaseCount === FORMAL_POST_COUNT ? ASSIGNED_COUNT : 0,
  issuedRenderingPipelineOracleCount: state.formalOracleCount === FORMAL_POST_COUNT ? ASSIGNED_COUNT : 0,
  issuedCapacityCaseCount: state.formalCaseCount === FORMAL_POST_COUNT ? ISSUED_CAPACITY_COUNT : 0,
  issuedCapacityOracleCount: state.formalOracleCount === FORMAL_POST_COUNT ? ISSUED_CAPACITY_COUNT : 0,
  assignedCorpusDigest: state.target.assignedCorpus?.assignedCorpusDigest,
  boundary: {
    productMutationPerformed: false,
    externalNetworkAllowed: false,
    rendererReady: false,
    ticket19Closed: false,
  },
};
const payload = `${JSON.stringify(report, null, 2)}\n`;
if (outputPath) {
  mkdirSync(dirname(resolve(REPO, outputPath)), { recursive: true });
  writeFileSync(resolve(REPO, outputPath), payload, "utf8");
}
process.stdout.write(`${JSON.stringify(report)}\n`);
process.exitCode = report.status === "PASS" ? 0 : 1;
