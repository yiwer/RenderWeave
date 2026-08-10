package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical original-artifact region forest and element ownership produced from view-relative evidence. */
record VisualGroundingPlan(
        String contractVersion,
        List<VisualRegion> regions,
        List<VisualElementRegionOwnership> elementRegions
) {
    static final String VERSION = "renderweave-visual-grounding/2.0";
    private static final int MAX_REGIONS = 128;

    VisualGroundingPlan {
        if (!VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("Unsupported visual grounding contract");
        }
        regions = Objects.requireNonNull(regions, "regions").stream()
                .sorted(Comparator.comparing(VisualRegion::regionId)).toList();
        elementRegions = Objects.requireNonNull(elementRegions, "elementRegions").stream()
                .sorted(Comparator.comparing(VisualElementRegionOwnership::elementId)).toList();
        if (regions.isEmpty() || regions.size() > MAX_REGIONS) {
            throw new IllegalArgumentException("Visual grounding must contain 1..128 regions");
        }
        validateRegionForest(regions);
        var elementIds = new HashSet<String>();
        for (var ownership : elementRegions) {
            if (!elementIds.add(ownership.elementId())) {
                throw new IllegalArgumentException("Visual element region ownership must be unique");
            }
        }
    }

    void requireKnownArtifacts(List<String> artifactIds) {
        artifactIds = List.copyOf(Objects.requireNonNull(artifactIds, "artifactIds"));
        var orderedArtifacts = artifactIds.stream().distinct().toList();
        var knownArtifacts = Set.copyOf(orderedArtifacts);
        if (knownArtifacts.isEmpty()) throw new IllegalArgumentException("Visual grounding requires artifacts");
        var rootArtifacts = new HashSet<String>();
        for (var region : regions) {
            var artifactId = region.evidence().getFirst().artifactId();
            if (!knownArtifacts.contains(artifactId)) {
                throw new IllegalArgumentException("Visual region references an unknown artifact");
            }
            if (region.parentRegionId() == null && !rootArtifacts.add(artifactId)) {
                throw new IllegalArgumentException("Each artifact may have only one root region");
            }
        }
        if (!rootArtifacts.equals(knownArtifacts)) {
            throw new IllegalArgumentException("Every artifact requires exactly one root region");
        }
        var actualOrder = regions.stream().filter(region -> region.parentRegionId() == null)
                .sorted(Comparator.comparingInt(VisualRegion::readingOrder))
                .map(region -> region.evidence().getFirst().artifactId()).toList();
        if (!actualOrder.equals(orderedArtifacts)) {
            throw new IllegalArgumentException("Root region order must match source artifact order");
        }
    }

    void requireConsistentWith(VisualElementInventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        var expectedElements = inventory.elements().stream().map(VisualElement::elementId)
                .collect(java.util.stream.Collectors.toSet());
        var actualElements = elementRegions.stream().map(VisualElementRegionOwnership::elementId)
                .collect(java.util.stream.Collectors.toSet());
        if (!expectedElements.equals(actualElements)) {
            throw new IllegalArgumentException("Every visual element requires region ownership");
        }
        for (var ownership : elementRegions) {
            var element = inventory.requireElement(ownership.elementId());
            for (var regionId : ownership.regionIds()) requireRegion(regionId);
            for (var evidence : element.evidence()) {
                var covered = ownership.regionIds().stream().map(this::requireRegion)
                        .anyMatch(region -> contains(region.evidence().getFirst(), evidence));
                if (!covered) {
                    throw new IllegalArgumentException("Element evidence must be contained by an owned region");
                }
            }
        }
    }

    VisualRegion requireRegion(String regionId) {
        return regions.stream().filter(region -> region.regionId().equals(regionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Visual plan references an unknown region"));
    }

    List<String> regionIdsForElement(String elementId) {
        return elementRegions.stream().filter(item -> item.elementId().equals(elementId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Visual element has no region ownership"))
                .regionIds();
    }

    List<String> rootRegionIds() {
        return regions.stream().filter(item -> item.parentRegionId() == null)
                .sorted(Comparator.comparingInt(VisualRegion::readingOrder))
                .map(VisualRegion::regionId).toList();
    }

    boolean descendantOrSame(String possibleDescendant, String possibleAncestor) {
        var current = requireRegion(possibleDescendant);
        while (true) {
            if (current.regionId().equals(possibleAncestor)) return true;
            if (current.parentRegionId() == null) return false;
            current = requireRegion(current.parentRegionId());
        }
    }

    private static void validateRegionForest(List<VisualRegion> regions) {
        var byId = new HashMap<String, VisualRegion>();
        for (var region : regions) {
            if (byId.putIfAbsent(region.regionId(), region) != null) {
                throw new IllegalArgumentException("Visual region ids must be unique");
            }
        }
        var roots = regions.stream().filter(item -> item.parentRegionId() == null).toList();
        if (roots.isEmpty() || roots.size() > MultiScaleVisualViewPlanner.MAX_VIEWS) {
            throw new IllegalArgumentException("Visual grounding requires 1..10 roots");
        }
        for (var root : roots) {
            if (root.kind() != VisualRegionKind.ROOT
                    || !root.evidence().getFirst().boundingBox().equals(
                    new CandidateBoundingBox(0, 0, 10_000, 10_000))) {
                throw new IllegalArgumentException("Visual roots must cover their complete source artifact");
            }
        }

        var children = new HashMap<String, List<VisualRegion>>();
        for (var region : regions) {
            if (region.parentRegionId() == null) continue;
            var parent = byId.get(region.parentRegionId());
            if (parent == null || parent.regionId().equals(region.regionId())) {
                throw new IllegalArgumentException("Visual region parent is invalid");
            }
            if (region.kind() == VisualRegionKind.ROOT
                    || (region.kind() == VisualRegionKind.ITEM
                    && (parent.kind() != VisualRegionKind.REPEATED_GROUP
                    || !Objects.equals(region.repeatGroupId(), parent.repeatGroupId())))) {
                throw new IllegalArgumentException("Visual region kind is invalid for its parent");
            }
            if (!parent.evidence().getFirst().artifactId().equals(region.evidence().getFirst().artifactId())
                    || !contains(parent.evidence().getFirst(), region.evidence().getFirst())) {
                throw new IllegalArgumentException("Visual child regions must be contained by their parent");
            }
            children.computeIfAbsent(parent.regionId(), ignored -> new ArrayList<>()).add(region);
        }

        var visited = new HashSet<String>();
        var queue = new ArrayDeque<RegionDepth>();
        roots.forEach(root -> queue.add(new RegionDepth(root.regionId(), 1)));
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (current.depth() > VisualAnalysisValidation.MAX_TREE_DEPTH
                    || !visited.add(current.regionId())) {
                throw new IllegalArgumentException("Visual region graph is cyclic or too deep");
            }
            children.getOrDefault(current.regionId(), List.of()).forEach(child ->
                    queue.addLast(new RegionDepth(child.regionId(), current.depth() + 1))
            );
        }
        if (visited.size() != regions.size()) {
            throw new IllegalArgumentException("Visual region graph contains an orphan");
        }

        validateSiblingSet("<roots>", roots);
        children.forEach(VisualGroundingPlan::validateSiblingSet);
        for (var region : regions) validateRepeatSemantics(region, children.getOrDefault(region.regionId(), List.of()));
    }

    private static void validateSiblingSet(String parentId, List<VisualRegion> siblings) {
        var orders = siblings.stream().map(VisualRegion::readingOrder).sorted().toList();
        for (var index = 0; index < orders.size(); index++) {
            if (orders.get(index) != index) {
                throw new IllegalArgumentException("Visual sibling readingOrder must be contiguous from zero");
            }
        }
        for (var left = 0; left < siblings.size(); left++) {
            for (var right = left + 1; right < siblings.size(); right++) {
                if (siblings.get(left).evidence().getFirst().artifactId().equals(
                        siblings.get(right).evidence().getFirst().artifactId())
                        && overlaps(siblings.get(left).evidence().getFirst().boundingBox(),
                        siblings.get(right).evidence().getFirst().boundingBox())) {
                    throw new IllegalArgumentException("Visual sibling regions must not overlap: " + parentId);
                }
            }
        }
        if ("<roots>".equals(parentId)) return;
        var byOrder = siblings.stream().sorted(Comparator.comparingInt(VisualRegion::readingOrder)).toList();
        var byPosition = siblings.stream().sorted(Comparator
                .comparingInt((VisualRegion value) -> value.evidence().getFirst().boundingBox().top())
                .thenComparingInt(value -> value.evidence().getFirst().boundingBox().left())
                .thenComparing(VisualRegion::regionId)).toList();
        if (!byOrder.equals(byPosition)) {
            throw new IllegalArgumentException("Visual readingOrder must follow canonical top-left order");
        }
    }

    private static void validateRepeatSemantics(VisualRegion region, List<VisualRegion> children) {
        if (region.kind() == VisualRegionKind.REPEATED_GROUP) {
            if (region.multiplicity() != VisualMultiplicity.MANY || region.repeatGroupId() == null
                    || children.isEmpty() || children.stream().anyMatch(child ->
                    child.kind() != VisualRegionKind.ITEM
                            || !region.repeatGroupId().equals(child.repeatGroupId()))) {
                throw new IllegalArgumentException("Repeated regions require matching item children");
            }
            return;
        }
        if (region.kind() == VisualRegionKind.ITEM) {
            if (region.multiplicity() != VisualMultiplicity.ONE || region.repeatGroupId() == null) {
                throw new IllegalArgumentException("Repeated items require one repeat group identity");
            }
            return;
        }
        if (region.multiplicity() != VisualMultiplicity.ONE || region.repeatGroupId() != null) {
            throw new IllegalArgumentException("Non-repeated visual regions must be singular");
        }
    }

    private static boolean contains(CandidateEvidence outer, CandidateEvidence inner) {
        if (!outer.artifactId().equals(inner.artifactId())) return false;
        var left = outer.boundingBox();
        var right = inner.boundingBox();
        return left.left() <= right.left() && left.top() <= right.top()
                && left.right() >= right.right() && left.bottom() >= right.bottom();
    }

    private static boolean overlaps(CandidateBoundingBox left, CandidateBoundingBox right) {
        return Math.max(left.left(), right.left()) < Math.min(left.right(), right.right())
                && Math.max(left.top(), right.top()) < Math.min(left.bottom(), right.bottom());
    }

    private record RegionDepth(String regionId, int depth) { }
}

enum VisualRegionKind { ROOT, SECTION, GROUP, REPEATED_GROUP, ITEM }

record VisualRegion(
        String regionId,
        String parentRegionId,
        VisualRegionKind kind,
        VisualMultiplicity multiplicity,
        int readingOrder,
        String repeatGroupId,
        List<CandidateEvidence> evidence
) {
    VisualRegion {
        regionId = VisualAnalysisValidation.localId(regionId, "regionId");
        if (parentRegionId != null) parentRegionId = VisualAnalysisValidation.localId(
                parentRegionId, "parentRegionId"
        );
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(multiplicity, "multiplicity");
        if (readingOrder < 0 || readingOrder > 127) {
            throw new IllegalArgumentException("Visual region readingOrder must be 0..127");
        }
        if (repeatGroupId != null) repeatGroupId = VisualAnalysisValidation.localId(
                repeatGroupId, "repeatGroupId"
        );
        evidence = VisualAnalysisValidation.imageEvidence(evidence, "region evidence");
        if (evidence.size() != 1) throw new IllegalArgumentException("A visual region requires one direct box");
    }
}

record VisualElementRegionOwnership(String elementId, List<String> regionIds) {
    VisualElementRegionOwnership {
        elementId = VisualAnalysisValidation.localId(elementId, "elementId");
        regionIds = VisualAnalysisValidation.localIds(regionIds, "regionIds", 8).stream().sorted().toList();
    }
}

/** Spatial ownership added by hierarchy/2.0 without changing Candidate materialization topology. */
record VisualEntityRegionPlan(
        String contractVersion,
        List<VisualEntityRegionOwnership> entities,
        List<VisualRelationshipRegionOwnership> relationships
) {
    static final String VERSION = "renderweave-visual-entity-regions/2.0";

    VisualEntityRegionPlan {
        if (!VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("Unsupported visual entity-region contract");
        }
        entities = Objects.requireNonNull(entities, "entities").stream()
                .sorted(Comparator.comparing(VisualEntityRegionOwnership::entityId)).toList();
        relationships = Objects.requireNonNull(relationships, "relationships").stream()
                .sorted(Comparator.comparing(VisualRelationshipRegionOwnership::relationshipId)).toList();
        unique(entities.stream().map(VisualEntityRegionOwnership::entityId).toList(), "entity");
        unique(relationships.stream().map(VisualRelationshipRegionOwnership::relationshipId).toList(),
                "relationship");
    }

    void requireConsistentWith(VisualHierarchyPlan hierarchy, VisualGroundingPlan grounding) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(grounding, "grounding");
        var expectedEntities = hierarchy.entities().stream().map(VisualEntityPlan::entityId)
                .collect(java.util.stream.Collectors.toSet());
        var actualEntities = entities.stream().map(VisualEntityRegionOwnership::entityId)
                .collect(java.util.stream.Collectors.toSet());
        var expectedRelationships = hierarchy.relationships().stream().map(VisualRelationshipPlan::relationshipId)
                .collect(java.util.stream.Collectors.toSet());
        var actualRelationships = relationships.stream()
                .map(VisualRelationshipRegionOwnership::relationshipId)
                .collect(java.util.stream.Collectors.toSet());
        if (!expectedEntities.equals(actualEntities) || !expectedRelationships.equals(actualRelationships)) {
            throw new IllegalArgumentException("Entity-region ownership must cover the complete hierarchy");
        }
        for (var entity : entities) entity.regionIds().forEach(grounding::requireRegion);
        var root = requireEntity(hierarchy.rootEntityId());
        if (!root.regionIds().containsAll(grounding.rootRegionIds())) {
            throw new IllegalArgumentException("Root entity must own every artifact root region");
        }
        for (var relationship : hierarchy.relationships()) {
            var ownership = requireRelationship(relationship.relationshipId());
            var region = grounding.requireRegion(ownership.regionId());
            var parent = requireEntity(relationship.parentEntityId());
            var child = requireEntity(relationship.childEntityId());
            if (parent.regionIds().stream().noneMatch(parentRegion ->
                    grounding.descendantOrSame(region.regionId(), parentRegion))
                    || child.regionIds().stream().noneMatch(childRegion ->
                    grounding.descendantOrSame(childRegion, region.regionId()))) {
                throw new IllegalArgumentException("Relationship region must connect parent and child ownership");
            }
            if ((relationship.cardinality() == VisualMultiplicity.MANY
                    && region.kind() != VisualRegionKind.REPEATED_GROUP)
                    || (relationship.cardinality() == VisualMultiplicity.ONE
                    && region.multiplicity() != VisualMultiplicity.ONE)) {
                throw new IllegalArgumentException("Relationship cardinality conflicts with its owned region");
            }
        }
    }

    void requireBindingsConsistent(
            VisualElementBindingPlan bindings,
            VisualGroundingPlan grounding
    ) {
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(grounding, "grounding");
        for (var binding : bindings.bindings()) {
            var entityRegions = requireEntity(binding.entityId()).regionIds();
            for (var elementRegion : grounding.regionIdsForElement(binding.elementId())) {
                if (entityRegions.stream().noneMatch(entityRegion ->
                        grounding.descendantOrSame(elementRegion, entityRegion))) {
                    throw new IllegalArgumentException("Bound element falls outside its entity regions");
                }
            }
        }
    }

    VisualEntityRegionOwnership requireEntity(String entityId) {
        return entities.stream().filter(item -> item.entityId().equals(entityId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity region ownership"));
    }

    VisualRelationshipRegionOwnership requireRelationship(String relationshipId) {
        return relationships.stream().filter(item -> item.relationshipId().equals(relationshipId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown relationship region ownership"));
    }

    private static void unique(List<String> ids, String kind) {
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("Visual " + kind + " region ownership must be unique");
        }
    }
}

record VisualEntityRegionOwnership(String entityId, List<String> regionIds) {
    VisualEntityRegionOwnership {
        entityId = VisualAnalysisValidation.localId(entityId, "entityId");
        regionIds = VisualAnalysisValidation.localIds(regionIds, "regionIds", 16).stream().sorted().toList();
    }
}

record VisualRelationshipRegionOwnership(String relationshipId, String regionId) {
    VisualRelationshipRegionOwnership {
        relationshipId = VisualAnalysisValidation.localId(relationshipId, "relationshipId");
        regionId = VisualAnalysisValidation.localId(regionId, "regionId");
    }
}
