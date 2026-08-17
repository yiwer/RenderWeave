import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");

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

const targetPath = "domain-services/generator-target-manifest-v1.json";
const primaryEntrypoint = "domain-services/generate-domain-services-fixtures.mjs";
const independentEntrypoint = "domain-services/validate_domain_services_fixtures_independent.py";
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
writeJson("domain-services/primary-result-v1.json", primaryResult);
writeJson("domain-services/independent-result-v1.json", independentResult);

const capacity = json("capacity-boundary/materialization-manifest-v1.json");
const snapshotPolicy = json("conformance-manifest-snapshot-policy-v1.json");
const formalCases = artifact("conformance-cases-v1.jsonl");
const formalOracles = artifact("conformance-oracles-v1.jsonl");
const domainReadiness = capacity.classReadiness.find((entry) => entry.executionClass === "EXEC::DOMAIN_SERVICES::1.0");
if (!domainReadiness) throw new Error("DOMAIN_SERVICES readiness missing");
if (JSON.stringify(domainReadiness.blockers) !== JSON.stringify([
  "EXACT_TARGET_MANIFEST_PENDING",
  "REQUIRED_EXECUTOR_MANIFESTS_PENDING",
  "INDEPENDENT_EXECUTION_REPLAY_PENDING",
])) throw new Error("DOMAIN_SERVICES blocker frontier drifted");

const evidence = {
  evidenceVersion: "renderweave-domain-services-fixture-static-a2/1.0",
  evidenceId: "DOMAIN_SERVICES_FIXTURE_STATIC_A2::2026-08-17::000001",
  status: "PASS",
  grade: "A2_INDEPENDENT_STATIC_REPLAY",
  scope: "DOMAIN_SERVICES safe-baseline, closed observation-adapter, deterministic generator, thirteen fixture artifacts, and twelve isolated capacity-guard inputs only; no product target or terminal was executed.",
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
    baseline: artifact("domain-services/baseline-v1.json"),
    fixtureContract: artifact("domain-services/fixture-contract-v1.json"),
    observationAdapter: artifact("domain-services/observation-adapter-v1.json"),
    scenarioCatalog: artifact("domain-services/capacity-scenarios-v1.json"),
    generatorGoldens: artifact("domain-services/generator-goldens-v1.json"),
    generatorTarget: artifact(targetPath),
    generatorImplementation: artifact("domain-services/generator-implementation-manifest-v1.json"),
  },
  fixtureInventory: {
    fixtureCount: target.fixtureArtifacts.length,
    capacityFixtureCount: 12,
    namedFixtureCount: 1,
    artifacts: target.fixtureArtifacts,
    expectationDataPresentInTargetFixtures: false,
    mediaPayloadsPresent: false,
  },
  replay: {
    primary: {
      result: artifact("domain-services/primary-result-v1.json"),
      executorId: primary.executorId,
      runtime: primary.runtime,
      checkCount: primary.checkCount,
      failureCount: primary.failureCount,
    },
    independent: {
      result: artifact("domain-services/independent-result-v1.json"),
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
    domainCapacityAxisCount: 4,
    generatedCapacityVariantCount: 12,
    namedBaselineScenarioCount: 1,
    globalNamedScenarioParameterShapeAligned: true,
    capacityParameterMemberOrderAlignedToCanonicalMapOrder: true,
    guardSeamReason: "The isolated scalar guard avoids unrepresentable media-file combinations while requiring a future exact product target to prove authoritative byte or decoded-descriptor derivation and reuse the same guard.",
  },
  formalRegistryBoundary: {
    cases: { ...formalCases, recordCount: 46 },
    oracles: { ...formalOracles, recordCount: 46 },
    issuedDomainCapacityCaseCount: 0,
    issuedDomainCapacityOracleCount: 0,
    appendPerformed: false,
  },
  classReadiness: domainReadiness,
  sideEffects: {
    productCodeFilesChanged: 0,
    productTargetInvocations: 0,
    databaseInvocations: 0,
    mediaDecoderInvocations: 0,
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
writeJson("domain-services/domain-services-fixture-static-a2-2026-08-17.json", evidence);
process.stdout.write(`${JSON.stringify({
  status: evidence.status,
  evidence: artifact("domain-services/domain-services-fixture-static-a2-2026-08-17.json"),
  primaryChecks: primary.checkCount,
  independentChecks: independent.checkCount,
  productExecutionObserved: false,
  recordIssuanceAllowed: false,
})}\n`);
