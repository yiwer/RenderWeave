import { createHash } from "node:crypto";
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const implementationRevision = "spec-registry-bootstrap/1.13";

function bytes(relativePath) {
  return readFileSync(resolve(root, relativePath));
}

function json(relativePath) {
  return JSON.parse(bytes(relativePath).toString("utf8"));
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function artifact(relativePath) {
  const content = bytes(relativePath);
  return {
    path: relativePath,
    sha256: `sha256:${sha256(content)}`,
    byteLength: content.length,
  };
}

function writeJson(relativePath, value) {
  writeFileSync(resolve(root, relativePath), `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function utf8Compare(left, right) {
  return Buffer.compare(Buffer.from(left, "utf8"), Buffer.from(right, "utf8"));
}

const targetPath = "spec-registry/target-manifest-v1.json";
const target = json(targetPath);
const snapshotPolicy = json("conformance-manifest-snapshot-policy-v1.json");
const requiredPaths = new Set(target.artifacts.map((entry) => entry.path));
requiredPaths.add("conformance-manifest-snapshot-policy-v1.json");
requiredPaths.add("spec-registry/refresh-spec-registry-postissuance-target.mjs");
for (const editorAdmissionPath of [
  "editor-automated/execution-admission-contract-v1.json",
  "editor-automated/non-capacity-assignment-v1.json",
  "editor-automated/repository-readiness-audit-v1.json",
  "editor-automated/admission-primary-result-v1.json",
  "editor-automated/admission-independent-result-v1.json",
  "editor-automated/editor-automation-admission-static-a2-2026-08-17.json",
  "editor-automated/generate-editor-automation-admission.mjs",
  "editor-automated/validate_editor_automation_admission_independent.py",
  "editor-automated/write-editor-automation-admission-evidence.mjs"
]) {
  requiredPaths.add(editorAdmissionPath);
}
for (const editorCandidatePath of [
  "editor-automated/atomic-candidate-contract-v1.json",
  "editor-automated/atomic-scenario-candidates-v1.json",
  "editor-automated/atomic-candidate-readiness-audit-v1.json",
  "editor-automated/atomic-candidate-primary-result-v1.json",
  "editor-automated/atomic-candidate-independent-result-v1.json",
  "editor-automated/editor-atomic-candidates-static-a2-2026-08-17.json",
  "editor-automated/terminal-adjudication-v1.json",
  "editor-automated/fault-schedule-contract-v1.json",
  "editor-automated/fault-schedule-catalog-v1.json",
  "editor-automated/input-fixture-contract-v1.json",
  "editor-automated/input-fixture-catalog-v1.json",
  "editor-automated/semantic-projection-contract-v1.json",
  "editor-automated/semantic-projection-catalog-v1.json",
  "editor-automated/content-source-contract-v1.json",
  "editor-automated/content-source-catalog-v1.json",
  "editor-automated/target-binding-contract-v1.json",
  "editor-automated/target-binding-catalog-v1.json",
  "editor-automated/generate-editor-atomic-candidates.mjs",
  "editor-automated/validate_editor_atomic_candidates_independent.py",
  "editor-automated/write-editor-atomic-candidate-evidence.mjs"
]) {
  requiredPaths.add(editorCandidatePath);
}
for (const faultArtifactName of readdirSync(resolve(root, "editor-automated/fault-schedules"))) {
  if (faultArtifactName.endsWith(".json")) requiredPaths.add(`editor-automated/fault-schedules/${faultArtifactName}`);
}
for (const inputFixtureName of readdirSync(resolve(root, "editor-automated/input-fixtures"))) {
  if (inputFixtureName.endsWith(".json")) requiredPaths.add(`editor-automated/input-fixtures/${inputFixtureName}`);
}
for (const targetArtifactName of readdirSync(resolve(root, "editor-automated/target-artifacts"))) {
  if (targetArtifactName.endsWith(".json")) requiredPaths.add(`editor-automated/target-artifacts/${targetArtifactName}`);
}
for (const contentSourceName of readdirSync(resolve(root, "editor-automated/content-sources"))) {
  if (contentSourceName.endsWith(".json")) requiredPaths.add(`editor-automated/content-sources/${contentSourceName}`);
}
for (const editorProbeCandidatePath of [
  "editor-automated/probe-profile-adjudication-v1.json",
  "editor-automated/probe-profile-candidate-v1_1.json",
  "editor-automated/probe-assertion-vectors-candidate-v1_1.json",
  "editor-automated/observation-adapter-candidate-v1_1.json",
  "editor-automated/probe-profile-candidate-readiness-audit-v1_1.json",
  "editor-automated/probe-profile-candidate-primary-result-v1_1.json",
  "editor-automated/probe-profile-candidate-independent-result-v1_1.json",
  "editor-automated/editor-probe-profile-candidate-static-a2-2026-08-17.json",
  "editor-automated/generate-editor-probe-profile-candidate.mjs",
  "editor-automated/validate_editor_probe_profile_candidate_independent.py",
  "editor-automated/write-editor-probe-profile-candidate-evidence.mjs"
]) {
  requiredPaths.add(editorProbeCandidatePath);
}
for (const snapshot of snapshotPolicy.requiredSeedSnapshots) {
  requiredPaths.add(snapshot.snapshotPath);
}

target.implementationRevision = implementationRevision;
target.artifacts = [...requiredPaths]
  .sort(utf8Compare)
  .map(artifact);
writeJson(targetPath, target);

const targetArtifact = artifact(targetPath);
const primary = {
  artifactVersion: "renderweave-spec-registry-executor-manifest/1.0",
  executorId: "SPEC_EXECUTOR::NODE::1.0",
  role: "primary-registry-validator",
  executionClass: "EXEC::SPEC_REGISTRY::1.0",
  targetId: target.targetId,
  targetManifest: targetArtifact,
  implementationRevision,
  runtime: "Node.js 24.x",
  entrypoint: artifact("spec-registry/validate-spec-registry-primary.mjs"),
  command: "node spec-registry/validate-spec-registry-primary.mjs --target spec-registry/target-manifest-v1.json",
  sharedSemanticLibrary: null,
  networkReadsAllowed: false,
  productMutationAllowed: false,
};
const independent = {
  artifactVersion: "renderweave-spec-registry-executor-manifest/1.0",
  executorId: "SPEC_EXECUTOR::PYTHON::1.0",
  role: "independent-schema-and-graph-replayer",
  executionClass: "EXEC::SPEC_REGISTRY::1.0",
  targetId: target.targetId,
  targetManifest: targetArtifact,
  implementationRevision,
  runtime: "CPython 3.12.x",
  entrypoint: artifact("spec-registry/validate-spec-registry-independent.py"),
  command: "python spec-registry/validate-spec-registry-independent.py --target spec-registry/target-manifest-v1.json",
  sharedSemanticLibrary: null,
  networkReadsAllowed: false,
  productMutationAllowed: false,
};
writeJson("spec-registry/primary-executor-manifest-v1.json", primary);
writeJson("spec-registry/independent-executor-manifest-v1.json", independent);

process.stdout.write(`${JSON.stringify({
  status: "REFRESHED",
  implementationRevision,
  targetManifest: artifact(targetPath),
  primaryExecutorManifest: artifact("spec-registry/primary-executor-manifest-v1.json"),
  independentExecutorManifest: artifact("spec-registry/independent-executor-manifest-v1.json"),
})}\n`);
