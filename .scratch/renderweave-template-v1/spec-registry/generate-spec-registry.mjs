import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");
const ROOT = resolve(SPEC, "..", "..");
const FIXTURES = resolve(HERE, "fixtures");
const EXPECTED = resolve(HERE, "expected-bytes");
const CANDIDATE = resolve(HERE, "candidate");
const mode = process.argv[2] ?? "bootstrap";

const PROFILE_ID = "renderweave-conformance-probes/1.0";
const C14N_ID = "renderweave-conformance-c14n/1.0";
const EXECUTION_CLASS = "EXEC::SPEC_REGISTRY::1.0";
const BASELINE_ID = "baseline.spec-registry.empty-v1";
const GENERATOR_PROFILE = "renderweave-spec-registry-generator/1.0";
const ADAPTER_ID = "renderweave-spec-registry-observation-adapter/1.0";
const TARGET_ID = "SPEC_TARGET::REGISTRY::1.0";
const IMPLEMENTATION_REVISION = "spec-registry-bootstrap/1.0";
const FAULT_IDENTITY = "sha256:41042a03228cdd46583d0e6aff814ba139e183b72280d45375e9a09d0b9e09ae";

const CLASS_ORDER = [
  "EXEC::SPEC_REGISTRY::1.0",
  "EXEC::DOMAIN_SERVICES::1.0",
  "EXEC::DESIGN_INPUT_EXPRESSION::1.0",
  "EXEC::RENDERING_PIPELINE::1.0",
  "EXEC::RENDERER_EXACT_OUTPUT::1.0",
  "EXEC::EDITOR_AUTOMATED::1.0"
];

const NAMED_MUTATIONS = [
  ["CASE_SCHEMA_UNKNOWN_MEMBER", "SPEC_REGISTRY_SCHEMA_INVALID", ["RW-T19-S13-058", "RW-T19-S13-059", "RW-T19-S13-076", "RW-T19-S13-077"]],
  ["ORACLE_SCHEMA_ASSERTION_INVALID", "SPEC_REGISTRY_SCHEMA_INVALID", ["RW-T19-S13-078", "RW-T19-S13-082", "RW-T19-S13-084", "RW-T19-S13-087"]],
  ["SOURCE_LOCATOR_OUT_OF_RANGE", "SPEC_REGISTRY_SOURCE_LOCATOR_INVALID", ["RW-T19-S13-034", "RW-T19-S13-354"]],
  ["DUPLICATE_REQUIREMENT_ID", "SPEC_REGISTRY_DUPLICATE_IDENTITY", ["RW-T19-S13-033", "RW-T19-S13-040"]],
  ["ORPHAN_REQUIREMENT", "SPEC_REGISTRY_ORPHAN", ["RW-T19-S13-033", "RW-T19-S13-105", "RW-T19-S13-123", "RW-T19-S13-124", "RW-T19-S13-327", "RW-T19-S13-350"]],
  ["ORPHAN_CASE", "SPEC_REGISTRY_ORPHAN", ["RW-T19-S13-033", "RW-T19-S13-106", "RW-T19-S13-107", "RW-T19-S13-351"]],
  ["ORPHAN_ORACLE", "SPEC_REGISTRY_ORPHAN", ["RW-T19-S13-033", "RW-T19-S13-109", "RW-T19-S13-352"]],
  ["TERMINAL_ASSERTION_MISSING", "SPEC_REGISTRY_TERMINAL_ASSERTION_MISSING", ["RW-T19-S13-108"]],
  ["SUPERSESSION_CYCLE", "SPEC_REGISTRY_SUPERSESSION_INVALID", ["RW-T19-S13-097", "RW-T19-S13-098", "RW-T19-S13-099"]],
  ["SUPERSESSION_DANGLING", "SPEC_REGISTRY_SUPERSESSION_INVALID", ["RW-T19-S13-100", "RW-T19-S13-102"]],
  ["ACTIVE_CASE_TO_SUPERSEDED_ORACLE", "SPEC_REGISTRY_SUPERSESSION_INVALID", ["RW-T19-S13-103", "RW-T19-S13-104", "RW-T19-S13-113"]],
  ["DUPLICATE_CASE_SIGNATURE", "SPEC_REGISTRY_SIGNATURE_DUPLICATE", ["RW-T19-S13-110", "RW-T19-S13-111", "RW-T19-S13-166"]],
  ["DUPLICATE_ORACLE_SIGNATURE", "SPEC_REGISTRY_SIGNATURE_DUPLICATE", ["RW-T19-S13-093", "RW-T19-S13-094", "RW-T19-S13-112", "RW-T19-S13-228"]],
  ["INPUT_IDENTITY_MISMATCH", "SPEC_REGISTRY_IDENTITY_MISMATCH", ["RW-T19-S13-068", "RW-T19-S13-163", "RW-T19-S13-165"]],
  ["FAULT_IDENTITY_MISMATCH", "SPEC_REGISTRY_IDENTITY_MISMATCH", ["RW-T19-S13-070", "RW-T19-S13-164", "RW-T19-S13-165"]],
  ["MANIFEST_DIGEST_MISMATCH", "SPEC_REGISTRY_MANIFEST_MISMATCH", ["RW-T19-S13-043", "RW-T19-S13-096", "RW-T19-S13-341", "RW-T19-S13-347"]],
  ["PROBE_OPERATOR_NOT_ALLOWED", "SPEC_REGISTRY_PROFILE_INVALID", ["RW-T19-S13-179", "RW-T19-S13-183", "RW-T19-S13-188"]],
  ["ASSERTION_VALUE_TYPE_MISMATCH", "SPEC_REGISTRY_PROFILE_INVALID", ["RW-T19-S13-177", "RW-T19-S13-184", "RW-T19-S13-336"]],
  ["GENERATOR_GOLDEN_MISMATCH", "SPEC_REGISTRY_GENERATOR_INVALID", ["RW-T19-S13-265", "RW-T19-S13-273", "RW-T19-S13-274", "RW-T19-S13-334"]],
  ["REFERENCE_CLOSURE_MISSING_ARTIFACT", "SPEC_REGISTRY_REFERENCE_MISSING", ["RW-T19-S13-258", "RW-T19-S13-275", "RW-T19-S13-341", "RW-T19-S13-354"]],
  ["ORDINARY_CASE_MULTI_TERMINAL", "SPEC_REGISTRY_TERMINAL_VECTOR_INVALID", ["RW-T19-S13-071", "RW-T19-S13-072", "RW-T19-S13-073"]],
  ["NONCANONICAL_JSONL_RECORD", "SPEC_REGISTRY_CANONICAL_RECORD_INVALID", ["RW-T19-S13-128", "RW-T19-S13-144", "RW-T19-S13-145"]],
  ["DUPLICATE_JSON_MEMBER", "SPEC_REGISTRY_SCHEMA_INVALID", ["RW-T19-S13-130"]],
  ["PROFILE_ID_INVALID", "SPEC_REGISTRY_PROFILE_INVALID", ["RW-T19-S13-172", "RW-T19-S13-174", "RW-T19-S13-175", "RW-T19-S13-246"]]
];

