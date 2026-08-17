import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");
const PROFILE = "renderweave-design-input-expression-generator/1.0";
const EXECUTION_CLASS = "EXEC::DESIGN_INPUT_EXPRESSION::1.0";
const BASELINE_ID = "baseline.design-input-expression.minimal-v1";
const ADAPTER_ID = "renderweave-design-input-expression-observation-adapter/1.0";
const ROOT = "design-input-expression";
const BASELINE_PATH = `${ROOT}/baseline-v1.json`;
const CONTRACT_PATH = `${ROOT}/fixture-contract-v1.json`;
const ADAPTER_PATH = `${ROOT}/observation-adapter-v1.json`;
const CATALOG_PATH = `${ROOT}/capacity-scenarios-v1.json`;
const GOLDENS_PATH = `${ROOT}/generator-goldens-v1.json`;
const TARGET_PATH = `${ROOT}/generator-target-manifest-v1.json`;
const IMPLEMENTATION_PATH = `${ROOT}/generator-implementation-manifest-v1.json`;
const COVERAGE_PATH = "conformance-capacity-coverage-v1.json";
const PROBE_PROFILE_PATH = "conformance-probe-profile-v1.json";
const EXPECTED_AXIS_COUNT = 65;
const EXPECTED_CAPACITY_SCENARIO_COUNT = 195;
const EXPECTED_TOTAL_SCENARIO_COUNT = 196;
const ALLOWED_ENCODINGS = new Set(["CANONICAL_INTEGER", "CANONICAL_DECIMAL", "ENUM_TOKEN"]);
const ALLOWED_COMPARATORS = new Set(["ENUM_EXACT", "EXACT", "MAX_INCLUSIVE", "MIN_EXCLUSIVE", "MIN_INCLUSIVE"]);
const OMITTED_EXPECTATION_KEYS = [
  "expectedTerminal",
  "expectedAssertions",
  "plannedAssertions",
  "plannedOracleId",
  "requirementIds",
  "resolvedCode",
  "resolvedKind",
];

function absolute(relativePath) {
  return resolve(SPEC, relativePath);
}

function bytes(relativePath) {
  return readFileSync(absolute(relativePath));
}

