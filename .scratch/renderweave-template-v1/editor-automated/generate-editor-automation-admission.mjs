import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import process from "node:process";

const SPEC = resolve(import.meta.dirname, "..");
const WORKTREE = resolve(SPEC, "..", "..");
const ROOT = "editor-automated";
const EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0";
const IMPLEMENTATION_REVISION = "editor-automation-admission/1.0";
const SOURCE_REVISION = "b14c2d7d4978c679e7ab8e7a2bace3da7af884de";
const CONTRACT_PATH = `${ROOT}/execution-admission-contract-v1.json`;
const ASSIGNMENT_PATH = `${ROOT}/non-capacity-assignment-v1.json`;
const AUDIT_PATH = `${ROOT}/repository-readiness-audit-v1.json`;
const PRIMARY_RESULT_PATH = `${ROOT}/admission-primary-result-v1.json`;

const PROPOSED_MANIFEST_PATHS = {
  supportedTargetMatrix: `${ROOT}/supported-targets-v1.json`,
  productBuild: `${ROOT}/product-build-manifest-v1.json`,
  targetCatalog: `${ROOT}/exact-target-manifests-v1.json`,
  runnerCatalog: `${ROOT}/automation-runner-manifests-v1.json`,
  assignedCorpus: `${ROOT}/assigned-active-corpus-v1.json`,
  independentReplay: `${ROOT}/independent-product-replay-v1.json`
};

const CLOSED_REJECTION_CODES = [
  "EDITOR_BOOTSTRAP_PREDECESSOR_PENDING",
  "EDITOR_SUPPORT_MATRIX_MISSING",
  "EDITOR_PRODUCT_BUILD_MANIFEST_MISSING",
  "EDITOR_BROWSER_BINARY_IDENTITY_MISSING",
  "EDITOR_OS_TARGET_IDENTITY_MISSING",
  "EDITOR_ENVIRONMENT_PROFILE_INCOMPLETE",
  "EDITOR_RUNNER_MANIFEST_MISSING",
  "EDITOR_CORPUS_ASSIGNMENT_INCOMPLETE",
  "EDITOR_OBSERVATION_ADAPTER_MISMATCH",
  "EDITOR_INDEPENDENT_REPLAY_MISSING"
];

function raw(relativePath) {
  return readFileSync(resolve(SPEC, relativePath));
}

function textFromWorktree(relativePath) {
  return readFileSync(resolve(WORKTREE, relativePath), "utf8");
}

function json(relativePath) {
  return JSON.parse(readFileSync(resolve(SPEC, relativePath), "utf8"));
}

function jsonFromWorktree(relativePath) {
  return JSON.parse(textFromWorktree(relativePath));
}

function sha(buffer) {
  return `sha256:${createHash("sha256").update(buffer).digest("hex")}`;
}

function artifact(relativePath, base = SPEC) {
  const content = readFileSync(resolve(base, relativePath));
  return { path: relativePath.replaceAll("\\", "/"), sha256: sha(content), byteLength: content.length };
}

