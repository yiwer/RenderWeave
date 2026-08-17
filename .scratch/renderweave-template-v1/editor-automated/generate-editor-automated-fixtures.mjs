import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import process from "node:process";

const SPEC = resolve(import.meta.dirname, "..");
const ROOT = "editor-automated";
const PROFILE = "renderweave-editor-automated-generator/1.0";
const EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0";
const BASELINE_ID = "baseline.editor-automated.minimal-v1";
const ADAPTER_ID = "renderweave-editor-automated-observation-adapter/1.0";
const SCENARIO_ID = "EDITOR-AUTOMATED-STRUCTURED-CLEAN-RECHECK-PENDING";
const IMPLEMENTATION_REVISION = "editor-automated-fixture-generator/1.0";
const FIXTURE_PATH = `${ROOT}/fixtures/named-editor-structured-clean-recheck-pending.json`;

function json(path) {
  return JSON.parse(readFileSync(resolve(SPEC, path), "utf8"));
}

function bytes(path) {
  return readFileSync(resolve(SPEC, path));
}

function sha(buffer) {
  return `sha256:${createHash("sha256").update(buffer).digest("hex")}`;
}

function encode(value) {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function write(path, value) {
  const absolute = resolve(SPEC, path);
  mkdirSync(dirname(absolute), { recursive: true });
  writeFileSync(absolute, encode(value));
}

function artifact(path) {
  const content = bytes(path);
  return { path, sha256: sha(content), byteLength: content.length };
}

function exactKeys(value, expected, label, check) {
  check(JSON.stringify(Object.keys(value)) === JSON.stringify(expected), `${label} keys`);
}

function baseline() {
  return {
    fixtureVersion: "renderweave-editor-automated-baseline/1.0",
    baselineId: BASELINE_ID,
    executionClass: EXECUTION_CLASS,
    authorityBoundary: "fixture-only EditorSession context; not a product payload, browser session, or authoritative preview",
    informationArchitecture: {
      variant: "B_CANVAS_FOCUS",
      canvasAuthority: "NON_AUTHORITATIVE_AUTHORING_FEEDBACK",
      leftNavigationEntries: ["STRUCTURE", "NODES", "ASSETS", "DEFINITIONS", "EXCHANGE"],
      rightInspector: "COLLAPSIBLE",
      bottomDockActions: ["NAVIGATION", "INSPECTOR", "DATA", "PROBLEMS", "AUTHORITATIVE_PREVIEW", "SAVE"]
    },
    trustedCurrentContext: {
      lifecycle: "ACTIVE",
      readiness: "READY",
      revision: 0,
      exactProfileSupport: "SUPPORTED",
      canonicalIntegrity: "PASS",
      productContentHashConstructed: false
    },
    editorSessionContext: {
      mode: "STRUCTURED",
      dirty: false,
      canonicalSemanticDiffCount: 0,
      authoredMutationState: "IDLE",
      currentRecheckState: "PENDING",
      saveEnabled: false,
      undoDepth: 0,
      redoDepth: 0,
      selectedEntityIds: [],
      problemPanelState: "EMPTY",
      localRecoveryDraft: "ABSENT",
      authoritativePreview: {
        enabled: false,
        resultSlot: "EMPTY",
        activeOperation: "ABSENT",
        basisDigest: "ABSENT"
      }
    },
    environmentContext: {
      targetKind: "SUPPORTED_DESKTOP_FIXTURE_ONLY",
      zoomPercent: 100,
      reducedMotion: false,
      highContrast: false,
      exactBrowserAndOperatingSystemTargetBound: false
    },
    faultSchedule: { kind: "NONE" },
    browserAutomationInvoked: false,
    webServerInvoked: false,
    productCodeInvoked: false,
    externalReadsAllowed: false,
    networkReadsAllowed: false,
    productMutationAllowedByFixtureGeneration: false
  };
}

function fixtureContract() {
  return {
    artifactVersion: "renderweave-editor-automated-fixture-contract/1.0",
    fixtureVersion: "renderweave-editor-automated-fixture/1.0",
    status: "FROZEN_STATIC_FIXTURE_CONTRACT",
    executionClass: EXECUTION_CLASS,
    serialization: {
      encoding: "UTF-8 without BOM",
      lineEnding: "LF",
      indentationSpaces: 2,
      finalLfRequired: true,
      memberOrder: `exactly the contract order emitted by ${PROFILE}`
    },
    allowedModes: ["NAMED_SCENARIO"],
    capacityBoundary: {
      supported: false,
      assignedAxisCount: 0,
      reason: "Ticket19 assigns no capacity axis to EXEC::EDITOR_AUTOMATED::1.0"
    },
    namedScenario: {
      parameterSchemaId: "renderweave-named-scenario-parameters/1.0",
      allowedScenarioIds: [SCENARIO_ID],
      entrypoint: "EDITOR_SESSION_BASELINE_CONTRACT_CHECK",
      expectedObservationProfile: ADAPTER_ID,
      parametersReferenceExactFixtureBytes: true,
      productMutationAllowed: false,
      browserAutomationAllowedDuringFixtureGeneration: false
    },
    fixtureTopLevelOrder: [
      "fixtureVersion",
      "generatorProfile",
      "executionClass",
      "baseline",
      "scenario",
      "observationAdapter",
      "targetContract"
    ],
    namedScenarioOrder: ["mode", "scenarioId", "operationId", "entrypoint", "faultSchedule"],
    forbiddenFixtureMembers: [
      "expectedTerminal",
      "expectedAssertions",
      "plannedAssertions",
      "plannedOracleId",
      "requirementIds",
      "resolvedCode",
      "resolvedKind",
      "contentHash",
      "latest",
      "default",
      "script"
    ],
    evidenceBoundary: {
      fixtureGenerationMayProve: "deterministic closed fixture bytes, a fixture-only Canvas Focus Structured Editor baseline context, exact admitted-probe adapter coverage, and absence of browser/product/network execution",
      fixtureGenerationCannotProve: "supported-browser behavior, operating-system behavior, accessibility tree semantics, focus or announcement order, keyboard accessibility, high-contrast or reduced-motion behavior, zoom behavior, save/conflict/recovery/preview orchestration, product writes, authoritative preview, J1, record issuance, or execution-class readiness"
    }
  };
}

function observationAdapter() {
  const profile = json("conformance-probe-profile-v1.json");
  const admitted = profile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS));
  return {
    artifactVersion: "renderweave-editor-automated-observation-adapter/1.0",
    adapterId: ADAPTER_ID,
    status: "FROZEN_CONTRACT_BROWSER_TARGET_PENDING",
    executionClass: EXECUTION_CLASS,
    probeProfile: profile.candidateProbeProfileId,
    closedObservationVersion: "renderweave-editor-automated-closed-observation/1.0",
    observationBoundary: "one exact supported-browser and operating-system target after one named user-visible command or state transition settles",
    genericJsonPathAllowed: false,
    arbitraryScriptAllowed: false,
    fallbackAllowed: false,
    expectedValuesVisibleToTarget: false,
    mappings: admitted.map((probe) => ({
      probeId: probe.probeId,
      valueType: probe.valueType,
      source: `closedObservation.${probe.probeId}`,
      absentPolicy: probe.allowedOperators.includes("ABSENT") ? "EXPLICIT_ABSENT" : "MUST_BE_PRESENT"
    })),
    mappingCount: admitted.length,
    fixtureOnlyBoundary: {
      closedObservationProduced: false,
      browserTargetBound: false,
      browserAutomationExecuted: false,
      accessibilityAssertionsEvaluated: false,
      j1Evaluated: false
    },
    evidenceBoundary: "This adapter freezes exact probe extraction only. The named fixture does not produce an observation; one exact target and its required browser-automation runner must later populate and replay every mapped field."
  };
}

