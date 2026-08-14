package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
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

/** One-shot pre-result freezer for repository-owned canonical raw upload fixtures. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5P_FIXTURE_FREEZE", matches = "true")
class R5PairedProductViewFixtureFreezeGateTest {
    private static final List<String> CASE_IDS = List.of(
            "transit-board-v3", "restaurant-menu-v3", "hospital-schedule-v3",
            "transit-board-v5", "transit-board-v2", "invoice-lines-v3",
            "school-timetable-v4", "building-directory-v5");
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void freezesCanonicalSourceSizeRastersWithoutReadingQualityResults() throws Exception {
        var output = requiredOutputDirectory();
        var corpus = new LayeredVisualCorpus();
        var rasterizer = new VisualStageRasterizer();
        var summary = new ArrayList<Map<String, Object>>();
        for (var caseId : CASE_IDS) {
            var evaluationCase = corpus.require(caseId);
            var rendered = rasterizer.render(evaluationCase.renderCase());
            assertEquals(evaluationCase.renderIdentity(), "render-sha256:" + rendered.sha256());
            var fixture = output.resolve(caseId + ".png");
            Files.write(fixture, rendered.bytes(), StandardOpenOption.CREATE_NEW);
            summary.add(Map.of(
                    "caseId", caseId,
                    "partition", evaluationCase.partition().name(),
                    "caseIdentity", evaluationCase.caseIdentity(),
                    "rawFixtureSha256", rendered.sha256(),
                    "width", rendered.width(),
                    "height", rendered.height()));
        }
        Files.write(
                output.resolve("r5p-fixture-freeze-summary.json"),
                JSON.writeValueAsBytes(Map.of(
                        "contractVersion", "renderweave-r5p-fixture-freeze/1.0",
                        "selectionBasis", "CASE_DOMAIN_DIFFICULTY_FAILURE_SLICE_METADATA_ONLY",
                        "caseCount", CASE_IDS.size(),
                        "cases", summary,
                        "qualityResultsRead", 0,
                        "externalProviderUsage", Map.of(
                                "attempts", 0, "reservations", 0, "costMicrosCny", 0),
                        "apiKeyReads", 0,
                        "terminalCode", "R5P_RAW_FIXTURES_FROZEN")),
                StandardOpenOption.CREATE_NEW);
    }

    private static Path requiredOutputDirectory() throws Exception {
        var value = System.getenv("RENDERWEAVE_R5P_FIXTURE_FREEZE_DIR");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("RENDERWEAVE_R5P_FIXTURE_FREEZE_DIR is required");
        }
        var repository = repositoryRoot();
        var evidenceRoot = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        var output = Path.of(value).toAbsolutePath().normalize();
        if (!output.startsWith(evidenceRoot) || output.equals(evidenceRoot)
                || Files.isSymbolicLink(output)
                || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)
                || containsEntry(output)) {
            throw new IllegalArgumentException("R5P_FIXTURE_FREEZE_PATH_INVALID");
        }
        return output;
    }

    private static boolean containsEntry(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isPresent();
        }
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
