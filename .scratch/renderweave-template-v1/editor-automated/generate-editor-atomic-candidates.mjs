import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const SPEC = resolve(import.meta.dirname, "..");
const ROOT = "editor-automated";
const EXECUTION_CLASS = "EXEC::EDITOR_AUTOMATED::1.0";
const CANDIDATE_PROFILE = "renderweave-editor-atomic-candidate/1.5";
const INPUT_PROFILE = "renderweave-editor-atomic-input-plan/1.1";
const FAULT_PROFILE = "renderweave-editor-atomic-fault-plan/1.1";
const FAULT_ARTIFACT_PROFILE = "renderweave-editor-fault-schedule/1.0";
const INPUT_FIXTURE_PROFILE = "renderweave-editor-input-fixture/1.0";
const TERMINAL_TARGET_PROFILE = "renderweave-editor-terminal-target/1.0";
const CONTRACT_PATH = `${ROOT}/atomic-candidate-contract-v1.json`;
const CANDIDATES_PATH = `${ROOT}/atomic-scenario-candidates-v1.json`;
const AUDIT_PATH = `${ROOT}/atomic-candidate-readiness-audit-v1.json`;
const PRIMARY_RESULT_PATH = `${ROOT}/atomic-candidate-primary-result-v1.json`;
const TERMINAL_ADJUDICATION_PATH = `${ROOT}/terminal-adjudication-v1.json`;
const FAULT_CONTRACT_PATH = `${ROOT}/fault-schedule-contract-v1.json`;
const FAULT_CATALOG_PATH = `${ROOT}/fault-schedule-catalog-v1.json`;
const FAULT_DIRECTORY = `${ROOT}/fault-schedules`;
const INPUT_FIXTURE_CONTRACT_PATH = `${ROOT}/input-fixture-contract-v1.json`;
const INPUT_FIXTURE_CATALOG_PATH = `${ROOT}/input-fixture-catalog-v1.json`;
const INPUT_FIXTURE_DIRECTORY = `${ROOT}/input-fixtures`;
const TARGET_BINDING_CONTRACT_PATH = `${ROOT}/target-binding-contract-v1.json`;
const TARGET_BINDING_CATALOG_PATH = `${ROOT}/target-binding-catalog-v1.json`;
const SEMANTIC_PROJECTION_CONTRACT_PATH = `${ROOT}/semantic-projection-contract-v1.json`;
const SEMANTIC_PROJECTION_CATALOG_PATH = `${ROOT}/semantic-projection-catalog-v1.json`;
const CONTENT_SOURCE_CONTRACT_PATH = `${ROOT}/content-source-contract-v1.json`;
const CONTENT_SOURCE_CATALOG_PATH = `${ROOT}/content-source-catalog-v1.json`;
const CONTENT_SOURCE_DIRECTORY = `${ROOT}/content-sources`;
const DIRTY_GUARD_CLEAN_DESIGN_PATH = `${CONTENT_SOURCE_DIRECTORY}/dirty-guard-clean-baseline.design.json`;
const DIRTY_GUARD_WORKING_DESIGN_PATH = `${CONTENT_SOURCE_DIRECTORY}/dirty-guard-working-copy.design.json`;
const DIRTY_GUARD_SOURCE_RECORD_PATH = `${CONTENT_SOURCE_DIRECTORY}/ecs-j10-012-pa011.json`;
const DIRTY_GUARD_CANDIDATE_ID = "EDC::J10::012";
const DIRTY_GUARD_PLAN_ASSERTION_ID = "PA011";
const DIRTY_GUARD_SOURCE_SLOT_ID = "ECS::J10::012::PA011";
const TARGET_ARTIFACT_DIRECTORY = `${ROOT}/target-artifacts`;
const CANDIDATE_PROBE_PROFILE_PATH = `${ROOT}/probe-profile-candidate-v1_1.json`;
const CANDIDATE_PROBE_ADJUDICATION_PATH = `${ROOT}/probe-profile-adjudication-v1.json`;
const CANDIDATE_PROBE_PROFILE_ID = "renderweave-conformance-probes/1.1";

const BLOCKERS = {
  fixture: "EXACT_PRODUCT_FIXTURE_ARTIFACT_MISSING",
  target: "EXACT_BROWSER_OS_TARGET_MISSING",
  runner: "EXECUTOR_MANIFEST_MISSING",
  replay: "INDEPENDENT_PRODUCT_REPLAY_MISSING",
  fault: "FAULT_SCHEDULE_ARTIFACT_MISSING",
  terminal: "EXPECTED_TERMINAL_CODE_OR_STAGE_UNBOUND",
  expected: "TARGET_LITERAL_OR_ARTIFACT_MISSING",
  probe: "EDITOR_PROBE_PROFILE_CANDIDATE_NOT_ISSUED"
};

function readJson(relativePath) {
  return JSON.parse(readFileSync(resolve(SPEC, relativePath), "utf8"));
}

function raw(relativePath) {
  return readFileSync(resolve(SPEC, relativePath));
}

function sha(value) {
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value, "utf8");
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stable(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function identity(domain, value) {
  return sha(`${domain}\0${stable(value)}`);
}

function encode(value) {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function write(relativePath, value) {
  const absolute = resolve(SPEC, relativePath);
  mkdirSync(dirname(absolute), { recursive: true });
  writeFileSync(absolute, encode(value));
}

function writeBytes(relativePath, bytes) {
  const absolute = resolve(SPEC, relativePath);
  mkdirSync(dirname(absolute), { recursive: true });
  writeFileSync(absolute, bytes);
}

function designCanonical(value) {
  if (value === null || value === undefined) throw new Error("DesignDSL canonical fixture forbids null or undefined");
  if (Array.isArray(value)) return `[${value.map(designCanonical).join(",")}]`;
  if (typeof value === "object") {
    const keys = Object.keys(value).sort((left, right) => Buffer.compare(Buffer.from(left, "utf8"), Buffer.from(right, "utf8")));
    return `{${keys.map((key) => `${JSON.stringify(key)}:${designCanonical(value[key])}`).join(",")}}`;
  }
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) throw new Error(`DesignDSL fixture number must be a safe integer: ${value}`);
    return String(value);
  }
  if (typeof value === "string" || typeof value === "boolean") return JSON.stringify(value);
  throw new Error(`unsupported DesignDSL fixture value: ${typeof value}`);
}

function designBytes(value) {
  return Buffer.from(designCanonical(value), "utf8");
}

function artifact(relativePath) {
  const bytes = raw(relativePath);
  return { path: relativePath, sha256: sha(bytes), byteLength: bytes.length };
}

