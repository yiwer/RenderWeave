package cn.hbads.renderweave.inference.certification;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOnlyCertificationCanaryAuthorizationTest {
    private static final UUID CYCLE_ID =
            UUID.fromString("c3bde304-b0b2-43f8-ab7e-16896ff04aed");
    private static final String MANIFEST_IDENTITY =
            "renderweave-image-only-certification-manifest/1.0:"
                    + "0e8e93ebaf18b083992aa6110aa895e59219f6b34594e7dceb3d44f129bd5fb4";
    private static final String EVALUATOR_IDENTITY =
            "renderweave-image-only-certification-evaluator/1.0:"
                    + "ebdb6bf82083ab35d234d4ded07990848d0e28add6e468c9e5a7b6a90555c29e";

    @Test
    void ownerJ1BindsFivePayloadFreeInputsAndIsClosedAfterTheFailedCanary()
            throws Exception {
        var repository = repositoryRoot();
        var cyclePath = repository.resolve("plans/image-only-certification-cycles/")
                .resolve(CYCLE_ID + ".json");
        var authorizationPath = repository.resolve("plans/live-canary-authorizations/")
                .resolve("20260817-image-only-canary-5-c3bde304.json");
        var cycleBytes = Files.readAllBytes(cyclePath);
        var cycleJson = new ObjectMapper().readTree(cycleBytes);

        assertEquals("renderweave-image-only-certification-cycle-preparation/1.0",
                cycleJson.get("version").asText());
        assertEquals(CYCLE_ID.toString(), cycleJson.get("cycleId").asText());
        assertEquals(MANIFEST_IDENTITY, cycleJson.get("manifestIdentity").asText());
        assertEquals(EVALUATOR_IDENTITY, cycleJson.get("evaluatorIdentity").asText());
        assertEquals("CANARY_5", cycleJson.get("stage").asText());
        assertEquals("USER_PROVIDED", cycleJson.get("inputProvenance").asText());
        assertEquals("ORDINARY_DESIGN", cycleJson.get("dataClassification").asText());
        assertEquals(5, cycleJson.get("cases").size());
        assertPayloadFree(cycleBytes);
        assertZeroProvider(cycleJson.get("externalProviderUsage"));

        var cases = new ArrayList<CertificationCanaryCase>();
        for (var item : cycleJson.get("cases")) {
            assertEquals("image/png", item.get("mediaType").asText());
            assertTrue(item.get("encodedBytes").asLong() <= 10L * 1024 * 1024);
            assertTrue(item.get("pixelCount").asLong() <= 25_000_000L);
            assertEquals(item.get("width").asLong() * item.get("height").asLong(),
                    item.get("pixelCount").asLong());
            cases.add(new CertificationCanaryCase(
                    item.get("caseId").asText(), item.get("artifactSha256").asText()));
        }
        assertEquals(5, cases.stream().map(CertificationCanaryCase::artifactSha256)
                .collect(java.util.stream.Collectors.toSet()).size());
        var manifest = new ImageOnlyCertificationManifestFactory().create(
                cycleJson.get("profileSha256").asText(), cases,
                cycleJson.get("assignmentSeed").asText());
        assertEquals(MANIFEST_IDENTITY, manifest.manifestIdentity());
        assertEquals(EVALUATOR_IDENTITY, manifest.evaluatorIdentity());

        var authorizationBytes = Files.readAllBytes(authorizationPath);
        assertPayloadFree(authorizationBytes);
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec()
                .read(authorizationBytes);
        assertEquals(AuthorizationStatus.CLOSED, authorization.status());
        assertEquals(CYCLE_ID, authorization.cycleId());
        assertEquals(CertificationStage.CANARY_5, authorization.stage());
        assertEquals(MANIFEST_IDENTITY, authorization.manifestIdentity());
        assertEquals(EVALUATOR_IDENTITY, authorization.evaluatorIdentity());
        assertEquals(5, authorization.maximumRuns());
        assertEquals(60, authorization.maximumProviderCalls());
        assertEquals(500_000, authorization.maximumModelTokens());
        assertEquals(10_000_000, authorization.maximumCostMicrosCny());
        assertEquals(12, authorization.maximumProviderCallsPerRun());
        assertEquals(6_000_000, authorization.maximumCostPerRunMicrosCny());
        assertEquals(Instant.parse("2026-08-17T10:49:06.053985Z"),
                authorization.closedAt());
        assertEquals(
                "CANARY_PROVIDER_BATCH_HALTED_VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID",
                authorization.closureReason());
        assertEquals("RenderWeave owner via conversation exact J1",
                authorization.approvedBy());
        assertEquals(Instant.parse("2026-08-17T09:48:59Z"), authorization.approvedAt());
        assertEquals(authorization.approvedAt(), authorization.effectiveAt());
        assertEquals(Set.copyOf(cases.stream().map(item -> new AuthorizedCertificationCase(
                        item.caseId(), item.artifactSha256())).toList()),
                Set.copyOf(authorization.cases()));
        assertEquals(4 * 60 * 60,
                authorization.expiresAt().getEpochSecond()
                        - authorization.effectiveAt().getEpochSecond());

        var resultBytes = Files.readAllBytes(repository.resolve(
                "plans/image-only-certification-cycles/"
                        + CYCLE_ID + "-canary5-terminal.json"));
        assertPayloadFree(resultBytes);
        var result = new ObjectMapper().readTree(resultBytes);
        assertEquals("renderweave-image-only-certification-stage-result/1.0",
                result.get("version").asText());
        assertEquals("FAILED", result.get("result").asText());
        assertEquals("TERMINAL_CLOSED", result.get("lifecycle").asText());
        assertEquals("VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID",
                result.get("terminalReason").asText());
        assertEquals(2, result.get("startedRuns").asInt());
        assertEquals(3, result.get("unstartedRuns").asInt());
        assertEquals(17, result.get("providerCalls").asInt());
        assertEquals(301_409, result.get("modelTokens").asLong());
        assertEquals(6_338_772, result.get("costMicrosCny").asLong());
        assertEquals(0, result.get("unsettledReservations").asInt());
        assertFalse(result.get("candidateApplied").asBoolean());
        assertFalse(result.get("staticSchemaPublished").asBoolean());
        assertFalse(result.get("nextLiveStageUnlocked").asBoolean());
        assertEquals(2, result.get("cases").size());

        var cycle = new FrozenCertificationCycle(
                CYCLE_ID, manifest.profileId(), manifest.profileSha256(),
                manifest.manifestIdentity(), manifest.evaluatorIdentity(),
                cycleJson.get("authorityInventorySha256").asText(),
                Instant.parse(cycleJson.get("createdAt").asText()));
        var certification = new ProfileCertificationService(new MemoryStore());
        certification.start(cycle, manifest);
        var failure = assertThrows(CertificationAuthorizationViolation.class,
                () -> new ImageOnlyCertificationPreflight().requireProviderZeroProof(
                        authorization, cycle, manifest, certification.progress(CYCLE_ID),
                        authorization.closedAt().plusSeconds(1)));
        assertEquals("CERTIFICATION_AUTHORIZATION_NOT_OPEN", failure.reasonCode());
    }

    private static void assertZeroProvider(JsonNode usage) {
        assertEquals(0, usage.get("attempts").asLong());
        assertEquals(0, usage.get("reservations").asLong());
        assertEquals(0, usage.get("costMicrosCny").asLong());
        assertEquals(0, usage.get("apiKeyReads").asLong());
    }

    private static void assertPayloadFree(byte[] bytes) {
        var text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(text.contains("F:\\"));
        assertFalse(text.contains(".png"));
        assertFalse(text.contains("下载"));
        assertFalse(text.contains("shenzhen"));
    }

    private static Path repositoryRoot() {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("plans"))
                ? current : current.getParent();
    }

    private static final class MemoryStore implements ProfileCertificationStore {
        private final Map<UUID, List<ProfileCertificationEvent>> events = new java.util.HashMap<>();

        @Override
        public void append(ProfileCertificationEvent event) {
            events.computeIfAbsent(event.cycleId(), ignored -> new ArrayList<>()).add(event);
        }

        @Override
        public List<ProfileCertificationEvent> events(UUID cycleId) {
            return List.copyOf(events.getOrDefault(cycleId, List.of()));
        }
    }
}