function namedFixture() {
  return {
    fixtureVersion: "renderweave-editor-automated-fixture/1.0",
    generatorProfile: PROFILE,
    executionClass: EXECUTION_CLASS,
    baseline: artifact(`${ROOT}/baseline-v1.json`),
    scenario: {
      mode: "NAMED_SCENARIO",
      scenarioId: SCENARIO_ID,
      operationId: "main",
      entrypoint: "EDITOR_SESSION_BASELINE_CONTRACT_CHECK",
      faultSchedule: { kind: "NONE" }
    },
    observationAdapter: {
      adapterId: ADAPTER_ID,
      ...artifact(`${ROOT}/observation-adapter-v1.json`)
    },
    targetContract: {
      fixtureOnlyContext: true,
      exactSupportedBrowserAndOperatingSystemTargetRequired: true,
      exactTargetBoundByThisFixture: false,
      browserAutomationExecutedByThisFixture: false,
      accessibilityTreeObservedByThisFixture: false,
      focusOrAnnouncementObservedByThisFixture: false,
      editorCommandExecutedByThisFixture: false,
      productSessionCreatedByThisFixture: false,
      productWriteExecutedByThisFixture: false,
      authoritativePreviewExecutedByThisFixture: false,
      j1ExecutedByThisFixture: false
    }
  };
}

