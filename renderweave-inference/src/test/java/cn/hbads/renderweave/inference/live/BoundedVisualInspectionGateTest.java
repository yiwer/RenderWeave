package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
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

/** Explicit zero-Provider A1 producer for the R5P-03 offline action core. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5P_ACTION_CORE", matches = "true")
class BoundedVisualInspectionGateTest {
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void writesDeterministicPayloadSafeActionCoreEvidence() throws Exception {
        var output = requiredOutput();
        var prepared = new ProductViewHarness().prepare(List.of(
                new ProductViewHarness.RawRasterFixture(
                        "action-core-grid-v1", "action-core-grid.png", "image/png", fixture())));
        var request = new BoundedVisualInspection.InspectionRequest(
                BoundedVisualInspection.REQUEST_VERSION,
                List.of(
                        new BoundedVisualInspection.InspectionRegion(
                                "view-00-tile-01",
                                new CandidateBoundingBox(1_000, 1_000, 8_000, 8_000),
                                BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                                BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400),
                        new BoundedVisualInspection.InspectionRegion(
                                "view-00-overview-00",
                                new CandidateBoundingBox(0, 0, 1_000, 1_000),
                                BoundedVisualInspection.MarginPreset.CONTEXT_0500_BPS,
                                BoundedVisualInspection.ResolutionPreset.INSPECT_LONG_EDGE_2400)));
        var module = new BoundedVisualInspection();
        var policy = BoundedVisualInspection.AdaptiveInspectionPolicy.initial();
        var first = module.inspect(prepared.artifactSet(), prepared.plan(), request, policy);
        var second = module.inspect(prepared.artifactSet(), prepared.plan(), request, policy);
        assertEquivalent(first, second);

        var duplicate = module.inspect(
                prepared.artifactSet(), prepared.plan(),
                new BoundedVisualInspection.InspectionRequest(
                        BoundedVisualInspection.REQUEST_VERSION,
                        List.of(request.regions().getFirst(), request.regions().getFirst())),
                policy);
        var unknown = module.inspect(
                prepared.artifactSet(), prepared.plan(),
                new BoundedVisualInspection.InspectionRequest(
                        BoundedVisualInspection.REQUEST_VERSION,
                        List.of(new BoundedVisualInspection.InspectionRegion(
                                "view-99-overview-00",
                                new CandidateBoundingBox(0, 0, 10_000, 10_000),
                                BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS,
                                BoundedVisualInspection.ResolutionPreset.DETAIL_LONG_EDGE_1400))),
                policy);
        var consumed = module.inspect(
                prepared.artifactSet(), prepared.plan(), request,
                BoundedVisualInspection.AdaptiveInspectionPolicy.consumed());

        var evidence = Map.ofEntries(
                Map.entry("contractVersion", "renderweave-r5p-action-core-evidence/1.0"),
                Map.entry("authorityIdentity",
                        R5PairedProductViewAuthority.load().authorityIdentity()),
                Map.entry("moduleVersion", BoundedVisualInspection.VERSION),
                Map.entry("policyIdentity", policy.identity()),
                Map.entry("basePlanIdentity", ProductViewHarness.staticPlanIdentity(prepared.plan())),
                Map.entry("runs", List.of(summary(first), summary(second))),
                Map.entry("negativeReasonCodes", List.of(
                        duplicate.reasonCode(), unknown.reasonCode(), consumed.reasonCode())),
                Map.entry("externalProviderUsage", Map.of(
                        "attempts", 0, "reservations", 0, "costMicrosCny", 0)),
                Map.entry("apiKeyReads", 0),
                Map.entry("terminalCode", "R5P_OFFLINE_ACTION_CORE_READY"));
        Files.write(output, JSON.writeValueAsBytes(evidence), StandardOpenOption.CREATE_NEW);
    }

    private static Map<String, Object> summary(
            BoundedVisualInspection.InspectionOutcome outcome
    ) {
        return Map.ofEntries(
                Map.entry("disposition", outcome.disposition().name()),
                Map.entry("reasonCode", outcome.reasonCode()),
                Map.entry("requestIdentity", outcome.requestIdentity()),
                Map.entry("policyIdentity", outcome.policyIdentity()),
                Map.entry("planVersion", outcome.planVersion()),
                Map.entry("planIdentity", outcome.planIdentity()),
                Map.entry("orderedKinds", outcome.executionViews().stream()
                        .map(view -> view.descriptor().kind().name()).toList()),
                Map.entry("resources", outcome.resourceSummary()),
                Map.entry("externalProviderUsage", outcome.externalProviderUsage()),
                Map.entry("apiKeyReads", outcome.apiKeyReads()));
    }

    private static void assertEquivalent(
            BoundedVisualInspection.InspectionOutcome first,
            BoundedVisualInspection.InspectionOutcome second
    ) {
        assertEquals(BoundedVisualInspection.Disposition.EXECUTED, first.disposition());
        assertEquals(first.disposition(), second.disposition());
        assertEquals(first.reasonCode(), second.reasonCode());
        assertEquals(first.requestIdentity(), second.requestIdentity());
        assertEquals(first.policyIdentity(), second.policyIdentity());
        assertEquals(first.planIdentity(), second.planIdentity());
        assertEquals(first.executionViews().stream()
                        .map(view -> view.descriptor().kind()).toList(),
                second.executionViews().stream()
                        .map(view -> view.descriptor().kind()).toList());
        assertEquals(first.resourceSummary().totalViews(),
                second.resourceSummary().totalViews());
        assertEquals(first.resourceSummary().totalEncodedBytes(),
                second.resourceSummary().totalEncodedBytes());
        assertEquals(first.resourceSummary().inspectedPixels(),
                second.resourceSummary().inspectedPixels());
        assertEquals(first.resourceSummary().additionalVisualTokens(),
                second.resourceSummary().additionalVisualTokens());
        assertEquals(0, first.externalProviderUsage().attempts());
        assertEquals(0, first.apiKeyReads());
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
                }
            }
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG unavailable");
        return output.toByteArray();
    }

    private static Path requiredOutput() throws Exception {
        var value = System.getenv("RENDERWEAVE_R5P_ACTION_CORE_EVIDENCE");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("RENDERWEAVE_R5P_ACTION_CORE_EVIDENCE is required");
        }
        var repository = repositoryRoot();
        var evidenceRoot = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        var output = Path.of(value).toAbsolutePath().normalize();
        var parent = output.getParent();
        if (!output.startsWith(evidenceRoot) || output.equals(evidenceRoot)
                || !"r5p-action-core-evidence.json".equals(output.getFileName().toString())
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P_ACTION_CORE_EVIDENCE_PATH_INVALID");
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
