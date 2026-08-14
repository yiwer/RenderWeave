package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageRasterizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R5ProductRasterTransformTest {
    @Test
    void cropsAndUpscalesOnlyTheNormalizedRasterWithStableSourceProjection() {
        var rendered = new VisualStageRasterizer().render(
                new LayeredVisualCorpus().require("transit-board-v3").renderCase());
        var source = new VisualSourceImage(
                rendered.sha256(), rendered.bytes(), rendered.width(), rendered.height());
        var basePlan = new MultiScaleVisualViewPlanner().plan(List.of(source), List.of());
        var baseView = basePlan.require("view-00-overview-00");
        var transform = new R5ProductRasterTransform();
        var box = new CandidateBoundingBox(200, 2900, 9800, 9800);

        var first = transform.render(source, baseView, box, 0, 2_400);
        var second = transform.render(source, baseView, box, 0, 2_400);

        assertEquals(2_400, Math.max(first.width(), first.height()));
        assertTrue(first.width() <= 2_400 && first.height() <= 2_400);
        assertEquals(new CandidateBoundingBox(195, 2890, 9805, 9805), first.sourceBoundingBox());
        assertEquals(first.identity(), second.identity());
        assertArrayEquals(first.bytes(), second.bytes());
        assertFalse(rendered.sha256().equals(first.artifactId()));
        assertTrue(first.identity().matches("renderweave-r5-product-raster-view/1\\.0:[0-9a-f]{64}"));
    }

    @Test
    void clipsContextMarginToTheNormalizedSourceBeforeScaling() {
        var rendered = new VisualStageRasterizer().render(
                new LayeredVisualCorpus().require("restaurant-menu-v3").renderCase());
        var source = new VisualSourceImage(
                rendered.sha256(), rendered.bytes(), rendered.width(), rendered.height());
        var baseView = new MultiScaleVisualViewPlanner().plan(List.of(source), List.of())
                .require("view-00-overview-00");

        var result = new R5ProductRasterTransform().render(
                source, baseView, new CandidateBoundingBox(100, 100, 9900, 9900), 500, 1_400);

        assertEquals(new CandidateBoundingBox(0, 0, 10_000, 10_000), result.sourceBoundingBox());
        assertEquals(1_400, Math.max(result.width(), result.height()));
    }
}
