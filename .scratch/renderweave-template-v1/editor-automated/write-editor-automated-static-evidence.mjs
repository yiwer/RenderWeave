import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");
const EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0";

function bytes(relativePath) {
  return readFileSync(resolve(SPEC, relativePath));
}

function json(relativePath) {
  return JSON.parse(bytes(relativePath).toString("utf8"));
}

function sha256(value) {
  return `sha256:${createHash("sha256").update(value).digest("hex")}`;
}

function artifact(relativePath) {
  const content = bytes(relativePath);
  return { path: relativePath, sha256: sha256(content), byteLength: content.length };
}

function writeJson(relativePath, value) {
  writeFileSync(resolve(SPEC, relativePath), `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function run(command, args) {
  const result = spawnSync(command, args, { cwd: SPEC, encoding: "utf8", windowsHide: true });
  if (result.status !== 0) throw new Error(`${command} failed (${result.status}): ${result.stderr || result.stdout}`);
  const value = JSON.parse(result.stdout);
  if (value.status !== "PASS") throw new Error(`${command} replay did not pass`);
  return value;
}

const targetPath = "editor-automated/generator-target-manifest-v1.json";
const primaryEntrypoint = "editor-automated/generate-editor-automated-fixtures.mjs";
const independentEntrypoint = "editor-automated/validate_editor_automated_fixtures_independent.py";
const target = json(targetPath);
run("node", [primaryEntrypoint, "verify"]);
run("python", [independentEntrypoint]);
const primary = json("editor-automated/primary-result-v1.json");
const independent = json("editor-automated/independent-result-v1.json");
if (primary.generatorTargetSha256 !== independent.generatorTargetSha256) throw new Error("generator target digest disagreement");

const snapshotPolicy = json("conformance-manifest-snapshot-policy-v1.json");
const coverage = json("conformance-capacity-coverage-v1.json");
const editorAxes = coverage.axes.filter((axis) => axis.executionClass === EXECUTION_CLASS);
if (editorAxes.length !== 0) throw new Error(`EDITOR_AUTOMATED capacity axis drifted: ${editorAxes.length}`);
const probeProfile = json("conformance-probe-profile-v1.json");
const editorProbeCount = probeProfile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS)).length;
const adapter = json("editor-automated/observation-adapter-v1.json");
if (adapter.mappingCount !== editorProbeCount) throw new Error("Editor adapter mapping count drifted");
const executionCatalog = json("conformance-execution-classes-v1.json");
const executionEntry = executionCatalog.classes.find((entry) => entry.executionClass === EXECUTION_CLASS);
if (!executionEntry || executionEntry.executable !== false) throw new Error("Editor execution frontier drifted");

const formalCases = artifact("conformance-cases-v1.jsonl");
const formalOracles = artifact("conformance-oracles-v1.jsonl");
const evidence = {
  evidenceVersion: "renderweave-editor-automated-fixture-static-a2/1.0",
  evidenceId: "EDITOR_AUTOMATED_FIXTURE_STATIC_A2::2026-08-17::000001",
  status: "PASS",
  grade: "A2_INDEPENDENT_STATIC_REPLAY",
  scope: "EDITOR_AUTOMATED fixture-only Canvas Focus Structured Editor baseline context, 31-probe closed observation adapter, deterministic named-fixture generator, and one exact named fixture only; no browser, operating-system target, Web server, product session, save, conflict, recovery, preview, accessibility assertion, J1, network, or product code was executed.",
  specRevision: {
    branch: "spec/template-v1",
    baseCommit: "b14c2d7d4978c679e7ab8e7a2bace3da7af884de",
    implementationRevision: target.implementationRevision,
  },
  authorityBindings: {
    acceptanceManifest: artifact("acceptance-manifest-v1.json"),
    ticket17: artifact("issues/17-authoring-workflow-prototype.md"),
    ticket18: artifact("issues/18-editor-preview-and-recovery.md"),
    probeProfile: artifact("conformance-probe-profile-v1.json"),
    safeBaselineCatalog: artifact("conformance-safe-baselines-v1.json"),
    generatorCatalog: artifact("conformance-generator-manifests-v1.json"),
    executionClassCatalog: artifact("conformance-execution-classes-v1.json"),
    bootstrapOrder: artifact("conformance-bootstrap-order-v1.json"),
    manifestSnapshotPolicy: {
      ...artifact("conformance-manifest-snapshot-policy-v1.json"),
      retainedSnapshotCount: snapshotPolicy.requiredSeedSnapshots.length,
      currentCatalogSnapshotCount: snapshotPolicy.currentCatalogSnapshots.length,
    },
    baseline: artifact("editor-automated/baseline-v1.json"),
    fixtureContract: artifact("editor-automated/fixture-contract-v1.json"),
    observationAdapter: artifact("editor-automated/observation-adapter-v1.json"),
    scenarioCatalog: artifact("editor-automated/capacity-scenarios-v1.json"),
    generatorGoldens: artifact("editor-automated/generator-goldens-v1.json"),
    generatorTarget: artifact(targetPath),
    generatorImplementation: artifact("editor-automated/generator-implementation-manifest-v1.json"),
  },
  fixtureInventory: {
    fixtureCount: target.fixtureArtifacts.length,
    capacityAxisCount: 0,
    capacityFixtureCount: 0,
    namedFixtureCount: 1,
    admittedProbeCount: editorProbeCount,
    artifacts: target.fixtureArtifacts,
    expectationDataPresentInTargetFixtures: false,
  },
  replay: {
    primary: {
      result: artifact("editor-automated/primary-result-v1.json"),
      executorId: primary.executorId,
      runtime: primary.runtime,
      checkCount: primary.checkCount,
      failureCount: 0,
    },
    independent: {
      result: artifact("editor-automated/independent-result-v1.json"),
      executorId: independent.executorId,
      runtime: independent.runtime,
      checkCount: independent.checkCount,
      failureCount: 0,
    },
    sharedSemanticLibrary: null,
    differentRuntimeAndParserImplementations: true,
    fixtureByteAgreement: true,
  },
  findings: {
    informationArchitectureVariant: "B_CANVAS_FOCUS",
    baselineMode: "STRUCTURED",
    baselineDirty: false,
    baselineCurrentRecheckState: "PENDING",
    baselineSaveEnabled: false,
    baselineAuthoritativePreviewEnabled: false,
    exactBrowserAndOperatingSystemTargetBound: false,
    browserObservationProduced: false,
    editorCapacityAxesAssigned: editorAxes.length,
  },
  formalRegistryBoundary: {
    cases: { ...formalCases, recordCount: 46 },
    oracles: { ...formalOracles, recordCount: 46 },
    issuedEditorAutomatedCaseCount: 0,
    issuedEditorAutomatedOracleCount: 0,
    appendPerformed: false,
  },
  sideEffects: {
    productCodeFilesChanged: 0,
    browserInvocations: 0,
    operatingSystemTargetInvocations: 0,
    webServerInvocations: 0,
    productSessionCreations: 0,
    productMutationInvocations: 0,
    authoritativePreviewInvocations: 0,
    accessibilityAssertionRuns: 0,
    j1Runs: 0,
    networkAttempts: 0,
    externalProviderAttempts: 0,
  },
  boundary: {
    fixtureAndGeneratorReferenceable: true,
    exactBrowserAndOperatingSystemTargetsPresent: false,
    exactProductTargetPresent: false,
    requiredBrowserAutomationExecutorManifestPresent: false,
    independentProductExecutionReplayPresent: false,
    executionClassExecutable: false,
    automatedEditorBehaviorProven: false,
    j1Passed: false,
    recordIssuanceAllowed: false,
    ticket19Closed: false,
  },
};

writeJson("editor-automated/editor-automated-fixture-static-a2-2026-08-17.json", evidence);
process.stdout.write(`${JSON.stringify({
  status: evidence.status,
  evidence: artifact("editor-automated/editor-automated-fixture-static-a2-2026-08-17.json"),
  primaryChecks: primary.checkCount,
  independentChecks: independent.checkCount,
  browserAutomationObserved: false,
  productExecutionObserved: false,
  j1Observed: false,
  recordIssuanceAllowed: false,
})}\n`);
