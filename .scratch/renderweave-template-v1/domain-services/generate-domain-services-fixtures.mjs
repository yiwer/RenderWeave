import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");
const PROFILE = "renderweave-domain-services-generator/1.0";
const EXECUTION_CLASS = "EXEC::DOMAIN_SERVICES::1.0";
const BASELINE_PATH = "domain-services/baseline-v1.json";
const CONTRACT_PATH = "domain-services/fixture-contract-v1.json";
const ADAPTER_PATH = "domain-services/observation-adapter-v1.json";
const CATALOG_PATH = "domain-services/capacity-scenarios-v1.json";
const GOLDENS_PATH = "domain-services/generator-goldens-v1.json";
const TARGET_PATH = "domain-services/generator-target-manifest-v1.json";
const IMPLEMENTATION_PATH = "domain-services/generator-implementation-manifest-v1.json";
const COVERAGE_PATH = "conformance-capacity-coverage-v1.json";
const LIMIT_IDS = [
  "assetsAndFetch.acceptedImageBytesPerContent",
  "assetsAndFetch.acceptedImageEdgePixelsPerContent",
  "assetsAndFetch.acceptedImagePixelsPerContent",
  "assetsAndFetch.acceptedFontBytesPerContent",
];
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
  return {
    path: relativePath,
    sha256: `sha256:${sha256(content)}`,
    byteLength: content.length,
  };
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

