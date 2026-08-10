package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiScaleVisualViewPlannerTest {
    @Test
    void plansOneOverviewAndBoundedDetailTilesWithReversibleCoordinates() throws Exception {
        var source = source(2_800, 1_800, Color.BLUE);
        var planner = new MultiScaleVisualViewPlanner();

        var first = planner.plan(List.of(source), List.of());
        var second = planner.plan(List.of(source), List.of());

        assertEquals(first.descriptors(), second.descriptors());
        assertEquals(
                first.providerImages().stream().map(item -> item.artifactId()).toList(),
                second.providerImages().stream().map(item -> item.artifactId()).toList()
        );
        assertEquals(5, first.descriptors().size());
        assertEquals(VisualViewKind.OVERVIEW, first.descriptors().getFirst().kind());
        assertTrue(first.providerImages().stream().allMatch(item ->
                item.width() <= MultiScaleVisualViewPlanner.DETAIL_LONG_EDGE
                        && item.height() <= MultiScaleVisualViewPlanner.DETAIL_LONG_EDGE
        ));

        var tile = first.descriptors().stream()
                .filter(item -> item.kind() == VisualViewKind.TILE).findFirst().orElseThrow();
        var mapped = first.toOriginalEvidence(new VisualViewEvidence(
                tile.viewId(), new CandidateBoundingBox(0, 0, 10_000, 10_000)
        ));
        assertEquals(source.artifactId(), mapped.artifactId());
        assertEquals(tile.sourceBoundingBox(), mapped.boundingBox());
    }

    @Test
    void prioritizesExplicitTargetedCropsAndMapsThemBackToTheOriginalArtifact() throws Exception {
        var source = source(2_000, 1_000, Color.ORANGE);
        var target = new CandidateBoundingBox(2_500, 2_000, 7_500, 8_000);
        var plan = new MultiScaleVisualViewPlanner().plan(
                List.of(source), List.of(new VisualTargetCrop(0, target))
        );

        var descriptor = plan.descriptors().stream()
                .filter(item -> item.kind() == VisualViewKind.TARGETED_CROP)
                .findFirst().orElseThrow();
        var mapped = plan.toOriginalEvidence(new VisualViewEvidence(
                descriptor.viewId(), new CandidateBoundingBox(0, 0, 10_000, 10_000)
        ));

        assertEquals(target, mapped.boundingBox());
        assertThrows(IllegalArgumentException.class, () -> plan.toOriginalEvidence(
                new VisualViewEvidence("view-99-overview-00", target)
        ));
    }

    @Test
    void preservesTenDuplicateInputOrdinalsWithoutExceedingTheViewOrByteEnvelope() throws Exception {
        var source = source(640, 480, Color.GREEN);
        var sources = java.util.stream.IntStream.range(0, 10).mapToObj(ignored -> source).toList();
        var plan = new MultiScaleVisualViewPlanner().plan(sources, List.of());

        assertEquals(10, plan.descriptors().size());
        assertEquals(10, plan.descriptors().stream().map(VisualViewDescriptor::viewId).distinct().count());
        assertTrue(plan.providerImages().stream().mapToLong(item -> item.bytes().length).sum()
                <= MultiScaleVisualViewPlanner.MAX_TOTAL_VIEW_BYTES);
        assertThrows(IllegalArgumentException.class, () -> new MultiScaleVisualViewPlanner().plan(
                List.of(source), List.of(new VisualTargetCrop(1, new CandidateBoundingBox(0, 0, 100, 100)))
        ));
    }

    @Test
    void everyMappedSubBoxRemainsInsideItsOriginalViewTransform() throws Exception {
        var plan = new MultiScaleVisualViewPlanner().plan(
                List.of(source(2_800, 2_700, Color.MAGENTA)), List.of()
        );
        for (var descriptor : plan.descriptors()) {
            for (var offset = 0; offset < 2_000; offset += 317) {
                var relative = new CandidateBoundingBox(
                        offset, offset / 2, Math.min(10_000, offset + 1_500),
                        Math.min(10_000, offset / 2 + 2_000)
                );
                var mapped = plan.toOriginalEvidence(
                        new VisualViewEvidence(descriptor.viewId(), relative)
                ).boundingBox();
                var source = descriptor.sourceBoundingBox();
                assertTrue(mapped.left() >= source.left() && mapped.top() >= source.top());
                assertTrue(mapped.right() <= source.right() && mapped.bottom() <= source.bottom());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new VisualViewEvidence(
                plan.descriptors().getFirst().viewId(), new CandidateBoundingBox(-1, 0, 1, 1)
        ));
    }

    private static VisualSourceImage source(int width, int height, Color color) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        var bytes = output.toByteArray();
        return new VisualSourceImage(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                bytes, width, height
        );
    }
}
