package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageEvaluator;
import cn.hbads.renderweave.inference.eval.visual.VisualStageRasterizer;
import cn.hbads.renderweave.inference.eval.visual.VisualStageReporter;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import cn.hbads.renderweave.inference.live.VisualStageCheckpointReader;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.run.InferenceRunService;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly opt-in paid visual stage batch. Ordinary server/eval gates always skip this class. */
@Testcontainers
@SpringBootTest(properties = {
        "renderweave.inference.live-enabled=false",
        "renderweave.inference.live-upload-enabled=false",
        "renderweave.inference.recovery-enabled=false",
        "renderweave.inference.blob-root=target/dashscope-visual-evaluation-blobs",
        "renderweave.inference.live-poll-millis=60000"
})
@Import(DashScopeVisualEvaluationTest.VisualEvaluationConfiguration.class)
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_VISUAL_EVALUATION", matches = "true")
class DashScopeVisualEvaluationTest {
    static final String BATCH_LIMIT_PROPERTY = "renderweave.visual-evaluation.batch-limit";
    private static final List<String> HALT_FAILURE_CODES = List.of(
            "DASHSCOPE_NETWORK_ERROR", "DASHSCOPE_RATE_LIMITED", "DASHSCOPE_TIMEOUT",
            "PROVIDER_ATTEMPT_BUDGET_EXHAUSTED", "PROVIDER_COST_BUDGET_EXHAUSTED",
            "VISUAL_EVALUATION_GOAL_BUDGET_EXCEEDED", "PROVIDER_NOT_CONFIGURED"
    );

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private InferenceRunService runService;
    @Autowired private LiveInferenceWorker worker;
    @Autowired private InferenceRunStore runs;
    @Autowired private InferenceReplayStore workflowStore;
    @Autowired private InferenceProfileRegistry profiles;
    @Autowired private VisualEvaluationAuthorization authorization;
    @Autowired private VisualEvaluationGoalBudget goalBudget;
    @Autowired private VisualEvaluationJournal journal;
    @Autowired private InferenceProvider provider;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;

    private final VisualStageCorpus corpus = new VisualStageCorpus();
    private final VisualStageRasterizer rasterizer = new VisualStageRasterizer();
    private final VisualStageCheckpointReader checkpointReader = new VisualStageCheckpointReader();
    private final VisualStageEvaluator evaluator = new VisualStageEvaluator();
    private final VisualStageReporter reporter = new VisualStageReporter();

    @Test
    void executesAtMostOneAuthorizedBatchAndPersistsIndependentlyVerifiableEvidence()
            throws Exception {
        var now = clock.instant();
        authorization.requireOpen(now);
        assertThat(provider.configured()).as("DashScope Provider must be explicitly configured").isTrue();
        try (var ignored = journal.acquireBatchLease(now)) {
            var recovery = journal.recoverInterrupted(goalBudget, now);
            assertThat(recovery.abandonedCaseIds()).as("no reserved case may be silently replayed").isEmpty();
            resetEphemeralDatabaseBudget();
            var terminal = journal.terminalAssignmentKeys();
            var batchLimit = effectiveBatchLimit(
                    authorization.maximumCasesPerBatch(), System.getProperty(BATCH_LIMIT_PROPERTY)
            );
            var pending = authorization.caseIds().stream()
                    .filter(caseId -> !terminal.contains(authorization.profileId() + "|" + caseId))
                    .limit(batchLimit).toList();
            var processed = 0;
            for (var caseId : pending) {
                var finished = execute(corpus.require(caseId));
                processed++;
                writeReport();
                if (finished.failureCode().map(DashScopeVisualEvaluationTest::shouldHalt).orElse(false)) break;
            }
            writeReport();
            assertThat(processed).isLessThanOrEqualTo(batchLimit);
            var budget = goalBudget.snapshot(authorization.model(), authorization.authorizationId());
            assertThat(budget.goal().tokens())
                    .isLessThanOrEqualTo(VisualEvaluationAuthorization.GOAL_MAXIMUM_TOKENS_PER_MODEL);
            assertThat(budget.goal().costMicrosCny())
                    .isLessThanOrEqualTo(VisualEvaluationAuthorization
                            .goalMaximumCostMicrosCny(authorization.model()));
            assertThat(budget.breached()).isFalse();
        }
    }

