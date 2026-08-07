package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.schema.identity.FieldKey;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CandidateValidator {

    public List<CandidateProblem> validate(
            CandidateBundle bundle,
            CandidateValidationContext context
    ) {
        var problems = new ArrayList<CandidateProblem>();
        if (!CandidateBundle.CONTRACT_VERSION.equals(bundle.contractVersion())) {
            add(problems, "CANDIDATE_VERSION_UNSUPPORTED", CandidateProblemSeverity.BLOCKER,
                    null, "/contractVersion", Map.of("actual", bundle.contractVersion()));
        }
        if (bundle.schemas().isEmpty()) {
            add(problems, "CANDIDATE_SCHEMA_MISSING", CandidateProblemSeverity.BLOCKER,
                    null, "/schemas", Map.of());
            return List.copyOf(problems);
        }

        var schemas = new LinkedHashMap<UUID, CandidateSchema>();
        var activeSchemas = new LinkedHashMap<UUID, CandidateSchema>();
        var allItemIds = new HashSet<UUID>();
        var proposedKeys = new HashMap<String, UUID>();
        for (var schemaIndex = 0; schemaIndex < bundle.schemas().size(); schemaIndex++) {
            var schema = bundle.schemas().get(schemaIndex);
            var pointer = "/schemas/" + schemaIndex;
            if (schemas.putIfAbsent(schema.candidateSchemaId(), schema) != null) {
                add(problems, "CANDIDATE_SCHEMA_ID_DUPLICATE", CandidateProblemSeverity.BLOCKER,
                        schema.candidateSchemaId(), pointer + "/candidateSchemaId", Map.of());
            }
            validateItemId(schema.candidateSchemaId(), pointer + "/candidateSchemaId", allItemIds, problems);
            var schemaRemoved = schema.assessment().resolution() == CandidateResolution.REMOVED;
            if (!schemaRemoved) activeSchemas.putIfAbsent(schema.candidateSchemaId(), schema);
            if (schemaRemoved && schema.candidateSchemaId().equals(bundle.rootCandidateSchemaId())) {
                add(problems, "CANDIDATE_ROOT_REMOVED", CandidateProblemSeverity.BLOCKER,
                        schema.candidateSchemaId(), pointer + "/assessment/resolution", Map.of());
            }
            if (schemaRemoved) {
                validateAssessment(schema.source(), schema.assessment(), schema.candidateSchemaId(),
                        pointer + "/assessment", context, problems);
                for (var fieldIndex = 0; fieldIndex < schema.fields().size(); fieldIndex++) {
                    var field = schema.fields().get(fieldIndex);
                    validateItemId(field.candidateFieldId(), pointer + "/fields/" + fieldIndex
                            + "/candidateFieldId", allItemIds, problems);
                }
                continue;
            }
            validateSchemaKey(schema, pointer, proposedKeys, problems);
            validateAssessment(schema.source(), schema.assessment(), schema.candidateSchemaId(),
                    pointer + "/assessment", context, problems);
            if (schema.displayName() == null || schema.displayName().isBlank()) {
                add(problems, "CANDIDATE_DISPLAY_NAME_MISSING", CandidateProblemSeverity.BLOCKER,
                        schema.candidateSchemaId(), pointer + "/displayName", Map.of());
            }

            var fieldKeys = new HashSet<String>();
            for (var fieldIndex = 0; fieldIndex < schema.fields().size(); fieldIndex++) {
                var field = schema.fields().get(fieldIndex);
                var fieldPointer = pointer + "/fields/" + fieldIndex;
                validateItemId(field.candidateFieldId(), fieldPointer + "/candidateFieldId", allItemIds, problems);
                validateFieldKey(field, fieldPointer, fieldKeys, problems);
                validateAssessment(field.source(), field.assessment(), field.candidateFieldId(),
                        fieldPointer + "/assessment", context, problems);
                if (field.assessment().resolution() == CandidateResolution.REMOVED) continue;
                if (field.required() && field.source() == CandidateSource.AI
                        && !userResolved(field.assessment().resolution())) {
                    add(problems, "AI_REQUIRED_UNCONFIRMED", CandidateProblemSeverity.BLOCKER,
                            field.candidateFieldId(), fieldPointer + "/required", Map.of());
                }
                validateValue(field.value(), field.candidateFieldId(), fieldPointer + "/value",
                        false, field.source(), field.assessment().resolution(), problems);
            }
        }

        if (!schemas.containsKey(bundle.rootCandidateSchemaId())) {
            add(problems, "CANDIDATE_ROOT_NOT_FOUND", CandidateProblemSeverity.BLOCKER,
                    bundle.rootCandidateSchemaId(), "/rootCandidateSchemaId", Map.of());
            return List.copyOf(problems);
        }
        validateGraph(bundle.rootCandidateSchemaId(), activeSchemas, problems);
        return List.copyOf(problems);
    }

    private static void validateSchemaKey(
            CandidateSchema schema,
            String pointer,
            Map<String, UUID> proposedKeys,
            List<CandidateProblem> problems
    ) {
        if (schema.proposedSchemaKey() == null) {
            add(problems, "CANDIDATE_SCHEMA_KEY_UNRESOLVED", CandidateProblemSeverity.BLOCKER,
                    schema.candidateSchemaId(), pointer + "/proposedSchemaKey", Map.of());
            return;
        }
        try {
            SchemaKey.userProvided(schema.proposedSchemaKey());
        } catch (RuntimeException invalid) {
            add(problems, "CANDIDATE_SCHEMA_KEY_INVALID", CandidateProblemSeverity.BLOCKER,
                    schema.candidateSchemaId(), pointer + "/proposedSchemaKey",
                    Map.of("value", schema.proposedSchemaKey()));
            return;
        }
        var previous = proposedKeys.putIfAbsent(schema.proposedSchemaKey(), schema.candidateSchemaId());
        if (previous != null) {
            add(problems, "CANDIDATE_SCHEMA_KEY_DUPLICATE", CandidateProblemSeverity.BLOCKER,
                    schema.candidateSchemaId(), pointer + "/proposedSchemaKey",
                    Map.of("otherCandidateSchemaId", previous.toString()));
        }
    }

    private static void validateFieldKey(
            CandidateField field,
            String pointer,
            Set<String> fieldKeys,
            List<CandidateProblem> problems
    ) {
        if (field.proposedFieldKey() == null) {
            add(problems, "CANDIDATE_FIELD_KEY_UNRESOLVED", CandidateProblemSeverity.BLOCKER,
                    field.candidateFieldId(), pointer + "/proposedFieldKey", Map.of());
            return;
        }
        try {
            FieldKey.of(field.proposedFieldKey());
        } catch (RuntimeException invalid) {
            add(problems, "CANDIDATE_FIELD_KEY_INVALID", CandidateProblemSeverity.BLOCKER,
                    field.candidateFieldId(), pointer + "/proposedFieldKey", Map.of());
            return;
        }
        if (!fieldKeys.add(field.proposedFieldKey())) {
            add(problems, "CANDIDATE_FIELD_KEY_DUPLICATE", CandidateProblemSeverity.BLOCKER,
                    field.candidateFieldId(), pointer + "/proposedFieldKey",
                    Map.of("value", field.proposedFieldKey()));
        }
    }

    private static void validateItemId(
            UUID itemId,
            String pointer,
            Set<UUID> allItemIds,
            List<CandidateProblem> problems
    ) {
        if (!allItemIds.add(itemId)) {
            add(problems, "CANDIDATE_ITEM_ID_DUPLICATE", CandidateProblemSeverity.BLOCKER,
                    itemId, pointer, Map.of());
        }
    }

    private static void validateAssessment(
            CandidateSource source,
            CandidateAssessment assessment,
            UUID itemId,
            String pointer,
            CandidateValidationContext context,
            List<CandidateProblem> problems
    ) {
        if (source == CandidateSource.USER) {
            if (assessment.confidenceBps() != null || assessment.inferred() || !assessment.evidence().isEmpty()) {
                add(problems, "USER_ITEM_HAS_AI_PROVENANCE", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer, Map.of());
            }
            if (assessment.resolution() != CandidateResolution.NOT_REQUIRED
                    && assessment.resolution() != CandidateResolution.REMOVED) {
                add(problems, "USER_ITEM_RESOLUTION_INVALID", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer + "/resolution", Map.of());
            }
            return;
        }

        var confidence = assessment.confidenceBps();
        if (confidence == null || confidence < 0 || confidence > 10_000) {
            add(problems, "AI_CONFIDENCE_INVALID", CandidateProblemSeverity.BLOCKER,
                    itemId, pointer + "/confidenceBps", Map.of());
        }
        if (assessment.evidence().isEmpty()) {
            add(problems, "AI_EVIDENCE_MISSING", CandidateProblemSeverity.BLOCKER,
                    itemId, pointer + "/evidence", Map.of());
        }
        for (var evidenceIndex = 0; evidenceIndex < assessment.evidence().size(); evidenceIndex++) {
            validateEvidence(assessment.evidence().get(evidenceIndex), itemId,
                    pointer + "/evidence/" + evidenceIndex, context, problems);
        }
        if (confidence != null && confidence < context.lowConfidenceThresholdBps()) {
            if (assessment.resolution() == CandidateResolution.NOT_REQUIRED) {
                add(problems, "LOW_CONFIDENCE_STATE_INVALID", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer + "/resolution", Map.of());
            } else if (assessment.resolution() == CandidateResolution.UNRESOLVED) {
                add(problems, "LOW_CONFIDENCE_UNRESOLVED", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer + "/resolution",
                        Map.of("confidenceBps", Integer.toString(confidence)));
            }
        }
    }

    private static void validateEvidence(
            CandidateEvidence evidence,
            UUID itemId,
            String pointer,
            CandidateValidationContext context,
            List<CandidateProblem> problems
    ) {
        if (evidence.kind() == CandidateEvidenceKind.IMAGE) {
            if (evidence.artifactId() == null || evidence.boundingBox() == null
                    || evidence.sampleIndex() != null || evidence.jsonPointer() != null) {
                add(problems, "IMAGE_EVIDENCE_SHAPE_INVALID", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer, Map.of());
                return;
            }
            if (!context.imageArtifactIds().contains(evidence.artifactId())) {
                add(problems, "IMAGE_EVIDENCE_ARTIFACT_UNKNOWN", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer + "/artifactId", Map.of());
            }
            var box = evidence.boundingBox();
            if (box.left() < 0 || box.top() < 0 || box.right() > 10_000 || box.bottom() > 10_000
                    || box.left() >= box.right() || box.top() >= box.bottom()) {
                add(problems, "IMAGE_EVIDENCE_BOUNDS_INVALID", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer + "/boundingBox", Map.of());
            }
            return;
        }
        if (evidence.kind() == CandidateEvidenceKind.JSON) {
            if (evidence.artifactId() != null || evidence.boundingBox() != null
                    || evidence.sampleIndex() == null || evidence.jsonPointer() == null) {
                add(problems, "JSON_EVIDENCE_SHAPE_INVALID", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer, Map.of());
                return;
            }
            if (evidence.sampleIndex() < 0 || evidence.sampleIndex() >= context.jsonSampleCount()) {
                add(problems, "JSON_EVIDENCE_SAMPLE_UNKNOWN", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer + "/sampleIndex", Map.of());
            }
            if (!validJsonPointer(evidence.jsonPointer())) {
                add(problems, "JSON_EVIDENCE_POINTER_INVALID", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer + "/jsonPointer", Map.of());
            }
            return;
        }
        add(problems, "EVIDENCE_KIND_INVALID", CandidateProblemSeverity.BLOCKER,
                itemId, pointer + "/kind", Map.of());
    }

    private static void validateValue(
            CandidateValue value,
            UUID itemId,
            String pointer,
            boolean insideArray,
            CandidateSource source,
            CandidateResolution resolution,
            List<CandidateProblem> problems
    ) {
        switch (value.kind()) {
            case ARRAY -> {
                if (value.items() == null || value.reference() != null) {
                    add(problems, "CANDIDATE_ARRAY_SHAPE_INVALID", CandidateProblemSeverity.BLOCKER,
                            itemId, pointer, Map.of());
                    return;
                }
                if (insideArray || value.items().kind() == CandidateValueKind.ARRAY) {
                    add(problems, "NESTED_ARRAY_UNSUPPORTED", CandidateProblemSeverity.BLOCKER,
                            itemId, pointer + "/items", Map.of());
                }
                validateValue(value.items(), itemId, pointer + "/items", true, source, resolution, problems);
            }
            case REFERENCE -> {
                if (value.reference() == null || value.items() != null) {
                    add(problems, "CANDIDATE_REFERENCE_SHAPE_INVALID", CandidateProblemSeverity.BLOCKER,
                            itemId, pointer, Map.of());
                } else {
                    validateReference(value.reference(), itemId, pointer + "/reference", problems);
                }
            }
            case UNRESOLVED -> {
                if (value.observedKinds().isEmpty()) {
                    add(problems, "UNRESOLVED_TYPE_EVIDENCE_MISSING", CandidateProblemSeverity.BLOCKER,
                            itemId, pointer + "/observedKinds", Map.of());
                }
                add(problems, "CANDIDATE_TYPE_UNRESOLVED", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer, Map.of());
            }
            case CONFLICT -> {
                if (value.observedKinds().size() < 2) {
                    add(problems, "CONFLICT_TYPE_EVIDENCE_INCOMPLETE", CandidateProblemSeverity.BLOCKER,
                            itemId, pointer + "/observedKinds", Map.of());
                }
                add(problems, "CANDIDATE_TYPE_CONFLICT", CandidateProblemSeverity.BLOCKER,
                        itemId, pointer, Map.of());
            }
            default -> {
                if (value.items() != null || value.reference() != null || !value.observedKinds().isEmpty()) {
                    add(problems, "CANDIDATE_SCALAR_SHAPE_INVALID", CandidateProblemSeverity.BLOCKER,
                            itemId, pointer, Map.of());
                }
            }
        }
        if (!value.constraints().isEmpty() && source == CandidateSource.AI && !userResolved(resolution)) {
            add(problems, "AI_CONSTRAINT_UNCONFIRMED", CandidateProblemSeverity.BLOCKER,
                    itemId, pointer + "/constraints", Map.of());
        }
    }

    private static void validateReference(
            CandidateReference reference,
            UUID itemId,
            String pointer,
            List<CandidateProblem> problems
    ) {
        if (reference.kind() == null) {
            add(problems, "CANDIDATE_REFERENCE_KIND_INVALID", CandidateProblemSeverity.BLOCKER,
                    itemId, pointer + "/kind", Map.of());
            return;
        }
        try {
            switch (reference.kind()) {
                case CANDIDATE_SCHEMA -> {
                    if (reference.candidateSchemaId() == null || reference.schemaKey() != null
                            || reference.versionTag() != null) throw new IllegalArgumentException();
                }
                case DRAFT -> {
                    if (reference.candidateSchemaId() != null || reference.versionTag() != null) {
                        throw new IllegalArgumentException();
                    }
                    SchemaKey.userProvided(reference.schemaKey());
                }
                case STATIC -> {
                    if (reference.candidateSchemaId() != null) throw new IllegalArgumentException();
                    var key = reference.schemaKey();
                    if (key != null && key.startsWith("system-")) SchemaKey.systemProvided(key);
                    else SchemaKey.userProvided(key);
                    VersionTag.of(reference.versionTag());
                }
            }
        } catch (RuntimeException invalid) {
            add(problems, "CANDIDATE_REFERENCE_SHAPE_INVALID", CandidateProblemSeverity.BLOCKER,
                    itemId, pointer, Map.of());
        }
    }

    private static void validateGraph(
            UUID rootCandidateSchemaId,
            Map<UUID, CandidateSchema> schemas,
            List<CandidateProblem> problems
    ) {
        var edges = new LinkedHashMap<UUID, List<UUID>>();
        for (var schema : schemas.values()) {
            var targets = new ArrayList<UUID>();
            for (var field : schema.fields()) {
                if (field.assessment().resolution() == CandidateResolution.REMOVED) continue;
                collectCandidateTargets(field.value(), targets);
            }
            edges.put(schema.candidateSchemaId(), List.copyOf(targets));
            for (var target : targets) {
                if (!schemas.containsKey(target)) {
                    add(problems, "CANDIDATE_REFERENCE_TARGET_MISSING", CandidateProblemSeverity.BLOCKER,
                            schema.candidateSchemaId(), "/schemas", Map.of("targetCandidateSchemaId", target.toString()));
                }
            }
        }

        var reachable = new LinkedHashSet<UUID>();
        var queue = new ArrayDeque<UUID>();
        queue.add(rootCandidateSchemaId);
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (!reachable.add(current)) continue;
            for (var target : edges.getOrDefault(current, List.of())) {
                if (schemas.containsKey(target)) queue.addLast(target);
            }
        }
        for (var schema : schemas.values()) {
            if (!reachable.contains(schema.candidateSchemaId())) {
                add(problems, "CANDIDATE_SCHEMA_ORPHAN", CandidateProblemSeverity.BLOCKER,
                        schema.candidateSchemaId(), "/schemas", Map.of());
            }
        }

        var colors = new HashMap<UUID, Integer>();
        detectCycles(rootCandidateSchemaId, edges, colors, problems);
    }

    private static void collectCandidateTargets(CandidateValue value, List<UUID> targets) {
        if (value.kind() == CandidateValueKind.REFERENCE && value.reference() != null
                && value.reference().kind() == CandidateReferenceKind.CANDIDATE_SCHEMA
                && value.reference().candidateSchemaId() != null) {
            targets.add(value.reference().candidateSchemaId());
        }
        if (value.kind() == CandidateValueKind.ARRAY && value.items() != null) {
            collectCandidateTargets(value.items(), targets);
        }
    }

    private static void detectCycles(
            UUID current,
            Map<UUID, List<UUID>> edges,
            Map<UUID, Integer> colors,
            List<CandidateProblem> problems
    ) {
        colors.put(current, 1);
        for (var target : edges.getOrDefault(current, List.of())) {
            var color = colors.getOrDefault(target, 0);
            if (color == 1) {
                add(problems, "CANDIDATE_REFERENCE_CYCLE", CandidateProblemSeverity.BLOCKER,
                        current, "/schemas", Map.of("targetCandidateSchemaId", target.toString()));
            } else if (color == 0 && edges.containsKey(target)) {
                detectCycles(target, edges, colors, problems);
            }
        }
        colors.put(current, 2);
    }

    private static boolean userResolved(CandidateResolution resolution) {
        return resolution == CandidateResolution.CONFIRMED
                || resolution == CandidateResolution.RESOLVED_BY_EDIT
                || resolution == CandidateResolution.REMOVED;
    }

    private static boolean validJsonPointer(String pointer) {
        if (pointer.isEmpty()) return true;
        if (!pointer.startsWith("/")) return false;
        for (var index = 0; index < pointer.length(); index++) {
            if (pointer.charAt(index) != '~') continue;
            if (index + 1 >= pointer.length()) return false;
            var escaped = pointer.charAt(++index);
            if (escaped != '0' && escaped != '1') return false;
        }
        return true;
    }

    private static void add(
            List<CandidateProblem> problems,
            String code,
            CandidateProblemSeverity severity,
            UUID itemId,
            String pointer,
            Map<String, String> args
    ) {
        problems.add(new CandidateProblem(code, severity, itemId, pointer, args));
    }
}
