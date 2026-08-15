package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R5P2SourceLineReconciliationTest {
    private static final String SOURCE = "a".repeat(64);

    @Test
    void locksPolicyIdentityAndExactThresholdBoundaries() {
        assertEquals(
                "FrozenSourceLineReconciliationPolicy/1.0:"
                        + "eead9287d942693156500a090daf5da5c2f9dafe4f6564ee642ac406c0f49443",
                R5P2SourceLineReconciliation.POLICY_IDENTITY);
        assertFalse(R5P2SourceLineReconciliation.areaThresholdAllows(4_999, 10_000));
        assertTrue(R5P2SourceLineReconciliation.areaThresholdAllows(5_000, 10_000));
        assertFalse(R5P2SourceLineReconciliation.verticalThresholdAllows(7_999, 10_000));
        assertTrue(R5P2SourceLineReconciliation.verticalThresholdAllows(8_000, 10_000));
    }

    @Test
    void replaysSharedJavaPythonGolden() throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/visual-eval/r5p2/source-line-reconciliation-golden-v1.json")) {
            var golden = JsonMapper.builder().build().readValue(input, GoldenDocument.class);
            assertEquals(R5P2SourceLineReconciliation.POLICY_IDENTITY, golden.policyIdentity());
            for (var threshold : golden.thresholds()) {
                var actual = switch (threshold.kind()) {
                    case "area" -> R5P2SourceLineReconciliation.areaThresholdAllows(
                            threshold.numerator(), threshold.denominator());
                    case "vertical" -> R5P2SourceLineReconciliation.verticalThresholdAllows(
                            threshold.numerator(), threshold.denominator());
                    default -> throw new IllegalArgumentException("unknown golden threshold");
                };
                assertEquals(threshold.expected(), actual, threshold.thresholdId());
            }
            for (var projection : golden.projections()) {
                assertEquals(projection.expectedSourceBox(), projection.project().sourceBox(),
                        projection.projectionId());
                assertEquals(projection.expectedDensity(), projection.project().pixelDensity(),
                        projection.projectionId());
            }
            for (var pair : golden.representativePairs()) {
                assertEquals(pair.expected(), R5P2SourceLineReconciliation.prefersRepresentative(
                        pair.candidate().toProjectedLine(), pair.existing().toProjectedLine()),
                        pair.pairId());
            }
            for (var invalidText : golden.invalidTexts()) {
                assertThrows(IllegalArgumentException.class, () -> line(
                        invalidText.caseId(), 0, 0,
                        new R5P2SourceLineReconciliation.SourceBox(0, 0, 10, 10),
                        8_000, invalidText.text()), invalidText.caseId());
            }
            for (var testCase : golden.cases()) {
                var lines = testCase.lines().stream().map(GoldenLine::toProjectedLine).toList();
                assertEquals(testCase.expectedRepresentativeIds(),
                        R5P2SourceLineReconciliation.reconcile(lines).representatives().stream()
                                .map(R5P2SourceLineReconciliation.ProjectedLine::observationId)
                                .toList(), testCase.caseId());
            }
        }
    }

    @Test
    void projectsToCanonicalSourceAndPrefersTheHighestExactPixelDensity() {
        var lowerDensity = R5P2SourceLineReconciliation.project(
                "overview", SOURCE, 0, 7, 200, 200, 1_000, 1_000,
                new R5P2SourceLineReconciliation.PixelBox(100, 200, 300, 400),
                new R5P2SourceLineReconciliation.PixelBox(0, 0, 100, 100),
                9_900, "route A");
        var higherDensity = R5P2SourceLineReconciliation.project(
                "crop", SOURCE, 1, 3, 400, 400, 1_000, 1_000,
                new R5P2SourceLineReconciliation.PixelBox(100, 200, 300, 400),
                new R5P2SourceLineReconciliation.PixelBox(0, 0, 200, 200),
                8_000, "route variant");

        var outcome = R5P2SourceLineReconciliation.reconcile(List.of(lowerDensity, higherDensity));

        assertEquals(new R5P2SourceLineReconciliation.SourceBox(1_000, 2_000, 2_000, 3_000),
                lowerDensity.sourceBox());
        assertEquals(List.of("crop"), outcome.representatives().stream()
                .map(R5P2SourceLineReconciliation.ProjectedLine::observationId).toList());
        assertEquals("route variant", outcome.representatives().getFirst().text());
        assertTrue(outcome.policyIdentity().startsWith("FrozenSourceLineReconciliationPolicy/1.0:"));
    }

    @Test
    void enforcesAreaVerticalCenterCrossViewAndCompleteLinkBoundaries() {
        var areaPassLeft = line("area-left", 0, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 100, 100), 9_000);
        var areaPassRight = line("area-right", 1, 0,
                new R5P2SourceLineReconciliation.SourceBox(50, 0, 150, 200), 8_000);
        assertTrue(R5P2SourceLineReconciliation.sameSourceLineCandidate(areaPassLeft, areaPassRight));

        var leftEdgeClosed = line("left-edge-closed", 1, 0,
                new R5P2SourceLineReconciliation.SourceBox(40, 0, 60, 100), 8_000);
        var rightEdgeOpen = line("right-edge-open", 1, 0,
                new R5P2SourceLineReconciliation.SourceBox(140, 0, 160, 100), 8_000);
        var centerReference = line("center-reference", 0, 0,
                new R5P2SourceLineReconciliation.SourceBox(50, 0, 150, 100), 9_000);
        assertTrue(R5P2SourceLineReconciliation.sameSourceLineCandidate(
                centerReference, leftEdgeClosed));
        assertFalse(R5P2SourceLineReconciliation.sameSourceLineCandidate(
                centerReference, rightEdgeOpen));

        var verticalBelow8000 = line("vertical-below-8000", 1, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 1_001, 100, 6_001), 8_000);
        var vertical8000 = line("vertical-8000", 1, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 1_000, 100, 6_000), 8_000);
        var verticalReference = line("vertical-reference", 0, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 100, 5_000), 9_000);
        assertFalse(R5P2SourceLineReconciliation.sameSourceLineCandidate(
                verticalReference, verticalBelow8000));
        assertTrue(R5P2SourceLineReconciliation.sameSourceLineCandidate(
                verticalReference, vertical8000));

        var sameView = line("same-view", 0, 1, areaPassLeft.sourceBox(), 9_100);
        assertFalse(R5P2SourceLineReconciliation.sameSourceLineCandidate(areaPassLeft, sameView));
        var otherSource = new R5P2SourceLineReconciliation.ProjectedLine(
                "other-source", "b".repeat(64), areaPassLeft.sourceBox(), 9_100,
                "other source", 1, 1, new R5P2SourceLineReconciliation.PixelDensity(1, 1));
        assertFalse(R5P2SourceLineReconciliation.sameSourceLineCandidate(
                areaPassLeft, otherSource));

        var a = line("a", 0, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 100, 100), 9_000);
        var b = line("b", 1, 0,
                new R5P2SourceLineReconciliation.SourceBox(20, 0, 120, 100), 8_000);
        var c = line("c", 2, 0,
                new R5P2SourceLineReconciliation.SourceBox(60, 0, 160, 100), 7_000);
        var completeLink = R5P2SourceLineReconciliation.reconcile(List.of(c, b, a));
        assertEquals(2, completeLink.representatives().size());
        assertEquals(List.of("a", "c"), completeLink.representatives().stream()
                .map(R5P2SourceLineReconciliation.ProjectedLine::observationId).toList());
    }

    @Test
    void keepsRepeatedTextAtDistinctSourcePositionsAndNeverSynthesizesText() {
        var first = line("first", 0, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 100, 100), 7_000, "重复");
        var firstVariant = line("first-variant", 1, 0,
                new R5P2SourceLineReconciliation.SourceBox(1, 1, 101, 101), 9_000, "重 复");
        var second = line("second", 1, 1,
                new R5P2SourceLineReconciliation.SourceBox(0, 200, 100, 300), 8_000, "重复");

        var outcome = R5P2SourceLineReconciliation.reconcile(List.of(second, firstVariant, first));

        assertEquals(List.of("first-variant", "second"), outcome.representatives().stream()
                .map(R5P2SourceLineReconciliation.ProjectedLine::observationId).toList());
        assertEquals(List.of("重 复", "重复"), outcome.representatives().stream()
                .map(R5P2SourceLineReconciliation.ProjectedLine::text).toList());
    }

    @Test
    void freezesEveryRepresentativeTieBreakWithOverflowSafeRationals() {
        var existing = detailedLine("existing", 4, 9,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 100, 100),
                8_000, new R5P2SourceLineReconciliation.PixelDensity(
                        Long.MAX_VALUE - 1, Long.MAX_VALUE));
        var density = detailedLine("density", 5, 8, existing.sourceBox(), 1,
                new R5P2SourceLineReconciliation.PixelDensity(
                        Long.MAX_VALUE, Long.MAX_VALUE - 1));
        assertTrue(R5P2SourceLineReconciliation.prefersRepresentative(density, existing));

        var confidence = detailedLine("confidence", 5, 8, existing.sourceBox(), 8_001,
                existing.pixelDensity());
        assertTrue(R5P2SourceLineReconciliation.prefersRepresentative(confidence, existing));

        var smaller = detailedLine("smaller", 5, 8,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 99, 100),
                existing.confidenceBps(), existing.pixelDensity());
        assertTrue(R5P2SourceLineReconciliation.prefersRepresentative(smaller, existing));

        var lowerView = detailedLine("lower-view", 3, 99, existing.sourceBox(),
                existing.confidenceBps(), existing.pixelDensity());
        assertTrue(R5P2SourceLineReconciliation.prefersRepresentative(lowerView, existing));

        var lowerLine = detailedLine("lower-line", 4, 8, existing.sourceBox(),
                existing.confidenceBps(), existing.pixelDensity());
        assertTrue(R5P2SourceLineReconciliation.prefersRepresentative(lowerLine, existing));
    }

    @Test
    void validatesCanonicalTextAndUsesUnicodeScalarStableOrder() {
        assertThrows(IllegalArgumentException.class, () -> line(
                "nfc", 0, 0, new R5P2SourceLineReconciliation.SourceBox(0, 0, 10, 10),
                8_000, "e\u0301"));
        assertThrows(IllegalArgumentException.class, () -> line(
                "ascii-space", 0, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 10, 10),
                8_000, "two  spaces"));
        assertThrows(IllegalArgumentException.class, () -> line(
                "unicode-space", 0, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 10, 10),
                8_000, "non\u00a0breaking"));
        assertThrows(IllegalArgumentException.class, () -> line(
                "iso-control", 0, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 10, 10),
                8_000, "line\nfeed"));

        var supplementary = line("supplementary", 0, 1,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 10, 10), 8_000,
                "\ud800\udc00");
        var privateUse = line("private-use", 0, 0,
                new R5P2SourceLineReconciliation.SourceBox(0, 0, 10, 10), 8_000,
                "\ue000");
        assertEquals(List.of("private-use", "supplementary"),
                R5P2SourceLineReconciliation.reconcile(List.of(supplementary, privateUse))
                        .representatives().stream()
                        .map(R5P2SourceLineReconciliation.ProjectedLine::observationId).toList());
    }

    private static R5P2SourceLineReconciliation.ProjectedLine line(
            String id, int view, int ordinal, R5P2SourceLineReconciliation.SourceBox box,
            int confidence) {
        return line(id, view, ordinal, box, confidence, id);
    }

    private static R5P2SourceLineReconciliation.ProjectedLine line(
            String id, int view, int ordinal, R5P2SourceLineReconciliation.SourceBox box,
            int confidence, String text) {
        return new R5P2SourceLineReconciliation.ProjectedLine(
                id, SOURCE, box, confidence, text, view, ordinal,
                new R5P2SourceLineReconciliation.PixelDensity(1, 1));
    }

    private static R5P2SourceLineReconciliation.ProjectedLine detailedLine(
            String id,
            int view,
            int ordinal,
            R5P2SourceLineReconciliation.SourceBox box,
            int confidence,
            R5P2SourceLineReconciliation.PixelDensity density
    ) {
        return new R5P2SourceLineReconciliation.ProjectedLine(
                id, SOURCE, box, confidence, id, view, ordinal, density);
    }

    private record GoldenDocument(
            String goldenVersion,
            String policyIdentity,
            List<GoldenThreshold> thresholds,
            List<GoldenProjection> projections,
            List<GoldenRepresentativePair> representativePairs,
            List<GoldenInvalidText> invalidTexts,
            List<GoldenCase> cases
    ) { }

    private record GoldenThreshold(
            String thresholdId,
            String kind,
            long numerator,
            long denominator,
            boolean expected
    ) { }

    private record GoldenProjection(
            String projectionId,
            String observationId,
            String sourceArtifactId,
            int viewOrdinal,
            int lineOrdinal,
            int viewWidth,
            int viewHeight,
            int sourceWidth,
            int sourceHeight,
            List<Integer> sourceCrop,
            List<Integer> viewLine,
            int confidenceBps,
            String text,
            List<Integer> expectedBox,
            List<Long> expectedPixelDensity
    ) {
        R5P2SourceLineReconciliation.ProjectedLine project() {
            return R5P2SourceLineReconciliation.project(
                    observationId, sourceArtifactId, viewOrdinal, lineOrdinal,
                    viewWidth, viewHeight, sourceWidth, sourceHeight,
                    pixelBox(sourceCrop), pixelBox(viewLine), confidenceBps, text);
        }

        R5P2SourceLineReconciliation.SourceBox expectedSourceBox() {
            return new R5P2SourceLineReconciliation.SourceBox(
                    expectedBox.get(0), expectedBox.get(1),
                    expectedBox.get(2), expectedBox.get(3));
        }

        R5P2SourceLineReconciliation.PixelDensity expectedDensity() {
            return new R5P2SourceLineReconciliation.PixelDensity(
                    expectedPixelDensity.get(0), expectedPixelDensity.get(1));
        }

        private static R5P2SourceLineReconciliation.PixelBox pixelBox(List<Integer> box) {
            return new R5P2SourceLineReconciliation.PixelBox(
                    box.get(0), box.get(1), box.get(2), box.get(3));
        }
    }

    private record GoldenCase(
            String caseId,
            List<GoldenLine> lines,
            List<String> expectedRepresentativeIds
    ) { }

    private record GoldenRepresentativePair(
            String pairId,
            GoldenLine candidate,
            GoldenLine existing,
            boolean expected
    ) { }

    private record GoldenInvalidText(String caseId, String text) { }

    private record GoldenLine(
            String observationId,
            String sourceArtifactId,
            List<Integer> box,
            int confidenceBps,
            String text,
            int viewOrdinal,
            int lineOrdinal,
            List<Long> density
    ) {
        R5P2SourceLineReconciliation.ProjectedLine toProjectedLine() {
            return new R5P2SourceLineReconciliation.ProjectedLine(
                    observationId,
                    sourceArtifactId,
                    new R5P2SourceLineReconciliation.SourceBox(
                            box.get(0), box.get(1), box.get(2), box.get(3)),
                    confidenceBps,
                    text,
                    viewOrdinal,
                    lineOrdinal,
                    new R5P2SourceLineReconciliation.PixelDensity(
                            density.get(0), density.get(1)));
        }
    }
}