function scenarioCatalog() {
  return {
    artifactVersion: "renderweave-editor-automated-scenario-catalog/1.0",
    status: "FROZEN_1_NAMED_FIXTURE_ONLY",
    generatorProfile: PROFILE,
    executionClass: EXECUTION_CLASS,
    sourceRule: "The sole scenario is a closed fixture-only EditorSession baseline context derived from Ticket17 Canvas Focus and Ticket18 canonical-baseline entry; it is not an automated product case.",
    baseline: artifact(`${ROOT}/baseline-v1.json`),
    observationAdapter: artifact(`${ROOT}/observation-adapter-v1.json`),
    scenarios: [{
      scenarioId: SCENARIO_ID,
      mode: "NAMED_SCENARIO",
      parameters: {
        mode: "NAMED_SCENARIO",
        scenarioId: SCENARIO_ID,
        fixtureArtifactPath: FIXTURE_PATH,
        fixtureArtifactSha256: artifact(FIXTURE_PATH).sha256,
        expectedObservationProfile: ADAPTER_ID
      },
      fixtureArtifactPath: FIXTURE_PATH
    }],
    scenarioCount: 1,
    capacityScenarioCount: 0,
    namedScenarioCount: 1
  };
}

function goldens() {
  const scenario = json(`${ROOT}/capacity-scenarios-v1.json`).scenarios[0];
  return {
    artifactVersion: "renderweave-editor-automated-generator-goldens/1.0",
    status: "FROZEN_STATIC_GOLDENS",
    generatorProfile: PROFILE,
    implementationRevision: IMPLEMENTATION_REVISION,
    vectors: [{
      scenarioId: scenario.scenarioId,
      mode: scenario.mode,
      parameters: scenario.parameters,
      expectedFixtureArtifact: artifact(FIXTURE_PATH)
    }],
    goldenCount: 1,
    capacityGoldenCount: 0,
    namedGoldenCount: 1
  };
}