const CANONICAL_REQUIREMENTS = {
  "C14N-P001": ["RW-T19-S13-135", "RW-T19-S13-140", "RW-T19-S13-141", "RW-T19-S13-143"],
  "C14N-P002": ["RW-T19-S13-142"],
  "C14N-P003": ["RW-T19-S13-136", "RW-T19-S13-140"],
  "C14N-P004": ["RW-T19-S13-138", "RW-T19-S13-139"],
  "C14N-P005": ["RW-T19-S13-137", "RW-T19-S13-138"],
  "C14N-P006": ["RW-T19-S13-137"],
  "C14N-P007": ["RW-T19-S13-147", "RW-T19-S13-149", "RW-T19-S13-150"],
  "C14N-P008": ["RW-T19-S13-152"],
  "C14N-P009": ["RW-T19-S13-157"],
  "C14N-P010": ["RW-T19-S13-159", "RW-T19-S13-160"],
  "C14N-P011": ["RW-T19-S13-161", "RW-T19-S13-162"],
  "C14N-P012": ["RW-T19-S13-163"],
  "C14N-P013": ["RW-T19-S13-164"],
  "C14N-N001": ["RW-T19-S13-130"],
  "C14N-N002": ["RW-T19-S13-131"],
  "C14N-N003": ["RW-T19-S13-132"],
  "C14N-N004": ["RW-T19-S13-133"],
  "C14N-N005": ["RW-T19-S13-133"],
  "C14N-N006": ["RW-T19-S13-134"],
  "C14N-N007": ["RW-T19-S13-129"],
  "C14N-N008": ["RW-T19-S13-158"]
};

function read(path) {
  return readFileSync(resolve(ROOT, path));
}

