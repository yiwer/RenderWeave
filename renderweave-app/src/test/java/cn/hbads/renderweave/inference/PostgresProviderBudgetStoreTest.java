package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.input.NormalizedInputReference;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.ProviderBudgetExceededException;
import cn.hbads.renderweave.inference.provider.ProviderBudgetReservation;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.NewInferenceRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class PostgresProviderBudgetStoreTest {
    private static final String BUDGET = "p5-synthetic-canary";
    private static final Instant T0 = Instant.parse("2026-08-08T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProviderBudgetStore budgets;

    @Autowired
    private InferenceRunStore runs;

    @Autowired
    private JdbcClient jdbcClient;

    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();

    @BeforeEach
    void clearData() {
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
        jdbcClient.sql("""
                        update inference_provider_budget
                        set maximum_attempts = 6, maximum_cost_micros_cny = 1000000
                        where budget_key = 'p5-synthetic-canary'
                        """).update();
    }

    @Test
    void queueClaimsKeepReplayAndLiveWorkersSeparated() {
        var replay = createRun("replay-v1", "queue-replay");
        var live = createRun("dashscope-qwen37-flash-v1", "queue-live");

        var replayClaim = runs.claimNext("replay-worker", T0.plusSeconds(1), Duration.ofSeconds(30))
                .orElseThrow();
        var liveClaim = runs.claimNextLive("live-worker", T0.plusSeconds(1), Duration.ofMinutes(5))
                .orElseThrow();

        assertThat(replayClaim.runId()).isEqualTo(replay);
        assertThat(liveClaim.runId()).isEqualTo(live);
        assertThat(runs.claimNext("replay-empty", T0.plusSeconds(2), Duration.ofSeconds(30))).isEmpty();
        assertThat(runs.claimNextLive("live-empty", T0.plusSeconds(2), Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void costReservationIsFailClosedAndSettlementCanOnlyReleaseKnownUnusedCost() {
        var firstRun = createRun("dashscope-qwen38-max-v1", "cost-1");
        var secondRun = createRun("dashscope-qwen38-max-v1", "cost-2");
        var thirdRun = createRun("dashscope-qwen38-max-v1", "cost-3");
        var fourthRun = createRun("dashscope-qwen38-max-v1", "cost-4");
        var first = budgets.reserve(BUDGET, firstRun, 0, 280_000, T0);
        budgets.reserve(BUDGET, secondRun, 0, 280_000, T0);
        budgets.reserve(BUDGET, thirdRun, 0, 280_000, T0);

        assertThatThrownBy(() -> budgets.reserve(BUDGET, fourthRun, 0, 280_000, T0))
                .isInstanceOf(ProviderBudgetExceededException.class)
                .extracting(failure -> ((ProviderBudgetExceededException) failure).code())
                .isEqualTo("PROVIDER_COST_BUDGET_EXHAUSTED");

        budgets.settle(first.reservationId(), 50_000, T0.plusSeconds(1));
        budgets.reserve(BUDGET, fourthRun, 0, 280_000, T0.plusSeconds(2));
        var snapshot = budgets.snapshot(BUDGET);
        assertThat(snapshot.consumedAttempts()).isEqualTo(4);
        assertThat(snapshot.consumedCostMicrosCny()).isEqualTo(890_000);
        assertThat(snapshot.remainingAttempts()).isEqualTo(2);
        assertThat(snapshot.remainingCostMicrosCny()).isEqualTo(110_000);
        assertThatThrownBy(() -> budgets.reserve(BUDGET, firstRun, 0, 1, T0.plusSeconds(3)))
                .isInstanceOf(ProviderBudgetExceededException.class)
                .extracting(failure -> ((ProviderBudgetExceededException) failure).code())
                .isEqualTo("PROVIDER_ATTEMPT_ALREADY_RESERVED");
    }

    @Test
    void concurrentReservationsCannotExceedTheGlobalAttemptLimit() throws Exception {
        jdbcClient.sql("""
                        update inference_provider_budget set maximum_attempts = 1
                        where budget_key = 'p5-synthetic-canary'
                        """).update();
        var runA = createRun("dashscope-qwen37-flash-v1", "attempt-a");
        var runB = createRun("dashscope-qwen37-flash-v1", "attempt-b");
        var start = new CountDownLatch(1);

        List<Object> outcomes;
        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var futures = List.of(runA, runB).stream().map(runId -> executor.submit(() -> {
                start.await();
                try {
                    return (Object) budgets.reserve(BUDGET, runId, 0, 20_000, T0);
                } catch (RuntimeException failure) {
                    return failure;
                }
            })).toList();
            start.countDown();
            outcomes = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(outcomes).filteredOn(ProviderBudgetReservation.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ProviderBudgetExceededException.class::isInstance).hasSize(1);
        assertThat(budgets.snapshot(BUDGET).consumedAttempts()).isEqualTo(1);
    }

    private UUID createRun(String profileId, String seed) {
        var profile = profiles.require(profileId);
        var artifactId = sha256(seed + ":artifact");
        var artifact = new NormalizedArtifact(
                artifactId, NormalizedArtifact.Kind.IMAGE, artifactId,
                "image/png", 128, 8, 4
        );
        var normalized = new NormalizedInput(
                InferenceMode.IMAGE_ONLY, profileId, seed, sha256(seed + ":input"),
                List.of(artifact),
                List.of(new NormalizedInputReference(NormalizedArtifact.Kind.IMAGE, 0, artifactId)),
                List.of()
        );
        return runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-" + seed, normalized, profile.snapshotJson(), T0
        )).run().runId();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
