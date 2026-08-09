package cn.hbads.renderweave.inference.eval;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateReferenceKind;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** ID-independent whole-graph scorer for AC-021. Generated UUIDs and schema keys are never gold identity. */
public final class LiveCandidateEvaluator {
    public LiveEvaluationResult evaluate(
            LiveEvaluationCase gold,
            CandidateBundle candidate,
            List<CandidateProblem> problems
    ) {
        Objects.requireNonNull(gold, "gold");
        Objects.requireNonNull(candidate, "candidate");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        var expected = GoldGraph.from(gold);
        var actual = ActualGraph.from(candidate, problems);

        var matchedEntities = intersection(expected.entities(), actual.entities());
        var missingEntities = difference(expected.entities(), actual.entities());
        var unexpectedEntities = difference(actual.entities(), expected.entities());
        var matchedFields = intersection(expected.fields().keySet(), actual.fields().keySet());
        var missingFields = difference(expected.fields().keySet(), actual.fields().keySet());
        var unexpectedFields = difference(actual.fields().keySet(), expected.fields().keySet());
        var typeMismatches = new ArrayList<String>();
        var matchedTypes = 0;
        var expectedSupportedTypes = 0;
        var uncertainTypeHallucinations = 0;
        for (var entry : expected.fields().entrySet()) {
            var actualShape = actual.fields().get(entry.getKey());
            if (supported(entry.getValue())) {
                expectedSupportedTypes++;
                if (entry.getValue().equals(actualShape)) matchedTypes++;
                else if (actualShape != null) {
                    typeMismatches.add(entry.getKey() + ":" + entry.getValue() + "!=" + actualShape);
                }
            } else if (actualShape != null && !entry.getValue().equals(actualShape)) {
                // Gold uncertainty is an explicit safety boundary. Replacing it with a concrete
                // type (or a different uncertainty/array topology) is an unsupported assertion,
                // not a free pass merely because supported-type accuracy excludes uncertain gold.
                typeMismatches.add(entry.getKey() + ":" + entry.getValue() + "!=" + actualShape);
                uncertainTypeHallucinations++;
            }
        }
        var matchedEdges = intersection(expected.edges(), actual.edges());
        var missingEdges = difference(expected.edges(), actual.edges()).stream()
                .map(value -> "missing:" + value).toList();
        var unexpectedEdges = difference(actual.edges(), expected.edges()).stream()
                .map(value -> "unexpected:" + value).toList();
        var edgeMismatches = new ArrayList<String>(missingEdges);
        edgeMismatches.addAll(unexpectedEdges);

        var criticalHallucinations = Math.addExact(uncertainTypeHallucinations,
                Math.addExact(actual.unsafeAssertionCount(), Math.addExact(
                Math.max(0, actual.entityCount() - matchedEntities.size()),
                Math.addExact(
                        Math.max(0, actual.fieldCount() - matchedFields.size()),
                        Math.max(0, actual.edgeCount() - matchedEdges.size())
                )
        )));
        var bundleContractBps = actual.contractValid() ? 10_000 : 0;
        var dagValidityBps = actual.dagValid() ? 10_000 : 0;
        var exact = bundleContractBps == 10_000
                && matchedEntities.size() == expected.entities().size()
                && actual.entityCount() == expected.entities().size()
                && matchedFields.size() == expected.fields().size()
                && actual.fieldCount() == expected.fields().size()
                && matchedTypes == expectedSupportedTypes
                && matchedEdges.size() == expected.edges().size()
                && actual.edgeCount() == expected.edges().size()
                && actual.evidencePresentCount() == actual.evidenceExpectedCount()
                && dagValidityBps == 10_000 && criticalHallucinations == 0;
        var blockerCount = (int) problems.stream()
                .filter(problem -> problem.severity() == CandidateProblemSeverity.BLOCKER)
                .count();
        return new LiveEvaluationResult(
                gold.caseId(), "EVALUATED", exact, bundleContractBps,
                expected.entities().size(), actual.entityCount(), matchedEntities.size(),
                expected.fields().size(), actual.fieldCount(), matchedFields.size(),
                expectedSupportedTypes, matchedTypes,
                expected.edges().size(), actual.edgeCount(), matchedEdges.size(),
                actual.evidenceExpectedCount(), actual.evidencePresentCount(), dagValidityBps,
                criticalHallucinations, blockerCount,
                missingEntities, unexpectedEntities, missingFields, unexpectedFields,
                List.copyOf(typeMismatches), List.copyOf(edgeMismatches)
        );
    }

