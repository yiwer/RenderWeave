package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit zero-result A1 producer for the frozen R5P assignment. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5P_ASSIGNMENT_GATE", matches = "true")
class R5PairedProductViewAssignmentGateTest {
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void writesPayloadSafePreResultAssignmentEvidence() throws Exception {
        var output = requiredOutput();
        var first = R5PairedProductViewAssignment.load();
        var second = R5PairedProductViewAssignment.load();
        assertEquals(first.identity(), second.identity());
        assertEquals(first.evaluationIdentity(), second.evaluationIdentity());
        assertEquals(first.cases(), second.cases());
        assertEquals("R5P_ASSIGNMENT_FROZEN", first.terminalCode());
        assertEquals(0, first.externalProviderUsage().attempts());
        assertEquals(0, first.apiKeyReads());

        var evidence = Map.ofEntries(
                Map.entry("contractVersion", "renderweave-r5p-assignment-evidence/1.0"),
                Map.entry("assignmentIdentity", first.identity()),
                Map.entry("evaluationIdentity", first.evaluationIdentity()),
                Map.entry("selectionBasis",
                        "CASE_DOMAIN_DIFFICULTY_FAILURE_SLICE_METADATA_ONLY"),
                Map.entry("seenUsage", "VETO_ONLY_NO_CONFIRMATION_NO_HOLDOUT_NO_AC021"),
                Map.entry("confirmationUsage", "SEALED_CONFIRMATION_ONLY_NO_AC021"),
                Map.entry("cases", first.cases().stream().map(item -> Map.ofEntries(
                        Map.entry("caseId", item.caseId()),
                        Map.entry("cohort", item.cohort().name()),
                        Map.entry("sourcePartition", item.sourcePartition().name()),
                        Map.entry("caseIdentity", item.caseIdentity()),
                        Map.entry("rawFixtureSha256", item.rawFixtureSha256()),
                        Map.entry("width", item.width()),
                        Map.entry("height", item.height()),
                        Map.entry("regionCount", item.regions().size()))).toList()),
                Map.entry("seenDiagnosticCount", first.seenCases().size()),
                Map.entry("sealedConfirmationCount", first.confirmationCases().size()),
                Map.entry("qualityResultsRead", 0),
                Map.entry("externalProviderUsage", first.externalProviderUsage()),
                Map.entry("apiKeyReads", first.apiKeyReads()),
                Map.entry("terminalCode", first.terminalCode()));
        Files.write(output, JSON.writeValueAsBytes(evidence), StandardOpenOption.CREATE_NEW);
    }

    private static Path requiredOutput() {
        var value = System.getenv("RENDERWEAVE_R5P_ASSIGNMENT_EVIDENCE");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("RENDERWEAVE_R5P_ASSIGNMENT_EVIDENCE is required");
        }
        var evidenceRoot = repositoryRoot().resolve(".sdlc/evidence")
                .toAbsolutePath().normalize();
        var output = Path.of(value).toAbsolutePath().normalize();
        var parent = output.getParent();
        if (!output.startsWith(evidenceRoot) || output.equals(evidenceRoot)
                || !"r5p-assignment-a1.json".equals(output.getFileName().toString())
                || parent == null
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P_ASSIGNMENT_EVIDENCE_PATH_INVALID");
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
