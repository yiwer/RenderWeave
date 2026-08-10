package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
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
    private static final VisualSemanticVerifier SEMANTIC_VERIFIER = new VisualSemanticVerifier();
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
            var semanticIssues = SEMANTIC_VERIFIER.verifyObservation(inventory, grounding);
            if (!semanticIssues.isEmpty()) {
                throw invalid(semanticIssues.getFirst().code(), null);
            }
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
            var entities = response.entities().stream().map(entity -> classified(
                    "VISUAL_HIERARCHY_V2_ENTITY_INVALID", () -> new VisualEntityPlan(
                            entity.entityId(), entity.schemaKey(), entity.displayName(),
                            entity.supportingElementIds()
                    )
            )).toList();
            var relationships = response.relationships().stream().map(relationship -> classified(
                    "VISUAL_HIERARCHY_V2_RELATIONSHIP_INVALID", () -> new VisualRelationshipPlan(
                            relationship.relationshipId(), relationship.parentEntityId(),
                            relationship.childEntityId(), relationship.fieldKey(), relationship.displayName(),
                            relationship.cardinality(), relationship.supportingElementIds()
                    )
            )).toList();
            var hierarchy = classifiedHierarchyShape(() -> new VisualHierarchyPlan(
                    VisualHierarchyPlan.VERSION_V2, response.rootEntityId(), entities, relationships
            ));
            classifiedHierarchySupport(() -> hierarchy.requireConsistentWith(inventory));
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
        var mismatchedInput = findCause(failure, MismatchedInputException.class);
        if (mismatchedInput != null) {
            if (messageStartsWithForType(
                    failure, "MismatchedInputException", "trailing token (`jsontoken."
            )) {
                return prefix + "_JSON_TRAILING_CONTENT";
            }
            var slot = shapeInvalidSlot(mismatchedInput);
            return prefix + "_JSON_SHAPE_INVALID" + (slot == null ? "" : "_" + slot);
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

    private static String shapeInvalidSlot(MismatchedInputException failure) {
        for (var index = failure.getPath().size() - 1; index >= 0; index--) {
            var reference = failure.getPath().get(index);
            var slot = shapeSlot(reference.from(), reference.getPropertyName());
            if (slot != null) return slot;
        }
        return null;
    }

    private static String shapeSlot(Object owner, String propertyName) {
        if (ownerIs(owner, GroundingOutput.class)) {
            if ("contractVersion".equals(propertyName)) return "ROOT_CONTRACT_VERSION";
            if ("regions".equals(propertyName)) return "ROOT_REGIONS";
            if ("elements".equals(propertyName)) return "ROOT_ELEMENTS";
        }
        if (ownerIs(owner, RegionOutput.class)) {
            if ("regionId".equals(propertyName)) return "REGION_ID";
            if ("parentRegionId".equals(propertyName)) return "REGION_PARENT_ID";
            if ("kind".equals(propertyName)) return "REGION_KIND";
            if ("multiplicity".equals(propertyName)) return "REGION_MULTIPLICITY";
            if ("readingOrder".equals(propertyName)) return "REGION_READING_ORDER";
            if ("repeatGroupId".equals(propertyName)) return "REGION_REPEAT_GROUP_ID";
            if ("evidence".equals(propertyName)) return "REGION_EVIDENCE";
        }
        if (ownerIs(owner, ElementOutput.class)) {
            if ("elementId".equals(propertyName)) return "ELEMENT_ID";
            if ("kind".equals(propertyName)) return "ELEMENT_KIND";
            if ("proposedKey".equals(propertyName)) return "ELEMENT_PROPOSED_KEY";
            if ("displayName".equals(propertyName)) return "ELEMENT_DISPLAY_NAME";
            if ("multiplicity".equals(propertyName)) return "ELEMENT_MULTIPLICITY";
            if ("valueHint".equals(propertyName)) return "ELEMENT_VALUE_HINT";
            if ("regionIds".equals(propertyName)) return "ELEMENT_REGION_IDS";
            if ("evidence".equals(propertyName)) return "ELEMENT_EVIDENCE";
        }
        if (ownerIs(owner, HierarchyOutput.class)) {
            if ("contractVersion".equals(propertyName)) return "ROOT_CONTRACT_VERSION";
            if ("rootEntityId".equals(propertyName)) return "ROOT_ENTITY_ID";
            if ("entities".equals(propertyName)) return "ROOT_ENTITIES";
            if ("relationships".equals(propertyName)) return "ROOT_RELATIONSHIPS";
        }
        if (ownerIs(owner, EntityOutput.class)) {
            if ("entityId".equals(propertyName)) return "ENTITY_ID";
            if ("schemaKey".equals(propertyName)) return "ENTITY_SCHEMA_KEY";
            if ("displayName".equals(propertyName)) return "ENTITY_DISPLAY_NAME";
            if ("regionIds".equals(propertyName)) return "ENTITY_REGION_IDS";
            if ("supportingElementIds".equals(propertyName)) return "ENTITY_ELEMENT_IDS";
        }
        if (ownerIs(owner, RelationshipOutput.class)) {
            if ("relationshipId".equals(propertyName)) return "RELATIONSHIP_ID";
            if ("parentEntityId".equals(propertyName)) return "RELATIONSHIP_PARENT_ID";
            if ("childEntityId".equals(propertyName)) return "RELATIONSHIP_CHILD_ID";
            if ("fieldKey".equals(propertyName)) return "RELATIONSHIP_FIELD_KEY";
            if ("displayName".equals(propertyName)) return "RELATIONSHIP_DISPLAY_NAME";
            if ("cardinality".equals(propertyName)) return "RELATIONSHIP_CARDINALITY";
            if ("regionId".equals(propertyName)) return "RELATIONSHIP_REGION_ID";
            if ("supportingElementIds".equals(propertyName)) return "RELATIONSHIP_ELEMENT_IDS";
        }
        if (ownerIs(owner, BindingOutput.class)) {
            if ("contractVersion".equals(propertyName)) return "ROOT_CONTRACT_VERSION";
            if ("bindings".equals(propertyName)) return "ROOT_BINDINGS";
        }
        if (ownerIs(owner, BindingItem.class)) {
            if ("elementId".equals(propertyName)) return "BINDING_ELEMENT_ID";
            if ("entityId".equals(propertyName)) return "BINDING_ENTITY_ID";
        }
        if (ownerIs(owner, VisualViewEvidence.class)) {
            if ("viewId".equals(propertyName)) return "EVIDENCE_VIEW_ID";
            if ("boundingBox".equals(propertyName)) return "EVIDENCE_BOUNDING_BOX";
        }
        if (ownerIs(owner, CandidateBoundingBox.class)
                && ("left".equals(propertyName) || "top".equals(propertyName)
                || "right".equals(propertyName) || "bottom".equals(propertyName))) {
            return "BOUNDING_BOX_COORDINATE";
        }
        return null;
    }

    private static boolean ownerIs(Object owner, Class<?> expectedType) {
        return owner == expectedType || expectedType.isInstance(owner);
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

    private static <T> T classifiedHierarchyShape(CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(hierarchyShapeCode(failure), failure);
        }
    }

    private static void classifiedHierarchySupport(CheckedRunnable runnable) {
        try {
            runnable.run();
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(hierarchySupportCode(failure), failure);
        }
    }

    private static String hierarchyShapeCode(Throwable failure) {
        return switch (controlledMessage(failure)) {
            case "Visual entity ids must be unique" ->
                    "VISUAL_HIERARCHY_V2_ENTITY_ID_DUPLICATE";
            case "Visual entity schema keys must be unique" ->
                    "VISUAL_HIERARCHY_V2_SCHEMA_KEY_DUPLICATE";
            case "Visual hierarchy root is missing" ->
                    "VISUAL_HIERARCHY_V2_ROOT_MISSING";
            case "Visual relationship ids must be unique" ->
                    "VISUAL_HIERARCHY_V2_RELATIONSHIP_ID_DUPLICATE";
            case "Visual relationship endpoints are invalid" ->
                    "VISUAL_HIERARCHY_V2_RELATIONSHIP_ENDPOINT_INVALID";
            case "Relationship field keys must be unique per parent" ->
                    "VISUAL_HIERARCHY_V2_PARENT_FIELD_DUPLICATE";
            case "Visual hierarchy root cannot have a parent" ->
                    "VISUAL_HIERARCHY_V2_ROOT_HAS_PARENT";
            case "Every non-root visual entity must have exactly one parent" ->
                    "VISUAL_HIERARCHY_V2_PARENT_COUNT_INVALID";
            case "Visual hierarchy exceeds depth 16" ->
                    "VISUAL_HIERARCHY_V2_DEPTH_INVALID";
            case "Visual hierarchy contains a cycle" ->
                    "VISUAL_HIERARCHY_V2_CYCLE_INVALID";
            case "Visual hierarchy contains an orphan" ->
                    "VISUAL_HIERARCHY_V2_ORPHAN_INVALID";
            default -> "VISUAL_HIERARCHY_V2_TOPOLOGY_INVALID";
        };
    }

    private static String hierarchySupportCode(Throwable failure) {
        return switch (controlledMessage(failure)) {
            case "Visual plan references an unknown element" ->
                    "VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN";
            case "Relationships must be supported by GROUP elements" ->
                    "VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP";
            case "A GROUP element may support only one relationship" ->
                    "VISUAL_HIERARCHY_V2_SUPPORT_GROUP_REUSED";
            case "Relationship cardinality must match a supporting GROUP element" ->
                    "VISUAL_HIERARCHY_V2_SUPPORT_CARDINALITY_MISMATCH";
            default -> "VISUAL_HIERARCHY_V2_SUPPORT_INVALID";
        };
    }

    private static String controlledMessage(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null) {
                return cause.getMessage();
            }
        }
        return "";
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