    /** Records an attempted case that never produced a strict reviewable Candidate. */
    public LiveEvaluationResult failure(LiveEvaluationCase gold, String outcomeCode) {
        Objects.requireNonNull(gold, "gold");
        if (outcomeCode == null || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("outcomeCode is invalid");
        }
        var expected = GoldGraph.from(gold);
        return new LiveEvaluationResult(
                gold.caseId(), outcomeCode, false, 0,
                expected.entities().size(), 0, 0,
                expected.fields().size(), 0, 0,
                expected.supportedTypeCount(), 0,
                expected.edges().size(), 0, 0,
                expected.entities().size() + expected.fields().size(), 0,
                0, 0, 0,
                expected.entities().stream().sorted().toList(), List.of(),
                expected.fields().keySet().stream().sorted().toList(), List.of(), List.of(),
                expected.edges().stream().sorted().map(value -> "missing:" + value).toList()
        );
    }

    private static boolean supported(String shape) {
        return !shape.endsWith("UNRESOLVED") && !shape.endsWith("CONFLICT");
    }

    private static <T extends Comparable<? super T>> List<T> difference(Set<T> left, Set<T> right) {
        var value = new TreeSet<>(left);
        value.removeAll(right);
        return List.copyOf(value);
    }

    private static <T> Set<T> intersection(Set<T> left, Set<T> right) {
        var value = new HashSet<>(left);
        value.retainAll(right);
        return Set.copyOf(value);
    }

    private static String shape(CandidateValue value) {
        if (value == null) return "INVALID";
        if (value.kind() == CandidateValueKind.ARRAY) {
            return "ARRAY:" + shape(value.items());
        }
        return value.kind().name();
    }

    private static UUID candidateTarget(CandidateValue value) {
        if (value == null) return null;
        if (value.kind() == CandidateValueKind.ARRAY) return candidateTarget(value.items());
        if (value.kind() != CandidateValueKind.REFERENCE || value.reference() == null
                || value.reference().kind() != CandidateReferenceKind.CANDIDATE_SCHEMA) return null;
        return value.reference().candidateSchemaId();
    }

    private static boolean referenceValue(CandidateValue value) {
        if (value == null) return false;
        if (value.kind() == CandidateValueKind.ARRAY) return referenceValue(value.items());
        return value.kind() == CandidateValueKind.REFERENCE;
    }

    private static List<CandidateField> activeFields(CandidateSchema schema) {
        return schema.fields().stream()
                .filter(field -> field.assessment().resolution() != CandidateResolution.REMOVED)
                .sorted(Comparator.comparing(
                                CandidateField::proposedFieldKey,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        ).thenComparing(CandidateField::candidateFieldId))
                .toList();
    }

    private record GoldGraph(
            Set<String> entities,
            Map<String, String> fields,
            Set<String> edges,
            int supportedTypeCount
    ) {
        private static GoldGraph from(LiveEvaluationCase gold) {
            var entities = new TreeSet<>(gold.expectedSchemas().keySet());
            var fields = new LinkedHashMap<String, String>();
            var edges = new TreeSet<String>();
            gold.expectedSchemas().forEach((schemaPath, expectedFields) ->
                    expectedFields.forEach((fieldKey, expectedShape) -> {
                        var identity = LiveEvaluationCase.fieldIdentity(schemaPath, fieldKey);
                        fields.put(identity, expectedShape);
                        if ("REFERENCE".equals(expectedShape)
                                || "ARRAY:REFERENCE".equals(expectedShape)) {
                            edges.add(identity + "->" + LiveEvaluationCase.childPath(schemaPath, fieldKey));
                        }
                    })
            );
            var supportedTypes = (int) fields.values().stream()
                    .filter(LiveCandidateEvaluator::supported).count();
            return new GoldGraph(Set.copyOf(entities), Map.copyOf(fields), Set.copyOf(edges), supportedTypes);
        }
    }

