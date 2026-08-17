import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.dirname(DIR);
const EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0";
const BASE_PROFILE_ID = "renderweave-conformance-probes/1.0";
const CANDIDATE_PROFILE_ID = "renderweave-conformance-probes/1.1";
const BASE_PROFILE_SHA256 = "sha256:f800eb1e6e138215c26c7761ed80e0fc9cf77fc3ce051be4e3c5ba530cd6053d";
const BASE_VECTORS_SHA256 = "sha256:291d450f0a1bfa84124664827b2e83fb562545561de9c9fd973070d0eb26762c";
const BASE_ADAPTER_SHA256 = "sha256:7f65c78c0d390e7ab972275b43341e94174d267ec4dadd28b71c60223fbe45d7";

const PATHS = {
  baseProfile: "conformance-probe-profile-v1.json",
  baseVectors: "spec-registry/assertion-vectors-v1.json",
  baseAdapter: "editor-automated/observation-adapter-v1.json",
  adjudication: "editor-automated/probe-profile-adjudication-v1.json",
  vectors: "editor-automated/probe-assertion-vectors-candidate-v1_1.json",
  profile: "editor-automated/probe-profile-candidate-v1_1.json",
  adapter: "editor-automated/observation-adapter-candidate-v1_1.json",
  audit: "editor-automated/probe-profile-candidate-readiness-audit-v1_1.json",
  primaryResult: "editor-automated/probe-profile-candidate-primary-result-v1_1.json"
};

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(ROOT, relativePath), "utf8"));
}

function bytes(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath));
}

function sha256(value) {
  return `sha256:${crypto.createHash("sha256").update(value).digest("hex")}`;
}

function artifact(relativePath) {
  const value = bytes(relativePath);
  return { path: relativePath, sha256: sha256(value), byteLength: value.length };
}