function plannedAssertions(axis, variant) {
  const assertions = [
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
  return assertions;
}

function capacityParameters(axis, variant) {
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

function fixtureFileName(limitId, variant) {
  const short = limitId
    .replace("assetsAndFetch.", "")
    .replaceAll(/([a-z0-9])([A-Z])/gu, "$1-$2")
    .toLowerCase();
  return `domain-services/fixtures/cap-${short}-${variant}.json`;
}

function buildScenarioCatalog(namedFixtureArtifact) {
  const coverage = json(COVERAGE_PATH);
  const axes = coverage.axes.filter((axis) => axis.executionClass === EXECUTION_CLASS);
  check(axes.length === 4, `expected 4 DOMAIN_SERVICES axes, found ${axes.length}`);
  check(axes.map((axis) => axis.limitId).join("|") === LIMIT_IDS.join("|"), "DOMAIN_SERVICES axis order drifted");
  const scenarios = [];
  for (const axis of axes) {
    check(axis.comparator === "MAX_INCLUSIVE", `${axis.limitId} comparator drifted`);
    check(axis.valueEncoding === "CANONICAL_INTEGER", `${axis.limitId} encoding drifted`);
    check(axis.resolvedOracle.contractStage === "ASSET_CONTENT_ADMISSION", `${axis.limitId} stage drifted`);
    for (const variant of axis.variants) {
      scenarios.push({
        scenarioId: variant.caseId,
        mode: "CAPACITY_BOUNDARY",
        parameters: capacityParameters(axis, variant),
        fixtureArtifactPath: fixtureFileName(axis.limitId, variant.variant),
      });
    }
  }
  scenarios.push({
    scenarioId: "DOMAIN-BASELINE-NOOP",
    mode: "NAMED_SCENARIO",
    parameters: {
      mode: "NAMED_SCENARIO",
      scenarioId: "DOMAIN-BASELINE-NOOP",
      fixtureArtifactPath: namedFixtureArtifact.path,
      fixtureArtifactSha256: namedFixtureArtifact.sha256,
      expectedObservationProfile: "renderweave-domain-services-observation-adapter/1.0",
    },
    fixtureArtifactPath: namedFixtureArtifact.path,
  });
  return {
    artifactVersion: "renderweave-domain-services-scenario-catalog/1.0",
    status: "FROZEN_12_CAPACITY_PLUS_1_NAMED",
    generatorProfile: PROFILE,
    executionClass: EXECUTION_CLASS,
    sourceRule: "The twelve CAPACITY_BOUNDARY parameter objects are copied from the fully expanded four-axis mapping; no matrix default remains. The named scenario is closed locally.",
    baseline: artifact(BASELINE_PATH),
    observationAdapter: artifact(ADAPTER_PATH),
    scenarios,
    scenarioCount: scenarios.length,
    capacityScenarioCount: 12,
    namedScenarioCount: 1,
  };
}

function validateCapacityParameters(parameters) {
  check(parameters.mode === "CAPACITY_BOUNDARY", "capacity mode missing");
  check(parameters.executionClass === EXECUTION_CLASS, "capacity executionClass mismatch");
  check(LIMIT_IDS.includes(parameters.limitId), "capacity limitId not admitted");
  check(parameters.valueEncoding === "CANONICAL_INTEGER", "capacity valueEncoding mismatch");
  check(parameters.comparator === "MAX_INCLUSIVE", "capacity comparator mismatch");
  check(["below", "at", "above"].includes(parameters.variant), "capacity variant mismatch");
  check(/^(0|[1-9][0-9]*)$/u.test(parameters.stimulusValue), "capacity observed value is not canonical integer text");
  check(parameters.contractStage === "ASSET_CONTENT_ADMISSION", "capacity contract stage mismatch");
  check(parameters.publicRenderStage === "ASSET_ADMISSION", "capacity public stage mismatch");
  check(parameters.resolvedCode === "ASSET_CONTENT_LIMIT_EXCEEDED", "capacity code mismatch");
  check(parameters.resolvedKind === "capacity", "capacity oracle kind mismatch");
  check(parameters.zeroBoundary === "ZERO_DOCUMENT_OUTPUT", "capacity zero boundary mismatch");
  check(Array.isArray(parameters.plannedAssertions) && parameters.plannedAssertions.length === 8, "capacity planned assertions incomplete");
  check(Array.isArray(parameters.requirementIds) && parameters.requirementIds.length >= 1, "capacity requirements incomplete");
}

function buildNamedFixture() {
  const baseline = artifact(BASELINE_PATH);
  const adapter = artifact(ADAPTER_PATH);
  return {
    fixtureVersion: "renderweave-domain-services-fixture/1.0",
    generatorProfile: PROFILE,
    executionClass: EXECUTION_CLASS,
    baseline: {
      baselineId: "baseline.domain-services.minimal-v1",
      path: baseline.path,
      sha256: baseline.sha256,
      byteLength: baseline.byteLength,
    },
    scenario: {
      mode: "NAMED_SCENARIO",
      scenarioId: "DOMAIN-BASELINE-NOOP",
      operationId: "main",
      entrypoint: "BASELINE_CONTRACT_CHECK",
      faultSchedule: { kind: "NONE" },
    },
    observationAdapter: {
      adapterId: "renderweave-domain-services-observation-adapter/1.0",
      path: adapter.path,
      sha256: adapter.sha256,
    },
    targetContract: {
      exactProductionGuardRequired: false,
      duplicateGuardImplementationForbidden: true,
      productApiSurfaceCreated: false,
      databaseRequiredForThisProbe: false,
      mediaPayloadRequiredForThisProbe: false,
      fullUploadPathProvenByThisProbe: false,
    },
  };
}

function buildFixture(parameters) {
  const baseline = artifact(BASELINE_PATH);
  const adapter = artifact(ADAPTER_PATH);
  if (parameters.mode === "CAPACITY_BOUNDARY") {
    validateCapacityParameters(parameters);
    return {
      fixtureVersion: "renderweave-domain-services-fixture/1.0",
      generatorProfile: PROFILE,
      executionClass: EXECUTION_CLASS,
      baseline: {
        baselineId: "baseline.domain-services.minimal-v1",
        path: baseline.path,
        sha256: baseline.sha256,
        byteLength: baseline.byteLength,
      },
      scenario: {
        mode: parameters.mode,
        scenarioId: `CAP::${parameters.limitId}::${parameters.variant}`,
        operationId: "main",
        entrypoint: "ASSET_CONTENT_ADMISSION_CAPACITY_GUARD",
        guardContractId: "renderweave-domain-asset-content-capacity-guard/1.0",
        limitId: parameters.limitId,
        observedValue: parameters.stimulusValue,
        valueEncoding: parameters.valueEncoding,
        comparator: parameters.comparator,
        variant: parameters.variant,
        contractStage: parameters.contractStage,
        publicRenderStage: parameters.publicRenderStage,
        reservationPoint: parameters.reservationPoint,
        zeroBoundary: parameters.zeroBoundary,
        faultSchedule: { kind: "NONE" },
      },
      observationAdapter: {
        adapterId: "renderweave-domain-services-observation-adapter/1.0",
        path: adapter.path,
        sha256: adapter.sha256,
      },
      targetContract: {
        exactProductionGuardRequired: true,
        duplicateGuardImplementationForbidden: true,
        productApiSurfaceCreated: false,
        databaseRequiredForThisProbe: false,
        mediaPayloadRequiredForThisProbe: false,
        fullUploadPathProvenByThisProbe: false,
      },
    };
  }
  check(parameters.mode === "NAMED_SCENARIO", "unknown generator mode");
  check(parameters.scenarioId === "DOMAIN-BASELINE-NOOP", "named scenario is not admitted");
  check(parameters.fixtureArtifactPath === "domain-services/fixtures/named-domain-baseline-noop.json", "named fixture path mismatch");
  check(parameters.expectedObservationProfile === "renderweave-domain-services-observation-adapter/1.0", "named observation profile mismatch");
  const expected = serialize(buildNamedFixture());
  check(parameters.fixtureArtifactSha256 === `sha256:${sha256(expected)}`, "named fixture digest mismatch");
  return buildNamedFixture();
}

function bootstrap() {
  const namedFixturePath = "domain-services/fixtures/named-domain-baseline-noop.json";
  write(namedFixturePath, buildNamedFixture());
  const catalog = buildScenarioCatalog(artifact(namedFixturePath));
  write(CATALOG_PATH, catalog);
  const goldenScenarios = [];
  const fixtures = [];
  for (const scenario of catalog.scenarios) {
    const fixture = buildFixture(scenario.parameters);
    const content = serialize(fixture);
    write(scenario.fixtureArtifactPath, content);
    const fixtureArtifact = artifact(scenario.fixtureArtifactPath);
    fixtures.push(fixtureArtifact);
    goldenScenarios.push({
      scenarioId: scenario.scenarioId,
      mode: scenario.mode,
      parameters: scenario.parameters,
      expectedFixtureArtifact: fixtureArtifact,
    });
  }
  const goldens = {
    artifactVersion: "renderweave-domain-services-generator-goldens/1.0",
    generatorProfile: PROFILE,
    executionClass: EXECUTION_CLASS,
    baseline: artifact(BASELINE_PATH),
    observationAdapter: artifact(ADAPTER_PATH),
    scenarios: goldenScenarios,
    goldenCount: goldenScenarios.length,
    capacityGoldenCount: 12,
    namedGoldenCount: 1,
  };
  write(GOLDENS_PATH, goldens);
  const target = {
    artifactVersion: "renderweave-domain-services-generator-target/1.0",
    targetId: "DOMAIN_SERVICES_GENERATOR_TARGET::FIXTURE::1.0",
    generatorProfile: PROFILE,
    status: "FROZEN_STATIC_GENERATOR_TARGET",
    implementationRevision: "domain-services-fixture-generator/1.0",
    entrypoint: artifact("domain-services/generate-domain-services-fixtures.mjs"),
    baseline: artifact(BASELINE_PATH),
    fixtureContract: artifact(CONTRACT_PATH),
    observationAdapter: artifact(ADAPTER_PATH),
    scenarioCatalog: artifact(CATALOG_PATH),
    goldenVectors: artifact(GOLDENS_PATH),
    fixtureArtifacts: fixtures,
    expectedScenarioCount: 13,
    productTarget: false,
    productExecutionAllowed: false,
    networkReadsAllowed: false,
    environmentReadsAllowed: false,
    currentTimeReadsAllowed: false,
  };
  write(TARGET_PATH, target);
  const implementation = {
    artifactVersion: "renderweave-domain-services-generator-implementation/1.0",
    generatorProfile: PROFILE,
    status: "FROZEN_GOLDENS_PRESENT_STATIC_ONLY",
    implementationRevision: "domain-services-fixture-generator/1.0",
    runtime: "Node.js >=24",
    entrypoint: artifact("domain-services/generate-domain-services-fixtures.mjs"),
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
  write(IMPLEMENTATION_PATH, implementation);
  return {
    status: "BOOTSTRAPPED",
    scenarioCatalog: artifact(CATALOG_PATH),
    goldenVectors: artifact(GOLDENS_PATH),
    targetManifest: artifact(TARGET_PATH),
    implementationManifest: artifact(IMPLEMENTATION_PATH),
    fixtureCount: fixtures.length,
  };
}

function verify() {
  let checks = 0;
  function same(condition, message) {
    checks += 1;
    check(condition, message);
  }
  const expectedCatalog = buildScenarioCatalog(artifact("domain-services/fixtures/named-domain-baseline-noop.json"));
  const catalog = json(CATALOG_PATH);
  same(serialize(expectedCatalog).equals(bytes(CATALOG_PATH)), "scenario catalog drifted");
  same(catalog.scenarioCount === 13 && catalog.capacityScenarioCount === 12, "scenario counts drifted");
  const goldens = json(GOLDENS_PATH);
  same(goldens.goldenCount === catalog.scenarioCount, "golden count drifted");
  for (const scenario of catalog.scenarios) {
    const expected = serialize(buildFixture(scenario.parameters));
    const actual = bytes(scenario.fixtureArtifactPath);
    same(expected.equals(actual), `fixture drifted: ${scenario.scenarioId}`);
    const golden = goldens.scenarios.find((entry) => entry.scenarioId === scenario.scenarioId);
    same(Boolean(golden), `golden missing: ${scenario.scenarioId}`);
    same(golden.expectedFixtureArtifact.sha256 === `sha256:${sha256(actual)}`, `golden hash drifted: ${scenario.scenarioId}`);
    const text = actual.toString("utf8");
    for (const forbidden of OMITTED_EXPECTATION_KEYS) {
      same(!text.includes(`\"${forbidden}\"`), `fixture leaks ${forbidden}: ${scenario.scenarioId}`);
    }
  }
  const target = json(TARGET_PATH);
  const implementation = json(IMPLEMENTATION_PATH);
  same(target.entrypoint.sha256 === artifact(target.entrypoint.path).sha256, "target entrypoint hash drifted");
  same(target.fixtureArtifacts.length === 13, "target fixture inventory drifted");
  same(implementation.targetManifest.sha256 === artifact(TARGET_PATH).sha256, "implementation target hash drifted");
  same(implementation.goldenVectors.sha256 === artifact(GOLDENS_PATH).sha256, "implementation golden hash drifted");
  same(target.productTarget === false && target.productExecutionAllowed === false, "static target boundary drifted");
  return {
    resultVersion: "renderweave-domain-services-fixture-generator-result/1.0",
    executorId: "DOMAIN_SERVICES_FIXTURE_GENERATOR::NODE::1.0",
    role: "primary-domain-services-fixture-generator-replayer",
    status: "PASS",
    checkCount: checks,
    failureCount: 0,
    runtime: `Node.js ${process.version}`,
    generatorTargetSha256: artifact(TARGET_PATH).sha256,
    fixtureCount: 13,
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
  process.stdout.write(serialize(buildFixture(scenario.parameters)));
} else {
  throw new Error("usage: node generate-domain-services-fixtures.mjs <bootstrap|verify|emit scenarioId>");
}