function json(relativePath) {
  return JSON.parse(bytes(relativePath).toString("utf8"));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function artifact(relativePath) {
  const content = bytes(relativePath);
  return { path: relativePath, sha256: `sha256:${sha256(content)}`, byteLength: content.length };
}

function serialize(value) {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function write(relativePath, content) {
  const target = absolute(relativePath);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, Buffer.isBuffer(content) ? content : serialize(content));
  return artifact(relativePath);
}

function check(condition, message) {
  if (!condition) throw new Error(message);
}

function expectedBaseline() {
  return {
    fixtureVersion: "renderweave-design-input-expression-baseline/1.0",
    baselineId: BASELINE_ID,
    executionClass: EXECUTION_CLASS,
    authorityContext: {
      staticSchemaRef: "system-empty@v1",
      designDsl: {
        dslVersion: "renderweave-design/1.0",
        expressionProfile: "renderweave-expression/1.0",
        displayName: "Baseline",
        definitions: [],
        designRoot: {
          nodeId: "00000000-0000-4000-8000-000000000001",
          kind: "canvas",
          widthMm: 210,
          heightMm: 297,
          bindings: [],
          children: [],
        },
      },
    },
    renderInput: { rootDocument: {}, customValues: [] },
    faultSchedule: { kind: "NONE" },
    externalReadsAllowed: false,
    networkReadsAllowed: false,
    currentTimeReadsAllowed: false,
    productMutationAllowedByFixtureGeneration: false,
  };
}

function axes() {
  const selected = json(COVERAGE_PATH).axes.filter((axis) => axis.executionClass === EXECUTION_CLASS);
  check(selected.length === EXPECTED_AXIS_COUNT, `expected ${EXPECTED_AXIS_COUNT} DESIGN_INPUT_EXPRESSION axes, found ${selected.length}`);
  const ids = new Set();
  for (const axis of selected) {
    check(!ids.has(axis.limitId), `duplicate limitId: ${axis.limitId}`);
    ids.add(axis.limitId);
    check(ALLOWED_ENCODINGS.has(axis.valueEncoding), `${axis.limitId} valueEncoding not admitted`);
    check(ALLOWED_COMPARATORS.has(axis.comparator), `${axis.limitId} comparator not admitted`);
    check(Array.isArray(axis.variants) && axis.variants.length === 3, `${axis.limitId} variants incomplete`);
    check(axis.variants.map((entry) => entry.variant).join("|") === "below|at|above", `${axis.limitId} variant order drifted`);
  }
  return selected;
}

function plannedAssertions(axis, variant) {
  return [
    {
      assertionId: "A001",
      probeId: "operation.accepted",
      operator: "EQ",
      expected: { kind: "LITERAL", value: variant.expectedAssertions.accepted },
    },
    variant.expectedAssertions.terminalCode === undefined
      ? { assertionId: "A002", probeId: "operation.terminalCode", operator: "ABSENT" }
      : {
          assertionId: "A002",
          probeId: "operation.terminalCode",
          operator: "EQ",
          expected: { kind: "LITERAL", value: variant.expectedAssertions.terminalCode },
        },
    variant.expectedAssertions.terminalStage === undefined
      ? { assertionId: "A003", probeId: "operation.terminalStage", operator: "ABSENT" }
      : {
          assertionId: "A003",
          probeId: "operation.terminalStage",
          operator: "EQ",
          expected: { kind: "LITERAL", value: variant.expectedAssertions.terminalStage },
        },
    {
      assertionId: "A004",
      probeId: "capacity.limitId",
      operator: "EQ",
      expected: { kind: "LITERAL", value: axis.limitId },
    },
    {
      assertionId: "A005",
      probeId: "capacity.observedValue",
      operator: "EQ",
      expected: { kind: "LITERAL", value: variant.stimulusValue },
    },
    {
      assertionId: "A006",
      probeId: "capacity.reservationReached",
      operator: "EQ",
      expected: { kind: "LITERAL", value: true },
    },
    variant.expectedAssertions.zeroBoundary === undefined
      ? { assertionId: "A007", probeId: "capacity.zeroBoundary", operator: "ABSENT" }
      : {
          assertionId: "A007",
          probeId: "capacity.zeroBoundary",
          operator: "EQ",
          expected: { kind: "LITERAL", value: variant.expectedAssertions.zeroBoundary },
        },
    {
      assertionId: "A008",
      probeId: "operation.downstreamEffects",
      operator: "SEQUENCE_EQ",
      expected: { kind: "LITERAL", value: variant.expectedAssertions.downstreamEffects },
    },
  ];
}

function parameters(axis, variant) {
  return {
    comparator: axis.comparator,
    contractStage: axis.resolvedOracle.contractStage,
    deltaId: axis.deltaId,
    executionClass: axis.executionClass,
    limitId: axis.limitId,
    limitValue: axis.limitValue,
    mode: "CAPACITY_BOUNDARY",
    plannedAssertions: plannedAssertions(axis, variant),
    plannedOracleId: variant.plannedOracleId,
    publicRenderStage: axis.resolvedOracle.publicRenderStage,
    requirementIds: axis.requirementIds,
    reservationPoint: axis.resolvedOracle.reservationPoint,
    resolvedCode: axis.resolvedOracle.code,
    resolvedKind: axis.resolvedOracle.kind,
    stimulusValue: variant.stimulusValue,
    valueEncoding: axis.valueEncoding,
    variant: variant.variant,
    zeroBoundary: axis.resolvedOracle.zeroBoundary,
  };
}

function slug(limitId) {
  return limitId
    .replaceAll(/([a-z0-9])([A-Z])/gu, "$1-$2")
    .replaceAll(/[^a-zA-Z0-9]+/gu, "-")
    .replaceAll(/^-+|-+$/gu, "")
    .toLowerCase();
}

function fixturePath(axisIndex, limitId, variant) {
  return `${ROOT}/fixtures/cap-${String(axisIndex + 1).padStart(3, "0")}-${slug(limitId)}-${variant}.json`;
}

function commonFixture() {
  const baseline = artifact(BASELINE_PATH);
  const adapter = artifact(ADAPTER_PATH);
  return {
    fixtureVersion: "renderweave-design-input-expression-fixture/1.0",
    generatorProfile: PROFILE,
    executionClass: EXECUTION_CLASS,
    baseline: {
      baselineId: BASELINE_ID,
      path: baseline.path,
      sha256: baseline.sha256,
      byteLength: baseline.byteLength,
    },
    observationAdapter: { adapterId: ADAPTER_ID, path: adapter.path, sha256: adapter.sha256 },
  };
}

function validateObservedValue(value, encoding) {
  check(typeof value === "string", "observed value must be exact string");
  if (encoding === "CANONICAL_INTEGER") {
    check(/^-?(0|[1-9][0-9]*)$/u.test(value), `noncanonical integer: ${value}`);
  } else if (encoding === "CANONICAL_DECIMAL") {
    check(/^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/u.test(value), `noncanonical decimal: ${value}`);
  } else {
    check(encoding === "ENUM_TOKEN" && value.length > 0, "invalid enum token");
  }
}

function capacityFixture(axis, variant) {
  validateObservedValue(variant.stimulusValue, axis.valueEncoding);
  const common = commonFixture();
  return {
    fixtureVersion: common.fixtureVersion,
    generatorProfile: common.generatorProfile,
    executionClass: common.executionClass,
    baseline: common.baseline,
    scenario: {
      mode: "CAPACITY_BOUNDARY",
      scenarioId: variant.caseId,
      operationId: "main",
      entrypoint: "DESIGN_INPUT_EXPRESSION_CAPACITY_GUARD",
      guardContractId: "renderweave-design-input-expression-capacity-guard/1.0",
      limitId: axis.limitId,
      observedValue: variant.stimulusValue,
      valueEncoding: axis.valueEncoding,
      comparator: axis.comparator,
      variant: variant.variant,
      contractStage: axis.resolvedOracle.contractStage,
      publicRenderStage: axis.resolvedOracle.publicRenderStage,
      reservationPoint: axis.resolvedOracle.reservationPoint,
      zeroBoundary: axis.resolvedOracle.zeroBoundary,
      faultSchedule: { kind: "NONE" },
    },
    observationAdapter: common.observationAdapter,
    targetContract: {
      exactProductionGuardRequired: true,
      duplicateGuardImplementationForbidden: true,
      productApiSurfaceCreated: false,
      parserOrCanonicalizerExecutedByThisProbe: false,
      semanticOrExpressionEvaluatorExecutedByThisProbe: false,
      fullAuthoringOrRenderInputPathProvenByThisProbe: false,
    },
  };
}

function namedFixture() {
  const common = commonFixture();
  return {
    fixtureVersion: common.fixtureVersion,
    generatorProfile: common.generatorProfile,
    executionClass: common.executionClass,
    baseline: common.baseline,
    scenario: {
      mode: "NAMED_SCENARIO",
      scenarioId: "DESIGN-INPUT-EXPRESSION-BASELINE-NOOP",
      operationId: "main",
      entrypoint: "BASELINE_CONTRACT_CHECK",
      faultSchedule: { kind: "NONE" },
    },
    observationAdapter: common.observationAdapter,
    targetContract: {
      exactProductionGuardRequired: false,
      duplicateGuardImplementationForbidden: true,
      productApiSurfaceCreated: false,
      parserOrCanonicalizerExecutedByThisProbe: false,
      semanticOrExpressionEvaluatorExecutedByThisProbe: false,
      fullAuthoringOrRenderInputPathProvenByThisProbe: false,
    },
  };
}

function buildScenarioCatalog(namedArtifact) {
  const scenarios = [];
  for (const [axisIndex, axis] of axes().entries()) {
    for (const variant of axis.variants) {
      scenarios.push({
        scenarioId: variant.caseId,
        mode: "CAPACITY_BOUNDARY",
        parameters: parameters(axis, variant),
        fixtureArtifactPath: fixturePath(axisIndex, axis.limitId, variant.variant),
      });
    }
  }
  scenarios.push({
    scenarioId: "DESIGN-INPUT-EXPRESSION-BASELINE-NOOP",
    mode: "NAMED_SCENARIO",
    parameters: {
      mode: "NAMED_SCENARIO",
      scenarioId: "DESIGN-INPUT-EXPRESSION-BASELINE-NOOP",
      fixtureArtifactPath: namedArtifact.path,
      fixtureArtifactSha256: namedArtifact.sha256,
      expectedObservationProfile: ADAPTER_ID,
    },
    fixtureArtifactPath: namedArtifact.path,
  });
  check(scenarios.length === EXPECTED_TOTAL_SCENARIO_COUNT, "scenario count drifted");
  return {
    artifactVersion: "renderweave-design-input-expression-scenario-catalog/1.0",
    status: "FROZEN_195_CAPACITY_PLUS_1_NAMED",
    generatorProfile: PROFILE,
    executionClass: EXECUTION_CLASS,
    sourceRule: "The 195 CAPACITY_BOUNDARY parameter objects are copied from the fully expanded 65-axis mapping; no matrix default or value interpretation remains. The named scenario is closed locally.",
    baseline: artifact(BASELINE_PATH),
    observationAdapter: artifact(ADAPTER_PATH),
    scenarios,
    scenarioCount: scenarios.length,
    capacityScenarioCount: EXPECTED_CAPACITY_SCENARIO_COUNT,
    namedScenarioCount: 1,
  };
}

function fixtureForScenario(scenario, byLimit) {
  if (scenario.mode === "NAMED_SCENARIO") {
    check(scenario.parameters.scenarioId === "DESIGN-INPUT-EXPRESSION-BASELINE-NOOP", "unknown named scenario");
    check(scenario.parameters.expectedObservationProfile === ADAPTER_ID, "named adapter mismatch");
    const expected = serialize(namedFixture());
    check(scenario.parameters.fixtureArtifactSha256 === `sha256:${sha256(expected)}`, "named fixture digest mismatch");
    return namedFixture();
  }
  const axis = byLimit.get(scenario.parameters.limitId);
  check(Boolean(axis), `unknown limitId: ${scenario.parameters.limitId}`);
  const variant = axis.variants.find((entry) => entry.variant === scenario.parameters.variant);
  check(Boolean(variant), `unknown variant: ${scenario.scenarioId}`);
  check(serialize(scenario.parameters).equals(serialize(parameters(axis, variant))), `parameter drift: ${scenario.scenarioId}`);
  return capacityFixture(axis, variant);
}

function validateContractAndAdapter() {
  check(serialize(expectedBaseline()).equals(bytes(BASELINE_PATH)), "baseline bytes drifted");
  const contract = json(CONTRACT_PATH);
  check(contract.status === "FROZEN_STATIC_FIXTURE_CONTRACT", "fixture contract status drifted");
  check(contract.allowedModes.join("|") === "CAPACITY_BOUNDARY|NAMED_SCENARIO", "fixture modes drifted");
  check(contract.capacityBoundary.entrypoint === "DESIGN_INPUT_EXPRESSION_CAPACITY_GUARD", "guard entrypoint drifted");
  check(contract.capacityBoundary.targetContract.exactProductionGuardRequired === true, "exact guard requirement missing");
  check(contract.capacityBoundary.targetContract.fullAuthoringOrRenderInputPathProvenByThisProbe === false, "static boundary drifted");
  check(contract.evidenceBoundary.fixtureGenerationCannotProve.includes("capacity enforcement"), "evidence boundary incomplete");

  const profile = json(PROBE_PROFILE_PATH);
  const adapter = json(ADAPTER_PATH);
  const admitted = profile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS));
  check(admitted.length === 25 && adapter.mappingCount === 25, "adapter mapping count drifted");
  check(adapter.genericJsonPathAllowed === false && adapter.arbitraryScriptAllowed === false, "adapter is not closed");
  check(adapter.expectedValuesVisibleToTarget === false, "adapter exposes expected values");
  for (const [index, probe] of admitted.entries()) {
    const mapping = adapter.mappings[index];
    check(mapping.probeId === probe.probeId, `adapter probe order drifted: ${probe.probeId}`);
    check(mapping.valueType === probe.valueType, `adapter type drifted: ${probe.probeId}`);
    check(mapping.source === `closedObservation.${probe.probeId}`, `adapter source drifted: ${probe.probeId}`);
    const absentPolicy = probe.allowedOperators.includes("ABSENT") ? "EXPLICIT_ABSENT" : "MUST_BE_PRESENT";
    check(mapping.absentPolicy === absentPolicy, `adapter absent policy drifted: ${probe.probeId}`);
  }
}

