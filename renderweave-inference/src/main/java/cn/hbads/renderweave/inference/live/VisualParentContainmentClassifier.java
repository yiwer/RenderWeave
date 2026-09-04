package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.replay.InferenceRejectionEnvelope;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Payload-free structural provenance for a final parent-containment rejection. */
final class VisualParentContainmentClassifier {
    private VisualParentContainmentClassifier() { }

    static Classification classify(List<VisualRegion> regions) {
        if (regions == null) return Classification.unclassified();
        try {
            var byId = new HashMap<String, VisualRegion>();
            for (var region : regions) {
                if (region == null || byId.putIfAbsent(region.regionId(), region) != null) {
                    return Classification.unclassified();
                }
            }

            var failures = EnumSet.noneOf(FailureFamily.class);
            var recoverableLinks = 0;
            var containmentLinks = 0;
            for (var region : regions) {
                if (!invalidContainmentLink(region, byId)) continue;
                containmentLinks++;
                var candidates = regions.stream()
                        .filter(candidate -> compatibleParent(region, candidate))
                        .filter(candidate -> strictlyContains(candidate, region))
                        .filter(candidate -> !createsCycle(
                                region.regionId(), candidate.regionId(), byId
                        ))
                        .toList();
                var minimal = candidates.stream().filter(candidate ->
                        candidates.stream().noneMatch(other ->
                                !other.regionId().equals(candidate.regionId())
                                        && strictlyContains(candidate, other)
                        )).toList();
                if (minimal.size() == 1
                        || (minimal.isEmpty()
                        && uniqueContainingRootAncestor(region, byId) != null)) {
                    recoverableLinks++;
                    continue;
                }
                failures.add(failureFamily(region.kind(), minimal.size()));
            }
            if (containmentLinks == 0) return Classification.unclassified();
            if (failures.isEmpty() && recoverableLinks == containmentLinks) {
                return Classification.atomicRollback();
            }
            return Classification.known(failures);
        } catch (RuntimeException unexpected) {
            return Classification.unclassified();
        }
    }

    private static boolean invalidContainmentLink(
            VisualRegion region,
            HashMap<String, VisualRegion> byId
    ) {
        if (region.parentRegionId() == null) return false;
        var parent = byId.get(region.parentRegionId());
        if (parent == null || parent.regionId().equals(region.regionId())
                || region.kind() == VisualRegionKind.ROOT) {
            return false;
        }
        if (region.kind() == VisualRegionKind.ITEM
                && (parent.kind() != VisualRegionKind.REPEATED_GROUP
                || !Objects.equals(region.repeatGroupId(), parent.repeatGroupId()))) {
            return false;
        }
        return !contains(parent, region);
    }

    private static FailureFamily failureFamily(VisualRegionKind kind, int minimalCandidates) {
        if (kind == VisualRegionKind.ITEM) {
            return minimalCandidates == 0
                    ? FailureFamily.ITEM_ZERO_COMPATIBLE
                    : FailureFamily.ITEM_AMBIGUOUS_COMPATIBLE;
        }
        return minimalCandidates == 0
                ? FailureFamily.NON_ITEM_ZERO_COMPATIBLE
                : FailureFamily.NON_ITEM_AMBIGUOUS_COMPATIBLE;
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

    private static boolean createsCycle(
            String childId,
            String candidateId,
            HashMap<String, VisualRegion> byId
    ) {
        var current = byId.get(candidateId);
        var visited = new LinkedHashSet<String>();
        while (current != null) {
            if (current.regionId().equals(childId) || !visited.add(current.regionId())) {
                return true;
            }
            current = current.parentRegionId() == null
                    ? null : byId.get(current.parentRegionId());
        }
        return false;
    }

    private static boolean strictlyContains(VisualRegion outer, VisualRegion inner) {
        return contains(outer, inner) && !outer.evidence().getFirst().boundingBox().equals(
                inner.evidence().getFirst().boundingBox()
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

    private enum FailureFamily {
        ITEM_ZERO_COMPATIBLE(0),
        ITEM_AMBIGUOUS_COMPATIBLE(1),
        NON_ITEM_ZERO_COMPATIBLE(2),
        NON_ITEM_AMBIGUOUS_COMPATIBLE(3);

        private final int detailIndex;

        FailureFamily(int detailIndex) {
            this.detailIndex = detailIndex;
        }
    }

    record Classification(List<String> detailCodes, int detailCodeCount) {
        Classification {
            detailCodes = List.copyOf(detailCodes);
            if (detailCodes.isEmpty()
                    || detailCodeCount != detailCodes.size()
                    || !detailCodes.equals(InferenceRejectionEnvelope
                    .PARENT_CONTAINMENT_DETAIL_CODES.stream()
                    .filter(detailCodes::contains).toList())) {
                throw new IllegalArgumentException(
                        "Parent-containment classification must be canonical and bounded"
                );
            }
        }

        private static Classification known(EnumSet<FailureFamily> failures) {
            var details = new ArrayList<String>();
            for (var family : FailureFamily.values()) {
                if (failures.contains(family)) {
                    details.add(InferenceRejectionEnvelope
                            .PARENT_CONTAINMENT_DETAIL_CODES.get(family.detailIndex));
                }
            }
            return new Classification(details, details.size());
        }

        private static Classification atomicRollback() {
            return one(4);
        }

        private static Classification unclassified() {
            return one(5);
        }

        private static Classification one(int index) {
            var details = List.of(InferenceRejectionEnvelope
                    .PARENT_CONTAINMENT_DETAIL_CODES.get(index));
            return new Classification(details, 1);
        }
    }
}
