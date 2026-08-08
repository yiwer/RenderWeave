package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblemJsonCodec;
import cn.hbads.renderweave.inference.eval.LiveCandidateEvaluator;
import cn.hbads.renderweave.inference.eval.LiveCertificationDecision;
import cn.hbads.renderweave.inference.eval.LiveCertificationPolicy;
import cn.hbads.renderweave.inference.eval.LiveEvaluationCorpus;
import cn.hbads.renderweave.inference.eval.LiveEvaluationReport;
import cn.hbads.renderweave.inference.eval.LiveEvaluationReporter;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicitly opt-in paid certification batch. Normal builds skip it even when a credential exists.
 * A repository authorization must also be OPEN, current and synthetic-only before the first test action.
 */
@Testcontainers
@SpringBootTest(properties = {
        "renderweave.inference.live-enabled=false",
        "renderweave.inference.live-upload-enabled=false",
        "renderweave.inference.blob-root=target/dashscope-live-certification-blobs",
        "renderweave.inference.live-poll-millis=60000"
})
@Import(DashScopeLiveCertificationTest.CertificationConfiguration.class)
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_LIVE_CERTIFICATION", matches = "true")
class DashScopeLiveCertificationTest {
    private static final String REPORT_VERSION = "renderweave-live-certification-report/1.0";

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
    private ReplayFixtureInputFactory fixtures;

    @Autowired
    private ProviderBudgetStore budgets;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Clock clock;

    @Autowired
    private LiveCertificationAuthorization authorization;

    @Autowired
    private LiveCertificationEvaluationIdentity evaluationIdentity;

    @Autowired
    private LiveCertificationJournal journal;

    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();
    private final LiveEvaluationCorpus corpus = new LiveEvaluationCorpus();
    private final LiveCandidateEvaluator evaluator = new LiveCandidateEvaluator();
    private final LiveEvaluationReporter reporter = new LiveEvaluationReporter();
    private final LiveCertificationPolicy policy = new LiveCertificationPolicy();
    private final CandidateJsonCodec candidateCodec = new CandidateJsonCodec();
    private final CandidateProblemJsonCodec problemCodec = new CandidateProblemJsonCodec();