function targetValue(fixtureArtifacts) {
  return {
    artifactVersion: "renderweave-design-input-expression-generator-target/1.0",
    targetId: "DESIGN_INPUT_EXPRESSION_GENERATOR_TARGET::FIXTURE::1.0",
    generatorProfile: PROFILE,
    status: "FROZEN_STATIC_GENERATOR_TARGET",
    implementationRevision: "design-input-expression-fixture-generator/1.0",
    entrypoint: artifact(`${ROOT}/generate-design-input-expression-fixtures.mjs`),
    baseline: artifact(BASELINE_PATH),
    fixtureContract: artifact(CONTRACT_PATH),
    observationAdapter: artifact(ADAPTER_PATH),
    scenarioCatalog: artifact(CATALOG_PATH),
    goldenVectors: artifact(GOLDENS_PATH),
    fixtureArtifacts,
    expectedScenarioCount: EXPECTED_TOTAL_SCENARIO_COUNT,
    productTarget: false,
    productExecutionAllowed: false,
    networkReadsAllowed: false,
    environmentReadsAllowed: false,
    currentTimeReadsAllowed: false,
  };
}

function implementationValue() {
  return {
    artifactVersion: "renderweave-design-input-expression-generator-implementation/1.0",
    generatorProfile: PROFILE,
    status: "FROZEN_GOLDENS_PRESENT_STATIC_ONLY",
    implementationRevision: "design-input-expression-fixture-generator/1.0",
    runtime: "Node.js >=24",
    entrypoint: artifact(`${ROOT}/generate-design-input-expression-fixtures.mjs`),
    baseline: artifact(BASELINE_PATH),
    fixtureContract: artifact(CONTRACT_PATH),
    observationAdapter: artifact(ADAPTER_PATH),
    scenarioCatalog: artifact(CATALOG_PATH),
    goldenVectors: artifact(GOLDENS_PATH),
    targetManifest: artifact(TARGET_PATH),
    omittedExpectationKeys: OMITTED_EXPECTATION_KEYS,
    productExecutionAllowed: false,
    environmentReadsAllowed: false,
    networkReadsAllowed: false,
    currentTimeReadsAllowed: false,
    hiddenDefaultsAllowed: false,
  };
}

