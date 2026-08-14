package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedVisualInspectionTest {
    @Test
    void composesRequiredOverviewsCanonicalInspectedViewsAndOptionalTiles() throws Exception {
        var prepared = prepared(2_800, 1_800, 1);
        var request = request(
                region("view-00-tile-01", new CandidateBoundingBox(1_000, 1_000, 8_000, 8_000),
                        BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                        BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400),
                region("view-00-overview-00", new CandidateBoundingBox(0, 0, 1_000, 1_000),
                        BoundedVisualInspection.MarginPreset.CONTEXT_0500_BPS,
                        BoundedVisualInspection.ResolutionPreset.INSPECT_LONG_EDGE_2400));
        var module = new BoundedVisualInspection();

        var first = module.inspect(
                prepared.artifactSet(), prepared.plan(), request,
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial());
        var second = module.inspect(
                prepared.artifactSet(), prepared.plan(), request,
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial());

        assertEquals(BoundedVisualInspection.Disposition.EXECUTED, first.disposition());
        assertEquals("R5P_INSPECTION_EXECUTED", first.reasonCode());
        assertEquals("renderweave-visual-view-plan/2.0", first.planVersion());
        assertEquals(0, first.externalProviderUsage().attempts());
        assertEquals(0, first.apiKeyReads());
        assertEquals(7, first.executionViews().size());
        assertEquals(VisualViewKind.OVERVIEW,
                first.executionViews().getFirst().descriptor().kind());
        assertEquals(VisualViewKind.TARGETED_CROP,
                first.executionViews().get(1).descriptor().kind());
        assertEquals(VisualViewKind.TARGETED_CROP,
                first.executionViews().get(2).descriptor().kind());
        assertTrue(first.executionViews().subList(3, first.executionViews().size()).stream()
                .allMatch(view -> view.descriptor().kind() == VisualViewKind.TILE));
        assertEquals(0, first.executionViews().get(1).descriptor().sourceBoundingBox().left());
        assertEquals(0, first.executionViews().get(1).descriptor().sourceBoundingBox().top());
        assertEquals(first.planIdentity(), second.planIdentity());
        assertEquals(first.requestIdentity(), second.requestIdentity());
        assertEquals(first.resourceSummary().totalViews(), second.resourceSummary().totalViews());
        assertEquals(first.resourceSummary().inspectedViews(),
                second.resourceSummary().inspectedViews());
        assertEquals(first.resourceSummary().totalEncodedBytes(),
                second.resourceSummary().totalEncodedBytes());
        assertEquals(first.resourceSummary().inspectedPixels(),
                second.resourceSummary().inspectedPixels());
        assertEquals(first.resourceSummary().additionalVisualTokens(),
                second.resourceSummary().additionalVisualTokens());
        for (var index = 0; index < first.executionViews().size(); index++) {
            assertEquals(
                    first.executionViews().get(index).providerImage().artifactId(),
                    second.executionViews().get(index).providerImage().artifactId());
            assertArrayEquals(
                    first.executionViews().get(index).providerImage().bytes(),
                    second.executionViews().get(index).providerImage().bytes());
        }
    }

    @Test
    void rejectsInvalidUnknownDuplicateAndConsumedRequestsBeforeTransform() throws Exception {
        var prepared = prepared(400, 300, 1);
        var calls = new AtomicInteger();
        var module = new BoundedVisualInspection((source, base, box, margin, edge) -> {
            calls.incrementAndGet();
            throw new AssertionError("transform must remain unreachable");
        }, System::nanoTime);
        var valid = region("view-00-overview-00", new CandidateBoundingBox(0, 0, 5_000, 5_000),
                BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400);

        assertRejected("R5P_INSPECTION_CONTRACT_UNSUPPORTED", module.inspect(
                prepared.artifactSet(), prepared.plan(),
                new BoundedVisualInspection.InspectionRequest("InspectionRequest/0.9", List.of(valid)),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial()));
        assertRejected("R5P_INSPECTION_REGION_INVALID", module.inspect(
                prepared.artifactSet(), prepared.plan(), request(region(
                        "view-00-overview-00", new CandidateBoundingBox(-1, 0, 10_000, 10_000),
                        BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                        BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400)),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial()));
        assertRejected("R5P_INSPECTION_BASE_VIEW_UNKNOWN", module.inspect(
                prepared.artifactSet(), prepared.plan(), request(region(
                        "view-99-overview-00", new CandidateBoundingBox(0, 0, 10_000, 10_000),
                        BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                        BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400)),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial()));
        assertRejected("R5P_INSPECTION_REGION_DUPLICATED", module.inspect(
                prepared.artifactSet(), prepared.plan(), request(valid, valid),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial()));
        assertExhausted("R5P_INSPECTION_ROUND_EXHAUSTED", module.inspect(
                prepared.artifactSet(), prepared.plan(), request(valid),
                BoundedVisualInspection.AdaptiveInspectionPolicy.consumed()));
        assertEquals(0, calls.get());
    }

    @Test
    void enforcesExactViewPixelAndVisualTokenBoundaries() throws Exception {
        var prepared = prepared(3_000, 3_000, 1);
        var outcome = new BoundedVisualInspection().inspect(
                prepared.artifactSet(), prepared.plan(),
                request(
                        region("view-00-overview-00",
                                new CandidateBoundingBox(0, 0, 10_000, 10_000),
                                BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                                BoundedVisualInspection.ResolutionPreset.INSPECT_LONG_EDGE_2400),
                        region("view-00-tile-00",
                                new CandidateBoundingBox(0, 0, 10_000, 10_000),
                                BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                                BoundedVisualInspection.ResolutionPreset.INSPECT_LONG_EDGE_2400)),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial());

        assertEquals(BoundedVisualInspection.Disposition.EXECUTED, outcome.disposition());
        assertEquals(10, outcome.resourceSummary().totalViews());
        assertEquals(2, outcome.resourceSummary().inspectedViews());
        assertEquals(11_520_000L, outcome.resourceSummary().inspectedPixels());
        assertEquals(11_254L, outcome.resourceSummary().additionalVisualTokens());
        assertTrue(outcome.resourceSummary().totalEncodedBytes() <= 30L * 1024L * 1024L);
        assertTrue(outcome.resourceSummary().localTransformMillis() <= 10_000L);
    }

    @Test
    void exhaustsViewByteTimeAndCheckedArithmeticLimitsWithoutPartialOutcome() throws Exception {
        var nineSources = prepared(32, 32, 9);
        var neverCalled = new AtomicInteger();
        var noTransform = new BoundedVisualInspection((source, base, box, margin, edge) -> {
            neverCalled.incrementAndGet();
            throw new AssertionError("transform must remain unreachable");
        }, System::nanoTime);
        var viewExhausted = noTransform.inspect(
                nineSources.artifactSet(), nineSources.plan(),
                request(
                        region("view-00-overview-00", new CandidateBoundingBox(0, 0, 10_000, 10_000),
                                BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                                BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400),
                        region("view-01-overview-00", new CandidateBoundingBox(0, 0, 10_000, 10_000),
                                BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                                BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400)),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial());
        assertExhausted("R5P_INSPECTION_VIEW_LIMIT_EXHAUSTED", viewExhausted);
        assertEquals(0, neverCalled.get());

        var prepared = prepared(64, 64, 1);
        var oversized = new byte[30 * 1024 * 1024 + 1];
        var byteModule = new BoundedVisualInspection((source, base, box, margin, edge) ->
                new R5ProductRasterTransform.RasterView(
                        "renderweave-r5-product-raster-view/1.0:" + "0".repeat(64),
                        "0".repeat(64), "image/png", source.artifactId(),
                        base.descriptor().sourceBoundingBox(), 1, 1, base.crop(), oversized),
                System::nanoTime);
        var byteExhausted = byteModule.inspect(
                prepared.artifactSet(), prepared.plan(), request(region(
                        "view-00-overview-00", new CandidateBoundingBox(0, 0, 10_000, 10_000),
                        BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                        BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400)),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial());
        assertExhausted("R5P_INSPECTION_BYTE_LIMIT_EXHAUSTED", byteExhausted);

        var timeout = new BoundedVisualInspection(
                new R5ProductRasterTransform()::render,
                sequence(0L, 10_000_000_001L));
        assertExhausted("R5P_INSPECTION_TRANSFORM_TIMEOUT", timeout.inspect(
                prepared.artifactSet(), prepared.plan(), request(region(
                        "view-00-overview-00", new CandidateBoundingBox(0, 0, 10_000, 10_000),
                        BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                        BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400)),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial()));

        var overflow = new BoundedVisualInspection(
                new R5ProductRasterTransform()::render,
                sequence(Long.MIN_VALUE, Long.MAX_VALUE));
        assertExhausted("R5P_INSPECTION_ARITHMETIC_EXHAUSTED", overflow.inspect(
                prepared.artifactSet(), prepared.plan(), request(region(
                        "view-00-overview-00", new CandidateBoundingBox(0, 0, 10_000, 10_000),
                        BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                        BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400)),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial()));
    }

    @Test
    void rejectsNonCanonicalPlanLineageAndKeepsOutcomePayloadSafe() throws Exception {
        var prepared = prepared(400, 300, 1);
        var other = prepared(401, 300, 1);
        var request = request(region(
                "view-00-overview-00", new CandidateBoundingBox(0, 0, 10_000, 10_000),
                BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400));

        assertRejected("R5P_INSPECTION_PLAN_LINEAGE_INVALID", new BoundedVisualInspection().inspect(
                prepared.artifactSet(), other.plan(), request,
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial()));

        var outcome = new BoundedVisualInspection().inspect(
                prepared.artifactSet(), prepared.plan(), request,
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial());
        assertEquals(BoundedVisualInspection.Disposition.EXECUTED, outcome.disposition());
        assertNotEquals(ProductViewHarness.staticPlanIdentity(prepared.plan()), outcome.planIdentity());
        assertTrue(outcome.toString().contains("payload=<redacted>"));
        assertTrue(!outcome.toString().contains("boundingBox"));
        assertTrue(!outcome.toString().contains("bytes="));
    }

    private static ProductViewHarness.PreparedProductView prepared(
            int width, int height, int sources
    ) throws Exception {
        var fixtures = new ArrayList<ProductViewHarness.RawRasterFixture>();
        for (var index = 0; index < sources; index++) {
            fixtures.add(new ProductViewHarness.RawRasterFixture(
                    "inspection-source-" + index,
                    "inspection-source-" + index + ".png",
                    "image/png",
                    raster(width, height, index)));
        }
        return new ProductViewHarness().prepare(fixtures);
    }

    private static byte[] raster(int width, int height, int salt) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color((salt * 37) % 255, (salt * 73) % 255, (salt * 109) % 255));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            graphics.drawString("SOURCE-" + salt, Math.min(8, width - 1), Math.min(20, height - 1));
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG encoder missing");
        return output.toByteArray();
    }

    private static BoundedVisualInspection.InspectionRequest request(
            BoundedVisualInspection.InspectionRegion... regions
    ) {
        return new BoundedVisualInspection.InspectionRequest(
                "InspectionRequest/1.0", List.of(regions));
    }

    private static BoundedVisualInspection.InspectionRegion region(
            String viewId,
            CandidateBoundingBox box,
            BoundedVisualInspection.MarginPreset margin,
            BoundedVisualInspection.ResolutionPreset resolution
    ) {
        return new BoundedVisualInspection.InspectionRegion(viewId, box, margin, resolution);
    }

    private static LongSupplier sequence(long... values) {
        var index = new AtomicInteger();
        return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
    }

    private static void assertRejected(String code, BoundedVisualInspection.InspectionOutcome outcome) {
        assertEquals(BoundedVisualInspection.Disposition.REJECTED, outcome.disposition());
        assertEquals(code, outcome.reasonCode());
        assertTrue(outcome.executionViews().isEmpty());
    }

    private static void assertExhausted(String code, BoundedVisualInspection.InspectionOutcome outcome) {
        assertEquals(BoundedVisualInspection.Disposition.EXHAUSTED, outcome.disposition());
        assertEquals(code, outcome.reasonCode());
        assertTrue(outcome.executionViews().isEmpty());
    }
}
