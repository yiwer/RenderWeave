import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "..", "..", "..");
const SPEC_ROOT = ".scratch/renderweave-template-v1";
const EXECUTION_CLASS = "EXEC::DOMAIN_SERVICES::1.0";
const TARGET_VERSION = "renderweave-domain-services-capacity-issuance-target/1.0";
const TARGET_ID = "DOMAIN_SERVICES_ISSUANCE::CAPACITY::1.0";
const DEFAULT_TARGET = `${SPEC_ROOT}/domain-services/capacity-record-issuance-target-v1.json`;
const CASE_PATH = `${SPEC_ROOT}/conformance-cases-v1.jsonl`;
const ORACLE_PATH = `${SPEC_ROOT}/conformance-oracles-v1.jsonl`;

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

  const predecessorPath = `${SPEC_ROOT}/domain-services/execution-class-target-v1.json`;
  const predecessorBytes = gitBlob(target.implementationRevision, predecessorPath);
  const predecessor = strictJson(predecessorBytes, predecessorPath);
  check(JSON.stringify(target.predecessorProductTarget) === JSON.stringify(binding(predecessorPath, predecessorBytes)),
    "PREDECESSOR_TARGET_BINDING", predecessorPath);
  check(predecessor.assignedCorpus.assignedCorpusDigest === target.assignedCorpus.assignedCorpusDigest,
    "PREDECESSOR_ASSIGNED_DIGEST", predecessor.assignedCorpus.assignedCorpusDigest);

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
  check(assignedCases.length === 12 && assignedOracles.length === 12 && assignedOracleIds.size === 12,
    "ASSIGNED_COUNTS", `${assignedCases.length}/${assignedOracles.length}/${assignedOracleIds.size}`);
  check(target.assignedCorpus.caseBytesSha256 === digest(assignedCaseBytes), "ASSIGNED_CASE_BYTES", digest(assignedCaseBytes));
  check(target.assignedCorpus.oracleBytesSha256 === digest(assignedOracleBytes), "ASSIGNED_ORACLE_BYTES", digest(assignedOracleBytes));

  const baseCases = gitBlob(target.implementationRevision, target.prestate.formalCases.path);
  const baseOracles = gitBlob(target.implementationRevision, target.prestate.formalOracles.path);
  const formalCases = bytes(CASE_PATH);
  const formalOracles = bytes(ORACLE_PATH);
  check(JSON.stringify(target.prestate.formalCases) === JSON.stringify({ ...binding(CASE_PATH, baseCases), recordCount: 46 }),
    "PRESTATE_CASE_BINDING", digest(baseCases));
  check(JSON.stringify(target.prestate.formalOracles) === JSON.stringify({ ...binding(ORACLE_PATH, baseOracles), recordCount: 46 }),
    "PRESTATE_ORACLE_BINDING", digest(baseOracles));
  check(formalCases.equals(Buffer.concat([baseCases, assignedCaseBytes])), "FORMAL_CASE_EXACT_APPEND", digest(formalCases));
  check(formalOracles.equals(Buffer.concat([baseOracles, assignedOracleBytes])), "FORMAL_ORACLE_EXACT_APPEND", digest(formalOracles));
  check(target.poststate.formalCases.sha256 === digest(formalCases), "POSTSTATE_CASE_BINDING", digest(formalCases));
  check(target.poststate.formalOracles.sha256 === digest(formalOracles), "POSTSTATE_ORACLE_BINDING", digest(formalOracles));

  const formalCaseRows = jsonLines(formalCases, "formal cases");
  const formalOracleRows = jsonLines(formalOracles, "formal oracles");
  check(formalCaseRows.length === 58 && formalOracleRows.length === 58,
    "FORMAL_COUNTS", `${formalCaseRows.length}/${formalOracleRows.length}`);
  check(Buffer.concat(formalCaseRows.slice(46).map(({ raw }) => raw)).equals(assignedCaseBytes),
    "FORMAL_CASE_SUFFIX", digest(Buffer.concat(formalCaseRows.slice(46).map(({ raw }) => raw))));
  check(Buffer.concat(formalOracleRows.slice(46).map(({ raw }) => raw)).equals(assignedOracleBytes),
    "FORMAL_ORACLE_SUFFIX", digest(Buffer.concat(formalOracleRows.slice(46).map(({ raw }) => raw))));
  const caseIds = formalCaseRows.map(({ record }) => record.caseId);
  const oracleIds = formalOracleRows.map(({ record }) => record.oracleId);
  check(new Set(caseIds).size === caseIds.length, "CASE_ID_UNIQUE", caseIds.length);
  check(new Set(oracleIds).size === oracleIds.length, "ORACLE_ID_UNIQUE", oracleIds.length);

  const oracleById = new Map(formalOracleRows.map(({ record }) => [record.oracleId, record]));
  const referenced = new Set();
  for (const { record } of assignedCases) {
    check(record.suite === "CAPACITY_BOUNDARY" && record.executionClass === EXECUTION_CLASS,
      "DOMAIN_CASE_ROUTING", record.caseId);
    const requirementIds = record.coverage.map((edge) => edge.requirementId);
    check(JSON.stringify(requirementIds) === JSON.stringify(utf8Sort(requirementIds)),
      "DOMAIN_COVERAGE_ORDER", record.caseId);
    for (const coverage of record.coverage) {
      for (const evidence of coverage.evidence) {
        referenced.add(evidence.oracleId);
        const oracle = oracleById.get(evidence.oracleId);
        check(Boolean(oracle), "DOMAIN_ORACLE_REFERENCE", evidence.oracleId);
        if (oracle) {
          const assertionIds = new Set(oracle.assertions.map((assertion) => assertion.assertionId));
          check(evidence.assertionIds.every((id) => assertionIds.has(id)),
            "DOMAIN_ASSERTION_REFERENCE", `${record.caseId}:${evidence.oracleId}`);
        }
      }
    }
  }
  check(JSON.stringify(utf8Sort(referenced)) === JSON.stringify(utf8Sort(assignedOracleIds)),
    "DOMAIN_NO_ORPHAN_ORACLE", [...referenced]);

  const profile = json(`${SPEC_ROOT}/conformance-probe-profile-v1.json`);
  const probeById = new Map(profile.probes.map((probe) => [probe.probeId, probe]));
  for (const { record } of assignedOracles) {
    check(record.probeProfile === "renderweave-conformance-probes/1.0", "DOMAIN_ORACLE_PROFILE", record.oracleId);
    record.assertions.forEach((assertion, index) => {
      check(assertion.assertionId === `A${String(index + 1).padStart(3, "0")}`,
        "DOMAIN_ASSERTION_ID", `${record.oracleId}:${assertion.assertionId}`);
      const probe = probeById.get(assertion.probeId);
      check(Boolean(probe) && probe.executionClasses.includes(EXECUTION_CLASS) && probe.allowedOperators.includes(assertion.operator),
        "DOMAIN_ASSERTION_PROBE", `${record.oracleId}:${assertion.probeId}`);
    });
  }

  const executionCatalog = json(`${SPEC_ROOT}/conformance-execution-classes-v1.json`);
  const domainClass = executionCatalog.classes.find((entry) => entry.executionClass === EXECUTION_CLASS);
  check(domainClass?.status === "EXECUTABLE_A2_REPLAYED" && domainClass.caseRecordCount === 12 &&
    domainClass.oracleRecordCount === 12 && domainClass.executable === true,
  "DOMAIN_CLASS_EXECUTABLE", domainClass?.status);
  const bootstrap = json(`${SPEC_ROOT}/conformance-bootstrap-order-v1.json`);
  const step = bootstrap.steps.find((entry) => entry.executionClass === EXECUTION_CLASS);
  check(step?.assignedCorpusStatus === "ISSUED_12_CASES_12_ORACLES" && step.executable === true,
    "DOMAIN_BOOTSTRAP_EXECUTABLE", step?.assignedCorpusStatus);
  check(bootstrap.caseRegistryRecordCount === 58 && bootstrap.oracleRegistryRecordCount === 58 &&
    bootstrap.currentPhase === "CAPACITY_BOUNDARY", "BOOTSTRAP_FORMAL_COUNTS",
  `${bootstrap.caseRegistryRecordCount}/${bootstrap.oracleRegistryRecordCount}`);

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
  reportVersion: "renderweave-domain-services-postissuance-primary/1",
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
  issuedDomainCaseCount: state.formalCaseCount === 58 ? 12 : 0,
  issuedDomainOracleCount: state.formalOracleCount === 58 ? 12 : 0,
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
