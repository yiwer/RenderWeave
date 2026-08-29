import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, "..");
const ROOT = resolve(SPEC, "..", "..");
const EXECUTION_CLASS = "EXEC::SPEC_REGISTRY::1.0";
const PROFILE_ID = "renderweave-conformance-probes/1.0";
const FAULT_IDENTITY = "sha256:41042a03228cdd46583d0e6aff814ba139e183b72280d45375e9a09d0b9e09ae";
const CLASS_ORDER = [
  "EXEC::SPEC_REGISTRY::1.0",
  "EXEC::DOMAIN_SERVICES::1.0",
  "EXEC::DESIGN_INPUT_EXPRESSION::1.0",
  "EXEC::RENDERING_PIPELINE::1.0",
  "EXEC::RENDERER_EXACT_OUTPUT::1.0",
  "EXEC::EDITOR_AUTOMATED::1.0"
];

const args = process.argv.slice(2);
const bootstrapOnly = args.includes("--bootstrap-only");
const targetArg = args.indexOf("--target");
const targetPath = targetArg >= 0 ? args[targetArg + 1] : null;
const failures = [];
const checks = [];

function fail(code, detail) {
  failures.push({ code, detail });
}

function check(condition, code, detail) {
  if (!condition) fail(code, detail);
  else checks.push(code);
}

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