function targetManifest() {
  return {
    artifactVersion: "renderweave-editor-automated-generator-target/1.0",
    targetId: "EDITOR_AUTOMATED_GENERATOR_TARGET::FIXTURE::1.0",
    generatorProfile: PROFILE,
    status: "FROZEN_STATIC_GENERATOR_TARGET",
    implementationRevision: IMPLEMENTATION_REVISION,
    entrypoint: artifact(`${ROOT}/generate-editor-automated-fixtures.mjs`),
    probeProfile: artifact("conformance-probe-profile-v1.json"),
    baseline: artifact(`${ROOT}/baseline-v1.json`),
    fixtureContract: artifact(`${ROOT}/fixture-contract-v1.json`),
    observationAdapter: artifact(`${ROOT}/observation-adapter-v1.json`),
    scenarioCatalog: artifact(`${ROOT}/capacity-scenarios-v1.json`),
    goldenVectors: artifact(`${ROOT}/generator-goldens-v1.json`),
    fixtureArtifacts: [artifact(FIXTURE_PATH)],
    expectedScenarioCount: 1,
    capacityAxisCount: 0,
    productTarget: false,
    browserTarget: false,
    productExecutionAllowed: false,
    browserAutomationAllowed: false,
    networkReadsAllowed: false,
    environmentReadsAllowed: false,
    currentTimeReadsAllowed: false
  };
}

function implementationManifest() {
  return {
    artifactVersion: "renderweave-editor-automated-generator-implementation/1.0",
    generatorProfile: PROFILE,
    status: "FROZEN_GOLDENS_PRESENT_STATIC_ONLY",
    implementationRevision: IMPLEMENTATION_REVISION,
    runtime: "Node.js >=24",
    entrypoint: artifact(`${ROOT}/generate-editor-automated-fixtures.mjs`),
    probeProfile: artifact("conformance-probe-profile-v1.json"),
    baseline: artifact(`${ROOT}/baseline-v1.json`),
    fixtureContract: artifact(`${ROOT}/fixture-contract-v1.json`),
    observationAdapter: artifact(`${ROOT}/observation-adapter-v1.json`),
    scenarioCatalog: artifact(`${ROOT}/capacity-scenarios-v1.json`),
    goldenVectors: artifact(`${ROOT}/generator-goldens-v1.json`),
    targetManifest: artifact(`${ROOT}/generator-target-manifest-v1.json`),
    omittedExpectationKeys: [
      "expectedTerminal",
      "expectedAssertions",
      "plannedAssertions",
      "plannedOracleId",
      "requirementIds",
      "resolvedCode",
      "resolvedKind"
    ],
    productExecutionAllowed: false,
    browserAutomationAllowed: false,
    environmentReadsAllowed: false,
    networkReadsAllowed: false,
    currentTimeReadsAllowed: false,
    hiddenDefaultsAllowed: false
  };
}

function expectedArtifacts() {
  return {
    [`${ROOT}/baseline-v1.json`]: baseline(),
    [`${ROOT}/fixture-contract-v1.json`]: fixtureContract(),
    [`${ROOT}/observation-adapter-v1.json`]: observationAdapter(),
    [FIXTURE_PATH]: namedFixture(),
    [`${ROOT}/capacity-scenarios-v1.json`]: scenarioCatalog(),
    [`${ROOT}/generator-goldens-v1.json`]: goldens(),
    [`${ROOT}/generator-target-manifest-v1.json`]: targetManifest(),
    [`${ROOT}/generator-implementation-manifest-v1.json`]: implementationManifest()
  };
}

function bootstrap() {
  write(`${ROOT}/baseline-v1.json`, baseline());
  write(`${ROOT}/fixture-contract-v1.json`, fixtureContract());
  write(`${ROOT}/observation-adapter-v1.json`, observationAdapter());
  write(FIXTURE_PATH, namedFixture());
  write(`${ROOT}/capacity-scenarios-v1.json`, scenarioCatalog());
  write(`${ROOT}/generator-goldens-v1.json`, goldens());
  write(`${ROOT}/generator-target-manifest-v1.json`, targetManifest());
  write(`${ROOT}/generator-implementation-manifest-v1.json`, implementationManifest());
}

