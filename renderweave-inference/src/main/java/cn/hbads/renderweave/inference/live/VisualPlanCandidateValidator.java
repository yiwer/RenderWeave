package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateReferenceKind;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ensures STRUCTURE cannot collapse or invent topology after the serial visual analysis. */
final class VisualPlanCandidateValidator {

    List<CandidateProblem> validate(
            CandidateBundle candidate,
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy,
            VisualElementBindingPlan bindings
    ) {
        var problems = new ArrayList<CandidateProblem>();
        var candidateSchemas = uniqueSchemasByKey(candidate.schemas());
        var expectedSchemaKeys = hierarchy.entities().stream()
                .map(VisualEntityPlan::schemaKey).collect(java.util.stream.Collectors.toSet());

        var root = candidate.schemas().stream()
                .filter(schema -> schema.candidateSchemaId().equals(candidate.rootCandidateSchemaId()))
                .findFirst().orElse(null);
        var expectedRoot = hierarchy.requireEntity(hierarchy.rootEntityId());
        if (root == null || !expectedRoot.schemaKey().equals(root.proposedSchemaKey())) {
            problems.add(problem("VISUAL_PLAN_ROOT_SCHEMA_MISMATCH", root, "/candidate/rootCandidateSchemaId"));
        }

        for (var entity : hierarchy.entities()) {
            var schema = candidateSchemas.get(entity.schemaKey());
            if (schema == null) {
                problems.add(problem("VISUAL_PLAN_SCHEMA_MISSING", null, "/candidate/schemas"));
                continue;
            }
            if (!hasPlannedEvidence(schema.assessment().evidence(), entity.supportingElementIds(), inventory)) {
                problems.add(problem("VISUAL_PLAN_SCHEMA_EVIDENCE_MISSING", schema, "/candidate/schemas"));
            }
            validateFields(candidateSchemas, schema, entity, inventory, hierarchy, bindings, problems);
        }
        for (var schema : candidate.schemas()) {
            if (schema.proposedSchemaKey() != null && !expectedSchemaKeys.contains(schema.proposedSchemaKey())) {
                problems.add(problem("VISUAL_PLAN_SCHEMA_UNEXPECTED", schema, "/candidate/schemas"));
            }
        }
        return List.copyOf(problems);
    }

    private static void validateFields(
            Map<String, CandidateSchema> candidateSchemas,
            CandidateSchema schema,
            VisualEntityPlan entity,
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy,
            VisualElementBindingPlan bindings,
            List<CandidateProblem> problems
    ) {
        var fields = uniqueFieldsByKey(schema.fields());
        var expectedKeys = new HashSet<String>();
        for (var relationship : hierarchy.relationships()) {
            if (!relationship.parentEntityId().equals(entity.entityId())) continue;
            expectedKeys.add(relationship.fieldKey());
            var field = fields.get(relationship.fieldKey());
            if (field == null) {
                problems.add(problem("VISUAL_PLAN_RELATION_MISSING", null, "/candidate/schemas/fields"));
                continue;
            }
            var targetPlan = hierarchy.requireEntity(relationship.childEntityId());
            var target = candidateSchemas.get(targetPlan.schemaKey());
            if (target == null || !matchesRelationship(field.value(), relationship.cardinality(), target)) {
                problems.add(problem("VISUAL_PLAN_RELATION_SHAPE_INVALID", field, "/candidate/schemas/fields"));
            }
            if (!hasPlannedEvidence(
                    field.assessment().evidence(), relationship.supportingElementIds(), inventory)) {
                problems.add(problem("VISUAL_PLAN_RELATION_EVIDENCE_MISSING", field, "/candidate/schemas/fields"));
            }
        }
        for (var binding : bindings.bindings()) {
            if (!binding.entityId().equals(entity.entityId())) continue;
            var element = inventory.requireElement(binding.elementId());
            expectedKeys.add(element.proposedKey());
            var field = fields.get(element.proposedKey());
            if (field == null) {
                problems.add(problem("VISUAL_PLAN_FIELD_MISSING", null, "/candidate/schemas/fields"));
                continue;
            }
            if (!matchesSlot(field.value(), element)) {
                problems.add(problem("VISUAL_PLAN_FIELD_SHAPE_INVALID", field, "/candidate/schemas/fields"));
            }
            if (field.assessment().evidence().stream().noneMatch(element.evidence()::contains)) {
                problems.add(problem("VISUAL_PLAN_FIELD_EVIDENCE_MISSING", field, "/candidate/schemas/fields"));
            }
        }
        for (var field : schema.fields()) {
            if (field.proposedFieldKey() != null && !expectedKeys.contains(field.proposedFieldKey())) {
                problems.add(problem("VISUAL_PLAN_FIELD_UNEXPECTED", field, "/candidate/schemas/fields"));
            }
        }
    }