function bootstrap() {
  validateContractAndAdapter();
  const namedPath = `${ROOT}/fixtures/named-design-input-expression-baseline-noop.json`;
  write(namedPath, namedFixture());
  const catalog = buildScenarioCatalog(artifact(namedPath));
  write(CATALOG_PATH, catalog);
  const byLimit = new Map(axes().map((axis) => [axis.limitId, axis]));
  const fixtureArtifacts = [];
  const goldenScenarios = [];
  for (const scenario of catalog.scenarios) {
    const fixture = fixtureForScenario(scenario, byLimit);
    write(scenario.fixtureArtifactPath, fixture);
    const fixtureArtifact = artifact(scenario.fixtureArtifactPath);
    fixtureArtifacts.push(fixtureArtifact);
    goldenScenarios.push({
      scenarioId: scenario.scenarioId,
      mode: scenario.mode,
      parameters: scenario.parameters,
      expectedFixtureArtifact: fixtureArtifact,
    });
  }
  write(GOLDENS_PATH, {
    artifactVersion: "renderweave-design-input-expression-generator-goldens/1.0",
    generatorProfile: PROFILE,
    executionClass: EXECUTION_CLASS,
    baseline: artifact(BASELINE_PATH),
    observationAdapter: artifact(ADAPTER_PATH),
    scenarios: goldenScenarios,
    goldenCount: EXPECTED_TOTAL_SCENARIO_COUNT,
    capacityGoldenCount: EXPECTED_CAPACITY_SCENARIO_COUNT,
    namedGoldenCount: 1,
  });
  write(TARGET_PATH, targetValue(fixtureArtifacts));
  write(IMPLEMENTATION_PATH, implementationValue());
  return {
    status: "BOOTSTRAPPED",
    scenarioCatalog: artifact(CATALOG_PATH),
    goldenVectors: artifact(GOLDENS_PATH),
    targetManifest: artifact(TARGET_PATH),
    implementationManifest: artifact(IMPLEMENTATION_PATH),
    fixtureCount: fixtureArtifacts.length,
  };
}