    @Test
    void runsAtMostOneAuthorizedResumableBatchAndWritesPayloadFreeEvidence() throws Exception {
        var startedAt = clock.instant();
        authorization.requireOpen(startedAt);
        evaluationIdentity.requireCurrent(authorization.evaluationIdentity());
        try (var ignored = journal.acquireBatchLease(startedAt)) {
            recoverInterruptedAssignments(startedAt);
            resetEphemeralDatabaseBudget();

            var completed = journal.completedAssignmentKeys();
            var pending = authorization.assignments(corpus).stream()
                    .filter(item -> !completed.contains(item.key()))
                    .limit(authorization.maximumCasesPerBatch())
                    .toList();
            var processed = 0;
            String haltReason = null;
            String harnessFailureCode = null;

            for (var assignment : pending) {
                try {
                    var result = execute(assignment);
                    processed++;
                    writeSummary(processed, haltReason, null);
                    if (!InferenceRunState.REVIEW_REQUIRED.name().equals(result.runState())) {
                        haltReason = result.failureCode() == null
                                ? "LIVE_CERTIFICATION_NON_REVIEWABLE_RUN"
                                : result.failureCode();
                        break;
                    }
                } catch (RuntimeException failure) {
                    harnessFailureCode = harnessFailureCode(failure);
                    haltReason = harnessFailureCode;
                    if (completeHarnessFailureIfPossible(assignment, harnessFailureCode)) processed++;
                    break;
                }
            }

            writeSummary(processed, haltReason, harnessFailureCode);
            var budget = budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY);
            assertThat(processed).isLessThanOrEqualTo(authorization.maximumCasesPerBatch());
            assertThat(budget.consumedAttempts()).isLessThanOrEqualTo(authorization.maximumProviderAttempts());
            assertThat(budget.consumedCostMicrosCny()).isLessThanOrEqualTo(
                    authorization.maximumCostMicrosCny()
            );
            assertThat(harnessFailureCode).isNull();
        }
    }

    private LiveCertificationJournal.CaseResult execute(
            LiveCertificationAuthorization.Assignment assignment
    ) {
        var now = clock.instant();
        var gold = assignment.evaluationCase();
        journal.beginAssignment(assignment.key(), assignment.profileId(), gold.caseId(), now);
        var replayInput = fixtures.create(gold.fixtureId(), true);
        var liveInput = new InferenceInput(
                replayInput.mode(), assignment.profileId(), gold.fixtureId(), true,
                replayInput.images(), replayInput.jsonSamples()
        );
        var profile = profiles.require(assignment.profileId());
        var created = runService.create(
                "cert-" + assignment.profileId() + "-" + gold.caseId(),
                liveInput,
                profile.snapshotJson()
        ).run();
        journal.bindRun(assignment.key(), created.runId(), clock.instant());
        var finished = worker.processNext("certification-worker").orElseThrow();
        if (!finished.runId().equals(created.runId())) {
            throw new IllegalStateException("Certification worker claimed an unexpected run");
        }
        var attempts = workflowStore.attempts(created.runId());
        var stored = workflowStore.findCandidate(created.runId());
        var evaluation = stored.isPresent()
                ? evaluator.evaluate(
                        gold,
                        candidateCodec.parse(stored.orElseThrow().currentJson()),
                        problemCodec.parse(stored.orElseThrow().validationProblemsJson())
                )
                : evaluator.failure(gold, finished.failureCode().orElse("LIVE_CANDIDATE_MISSING"));
        var result = new LiveCertificationJournal.CaseResult(
                assignment.key(), assignment.profileId(), gold.caseId(), finished.state().name(),
                finished.failureCode().orElse(null),
                LiveCertificationJournal.EvaluationMetrics.from(evaluation),
                attempts.stream().map(attempt -> new LiveCertificationJournal.AttemptResult(
                        attempt.attemptOrdinal(), attempt.stage().name(), attempt.status().name(),
                        attempt.outcomeCode(), attempt.providerModel().orElse(null),
                        attempt.inputTokens(), attempt.outputTokens(),
                        attempt.estimatedCostMicrosCny(), attempt.durationMillis()
                )).toList(), clock.instant().toString()
        );
        journal.completeCase(result, clock.instant());
        return result;
    }

    private void recoverInterruptedAssignments(Instant now) {
        for (var execution : journal.inProgressExecutions()) {
            if (execution.runId() == null || !journal.hasReservationForRun(execution.runId())) {
                journal.discardUnreservedAssignment(execution.assignmentKey(), now);
                continue;
            }
            var gold = corpus.require(execution.caseId());
            journal.completeCase(new LiveCertificationJournal.CaseResult(
                    execution.assignmentKey(), execution.profileId(), execution.caseId(),
                    InferenceRunState.FAILED.name(), "HARNESS_INTERRUPTED_AFTER_RESERVATION",
                    LiveCertificationJournal.EvaluationMetrics.from(
                            evaluator.failure(gold, "HARNESS_INTERRUPTED_AFTER_RESERVATION")
                    ),
                    List.of(), now.toString()
            ), now);
        }
    }

    private boolean completeHarnessFailureIfPossible(
            LiveCertificationAuthorization.Assignment assignment,
            String failureCode
    ) {
        var execution = journal.inProgressExecutions().stream()
                .filter(item -> item.assignmentKey().equals(assignment.key()))
                .findFirst();
        if (execution.isEmpty()) return false;
        journal.completeCase(new LiveCertificationJournal.CaseResult(
                assignment.key(), assignment.profileId(), assignment.evaluationCase().caseId(),
                InferenceRunState.FAILED.name(), failureCode,
                LiveCertificationJournal.EvaluationMetrics.from(
                        evaluator.failure(assignment.evaluationCase(), failureCode)
                ),
                List.of(), clock.instant().toString()
        ), clock.instant());
        return true;
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
                .param("budgetKey", LiveInferenceWorker.CANARY_BUDGET_KEY)
                .update();
        if (updated != 1) throw new IllegalStateException("Certification database budget is unavailable");
    }

    private void writeSummary(
            int processedInBatch,
            String haltReason,
            String harnessFailureCode
    ) throws IOException {
        var profileReports = new ArrayList<ProfileSummary>();
        for (var profileId : authorization.profileIds()) {
            var results = journal.resultsFor(profileId).stream()
                    .map(LiveCertificationJournal.CaseResult::evaluation)
                    .map(LiveCertificationJournal.EvaluationMetrics::toResult)
                    .toList();
            var report = reporter.report(profileId, corpus, results);
            profileReports.add(new ProfileSummary(
                    profileId,
                    profiles.require(profileId).profile().model(),
                    report,
                    policy.decide(profileId, corpus, results)
            ));
        }
        var budget = budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY);
        var summary = new CertificationSummary(
                REPORT_VERSION,
                clock.instant().toString(),
                authorization.authorizationId(),
                authorization.status(),
                authorization.inputClassification(),
                authorization.corpusVersion(),
                authorization.evaluationIdentity(),
                corpus.cases().size(),
                authorization.maximumCasesPerBatch(),
                processedInBatch,
                haltReason,
                harnessFailureCode,
                budget.maximumAttempts(),
                budget.consumedAttempts(),
                budget.maximumCostMicrosCny(),
                budget.consumedCostMicrosCny(),
                List.copyOf(profileReports)
        );
        writeAtomically(evidenceDirectory().resolve("summary.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    private static void writeAtomically(Path destination, String content) throws IOException {
        Files.createDirectories(destination.getParent());
        var temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(
                    temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
            );
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

    private Path evidenceDirectory() {
        return repositoryRoot().resolve(".sdlc").resolve("evidence")
                .resolve(authorization.authorizationId());
    }

    private static Path authorizationFile() {
        return repositoryRoot().resolve("plans").resolve("live-certification-authorizations")
                .resolve("p5-certification-20260808.json");
    }

    private static String harnessFailureCode(RuntimeException failure) {
        var simpleName = failure.getClass().getSimpleName()
                .replaceAll("[^A-Za-z0-9]", "_")
                .toUpperCase(Locale.ROOT);
        var value = "HARNESS_" + (simpleName.isBlank() ? "RUNTIME_FAILURE" : simpleName);
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve(".sdlc"))) {
            return current;
        }
        var parent = current.getParent();
        if (parent != null && Files.exists(parent.resolve("pom.xml"))
                && Files.isDirectory(parent.resolve(".sdlc"))) {
            return parent;
        }
        throw new IllegalStateException("Repository root cannot be located for certification evidence");
    }

    private record CertificationSummary(
            String reportVersion,
            String generatedAt,
            String authorizationId,
            String authorizationStatus,
            String inputClassification,
            String evaluationCorpusVersion,
            String evaluationIdentity,
            int evaluationCorpusCaseCount,
            int maximumCasesPerBatch,
            int processedCasesInBatch,
            String haltReason,
            String harnessFailureCode,
            int maximumProviderAttempts,
            int consumedProviderAttempts,
            long maximumCostMicrosCny,
            long consumedCostMicrosCny,
            List<ProfileSummary> profiles
    ) { }

    private record ProfileSummary(
            String profileId,
            String model,
            LiveEvaluationReport report,
            LiveCertificationDecision decision
    ) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class CertificationConfiguration {
        @Bean
        LiveCertificationAuthorization liveCertificationAuthorization(ObjectMapper json) {
            return LiveCertificationAuthorization.load(authorizationFile(), json);
        }

        @Bean
        LiveCertificationEvaluationIdentity liveCertificationEvaluationIdentity() {
            return new LiveCertificationEvaluationIdentity(repositoryRoot(), authorizationFile());
        }

        @Bean
        LiveCertificationJournal liveCertificationJournal(
                LiveCertificationAuthorization authorization,
                LiveCertificationEvaluationIdentity evaluationIdentity,
                ObjectMapper json,
                Clock clock
        ) {
            var now = clock.instant();
            authorization.requireOpen(now);
            evaluationIdentity.requireCurrent(authorization.evaluationIdentity());
            return new LiveCertificationJournal(
                    repositoryRoot().resolve(".sdlc").resolve("evidence")
                            .resolve(authorization.authorizationId()),
                    authorization,
                    json,
                    now
            );
        }

        @Bean
        @Primary
        ProviderBudgetStore certificationBudgetStore(
                LiveCertificationAuthorization authorization,
                LiveCertificationJournal journal,
                PostgresProviderBudgetStore delegate
        ) {
            return new JournaledCertificationBudgetStore(authorization, journal, delegate);
        }
    }
}
