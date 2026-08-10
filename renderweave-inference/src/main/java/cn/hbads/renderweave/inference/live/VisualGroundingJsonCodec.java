package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.ValueInstantiationException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict decoders for region-grounded visual contracts; view evidence is canonicalized before persistence. */
final class VisualGroundingJsonCodec {
    private static final int MAX_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    GroundedElementInventory parseElements(
            String value,
            VisualViewPlan views,
            List<String> sourceArtifactIds
    ) {
        try {
            var response = decode(value, GroundingOutput.class, "VISUAL_GROUNDING");
            if (!VisualGroundingPlan.VERSION.equals(response.contractVersion())) {
                throw invalid("VISUAL_GROUNDING_VERSION_INVALID", null);
            }
            var regions = classified("VISUAL_GROUNDING_REGION_INVALID", () ->
                    response.regions().stream().map(region -> new VisualRegion(
                            region.regionId(), region.parentRegionId(), region.kind(), region.multiplicity(),
                            region.readingOrder(), region.repeatGroupId(),
                            originalEvidence(region.evidence(), views)
                    )).toList()
            );
            var elements = classified("VISUAL_GROUNDING_ELEMENT_INVALID", () ->
                    response.elements().stream().map(element -> new VisualElement(
                            element.elementId(), element.kind(), element.proposedKey(), element.displayName(),
                            element.multiplicity(), element.valueHint(),
                            originalEvidence(element.evidence(), views)
                    )).toList()
            );
            var inventory = classified("VISUAL_GROUNDING_ELEMENT_INVALID", () ->
                    new VisualElementInventory(VisualElementInventory.VERSION, elements)
            );
            var grounding = classified("VISUAL_GROUNDING_REGION_FOREST_INVALID", () ->
                    new VisualGroundingPlan(
                    VisualGroundingPlan.VERSION, regions,
                    response.elements().stream().map(element -> new VisualElementRegionOwnership(
                            element.elementId(), element.regionIds()
                    )).toList()
                    )
            );
            classified("VISUAL_GROUNDING_ARTIFACT_COVERAGE_INVALID", () -> {
                inventory.requireKnownArtifacts(Set.copyOf(sourceArtifactIds));
                grounding.requireKnownArtifacts(sourceArtifactIds);
            });
            classified("VISUAL_GROUNDING_ELEMENT_OWNERSHIP_INVALID", () ->
                    grounding.requireConsistentWith(inventory)
            );
            return new GroundedElementInventory(inventory, grounding);
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("VISUAL_GROUNDING_CONTRACT_INVALID", failure);
        }
    }

    GroundedHierarchyPlan parseHierarchy(
            String value,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding
    ) {
        try {
            var response = decode(value, HierarchyOutput.class, "VISUAL_HIERARCHY_V2");
            if (!VisualHierarchyPlan.VERSION_V2.equals(response.contractVersion())) {
                throw invalid("VISUAL_HIERARCHY_V2_VERSION_INVALID", null);
            }
            var hierarchy = classified("VISUAL_HIERARCHY_V2_TOPOLOGY_INVALID", () ->
                    new VisualHierarchyPlan(
                    VisualHierarchyPlan.VERSION_V2, response.rootEntityId(),
                    response.entities().stream().map(entity -> new VisualEntityPlan(
                            entity.entityId(), entity.schemaKey(), entity.displayName(),
                            entity.supportingElementIds()
                    )).toList(),
                    response.relationships().stream().map(relationship -> new VisualRelationshipPlan(
                            relationship.relationshipId(), relationship.parentEntityId(),
                            relationship.childEntityId(), relationship.fieldKey(), relationship.displayName(),
                            relationship.cardinality(), relationship.supportingElementIds()
                    )).toList()
                    )
            );
            classified("VISUAL_HIERARCHY_V2_TOPOLOGY_INVALID", () ->
                    hierarchy.requireConsistentWith(inventory)
            );
            var entityRegions = classified("VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID", () ->
                    new VisualEntityRegionPlan(
                    VisualEntityRegionPlan.VERSION,
                    response.entities().stream().map(entity -> new VisualEntityRegionOwnership(
                            entity.entityId(), entity.regionIds()
                    )).toList(),
                    response.relationships().stream().map(relationship ->
                            new VisualRelationshipRegionOwnership(
                                    relationship.relationshipId(), relationship.regionId()
                            )).toList()
                    )
            );
            classified("VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID", () ->
                    entityRegions.requireConsistentWith(hierarchy, grounding)
            );
            return new GroundedHierarchyPlan(hierarchy, entityRegions);
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("VISUAL_HIERARCHY_V2_CONTRACT_INVALID", failure);
        }
    }

