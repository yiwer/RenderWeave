package cn.hbads.renderweave.inference.live;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

record VisualHierarchyPlan(
        String contractVersion,
        String rootEntityId,
        List<VisualEntityPlan> entities,
        List<VisualRelationshipPlan> relationships
) {
    static final String VERSION = "renderweave-visual-hierarchy/1.0";
    static final String VERSION_V2 = "renderweave-visual-hierarchy/2.0";

    VisualHierarchyPlan {
        if (!VERSION.equals(contractVersion) && !VERSION_V2.equals(contractVersion)) {
            throw new IllegalArgumentException("Unsupported visual hierarchy contract");
        }
        rootEntityId = VisualAnalysisValidation.localId(rootEntityId, "rootEntityId");
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relationships = List.copyOf(Objects.requireNonNull(relationships, "relationships"));
        if (entities.isEmpty() || entities.size() > VisualAnalysisValidation.MAX_ENTITIES) {
            throw new IllegalArgumentException("Visual hierarchy must contain 1..32 entities");
        }
        if (relationships.size() > VisualAnalysisValidation.MAX_RELATIONSHIPS) {
            throw new IllegalArgumentException("Visual hierarchy has too many relationships");
        }
        validateShape(rootEntityId, entities, relationships);
    }

    void requireConsistentWith(VisualElementInventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        var usedGroups = new HashSet<String>();
        for (var entity : entities) {
            for (var elementId : entity.supportingElementIds()) inventory.requireElement(elementId);
        }
        for (var relationship : relationships) {
            var hasMatchingGroup = false;
            for (var elementId : relationship.supportingElementIds()) {
                var element = inventory.requireElement(elementId);
                if (element.kind() != VisualElementKind.GROUP) {
                    throw new IllegalArgumentException("Relationships must be supported by GROUP elements");
                }
                if (!usedGroups.add(elementId)) {
                    throw new IllegalArgumentException("A GROUP element may support only one relationship");
                }
                if (element.multiplicity() == relationship.cardinality()) hasMatchingGroup = true;
            }
            if (!hasMatchingGroup) {
                throw new IllegalArgumentException("Relationship cardinality must match a supporting GROUP element");
            }
        }
    }

    VisualEntityPlan requireEntity(String entityId) {
        return entities.stream().filter(entity -> entity.entityId().equals(entityId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Visual binding references an unknown entity"));
    }

    private static void validateShape(
            String rootEntityId,
            List<VisualEntityPlan> entities,
            List<VisualRelationshipPlan> relationships
    ) {
        var byId = new HashMap<String, VisualEntityPlan>();
        var schemaKeys = new HashSet<String>();
        for (var entity : entities) {
            if (byId.putIfAbsent(entity.entityId(), entity) != null) {
                throw new IllegalArgumentException("Visual entity ids must be unique");
            }
            if (!schemaKeys.add(entity.schemaKey())) {
                throw new IllegalArgumentException("Visual entity schema keys must be unique");
            }
        }
        if (!byId.containsKey(rootEntityId)) throw new IllegalArgumentException("Visual hierarchy root is missing");

        var relationshipIds = new HashSet<String>();
        var parentFields = new HashSet<String>();
        var incoming = new HashMap<String, Integer>();
        var outgoing = new HashMap<String, List<String>>();
        for (var relationship : relationships) {
            if (!relationshipIds.add(relationship.relationshipId())) {
                throw new IllegalArgumentException("Visual relationship ids must be unique");
            }
            if (!byId.containsKey(relationship.parentEntityId())
                    || !byId.containsKey(relationship.childEntityId())
                    || relationship.parentEntityId().equals(relationship.childEntityId())) {
                throw new IllegalArgumentException("Visual relationship endpoints are invalid");
            }
            if (!parentFields.add(relationship.parentEntityId() + "\u0000" + relationship.fieldKey())) {
                throw new IllegalArgumentException("Relationship field keys must be unique per parent");
            }
            incoming.merge(relationship.childEntityId(), 1, Integer::sum);
            outgoing.computeIfAbsent(relationship.parentEntityId(), ignored -> new ArrayList<>())
                    .add(relationship.childEntityId());
        }
        if (incoming.getOrDefault(rootEntityId, 0) != 0) {
            throw new IllegalArgumentException("Visual hierarchy root cannot have a parent");
        }
        for (var entity : entities) {
            if (!entity.entityId().equals(rootEntityId)
                    && incoming.getOrDefault(entity.entityId(), 0) != 1) {
                throw new IllegalArgumentException("Every non-root visual entity must have exactly one parent");
            }
        }

        var visited = new HashSet<String>();
        var queue = new ArrayDeque<NodeDepth>();
        queue.add(new NodeDepth(rootEntityId, 1));
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (current.depth() > VisualAnalysisValidation.MAX_TREE_DEPTH) {
                throw new IllegalArgumentException("Visual hierarchy exceeds depth 16");
            }
            if (!visited.add(current.entityId())) throw new IllegalArgumentException("Visual hierarchy contains a cycle");
            for (var child : outgoing.getOrDefault(current.entityId(), List.of())) {
                queue.addLast(new NodeDepth(child, current.depth() + 1));
            }
        }
        if (visited.size() != entities.size()) throw new IllegalArgumentException("Visual hierarchy contains an orphan");
    }

    private record NodeDepth(String entityId, int depth) { }
}

record VisualEntityPlan(
        String entityId,
        String schemaKey,
        String displayName,
        List<String> supportingElementIds
) {
    VisualEntityPlan {
        entityId = VisualAnalysisValidation.localId(entityId, "entityId");
        schemaKey = VisualAnalysisValidation.schemaKey(schemaKey);
        displayName = VisualAnalysisValidation.displayName(displayName, "displayName");
        supportingElementIds = VisualAnalysisValidation.localIds(
                supportingElementIds, "supportingElementIds", 32
        );
    }
}

record VisualRelationshipPlan(
        String relationshipId,
        String parentEntityId,
        String childEntityId,
        String fieldKey,
        String displayName,
        VisualMultiplicity cardinality,
        List<String> supportingElementIds
) {
    VisualRelationshipPlan {
        relationshipId = VisualAnalysisValidation.localId(relationshipId, "relationshipId");
        parentEntityId = VisualAnalysisValidation.localId(parentEntityId, "parentEntityId");
        childEntityId = VisualAnalysisValidation.localId(childEntityId, "childEntityId");
        fieldKey = VisualAnalysisValidation.fieldKey(fieldKey);
        displayName = VisualAnalysisValidation.displayName(displayName, "displayName");
        Objects.requireNonNull(cardinality, "cardinality");
        supportingElementIds = VisualAnalysisValidation.localIds(
                supportingElementIds, "supportingElementIds", 16
        );
    }
}

