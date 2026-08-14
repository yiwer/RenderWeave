package cn.hbads.renderweave.inference.live;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductViewHarnessTest {
    @Test
    void normalizesThroughTheProductBlobBoundaryAndAcquiresTheCompleteStaticPlan()
            throws Exception {
        var raw = selfDescribingRaster("jpeg");
        var rawDigest = sha256(raw);
        var fixture = new ProductViewHarness.RawRasterFixture(
                "coordinate-grid-v1", "coordinate-grid.jpg", "image/jpeg", raw);
        var observedMediaTypes = new ArrayList<String>();

        var outcome = new ProductViewHarness().acquireCompleteStaticPlan(
                List.of(fixture), views -> {
                    observedMediaTypes.addAll(views.stream()
                            .map(ProductViewHarness.PlannedView::mediaType).toList());
                    return java.util.stream.IntStream.range(0, views.size())
                            .mapToObj(index -> ProductViewHarness.AcquisitionArtifact.observed(
                                    index, views.get(index)))
                            .toList();
                });

        assertEquals("R5P_HARNESS_CONFORMANT", outcome.terminalCode());
        assertEquals(1, outcome.normalizationProvenance().size());
        assertEquals(rawDigest, outcome.normalizationProvenance().getFirst().rawFixtureSha256());
        assertNotEquals(rawDigest,
                outcome.normalizationProvenance().getFirst().normalizedArtifactId());
        assertEquals(1, outcome.blobWrites());
        assertEquals(1, outcome.blobReads());
        assertTrue(outcome.plannedViewCount() > 1, "fixture must exercise overview plus tiles");
        assertEquals(outcome.plannedViewCount(), outcome.acquiredViewCount());
        assertTrue(observedMediaTypes.stream().allMatch("image/png"::equals));
        assertEquals(0, outcome.externalProviderUsage().attempts());
        assertEquals(0, outcome.apiKeyReads());
        assertTrue(outcome.toString().contains("payload=<redacted>"));
    }

    @Test
    void rejectsIncompleteReorderedReplacedOrFabricatedAcquisitionTraces() throws Exception {
        var fixture = new ProductViewHarness.RawRasterFixture(
                "coordinate-grid-v1", "coordinate-grid.png", "image/png",
                selfDescribingRaster("png"));
        var harness = new ProductViewHarness();

        assertCode("R5P_PLAN_ACQUISITION_COVERAGE_INVALID", () ->
                harness.acquireCompleteStaticPlan(List.of(fixture), views ->
                        List.of(ProductViewHarness.AcquisitionArtifact.observed(0, views.getFirst()))));
        assertCode("R5P_PLAN_ACQUISITION_ORDER_INVALID", () ->
                harness.acquireCompleteStaticPlan(List.of(fixture), views -> {
                    var result = exactTrace(views);
                    var first = result.get(0);
                    result.set(0, result.get(1));
                    result.set(1, first);
                    return result;
                }));
        assertCode("R5P_PLAN_ACQUISITION_BYTES_INVALID", () ->
                harness.acquireCompleteStaticPlan(List.of(fixture), views -> {
                    var result = exactTrace(views);
                    var original = result.get(1);
                    result.set(1, new ProductViewHarness.AcquisitionArtifact(
                            original.acquisitionOrdinal(), original.viewId(), "0".repeat(64),
                            original.width(), original.height(), original.encodedBytes(),
                            original.encodedSha256()));
                    return result;
                }));
        assertCode("R5P_PLAN_ACQUISITION_DIMENSIONS_INVALID", () ->
                harness.acquireCompleteStaticPlan(List.of(fixture), views -> {
                    var result = exactTrace(views);
                    var original = result.get(1);
                    result.set(1, new ProductViewHarness.AcquisitionArtifact(
                            original.acquisitionOrdinal(), original.viewId(),
                            original.providerArtifactId(), original.width() + 1, original.height(),
                            original.encodedBytes(), original.encodedSha256()));
                    return result;
                }));
        assertCode("R5P_PLAN_ACQUISITION_COVERAGE_INVALID", () ->
                harness.acquireCompleteStaticPlan(List.of(fixture), views -> {
                    var result = exactTrace(views);
                    result.add(ProductViewHarness.AcquisitionArtifact.observed(
                            result.size(), views.getLast()));
                    return result;
                }));
    }

    @Test
    void repeatsTheSameNormalizedPlanAndPayloadSafeTrace() throws Exception {
        var fixture = new ProductViewHarness.RawRasterFixture(
                "coordinate-grid-v1", "coordinate-grid.png", "image/png",
                selfDescribingRaster("png"));
        var harness = new ProductViewHarness();

        var first = harness.acquireCompleteStaticPlan(List.of(fixture),
                views -> List.copyOf(exactTrace(views)));
        var second = harness.acquireCompleteStaticPlan(List.of(fixture),
                views -> List.copyOf(exactTrace(views)));

        assertEquals(first.normalizationProvenance(), second.normalizationProvenance());
        assertEquals(first.staticPlanIdentity(), second.staticPlanIdentity());
        assertEquals(first.viewSummaries(), second.viewSummaries());
        assertEquals(first.acquisitionTrace(), second.acquisitionTrace());
        assertEquals(first.evidenceIdentity(), second.evidenceIdentity());
    }

    private static ArrayList<ProductViewHarness.AcquisitionArtifact> exactTrace(
            List<ProductViewHarness.PlannedView> views
    ) {
        var result = new ArrayList<ProductViewHarness.AcquisitionArtifact>();
        for (var index = 0; index < views.size(); index++) {
            result.add(ProductViewHarness.AcquisitionArtifact.observed(index, views.get(index)));
        }
        return result;
    }

    private static byte[] selfDescribingRaster(String format) throws Exception {
        var image = new BufferedImage(2_800, 1_800, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            var colors = List.of(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW);
            for (var row = 0; row < 2; row++) {
                for (var column = 0; column < 2; column++) {
                    graphics.setColor(colors.get(row * 2 + column));
                    graphics.fillRect(column * 1_400, row * 900, 1_400, 900);
                    graphics.setColor(Color.BLACK);
                    graphics.drawString("R" + row + "C" + column,
                            column * 1_400 + 40, row * 900 + 80);
                }
            }
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) throw new IllegalStateException("encoder missing");
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(IllegalArgumentException.class, action);
        assertEquals(code, failure.getMessage());
    }
}
