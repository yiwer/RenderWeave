import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "..", "..", "..");
const SPEC_ROOT = ".scratch/renderweave-template-v1";
const EXECUTION_CLASS = "EXEC::DESIGN_INPUT_EXPRESSION::1.0";
const TARGET_VERSION = "renderweave-design-input-expression-capacity-issuance-target/1.0";
const TARGET_ID = "DESIGN_INPUT_EXPRESSION_ISSUANCE::CAPACITY::1.0";
const DEFAULT_TARGET = `${SPEC_ROOT}/design-input-expression/capacity-record-issuance-target-v1.json`;
const CASE_PATH = `${SPEC_ROOT}/conformance-cases-v1.jsonl`;
const ORACLE_PATH = `${SPEC_ROOT}/conformance-oracles-v1.jsonl`;
const SPEC_COUNT = 46;
const DOMAIN_COUNT = 12;
const FORMAL_PREFIX_COUNT = SPEC_COUNT + DOMAIN_COUNT;
const ASSIGNED_COUNT = 195;
const FORMAL_POST_COUNT = FORMAL_PREFIX_COUNT + ASSIGNED_COUNT;
const ISSUED_CAPACITY_COUNT = DOMAIN_COUNT + ASSIGNED_COUNT;

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
    maxBuffer: 16 * 1024 * 1024,
    windowsHide: true,
  });
  if (result.status !== 0) {
    throw new Error(`git show failed for ${revision}:${path}`);
  }
  return result.stdout;
}