function write(relativePath, value) {
  fs.writeFileSync(path.join(ROOT, relativePath), `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function stableUnique(values) {
  return [...new Set(values)];
}

const editorBoundary = "supported-browser automated Editor observation after one named user-visible command or state transition settles";
const newProbes = [
  {
    probeId: "editor.canonicalBaselineBytes",
    valueType: "BYTE_SEQUENCE",
    observationBoundary: `${editorBoundary}; exact UTF-8 bytes of the closed renderweave-editor-canonical-baseline-projection/1.0 object in member order profile, revision, contentHash, workingCopyDigest; the projection contains no DesignDSL bytes`,
    allowedOperators: ["BYTES_EQ", "ABSENT"],
    sensitivity: "AUTHORIZED_DIAGNOSTIC",
    executionClasses: [EXECUTION_CLASS]
  },
  {
    probeId: "editor.compatibilityOriginalDigest",
    valueType: "DIGEST",
    observationBoundary: `${editorBoundary}; SHA-256 of the exact original compatibility-mode bytes retained for export, or absence when no compatibility original is retained`,
    allowedOperators: ["EQ", "ABSENT"],
    sensitivity: "AUTHORIZED_DIAGNOSTIC",
    executionClasses: [EXECUTION_CLASS]
  },
  {
    probeId: "editor.confirmationAvailable",
    valueType: "BOOLEAN",
    observationBoundary: `${editorBoundary}; whether the exact current command state exposes the guarded overwrite confirmation action`,
    allowedOperators: ["EQ"],
    sensitivity: "PUBLIC_BOUNDED",
    executionClasses: [EXECUTION_CLASS]
  },
  {
    probeId: "editor.dirty",
    valueType: "BOOLEAN",
    observationBoundary: `${editorBoundary}; whether the working copy differs semantically from its adopted canonical baseline`,
    allowedOperators: ["EQ"],
    sensitivity: "PUBLIC_BOUNDED",
    executionClasses: [EXECUTION_CLASS]
  },
  {
    probeId: "editor.mutationLockActive",
    valueType: "BOOLEAN",
    observationBoundary: `${editorBoundary}; whether the Editor mutation gate is closed while save reconciliation is in progress`,
    allowedOperators: ["EQ"],
    sensitivity: "AUTHORIZED_DIAGNOSTIC",
    executionClasses: [EXECUTION_CLASS]
  },
  {
    probeId: "editor.normalizationSummaryBytes",
    valueType: "BYTE_SEQUENCE",
    observationBoundary: `${editorBoundary}; exact UTF-8 bytes of the closed renderweave-editor-normalization-summary/1.0 projection containing only bounded category identifiers and counts, or absence when no normalization summary exists`,
    allowedOperators: ["BYTES_EQ", "ABSENT"],
    sensitivity: "AUTHORIZED_DIAGNOSTIC",
    executionClasses: [EXECUTION_CLASS]
  },
  {
    probeId: "editor.previewEligibilityGeneration",
    valueType: "INTEGER",
    observationBoundary: `${editorBoundary}; nonnegative generation owned by the Editor and incremented whenever authoritative-preview display eligibility changes`,
    allowedOperators: ["EQ"],
    sensitivity: "AUTHORIZED_DIAGNOSTIC",
    executionClasses: [EXECUTION_CLASS]
  },
  {
    probeId: "editor.recoveryDraftEnvelopeBytes",
    valueType: "BYTE_SEQUENCE",
    observationBoundary: `${editorBoundary}; exact UTF-8 bytes of the closed renderweave-editor-recovery-draft-envelope/1.0 projection containing only workingCopyDigest, optional baseRevision/baseContentHash, and false-valued containsRootDocument/containsCustomValues/containsPreviewImage/containsAssetBytes proofs, or absence when no device-local recovery draft exists; raw recovery or business content is forbidden`,
    allowedOperators: ["BYTES_EQ", "ABSENT"],
    sensitivity: "AUTHORIZED_DIAGNOSTIC",
    executionClasses: [EXECUTION_CLASS]
  },
  {
    probeId: "editor.workingCopyDigest",
    valueType: "DIGEST",
    observationBoundary: `${editorBoundary}; SHA-256 of the exact canonical working-copy DesignDSL bytes without exposing those bytes, or absence in Raw Repair and Compatibility Read-only modes where no trusted canonical DesignDSL working copy exists`,
    allowedOperators: ["EQ", "ABSENT"],
    sensitivity: "AUTHORIZED_DIAGNOSTIC",
    executionClasses: [EXECUTION_CLASS]
  }
];

const proposalCounts = new Map([
  ["editor.canonicalBaselineContentHash", ["DIGEST", 9]],
  ["editor.canonicalBaselineDigest", ["DIGEST", 9]],
  ["editor.canonicalBaselineRevision", ["INTEGER", 12]],
  ["editor.compatibilityOriginalDigest", ["DIGEST", 2]],
  ["editor.confirmationAvailable", ["BOOLEAN", 17]],
  ["editor.dirty", ["BOOLEAN", 7]],
  ["editor.mutationLockActive", ["BOOLEAN", 8]],
  ["editor.normalizationSummaryBytes", ["BYTE_SEQUENCE", 1]],
  ["editor.previewEligibilityGeneration", ["INTEGER", 21]],
  ["editor.recoveryDraftBytes", ["BYTE_SEQUENCE", 1]],
  ["editor.recoveryDraftPresent", ["BOOLEAN", 4]],
  ["editor.workingCopyDigest", ["DIGEST", 37]]
]);

const adjudicationRules = new Map([
  ["editor.canonicalBaselineContentHash", ["CONSOLIDATED_INTO", "editor.canonicalBaselineBytes", "One closed baseline projection keeps revision, contentHash, and working-copy digest coherent at one observation boundary."]],
  ["editor.canonicalBaselineDigest", ["CONSOLIDATED_INTO", "editor.canonicalBaselineBytes", "A separate digest would widen the test seam without adding an independently meaningful state transition."]],
  ["editor.canonicalBaselineRevision", ["CONSOLIDATED_INTO", "editor.canonicalBaselineBytes", "Revision is a member of the closed baseline projection rather than an independently observable Editor subsystem."]],
  ["editor.compatibilityOriginalDigest", ["APPROVED_UNCHANGED", "editor.compatibilityOriginalDigest", "Exact retained-original identity is independently meaningful while raw original bytes remain hidden."]],
  ["editor.confirmationAvailable", ["APPROVED_UNCHANGED", "editor.confirmationAvailable", "The user-visible guarded action is a small stable interface fact."]],
  ["editor.dirty", ["APPROVED_UNCHANGED", "editor.dirty", "Dirty is a user-visible semantic guard and not a dump of internal state."]],
  ["editor.mutationLockActive", ["APPROVED_UNCHANGED", "editor.mutationLockActive", "The mutation gate is the narrow seam needed to verify command exclusion during reconciliation."]],
  ["editor.normalizationSummaryBytes", ["APPROVED_UNCHANGED", "editor.normalizationSummaryBytes", "A closed bounded category/count projection verifies the summary without exposing authored content."]],
  ["editor.previewEligibilityGeneration", ["APPROVED_UNCHANGED", "editor.previewEligibilityGeneration", "A monotonic generation is the narrow identity seam for late-result eligibility."]],
  ["editor.recoveryDraftBytes", ["CONSOLIDATED_INTO", "editor.recoveryDraftEnvelopeBytes", "Raw recovery bytes would expose business content; only a closed digest-and-exclusion-proof envelope is admissible."]],
  ["editor.recoveryDraftPresent", ["CONSOLIDATED_INTO", "editor.recoveryDraftEnvelopeBytes", "Presence is represented by BYTES_EQ versus ABSENT on the same safe envelope probe."]],
  ["editor.workingCopyDigest", ["APPROVED_UNCHANGED", "editor.workingCopyDigest", "A digest proves whole-working-copy identity while keeping authored bytes outside the observation seam."]]
]);

function buildAdjudication() {
  const decisions = [...proposalCounts].map(([sourceProbeId, [sourceValueType, sourceCandidateCount]]) => {
    const [decision, candidateProbeId, rationale] = adjudicationRules.get(sourceProbeId);
    return { sourceProbeId, sourceValueType, sourceCandidateCount, decision, candidateProbeId, rationale };
  });
  return {
    artifactVersion: "renderweave-editor-probe-profile-adjudication/1.0",
    status: "ADJUDICATED_CANDIDATE_ONLY_NOT_ISSUED",
    executionClass: EXECUTION_CLASS,
    baseProbeProfile: { profileId: BASE_PROFILE_ID, ...artifact(PATHS.baseProfile) },
    candidateProbeProfileId: CANDIDATE_PROFILE_ID,
    designRule: {
      interfaceKind: "closed observation Interface",
      deepModuleBoundary: "Expose a small stable semantic result at the command-settled Seam; do not expose Editor aggregate layout, generic state snapshots, raw drafts, or raw DesignDSL only to make tests convenient.",
      genericStateSnapshotAllowed: false,
      rawRecoveryDraftAllowed: false,
      rawWorkingCopyAllowed: false,
      arbitraryScriptAllowed: false,
      wildcardAllowed: false
    },
    decisions,
    counts: {
      sourceProposalCount: decisions.length,
      approvedUnchangedSourceCount: decisions.filter((entry) => entry.decision === "APPROVED_UNCHANGED").length,
      consolidatedSourceCount: decisions.filter((entry) => entry.decision === "CONSOLIDATED_INTO").length,
      candidateProbeCount: newProbes.length
    },
    boundary: "This adjudication selects a complete 1.1 candidate interface. It does not issue the Probe Profile, mutate the current 1.0 Profile, admit Oracle records, execute a browser, or prove product behavior."
  };
}

function expectedFor(probe, operator) {
  if (operator === "ABSENT") return undefined;
  if (operator === "BYTES_EQ") {
    return {
      kind: "ARTIFACT",
      artifactPath: "spec-registry/expected-bytes/assertion-byte-sample-v1.bin",
      mediaType: "application/octet-stream",
      artifactSha256: "sha256:0150a92bb1212cd00516b65fde0704614760000963874fcbb11eaa734ee87809"
    };
  }
  if (probe.valueType === "DIGEST") return { kind: "LITERAL", value: "sha256:0000000000000000000000000000000000000000000000000000000000000000" };
  if (probe.valueType === "BOOLEAN") return { kind: "LITERAL", value: true };
  if (probe.valueType === "INTEGER") return { kind: "LITERAL", value: 1 };
  throw new Error(`no accepted expected sample for ${probe.probeId}/${operator}`);
}

function rejectedExpectedFor(probe) {
  if (probe.valueType === "BYTE_SEQUENCE") return { kind: "LITERAL", value: "not-an-artifact" };
  if (probe.valueType === "DIGEST") return { kind: "LITERAL", value: 1 };
  if (probe.valueType === "BOOLEAN") return { kind: "LITERAL", value: "true" };
  if (probe.valueType === "INTEGER") return { kind: "LITERAL", value: "1" };
  throw new Error(`no rejected expected sample for ${probe.probeId}`);
}

function buildVectors(baseVectors) {
  let acceptedOrdinal = baseVectors.acceptedVectors.length;
  let rejectedOrdinal = baseVectors.rejectedVectors.length;
  const deltaAccepted = [];
  const deltaRejected = [];
  for (const probe of newProbes) {
    for (const operator of probe.allowedOperators) {
      acceptedOrdinal += 1;
      const assertion = { assertionId: "A001", probeId: probe.probeId, operator };
      const expected = expectedFor(probe, operator);
      if (expected !== undefined) assertion.expected = expected;
      deltaAccepted.push({
        vectorId: `ASSERT-OK-${String(acceptedOrdinal).padStart(3, "0")}`,
        probeId: probe.probeId,
        valueType: probe.valueType,
        assertion
      });
    }
    rejectedOrdinal += 1;
    const operator = probe.allowedOperators.find((entry) => entry !== "ABSENT");
    deltaRejected.push({
      vectorId: `ASSERT-NO-${String(rejectedOrdinal).padStart(3, "0")}`,
      probeId: probe.probeId,
      valueType: probe.valueType,
      assertion: {
        assertionId: "A001",
        probeId: probe.probeId,
        operator,
        expected: rejectedExpectedFor(probe)
      },
      expectedCode: "CONFORMANCE_ASSERTION_EXPECTED_INVALID"
    });
  }
  const acceptedVectors = [...baseVectors.acceptedVectors, ...deltaAccepted];
  const rejectedVectors = [...baseVectors.rejectedVectors, ...deltaRejected];
  return {
    artifactVersion: "renderweave-conformance-assertion-vectors/1.1-candidate",
    status: "COMPLETE_CANDIDATE_VECTOR_SET_NOT_ISSUED",
    probeProfile: CANDIDATE_PROFILE_ID,
    baseProbeProfile: { profileId: BASE_PROFILE_ID, ...artifact(PATHS.baseProfile) },
    baseAssertionVectors: artifact(PATHS.baseVectors),
    probeCount: 110 + newProbes.length,
    valueEncoding: baseVectors.valueEncoding,
    acceptedVectors,
    rejectedVectors,
    acceptedVectorCount: acceptedVectors.length,
    rejectedVectorCount: rejectedVectors.length,
    delta: {
      addedProbeCount: newProbes.length,
      acceptedVectorCount: deltaAccepted.length,
      rejectedVectorCount: deltaRejected.length,
      acceptedVectorIds: deltaAccepted.map((entry) => entry.vectorId),
      rejectedVectorIds: deltaRejected.map((entry) => entry.vectorId)
    },
    completeProbeOperatorCoverage: true,
    boundary: "Complete static candidate vectors are not issuance. The current 1.0 assertion-vector artifact remains authoritative until a separately approved Profile replacement is issued."
  };
}

function buildProfile(baseProfile) {
  const probes = [...baseProfile.probes, ...newProbes];
  return {
    ...baseProfile,
    artifactVersion: "renderweave-conformance-probe-profile-artifact/1.1-candidate",
    candidateProbeProfileId: CANDIDATE_PROFILE_ID,
    status: "COMPLETE_CANDIDATE_NOT_ISSUED",
    authority: "Ticket19 closed-probe framework plus the Editor 12-to-9 deep-interface adjudication; this artifact is complete for static review but has not replaced the issued 1.0 Profile",
    recordMayReference: false,
    baseProbeProfile: { profileId: BASE_PROFILE_ID, ...artifact(PATHS.baseProfile) },
    adjudication: artifact(PATHS.adjudication),
    assertionVectors: {
      ...artifact(PATHS.vectors),
      acceptedVectorCount: 157,
      rejectedVectorCount: 119,
      probeOperatorCoverageComplete: true,
      candidateOnly: true
    },
    probes,
    probeCount: probes.length,
    issuanceBlockers: [
      "EXPLICIT_PROFILE_1_1_ISSUANCE_AUTHORITY_PENDING",
      "CURRENT_PROFILE_1_0_SUPERSESSION_RECORD_PENDING"
    ],
    classRecordGate: "No Case or Oracle may reference this candidate identity before explicit Profile issuance. If 1.1 is later issued, Editor record issuance remains a separate gate requiring frozen exact fixtures/faults/terminals, an admitted Editor target and runner, and independent product replay; those class facts do not gate the global Profile identity itself."
  };
}

const addedMappings = [
  ["editor.canonicalBaselineBytes", "BYTE_SEQUENCE", "closedObservation.editor.canonicalBaselineBytes", "EXPLICIT_ABSENT"],
  ["editor.compatibilityOriginalDigest", "DIGEST", "closedObservation.editor.compatibilityOriginalDigest", "EXPLICIT_ABSENT"],
  ["editor.confirmationAvailable", "BOOLEAN", "closedObservation.editor.confirmationAvailable", "MUST_BE_PRESENT"],
  ["editor.dirty", "BOOLEAN", "closedObservation.editor.dirty", "MUST_BE_PRESENT"],
  ["editor.mutationLockActive", "BOOLEAN", "closedObservation.editor.mutationLockActive", "MUST_BE_PRESENT"],
  ["editor.normalizationSummaryBytes", "BYTE_SEQUENCE", "closedObservation.editor.normalizationSummaryBytes", "EXPLICIT_ABSENT"],
  ["editor.previewEligibilityGeneration", "INTEGER", "closedObservation.editor.previewEligibilityGeneration", "MUST_BE_PRESENT"],
  ["editor.recoveryDraftEnvelopeBytes", "BYTE_SEQUENCE", "closedObservation.editor.recoveryDraftEnvelopeBytes", "EXPLICIT_ABSENT"],
  ["editor.workingCopyDigest", "DIGEST", "closedObservation.editor.workingCopyDigest", "EXPLICIT_ABSENT"]
].map(([probeId, valueType, source, absentPolicy]) => ({ probeId, valueType, source, absentPolicy }));

function buildAdapter(baseAdapter) {
  const mappings = [...baseAdapter.mappings, ...addedMappings];
  return {
    ...baseAdapter,
    artifactVersion: "renderweave-editor-automated-observation-adapter/1.1-candidate",
    adapterId: "renderweave-editor-automated-observation-adapter/1.1-candidate",
    status: "COMPLETE_CANDIDATE_BROWSER_TARGET_PENDING_NOT_ISSUED",
    probeProfile: CANDIDATE_PROFILE_ID,
    probeProfileArtifact: artifact(PATHS.profile),
    baseAdapter: artifact(PATHS.baseAdapter),
    closedObservationVersion: "renderweave-editor-automated-closed-observation/1.1-candidate",
    mappings,
    mappingCount: mappings.length,
    projectionContracts: {
      canonicalBaselineBytes: {
        profile: "renderweave-editor-canonical-baseline-projection/1.0",
        exactMembersInOrder: ["profile", "revision", "contentHash", "workingCopyDigest"],
        rawDesignDslAllowed: false
      },
      normalizationSummaryBytes: {
        profile: "renderweave-editor-normalization-summary/1.0",
        exactMembersInOrder: ["profile", "categories"],
        categoryExactMembersInOrder: ["categoryId", "count"],
        businessContentAllowed: false
      },
      recoveryDraftEnvelopeBytes: {
        profile: "renderweave-editor-recovery-draft-envelope/1.0",
        exactMembersInOrder: ["profile", "workingCopyDigest", "baseRevision", "baseContentHash", "containsRootDocument", "containsCustomValues", "containsPreviewImage", "containsAssetBytes"],
        nullableMembers: [],
        optionalMembers: ["baseRevision", "baseContentHash"],
        requiredFalseMembers: ["containsRootDocument", "containsCustomValues", "containsPreviewImage", "containsAssetBytes"],
        rawRecoveryDraftAllowed: false
      }
    },
    fixtureOnlyBoundary: {
      closedObservationProduced: false,
      browserTargetBound: false,
      browserAutomationExecuted: false,
      accessibilityAssertionsEvaluated: false,
      j1Evaluated: false
    },
    evidenceBoundary: "This candidate Adapter closes the 1.1 extraction seam only. It neither changes product code nor produces observations; an exact product target and browser runner must later populate and replay every field."
  };
}

function validateExpectedShape(probe, assertion) {
  if (!probe.allowedOperators.includes(assertion.operator)) return false;
  if (assertion.operator === "ABSENT") return !Object.hasOwn(assertion, "expected");
  if (assertion.operator === "BYTES_EQ") return assertion.expected?.kind === "ARTIFACT" && /^sha256:[0-9a-f]{64}$/.test(assertion.expected.artifactSha256 ?? "");
  if (assertion.operator === "SEQUENCE_EQ") {
    if (assertion.expected?.kind !== "LITERAL" || !Array.isArray(assertion.expected.value)) return false;
    if (probe.valueType === "TEXT_SEQUENCE") return assertion.expected.value.every((entry) => typeof entry === "string");
    if (probe.valueType === "INTEGER_SEQUENCE") return assertion.expected.value.every((entry) => Number.isSafeInteger(entry));
    return false;
  }
  if (assertion.operator === "WITHIN") {
    const interval = assertion.expected?.kind === "LITERAL" ? assertion.expected.value : null;
    const rational = (value) => value && Number.isSafeInteger(value.numerator) && Number.isSafeInteger(value.denominator) && value.denominator > 0;
    return probe.valueType === "RATIONAL_DURATION_MILLISECONDS" && rational(interval?.minimum) && rational(interval?.maximum);
  }
  if (assertion.operator !== "EQ" || assertion.expected?.kind !== "LITERAL") return false;
  const value = assertion.expected.value;
  if (probe.valueType === "DIGEST") return typeof value === "string" && /^sha256:[0-9a-f]{64}$/.test(value);
  if (probe.valueType === "BOOLEAN") return typeof value === "boolean";
  if (probe.valueType === "INTEGER") return Number.isSafeInteger(value);
  if (["TEXT", "CODE", "STAGE", "DECIMAL"].includes(probe.valueType)) return typeof value === "string";
  return false;
}

function validate(baseProfile, baseVectors, baseAdapter, adjudication, vectors, profile, adapter) {
  const checks = [];
  const check = (name, pass, details = null) => checks.push({ name, pass: Boolean(pass), details });
  check("base Profile bytes remain exact", artifact(PATHS.baseProfile).sha256 === BASE_PROFILE_SHA256, artifact(PATHS.baseProfile));
  check("base assertion vectors remain exact", artifact(PATHS.baseVectors).sha256 === BASE_VECTORS_SHA256, artifact(PATHS.baseVectors));
  check("base Editor adapter remains exact", artifact(PATHS.baseAdapter).sha256 === BASE_ADAPTER_SHA256, artifact(PATHS.baseAdapter));
  check("adjudication covers exactly 12 proposals", adjudication.decisions.length === 12 && new Set(adjudication.decisions.map((entry) => entry.sourceProbeId)).size === 12, adjudication.counts);
  check("adjudication converges to exactly 9 probes", adjudication.counts.candidateProbeCount === 9 && new Set(adjudication.decisions.map((entry) => entry.candidateProbeId)).size === 9, adjudication.counts);
  check("candidate Probe Profile is complete but unissued", profile.candidateProbeProfileId === CANDIDATE_PROFILE_ID && profile.status === "COMPLETE_CANDIDATE_NOT_ISSUED" && profile.recordMayReference === false && profile.issuanceBlockers.length > 0, null);
  check("candidate retains base probes byte-semantically and appends 9", JSON.stringify(profile.probes.slice(0, baseProfile.probes.length)) === JSON.stringify(baseProfile.probes) && profile.probes.length === 119 && profile.probeCount === 119, profile.probeCount);
  check("candidate probe identities are unique", new Set(profile.probes.map((probe) => probe.probeId)).size === profile.probes.length, null);
  check("forbidden generic snapshot mechanisms stay forbidden", profile.forbiddenProbeMechanisms.includes("generic state snapshot") && adjudication.designRule.genericStateSnapshotAllowed === false && adjudication.designRule.rawRecoveryDraftAllowed === false, adjudication.designRule);
  const probeById = new Map(profile.probes.map((probe) => [probe.probeId, probe]));
  const acceptedByKey = new Map();
  for (const vector of vectors.acceptedVectors) {
    const key = `${vector.probeId}\u0000${vector.assertion.operator}`;
    acceptedByKey.set(key, (acceptedByKey.get(key) ?? 0) + 1);
  }
  const everyOperatorCoveredOnce = profile.probes.every((probe) => probe.allowedOperators.every((operator) => acceptedByKey.get(`${probe.probeId}\u0000${operator}`) === 1));
  check("every candidate probe/operator has exactly one accepted vector", everyOperatorCoveredOnce && vectors.acceptedVectorCount === 157, vectors.acceptedVectorCount);
  check("every candidate probe has exactly one rejected vector", vectors.rejectedVectorCount === 119 && new Set(vectors.rejectedVectors.map((entry) => entry.probeId)).size === 119, vectors.rejectedVectorCount);
  check("all accepted vectors have exact typed expected shapes", vectors.acceptedVectors.every((entry) => validateExpectedShape(probeById.get(entry.probeId), entry.assertion)), null);
  check("candidate vector delta is exactly 14 accepted and 9 rejected", vectors.delta.acceptedVectorCount === 14 && vectors.delta.rejectedVectorCount === 9, vectors.delta);
  const editorProbeIds = new Set(profile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS)).map((probe) => probe.probeId));
  const adapterProbeIds = adapter.mappings.map((mapping) => mapping.probeId);
  check("candidate Editor Adapter maps all and only 40 Editor probes", adapter.mappingCount === 40 && adapterProbeIds.length === 40 && new Set(adapterProbeIds).size === 40 && adapterProbeIds.every((id) => editorProbeIds.has(id)) && editorProbeIds.size === 40, { adapter: adapter.mappingCount, profile: editorProbeIds.size });
  check("candidate Adapter retains every base mapping", JSON.stringify(adapter.mappings.slice(0, baseAdapter.mappings.length)) === JSON.stringify(baseAdapter.mappings), null);
  check("candidate Adapter exposes no generic extraction", adapter.genericJsonPathAllowed === false && adapter.arbitraryScriptAllowed === false && adapter.fallbackAllowed === false && adapter.expectedValuesVisibleToTarget === false, null);
  check("base artifacts still declare 1.0", baseProfile.candidateProbeProfileId === BASE_PROFILE_ID && baseVectors.probeProfile === BASE_PROFILE_ID && baseAdapter.probeProfile === BASE_PROFILE_ID, null);
  return { checks, passed: checks.every((entry) => entry.pass) };
}

const baseProfile = readJson(PATHS.baseProfile);
const baseVectors = readJson(PATHS.baseVectors);
const baseAdapter = readJson(PATHS.baseAdapter);

const adjudication = buildAdjudication();
write(PATHS.adjudication, adjudication);
const vectors = buildVectors(baseVectors);
write(PATHS.vectors, vectors);
const profile = buildProfile(baseProfile);
write(PATHS.profile, profile);
const adapter = buildAdapter(baseAdapter);
write(PATHS.adapter, adapter);

const validation = validate(baseProfile, baseVectors, baseAdapter, adjudication, vectors, profile, adapter);
const audit = {
  artifactVersion: "renderweave-editor-probe-profile-candidate-readiness-audit/1.1",
  status: validation.passed ? "STATIC_CANDIDATE_COMPLETE_NOT_ISSUED" : "STATIC_CANDIDATE_INVALID",
  executionClass: EXECUTION_CLASS,
  baseArtifacts: {
    probeProfile: artifact(PATHS.baseProfile),
    assertionVectors: artifact(PATHS.baseVectors),
    observationAdapter: artifact(PATHS.baseAdapter)
  },
  candidateArtifacts: {
    adjudication: artifact(PATHS.adjudication),
    probeProfile: artifact(PATHS.profile),
    assertionVectors: artifact(PATHS.vectors),
    observationAdapter: artifact(PATHS.adapter)
  },
  counts: {
    sourceProposalCount: 12,
    candidateAddedProbeCount: 9,
    candidateTotalProbeCount: 119,
    candidateEditorProbeCount: 40,
    candidateAcceptedVectorCount: 157,
    candidateRejectedVectorCount: 119,
    candidateAddedAcceptedVectorCount: 14,
    candidateAddedRejectedVectorCount: 9
  },
  validation,
  issuanceBoundary: {
    candidateComplete: validation.passed,
    currentProfileMutated: false,
    currentProfileSuperseded: false,
    candidateRecordMayReference: false,
    formalJsonlAppended: false,
    productCodeChanged: false,
    browserStarted: false,
    webServiceStarted: false,
    networkUsed: false,
    j1Executed: false,
    productReadyClaimed: false
  },
  gateSeparation: {
    globalProfileCandidateGate: {
      exactInventoryComplete: validation.passed,
      probeOperatorVectorsComplete: validation.passed,
      explicitIssuanceAuthorityPending: true,
      currentProfileSupersessionRecordPending: true,
      editorProductTargetRequiredForProfileIssuance: false
    },
    editorRecordGate: {
      candidateProfileMustFirstBeIssued: true,
      exactFixtureFaultTerminalAndTargetBindingsPending: true,
      exactTargetAndRunnerAdmissionPending: true,
      independentProductReplayPending: true
    }
  },
  remainingBlockers: [
    "EXACT_PRODUCT_FIXTURE_ARTIFACT_MISSING",
    "FAULT_SCHEDULE_ARTIFACT_MISSING_FOR_FAULTED_SCENARIOS",
    "EXPECTED_TERMINAL_CODE_OR_STAGE_UNBOUND_FOR_PENDING_SCENARIOS",
    "TARGET_LITERAL_OR_ARTIFACT_MISSING",
    "EXACT_BROWSER_OS_TARGET_MISSING",
    "EXECUTOR_MANIFEST_MISSING",
    "INDEPENDENT_PRODUCT_REPLAY_MISSING",
    "PROFILE_CANDIDATE_NOT_ISSUED"
  ],
  decision: validation.passed
    ? "The 1.1 candidate is structurally complete and safe to bind from planning candidates, but no Case or Oracle may reference it until explicit issuance and all product-execution blockers close."
    : "The 1.1 candidate is invalid and must not be referenced."
};
write(PATHS.audit, audit);

const primaryResult = {
  artifactVersion: "renderweave-editor-probe-profile-candidate-primary-result/1.1",
  status: validation.passed ? "PASS_STATIC_CANDIDATE_NOT_ISSUED" : "FAIL",
  runtime: { engine: "node", version: process.version, role: "candidate-generator-and-primary-validator" },
  artifacts: {
    adjudication: artifact(PATHS.adjudication),
    probeProfile: artifact(PATHS.profile),
    assertionVectors: artifact(PATHS.vectors),
    observationAdapter: artifact(PATHS.adapter),
    readinessAudit: artifact(PATHS.audit)
  },
  checkCount: validation.checks.length,
  failureCount: validation.checks.filter((entry) => !entry.pass).length,
  checks: validation.checks,
  passed: validation.passed,
  boundary: "Static candidate generation and validation only; no profile issuance, product code, browser, network, formal JSONL, J1, or READY evidence."
};
write(PATHS.primaryResult, primaryResult);

if (!validation.passed) {
  console.error(JSON.stringify(primaryResult, null, 2));
  process.exit(1);
}
console.log(JSON.stringify({
  status: primaryResult.status,
  checkCount: primaryResult.checks.length,
  counts: audit.counts,
  artifacts: primaryResult.artifacts
}, null, 2));
