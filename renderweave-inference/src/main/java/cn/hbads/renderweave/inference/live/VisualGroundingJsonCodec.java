package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
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
            var response = read(value, GroundingOutput.class);
            if (!VisualGroundingPlan.VERSION.equals(response.contractVersion())) {
                throw new IllegalArgumentException("Visual grounding version is invalid");
            }
            var regions = response.regions().stream().map(region -> new VisualRegion(
                    region.regionId(), region.parentRegionId(), region.kind(), region.multiplicity(),
                    region.readingOrder(), region.repeatGroupId(),
                    originalEvidence(region.evidence(), views)
            )).toList();
            var elements = response.elements().stream().map(element -> new VisualElement(
                    element.elementId(), element.kind(), element.proposedKey(), element.displayName(),
                    element.multiplicity(), element.valueHint(), originalEvidence(element.evidence(), views)
            )).toList();
            var inventory = new VisualElementInventory(VisualElementInventory.VERSION, elements);
            var grounding = new VisualGroundingPlan(
                    VisualGroundingPlan.VERSION, regions,
                    response.elements().stream().map(element -> new VisualElementRegionOwnership(
                            element.elementId(), element.regionIds()
                    )).toList()
            );
            inventory.requireKnownArtifacts(Set.copyOf(sourceArtifactIds));
            grounding.requireKnownArtifacts(sourceArtifactIds);
            grounding.requireConsistentWith(inventory);
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
            var response = read(value, HierarchyOutput.class);
            if (!VisualHierarchyPlan.VERSION_V2.equals(response.contractVersion())) {
                throw new IllegalArgumentException("Visual hierarchy v2 version is invalid");
            }
            var hierarchy = new VisualHierarchyPlan(
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
            );
            hierarchy.requireConsistentWith(inventory);
            var entityRegions = new VisualEntityRegionPlan(
                    VisualEntityRegionPlan.VERSION,
                    response.entities().stream().map(entity -> new VisualEntityRegionOwnership(
                            entity.entityId(), entity.regionIds()
                    )).toList(),
                    response.relationships().stream().map(relationship ->
                            new VisualRelationshipRegionOwnership(
                                    relationship.relationshipId(), relationship.regionId()
                            )).toList()
            );
            entityRegions.requireConsistentWith(hierarchy, grounding);
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
            var response = read(value, BindingOutput.class);
            if (!VisualElementBindingPlan.VERSION_V2.equals(response.contractVersion())) {
                throw new IllegalArgumentException("Visual bindings v2 version is invalid");
            }
            var result = new VisualElementBindingPlan(
                    VisualElementBindingPlan.VERSION_V2,
                    response.bindings().stream().map(binding ->
                            new VisualElementBinding(binding.elementId(), binding.entityId())
                    ).toList()
            );
            result.requireConsistentWith(inventory, hierarchy);
            entityRegions.requireBindingsConsistent(result, grounding);
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

    private static <T> T read(String value, Class<T> type) throws Exception {
        requireBounded(value);
        return JSON.readValue(value, type);
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
