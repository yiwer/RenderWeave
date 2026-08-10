package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
}