function verify() {
  let checkCount = 0;
  const check = (condition, message) => {
    checkCount += 1;
    if (!condition) throw new Error(message);
  };

  const expected = expectedArtifacts();
  for (const [path, value] of Object.entries(expected)) {
    const actual = bytes(path);
    check(actual.equals(encode(value)), `${path} exact bytes`);
    check(!actual.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf])), `${path} no BOM`);
    check(!actual.includes(Buffer.from("\r")), `${path} LF only`);
    check(actual.at(-1) === 0x0a, `${path} final LF`);
  }

  const base = json(`${ROOT}/baseline-v1.json`);
  exactKeys(base, ["fixtureVersion", "baselineId", "executionClass", "authorityBoundary", "informationArchitecture", "trustedCurrentContext", "editorSessionContext", "environmentContext", "faultSchedule", "browserAutomationInvoked", "webServerInvoked", "productCodeInvoked", "externalReadsAllowed", "networkReadsAllowed", "productMutationAllowedByFixtureGeneration"], "baseline", check);
  check(base.baselineId === BASELINE_ID, "baseline id");
  check(base.informationArchitecture.variant === "B_CANVAS_FOCUS", "Canvas Focus variant");
  check(base.editorSessionContext.mode === "STRUCTURED", "Structured mode");
  check(base.editorSessionContext.dirty === false && base.editorSessionContext.canonicalSemanticDiffCount === 0, "clean semantic baseline");
  check(base.editorSessionContext.currentRecheckState === "PENDING", "authoritative recheck pending");
  check(base.editorSessionContext.saveEnabled === false, "clean save disabled");
  check(base.editorSessionContext.authoritativePreview.enabled === false, "preview disabled while recheck pending");
  check(base.trustedCurrentContext.productContentHashConstructed === false, "no product content hash");
  check(base.environmentContext.exactBrowserAndOperatingSystemTargetBound === false, "no browser target binding");
  for (const key of ["browserAutomationInvoked", "webServerInvoked", "productCodeInvoked", "externalReadsAllowed", "networkReadsAllowed", "productMutationAllowedByFixtureGeneration"]) check(base[key] === false, `baseline ${key} false`);

  const contract = json(`${ROOT}/fixture-contract-v1.json`);
  check(JSON.stringify(contract.allowedModes) === JSON.stringify(["NAMED_SCENARIO"]), "named-only mode");
  check(contract.capacityBoundary.supported === false && contract.capacityBoundary.assignedAxisCount === 0, "zero capacity axes");
  check(JSON.stringify(contract.namedScenario.allowedScenarioIds) === JSON.stringify([SCENARIO_ID]), "closed scenario id");

  const profile = json("conformance-probe-profile-v1.json");
  const admitted = profile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS));
  const adapter = json(`${ROOT}/observation-adapter-v1.json`);
  check(adapter.mappingCount === admitted.length, "adapter mapping count");
  check(JSON.stringify(adapter.mappings.map((entry) => entry.probeId)) === JSON.stringify(admitted.map((entry) => entry.probeId)), "adapter exact admitted order");
  for (let i = 0; i < admitted.length; i += 1) {
    const probe = admitted[i];
    const mapping = adapter.mappings[i];
    check(mapping.valueType === probe.valueType, `${probe.probeId} type`);
    check(mapping.source === `closedObservation.${probe.probeId}`, `${probe.probeId} source`);
    check(mapping.absentPolicy === (probe.allowedOperators.includes("ABSENT") ? "EXPLICIT_ABSENT" : "MUST_BE_PRESENT"), `${probe.probeId} absent policy`);
  }

  const fixture = json(FIXTURE_PATH);
  exactKeys(fixture, contract.fixtureTopLevelOrder, "fixture", check);
  exactKeys(fixture.scenario, contract.namedScenarioOrder, "fixture scenario", check);
  check(fixture.scenario.scenarioId === SCENARIO_ID, "fixture scenario id");
  check(fixture.baseline.sha256 === artifact(`${ROOT}/baseline-v1.json`).sha256, "fixture baseline digest");
  check(fixture.observationAdapter.sha256 === artifact(`${ROOT}/observation-adapter-v1.json`).sha256, "fixture adapter digest");
  for (const [key, value] of Object.entries(fixture.targetContract)) if (key !== "fixtureOnlyContext" && key !== "exactSupportedBrowserAndOperatingSystemTargetRequired") check(value === false, `target ${key} false`);
  for (const forbidden of contract.forbiddenFixtureMembers) check(!Object.prototype.hasOwnProperty.call(fixture, forbidden) && !JSON.stringify(fixture).includes(`\"${forbidden}\"`), `fixture forbids ${forbidden}`);

  const scenarios = json(`${ROOT}/capacity-scenarios-v1.json`);
  check(scenarios.scenarioCount === 1 && scenarios.capacityScenarioCount === 0 && scenarios.namedScenarioCount === 1, "scenario counts");
  check(scenarios.scenarios[0].parameters.fixtureArtifactSha256 === artifact(FIXTURE_PATH).sha256, "scenario fixture digest");
  const vectors = json(`${ROOT}/generator-goldens-v1.json`);
  check(vectors.goldenCount === 1 && vectors.capacityGoldenCount === 0 && vectors.namedGoldenCount === 1, "golden counts");
  check(vectors.vectors[0].expectedFixtureArtifact.sha256 === artifact(FIXTURE_PATH).sha256, "golden fixture digest");

  const target = json(`${ROOT}/generator-target-manifest-v1.json`);
  check(target.expectedScenarioCount === 1 && target.capacityAxisCount === 0, "target counts");
  for (const key of ["productTarget", "browserTarget", "productExecutionAllowed", "browserAutomationAllowed", "networkReadsAllowed", "environmentReadsAllowed", "currentTimeReadsAllowed"]) check(target[key] === false, `target ${key} false`);
  check(target.fixtureArtifacts.length === 1 && target.fixtureArtifacts[0].sha256 === artifact(FIXTURE_PATH).sha256, "target fixture binding");

  const implementation = json(`${ROOT}/generator-implementation-manifest-v1.json`);
  check(implementation.targetManifest.sha256 === artifact(`${ROOT}/generator-target-manifest-v1.json`).sha256, "implementation target binding");
  check(implementation.browserAutomationAllowed === false && implementation.productExecutionAllowed === false, "implementation execution disabled");

  return checkCount;
}

