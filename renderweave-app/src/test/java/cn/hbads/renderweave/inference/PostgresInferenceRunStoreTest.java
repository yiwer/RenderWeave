package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.input.NormalizedInputReference;
import cn.hbads.renderweave.inference.run.InferenceIdempotencyConflictException;
import cn.hbads.renderweave.inference.run.InferenceLeaseLostException;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.InferenceStage;
import cn.hbads.renderweave.inference.run.InvalidInferenceRunTransitionException;
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
class PostgresInferenceRunStoreTest {
    private static final Instant T0 = Instant.parse("2026-08-08T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private InferenceRunStore runs;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearRuns() {
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
    }

    @Test
    void concurrentSameKeyCreatesExactlyOneDurableRunAndReplaysTheWinner() throws Exception {
        var start = new CountDownLatch(1);
        var input = normalized("same-input", "same-artifact");
        var commands = List.of(
                NewInferenceRun.initial(UUID.randomUUID(), "idem-create", input, profile(), T0),
                NewInferenceRun.initial(UUID.randomUUID(), "idem-create", input, profile(), T0)
        );

        List<InferenceRunStore.CreationResult> results;
        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var futures = commands.stream().map(command -> executor.submit(() -> {
                start.await();
                return runs.create(command);
            })).toList();
            start.countDown();
            results = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(results).filteredOn(InferenceRunStore.CreationResult::created).hasSize(1);
        assertThat(results).filteredOn(result -> !result.created()).hasSize(1);
        assertThat(results).extracting(result -> result.run().runId()).containsOnly(results.getFirst().run().runId());
        assertThat(count("inference_run")).isEqualTo(1);
        assertThat(count("inference_artifact")).isEqualTo(1);
        assertThat(count("inference_run_input")).isEqualTo(1);
        assertThat(count("inference_run_event")).isEqualTo(1);

        var replay = runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-create", input, profile(), T0.plusSeconds(1)
        ));
        assertThat(replay.created()).isFalse();
        assertThat(replay.run().state()).isEqualTo(InferenceRunState.QUEUED);
        assertThat(replay.run().stage()).isEqualTo(InferenceStage.OBSERVE);
        assertThat(replay.run().sequence()).isEqualTo(1);

        assertThatThrownBy(() -> runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-create", normalized("different-input", "other-artifact"),
                profile(), T0.plusSeconds(2)
        ))).isInstanceOf(InferenceIdempotencyConflictException.class);
        assertThat(count("inference_run")).isEqualTo(1);
    }

    @Test
    void skipLockedClaimsDistinctRunsAndPersistsMonotonicEvents() throws Exception {
        runs.create(command("claim-a", "input-a", "artifact-a"));
        runs.create(command("claim-b", "input-b", "artifact-b"));
        var start = new CountDownLatch(1);

        List<UUID> claimedIds;
        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var futures = List.of("worker-a", "worker-b").stream().map(worker -> executor.submit(() -> {
                start.await();
                return runs.claimNext(worker, T0, Duration.ofSeconds(30)).orElseThrow();
            })).toList();
            start.countDown();
            claimedIds = futures.stream().map(future -> {
                try {
                    return future.get().runId();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(claimedIds).doesNotHaveDuplicates().hasSize(2);
        assertThat(runs.claimNext("worker-c", T0, Duration.ofSeconds(30))).isEmpty();
        for (var runId : claimedIds) {
            var events = runs.eventsAfter(runId, 0, 20);
            assertThat(events).extracting(event -> event.sequence()).containsExactly(1L, 2L);
            assertThat(events).extracting(event -> event.type()).containsExactly("QUEUED", "LEASE_ACQUIRED");
        }
    }

    @Test
    void exactClaimLeasesOnlyTheRequestedRun() {
        var first = runs.create(command("exact-first", "exact-input-a", "exact-artifact-a")).run();
        var second = runs.create(command("exact-second", "exact-input-b", "exact-artifact-b")).run();

        var claimed = runs.claim(second.runId(), "http-worker", T0, Duration.ofSeconds(30)).orElseThrow();

        assertThat(claimed.runId()).isEqualTo(second.runId());
        assertThat(claimed.state()).isEqualTo(InferenceRunState.RUNNING);
        assertThat(runs.find(first.runId()).orElseThrow().state()).isEqualTo(InferenceRunState.QUEUED);
        assertThat(runs.claim(UUID.randomUUID(), "http-worker", T0, Duration.ofSeconds(30))).isEmpty();
        assertThat(runs.eventsAfter(second.runId(), 0, 10))
                .extracting(event -> event.type())
                .containsExactly("QUEUED", "LEASE_ACQUIRED");
    }

    @Test
    void leaseRenewalCheckpointAndExpiryResumeFromTheLastSafeStage() {
        var created = runs.create(command("lease-run", "lease-input", "lease-artifact")).run();
        var firstLease = runs.claimNext("worker-a", T0, Duration.ofSeconds(10)).orElseThrow();
        var firstToken = firstLease.lease().orElseThrow().token();

        assertThat(runs.renewLease(created.runId(), firstToken, T0.plusSeconds(5), Duration.ofSeconds(10))).isTrue();
        assertThat(runs.claimNext("worker-b", T0.plusSeconds(11), Duration.ofSeconds(10))).isEmpty();

        var checkpointed = runs.checkpoint(
                created.runId(), firstToken, InferenceStage.OBSERVE, InferenceStage.STRUCTURE,
                "{\"observationArtifactId\":\"safe-checkpoint\"}", T0.plusSeconds(12)
        );
        assertThat(checkpointed.stage()).isEqualTo(InferenceStage.STRUCTURE);
        assertThat(checkpointed.sequence()).isEqualTo(3);

        var resumed = runs.claimNext("worker-b", T0.plusSeconds(16), Duration.ofSeconds(10)).orElseThrow();
        assertThat(resumed.runId()).isEqualTo(created.runId());
        assertThat(resumed.stage()).isEqualTo(InferenceStage.STRUCTURE);
        assertThat(resumed.checkpointJson()).contains("safe-checkpoint");
        assertThat(resumed.lease().orElseThrow().token()).isNotEqualTo(firstToken);

        assertThatThrownBy(() -> runs.checkpoint(
                created.runId(), firstToken, InferenceStage.STRUCTURE,
                InferenceStage.DETERMINISTIC_VALIDATE, "{}", T0.plusSeconds(17)
        )).isInstanceOf(InferenceLeaseLostException.class);

        var advanced = runs.checkpoint(
                created.runId(), resumed.lease().orElseThrow().token(), InferenceStage.STRUCTURE,
                InferenceStage.DETERMINISTIC_VALIDATE, "{\"structure\":\"complete\"}", T0.plusSeconds(17)
        );
        assertThat(advanced.stage()).isEqualTo(InferenceStage.DETERMINISTIC_VALIDATE);
        assertThat(runs.eventsAfter(created.runId(), 0, 20))
                .extracting(event -> event.sequence())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(runs.eventsAfter(created.runId(), 3, 20))
                .extracting(event -> event.type())
                .containsExactly("LEASE_RECLAIMED", "CHECKPOINT_ADVANCED");
    }

    @Test
    void runningCancellationIsCooperativeAndRetryCreatesANewQueuedRunWithoutCopyingRawData() {
        var source = runs.create(command("cancel-source", "cancel-input", "shared-artifact")).run();
        var running = runs.claimNext("worker-a", T0, Duration.ofSeconds(30)).orElseThrow();
        var token = running.lease().orElseThrow().token();

        var requested = runs.requestCancellation(source.runId(), T0.plusSeconds(1));
        assertThat(requested.state()).isEqualTo(InferenceRunState.RUNNING);
        assertThat(requested.cancellationRequested()).isTrue();
        assertThat(runs.renewLease(source.runId(), token, T0.plusSeconds(2), Duration.ofSeconds(10))).isFalse();

        var cancelled = runs.acknowledgeCancellation(source.runId(), token, T0.plusSeconds(2));
        assertThat(cancelled.state()).isEqualTo(InferenceRunState.CANCELLED);
        assertThat(cancelled.finishedAt()).contains(T0.plusSeconds(2));
        assertThat(cancelled.lease()).isEmpty();

        var retryId = UUID.randomUUID();
        var retried = runs.retry(source.runId(), retryId, "retry-source", T0.plusSeconds(3));
        assertThat(retried.created()).isTrue();
        assertThat(retried.run().runId()).isEqualTo(retryId);
        assertThat(retried.run().retryOfRunId()).contains(source.runId());
        assertThat(retried.run().state()).isEqualTo(InferenceRunState.QUEUED);
        assertThat(retried.run().inputs()).usingRecursiveComparison().isEqualTo(cancelled.inputs());
        assertThat(retried.run().checkpointJson()).contains("NORMALIZE").doesNotContain("safe-checkpoint");

        var replay = runs.retry(source.runId(), UUID.randomUUID(), "retry-source", T0.plusSeconds(4));
        assertThat(replay.created()).isFalse();
        assertThat(replay.run().runId()).isEqualTo(retryId);
        assertThat(count("inference_artifact")).isEqualTo(1);
    }

    @Test
    void artifactDeletionWaitsForTheLastRunReferenceAndRemainsDurablyRetryable() {
        var source = runs.create(command("delete-source", "delete-input", "shared-delete-artifact")).run();
        runs.requestCancellation(source.runId(), T0.plusSeconds(1));
        var retry = runs.retry(source.runId(), UUID.randomUUID(), "delete-retry", T0.plusSeconds(2)).run();

        assertThat(runs.delete(source.runId())).isEmpty();
        assertThat(count("inference_artifact")).isEqualTo(1);
        assertThat(runs.find(retry.runId()).orElseThrow().retryOfRunId()).isEmpty();

        var pending = runs.delete(retry.runId());
        assertThat(pending).hasSize(1);
        assertThat(runs.pendingArtifactDeletions(10)).containsExactlyElementsOf(pending);
        assertThat(runs.confirmArtifactDeletion(pending.getFirst().artifactId())).isTrue();
        assertThat(runs.pendingArtifactDeletions(10)).isEmpty();
        assertThat(count("inference_artifact")).isZero();
    }

    @Test
    void queuedCancellationIsImmediateAndNonTerminalRunsCannotBeRetried() {
        var created = runs.create(command("queued-cancel", "queued-input", "queued-artifact")).run();

        assertThatThrownBy(() -> runs.retry(
                created.runId(), UUID.randomUUID(), "invalid-retry", T0.plusSeconds(1)
        )).isInstanceOf(InvalidInferenceRunTransitionException.class);

        var cancelled = runs.requestCancellation(created.runId(), T0.plusSeconds(2));
        assertThat(cancelled.state()).isEqualTo(InferenceRunState.CANCELLED);
        assertThat(cancelled.sequence()).isEqualTo(2);
        assertThat(runs.claimNext("worker", T0.plusSeconds(3), Duration.ofSeconds(10))).isEmpty();
    }

    @Test
    void applyingRunRejectsCancellationWithoutChangingItsAtomicSection() {
        var created = runs.create(command("applying-cancel", "applying-input", "applying-artifact")).run();
        jdbcClient.sql("""
                        update inference_run
                        set state = 'APPLYING', stage = 'ATOMIC_CREATE'
                        where run_id = :runId
                        """)
                .param("runId", created.runId())
                .update();

        assertThatThrownBy(() -> runs.requestCancellation(created.runId(), T0.plusSeconds(1)))
                .isInstanceOf(InvalidInferenceRunTransitionException.class)
                .hasMessageContaining("APPLYING cannot be cancelled");

        var unchanged = runs.find(created.runId()).orElseThrow();
        assertThat(unchanged.state()).isEqualTo(InferenceRunState.APPLYING);
        assertThat(unchanged.stage()).isEqualTo(InferenceStage.ATOMIC_CREATE);
        assertThat(unchanged.cancellationRequested()).isFalse();
        assertThat(unchanged.sequence()).isEqualTo(1);
    }

    private NewInferenceRun command(String idempotencyKey, String inputSeed, String artifactSeed) {
        return NewInferenceRun.initial(
                UUID.randomUUID(), idempotencyKey, normalized(inputSeed, artifactSeed), profile(), T0
        );
    }

    private static NormalizedInput normalized(String inputSeed, String artifactSeed) {
        var artifactId = sha256(artifactSeed);
        var artifact = new NormalizedArtifact(
                artifactId, NormalizedArtifact.Kind.IMAGE, artifactId,
                "image/png", 128, 8, 4
        );
        return new NormalizedInput(
                InferenceMode.IMAGE_ONLY, "replay-v1", "fixture-01", sha256(inputSeed),
                List.of(artifact),
                List.of(new NormalizedInputReference(NormalizedArtifact.Kind.IMAGE, 0, artifactId)),
                List.of()
        );
    }

    private static String profile() {
        return "{\"profileId\":\"replay-v1\",\"provider\":\"replay\",\"networkAllowed\":false}";
    }

    private long count(String table) {
        return jdbcClient.sql("select count(*) from " + table).query(Long.class).single();
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
