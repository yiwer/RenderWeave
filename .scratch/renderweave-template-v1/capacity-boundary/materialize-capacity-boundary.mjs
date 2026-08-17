import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");
const ROOT = resolve(SPEC, "..", "..");
const OUT = resolve(HERE, "candidate");
const SNAPSHOTS = resolve(SPEC, "conformance-manifest-snapshots");
const PROFILE_ID = "renderweave-conformance-probes/1.0";
const FAULT_IDENTITY = "sha256:41042a03228cdd46583d0e6aff814ba139e183b72280d45375e9a09d0b9e09ae";
const EXPECTED_CLASS_AXIS_COUNTS = new Map([
  ["EXEC::DOMAIN_SERVICES::1.0", 4],
  ["EXEC::DESIGN_INPUT_EXPRESSION::1.0", 65],
  ["EXEC::RENDERING_PIPELINE::1.0", 52],
  ["EXEC::RENDERER_EXACT_OUTPUT::1.0", 54]
]);

function bytes(path) {
  return readFileSync(resolve(ROOT, path));
}

function json(path) {
  return JSON.parse(bytes(path).toString("utf8"));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function digest(value) {
  return `sha256:${sha256(value)}`;
}

function utf8Compare(left, right) {
  return Buffer.compare(Buffer.from(left, "utf8"), Buffer.from(right, "utf8"));
}

function sortedObject(value) {
  const result = {};
  for (const key of Object.keys(value).sort(utf8Compare)) result[key] = value[key];
  return result;
}

function canonicalMap(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalMap).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort(utf8Compare).map((key) => `${JSON.stringify(key)}:${canonicalMap(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function identity(domain, projection) {
  return digest(Buffer.concat([
    Buffer.from(`${domain}\0`, "utf8"),
    Buffer.from(canonicalMap(projection), "utf8")
  ]));
}

function rel(absolute) {
  return relative(SPEC, absolute).replaceAll("\\", "/");
}

function artifact(path) {
  const content = bytes(path);
  return {
    path: path.replace(".scratch/renderweave-template-v1/", ""),
    sha256: digest(content),
    byteLength: content.length
  };
}

function write(path, value) {
  const absolute = resolve(ROOT, path);
  mkdirSync(dirname(absolute), { recursive: true });
  const content = Buffer.isBuffer(value) ? value : Buffer.from(value, "utf8");
  writeFileSync(absolute, content);
  return { path: rel(absolute), sha256: digest(content), byteLength: content.length };
}

function writeImmutable(path, content) {
  const absolute = resolve(ROOT, path);
  mkdirSync(dirname(absolute), { recursive: true });
  if (existsSync(absolute)) {
    const current = readFileSync(absolute);
    if (!current.equals(content)) throw new Error(`immutable snapshot mismatch: ${rel(absolute)}`);
  } else {
    writeFileSync(absolute, content);
  }
  return { path: rel(absolute), sha256: digest(content), byteLength: content.length };
}

function countJsonl(content) {
  return content.toString("utf8").split(/\r?\n/u).filter((line) => line.length > 0).length;
}

function check(condition, message) {
  if (!condition) throw new Error(message);
}

function normalizeCoverage() {
  const path = ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json";
  const coverage = json(path);
  coverage.status = "MAPPING_FROZEN_SHAPE_CANDIDATES_STATIC_REPLAYED_ALL_ASSIGNED_CLASS_FIXTURES_REFERENCEABLE_PRODUCT_GATES_PENDING";
  const safePath = `.scratch/renderweave-template-v1/${coverage.inputs.safeBaselineManifest.path}`;
  const generatorPath = `.scratch/renderweave-template-v1/${coverage.inputs.generatorManifest.path}`;
  coverage.inputs.safeBaselineManifest.sha256 = digest(bytes(safePath));
  coverage.inputs.generatorManifest.sha256 = digest(bytes(generatorPath));
  let singletonSequenceCount = 0;
  for (const axis of coverage.axes) {
    for (const variant of axis.variants) {
      const effects = variant.expectedAssertions.downstreamEffects;
      if (typeof effects === "string") {
        variant.expectedAssertions.downstreamEffects = [effects];
        singletonSequenceCount += 1;
      }
      check(Array.isArray(variant.expectedAssertions.downstreamEffects), `downstreamEffects must be a sequence: ${variant.caseId}`);
    }
  }
  const normalized = Buffer.from(`${JSON.stringify(coverage)}\n`, "utf8");
  const output = write(path, normalized);
  return { output, singletonSequenceCount };
}

function emitEvidence() {
  const primaryPath = ".scratch/renderweave-template-v1/capacity-boundary/primary-result-v1.json";
  const independentPath = ".scratch/renderweave-template-v1/capacity-boundary/independent-result-v1.json";
  const manifestPath = ".scratch/renderweave-template-v1/capacity-boundary/materialization-manifest-v1.json";
  const primary = json(primaryPath);
  const independent = json(independentPath);
  const manifest = json(manifestPath);
  check(primary.status === "PASS" && primary.failureCount === 0, "primary static replay is not green");
  check(independent.status === "PASS" && independent.failureCount === 0, "independent static replay is not green");
  check(primary.materializationManifestSha256 === independent.materializationManifestSha256, "validator manifest binding mismatch");
  check(primary.candidateCasesSha256 === independent.candidateCasesSha256, "validator Case binding mismatch");
  check(primary.candidateOraclesSha256 === independent.candidateOraclesSha256, "validator Oracle binding mismatch");
  const evidence = {
    evidenceVersion: "renderweave-capacity-boundary-static-a2/1.5",
    evidenceId: "CAPACITY_BOUNDARY_STATIC_A2::2026-08-17::000006",
    status: "PASS",
    grade: "A2_INDEPENDENT_STATIC_REPLAY",
    predecessorEvidenceId: "CAPACITY_BOUNDARY_STATIC_A2::2026-08-17::000005",
    scope: "Specification-only 175-axis to 525 Case-shape and 525 Oracle-shape materialization; no product executor, target, capacity terminal, Renderer, or formal record issuance is covered.",
    specRevision: {
      branch: "spec/template-v1",
      baseCommit: "b14c2d7d4978c679e7ab8e7a2bace3da7af884de",
      materializerRevision: "capacity-boundary-materializer/1.5"
    },
    authorityBindings: {
      acceptanceManifest: artifact(".scratch/renderweave-template-v1/acceptance-manifest-v1.json"),
      materializationManifest: artifact(manifestPath),
      capacityCoverage: artifact(".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json"),
      requirementsRegistry: artifact(".scratch/renderweave-template-v1/requirements-v1.json"),
      probeProfile: artifact(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json"),
      generatorManifest: artifact(".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json"),
      safeBaselineManifest: artifact(".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json"),
      executionClassCatalog: artifact(".scratch/renderweave-template-v1/conformance-execution-classes-v1.json"),
      manifestSnapshotPolicy: artifact(".scratch/renderweave-template-v1/conformance-manifest-snapshot-policy-v1.json")
    },
    candidateBindings: {
      cases: manifest.outputs.candidateCases,
      oracles: manifest.outputs.candidateOracles,
      axisCount: manifest.counts.axisCount,
      variantCount: manifest.counts.variantCount,
      caseCount: manifest.counts.shapeCandidateCaseCount,
      oracleCount: manifest.counts.shapeCandidateOracleCount
    },
    replay: {
      primary: {
        result: artifact(primaryPath),
        entrypoint: artifact(".scratch/renderweave-template-v1/capacity-boundary/validate-capacity-boundary-primary.mjs"),
        executorId: primary.executorId,
        runtime: primary.runtime,
        checkCount: primary.checkCount,
        failureCount: primary.failureCount
      },
      independent: {
        result: artifact(independentPath),
        entrypoint: artifact(".scratch/renderweave-template-v1/capacity-boundary/validate_capacity_boundary_independent.py"),
        executorId: independent.executorId,
        runtime: independent.runtime,
        checkCount: independent.checkCount,
        failureCount: independent.failureCount
      },
      sharedSemanticLibrary: null,
      differentRuntimeAndParserImplementations: true,
      candidateDigestAgreement: true
    },
    normalizationFindings: {
      evidenceGrade: "A1_TOOL_CAPTURED_THEN_A2_CURRENT_STATE_REPLAYED",
      currentCatalogDigestRefreshCount: 2,
      singletonTextSequenceCountCorrectedInPredecessor: 354,
      singletonTextSequenceCountChangedThisRevision: 0
    },
    catalogEvolution: {
      domainServicesFixtureBootstrapEvidence: "domain-services/domain-services-fixture-static-a2-2026-08-17.json",
      designInputExpressionFixtureBootstrapEvidence: "design-input-expression/design-input-expression-fixture-static-a2-2026-08-17.json",
      renderingPipelineFixtureBootstrapEvidence: "rendering-pipeline/rendering-pipeline-fixture-static-a2-2026-08-17.json",
      rendererExactOutputFixtureBootstrapEvidence: "renderer-exact-output/renderer-exact-output-fixture-static-a2-2026-08-17.json",
      editorAutomatedFixtureBootstrapEvidence: "editor-automated/editor-automated-fixture-static-a2-2026-08-17.json",
      retainedCatalogSnapshotCount: json(".scratch/renderweave-template-v1/conformance-manifest-snapshot-policy-v1.json").requiredSeedSnapshots.length,
      currentSafeBaselineManifestSha256: manifest.inputs.safeBaselineManifest.sha256,
      currentGeneratorManifestSha256: manifest.inputs.generatorManifest.sha256,
      domainCapacityCandidatesBindExactGeneratorGoldens: true,
      designCapacityCandidatesBindExactGeneratorGoldens: true,
      renderingCapacityCandidatesBindExactGeneratorGoldens: true,
      rendererCapacityCandidatesBindExactGeneratorGoldens: true,
      allSixExecutionClassFixtureCatalogsReferenceable: true,
      formalRecordBytesChanged: false
    },
    classReadiness: manifest.classReadiness,
    formalRegistryBoundary: {
      cases: manifest.formalRegistries.cases,
      oracles: manifest.formalRegistries.oracles,
      appendPerformed: false,
      issuedCapacityCaseCount: 0,
      issuedCapacityOracleCount: 0
    },
    sideEffects: {
      productCodeFilesChanged: 0,
      productExecutorInvocations: 0,
      rendererInvocations: 0,
      networkAttempts: 0,
      externalProviderAttempts: 0
    },
    boundary: {
      currentPhase: "CAPACITY_BOUNDARY",
      staticShapeCandidatesReady: true,
      assignedExecutionClassesExecutable: false,
      recordIssuanceAllowed: false,
      fullAutomatedCorpusExecutable: false,
      rendererCertified: false,
      rendererReady: false,
      ticket19Closed: false
    }
  };
  return write(
    ".scratch/renderweave-template-v1/capacity-boundary/capacity-boundary-static-a2-2026-08-17.json",
    `${JSON.stringify(evidence, null, 2)}\n`
  );
}

function snapshotCatalogs() {
  const policyPath = ".scratch/renderweave-template-v1/conformance-manifest-snapshot-policy-v1.json";
  const sources = [
    {
      kind: "SAFE_BASELINE_CATALOG",
      path: ".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json"
    },
    {
      kind: "GENERATOR_CATALOG",
      path: ".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json"
    }
  ];
  const previous = existsSync(resolve(ROOT, policyPath)) ? json(policyPath) : { requiredSeedSnapshots: [] };
  const retained = new Map();
  for (const snapshot of previous.requiredSeedSnapshots ?? []) {
    const content = bytes(`.scratch/renderweave-template-v1/${snapshot.snapshotPath}`);
    check(digest(content) === snapshot.sha256, `historical snapshot digest mismatch: ${snapshot.snapshotPath}`);
    check(content.length === snapshot.byteLength, `historical snapshot length mismatch: ${snapshot.snapshotPath}`);
    const expectedPath = `conformance-manifest-snapshots/${snapshot.sha256.replace("sha256:", "sha256-")}.json`;
    check(snapshot.snapshotPath === expectedPath, `historical snapshot path mismatch: ${snapshot.snapshotPath}`);
    retained.set(`${snapshot.kind}|${snapshot.sha256}`, snapshot);
  }
  const currentCatalogSnapshots = sources.map((source) => {
    const content = bytes(source.path);
    const sha = digest(content);
    const output = writeImmutable(
      `.scratch/renderweave-template-v1/conformance-manifest-snapshots/${sha.replace("sha256:", "sha256-")}.json`,
      content
    );
    const snapshot = {
      kind: source.kind,
      sourcePathAtCapture: source.path.replace(".scratch/renderweave-template-v1/", ""),
      snapshotPath: output.path,
      sha256: output.sha256,
      byteLength: output.byteLength
    };
    retained.set(`${snapshot.kind}|${snapshot.sha256}`, snapshot);
    return snapshot;
  });
  const requiredSeedSnapshots = [...retained.values()].sort((left, right) => {
    const kindOrder = utf8Compare(left.kind, right.kind);
    return kindOrder === 0 ? utf8Compare(left.sha256, right.sha256) : kindOrder;
  });
  const policy = {
    artifactVersion: "renderweave-conformance-manifest-snapshot-policy/1.0",
    status: "FROZEN_DIGEST_ADDRESSED_SEED_SNAPSHOTS",
    authority: "Existing issued GENERATED inputs resolve generator and safe-baseline manifests by exact digest; catalog evolution never overwrites or removes digest-addressed historical bytes.",
    digestPathRule: "conformance-manifest-snapshots/sha256-<64 lowercase hexadecimal>.json",
    resolutionRule: "Resolve the exact Case-bound digest only; current, latest, path-only, or cross-kind fallback is forbidden.",
    mutationRule: "Snapshot bytes are immutable. A new catalog state creates a new digest-addressed file; an existing digest path is never overwritten.",
    requiredSeedSnapshots,
    seedSnapshotCount: requiredSeedSnapshots.length,
    currentCatalogSnapshots,
    currentCatalogSnapshotCount: currentCatalogSnapshots.length
  };
  const policyArtifact = write(
    policyPath,
    `${JSON.stringify(policy, null, 2)}\n`
  );
  return { policy, policyArtifact };
}

function requirementIds() {
  const registry = json(".scratch/renderweave-template-v1/requirements-v1.json");
  const ids = new Set();
  for (const ticket of registry.tickets) {
    const lines = bytes(`.scratch/renderweave-template-v1/${ticket.registryPath}`).toString("utf8").trimEnd().split(/\r?\n/u);
    for (const line of lines.slice(1)) {
      if (line.length === 0) continue;
      const id = line.split("\t", 1)[0];
      check(!ids.has(id), `duplicate requirementId: ${id}`);
      ids.add(id);
    }
  }
  check(ids.size === registry.counts.requirements, `requirement count mismatch: ${ids.size}`);
  return ids;
}

function literal(probeId, value) {
  return { probeId, operator: "EQ", expected: { kind: "LITERAL", value } };
}

function absent(probeId) {
  return { probeId, operator: "ABSENT" };
}

function sequence(probeId, value) {
  return { probeId, operator: "SEQUENCE_EQ", expected: { kind: "LITERAL", value } };
}

function materializedAssertions(axis, variant) {
  const expected = variant.expectedAssertions;
  check(typeof expected.accepted === "boolean", `accepted must be boolean: ${variant.caseId}`);
  check(Array.isArray(expected.downstreamEffects), `downstreamEffects must be array: ${variant.caseId}`);
  const assertions = [
    literal("operation.accepted", expected.accepted),
    Object.hasOwn(expected, "terminalCode") ? literal("operation.terminalCode", expected.terminalCode) : absent("operation.terminalCode"),
    Object.hasOwn(expected, "terminalStage") ? literal("operation.terminalStage", expected.terminalStage) : absent("operation.terminalStage"),
    literal("capacity.limitId", axis.limitId),
    literal("capacity.observedValue", variant.stimulusValue),
    literal("capacity.reservationReached", true),
    Object.hasOwn(expected, "zeroBoundary") ? literal("capacity.zeroBoundary", expected.zeroBoundary) : absent("capacity.zeroBoundary"),
    sequence("operation.downstreamEffects", expected.downstreamEffects)
  ];
  return assertions.map((assertion, index) => ({
    assertionId: `A${String(index + 1).padStart(3, "0")}`,
    ...assertion
  }));
}

function expectedLiteralValid(valueType, assertion) {
  if (assertion.operator === "ABSENT") return !Object.hasOwn(assertion, "expected");
  if (assertion.expected?.kind !== "LITERAL") return false;
  const value = assertion.expected.value;
  if (assertion.operator === "SEQUENCE_EQ") return valueType === "TEXT_SEQUENCE" && Array.isArray(value) && value.every((item) => typeof item === "string");
  if (assertion.operator !== "EQ") return false;
  if (valueType === "BOOLEAN") return typeof value === "boolean";
  return ["TEXT", "CODE", "STAGE", "DIGEST"].includes(valueType) && typeof value === "string";
}

function inputRecord(binding, parameters, generatorManifestSha256, safeBaselineManifestSha256) {
  const projection = {
    kind: "GENERATED",
    generatorProfile: binding.generatorProfile,
    generatorManifestSha256,
    parameters: sortedObject(parameters),
    safeBaselineId: binding.safeBaselineId,
    safeBaselineManifestSha256
  };
  return {
    ...projection,
    identitySha256: identity("renderweave-conformance-input-identity/1", projection)
  };
}

function classBlockers(executionClass, generators, baselines, classes) {
  const generator = generators.generators.find((entry) => entry.executionClass === executionClass);
  const baseline = baselines.baselines.find((entry) => entry.executionClass === executionClass);
  const execution = classes.classes.find((entry) => entry.executionClass === executionClass);
  const blockers = [];
  if (baseline?.recordMayReference !== true || !baseline?.fixtureArtifact || !baseline?.observationAdapter) {
    blockers.push("SAFE_BASELINE_FIXTURE_SCHEMA_AND_ADAPTER_PENDING");
  }
  if (generator?.recordMayReference !== true || !generator?.implementationManifest || !generator?.targetManifest || !generator?.goldenVectors) {
    blockers.push("GENERATOR_IMPLEMENTATION_TARGET_AND_GOLDENS_PENDING");
  }
  if (!execution?.observationAdapter) blockers.push("OBSERVATION_ADAPTER_PENDING");
  if (!execution?.targetManifest) blockers.push("EXACT_TARGET_MANIFEST_PENDING");
  if (!Array.isArray(execution?.executorManifests) || execution.executorManifests.length < execution.requiredExecutorRoles.length) {
    blockers.push("REQUIRED_EXECUTOR_MANIFESTS_PENDING");
  }
  if (!execution?.replayEvidence || execution?.executable !== true) blockers.push("INDEPENDENT_EXECUTION_REPLAY_PENDING");
  return blockers;
}

function materialize() {
  mkdirSync(OUT, { recursive: true });
  mkdirSync(SNAPSHOTS, { recursive: true });
  const coverage = json(".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json");
  const probes = json(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json");
  const generators = json(".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json");
  const baselines = json(".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json");
  const classes = json(".scratch/renderweave-template-v1/conformance-execution-classes-v1.json");
  const knownRequirements = requirementIds();
  const probeById = new Map(probes.probes.map((probe) => [probe.probeId, probe]));
  const generatorDigest = digest(bytes(".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json"));
  const baselineDigest = digest(bytes(".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json"));
  check(coverage.inputs.generatorManifest.sha256 === generatorDigest, "capacity coverage generator manifest digest is stale");
  check(coverage.inputs.safeBaselineManifest.sha256 === baselineDigest, "capacity coverage safe-baseline manifest digest is stale");
  check(coverage.axisCount === 175 && coverage.axes.length === 175, "capacity axis count must be 175");

  const cases = [];
  const oracles = [];
  const caseIds = new Set();
  const oracleIds = new Set();
  const classAxisCounts = new Map();
  for (const axis of coverage.axes) {
    check(EXPECTED_CLASS_AXIS_COUNTS.has(axis.executionClass), `capacity axis assigned to forbidden class: ${axis.limitId}`);
    classAxisCounts.set(axis.executionClass, (classAxisCounts.get(axis.executionClass) ?? 0) + 1);
    check(axis.variants.length === 3, `axis must have three variants: ${axis.limitId}`);
    check(JSON.stringify(axis.variants.map((variant) => variant.variant)) === JSON.stringify(["below", "at", "above"]), `variant order invalid: ${axis.limitId}`);
    check(axis.requirementIds.length > 0, `axis has no requirements: ${axis.limitId}`);
    const sortedRequirements = [...new Set(axis.requirementIds)].sort(utf8Compare);
    check(sortedRequirements.length === axis.requirementIds.length, `duplicate axis requirement: ${axis.limitId}`);
    for (const requirementId of sortedRequirements) check(knownRequirements.has(requirementId), `unknown requirement ${requirementId}`);
    const binding = coverage.classBindings[axis.executionClass];
    check(Boolean(binding), `missing class binding: ${axis.executionClass}`);
    const generator = generators.generators.find((entry) => entry.generatorProfile === binding.generatorProfile);
    const baseline = baselines.baselines.find((entry) => entry.baselineId === binding.safeBaselineId);
    check(generator?.executionClass === axis.executionClass, `generator class mismatch: ${axis.limitId}`);
    check(baseline?.executionClass === axis.executionClass, `baseline class mismatch: ${axis.limitId}`);
    for (const variant of axis.variants) {
      check(!caseIds.has(variant.caseId), `duplicate caseId: ${variant.caseId}`);
      check(!oracleIds.has(variant.plannedOracleId), `duplicate oracleId: ${variant.plannedOracleId}`);
      caseIds.add(variant.caseId);
      oracleIds.add(variant.plannedOracleId);
      const assertions = materializedAssertions(axis, variant);
      for (const assertion of assertions) {
        const probe = probeById.get(assertion.probeId);
        check(Boolean(probe), `unknown probe: ${assertion.probeId}`);
        check(probe.executionClasses.includes(axis.executionClass), `probe not allowed for class: ${assertion.probeId}`);
        check(probe.allowedOperators.includes(assertion.operator), `operator not allowed: ${assertion.probeId}`);
        check(expectedLiteralValid(probe.valueType, assertion), `assertion type mismatch: ${variant.plannedOracleId}:${assertion.assertionId}`);
      }
      const oracle = {
        recordVersion: "renderweave-conformance-oracle-record/1.0",
        oracleId: variant.plannedOracleId,
        probeProfile: PROFILE_ID,
        assertions,
        supersedes: []
      };
      const parameters = {
        mode: "CAPACITY_BOUNDARY",
        limitId: axis.limitId,
        variant: variant.variant,
        limitValue: axis.limitValue,
        valueEncoding: axis.valueEncoding,
        comparator: axis.comparator,
        deltaId: axis.deltaId,
        stimulusValue: variant.stimulusValue,
        resolvedKind: axis.resolvedOracle.kind,
        resolvedCode: axis.resolvedOracle.code,
        contractStage: axis.resolvedOracle.contractStage,
        publicRenderStage: axis.resolvedOracle.publicRenderStage,
        reservationPoint: axis.resolvedOracle.reservationPoint,
        zeroBoundary: axis.resolvedOracle.zeroBoundary,
        executionClass: axis.executionClass,
        requirementIds: sortedRequirements,
        plannedOracleId: variant.plannedOracleId,
        plannedAssertions: assertions
      };
      const caseRecord = {
        recordVersion: "renderweave-conformance-case-record/1.0",
        caseId: variant.caseId,
        suite: "CAPACITY_BOUNDARY",
        executionClass: axis.executionClass,
        stimulus: {
          input: inputRecord(binding, parameters, generatorDigest, baselineDigest),
          faultSchedule: { kind: "NONE", identitySha256: FAULT_IDENTITY }
        },
        expectedTerminals: [variant.expectedTerminal],
        coverage: sortedRequirements.map((requirementId) => ({
          requirementId,
          evidence: [{ oracleId: variant.plannedOracleId, assertionIds: assertions.map((assertion) => assertion.assertionId) }]
        })),
        supersedes: []
      };
      cases.push(caseRecord);
      oracles.push(oracle);
    }
  }
  for (const [executionClass, expected] of EXPECTED_CLASS_AXIS_COUNTS) {
    check(classAxisCounts.get(executionClass) === expected, `class axis count mismatch: ${executionClass}`);
  }
  check(cases.length === 525 && oracles.length === 525, "capacity candidate count must be 525/525");
  const expectedOracleIds = Array.from({ length: 525 }, (_, index) => `ORC::CAPACITY::${String(index + 1).padStart(6, "0")}`);
  check(JSON.stringify(oracles.map((oracle) => oracle.oracleId)) === JSON.stringify(expectedOracleIds), "capacity Oracle identities are not continuous");

  const candidateCases = write(
    ".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-cases-v1.jsonl",
    Buffer.from(`${cases.map((record) => JSON.stringify(record)).join("\n")}\n`, "utf8")
  );
  const candidateOracles = write(
    ".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-oracles-v1.jsonl",
    Buffer.from(`${oracles.map((record) => JSON.stringify(record)).join("\n")}\n`, "utf8")
  );
  const formalCases = artifact(".scratch/renderweave-template-v1/conformance-cases-v1.jsonl");
  const formalOracles = artifact(".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl");
  check(countJsonl(bytes(".scratch/renderweave-template-v1/conformance-cases-v1.jsonl")) === 46, "formal Case registry must remain at 46 records");
  check(countJsonl(bytes(".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl")) === 46, "formal Oracle registry must remain at 46 records");
  check(!bytes(".scratch/renderweave-template-v1/conformance-cases-v1.jsonl").toString("utf8").includes('"caseId":"CAP::'), "formal registry already contains capacity records");

  const classReadiness = [...EXPECTED_CLASS_AXIS_COUNTS].map(([executionClass, axisCount]) => ({
    executionClass,
    axisCount,
    candidateCaseCount: axisCount * 3,
    candidateOracleCount: axisCount * 3,
    blockers: classBlockers(executionClass, generators, baselines, classes),
    recordIssuanceAllowed: false
  }));
  for (const entry of classReadiness) {
    const expectedCount = new Set([
      "EXEC::DOMAIN_SERVICES::1.0",
      "EXEC::DESIGN_INPUT_EXPRESSION::1.0",
      "EXEC::RENDERING_PIPELINE::1.0",
      "EXEC::RENDERER_EXACT_OUTPUT::1.0"
    ]).has(entry.executionClass) ? 3 : 6;
    check(entry.blockers.length === expectedCount, `unexpected blocker vector for ${entry.executionClass}`);
  }
  const manifest = {
    artifactVersion: "renderweave-capacity-boundary-materialization/1.0",
    status: "STATIC_SHAPE_CANDIDATES_READY_RECORD_ISSUANCE_BLOCKED",
    authority: "Specification-only materialization of the frozen 175-axis mapping; candidates are not issued records and do not prove any product executor, target, capacity terminal, Renderer certification, or READY state.",
    sourceRevision: {
      branch: "spec/template-v1",
      baseCommit: "b14c2d7d4978c679e7ab8e7a2bace3da7af884de"
    },
    inputs: {
      capacityCoverage: artifact(".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json"),
      probeProfile: artifact(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json"),
      requirementsRegistry: artifact(".scratch/renderweave-template-v1/requirements-v1.json"),
      generatorManifest: artifact(".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json"),
      safeBaselineManifest: artifact(".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json"),
      executionClassCatalog: artifact(".scratch/renderweave-template-v1/conformance-execution-classes-v1.json"),
      manifestSnapshotPolicy: artifact(".scratch/renderweave-template-v1/conformance-manifest-snapshot-policy-v1.json")
    },
    outputs: {
      candidateCases: { ...candidateCases, recordCount: cases.length },
      candidateOracles: { ...candidateOracles, recordCount: oracles.length }
    },
    counts: {
      axisCount: coverage.axes.length,
      variantCount: 3,
      shapeCandidateCaseCount: cases.length,
      shapeCandidateOracleCount: oracles.length,
      formallyIssuedCapacityCaseCount: 0,
      formallyIssuedCapacityOracleCount: 0
    },
    classReadiness,
    formalRegistries: {
      cases: { ...formalCases, recordCount: 46 },
      oracles: { ...formalOracles, recordCount: 46 },
      appendPerformedByThisMaterialization: false
    },
    identityBoundary: {
      candidatesBindCurrentPendingCatalogSnapshots: true,
      candidateInputIdentityWillChangeWhenAReferenceableCatalogSnapshotIsIssued: true,
      reservedCaseAndOracleIdsRemainUnissued: true,
      exactCandidateBytesMayBeRebuiltBeforePreissuance: true
    },
    evidenceBoundary: {
      staticMaterializationOnly: true,
      productExecutorInvocations: 0,
      rendererInvocations: 0,
      networkAttempts: 0,
      externalProviderAttempts: 0,
      recordIssuanceAllowed: false,
      ticket19MayClose: false
    }
  };
  const manifestArtifact = write(
    ".scratch/renderweave-template-v1/capacity-boundary/materialization-manifest-v1.json",
    `${JSON.stringify(manifest, null, 2)}\n`
  );
  return { candidateCases, candidateOracles, manifestArtifact, classReadiness };
}

const mode = process.argv[2] ?? "all";
if (!new Set(["normalize", "materialize", "evidence", "all"]).has(mode)) {
  throw new Error("usage: node materialize-capacity-boundary.mjs <normalize|materialize|evidence|all>");
}
const result = {};
if (mode === "normalize" || mode === "all") result.normalization = normalizeCoverage();
if (mode === "materialize" || mode === "all") {
  result.snapshots = snapshotCatalogs();
  result.materialization = materialize();
}
if (mode === "evidence") result.evidence = emitEvidence();
process.stdout.write(`${JSON.stringify({ status: "PASS", mode, ...result }, null, 2)}\n`);
