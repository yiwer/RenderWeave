package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblemJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.certification.AuthorizationStatus;
import cn.hbads.renderweave.inference.certification.AuthorizedCertificationCase;
import cn.hbads.renderweave.inference.certification.CertificationCanaryCase;
import cn.hbads.renderweave.inference.certification.CertificationInferenceProvider;
import cn.hbads.renderweave.inference.certification.CertificationStageExecutionService;
import cn.hbads.renderweave.inference.certification.CertificationStageLedgerSnapshot;
import cn.hbads.renderweave.inference.certification.CertificationStageLedgerStatus;
import cn.hbads.renderweave.inference.certification.FrozenCertificationCycle;
import cn.hbads.renderweave.inference.certification.FrozenImageOnlyCertificationManifest;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationAuthorization;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationAuthorizationJsonCodec;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationManifestFactory;
import cn.hbads.renderweave.inference.certification.ProfileCertificationService;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.run.InferenceRunService;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.vision.DocumentVisionPreprocessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicitly opt-in paid IMAGE_ONLY CANARY_5 runner. Normal builds skip it even when a
 * credential exists. Regular evidence is payload-free; review payload is isolated under
 * .scratch and never applied or published by this runner.
 */
@Testcontainers
@SpringBootTest(properties = {
        "renderweave.inference.live-enabled=false",
        "renderweave.inference.live-upload-enabled=false",
        "renderweave.inference.recovery-enabled=false",
        "renderweave.inference.blob-root=target/image-only-certification-canary-blobs-c3bde304",
        "renderweave.inference.live-poll-millis=60000"
})
@Import(ImageOnlyCertificationCanaryLiveTest.LiveConfiguration.class)
@EnabledIfEnvironmentVariable(
        named = "RENDERWEAVE_RUN_IMAGE_ONLY_CERTIFICATION_CANARY", matches = "true")
class ImageOnlyCertificationCanaryLiveTest {
    private static final String AUTHORIZATION_ID = "20260817-iopa-canary5-c3bde304";
    private static final String EVIDENCE_FILE = "image-only-canary-live-summary.json";
    private static final String EXPECTED_CAPABILITY =
            "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private InferenceRunService runService;
    @Autowired private LiveInferenceWorker worker;
    @Autowired private InferenceReplayStore workflowStore;
    @Autowired private PostgresProfileCertificationStore profileCertificationStore;
    @Autowired private CertificationStageExecutionService stageExecution;
    @Autowired private ImageOnlyCertificationAuthorization authorization;
    @Autowired private CertificationCycleFixture cycleFixture;
    @Autowired private InferenceProfileRegistry profiles;
    @Autowired private InferenceProvider provider;
    @Autowired private DocumentVisionPreprocessor documentVision;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;

    private final CandidateJsonCodec candidateCodec = new CandidateJsonCodec();
    private final CandidateProblemJsonCodec problemCodec = new CandidateProblemJsonCodec();

