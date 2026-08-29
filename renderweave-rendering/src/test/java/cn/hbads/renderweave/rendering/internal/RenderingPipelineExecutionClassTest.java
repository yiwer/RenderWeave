package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationCommand;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationOutcome;
import cn.hbads.renderweave.rendering.api.Evaluator.ExternalAssetReadAuthorization;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.OwnerScope;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.spi.CapabilityStateStore;
import cn.hbads.renderweave.rendering.spi.RenderEngine.RendererCommand;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import cn.hbads.renderweave.template.internal.TemplateModule;
import cn.hbads.renderweave.validation.ResolvedSchema;
import cn.hbads.renderweave.validation.ResolvedSchemaIdentity;
import cn.hbads.renderweave.validation.ResolvedValidationTarget;
import cn.hbads.renderweave.validation.ValidationTargetResolver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact Java evaluator/sealer role for the Rendering Pipeline execution class. */
class RenderingPipelineExecutionClassTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXECUTION_CLASS = "EXEC::RENDERING_PIPELINE::1.0";
    private static final String TARGET_PATH =
            ".scratch/renderweave-template-v1/rendering-pipeline/execution-class-target-v1.json";
    private static final String REQUEST_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final long DEADLINE_EPOCH_MILLIS = 2_000_000_000_000L;
    private static final StaticSchemaRef EMPTY_SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final Set<String> FIXTURE_MEMBERS = Set.of(
            "fixtureVersion", "generatorProfile", "executionClass", "baseline", "scenario",
            "observationAdapter", "targetContract");
    private static final Set<String> FORBIDDEN_MEMBERS = Set.of(
            "expectedTerminal", "expectedAssertions", "plannedAssertions", "plannedOracleId",
            "requirementIds", "resolvedCode", "resolvedKind", "latest", "default", "script");
    private static final String EFFECTIVE_BUDGET_VECTOR = "{\"groups\":{"
            + "\"capabilityRuntime\":{\"limits\":{"
            + "\"staticCapabilitySources\":4096,\"totalDemands\":8192,"
            + "\"clockDemands\":4096,\"randomDemands\":4096,"
            + "\"positionCanonicalBytesPerDemand\":2048,"
            + "\"positionCanonicalBytesTotal\":16777216,"
            + "\"capabilityStateRecordBytes\":1048576,"
            + "\"resultDigestStreamingBytes\":16777216,"
            + "\"initializationAttempts\":3,\"randomRejectionAttempts\":128}}}}";

    @Test
    void sealsTheExactRootTargetAndReplaysEveryRenderingOwnedCapacityAxis() throws Exception {
        var capacity = replayCapacityFixtures();
        var sealed = sealBaseline();
        writeReportIfRequested(capacity, sealed);
    }

    private static CapacityReplay replayCapacityFixtures() throws Exception {
        var guard = new RenderingPipelineCapacityGuard();
        List<Path> fixtures;
        try (var paths = Files.list(fixtureRoot())) {
            fixtures = paths
                    .filter(path -> path.getFileName().toString().startsWith("cap-"))
                    .filter(path -> !path.getFileName().toString()
                            .matches("cap-00[1-5]-.*\\.json"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        assertEquals(141, fixtures.size(), "Rendering-owned fixture count");

        var axes = new HashSet<String>();
        var caseIds = new HashSet<String>();
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
            assertEquals(EXECUTION_CLASS, fixture.get("executionClass").asText());

            JsonNode scenario = fixture.get("scenario");
            assertEquals("CAPACITY_BOUNDARY", scenario.get("mode").asText());
            assertEquals("RENDERING_PIPELINE_CAPACITY_GUARD",
                    scenario.get("entrypoint").asText());
            assertEquals("renderweave-rendering-pipeline-capacity-guard/1.0",
                    scenario.get("guardContractId").asText());
            assertEquals("NONE", scenario.get("faultSchedule").get("kind").asText());

            String caseId = scenario.get("scenarioId").asText();
            String limitId = scenario.get("limitId").asText();
            String variant = scenario.get("variant").asText();
            assertEquals("CAP::" + limitId + "::" + variant, caseId);
            assertTrue(caseIds.add(caseId), "duplicate caseId");
            axes.add(limitId);

            var decision = guard.evaluate(
                    limitId, Long.parseLong(scenario.get("observedValue").asText()));
            if (decision.accepted()) {
                accepted++;
            } else {
                rejected++;
                assertEquals(scenario.get("publicRenderStage").asText(),
                        decision.publicStage().name());
            }
            observations.add(observation(fixturePath, fixtureBytes, scenario, decision));
        }
        assertEquals(47, axes.size());
        assertEquals(86, accepted);
        assertEquals(55, rejected);
        return new CapacityReplay(observations, accepted, rejected);
    }

    private static Map<String, Object> observation(
            Path fixturePath,
            byte[] fixtureBytes,
            JsonNode scenario,
            RenderingPipelineCapacityGuard.ProbeDecision decision
    ) throws Exception {
        String zeroBoundary = decision.accepted()
                ? null : scenario.get("zeroBoundary").asText();
        var value = new LinkedHashMap<String, Object>();
        value.put("accepted", decision.accepted());
        value.put("terminalCode",
                decision.terminalCode() == null ? null : decision.terminalCode().name());
        value.put("terminalStage",
                decision.accepted() ? null : scenario.get("contractStage").asText());
        value.put("publicRenderStage",
                decision.accepted() ? null : decision.publicStage().name());
        value.put("limitId", decision.limitId());
        value.put("observedValue", Long.toString(decision.observedValue()));
        value.put("reservationReached", true);
        value.put("zeroBoundary", zeroBoundary);
        value.put("downstreamEffects", downstreamEffects(decision.accepted(), zeroBoundary));

        var result = new LinkedHashMap<String, Object>();
        result.put("caseId", scenario.get("scenarioId").asText());
        result.put("fixturePath", "rendering-pipeline/fixtures/" + fixturePath.getFileName());
        result.put("fixtureSha256", "sha256:" + sha256(fixtureBytes));
        result.put("observation", value);
        return result;
    }

    private static BaselineSeal sealBaseline() throws Exception {
        JsonNode baseline = JSON.readTree(Files.readAllBytes(baselinePath()));
        assertEquals("renderweave-rendering-pipeline-baseline/1.0",
                baseline.get("fixtureVersion").asText());
        assertEquals(EXECUTION_CLASS, baseline.get("executionClass").asText());
        assertFalse(baseline.get("externalReadsAllowed").asBoolean());
        assertFalse(baseline.get("networkReadsAllowed").asBoolean());
        assertFalse(baseline.get("currentTimeReadsAllowed").asBoolean());

        JsonNode context = baseline.get("authorityContext");
        JsonNode root = context.get("rootTemplate");
        var dslAdmission = TemplateModule.designDslAuthority()
                .admit(JSON.writeValueAsBytes(root.get("designDsl")));
        var admittedDsl = assertInstanceOf(DesignDslAuthority.Admitted.class, dslAdmission);
        var templateId = new TemplateApplication.TemplateId(root.get("templateId").asText());
        var closureOwner = new TemplateClosureAuthority.OwnerScope(
                context.get("ownerScopeId").asText());
        var snapshot = new TemplateSnapshot(
                templateId,
                root.get("revision").asLong(),
                closureOwner,
                EMPTY_SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                admittedDsl.canonicalUtf8(),
                admittedDsl.contentHash());
        var closure = new ClosureSnapshot(
                closureOwner, templateId, snapshot.revision(), List.of(snapshot), List.of());
        var closureCalls = new int[1];
        TemplateClosureAuthority closureAuthority = (requestId, ignored, control) -> {
            closureCalls[0]++;
            return new TemplateClosureAuthority.ClosureFrozen(closure);
        };

        Evaluator evaluator = RenderingModule.evaluator(
                closureAuthority,
                TemplateModule.designInputExpressionCapacityAuthority(),
                TemplateModule.designSemanticAuthority(),
                TemplateModule.designDslAuthority(),
                null,
                failClosedCapabilities(),
                failClosedCapabilityStates(),
                EFFECTIVE_BUDGET_VECTOR,
                validationResolver(),
                Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC));
        byte[] renderInput = JSON.writeValueAsBytes(baseline.get("renderInput"));
        var command = new EvaluationCommand(
                new RenderRequestId(REQUEST_ID),
                new OwnerScope(context.get("ownerScopeId").asText()),
                "sha256:" + "5".repeat(64),
                ExternalAssetReadAuthorization.DENIED,
                templateId,
                renderInput,
                OutputSelection.defaultPng(),
                baseline.get("effectiveRendererParameters").get("rendererProfile").asText(),
                DEADLINE_EPOCH_MILLIS,
                Long.MAX_VALUE,
                Long.MAX_VALUE);

        var outcome = evaluator.evaluate(command);
        var document = assertInstanceOf(EvaluationOutcome.SealedDocument.class, outcome);
        assertEquals(1, closureCalls[0]);
        JsonNode documentJson = JSON.readTree(document.renderDocumentCanonicalUtf8());
        assertEquals("renderweave-render/1.0", documentJson.get("dslVersion").asText());
        assertEquals("renderweave-layout/1.0", documentJson.get("layoutProfile").asText());
        assertEquals(0, documentJson.get("resources").size());
        assertEquals(0, documentJson.get("canvas").get("children").size());
        assertEquals("rwocc_0000000000000000",
                documentJson.get("canvas").get("occurrenceId").asText());

        var rendererCommand = new RendererCommand(
                "renderweave-render-command/1.0",
                command.renderRequestId(),
                command.rendererProfile(),
                command.deadlineAtEpochMilli(),
                document.renderDocumentDigest(),
                document.renderDocumentCanonicalUtf8(),
                command.outputSelection(),
                false);
        byte[] commandBytes = encodeCommand(rendererCommand);
        JSON.readTree(commandBytes);
        return new BaselineSeal(document, commandBytes, closureCalls[0]);
    }

    private static byte[] encodeCommand(RendererCommand command) {
        var deadline = new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(command.deadlineAtEpochMilli()));
        var png = (OutputSelection.Png) command.outputSelection();
        var document = new String(
                command.renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        var canonical = "{\"contractVersion\":\"" + command.contractVersion()
                + "\",\"requestId\":\"" + command.renderRequestId().value()
                + "\",\"rendererProfile\":\"" + command.rendererProfile()
                + "\",\"deadlineAt\":\"" + deadline
                + "\",\"renderDocumentDigest\":\"" + command.renderDocumentDigest()
                + "\",\"document\":" + document
                + ",\"output\":{\"profile\":\"renderweave-output-png/1.0\",\"dpi\":"
                + png.dpi() + "},\"diagnostics\":{\"layoutTrace\":false}}";
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeReportIfRequested(
            CapacityReplay capacity,
            BaselineSeal baseline
    ) throws Exception {
        String reportValue = System.getProperty("renderweave.renderingPipeline.javaReport");
        if (reportValue == null) {
            return;
        }
        String commandValue = System.getProperty("renderweave.renderingPipeline.command");
        String targetValue = System.getProperty("renderweave.renderingPipeline.target");
        assertTrue(commandValue != null, "command output is required with Java report");
        assertTrue(targetValue != null, "exact target is required with Java report");
        Path commandPath = Path.of(commandValue);
        Files.write(commandPath, baseline.commandBytes(), StandardOpenOption.CREATE_NEW);

        byte[] targetBytes = Files.readAllBytes(Path.of(targetValue));
        JsonNode target = JSON.readTree(targetBytes);
        assertEquals("renderweave-rendering-pipeline-execution-class-target/1.0",
                target.get("artifactVersion").asText());
        assertEquals(EXECUTION_CLASS, target.get("executionClass").asText());

        var baselineResult = new LinkedHashMap<String, Object>();
        baselineResult.put("evaluatorInvocations", 1);
        baselineResult.put("closureAuthorityInvocations", baseline.closureCalls());
        baselineResult.put("assetResolveCount", 0);
        baselineResult.put("capabilityDemandCount", 0);
        baselineResult.put("networkAttempts", 0);
        baselineResult.put("externalProviderAttempts", 0);
        baselineResult.put("nodeCount", 1);
        baselineResult.put("resourceCount", 0);
        baselineResult.put("renderDocumentDigest",
                baseline.document().renderDocumentDigest());
        baselineResult.put("evaluationResultDigest",
                baseline.document().evaluationResultDigest());
        baselineResult.put("commandDigest", digest(
                "renderweave-render-command/1\0", baseline.commandBytes()));
        baselineResult.put("commandArtifact", binding(
                commandPath.getFileName().toString(), baseline.commandBytes()));

        var report = new LinkedHashMap<String, Object>();
        report.put("reportVersion", "renderweave-rendering-pipeline-java-executor/1");
        report.put("engine", "java-evaluator-and-sealer");
        report.put("assurance", "A1_PRODUCT_EXECUTION");
        report.put("executionClass", EXECUTION_CLASS);
        report.put("targetManifest", binding(TARGET_PATH, targetBytes));
        report.put("implementationRevision", target.get("implementationRevision").asText());
        report.put("axisCount", 47);
        report.put("caseCount", capacity.observations().size());
        report.put("acceptedCount", capacity.accepted());
        report.put("rejectedCount", capacity.rejected());
        report.put("observations", capacity.observations());
        report.put("baseline", baselineResult);
        report.put("boundary", Map.of(
                "formalRecordsIssued", 0,
                "recordIssuanceAllowed", false,
                "executionClassExecutable", false,
                "rendererProfileRegistered", false));
        writeNewJson(Path.of(reportValue), report);
    }

    private static RenderingCapabilityRuntime failClosedCapabilities() {
        return new RenderingCapabilityRuntime() {
            @Override
            public Established establish(CapabilityRequirements requirements) {
                throw new AssertionError("resource-free baseline must not establish capability state");
            }

            @Override
            public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
                throw new AssertionError("resource-free baseline must not restore capability state");
            }

            @Override
            public Set<CapabilityContract> supportedContracts() {
                return Set.of();
            }
        };
    }

    private static CapabilityStateStore failClosedCapabilityStates() {
        return new CapabilityStateStore() {
            @Override
            public SaveOutcome save(SaveRequest request) {
                throw new AssertionError("resource-free baseline must not save capability state");
            }

            @Override
            public LoadOutcome load(RenderRequestId requestId, String evaluationFingerprint) {
                throw new AssertionError("resource-free baseline must not load capability state");
            }
        };
    }

    private static ValidationTargetResolver validationResolver() {
        var schema = new ResolvedSchema(
                new ResolvedSchemaIdentity.StaticIdentity(EMPTY_SCHEMA),
                new SchemaDefinition(
                        SchemaDefinition.DSL_VERSION, "Empty", Optional.empty(), List.of()));
        var target = new ResolvedValidationTarget(
                new ResolvedSchemaIdentity.StaticIdentity(EMPTY_SCHEMA),
                Map.of(),
                Map.of(EMPTY_SCHEMA, schema));
        return ignored -> target;
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

    private static Path repoRoot() {
        String reactor = System.getProperty("maven.multiModuleProjectDirectory");
        return (reactor == null ? Path.of("..").toAbsolutePath() : Path.of(reactor))
                .toAbsolutePath().normalize();
    }

    private static Path fixtureRoot() {
        String explicit = System.getProperty("renderweave.renderingPipeline.fixtureRoot");
        return explicit == null
                ? repoRoot().resolve(".scratch/renderweave-template-v1/rendering-pipeline/fixtures")
                : Path.of(explicit).toAbsolutePath().normalize();
    }

    private static Path baselinePath() {
        return repoRoot().resolve(".scratch/renderweave-template-v1/rendering-pipeline/baseline-v1.json");
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
            node.forEach(RenderingPipelineExecutionClassTest::assertNoForbiddenMembers);
        }
    }

    private static Map<String, Object> binding(String path, byte[] bytes) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("path", path);
        result.put("sha256", "sha256:" + sha256(bytes));
        result.put("byteLength", bytes.length);
        return result;
    }

    private static void writeNewJson(Path path, Map<String, Object> value) throws Exception {
        byte[] pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        byte[] output = java.util.Arrays.copyOf(pretty, pretty.length + 1);
        output[pretty.length] = (byte) '\n';
        Files.write(path, output, StandardOpenOption.CREATE_NEW);
    }

    private static String digest(String domain, byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(domain.getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record CapacityReplay(
            List<Map<String, Object>> observations,
            int accepted,
            int rejected
    ) {
    }

    private record BaselineSeal(
            EvaluationOutcome.SealedDocument document,
            byte[] commandBytes,
            int closureCalls
    ) {
        private BaselineSeal {
            commandBytes = commandBytes.clone();
        }

        @Override
        public byte[] commandBytes() {
            return commandBytes.clone();
        }
    }
}
