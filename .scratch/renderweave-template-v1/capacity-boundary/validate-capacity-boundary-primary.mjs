import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");
const ROOT = resolve(SPEC, "..", "..");
const MANIFEST = ".scratch/renderweave-template-v1/capacity-boundary/materialization-manifest-v1.json";
const EXPECTED_CLASS_AXIS_COUNTS = new Map([
  ["EXEC::DOMAIN_SERVICES::1.0", 4],
  ["EXEC::DESIGN_INPUT_EXPRESSION::1.0", 65],
  ["EXEC::RENDERING_PIPELINE::1.0", 52],
  ["EXEC::RENDERER_EXACT_OUTPUT::1.0", 54]
]);
const FAULT_IDENTITY = "sha256:41042a03228cdd46583d0e6aff814ba139e183b72280d45375e9a09d0b9e09ae";

let checkCount = 0;
const failures = [];

function check(condition, code, detail) {
  checkCount += 1;
  if (!condition) failures.push({ code, detail });
}

function raw(path) {
  return readFileSync(resolve(ROOT, path));
}

function parse(path) {
  return JSON.parse(raw(path).toString("utf8"));
}

function digest(value) {
  return `sha256:${createHash("sha256").update(value).digest("hex")}`;
}

function utf8Compare(left, right) {
  return Buffer.compare(Buffer.from(left, "utf8"), Buffer.from(right, "utf8"));
}