    static int effectiveBatchLimit(int authorizedMaximum, String requested) {
        if (authorizedMaximum < 1 || authorizedMaximum > 5) {
            throw new IllegalArgumentException("VISUAL_EVALUATION_AUTHORIZED_BATCH_LIMIT_INVALID");
        }
        if (requested == null || requested.isBlank()) return authorizedMaximum;
        try {
            var parsed = Integer.parseInt(requested);
            if (parsed < 1 || parsed > authorizedMaximum) {
                throw new IllegalArgumentException("VISUAL_EVALUATION_BATCH_LIMIT_INVALID");
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("VISUAL_EVALUATION_BATCH_LIMIT_INVALID", invalid);
        }
    }

    static boolean shouldHalt(String failureCode) {
        return failureCode != null && (HALT_FAILURE_CODES.contains(failureCode)
                || failureCode.matches("DASHSCOPE_HTTP_[1-5][0-9]{2}"));
    }

    private cn.hbads.renderweave.inference.run.InferenceRunSnapshot execute(
            VisualStageCorpus.EvaluationCase evaluationCase
    ) {
        var now = clock.instant();
        var assignment = authorization.profileId() + "|" + evaluationCase.caseId();
        var executionId = journal.beginAssignment(evaluationCase.caseId(), now);
        var rendered = rasterizer.render(evaluationCase);
        var input = new InferenceInput(
                InferenceMode.IMAGE_ONLY, authorization.profileId(), evaluationCase.caseId(), true,
                List.of(new InferenceInput.BinaryInput(
                        evaluationCase.caseId() + ".png", rendered.mediaType(), rendered.bytes()
                )), List.of()
        );
        var profile = profiles.require(authorization.profileId());
        var created = runService.create(
                "visual-eval-" + authorization.authorizationId() + "-" + evaluationCase.caseId(),
                input, profile.snapshotJson()
        ).run();
        journal.bindRun(assignment, executionId, created.runId(), clock.instant());
        var finished = worker.processNext("visual-evaluation-worker").orElseThrow();
        if (!finished.runId().equals(created.runId())) {
            throw new IllegalStateException("Visual evaluation worker claimed an unexpected run");
        }
        var attempts = workflowStore.attempts(created.runId());
        var finalRun = runs.find(created.runId()).orElseThrow();
        var snapshot = checkpointReader.read(finalRun.checkpointJson(), attempts.size());
        var stageResult = finalRun.failureCode()
                .map(code -> evaluator.evaluateFailure(evaluationCase, snapshot, code))
                .orElseGet(() -> evaluator.evaluate(evaluationCase, snapshot));
        var reservations = goalBudget.reservationsForRun(created.runId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        VisualEvaluationGoalBudget.Reservation::attemptOrdinal, item -> item
                ));
        var evidenceAttempts = attempts.stream().map(attempt -> {
            var reservation = reservations.get(attempt.attemptOrdinal());
            if (reservation == null) {
                throw new IllegalStateException("Visual evaluation attempt lacks Goal reservation");
            }
            return new VisualEvaluationJournal.AttemptResult(
                    reservation.reservationId(), attempt.attemptOrdinal(), attempt.stage().name(),
                    attempt.outcomeCode(), authorization.model(), reservation.actualInputTokens(),
                    reservation.actualOutputTokens(), reservation.actualCostMicrosCny(),
                    attempt.durationMillis(), attempt.problemCodeCounts()
            );
        }).toList();
        journal.completeCase(
                assignment, executionId, created.runId(), stageResult, evidenceAttempts,
                goalBudget, clock.instant()
        );
        return finished;
    }