    private record ActualGraph(
            Set<String> entities,
            int entityCount,
            Map<String, String> fields,
            int fieldCount,
            Set<String> edges,
            int edgeCount,
            int evidenceExpectedCount,
            int evidencePresentCount,
            boolean contractValid,
            boolean dagValid,
            int unsafeAssertionCount
    ) {
        private static ActualGraph from(CandidateBundle candidate, List<CandidateProblem> problems) {
            var initialProvenanceViolations = candidate.schemas().stream()
                    .mapToInt(schema -> initialInferenceItemValid(schema.source(), schema.assessment()) ? 0 : 1)
                    .sum();
            initialProvenanceViolations = Math.addExact(
                    initialProvenanceViolations,
                    candidate.schemas().stream().flatMap(schema -> schema.fields().stream())
                            .mapToInt(field -> initialInferenceItemValid(
                                    field.source(), field.assessment()
                            ) ? 0 : 1)
                            .sum()
            );
            var active = candidate.schemas().stream()
                    .filter(schema -> schema.assessment().resolution() != CandidateResolution.REMOVED)
                    .toList();
            var schemas = new LinkedHashMap<UUID, CandidateSchema>();
            var duplicateSchemaIds = false;
            for (var schema : active) {
                if (schemas.putIfAbsent(schema.candidateSchemaId(), schema) != null) duplicateSchemaIds = true;
            }
            var root = schemas.get(candidate.rootCandidateSchemaId());
            var paths = canonicalPaths(root, schemas);
            var reachable = new HashSet<>(paths.keySet());
            var orphanOrdinal = 0;
            for (var schema : schemas.values()) {
                if (!paths.containsKey(schema.candidateSchemaId())) {
                    paths.put(schema.candidateSchemaId(), "/@orphan-" + (++orphanOrdinal));
                }
            }

            var entityIdentities = new LinkedHashSet<>(paths.values());
            var fields = new LinkedHashMap<String, String>();
            var edges = new LinkedHashSet<String>();
            var fieldCount = 0;
            var edgeCount = 0;
            var evidenceExpected = 0;
            var evidencePresent = 0;
            var unsafeAssertions = initialProvenanceViolations;
            var allItemIds = new HashSet<UUID>();
            var duplicateItemIds = false;
            var allAi = true;
            var schemaKeys = new HashSet<String>();
            var duplicateSchemaKeys = false;
            for (var schema : active) {
                if (!allItemIds.add(schema.candidateSchemaId())) duplicateItemIds = true;
                if (schema.source() != CandidateSource.AI) {
                    allAi = false;
                }
                if (schema.proposedSchemaKey() == null || !schemaKeys.add(schema.proposedSchemaKey())) {
                    duplicateSchemaKeys = true;
                }
                evidenceExpected++;
                if (!schema.assessment().evidence().isEmpty()) evidencePresent++;
                var path = paths.getOrDefault(schema.candidateSchemaId(), "/@invalid");
                var fieldKeys = new HashSet<String>();
                var invalidFieldKeys = false;
                var invalidOrdinal = 0;
                for (var field : activeFields(schema)) {
                    fieldCount++;
                    if (!allItemIds.add(field.candidateFieldId())) duplicateItemIds = true;
                    if (field.source() != CandidateSource.AI) {
                        allAi = false;
                    }
                    if (field.required()) unsafeAssertions++;
                    if (field.value() != null && !field.value().constraints().isEmpty()) unsafeAssertions++;
                    evidenceExpected++;
                    if (!field.assessment().evidence().isEmpty()) evidencePresent++;
                    var key = field.proposedFieldKey();
                    if (key == null || key.isBlank() || !fieldKeys.add(key)) {
                        invalidFieldKeys = true;
                        key = "@invalid-" + (++invalidOrdinal);
                    }
                    var identity = LiveEvaluationCase.fieldIdentity(path, key);
                    fields.putIfAbsent(identity, shape(field.value()));
                    if (referenceValue(field.value())) {
                        edgeCount++;
                        var target = candidateTarget(field.value());
                        var targetPath = target == null ? "/@external" : paths.getOrDefault(target, "/@missing");
                        edges.add(identity + "->" + targetPath);
                    }
                }
                if (invalidFieldKeys) duplicateItemIds = true;
            }
            var contractProblems = problems.stream().anyMatch(ActualGraph::contractProblem);
            var contractValid = CandidateBundle.CONTRACT_VERSION.equals(candidate.contractVersion())
                    && root != null && !active.isEmpty() && !duplicateSchemaIds && !duplicateItemIds
                    && !duplicateSchemaKeys && allAi && initialProvenanceViolations == 0
                    && !contractProblems;
            var dagValid = root != null && !duplicateSchemaIds
                    && reachable.size() == schemas.size() && graphIsDag(root.candidateSchemaId(), schemas);
            return new ActualGraph(
                    Set.copyOf(entityIdentities), active.size(), Map.copyOf(fields), fieldCount,
                    Set.copyOf(edges), edgeCount, evidenceExpected, evidencePresent,
                    contractValid, dagValid, unsafeAssertions
            );
        }

        private static boolean initialInferenceItemValid(
                CandidateSource source,
                cn.hbads.renderweave.inference.candidate.CandidateAssessment assessment
        ) {
            return source == CandidateSource.AI
                    && assessment.inferred()
                    && (assessment.resolution() == CandidateResolution.NOT_REQUIRED
                    || assessment.resolution() == CandidateResolution.UNRESOLVED);
        }

        private static Map<UUID, String> canonicalPaths(
                CandidateSchema root,
                Map<UUID, CandidateSchema> schemas
        ) {
            var paths = new LinkedHashMap<UUID, String>();
            if (root == null) return paths;
            paths.put(root.candidateSchemaId(), "/");
            var queue = new ArrayDeque<UUID>();
            queue.add(root.candidateSchemaId());
            while (!queue.isEmpty()) {
                var sourceId = queue.removeFirst();
                var source = schemas.get(sourceId);
                if (source == null) continue;
                for (var field : activeFields(source)) {
                    var target = candidateTarget(field.value());
                    if (target == null || !schemas.containsKey(target) || paths.containsKey(target)) continue;
                    var key = field.proposedFieldKey();
                    if (key == null || key.isBlank()) key = "@invalid";
                    paths.put(target, LiveEvaluationCase.childPath(paths.get(sourceId), key));
                    queue.addLast(target);
                }
            }
            return paths;
        }

        private static boolean graphIsDag(UUID root, Map<UUID, CandidateSchema> schemas) {
            var colors = new HashMap<UUID, Integer>();
            return visit(root, schemas, colors);
        }

        private static boolean visit(
                UUID current,
                Map<UUID, CandidateSchema> schemas,
                Map<UUID, Integer> colors
        ) {
            colors.put(current, 1);
            var schema = schemas.get(current);
            if (schema == null) return false;
            for (var field : activeFields(schema)) {
                var target = candidateTarget(field.value());
                if (target == null) continue;
                if (!schemas.containsKey(target)) return false;
                var color = colors.getOrDefault(target, 0);
                if (color == 1 || color == 0 && !visit(target, schemas, colors)) return false;
            }
            colors.put(current, 2);
            return true;
        }

        private static boolean contractProblem(CandidateProblem problem) {
            return switch (problem.code()) {
                case "CANDIDATE_VERSION_UNSUPPORTED", "CANDIDATE_SCHEMA_MISSING",
                        "CANDIDATE_SCHEMA_ID_DUPLICATE", "CANDIDATE_ITEM_ID_DUPLICATE",
                        "CANDIDATE_ROOT_REMOVED", "CANDIDATE_ROOT_NOT_FOUND",
                        "CANDIDATE_SCHEMA_KEY_UNRESOLVED", "CANDIDATE_SCHEMA_KEY_INVALID",
                        "CANDIDATE_SCHEMA_KEY_DUPLICATE", "CANDIDATE_DISPLAY_NAME_MISSING",
                        "CANDIDATE_FIELD_KEY_UNRESOLVED", "CANDIDATE_FIELD_KEY_INVALID",
                        "CANDIDATE_FIELD_KEY_DUPLICATE", "USER_ITEM_HAS_AI_PROVENANCE",
                        "USER_ITEM_RESOLUTION_INVALID", "AI_CONFIDENCE_INVALID",
                        "INFERENCE_SOURCE_INVALID", "INFERENCE_PROVENANCE_INVALID",
                        "INFERENCE_RESOLUTION_INVALID",
                        "IMAGE_EVIDENCE_SHAPE_INVALID", "IMAGE_EVIDENCE_ARTIFACT_UNKNOWN",
                        "IMAGE_EVIDENCE_BOUNDS_INVALID", "JSON_EVIDENCE_SHAPE_INVALID",
                        "JSON_EVIDENCE_SAMPLE_UNKNOWN", "JSON_EVIDENCE_POINTER_INVALID",
                        "JSON_EVIDENCE_LOCATION_UNKNOWN", "JSON_EVIDENCE_ITEM_MISMATCH",
                        "EVIDENCE_KIND_INVALID", "CANDIDATE_ARRAY_SHAPE_INVALID",
                        "NESTED_ARRAY_UNSUPPORTED", "CANDIDATE_REFERENCE_SHAPE_INVALID",
                        "UNRESOLVED_TYPE_EVIDENCE_MISSING", "CONFLICT_TYPE_EVIDENCE_INCOMPLETE",
                        "CANDIDATE_SCALAR_SHAPE_INVALID" -> true;
                default -> false;
            };
        }
    }
}
