package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageRasterizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One-shot, pre-result freezer for the four fresh R5P2 repository rasters. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5P2_FIXTURE_FREEZE", matches = "true")
class R5P2RepositoryRasterFixtureFreezeGateTest {
    private static final List<FrozenCase> CASES = List.of(
            new FrozenCase("weather-forecast-v3",
                    "9decfce03b43ac52a832e905c48be2edf8eeaaf7c7d9d187207c0e754459c063"),
            new FrozenCase("warehouse-inventory-v2",
                    "4bdefd154a213d2b1f25a0a56c7adbaead30f021fa08211ca08f586eb0c8637e"),
            new FrozenCase("event-agenda-v4",
                    "03201bed0318fe5b556ca4f590dc5e3d2f6baea1b6d3539ad576c8a61a26a6d2"),
            new FrozenCase("product-catalog-v5",
                    "a3de37a98c6afc1e6493c0818ff1585dd1f538a4fc9d7ee9c18d5a7b7ca7e99b"));
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void freezesEachCanonicalRasterExactlyOnceWithoutLayeredGoldOrMetrics() throws Exception {
        var output = requiredEmptyOutputDirectory();
        var source = new VisualStageCorpus();
        var rasterizer = new VisualStageRasterizer();
        var summary = new ArrayList<Map<String, Object>>();
        for (var frozen : CASES) {
            var evaluationCase = source.require(frozen.caseId());
            var rendered = rasterizer.render(evaluationCase);
            assertEquals(frozen.rawSha256(), rendered.sha256(), frozen.caseId());
            Files.write(output.resolve(frozen.caseId() + ".png"), rendered.bytes(),
                    StandardOpenOption.CREATE_NEW);
            summary.add(Map.of(
                    "caseId", frozen.caseId(),
                    "rawFixtureSha256", rendered.sha256(),
                    "width", rendered.width(),
                    "height", rendered.height()));
        }
        Files.write(output.resolve("r5p2-fixture-freeze-summary.json"),
                JSON.writeValueAsBytes(Map.ofEntries(
                        Map.entry("contractVersion",
                                "renderweave-r5p2-repository-raster-fixture-freeze/1.0"),
                        Map.entry("sourceCorpusVersion", VisualStageCorpus.VERSION),
                        Map.entry("sourceScenesSha256", source.sourceSha256()),
                        Map.entry("rawRasterGenerationCount", CASES.size()),
                        Map.entry("cases", summary),
                        Map.entry("layeredCorpusConstructed", 0),
                        Map.entry("preFreezeGoldReads", 0),
                        Map.entry("preFreezeMetricReads", 0),
                        Map.entry("externalProviderUsage", Map.of(
                                "attempts", 0, "reservations", 0, "costMicrosCny", 0)),
                        Map.entry("apiKeyReads", 0),
                        Map.entry("terminalCode", "R5P2_RAW_FIXTURES_FROZEN"))),
                StandardOpenOption.CREATE_NEW);
    }

    private static Path requiredEmptyOutputDirectory() throws Exception {
        var configured = System.getenv("RENDERWEAVE_R5P2_FIXTURE_FREEZE_DIR");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("RENDERWEAVE_R5P2_FIXTURE_FREEZE_DIR is required");
        }
        var evidenceRoot = repositoryRoot().resolve(".sdlc/evidence").toAbsolutePath().normalize();
        var output = Path.of(configured).toAbsolutePath().normalize();
        if (!output.startsWith(evidenceRoot) || output.equals(evidenceRoot)
                || Files.isSymbolicLink(output)
                || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P2_FIXTURE_FREEZE_PATH_INVALID");
        }
        try (var entries = Files.list(output)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("R5P2_FIXTURE_FREEZE_PATH_NOT_EMPTY");
            }
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

    private record FrozenCase(String caseId, String rawSha256) { }
}
