import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");
const EXECUTION_CLASS = "EXEC::RENDERER_EXACT_OUTPUT::1.0";

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
  if (value.status !== "PASS" || value.failureCount !== 0) throw new Error(`${command} replay did not pass`);
  return value;
}

function countBy(values, selector) {
  const counts = {};
  for (const value of values) {
    const key = selector(value);
    counts[key] = (counts[key] ?? 0) + 1;
  }
  return Object.fromEntries(Object.entries(counts).sort(([left], [right]) => Buffer.compare(Buffer.from(left), Buffer.from(right))));
}

const targetPath = "renderer-exact-output/generator-target-manifest-v1.json";
const primaryEntrypoint = "renderer-exact-output/generate-renderer-exact-output-fixtures.mjs";
const independentEntrypoint = "renderer-exact-output/validate_renderer_exact_output_fixtures_independent.py";
const target = json(targetPath);
const primary = run("node", [primaryEntrypoint, "verify"]);
const independent = run("python", [independentEntrypoint]);
if (primary.generatorTargetSha256 !== independent.generatorTargetSha256) throw new Error("generator target digest disagreement");

const primaryResult = {
  ...primary,
  implementationRevision: target.implementationRevision,
  targetManifest: artifact(targetPath),
  entrypoint: artifact(primaryEntrypoint),
};
const independentResult = {
  ...independent,
  implementationRevision: target.implementationRevision,
  targetManifest: artifact(targetPath),
  entrypoint: artifact(independentEntrypoint),
};
writeJson("renderer-exact-output/primary-result-v1.json", primaryResult);
writeJson("renderer-exact-output/independent-result-v1.json", independentResult);

const capacity = json("capacity-boundary/materialization-manifest-v1.json");
const snapshotPolicy = json("conformance-manifest-snapshot-policy-v1.json");
const formalCases = artifact("conformance-cases-v1.jsonl");
const formalOracles = artifact("conformance-oracles-v1.jsonl");
const readiness = capacity.classReadiness.find((entry) => entry.executionClass === EXECUTION_CLASS);
if (!readiness) throw new Error("RENDERER_EXACT_OUTPUT readiness missing");
if (JSON.stringify(readiness.blockers) !== JSON.stringify([
  "EXACT_TARGET_MANIFEST_PENDING",
  "REQUIRED_EXECUTOR_MANIFESTS_PENDING",
  "INDEPENDENT_EXECUTION_REPLAY_PENDING",
])) throw new Error("RENDERER_EXACT_OUTPUT blocker frontier drifted");

const coverage = json("conformance-capacity-coverage-v1.json");
const axes = coverage.axes.filter((axis) => axis.executionClass === EXECUTION_CLASS);
if (axes.length !== 54) throw new Error(`RENDERER_EXACT_OUTPUT axis count drifted: ${axes.length}`);

