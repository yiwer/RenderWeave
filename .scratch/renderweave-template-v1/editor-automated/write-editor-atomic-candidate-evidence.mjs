import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const SPEC = resolve(import.meta.dirname, "..");
const ROOT = "editor-automated";
const OUTPUT = `${ROOT}/editor-atomic-candidates-static-a2-2026-08-17.json`;

function raw(relativePath) {
  return readFileSync(resolve(SPEC, relativePath));
}

function json(relativePath) {
  return JSON.parse(raw(relativePath).toString("utf8"));
}

function sha(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function artifact(relativePath) {
  const bytes = raw(relativePath);
  return { path: relativePath, sha256: sha(bytes), byteLength: bytes.length };
}

function recordCount(relativePath) {
  return raw(relativePath).toString("utf8").split(/\r?\n/u).filter((line) => line.length > 0).length;
}

const primary = json(`${ROOT}/atomic-candidate-primary-result-v1.json`);
const independent = json(`${ROOT}/atomic-candidate-independent-result-v1.json`);
const audit = json(`${ROOT}/atomic-candidate-readiness-audit-v1.json`);
const formalCaseCount = recordCount("conformance-cases-v1.jsonl");
const formalOracleCount = recordCount("conformance-oracles-v1.jsonl");
if (primary.status !== "PASS_STATIC_CANDIDATE_DECOMPOSITION" || primary.failureCount !== 0) throw new Error("primary result is not a clean pass");
if (independent.status !== "PASS_STATIC_CANDIDATE_DECOMPOSITION" || independent.failureCount !== 0) throw new Error("independent result is not a clean pass");
if (audit.status !== "ONE_CONTENT_SOURCE_AND_DETERMINISTIC_TARGETS_BOUND_PREISSUANCE_BLOCKED" || audit.decision.formalRecordIssuanceAllowed !== false) throw new Error("audit boundary drifted");
if (audit.counts.candidateCount !== 108 || audit.counts.plannedRequirementUnionCount !== 138 || audit.counts.assertionPlanCount !== 1265) throw new Error("candidate inventory drifted");
if (audit.counts.exactLiteralAssertionPlanCount !== 749 || audit.counts.exactArtifactAssertionPlanCount !== 108 || audit.counts.exactAbsentAssertionPlanCount !== 289 || audit.counts.exactExpectationAssertionPlanCount !== 1146 || audit.counts.pendingExpectationAssertionPlanCount !== 119) throw new Error("candidate expectation inventory drifted");
if (audit.counts.inputFixtureArtifactBindingCount !== 108 || audit.counts.targetArtifactBindingCount !== 108 || audit.counts.targetLiteralAdjudicatedFromPendingCount !== 81) throw new Error("input fixture or target binding inventory drifted");
if (audit.counts.semanticProjectionExactLiteralBindingCount !== 42 || audit.counts.semanticProjectionContentPrerequisiteCount !== 58 || audit.counts.contentPrerequisiteResolvedBySourceCount !== 1 || audit.counts.contentPrerequisiteRemainingCount !== 57 || audit.counts.contentSourceSlotCount !== 47 || audit.counts.contentSourceExactBindingCount !== 1 || audit.counts.contentSourceUnboundBindingCount !== 46 || audit.counts.contentSourceRecordArtifactCount !== 1 || audit.counts.contentSourceCanonicalDesignDslArtifactCount !== 2 || audit.counts.uiObservationPendingCount !== 62) throw new Error("semantic projection or content source inventory drifted");
if (audit.counts.terminalAdjudicatedFromPendingCount !== 33 || audit.counts.pendingTerminalBindingCount !== 0 || audit.counts.faultArtifactBindingCount !== 37 || audit.counts.faultNoneBindingCount !== 71) throw new Error("terminal or fault binding inventory drifted");
if (audit.counts.proposedProbeCount !== 0 || audit.counts.candidateProfileBindingAssertionCount !== 109 || audit.counts.candidateProfileBoundCandidateCount !== 82) throw new Error("candidate probe binding inventory drifted");
if (audit.counts.formalCaseCount !== formalCaseCount || audit.counts.formalOracleCount !== formalOracleCount || formalCaseCount !== formalOracleCount) throw new Error("global formal registry count drifted");
if (audit.counts.formalEditorCaseCount !== 0 || audit.counts.formalEditorOracleCount !== 0) throw new Error("Editor formal namespace drifted");

const evidence = {
  evidenceVersion: "renderweave-editor-atomic-candidates-static-evidence/1.5",
  evidenceId: "editor-atomic-candidates-static-a2-2026-08-17",
  capturedAt: "2026-08-17T00:00:00+08:00",
  evidenceLevel: "A2_STATIC",
  result: "A2_STATIC_ONE_CONTENT_SOURCE_AND_189_DETERMINISTIC_TARGETS_REPLAYED_46_SOURCE_SLOTS_UNBOUND_119_TARGETS_PENDING",
  scope: "candidate-only decomposition of the twelve frozen Editor J1 journey seeds, including immutable semantic input fixtures, exact planning terminal and fault bindings, 108 terminal-vector target artifacts, 38 network target literals, 22 command-result codes, 20 preview-generation literals, two exact no-working-copy absences, and one Editor-owned immutable dirty-guard content source; 46 first-layer source slots, 57 content-derived targets, and 62 UI observations remain fail-closed",
  targetArtifacts: {
    contract: artifact(`${ROOT}/atomic-candidate-contract-v1.json`),
    candidates: artifact(`${ROOT}/atomic-scenario-candidates-v1.json`),
    audit: artifact(`${ROOT}/atomic-candidate-readiness-audit-v1.json`),
    sourceAssignment: artifact(`${ROOT}/non-capacity-assignment-v1.json`),
    probeProfile: artifact("conformance-probe-profile-v1.json"),
    candidateProbeProfile: artifact(`${ROOT}/probe-profile-candidate-v1_1.json`),
    candidateProbeAdjudication: artifact(`${ROOT}/probe-profile-adjudication-v1.json`),
    candidateProbeProfileEvidence: artifact(`${ROOT}/editor-probe-profile-candidate-static-a2-2026-08-17.json`),
    terminalAdjudication: artifact(`${ROOT}/terminal-adjudication-v1.json`),
    faultScheduleContract: artifact(`${ROOT}/fault-schedule-contract-v1.json`),
    faultScheduleCatalog: artifact(`${ROOT}/fault-schedule-catalog-v1.json`),
    inputFixtureContract: artifact(`${ROOT}/input-fixture-contract-v1.json`),
    inputFixtureCatalog: artifact(`${ROOT}/input-fixture-catalog-v1.json`),
    semanticProjectionContract: artifact(`${ROOT}/semantic-projection-contract-v1.json`),
    semanticProjectionCatalog: artifact(`${ROOT}/semantic-projection-catalog-v1.json`),
    contentSourceContract: artifact(`${ROOT}/content-source-contract-v1.json`),
    contentSourceCatalog: artifact(`${ROOT}/content-source-catalog-v1.json`),
    targetBindingContract: artifact(`${ROOT}/target-binding-contract-v1.json`),
    targetBindingCatalog: artifact(`${ROOT}/target-binding-catalog-v1.json`),
    formalCases: artifact("conformance-cases-v1.jsonl"),
    formalOracles: artifact("conformance-oracles-v1.jsonl")
  },
  implementations: {
    primaryGeneratorAndValidator: artifact(`${ROOT}/generate-editor-atomic-candidates.mjs`),
    independentValidator: artifact(`${ROOT}/validate_editor_atomic_candidates_independent.py`)
  },
  results: {
    primary: artifact(`${ROOT}/atomic-candidate-primary-result-v1.json`),
    independent: artifact(`${ROOT}/atomic-candidate-independent-result-v1.json`)
  },
  observed: {
    journeySeedCount: 12,
    candidateCount: 108,
    plannedRequirementUnionCount: 138,
    assertionPlanCount: 1265,
    exactLiteralAssertionPlanCount: 749,
    exactArtifactAssertionPlanCount: 108,
    exactAbsentAssertionPlanCount: 289,
    exactExpectationAssertionPlanCount: 1146,
    pendingExpectationAssertionPlanCount: 119,
    inputFixtureArtifactBindingCount: 108,
    targetArtifactBindingCount: 108,
    targetLiteralAdjudicatedFromPendingCount: 81,
    semanticProjectionExactLiteralBindingCount: 42,
    semanticProjectionContentPrerequisiteCount: 58,
    contentPrerequisiteResolvedBySourceCount: 1,
    contentPrerequisiteRemainingCount: 57,
    contentSourceSlotCount: 47,
    contentSourceExactBindingCount: 1,
    contentSourceUnboundBindingCount: 46,
    contentSourceRecordArtifactCount: 1,
    contentSourceCanonicalDesignDslArtifactCount: 2,
    uiObservationPendingCount: 62,
    terminalAdjudicatedFromPendingCount: 33,
    pendingTerminalBindingCount: 0,
    faultArtifactBindingCount: 37,
    faultNoneBindingCount: 71,
    proposedProbeCount: 0,
    candidateProfileProbeUsageCount: 9,
    candidateProfileBindingAssertionCount: 109,
    candidateProfileBoundCandidateCount: 82,
    primaryCheckCount: primary.checkCount,
    independentCheckCount: independent.checkCount,
    formalCaseCount,
    formalOracleCount,
    formalEditorCaseCount: 0,
    formalEditorOracleCount: 0
  },
  negativeTerminals: audit.blockers,
  sealedBoundaries: {
    planningCandidatesOnly: true,
    editorFormalCaseOrOracleIssued: false,
    editorConformanceJsonlAppended: false,
    globalDomainServicesCapacityRecordsPresent: true,
    currentProbeProfileMutated: false,
    candidateProbeProfileComplete: true,
    candidateProbeProfileIssued: false,
    terminalBindingsExactAtPlanningBoundary: true,
    faultScheduleArtifactsBoundAtPlanningBoundary: true,
    semanticInputFixtureArtifactsBoundAtPlanningBoundary: true,
    semanticProjectionLiteralsBoundAtPlanningBoundary: true,
    noTrustedWorkingCopyModesBoundAbsent: true,
    contentSourceInterfaceFrozen: true,
    contentSourceArtifactsBound: true,
    contentSourceBindingIsSpecFixtureOnly: true,
    contentDerivedPrerequisitesRemainPending: true,
    uiObservedTargetsRemainPending: true,
    deterministicTargetBindingsBoundAtPlanningBoundary: true,
    underdeterminedTargetBindingsRemainPending: true,
    productTerminalObserved: false,
    productFaultInjectionObserved: false,
    productFixtureAdapterObserved: false,
    exactBrowserOsTargetBound: false,
    automationRunnerBound: false,
    browserStarted: false,
    webServiceStarted: false,
    productCodeChanged: false,
    networkUsed: false,
    j1Executed: false,
    rendererOrEditorReadyClaimed: false
  },
  interpretation: "A2 independently replays the static split, 108 immutable semantic fixture artifacts and formal input identities, 33 exact terminal adjudications, 37 immutable fault artifacts plus 71 exact NONE identities, 108 exact terminal-vector artifacts, 81 exact literals, two exact no-working-copy absences, and one Editor-owned dirty-guard source record over two exact canonical DesignDSL artifacts. That source binds one working-copy digest expectation while 46 first-layer source slots, 57 content-derived expectations, and all 62 UI observations remain fail-closed. This fixture proves only specification bytes and expected atomic-rejection state preservation; no product action, product adapter, browser behavior, Editor formal issuance, J1, or readiness is proved."
};

writeFileSync(resolve(SPEC, OUTPUT), Buffer.from(`${JSON.stringify(evidence, null, 2)}\n`, "utf8"));
console.log(JSON.stringify({ output: OUTPUT, sha256: artifact(OUTPUT).sha256, result: evidence.result, candidateCount: evidence.observed.candidateCount }, null, 2));