function verify() {
  let checks = 0;
  const same = (condition, message) => {
    checks += 1;
    check(condition, message);
  };
  validateContractAndAdapter();
  const namedPath = `${ROOT}/fixtures/named-design-input-expression-baseline-noop.json`;
  const expectedCatalog = buildScenarioCatalog(artifact(namedPath));
  const catalog = json(CATALOG_PATH);
  same(serialize(expectedCatalog).equals(bytes(CATALOG_PATH)), "scenario catalog drifted");
  same(catalog.scenarioCount === EXPECTED_TOTAL_SCENARIO_COUNT, "scenario count drifted");
  same(catalog.capacityScenarioCount === EXPECTED_CAPACITY_SCENARIO_COUNT, "capacity scenario count drifted");
  const byLimit = new Map(axes().map((axis) => [axis.limitId, axis]));
  const goldens = json(GOLDENS_PATH);
  same(goldens.goldenCount === catalog.scenarioCount, "golden count drifted");
  const fixtureArtifacts = [];
  for (const scenario of catalog.scenarios) {
    const expected = serialize(fixtureForScenario(scenario, byLimit));
    const actual = bytes(scenario.fixtureArtifactPath);
    same(expected.equals(actual), `fixture drifted: ${scenario.scenarioId}`);
    const golden = goldens.scenarios.find((entry) => entry.scenarioId === scenario.scenarioId);
    same(Boolean(golden), `golden missing: ${scenario.scenarioId}`);
    same(golden.expectedFixtureArtifact.sha256 === `sha256:${sha256(actual)}`, `golden hash drifted: ${scenario.scenarioId}`);
    same(golden.expectedFixtureArtifact.byteLength === actual.length, `golden length drifted: ${scenario.scenarioId}`);
    fixtureArtifacts.push(artifact(scenario.fixtureArtifactPath));
    const text = actual.toString("utf8");
    for (const forbidden of OMITTED_EXPECTATION_KEYS) {
      same(!text.includes(`\"${forbidden}\"`), `fixture leaks ${forbidden}: ${scenario.scenarioId}`);
    }
  }
  const expectedTarget = targetValue(fixtureArtifacts);
  same(serialize(expectedTarget).equals(bytes(TARGET_PATH)), "target manifest drifted");
  same(serialize(implementationValue()).equals(bytes(IMPLEMENTATION_PATH)), "implementation manifest drifted");
  same(expectedTarget.productTarget === false && expectedTarget.productExecutionAllowed === false, "static target boundary drifted");
  return {
    resultVersion: "renderweave-design-input-expression-fixture-generator-result/1.0",
    executorId: "DESIGN_INPUT_EXPRESSION_FIXTURE_GENERATOR::NODE::1.0",
    role: "primary-design-input-expression-fixture-generator-replayer",
    status: "PASS",
    checkCount: checks,
    failureCount: 0,
    runtime: `Node.js ${process.version}`,
    generatorTargetSha256: artifact(TARGET_PATH).sha256,
    fixtureCount: fixtureArtifacts.length,
    productExecutionObserved: false,
    recordIssuanceAllowed: false,
  };
}

const mode = process.argv[2] ?? "verify";
if (mode === "bootstrap") {
  process.stdout.write(`${JSON.stringify(bootstrap(), null, 2)}\n`);
} else if (mode === "verify") {
  process.stdout.write(`${JSON.stringify(verify(), null, 2)}\n`);
} else if (mode === "emit") {
  const scenarioId = process.argv[3];
  check(typeof scenarioId === "string", "emit requires scenarioId");
  const scenario = json(CATALOG_PATH).scenarios.find((entry) => entry.scenarioId === scenarioId);
  check(Boolean(scenario), `unknown scenarioId: ${scenarioId}`);
  const byLimit = new Map(axes().map((axis) => [axis.limitId, axis]));
  process.stdout.write(serialize(fixtureForScenario(scenario, byLimit)));
} else {
  throw new Error("usage: node generate-design-input-expression-fixtures.mjs <bootstrap|verify|emit scenarioId>");
}
