package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.eval.visual.quality.R5PairedProductViewAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit zero-Provider producer for the payload-safe R5P-02 conformance evidence. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5P_HARNESS", matches = "true")
class ProductViewHarnessGateTest {
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void writesTwoDeterministicCompletePlanRuns() throws Exception {
        var output = requiredOutput();
        var fixture = new ProductViewHarness.RawRasterFixture(
                "coordinate-grid-v1", "coordinate-grid.jpg", "image/jpeg", fixture());
        var harness = new ProductViewHarness();
        ProductViewHarness.StaticPlanAcquisition acquisition = views ->
                java.util.stream.IntStream.range(0, views.size())
                        .mapToObj(index -> ProductViewHarness.AcquisitionArtifact.observed(
                                index, views.get(index)))
                        .toList();
        var first = harness.acquireCompleteStaticPlan(List.of(fixture), acquisition);
        var second = harness.acquireCompleteStaticPlan(List.of(fixture), acquisition);
        assertEquals(first.normalizationProvenance(), second.normalizationProvenance());
        assertEquals(first.staticPlanIdentity(), second.staticPlanIdentity());
        assertEquals(first.acquisitionTrace(), second.acquisitionTrace());
        assertEquals(first.evidenceIdentity(), second.evidenceIdentity());
        var authority = R5PairedProductViewAuthority.load();
        var evidence = Map.of(
                "contractVersion", "renderweave-r5p-harness-evidence/1.0",
                "authorityIdentity", authority.authorityIdentity(),
                "runs", List.of(first, second),
                "externalProviderUsage", Map.of(
                        "attempts", 0, "reservations", 0, "costMicrosCny", 0),
                "apiKeyReads", 0,
                "terminalCode", "R5P_HARNESS_CONFORMANT");
        Files.write(output, JSON.writeValueAsBytes(evidence), StandardOpenOption.CREATE_NEW);
    }

    private static byte[] fixture() throws Exception {
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
        if (!ImageIO.write(image, "jpeg", output)) throw new IllegalStateException("JPEG unavailable");
        return output.toByteArray();
    }

    private static Path requiredOutput() throws Exception {
        var value = System.getenv("RENDERWEAVE_R5P_HARNESS_EVIDENCE");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("RENDERWEAVE_R5P_HARNESS_EVIDENCE is required");
        }
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        var output = Path.of(value).toAbsolutePath().normalize();
        var parent = output.getParent();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"r5p-harness-evidence.json".equals(output.getFileName().toString())
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P_HARNESS_EVIDENCE_PATH_INVALID");
        }
        return output;
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("renderweave-inference"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("REPOSITORY_ROOT_NOT_FOUND");
    }
}