    @Test
    void executesAuthorizedFiveCaseBatchAndLeavesCandidatesForOwnerReview() throws Exception {
        var now = clock.instant();
        requirePreflight(now);
        var inputs = loadAuthorizedInputs();
        var evidence = evidenceDirectory();
        var review = reviewDirectory();
        requireFreshOutputs(evidence, review);
        // The authorization-scoped directory is the durable one-shot marker. Create it before
        // opening the ephemeral database ledger so another invocation cannot race this batch.
        Files.createDirectories(review);

        var certification = new ProfileCertificationService(profileCertificationStore);
        certification.start(cycleFixture.cycle(), cycleFixture.manifest());
        stageExecution.openStage(
                authorization, cycleFixture.cycle(), cycleFixture.manifest(),
                certification.progress(authorization.cycleId()), now);
        var results = new ArrayList<CaseEvidence>();
        String haltReason = null;
        String harnessFailureCode = null;
        RuntimeException harnessFailure = null;
        try {
            writeEvidence(evidence, results, "RUNNING", null, null);
            for (var authorizedCase : authorization.cases()) {
                var result = executeCase(authorizedCase, inputs.get(authorizedCase.artifactSha256()), review);
                results.add(result);
                writeEvidence(evidence, results, "RUNNING", null, null);
                if (!result.reviewRequired()) {
                    haltReason = result.failureCode() == null
                            ? "CANARY_CASE_NOT_REVIEW_REQUIRED" : result.failureCode();
                    break;
                }
            }
        } catch (RuntimeException failure) {
            harnessFailure = failure;
            harnessFailureCode = harnessFailureCode(failure);
            haltReason = harnessFailureCode;
        } finally {
            var allReviewRequired = results.size() == authorization.maximumRuns()
                    && results.stream().allMatch(CaseEvidence::reviewRequired);
            stageExecution.closeStage(
                    authorization.authorizationId(),
                    allReviewRequired
                            ? "CANARY_PROVIDER_BATCH_COMPLETED"
                            : "CANARY_PROVIDER_BATCH_HALTED",
                    clock.instant());
            writeEvidence(
                    evidence, results,
                    allReviewRequired
                            ? "PROVIDER_BATCH_CLOSED_REVIEW_PENDING"
                            : "PROVIDER_BATCH_CLOSED_TERMINAL",
                    haltReason, harnessFailureCode);
        }

        if (harnessFailure != null) throw harnessFailure;
        var ledger = stageExecution.snapshot(authorization.authorizationId());
        assertThat(ledger.status()).isEqualTo(CertificationStageLedgerStatus.CLOSED);
        assertThat(ledger.startedRuns()).isEqualTo(5);
        assertThat(ledger.providerCalls()).isLessThanOrEqualTo(authorization.maximumProviderCalls());
        assertThat(ledger.exposedModelTokens()).isLessThanOrEqualTo(authorization.maximumModelTokens());
        assertThat(ledger.exposedCostMicrosCny()).isLessThanOrEqualTo(
                authorization.maximumCostMicrosCny());
        assertThat(results).hasSize(5).allSatisfy(result -> {
            assertThat(result.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED.name());
            assertThat(result.failureCode()).isNull();
            assertThat(result.reviewRequired()).isTrue();
        });
    }