function readJsonl(relativePath) {
  return readFileSync(resolve(SPEC, relativePath), "utf8")
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

function r(ticket, section, ordinal) {
  return `RW-T${String(ticket).padStart(2, "0")}-S${String(section).padStart(1, "0")}-${String(ordinal).padStart(3, "0")}`;
}

const NONE = Object.freeze({ kind: "NONE", events: [] });

function fault(...events) {
  return { kind: "PLANNED_SEQUENCE", events };
}

function ev(at, action, parameters = {}) {
  return { at, action, parameters };
}

function input(baselineId, parameters = {}) {
  return { baselineId, parameters };
}

function step(action, parameters = {}) {
  return { action, parameters };
}

function success(operationId) {
  return { operationId, outcome: "SUCCESS", codeBinding: { status: "NOT_REQUIRED" }, stageBinding: { status: "NOT_REQUIRED" } };
}

function problem(operationId, code = null, stage = null) {
  return {
    operationId,
    outcome: "PROBLEM",
    codeBinding: code ? { status: "EXACT", code } : { status: "PENDING_SPEC_BINDING" },
    stageBinding: stage ? { status: "EXACT", stage } : { status: "PENDING_SPEC_BINDING" }
  };
}

function rejection(operationId, code = null) {
  return {
    operationId,
    outcome: "NONTERMINAL_REJECTION",
    codeBinding: code ? { status: "EXACT", code } : { status: "PENDING_PRODUCT_CODE_BINDING" },
    stageBinding: { status: "NOT_REQUIRED" }
  };
}

function lit(probeId, operator, value) {
  return { probeBinding: { status: "CURRENT_PROFILE", probeId }, operator, expectation: { status: "EXACT_LITERAL", expected: { kind: "LITERAL", value } } };
}

function pending(probeId, operator, bindingKey, expectedKind = "LITERAL", plannedSemanticValue = undefined) {
  const expectation = { status: expectedKind === "ARTIFACT" ? "PENDING_TARGET_ARTIFACT" : "PENDING_TARGET_LITERAL", bindingKey, expectedKind };
  if (plannedSemanticValue !== undefined) expectation.plannedSemanticValue = plannedSemanticValue;
  return {
    probeBinding: { status: "CURRENT_PROFILE", probeId },
    operator,
    expectation
  };
}

function exactAbsent(probeId) {
  return { probeBinding: { status: "CURRENT_PROFILE", probeId }, operator: "ABSENT", expectation: { status: "EXACT_ABSENT" } };
}

function candidatePending(probeId, operator, bindingKey, expectedKind = "LITERAL", plannedSemanticValue = undefined) {
  const expectation = { status: expectedKind === "ARTIFACT" ? "PENDING_TARGET_ARTIFACT" : "PENDING_TARGET_LITERAL", bindingKey, expectedKind };
  if (plannedSemanticValue !== undefined) expectation.plannedSemanticValue = plannedSemanticValue;
  return {
    probeBinding: { status: "CANDIDATE_PROFILE_NOT_ISSUED", probeProfile: CANDIDATE_PROBE_PROFILE_ID, probeId },
    operator,
    expectation
  };
}

function candidateLit(probeId, operator, value) {
  return {
    probeBinding: { status: "CANDIDATE_PROFILE_NOT_ISSUED", probeProfile: CANDIDATE_PROBE_PROFILE_ID, probeId },
    operator,
    expectation: { status: "EXACT_LITERAL", expected: { kind: "LITERAL", value } }
  };
}

function candidateAbsent(probeId) {
  return {
    probeBinding: { status: "CANDIDATE_PROFILE_NOT_ISSUED", probeProfile: CANDIDATE_PROBE_PROFILE_ID, probeId },
    operator: "ABSENT",
    expectation: { status: "EXACT_ABSENT" }
  };
}

function scenario(seed, slug, title, profile, requirements, options = {}) {
  return {
    seed,
    slug,
    title,
    profile,
    requirements,
    input: options.input ?? input("EditorStructuredClean"),
    steps: options.steps ?? [step(slug)],
    fault: options.fault ?? NONE,
    terminals: options.terminals ?? [success(slug)],
    expected: options.expected ?? {},
    extraAssertions: options.extraAssertions ?? []
  };
}

const t18 = (s, ...ns) => ns.map((n) => r(18, s, n));
const t17 = (s, ...ns) => ns.map((n) => r(17, s, n));
const t04 = (s, ...ns) => ns.map((n) => r(4, s, n));

const TERMINAL_ADJUDICATIONS = Object.freeze({
  "delete-unsafe-rejected": { code: "EDITOR_STRUCTURE_COMMAND_REFERENCE_UNSAFE", stage: null },
  "array-reorder-overlap-rejected": { code: "EDITOR_ARRAY_REWRITE_OVERLAP", stage: null },
  "array-reorder-out-of-bounds-rejected": { code: "EDITOR_ARRAY_REWRITE_OUT_OF_BOUNDS", stage: null },
  "array-reorder-duplicate-target-rejected": { code: "EDITOR_ARRAY_REWRITE_DUPLICATE_TARGET", stage: null },
  "save-problem-submission-basis": { code: "DESIGN_PROPERTY_CONSTRAINT_INVALID", stage: "DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION" },
  "import-migration-problem-basis": { code: "DESIGN_DSL_VERSION_UNSUPPORTED", stage: "DESIGN_PARSE" },
  "preview-problem-basis": { code: "TEMPLATE_CLOSURE_UNSTABLE", stage: "TEMPLATE_CLOSURE" },
  "failure-focuses-summary": { code: "DESIGN_PROPERTY_CONSTRAINT_INVALID", stage: "DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION" },
  "dependency-set-offers-confirmation": { code: "TEMPLATE_DEPENDENCY_ERROR", stage: "DESIGN_DEPENDENCY_VALIDATION" },
  "truncated-set-response": { code: "PROBLEM_LIMIT_REACHED", stage: "BOUNDED_PROBLEM_COLLECTION" },
  "truncated-confirmation-attempt-rejected": { code: "EDITOR_INVALID_CONFIRMATION_UNAVAILABLE", stage: null },
  "unknown-version-hard-error-response": { code: "DESIGN_DSL_VERSION_UNSUPPORTED", stage: "DESIGN_PARSE" },
  "unknown-version-confirmation-rejected": { code: "EDITOR_INVALID_CONFIRMATION_UNAVAILABLE", stage: null },
  "unknown-member-kind-hard-error-response": { code: "DESIGN_DSL_MEMBER_OR_KIND_UNKNOWN", stage: "DESIGN_SEMANTIC_VALIDATION" },
  "unknown-member-kind-confirmation-rejected": { code: "EDITOR_INVALID_CONFIRMATION_UNAVAILABLE", stage: null },
  "structural-hard-error-response": { code: "DESIGN_DSL_STRUCTURE_INVALID", stage: "DESIGN_SEMANTIC_VALIDATION" },
  "structural-confirmation-rejected": { code: "EDITOR_INVALID_CONFIRMATION_UNAVAILABLE", stage: null },
  "template-cycle-hard-error-response": { code: "TEMPLATE_REF_CYCLE", stage: "DESIGN_SEMANTIC_VALIDATION" },
  "template-cycle-confirmation-rejected": { code: "EDITOR_INVALID_CONFIRMATION_UNAVAILABLE", stage: null },
  "security-hard-error-response": { code: "DESIGN_DSL_SECURITY_VIOLATION", stage: "DESIGN_SEMANTIC_VALIDATION" },
  "security-confirmation-rejected": { code: "EDITOR_INVALID_CONFIRMATION_UNAVAILABLE", stage: null },
  "capacity-hard-error-response": { code: "DESIGN_DSL_LIMIT_EXCEEDED", stage: "DESIGN_SEMANTIC_VALIDATION" },
  "capacity-confirmation-rejected": { code: "EDITOR_INVALID_CONFIRMATION_UNAVAILABLE", stage: null },
  "first-conflict-preserves-local": { code: "TEMPLATE_REVISION_CONFLICT", stage: "TEMPLATE_MUTATION" },
  "second-drift-requires-new-confirmation": { code: "TEMPLATE_REVISION_CONFLICT", stage: "TEMPLATE_MUTATION" },
  "transport-unknown-enters-reconciliation": { code: "TEMPLATE_SAVE_OUTCOME_UNKNOWN", stage: "EDITOR_SAVE_RECONCILIATION" },
  "reconcile-enters-conflict": { code: "TEMPLATE_REVISION_CONFLICT", stage: "EDITOR_SAVE_RECONCILIATION" },
  "reconcile-deleted-readonly": { code: "TEMPLATE_DELETED", stage: "EDITOR_SAVE_RECONCILIATION" },
  "reconcile-integrity-fails-closed": { code: "TEMPLATE_INTEGRITY_MISMATCH", stage: "EDITOR_SAVE_RECONCILIATION" },
  "reconcile-unreadable-remains-unknown": { code: "TEMPLATE_CURRENT_UNAVAILABLE", stage: "EDITOR_SAVE_RECONCILIATION" },
  "dirty-guard-blocks-replacement": { code: "EDITOR_DIRTY_DRAFT_REPLACEMENT_BLOCKED", stage: null },
  "save-and-preview-save-fails": { code: "DESIGN_PROPERTY_CONSTRAINT_INVALID", stage: "DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION" },
  "preview-failure-does-not-rollback-save": { code: "RENDER_DEADLINE_EXCEEDED", stage: "ENGINE" }
});

const EXISTING_SPEC_CODES = Object.freeze([
  "DESIGN_DSL_LIMIT_EXCEEDED",
  "DESIGN_PROPERTY_CONSTRAINT_INVALID",
  "PROBLEM_LIMIT_REACHED",
  "RENDER_DEADLINE_EXCEEDED",
  "TEMPLATE_CLOSURE_UNSTABLE"
]);

const EXISTING_SPEC_STAGES = Object.freeze([
  "BOUNDED_PROBLEM_COLLECTION",
  "DESIGN_PARSE",
  "DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION",
  "DESIGN_SEMANTIC_VALIDATION",
  "ENGINE",
  "TEMPLATE_CLOSURE"
]);

const definitions = [
  scenario(1, "keyboard-core-traversal", "Keyboard-only traversal across core workspaces", "KEYBOARD", [...t18(3, 32), ...t18(4, 52, 54, 57, 64)], {
    input: input("EditorStructuredClean", { viewportClass: "SUPPORTED_DESKTOP", entryFocus: "EDITOR_ROOT" }),
    steps: [step("TRAVERSE_CORE_WORKSPACES_BY_KEYBOARD", { order: ["STRUCTURE_TREE", "CANVAS_EQUIVALENT", "PROPERTY_GROUPS", "PROBLEM_SUMMARY", "PREVIEW_CONTROLS", "EDITOR_ROOT"] })]
  }),
  scenario(1, "tree-roving-canvas-equivalent", "Tree roving focus and Canvas-equivalent selection", "KEYBOARD", t18(4, 53, 55, 57, 64), {
    input: input("EditorStructuredWithSelectableCanvasEntity", { selectedEntityId: "node-focus-a" }),
    steps: [step("ROVE_TREE_AND_ENTER_CANVAS_EQUIVALENT", { keys: ["ARROW_DOWN", "ARROW_UP", "ENTER"] })]
  }),
  scenario(1, "escape-restores-trigger-focus", "Escape closes temporary UI and restores trigger focus", "KEYBOARD", t18(4, 47, 57, 64), {
    input: input("EditorStructuredWithOpenTemporaryPanel", { triggerLocator: "PROBLEM_SUMMARY_TRIGGER" }),
    steps: [step("PRESS_ESCAPE")]
  }),

  scenario(2, "create-entity-atomic", "Create generates all affected identities atomically", "LOCAL_MUTATION", t18(3, 18), {
    steps: [step("CREATE_ENTITY", { entityKind: "NODE", parentId: "node-container-a" })], expected: { undoDepth: 1 }
  }),
  scenario(2, "duplicate-entity-remaps", "Duplicate remaps all copied identities and references atomically", "LOCAL_MUTATION", t18(3, 18), {
    input: input("EditorStructuredWithRepeatTemplateUseBindings"), steps: [step("DUPLICATE_ENTITY", { entityId: "node-repeat-a" })], expected: { undoDepth: 1 }
  }),
  scenario(2, "delete-entity-atomic", "Delete rewrites safe references atomically", "LOCAL_MUTATION", t18(3, 18), {
    input: input("EditorStructuredWithSafelyDeletableEntity"), steps: [step("DELETE_ENTITY", { entityId: "node-leaf-a" })], expected: { undoDepth: 1 }
  }),
  scenario(2, "delete-unsafe-rejected", "Unsafe delete is wholly rejected", "LOCAL_REJECTION", t18(3, 19, 20), {
    input: input("EditorStructuredWithUnsafeDeleteReference"), steps: [step("DELETE_ENTITY", { entityId: "definition-required-a" })], terminals: [rejection("deleteEntity")]
  }),
  scenario(2, "move-entity-atomic", "Move preserves lexical and reference safety atomically", "LOCAL_MUTATION", t18(3, 18, 19, 20), {
    input: input("EditorStructuredWithMovableNode"), steps: [step("MOVE_ENTITY", { entityId: "node-move-a", beforeEntityId: "node-anchor-b" })], expected: { undoDepth: 1 }
  }),
  scenario(2, "reparent-entity-atomic", "Reparent converts placement and references atomically", "LOCAL_MUTATION", t18(3, 18, 19, 20), {
    input: input("EditorStructuredWithReparentableNode"), steps: [step("REPARENT_ENTITY", { entityId: "node-child-a", newParentId: "node-container-b" })], expected: { undoDepth: 1 }
  }),
  scenario(2, "reorder-entity-atomic", "Structural reorder applies as one command", "LOCAL_MUTATION", t18(3, 18, 19, 20), {
    input: input("EditorStructuredWithOrderedChildren"), steps: [step("REORDER_ENTITY", { entityId: "node-order-b", targetIndex: 0 })], expected: { undoDepth: 1 }
  }),
  scenario(2, "undo-complete-working-copy", "Undo restores a complete working copy through invalid state", "LOCAL_MUTATION", t18(3, 22, 23), {
    input: input("EditorStructuredWithOneInvalidatingCommand", { undoDepth: 1, redoDepth: 0 }), steps: [step("UNDO", { shortcut: "CTRL_OR_CMD_Z" })], expected: { undoDepth: 0, redoDepth: 1 }
  }),
  scenario(2, "redo-complete-working-copy", "Redo restores a complete working copy through invalid state", "LOCAL_MUTATION", [...t18(3, 22, 23), ...t18(4, 44)], {
    input: input("EditorStructuredAfterUndo", { undoDepth: 0, redoDepth: 1, operatingSystemFamily: "WINDOWS" }), steps: [step("REDO", { shortcuts: ["CTRL_SHIFT_Z", "CTRL_Y"] })], expected: { undoDepth: 1, redoDepth: 0 }
  }),
  scenario(2, "save-clears-local-history", "Successful save adopts canonical baseline and clears local history", "SAVE_SUCCESS", t18(3, 24, 25), {
    input: input("EditorStructuredDirtyWithUndoRedo", { undoDepth: 2, redoDepth: 1 }), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_CANONICAL_SUCCESS", { revisionDelta: 1 })), expected: { undoDepth: 0, redoDepth: 0, semanticDiffCount: 0 }
  }),
  scenario(2, "overwrite-clears-local-history", "Conflict overwrite adopts a canonical baseline and clears local history", "SAVE_SUCCESS", t18(3, 24, 25), {
    input: input("EditorStructuredConflictConfirmed", { undoDepth: 2, redoDepth: 0 }), steps: [step("RESUBMIT_COMPLETE_DESIGN_WITH_LATEST_REVISION")], fault: fault(ev("SAVE_RESPONSE", "RETURN_CANONICAL_SUCCESS", { revisionDelta: 2 })), expected: { undoDepth: 0, redoDepth: 0, semanticDiffCount: 0 }
  }),
  scenario(2, "revision-restore-clears-local-history", "Revision restore adopts a canonical baseline and clears local history", "SAVE_SUCCESS", t18(3, 24, 25), {
    input: input("EditorStructuredDirtyWithRestoreTarget", { undoDepth: 1, redoDepth: 1 }), steps: [step("RESTORE_TEMPLATE_REVISION", { sourceRevision: 2 })], fault: fault(ev("RESTORE_RESPONSE", "RETURN_CANONICAL_SUCCESS", { revisionDelta: 1 })), expected: { undoDepth: 0, redoDepth: 0, semanticDiffCount: 0 }
  }),
  scenario(2, "whole-template-copy-or-create-clears-history", "Whole-Template copy or create starts a new canonical baseline without old local history", "SAVE_SUCCESS", t18(3, 24, 25), {
    input: input("EditorStructuredDirtyCopySource", { undoDepth: 3, redoDepth: 0 }), steps: [step("CREATE_TEMPLATE_FROM_COMPLETE_DESIGN")], fault: fault(ev("CREATE_RESPONSE", "RETURN_CANONICAL_SUCCESS", { revision: 0 })), expected: { undoDepth: 0, redoDepth: 0, semanticDiffCount: 0 }
  }),

  scenario(3, "binding-unbound-state", "Unbound property exposes the unbound action and non-color state", "BINDING", [...t17(4, 4), ...t18(4, 56)], {
    input: input("EditorStructuredUnboundProperty"), steps: [step("INSPECT_PROPERTY", { target: "node-text-a.text" })]
  }),
  scenario(3, "binding-present-state", "Valid PRESENT Binding exposes bound state and source summary", "BINDING", [...t17(4, 5, 7), ...t18(4, 56)], {
    input: input("EditorStructuredBindingPresent"), steps: [step("INSPECT_PROPERTY", { target: "node-text-a.text" })]
  }),
  scenario(3, "binding-absent-state", "ABSENT Binding exposes abnormal state without baseline fallback", "BINDING", [...t17(4, 6, 7, 8, 9), ...t18(4, 56)], {
    input: input("EditorStructuredBindingAbsent"), steps: [step("INSPECT_PROPERTY", { target: "node-text-a.text" })], expected: { previewVisible: false }
  }),
  scenario(3, "binding-error-state", "ERROR Binding exposes abnormal state without baseline fallback", "BINDING", [...t17(4, 6, 7, 8, 9), ...t18(4, 56)], {
    input: input("EditorStructuredBindingError"), steps: [step("INSPECT_PROPERTY", { target: "node-text-a.text" })], expected: { previewVisible: false }
  }),
  scenario(3, "binding-type-invalid-state", "Binding type-invalid state has distinct accessible semantics", "BINDING", [...t17(4, 6, 8, 9), ...t18(4, 56, 65)], {
    input: input("EditorStructuredBindingTypeInvalid"), steps: [step("INSPECT_PROPERTY", { target: "node-text-a.text" })], expected: { previewVisible: false }
  }),
  scenario(3, "binding-property-invalid-state", "Binding property-invalid state has distinct accessible semantics", "BINDING", [...t17(4, 6, 8, 9), ...t18(4, 56, 65)], {
    input: input("EditorStructuredBindingPropertyInvalid"), steps: [step("INSPECT_PROPERTY", { target: "node-text-a.opacity" })], expected: { previewVisible: false }
  }),
  scenario(3, "binding-remove-to-unbound", "Removing a Binding returns to the unbound state", "LOCAL_MUTATION", [...t17(4, 4), ...t18(4, 56)], {
    input: input("EditorStructuredBindingPresent"), steps: [step("REMOVE_BINDING", { target: "node-text-a.text" })], expected: { undoDepth: 1 }, extraAssertions: [pending("editor.accessibilityTreeBytes", "BYTES_EQ", "binding.remove.unbound.accessibility-tree", "ARTIFACT")]
  }),
  scenario(3, "authoritative-preview-distinct", "Authoritative Preview remains distinct from Canvas feedback", "PREVIEW_SUCCESS", t17(1, 14, 15, 17), {
    input: input("EditorStructuredCleanWithLocalCanvasFeedback"), steps: [step("START_AUTHORITATIVE_PREVIEW")], fault: fault(ev("PREVIEW_RESPONSE", "RETURN_RENDER_OUTPUT")), expected: { previewVisible: true, renderDocumentCount: 1, renderOutputCount: 1 }
  }),

  scenario(4, "array-reorder-single-target", "One numeric selector follows the moved semantic item", "LOCAL_MUTATION", t18(3, 28, 30, 31), {
    input: input("EditorStructuredArrayOneNumericTarget"), steps: [step("REORDER_SEMANTIC_ARRAY", { arrayPointer: "/nodes/0/runs", fromIndex: 0, toIndex: 2 })], expected: { undoDepth: 1 }
  }),
  scenario(4, "array-reorder-multiple-targets", "Every affected numeric selector follows one permutation", "LOCAL_MUTATION", t18(3, 28, 30, 31), {
    input: input("EditorStructuredArrayMultipleNumericTargets"), steps: [step("REORDER_SEMANTIC_ARRAY", { arrayPointer: "/nodes/0/runs", fromIndex: 2, toIndex: 0 })], expected: { undoDepth: 1 }
  }),
  scenario(4, "array-reorder-overlap-rejected", "Ancestor-descendant overlap rejects the entire reorder", "LOCAL_REJECTION", t18(3, 29), {
    input: input("EditorStructuredArrayOverlappingTargets"), steps: [step("REORDER_SEMANTIC_ARRAY", { arrayPointer: "/nodes/0/commands", fromIndex: 0, toIndex: 1 })], terminals: [rejection("reorderSemanticArray")]
  }),
  scenario(4, "array-reorder-out-of-bounds-rejected", "Out-of-bounds rewrite rejects the entire reorder", "LOCAL_REJECTION", t18(3, 29), {
    input: input("EditorStructuredArrayOutOfBoundsTarget"), steps: [step("REORDER_SEMANTIC_ARRAY", { arrayPointer: "/nodes/0/runs", fromIndex: 0, toIndex: 1 })], terminals: [rejection("reorderSemanticArray")]
  }),
  scenario(4, "array-reorder-duplicate-target-rejected", "Duplicate rewritten target rejects the entire reorder", "LOCAL_REJECTION", t18(3, 29), {
    input: input("EditorStructuredArrayDuplicateTargetAfterPermutation"), steps: [step("REORDER_SEMANTIC_ARRAY", { arrayPointer: "/nodes/0/runs", fromIndex: 1, toIndex: 0 })], terminals: [rejection("reorderSemanticArray")]
  }),

  scenario(5, "local-lint-generation", "Local lint problem binds to working-content generation", "PROBLEM", t18(3, 32, 33, 34, 35, 37), {
    input: input("EditorStructuredWithLocalLintProblem", { generation: 7 }), steps: [step("OPEN_PROBLEM_PANEL")]
  }),
  scenario(5, "server-current-report-basis", "Server current report binds revision and contentHash", "PROBLEM", t18(3, 32, 33, 34, 35, 38), {
    input: input("EditorStructuredWithCurrentReport", { revision: 4, contentHashAlias: "current-hash-a" }), steps: [step("OPEN_PROBLEM_PANEL")]
  }),
  scenario(5, "save-problem-submission-basis", "Save problem binds submitted canonical content", "PROBLEM", t18(3, 32, 33, 34, 35, 39, 42), {
    input: input("EditorStructuredDirtySaveProblem"), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_STRUCTURED_PROBLEM")), terminals: [problem("saveTemplate")]
  }),
  scenario(5, "import-migration-problem-basis", "Import or migration problem binds submitted canonical content", "PROBLEM", t18(3, 32, 33, 34, 35, 39, 42), {
    input: input("EditorImportMigrationProblem"), steps: [step("VALIDATE_IMPORT_OR_MIGRATION")], terminals: [problem("validateImportOrMigration")]
  }),
  scenario(5, "closure-problem-authorized-location", "Closure problem preserves authorized locator and redacts child paths", "PROBLEM", t18(3, 32, 33, 34, 35, 36), {
    input: input("EditorStructuredClosureProblem"), steps: [step("OPEN_PROBLEM_PANEL")]
  }),
  scenario(5, "preview-problem-basis", "Preview problem binds renderOperationId and preview basis", "PROBLEM", t18(3, 32, 33, 34, 35, 40, 42), {
    input: input("EditorStructuredCleanPreviewProblem"), steps: [step("START_AUTHORITATIVE_PREVIEW")], fault: fault(ev("PREVIEW_RESPONSE", "RETURN_STRUCTURED_PROBLEM")), terminals: [problem("authoritativePreview")], expected: { previewVisible: false }
  }),
  scenario(5, "stale-problem-generation-discarded", "Stale problem generation cannot replace or focus current problems", "PROBLEM", t18(3, 37, 38, 39, 40, 41), {
    input: input("EditorStructuredWithCurrentProblemGeneration", { currentGeneration: 8 }), steps: [step("DELIVER_OLD_PROBLEM_RESULT", { deliveredGeneration: 7 })], fault: fault(ev("RESULT_DELIVERY", "DELIVER_STALE_PROBLEM_GENERATION", { generation: 7 })), expected: { focusUnchanged: true }
  }),
  scenario(5, "failure-focuses-summary", "Explicit save or preview failure focuses the categorized summary", "PROBLEM", t18(3, 32, 33, 42), {
    input: input("EditorStructuredDirtySaveProblem"), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_STRUCTURED_PROBLEM")), terminals: [problem("saveTemplate")]
  }),
  scenario(5, "problem-location-activated", "Detail navigation occurs only after author activation", "PROBLEM", t18(3, 35, 43), {
    input: input("EditorStructuredWithLocatableProblem"), steps: [step("ACTIVATE_PROBLEM_LOCATION", { problemIndex: 0 })]
  }),
  scenario(5, "redacted-location-understandable", "Redacted location remains understandable without fabricated locator", "PROBLEM", [...t18(3, 36), ...t18(4, 66)], {
    input: input("EditorStructuredWithRedactedProblem"), steps: [step("OPEN_PROBLEM_DETAIL", { problemIndex: 0 })]
  }),

  scenario(6, "save-normalization-summary", "Canonical save reports metadata, set, and equivalent-decimal normalization", "SAVE_SUCCESS", [...t18(1, 16), ...t18(4, 32, 33)], {
    input: input("EditorStructuredDirtyCanonicalNormalization", { normalizationKinds: ["METADATA_TRIM", "SET_SORT", "EQUIVALENT_DECIMAL"] }), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_CANONICAL_NORMALIZED_SUCCESS")), expected: { semanticDiffCount: 0 }
  }),
  scenario(6, "save-preserves-expression-unicode", "Canonical resynchronization preserves exact Expression source and Unicode", "SAVE_SUCCESS", [...t18(1, 16), ...t18(4, 32, 34)], {
    input: input("EditorStructuredDirtyExactExpressionUnicode"), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_CANONICAL_SUCCESS")), expected: { semanticDiffCount: 0 }
  }),
  scenario(6, "save-preserves-semantic-arrays-fields", "Canonical resynchronization preserves semantic array order and every supported field", "SAVE_SUCCESS", [...t18(1, 16), ...t18(4, 32, 34)], {
    input: input("EditorStructuredDirtySemanticArraysAndAllFields"), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_CANONICAL_SUCCESS")), expected: { semanticDiffCount: 0 }
  }),

  scenario(7, "dependency-set-offers-confirmation", "Complete dependency-only set offers bounded invalid confirmation", "CONFIRMATION", t18(1, 26), {
    input: input("EditorStructuredCompleteDependencyErrorSet"), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_COMPLETE_DEPENDENCY_ERROR_SET")), terminals: [problem("saveTemplate")], expected: { confirmationAvailable: true }
  }),
  scenario(7, "dependency-confirmation-commits-invalid", "Valid complete dependency confirmation may commit INVALID", "SAVE_SUCCESS", t18(1, 26), {
    input: input("EditorStructuredCompleteDependencyErrorSetWithValidConfirmation"), steps: [step("CONFIRM_INVALID_SAVE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_CANONICAL_INVALID_SUCCESS")), expected: { semanticDiffCount: 0 }
  }),
  scenario(7, "truncated-set-response", "PROBLEM_LIMIT_REACHED response exposes no invalid confirmation", "CONFIRMATION", t18(1, 27, 29), {
    input: input("EditorStructuredTruncatedDependencyErrorSet"), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_PROBLEM_LIMIT_REACHED")), terminals: [problem("saveTemplate", "PROBLEM_LIMIT_REACHED")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "truncated-confirmation-attempt-rejected", "Confirmation attempt after PROBLEM_LIMIT_REACHED is rejected with zero writes", "CONFIRMATION", t18(1, 27, 28, 29), {
    input: input("EditorAfterTruncatedDependencyErrorResponse"), steps: [step("ATTEMPT_INVALID_CONFIRMATION")], terminals: [rejection("attemptInvalidConfirmation")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "unknown-version-hard-error-response", "Unknown DSL version response is non-confirmable and zero-write", "CONFIRMATION", [...t04(1, 38, 39, 40), ...t18(1, 27)], {
    input: input("EditorUnknownDslVersion"), steps: [step("SAVE_TEMPLATE")], terminals: [problem("saveTemplate")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "unknown-version-confirmation-rejected", "Unknown DSL version confirmation attempt is rejected with zero writes", "CONFIRMATION", [...t04(1, 39, 40), ...t18(1, 27, 28)], {
    input: input("EditorAfterUnknownDslVersionResponse"), steps: [step("ATTEMPT_INVALID_CONFIRMATION")], terminals: [rejection("attemptInvalidConfirmation")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "unknown-member-kind-hard-error-response", "Unknown member or kind response is non-confirmable and zero-write", "CONFIRMATION", [...t04(1, 38, 39, 40), ...t18(1, 27)], {
    input: input("EditorUnknownMemberOrKind"), steps: [step("SAVE_TEMPLATE")], terminals: [problem("saveTemplate")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "unknown-member-kind-confirmation-rejected", "Unknown member or kind confirmation attempt is rejected with zero writes", "CONFIRMATION", [...t04(1, 39, 40), ...t18(1, 27, 28)], {
    input: input("EditorAfterUnknownMemberOrKindResponse"), steps: [step("ATTEMPT_INVALID_CONFIRMATION")], terminals: [rejection("attemptInvalidConfirmation")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "structural-hard-error-response", "Structural fault response is non-confirmable and zero-write", "CONFIRMATION", [...t04(1, 38, 39, 40), ...t18(1, 27)], {
    input: input("EditorStructuralHardError"), steps: [step("SAVE_TEMPLATE")], terminals: [problem("saveTemplate")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "structural-confirmation-rejected", "Structural-fault confirmation attempt is rejected with zero writes", "CONFIRMATION", [...t04(1, 39, 40), ...t18(1, 27, 28)], {
    input: input("EditorAfterStructuralHardErrorResponse"), steps: [step("ATTEMPT_INVALID_CONFIRMATION")], terminals: [rejection("attemptInvalidConfirmation")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "template-cycle-hard-error-response", "TemplateRef cycle response is non-confirmable and zero-write", "CONFIRMATION", [...t04(1, 38, 39, 40), ...t18(1, 27)], {
    input: input("EditorTemplateRefCycle"), steps: [step("SAVE_TEMPLATE")], terminals: [problem("saveTemplate")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "template-cycle-confirmation-rejected", "TemplateRef-cycle confirmation attempt is rejected with zero writes", "CONFIRMATION", [...t04(1, 39, 40), ...t18(1, 27, 28)], {
    input: input("EditorAfterTemplateRefCycleResponse"), steps: [step("ATTEMPT_INVALID_CONFIRMATION")], terminals: [rejection("attemptInvalidConfirmation")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "security-hard-error-response", "Security failure response is non-confirmable and zero-write", "CONFIRMATION", [...t04(1, 38, 39, 40), ...t18(1, 27)], {
    input: input("EditorSecurityHardError"), steps: [step("SAVE_TEMPLATE")], terminals: [problem("saveTemplate")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "security-confirmation-rejected", "Security-failure confirmation attempt is rejected with zero writes", "CONFIRMATION", [...t04(1, 39, 40), ...t18(1, 27, 28)], {
    input: input("EditorAfterSecurityHardErrorResponse"), steps: [step("ATTEMPT_INVALID_CONFIRMATION")], terminals: [rejection("attemptInvalidConfirmation")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "capacity-hard-error-response", "Capacity failure response is non-confirmable and zero-write", "CONFIRMATION", [...t04(1, 38, 39, 40), ...t18(1, 27)], {
    input: input("EditorCapacityHardError"), steps: [step("SAVE_TEMPLATE")], terminals: [problem("saveTemplate")], expected: { confirmationAvailable: false }
  }),
  scenario(7, "capacity-confirmation-rejected", "Capacity-failure confirmation attempt is rejected with zero writes", "CONFIRMATION", [...t04(1, 39, 40), ...t18(1, 27, 28)], {
    input: input("EditorAfterCapacityHardErrorResponse"), steps: [step("ATTEMPT_INVALID_CONFIRMATION")], terminals: [rejection("attemptInvalidConfirmation")], expected: { confirmationAvailable: false }
  }),

  scenario(8, "first-conflict-preserves-local", "First revision conflict preserves complete local DesignDSL without merge", "CONFLICT", t18(1, 19, 20, 21, 25), {
    input: input("EditorStructuredDirtyExpectedRevisionBehind"), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("BEFORE_SAVE", "ADVANCE_SERVER_CURRENT"), ev("SAVE_RESPONSE", "RETURN_REVISION_CONFLICT")), terminals: [problem("saveTemplate")]
  }),
  scenario(8, "confirmed-overwrite-fresh-revision", "Explicit overwrite fetches latest and resubmits complete DesignDSL", "SAVE_SUCCESS", t18(1, 22, 23, 25), {
    input: input("EditorStructuredConflictAwaitingDecision"), steps: [step("CONFIRM_OVERWRITE"), step("READ_LATEST_CURRENT"), step("RESUBMIT_COMPLETE_DESIGN_WITH_LATEST_REVISION")], fault: fault(ev("LATEST_CURRENT_READ", "RETURN_ADVANCED_CURRENT"), ev("SAVE_RESPONSE", "RETURN_CANONICAL_SUCCESS")), expected: { semanticDiffCount: 0 }
  }),
  scenario(8, "second-drift-requires-new-confirmation", "Further drift returns a new conflict and revokes prior confirmation", "CONFLICT", t18(1, 24, 25), {
    input: input("EditorStructuredConflictConfirmedOnce"), steps: [step("READ_LATEST_CURRENT"), step("RESUBMIT_COMPLETE_DESIGN_WITH_LATEST_REVISION")], fault: fault(ev("AFTER_LATEST_READ_BEFORE_SAVE", "ADVANCE_SERVER_CURRENT"), ev("SAVE_RESPONSE", "RETURN_REVISION_CONFLICT")), terminals: [problem("resubmitCompleteDesign")], expected: { confirmationAvailable: false }
  }),

  scenario(9, "transport-unknown-enters-reconciliation", "Transport-unknown save enters locked reconciliation without blind resend", "RECONCILIATION", t18(5, 6, 7, 8, 9), {
    input: input("EditorStructuredDirtySavePending"), steps: [step("SAVE_TEMPLATE")], fault: fault(ev("AFTER_SERVER_MAY_COMMIT_BEFORE_RESPONSE", "DROP_RESPONSE")), terminals: [problem("saveTemplate")], expected: { mutationLockActive: true, networkRequestSequence: ["SAVE_TEMPLATE", "READ_TRUSTED_CURRENT"] }
  }),
  scenario(9, "reconcile-converged-content", "Trusted current equal to proposed hash is adopted without ownership claim", "RECONCILIATION", t18(5, 10, 19), {
    input: input("EditorSaveReconciliationUnknown"), steps: [step("READ_TRUSTED_CURRENT")], fault: fault(ev("CURRENT_READ", "RETURN_ADVANCED_CURRENT_WITH_PROPOSED_HASH")), expected: { mutationLockActive: false, semanticDiffCount: 0 }
  }),
  scenario(9, "reconcile-safe-explicit-retry", "Unchanged current permits only explicit retry and assumes no idempotency", "RECONCILIATION", [...t04(1, 90), ...t18(5, 11, 19)], {
    input: input("EditorSaveReconciliationUnknown"), steps: [step("READ_TRUSTED_CURRENT"), step("AUTHOR_EXPLICIT_RETRY")], fault: fault(ev("CURRENT_READ", "RETURN_ORIGINAL_EXPECTED_REVISION"), ev("SAVE_RESPONSE", "RETURN_CANONICAL_SUCCESS")), expected: { mutationLockActive: false }
  }),
  scenario(9, "reconcile-enters-conflict", "Advanced different hash enters guarded conflict overwrite", "RECONCILIATION", t18(5, 12), {
    input: input("EditorSaveReconciliationUnknown"), steps: [step("READ_TRUSTED_CURRENT")], fault: fault(ev("CURRENT_READ", "RETURN_ADVANCED_CURRENT_WITH_DIFFERENT_HASH")), terminals: [problem("reconcileSave")], expected: { mutationLockActive: true }
  }),
  scenario(9, "reconcile-deleted-readonly", "Deleted target enters read-only export state", "RECONCILIATION", t18(5, 13), {
    input: input("EditorSaveReconciliationUnknown"), steps: [step("READ_TRUSTED_CURRENT")], fault: fault(ev("CURRENT_READ", "RETURN_TEMPLATE_DELETED")), terminals: [problem("reconcileSave")], expected: { mutationLockActive: true, mode: "COMPATIBILITY_READ_ONLY" }
  }),
  scenario(9, "reconcile-integrity-fails-closed", "Revision regression or integrity mismatch fails closed", "RECONCILIATION", t18(5, 14), {
    input: input("EditorSaveReconciliationUnknown"), steps: [step("READ_TRUSTED_CURRENT")], fault: fault(ev("CURRENT_READ", "RETURN_REVISION_REGRESSION_OR_INTEGRITY_MISMATCH")), terminals: [problem("reconcileSave")], expected: { mutationLockActive: true }
  }),
  scenario(9, "reconcile-unreadable-remains-unknown", "Unreadable trusted current remains unknown and keeps guards", "RECONCILIATION", t18(5, 15, 16, 17), {
    input: input("EditorSaveReconciliationUnknown"), steps: [step("READ_TRUSTED_CURRENT"), step("EXPORT_DRAFT"), step("LEAVE_WITH_RECOVERY_RETAINED")], fault: fault(ev("CURRENT_READ", "RETURN_UNAVAILABLE")), terminals: [problem("reconcileSave")], expected: { mutationLockActive: true, previewVisible: false }
  }),
  scenario(9, "reopen-resumes-reconciliation", "Reopening resumes reconciliation before ordinary editing", "RECONCILIATION", t18(5, 18, 19), {
    input: input("EditorClosedWithSaveReconciliationRecovery"), steps: [step("OPEN_TEMPLATE"), step("READ_TRUSTED_CURRENT")], fault: fault(ev("CURRENT_READ", "RETURN_ADVANCED_CURRENT_WITH_PROPOSED_HASH")), expected: { mutationLockActive: false, semanticDiffCount: 0 }
  }),

  scenario(10, "recovery-reopen-offers-actions", "Reopen offers explicit Restore, Export, and Discard", "MODE", [...t18(1, 9, 10, 11, 12), ...t18(2, 1, 6, 14, 15)], {
    input: input("EditorClosedWithDeviceLocalRecoveryDraft"), steps: [step("OPEN_TEMPLATE")], expected: { mode: "STRUCTURED_EDITOR", recoveryDraftPresent: true }
  }),
  scenario(10, "recovery-restore-same-base", "Same-base recovery restores a dirty working draft without write", "MODE", t18(2, 7, 9, 10), {
    input: input("EditorRecoveryDraftSameAsServerBase"), steps: [step("RESTORE_RECOVERY_DRAFT")], expected: { mode: "STRUCTURED_EDITOR", dirty: true, writeCount: 0 }
  }),
  scenario(10, "recovery-restore-advanced-base", "Advanced baseline is reported before explicit recovery restore", "MODE", t18(2, 8, 9, 10), {
    input: input("EditorRecoveryDraftBehindServerCurrent"), steps: [step("ACKNOWLEDGE_ADVANCED_BASELINE"), step("RESTORE_RECOVERY_DRAFT")], expected: { mode: "STRUCTURED_EDITOR", dirty: true, writeCount: 0 }
  }),
  scenario(10, "recovery-export", "Recovery export is explicit and performs no write", "MODE", [...t18(1, 10), ...t18(2, 14)], {
    input: input("EditorClosedWithDeviceLocalRecoveryDraft"), steps: [step("EXPORT_RECOVERY_DRAFT")], expected: { recoveryDraftPresent: true, writeCount: 0 }
  }),
  scenario(10, "recovery-discard", "Recovery discard is explicit and removes only local recovery", "MODE", [...t18(1, 10), ...t18(2, 14)], {
    input: input("EditorClosedWithDeviceLocalRecoveryDraft"), steps: [step("DISCARD_RECOVERY_DRAFT")], expected: { recoveryDraftPresent: false, writeCount: 0 }
  }),
  scenario(10, "recovery-minimal-content", "Recovery record omits runtime input, preview image, and Asset bytes", "MODE", [...t18(1, 12), ...t18(2, 15)], {
    input: input("EditorRecoveryDraftWithSensitiveRuntimeCandidates"), steps: [step("PERSIST_DEVICE_LOCAL_RECOVERY_DRAFT")], expected: { recoveryDraftPresent: true, writeCount: 0 }
  }),
  scenario(10, "valid-import-becomes-dirty-draft", "Supported valid import replaces only the local draft", "MODE", [...t18(3, 26, 27), ...t18(4, 20, 22, 23, 24)], {
    input: input("EditorStructuredCleanWithValidSupportedImport"), steps: [step("IMPORT_DESIGN_DSL"), step("ACCEPT_IMPORT")], expected: { mode: "STRUCTURED_EDITOR", dirty: true, undoDepth: 0, redoDepth: 0, writeCount: 0 }
  }),
  scenario(10, "malformed-import-enters-raw-repair", "Malformed raw import enters Raw Repair without partial structure", "MODE", [...t18(3, 1, 4, 5, 8, 9, 10, 12), ...t18(4, 24)], {
    input: input("EditorMalformedRawImport", { fault: "DUPLICATE_JSON_KEYS" }), steps: [step("IMPORT_RAW_CONTENT")], expected: { mode: "RAW_REPAIR", dirty: false, writeCount: 0 }
  }),
  scenario(10, "raw-repair-valid-transition", "Raw Repair enters Structured Editor only after complete strict construction", "MODE", t18(3, 4, 5, 11, 12), {
    input: input("EditorRawRepairWithRepairedSupportedContent"), steps: [step("VALIDATE_REPAIRED_RAW_CONTENT"), step("ACCEPT_REPAIRED_CONTENT")], expected: { mode: "STRUCTURED_EDITOR", dirty: true, writeCount: 0 }
  }),
  scenario(10, "unsupported-wire-compatibility-readonly", "Complete unsupported wire enters Compatibility Read-only and preserves original", "MODE", [...t18(3, 1, 6, 7, 8, 9, 10, 14, 15), ...t18(4, 25)], {
    input: input("EditorCompleteUnsupportedExactWire"), steps: [step("IMPORT_UNSUPPORTED_COMPLETE_CONTENT")], expected: { mode: "COMPATIBILITY_READ_ONLY", dirty: false, writeCount: 0 }
  }),
  scenario(10, "migration-preview-accepted-dirty", "Accepted contentHash-bound migration creates only a dirty draft", "MODE", [...t18(3, 13, 14, 15, 26, 27), ...t18(4, 20, 22, 23)], {
    input: input("EditorCompatibilityReadOnlyWithMigrationPreview"), steps: [step("START_MIGRATION_PREVIEW"), step("ACCEPT_MIGRATION_PREVIEW")], expected: { mode: "STRUCTURED_EDITOR", dirty: true, undoDepth: 0, redoDepth: 0, writeCount: 0 }
  }),
  scenario(10, "dirty-guard-blocks-replacement", "Dirty guard blocks silent import, migration, or recovery replacement", "LOCAL_REJECTION", t18(4, 20, 21), {
    input: input("EditorStructuredDirtyBeforeReplacement"), steps: [step("ATTEMPT_REPLACE_WORKING_DRAFT", { source: "IMPORT" })], terminals: [rejection("replaceWorkingDraft")]
  }),

  scenario(11, "clean-current-preview", "Clean session previews only saved current", "PREVIEW_SUCCESS", t18(1, 1, 3), {
    input: input("EditorStructuredCleanCurrentReady"), steps: [step("START_AUTHORITATIVE_PREVIEW")], fault: fault(ev("PREVIEW_RESPONSE", "RETURN_RENDER_OUTPUT")), expected: { previewVisible: true, renderDocumentCount: 1, renderOutputCount: 1 }
  }),
  scenario(11, "dirty-primary-save-and-preview", "Dirty session offers Save and Preview instead of direct authoritative preview", "PREVIEW_STATE", t18(1, 2, 4), {
    input: input("EditorStructuredDirty"), steps: [step("INSPECT_PREVIEW_PRIMARY_ACTION")], expected: { previewVisible: false }
  }),
  scenario(11, "save-and-preview-save-fails", "Save failure starts no preview", "PREVIEW_PROBLEM", [...t18(1, 5, 7), ...t18(2, 26, 29, 30)], {
    input: input("EditorStructuredDirtySaveProblem"), steps: [step("SAVE_AND_PREVIEW")], fault: fault(ev("SAVE_RESPONSE", "RETURN_STRUCTURED_PROBLEM")), terminals: [problem("saveTemplate")], expected: { previewVisible: false, writeCount: 0, renderDocumentCount: 0, renderOutputCount: 0 }
  }),
  scenario(11, "save-and-preview-invalid-save", "Confirmed INVALID save starts no preview", "PREVIEW_PROBLEM", t18(1, 5, 7), {
    input: input("EditorStructuredDirtyConfirmedInvalid"), steps: [step("SAVE_AND_PREVIEW_SAVE_PHASE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_CANONICAL_INVALID_SUCCESS")), terminals: [success("saveTemplate")], expected: { accepted: true, previewVisible: false, writeCount: 1, renderDocumentCount: 0, renderOutputCount: 0 }
  }),
  scenario(11, "save-and-preview-save-phase-success", "Save-and-Preview save phase commits before preview starts", "PREVIEW_STATE", [...t18(1, 5), ...t18(2, 26, 27)], {
    input: input("EditorStructuredDirtyReadyAfterSave"), steps: [step("SAVE_AND_PREVIEW_SAVE_PHASE")], fault: fault(ev("SAVE_RESPONSE", "RETURN_CANONICAL_SUCCESS"), ev("AUTHORITATIVE_RECHECK", "RETURN_READY")), terminals: [success("saveTemplate")], expected: { accepted: true, previewVisible: false, writeCount: 1, renderDocumentCount: 0, renderOutputCount: 0 }
  }),
  scenario(11, "save-and-preview-preview-phase-success", "Independent preview starts only from the saved READY baseline", "PREVIEW_SUCCESS", [...t18(1, 5, 6), ...t18(2, 26, 27)], {
    input: input("EditorCanonicalReadyAfterSaveAndPreviewSavePhase"), steps: [step("START_PREVIEW_AFTER_SAVE_PHASE")], fault: fault(ev("PREVIEW_RESPONSE", "RETURN_RENDER_OUTPUT")), terminals: [success("authoritativePreview")], expected: { previewVisible: true, writeCount: 0, renderDocumentCount: 1, renderOutputCount: 1 }
  }),
  scenario(11, "basis-design-edit-withdraws", "Design edit withdraws old preview", "PREVIEW_STATE", t18(2, 17, 18), {
    input: input("EditorPreviewVisible"), steps: [step("EDIT_DESIGN_DSL")], expected: { previewVisible: false }
  }),
  scenario(11, "basis-sample-input-withdraws", "Sample input change withdraws old preview", "PREVIEW_STATE", t18(2, 17, 18), {
    input: input("EditorPreviewVisible"), steps: [step("CHANGE_SAMPLE_INPUT")], expected: { previewVisible: false }
  }),
  scenario(11, "basis-format-withdraws", "Output format change withdraws old preview", "PREVIEW_STATE", t18(2, 17, 18), {
    input: input("EditorPreviewVisible", { format: "PNG" }), steps: [step("CHANGE_OUTPUT_FORMAT", { format: "JPEG" })], expected: { previewVisible: false }
  }),
  scenario(11, "basis-dpi-withdraws", "DPI change withdraws old preview", "PREVIEW_STATE", t18(2, 17, 18), {
    input: input("EditorPreviewVisible", { dpi: 96 }), steps: [step("CHANGE_DPI", { dpi: 144 })], expected: { previewVisible: false }
  }),
  scenario(11, "basis-jpeg-quality-withdraws", "JPEG quality change withdraws old preview", "PREVIEW_STATE", t18(2, 17, 18), {
    input: input("EditorPreviewVisible", { format: "JPEG", quality: 90 }), steps: [step("CHANGE_JPEG_QUALITY", { quality: 80 })], expected: { previewVisible: false }
  }),
  scenario(11, "basis-layout-trace-withdraws", "LayoutTrace choice change withdraws old preview", "PREVIEW_STATE", t18(2, 17, 18), {
    input: input("EditorPreviewVisible", { includeLayoutTrace: false }), steps: [step("CHANGE_LAYOUT_TRACE_CHOICE", { includeLayoutTrace: true })], expected: { previewVisible: false }
  }),
  scenario(11, "basis-current-readiness-withdraws", "Current revision or readiness change withdraws old preview", "PREVIEW_STATE", t18(2, 17, 18), {
    input: input("EditorPreviewVisible"), steps: [step("DELIVER_CURRENT_OR_READINESS_CHANGE")], fault: fault(ev("CURRENT_EVENT", "ADVANCE_REVISION_OR_CHANGE_READINESS")), expected: { previewVisible: false }
  }),
  scenario(11, "panel-selection-preserves-basis", "Panel visibility and selection movement preserve preview basis", "PREVIEW_STATE", t18(2, 19), {
    input: input("EditorPreviewVisible"), steps: [step("TOGGLE_PANEL"), step("MOVE_SELECTION")], expected: { previewVisible: true, basisDigestUnchanged: true }
  }),
  scenario(11, "new-preview-revokes-old", "Starting a new preview cancels and revokes prior display eligibility", "PREVIEW_STATE", t18(2, 16, 20, 21), {
    input: input("EditorPreviewOperationActive"), steps: [step("START_AUTHORITATIVE_PREVIEW")], fault: fault(ev("CANCEL_REQUEST", "BEST_EFFORT_CANCEL_PREVIOUS")), expected: { previewVisible: false, previewGenerationDelta: 1 }
  }),
  scenario(11, "author-cancel-clears-slot", "Author cancel clears the preview slot immediately", "PREVIEW_STATE", t18(2, 22, 23), {
    input: input("EditorPreviewOperationActiveWithVisibleResult"), steps: [step("CANCEL_AUTHORITATIVE_PREVIEW")], fault: fault(ev("CANCEL_REQUEST", "ENGINE_MAY_ALREADY_SEAL")), expected: { previewVisible: false }
  }),
  scenario(11, "late-success-discarded", "Late success from old operation cannot replace current state", "PREVIEW_STATE", [...t18(2, 24), ...t18(3, 40, 41)], {
    input: input("EditorNewerPreviewGenerationActive", { currentGeneration: 9, deliveredGeneration: 8 }), steps: [step("DELIVER_OLD_PREVIEW_SUCCESS")], fault: fault(ev("RESULT_DELIVERY", "DELIVER_LATE_PREVIEW_SUCCESS", { generation: 8 })), expected: { previewVisible: false }
  }),
  scenario(11, "late-failure-discarded", "Late failure from old operation cannot replace current problems", "PREVIEW_STATE", [...t18(2, 24), ...t18(3, 40, 41)], {
    input: input("EditorNewerPreviewGenerationActive", { currentGeneration: 9, deliveredGeneration: 8 }), steps: [step("DELIVER_OLD_PREVIEW_FAILURE")], fault: fault(ev("RESULT_DELIVERY", "DELIVER_LATE_PREVIEW_FAILURE", { generation: 8 })), expected: { previewVisible: false }
  }),
  scenario(11, "preview-failure-does-not-rollback-save", "Preview failure after save never rolls back the committed revision", "PREVIEW_PROBLEM", t18(2, 26, 27, 28, 29, 30), {
    input: input("EditorCanonicalReadyAfterCommittedSave"), steps: [step("START_PREVIEW_AFTER_SAVE_PHASE")], fault: fault(ev("PREVIEW_RESPONSE", "RETURN_RUNTIME_PROBLEM")), terminals: [problem("authoritativePreview")], expected: { previewVisible: false, writeCount: 0, renderDocumentCount: 0, renderOutputCount: 0 }
  }),
  scenario(11, "single-slot-no-history-autorun", "Preview uses one slot with no history or automatic rerun", "PREVIEW_STATE", t18(2, 16, 25), {
    input: input("EditorPreviewCompletedOnce"), steps: [step("INSPECT_PREVIEW_SLOT_AND_IDLE_NETWORK")], expected: { previewVisible: true, networkRequestSequence: [] }
  }),

  scenario(12, "screen-reader-semantic-tree", "Core controls expose stable accessible semantics", "ACCESSIBILITY", t18(4, 52, 53, 55, 57, 67), {
    input: input("EditorStructuredClean", { assistiveMode: "SCREEN_READER" }), steps: [step("TRAVERSE_ACCESSIBILITY_TREE")]
  }),
  scenario(12, "keyboard-core-flow", "Core authoring and problem flow remains keyboard operable", "KEYBOARD", t18(4, 54, 57), {
    input: input("EditorStructuredWithCoreFlowProblems"), steps: [step("COMPLETE_CORE_FLOW_BY_KEYBOARD")]
  }),
  scenario(12, "live-region-announcements", "Progress and blocking status use bounded non-duplicated announcements", "ACCESSIBILITY", t18(4, 58), {
    input: input("EditorStructuredWithProgressAndBlockingProblem"), steps: [step("TRIGGER_PROGRESS_THEN_BLOCKING_ERROR")]
  }),
  scenario(12, "reduced-motion-core-flow", "Reduced-motion preference preserves the complete core flow", "ACCESSIBILITY", t18(4, 59), {
    input: input("EditorStructuredClean", { environmentProfile: "REDUCED_MOTION" }), steps: [step("COMPLETE_CORE_FLOW")], expected: { reducedMotionHonored: true }
  }),
  scenario(12, "zoom-200-canvas-pan", "At 200 percent zoom panels and two-dimensional Canvas remain operable", "ACCESSIBILITY", t18(4, 60, 61), {
    input: input("EditorStructuredLargeCanvas", { environmentProfile: "ZOOM_200" }), steps: [step("COMPLETE_CORE_FLOW_AND_PAN_CANVAS")], expected: { zoom200Operable: true }
  }),
  scenario(12, "unsupported-width-operable", "Unsupported-width state remains keyboard and screen-reader operable", "ACCESSIBILITY", t18(4, 62), {
    input: input("EditorUnsupportedWidth", { viewportClass: "BELOW_DESKTOP_THRESHOLD", assistiveMode: "SCREEN_READER" }), steps: [step("TRAVERSE_UNSUPPORTED_WIDTH_STATE")]
  }),
  scenario(12, "high-contrast-noncolor-flow", "Supported high contrast preserves non-color state cues and core flow", "ACCESSIBILITY", t18(4, 56, 68), {
    input: input("EditorStructuredWithBindingAndSeverityStates", { environmentProfile: "SUPPORTED_HIGH_CONTRAST" }), steps: [step("COMPLETE_CORE_FLOW")], expected: { highContrastOperable: true }
  })
];

function copy(value) {
  return JSON.parse(JSON.stringify(value));
}

function applyTerminalAdjudications() {
  const seen = new Set();
  for (const definition of definitions) {
    const decision = TERMINAL_ADJUDICATIONS[definition.slug];
    if (!decision) continue;
    if (definition.terminals.length !== 1) throw new Error(`terminal adjudication requires one terminal: ${definition.slug}`);
    const terminal = definition.terminals[0];
    const previouslyPending = terminal.codeBinding.status.startsWith("PENDING") || terminal.stageBinding.status.startsWith("PENDING");
    if (!previouslyPending) throw new Error(`terminal adjudication is not closing a pending binding: ${definition.slug}`);
    definition.terminalAdjudication = {
      previousCodeBinding: copy(terminal.codeBinding),
      previousStageBinding: copy(terminal.stageBinding),
      literalSource: EXISTING_SPEC_CODES.includes(decision.code) && (decision.stage === null || EXISTING_SPEC_STAGES.includes(decision.stage))
        ? "EXISTING_SPEC_LITERAL"
        : "EDITOR_ACCEPTANCE_LITERAL"
    };
    terminal.codeBinding = { status: "EXACT", code: decision.code };
    terminal.stageBinding = decision.stage === null ? { status: "NOT_REQUIRED" } : { status: "EXACT", stage: decision.stage };
    seen.add(definition.slug);
  }
  const missing = Object.keys(TERMINAL_ADJUDICATIONS).filter((slug) => !seen.has(slug));
  if (seen.size !== 33 || missing.length !== 0) throw new Error(`terminal adjudication closure drift: seen=${seen.size} missing=${missing.join(",")}`);
}

function indexDefinitions(assignment) {
  const seeds = new Map(assignment.automatedJourneySeeds.map((entry) => [Number(entry.sourceJ1CaseId.slice(-3)), entry]));
  const counters = new Map();
  return definitions.map((definition) => {
    const seed = seeds.get(definition.seed);
    if (!seed) throw new Error(`unknown seed ${definition.seed}`);
    const ordinal = (counters.get(definition.seed) ?? 0) + 1;
    counters.set(definition.seed, ordinal);
    return {
      definition,
      seed,
      candidateId: `EDC::J${String(definition.seed).padStart(2, "0")}::${String(ordinal).padStart(3, "0")}`
    };
  });
}

function terminalProjection(terminal) {
  const projection = { operationId: terminal.operationId, outcome: terminal.outcome };
  if (terminal.codeBinding.status === "EXACT") projection.code = terminal.codeBinding.code;
  else if (terminal.codeBinding.status !== "NOT_REQUIRED") throw new Error(`terminal code is not exact: ${terminal.operationId}`);
  if (terminal.stageBinding.status === "EXACT") projection.stage = terminal.stageBinding.stage;
  else if (terminal.stageBinding.status !== "NOT_REQUIRED") throw new Error(`terminal stage is not exact: ${terminal.operationId}`);
  return projection;
}

function buildTerminalAdjudication(indexedDefinitions) {
  const decisions = indexedDefinitions
    .filter(({ definition }) => definition.terminalAdjudication)
    .map(({ definition, candidateId }) => ({
      candidateId,
      scenarioSlug: definition.slug,
      requirementIds: [...definition.requirements].sort(),
      previousCodeBinding: definition.terminalAdjudication.previousCodeBinding,
      previousStageBinding: definition.terminalAdjudication.previousStageBinding,
      exactTerminal: terminalProjection(definition.terminals[0]),
      literalSource: definition.terminalAdjudication.literalSource,
      productImplementationObserved: false
    }));
  const codes = [...new Set(decisions.map((entry) => entry.exactTerminal.code))].sort();
  const stages = [...new Set(decisions.flatMap((entry) => entry.exactTerminal.stage ? [entry.exactTerminal.stage] : []))].sort();
  return {
    artifactVersion: "renderweave-editor-terminal-adjudication/1.0",
    status: "EXACT_PLANNING_BINDINGS_PRODUCT_UNPROVEN",
    authority: "Ticket04-Ticket18 semantics plus Ticket19 exact terminal planning; new literals are acceptance-contract decisions and not evidence that product code exists",
    executionClass: EXECUTION_CLASS,
    rules: {
      oneTerminalPerCandidate: true,
      codeAndStageMayBeOmittedOnlyByExactNotRequiredDecision: true,
      genericFallbackForbidden: true,
      naturalLanguageParsingForbidden: true,
      productObservationRequiredBeforeFormalIssuance: true
    },
    existingSpecLiterals: {
      codes: EXISTING_SPEC_CODES,
      stages: EXISTING_SPEC_STAGES
    },
    editorAcceptanceLiterals: {
      codes: codes.filter((code) => !EXISTING_SPEC_CODES.includes(code)),
      stages: stages.filter((stage) => !EXISTING_SPEC_STAGES.includes(stage))
    },
    counts: {
      candidateTerminalCount: indexedDefinitions.length,
      adjudicatedFromPendingCount: decisions.length,
      exactCodeCandidateCount: indexedDefinitions.filter(({ definition }) => definition.terminals[0].codeBinding.status === "EXACT").length,
      exactStageCandidateCount: indexedDefinitions.filter(({ definition }) => definition.terminals[0].stageBinding.status === "EXACT").length,
      pendingCodeOrStageCount: indexedDefinitions.filter(({ definition }) => definition.terminals.some((terminal) => terminal.codeBinding.status.startsWith("PENDING") || terminal.stageBinding.status.startsWith("PENDING"))).length
    },
    decisions,
    zeroExecutionBoundary: {
      productCodeChanged: false,
      browserStarted: false,
      networkUsed: false,
      formalJsonlAppended: false,
      productTerminalObserved: false,
      j1Executed: false,
      readyClaimed: false
    }
  };
}

function formalFaultIdentity(valueWithoutIdentity) {
  return sha(Buffer.concat([
    Buffer.from("renderweave-conformance-fault-identity/1\0", "utf8"),
    Buffer.from(JSON.stringify(valueWithoutIdentity), "utf8")
  ]));
}

function formalInputIdentity(valueWithoutIdentity) {
  return sha(Buffer.concat([
    Buffer.from("renderweave-conformance-input-identity/1\0", "utf8"),
    Buffer.from(JSON.stringify(valueWithoutIdentity), "utf8")
  ]));
}

function candidateFileStem(candidateId) {
  return candidateId.toLowerCase().replaceAll("::", "-");
}

function faultArtifactPath(candidateId) {
  return `${FAULT_DIRECTORY}/${candidateFileStem(candidateId)}.json`;
}

function inputFixturePath(candidateId) {
  return `${INPUT_FIXTURE_DIRECTORY}/${candidateFileStem(candidateId)}.json`;
}

function targetArtifactPath(candidateId, planAssertionId) {
  return `${TARGET_ARTIFACT_DIRECTORY}/${candidateFileStem(candidateId)}-${planAssertionId.toLowerCase()}.json`;
}

function buildInputFixtureContract(indexedDefinitions) {
  const actionSignatures = new Map();
  for (const { definition } of indexedDefinitions) {
    for (const action of definition.steps) {
      const signature = { action: action.action, parameterMembers: Object.keys(action.parameters).sort() };
      actionSignatures.set(stable(signature), signature);
    }
  }
  return {
    artifactVersion: "renderweave-editor-input-fixture-contract/1.0",
    profileId: INPUT_FIXTURE_PROFILE,
    status: "FROZEN_SEMANTIC_FIXTURE_INTERFACE_PRODUCT_ADAPTER_PENDING",
    executionClass: EXECUTION_CLASS,
    module: {
      name: "EditorInputFixture",
      interface: "one immutable SHA-256-bound semantic baseline selection, closed parameter object, and ordered action script per Editor candidate",
      seam: "the fixture describes only product-visible starting semantics and named user actions; DOM selectors, framework state, persistence rows, transport internals, clocks, arbitrary scripts, and hidden setup are excluded",
      adapter: "a future admitted product adapter must construct the named baseline and execute each action once in order without changing fixture bytes or candidate meaning"
    },
    artifactMembersInOrder: ["artifactVersion", "candidateId", "executionClass", "baselineId", "parameters", "actionScript"],
    actionMembersInOrder: ["action", "parameters"],
    allowedBaselineIds: [...new Set(indexedDefinitions.map(({ definition }) => definition.input.baselineId))].sort(),
    allowedActionSignatures: [...actionSignatures.values()].sort((left, right) => Buffer.compare(Buffer.from(stable(left)), Buffer.from(stable(right)))),
    formalInputBindingMembersInOrder: ["kind", "artifactPath", "mediaType", "artifactSha256", "identitySha256"],
    formalInputIdentityDomain: "renderweave-conformance-input-identity/1",
    forbiddenMechanisms: ["wildcard", "regex selector", "arbitrary script", "ambient database state", "ambient browser storage", "ambient time", "ambient entropy", "network lookup", "hidden default"],
    evolutionRule: "Any new baseline semantic, parameter member, action, ordering rule, or adapter obligation requires a new exact input fixture Profile.",
    boundary: "These artifacts freeze semantic stimuli and formal input identities only; they do not prove that a product fixture adapter exists, a browser loaded the baseline, or an action executed."
  };
}

function buildInputFixtureCatalog(indexedDefinitions) {
  const bindings = new Map();
  const artifacts = [];
  for (const { definition, candidateId } of indexedDefinitions) {
    const relativePath = inputFixturePath(candidateId);
    const value = {
      artifactVersion: INPUT_FIXTURE_PROFILE,
      candidateId,
      executionClass: EXECUTION_CLASS,
      baselineId: definition.input.baselineId,
      parameters: definition.input.parameters,
      actionScript: definition.steps
    };
    write(relativePath, value);
    const boundArtifact = artifact(relativePath);
    const formalBase = {
      kind: "ARTIFACT",
      artifactPath: relativePath,
      mediaType: "application/json",
      artifactSha256: boundArtifact.sha256
    };
    const formalBinding = { ...formalBase, identitySha256: formalInputIdentity(formalBase) };
    bindings.set(candidateId, formalBinding);
    artifacts.push({ candidateId, scenarioSlug: definition.slug, artifact: boundArtifact, formalBinding });
  }
  return {
    catalog: {
      artifactVersion: "renderweave-editor-input-fixture-catalog/1.0",
      status: "EXACT_SEMANTIC_FIXTURES_PRODUCT_ADAPTER_UNPROVEN",
      executionClass: EXECUTION_CLASS,
      contract: artifact(INPUT_FIXTURE_CONTRACT_PATH),
      counts: { candidateCount: indexedDefinitions.length, artifactCandidateCount: artifacts.length, orphanArtifactCount: 0 },
      artifacts,
      boundary: "Every Editor candidate now has immutable semantic fixture bytes and a formal input identity; no product fixture adapter, browser setup, action execution, or observed result is proved."
    },
    bindings
  };
}

const UI_OBSERVED_PROBES = new Set([
  "editor.accessibilityTreeBytes",
  "editor.announcementSequence",
  "editor.focusSequence",
  "editor.problemPanelBytes"
]);

const COMMAND_RESULT_BY_SLUG = Object.freeze({
  "create-entity-atomic": "COMMAND_APPLIED",
  "duplicate-entity-remaps": "COMMAND_APPLIED",
  "delete-entity-atomic": "COMMAND_APPLIED",
  "delete-unsafe-rejected": "COMMAND_REJECTED_ATOMIC",
  "move-entity-atomic": "COMMAND_APPLIED",
  "reparent-entity-atomic": "COMMAND_APPLIED",
  "reorder-entity-atomic": "COMMAND_APPLIED",
  "undo-complete-working-copy": "COMMAND_APPLIED",
  "redo-complete-working-copy": "COMMAND_APPLIED",
  "binding-unbound-state": "BINDING_UNBOUND",
  "binding-present-state": "BINDING_PRESENT",
  "binding-absent-state": "BINDING_ABSENT",
  "binding-error-state": "BINDING_ERROR",
  "binding-type-invalid-state": "BINDING_TYPE_INVALID",
  "binding-property-invalid-state": "BINDING_PROPERTY_INVALID",
  "binding-remove-to-unbound": "COMMAND_APPLIED",
  "array-reorder-single-target": "COMMAND_APPLIED",
  "array-reorder-multiple-targets": "COMMAND_APPLIED",
  "array-reorder-overlap-rejected": "COMMAND_REJECTED_ATOMIC",
  "array-reorder-out-of-bounds-rejected": "COMMAND_REJECTED_ATOMIC",
  "array-reorder-duplicate-target-rejected": "COMMAND_REJECTED_ATOMIC",
  "dirty-guard-blocks-replacement": "COMMAND_REJECTED_ATOMIC"
});

const PREVIEW_GENERATION_DELTA_BY_SLUG = Object.freeze({
  "authoritative-preview-distinct": 1,
  "clean-current-preview": 1,
  "dirty-primary-save-and-preview": 0,
  "save-and-preview-save-fails": 0,
  "save-and-preview-invalid-save": 1,
  "save-and-preview-save-phase-success": 1,
  "save-and-preview-preview-phase-success": 1,
  "basis-design-edit-withdraws": 1,
  "basis-sample-input-withdraws": 1,
  "basis-format-withdraws": 1,
  "basis-dpi-withdraws": 1,
  "basis-jpeg-quality-withdraws": 1,
  "basis-layout-trace-withdraws": 1,
  "basis-current-readiness-withdraws": 1,
  "panel-selection-preserves-basis": 0,
  "author-cancel-clears-slot": 1,
  "late-success-discarded": 0,
  "late-failure-discarded": 0,
  "preview-failure-does-not-rollback-save": 1,
  "single-slot-no-history-autorun": 0
});

const NO_TRUSTED_WORKING_COPY_SLUGS = new Set([
  "malformed-import-enters-raw-repair",
  "unsupported-wire-compatibility-readonly"
]);

const CONTENT_PREREQUISITES_BY_PROBE = Object.freeze({
  "editor.workingCopyDigest": ["EXACT_WORKING_COPY_CANONICAL_DESIGN_DSL_BYTES"],
  "editor.canonicalBaselineBytes": [
    "EXACT_SERVER_CANONICAL_REVISION",
    "EXACT_SERVER_CANONICAL_CONTENT_HASH",
    "EXACT_WORKING_COPY_CANONICAL_DESIGN_DSL_BYTES",
    "CANONICAL_BASELINE_PROJECTION_ENCODER"
  ],
  "editor.previewBasisDigest": [
    "EXACT_SAVED_CURRENT_IDENTITY",
    "EXACT_LOCAL_DIVERGENCE_STATE",
    "EXACT_SAMPLE_INPUT_IDENTITY",
    "EXACT_OUTPUT_PARAMETER_VECTOR",
    "EXACT_CURRENT_READINESS",
    "PREVIEW_BASIS_CANONICAL_ENCODER"
  ],
  "editor.recoveryDraftEnvelopeBytes": [
    "EXACT_RECOVERY_WORKING_COPY_DIGEST",
    "EXACT_RECOVERY_BASE_FACTS",
    "RECOVERY_ENVELOPE_CANONICAL_ENCODER"
  ],
  "editor.compatibilityOriginalDigest": ["EXACT_COMPATIBILITY_ORIGINAL_BYTES"],
  "editor.normalizationSummaryBytes": [
    "EXACT_SERVER_CANONICAL_BEFORE_AFTER_BYTES",
    "EXACT_NORMALIZATION_CATEGORY_COUNTS",
    "NORMALIZATION_SUMMARY_CANONICAL_ENCODER"
  ]
});

const CONTENT_PREREQUISITE_CATALOG = Object.freeze([
  { prerequisiteId: "EXACT_WORKING_COPY_CANONICAL_DESIGN_DSL_BYTES", meaning: "the exact complete post-action working-copy DesignDSL after authoritative canonical encoding" },
  { prerequisiteId: "EXACT_SERVER_CANONICAL_REVISION", meaning: "the exact trusted server revision adopted by the candidate" },
  { prerequisiteId: "EXACT_SERVER_CANONICAL_CONTENT_HASH", meaning: "the exact trusted server contentHash adopted by the candidate" },
  { prerequisiteId: "CANONICAL_BASELINE_PROJECTION_ENCODER", meaning: "the frozen renderweave-editor-canonical-baseline-projection/1.0 member and byte encoding" },
  { prerequisiteId: "EXACT_SAVED_CURRENT_IDENTITY", meaning: "the exact saved current revision and contentHash used by Authoritative Preview" },
  { prerequisiteId: "EXACT_LOCAL_DIVERGENCE_STATE", meaning: "the exact clean or divergent local-content fact in the preview basis" },
  { prerequisiteId: "EXACT_SAMPLE_INPUT_IDENTITY", meaning: "the exact RootDocument and customValues sample identity without exposing business bytes" },
  { prerequisiteId: "EXACT_OUTPUT_PARAMETER_VECTOR", meaning: "the exact format, DPI, JPEG quality, and LayoutTrace choice vector" },
  { prerequisiteId: "EXACT_CURRENT_READINESS", meaning: "the exact current readiness admitted into the preview basis" },
  { prerequisiteId: "PREVIEW_BASIS_CANONICAL_ENCODER", meaning: "the frozen closed preview-basis projection and digest encoder" },
  { prerequisiteId: "EXACT_RECOVERY_WORKING_COPY_DIGEST", meaning: "the exact digest of the complete recovery working copy" },
  { prerequisiteId: "EXACT_RECOVERY_BASE_FACTS", meaning: "the exact optional recovery baseRevision and baseContentHash facts" },
  { prerequisiteId: "RECOVERY_ENVELOPE_CANONICAL_ENCODER", meaning: "the frozen recovery-draft-envelope/1.0 member and byte encoding" },
  { prerequisiteId: "EXACT_COMPATIBILITY_ORIGINAL_BYTES", meaning: "the exact complete original compatibility-mode bytes retained for export" },
  { prerequisiteId: "EXACT_SERVER_CANONICAL_BEFORE_AFTER_BYTES", meaning: "the exact submitted and trusted server-canonical DesignDSL bytes" },
  { prerequisiteId: "EXACT_NORMALIZATION_CATEGORY_COUNTS", meaning: "the exact bounded normalization category identifiers and counts" },
  { prerequisiteId: "NORMALIZATION_SUMMARY_CANONICAL_ENCODER", meaning: "the frozen normalization-summary/1.0 member and byte encoding" }
]);

const TARGET_PENDING_REASON = Object.freeze({
  "editor.accessibilityTreeBytes": "Exact accessible names, roles, states, relationships, and tree serialization require an admitted product accessibility observation.",
  "editor.announcementSequence": "Exact live-region announcement text and order require an admitted product accessibility observation.",
  "editor.focusSequence": "Exact focus targets and order require an admitted product focus observation.",
  "editor.previewBasisDigest": "The exact preview-basis source facts and canonical encoder prerequisites are not all frozen.",
  "editor.problemPanelBytes": "Exact grouped problem-panel projection and serialization require a new atomic contract or admitted product observation.",
  "editor.canonicalBaselineBytes": "The exact server canonical facts, complete working-copy bytes, and projection encoder prerequisites are not all frozen.",
  "editor.compatibilityOriginalDigest": "The exact original compatibility payload bytes required for this digest are not frozen in the semantic fixture.",
  "editor.normalizationSummaryBytes": "The exact before/after bytes, category counts, and summary encoder prerequisites are not all frozen.",
  "editor.recoveryDraftEnvelopeBytes": "The exact recovery digest, base facts, and envelope encoder prerequisites are not all frozen.",
  "editor.workingCopyDigest": "The complete resulting working-copy DesignDSL bytes required for this digest are not uniquely derivable from the abstract action plan."
});

function buildSemanticProjectionContract() {
  return {
    artifactVersion: "renderweave-editor-semantic-projection-contract/1.1",
    status: "FROZEN_PLANNING_PROJECTION_INTERFACE_PRODUCT_ADAPTER_PENDING",
    executionClass: EXECUTION_CLASS,
    module: {
      name: "EditorSemanticProjection",
      interface: "project one settled semantic candidate action into a closed command-result code or preview-eligibility generation, or report the exact content prerequisites still required for a byte-derived target",
      seam: "the projection consumes only candidateId, immutable semantic fixture identity, exact named action and fault semantics, and settled product-visible outcome; DOM, framework state, transport, persistence rows, raw business values, and guessed bytes are excluded",
      adapter: "a future admitted product adapter must expose the same projection without changing candidate semantics or treating this planning catalog as a product observation"
    },
    commandResult: {
      allowedCodes: [
        "COMMAND_APPLIED",
        "COMMAND_REJECTED_ATOMIC",
        "BINDING_UNBOUND",
        "BINDING_PRESENT",
        "BINDING_ABSENT",
        "BINDING_ERROR",
        "BINDING_TYPE_INVALID",
        "BINDING_PROPERTY_INVALID"
      ],
      rule: "A successful local structural or binding-removal command projects COMMAND_APPLIED; a fail-closed atomic rejection projects COMMAND_REJECTED_ATOMIC; a pure Binding inspection projects its one exact closed Binding state code."
    },
    previewEligibilityGeneration: {
      initialGenerationRule: "Use input.parameters.currentGeneration when explicitly present; otherwise the isolated semantic fixture starts at exact generation 0. This is an explicit acceptance-fixture default, not ambient framework state.",
      transitionRule: "Add exactly one for each preview display-eligibility invalidation or new-operation generation transition named by the candidate action/fault script; inspection and discarded late delivery add zero.",
      forbiddenInference: "Do not read a framework counter, wall clock, operation ordinal, prior browser session, or hidden setup; do not reset an explicit currentGeneration parameter."
    },
    workingCopyAvailability: {
      digestPresent: "Only a trusted canonical DesignDSL working copy has editor.workingCopyDigest.",
      digestAbsent: "Raw Repair preserves malformed raw bytes and Compatibility Read-only preserves unsupported original bytes, but neither mode constructs a trusted canonical DesignDSL working copy; the digest is therefore exactly ABSENT.",
      forbiddenSubstitution: "Never hash malformed raw import bytes or unsupported compatibility-original bytes as though they were a canonical DesignDSL working copy."
    },
    contentPrerequisites: CONTENT_PREREQUISITE_CATALOG,
    noInferenceRule: "A byte- or digest-derived target remains pending until every listed prerequisite is exact; placeholder DesignDSL, synthetic digest, guessed revision, generic empty object, or UI convention is forbidden.",
    boundary: "This contract freezes a narrow acceptance projection only. It is not product code, an observation adapter implementation, a browser result, a formal Oracle, J1 evidence, or READY evidence."
  };
}

function buildSemanticProjectionCatalog(indexedDefinitions) {
  const bindings = new Map();
  const decisions = [];
  const uiCounts = new Map();
  let sourcePendingCount = 0;
  let priorMechanicalExactCount = 0;
  let semanticInputPendingCount = 0;
  let commandCount = 0;
  let generationCount = 0;
  let contentCount = 0;
  for (const { definition, candidateId } of indexedDefinitions) {
    const assertions = assertionTemplates(definition).map((entry, index) => ({ planAssertionId: `PA${String(index + 1).padStart(3, "0")}`, ...entry }));
    for (const assertion of assertions) {
      if (!assertion.expectation.status.startsWith("PENDING")) continue;
      sourcePendingCount += 1;
      const probeId = assertion.probeBinding.probeId;
      if ((probeId === "operation.terminalsBytes" || probeId === "editor.networkRequestSequence") && assertion.expectation.plannedSemanticValue !== undefined) {
        priorMechanicalExactCount += 1;
        continue;
      }
      semanticInputPendingCount += 1;
      if (UI_OBSERVED_PROBES.has(probeId)) {
        uiCounts.set(probeId, (uiCounts.get(probeId) ?? 0) + 1);
        continue;
      }
      const bindingKey = `${candidateId}::${assertion.planAssertionId}`;
      const decisionBase = {
        candidateId,
        planAssertionId: assertion.planAssertionId,
        scenarioSlug: definition.slug,
        probeId,
        operator: assertion.operator,
        bindingKey: assertion.expectation.bindingKey,
        originalStatus: assertion.expectation.status
      };
      if (probeId === "editor.commandResult") {
        if (!Object.hasOwn(COMMAND_RESULT_BY_SLUG, definition.slug)) throw new Error(`command-result semantic projection missing: ${definition.slug}`);
        const value = COMMAND_RESULT_BY_SLUG[definition.slug];
        const exact = { status: "EXACT_LITERAL", expected: { kind: "LITERAL", value }, resolution: "EXACT_COMMAND_RESULT_CODE" };
        bindings.set(bindingKey, exact);
        decisions.push({ ...decisionBase, resolution: exact.resolution, resultStatus: exact.status, expected: exact.expected });
        commandCount += 1;
      } else if (probeId === "editor.previewEligibilityGeneration") {
        if (!Object.hasOwn(PREVIEW_GENERATION_DELTA_BY_SLUG, definition.slug)) throw new Error(`preview-generation semantic projection missing: ${definition.slug}`);
        const initialGeneration = definition.input.parameters.currentGeneration ?? 0;
        const generationDelta = PREVIEW_GENERATION_DELTA_BY_SLUG[definition.slug];
        if (!Number.isSafeInteger(initialGeneration) || initialGeneration < 0) throw new Error(`invalid initial preview generation: ${definition.slug}`);
        const value = initialGeneration + generationDelta;
        const exact = { status: "EXACT_LITERAL", expected: { kind: "LITERAL", value }, resolution: "EXACT_PREVIEW_ELIGIBILITY_GENERATION" };
        bindings.set(bindingKey, exact);
        decisions.push({ ...decisionBase, resolution: exact.resolution, resultStatus: exact.status, initialGeneration, generationDelta, expected: exact.expected });
        generationCount += 1;
      } else {
        const prerequisiteIds = CONTENT_PREREQUISITES_BY_PROBE[probeId];
        if (!prerequisiteIds) throw new Error(`non-UI semantic projection classification missing: ${probeId}`);
        decisions.push({
          ...decisionBase,
          resolution: "WAIT_FOR_EXACT_CONTENT_PREREQUISITES",
          resultStatus: assertion.expectation.status,
          prerequisiteIds,
          reason: TARGET_PENDING_REASON[probeId]
        });
        contentCount += 1;
      }
    }
  }
  const excludedUiObservedByProbe = [...uiCounts].sort(([left], [right]) => left.localeCompare(right)).map(([probeId, count]) => ({ probeId, count }));
  const excludedUiObservedCount = excludedUiObservedByProbe.reduce((sum, entry) => sum + entry.count, 0);
  if (sourcePendingCount !== 308 || priorMechanicalExactCount !== 146 || semanticInputPendingCount !== 162 || excludedUiObservedCount !== 62 || decisions.length !== 100 || commandCount !== 22 || generationCount !== 20 || contentCount !== 58 || bindings.size !== 42) {
    throw new Error(`semantic projection inventory drift: source=${sourcePendingCount} prior=${priorMechanicalExactCount} input=${semanticInputPendingCount} ui=${excludedUiObservedCount} decisions=${decisions.length} command=${commandCount} generation=${generationCount} content=${contentCount} bindings=${bindings.size}`);
  }
  return {
    catalog: {
      artifactVersion: "renderweave-editor-semantic-projection-catalog/1.1",
      status: "42_EXACT_SEMANTIC_LITERALS_58_CONTENT_PREREQUISITES_62_UI_OBSERVATIONS_EXCLUDED",
      executionClass: EXECUTION_CLASS,
      contract: artifact(SEMANTIC_PROJECTION_CONTRACT_PATH),
      counts: {
        sourcePendingExpectationCount: sourcePendingCount,
        priorMechanicalExactCount,
        semanticInputPendingExpectationCount: semanticInputPendingCount,
        excludedUiObservedCount,
        nonUiDecisionCount: decisions.length,
        exactCommandResultCount: commandCount,
        exactPreviewGenerationCount: generationCount,
        exactLiteralBindingCount: bindings.size,
        contentDerivedPendingCount: contentCount
      },
      excludedUiObservedByProbe,
      decisions,
      boundary: "Only 42 non-UI literals are mechanically closed. Fifty-eight content-derived assertions retain exact prerequisite edges; two modes with no trusted DesignDSL working copy bind editor.workingCopyDigest to exact ABSENT instead. Sixty-two accessibility/focus/problem-panel observations are deliberately excluded and remain fail-closed; no product observation occurred."
    },
    bindings
  };
}

function buildContentSourceContract() {
  return {
    artifactVersion: "renderweave-editor-content-source-contract/1.1",
    profileId: "renderweave-editor-content-source/1.1",
    status: "FROZEN_CONTENT_FACT_SOURCE_INTERFACE_ONE_IMMUTABLE_SPEC_FIXTURE_BOUND",
    executionClass: EXECUTION_CLASS,
    module: {
      name: "EditorContentSource",
      interface: "resolve one source slot to verified canonical DesignDSL and, when required, trusted canonical-baseline facts through one closed result union",
      seam: "candidate generation, target binding, and a future product runner consume the same source result; baseline labels, revision deltas, action names, DOM state, transport internals, persistence rows, and cross-execution-class artifacts cannot substitute for source facts",
      adapters: [
        {
          adapterKind: "IMMUTABLE_SPEC_FIXTURE",
          authority: "an immutable Editor-owned specification fixture containing exact canonical DesignDSL bytes and a closed state proof; it freezes planned fixture content but proves no product behavior"
        },
        {
          adapterKind: "ADMITTED_PRODUCT_CAPTURE",
          authority: "an admitted product target capture containing the exact canonical response or trusted current facts and exact canonical DesignDSL bytes"
        }
      ]
    },
    sourceKinds: [
      {
        sourceKind: "WORKING_COPY_CANONICAL_DESIGN_DSL",
        requiredFacts: ["canonicalDesignDslArtifact", "workingCopyDigest"],
        resultRule: "workingCopyDigest is SHA-256 over the exact renderweave-design-c14n/1.0 canonical DesignDSL bytes, with sha256:<64 lowercase hex> wire"
      },
      {
        sourceKind: "CANONICAL_BASELINE",
        requiredFacts: ["revision", "contentHash", "canonicalDesignDslArtifact", "workingCopyDigest", "canonicalBaselineProjectionArtifact"],
        resultRule: "revision, contentHash, canonical DesignDSL bytes, workingCopyDigest, and projection bytes are one coherent trusted baseline result"
      }
    ],
    bindingUnion: {
      UNBOUND: {
        exactMembersInOrder: ["status", "reasonCode"],
        reasonCode: "EXACT_EDITOR_CONTENT_SOURCE_ARTIFACT_MISSING"
      },
      EXACT_SOURCE: {
        exactMembersInOrder: ["status", "adapterKind", "sourceRecordArtifact"],
        adapterKinds: ["IMMUTABLE_SPEC_FIXTURE", "ADMITTED_PRODUCT_CAPTURE"],
        sourceRecordArtifactMembersInOrder: ["path", "sha256", "byteLength"]
      }
    },
    sourceRecord: {
      profile: "renderweave-editor-content-source-record/1.0",
      exactMembersInOrder: ["artifactVersion", "sourceSlotId", "candidateId", "planAssertionId", "executionClass", "sourceKind", "adapterKind", "acquisitionMode", "semanticInputFixture", "stateProof", "result", "boundary"],
      resultMembersBySourceKind: {
        WORKING_COPY_CANONICAL_DESIGN_DSL: ["canonicalDesignDslArtifact", "workingCopyDigest"],
        CANONICAL_BASELINE: ["revision", "contentHash", "canonicalDesignDslArtifact", "workingCopyDigest", "canonicalBaselineProjectionArtifact"]
      },
      immutableSpecFixtureProof: {
        proofKind: "DIRTY_GUARD_ATOMIC_REJECTION",
        exactMembersInOrder: ["proofKind", "canonicalBaseline", "preActionWorkingCopy", "dirtyStateRule", "expectedTerminal", "postActionRule"],
        canonicalBaselineMembersInOrder: ["revision", "contentHash", "workingCopyDigest", "canonicalDesignDslArtifact"],
        preActionWorkingCopyMembersInOrder: ["canonicalDesignDslArtifact", "workingCopyDigest"],
        expectedTerminalMembersInOrder: ["operationId", "outcome", "code"],
        postActionRule: "BYTE_IDENTICAL_TO_PRE_ACTION"
      }
    },
    canonicalFacts: {
      designDslCanonicalProfile: "renderweave-design-c14n/1.0",
      workingCopyDigest: "sha256(exact canonical DesignDSL bytes), encoded as sha256:<64 lowercase hex>",
      contentHash: "sha256(UTF-8(\"renderweave-design-content/1\\0\") + exact canonical DesignDSL bytes), encoded as sha256:<64 lowercase hex>",
      revision: "exact trusted nonnegative integer returned by the canonical response or trusted current; revisionDelta and baseline labels are never absolute revision facts",
      canonicalBaselineProjection: {
        profile: "renderweave-editor-canonical-baseline-projection/1.0",
        exactMembersInOrder: ["profile", "revision", "contentHash", "workingCopyDigest"],
        encoding: "UTF-8 strict JSON, no BOM, exact member order, ':' and ',' separators, no insignificant whitespace, shortest nonnegative base-10 revision, one final LF"
      }
    },
    sourceSlotRule: "Each pending first-layer content decision receives exactly one source slot. A source artifact may be reused only when exact bytes and every baseline fact are identical and the reuse remains inside EXEC::EDITOR_AUTOMATED::1.0.",
    forbiddenInference: [
      "derive DesignDSL bytes from baselineId, scenario title, action name, semantic parameter, or expected outcome",
      "derive absolute revision from revisionDelta",
      "invent contentHash, workingCopyDigest, canonical response, placeholder DesignDSL, or empty object",
      "reuse a DesignDSL baseline or capture from another execution class",
      "treat the semantic input fixture as exact DesignDSL or product-captured content"
    ],
    boundary: "This is a specification-only content-fact interface. An IMMUTABLE_SPEC_FIXTURE may freeze Editor-owned canonical source bytes and an expected state proof, but it creates no product adapter, product capture, browser observation, formal Case or Oracle, J1 evidence, or READY evidence."
  };
}

function buildContentSourceCatalog(indexedDefinitions, semanticProjectionCatalog) {
  const definitionByCandidateId = new Map(indexedDefinitions.map((entry) => [entry.candidateId, entry.definition]));
  const bindings = new Map();
  const sourceRecords = [];
  const supportingArtifacts = [];
  const slots = semanticProjectionCatalog.decisions
    .filter((decision) => decision.resolution === "WAIT_FOR_EXACT_CONTENT_PREREQUISITES" && ["editor.workingCopyDigest", "editor.canonicalBaselineBytes"].includes(decision.probeId))
    .map((decision) => {
      const definition = definitionByCandidateId.get(decision.candidateId);
      if (!definition) throw new Error(`content source definition missing: ${decision.candidateId}`);
      const sourceKind = decision.probeId === "editor.workingCopyDigest"
        ? "WORKING_COPY_CANONICAL_DESIGN_DSL"
        : "CANONICAL_BASELINE";
      const writes = definition.expected.writeCount ?? (definition.profile === "SAVE_SUCCESS" ? 1 : 0);
      const sourceSlotId = `ECS::${decision.candidateId.slice("EDC::".length)}::${decision.planAssertionId}`;
      const isDirtyGuardFixture = sourceSlotId === DIRTY_GUARD_SOURCE_SLOT_ID;
      const acquisitionMode = isDirtyGuardFixture
        ? "ATOMIC_REJECTION_PRESERVES_INITIAL_WORKING_COPY"
        : sourceKind === "WORKING_COPY_CANONICAL_DESIGN_DSL"
        ? "POST_SETTLED_ACTION_WORKING_COPY"
        : writes > 0
          ? "SERVER_CANONICAL_RESPONSE"
          : "EXISTING_TRUSTED_BASELINE";
      let binding = { status: "UNBOUND", reasonCode: "EXACT_EDITOR_CONTENT_SOURCE_ARTIFACT_MISSING" };
      if (isDirtyGuardFixture) {
        if (decision.candidateId !== DIRTY_GUARD_CANDIDATE_ID || decision.planAssertionId !== DIRTY_GUARD_PLAN_ASSERTION_ID || sourceKind !== "WORKING_COPY_CANONICAL_DESIGN_DSL" || definition.slug !== "dirty-guard-blocks-replacement" || stable(definition.steps) !== stable([{ action: "ATTEMPT_REPLACE_WORKING_DRAFT", parameters: { source: "IMPORT" } }]) || stable(terminalProjection(definition.terminals[0])) !== stable({ operationId: "replaceWorkingDraft", outcome: "NONTERMINAL_REJECTION", code: "EDITOR_DIRTY_DRAFT_REPLACEMENT_BLOCKED" })) {
          throw new Error(`dirty-guard source fixture semantic boundary drift: ${stable({ decision, definition })}`);
        }
        const designRoot = {
          nodeId: "00000000-0000-4000-8000-000000000001",
          kind: "canvas",
          widthMm: 210,
          heightMm: 297,
          bindings: [],
          children: []
        };
        const cleanDesign = {
          dslVersion: "renderweave-design/1.0",
          expressionProfile: "renderweave-expression/1.0",
          displayName: "Clean Baseline",
          definitions: [],
          designRoot
        };
        const dirtyDesign = { ...cleanDesign, displayName: "Dirty Before Replacement" };
        const cleanBytes = designBytes(cleanDesign);
        const dirtyBytes = designBytes(dirtyDesign);
        writeBytes(DIRTY_GUARD_CLEAN_DESIGN_PATH, cleanBytes);
        writeBytes(DIRTY_GUARD_WORKING_DESIGN_PATH, dirtyBytes);
        const cleanWorkingCopyDigest = sha(cleanBytes);
        const workingCopyDigest = sha(dirtyBytes);
        const cleanContentHash = sha(Buffer.concat([Buffer.from("renderweave-design-content/1\0", "utf8"), cleanBytes]));
        if (cleanWorkingCopyDigest === workingCopyDigest || cleanContentHash === workingCopyDigest) throw new Error("dirty-guard fixture identities must remain distinct");
        const sourceRecord = {
          artifactVersion: "renderweave-editor-content-source-record/1.0",
          sourceSlotId,
          candidateId: decision.candidateId,
          planAssertionId: decision.planAssertionId,
          executionClass: EXECUTION_CLASS,
          sourceKind,
          adapterKind: "IMMUTABLE_SPEC_FIXTURE",
          acquisitionMode,
          semanticInputFixture: artifact(inputFixturePath(decision.candidateId)),
          stateProof: {
            proofKind: "DIRTY_GUARD_ATOMIC_REJECTION",
            canonicalBaseline: {
              revision: 0,
              contentHash: cleanContentHash,
              workingCopyDigest: cleanWorkingCopyDigest,
              canonicalDesignDslArtifact: artifact(DIRTY_GUARD_CLEAN_DESIGN_PATH)
            },
            preActionWorkingCopy: {
              canonicalDesignDslArtifact: artifact(DIRTY_GUARD_WORKING_DESIGN_PATH),
              workingCopyDigest
            },
            dirtyStateRule: "PRE_ACTION_WORKING_COPY_DIGEST_DIFFERS_FROM_CANONICAL_BASELINE_DIGEST",
            expectedTerminal: {
              operationId: "replaceWorkingDraft",
              outcome: "NONTERMINAL_REJECTION",
              code: "EDITOR_DIRTY_DRAFT_REPLACEMENT_BLOCKED"
            },
            postActionRule: "BYTE_IDENTICAL_TO_PRE_ACTION"
          },
          result: {
            canonicalDesignDslArtifact: artifact(DIRTY_GUARD_WORKING_DESIGN_PATH),
            workingCopyDigest
          },
          boundary: "Editor-owned immutable planning fixture and expected atomic-rejection state proof only; no product action, capture, browser observation, formal record, J1, or READY evidence."
        };
        write(DIRTY_GUARD_SOURCE_RECORD_PATH, sourceRecord);
        const sourceRecordArtifact = artifact(DIRTY_GUARD_SOURCE_RECORD_PATH);
        binding = { status: "EXACT_SOURCE", adapterKind: "IMMUTABLE_SPEC_FIXTURE", sourceRecordArtifact };
        sourceRecords.push({ sourceSlotId, candidateId: decision.candidateId, planAssertionId: decision.planAssertionId, artifact: sourceRecordArtifact });
        supportingArtifacts.push(
          { role: "CANONICAL_BASELINE_DESIGN_DSL", artifact: artifact(DIRTY_GUARD_CLEAN_DESIGN_PATH) },
          { role: "PRE_AND_POST_ACTION_WORKING_COPY_DESIGN_DSL", artifact: artifact(DIRTY_GUARD_WORKING_DESIGN_PATH) }
        );
        bindings.set(`${decision.candidateId}::${decision.planAssertionId}`, {
          status: "EXACT_LITERAL",
          expected: { kind: "LITERAL", value: workingCopyDigest },
          resolution: "EXACT_WORKING_COPY_DIGEST_FROM_CONTENT_SOURCE",
          sourceSlotId,
          sourceRecordArtifact
        });
      }
      return {
        sourceSlotId,
        candidateId: decision.candidateId,
        planAssertionId: decision.planAssertionId,
        scenarioSlug: decision.scenarioSlug,
        probeId: decision.probeId,
        sourceKind,
        acquisitionMode,
        semanticInputFixture: artifact(inputFixturePath(decision.candidateId)),
        prerequisiteIds: decision.prerequisiteIds,
        binding
      };
    });
  const count = (key, value) => slots.filter((entry) => entry[key] === value).length;
  if (slots.length !== 47 || new Set(slots.map((entry) => entry.sourceSlotId)).size !== 47 || count("sourceKind", "WORKING_COPY_CANONICAL_DESIGN_DSL") !== 35 || count("sourceKind", "CANONICAL_BASELINE") !== 12 || count("acquisitionMode", "SERVER_CANONICAL_RESPONSE") !== 10 || count("acquisitionMode", "EXISTING_TRUSTED_BASELINE") !== 2) {
    throw new Error(`content source slot inventory drift: ${stable(slots)}`);
  }
  if (sourceRecords.length !== 1 || supportingArtifacts.length !== 2 || bindings.size !== 1) throw new Error("dirty-guard content source binding inventory drift");
  return {
    catalog: {
      artifactVersion: "renderweave-editor-content-source-catalog/1.1",
      status: "ONE_IMMUTABLE_SPEC_FIXTURE_BOUND_46_SOURCE_SLOTS_UNBOUND",
      executionClass: EXECUTION_CLASS,
      contract: artifact(CONTENT_SOURCE_CONTRACT_PATH),
      semanticProjectionCatalog: artifact(SEMANTIC_PROJECTION_CATALOG_PATH),
      counts: {
        sourceSlotCount: slots.length,
        workingCopySourceSlotCount: count("sourceKind", "WORKING_COPY_CANONICAL_DESIGN_DSL"),
        canonicalBaselineSourceSlotCount: count("sourceKind", "CANONICAL_BASELINE"),
        postSettledActionWorkingCopySlotCount: count("acquisitionMode", "POST_SETTLED_ACTION_WORKING_COPY"),
        atomicRejectionFixtureSlotCount: count("acquisitionMode", "ATOMIC_REJECTION_PRESERVES_INITIAL_WORKING_COPY"),
        serverCanonicalResponseSlotCount: count("acquisitionMode", "SERVER_CANONICAL_RESPONSE"),
        existingTrustedBaselineSlotCount: count("acquisitionMode", "EXISTING_TRUSTED_BASELINE"),
        exactSourceBindingCount: slots.filter((entry) => entry.binding.status === "EXACT_SOURCE").length,
        unboundSourceBindingCount: slots.filter((entry) => entry.binding.status === "UNBOUND").length,
        sourceRecordArtifactCount: sourceRecords.length,
        canonicalDesignDslArtifactCount: supportingArtifacts.length,
        sourceArtifactCount: sourceRecords.length + supportingArtifacts.length
      },
      sourceRecords,
      supportingArtifacts,
      slots,
      boundary: "Exactly one Editor-owned immutable dirty-guard fixture freezes canonical DesignDSL bytes and an expected atomic-rejection state proof. The remaining 46 source slots stay UNBOUND; no product adapter, product capture, browser execution, formal record, J1, or READY evidence exists."
    },
    bindings
  };
}

function buildTargetBindingContract() {
  return {
    artifactVersion: "renderweave-editor-target-binding-contract/1.3",
    status: "FROZEN_SEMANTIC_TARGET_RESOLUTION_INTERFACE_PRODUCT_OBSERVATION_PENDING",
    executionClass: EXECUTION_CLASS,
    module: {
      name: "EditorTargetBinding",
      interface: "resolve a pending assertion only when one exact expected literal or immutable artifact follows mechanically from already frozen candidate semantics",
      seam: "target resolution consumes candidate terminal, network, EditorSemanticProjection, and verified EditorContentSource decisions only; it cannot inspect or invent DOM, accessibility, focus, raw content, transport timing, or persistence internals",
      adapter: "future product observations must match these exact expectations and separately bind every still-pending assertion before formal Oracle issuance"
    },
    semanticProjectionContract: artifact(SEMANTIC_PROJECTION_CONTRACT_PATH),
    semanticProjectionCatalog: artifact(SEMANTIC_PROJECTION_CATALOG_PATH),
    contentSourceContract: artifact(CONTENT_SOURCE_CONTRACT_PATH),
    contentSourceCatalog: artifact(CONTENT_SOURCE_CATALOG_PATH),
    resolutionRules: [
      {
        ruleId: "EXACT_TERMINAL_VECTOR_ARTIFACT",
        probeId: "operation.terminalsBytes",
        from: "the candidate's one exact terminal projected to operationId, outcome, optional code, and optional stage",
        expectationStatus: "EXACT_ARTIFACT",
        artifactProfile: TERMINAL_TARGET_PROFILE,
        encoding: "UTF-8 JSON, two-space indentation, LF line endings, one final LF, root array, member order operationId/outcome/code?/stage?",
        mediaType: "application/json"
      },
      {
        ruleId: "EXACT_NETWORK_REQUEST_SEQUENCE_LITERAL",
        probeId: "editor.networkRequestSequence",
        from: "the already frozen ordered product-boundary action projection",
        expectationStatus: "EXACT_LITERAL"
      },
      {
        ruleId: "EXACT_COMMAND_RESULT_CODE",
        probeId: "editor.commandResult",
        from: "the closed EditorSemanticProjection command-result catalog",
        expectationStatus: "EXACT_LITERAL"
      },
      {
        ruleId: "EXACT_PREVIEW_ELIGIBILITY_GENERATION",
        probeId: "editor.previewEligibilityGeneration",
        from: "the explicit fixture generation start plus the closed candidate transition delta",
        expectationStatus: "EXACT_LITERAL"
      },
      {
        ruleId: "EXACT_WORKING_COPY_DIGEST_FROM_CONTENT_SOURCE",
        probeId: "editor.workingCopyDigest",
        from: "one EXACT_SOURCE EditorContentSource result whose canonical DesignDSL artifact bytes independently replay to the same SHA-256 digest",
        expectationStatus: "EXACT_LITERAL"
      }
    ],
    unresolvedProbeReasons: TARGET_PENDING_REASON,
    noInferenceRule: "A pending expectation without one applicable exact rule remains pending; similarity, UI convention, placeholder bytes, a generic default, or a guessed digest is forbidden.",
    boundary: "Exact target bindings are acceptance-contract decisions, not product observations. Remaining pending targets stay fail-closed, and all exact targets still require independent product replay."
  };
}

function buildTargetBindingCatalog(indexedDefinitions, semanticBindings, contentSourceBindings) {
  const bindings = new Map();
  const decisions = [];
  const artifacts = [];
  for (const { definition, candidateId } of indexedDefinitions) {
    const assertions = assertionTemplates(definition).map((entry, index) => ({ planAssertionId: `PA${String(index + 1).padStart(3, "0")}`, ...entry }));
    for (const assertion of assertions) {
      const expectation = assertion.expectation;
      if (!expectation.status.startsWith("PENDING")) continue;
      const decisionBase = {
        candidateId,
        planAssertionId: assertion.planAssertionId,
        probeId: assertion.probeBinding.probeId,
        operator: assertion.operator,
        bindingKey: expectation.bindingKey,
        originalStatus: expectation.status
      };
      const bindingKey = `${candidateId}::${assertion.planAssertionId}`;
      const semanticBinding = semanticBindings.get(bindingKey);
      if (semanticBinding) {
        const exactExpectation = { status: semanticBinding.status, expected: semanticBinding.expected };
        bindings.set(bindingKey, exactExpectation);
        decisions.push({
          ...decisionBase,
          resolution: semanticBinding.resolution,
          resultStatus: semanticBinding.status,
          expected: semanticBinding.expected,
          semanticProjectionDecision: { catalogPath: SEMANTIC_PROJECTION_CATALOG_PATH, candidateId, planAssertionId: assertion.planAssertionId }
        });
      } else if (contentSourceBindings.has(bindingKey)) {
        const contentSourceBinding = contentSourceBindings.get(bindingKey);
        const exactExpectation = { status: contentSourceBinding.status, expected: contentSourceBinding.expected };
        bindings.set(bindingKey, exactExpectation);
        decisions.push({
          ...decisionBase,
          resolution: contentSourceBinding.resolution,
          resultStatus: contentSourceBinding.status,
          expected: contentSourceBinding.expected,
          sourceSlotId: contentSourceBinding.sourceSlotId,
          sourceRecordArtifact: contentSourceBinding.sourceRecordArtifact
        });
      } else if (assertion.probeBinding.probeId === "operation.terminalsBytes" && expectation.plannedSemanticValue !== undefined) {
        const relativePath = targetArtifactPath(candidateId, assertion.planAssertionId);
        write(relativePath, expectation.plannedSemanticValue);
        const boundArtifact = artifact(relativePath);
        const expected = {
          kind: "ARTIFACT",
          artifactPath: relativePath,
          mediaType: "application/json",
          artifactSha256: boundArtifact.sha256
        };
        bindings.set(bindingKey, { status: "EXACT_ARTIFACT", expected });
        artifacts.push({ candidateId, planAssertionId: assertion.planAssertionId, probeId: assertion.probeBinding.probeId, artifact: boundArtifact });
        decisions.push({ ...decisionBase, resolution: "EXACT_TERMINAL_VECTOR_ARTIFACT", resultStatus: "EXACT_ARTIFACT", expected });
      } else if (assertion.probeBinding.probeId === "editor.networkRequestSequence" && expectation.plannedSemanticValue !== undefined) {
        const exactExpectation = { status: "EXACT_LITERAL", expected: { kind: "LITERAL", value: expectation.plannedSemanticValue } };
        bindings.set(bindingKey, exactExpectation);
        decisions.push({ ...decisionBase, resolution: "EXACT_NETWORK_REQUEST_SEQUENCE_LITERAL", resultStatus: "EXACT_LITERAL", expected: exactExpectation.expected });
      } else {
        const reason = TARGET_PENDING_REASON[assertion.probeBinding.probeId];
        if (!reason) throw new Error(`pending target reason missing: ${assertion.probeBinding.probeId}`);
        decisions.push({ ...decisionBase, resolution: "REMAIN_PENDING_FAIL_CLOSED", resultStatus: expectation.status, reason });
      }
    }
  }
  const count = (status) => decisions.filter((entry) => entry.resultStatus === status).length;
  const pendingByProbe = [...new Set(decisions.filter((entry) => entry.resultStatus.startsWith("PENDING")).map((entry) => entry.probeId))]
    .sort()
    .map((probeId) => ({ probeId, count: decisions.filter((entry) => entry.probeId === probeId && entry.resultStatus.startsWith("PENDING")).length, reason: TARGET_PENDING_REASON[probeId] }));
  return {
    catalog: {
      artifactVersion: "renderweave-editor-target-binding-catalog/1.3",
      status: "189_EXACT_TARGETS_BOUND_119_UNDERDETERMINED_FAIL_CLOSED",
      executionClass: EXECUTION_CLASS,
      contract: artifact(TARGET_BINDING_CONTRACT_PATH),
      counts: {
        originalPendingExpectationCount: decisions.length,
        exactArtifactBindingCount: count("EXACT_ARTIFACT"),
        exactLiteralBindingCount: count("EXACT_LITERAL"),
        exactBindingCount: count("EXACT_ARTIFACT") + count("EXACT_LITERAL"),
        remainingPendingLiteralCount: count("PENDING_TARGET_LITERAL"),
        remainingPendingArtifactCount: count("PENDING_TARGET_ARTIFACT"),
        remainingPendingCount: decisions.filter((entry) => entry.resultStatus.startsWith("PENDING")).length,
        targetArtifactCount: artifacts.length,
        orphanTargetArtifactCount: 0
      },
      pendingByProbe,
      artifacts,
      decisions,
      boundary: "The 189 mechanically determined expectations are exact planning targets only, including one digest replayed from an immutable EditorContentSource fixture. Two no-working-copy modes bind exact ABSENT outside this pending-target catalog. The remaining 119 split into 57 unresolved content-derived prerequisite graphs and 62 UI observations; no value was fabricated and no product observation, browser execution, formal Oracle issuance, J1, or READY evidence occurred."
    },
    bindings
  };
}

function buildFaultScheduleContract(indexedDefinitions) {
  const signatures = new Map();
  for (const { definition } of indexedDefinitions) {
    for (const event of definition.fault.events) {
      const signature = {
        at: event.at,
        action: event.action,
        parameterMembers: Object.keys(event.parameters).sort()
      };
      signatures.set(stable(signature), signature);
    }
  }
  return {
    artifactVersion: "renderweave-editor-fault-schedule-contract/1.0",
    profileId: FAULT_ARTIFACT_PROFILE,
    status: "FROZEN_PLANNING_ARTIFACT_INTERFACE_PRODUCT_ADAPTER_PENDING",
    executionClass: EXECUTION_CLASS,
    module: {
      name: "EditorFaultSchedule",
      interface: "one immutable SHA-256-bound artifact selecting an ordered exact-once sequence at named Editor seams",
      seam: "the future admitted runner adapter maps only a catalogued artifact hash to product-owned injection points; candidates never address DOM, transport implementation, persistence internals, clocks, or arbitrary scripts",
      adapter: "a product runner adapter must consume every event once in array order and fail the run on missing, duplicate, reordered, or unrecognized injection"
    },
    artifactMembersInOrder: ["artifactVersion", "candidateId", "executionClass", "events", "expectedTerminal"],
    eventMembersInOrder: ["at", "action", "parameters"],
    eventOrderSemantic: true,
    exactOnce: true,
    allowedEventSignatures: [...signatures.values()].sort((left, right) => Buffer.compare(Buffer.from(stable(left)), Buffer.from(stable(right)))),
    forbiddenMechanisms: ["wildcard", "regex trigger", "arbitrary script", "ambient time", "ambient entropy", "network lookup", "hidden default", "unspecified extra event"],
    evolutionRule: "Any new event point, action, parameter member, ordering rule, or injection semantic requires a new exact fault schedule Profile.",
    boundary: "These artifacts define deterministic planning stimuli only; they do not prove a product seam, runner adapter, browser execution, injected fault, or observed terminal."
  };
}

function buildFaultScheduleCatalog(indexedDefinitions) {
  const noneBase = { kind: "NONE" };
  const noneBinding = { ...noneBase, identitySha256: formalFaultIdentity(noneBase) };
  const bindings = new Map();
  const artifacts = [];
  for (const { definition, candidateId } of indexedDefinitions) {
    if (definition.fault.kind === "NONE") {
      bindings.set(candidateId, noneBinding);
      continue;
    }
    const relativePath = faultArtifactPath(candidateId);
    const value = {
      artifactVersion: FAULT_ARTIFACT_PROFILE,
      candidateId,
      executionClass: EXECUTION_CLASS,
      events: definition.fault.events,
      expectedTerminal: terminalProjection(definition.terminals[0])
    };
    write(relativePath, value);
    const boundArtifact = artifact(relativePath);
    const formalBase = { kind: "ARTIFACT", artifactPath: relativePath, artifactSha256: boundArtifact.sha256 };
    const formalBinding = { ...formalBase, identitySha256: formalFaultIdentity(formalBase) };
    bindings.set(candidateId, formalBinding);
    artifacts.push({ candidateId, scenarioSlug: definition.slug, artifact: boundArtifact, formalBinding });
  }
  const catalog = {
    artifactVersion: "renderweave-editor-fault-schedule-catalog/1.0",
    status: "EXACT_PLANNING_ARTIFACTS_PRODUCT_ADAPTER_UNPROVEN",
    executionClass: EXECUTION_CLASS,
    contract: artifact(FAULT_CONTRACT_PATH),
    formalNoneBinding: noneBinding,
    counts: {
      candidateCount: indexedDefinitions.length,
      noneCandidateCount: indexedDefinitions.length - artifacts.length,
      artifactCandidateCount: artifacts.length,
      orphanArtifactCount: 0
    },
    artifacts,
    boundary: "Every non-NONE planning schedule has immutable bytes and a formal fault identity candidate; no product adapter, browser injection, or terminal observation has occurred."
  };
  return { catalog, bindings };
}

function contract() {
  return {
    artifactVersion: "renderweave-editor-atomic-candidate-contract/1.5",
    contractId: CANDIDATE_PROFILE,
    status: "FROZEN_PLANNING_INTERFACE_FORMAL_ISSUANCE_FORBIDDEN",
    executionClass: EXECUTION_CLASS,
    module: {
      name: "EditorAtomicCandidateDecomposition",
      interface: "journey seed to finite candidate plans with one immutable semantic input fixture, one exact fault schedule, one expected terminal vector, one closed semantic projection, an explicit content-source slot where required, and one partially exact assertion plan",
      seam: "the candidate observes only the frozen EditorAutomationAdmission boundary and named observation probes; target, runner, DOM, transport, and persistence internals cannot enter Case identity",
      adapter: "a future admitted Editor runner binds each abstract plan to one immutable product fixture, fault artifact, target literal set, and observation adapter without changing the candidate semantics"
    },
    candidateIdentity: {
      pattern: "^EDC::J(?:0[1-9]|1[0-2])::[0-9]{3}$",
      namespaceMeaning: "planning-only Editor Decomposition Candidate",
      formalCasePatternMatchAllowed: false,
      formalOraclePatternMatchAllowed: false,
      identityDigestDomain: "renderweave-editor-atomic-candidate-identity/1",
      digestMembers: ["inputPlan", "faultSchedulePlan", "expectedTerminalPlan", "assertionPlan"]
    },
    requiredCandidateMembers: [
      "candidateId", "journeySeedId", "sourceJ1CaseId", "title", "status", "stimulusPlan", "expectedTerminalPlan", "assertionPlan", "coveragePlan", "identityDigest", "blockers", "formalIssuanceAllowed"
    ],
    splitRule: "A different input plan, fault schedule, expected terminal vector, observation boundary, or assertion set always creates a different candidate.",
    inputPlan: {
      profile: INPUT_PROFILE,
      required: ["baselineId", "parameters", "actionScript", "formalBinding", "planIdentityDigest"],
      artifactContract: artifact(INPUT_FIXTURE_CONTRACT_PATH),
      artifactCatalog: artifact(INPUT_FIXTURE_CATALOG_PATH),
      preissuanceBindings: ["immutable semantic fixture artifact path, mediaType, sha256, and formal identitySha256"],
      executionBoundary: "Exact semantic fixture bytes and identity do not prove that a product adapter constructed the baseline or executed any action."
    },
    faultSchedulePlan: {
      profile: FAULT_PROFILE,
      kinds: ["NONE", "PLANNED_SEQUENCE"],
      required: ["kind", "events", "formalBinding", "planIdentityDigest"],
      artifactContract: artifact(FAULT_CONTRACT_PATH),
      artifactCatalog: artifact(FAULT_CATALOG_PATH),
      preissuanceBindings: ["formal NONE identitySha256 or immutable fault artifact path, sha256, and identitySha256"],
      executionBoundary: "Exact planning artifact bytes and identity do not prove that a product runner exposes or injected the named fault seam."
    },
    expectedTerminalPlan: {
      outcomes: ["SUCCESS", "PROBLEM", "NONTERMINAL_REJECTION"],
      terminalCount: 1,
      adjudication: artifact(TERMINAL_ADJUDICATION_PATH),
      allowedBindingStatuses: ["EXACT", "NOT_REQUIRED"],
      rule: "Every ordinary Editor candidate has exactly one terminal. Sequential save, confirmation, and preview phases use separate candidate identities. Code and stage are exact literals or an exact NOT_REQUIRED decision; generic fallback and pending terminal binding are forbidden."
    },
    assertionPlan: {
      currentProbeBinding: "CURRENT_PROFILE requires a probe exposed to EXEC::EDITOR_AUTOMATED::1.0 by renderweave-conformance-probes/1.0",
      candidateProbeBinding: "CANDIDATE_PROFILE_NOT_ISSUED binds a real probe in the complete renderweave-conformance-probes/1.1 candidate, but remains planning-only and cannot appear in an Oracle until that exact Profile is explicitly issued",
      candidateProbeProfile: artifact(CANDIDATE_PROBE_PROFILE_PATH),
      candidateProbeAdjudication: artifact(CANDIDATE_PROBE_ADJUDICATION_PATH),
      expectationStatuses: ["EXACT_LITERAL", "EXACT_ARTIFACT", "EXACT_ABSENT", "PENDING_TARGET_LITERAL", "PENDING_TARGET_ARTIFACT"],
      semanticProjectionContract: artifact(SEMANTIC_PROJECTION_CONTRACT_PATH),
      semanticProjectionCatalog: artifact(SEMANTIC_PROJECTION_CATALOG_PATH),
      contentSourceContract: artifact(CONTENT_SOURCE_CONTRACT_PATH),
      contentSourceCatalog: artifact(CONTENT_SOURCE_CATALOG_PATH),
      targetBindingContract: artifact(TARGET_BINDING_CONTRACT_PATH),
      targetBindingCatalog: artifact(TARGET_BINDING_CATALOG_PATH),
      coverageMeaning: "planned assertion linkage only; no assertion has been executed and no requirement is covered until a formal active Case and Oracle replay it"
    },
    closedBlockers: Object.values(BLOCKERS),
    issuanceGate: [
      "Every input and fault schedule is bound to immutable formal artifacts and identity digests, and an admitted product fixture adapter proves the semantic baseline/action mapping.",
      "Every expected terminal code and stage required by the scenario is exact and independently aligned with the terminal adjudication catalog.",
      "Every assertion uses an issued probe for this execution class and an exact literal or digest-bound artifact.",
      "The exact product build, browser and OS target, runner, active corpus, adapter, and independent replay are admitted.",
      "The candidate is converted to a schema-valid CONF::EDITOR_AUTOMATED identity and reusable ORC::EDITOR_AUTOMATED assertion set without mutating this planning artifact."
    ],
    appendBoundary: {
      formalCasesPath: "conformance-cases-v1.jsonl",
      formalOraclesPath: "conformance-oracles-v1.jsonl",
      appendAllowedByThisContract: false,
      candidateFilesAreNotRegistryRecords: true
    },
    j1Boundary: {
      sourceChecklistOnly: true,
      browserOrHumanExecutionPerformed: false,
      mayCountAsJ1: false
    }
  };
}

function assertionTemplates(definition) {
  const finalOutcome = definition.terminals.at(-1).outcome;
  const terminal = definition.terminals[0];
  const expected = definition.expected;
  const accepted = expected.accepted ?? (finalOutcome === "SUCCESS");
  const writes = expected.writeCount ?? (definition.profile === "SAVE_SUCCESS" ? 1 : 0);
  const renderDocuments = expected.renderDocumentCount ?? (definition.profile === "PREVIEW_SUCCESS" ? 1 : 0);
  const renderOutputs = expected.renderOutputCount ?? (definition.profile === "PREVIEW_SUCCESS" ? 1 : 0);
  const networkAction = new Map([
    ["SAVE_TEMPLATE", "SAVE_TEMPLATE"],
    ["CONFIRM_INVALID_SAVE", "SAVE_TEMPLATE"],
    ["RESTORE_TEMPLATE_REVISION", "RESTORE_TEMPLATE_REVISION"],
    ["CREATE_TEMPLATE_FROM_COMPLETE_DESIGN", "CREATE_TEMPLATE"],
    ["CONFIRM_OVERWRITE", null],
    ["READ_LATEST_CURRENT", "READ_LATEST_CURRENT"],
    ["RESUBMIT_COMPLETE_DESIGN_WITH_LATEST_REVISION", "SAVE_TEMPLATE"],
    ["READ_TRUSTED_CURRENT", "READ_TRUSTED_CURRENT"],
    ["AUTHOR_EXPLICIT_RETRY", "SAVE_TEMPLATE"],
    ["START_AUTHORITATIVE_PREVIEW", "START_AUTHORITATIVE_PREVIEW"],
    ["START_PREVIEW_AFTER_SAVE_PHASE", "START_AUTHORITATIVE_PREVIEW"],
    ["SAVE_AND_PREVIEW", "SAVE_TEMPLATE"],
    ["SAVE_AND_PREVIEW_SAVE_PHASE", "SAVE_TEMPLATE"]
  ]);
  const inferredNetwork = definition.steps.flatMap((entry) => networkAction.has(entry.action) && networkAction.get(entry.action) !== null ? [networkAction.get(entry.action)] : []);
  const network = expected.networkRequestSequence ?? inferredNetwork;
  const terminalCodeAssertion = terminal.codeBinding.status === "EXACT"
    ? lit("operation.terminalCode", "EQ", terminal.codeBinding.code)
    : terminal.codeBinding.status === "NOT_REQUIRED"
      ? exactAbsent("operation.terminalCode")
      : pending("operation.terminalCode", "EQ", `${definition.slug}.terminal-code`);
  const terminalStageAssertion = terminal.stageBinding.status === "EXACT"
    ? lit("operation.terminalStage", "EQ", terminal.stageBinding.stage)
    : terminal.stageBinding.status === "NOT_REQUIRED"
      ? exactAbsent("operation.terminalStage")
      : pending("operation.terminalStage", "EQ", `${definition.slug}.terminal-stage`);
  const assertions = [
    lit("operation.accepted", "EQ", accepted),
    terminalCodeAssertion,
    terminalStageAssertion,
    exactAbsent("operation.terminalParametersBytes"),
    pending("operation.terminalsBytes", "BYTES_EQ", `${definition.slug}.terminal-vector`, "ARTIFACT", [terminalProjection(terminal)]),
    lit("operation.writeCount", "EQ", writes),
    lit("operation.renderDocumentCount", "EQ", renderDocuments),
    lit("operation.renderOutputCount", "EQ", renderOutputs),
    network.length === 0
      ? lit("editor.networkRequestSequence", "SEQUENCE_EQ", [])
      : pending("editor.networkRequestSequence", "SEQUENCE_EQ", `${definition.slug}.network-request-sequence`, "LITERAL", network)
  ];

  if (definition.profile === "KEYBOARD") {
    assertions.push(lit("editor.keyboardTrapDetected", "EQ", false));
    assertions.push(pending("editor.focusSequence", "SEQUENCE_EQ", `${definition.slug}.focus-sequence`));
    assertions.push(pending("editor.accessibilityTreeBytes", "BYTES_EQ", `${definition.slug}.accessibility-tree`, "ARTIFACT"));
  }
  if (["LOCAL_MUTATION", "LOCAL_REJECTION"].includes(definition.profile)) {
    assertions.push(pending("editor.commandResult", "EQ", `${definition.slug}.command-result`));
    assertions.push(candidatePending("editor.workingCopyDigest", "EQ", `${definition.slug}.working-copy-digest`));
  }
  if (definition.profile === "LOCAL_MUTATION") {
    if (expected.undoDepth !== undefined) assertions.push(lit("editor.undoDepth", "EQ", expected.undoDepth));
    if (expected.redoDepth !== undefined) assertions.push(lit("editor.redoDepth", "EQ", expected.redoDepth));
  }
  if (definition.profile === "BINDING") {
    assertions.push(pending("editor.accessibilityTreeBytes", "BYTES_EQ", `${definition.slug}.accessible-state`, "ARTIFACT"));
    assertions.push(pending("editor.commandResult", "EQ", `${definition.slug}.binding-state-code`));
    if (expected.previewVisible !== undefined) assertions.push(lit("editor.previewVisible", "EQ", expected.previewVisible));
  }
  if (["PROBLEM", "CONFIRMATION", "CONFLICT", "PREVIEW_PROBLEM"].includes(definition.profile)) {
    assertions.push(pending("editor.problemPanelBytes", "BYTES_EQ", `${definition.slug}.problem-panel`, "ARTIFACT"));
  }
  if (definition.profile === "PROBLEM") {
    assertions.push(pending("editor.focusSequence", "SEQUENCE_EQ", `${definition.slug}.focus-sequence`));
  }
  if (definition.profile === "SAVE_SUCCESS") {
    assertions.push(candidatePending("editor.canonicalBaselineBytes", "BYTES_EQ", `${definition.slug}.canonical-baseline-projection`, "ARTIFACT"));
    if (expected.semanticDiffCount !== undefined) assertions.push(lit("editor.semanticDiffCount", "EQ", expected.semanticDiffCount));
    if (expected.undoDepth !== undefined) assertions.push(lit("editor.undoDepth", "EQ", expected.undoDepth));
    if (expected.redoDepth !== undefined) assertions.push(lit("editor.redoDepth", "EQ", expected.redoDepth));
    if (definition.slug === "save-normalization-summary") assertions.push(candidatePending("editor.normalizationSummaryBytes", "BYTES_EQ", `${definition.slug}.normalization-summary`, "ARTIFACT"));
  }
  if (definition.profile === "CONFLICT") {
    assertions.push(candidatePending("editor.workingCopyDigest", "EQ", `${definition.slug}.working-copy-digest`));
    assertions.push(candidateLit("editor.confirmationAvailable", "EQ", expected.confirmationAvailable ?? true));
  }
  if (definition.profile === "CONFIRMATION") {
    assertions.push(candidateLit("editor.confirmationAvailable", "EQ", expected.confirmationAvailable));
  }
  if (definition.profile === "RECONCILIATION") {
    assertions.push(candidateLit("editor.mutationLockActive", "EQ", expected.mutationLockActive));
    assertions.push(candidatePending("editor.workingCopyDigest", "EQ", `${definition.slug}.working-copy-digest`));
    if (expected.semanticDiffCount !== undefined) assertions.push(lit("editor.semanticDiffCount", "EQ", expected.semanticDiffCount));
    if (expected.previewVisible !== undefined) assertions.push(lit("editor.previewVisible", "EQ", expected.previewVisible));
    if (expected.mode !== undefined) assertions.push(lit("editor.mode", "EQ", expected.mode));
  }
  if (definition.profile === "MODE") {
    if (expected.mode !== undefined) assertions.push(lit("editor.mode", "EQ", expected.mode));
    if (expected.dirty !== undefined) assertions.push(candidateLit("editor.dirty", "EQ", expected.dirty));
    if (expected.recoveryDraftPresent === true) assertions.push(candidatePending("editor.recoveryDraftEnvelopeBytes", "BYTES_EQ", `${definition.slug}.recovery-draft-envelope`, "ARTIFACT"));
    if (expected.recoveryDraftPresent === false) assertions.push(candidateAbsent("editor.recoveryDraftEnvelopeBytes"));
    if (["unsupported-wire-compatibility-readonly", "migration-preview-accepted-dirty"].includes(definition.slug)) assertions.push(candidatePending("editor.compatibilityOriginalDigest", "EQ", `${definition.slug}.compatibility-original-digest`));
    if (expected.undoDepth !== undefined) assertions.push(lit("editor.undoDepth", "EQ", expected.undoDepth));
    if (expected.redoDepth !== undefined) assertions.push(lit("editor.redoDepth", "EQ", expected.redoDepth));
    if (NO_TRUSTED_WORKING_COPY_SLUGS.has(definition.slug)) assertions.push(candidateAbsent("editor.workingCopyDigest"));
    else assertions.push(candidatePending("editor.workingCopyDigest", "EQ", `${definition.slug}.working-copy-digest`));
  }
  if (["PREVIEW_SUCCESS", "PREVIEW_STATE", "PREVIEW_PROBLEM"].includes(definition.profile)) {
    if (expected.previewVisible !== undefined) assertions.push(lit("editor.previewVisible", "EQ", expected.previewVisible));
    if (expected.basisDigestUnchanged) assertions.push(pending("editor.previewBasisDigest", "EQ", `${definition.slug}.unchanged-preview-basis-digest`));
    else if (expected.previewVisible === false) assertions.push(exactAbsent("editor.previewBasisDigest"));
    else assertions.push(pending("editor.previewBasisDigest", "EQ", `${definition.slug}.preview-basis-digest`));
    if (expected.previewGenerationDelta === 1) assertions.push(candidateLit("editor.previewEligibilityGeneration", "EQ", 1));
    else assertions.push(candidatePending("editor.previewEligibilityGeneration", "EQ", `${definition.slug}.preview-eligibility-generation`));
    if (definition.profile === "PREVIEW_PROBLEM") assertions.push(candidatePending("editor.canonicalBaselineBytes", "BYTES_EQ", `${definition.slug}.canonical-baseline-projection`, "ARTIFACT"));
  }
  if (definition.profile === "ACCESSIBILITY") {
    assertions.push(pending("editor.accessibilityTreeBytes", "BYTES_EQ", `${definition.slug}.accessibility-tree`, "ARTIFACT"));
    if (definition.slug === "live-region-announcements") assertions.push(pending("editor.announcementSequence", "SEQUENCE_EQ", `${definition.slug}.announcement-sequence`));
    if (expected.reducedMotionHonored !== undefined) assertions.push(lit("editor.reducedMotionHonored", "EQ", expected.reducedMotionHonored));
    if (expected.zoom200Operable !== undefined) assertions.push(lit("editor.zoom200Operable", "EQ", expected.zoom200Operable));
    if (expected.highContrastOperable !== undefined) assertions.push(lit("editor.highContrastOperable", "EQ", expected.highContrastOperable));
    if (definition.slug === "unsupported-width-operable") assertions.push(lit("editor.keyboardTrapDetected", "EQ", false));
  }
  assertions.push(...definition.extraAssertions);
  return assertions;
}

function buildCandidates(indexedDefinitions, inputBindings, faultBindings, targetBindings) {
  return indexedDefinitions.map(({ definition, seed, candidateId }) => {
    const inputFormalBinding = inputBindings.get(candidateId);
    if (!inputFormalBinding) throw new Error(`input fixture binding missing: ${candidateId}`);
    const inputPlanBase = {
      profile: INPUT_PROFILE,
      baselineId: definition.input.baselineId,
      parameters: definition.input.parameters,
      actionScript: definition.steps,
      formalBinding: inputFormalBinding
    };
    const inputPlan = { ...inputPlanBase, planIdentityDigest: identity("renderweave-editor-atomic-input-plan/1", inputPlanBase) };
    const formalBinding = faultBindings.get(candidateId);
    if (!formalBinding) throw new Error(`fault binding missing: ${candidateId}`);
    const faultPlanBase = { profile: FAULT_PROFILE, kind: definition.fault.kind, events: definition.fault.events, formalBinding };
    const faultSchedulePlan = { ...faultPlanBase, planIdentityDigest: identity("renderweave-editor-atomic-fault-plan/1", faultPlanBase) };
    const plannedAssertions = assertionTemplates(definition).map((entry, index) => {
      const planAssertionId = `PA${String(index + 1).padStart(3, "0")}`;
      const exactExpectation = targetBindings.get(`${candidateId}::${planAssertionId}`);
      return { planAssertionId, ...entry, ...(exactExpectation ? { expectation: exactExpectation } : {}) };
    });
    const assertionIds = plannedAssertions.map((entry) => entry.planAssertionId);
    const coveragePlan = definition.requirements.map((requirementId) => ({ requirementId, assertionPlanIds: assertionIds, status: "PLANNED_NOT_EVIDENCE" }));
    const blockers = new Set([BLOCKERS.target, BLOCKERS.runner, BLOCKERS.replay]);
    if (definition.terminals.some((entry) => entry.codeBinding.status.startsWith("PENDING") || entry.stageBinding.status.startsWith("PENDING"))) blockers.add(BLOCKERS.terminal);
    if (plannedAssertions.some((entry) => entry.expectation.status.startsWith("PENDING"))) blockers.add(BLOCKERS.expected);
    if (plannedAssertions.some((entry) => entry.probeBinding.status === "CANDIDATE_PROFILE_NOT_ISSUED")) blockers.add(BLOCKERS.probe);
    const identityObject = { inputPlan, faultSchedulePlan, expectedTerminalPlan: definition.terminals, assertionPlan: plannedAssertions };
    return {
      candidateId,
      journeySeedId: seed.journeySeedId,
      sourceJ1CaseId: seed.sourceJ1CaseId,
      title: definition.title,
      status: "PREISSUANCE_BLOCKED",
      scenarioSlug: definition.slug,
      stimulusPlan: { inputPlan, faultSchedulePlan },
      expectedTerminalPlan: definition.terminals,
      assertionPlan: plannedAssertions,
      coveragePlan,
      identityDigest: identity("renderweave-editor-atomic-candidate-identity/1", identityObject),
      blockers: [...blockers].sort(),
      formalIssuanceAllowed: false
    };
  });
}

function buildAudit(candidates) {
  const assignment = readJson(`${ROOT}/non-capacity-assignment-v1.json`);
  const currentProbeProfile = readJson("conformance-probe-profile-v1.json");
  const candidateProbeProfile = readJson(CANDIDATE_PROBE_PROFILE_PATH);
  const formalCases = readJsonl("conformance-cases-v1.jsonl");
  const formalOracles = readJsonl("conformance-oracles-v1.jsonl");
  const currentEditorProbeIds = new Set(currentProbeProfile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS)).map((probe) => probe.probeId));
  const candidateEditorProbeIds = new Set(candidateProbeProfile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS)).map((probe) => probe.probeId));
  const plannedCoverage = [...new Set(candidates.flatMap((candidate) => candidate.coveragePlan.map((edge) => edge.requirementId)))].sort();
  const seedCoverage = [...new Set(assignment.automatedJourneySeeds.flatMap((seed) => seed.requirementIds))].sort();
  let assertionCount = 0;
  let exactLiteralAssertionCount = 0;
  let exactArtifactAssertionCount = 0;
  let exactAbsentAssertionCount = 0;
  let pendingExpectationCount = 0;
  let candidateProfileBindingAssertionCount = 0;
  for (const candidate of candidates) {
    for (const assertion of candidate.assertionPlan) {
      assertionCount += 1;
      if (assertion.expectation.status === "EXACT_LITERAL") exactLiteralAssertionCount += 1;
      else if (assertion.expectation.status === "EXACT_ARTIFACT") exactArtifactAssertionCount += 1;
      else if (assertion.expectation.status === "EXACT_ABSENT") exactAbsentAssertionCount += 1;
      else pendingExpectationCount += 1;
      if (assertion.probeBinding.status === "CANDIDATE_PROFILE_NOT_ISSUED") candidateProfileBindingAssertionCount += 1;
    }
  }
  const byJourney = assignment.automatedJourneySeeds.map((seed) => {
    const members = candidates.filter((candidate) => candidate.journeySeedId === seed.journeySeedId);
    const covered = [...new Set(members.flatMap((candidate) => candidate.coveragePlan.map((edge) => edge.requirementId)))].sort();
    return {
      journeySeedId: seed.journeySeedId,
      sourceJ1CaseId: seed.sourceJ1CaseId,
      candidateCount: members.length,
      seedRequirementCount: seed.requirementIds.length,
      plannedRequirementCount: covered.length,
      uncoveredRequirementIds: seed.requirementIds.filter((id) => !covered.includes(id)),
      extraneousRequirementIds: covered.filter((id) => !seed.requirementIds.includes(id))
    };
  });
  return {
    artifactVersion: "renderweave-editor-atomic-candidate-readiness-audit/1.5",
    status: "ONE_CONTENT_SOURCE_AND_DETERMINISTIC_TARGETS_BOUND_PREISSUANCE_BLOCKED",
    executionClass: EXECUTION_CLASS,
    contract: artifact(CONTRACT_PATH),
    candidates: artifact(CANDIDATES_PATH),
    sourceAssignment: artifact(`${ROOT}/non-capacity-assignment-v1.json`),
    probeProfiles: {
      current: artifact("conformance-probe-profile-v1.json"),
      candidate: artifact(CANDIDATE_PROBE_PROFILE_PATH),
      adjudication: artifact(CANDIDATE_PROBE_ADJUDICATION_PATH)
    },
    terminalAndFaultBindings: {
      terminalAdjudication: artifact(TERMINAL_ADJUDICATION_PATH),
      faultScheduleContract: artifact(FAULT_CONTRACT_PATH),
      faultScheduleCatalog: artifact(FAULT_CATALOG_PATH)
    },
    inputAndTargetBindings: {
      inputFixtureContract: artifact(INPUT_FIXTURE_CONTRACT_PATH),
      inputFixtureCatalog: artifact(INPUT_FIXTURE_CATALOG_PATH),
      semanticProjectionContract: artifact(SEMANTIC_PROJECTION_CONTRACT_PATH),
      semanticProjectionCatalog: artifact(SEMANTIC_PROJECTION_CATALOG_PATH),
      contentSourceContract: artifact(CONTENT_SOURCE_CONTRACT_PATH),
      contentSourceCatalog: artifact(CONTENT_SOURCE_CATALOG_PATH),
      targetBindingContract: artifact(TARGET_BINDING_CONTRACT_PATH),
      targetBindingCatalog: artifact(TARGET_BINDING_CATALOG_PATH)
    },
    counts: {
      journeySeedCount: assignment.automatedJourneySeeds.length,
      candidateCount: candidates.length,
      seedRequirementUnionCount: seedCoverage.length,
      plannedRequirementUnionCount: plannedCoverage.length,
      assertionPlanCount: assertionCount,
      exactLiteralAssertionPlanCount: exactLiteralAssertionCount,
      exactArtifactAssertionPlanCount: exactArtifactAssertionCount,
      exactAbsentAssertionPlanCount: exactAbsentAssertionCount,
      exactExpectationAssertionPlanCount: exactLiteralAssertionCount + exactArtifactAssertionCount + exactAbsentAssertionCount,
      pendingExpectationAssertionPlanCount: pendingExpectationCount,
      inputFixtureArtifactBindingCount: candidates.filter((candidate) => candidate.stimulusPlan.inputPlan.formalBinding.kind === "ARTIFACT").length,
      targetArtifactBindingCount: candidates.flatMap((candidate) => candidate.assertionPlan).filter((assertion) => assertion.expectation.status === "EXACT_ARTIFACT").length,
      targetLiteralAdjudicatedFromPendingCount: 81,
      semanticProjectionExactLiteralBindingCount: 42,
      semanticProjectionContentPrerequisiteCount: 58,
      contentPrerequisiteResolvedBySourceCount: 1,
      contentPrerequisiteRemainingCount: 57,
      contentSourceSlotCount: 47,
      contentSourceExactBindingCount: 1,
      contentSourceUnboundBindingCount: 46,
      contentSourceRecordArtifactCount: 1,
      contentSourceCanonicalDesignDslArtifactCount: 2,
      uiObservationPendingCount: 62,
      terminalAdjudicatedFromPendingCount: 33,
      pendingTerminalBindingCount: candidates.filter((candidate) => candidate.expectedTerminalPlan.some((terminal) => terminal.codeBinding.status.startsWith("PENDING") || terminal.stageBinding.status.startsWith("PENDING"))).length,
      faultArtifactBindingCount: candidates.filter((candidate) => candidate.stimulusPlan.faultSchedulePlan.formalBinding.kind === "ARTIFACT").length,
      faultNoneBindingCount: candidates.filter((candidate) => candidate.stimulusPlan.faultSchedulePlan.formalBinding.kind === "NONE").length,
      proposedProbeCount: 0,
      candidateProfileBindingAssertionCount,
      candidateProfileBoundCandidateCount: candidates.filter((candidate) => candidate.assertionPlan.some((assertion) => assertion.probeBinding.status === "CANDIDATE_PROFILE_NOT_ISSUED")).length,
      formalCaseCount: formalCases.length,
      formalOracleCount: formalOracles.length,
      formalEditorCaseCount: formalCases.filter((record) => record.executionClass === EXECUTION_CLASS).length,
      formalEditorOracleCount: formalOracles.filter((record) => record.oracleId?.startsWith("ORC::EDITOR_AUTOMATED::")).length
    },
    byJourney,
    requirementClosure: {
      exactSeedUnionMatch: JSON.stringify(seedCoverage) === JSON.stringify(plannedCoverage),
      missingRequirementIds: seedCoverage.filter((id) => !plannedCoverage.includes(id)),
      extraneousRequirementIds: plannedCoverage.filter((id) => !seedCoverage.includes(id))
    },
    identityAudit: {
      candidateIdsUnique: new Set(candidates.map((candidate) => candidate.candidateId)).size === candidates.length,
      identityDigestsUnique: new Set(candidates.map((candidate) => candidate.identityDigest)).size === candidates.length,
      formalCaseNamespaceUsed: candidates.some((candidate) => candidate.candidateId.startsWith("CONF::")),
      formalOracleNamespaceUsed: candidates.some((candidate) => candidate.candidateId.startsWith("ORC::"))
    },
    currentProfileAudit: {
      currentEditorProbeCount: currentEditorProbeIds.size,
      unknownCurrentProbeBindings: [...new Set(candidates.flatMap((candidate) => candidate.assertionPlan)
        .filter((assertion) => assertion.probeBinding.status === "CURRENT_PROFILE" && !currentEditorProbeIds.has(assertion.probeBinding.probeId))
        .map((assertion) => assertion.probeBinding.probeId))].sort(),
      candidateProfileId: CANDIDATE_PROBE_PROFILE_ID,
      candidateEditorProbeCount: candidateEditorProbeIds.size,
      unknownCandidateProbeBindings: [...new Set(candidates.flatMap((candidate) => candidate.assertionPlan)
        .filter((assertion) => assertion.probeBinding.status === "CANDIDATE_PROFILE_NOT_ISSUED" && !candidateEditorProbeIds.has(assertion.probeBinding.probeId))
        .map((assertion) => assertion.probeBinding.probeId))].sort(),
      proposedProbeCandidates: [],
      profileMutationPerformed: false,
      candidateProfileIssued: false,
      requiredNextProfileAction: "Keep every 1.1 binding planning-only until the 119 remaining target expectations, 46 unbound first-layer content source slots, exact target and runner admission, product fixture adapter, product replay, and explicit Profile issuance are complete."
    },
    blockers: Object.values(BLOCKERS).map((code) => ({ code, candidateCount: candidates.filter((candidate) => candidate.blockers.includes(code)).length })),
    zeroExecutionBoundary: {
      productCodeChanged: false,
      browserStarted: false,
      webServiceStarted: false,
      networkUsed: false,
      formalJsonlAppended: false,
      j1Executed: false,
      productReadyClaimed: false
    },
    decision: {
      formalRecordIssuanceAllowed: false,
      nextSafeAction: "Continue binding only independently derivable immutable Editor content artifacts to the remaining 46 EditorContentSource slots, then satisfy the remaining downstream content prerequisite graphs and observe the 62 UI targets without invention; only afterward admit an exact product target, fixture adapter, and runner and obtain independent product replay before requesting explicit 1.1 Profile issuance or converting any candidate to formal Case and Oracle records."
    }
  };
}

function validate(contractValue, candidatesValue, auditValue) {
  const checks = [];
  const check = (name, pass, details) => checks.push({ name, pass: Boolean(pass), details });
  const assignment = readJson(`${ROOT}/non-capacity-assignment-v1.json`);
  const currentProbeProfile = readJson("conformance-probe-profile-v1.json");
  const candidateProbeProfile = readJson(CANDIDATE_PROBE_PROFILE_PATH);
  const terminalAdjudication = readJson(TERMINAL_ADJUDICATION_PATH);
  const faultContract = readJson(FAULT_CONTRACT_PATH);
  const faultCatalog = readJson(FAULT_CATALOG_PATH);
  const inputFixtureContract = readJson(INPUT_FIXTURE_CONTRACT_PATH);
  const inputFixtureCatalog = readJson(INPUT_FIXTURE_CATALOG_PATH);
  const semanticProjectionContract = readJson(SEMANTIC_PROJECTION_CONTRACT_PATH);
  const semanticProjectionCatalog = readJson(SEMANTIC_PROJECTION_CATALOG_PATH);
  const contentSourceContract = readJson(CONTENT_SOURCE_CONTRACT_PATH);
  const contentSourceCatalog = readJson(CONTENT_SOURCE_CATALOG_PATH);
  const targetBindingContract = readJson(TARGET_BINDING_CONTRACT_PATH);
  const targetBindingCatalog = readJson(TARGET_BINDING_CATALOG_PATH);
  const currentAllowedOperators = new Map(currentProbeProfile.probes.map((probe) => [probe.probeId, new Set(probe.allowedOperators)]));
  const currentEditorProbes = new Set(currentProbeProfile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS)).map((probe) => probe.probeId));
  const candidateAllowedOperators = new Map(candidateProbeProfile.probes.map((probe) => [probe.probeId, new Set(probe.allowedOperators)]));
  const candidateEditorProbes = new Set(candidateProbeProfile.probes.filter((probe) => probe.executionClasses.includes(EXECUTION_CLASS)).map((probe) => probe.probeId));
  const seedById = new Map(assignment.automatedJourneySeeds.map((seed) => [seed.journeySeedId, seed]));
  const candidatePattern = new RegExp(contractValue.candidateIdentity.pattern);
  check("candidate count is nonzero", candidatesValue.candidates.length > 0, candidatesValue.candidates.length);
  check("all 12 journey seeds have candidates", new Set(candidatesValue.candidates.map((candidate) => candidate.journeySeedId)).size === 12, auditValue.byJourney);
  check("candidate identities are unique planning identities", candidatesValue.candidates.every((candidate) => candidatePattern.test(candidate.candidateId)) && new Set(candidatesValue.candidates.map((candidate) => candidate.candidateId)).size === candidatesValue.candidates.length, candidatesValue.candidates.map((candidate) => candidate.candidateId));
  check("formal identity namespaces are unused", candidatesValue.candidates.every((candidate) => !candidate.candidateId.startsWith("CONF::") && !candidate.candidateId.startsWith("ORC::")), auditValue.identityAudit);
  check("every candidate remains blocked and unissued", candidatesValue.candidates.every((candidate) => candidate.status === "PREISSUANCE_BLOCKED" && candidate.formalIssuanceAllowed === false && candidate.blockers.length > 0), null);
  check("all 33 formerly pending terminal bindings are exact", terminalAdjudication.counts.adjudicatedFromPendingCount === 33 && terminalAdjudication.counts.pendingCodeOrStageCount === 0 && candidatesValue.candidates.every((candidate) => candidate.expectedTerminalPlan.every((terminal) => ["EXACT", "NOT_REQUIRED"].includes(terminal.codeBinding.status) && ["EXACT", "NOT_REQUIRED"].includes(terminal.stageBinding.status))), terminalAdjudication.counts);
  check("terminal adjudication aligns with candidate terminal vectors", terminalAdjudication.decisions.every((decision) => {
    const candidate = candidatesValue.candidates.find((entry) => entry.candidateId === decision.candidateId);
    return candidate && stable(terminalProjection(candidate.expectedTerminalPlan[0])) === stable(decision.exactTerminal);
  }), null);
  check("all fault schedules have formal bindings and 37 immutable artifacts", faultCatalog.counts.candidateCount === 108 && faultCatalog.counts.artifactCandidateCount === 37 && faultCatalog.counts.noneCandidateCount === 71 && candidatesValue.candidates.every((candidate) => {
    const binding = candidate.stimulusPlan.faultSchedulePlan.formalBinding;
    const { identitySha256, ...base } = binding;
    return identitySha256 === formalFaultIdentity(base) && (candidate.stimulusPlan.faultSchedulePlan.kind === "NONE" ? binding.kind === "NONE" : binding.kind === "ARTIFACT");
  }), faultCatalog.counts);
  check("fault artifacts align with exact events and terminals", faultCatalog.artifacts.every((entry) => {
    const candidate = candidatesValue.candidates.find((item) => item.candidateId === entry.candidateId);
    const value = readJson(entry.artifact.path);
    return candidate
      && entry.artifact.sha256 === artifact(entry.artifact.path).sha256
      && stable(value.events) === stable(candidate.stimulusPlan.faultSchedulePlan.events)
      && stable(value.expectedTerminal) === stable(terminalProjection(candidate.expectedTerminalPlan[0]));
  }), null);
  check("fault and terminal blockers are cleared without product execution", candidatesValue.candidates.every((candidate) => !candidate.blockers.includes(BLOCKERS.fault) && !candidate.blockers.includes(BLOCKERS.terminal)), auditValue.blockers);
  check("all 108 semantic input fixtures have formal bindings", inputFixtureCatalog.counts.candidateCount === 108 && inputFixtureCatalog.counts.artifactCandidateCount === 108 && candidatesValue.candidates.every((candidate) => {
    const binding = candidate.stimulusPlan.inputPlan.formalBinding;
    const { identitySha256, ...base } = binding;
    return binding.kind === "ARTIFACT" && identitySha256 === formalInputIdentity(base);
  }), inputFixtureCatalog.counts);
  check("input fixture artifacts align with candidate semantics", inputFixtureCatalog.artifacts.every((entry) => {
    const candidate = candidatesValue.candidates.find((item) => item.candidateId === entry.candidateId);
    const value = readJson(entry.artifact.path);
    return candidate
      && entry.artifact.sha256 === artifact(entry.artifact.path).sha256
      && value.baselineId === candidate.stimulusPlan.inputPlan.baselineId
      && stable(value.parameters) === stable(candidate.stimulusPlan.inputPlan.parameters)
      && stable(value.actionScript) === stable(candidate.stimulusPlan.inputPlan.actionScript)
      && stable(entry.formalBinding) === stable(candidate.stimulusPlan.inputPlan.formalBinding);
  }), null);
  check("semantic fixture artifact blocker is cleared while adapter remains unproven", candidatesValue.candidates.every((candidate) => !candidate.blockers.includes(BLOCKERS.fixture)) && inputFixtureContract.boundary.includes("do not prove"), auditValue.blockers);
  check("semantic projection closes 42 literals, two exact no-working-copy absences, and preserves 58 content prerequisites plus 62 UI observations", semanticProjectionCatalog.counts.sourcePendingExpectationCount === 308
    && semanticProjectionCatalog.counts.priorMechanicalExactCount === 146
    && semanticProjectionCatalog.counts.semanticInputPendingExpectationCount === 162
    && semanticProjectionCatalog.counts.excludedUiObservedCount === 62
    && semanticProjectionCatalog.counts.nonUiDecisionCount === 100
    && semanticProjectionCatalog.counts.exactCommandResultCount === 22
    && semanticProjectionCatalog.counts.exactPreviewGenerationCount === 20
    && semanticProjectionCatalog.counts.exactLiteralBindingCount === 42
    && semanticProjectionCatalog.counts.contentDerivedPendingCount === 58
    && semanticProjectionContract.workingCopyAvailability.digestAbsent.includes("Raw Repair")
    && semanticProjectionContract.noInferenceRule.includes("remains pending"), semanticProjectionCatalog.counts);
  check("semantic projection decisions replay from frozen candidate semantics", semanticProjectionCatalog.decisions.every((decision) => {
    const candidate = candidatesValue.candidates.find((entry) => entry.candidateId === decision.candidateId);
    if (!candidate) return false;
    if (decision.probeId === "editor.commandResult") {
      return decision.resolution === "EXACT_COMMAND_RESULT_CODE"
        && decision.expected?.kind === "LITERAL"
        && decision.expected.value === COMMAND_RESULT_BY_SLUG[decision.scenarioSlug];
    }
    if (decision.probeId === "editor.previewEligibilityGeneration") {
      const initial = candidate.stimulusPlan.inputPlan.parameters.currentGeneration ?? 0;
      const delta = PREVIEW_GENERATION_DELTA_BY_SLUG[decision.scenarioSlug];
      return decision.resolution === "EXACT_PREVIEW_ELIGIBILITY_GENERATION"
        && decision.initialGeneration === initial
        && decision.generationDelta === delta
        && decision.expected?.value === initial + delta;
    }
    return decision.resolution === "WAIT_FOR_EXACT_CONTENT_PREREQUISITES"
      && stable(decision.prerequisiteIds) === stable(CONTENT_PREREQUISITES_BY_PROBE[decision.probeId]);
  }), null);
  const contentSourceExpectedKeys = semanticProjectionCatalog.decisions
    .filter((decision) => decision.resolution === "WAIT_FOR_EXACT_CONTENT_PREREQUISITES" && ["editor.workingCopyDigest", "editor.canonicalBaselineBytes"].includes(decision.probeId))
    .map((decision) => `${decision.candidateId}::${decision.planAssertionId}`)
    .sort();
  const contentSourceActualKeys = contentSourceCatalog.slots.map((slot) => `${slot.candidateId}::${slot.planAssertionId}`).sort();
  const dirtyGuardSourceRecord = readJson(DIRTY_GUARD_SOURCE_RECORD_PATH);
  const dirtyGuardCleanBytes = raw(DIRTY_GUARD_CLEAN_DESIGN_PATH);
  const dirtyGuardWorkingBytes = raw(DIRTY_GUARD_WORKING_DESIGN_PATH);
  const dirtyGuardSlot = contentSourceCatalog.slots.find((slot) => slot.sourceSlotId === DIRTY_GUARD_SOURCE_SLOT_ID);
  check("EditorContentSource freezes 47 first-layer slots and binds one independently replayable immutable fixture", contentSourceContract.module.name === "EditorContentSource"
    && contentSourceContract.canonicalFacts.designDslCanonicalProfile === "renderweave-design-c14n/1.0"
    && contentSourceContract.canonicalFacts.contentHash.includes("renderweave-design-content/1\\0")
    && contentSourceCatalog.counts.sourceSlotCount === 47
    && contentSourceCatalog.counts.workingCopySourceSlotCount === 35
    && contentSourceCatalog.counts.canonicalBaselineSourceSlotCount === 12
    && contentSourceCatalog.counts.postSettledActionWorkingCopySlotCount === 34
    && contentSourceCatalog.counts.atomicRejectionFixtureSlotCount === 1
    && contentSourceCatalog.counts.serverCanonicalResponseSlotCount === 10
    && contentSourceCatalog.counts.existingTrustedBaselineSlotCount === 2
    && contentSourceCatalog.counts.exactSourceBindingCount === 1
    && contentSourceCatalog.counts.unboundSourceBindingCount === 46
    && contentSourceCatalog.counts.sourceRecordArtifactCount === 1
    && contentSourceCatalog.counts.canonicalDesignDslArtifactCount === 2
    && contentSourceCatalog.counts.sourceArtifactCount === 3
    && stable(contentSourceExpectedKeys) === stable(contentSourceActualKeys)
    && new Set(contentSourceCatalog.slots.map((slot) => slot.sourceSlotId)).size === 47
    && contentSourceCatalog.slots.every((slot) => stable(slot.semanticInputFixture) === stable(artifact(inputFixturePath(slot.candidateId))))
    && contentSourceCatalog.slots.filter((slot) => slot.binding.status === "UNBOUND").every((slot) => slot.binding.reasonCode === "EXACT_EDITOR_CONTENT_SOURCE_ARTIFACT_MISSING")
    && dirtyGuardSlot?.binding.status === "EXACT_SOURCE"
    && dirtyGuardSlot.binding.adapterKind === "IMMUTABLE_SPEC_FIXTURE"
    && stable(dirtyGuardSlot.binding.sourceRecordArtifact) === stable(artifact(DIRTY_GUARD_SOURCE_RECORD_PATH))
    && dirtyGuardSourceRecord.sourceSlotId === DIRTY_GUARD_SOURCE_SLOT_ID
    && dirtyGuardSourceRecord.stateProof.postActionRule === "BYTE_IDENTICAL_TO_PRE_ACTION"
    && dirtyGuardSourceRecord.stateProof.canonicalBaseline.contentHash === sha(Buffer.concat([Buffer.from("renderweave-design-content/1\0", "utf8"), dirtyGuardCleanBytes]))
    && dirtyGuardSourceRecord.stateProof.canonicalBaseline.workingCopyDigest === sha(dirtyGuardCleanBytes)
    && dirtyGuardSourceRecord.stateProof.preActionWorkingCopy.workingCopyDigest === sha(dirtyGuardWorkingBytes)
    && dirtyGuardSourceRecord.result.workingCopyDigest === sha(dirtyGuardWorkingBytes)
    && dirtyGuardSourceRecord.result.workingCopyDigest !== dirtyGuardSourceRecord.stateProof.canonicalBaseline.workingCopyDigest
    && dirtyGuardCleanBytes.equals(designBytes(JSON.parse(dirtyGuardCleanBytes.toString("utf8"))))
    && dirtyGuardWorkingBytes.equals(designBytes(JSON.parse(dirtyGuardWorkingBytes.toString("utf8")))), contentSourceCatalog.counts);
  check("target adjudication binds exactly 108 artifacts and 81 literals", targetBindingCatalog.counts.originalPendingExpectationCount === 308
    && targetBindingCatalog.counts.exactArtifactBindingCount === 108
    && targetBindingCatalog.counts.exactLiteralBindingCount === 81
    && targetBindingCatalog.counts.exactBindingCount === 189
    && targetBindingCatalog.counts.remainingPendingLiteralCount === 56
    && targetBindingCatalog.counts.remainingPendingArtifactCount === 63
    && targetBindingCatalog.counts.remainingPendingCount === 119
    && targetBindingCatalog.counts.targetArtifactCount === 108, targetBindingCatalog.counts);
  check("all exact target artifacts replay and bind candidate assertions", targetBindingCatalog.artifacts.every((entry) => {
    const candidate = candidatesValue.candidates.find((item) => item.candidateId === entry.candidateId);
    const assertion = candidate?.assertionPlan.find((item) => item.planAssertionId === entry.planAssertionId);
    return assertion?.expectation.status === "EXACT_ARTIFACT"
      && assertion.expectation.expected.artifactPath === entry.artifact.path
      && assertion.expectation.expected.artifactSha256 === artifact(entry.artifact.path).sha256;
  }), null);
  check("only mechanically determined pending targets were resolved", targetBindingCatalog.decisions.every((decision) => {
    const candidate = candidatesValue.candidates.find((item) => item.candidateId === decision.candidateId);
    const assertion = candidate?.assertionPlan.find((item) => item.planAssertionId === decision.planAssertionId);
    if (decision.resultStatus === "EXACT_ARTIFACT") return decision.probeId === "operation.terminalsBytes" && assertion?.expectation.status === "EXACT_ARTIFACT";
    if (decision.resultStatus === "EXACT_LITERAL") return [
      "editor.networkRequestSequence",
      "editor.commandResult",
      "editor.previewEligibilityGeneration",
      "editor.workingCopyDigest"
    ].includes(decision.probeId) && assertion?.expectation.status === "EXACT_LITERAL";
    return decision.resultStatus.startsWith("PENDING") && assertion?.expectation.status === decision.resultStatus;
  }) && targetBindingContract.noInferenceRule.includes("remains pending"), null);
  check("input plan digests replay", candidatesValue.candidates.every((candidate) => {
    const { planIdentityDigest, ...base } = candidate.stimulusPlan.inputPlan;
    return planIdentityDigest === identity("renderweave-editor-atomic-input-plan/1", base);
  }), null);
  check("fault plan digests replay", candidatesValue.candidates.every((candidate) => {
    const { planIdentityDigest, ...base } = candidate.stimulusPlan.faultSchedulePlan;
    return planIdentityDigest === identity("renderweave-editor-atomic-fault-plan/1", base);
  }), null);
  check("candidate identity digests replay", candidatesValue.candidates.every((candidate) => candidate.identityDigest === identity("renderweave-editor-atomic-candidate-identity/1", {
    inputPlan: candidate.stimulusPlan.inputPlan,
    faultSchedulePlan: candidate.stimulusPlan.faultSchedulePlan,
    expectedTerminalPlan: candidate.expectedTerminalPlan,
    assertionPlan: candidate.assertionPlan
  })), null);
  check("candidate identity digests are unique", new Set(candidatesValue.candidates.map((candidate) => candidate.identityDigest)).size === candidatesValue.candidates.length, auditValue.identityAudit);
  check("every ordinary Editor candidate has exactly one terminal", candidatesValue.candidates.every((candidate) => candidate.expectedTerminalPlan.length === 1), null);
  check("every current probe binding belongs to Editor class and supports operator", candidatesValue.candidates.every((candidate) => candidate.assertionPlan.every((assertion) => assertion.probeBinding.status !== "CURRENT_PROFILE" || (currentEditorProbes.has(assertion.probeBinding.probeId) && currentAllowedOperators.get(assertion.probeBinding.probeId)?.has(assertion.operator)))), auditValue.currentProfileAudit.unknownCurrentProbeBindings);
  check("every candidate probe binding belongs to the complete unissued 1.1 Editor class and supports operator", candidatesValue.candidates.every((candidate) => candidate.assertionPlan.every((assertion) => assertion.probeBinding.status !== "CANDIDATE_PROFILE_NOT_ISSUED" || (assertion.probeBinding.probeProfile === CANDIDATE_PROBE_PROFILE_ID && candidateEditorProbes.has(assertion.probeBinding.probeId) && candidateAllowedOperators.get(assertion.probeBinding.probeId)?.has(assertion.operator)))), auditValue.currentProfileAudit.unknownCandidateProbeBindings);
  check("no unresolved proposed probe bindings remain", candidatesValue.candidates.every((candidate) => candidate.assertionPlan.every((assertion) => assertion.probeBinding.status !== "PROPOSED_PROFILE_REVISION")), auditValue.counts.proposedProbeCount);
  check("assertion expectation shapes are closed", candidatesValue.candidates.every((candidate) => candidate.assertionPlan.every((assertion) => {
    const status = assertion.expectation.status;
    if (status === "EXACT_LITERAL") return assertion.operator !== "ABSENT" && assertion.expectation.expected?.kind === "LITERAL";
    if (status === "EXACT_ARTIFACT") return assertion.operator === "BYTES_EQ" && assertion.expectation.expected?.kind === "ARTIFACT" && assertion.expectation.expected.mediaType === "application/json";
    if (status === "EXACT_ABSENT") return assertion.operator === "ABSENT" && Object.keys(assertion.expectation).length === 1;
    if (status === "PENDING_TARGET_LITERAL") return assertion.operator !== "ABSENT" && assertion.expectation.expectedKind === "LITERAL";
    if (status === "PENDING_TARGET_ARTIFACT") return assertion.operator !== "ABSENT" && assertion.expectation.expectedKind === "ARTIFACT";
    return false;
  })), null);
  check("every terminal has code, stage, parameter, and terminal-vector assertion plans", candidatesValue.candidates.every((candidate) => {
    const byProbe = new Map(candidate.assertionPlan.filter((assertion) => assertion.probeBinding.status === "CURRENT_PROFILE").map((assertion) => [assertion.probeBinding.probeId, assertion]));
    const terminal = candidate.expectedTerminalPlan[0];
    const code = byProbe.get("operation.terminalCode");
    const stage = byProbe.get("operation.terminalStage");
    const parameters = byProbe.get("operation.terminalParametersBytes");
    const vector = byProbe.get("operation.terminalsBytes");
    const codeAligned = terminal.codeBinding.status === "EXACT" ? code?.expectation.expected?.value === terminal.codeBinding.code : terminal.codeBinding.status === "NOT_REQUIRED" ? code?.expectation.status === "EXACT_ABSENT" : code?.expectation.status === "PENDING_TARGET_LITERAL";
    const stageAligned = terminal.stageBinding.status === "EXACT" ? stage?.expectation.expected?.value === terminal.stageBinding.stage : terminal.stageBinding.status === "NOT_REQUIRED" ? stage?.expectation.status === "EXACT_ABSENT" : stage?.expectation.status === "PENDING_TARGET_LITERAL";
    return codeAligned && stageAligned && parameters?.expectation.status === "EXACT_ABSENT" && vector?.expectation.status === "EXACT_ARTIFACT";
  }), null);
  check("every assertion plan id is local, unique, and mapped", candidatesValue.candidates.every((candidate) => {
    const ids = candidate.assertionPlan.map((assertion) => assertion.planAssertionId);
    const mapped = new Set(candidate.coveragePlan.flatMap((edge) => edge.assertionPlanIds));
    return ids.every((id, index) => id === `PA${String(index + 1).padStart(3, "0")}`) && new Set(ids).size === ids.length && ids.every((id) => mapped.has(id));
  }), null);
  check("coverage edges stay inside source journey seed", candidatesValue.candidates.every((candidate) => {
    const seed = seedById.get(candidate.journeySeedId);
    return seed && candidate.coveragePlan.every((edge) => seed.requirementIds.includes(edge.requirementId) && edge.status === "PLANNED_NOT_EVIDENCE");
  }), null);
  check("every seed requirement has at least one planned edge", auditValue.requirementClosure.exactSeedUnionMatch && auditValue.byJourney.every((entry) => entry.uncoveredRequirementIds.length === 0 && entry.extraneousRequirementIds.length === 0), auditValue.requirementClosure);
  check("pending or unissued-candidate assertions force corresponding blockers", candidatesValue.candidates.every((candidate) => {
    const pendingExpectation = candidate.assertionPlan.some((assertion) => assertion.expectation.status.startsWith("PENDING"));
    const candidateProbe = candidate.assertionPlan.some((assertion) => assertion.probeBinding.status === "CANDIDATE_PROFILE_NOT_ISSUED");
    return (!pendingExpectation || candidate.blockers.includes(BLOCKERS.expected)) && (!candidateProbe || candidate.blockers.includes(BLOCKERS.probe));
  }), null);
  check("global formal JSONL counts include issued Domain Services and Design/Input/Expression suffixes", auditValue.counts.formalCaseCount === 253 && auditValue.counts.formalOracleCount === 253, { cases: auditValue.counts.formalCaseCount, oracles: auditValue.counts.formalOracleCount });
  check("Editor formal namespaces remain empty", auditValue.counts.formalEditorCaseCount === 0 && auditValue.counts.formalEditorOracleCount === 0, { cases: auditValue.counts.formalEditorCaseCount, oracles: auditValue.counts.formalEditorOracleCount });
  check("zero-execution boundary is fully false", Object.values(auditValue.zeroExecutionBoundary).every((value) => value === false), auditValue.zeroExecutionBoundary);
  check("formal issuance remains forbidden", auditValue.decision.formalRecordIssuanceAllowed === false, auditValue.decision);
  check("all fixture, target, terminal, and fault catalogs remain planning-only", terminalAdjudication.zeroExecutionBoundary.productTerminalObserved === false
    && faultContract.boundary.includes("do not prove")
    && faultCatalog.boundary.includes("no product adapter")
    && inputFixtureCatalog.boundary.includes("no product fixture adapter")
    && semanticProjectionCatalog.boundary.includes("no product observation")
    && contentSourceCatalog.boundary.includes("UNBOUND")
    && targetBindingCatalog.boundary.includes("no product observation"), null);
  return { checks, passed: checks.every((entry) => entry.pass) };
}

applyTerminalAdjudications();
const assignmentValue = readJson(`${ROOT}/non-capacity-assignment-v1.json`);
const indexedDefinitions = indexDefinitions(assignmentValue);
const terminalAdjudicationValue = buildTerminalAdjudication(indexedDefinitions);
write(TERMINAL_ADJUDICATION_PATH, terminalAdjudicationValue);
const faultContractValue = buildFaultScheduleContract(indexedDefinitions);
write(FAULT_CONTRACT_PATH, faultContractValue);
const { catalog: faultCatalogValue, bindings: faultBindings } = buildFaultScheduleCatalog(indexedDefinitions);
write(FAULT_CATALOG_PATH, faultCatalogValue);
const inputFixtureContractValue = buildInputFixtureContract(indexedDefinitions);
write(INPUT_FIXTURE_CONTRACT_PATH, inputFixtureContractValue);
const { catalog: inputFixtureCatalogValue, bindings: inputBindings } = buildInputFixtureCatalog(indexedDefinitions);
write(INPUT_FIXTURE_CATALOG_PATH, inputFixtureCatalogValue);
const semanticProjectionContractValue = buildSemanticProjectionContract();
write(SEMANTIC_PROJECTION_CONTRACT_PATH, semanticProjectionContractValue);
const { catalog: semanticProjectionCatalogValue, bindings: semanticProjectionBindings } = buildSemanticProjectionCatalog(indexedDefinitions);
write(SEMANTIC_PROJECTION_CATALOG_PATH, semanticProjectionCatalogValue);
const contentSourceContractValue = buildContentSourceContract();
write(CONTENT_SOURCE_CONTRACT_PATH, contentSourceContractValue);
const { catalog: contentSourceCatalogValue, bindings: contentSourceBindings } = buildContentSourceCatalog(indexedDefinitions, semanticProjectionCatalogValue);
write(CONTENT_SOURCE_CATALOG_PATH, contentSourceCatalogValue);
const targetBindingContractValue = buildTargetBindingContract();
write(TARGET_BINDING_CONTRACT_PATH, targetBindingContractValue);
const { catalog: targetBindingCatalogValue, bindings: targetBindings } = buildTargetBindingCatalog(indexedDefinitions, semanticProjectionBindings, contentSourceBindings);
write(TARGET_BINDING_CATALOG_PATH, targetBindingCatalogValue);
const contractValue = contract();
write(CONTRACT_PATH, contractValue);
const candidates = buildCandidates(indexedDefinitions, inputBindings, faultBindings, targetBindings);
const candidatesValue = {
  artifactVersion: "renderweave-editor-atomic-scenario-candidates/1.5",
  status: "PLANNING_CANDIDATES_ONLY_FORMAL_ISSUANCE_FORBIDDEN",
  executionClass: EXECUTION_CLASS,
  contract: artifact(CONTRACT_PATH),
  sourceAssignment: artifact(`${ROOT}/non-capacity-assignment-v1.json`),
  candidateProbeProfile: artifact(CANDIDATE_PROBE_PROFILE_PATH),
  terminalAdjudication: artifact(TERMINAL_ADJUDICATION_PATH),
  faultScheduleContract: artifact(FAULT_CONTRACT_PATH),
  faultScheduleCatalog: artifact(FAULT_CATALOG_PATH),
  inputFixtureContract: artifact(INPUT_FIXTURE_CONTRACT_PATH),
  inputFixtureCatalog: artifact(INPUT_FIXTURE_CATALOG_PATH),
  semanticProjectionContract: artifact(SEMANTIC_PROJECTION_CONTRACT_PATH),
  semanticProjectionCatalog: artifact(SEMANTIC_PROJECTION_CATALOG_PATH),
  contentSourceContract: artifact(CONTENT_SOURCE_CONTRACT_PATH),
  contentSourceCatalog: artifact(CONTENT_SOURCE_CATALOG_PATH),
  targetBindingContract: artifact(TARGET_BINDING_CONTRACT_PATH),
  targetBindingCatalog: artifact(TARGET_BINDING_CATALOG_PATH),
  candidateCount: candidates.length,
  candidates,
  boundary: "These records split automation intent and bind semantic fixture and partial target artifacts only. They are not Case records, Oracle records, product-executable fixtures, browser results, J1 evidence, or product readiness evidence."
};
write(CANDIDATES_PATH, candidatesValue);
const auditValue = buildAudit(candidates);
write(AUDIT_PATH, auditValue);
const validation = validate(contractValue, candidatesValue, auditValue);
const primaryResult = {
  artifactVersion: "renderweave-editor-atomic-candidate-primary-result/1.5",
  implementationRevision: "editor-atomic-candidate-generator/1.5",
  target: {
    contract: artifact(CONTRACT_PATH),
    candidates: artifact(CANDIDATES_PATH),
    audit: artifact(AUDIT_PATH),
    sourceAssignment: artifact(`${ROOT}/non-capacity-assignment-v1.json`),
    candidateProbeProfile: artifact(CANDIDATE_PROBE_PROFILE_PATH),
    candidateProbeAdjudication: artifact(CANDIDATE_PROBE_ADJUDICATION_PATH),
    terminalAdjudication: artifact(TERMINAL_ADJUDICATION_PATH),
    faultScheduleContract: artifact(FAULT_CONTRACT_PATH),
    faultScheduleCatalog: artifact(FAULT_CATALOG_PATH),
    inputFixtureContract: artifact(INPUT_FIXTURE_CONTRACT_PATH),
    inputFixtureCatalog: artifact(INPUT_FIXTURE_CATALOG_PATH),
    semanticProjectionContract: artifact(SEMANTIC_PROJECTION_CONTRACT_PATH),
    semanticProjectionCatalog: artifact(SEMANTIC_PROJECTION_CATALOG_PATH),
    contentSourceContract: artifact(CONTENT_SOURCE_CONTRACT_PATH),
    contentSourceCatalog: artifact(CONTENT_SOURCE_CATALOG_PATH),
    targetBindingContract: artifact(TARGET_BINDING_CONTRACT_PATH),
    targetBindingCatalog: artifact(TARGET_BINDING_CATALOG_PATH),
    formalCases: artifact("conformance-cases-v1.jsonl"),
    formalOracles: artifact("conformance-oracles-v1.jsonl")
  },
  status: validation.passed ? "PASS_STATIC_CANDIDATE_DECOMPOSITION" : "FAIL",
  checkCount: validation.checks.length,
  failureCount: validation.checks.filter((entry) => !entry.pass).length,
  checks: validation.checks,
  boundary: "Static candidate validation only; no browser, product build, product code, network, formal registry append, J1, or READY claim."
};
write(PRIMARY_RESULT_PATH, primaryResult);
if (!validation.passed) {
  console.error(JSON.stringify(primaryResult, null, 2));
  process.exitCode = 1;
} else {
  console.log(JSON.stringify({ status: primaryResult.status, candidateCount: candidates.length, assertionPlanCount: auditValue.counts.assertionPlanCount, proposedProbeCount: auditValue.counts.proposedProbeCount, candidateProfileBindingAssertionCount: auditValue.counts.candidateProfileBindingAssertionCount, checkCount: primaryResult.checkCount }, null, 2));
}