function readJson(path) {
  return JSON.parse(read(path).toString("utf8"));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function digest(value) {
  return `sha256:${sha256(value)}`;
}

function pretty(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function write(path, value) {
  const absolute = resolve(ROOT, path);
  mkdirSync(dirname(absolute), { recursive: true });
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value, "utf8");
  writeFileSync(absolute, bytes);
  return { path: relative(SPEC, absolute).replaceAll("\\", "/"), sha256: digest(bytes), byteLength: bytes.length };
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
  return digest(Buffer.concat([Buffer.from(`${domain}\0`, "utf8"), Buffer.from(canonicalMap(projection), "utf8")]));
}

function rel(path) {
  return relative(SPEC, path).replaceAll("\\", "/");
}

function scenarioDigest(scenarioId) {
  return digest(Buffer.from(`renderweave-spec-registry-scenario/1\0${scenarioId}`, "utf8"));
}

function canonicalVectorScenarios() {
  const vectors = readJson(".scratch/renderweave-template-v1/conformance-canonical-vectors-v1.json");
  return [...vectors.positiveVectors, ...vectors.negativeVectors].map((vector) => ({
    scenarioId: `SPEC-C14N-${vector.vectorId}`,
    scenarioKind: "CANONICAL_VECTOR",
    vectorId: vector.vectorId,
    vectorKind: vector.kind ?? "REJECTION",
    expectedOutcome: vector.expectedCode ? "PROBLEM" : "SUCCESS",
    expectedCode: vector.expectedCode ?? null,
    requirementIds: CANONICAL_REQUIREMENTS[vector.vectorId]
  }));
}

function allScenarios() {
  const complete = [{
    scenarioId: "SPEC-COMPLETE-REGISTRY",
    scenarioKind: "COMPLETE_REGISTRY",
    expectedOutcome: "SUCCESS",
    expectedCode: null,
    requirementIds: []
  }];
  const mutations = NAMED_MUTATIONS.map(([mutationKind, expectedCode, requirementIds], index) => ({
    scenarioId: `SPEC-MUT-${String(index + 1).padStart(3, "0")}`,
    scenarioKind: "NAMED_MUTATION",
    mutationKind,
    expectedOutcome: "PROBLEM",
    expectedCode,
    requirementIds
  }));
  return [...complete, ...mutations, ...canonicalVectorScenarios()];
}

function bootstrap() {
  mkdirSync(FIXTURES, { recursive: true });
  mkdirSync(EXPECTED, { recursive: true });
  mkdirSync(CANDIDATE, { recursive: true });

  const baseline = {
    fixtureVersion: "renderweave-spec-registry-baseline/1.0",
    baselineId: BASELINE_ID,
    executionClass: EXECUTION_CLASS,
    targetRoot: ".scratch/renderweave-template-v1",
    registryMode: "APPEND_ONLY_JSONL",
    productMutationAllowed: false,
    externalReadAllowed: false
  };
  const baselineArtifact = write(".scratch/renderweave-template-v1/spec-registry/baseline-v1.json", pretty(baseline));
  const byteSample = write(".scratch/renderweave-template-v1/spec-registry/expected-bytes/assertion-byte-sample-v1.bin", Buffer.from([0x00, 0x01, 0x7f, 0x80, 0xff]));

  const canonicalVectors = readJson(".scratch/renderweave-template-v1/conformance-canonical-vectors-v1.json");
  const vectorById = new Map([...canonicalVectors.positiveVectors, ...canonicalVectors.negativeVectors].map((v) => [v.vectorId, v]));
  const scenarios = allScenarios();
  if (scenarios.length !== 46) throw new Error(`expected 46 scenarios, got ${scenarios.length}`);
  const fixtureEntries = [];
  for (const scenario of scenarios) {
    const fixture = {
      fixtureVersion: "renderweave-spec-registry-scenario/1.0",
      scenarioId: scenario.scenarioId,
      scenarioKind: scenario.scenarioKind,
      expectedOutcome: scenario.expectedOutcome,
      expectedCode: scenario.expectedCode,
      expectedStage: scenario.expectedOutcome === "PROBLEM" ? "SPEC_REGISTRY" : null
    };
    if (scenario.mutationKind) fixture.mutationKind = scenario.mutationKind;
    if (scenario.vectorId) fixture.vectorId = scenario.vectorId;
    const fixtureName = `${scenario.scenarioId.toLowerCase()}.json`;
    const fixtureArtifact = write(`.scratch/renderweave-template-v1/spec-registry/fixtures/${fixtureName}`, pretty(fixture));
    const entry = {
      ordinal: fixtureEntries.length + 1,
      ...scenario,
      fixtureArtifactPath: fixtureArtifact.path,
      fixtureArtifactSha256: fixtureArtifact.sha256,
      fixtureByteLength: fixtureArtifact.byteLength
    };
    if (scenario.vectorId) {
      const vector = vectorById.get(scenario.vectorId);
      if (vector.expectedCanonicalUtf8Hex) {
        const expectedArtifact = write(`.scratch/renderweave-template-v1/spec-registry/expected-bytes/${scenario.vectorId.toLowerCase()}.bin`, Buffer.from(vector.expectedCanonicalUtf8Hex, "hex"));
        entry.expectedBytesArtifact = expectedArtifact;
      }
    }
    fixtureEntries.push(entry);
  }

  const catalog = {
    artifactVersion: "renderweave-spec-registry-scenario-catalog/1.0",
    status: "FROZEN",
    generatorProfile: GENERATOR_PROFILE,
    executionClass: EXECUTION_CLASS,
    completeRegistryScenarioCount: 1,
    namedMutationScenarioCount: 24,
    canonicalVectorScenarioCount: 21,
    scenarioCount: 46,
    scenarios: fixtureEntries
  };
  const catalogArtifact = write(".scratch/renderweave-template-v1/spec-registry/scenario-catalog-v1.json", pretty(catalog));

  const probeProfile = readJson(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json");
  const sampleFor = (valueType) => {
    switch (valueType) {
      case "BOOLEAN": return true;
      case "TEXT": return "sample";
      case "CODE": return "SPEC_REGISTRY_SCHEMA_INVALID";
      case "STAGE": return "SPEC_REGISTRY";
      case "DIGEST": return `sha256:${"0".repeat(64)}`;
      case "INTEGER": return 1;
      case "TEXT_SEQUENCE": return ["alpha", "beta"];
      case "INTEGER_SEQUENCE": return [0, 1];
      default: return null;
    }
  };
  const wrongFor = (valueType) => {
    if (valueType === "BOOLEAN") return "true";
    if (valueType === "INTEGER") return "1";
    if (valueType.endsWith("SEQUENCE")) return "not-a-sequence";
    return 1;
  };
  const accepted = [];
  const rejected = [];
  for (const probe of probeProfile.probes) {
    for (const operator of probe.allowedOperators) {
      const assertion = { assertionId: "A001", probeId: probe.probeId, operator };
      if (operator === "BYTES_EQ") {
        assertion.expected = { kind: "ARTIFACT", artifactPath: byteSample.path, mediaType: "application/octet-stream", artifactSha256: byteSample.sha256 };
      } else if (operator === "WITHIN") {
        assertion.expected = { kind: "LITERAL", value: { minimum: { numerator: 0, denominator: 1 }, maximum: { numerator: 5000, denominator: 1 } } };
      } else if (operator !== "ABSENT") {
        assertion.expected = { kind: "LITERAL", value: sampleFor(probe.valueType) };
      }
      accepted.push({ vectorId: `ASSERT-OK-${String(accepted.length + 1).padStart(3, "0")}`, probeId: probe.probeId, valueType: probe.valueType, assertion });
    }
    const operator = probe.allowedOperators[0];
    const assertion = { assertionId: "A001", probeId: probe.probeId, operator };
    if (operator === "BYTES_EQ") assertion.expected = { kind: "LITERAL", value: "not-an-artifact" };
    else if (operator === "WITHIN") assertion.expected = { kind: "LITERAL", value: { minimum: 1, maximum: 0 } };
    else if (operator !== "ABSENT") assertion.expected = { kind: "LITERAL", value: wrongFor(probe.valueType) };
    rejected.push({ vectorId: `ASSERT-NO-${String(rejected.length + 1).padStart(3, "0")}`, probeId: probe.probeId, valueType: probe.valueType, assertion, expectedCode: "CONFORMANCE_ASSERTION_EXPECTED_INVALID" });
  }
  const assertionVectors = {
    artifactVersion: "renderweave-conformance-assertion-vectors/1.0",
    status: "FROZEN",
    probeProfile: PROFILE_ID,
    probeCount: probeProfile.probes.length,
    valueEncoding: {
      BOOLEAN: "JSON boolean",
      TEXT: "JSON string",
      CODE: "JSON string matching uppercase stable-code syntax",
      STAGE: "JSON string matching uppercase stage syntax",
      DIGEST: "JSON string sha256:<64 lowercase hex>",
      INTEGER: "JSON integer within exact safe registry range",
      BYTE_SEQUENCE: "SHA-256-bound ARTIFACT expected value",
      TEXT_SEQUENCE: "JSON string array preserving order",
      INTEGER_SEQUENCE: "JSON integer array preserving order",
      RATIONAL_DURATION_MILLISECONDS: "WITHIN literal with closed minimum and maximum rational numerator/denominator pairs"
    },
    acceptedVectors: accepted,
    rejectedVectors: rejected,
    acceptedVectorCount: accepted.length,
    rejectedVectorCount: rejected.length,
    completeProbeOperatorCoverage: true
  };
  const assertionArtifact = write(".scratch/renderweave-template-v1/spec-registry/assertion-vectors-v1.json", pretty(assertionVectors));

  const specProbes = probeProfile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS));
  const adapter = {
    artifactVersion: "renderweave-spec-registry-observation-adapter/1.0",
    adapterId: ADAPTER_ID,
    status: "FROZEN",
    executionClass: EXECUTION_CLASS,
    probeProfile: PROFILE_ID,
    genericJsonPathAllowed: false,
    arbitraryScriptAllowed: false,
    fallbackAllowed: false,
    mappings: specProbes.map((probe) => ({
      probeId: probe.probeId,
      valueType: probe.valueType,
      source: `closedObservation.${probe.probeId}`,
      absentPolicy: probe.allowedOperators.includes("ABSENT") ? "EXPLICIT_ABSENT" : "MUST_BE_PRESENT"
    })),
    mappingCount: specProbes.length
  };
  if (adapter.mappingCount !== 31) throw new Error(`expected 31 SPEC_REGISTRY probes, got ${adapter.mappingCount}`);
  const adapterArtifact = write(".scratch/renderweave-template-v1/spec-registry/observation-adapter-v1.json", pretty(adapter));

  const goldens = {
    artifactVersion: "renderweave-spec-registry-generator-goldens/1.0",
    generatorProfile: GENERATOR_PROFILE,
    baseline: baselineArtifact,
    scenarios: fixtureEntries.map((entry) => ({
      scenarioId: entry.scenarioId,
      parameters: {
        mode: "NAMED_SCENARIO",
        scenarioId: entry.scenarioId,
        fixtureArtifactPath: entry.fixtureArtifactPath,
        fixtureArtifactSha256: entry.fixtureArtifactSha256,
        expectedObservationProfile: ADAPTER_ID
      },
      expectedFixtureArtifactPath: entry.fixtureArtifactPath,
      expectedFixtureArtifactSha256: entry.fixtureArtifactSha256,
      expectedFixtureByteLength: entry.fixtureByteLength
    })),
    goldenCount: fixtureEntries.length
  };
  const goldenArtifact = write(".scratch/renderweave-template-v1/spec-registry/generator-goldens-v1.json", pretty(goldens));

  const scriptArtifact = { path: rel(fileURLToPath(import.meta.url)), sha256: digest(readFileSync(fileURLToPath(import.meta.url))), byteLength: readFileSync(fileURLToPath(import.meta.url)).length };
  const generatorTarget = {
    artifactVersion: "renderweave-spec-registry-generator-target/1.0",
    targetId: "SPEC_GENERATOR_TARGET::REGISTRY::1.0",
    generatorProfile: GENERATOR_PROFILE,
    status: "FROZEN",
    implementationRevision: IMPLEMENTATION_REVISION,
    entrypoint: scriptArtifact,
    baseline: baselineArtifact,
    scenarioCatalog: catalogArtifact,
    assertionVectors: assertionArtifact,
    observationAdapter: adapterArtifact,
    goldenVectors: goldenArtifact,
    expectedScenarioCount: 46,
    networkReadsAllowed: false,
    environmentReadsAllowed: false
  };
  const generatorTargetArtifact = write(".scratch/renderweave-template-v1/spec-registry/generator-target-manifest-v1.json", pretty(generatorTarget));
  const implementationManifest = {
    artifactVersion: "renderweave-spec-registry-generator-implementation/1.0",
    generatorProfile: GENERATOR_PROFILE,
    status: "FROZEN_GOLDENS_PRESENT",
    implementationRevision: IMPLEMENTATION_REVISION,
    runtime: "Node.js >=24",
    entrypoint: scriptArtifact,
    baseline: baselineArtifact,
    scenarioCatalog: catalogArtifact,
    assertionVectors: assertionArtifact,
    observationAdapter: adapterArtifact,
    goldenVectors: goldenArtifact,
    targetManifest: generatorTargetArtifact,
    environmentReadsAllowed: false,
    networkReadsAllowed: false,
    currentTimeReadsAllowed: false,
    hiddenDefaultsAllowed: false
  };
  const implementationArtifact = write(".scratch/renderweave-template-v1/spec-registry/generator-implementation-manifest-v1.json", pretty(implementationManifest));
  process.stdout.write(`${pretty({ status: "BOOTSTRAP_ARTIFACTS_GENERATED", baselineArtifact, catalogArtifact, assertionArtifact, adapterArtifact, goldenArtifact, generatorTargetArtifact, implementationArtifact })}`);
}