    private void requirePreflight(Instant now) {
        assertThat(authorization.authorizationId()).isEqualTo(AUTHORIZATION_ID);
        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.OPEN);
        assertThat(now).isAfterOrEqualTo(authorization.effectiveAt());
        assertThat(now).isBefore(authorization.expiresAt());
        assertThat(provider.configured()).as("exact DashScope Provider must be configured").isTrue();
        var profile = profiles.require(authorization.profileId());
        assertThat(profile.canonicalSha256()).isEqualTo(authorization.profileSha256());
        assertThat(profile.profile().model()).isEqualTo(authorization.model());
        var capability = documentVision.capability();
        assertThat(capability.available()).as(capability.diagnosticCode()).isTrue();
        assertThat(capability.capabilityId()).isEqualTo(EXPECTED_CAPABILITY);
    }

    private CaseEvidence executeCase(
            AuthorizedCertificationCase authorizedCase,
            byte[] imageBytes,
            Path reviewDirectory
    ) throws IOException {
        Objects.requireNonNull(imageBytes, "authorized image bytes");
        var profile = profiles.require(authorization.profileId());
        var input = new InferenceInput(
                InferenceMode.IMAGE_ONLY, authorization.profileId(), authorizedCase.caseId(), true,
                List.of(new InferenceInput.BinaryInput(
                        authorizedCase.caseId() + ".png", "image/png", imageBytes)),
                List.of());
        var created = runService.create(
                "iopa-" + authorization.authorizationId() + "-" + authorizedCase.caseId(),
                input, profile.snapshotJson(), 6_000_000L).run();
        stageExecution.startRun(
                authorization.authorizationId(), created.runId(), authorizedCase, clock.instant());
        var finished = worker.processNext("iopa-canary-worker").orElseThrow();
        if (!finished.runId().equals(created.runId())) {
            throw new IllegalStateException("CANARY_WORKER_CLAIMED_UNEXPECTED_RUN");
        }

        var attempts = workflowStore.attempts(created.runId());
        var storedCandidate = workflowStore.findCandidate(created.runId());
        var reviewRequired = finished.state() == InferenceRunState.REVIEW_REQUIRED
                && storedCandidate.isPresent();
        int schemas = 0;
        int fields = 0;
        int blockers = 0;
        int warnings = 0;
        if (storedCandidate.isPresent()) {
            var stored = storedCandidate.orElseThrow();
            var candidate = candidateCodec.parse(stored.currentJson());
            var problems = problemCodec.parse(stored.validationProblemsJson());
            schemas = candidate.schemas().size();
            fields = candidate.schemas().stream().mapToInt(item -> item.fields().size()).sum();
            blockers = (int) problems.stream()
                    .filter(item -> item.severity() == CandidateProblemSeverity.BLOCKER).count();
            warnings = (int) problems.stream()
                    .filter(item -> item.severity() == CandidateProblemSeverity.WARNING).count();
            writeReviewPack(reviewDirectory, authorizedCase, created.runId(), stored.revision(),
                    stored.currentJson(), stored.validationProblemsJson());
        }
        return new CaseEvidence(
                authorizedCase.caseId(), authorizedCase.artifactSha256(), created.runId(),
                finished.state().name(), finished.failureCode().orElse(null), reviewRequired,
                schemas, fields, blockers, warnings,
                attempts.stream().map(attempt -> new AttemptEvidence(
                        attempt.attemptOrdinal(), attempt.stage().name(), attempt.status().name(),
                        attempt.outcomeCode(), attempt.providerModel().orElse(null),
                        attempt.inputTokens(), attempt.outputTokens(),
                        attempt.estimatedCostMicrosCny(), attempt.durationMillis(),
                        attempt.problemCodeCounts())).toList());
    }

    private Map<String, byte[]> loadAuthorizedInputs() throws IOException {
        var configured = requireEnvironment("RENDERWEAVE_IMAGE_ONLY_CERTIFICATION_INPUT_DIRECTORY");
        var directory = Path.of(configured).toRealPath();
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
            throw new IllegalStateException("CANARY_INPUT_DIRECTORY_INVALID");
        }
        var files = new ArrayList<Path>();
        try (var listed = Files.list(directory)) {
            listed.filter(Files::isRegularFile).forEach(files::add);
        }
        if (files.size() != authorization.cases().size()) {
            throw new IllegalStateException("CANARY_INPUT_COUNT_MISMATCH");
        }
        var inputs = new HashMap<String, byte[]>();
        for (var file : files) {
            var bytes = Files.readAllBytes(file);
            var hash = sha256(bytes);
            if (inputs.putIfAbsent(hash, bytes) != null) {
                throw new IllegalStateException("CANARY_INPUT_HASH_DUPLICATE");
            }
        }
        assertThat(inputs.keySet()).containsExactlyInAnyOrderElementsOf(
                authorization.cases().stream()
                        .map(AuthorizedCertificationCase::artifactSha256).toList());
        return Map.copyOf(inputs);
    }

    private void writeReviewPack(
            Path directory,
            AuthorizedCertificationCase authorizedCase,
            UUID runId,
            long candidateRevision,
            String candidateJson,
            String problemsJson
    ) throws IOException {
        var root = json.createObjectNode();
        root.put("version", "renderweave-image-only-certification-review-pack/1.0");
        root.put("authorizationId", authorization.authorizationId());
        root.put("cycleId", authorization.cycleId().toString());
        root.put("profileId", authorization.profileId());
        root.put("caseId", authorizedCase.caseId());
        root.put("artifactSha256", authorizedCase.artifactSha256());
        root.put("runId", runId.toString());
        root.put("candidateRevision", candidateRevision);
        root.put("manualVerdict", "PENDING_OWNER_REVIEW");
        root.put("applyAllowed", false);
        root.set("candidate", json.readTree(candidateJson));
        root.set("problems", json.readTree(problemsJson));
        writeNewAtomically(
                directory.resolve(authorizedCase.caseId() + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private void writeEvidence(
            Path directory,
            List<CaseEvidence> results,
            String lifecycle,
            String haltReason,
            String harnessFailureCode
    ) throws IOException {
        var ledger = stageExecution.snapshot(authorization.authorizationId());
        var summary = new LiveSummary(
                "renderweave-image-only-certification-canary-live/1.0",
                clock.instant(), authorization.authorizationId(), authorization.cycleId(),
                authorization.stage().name(), authorization.profileId(),
                authorization.profileSha256(), authorization.manifestIdentity(),
                authorization.evaluatorIdentity(), "USER_PROVIDED", "ORDINARY_DESIGN",
                lifecycle, "PENDING_OWNER_REVIEW", haltReason, harnessFailureCode,
                authorization.maximumRuns(), authorization.maximumProviderCalls(),
                authorization.maximumModelTokens(), authorization.maximumCostMicrosCny(),
                authorization.maximumProviderCallsPerRun(),
                authorization.maximumCostPerRunMicrosCny(),
                List.copyOf(results), ledger, false, false);
        var encoded = PayloadFreeLiveEvidenceGuard.requirePayloadFree(
                json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        writeAtomically(directory.resolve(EVIDENCE_FILE), encoded);
    }

    private static void requireFreshOutputs(Path evidence, Path review) throws IOException {
        Files.createDirectories(evidence);
        if (Files.exists(evidence.resolve(EVIDENCE_FILE)) || Files.exists(review)) {
            throw new IllegalStateException("CANARY_AUTHORIZATION_ALREADY_EXECUTED");
        }
    }

    private static Path evidenceDirectory() throws IOException {
        var configured = requireEnvironment("RENDERWEAVE_IMAGE_ONLY_CERTIFICATION_EVIDENCE_DIRECTORY");
        var directory = Path.of(configured).toAbsolutePath().normalize();
        var root = repositoryRoot().resolve(".sdlc").resolve("evidence").toRealPath();
        if (!directory.startsWith(root)) {
            throw new IllegalStateException("CANARY_EVIDENCE_DIRECTORY_INVALID");
        }
        return directory;
    }

    private static Path reviewDirectory() {
        return repositoryRoot().resolve(".scratch").resolve("image-only-certification-reviews")
                .resolve(AUTHORIZATION_ID);
    }

    private static String requireEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + "_REQUIRED");
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String harnessFailureCode(RuntimeException failure) {
        var simple = failure.getClass().getSimpleName()
                .replaceAll("[^A-Za-z0-9]", "_").toUpperCase(java.util.Locale.ROOT);
        var code = "HARNESS_" + (simple.isBlank() ? "RUNTIME_FAILURE" : simple);
        return code.length() <= 128 ? code : code.substring(0, 128);
    }

    private static void writeAtomically(Path destination, String content) throws IOException {
        var temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeNewAtomically(Path destination, String content) throws IOException {
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) {
            throw new IllegalStateException("CANARY_REVIEW_PACK_ALREADY_EXISTS");
        }
        var temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static CertificationCycleFixture loadCycleFixture() {
        try {
            var path = repositoryRoot().resolve("plans").resolve("image-only-certification-cycles")
                    .resolve("c3bde304-b0b2-43f8-ab7e-16896ff04aed.json");
            var root = new ObjectMapper().readTree(Files.readAllBytes(path));
            var canaries = new ArrayList<CertificationCanaryCase>();
            for (var item : root.path("cases")) {
                canaries.add(new CertificationCanaryCase(
                        item.path("caseId").asText(), item.path("artifactSha256").asText()));
            }
            var manifest = new ImageOnlyCertificationManifestFactory().create(
                    root.path("profileSha256").asText(), canaries,
                    root.path("assignmentSeed").asText());
            if (!manifest.manifestIdentity().equals(root.path("manifestIdentity").asText())
                    || !manifest.evaluatorIdentity().equals(root.path("evaluatorIdentity").asText())) {
                throw new IllegalStateException("CANARY_CYCLE_MANIFEST_DRIFT");
            }
            var cycle = new FrozenCertificationCycle(
                    UUID.fromString(root.path("cycleId").asText()),
                    root.path("profileId").asText(), root.path("profileSha256").asText(),
                    root.path("manifestIdentity").asText(), root.path("evaluatorIdentity").asText(),
                    root.path("authorityInventorySha256").asText(),
                    Instant.parse(root.path("createdAt").asText()));
            return new CertificationCycleFixture(cycle, manifest);
        } catch (IOException failure) {
            throw new IllegalStateException("CANARY_CYCLE_CANNOT_BE_READ", failure);
        }
    }

    private static ImageOnlyCertificationAuthorization loadAuthorization() {
        try {
            var path = repositoryRoot().resolve("plans").resolve("live-canary-authorizations")
                    .resolve("20260817-image-only-canary-5-c3bde304.json");
            return new ImageOnlyCertificationAuthorizationJsonCodec().read(Files.readAllBytes(path));
        } catch (IOException failure) {
            throw new IllegalStateException("CANARY_AUTHORIZATION_CANNOT_BE_READ", failure);
        }
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve(".sdlc"))) {
            return current;
        }
        var parent = current.getParent();
        if (parent != null && Files.exists(parent.resolve("pom.xml"))
                && Files.isDirectory(parent.resolve(".sdlc"))) {
            return parent;
        }
        throw new IllegalStateException("Repository root cannot be located");
    }

    private record CertificationCycleFixture(
            FrozenCertificationCycle cycle,
            FrozenImageOnlyCertificationManifest manifest
    ) { }

    private record LiveSummary(
            String version,
            Instant generatedAt,
            String authorizationId,
            UUID cycleId,
            String stage,
            String profileId,
            String profileSha256,
            String manifestIdentity,
            String evaluatorIdentity,
            String inputProvenance,
            String dataClassification,
            String lifecycle,
            String manualReviewStatus,
            String haltReason,
            String harnessFailureCode,
            int maximumRuns,
            int maximumProviderCalls,
            long maximumModelTokens,
            long maximumCostMicrosCny,
            int maximumProviderCallsPerRun,
            long maximumCostPerRunMicrosCny,
            List<CaseEvidence> cases,
            CertificationStageLedgerSnapshot ledger,
            boolean candidateApplied,
            boolean staticSchemaPublished
    ) { }

    private record CaseEvidence(
            String caseId,
            String artifactSha256,
            UUID runId,
            String state,
            String failureCode,
            boolean reviewRequired,
            int candidateSchemas,
            int candidateFields,
            int blockerProblems,
            int warningProblems,
            List<AttemptEvidence> attempts
    ) { }

    private record AttemptEvidence(
            int attemptOrdinal,
            String stage,
            String status,
            String outcomeCode,
            String providerModel,
            long inputTokens,
            long outputTokens,
            long costMicrosCny,
            long durationMillis,
            Map<String, Integer> problemCodeCounts
    ) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class LiveConfiguration {
        @Bean
        ImageOnlyCertificationAuthorization imageOnlyCertificationAuthorization() {
            return loadAuthorization();
        }

        @Bean
        CertificationCycleFixture certificationCycleFixture() {
            return loadCycleFixture();
        }

        @Bean
        CertificationStageExecutionService certificationStageExecutionService(
                PostgresCertificationStageExecutionStore store
        ) {
            return new CertificationStageExecutionService(store);
        }

        @Bean
        @Primary
        InferenceProvider certificationInferenceProvider(
                @Qualifier("dashScopeInferenceProvider") InferenceProvider delegate,
                CertificationStageExecutionService execution,
                ImageOnlyCertificationAuthorization authorization,
                Clock clock
        ) {
            return new CertificationInferenceProvider(
                    delegate, execution, authorization.authorizationId(), clock);
        }
    }
}