    private void resetEphemeralDatabaseBudget() {
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
        var updated = jdbcClient.sql("""
                        update inference_provider_budget
                        set maximum_attempts = :maximumAttempts,
                            maximum_cost_micros_cny = :maximumCost
                        where budget_key = :budgetKey
                        """)
                .param("maximumAttempts", authorization.maximumProviderAttempts())
                .param("maximumCost", authorization.maximumCostMicrosCny())
                .param("budgetKey", LiveInferenceWorker.PRODUCT_BUDGET_KEY)
                .update();
        if (updated != 1) throw new IllegalStateException("Product Provider budget is unavailable");
    }

    private void writeReport() throws IOException {
        var report = reporter.report(corpus, journal.completedResults());
        writeAtomically(evidenceDirectory().resolve("report.json"),
                PayloadFreeLiveEvidenceGuard.requirePayloadFree(
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(report)));
    }

    private static void writeAtomically(Path destination, String content) throws IOException {
        Files.createDirectories(destination.getParent());
        var temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                var bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IllegalStateException("VISUAL_EVALUATION_ATOMIC_MOVE_REQUIRED", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path evidenceDirectory() {
        return repositoryRoot().resolve(".sdlc/evidence").resolve(authorization.authorizationId());
    }

    static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        while (current != null && (!Files.isRegularFile(current.resolve("pom.xml"))
                || !Files.isDirectory(current.resolve(".sdlc")))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Visual evaluation repository is unavailable");
        return current;
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record Preflight(
            VisualEvaluationAuthorization authorization,
            VisualEvaluationIdentity identity,
            InferenceProfileRegistry.ProfileResource profile
    ) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class VisualEvaluationConfiguration {
        @Bean
        VisualEvaluationAuthorization visualEvaluationAuthorization(ObjectMapper json) {
            return VisualEvaluationAuthorization.load(
                    VisualEvaluationAuthorizationLocator.resolve(repositoryRoot()), json
            );
        }

        @Bean
        VisualEvaluationIdentity visualEvaluationIdentity() {
            return new VisualEvaluationIdentity(
                    repositoryRoot(), VisualEvaluationAuthorizationLocator.all(repositoryRoot())
            );
        }

        @Bean
        Preflight visualEvaluationPreflight(
                VisualEvaluationAuthorization authorization,
                VisualEvaluationIdentity identity,
                InferenceProfileRegistry profiles,
                Clock clock
        ) {
            var now = clock.instant();
            authorization.requireOpen(now);
            identity.requireCurrent(authorization.evaluationIdentity());
            authorization.requireCorpus(new VisualStageCorpus());
            var profile = profiles.require(authorization.profileId());
            authorization.requireProfileSnapshot(sha256(profile.snapshotJson()));
            if (!authorization.model().equals(profile.profile().model())) {
                throw new IllegalStateException("VISUAL_EVALUATION_PROFILE_MODEL_MISMATCH");
            }
            return new Preflight(authorization, identity, profile);
        }

        @Bean
        VisualEvaluationGoalBudget visualEvaluationGoalBudget(
                Preflight preflight,
                ObjectMapper json,
                Clock clock
        ) {
            Objects.requireNonNull(preflight, "preflight");
            return new VisualEvaluationGoalBudget(
                    repositoryRoot().resolve(".sdlc/evidence")
                            .resolve(VisualEvaluationGoalBudget.GOAL_ID),
                    json, clock.instant()
            );
        }

        @Bean
        VisualEvaluationJournal visualEvaluationJournal(
                Preflight preflight,
                VisualEvaluationGoalBudget goalBudget,
                ObjectMapper json,
                Clock clock
        ) {
            Objects.requireNonNull(goalBudget, "goalBudget");
            return new VisualEvaluationJournal(
                    repositoryRoot().resolve(".sdlc/evidence")
                            .resolve(preflight.authorization().authorizationId()),
                    preflight.authorization(), new VisualStageCorpus(), json, clock.instant()
            );
        }

        @Bean
        @Primary
        InferenceProvider visualEvaluationProvider(
                Preflight preflight,
                VisualEvaluationGoalBudget goalBudget,
                @Qualifier("dashScopeInferenceProvider") InferenceProvider delegate,
                Clock clock
        ) {
            return new GoalBudgetInferenceProvider(
                    preflight.authorization(), goalBudget, delegate, clock
            );
        }
    }
}
