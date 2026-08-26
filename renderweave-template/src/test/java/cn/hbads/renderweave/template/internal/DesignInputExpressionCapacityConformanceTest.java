package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignInputExpressionCapacityConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET_PATH =
            ".scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v3.json";
    private static final Set<String> FIXTURE_MEMBERS = Set.of(
            "fixtureVersion", "generatorProfile", "executionClass", "baseline", "scenario",
            "observationAdapter", "targetContract"
    );
    private static final Set<String> SCENARIO_MEMBERS = Set.of(
            "mode", "scenarioId", "operationId", "entrypoint", "guardContractId", "limitId",
            "observedValue", "valueEncoding", "comparator", "variant", "contractStage",
            "publicRenderStage", "reservationPoint", "zeroBoundary", "faultSchedule"
    );
    private static final Set<String> FORBIDDEN_MEMBERS = Set.of(
            "expectedTerminal", "expectedAssertions", "plannedAssertions", "plannedOracleId",
            "requirementIds", "resolvedCode", "resolvedKind", "latest", "default", "script"
    );

    @Test
    void replaysEveryFrozenScalarThroughTheProductCapacityInterface() throws Exception {
        var authority = TemplateModule.designInputExpressionCapacityAuthority();
        List<Path> fixturePaths;
        try (var paths = Files.list(fixtureRoot())) {
            fixturePaths = paths
                    .filter(path -> path.getFileName().toString().startsWith("cap-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        assertEquals(195, fixturePaths.size(), "frozen scalar fixture count");

        var caseIds = new HashSet<String>();
        var axes = new HashSet<String>();
        var axisVariants = new HashSet<String>();
        var observations = new ArrayList<Map<String, Object>>();
        int acceptedCount = 0;
        int rejectedCount = 0;

        for (Path fixturePath : fixturePaths) {
            byte[] fixtureBytes = Files.readAllBytes(fixturePath);
            JsonNode fixture = JSON.readTree(fixtureBytes);
            assertEquals(FIXTURE_MEMBERS, members(fixture), "closed fixture members");
            assertNoForbiddenMembers(fixture);
            assertEquals("renderweave-design-input-expression-fixture/1.0",
                    fixture.get("fixtureVersion").asText());
            assertEquals("renderweave-design-input-expression-generator/1.0",
                    fixture.get("generatorProfile").asText());
            assertEquals("EXEC::DESIGN_INPUT_EXPRESSION::1.0",
                    fixture.get("executionClass").asText());

            JsonNode scenario = fixture.get("scenario");
            assertEquals(SCENARIO_MEMBERS, members(scenario), "closed scenario members");
            assertEquals("CAPACITY_BOUNDARY", scenario.get("mode").asText());
            assertEquals("main", scenario.get("operationId").asText());
            assertEquals("DESIGN_INPUT_EXPRESSION_CAPACITY_GUARD",
                    scenario.get("entrypoint").asText());
            assertEquals("renderweave-design-input-expression-capacity-guard/1.0",
                    scenario.get("guardContractId").asText());
            assertEquals("NONE", scenario.get("faultSchedule").get("kind").asText());

            String caseId = scenario.get("scenarioId").asText();
            String limitId = scenario.get("limitId").asText();
            String observedValue = scenario.get("observedValue").asText();
            String variant = scenario.get("variant").asText();
            assertEquals("CAP::" + limitId + "::" + variant, caseId);
            assertTrue(Set.of("below", "at", "above").contains(variant));
            assertTrue(caseIds.add(caseId), "duplicate scenarioId");
            axes.add(limitId);
            assertTrue(axisVariants.add(limitId + "::" + variant), "duplicate axis variant");

            var decision = authority.evaluate(
                    new DesignInputExpressionCapacityAuthority.Observation(limitId, observedValue)
            );
            assertFalse(
                    decision instanceof DesignInputExpressionCapacityAuthority.Invalid,
                    "frozen fixture must address a valid product rule: " + caseId
            );

            var observation = new LinkedHashMap<String, Object>();
            if (decision instanceof DesignInputExpressionCapacityAuthority.Accepted) {
                acceptedCount++;
                observation.put("accepted", true);
                observation.put("terminalCode", null);
                observation.put("terminalStage", null);
                observation.put("publicRenderStage", null);
                observation.put("zeroBoundary", null);
                observation.put("downstreamEffects", List.of("targetAxisAccepted=1"));
            } else {
                rejectedCount++;
                var rejected = assertInstanceOf(
                        DesignInputExpressionCapacityAuthority.Rejected.class,
                        decision
                );
                observation.put("accepted", false);
                observation.put("terminalCode", rejected.terminal().code());
                observation.put("terminalStage", rejected.terminal().contractStage());
                observation.put("publicRenderStage", rejected.terminal().publicRenderStage());
                observation.put("zeroBoundary", rejected.terminal().zeroBoundary());
                observation.put("downstreamEffects", rejected.terminal().downstreamEffects());
            }
            observation.put("limitId", limitId);
            observation.put("observedValue", observedValue);
            observation.put("reservationReached", true);

            var result = new LinkedHashMap<String, Object>();
            result.put("caseId", caseId);
            result.put(
                    "fixturePath",
                    "design-input-expression/fixtures/" + fixturePath.getFileName()
            );
            result.put("fixtureSha256", "sha256:" + sha256(fixtureBytes));
            result.put("observation", observation);
            observations.add(result);
        }

        assertEquals(65, axes.size());
        assertEquals(195, axisVariants.size());
        assertEquals(125, acceptedCount);
        assertEquals(70, rejectedCount);
        writeReportIfRequested(observations, axes.size(), acceptedCount, rejectedCount);
    }

    private void writeReportIfRequested(
            List<Map<String, Object>> observations,
            int axisCount,
            int acceptedCount,
            int rejectedCount
    ) throws Exception {
        String reportPath = System.getProperty("renderweave.designInputExpression.primaryReport");
        if (reportPath == null) {
            return;
        }
        String configuredTarget = System.getProperty("renderweave.designInputExpression.target");
        assertTrue(configuredTarget != null, "exact component target is required for report output");
        byte[] targetBytes = Files.readAllBytes(Path.of(configuredTarget));
        JsonNode target = JSON.readTree(targetBytes);
        assertEquals("renderweave-design-input-expression-capacity-component-target/1.0",
                target.get("artifactVersion").asText());
        assertEquals("DESIGN_INPUT_EXPRESSION_TARGET::CAPACITY_AUTHORITY_PARTIAL_WIRING::3.0",
                target.get("targetId").asText());
        assertEquals("EXEC::DESIGN_INPUT_EXPRESSION::1.0",
                target.get("executionClass").asText());
        assertEquals("renderweave-design-input-expression-capacity-guard/1.0",
                target.get("guardContractId").asText());
        assertEquals(33, target.get("productWiring").get("wiredAxisCount").asInt());
        assertEquals(32, target.get("productWiring").get("remainingAxisCount").asInt());

        var targetBinding = new LinkedHashMap<String, Object>();
        targetBinding.put("path", TARGET_PATH);
        targetBinding.put("sha256", "sha256:" + sha256(targetBytes));
        targetBinding.put("byteLength", targetBytes.length);

        var report = new LinkedHashMap<String, Object>();
        report.put("reportVersion",
                "renderweave-design-input-expression-capacity-primary/1");
        report.put("engine", "java-semantic-authority");
        report.put("role", "primary-product-capacity-interface-executor");
        report.put("assurance", "A1_PRODUCT_COMPONENT_EXECUTION");
        report.put("executionClass", "EXEC::DESIGN_INPUT_EXPRESSION::1.0");
        report.put("guardContractId",
                "renderweave-design-input-expression-capacity-guard/1.0");
        report.put("targetManifest", targetBinding);
        report.put("implementationRevision", target.get("implementationRevision").asText());
        report.put("axisCount", axisCount);
        report.put("caseCount", observations.size());
        report.put("acceptedCount", acceptedCount);
        report.put("rejectedCount", rejectedCount);
        report.put("passed", observations.size());
        report.put("failed", 0);
        report.put("observations", observations);

        var boundary = new LinkedHashMap<String, Object>();
        boundary.put("scalarGuardOnly", true);
        boundary.put("wiredProductAxisCount", 33);
        boundary.put("remainingProductAxisCount", 32);
        boundary.put("parserOrCanonicalizerExecutedByScalarProbe", false);
        boundary.put("productReservationProofSeparate", true);
        boundary.put("formalRecordsIssued", 0);
        boundary.put("preissuanceReady", false);
        boundary.put("recordIssuanceAllowed", false);
        boundary.put("executionClassExecutable", false);
        report.put("boundary", boundary);

        byte[] pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        byte[] output = new byte[pretty.length + 1];
        System.arraycopy(pretty, 0, output, 0, pretty.length);
        output[pretty.length] = (byte) '\n';
        Files.write(Path.of(reportPath), output, StandardOpenOption.CREATE_NEW);
    }

    private static Path fixtureRoot() {
        String explicit = System.getProperty("renderweave.designInputExpression.fixtureRoot");
        if (explicit != null) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        String reactor = System.getProperty("maven.multiModuleProjectDirectory");
        Path root = reactor == null ? Path.of("..").toAbsolutePath() : Path.of(reactor);
        return root.resolve(
                        ".scratch/renderweave-template-v1/design-input-expression/fixtures"
                )
                .toAbsolutePath()
                .normalize();
    }

    private static Set<String> members(JsonNode node) {
        var names = new HashSet<String>();
        names.addAll(node.propertyNames());
        return names;
    }

    private static void assertNoForbiddenMembers(JsonNode node) {
        if (node.isObject()) {
            node.propertyNames().forEach(name -> {
                assertFalse(FORBIDDEN_MEMBERS.contains(name),
                        "forbidden fixture member: " + name);
                assertNoForbiddenMembers(node.get(name));
            });
        } else if (node.isArray()) {
            node.forEach(DesignInputExpressionCapacityConformanceTest::assertNoForbiddenMembers);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