function writePrimaryResult(checkCount) {
  write(`${ROOT}/primary-result-v1.json`, {
    resultVersion: "renderweave-editor-automated-fixture-generator-result/1.0",
    executorId: "EDITOR_AUTOMATED_FIXTURE_GENERATOR::NODE::1.0",
    role: "primary-editor-automated-fixture-generator-replayer",
    status: "PASS",
    checkCount,
    failureCount: 0,
    runtime: `Node.js ${process.version}`,
    generatorTargetSha256: artifact(`${ROOT}/generator-target-manifest-v1.json`).sha256,
    fixtureCount: 1,
    browserAutomationObserved: false,
    productExecutionObserved: false,
    j1Observed: false,
    recordIssuanceAllowed: false,
    implementationRevision: IMPLEMENTATION_REVISION,
    targetManifest: artifact(`${ROOT}/generator-target-manifest-v1.json`),
    entrypoint: artifact(`${ROOT}/generate-editor-automated-fixtures.mjs`)
  });
}

const command = process.argv[2];
if (command === "bootstrap") {
  bootstrap();
  const count = verify();
  writePrimaryResult(count);
  console.log(JSON.stringify({ status: "PASS", checkCount: count, fixtureCount: 1 }));
} else if (command === "verify") {
  const count = verify();
  writePrimaryResult(count);
  console.log(JSON.stringify({ status: "PASS", checkCount: count, fixtureCount: 1 }));
} else if (command === "emit" && process.argv[3] === SCENARIO_ID) {
  process.stdout.write(bytes(FIXTURE_PATH));
} else {
  throw new Error(`usage: node generate-editor-automated-fixtures.mjs <bootstrap|verify|emit ${SCENARIO_ID}>`);
}
