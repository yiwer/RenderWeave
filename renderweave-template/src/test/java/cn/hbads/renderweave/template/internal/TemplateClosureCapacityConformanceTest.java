package cn.hbads.renderweave.template.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java Template-owned slice of the Rendering Pipeline execution-class replay. */
class TemplateClosureCapacityConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIXTURE_MEMBERS = Set.of(
            "fixtureVersion", "generatorProfile", "executionClass", "baseline", "scenario",
            "observationAdapter", "targetContract");
    private static final Set<String> FORBIDDEN_MEMBERS = Set.of(
            "expectedTerminal", "expectedAssertions", "plannedAssertions", "plannedOracleId",
            "requirementIds", "resolvedCode", "resolvedKind", "latest", "default", "script");

    @Test
    void replaysAllTemplateClosureAxesThroughTheProductionFreezeGuard() throws Exception {
        var guard = new TemplateClosureCapacityGuard();
        List<Path> fixtures;
        try (var paths = Files.list(fixtureRoot())) {
            fixtures = paths
                    .filter(path -> path.getFileName().toString()
                            .matches("cap-00[1-5]-.*\\.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        assertEquals(15, fixtures.size(), "Template closure fixture count");

        var caseIds = new HashSet<String>();
        var axes = new HashSet<String>();
        var observations = new ArrayList<Map<String, Object>>();
        int accepted = 0;
        int rejected = 0;

        for (var fixturePath : fixtures) {
            byte[] fixtureBytes = Files.readAllBytes(fixturePath);
            JsonNode fixture = JSON.readTree(fixtureBytes);
            assertEquals(FIXTURE_MEMBERS, members(fixture));
            assertNoForbiddenMembers(fixture);
            assertEquals("renderweave-rendering-pipeline-fixture/1.0",
                    fixture.get("fixtureVersion").asText());
            assertEquals("EXEC::RENDERING_PIPELINE::1.0",
                    fixture.get("executionClass").asText());

            JsonNode scenario = fixture.get("scenario");
            assertEquals("CAPACITY_BOUNDARY", scenario.get("mode").asText());
            assertEquals("RENDERING_PIPELINE_CAPACITY_GUARD",
                    scenario.get("entrypoint").asText());
            assertEquals("renderweave-rendering-pipeline-capacity-guard/1.0",
                    scenario.get("guardContractId").asText());
            assertEquals("NONE", scenario.get("faultSchedule").get("kind").asText());

            String caseId = scenario.get("scenarioId").asText();
            String limitId = scenario.get("limitId").asText();
            String observedText = scenario.get("observedValue").asText();
            long observedValue = Long.parseLong(observedText);
            String variant = scenario.get("variant").asText();
            assertEquals("CAP::" + limitId + "::" + variant, caseId);
            assertTrue(caseIds.add(caseId), "duplicate caseId");
            axes.add(limitId);

            var decision = guard.evaluate(limitId, observedValue);
            if (decision.accepted()) {
                accepted++;
            } else {
                rejected++;
            }
            observations.add(observation(
                    fixturePath, fixtureBytes, scenario, decision.accepted(),
                    decision.terminalCode()));
        }

        assertEquals(5, axes.size());
        assertEquals(9, accepted);
        assertEquals(6, rejected);
        writeReportIfRequested(observations, accepted, rejected);
    }

    private static Map<String, Object> observation(
            Path fixturePath,
            byte[] fixtureBytes,
            JsonNode scenario,
            boolean accepted,
            String terminalCode
    ) throws Exception {
        String zeroBoundary = accepted ? null : scenario.get("zeroBoundary").asText();
        var value = new LinkedHashMap<String, Object>();
        value.put("accepted", accepted);
        value.put("terminalCode", accepted ? null : terminalCode);
        value.put("terminalStage", accepted ? null : scenario.get("contractStage").asText());
        value.put("publicRenderStage",
                accepted ? null : scenario.get("publicRenderStage").asText());
        value.put("limitId", scenario.get("limitId").asText());
        value.put("observedValue", scenario.get("observedValue").asText());
        value.put("reservationReached", true);
        value.put("zeroBoundary", zeroBoundary);
        value.put("downstreamEffects", downstreamEffects(accepted, zeroBoundary));

        var result = new LinkedHashMap<String, Object>();
        result.put("caseId", scenario.get("scenarioId").asText());
        result.put("fixturePath", "rendering-pipeline/fixtures/" + fixturePath.getFileName());
        result.put("fixtureSha256", "sha256:" + sha256(fixtureBytes));
        result.put("observation", value);
        return result;
    }

    private static List<String> downstreamEffects(boolean accepted, String zeroBoundary) {
        if (accepted) {
            return List.of("targetAxisAccepted=1");
        }
        return switch (zeroBoundary) {
            case "ZERO_EVALUATION_DOCUMENT_OUTPUT" -> List.of(
                    "capabilityStates=0", "evaluations=0", "renderDocuments=0",
                    "engineCommands=0", "renderOutputs=0");
            case "ZERO_DOCUMENT_OUTPUT" -> List.of(
                    "renderDocuments=0", "engineCommands=0", "renderOutputs=0");
            case "ALGORITHM_INVARIANT" -> List.of("certificationAccepted=0");
            default -> throw new IllegalArgumentException(
                    "unknown rejected zero boundary: " + zeroBoundary);
        };
    }

    private static void writeReportIfRequested(
            List<Map<String, Object>> observations,
            int accepted,
            int rejected
    ) throws Exception {
        String configured = System.getProperty("renderweave.renderingPipeline.templateReport");
        if (configured == null) {
            return;
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("reportVersion", "renderweave-rendering-pipeline-template-capacity/1");
        report.put("engine", "java-template-closure-authority");
        report.put("executionClass", "EXEC::RENDERING_PIPELINE::1.0");
        report.put("axisCount", 5);
        report.put("caseCount", observations.size());
        report.put("acceptedCount", accepted);
        report.put("rejectedCount", rejected);
        report.put("observations", observations);
        report.put("networkAttempts", 0);
        report.put("externalProviderAttempts", 0);
        writeNewJson(Path.of(configured), report);
    }

    private static Path fixtureRoot() {
        String explicit = System.getProperty("renderweave.renderingPipeline.fixtureRoot");
        if (explicit != null) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        String reactor = System.getProperty("maven.multiModuleProjectDirectory");
        Path root = reactor == null ? Path.of("..").toAbsolutePath() : Path.of(reactor);
        return root.resolve(".scratch/renderweave-template-v1/rendering-pipeline/fixtures")
                .toAbsolutePath().normalize();
    }

    private static Set<String> members(JsonNode node) {
        var result = new HashSet<String>();
        result.addAll(node.propertyNames());
        return result;
    }

    private static void assertNoForbiddenMembers(JsonNode node) {
        if (node.isObject()) {
            node.propertyNames().forEach(name -> {
                assertFalse(FORBIDDEN_MEMBERS.contains(name),
                        "forbidden target input member: " + name);
                assertNoForbiddenMembers(node.get(name));
            });
        } else if (node.isArray()) {
            node.forEach(TemplateClosureCapacityConformanceTest::assertNoForbiddenMembers);
        }
    }

    private static void writeNewJson(Path path, Map<String, Object> value) throws Exception {
        byte[] pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        byte[] output = java.util.Arrays.copyOf(pretty, pretty.length + 1);
        output[pretty.length] = (byte) '\n';
        Files.write(path, output, StandardOpenOption.CREATE_NEW);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
