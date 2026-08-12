package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.run.InferenceStage;
import cn.hbads.renderweave.inference.vision.DocumentVisionObservation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded, payload-free semantic checks that route an issue to the earliest regenerable stage. */
final class VisualSemanticVerifier {
    static final String VERSION = "renderweave-visual-semantic-verifier/1.0";
    private static final int MIN_VERTICAL_SEQUENCE_ITEMS = 8;
    private static final int MAX_VERTICAL_SEQUENCE_CENTER_SPREAD = 2_500;
    private static final int MIN_VERTICAL_SEQUENCE_HORIZONTAL_SPAN = 4_000;

    List<VisualSemanticIssue> verifyObservation(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding
    ) {
        return verifyObservation(
                inventory, grounding, VisualObservationSemanticPolicy.LEGACY
        );
    }

    List<VisualSemanticIssue> verifyObservation(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualObservationSemanticPolicy semanticPolicy
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
        Objects.requireNonNull(semanticPolicy, "semanticPolicy");
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
            var ownedContainers = grounding.regionIdsForElement(group.elementId()).stream()
                    .map(grounding::requireRegion)
                    .filter(region -> region.kind() == VisualRegionKind.GROUP
                            || region.kind() == VisualRegionKind.REPEATED_GROUP)
                    .toList();
            if (ownedContainers.isEmpty()) {
                issues.add(VisualSemanticIssue.OBSERVE_GROUP_REGION_INVALID);
            } else if (semanticPolicy == VisualObservationSemanticPolicy
                    .SLOT_LEAF_EVIDENCE_AND_GROUP_REGION_CARDINALITY_REQUIRED
                    && ownedContainers.stream().noneMatch(region ->
                    group.multiplicity() == VisualMultiplicity.MANY
                            ? region.kind() == VisualRegionKind.REPEATED_GROUP
                            : region.multiplicity() == VisualMultiplicity.ONE)) {
                issues.add(VisualSemanticIssue.OBSERVE_REPEATED_GROUP_CARDINALITY_INVALID);
            }
        }
        issues.addAll(verifyElementEvidenceTopology(inventory, semanticPolicy));
        return issues.stream().distinct()
                .sorted(Comparator.comparing(VisualSemanticIssue::code)).toList();
    }

    List<VisualSemanticIssue> verifyElementEvidenceTopology(
            VisualElementInventory inventory,
            VisualObservationSemanticPolicy semanticPolicy
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(semanticPolicy, "semanticPolicy");
        if (semanticPolicy == VisualObservationSemanticPolicy.LEGACY) return List.of();
        for (var slot : inventory.elements()) {
            if (slot.kind() != VisualElementKind.SLOT) continue;
            for (var outer : slot.evidence()) {
                var containsAnotherElement = inventory.elements().stream()
                        .filter(other -> !other.elementId().equals(slot.elementId()))
                        .flatMap(other -> other.evidence().stream())
                        .anyMatch(inner -> strictlyContains(outer, inner));
                if (containsAnotherElement) {
                    return List.of(VisualSemanticIssue.OBSERVE_SLOT_EVIDENCE_CONTAINS_ELEMENT);
                }
            }
        }
        return List.of();
    }

    /**
     * Rejects a silent ROOT-only omission only when local OCR geometry supplies a strong,
     * payload-free vertical sequence signal. Text is deliberately never inspected.
     */
    List<VisualSemanticIssue> verifyDocumentVisionCoverage(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            DocumentVisionObservation observation
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
        Objects.requireNonNull(observation, "observation");
        var covered = inventory.elements().stream().anyMatch(element ->
                element.kind() == VisualElementKind.GROUP
                        && element.multiplicity() == VisualMultiplicity.MANY
                        && grounding.regionIdsForElement(element.elementId()).stream()
                        .map(grounding::requireRegion)
                        .anyMatch(region -> region.kind() == VisualRegionKind.REPEATED_GROUP)
        );
        if (covered) return List.of();

        for (var artifact : observation.artifacts()) {
            var vertical = artifact.lines().stream()
                    .filter(line -> line.confidence()
                            != DocumentVisionObservation.ConfidenceBucket.LOW)
                    .map(DocumentVisionObservation.TextLine::boundingBox)
                    .filter(box -> {
                        var width = box.right() - box.left();
                        var height = box.bottom() - box.top();
                        return width <= 1_200 && height >= 600 && height <= 4_000
                                && height >= width * 2;
                    })
                    .sorted(Comparator.comparingInt(box -> box.top() + box.bottom()))
                    .toList();
            for (var start = 0; start < vertical.size(); start++) {
                var minimumCenter = vertical.get(start).top() + vertical.get(start).bottom();
                var minimumLeft = 10_000;
                var maximumRight = 0;
                var count = 0;
                for (var index = start; index < vertical.size(); index++) {
                    var box = vertical.get(index);
                    var center = box.top() + box.bottom();
                    if (center - minimumCenter > MAX_VERTICAL_SEQUENCE_CENTER_SPREAD * 2) break;
                    minimumLeft = Math.min(minimumLeft, box.left());
                    maximumRight = Math.max(maximumRight, box.right());
                    count++;
                }
                if (count >= MIN_VERTICAL_SEQUENCE_ITEMS
                        && maximumRight - minimumLeft >= MIN_VERTICAL_SEQUENCE_HORIZONTAL_SPAN) {
                    return List.of(VisualSemanticIssue.OBSERVE_DOCUMENT_SEQUENCE_GROUP_MISSING);
                }
            }
        }
        return List.of();
    }

    List<VisualSemanticIssue> verifyHierarchy(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualHierarchyPlan hierarchy,
            VisualEntityRegionPlan entityRegions
    ) {
        return verifyHierarchy(
                inventory, grounding, hierarchy, entityRegions,
                VisualHierarchySemanticPolicy.LEGACY
        );
    }

    List<VisualSemanticIssue> verifyHierarchy(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualHierarchyPlan hierarchy,
            VisualEntityRegionPlan entityRegions,
            VisualHierarchySemanticPolicy semanticPolicy
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(entityRegions, "entityRegions");
        Objects.requireNonNull(semanticPolicy, "semanticPolicy");
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
        issues.addAll(verifyEntityRegionTopology(
                grounding, hierarchy, entityRegions, semanticPolicy
        ));
        return canonical(issues);
    }

    List<VisualSemanticIssue> verifyEntityRegionTopology(
            VisualGroundingPlan grounding,
            VisualHierarchyPlan hierarchy,
            VisualEntityRegionPlan entityRegions,
            VisualHierarchySemanticPolicy semanticPolicy
    ) {
        Objects.requireNonNull(grounding, "grounding");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(entityRegions, "entityRegions");
        Objects.requireNonNull(semanticPolicy, "semanticPolicy");
        if (semanticPolicy == VisualHierarchySemanticPolicy.LEGACY) return List.of();

        var issues = new ArrayList<VisualSemanticIssue>();
        var rootRegions = Set.copyOf(grounding.rootRegionIds());
        for (var entity : entityRegions.entities()) {
            if (!entity.entityId().equals(hierarchy.rootEntityId())
                    && entity.regionIds().stream().anyMatch(rootRegions::contains)) {
                issues.add(VisualSemanticIssue.HIERARCHY_NON_ROOT_OWNS_ROOT_REGION);
            }
            for (var left = 0; left < entity.regionIds().size(); left++) {
                for (var right = left + 1; right < entity.regionIds().size(); right++) {
                    var leftRegion = entity.regionIds().get(left);
                    var rightRegion = entity.regionIds().get(right);
                    if (grounding.descendantOrSame(leftRegion, rightRegion)
                            || grounding.descendantOrSame(rightRegion, leftRegion)) {
                        issues.add(VisualSemanticIssue.HIERARCHY_ENTITY_REGION_REDUNDANT);
                    }
                }
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
        return verifyHierarchyPrerequisites(inventory, !hierarchy.relationships().isEmpty());
    }

    List<VisualSemanticIssue> verifyHierarchyPrerequisites(
            VisualElementInventory inventory,
            boolean hasRelationships
    ) {
        Objects.requireNonNull(inventory, "inventory");
        var hasGroup = inventory.elements().stream()
                .anyMatch(element -> element.kind() == VisualElementKind.GROUP);
        if (hasRelationships && !hasGroup) {
            return List.of(VisualSemanticIssue.OBSERVE_RELATIONSHIP_GROUP_MISSING);
        }
        return List.of();
    }

    List<VisualSemanticIssue> verifyRelationshipRegionGroupOwners(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualHierarchyPlan hierarchy,
            VisualEntityRegionPlan entityRegions
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(entityRegions, "entityRegions");
        var groups = inventory.elements().stream()
                .filter(element -> element.kind() == VisualElementKind.GROUP)
                .toList();
        var issues = new ArrayList<VisualSemanticIssue>();
        for (var relationship : hierarchy.relationships()) {
            var relationshipRegionId = entityRegions.requireRelationship(
                    relationship.relationshipId()
            ).regionId();
            var relationshipRegion = grounding.requireRegion(relationshipRegionId);
            if ((relationshipRegion.kind() == VisualRegionKind.GROUP
                    || relationshipRegion.kind() == VisualRegionKind.REPEATED_GROUP)
                    && groups.stream().noneMatch(group -> grounding
                    .regionIdsForElement(group.elementId()).contains(relationshipRegionId))) {
                issues.add(VisualSemanticIssue.OBSERVE_RELATIONSHIP_REGION_GROUP_MISSING);
            }
        }
        return canonical(issues);
    }

    List<VisualSemanticIssue> verifyBindings(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualHierarchyPlan hierarchy,
            VisualEntityRegionPlan entityRegions,
            VisualElementBindingPlan bindings
    ) {
        return verifyBindings(
                inventory, grounding, hierarchy, entityRegions, bindings,
                VisualBindingSemanticPolicy.NEAREST_ENTITY
        );
    }

    List<VisualSemanticIssue> verifyBindings(
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualHierarchyPlan hierarchy,
            VisualEntityRegionPlan entityRegions,
            VisualElementBindingPlan bindings,
            VisualBindingSemanticPolicy semanticPolicy
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(grounding, "grounding");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(entityRegions, "entityRegions");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(semanticPolicy, "semanticPolicy");
        var issues = new ArrayList<VisualSemanticIssue>();
        for (var binding : bindings.bindings()) {
            var elementRegions = grounding.regionIdsForElement(binding.elementId());
            var chosen = entityRegions.requireEntity(binding.entityId());
            if (semanticPolicy == VisualBindingSemanticPolicy.UNIQUE_MINIMAL_ENTITY_OWNER) {
                var owners = entityRegions.entities().stream()
                        .filter(candidate -> ownsAll(candidate, elementRegions, grounding))
                        .toList();
                var minimal = owners.stream().filter(candidate -> owners.stream().noneMatch(other ->
                        !other.entityId().equals(candidate.entityId())
                                && strictlyInside(other, candidate, grounding)
                )).toList();
                if (minimal.size() != 1) {
                    issues.add(VisualSemanticIssue.HIERARCHY_BINDING_OWNER_AMBIGUOUS);
                } else if (!minimal.getFirst().entityId().equals(chosen.entityId())) {
                    issues.add(VisualSemanticIssue.BINDING_NOT_NEAREST_ENTITY);
                }
                continue;
            }
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

    private static boolean strictlyContains(CandidateEvidence outer, CandidateEvidence inner) {
        if (!outer.artifactId().equals(inner.artifactId())) return false;
        var container = outer.boundingBox();
        var contained = inner.boundingBox();
        return !container.equals(contained)
                && container.left() <= contained.left()
                && container.top() <= contained.top()
                && container.right() >= contained.right()
                && container.bottom() >= contained.bottom();
    }

    private static List<VisualSemanticIssue> canonical(List<VisualSemanticIssue> issues) {
        return issues.stream().distinct()
                .sorted(Comparator.comparing(VisualSemanticIssue::code)).toList();
    }
}

enum VisualObservationSemanticPolicy {
    LEGACY,
    SLOT_LEAF_EVIDENCE_REQUIRED,
    SLOT_LEAF_EVIDENCE_AND_GROUP_REGION_CARDINALITY_REQUIRED
}

enum VisualHierarchySemanticPolicy {
    LEGACY,
    MINIMAL_ENTITY_REGION_OWNERSHIP
}

enum VisualBindingSemanticPolicy {
    NEAREST_ENTITY,
    UNIQUE_MINIMAL_ENTITY_OWNER
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
    OBSERVE_SLOT_EVIDENCE_CONTAINS_ELEMENT(
            "VISUAL_SEMANTIC_SLOT_EVIDENCE_CONTAINS_ELEMENT", InferenceStage.OBSERVE
    ),
    OBSERVE_RELATIONSHIP_GROUP_MISSING(
            "VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING", InferenceStage.OBSERVE
    ),
    OBSERVE_RELATIONSHIP_REGION_GROUP_MISSING(
            "VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_REGION_GROUP_MISSING", InferenceStage.OBSERVE
    ),
    OBSERVE_DOCUMENT_SEQUENCE_GROUP_MISSING(
            "VISUAL_SEMANTIC_OBSERVE_DOCUMENT_SEQUENCE_GROUP_MISSING", InferenceStage.OBSERVE
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
    HIERARCHY_ENTITY_REGION_REDUNDANT(
            "VISUAL_SEMANTIC_HIERARCHY_ENTITY_REGION_REDUNDANT", InferenceStage.HIERARCHY
    ),
    HIERARCHY_NON_ROOT_OWNS_ROOT_REGION(
            "VISUAL_SEMANTIC_HIERARCHY_NON_ROOT_OWNS_ROOT_REGION", InferenceStage.HIERARCHY
    ),
    HIERARCHY_BINDING_OWNER_AMBIGUOUS(
            "VISUAL_SEMANTIC_HIERARCHY_BINDING_OWNER_AMBIGUOUS", InferenceStage.HIERARCHY
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
