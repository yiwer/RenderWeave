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
import java.util.LinkedHashSet;
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
            var grounding = classifiedGroundingShape(() ->
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
            classifiedGroundingOwnership(() -> grounding.requireConsistentWith(inventory));
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
        return parseHierarchy(
                value, inventory, grounding, VisualRelationshipCardinalityPolicy.MODEL_ASSERTED
        );
    }

    GroundedHierarchyPlan parseHierarchy(
            String value,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualRelationshipCardinalityPolicy cardinalityPolicy
    ) {
        return parseHierarchy(
                value, inventory, grounding, cardinalityPolicy,
                VisualHierarchyPrerequisitePolicy.GROUP_EXISTENCE_ONLY,
                VisualHierarchyRegionDiagnosticPolicy.LEGACY_GENERIC
        );
    }

    GroundedHierarchyPlan parseHierarchy(
            String value,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualRelationshipCardinalityPolicy cardinalityPolicy,
            VisualHierarchyPrerequisitePolicy prerequisitePolicy
    ) {
        return parseHierarchy(
                value, inventory, grounding, cardinalityPolicy, prerequisitePolicy,
                VisualHierarchyRegionDiagnosticPolicy.LEGACY_GENERIC
        );
    }

    GroundedHierarchyPlan parseHierarchy(
            String value,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualRelationshipCardinalityPolicy cardinalityPolicy,
            VisualHierarchyPrerequisitePolicy prerequisitePolicy,
            VisualHierarchyRegionDiagnosticPolicy regionDiagnosticPolicy
    ) {
        return parseHierarchy(
                value, inventory, grounding, cardinalityPolicy, prerequisitePolicy,
                regionDiagnosticPolicy, VisualRelationshipSupportIdPolicy.STRICT,
                VisualRelationshipRegionPolicy.STRICT
        );
    }

    GroundedHierarchyPlan parseHierarchy(
            String value,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualRelationshipCardinalityPolicy cardinalityPolicy,
            VisualHierarchyPrerequisitePolicy prerequisitePolicy,
            VisualHierarchyRegionDiagnosticPolicy regionDiagnosticPolicy,
            VisualRelationshipSupportIdPolicy supportIdPolicy
    ) {
        return parseHierarchy(
                value, inventory, grounding, cardinalityPolicy, prerequisitePolicy,
                regionDiagnosticPolicy, supportIdPolicy, VisualRelationshipRegionPolicy.STRICT
        );
    }

    GroundedHierarchyPlan parseHierarchy(
            String value,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualRelationshipCardinalityPolicy cardinalityPolicy,
            VisualHierarchyPrerequisitePolicy prerequisitePolicy,
            VisualHierarchyRegionDiagnosticPolicy regionDiagnosticPolicy,
            VisualRelationshipSupportIdPolicy supportIdPolicy,
            VisualRelationshipRegionPolicy relationshipRegionPolicy
    ) {
        try {
            Objects.requireNonNull(cardinalityPolicy, "cardinalityPolicy");
            Objects.requireNonNull(prerequisitePolicy, "prerequisitePolicy");
            Objects.requireNonNull(regionDiagnosticPolicy, "regionDiagnosticPolicy");
            Objects.requireNonNull(supportIdPolicy, "supportIdPolicy");
            Objects.requireNonNull(relationshipRegionPolicy, "relationshipRegionPolicy");
            var response = decode(value, HierarchyOutput.class, "VISUAL_HIERARCHY_V2");
            if (!VisualHierarchyPlan.VERSION_V2.equals(response.contractVersion())) {
                throw invalid("VISUAL_HIERARCHY_V2_VERSION_INVALID", null);
            }
            var entities = response.entities().stream()
                    .map(VisualGroundingJsonCodec::classifiedEntity)
                    .toList();
            var classifiedRelationships = response.relationships().stream()
                    .map(relationship -> classifiedRelationship(
                            relationship, inventory, grounding, cardinalityPolicy, supportIdPolicy
                    ))
                    .toList();
            var relationships = classifiedRelationships.stream()
                    .map(ClassifiedRelationship::plan)
                    .toList();
            var hierarchy = classifiedHierarchyShape(() -> new VisualHierarchyPlan(
                    VisualHierarchyPlan.VERSION_V2, response.rootEntityId(), entities, relationships
            ));
            var prerequisiteIssues = SEMANTIC_VERIFIER.verifyHierarchyPrerequisites(
                    inventory, hierarchy
            );
            if (!prerequisiteIssues.isEmpty()) {
                throw invalid(prerequisiteIssues.getFirst(), null);
            }
            final ClassifiedEntityRegions classifiedEntityRegions;
            if (prerequisitePolicy
                    == VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED) {
                classifiedEntityRegions = normalizeRelationshipRegions(
                        classifiedEntityRegions(response, regionDiagnosticPolicy), hierarchy,
                        inventory, grounding, relationshipRegionPolicy
                );
                classifiedEntityRegionConsistency(
                        classifiedEntityRegions.plan(), hierarchy, grounding, regionDiagnosticPolicy
                );
                var regionOwnerIssues = SEMANTIC_VERIFIER.verifyRelationshipRegionGroupOwners(
                        inventory, grounding, hierarchy, classifiedEntityRegions.plan()
                );
                if (!regionOwnerIssues.isEmpty()) {
                    throw invalid(regionOwnerIssues.getFirst(), null);
                }
                classifiedHierarchySupport(() -> hierarchy.requireConsistentWith(inventory));
            } else {
                // Preserve historical diagnostic precedence for immutable pre-4.4 profiles.
                classifiedHierarchySupport(() -> hierarchy.requireConsistentWith(inventory));
                classifiedEntityRegions = normalizeRelationshipRegions(
                        classifiedEntityRegions(response, regionDiagnosticPolicy), hierarchy,
                        inventory, grounding, relationshipRegionPolicy
                );
                classifiedEntityRegionConsistency(
                        classifiedEntityRegions.plan(), hierarchy, grounding, regionDiagnosticPolicy
                );
            }
            var entityRegions = classifiedEntityRegions.plan();
            var semanticIssues = SEMANTIC_VERIFIER.verifyHierarchy(
                    inventory, grounding, hierarchy, entityRegions
            );
            if (!semanticIssues.isEmpty()) {
                throw invalid(semanticIssues.getFirst(), null);
            }
            return new GroundedHierarchyPlan(
                    hierarchy, entityRegions,
                    cardinalityPolicy == VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED
                            ? relationships.size() : 0,
                    classifiedRelationships.stream()
                            .mapToInt(ClassifiedRelationship::normalizedSupportIdReferences)
                            .sum(),
                    classifiedRelationships.stream()
                            .mapToInt(ClassifiedRelationship::normalizedSupportOwners)
                            .sum(),
                    classifiedEntityRegions.normalizedRelationshipRegions()
            );
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("VISUAL_HIERARCHY_V2_CONTRACT_INVALID", failure);
        }
    }

    private static VisualEntityRegionPlan classifiedEntityRegions(
            HierarchyOutput response,
            VisualHierarchyRegionDiagnosticPolicy diagnosticPolicy
    ) {
        if (diagnosticPolicy == VisualHierarchyRegionDiagnosticPolicy.LEGACY_GENERIC) {
            return classified("VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID", () ->
                    entityRegionPlan(response, false)
            );
        }
        return classified("VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID", () ->
                entityRegionPlan(response, true)
        );
    }

    private static VisualEntityRegionPlan entityRegionPlan(
            HierarchyOutput response,
            boolean detailed
    ) {
        return new VisualEntityRegionPlan(
                VisualEntityRegionPlan.VERSION,
                response.entities().stream()
                        .map(entity -> entityRegionOwnership(entity, detailed)).toList(),
                response.relationships().stream()
                        .map(relationship -> relationshipRegionOwnership(relationship, detailed)).toList()
        );
    }

    private static ClassifiedEntityRegions normalizeRelationshipRegions(
            VisualEntityRegionPlan entityRegions,
            VisualHierarchyPlan hierarchy,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualRelationshipRegionPolicy policy
    ) {
        if (policy == VisualRelationshipRegionPolicy.STRICT) {
            return new ClassifiedEntityRegions(entityRegions, 0);
        }
        var normalized = new ArrayList<VisualRelationshipRegionOwnership>();
        var normalizedCount = 0;
        var requireConnection = policy
                == VisualRelationshipRegionPolicy
                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION;
        for (var ownership : entityRegions.relationships()) {
            var relationship = hierarchy.relationships().stream()
                    .filter(item -> item.relationshipId().equals(ownership.relationshipId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Unknown relationship region ownership"
                    ));
            final VisualRegion currentRegion;
            try {
                currentRegion = grounding.requireRegion(ownership.regionId());
            } catch (IllegalArgumentException unknownRegion) {
                normalized.add(ownership);
                continue;
            }
            var currentCardinalityCompatible = relationshipRegionCardinalityCompatible(
                    relationship.cardinality(), currentRegion
            );
            var currentConnectionCompatible = !requireConnection
                    || relationshipRegionConnectionCompatible(
                    relationship, currentRegion, entityRegions, grounding
            );
            if (currentCardinalityCompatible && currentConnectionCompatible) {
                normalized.add(ownership);
                continue;
            }
            var supportingGroup = inventory.requireElement(
                    relationship.supportingElementIds().getFirst()
            );
            var cardinalityCompatibleRegions = grounding
                    .regionIdsForElement(supportingGroup.elementId()).stream()
                    .map(grounding::requireRegion)
                    .filter(region -> relationshipRegionCardinalityCompatible(
                            relationship.cardinality(), region
                    )).distinct().toList();
            if (cardinalityCompatibleRegions.isEmpty()) {
                throw invalid(VisualSemanticIssue.OBSERVE_GROUP_REGION_INVALID, null);
            }
            var compatibleRegions = cardinalityCompatibleRegions.stream()
                    .filter(region -> !requireConnection
                            || relationshipRegionConnectionCompatible(
                            relationship, region, entityRegions, grounding
                    ))
                    .map(VisualRegion::regionId).toList();
            if (compatibleRegions.size() == 1) {
                normalized.add(new VisualRelationshipRegionOwnership(
                        ownership.relationshipId(), compatibleRegions.getFirst()
                ));
                normalizedCount++;
            } else {
                normalized.add(ownership);
            }
        }
        return new ClassifiedEntityRegions(new VisualEntityRegionPlan(
                VisualEntityRegionPlan.VERSION, entityRegions.entities(), normalized
        ), normalizedCount);
    }

    private static boolean relationshipRegionCardinalityCompatible(
            VisualMultiplicity cardinality,
            VisualRegion region
    ) {
        return cardinality == VisualMultiplicity.MANY
                ? region.kind() == VisualRegionKind.REPEATED_GROUP
                : region.multiplicity() == VisualMultiplicity.ONE;
    }

    private static boolean relationshipRegionConnectionCompatible(
            VisualRelationshipPlan relationship,
            VisualRegion region,
            VisualEntityRegionPlan entityRegions,
            VisualGroundingPlan grounding
    ) {
        var parent = entityRegions.entities().stream()
                .filter(item -> item.entityId().equals(relationship.parentEntityId()))
                .findFirst();
        var child = entityRegions.entities().stream()
                .filter(item -> item.entityId().equals(relationship.childEntityId()))
                .findFirst();
        if (parent.isEmpty() || child.isEmpty()) return false;
        try {
            return parent.orElseThrow().regionIds().stream().anyMatch(parentRegion ->
                    grounding.descendantOrSame(region.regionId(), parentRegion))
                    && child.orElseThrow().regionIds().stream().anyMatch(childRegion ->
                    grounding.descendantOrSame(childRegion, region.regionId()));
        } catch (IllegalArgumentException invalidOwnership) {
            return false;
        }
    }

    private record ClassifiedEntityRegions(
            VisualEntityRegionPlan plan,
            int normalizedRelationshipRegions
    ) { }

    private static VisualEntityRegionOwnership entityRegionOwnership(
            EntityOutput entity,
            boolean detailed
    ) {
        if (!detailed) return new VisualEntityRegionOwnership(entity.entityId(), entity.regionIds());
        return classified("VISUAL_HIERARCHY_V2_ENTITY_REGION_IDS_INVALID", () ->
                new VisualEntityRegionOwnership(entity.entityId(), entity.regionIds()));
    }

    private static VisualRelationshipRegionOwnership relationshipRegionOwnership(
            RelationshipOutput relationship,
            boolean detailed
    ) {
        if (!detailed) {
            return new VisualRelationshipRegionOwnership(
                    relationship.relationshipId(), relationship.regionId()
            );
        }
        return classified("VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_ID_INVALID", () ->
                new VisualRelationshipRegionOwnership(
                        relationship.relationshipId(), relationship.regionId()
                ));
    }

    private static void classifiedEntityRegionConsistency(
            VisualEntityRegionPlan entityRegions,
            VisualHierarchyPlan hierarchy,
            VisualGroundingPlan grounding,
            VisualHierarchyRegionDiagnosticPolicy diagnosticPolicy
    ) {
        if (diagnosticPolicy == VisualHierarchyRegionDiagnosticPolicy.LEGACY_GENERIC) {
            classified("VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID", () ->
                    entityRegions.requireConsistentWith(hierarchy, grounding));
            return;
        }
        classifiedHierarchyRegionConsistency(() ->
                entityRegions.requireConsistentWith(hierarchy, grounding));
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
            var semanticIssues = SEMANTIC_VERIFIER.verifyBindings(
                    inventory, grounding, hierarchy, entityRegions, result
            );
            if (!semanticIssues.isEmpty()) {
                throw invalid(semanticIssues.getFirst(), null);
            }
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
            if (target != null && target.isEnum()) {
                var slot = shapeInvalidSlot(invalidFormat);
                return prefix + "_JSON_ENUM_INVALID" + (slot == null ? "" : "_" + slot);
            }
            return prefix + "_JSON_FORMAT_INVALID";
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

    private static VisualEntityPlan classifiedEntity(EntityOutput entity) {
        var entityId = classified("VISUAL_HIERARCHY_V2_ENTITY_ID_INVALID", () ->
                VisualAnalysisValidation.localId(entity.entityId(), "entityId")
        );
        var schemaKey = classified("VISUAL_HIERARCHY_V2_ENTITY_SCHEMA_KEY_INVALID", () ->
                VisualAnalysisValidation.schemaKey(entity.schemaKey())
        );
        var displayName = classified("VISUAL_HIERARCHY_V2_ENTITY_DISPLAY_NAME_INVALID", () ->
                VisualAnalysisValidation.displayName(entity.displayName(), "displayName")
        );
        var supportingElementIds = classified("VISUAL_HIERARCHY_V2_ENTITY_SUPPORT_IDS_INVALID", () ->
                VisualAnalysisValidation.localIds(
                        entity.supportingElementIds(), "supportingElementIds", 32
                )
        );
        return classified("VISUAL_HIERARCHY_V2_ENTITY_INVALID", () -> new VisualEntityPlan(
                entityId, schemaKey, displayName, supportingElementIds
        ));
    }

    private static ClassifiedRelationship classifiedRelationship(
            RelationshipOutput relationship,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualRelationshipCardinalityPolicy cardinalityPolicy,
            VisualRelationshipSupportIdPolicy supportIdPolicy
    ) {
        var relationshipId = classified("VISUAL_HIERARCHY_V2_RELATIONSHIP_ID_INVALID", () ->
                VisualAnalysisValidation.localId(relationship.relationshipId(), "relationshipId")
        );
        var parentEntityId = classified("VISUAL_HIERARCHY_V2_RELATIONSHIP_PARENT_ID_INVALID", () ->
                VisualAnalysisValidation.localId(relationship.parentEntityId(), "parentEntityId")
        );
        var childEntityId = classified("VISUAL_HIERARCHY_V2_RELATIONSHIP_CHILD_ID_INVALID", () ->
                VisualAnalysisValidation.localId(relationship.childEntityId(), "childEntityId")
        );
        var fieldKey = classified("VISUAL_HIERARCHY_V2_RELATIONSHIP_FIELD_KEY_INVALID", () ->
                VisualAnalysisValidation.fieldKey(relationship.fieldKey())
        );
        var displayName = classified("VISUAL_HIERARCHY_V2_RELATIONSHIP_DISPLAY_NAME_INVALID", () ->
                VisualAnalysisValidation.displayName(relationship.displayName(), "displayName")
        );
        var supportIds = normalizeRelationshipSupportOwner(
                relationship, inventory, grounding, supportIdPolicy,
                classifiedRelationshipSupportIds(
                        relationship.supportingElementIds(), supportIdPolicy
                )
        );
        var cardinality = cardinalityPolicy == VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED
                ? derivedCardinality(inventory, supportIds.ids())
                : classified("VISUAL_HIERARCHY_V2_RELATIONSHIP_CARDINALITY_INVALID", () ->
                        Objects.requireNonNull(relationship.cardinality(), "cardinality")
                );
        var plan = classified("VISUAL_HIERARCHY_V2_RELATIONSHIP_INVALID", () ->
                new VisualRelationshipPlan(
                        relationshipId, parentEntityId, childEntityId, fieldKey, displayName,
                        cardinality, supportIds.ids()
                ));
        return new ClassifiedRelationship(
                plan, supportIds.normalizedReferences(), supportIds.normalizedOwners()
        );
    }

    private static ClassifiedSupportIds classifiedRelationshipSupportIds(
            List<String> values,
            VisualRelationshipSupportIdPolicy policy
    ) {
        if (policy == VisualRelationshipSupportIdPolicy.STRICT) {
            return new ClassifiedSupportIds(classified(
                    "VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_INVALID",
                    () -> VisualAnalysisValidation.localIds(values, "supportingElementIds", 16)
            ), 0, 0);
        }
        if (values == null) {
            throw invalid("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_MISSING", null);
        }
        if (values.isEmpty()) {
            throw invalid("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY", null);
        }
        if (values.size() > 16) {
            throw invalid("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_LIMIT_EXCEEDED", null);
        }
        var unique = new LinkedHashSet<String>();
        for (var value : values) {
            var validated = classified("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_ID_INVALID", () ->
                    VisualAnalysisValidation.localId(value, "supportingElementIds")
            );
            unique.add(validated);
        }
        return new ClassifiedSupportIds(
                List.copyOf(unique), values.size() - unique.size(), 0
        );
    }

    private static ClassifiedSupportIds normalizeRelationshipSupportOwner(
            RelationshipOutput relationship,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualRelationshipSupportIdPolicy policy,
            ClassifiedSupportIds supportIds
    ) {
        if (policy != VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_REGION_GROUP_OWNER
                || supportIds.ids().size() != 1) {
            return supportIds;
        }
        try {
            var supporting = inventory.requireElement(supportIds.ids().getFirst());
            if (supporting.kind() == VisualElementKind.GROUP) return supportIds;
            var regionId = VisualAnalysisValidation.localId(relationship.regionId(), "regionId");
            var region = grounding.requireRegion(regionId);
            if (region.kind() != VisualRegionKind.GROUP
                    && region.kind() != VisualRegionKind.REPEATED_GROUP) {
                return supportIds;
            }
            var owners = inventory.elements().stream()
                    .filter(element -> element.kind() == VisualElementKind.GROUP)
                    .filter(element -> grounding.regionIdsForElement(element.elementId())
                            .contains(regionId))
                    .toList();
            if (owners.size() != 1) return supportIds;
            return new ClassifiedSupportIds(
                    List.of(owners.getFirst().elementId()),
                    supportIds.normalizedReferences(), 1
            );
        } catch (IllegalArgumentException failure) {
            // Keep existing fixed-code diagnostics for unknown supports or invalid regions.
            return supportIds;
        }
    }

    private record ClassifiedSupportIds(
            List<String> ids,
            int normalizedReferences,
            int normalizedOwners
    ) { }

    private record ClassifiedRelationship(
            VisualRelationshipPlan plan,
            int normalizedSupportIdReferences,
            int normalizedSupportOwners
    ) { }

    private static VisualMultiplicity derivedCardinality(
            VisualElementInventory inventory,
            List<String> supportingElementIds
    ) {
        if (supportingElementIds.size() != 1) {
            throw invalid("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_COUNT_INVALID", null);
        }
        final VisualElement supporting;
        try {
            supporting = inventory.requireElement(supportingElementIds.getFirst());
        } catch (IllegalArgumentException failure) {
            throw invalid("VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN", failure);
        }
        if (supporting.kind() != VisualElementKind.GROUP) {
            throw invalid("VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP", null);
        }
        return supporting.multiplicity();
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

    private static void classifiedHierarchyRegionConsistency(CheckedRunnable runnable) {
        try {
            runnable.run();
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(hierarchyRegionConsistencyCode(failure), failure);
        }
    }

    private static String hierarchyRegionConsistencyCode(Throwable failure) {
        return switch (controlledMessage(failure)) {
            case "Entity-region ownership must cover the complete hierarchy" ->
                    "VISUAL_HIERARCHY_V2_REGION_COVERAGE_INVALID";
            case "Visual plan references an unknown region" ->
                    "VISUAL_HIERARCHY_V2_REGION_REFERENCE_UNKNOWN";
            case "Root entity must own every artifact root region" ->
                    "VISUAL_HIERARCHY_V2_ROOT_REGION_OWNERSHIP_INVALID";
            case "Relationship region must connect parent and child ownership" ->
                    "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CONNECTION_INVALID";
            case "Relationship cardinality conflicts with its owned region" ->
                    "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID";
            default -> "VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID";
        };
    }

    private static <T> T classifiedGroundingShape(CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(groundingShapeCode(failure), failure);
        }
    }

    private static void classifiedGroundingOwnership(CheckedRunnable runnable) {
        try {
            runnable.run();
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(groundingOwnershipCode(failure), failure);
        }
    }

    private static String groundingOwnershipCode(Throwable failure) {
        return switch (controlledMessage(failure)) {
            case "Every visual element requires region ownership" ->
                    "VISUAL_GROUNDING_ELEMENT_REGION_COVERAGE_INVALID";
            case "Visual plan references an unknown region" ->
                    "VISUAL_GROUNDING_ELEMENT_REGION_UNKNOWN";
            case "Element evidence must be contained by an owned region" ->
                    "VISUAL_GROUNDING_ELEMENT_EVIDENCE_OUTSIDE_REGION";
            default -> "VISUAL_GROUNDING_ELEMENT_OWNERSHIP_INVALID";
        };
    }

    private static String groundingShapeCode(Throwable failure) {
        return switch (controlledMessage(failure)) {
            case "Visual grounding must contain 1..128 regions" ->
                    "VISUAL_GROUNDING_REGION_COUNT_INVALID";
            case "Visual region ids must be unique" ->
                    "VISUAL_GROUNDING_REGION_ID_DUPLICATE";
            case "Visual grounding requires 1..10 roots" ->
                    "VISUAL_GROUNDING_ROOT_COUNT_INVALID";
            case "Visual roots must cover their complete source artifact" ->
                    "VISUAL_GROUNDING_ROOT_COVERAGE_INVALID";
            case "Visual region parent is invalid" ->
                    "VISUAL_GROUNDING_PARENT_INVALID";
            case "Visual region kind is invalid for its parent" ->
                    "VISUAL_GROUNDING_PARENT_KIND_INVALID";
            case "Visual child regions must be contained by their parent" ->
                    "VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID";
            case "Visual region graph is cyclic or too deep" ->
                    "VISUAL_GROUNDING_CYCLE_OR_DEPTH_INVALID";
            case "Visual region graph contains an orphan" ->
                    "VISUAL_GROUNDING_ORPHAN_INVALID";
            case "Visual sibling readingOrder must be contiguous from zero" ->
                    "VISUAL_GROUNDING_READING_ORDER_GAP";
            case "Visual readingOrder must follow canonical top-left order" ->
                    "VISUAL_GROUNDING_READING_ORDER_POSITION_INVALID";
            case "Repeated regions require matching item children" ->
                    "VISUAL_GROUNDING_REPEAT_CHILD_INVALID";
            case "Repeated items require one repeat group identity" ->
                    "VISUAL_GROUNDING_REPEAT_ITEM_INVALID";
            case "Non-repeated visual regions must be singular" ->
                    "VISUAL_GROUNDING_NON_REPEATED_CARDINALITY_INVALID";
            default -> controlledMessage(failure).startsWith(
                    "Visual sibling regions must not overlap:"
            ) ? "VISUAL_GROUNDING_SIBLING_OVERLAP"
                    : "VISUAL_GROUNDING_REGION_FOREST_INVALID";
        };
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

    private static InvalidVisualAnalysisException invalid(
            VisualSemanticIssue issue,
            Throwable cause
    ) {
        return new InvalidVisualAnalysisException(
                issue.code(), "Visual grounding output is invalid", cause, issue.earliestStage()
        );
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
        VisualEntityRegionPlan entityRegions,
        int derivedRelationshipCardinalities,
        int normalizedRelationshipSupportIdReferences,
        int normalizedRelationshipSupportOwners,
        int normalizedRelationshipRegions
) {
    GroundedHierarchyPlan {
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(entityRegions, "entityRegions");
        if (derivedRelationshipCardinalities < 0
                || derivedRelationshipCardinalities > hierarchy.relationships().size()) {
            throw new IllegalArgumentException("Derived relationship cardinality count is invalid");
        }
        if (normalizedRelationshipSupportIdReferences < 0
                || normalizedRelationshipSupportIdReferences
                > hierarchy.relationships().size() * 15) {
            throw new IllegalArgumentException("Normalized relationship support id count is invalid");
        }
        if (normalizedRelationshipSupportOwners < 0
                || normalizedRelationshipSupportOwners > hierarchy.relationships().size()) {
            throw new IllegalArgumentException("Normalized relationship support owner count is invalid");
        }
        if (normalizedRelationshipRegions < 0
                || normalizedRelationshipRegions > hierarchy.relationships().size()) {
            throw new IllegalArgumentException("Normalized relationship region count is invalid");
        }
    }
}

enum VisualRelationshipCardinalityPolicy {
    MODEL_ASSERTED,
    SUPPORT_GROUP_DERIVED
}

enum VisualHierarchyPrerequisitePolicy {
    GROUP_EXISTENCE_ONLY,
    RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED
}

enum VisualHierarchyRegionDiagnosticPolicy {
    LEGACY_GENERIC,
    DETAILED_FIXED_CODES
}

enum VisualRelationshipSupportIdPolicy {
    STRICT,
    CANONICALIZE_EXACT_DUPLICATES,
    CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_REGION_GROUP_OWNER
}

enum VisualRelationshipRegionPolicy {
    STRICT,
    UNIQUE_CARDINALITY_COMPATIBLE_GROUP_REGION,
    UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
}