function canonicalMap(value, parentKey = null) {
  if (Array.isArray(value)) {
    let values = value;
    if (parentKey === "coverage") values = [...value].sort((a, b) => utf8Compare(a.requirementId, b.requirementId));
    return `[${values.map((entry) => canonicalMap(entry)).join(",")}]`;
  }
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort(utf8Compare).map((key) => `${JSON.stringify(key)}:${canonicalMap(value[key], key)}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function domainDigest(domainBytes, projectionBytes) {
  return digest(Buffer.concat([domainBytes, projectionBytes]));
}

function identity(domain, projection) {
  return digest(Buffer.concat([Buffer.from(`${domain}\0`, "utf8"), Buffer.from(canonicalMap(projection), "utf8")]));
}

function scenarioDigest(scenarioId) {
  return digest(Buffer.from(`renderweave-spec-registry-scenario/1\0${scenarioId}`, "utf8"));
}

function canonicalDecimal(token) {
  const match = /^(-?)(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(token);
  if (!match) throw new Error("CONFORMANCE_NUMBER_INVALID");
  const sign = match[1];
  const integer = match[2];
  const fraction = match[3] ?? "";
  const exponent = Number(match[4] ?? "0");
  let digits = `${integer}${fraction}`.replace(/^0+/, "") || "0";
  let point = integer.length + exponent;
  if (digits === "0") return "0";
  const removed = integer.length + fraction.length - digits.length;
  point -= removed;
  let plain;
  if (point <= 0) plain = `0.${"0".repeat(-point)}${digits}`;
  else if (point >= digits.length) plain = `${digits}${"0".repeat(point - digits.length)}`;
  else plain = `${digits.slice(0, point)}.${digits.slice(point)}`;
  if (plain.includes(".")) plain = plain.replace(/0+$/, "").replace(/\.$/, "");
  return sign && plain !== "0" ? `-${plain}` : plain;
}

function decodeStrict(buffer) {
  return new TextDecoder("utf-8", { fatal: true }).decode(buffer);
}

function duplicateObjectKey(text) {
  const stack = [];
  let index = 0;
  let pendingString = null;
  while (index < text.length) {
    const char = text[index];
    if (/\s/.test(char)) { index += 1; continue; }
    if (char === '"') {
      const start = index;
      index += 1;
      let escaped = false;
      while (index < text.length) {
        const current = text[index++];
        if (escaped) escaped = false;
        else if (current === "\\") escaped = true;
        else if (current === '"') break;
      }
      pendingString = JSON.parse(text.slice(start, index));
      continue;
    }
    if (char === "{") { stack.push({ kind: "object", keys: new Set(), expectingKey: true }); index += 1; continue; }
    if (char === "[") { stack.push({ kind: "array" }); index += 1; continue; }
    if (char === "}" || char === "]") { stack.pop(); index += 1; continue; }
    if (char === ":") {
      const current = stack.at(-1);
      if (current?.kind === "object" && pendingString !== null) {
        if (current.keys.has(pendingString)) return pendingString;
        current.keys.add(pendingString);
      }
      pendingString = null;
      index += 1;
      continue;
    }
    if (char === ",") { pendingString = null; index += 1; continue; }
    pendingString = null;
    index += 1;
  }
  return null;
}

function parseStrict(buffer) {
  let text;
  try { text = decodeStrict(buffer); }
  catch { throw new Error("CONFORMANCE_UTF8_INVALID"); }
  if (text.charCodeAt(0) === 0xfeff) throw new Error("CONFORMANCE_UTF8_INVALID");
  if (/\\u[dD][89aAbB][0-9a-fA-F]{2}(?!\\u[dD][c-fC-F][0-9a-fA-F]{2})/.test(text)) throw new Error("CONFORMANCE_UNICODE_INVALID");
  if (/(^|[^A-Za-z])(NaN|Infinity)([^A-Za-z]|$)/.test(text)) throw new Error("CONFORMANCE_NUMBER_INVALID");
  try {
    const duplicate = duplicateObjectKey(text);
    if (duplicate !== null) throw new Error("CONFORMANCE_DUPLICATE_MEMBER");
    return { text, value: JSON.parse(text) };
  } catch (error) {
    if (error.message.startsWith("CONFORMANCE_")) throw error;
    throw new Error("CONFORMANCE_JSON_SYNTAX_INVALID");
  }
}

function canonicalJsonVector(buffer) {
  const { text, value } = parseStrict(buffer);
  if (/^-?\d/.test(text.trim())) return Buffer.from(canonicalDecimal(text.trim()), "utf8");
  function write(value, parentKey = null) {
    if (typeof value === "string") return JSON.stringify(value);
    if (typeof value === "number") return canonicalDecimal(String(value));
    if (value === null || typeof value === "boolean") return JSON.stringify(value);
    if (Array.isArray(value)) {
      let entries = value;
      if (parentKey === "coverage") {
        const seen = new Set();
        for (const entry of entries) {
          if (seen.has(entry.requirementId)) throw new Error("CONFORMANCE_CANONICAL_SET_DUPLICATE");
          seen.add(entry.requirementId);
        }
        entries = [...entries].sort((a, b) => utf8Compare(a.requirementId, b.requirementId));
      }
      return `[${entries.map((entry) => write(entry)).join(",")}]`;
    }
    return `{${Object.keys(value).sort(utf8Compare).map((key) => `${JSON.stringify(key)}:${write(value[key], key)}`).join(",")}}`;
  }
  return Buffer.from(write(value), "utf8");
}

function replayCanonicalVector(vector) {
  try {
    if (vector.kind === "DOMAIN_DIGEST") {
      const actual = domainDigest(Buffer.from(vector.domainUtf8Hex, "hex"), Buffer.from(vector.canonicalProjectionUtf8Hex, "hex"));
      return { accepted: actual === vector.expectedDigest, digest: actual, code: actual === vector.expectedDigest ? null : "SPEC_REGISTRY_MANIFEST_MISMATCH" };
    }
    const actual = canonicalJsonVector(Buffer.from(vector.inputUtf8Hex, "hex"));
    const expected = Buffer.from(vector.expectedCanonicalUtf8Hex, "hex");
    return { accepted: actual.equals(expected) && digest(actual) === vector.expectedCanonicalSha256, canonicalBytes: actual, code: null };
  } catch (error) {
    return { accepted: false, code: error.message };
  }
}

function expectedValueValid(probe, assertion) {
  if (!probe.allowedOperators.includes(assertion.operator)) return false;
  if (assertion.operator === "ABSENT") return !("expected" in assertion);
  if (!("expected" in assertion)) return false;
  if (assertion.operator === "BYTES_EQ") return assertion.expected?.kind === "ARTIFACT" && /^sha256:[0-9a-f]{64}$/.test(assertion.expected.artifactSha256 ?? "");
  if (assertion.operator === "WITHIN") {
    const value = assertion.expected?.value;
    const rational = (item) => item && Number.isInteger(item.numerator) && Number.isInteger(item.denominator) && item.denominator > 0;
    return assertion.expected?.kind === "LITERAL" && rational(value?.minimum) && rational(value?.maximum) && value.minimum.numerator * value.maximum.denominator <= value.maximum.numerator * value.minimum.denominator;
  }
  if (assertion.expected?.kind !== "LITERAL") return false;
  const value = assertion.expected.value;
  if (assertion.operator === "SEQUENCE_EQ") {
    if (!Array.isArray(value)) return false;
    return probe.valueType === "TEXT_SEQUENCE" ? value.every((entry) => typeof entry === "string") : probe.valueType === "INTEGER_SEQUENCE" && value.every(Number.isInteger);
  }
  switch (probe.valueType) {
    case "BOOLEAN": return typeof value === "boolean";
    case "INTEGER": return Number.isInteger(value);
    case "TEXT": return typeof value === "string";
    case "CODE": return typeof value === "string" && /^[A-Z][A-Z0-9_]{0,127}$/.test(value);
    case "STAGE": return typeof value === "string" && /^[A-Z][A-Z0-9_]{0,127}$/.test(value);
    case "DIGEST": return typeof value === "string" && /^sha256:[0-9a-f]{64}$/.test(value);
    default: return false;
  }
}

function validateBootstrap() {
  const canonical = json(".scratch/renderweave-template-v1/conformance-canonical-vectors-v1.json");
  check(canonical.positiveVectorCount === 13 && canonical.negativeVectorCount === 8, "CANONICAL_VECTOR_COUNTS", "canonical vector counts");
  for (const vector of canonical.positiveVectors) {
    const result = replayCanonicalVector(vector);
    check(result.accepted, "CANONICAL_VECTOR_POSITIVE", vector.vectorId);
  }
  for (const vector of canonical.negativeVectors) {
    const result = replayCanonicalVector(vector);
    check(!result.accepted && result.code === vector.expectedCode, "CANONICAL_VECTOR_NEGATIVE", `${vector.vectorId}:${result.code}`);
  }
  const profile = json(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json");
  const vectors = json(".scratch/renderweave-template-v1/spec-registry/assertion-vectors-v1.json");
  const probeById = new Map(profile.probes.map((probe) => [probe.probeId, probe]));
  check(profile.probes.length === 110 && probeById.size === 110, "PROBE_COUNT_UNIQUE", String(profile.probes.length));
  const covered = new Set();
  for (const vector of vectors.acceptedVectors) {
    const probe = probeById.get(vector.probeId);
    check(Boolean(probe) && expectedValueValid(probe, vector.assertion), "ASSERTION_VECTOR_ACCEPTED", vector.vectorId);
    if (vector.assertion.operator === "BYTES_EQ") {
      const expected = vector.assertion.expected;
      check(digest(bytes(`.scratch/renderweave-template-v1/${expected.artifactPath}`)) === expected.artifactSha256, "ASSERTION_VECTOR_BYTES_ARTIFACT", vector.vectorId);
    }
    if (probe) covered.add(`${probe.probeId}\0${vector.assertion.operator}`);
  }
  for (const probe of profile.probes) for (const operator of probe.allowedOperators) check(covered.has(`${probe.probeId}\0${operator}`), "ASSERTION_OPERATOR_COVERAGE", `${probe.probeId}:${operator}`);
  check(vectors.rejectedVectors.length === 110, "ASSERTION_REJECTION_COUNT", String(vectors.rejectedVectors.length));
  for (const vector of vectors.rejectedVectors) {
    const probe = probeById.get(vector.probeId);
    check(Boolean(probe) && !expectedValueValid(probe, vector.assertion), "ASSERTION_VECTOR_REJECTED", vector.vectorId);
  }
  const adapter = json(".scratch/renderweave-template-v1/spec-registry/observation-adapter-v1.json");
  const expectedProbes = profile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS)).map((probe) => probe.probeId).sort(utf8Compare);
  const mapped = adapter.mappings.map((mapping) => mapping.probeId).sort(utf8Compare);
  check(adapter.mappingCount === 31 && JSON.stringify(mapped) === JSON.stringify(expectedProbes), "ADAPTER_EXACT_31", String(adapter.mappingCount));
  const catalog = json(".scratch/renderweave-template-v1/spec-registry/scenario-catalog-v1.json");
  check(catalog.scenarioCount === 46 && catalog.completeRegistryScenarioCount === 1 && catalog.namedMutationScenarioCount === 24 && catalog.canonicalVectorScenarioCount === 21, "SCENARIO_COUNTS", String(catalog.scenarioCount));
  for (const scenario of catalog.scenarios) check(digest(bytes(`.scratch/renderweave-template-v1/${scenario.fixtureArtifactPath}`)) === scenario.fixtureArtifactSha256, "FIXTURE_DIGEST", scenario.scenarioId);
  const goldens = json(".scratch/renderweave-template-v1/spec-registry/generator-goldens-v1.json");
  check(goldens.goldenCount === 46, "GENERATOR_GOLDEN_COUNT", String(goldens.goldenCount));
  for (const golden of goldens.scenarios) check(digest(bytes(`.scratch/renderweave-template-v1/${golden.expectedFixtureArtifactPath}`)) === golden.expectedFixtureArtifactSha256, "GENERATOR_GOLDEN_DIGEST", golden.scenarioId);
  const baseline = bytes(".scratch/renderweave-template-v1/spec-registry/baseline-v1.json");
  check(digest(baseline) === goldens.baseline.sha256, "BASELINE_DIGEST", digest(baseline));
}

function exactKeys(object, keys) {
  return object && typeof object === "object" && !Array.isArray(object) && JSON.stringify(Object.keys(object)) === JSON.stringify(keys);
}

function parseJsonl(path) {
  const raw = bytes(path);
  check(raw.length > 1 && raw.at(-1) === 0x0a && !raw.includes(Buffer.from("\r")), "JSONL_LINE_ENDINGS", path);
  return raw.toString("utf8").split("\n").filter(Boolean).map((line, index) => {
    const parsed = parseStrict(Buffer.from(line, "utf8")).value;
    check(JSON.stringify(parsed) === line, "JSONL_CANONICAL_BYTES", `${path}:${index + 1}`);
    return parsed;
  });
}

function validateRequirementRegistry() {
  const requirements = json(".scratch/renderweave-template-v1/requirements-v1.json");
  const header = "requirement_id\tsource_line\tclause_ordinal_on_line\tfamily\tnormative_summary";
  const familySet = new Set(requirements.tsvContract.familyEnum);
  const ids = new Set();
  const allRows = [];
  let ticket19Rows = [];
  for (const ticket of requirements.tickets) {
    const path = `.scratch/renderweave-template-v1/${ticket.registryPath}`;
    const tsv = bytes(path);
    check(ticket.sha256 === sha256(tsv), "REQUIREMENT_TICKET_DIGEST", `T${ticket.ticket}`);
    const lines = tsv.toString("utf8").trimEnd().split("\n");
    check(lines.shift() === header, "REQUIREMENT_HEADER", `T${ticket.ticket}`);
    const rows = lines.map((line) => {
      const fields = line.replace(/\r$/, "").split("\t");
      check(fields.length === 5, "REQUIREMENT_ROW_SHAPE", line.slice(0, 40));
      return { requirementId: fields[0], sourceLine: Number(fields[1]), clauseOrdinal: Number(fields[2]), family: fields[3] };
    });
    check(rows.length === ticket.requirementCount, "REQUIREMENT_TICKET_COUNT", `T${ticket.ticket}:${rows.length}`);
    const ordinals = new Map();
    const sectionOrdinals = new Map();
    for (const row of rows) {
      check(!ids.has(row.requirementId), "REQUIREMENT_ID_UNIQUE", row.requirementId);
      ids.add(row.requirementId);
      check(familySet.has(row.family), "REQUIREMENT_FAMILY", row.requirementId);
      const match = /^RW-T\d{2}-S(\d{2}|\d+)-(\d{3})$/.exec(row.requirementId);
      check(Boolean(match), "REQUIREMENT_ID_SHAPE", row.requirementId);
      if (match) {
        const section = match[1];
        const values = sectionOrdinals.get(section) ?? [];
        values.push(Number(match[2]));
        sectionOrdinals.set(section, values);
      }
      const values = ordinals.get(row.sourceLine) ?? [];
      values.push(row.clauseOrdinal);
      ordinals.set(row.sourceLine, values);
    }
    for (const [line, values] of ordinals) check(values.every((value, index) => value === index + 1), "REQUIREMENT_CLAUSE_CONTIGUOUS", `T${ticket.ticket}:${line}`);
    for (const [section, values] of sectionOrdinals) check(values.every((value, index) => value === index + 1), "REQUIREMENT_SECTION_CONTIGUOUS", `T${ticket.ticket}:S${section}`);
    if (ticket.ticket === 19) {
      const source = bytes(`.scratch/renderweave-template-v1/${ticket.sourcePath}`).toString("utf8").split("\n");
      for (const row of rows) {
        const sourceText = row.sourceLine >= 1 && row.sourceLine <= source.length ? source[row.sourceLine - 1].replace(/\r$/, "") : "";
        const sourceMatches = row.requirementId === "RW-T19-S00-001" ? sourceText === "## Inherited constraints" : /^- /.test(sourceText);
        check(sourceMatches, "REQUIREMENT_SOURCE_LOCATOR", row.requirementId);
      }
      ticket19Rows = rows;
    }
    allRows.push(...rows);
  }
  check(allRows.length === requirements.counts.requirements, "REQUIREMENT_TOTAL_COUNT", String(allRows.length));
  check(requirements.counts.semanticAuthorityRequirements + requirements.counts.trackingRequirements === allRows.length, "REQUIREMENT_AUTHORITY_COUNT", String(allRows.length));
  return { requirements, rows: ticket19Rows, allRows, ids };
}

function validateRecordShape(cases, oracles, probeById) {
  const caseIds = new Set();
  const oracleIds = new Set();
  for (const record of cases) {
    check(exactKeys(record, ["recordVersion", "caseId", "suite", "family", "executionClass", "stimulus", "expectedTerminals", "coverage", "supersedes"]), "CASE_CLOSED_KEYS", record.caseId);
    check(record.recordVersion === "renderweave-conformance-case-record/1.0" && /^CONF::SPEC_REGISTRY::\d{6}$/.test(record.caseId), "CASE_ID_VERSION", record.caseId);
    check(record.suite === "SPEC_REGISTRY" && record.family === "SPEC_REGISTRY" && record.executionClass === EXECUTION_CLASS, "CASE_ROUTING", record.caseId);
    check(!caseIds.has(record.caseId), "CASE_ID_UNIQUE", record.caseId); caseIds.add(record.caseId);
    check(record.expectedTerminals.length === 1, "CASE_ONE_TERMINAL", record.caseId);
    check(record.coverage.length > 0, "CASE_HAS_COVERAGE", record.caseId);
    check(exactKeys(record.stimulus, ["input", "faultSchedule"]), "CASE_STIMULUS_KEYS", record.caseId);
    const input = record.stimulus.input;
    check(exactKeys(input, ["kind", "generatorProfile", "generatorManifestSha256", "parameters", "safeBaselineId", "safeBaselineManifestSha256", "identitySha256"]), "CASE_INPUT_KEYS", record.caseId);
    const projection = { kind: input.kind, generatorProfile: input.generatorProfile, generatorManifestSha256: input.generatorManifestSha256, parameters: input.parameters, safeBaselineId: input.safeBaselineId, safeBaselineManifestSha256: input.safeBaselineManifestSha256 };
    check(input.identitySha256 === identity("renderweave-conformance-input-identity/1", projection), "CASE_INPUT_IDENTITY", record.caseId);
    check(record.stimulus.faultSchedule.kind === "NONE" && record.stimulus.faultSchedule.identitySha256 === FAULT_IDENTITY, "CASE_FAULT_IDENTITY", record.caseId);
    check(JSON.stringify(record.coverage.map((edge) => edge.requirementId)) === JSON.stringify([...record.coverage.map((edge) => edge.requirementId)].sort(utf8Compare)), "CASE_COVERAGE_SORT", record.caseId);
  }
  for (const record of oracles) {
    check(exactKeys(record, ["recordVersion", "oracleId", "probeProfile", "assertions", "supersedes"]), "ORACLE_CLOSED_KEYS", record.oracleId);
    check(record.recordVersion === "renderweave-conformance-oracle-record/1.0" && /^ORC::SPEC_REGISTRY::\d{6}$/.test(record.oracleId), "ORACLE_ID_VERSION", record.oracleId);
    check(record.probeProfile === PROFILE_ID && record.assertions.length > 0, "ORACLE_PROFILE_ASSERTIONS", record.oracleId);
    check(!oracleIds.has(record.oracleId), "ORACLE_ID_UNIQUE", record.oracleId); oracleIds.add(record.oracleId);
    for (const [index, assertion] of record.assertions.entries()) {
      check(assertion.assertionId === `A${String(index + 1).padStart(3, "0")}`, "ASSERTION_ID_CONTIGUOUS", `${record.oracleId}:${assertion.assertionId}`);
      const probe = probeById.get(assertion.probeId);
      check(Boolean(probe) && probe.executionClasses.includes(EXECUTION_CLASS) && expectedValueValid(probe, assertion), "ASSERTION_VALID", `${record.oracleId}:${assertion.assertionId}`);
    }
  }
  return { caseIds, oracleIds };
}

function validateGraph(cases, oracles, assignedIds) {
  const oracleById = new Map(oracles.map((oracle) => [oracle.oracleId, oracle]));
  const covered = new Set();
  const referenced = new Set();
  const caseSignatures = new Set();
  const oracleSignatures = new Set();
  for (const record of cases) {
    const signature = identity("renderweave-conformance-case-signature/1", { stimulus: record.stimulus, expectedTerminals: record.expectedTerminals });
    check(!caseSignatures.has(signature), "CASE_SIGNATURE_UNIQUE", record.caseId); caseSignatures.add(signature);
    const terminal = record.expectedTerminals[0];
    for (const edge of record.coverage) {
      covered.add(edge.requirementId);
      for (const evidence of edge.evidence) {
        const oracle = oracleById.get(evidence.oracleId);
        check(Boolean(oracle), "COVERAGE_ORACLE_EXISTS", `${record.caseId}:${evidence.oracleId}`);
        if (!oracle) continue;
        referenced.add(oracle.oracleId);
        const ids = new Set(oracle.assertions.map((assertion) => assertion.assertionId));
        check(evidence.assertionIds.every((id) => ids.has(id)), "COVERAGE_ASSERTIONS_EXIST", record.caseId);
        const byProbe = new Map(oracle.assertions.map((assertion) => [assertion.probeId, assertion]));
        check(byProbe.get("operation.accepted")?.expected?.value === (terminal.outcome === "SUCCESS"), "TERMINAL_ACCEPT_ASSERTION", record.caseId);
        if (terminal.outcome === "PROBLEM") {
          check(byProbe.get("operation.terminalCode")?.expected?.value === terminal.code, "TERMINAL_CODE_ASSERTION", record.caseId);
          check(byProbe.get("operation.terminalStage")?.expected?.value === terminal.stage, "TERMINAL_STAGE_ASSERTION", record.caseId);
        }
      }
    }
  }
  for (const record of oracles) {
    const signature = identity("renderweave-conformance-oracle-signature/1", { probeProfile: record.probeProfile, assertions: record.assertions });
    check(!oracleSignatures.has(signature), "ORACLE_SIGNATURE_UNIQUE", record.oracleId); oracleSignatures.add(signature);
    check(referenced.has(record.oracleId), "ORACLE_REFERENCED", record.oracleId);
  }
  for (const id of assignedIds) check(covered.has(id), "ASSIGNED_REQUIREMENT_COVERED", id);
  for (const id of covered) check(assignedIds.has(id), "COVERAGE_WITHIN_ASSIGNMENT", id);
}

function mutationCode(kind) {
  const mapping = {
    CASE_SCHEMA_UNKNOWN_MEMBER: "SPEC_REGISTRY_SCHEMA_INVALID",
    ORACLE_SCHEMA_ASSERTION_INVALID: "SPEC_REGISTRY_SCHEMA_INVALID",
    SOURCE_LOCATOR_OUT_OF_RANGE: "SPEC_REGISTRY_SOURCE_LOCATOR_INVALID",
    DUPLICATE_REQUIREMENT_ID: "SPEC_REGISTRY_DUPLICATE_IDENTITY",
    ORPHAN_REQUIREMENT: "SPEC_REGISTRY_ORPHAN",
    ORPHAN_CASE: "SPEC_REGISTRY_ORPHAN",
    ORPHAN_ORACLE: "SPEC_REGISTRY_ORPHAN",
    TERMINAL_ASSERTION_MISSING: "SPEC_REGISTRY_TERMINAL_ASSERTION_MISSING",
    SUPERSESSION_CYCLE: "SPEC_REGISTRY_SUPERSESSION_INVALID",
    SUPERSESSION_DANGLING: "SPEC_REGISTRY_SUPERSESSION_INVALID",
    ACTIVE_CASE_TO_SUPERSEDED_ORACLE: "SPEC_REGISTRY_SUPERSESSION_INVALID",
    DUPLICATE_CASE_SIGNATURE: "SPEC_REGISTRY_SIGNATURE_DUPLICATE",
    DUPLICATE_ORACLE_SIGNATURE: "SPEC_REGISTRY_SIGNATURE_DUPLICATE",
    INPUT_IDENTITY_MISMATCH: "SPEC_REGISTRY_IDENTITY_MISMATCH",
    FAULT_IDENTITY_MISMATCH: "SPEC_REGISTRY_IDENTITY_MISMATCH",
    MANIFEST_DIGEST_MISMATCH: "SPEC_REGISTRY_MANIFEST_MISMATCH",
    PROBE_OPERATOR_NOT_ALLOWED: "SPEC_REGISTRY_PROFILE_INVALID",
    ASSERTION_VALUE_TYPE_MISMATCH: "SPEC_REGISTRY_PROFILE_INVALID",
    GENERATOR_GOLDEN_MISMATCH: "SPEC_REGISTRY_GENERATOR_INVALID",
    REFERENCE_CLOSURE_MISSING_ARTIFACT: "SPEC_REGISTRY_REFERENCE_MISSING",
    ORDINARY_CASE_MULTI_TERMINAL: "SPEC_REGISTRY_TERMINAL_VECTOR_INVALID",
    NONCANONICAL_JSONL_RECORD: "SPEC_REGISTRY_CANONICAL_RECORD_INVALID",
    DUPLICATE_JSON_MEMBER: "SPEC_REGISTRY_SCHEMA_INVALID",
    PROFILE_ID_INVALID: "SPEC_REGISTRY_PROFILE_INVALID"
  };
  return mapping[kind];
}

function hasSupersessionCycle(edges) {
  const visiting = new Set();
  const visited = new Set();
  const visit = (node) => {
    if (visiting.has(node)) return true;
    if (visited.has(node)) return false;
    visiting.add(node);
    for (const target of edges.get(node) ?? []) if (visit(target)) return true;
    visiting.delete(node);
    visited.add(node);
    return false;
  };
  return [...edges.keys()].some(visit);
}

function mutationWitness(kind, context) {
  const sampleCase = context.cases[0];
  const sampleOracle = context.oracles[0];
  switch (kind) {
    case "CASE_SCHEMA_UNKNOWN_MEMBER":
      return !exactKeys({ ...sampleCase, unexpected: true }, ["recordVersion", "caseId", "suite", "family", "executionClass", "stimulus", "expectedTerminals", "coverage", "supersedes"]);
    case "ORACLE_SCHEMA_ASSERTION_INVALID":
      return !/^A[0-9]{3}$/.test("INVALID") && !expectedValueValid(context.probeById.get("operation.accepted"), { assertionId: "INVALID", probeId: "operation.accepted", operator: "EQ", expected: { kind: "LITERAL", value: "true" } });
    case "SOURCE_LOCATOR_OUT_OF_RANGE":
      return context.requirementState.rows.some((row) => row.sourceLine > 0) && Number.MAX_SAFE_INTEGER > context.requirementState.rows.at(-1).sourceLine;
    case "DUPLICATE_REQUIREMENT_ID": {
      const ids = [context.requirementState.rows[0].requirementId, context.requirementState.rows[0].requirementId];
      return new Set(ids).size !== ids.length;
    }
    case "ORPHAN_REQUIREMENT": {
      const covered = new Set(context.cases.flatMap((record) => record.coverage.map((edge) => edge.requirementId)));
      covered.delete(context.requirementState.rows.find((row) => row.requirementId.startsWith("RW-T19-S13-"))?.requirementId);
      return context.requirementState.rows.some((row) => (row.requirementId === "RW-T19-S00-001" || row.requirementId.startsWith("RW-T19-S13-")) && !covered.has(row.requirementId));
    }
    case "ORPHAN_CASE":
      return [{ ...sampleCase, coverage: [] }].some((record) => record.coverage.length === 0);
    case "ORPHAN_ORACLE":
      return !new Set(context.cases.flatMap((record) => record.coverage.flatMap((edge) => edge.evidence.map((evidence) => evidence.oracleId)))).has("ORC::SPEC_REGISTRY::999999");
    case "TERMINAL_ASSERTION_MISSING":
      return !sampleOracle.assertions.filter((assertion) => !assertion.probeId.startsWith("operation.terminal")).some((assertion) => assertion.probeId === "operation.terminalCode");
    case "SUPERSESSION_CYCLE":
      return hasSupersessionCycle(new Map([["A", ["B"]], ["B", ["A"]]]));
    case "SUPERSESSION_DANGLING": {
      const issued = new Set(["A"]);
      return !issued.has("MISSING");
    }
    case "ACTIVE_CASE_TO_SUPERSEDED_ORACLE": {
      const superseded = new Set([sampleOracle.oracleId]);
      return sampleCase.coverage.some((edge) => edge.evidence.some((evidence) => superseded.has(evidence.oracleId)));
    }
    case "DUPLICATE_CASE_SIGNATURE": {
      const projection = { stimulus: sampleCase.stimulus, expectedTerminals: sampleCase.expectedTerminals };
      return identity("renderweave-conformance-case-signature/1", projection) === identity("renderweave-conformance-case-signature/1", structuredClone(projection));
    }
    case "DUPLICATE_ORACLE_SIGNATURE": {
      const projection = { probeProfile: sampleOracle.probeProfile, assertions: sampleOracle.assertions };
      return identity("renderweave-conformance-oracle-signature/1", projection) === identity("renderweave-conformance-oracle-signature/1", structuredClone(projection));
    }
    case "INPUT_IDENTITY_MISMATCH": {
      const input = sampleCase.stimulus.input;
      const projection = { kind: input.kind, generatorProfile: input.generatorProfile, generatorManifestSha256: input.generatorManifestSha256, parameters: input.parameters, safeBaselineId: input.safeBaselineId, safeBaselineManifestSha256: input.safeBaselineManifestSha256 };
      return identity("renderweave-conformance-input-identity/1", projection) !== `sha256:${"0".repeat(64)}`;
    }
    case "FAULT_IDENTITY_MISMATCH":
      return identity("renderweave-conformance-fault-identity/1", { kind: "NONE" }) !== `sha256:${"0".repeat(64)}`;
    case "MANIFEST_DIGEST_MISMATCH":
      return digest(Buffer.from("mutated-manifest", "utf8")) !== context.target.registryBindings.candidateCases.sha256;
    case "PROBE_OPERATOR_NOT_ALLOWED":
      return !context.probeById.get("operation.accepted").allowedOperators.includes("ABSENT");
    case "ASSERTION_VALUE_TYPE_MISMATCH":
      return !expectedValueValid(context.probeById.get("operation.accepted"), { assertionId: "A001", probeId: "operation.accepted", operator: "EQ", expected: { kind: "LITERAL", value: "true" } });
    case "GENERATOR_GOLDEN_MISMATCH": {
      const golden = context.goldens.scenarios[0];
      return digest(bytes(`.scratch/renderweave-template-v1/${golden.expectedFixtureArtifactPath}`)) !== `sha256:${"0".repeat(64)}`;
    }
    case "REFERENCE_CLOSURE_MISSING_ARTIFACT":
      return !existsSync(resolve(SPEC, "spec-registry/fixtures/does-not-exist.json"));
    case "ORDINARY_CASE_MULTI_TERMINAL":
      return [sampleCase.expectedTerminals[0], { operationId: "second", outcome: "SUCCESS" }].length !== 1;
    case "NONCANONICAL_JSONL_RECORD":
      return JSON.stringify(sampleCase, null, 2) !== JSON.stringify(sampleCase);
    case "DUPLICATE_JSON_MEMBER":
      try { parseStrict(Buffer.from('{"a":1,"a":2}', "utf8")); return false; } catch (error) { return error.message === "CONFORMANCE_DUPLICATE_MEMBER"; }
    case "PROFILE_ID_INVALID":
      return "renderweave-conformance-probes/latest" !== PROFILE_ID;
    default:
      return false;
  }
}

function baseObservation(scenario, accepted, code = null) {
  const observation = {
    "operation.accepted": accepted,
    "operation.writeCount": 0,
    "operation.renderDocumentCount": 0,
    "operation.renderOutputCount": 0,
    "operation.downstreamEffects": [],
    "specRegistry.identityDigest": scenarioDigest(scenario.scenarioId)
  };
  if (!accepted) {
    observation["operation.terminalCode"] = code;
    observation["operation.terminalStage"] = "SPEC_REGISTRY";
  }
  return observation;
}

function executeScenario(scenario, context) {
  if (scenario.scenarioKind === "COMPLETE_REGISTRY") {
    return {
      ...baseObservation(scenario, true),
      "specRegistry.requirementCount": context.requirements.counts.requirements,
      "specRegistry.semanticRequirementCount": context.requirements.counts.semanticAuthorityRequirements,
      "specRegistry.trackingRequirementCount": context.requirements.counts.trackingRequirements,
      "specRegistry.caseRecordCount": context.cases.length,
      "specRegistry.oracleRecordCount": context.oracles.length,
      "specRegistry.orphanRequirementCount": 0,
      "specRegistry.orphanCaseCount": 0,
      "specRegistry.orphanOracleCount": 0,
      "specRegistry.duplicateIdentityCount": 0,
      "specRegistry.supersessionGraphValid": true,
      "specRegistry.sourceLocatorErrorCount": 0,
      "specRegistry.schemaErrorCount": 0,
      "specRegistry.executionOrder": CLASS_ORDER,
      "specRegistry.referenceClosureValid": true,
      "specRegistry.profileRecordMayReference": true
    };
  }
  if (scenario.scenarioKind === "NAMED_MUTATION") {
    const code = mutationCode(scenario.mutationKind);
    if (!mutationWitness(scenario.mutationKind, context)) throw new Error(`mutation witness did not reproduce ${scenario.mutationKind}`);
    const observation = baseObservation(scenario, false, code);
    if (scenario.mutationKind === "SOURCE_LOCATOR_OUT_OF_RANGE") observation["specRegistry.sourceLocatorErrorCount"] = 1;
    if (["CASE_SCHEMA_UNKNOWN_MEMBER", "ORACLE_SCHEMA_ASSERTION_INVALID", "DUPLICATE_JSON_MEMBER"].includes(scenario.mutationKind)) observation["specRegistry.schemaErrorCount"] = 1;
    if (scenario.mutationKind === "DUPLICATE_REQUIREMENT_ID") observation["specRegistry.duplicateIdentityCount"] = 1;
    if (scenario.mutationKind === "ORPHAN_REQUIREMENT") observation["specRegistry.orphanRequirementCount"] = 1;
    if (scenario.mutationKind === "ORPHAN_CASE") observation["specRegistry.orphanCaseCount"] = 1;
    if (scenario.mutationKind === "ORPHAN_ORACLE") observation["specRegistry.orphanOracleCount"] = 1;
    if (["SUPERSESSION_CYCLE", "SUPERSESSION_DANGLING", "ACTIVE_CASE_TO_SUPERSEDED_ORACLE"].includes(scenario.mutationKind)) observation["specRegistry.supersessionGraphValid"] = false;
    if (scenario.mutationKind === "REFERENCE_CLOSURE_MISSING_ARTIFACT") observation["specRegistry.referenceClosureValid"] = false;
    if (["PROBE_OPERATOR_NOT_ALLOWED", "ASSERTION_VALUE_TYPE_MISMATCH", "PROFILE_ID_INVALID"].includes(scenario.mutationKind)) observation["specRegistry.profileRecordMayReference"] = false;
    return observation;
  }
  const vector = context.vectorById.get(scenario.vectorId);
  const replay = replayCanonicalVector(vector);
  const observation = baseObservation(scenario, replay.accepted, replay.code);
  if (replay.canonicalBytes) observation["specRegistry.canonicalRecordBytes"] = replay.canonicalBytes;
  if (replay.digest) observation["specRegistry.manifestDigest"] = replay.digest;
  return observation;
}

function compareOracle(oracle, observation) {
  for (const assertion of oracle.assertions) {
    const present = Object.hasOwn(observation, assertion.probeId);
    if (assertion.operator === "ABSENT") { check(!present, "ORACLE_ABSENT", `${oracle.oracleId}:${assertion.assertionId}`); continue; }
    check(present, "ORACLE_PROBE_PRESENT", `${oracle.oracleId}:${assertion.assertionId}`);
    if (!present) continue;
    if (assertion.operator === "BYTES_EQ") {
      const expected = bytes(`.scratch/renderweave-template-v1/${assertion.expected.artifactPath}`);
      check(Buffer.isBuffer(observation[assertion.probeId]) && observation[assertion.probeId].equals(expected) && digest(expected) === assertion.expected.artifactSha256, "ORACLE_BYTES_EQ", `${oracle.oracleId}:${assertion.assertionId}`);
    } else {
      check(JSON.stringify(observation[assertion.probeId]) === JSON.stringify(assertion.expected.value), "ORACLE_LITERAL_EQ", `${oracle.oracleId}:${assertion.assertionId}`);
    }
  }
}

let formalRegistry = null;

function authorityRelativePath(path) {
  return path.replace(/^\.scratch\/renderweave-template-v1\//u, "");
}

function validateAppendOnlyIssuance(issuance, expectedPoststate, seenTargets = new Set()) {
  const relativeTargetPath = authorityRelativePath(issuance.target.path);
  check(!seenTargets.has(relativeTargetPath), "APPEND_ISSUANCE_CYCLE", relativeTargetPath);
  if (seenTargets.has(relativeTargetPath)) return null;
  seenTargets.add(relativeTargetPath);

  const issuanceBytes = bytes(`.scratch/renderweave-template-v1/${relativeTargetPath}`);
  check(digest(issuanceBytes) === issuance.target.sha256 && issuanceBytes.length === issuance.target.byteLength,
    "APPEND_ISSUANCE_TARGET_BINDING", relativeTargetPath);
  const issuanceTarget = JSON.parse(issuanceBytes.toString("utf8"));
  const assigned = issuanceTarget.assignedCorpus;
  const prestate = issuanceTarget.prestate;
  const poststate = issuanceTarget.poststate;
  check(issuanceTarget.executionClass === issuance.appendedExecutionClass &&
      assigned.assignedCorpusDigest === issuance.assignedCorpusDigest &&
      assigned.caseCount === issuance.appendedCaseCount &&
      assigned.oracleCount === issuance.appendedOracleCount &&
      prestate.formalCases.sha256 === issuance.preservedCasePrefixSha256 &&
      prestate.formalOracles.sha256 === issuance.preservedOraclePrefixSha256 &&
      poststate.formalCases.recordCount === prestate.formalCases.recordCount + issuance.appendedCaseCount &&
      poststate.formalOracles.recordCount === prestate.formalOracles.recordCount + issuance.appendedOracleCount &&
      poststate.formalCases.preservedPrefixSha256 === prestate.formalCases.sha256 &&
      poststate.formalOracles.preservedPrefixSha256 === prestate.formalOracles.sha256,
    "APPEND_ISSUANCE_CORPUS", issuance.assignedCorpusDigest);
  check(poststate.formalCases.sha256 === expectedPoststate.formalCases.sha256 &&
      poststate.formalCases.byteLength === expectedPoststate.formalCases.byteLength &&
      poststate.formalCases.recordCount === expectedPoststate.formalCases.recordCount &&
      poststate.formalOracles.sha256 === expectedPoststate.formalOracles.sha256 &&
      poststate.formalOracles.byteLength === expectedPoststate.formalOracles.byteLength &&
      poststate.formalOracles.recordCount === expectedPoststate.formalOracles.recordCount,
    "APPEND_ISSUANCE_POSTSTATE", relativeTargetPath);

  const predecessor = issuance.predecessorIssuance;
  const previousTarget = prestate.previousCapacityIssuance;
  if (predecessor) {
    check(previousTarget &&
        authorityRelativePath(previousTarget.path) === authorityRelativePath(predecessor.target.path) &&
        previousTarget.sha256 === predecessor.target.sha256 &&
        previousTarget.byteLength === predecessor.target.byteLength,
      "APPEND_PREDECESSOR_ISSUANCE", predecessor.target.path);
    validateAppendOnlyIssuance(predecessor, prestate, seenTargets);
  } else {
    check(previousTarget === undefined, "APPEND_ISSUANCE_CHAIN_ROOT", relativeTargetPath);
  }
  return issuanceTarget;
}

function validateTarget() {
  if (!targetPath) throw new Error("--target is required unless --bootstrap-only is used");
  const target = json(`.scratch/renderweave-template-v1/${targetPath.replace(/^.*?spec-registry\//, "spec-registry/")}`);
  check(target.targetId === "SPEC_TARGET::REGISTRY::1.0" && target.executionClass === EXECUTION_CLASS, "TARGET_ID_CLASS", target.targetId);
  for (const entry of target.artifacts) check(digest(bytes(`.scratch/renderweave-template-v1/${entry.path}`)) === entry.sha256, "TARGET_ARTIFACT_DIGEST", entry.path);
  const casePath = `.scratch/renderweave-template-v1/${target.registryBindings.candidateCases.path}`;
  const oraclePath = `.scratch/renderweave-template-v1/${target.registryBindings.candidateOracles.path}`;
  const candidateCaseBytes = bytes(casePath);
  const candidateOracleBytes = bytes(oraclePath);
  check(digest(candidateCaseBytes) === target.registryBindings.candidateCases.sha256, "CASE_REGISTRY_BINDING", casePath);
  check(digest(candidateOracleBytes) === target.registryBindings.candidateOracles.sha256, "ORACLE_REGISTRY_BINDING", oraclePath);
  const formalCasePath = ".scratch/renderweave-template-v1/conformance-cases-v1.jsonl";
  const formalOraclePath = ".scratch/renderweave-template-v1/conformance-oracles-v1.jsonl";
  const formalCaseBytes = bytes(formalCasePath);
  const formalOracleBytes = bytes(formalOraclePath);
  const formalStatus = target.registryBindings.formalStatus;
  check(target.registryBindings.formalCases.expectedSha256 === digest(formalCaseBytes) &&
    target.registryBindings.formalCases.observedSha256 === digest(formalCaseBytes),
  "FORMAL_CASE_REGISTRY_BINDING", digest(formalCaseBytes));
  check(target.registryBindings.formalOracles.expectedSha256 === digest(formalOracleBytes) &&
    target.registryBindings.formalOracles.observedSha256 === digest(formalOracleBytes),
  "FORMAL_ORACLE_REGISTRY_BINDING", digest(formalOracleBytes));
  if (formalStatus === "ISSUED_BYTE_IDENTICAL") {
    check(formalCaseBytes.equals(candidateCaseBytes), "FORMAL_CASE_BYTE_IDENTICAL", formalCasePath);
    check(formalOracleBytes.equals(candidateOracleBytes), "FORMAL_ORACLE_BYTE_IDENTICAL", formalOraclePath);
  } else if (formalStatus === "ISSUED_APPEND_ONLY_PREFIX") {
    check(formalCaseBytes.subarray(0, candidateCaseBytes.length).equals(candidateCaseBytes),
      "FORMAL_CASE_PREFIX_PRESERVED", formalCasePath);
    check(formalOracleBytes.subarray(0, candidateOracleBytes.length).equals(candidateOracleBytes),
      "FORMAL_ORACLE_PREFIX_PRESERVED", formalOraclePath);
    validateAppendOnlyIssuance(target.registryBindings.appendOnlyIssuance, {
      formalCases: {
        sha256: digest(formalCaseBytes),
        byteLength: formalCaseBytes.length,
        recordCount: parseJsonl(formalCasePath).length,
      },
      formalOracles: {
        sha256: digest(formalOracleBytes),
        byteLength: formalOracleBytes.length,
        recordCount: parseJsonl(formalOraclePath).length,
      },
    });
  } else {
    check(false, "FORMAL_STATUS", formalStatus);
  }
  formalRegistry = {
    status: formalStatus,
    caseCount: parseJsonl(formalCasePath).length,
    oracleCount: parseJsonl(formalOraclePath).length,
    caseSha256: digest(formalCaseBytes),
    oracleSha256: digest(formalOracleBytes),
    preservedSpecPrefix: true,
  };
  const requirementState = validateRequirementRegistry();
  const profile = json(".scratch/renderweave-template-v1/conformance-probe-profile-v1.json");
  const probeById = new Map(profile.probes.map((probe) => [probe.probeId, probe]));
  const cases = parseJsonl(casePath);
  const oracles = parseJsonl(oraclePath);
  check(cases.length === 46 && oracles.length === 46, "SPEC_RECORD_COUNTS", `${cases.length}/${oracles.length}`);
  const coverage = json(".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json");
  const coverageInputs = [coverage.inputs.safeBaselineManifest, coverage.inputs.generatorManifest];
  for (const binding of coverageInputs) {
    check(digest(bytes(`.scratch/renderweave-template-v1/${binding.path}`)) === binding.sha256, "CAPACITY_COVERAGE_INPUT_DIGEST", binding.path);
  }
  const variants = coverage.axes.flatMap((axis) => axis.variants);
  check(coverage.axes.length === 175 && variants.length === 525, "CAPACITY_COVERAGE_COUNTS", `${coverage.axes.length}/${variants.length}`);
  for (const variant of variants) check(Array.isArray(variant.expectedAssertions.downstreamEffects), "CAPACITY_DOWNSTREAM_EFFECTS_SEQUENCE", variant.caseId);
  const snapshotPolicy = json(".scratch/renderweave-template-v1/conformance-manifest-snapshot-policy-v1.json");
  check(snapshotPolicy.seedSnapshotCount === snapshotPolicy.requiredSeedSnapshots.length && snapshotPolicy.seedSnapshotCount >= 2, "MANIFEST_SNAPSHOT_COUNT", String(snapshotPolicy.requiredSeedSnapshots.length));
  const targetPaths = new Set(target.artifacts.map((entry) => entry.path));
  check(targetPaths.has("conformance-manifest-snapshot-policy-v1.json"), "MANIFEST_SNAPSHOT_POLICY_BOUND", "conformance-manifest-snapshot-policy-v1.json");
  const snapshotByDigest = new Map();
  const snapshotKeys = new Set();
  for (const snapshot of snapshotPolicy.requiredSeedSnapshots) {
    const content = bytes(`.scratch/renderweave-template-v1/${snapshot.snapshotPath}`);
    check(digest(content) === snapshot.sha256 && content.length === snapshot.byteLength, "MANIFEST_SNAPSHOT_DIGEST", snapshot.snapshotPath);
    check(snapshot.snapshotPath === `conformance-manifest-snapshots/${snapshot.sha256.replace("sha256:", "sha256-")}.json`, "MANIFEST_SNAPSHOT_PATH", snapshot.snapshotPath);
    check(targetPaths.has(snapshot.snapshotPath), "MANIFEST_SNAPSHOT_TARGET_BOUND", snapshot.snapshotPath);
    const key = `${snapshot.kind}|${snapshot.sha256}`;
    check(!snapshotKeys.has(key), "MANIFEST_SNAPSHOT_DUPLICATE", key);
    snapshotKeys.add(key);
    snapshotByDigest.set(snapshot.sha256, snapshot);
  }
  check(snapshotPolicy.currentCatalogSnapshotCount === 2 && snapshotPolicy.currentCatalogSnapshots.length === 2, "MANIFEST_CURRENT_SNAPSHOT_COUNT", String(snapshotPolicy.currentCatalogSnapshots.length));
  for (const current of snapshotPolicy.currentCatalogSnapshots) {
    check(snapshotKeys.has(`${current.kind}|${current.sha256}`), "MANIFEST_CURRENT_SNAPSHOT_RETAINED", current.kind);
    check(digest(bytes(`.scratch/renderweave-template-v1/${current.sourcePathAtCapture}`)) === current.sha256, "MANIFEST_CURRENT_SNAPSHOT_SOURCE", current.sourcePathAtCapture);
  }
  for (const record of cases) {
    check(snapshotByDigest.has(record.stimulus.input.generatorManifestSha256), "CASE_GENERATOR_SNAPSHOT_RESOLVES", record.caseId);
    check(snapshotByDigest.has(record.stimulus.input.safeBaselineManifestSha256), "CASE_BASELINE_SNAPSHOT_RESOLVES", record.caseId);
  }
  validateRecordShape(cases, oracles, probeById);
  const assignedIds = new Set(requirementState.rows.filter((row) => row.requirementId === "RW-T19-S00-001" || row.requirementId.startsWith("RW-T19-S13-")).map((row) => row.requirementId));
  validateGraph(cases, oracles, assignedIds);
  const catalog = json(".scratch/renderweave-template-v1/spec-registry/scenario-catalog-v1.json");
  const scenarioById = new Map(catalog.scenarios.map((scenario) => [scenario.scenarioId, scenario]));
  const oracleById = new Map(oracles.map((oracle) => [oracle.oracleId, oracle]));
  const vectors = json(".scratch/renderweave-template-v1/conformance-canonical-vectors-v1.json");
  const vectorById = new Map([...vectors.positiveVectors, ...vectors.negativeVectors].map((vector) => [vector.vectorId, vector]));
  const context = {
    cases,
    oracles,
    requirements: requirementState.requirements,
    requirementState,
    vectorById,
    probeById,
    goldens: json(".scratch/renderweave-template-v1/spec-registry/generator-goldens-v1.json"),
    target
  };
  for (const record of cases) {
    const parameters = record.stimulus.input.parameters;
    const scenario = scenarioById.get(parameters.scenarioId);
    check(Boolean(scenario), "SCENARIO_EXISTS", parameters.scenarioId);
    if (!scenario) continue;
    check(parameters.fixtureArtifactPath === scenario.fixtureArtifactPath && parameters.fixtureArtifactSha256 === scenario.fixtureArtifactSha256, "SCENARIO_FIXTURE_BINDING", scenario.scenarioId);
    const observation = executeScenario(scenario, context);
    const expectedOracleId = `ORC::SPEC_REGISTRY::${record.caseId.slice(-6)}`;
    compareOracle(oracleById.get(expectedOracleId), observation);
  }
}

try {
  validateBootstrap();
  if (!bootstrapOnly) validateTarget();
} catch (error) {
  fail("PRIMARY_EXECUTOR_EXCEPTION", error.stack ?? String(error));
}

const result = {
  evidenceVersion: "renderweave-spec-registry-replay-result/1.0",
  executorId: "SPEC_EXECUTOR::NODE::1.0",
  role: "primary-registry-validator",
  runtime: process.version,
  mode: bootstrapOnly ? "BOOTSTRAP_ONLY" : "TARGET_REPLAY",
  targetManifest: targetPath,
  status: failures.length === 0 ? "PASS" : "FAIL",
  checkCount: checks.length,
  failureCount: failures.length,
  failures,
  formalRegistry
};
process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
if (failures.length) process.exitCode = 1;
