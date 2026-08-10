package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded, payload-free semantic checks that route an issue to the earliest regenerable stage. */
final class VisualSemanticVerifier {
    static final String VERSION = "renderweave-visual-semantic-verifier/1.0";

    List<VisualSemanticIssue> verifyObservation(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
        var issues = new ArrayList<VisualSemanticIssue>();
        var groups = inventory.elements().stream()
                .filter(element -> element.kind() == VisualElementKind.GROUP)
                .toList();
        var slots = inventory.elements().stream()
                .filter(element -> element.kind() == VisualElementKind.SLOT)
                .toList();

        for (var region : grounding.regions()) {
            if (region.kind() == VisualRegionKind.REPEATED_GROUP) {
                var supportingGroups = groups.stream().filter(group ->
                        grounding.regionIdsForElement(group.elementId()).contains(region.regionId())
                ).toList();
                if (supportingGroups.isEmpty()) {
                    issues.add(VisualSemanticIssue.OBSERVE_REPEATED_GROUP_ELEMENT_MISSING);
                } else if (supportingGroups.stream().noneMatch(group ->
                        group.multiplicity() == VisualMultiplicity.MANY)) {
                    issues.add(VisualSemanticIssue.OBSERVE_REPEATED_GROUP_CARDINALITY_INVALID);
                }
            }
            if (region.kind() == VisualRegionKind.ITEM) {
                var hasOwnedSlot = slots.stream().anyMatch(slot ->
                        grounding.regionIdsForElement(slot.elementId()).stream().anyMatch(owned ->
                                grounding.descendantOrSame(owned, region.regionId())
                        )
                );
                if (!hasOwnedSlot) issues.add(VisualSemanticIssue.OBSERVE_ITEM_FIELD_MISSING);
            }
        }
        for (var group : groups) {
            var ownsContainer = grounding.regionIdsForElement(group.elementId()).stream()
                    .map(grounding::requireRegion)
                    .anyMatch(region -> region.kind() == VisualRegionKind.GROUP
                            || region.kind() == VisualRegionKind.REPEATED_GROUP);
            if (!ownsContainer) issues.add(VisualSemanticIssue.OBSERVE_GROUP_REGION_INVALID);
        }
        return issues.stream().distinct()
                .sorted(Comparator.comparing(VisualSemanticIssue::code)).toList();
    }

    List<VisualSemanticIssue> verifyHierarchy(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualHierarchyPlan hierarchy,
            VisualEntityRegionPlan entityRegions
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(entityRegions, "entityRegions");
        var issues = new ArrayList<VisualSemanticIssue>();
        var groups = inventory.elements().stream()
                .filter(element -> element.kind() == VisualElementKind.GROUP)
                .toList();

        for (var group : groups) {
            var edges = hierarchy.relationships().stream().filter(relationship ->
                    relationship.supportingElementIds().contains(group.elementId())
            ).toList();
            if (edges.isEmpty()) {
                issues.add(VisualSemanticIssue.HIERARCHY_GROUP_EDGE_MISSING);
            } else if (edges.size() != 1) {
                issues.add(VisualSemanticIssue.HIERARCHY_GROUP_EDGE_COUNT_INVALID);
            }
        }
        for (var relationship : hierarchy.relationships()) {
            var supportingGroups = relationship.supportingElementIds().stream()
                    .map(inventory::requireElement)
                    .filter(element -> element.kind() == VisualElementKind.GROUP)
                    .toList();
            if (supportingGroups.size() != 1) {
                issues.add(VisualSemanticIssue.HIERARCHY_EDGE_GROUP_COUNT_INVALID);
                continue;
            }
            var relationshipRegion = entityRegions.requireRelationship(
                    relationship.relationshipId()
            ).regionId();
            if (!grounding.regionIdsForElement(supportingGroups.getFirst().elementId())
                    .contains(relationshipRegion)) {
                issues.add(VisualSemanticIssue.HIERARCHY_EDGE_REGION_INVALID);
            }
        }
        return canonical(issues);
    }

    List<VisualSemanticIssue> verifyHierarchyPrerequisites(
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(hierarchy, "hierarchy");
        var hasGroup = inventory.elements().stream()
                .anyMatch(element -> element.kind() == VisualElementKind.GROUP);
        if (!hierarchy.relationships().isEmpty() && !hasGroup) {
            return List.of(VisualSemanticIssue.OBSERVE_RELATIONSHIP_GROUP_MISSING);
        }
        return List.of();
    }