function utf8Sort(values) {
  return [...values].sort((left, right) => Buffer.compare(Buffer.from(left, "utf8"), Buffer.from(right, "utf8")));
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
    check(typeof artifact.path === "string" && !artifactPaths.has(artifact.path), "TARGET_ARTIFACT_PATH", artifact.path);
    artifactPaths.add(artifact.path);
    const value = gitBlob(target.implementationRevision, artifact.path);
    check(JSON.stringify(artifact) === JSON.stringify(binding(artifact.path, value)), "TARGET_ARTIFACT_BINDING", artifact.path);
  }

  const predecessorPath = `${SPEC_ROOT}/design-input-expression/execution-class-target-v1.json`;
  const predecessorBytes = gitBlob(target.implementationRevision, predecessorPath);
  const predecessor = strictJson(predecessorBytes, predecessorPath);
  check(JSON.stringify(target.predecessorProductTarget) === JSON.stringify(binding(predecessorPath, predecessorBytes)),
    "PREDECESSOR_TARGET_BINDING", predecessorPath);
  check(predecessor.assignedCorpus.assignedCorpusDigest === target.assignedCorpus.assignedCorpusDigest,
    "PREDECESSOR_ASSIGNED_DIGEST", predecessor.assignedCorpus.assignedCorpusDigest);
  check(predecessor.assignedCorpus.assignedCaseCount === ASSIGNED_COUNT &&
    predecessor.assignedCorpus.assignedOracleCount === ASSIGNED_COUNT,
  "PREDECESSOR_ASSIGNED_COUNTS", `${predecessor.assignedCorpus.assignedCaseCount}/${predecessor.assignedCorpus.assignedOracleCount}`);

  for (const manifest of target.requiredExecutorManifests) {
    const value = gitBlob(target.implementationRevision, manifest.path);
    const parsed = strictJson(value, manifest.path);
    check(JSON.stringify(manifest) === JSON.stringify(binding(manifest.path, value)),
      "EXECUTOR_MANIFEST_BINDING", manifest.path);
    check(parsed.executionClass === EXECUTION_CLASS && parsed.targetManifest.sha256 === target.predecessorProductTarget.sha256,
      "EXECUTOR_MANIFEST_TARGET", manifest.path);
  }

  const candidateCasePath = target.assignedCorpus.sourceCases.path;
  const candidateOraclePath = target.assignedCorpus.sourceOracles.path;
  const candidateCases = gitBlob(target.implementationRevision, candidateCasePath);
  const candidateOracles = gitBlob(target.implementationRevision, candidateOraclePath);
  check(JSON.stringify(target.assignedCorpus.sourceCases) === JSON.stringify(binding(candidateCasePath, candidateCases)),
    "CANDIDATE_CASE_BINDING", candidateCasePath);
  check(JSON.stringify(target.assignedCorpus.sourceOracles) === JSON.stringify(binding(candidateOraclePath, candidateOracles)),
    "CANDIDATE_ORACLE_BINDING", candidateOraclePath);
  const candidateCaseRows = jsonLines(candidateCases, "candidate cases");
  const candidateOracleRows = jsonLines(candidateOracles, "candidate oracles");
  const assignedCases = candidateCaseRows.filter(({ record }) => record.executionClass === EXECUTION_CLASS);
  const assignedOracleIds = new Set(assignedCases.flatMap(({ record }) =>
    record.coverage.flatMap((coverage) => coverage.evidence.map((evidence) => evidence.oracleId))));
  const assignedOracles = candidateOracleRows.filter(({ record }) => assignedOracleIds.has(record.oracleId));
  const assignedCaseBytes = Buffer.concat(assignedCases.map(({ raw }) => raw));
  const assignedOracleBytes = Buffer.concat(assignedOracles.map(({ raw }) => raw));
  check(assignedCases.length === ASSIGNED_COUNT && assignedOracles.length === ASSIGNED_COUNT &&
    assignedOracleIds.size === ASSIGNED_COUNT,
    "ASSIGNED_COUNTS", `${assignedCases.length}/${assignedOracles.length}/${assignedOracleIds.size}`);
  check(target.assignedCorpus.caseBytesSha256 === digest(assignedCaseBytes), "ASSIGNED_CASE_BYTES", digest(assignedCaseBytes));
  check(target.assignedCorpus.oracleBytesSha256 === digest(assignedOracleBytes), "ASSIGNED_ORACLE_BYTES", digest(assignedOracleBytes));
  check(target.assignedCorpus.caseCount === ASSIGNED_COUNT && target.assignedCorpus.oracleCount === ASSIGNED_COUNT,
    "TARGET_ASSIGNED_COUNTS", `${target.assignedCorpus.caseCount}/${target.assignedCorpus.oracleCount}`);
  check(JSON.stringify(target.assignedCorpus.caseIds) === JSON.stringify(assignedCases.map(({ record }) => record.caseId)),
    "TARGET_ASSIGNED_CASE_IDS", target.assignedCorpus.caseIds.length);
  check(JSON.stringify(target.assignedCorpus.oracleIds) === JSON.stringify(assignedOracles.map(({ record }) => record.oracleId)),
    "TARGET_ASSIGNED_ORACLE_IDS", target.assignedCorpus.oracleIds.length);
  const assignedCorpusDigest = digest(Buffer.concat([
    Buffer.from("renderweave-design-input-expression-assigned-corpus/1\0", "utf8"),
    assignedCaseBytes,
    Buffer.from([0]),
    assignedOracleBytes,
  ]));
  check(target.assignedCorpus.assignedCorpusDigest === assignedCorpusDigest,
    "TARGET_ASSIGNED_CORPUS_DIGEST", assignedCorpusDigest);

  const baseCases = gitBlob(target.implementationRevision, target.prestate.formalCases.path);
  const baseOracles = gitBlob(target.implementationRevision, target.prestate.formalOracles.path);
  const previousIssuancePath = target.prestate.previousCapacityIssuance.path;
  const previousIssuanceBytes = gitBlob(target.implementationRevision, previousIssuancePath);
  const previousIssuance = strictJson(previousIssuanceBytes, previousIssuancePath);
  check(JSON.stringify(target.prestate.previousCapacityIssuance) ===
    JSON.stringify(binding(previousIssuancePath, previousIssuanceBytes)),
  "PREVIOUS_ISSUANCE_BINDING", previousIssuancePath);
  check(previousIssuance.executionClass === "EXEC::DOMAIN_SERVICES::1.0" &&
    previousIssuance.poststate.formalCases.sha256 === digest(baseCases) &&
    previousIssuance.poststate.formalOracles.sha256 === digest(baseOracles),
  "PREVIOUS_ISSUANCE_POSTSTATE", previousIssuance.executionClass);
  const specCases = gitBlob(target.implementationRevision,
    `${SPEC_ROOT}/spec-registry/candidate/conformance-cases-v1.jsonl`);
  const specOracles = gitBlob(target.implementationRevision,
    `${SPEC_ROOT}/spec-registry/candidate/conformance-oracles-v1.jsonl`);
  check(baseCases.subarray(0, specCases.length).equals(specCases), "SPEC_CASE_PREFIX", digest(specCases));
  check(baseOracles.subarray(0, specOracles.length).equals(specOracles), "SPEC_ORACLE_PREFIX", digest(specOracles));
  check(digest(baseCases.subarray(specCases.length)) === previousIssuance.assignedCorpus.caseBytesSha256,
    "DOMAIN_CASE_SUFFIX", digest(baseCases.subarray(specCases.length)));
  check(digest(baseOracles.subarray(specOracles.length)) === previousIssuance.assignedCorpus.oracleBytesSha256,
    "DOMAIN_ORACLE_SUFFIX", digest(baseOracles.subarray(specOracles.length)));
  const formalCases = bytes(CASE_PATH);
  const formalOracles = bytes(ORACLE_PATH);
  check(JSON.stringify(target.prestate.formalCases) === JSON.stringify({
    ...binding(CASE_PATH, baseCases), recordCount: FORMAL_PREFIX_COUNT,
  }),
    "PRESTATE_CASE_BINDING", digest(baseCases));
  check(JSON.stringify(target.prestate.formalOracles) === JSON.stringify({
    ...binding(ORACLE_PATH, baseOracles), recordCount: FORMAL_PREFIX_COUNT,
  }),
    "PRESTATE_ORACLE_BINDING", digest(baseOracles));
  check(formalCases.equals(Buffer.concat([baseCases, assignedCaseBytes])), "FORMAL_CASE_EXACT_APPEND", digest(formalCases));
  check(formalOracles.equals(Buffer.concat([baseOracles, assignedOracleBytes])), "FORMAL_ORACLE_EXACT_APPEND", digest(formalOracles));
  check(JSON.stringify(target.poststate.formalCases) === JSON.stringify({
    ...binding(CASE_PATH, formalCases), recordCount: FORMAL_POST_COUNT, preservedPrefixSha256: digest(baseCases),
  }), "POSTSTATE_CASE_BINDING", digest(formalCases));
  check(JSON.stringify(target.poststate.formalOracles) === JSON.stringify({
    ...binding(ORACLE_PATH, formalOracles), recordCount: FORMAL_POST_COUNT, preservedPrefixSha256: digest(baseOracles),
  }), "POSTSTATE_ORACLE_BINDING", digest(formalOracles));

  const formalCaseRows = jsonLines(formalCases, "formal cases");
  const formalOracleRows = jsonLines(formalOracles, "formal oracles");
  check(formalCaseRows.length === FORMAL_POST_COUNT && formalOracleRows.length === FORMAL_POST_COUNT,
    "FORMAL_COUNTS", `${formalCaseRows.length}/${formalOracleRows.length}`);
  check(Buffer.concat(formalCaseRows.slice(FORMAL_PREFIX_COUNT).map(({ raw }) => raw)).equals(assignedCaseBytes),
    "FORMAL_CASE_SUFFIX", digest(Buffer.concat(formalCaseRows.slice(FORMAL_PREFIX_COUNT).map(({ raw }) => raw))));
  check(Buffer.concat(formalOracleRows.slice(FORMAL_PREFIX_COUNT).map(({ raw }) => raw)).equals(assignedOracleBytes),
    "FORMAL_ORACLE_SUFFIX", digest(Buffer.concat(formalOracleRows.slice(FORMAL_PREFIX_COUNT).map(({ raw }) => raw))));
  const caseIds = formalCaseRows.map(({ record }) => record.caseId);
  const oracleIds = formalOracleRows.map(({ record }) => record.oracleId);
  check(new Set(caseIds).size === caseIds.length, "CASE_ID_UNIQUE", caseIds.length);
  check(new Set(oracleIds).size === oracleIds.length, "ORACLE_ID_UNIQUE", oracleIds.length);

  const oracleById = new Map(formalOracleRows.map(({ record }) => [record.oracleId, record]));
  const referenced = new Set();
  for (const { record } of assignedCases) {
    check(record.suite === "CAPACITY_BOUNDARY" && record.executionClass === EXECUTION_CLASS,
      "DESIGN_CASE_ROUTING", record.caseId);
    const requirementIds = record.coverage.map((edge) => edge.requirementId);
    check(JSON.stringify(requirementIds) === JSON.stringify(utf8Sort(requirementIds)),
      "DESIGN_COVERAGE_ORDER", record.caseId);
    for (const coverage of record.coverage) {
      for (const evidence of coverage.evidence) {
        referenced.add(evidence.oracleId);
        const oracle = oracleById.get(evidence.oracleId);
        check(Boolean(oracle), "DESIGN_ORACLE_REFERENCE", evidence.oracleId);
        if (oracle) {
          const assertionIds = new Set(oracle.assertions.map((assertion) => assertion.assertionId));
          check(evidence.assertionIds.every((id) => assertionIds.has(id)),
            "DESIGN_ASSERTION_REFERENCE", `${record.caseId}:${evidence.oracleId}`);
        }
      }
    }
  }
  check(JSON.stringify(utf8Sort(referenced)) === JSON.stringify(utf8Sort(assignedOracleIds)),
    "DESIGN_NO_ORPHAN_ORACLE", [...referenced]);

  const profile = json(`${SPEC_ROOT}/conformance-probe-profile-v1.json`);
  const probeById = new Map(profile.probes.map((probe) => [probe.probeId, probe]));
  for (const { record } of assignedOracles) {
    check(record.probeProfile === "renderweave-conformance-probes/1.0", "DESIGN_ORACLE_PROFILE", record.oracleId);
    record.assertions.forEach((assertion, index) => {
      check(assertion.assertionId === `A${String(index + 1).padStart(3, "0")}`,
        "DESIGN_ASSERTION_ID", `${record.oracleId}:${assertion.assertionId}`);
      const probe = probeById.get(assertion.probeId);
      check(Boolean(probe) && probe.executionClasses.includes(EXECUTION_CLASS) && probe.allowedOperators.includes(assertion.operator),
        "DESIGN_ASSERTION_PROBE", `${record.oracleId}:${assertion.probeId}`);
    });
  }

  const executionCatalog = json(`${SPEC_ROOT}/conformance-execution-classes-v1.json`);
  const designClass = executionCatalog.classes.find((entry) => entry.executionClass === EXECUTION_CLASS);
  const domainClass = executionCatalog.classes.find((entry) => entry.executionClass === "EXEC::DOMAIN_SERVICES::1.0");
  check(designClass?.status === "EXECUTABLE_A2_REPLAYED" && designClass.caseRecordCount === ASSIGNED_COUNT &&
    designClass.oracleRecordCount === ASSIGNED_COUNT && designClass.executable === true,
  "DESIGN_CLASS_EXECUTABLE", designClass?.status);
  check(domainClass?.status === "EXECUTABLE_A2_REPLAYED" && domainClass.caseRecordCount === DOMAIN_COUNT &&
    domainClass.oracleRecordCount === DOMAIN_COUNT && domainClass.executable === true,
  "DOMAIN_PREDECESSOR_EXECUTABLE", domainClass?.status);
  const bootstrap = json(`${SPEC_ROOT}/conformance-bootstrap-order-v1.json`);
  const step = bootstrap.steps.find((entry) => entry.executionClass === EXECUTION_CLASS);
  check(step?.assignedCorpusStatus === "ISSUED_195_CASES_195_ORACLES" && step.executable === true,
    "DESIGN_BOOTSTRAP_EXECUTABLE", step?.assignedCorpusStatus);
  check(bootstrap.caseRegistryRecordCount === FORMAL_POST_COUNT &&
    bootstrap.oracleRegistryRecordCount === FORMAL_POST_COUNT &&
    bootstrap.currentPhase === "CAPACITY_BOUNDARY", "BOOTSTRAP_FORMAL_COUNTS",
  `${bootstrap.caseRegistryRecordCount}/${bootstrap.oracleRegistryRecordCount}`);
  const acceptance = json(`${SPEC_ROOT}/acceptance-manifest-v1.json`);
  const designAcceptance = acceptance.conformanceRegistries.designInputExpressionFixtureBootstrap;
  check(designAcceptance.status === "EXECUTABLE_A2_REPLAYED" &&
    designAcceptance.formalCapacityRecordCount === ASSIGNED_COUNT && designAcceptance.executable === true,
  "DESIGN_ACCEPTANCE_EXECUTABLE", designAcceptance.status);
  check(acceptance.counts.issuedCapacityBoundaryCases === ISSUED_CAPACITY_COUNT &&
    acceptance.counts.issuedCapacityBoundaryOracles === ISSUED_CAPACITY_COUNT &&
    acceptance.counts.executableContractBoundaryCases === ISSUED_CAPACITY_COUNT,
  "ACCEPTANCE_CAPACITY_COUNTS", JSON.stringify(acceptance.counts));
  check(executionCatalog.capacityBoundaryMaterialization.formalCapacityCaseCount === ISSUED_CAPACITY_COUNT &&
    executionCatalog.capacityBoundaryMaterialization.formalCapacityOracleCount === ISSUED_CAPACITY_COUNT,
  "CATALOG_CAPACITY_COUNTS", JSON.stringify(executionCatalog.capacityBoundaryMaterialization));
  const phase = bootstrap.recordIssuancePhases.find((entry) => entry.phase === "CAPACITY_BOUNDARY");
  check(phase?.formalIssuedCaseCount === ISSUED_CAPACITY_COUNT &&
    phase?.formalIssuedOracleCount === ISSUED_CAPACITY_COUNT,
  "BOOTSTRAP_CAPACITY_COUNTS", JSON.stringify(phase));
  const specTarget = json(`${SPEC_ROOT}/spec-registry/target-manifest-v1.json`);
  const issuance = specTarget.registryBindings.appendOnlyIssuance;
  check(specTarget.implementationRevision === "spec-registry-bootstrap/1.15" &&
    issuance.appendedExecutionClass === EXECUTION_CLASS && issuance.appendedCaseCount === ASSIGNED_COUNT &&
    issuance.appendedOracleCount === ASSIGNED_COUNT &&
    issuance.assignedCorpusDigest === target.assignedCorpus.assignedCorpusDigest,
  "SPEC_TARGET_ISSUANCE", specTarget.implementationRevision);
  check(issuance.target.path === targetPath.replace(`${SPEC_ROOT}/`, "") &&
    issuance.target.sha256 === digest(targetBytes) && issuance.target.byteLength === targetBytes.length &&
    issuance.predecessorIssuance.appendedExecutionClass === "EXEC::DOMAIN_SERVICES::1.0",
  "SPEC_TARGET_ISSUANCE_CHAIN", issuance.target.path);

  return {
    targetBytes,
    target,
    formalCaseCount: formalCaseRows.length,
    formalOracleCount: formalOracleRows.length,
  };
}

