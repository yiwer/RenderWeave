package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblemJsonCodec;
import cn.hbads.renderweave.inference.eval.LiveCandidateEvaluator;
import cn.hbads.renderweave.inference.eval.LiveEvaluationCorpus;
import cn.hbads.renderweave.inference.eval.LiveEvaluationResult;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.run.InferenceRunService;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicitly opt-in paid canary. Normal builds skip this class even when a credential exists.
 * Evidence contains metrics and provider telemetry only; Candidate/model output is deliberately excluded.
 */
@Testcontainers
@SpringBootTest(properties = {
        "renderweave.inference.live-enabled=false",
        "renderweave.inference.blob-root=target/dashscope-live-canary-blobs",
        "renderweave.inference.live-poll-millis=60000"
})
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_LIVE_CANARY", matches = "true")
class DashScopeLiveCanaryTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private InferenceRunService runService;

    @Autowired
    private LiveInferenceWorker worker;

    @Autowired
    private InferenceRunStore runs;

    @Autowired
    private InferenceReplayStore workflowStore;

    @Autowired
    private ProviderBudgetStore budgets;

    @Autowired
    private ReplayFixtureInputFactory fixtures;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper json;

    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();
    private final LiveEvaluationCorpus corpus = new LiveEvaluationCorpus();
    private final LiveCandidateEvaluator evaluator = new LiveCandidateEvaluator();
    private final CandidateJsonCodec candidateCodec = new CandidateJsonCodec();
    private final CandidateProblemJsonCodec problemCodec = new CandidateProblemJsonCodec();

    @Test
    void twoModelsRunAgainstRepositorySyntheticGoldWithinTheAuthorizedGlobalBudget() throws Exception {
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
        var cases = List.of(
                new CanaryCase("dashscope-qwen37-flash-v1", "live-json-01-scalars"),
                new CanaryCase("dashscope-qwen38-max-v1", "live-json-02-nested-object")
        );
        var results = new ArrayList<CanaryResult>();
        String harnessFailureCode = null;

        try {
            for (var selected : cases) {
                var gold = corpus.require(selected.evaluationCaseId());
                var replayInput = fixtures.create(gold.fixtureId(), true);
                var liveInput = new InferenceInput(
                        replayInput.mode(), selected.profileId(), gold.fixtureId(), true,
                        replayInput.images(), replayInput.jsonSamples()
                );
                var profile = profiles.require(selected.profileId());
                var created = runService.create(
                        "canary-" + selected.profileId(), liveInput, profile.snapshotJson()
                ).run();
                var finished = worker.processNext("canary-worker-" + selected.profileId()).orElseThrow();
                var attempts = workflowStore.attempts(created.runId());
                LiveEvaluationResult evaluation = null;
                var stored = workflowStore.findCandidate(created.runId());
                if (stored.isPresent()) {
                    evaluation = evaluator.evaluate(
                            gold,
                            candidateCodec.parse(stored.orElseThrow().currentJson()),
                            problemCodec.parse(stored.orElseThrow().validationProblemsJson())
                    );
                }
                results.add(new CanaryResult(
                        gold.caseId(), selected.profileId(), profile.profile().model(),
                        finished.state().name(), finished.failureCode().orElse(null), evaluation,
                        attempts.stream().map(attempt -> new AttemptResult(
                                attempt.attemptOrdinal(), attempt.stage().name(), attempt.status().name(),
                                attempt.outcomeCode(), attempt.providerRequestId().orElse(null),
                                attempt.providerModel().orElse(null), attempt.inputTokens(), attempt.outputTokens(),
                                attempt.estimatedCostMicrosCny(), attempt.durationMillis()
                        )).toList()
                ));
            }
        } catch (RuntimeException failure) {
            harnessFailureCode = "HARNESS_" + failure.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
        }

        var budget = budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY);
        var summary = new CanarySummary(
                "renderweave-dashscope-canary/1.0", Instant.now().toString(),
                "REPOSITORY_SYNTHETIC_ONLY", LiveEvaluationCorpus.VERSION, corpus.cases().size(),
                false, harnessFailureCode, budget.maximumAttempts(), budget.consumedAttempts(),
                budget.maximumCostMicrosCny(), budget.consumedCostMicrosCny(), List.copyOf(results)
        );
        writeEvidence(summary);

        assertThat(harnessFailureCode).isNull();
        assertThat(budget.consumedAttempts()).isBetween(2, 6);
        assertThat(budget.consumedCostMicrosCny()).isLessThanOrEqualTo(1_000_000);
        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED.name());
            assertThat(result.failureCode()).isNull();
            assertThat(result.evaluation()).isNotNull();
            assertThat(result.evaluation().passed()).isTrue();
        });
    }

    private void writeEvidence(CanarySummary summary) throws Exception {
        var timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        var directory = repositoryRoot().resolve(".sdlc").resolve("evidence")
                .resolve(timestamp + "-p5-live-canary");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("summary.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
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
        throw new IllegalStateException("Repository root cannot be located for safe canary evidence");
    }

    private record CanaryCase(String profileId, String evaluationCaseId) { }

    private record CanarySummary(
            String reportVersion,
            String generatedAt,
            String inputClassification,
            String evaluationCorpusVersion,
            int evaluationCorpusCaseCount,
            boolean certificationComplete,
            String harnessFailureCode,
            int maximumAttempts,
            int consumedAttempts,
            long maximumCostMicrosCny,
            long consumedCostMicrosCny,
            List<CanaryResult> results
    ) { }

    private record CanaryResult(
            String caseId,
            String profileId,
            String model,
            String state,
            String failureCode,
            LiveEvaluationResult evaluation,
            List<AttemptResult> attempts
    ) { }

    private record AttemptResult(
            int ordinal,
            String stage,
            String status,
            String outcomeCode,
            String providerRequestId,
            String providerModel,
            long inputTokens,
            long outputTokens,
            long estimatedCostMicrosCny,
            long durationMillis
    ) { }
}