const evidence = {
  evidenceVersion: "renderweave-renderer-exact-output-fixture-static-a2/1.0",
  evidenceId: "RENDERER_EXACT_OUTPUT_FIXTURE_STATIC_A2::2026-08-17::000001",
  status: "PASS",
  grade: "A2_INDEPENDENT_STATIC_REPLAY",
  scope: "RENDERER_EXACT_OUTPUT fixture-only effective-command context, closed observation-adapter, deterministic generator, 163 fixture artifacts, and 162 isolated capacity-predicate inputs only; no product Command or RenderDocument was constructed, and no resource fetch, decode, layout, shaping, raster, encode, trace, Engine, physical Linux certification, or terminal output was executed.",
  specRevision: {
    branch: "spec/template-v1",
    baseCommit: "b14c2d7d4978c679e7ab8e7a2bace3da7af884de",
    implementationRevision: target.implementationRevision,
  },
  authorityBindings: {
    acceptanceManifest: artifact("acceptance-manifest-v1.json"),
    safeBaselineCatalog: artifact("conformance-safe-baselines-v1.json"),
    generatorCatalog: artifact("conformance-generator-manifests-v1.json"),
    executionClassCatalog: artifact("conformance-execution-classes-v1.json"),
    capacityCoverage: artifact("conformance-capacity-coverage-v1.json"),
    capacityMaterialization: artifact("capacity-boundary/materialization-manifest-v1.json"),
    manifestSnapshotPolicy: {
      ...artifact("conformance-manifest-snapshot-policy-v1.json"),
      retainedSnapshotCount: snapshotPolicy.requiredSeedSnapshots.length,
      currentCatalogSnapshotCount: snapshotPolicy.currentCatalogSnapshots.length,
    },
    baseline: artifact("renderer-exact-output/baseline-v1.json"),
    fixtureContract: artifact("renderer-exact-output/fixture-contract-v1.json"),
    observationAdapter: artifact("renderer-exact-output/observation-adapter-v1.json"),
    scenarioCatalog: artifact("renderer-exact-output/capacity-scenarios-v1.json"),
    generatorGoldens: artifact("renderer-exact-output/generator-goldens-v1.json"),
    generatorTarget: artifact(targetPath),
    generatorImplementation: artifact("renderer-exact-output/generator-implementation-manifest-v1.json"),
  },
  fixtureInventory: {
    fixtureCount: target.fixtureArtifacts.length,
    capacityFixtureCount: 162,
    namedFixtureCount: 1,
    artifacts: target.fixtureArtifacts,
    expectationDataPresentInTargetFixtures: false,
    giantBoundaryPayloadsMaterialized: false,
  },
  replay: {
    primary: {
      result: artifact("renderer-exact-output/primary-result-v1.json"),
      executorId: primary.executorId,
      runtime: primary.runtime,
      checkCount: primary.checkCount,
      failureCount: primary.failureCount,
    },
    independent: {
      result: artifact("renderer-exact-output/independent-result-v1.json"),
      executorId: independent.executorId,
      runtime: independent.runtime,
      checkCount: independent.checkCount,
      failureCount: independent.failureCount,
    },
    sharedSemanticLibrary: null,
    differentRuntimeAndParserImplementations: true,
    fixtureByteAgreement: true,
  },
  findings: {
    rendererExactOutputCapacityAxisCount: axes.length,
    generatedCapacityVariantCount: axes.length * 3,
    namedBaselineScenarioCount: 1,
    comparatorDistribution: countBy(axes, (axis) => axis.comparator),
    valueEncodingDistribution: countBy(axes, (axis) => axis.valueEncoding),
    contractStageDistribution: countBy(axes, (axis) => axis.resolvedOracle.contractStage),
    globalNamedScenarioParameterShapeAligned: true,
    capacityParameterMemberOrderAlignedToCanonicalMapOrder: true,
    formulaAxisCount: axes.filter((axis) => axis.valueEncoding === "FORMULA").length,
    guardSeamReason: "The isolated canonical-integer or closed Stack-formula predicate avoids fabricating giant resource, surface, layout, shaping, raster, trace, deadline, deployment, or retention payloads while requiring each future exact Renderer target to prove authoritative stage derivation and enforce the same axis-specific predicate at the frozen reservation or certification point.",
  },
  formalRegistryBoundary: {
    cases: { ...formalCases, recordCount: 46 },
    oracles: { ...formalOracles, recordCount: 46 },
    issuedRendererCapacityCaseCount: 0,
    issuedRendererCapacityOracleCount: 0,
    appendPerformed: false,
  },
  classReadiness: readiness,
  sideEffects: {
    productCodeFilesChanged: 0,
    productTargetInvocations: 0,
    productCommandConstructions: 0,
    renderDocumentParses: 0,
    resourceFetchOrDecodeInvocations: 0,
    layoutOrShapingInvocations: 0,
    rasterOrEncodingInvocations: 0,
    physicalLinuxCertificationRuns: 0,
    rendererInvocations: 0,
    networkAttempts: 0,
    externalProviderAttempts: 0,
  },
  boundary: {
    fixtureAndGeneratorReferenceable: true,
    exactProductTargetPresent: false,
    requiredProductExecutorManifestsPresent: false,
    independentProductExecutionReplayPresent: false,
    executionClassExecutable: false,
    capacityTerminalProven: false,
    recordIssuanceAllowed: false,
    rendererReady: false,
    ticket19Closed: false,
  },
};
writeJson("renderer-exact-output/renderer-exact-output-fixture-static-a2-2026-08-17.json", evidence);
process.stdout.write(`${JSON.stringify({
  status: evidence.status,
  evidence: artifact("renderer-exact-output/renderer-exact-output-fixture-static-a2-2026-08-17.json"),
  primaryChecks: primary.checkCount,
  independentChecks: independent.checkCount,
  productExecutionObserved: false,
  recordIssuanceAllowed: false,
})}\n`);
