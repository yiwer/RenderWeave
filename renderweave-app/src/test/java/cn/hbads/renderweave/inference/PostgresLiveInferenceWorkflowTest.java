package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblemJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateReference;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.input.NormalizedInputReference;
import cn.hbads.renderweave.inference.input.StrictJsonSampleProfiler;
import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import cn.hbads.renderweave.inference.provider.ProviderCallException;
import cn.hbads.renderweave.inference.provider.ProviderCostEstimator;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.replay.InferenceAttempt;
import cn.hbads.renderweave.inference.replay.InferenceAttemptStatus;
import cn.hbads.renderweave.inference.replay.InferenceRejectionEnvelope;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.replay.ReplayCorpus;
import cn.hbads.renderweave.inference.run.InferenceStage;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.NewInferenceRun;
import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.VisualEvidenceAcquisition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class PostgresLiveInferenceWorkflowTest {
    private static final Instant T0 = Instant.parse("2026-08-08T00:00:00Z");
    private static final String PROFILE = "dashscope-qwen37-flash-v1";
    private static final String GROUNDED_PROFILE =
            "dashscope-qwen37-plus-20260526-grounded-v1";
    private static final String SERIAL_PRODUCT_PROFILE =
            "dashscope-qwen37-flash-product-v4";
    private static final String LOCAL_MATERIALIZER_PROFILE =
            "dashscope-qwen37-flash-product-v5";
    private static final String GROUNDED_VISUAL_PROFILE =
            "dashscope-qwen37-flash-product-v6-transit-board";
    private static final String EVIDENCE_DERIVED_HIERARCHY_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v16-generic";
    private static final String REGION_OWNED_HIERARCHY_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v17-generic";
    private static final String DIAGNOSTIC_HIERARCHY_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v18-generic";
    private static final String SUPPORT_NORMALIZED_HIERARCHY_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v19-generic";
    private static final String REGION_NORMALIZED_HIERARCHY_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v20-generic";
    private static final String CONNECTION_NORMALIZED_HIERARCHY_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v21-generic";
    private static final String SUPPORT_OWNER_NORMALIZED_HIERARCHY_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v22-generic";
    private static final String HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-product-v7-hybrid-generic";
    private static final String SUPPORT_OWNER_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v23-hybrid-generic";
    private static final String BOUNDED_OBSERVATION_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v24-hybrid-generic";
    private static final String LEAF_EVIDENCE_VERIFIED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v25-hybrid-generic";
    private static final String ENCLOSING_SUPPORT_OWNER_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v26-hybrid-generic";
    private static final String SOURCE_ANCESTOR_SUPPORT_OWNER_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v27-hybrid-generic";
    private static final String MINIMAL_ENTITY_OWNERSHIP_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v28-hybrid-generic";
    private static final String GROUP_REGION_CARDINALITY_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v29-hybrid-generic";
    private static final String EVIDENCE_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v30-hybrid-generic";
    private static final String REPEATED_ITEM_SLOT_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v31-hybrid-generic";
    private static final String EMPTY_SUPPORT_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v32-hybrid-generic";
    private static final String UNKNOWN_SUPPORT_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v33-hybrid-generic";
    private static final String UNIQUE_REGION_PARENT_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v34-hybrid-generic";
    private static final String EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v35-hybrid-generic";
    private static final String STRUCTURAL_REGION_KIND_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v36-hybrid-generic";
    private static final String CONSTRAINT_REGION_KIND_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v37-hybrid-generic";
    private static final String ANCESTOR_REGION_PARENT_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v38-hybrid-generic";
    private static final String GAPPED_READING_ORDER_NORMALIZED_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v39-hybrid-generic";
    private static final String READING_ORDER_DIAGNOSTIC_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-20260715-product-v40-hybrid-generic";
    private static final String EARLY_RELATIONSHIP_GROUP_PREREQUISITE_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-flash-product-v41-hybrid-generic";
    private static final String PLUS_V42_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-plus-product-v42-hybrid-generic";
    private static final String PLUS_V44_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-plus-product-v44-hybrid-generic";
    private static final String PLUS_V45_HYBRID_VISUAL_PROFILE =
            "dashscope-qwen37-plus-product-v45-hybrid-generic";
    private static final String MAX_V46_CERTIFICATION_PROFILE =
            "dashscope-qwen38-max-product-v46-hybrid-generic";
    private static final String MAX_V47_SUCCESSOR_PROFILE =
            "dashscope-qwen38-max-product-v47-hybrid-generic";
    private static final String MAX_V48_REGION_RECOVERY_PROFILE =
            "dashscope-qwen38-max-product-v48-hybrid-generic";
    private static final String MAX_V49_MIXED_RECOVERY_PROFILE =
            "dashscope-qwen38-max-product-v49-hybrid-generic";
    private static final String MAX_V50_LOCAL_ID_CANONICALIZATION_PROFILE =
            "dashscope-qwen38-max-product-v50-hybrid-generic";
    private static final String MAX_V51_PARENT_CONTAINMENT_PROVENANCE_PROFILE =
            "dashscope-qwen38-max-product-v51-hybrid-generic";
    private static final String MAX_V52_ITEM_PARENT_ENVELOPE_NORMALIZATION_PROFILE =
            "dashscope-qwen38-max-product-v52-hybrid-generic";
    private static final String DOCUMENT_VISION_CAPABILITY =
            "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1";

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

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
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
            assertThat(attempt.problemCodeCounts()).isEmpty();
        });
        var budget = budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY);
        assertThat(budget.consumedAttempts()).isEqualTo(1);
        assertThat(budget.consumedCostMicrosCny()).isEqualTo(600);
    }

    @Test
    void cancellationDuringAProviderCallKeepsItsSettledAttemptTelemetry() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-cancel-during-provider");
        var provider = new ScriptedProvider(request -> {
            runs.requestCancellation(created, T0.plusSeconds(1));
            return response(request, candidate(request));
        });

        var finished = worker(provider, blobs).processNext("live-cancel-provider-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.CANCELLED);
        assertThat(finished.cancellationRequested()).isTrue();
        assertThat(provider.requests).hasSize(1);
        assertThat(workflowStore.attempts(created))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
                    assertThat(attempt.estimatedCostMicrosCny()).isPositive();
                });
        assertThat(jdbcClient.sql("""
                        select state from inference_provider_reservation
                        where run_id = :runId
                        """)
                .param("runId", created)
                .query(String.class).single()).isEqualTo("SETTLED");
    }

    @Test
    void cancellationDuringAFailedProviderCallKeepsItsFailureTelemetry() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-cancel-during-provider-failure");
        var provider = new ScriptedProvider(request -> {
            runs.requestCancellation(created, T0.plusSeconds(1));
            throw new ProviderCallException(
                    "DASHSCOPE_NETWORK_ERROR", true, null, Optional.empty(), null
            );
        });

        var finished = worker(provider, blobs).processNext("live-cancel-failed-provider-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.CANCELLED);
        assertThat(provider.requests).hasSize(1);
        assertThat(workflowStore.attempts(created))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.FAILED);
                    assertThat(attempt.outcomeCode()).isEqualTo("DASHSCOPE_NETWORK_ERROR");
                });
    }

    @Test
    void serialProductV3PreservesStationNoticeRouteAndStopTopology() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "serial-station-board", 1_050, 1_660, SERIAL_PRODUCT_PROFILE);
        var provider = new ScriptedProvider(
                request -> response(request, stationElements(request)),
                request -> response(request, stationHierarchy()),
                request -> response(request, stationBindings()),
                request -> response(request, stationCandidate(request))
        );

        var claimed = runs.claimNextLive(
                "serial-station-worker", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();
        var finished = worker(provider, blobs).drain(claimed);

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "HIERARCHY", "ELEMENT_BINDING", "STRUCTURE");
        assertThat(provider.requests.get(1).taskJson())
                .contains("renderweave-live-task/3.0", "renderweave-visual-elements/1.0");
        assertThat(provider.requests.get(2).taskJson())
                .contains("renderweave-visual-hierarchy/1.0");
        assertThat(provider.requests.get(3).taskJson())
                .contains("renderweave-visual-bindings/1.0");
        assertThat(workflowStore.attempts(created)).hasSize(4)
                .extracting(attempt -> attempt.status())
                .containsOnly(InferenceAttemptStatus.SUCCEEDED);

        var candidate = candidateCodec.parse(workflowStore.findCandidate(created).orElseThrow().currentJson());
        assertThat(candidate.schemas()).extracting(CandidateSchema::proposedSchemaKey)
                .containsExactlyInAnyOrder("bus-stop-board", "warm-notice", "bus-route", "bus-stop");
        var board = candidate.schemas().stream()
                .filter(schema -> "bus-stop-board".equals(schema.proposedSchemaKey())).findFirst().orElseThrow();
        assertThat(board.fields()).extracting(CandidateField::proposedFieldKey)
                .contains("stationName", "stationEnglishName", "warmNotice", "routes");
        var route = candidate.schemas().stream()
                .filter(schema -> "bus-route".equals(schema.proposedSchemaKey())).findFirst().orElseThrow();
        assertThat(route.fields().stream().filter(field -> "stops".equals(field.proposedFieldKey()))
                .findFirst().orElseThrow().value().items().kind()).isEqualTo(CandidateValueKind.REFERENCE);
        assertThat(budgets.snapshot(LiveInferenceWorker.PRODUCT_BUDGET_KEY).consumedAttempts()).isEqualTo(4);
    }

    @Test
    void serialProductV3RetriesOneInvalidIntermediateContractWithinTheFiveCallEnvelope() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "serial-station-retry", 1_050, 1_660, SERIAL_PRODUCT_PROFILE);
        var provider = new ScriptedProvider(
                request -> response(request, "{}"),
                request -> response(request, stationElements(request)),
                request -> response(request, stationHierarchy()),
                request -> response(request, stationBindings()),
                request -> response(request, stationCandidate(request))
        );

        var finished = worker(provider, blobs).processNext("serial-retry-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "OBSERVE", "HIERARCHY", "ELEMENT_BINDING", "STRUCTURE");
        assertThat(provider.requests.get(1).taskJson())
                .contains("VISUAL_ELEMENTS_CONTRACT_INVALID");
        assertThat(workflowStore.attempts(created)).hasSize(5);
        assertThat(workflowStore.attempts(created).getFirst().status())
                .isEqualTo(InferenceAttemptStatus.REJECTED);
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsEntry("VISUAL_ELEMENTS_CONTRACT_INVALID", 1);
        assertThat(budgets.snapshot(LiveInferenceWorker.PRODUCT_BUDGET_KEY).consumedAttempts()).isEqualTo(5);
    }

    @Test
    void serialProductV3ResumesFromItsPersistedStageAfterLeaseExpiryWithoutRepeatingObserve() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "serial-station-resume", 1_050, 1_660, SERIAL_PRODUCT_PROFILE);
        var provider = new ScriptedProvider(
                request -> response(request, stationElements(request)),
                request -> response(request, stationHierarchy()),
                request -> response(request, stationBindings()),
                request -> response(request, stationCandidate(request))
        );
        var firstWorker = worker(provider, blobs, T0.plusSeconds(1));
        var claimed = runs.claimNextLive(
                "serial-first-worker", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();

        var afterObserve = firstWorker.advance(claimed);
        var finished = worker(provider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("serial-recovery-worker").orElseThrow();

        assertThat(afterObserve.stage().name()).isEqualTo("HIERARCHY");
        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "HIERARCHY", "ELEMENT_BINDING", "STRUCTURE");
        assertThat(workflowStore.attempts(created)).hasSize(4);
    }

    @Test
    void pipelineFourMaterializesTheStationTreeLocallyWithExactlyThreeProviderAttempts() {
        var blobs = new MemoryBlobStore();
        var created = create(
                blobs, "local-materializer-station", 1_050, 1_660, LOCAL_MATERIALIZER_PROFILE
        );
        var provider = new ScriptedProvider(
                request -> response(request, stationElements(request)),
                request -> response(request, stationHierarchy()),
                request -> response(request, stationBindings())
        );

        var finished = worker(provider, blobs).processNext("local-materializer-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "HIERARCHY", "ELEMENT_BINDING");
        assertThat(workflowStore.attempts(created)).hasSize(3)
                .allSatisfy(attempt -> {
                    assertThat(attempt.stage()).isNotEqualTo(InferenceStage.STRUCTURE);
                    assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
                });
        assertThat(jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId
                        """)
                .param("runId", created)
                .query(Long.class).single()).isEqualTo(3L);
        assertThat(budgets.snapshot(LiveInferenceWorker.PRODUCT_BUDGET_KEY).consumedAttempts())
                .isEqualTo(3);

        var candidate = candidateCodec.parse(
                workflowStore.findCandidate(created).orElseThrow().currentJson()
        );
        assertThat(candidate.schemas()).extracting(CandidateSchema::proposedSchemaKey)
                .containsExactly("bus-stop-board", "bus-route", "warm-notice", "bus-stop");
        assertThat(candidate.schemas()).allSatisfy(schema -> {
            assertThat(schema.assessment().resolution()).isEqualTo(CandidateResolution.UNRESOLVED);
            assertThat(schema.assessment().confidenceBps()).isEqualTo(7_999);
            assertThat(schema.fields()).allSatisfy(field -> {
                assertThat(field.required()).isFalse();
                assertThat(field.assessment().resolution()).isEqualTo(CandidateResolution.UNRESOLVED);
                assertThat(field.assessment().confidenceBps()).isEqualTo(7_999);
            });
        });
        var route = candidate.schemas().stream()
                .filter(schema -> "bus-route".equals(schema.proposedSchemaKey()))
                .findFirst().orElseThrow();
        assertThat(route.fields().stream().filter(field -> "stops".equals(field.proposedFieldKey()))
                .findFirst().orElseThrow().value().items().kind())
                .isEqualTo(CandidateValueKind.REFERENCE);
    }

    @Test
    void pipelineFourRecoversAtStructureWithoutRepeatingAProviderStage() {
        var blobs = new MemoryBlobStore();
        var created = create(
                blobs, "local-materializer-recovery", 1_050, 1_660, LOCAL_MATERIALIZER_PROFILE
        );
        var firstProvider = new ScriptedProvider(
                request -> response(request, stationElements(request)),
                request -> response(request, stationHierarchy()),
                request -> response(request, stationBindings())
        );
        var firstWorker = worker(firstProvider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "local-materializer-first", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();

        current = firstWorker.advance(current);
        current = firstWorker.advance(current);
        current = firstWorker.advance(current);
        assertThat(current.stage()).isEqualTo(InferenceStage.STRUCTURE);

        var recoveryProvider = new ScriptedProvider();
        var finished = worker(recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("local-materializer-recovery").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(firstProvider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "HIERARCHY", "ELEMENT_BINDING");
        assertThat(recoveryProvider.requests).isEmpty();
        assertThat(workflowStore.attempts(created)).hasSize(3);
        assertThat(workflowStore.findCandidate(created)).isPresent();
    }

    @Test
    void pipelineFourPointOneGroundsMultiScaleViewsAndPersistsOnlyOriginalCoordinates() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "grounded-visual-station");
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );

        var finished = worker(provider, blobs).processNext("grounded-visual-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "HIERARCHY", "ELEMENT_BINDING");
        assertThat(provider.requests).allSatisfy(request -> {
            assertThat(request.images()).hasSize(3);
            assertThat(request.taskJson())
                    .contains("renderweave-live-task/4.0")
                    .contains("renderweave-visual-view-plan/1.0")
                    .contains("\"viewCatalog\"")
                    .contains("renderweave-visual-hint-pack/transit-board/1.0");
            assertThat(request.systemPrompt()).contains("停靠站点");
        });
        assertThat(workflowStore.attempts(created)).hasSize(3)
                .allSatisfy(attempt -> assertThat(attempt.status())
                        .isEqualTo(InferenceAttemptStatus.SUCCEEDED));
        assertThat(finished.checkpointJson())
                .contains("renderweave-live-checkpoint/3.0")
                .contains("renderweave-visual-grounding/2.0")
                .contains("renderweave-visual-entity-regions/2.0")
                .doesNotContain("view-00-");

        var candidate = candidateCodec.parse(
                workflowStore.findCandidate(created).orElseThrow().currentJson()
        );
        assertThat(candidate.schemas()).extracting(CandidateSchema::proposedSchemaKey)
                .containsExactly("bus-stop-board", "bus-route", "warm-notice", "bus-stop");
        assertThat(candidate.schemas().stream()
                .flatMap(schema -> schema.fields().stream())
                .flatMap(field -> field.assessment().evidence().stream()))
                .allSatisfy(evidence -> assertThat(evidence.artifactId())
                        .isEqualTo(finished.inputs().getFirst().artifact().artifactId()));
    }

    @Test
    void pipelineFourPointThreeDerivesRelationshipCardinalityFromUniqueGroupEvidence() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "evidence-derived-hierarchy-cardinality",
                EVIDENCE_DERIVED_HIERARCHY_PROFILE
        );
        var mismatchedHierarchy = groundedStationHierarchy().replaceFirst(
                "\"cardinality\":\"MANY\"", "\"cardinality\":\"ONE\""
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, mismatchedHierarchy),
                request -> response(request, groundedStationBindings())
        );

        var finished = worker(provider, blobs)
                .processNext("evidence-derived-hierarchy-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests).allSatisfy(request ->
                assertThat(request.profile().pipelineVersion())
                        .isEqualTo("renderweave-inference-pipeline/4.3")
        );
        assertThat(workflowStore.attempts(created)).hasSize(3)
                .allSatisfy(attempt -> assertThat(attempt.status())
                        .isEqualTo(InferenceAttemptStatus.SUCCEEDED));
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3
                ));
        assertThat(candidateCodec.parse(
                workflowStore.findCandidate(created).orElseThrow().currentJson()
        ).schemas()).extracting(CandidateSchema::proposedSchemaKey)
                .containsExactly("bus-stop-board", "bus-route", "warm-notice", "bus-stop");
    }

    @Test
    void groundedContractRetriesOnlyCurrentStageAndPersistsBoundedDiagnostic() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "grounded-version-diagnostic");
        var invalid = groundedStationElements().replace(
                "renderweave-visual-grounding/2.0", "renderweave-visual-grounding/9.0"
        );
        var provider = new ScriptedProvider(
                request -> response(request, invalid),
                request -> response(request, invalid),
                request -> response(request, invalid),
                request -> response(request, invalid),
                request -> response(request, invalid)
        );

        var finished = worker(provider, blobs).processNext("grounded-diagnostic-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains("VISUAL_GROUNDING_VERSION_INVALID");
        assertThat(provider.requests).hasSize(5).allSatisfy(request ->
                assertThat(request.stage()).isEqualTo(InferenceStage.OBSERVE)
        );
        assertThat(workflowStore.attempts(created)).hasSize(5).allSatisfy(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.REJECTED);
            assertThat(attempt.problemCodeCounts())
                    .containsExactlyEntriesOf(java.util.Map.of("VISUAL_GROUNDING_VERSION_INVALID", 1));
        });
        assertThat(workflowStore.findCandidate(created)).isEmpty();
    }

    @Test
    void groundedSemanticVerifierRetriesObservationBeforeHierarchy() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "grounded-semantic-observation-retry");
        var readingOrderGap = groundedStationElements().replace(
                "\"regionId\":\"routes\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":2",
                "\"regionId\":\"routes\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":3"
        );
        var flattened = groundedStationElements().replace(
                "\"elementId\":\"route-group\",\"kind\":\"GROUP\",\"proposedKey\":\"routes\",\"displayName\":\"线路\",\"multiplicity\":\"MANY\",\"valueHint\":null",
                "\"elementId\":\"route-group\",\"kind\":\"SLOT\",\"proposedKey\":\"routes\",\"displayName\":\"线路\",\"multiplicity\":\"MANY\",\"valueHint\":\"TEXT\""
        );
        var provider = new ScriptedProvider(
                request -> response(request, readingOrderGap),
                request -> response(request, flattened),
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );

        var finished = worker(provider, blobs).processNext("grounded-semantic-retry-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "OBSERVE", "OBSERVE", "HIERARCHY", "ELEMENT_BINDING");
        assertThat(provider.requests.get(1).taskJson())
                .contains("VISUAL_GROUNDING_READING_ORDER_GAP");
        assertThat(provider.requests.get(2).taskJson())
                .contains("VISUAL_GROUNDING_READING_ORDER_GAP")
                .contains("VISUAL_SEMANTIC_REPEATED_GROUP_ELEMENT_MISSING");
        assertThat(workflowStore.attempts(created).getFirst().status())
                .isEqualTo(InferenceAttemptStatus.REJECTED);
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_GROUNDING_READING_ORDER_GAP", 1
                ));
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_REPEATED_GROUP_ELEMENT_MISSING", 1
                ));
        assertThat(workflowStore.attempts(created).subList(2, 5))
                .allSatisfy(attempt -> assertThat(attempt.status())
                        .isEqualTo(InferenceAttemptStatus.SUCCEEDED));
    }

    @Test
    void groundedSemanticVerifierPreservesUpstreamStagesAndUsesVerifiedRepairCrops() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "grounded-semantic-stage-local-repair");
        var misplacedBinding = groundedStationBindings().replace(
                "{\"elementId\":\"stop-name\",\"entityId\":\"stop\"}",
                "{\"elementId\":\"stop-name\",\"entityId\":\"board\"}"
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchyWithoutNoticeEdge()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, misplacedBinding),
                request -> response(request, groundedStationBindings())
        );

        var finished = worker(provider, blobs).processNext("grounded-stage-local-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING, InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(1).taskJson()).doesNotContain("TARGETED_CROP");
        assertThat(provider.requests.get(2).taskJson())
                .contains("TARGETED_CROP")
                .contains("VISUAL_SEMANTIC_HIERARCHY_GROUP_EDGE_MISSING")
                .contains("\"groundingPlan\":{");
        assertThat(provider.requests.get(4).taskJson())
                .contains("TARGETED_CROP")
                .contains("VISUAL_SEMANTIC_BINDING_NOT_NEAREST_ENTITY")
                .contains("\"hierarchyPlan\":{");
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_HIERARCHY_GROUP_EDGE_MISSING", 1
                ));
        assertThat(workflowStore.attempts(created).get(3).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_BINDING_NOT_NEAREST_ENTITY", 1
                ));
    }

    @Test
    void groundedHierarchyStructuralRepairPreservesUpstreamStageWithoutAddingACrop() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "grounded-hierarchy-structural-repair");
        var invalidEntityId = groundedStationHierarchy().replace(
                "\"entityId\":\"route\"", "\"entityId\":\"Route\""
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, invalidEntityId),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );

        var finished = worker(provider, blobs).processNext("grounded-structural-repair-worker")
                .orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(2).taskJson())
                .contains("VISUAL_HIERARCHY_V2_ENTITY_ID_INVALID")
                .doesNotContain("TARGETED_CROP")
                .contains("\"groundingPlan\":{");
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_V2_ENTITY_ID_INVALID", 1
                ));
    }

    @Test
    void hierarchyWithoutObservedGroupsRewindsAndRecoversObservationAfterLeaseExpiry() {
        assertThat(InferenceStage.HIERARCHY.canTransitionTo(InferenceStage.OBSERVE)).isFalse();
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "grounded-observation-rewind");
        var firstProvider = new ScriptedProvider(
                request -> response(request, flatGroundedStationElements()),
                request -> response(request, groundedStationHierarchy())
        );
        var firstWorker = worker(firstProvider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "grounded-observation-rewind-first", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();

        current = firstWorker.advance(current);
        current = firstWorker.advance(current);

        assertThat(current.stage()).isEqualTo(InferenceStage.OBSERVE);
        assertThat(current.checkpointJson())
                .contains("\"completedStage\": \"NORMALIZE\"")
                .contains("\"providerCalls\": 2")
                .doesNotContain("renderweave-visual-grounding/2.0")
                .doesNotContain("renderweave-visual-hierarchy/2.0");
        assertThat(firstProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE, InferenceStage.HIERARCHY);
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING", 1
                ));

        var recoveryProvider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var finished = worker(recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("grounded-observation-rewind-recovery").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(recoveryProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(recoveryProvider.requests.getFirst().taskJson())
                .contains("VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING")
                .doesNotContain("TARGETED_CROP")
                .contains("\"elementInventory\":null")
                .contains("\"groundingPlan\":null");
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.REJECTED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
    }

    @Test
    void relationshipRegionWithoutGroupOwnerRewindsAndRecoversObservationAfterLeaseExpiry() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "relationship-region-owner-rewind", REGION_OWNED_HIERARCHY_PROFILE
        );
        var firstProvider = new ScriptedProvider(
                request -> response(request, groundedStationElementsWithoutNoticeRegionOwner()),
                request -> response(request, groundedStationHierarchy())
        );
        var firstWorker = worker(firstProvider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "relationship-region-owner-first", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();

        current = firstWorker.advance(current);
        current = firstWorker.advance(current);

        assertThat(current.stage()).isEqualTo(InferenceStage.OBSERVE);
        assertThat(current.checkpointJson())
                .contains("\"completedStage\": \"NORMALIZE\"")
                .contains("\"providerCalls\": 2")
                .doesNotContain("renderweave-visual-grounding/2.0")
                .doesNotContain("renderweave-visual-hierarchy/2.0");
        assertThat(firstProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE, InferenceStage.HIERARCHY);
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_REGION_GROUP_MISSING", 1
                ));

        var recoveryProvider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var finished = worker(recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("relationship-region-owner-recovery").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(recoveryProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(recoveryProvider.requests.getFirst().taskJson())
                .contains("VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_REGION_GROUP_MISSING")
                .doesNotContain("TARGETED_CROP")
                .contains("\"elementInventory\":null")
                .contains("\"groundingPlan\":null");
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.REJECTED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
    }

    @Test
    void detailedHierarchyRegionRepairStaysLocalAndRecoversAfterLeaseExpiry() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "detailed-hierarchy-region-repair", DIAGNOSTIC_HIERARCHY_PROFILE
        );
        var firstProvider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy().replace(
                        "\"regionIds\":[\"root\"]", "\"regionIds\":[\"header\"]"
                ))
        );
        var firstWorker = worker(firstProvider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "detailed-hierarchy-region-first", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();

        current = firstWorker.advance(current);
        current = firstWorker.advance(current);

        assertThat(current.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(current.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("renderweave-visual-hierarchy/2.0");
        assertThat(workflowStore.attempts(created)).hasSize(2);
        assertThat(firstProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE, InferenceStage.HIERARCHY);
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_V2_ROOT_REGION_OWNERSHIP_INVALID", 1
                ));

        var recoveryProvider = new ScriptedProvider(
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var finished = worker(recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("detailed-hierarchy-region-recovery").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(recoveryProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.HIERARCHY, InferenceStage.ELEMENT_BINDING);
        assertThat(recoveryProvider.requests.getFirst().taskJson())
                .contains("VISUAL_HIERARCHY_V2_ROOT_REGION_OWNERSHIP_INVALID")
                .doesNotContain("TARGETED_CROP");
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.REJECTED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED
                );
    }

    @Test
    void exactDuplicateRelationshipSupportIdsNormalizeAndResumeAtBindingAfterLeaseExpiry() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "support-id-normalization", SUPPORT_NORMALIZED_HIERARCHY_PROFILE
        );
        var duplicateSupport = groundedStationHierarchy().replace(
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\"]",
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\",\"route-group\"]"
        );
        var firstProvider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, duplicateSupport)
        );
        var firstWorker = worker(firstProvider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "support-id-normalization-first", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();

        current = firstWorker.advance(current);
        current = firstWorker.advance(current);

        assertThat(current.stage()).isEqualTo(InferenceStage.ELEMENT_BINDING);
        assertThat(current.checkpointJson()).contains("renderweave-visual-hierarchy/2.0");
        assertThat(firstProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE, InferenceStage.HIERARCHY);
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_IDS_NORMALIZED", 1
                ));

        var recoveryProvider = new ScriptedProvider(
                request -> response(request, groundedStationBindings())
        );
        var finished = worker(recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("support-id-normalization-recovery").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(recoveryProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.ELEMENT_BINDING);
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
    }

    @Test
    void uniqueEvidenceOwnedRelationshipRegionNormalizesAndResumesAtBindingAfterLeaseExpiry() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "relationship-region-normalization", REGION_NORMALIZED_HIERARCHY_PROFILE
        );
        var wrongRegionAndDuplicateSupport = groundedStationHierarchy().replace(
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\"]",
                "\"regionId\":\"root\",\"supportingElementIds\":[\"route-group\",\"route-group\"]"
        );
        var firstProvider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, wrongRegionAndDuplicateSupport)
        );
        var firstWorker = worker(firstProvider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "relationship-region-normalization-first", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        current = firstWorker.advance(current);
        current = firstWorker.advance(current);

        assertThat(current.stage()).isEqualTo(InferenceStage.ELEMENT_BINDING);
        assertThat(current.checkpointJson()).contains("renderweave-visual-hierarchy/2.0");
        assertThat(firstProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE, InferenceStage.HIERARCHY);
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_IDS_NORMALIZED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_REGION_NORMALIZED", 1
                ));

        var recoveryProvider = new ScriptedProvider(
                request -> response(request, groundedStationBindings())
        );
        var finished = worker(recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("relationship-region-normalization-recovery").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(recoveryProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.ELEMENT_BINDING);
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
    }

    @Test
    void uniqueConnectedRelationshipRegionNormalizesAndResumesAtBindingAfterLeaseExpiry() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "relationship-region-connection-normalization",
                CONNECTION_NORMALIZED_HIERARCHY_PROFILE
        );
        var disconnectedRegion = groundedStationHierarchy().replace(
                "\"relationshipId\":\"board-routes\",\"parentEntityId\":\"board\",\"childEntityId\":\"route\",\"fieldKey\":\"routes\",\"displayName\":\"线路\",\"cardinality\":\"MANY\",\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\"]",
                "\"relationshipId\":\"board-routes\",\"parentEntityId\":\"board\",\"childEntityId\":\"route\",\"fieldKey\":\"routes\",\"displayName\":\"线路\",\"cardinality\":\"MANY\",\"regionId\":\"stops\",\"supportingElementIds\":[\"route-group\"]"
        );
        var firstProvider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, disconnectedRegion)
        );
        var firstWorker = worker(firstProvider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "relationship-region-connection-normalization-first", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        current = firstWorker.advance(current);
        current = firstWorker.advance(current);

        assertThat(current.stage()).isEqualTo(InferenceStage.ELEMENT_BINDING);
        assertThat(current.checkpointJson()).contains("renderweave-visual-hierarchy/2.0");
        assertThat(firstProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE, InferenceStage.HIERARCHY);
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3,
                        "VISUAL_HIERARCHY_RELATIONSHIP_REGION_NORMALIZED", 1
                ));

        var recoveryProvider = new ScriptedProvider(
                request -> response(request, groundedStationBindings())
        );
        var finished = worker(recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("relationship-region-connection-normalization-recovery").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(recoveryProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.ELEMENT_BINDING);
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
    }

    @Test
    void uniqueRelationshipRegionGroupOwnerNormalizesAndResumesAtBindingAfterLeaseExpiry() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "relationship-support-owner-normalization",
                SUPPORT_OWNER_NORMALIZED_HIERARCHY_PROFILE
        );
        var slotSupport = groundedStationHierarchy().replace(
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\"]",
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-number\"]"
        );
        var firstProvider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, slotSupport)
        );
        var firstWorker = worker(firstProvider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "relationship-support-owner-normalization-first", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        current = firstWorker.advance(current);
        current = firstWorker.advance(current);

        assertThat(current.stage()).isEqualTo(InferenceStage.ELEMENT_BINDING);
        assertThat(current.checkpointJson()).contains("renderweave-visual-hierarchy/2.0");
        assertThat(firstProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE, InferenceStage.HIERARCHY);
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED", 1
                ));

        var recoveryProvider = new ScriptedProvider(
                request -> response(request, groundedStationBindings())
        );
        var finished = worker(recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("relationship-support-owner-normalization-recovery").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(recoveryProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.ELEMENT_BINDING);
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
    }

    @Test
    void groundedStageLocalRepairResumesAfterLeaseExpiryWithoutReobserving() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "grounded-semantic-lease-recovery");
        var firstProvider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchyWithoutNoticeEdge())
        );
        var firstWorker = worker(firstProvider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "grounded-semantic-first", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();

        current = firstWorker.advance(current);
        current = firstWorker.advance(current);

        assertThat(current.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(current.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("renderweave-visual-hierarchy/2.0");
        assertThat(firstProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE, InferenceStage.HIERARCHY);

        var recoveryProvider = new ScriptedProvider(
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var finished = worker(recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)))
                .processNext("grounded-semantic-recovery").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(recoveryProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.HIERARCHY, InferenceStage.ELEMENT_BINDING);
        assertThat(recoveryProvider.requests.getFirst().taskJson())
                .contains("TARGETED_CROP")
                .contains("VISUAL_SEMANTIC_HIERARCHY_GROUP_EDGE_MISSING");
        assertThat(workflowStore.attempts(created)).extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.REJECTED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED
                );
    }

    @Test
    void groundedStageLocalRepairCancelsWithoutReplayingSuccessfulObservation() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "grounded-semantic-cancel");
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchyWithoutNoticeEdge())
        );
        var activeWorker = worker(provider, blobs, T0.plusSeconds(1));
        var current = runs.claimNextLive(
                "grounded-semantic-cancel-first", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();
        current = activeWorker.advance(current);
        current = activeWorker.advance(current);

        var cancelling = runs.requestCancellation(created, T0.plusSeconds(2));
        var finished = activeWorker.drain(cancelling);

        assertThat(finished.state()).isEqualTo(InferenceRunState.CANCELLED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE, InferenceStage.HIERARCHY);
        assertThat(workflowStore.attempts(created)).hasSize(2);
        assertThat(finished.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("renderweave-visual-hierarchy/2.0");
    }

    @Test
    void groundedLengthStopPersistsTruncationWithoutParsingPartialPayload() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "grounded-length-diagnostic");
        var provider = new ScriptedProvider(
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, "{\"partial\":true}", "length")
        );

        var finished = worker(provider, blobs).processNext("grounded-length-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains("VISUAL_GROUNDING_OUTPUT_TRUNCATED");
        assertThat(workflowStore.attempts(created)).hasSize(5).allSatisfy(attempt ->
                assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(
                        Map.of("VISUAL_GROUNDING_OUTPUT_TRUNCATED", 1)
                )
        );
    }

    @Test
    void pipelineFourPointTwentyNineStopsAfterTheThirdEquivalentRejectedAttempt() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v47-equivalent-reject-breaker", MAX_V47_SUCCESSOR_PROFILE
        );
        var provider = new ScriptedProvider(repeatSteps(12,
                request -> response(request, "{\"partial\":true}", "length")));
        var preprocessCalls = new AtomicInteger();

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(preprocessCalls, "OCR_SENTINEL_V47_BREAKER")
        ).processNext("v47-equivalent-reject-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains("VISUAL_GROUNDING_OUTPUT_TRUNCATED");
        assertThat(provider.requests).hasSize(3).allSatisfy(request ->
                assertThat(request.stage()).isEqualTo(InferenceStage.OBSERVE));
        assertThat(workflowStore.attempts(created)).hasSize(3).allSatisfy(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.REJECTED);
            assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(
                    Map.of("VISUAL_GROUNDING_OUTPUT_TRUNCATED", 1));
        });
        assertThat(preprocessCalls).hasValue(1);
    }

    @Test
    void pipelineFourPointThirtyStopsAfterTheThirdAllowlistedEquivalentRejectedAttempt() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v48-equivalent-reject-breaker", MAX_V48_REGION_RECOVERY_PROFILE
        );
        var provider = new ScriptedProvider(repeatSteps(12,
                request -> response(request, "{\"partial\":true}", "length")));

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V48_BREAKER")
        ).processNext("v48-equivalent-reject-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains("VISUAL_GROUNDING_OUTPUT_TRUNCATED");
        assertThat(provider.requests).hasSize(3).allSatisfy(request ->
                assertThat(request.stage()).isEqualTo(InferenceStage.OBSERVE));
        assertThat(workflowStore.attempts(created)).hasSize(3).allSatisfy(attempt ->
                assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(
                        Map.of("VISUAL_GROUNDING_OUTPUT_TRUNCATED", 1)
                ));
    }

    @Test
    void pipelineFourPointThirtyFailsAnUnlistedObserveCodeWithoutAnotherReservation() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v48-unlisted-observe-code", MAX_V48_REGION_RECOVERY_PROFILE
        );
        var invalidVersion = groundedStationElements().replace(
                "renderweave-visual-grounding/2.0", "renderweave-visual-grounding/9.0"
        );
        var provider = new ScriptedProvider(request -> response(request, invalidVersion));

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V48_UNLISTED")
        ).processNext("v48-unlisted-observe-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains("VISUAL_GROUNDING_VERSION_INVALID");
        assertThat(provider.requests).hasSize(1);
        assertThat(workflowStore.attempts(created)).hasSize(1).first().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.REJECTED);
            assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(
                    Map.of("VISUAL_GROUNDING_VERSION_INVALID", 1));
        });
    }

    @Test
    void pipelineFourPointThirtyFeedsFieldSpecificEvidenceCodeIntoOneBoundedCorrection() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v48-field-specific-correction", MAX_V48_REGION_RECOVERY_PROFILE
        );
        var invalidEvidence = groundedStationElements().replaceFirst(
                "view-00-overview-00", "unknown-view"
        );
        var provider = new ScriptedProvider(
                request -> response(request, invalidEvidence),
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V48_CORRECTION")
        ).processNext("v48-field-specific-correction-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY, InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(1).taskJson())
                .contains("VISUAL_GROUNDING_REGION_EVIDENCE_INVALID");
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID", 1
                ));
    }

    @Test
    void pipelineFourPointThirtyOnePersistsMixedEnvelopeBeforeRetryPermit() {
        var blobs = new MemoryBlobStore();
        var created = createV49EnvelopeProbe(blobs, "v49-mixed-envelope");
        var mixed = groundedStationElements()
                .replaceFirst("\"regionId\":\"header\"", "\"regionId\":\"Header\"")
                .replaceFirst(
                        "\"parentRegionId\":\"root\"", "\"parentRegionId\":\"Root\""
                );
        var provider = new ScriptedProvider(request -> response(request, mixed));

        var claimed = runs.claim(
                created, "v49-envelope-worker", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();
        var advanced = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V49_ENVELOPE")
        ).advance(claimed);

        assertThat(advanced.state()).isEqualTo(InferenceRunState.RUNNING);
        assertThat(advanced.stage()).isEqualTo(InferenceStage.OBSERVE);
        assertThat(provider.requests).hasSize(1);
        var attempt = workflowStore.attempts(created).getFirst();
        assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.REJECTED);
        assertThat(attempt.problemCodeCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "VISUAL_GROUNDING_REGION_ID_INVALID", 1,
                "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID", 1
        ));
        assertThat(attempt.rejectionEnvelope()).contains(new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(
                        "VISUAL_GROUNDING_REGION_ID_INVALID",
                        "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"
                )
        ));
        assertThat(jdbcClient.sql("""
                        select rejection_envelope::text
                        from inference_attempt
                        where run_id = :runId and attempt_ordinal = 0
                        """)
                .param("runId", created)
                .query(String.class)
                .single()).contains(
                "VISUAL_GROUNDING_REGION_FIELDS_INVALID",
                "VISUAL_GROUNDING_REGION_ID_INVALID",
                "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"
        );
        assertThat(jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId and state = 'SETTLED'
                        """)
                .param("runId", created)
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId and state = 'RESERVED'
                        """)
                .param("runId", created)
                .query(Long.class).single()).isZero();
    }

    @Test
    void postgresRoundTripsUnclassifiedEnvelopeBeforePrimaryOnlyTerminal() {
        var blobs = new MemoryBlobStore();
        var created = createV49EnvelopeProbe(blobs, "v49-unclassified-envelope");
        var claimed = runs.claim(
                created, "v49-unclassified-worker", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();
        var envelope = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.UNCLASSIFIED_REGION_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of()
        );
        var recorded = workflowStore.recordAttempt(
                created, claimed.lease().orElseThrow().token(),
                new InferenceAttempt(
                        created, 0, InferenceStage.OBSERVE, InferenceAttemptStatus.REJECTED,
                        "LIVE_VISUAL_ANALYSIS_REJECTED", Optional.empty(), Optional.empty(),
                        0, 0, 0, 0, Map.of(), Optional.of(envelope), T0.plusSeconds(2)
                ),
                T0.plusSeconds(2)
        );
        var terminal = runs.fail(
                created, recorded.lease().orElseThrow().token(), envelope.primaryCode(),
                T0.plusSeconds(3)
        );

        assertThat(terminal.failureCode()).contains(
                InferenceRejectionEnvelope.UNCLASSIFIED_REGION_PRIMARY_CODE
        );
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.problemCodeCounts()).isEmpty();
            assertThat(attempt.rejectionEnvelope()).contains(envelope);
        });
    }

    @Test
    void expiredLeaseReplaysPersistedUnclassifiedEnvelopeWithoutAnotherProviderPermit() {
        var blobs = new MemoryBlobStore();
        var created = createV49EnvelopeProbe(blobs, "v49-envelope-crash-replay");
        var claimed = runs.claim(
                created, "crashed-v49-envelope-worker", T0.plusSeconds(1),
                Duration.ofSeconds(5)
        ).orElseThrow();
        var envelope = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.UNCLASSIFIED_REGION_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of()
        );
        workflowStore.recordAttempt(
                created, claimed.lease().orElseThrow().token(),
                new InferenceAttempt(
                        created, 0, InferenceStage.OBSERVE, InferenceAttemptStatus.REJECTED,
                        "LIVE_VISUAL_ANALYSIS_REJECTED", Optional.empty(), Optional.empty(),
                        0, 0, 0, 0, envelope.detailCodeCounts(), Optional.of(envelope),
                        T0.plusSeconds(2)
                ),
                T0.plusSeconds(2)
        );
        var provider = new ScriptedProvider(request -> {
            throw new AssertionError("persisted terminal envelope must not issue Provider permit");
        });

        var terminal = worker(
                provider, blobs, T0.plusSeconds(7),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V49_REPLAY")
        ).processNext("replacement-v49-envelope-worker").orElseThrow();

        assertThat(terminal.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(terminal.failureCode()).contains(envelope.primaryCode());
        assertThat(provider.requests).isEmpty();
        assertThat(workflowStore.attempts(created)).singleElement()
                .extracting(InferenceAttempt::rejectionEnvelope)
                .isEqualTo(Optional.of(envelope));
    }

    @Test
    void expiredLeaseAfterThirdEquivalentMixedSetStopsBeforeAnotherProviderPermit() {
        var blobs = new MemoryBlobStore();
        var created = createV49EnvelopeProbe(blobs, "v49-mixed-breaker-crash-replay");
        var current = runs.claim(
                created, "crashed-v49-mixed-breaker-worker", T0.plusSeconds(1),
                Duration.ofSeconds(5)
        ).orElseThrow();
        var envelope = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(
                        "VISUAL_GROUNDING_REGION_ID_INVALID",
                        "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"
                )
        );
        for (var ordinal = 0; ordinal < 3; ordinal++) {
            current = workflowStore.recordAttempt(
                    created, current.lease().orElseThrow().token(),
                    new InferenceAttempt(
                            created, ordinal, InferenceStage.OBSERVE,
                            InferenceAttemptStatus.REJECTED,
                            "LIVE_VISUAL_ANALYSIS_REJECTED", Optional.empty(), Optional.empty(),
                            0, 0, 0, 0, envelope.detailCodeCounts(), Optional.of(envelope),
                            T0.plusSeconds(2 + ordinal)
                    ),
                    T0.plusSeconds(2 + ordinal)
            );
        }
        var provider = new ScriptedProvider(request -> {
            throw new AssertionError("third equivalent mixed set must trip persisted breaker");
        });

        var terminal = worker(
                provider, blobs, T0.plusSeconds(7),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V49_BREAKER_REPLAY")
        ).processNext("replacement-v49-mixed-breaker-worker").orElseThrow();

        assertThat(terminal.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(terminal.failureCode()).contains(envelope.primaryCode());
        assertThat(provider.requests).isEmpty();
        assertThat(workflowStore.attempts(created)).hasSize(3).allSatisfy(attempt ->
                assertThat(attempt.rejectionEnvelope()).contains(envelope)
        );
    }

    @Test
    void postgresRejectsMalformedMixedEnvelopeBeforeItCanEnterTheLedger() {
        var blobs = new MemoryBlobStore();
        var created = createV49EnvelopeProbe(blobs, "v49-malformed-envelope");
        var claimed = runs.claim(
                created, "v49-malformed-envelope-worker", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();
        var valid = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.UNCLASSIFIED_REGION_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of()
        );
        workflowStore.recordAttempt(
                created, claimed.lease().orElseThrow().token(),
                new InferenceAttempt(
                        created, 0, InferenceStage.OBSERVE, InferenceAttemptStatus.REJECTED,
                        "LIVE_VISUAL_ANALYSIS_REJECTED", Optional.empty(), Optional.empty(),
                        0, 0, 0, 0, Map.of(), Optional.of(valid), T0.plusSeconds(2)
                ),
                T0.plusSeconds(2)
        );
        var malformed = """
                {"primaryCode":"VISUAL_GROUNDING_REGION_FIELDS_INVALID",
                 "earliestStage":"OBSERVE",
                 "detailCodes":["VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
                                "VISUAL_GROUNDING_REGION_ID_INVALID"],
                 "detailCodeCount":2}
                """;

        assertThatThrownBy(() -> jdbcClient.sql("""
                        update inference_attempt
                        set rejection_envelope = cast(:envelope as jsonb)
                        where run_id = :runId
                        """)
                .param("envelope", malformed)
                .param("runId", created)
                .update()).isInstanceOf(RuntimeException.class);
        assertThat(workflowStore.attempts(created)).singleElement()
                .extracting(InferenceAttempt::rejectionEnvelope)
                .isEqualTo(Optional.of(valid));
    }

    @Test
    void pipelineFourPointThirtyOneCorrectsPromptCoveredMixedFieldsToReviewRequired() {
        var blobs = new MemoryBlobStore();
        var created = createV49EnvelopeProbe(blobs, "v49-mixed-correction");
        var mixed = mixedRegionIdAndParent(
                "UNSAFE_REGION_VALUE_ALPHA", "UNSAFE_REGION_VALUE_BETA"
        );
        var provider = new ScriptedProvider(
                request -> response(request, mixed),
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var staticSchemaCountBefore = jdbcClient.sql("select count(*) from static_schema")
                .query(Long.class).single();

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V49_MIXED_CORRECTION")
        ).processNext("v49-mixed-correction-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY, InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(1).taskJson())
                .contains(
                        "VISUAL_GROUNDING_REGION_ID_INVALID",
                        "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"
                )
                .doesNotContain("UNSAFE_REGION_VALUE_ALPHA", "UNSAFE_REGION_VALUE_BETA");
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).getFirst().rejectionEnvelope())
                .contains(new InferenceRejectionEnvelope(
                        InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                        InferenceStage.OBSERVE,
                        List.of(
                                "VISUAL_GROUNDING_REGION_ID_INVALID",
                                "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"
                        )
                ));
        assertThat(jdbcClient.sql("select count(*) from schema_draft")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from static_schema")
                .query(Long.class).single()).isEqualTo(staticSchemaCountBefore);
    }

    @Test
    void pipelineFourPointThirtyOneBreaksBeforeTheFourthEquivalentMixedSet() {
        var blobs = new MemoryBlobStore();
        var created = createV49EnvelopeProbe(blobs, "v49-mixed-set-breaker");
        var mixed = mixedRegionIdAndParent("InvalidAlpha", "InvalidBeta");
        var provider = new ScriptedProvider(repeatSteps(
                12, request -> response(request, mixed)
        ));

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V49_MIXED_BREAKER")
        ).processNext("v49-mixed-breaker-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE
        );
        assertThat(provider.requests).hasSize(3);
        assertThat(workflowStore.attempts(created)).hasSize(3).allSatisfy(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.REJECTED);
            assertThat(attempt.rejectionEnvelope()).isPresent();
        });
        assertThat(jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId and state = 'SETTLED'
                        """)
                .param("runId", created).query(Long.class).single()).isEqualTo(3L);
        assertThat(jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId and state = 'RESERVED'
                        """)
                .param("runId", created).query(Long.class).single()).isZero();
    }

    @Test
    void pipelineFourPointThirtyTwoCanonicalizesOpaqueLocalIdsWithoutARepairCall() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v50-local-id-canonicalization",
                MAX_V50_LOCAL_ID_CANONICALIZATION_PROFILE
        );
        var provider = new ScriptedProvider(
                request -> response(request, v50OpaqueElements()),
                request -> response(request, v50CanonicalHierarchy()),
                request -> response(request, v50CanonicalBindings())
        );
        var staticSchemaCountBefore = jdbcClient.sql("select count(*) from static_schema")
                .query(Long.class).single();

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V50_LOCAL_IDS")
        ).processNext("v50-local-id-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(1).taskJson())
                .contains("r1", "r3", "e1", "e2")
                .doesNotContain("Opaque Root", "Opaque Repeat", "Opaque Title");
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_GROUNDING_LOCAL_ID_CLASSES_CANONICALIZED", 9
                ));
        assertThat(jdbcClient.sql("select count(*) from schema_draft")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from static_schema")
                .query(Long.class).single()).isEqualTo(staticSchemaCountBefore);
    }

    @Test
    void pipelineFourPointThirtyThreeClassifiesContainmentAndStopsBeforeSecondPermit() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v51-parent-containment-provenance",
                MAX_V51_PARENT_CONTAINMENT_PROVENANCE_PROFILE
        );
        var cyclicOutsideParent = groundedStationElements().replace(
                "\"regionId\":\"routes\",\"parentRegionId\":\"root\"",
                "\"regionId\":\"routes\",\"parentRegionId\":\"stop-item\""
        );
        var provider = new ScriptedProvider(
                request -> response(request, cyclicOutsideParent)
        );

        var claimed = runs.claimNextLive(
                "v51-containment-worker", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();
        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V51_CONTAINMENT")
        ).drain(claimed);

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains(
                InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE
        );
        assertThat(provider.requests).singleElement().satisfies(request ->
                assertThat(request.stage()).isEqualTo(InferenceStage.OBSERVE)
        );
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.REJECTED);
            assertThat(attempt.rejectionEnvelope()).contains(new InferenceRejectionEnvelope(
                    InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE,
                    InferenceStage.OBSERVE,
                    List.of(
                            "VISUAL_GROUNDING_PARENT_CONTAINMENT_NON_ITEM_ZERO_COMPATIBLE"
                    )
            ));
        });
        assertThat(jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId and state = 'SETTLED'
                        """)
                .param("runId", created).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId and state = 'RESERVED'
                        """)
                .param("runId", created).query(Long.class).single()).isZero();
    }

    @Test
    void pipelineFourPointThirtyFourNormalizesExactItemParentEnvelopeBeforeSecondPermit() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v52-item-parent-envelope-normalization",
                MAX_V52_ITEM_PARENT_ENVELOPE_NORMALIZATION_PROFILE
        );
        var shrunkenRepeatedGroup = v50OpaqueElements().replace(
                "\"regionId\":\"Opaque Repeat\",\"parentRegionId\":\"Opaque Root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":1,\"repeatGroupId\":\"Opaque Rows\",\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":2000,\"right\":10000,\"bottom\":10000}}]",
                "\"regionId\":\"Opaque Repeat\",\"parentRegionId\":\"Opaque Root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":1,\"repeatGroupId\":\"Opaque Rows\",\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":2000,\"right\":8000,\"bottom\":9000}}]"
        );
        var provider = new ScriptedProvider(
                request -> response(request, shrunkenRepeatedGroup),
                request -> response(request, v50CanonicalHierarchy()),
                request -> response(request, v50CanonicalBindings())
        );
        var staticSchemaCountBefore = jdbcClient.sql("select count(*) from static_schema")
                .query(Long.class).single();

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V52_PARENT_ENVELOPE")
        ).processNext("v52-parent-envelope-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsEntry("VISUAL_GROUNDING_REPEATED_GROUP_ENVELOPE_NORMALIZED", 1);
        assertThat(jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId and state = 'SETTLED'
                        """)
                .param("runId", created).query(Long.class).single()).isEqualTo(3L);
        assertThat(jdbcClient.sql("""
                        select count(*) from inference_provider_reservation
                        where run_id = :runId and state = 'RESERVED'
                        """)
                .param("runId", created).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from schema_draft")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from static_schema")
                .query(Long.class).single()).isEqualTo(staticSchemaCountBefore);
    }

    @Test
    void changingMixedSetsRemainIsolatedButCannotBypassTheTotalCallCap() {
        var blobs = new MemoryBlobStore();
        var created = createV49EnvelopeProbe(blobs, "v49-changing-mixed-cap", 3);
        var setA = mixedRegionIdAndParent("InvalidAlpha", "InvalidBeta");
        var setB = mixedRegionIdAndReadingOrder("InvalidGamma");
        var provider = new ScriptedProvider(
                request -> response(request, setA),
                request -> response(request, setB),
                request -> response(request, setA)
        );

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V49_MIXED_CAP")
        ).processNext("v49-changing-mixed-cap-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE
        );
        assertThat(provider.requests).hasSize(3);
        assertThat(workflowStore.attempts(created)).extracting(attempt ->
                        attempt.rejectionEnvelope().orElseThrow().detailCodes())
                .containsExactly(
                        List.of(
                                "VISUAL_GROUNDING_REGION_ID_INVALID",
                                "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"
                        ),
                        List.of(
                                "VISUAL_GROUNDING_REGION_ID_INVALID",
                                "VISUAL_GROUNDING_REGION_READING_ORDER_INVALID"
                        ),
                        List.of(
                                "VISUAL_GROUNDING_REGION_ID_INVALID",
                                "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"
                        )
                );
    }

    @Test
    void equivalentRejectCountsAreIsolatedByCodeAndStage() {
        var invalidVersion = groundedStationElements().replace(
                "renderweave-visual-grounding/2.0", "renderweave-visual-grounding/9.0"
        );

        var codeBlobs = new MemoryBlobStore();
        var codeRun = createGroundedVisual(
                codeBlobs, "v47-code-isolation", MAX_V47_SUCCESSOR_PROFILE
        );
        var codeProvider = new ScriptedProvider(
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, invalidVersion),
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, invalidVersion),
                request -> response(request, "{\"partial\":true}", "length")
        );
        var codeFinished = worker(
                codeProvider, codeBlobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V47_CODE_ISOLATION")
        ).processNext("v47-code-isolation-worker").orElseThrow();

        assertThat(codeFinished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(codeProvider.requests).hasSize(5);
        assertThat(workflowStore.attempts(codeRun)).extracting(attempt ->
                attempt.problemCodeCounts().keySet().iterator().next())
                .containsExactly(
                        "VISUAL_GROUNDING_OUTPUT_TRUNCATED",
                        "VISUAL_GROUNDING_VERSION_INVALID",
                        "VISUAL_GROUNDING_OUTPUT_TRUNCATED",
                        "VISUAL_GROUNDING_VERSION_INVALID",
                        "VISUAL_GROUNDING_OUTPUT_TRUNCATED"
                );

        var stageBlobs = new MemoryBlobStore();
        var stageRun = createGroundedVisual(
                stageBlobs, "v47-stage-isolation", MAX_V47_SUCCESSOR_PROFILE
        );
        var stageProvider = new ScriptedProvider(
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, groundedStationElements()),
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, "{\"partial\":true}", "length"),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var stageFinished = worker(
                stageProvider, stageBlobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V47_STAGE_ISOLATION")
        ).processNext("v47-stage-isolation-worker").orElseThrow();

        assertThat(stageFinished.state())
                .as("failure=%s", stageFinished.failureCode())
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(stageProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.OBSERVE, InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY, InferenceStage.HIERARCHY,
                        InferenceStage.HIERARCHY, InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(stageRun)).hasSize(7);
    }

    @Test
    void historicalPipelineFourPointTwentyEightKeepsItsTwelveCallSnapshot() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v46-equivalent-reject-history", MAX_V46_CERTIFICATION_PROFILE
        );
        var provider = new ScriptedProvider(repeatSteps(12,
                request -> response(request, "{\"partial\":true}", "length")));

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(new AtomicInteger(), "OCR_SENTINEL_V46_HISTORY")
        ).processNext("v46-equivalent-reject-history-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(provider.requests).hasSize(12);
        assertThat(workflowStore.attempts(created)).hasSize(12);
    }

    @Test
    void pipelineFourPointTwoUsesOneEphemeralOcrObservationAcrossAllVisualStages() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "hybrid-visual-station", HYBRID_VISUAL_PROFILE);
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(preprocessCalls, "OCR_SENTINEL_STATION_NAME");

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("hybrid-visual-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).hasSize(3).allSatisfy(request -> {
            assertThat(request.taskJson())
                    .contains("renderweave-live-task/5.0")
                    .contains("documentVisionObservation")
                    .contains("OCR_SENTINEL_STATION_NAME");
            assertThat(request.systemPrompt())
                    .contains("untrusted image content")
                    .contains("secondary evidence")
                    .doesNotContain("停靠站点");
        });
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_STATION_NAME")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_STATION_NAME")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_STATION_NAME")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTenUsesOneEphemeralOcrObservationAndRetainsSupportOwnerPolicy() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "support-owner-hybrid-visual-station", SUPPORT_OWNER_HYBRID_VISUAL_PROFILE
        );
        var slotSupport = groundedStationHierarchy().replace(
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\"]",
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-number\"]"
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, slotSupport),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(preprocessCalls, "OCR_SENTINEL_V23_STATION_NAME");

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("support-owner-hybrid-visual-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).hasSize(3).allSatisfy(request -> {
            assertThat(request.taskJson())
                    .contains("renderweave-live-task/5.0")
                    .contains("documentVisionObservation")
                    .contains("OCR_SENTINEL_V23_STATION_NAME");
            assertThat(request.systemPrompt())
                    .contains("untrusted image content")
                    .contains("secondary evidence")
                    .doesNotContain("停靠站点");
        });
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED", 1
                ));
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V23_STATION_NAME")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V23_STATION_NAME")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V23_STATION_NAME")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointElevenNormalizesOnlyBoundedObservationDriftAndRetainsLaterPolicies() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "bounded-observation-hybrid-station",
                BOUNDED_OBSERVATION_HYBRID_VISUAL_PROFILE
        );
        var boundedObservationDrift = groundedStationElements()
                .replaceFirst("\"kind\":\"ROOT\"", "\"kind\":\"DOCUMENT\"")
                .replace(
                        "\"regionId\":\"notice\",\"parentRegionId\":\"root\",\"kind\":\"GROUP\"",
                        "\"regionId\":\"notice\",\"parentRegionId\":\"root\",\"kind\":\"container\""
                )
                .replace(
                        "\"regionId\":\"route-item\",\"parentRegionId\":\"routes\",\"kind\":\"ITEM\",\"multiplicity\":\"ONE\",\"readingOrder\":0",
                        "\"regionId\":\"route-item\",\"parentRegionId\":\"root\",\"kind\":\"item\",\"multiplicity\":\"ONE\",\"readingOrder\":3"
                );
        var slotSupport = groundedStationHierarchy().replace(
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\"]",
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-number\"]"
        );
        var provider = new ScriptedProvider(
                request -> response(request, boundedObservationDrift),
                request -> response(request, slotSupport),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V24_STATION_NAME"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("bounded-observation-hybrid-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).hasSize(3).allSatisfy(request ->
                assertThat(request.taskJson())
                        .contains("renderweave-live-task/5.0")
                        .contains("documentVisionObservation")
                        .contains("OCR_SENTINEL_V24_STATION_NAME")
        );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_GROUNDING_REGION_KIND_NORMALIZED", 3,
                        "VISUAL_GROUNDING_ITEM_PARENT_NORMALIZED", 1,
                        "VISUAL_GROUNDING_READING_ORDER_NORMALIZED", 1
                ));
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED", 1
                ));
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V24_STATION_NAME")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V24_STATION_NAME")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V24_STATION_NAME")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwelveRetriesContainerSizedSlotOnlyAtObservation() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "leaf-evidence-verified-station",
                LEAF_EVIDENCE_VERIFIED_HYBRID_VISUAL_PROFILE
        );
        var containerSlot = groundedStationElements().replace(
                "\"elementId\":\"notice-group\",\"kind\":\"GROUP\",\"proposedKey\":\"warmNotice\",\"displayName\":\"温馨提示\",\"multiplicity\":\"ONE\",\"valueHint\":null",
                "\"elementId\":\"notice-group\",\"kind\":\"SLOT\",\"proposedKey\":\"warmNotice\",\"displayName\":\"温馨提示\",\"multiplicity\":\"ONE\",\"valueHint\":\"UNRESOLVED\""
        );
        var provider = new ScriptedProvider(
                request -> response(request, containerSlot),
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V25_STATION_NAME"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("leaf-evidence-verified-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "OBSERVE", "HIERARCHY", "ELEMENT_BINDING");
        assertThat(provider.requests.get(1).taskJson())
                .contains("VISUAL_SEMANTIC_SLOT_EVIDENCE_CONTAINS_ELEMENT");
        assertThat(workflowStore.attempts(created))
                .extracting(attempt -> attempt.status())
                .containsExactly(
                        InferenceAttemptStatus.REJECTED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_SLOT_EVIDENCE_CONTAINS_ELEMENT", 1
                ));
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V25_STATION_NAME")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V25_STATION_NAME")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V25_STATION_NAME")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointThirteenNormalizesOneEnclosingConnectedSupportOwner() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "enclosing-support-owner-station",
                ENCLOSING_SUPPORT_OWNER_HYBRID_VISUAL_PROFILE
        );
        var supportRegionDeadlock = groundedStationHierarchy().replace(
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\"]",
                "\"regionId\":\"route-item\",\"supportingElementIds\":[\"route-number\"]"
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, supportRegionDeadlock),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V26_STATION_NAME"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("enclosing-support-owner-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "HIERARCHY", "ELEMENT_BINDING");
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_ENCLOSING_SUPPORT_OWNER_NORMALIZED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_REGION_NORMALIZED", 1
                ));
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V26_STATION_NAME")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V26_STATION_NAME")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V26_STATION_NAME")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointFourteenNormalizesOneSourceAncestorSupportOwner() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "source-ancestor-support-owner-station",
                SOURCE_ANCESTOR_SUPPORT_OWNER_HYBRID_VISUAL_PROFILE
        );
        var provider = new ScriptedProvider(
                request -> response(request, sourceAncestorElements()),
                request -> response(request, sourceAncestorHierarchy()),
                request -> response(request, sourceAncestorBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V27_STATION_NAME"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("source-ancestor-support-owner-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("OBSERVE", "HIERARCHY", "ELEMENT_BINDING");
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_REGION_NORMALIZED", 1
                ));
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V27_STATION_NAME")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V27_STATION_NAME")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V27_STATION_NAME")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointFifteenRepairsRedundantEntityRegionsOnlyAtHierarchy() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "minimal-entity-region-station",
                MINIMAL_ENTITY_OWNERSHIP_HYBRID_VISUAL_PROFILE
        );
        var redundantHierarchy = groundedStationHierarchy().replace(
                "\"entityId\":\"route\",\"schemaKey\":\"bus-route\",\"displayName\":\"线路\",\"regionIds\":[\"route-item\"]",
                "\"entityId\":\"route\",\"schemaKey\":\"bus-route\",\"displayName\":\"线路\",\"regionIds\":[\"routes\",\"route-item\"]"
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, redundantHierarchy),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V28_HIERARCHY"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("minimal-entity-region-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(2).taskJson())
                .contains("VISUAL_SEMANTIC_HIERARCHY_ENTITY_REGION_REDUNDANT")
                .contains("TARGETED_CROP")
                .contains("\"groundingPlan\":{");
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_HIERARCHY_ENTITY_REGION_REDUNDANT", 1
                ));
    }

    @Test
    void pipelineFourPointFifteenRewindsAmbiguousBindingOwnershipToHierarchy() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "unique-minimal-binding-owner-station",
                MINIMAL_ENTITY_OWNERSHIP_HYBRID_VISUAL_PROFILE
        );
        var ambiguousHierarchy = groundedStationHierarchy().replace(
                "\"entityId\":\"notice-entity\",\"schemaKey\":\"warm-notice\",\"displayName\":\"温馨提示\",\"regionIds\":[\"notice\"]",
                "\"entityId\":\"notice-entity\",\"schemaKey\":\"warm-notice\",\"displayName\":\"温馨提示\",\"regionIds\":[\"notice\",\"stop-item\"]"
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, ambiguousHierarchy),
                request -> response(request, groundedStationBindings()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V28_BINDING"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("unique-minimal-binding-owner-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(3).taskJson())
                .contains("VISUAL_SEMANTIC_HIERARCHY_BINDING_OWNER_AMBIGUOUS")
                .contains("TARGETED_CROP")
                .contains("\"elementInventory\":{")
                .contains("\"groundingPlan\":{")
                .contains("\"hierarchyPlan\":null");
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_HIERARCHY_BINDING_OWNER_AMBIGUOUS", 1
                ));
    }

    @Test
    void pipelineFourPointSixteenRepairsManyGroupRegionCardinalityAtObserve() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "group-region-cardinality-station",
                GROUP_REGION_CARDINALITY_HYBRID_VISUAL_PROFILE
        );
        var provider = new ScriptedProvider(
                request -> response(request, manyGroupWithSingularRegion()),
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V29_GROUP_CARDINALITY"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("group-region-cardinality-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY, InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(1).taskJson())
                .contains("VISUAL_SEMANTIC_REPEATED_GROUP_CARDINALITY_INVALID")
                .contains("\"elementInventory\":null")
                .contains("\"groundingPlan\":null");
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_REPEATED_GROUP_CARDINALITY_INVALID", 1
                ));
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V29_GROUP_CARDINALITY");
    }

    @Test
    void pipelineFourPointSeventeenNormalizesOneUniqueEvidenceOwnerBeforeHierarchy() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "evidence-owner-normalized-station",
                EVIDENCE_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElementsWithMisownedStationName()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V30_EVIDENCE_OWNER"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("evidence-owner-normalized-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(1).taskJson())
                .contains("\"elementId\":\"station-name\",\"regionIds\":[\"header\"]")
                .doesNotContain("\"elementId\":\"station-name\",\"regionIds\":[\"notice\"]");
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED", 1
                ));
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V30_EVIDENCE_OWNER")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V30_EVIDENCE_OWNER")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V30_EVIDENCE_OWNER")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointEighteenNormalizesRepeatedItemSlotOwnersBeforeHierarchy() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "repeated-item-slot-owner-normalized-station",
                REPEATED_ITEM_SLOT_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var provider = new ScriptedProvider(
                request -> response(request, repeatedItemSlotOwnerElements()),
                request -> response(request, repeatedItemSlotOwnerHierarchy()),
                request -> response(request, repeatedItemSlotOwnerBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V31_REPEATED_ITEM_SLOT_OWNER"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("repeated-item-slot-owner-normalized-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(1).taskJson())
                .contains("\"elementId\":\"item-label\",\"regionIds\":[\"item-a\",\"item-b\"]")
                .doesNotContain("\"elementId\":\"item-label\",\"regionIds\":[\"repeat\"]");
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED", 1
                ));
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V31_REPEATED_ITEM_SLOT_OWNER")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V31_REPEATED_ITEM_SLOT_OWNER")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V31_REPEATED_ITEM_SLOT_OWNER")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointNineteenNormalizesOneEmptyRelationshipSupportOwner() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "empty-support-owner-normalized-station",
                EMPTY_SUPPORT_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var emptySupport = groundedStationHierarchy().replace(
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\"]",
                "\"regionId\":\"routes\",\"supportingElementIds\":[]"
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, emptySupport),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V32_EMPTY_SUPPORT_OWNER"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("empty-support-owner-normalized-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED", 1
                ));
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .doesNotContainKey("VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED");
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .doesNotContainKey("VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V32_EMPTY_SUPPORT_OWNER")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V32_EMPTY_SUPPORT_OWNER")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V32_EMPTY_SUPPORT_OWNER")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwentyNormalizesOneUnknownRelationshipSupportOwner() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "unknown-support-owner-normalized-station",
                UNKNOWN_SUPPORT_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var unknownSupport = groundedStationHierarchy().replace(
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"route-group\"]",
                "\"regionId\":\"routes\",\"supportingElementIds\":[\"unknown-group\"]"
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElements()),
                request -> response(request, unknownSupport),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V33_UNKNOWN_SUPPORT_OWNER"
        );

        var finished = worker(provider, blobs, T0.plusSeconds(1), preprocessor)
                .processNext("unknown-support-owner-normalized-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 3,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_UNKNOWN_SUPPORT_OWNER_NORMALIZED", 1
                ));
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .doesNotContainKey("VISUAL_HIERARCHY_RELATIONSHIP_UNKNOWN_SUPPORT_OWNER_NORMALIZED");
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .doesNotContainKey("VISUAL_HIERARCHY_RELATIONSHIP_UNKNOWN_SUPPORT_OWNER_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V33_UNKNOWN_SUPPORT_OWNER")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V33_UNKNOWN_SUPPORT_OWNER")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V33_UNKNOWN_SUPPORT_OWNER")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwentyOneResumesAfterNormalizingOneUniqueRegionParent() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "unique-region-parent-normalized-station",
                UNIQUE_REGION_PARENT_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedStationElementsWithMisparentedNotice()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V34_REGION_PARENT"
        );
        var firstWorker = worker(provider, blobs, T0.plusSeconds(1), preprocessor);
        var claimed = runs.claimNextLive(
                "unique-region-parent-first-worker", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        var afterObserve = firstWorker.advance(claimed);

        assertThat(afterObserve.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE);
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
            assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(Map.of(
                    "VISUAL_GROUNDING_REGION_PARENT_NORMALIZED", 1
            ));
        });
        assertThat(afterObserve.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("OCR_SENTINEL_V34_REGION_PARENT")
                .doesNotContain("ocr-00-000");

        var finished = worker(
                provider, blobs, T0.plus(Duration.ofMinutes(7)), preprocessor
        ).processNext("unique-region-parent-recovery-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls)
                .as("ephemeral OCR is recomputed after lease expiry without replaying OBSERVE")
                .hasValue(2);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_REGION_PARENT_NORMALIZED");
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_REGION_PARENT_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V34_REGION_PARENT")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V34_REGION_PARENT")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V34_REGION_PARENT")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwentyTwoResumesAfterNormalizingOneEmptySourceAncestorSupportOwner() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "empty-source-ancestor-support-owner-station",
                EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var emptySourceAncestorSupport = sourceAncestorHierarchy().replace(
                "\"regionId\":\"route-item\",\"supportingElementIds\":[\"route-number\"]",
                "\"regionId\":\"route-item\",\"supportingElementIds\":[]"
        ).replace(
                "\"regionIds\":[\"root\",\"route-item\"]",
                "\"regionIds\":[\"route-item\"]"
        );
        var provider = new ScriptedProvider(
                request -> response(request, sourceAncestorElements().replace(
                        "\"regionIds\":[\"root\",\"route-item\"]",
                        "\"regionIds\":[\"route-item\"]"
                )),
                request -> response(request, emptySourceAncestorSupport),
                request -> response(request, sourceAncestorBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V35_EMPTY_SOURCE_ANCESTOR"
        );
        var firstWorker = worker(provider, blobs, T0.plusSeconds(1), preprocessor);
        var claimed = runs.claimNextLive(
                "empty-source-ancestor-first-worker", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        var afterObserve = firstWorker.advance(claimed);

        assertThat(afterObserve.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE);
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
            assertThat(attempt.problemCodeCounts())
                    .doesNotContainKey(
                            "VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED"
                    );
        });
        assertThat(afterObserve.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("OCR_SENTINEL_V35_EMPTY_SOURCE_ANCESTOR")
                .doesNotContain("ocr-00-000");

        var finished = worker(
                provider, blobs, T0.plus(Duration.ofMinutes(7)), preprocessor
        ).processNext("empty-source-ancestor-recovery-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls)
                .as("ephemeral OCR is recomputed after lease expiry without replaying OBSERVE")
                .hasValue(2);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED", 1,
                        "VISUAL_HIERARCHY_RELATIONSHIP_REGION_NORMALIZED", 1
                ));
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .doesNotContainKey(
                        "VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED"
                );
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V35_EMPTY_SOURCE_ANCESTOR")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V35_EMPTY_SOURCE_ANCESTOR")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V35_EMPTY_SOURCE_ANCESTOR")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwentyThreeResumesAfterNormalizingContractUniqueRegionKinds() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "contract-unique-region-kinds-station",
                STRUCTURAL_REGION_KIND_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var structurallyClassifiableKinds = groundedStationElements()
                .replaceFirst("\"kind\":\"ROOT\"", "\"kind\":\"CANVAS\"")
                .replaceFirst("\"kind\":\"REPEATED_GROUP\"", "\"kind\":\"GROUP\"")
                .replaceFirst("\"kind\":\"ITEM\"", "\"kind\":\"ROW\"");
        var provider = new ScriptedProvider(
                request -> response(request, structurallyClassifiableKinds),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V36_STRUCTURAL_REGION_KIND"
        );
        var firstWorker = worker(provider, blobs, T0.plusSeconds(1), preprocessor);
        var claimed = runs.claimNextLive(
                "structural-region-kind-first-worker", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        var afterObserve = firstWorker.advance(claimed);

        assertThat(afterObserve.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE);
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
            assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(Map.of(
                    "VISUAL_GROUNDING_REGION_KIND_NORMALIZED", 3
            ));
        });
        assertThat(afterObserve.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("OCR_SENTINEL_V36_STRUCTURAL_REGION_KIND")
                .doesNotContain("ocr-00-000");

        var finished = worker(
                provider, blobs, T0.plus(Duration.ofMinutes(7)), preprocessor
        ).processNext("structural-region-kind-recovery-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls)
                .as("ephemeral OCR is recomputed after lease expiry without replaying OBSERVE")
                .hasValue(2);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_REGION_KIND_NORMALIZED");
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_REGION_KIND_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V36_STRUCTURAL_REGION_KIND")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V36_STRUCTURAL_REGION_KIND")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V36_STRUCTURAL_REGION_KIND")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwentyFourResumesAfterNormalizingConstraintUniqueGroupKind() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "constraint-unique-group-kind-station",
                CONSTRAINT_REGION_KIND_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var constraintClassifiableKind = groundedStationElements().replace(
                "\"regionId\":\"notice\",\"parentRegionId\":\"root\",\"kind\":\"GROUP\"",
                "\"regionId\":\"notice\",\"parentRegionId\":\"root\",\"kind\":\"PANEL\""
        );
        var provider = new ScriptedProvider(
                request -> response(request, constraintClassifiableKind),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V37_CONSTRAINT_REGION_KIND"
        );
        var firstWorker = worker(provider, blobs, T0.plusSeconds(1), preprocessor);
        var claimed = runs.claimNextLive(
                "constraint-region-kind-first-worker", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        var afterObserve = firstWorker.advance(claimed);

        assertThat(afterObserve.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE);
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
            assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(Map.of(
                    "VISUAL_GROUNDING_REGION_KIND_NORMALIZED", 1
            ));
        });
        assertThat(afterObserve.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("OCR_SENTINEL_V37_CONSTRAINT_REGION_KIND")
                .doesNotContain("ocr-00-000");

        var finished = worker(
                provider, blobs, T0.plus(Duration.ofMinutes(7)), preprocessor
        ).processNext("constraint-region-kind-recovery-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls)
                .as("ephemeral OCR is recomputed after lease expiry without replaying OBSERVE")
                .hasValue(2);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_REGION_KIND_NORMALIZED");
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_REGION_KIND_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V37_CONSTRAINT_REGION_KIND")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V37_CONSTRAINT_REGION_KIND")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V37_CONSTRAINT_REGION_KIND")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwentyFiveResumesAfterPromotingKnownContainmentFailureToRootAncestor() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "ancestor-region-parent-station",
                ANCESTOR_REGION_PARENT_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var knownOutsideParent = groundedStationElements().replace(
                "\"regionId\":\"notice\",\"parentRegionId\":\"root\"",
                "\"regionId\":\"notice\",\"parentRegionId\":\"routes\""
        );
        var provider = new ScriptedProvider(
                request -> response(request, knownOutsideParent),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V38_ANCESTOR_REGION_PARENT"
        );
        var firstWorker = worker(provider, blobs, T0.plusSeconds(1), preprocessor);
        var claimed = runs.claimNextLive(
                "ancestor-region-parent-first-worker", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        var afterObserve = firstWorker.advance(claimed);

        assertThat(afterObserve.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE);
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
            assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(Map.of(
                    "VISUAL_GROUNDING_REGION_PARENT_NORMALIZED", 1
            ));
        });
        assertThat(afterObserve.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("OCR_SENTINEL_V38_ANCESTOR_REGION_PARENT")
                .doesNotContain("ocr-00-000");

        var finished = worker(
                provider, blobs, T0.plus(Duration.ofMinutes(7)), preprocessor
        ).processNext("ancestor-region-parent-recovery-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls)
                .as("ephemeral OCR is recomputed after lease expiry without replaying OBSERVE")
                .hasValue(2);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_REGION_PARENT_NORMALIZED");
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_REGION_PARENT_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V38_ANCESTOR_REGION_PARENT")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V38_ANCESTOR_REGION_PARENT")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V38_ANCESTOR_REGION_PARENT")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwentySixResumesAfterCompactingCanonicalSiblingReadingOrders() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "gapped-reading-order-station",
                GAPPED_READING_ORDER_NORMALIZED_HYBRID_VISUAL_PROFILE
        );
        var canonicalGappedReadingOrders = groundedStationElements()
                .replace(
                        "\"regionId\":\"notice\",\"parentRegionId\":\"root\",\"kind\":\"GROUP\",\"multiplicity\":\"ONE\",\"readingOrder\":1",
                        "\"regionId\":\"notice\",\"parentRegionId\":\"root\",\"kind\":\"GROUP\",\"multiplicity\":\"ONE\",\"readingOrder\":7"
                )
                .replace(
                        "\"regionId\":\"routes\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":2",
                        "\"regionId\":\"routes\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":9"
                );
        var provider = new ScriptedProvider(
                request -> response(request, canonicalGappedReadingOrders),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V39_GAPPED_READING_ORDER"
        );
        var firstWorker = worker(provider, blobs, T0.plusSeconds(1), preprocessor);
        var claimed = runs.claimNextLive(
                "gapped-reading-order-first-worker", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        var afterObserve = firstWorker.advance(claimed);

        assertThat(afterObserve.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE);
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
            assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(Map.of(
                    "VISUAL_GROUNDING_READING_ORDER_NORMALIZED", 2
            ));
        });
        assertThat(afterObserve.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("OCR_SENTINEL_V39_GAPPED_READING_ORDER")
                .doesNotContain("ocr-00-000");

        var finished = worker(
                provider, blobs, T0.plus(Duration.ofMinutes(7)), preprocessor
        ).processNext("gapped-reading-order-recovery-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls)
                .as("ephemeral OCR is recomputed after lease expiry without replaying OBSERVE")
                .hasValue(2);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_READING_ORDER_NORMALIZED");
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_READING_ORDER_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V39_GAPPED_READING_ORDER")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V39_GAPPED_READING_ORDER")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V39_GAPPED_READING_ORDER")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwentySevenResumesWithBoundedReadingOrderDiagnostics() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "reading-order-diagnostic-station",
                READING_ORDER_DIAGNOSTIC_HYBRID_VISUAL_PROFILE
        );
        var canonicalGappedReadingOrders = groundedStationElements()
                .replace(
                        "\"regionId\":\"notice\",\"parentRegionId\":\"root\",\"kind\":\"GROUP\",\"multiplicity\":\"ONE\",\"readingOrder\":1",
                        "\"regionId\":\"notice\",\"parentRegionId\":\"root\",\"kind\":\"GROUP\",\"multiplicity\":\"ONE\",\"readingOrder\":7"
                )
                .replace(
                        "\"regionId\":\"routes\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":2",
                        "\"regionId\":\"routes\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":9"
                );
        var provider = new ScriptedProvider(
                request -> response(request, canonicalGappedReadingOrders),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var preprocessor = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V40_READING_ORDER_DIAGNOSTIC"
        );
        var firstWorker = worker(provider, blobs, T0.plusSeconds(1), preprocessor);
        var claimed = runs.claimNextLive(
                "reading-order-diagnostic-first-worker", T0.plusSeconds(1),
                Duration.ofMinutes(5)
        ).orElseThrow();

        var afterObserve = firstWorker.advance(claimed);

        assertThat(afterObserve.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE);
        assertThat(workflowStore.attempts(created)).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(InferenceAttemptStatus.SUCCEEDED);
            assertThat(attempt.problemCodeCounts()).containsExactlyEntriesOf(Map.of(
                    "VISUAL_GROUNDING_READING_ORDER_NORMALIZED", 2
            ));
        });
        assertThat(afterObserve.checkpointJson())
                .contains("renderweave-visual-grounding/2.0")
                .doesNotContain("OCR_SENTINEL_V40_READING_ORDER_DIAGNOSTIC")
                .doesNotContain("ocr-00-000");

        var finished = worker(
                provider, blobs, T0.plus(Duration.ofMinutes(7)), preprocessor
        ).processNext("reading-order-diagnostic-recovery-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls)
                .as("ephemeral OCR is recomputed after lease expiry without replaying OBSERVE")
                .hasValue(2);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_READING_ORDER_NORMALIZED");
        assertThat(workflowStore.attempts(created).get(2).problemCodeCounts())
                .doesNotContainKey("VISUAL_GROUNDING_READING_ORDER_NORMALIZED");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V40_READING_ORDER_DIAGNOSTIC")
                .doesNotContain("ocr-00-000");
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V40_READING_ORDER_DIAGNOSTIC")
                .doesNotContain("ocr-00-000");
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V40_READING_ORDER_DIAGNOSTIC")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void pipelineFourPointTwentyEightReobservesUnknownSupportWhenObservationHasNoGroupElements() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "early-relationship-group-prerequisite-station",
                EARLY_RELATIONSHIP_GROUP_PREREQUISITE_HYBRID_VISUAL_PROFILE
        );
        var groupRegionWithoutGroupElements = flatGroundedStationElements().replace(
                "\"kind\":\"SECTION\"", "\"kind\":\"GROUP\""
        );
        var provider = new ScriptedProvider(
                request -> response(request, groupRegionWithoutGroupElements),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(preprocessCalls, "OCR_SENTINEL_V41_EARLY_GROUP_PREREQUISITE")
        ).processNext("early-relationship-group-prerequisite-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.HIERARCHY,
                        InferenceStage.OBSERVE, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.REJECTED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING", 1
                ));
        assertThat(provider.requests.get(2).taskJson())
                .contains("VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING")
                .doesNotContain("TARGETED_CROP")
                .contains("\"elementInventory\":null")
                .contains("\"groundingPlan\":null");
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V41_EARLY_GROUP_PREREQUISITE")
                .doesNotContain("ocr-00-000");
    }

    @Test
    void productV42UsesAllSevenCallsAcrossObservationRetriesAndHierarchyRewind() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v42-seven-call-hierarchy-rewind",
                PLUS_V42_HYBRID_VISUAL_PROFILE, 5_000_000L
        );
        var invalidObservation = groundedStationElements().replace(
                "renderweave-visual-grounding/2.0", "renderweave-visual-grounding/9.0"
        );
        var groupRegionWithoutGroupElements = flatGroundedStationElements().replace(
                "\"kind\":\"SECTION\"", "\"kind\":\"GROUP\""
        );
        var provider = new ScriptedProvider(
                request -> response(request, invalidObservation),
                request -> response(request, groupRegionWithoutGroupElements),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, invalidObservation),
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();

        var finished = worker(
                provider, blobs, T0.plusSeconds(1),
                hybridPreprocessor(preprocessCalls, "OCR_SENTINEL_V42_SEVEN_CALL_REWIND")
        ).processNext("v42-seven-call-hierarchy-rewind-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY, InferenceStage.OBSERVE,
                        InferenceStage.OBSERVE, InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.REJECTED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.findCandidate(created)).isPresent();
    }

    @Test
    void productV44RetriesObservationWhenLocalVisionProvesAnOmittedDenseSequence() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v44-document-sequence-coverage",
                PLUS_V44_HYBRID_VISUAL_PROFILE, 5_000_000L
        );
        var provider = new ScriptedProvider(
                request -> response(request, flatGroundedStationElements()),
                request -> response(request, groundedStationElements()),
                request -> response(request, groundedStationHierarchy()),
                request -> response(request, groundedStationBindings())
        );
        var preprocessCalls = new AtomicInteger();

        var finished = worker(
                provider, blobs, T0.plusSeconds(1), denseSequencePreprocessor(preprocessCalls)
        ).processNext("v44-document-sequence-coverage-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE, InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY, InferenceStage.ELEMENT_BINDING
                );
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.REJECTED, InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED
                );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "VISUAL_SEMANTIC_OBSERVE_DOCUMENT_SEQUENCE_GROUP_MISSING", 1
                ));
        assertThat(provider.requests.get(1).taskJson())
                .contains("VISUAL_SEMANTIC_OBSERVE_DOCUMENT_SEQUENCE_GROUP_MISSING")
                .contains("\"elementInventory\":null")
                .contains("\"groundingPlan\":null");
        assertThat(preprocessCalls).hasValue(1);
        assertThat(workflowStore.findCandidate(created)).isPresent();
    }

    @Test
    void productV45CoalescesRepeatedInstanceObservationsIntoOneNestedSchemaField() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v45-repeated-observation-coalescing",
                PLUS_V45_HYBRID_VISUAL_PROFILE, 5_000_000L
        );
        var provider = new ScriptedProvider(
                request -> response(request, repeatedObservationElements()),
                request -> response(request, repeatedObservationHierarchy()),
                request -> response(request, repeatedObservationBindings())
        );
        var preprocessCalls = new AtomicInteger();
        var acquisition = hybridPreprocessor(
                preprocessCalls, "OCR_SENTINEL_V45_REPEATED_OBSERVATION"
        );
        var successorWorker = worker(provider, blobs, T0.plusSeconds(1), acquisition);

        assertThat(successorWorker.documentObservationSuccessorIdentity())
                .hasValueSatisfying(identity -> assertThat(identity)
                        .startsWith("renderweave-document-observation-successor/1.0:"));
        var finished = successorWorker.processNext(
                "v45-repeated-observation-worker"
        ).orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(preprocessCalls).hasValue(1);
        assertThat(provider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(
                        InferenceStage.OBSERVE,
                        InferenceStage.HIERARCHY,
                        InferenceStage.ELEMENT_BINDING
                );
        assertThat(provider.requests.get(2).systemPrompt())
                .contains("coalesce those identical observations into one field");
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED,
                        InferenceAttemptStatus.SUCCEEDED
                );

        var candidate = candidateCodec.parse(
                workflowStore.findCandidate(created).orElseThrow().currentJson()
        );
        assertThat(candidate.schemas()).hasSize(2);
        var root = candidate.schemas().stream()
                .filter(schema -> schema.candidateSchemaId().equals(candidate.rootCandidateSchemaId()))
                .findFirst().orElseThrow();
        var child = candidate.schemas().stream()
                .filter(schema -> !schema.candidateSchemaId().equals(candidate.rootCandidateSchemaId()))
                .findFirst().orElseThrow();
        assertThat(root.fields()).anySatisfy(field -> {
            assertThat(field.proposedFieldKey()).isEqualTo("items");
            assertThat(field.value().kind()).isEqualTo(CandidateValueKind.ARRAY);
            assertThat(field.value().items().kind()).isEqualTo(CandidateValueKind.REFERENCE);
            assertThat(field.value().items().reference().candidateSchemaId())
                    .isEqualTo(child.candidateSchemaId());
        });
        assertThat(child.fields()).singleElement().satisfies(field -> {
            assertThat(field.proposedFieldKey()).isEqualTo("label");
            assertThat(field.assessment().evidence()).hasSize(2);
            assertThat(field.assessment().evidence())
                    .extracting(evidence -> evidence.boundingBox().top())
                    .containsExactly(2_300, 6_300);
        });
        assertThat(candidateSemanticSummary(candidate)).isEqualTo(
                "root=document;"
                        + "schema=document(root)[title:TEXT:e1,items:ARRAY<REFERENCE:item>:e1];"
                        + "schema=item[ label:TEXT:e2@2300,6300 ]"
        );
        var validationProblems = new CandidateProblemJsonCodec().parse(
                workflowStore.findCandidate(created).orElseThrow().validationProblemsJson()
        );
        assertThat(validationProblems).hasSize(5).allSatisfy(problem -> {
            assertThat(problem.code()).isEqualTo("LOW_CONFIDENCE_UNRESOLVED");
            assertThat(problem.severity()).isEqualTo(CandidateProblemSeverity.BLOCKER);
        });
        assertThat(validationProblems).noneSatisfy(problem ->
                assertThat(problem.severity()).isEqualTo(CandidateProblemSeverity.WARNING));
        var budget = budgets.snapshot(LiveInferenceWorker.PRODUCT_BUDGET_KEY);
        assertThat(budget.consumedAttempts()).isEqualTo(3);
        assertThat(budget.consumedCostMicrosCny()).isEqualTo(18_000L);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from inference_provider_reservation
                        where run_id = :runId and state = 'SETTLED'
                        """)
                .param("runId", created).query(Integer.class).single()).isEqualTo(3);
        assertNoEphemeralObservationPayload(
                created, "OCR_SENTINEL_V45_REPEATED_OBSERVATION",
                "ocr-00-000", acquisition.policy().identity()
        );
        assertThat(workflowStore.findCandidate(created).orElseThrow().currentJson())
                .doesNotContain("OCR_SENTINEL_V45_REPEATED_OBSERVATION")
                .doesNotContain("ocr-00-000")
                .doesNotContain(DocumentObservationIR.VERSION)
                .doesNotContain(acquisition.policy().identity());
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V45_REPEATED_OBSERVATION")
                .doesNotContain("ocr-00-000")
                .doesNotContain(DocumentObservationIR.VERSION)
                .doesNotContain(acquisition.policy().identity());
    }

    @Test
    void productV45SuccessorRecomputesIrAfterLeaseExpiryWithoutReplayingObserve() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(
                blobs, "v45-successor-lease-recovery",
                PLUS_V45_HYBRID_VISUAL_PROFILE, 5_000_000L
        );
        var firstProvider = new ScriptedProvider(
                request -> response(request, repeatedObservationElements())
        );
        var acquisitionCalls = new AtomicInteger();
        var acquisition = hybridPreprocessor(
                acquisitionCalls, "OCR_SENTINEL_V45_LEASE_RECOVERY"
        );
        var firstWorker = worker(
                firstProvider, blobs, T0.plusSeconds(1), acquisition
        );
        var claimed = runs.claimNextLive(
                "v45-successor-first-worker", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();

        var afterObserve = firstWorker.advance(claimed);

        assertThat(afterObserve.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(acquisitionCalls).hasValue(1);
        assertThat(firstProvider.requests).extracting(ProviderInferenceRequest::stage)
                .containsExactly(InferenceStage.OBSERVE);
        assertThat(workflowStore.attempts(created)).extracting(InferenceAttempt::status)
                .containsExactly(InferenceAttemptStatus.SUCCEEDED);
        assertThat(afterObserve.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V45_LEASE_RECOVERY")
                .doesNotContain("ocr-00-000")
                .doesNotContain(DocumentObservationIR.VERSION)
                .doesNotContain(acquisition.policy().identity());

        var recoveryProvider = new ScriptedProvider(
                request -> response(request, repeatedObservationHierarchy()),
                request -> response(request, repeatedObservationBindings())
        );
        var finished = worker(
                recoveryProvider, blobs, T0.plus(Duration.ofMinutes(7)), acquisition
        ).processNext("v45-successor-recovery-worker").orElseThrow();

        assertThat(finished.state())
                .as("failure=%s attempts=%s", finished.failureCode(), workflowStore.attempts(created))
                .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(acquisitionCalls)
                .as("ephemeral IR is recomputed after lease expiry")
                .hasValue(2);
        assertThat(recoveryProvider.requests).extracting(ProviderInferenceRequest::stage)
                .as("the accepted OBSERVE stage must not replay")
                .containsExactly(InferenceStage.HIERARCHY, InferenceStage.ELEMENT_BINDING);
        assertThat(workflowStore.attempts(created)).extracting(
                        InferenceAttempt::stage, InferenceAttempt::status
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                InferenceStage.OBSERVE, InferenceAttemptStatus.SUCCEEDED
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                InferenceStage.HIERARCHY, InferenceAttemptStatus.SUCCEEDED
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                InferenceStage.ELEMENT_BINDING, InferenceAttemptStatus.SUCCEEDED
                        )
                );
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson())
                .doesNotContain("OCR_SENTINEL_V45_LEASE_RECOVERY")
                .doesNotContain("ocr-00-000")
                .doesNotContain(DocumentObservationIR.VERSION)
                .doesNotContain(acquisition.policy().identity());
        assertThat(review.validationProblemsJson())
                .doesNotContain("OCR_SENTINEL_V45_LEASE_RECOVERY")
                .doesNotContain("ocr-00-000")
                .doesNotContain(DocumentObservationIR.VERSION)
                .doesNotContain(acquisition.policy().identity());
        assertThat(finished.checkpointJson())
                .doesNotContain("OCR_SENTINEL_V45_LEASE_RECOVERY")
                .doesNotContain("ocr-00-000")
                .doesNotContain(DocumentObservationIR.VERSION)
                .doesNotContain(acquisition.policy().identity());
        assertNoEphemeralObservationPayload(
                created, "OCR_SENTINEL_V45_LEASE_RECOVERY",
                "ocr-00-000", acquisition.policy().identity()
        );
    }

    @Test
    void cancellationIsAcknowledgedBeforeHybridPreprocessing() {
        var blobs = new MemoryBlobStore();
        var created = createGroundedVisual(blobs, "hybrid-cancel-before-ocr", HYBRID_VISUAL_PROFILE);
        var claimed = runs.claimNextLive(
                "hybrid-cancel-worker", T0.plusSeconds(1), Duration.ofMinutes(5)
        ).orElseThrow();
        var cancelled = runs.requestCancellation(created, T0.plusSeconds(2));
        var preprocessCalls = new AtomicInteger();

        var finished = worker(
                new ScriptedProvider(), blobs, T0.plusSeconds(3),
                hybridPreprocessor(preprocessCalls, "MUST_NOT_BE_OBSERVED")
        ).drain(cancelled);

        assertThat(claimed.runId()).isEqualTo(created);
        assertThat(finished.state()).isEqualTo(InferenceRunState.CANCELLED);
        assertThat(preprocessCalls).hasValue(0);
        assertThat(blobs.readCalls).hasValue(0);
        assertThat(workflowStore.attempts(created)).isEmpty();
        assertThat(budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY).consumedAttempts()).isZero();
    }

    @Test
    void serialProviderCallRenewsItsLeaseBeforeEnteringTheNetworkWait() {
        var blobs = new MemoryBlobStore();
        create(blobs, "serial-lease-renewal", 1_050, 1_660, SERIAL_PRODUCT_PROFILE);
        var provider = new ScriptedProvider(request -> response(request, stationElements(request)));
        var claimed = runs.claimNextLive(
                "serial-short-lease-worker", T0.plusSeconds(1), Duration.ofSeconds(30)
        ).orElseThrow();

        var afterObserve = worker(provider, blobs, T0.plusSeconds(20)).advance(claimed);

        assertThat(afterObserve.stage()).isEqualTo(InferenceStage.HIERARCHY);
        assertThat(afterObserve.lease().orElseThrow().expiresAt())
                .isEqualTo(T0.plusSeconds(20).plus(Duration.ofMinutes(5)));
    }

    @Test
    void groundedJsonOnlyCompletesForReviewWithoutProviderAttemptOrReservation() {
        var blobs = new MemoryBlobStore();
        var created = createJsonOnlyGrounded(blobs, "grounded-json", "{\"title\":\"hello\"}");
        var provider = new ScriptedProvider();

        var finished = worker(provider, blobs).processNext("grounded-json-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).isEmpty();
        assertThat(workflowStore.attempts(created)).isEmpty();
        assertThat(budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY).consumedAttempts()).isZero();
        var candidate = candidateCodec.parse(
                workflowStore.findCandidate(created).orElseThrow().currentJson()
        );
        assertThat(candidate.schemas()).singleElement().satisfies(schema -> {
            assertThat(schema.proposedSchemaKey()).startsWith("inferred-");
            assertThat(schema.assessment().inferred()).isTrue();
            assertThat(schema.fields()).singleElement().satisfies(field -> {
                assertThat(field.proposedFieldKey()).isEqualTo("title");
                assertThat(field.value().kind()).isEqualTo(CandidateValueKind.TEXT);
                assertThat(field.assessment().evidence()).allMatch(evidence ->
                        evidence.jsonPointer() != null && evidence.artifactId() == null);
            });
        });
    }

    @Test
    void everyGroundedJsonCorpusCaseReachesReviewWithZeroProviderSideEffects() {
        var blobs = new MemoryBlobStore();
        var provider = new ScriptedProvider();
        var worker = worker(provider, blobs);
        var evaluated = 0;

        for (var fixture : new ReplayCorpus().cases()) {
            if (fixture.mode() != InferenceMode.JSON_ONLY) continue;
            evaluated++;
            var created = createJsonOnlyGrounded(
                    blobs, "grounded-" + fixture.fixtureId(), fixture.jsonSamples()
            );

            var finished = worker.processNext("grounded-corpus-worker").orElseThrow();

            assertThat(finished.runId()).isEqualTo(created);
            assertThat(finished.state())
                    .as(fixture.fixtureId())
                    .isEqualTo(InferenceRunState.REVIEW_REQUIRED);
            assertThat(workflowStore.attempts(created)).as(fixture.fixtureId()).isEmpty();
        }

        assertThat(evaluated).isEqualTo(20);
        assertThat(provider.requests).isEmpty();
        var budget = budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY);
        assertThat(budget.consumedAttempts()).isZero();
        assertThat(budget.consumedCostMicrosCny()).isZero();
    }

    @Test
    void recoveredGroundedJsonRepairStageFailsBeforeAnyProviderSideEffect() {
        var blobs = new MemoryBlobStore();
        var provider = new ScriptedProvider();
        var created = createJsonOnlyGrounded(
                blobs, "grounded-json-repair-recovery", "{\"title\":\"hello\"}"
        );
        var repairCheckpoint = """
                {
                  "checkpointVersion": "renderweave-live-checkpoint/1.0",
                  "completedStage": "CRITIQUE",
                  "structureCalls": 1,
                  "repairRounds": 0,
                  "outputValid": false,
                  "candidate": null,
                  "validationProblems": [
                    {
                      "code": "LIVE_STRUCTURE_OUTPUT_INVALID",
                      "severity": "BLOCKER",
                      "itemId": null,
                      "pointer": "/candidate",
                      "args": {}
                    }
                  ]
                }
                """;
        jdbcClient.sql("""
                        update inference_run
                        set stage = 'REPAIR', checkpoint_json = cast(:checkpoint as jsonb)
                        where run_id = :runId
                        """)
                .param("checkpoint", repairCheckpoint)
                .param("runId", created)
                .update();

        var finished = worker(provider, blobs)
                .processNext("grounded-json-repair-recovery-worker")
                .orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode())
                .contains("LIVE_GROUNDED_JSON_EXTERNAL_CALL_BLOCKED");
        assertThat(provider.requests).isEmpty();
        assertThat(workflowStore.attempts(created)).isEmpty();
        var budget = budgets.snapshot(LiveInferenceWorker.CANARY_BUDGET_KEY);
        assertThat(budget.consumedAttempts()).isZero();
        assertThat(budget.consumedCostMicrosCny()).isZero();
    }

    @Test
    void groundedCombinedPersistsJsonTruthAndOnlySafeVisualOverlay() {
        var blobs = new MemoryBlobStore();
        var created = createCombined(
                blobs, "grounded-combined", "{\"title\":\"hello\"}", GROUNDED_PROFILE
        );
        var provider = new ScriptedProvider(
                request -> response(request, groundedCombinedProposal(request))
        );

        var finished = worker(provider, blobs).processNext("grounded-combined-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).singleElement().satisfies(request -> {
            assertThat(request.taskJson()).contains("renderweave-live-task/2.0");
            assertThat(request.taskJson()).contains("groundedCandidate");
            assertThat(request.taskJson()).contains("\"proposedFieldKey\":\"title\"");
        });
        assertThat(workflowStore.attempts(created)).hasSize(1);
        var candidate = candidateCodec.parse(
                workflowStore.findCandidate(created).orElseThrow().currentJson()
        );
        var root = candidate.schemas().stream()
                .filter(schema -> schema.candidateSchemaId().equals(candidate.rootCandidateSchemaId()))
                .findFirst().orElseThrow();
        assertThat(root.fields()).extracting(CandidateField::proposedFieldKey)
                .containsExactly("subtitle", "title");
        assertThat(root.fields().stream().filter(field -> field.proposedFieldKey().equals("title"))
                .findFirst().orElseThrow().value().kind()).isEqualTo(CandidateValueKind.TEXT);
        var subtitle = root.fields().stream().filter(field -> field.proposedFieldKey().equals("subtitle"))
                .findFirst().orElseThrow();
        assertThat(subtitle.required()).isFalse();
        assertThat(subtitle.value().constraints()).isEmpty();
        assertThat(subtitle.assessment().resolution()).isEqualTo(CandidateResolution.UNRESOLVED);
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
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_BUNDLE_CONTRACT_VERSION", 1
                ));
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts()).isEmpty();
        assertThat(workflowStore.findCandidate(created).orElseThrow().currentJson())
                .doesNotContain("\"contractVersion\":null");
    }

    @Test
    void invalidAssessmentEvidenceFeedsTheExactDecodeDiagnosticIntoRepair() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-assessment-evidence-repair");
        var provider = new ScriptedProvider(
                request -> response(request, candidate(request).replaceFirst(
                        "\"evidence\":\\[[^]]*]", "\"evidence\":null"
                )),
                request -> response(request, candidate(request))
        );

        var finished = worker(provider, blobs)
                .processNext("live-assessment-evidence-repair-worker")
                .orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(1).stage().name()).isEqualTo("REPAIR");
        assertThat(provider.requests.get(1).taskJson()).contains(
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_ASSESSMENT_EVIDENCE"
        );
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsExactlyEntriesOf(Map.of(
                        "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_ASSESSMENT_EVIDENCE", 1
                ));
        assertThat(workflowStore.findCandidate(created).orElseThrow().currentJson())
                .doesNotContain("\"evidence\":null");
    }

    @Test
    void structureAndRepairAttemptsPersistRefinedPayloadFreeDecodeSlots() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-refined-decode-taxonomy");
        var provider = new ScriptedProvider(
                request -> response(request, candidate(request).replace(
                        "\"source\":\"AI\"", "\"source\":\"SENSITIVE_ENUM_VALUE\""
                )),
                request -> response(request, candidate(request).replace(
                        "\"contractVersion\":\"renderweave-candidate/1.0\"",
                        "\"contractVersion\":null"
                )),
                request -> response(request, candidate(request).replaceFirst(
                        "\"rootCandidateSchemaId\":\"[^\"]+\"",
                        "\"rootCandidateSchemaId\":\"SENSITIVE_FORMAT_VALUE!\""
                ))
        );

        var finished = worker(provider, blobs)
                .processNext("live-refined-decode-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains("LIVE_REPAIR_BUDGET_EXHAUSTED");
        assertThat(provider.requests).extracting(request -> request.stage().name())
                .containsExactly("STRUCTURE", "REPAIR", "REPAIR");
        assertThat(workflowStore.attempts(created))
                .extracting(attempt -> attempt.problemCodeCounts())
                .containsExactly(
                        Map.of("CANDIDATE_DECODE_ENUM_INVALID_SOURCE", 1),
                        Map.of("CANDIDATE_DECODE_CONSTRUCTOR_INVALID_BUNDLE_CONTRACT_VERSION", 1),
                        Map.of("CANDIDATE_DECODE_FORMAT_INVALID_ROOT_SCHEMA_ID", 1)
                );
        assertThat(workflowStore.attempts(created).toString()).doesNotContain(
                "SENSITIVE_ENUM_VALUE", "SENSITIVE_FORMAT_VALUE"
        );
    }

    @Test
    void deterministicBlockerTriggersRepairWithStableProblemCodes() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-deterministic-repair");
        var provider = new ScriptedProvider(
                request -> response(request, candidate(request, true)),
                request -> response(request, candidate(request, false))
        );

        var finished = worker(provider, blobs).processNext("live-deterministic-repair-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(1).stage().name()).isEqualTo("REPAIR");
        assertThat(provider.requests.get(1).taskJson()).contains("AI_REQUIRED_UNCONFIRMED");
        assertThat(workflowStore.attempts(created))
                .extracting(attempt -> attempt.status())
                .containsExactly(InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED);
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsEntry("AI_REQUIRED_UNCONFIRMED", 1);
        assertThat(workflowStore.attempts(created).get(1).problemCodeCounts()).isEmpty();
    }

    @Test
    void missingJsonEvidenceTriggersOneRepairWithTheStableProblemCode() {
        var blobs = new MemoryBlobStore();
        var created = createCombined(blobs, "live-json-evidence-repair");
        var provider = new ScriptedProvider(
                request -> response(request, candidate(request)),
                request -> response(request, jsonCandidate(request))
        );

        var finished = worker(provider, blobs).processNext("live-json-evidence-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(1).stage().name()).isEqualTo("REPAIR");
        assertThat(provider.requests.get(1).taskJson()).contains("JSON_EVIDENCE_ITEM_MISSING");
        assertThat(workflowStore.attempts(created))
                .extracting(attempt -> attempt.status())
                .containsExactly(InferenceAttemptStatus.SUCCEEDED, InferenceAttemptStatus.SUCCEEDED);
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsEntry("JSON_EVIDENCE_ITEM_MISSING", 2);
    }

    @Test
    void humanOnlySemanticBlockerGoesDirectlyToReview() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-human-only-review");
        var provider = new ScriptedProvider(
                request -> response(request, candidateWithHumanBlocker(request))
        );

        var finished = worker(provider, blobs).processNext("live-human-only-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).hasSize(1);
        assertThat(workflowStore.findCandidate(created).orElseThrow().validationProblemsJson())
                .contains("CANDIDATE_TYPE_UNRESOLVED");
    }

    @Test
    void imageOnlyCanonicalFormattingNoiseIsNormalizedBeforeHumanReview() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-image-only-canonical-formatting");
        var provider = new ScriptedProvider(
                request -> response(request, candidateWithCanonicalFormattingNoise(request))
        );

        var finished = worker(provider, blobs).processNext("live-image-only-normalize-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).hasSize(1);
        var attempt = workflowStore.attempts(created).getFirst();
        assertThat(attempt.problemCodeCounts())
                .containsEntry("CANDIDATE_SCHEMA_KEY_NORMALIZED", 1)
                .containsEntry("CANDIDATE_SCALAR_OBSERVED_KINDS_NORMALIZED", 6)
                .containsEntry("CANDIDATE_ITEM_UNRESOLVED", 1)
                .doesNotContainKeys("CANDIDATE_SCHEMA_KEY_INVALID", "CANDIDATE_SCALAR_SHAPE_INVALID");
        var review = workflowStore.findCandidate(created).orElseThrow();
        var candidate = candidateCodec.parse(review.currentJson());
        assertThat(candidate.schemas().getFirst().proposedSchemaKey())
                .matches("inferred-[0-9a-f]{32}");
        assertThat(candidate.schemas().getFirst().fields())
                .allSatisfy(field -> assertThat(field.value().observedKinds()).isEmpty());
        assertThat(review.validationProblemsJson())
                .contains("CANDIDATE_SCHEMA_KEY_NORMALIZED")
                .contains("CANDIDATE_SCALAR_OBSERVED_KINDS_NORMALIZED")
                .contains("CANDIDATE_ITEM_UNRESOLVED");
    }

    @Test
    void imageOnlyPixelCoordinateFamilyIsNormalizedUsingArtifactDimensions() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-image-only-pixel-evidence", 1_510, 4_096);
        var provider = new ScriptedProvider(
                request -> response(request, candidateWithPixelCoordinateEvidence(request))
        );

        var finished = worker(provider, blobs).processNext("live-image-only-pixel-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).hasSize(1);
        assertThat(workflowStore.attempts(created).getFirst().problemCodeCounts())
                .containsEntry("IMAGE_EVIDENCE_PIXEL_COORDINATES_NORMALIZED", 8);
        var candidate = candidateCodec.parse(workflowStore.findCandidate(created).orElseThrow().currentJson());
        assertThat(candidate.schemas().getFirst().fields().get(5).assessment().evidence().getFirst().boundingBox())
                .isEqualTo(new CandidateBoundingBox(1_324, 2_197, 8_610, 9_278));
    }

    @Test
    void unrepresentableExactJsonFieldKeyIsPreservedForHumanReviewWithoutRepair() {
        var blobs = new MemoryBlobStore();
        var fieldKey = "x".repeat(129);
        var created = createCombined(
                blobs, "live-unrepresentable-field-key",
                "{\"" + fieldKey + "\":\"hello\"}"
        );
        var provider = new ScriptedProvider(
                request -> response(request, jsonCandidate(request, fieldKey))
        );

        var finished = worker(provider, blobs).processNext("live-field-key-review-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.requests).hasSize(1);
        var review = workflowStore.findCandidate(created).orElseThrow();
        assertThat(review.currentJson()).contains(fieldKey);
        assertThat(review.validationProblemsJson()).contains("CANDIDATE_FIELD_KEY_INVALID");
    }

    @Test
    void mixedDeterministicAndHumanBlockersFailWithoutAnotherCallOrReviewCandidate() {
        var blobs = new MemoryBlobStore();
        var created = create(blobs, "live-human-review-boundary");
        var provider = new ScriptedProvider(
                request -> response(request, candidateWithMixedBlockers(request))
        );

        var finished = worker(provider, blobs).processNext("live-human-review-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(finished.failureCode()).contains("LIVE_UNSAFE_BLOCKER_SET");
        assertThat(provider.requests).hasSize(1);
        assertThat(workflowStore.attempts(created))
                .extracting(attempt -> attempt.status())
                .containsExactly(InferenceAttemptStatus.SUCCEEDED);
        assertThat(workflowStore.findCandidate(created)).isEmpty();
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
        return worker(provider, blobs, T0.plusSeconds(1));
    }

    private LiveInferenceWorker worker(InferenceProvider provider, BlobStore blobs, Instant now) {
        return new LiveInferenceWorker(
                runs, workflowStore, budgets, provider, blobs,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5)
        );
    }

    private LiveInferenceWorker worker(
            InferenceProvider provider,
            BlobStore blobs,
            Instant now,
            AcquisitionFixture acquisition
    ) {
        return new LiveInferenceWorker(
                runs, workflowStore, budgets, provider, blobs,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5),
                acquisition.seam(), acquisition.policy()
        );
    }

    private UUID create(MemoryBlobStore blobs, String seed) {
        return create(blobs, seed, 32, 16);
    }

    private UUID create(MemoryBlobStore blobs, String seed, int width, int height) {
        return create(blobs, seed, width, height, PROFILE);
    }

    private UUID create(
            MemoryBlobStore blobs,
            String seed,
            int width,
            int height,
            String profileId
    ) {
        var profile = profiles.require(profileId);
        var bytes = ("synthetic-image:" + seed).getBytes(StandardCharsets.UTF_8);
        var artifactId = sha256(bytes);
        blobs.values.put(artifactId, bytes);
        var artifact = new NormalizedArtifact(
                artifactId, NormalizedArtifact.Kind.IMAGE, artifactId,
                "image/png", bytes.length, width, height
        );
        var normalized = new NormalizedInput(
                InferenceMode.IMAGE_ONLY, profileId, seed, sha256(seed.getBytes(StandardCharsets.UTF_8)),
                List.of(artifact),
                List.of(new NormalizedInputReference(NormalizedArtifact.Kind.IMAGE, 0, artifactId)),
                List.of()
        );
        return runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-" + seed, normalized, profile.snapshotJson(), T0
        )).run().runId();
    }

    private UUID createGroundedVisual(MemoryBlobStore blobs, String seed) {
        return createGroundedVisual(blobs, seed, GROUNDED_VISUAL_PROFILE);
    }

    private UUID createGroundedVisual(MemoryBlobStore blobs, String seed, String profileId) {
        return createGroundedVisual(blobs, seed, profileId, null);
    }

    private UUID createGroundedVisual(
            MemoryBlobStore blobs,
            String seed,
            String profileId,
            Long costLimitMicrosCny
    ) {
        var profile = profiles.require(profileId);
        return createGroundedVisual(
                blobs, seed, profileId, profile.snapshotJson(), costLimitMicrosCny
        );
    }

    private UUID createV49EnvelopeProbe(MemoryBlobStore blobs, String seed) {
        return createV49EnvelopeProbe(blobs, seed, 12);
    }

    private UUID createV49EnvelopeProbe(MemoryBlobStore blobs, String seed, int maximumCalls) {
        var v49 = profiles.require(MAX_V49_MIXED_RECOVERY_PROFILE);
        var snapshot = v49.snapshotJson();
        if (maximumCalls != 12) {
            snapshot = snapshot.replace(
                    "\"maximumTotalCalls\":12",
                    "\"maximumTotalCalls\":" + maximumCalls
            );
        }
        return createGroundedVisual(
                blobs, seed, MAX_V49_MIXED_RECOVERY_PROFILE, snapshot, null
        );
    }

    private UUID createGroundedVisual(
            MemoryBlobStore blobs,
            String seed,
            String profileId,
            String profileSnapshot,
            Long costLimitMicrosCny
    ) {
        var image = new BufferedImage(1_050, 1_660, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, image.getWidth(), 160);
        } finally {
            graphics.dispose();
        }
        final byte[] bytes;
        try {
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            bytes = output.toByteArray();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        var artifactId = sha256(bytes);
        blobs.values.put(artifactId, bytes);
        var artifact = new NormalizedArtifact(
                artifactId, NormalizedArtifact.Kind.IMAGE, artifactId,
                "image/png", bytes.length, image.getWidth(), image.getHeight()
        );
        var normalized = new NormalizedInput(
                InferenceMode.IMAGE_ONLY, profileId, seed,
                sha256(seed.getBytes(StandardCharsets.UTF_8)),
                List.of(artifact),
                List.of(new NormalizedInputReference(NormalizedArtifact.Kind.IMAGE, 0, artifactId)),
                List.of()
        );
        return runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-" + seed, normalized, profileSnapshot,
                costLimitMicrosCny, T0
        )).run().runId();
    }

    private static AcquisitionFixture hybridPreprocessor(
            AtomicInteger calls,
            String text
    ) {
        var expectedPolicy = successorPolicy();
        VisualEvidenceAcquisition seam = (artifacts, policy) -> {
                assertThat(policy).isEqualTo(expectedPolicy);
                calls.incrementAndGet();
                assertThat(artifacts.artifacts()).singleElement().satisfies(artifact -> {
                    assertThat(artifact.bytes()).isNotEmpty();
                    assertThat(artifact.width()).isEqualTo(1_050);
                    assertThat(artifact.height()).isEqualTo(1_660);
                });
                var artifact = artifacts.artifacts().getFirst();
                return DocumentObservationIR.canonical(
                        policy, successorProvenance(policy),
                        List.of(new DocumentObservationIR.ArtifactObservation(
                                artifact.artifactId(), artifact.sourceOrdinal(), artifact.mediaType(),
                                artifact.width(), artifact.height(), true,
                                List.of(new DocumentObservationIR.TextLine(
                                        "ocr-00-000", 0,
                                        new DocumentObservationIR.SourcePixelBox(53, 17, 525, 83),
                                        new DocumentObservationIR.Confidence(
                                                9_000, policy.confidenceScaleIdentity(),
                                                DocumentObservationIR.ConfidenceBucket.HIGH,
                                                policy.confidenceBucketProjectionIdentity()
                                        ),
                                        text, DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED
                                ))
                        ))
                );
        };
        return new AcquisitionFixture(seam, expectedPolicy);
    }

    private static AcquisitionFixture denseSequencePreprocessor(AtomicInteger calls) {
        var expectedPolicy = successorPolicy();
        VisualEvidenceAcquisition seam = (artifacts, policy) -> {
                assertThat(policy).isEqualTo(expectedPolicy);
                calls.incrementAndGet();
                var artifact = artifacts.artifacts().getFirst();
                var lines = new ArrayList<DocumentObservationIR.TextLine>();
                for (var index = 0; index < 10; index++) {
                    var left = 53 + index * 84;
                    lines.add(new DocumentObservationIR.TextLine(
                            "ocr-00-%03d".formatted(index), index,
                            new DocumentObservationIR.SourcePixelBox(left, 664, left + 42, 996),
                            new DocumentObservationIR.Confidence(
                                    9_000, policy.confidenceScaleIdentity(),
                                    DocumentObservationIR.ConfidenceBucket.HIGH,
                                    policy.confidenceBucketProjectionIdentity()
                            ),
                            "item", DocumentObservationIR.Sensitivity.EPHEMERAL_UNTRUSTED
                    ));
                }
                return DocumentObservationIR.canonical(
                        policy, successorProvenance(policy),
                        List.of(new DocumentObservationIR.ArtifactObservation(
                                artifact.artifactId(), artifact.sourceOrdinal(), artifact.mediaType(),
                                artifact.width(), artifact.height(), true, lines
                        ))
                );
        };
        return new AcquisitionFixture(seam, expectedPolicy);
    }

    private static AcquisitionPolicy successorPolicy() {
        return new AcquisitionPolicy(
                AcquisitionPolicy.VERSION, DocumentObservationIR.VERSION,
                DOCUMENT_VISION_CAPABILITY, "rapidocr-local-process/1.0",
                "rapidocr-openvino-ppocrv6-small", "rapidocr-3.9.2+openvino-2026.0.0",
                "b".repeat(64), "explicit-bgr/1.0", "rapidocr-lines/1.0",
                "source-pixel-top-left/1.0", "half-open-box/1.0",
                "v45-source-to-candidate/1.0", "top-left-canonical/1.0",
                "unicode-nfc-whitespace-collapse/1.0", "basis-points/1.0",
                "v45-confidence-buckets/1.0",
                AcquisitionPolicy.TextExposure.EPHEMERAL_STAGE_CONTEXT_ONLY,
                10, 512, 256, 32 * 1024, 512 * 1024, 30_000
        );
    }

    private static DocumentObservationIR.Provenance successorProvenance(AcquisitionPolicy policy) {
        return new DocumentObservationIR.Provenance(
                policy.capabilityIdentity(), policy.adapterIdentity(), policy.engine(),
                policy.engineVersion(), policy.modelManifestSha256(), policy.preprocessingIdentity(),
                policy.postprocessingIdentity(), policy.readingOrderDerivationIdentity(),
                policy.projectionIdentity(), policy.confidenceScaleIdentity(),
                policy.confidenceBucketProjectionIdentity(), policy.canonicalizationIdentity()
        );
    }

    private record AcquisitionFixture(
            VisualEvidenceAcquisition seam,
            AcquisitionPolicy policy
    ) {
    }

    private UUID createCombined(MemoryBlobStore blobs, String seed) {
        return createCombined(blobs, seed, "{\"title\":\"hello\"}");
    }

    private UUID createCombined(MemoryBlobStore blobs, String seed, String jsonSample) {
        return createCombined(blobs, seed, jsonSample, PROFILE);
    }

    private UUID createCombined(
            MemoryBlobStore blobs,
            String seed,
            String jsonSample,
            String profileId
    ) {
        var profile = profiles.require(profileId);
        var imageBytes = ("synthetic-image:" + seed).getBytes(StandardCharsets.UTF_8);
        var imageId = sha256(imageBytes);
        blobs.values.put(imageId, imageBytes);
        var image = new NormalizedArtifact(
                imageId, NormalizedArtifact.Kind.IMAGE, imageId,
                "image/png", imageBytes.length, 32, 16
        );
        var sample = new InferenceInput.BinaryInput(
                "sample.json", "application/json", jsonSample.getBytes(StandardCharsets.UTF_8)
        );
        var profileBytes = new StrictJsonSampleProfiler().profile(List.of(sample));
        var jsonId = sha256(profileBytes);
        blobs.values.put(jsonId, profileBytes);
        var jsonProfile = new NormalizedArtifact(
                jsonId, NormalizedArtifact.Kind.JSON_PROFILE, jsonId,
                "application/vnd.renderweave.json-profile+json", profileBytes.length, null, null
        );
        var normalized = new NormalizedInput(
                InferenceMode.COMBINED, profileId, seed, sha256(seed.getBytes(StandardCharsets.UTF_8)),
                List.of(image, jsonProfile),
                List.of(
                        new NormalizedInputReference(NormalizedArtifact.Kind.IMAGE, 0, imageId),
                        new NormalizedInputReference(NormalizedArtifact.Kind.JSON_PROFILE, 0, jsonId)
                ),
                List.of()
        );
        return runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-" + seed, normalized, profile.snapshotJson(), T0
        )).run().runId();
    }

    private UUID createJsonOnlyGrounded(MemoryBlobStore blobs, String seed, String jsonSample) {
        return createJsonOnlyGrounded(blobs, seed, List.of(jsonSample));
    }

    private UUID createJsonOnlyGrounded(
            MemoryBlobStore blobs,
            String seed,
            List<String> jsonSamples
    ) {
        var profile = profiles.require(GROUNDED_PROFILE);
        var samples = jsonSamples.stream().map(jsonSample -> new InferenceInput.BinaryInput(
                "sample.json", "application/json", jsonSample.getBytes(StandardCharsets.UTF_8)
        )).toList();
        var profileBytes = new StrictJsonSampleProfiler().profile(samples);
        var jsonId = sha256(profileBytes);
        blobs.values.put(jsonId, profileBytes);
        var jsonProfile = new NormalizedArtifact(
                jsonId, NormalizedArtifact.Kind.JSON_PROFILE, jsonId,
                "application/vnd.renderweave.json-profile+json", profileBytes.length, null, null
        );
        var normalized = new NormalizedInput(
                InferenceMode.JSON_ONLY,
                GROUNDED_PROFILE,
                seed,
                sha256(seed.getBytes(StandardCharsets.UTF_8)),
                List.of(jsonProfile),
                List.of(new NormalizedInputReference(
                        NormalizedArtifact.Kind.JSON_PROFILE, 0, jsonId
                )),
                List.of()
        );
        return runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-" + seed, normalized, profile.snapshotJson(), T0
        )).run().runId();
    }

    private static String groundedStationElements() {
        return """
                {"contractVersion":"renderweave-visual-grounding/2.0","regions":[
                  {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                  {"regionId":"header","parentRegionId":"root","kind":"SECTION","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":1000}}]},
                  {"regionId":"notice","parentRegionId":"root","kind":"GROUP","multiplicity":"ONE","readingOrder":1,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":1000,"right":10000,"bottom":2200}}]},
                  {"regionId":"routes","parentRegionId":"root","kind":"REPEATED_GROUP","multiplicity":"MANY","readingOrder":2,"repeatGroupId":"routes","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2200,"right":10000,"bottom":10000}}]},
                  {"regionId":"route-item","parentRegionId":"routes","kind":"ITEM","multiplicity":"ONE","readingOrder":0,"repeatGroupId":"routes","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2200,"right":10000,"bottom":10000}}]},
                  {"regionId":"stops","parentRegionId":"route-item","kind":"REPEATED_GROUP","multiplicity":"MANY","readingOrder":0,"repeatGroupId":"stops","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":4000,"right":10000,"bottom":10000}}]},
                  {"regionId":"stop-item","parentRegionId":"stops","kind":"ITEM","multiplicity":"ONE","readingOrder":0,"repeatGroupId":"stops","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":4000,"right":10000,"bottom":10000}}]}
                ],"elements":[
                  {"elementId":"station-name","kind":"SLOT","proposedKey":"stationName","displayName":"站点名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["header"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":100,"right":5000,"bottom":400}}]},
                  {"elementId":"station-english","kind":"SLOT","proposedKey":"stationEnglishName","displayName":"站点英文名","multiplicity":"ONE","valueHint":"TEXT","regionIds":["header"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":450,"right":5000,"bottom":800}}]},
                  {"elementId":"notice-group","kind":"GROUP","proposedKey":"warmNotice","displayName":"温馨提示","multiplicity":"ONE","valueHint":null,"regionIds":["notice"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":1000,"right":10000,"bottom":2200}}]},
                  {"elementId":"notice-date","kind":"SLOT","proposedKey":"effectiveDate","displayName":"生效日期","multiplicity":"ONE","valueHint":"DATE","regionIds":["notice"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":1000,"top":1200,"right":4000,"bottom":1500}}]},
                  {"elementId":"notice-content","kind":"SLOT","proposedKey":"content","displayName":"提示内容","multiplicity":"ONE","valueHint":"TEXT","regionIds":["notice"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":1000,"top":1600,"right":9000,"bottom":2000}}]},
                  {"elementId":"route-group","kind":"GROUP","proposedKey":"routes","displayName":"线路","multiplicity":"MANY","valueHint":null,"regionIds":["routes"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2200,"right":10000,"bottom":10000}}]},
                  {"elementId":"route-number","kind":"SLOT","proposedKey":"routeNumber","displayName":"线路编号","multiplicity":"ONE","valueHint":"TEXT","regionIds":["route-item"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":2400,"right":2500,"bottom":2900}}]},
                  {"elementId":"stop-group","kind":"GROUP","proposedKey":"stops","displayName":"停靠站点","multiplicity":"MANY","valueHint":null,"regionIds":["stops"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":4000,"right":10000,"bottom":10000}}]},
                  {"elementId":"stop-name","kind":"SLOT","proposedKey":"name","displayName":"站点名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["stop-item"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":4300,"right":3000,"bottom":4800}}]}
                ]}
                """;
    }

    private static String groundedStationElementsWithMisparentedNotice() {
        return groundedStationElements()
                .replace(
                        "{\"regionId\":\"notice\",\"parentRegionId\":\"root\",\"kind\":\"GROUP\",\"multiplicity\":\"ONE\",\"readingOrder\":1,\"repeatGroupId\":null,\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":1000,\"right\":10000,\"bottom\":2200}}]}",
                        "{\"regionId\":\"notice-shell\",\"parentRegionId\":\"root\",\"kind\":\"SECTION\",\"multiplicity\":\"ONE\",\"readingOrder\":1,\"repeatGroupId\":null,\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":1000,\"right\":10000,\"bottom\":2200}}]},\n                  "
                                + "{\"regionId\":\"notice\",\"parentRegionId\":\"routes\",\"kind\":\"GROUP\",\"multiplicity\":\"ONE\",\"readingOrder\":0,\"repeatGroupId\":null,\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":1100,\"right\":10000,\"bottom\":2100}}]}"
                )
                .replace(
                        "\"elementId\":\"notice-group\",\"kind\":\"GROUP\",\"proposedKey\":\"warmNotice\",\"displayName\":\"温馨提示\",\"multiplicity\":\"ONE\",\"valueHint\":null,\"regionIds\":[\"notice\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":1000,\"right\":10000,\"bottom\":2200}}]",
                        "\"elementId\":\"notice-group\",\"kind\":\"GROUP\",\"proposedKey\":\"warmNotice\",\"displayName\":\"温馨提示\",\"multiplicity\":\"ONE\",\"valueHint\":null,\"regionIds\":[\"notice\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":1100,\"right\":10000,\"bottom\":2100}}]"
                );
    }

    private static String manyGroupWithSingularRegion() {
        return groundedStationElements().replace(
                "{\"elementId\":\"notice-date\"",
                "{\"elementId\":\"invalid-many-group\",\"kind\":\"GROUP\","
                        + "\"proposedKey\":\"repeatedNotice\",\"displayName\":\"重复提示\","
                        + "\"multiplicity\":\"MANY\",\"valueHint\":null,"
                        + "\"regionIds\":[\"notice\"],\"evidence\":[{\"viewId\":"
                        + "\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":1000,"
                        + "\"right\":10000,\"bottom\":2200}}]},\n                  "
                        + "{\"elementId\":\"notice-date\""
        );
    }

    private static String groundedStationElementsWithMisownedStationName() {
        return groundedStationElements().replaceFirst(
                "\"regionIds\":\\[\"header\"\\]",
                "\"regionIds\":[\"notice\"]"
        );
    }

    private static String repeatedItemSlotOwnerElements() {
        return """
                {"contractVersion":"renderweave-visual-grounding/2.0","regions":[
                  {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                  {"regionId":"header","parentRegionId":"root","kind":"SECTION","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":2000}}]},
                  {"regionId":"repeat","parentRegionId":"root","kind":"REPEATED_GROUP","multiplicity":"MANY","readingOrder":1,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                  {"regionId":"item-a","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":0,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":6000}}]},
                  {"regionId":"item-b","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":1,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":6000,"right":10000,"bottom":10000}}]}
                ],"elements":[
                  {"elementId":"title","kind":"SLOT","proposedKey":"title","displayName":"标题","multiplicity":"ONE","valueHint":"TEXT","regionIds":["header"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":100,"right":3000,"bottom":700}}]},
                  {"elementId":"row-group","kind":"GROUP","proposedKey":"items","displayName":"重复项目","multiplicity":"MANY","valueHint":null,"regionIds":["repeat"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                  {"elementId":"item-label","kind":"SLOT","proposedKey":"label","displayName":"项目名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["repeat"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":2300,"right":3000,"bottom":2800}},{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":6300,"right":3000,"bottom":6800}}]}
                ]}
                """;
    }

    private static String repeatedItemSlotOwnerHierarchy() {
        return """
                {"contractVersion":"renderweave-visual-hierarchy/2.0","rootEntityId":"document","entities":[
                  {"entityId":"document","schemaKey":"document","displayName":"文档","regionIds":["root"],"supportingElementIds":["title"]},
                  {"entityId":"item","schemaKey":"item","displayName":"项目","regionIds":["item-a","item-b"],"supportingElementIds":["row-group"]}
                ],"relationships":[
                  {"relationshipId":"document-items","parentEntityId":"document","childEntityId":"item","fieldKey":"items","displayName":"项目","cardinality":"MANY","regionId":"repeat","supportingElementIds":["row-group"]}
                ]}
                """;
    }

    private static String repeatedItemSlotOwnerBindings() {
        return """
                {"contractVersion":"renderweave-visual-bindings/2.0","bindings":[
                  {"elementId":"title","entityId":"document"},
                  {"elementId":"item-label","entityId":"item"}
                ]}
                """;
    }

    private static String repeatedObservationElements() {
        return """
                {"contractVersion":"renderweave-visual-grounding/2.0","regions":[
                  {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                  {"regionId":"repeat","parentRegionId":"root","kind":"REPEATED_GROUP","multiplicity":"MANY","readingOrder":0,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                  {"regionId":"item-a","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":0,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":6000}}]},
                  {"regionId":"item-b","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":1,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":6000,"right":10000,"bottom":10000}}]}
                ],"elements":[
                  {"elementId":"title","kind":"SLOT","proposedKey":"title","displayName":"Title","multiplicity":"ONE","valueHint":"TEXT","regionIds":["root"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":100,"right":3000,"bottom":700}}]},
                  {"elementId":"row-group","kind":"GROUP","proposedKey":"items","displayName":"Items","multiplicity":"MANY","valueHint":null,"regionIds":["repeat"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                  {"elementId":"item-label-a","kind":"SLOT","proposedKey":"label","displayName":"Label","multiplicity":"ONE","valueHint":"TEXT","regionIds":["item-a"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":2300,"right":3000,"bottom":2800}}]},
                  {"elementId":"item-label-b","kind":"SLOT","proposedKey":"label","displayName":"Label","multiplicity":"ONE","valueHint":"TEXT","regionIds":["item-b"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":6300,"right":3000,"bottom":6800}}]}
                ]}
                """;
    }

    private static String repeatedObservationHierarchy() {
        return """
                {"contractVersion":"renderweave-visual-hierarchy/2.0","rootEntityId":"document","entities":[
                  {"entityId":"document","schemaKey":"document","displayName":"Document","regionIds":["root"],"supportingElementIds":["title"]},
                  {"entityId":"item","schemaKey":"item","displayName":"Item","regionIds":["item-a","item-b"],"supportingElementIds":["row-group"]}
                ],"relationships":[
                  {"relationshipId":"document-items","parentEntityId":"document","childEntityId":"item","fieldKey":"items","displayName":"Items","cardinality":"MANY","regionId":"repeat","supportingElementIds":["row-group"]}
                ]}
                """;
    }

    private static String repeatedObservationBindings() {
        return """
                {"contractVersion":"renderweave-visual-bindings/2.0","bindings":[
                  {"elementId":"title","entityId":"document"},
                  {"elementId":"item-label-a","entityId":"item"},
                  {"elementId":"item-label-b","entityId":"item"}
                ]}
                """;
    }

    private static String sourceAncestorElements() {
        return """
                {"contractVersion":"renderweave-visual-grounding/2.0","regions":[
                  {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                  {"regionId":"routes","parentRegionId":"root","kind":"REPEATED_GROUP","multiplicity":"MANY","readingOrder":0,"repeatGroupId":"routes","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":1000,"right":10000,"bottom":10000}}]},
                  {"regionId":"route-item","parentRegionId":"routes","kind":"ITEM","multiplicity":"ONE","readingOrder":0,"repeatGroupId":"routes","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":1000,"right":10000,"bottom":10000}}]}
                ],"elements":[
                  {"elementId":"route-group","kind":"GROUP","proposedKey":"routes","displayName":"线路","multiplicity":"MANY","valueHint":null,"regionIds":["routes"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":1000,"right":10000,"bottom":10000}}]},
                  {"elementId":"route-number","kind":"SLOT","proposedKey":"routeNumber","displayName":"线路编号","multiplicity":"ONE","valueHint":"TEXT","regionIds":["root","route-item"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":1400,"right":2500,"bottom":1900}}]}
                ]}
                """;
    }

    private static String sourceAncestorHierarchy() {
        return """
                {"contractVersion":"renderweave-visual-hierarchy/2.0","rootEntityId":"board","entities":[
                  {"entityId":"board","schemaKey":"board","displayName":"站牌","regionIds":["root"],"supportingElementIds":["route-group"]},
                  {"entityId":"route","schemaKey":"route","displayName":"线路","regionIds":["root","route-item"],"supportingElementIds":["route-number"]}
                ],"relationships":[
                  {"relationshipId":"board-routes","parentEntityId":"board","childEntityId":"route","fieldKey":"routes","displayName":"线路","cardinality":"MANY","regionId":"route-item","supportingElementIds":["route-number"]}
                ]}
                """;
    }

    private static String sourceAncestorBindings() {
        return """
                {"contractVersion":"renderweave-visual-bindings/2.0","bindings":[
                  {"elementId":"route-number","entityId":"route"}
                ]}
                """;
    }

    private static String groundedStationElementsWithoutNoticeRegionOwner() {
        return groundedStationElements()
                .replace(
                        "{\"regionId\":\"routes\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":2",
                        "{\"regionId\":\"notice-owner\",\"parentRegionId\":\"notice\",\"kind\":\"GROUP\",\"multiplicity\":\"ONE\",\"readingOrder\":0,\"repeatGroupId\":null,\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":1000,\"right\":10000,\"bottom\":2200}}]},\n                  {\"regionId\":\"routes\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":2"
                )
                .replace(
                        "\"elementId\":\"notice-group\",\"kind\":\"GROUP\",\"proposedKey\":\"warmNotice\",\"displayName\":\"温馨提示\",\"multiplicity\":\"ONE\",\"valueHint\":null,\"regionIds\":[\"notice\"]",
                        "\"elementId\":\"notice-group\",\"kind\":\"GROUP\",\"proposedKey\":\"warmNotice\",\"displayName\":\"温馨提示\",\"multiplicity\":\"ONE\",\"valueHint\":null,\"regionIds\":[\"notice-owner\"]"
                );
    }

    private static String flatGroundedStationElements() {
        return """
                {"contractVersion":"renderweave-visual-grounding/2.0","regions":[
                  {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                  {"regionId":"content","parentRegionId":"root","kind":"SECTION","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]}
                ],"elements":[
                  {"elementId":"station-name","kind":"SLOT","proposedKey":"stationName","displayName":"站点名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["content"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":100,"right":5000,"bottom":400}}]},
                  {"elementId":"route-number","kind":"SLOT","proposedKey":"routeNumber","displayName":"线路编号","multiplicity":"ONE","valueHint":"TEXT","regionIds":["content"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":2400,"right":2500,"bottom":2900}}]},
                  {"elementId":"stop-name","kind":"SLOT","proposedKey":"name","displayName":"站点名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["content"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":4300,"right":3000,"bottom":4800}}]}
                ]}
                """;
    }

    private static String groundedStationHierarchy() {
        return """
                {"contractVersion":"renderweave-visual-hierarchy/2.0","rootEntityId":"board","entities":[
                  {"entityId":"board","schemaKey":"bus-stop-board","displayName":"站牌","regionIds":["root"],"supportingElementIds":["station-name"]},
                  {"entityId":"notice-entity","schemaKey":"warm-notice","displayName":"温馨提示","regionIds":["notice"],"supportingElementIds":["notice-group"]},
                  {"entityId":"route","schemaKey":"bus-route","displayName":"线路","regionIds":["route-item"],"supportingElementIds":["route-group"]},
                  {"entityId":"stop","schemaKey":"bus-stop","displayName":"停靠站点","regionIds":["stop-item"],"supportingElementIds":["stop-group"]}
                ],"relationships":[
                  {"relationshipId":"board-notice","parentEntityId":"board","childEntityId":"notice-entity","fieldKey":"warmNotice","displayName":"温馨提示","cardinality":"ONE","regionId":"notice","supportingElementIds":["notice-group"]},
                  {"relationshipId":"board-routes","parentEntityId":"board","childEntityId":"route","fieldKey":"routes","displayName":"线路","cardinality":"MANY","regionId":"routes","supportingElementIds":["route-group"]},
                  {"relationshipId":"route-stops","parentEntityId":"route","childEntityId":"stop","fieldKey":"stops","displayName":"停靠站点","cardinality":"MANY","regionId":"stops","supportingElementIds":["stop-group"]}
                ]}
                """;
    }

    private static String groundedStationHierarchyWithoutNoticeEdge() {
        return """
                {"contractVersion":"renderweave-visual-hierarchy/2.0","rootEntityId":"board","entities":[
                  {"entityId":"board","schemaKey":"bus-stop-board","displayName":"站牌","regionIds":["root"],"supportingElementIds":["station-name","notice-group"]},
                  {"entityId":"route","schemaKey":"bus-route","displayName":"线路","regionIds":["route-item"],"supportingElementIds":["route-group"]},
                  {"entityId":"stop","schemaKey":"bus-stop","displayName":"停靠站点","regionIds":["stop-item"],"supportingElementIds":["stop-group"]}
                ],"relationships":[
                  {"relationshipId":"board-routes","parentEntityId":"board","childEntityId":"route","fieldKey":"routes","displayName":"线路","cardinality":"MANY","regionId":"routes","supportingElementIds":["route-group"]},
                  {"relationshipId":"route-stops","parentEntityId":"route","childEntityId":"stop","fieldKey":"stops","displayName":"停靠站点","cardinality":"MANY","regionId":"stops","supportingElementIds":["stop-group"]}
                ]}
                """;
    }

    private static String groundedStationBindings() {
        return """
                {"contractVersion":"renderweave-visual-bindings/2.0","bindings":[
                  {"elementId":"station-name","entityId":"board"},
                  {"elementId":"station-english","entityId":"board"},
                  {"elementId":"notice-date","entityId":"notice-entity"},
                  {"elementId":"notice-content","entityId":"notice-entity"},
                  {"elementId":"route-number","entityId":"route"},
                  {"elementId":"stop-name","entityId":"stop"}
                ]}
                """;
    }

    private String stationElements(ProviderInferenceRequest request) {
        var artifactId = request.images().getFirst().artifactId();
        return """
                {"contractVersion":"renderweave-visual-elements/1.0","elements":[
                  {"elementId":"station-name","kind":"SLOT","proposedKey":"stationName","displayName":"站点名称","multiplicity":"ONE","valueHint":"TEXT","evidence":[%s]},
                  {"elementId":"station-english","kind":"SLOT","proposedKey":"stationEnglishName","displayName":"站点英文名","multiplicity":"ONE","valueHint":"TEXT","evidence":[%s]},
                  {"elementId":"notice-group","kind":"GROUP","proposedKey":"warmNotice","displayName":"温馨提示","multiplicity":"ONE","valueHint":null,"evidence":[%s]},
                  {"elementId":"notice-date","kind":"SLOT","proposedKey":"effectiveDate","displayName":"生效日期","multiplicity":"ONE","valueHint":"DATE","evidence":[%s]},
                  {"elementId":"notice-content","kind":"SLOT","proposedKey":"content","displayName":"提示内容","multiplicity":"ONE","valueHint":"TEXT","evidence":[%s]},
                  {"elementId":"route-group","kind":"GROUP","proposedKey":"routes","displayName":"线路","multiplicity":"MANY","valueHint":null,"evidence":[%s]},
                  {"elementId":"route-number","kind":"SLOT","proposedKey":"routeNumber","displayName":"线路编号","multiplicity":"ONE","valueHint":"TEXT","evidence":[%s]},
                  {"elementId":"stop-group","kind":"GROUP","proposedKey":"stops","displayName":"停靠站点","multiplicity":"MANY","valueHint":null,"evidence":[%s]},
                  {"elementId":"stop-name","kind":"SLOT","proposedKey":"name","displayName":"站点名称","multiplicity":"ONE","valueHint":"TEXT","evidence":[%s]}
                ]}
                """.formatted(
                evidenceJson(artifactId, 100), evidenceJson(artifactId, 400),
                evidenceJson(artifactId, 900), evidenceJson(artifactId, 1100),
                evidenceJson(artifactId, 1400), evidenceJson(artifactId, 2500),
                evidenceJson(artifactId, 2800), evidenceJson(artifactId, 3800),
                evidenceJson(artifactId, 4200)
        );
    }

    private static String stationHierarchy() {
        return """
                {"contractVersion":"renderweave-visual-hierarchy/1.0","rootEntityId":"board",
                 "entities":[
                   {"entityId":"board","schemaKey":"bus-stop-board","displayName":"站牌","supportingElementIds":["station-name"]},
                   {"entityId":"notice","schemaKey":"warm-notice","displayName":"温馨提示","supportingElementIds":["notice-group"]},
                   {"entityId":"route","schemaKey":"bus-route","displayName":"线路","supportingElementIds":["route-group"]},
                   {"entityId":"stop","schemaKey":"bus-stop","displayName":"停靠站点","supportingElementIds":["stop-group"]}
                 ],"relationships":[
                   {"relationshipId":"board-notice","parentEntityId":"board","childEntityId":"notice","fieldKey":"warmNotice","displayName":"温馨提示","cardinality":"ONE","supportingElementIds":["notice-group"]},
                   {"relationshipId":"board-routes","parentEntityId":"board","childEntityId":"route","fieldKey":"routes","displayName":"线路","cardinality":"MANY","supportingElementIds":["route-group"]},
                   {"relationshipId":"route-stops","parentEntityId":"route","childEntityId":"stop","fieldKey":"stops","displayName":"停靠站点","cardinality":"MANY","supportingElementIds":["stop-group"]}
                 ]}
                """;
    }

    private static String stationBindings() {
        return """
                {"contractVersion":"renderweave-visual-bindings/1.0","bindings":[
                  {"elementId":"station-name","entityId":"board"},
                  {"elementId":"station-english","entityId":"board"},
                  {"elementId":"notice-date","entityId":"notice"},
                  {"elementId":"notice-content","entityId":"notice"},
                  {"elementId":"route-number","entityId":"route"},
                  {"elementId":"stop-name","entityId":"stop"}
                ]}
                """;
    }

    private String stationCandidate(ProviderInferenceRequest request) {
        var boardId = UUID.nameUUIDFromBytes((request.runId() + ":board").getBytes(StandardCharsets.UTF_8));
        var noticeId = UUID.nameUUIDFromBytes((request.runId() + ":notice").getBytes(StandardCharsets.UTF_8));
        var routeId = UUID.nameUUIDFromBytes((request.runId() + ":route").getBytes(StandardCharsets.UTF_8));
        var stopId = UUID.nameUUIDFromBytes((request.runId() + ":stop").getBytes(StandardCharsets.UTF_8));
        var artifactId = request.images().getFirst().artifactId();
        var board = new CandidateSchema(
                boardId, "bus-stop-board", "站牌", CandidateSource.AI,
                stationAssessment(artifactId, 100),
                List.of(
                        stationField(request, "station-name", "stationName", CandidateValueKind.TEXT, 100),
                        stationField(request, "station-english", "stationEnglishName", CandidateValueKind.TEXT, 400),
                        stationReferenceField(request, "warm-notice", "warmNotice", noticeId, false, 900),
                        stationReferenceField(request, "routes", "routes", routeId, true, 2500)
                )
        );
        var notice = new CandidateSchema(
                noticeId, "warm-notice", "温馨提示", CandidateSource.AI,
                stationAssessment(artifactId, 900),
                List.of(
                        stationField(request, "notice-date", "effectiveDate", CandidateValueKind.DATE, 1100),
                        stationField(request, "notice-content", "content", CandidateValueKind.TEXT, 1400)
                )
        );
        var route = new CandidateSchema(
                routeId, "bus-route", "线路", CandidateSource.AI,
                stationAssessment(artifactId, 2500),
                List.of(
                        stationField(request, "route-number", "routeNumber", CandidateValueKind.TEXT, 2800),
                        stationReferenceField(request, "stops", "stops", stopId, true, 3800)
                )
        );
        var stop = new CandidateSchema(
                stopId, "bus-stop", "停靠站点", CandidateSource.AI,
                stationAssessment(artifactId, 3800),
                List.of(stationField(request, "stop-name", "name", CandidateValueKind.TEXT, 4200))
        );
        return candidateCodec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, boardId, List.of(board, notice, route, stop)
        ));
    }

    private static CandidateField stationField(
            ProviderInferenceRequest request,
            String id,
            String key,
            CandidateValueKind kind,
            int top
    ) {
        return new CandidateField(
                UUID.nameUUIDFromBytes((request.runId() + ":" + id).getBytes(StandardCharsets.UTF_8)),
                key, key, false, CandidateValue.scalar(kind), CandidateSource.AI,
                stationAssessment(request.images().getFirst().artifactId(), top)
        );
    }

    private static CandidateField stationReferenceField(
            ProviderInferenceRequest request,
            String id,
            String key,
            UUID target,
            boolean many,
            int top
    ) {
        var reference = CandidateValue.reference(CandidateReference.candidate(target));
        return new CandidateField(
                UUID.nameUUIDFromBytes((request.runId() + ":" + id).getBytes(StandardCharsets.UTF_8)),
                key, key, false, many ? CandidateValue.array(reference) : reference, CandidateSource.AI,
                stationAssessment(request.images().getFirst().artifactId(), top)
        );
    }

    private static CandidateAssessment stationAssessment(String artifactId, int top) {
        return CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.image(
                        artifactId, new CandidateBoundingBox(500, top, 9_500, top + 200)
                ))
        );
    }

    private static String evidenceJson(String artifactId, int top) {
        return """
                {"kind":"IMAGE","artifactId":"%s","boundingBox":{"left":500,"top":%d,"right":9500,"bottom":%d},"sampleIndex":null,"jsonPointer":null}
                """.formatted(artifactId, top, top + 200).strip();
    }

    private String candidate(ProviderInferenceRequest request) {
        return candidate(request, false);
    }

    private String candidate(ProviderInferenceRequest request, boolean required) {
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
                                fieldId, "title", "标题", required,
                                CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI, assessment
                        ))
                ))
        ));
    }

    private String candidateWithMixedBlockers(ProviderInferenceRequest request) {
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
                                fieldId, "title", "标题", true,
                                CandidateValue.unresolved("null"), CandidateSource.AI, assessment
                        ))
                ))
        ));
    }

    private String candidateWithHumanBlocker(ProviderInferenceRequest request) {
        var schemaId = UUID.nameUUIDFromBytes((request.runId() + ":schema").getBytes(StandardCharsets.UTF_8));
        var fieldId = UUID.nameUUIDFromBytes((request.runId() + ":field").getBytes(StandardCharsets.UTF_8));
        var evidence = CandidateEvidence.image(
                request.images().getFirst().artifactId(), new CandidateBoundingBox(500, 500, 9_500, 2_500)
        );
        var schemaAssessment = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED, List.of(evidence)
        );
        var fieldAssessment = CandidateAssessment.ai(
                3_000, true, CandidateResolution.UNRESOLVED, List.of(evidence)
        );
        return candidateCodec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "synthetic-card", "合成卡片", CandidateSource.AI, schemaAssessment,
                        List.of(new CandidateField(
                                fieldId, "title", "标题", false,
                                CandidateValue.unresolved("null"), CandidateSource.AI, fieldAssessment
                        ))
                ))
        ));
    }

    private String candidateWithCanonicalFormattingNoise(ProviderInferenceRequest request) {
        var schemaId = UUID.nameUUIDFromBytes((request.runId() + ":schema").getBytes(StandardCharsets.UTF_8));
        var evidence = CandidateEvidence.image(
                request.images().getFirst().artifactId(), new CandidateBoundingBox(500, 500, 9_500, 2_500)
        );
        var schemaAssessment = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED, List.of(evidence)
        );
        var fields = new ArrayList<CandidateField>();
        for (var index = 0; index < 6; index++) {
            var assessment = CandidateAssessment.ai(
                    9_000, true,
                    index == 0 ? CandidateResolution.UNRESOLVED : CandidateResolution.NOT_REQUIRED,
                    List.of(evidence)
            );
            fields.add(new CandidateField(
                    UUID.nameUUIDFromBytes((request.runId() + ":field:" + index).getBytes(StandardCharsets.UTF_8)),
                    "field" + index, "字段 " + (index + 1), false,
                    new CandidateValue(
                            CandidateValueKind.TEXT, null, null, List.of("TEXT"), Map.of()
                    ),
                    CandidateSource.AI, assessment
            ));
        }
        return candidateCodec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "商品卡片", "商品卡片", CandidateSource.AI, schemaAssessment, fields
                ))
        ));
    }

    private String candidateWithPixelCoordinateEvidence(ProviderInferenceRequest request) {
        var schemaId = UUID.nameUUIDFromBytes((request.runId() + ":schema").getBytes(StandardCharsets.UTF_8));
        var boxes = new int[][]{
                {200, 200, 1_300, 500},
                {200, 500, 1_300, 700},
                {200, 700, 800, 800},
                {800, 700, 1_300, 800},
                {200, 800, 1_300, 900},
                {200, 900, 1_300, 3_800},
                {200, 3_900, 1_300, 4_096}
        };
        var fields = new ArrayList<CandidateField>();
        for (var index = 0; index < boxes.length; index++) {
            var box = boxes[index];
            var evidence = CandidateEvidence.image(
                    request.images().getFirst().artifactId(),
                    new CandidateBoundingBox(box[0], box[1], box[2], box[3])
            );
            fields.add(new CandidateField(
                    UUID.nameUUIDFromBytes((request.runId() + ":field:" + index).getBytes(StandardCharsets.UTF_8)),
                    "field" + index, "字段 " + (index + 1), false,
                    CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI,
                    CandidateAssessment.ai(9_000, true, CandidateResolution.NOT_REQUIRED, List.of(evidence))
            ));
        }
        var schemaEvidence = CandidateEvidence.image(
                request.images().getFirst().artifactId(),
                new CandidateBoundingBox(boxes[0][0], boxes[0][1], boxes[0][2], boxes[0][3])
        );
        return candidateCodec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "route-card", "线路卡", CandidateSource.AI,
                        CandidateAssessment.ai(
                                9_000, true, CandidateResolution.NOT_REQUIRED, List.of(schemaEvidence)
                        ),
                        fields
                ))
        ));
    }

    private String jsonCandidate(ProviderInferenceRequest request) {
        return jsonCandidate(request, "title");
    }

    private String jsonCandidate(ProviderInferenceRequest request, String fieldKey) {
        var schemaId = UUID.nameUUIDFromBytes((request.runId() + ":schema").getBytes(StandardCharsets.UTF_8));
        var fieldId = UUID.nameUUIDFromBytes((request.runId() + ":field").getBytes(StandardCharsets.UTF_8));
        var schemaAssessment = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.json(0, ""))
        );
        var fieldAssessment = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.json(0, "/" + fieldKey))
        );
        return candidateCodec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaId,
                List.of(new CandidateSchema(
                        schemaId, "synthetic-card", "合成卡片", CandidateSource.AI, schemaAssessment,
                        List.of(new CandidateField(
                                fieldId, fieldKey, "标题", false,
                                CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI, fieldAssessment
                        ))
                ))
        ));
    }

    private String groundedCombinedProposal(ProviderInferenceRequest request) {
        var schemaId = UUID.nameUUIDFromBytes(
                (request.runId() + ":grounded-schema").getBytes(StandardCharsets.UTF_8)
        );
        var imageEvidence = CandidateEvidence.image(
                request.images().getFirst().artifactId(),
                new CandidateBoundingBox(500, 500, 9_500, 2_500)
        );
        var schemaAssessment = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED, List.of(imageEvidence)
        );
        var titleAssessment = CandidateAssessment.ai(
                9_000, true, CandidateResolution.NOT_REQUIRED, List.of(imageEvidence)
        );
        var subtitleAssessment = CandidateAssessment.ai(
                6_500, true, CandidateResolution.UNRESOLVED, List.of(imageEvidence)
        );
        return candidateCodec.write(new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION,
                schemaId,
                List.of(new CandidateSchema(
                        schemaId,
                        "grounded-card",
                        "组合卡片",
                        CandidateSource.AI,
                        schemaAssessment,
                        List.of(
                                new CandidateField(
                                        UUID.randomUUID(), "title", "标题", true,
                                        CandidateValue.scalar(CandidateValueKind.DECIMAL),
                                        CandidateSource.AI, titleAssessment
                                ),
                                new CandidateField(
                                        UUID.randomUUID(), "subtitle", "副标题", true,
                                        new CandidateValue(
                                                CandidateValueKind.TEXT, null, null, List.of(),
                                                Map.of("minLength", "1")
                                        ),
                                        CandidateSource.AI, subtitleAssessment
                                )
                        )
                ))
        ));
    }

    private static ProviderInferenceResponse response(ProviderInferenceRequest request, String candidate) {
        return response(request, candidate, "stop");
    }

    private static ProviderInferenceResponse response(
            ProviderInferenceRequest request,
            String candidate,
            String finishReason
    ) {
        return new ProviderInferenceResponse(
                candidate, "req-" + request.attemptOrdinal(), request.profile().model(),
                new ProviderUsage(1_000, 500), finishReason
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

    private static String v50OpaqueElements() {
        return """
                {"contractVersion":"renderweave-visual-grounding/2.0","regions":[
                  {"regionId":"Opaque Root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                  {"regionId":"Opaque Header","parentRegionId":"Opaque Root","kind":"SECTION","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":2000}}]},
                  {"regionId":"Opaque Repeat","parentRegionId":"Opaque Root","kind":"REPEATED_GROUP","multiplicity":"MANY","readingOrder":1,"repeatGroupId":"Opaque Rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                  {"regionId":"Opaque Item A","parentRegionId":"Opaque Repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":0,"repeatGroupId":"Opaque Rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":6000}}]},
                  {"regionId":"Opaque Item B","parentRegionId":"Opaque Repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":1,"repeatGroupId":"Opaque Rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":6000,"right":10000,"bottom":10000}}]}
                ],"elements":[
                  {"elementId":"Opaque Title","kind":"SLOT","proposedKey":"title","displayName":"标题","multiplicity":"ONE","valueHint":"TEXT","regionIds":["Opaque Header"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":100,"right":3000,"bottom":700}}]},
                  {"elementId":"Opaque Row Group","kind":"GROUP","proposedKey":"items","displayName":"重复项目","multiplicity":"MANY","valueHint":null,"regionIds":["Opaque Repeat"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                  {"elementId":"Opaque Item Label","kind":"SLOT","proposedKey":"label","displayName":"项目名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["Opaque Item A","Opaque Item B"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":2300,"right":3000,"bottom":2800}},{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":6300,"right":3000,"bottom":6800}}]}
                ]}
                """;
    }

    private static String v50CanonicalHierarchy() {
        return """
                {"contractVersion":"renderweave-visual-hierarchy/2.0","rootEntityId":"document","entities":[
                  {"entityId":"document","schemaKey":"document","displayName":"文档","regionIds":["r1"],"supportingElementIds":["e1"]},
                  {"entityId":"item","schemaKey":"item","displayName":"项目","regionIds":["r4","r5"],"supportingElementIds":["e2"]}
                ],"relationships":[
                  {"relationshipId":"document-items","parentEntityId":"document","childEntityId":"item","fieldKey":"items","displayName":"项目","cardinality":"MANY","regionId":"r3","supportingElementIds":["e2"]}
                ]}
                """;
    }

    private static String v50CanonicalBindings() {
        return """
                {"contractVersion":"renderweave-visual-bindings/2.0","bindings":[
                  {"elementId":"e1","entityId":"document"},
                  {"elementId":"e3","entityId":"item"}
                ]}
                """;
    }

    private static String mixedRegionIdAndParent(String regionId, String parentRegionId) {
        return groundedStationElements()
                .replaceFirst("\"regionId\":\"header\"", "\"regionId\":\"" + regionId + "\"")
                .replaceFirst(
                        "\"parentRegionId\":\"root\"",
                        "\"parentRegionId\":\"" + parentRegionId + "\""
                );
    }

    private static String mixedRegionIdAndReadingOrder(String regionId) {
        return groundedStationElements().replaceFirst(
                "\"regionId\":\"header\",\"parentRegionId\":\"root\",\"kind\":\"SECTION\","
                        + "\"multiplicity\":\"ONE\",\"readingOrder\":0",
                "\"regionId\":\"" + regionId + "\",\"parentRegionId\":\"root\","
                        + "\"kind\":\"SECTION\",\"multiplicity\":\"ONE\",\"readingOrder\":128"
        );
    }

    private static Step[] repeatSteps(int count, Step step) {
        var result = new Step[count];
        java.util.Arrays.fill(result, step);
        return result;
    }

    @FunctionalInterface
    private interface Step {
        ProviderInferenceResponse apply(ProviderInferenceRequest request);
    }

    private static final class MemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> values = new HashMap<>();
        private final AtomicInteger readCalls = new AtomicInteger();

        @Override
        public WriteReceipt write(String artifactId, byte[] bytes) {
            return new WriteReceipt(artifactId, values.putIfAbsent(artifactId, bytes.clone()) == null);
        }

        @Override
        public byte[] read(String locator) {
            readCalls.incrementAndGet();
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

    private static String candidateSemanticSummary(CandidateBundle candidate) {
        var schemasById = candidate.schemas().stream().collect(java.util.stream.Collectors.toMap(
                CandidateSchema::candidateSchemaId, CandidateSchema::proposedSchemaKey
        ));
        var rootKey = schemasById.get(candidate.rootCandidateSchemaId());
        var schemas = candidate.schemas().stream()
                .sorted(java.util.Comparator.comparing(CandidateSchema::proposedSchemaKey))
                .map(schema -> {
                    var root = schema.candidateSchemaId().equals(candidate.rootCandidateSchemaId())
                            ? "(root)" : "";
                    var fields = schema.fields().stream().map(field -> {
                        var value = switch (field.value().kind()) {
                            case ARRAY -> "ARRAY<" + valueShape(field.value().items(), schemasById) + ">";
                            default -> valueShape(field.value(), schemasById);
                        };
                        var evidence = field.assessment().evidence();
                        var topCoordinates = evidence.size() > 1
                                ? "@" + evidence.stream()
                                .map(item -> Integer.toString(item.boundingBox().top()))
                                .collect(java.util.stream.Collectors.joining(","))
                                : "";
                        return field.proposedFieldKey() + ":" + value + ":e" + evidence.size()
                                + topCoordinates;
                    }).collect(java.util.stream.Collectors.joining(","));
                    if (schema.proposedSchemaKey().equals("item")) {
                        return "schema=" + schema.proposedSchemaKey() + root + "[ " + fields + " ]";
                    }
                    return "schema=" + schema.proposedSchemaKey() + root + "[" + fields + "]";
                }).collect(java.util.stream.Collectors.joining(";"));
        return "root=" + rootKey + ";" + schemas;
    }

    private static String valueShape(
            CandidateValue value,
            Map<UUID, String> schemasById
    ) {
        if (value.kind() == CandidateValueKind.REFERENCE) {
            return "REFERENCE:" + schemasById.get(value.reference().candidateSchemaId());
        }
        return value.kind().name();
    }

    private void assertNoEphemeralObservationPayload(
            UUID runId,
            String textSentinel,
            String observationId,
            String policyIdentity
    ) {
        var payloads = jdbcClient.sql("""
                        select profile_snapshot::text as payload
                        from inference_run where run_id = :runId
                        union all
                        select checkpoint_json::text
                        from inference_run where run_id = :runId
                        union all
                        select data_json::text
                        from inference_run_event where run_id = :runId
                        union all
                        select original_json::text
                        from inference_candidate where run_id = :runId
                        union all
                        select current_json::text
                        from inference_candidate where run_id = :runId
                        union all
                        select validation_problems::text
                        from inference_candidate where run_id = :runId
                        union all
                        select row_to_json(attempt_row)::text
                        from (
                            select attempt_ordinal, stage, status, outcome_code,
                                   input_tokens, output_tokens, estimated_cost_micros_cny,
                                   duration_millis, problem_code_counts
                            from inference_attempt where run_id = :runId
                            order by attempt_ordinal
                        ) attempt_row
                        """)
                .param("runId", runId)
                .query(String.class)
                .list();
        assertThat(payloads).isNotEmpty().allSatisfy(payload -> assertThat(payload)
                .doesNotContain(textSentinel)
                .doesNotContain(observationId)
                .doesNotContain(DocumentObservationIR.VERSION)
                .doesNotContain(policyIdentity)
                .doesNotContain("data:image;base64"));
    }
}
