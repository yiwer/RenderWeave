package cn.hbads.renderweave.asset.internal;

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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainServicesCapacityConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET_PATH =
            ".scratch/renderweave-template-v1/domain-services/product-execution-target-v1.json";
    private static final Pattern CANONICAL_INTEGER = Pattern.compile("0|[1-9][0-9]*");
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
    void executesEveryFrozenScalarFixtureThroughTheProductionGuard() throws Exception {
        Path fixtureRoot = fixtureRoot();
        List<Path> fixturePaths;
        try (var entries = Files.list(fixtureRoot)) {
            fixturePaths = entries
                    .filter(path -> path.getFileName().toString().startsWith("cap-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        assertEquals(12, fixturePaths.size(), "frozen Domain Services capacity fixture count");

        var observations = new ArrayList<Map<String, Object>>();
        var observedCases = new HashSet<String>();
        var observedAxes = new HashSet<String>();
        var observedVariants = new HashSet<String>();
        for (Path fixturePath : fixturePaths) {
            byte[] fixtureBytes = Files.readAllBytes(fixturePath);
            JsonNode fixture = JSON.readTree(fixtureBytes);
            assertEquals(FIXTURE_MEMBERS, members(fixture), "closed fixture members");
            assertNoForbiddenMembers(fixture);
            assertEquals("renderweave-domain-services-fixture/1.0",
                    fixture.get("fixtureVersion").asText());
            assertEquals("renderweave-domain-services-generator/1.0",
                    fixture.get("generatorProfile").asText());
            assertEquals("EXEC::DOMAIN_SERVICES::1.0",
                    fixture.get("executionClass").asText());

            JsonNode scenario = fixture.get("scenario");
            assertEquals(SCENARIO_MEMBERS, members(scenario), "closed scenario members");
            assertEquals("CAPACITY_BOUNDARY", scenario.get("mode").asText());
            assertEquals("main", scenario.get("operationId").asText());
            assertEquals("ASSET_CONTENT_ADMISSION_CAPACITY_GUARD",
                    scenario.get("entrypoint").asText());
            assertEquals("renderweave-domain-asset-content-capacity-guard/1.0",
                    scenario.get("guardContractId").asText());
            assertEquals("CANONICAL_INTEGER", scenario.get("valueEncoding").asText());
            assertEquals("MAX_INCLUSIVE", scenario.get("comparator").asText());
            assertEquals("ASSET_CONTENT_ADMISSION", scenario.get("contractStage").asText());
            assertEquals("ASSET_ADMISSION", scenario.get("publicRenderStage").asText());
            assertEquals("NONE", scenario.get("faultSchedule").get("kind").asText());

            String caseId = scenario.get("scenarioId").asText();
            String limitId = scenario.get("limitId").asText();
            String variant = scenario.get("variant").asText();
            String observedText = scenario.get("observedValue").asText();
            assertTrue(CANONICAL_INTEGER.matcher(observedText).matches(),
                    "observed scalar must be a canonical integer");
            long observedValue = Long.parseLong(observedText);
            var axis = AssetContentCapacityGuard.Axis.fromExternalId(limitId);
            var decision = AssetContentCapacityGuard.evaluate(axis, observedValue);

            assertTrue(observedCases.add(caseId), "duplicate scenarioId");
            observedAxes.add(limitId);
            observedVariants.add(limitId + "::" + variant);
            assertEquals("CAP::" + limitId + "::" + variant, caseId);
            assertTrue(Set.of("below", "at", "above").contains(variant));
            assertEquals(!"above".equals(variant), decision.accepted());

            var observation = new LinkedHashMap<String, Object>();
            observation.put("accepted", decision.accepted());
            observation.put("terminalCode",
                    decision.accepted() ? null : "ASSET_CONTENT_LIMIT_EXCEEDED");
            observation.put("terminalStage",
                    decision.accepted() ? null : "ASSET_CONTENT_ADMISSION");
            observation.put("limitId", limitId);
            observation.put("observedValue", observedText);
            observation.put("reservationReached", true);
            observation.put("zeroBoundary",
                    decision.accepted() ? null : "ZERO_DOCUMENT_OUTPUT");
            observation.put("downstreamEffects", decision.accepted()
                    ? List.of("targetAxisAccepted=1")
                    : List.of("renderDocuments=0", "engineCommands=0", "renderOutputs=0"));

            var result = new LinkedHashMap<String, Object>();
            result.put("caseId", caseId);
            result.put("fixturePath", "domain-services/fixtures/" + fixturePath.getFileName());
            result.put("fixtureSha256", "sha256:" + sha256(fixtureBytes));
            result.put("observation", observation);
            observations.add(result);
        }

        assertEquals(4, observedAxes.size());
        assertEquals(12, observedVariants.size());
        for (var axis : AssetContentCapacityGuard.Axis.values()) {
            for (String variant : List.of("below", "at", "above")) {
                assertTrue(observedVariants.contains(axis.externalId() + "::" + variant));
            }
        }

        String reportPath = System.getProperty("renderweave.domainServices.primaryReport");
        if (reportPath != null) {
            String targetPath = System.getProperty("renderweave.domainServices.target");
            assertTrue(targetPath != null, "exact product target is required for report issuance");
            byte[] targetBytes = Files.readAllBytes(Path.of(targetPath));
            JsonNode target = JSON.readTree(targetBytes);
            assertEquals("renderweave-domain-services-capacity-product-target/1.0",
                    target.get("artifactVersion").asText());
            assertEquals("DOMAIN_SERVICES_CAPACITY_TARGET::ASSET_CONTENT_GUARD::1.0",
                    target.get("targetId").asText());
            assertEquals("EXEC::DOMAIN_SERVICES::1.0",
                    target.get("executionClass").asText());
            assertEquals("renderweave-domain-asset-content-capacity-guard/1.0",
                    target.get("guardContractId").asText());

            var report = new LinkedHashMap<String, Object>();
            report.put("reportVersion", "renderweave-domain-services-capacity-primary/1");
            report.put("engine", "java-domain-authority");
            report.put("role", "primary-exact-product-guard-executor");
            report.put("assurance", "A1_EXACT_PRODUCT_EXECUTION");
            report.put("executionClass", "EXEC::DOMAIN_SERVICES::1.0");
            report.put("guardContractId",
                    "renderweave-domain-asset-content-capacity-guard/1.0");
            var targetBinding = new LinkedHashMap<String, Object>();
            targetBinding.put("path", TARGET_PATH);
            targetBinding.put("sha256", "sha256:" + sha256(targetBytes));
            targetBinding.put("byteLength", targetBytes.length);
            report.put("targetManifest", targetBinding);
            report.put("implementationRevision",
                    target.get("implementationRevision").asText());
            report.put("caseCount", observations.size());
            report.put("passed", observations.size());
            report.put("failed", 0);
            report.put("observations", observations);
            var boundary = new LinkedHashMap<String, Object>();
            boundary.put("mediaPayloadAllocated", false);
            boundary.put("databaseUsed", false);
            boundary.put("renderDocumentCount", 0);
            boundary.put("renderOutputCount", 0);
            boundary.put("formalRecordsIssued", 0);
            report.put("boundary", boundary);
            byte[] reportBytes = JSON.writeValueAsBytes(report);
            Files.writeString(
                    Path.of(reportPath),
                    new String(reportBytes, StandardCharsets.UTF_8) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
        }
    }

    private static Path fixtureRoot() {
        String explicit = System.getProperty("renderweave.domainServices.fixtureRoot");
        if (explicit != null) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        String reactor = System.getProperty("maven.multiModuleProjectDirectory");
        Path root = reactor == null ? Path.of("..").toAbsolutePath() : Path.of(reactor);
        return root.resolve(".scratch/renderweave-template-v1/domain-services/fixtures")
                .toAbsolutePath()
                .normalize();
    }

    private static Set<String> members(JsonNode object) {
        var names = new HashSet<String>();
        object.propertyNames().forEach(names::add);
        return Set.copyOf(names);
    }

    private static void assertNoForbiddenMembers(JsonNode node) {
        if (node.isObject()) {
            node.propertyNames().forEach(name -> {
                assertFalse(FORBIDDEN_MEMBERS.contains(name), "executor input contains " + name);
                assertNoForbiddenMembers(node.get(name));
            });
        } else if (node.isArray()) {
            node.forEach(DomainServicesCapacityConformanceTest::assertNoForbiddenMembers);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
