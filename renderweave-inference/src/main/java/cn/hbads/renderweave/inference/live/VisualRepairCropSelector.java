package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Derives bounded retry crops only from plans that already passed strict local validation. */
final class VisualRepairCropSelector {
    private static final int MAX_TARGETS = 4;

    List<VisualTargetCrop> select(
            InferenceStage stage,
            List<String> retryProblemCodes,
            List<String> sourceArtifactIds,
            VisualElementInventory inventory,
            VisualGroundingPlan grounding,
            VisualHierarchyPlan hierarchy,
            VisualEntityRegionPlan entityRegions
    ) {
        Objects.requireNonNull(stage, "stage");
        retryProblemCodes = List.copyOf(Objects.requireNonNull(
                retryProblemCodes, "retryProblemCodes"
        ));
        sourceArtifactIds = List.copyOf(Objects.requireNonNull(
                sourceArtifactIds, "sourceArtifactIds"
        ));
        if (retryProblemCodes.isEmpty() || grounding == null || inventory == null) {
            return List.of();
        }

        final VisualElementKind selectedKind;
        final java.util.function.Predicate<String> relevantCode;
        if (stage == InferenceStage.HIERARCHY) {
            selectedKind = VisualElementKind.GROUP;
            relevantCode = code -> code.startsWith("VISUAL_HIERARCHY")
                    || code.startsWith("VISUAL_SEMANTIC_HIERARCHY");
        } else if (stage == InferenceStage.ELEMENT_BINDING) {
            selectedKind = VisualElementKind.SLOT;
            relevantCode = code -> code.startsWith("VISUAL_BINDING")
                    || code.startsWith("VISUAL_BINDINGS")
                    || code.startsWith("VISUAL_SEMANTIC_BINDING");
        } else {
            return List.of();
        }
        if (retryProblemCodes.stream().noneMatch(relevantCode)) {
            return List.of();
        }

        var keys = new LinkedHashSet<CropKey>();
        for (var element : inventory.elements()) {
            if (element.kind() != selectedKind) continue;
            for (var regionId : grounding.regionIdsForElement(element.elementId())) {
                var evidence = grounding.requireRegion(regionId).evidence().getFirst();
                var sourceOrdinal = sourceArtifactIds.indexOf(evidence.artifactId());
                if (sourceOrdinal < 0) {
                    throw new IllegalArgumentException(
                            "Verified repair crop references an unknown source artifact"
                    );
                }
                keys.add(new CropKey(sourceOrdinal, evidence.boundingBox()));
            }
        }
        var ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparingInt(CropKey::sourceOrdinal)
                .thenComparingInt(key -> key.boundingBox().top())
                .thenComparingInt(key -> key.boundingBox().left())
                .thenComparingInt(key -> key.boundingBox().bottom())
                .thenComparingInt(key -> key.boundingBox().right()));
        return ordered.stream().limit(MAX_TARGETS)
                .map(key -> new VisualTargetCrop(key.sourceOrdinal(), key.boundingBox()))
                .toList();
    }

    private record CropKey(int sourceOrdinal, CandidateBoundingBox boundingBox) { }
}
