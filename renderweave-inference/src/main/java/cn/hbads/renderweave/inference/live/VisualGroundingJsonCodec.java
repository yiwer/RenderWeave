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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Strict decoders for region-grounded visual contracts; view evidence is canonicalized before persistence. */
final class VisualGroundingJsonCodec {
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_NORMALIZED_REGION_PARENTS = 8;
    private static final int MAX_NORMALIZED_READING_ORDERS = 8;
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
        return parseElements(
                value, views, sourceArtifactIds, VisualObservationNormalizationPolicy.STRICT,
                VisualObservationSemanticPolicy.LEGACY
        );
    }

    GroundedElementInventory parseElements(
            String value,
            VisualViewPlan views,
            List<String> sourceArtifactIds,
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        return parseElements(
                value, views, sourceArtifactIds, normalizationPolicy,
                VisualObservationSemanticPolicy.LEGACY
        );
    }

    GroundedElementInventory parseElements(
            String value,
            VisualViewPlan views,
            List<String> sourceArtifactIds,
            VisualObservationNormalizationPolicy normalizationPolicy,
            VisualObservationSemanticPolicy semanticPolicy
    ) {
        try {
            Objects.requireNonNull(normalizationPolicy, "normalizationPolicy");
            Objects.requireNonNull(semanticPolicy, "semanticPolicy");
            var response = decode(value, GroundingOutput.class, "VISUAL_GROUNDING");
            if (!VisualGroundingPlan.VERSION.equals(response.contractVersion())) {
                throw invalid("VISUAL_GROUNDING_VERSION_INVALID", null);
            }
            var classifiedRegions = classified("VISUAL_GROUNDING_REGION_INVALID", () ->
                    observationRegions(
                            response.regions(), response.elements(), views, normalizationPolicy
                    )
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
            var initialGrounding = classifiedGroundingShape(
                    classifiedRegions.regions(), normalizationPolicy, () ->
                    new VisualGroundingPlan(
                    VisualGroundingPlan.VERSION, classifiedRegions.regions(),
                    response.elements().stream().map(element -> new VisualElementRegionOwnership(
                            element.elementId(), element.regionIds()
                    )).toList()
                    )
            );
            var normalizedOwnerships = normalizeElementEvidenceOwners(
                    inventory, initialGrounding, normalizationPolicy
            );
            var normalizedItemSlotOwnerships = normalizeRepeatedItemSlotOwners(
                    inventory, normalizedOwnerships.grounding(), normalizationPolicy
            );
            var grounding = normalizedItemSlotOwnerships.grounding();
            classified("VISUAL_GROUNDING_ARTIFACT_COVERAGE_INVALID", () -> {
                inventory.requireKnownArtifacts(Set.copyOf(sourceArtifactIds));
                grounding.requireKnownArtifacts(sourceArtifactIds);
            });
            classifiedGroundingOwnership(() -> grounding.requireConsistentWith(inventory));
            var semanticIssues = SEMANTIC_VERIFIER.verifyObservation(
                    inventory, grounding, semanticPolicy
            );
            if (!semanticIssues.isEmpty()) {
                throw invalid(semanticIssues.getFirst().code(), null);
            }
            return new GroundedElementInventory(
                    inventory, grounding, classifiedRegions.normalizedRegionKinds(),
                    classifiedRegions.normalizedItemParents(),
                    classifiedRegions.normalizedRegionParents(),
                    classifiedRegions.normalizedReadingOrders(),
                    normalizedOwnerships.normalizedElements(),
                    normalizedItemSlotOwnerships.normalizedElements()
            );
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
        return parseHierarchy(
                value, inventory, grounding, cardinalityPolicy, prerequisitePolicy,
                regionDiagnosticPolicy, supportIdPolicy, relationshipRegionPolicy,
                VisualHierarchySemanticPolicy.LEGACY
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
            VisualRelationshipRegionPolicy relationshipRegionPolicy,
            VisualHierarchySemanticPolicy semanticPolicy
    ) {
        try {
            Objects.requireNonNull(cardinalityPolicy, "cardinalityPolicy");
            Objects.requireNonNull(prerequisitePolicy, "prerequisitePolicy");
            Objects.requireNonNull(regionDiagnosticPolicy, "regionDiagnosticPolicy");
            Objects.requireNonNull(supportIdPolicy, "supportIdPolicy");
            Objects.requireNonNull(relationshipRegionPolicy, "relationshipRegionPolicy");
            Objects.requireNonNull(semanticPolicy, "semanticPolicy");
            var response = decode(value, HierarchyOutput.class, "VISUAL_HIERARCHY_V2");
            if (!VisualHierarchyPlan.VERSION_V2.equals(response.contractVersion())) {
                throw invalid("VISUAL_HIERARCHY_V2_VERSION_INVALID", null);
            }
            var entities = response.entities().stream()
                    .map(VisualGroundingJsonCodec::classifiedEntity)
                    .toList();
            var supportOwnerEntityRegions = usesConnectedSupportOwnerRegions(supportIdPolicy)
                    ? classifiedEntityRegions(response, regionDiagnosticPolicy) : null;
            var classifiedRelationships = response.relationships().stream()
                    .map(relationship -> classifiedRelationship(
                            relationship, inventory, grounding, cardinalityPolicy, supportIdPolicy,
                            supportOwnerEntityRegions
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
                        supportOwnerEntityRegions == null
                                ? classifiedEntityRegions(response, regionDiagnosticPolicy)
                                : supportOwnerEntityRegions,
                        hierarchy,
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
                        supportOwnerEntityRegions == null
                                ? classifiedEntityRegions(response, regionDiagnosticPolicy)
                                : supportOwnerEntityRegions,
                        hierarchy,
                        inventory, grounding, relationshipRegionPolicy
                );
                classifiedEntityRegionConsistency(
                        classifiedEntityRegions.plan(), hierarchy, grounding, regionDiagnosticPolicy
                );
            }
            var entityRegions = classifiedEntityRegions.plan();
            var semanticIssues = SEMANTIC_VERIFIER.verifyHierarchy(
                    inventory, grounding, hierarchy, entityRegions, semanticPolicy
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
                    classifiedRelationships.stream()
                            .mapToInt(ClassifiedRelationship::normalizedEnclosingSupportOwners)
                            .sum(),
                    classifiedRelationships.stream()
                            .mapToInt(ClassifiedRelationship::normalizedSourceAncestorSupportOwners)
                            .sum(),
                    classifiedRelationships.stream()
                            .mapToInt(ClassifiedRelationship::normalizedEmptySupportOwners)
                            .sum(),
                    classifiedRelationships.stream()
                            .mapToInt(ClassifiedRelationship::normalizedEmptySourceAncestorSupportOwners)
                            .sum(),
                    classifiedRelationships.stream()
                            .mapToInt(ClassifiedRelationship::normalizedUnknownSupportOwners)
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
        return relationshipRegionConnectionCompatible(
                relationship.parentEntityId(), relationship.childEntityId(), region,
                entityRegions, grounding
        );
    }

    private static boolean relationshipRegionConnectionCompatible(
            String parentEntityId,
            String childEntityId,
            VisualRegion region,
            VisualEntityRegionPlan entityRegions,
            VisualGroundingPlan grounding
    ) {
        var parent = entityRegions.entities().stream()
                .filter(item -> item.entityId().equals(parentEntityId))
                .findFirst();
        var child = entityRegions.entities().stream()
                .filter(item -> item.entityId().equals(childEntityId))
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
        return parseBindings(
                value, inventory, hierarchy, grounding, entityRegions,
                VisualBindingSemanticPolicy.NEAREST_ENTITY
        );
    }

    VisualElementBindingPlan parseBindings(
            String value,
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy,
            VisualGroundingPlan grounding,
            VisualEntityRegionPlan entityRegions,
            VisualBindingSemanticPolicy semanticPolicy
    ) {
        try {
            Objects.requireNonNull(semanticPolicy, "semanticPolicy");
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
                    inventory, grounding, hierarchy, entityRegions, result, semanticPolicy
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
            VisualRelationshipSupportIdPolicy supportIdPolicy,
            VisualEntityRegionPlan entityRegions
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
                relationship, parentEntityId, childEntityId, inventory, grounding,
                supportIdPolicy, entityRegions,
                classifiedRelationshipSupportIds(
                        relationship.supportingElementIds(), supportIdPolicy
                )
        );
        if (supportIds.ids().isEmpty()) {
            throw invalid("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY", null);
        }
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
                plan, supportIds.normalizedReferences(), supportIds.normalizedOwners(),
                supportIds.normalizedEnclosingOwners(),
                supportIds.normalizedSourceAncestorOwners(),
                supportIds.normalizedEmptySupportOwners(),
                supportIds.normalizedEmptySourceAncestorOwners(),
                supportIds.normalizedUnknownSupportOwners()
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
            ), 0, 0, 0, 0, 0, 0, 0);
        }
        if (values == null) {
            throw invalid("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_MISSING", null);
        }
        if (values.isEmpty()) {
            if (supportsEmptyExactRegionOwner(policy)) {
                return new ClassifiedSupportIds(List.of(), 0, 0, 0, 0, 0, 0, 0);
            }
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
                List.copyOf(unique), values.size() - unique.size(), 0, 0, 0, 0, 0, 0
        );
    }

    private static ClassifiedSupportIds normalizeRelationshipSupportOwner(
            RelationshipOutput relationship,
            String parentEntityId,
            String childEntityId,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualRelationshipSupportIdPolicy policy,
            VisualEntityRegionPlan entityRegions,
            ClassifiedSupportIds supportIds
    ) {
        var exactRegionPolicy = policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_REGION_GROUP_OWNER;
        var emptySupportPolicy = supportsEmptyExactRegionOwner(policy);
        var unknownSupportPolicy = supportsUnknownExactRegionOwner(policy);
        var sourceAncestorPolicy = emptySupportPolicy || policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_OR_SOURCE_ANCESTOR_CONNECTED_GROUP_OWNER;
        var enclosingPolicy = sourceAncestorPolicy || policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_CONNECTED_GROUP_OWNER;
        if (emptySupportPolicy && supportIds.ids().isEmpty()) {
            try {
                var owners = exactConnectedRelationshipRegionGroupOwners(
                        relationship, parentEntityId, childEntityId,
                        inventory, grounding, entityRegions
                );
                if (owners.size() == 1) {
                    return new ClassifiedSupportIds(
                            List.of(owners.getFirst().elementId()),
                            supportIds.normalizedReferences(), 1, 0, 0, 1, 0, 0
                    );
                }
                if (owners.isEmpty() && supportsEmptySourceAncestorOwner(policy)) {
                    var ancestorOwners = strictConnectedRelationshipRegionAncestorGroupOwners(
                            relationship, parentEntityId, childEntityId,
                            inventory, grounding, entityRegions
                    );
                    if (ancestorOwners.size() == 1) {
                        return new ClassifiedSupportIds(
                                List.of(ancestorOwners.getFirst().owner().elementId()),
                                supportIds.normalizedReferences(), 1, 0, 0, 0, 1, 0
                        );
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Preserve the empty-support fixed code for incomplete or unknown structure.
            }
            return supportIds;
        }
        if (unknownSupportPolicy && supportIds.ids().size() == 1) {
            try {
                inventory.requireElement(supportIds.ids().getFirst());
            } catch (IllegalArgumentException unknownSupport) {
                try {
                    var owners = exactConnectedRelationshipRegionGroupOwners(
                            relationship, parentEntityId, childEntityId,
                            inventory, grounding, entityRegions
                    );
                    if (owners.size() == 1) {
                        return new ClassifiedSupportIds(
                                List.of(owners.getFirst().elementId()),
                                supportIds.normalizedReferences(), 1, 0, 0, 0, 0, 1
                        );
                    }
                } catch (IllegalArgumentException ignored) {
                    // Preserve the unknown-support fixed code for incomplete structure.
                }
                return supportIds;
            }
        }
        if ((!exactRegionPolicy && !enclosingPolicy)
                || supportIds.ids().size() != 1) {
            return supportIds;
        }
        try {
            var supporting = inventory.requireElement(supportIds.ids().getFirst());
            if (supporting.kind() == VisualElementKind.GROUP) return supportIds;
            var regionId = VisualAnalysisValidation.localId(relationship.regionId(), "regionId");
            var region = grounding.requireRegion(regionId);
            var containerRegion = region.kind() == VisualRegionKind.GROUP
                    || region.kind() == VisualRegionKind.REPEATED_GROUP;
            var owners = containerRegion ? inventory.elements().stream()
                    .filter(element -> element.kind() == VisualElementKind.GROUP)
                    .filter(element -> grounding.regionIdsForElement(element.elementId())
                            .contains(regionId))
                    .toList() : List.<VisualElement>of();
            if (owners.size() == 1) {
                return new ClassifiedSupportIds(
                        List.of(owners.getFirst().elementId()),
                        supportIds.normalizedReferences(), 1, 0, 0, 0, 0, 0
                );
            }
            if (!enclosingPolicy || owners.size() > 1 || entityRegions == null) return supportIds;
            var supportingRegions = grounding.regionIdsForElement(supporting.elementId());
            if (supportingRegions.isEmpty()) return supportIds;
            var candidates = new ArrayList<SupportOwnerRegion>();
            for (var owner : inventory.elements()) {
                if (owner.kind() != VisualElementKind.GROUP) continue;
                for (var ownerRegionId : grounding.regionIdsForElement(owner.elementId())) {
                    var ownerRegion = grounding.requireRegion(ownerRegionId);
                    if (ownerRegion.kind() != VisualRegionKind.GROUP
                            && ownerRegion.kind() != VisualRegionKind.REPEATED_GROUP) {
                        continue;
                    }
                    if (!relationshipRegionCardinalityCompatible(
                            owner.multiplicity(), ownerRegion
                    )) {
                        continue;
                    }
                    var enclosesSupport = supportingRegions.stream().allMatch(supportRegionId ->
                            grounding.descendantOrSame(supportRegionId, ownerRegionId));
                    if (!enclosesSupport || !relationshipRegionConnectionCompatible(
                            parentEntityId, childEntityId, ownerRegion, entityRegions, grounding
                    )) {
                        continue;
                    }
                    candidates.add(new SupportOwnerRegion(owner, ownerRegion));
                }
            }
            if (candidates.size() == 1) {
                return new ClassifiedSupportIds(
                        List.of(candidates.getFirst().owner().elementId()),
                        supportIds.normalizedReferences(), 1, 1, 0, 0, 0, 0
                );
            }
            if (!candidates.isEmpty() || !sourceAncestorPolicy) return supportIds;
            var sourceAncestorCandidates = new ArrayList<SupportOwnerRegion>();
            for (var owner : inventory.elements()) {
                if (owner.kind() != VisualElementKind.GROUP) continue;
                for (var ownerRegionId : grounding.regionIdsForElement(owner.elementId())) {
                    var ownerRegion = grounding.requireRegion(ownerRegionId);
                    if (ownerRegion.kind() != VisualRegionKind.GROUP
                            && ownerRegion.kind() != VisualRegionKind.REPEATED_GROUP) {
                        continue;
                    }
                    if (!relationshipRegionCardinalityCompatible(
                            owner.multiplicity(), ownerRegion
                    ) || !grounding.descendantOrSame(region.regionId(), ownerRegionId)
                            || !relationshipRegionConnectionCompatible(
                            parentEntityId, childEntityId, ownerRegion, entityRegions, grounding
                    )) {
                        continue;
                    }
                    sourceAncestorCandidates.add(new SupportOwnerRegion(owner, ownerRegion));
                }
            }
            if (sourceAncestorCandidates.size() != 1) return supportIds;
            return new ClassifiedSupportIds(
                    List.of(sourceAncestorCandidates.getFirst().owner().elementId()),
                    supportIds.normalizedReferences(), 1, 0, 1, 0, 0, 0
            );
        } catch (IllegalArgumentException failure) {
            // Keep existing fixed-code diagnostics for unknown supports or invalid regions.
            return supportIds;
        }
    }

    private static List<VisualElement> exactConnectedRelationshipRegionGroupOwners(
            RelationshipOutput relationship,
            String parentEntityId,
            String childEntityId,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualEntityRegionPlan entityRegions
    ) {
        var regionId = VisualAnalysisValidation.localId(relationship.regionId(), "regionId");
        var region = grounding.requireRegion(regionId);
        var containerRegion = region.kind() == VisualRegionKind.GROUP
                || region.kind() == VisualRegionKind.REPEATED_GROUP;
        var connected = entityRegions != null && relationshipRegionConnectionCompatible(
                parentEntityId, childEntityId, region, entityRegions, grounding
        );
        if (!containerRegion || !connected) return List.of();
        return inventory.elements().stream()
                .filter(element -> element.kind() == VisualElementKind.GROUP)
                .filter(element -> grounding.regionIdsForElement(element.elementId())
                        .contains(regionId))
                .filter(element -> relationshipRegionCardinalityCompatible(
                        element.multiplicity(), region
                ))
                .toList();
    }

    private static List<SupportOwnerRegion> strictConnectedRelationshipRegionAncestorGroupOwners(
            RelationshipOutput relationship,
            String parentEntityId,
            String childEntityId,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualEntityRegionPlan entityRegions
    ) {
        var relationshipRegionId = VisualAnalysisValidation.localId(
                relationship.regionId(), "regionId"
        );
        var relationshipRegion = grounding.requireRegion(relationshipRegionId);
        if (entityRegions == null) return List.of();
        var candidates = new ArrayList<SupportOwnerRegion>();
        for (var owner : inventory.elements()) {
            if (owner.kind() != VisualElementKind.GROUP) continue;
            for (var ownerRegionId : grounding.regionIdsForElement(owner.elementId())) {
                var ownerRegion = grounding.requireRegion(ownerRegionId);
                if (ownerRegion.regionId().equals(relationshipRegion.regionId())
                        || (ownerRegion.kind() != VisualRegionKind.GROUP
                        && ownerRegion.kind() != VisualRegionKind.REPEATED_GROUP)
                        || !relationshipRegionCardinalityCompatible(
                        owner.multiplicity(), ownerRegion
                ) || !grounding.descendantOrSame(
                        relationshipRegion.regionId(), ownerRegion.regionId()
                ) || !relationshipRegionConnectionCompatible(
                        parentEntityId, childEntityId, ownerRegion, entityRegions, grounding
                )) {
                    continue;
                }
                candidates.add(new SupportOwnerRegion(owner, ownerRegion));
            }
        }
        return List.copyOf(candidates);
    }

    private static boolean usesConnectedSupportOwnerRegions(
            VisualRelationshipSupportIdPolicy policy
    ) {
        return policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_CONNECTED_GROUP_OWNER
                || policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_OR_SOURCE_ANCESTOR_CONNECTED_GROUP_OWNER
                || supportsEmptyExactRegionOwner(policy);
    }

    private static boolean supportsEmptyExactRegionOwner(
            VisualRelationshipSupportIdPolicy policy
    ) {
        return policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_SUPPORT
                || policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT
                || policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT_AND_EMPTY_SOURCE_ANCESTOR;
    }

    private static boolean supportsUnknownExactRegionOwner(
            VisualRelationshipSupportIdPolicy policy
    ) {
        return policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT
                || policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT_AND_EMPTY_SOURCE_ANCESTOR;
    }

    private static boolean supportsEmptySourceAncestorOwner(
            VisualRelationshipSupportIdPolicy policy
    ) {
        return policy == VisualRelationshipSupportIdPolicy
                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT_AND_EMPTY_SOURCE_ANCESTOR;
    }

    private record ClassifiedSupportIds(
            List<String> ids,
            int normalizedReferences,
            int normalizedOwners,
            int normalizedEnclosingOwners,
            int normalizedSourceAncestorOwners,
            int normalizedEmptySupportOwners,
            int normalizedEmptySourceAncestorOwners,
            int normalizedUnknownSupportOwners
    ) { }

    private record SupportOwnerRegion(VisualElement owner, VisualRegion region) { }

    private record ClassifiedRelationship(
            VisualRelationshipPlan plan,
            int normalizedSupportIdReferences,
            int normalizedSupportOwners,
            int normalizedEnclosingSupportOwners,
            int normalizedSourceAncestorSupportOwners,
            int normalizedEmptySupportOwners,
            int normalizedEmptySourceAncestorSupportOwners,
            int normalizedUnknownSupportOwners
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

    private static <T> T classifiedGroundingShape(
            List<VisualRegion> regions,
            VisualObservationNormalizationPolicy normalizationPolicy,
            CheckedSupplier<T> supplier
    ) {
        try {
            return supplier.get();
        } catch (InvalidVisualAnalysisException failure) {
            throw failure;
        } catch (Exception failure) {
            var code = groundingShapeCode(failure);
            if ("VISUAL_GROUNDING_READING_ORDER_GAP".equals(code)
                    && readingOrderDiagnosticPolicy(normalizationPolicy)) {
                code = boundedReadingOrderDiagnosticCode(regions);
            }
            throw invalid(code, failure);
        }
    }

    private static String boundedReadingOrderDiagnosticCode(List<VisualRegion> regions) {
        var roots = regions.stream().filter(region -> region.parentRegionId() == null).toList();
        if (!contiguousReadingOrders(roots)) return "VISUAL_GROUNDING_READING_ORDER_GAP";

        var siblingsByParent = new HashMap<String, List<VisualRegion>>();
        for (var region : regions) {
            if (region.parentRegionId() != null) {
                siblingsByParent.computeIfAbsent(
                        region.parentRegionId(), ignored -> new ArrayList<>()
                ).add(region);
            }
        }
        var duplicate = false;
        var position = false;
        var unclassified = false;
        for (var siblings : siblingsByParent.values()) {
            if (contiguousReadingOrders(siblings)) continue;
            var distinctOrders = siblings.stream().map(VisualRegion::readingOrder)
                    .collect(java.util.stream.Collectors.toSet());
            if (distinctOrders.size() != siblings.size()) {
                duplicate = true;
            } else if (!canonicalReadingOrder(siblings)) {
                position = true;
            } else {
                unclassified = true;
            }
        }
        if (duplicate && !position && !unclassified) {
            return "VISUAL_GROUNDING_READING_ORDER_DUPLICATE";
        }
        if (position && !duplicate && !unclassified) {
            return "VISUAL_GROUNDING_READING_ORDER_POSITION_INVALID";
        }
        return "VISUAL_GROUNDING_READING_ORDER_GAP";
    }

    private static boolean contiguousReadingOrders(List<VisualRegion> siblings) {
        var orders = siblings.stream().map(VisualRegion::readingOrder).sorted().toList();
        for (var index = 0; index < orders.size(); index++) {
            if (orders.get(index) != index) return false;
        }
        return true;
    }

    private static boolean canonicalReadingOrder(List<VisualRegion> siblings) {
        var byOrder = siblings.stream().sorted(Comparator.comparingInt(VisualRegion::readingOrder))
                .map(VisualRegion::regionId).toList();
        var byPosition = siblings.stream().sorted(Comparator
                        .comparingInt((VisualRegion value) -> value.evidence().getFirst()
                                .boundingBox().top())
                        .thenComparingInt(value -> value.evidence().getFirst()
                                .boundingBox().left())
                        .thenComparing(VisualRegion::regionId))
                .map(VisualRegion::regionId).toList();
        return byOrder.equals(byPosition);
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

    private static ClassifiedObservationRegions observationRegions(
            List<RegionOutput> output,
            List<ElementOutput> elements,
            VisualViewPlan views,
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        var classified = new ArrayList<ClassifiedRegionOutput>();
        for (var region : output) {
            var classifiedKind = lexicalRegionKind(region.kind(), normalizationPolicy);
            if (classifiedKind == null && !structuralKindPolicy(normalizationPolicy)) {
                throw invalid("VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND", null);
            }
            var evidence = originalEvidence(region.evidence(), views);
            classifiedKind = structurallyClassifiedRegionKind(
                    region, evidence, normalizationPolicy, classifiedKind
            );
            classified.add(new ClassifiedRegionOutput(region, evidence, classifiedKind));
        }
        if (constraintUniqueKindPolicy(normalizationPolicy)) {
            classified = new ArrayList<>(normalizeUniqueGroupOwnerKinds(classified, elements));
        }
        var regions = new ArrayList<VisualRegion>();
        var normalizedRegionKinds = 0;
        for (var item : classified) {
            if (item.kind() == null) {
                throw invalid("VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND", null);
            }
            if (item.kind().normalized()) normalizedRegionKinds++;
            var region = item.region();
            regions.add(new VisualRegion(
                    region.regionId(), region.parentRegionId(), item.kind().kind(),
                    region.multiplicity(), region.readingOrder(), region.repeatGroupId(),
                    item.evidence()
            ));
        }
        if (normalizationPolicy == VisualObservationNormalizationPolicy.STRICT) {
            return new ClassifiedObservationRegions(
                    List.copyOf(regions), normalizedRegionKinds, 0, 0, 0
            );
        }
        var itemParents = normalizeUniqueItemParents(regions, normalizedRegionKinds);
        var parentNormalized = uniqueCompatibleParentPolicy(normalizationPolicy)
                ? normalizeUniqueCompatibleParents(
                        itemParents, ancestorParentPolicy(normalizationPolicy)
                ) : itemParents;
        return gappedReadingOrderPolicy(normalizationPolicy)
                ? normalizeUniqueGappedReadingOrders(parentNormalized) : parentNormalized;
    }

    private static boolean structuralKindPolicy(
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        return normalizationPolicy == VisualObservationNormalizationPolicy
                .BOUNDED_STRUCTURAL_KIND_UNIQUE_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                || constraintUniqueKindPolicy(normalizationPolicy);
    }

    private static boolean constraintUniqueKindPolicy(
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        return normalizationPolicy == VisualObservationNormalizationPolicy
                .BOUNDED_CONSTRAINT_UNIQUE_KIND_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                || normalizationPolicy == VisualObservationNormalizationPolicy
                .BOUNDED_CONSTRAINT_UNIQUE_KIND_ANCESTOR_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                || gappedReadingOrderPolicy(normalizationPolicy);
    }

    private static boolean uniqueCompatibleParentPolicy(
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        return normalizationPolicy == VisualObservationNormalizationPolicy
                .BOUNDED_ENUM_UNIQUE_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                || normalizationPolicy == VisualObservationNormalizationPolicy
                .BOUNDED_STRUCTURAL_KIND_UNIQUE_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                || constraintUniqueKindPolicy(normalizationPolicy);
    }

    private static boolean ancestorParentPolicy(
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        return normalizationPolicy == VisualObservationNormalizationPolicy
                .BOUNDED_CONSTRAINT_UNIQUE_KIND_ANCESTOR_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                || gappedReadingOrderPolicy(normalizationPolicy);
    }

    private static boolean gappedReadingOrderPolicy(
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        return normalizationPolicy == VisualObservationNormalizationPolicy
                .BOUNDED_CONSTRAINT_UNIQUE_KIND_ANCESTOR_PARENT_GAPPED_READING_ORDER_EVIDENCE_AND_ITEM_SLOT_OWNER
                || readingOrderDiagnosticPolicy(normalizationPolicy);
    }

    private static boolean readingOrderDiagnosticPolicy(
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        return normalizationPolicy == VisualObservationNormalizationPolicy
                .BOUNDED_CONSTRAINT_UNIQUE_KIND_ANCESTOR_PARENT_GAPPED_READING_ORDER_DIAGNOSTIC_EVIDENCE_AND_ITEM_SLOT_OWNER;
    }

    private static ClassifiedRegionKind structurallyClassifiedRegionKind(
            RegionOutput region,
            List<CandidateEvidence> evidence,
            VisualObservationNormalizationPolicy normalizationPolicy,
            ClassifiedRegionKind classified
    ) {
        if (structuralKindPolicy(normalizationPolicy)) {
            var structural = structurallyRequiredRegionKind(region, evidence);
            if (structural != null && (classified == null || classified.kind() != structural)) {
                return new ClassifiedRegionKind(structural, true);
            }
        }
        if (classified != null) return classified;
        if (constraintUniqueKindPolicy(normalizationPolicy)) {
            return null;
        }
        throw invalid("VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND", null);
    }

    private static List<ClassifiedRegionOutput> normalizeUniqueGroupOwnerKinds(
            List<ClassifiedRegionOutput> source,
            List<ElementOutput> elements
    ) {
        var classified = new ArrayList<>(source);
        var indexesById = new HashMap<String, Integer>();
        for (var index = 0; index < classified.size(); index++) {
            var regionId = classified.get(index).region().regionId();
            if (indexesById.putIfAbsent(regionId, index) != null) return List.copyOf(source);
        }
        boolean changed;
        do {
            changed = false;
            for (var element : elements) {
                if (element.kind() != VisualElementKind.GROUP
                        || element.multiplicity() != VisualMultiplicity.ONE
                        || element.regionIds() == null || element.regionIds().isEmpty()) {
                    continue;
                }
                var ownedIds = new LinkedHashSet<>(element.regionIds());
                if (ownedIds.size() != element.regionIds().size()) continue;
                var owned = new ArrayList<ClassifiedRegionOutput>();
                var complete = true;
                for (var regionId : ownedIds) {
                    var index = indexesById.get(regionId);
                    if (index == null) {
                        complete = false;
                        break;
                    }
                    owned.add(classified.get(index));
                }
                if (!complete || owned.stream().anyMatch(
                        VisualGroundingJsonCodec::compatibleSingularGroupKind
                )) {
                    continue;
                }
                var candidates = owned.stream()
                        .filter(item -> item.kind() == null)
                        .filter(VisualGroundingJsonCodec::possibleSingularGroupKind)
                        .toList();
                if (candidates.size() != 1) continue;
                var candidate = candidates.getFirst();
                var index = indexesById.get(candidate.region().regionId());
                classified.set(index, new ClassifiedRegionOutput(
                        candidate.region(), candidate.evidence(),
                        new ClassifiedRegionKind(VisualRegionKind.GROUP, true)
                ));
                changed = true;
            }
        } while (changed);
        return List.copyOf(classified);
    }

    private static boolean compatibleSingularGroupKind(ClassifiedRegionOutput item) {
        return item.kind() != null
                && item.kind().kind() == VisualRegionKind.GROUP
                && item.region().multiplicity() == VisualMultiplicity.ONE
                && item.region().repeatGroupId() == null;
    }

    private static boolean possibleSingularGroupKind(ClassifiedRegionOutput item) {
        return item.region().parentRegionId() != null
                && item.region().multiplicity() == VisualMultiplicity.ONE
                && item.region().repeatGroupId() == null;
    }

    private static ClassifiedRegionKind lexicalRegionKind(
            String value,
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        if (value != null) {
            try {
                return new ClassifiedRegionKind(VisualRegionKind.valueOf(value), false);
            } catch (IllegalArgumentException ignored) {
                // Bounded aliases are handled below; all other values remain fail-closed.
            }
        }
        if (normalizationPolicy != VisualObservationNormalizationPolicy.STRICT && value != null) {
            var canonical = value.toUpperCase(Locale.ROOT);
            try {
                return new ClassifiedRegionKind(VisualRegionKind.valueOf(canonical), true);
            } catch (IllegalArgumentException ignored) {
                if ("DOCUMENT".equals(canonical)) {
                    return new ClassifiedRegionKind(VisualRegionKind.ROOT, true);
                }
                if ("CONTAINER".equals(canonical)) {
                    return new ClassifiedRegionKind(VisualRegionKind.GROUP, true);
                }
            }
        }
        return null;
    }

    private static VisualRegionKind structurallyRequiredRegionKind(
            RegionOutput region,
            List<CandidateEvidence> evidence
    ) {
        if (region.multiplicity() == VisualMultiplicity.MANY && region.repeatGroupId() != null) {
            return VisualRegionKind.REPEATED_GROUP;
        }
        if (region.multiplicity() == VisualMultiplicity.ONE && region.repeatGroupId() != null) {
            return VisualRegionKind.ITEM;
        }
        if (region.parentRegionId() == null
                && region.multiplicity() == VisualMultiplicity.ONE
                && region.repeatGroupId() == null
                && evidence.size() == 1
                && evidence.getFirst().boundingBox().equals(
                        new CandidateBoundingBox(0, 0, 10_000, 10_000)
                )) {
            return VisualRegionKind.ROOT;
        }
        return null;
    }

    private static ClassifiedObservationRegions normalizeUniqueItemParents(
            List<VisualRegion> source,
            int normalizedRegionKinds
    ) {
        var regions = new ArrayList<>(source);
        var byId = new HashMap<String, VisualRegion>();
        for (var region : regions) {
            if (byId.putIfAbsent(region.regionId(), region) != null) {
                return new ClassifiedObservationRegions(
                        List.copyOf(regions), normalizedRegionKinds, 0, 0, 0
                );
            }
        }
        var affectedParents = new LinkedHashSet<String>();
        var normalizedItemParents = 0;
        for (var index = 0; index < regions.size(); index++) {
            var item = regions.get(index);
            if (item.kind() != VisualRegionKind.ITEM || item.parentRegionId() == null) continue;
            var currentParent = byId.get(item.parentRegionId());
            if (currentParent == null || currentParent.regionId().equals(item.regionId())) continue;
            if (currentParent.kind() == VisualRegionKind.REPEATED_GROUP
                    && Objects.equals(currentParent.repeatGroupId(), item.repeatGroupId())) {
                continue;
            }
            var candidates = regions.stream().filter(candidate ->
                    candidate.kind() == VisualRegionKind.REPEATED_GROUP
                            && Objects.equals(candidate.repeatGroupId(), item.repeatGroupId())
                            && contains(candidate, item)
            ).toList();
            if (candidates.size() != 1) continue;
            var replacementParent = candidates.getFirst();
            affectedParents.add(item.parentRegionId());
            affectedParents.add(replacementParent.regionId());
            var normalized = copyRegion(
                    item, replacementParent.regionId(), item.readingOrder()
            );
            regions.set(index, normalized);
            byId.put(item.regionId(), normalized);
            normalizedItemParents++;
        }
        var normalizedReadingOrders = normalizeReadingOrders(regions, affectedParents);
        return new ClassifiedObservationRegions(
                List.copyOf(regions), normalizedRegionKinds,
                normalizedItemParents, 0, normalizedReadingOrders
        );
    }

    private static ClassifiedObservationRegions normalizeUniqueCompatibleParents(
            ClassifiedObservationRegions source,
            boolean allowUniqueContainingRootAncestor
    ) {
        var regions = new ArrayList<>(source.regions());
        var byId = new HashMap<String, VisualRegion>();
        for (var region : regions) {
            if (byId.putIfAbsent(region.regionId(), region) != null) return source;
        }
        var replacements = new HashMap<String, String>();
        var affectedParents = new LinkedHashSet<String>();
        for (var region : regions) {
            if (!invalidParentLink(region, byId)) continue;
            if (region.parentRegionId() == null || region.kind() == VisualRegionKind.ROOT) {
                return source;
            }
            var candidates = regions.stream()
                    .filter(candidate -> compatibleParent(region, candidate))
                    .filter(candidate -> strictlyContains(candidate, region))
                    .filter(candidate -> !createsCycle(region.regionId(), candidate.regionId(), byId))
                    .toList();
            var minimal = candidates.stream().filter(candidate -> candidates.stream().noneMatch(other ->
                    !other.regionId().equals(candidate.regionId())
                            && strictlyContains(candidate, other)
            )).toList();
            if (replacements.size() >= MAX_NORMALIZED_REGION_PARENTS) {
                return source;
            }
            final String replacement;
            if (minimal.size() == 1) {
                replacement = minimal.getFirst().regionId();
            } else if (minimal.isEmpty() && allowUniqueContainingRootAncestor) {
                replacement = uniqueContainingRootAncestor(region, byId);
                if (replacement == null) return source;
            } else {
                return source;
            }
            replacements.put(region.regionId(), replacement);
            affectedParents.add(region.parentRegionId());
            affectedParents.add(replacement);
        }
        if (replacements.isEmpty()) return source;
        for (var index = 0; index < regions.size(); index++) {
            var region = regions.get(index);
            var replacement = replacements.get(region.regionId());
            if (replacement != null) {
                regions.set(index, copyRegion(region, replacement, region.readingOrder()));
            }
        }
        var normalizedReadingOrders = normalizeReadingOrders(regions, affectedParents);
        try {
            new VisualGroundingPlan(VisualGroundingPlan.VERSION, regions, List.of());
        } catch (IllegalArgumentException ignored) {
            return source;
        }
        return new ClassifiedObservationRegions(
                List.copyOf(regions), source.normalizedRegionKinds(),
                source.normalizedItemParents(), replacements.size(),
                source.normalizedReadingOrders() + normalizedReadingOrders
        );
    }

    private static String uniqueContainingRootAncestor(
            VisualRegion child,
            HashMap<String, VisualRegion> byId
    ) {
        if (child.parentRegionId() == null || child.kind() == VisualRegionKind.ROOT
                || child.kind() == VisualRegionKind.ITEM) {
            return null;
        }
        var parent = byId.get(child.parentRegionId());
        if (parent == null || parent.regionId().equals(child.regionId())
                || !parent.evidence().getFirst().artifactId().equals(
                child.evidence().getFirst().artifactId())
                || contains(parent, child)) {
            return null;
        }
        var visited = new LinkedHashSet<String>();
        var current = parent;
        while (true) {
            if (!visited.add(current.regionId())) return null;
            if (current.kind() == VisualRegionKind.ROOT) {
                return current.parentRegionId() == null && strictlyContains(current, child)
                        ? current.regionId() : null;
            }
            if (current.parentRegionId() == null) return null;
            current = byId.get(current.parentRegionId());
            if (current == null) return null;
        }
    }

    private static ClassifiedObservationRegions normalizeUniqueGappedReadingOrders(
            ClassifiedObservationRegions source
    ) {
        var regions = new ArrayList<>(source.regions());
        var parentIds = new LinkedHashSet<String>();
        regions.stream().map(VisualRegion::parentRegionId).filter(Objects::nonNull)
                .forEach(parentIds::add);
        var normalizedReadingOrders = 0;
        for (var parentId : parentIds) {
            var siblings = new ArrayList<Integer>();
            for (var index = 0; index < regions.size(); index++) {
                if (parentId.equals(regions.get(index).parentRegionId())) siblings.add(index);
            }
            if (siblings.isEmpty()) continue;
            var distinctOrders = siblings.stream().map(index -> regions.get(index).readingOrder())
                    .collect(java.util.stream.Collectors.toSet());
            if (distinctOrders.size() != siblings.size()) continue;
            var byOrder = siblings.stream().sorted(Comparator.comparingInt(
                    index -> regions.get(index).readingOrder()
            )).toList();
            var byPosition = siblings.stream().sorted(Comparator
                    .comparingInt((Integer index) -> regions.get(index).evidence().getFirst()
                            .boundingBox().top())
                    .thenComparingInt(index -> regions.get(index).evidence().getFirst()
                            .boundingBox().left())
                    .thenComparing(index -> regions.get(index).regionId())).toList();
            if (!byOrder.equals(byPosition)) continue;
            var contiguous = true;
            for (var order = 0; order < byOrder.size(); order++) {
                if (regions.get(byOrder.get(order)).readingOrder() != order) {
                    contiguous = false;
                    break;
                }
            }
            if (contiguous) continue;
            var changed = 0;
            for (var order = 0; order < byOrder.size(); order++) {
                if (regions.get(byOrder.get(order)).readingOrder() != order) changed++;
            }
            if (normalizedReadingOrders + changed > MAX_NORMALIZED_READING_ORDERS) return source;
            for (var order = 0; order < byOrder.size(); order++) {
                var index = byOrder.get(order);
                var region = regions.get(index);
                if (region.readingOrder() != order) {
                    regions.set(index, copyRegion(region, region.parentRegionId(), order));
                }
            }
            normalizedReadingOrders += changed;
        }
        if (normalizedReadingOrders == 0) return source;
        try {
            new VisualGroundingPlan(VisualGroundingPlan.VERSION, regions, List.of());
        } catch (IllegalArgumentException ignored) {
            return source;
        }
        return new ClassifiedObservationRegions(
                List.copyOf(regions), source.normalizedRegionKinds(),
                source.normalizedItemParents(), source.normalizedRegionParents(),
                source.normalizedReadingOrders() + normalizedReadingOrders
        );
    }

    private static boolean invalidParentLink(
            VisualRegion region,
            HashMap<String, VisualRegion> byId
    ) {
        if (region.parentRegionId() == null) return false;
        var parent = byId.get(region.parentRegionId());
        if (parent == null || parent.regionId().equals(region.regionId())) return true;
        if (region.kind() == VisualRegionKind.ROOT) return true;
        if (region.kind() == VisualRegionKind.ITEM
                && (parent.kind() != VisualRegionKind.REPEATED_GROUP
                || !Objects.equals(region.repeatGroupId(), parent.repeatGroupId()))) {
            return true;
        }
        return !contains(parent, region);
    }

    private static boolean compatibleParent(VisualRegion child, VisualRegion candidate) {
        if (child.regionId().equals(candidate.regionId())
                || child.kind() == VisualRegionKind.ROOT
                || !child.evidence().getFirst().artifactId().equals(
                candidate.evidence().getFirst().artifactId())) {
            return false;
        }
        if (child.kind() == VisualRegionKind.ITEM) {
            return child.multiplicity() == VisualMultiplicity.ONE
                    && child.repeatGroupId() != null
                    && candidate.kind() == VisualRegionKind.REPEATED_GROUP
                    && candidate.multiplicity() == VisualMultiplicity.MANY
                    && Objects.equals(child.repeatGroupId(), candidate.repeatGroupId());
        }
        return (candidate.kind() == VisualRegionKind.SECTION
                || candidate.kind() == VisualRegionKind.GROUP)
                && candidate.multiplicity() == VisualMultiplicity.ONE
                && candidate.repeatGroupId() == null;
    }

    private static boolean createsCycle(
            String childId,
            String candidateId,
            HashMap<String, VisualRegion> byId
    ) {
        var current = byId.get(candidateId);
        var visited = new LinkedHashSet<String>();
        while (current != null) {
            if (current.regionId().equals(childId) || !visited.add(current.regionId())) return true;
            current = current.parentRegionId() == null ? null : byId.get(current.parentRegionId());
        }
        return false;
    }

    private static int normalizeReadingOrders(
            ArrayList<VisualRegion> regions,
            Set<String> affectedParents
    ) {
        var normalizedReadingOrders = 0;
        for (var parentId : affectedParents) {
            var siblingIndexes = new ArrayList<Integer>();
            for (var index = 0; index < regions.size(); index++) {
                if (Objects.equals(parentId, regions.get(index).parentRegionId())) {
                    siblingIndexes.add(index);
                }
            }
            siblingIndexes.sort(Comparator
                    .comparingInt((Integer index) -> regions.get(index).evidence().getFirst()
                            .boundingBox().top())
                    .thenComparingInt(index -> regions.get(index).evidence().getFirst()
                            .boundingBox().left())
                    .thenComparing(index -> regions.get(index).regionId()));
            for (var order = 0; order < siblingIndexes.size(); order++) {
                var index = siblingIndexes.get(order);
                var region = regions.get(index);
                if (region.readingOrder() == order) continue;
                regions.set(index, copyRegion(region, region.parentRegionId(), order));
                normalizedReadingOrders++;
            }
        }
        return normalizedReadingOrders;
    }

    private static VisualRegion copyRegion(
            VisualRegion source,
            String parentRegionId,
            int readingOrder
    ) {
        return new VisualRegion(
                source.regionId(), parentRegionId, source.kind(), source.multiplicity(),
                readingOrder, source.repeatGroupId(), source.evidence()
        );
    }

    private static boolean contains(VisualRegion outer, VisualRegion inner) {
        var outerEvidence = outer.evidence().getFirst();
        var innerEvidence = inner.evidence().getFirst();
        if (!outerEvidence.artifactId().equals(innerEvidence.artifactId())) return false;
        var left = outerEvidence.boundingBox();
        var right = innerEvidence.boundingBox();
        return left.left() <= right.left() && left.top() <= right.top()
                && left.right() >= right.right() && left.bottom() >= right.bottom();
    }

    private static boolean strictlyContains(VisualRegion outer, VisualRegion inner) {
        return contains(outer, inner) && !outer.evidence().getFirst().boundingBox().equals(
                inner.evidence().getFirst().boundingBox()
        );
    }

    private static boolean contains(CandidateEvidence outer, CandidateEvidence inner) {
        if (!outer.artifactId().equals(inner.artifactId())) return false;
        var left = outer.boundingBox();
        var right = inner.boundingBox();
        return left.left() <= right.left() && left.top() <= right.top()
                && left.right() >= right.right() && left.bottom() >= right.bottom();
    }

    private static NormalizedElementRegionOwnerships normalizeElementEvidenceOwners(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        if (normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_ENUM_UNIQUE_ITEM_PARENT_AND_EVIDENCE_OWNER
                && normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_ENUM_UNIQUE_ITEM_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_ENUM_UNIQUE_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_STRUCTURAL_KIND_UNIQUE_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_CONSTRAINT_UNIQUE_KIND_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_CONSTRAINT_UNIQUE_KIND_ANCESTOR_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && !gappedReadingOrderPolicy(normalizationPolicy)) {
            return new NormalizedElementRegionOwnerships(grounding, 0);
        }
        var inventoryIds = inventory.elements().stream().map(VisualElement::elementId)
                .collect(java.util.stream.Collectors.toSet());
        var ownershipIds = grounding.elementRegions().stream()
                .map(VisualElementRegionOwnership::elementId)
                .collect(java.util.stream.Collectors.toSet());
        if (!inventoryIds.equals(ownershipIds)) {
            return new NormalizedElementRegionOwnerships(grounding, 0);
        }
        var regionsById = new HashMap<String, VisualRegion>();
        grounding.regions().forEach(region -> regionsById.put(region.regionId(), region));
        var normalized = new ArrayList<VisualElementRegionOwnership>();
        var normalizedElements = 0;
        for (var element : inventory.elements()) {
            var ownership = grounding.elementRegions().stream()
                    .filter(item -> item.elementId().equals(element.elementId()))
                    .findFirst().orElseThrow();
            if (ownership.regionIds().stream().anyMatch(id -> !regionsById.containsKey(id))) {
                return new NormalizedElementRegionOwnerships(grounding, 0);
            }
            var allCovered = element.evidence().stream().allMatch(evidence ->
                    ownership.regionIds().stream().map(regionsById::get)
                            .anyMatch(region -> contains(region.evidence().getFirst(), evidence))
            );
            if (allCovered) {
                normalized.add(ownership);
                continue;
            }
            var replacements = new LinkedHashSet<String>();
            ownership.regionIds().stream().map(regionsById::get)
                    .filter(region -> element.evidence().stream().anyMatch(evidence ->
                            contains(region.evidence().getFirst(), evidence)))
                    .map(VisualRegion::regionId).forEach(replacements::add);
            for (var evidence : element.evidence()) {
                if (replacements.stream().map(regionsById::get).anyMatch(region ->
                        contains(region.evidence().getFirst(), evidence))) {
                    continue;
                }
                var candidates = grounding.regions().stream()
                        .filter(region -> region.kind() != VisualRegionKind.ROOT)
                        .filter(region -> compatibleEvidenceOwner(element, region))
                        .filter(region -> contains(region.evidence().getFirst(), evidence))
                        .toList();
                var minimal = candidates.stream().filter(candidate -> candidates.stream().noneMatch(other ->
                        !other.regionId().equals(candidate.regionId())
                                && grounding.descendantOrSame(
                                other.regionId(), candidate.regionId()
                        )
                )).toList();
                if (minimal.size() != 1) {
                    return new NormalizedElementRegionOwnerships(grounding, 0);
                }
                replacements.add(minimal.getFirst().regionId());
            }
            if (replacements.isEmpty() || replacements.size() > 8) {
                return new NormalizedElementRegionOwnerships(grounding, 0);
            }
            normalized.add(new VisualElementRegionOwnership(
                    element.elementId(), List.copyOf(replacements)
            ));
            normalizedElements++;
        }
        return new NormalizedElementRegionOwnerships(new VisualGroundingPlan(
                VisualGroundingPlan.VERSION, grounding.regions(), normalized
        ), normalizedElements);
    }

    private static NormalizedElementRegionOwnerships normalizeRepeatedItemSlotOwners(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualObservationNormalizationPolicy normalizationPolicy
    ) {
        if (normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_ENUM_UNIQUE_ITEM_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_ENUM_UNIQUE_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_STRUCTURAL_KIND_UNIQUE_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_CONSTRAINT_UNIQUE_KIND_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && normalizationPolicy != VisualObservationNormalizationPolicy
                .BOUNDED_CONSTRAINT_UNIQUE_KIND_ANCESTOR_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER
                && !gappedReadingOrderPolicy(normalizationPolicy)) {
            return new NormalizedElementRegionOwnerships(grounding, 0);
        }
        var inventoryIds = inventory.elements().stream().map(VisualElement::elementId)
                .collect(java.util.stream.Collectors.toSet());
        var ownershipIds = grounding.elementRegions().stream()
                .map(VisualElementRegionOwnership::elementId)
                .collect(java.util.stream.Collectors.toSet());
        if (!inventoryIds.equals(ownershipIds)) {
            return new NormalizedElementRegionOwnerships(grounding, 0);
        }
        var slots = inventory.elements().stream()
                .filter(element -> element.kind() == VisualElementKind.SLOT)
                .toList();
        var items = grounding.regions().stream()
                .filter(region -> region.kind() == VisualRegionKind.ITEM)
                .toList();
        var missingItems = items.stream()
                .filter(item -> slots.stream().noneMatch(slot -> grounding
                        .regionIdsForElement(slot.elementId()).stream()
                        .anyMatch(owner -> grounding.descendantOrSame(owner, item.regionId()))))
                .toList();
        if (missingItems.isEmpty()) {
            return new NormalizedElementRegionOwnerships(grounding, 0);
        }
        var regionsById = new HashMap<String, VisualRegion>();
        grounding.regions().forEach(region -> regionsById.put(region.regionId(), region));
        for (var ownership : grounding.elementRegions()) {
            if (ownership.regionIds().stream().anyMatch(id -> !regionsById.containsKey(id))) {
                return new NormalizedElementRegionOwnerships(grounding, 0);
            }
            var element = inventory.requireElement(ownership.elementId());
            if (element.evidence().stream().anyMatch(evidence -> ownership.regionIds().stream()
                    .map(regionsById::get).noneMatch(region ->
                            contains(region.evidence().getFirst(), evidence)))) {
                return new NormalizedElementRegionOwnerships(grounding, 0);
            }
        }
        var itemLocalSlots = slots.stream().filter(slot -> slot.evidence().stream()
                .allMatch(evidence -> items.stream().anyMatch(item ->
                        contains(item.evidence().getFirst(), evidence)))).toList();
        var candidateSlotIds = new LinkedHashSet<String>();
        for (var item : missingItems) {
            var itemEvidence = item.evidence().getFirst();
            var candidates = itemLocalSlots.stream().filter(slot -> slot.evidence().stream()
                    .anyMatch(evidence -> contains(itemEvidence, evidence))).toList();
            if (candidates.isEmpty()) {
                return new NormalizedElementRegionOwnerships(grounding, 0);
            }
            candidates.stream().map(VisualElement::elementId).forEach(candidateSlotIds::add);
        }
        var replacementIds = new HashMap<String, List<String>>();
        for (var slot : itemLocalSlots) {
            if (!candidateSlotIds.contains(slot.elementId())) continue;
            var replacements = new LinkedHashSet<String>();
            for (var evidence : slot.evidence()) {
                var candidates = grounding.regions().stream()
                        .filter(region -> region.kind() != VisualRegionKind.ROOT)
                        .filter(region -> contains(region.evidence().getFirst(), evidence))
                        .toList();
                var minimal = candidates.stream().filter(candidate -> candidates.stream()
                        .noneMatch(other -> !other.regionId().equals(candidate.regionId())
                                && grounding.descendantOrSame(
                                other.regionId(), candidate.regionId()
                        ))).toList();
                if (minimal.size() != 1) {
                    return new NormalizedElementRegionOwnerships(grounding, 0);
                }
                replacements.add(minimal.getFirst().regionId());
            }
            if (replacements.isEmpty() || replacements.size() > 8) {
                return new NormalizedElementRegionOwnerships(grounding, 0);
            }
            replacementIds.put(slot.elementId(), replacements.stream().sorted().toList());
        }
        var normalized = grounding.elementRegions().stream().map(ownership -> {
            var replacement = replacementIds.get(ownership.elementId());
            return replacement == null ? ownership : new VisualElementRegionOwnership(
                    ownership.elementId(), replacement
            );
        }).toList();
        var normalizedGrounding = new VisualGroundingPlan(
                VisualGroundingPlan.VERSION, grounding.regions(), normalized
        );
        var allItemsResolved = missingItems.stream().allMatch(item -> slots.stream().anyMatch(slot ->
                normalizedGrounding.regionIdsForElement(slot.elementId()).stream().anyMatch(owner ->
                        normalizedGrounding.descendantOrSame(owner, item.regionId()))
        ));
        if (!allItemsResolved) {
            return new NormalizedElementRegionOwnerships(grounding, 0);
        }
        var normalizedElements = (int) grounding.elementRegions().stream()
                .filter(ownership -> replacementIds.containsKey(ownership.elementId())
                        && !ownership.regionIds().equals(
                        replacementIds.get(ownership.elementId())))
                .count();
        return normalizedElements == 0
                ? new NormalizedElementRegionOwnerships(grounding, 0)
                : new NormalizedElementRegionOwnerships(
                normalizedGrounding, normalizedElements
        );
    }

    private static boolean compatibleEvidenceOwner(VisualElement element, VisualRegion region) {
        if (element.kind() == VisualElementKind.SLOT) return true;
        return element.multiplicity() == VisualMultiplicity.MANY
                ? region.kind() == VisualRegionKind.REPEATED_GROUP
                : region.kind() == VisualRegionKind.GROUP;
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
            String kind,
            VisualMultiplicity multiplicity,
            int readingOrder,
            String repeatGroupId,
            List<VisualViewEvidence> evidence
    ) { }

    private record ClassifiedRegionKind(VisualRegionKind kind, boolean normalized) { }

    private record ClassifiedRegionOutput(
            RegionOutput region,
            List<CandidateEvidence> evidence,
            ClassifiedRegionKind kind
    ) {
        private ClassifiedRegionOutput {
            Objects.requireNonNull(region, "region");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        }
    }

    private record NormalizedElementRegionOwnerships(
            VisualGroundingPlan grounding,
            int normalizedElements
    ) { }

    private record ClassifiedObservationRegions(
            List<VisualRegion> regions,
            int normalizedRegionKinds,
            int normalizedItemParents,
            int normalizedRegionParents,
            int normalizedReadingOrders
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
        VisualGroundingPlan grounding,
        int normalizedRegionKinds,
        int normalizedItemParents,
        int normalizedRegionParents,
        int normalizedReadingOrders,
        int normalizedElementRegionOwners,
        int normalizedRepeatedItemSlotOwners
) {
    GroundedElementInventory {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
        if (normalizedRegionKinds < 0 || normalizedRegionKinds > grounding.regions().size()
                || normalizedItemParents < 0 || normalizedItemParents > grounding.regions().size()
                || normalizedRegionParents < 0
                || normalizedRegionParents > grounding.regions().size()
                || normalizedReadingOrders < 0
                || normalizedReadingOrders > grounding.regions().size()
                || normalizedElementRegionOwners < 0
                || normalizedElementRegionOwners > inventory.elements().size()
                || normalizedRepeatedItemSlotOwners < 0
                || normalizedRepeatedItemSlotOwners > inventory.elements().size()) {
            throw new IllegalArgumentException("Observation normalization count is invalid");
        }
    }
}

record GroundedHierarchyPlan(
        VisualHierarchyPlan hierarchy,
        VisualEntityRegionPlan entityRegions,
        int derivedRelationshipCardinalities,
        int normalizedRelationshipSupportIdReferences,
        int normalizedRelationshipSupportOwners,
        int normalizedRelationshipEnclosingSupportOwners,
        int normalizedRelationshipSourceAncestorSupportOwners,
        int normalizedRelationshipEmptySupportOwners,
        int normalizedRelationshipEmptySourceAncestorSupportOwners,
        int normalizedRelationshipUnknownSupportOwners,
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
        if (normalizedRelationshipEnclosingSupportOwners < 0
                || normalizedRelationshipEnclosingSupportOwners
                > normalizedRelationshipSupportOwners) {
            throw new IllegalArgumentException(
                    "Normalized enclosing relationship support owner count is invalid"
            );
        }
        if (normalizedRelationshipSourceAncestorSupportOwners < 0
                || normalizedRelationshipEmptySupportOwners < 0
                || normalizedRelationshipEmptySourceAncestorSupportOwners < 0
                || normalizedRelationshipUnknownSupportOwners < 0
                || normalizedRelationshipSourceAncestorSupportOwners
                + normalizedRelationshipEnclosingSupportOwners
                + normalizedRelationshipEmptySupportOwners
                + normalizedRelationshipEmptySourceAncestorSupportOwners
                + normalizedRelationshipUnknownSupportOwners
                > normalizedRelationshipSupportOwners) {
            throw new IllegalArgumentException(
                    "Normalized specialized relationship support owner count is invalid"
            );
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
    CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_REGION_GROUP_OWNER,
    CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_CONNECTED_GROUP_OWNER,
    CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_OR_SOURCE_ANCESTOR_CONNECTED_GROUP_OWNER,
    CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_SUPPORT,
    CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT,
    CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT_AND_EMPTY_SOURCE_ANCESTOR
}

enum VisualRelationshipRegionPolicy {
    STRICT,
    UNIQUE_CARDINALITY_COMPATIBLE_GROUP_REGION,
    UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
}

enum VisualObservationNormalizationPolicy {
    STRICT,
    BOUNDED_ENUM_AND_UNIQUE_ITEM_PARENT,
    BOUNDED_ENUM_UNIQUE_ITEM_PARENT_AND_EVIDENCE_OWNER,
    BOUNDED_ENUM_UNIQUE_ITEM_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER,
    BOUNDED_ENUM_UNIQUE_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER,
    BOUNDED_STRUCTURAL_KIND_UNIQUE_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER,
    BOUNDED_CONSTRAINT_UNIQUE_KIND_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER,
    BOUNDED_CONSTRAINT_UNIQUE_KIND_ANCESTOR_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER,
    BOUNDED_CONSTRAINT_UNIQUE_KIND_ANCESTOR_PARENT_GAPPED_READING_ORDER_EVIDENCE_AND_ITEM_SLOT_OWNER,
    BOUNDED_CONSTRAINT_UNIQUE_KIND_ANCESTOR_PARENT_GAPPED_READING_ORDER_DIAGNOSTIC_EVIDENCE_AND_ITEM_SLOT_OWNER
}
