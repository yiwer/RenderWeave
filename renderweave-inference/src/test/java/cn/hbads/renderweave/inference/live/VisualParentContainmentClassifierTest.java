package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.replay.InferenceRejectionEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VisualParentContainmentClassifierTest {
    private static final String ARTIFACT = "a".repeat(64);
    private static final String OTHER_ARTIFACT = "b".repeat(64);
    private static final String ITEM_ZERO = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(0);
    private static final String ITEM_AMBIGUOUS = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(1);
    private static final String NON_ITEM_ZERO = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(2);
    private static final String NON_ITEM_AMBIGUOUS = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(3);
    private static final String ATOMIC_ROLLBACK = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(4);
    private static final String UNCLASSIFIED = InferenceRejectionEnvelope
            .PARENT_CONTAINMENT_DETAIL_CODES.get(5);

    @Test
    void classifiesEveryFrozenFailureFamilyWithoutPayloadDetails() {
        assertClassification(itemZero(), ITEM_ZERO);
        assertClassification(itemAmbiguous(), ITEM_AMBIGUOUS);
        assertClassification(nonItemZero(), NON_ITEM_ZERO);
        assertClassification(nonItemAmbiguous(), NON_ITEM_AMBIGUOUS);
        assertClassification(atomicRollback(), ATOMIC_ROLLBACK);
        assertClassification(null, UNCLASSIFIED);
        assertClassification(List.of(root()), UNCLASSIFIED);
    }

    @Test
    void aggregatesKnownFamiliesInCanonicalAllowlistOrder() {
        var regions = new ArrayList<>(itemZero());
        regions.addAll(nonItemAmbiguous().stream()
                .filter(region -> !"root".equals(region.regionId()))
                .map(region -> copyWithPrefix(region, "second-"))
                .toList());

        var actual = VisualParentContainmentClassifier.classify(regions);

        assertEquals(List.of(ITEM_ZERO, NON_ITEM_AMBIGUOUS), actual.detailCodes());
        assertEquals(2, actual.detailCodeCount());
    }

    @Test
    void resultCannotCarryIdsCoordinatesOrArtifactPayload() {
        var marker = "secret-runtime-region-marker";
        var regions = atomicRollback().stream()
                .map(region -> "child".equals(region.regionId())
                        ? copy(region, marker, region.parentRegionId()) : region)
                .toList();

        var actual = VisualParentContainmentClassifier.classify(regions);

        assertEquals(List.of(ATOMIC_ROLLBACK), actual.detailCodes());
        assertFalse(actual.toString().contains(marker));
        assertFalse(actual.toString().contains(ARTIFACT));
        assertFalse(actual.toString().matches(".*\\b[0-9]{3,}\\b.*"));
    }

    private static void assertClassification(List<VisualRegion> regions, String expected) {
        var actual = VisualParentContainmentClassifier.classify(regions);
        assertEquals(List.of(expected), actual.detailCodes());
        assertEquals(1, actual.detailCodeCount());
    }

    private static List<VisualRegion> itemZero() {
        return List.of(
                root(),
                region("bad-group", "root", VisualRegionKind.REPEATED_GROUP,
                        VisualMultiplicity.MANY, "repeat", ARTIFACT, 500, 500, 2_000, 2_000),
                region("item", "bad-group", VisualRegionKind.ITEM,
                        VisualMultiplicity.ONE, "repeat", ARTIFACT, 8_000, 8_000, 9_000, 9_000)
        );
    }

    private static List<VisualRegion> itemAmbiguous() {
        return List.of(
                root(),
                region("bad-group", "root", VisualRegionKind.REPEATED_GROUP,
                        VisualMultiplicity.MANY, "repeat", ARTIFACT, 500, 500, 2_000, 2_000),
                region("candidate-a", "root", VisualRegionKind.REPEATED_GROUP,
                        VisualMultiplicity.MANY, "repeat", ARTIFACT,
                        7_000, 7_000, 9_000, 9_000),
                region("candidate-b", "root", VisualRegionKind.REPEATED_GROUP,
                        VisualMultiplicity.MANY, "repeat", ARTIFACT,
                        7_500, 6_500, 9_500, 8_500),
                region("item", "bad-group", VisualRegionKind.ITEM,
                        VisualMultiplicity.ONE, "repeat", ARTIFACT,
                        8_000, 7_500, 8_500, 8_000)
        );
    }

    private static List<VisualRegion> nonItemZero() {
        return List.of(
                root(),
                region("bad-group", "root", VisualRegionKind.GROUP,
                        VisualMultiplicity.ONE, null, ARTIFACT, 500, 500, 2_000, 2_000),
                region("child", "bad-group", VisualRegionKind.SECTION,
                        VisualMultiplicity.ONE, null, OTHER_ARTIFACT,
                        8_000, 8_000, 9_000, 9_000)
        );
    }

    private static List<VisualRegion> nonItemAmbiguous() {
        return List.of(
                root(),
                region("bad-group", "root", VisualRegionKind.GROUP,
                        VisualMultiplicity.ONE, null, ARTIFACT, 500, 500, 2_000, 2_000),
                region("candidate-a", "root", VisualRegionKind.GROUP,
                        VisualMultiplicity.ONE, null, ARTIFACT,
                        7_000, 7_000, 9_000, 9_000),
                region("candidate-b", "root", VisualRegionKind.SECTION,
                        VisualMultiplicity.ONE, null, ARTIFACT,
                        7_500, 6_500, 9_500, 8_500),
                region("child", "bad-group", VisualRegionKind.SECTION,
                        VisualMultiplicity.ONE, null, ARTIFACT,
                        8_000, 7_500, 8_500, 8_000)
        );
    }

    private static List<VisualRegion> atomicRollback() {
        return List.of(
                root(),
                region("bad-group", "root", VisualRegionKind.GROUP,
                        VisualMultiplicity.ONE, null, ARTIFACT, 500, 500, 2_000, 2_000),
                region("child", "bad-group", VisualRegionKind.SECTION,
                        VisualMultiplicity.ONE, null, ARTIFACT, 8_000, 8_000, 9_000, 9_000)
        );
    }

    private static VisualRegion root() {
        return region("root", null, VisualRegionKind.ROOT, VisualMultiplicity.ONE,
                null, ARTIFACT, 0, 0, 10_000, 10_000);
    }

    private static VisualRegion copyWithPrefix(VisualRegion source, String prefix) {
        return copy(
                source, prefix + source.regionId(),
                source.parentRegionId() == null ? null : prefix + source.parentRegionId()
        );
    }

    private static VisualRegion copy(
            VisualRegion source,
            String regionId,
            String parentRegionId
    ) {
        return new VisualRegion(
                regionId, parentRegionId, source.kind(), source.multiplicity(),
                source.readingOrder(), source.repeatGroupId(), source.evidence()
        );
    }

    private static VisualRegion region(
            String id,
            String parentId,
            VisualRegionKind kind,
            VisualMultiplicity multiplicity,
            String repeatGroupId,
            String artifactId,
            int left,
            int top,
            int right,
            int bottom
    ) {
        return new VisualRegion(
                id, parentId, kind, multiplicity, 0, repeatGroupId,
                List.of(CandidateEvidence.image(
                        artifactId, new CandidateBoundingBox(left, top, right, bottom)
                ))
        );
    }
}
