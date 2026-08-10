package cn.hbads.renderweave.inference.live;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

record VisualElementBindingPlan(
        String contractVersion,
        List<VisualElementBinding> bindings
) {
    static final String VERSION = "renderweave-visual-bindings/1.0";
    static final String VERSION_V2 = "renderweave-visual-bindings/2.0";

    VisualElementBindingPlan {
        if (!VERSION.equals(contractVersion) && !VERSION_V2.equals(contractVersion)) {
            throw new IllegalArgumentException("Unsupported visual binding contract");
        }
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (bindings.isEmpty() || bindings.size() > VisualAnalysisValidation.MAX_ELEMENTS) {
            throw new IllegalArgumentException("Visual binding plan must contain 1..128 bindings");
        }
        var elementIds = new HashSet<String>();
        for (var binding : bindings) {
            if (!elementIds.add(binding.elementId())) {
                throw new IllegalArgumentException("Every visual element may be bound only once");
            }
        }
    }

    void requireConsistentWith(VisualElementInventory inventory, VisualHierarchyPlan hierarchy) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(hierarchy, "hierarchy");
        var expectedSlots = new HashSet<String>();
        for (var element : inventory.elements()) {
            if (element.kind() == VisualElementKind.SLOT) expectedSlots.add(element.elementId());
        }
        var actualSlots = new HashSet<String>();
        var entityFields = new HashSet<String>();
        hierarchy.relationships().forEach(relationship -> entityFields.add(
                relationship.parentEntityId() + "\u0000" + relationship.fieldKey()
        ));
        for (var binding : bindings) {
            var element = inventory.requireElement(binding.elementId());
            if (element.kind() != VisualElementKind.SLOT) {
                throw new IllegalArgumentException("Only SLOT elements may be bound to fields");
            }
            hierarchy.requireEntity(binding.entityId());
            actualSlots.add(binding.elementId());
            if (!entityFields.add(binding.entityId() + "\u0000" + element.proposedKey())) {
                throw new IllegalArgumentException("Bound field and relationship keys must be unique per entity");
            }
        }
        if (!actualSlots.equals(expectedSlots)) {
            throw new IllegalArgumentException("Every SLOT element must be bound exactly once");
        }

        var coveredElements = new HashSet<>(actualSlots);
        hierarchy.entities().forEach(entity -> coveredElements.addAll(entity.supportingElementIds()));
        hierarchy.relationships().forEach(relationship ->
                coveredElements.addAll(relationship.supportingElementIds()));
        var allElements = inventory.elements().stream().map(VisualElement::elementId)
                .collect(java.util.stream.Collectors.toSet());
        if (!coveredElements.containsAll(allElements)) {
            throw new IllegalArgumentException("Every visual element must participate in the plan");
        }
    }
}

record VisualElementBinding(
        String elementId,
        String entityId
) {
    VisualElementBinding {
        elementId = VisualAnalysisValidation.localId(elementId, "elementId");
        entityId = VisualAnalysisValidation.localId(entityId, "entityId");
    }
}
