import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const SPEC = resolve(import.meta.dirname, "..");
const ROOT = "editor-automated";
const OUTPUT = `${ROOT}/editor-probe-profile-candidate-static-a2-2026-08-17.json`;

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

const primary = json(`${ROOT}/probe-profile-candidate-primary-result-v1_1.json`);
const independent = json(`${ROOT}/probe-profile-candidate-independent-result-v1_1.json`);
const audit = json(`${ROOT}/probe-profile-candidate-readiness-audit-v1_1.json`);
if (primary.status !== "PASS_STATIC_CANDIDATE_NOT_ISSUED" || primary.passed !== true) throw new Error("primary candidate result is not a clean pass");
if (independent.status !== "PASS_STATIC_CANDIDATE_NOT_ISSUED" || independent.passed !== true) throw new Error("independent candidate result is not a clean pass");
if (audit.status !== "STATIC_CANDIDATE_COMPLETE_NOT_ISSUED" || audit.issuanceBoundary.candidateRecordMayReference !== false) throw new Error("candidate issuance boundary drifted");
if (audit.counts.sourceProposalCount !== 12 || audit.counts.candidateAddedProbeCount !== 9 || audit.counts.candidateTotalProbeCount !== 119 || audit.counts.candidateEditorProbeCount !== 40) throw new Error("candidate probe inventory drifted");
if (audit.counts.candidateAcceptedVectorCount !== 157 || audit.counts.candidateRejectedVectorCount !== 119) throw new Error("candidate vector inventory drifted");

const evidence = {
  evidenceVersion: "renderweave-editor-probe-profile-candidate-static-evidence/1.1",
  evidenceId: "editor-probe-profile-candidate-static-a2-2026-08-17",
  capturedAt: "2026-08-17T00:00:00+08:00",
  evidenceLevel: "A2_STATIC",
  result: "A2_STATIC_PROBE_PROFILE_CANDIDATE_REPLAYED_NOT_ISSUED",
  scope: "12-to-9 Editor observation-interface adjudication, full renderweave-conformance-probes/1.1 candidate inventory, complete probe/operator assertion vectors, and closed Editor Adapter candidate",
  targetArtifacts: {
    adjudication: artifact(`${ROOT}/probe-profile-adjudication-v1.json`),
    probeProfileCandidate: artifact(`${ROOT}/probe-profile-candidate-v1_1.json`),
    assertionVectorsCandidate: artifact(`${ROOT}/probe-assertion-vectors-candidate-v1_1.json`),
    observationAdapterCandidate: artifact(`${ROOT}/observation-adapter-candidate-v1_1.json`),
    readinessAudit: artifact(`${ROOT}/probe-profile-candidate-readiness-audit-v1_1.json`),
    currentProbeProfile: artifact("conformance-probe-profile-v1.json"),
    currentAssertionVectors: artifact("spec-registry/assertion-vectors-v1.json"),
    currentObservationAdapter: artifact(`${ROOT}/observation-adapter-v1.json`)
  },
  implementations: {
    primaryGeneratorAndValidator: artifact(`${ROOT}/generate-editor-probe-profile-candidate.mjs`),
    independentValidator: artifact(`${ROOT}/validate_editor_probe_profile_candidate_independent.py`)
  },
  results: {
    primary: artifact(`${ROOT}/probe-profile-candidate-primary-result-v1_1.json`),
    independent: artifact(`${ROOT}/probe-profile-candidate-independent-result-v1_1.json`)
  },
  observed: {
    sourceProposalCount: 12,
    approvedUnchangedSourceCount: 7,
    consolidatedSourceCount: 5,
    candidateAddedProbeCount: 9,
    candidateTotalProbeCount: 119,
    candidateEditorProbeCount: 40,
    candidateAcceptedVectorCount: 157,
    candidateRejectedVectorCount: 119,
    primaryCheckCount: primary.checkCount,
    independentCheckCount: independent.checkCount
  },
  negativeTerminals: audit.remainingBlockers,
  sealedBoundaries: {
    profileCandidateComplete: true,
    profileIssued: false,
    currentProfileSuperseded: false,
    candidateRecordMayReference: false,
    formalCaseOrOracleIssued: false,
    conformanceJsonlAppended: false,
    productCodeChanged: false,
    exactProductFixtureBound: false,
    exactBrowserOsTargetBound: false,
    automationRunnerBound: false,
    browserStarted: false,
    webServiceStarted: false,
    networkUsed: false,
    j1Executed: false,
    rendererOrEditorReadyClaimed: false
  },
  interpretation: "A2 independently replays the complete static 1.1 candidate and its deep closed observation Interface. This is preissuance evidence only: the issued 1.0 Profile remains current, no formal record may reference 1.1, and no product or browser behavior was exercised."
};

writeFileSync(resolve(SPEC, OUTPUT), Buffer.from(`${JSON.stringify(evidence, null, 2)}\n`, "utf8"));
console.log(JSON.stringify({ output: OUTPUT, sha256: artifact(OUTPUT).sha256, result: evidence.result, candidateTotalProbeCount: evidence.observed.candidateTotalProbeCount }, null, 2));