    VisualElementBindingPlan parseBindings(
            String value,
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy,
            VisualGroundingPlan grounding,
            VisualEntityRegionPlan entityRegions
    ) {
        try {
            var response = decode(value, BindingOutput.class, "VISUAL_BINDINGS_V2");
            if (!VisualElementBindingPlan.VERSION_V2.equals(response.contractVersion())) {
                throw invalid("VISUAL_BINDINGS_V2_VERSION_INVALID", null);
            }
            var result = classified("VISUAL_BINDINGS_V2_COVERAGE_INVALID", () ->
                    new VisualElementBindingPlan(
                    VisualElementBindingPlan.VERSION_V2,
                    response.bindings().stream().map(binding ->
                            new VisualElementBinding(binding.elementId(), binding.entityId())
                    ).toList()
                    )
            );
            classified("VISUAL_BINDINGS_V2_COVERAGE_INVALID", () ->
                    result.requireConsistentWith(inventory, hierarchy)
            );
            classified("VISUAL_BINDINGS_V2_REGION_OWNERSHIP_INVALID", () ->
                    entityRegions.requireBindingsConsistent(result, grounding)
            );
            return result;
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("VISUAL_BINDINGS_V2_CONTRACT_INVALID", failure);
        }
    }

    String write(Object value) {
        try {
            var result = JSON.writeValueAsString(value);
            requireBounded(result);
            return result;
        } catch (Exception failure) {
            throw invalid("VISUAL_GROUNDING_ENCODING_INVALID", failure);
        }
    }

    private static <T> T decode(String value, Class<T> type, String prefix) {
        try {
            requireBounded(value);
        } catch (Exception failure) {
            throw invalid(prefix + "_OUTPUT_BOUNDS_INVALID", failure);
        }
        try {
            return JSON.readValue(value, type);
        } catch (Exception failure) {
            throw invalid(classifyJsonFailure(prefix, failure), failure);
        }
    }

    private static String classifyJsonFailure(String prefix, Throwable failure) {
        if (containsType(failure, "UnrecognizedPropertyException")) {
            return prefix + "_JSON_UNKNOWN_MEMBER";
        }
        var invalidFormat = findCause(failure, InvalidFormatException.class);
        if (invalidFormat != null) {
            var target = invalidFormat.getTargetType();
            return prefix + (target != null && target.isEnum()
                    ? "_JSON_ENUM_INVALID" : "_JSON_FORMAT_INVALID");
        }
        if (findCause(failure, ValueInstantiationException.class) != null) {
            return prefix + "_JSON_CONSTRUCTOR_INVALID";
        }
        if (findCause(failure, MismatchedInputException.class) != null) {
            if (messageStartsWithForType(
                    failure, "MismatchedInputException", "trailing token (`jsontoken."
            )) {
                return prefix + "_JSON_TRAILING_CONTENT";
            }
            return prefix + "_JSON_SHAPE_INVALID";
        }
        if (containsType(failure, "StreamReadException")
                || containsType(failure, "UnexpectedEndOfInputException")) {
            if (messageStartsWithForType(failure, "StreamReadException", "duplicate field")
                    || messageStartsWithForType(
                            failure, "StreamReadException", "duplicate object property"
                    )) {
                return prefix + "_JSON_DUPLICATE_MEMBER";
            }
            return prefix + "_JSON_SYNTAX_INVALID";
        }
        return prefix + "_JSON_OTHER";
    }