    List<VisualSemanticIssue> verifyBindings(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualHierarchyPlan hierarchy,
            VisualEntityRegionPlan entityRegions,
            VisualElementBindingPlan bindings
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(entityRegions, "entityRegions");
        Objects.requireNonNull(bindings, "bindings");
        var issues = new ArrayList<VisualSemanticIssue>();
        for (var binding : bindings.bindings()) {
            var elementRegions = grounding.regionIdsForElement(binding.elementId());
            var chosen = entityRegions.requireEntity(binding.entityId());
            var hasNearer = entityRegions.entities().stream()
                    .filter(candidate -> !candidate.entityId().equals(chosen.entityId()))
                    .filter(candidate -> ownsAll(candidate, elementRegions, grounding))
                    .anyMatch(candidate -> strictlyInside(candidate, chosen, grounding));
            if (hasNearer) issues.add(VisualSemanticIssue.BINDING_NOT_NEAREST_ENTITY);
        }
        return canonical(issues);
    }

    private static boolean ownsAll(
            VisualEntityRegionOwnership entity,
            List<String> elementRegions,
            VisualGroundingPlan grounding
    ) {
        return elementRegions.stream().allMatch(elementRegion -> entity.regionIds().stream()
                .anyMatch(entityRegion -> grounding.descendantOrSame(elementRegion, entityRegion)));
    }

    private static boolean strictlyInside(
            VisualEntityRegionOwnership candidate,
            VisualEntityRegionOwnership chosen,
            VisualGroundingPlan grounding
    ) {
        var contained = candidate.regionIds().stream().allMatch(candidateRegion ->
                chosen.regionIds().stream().anyMatch(chosenRegion ->
                        grounding.descendantOrSame(candidateRegion, chosenRegion)
                )
        );
        var strict = candidate.regionIds().stream().anyMatch(candidateRegion ->
                chosen.regionIds().stream().anyMatch(chosenRegion ->
                        !candidateRegion.equals(chosenRegion)
                                && grounding.descendantOrSame(candidateRegion, chosenRegion)
                )
        );
        return contained && strict;
    }

    private static List<VisualSemanticIssue> canonical(List<VisualSemanticIssue> issues) {
        return issues.stream().distinct()
                .sorted(Comparator.comparing(VisualSemanticIssue::code)).toList();
    }
}

enum VisualSemanticIssue {
    OBSERVE_REPEATED_GROUP_ELEMENT_MISSING(
            "VISUAL_SEMANTIC_REPEATED_GROUP_ELEMENT_MISSING", InferenceStage.OBSERVE
    ),
    OBSERVE_REPEATED_GROUP_CARDINALITY_INVALID(
            "VISUAL_SEMANTIC_REPEATED_GROUP_CARDINALITY_INVALID", InferenceStage.OBSERVE
    ),
    OBSERVE_ITEM_FIELD_MISSING(
            "VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING", InferenceStage.OBSERVE
    ),
    OBSERVE_GROUP_REGION_INVALID(
            "VISUAL_SEMANTIC_GROUP_REGION_INVALID", InferenceStage.OBSERVE
    ),
    OBSERVE_RELATIONSHIP_GROUP_MISSING(
            "VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING", InferenceStage.OBSERVE
    ),
    HIERARCHY_GROUP_EDGE_MISSING(
            "VISUAL_SEMANTIC_HIERARCHY_GROUP_EDGE_MISSING", InferenceStage.HIERARCHY
    ),
    HIERARCHY_GROUP_EDGE_COUNT_INVALID(
            "VISUAL_SEMANTIC_HIERARCHY_GROUP_EDGE_COUNT_INVALID", InferenceStage.HIERARCHY
    ),
    HIERARCHY_EDGE_GROUP_COUNT_INVALID(
            "VISUAL_SEMANTIC_HIERARCHY_EDGE_GROUP_COUNT_INVALID", InferenceStage.HIERARCHY
    ),
    HIERARCHY_EDGE_REGION_INVALID(
            "VISUAL_SEMANTIC_HIERARCHY_EDGE_REGION_INVALID", InferenceStage.HIERARCHY
    ),
    BINDING_NOT_NEAREST_ENTITY(
            "VISUAL_SEMANTIC_BINDING_NOT_NEAREST_ENTITY", InferenceStage.ELEMENT_BINDING
    );

    private final String code;
    private final InferenceStage earliestStage;

    VisualSemanticIssue(String code, InferenceStage earliestStage) {
        this.code = code;
        this.earliestStage = earliestStage;
    }

    String code() {
        return code;
    }

    InferenceStage earliestStage() {
        return earliestStage;
    }

    static Optional<InferenceStage> earliestStage(String code) {
        return java.util.Arrays.stream(values())
                .filter(issue -> issue.code.equals(code))
                .map(VisualSemanticIssue::earliestStage)
                .findFirst();
    }
}