function canonicalMap(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalMap).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort(utf8Compare).map((key) => `${JSON.stringify(key)}:${canonicalMap(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function identity(domain, projection) {
  return digest(Buffer.from(`${domain}\0${canonicalMap(projection)}`, "utf8"));
}

function exactKeys(value, expected) {
  return value && typeof value === "object" && !Array.isArray(value)
    && JSON.stringify(Object.keys(value)) === JSON.stringify(expected);
}

function jsonl(path) {
  const content = raw(path).toString("utf8");
  check(content.endsWith("\n") && !content.endsWith("\n\n"), "JSONL_TERMINATOR", path);
  const lines = content.split("\n").slice(0, -1);
  const records = [];
  for (const [index, line] of lines.entries()) {
    check(line.length > 0, "JSONL_BLANK_LINE", `${path}:${index + 1}`);
    try {
      const record = JSON.parse(line);
      check(JSON.stringify(record) === line, "JSONL_NONCANONICAL_LINE", `${path}:${index + 1}`);
      records.push(record);
    } catch (error) {
      failures.push({ code: "JSONL_PARSE", detail: `${path}:${index + 1}:${error.message}` });
    }
  }
  return records;
}

function loadRequirements() {
  const registry = parse(".scratch/renderweave-template-v1/requirements-v1.json");
  const ids = new Set();
  for (const ticket of registry.tickets) {
    const content = raw(`.scratch/renderweave-template-v1/${ticket.registryPath}`);
    check(createHash("sha256").update(content).digest("hex") === ticket.sha256, "REQUIREMENT_TSV_DIGEST", ticket.registryPath);
    const lines = content.toString("utf8").trimEnd().split(/\r?\n/u);
    check(lines[0] === "requirement_id\tsource_line\tclause_ordinal_on_line\tfamily\tnormative_summary", "REQUIREMENT_TSV_HEADER", ticket.registryPath);
    for (const line of lines.slice(1)) {
      const columns = line.split("\t");
      check(columns.length === 5, "REQUIREMENT_TSV_COLUMNS", `${ticket.registryPath}:${line}`);
      check(!ids.has(columns[0]), "REQUIREMENT_ID_DUPLICATE", columns[0]);
      ids.add(columns[0]);
    }
  }
  check(ids.size === registry.counts.requirements, "REQUIREMENT_COUNT", String(ids.size));
  return ids;
}

function expectedAssertions(axis, variant) {
  const source = variant.expectedAssertions;
  const literal = (probeId, value) => ({ probeId, operator: "EQ", expected: { kind: "LITERAL", value } });
  const absent = (probeId) => ({ probeId, operator: "ABSENT" });
  const sequence = (probeId, value) => ({ probeId, operator: "SEQUENCE_EQ", expected: { kind: "LITERAL", value } });
  return [
    literal("operation.accepted", source.accepted),
    Object.hasOwn(source, "terminalCode") ? literal("operation.terminalCode", source.terminalCode) : absent("operation.terminalCode"),
    Object.hasOwn(source, "terminalStage") ? literal("operation.terminalStage", source.terminalStage) : absent("operation.terminalStage"),
    literal("capacity.limitId", axis.limitId),
    literal("capacity.observedValue", variant.stimulusValue),
    literal("capacity.reservationReached", true),
    Object.hasOwn(source, "zeroBoundary") ? literal("capacity.zeroBoundary", source.zeroBoundary) : absent("capacity.zeroBoundary"),
    sequence("operation.downstreamEffects", source.downstreamEffects)
  ].map((assertion, index) => ({ assertionId: `A${String(index + 1).padStart(3, "0")}`, ...assertion }));
}

function expectedTypeValid(probe, assertion) {
  if (!probe.allowedOperators.includes(assertion.operator)) return false;
  if (assertion.operator === "ABSENT") return !Object.hasOwn(assertion, "expected");
  if (assertion.expected?.kind !== "LITERAL") return false;
  const value = assertion.expected.value;
  if (assertion.operator === "SEQUENCE_EQ") return probe.valueType === "TEXT_SEQUENCE" && Array.isArray(value) && value.every((item) => typeof item === "string");
  if (assertion.operator !== "EQ") return false;
  if (probe.valueType === "BOOLEAN") return typeof value === "boolean";
  return ["TEXT", "CODE", "STAGE", "DIGEST"].includes(probe.valueType) && typeof value === "string";
}

function main() {
  const outputIndex = process.argv.indexOf("--output");
  const outputPath = outputIndex >= 0 ? process.argv[outputIndex + 1] : null;
  const manifest = parse(MANIFEST);
  check(manifest.status === "STATIC_SHAPE_CANDIDATES_READY_RECORD_ISSUANCE_BLOCKED", "MANIFEST_STATUS", manifest.status);
  check(manifest.evidenceBoundary.staticMaterializationOnly === true && manifest.evidenceBoundary.recordIssuanceAllowed === false, "MANIFEST_BOUNDARY", "static/blocked");
  for (const binding of Object.values(manifest.inputs)) {
    const path = `.scratch/renderweave-template-v1/${binding.path}`;
    const content = raw(path);
    check(digest(content) === binding.sha256, "MANIFEST_INPUT_DIGEST", binding.path);
    check(content.length === binding.byteLength, "MANIFEST_INPUT_LENGTH", binding.path);
  }
  for (const binding of Object.values(manifest.outputs)) {
    const path = `.scratch/renderweave-template-v1/${binding.path}`;
    const content = raw(path);
    check(digest(content) === binding.sha256, "MANIFEST_OUTPUT_DIGEST", binding.path);
    check(content.length === binding.byteLength, "MANIFEST_OUTPUT_LENGTH", binding.path);
  }
  const coverage = parse(".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json");
  const probes = parse(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json");
  const generators = parse(".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json");
  const baselines = parse(".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json");
  const classes = parse(".scratch/renderweave-template-v1/conformance-execution-classes-v1.json");
  const domainGoldens = parse(".scratch/renderweave-template-v1/domain-services/generator-goldens-v1.json");
  const domainGoldenByScenario = new Map(domainGoldens.scenarios.map((entry) => [entry.scenarioId, entry]));
  const designGoldens = parse(".scratch/renderweave-template-v1/design-input-expression/generator-goldens-v1.json");
  const designGoldenByScenario = new Map(designGoldens.scenarios.map((entry) => [entry.scenarioId, entry]));
  const renderingGoldens = parse(".scratch/renderweave-template-v1/rendering-pipeline/generator-goldens-v1.json");
  const renderingGoldenByScenario = new Map(renderingGoldens.scenarios.map((entry) => [entry.scenarioId, entry]));
  const rendererGoldens = parse(".scratch/renderweave-template-v1/renderer-exact-output/generator-goldens-v1.json");
  const rendererGoldenByScenario = new Map(rendererGoldens.scenarios.map((entry) => [entry.scenarioId, entry]));
  const staticGoldensByClass = new Map([
    ["EXEC::DOMAIN_SERVICES::1.0", domainGoldenByScenario],
    ["EXEC::DESIGN_INPUT_EXPRESSION::1.0", designGoldenByScenario],
    ["EXEC::RENDERING_PIPELINE::1.0", renderingGoldenByScenario],
    ["EXEC::RENDERER_EXACT_OUTPUT::1.0", rendererGoldenByScenario]
  ]);
  const policy = parse(".scratch/renderweave-template-v1/conformance-manifest-snapshot-policy-v1.json");
  const requirements = loadRequirements();
  const generatorDigest = digest(raw(".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json"));
  const baselineDigest = digest(raw(".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json"));
  check(coverage.inputs.generatorManifest.sha256 === generatorDigest, "COVERAGE_GENERATOR_DIGEST", coverage.inputs.generatorManifest.sha256);
  check(coverage.inputs.safeBaselineManifest.sha256 === baselineDigest, "COVERAGE_BASELINE_DIGEST", coverage.inputs.safeBaselineManifest.sha256);
  check(policy.seedSnapshotCount === policy.requiredSeedSnapshots.length && policy.seedSnapshotCount >= 2, "SNAPSHOT_COUNT", String(policy.requiredSeedSnapshots.length));
  const snapshotKeys = new Set();
  for (const snapshot of policy.requiredSeedSnapshots) {
    const snapshotBytes = raw(`.scratch/renderweave-template-v1/${snapshot.snapshotPath}`);
    check(digest(snapshotBytes) === snapshot.sha256, "SNAPSHOT_DIGEST", snapshot.snapshotPath);
    check(snapshot.snapshotPath === `conformance-manifest-snapshots/${snapshot.sha256.replace("sha256:", "sha256-")}.json`, "SNAPSHOT_PATH", snapshot.snapshotPath);
    check(snapshotBytes.length === snapshot.byteLength, "SNAPSHOT_LENGTH", snapshot.snapshotPath);
    const key = `${snapshot.kind}|${snapshot.sha256}`;
    check(!snapshotKeys.has(key), "SNAPSHOT_DUPLICATE", key);
    snapshotKeys.add(key);
  }
  check(policy.currentCatalogSnapshotCount === 2 && policy.currentCatalogSnapshots.length === 2, "CURRENT_SNAPSHOT_COUNT", String(policy.currentCatalogSnapshots.length));
  for (const current of policy.currentCatalogSnapshots) {
    check(snapshotKeys.has(`${current.kind}|${current.sha256}`), "CURRENT_SNAPSHOT_RETAINED", current.kind);
    check(digest(raw(`.scratch/renderweave-template-v1/${current.sourcePathAtCapture}`)) === current.sha256, "CURRENT_SNAPSHOT_SOURCE", current.sourcePathAtCapture);
  }

  const cases = jsonl(".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-cases-v1.jsonl");
  const oracles = jsonl(".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-oracles-v1.jsonl");
  check(cases.length === 525 && oracles.length === 525, "CANDIDATE_COUNTS", `${cases.length}/${oracles.length}`);
  const caseById = new Map(cases.map((record) => [record.caseId, record]));
  const oracleById = new Map(oracles.map((record) => [record.oracleId, record]));
  check(caseById.size === cases.length, "CASE_ID_UNIQUE", String(caseById.size));
  check(oracleById.size === oracles.length, "ORACLE_ID_UNIQUE", String(oracleById.size));
  const probeById = new Map(probes.probes.map((probe) => [probe.probeId, probe]));
  const classCounts = new Map();
  const caseSignatures = new Set();
  const oracleSignatures = new Set();
  let expectedOrdinal = 1;

  for (const axis of coverage.axes) {
    classCounts.set(axis.executionClass, (classCounts.get(axis.executionClass) ?? 0) + 1);
    check(axis.variants.length === 3, "AXIS_VARIANT_COUNT", axis.limitId);
    check(Array.isArray(axis.requirementIds) && axis.requirementIds.length > 0, "AXIS_REQUIREMENTS", axis.limitId);
    for (const requirementId of axis.requirementIds) check(requirements.has(requirementId), "AXIS_REQUIREMENT_EXISTS", requirementId);
    for (const variant of axis.variants) {
      const caseRecord = caseById.get(variant.caseId);
      const oracle = oracleById.get(variant.plannedOracleId);
      check(Boolean(caseRecord), "CASE_RESERVED_ID_PRESENT", variant.caseId);
      check(Boolean(oracle), "ORACLE_RESERVED_ID_PRESENT", variant.plannedOracleId);
      if (!caseRecord || !oracle) continue;
      check(variant.plannedOracleId === `ORC::CAPACITY::${String(expectedOrdinal).padStart(6, "0")}`, "ORACLE_ID_CONTINUOUS", variant.plannedOracleId);
      expectedOrdinal += 1;
      check(exactKeys(caseRecord, ["recordVersion", "caseId", "suite", "executionClass", "stimulus", "expectedTerminals", "coverage", "supersedes"]), "CASE_KEYS", caseRecord.caseId);
      check(caseRecord.recordVersion === "renderweave-conformance-case-record/1.0" && caseRecord.suite === "CAPACITY_BOUNDARY", "CASE_VERSION_SUITE", caseRecord.caseId);
      check(caseRecord.executionClass === axis.executionClass, "CASE_EXECUTION_CLASS", caseRecord.caseId);
      check(JSON.stringify(caseRecord.expectedTerminals) === JSON.stringify([variant.expectedTerminal]), "CASE_TERMINAL", caseRecord.caseId);
      check(caseRecord.supersedes.length === 0, "CASE_SUPERSEDES", caseRecord.caseId);
      check(exactKeys(caseRecord.stimulus, ["input", "faultSchedule"]), "CASE_STIMULUS_KEYS", caseRecord.caseId);
      check(exactKeys(caseRecord.stimulus.input, ["kind", "generatorProfile", "generatorManifestSha256", "parameters", "safeBaselineId", "safeBaselineManifestSha256", "identitySha256"]), "CASE_INPUT_KEYS", caseRecord.caseId);
      const input = caseRecord.stimulus.input;
      const inputProjection = { kind: input.kind, generatorProfile: input.generatorProfile, generatorManifestSha256: input.generatorManifestSha256, parameters: input.parameters, safeBaselineId: input.safeBaselineId, safeBaselineManifestSha256: input.safeBaselineManifestSha256 };
      check(input.identitySha256 === identity("renderweave-conformance-input-identity/1", inputProjection), "CASE_INPUT_IDENTITY", caseRecord.caseId);
      check(input.generatorManifestSha256 === generatorDigest && input.safeBaselineManifestSha256 === baselineDigest, "CASE_MANIFEST_BINDINGS", caseRecord.caseId);
      check(caseRecord.stimulus.faultSchedule.kind === "NONE" && caseRecord.stimulus.faultSchedule.identitySha256 === FAULT_IDENTITY, "CASE_FAULT", caseRecord.caseId);
      check(input.parameters.limitId === axis.limitId && input.parameters.variant === variant.variant && input.parameters.stimulusValue === variant.stimulusValue, "CASE_PARAMETERS_AXIS", caseRecord.caseId);
      check(input.parameters.plannedOracleId === variant.plannedOracleId, "CASE_PARAMETERS_ORACLE", caseRecord.caseId);
      const sortedRequirements = [...axis.requirementIds].sort(utf8Compare);
      check(JSON.stringify(input.parameters.requirementIds) === JSON.stringify(sortedRequirements), "CASE_PARAMETERS_REQUIREMENTS", caseRecord.caseId);
      check(JSON.stringify(caseRecord.coverage.map((edge) => edge.requirementId)) === JSON.stringify(sortedRequirements), "CASE_COVERAGE_REQUIREMENTS", caseRecord.caseId);
      check(caseRecord.coverage.every((edge) => edge.evidence.length === 1 && edge.evidence[0].oracleId === oracle.oracleId && JSON.stringify(edge.evidence[0].assertionIds) === JSON.stringify(["A001", "A002", "A003", "A004", "A005", "A006", "A007", "A008"])), "CASE_COVERAGE_ASSERTIONS", caseRecord.caseId);

      const assertions = expectedAssertions(axis, variant);
      check(exactKeys(oracle, ["recordVersion", "oracleId", "probeProfile", "assertions", "supersedes"]), "ORACLE_KEYS", oracle.oracleId);
      check(oracle.recordVersion === "renderweave-conformance-oracle-record/1.0" && oracle.probeProfile === "renderweave-conformance-probes/1.0", "ORACLE_VERSION_PROFILE", oracle.oracleId);
      check(JSON.stringify(oracle.assertions) === JSON.stringify(assertions), "ORACLE_MATERIALIZATION", oracle.oracleId);
      check(JSON.stringify(input.parameters.plannedAssertions) === JSON.stringify(assertions), "CASE_PLANNED_ASSERTIONS", caseRecord.caseId);
      const staticGoldens = staticGoldensByClass.get(axis.executionClass);
      if (staticGoldens) {
        const golden = staticGoldens.get(caseRecord.caseId);
        check(Boolean(golden), "STATIC_GENERATOR_GOLDEN_PRESENT", caseRecord.caseId);
        check(JSON.stringify(golden?.parameters) === JSON.stringify(input.parameters), "STATIC_GENERATOR_PARAMETERS_MATCH_CANDIDATE", caseRecord.caseId);
        check(golden?.expectedFixtureArtifact?.sha256 === digest(raw(`.scratch/renderweave-template-v1/${golden?.expectedFixtureArtifact?.path}`)), "STATIC_GENERATOR_FIXTURE_BINDING", caseRecord.caseId);
      }
      check(oracle.supersedes.length === 0, "ORACLE_SUPERSEDES", oracle.oracleId);
      for (const assertion of oracle.assertions) {
        const probe = probeById.get(assertion.probeId);
        check(Boolean(probe), "ASSERTION_PROBE_EXISTS", `${oracle.oracleId}:${assertion.assertionId}`);
        if (!probe) continue;
        check(probe.executionClasses.includes(axis.executionClass), "ASSERTION_PROBE_CLASS", `${oracle.oracleId}:${assertion.assertionId}`);
        check(expectedTypeValid(probe, assertion), "ASSERTION_TYPE_OPERATOR", `${oracle.oracleId}:${assertion.assertionId}`);
      }
      const caseSignature = identity("renderweave-conformance-case-signature/1", { stimulus: caseRecord.stimulus, expectedTerminals: caseRecord.expectedTerminals });
      const oracleSignature = identity("renderweave-conformance-oracle-signature/1", { probeProfile: oracle.probeProfile, assertions: oracle.assertions });
      check(!caseSignatures.has(caseSignature), "CASE_SIGNATURE_DUPLICATE", caseRecord.caseId);
      check(!oracleSignatures.has(oracleSignature), "ORACLE_SIGNATURE_DUPLICATE", oracle.oracleId);
      caseSignatures.add(caseSignature);
      oracleSignatures.add(oracleSignature);
    }
  }
  check(expectedOrdinal === 526, "ORACLE_ORDINAL_END", String(expectedOrdinal));
  check(domainGoldenByScenario.size === 13, "DOMAIN_GOLDEN_COUNT", String(domainGoldenByScenario.size));
  check(designGoldenByScenario.size === 196, "DESIGN_GOLDEN_COUNT", String(designGoldenByScenario.size));
  check(renderingGoldenByScenario.size === 157, "RENDERING_GOLDEN_COUNT", String(renderingGoldenByScenario.size));
  check(rendererGoldenByScenario.size === 163, "RENDERER_GOLDEN_COUNT", String(rendererGoldenByScenario.size));
  for (const [executionClass, expected] of EXPECTED_CLASS_AXIS_COUNTS) {
    check(classCounts.get(executionClass) === expected, "CLASS_AXIS_COUNT", executionClass);
    const generator = generators.generators.find((entry) => entry.executionClass === executionClass);
    const baseline = baselines.baselines.find((entry) => entry.executionClass === executionClass);
    const execution = classes.classes.find((entry) => entry.executionClass === executionClass);
    const isStaticBootstrapped = staticGoldensByClass.has(executionClass);
    check(generator?.recordMayReference === isStaticBootstrapped, "GENERATOR_REFERENCEABILITY", executionClass);
    check(baseline?.recordMayReference === isStaticBootstrapped, "BASELINE_REFERENCEABILITY", executionClass);
    check(execution?.executable !== true && !execution?.targetManifest, "EXECUTION_CLASS_NOT_EXECUTABLE", executionClass);
    check(Boolean(execution?.observationAdapter) === isStaticBootstrapped, "EXECUTION_CLASS_ADAPTER", executionClass);
    if (isStaticBootstrapped) {
      for (const binding of [generator.implementationManifest, generator.targetManifest, generator.goldenVectors, baseline.fixtureArtifact, baseline.observationAdapter, execution.observationAdapter]) {
        const content = raw(`.scratch/renderweave-template-v1/${binding.path}`);
        check(digest(content) === binding.sha256, "STATIC_BINDING_DIGEST", binding.path);
      }
    }
    const readiness = manifest.classReadiness.find((entry) => entry.executionClass === executionClass);
    check(readiness?.axisCount === expected && readiness?.candidateCaseCount === expected * 3, "READINESS_COUNTS", executionClass);
    const expectedBlockers = isStaticBootstrapped
      ? ["EXACT_TARGET_MANIFEST_PENDING", "REQUIRED_EXECUTOR_MANIFESTS_PENDING", "INDEPENDENT_EXECUTION_REPLAY_PENDING"]
      : ["SAFE_BASELINE_FIXTURE_SCHEMA_AND_ADAPTER_PENDING", "GENERATOR_IMPLEMENTATION_TARGET_AND_GOLDENS_PENDING", "OBSERVATION_ADAPTER_PENDING", "EXACT_TARGET_MANIFEST_PENDING", "REQUIRED_EXECUTOR_MANIFESTS_PENDING", "INDEPENDENT_EXECUTION_REPLAY_PENDING"];
    check(JSON.stringify(readiness?.blockers) === JSON.stringify(expectedBlockers) && readiness?.recordIssuanceAllowed === false, "READINESS_BLOCKERS", executionClass);
  }
  const formalCases = jsonl(".scratch/renderweave-template-v1/conformance-cases-v1.jsonl");
  const formalOracles = jsonl(".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl");
  check(formalCases.length === 46 && formalCases.every((record) => !record.caseId.startsWith("CAP::")), "FORMAL_CASES_UNCHANGED", String(formalCases.length));
  check(formalOracles.length === 46 && formalOracles.every((record) => !record.oracleId.startsWith("ORC::CAPACITY::")), "FORMAL_ORACLES_UNCHANGED", String(formalOracles.length));
  check(manifest.formalRegistries.appendPerformedByThisMaterialization === false, "NO_FORMAL_APPEND", "false");

  const result = {
    resultVersion: "renderweave-capacity-boundary-static-replay-result/1.0",
    executorId: "CAPACITY_STATIC_EXECUTOR::NODE::1.0",
    role: "primary-capacity-materialization-validator",
    status: failures.length === 0 ? "PASS" : "FAIL",
    checkCount,
    failureCount: failures.length,
    failures,
    runtime: `Node.js ${process.version}`,
    materializationManifestSha256: digest(raw(MANIFEST)),
    candidateCasesSha256: digest(raw(".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-cases-v1.jsonl")),
    candidateOraclesSha256: digest(raw(".scratch/renderweave-template-v1/capacity-boundary/candidate/conformance-oracles-v1.jsonl")),
    scope: "static candidate materialization only; no class executor or target invoked",
    recordIssuanceAllowed: false
  };
  const serialized = `${JSON.stringify(result, null, 2)}\n`;
  if (outputPath) writeFileSync(resolve(ROOT, outputPath), serialized, "utf8");
  process.stdout.write(serialized);
  if (failures.length > 0) process.exitCode = 1;
}

main();
