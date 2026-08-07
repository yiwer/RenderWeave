package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.input.NormalizedInputReference;
import cn.hbads.renderweave.inference.input.StrictJsonSampleProfiler;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.replay.InferenceAttemptStatus;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.replay.ReplayCorpus;
import cn.hbads.renderweave.inference.replay.ReplayInferenceWorker;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.InferenceStage;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class PostgresReplayInferenceWorkflowTest {
    private static final Instant T0 = Instant.parse("2026-08-08T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private InferenceRunStore runs;

    @Autowired
    private InferenceReplayStore replayStore;

    @Autowired
    private JdbcClient jdbcClient;

    private final ReplayCorpus corpus = new ReplayCorpus();
    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();
    private final StrictJsonSampleProfiler reducer = new StrictJsonSampleProfiler();
    private final CandidateJsonCodec candidateCodec = new CandidateJsonCodec();

    @BeforeEach
    void clearRuns() {
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
    }

    @Test
    void allModesReachReviewWithImmutableRevisionZeroAndNeverTouchSchemaRepositories() {
        var draftCount = count("schema_draft");
        var staticCount = count("static_schema");
        var fixtures = List.of(
                "image-08-low-information",
                "json-07-object-scalar-conflict",
                "combined-02-date-refinement"
        );

        for (var fixtureId : fixtures) {
            var blobs = new MemoryBlobStore();
            var created = create(fixtureId, blobs);
            var worker = worker(blobs, T0.plusSeconds(1));
            var finished = worker.processNext("worker-" + fixtureId).orElseThrow();

            assertThat(finished.runId()).isEqualTo(created.runId());
            assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
            assertThat(finished.stage()).isEqualTo(InferenceStage.USER_APPROVAL);
            assertThat(finished.lease()).isEmpty();
            var snapshot = replayStore.findCandidate(created.runId()).orElseThrow();
            assertThat(snapshot.revision()).isZero();
            assertThat(snapshot.contractVersion()).isEqualTo("renderweave-candidate/1.0");
            assertThat(snapshot.originalJson()).isEqualTo(snapshot.currentJson());
            assertThat(candidateCodec.parse(snapshot.currentJson()).rootCandidateSchemaId()).isNotNull();
            assertThat(replayStore.attempts(created.runId()))
                    .singleElement()
                    .extracting(attempt -> attempt.status())
                    .isEqualTo(InferenceAttemptStatus.SUCCEEDED);
            assertThat(runs.eventsAfter(created.runId(), 0, 30))
                    .extracting(event -> event.type())
                    .contains("REVIEW_REQUIRED");
        }

        assertThat(count("schema_draft")).isEqualTo(draftCount);
        assertThat(count("schema_draft_revision")).isZero();
        assertThat(count("static_schema")).isEqualTo(staticCount);
        assertThat(count("inference_candidate")).isEqualTo(3);
    }

    @Test
    void expiredLeaseResumesAfterTheLastCheckpointWithoutRepeatingCompletedAttempts() {
        var blobs = new MemoryBlobStore();
        var created = create("combined-20-repair-twice", blobs);
        var firstWorker = worker(blobs, T0.plusSeconds(1));
        var claimed = runs.claimNext("worker-before-crash", T0.plusSeconds(1), Duration.ofSeconds(10))
                .orElseThrow();

        var structured = firstWorker.advance(firstWorker.advance(claimed));
        assertThat(structured.stage()).isEqualTo(InferenceStage.DETERMINISTIC_VALIDATE);
        assertThat(replayStore.attempts(created.runId()))
                .extracting(attempt -> attempt.attemptOrdinal())
                .containsExactly(0);
        assertThat(replayStore.attempts(created.runId()).getFirst().status())
                .isEqualTo(InferenceAttemptStatus.REJECTED);

        var recovered = worker(blobs, T0.plusSeconds(12))
                .processNext("worker-after-crash").orElseThrow();
        assertThat(recovered.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(replayStore.attempts(created.runId()))
                .extracting(attempt -> attempt.attemptOrdinal())
                .containsExactly(0, 1, 2);
        assertThat(replayStore.attempts(created.runId()))
                .extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.REJECTED,
                        InferenceAttemptStatus.REJECTED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(runs.eventsAfter(created.runId(), 0, 40))
                .extracting(event -> event.type())
                .contains("LEASE_RECLAIMED", "REVIEW_REQUIRED");
        assertThat(count("inference_candidate")).isEqualTo(1);
    }

    @Test
    void cooperativeCancellationProducesNeitherAttemptCandidateNorDraft() {
        var blobs = new MemoryBlobStore();
        var created = create("json-01-scalars", blobs);
        var claimed = runs.claimNext("cancel-worker", T0.plusSeconds(1), Duration.ofSeconds(30))
                .orElseThrow();
        var requested = runs.requestCancellation(created.runId(), T0.plusSeconds(2));

        var cancelled = worker(blobs, T0.plusSeconds(3)).advance(requested);

        assertThat(cancelled.state()).isEqualTo(InferenceRunState.CANCELLED);
        assertThat(cancelled.runId()).isEqualTo(claimed.runId());
        assertThat(replayStore.attempts(created.runId())).isEmpty();
        assertThat(replayStore.findCandidate(created.runId())).isEmpty();
        assertThat(count("schema_draft")).isZero();
    }

    private cn.hbads.renderweave.inference.run.InferenceRunSnapshot create(
            String fixtureId,
            MemoryBlobStore blobs
    ) {
        var fixture = corpus.require(fixtureId);
        var artifacts = new ArrayList<NormalizedArtifact>();
        var references = new ArrayList<NormalizedInputReference>();
        for (var ordinal = 0; ordinal < fixture.imageCount(); ordinal++) {
            var artifactId = sha256(fixtureId + ":image:" + ordinal);
            artifacts.add(new NormalizedArtifact(
                    artifactId, NormalizedArtifact.Kind.IMAGE, artifactId,
                    "image/png", 128, 8, 4
            ));
            references.add(new NormalizedInputReference(
                    NormalizedArtifact.Kind.IMAGE, ordinal, artifactId
            ));
        }
        if (!fixture.jsonSamples().isEmpty()) {
            var samples = fixture.jsonSamples().stream().map(sample -> new InferenceInput.BinaryInput(
                    fixtureId + ".json", "application/json", sample.getBytes(StandardCharsets.UTF_8)
            )).toList();
            var bytes = reducer.profile(samples);
            var artifactId = sha256(bytes);
            blobs.values.put(artifactId, bytes);
            artifacts.add(new NormalizedArtifact(
                    artifactId, NormalizedArtifact.Kind.JSON_PROFILE, artifactId,
                    "application/vnd.renderweave.json-profile+json", bytes.length, null, null
            ));
            references.add(new NormalizedInputReference(
                    NormalizedArtifact.Kind.JSON_PROFILE, 0, artifactId
            ));
        }
        var normalized = new NormalizedInput(
                fixture.mode(), "replay-v1", fixtureId, sha256(fixtureId + ":input"),
                artifacts, references, List.of()
        );
        return runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-" + fixtureId, normalized,
                profiles.require("replay-v1").snapshotJson(), T0
        )).run();
    }

    private ReplayInferenceWorker worker(BlobStore blobs, Instant now) {
        return new ReplayInferenceWorker(
                runs, replayStore, blobs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(30)
        );
    }

    private long count(String table) {
        return jdbcClient.sql("select count(*) from " + table).query(Long.class).single();
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class MemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public WriteReceipt write(String artifactId, byte[] bytes) {
            var created = values.putIfAbsent(artifactId, bytes.clone()) == null;
            return new WriteReceipt(artifactId, created);
        }

        @Override
        public byte[] read(String locator) {
            var value = values.get(locator);
            if (value == null) throw new IllegalArgumentException("Unknown test blob " + locator);
            return value.clone();
        }

        @Override
        public void delete(String locator) {
            values.remove(locator);
        }
    }
}
