package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.input.NormalizedInputReference;
import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import cn.hbads.renderweave.inference.provider.ProviderCallException;
import cn.hbads.renderweave.inference.provider.ProviderCostEstimator;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.replay.InferenceAttemptStatus;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.run.InferenceRunState;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class PostgresLiveInferenceWorkflowTest {
    private static final Instant T0 = Instant.parse("2026-08-08T00:00:00Z");
    private static final String PROFILE = "dashscope-qwen37-flash-v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private InferenceRunStore runs;

    @Autowired
    private InferenceReplayStore workflowStore;

    @Autowired
    private ProviderBudgetStore budgets;

    @Autowired
    private JdbcClient jdbcClient;

    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();
    private final CandidateJsonCodec candidateCodec = new CandidateJsonCodec();

    @BeforeEach
    void clearData() {
        jdbcClient.sql("delete from inference_provider_reservation").update();
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
    }

    @Test
    void validResponseReachesReviewAndPersistsOnlySafeTelemetry() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-success");
        var provider = new ScriptedProvider(request -> response(request, candidate(request)));

        var finished = worker(provider, blobs).processNext("live-success-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(workflowStore.findCandidate(created).orElseThrow().currentJson())
                .contains("renderweave-candidate/1.0");
        assertThat(provider.requests).singleElement().satisfies(request -> {
            assertThat(request.systemPrompt()).contains("JSON");
            assertThat(request.taskJson()).contains("renderweave-live-task/1.0").doesNotContain("api-key");
            assertThat(request.images()).hasSize(1);
        });
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
            assertThat(attempt.providerRequestId()).contains("req-0");
            assertThat(attempt.providerModel()).contains("qwen3.7-flash");
            assertThat(attempt.inputTokens()).isEqualTo(1_000);
            assertThat(attempt.outputTokens()).isEqualTo(500);
            assertThat(attempt.estimatedCostMicrosCny()).isEqualTo(600);
        });
        var budget = budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY);
        assertThat(budget.consumedAttempts()).isEqualTo(1);
        assertThat(budget.consumedCostMicrosCny()).isEqualTo(600);
    }

    @Test
    void invalidContractIsNeverPersistedAndOneRepairCanReachReview() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-repair");
        var provider = new ScriptedProvider(
                request -> response(request, "{}"),
                request -> response(request, candidate(request))
        );

        var finished = worker(provider, blobs).processNext("live-repair-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(1).stage().name()).isEqualTo("REPAIR");
        assertThat(workflowStore.attempts(created))
                .extracting(attempt -> attempt.status())
                .containsExactly(InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED);
        assertThat(workflowStore.findCandidate(created).orElseThrow().currentJson())
                .doesNotContain("\"contractVersion\":null");
    }

    @Test
    void retryableProviderFailureConsumesAReservationAndProducesAnAuditedRetry() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-network-retry");
        var provider = new ScriptedProvider(
                request -> {
                    throw new ProviderCallException(
                            "DASHSCOPE_NETWORK_ERROR", true, null, Optional.empty(), null
                    );
                },
                request -> response(request, candidate(request))
        );

        var finished = worker(provider, blobs).processNext("live-retry-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(workflowStore.attempts(created))
                .extracting(attempt -> attempt.status())
                .containsExactly(InferenceAttemptStatus.FAILED, InferenceAttemptStatus.SUCCEEDED);
        assertThat(workflowStore.attempts(created).getFirst().outcomeCode())
                .isEqualTo("DASHSCOPE_NETWORK_ERROR");
        var budget = budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY);
        assertThat(budget.consumedAttempts()).isEqualTo(2);
        assertThat(budget.consumedCostMicrosCny()).isEqualTo(
                ProviderCostEstimator.maximumRequestCostMicrosCny(provider.requests.getFirst()) + 600
        );
    }

    @Test
    void retryAfterFailsSafelyWithoutAnImmediateSecondProviderCall() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-retry-after");
        var provider = new ScriptedProvider(request -> {
            throw new ProviderCallException(
                    "DASHSCOPE_HTTP_429", true, 429,
                    Optional.of(Duration.ofSeconds(30)), null
            );
        });

        var finished = worker(provider, blobs).processNext("live-retry-after-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains("DASHSCOPE_RETRY_AFTER");
        assertThat(provider.requests).hasSize(1);
        assertThat(workflowStore.attempts(created)).hasSize(1);
        assertThat(budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY).consumedAttempts()).isEqualTo(1);
    }

    @Test
    void missingCredentialFailsBeforeAnyReservationOrProviderAttempt() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-not-configured");
        var provider = new InferenceProvider() {
            @Override
            public ProviderInferenceResponse complete(ProviderInferenceRequest request) {
                throw new AssertionError("Provider must not be called");
            }

            @Override
            public boolean configured() {
                return false;
            }
        };

        var finished = worker(provider, blobs).processNext("live-missing-key-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains("DASHSCOPE_NOT_CONFIGURED");
        assertThat(workflowStore.attempts(created)).isEmpty();
        assertThat(budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY).consumedAttempts()).isZero();
    }

    private LiveInferenceWorker worker(InferenceProvider provider, BlobStore blobs) {
        return new LiveInferenceWorker(
                runs, workflowStore, budgets, provider, blobs,
                Clock.fixed(T0.plusSeconds(1), ZoneOffset.UTC), Duration.ofMinutes(5)
        );
    }

    private UUID create(MemoryBlobStore blobs, String seed) {
        var profile = profiles.require(PROFILE);
        var bytes = ("synthetic-image:" + seed).getBytes(StandardCharsets.UTF_8);
        var artifactId = sha256(bytes);
        blobs.values.put(artifactId, bytes);
        var artifact = new NormalizedArtifact(
                artifactId, NormalizedArtifact.Kind.IMAGE, artifactId,
                "image/png", bytes.length, 32, 16
        );
        var normalized = new NormalizedInput(
                InferenceMode.IMAGE_ONLY, PROFILE, seed, sha256(seed.getBytes(StandardCharsets.UTF_8)),
                List.of(artifact),
                List.of(new NormalizedInputReference(NormalizedArtifact.Kind.IMAGE, 0, artifactId)),
                List.of()
        );
        return runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-" + seed, normalized, profile.snapshotJson(), T0
        )).run().runId();
    }

    private String candidate(ProviderInferenceRequest request) {
        var schemaId = UUID.nameUUIDFromBytes((request.runId() + ":schema").getBytes(StandardCharsets.UTF_8));
        var fieldId = UUID.nameUUIDFromBytes((request.runId() + ":field").getBytes(StandardCharsets.UTF_8));
        var evidence = CandidateEvidence.image(
                request.images().getFirst().artifactId(), new CandidateBoundingBox(500, 500, 9_500, 2_500)
        );
        var assessment = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED, List.of(evidence)
        );
        return candidateCodec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "synthetic-card", "合成卡片", CandidateSource.AI, assessment,
                        List.of(new CandidateField(
                                fieldId, "title", "标题", false,
                                CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI, assessment
                        ))
                ))
        ));
    }

    private static ProviderInferenceResponse response(ProviderInferenceRequest request, String candidate) {
        return new ProviderInferenceResponse(
                candidate, "req-" + request.attemptOrdinal(), request.profile().model(),
                new ProviderUsage(1_000, 500), "stop"
        );
    }

    private static final class ScriptedProvider implements InferenceProvider {
        private final ArrayDeque<Step> steps;
        private final List<ProviderInferenceRequest> requests = new ArrayList<>();

        private ScriptedProvider(Step... steps) {
            this.steps = new ArrayDeque<>(List.of(steps));
        }

        @Override
        public ProviderInferenceResponse complete(ProviderInferenceRequest request) {
            requests.add(request);
            return steps.removeFirst().apply(request);
        }
    }

    @FunctionalInterface
    private interface Step {
        ProviderInferenceResponse apply(ProviderInferenceRequest request);
    }

    private static final class MemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public WriteReceipt write(String artifactId, byte[] bytes) {
            return new WriteReceipt(artifactId, values.putIfAbsent(artifactId, bytes.clone()) == null);
        }

        @Override
        public byte[] read(String locator) {
            return values.get(locator).clone();
        }

        @Override
        public void delete(String locator) {
            values.remove(locator);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