let state = { targetBytes: Buffer.alloc(0), target: {}, formalCaseCount: 0, formalOracleCount: 0 };
try {
  state = replay();
} catch (error) {
  failures.push({ code: "UNEXPECTED_REPLAY_FAILURE", detail: String(error.message || error) });
}
const report = {
  reportVersion: "renderweave-design-input-expression-postissuance-primary/1",
  engine: "node-primary-registry-validator",
  runtime: process.version,
  status: failures.length === 0 ? "PASS" : "FAIL",
  checkCount,
  failureCount: failures.length,
  failures,
  targetManifest: binding(targetPath, state.targetBytes),
  implementationRevision: state.target.implementationRevision ?? null,
  executionClass: EXECUTION_CLASS,
  formalCaseCount: state.formalCaseCount,
  formalOracleCount: state.formalOracleCount,
  issuedDesignInputExpressionCaseCount: state.formalCaseCount === FORMAL_POST_COUNT ? ASSIGNED_COUNT : 0,
  issuedDesignInputExpressionOracleCount: state.formalOracleCount === FORMAL_POST_COUNT ? ASSIGNED_COUNT : 0,
  issuedCapacityCaseCount: state.formalCaseCount === FORMAL_POST_COUNT ? ISSUED_CAPACITY_COUNT : 0,
  issuedCapacityOracleCount: state.formalOracleCount === FORMAL_POST_COUNT ? ISSUED_CAPACITY_COUNT : 0,
  assignedCorpusDigest: state.target.assignedCorpus?.assignedCorpusDigest ?? null,
  boundary: {
    productMutationPerformed: false,
    externalNetworkAllowed: false,
    rendererReady: false,
    ticket19Closed: false,
  },
};
const pretty = `${JSON.stringify(report, null, 2)}\n`;
if (outputPath) {
  mkdirSync(dirname(resolve(REPO, outputPath)), { recursive: true });
  writeFileSync(resolve(REPO, outputPath), pretty, "utf8");
}
process.stdout.write(`${JSON.stringify(report)}\n`);
process.exitCode = report.status === "PASS" ? 0 : 1;