function loadRequirements() {
  const lines = read(".scratch/renderweave-template-v1/requirements/19.tsv").toString("utf8").trimEnd().split("\n");
  const header = lines.shift();
  if (header !== "requirement_id\tsource_line\tclause_ordinal_on_line\tfamily\tnormative_summary") throw new Error("unexpected T19 TSV header");
  return lines.map((line) => {
    const [requirementId, sourceLine, clauseOrdinal, family, normativeSummary] = line.split("\t");
    return { requirementId, sourceLine: Number(sourceLine), clauseOrdinal: Number(clauseOrdinal), family, normativeSummary };
  });
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

function assertionSet(scenario, context) {
  const success = scenario.expectedOutcome === "SUCCESS";
  const assertions = [
    literal("operation.accepted", success),
    success ? absent("operation.terminalCode") : literal("operation.terminalCode", scenario.expectedCode),
    success ? absent("operation.terminalStage") : literal("operation.terminalStage", "SPEC_REGISTRY"),
    literal("operation.writeCount", 0),
    literal("operation.renderDocumentCount", 0),
    literal("operation.renderOutputCount", 0),
    sequence("operation.downstreamEffects", []),
    literal("specRegistry.identityDigest", scenarioDigest(scenario.scenarioId))
  ];
  if (scenario.scenarioKind === "COMPLETE_REGISTRY") {
    assertions.push(
      literal("specRegistry.requirementCount", context.requirementCount),
      literal("specRegistry.semanticRequirementCount", context.requirementCount - 1),
      literal("specRegistry.trackingRequirementCount", 1),
      literal("specRegistry.caseRecordCount", 46),
      literal("specRegistry.oracleRecordCount", 46),
      literal("specRegistry.orphanRequirementCount", 0),
      literal("specRegistry.orphanCaseCount", 0),
      literal("specRegistry.orphanOracleCount", 0),
      literal("specRegistry.duplicateIdentityCount", 0),
      literal("specRegistry.supersessionGraphValid", true),
      literal("specRegistry.sourceLocatorErrorCount", 0),
      literal("specRegistry.schemaErrorCount", 0),
      sequence("specRegistry.executionOrder", CLASS_ORDER),
      literal("specRegistry.referenceClosureValid", true),
      literal("specRegistry.profileRecordMayReference", true)
    );
  }
  if (scenario.mutationKind === "SOURCE_LOCATOR_OUT_OF_RANGE") assertions.push(literal("specRegistry.sourceLocatorErrorCount", 1));
  if (["CASE_SCHEMA_UNKNOWN_MEMBER", "ORACLE_SCHEMA_ASSERTION_INVALID", "DUPLICATE_JSON_MEMBER"].includes(scenario.mutationKind)) assertions.push(literal("specRegistry.schemaErrorCount", 1));
  if (scenario.mutationKind === "DUPLICATE_REQUIREMENT_ID") assertions.push(literal("specRegistry.duplicateIdentityCount", 1));
  if (scenario.mutationKind === "ORPHAN_REQUIREMENT") assertions.push(literal("specRegistry.orphanRequirementCount", 1));
  if (scenario.mutationKind === "ORPHAN_CASE") assertions.push(literal("specRegistry.orphanCaseCount", 1));
  if (scenario.mutationKind === "ORPHAN_ORACLE") assertions.push(literal("specRegistry.orphanOracleCount", 1));
  if (["SUPERSESSION_CYCLE", "SUPERSESSION_DANGLING", "ACTIVE_CASE_TO_SUPERSEDED_ORACLE"].includes(scenario.mutationKind)) assertions.push(literal("specRegistry.supersessionGraphValid", false));
  if (scenario.mutationKind === "REFERENCE_CLOSURE_MISSING_ARTIFACT") assertions.push(literal("specRegistry.referenceClosureValid", false));
  if (["PROBE_OPERATOR_NOT_ALLOWED", "ASSERTION_VALUE_TYPE_MISMATCH", "PROFILE_ID_INVALID"].includes(scenario.mutationKind)) assertions.push(literal("specRegistry.profileRecordMayReference", false));
  if (scenario.scenarioKind === "CANONICAL_VECTOR") {
    const vector = context.vectorById.get(scenario.vectorId);
    if (vector.expectedCanonicalUtf8Hex) {
      const expectedPath = `spec-registry/expected-bytes/${scenario.vectorId.toLowerCase()}.bin`;
      assertions.push({
        probeId: "specRegistry.canonicalRecordBytes",
        operator: "BYTES_EQ",
        expected: { kind: "ARTIFACT", artifactPath: expectedPath, mediaType: "application/octet-stream", artifactSha256: digest(Buffer.from(vector.expectedCanonicalUtf8Hex, "hex")) }
      });
    }
    if (vector.expectedDigest) assertions.push(literal("specRegistry.manifestDigest", vector.expectedDigest));
  }
  return assertions.map((assertion, index) => ({ assertionId: `A${String(index + 1).padStart(3, "0")}`, ...assertion }));
}

function generatedInput(parameters, generatorManifestSha256, safeBaselineManifestSha256) {
  const projection = {
    kind: "GENERATED",
    generatorProfile: GENERATOR_PROFILE,
    generatorManifestSha256,
    parameters: sortedObject(parameters),
    safeBaselineId: BASELINE_ID,
    safeBaselineManifestSha256
  };
  return { ...projection, identitySha256: identity("renderweave-conformance-input-identity/1", projection) };
}

function canonicalOracleLine(record) {
  return JSON.stringify(record);
}

function canonicalCaseLine(record) {
  return JSON.stringify(record);
}

function artifact(path) {
  const bytes = read(path);
  return { path: path.replace(".scratch/renderweave-template-v1/", ""), sha256: digest(bytes), byteLength: bytes.length };
}

function records(finalized = false) {
  const scenarios = readJson(".scratch/renderweave-template-v1/spec-registry/scenario-catalog-v1.json").scenarios;
  const canonicalVectors = readJson(".scratch/renderweave-template-v1/conformance-canonical-vectors-v1.json");
  const vectorById = new Map([...canonicalVectors.positiveVectors, ...canonicalVectors.negativeVectors].map((v) => [v.vectorId, v]));
  const requirements = loadRequirements();
  const assigned = requirements.filter((requirement) => requirement.requirementId === "RW-T19-S00-001" || requirement.requirementId.startsWith("RW-T19-S13-"));
  const allIds = new Set(requirements.map((requirement) => requirement.requirementId));
  const scenarioById = new Map(scenarios.map((scenario) => [scenario.scenarioId, { ...scenario, requirementIds: [...scenario.requirementIds] }]));
  const anchored = new Set();
  for (const scenario of scenarioById.values()) {
    for (const requirementId of scenario.requirementIds) {
      if (!allIds.has(requirementId)) throw new Error(`unknown anchored requirement ${requirementId}`);
      anchored.add(requirementId);
    }
  }
  const complete = scenarioById.get("SPEC-COMPLETE-REGISTRY");
  for (const requirement of assigned) if (!anchored.has(requirement.requirementId)) complete.requirementIds.push(requirement.requirementId);
  complete.requirementIds.sort(utf8Compare);

  const generatorManifestSha256 = digest(read(".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json"));
  const safeBaselineManifestSha256 = digest(read(".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json"));
  const context = { requirementCount: JSON.parse(read(".scratch/renderweave-template-v1/requirements-v1.json")).counts.requirements, vectorById };
  const cases = [];
  const oracles = [];
  const coverageCases = [];
  for (const source of [...scenarioById.values()].sort((a, b) => a.ordinal - b.ordinal)) {
    const ordinal = String(source.ordinal).padStart(6, "0");
    const caseId = `CONF::SPEC_REGISTRY::${ordinal}`;
    const oracleId = `ORC::SPEC_REGISTRY::${ordinal}`;
    const assertions = assertionSet(source, context);
    const oracle = {
      recordVersion: "renderweave-conformance-oracle-record/1.0",
      oracleId,
      probeProfile: PROFILE_ID,
      assertions,
      supersedes: []
    };
    const parameters = {
      mode: "NAMED_SCENARIO",
      scenarioId: source.scenarioId,
      fixtureArtifactPath: source.fixtureArtifactPath,
      fixtureArtifactSha256: source.fixtureArtifactSha256,
      expectedObservationProfile: ADAPTER_ID
    };
    const terminal = source.expectedOutcome === "SUCCESS"
      ? { operationId: "validate", outcome: "SUCCESS" }
      : { operationId: "validate", outcome: "PROBLEM", code: source.expectedCode, stage: "SPEC_REGISTRY" };
    const assertionIds = assertions.map((assertion) => assertion.assertionId);
    const coverage = [...new Set(source.requirementIds)].sort(utf8Compare).map((requirementId) => ({
      requirementId,
      evidence: [{ oracleId, assertionIds }]
    }));
    if (coverage.length === 0) throw new Error(`scenario ${source.scenarioId} has no coverage`);
    const caseRecord = {
      recordVersion: "renderweave-conformance-case-record/1.0",
      caseId,
      suite: "SPEC_REGISTRY",
      family: "SPEC_REGISTRY",
      executionClass: EXECUTION_CLASS,
      stimulus: {
        input: generatedInput(parameters, generatorManifestSha256, safeBaselineManifestSha256),
        faultSchedule: { kind: "NONE", identitySha256: FAULT_IDENTITY }
      },
      expectedTerminals: [terminal],
      coverage,
      supersedes: []
    };
    cases.push(caseRecord);
    oracles.push(oracle);
    coverageCases.push({
      caseId,
      oracleId,
      scenarioId: source.scenarioId,
      requirementIds: coverage.map((edge) => edge.requirementId),
      assertionIds,
      expectedTerminal: terminal
    });
  }
  const covered = new Set(cases.flatMap((record) => record.coverage.map((edge) => edge.requirementId)));
  const missing = assigned.map((requirement) => requirement.requirementId).filter((id) => !covered.has(id));
  if (missing.length) throw new Error(`uncovered assigned requirements: ${missing.join(",")}`);
  if (cases.length !== 46 || oracles.length !== 46) throw new Error("SPEC_REGISTRY record count drift");
  const caseBytes = Buffer.from(`${cases.map(canonicalCaseLine).join("\n")}\n`, "utf8");
  const oracleBytes = Buffer.from(`${oracles.map(canonicalOracleLine).join("\n")}\n`, "utf8");
  const candidateCases = write(".scratch/renderweave-template-v1/spec-registry/candidate/conformance-cases-v1.jsonl", caseBytes);
  const candidateOracles = write(".scratch/renderweave-template-v1/spec-registry/candidate/conformance-oracles-v1.jsonl", oracleBytes);

  const coverageArtifact = write(".scratch/renderweave-template-v1/spec-registry/coverage-v1.json", pretty({
    artifactVersion: "renderweave-spec-registry-coverage/1.0",
    status: "FROZEN",
    executionClass: EXECUTION_CLASS,
    assignmentRule: "RW-T19-S00-001 plus every active RW-T19-S13 requirement",
    assignedRequirementCount: assigned.length,
    assignedRequirementIds: assigned.map((requirement) => requirement.requirementId).sort(utf8Compare),
    caseCount: cases.length,
    oracleCount: oracles.length,
    cases: coverageCases
  }));

  const corpusDigest = digest(Buffer.from(`renderweave-spec-registry-active-corpus/1\0${candidateCases.sha256}\0${candidateOracles.sha256}`, "utf8"));
  const artifactPaths = [
    ".scratch/renderweave-template-v1/issues/19-security-capacity-acceptance.md",
    ".scratch/renderweave-template-v1/requirements/19.tsv",
    ".scratch/renderweave-template-v1/requirements-v1.json",
    ".scratch/renderweave-template-v1/conformance-case-record-schema-v1.json",
    ".scratch/renderweave-template-v1/conformance-oracle-record-schema-v1.json",
    ".scratch/renderweave-template-v1/conformance-canonical-profile-v1.json",
    ".scratch/renderweave-template-v1/conformance-canonical-vectors-v1.json",
    ".scratch/renderweave-template-v1/conformance-probe-profile-v1.json",
    ".scratch/renderweave-template-v1/conformance-oracle-family-catalog-v1.json",
    ".scratch/renderweave-template-v1/conformance-safe-baselines-v1.json",
    ".scratch/renderweave-template-v1/conformance-generator-manifests-v1.json",
    ".scratch/renderweave-template-v1/conformance-execution-classes-v1.json",
    ".scratch/renderweave-template-v1/conformance-bootstrap-order-v1.json",
    ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json",
    ".scratch/renderweave-template-v1/capacity-budgets-v1.json",
    ".scratch/renderweave-template-v1/capacity-oracles-v1.json",
    ".scratch/renderweave-template-v1/capacity-boundaries-v1.json",
    ".scratch/renderweave-template-v1/spec-registry/baseline-v1.json",
    ".scratch/renderweave-template-v1/spec-registry/scenario-catalog-v1.json",
    ".scratch/renderweave-template-v1/spec-registry/assertion-vectors-v1.json",
    ".scratch/renderweave-template-v1/spec-registry/observation-adapter-v1.json",
    ".scratch/renderweave-template-v1/spec-registry/generator-goldens-v1.json",
    ".scratch/renderweave-template-v1/spec-registry/generator-target-manifest-v1.json",
    ".scratch/renderweave-template-v1/spec-registry/generator-implementation-manifest-v1.json",
    ".scratch/renderweave-template-v1/spec-registry/coverage-v1.json"
  ];
  for (const scenario of scenarios) artifactPaths.push(`.scratch/renderweave-template-v1/${scenario.fixtureArtifactPath}`);
  const artifacts = [...new Set(artifactPaths)].map(artifact).sort((a, b) => utf8Compare(a.path, b.path));
  const formalCases = artifact(".scratch/renderweave-template-v1/conformance-cases-v1.jsonl");
  const formalOracles = artifact(".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl");
  const formalIssued = formalCases.sha256 === candidateCases.sha256 && formalOracles.sha256 === candidateOracles.sha256;
  if (finalized && !formalIssued) throw new Error("cannot finalize before formal JSONL bytes equal candidates");
  const target = {
    artifactVersion: "renderweave-spec-registry-target-manifest/1.0",
    targetId: TARGET_ID,
    status: finalized ? "ISSUED_EXACT_TARGET" : "PREISSUANCE_EXACT_TARGET",
    implementationRevision: IMPLEMENTATION_REVISION,
    executionClass: EXECUTION_CLASS,
    canonicalProfile: C14N_ID,
    probeProfile: PROFILE_ID,
    activeCorpusDigest: corpusDigest,
    artifacts,
    registryBindings: {
      candidateCases,
      candidateOracles,
      formalCases: { path: formalCases.path, expectedSha256: candidateCases.sha256, observedSha256: formalCases.sha256 },
      formalOracles: { path: formalOracles.path, expectedSha256: candidateOracles.sha256, observedSha256: formalOracles.sha256 },
      formalStatus: formalIssued ? "ISSUED_BYTE_IDENTICAL" : "PENDING_BYTE_IDENTICAL_ISSUANCE"
    },
    networkReadsAllowed: false,
    productMutationAllowed: false
  };
  const targetPath = finalized ? ".scratch/renderweave-template-v1/spec-registry/target-manifest-v1.json" : ".scratch/renderweave-template-v1/spec-registry/preissuance-target-manifest-v1.json";
  const targetArtifact = write(targetPath, pretty(target));

  const primaryScript = artifact(".scratch/renderweave-template-v1/spec-registry/validate-spec-registry-primary.mjs");
  const independentScript = artifact(".scratch/renderweave-template-v1/spec-registry/validate-spec-registry-independent.py");
  const executor = (role, executorId, runtime, entrypoint, command) => ({
    artifactVersion: "renderweave-spec-registry-executor-manifest/1.0",
    executorId,
    role,
    executionClass: EXECUTION_CLASS,
    targetId: TARGET_ID,
    targetManifest: targetArtifact,
    implementationRevision: IMPLEMENTATION_REVISION,
    runtime,
    entrypoint,
    command,
    sharedSemanticLibrary: null,
    networkReadsAllowed: false,
    productMutationAllowed: false
  });
  const suffix = finalized ? "" : "preissuance-";
  const primaryExecutor = write(`.scratch/renderweave-template-v1/spec-registry/${suffix}primary-executor-manifest-v1.json`, pretty(executor(
    "primary-registry-validator",
    "SPEC_EXECUTOR::NODE::1.0",
    "Node.js 24.x",
    primaryScript,
    `node ${primaryScript.path} --target ${targetArtifact.path}`
  )));
  const independentExecutor = write(`.scratch/renderweave-template-v1/spec-registry/${suffix}independent-executor-manifest-v1.json`, pretty(executor(
    "independent-schema-and-graph-replayer",
    "SPEC_EXECUTOR::PYTHON::1.0",
    "CPython 3.12.x",
    independentScript,
    `python ${independentScript.path} --target ${targetArtifact.path}`
  )));
  process.stdout.write(`${pretty({
    status: finalized ? "FINAL_RECORD_ARTIFACTS_GENERATED" : "PREISSUANCE_RECORD_ARTIFACTS_GENERATED",
    assignedRequirementCount: assigned.length,
    caseCount: cases.length,
    oracleCount: oracles.length,
    candidateCases,
    candidateOracles,
    coverageArtifact,
    corpusDigest,
    targetArtifact,
    primaryExecutor,
    independentExecutor
  })}`);
}

if (mode === "bootstrap") bootstrap();
else if (mode === "records") records(false);
else if (mode === "finalize") records(true);
else throw new Error(`usage: node ${rel(fileURLToPath(import.meta.url))} <bootstrap|records|finalize>`);