function encode(value) {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function write(relativePath, value) {
  const absolute = resolve(SPEC, relativePath);
  mkdirSync(dirname(absolute), { recursive: true });
  writeFileSync(absolute, encode(value));
}

function requirementSetDigest(ids) {
  const hash = createHash("sha256");
  hash.update(Buffer.from("renderweave-editor-non-capacity-requirements/1\0", "utf8"));
  hash.update(Buffer.from(ids.join("\n"), "utf8"));
  return `sha256:${hash.digest("hex")}`;
}

function parseTsv(content, sourcePath) {
  const lines = content.replace(/\n$/, "").split("\n").map((line) => line.replace(/\r$/, ""));
  const header = lines.shift().split("\t");
  if (JSON.stringify(header) !== JSON.stringify(["requirement_id", "source_line", "clause_ordinal_on_line", "family", "normative_summary"])) {
    throw new Error(`unexpected TSV header: ${sourcePath}`);
  }
  return lines.filter(Boolean).map((line) => {
    const fields = line.split("\t");
    if (fields.length !== 5) throw new Error(`unexpected TSV width: ${sourcePath}`);
    return Object.fromEntries(header.map((name, index) => [name, fields[index]]));
  });
}

function requirements() {
  const dir = resolve(SPEC, "requirements");
  return readdirSync(dir)
    .filter((name) => /^\d{2}\.tsv$/.test(name))
    .sort()
    .flatMap((name) => parseTsv(readFileSync(resolve(dir, name), "utf8"), `requirements/${name}`));
}

function contract() {
  return {
    artifactVersion: "renderweave-editor-execution-admission-contract/1.0",
    contractId: "renderweave-editor-execution-admission/1.0",
    status: "FROZEN_STATIC_INTERFACE",
    executionClass: EXECUTION_CLASS,
    seam: {
      name: "EditorAutomationAdmission",
      interface: "one all-or-nothing admission decision over digest-bound support, build, target, runner, corpus, adapter, and replay manifests",
      adapterRole: "supported-browser-automation-runner",
      resultUnion: ["ADMITTED", "REJECTED"],
      ambientDiscoveryAllowed: false,
      partialAdmissionAllowed: false
    },
    admissionOrder: [
      "BOOTSTRAP_PREDECESSORS",
      "SUPPORTED_TARGET_MATRIX",
      "PRODUCT_BUILD",
      "BROWSER_OS_TARGET",
      "ENVIRONMENT_PROFILES",
      "AUTOMATION_RUNNER",
      "ASSIGNED_ACTIVE_CORPUS",
      "OBSERVATION_ADAPTER",
      "INDEPENDENT_REPLAY"
    ],
    requiredManifestKinds: [
      {
        kind: "SUPPORTED_TARGET_MATRIX",
        requiredFacts: ["supportMatrixId", "supportDecisionRevision", "entryId", "browserProduct", "browserChannelPolicy", "operatingSystemFamily", "versionPolicy"],
        forbiddenSubstitutes: ["one local browser run", "Playwright device alias", "project name containing canary", "package semver range"]
      },
      {
        kind: "PRODUCT_BUILD",
        requiredFacts: ["sourceRevision", "cleanSource", "packageLockSha256", "artifactManifestSha256", "webAssetSetSha256", "serviceTopologyManifestSha256", "profileCatalogDigest"],
        forbiddenSubstitutes: ["development server", "source tree without build", "prototype screenshot", "green unit test summary"]
      },
      {
        kind: "BROWSER_OS_TARGET",
        requiredFacts: ["targetId", "supportMatrixEntryId", "productBuildId", "browserProduct", "browserVersion", "browserBuildRevision", "browserArtifactSha256", "operatingSystemFamily", "operatingSystemVersion", "operatingSystemBuild", "architecture", "hostOrImageManifestSha256", "fontSetSha256", "locale", "timeZone", "colorProfile", "deviceScaleFactor", "viewport"],
        forbiddenSubstitutes: ["browser channel name alone", "unversioned host browser", "OS family alone", "latest", "default"]
      },
      {
        kind: "AUTOMATION_RUNNER",
        requiredFacts: ["executorId", "role", "targetId", "implementationRevision", "entrypointSha256", "runtimeExactVersion", "dependencyLockSha256", "playwrightExactVersion", "observationAdapterSha256", "assignedCorpusDigest", "launchPolicy"],
        forbiddenSubstitutes: ["generic playwright.config.ts", "reuseExistingServer", "browser download during run", "channel fallback", "arbitrary page script"]
      },
      {
        kind: "ASSIGNED_ACTIVE_CORPUS",
        requiredFacts: ["probeProfileId", "activeCaseIds", "activeOracleIds", "activeCorpusDigest", "requirementCoverageDigest", "supersessionClosureDigest"],
        forbiddenSubstitutes: ["routing inventory", "J1 checklist", "fixture catalog", "case count without identities"]
      },
      {
        kind: "INDEPENDENT_REPLAY",
        requiredFacts: ["runId", "executorId", "targetId", "implementationRevision", "activeCorpusDigest", "observationAdapterSha256", "caseResultSetDigest", "failureCount", "capturedRuntimeVersions"],
        forbiddenSubstitutes: ["self-reported pass", "fixture-only replay", "screenshot-only evidence", "J1 result"]
      }
    ],
    requiredEnvironmentProfiles: [
      { profileId: "DEFAULT_100", exactFacts: ["zoomPercent=100", "reducedMotion=false", "highContrast=false"] },
      { profileId: "ZOOM_200", exactFacts: ["zoomPercent=200", "viewport and deviceScaleFactor remain target-bound"] },
      { profileId: "REDUCED_MOTION", exactFacts: ["reducedMotion=true", "all other target identity facts unchanged"] },
      { profileId: "SUPPORTED_HIGH_CONTRAST", exactFacts: ["named OS-supported high-contrast or forced-colors setting", "all other target identity facts unchanged"] }
    ],
    targetRules: [
      "One targetId binds exactly one support-matrix entry, product build, browser artifact, OS host or image manifest, and environment identity.",
      "Changing any target identity fact creates a new targetId and a new runId; it never mutates an earlier run.",
      "Every supported browser and operating-system matrix entry is replayed independently; one target cannot stand in for another.",
      "The target provides no expected Oracle values to the product under observation."
    ],
    runnerRules: [
      "The runner launches only the browser artifact and product build named by the target; runtime download, channel fallback, and reuse of an ambient server are forbidden.",
      "The runner observes only the frozen closed observation adapter and cannot evaluate generic JSONPath, arbitrary scripts, wildcards, or natural-language predicates.",
      "The runner records exact runtime versions, launch arguments, target identity, corpus digest, and every terminal before issuing replay evidence.",
      "A failed or incomplete case remains failed or incomplete; the runner cannot repair, skip, reinterpret, or replace it with J1."
    ],
    independentReplayRules: [
      "Independent replay starts from the same immutable product, target, runner, corpus, and adapter manifests.",
      "Replay evidence binds the complete case result set and cannot summarize only passing cases.",
      "Fixture-generator A2 proves only fixture bytes and cannot satisfy product replay."
    ],
    closedRejectionCodes: CLOSED_REJECTION_CODES,
    issuanceBoundary: {
      routingInventoryMayExistBeforeAdmission: true,
      formalCaseOrOracleIssuanceBeforeAtomicSplit: false,
      formalRecordIssuanceBeforeTargetAndRunnerAdmission: false,
      executableClaimBeforeIndependentReplay: false
    },
    j1Boundary: {
      separateHumanRecord: true,
      requiredCaseCount: 12,
      countedInAutomatedCorpus: false,
      maySubstituteAutomatedCoverage: false,
      automatedAdmissionMaySubstituteJ1: false
    }
  };
}

function assignment() {
  const all = requirements();
  const byId = new Map(all.map((row) => [row.requirement_id, row]));
  const primary = all.filter((row) => row.family === "EDITOR_AUTOMATED").map((row) => row.requirement_id).sort();
  const j1 = json("j1-editor-checklist-v1.json");
  const journeyUnion = [...new Set(j1.cases.flatMap((entry) => entry.requirementIds))].sort();
  for (const id of journeyUnion) if (!byId.has(id)) throw new Error(`J1 requirement missing: ${id}`);
  const crossFamily = journeyUnion.filter((id) => byId.get(id).family !== "EDITOR_AUTOMATED");
  const assigned = [...new Set([...primary, ...crossFamily])].sort();
  const groups = new Map();
  for (const id of primary) {
    const match = /^RW-T(\d{2})-S(\d+)-/.exec(id);
    if (!match) throw new Error(`requirement identity drifted: ${id}`);
    const key = `EDITOR-FAMILY-T${match[1]}-S${match[2]}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(id);
  }
  if (crossFamily.length) groups.set("J1-CROSS-FAMILY-DEPENDENCIES", crossFamily);
  const probeProfile = json("conformance-probe-profile-v1.json");
  const probeIds = probeProfile.probes
    .filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS))
    .map((probe) => probe.probeId);
  return {
    artifactVersion: "renderweave-editor-non-capacity-assignment/1.0",
    status: "ROUTING_INVENTORY_FROZEN_ATOMIC_CASE_SPLIT_PENDING",
    executionClass: EXECUTION_CLASS,
    authorityBoundary: "requirement routing and journey seeds only; not Case, Oracle, target, runner, run, automated evidence, or J1 evidence",
    sourceRegistry: artifact("requirements-v1.json"),
    j1Checklist: artifact("j1-editor-checklist-v1.json"),
    observationAdapter: artifact(`${ROOT}/observation-adapter-v1.json`),
    selectionRule: {
      primary: "every requirement row whose family is exactly EDITOR_AUTOMATED",
      crossFamily: "every non-EDITOR_AUTOMATED requirement explicitly referenced by the frozen J1 journeys because the same Editor automation stimulus must preserve that invariant",
      caseIdentityNotDerivedFromFamilyOrJourney: true
    },
    assignedRequirementSet: {
      digestProfile: "renderweave-editor-non-capacity-requirements/1",
      digest: requirementSetDigest(assigned),
      requirementIds: assigned
    },
    routingGroups: [...groups.entries()].map(([groupId, requirementIds]) => ({
      groupId,
      meaning: "routing-only; every member still requires an exact input, fault schedule, terminal vector, Oracle, and assertion-level coverage edge",
      requirementCount: requirementIds.length,
      requirementIds
    })),
    automatedJourneySeeds: j1.cases.map((entry) => ({
      journeySeedId: `AUTO-SEED-${entry.caseId}`,
      sourceJ1CaseId: entry.caseId,
      title: entry.title,
      meaning: "automation decomposition seed only; not one Case identity and not J1 execution",
      requirementIds: [...entry.requirementIds].sort(),
      atomicSplitRule: "Split whenever input, fault schedule, expected terminal, observation boundary, or assertion set differs."
    })),
    classProbeSet: {
      probeProfileId: probeProfile.candidateProbeProfileId,
      probeCount: probeIds.length,
      probeIds,
      perCaseAssertionMappingStatus: "PENDING_ATOMIC_CASE_SPLIT"
    },
    counts: {
      registryRequirementCount: all.length,
      primaryEditorFamilyRequirementCount: primary.length,
      crossFamilyJ1DependencyCount: crossFamily.length,
      assignedRequirementCount: assigned.length,
      routingGroupCount: groups.size,
      automatedJourneySeedCount: j1.cases.length,
      journeySeedRequirementUnionCount: journeyUnion.length,
      assignedOutsideJourneySeedsCount: assigned.filter((id) => !journeyUnion.includes(id)).length,
      formalCaseCount: 0,
      formalOracleCount: 0
    },
    issuance: {
      atomicCaseSplitStatus: "PENDING",
      exactTerminalAndAssertionMappingStatus: "PENDING",
      exactTargetAndRunnerStatus: "PENDING",
      independentProductReplayStatus: "PENDING",
      formalRecordIssuanceAllowed: false
    }
  };
}

function audit() {
  const packageJson = jsonFromWorktree("web/package.json");
  const packageLock = jsonFromWorktree("web/package-lock.json");
  const playwrightConfig = textFromWorktree("web/playwright.config.ts");
  const bootstrap = json("conformance-bootstrap-order-v1.json");
  const pendingPredecessors = bootstrap.steps
    .filter((step) => step.ordinal >= 2 && step.ordinal <= 5 && step.executable !== true)
    .map((step) => step.executionClass);
  const configuredProject = /name:\s*'([^']+)'/.exec(playwrightConfig)?.[1] ?? null;
  const configuredDeviceAlias = /devices\['([^']+)'\]/.exec(playwrightConfig)?.[1] ?? null;
  const viewport = /viewport:\s*\{\s*width:\s*(\d+),\s*height:\s*(\d+)\s*\}/.exec(playwrightConfig);
  const reuseExistingServer = /reuseExistingServer:\s*true/.test(playwrightConfig);
  const missingPaths = Object.entries(PROPOSED_MANIFEST_PATHS)
    .filter(([, path]) => !existsSync(resolve(SPEC, path)))
    .map(([kind, path]) => ({ kind, path }));
  const blockers = [
    "EDITOR_BOOTSTRAP_PREDECESSOR_PENDING",
    "EDITOR_SUPPORT_MATRIX_MISSING",
    "EDITOR_PRODUCT_BUILD_MANIFEST_MISSING",
    "EDITOR_BROWSER_BINARY_IDENTITY_MISSING",
    "EDITOR_OS_TARGET_IDENTITY_MISSING",
    "EDITOR_ENVIRONMENT_PROFILE_INCOMPLETE",
    "EDITOR_RUNNER_MANIFEST_MISSING",
    "EDITOR_CORPUS_ASSIGNMENT_INCOMPLETE",
    "EDITOR_INDEPENDENT_REPLAY_MISSING"
  ];
  return {
    artifactVersion: "renderweave-editor-repository-readiness-audit/1.0",
    auditId: "EDITOR_AUTOMATION_REPOSITORY_AUDIT::2026-08-17::000001",
    status: "NOT_ADMITTED_EXACT_TARGET_AND_RUNNER_ABSENT",
    executionClass: EXECUTION_CLASS,
    sourceRevision: SOURCE_REVISION,
    auditScope: "repository facts only; no browser, Web server, product build, product code, network, J1, or external environment was executed",
    repositoryFacts: {
      webPackage: artifact("web/package.json", WORKTREE),
      webPackageLock: artifact("web/package-lock.json", WORKTREE),
      playwrightConfig: artifact("web/playwright.config.ts", WORKTREE),
      nodeEngineRange: packageJson.engines.node,
      playwrightDependency: packageJson.devDependencies["@playwright/test"],
      lockedPlaywrightPackageVersion: packageLock.packages["node_modules/@playwright/test"].version,
      configuredProject,
      configuredDeviceAlias,
      configuredViewport: viewport ? { width: Number(viewport[1]), height: Number(viewport[2]) } : null,
      reuseExistingServer
    },
    observedButInadmissibleSubstitutes: [
      "The package lock pins the Playwright library but does not bind a supported-browser policy entry or exact browser artifact.",
      "The chromium-canary project name and Desktop Chrome device alias are configuration labels, not browser binary identity.",
      "reuseExistingServer permits ambient service drift and therefore cannot satisfy an exact product target runner.",
      "Ticket17 prototype A1 and screenshots do not bind the Ticket18 product build, exact browser/OS target, complete automated corpus, or independent replay."
    ],
    missingManifestPaths: missingPaths,
    predecessorExecutionClassesPending: pendingPredecessors,
    admissionDecision: {
      result: "REJECTED",
      rejectionCodes: blockers,
      exactTargetManifestIssued: false,
      automationRunnerManifestIssued: false,
      formalEditorCaseCount: 0,
      formalEditorOracleCount: 0,
      browserAutomationExecuted: false,
      productExecutionEvidence: false,
      recordIssuanceAllowed: false,
      executable: false
    },
    j1Boundary: {
      status: "PENDING_SEPARATE_HUMAN_RECORD",
      evaluatedByThisAudit: false,
      blocksAutomatedEvidenceSubstitution: true
    },
    nextSafeStep: "Split the routed inventory into exact non-capacity candidate inputs, fault schedules, terminals, and assertion mappings while leaving formal records unissued; exact targets require an explicit supported-target policy and an immutable product build."
  };
}

function expectedArtifacts() {
  return {
    [CONTRACT_PATH]: contract(),
    [ASSIGNMENT_PATH]: assignment(),
    [AUDIT_PATH]: audit()
  };
}

function bootstrap() {
  write(CONTRACT_PATH, contract());
  write(ASSIGNMENT_PATH, assignment());
  write(AUDIT_PATH, audit());
}

function verify() {
  let checkCount = 0;
  const check = (condition, label) => {
    checkCount += 1;
    if (!condition) throw new Error(label);
  };
  for (const [path, value] of Object.entries(expectedArtifacts())) {
    check(raw(path).equals(encode(value)), `${path} bytes drifted`);
  }
  const admissionContract = json(CONTRACT_PATH);
  check(new Set(admissionContract.closedRejectionCodes).size === admissionContract.closedRejectionCodes.length, "duplicate rejection code");
  check(JSON.stringify(admissionContract.closedRejectionCodes) === JSON.stringify(CLOSED_REJECTION_CODES), "rejection code order");
  check(admissionContract.requiredManifestKinds.length === 6, "manifest kind count");
  check(admissionContract.requiredEnvironmentProfiles.length === 4, "environment profile count");
  check(admissionContract.j1Boundary.requiredCaseCount === 12, "J1 case count");
  const routed = json(ASSIGNMENT_PATH);
  const ids = routed.assignedRequirementSet.requirementIds;
  check(ids.length === 414, "assigned requirement count");
  check(new Set(ids).size === ids.length, "assigned requirement uniqueness");
  check(routed.counts.primaryEditorFamilyRequirementCount === 410, "Editor family count");
  check(routed.counts.crossFamilyJ1DependencyCount === 4, "cross-family J1 dependency count");
  check(routed.counts.routingGroupCount === 45, "routing group count");
  check(routed.counts.automatedJourneySeedCount === 12, "journey seed count");
  check(routed.counts.journeySeedRequirementUnionCount === 138, "journey requirement union count");
  check(routed.counts.assignedOutsideJourneySeedsCount === 276, "outside journey count");
  check(routed.classProbeSet.probeCount === 31, "Editor probe count");
  check(routed.counts.formalCaseCount === 0 && routed.counts.formalOracleCount === 0, "formal record zero boundary");
  check(routed.issuance.formalRecordIssuanceAllowed === false, "formal record issuance boundary");
  const routedGroupIds = routed.routingGroups.flatMap((group) => group.requirementIds).sort();
  check(JSON.stringify(routedGroupIds) === JSON.stringify(ids), "routing groups complete and unique");
  const readiness = json(AUDIT_PATH);
  check(readiness.admissionDecision.result === "REJECTED", "admission result");
  check(JSON.stringify(readiness.admissionDecision.rejectionCodes) === JSON.stringify(CLOSED_REJECTION_CODES.filter((code) => code !== "EDITOR_OBSERVATION_ADAPTER_MISMATCH")), "audit blocker set");
  check(readiness.predecessorExecutionClassesPending.length === 4, "predecessor count");
  check(readiness.missingManifestPaths.length === 6, "missing manifest count");
  check(readiness.admissionDecision.browserAutomationExecuted === false, "browser execution boundary");
  check(readiness.admissionDecision.productExecutionEvidence === false, "product execution boundary");
  check(readiness.admissionDecision.recordIssuanceAllowed === false, "record issuance boundary");
  const result = {
    resultVersion: "renderweave-editor-automation-admission-static-result/1.0",
    executorId: "EDITOR_ADMISSION_STATIC::NODE::1.0",
    role: "primary-editor-admission-contract-validator",
    executionClass: EXECUTION_CLASS,
    implementationRevision: IMPLEMENTATION_REVISION,
    status: "PASS_STATIC_CONTRACT_REJECTED_PRODUCT_ADMISSION",
    checkCount,
    failureCount: 0,
    contract: artifact(CONTRACT_PATH),
    assignment: artifact(ASSIGNMENT_PATH),
    readinessAudit: artifact(AUDIT_PATH),
    assignedRequirementCount: routed.counts.assignedRequirementCount,
    exactTargetManifestIssued: false,
    automationRunnerManifestIssued: false,
    browserAutomationExecuted: false,
    productExecutionEvidence: false,
    recordIssuanceAllowed: false
  };
  write(PRIMARY_RESULT_PATH, result);
  return result;
}

const command = process.argv[2];
if (command === "bootstrap") {
  bootstrap();
  process.stdout.write(`${JSON.stringify(verify())}\n`);
} else if (command === "verify") {
  process.stdout.write(`${JSON.stringify(verify())}\n`);
} else {
  throw new Error("usage: node generate-editor-automation-admission.mjs <bootstrap|verify>");
}
