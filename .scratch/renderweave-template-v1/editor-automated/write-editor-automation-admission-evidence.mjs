import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import process from "node:process";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");
const WORKTREE = resolve(SPEC, "..", "..");
const EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0";
const SOURCE_REVISION = "b14c2d7d4978c679e7ab8e7a2bace3da7af884de";
const EVIDENCE_PATH = "editor-automated/editor-automation-admission-static-a2-2026-08-17.json";

function bytes(relativePath, base = SPEC) {
  return readFileSync(resolve(base, relativePath));
}

function json(relativePath, base = SPEC) {
  return JSON.parse(bytes(relativePath, base).toString("utf8"));
}

function sha256(value) {
  return `sha256:${createHash("sha256").update(value).digest("hex")}`;
}

function artifact(relativePath, base = SPEC) {
  const value = bytes(relativePath, base);
  return { path: relativePath.replaceAll("\\", "/"), sha256: sha256(value), byteLength: value.length };
}

function writeJson(relativePath, value) {
  writeFileSync(resolve(SPEC, relativePath), Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8"));
}

const contract = json("editor-automated/execution-admission-contract-v1.json");
const assignment = json("editor-automated/non-capacity-assignment-v1.json");
const audit = json("editor-automated/repository-readiness-audit-v1.json");
const primary = json("editor-automated/admission-primary-result-v1.json");
const independent = json("editor-automated/admission-independent-result-v1.json");

if (primary.failureCount !== 0 || independent.failureCount !== 0) throw new Error("admission static replay failed");
if (primary.contract.sha256 !== independent.contract.sha256) throw new Error("contract digest disagreement");
if (primary.assignment.sha256 !== independent.assignment.sha256) throw new Error("assignment digest disagreement");
if (primary.readinessAudit.sha256 !== independent.readinessAudit.sha256) throw new Error("audit digest disagreement");
if (audit.admissionDecision.result !== "REJECTED") throw new Error("repository audit unexpectedly admitted");
if (assignment.issuance.formalRecordIssuanceAllowed !== false) throw new Error("assignment unexpectedly issueable");
if (contract.issuanceBoundary.executableClaimBeforeIndependentReplay !== false) throw new Error("contract executable boundary drifted");

const evidence = {
  evidenceVersion: "renderweave-editor-automation-admission-static-a2/1.0",
  evidenceId: "EDITOR_AUTOMATION_ADMISSION_STATIC_A2::2026-08-17::000001",
  status: "A2_STATIC_ADMISSION_INTERFACE_REPLAYED_PRODUCT_ADMISSION_REJECTED",
  grade: "A2_INDEPENDENT_STATIC_REPLAY_ONLY",
  executionClass: EXECUTION_CLASS,
  sourceRevision: SOURCE_REVISION,
  conclusion: {
    admissionResult: audit.admissionDecision.result,
    rejectionCodes: audit.admissionDecision.rejectionCodes,
    assignedRequirementCount: assignment.counts.assignedRequirementCount,
    routingGroupCount: assignment.counts.routingGroupCount,
    journeySeedCount: assignment.counts.automatedJourneySeedCount,
    admittedProbeCount: assignment.classProbeSet.probeCount,
    formalEditorCaseCount: assignment.counts.formalCaseCount,
    formalEditorOracleCount: assignment.counts.formalOracleCount,
    exactTargetManifestIssued: false,
    automationRunnerManifestIssued: false,
    productExecutionEvidence: false,
    browserAutomationEvidence: false,
    recordIssuanceAllowed: false,
    executable: false
  },
  staticInterfaceArtifacts: {
    executionAdmissionContract: artifact("editor-automated/execution-admission-contract-v1.json"),
    nonCapacityAssignment: artifact("editor-automated/non-capacity-assignment-v1.json"),
    repositoryReadinessAudit: artifact("editor-automated/repository-readiness-audit-v1.json")
  },
  authorityBindings: {
    requirementsRegistry: artifact("requirements-v1.json"),
    j1Checklist: artifact("j1-editor-checklist-v1.json"),
    probeProfile: artifact("conformance-probe-profile-v1.json"),
    observationAdapter: artifact("editor-automated/observation-adapter-v1.json"),
    webPackage: artifact("web/package.json", WORKTREE),
    webPackageLock: artifact("web/package-lock.json", WORKTREE),
    playwrightConfig: artifact("web/playwright.config.ts", WORKTREE)
  },
  executors: [
    {
      executorId: primary.executorId,
      role: primary.role,
      runtime: process.version,
      entrypoint: artifact("editor-automated/generate-editor-automation-admission.mjs"),
      result: artifact("editor-automated/admission-primary-result-v1.json"),
      checkCount: primary.checkCount,
      failureCount: primary.failureCount
    },
    {
      executorId: independent.executorId,
      role: independent.role,
      runtime: "CPython independent implementation",
      entrypoint: artifact("editor-automated/validate_editor_automation_admission_independent.py"),
      result: artifact("editor-automated/admission-independent-result-v1.json"),
      checkCount: independent.checkCount,
      failureCount: independent.failureCount
    }
  ],
  replayAgreement: {
    contractSha256: primary.contract.sha256,
    assignmentSha256: primary.assignment.sha256,
    readinessAuditSha256: primary.readinessAudit.sha256,
    assignedRequirementCount: primary.assignedRequirementCount,
    bothRejectedProductAdmission: true,
    sharedSemanticLibrary: null
  },
  evidenceBoundary: {
    mayProve: "the Editor admission seam, exact target and runner fact requirements, closed rejection codes, complete routing inventory, 12 automation journey seeds, and the repository's current fail-closed admission decision",
    cannotProve: "a supported-browser policy, product build, browser binary, OS target, environment profile, automation runner, formal Editor Case or Oracle, browser behavior, product behavior, accessibility semantics, independent product replay, J1, execution-class readiness, Renderer readiness, or Ticket19 closure",
    browserLaunched: false,
    webServerLaunched: false,
    productCodeExecuted: false,
    productBuildExecuted: false,
    networkRead: false,
    j1Evaluated: false,
    formalRegistryMutated: false
  }
};

writeJson(EVIDENCE_PATH, evidence);
process.stdout.write(`${JSON.stringify({ status: evidence.status, evidence: artifact(EVIDENCE_PATH), checks: primary.checkCount + independent.checkCount })}\n`);