    private static Map<String, CandidateSchema> uniqueSchemasByKey(List<CandidateSchema> schemas) {
        var result = new HashMap<String, CandidateSchema>();
        var ambiguous = new HashSet<String>();
        for (var schema : schemas) {
            var key = schema.proposedSchemaKey();
            if (key == null || ambiguous.contains(key)) continue;
            if (result.putIfAbsent(key, schema) != null) {
                result.remove(key);
                ambiguous.add(key);
            }
        }
        return result;
    }

    private static Map<String, CandidateField> uniqueFieldsByKey(List<CandidateField> fields) {
        var result = new HashMap<String, CandidateField>();
        var ambiguous = new HashSet<String>();
        for (var field : fields) {
            var key = field.proposedFieldKey();
            if (key == null || ambiguous.contains(key)) continue;
            if (result.putIfAbsent(key, field) != null) {
                result.remove(key);
                ambiguous.add(key);
            }
        }
        return result;
    }

    private static boolean matchesRelationship(
            CandidateValue value,
            VisualMultiplicity cardinality,
            CandidateSchema target
    ) {
        var reference = cardinality == VisualMultiplicity.ONE
                ? value
                : value.kind() == CandidateValueKind.ARRAY ? value.items() : null;
        return reference != null
                && reference.kind() == CandidateValueKind.REFERENCE
                && reference.reference() != null
                && reference.reference().kind() == CandidateReferenceKind.CANDIDATE_SCHEMA
                && target.candidateSchemaId().equals(reference.reference().candidateSchemaId());
    }

    private static boolean matchesSlot(CandidateValue value, VisualElement element) {
        var scalar = element.multiplicity() == VisualMultiplicity.ONE
                ? value
                : value.kind() == CandidateValueKind.ARRAY ? value.items() : null;
        if (scalar == null || scalar.kind() == CandidateValueKind.ARRAY
                || scalar.kind() == CandidateValueKind.REFERENCE) return false;
        if (element.valueHint() == VisualValueHint.UNRESOLVED) {
            return scalar.kind() == CandidateValueKind.UNRESOLVED
                    || scalar.kind() == CandidateValueKind.CONFLICT;
        }
        return scalar.kind().name().equals(element.valueHint().name());
    }

    private static boolean hasPlannedEvidence(
            List<CandidateEvidence> actual,
            List<String> supportingElementIds,
            VisualElementInventory inventory
    ) {
        for (var elementId : supportingElementIds) {
            var expected = inventory.requireElement(elementId).evidence();
            if (actual.stream().anyMatch(expected::contains)) return true;
        }
        return false;
    }

    private static CandidateProblem problem(String code, Object item, String pointer) {
        var itemId = item instanceof CandidateSchema schema ? schema.candidateSchemaId()
                : item instanceof CandidateField field ? field.candidateFieldId() : null;
        return new CandidateProblem(
                code, CandidateProblemSeverity.BLOCKER, itemId, pointer, Map.of()
        );
    }
}