    private static boolean containsType(Throwable failure, String simpleName) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (isType(cause, simpleName)) return true;
        }
        return false;
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> expectedType) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (expectedType.isInstance(cause)) return expectedType.cast(cause);
        }
        return null;
    }

    private static boolean messageStartsWithForType(
            Throwable failure,
            String simpleName,
            String prefix
    ) {
        var expected = prefix.toLowerCase(java.util.Locale.ROOT);
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (!isType(cause, simpleName)) continue;
            var message = cause.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).startsWith(expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isType(Throwable failure, String simpleName) {
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().equals(simpleName)) return true;
        }
        return false;
    }

    private static <T> T classified(String code, CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(code, failure);
        }
    }

    private static void classified(String code, CheckedRunnable runnable) {
        classified(code, () -> {
            runnable.run();
            return null;
        });
    }

    private static List<CandidateEvidence> originalEvidence(
            List<VisualViewEvidence> evidence,
            VisualViewPlan views
    ) {
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > VisualAnalysisValidation.MAX_EVIDENCE_PER_ITEM) {
            throw new IllegalArgumentException("View evidence count is invalid");
        }
        var result = new ArrayList<CandidateEvidence>();
        for (var item : evidence) result.add(views.toOriginalEvidence(item));
        return List.copyOf(result);
    }

    private static void requireBounded(String value) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Visual grounding JSON exceeds its boundary");
        }
    }

    private static InvalidVisualAnalysisException invalid(String code, Throwable cause) {
        return new InvalidVisualAnalysisException(code, "Visual grounding output is invalid", cause);
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private record GroundingOutput(
            String contractVersion,
            List<RegionOutput> regions,
            List<ElementOutput> elements
    ) {
        private GroundingOutput {
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        }
    }

    private record RegionOutput(
            String regionId,
            String parentRegionId,
            VisualRegionKind kind,
            VisualMultiplicity multiplicity,
            int readingOrder,
            String repeatGroupId,
            List<VisualViewEvidence> evidence
    ) { }

    private record ElementOutput(
            String elementId,
            VisualElementKind kind,
            String proposedKey,
            String displayName,
            VisualMultiplicity multiplicity,
            VisualValueHint valueHint,
            List<String> regionIds,
            List<VisualViewEvidence> evidence
    ) { }

    private record HierarchyOutput(
            String contractVersion,
            String rootEntityId,
            List<EntityOutput> entities,
            List<RelationshipOutput> relationships
    ) {
        private HierarchyOutput {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relationships = List.copyOf(Objects.requireNonNull(relationships, "relationships"));
        }
    }

    private record EntityOutput(
            String entityId,
            String schemaKey,
            String displayName,
            List<String> regionIds,
            List<String> supportingElementIds
    ) { }

    private record RelationshipOutput(
            String relationshipId,
            String parentEntityId,
            String childEntityId,
            String fieldKey,
            String displayName,
            VisualMultiplicity cardinality,
            String regionId,
            List<String> supportingElementIds
    ) { }

    private record BindingOutput(String contractVersion, List<BindingItem> bindings) {
        private BindingOutput {
            bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        }
    }

    private record BindingItem(String elementId, String entityId) { }
}

record GroundedElementInventory(
        VisualElementInventory inventory,
        VisualGroundingPlan grounding
) {
    GroundedElementInventory {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
    }
}

record GroundedHierarchyPlan(
        VisualHierarchyPlan hierarchy,
        VisualEntityRegionPlan entityRegions
) {
    GroundedHierarchyPlan {
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(entityRegions, "entityRegions");
    }
}
